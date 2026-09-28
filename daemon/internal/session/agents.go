package session

import (
	"bufio"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Agent is one subagent a session started with its Agent tool: what it was asked to do and,
// read from its own transcript, how far it has got. Claude Code writes each one to
// <session>/subagents/agent-<id>.jsonl, with agent-<id>.meta.json beside it naming the tool
// call that started it, its type, model, parent and whether it runs in the background.
type Agent struct {
	ID          string    `json:"id"`
	ToolID      string    `json:"tool_id,omitempty"` // the Agent call that started it
	Type        string    `json:"type,omitempty"`    // "general-purpose", "Explore", ...
	Description string    `json:"description,omitempty"`
	Model       string    `json:"model,omitempty"`
	Background  bool      `json:"background,omitempty"`
	Depth       int       `json:"depth,omitempty"`  // 1: started by the session; 2+: by another agent
	Parent      string    `json:"parent,omitempty"` // the agent that started this one, if any
	State       string    `json:"state"`            // "running", "done" or "stopped"
	Started     time.Time `json:"started,omitempty"`
	LastActive  time.Time `json:"last_active,omitempty"`
	Tools       int       `json:"tools"`
	Doing       string    `json:"doing,omitempty"` // its latest tool call, "Bash: npm test"
}

// An agent that has stopped writing is "stopped" rather than "running" after this long idle,
// unless a tool call of its is still open: a long build writes nothing until it ends.
const (
	agentIdle     = 5 * time.Minute
	agentToolIdle = 60 * time.Minute
)

// agentRead is what has been read of one agent's transcript so far, so that each look reads
// only what was appended since the last.
type agentRead struct {
	offset  int64
	partial []byte
	first   time.Time
	last    time.Time
	tools   int
	doing   string
	open    map[string]bool // tool calls without a result yet
	ended   bool            // its last word was a final answer (end_turn)
}

var (
	agentMu    sync.Mutex
	agentCache = map[string]*agentRead{}
)

// SubagentsDir is where a session's agents are written, beside its transcript.
func SubagentsDir(transcriptPath string) string {
	return filepath.Join(strings.TrimSuffix(transcriptPath, ".jsonl"), "subagents")
}

// Agents lists the subagents of the session whose transcript is at path, newest first.
func Agents(path string) []Agent {
	return agentsIn(SubagentsDir(path), time.Now())
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

func agentsIn(dir string, now time.Time) []Agent {
	files, _ := filepath.Glob(filepath.Join(dir, "agent-*.jsonl"))
	out := make([]Agent, 0, len(files))
	for _, f := range files {
		id := strings.TrimSuffix(strings.TrimPrefix(filepath.Base(f), "agent-"), ".jsonl")
		a := Agent{ID: id}
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
			a.Depth, a.Model, a.Background = meta.SpawnDepth, meta.Model, meta.RequestShape == "background"
		}
		r := readAgent(f)
		if r == nil {
			continue
		}
		a.Started, a.LastActive, a.Tools, a.Doing = r.first, r.last, r.tools, r.doing
		idle := now.Sub(r.last)
		switch {
		case r.ended:
			a.State = "done"
		case len(r.open) > 0 && idle < agentToolIdle, len(r.open) == 0 && idle < agentIdle:
			a.State = "running"
		default:
			a.State = "stopped"
		}
		out = append(out, a)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Started.After(out[j].Started) })
	return out
}

// readAgent brings the cached reading of one agent's transcript up to date and returns a copy.
func readAgent(path string) *agentRead {
	agentMu.Lock()
	defer agentMu.Unlock()
	r := agentCache[path]
	st, err := os.Stat(path)
	if err != nil {
		delete(agentCache, path)
		return nil
	}
	if r == nil || st.Size() < r.offset {
		r = &agentRead{open: map[string]bool{}}
		agentCache[path] = r
	}
	if st.Size() > r.offset {
		if f, err := os.Open(path); err == nil {
			if _, err := f.Seek(r.offset, io.SeekStart); err == nil {
				chunk, _ := io.ReadAll(bufio.NewReader(f))
				r.offset += int64(len(chunk))
				data := append(r.partial, chunk...)
				lines := strings.Split(string(data), "\n")
				r.partial = []byte(lines[len(lines)-1]) // an unfinished line waits for the rest
				for _, l := range lines[:len(lines)-1] {
					r.take([]byte(l))
				}
			}
			f.Close()
		}
	}
	if r.last.IsZero() {
		r.last = st.ModTime()
	}
	c := *r
	c.open = map[string]bool{}
	for k := range r.open {
		c.open[k] = true
	}
	return &c
}

// take reads one record of an agent's transcript.
func (r *agentRead) take(line []byte) {
	var rec struct {
		Type      string    `json:"type"`
		Timestamp time.Time `json:"timestamp"`
		Message   struct {
			Content    json.RawMessage `json:"content"`
			StopReason string          `json:"stop_reason"`
		} `json:"message"`
	}
	if json.Unmarshal(line, &rec) != nil {
		return
	}
	if !rec.Timestamp.IsZero() {
		if r.first.IsZero() {
			r.first = rec.Timestamp
		}
		r.last = rec.Timestamp
	}
	var blocks []struct {
		Type      string `json:"type"`
		ID        string `json:"id"`
		ToolUseID string `json:"tool_use_id"`
	}
	_ = json.Unmarshal(rec.Message.Content, &blocks)
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
		// A final answer ends the agent; anything after it (a follow-up message sent to
		// it) starts it again.
		r.ended = rec.Message.StopReason == "end_turn"
	case "user":
		for _, b := range blocks {
			if b.Type == "tool_result" {
				delete(r.open, b.ToolUseID)
			}
		}
		if len(blocks) == 0 || blocks[0].Type != "tool_result" {
			r.ended = false // a new message to the agent: it is at work again
		}
	}
}
