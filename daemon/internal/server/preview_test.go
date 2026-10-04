package server

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/tailnet"
)

func TestScanFindsOwnListener(t *testing.T) {
	if os.Getenv("SCAN_DUMP") != "" { // a way to see the live scan: SCAN_DUMP=1 go test -run Scan -v
		for _, d := range scanDevServers(nil) {
			t.Logf("%+v", d)
		}
	}
	// A fixed port: the scan hides the ephemeral range, where port 0 would land.
	var ln net.Listener
	var err error
	for p := 18080; p < 18100 && ln == nil; p++ {
		ln, err = net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p))
	}
	if ln == nil {
		t.Skip("no loopback port free:", err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port
	found := false
	for _, d := range scanDevServers(nil) {
		if d.Port == port {
			found = true
			if d.Process == "" || d.Name == "" {
				t.Errorf("listener %d has no process name: %+v", port, d)
			}
		}
	}
	if !found {
		t.Fatalf("scan did not list our own listener on %d", port)
	}
	for _, d := range scanDevServers(map[int]bool{port: true}) {
		if d.Port == port {
			t.Fatalf("excluded port %d still listed", port)
		}
	}
}

func TestProxyPresentsLocalhostAndGates(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, r.Host+" "+r.Header.Get("X-Forwarded-Host"))
	}))
	defer up.Close()
	_, portStr, _ := net.SplitHostPort(up.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)

	allow := true
	hostOK := true
	front := httptest.NewServer(newPreviewHandler(port,
		func(string) bool { return hostOK }, func(*http.Request) bool { return allow }))
	defer front.Close()

	res, err := http.Get(front.URL + "/x")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	frontHost := front.Listener.Addr().String()
	if want := "127.0.0.1:" + portStr + " " + frontHost; string(body) != want {
		t.Fatalf("upstream saw %q, want %q", body, want)
	}

	allow = false
	res, err = http.Get(front.URL + "/x")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusForbidden {
		t.Fatalf("unpaired caller got %d, want 403", res.StatusCode)
	}

	// A Host that is not this machine (a rebinding page's own name) is turned away
	// before the device gate even runs.
	allow = true
	hostOK = false
	res, err = http.Get(front.URL + "/x")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusMisdirectedRequest {
		t.Fatalf("foreign Host got %d, want 421", res.StatusCode)
	}
}

func TestHostsForNamesEveryBindAndName(t *testing.T) {
	s := &Server{}
	s.SetBind([]string{"192.0.2.10", "2001:db8::10"}, 8737, []string{"box.example.ts.net."})
	hosts := s.hostsFor(8741)
	for _, want := range []string{"192.0.2.10:8741", "[2001:db8::10]:8741", "box.example.ts.net:8741", "box:8741"} {
		if !hosts[want] {
			t.Errorf("%q not accepted: %v", want, hosts)
		}
	}
	if hosts["evil.example:8741"] || hosts["192.0.2.10:8737"] || hosts["192:8741"] {
		t.Errorf("foreign host or wrong port accepted: %v", hosts)
	}
	if got := s.shareHost(); got != "192.0.2.10" {
		t.Errorf("share host %q, want the IPv4 address", got)
	}
}

// The gate asks WhoIs once per address and the allowlist every time, so a revoke is
// honoured by the very next request without any cache reset.
func TestGateRechecksAllowlistEveryRequest(t *testing.T) {
	ids := &identityCache{m: map[string]idEntry{}}
	calls := 0
	whois := func(_ context.Context, addr string) (*tailnet.Peer, error) {
		calls++
		return &tailnet.Peer{NodeID: "n1"}, nil
	}
	allowed := true
	allow := func(nodeID string) bool { return nodeID == "n1" && allowed }
	req := httptest.NewRequest("GET", "/", nil)
	req.RemoteAddr = "192.0.2.9:40000"

	if !gateRequest(req, ids, whois, allow, nil) {
		t.Fatal("paired device refused")
	}
	if !gateRequest(req, ids, whois, allow, nil) {
		t.Fatal("paired device refused on second request")
	}
	if calls != 1 {
		t.Fatalf("WhoIs called %d times, want 1 (cached)", calls)
	}
	allowed = false // revoked
	if gateRequest(req, ids, whois, allow, nil) {
		t.Fatal("revoked device still allowed")
	}
	if calls != 1 {
		t.Fatalf("WhoIs re-asked after revoke: %d calls", calls)
	}
}

func TestGateForgetsAFailedLookupQuickly(t *testing.T) {
	ids := &identityCache{m: map[string]idEntry{}}
	fail := true
	whois := func(_ context.Context, addr string) (*tailnet.Peer, error) {
		if fail {
			return nil, io.EOF
		}
		return &tailnet.Peer{NodeID: "n1"}, nil
	}
	req := httptest.NewRequest("GET", "/", nil)
	req.RemoteAddr = "192.0.2.9:40000"
	if gateRequest(req, ids, whois, func(string) bool { return true }, nil) {
		t.Fatal("unknown caller allowed")
	}
	if _, known := ids.lookup("192.0.2.9", time.Now().Add(6*time.Second)); known {
		t.Fatal("a failed lookup was remembered for more than five seconds")
	}
}

func TestResetForgetsIdentities(t *testing.T) {
	ids := &identityCache{m: map[string]idEntry{}}
	ids.put("192.0.2.9", "n1", time.Minute)
	ids.reset()
	if _, known := ids.lookup("192.0.2.9", time.Now()); known {
		t.Fatal("identity survived reset")
	}
}

func TestNotASite(t *testing.T) {
	for _, hide := range []string{"adb", "qemu-system-x86", "netsimd", "Discord", "java"} {
		if !notASite(hide) {
			t.Errorf("%q should be hidden", hide)
		}
	}
	for _, show := range []string{"node", "python3", "vite", "my-api", "go", "cargo", "bun"} {
		if notASite(show) {
			t.Errorf("%q should be listed", show)
		}
	}
}

func TestTmuxNameForIsASafeSlug(t *testing.T) {
	for in, want := range map[string]string{"/srv/p/My App!": "my-app", "/srv/p/porthole": "porthole", "/srv/p/---": "claude"} {
		if got := tmuxNameFor(context.Background(), in); got != want && !strings.HasPrefix(got, want+"-") {
			t.Errorf("%q -> %q, want %q", in, got, want)
		}
	}
}

func TestDevServerName(t *testing.T) {
	cases := map[string]string{
		"node\x00/p/node_modules/.bin/vite\x00--port\x005173": "vite",
		"python3\x00-m\x00http.server\x008000":                "python http.server",
		"my-api\x00--listen":                                  "my-api",
	}
	for cmd, want := range cases {
		proc := "my-api"
		if got := devServerName(proc, []byte(cmd)); got != want {
			t.Errorf("%q -> %q, want %q", cmd, got, want)
		}
	}
}

func TestParseShellPane(t *testing.T) {
	dir := t.TempDir()
	other := t.TempDir()
	cases := []struct {
		name string
		out  string
		want string
		pid  int
		ok   bool
	}{
		{"shell at cwd", "1\t1\tfish\t" + dir + "\t%3\t41\n", "%3", 41, true},
		{"login shell", "1\t1\t-bash\t" + dir + "\t%3\t41\n", "%3", 41, true},
		{"vim in the pane", "1\t1\tvim\t" + dir + "\t%3\t41\n", "", 0, false},
		{"ssh in the pane", "1\t1\tssh\t" + dir + "\t%3\t41\n", "", 0, false},
		{"shell elsewhere", "1\t1\tbash\t" + other + "\t%3\t41\n", "", 0, false},
		{"inactive pane ignored, active one taken", "1\t0\tvim\t" + dir + "\t%1\t7\n1\t1\tzsh\t" + dir + "\t%2\t9\n", "%2", 9, true},
		{"no active pane", "0\t0\tbash\t" + dir + "\t%1\t41\n", "", 0, false},
		{"empty", "", "", 0, false},
	}
	for _, c := range cases {
		got, pid, ok := parseShellPane(c.out, dir)
		if got != c.want || pid != c.pid || ok != c.ok {
			t.Errorf("%s: got %q,%d,%v want %q,%d,%v", c.name, got, pid, ok, c.want, c.pid, c.ok)
		}
	}
}

func TestHasChildren(t *testing.T) {
	// Processes made for the test, not the test binary itself: other tests may leave it
	// with children of its own for a moment.
	start := func(args ...string) *exec.Cmd {
		cmd := exec.Command(args[0], args[1:]...)
		if err := cmd.Start(); err != nil {
			t.Skip("cannot start", args[0], err)
		}
		t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
		return cmd
	}
	lone := start("sleep", "30")
	if hasChildren(lone.Process.Pid) {
		t.Fatal("a process with no children was said to have some")
	}
	parent := start("sh", "-c", "sleep 30 & wait")
	for i := 0; i < 50 && !hasChildren(parent.Process.Pid); i++ {
		time.Sleep(50 * time.Millisecond)
	}
	if !hasChildren(parent.Process.Pid) {
		t.Fatal("a running child was not seen")
	}
}
