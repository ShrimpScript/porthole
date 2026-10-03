package transcript

import (
	"strings"
	"testing"
)

// The shapes below are copied from Claude Code 2.1.284 transcripts (a throwaway session
// on a private tmux server, 2026-10-02), trimmed to the fields the parser reads.

func TestACommandAndItsReplyAreOneRow(t *testing.T) {
	res := parse(t,
		`{"type":"user","isMeta":true,"message":{"content":"<local-command-caveat>The command below was run directly in Claude Code, not sent to you as a request.</local-command-caveat>"}}`,
		`{"type":"user","uuid":"u1","timestamp":"2026-10-02T23:40:00Z","message":{"content":"<command-name>/effort</command-name>\n            <command-message>effort</command-message>\n            <command-args>high</command-args>"}}`,
		`{"type":"user","message":{"content":"<local-command-stdout>Set effort level to high (saved as your default for new sessions): Comprehensive implementation with extensive testing and documentation</local-command-stdout>"}}`,
	)
	if len(res.Rows) != 1 {
		t.Fatalf("want one row, got %+v", res.Rows)
	}
	r := res.Rows[0]
	if r.Kind != KindCommand || r.Text != "/effort high" || r.ToolID != "cmd:u1" || r.Command == nil {
		t.Fatalf("row: %+v", r)
	}
	c := r.Command
	if c.Name != "effort" || c.Args != "high" || c.Value != "high" || !c.Saved || c.Error {
		t.Errorf("command: %+v", c)
	}
	if !strings.HasPrefix(c.Output, "Set effort level to high") || r.Detail != c.Output {
		t.Errorf("reply not attached: output %q detail %q", c.Output, r.Detail)
	}
	if res.State.Working {
		t.Error("a local command does not start a turn")
	}
}

func TestNewerCLIsWriteCommandsAsSystemRecords(t *testing.T) {
	res := parse(t,
		`{"type":"system","subtype":"local_command","uuid":"r1","content":"<command-name>/rename</command-name>\n            <command-message>rename</command-message>\n            <command-args>probe commands</command-args>"}`,
		`{"type":"system","subtype":"local_command","content":"<local-command-stdout>Session renamed to: probe commands</local-command-stdout>"}`,
		`{"type":"user","isMeta":true,"message":{"content":"<system-reminder>\nThe user named this session \"probe commands\".\n</system-reminder>"}}`,
		`{"type":"system","subtype":"local_command","uuid":"m1","content":"<command-name>/model</command-name>\n            <command-message>model</command-message>\n            <command-args></command-args>"}`,
		`{"type":"system","subtype":"local_command","content":"<local-command-stdout>Kept model as `+"`Fable 5.1`"+`</local-command-stdout>"}`,
		`{"type":"user","message":{"content":"<command-name>/effort</command-name><command-args></command-args>"}}`,
		`{"type":"user","message":{"content":"<local-command-stdout>Set effort level to max (this session only): Maximum capability</local-command-stdout>"}}`,
	)
	if len(res.Rows) != 3 {
		t.Fatalf("want three rows, got %+v", res.Rows)
	}
	if c := res.Rows[0].Command; c == nil || c.Name != "rename" || c.Value != "probe commands" {
		t.Errorf("rename: %+v", c)
	}
	if c := res.Rows[1].Command; c == nil || c.Name != "model" || c.Value != "Fable 5.1" || c.Saved {
		t.Errorf("model: %+v", c)
	}
	if c := res.Rows[2].Command; c == nil || c.Value != "max" || c.Saved {
		t.Errorf("effort for this session only: %+v", c)
	}
}

const contextMarkdown = "## Context Usage\n\n**Model:** claude-opus-5-5  \n**Tokens:** 27.1k / 1m (3%)\n\n" +
	"### Estimated usage by category\n\n| Category | Tokens | Percentage |\n|----------|--------|------------|\n" +
	"| System prompt | 2.4k | 0.2% |\n| System tools | 12.2k | 1.2% |\n| MCP tools (deferred) | 137.8k | 13.8% |\n" +
	"| Custom agents | 320 | 0.0% |\n| Messages | 10 | 0.0% |\n| Free space | 939.9k | 94.0% |\n| Autocompact buffer | 33k | 3.3% |\n\n" +
	"### MCP Tools\n\n| Tool | Server | Tokens |\n|------|--------|--------|\n| mcp__blender__set_texture | blender | 459 |\n"

func TestContextBecomesACardWithItsTable(t *testing.T) {
	md := strings.ReplaceAll(strings.ReplaceAll(contextMarkdown, "\n", `\n`), `"`, `\"`)
	res := parse(t,
		`{"type":"system","subtype":"local_command","uuid":"c1","content":"<command-name>/context</command-name>\n            <command-message>context</command-message>\n            <command-args></command-args>"}`,
		`{"type":"system","subtype":"local_command","content":"<local-command-stdout> \u001b[1mContext Usage\u001b[22m\n\u001b[38;5;244m⛀ ⛁\u001b[39m</local-command-stdout>"}`,
		`{"type":"user","isMeta":true,"message":{"content":"`+md+`"}}`,
	)
	if len(res.Rows) != 1 || res.Rows[0].Command == nil {
		t.Fatalf("want one command row (the table is not a message), got %+v", res.Rows)
	}
	r := res.Rows[0]
	ctx := r.Command.Context
	if ctx == nil || ctx.Model != "claude-opus-5-5" || ctx.Used != 27100 || ctx.Total != 1000000 {
		t.Fatalf("context: %+v", ctx)
	}
	want := []ContextPart{
		{"System prompt", 2400, ""}, {"System tools", 12200, ""}, {"MCP tools", 137800, "deferred"},
		{"Custom agents", 320, ""}, {"Messages", 10, ""}, {"Free space", 939900, "free"}, {"Autocompact buffer", 33000, "buffer"},
	}
	if len(ctx.Parts) != len(want) {
		t.Fatalf("parts: %+v", ctx.Parts)
	}
	for i, p := range want {
		if ctx.Parts[i] != p {
			t.Errorf("part %d = %+v, want %+v", i, ctx.Parts[i], p)
		}
	}
	if strings.Contains(r.Command.Output, "\u001b") {
		t.Errorf("terminal colours left in the output: %q", r.Command.Output)
	}
	if r.Detail != "" {
		t.Errorf("the context card is the whole of it; detail %q", r.Detail)
	}
}

func TestCompactIsTheCommandThenADivider(t *testing.T) {
	res := parse(t,
		`{"type":"user","uuid":"k1","timestamp":"2026-10-02T23:41:00Z","message":{"content":"/compact"}}`,
		`{"type":"system","subtype":"compact_boundary","content":"Conversation compacted","timestamp":"2026-10-02T23:41:11Z","compactMetadata":{"trigger":"manual","preTokens":39045,"postTokens":5594,"durationMs":10836}}`,
		`{"type":"user","isCompactSummary":true,"isVisibleInTranscriptOnly":true,"message":{"content":"This session is being continued from a previous conversation that ran out of context."}}`,
		`{"type":"user","isMeta":true,"message":{"content":"<local-command-caveat>The command below was run directly in Claude Code.</local-command-caveat>"}}`,
		`{"type":"user","message":{"content":"<command-name>/compact</command-name>\n            <command-message>compact</command-message>\n            <command-args></command-args>"}}`,
		`{"type":"user","message":{"content":"<local-command-stdout>\u001b[2mCompacted (ctrl+o to see full summary)\u001b[22m</local-command-stdout>"}}`,
	)
	if len(res.Rows) != 2 {
		t.Fatalf("want the command and the divider, got %+v", res.Rows)
	}
	if r := res.Rows[0]; r.Kind != KindCommand || r.Text != "/compact" {
		t.Errorf("first row: %+v", r)
	}
	d := res.Rows[1]
	if d.Kind != KindCommand || d.Command == nil || d.Command.Compact == nil {
		t.Fatalf("divider: %+v", d)
	}
	if cp := d.Command.Compact; cp.Trigger != "manual" || cp.Before != 39045 || cp.After != 5594 || cp.DurationMs != 10836 {
		t.Errorf("compaction: %+v", cp)
	}
	if res.State.Working {
		t.Error("a manual compaction is over once its boundary is written")
	}
}

func TestCompactingShowsAsWork(t *testing.T) {
	res := parse(t, `{"type":"user","timestamp":"2026-10-02T23:41:00Z","message":{"content":"/compact keep the API notes"}}`)
	if len(res.Rows) != 1 || res.Rows[0].Command == nil || res.Rows[0].Command.Args != "keep the API notes" {
		t.Fatalf("rows: %+v", res.Rows)
	}
	if !res.State.Working {
		t.Error("the CLI is busy compacting until the boundary lands")
	}
	if res.State.Prompts != 0 {
		t.Error("/compact is not a prompt")
	}
}

// Skill text the CLI writes as a meta user record is not something the person sent.
func TestSkillTextIsNotAMessage(t *testing.T) {
	res := parse(t,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Skill","input":{"skill":"release-notes"}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"Launching skill: release-notes"}]}}`,
		`{"type":"user","isMeta":true,"sourceToolUseID":"toolu_1","message":{"content":"Base directory for this skill: /skills/release-notes\n\n# release-notes"}}`,
		`{"type":"user","isMeta":true,"message":{"content":"<command-message>workflow-authoring</command-message>\n<command-name>workflow-authoring</command-name>\n<skill-format>true</skill-format>"}}`,
		`{"type":"user","isMeta":true,"message":{"content":[{"type":"text","text":"# Workflow authoring reference"}]}}`,
	)
	for _, r := range res.Rows {
		if r.Kind == KindUser {
			t.Fatalf("skill text rendered as a message: %+v", r)
		}
	}
	last := res.Rows[len(res.Rows)-1]
	if last.Command == nil || last.Command.Name != "workflow-authoring" || !last.Command.Skill {
		t.Errorf("skill command: %+v", last)
	}
}

// What else the CLI injects as a prompt still reads as one.
func TestAScheduledPromptStillReadsAsAPrompt(t *testing.T) {
	res := parse(t,
		`{"type":"system","subtype":"scheduled_task_fire","content":"Claude resuming /loop wakeup"}`,
		`{"type":"user","isMeta":true,"message":{"content":"Nightly check: run the tests"}}`,
	)
	if len(res.Rows) != 1 || res.Rows[0].Kind != KindUser {
		t.Fatalf("rows: %+v", res.Rows)
	}
}

func TestAReplyOnStderrIsAFailure(t *testing.T) {
	res := parse(t,
		`{"type":"user","message":{"content":"<command-name>/login</command-name>"}}`,
		`{"type":"user","message":{"content":"<local-command-stderr>Login interrupted</local-command-stderr>"}}`,
	)
	if len(res.Rows) != 1 || res.Rows[0].Command == nil || !res.Rows[0].Command.Error || res.Rows[0].Command.Output != "Login interrupted" {
		t.Fatalf("rows: %+v", res.Rows)
	}
}

func TestACommandTypedWhileBusyResolvesItsQueuedBubble(t *testing.T) {
	res := parse(t,
		`{"type":"queue-operation","operation":"enqueue","content":"/effort high"}`,
		`{"type":"user","message":{"content":"<command-name>/effort</command-name><command-args>high</command-args>"}}`,
	)
	if len(res.Rows) != 1 || res.Rows[0].Kind != KindCommand {
		t.Fatalf("rows: %+v", res.Rows)
	}
	if res.Stats.ByKind[string(KindQueued)] != 0 || res.Stats.ByKind[string(KindCommand)] != 1 {
		t.Errorf("counts: %v", res.Stats.ByKind)
	}
}

// The tailer can split a command from its reply; the reply then follows as a result row
// paired with the command by ToolID, and /context's table as a card of its own.
func TestAReplyInALaterBatchPairsByToolID(t *testing.T) {
	first, err := ParseFrom(strings.NewReader(`{"type":"user","uuid":"e1","message":{"content":"<command-name>/effort</command-name><command-args>low</command-args>"}}`), State{})
	if err != nil {
		t.Fatal(err)
	}
	second, err := ParseFrom(strings.NewReader(`{"type":"user","message":{"content":"<local-command-stdout>Set effort level to low (this session only): Quick</local-command-stdout>"}}`), first.State)
	if err != nil {
		t.Fatal(err)
	}
	if len(second.Rows) != 1 || second.Rows[0].Kind != KindResult || second.Rows[0].ToolID != first.Rows[0].ToolID {
		t.Fatalf("second batch: %+v", second.Rows)
	}

	md := strings.ReplaceAll(contextMarkdown, "\n", `\n`)
	a, _ := ParseFrom(strings.NewReader(`{"type":"system","subtype":"local_command","uuid":"c2","content":"<command-name>/context</command-name>"}`), State{})
	b, _ := ParseFrom(strings.NewReader(`{"type":"user","isMeta":true,"message":{"content":"`+md+`"}}`), a.State)
	if len(b.Rows) != 1 || b.Rows[0].Command == nil || b.Rows[0].Command.Context == nil || b.Rows[0].ToolID != "cmd:c2" {
		t.Fatalf("context in a later batch: %+v", b.Rows)
	}
}

func TestTokenCounts(t *testing.T) {
	for in, want := range map[string]int64{"27.1k": 27100, "1m": 1000000, "320": 320, "939.9k": 939900, "1.5M": 1500000, "x": 0} {
		if got := tokenCount(in); got != want {
			t.Errorf("tokenCount(%q) = %d, want %d", in, got, want)
		}
	}
}

// Only text that is visibly the command's own is taken as its body.
func TestAMessageRightAfterACommandIsStillAMessage(t *testing.T) {
	res := parse(t,
		`{"type":"user","message":{"content":"<command-name>/effort</command-name><command-args>high</command-args>"}}`,
		`{"type":"user","message":{"content":"<local-command-stdout>Set effort level to high (this session only): x</local-command-stdout>"}}`,
		`{"type":"user","isMeta":true,"origin":{"kind":"channel"},"message":{"content":"<channel source=\"phone\">are you there?</channel>"}}`,
	)
	if len(res.Rows) != 2 || res.Rows[1].Kind != KindUser {
		t.Fatalf("rows: %+v", res.Rows)
	}
}

func TestCompactInstructionsCanSpanLines(t *testing.T) {
	res := parse(t, `{"type":"user","message":{"content":"/compact keep\nthe API notes"}}`)
	if len(res.Rows) != 1 || res.Rows[0].Command == nil || res.Rows[0].Command.Args != "keep\nthe API notes" || res.State.Prompts != 0 {
		t.Fatalf("rows: %+v prompts %d", res.Rows, res.State.Prompts)
	}
}

// After an automatic compaction the next /compact the person types is still theirs: only
// the CLI's echo after a boundary is swallowed.
func TestACompactTypedAfterAnAutoCompactionShows(t *testing.T) {
	res := parse(t,
		`{"type":"user","message":{"content":"carry on"}}`,
		`{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"auto","preTokens":970000,"postTokens":13000}}`,
		`{"type":"user","isCompactSummary":true,"message":{"content":"This session is being continued."}}`,
		`{"type":"user","message":{"content":"[Request interrupted by user]"}}`,
		`{"type":"user","message":{"content":"/compact keep notes"}}`,
	)
	last := res.Rows[len(res.Rows)-1]
	if last.Command == nil || last.Command.Name != "compact" || last.Command.Args != "keep notes" {
		t.Fatalf("rows: %+v", res.Rows)
	}
}

func TestAModelInBoldStillReads(t *testing.T) {
	res := parse(t,
		`{"type":"user","message":{"content":"<command-name>/model</command-name>"}}`,
		`{"type":"user","message":{"content":"<local-command-stdout>Kept model as \u001b[1mOpus 5\u001b[22m</local-command-stdout>"}}`,
	)
	if c := res.Rows[0].Command; c == nil || c.Value != "Opus 5" || c.Saved {
		t.Fatalf("command: %+v", c)
	}
}

// A skill the person types is theirs and starts work (shape from 2.1.284: the command,
// then the skill's text as a meta record); one the CLI loads for a mode is marked Auto.
func TestATypedSkillAndOneTheCLILoads(t *testing.T) {
	res := parse(t,
		`{"type":"user","timestamp":"2026-10-03T06:00:00Z","message":{"content":"<command-message>probe-skill</command-message>\n<command-name>/probe-skill</command-name>\n<command-args>now</command-args>"}}`,
		`{"type":"user","isMeta":true,"timestamp":"2026-10-03T06:00:00Z","message":{"content":[{"type":"text","text":"Base directory for this skill: /srv/proj/.claude/skills/probe-skill\n\nReply with the single word: probed"}]}}`,
	)
	if len(res.Rows) != 1 {
		t.Fatalf("rows: %+v", res.Rows)
	}
	if c := res.Rows[0].Command; c == nil || c.Name != "probe-skill" || c.Args != "now" || !c.Skill || c.Auto {
		t.Errorf("typed skill: %+v", c)
	}
	if !res.State.Working {
		t.Error("a skill the person ran starts work")
	}
	auto := parse(t, `{"type":"user","isMeta":true,"message":{"content":"<command-message>workflow-authoring</command-message>\n<command-name>workflow-authoring</command-name>\n<skill-format>true</skill-format>"}}`)
	if c := auto.Rows[0].Command; c == nil || !c.Auto {
		t.Errorf("skill the CLI loaded: %+v", c)
	}
}
