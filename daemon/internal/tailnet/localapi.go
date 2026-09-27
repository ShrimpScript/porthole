// Package tailnet talks to the local Tailscale over its LocalAPI.
//
// Deliberately no dependency on the Tailscale Go module: the LocalAPI is plain HTTP, and
// speaking it directly keeps portholed a small static binary with no vendored networking
// stack. It is reached one of three ways: a unix socket (tailscaled on Linux, and the
// open-source tailscaled on a Mac), or - for the Tailscale app on a Mac, which runs in a
// sandbox - a localhost TCP port with a token, found the way Tailscale's own CLI finds it.
package tailnet

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Socket paths, in the order tailscaled is known to use them. The last is the
// open-source tailscaled on a Mac (Homebrew's tailscale).
var socketPaths = []string{
	"/var/run/tailscale/tailscaled.sock",
	"/run/tailscale/tailscaled.sock",
	"/var/run/tailscaled.socket",
}

type Client struct {
	http *http.Client
	// token, for the Mac app's TCP LocalAPI: sent as the Basic auth password.
	token func() string
}

// Peer is the subset of a WhoIs response Porthole actually uses.
type Peer struct {
	NodeID    string // StableID - survives IP changes, so this is what the allowlist keys on
	Name      string // MagicDNS name, e.g. phone-1.tailnet-name.ts.net.
	Addr      string
	Addrs     []string // the node's tailnet addresses, both families
	UserLogin string
	UserID    int64
}

// ShortName is the hostname without the tailnet suffix, for display.
func (p Peer) ShortName() string {
	n := strings.TrimSuffix(p.Name, ".")
	if i := strings.Index(n, "."); i > 0 {
		return n[:i]
	}
	return n
}

type Status struct {
	SelfIPs   []string
	SelfName  string
	SelfUser  int64
	Connected bool
}

func New() (*Client, error) {
	path := ""
	for _, p := range socketPaths {
		if _, err := os.Stat(p); err == nil {
			path = p
			break
		}
	}
	if path != "" {
		return &Client{http: &http.Client{
			Timeout: 5 * time.Second,
			Transport: &http.Transport{
				DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
					var d net.Dialer
					return d.DialContext(ctx, "unix", path)
				},
			},
		}}, nil
	}
	if runtime.GOOS == "darwin" {
		if _, _, err := macAppEndpoint(); err == nil {
			return newMacAppClient(), nil
		}
	}
	return nil, fmt.Errorf("tailscaled socket not found (is Tailscale running?)")
}

// newMacAppClient reaches the Tailscale app for macOS. Its port and token change when the
// app restarts, so they are looked up again whenever a connection fails.
func newMacAppClient() *Client {
	var mu sync.Mutex
	var port int
	var token string
	endpoint := func(fresh bool) (int, string, error) {
		mu.Lock()
		defer mu.Unlock()
		if port == 0 || fresh {
			p, t, err := macAppEndpoint()
			if err != nil {
				return 0, "", err
			}
			port, token = p, t
		}
		return port, token, nil
	}
	c := &Client{}
	c.token = func() string { _, t, _ := endpoint(false); return t }
	c.http = &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				p, _, err := endpoint(false)
				if err == nil {
					conn, derr := d.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(p))
					if derr == nil {
						return conn, nil
					}
				}
				if p, _, err = endpoint(true); err != nil {
					return nil, err
				}
				return d.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(p))
			},
		},
	}
	return c
}

// macAppEndpoint finds the Tailscale app's LocalAPI port and token: the App Store app
// holds a "sameuserproof-PORT-TOKEN" file open (found with lsof), the standalone app
// writes /Library/Tailscale/ipnport (a symlink to the port) and a token file beside it.
func macAppEndpoint() (int, string, error) {
	out, err := exec.Command("lsof", "-n", "-a", "-u"+strconv.Itoa(os.Getuid()), "-c", "IPNExtension", "-F").Output()
	if err == nil {
		if p, t, ok := parseSameUserProof(string(out)); ok {
			return p, t, nil
		}
	}
	portStr, err := os.Readlink("/Library/Tailscale/ipnport")
	if err != nil {
		return 0, "", fmt.Errorf("the Tailscale app is not running")
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return 0, "", err
	}
	tok, err := os.ReadFile("/Library/Tailscale/sameuserproof-" + portStr)
	if err != nil {
		return 0, "", err
	}
	if t := strings.TrimSpace(string(tok)); t != "" {
		return port, t, nil
	}
	return 0, "", fmt.Errorf("empty Tailscale token file")
}

// parseSameUserProof reads the App Store app's port and token out of lsof's -F output,
// where the open file shows as ".../io.tailscale.ipn.macos/sameuserproof-PORT-TOKEN".
func parseSameUserProof(lsof string) (int, string, bool) {
	const marker = ".tailscale.ipn.macos/sameuserproof-"
	for _, line := range strings.Split(lsof, "\n") {
		_, after, ok := strings.Cut(line, marker)
		if !ok {
			continue
		}
		portStr, token, ok := strings.Cut(strings.TrimSpace(after), "-")
		if !ok || token == "" {
			continue
		}
		if port, err := strconv.Atoi(portStr); err == nil && port > 0 {
			return port, token, true
		}
	}
	return 0, "", false
}

func (c *Client) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock"+path, nil)
	if err != nil {
		return err
	}
	req.Host = "local-tailscaled.sock"
	if c.token != nil {
		req.SetBasicAuth("", c.token())
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("localapi %s: %s", path, resp.Status)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// Status reports this machine's tailnet addresses. Used to decide what to bind.
func (c *Client) Status(ctx context.Context) (*Status, error) {
	var raw struct {
		BackendState string
		Self         struct {
			DNSName      string
			TailscaleIPs []string
			UserID       int64
		}
	}
	if err := c.get(ctx, "/localapi/v0/status", &raw); err != nil {
		return nil, err
	}
	return &Status{
		SelfIPs:   raw.Self.TailscaleIPs,
		SelfName:  raw.Self.DNSName,
		SelfUser:  raw.Self.UserID,
		Connected: raw.BackendState == "Running",
	}, nil
}

// WhoIs identifies the tailnet node behind a remote address ("100.x.y.z:port").
//
// This is the only identity source on the wire. A caller that WhoIs cannot resolve is
// not on the tailnet and is refused outright.
func (c *Client) WhoIs(ctx context.Context, remoteAddr string) (*Peer, error) {
	var raw struct {
		Node struct {
			StableID  string
			Name      string
			User      int64
			Addresses []string // "100.x.y.z/32", "fd7a:.../128"
		}
		UserProfile struct {
			ID          int64
			LoginName   string
			DisplayName string
		}
	}
	if err := c.get(ctx, "/localapi/v0/whois?addr="+remoteAddr, &raw); err != nil {
		return nil, err
	}
	if raw.Node.StableID == "" {
		return nil, fmt.Errorf("whois returned no node for %s", remoteAddr)
	}
	var addrs []string
	for _, a := range raw.Node.Addresses {
		if p, err := netip.ParsePrefix(a); err == nil {
			addrs = append(addrs, p.Addr().String())
		}
	}
	return &Peer{
		NodeID:    raw.Node.StableID,
		Name:      raw.Node.Name,
		Addr:      remoteAddr,
		Addrs:     addrs,
		UserLogin: raw.UserProfile.LoginName,
		UserID:    raw.UserProfile.ID,
	}, nil
}

// RunSSH reports whether Tailscale SSH serves this machine.
func (c *Client) RunSSH(ctx context.Context) (bool, error) {
	var prefs struct{ RunSSH bool }
	if err := c.get(ctx, "/localapi/v0/prefs", &prefs); err != nil {
		return false, err
	}
	return prefs.RunSSH, nil
}

// Resolver is the identity lookup the server depends on. Production uses *Client;
// tests inject a fake so the refusal and pairing paths can be exercised without a
// second physical tailnet node.
type Resolver interface {
	WhoIs(ctx context.Context, remoteAddr string) (*Peer, error)
}
