package server

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

// runCmd runs a command and returns its combined output; tests replace it.
var runCmd = func(ctx context.Context, name string, args ...string) ([]byte, error) {
	return exec.CommandContext(ctx, name, args...).CombinedOutput()
}

var paneID = regexp.MustCompile(`^%[0-9]+$`)

// newTmuxSession creates a detached tmux session in cwd and returns its first pane id.
//
// A tmux server must not be born inside the daemon's sandbox. The service runs with
// ProtectSystem=strict, so /tmp is read-only there: tmux cannot create its socket, and it
// still exits 0 with the error as its output. And a server that did start would pass the
// sandbox on to everything run in it - a read-only filesystem and no sudo for Claude
// Code. So when no server is running (after a reboot, before anyone opened tmux at the
// desk) and the daemon runs under systemd, the server is started as a transient user unit
// of its own, outside the sandbox. With a server already running, tmux only asks it for a
// new session, and the shell belongs to that server.
func newTmuxSession(ctx context.Context, name, cwd string) (string, error) {
	_, listErr := runCmd(ctx, "tmux", "list-sessions")
	underSystemd := os.Getenv("INVOCATION_ID") != ""
	if listErr == nil || !underSystemd {
		out, err := runCmd(ctx, "tmux", "new-session", "-d", "-s", name, "-c", cwd, "-P", "-F", "#{pane_id}")
		return checkPane(out, err)
	}
	unit := fmt.Sprintf("porthole-tmux-%s-%d", unitSafe(name), time.Now().Unix())
	out, err := runCmd(ctx, "systemd-run", "--user", "--quiet", "--collect", "--unit="+unit,
		"-p", "Type=forking", "tmux", "new-session", "-d", "-s", name, "-c", cwd)
	if err != nil {
		return "", fmt.Errorf("could not start tmux outside the daemon: %s", strings.TrimSpace(string(out)))
	}
	out, err = runCmd(ctx, "tmux", "list-panes", "-t", name+":", "-F", "#{pane_id}")
	first, _, _ := strings.Cut(strings.TrimSpace(string(out)), "\n")
	return checkPane([]byte(first), err)
}

// checkPane accepts only a pane id. tmux reports some failures with exit status 0 and
// the error as its output, which must never be typed into as if it named a pane.
func checkPane(out []byte, err error) (string, error) {
	s := strings.TrimSpace(string(out))
	if err != nil || !paneID.MatchString(s) {
		if s == "" && err != nil {
			s = err.Error()
		}
		return "", fmt.Errorf("%s", s)
	}
	return s, nil
}

// unitSafe keeps a tmux session name usable inside a systemd unit name.
func unitSafe(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_':
			b.WriteRune(r)
		default:
			b.WriteRune('-')
		}
	}
	return b.String()
}

// scrollPane walks a pane's history with tmux's own copy mode, so the phone scrolls the
// same scrollback a person would at the desk (and the desk sees the pane scrolled, as it
// would with a mouse wheel). lines > 0 goes back, < 0 forward; copy mode is entered with
// -e, so scrolling forward past the end leaves it. 0 leaves history at once, which the
// app sends before typing - keys pressed in copy mode would drive copy mode, not Claude.
func scrollPane(ctx context.Context, pane string, lines int) {
	for _, args := range scrollArgs(pane, lines) {
		_, _ = runCmd(ctx, "tmux", args...)
	}
}

func scrollArgs(pane string, lines int) [][]string {
	const max = 500
	switch {
	case lines > 0:
		return [][]string{
			{"copy-mode", "-e", "-t", pane},
			{"send-keys", "-t", pane, "-X", "-N", fmt.Sprint(min(lines, max)), "scroll-up"},
		}
	case lines < 0:
		return [][]string{{"send-keys", "-t", pane, "-X", "-N", fmt.Sprint(min(-lines, max)), "scroll-down"}}
	default:
		return [][]string{{"send-keys", "-t", pane, "-X", "cancel"}}
	}
}
