package session

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

// Agent is one subagent a session started with its Agent tool: what it was asked to do and,
// read from its own transcript, how far it has got. Claude Code writes each one to
// <session>/subagents/agent-<id>.jsonl (a workflow's under subagents/workflows/<run>/), with
// agent-<id>.meta.json beside it naming the tool call that started it, its type, model, parent
// and whether it runs in the background.
type Agent struct {
	ID          string    `json:"id"`
	ToolID      string    `json:"tool_id,omitempty"` // the Agent call that started it
	Type        string    `json:"type,omitempty"`    // "general-purpose", "Explore", ...
	Description string    `json:"description,omitempty"`
	Model       string    `json:"model,omitempty"`
	Background  bool      `json:"background,omitempty"`
	Depth       int       `json:"depth,omitempty"`  // 1: started by the session; 2+: by another agent
	Parent      string    `json:"parent,omitempty"` // the agent that started this one, if any
	State       string    `json:"state"`            // "running", "done", "failed" or "stopped"
	Started     time.Time `json:"started,omitzero"`
	LastActive  time.Time `json:"last_active,omitzero"`
	Tools       int       `json:"tools"`
	Doing       string    `json:"doing,omitempty"` // its latest tool call, "Bash: npm test"
}

// An agent that has stopped writing is "stopped" rather than "running" after this long idle,
// unless a tool call of its is still open: a long build writes nothing until it ends. Real
// agents go quiet for minutes while they think, so the first is generous.
const (
	agentIdle     = 10 * time.Minute
	agentToolIdle = 60 * time.Minute
)

// agentRead is what has been read of one agent's transcript so far, so that each look reads
// only what was appended since the last.
type agentRead struct {
	mu     sync.Mutex
	info   os.FileInfo // the file as last read: a replaced file starts over
	offset int64
	first  time.Time
	last   time.Time
	tools  int
	doing  string
	open   map[string]bool // tool calls without a result yet
	ended  bool            // its last word was a final answer (end_turn)
	cut    bool            // its last word was an interruption or an API error
}

// parentRead is the session's own transcript, read for the word on each agent: a background
// agent's end comes as a <task-notification> naming it and its status; a foreground agent's,
// as its Agent call's result. That word beats anything read from the agent's own file.
type parentRead struct {
	mu     sync.Mutex
	info   os.FileInfo
	offset int64
	status map[string]string // agent id -> "completed", "failed", "killed", "stopped"
}

var (
	agentMu     sync.Mutex
	agentCache  = map[string]*agentRead{}
	parentCache = map[string]*parentRead{}
)

// SubagentsDir is where a session's agents are written, beside its transcript.
func SubagentsDir(transcriptPath string) string {
	return filepath.Join(strings.TrimSuffix(transcriptPath, ".jsonl"), "subagents")
}

// Agents lists the subagents of the session whose transcript is at path, newest first.
func Agents(path string) []Agent {
	return agentsIn(SubagentsDir(path), path, time.Now())
}

// RunningAgents counts the agents of that session still at work.
func RunningAgents(path string) int {
	n := 0
	for _, a := range Agents(path) {
		if a.State == "running" {
			n++
		}
	}
	return n
}

func agentsIn(dir, parent string, now time.Time) []Agent {
	files, _ := filepath.Glob(filepath.Join(dir, "agent-*.jsonl"))
	wf, _ := filepath.Glob(filepath.Join(dir, "workflows", "*", "agent-*.jsonl"))
	files = append(files, wf...)
	if len(files) == 0 {
		return nil
	}
	pruneAgents(dir, files)
	word := parentWord(parent)
	out := make([]Agent, 0, len(files))
	for _, f := range files {
		id := strings.TrimSuffix(strings.TrimPrefix(filepath.Base(f), "agent-"), ".jsonl")
		a := Agent{ID: id, Depth: 1}
		var meta struct {
			AgentType    string `json:"agentType"`
			Description  string `json:"description"`
			ToolUseID    string `json:"toolUseId"`
			ParentAgent  string `json:"parentAgentId"`
			SpawnDepth   int    `json:"spawnDepth"`
			RequestShape string `json:"requestShape"`
			Model        string `json:"model"`
		}
		if b, err := os.ReadFile(strings.TrimSuffix(f, ".jsonl") + ".meta.json"); err == nil && json.Unmarshal(b, &meta) == nil {
			a.Type, a.Description, a.ToolID, a.Parent = meta.AgentType, meta.Description, meta.ToolUseID, meta.ParentAgent
			a.Model, a.Background = meta.Model, meta.RequestShape == "background"
			if meta.SpawnDepth > 0 {
				a.Depth = meta.SpawnDepth
			}
		}
		r := readAgent(f)
		if r == nil {
			continue
		}
		a.Started, a.LastActive, a.Tools, a.Doing = r.first, r.last, r.tools, r.doing
		idle := now.Sub(r.last)
		switch word[id] {
		case "completed":
			a.State = "done"
		case "failed":
			a.State = "failed"
		case "killed", "stopped":
			a.State = "stopped"
		default:
			switch {
			case r.ended:
				a.State = "done"
			case r.cut:
				a.State = "stopped"
			case len(r.open) > 0 && idle < agentToolIdle, len(r.open) == 0 && idle < agentIdle:
				a.State = "running"
			default:
				a.State = "stopped"
			}
		}
		out = append(out, a)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Started.After(out[j].Started) })
	return out
}

// pruneAgents forgets readings of files in dir that are gone, so the cache follows the disk.
func pruneAgents(dir string, files []string) {
	keep := make(map[string]bool, len(files))
	for _, f := range files {
		keep[f] = true
	}
	agentMu.Lock()
	defer agentMu.Unlock()
	for p := range agentCache {
		if strings.HasPrefix(p, dir+string(filepath.Separator)) && !keep[p] {
			delete(agentCache, p)
		}
	}
}

// eachNewLine reads the complete lines appended to path since offset, one at a time: a
// session's files can be hundreds of megabytes, never read whole. It returns the new offset,
// which stops before an unfinished last line so that it is read again whole next time.
func eachNewLine(path string, offset int64, fn func([]byte)) int64 {
	f, err := os.Open(path)
	if err != nil {
		return offset
	}
	defer f.Close()
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return offset
	}
	br := bufio.NewReaderSize(f, 64<<10)
	for {
		line, err := br.ReadBytes('\n')
		if err != nil {
			return offset // EOF or a part line: leave it for the next look
		}
		offset += int64(len(line))
		fn(line)
	}
}

// readAgent brings the cached reading of one agent's transcript up to date and returns a copy.
func readAgent(path string) *agentRead {
	st, err := os.Stat(path)
	agentMu.Lock()
	if err != nil {
		delete(agentCache, path)
		agentMu.Unlock()
		return nil
	}
	r := agentCache[path]
	if r == nil {
		r = &agentRead{open: map[string]bool{}}
		agentCache[path] = r
	}
	agentMu.Unlock()

	r.mu.Lock() // one file at a time; other agents and other callers are not held up
	defer r.mu.Unlock()
	if r.info != nil && (!os.SameFile(r.info, st) || st.Size() < r.offset) {
		r.reset() // replaced or truncated: read it again from the start
	}
	r.info = st
	if st.Size() > r.offset {
		r.offset = eachNewLine(path, r.offset, r.take)
	}
	if r.last.IsZero() {
		r.last = st.ModTime()
	}
	c := agentRead{offset: r.offset, first: r.first, last: r.last, tools: r.tools, doing: r.doing, ended: r.ended, cut: r.cut, open: map[string]bool{}}
	for k := range r.open {
		c.open[k] = true
	}
	return &c
}

// reset forgets everything read, keeping the lock (which the caller holds).
func (r *agentRead) reset() {
	r.info, r.offset, r.first, r.last, r.tools, r.doing = nil, 0, time.Time{}, time.Time{}, 0, ""
	r.open, r.ended, r.cut = map[string]bool{}, false, false
}

// take reads one record of an agent's transcript.
func (r *agentRead) take(line []byte) {
	var rec struct {
		Type      string    `json:"type"`
		Timestamp time.Time `json:"timestamp"`
		APIError  bool      `json:"isApiErrorMessage"`
		Message   struct {
			Content    json.RawMessage `json:"content"`
			StopReason string          `json:"stop_reason"`
		} `json:"message"`
	}
	if json.Unmarshal(line, &rec) != nil {
		return
	}
	// Records are not always written in time order (an attachment can be stamped minutes
	// before the result written ahead of it): keep the latest, not the last.
	if !rec.Timestamp.IsZero() {
		if r.first.IsZero() || rec.Timestamp.Before(r.first) {
			r.first = rec.Timestamp
		}
		if rec.Timestamp.After(r.last) {
			r.last = rec.Timestamp
		}
	}
	var blocks []struct {
		Type      string `json:"type"`
		ID        string `json:"id"`
		ToolUseID string `json:"tool_use_id"`
		Text      string `json:"text"`
	}
	text := ""
	if json.Unmarshal(rec.Message.Content, &blocks) != nil {
		_ = json.Unmarshal(rec.Message.Content, &text)
	}
	switch rec.Type {
	case "assistant":
		for _, b := range blocks {
			if b.Type == "tool_use" {
				r.tools++
				r.open[b.ID] = true
			}
		}
		if l := ToolLabel(rec.Message.Content); l != "" {
			r.doing = l
		}
		// A final answer ends the agent; an API error (a usage limit, say) stops it.
		r.ended = rec.Message.StopReason == "end_turn"
		r.cut = rec.APIError
	case "user":
		toolResult := false
		for _, b := range blocks {
			if b.Type == "tool_result" {
				delete(r.open, b.ToolUseID)
				toolResult = true
			}
			if b.Type == "text" && strings.HasPrefix(b.Text, "[Request interrupted") {
				text = b.Text
			}
		}
		if strings.HasPrefix(strings.TrimSpace(text), "[Request interrupted") {
			r.cut, r.ended = true, false // the person stopped it
			return
		}
		if !toolResult {
			r.ended, r.cut = false, false // a new message to the agent: it is at work again
		}
	}
}

var (
	notifTaskID = regexp.MustCompile(`<task-id>([^<]+)</task-id>`)
	notifStatus = regexp.MustCompile(`<status>([a-z_]+)</status>`)
)

// parentWord brings the reading of the session's own transcript up to date and returns, for
// each agent the session has heard back from, how it ended.
func parentWord(path string) map[string]string {
	if path == "" {
		return nil
	}
	st, err := os.Stat(path)
	agentMu.Lock()
	if err != nil {
		delete(parentCache, path)
		agentMu.Unlock()
		return nil
	}
	p := parentCache[path]
	if p == nil {
		p = &parentRead{status: map[string]string{}}
		parentCache[path] = p
	}
	agentMu.Unlock()

	p.mu.Lock()
	defer p.mu.Unlock()
	if p.info != nil && (!os.SameFile(p.info, st) || st.Size() < p.offset) {
		p.offset, p.status = 0, map[string]string{}
	}
	p.info = st
	if st.Size() > p.offset {
		p.offset = eachNewLine(path, p.offset, func(line []byte) {
			// Most records say nothing about agents; skip them before any JSON work.
			if !bytes.Contains(line, []byte("task-notification")) && !bytes.Contains(line, []byte(`"agentId"`)) {
				return
			}
			var rec struct {
				Type    string `json:"type"`
				Message struct {
					Content json.RawMessage `json:"content"`
				} `json:"message"`
				Result json.RawMessage `json:"toolUseResult"`
			}
			if json.Unmarshal(line, &rec) != nil || rec.Type != "user" {
				return
			}
			// a foreground agent's call returns when the agent is done
			var res struct {
				AgentID string `json:"agentId"`
				Status  string `json:"status"`
				IsAsync bool   `json:"isAsync"`
			}
			if json.Unmarshal(rec.Result, &res) == nil && res.AgentID != "" && !res.IsAsync && res.Status != "" {
				p.status[res.AgentID] = res.Status
			}
			// a background agent's end arrives as a notification
			body := string(rec.Message.Content)
			var s string
			if json.Unmarshal(rec.Message.Content, &s) == nil {
				body = s
			}
			if strings.Contains(body, "<task-notification>") {
				id, status := notifTaskID.FindStringSubmatch(body), notifStatus.FindStringSubmatch(body)
				if id != nil && status != nil {
					p.status[strings.TrimSpace(id[1])] = status[1]
				}
			}
		})
	}
	out := make(map[string]string, len(p.status))
	for k, v := range p.status {
		out[k] = v
	}
	return out
}
