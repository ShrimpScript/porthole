// Package session finds Claude Code sessions on this machine and watches them.
package session

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

type Info struct {
	ID         string    `json:"id"`    // the transcript uuid
	Title      string    `json:"title"` // ai-title/agent-name, else the directory name
	Cwd        string    `json:"cwd"`
	Branch     string    `json:"branch,omitempty"`
	LastActive time.Time `json:"last_active"`
	// Live means Claude Code is running in a tmux pane in this directory: a prompt can
	// be typed to it. Tmux means a tmux session exists there at all, so the terminal can
	// attach even when Claude is not running (and someone could start it).
	Live    bool   `json:"live"`
	Tmux    bool   `json:"has_tmux"`
	Working bool   `json:"working"`         // a prompt is in flight, per the transcript
	Model   string `json:"model,omitempty"` // the latest assistant message's model
	// WorkingSince is when the current turn's prompt was recorded; Doing is the tool the
	// assistant last asked for ("Bash: ls -la", "Read App.tsx"), for the list's live line.
	WorkingSince time.Time `json:"working_since,omitempty"`
	Doing        string    `json:"doing,omitempty"`
	Asking       string    `json:"asking,omitempty"` // the question Claude is waiting on the person to answer
	TmuxName     string    `json:"tmux,omitempty"`
	Pane         string    `json:"pane,omitempty"` // the pane Claude runs in, from the CLI's registry; keystrokes go here
	Window       string    `json:"-"`              // its tmux window id, for the terminal mirror
	Name         string    `json:"name,omitempty"` // the CLI's own session name
	Transcript   string    `json:"-"`
	Size         int64     `json:"-"`
}

// peek reads session metadata without parsing the whole transcript. Transcripts can reach
// tens of megabytes, and the sessions list must not pay that cost: the first line
// carries cwd/version, and the most recent title and branch are near the end.
type peeked struct {
	transcript.Meta
	Working      bool
	Model        string
	WorkingSince time.Time
	Asking       string
	Doing        string
}

func peek(path string, size int64) (peeked, error) {
	var meta peeked
	f, err := os.Open(path)
	if err != nil {
		return meta, err
	}
	defer f.Close()

	apply := func(line []byte) {
		var d struct {
			Type      string `json:"type"`
			Subtype   string `json:"subtype"`
			SessionID string `json:"sessionId"`
			Cwd       string `json:"cwd"`
			GitBranch string `json:"gitBranch"`
			Title     string `json:"title"`
			AiTitle   string `json:"aiTitle"` // what the CLI actually writes for ai-title records
			AgentName string `json:"agentName"`
			Timestamp string `json:"timestamp"`
			Message   *struct {
				Model      string          `json:"model"`
				StopReason string          `json:"stop_reason"`
				Content    json.RawMessage `json:"content"`
			} `json:"message"`
		}
		if json.Unmarshal(line, &d) != nil {
			return
		}
		// The working state is the same rule the feed uses (transcript.State): a prompt
		// or a tool result starts a turn, end_turn / interrupt / turn_duration ends it.
		switch d.Type {
		case "assistant":
			if d.Message != nil {
				if d.Message.Model != "" {
					meta.Model = d.Message.Model
				}
				meta.Working = d.Message.StopReason != "end_turn"
				if l := ToolLabel(d.Message.Content); l != "" {
					meta.Doing = l
				}
				meta.Asking = askingText(d.Message.Content)
				if !meta.Working {
					meta.Doing, meta.Asking = "", ""
				}
			}
		case "user":
			if d.Message != nil {
				if userStartsTurn(d.Message.Content) {
					// A tool result or a prompt: the question was dealt with. An injected
					// envelope (<task-notification>, a reminder) is not, and must not clear it.
					// Only the record that opens the turn sets its start: a tool result
					// inside a running turn is not a new one, and restarting the clock there
					// made "working for" read seconds all through a long turn.
					if t, err := time.Parse(time.RFC3339Nano, d.Timestamp); err == nil && !meta.Working {
						meta.WorkingSince = t
					}
					meta.Asking = ""
					meta.Working = true
					meta.Doing = ""
				}
				if isInterrupt(d.Message.Content) {
					meta.Working = false
					meta.Doing, meta.Asking = "", ""
				}
			}
		case "system":
			if d.Subtype == "turn_duration" {
				meta.Working = false
				meta.Doing, meta.Asking = "", ""
			}
		}
		if d.SessionID != "" {
			meta.SessionID = d.SessionID
		}
		if d.Cwd != "" {
			meta.Cwd = d.Cwd
		}
		if d.GitBranch != "" {
			meta.GitBranch = d.GitBranch
		}
		if d.Type == "ai-title" || d.Type == "agent-name" {
			switch {
			case d.AiTitle != "":
				meta.Title = d.AiTitle
			case d.Title != "":
				meta.Title = d.Title
			case d.AgentName != "":
				meta.Title = d.AgentName
			}
		}
	}

	// Head. Not just the first line: a transcript often opens with `mode` and
	// `permission-mode` records that carry no cwd, so read until the metadata is found
	// or the budget runs out.
	const headLines = 60
	head := bufio.NewScanner(f)
	head.Buffer(make([]byte, 0, 1<<20), 8<<20)
	for i := 0; i < headLines && head.Scan(); i++ {
		apply(head.Bytes())
		if meta.Cwd != "" && meta.SessionID != "" {
			break
		}
	}

	// Tail: the newest title, branch and working state. Small files are read whole,
	// because skipping the tail on a short transcript is how a session ends up with no
	// title at all. The head was for cwd and id: whatever it said about working, since
	// and doing is hours old and must not survive into the answer.
	meta.Working, meta.WorkingSince, meta.Doing, meta.Asking = false, time.Time{}, "", ""
	const window = 512 << 10
	seekTo := int64(0)
	skipPartial := false
	if size > window {
		seekTo, skipPartial = size-window, true
	}
	if _, err := f.Seek(seekTo, io.SeekStart); err == nil {
		tail := bufio.NewScanner(f)
		tail.Buffer(make([]byte, 0, 1<<20), 8<<20)
		if skipPartial {
			tail.Scan() // discard the partial first line
		}
		for tail.Scan() {
			apply(tail.Bytes())
		}
	}
	if meta.Title == "" {
		// The title record lands a few turns in, which on a long transcript is neither
		// in the head nor within the tail window. One full scan, remembered.
		meta.Title = scanTitle(path, size)
	}
	return meta, nil
}

// titles caches the one full-file title scan per transcript: found titles for good
// (the tail catches a later rename), and "none" until the file has grown by a megabyte.
var titles = struct {
	mu sync.Mutex
	m  map[string]titleScan
}{m: map[string]titleScan{}}

type titleScan struct {
	title string
	size  int64
}

func scanTitle(path string, size int64) string {
	titles.mu.Lock()
	c, ok := titles.m[path]
	titles.mu.Unlock()
	if ok && (c.title != "" || size-c.size < 1<<20) {
		return c.title
	}
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	var title string
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 1<<20), 8<<20)
	for sc.Scan() {
		line := sc.Bytes()
		if !bytes.Contains(line, []byte(`"ai-title"`)) && !bytes.Contains(line, []byte(`"agent-name"`)) {
			continue
		}
		var d struct {
			Type      string `json:"type"`
			AiTitle   string `json:"aiTitle"`
			Title     string `json:"title"`
			AgentName string `json:"agentName"`
		}
		if json.Unmarshal(line, &d) != nil || (d.Type != "ai-title" && d.Type != "agent-name") {
			continue
		}
		switch {
		case d.AiTitle != "":
			title = d.AiTitle
		case d.Title != "":
			title = d.Title
		case d.AgentName != "":
			title = d.AgentName
		}
	}
	titles.mu.Lock()
	titles.m[path] = titleScan{title: title, size: size}
	titles.mu.Unlock()
	return title
}

// askingText is the first question of an AskUserQuestion call in an assistant message's
// content, "" when there is none.
func askingText(content json.RawMessage) string {
	var blocks []map[string]any
	if json.Unmarshal(content, &blocks) != nil {
		return ""
	}
	for _, b := range blocks {
		if b["type"] != "tool_use" || b["name"] != "AskUserQuestion" {
			continue
		}
		input, _ := b["input"].(map[string]any)
		qs, _ := input["questions"].([]any)
		for _, q := range qs {
			if m, ok := q.(map[string]any); ok {
				if t, _ := m["question"].(string); strings.TrimSpace(t) != "" {
					return strings.TrimSpace(t)
				}
			}
		}
	}
	return ""
}

// ToolLabel names the last tool_use block in an assistant message the way the feed does:
// the tool, then the one input that identifies the call, cut short.
func ToolLabel(content json.RawMessage) string {
	var blocks []struct {
		Type  string                     `json:"type"`
		Name  string                     `json:"name"`
		Input map[string]json.RawMessage `json:"input"`
	}
	if json.Unmarshal(content, &blocks) != nil {
		return ""
	}
	label := ""
	for _, b := range blocks {
		if b.Type != "tool_use" || b.Name == "" {
			continue
		}
		hint := ""
		for _, k := range []string{"command", "file_path", "pattern", "path", "query", "url", "description"} {
			if raw, ok := b.Input[k]; ok {
				var v string
				if json.Unmarshal(raw, &v) == nil && v != "" {
					if k == "file_path" || k == "path" {
						v = filepath.Base(v)
					}
					hint = v
					break
				}
			}
		}
		hint = strings.Join(strings.Fields(hint), " ")
		if r := []rune(hint); len(r) > 40 {
			hint = string(r[:39]) + "\u2026"
		}
		if hint != "" {
			label = b.Name + ": " + hint
		} else {
			label = b.Name
		}
	}
	return label
}

// userStartsTurn reports whether a user record is a real prompt or a tool result - the
// two records after which Claude is working again. Envelopes injected with role=user are
// neither.
func userStartsTurn(content json.RawMessage) bool {
	var s string
	if json.Unmarshal(content, &s) == nil {
		kind, _ := transcript.ClassifyUser(s)
		return kind == "user"
	}
	var blocks []map[string]any
	if json.Unmarshal(content, &blocks) != nil {
		return false
	}
	for _, b := range blocks {
		if b["type"] == "tool_result" {
			return true
		}
		if b["type"] == "text" {
			if t, ok := b["text"].(string); ok {
				if kind, _ := transcript.ClassifyUser(t); kind == "user" {
					return true
				}
			}
		}
	}
	return false
}

func isInterrupt(content json.RawMessage) bool {
	var s string
	if json.Unmarshal(content, &s) == nil {
		_, disp := transcript.ClassifyUser(s)
		return disp == "You interrupted"
	}
	var blocks []map[string]any
	if json.Unmarshal(content, &blocks) != nil {
		return false
	}
	for _, b := range blocks {
		if t, ok := b["text"].(string); ok {
			if _, disp := transcript.ClassifyUser(t); disp == "You interrupted" {
				return true
			}
		}
	}
	return false
}

// realpath resolves symlinks, returning the input unchanged when that is not possible.
// Needed because a transcript may record $HOME while tmux reports the resolved path
// behind a bind mount for the same directory - exact string matching would silently
// report every session as idle.
func realpath(p string) string {
	if p == "" {
		return p
	}
	if r, err := filepath.EvalSymlinks(p); err == nil {
		return r
	}
	return p
}

type tmuxInfo struct {
	name   string
	claude bool // a pane in this session is running claude
}

// tmuxSessions maps a working directory to its tmux session, noting whether Claude Code
// is actually running in it. A tmux session sitting in the right directory is NOT enough:
// a message sent from the phone and typed into an idle shell runs as a shell command.
func tmuxSessions() map[string]tmuxInfo {
	out := map[string]tmuxInfo{}
	cmd := exec.Command("tmux", "list-panes", "-a", "-F", "#{session_path}\t#{session_name}\t#{pane_current_command}\t#{pane_pid}")
	b, err := cmd.Output()
	if err != nil {
		// No tmux, or no server running. Both are normal: sessions are simply not live.
		return out
	}
	tree := processTree()
	for _, line := range strings.Split(strings.TrimSpace(string(b)), "\n") {
		parts := strings.Split(line, "\t")
		if len(parts) < 4 || parts[0] == "" {
			continue
		}
		path, name, current, pidStr := parts[0], parts[1], parts[2], parts[3]
		pid, _ := strconv.Atoi(pidStr)
		running := current == "claude" || tree.hasDescendant(pid, "claude")
		for _, key := range []string{path, realpath(path)} {
			prev, seen := out[key]
			if !seen || (running && !prev.claude) {
				out[key] = tmuxInfo{name: name, claude: running}
			}
		}
	}
	return out
}

// procTree is a snapshot of pid -> children with command names, so a wrapper script or
// shell function that launches claude underneath still counts as running it.
type procTree struct {
	children map[int][]int
	comm     map[int]string
}

func processTree() procTree {
	t := procTree{children: map[int][]int{}, comm: map[int]string{}}
	b, err := exec.Command("ps", "-eo", "pid=,ppid=,comm=").Output()
	if err != nil {
		return t
	}
	for _, line := range strings.Split(strings.TrimSpace(string(b)), "\n") {
		f := strings.Fields(line)
		if len(f) < 3 {
			continue
		}
		pid, _ := strconv.Atoi(f[0])
		ppid, _ := strconv.Atoi(f[1])
		t.comm[pid] = f[2]
		t.children[ppid] = append(t.children[ppid], pid)
	}
	return t
}

func (t procTree) hasDescendant(pid int, name string) bool {
	for _, c := range t.children[pid] {
		if t.comm[c] == name || t.hasDescendant(c, name) {
			return true
		}
	}
	return false
}

// Resolve finds a session by transcript id. An id the phone learned earlier may belong
// to a transcript that a restarted Claude Code has since superseded in the same
// directory; that directory's newest session is what the phone means.
func Resolve(id string) (Info, bool) {
	list, err := List()
	if err != nil {
		return Info{}, false
	}
	for _, si := range list {
		if si.ID == id {
			return si, true
		}
	}
	// Gone. Its successor is the session now running in the pane it had (a restart),
	// never a session that merely shares the directory while another is live there.
	if pane := rememberedPane(id); pane != "" {
		for _, si := range list {
			if si.Live && si.Pane == pane {
				return si, true
			}
		}
	}
	matches, _ := filepath.Glob(filepath.Join(transcript.ProjectsDir(), "*", id+".jsonl"))
	if len(matches) == 0 {
		return Info{}, false
	}
	dir := filepath.Dir(matches[0])
	var inDir []Info
	for _, si := range list {
		if filepath.Dir(si.Transcript) == dir {
			inDir = append(inDir, si)
		}
	}
	switch {
	case len(inDir) == 1:
		return inDir[0], true
	case len(inDir) > 1:
		return Info{}, false // two candidates: the phone must pick from the list
	}
	return Info{}, false
}

// panes remembers which pane each live session had, so an id the phone holds can be
// followed to the session that replaced it in that pane after a restart.
var panes = struct {
	mu sync.Mutex
	m  map[string]string
}{m: map[string]string{}}

func rememberPane(id, pane string) {
	if pane == "" {
		return
	}
	panes.mu.Lock()
	panes.m[id] = pane
	panes.mu.Unlock()
}

func rememberedPane(id string) string {
	panes.mu.Lock()
	defer panes.mu.Unlock()
	return panes.m[id]
}

// List returns one entry per running Claude Code process (from the CLI's registry),
// plus, for directories where none runs, the most recent transcript there so it can be
// resumed. Without a registry (an older CLI) it falls back to one entry per directory
// with liveness guessed from tmux. Newest first.
func List() ([]Info, error) {
	procs := Procs()
	if len(procs) == 0 {
		return listLegacy()
	}
	tmux := tmuxSessions()
	var out []Info
	// One row per session, not per process: `claude --resume <id>` in a second pane is
	// the same conversation in two places, and the phone showed it twice - which is a
	// duplicate key in a list and, before this, a crash. The newest process wins, since
	// that is the pane the person moved to.
	byID := map[string]int{}
	liveDirs := map[string]bool{}
	for _, p := range procs {
		path := transcriptFor(p)
		var meta peeked
		var size int64
		last := p.StartedAt
		if st, err := os.Stat(path); err == nil {
			size, last = st.Size(), st.ModTime()
			if m, err := peek(path, size); err == nil {
				meta = m
			}
		}
		title := meta.Title
		if title == "" {
			title = p.Name
		}
		if title == "" {
			title = filepath.Base(p.Cwd)
		}
		cwd := meta.Cwd
		if cwd == "" {
			cwd = p.Cwd
		}
		si := Info{
			ID: p.SessionID, Title: title, Cwd: cwd, Branch: meta.GitBranch, LastActive: last,
			Live: true, Tmux: p.Pane != "", Working: meta.Working, Model: meta.Model,
			TmuxName: p.TmuxSess, Pane: p.Pane, Window: p.Window, Name: p.Name,
			Transcript: path, Size: size,
		}
		if meta.Working {
			si.WorkingSince, si.Doing, si.Asking = meta.WorkingSince, meta.Doing, meta.Asking
		}
		// The CLI's own word beats a reading of its transcript. A record the classifier
		// has not met yet - a new wrapper around a local command, say - can open a turn
		// that no reply ever closes, and the session then reads as working for days.
		// "idle" and "shell" (running a ! command) are not a turn; "busy" and "waiting"
		// (on a question or a permission prompt) leave the transcript's reading alone.
		if notWorking(p.Status) {
			si.Working, si.WorkingSince, si.Doing, si.Asking = false, time.Time{}, "", ""
		}
		rememberPane(si.ID, si.Pane)
		if at, seen := byID[si.ID]; seen {
			out[at] = si // procs are oldest first, so this is the newer process
		} else {
			byID[si.ID] = len(out)
			out = append(out, si)
		}
		liveDirs[filepath.Dir(path)] = true
	}
	// Directories with nothing running: their newest transcript, resumable.
	dirs, _ := filepath.Glob(filepath.Join(transcript.ProjectsDir(), "*"))
	for _, dir := range dirs {
		if liveDirs[dir] {
			continue
		}
		newest, st := newestTranscript(dir)
		if newest == "" {
			continue
		}
		meta, err := peek(newest, st.Size())
		if err != nil {
			continue
		}
		title := meta.Title
		if title == "" {
			title = filepath.Base(meta.Cwd)
		}
		if title == "" || title == "." {
			title = filepath.Base(dir)
		}
		tm, has := tmux[meta.Cwd]
		if !has {
			tm, has = tmux[realpath(meta.Cwd)]
		}
		out = append(out, Info{
			ID: strings.TrimSuffix(filepath.Base(newest), ".jsonl"), Title: title, Cwd: meta.Cwd,
			Branch: meta.GitBranch, LastActive: st.ModTime(), Tmux: has, Model: meta.Model,
			TmuxName: tm.name, Transcript: newest, Size: st.Size(),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastActive.After(out[j].LastActive) })
	return out, nil
}

// transcriptFor is the file a registered process writes: under the project slug of its
// directory, by session id. The slug is what the CLI derives from the cwd it was given,
// so the registry's cwd is tried as-is first, then resolved, then found by id.
func transcriptFor(p Proc) string {
	for _, cwd := range []string{p.Cwd, realpath(p.Cwd)} {
		path := filepath.Join(transcript.ProjectsDir(), transcript.ProjectSlug(cwd), p.SessionID+".jsonl")
		if _, err := os.Stat(path); err == nil {
			return path
		}
	}
	if m, _ := filepath.Glob(filepath.Join(transcript.ProjectsDir(), "*", p.SessionID+".jsonl")); len(m) > 0 {
		return m[0]
	}
	return filepath.Join(transcript.ProjectsDir(), transcript.ProjectSlug(p.Cwd), p.SessionID+".jsonl")
}

// newestTranscript is the most recently written transcript in a project directory that
// holds more than a header.
func newestTranscript(dir string) (string, os.FileInfo) {
	files, _ := filepath.Glob(filepath.Join(dir, "*.jsonl"))
	var newest string
	var newestInfo os.FileInfo
	for _, f := range files {
		st, err := os.Stat(f)
		if err != nil || st.Size() < 200 {
			continue
		}
		if newestInfo == nil || st.ModTime().After(newestInfo.ModTime()) {
			newest, newestInfo = f, st
		}
	}
	return newest, newestInfo
}

// listLegacy is the pre-registry discovery: one entry per project directory, live when
// a tmux session in that directory runs claude.
func listLegacy() ([]Info, error) {
	dirs, err := filepath.Glob(filepath.Join(transcript.ProjectsDir(), "*"))
	if err != nil {
		return nil, err
	}
	tmux := tmuxSessions()

	var out []Info
	for _, dir := range dirs {
		fi, err := os.Stat(dir)
		if err != nil || !fi.IsDir() {
			continue
		}
		newest, newestInfo := newestTranscript(dir)
		if newest == "" {
			continue
		}
		meta, err := peek(newest, newestInfo.Size())
		if err != nil {
			continue
		}
		title := meta.Title
		if title == "" {
			title = filepath.Base(meta.Cwd)
		}
		if title == "" || title == "." {
			title = filepath.Base(dir)
		}
		tm, has := tmux[meta.Cwd]
		if !has {
			tm, has = tmux[realpath(meta.Cwd)]
		}
		live := has && tm.claude
		out = append(out, Info{
			ID:         strings.TrimSuffix(filepath.Base(newest), ".jsonl"),
			Title:      title,
			Cwd:        meta.Cwd,
			Branch:     meta.GitBranch,
			LastActive: newestInfo.ModTime(),
			Live:       live,
			Tmux:       has,
			Working:    live && meta.Working,
			Model:      meta.Model,
			WorkingSince: func() time.Time {
				if live && meta.Working {
					return meta.WorkingSince
				}
				return time.Time{}
			}(),
			Doing: func() string {
				if live && meta.Working {
					return meta.Doing
				}
				return ""
			}(),
			TmuxName:   tm.name,
			Transcript: newest,
			Size:       newestInfo.Size(),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastActive.After(out[j].LastActive) })
	return out, nil
}

// notWorking reports whether the registry status rules out a running turn. An empty
// status is an older CLI that does not say, so the transcript decides.
func notWorking(status string) bool { return status == "idle" || status == "shell" }
