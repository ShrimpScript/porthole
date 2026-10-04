package server

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/store"
	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// fakeClaude stands in for Claude Code's auth commands, printing what 2.1.289 prints. Its
// sign-in records the environment it was given, so a test can see that nothing it could
// open a browser with leads anywhere but a stub.
const fakeClaude = `#!/bin/sh
state="$FAKE_DIR/account"
case "$1 $2" in
"auth status")
  if [ -f "$state" ]; then
    printf '{"loggedIn":true,"authMethod":"claude.ai","email":"%s","orgName":"Org","subscriptionType":"pro"}\n' "$(cat "$state")"
  else
    printf '{"loggedIn":false,"authMethod":"none","apiProvider":"firstParty"}\n'; exit 1
  fi ;;
"auth logout") rm -f "$state"; echo "Successfully logged out from your Anthropic account." ;;
"auth login")
  { echo "DISPLAY=${DISPLAY-unset}"; echo "WAYLAND=${WAYLAND_DISPLAY-unset}"; echo "BROWSER=$BROWSER"
    echo "xdg-open=$(command -v xdg-open)"; echo "open=$(command -v open)"; } > "$FAKE_DIR/env"
  echo "Opening browser to sign in…"
  printf '\033[2mIf the browser didn'"'"'t open, visit: \033[0mhttps://claude.com/cai/oauth/authorize?code=true&client_id=c&response_type=code&redirect_uri=https%%3A%%2F%%2Fplatform.claude.com%%2Foauth%%2Fcode%%2Fcallback&state=s1\n'
  printf "Paste code here if prompted > "
  IFS= read -r code
  if [ "$code" = "good#s1" ]; then echo "other@example.com" > "$state"; echo "Login successful."; exit 0; fi
  echo "OAuth error: Invalid code"; exit 1 ;;
esac
`

func accountConn(t *testing.T) (*websocket.Conn, context.Context, string) {
	t.Helper()
	dir := t.TempDir()
	bin := filepath.Join(dir, "claude")
	if err := os.WriteFile(bin, []byte(fakeClaude), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("FAKE_DIR", dir)
	// The stubs go in porthole's own folder: this test's, never the real one.
	t.Setenv("PORTHOLE_STATE_DIR", filepath.Join(dir, "state"))
	old, oldSD := findClaude, underSystemd
	findClaude = func() string { return bin }
	underSystemd = func() bool { return false }
	t.Cleanup(func() { findClaude, underSystemd = old, oldSD })

	_, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	c.SetReadLimit(1 << 22)
	t.Cleanup(func() { _ = c.CloseNow() })
	readType(t, ctx, c, proto.TypeHello)
	return c, ctx, dir
}

func sendFrame(t *testing.T, ctx context.Context, c *websocket.Conn, f map[string]any) {
	t.Helper()
	b, _ := json.Marshal(f)
	if err := c.Write(ctx, websocket.MessageText, b); err != nil {
		t.Fatal(err)
	}
}

// Signing in to another account from the phone: the phone gets Claude's own sign-in
// page, a wrong code is turned away, the right one signs Claude Code in, and signing out
// signs it out.
func TestSigningInToAnotherAccount(t *testing.T) {
	c, ctx, dir := accountConn(t)
	state := func() accountState {
		var st accountState
		_ = json.Unmarshal(readType(t, ctx, c, proto.TypeAccountState), &st)
		return st
	}
	link := func() string {
		var l accountLink
		_ = json.Unmarshal(readType(t, ctx, c, proto.TypeAccountLink), &l)
		return l.URL
	}
	done := func() accountDone {
		var d accountDone
		_ = json.Unmarshal(readType(t, ctx, c, proto.TypeAccountDone), &d)
		return d
	}

	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountGet})
	if st := state(); st.LoggedIn || st.Error != "" {
		t.Fatalf("before: %+v", st)
	}

	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountSignIn})
	u := link()
	if !strings.HasPrefix(u, "https://claude.com/cai/oauth/authorize?") || !strings.Contains(u, "platform.claude.com") || !strings.HasSuffix(u, "state=s1") {
		t.Fatalf("link = %q", u)
	}
	env, _ := os.ReadFile(filepath.Join(dir, "env"))
	for _, l := range strings.Split(strings.TrimSpace(string(env)), "\n") {
		k, v, _ := strings.Cut(l, "=")
		switch k {
		case "DISPLAY", "WAYLAND":
			if v != "unset" {
				t.Errorf("%s reached the sign-in: %q", k, v)
			}
		default:
			if !strings.Contains(v, string(filepath.Separator)+"signin"+string(filepath.Separator)+"run-") {
				t.Errorf("%s leads to %q, not a stub: a browser could open on the computer", k, v)
			}
		}
	}

	// A code from some other sign-in is turned away at once, and this one keeps waiting.
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountCode, "code": "abcdef#other"})
	if d := done(); d.OK || !d.Retry || !strings.Contains(d.Error, "another sign-in") || d.State != "s1" {
		t.Fatalf("another sign-in's code: %+v", d)
	}
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountCode, "code": "nope#s1"})
	if d := done(); d.OK || !strings.Contains(d.Error, "Invalid code") {
		t.Fatalf("wrong code: %+v", d)
	}

	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountSignIn})
	link()
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountCode, "code": "  good#s1\n"})
	if st := state(); !st.LoggedIn || st.Email != "other@example.com" || st.Plan != "pro" || st.Method != "claude.ai" {
		t.Fatalf("after: %+v", st)
	}
	if d := done(); !d.OK {
		t.Fatalf("right code: %+v", d)
	}
	stub := strings.TrimPrefix(strings.Split(string(env), "\n")[2], "BROWSER=")
	if _, err := os.Stat(filepath.Dir(stub)); !os.IsNotExist(err) {
		t.Errorf("the stubs were left behind in %s", filepath.Dir(stub))
	}

	// A phone that lost its connection while the sign-in finished hears how it ended.
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountGet})
	state()
	if d := done(); !d.OK || d.State != "s1" {
		t.Fatalf("asked again: %+v", d)
	}

	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountSignOut})
	if st := state(); st.LoggedIn {
		t.Fatalf("signed out: %+v", st)
	}
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountCode, "code": "good#s1"})
	if d := done(); d.OK || !strings.Contains(d.Error, "no sign-in is waiting") {
		t.Fatalf("a code with nothing waiting: %+v", d)
	}
}

// Abandoning a sign-in ends the CLI; a new one replaces one under way.
func TestASignInCanBeAbandoned(t *testing.T) {
	c, ctx, _ := accountConn(t)
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountSignIn})
	readType(t, ctx, c, proto.TypeAccountLink)
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountCancel})
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountCode, "code": "good#s1"})
	var d accountDone
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeAccountDone), &d)
	if d.OK || !strings.Contains(d.Error, "no sign-in is waiting") {
		t.Fatalf("after cancel: %+v", d)
	}
}

// ---- restarting -------------------------------------------------------------------------

func tmuxOut(t *testing.T, args ...string) string {
	t.Helper()
	out, err := exec.Command("tmux", args...).CombinedOutput()
	if err != nil {
		t.Fatalf("tmux %v: %v %s", args, err, out)
	}
	return strings.TrimSpace(string(out))
}

func childOf(pid string) int {
	out, _ := exec.Command("pgrep", "-P", pid).Output()
	n, _ := strconv.Atoi(strings.TrimSpace(strings.Split(string(out), "\n")[0]))
	return n
}

// A session busy with a turn is restarted when it finishes, not in the middle of it, and
// comes back in the same pane on the same conversation.
func TestARestartWaitsForTheTurnThenResumesInTheSamePane(t *testing.T) {
	tmuxtest.Fresh(t)
	dir := t.TempDir()
	bin := filepath.Join(dir, "bin")
	_ = os.Mkdir(bin, 0o755)
	// What the shell runs when the restart types `claude --resume <id>`.
	if err := os.WriteFile(filepath.Join(bin, "claude"), []byte("#!/bin/sh\necho \"$@\" > \""+filepath.Join(dir, "resumed")+"\"\nexec sleep 300\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	for try := 0; ; try++ {
		// A clean environment, run directly (tmux does not wrap separate arguments in a
		// shell): the person's shell startup would put the real claude back on the PATH.
		if out, err := exec.Command("tmux", "new-session", "-d", "-s", "rs", "-x", "120", "-y", "30", "-c", dir,
			"env", "-i", "PATH="+bin+":/usr/bin:/bin", "HOME="+dir, "TERM=xterm", "sh").CombinedOutput(); err == nil {
			break
		} else if try == 40 {
			t.Fatalf("tmux: %v %s", err, out)
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Cleanup(func() { _ = exec.Command("tmux", "kill-session", "-t", "rs").Run() })
	pane := tmuxOut(t, "display-message", "-p", "-t", "rs", "#{pane_id}")
	shell := tmuxOut(t, "display-message", "-p", "-t", "rs", "#{pane_pid}")
	// The CLI that is running now: a stand-in, started from the pane's shell.
	tmuxOut(t, "send-keys", "-t", pane, "-l", "sleep 300")
	tmuxOut(t, "send-keys", "-t", pane, "Enter")
	var running int
	for i := 0; i < 100 && running == 0; i++ {
		time.Sleep(50 * time.Millisecond)
		running = childOf(shell)
	}
	if running == 0 {
		t.Fatal("the stand-in CLI did not start")
	}

	var mu sync.Mutex
	status := "busy"
	oldProc, oldPoll := procFor, restartPoll
	procFor = func(id string) (session.Proc, bool) {
		mu.Lock()
		defer mu.Unlock()
		return session.Proc{PID: running, SessionID: id, Cwd: dir, Pane: pane, Status: status}, true
	}
	restartPoll = 50 * time.Millisecond
	t.Cleanup(func() { procFor, restartPoll = oldProc, oldPoll })

	escapes := 0
	oldWait, oldEsc := waitingOutLimit, sendEscape
	waitingOutLimit = func(context.Context, string) bool { return true }
	sendEscape = func(context.Context, string) { mu.Lock(); escapes++; mu.Unlock() }
	t.Cleanup(func() { waitingOutLimit, sendEscape = oldWait, oldEsc })
	c, ctx, _ := accountConn(t)
	// newTestServer gave this test a projects folder of its own: the conversation lives there.
	conv := filepath.Join(transcript.ProjectsDir(), "-proj", "11111111-2222-3333-4444-555555555555.jsonl")
	_ = os.MkdirAll(filepath.Dir(conv), 0o755)
	if err := os.WriteFile(conv, []byte("{}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeSessionsRestart, "ids": []string{"11111111-2222-3333-4444-555555555555"}})
	next := func() restartFrame {
		var f restartFrame
		_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSessionRestarted), &f)
		return f
	}
	if f := next(); f.SessionID != "11111111-2222-3333-4444-555555555555" || f.State != "waiting" {
		t.Fatalf("while busy: %+v", f)
	}
	// Asked again while it waits: one restart, not two.
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeSessionsRestart, "ids": []string{"11111111-2222-3333-4444-555555555555", "not-an-id; rm -rf ~"}})
	if f := next(); f.State != "waiting" {
		t.Fatalf("asked twice: %+v", f)
	}
	if f := next(); f.State != "failed" || f.Error != "that is not a session id" {
		t.Fatalf("a bad id: %+v", f)
	}
	time.Sleep(300 * time.Millisecond)
	if b, _ := os.ReadFile(filepath.Join(dir, "resumed")); len(b) > 0 {
		t.Fatal("restarted in the middle of a turn")
	}
	mu.Lock()
	if escapes != 1 {
		t.Errorf("the limit wait was cancelled %d times, want once", escapes)
	}
	status = "idle"
	mu.Unlock()
	if f := next(); f.State != "restarted" {
		t.Fatalf("once idle: %+v", f)
	}
	var got []byte
	for i := 0; i < 100 && len(got) == 0; i++ {
		time.Sleep(50 * time.Millisecond)
		got, _ = os.ReadFile(filepath.Join(dir, "resumed"))
	}
	if strings.TrimSpace(string(got)) != "--resume 11111111-2222-3333-4444-555555555555" {
		t.Fatalf("typed into the pane: %q\n%s", got, tmuxOut(t, "capture-pane", "-p", "-t", pane))
	}
}

// A CLI that is not run from its pane's own shell - here it is the pane's command - is
// left running: stopping it would take the pane with it and bring nothing back.
func TestARestartLeavesAClaudeNotRunFromAShell(t *testing.T) {
	tmuxtest.Fresh(t)
	dir := t.TempDir()
	for try := 0; ; try++ {
		if out, err := exec.Command("tmux", "new-session", "-d", "-s", "keep", "-c", dir, "env", "-i", "PATH=/usr/bin:/bin", "sh").CombinedOutput(); err == nil {
			break
		} else if try == 40 {
			t.Fatalf("tmux: %v %s", err, out)
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Cleanup(func() { _ = exec.Command("tmux", "kill-session", "-t", "keep").Run() })
	pane := tmuxOut(t, "new-window", "-d", "-t", "keep:", "-c", dir, "-P", "-F", "#{pane_id}", "sleep", "300")
	// Read just after the window opens, pane_pid can still name the tmux server itself.
	var pid int
	for i := 0; i < 100; i++ {
		pid, _ = strconv.Atoi(tmuxOut(t, "display-message", "-p", "-t", pane, "#{pane_pid}"))
		if out, _ := exec.Command("ps", "-o", "comm=", "-p", strconv.Itoa(pid)).Output(); strings.TrimSpace(string(out)) == "sleep" {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	oldProc := procFor
	procFor = func(id string) (session.Proc, bool) {
		return session.Proc{PID: pid, SessionID: id, Cwd: dir, Pane: pane, Status: "idle"}, true
	}
	t.Cleanup(func() { procFor = oldProc })

	c, ctx, _ := accountConn(t)
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeSessionsRestart, "ids": []string{"66666666-7777-8888-9999-000000000000"}})
	var f restartFrame
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSessionRestarted), &f)
	if f.State != "failed" || !strings.Contains(f.Error, "left running") {
		t.Fatalf("got %+v", f)
	}
	if syscall.Kill(pid, 0) != nil {
		t.Fatal("it was stopped anyway")
	}
}

// A restart still waiting for its session can be called off.
func TestAWaitingRestartCanBeCalledOff(t *testing.T) {
	oldProc, oldPoll := procFor, restartPoll
	procFor = func(id string) (session.Proc, bool) {
		return session.Proc{PID: os.Getpid(), SessionID: id, Pane: "%1", Status: "busy"}, true
	}
	restartPoll = 50 * time.Millisecond
	oldWait := waitingOutLimit
	waitingOutLimit = func(context.Context, string) bool { return false }
	t.Cleanup(func() { procFor, restartPoll, waitingOutLimit = oldProc, oldPoll, oldWait })
	c, ctx, _ := accountConn(t)
	id := "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeSessionsRestart, "ids": []string{id}})
	var f restartFrame
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSessionRestarted), &f)
	if f.State != "waiting" {
		t.Fatalf("first: %+v", f)
	}
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeRestartCancel, "ids": []string{id}})
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSessionRestarted), &f)
	if f.State != "cancelled" {
		t.Fatalf("after calling it off: %+v", f)
	}
	// And it can be asked for again.
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeSessionsRestart, "ids": []string{id}})
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSessionRestarted), &f)
	if f.State != "waiting" {
		t.Fatalf("asked again: %+v", f)
	}
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeRestartCancel, "ids": []string{id}})
	readType(t, ctx, c, proto.TypeSessionRestarted)
}
