package transcript

import (
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Slash commands run at the desk, as the CLI records them (measured on 2.1.284):
//
//   - a caveat (silent), then the command: a user record or, in newer builds, a system
//     local_command record holding <command-name>/effort</command-name>
//     <command-message>effort</command-message><command-args>high</command-args>;
//   - then its reply, the same two ways: <local-command-stdout>Set effort level to high
//     (saved as your default for new sessions): ...</local-command-stdout>;
//   - /context also writes its table as Markdown, in a meta user record ("## Context Usage");
//   - a skill run as a command writes the skill's text in a meta user record;
//   - /compact writes the typed line as a plain user record, then a compact_boundary with
//     the token counts, the summary (isCompactSummary), and only then its echo and
//     "Compacted".
//
// Each command is one row of kind "command" with its reply attached. The reply lands in
// the same batch as the command in practice (the tailer reads a burst at once); when a
// batch does split them, the reply follows as a result row carrying the command's ToolID.

// Command is a slash command run in the session, and what the CLI said back.
type Command struct {
	Name   string `json:"name"` // without the slash
	Args   string `json:"args,omitempty"`
	Output string `json:"output,omitempty"` // the CLI's reply, without terminal colours
	Error  bool   `json:"error,omitempty"`  // the reply came on stderr
	// Value is what the command set, read from its reply: the effort level, the model,
	// the session's new name. Saved says the CLI also made it the default for new sessions.
	Value string `json:"value,omitempty"`
	Saved bool   `json:"saved,omitempty"`
	Skill bool   `json:"skill,omitempty"` // the command ran a skill
	// Auto: the CLI ran it on its own (a skill it loads for a mode), not the person.
	Auto    bool          `json:"auto,omitempty"`
	Context *ContextUsage `json:"context,omitempty"`
	Compact *Compaction   `json:"compact,omitempty"`
}

// ContextUsage is /context's table: what fills the context window, by category.
type ContextUsage struct {
	Model string        `json:"model,omitempty"`
	Used  int64         `json:"used"`
	Total int64         `json:"total"`
	Parts []ContextPart `json:"parts,omitempty"`
}

type ContextPart struct {
	Name   string `json:"name"`
	Tokens int64  `json:"tokens"`
	// Kind is "" for what is in the window, "deferred" for tools listed but not loaded
	// (the CLI leaves them out of the total), "free", or "buffer" (held for autocompact).
	Kind string `json:"kind,omitempty"`
}

// Compaction is a compact_boundary: the conversation was summarised to free the window.
type Compaction struct {
	Trigger    string `json:"trigger,omitempty"` // "manual" or "auto"
	Before     int64  `json:"before,omitempty"`  // tokens
	After      int64  `json:"after,omitempty"`
	DurationMs int64  `json:"duration_ms,omitempty"`
}

// cmdTrack follows the latest command across batches, so its reply and body find it.
type cmdTrack struct {
	open    bool
	name    string
	toolID  string
	row     int // its row in the current batch; -1 when an earlier batch sent it
	replied bool
	bodied  bool
	skill   bool // a skill-format command: the text after it is the skill's
	// compacted: a compact_boundary row was just written, so the /compact echo that
	// follows it (and its "Compacted") is already on screen.
	compacted bool
}

var (
	cmdName     = regexp.MustCompile(`<command-name>\s*/?([^<]*?)\s*</command-name>`)
	cmdArgs     = regexp.MustCompile(`(?s)<command-args>(.*?)</command-args>`)
	cmdReply    = regexp.MustCompile(`(?s)^<local-command-(stdout|stderr)>(.*?)(?:</local-command-(?:stdout|stderr)>|$)`)
	ansi        = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]`)
	compactLine = regexp.MustCompile(`(?s)^/compact(?:\s+(.*))?$`)
)

// commandText reads a command echo: its name without the slash, and its arguments.
func commandText(t string) (name, args string, skill, ok bool) {
	t = strings.TrimSpace(t)
	if !strings.HasPrefix(t, "<command-name>") && !strings.HasPrefix(t, "<command-message>") {
		return "", "", false, false
	}
	m := cmdName.FindStringSubmatch(t)
	if m == nil || strings.TrimSpace(m[1]) == "" {
		return "", "", false, false
	}
	if a := cmdArgs.FindStringSubmatch(t); a != nil {
		args = strings.TrimSpace(a[1])
	}
	return strings.TrimSpace(m[1]), args, strings.Contains(t, "<skill-format>true"), true
}

// commandRow adds the row for a command, or swallows the echo /compact writes after its
// boundary row.
func (res *Result) commandRow(name, args string, skill, auto bool, ts time.Time, uuid string, queued map[string]int) {
	st := &res.State
	if name == "compact" && st.cmd.compacted {
		st.cmd = cmdTrack{open: true, name: name, row: -1, replied: true, bodied: true}
		res.Stats.Silent++
		return
	}
	text := "/" + name
	if args != "" {
		text += " " + args
	}
	id := "cmd:" + uuid
	if uuid == "" {
		id = "cmd:" + ts.Format(time.RFC3339Nano) + ":" + text
	}
	row := Row{Kind: KindCommand, Glyph: "/", Text: text, TS: ts, ToolID: id,
		Command: &Command{Name: name, Args: args, Skill: skill, Auto: auto}}
	st.cmd = cmdTrack{open: true, name: name, toolID: id, row: len(res.Rows), skill: skill}
	// Typed while Claude was busy, it was queued as text: the pending bubble becomes this.
	key := truncRunes(strings.ReplaceAll(text, "\n", " "), 60)
	if idx, ok := queued[key]; ok && idx < len(res.Rows) {
		delete(queued, key)
		res.Rows[idx] = row
		res.Stats.ByKind[string(KindQueued)]--
		res.Stats.ByKind[string(KindCommand)]++
		st.cmd.row = idx
		return
	}
	res.add(row)
}

// commandReply attaches a command's stdout or stderr to its row.
func (res *Result) commandReply(stream, body string, ts time.Time) {
	st := &res.State
	out := strings.TrimSpace(ansi.ReplaceAllString(body, ""))
	if !st.cmd.open || st.cmd.replied || out == "" {
		res.Stats.Silent++
		return
	}
	st.cmd.replied = true
	failed := stream == "stderr"
	detail, trunc := out, false
	if len([]rune(detail)) > DetailCap {
		detail, trunc = truncRunes(detail, DetailCap), true
	}
	if r := res.cmdRow(); r != nil {
		c := *r.Command
		c.Output, c.Error = truncRunes(out, 2000), failed
		readValue(&c)
		r.Command = &c
		if c.Name != "context" { // the context card is the whole of it
			r.Detail, r.Truncated = detail, trunc
		}
		res.Stats.Silent++
		return
	}
	g := "✓"
	if failed {
		g = "✗"
	}
	res.add(Row{Kind: KindResult, Glyph: g, Text: flat(out, 80), TS: ts, ToolID: st.cmd.toolID, Detail: detail, Truncated: trunc})
}

// commandBody takes the meta record a command writes after itself: /context's table, a
// skill's text. Reports false for anything else, which then reads as before: a message
// from another channel that lands right after a command is still the person's.
func (res *Result) commandBody(txt string, ts time.Time) bool {
	st := &res.State
	if !st.cmd.open || st.cmd.bodied {
		return false
	}
	if !st.cmd.skill && !cliText(txt) {
		return false
	}
	st.cmd.bodied = true
	res.Stats.Silent++
	r := res.cmdRow()
	if strings.HasPrefix(txt, "## Context Usage") {
		ctx := parseContext(txt)
		if ctx == nil {
			return true
		}
		if r != nil {
			c := *r.Command
			c.Context = ctx
			r.Command = &c
			return true
		}
		// The command went in an earlier batch: the table comes as its own card.
		res.Stats.Silent--
		res.add(Row{Kind: KindCommand, Glyph: "/", Text: "/context", TS: ts, ToolID: st.cmd.toolID,
			Command: &Command{Name: "context", Context: ctx}})
		return true
	}
	if strings.HasPrefix(txt, "Base directory for this skill") {
		if r != nil {
			c := *r.Command
			c.Skill = true
			r.Command = &c
		}
		// A skill the person ran: Claude now works on it.
		st.startWorking(ts)
	}
	return true
}

// cliText reports text the CLI writes for itself after a command or a tool (/context's
// table, a skill's instructions), which is not a prompt even in a user record.
func cliText(txt string) bool {
	t := strings.TrimSpace(txt)
	return strings.HasPrefix(t, "## Context Usage") || strings.HasPrefix(t, "Base directory for this skill")
}

// cmdRow is the open command's row when it is in this batch.
func (res *Result) cmdRow() *Row {
	i := res.State.cmd.row
	if i < 0 || i >= len(res.Rows) || res.Rows[i].Command == nil || res.Rows[i].ToolID != res.State.cmd.toolID {
		return nil
	}
	return &res.Rows[i]
}

// compactRow is a compact_boundary: a divider with what the summary freed.
func (res *Result) compactRow(m *compactMeta, ts time.Time) {
	cp := &Compaction{}
	if m != nil {
		cp = &Compaction{Trigger: m.Trigger, Before: m.PreTokens, After: m.PostTokens, DurationMs: m.DurationMs}
	}
	res.add(Row{Kind: KindCommand, Glyph: "/", Text: "Conversation compacted", TS: ts,
		Command: &Command{Name: "compact", Compact: cp}})
	if cp.Trigger != "auto" {
		res.State.stopWorking() // an auto compaction happens mid-turn; a manual one ends here
	}
	res.State.cmd = cmdTrack{compacted: true, row: -1}
}

type compactMeta struct {
	Trigger    string `json:"trigger"`
	PreTokens  int64  `json:"preTokens"`
	PostTokens int64  `json:"postTokens"`
	DurationMs int64  `json:"durationMs"`
}

var (
	effortSet = regexp.MustCompile(`^Set effort level to (\w+)`)
	// "Set model to `Fable 5.1` and saved as ...", "Kept model as Opus 5" (bold, once the
	// colours are gone).
	modelSet = regexp.MustCompile(`^(?:Set model to|Kept model as)\s+(.+?)(?:\s+and saved as your default.*)?$`)
	renamed  = regexp.MustCompile(`^Session renamed to:\s*(.+)`)
)

// readValue reads what a command set from its reply, for the commands whose card shows it.
func readValue(c *Command) {
	out := c.Output
	switch c.Name {
	case "effort":
		if m := effortSet.FindStringSubmatch(out); m != nil {
			c.Value = m[1]
		}
	case "model":
		if m := modelSet.FindStringSubmatch(out); m != nil {
			c.Value = strings.TrimSpace(strings.Trim(m[1], "`"))
		}
	case "rename":
		if m := renamed.FindStringSubmatch(out); m != nil {
			c.Value = strings.TrimSpace(m[1])
		}
	}
	c.Saved = c.Value != "" && strings.Contains(out, "saved as your default")
}

var (
	ctxModel  = regexp.MustCompile(`\*\*Model:\*\*\s*(\S+)`)
	ctxTokens = regexp.MustCompile(`\*\*Tokens:\*\*\s*([\d.]+[kKmMbB]?)\s*/\s*([\d.]+[kKmMbB]?)`)
	ctxRow    = regexp.MustCompile(`^\|\s*([^|]+?)\s*\|\s*([\d.]+[kKmMbB]?)\s*\|\s*[\d.]+%\s*\|$`)
)

// parseContext reads /context's Markdown: the model, tokens used of the window, and the
// "Estimated usage by category" table. Nil when it holds none of that.
func parseContext(md string) *ContextUsage {
	c := &ContextUsage{}
	if m := ctxModel.FindStringSubmatch(md); m != nil {
		c.Model = m[1]
	}
	if m := ctxTokens.FindStringSubmatch(md); m != nil {
		c.Used, c.Total = tokenCount(m[1]), tokenCount(m[2])
	}
	sec := ""
	if i := strings.Index(md, "### Estimated usage by category"); i >= 0 {
		sec = md[i+3:]
		if j := strings.Index(sec, "\n### "); j >= 0 {
			sec = sec[:j]
		}
	}
	for _, line := range strings.Split(sec, "\n") {
		m := ctxRow.FindStringSubmatch(strings.TrimSpace(line))
		if m == nil {
			continue
		}
		p := ContextPart{Name: m[1], Tokens: tokenCount(m[2])}
		switch {
		case p.Name == "Free space":
			p.Kind = "free"
		case p.Name == "Autocompact buffer":
			p.Kind = "buffer"
		case strings.HasSuffix(p.Name, "(deferred)"):
			p.Kind, p.Name = "deferred", strings.TrimSpace(strings.TrimSuffix(p.Name, "(deferred)"))
		}
		c.Parts = append(c.Parts, p)
	}
	if c.Total == 0 && len(c.Parts) == 0 {
		return nil
	}
	return c
}

// tokenCount reads the CLI's "27.1k", "1m", "320".
func tokenCount(s string) int64 {
	s = strings.ToLower(strings.TrimSpace(s))
	mult := 1.0
	switch {
	case strings.HasSuffix(s, "k"):
		mult, s = 1e3, strings.TrimSuffix(s, "k")
	case strings.HasSuffix(s, "m"):
		mult, s = 1e6, strings.TrimSuffix(s, "m")
	case strings.HasSuffix(s, "b"):
		mult, s = 1e9, strings.TrimSuffix(s, "b")
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0
	}
	return int64(f*mult + 0.5)
}
