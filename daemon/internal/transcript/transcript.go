// Package transcript turns a Claude Code session transcript into Porthole feed rows.
//
// This is a port of tools/replay.py, which was verified against 46,842 records across 28
// real transcripts with zero unmapped types. The two implementations are held to
// agreement by TestParityWithReplayPy, because the rule that matters is that every row
// comes from a record, and a record that cannot be mapped is rendered silently rather
// than invented.
package transcript

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

type Kind string

const (
	KindUser      Kind = "user"
	KindAssistant Kind = "assistant"
	KindTool      Kind = "tool"
	KindResult    Kind = "result"
	KindQueued    Kind = "queued"
	KindEvent     Kind = "event"
	// KindTurn is the CLI's "Cooked for 1m 6s · done 6:05 PM" line, from its own
	// turn_duration record. Text is the duration; the app adds the clock from TS.
	KindTurn Kind = "turn"
	// KindImage is a picture in the conversation: one Claude looked at (a tool result
	// carrying an image block), one the person pasted, or one Claude sent as a file.
	// ImageRef says where the bytes live; the app fetches them on demand.
	KindImage Kind = "image"
	// KindQuestion is Claude asking the person to choose (the AskUserQuestion tool).
	// Questions carries the choices; the result row with the same ToolID is the answer.
	KindQuestion Kind = "question"
	// KindCommand is a slash command run at the desk, with the CLI's reply attached
	// (see commands.go), or a compaction divider.
	KindCommand Kind = "command"
)

// Question is one question from an AskUserQuestion call, as the CLI shows it: a picker
// with numbered options, "Type something" after them.
type Question struct {
	Header      string   `json:"header,omitempty"`
	Text        string   `json:"text"`
	MultiSelect bool     `json:"multi,omitempty"`
	Kind        string   `json:"kind,omitempty"` // "choice" (default), "text" or "number": the latter two have no options
	Options     []Option `json:"options"`
}

type Option struct {
	Label       string `json:"label"`
	Description string `json:"description,omitempty"`
}

type Row struct {
	Kind   Kind      `json:"kind"`
	Glyph  string    `json:"glyph,omitempty"`
	Text   string    `json:"text"`
	Metric string    `json:"metric,omitempty"`
	TS     time.Time `json:"ts,omitempty"`
	// Detail is the full text behind a row, for the drill-in view. Capped, because
	// long output is never inlined in the feed and a tool result can be megabytes.
	Detail    string `json:"detail,omitempty"`
	Truncated bool   `json:"truncated,omitempty"`
	// ToolID pairs a tool row with its result row, so the app can show a call as
	// running until its result arrives.
	ToolID string `json:"tool_id,omitempty"`
	// ImageRef locates an image row's bytes: "<record uuid>:<n>" for the n-th image
	// block in that transcript record, or "file:<absolute path>" for a file Claude sent.
	ImageRef string `json:"image_ref,omitempty"`
	Media    string `json:"media,omitempty"`
	// Questions is set on a question row: what Claude asked and the choices offered.
	Questions []Question `json:"questions,omitempty"`
	// Agent is set on the row of an Agent (or Task) call: the subagent it started. Its
	// progress arrives separately, in session.agents frames, matched by ToolID.
	Agent *AgentCall `json:"agent,omitempty"`
	// Command is set on a command row: which command, and what it said back.
	Command *Command `json:"command,omitempty"`
}

// AgentCall is what an Agent call asked for, from its input.
type AgentCall struct {
	Type        string `json:"type,omitempty"` // subagent_type
	Description string `json:"description,omitempty"`
	Model       string `json:"model,omitempty"`
	Background  bool   `json:"background,omitempty"`
}

// Usage is token accounting summed over assistant messages.
type Usage struct {
	Input      int64 `json:"input"`
	Output     int64 `json:"output"`
	CacheRead  int64 `json:"cache_read"`
	CacheWrite int64 `json:"cache_write"`
	Thinking   int64 `json:"thinking"`
}

func (u *Usage) add(o Usage) {
	u.Input += o.Input
	u.Output += o.Output
	u.CacheRead += o.CacheRead
	u.CacheWrite += o.CacheWrite
	u.Thinking += o.Thinking
}

// State is what the feed needs to know about a session beyond its rows: whether Claude
// is working right now, on which tool, with which model, and what it has cost so far.
// Every field is derived from records; nothing here is a guess. It carries across
// incremental parses (see ParseFrom) so the tailer keeps one running picture.
type State struct {
	Model          string `json:"model,omitempty"`
	PermissionMode string `json:"permission_mode,omitempty"`
	// Working is true from a real prompt (or a tool result) until the assistant ends
	// its turn, is interrupted, or a turn_duration record lands.
	Working      bool      `json:"working"`
	WorkingSince time.Time `json:"working_since,omitempty"`
	// PendingTool is the name of a tool call whose result has not been recorded yet.
	PendingTool string `json:"pending_tool,omitempty"`
	// Asking is the question Claude is waiting on the person to answer, "" otherwise.
	// Working stays true meanwhile: the turn is open, blocked on a human.
	Asking      string         `json:"asking,omitempty"`
	Turns       int            `json:"turns"`
	Prompts     int            `json:"prompts"`
	Replies     int            `json:"replies"`
	Usage       Usage          `json:"usage"`
	LastContext int64          `json:"last_context"` // tokens in the latest request: input + cache read + cache write
	Tools       map[string]int `json:"tools,omitempty"`
	FirstTS     time.Time      `json:"first_ts,omitempty"`
	LastTS      time.Time      `json:"last_ts,omitempty"`
	// From the CLI's own cost-state records: what it would bill at API prices, how long
	// the API calls took, and the net line changes. Zero when the CLI wrote none.
	CostUSD      float64 `json:"cost_usd,omitempty"`
	APIMs        int64   `json:"api_ms,omitempty"`
	LinesAdded   int64   `json:"lines_added,omitempty"`
	LinesRemoved int64   `json:"lines_removed,omitempty"`

	cmd cmdTrack // the latest slash command, so its reply finds its row
}

func (st *State) clone() State {
	c := *st
	c.Tools = map[string]int{}
	for k, v := range st.Tools {
		c.Tools[k] = v
	}
	return c
}

func (st *State) touch(ts time.Time) {
	if ts.IsZero() {
		return
	}
	if st.FirstTS.IsZero() {
		st.FirstTS = ts
	}
	st.LastTS = ts
}

func (st *State) startWorking(ts time.Time) {
	if !st.Working {
		st.WorkingSince = ts
	}
	st.Working = true
}

func (st *State) stopWorking() {
	st.Working = false
	st.PendingTool = ""
	st.Asking = ""
}

// DetailCap bounds the drill-in text behind a tool result, which can be megabytes.
const DetailCap = 8000

// TextCap bounds a message body. Messages travel to the phone in full - a prompt cut
// off mid-sentence is exactly what the person away from the desk cannot verify - so
// this is a guard against a pathological paste, not a display decision.
const TextCap = 16000

type Meta struct {
	SessionID string `json:"session_id,omitempty"`
	Cwd       string `json:"cwd,omitempty"`
	GitBranch string `json:"git_branch,omitempty"`
	Version   string `json:"version,omitempty"`
	Title     string `json:"title,omitempty"`

	named bool // the person named the session (/rename): their name wins over a generated one
}

type Stats struct {
	Records int            `json:"records"`
	Rows    int            `json:"rows"`
	ByKind  map[string]int `json:"by_kind"`
	Silent  int            `json:"silent"`
	// Unmapped counts records of a known shape that produced no row - each one is a row
	// the feed would have had to invent, so this must stay zero on real transcripts.
	Unmapped map[string]int `json:"unmapped,omitempty"`
	// Malformed counts lines that are not valid JSON. Real transcripts do contain a few
	// (a crash mid-write leaves a truncated line), so this is skip-and-count, not an
	// error - but it is why the tailer only ever processes complete newline-terminated
	// lines and keeps a partial tail in its buffer.
	Malformed int `json:"malformed,omitempty"`
}

type Result struct {
	Meta  Meta  `json:"meta"`
	Rows  []Row `json:"rows"`
	Stats Stats `json:"stats"`
	State State `json:"state"`
}

// verbs says what happened in the user's language, not the tool's name.
var verbs = map[string]string{
	"Read": "Read", "Edit": "Edited", "Write": "Wrote", "NotebookEdit": "Edited notebook",
	"Bash": "Ran", "Grep": "Searched", "Glob": "Globbed", "Task": "Delegated", "Agent": "Delegated",
	"WebFetch": "Fetched", "WebSearch": "Searched the web", "TodoWrite": "Updated the plan",
	"Skill": "Loaded skill", "Artifact": "Published", "SendUserFile": "Sent a file",
}

// silentTypes are real records that are correctly NOT feed rows. Verified by sampling:
// none of them carries a message payload.
var silentTypes = map[string]bool{
	"system": true, "attachment": true, "file-history-snapshot": true, "mode": true,
	"permission-mode": true, "last-prompt": true, "ai-title": true, "bridge-session": true,
	"atis-latch": true, "file-history-delta": true, "agent-name": true, "frame-link": true,
	"artifact-autoreact-ledger": true, "artifact-comment-monitor": true,
	"cost-state": true, "custom-title": true,
}

// envelopes are system payloads injected with role=user. Rendering them as chat bubbles
// would show the operator messages they never sent; in one real transcript they
// outnumbered genuine user messages 270 to 23.
type envelope struct {
	prefix string
	kind   string // "silent" or "event"
	label  string
}

var envelopes = []envelope{
	{"<task-notification>", "silent", ""},
	{"<system-reminder>", "silent", ""},
	// The caveat the CLI writes before a local command's echo. Older versions wrote it
	// bare ("Caveat: ..."), newer ones inside this tag; either way nobody typed it, and a
	// local command gets no reply, so read as a prompt it opens a turn that never ends.
	{"<local-command-caveat>", "silent", ""},
	{"<local-command-stdout>", "silent", ""},
	{"<local-command-stderr>", "silent", ""},
	{"<command-message>", "silent", ""},
	{"Caveat: The messages below", "silent", ""},
	// Shell mode (a line starting with !) runs the command itself and records it as the
	// user's: the command is something they did, its output is not a message.
	{"<bash-input>", "event", "You ran a shell command"},
	{"<bash-stdout>", "silent", ""},
	{"<bash-stderr>", "silent", ""},
	{"[Request interrupted by user", "event", "You interrupted"}, // also "... for tool use]": an Escaped picker or tool
	{"<command-name>", "event", "You ran a command"},
	// An image result comes back as a user-role text block describing it. That is an
	// attachment, not something the person typed, so it renders as a quiet event.
	{"[Image:", "event", "Image attached"},
}

// classifyUser reports how a user-role text should render.
// ClassifyUser is classifyUser for other packages (the sessions list peeks tails).
func ClassifyUser(txt string) (kind, display string) { return classifyUser(txt) }

func classifyUser(txt string) (kind, display string) {
	t := strings.TrimLeft(txt, " \t\r\n")
	for _, e := range envelopes {
		if strings.HasPrefix(t, e.prefix) {
			if e.kind == "event" && e.prefix == "<command-name>" {
				rest := strings.TrimPrefix(t, e.prefix)
				if i := strings.Index(rest, "<"); i >= 0 {
					rest = rest[:i]
				}
				if name := strings.TrimSpace(rest); name != "" {
					return "event", "You ran /" + name
				}
			}
			if e.prefix == "<bash-input>" {
				rest := strings.TrimPrefix(t, e.prefix)
				if i := strings.Index(rest, "</bash-input>"); i >= 0 {
					rest = rest[:i]
				}
				if cmd := strings.TrimSpace(strings.ReplaceAll(rest, "\n", " ")); cmd != "" {
					return "event", "You ran ! " + truncRunes(cmd, 60)
				}
			}
			return e.kind, e.label
		}
	}
	return "user", txt
}

// truncRunes cuts to n runes, matching Python's code-point slicing.
func truncRunes(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	i, count := 0, 0
	for idx := range s {
		if count == n {
			i = idx
			break
		}
		count++
		i = len(s)
	}
	return s[:i]
}

func lastRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[len(r)-n:])
}

func short(p string, n int) string {
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		p = strings.ReplaceAll(p, home, "~")
	}
	if utf8.RuneCountInString(p) <= n {
		return p
	}
	return "…" + lastRunes(p, n-1)
}

func target(tool string, input map[string]any) string {
	if input == nil {
		return ""
	}
	str := func(k string) (string, bool) {
		v, ok := input[k]
		if !ok {
			return "", false
		}
		s, ok := v.(string)
		return s, ok
	}
	for _, k := range []string{"file_path", "notebook_path", "path"} {
		if s, ok := str(k); ok {
			return short(s, 44)
		}
	}
	switch tool {
	case "Bash":
		c, _ := str("command")
		c = strings.ReplaceAll(strings.TrimSpace(c), "\n", " ")
		if utf8.RuneCountInString(c) <= 60 {
			return c
		}
		return truncRunes(c, 59) + "…"
	case "Grep", "Glob":
		s, _ := str("pattern")
		return s
	case "Task", "Agent": // Agent is the current name of what was Task
		s, _ := str("description")
		return s
	case "WebFetch", "WebSearch":
		if s, ok := str("url"); ok {
			return short(s, 44)
		}
		s, _ := str("query")
		return short(s, 44)
	case "Skill":
		s, _ := str("skill")
		return s
	}
	return ""
}

// resultRow turns a tool_result into (glyph, summary, metric, detail, truncated). It
// never invents an outcome: an empty result is "done" with no metric, not a fabricated
// success detail.
func resultRow(content any) (string, string, string, string, bool) {
	var text string
	switch v := content.(type) {
	case string:
		text = v
	case []any:
		var parts []string
		for _, b := range v {
			if m, ok := b.(map[string]any); ok {
				if s, ok := m["text"].(string); ok {
					parts = append(parts, s)
				} else {
					parts = append(parts, "")
				}
			}
		}
		text = strings.Join(parts, "\n")
	}
	lines := 0
	if text != "" {
		lines = strings.Count(text, "\n") + 1
	}
	head := text
	if utf8.RuneCountInString(head) > 200 {
		head = truncRunes(head, 200)
	}
	detail := text
	truncated := false
	if utf8.RuneCountInString(detail) > DetailCap {
		detail = truncRunes(detail, DetailCap)
		truncated = true
	}
	if looksFailed(head) {
		first := strings.SplitN(strings.TrimSpace(text), "\n", 2)[0]
		return "✗", "failed", truncRunes(first, 48), detail, truncated
	}
	if strings.TrimSpace(text) == "" {
		return "✓", "done", "", "", false
	}
	if lines > 1 {
		return "✓", "done", fmt.Sprintf("%d lines", lines), detail, truncated
	}
	return "✓", "done", fmt.Sprintf("%d chars", utf8.RuneCountInString(text)), detail, truncated
}

type record struct {
	UUID           string          `json:"uuid"`
	Type           string          `json:"type"`
	Subtype        string          `json:"subtype"`
	PermissionMode string          `json:"permissionMode"`
	DurationMs     int64           `json:"durationMs"`
	TotalCostUSD   float64         `json:"totalCostUSD"`
	TotalAPIMs     int64           `json:"totalAPIDuration"`
	LinesAdded     int64           `json:"totalLinesAdded"`
	LinesRemoved   int64           `json:"totalLinesRemoved"`
	SessionID      string          `json:"sessionId"`
	Cwd            string          `json:"cwd"`
	GitBranch      string          `json:"gitBranch"`
	Version        string          `json:"version"`
	Title          string          `json:"title"`
	AgentName      string          `json:"agentName"`
	AiTitle        string          `json:"aiTitle"` // what the CLI writes in ai-title records
	CustomTitle    string          `json:"customTitle"`
	Operation      string          `json:"operation"`
	Content        string          `json:"content"`
	Timestamp      string          `json:"timestamp"`
	Message        json.RawMessage `json:"message"`
	// IsMeta marks a user record the CLI wrote itself. SourceToolUseID says a tool call
	// put it there (a skill's text after the Skill tool).
	IsMeta           bool         `json:"isMeta"`
	SourceToolUseID  string       `json:"sourceToolUseID"`
	IsCompactSummary bool         `json:"isCompactSummary"`
	CompactMetadata  *compactMeta `json:"compactMetadata"`
}

type message struct {
	Content    any    `json:"content"`
	Model      string `json:"model"`
	StopReason string `json:"stop_reason"`
	Usage      *struct {
		Input      int64 `json:"input_tokens"`
		Output     int64 `json:"output_tokens"`
		CacheRead  int64 `json:"cache_read_input_tokens"`
		CacheWrite int64 `json:"cache_creation_input_tokens"`
		Details    *struct {
			Thinking int64 `json:"thinking_tokens"`
		} `json:"output_tokens_details"`
	} `json:"usage"`
}

// Parse reads a transcript and maps it to feed rows, starting from a fresh State.
func Parse(r io.Reader) (*Result, error) { return ParseFrom(r, State{}) }

// ParseFrom continues from a State carried over from an earlier parse of the same
// transcript, which is how the tailer keeps working/usage right across batches.
func ParseFrom(r io.Reader, prev State) (*Result, error) {
	res := &Result{Stats: Stats{ByKind: map[string]int{}, Unmapped: map[string]int{}}, State: prev.clone()}
	res.State.cmd.row = -1     // an open command's row went out with an earlier batch
	queued := map[string]int{} // queued prompt -> row index, so delivery resolves it

	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 1<<20), 64<<20) // transcripts carry very long lines
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		var d record
		if err := json.Unmarshal([]byte(line), &d); err != nil {
			res.Stats.Records++
			res.Stats.Malformed++
			continue
		}
		res.Stats.Records++

		if d.SessionID != "" {
			res.Meta.SessionID = d.SessionID
		}
		if d.Cwd != "" {
			res.Meta.Cwd = d.Cwd
		}
		if d.GitBranch != "" {
			res.Meta.GitBranch = d.GitBranch
		}
		if d.Version != "" {
			res.Meta.Version = d.Version
		}
		// ai-title / agent-name carry a human session name; the sessions list shows this
		// rather than a directory basename.
		if (d.Type == "ai-title" || d.Type == "agent-name") && !res.Meta.named {
			if d.AiTitle != "" {
				res.Meta.Title = d.AiTitle
			} else if d.Title != "" {
				res.Meta.Title = d.Title
			} else if d.AgentName != "" {
				res.Meta.Title = d.AgentName
			}
		}
		// /rename writes the name the person gave the session.
		if d.Type == "custom-title" && strings.TrimSpace(d.CustomTitle) != "" {
			res.Meta.Title, res.Meta.named = strings.TrimSpace(d.CustomTitle), true
		}

		ts, _ := time.Parse(time.RFC3339, d.Timestamp)
		res.State.touch(ts)

		var content any
		var msg message
		if len(d.Message) > 0 {
			if err := json.Unmarshal(d.Message, &msg); err == nil {
				content = msg.Content
			}
		}

		if d.Type == "permission-mode" && d.PermissionMode != "" {
			res.State.PermissionMode = d.PermissionMode
		}
		if d.Type == "cost-state" {
			// Cumulative totals: each record supersedes the last.
			res.State.CostUSD = d.TotalCostUSD
			res.State.APIMs = d.TotalAPIMs
			res.State.LinesAdded = d.LinesAdded
			res.State.LinesRemoved = d.LinesRemoved
		}
		if d.Type == "system" && d.Subtype == "turn_duration" {
			res.State.stopWorking()
			res.add(Row{Kind: KindTurn, Glyph: "✻", Text: "Worked for " + humanMs(d.DurationMs), TS: ts})
		}

		switch {
		case d.Type == "user":
			res.handleUser(content, ts, queued, &d)

		case d.Type == "system" && d.Subtype == "local_command":
			// Newer CLIs write the command and its reply as system records.
			c := strings.TrimSpace(d.Content)
			if name, args, skill, ok := commandText(c); ok {
				res.commandRow(name, args, skill, false, ts, d.UUID, queued)
			} else if m := cmdReply.FindStringSubmatch(c); m != nil {
				res.commandReply(m[1], m[2], ts)
			} else {
				res.Stats.Silent++
			}

		case d.Type == "system" && d.Subtype == "compact_boundary":
			res.compactRow(d.CompactMetadata, ts)

		case d.Type == "assistant":
			res.State.cmd = cmdTrack{row: -1}
			if msg.Model != "" {
				res.State.Model = msg.Model
			}
			if u := msg.Usage; u != nil {
				add := Usage{Input: u.Input, Output: u.Output, CacheRead: u.CacheRead, CacheWrite: u.CacheWrite}
				if u.Details != nil {
					add.Thinking = u.Details.Thinking
				}
				res.State.Usage.add(add)
				res.State.LastContext = u.Input + u.CacheRead + u.CacheWrite
			}
			if msg.StopReason == "end_turn" {
				res.State.stopWorking()
				res.State.Turns++
			} else {
				res.State.startWorking(ts)
			}
			list, ok := content.([]any)
			if !ok {
				res.Stats.Unmapped["assistant/other"]++
				continue
			}
			for _, b := range list {
				m, ok := b.(map[string]any)
				if !ok {
					continue
				}
				switch m["type"] {
				case "text":
					txt := strings.TrimSpace(str(m["text"]))
					if txt != "" {
						body, trunc := capText(txt)
						res.add(Row{Kind: KindAssistant, Text: body, TS: ts, Truncated: trunc})
						res.State.Replies++
					}
				case "tool_use":
					name := str(m["name"])
					if name == "" {
						name = "?"
					}
					verb, ok := verbs[name]
					if !ok {
						verb = name
					}
					var input map[string]any
					if in, ok := m["input"].(map[string]any); ok {
						input = in
					}
					text := strings.TrimSpace(verb + " " + target(name, input))
					detail := ""
					if input != nil {
						if c, ok := input["command"].(string); ok {
							detail = c
						}
					}
					if res.State.Tools == nil {
						res.State.Tools = map[string]int{}
					}
					res.State.Tools[name]++
					res.State.PendingTool = name
					if name == "AskUserQuestion" {
						qs := parseQuestions(input)
						if len(qs) > 0 {
							res.State.Asking = qs[0].Text
							res.add(Row{Kind: KindQuestion, Glyph: "?", Text: qs[0].Text, TS: ts, ToolID: str(m["id"]), Questions: qs})
							continue
						}
					}
					row := Row{Kind: KindTool, Glyph: "▸", Text: text, TS: ts, Detail: detail, ToolID: str(m["id"])}
					if (name == "Agent" || name == "Task") && input != nil {
						bg, _ := input["run_in_background"].(bool)
						row.Agent = &AgentCall{Type: str(input["subagent_type"]), Description: str(input["description"]), Model: str(input["model"]), Background: bg}
						if p, ok := input["prompt"].(string); ok {
							row.Detail = p // what it was asked, for the drill-in
						}
					}
					res.add(row)
					// A picture Claude sent to the person is worth showing, not just naming.
					if name == "SendUserFile" && input != nil {
						if files, ok := input["files"].([]any); ok {
							for _, f := range files {
								if path, ok := f.(string); ok && imageMedia(path) != "" {
									res.add(Row{Kind: KindImage, Text: filepath.Base(path), TS: ts,
										ImageRef: "file:" + path, Media: imageMedia(path)})
								}
							}
						}
					}
				case "thinking":
					res.Stats.Silent++
					res.Stats.ByKind["(thinking, not shown)"]++
				default:
					res.Stats.Unmapped["assistant/"+str(m["type"])]++
				}
			}

		case d.Type == "queue-operation":
			c := strings.TrimSpace(d.Content)
			if d.Operation == "enqueue" && c != "" {
				kind, disp := classifyUser(c)
				if kind != "user" {
					res.Stats.Silent++
					res.Stats.ByKind["(injected envelope, not shown)"]++
					continue
				}
				body, trunc := capText(disp)
				queued[truncRunes(strings.ReplaceAll(disp, "\n", " "), 60)] = len(res.Rows)
				res.add(Row{Kind: KindQueued, Glyph: "⋯", Text: body, Metric: "queued", TS: ts, Truncated: trunc})
			} else {
				res.Stats.Silent++
			}

		case silentTypes[d.Type]:
			if d.Type == "system" {
				res.State.cmd.open = false // a turn ended, a scheduled prompt fired: the command is over
			}
			res.Stats.Silent++

		default:
			res.Stats.Unmapped[d.Type]++
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	res.Stats.Rows = len(res.Rows)
	return res, nil
}

func (res *Result) handleUser(content any, ts time.Time, queued map[string]int, d *record) {
	uuid := d.UUID
	if d.IsCompactSummary {
		// The summary a compaction leaves for Claude; the divider row already says it happened.
		res.Stats.Silent++
		res.Stats.ByKind["(injected envelope, not shown)"]++
		return
	}
	var txt string
	switch v := content.(type) {
	case []any:
		got := false
		images := 0 // image blocks seen in this record, in order - the n in "<uuid>:<n>"
		for _, b := range v {
			m, ok := b.(map[string]any)
			if !ok {
				continue
			}
			if m["type"] == "tool_result" {
				g, s, metric, detail, trunc := resultRow(m["content"])
				if res.State.PendingTool == "AskUserQuestion" {
					// "User answered Claude's questions: · Which colour? → Blue": the
					// answer is what the person wants to see on the card.
					if a := answerText(m["content"]); a != "" {
						s = "Answered: " + a
					}
				}
				res.add(Row{
					Kind: KindResult, Glyph: g, Text: s, Metric: metric, TS: ts,
					Detail: detail, Truncated: trunc, ToolID: str(m["tool_use_id"]),
				})
				// The result is in; Claude picks the turn back up.
				res.State.PendingTool = ""
				res.State.Asking = ""
				res.State.startWorking(ts)
				got = true
				// An image Claude just looked at.
				if list, ok := m["content"].([]any); ok {
					for _, x := range list {
						if xm, ok := x.(map[string]any); ok && xm["type"] == "image" {
							res.add(Row{Kind: KindImage, Text: "Image Claude looked at", TS: ts,
								ImageRef: fmt.Sprintf("%s:%d", uuid, images), Media: imageBlockMedia(xm)})
							images++
						}
					}
				}
			} else if m["type"] == "image" {
				// An image the person pasted into the prompt.
				res.add(Row{Kind: KindImage, Text: "Image you sent", TS: ts,
					ImageRef: fmt.Sprintf("%s:%d", uuid, images), Media: imageBlockMedia(m)})
				images++
			}
		}
		if got {
			res.State.cmd = cmdTrack{row: -1}
			return
		}
		var parts []string
		for _, b := range v {
			if m, ok := b.(map[string]any); ok && m["type"] == "text" {
				parts = append(parts, str(m["text"]))
			}
		}
		txt = strings.TrimSpace(strings.Join(parts, " "))
	case string:
		txt = strings.TrimSpace(v)
	}

	if txt == "" {
		res.Stats.Silent++
		return
	}
	if name, args, skill, ok := commandText(txt); ok {
		// A meta command record is one the CLI wrote for itself: a skill a mode loads.
		res.commandRow(name, args, skill, d.IsMeta && skill, ts, uuid, queued)
		return
	}
	if m := cmdReply.FindStringSubmatch(txt); m != nil {
		res.commandReply(m[1], m[2], ts)
		return
	}
	kind, display := classifyUser(txt)
	if kind == "user" && d.IsMeta {
		// Text the CLI wrote, not the person: a skill's text after the Skill tool, or what
		// a command writes after itself (/context's table, a skill run as a command).
		// Anything else it injects (a scheduled prompt, a channel message) still reads as
		// a prompt, as it did before.
		if d.SourceToolUseID != "" {
			res.Stats.Silent++
			res.Stats.ByKind["(injected envelope, not shown)"]++
			return
		}
		if res.commandBody(txt, ts) {
			res.Stats.ByKind["(injected envelope, not shown)"]++
			return
		}
	}
	if kind == "user" && !d.IsMeta {
		if m := compactLine.FindStringSubmatch(txt); m != nil {
			// /compact is recorded as the line typed; its boundary and echo follow when done.
			// The line is the person's even right after an auto compaction, so the echo
			// swallowing that follows a boundary does not apply to it.
			res.State.cmd = cmdTrack{row: -1}
			res.commandRow("compact", strings.TrimSpace(m[1]), false, false, ts, uuid, queued)
			res.State.startWorking(ts)
			return
		}
		res.State.cmd = cmdTrack{row: -1}
	}
	switch kind {
	case "silent":
		res.Stats.Silent++
		res.Stats.ByKind["(injected envelope, not shown)"]++
	case "event":
		if display == "You interrupted" {
			res.State.stopWorking()
		}
		res.add(Row{Kind: KindEvent, Glyph: "•", Text: display, TS: ts})
	default:
		res.State.Prompts++
		res.State.startWorking(ts)
		one := strings.ReplaceAll(display, "\n", " ")
		key := truncRunes(one, 60)
		body, trunc := capText(display)
		// A prompt that was queued earlier resolves into this row instead of appearing
		// twice: the pending bubble becomes the real message.
		if idx, ok := queued[key]; ok && idx < len(res.Rows) {
			delete(queued, key)
			res.Rows[idx] = Row{Kind: KindUser, Text: body, TS: ts, Truncated: trunc}
			res.Stats.ByKind[string(KindQueued)]--
			res.Stats.ByKind[string(KindUser)]++
			return
		}
		res.add(Row{Kind: KindUser, Text: body, TS: ts, Truncated: trunc})
	}
}

// answerText pulls the chosen answers out of an AskUserQuestion result. The CLI writes
// `Your questions have been answered: "Which colour?"="Blue". You can now continue with
// these answers in mind.` (2.1.2xx); older builds wrote one "· question → answer" line
// per question. Several answers are joined with " · ".
func answerText(content any) string {
	txt := ""
	switch v := content.(type) {
	case string:
		txt = v
	case []any:
		for _, b := range v {
			if m, ok := b.(map[string]any); ok && m["type"] == "text" {
				txt += str(m["text"]) + "\n"
			}
		}
	}
	var out []string
	for _, m := range answeredPair.FindAllStringSubmatch(txt, -1) {
		if a := strings.TrimSpace(m[1]); a != "" {
			out = append(out, a)
		}
	}
	if len(out) == 0 {
		for _, line := range strings.Split(txt, "\n") {
			if i := strings.LastIndex(line, "→"); i >= 0 {
				if a := strings.TrimSpace(line[i+len("→"):]); a != "" {
					out = append(out, a)
				}
			}
		}
	}
	return strings.Join(out, " · ")
}

// answeredPair matches `"question"="answer"`; the answer may hold commas but no quotes.
var answeredPair = regexp.MustCompile(`"[^"]*"="([^"]*)"`)

// parseQuestions reads an AskUserQuestion input: questions[{question, header, options
// [{label, description}], multiSelect}].
func parseQuestions(input map[string]any) []Question {
	list, _ := input["questions"].([]any)
	var out []Question
	for _, it := range list {
		m, ok := it.(map[string]any)
		if !ok {
			continue
		}
		q := Question{Text: strings.TrimSpace(str(m["question"])), Header: strings.TrimSpace(str(m["header"]))}
		q.MultiSelect, _ = m["multiSelect"].(bool)
		if k := strings.TrimSpace(str(m["kind"])); k != "" && k != "choice" {
			q.Kind = k
		}
		opts, _ := m["options"].([]any)
		for _, o := range opts {
			om, ok := o.(map[string]any)
			if !ok {
				continue
			}
			if l := strings.TrimSpace(str(om["label"])); l != "" {
				q.Options = append(q.Options, Option{Label: l, Description: strings.TrimSpace(str(om["description"]))})
			}
		}
		if q.Text != "" {
			out = append(out, q)
		}
	}
	return out
}

func (res *Result) add(r Row) {
	res.Rows = append(res.Rows, r)
	res.Stats.ByKind[string(r.Kind)]++
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

func flat(s string, n int) string {
	return truncRunes(strings.ReplaceAll(s, "\n", " "), n)
}

// zeroFailures matches the reassuring forms - "0 failed", "0 errors", "no errors",
// "failures: 0" - so a passing test run is not painted red for saying so.
var zeroFailures = regexp.MustCompile(`(?i)\b(0 (failed|failures|errors?)|no (errors?|failures)|(failed|failures|errors?)[:=]\s*0)\b`)

// looksFailed is the feed's only guess about an outcome: the first 200 runes mention an
// error or a failure, and not merely that there were none.
func looksFailed(head string) bool {
	low := strings.ToLower(zeroFailures.ReplaceAllString(head, ""))
	return strings.Contains(low, "error") || strings.Contains(low, "failed")
}

// imageMedia maps a file extension to its media type, or "" for a non-image.
// ImageMedia is imageMedia for other packages.
func ImageMedia(path string) string { return imageMedia(path) }

func imageMedia(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".webp":
		return "image/webp"
	case ".gif":
		return "image/gif"
	}
	return ""
}

func imageBlockMedia(m map[string]any) string {
	if src, ok := m["source"].(map[string]any); ok {
		if mt, ok := src["media_type"].(string); ok {
			return mt
		}
	}
	return "image/png"
}

// ImageBlock finds the n-th image block of a record by uuid in a transcript file and
// returns its media type and raw base64 data. Records are append-only lines, so this
// is a scan for the one line carrying the uuid.
func ImageBlock(path, uuid string, n int) (media, data string, err error) {
	f, err := os.Open(path)
	if err != nil {
		return "", "", err
	}
	defer f.Close()
	needle := []byte(`"uuid":"` + uuid + `"`)
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 1<<20), 64<<20)
	for sc.Scan() {
		line := sc.Bytes()
		if !bytes.Contains(line, needle) {
			continue
		}
		var d record
		if json.Unmarshal(line, &d) != nil || d.UUID != uuid {
			continue
		}
		var m message
		if json.Unmarshal(d.Message, &m) != nil {
			return "", "", fmt.Errorf("record has no message")
		}
		list, _ := m.Content.([]any)
		i := 0
		for _, b := range list {
			bm, ok := b.(map[string]any)
			if !ok {
				continue
			}
			var candidates []map[string]any
			if bm["type"] == "image" {
				candidates = append(candidates, bm)
			} else if bm["type"] == "tool_result" {
				if cl, ok := bm["content"].([]any); ok {
					for _, x := range cl {
						if xm, ok := x.(map[string]any); ok && xm["type"] == "image" {
							candidates = append(candidates, xm)
						}
					}
				}
			}
			for _, c := range candidates {
				if i == n {
					src, _ := c["source"].(map[string]any)
					return imageBlockMedia(c), str(src["data"]), nil
				}
				i++
			}
		}
		return "", "", fmt.Errorf("record has no image %d", n)
	}
	if err := sc.Err(); err != nil {
		return "", "", err
	}
	return "", "", fmt.Errorf("record not found")
}

// humanMs renders a duration the way the CLI does: 1.9s, 45s, 1m 6s, 1h 2m.
func humanMs(ms int64) string {
	if ms < 10_000 {
		return fmt.Sprintf("%.1fs", float64(ms)/1000)
	}
	secs := ms / 1000
	switch {
	case secs < 60:
		return fmt.Sprintf("%ds", secs)
	case secs < 3600:
		return fmt.Sprintf("%dm %ds", secs/60, secs%60)
	default:
		return fmt.Sprintf("%dh %dm", secs/3600, (secs%3600)/60)
	}
}

// capText keeps a message body intact - newlines and all, because the phone renders
// markdown - and only cuts at TextCap, reporting that it did.
func capText(s string) (string, bool) {
	if utf8.RuneCountInString(s) <= TextCap {
		return s, false
	}
	return truncRunes(s, TextCap), true
}

// ParseFile is the convenience wrapper used by `portholed replay` and the tailer.
func ParseFile(path string) (*Result, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return Parse(f)
}

// ProjectSlug is how Claude Code names a project directory: the absolute path with
// every non-alphanumeric run replaced by a dash.
func ProjectSlug(cwd string) string {
	var b strings.Builder
	for _, r := range cwd {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		} else {
			b.WriteRune('-')
		}
	}
	return b.String()
}

// ProjectsDir is where Claude Code keeps transcripts.
func ProjectsDir() string {
	if d := os.Getenv("CLAUDE_CONFIG_DIR"); d != "" {
		return filepath.Join(d, "projects")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".claude", "projects")
}
