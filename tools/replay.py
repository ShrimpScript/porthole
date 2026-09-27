#!/usr/bin/env python3
"""
replay.py - map a Claude Code transcript to Porthole feed rows.

The reference implementation for the daemon's transcript mapping
(daemon/internal/transcript, held to it by TestParityWithReplayPy): if a record
cannot be mapped to one of the feed's row types, the feed would have to invent
something the operator never did. So this prints a fidelity report as well
as the rendered feed.

    python3 tools/replay.py <transcript.jsonl> [--feed] [--limit N]
"""
import json
import re
import sys
import os
from collections import Counter

# ---- tool -> feed verb -------------------------------------------------------
# The feed says what happened in the user's language, not the tool's name.
VERBS = {
    "Read": "Read", "Edit": "Edited", "Write": "Wrote", "NotebookEdit": "Edited notebook",
    "Bash": "Ran", "Grep": "Searched", "Glob": "Globbed", "Task": "Delegated",
    "WebFetch": "Fetched", "WebSearch": "Searched the web", "TodoWrite": "Updated the plan",
    "Skill": "Loaded skill", "Artifact": "Published", "SendUserFile": "Sent a file",
}



# ---- user-record envelopes ---------------------------------------------------
# Not every `user` record is something a human typed. Claude Code injects system
# envelopes with role=user: background-task notifications, system reminders,
# slash-command echoes, local command output. Rendering those as user bubbles
# would show the operator messages they never sent.
ENVELOPES = (
    ("<task-notification>",   "silent", ""),                 # background task finished
    ("<system-reminder>",     "silent", ""),
    ("<local-command-caveat>", "silent", ""),                # newer CLIs wrap the caveat
    ("<local-command-stdout>", "silent", ""),
    ("<local-command-stderr>", "silent", ""),
    ("<command-message>",     "silent", ""),
    ("Caveat: The messages below", "silent", ""),
    ("<bash-input>",          "event", "You ran a shell command"),   # shell mode: ! cmd
    ("<bash-stdout>",         "silent", ""),
    ("<bash-stderr>",         "silent", ""),
    ("[Request interrupted by user", "event", "You interrupted"),   # also "... for tool use]"
    ("<command-name>",        "event", "You ran a command"),
    ("[Image:",               "event", "Image attached"),      # an image result, not typed text
)


def classify_user(txt):
    """(kind, display) for a user record: 'user' | 'event' | 'silent'."""
    t = txt.lstrip()
    for prefix, kind, label in ENVELOPES:
        if t.startswith(prefix):
            if kind == "event" and prefix == "<command-name>":
                name = t[len(prefix):].split("<", 1)[0].strip()
                return "event", f"You ran /{name}" if name else label
            if prefix == "<bash-input>":
                cmd = t[len(prefix):].split("</bash-input>", 1)[0].replace("\n", " ").strip()
                return "event", f"You ran ! {cmd[:60]}" if cmd else label
            return kind, label
    return "user", txt


def short(p, n=44):
    if not isinstance(p, str):
        return ""
    p = p.replace(os.path.expanduser("~"), "~")
    return p if len(p) <= n else "…" + p[-(n - 1):]


def target(tool, inp):
    if not isinstance(inp, dict):
        return ""
    for k in ("file_path", "notebook_path", "path"):
        if k in inp:
            return short(inp[k])
    if tool == "Bash":
        c = (inp.get("command") or "").strip().replace("\n", " ")
        return c if len(c) <= 60 else c[:59] + "…"
    if tool in ("Grep", "Glob"):
        return inp.get("pattern", "")
    if tool == "Task":
        return inp.get("description", "")
    if tool in ("WebFetch", "WebSearch"):
        return short(inp.get("url") or inp.get("query", ""))
    if tool == "Skill":
        return inp.get("skill", "")
    return ""


def result_row(content):
    """A tool_result -> (glyph, summary, metric). Never invents an outcome."""
    text = ""
    if isinstance(content, str):
        text = content
    elif isinstance(content, list):
        text = "\n".join(b.get("text", "") for b in content if isinstance(b, dict))
    lines = text.count("\n") + 1 if text else 0
    low = text.lower()
    # "0 failed", "no errors", "failures: 0" are not failures (mirrors looksFailed in Go)
    low = re.sub(r"(?i)\b(0 (failed|failures|errors?)|no (errors?|failures)|(failed|failures|errors?)[:=]\s*0)\b", "", low[:200])
    if "error" in low or "failed" in low:
        return "✗", "failed", (text.strip().split("\n") or [""])[0][:48]
    if not text.strip():
        return "✓", "done", ""
    return "✓", "done", f"{lines} lines" if lines > 1 else f"{len(text)} chars"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = sys.argv[1]
    show_feed = "--feed" in sys.argv
    as_json = "--json" in sys.argv
    limit = 0
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])

    kinds = Counter()      # every top-level record type seen
    mapped = Counter()     # feed row types produced
    unmapped = Counter()   # record types that produced no row
    rows = []
    meta = {}
    queued_keys = {}   # queued prompt -> row index, so delivery resolves it

    for line in open(path, errors="replace"):
        line = line.strip()
        if not line:
            continue
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            kinds["<malformed>"] += 1
            continue

        t = d.get("type", "?")
        kinds[t] += 1
        for k in ("cwd", "gitBranch", "sessionId", "version"):
            if d.get(k):
                meta[k] = d[k]
                meta[{"cwd": "cwd", "gitBranch": "git_branch",
                      "sessionId": "session_id", "version": "version"}[k]] = d[k]
        # ai-title / agent-name carry a human session name - the sessions list
        # should show this, not the directory basename.
        if t in ("ai-title", "agent-name"):
            meta["title"] = d.get("title") or d.get("agentName") or meta.get("title")

        msg = d.get("message") or {}
        content = msg.get("content")

        if t == "user":
            # Either a real prompt, a tool_result envelope, or an injected system
            # envelope wearing a user role.
            txt = ""
            if isinstance(content, list):
                got = False
                for b in content:
                    if isinstance(b, dict) and b.get("type") == "tool_result":
                        g, sm, m = result_row(b.get("content"))
                        rows.append(("result", g, sm, m))
                        mapped["result"] += 1
                        got = True
                        # an image Claude looked at (mirrors transcript.go KindImage)
                        for x in (b.get("content") if isinstance(b.get("content"), list) else []):
                            if isinstance(x, dict) and x.get("type") == "image":
                                rows.append(("image", "", "Image Claude looked at", ""))
                                mapped["image"] += 1
                    elif isinstance(b, dict) and b.get("type") == "image":
                        rows.append(("image", "", "Image you sent", ""))
                        mapped["image"] += 1
                if got:
                    continue
                txt = " ".join(b.get("text", "") for b in content
                               if isinstance(b, dict) and b.get("type") == "text").strip()
            elif isinstance(content, str):
                txt = content.strip()

            if not txt:
                mapped["(not a feed row)"] += 1
                continue

            kind, display = classify_user(txt)
            if kind == "silent":
                mapped["(injected envelope, not shown)"] += 1
            elif kind == "event":
                rows.append(("event", "•", display, ""))
                mapped["session event"] += 1
            else:
                flat = display.replace("\n", " ")
                # If this prompt was queued earlier, the pending bubble resolves into
                # this one rather than appearing twice.
                key = flat[:60]
                if key in queued_keys:
                    idx = queued_keys.pop(key)
                    rows[idx] = ("user", "", display, "")
                    mapped["queued user message"] -= 1
                    mapped["user message"] += 1
                else:
                    rows.append(("user", "", display, ""))
                    mapped["user message"] += 1

        elif t == "assistant":
            if not isinstance(content, list):
                unmapped["assistant/other"] += 1
                continue
            for b in content:
                if not isinstance(b, dict):
                    continue
                bt = b.get("type")
                if bt == "text":
                    txt = (b.get("text") or "").strip()
                    if txt:
                        rows.append(("assistant", "", txt, ""))
                        mapped["assistant text"] += 1
                elif bt == "tool_use" and b.get("name") == "AskUserQuestion":
                    # Claude asking the person to choose: its own kind, with the choices.
                    qs = (b.get("input") or {}).get("questions") or []
                    q0 = qs[0] if qs and isinstance(qs[0], dict) else {}
                    rows.append(("question", "?", (q0.get("question") or "").strip(), ""))
                    mapped["question"] += 1
                elif bt == "tool_use":
                    name = b.get("name", "?")
                    verb = VERBS.get(name, name)
                    rows.append(("tool", "▸", f"{verb} {target(name, b.get('input'))}".strip(), ""))
                    mapped["tool call"] += 1
                    if name == "SendUserFile" and isinstance(b.get("input"), dict):
                        for f in (b["input"].get("files") or []):
                            if isinstance(f, str) and f.lower().rsplit(".", 1)[-1] in ("png", "jpg", "jpeg", "webp", "gif"):
                                rows.append(("image", "", f.rsplit("/", 1)[-1], ""))
                                mapped["image"] += 1
                elif bt == "thinking":
                    mapped["(thinking, not shown)"] += 1
                else:
                    unmapped[f"assistant/{bt}"] += 1

        elif t == "queue-operation":
            # A prompt typed while Claude was busy. It IS user content: if the feed
            # drops it, a message sent from the phone silently vanishes until it is
            # picked up. Renders as a pending user bubble.
            c = (d.get("content") or "").strip()
            if d.get("operation") == "enqueue" and c:
                k2, disp = classify_user(c)
                if k2 != "user":
                    mapped["(injected envelope, not shown)"] += 1
                    continue
                flat = disp.replace("\n", " ")
                queued_keys[flat[:60]] = len(rows)
                rows.append(("queued", "⋯", disp, "queued"))
                mapped["queued user message"] += 1
            else:
                mapped["(not a feed row)"] += 1

        elif t == "system" and d.get("subtype") == "turn_duration":
            ms = int(d.get("durationMs") or 0)
            if ms < 10_000:
                dur = f"{ms/1000:.1f}s"
            elif ms < 60_000:
                dur = f"{ms//1000}s"
            elif ms < 3_600_000:
                dur = f"{ms//60000}m {(ms//1000)%60}s"
            else:
                dur = f"{ms//3_600_000}h {(ms//60000)%60}m"
            rows.append(("turn", "✻", f"Worked for {dur}", ""))
            mapped["turn summary"] += 1

        elif t in ("system", "attachment", "file-history-snapshot", "mode",
                   "permission-mode", "last-prompt", "ai-title", "bridge-session",
                   "atis-latch", "file-history-delta", "agent-name", "frame-link",
                   "artifact-autoreact-ledger", "artifact-comment-monitor",
                   "cost-state"):
            # Real records that are correctly NOT feed rows. Silence is the honest render.
            mapped["(not a feed row)"] += 1
        else:
            unmapped[t] += 1

    total_rows = sum(v for k, v in mapped.items() if not k.startswith("("))

    if as_json:
        # Machine-readable stats, so the Go port can be held to parity with this file.
        by_kind = {k: v for k, v in mapped.items() if not k.startswith("(")}
        json.dump({
            "records": sum(kinds.values()),
            "rows": total_rows,
            "by_kind": by_kind,
            "unmapped": dict(unmapped),
            "meta": {k: meta.get(k, "") for k in ("session_id", "cwd", "git_branch")},
        }, sys.stdout, sort_keys=True)
        print()
        return

    print(f"transcript : {os.path.basename(path)}")
    for k in ("title", "sessionId", "cwd", "gitBranch", "version"):
        if k in meta:
            print(f"{k:11}: {meta[k]}")
    print(f"records    : {sum(kinds.values())}   feed rows: {total_rows}")
    print()
    print("record types seen")
    for k, v in kinds.most_common():
        print(f"  {k:26} {v:5}")
    print()
    print("mapped to")
    for k, v in mapped.most_common():
        print(f"  {k:26} {v:5}")
    if unmapped:
        print()
        print("UNMAPPED  (each one is a feed row that would have to be invented)")
        for k, v in unmapped.most_common():
            print(f"  {k:26} {v:5}")
    else:
        print()
        print("UNMAPPED  none - every record either renders or is deliberately silent")

    if show_feed:
        print("\n" + "─" * 66)
        out = rows[-limit:] if limit else rows
        for kind, glyph, text, metric in out:
            if kind == "event":
                print(f"  \033[2m• {text}\033[0m")
            elif kind == "queued":
                print(f"{'':>18}\033[2m {text[:44]}  ⋯queued \033[0m")
            elif kind == "user":
                print(f"{'':>18}\033[7m {text[:44]} \033[0m")
            elif kind == "assistant":
                print(f"  {text}")
            elif kind == "tool":
                print(f"  {glyph} {text}")
            else:
                print(f"  {glyph} {text:<30} {metric:>24}")


if __name__ == "__main__":
    main()
