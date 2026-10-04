// Package server is the tailnet-facing side of portholed.
//
// Access control is two gates, and both must pass on every connection:
//  1. WhoIs resolves the remote address to a tailnet node. No resolution, no entry.
//  2. That node is on the device allowlist, or presents a valid pairing code.
package server

import (
	"context"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/user"
	"runtime"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/shrimpscript/porthole/daemon/internal/platform"
	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/service"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/sshkeys"
	"github.com/shrimpscript/porthole/daemon/internal/store"
	"github.com/shrimpscript/porthole/daemon/internal/tailnet"
)

// sessionListFrame keeps proto free of a dependency on the session package.
type sessionListFrame struct {
	proto.Frame
	Sessions []session.Info `json:"sessions"`
}

const Version = "0.39.1"

type Server struct {
	res  tailnet.Resolver
	st   *store.Store
	pair pairing
	log  *slog.Logger
	caps []string

	mu    sync.Mutex
	conns map[string]map[*websocket.Conn]struct{} // nodeID -> live sockets
	awake *keepAwake
	// authorizedKeys is the file failsafe keys go in; tests point it elsewhere.
	authorizedKeys string
	// newPanes are the panes started for a phone that are waiting on the trust prompt.
	newPanes startedPanes
	writers  map[*websocket.Conn]*writer // serialised writer per socket

	approvals *approvals

	// Where the daemon is reachable, so a preview proxy binds exactly the same
	// addresses (tailnet only, or the dev loopback) and never anything wider.
	bindIPs   []string
	bindNames []string // hostnames a phone may use for this machine (MagicDNS; dev aliases)
	screens   waker    // wakes sleeping screens for captures, shared by every phone
	bindPort  int
	pmu       sync.Mutex
	previews  map[int]*previewProxy // upstream port -> the share

	// What each live session's screen is asking right now, from the turn watcher's
	// scan of its pane (the transcript only records a question once answered).
	askMu       sync.Mutex
	asking      map[string]string                                     // session id -> question text ("" when none; absent when unscanned)
	changesBusy map[string]bool                                       // session id -> a changes.get is running
	filesMu     sync.Mutex                                            // one directory listing at a time, for files.get
	filesCache  map[string]*fileList                                  // directory -> its files, for a few seconds
	listFiles   func(context.Context, string) ([]string, bool, error) // nil: the real listing
}

// screenAsking is the question the session's pane showed on the last scan, and whether
// the pane has been scanned at all.
func (s *Server) screenAsking(id string) (string, bool) {
	s.askMu.Lock()
	defer s.askMu.Unlock()
	q, ok := s.asking[id]
	return q, ok
}

func (s *Server) setScreenAsking(id, q string) (changed bool) {
	s.askMu.Lock()
	defer s.askMu.Unlock()
	if s.asking == nil {
		s.asking = map[string]string{}
	}
	prev, had := s.asking[id]
	s.asking[id] = q
	return !had || prev != q
}

func (s *Server) forgetScreenAsking(id string) {
	s.askMu.Lock()
	defer s.askMu.Unlock()
	delete(s.asking, id)
}

// overlayAsking puts the screen's question on list entries, where the transcript's
// (later, or never while the picker is up) would leave "Needs you" dark.
func (s *Server) overlayAsking(list []session.Info) {
	for i := range list {
		if q, ok := s.screenAsking(list[i].ID); ok && list[i].Live {
			list[i].Asking = q
		}
	}
}

// SetBind records the addresses the daemon itself was bound to, and the names a phone
// may legitimately put in a Host header for them. Preview proxies reuse both, which is
// what keeps "tailnet only" true for every listener the daemon opens.
func (s *Server) SetBind(ips []string, port int, names []string) {
	s.bindIPs = append([]string(nil), ips...)
	s.bindNames = append([]string(nil), names...)
	s.bindPort = port
}

func New(res tailnet.Resolver, st *store.Store, log *slog.Logger) *Server {
	return &Server{
		res: res,
		st:  st,
		log: log,
		// Only what the daemon can actually honour - the app reveals UI from this.
		caps:           detectCaps(),
		conns:          map[string]map[*websocket.Conn]struct{}{},
		awake:          newKeepAwake(),
		authorizedKeys: sshkeys.Path(),
		writers:        map[*websocket.Conn]*writer{},
		approvals:      newApprovals(),
		previews:       map[int]*previewProxy{},
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	mux.HandleFunc("/builds/", s.handleBuilds)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		// Plain "ok" for humans and older apps; caps so the consent screen can list
		// what this daemon can actually do before pairing.
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "version": Version, "caps": s.caps})
	})
	return mux
}

// BeginPair issues a pairing code (called from the control socket by `portholed pair`).
func (s *Server) BeginPair() (string, time.Time, error) { return s.pair.Begin() }

func (s *Server) Devices() []*store.Device { return s.st.List() }

// Revoke removes a device and closes its live sockets immediately. Revocation that only
// takes effect on the next connection is not revocation.
func (s *Server) Revoke(nodeID string) (bool, error) {
	// The preview gate consults the allowlist on every request, so revocation bites at
	// once; forgetting cached identities as well is belt and braces.
	previewIdentity.reset()
	// The failsafe key goes first and whatever the store says: a key must never outlive
	// the pairing it came with.
	if removed, err := sshkeys.Remove(s.authorizedKeys, nodeID); err != nil {
		s.log.Warn("could not remove the failsafe key", "node", nodeID, "err", err)
	} else if removed {
		s.log.Info("failsafe key removed", "node", nodeID)
	}
	ok, err := s.st.Revoke(nodeID)
	if err != nil || !ok {
		return ok, err
	}
	s.mu.Lock()
	live := s.conns[nodeID]
	delete(s.conns, nodeID)

	// Deliberately NOT websocket.Close: that waits for the peer's close-handshake reply,
	// up to five seconds per socket, so revoking a device with a phone in a tunnel would
	// hang `portholed revoke`. Revocation has to be instant, so send the reason on a short
	// budget and then drop the connection without negotiating.
	revoked := proto.NewError(proto.ErrRevoked, "this device was revoked")
	b, _ := json.Marshal(revoked)
	for c := range live {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		_ = c.Write(ctx, websocket.MessageText, b)
		cancel()
		delete(s.writers, c)
		_ = c.CloseNow()
	}
	s.mu.Unlock()
	s.log.Info("device revoked", "node", nodeID, "closed_sockets", len(live))
	return true, nil
}

func (s *Server) track(nodeID string, c *websocket.Conn, w *writer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.conns[nodeID] == nil {
		s.conns[nodeID] = map[*websocket.Conn]struct{}{}
	}
	s.conns[nodeID][c] = struct{}{}
	s.writers[c] = w
	s.awake.phone(+1)
}

func (s *Server) untrack(nodeID string, c *websocket.Conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.writers[c]; ok {
		s.awake.phone(-1)
	}
	delete(s.writers, c)
	if m := s.conns[nodeID]; m != nil {
		delete(m, c)
		if len(m) == 0 {
			delete(s.conns, nodeID)
		}
	}
}

// LiveCount is used by tests and `portholed status`.
func (s *Server) LiveCount(nodeID string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.conns[nodeID])
}

func refuse(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(proto.NewError(code, msg))
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	// Gate 0: no browsers. Both gates below are device-level, and a browser on a paired
	// device IS that device - so a malicious web page open on the phone could otherwise
	// drive this daemon (list sessions, open a shell, approve tool calls) purely from
	// script. That is cross-site WebSocket hijacking. The native client is not a browser
	// and sends no Origin (verified: okhttp sends none); a present Origin means a browser,
	// which has no legitimate reason to reach this socket.
	if origin := r.Header.Get("Origin"); origin != "" {
		s.log.Warn("rejected browser origin", "origin", origin, "addr", r.RemoteAddr)
		refuse(w, http.StatusForbidden, proto.ErrForbidden, "browser clients are not allowed")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	peer, err := s.res.WhoIs(ctx, r.RemoteAddr)
	cancel()
	if err != nil {
		// Gate 1. Not a tailnet peer we can name: refuse without saying why in detail.
		s.log.Warn("whois failed", "addr", r.RemoteAddr, "err", err)
		refuse(w, http.StatusForbidden, proto.ErrNotTailnet,
			"not a recognised device on this tailnet")
		return
	}

	dev, allowed := s.st.Allowed(peer.NodeID)
	if !allowed {
		// Gate 2. Unpaired: the only way through is a valid code shown on this machine.
		code := r.URL.Query().Get("code")
		if code == "" {
			s.log.Info("refused unpaired device", "node", peer.ShortName())
			refuse(w, http.StatusForbidden, proto.ErrNotPaired,
				"this device is not paired; run `portholed pair` on the computer")
			return
		}
		if !s.pair.Redeem(code) {
			s.log.Warn("bad pairing code", "node", peer.ShortName())
			refuse(w, http.StatusForbidden, proto.ErrBadCode, "that code is wrong or expired")
			return
		}
		dev = &store.Device{
			NodeID: peer.NodeID,
			Name:   peer.ShortName(),
			User:   peer.UserLogin,
			Scopes: []string{"sessions"},
		}
		if err := s.st.Add(dev); err != nil {
			s.log.Error("could not persist device", "err", err)
			refuse(w, http.StatusInternalServerError, "store", "could not save the pairing")
			return
		}
		s.log.Info("device paired", "node", dev.Name, "user", dev.User)
	}

	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// The library's same-origin check is skipped because Gate 0 above already rejected
		// every request carrying an Origin, which is a stricter rule than host-matching:
		// no browser reaches this line at all.
		InsecureSkipVerify: true,
	})
	if err != nil {
		s.log.Warn("websocket accept failed", "err", err)
		return
	}
	// A prompt with a photo or a file in it is megabytes; past the limit the library
	// closes the connection, so the limit is what the phone may send, not its 32 KB.
	c.SetReadLimit(maxFrameBytes)
	wr := &writer{c: c}
	s.track(peer.NodeID, c, wr)
	s.st.Touch(peer.NodeID)
	defer func() {
		s.untrack(peer.NodeID, c)
		_ = c.CloseNow()
	}()

	host, _ := os.Hostname()
	sshUser := ""
	if u, err := user.Current(); err == nil {
		sshUser = u.Username
	}
	hello := proto.NewHello(Version, host, runtime.GOOS, dev.Name, sshUser, s.caps)
	hello.Restart = service.RestartCommand()
	hello.Failsafe = s.failsafe(r.Context(), peer.NodeID)
	hello.SSHServer = sshListening()
	hello.AutoContinue = autoContinueSetting()
	if b := latestBuild(BuildsDir(), DefaultApp); b != nil {
		hello.LatestBuild = &proto.BuildInfo{App: b.App, Version: b.Version, Path: "/builds/" + b.File, Size: b.Size, Code: b.Code}
	}
	hello.Builds = buildInfos(newestBuilds(BuildsDir()))
	if err := wr.send(r.Context(), hello); err != nil {
		return
	}
	s.log.Info("device connected", "node", dev.Name, "addr", peer.Addr)

	// The sessions list is the first useful thing the app shows, so send it immediately
	// rather than waiting to be asked.
	if err := s.writeSessionList(r.Context(), c); err != nil {
		s.log.Warn("could not send session list", "err", err)
		return
	}

	s.serveClient(r.Context(), wr, dev.Name, peer)
}

// writeSessionList is only called before the pump starts, or from the pump's own
// goroutine, so it may write directly.
func (s *Server) writeSessionList(ctx context.Context, c *websocket.Conn) error {
	list, err := session.List()
	if err != nil {
		return err
	}
	s.overlayAsking(list)
	f := sessionListFrame{
		Frame:    proto.Frame{V: proto.Version, Type: proto.TypeSessionList},
		Sessions: list,
	}
	b, err := json.Marshal(f)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return c.Write(wctx, websocket.MessageText, b)
}

// detectCaps advertises only what the machine can do. Capture needs grim (Wayland
// stills) and recording wf-recorder, or on a Mac the built-in screencapture. Uploads need nothing beyond a writable home.
func detectCaps() []string {
	caps := []string{proto.CapSessions, proto.CapPrompt, proto.CapApprovals, proto.CapUpload, proto.CapAttach, proto.CapSSHKey, proto.CapPromptAck}
	if runtime.GOOS == "darwin" {
		// screencapture ships with macOS and does both.
		if _, err := exec.LookPath("screencapture"); err == nil {
			caps = append(caps, proto.CapCapture, proto.CapRecord)
		}
	} else {
		if _, err := exec.LookPath("grim"); err == nil {
			caps = append(caps, proto.CapCapture)
		}
		if _, err := exec.LookPath("wf-recorder"); err == nil {
			caps = append(caps, proto.CapRecord)
		}
	}
	if platform.CanListListeners() {
		caps = append(caps, proto.CapPreview) // finding dev servers maps sockets to processes
	}
	if gitUsable() {
		caps = append(caps, proto.CapChanges)
	}
	caps = append(caps, proto.CapFiles) // git's list in a repository, a walk outside one
	if _, err := exec.LookPath("tmux"); err == nil {
		caps = append(caps, proto.CapStart) // starting Claude needs a tmux to put it in
	}
	return caps
}

// autoContinueSetting reads Claude Code's autoContinueAtUsageLimit from the user's
// settings file. Read-only: the daemon never writes settings.json. Absent means the
// CLI default, which is on for a claude.ai subscription.
func autoContinueSetting() bool {
	dir := os.Getenv("CLAUDE_CONFIG_DIR")
	if dir == "" {
		home, _ := os.UserHomeDir()
		dir = home + "/.claude"
	}
	b, err := os.ReadFile(dir + "/settings.json")
	if err != nil {
		return true
	}
	var m map[string]any
	if json.Unmarshal(b, &m) != nil {
		return true
	}
	if v, ok := m["autoContinueAtUsageLimit"].(bool); ok {
		return v
	}
	return true
}

// TailnetListeners binds every tailnet address and nothing else. A LAN or wildcard bind
// is a release blocker, so this never takes a host from configuration.
func TailnetListeners(ips []string, port string) ([]net.Listener, error) {
	var out []net.Listener
	for _, ip := range ips {
		addr := net.JoinHostPort(ip, port)
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			for _, l := range out {
				_ = l.Close()
			}
			return nil, err
		}
		out = append(out, ln)
	}
	return out, nil
}
