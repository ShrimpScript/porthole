package server

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"hash/fnv"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/shrimpscript/porthole/daemon/internal/platform"
	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/tailnet"
	"github.com/shrimpscript/porthole/daemon/internal/termbridge"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// backfillRows is what a phone gets when it attaches. Enough to orient, not so much
// that a 20,000-row transcript is pushed down a radio link.
const backfillRows = 60

type rowsFrame struct {
	proto.Frame
	SessionID string            `json:"session_id"`
	Rows      []transcript.Row  `json:"rows"`
	Backfill  bool              `json:"backfill,omitempty"`
	Earlier   bool              `json:"earlier,omitempty"`   // rows older than what the phone holds; prepend
	Synthetic bool              `json:"synthetic,omitempty"` // rows the daemon made up (a switch notice): not transcript rows
	Remaining int               `json:"remaining,omitempty"` // older rows still available after these
	Meta      *transcript.Meta  `json:"meta,omitempty"`
	State     *transcript.State `json:"state,omitempty"`
}

// statusFrame is what the CLI's own screen says: read from the tmux pane, so it is the
// exact spinner text a person at the desk would see, never a guess.
type statusFrame struct {
	proto.Frame
	SessionID      string `json:"session_id"`
	Working        bool   `json:"working"`
	Text           string `json:"text,omitempty"`    // "Nebulizing…"
	Elapsed        string `json:"elapsed,omitempty"` // "12s"
	Tokens         string `json:"tokens,omitempty"`  // "1.2k tokens"
	PermissionMode string `json:"permission_mode,omitempty"`
	Interruptible  bool   `json:"interruptible"`
	// A claude.ai usage limit, as the CLI reports it on screen (interactive-mode docs,
	// "Wait for a usage limit to reset"). Empty when no limit line is showing.
	LimitText     string `json:"limit_text,omitempty"`      // the line as shown
	LimitResumeAt string `json:"limit_resume_at,omitempty"` // "3:45pm" from "continuing automatically at 3:45pm"
	LimitWaiting  bool   `json:"limit_waiting"`             // the CLI is counting down to continue on its own
	LimitEnter    bool   `json:"limit_enter"`               // "press enter to continue" - the phone can send it
	LimitStopped  bool   `json:"limit_stopped"`             // gave up after repeated hits, or cancelled
	Effort        string `json:"effort,omitempty"`          // "xhigh" from the CLI's "◉ xhigh · /effort" line, shown after a turn
	EffortDefault string `json:"effort_default,omitempty"`  // the CLI's saved default (settings.json effortLevel), for when the line is not up
	// The question picker on screen, if any: the live card the phone answers from.
	Question *ScreenQuestion `json:"question,omitempty"`
}

// clientFrame is the subset of client->server frames the daemon understands.
type clientFrame struct {
	Type      string `json:"type"`
	SessionID string `json:"session_id"`
	Text      string `json:"text"`
	ToolUseID string `json:"tool_use_id"`
	Decision  string `json:"decision"`
	Reason    string `json:"reason"`
	Cols      int    `json:"cols"`
	Rows      int    `json:"rows"`
	Lines     int    `json:"lines,omitempty"`
	Data      string `json:"data"`
	Key       string `json:"key"`
	Ref       string `json:"ref"`
	Seconds   int    `json:"seconds"`
	Live      bool   `json:"live"`    // a watch-mode frame: replaces the last one, never a feed row
	Port      int    `json:"port"`    // preview.open / preview.close: the local dev server's port
	Before    int    `json:"before"`  // session.earlier: how many transcript rows the phone already holds
	Mode      string `json:"mode"`    // session.start: "resume" (this transcript) or "new" (fresh, same directory)
	Option    int    `json:"option"`  // session.answer: the 1-based option to press (0: none)
	Advance   bool   `json:"advance"` // session.answer: Right, to leave a multi-select question for the next tab
	Submit    bool   `json:"submit"`  // session.answer: Enter on the picker's review screen
	// Images attached to a prompt from the phone. Saved beside the daemon's state and
	// named in the prompt, so Claude can Read them.
	Attachments []promptImage `json:"attachments"`
	PublicKey   string        `json:"public_key"` // ssh.key: the phone's failsafe key; empty removes it
	Cwd         string        `json:"cwd"`        // session.new: the folder to start Claude Code in
	Pane        string        `json:"pane"`       // session.trust: the pane asking
	Trust       bool          `json:"trust"`      // session.trust: trust the folder, or quit
	Query       string        `json:"query"`      // files.get: what follows the "@" so far
	// upload.chunk: one piece of a file; prompt.send names the finished ones in Uploads.
	Upload  string   `json:"upload"`
	Seq     int      `json:"seq"`
	Last    bool     `json:"last"`
	Name    string   `json:"name"`
	Media   string   `json:"media"`
	Uploads []string `json:"uploads"`
}

type promptImage struct {
	Name  string `json:"name"`
	Media string `json:"media"`
	Data  string `json:"data"` // base64
}

type imageFrame struct {
	proto.Frame
	SessionID string `json:"session_id,omitempty"`
	Ref       string `json:"ref"`
	Media     string `json:"media"`
	Data      string `json:"data"` // base64
	Text      string `json:"text,omitempty"`
}

type ptyDataFrame struct {
	proto.Frame
	Data string `json:"data"` // base64: terminal output is bytes, not text
}

type ptySizeFrame struct {
	proto.Frame
	Cols int `json:"cols"`
	Rows int `json:"rows"`
}

// attachment is one connection's view of one session.
type attachment struct {
	mu     sync.Mutex
	cancel context.CancelFunc
	id     string // the attached session, for "earlier"
	path   string // its transcript

	termMu sync.Mutex
	term   *termbridge.Bridge
	// termPane is the tmux pane the open terminal shows, for walking its history.
	termPane string
}

func (a *attachment) closeTerm() {
	a.termMu.Lock()
	defer a.termMu.Unlock()
	if a.term != nil {
		_ = a.term.Close()
		a.term = nil
	}
}

func (a *attachment) stop() {
	a.mu.Lock()
	if a.cancel != nil {
		a.cancel()
		a.cancel = nil
	}
	a.mu.Unlock()
	a.closeTerm()
}

// writer serialises writes to one socket. A websocket connection permits exactly one
// concurrent writer, and the tailer goroutine writes alongside request handling.
type writer struct {
	mu sync.Mutex
	c  *websocket.Conn
}

func (w *writer) send(ctx context.Context, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return w.c.Write(wctx, websocket.MessageText, b)
}

// serveClient reads client frames until the socket closes.
func (s *Server) serveClient(ctx context.Context, w *writer, deviceName string, peer *tailnet.Peer) {
	att := &attachment{}
	defer att.stop()
	ups := newUploads()
	defer ups.close()
	go keepalive(ctx, w.c)

	for {
		_, data, err := w.c.Read(ctx)
		if err != nil {
			return
		}
		var f clientFrame
		if json.Unmarshal(data, &f) != nil {
			continue // unknown or malformed frames are ignored, never fatal
		}
		switch f.Type {
		case proto.TypeSessionAttach:
			s.attach(ctx, w, att, f.SessionID)
		case proto.TypeUploadChunk:
			// Said once per upload: the pieces after a failed one are dropped quietly.
			if err := ups.chunk(f.Upload, f.Seq, f.Data, f.Name, f.Media, f.Last); err != nil {
				_ = w.send(ctx, proto.NewErrorFor("upload_failed", err.Error(), f.Upload))
			}
		case proto.TypePromptSend:
			text := f.Text
			// A prompt names itself (prompt_ack) so the phone holds it until it is typed;
			// one with files is named by its first upload, as before.
			ref := f.Ref
			if len(f.Uploads) > 0 {
				ref = f.Uploads[0]
			}
			if len(f.Uploads) > 0 || len(f.Attachments) > 0 {
				// Files for a session that cannot take the prompt would only be kept
				// for nothing: say so, and let them go.
				si, ok := s.findSession(f.SessionID)
				if !ok || !si.Live {
					ups.discard(f.Uploads)
					if !ok {
						_ = w.send(ctx, proto.NewErrorFor("no_session", "that session is no longer here", ref))
					} else {
						_ = w.send(ctx, proto.NewErrorFor("not_live", notRunningMessage(si), ref))
					}
					continue
				}
			}
			var files []savedFile
			switch {
			case len(f.Uploads) > 0:
				var err error
				if files, err = ups.take(f.Uploads); err != nil {
					_ = w.send(ctx, proto.NewErrorFor("upload_failed", err.Error(), ref))
					continue
				}
				s.log.Info("files attached", "from", deviceName, "count", len(files))
				text = attachedPrompt(text, files)
			case len(f.Attachments) > 0:
				text = s.attachFiles(ctx, w, text, f.Attachments, deviceName)
				if text == "" {
					continue
				}
			}
			if !s.sendPrompt(ctx, w, f.SessionID, text, deviceName, ref) {
				// Not typed: the phone gets the message back and sends the files again.
				for _, f := range files {
					_ = os.Remove(f.path)
				}
			} else if ref != "" {
				// Typed: the phone can let go of the files it kept in case.
				_ = w.send(ctx, map[string]any{"v": proto.Version, "type": proto.TypePromptSent, "ref": ref})
			}
		case proto.TypePTYOpen:
			s.openTerminal(ctx, w, att, f.SessionID, f.Cols, f.Rows, deviceName)
		case proto.TypePTYInput:
			att.termMu.Lock()
			t := att.term
			att.termMu.Unlock()
			if t != nil {
				if b, err := base64.StdEncoding.DecodeString(f.Data); err == nil {
					_, _ = t.Write(b)
				}
			}
		case proto.TypePTYScroll:
			att.termMu.Lock()
			pane := att.termPane
			att.termMu.Unlock()
			if pane != "" {
				scrollPane(ctx, pane, f.Lines)
			}
		case proto.TypePTYResize:
			att.termMu.Lock()
			t := att.term
			att.termMu.Unlock()
			if t != nil {
				_ = t.Resize(f.Cols, f.Rows)
			}
		case proto.TypePermissionDecide:
			// Only allow/deny are honoured. Anything else is dropped rather than
			// guessed at - there is no safe default for "some other word".
			if f.Decision != "allow" && f.Decision != "deny" {
				continue
			}
			if !s.approvals.Decide(f.ToolUseID, Decision{Decision: f.Decision, Reason: f.Reason}) {
				s.log.Debug("late permission decision ignored", "id", f.ToolUseID)
			}
		case proto.TypeSessionList:
			if err := s.writeSessionList(ctx, w.c); err != nil {
				s.log.Warn("session list refresh failed", "err", err)
			}
		case proto.TypeSessionEarlier:
			s.earlier(ctx, w, att, f.Before)
		case proto.TypeSessionStart:
			s.startClaude(ctx, w, f.SessionID, f.Mode, deviceName)
		case proto.TypeSessionNew:
			s.newClaude(ctx, w, f.Cwd, deviceName)
		case proto.TypeSessionTrust:
			go s.answerTrust(ctx, w, f.Pane, f.Trust, deviceName) // it waits on the screen
		case proto.TypeSessionInterrupt:
			s.interrupt(ctx, w, f.SessionID, deviceName)
		case proto.TypeSessionKey:
			s.sendKey(ctx, w, f.SessionID, f.Key, deviceName)
		case proto.TypeSessionAnswer:
			// Off the read loop: it waits between presses for the picker to redraw.
			go s.answer(ctx, w, f.SessionID, f.Option, f.Text, f.Advance, f.Submit, deviceName)
		case proto.TypeImageGet:
			s.serveImage(ctx, w, f.SessionID, f.Ref)
		case proto.TypeCaptureStill:
			s.captureStill(ctx, w, deviceName, f.Live)
		case proto.TypeCaptureClip:
			s.captureClip(ctx, w, f.Seconds, deviceName)
		case proto.TypeClientReport:
			s.clientReport(f.Text, deviceName)
		case proto.TypeChangesGet:
			go s.changes(ctx, w, f.SessionID) // git on a big tree can take a moment
		case proto.TypeFilesGet:
			go s.files(ctx, w, f.SessionID, f.Query) // the first listing of a big tree can too
		case proto.TypePreviewList:
			s.previewList(ctx, w)
		case proto.TypePreviewOpen:
			s.previewOpen(ctx, w, f.Port, deviceName)
		case proto.TypePreviewClose:
			s.previewClose(ctx, w, f.Port, deviceName)
		case proto.TypeSSHKey:
			s.sshKey(ctx, w, peer, f.PublicKey, deviceName)
		}
	}
}

// keepalive pings the phone every 30s and closes the socket when a pong does not come
// back within 10s. A phone that drops off the tailnet mid-tunnel sends no close frame
// and no RST; without this the daemon would count it as connected until TCP gave up,
// and an attached tailer would keep running for nobody. ctx is the request context,
// cancelled when the read loop returns.
func keepalive(ctx context.Context, c *websocket.Conn) {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := c.Ping(pctx)
			cancel()
			if err != nil {
				_ = c.Close(websocket.StatusGoingAway, "no pong")
				return
			}
		}
	}
}

// earlier sends the 60 rows before the `before` newest ones of the attached transcript,
// so a phone can walk back through history a page at a time.
func (s *Server) earlier(ctx context.Context, w *writer, att *attachment, before int) {
	att.mu.Lock()
	id, path := att.id, att.path
	att.mu.Unlock()
	if path == "" {
		_ = w.send(ctx, proto.NewError("no_session", "attach to a session first"))
		return
	}
	res, err := transcript.ParseFile(path)
	if err != nil {
		_ = w.send(ctx, proto.NewError("no_session", "could not read the transcript"))
		return
	}
	rows := res.Rows
	end := len(rows) - before
	if end < 0 {
		end = 0
	}
	start := end - backfillRows
	if start < 0 {
		start = 0
	}
	_ = w.send(ctx, rowsFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionRows},
		SessionID: id, Rows: rows[start:end], Earlier: true, Remaining: start})
}

type startedFrame struct {
	proto.Frame
	SessionID string `json:"session_id"`
	Tmux      string `json:"tmux"`
	Pane      string `json:"pane,omitempty"` // where claude was typed; the new session will register here
	Mode      string `json:"mode"`
	Cwd       string `json:"cwd,omitempty"` // session.new: the directory it was started in
}

// startClaude runs Claude Code in the directory of a known session, from the phone: in
// that directory's tmux window if there is one, else in a new one named after the
// directory. "resume" continues this transcript, "new" starts fresh. Only directories
// that already hold a transcript are eligible - the phone names a session, never a path.
// The command is typed into the shell the way a person would, so PATH, aliases and the
// login environment are the user's own, and the window outlives Claude.
func (s *Server) startClaude(ctx context.Context, w *writer, id, mode, device string) {
	if !s.hasCap(proto.CapStart) {
		_ = w.send(ctx, proto.NewError("no_start", "tmux is not installed on the computer"))
		return
	}
	si, ok := s.findSession(id)
	if !ok {
		_ = w.send(ctx, proto.NewError("no_session", "that session is no longer here"))
		return
	}
	if si.Live {
		_ = w.send(ctx, startedFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionStarted}, SessionID: si.ID, Tmux: si.TmuxName, Pane: si.Pane, Mode: "already"})
		return
	}
	if mode != "resume" {
		mode = "new"
	}
	if st, err := os.Stat(si.Cwd); err != nil || !st.IsDir() {
		_ = w.send(ctx, proto.NewError("no_dir", "the session's directory is gone: "+si.Cwd))
		return
	}
	cctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	// Where to type. Only a plain shell sitting in the session's directory is safe to
	// type into: the pane may hold vim, an ssh session or a REPL (Live only says no
	// claude runs there), or a shell that cd'd elsewhere, where "claude --resume" would
	// not find the transcript. Anything else gets a fresh window in that directory.
	target := si.TmuxName
	var pane string
	if target == "" {
		target = tmuxNameFor(cctx, si.Cwd)
		p, err := newTmuxSession(cctx, target, si.Cwd)
		if err != nil {
			_ = w.send(ctx, proto.NewError("tmux_failed", "Couldn't start tmux on the computer: "+err.Error()))
			return
		}
		pane = p
		time.Sleep(300 * time.Millisecond) // let the shell print its prompt before we type
	} else if p, ok := shellPane(cctx, target, si.Cwd); ok {
		pane = p
	} else {
		p, err := checkPane(exec.CommandContext(cctx, "tmux", "new-window", "-t", target+":", "-c", si.Cwd, "-P", "-F", "#{pane_id}").CombinedOutput())
		if err != nil {
			_ = w.send(ctx, proto.NewError("tmux_failed", "Couldn't open a tmux window on the computer: "+err.Error()))
			return
		}
		pane = p
		time.Sleep(300 * time.Millisecond)
	}
	if pane == "" {
		pane = target
	}
	cmd := "claude"
	if mode == "resume" {
		cmd = "claude --resume " + si.ID
	}
	if err := exec.CommandContext(cctx, "tmux", "send-keys", "-t", pane, "-l", cmd).Run(); err != nil {
		_ = w.send(ctx, proto.NewError("tmux_failed", "could not type into the tmux window"))
		return
	}
	if err := exec.CommandContext(cctx, "tmux", "send-keys", "-t", pane, "Enter").Run(); err != nil {
		_ = w.send(ctx, proto.NewError("tmux_failed", "could not type into the tmux window"))
		return
	}
	s.log.Info("claude started from the phone", "session", si.Title, "mode", mode, "tmux", target, "pane", pane, "from", device)
	_ = w.send(ctx, startedFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionStarted}, SessionID: si.ID, Tmux: target, Pane: pane, Mode: mode})
}

// shellPane finds the active pane of a tmux session's current window and returns its
// id when a plain shell sits idle there in cwd. Anything else is not a place to type.
// "Idle" is checked through the process tree, because tmux reports the shell as the
// pane's command while a program the shell launched (vim under `fish -c`, a script)
// still runs in its process group.
func shellPane(ctx context.Context, sess, cwd string) (string, bool) {
	out, err := exec.CommandContext(ctx, "tmux", "list-panes", "-t", sess+":", "-F",
		"#{window_active}\t#{pane_active}\t#{pane_current_command}\t#{pane_current_path}\t#{pane_id}\t#{pane_pid}").Output()
	if err != nil {
		return "", false
	}
	pane, pid, ok := parseShellPane(string(out), cwd)
	if !ok || hasChildren(pid) {
		return "", false
	}
	return pane, true
}

// parseShellPane is shellPane without tmux: one list-panes line per pane, tab-separated
// window_active, pane_active, current command, current path, pane id, pane pid.
func parseShellPane(out, cwd string) (string, int, bool) {
	want := samePath(cwd)
	for _, l := range strings.Split(strings.TrimSpace(out), "\n") {
		p := strings.Split(l, "\t")
		if len(p) < 6 || p[0] != "1" || p[1] != "1" {
			continue
		}
		pid, err := strconv.Atoi(p[5])
		if err != nil || !isShell(p[2]) || samePath(p[3]) != want || !strings.HasPrefix(p[4], "%") {
			return "", 0, false
		}
		return p[4], pid, true
	}
	return "", 0, false
}

// hasChildren reports whether any process names pid as its parent.
func hasChildren(pid int) bool { return platform.HasChildren(pid) }

var shells = map[string]bool{"bash": true, "fish": true, "zsh": true, "sh": true, "dash": true, "ksh": true, "tcsh": true, "nu": true}

func isShell(command string) bool { return shells[strings.TrimPrefix(filepath.Base(command), "-")] }

// samePath resolves symlinks so a directory and a link to it compare equal, as
// session_path and pane_current_path may name the same place differently.
func samePath(p string) string {
	if r, err := filepath.EvalSymlinks(p); err == nil {
		return filepath.Clean(r)
	}
	return filepath.Clean(p)
}

// tmuxNameFor names a new window after the directory, avoiding a name tmux already has.
func tmuxNameFor(ctx context.Context, cwd string) string {
	base := strings.ToLower(filepath.Base(cwd))
	var b strings.Builder
	for _, r := range base {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		} else {
			b.WriteRune('-')
		}
	}
	name := strings.Trim(b.String(), "-")
	if name == "" {
		name = "claude"
	}
	taken := map[string]bool{}
	if out, err := runCmd(ctx, "tmux", "list-sessions", "-F", "#{session_name}"); err == nil {
		for _, l := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			taken[l] = true
		}
	}
	candidate := name
	for i := 2; taken[candidate]; i++ {
		candidate = fmt.Sprintf("%s-%d", name, i)
	}
	return candidate
}

func (s *Server) findSession(id string) (session.Info, bool) { return session.Resolve(id) }

// typeTarget is where a session's keystrokes go: the pane the CLI registered, else the
// tmux session (its active pane, the pre-registry guess). captureTarget is the same for
// capture-pane, where a session name wants the exact-match prefix.
func typeTarget(si session.Info) string {
	if si.Pane != "" {
		return si.Pane
	}
	return si.TmuxName
}

func captureTarget(si session.Info) string {
	if si.Pane != "" {
		return si.Pane
	}
	return "=" + si.TmuxName
}

func (s *Server) attach(ctx context.Context, w *writer, att *attachment, id string) {
	att.stop() // one session at a time per connection

	si, ok := s.findSession(id)
	if !ok {
		_ = w.send(ctx, proto.NewError("no_session", "that session is no longer here"))
		return
	}

	tl := session.NewTailer(si.Transcript)
	att.mu.Lock()
	att.id, att.path = id, si.Transcript
	att.mu.Unlock()
	// The phone keeps the id it attached with; the daemon follows this session's own
	// restart in the same pane. Another Claude started in the same directory is a
	// different session and never takes this feed over.
	tl.ShouldSwitch = func(newPath string) bool { return session.Successor(si, newPath) }
	tl.OnSwitch = func(newPath string) {
		st := tl.State()
		// The attachment now belongs to the new transcript: "earlier" must page it, and
		// its id is the new file's.
		att.mu.Lock()
		att.path = newPath
		att.id = strings.TrimSuffix(filepath.Base(newPath), ".jsonl")
		att.mu.Unlock()
		_ = w.send(ctx, rowsFrame{
			Frame:     proto.Frame{V: proto.Version, Type: proto.TypeSessionEvent},
			SessionID: id, State: &st, Synthetic: true,
			Rows: []transcript.Row{{Kind: transcript.KindEvent, Glyph: "•", Text: "Claude Code started a new session here", TS: time.Now()}},
		})
		s.log.Info("tailer switched to a new transcript", "session", si.Title, "path", newPath)
	}
	res, err := tl.Backfill(backfillRows)
	if err != nil {
		_ = w.send(ctx, proto.NewError("read_failed", err.Error()))
		return
	}
	st := tl.State()
	_ = w.send(ctx, rowsFrame{
		Frame:     proto.Frame{V: proto.Version, Type: proto.TypeSessionRows},
		SessionID: id, Rows: res.Rows, Backfill: true, Remaining: tl.Total() - len(res.Rows), Meta: &res.Meta, State: &st,
	})
	if err := tl.SeekEnd(); err != nil {
		s.log.Warn("seek failed", "err", err)
	}

	wctx, cancel := context.WithCancel(ctx)
	att.mu.Lock()
	att.cancel = cancel
	att.mu.Unlock()

	go func() {
		err := tl.Watch(wctx, func(rows []transcript.Row, state transcript.State) {
			_ = w.send(wctx, rowsFrame{
				Frame:     proto.Frame{V: proto.Version, Type: proto.TypeSessionEvent},
				SessionID: id, Rows: rows, State: &state,
			})
		})
		if err != nil && wctx.Err() == nil {
			s.log.Warn("tailer stopped", "session", id, "err", err)
		}
	}()
	if si.TmuxName != "" {
		go s.pollStatus(wctx, w, id, captureTarget(si))
	}
	go s.pollAgents(wctx, w, id, func() string { att.mu.Lock(); defer att.mu.Unlock(); return att.path })
	s.log.Info("device attached to session", "session", si.Title, "rows", len(res.Rows))
}

// agentsFrame is the attached session's subagents: what each was asked and how far it has got.
type agentsFrame struct {
	proto.Frame
	SessionID string          `json:"session_id"`
	Agents    []session.Agent `json:"agents"`
}

// pollAgents follows the attached session's subagents: their transcripts grow while the
// session's own may sit still (a background agent works on after the call returned). A
// frame goes out when anything about them changes, and once at the start if there are any.
func (s *Server) pollAgents(ctx context.Context, w *writer, id string, path func() string) {
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	var last []byte
	for {
		agents := session.Agents(path())
		b, _ := json.Marshal(agents)
		if !bytes.Equal(b, last) && (len(agents) > 0 || last != nil) {
			last = b
			_ = w.send(ctx, agentsFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionAgents}, SessionID: id, Agents: agents})
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// spinnerLine is the CLI's working line: a spinner glyph, a verb ending in an ellipsis,
// and optionally "(12s · ↑ 1.2k tokens · esc to interrupt)". The "Baked for 1s · done"
// line after a turn starts with the same glyph but has no ellipsis, so it does not match.
var spinnerLine = regexp.MustCompile(`^\s*[✻✽✶✳✢·⏺]\s+(\S[^(]*?…)\s*(?:\((.*)\))?\s*$`)

// Usage-limit lines, verbatim from the docs. Matched loosely (case-insensitive substrings)
// because the middle dots and times vary.
var limitResumeAt = regexp.MustCompile(`continuing automatically at ([0-9]{1,2}(?::[0-9]{2})?\s?[ap]m)`)

func parseLimit(line string, f *statusFrame) {
	l := strings.ToLower(line)
	switch {
	case strings.Contains(l, "usage limit reached"):
		f.LimitText = strings.TrimSpace(line)
		f.LimitWaiting = true
		if m := limitResumeAt.FindStringSubmatch(line); m != nil {
			f.LimitResumeAt = m[1]
		}
	case strings.Contains(l, "usage limit reset") && strings.Contains(l, "continuing"):
		f.LimitText = strings.TrimSpace(line)
		f.LimitWaiting = true
	case strings.Contains(l, "usage limit has reset") && strings.Contains(l, "press enter"):
		f.LimitText = strings.TrimSpace(line)
		f.LimitEnter = true
	case strings.Contains(l, "automatic continue stopped") || strings.Contains(l, "automatic continue cancelled"):
		f.LimitText = strings.TrimSpace(line)
		f.LimitStopped = true
	case strings.Contains(l, "hit your") && strings.Contains(l, "limit"):
		// "You've hit your session limit · resets 3:45pm" - the hit itself, before or
		// without a wait. Keep the first such line; the wait lines above override it.
		if f.LimitText == "" {
			f.LimitText = strings.TrimSpace(line)
		}
	}
}

// effortDefault reads the CLI's saved effort level from its settings file, at most once
// every 10 s: the screen shows the session's level only after a turn, so this is what
// the phone can say the rest of the time.
func effortDefault() string {
	effortMu.Lock()
	defer effortMu.Unlock()
	if time.Since(effortRead) < 10*time.Second {
		return effortCache
	}
	effortRead = time.Now()
	effortCache = ""
	dir := os.Getenv("CLAUDE_CONFIG_DIR")
	if dir == "" {
		home, _ := os.UserHomeDir()
		dir = filepath.Join(home, ".claude")
	}
	b, err := os.ReadFile(filepath.Join(dir, "settings.json"))
	if err != nil {
		return ""
	}
	var st struct {
		EffortLevel string `json:"effortLevel"`
	}
	if json.Unmarshal(b, &st) == nil {
		effortCache = st.EffortLevel
	}
	return effortCache
}

var (
	effortMu    sync.Mutex
	effortRead  time.Time
	effortCache string
)

// permLine is the CLI's bottom status: "⏵⏵ bypass permissions on (shift+tab to cycle)".
// Shift-Tab cycles bypass → auto → manual → accept edits → plan → bypass (2.1.270), and
// the glyph changes with the mode: ⏵⏵ for the permissive ones, ⏸ for manual and plan.
var permLine = regexp.MustCompile(`^\s*[⏵⏸]+\s+([a-z][a-z ]*?(?:on|off))\b`)

// effortLine is the CLI's effort marker above the prompt: "◉ xhigh · /effort" (a
// filled dot when the level is the default, a bullet when changed this session).
var effortLine = regexp.MustCompile(`[◉●]\s+([a-z]+)\s+·\s+/effort`)

// parseTUI reads a captured pane. Everything returned was on screen; nothing is inferred.
func parseTUI(screen string) (f statusFrame) {
	for _, line := range strings.Split(screen, "\n") {
		if m := spinnerLine.FindStringSubmatch(line); m != nil {
			f.Working = true
			f.Text = strings.TrimSpace(m[1])
			for _, part := range strings.Split(m[2], "·") {
				part = strings.TrimSpace(part)
				switch {
				case strings.HasSuffix(part, "tokens"):
					f.Tokens = strings.TrimLeft(part, "↑↓ ")
				case strings.Contains(part, "interrupt"):
					f.Interruptible = true
				case part != "" && (strings.HasSuffix(part, "s") || strings.HasSuffix(part, "m")) && part[0] >= '0' && part[0] <= '9':
					f.Elapsed = part
				}
			}
		}
		if strings.Contains(line, "esc to interrupt") {
			f.Working = true
			f.Interruptible = true
		}
		parseLimit(line, &f)
		if m := effortLine.FindStringSubmatch(line); m != nil {
			f.Effort = m[1]
		}
		if m := permLine.FindStringSubmatch(line); m != nil {
			f.PermissionMode = strings.TrimSpace(m[1])
		}
	}
	return f
}

// pollStatus reads the session's pane once a second while a phone is attached and sends
// the CLI's own status whenever it changes. One capture-pane per second is cheap; the
// alternative - inventing a "thinking" spinner from silence - would show activity that
// is not happening.
func (s *Server) pollStatus(ctx context.Context, w *writer, id, target string) {
	var last statusFrame
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for n := 0; ; n++ {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
		// A window shrunk to a line hides everything read below; checked now and then.
		if n%10 == 0 {
			ensureReadable(ctx, target, s.log)
		}
		out, err := exec.CommandContext(ctx, "tmux", "capture-pane", "-p", "-t", target).Output()
		if err != nil {
			continue
		}
		f := parseTUI(string(out))
		f.Question = parseQuestion(string(out))
		f.EffortDefault = effortDefault()
		if reflect.DeepEqual(f, last) {
			continue
		}
		last = f
		f.Frame = proto.Frame{V: proto.Version, Type: proto.TypeSessionStatus}
		f.SessionID = id
		_ = w.send(ctx, f)
	}
}

// notRunningMessage says exactly what is missing, because "no live terminal" sent people
// hunting for a network fault when the fix was to type `claude`.
func notRunningMessage(si session.Info) string {
	if si.Tmux {
		return "Claude Code isn't running in this session's tmux window. Open the terminal and start it with: claude"
	}
	return "this session has no tmux window on the computer - start one there with tmux, then claude"
}

// panelTabs is the header of the CLI's status panel (/cost, /status, /context open it).
// While it is up, typed text goes nowhere - a prompt sent from the phone would vanish.
var panelTabs = regexp.MustCompile(`\bSettings\s+Status\s+Config\s+Usage\s+Stats\b`)

// dismissPanel closes that panel with Esc before a prompt is typed. Esc is only sent
// when the panel is actually on screen: at any other time it would interrupt a turn.
func dismissPanel(ctx context.Context, si session.Info) {
	out, err := exec.CommandContext(ctx, "tmux", "capture-pane", "-p", "-t", captureTarget(si)).Output()
	if err != nil || !panelTabs.Match(out) {
		return
	}
	_ = exec.CommandContext(ctx, "tmux", "send-keys", "-t", typeTarget(si), "Escape").Run()
	time.Sleep(200 * time.Millisecond)
}

// sendKey types one named key into the session. Only keys with a documented meaning at
// the CLI's prompt are allowed: Enter ("press enter to continue" after a limit reset)
// and Escape (cancel a wait, or interrupt). Anything else is refused.
// clientReport keeps what the phone says killed it, beside the daemon's own state and
// in the log. The phone is the only witness to its own crashes, and an app that dies
// silently is undebuggable; this is the whole of the reporting, and it goes nowhere else.
func (s *Server) clientReport(text, device string) {
	text = strings.TrimSpace(text)
	if text == "" {
		return
	}
	if len(text) > 64<<10 {
		text = text[:64<<10]
	}
	dir := filepath.Join(filepath.Dir(BuildsDir()), "crashes")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		s.log.Warn("could not keep the phone's report", "err", err)
		return
	}
	name := filepath.Join(dir, time.Now().Format("2006-01-02T15-04-05")+".txt")
	if err := os.WriteFile(name, []byte("from "+device+"\n\n"+text+"\n"), 0o600); err != nil {
		s.log.Warn("could not keep the phone's report", "err", err)
		return
	}
	first := text
	if i := strings.Index(first, "\n"); i > 0 {
		first = first[:i]
	}
	s.log.Warn("the phone reported a crash", "from", device, "first_line", first, "kept", name)
}

// answer drives the CLI's question picker, as measured on 2.1.270: a digit selects a
// single-select option (and, with one question, submits at once); on a multi-select
// question a digit toggles and Right moves on; after the last question a review screen
// waits for Enter. Text is for the "Type something" option: the digit opens the field,
// the text is typed literally, Enter submits it. The phone sequences these per question;
// the daemon presses exactly what it is told, in this order: option, text, Right, Enter.
func (s *Server) answer(ctx context.Context, w *writer, id string, option int, text string, advance, submit bool, device string) {
	si, ok := s.findSession(id)
	if !ok {
		_ = w.send(ctx, proto.NewError("no_session", "that session is no longer here"))
		return
	}
	if !si.Live {
		_ = w.send(ctx, proto.NewError("not_live", notRunningMessage(si)))
		return
	}
	if option < 0 || option > 9 || (option == 0 && !advance && !submit && text == "") {
		_ = w.send(ctx, proto.NewError("bad_option", "option must be 1-9, text, or advance/submit"))
		return
	}
	cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	target := typeTarget(si)
	// Only ever press into a picker that is on screen now. A stale tap (answered at
	// the desk a moment ago) would otherwise type a digit into the prompt.
	screen, err := exec.CommandContext(cctx, "tmux", "capture-pane", "-p", "-t", captureTarget(si)).Output()
	if err != nil {
		_ = w.send(ctx, proto.NewError("send_failed", "could not read the screen"))
		return
	}
	sq := parseQuestion(string(screen))
	if sq == nil {
		_ = w.send(ctx, proto.NewError("not_asking", "Claude is not asking anything right now"))
		return
	}
	if option > 0 {
		max := 0
		for _, o := range sq.Options {
			if o.N > max {
				max = o.N
			}
		}
		if sq.Typed > max {
			max = sq.Typed
		}
		if option > max {
			_ = w.send(ctx, proto.NewError("bad_option", "that option is not on screen"))
			return
		}
	}
	press := func(key string) bool {
		if err := exec.CommandContext(cctx, "tmux", "send-keys", "-t", target, key).Run(); err != nil {
			_ = w.send(ctx, proto.NewError("send_failed", "could not press "+key))
			return false
		}
		return true
	}
	if option > 0 {
		if err := exec.CommandContext(cctx, "tmux", "send-keys", "-t", target, "-l", strconv.Itoa(option)).Run(); err != nil {
			_ = w.send(ctx, proto.NewError("send_failed", "could not press the option"))
			return
		}
	}
	if text != "" {
		time.Sleep(250 * time.Millisecond) // the field opens
		if err := exec.CommandContext(cctx, "tmux", "send-keys", "-t", target, "-l", "--", text).Run(); err != nil {
			_ = w.send(ctx, proto.NewError("send_failed", "could not type the answer"))
			return
		}
		if err := exec.CommandContext(cctx, "tmux", "send-keys", "-t", target, "Enter").Run(); err != nil {
			_ = w.send(ctx, proto.NewError("send_failed", "could not submit the answer"))
			return
		}
	}
	if advance {
		time.Sleep(150 * time.Millisecond)
		if !press("Right") {
			return
		}
	}
	if submit {
		time.Sleep(250 * time.Millisecond) // the review screen draws
		if !press("Enter") {
			return
		}
	}
	s.log.Info("question answered from the phone", "session", si.Title, "option", option, "typed", text != "", "advance", advance, "submit", submit, "from", device)
}

func (s *Server) sendKey(ctx context.Context, w *writer, id, key, device string) {
	// BTab is tmux's Shift-Tab: the CLI cycles its permission mode on it.
	if key != "Enter" && key != "Escape" && key != "BTab" {
		_ = w.send(ctx, proto.NewError("bad_key", "only Enter, Escape and BTab (Shift-Tab) can be sent this way"))
		return
	}
	si, ok := s.findSession(id)
	if !ok || !si.Live {
		_ = w.send(ctx, proto.NewError("not_live", "Claude Code isn't running in that session"))
		return
	}
	if err := exec.CommandContext(ctx, "tmux", "send-keys", "-t", typeTarget(si), key).Run(); err != nil {
		_ = w.send(ctx, proto.NewError("send_failed", "could not send "+key))
		return
	}
	s.log.Info("key sent", "session", si.Title, "key", key, "from", device)
}

// interrupt is Esc at the desk: it stops the current turn and nothing else, the same
// key a person would press.
func (s *Server) interrupt(ctx context.Context, w *writer, id, device string) {
	si, ok := s.findSession(id)
	if !ok || !si.Live {
		_ = w.send(ctx, proto.NewError("not_live", "Claude Code isn't running in that session, so there is nothing to interrupt"))
		return
	}
	if err := exec.CommandContext(ctx, "tmux", "send-keys", "-t", typeTarget(si), "Escape").Run(); err != nil {
		_ = w.send(ctx, proto.NewError("send_failed", "could not send Esc"))
		return
	}
	s.log.Info("interrupt sent", "session", si.Title, "from", device)
}

// sendPrompt types a prompt into the session's tmux window.
//
// This is deliberately the same path a person at the desk uses: the text lands in the
// running Claude TUI, so the desktop and the phone are looking at one session rather
// than two divergent ones.
//
// ref names the prompt (its own ref, or its first upload), so a refusal reaches the phone
// with the message it is about. It reports whether the prompt was typed.
func (s *Server) sendPrompt(ctx context.Context, w *writer, id, text, device, ref string) bool {
	if strings.TrimSpace(text) == "" {
		return false
	}
	si, ok := s.findSession(id)
	if !ok {
		_ = w.send(ctx, proto.NewErrorFor("no_session", "that session is no longer here", ref))
		return false
	}
	if !si.Live {
		_ = w.send(ctx, proto.NewErrorFor("not_live", notRunningMessage(si), ref))
		return false
	}

	target := typeTarget(si)
	ensureReadable(ctx, captureTarget(si), s.log)
	dismissPanel(ctx, si)
	submitted, err := typePrompt(ctx, si, text)
	if err != nil {
		_ = w.send(ctx, proto.NewErrorFor("send_failed", fmt.Sprintf("could not type into %s", target), ref))
		return false
	}
	if !submitted {
		// In the box at the desk, not sent: the phone keeps its copy, and says where this one is.
		s.log.Warn("prompt typed but not submitted", "session", si.Title, "from", device, "chars", len(text))
		_ = w.send(ctx, proto.NewErrorFor("send_failed",
			"Claude Code did not take the Enter: the message is waiting in its input box at the desk. Press Enter there, or clear the box before sending it again", ref))
		return false
	}
	s.log.Info("prompt sent", "session", si.Title, "from", device, "chars", len(text))
	return true
}

// openTerminal attaches the phone to the session's tmux window and pumps bytes.
func (s *Server) openTerminal(ctx context.Context, w *writer, att *attachment, id string, cols, rows int, device string) {
	att.closeTerm()

	si, ok := s.findSession(id)
	if !ok || si.TmuxName == "" {
		_ = w.send(ctx, proto.NewError("not_live",
			"that session has no live terminal - resume it from the phone, or start Claude Code inside tmux on the computer"))
		return
	}

	b, err := termbridge.Open(ctx, si.TmuxName, si.Window, deviceTag(device), cols, rows)
	if err != nil {
		_ = w.send(ctx, proto.NewError("pty_failed", err.Error()))
		return
	}
	att.termMu.Lock()
	att.term = b
	att.termPane = si.Pane
	att.termMu.Unlock()
	// Tell the app the real grid before any output arrives, so it sizes its emulator to
	// the desktop's window rather than clipping to the phone's viewport.
	_ = w.send(ctx, ptySizeFrame{
		Frame: proto.Frame{V: proto.Version, Type: proto.TypePTYSize},
		Cols:  b.Cols, Rows: b.Rows,
	})
	s.log.Info("terminal opened", "session", si.TmuxName, "cols", b.Cols, "rows", b.Rows)

	// The window follows the desk: when it is resized there, the phone's grid must follow,
	// or every line after the change lands in the wrong place.
	done := make(chan struct{})
	go func() {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-done:
				return
			case <-t.C:
				wc, wr := termbridge.WindowSize(ctx, b.Window())
				if wc > 0 && wr > 0 && (wc != b.Cols || wr != b.Rows) && b.SetSize(wc, wr) == nil {
					_ = w.send(ctx, ptySizeFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypePTYSize}, Cols: wc, Rows: wr})
				}
			}
		}
	}()

	go func() {
		defer close(done)
		buf := make([]byte, 8192)
		for {
			n, err := b.Read(buf)
			if n > 0 {
				_ = w.send(ctx, ptyDataFrame{
					Frame: proto.Frame{V: proto.Version, Type: proto.TypePTYData},
					Data:  base64.StdEncoding.EncodeToString(buf[:n]),
				})
			}
			if err != nil {
				_ = w.send(ctx, proto.Frame{V: proto.Version, Type: proto.TypePTYClosed})
				s.log.Info("terminal closed", "session", si.TmuxName)
				return
			}
		}
	}()
}

// ---- images ----------------------------------------------------------------------

// maxImageBytes bounds what travels to the phone for one picture.
const maxImageBytes = 8 << 20

// serveImage answers image.get. A "file:" ref must be an absolute path under the user's
// home - the phone already holds shell access, so this is a sanity bound, not a wall.
// A "<uuid>:<n>" ref is looked up in every transcript of the session's directory,
// because the feed may still show rows from a transcript a restart has superseded.
func (s *Server) serveImage(ctx context.Context, w *writer, id, ref string) {
	reply := func(media, data string) {
		_ = w.send(ctx, imageFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeImageData},
			SessionID: id, Ref: ref, Media: media, Data: data})
	}
	if strings.HasPrefix(ref, "file:") {
		path := strings.TrimPrefix(ref, "file:")
		home, _ := os.UserHomeDir()
		// Cleaned and resolved first: a path climbing out of home with ".." and a link out of home
		// would otherwise pass a check on the text alone.
		if filepath.IsAbs(path) {
			path = filepath.Clean(path)
			if real, err := filepath.EvalSymlinks(path); err == nil {
				path = real
			}
		}
		if realHome, err := filepath.EvalSymlinks(home); err == nil && home != "" {
			home = realHome
		}
		if !filepath.IsAbs(path) || home == "" || !strings.HasPrefix(path, home+"/") {
			_ = w.send(ctx, proto.NewErrorFor("bad_ref", "only files under your home can be shown", ref))
			return
		}
		media := transcript.ImageMedia(path)
		if media == "" {
			_ = w.send(ctx, proto.NewErrorFor("bad_ref", "not an image", ref))
			return
		}
		fi, err := os.Stat(path)
		if err != nil || fi.Size() > maxImageBytes {
			_ = w.send(ctx, proto.NewErrorFor("no_image", "that file is gone or too large to send", ref))
			return
		}
		b, err := os.ReadFile(path)
		if err != nil {
			_ = w.send(ctx, proto.NewErrorFor("no_image", err.Error(), ref))
			return
		}
		reply(media, base64.StdEncoding.EncodeToString(b))
		return
	}
	uuid, nStr, ok := strings.Cut(ref, ":")
	n, _ := strconv.Atoi(nStr)
	si, found := s.findSession(id)
	if !ok || !found {
		_ = w.send(ctx, proto.NewErrorFor("bad_ref", "unknown image reference", ref))
		return
	}
	files, _ := filepath.Glob(filepath.Join(filepath.Dir(si.Transcript), "*.jsonl"))
	for _, f := range files {
		media, data, err := transcript.ImageBlock(f, uuid, n)
		if err == nil {
			if len(data) > maxImageBytes*4/3 {
				_ = w.send(ctx, proto.NewErrorFor("no_image", "that image is too large to send", ref))
				return
			}
			reply(media, data)
			return
		}
	}
	_ = w.send(ctx, proto.NewErrorFor("no_image", "that image is no longer in the transcript", ref))
}

func uploadsDir() string {
	if d := os.Getenv("PORTHOLE_STATE_DIR"); d != "" {
		return filepath.Join(d, "uploads")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "porthole", "uploads")
}

// ---- capture ----------------------------------------------------------------------

// captureEnv makes sure the compositor is reachable from a user service, which is
// started before Hyprland has exported its socket name in some setups.
func captureEnv() []string {
	env := os.Environ()
	if os.Getenv("WAYLAND_DISPLAY") == "" {
		if rt := os.Getenv("XDG_RUNTIME_DIR"); rt != "" {
			if m, _ := filepath.Glob(filepath.Join(rt, "wayland-*")); len(m) > 0 {
				for _, p := range m {
					if !strings.HasSuffix(p, ".lock") && !strings.Contains(filepath.Base(p), ".") {
						env = append(env, "WAYLAND_DISPLAY="+filepath.Base(p))
						break
					}
				}
			}
		}
	}
	return env
}

// focusedOutput names the monitor the person is looking at, from Hyprland. With two
// monitors, grim would stitch both into one wide image and wf-recorder stops to ask
// which one - so both get told. Empty when the compositor cannot say.
func focusedOutput() string {
	out, err := exec.Command("hyprctl", "-j", "monitors").Output()
	if err != nil {
		return ""
	}
	var mons []struct {
		Name    string `json:"name"`
		Focused bool   `json:"focused"`
	}
	if json.Unmarshal(out, &mons) != nil {
		return ""
	}
	for _, m := range mons {
		if m.Focused {
			return m.Name
		}
	}
	if len(mons) > 0 {
		return mons[0].Name
	}
	return ""
}

// captureDir lives beside the daemon's state, not in /tmp: the user service runs with
// ProtectSystem=strict, so /tmp is read-only for it and a screenshot written there fails
// with "Read-only file system", even though the same capture works from a shell.
func captureDir() string {
	d := filepath.Join(filepath.Dir(uploadsDir()), "capture")
	_ = os.MkdirAll(d, 0o700)
	return d
}

// captureStill takes one screenshot of the desktop with grim and sends it as an image
// frame. The phone shows it as a row; nothing is written to the session.
func (s *Server) captureStill(ctx context.Context, w *writer, device string, live bool) {
	if !s.hasCap(proto.CapCapture) {
		_ = w.send(ctx, proto.NewError("no_capture", "grim is not installed on the computer"))
		return
	}
	sweepCaptures()
	cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	// An older app's live view asks for a frame every two seconds: never woken for that,
	// or the screens would power-cycle all along.
	woke := false
	if !live {
		woke = s.screens.acquire(cctx)
		defer s.screens.release()
	}
	b, media, err := still(cctx)
	if err != nil {
		s.log.Warn("screenshot failed", "from", device, "err", err)
		if cctx.Err() != nil {
			err = errors.New("the screen did not give a picture in 10 seconds (is it asleep or locked?)")
		}
		_ = w.send(ctx, proto.NewError("capture_failed", err.Error()))
		return
	}
	caption := "Screen"
	if woke {
		caption = "Screen (woke the displays for it)"
	}
	ref := fmt.Sprintf("capture:%d", time.Now().UnixMilli())
	if live {
		ref = "live" // one slot: the watch view shows the newest frame
	} else {
		s.log.Info("screenshot sent", "from", device, "bytes", len(b), "woke", woke)
	}
	_ = w.send(ctx, imageFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeImageData},
		Ref: ref, Media: media, Data: base64.StdEncoding.EncodeToString(b), Text: caption})
}

// still is one screenshot and its media type: screencapture on a Mac, grim on Wayland.
func still(ctx context.Context) ([]byte, string, error) {
	if runtime.GOOS == "darwin" {
		b, err := macStill(ctx)
		return b, "image/jpeg", err
	}
	path := filepath.Join(captureDir(), fmt.Sprintf("still-%d.png", time.Now().UnixMilli()))
	defer os.Remove(path)
	args := []string{"-t", "png", "-l", "6"}
	if o := focusedOutput(); o != "" {
		args = append(args, "-o", o)
	}
	cmd := exec.CommandContext(ctx, "grim", append(args, path)...)
	cmd.Env = captureEnv()
	if out, err := cmd.CombinedOutput(); err != nil {
		return nil, "", errors.New("grim: " + strings.TrimSpace(string(out)))
	}
	b, err := os.ReadFile(path)
	return b, "image/png", err
}

// captureClip records the desktop for a few seconds with wf-recorder and sends the mp4.
func (s *Server) captureClip(ctx context.Context, w *writer, seconds int, device string) {
	if !s.hasCap(proto.CapRecord) {
		_ = w.send(ctx, proto.NewError("no_capture", "wf-recorder is not installed on the computer"))
		return
	}
	if seconds < 2 {
		seconds = 5
	}
	if seconds > 15 {
		seconds = 15
	}
	s.screens.acquire(ctx)
	b, err := clip(ctx, seconds)
	s.screens.release()
	if err != nil || len(b) == 0 {
		msg := "the recording produced no file"
		if err != nil {
			msg = err.Error()
		}
		s.log.Warn("screen recording failed", "from", device, "err", msg)
		_ = w.send(ctx, proto.NewError("capture_failed", msg))
		return
	}
	if len(b) > 12<<20 {
		_ = w.send(ctx, proto.NewError("capture_failed", "the clip is too large to send - try fewer seconds"))
		return
	}
	s.log.Info("clip sent", "from", device, "seconds", seconds, "bytes", len(b))
	_ = w.send(ctx, imageFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeClipData},
		Ref: fmt.Sprintf("clip:%d", time.Now().UnixMilli()), Media: "video/mp4",
		Data: base64.StdEncoding.EncodeToString(b), Text: fmt.Sprintf("Screen, %ds", seconds)})
}

// clip records the screen for a few seconds: screencapture on a Mac, wf-recorder on
// Wayland.
func clip(ctx context.Context, seconds int) ([]byte, error) {
	if runtime.GOOS == "darwin" {
		return macClip(ctx, seconds)
	}
	path := filepath.Join(captureDir(), fmt.Sprintf("clip-%d.mp4", time.Now().UnixMilli()))
	defer os.Remove(path)
	args := []string{"-f", path, "-c", "libx264", "-r", "20", "-p", "crf=30", "-p", "preset=veryfast"}
	if o := focusedOutput(); o != "" {
		args = append(args, "-o", o)
	}
	cmd := exec.Command("wf-recorder", args...)
	cmd.Env = captureEnv()
	cmd.Stdin = nil // never let it wait on a question
	if err := cmd.Start(); err != nil {
		return nil, errors.New("wf-recorder: " + err.Error())
	}
	select {
	case <-time.After(time.Duration(seconds) * time.Second):
	case <-ctx.Done():
	}
	_ = cmd.Process.Signal(os.Interrupt) // wf-recorder finalises the file on SIGINT
	done := make(chan struct{})
	go func() { _ = cmd.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(8 * time.Second):
		_ = cmd.Process.Kill()
	}
	return os.ReadFile(path)
}

// sweepCaptures drops capture files older than an hour, in case a send failed midway.
func sweepCaptures() {
	entries, _ := os.ReadDir(captureDir())
	for _, e := range entries {
		if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > time.Hour {
			_ = os.Remove(filepath.Join(captureDir(), e.Name()))
		}
	}
}

func (s *Server) hasCap(c string) bool {
	for _, x := range s.caps {
		if x == c {
			return true
		}
	}
	return false
}

// deviceTag is a short, stable, tmux-safe name for a paired device, so each phone gets
// its own mirror of a window without its name showing up in tmux.
func deviceTag(device string) string {
	if device == "" {
		return ""
	}
	h := fnv.New32a()
	_, _ = h.Write([]byte(device))
	return fmt.Sprintf("%06x", h.Sum32()&0xffffff)
}
