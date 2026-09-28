package session

import (
	"encoding/json"
	"github.com/shrimpscript/porthole/daemon/internal/platform"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// Proc is one running Claude Code process, read from the registry the CLI keeps at
// ~/.claude/sessions/<pid>.json: which transcript it writes, in which directory, and
// which tmux pane it lives in. It is the only exact link between a process and a
// transcript. A tmux session sitting in the right directory is a guess, and with two
// sessions in one directory that guess joins their feeds and types into the wrong one.
type Proc struct {
	PID       int
	SessionID string
	Cwd       string
	Name      string // the CLI's own name for the session ("proj-9a"), as /resume shows it
	Status    string // "busy", "waiting", "idle" or "shell", as the CLI reports it
	// WaitingFor is the CLI's reason while Status is "waiting": "permission prompt", "input
	// needed" (a tool asking the person), "sandbox request", "worker request", "goal
	// proposal", or "dialog open" (a menu such as /model left open). Empty from older CLIs.
	WaitingFor string
	TmuxSess   string // tmux session name; "" when Claude runs outside tmux
	Window     string // tmux window id, "@3"
	Pane       string // tmux pane id, "%7"
	StartedAt  time.Time
}

// SessionsDir is the registry directory, beside the transcripts.
func SessionsDir() string { return filepath.Join(filepath.Dir(transcript.ProjectsDir()), "sessions") }

// Procs lists registry entries whose process is still the one that wrote them.
func Procs() []Proc { return procsIn(SessionsDir(), procAlive) }

func procsIn(dir string, alive func(pid int, start string) bool) []Proc {
	files, _ := filepath.Glob(filepath.Join(dir, "*.json"))
	var out []Proc
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		var r struct {
			PID        int    `json:"pid"`
			SessionID  string `json:"sessionId"`
			Cwd        string `json:"cwd"`
			ProcStart  string `json:"procStart"`
			Kind       string `json:"kind"`
			Tmux       string `json:"tmux"`
			Name       string `json:"name"`
			Status     string `json:"status"`
			WaitingFor string `json:"waitingFor"`
			StartedAt  int64  `json:"startedAt"`
		}
		if json.Unmarshal(b, &r) != nil || r.PID <= 0 || r.SessionID == "" {
			continue
		}
		// Headless runs (claude -p, the SDK) have no screen to type into and would
		// flood the list from scripts; only interactive sessions are sessions here.
		if r.Kind != "" && r.Kind != "interactive" {
			continue
		}
		if !alive(r.PID, r.ProcStart) {
			continue
		}
		p := Proc{PID: r.PID, SessionID: r.SessionID, Cwd: r.Cwd, Name: r.Name, Status: r.Status, WaitingFor: r.WaitingFor}
		if r.StartedAt > 0 {
			p.StartedAt = time.UnixMilli(r.StartedAt)
		}
		p.TmuxSess, p.Window, p.Pane = parseTmuxRef(r.Tmux)
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.Before(out[j].StartedAt) })
	return out
}

// parseTmuxRef splits the registry's "session:@window.%pane". tmux forbids ':' and '.'
// in session names, so the last ":@" and ".%" are unambiguous.
func parseTmuxRef(s string) (sess, window, pane string) {
	i := strings.LastIndex(s, ":@")
	if i < 0 {
		return "", "", ""
	}
	sess = s[:i]
	rest := s[i+1:]
	j := strings.LastIndex(rest, ".%")
	if j < 0 {
		return sess, rest, ""
	}
	return sess, rest[:j], rest[j+1:]
}

// procAlive checks the process's start time against the registry's, so a pid the kernel
// has reused for something else does not resurrect a dead session. The registry holds
// the start time in the form this OS gives it (see platform.ProcStart).
func procAlive(pid int, start string) bool {
	got, ok := platform.ProcStart(pid)
	return ok && (start == "" || got == "" || got == start)
}

// Successor says whether a transcript that just appeared in a session's directory is
// where a feed following that session should move. Only a restart moves it: the session
// followed has ended, and the new one runs in the same pane. A second Claude started
// beside a live one is a different session, never a continuation.
func Successor(old Info, newPath string) bool {
	return successor(old, newPath, Procs())
}

func successor(old Info, newPath string, procs []Proc) bool {
	if len(procs) == 0 {
		return true // no registry (an older CLI): the directory's newest is all there is
	}
	newID := strings.TrimSuffix(filepath.Base(newPath), ".jsonl")
	var np *Proc
	for i := range procs {
		if procs[i].SessionID == old.ID {
			return false
		}
		if procs[i].SessionID == newID {
			np = &procs[i]
		}
	}
	if np == nil {
		return false // a file without a process behind it is not a session
	}
	if old.Pane == "" || np.Pane == "" {
		return realpath(np.Cwd) == realpath(old.Cwd)
	}
	return np.Pane == old.Pane
}

// ProjectSlugForTest is transcript.ProjectSlug, re-exported so this package's tests can
// lay out a fake projects directory.
func ProjectSlugForTest(cwd string) string { return transcript.ProjectSlug(cwd) }
