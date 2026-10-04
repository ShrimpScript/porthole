package server

// Preview: share a dev server that is listening on the computer's loopback with the
// phone, over the tailnet, so the site under test can be opened in the phone's browser.
//
// `tailscale serve` is the obvious tool; this is done in-process instead, and on
// purpose. `serve` needs the tailnet's HTTPS feature for TLS, cannot be limited to
// paired devices, and leaves its config in tailscaled after the daemon is gone. A
// reverse proxy here binds exactly the addresses the daemon is bound to, asks WhoIs and
// the device allowlist on every connection, forwards WebSockets (dev-server HMR), and
// dies with the process.

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/platform"
	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/tailnet"
)

// previewPortBase is the first public port a share takes; the daemon itself is 8737.
const previewPortBase = 8741
const previewPortSpan = 20

// devServer is one TCP listener on loopback or the wildcard, owned by this user.
type devServer struct {
	Port    int    `json:"port"`
	Process string `json:"process"` // the executable name
	Name    string `json:"name"`    // a friendlier label from the command line, or Process
}

type previewShare struct {
	Upstream int `json:"upstream"`
	Port     int `json:"port"`
	// Host is the address the phone opens the share at: the computer's tailnet IP, which
	// needs no DNS. A browser with its own secure DNS skips Tailscale's resolver, and a
	// MagicDNS name then does not resolve at all.
	Host string `json:"host,omitempty"`
}

type previewProxy struct {
	share     previewShare
	srv       *http.Server
	listeners []net.Listener
}

type previewListFrame struct {
	proto.Frame
	Servers []devServer    `json:"servers"`
	Active  []previewShare `json:"active"`
}

type previewStateFrame struct {
	proto.Frame
	previewShare
	Open bool `json:"open"`
}

// scanDevServers lists the user's listening TCP ports on loopback or the wildcard,
// 1024 and up, with the process behind each (see platform.Listeners). Sockets of
// processes that are not ours are absent, which is the intent: the phone is offered
// what the user is running, not what the system is.
func scanDevServers(exclude map[int]bool) []devServer {
	var out []devServer
	for _, l := range platform.Listeners() {
		if exclude[l.Port] || l.Port >= platform.EphemeralFrom {
			continue // ephemeral ports are IPC (emulators, agents), never a site
		}
		if notASite(l.Process) {
			continue
		}
		out = append(out, devServer{Port: l.Port, Process: l.Process, Name: devServerName(l.Process, l.Cmdline)})
	}
	// Recognised dev servers first, then everything else the user runs, by port.
	sort.Slice(out, func(i, j int) bool {
		ki, kj := out[i].Name != out[i].Process, out[j].Name != out[j].Process
		if ki != kj {
			return ki
		}
		return out[i].Port < out[j].Port
	})
	return out
}

// notASite hides listeners that are never a page: tooling, IPC and media daemons that
// happen to own a loopback port. Anything unrecognised stays listed - the user may well
// be running a server this list has never heard of.
func notASite(process string) bool {
	p := strings.ToLower(process)
	for _, k := range []string{
		"adb", "qemu-system", "netsimd", "emulator", "discord", "steam", "spotify",
		"tailscaled", "portholed", "obs", "kdeconnect", "pipewire", "wireplumber",
		"pulseaudio", "ssh-agent", "gpg-agent", "dbus", "cups", "kwallet", "gnome-keyring",
		"syncthing", "ollama", "mpd", "chrome", "chromium", "firefox", "code", "cursor",
		"jetbrains", "idea", "gradle", "kotlin", "java", "docker", "containerd",
	} {
		if strings.HasPrefix(p, k) {
			return true
		}
	}
	return false
}

// devServerName turns "node /x/node_modules/.bin/vite --port 5173" into "vite".
func devServerName(process string, cmdline []byte) string {
	args := strings.ToLower(strings.ReplaceAll(string(cmdline), "\x00", " "))
	for _, k := range []string{
		"vite", "next", "nuxt", "astro", "expo", "storybook", "webpack", "react-scripts",
		"remix", "sveltekit", "svelte", "angular", "tauri", "parcel", "esbuild", "turbo",
		"http.server", "uvicorn", "flask", "django", "gunicorn", "hugo", "jekyll", "rails",
		"php", "live-server", "serve",
	} {
		if strings.Contains(args, k) {
			if k == "http.server" {
				return "python http.server"
			}
			return k
		}
	}
	if process == "" {
		return "server"
	}
	return process
}

func (s *Server) activePreviews() []previewShare {
	s.pmu.Lock()
	defer s.pmu.Unlock()
	out := make([]previewShare, 0, len(s.previews))
	for _, p := range s.previews {
		out = append(out, p.share)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Upstream < out[j].Upstream })
	return out
}

func (s *Server) previewExclude() map[int]bool {
	ex := map[int]bool{s.bindPort: true}
	for _, p := range s.activePreviews() {
		ex[p.Port] = true
	}
	return ex
}

func (s *Server) previewList(ctx context.Context, w *writer) {
	if !s.hasCap(proto.CapPreview) {
		_ = w.send(ctx, proto.NewError("no_preview", "this computer cannot list its listeners"))
		return
	}
	servers := scanDevServers(s.previewExclude())
	if servers == nil {
		servers = []devServer{}
	}
	s.log.Debug("dev servers listed", "count", len(servers))
	// A share whose dev server has exited is closed here, so the phone never shows a
	// share it cannot stop and the port is not held for a page that is gone.
	listening := map[int]bool{}
	for _, d := range servers {
		listening[d.Port] = true
	}
	for _, a := range s.activePreviews() {
		if !listening[a.Upstream] {
			if p := s.takePreview(a.Upstream); p != nil {
				p.close()
				s.log.Info("dev server gone, share closed", "upstream", a.Upstream, "port", a.Port)
			}
		}
	}
	_ = w.send(ctx, previewListFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypePreviewList},
		Servers: servers, Active: s.activePreviews()})
}

func (s *Server) takePreview(upstream int) *previewProxy {
	s.pmu.Lock()
	defer s.pmu.Unlock()
	p := s.previews[upstream]
	delete(s.previews, upstream)
	return p
}

func (p *previewProxy) close() {
	cctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	_ = p.srv.Shutdown(cctx)
	cancel()
	for _, l := range p.listeners {
		_ = l.Close()
	}
}

// hostsFor is every Host header a phone may legitimately send to a share on port:
// each bound address and each known name, with the port. A MagicDNS name also counts
// by its first label alone ("box" for box.tailnet.ts.net), which is how many phones
// were paired - and a share refused it as "reached by the tailnet address only".
func (s *Server) hostsFor(port int) map[string]bool {
	out := map[string]bool{}
	ps := strconv.Itoa(port)
	for _, ip := range s.bindIPs {
		out[strings.ToLower(net.JoinHostPort(ip, ps))] = true
	}
	for _, n := range s.bindNames {
		n = strings.TrimSuffix(n, ".")
		if n == "" {
			continue
		}
		out[strings.ToLower(net.JoinHostPort(n, ps))] = true
		if short, _, ok := strings.Cut(n, "."); ok && short != "" && net.ParseIP(n) == nil {
			out[strings.ToLower(net.JoinHostPort(short, ps))] = true
		}
	}
	return out
}

// shareHost is the address a share is opened at: an IPv4 tailnet address if the daemon
// has one, else its first.
func (s *Server) shareHost() string {
	usable := func(ip string, v4 bool) bool {
		p := net.ParseIP(ip)
		return p != nil && !p.IsLoopback() && (p.To4() != nil) == v4
	}
	for _, v4 := range []bool{true, false} {
		for _, ip := range s.bindIPs {
			if usable(ip, v4) {
				return ip
			}
		}
	}
	return "" // only loopback (the emulator setup): the app opens the address it knows
}

func (s *Server) previewOpen(ctx context.Context, w *writer, upstream int, device string) {
	if !s.hasCap(proto.CapPreview) {
		_ = w.send(ctx, proto.NewError("no_preview", "this computer cannot share a dev server"))
		return
	}
	if upstream < 1024 || upstream > 65535 {
		_ = w.send(ctx, proto.NewError("bad_port", "that is not a dev server port"))
		return
	}
	if len(s.bindIPs) == 0 {
		_ = w.send(ctx, proto.NewError("no_bind", "the daemon does not know its own address"))
		return
	}
	// The scan is the slow part, so it runs outside the lock; the existence check is
	// repeated under it so two opens for one port cannot both bind.
	if !s.previewHas(upstream) {
		listening := false
		for _, d := range scanDevServers(nil) {
			if d.Port == upstream {
				listening = true
				break
			}
		}
		if !listening {
			s.log.Warn("share refused: nothing listening", "upstream", upstream, "from", device)
			_ = w.send(ctx, proto.NewError("not_listening", fmt.Sprintf("nothing is listening on localhost:%d", upstream)))
			return
		}
	}

	s.pmu.Lock()
	if p, ok := s.previews[upstream]; ok {
		s.pmu.Unlock()
		_ = w.send(ctx, previewStateFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypePreviewState},
			previewShare: p.share, Open: true})
		return
	}
	var lns []net.Listener
	port := 0
	for p := previewPortBase; p < previewPortBase+previewPortSpan; p++ {
		got, err := TailnetListeners(s.bindIPs, strconv.Itoa(p))
		if err == nil {
			lns, port = got, p
			break
		}
	}
	if lns == nil {
		s.pmu.Unlock()
		s.log.Warn("share refused: no free port", "upstream", upstream, "from", device)
		_ = w.send(ctx, proto.NewError("no_port", "no free port to share on"))
		return
	}
	hosts := s.hostsFor(port)
	srv := &http.Server{
		Handler: newPreviewHandler(upstream, func(h string) bool {
			ok := hosts[strings.ToLower(h)]
			if !ok {
				s.log.Warn("share refused a foreign Host", "host", h, "port", port)
			}
			return ok
		}, s.previewAllowed),
		ReadHeaderTimeout: 10 * time.Second,
	}
	for _, ln := range lns {
		go func(l net.Listener) { _ = srv.Serve(l) }(ln)
	}
	share := previewShare{Upstream: upstream, Port: port, Host: s.shareHost()}
	s.previews[upstream] = &previewProxy{share: share, srv: srv, listeners: lns}
	s.pmu.Unlock()
	s.log.Info("dev server shared", "upstream", upstream, "port", port, "from", device)
	_ = w.send(ctx, previewStateFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypePreviewState},
		previewShare: share, Open: true})
}

func (s *Server) previewHas(upstream int) bool {
	s.pmu.Lock()
	defer s.pmu.Unlock()
	_, ok := s.previews[upstream]
	return ok
}

func (s *Server) previewClose(ctx context.Context, w *writer, upstream int, device string) {
	p := s.takePreview(upstream)
	if p == nil {
		_ = w.send(ctx, previewStateFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypePreviewState},
			previewShare: previewShare{Upstream: upstream}, Open: false})
		return
	}
	p.close()
	s.log.Info("dev server unshared", "upstream", upstream, "port", p.share.Port, "from", device)
	_ = w.send(ctx, previewStateFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypePreviewState},
		previewShare: p.share, Open: false})
}

// previewAllowed is the same two gates the WebSocket uses. Only the identity (address ->
// node) is cached, for a minute, so a page's forty assets cost one WhoIs; the allowlist
// is consulted on every request, so a revoke is honoured by the very next one. A WhoIs
// failure is remembered for five seconds - long enough not to hammer tailscaled, short
// enough that a hiccup does not blank a page for a minute.
func (s *Server) previewAllowed(r *http.Request) bool {
	return gateRequest(r, previewIdentity, s.res.WhoIs, func(nodeID string) bool {
		_, ok := s.st.Allowed(nodeID)
		return ok
	}, s.log)
}

func gateRequest(r *http.Request, ids *identityCache, whois func(context.Context, string) (*tailnet.Peer, error),
	allowed func(string) bool, log *slog.Logger) bool {
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	nodeID, known := ids.lookup(ip, time.Now())
	if !known {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		peer, err := whois(ctx, r.RemoteAddr)
		cancel()
		if err != nil {
			ids.put(ip, "", 5*time.Second)
			if log != nil {
				log.Warn("preview refused", "addr", r.RemoteAddr, "err", err)
			}
			return false
		}
		nodeID = peer.NodeID
		ids.put(ip, nodeID, time.Minute)
	}
	return nodeID != "" && allowed(nodeID)
}

type identityCache struct {
	mu sync.Mutex
	m  map[string]idEntry
}

type idEntry struct {
	nodeID string // empty: the last lookup failed
	until  time.Time
}

func (c *identityCache) lookup(ip string, now time.Time) (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[ip]
	if !ok || now.After(e.until) {
		return "", false
	}
	return e.nodeID, true
}

func (c *identityCache) put(ip, nodeID string, ttl time.Duration) {
	c.mu.Lock()
	c.m[ip] = idEntry{nodeID: nodeID, until: time.Now().Add(ttl)}
	c.mu.Unlock()
}

// reset forgets every cached identity. Called on revoke, as belt and braces.
func (c *identityCache) reset() {
	c.mu.Lock()
	c.m = map[string]idEntry{}
	c.mu.Unlock()
}

var previewIdentity = &identityCache{m: map[string]idEntry{}}

// newPreviewHandler proxies to localhost:<upstream>. Two checks come first. The Host
// header must name this machine on the share's port: a page in the phone's browser
// could otherwise rebind its own hostname to this address and read the dev server as
// its own origin, and the upstream would never know because the proxy presents Host as
// localhost (which Vite and friends insist on). Then the caller must be a paired
// device. Bytes are flushed as they arrive so HMR event streams and WebSockets work.
func newPreviewHandler(upstream int, hostOK func(string) bool, allow func(*http.Request) bool) http.Handler {
	target := &url.URL{Scheme: "http", Host: net.JoinHostPort("127.0.0.1", strconv.Itoa(upstream))}
	rp := &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(target)
			pr.Out.Host = target.Host
			pr.SetXForwarded()
		},
		FlushInterval: -1,
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !hostOK(r.Host) {
			http.Error(w, "this share is reached by the computer's tailnet address only", http.StatusMisdirectedRequest)
			return
		}
		if !allow(r) {
			http.Error(w, "this device is not paired with portholed", http.StatusForbidden)
			return
		}
		rp.ServeHTTP(w, r)
	})
}
