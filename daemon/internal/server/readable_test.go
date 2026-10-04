package server

import (
	"context"
	"io"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/creack/pty"

	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
)

// A window shrunk to one line with nobody at the desk is given a readable size, and its
// window-size is left as it was, so the next client sizes it as usual. One with a client
// attached is the client's business, and a readable one is left alone.
func TestAWindowTooSmallToReadIsResized(t *testing.T) {
	tmuxtest.Fresh(t)
	tm := func(args ...string) string {
		out, _ := exec.Command("tmux", args...).CombinedOutput()
		return strings.TrimSpace(string(out))
	}
	var err error
	for try := 0; try < 40; try++ {
		if err = exec.Command("tmux", "new-session", "-d", "-s", "tiny", "-x", "80", "-y", "1", "sleep 300").Run(); err == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if !ensureReadable(ctx, "=tiny:", nil) {
		t.Fatal("a one-line window was not resized")
	}
	if got := tm("display-message", "-p", "-t", "=tiny:", "#{window_width}x#{window_height}"); got != "120x40" {
		t.Fatalf("size %s", got)
	}
	if got := tm("show-options", "-w", "-t", "=tiny:", "window-size"); got != "" {
		t.Fatalf("window-size left pinned: %q", got)
	}
	if ensureReadable(ctx, "=tiny:", nil) {
		t.Fatal("a readable window was resized again")
	}
}

// Someone watching through another member of the session's group (a phone's mirror, a
// second terminal) is looking at the window: it is not resized under them.
func TestAWindowSomeoneWatchesIsLeftAlone(t *testing.T) {
	tmuxtest.Fresh(t)
	var err error
	for try := 0; try < 40; try++ {
		if err = exec.Command("tmux", "new-session", "-d", "-s", "main", "-x", "80", "-y", "1", "sleep 300").Run(); err == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	if err := exec.Command("tmux", "new-session", "-d", "-s", "view", "-t", "=main").Run(); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("tmux", "attach-session", "-t", "=view")
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")
	f, err := pty.StartWithSize(cmd, &pty.Winsize{Cols: 50, Rows: 10})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait(); f.Close() })
	go func() { _, _ = io.Copy(io.Discard, f) }()
	for i := 0; i < 50; i++ {
		out, _ := exec.Command("tmux", "list-clients", "-t", "=view").Output()
		if strings.TrimSpace(string(out)) != "" {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if ensureReadable(context.Background(), "=main:", nil) {
		t.Fatal("resized a window someone is watching through the group")
	}
}
