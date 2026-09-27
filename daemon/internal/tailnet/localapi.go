// Package tailnet talks to the local tailscaled over its unix socket.
//
// Deliberately no dependency on the Tailscale Go module: the LocalAPI is plain HTTP
// over a unix socket, and speaking it directly keeps portholed a small static binary
// with no vendored networking stack.
package tailnet

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"time"
)

// Socket paths, in the order tailscaled is known to use them.
var socketPaths = []string{
	"/var/run/tailscale/tailscaled.sock",
	"/run/tailscale/tailscaled.sock",
}

type Client struct {
	http *http.Client
}

// Peer is the subset of a WhoIs response Porthole actually uses.
type Peer struct {
	NodeID    string // StableID - survives IP changes, so this is what the allowlist keys on
	Name      string // MagicDNS name, e.g. phone-1.tailnet-name.ts.net.
	Addr      string
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
	if path == "" {
		return nil, fmt.Errorf("tailscaled socket not found (is Tailscale running?)")
	}
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

func (c *Client) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock"+path, nil)
	if err != nil {
		return err
	}
	req.Host = "local-tailscaled.sock"
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
			StableID string
			Name     string
			User     int64
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
	return &Peer{
		NodeID:    raw.Node.StableID,
		Name:      raw.Node.Name,
		Addr:      remoteAddr,
		UserLogin: raw.UserProfile.LoginName,
		UserID:    raw.UserProfile.ID,
	}, nil
}

// Resolver is the identity lookup the server depends on. Production uses *Client;
// tests inject a fake so the refusal and pairing paths can be exercised without a
// second physical tailnet node.
type Resolver interface {
	WhoIs(ctx context.Context, remoteAddr string) (*Peer, error)
}
