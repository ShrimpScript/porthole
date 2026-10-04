package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/creack/pty"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// The Claude account Claude Code on this computer is signed in to, and signing in to
// another from the phone. Claude Code keeps one sign-in per user, so this is the
// computer's account, for every session on it.
//
// Signing in is Claude Code's own `claude auth login`. It opens a browser on the computer
// with a localhost callback, and also prints a link any device can open, then waits for
// the code that link's page shows. The daemon runs it with no display and every way of
// opening a browser stubbed out - nobody is at the desk, and a browser there could finish
// the sign-in with whatever account it happens to hold - and hands the link to the phone.
// The phone signs in, and the code comes back to the CLI. Porthole stores nothing.

// findClaude locates Claude Code; a variable so tests run a stand-in.
var findClaude = claudeBinary

// claudeBinary finds Claude Code to run it directly. Sessions never needed this - the
// phone types `claude` into the person's own shell - and the daemon's service has a
// narrower PATH than that shell (~/.local/bin, where the installer puts it, is not on it).
func claudeBinary() string {
	if p, err := exec.LookPath("claude"); err == nil {
		return p
	}
	home, _ := os.UserHomeDir()
	for _, p := range []string{
		filepath.Join(home, ".local", "bin", "claude"),
		filepath.Join(home, ".claude", "local", "claude"),
		"/opt/homebrew/bin/claude",
		"/usr/local/bin/claude",
		filepath.Join(home, ".npm-global", "bin", "claude"),
		filepath.Join(home, ".bun", "bin", "claude"),
	} {
		if st, err := os.Stat(p); err == nil && !st.IsDir() && st.Mode()&0o111 != 0 {
			return p
		}
	}
	// Wherever the login shell finds it.
	sh := os.Getenv("SHELL")
	if sh == "" {
		sh = "/bin/sh"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, sh, "-l", "-c", "command -v claude").Output()
	if err != nil {
		return ""
	}
	p := strings.TrimSpace(string(out))
	if !filepath.IsAbs(p) {
		return ""
	}
	return p
}

// ---- running Claude Code's auth commands ----------------------------------------------

// underSystemd: the daemon is a systemd service, whose sandbox leaves the filesystem
// read-only outside porthole's own folder (ProtectSystem=strict) - Claude Code could
// neither write a sign-in nor remove one there. Its auth commands then run as a transient
// user unit of their own, outside the sandbox, the way a tmux server is (tmuxstart.go).
var underSystemd = func() bool { return os.Getenv("INVOCATION_ID") != "" }

// noDisplay is what the auth commands must not see: a display to open a browser on.
var noDisplay = []string{"DISPLAY", "WAYLAND_DISPLAY", "DBUS_SESSION_BUS_ADDRESS", "BROWSER"}

// authCmd builds `claude <args>`: directly, or in a transient unit under systemd. stubs,
// when set, is a directory of no-op browser openers put first on the PATH. tty gives the
// command a terminal (the sign-in prompts on one); otherwise its output comes back on
// stdout. The unit's name is returned, to stop it by.
func authCmd(ctx context.Context, bin, stubs string, tty bool, args ...string) (*exec.Cmd, string) {
	home, _ := os.UserHomeDir()
	path := os.Getenv("PATH")
	if stubs != "" {
		path = stubs + string(os.PathListSeparator) + path
	}
	set := map[string]string{"PATH": path}
	if stubs != "" {
		set["BROWSER"] = filepath.Join(stubs, "xdg-open")
	}
	if d := os.Getenv("CLAUDE_CONFIG_DIR"); d != "" {
		set["CLAUDE_CONFIG_DIR"] = d
	}
	if !underSystemd() {
		cmd := exec.CommandContext(ctx, bin, args...)
		cmd.Dir = home
		var env []string
		for _, kv := range os.Environ() {
			k, _, _ := strings.Cut(kv, "=")
			if _, over := set[k]; over || isNoDisplay(k) {
				continue
			}
			env = append(env, kv)
		}
		for k, v := range set {
			env = append(env, k+"="+v)
		}
		cmd.Env = env
		return cmd, ""
	}
	unit := fmt.Sprintf("porthole-claude-auth-%d-%d", os.Getpid(), time.Now().UnixNano())
	argv := []string{"--user", "--quiet", "--collect", "--wait", "--unit=" + unit, "--working-directory=" + home}
	if tty {
		argv = append(argv, "--pty")
	} else {
		argv = append(argv, "--pipe")
	}
	for _, k := range noDisplay {
		argv = append(argv, "-p", "UnsetEnvironment="+k)
	}
	for k, v := range set {
		argv = append(argv, "--setenv="+k+"="+v)
	}
	argv = append(argv, bin)
	argv = append(argv, args...)
	return exec.CommandContext(ctx, "systemd-run", argv...), unit
}

func isNoDisplay(k string) bool {
	for _, x := range noDisplay {
		if x == k {
			return true
		}
	}
	return false
}

func stopUnit(unit string) {
	if unit == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = exec.CommandContext(ctx, "systemctl", "--user", "stop", unit).Run()
}

type accountState struct {
	proto.Frame
	LoggedIn bool   `json:"logged_in"`
	Email    string `json:"email,omitempty"`
	Plan     string `json:"plan,omitempty"`   // "pro", "max", "team", "enterprise", as the CLI says it
	Method   string `json:"method,omitempty"` // "claude.ai" (a subscription) or "console" (API billing)
	Org      string `json:"org,omitempty"`
	Error    string `json:"error,omitempty"` // the status could not be read
}

// accountStatus reads `claude auth status --json`.
func accountStatus(ctx context.Context) accountState {
	st := accountState{Frame: proto.Frame{V: proto.Version, Type: proto.TypeAccountState}}
	bin := findClaude()
	if bin == "" {
		st.Error = "Claude Code was not found on the computer"
		return st
	}
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	cmd, _ := authCmd(ctx, bin, "", false, "auth", "status", "--json")
	out, err := cmd.Output()
	var r struct {
		LoggedIn         bool   `json:"loggedIn"`
		AuthMethod       string `json:"authMethod"`
		Email            string `json:"email"`
		OrgName          string `json:"orgName"`
		SubscriptionType string `json:"subscriptionType"`
	}
	// Signed out, the CLI still prints its JSON, and exits non-zero.
	if jerr := json.Unmarshal(bytes.TrimSpace(out), &r); jerr != nil {
		if err == nil {
			err = jerr
		}
		st.Error = "could not read Claude Code's sign-in: " + err.Error()
		return st
	}
	st.LoggedIn, st.Email, st.Plan, st.Method, st.Org = r.LoggedIn, r.Email, r.SubscriptionType, r.AuthMethod, r.OrgName
	return st
}

// browserStubs are the commands that open a browser, on Linux and on a Mac.
var browserStubs = []string{"xdg-open", "open", "sensible-browser", "x-www-browser", "www-browser", "wslview", "gio", "kde-open", "gnome-open"}

// writeStubs makes a directory of no-op browser openers, in porthole's own folder: the
// one place the service may write.
func writeStubs() (string, error) {
	parent := filepath.Join(filepath.Dir(uploadsDir()), "signin")
	if err := os.MkdirAll(parent, 0o700); err != nil {
		return "", err
	}
	dir, err := os.MkdirTemp(parent, "run-")
	if err != nil {
		return "", err
	}
	for _, name := range browserStubs {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("#!/bin/sh\nexit 0\n"), 0o755); err != nil {
			os.RemoveAll(dir)
			return "", err
		}
	}
	return dir, nil
}

// ---- signing in --------------------------------------------------------------------------

// signIn is one `claude auth login` in progress.
type signIn struct {
	cmd    *exec.Cmd
	unit   string // its transient unit, under systemd
	ptmx   *os.File
	done   chan struct{} // closed when it has exited
	err    error         // its exit, once done is closed
	mu     sync.Mutex
	out    bytes.Buffer // everything it printed
	sentAt int          // out's length when the code was typed
	url    chan string  // the link, once printed
	state  string       // the link's state: the code from its page ends with it
}

func (si *signIn) output() string {
	si.mu.Lock()
	defer si.mu.Unlock()
	return si.out.String()
}

func (si *signIn) exited() bool {
	select {
	case <-si.done:
		return true
	default:
		return false
	}
}

// stop ends it, unless it has ended already: its process group may be someone else's by now.
func (si *signIn) stop() {
	if si.exited() {
		return
	}
	if si.cmd.Process != nil {
		_ = syscall.Kill(-si.cmd.Process.Pid, syscall.SIGKILL)
		_ = si.cmd.Process.Kill()
	}
	<-si.done // its unit, if it has one, is stopped as it ends
}

// The link the CLI prints for another device: its callback is Anthropic's code page,
// not a localhost port on this computer.
var authorizeURL = regexp.MustCompile(`https://[^\s"'<>]+/oauth/authorize\?[^\s"'<>]+`)

var ansiSeq = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(\x07|\x1b\\)|\x1b[()][0-9A-Za-z]|\x1b[=>]`)

func plainText(s string) string { return ansiSeq.ReplaceAllString(s, "") }

func linkIn(out string) string {
	for _, u := range authorizeURL.FindAllString(plainText(out), -1) {
		if !strings.Contains(u, "redirect_uri=http%3A%2F%2Flocalhost") && !strings.Contains(u, "redirect_uri=http://localhost") {
			return u
		}
	}
	return ""
}

var linkState = regexp.MustCompile(`[?&]state=([^&#\s]+)`)

// signInTimeout is how long a started sign-in may wait for its code.
var signInTimeout = 10 * time.Minute

type accountLink struct {
	proto.Frame
	URL string `json:"url"`
}

type accountDone struct {
	proto.Frame
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	// Retry: the CLI is still waiting, so another code can be pasted.
	Retry bool `json:"retry,omitempty"`
	// State names the sign-in it is about (its link's state), so a phone that asks again
	// after losing its connection can tell its own.
	State string `json:"state,omitempty"`
}

// accounts holds the sign-in under way, at most one for the computer, and how the last
// one ended, for a phone whose connection dropped while it finished.
type accounts struct {
	start  sync.Mutex // one sign-in starts at a time
	mu     sync.Mutex
	cur    *signIn
	last   *accountDone
	lastAt time.Time
}

func (a *accounts) take() *signIn {
	a.mu.Lock()
	defer a.mu.Unlock()
	c := a.cur
	a.cur = nil
	return c
}

// drop forgets run if it is still the current one.
func (a *accounts) drop(run *signIn) {
	a.mu.Lock()
	if a.cur == run {
		a.cur = nil
	}
	a.mu.Unlock()
}

func (a *accounts) ended(d accountDone) {
	a.mu.Lock()
	a.last, a.lastAt = &d, time.Now()
	a.mu.Unlock()
}

// recent is how the last sign-in ended, if that was in the last ten minutes.
func (a *accounts) recent() *accountDone {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.last == nil || time.Since(a.lastAt) > 10*time.Minute {
		return nil
	}
	d := *a.last
	return &d
}

func doneFrame(d accountDone) accountDone {
	d.Frame = proto.Frame{V: proto.Version, Type: proto.TypeAccountDone}
	return d
}

// sendLater sends on a connection without its request's context, which may be gone:
// a result that cannot be delivered now is kept (accounts.recent) for the next ask.
func sendLater(w *writer, v any) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = w.send(ctx, v)
}

// accountGet answers account.get: the account, and how a sign-in that ended lately went.
func (s *Server) accountGet(ctx context.Context, w *writer) {
	_ = w.send(ctx, accountStatus(ctx))
	if d := s.accounts.recent(); d != nil {
		_ = w.send(ctx, doneFrame(*d))
	}
}

// signInStart starts `claude auth login` and sends the phone its link. A sign-in already
// under way, from this phone or another, is abandoned: the newest attempt is the one
// someone is looking at.
func (s *Server) signInStart(ctx context.Context, w *writer, device string) {
	s.accounts.start.Lock()
	defer s.accounts.start.Unlock()
	if old := s.accounts.take(); old != nil {
		old.stop()
	}
	fail := func(msg string) {
		sendLater(w, doneFrame(accountDone{Error: msg}))
	}
	bin := findClaude()
	if bin == "" {
		fail("Claude Code was not found on the computer")
		return
	}
	stubs, err := writeStubs()
	if err != nil {
		fail("could not prepare the sign-in: " + err.Error())
		return
	}
	cmd, unit := authCmd(context.Background(), bin, stubs, true, "auth", "login", "--claudeai")
	// Wide, so the link is printed on one line rather than wrapped into pieces.
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Cols: 2000, Rows: 50})
	if err != nil {
		os.RemoveAll(stubs)
		fail("could not start Claude Code's sign-in: " + err.Error())
		return
	}
	run := &signIn{cmd: cmd, unit: unit, ptmx: ptmx, done: make(chan struct{}), url: make(chan string, 1)}
	go func() {
		buf := make([]byte, 4096)
		sent := false
		for {
			n, err := ptmx.Read(buf)
			if n > 0 {
				run.mu.Lock()
				run.out.Write(buf[:n])
				text := run.out.String()
				run.mu.Unlock()
				if !sent {
					if u := linkIn(text); u != "" {
						run.url <- u
						sent = true
					}
				}
			}
			if err != nil {
				break
			}
		}
	}()
	go func() {
		run.err = cmd.Wait()
		go stopUnit(unit) // a client gone leaves nothing running in its unit
		ptmx.Close()
		os.RemoveAll(stubs)
		close(run.done)
		s.accounts.drop(run)
	}()
	s.accounts.mu.Lock()
	s.accounts.cur = run
	s.accounts.mu.Unlock()
	s.log.Info("claude sign-in started from the phone", "from", device)

	// An unanswered sign-in does not wait forever.
	go func() {
		select {
		case <-run.done:
		case <-time.After(signInTimeout):
			s.accounts.drop(run)
			run.stop()
		}
	}()

	select {
	case u := <-run.url:
		if m := linkState.FindStringSubmatch(u); m != nil {
			run.mu.Lock()
			run.state = m[1]
			run.mu.Unlock()
		}
		sendLater(w, accountLink{Frame: proto.Frame{V: proto.Version, Type: proto.TypeAccountLink}, URL: u})
	case <-run.done:
		fail("Claude Code's sign-in ended before giving a link: " + lastLines(run.output(), 3))
	case <-time.After(30 * time.Second):
		s.accounts.drop(run)
		run.stop()
		fail("Claude Code did not give a sign-in link: " + lastLines(run.output(), 3))
	}
}

// signInCode types the code from the sign-in page into the waiting CLI and reports how
// it went, with the account it is signed in to now. It waits on the CLI, not on the
// phone's connection: a phone that dropped meanwhile hears the result when it asks again.
func (s *Server) signInCode(w *writer, code, device string) {
	s.accounts.mu.Lock()
	run := s.accounts.cur
	s.accounts.mu.Unlock()
	done := func(d accountDone) { sendLater(w, doneFrame(d)) }
	code = strings.TrimSpace(code)
	if run == nil || run.exited() {
		done(accountDone{Error: "no sign-in is waiting for a code - start again"})
		return
	}
	run.mu.Lock()
	state := run.state
	run.mu.Unlock()
	if code == "" || strings.ContainsAny(code, " \r\n\t\x1b") {
		done(accountDone{Error: "that is not a sign-in code", Retry: true, State: state})
		return
	}
	// The page's code ends with its link's state. One that does not is from another
	// sign-in (or not a code at all), and the CLI would fail on it and give up.
	if state != "" && !strings.HasSuffix(code, "#"+state) {
		done(accountDone{Error: "that code is from another sign-in - copy the one this page shows", Retry: true, State: state})
		return
	}
	run.mu.Lock()
	run.sentAt = run.out.Len()
	run.mu.Unlock()
	if _, err := run.ptmx.Write([]byte(code + "\r")); err != nil {
		done(accountDone{Error: "could not pass the code to Claude Code: " + err.Error(), State: state})
		return
	}
	select {
	case <-run.done:
		s.accounts.drop(run)
		if run.err != nil {
			d := accountDone{Error: "Claude Code did not sign in: " + lastLines(run.output(), 2), State: state}
			s.accounts.ended(d)
			done(d)
			return
		}
		st := accountStatus(context.Background())
		s.log.Info("claude signed in from the phone", "from", device, "plan", st.Plan)
		d := accountDone{OK: true, State: state}
		s.accounts.ended(d)
		sendLater(w, st)
		done(d)
	case <-time.After(45 * time.Second):
		// Still running: it did not take the code, and may be asking again.
		run.mu.Lock()
		after := run.out.String()[run.sentAt:]
		run.mu.Unlock()
		msg := lastLines(after, 2)
		if msg == "" {
			msg = "Claude Code did not accept the code"
		}
		done(accountDone{Error: msg, Retry: true, State: state})
	}
}

func (s *Server) signInCancel() {
	if run := s.accounts.take(); run != nil {
		run.stop()
	}
}

// signOut runs `claude auth logout`.
func (s *Server) signOut(ctx context.Context, w *writer, device string) {
	bin := findClaude()
	if bin == "" {
		_ = w.send(ctx, proto.NewError("no_claude", "Claude Code was not found on the computer"))
		return
	}
	cctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	cmd, _ := authCmd(cctx, bin, "", false, "auth", "logout")
	if out, err := cmd.CombinedOutput(); err != nil {
		_ = w.send(ctx, proto.NewError("signout_failed", "Claude Code did not sign out: "+lastLines(string(out), 2)))
		return
	}
	s.log.Info("claude signed out from the phone", "from", device)
	_ = w.send(ctx, accountStatus(ctx))
}

// lastLines is the end of a CLI's output, as text, for an error message.
func lastLines(out string, n int) string {
	var keep []string
	for _, l := range strings.Split(plainText(out), "\n") {
		if l = strings.TrimSpace(strings.ReplaceAll(l, "\r", "")); l != "" {
			keep = append(keep, l)
		}
	}
	if len(keep) > n {
		keep = keep[len(keep)-n:]
	}
	return truncRunes(strings.Join(keep, " "), 300)
}

func truncRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

// ---- restarting sessions on the account they should use -------------------------------

// Claude Code reads its sign-in when it starts, so sessions already running may carry on
// with the account they had. Restarting one stops its CLI and resumes the same
// conversation in the same pane. A session in the middle of something - a turn, a !
// command, a question, a permission prompt - is left to finish first; one waiting out a
// usage limit has the wait cancelled, since another account is why it is restarting.

type restartFrame struct {
	proto.Frame
	SessionID string `json:"session_id"`
	// "waiting" (busy; it restarts when it is free), "restarted", "failed" or "cancelled".
	State string `json:"state"`
	Error string `json:"error,omitempty"`
}

var (
	restartPoll    = 2 * time.Second
	restartPatient = 3 * time.Hour
	restartStop    = 10 * time.Second
)

// Session ids are the CLI's UUIDs; anything else is never typed into a shell.
var sessionUUID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// restarts are the sessions being restarted, so one is never restarted twice at once,
// and a waiting one can be called off.
type restarts struct {
	mu   sync.Mutex
	runs map[string]context.CancelFunc
}

func (r *restarts) begin(id string, cancel context.CancelFunc) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.runs == nil {
		r.runs = map[string]context.CancelFunc{}
	}
	if _, ok := r.runs[id]; ok {
		return false
	}
	r.runs[id] = cancel
	return true
}

func (r *restarts) end(id string) {
	r.mu.Lock()
	delete(r.runs, id)
	r.mu.Unlock()
}

func (r *restarts) cancel(id string) {
	r.mu.Lock()
	c := r.runs[id]
	r.mu.Unlock()
	if c != nil {
		c()
	}
}

func (s *Server) restartSessions(w *writer, ids []string, device string) {
	seen := map[string]bool{}
	for _, id := range ids {
		if seen[id] || len(seen) >= 50 {
			continue
		}
		seen[id] = true
		if !sessionUUID.MatchString(id) {
			sendLater(w, restartFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionRestarted}, SessionID: id, State: "failed", Error: "that is not a session id"})
			continue
		}
		// Not tied to the phone's connection: asked once, it happens even if the phone
		// has gone by the time the session is free.
		ctx, cancel := context.WithTimeout(context.Background(), restartPatient)
		if !s.restarting.begin(id, cancel) {
			cancel()
			sendLater(w, restartFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionRestarted}, SessionID: id, State: "waiting"})
			continue
		}
		go func(id string) {
			defer cancel()
			defer s.restarting.end(id)
			s.restartOne(ctx, w, id, device)
		}(id)
	}
}

func (s *Server) restartCancel(ids []string) {
	for _, id := range ids {
		s.restarting.cancel(id)
	}
}

func (s *Server) restartOne(ctx context.Context, w *writer, id, device string) {
	report := func(state, msg string) {
		sendLater(w, restartFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionRestarted}, SessionID: id, State: state, Error: msg})
	}
	told, escaped := false, false
	pinned := 0 // the CLI first found: only it is stopped
	for {
		p, ok := procFor(id)
		switch {
		case !ok:
			report("failed", "that session is not running")
			return
		case pinned != 0 && p.PID != pinned:
			report("failed", "it was started again meanwhile")
			return
		case !paneID.MatchString(p.Pane):
			report("failed", "that session is not running in tmux, so it cannot be restarted from here")
			return
		}
		pinned = p.PID
		if !busy(p) {
			if err := s.restartIn(ctx, p); err != nil {
				report("failed", err.Error())
				return
			}
			s.log.Info("session restarted from the phone", "session", id, "pane", p.Pane, "from", device)
			report("restarted", "")
			return
		}
		if !escaped && waitingOutLimit(ctx, p.Pane) {
			// Counting down to the reset (or holding at it for Enter): another account is
			// the reason for the restart, so the wait is cancelled rather than sat out.
			sendEscape(ctx, p.Pane)
			escaped = true
		}
		if !told {
			report("waiting", "")
			told = true
		}
		select {
		case <-ctx.Done():
			if errors.Is(ctx.Err(), context.Canceled) {
				report("cancelled", "")
			} else {
				report("failed", "it was still busy after "+restartPatient.String())
			}
			return
		case <-time.After(restartPoll):
		}
	}
}

// waitingOutLimit reads the pane for Claude Code's usage-limit wait.
var waitingOutLimit = func(ctx context.Context, pane string) bool {
	out, err := exec.CommandContext(ctx, "tmux", "capture-pane", "-p", "-t", pane).Output()
	if err != nil {
		return false
	}
	var f statusFrame
	for _, l := range strings.Split(string(out), "\n") {
		parseLimit(l, &f)
	}
	return f.LimitWaiting || f.LimitEnter
}

var sendEscape = func(ctx context.Context, pane string) {
	_ = exec.CommandContext(ctx, "tmux", "send-keys", "-t", pane, "Escape").Run()
}

// procFor is the running CLI of a session, the newest when it runs in two places.
var procFor = func(id string) (session.Proc, bool) {
	var best session.Proc
	found := false
	for _, p := range session.Procs() {
		if p.SessionID == id && (!found || p.StartedAt.After(best.StartedAt)) {
			best, found = p, true
		}
	}
	return best, found
}

// busy: anything but idle - a turn, a ! command, a question, a permission prompt, a menu.
func busy(p session.Proc) bool {
	switch p.Status {
	case "idle":
		return false
	case "":
		// An older CLI says nothing; its transcript does.
		if si, ok := session.Resolve(p.SessionID); ok {
			return si.Working
		}
		return false
	}
	return true
}

// psField reads one ps column for a process ("ppid", "comm"), on Linux and on a Mac.
func psField(pid int, field string) string {
	out, err := exec.Command("ps", "-o", field+"=", "-p", strconv.Itoa(pid)).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// restartIn stops the CLI and resumes the same conversation in the same pane, from the
// shell it was started from. Everything that would stop the resume is checked first, so
// a session is never stopped only to be left down.
func (s *Server) restartIn(ctx context.Context, p session.Proc) error {
	out, err := runCmd(ctx, "tmux", "display-message", "-p", "-t", p.Pane, "#{pane_id}\t#{pane_pid}\t#{pane_dead}")
	f := strings.Split(strings.TrimRight(string(out), "\n"), "\t")
	// A pane that has gone answers with empty fields, not an error.
	if err != nil || len(f) < 3 || f[0] == "" || f[2] == "1" {
		return errors.New("its tmux pane is gone; start it again from the session")
	}
	shell, _ := strconv.Atoi(f[1])
	if !isShell(psField(shell, "comm")) || psField(p.PID, "ppid") != strconv.Itoa(shell) {
		return errors.New("Claude Code is not run from its pane's own shell there, so it was left running; restart it from the session")
	}
	if err := stopProcess(p.PID); err != nil {
		return err
	}
	// The shell comes back to its prompt; a prompt may run a moment of its own (git, a
	// theme), so it gets a few seconds to be idle.
	idle := false
	var cwd string
	for i := 0; i < 30 && !idle; i++ {
		time.Sleep(100 * time.Millisecond)
		o, err := runCmd(ctx, "tmux", "display-message", "-p", "-t", p.Pane, "#{pane_current_command}\t#{pane_current_path}")
		g := strings.Split(strings.TrimRight(string(o), "\n"), "\t")
		if err == nil && len(g) >= 2 && isShell(g[0]) && !hasChildren(shell) {
			idle, cwd = true, g[1]
		}
	}
	if !idle {
		return errors.New("Claude Code stopped, but its pane's shell did not come back to a prompt; start it again from the session")
	}
	// A session never prompted has no conversation saved to resume: it starts afresh.
	line := "claude"
	if hasTranscript(p.SessionID) {
		if p.Cwd != "" && samePath(cwd) != samePath(p.Cwd) {
			// `claude --resume <id>` looks for the conversation where it is run.
			return fmt.Errorf("Claude Code stopped, but its shell is in %s, not the session's folder; start it again from the session", cwd)
		}
		line = "claude --resume " + p.SessionID
	}
	if err := exec.CommandContext(ctx, "tmux", "send-keys", "-t", p.Pane, "-l", line).Run(); err != nil {
		return errors.New("could not type into its tmux pane")
	}
	if err := exec.CommandContext(ctx, "tmux", "send-keys", "-t", p.Pane, "Enter").Run(); err != nil {
		return errors.New("could not type into its tmux pane")
	}
	return nil
}

func hasTranscript(id string) bool {
	m, _ := filepath.Glob(filepath.Join(transcript.ProjectsDir(), "*", id+".jsonl"))
	return len(m) > 0
}

// stopProcess ends a CLI the way closing its terminal would: SIGHUP, then SIGTERM, then
// SIGKILL, a few seconds apart. Its conversation is already on disk.
var stopProcess = func(pid int) error {
	if pid <= 0 {
		return errors.New("no process to stop")
	}
	for _, sig := range []syscall.Signal{syscall.SIGHUP, syscall.SIGTERM, syscall.SIGKILL} {
		if err := syscall.Kill(pid, sig); err != nil {
			if errors.Is(err, syscall.ESRCH) {
				return nil
			}
			return fmt.Errorf("could not stop Claude Code: %v", err)
		}
		deadline := time.Now().Add(restartStop / 3)
		for time.Now().Before(deadline) {
			if syscall.Kill(pid, 0) != nil {
				return nil
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
	return errors.New("Claude Code did not stop")
}
