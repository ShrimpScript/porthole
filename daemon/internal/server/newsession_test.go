package server

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/store"
	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
)

// The phone's New session: a fresh Claude Code in a folder, in a tmux session of its own
// named after it - a second one for the same folder, never the first reused. Real tmux,
// on the tests' private server, with a stand-in claude that records where it ran.
func TestNewSessionStartsClaudeInItsOwnTmuxSession(t *testing.T) {
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not installed")
	}
	tmuxtest.Require(t)
	t.Setenv("INVOCATION_ID", "") // not under systemd: tmux is started directly
	t.Setenv("SHELL", "/bin/sh")

	bin := t.TempDir()
	ran := filepath.Join(bin, "ran")
	script := "#!/bin/sh\n{ pwd -P; echo \"args:$*\"; } >> '" + ran + "'\nexec sleep 30\n"
	if err := os.WriteFile(filepath.Join(bin, "claude"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", bin+string(os.PathListSeparator)+os.Getenv("PATH"))
	proj := filepath.Join(t.TempDir(), "My Project")
	if err := os.Mkdir(proj, 0o755); err != nil {
		t.Fatal(err)
	}

	_, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })
	var h proto.Hello
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeHello), &h)
	if !strings.Contains(strings.Join(h.Caps, " "), proto.CapStart) {
		t.Skip("the daemon does not offer start here")
	}

	start := func(cwd string) []byte {
		b, _ := json.Marshal(map[string]string{"type": proto.TypeSessionNew, "cwd": cwd})
		if err := c.Write(ctx, websocket.MessageText, b); err != nil {
			t.Fatal(err)
		}
		for {
			_, data, err := c.Read(ctx)
			if err != nil {
				t.Fatal(err)
			}
			var f proto.Frame
			if json.Unmarshal(data, &f) == nil && (f.Type == proto.TypeSessionStarted || f.Type == proto.TypeError) {
				return data
			}
		}
	}
	var got struct {
		Type, Tmux, Pane, Mode, Cwd, Code string
	}
	pane := regexp.MustCompile(`^%[0-9]+$`)
	realProj, _ := filepath.EvalSymlinks(proj)

	_ = json.Unmarshal(start(proj), &got)
	if got.Type != proto.TypeSessionStarted || got.Tmux != "my-project" || !pane.MatchString(got.Pane) || got.Mode != "new" || got.Cwd != proj {
		t.Fatalf("first start: %+v", got)
	}
	waitFor(t, func() bool {
		b, _ := os.ReadFile(ran)
		return strings.Contains(string(b), realProj+"\nargs:\n")
	}, "claude never ran in the folder")

	// Another task in the same folder gets its own session.
	got = struct{ Type, Tmux, Pane, Mode, Cwd, Code string }{}
	_ = json.Unmarshal(start(proj), &got)
	if got.Tmux != "my-project-2" {
		t.Fatalf("second start reused or misnamed the session: %+v", got)
	}
	waitFor(t, func() bool {
		b, _ := os.ReadFile(ran)
		return strings.Count(string(b), realProj) == 2
	}, "the second claude never ran")

	for _, bad := range []string{"relative/path", filepath.Join(proj, "missing"), filepath.Join(bin, "claude")} {
		got = struct{ Type, Tmux, Pane, Mode, Cwd, Code string }{}
		_ = json.Unmarshal(start(bad), &got)
		if got.Type != proto.TypeError || got.Code != "no_dir" {
			t.Errorf("start(%q) = %+v, want a no_dir error", bad, got)
		}
	}
}

func waitFor(t *testing.T, ok func() bool, msg string) {
	t.Helper()
	for i := 0; i < 100; i++ {
		if ok() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal(msg)
}

func TestProjectDir(t *testing.T) {
	home, _ := os.UserHomeDir()
	if got, err := projectDir("~"); err != nil || got != home {
		t.Errorf("projectDir(~) = %q, %v", got, err)
	}
	if got, err := projectDir("  " + home + "/  "); err != nil || got != home {
		t.Errorf("projectDir(padded home) = %q, %v", got, err)
	}
	if _, err := projectDir("~other/x"); err == nil {
		t.Error("another user's ~ was accepted")
	}
}
