package server

import (
	"context"
	"errors"
	"strings"
	"testing"
)

type fakeRun struct {
	calls   []string
	replies map[string]struct {
		out string
		err error
	}
}

func (f *fakeRun) run(_ context.Context, name string, args ...string) ([]byte, error) {
	line := name + " " + strings.Join(args, " ")
	f.calls = append(f.calls, line)
	for prefix, r := range f.replies {
		if strings.HasPrefix(line, prefix) {
			return []byte(r.out), r.err
		}
	}
	return nil, nil
}

func withFake(t *testing.T, f *fakeRun, invocation string) {
	t.Helper()
	old := runCmd
	runCmd = f.run
	t.Cleanup(func() { runCmd = old })
	t.Setenv("INVOCATION_ID", invocation)
}

type reply = struct {
	out string
	err error
}

func TestTmuxWithAServerRunningIsAskedDirectly(t *testing.T) {
	f := &fakeRun{replies: map[string]reply{
		"tmux list-sessions": {"0: 1 windows", nil},
		"tmux new-session":   {"%12\n", nil},
	}}
	withFake(t, f, "abc")
	pane, err := newTmuxSession(context.Background(), "shop-api", "/srv/app")
	if err != nil || pane != "%12" {
		t.Fatalf("pane %q err %v", pane, err)
	}
	if strings.Contains(strings.Join(f.calls, "\n"), "systemd-run") {
		t.Fatalf("a running server must not be bypassed: %v", f.calls)
	}
}

func TestTmuxWithNoServerStartsOutsideTheSandbox(t *testing.T) {
	f := &fakeRun{replies: map[string]reply{
		"tmux list-sessions": {"no server running on /tmp/tmux-1000/default", errors.New("exit status 1")},
		"systemd-run":        {"", nil},
		"tmux list-panes":    {"%0\n%1\n", nil},
	}}
	withFake(t, f, "abc")
	pane, err := newTmuxSession(context.Background(), "shop api", "/srv/app")
	if err != nil || pane != "%0" {
		t.Fatalf("pane %q err %v (calls %v)", pane, err, f.calls)
	}
	var start string
	for _, c := range f.calls {
		if strings.HasPrefix(c, "systemd-run") {
			start = c
		}
	}
	if !strings.Contains(start, "--user") || !strings.Contains(start, "-p Type=forking tmux new-session -d -s shop api -c /srv/app") ||
		!strings.Contains(start, "--unit=porthole-tmux-shop-api-") {
		t.Fatalf("started with %q", start)
	}
}

func TestTmuxOutsideSystemdKeepsThePlainPath(t *testing.T) {
	f := &fakeRun{replies: map[string]reply{
		"tmux list-sessions": {"no server running", errors.New("exit status 1")},
		"tmux new-session":   {"%3", nil},
	}}
	withFake(t, f, "")
	if pane, err := newTmuxSession(context.Background(), "w", "/srv/app"); err != nil || pane != "%3" {
		t.Fatalf("pane %q err %v", pane, err)
	}
}

// tmux exits 0 with its error as output when it cannot make its socket; that text was
// once typed into as a pane id.
func TestTmuxErrorTextIsNeverAPane(t *testing.T) {
	f := &fakeRun{replies: map[string]reply{
		"tmux list-sessions": {"", nil},
		"tmux new-session":   {"error creating /tmp/tmux-1000/default (Read-only file system)", nil},
	}}
	withFake(t, f, "")
	pane, err := newTmuxSession(context.Background(), "w", "/srv/app")
	if err == nil || pane != "" || !strings.Contains(err.Error(), "Read-only file system") {
		t.Fatalf("pane %q err %v", pane, err)
	}
}

func TestScrollPaneUsesCopyMode(t *testing.T) {
	up := scrollArgs("%4", 3)
	if strings.Join(up[0], " ") != "copy-mode -e -t %4" || strings.Join(up[1], " ") != "send-keys -t %4 -X -N 3 scroll-up" {
		t.Fatalf("up: %v", up)
	}
	if got := strings.Join(scrollArgs("%4", -2)[0], " "); got != "send-keys -t %4 -X -N 2 scroll-down" {
		t.Fatalf("down: %s", got)
	}
	if got := strings.Join(scrollArgs("%4", 0)[0], " "); got != "send-keys -t %4 -X cancel" {
		t.Fatalf("live: %s", got)
	}
	if got := scrollArgs("%4", 100000)[1][5]; got != "500" {
		t.Fatalf("a wild value is bounded, got %s", got)
	}
}
