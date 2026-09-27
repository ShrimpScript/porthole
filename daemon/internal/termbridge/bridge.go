// Package termbridge attaches a phone to a tmux session over a pseudo-terminal.
package termbridge

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"

	"github.com/creack/pty"
)

// Bridge is one phone's view of one tmux session.
type Bridge struct {
	mu     sync.Mutex
	ptmx   *os.File
	cmd    *exec.Cmd
	mirror string // the session-group member we created, to clean up on close
	target string
	window string // the window the size override sits on: "@3", or "session:" for its current window
	closed bool

	// Cols/Rows are the grid the phone should render: the tmux WINDOW's size, not the
	// phone's viewport. With window-size=largest the window follows the desktop.
	Cols, Rows int
}

// mirrorName is the group member created for this device, so a phone gets its own client
// without inheriting the desktop's. Note the mirror alone is NOT what stops the desktop
// being resized - measured, attaching a 60-column phone still dragged a 200-column
// desktop window down to 60, because tmux defaults to window-size=latest. The fix is
// setting window-size=largest in Open; the mirror keeps the client bookkeeping separate.
const (
	mirrorSuffix = "-porthole"

	// The previous window-size is parked on the window itself, as a tmux user option,
	// so recovery survives the daemon's death - see Reap.
	prevOption  = "@porthole-prev-window-size"
	unsetMarker = "porthole-was-unset"
)

// mirrorName is one mirror per target window per device: two sessions in one tmux
// session, or two phones on one window, never share a client - a shared mirror has one
// current window, and whichever opened last would move the other's view.
func mirrorName(target, window, owner string) string {
	parts := []string{target}
	if w := strings.TrimPrefix(window, "@"); w != "" {
		parts = append(parts, "w"+w)
	}
	if owner != "" {
		parts = append(parts, owner)
	}
	return strings.Join(parts, "-") + mirrorSuffix
}

// WindowSize reports the target window's current grid, which is the size the phone must
// render. With window-size=largest the window follows the DESKTOP, so asking the PTY for
// the phone's dimensions would make tmux draw wider than the PTY and clip.
func WindowSize(ctx context.Context, target string) (cols, rows int) {
	// target is a window ("@3" or "session:") or a bare session name, whose current
	// window is meant.
	if !strings.HasPrefix(target, "@") && !strings.HasSuffix(target, ":") {
		target = "=" + target + ":"
	}
	out, err := exec.CommandContext(ctx, "tmux", "display-message", "-p", "-t", target,
		"#{window_width} #{window_height}").Output()
	if err != nil {
		return 0, 0
	}
	first := strings.SplitN(strings.TrimSpace(string(out)), "\n", 2)[0]
	_, _ = fmt.Sscanf(first, "%d %d", &cols, &rows)
	return cols, rows
}

// Open attaches to target and returns a live bridge, along with the grid size the phone
// should render. cols/rows are the phone's own viewport, used only as a floor.
func Open(ctx context.Context, target, window, owner string, cols, rows int) (*Bridge, error) {
	if target == "" {
		return nil, fmt.Errorf("no tmux session named")
	}
	// The window the session's Claude runs in, when the registry named one; otherwise
	// the session's current window, which is all a name can say.
	winTarget := target + ":"
	if window != "" {
		winTarget = window
	}
	if cols <= 0 || rows <= 0 {
		cols, rows = 80, 24
	}

	// Without this, tmux sizes the shared window to the LATEST client, so a 60-column
	// phone instantly shrinks the desktop's 200-column pane. Measured: 200x49 -> 60x19.
	// "largest" keeps the window at the biggest attached session, so the desktop is
	// untouched and the phone renders the desktop's real grid - which is also what
	// "see it exactly as it looks at the computer" actually means.
	marker := windowOption(ctx, winTarget, "window-size")
	if marker == "" {
		marker = unsetMarker
	}
	// Parked on the window before the override, so a daemon that dies without running
	// Close still leaves enough behind for the next start to undo this.
	_ = exec.CommandContext(ctx, "tmux", "set-option", "-w", "-t", winTarget,
		prevOption, marker).Run()
	_ = exec.CommandContext(ctx, "tmux", "set-option", "-w", "-t", winTarget,
		"window-size", "largest").Run()

	mirror := mirrorName(target, window, owner)
	// A mirror left from before is only reused if it is still in the target's group. One
	// whose target was closed and opened again belongs to the old group: it shows the old
	// windows, and the phone watched a frozen screen while the feed moved on.
	if sessionExists(ctx, mirror) && !sameGroup(ctx, mirror, target) {
		_ = exec.CommandContext(ctx, "tmux", "kill-session", "-t", "="+mirror).Run()
	}
	// -d so creating it does not attach here; -t puts it in target's session group. The
	// target is matched exactly: tmux would otherwise take a prefix, and with "work" gone
	// "work-2" would be mirrored instead.
	if !sessionExists(ctx, mirror) {
		mk := exec.CommandContext(ctx, "tmux", "new-session", "-d", "-s", mirror, "-t", "="+target)
		if out, err := mk.CombinedOutput(); err != nil {
			return nil, fmt.Errorf("could not mirror %s: %s", target, strings.TrimSpace(string(out)))
		}
	}
	// A grouped session has its own current window: point the mirror at Claude's
	// window without moving the desktop's view. (The active pane is shared, so that is
	// left alone; the whole window is shown.)
	if window != "" {
		_ = exec.CommandContext(ctx, "tmux", "select-window", "-t", "="+mirror+":"+window).Run()
	}

	// Size the PTY to the WINDOW, not the phone. tmux paints at the window's width, so a
	// narrower PTY would simply clip the right-hand columns.
	if wc, wr := WindowSize(ctx, winTarget); wc > 0 && wr > 0 {
		cols, rows = wc, wr
	}

	// -d on attach detaches other clients of THIS mirror session only, so a stale phone
	// connection never fights a fresh one. The desktop's own client is untouched.
	// -u: the client sends UTF-8 whatever the daemon's locale. tmux decides that per
	// client from LC_ALL/LC_CTYPE/LANG, and a launchd agent has none, so on a Mac every
	// character of Claude Code's frame arrived on the phone as an underscore.
	cmd := exec.CommandContext(ctx, "tmux", "-u", "attach-session", "-d", "-t", mirror)
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")

	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Cols: uint16(cols), Rows: uint16(rows)})
	if err != nil {
		return nil, err
	}
	return &Bridge{
		ptmx: ptmx, cmd: cmd, mirror: mirror,
		target: target,
		window: winTarget,
		Cols:   cols, Rows: rows,
	}, nil
}

// windowOption reads one window option, returning "" when unset or unreadable.
func windowOption(ctx context.Context, window, name string) string {
	out, err := exec.CommandContext(ctx, "tmux", "show-options", "-w", "-t", window, name).Output()
	if err != nil {
		return ""
	}
	f := strings.Fields(strings.TrimSpace(string(out)))
	if len(f) < 2 {
		return ""
	}
	return f[1]
}

// sameGroup reports whether two sessions share a session group.
func sameGroup(ctx context.Context, a, b string) bool {
	group := func(name string) string {
		out, err := exec.CommandContext(ctx, "tmux", "display-message", "-p", "-t", "="+name+":", "#{session_group}").Output()
		if err != nil {
			return ""
		}
		return strings.TrimSpace(string(out))
	}
	ga := group(a)
	return ga != "" && ga == group(b)
}

// Window is the tmux window this bridge shows, for following its size.
func (b *Bridge) Window() string { return b.window }

// SetSize records a new grid after the window was resized, and resizes the PTY to it.
func (b *Bridge) SetSize(cols, rows int) error {
	if err := b.Resize(cols, rows); err != nil {
		return err
	}
	b.mu.Lock()
	b.Cols, b.Rows = cols, rows
	b.mu.Unlock()
	return nil
}

func sessionExists(ctx context.Context, name string) bool {
	return exec.CommandContext(ctx, "tmux", "has-session", "-t", "="+name).Run() == nil
}

// Read blocks until the terminal produces output.
func (b *Bridge) Read(p []byte) (int, error) { return b.ptmx.Read(p) }

// Write sends keystrokes to the terminal.
func (b *Bridge) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return 0, os.ErrClosed
	}
	return b.ptmx.Write(p)
}

// Resize follows the phone: rotating to landscape changes the column count, and a TUI
// that is not told will keep drawing at the old width.
func (b *Bridge) Resize(cols, rows int) error {
	if cols <= 0 || rows <= 0 {
		return nil
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return os.ErrClosed
	}
	return pty.Setsize(b.ptmx, &pty.Winsize{Cols: uint16(cols), Rows: uint16(rows)})
}

// Close detaches and removes the mirror session, leaving the user's own session alone.
func (b *Bridge) Close() error {
	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		return nil
	}
	b.closed = true
	b.mu.Unlock()

	_ = b.ptmx.Close()
	if b.cmd != nil && b.cmd.Process != nil {
		_ = b.cmd.Process.Kill()
		_ = b.cmd.Wait()
	}
	// Kill only the mirror. The target session and the desktop's client survive.
	if b.mirror != "" {
		_ = exec.Command("tmux", "kill-session", "-t", "="+b.mirror).Run()
	}
	// Put the user's window-size back the way it was.
	if b.target != "" {
		restoreWindowSize(context.Background(), b.window)
	}
	return nil
}

// restoreWindowSize undoes the window-size override using the value parked on the
// window, then clears the marker. Doing it from the marker rather than from memory
// means Close and Reap take the identical path.
func restoreWindowSize(ctx context.Context, window string) {
	prev := windowOption(ctx, window, prevOption)
	if prev == "" {
		return
	}
	if prev == unsetMarker {
		_ = exec.CommandContext(ctx, "tmux", "set-option", "-w", "-u", "-t", window,
			"window-size").Run()
	} else {
		_ = exec.CommandContext(ctx, "tmux", "set-option", "-w", "-t", window,
			"window-size", prev).Run()
	}
	_ = exec.CommandContext(ctx, "tmux", "set-option", "-w", "-u", "-t", window,
		prevOption).Run()
}

// Reap cleans up after a daemon that died with a terminal open.
//
// Close kills the mirror and restores window-size, but a crashed or killed daemon never
// runs it. The leftover mirror session is only clutter; the real harm is that the user's
// own tmux window stays pinned to window-size=largest - a setting they never chose, on a
// session they use for everything else, with nothing on screen to explain it. Run at
// startup, before any bridge opens.
//
// Only unattached mirrors are touched. A mirror in live use has the bridge's PTY
// attached to it, so this cannot pull the terminal out from under a running daemon.
func Reap(ctx context.Context) []string {
	out, err := exec.CommandContext(ctx, "tmux", "list-sessions",
		"-F", "#{session_name} #{session_attached}").Output()
	if err != nil {
		return nil // no server running, nothing to clean up
	}

	attached := map[string]bool{}
	var names []string
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		f := strings.Fields(line)
		if len(f) < 2 {
			continue
		}
		names = append(names, f[0])
		attached[f[0]] = f[1] != "0"
	}

	var reaped []string
	for _, name := range names {
		if !strings.HasSuffix(name, mirrorSuffix) || attached[name] {
			continue
		}
		_ = exec.CommandContext(ctx, "tmux", "kill-session", "-t", "="+name).Run()
		reaped = append(reaped, name)
	}
	// Separate pass over the targets: a window can still carry the marker after its
	// mirror is gone, and that is the half that actually affects the user.
	for _, name := range names {
		if strings.HasSuffix(name, mirrorSuffix) {
			continue
		}
		restoreWindowSize(ctx, name+":")
	}
	return reaped
}
