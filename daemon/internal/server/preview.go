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
	"bufio"
	"context"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

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

// scanDevServers lists listening TCP sockets on loopback or the wildcard with a port of
// 1024 or more, resolved to the owning process through /proc. Sockets of processes
// that are not ours are unreadable and therefore absent, which is the intent: the
// phone is offered what the user is running, not what the system is.
func scanDevServers(exclude map[int]bool) []devServer {
	portByInode := map[uint64]int{}
	for _, f := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		readProcNet(f, portByInode)
	}
	if len(portByInode) == 0 {
		return nil
	}
	pidByPort := map[int]int{}
	procs, _ := os.ReadDir("/proc")
	for _, e := range procs {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		fds, err := os.ReadDir(fmt.Sprintf("/proc/%d/fd", pid))
		if err != nil {
			continue // another user's process
		}
		for _, fd := range fds {
			link, err := os.Readlink(fmt.Sprintf("/proc/%d/fd/%s", pid, fd.Name()))
			if err != nil || !strings.HasPrefix(link, "socket:[") {
				continue
			}
			ino, err := strconv.ParseUint(strings.TrimSuffix(strings.TrimPrefix(link, "socket:["), "]"), 10, 64)
			if err != nil {
				continue
			}
			if port, ok := portByInode[ino]; ok {
				if _, seen := pidByPort[port]; !seen {
					pidByPort[port] = pid
				}
			}
		}
	}
	var out []devServer
	for port, pid := range pidByPort {
		if exclude[port] || port >= ephemeralFrom {
			continue // ephemeral ports are IPC (emulators, agents), never a site
		}
		comm, _ := os.ReadFile(fmt.Sprintf("/proc/%d/comm", pid))
		cmdline, _ := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
		process := strings.TrimSpace(string(comm))
		if notASite(process) {
			continue
		}
		out = append(out, devServer{Port: port, Process: process, Name: devServerName(process, cmdline)})
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

// ephemeralFrom is where Linux hands out ports; a listener up there was not chosen.
const ephemeralFrom = 32768

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

// readProcNet collects LISTEN sockets on loopback/wildcard from one /proc/net table.
func readProcNet(path string, portByInode map[uint64]int) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Scan() // header
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 10 || fields[3] != "0A" { // 0A = LISTEN
			continue
		}
		hostHex, portHex, ok := strings.Cut(fields[1], ":")
		if !ok || !localOrAny(hostHex) {
			continue
		}
		port64, err := strconv.ParseUint(portHex, 16, 16)
		if err != nil || port64 < 1024 {
			continue
		}
		ino, err := strconv.ParseUint(fields[9], 10, 64)
		if err != nil {
			continue
		}
		portByInode[ino] = int(port64)
	}
}

// localOrAny: /proc writes addresses as little-endian hex words. 127.0.0.1 is
// 0100007F, ::1 ends in 01000000, and any-address is all zeros in either width.
func localOrAny(h string) bool {
	b, err := hex.DecodeString(h)
	if err != nil {
		return false
	}
	switch len(b) {
	case 4:
		return (b[3] == 127) || (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0)
	case 16:
		allZero := true
		for i := 0; i < 15; i++ {
			if b[i] != 0 {
				allZero = false
				break
			}
		}
		return allZero && (b[15] == 0 || b[12] == 1 && b[13] == 0 && b[14] == 0 && b[15] == 0) || isProcV6Loopback(b)
	}
	return false
}

func isProcV6Loopback(b []byte) bool {
	// ::1 as /proc prints it: three zero words then 01000000.
	for i := 0; i < 12; i++ {
		if b[i] != 0 {
			return false
		}
	}
	return b[12] == 1 && b[13] == 0 && b[14] == 0 && b[15] == 0
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
// each bound address and each known name, with the port.
func (s *Server) hostsFor(port int) map[string]bool {
	out := map[string]bool{}
	ps := strconv.Itoa(port)
	for _, ip := range s.bindIPs {
		out[strings.ToLower(net.JoinHostPort(ip, ps))] = true
	}
	for _, n := range s.bindNames {
		if n != "" {
			out[strings.ToLower(net.JoinHostPort(strings.TrimSuffix(n, "."), ps))] = true
		}
	}
	return out
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
		_ = w.send(ctx, proto.NewError("no_port", "no free port to share on"))
		return
	}
	hosts := s.hostsFor(port)
	srv := &http.Server{
		Handler:           newPreviewHandler(upstream, func(h string) bool { return hosts[strings.ToLower(h)] }, s.previewAllowed),
		ReadHeaderTimeout: 10 * time.Second,
	}
	for _, ln := range lns {
		go func(l net.Listener) { _ = srv.Serve(l) }(ln)
	}
	share := previewShare{Upstream: upstream, Port: port}
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
