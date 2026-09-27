package server

import (
	"context"
	"strings"
	"sync"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
)

// TrustPrompt is Claude Code's "do you trust this folder" question, read off the screen.
// It comes up the first time Claude Code runs in a folder, before the session registers
// - so the phone cannot see that session yet, and without this it waited on a question
// only the desk could answer.
type TrustPrompt struct {
	Folder string   // the folder it names
	Lines  []string // the option lines, as drawn
	Yes    int      // index into Lines of the option that trusts the folder
	At     int      // index into Lines of the selected option
}

// parseTrust reads the prompt out of a captured pane. Nil when it is not showing.
func parseTrust(screen string) *TrustPrompt {
	if !strings.Contains(screen, "Quick safety check") && !strings.Contains(screen, "trust the files in this folder") {
		return nil
	}
	lines := strings.Split(screen, "\n")
	foot := -1
	for i := len(lines) - 1; i >= 0; i-- {
		if strings.Contains(lines[i], "Enter to confirm") && strings.Contains(lines[i], "Esc to") {
			foot = i
			break
		}
	}
	if foot < 0 {
		return nil
	}
	t := &TrustPrompt{Yes: -1, At: -1}
	// The folder is the first line with anything on it after "Accessing workspace:".
	for i := 0; i < foot; i++ {
		if !strings.Contains(lines[i], "Accessing workspace:") {
			continue
		}
		for j := i + 1; j < foot && t.Folder == ""; j++ {
			t.Folder = strings.TrimSpace(lines[j])
		}
		break
	}
	// The options sit just above the legend: every line from the first "Yes"/"No" one down.
	for i := foot - 1; i >= 0 && foot-i <= 6; i-- {
		l := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(lines[i]), "❯"))
		if l == "" {
			continue
		}
		// "Yes, I trust this folder", "1. Yes, proceed", "No, exit"...
		plain := strings.TrimLeft(l, "0123456789. ")
		if !strings.HasPrefix(plain, "Yes") && !strings.HasPrefix(plain, "No") {
			break
		}
		t.Lines = append([]string{lines[i]}, t.Lines...)
	}
	for i, l := range t.Lines {
		plain := strings.TrimLeft(strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(l), "❯")), "0123456789. ")
		if strings.HasPrefix(plain, "Yes") && t.Yes < 0 {
			t.Yes = i
		}
		if strings.HasPrefix(strings.TrimSpace(l), "❯") {
			t.At = i
		}
	}
	if t.Yes < 0 || t.At < 0 {
		return nil
	}
	return t
}

// trustAsk is the daemon's question to the phone that started the session.
type trustAsk struct {
	proto.Frame
	Pane   string `json:"pane"`
	Tmux   string `json:"tmux"`
	Cwd    string `json:"cwd"`
	Folder string `json:"folder"`
}

// started remembers the panes this daemon typed `claude` into for a phone, so a trust
// answer is only ever pressed into one of those, and a refusal only removes a tmux
// session the daemon made.
type startedPanes struct {
	mu    sync.Mutex
	panes map[string]string // pane -> tmux session it created
}

func (p *startedPanes) add(pane, tmux string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.panes == nil {
		p.panes = map[string]string{}
	}
	p.panes[pane] = tmux
}

func (p *startedPanes) take(pane string) (string, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	t, ok := p.panes[pane]
	delete(p.panes, pane)
	return t, ok
}

func (p *startedPanes) has(pane string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	_, ok := p.panes[pane]
	return ok
}

// watchTrust looks at a freshly started pane for Claude Code's trust prompt and asks the
// phone when it comes up. It gives up once Claude Code is past it, or after half a minute.
func (s *Server) watchTrust(ctx context.Context, w *writer, pane, tmux, cwd string) {
	deadline := time.Now().Add(30 * time.Second)
	asked := false
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return
		case <-time.After(500 * time.Millisecond):
		}
		out, err := runCmd(ctx, "tmux", "capture-pane", "-p", "-J", "-t", pane)
		if err != nil {
			return // the pane is gone
		}
		t := parseTrust(string(out))
		switch {
		case t != nil && !asked:
			asked = true
			s.newPanes.add(pane, tmux)
			_ = w.send(ctx, trustAsk{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionTrust},
				Pane: pane, Tmux: tmux, Cwd: cwd, Folder: t.Folder})
		case t == nil && asked:
			return // answered, here or at the desk
		}
	}
}

// answerTrust answers the trust prompt on a pane this daemon started for the phone:
// trust moves the selection onto the "Yes" option and confirms it; refusing presses Esc,
// which quits Claude Code, and removes the tmux session made for it.
func (s *Server) answerTrust(ctx context.Context, w *writer, pane string, trust bool, device string) {
	if !s.newPanes.has(pane) {
		_ = w.send(ctx, proto.NewError("not_asking", "that is not a session this phone started"))
		return
	}
	cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	out, err := runCmd(cctx, "tmux", "capture-pane", "-p", "-J", "-t", pane)
	t := parseTrust(string(out))
	if err != nil || t == nil {
		s.newPanes.take(pane)
		_ = w.send(ctx, proto.NewError("not_asking", "Claude Code is not asking about the folder any more"))
		return
	}
	tmux, _ := s.newPanes.take(pane)
	if !trust {
		_, _ = runCmd(cctx, "tmux", "send-keys", "-t", pane, "Escape")
		time.Sleep(500 * time.Millisecond)
		_, _ = runCmd(cctx, "tmux", "kill-session", "-t", "="+tmux)
		s.log.Info("folder not trusted; session removed", "tmux", tmux, "from", device)
		return
	}
	// Walk the selection to "Yes", then read the screen again before confirming: Enter
	// on the wrong line would quit Claude Code.
	key := "Down"
	steps := t.Yes - t.At
	if steps < 0 {
		key, steps = "Up", -steps
	}
	for i := 0; i < steps; i++ {
		_, _ = runCmd(cctx, "tmux", "send-keys", "-t", pane, key)
	}
	for i := 0; i < 20; i++ {
		time.Sleep(100 * time.Millisecond)
		out, _ = runCmd(cctx, "tmux", "capture-pane", "-p", "-J", "-t", pane)
		if now := parseTrust(string(out)); now != nil && now.At == now.Yes {
			_, _ = runCmd(cctx, "tmux", "send-keys", "-t", pane, "Enter")
			s.log.Info("folder trusted from the phone", "tmux", tmux, "from", device)
			return
		}
	}
	_ = w.send(ctx, proto.NewError("send_failed", "could not select \"Yes\" in the trust prompt; answer it in the terminal"))
}
