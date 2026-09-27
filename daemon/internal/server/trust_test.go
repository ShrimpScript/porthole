package server

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/store"
	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
)

// As Claude Code 2.1.283 drew it, in a folder it had not been used in.
const trustScreen = `[user@host trustprobe]$ claude
────────────────────────────────────────────────────────────────────────────────
 Accessing workspace:

 /srv/projects/new-app

 Quick safety check: Is this a project you created or one you trust? (Like your own code, a
 well-known open source project, or work from your team). If not, take a moment to review what's in
 this folder first.

 Claude Code'll be able to read, edit, and execute files here.

 Security guide

 ❯ No, exit
   Yes, I trust this folder

 Enter to confirm · Esc to cancel
`

func TestParseTrust(t *testing.T) {
	tp := parseTrust(trustScreen)
	if tp == nil || tp.Folder != "/srv/projects/new-app" || len(tp.Lines) != 2 || tp.Yes != 1 || tp.At != 0 {
		t.Fatalf("parseTrust = %+v", tp)
	}
	// An older wording, numbered, with Yes first and selected.
	old := "Do you trust the files in this folder?\n\n/srv/x\n\n ❯ 1. Yes, proceed\n   2. No, exit\n\n Enter to confirm · Esc to exit\n"
	if tp := parseTrust(old); tp == nil || tp.Yes != 0 || tp.At != 0 {
		t.Fatalf("older prompt: %+v", tp)
	}
	// Claude Code at work, and a question picker, are not the trust prompt.
	for _, s := range []string{
		"> fix the tests\n● Running tests\n",
		"☐ Colour\nWhich colour?\n❯ 1. Red\n  2. Blue\nEnter to select · ↑/↓ to navigate · Esc to cancel\n",
	} {
		if parseTrust(s) != nil {
			t.Errorf("parseTrust(%q) found a prompt", s)
		}
	}
}

// A stand-in claude that draws the trust prompt and answers the keys the way Claude Code
// does: Up/Down move, Enter confirms, Esc quits. It writes what was chosen to $TRUST_OUT.
const fakeTrustClaude = `#!/usr/bin/env python3
import os, tty, termios
opts = ['No, exit', 'Yes, I trust this folder']
sel = 0
def draw():
    s = '\x1b[2J\x1b[H Accessing workspace:\r\n\r\n ' + os.getcwd() + '\r\n\r\n Quick safety check: Is this a project you created or one you trust?\r\n\r\n Security guide\r\n\r\n'
    for i, o in enumerate(opts):
        s += (' ❯ ' if i == sel else '   ') + o + '\r\n'
    s += '\r\n Enter to confirm · Esc to cancel\r\n'
    os.write(1, s.encode('utf-8'))
old = termios.tcgetattr(0)
tty.setraw(0)
try:
    draw()
    while True:
        b = os.read(0, 16)
        if b in (b'\x1b[B', b'\x1bOB'):
            sel = min(sel + 1, 1); draw()
        elif b in (b'\x1b[A', b'\x1bOA'):
            sel = max(sel - 1, 0); draw()
        elif b in (b'\r', b'\n'):
            open(os.environ['TRUST_OUT'], 'a').write(opts[sel] + '\n'); break
        elif b == b'\x1b':
            open(os.environ['TRUST_OUT'], 'a').write('escape\n'); break
finally:
    termios.tcsetattr(0, termios.TCSADRAIN, old)
if sel == 1:
    os.execvp('sleep', ['sleep', '30'])
`

func TestTrustPromptFromThePhone(t *testing.T) {
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not installed")
	}
	if _, err := exec.LookPath("python3"); err != nil {
		t.Skip("python3 not installed")
	}
	tmuxtest.Fresh(t)
	t.Setenv("INVOCATION_ID", "")
	t.Setenv("SHELL", "/bin/sh")
	bin := t.TempDir()
	out := filepath.Join(bin, "chose")
	if err := os.WriteFile(filepath.Join(bin, "claude"), []byte(fakeTrustClaude), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", bin+string(os.PathListSeparator)+os.Getenv("PATH"))
	t.Setenv("TRUST_OUT", out)

	_, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })
	readType(t, ctx, c, proto.TypeHello)
	send := func(v any) {
		b, _ := json.Marshal(v)
		if err := c.Write(ctx, websocket.MessageText, b); err != nil {
			t.Fatal(err)
		}
	}
	chose := func() string { b, _ := os.ReadFile(out); return string(b) }

	// Asked, and trusted from the phone: the selection is walked to Yes and confirmed.
	proj := filepath.Join(t.TempDir(), "new-app")
	_ = os.Mkdir(proj, 0o755)
	send(map[string]string{"type": proto.TypeSessionNew, "cwd": proj})
	var ask trustAsk
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSessionTrust), &ask)
	if ask.Pane == "" || ask.Tmux != "new-app" || !strings.HasSuffix(ask.Folder, "new-app") {
		t.Fatalf("trust ask = %+v", ask)
	}
	send(map[string]any{"type": proto.TypeSessionTrust, "pane": ask.Pane, "trust": true})
	waitFor(t, func() bool { return chose() == "Yes, I trust this folder\n" }, "trust was not chosen: "+chose())

	// Refused: Esc quits Claude Code and the tmux session made for it goes.
	proj2 := filepath.Join(t.TempDir(), "other-app")
	_ = os.Mkdir(proj2, 0o755)
	send(map[string]string{"type": proto.TypeSessionNew, "cwd": proj2})
	ask = trustAsk{}
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSessionTrust), &ask)
	send(map[string]any{"type": proto.TypeSessionTrust, "pane": ask.Pane, "trust": false})
	waitFor(t, func() bool { return strings.HasSuffix(chose(), "escape\n") }, "Esc was not pressed: "+chose())
	waitFor(t, func() bool { return exec.Command("tmux", "has-session", "-t", "=other-app").Run() != nil },
		"the refused session was left behind")

	// A pane the daemon did not start for this phone is not answered.
	send(map[string]any{"type": proto.TypeSessionTrust, "pane": "%0", "trust": true})
	var e proto.Error
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeError), &e)
	if e.Code != "not_asking" {
		t.Fatalf("answer for a stranger's pane: %+v", e)
	}
}
