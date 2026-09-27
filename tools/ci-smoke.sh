#!/usr/bin/env bash
# End-to-end smoke test on a real Linux or macOS machine, for CI.
#
# It uses the machine's default tmux server and, on a Mac, installs a launchd agent, so it
# refuses to run anywhere but CI - on your own computer those are your sessions.
#
#   CI=true ./tools/ci-smoke.sh
set -euo pipefail
[ "${CI:-}" = true ] || { echo "ci-smoke.sh runs only in CI (it uses the default tmux server)" >&2; exit 2; }

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OS=$(uname -s)
WORK="$HOME/porthole-smoke"
rm -rf "$WORK"; mkdir -p "$WORK/bin" "$WORK/proj"
export CLAUDE_CONFIG_DIR="$WORK/claude"
export PORTHOLE_STATE_DIR="$WORK/state"
export PORTHOLE_SOCK="$WORK/portholed.sock"
mkdir -p "$CLAUDE_CONFIG_DIR/sessions" "$PORTHOLE_STATE_DIR"
unset TMUX
pass() { printf 'ok    %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*" >&2; exit 1; }

# 1. The daemon, under both of its names.
env -C "$REPO/daemon" CGO_ENABLED=0 go build -trimpath -o "$WORK/bin/portholed" ./cmd/portholed
ln -sf "$WORK/bin/portholed" "$WORK/bin/porthole"
export PATH="$WORK/bin:$PATH"
pass "built portholed for $OS"

# 2. A stand-in claude: records how it was called and stays alive like the real one.
cat > "$WORK/bin/claude" <<'SH'
#!/bin/sh
[ "$1" = --version ] && { echo "2.0.0 (Claude Code)"; exit 0; }
printf '%s\n' "$*" > "$HOME/porthole-smoke/claude-args"
echo $$ > "$HOME/porthole-smoke/claude-pid"
exec sleep 600
SH
chmod +x "$WORK/bin/claude"

# 3. porthole starts Claude Code inside tmux, named after the folder. Attaching needs a
#    terminal CI does not have; the session is what is being tested.
(cd "$WORK/proj" && porthole --model "it's fine" </dev/null >/dev/null 2>&1 || true)
for _ in $(seq 1 50); do [ -s "$WORK/claude-pid" ] && break; sleep 0.2; done
[ -s "$WORK/claude-pid" ] || fail "porthole did not start claude inside tmux"
[ "$(cat "$WORK/claude-args")" = "--model it's fine" ] || fail "claude got: $(cat "$WORK/claude-args")"
tmux has-session -t =proj || fail "no tmux session named after the folder"
pass "porthole started claude in tmux session 'proj' with its arguments intact"

# 4. A registry entry exactly as Claude Code writes one on this OS, and a transcript.
PID=$(cat "$WORK/claude-pid")
if [ "$OS" = Linux ]; then
    START=$(awk '{ sub(/.*\) /, ""); print $20 }' "/proc/$PID/stat")
else
    START=$(LC_ALL=C TZ=UTC ps -o lstart= -p "$PID" | sed 's/^ *//; s/ *$//')
fi
REF=$(tmux display-message -p -t =proj: '#{session_name}:#{window_id}.#{pane_id}')
CWD=$(cd "$WORK/proj" && pwd -P)
cat > "$CLAUDE_CONFIG_DIR/sessions/$PID.json" <<JSON
{"pid":$PID,"sessionId":"smoke-1","cwd":"$CWD","startedAt":1790000000000,"procStart":"$START","kind":"interactive","tmux":"$REF","name":"smoke","status":"idle"}
JSON
SLUG=$(printf '%s' "$CWD" | sed 's/[^A-Za-z0-9]/-/g')
mkdir -p "$CLAUDE_CONFIG_DIR/projects/$SLUG"
cat > "$CLAUDE_CONFIG_DIR/projects/$SLUG/smoke-1.jsonl" <<JSON
{"type":"user","sessionId":"smoke-1","cwd":"$CWD","timestamp":"2026-09-27T10:00:00Z","message":{"role":"user","content":"Check the smoke test"}}
{"type":"assistant","sessionId":"smoke-1","cwd":"$CWD","timestamp":"2026-09-27T10:00:05Z","message":{"model":"claude-opus-5","role":"assistant","content":[{"type":"text","text":"Done."}],"stop_reason":"end_turn"}}
{"type":"ai-title","sessionId":"smoke-1","aiTitle":"Smoke test session"}
JSON
LIST=$(portholed sessions)
echo "$LIST"
echo "$LIST" | grep -E '^live +Smoke test session' >/dev/null || fail "the session is not listed as live"
pass "the session is live: its process start time matches the registry on $OS"

# 5. doctor runs to the end (Tailscale is not on a CI machine, so it may report failures).
portholed doctor || true
pass "doctor ran"

# 6. The daemon itself, on loopback in development mode, answering its control socket.
portholed serve -dev-listen 127.0.0.1:18737 > "$WORK/serve.log" 2>&1 &
SERVE=$!
for _ in $(seq 1 50); do portholed status >/dev/null 2>&1 && break; sleep 0.2; done
portholed status || { cat "$WORK/serve.log"; fail "the daemon did not answer"; }
kill "$SERVE"; wait "$SERVE" 2>/dev/null || true
pass "the daemon served and answered on its control socket"

# 7. The background service: a launchd agent on a Mac. It cannot stay up here (there is
#    no Tailscale to bind), so what is checked is that launchd accepts and removes it.
if [ "$OS" = Darwin ]; then
    PLIST="$HOME/Library/LaunchAgents/dev.shrimpscript.portholed.plist"
    portholed service install || true
    [ -f "$PLIST" ] || fail "no launchd agent written"
    plutil -lint "$PLIST" || fail "the agent is not a valid plist"
    launchctl print "gui/$(id -u)/dev.shrimpscript.portholed" >/dev/null || fail "launchd did not load the agent"
    portholed service status || true
    portholed service uninstall
    [ ! -f "$PLIST" ] || fail "uninstall left the agent behind"
    pass "the launchd agent installs, loads and uninstalls"
fi

# Only a server this test owns is stopped: in CI that is the machine's default one, and
# anywhere TMUX_TMPDIR points elsewhere, only a server whose socket is in there.
SOCK=$(tmux display-message -p '#{socket_path}' 2>/dev/null || true)
if [ -z "${TMUX_TMPDIR:-}" ] || [ "${SOCK#"$TMUX_TMPDIR"}" != "$SOCK" ]; then
    tmux kill-server 2>/dev/null || true
fi
rm -rf "$WORK"
echo "smoke test passed on $OS"
