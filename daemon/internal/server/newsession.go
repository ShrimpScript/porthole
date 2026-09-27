package server

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
)

// newClaude starts a fresh Claude Code in dir, in a tmux session of its own named after
// the folder (numbered when the name is taken) - what `porthole` does at the desk, from
// the phone. It never reuses a session: this is for starting another task. The phone
// finds the new session by the pane in the answer, once Claude Code registers there.
func (s *Server) newClaude(ctx context.Context, w *writer, dir, device string) {
	if !s.hasCap(proto.CapStart) {
		_ = w.send(ctx, proto.NewError("no_start", "tmux is not installed on the computer"))
		return
	}
	cwd, err := projectDir(dir)
	if err != nil {
		_ = w.send(ctx, proto.NewError("no_dir", err.Error()))
		return
	}
	cctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	name := tmuxNameFor(cctx, cwd)
	pane, err := newTmuxSession(cctx, name, cwd)
	if err != nil {
		_ = w.send(ctx, proto.NewError("tmux_failed", "Couldn't start tmux on the computer: "+err.Error()))
		return
	}
	time.Sleep(300 * time.Millisecond) // let the shell print its prompt before typing
	if err := typeLine(cctx, pane, "claude"); err != nil {
		_ = w.send(ctx, proto.NewError("tmux_failed", "could not type into the tmux window"))
		return
	}
	s.log.Info("new claude session from the phone", "dir", cwd, "tmux", name, "pane", pane, "from", device)
	_ = w.send(ctx, startedFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionStarted},
		Tmux: name, Pane: pane, Mode: "new", Cwd: cwd})
	// A folder Claude Code has not been used in asks first whether to trust it, before
	// the session registers; the phone that asked for it is the one to answer.
	go s.watchTrust(ctx, w, pane, name, cwd)
}

// typeLine types a line into a pane and presses Enter.
func typeLine(ctx context.Context, pane, line string) error {
	if _, err := runCmd(ctx, "tmux", "send-keys", "-t", pane, "-l", line); err != nil {
		return err
	}
	_, err := runCmd(ctx, "tmux", "send-keys", "-t", pane, "Enter")
	return err
}

// projectDir is the folder the phone asked for: an absolute path, or one under the home
// directory written with ~. It must exist and be a directory.
func projectDir(p string) (string, error) {
	p = strings.TrimSpace(p)
	if p == "~" || strings.HasPrefix(p, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		p = filepath.Join(home, strings.TrimPrefix(p, "~"))
	}
	if !filepath.IsAbs(p) {
		return "", errors.New("give the folder as a full path, or one starting with ~/")
	}
	p = filepath.Clean(p)
	st, err := os.Stat(p)
	if err != nil {
		return "", errors.New("there is no folder " + p + " on the computer")
	}
	if !st.IsDir() {
		return "", errors.New(p + " is a file, not a folder")
	}
	return p, nil
}
