package termbridge

import (
	"context"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/creack/pty"

	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
)

func tmuxAvailable(t *testing.T) {
	t.Helper()
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not installed")
	}
}

// startSession makes a throwaway tmux session at a known size.
func startSession(t *testing.T, name string, cols, rows int) {
	t.Helper()
	tmuxtest.Require(t)
	_ = exec.Command("tmux", "kill-session", "-t", "="+name).Run()
	cmd := exec.Command("tmux", "new-session", "-d", "-s", name,
		"-x", strconv.Itoa(cols), "-y", strconv.Itoa(rows), "sh")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("could not start tmux session: %s", out)
	}
	t.Cleanup(func() {
		_ = exec.Command("tmux", "kill-session", "-t", "="+name).Run()
		_ = exec.Command("tmux", "kill-session", "-t", "="+mirrorName(name, "", "")).Run()
	})
}

func sessionWidth(t *testing.T, name string) int {
	t.Helper()
	// display-message returns empty for a detached session; list-windows does not.
	out, err := exec.Command("tmux", "list-windows", "-t", "="+name,
		"-F", "#{window_width}").Output()
	if err != nil {
		return -1
	}
	first := strings.SplitN(strings.TrimSpace(string(out)), "\n", 2)[0]
	n, _ := strconv.Atoi(first)
	return n
}

func TestBridgeCarriesOutput(t *testing.T) {
	tmuxAvailable(t)
	startSession(t, "porthole-bridge-test", 100, 30)

	b, err := Open(context.Background(), "porthole-bridge-test", "", "", 60, 20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer b.Close()

	// Let tmux paint, then type something recognisable.
	time.Sleep(600 * time.Millisecond)
	if _, err := b.Write([]byte("echo BRIDGE_OK\n")); err != nil {
		t.Fatalf("write: %v", err)
	}

	deadline := time.Now().Add(6 * time.Second)
	var seen strings.Builder
	buf := make([]byte, 4096)
	for time.Now().Before(deadline) {
		_ = setReadDeadlineBestEffort(b, 500*time.Millisecond)
		n, err := b.Read(buf)
		if n > 0 {
			seen.Write(buf[:n])
			if strings.Contains(seen.String(), "BRIDGE_OK") {
				return // the phone saw real terminal output
			}
		}
		if err != nil {
			break
		}
	}
	t.Fatalf("never saw the command output; got %.200q", seen.String())
}

// The whole reason for the session-group mirror: attaching a narrow phone must NOT
// shrink the desktop's own window. That squashing is the complaint this project exists
// to fix, so it gets an explicit regression test.
func TestPhoneDoesNotSquashTheDesktop(t *testing.T) {
	tmuxAvailable(t)
	const name = "porthole-squash-test"
	startSession(t, name, 200, 50)

	// A REAL desktop client at 200 columns. Without one the phone is the only client and
	// tmux resizes to it legitimately - the earlier version of this test skipped for that
	// reason and hid the bug.
	desk := exec.Command("tmux", "attach-session", "-t", "="+name)
	desk.Env = append(os.Environ(), "TERM=xterm-256color")
	dp, err := pty.StartWithSize(desk, &pty.Winsize{Cols: 200, Rows: 50})
	if err != nil {
		t.Skipf("could not attach a desktop client: %v", err)
	}
	go func() {
		b := make([]byte, 4096)
		for {
			if _, e := dp.Read(b); e != nil {
				return
			}
		}
	}()
	t.Cleanup(func() {
		_ = dp.Close()
		if desk.Process != nil {
			_ = desk.Process.Kill()
			_ = desk.Wait()
		}
	})
	time.Sleep(700 * time.Millisecond)

	before := sessionWidth(t, name)
	if before < 100 {
		t.Fatalf("desktop client did not take effect; width is %d", before)
	}

	b, err := Open(context.Background(), name, "", "", 60, 20) // a narrow "phone"
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer b.Close()
	go func() {
		buf := make([]byte, 4096)
		for {
			if _, e := b.Read(buf); e != nil {
				return
			}
		}
	}()
	time.Sleep(900 * time.Millisecond)

	if after := sessionWidth(t, name); after != before {
		t.Fatalf("a 60-column phone shrank the desktop window from %d to %d columns",
			before, after)
	}
}

func TestResizeDoesNotError(t *testing.T) {
	tmuxAvailable(t)
	startSession(t, "porthole-resize-test", 100, 30)
	b, err := Open(context.Background(), "porthole-resize-test", "", "", 60, 20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer b.Close()
	time.Sleep(400 * time.Millisecond)
	// Rotating to landscape is a resize, and it must be accepted.
	if err := b.Resize(120, 30); err != nil {
		t.Fatalf("resize: %v", err)
	}
}

func TestCloseRemovesOnlyTheMirror(t *testing.T) {
	tmuxAvailable(t)
	const name = "porthole-mirror-test"
	startSession(t, name, 100, 30)

	b, err := Open(context.Background(), name, "", "", 60, 20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	time.Sleep(400 * time.Millisecond)
	if err := b.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	time.Sleep(300 * time.Millisecond)

	if exec.Command("tmux", "has-session", "-t", "="+mirrorName(name, "", "")).Run() == nil {
		t.Fatal("the mirror session survived Close")
	}
	if exec.Command("tmux", "has-session", "-t", "="+name).Run() != nil {
		t.Fatal("Close killed the user's own session")
	}
}

// setReadDeadlineBestEffort keeps the read loop from blocking forever on a quiet
// terminal. A PTY is a file, so this is advisory.
func setReadDeadlineBestEffort(b *Bridge, d time.Duration) error {
	type deadliner interface{ SetReadDeadline(time.Time) error }
	if f, ok := any(b.ptmx).(deadliner); ok {
		return f.SetReadDeadline(time.Now().Add(d))
	}
	return nil
}

// TestReapRestoresWindowSize covers the crash path: a daemon that dies with a terminal
// open never runs Close, so the mirror survives and - the part the user actually feels -
// their own tmux window stays pinned to window-size=largest.
func TestReapRestoresWindowSize(t *testing.T) {
	tmuxAvailable(t)
	const target = "porthole-reap-test"
	startSession(t, target, 200, 50)
	defer exec.Command("tmux", "kill-session", "-t", "="+target).Run()

	ctx := context.Background()
	b, err := Open(ctx, target, "", "", 60, 20)
	if err != nil {
		t.Skipf("could not open bridge: %v", err)
	}
	if got := windowOption(ctx, target, "window-size"); got != "largest" {
		t.Fatalf("Open did not set window-size: got %q", got)
	}

	// Kill the way a crash does: drop the PTY and the mirror's client without Close.
	_ = b.ptmx.Close()
	if b.cmd != nil && b.cmd.Process != nil {
		_ = b.cmd.Process.Kill()
		_ = b.cmd.Wait()
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if !sessionExists(ctx, mirrorName(target, "", "")) {
			break
		}
		out, _ := exec.Command("tmux", "display-message", "-p", "-t", mirrorName(target, "", ""),
			"#{session_attached}").Output()
		if strings.TrimSpace(string(out)) == "0" {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}

	Reap(ctx)

	if sessionExists(ctx, mirrorName(target, "", "")) {
		t.Errorf("mirror session survived Reap")
	}
	if got := windowOption(ctx, target, "window-size"); got == "largest" {
		t.Errorf("window-size still pinned to largest after Reap")
	}
	if got := windowOption(ctx, target, prevOption); got != "" {
		t.Errorf("marker left behind: %q", got)
	}
}

// A mirror from an older group - its target closed and opened again - shows the old
// windows. Open must replace it, not reuse it, or the phone watches a frozen screen.
func TestOpenReplacesAMirrorFromAnotherGroup(t *testing.T) {
	tmuxAvailable(t)
	target := "porthole-stale-test"
	startSession(t, target, 80, 24)
	stale := mirrorName(target, "", "")
	// A lone session with the mirror's name: not in the target's group.
	if out, err := exec.Command("tmux", "new-session", "-d", "-s", stale, "-x", "80", "-y", "24").CombinedOutput(); err != nil {
		t.Skipf("could not make the stale mirror: %s", out)
	}
	defer exec.Command("tmux", "kill-session", "-t", "="+stale).Run()
	ctx := context.Background()
	if sameGroup(ctx, stale, target) {
		t.Fatal("setup: the stale mirror should not share the target's group")
	}
	b, err := Open(ctx, target, "", "", 60, 20)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if !sameGroup(ctx, stale, target) {
		t.Fatal("Open reused a mirror from another group")
	}
}

func TestMirrorsAreOnePerWindowAndDevice(t *testing.T) {
	a := mirrorName("work", "@3", "a1b2c3")
	b := mirrorName("work", "@4", "a1b2c3")
	c := mirrorName("work", "@3", "d4e5f6")
	if a == b || a == c || b == c {
		t.Fatalf("mirrors collide: %s %s %s", a, b, c)
	}
	if a != "work-w3-a1b2c3-porthole" || !strings.HasSuffix(a, mirrorSuffix) {
		t.Fatalf("got %q", a)
	}
}

// With no one at the desk, the phone is the window's only client. Its PTY is the
// window's size, so a status bar on the mirror would leave one row short: tmux shrank
// the window to fit, the daemon followed the window, and the grid lost a row every
// round - the phone watched the terminal creep upwards. The mirror has no status bar.
func TestPhoneAloneKeepsTheWindowSize(t *testing.T) {
	tmuxAvailable(t)
	const name = "porthole-alone-test"
	startSession(t, name, 100, 30)
	_ = exec.Command("tmux", "set-option", "-w", "-t", "="+name+":", "window-size", "largest").Run()
	cols, rows := WindowSize(context.Background(), name)
	if cols != 100 || rows != 30 {
		t.Skipf("tmux did not make a 100x30 window: %dx%d", cols, rows)
	}
	b, err := Open(context.Background(), name, "", "", 60, 20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer b.Close()
	go func() {
		buf := make([]byte, 4096)
		for {
			if _, e := b.Read(buf); e != nil {
				return
			}
		}
	}()
	// Round after round, as the daemon's follower does: size the PTY to the window.
	for i := 0; i < 4; i++ {
		time.Sleep(400 * time.Millisecond)
		wc, wr := WindowSize(context.Background(), b.Window())
		if wc != 100 || wr != 30 {
			t.Fatalf("round %d: the window is %dx%d, not 100x30", i, wc, wr)
		}
		_ = b.SetSize(wc, wr)
	}
}
