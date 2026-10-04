package server

import (
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
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

// Against a real Claude Code, only when PORTHOLE_REAL_CLAUDE is set (it spends a few
// tokens of Haiku): the prompt that was lost on 2026-10-03 - long, naming three images,
// whose Enter the CLI dropped while attaching them - is typed and submitted, and the CLI
// records it with its images.
func TestAPromptNamingImagesReachesARealClaude(t *testing.T) {
	if os.Getenv("PORTHOLE_REAL_CLAUDE") == "" {
		t.Skip("set PORTHOLE_REAL_CLAUDE=1 to drive a real Claude Code (Haiku)")
	}
	if _, err := exec.LookPath("claude"); err != nil {
		t.Skip("claude is not installed")
	}
	tmuxtest.Fresh(t)
	dir := t.TempDir()
	var paths []string
	for i := 0; i < 3; i++ {
		img := image.NewRGBA(image.Rect(0, 0, 64, 64))
		for x := 0; x < 64; x++ {
			for y := 0; y < 64; y++ {
				img.Set(x, y, color.RGBA{uint8(x * 4), uint8(y * 4), uint8(i * 80), 255})
			}
		}
		p := filepath.Join(dir, fmt.Sprintf("shot-%d.png", i))
		f, _ := os.Create(p)
		_ = png.Encode(f, img)
		f.Close()
		paths = append(paths, p)
	}
	tm := func(args ...string) string {
		out, _ := exec.Command("tmux", args...).CombinedOutput()
		return string(out)
	}
	for try := 0; try < 40; try++ {
		if out, err := exec.Command("tmux", "new-session", "-d", "-s", "real", "-x", "150", "-y", "50", "-c", dir, "claude --model haiku").CombinedOutput(); err == nil {
			break
		} else if try == 39 {
			t.Fatalf("tmux: %v %s", err, out)
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Cleanup(func() {
		_ = exec.Command("tmux", "send-keys", "-t", "real", "C-c").Run()
		time.Sleep(300 * time.Millisecond)
		_ = exec.Command("tmux", "send-keys", "-t", "real", "C-c").Run()
		time.Sleep(time.Second)
		_ = exec.Command("tmux", "kill-session", "-t", "real").Run()
		// The CLI keeps a project for the temporary folder, and writes to it as it exits;
		// once it has, that folder, and only that one, goes.
		time.Sleep(2 * time.Second)
		if slug := transcript.ProjectSlug(dir); strings.Contains(slug, "TestAPromptNamingImages") {
			_ = os.RemoveAll(filepath.Join(transcript.ProjectsDir(), slug))
			time.Sleep(time.Second)
			_ = os.RemoveAll(filepath.Join(transcript.ProjectsDir(), slug))
		}
	})
	ready := false
	for i := 0; i < 60 && !ready; i++ {
		time.Sleep(500 * time.Millisecond)
		pane := tm("capture-pane", "-p", "-t", "real")
		if strings.Contains(pane, "trust this folder") {
			tm("send-keys", "-t", "real", "Down")
			time.Sleep(300 * time.Millisecond)
			tm("send-keys", "-t", "real", "Enter")
			continue
		}
		ready = strings.Contains(pane, "❯") && strings.Count(pane, "────") >= 2
	}
	if !ready {
		t.Fatalf("Claude Code did not come up:\n%s", tm("capture-pane", "-p", "-t", "real"))
	}
	id := registeredSession(t, dir, func() string { return tm("capture-pane", "-p", "-t", "real") })
	pane := strings.TrimSpace(tm("display-message", "-p", "-t", "real", "#{pane_id}"))
	si := session.Info{ID: id, Pane: pane, TmuxName: "real",
		Transcript: filepath.Join(transcript.ProjectsDir(), transcript.ProjectSlug(dir), id+".jsonl")}

	text := "Reply with the single word: ok."
	for i := 1; i <= 14; i++ {
		text += fmt.Sprintf("\nLine %d: filler so the message is long, the way a real request with detail is long.", i)
	}
	for _, p := range paths {
		text += "\n\nAttached image (read it with the Read tool): " + p
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	ok, err := typePrompt(ctx, si, text)
	if err != nil || !ok {
		t.Fatalf("typePrompt = %v, %v\n%s", ok, err, tm("capture-pane", "-p", "-t", "real"))
	}
	b, _ := os.ReadFile(si.Transcript)
	if !strings.Contains(string(b), "Line 14: filler") || !strings.Contains(string(b), `"type":"image"`) {
		t.Fatalf("the transcript does not hold the prompt with its images:\n%s", tm("capture-pane", "-p", "-t", "real"))
	}
}

// registeredSession waits for the CLI's registry to name the session running in dir.
func registeredSession(t *testing.T, dir string, screen func() string) string {
	t.Helper()
	home, _ := os.UserHomeDir()
	reg := filepath.Join(home, ".claude", "sessions")
	if c := os.Getenv("CLAUDE_CONFIG_DIR"); c != "" {
		reg = filepath.Join(c, "sessions")
	}
	for i := 0; i < 40; i++ {
		files, _ := filepath.Glob(filepath.Join(reg, "*.json"))
		for _, f := range files {
			b, _ := os.ReadFile(f)
			var r struct {
				Cwd       string `json:"cwd"`
				SessionID string `json:"sessionId"`
			}
			if json.Unmarshal(b, &r) == nil && r.Cwd == dir && r.SessionID != "" {
				return r.SessionID
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
	files, _ := filepath.Glob(filepath.Join(reg, "*.json"))
	t.Fatalf("the CLI's registry never named the session in %s (%d entries)\n%s", dir, len(files), screen())
	return ""
}

// The real CLI's sign-in, run against a throwaway config so no account changes: the
// phone gets Claude's own sign-in page for another device, and abandoning it ends the CLI.
func TestARealSignInGivesThePhoneClaudesSignInPage(t *testing.T) {
	if os.Getenv("PORTHOLE_REAL_CLAUDE") == "" {
		t.Skip("set PORTHOLE_REAL_CLAUDE=1 to drive a real Claude Code")
	}
	if claudeBinary() == "" {
		t.Skip("claude is not installed")
	}
	cfg := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", cfg)
	t.Setenv("PORTHOLE_STATE_DIR", t.TempDir()) // the stubs, not in the real porthole folder
	srv, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	c.SetReadLimit(1 << 22)
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountGet})
	var before accountState
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeAccountState), &before)
	if before.LoggedIn {
		t.Fatalf("a throwaway config is signed in: %+v", before)
	}
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountSignIn})
	var l accountLink
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeAccountLink), &l)
	if !strings.Contains(l.URL, "/oauth/authorize?") || !strings.Contains(l.URL, "platform.claude.com%2Foauth%2Fcode%2Fcallback") {
		t.Fatalf("link = %q", l.URL)
	}
	if underSystemd() {
		// Under the service's sandbox the CLI must run in a unit of its own, outside it.
		out, _ := exec.Command("systemctl", "--user", "list-units", "--no-legend", "--state=active", "porthole-claude-auth-*").Output()
		if !strings.Contains(string(out), "porthole-claude-auth-") {
			t.Fatal("under systemd, the sign-in did not run in a unit of its own")
		}
		t.Log("ran outside the sandbox:", strings.Fields(string(out))[0])
	}
	srv.accounts.mu.Lock()
	pid := srv.accounts.cur.cmd.Process.Pid
	srv.accounts.mu.Unlock()
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeAccountCancel})
	time.Sleep(500 * time.Millisecond)
	if underSystemd() {
		gone := false
		for i := 0; i < 50 && !gone; i++ {
			out, _ := exec.Command("systemctl", "--user", "list-units", "--no-legend", "--state=active", "porthole-claude-auth-*").Output()
			gone = !strings.Contains(string(out), "porthole-claude-auth-")
			time.Sleep(100 * time.Millisecond)
		}
		if !gone {
			t.Fatal("the sign-in's unit is still active")
		}
	}
	if e1, e2 := syscall.Kill(pid, 0), syscall.Kill(-pid, 0); e1 == nil || e2 == nil {
		ps, _ := exec.Command("ps", "-eo", "pid,ppid,pgid,stat,args").Output()
		var mine []string
		for _, l := range strings.Split(string(ps), "\n") {
			if f := strings.Fields(l); len(f) > 3 && (f[0] == strconv.Itoa(pid) || f[2] == strconv.Itoa(pid)) {
				mine = append(mine, l)
			}
		}
		t.Fatalf("the sign-in is still running (kill pid: %v, group: %v):\n%s", e1, e2, strings.Join(mine, "\n"))
	}
	if _, err := os.Stat(filepath.Join(cfg, ".credentials.json")); err == nil {
		t.Fatal("credentials were written by an abandoned sign-in")
	}
}

// A real session restarted from the phone stops and comes back in its pane on the same
// conversation. Starting Claude Code spends no tokens.
func TestARealSessionRestartsOnItsConversation(t *testing.T) {
	if os.Getenv("PORTHOLE_REAL_CLAUDE") == "" {
		t.Skip("set PORTHOLE_REAL_CLAUDE=1 to drive a real Claude Code")
	}
	bin := claudeBinary()
	if bin == "" {
		t.Skip("claude is not installed")
	}
	home, _ := os.UserHomeDir()
	// For the daemon's own lookups only: the CLI's registry and transcripts. Never handed
	// to the CLI itself - with it set, Claude Code reads its config from inside this folder,
	// finds none, and runs its first-run setup against the real settings.
	t.Setenv("CLAUDE_CONFIG_DIR", filepath.Join(home, ".claude"))
	tmuxtest.Fresh(t)
	dir := t.TempDir()
	tm := func(args ...string) string {
		out, _ := exec.Command("tmux", args...).CombinedOutput()
		return string(out)
	}
	for try := 0; ; try++ {
		if out, err := exec.Command("tmux", "new-session", "-d", "-s", "realrs", "-x", "150", "-y", "50", "-c", dir,
			// A clean environment but tmux's own, which is how the CLI knows its pane.
			"sh", "-c", `exec env -i TMUX="$TMUX" TMUX_PANE="$TMUX_PANE" PATH="$0" HOME="$1" TERM=xterm-256color LANG=C.UTF-8 sh`,
			filepath.Dir(bin)+":/usr/bin:/bin", home).CombinedOutput(); err == nil {
			break
		} else if try == 40 {
			t.Fatalf("tmux: %v %s", err, out)
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Cleanup(func() {
		_ = exec.Command("tmux", "send-keys", "-t", "realrs", "C-c").Run()
		time.Sleep(300 * time.Millisecond)
		_ = exec.Command("tmux", "send-keys", "-t", "realrs", "C-c").Run()
		time.Sleep(time.Second)
		_ = exec.Command("tmux", "kill-session", "-t", "realrs").Run()
		time.Sleep(2 * time.Second)
		if slug := transcript.ProjectSlug(dir); strings.Contains(slug, "TestARealSessionRestarts") {
			_ = os.RemoveAll(filepath.Join(transcript.ProjectsDir(), slug))
			time.Sleep(time.Second)
			_ = os.RemoveAll(filepath.Join(transcript.ProjectsDir(), slug))
		}
	})
	tm("send-keys", "-t", "realrs", "-l", "claude --model haiku")
	tm("send-keys", "-t", "realrs", "Enter")
	up := func() bool {
		for i := 0; i < 60; i++ {
			time.Sleep(500 * time.Millisecond)
			pane := tm("capture-pane", "-p", "-t", "realrs")
			if strings.Contains(pane, "trust this folder") {
				tm("send-keys", "-t", "realrs", "Down")
				time.Sleep(300 * time.Millisecond)
				tm("send-keys", "-t", "realrs", "Enter")
				continue
			}
			if strings.Contains(pane, "❯") && strings.Count(pane, "────") >= 2 {
				return true
			}
		}
		return false
	}
	if !up() {
		t.Fatalf("Claude Code did not come up:\n%s", tm("capture-pane", "-p", "-t", "realrs"))
	}
	id := registeredSession(t, dir, func() string { return tm("capture-pane", "-p", "-t", "realrs") })
	// A conversation to resume, without asking the model anything: a shell-mode line.
	tm("send-keys", "-t", "realrs", "-l", "!echo porthole-restart")
	time.Sleep(400 * time.Millisecond)
	tm("send-keys", "-t", "realrs", "Enter")
	for i := 0; i < 40 && !hasTranscript(id); i++ {
		time.Sleep(250 * time.Millisecond)
	}
	if !hasTranscript(id) {
		t.Fatalf("no transcript to resume:\n%s", tm("capture-pane", "-p", "-t", "realrs"))
	}
	var first session.Proc
	for i := 0; i < 40; i++ {
		if p, ok := procFor(id); ok && p.Status == "idle" {
			first = p
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if first.PID == 0 {
		t.Fatal("the session never showed as idle in the CLI's registry")
	}

	_, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	c.SetReadLimit(1 << 22)
	sendFrame(t, ctx, c, map[string]any{"type": proto.TypeSessionsRestart, "ids": []string{id}})
	var f restartFrame
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSessionRestarted), &f)
	if f.State != "restarted" {
		t.Fatalf("restart: %+v\n%s", f, tm("capture-pane", "-p", "-t", "realrs"))
	}
	if !up() {
		t.Fatalf("Claude Code did not come back:\n%s", tm("capture-pane", "-p", "-t", "realrs"))
	}
	var again session.Proc
	for i := 0; i < 40; i++ {
		if p, ok := procFor(id); ok && p.PID != first.PID {
			again = p
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if again.PID == 0 {
		t.Fatalf("no new CLI registered the same conversation:\n%s", tm("capture-pane", "-p", "-t", "realrs"))
	}
	if syscall.Kill(first.PID, 0) == nil {
		t.Fatal("the old CLI is still running")
	}
	args, _ := exec.Command("ps", "-o", "args=", "-p", strconv.Itoa(again.PID)).Output()
	if !strings.Contains(string(args), "--resume "+id) {
		t.Fatalf("the new CLI is %q", args)
	}
}
