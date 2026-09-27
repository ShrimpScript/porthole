package server

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/store"
	"github.com/shrimpscript/porthole/daemon/internal/tailnet"
)

// fakeResolver stands in for tailscaled's WhoIs. A real second tailnet node cannot be
// conjured in a unit test, and a host cannot reach its own tailnet IP, so identity is
// injected here and the production path is exercised unchanged above it.
type fakeResolver struct {
	peer *tailnet.Peer
	err  error
}

func (f fakeResolver) WhoIs(context.Context, string) (*tailnet.Peer, error) {
	if f.err != nil {
		return nil, f.err
	}
	return f.peer, nil
}

const testNode = "nTESTDEVICE001CNTRL"

func newTestServer(t *testing.T, res tailnet.Resolver) (*Server, *httptest.Server, *store.Store) {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "devices.json"))
	if err != nil {
		t.Fatalf("store: %v", err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	s := New(res, st, log)
	hs := httptest.NewServer(s.Handler())
	t.Cleanup(hs.Close)
	return s, hs, st
}

func wsURL(hs *httptest.Server, query string) string {
	return "ws" + strings.TrimPrefix(hs.URL, "http") + "/ws" + query
}

func peer() *tailnet.Peer {
	return &tailnet.Peer{
		NodeID: testNode, Name: "pixel-test.example.ts.net.",
		Addr: "100.99.0.1:5555", UserLogin: "someone@example.com",
	}
}

// An unpaired tailnet peer is refused.
func TestUnpairedDeviceIsRefused(t *testing.T) {
	_, hs, _ := newTestServer(t, fakeResolver{peer: peer()})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, resp, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err == nil {
		c.CloseNow()
		t.Fatal("unpaired device was allowed to connect")
	}
	if resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403, got %v", resp)
	}
	defer resp.Body.Close()
	var e proto.Error
	_ = json.NewDecoder(resp.Body).Decode(&e)
	if e.Code != proto.ErrNotPaired {
		t.Fatalf("want error code %q, got %q", proto.ErrNotPaired, e.Code)
	}
}

// A caller WhoIs cannot resolve is not on the tailnet and never reaches the allowlist.
func TestUnknownPeerIsRefused(t *testing.T) {
	_, hs, _ := newTestServer(t, fakeResolver{err: errors.New("no such node")})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, resp, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err == nil {
		c.CloseNow()
		t.Fatal("unknown peer was allowed to connect")
	}
	defer resp.Body.Close()
	var e proto.Error
	_ = json.NewDecoder(resp.Body).Decode(&e)
	if e.Code != proto.ErrNotTailnet {
		t.Fatalf("want %q, got %q", proto.ErrNotTailnet, e.Code)
	}
}

// A paired device connects and receives daemon.hello.
func TestPairedDeviceGetsHello(t *testing.T) {
	s, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatalf("paired device could not connect: %v", err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })

	_, data, err := c.Read(ctx)
	if err != nil {
		t.Fatalf("read hello: %v", err)
	}
	var h proto.Hello
	if err := json.Unmarshal(data, &h); err != nil {
		t.Fatal(err)
	}
	if h.Type != proto.TypeHello || h.V != proto.Version {
		t.Fatalf("bad hello frame: %+v", h)
	}
	if h.DeviceName != "pixel-test" {
		t.Fatalf("hello should name the device, got %q", h.DeviceName)
	}
	if len(h.Caps) == 0 {
		t.Fatal("hello must advertise capabilities")
	}
	// Never advertise what it cannot do - the app reveals UI based on this. Capture
	// and recording are advertised only when grim / wf-recorder are installed.
	for _, c := range h.Caps {
		if c == proto.CapADB {
			t.Fatalf("advertised an unimplemented capability: %s", c)
		}
	}
	if s.LiveCount(testNode) != 1 {
		t.Fatalf("want 1 live socket, got %d", s.LiveCount(testNode))
	}
}

// Pairing with the right code admits the device and persists it; a wrong
// code does not.
func TestPairingCode(t *testing.T) {
	s, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	code, _, err := s.BeginPair()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if c, _, err := websocket.Dial(ctx, wsURL(hs, "?code=000000-wrong"), nil); err == nil {
		c.CloseNow()
		t.Fatal("a wrong pairing code was accepted")
	}
	if _, ok := st.Allowed(testNode); ok {
		t.Fatal("a failed pairing must not persist the device")
	}

	c, _, err := websocket.Dial(ctx, wsURL(hs, "?code="+code), nil)
	if err != nil {
		t.Fatalf("correct code was rejected: %v", err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })
	if _, _, err := c.Read(ctx); err != nil {
		t.Fatalf("no hello after pairing: %v", err)
	}
	if _, ok := st.Allowed(testNode); !ok {
		t.Fatal("pairing did not persist the device")
	}

	// Single use: the same code must not admit a second device.
	if s.pair.Redeem(code) {
		t.Fatal("pairing code was reusable")
	}
}

func TestPairingCodeExpires(t *testing.T) {
	s, _, _ := newTestServer(t, fakeResolver{peer: peer()})
	code, _, err := s.BeginPair()
	if err != nil {
		t.Fatal(err)
	}
	s.pair.mu.Lock()
	s.pair.pending.expires = time.Now().Add(-time.Second)
	s.pair.mu.Unlock()
	if s.pair.Redeem(code) {
		t.Fatal("an expired code was accepted")
	}
}

func TestPairingAttemptsAreBounded(t *testing.T) {
	s, _, _ := newTestServer(t, fakeResolver{peer: peer()})
	code, _, err := s.BeginPair()
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < maxPairAttempts; i++ {
		s.pair.Redeem("999999")
	}
	if s.pair.Redeem(code) {
		t.Fatal("the correct code still worked after the attempt budget was spent")
	}
}

// Revoke drops a live socket mid-stream, not at next connect.
func TestRevokeClosesLiveSocket(t *testing.T) {
	s, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.Read(ctx); err != nil {
		t.Fatalf("hello: %v", err)
	}

	start := time.Now()
	ok, err := s.Revoke(testNode)
	if err != nil || !ok {
		t.Fatalf("revoke failed: ok=%v err=%v", ok, err)
	}
	// Revocation must be immediate. Waiting on the websocket close handshake costs up
	// to 5s per socket and would make `portholed revoke` hang on a phone in a tunnel.
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("revoke took %v; it must not wait for a close handshake", elapsed)
	}

	// The app is told why, so it can show the "revoked" card instead of a bare drop.
	reasonCtx, reasonCancel := context.WithTimeout(context.Background(), 2*time.Second)
	_, data, readErr := c.Read(reasonCtx)
	reasonCancel()
	if readErr != nil {
		t.Fatalf("no revocation reason sent: %v", readErr)
	}
	var e proto.Error
	if err := json.Unmarshal(data, &e); err != nil || e.Code != proto.ErrRevoked {
		t.Fatalf("want a %q error frame, got %s (%v)", proto.ErrRevoked, data, err)
	}

	// The client's pending read must now fail, without waiting for a reconnect.
	readCtx, readCancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer readCancel()
	if _, _, err := c.Read(readCtx); err == nil {
		t.Fatal("socket stayed open after revoke")
	}
	// Release the hijacked connection now; httptest.Server.Close waits on it otherwise.
	_ = c.CloseNow()
	if _, stillAllowed := st.Allowed(testNode); stillAllowed {
		t.Fatal("revoked device is still on the allowlist")
	}

	// And it cannot simply reconnect.
	c2, resp2, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err == nil {
		c2.CloseNow()
		t.Fatal("revoked device reconnected")
	}
	if resp2 != nil {
		resp2.Body.Close()
	}
}

func TestDeviceStorePersistsAcrossRestart(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "devices.json")
	st, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	reopened, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := reopened.Allowed(testNode); !ok {
		t.Fatal("allowlist did not survive a restart")
	}
}

// Gate 0: a browser (any request carrying an Origin) is refused before the device gates,
// even from an allowlisted device. This is the defence against cross-site WebSocket
// hijacking - a malicious page open on a paired device could otherwise drive the daemon
// from script. The native client sends no Origin, so this never affects the real app.
func TestBrowserOriginIsRefused(t *testing.T) {
	_, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "phone"}); err != nil {
		t.Fatal(err) // fully paired, so it would pass gates 1 and 2
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	hdr := http.Header{"Origin": {"https://evil.example"}}
	c, resp, err := websocket.Dial(ctx, wsURL(hs, ""), &websocket.DialOptions{HTTPHeader: hdr})
	if err == nil {
		c.CloseNow()
		t.Fatal("a browser Origin was allowed to connect - CSWSH is possible")
	}
	if resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403, got %v", resp)
	}
	defer resp.Body.Close()
	var e proto.Error
	_ = json.NewDecoder(resp.Body).Decode(&e)
	if e.Code != proto.ErrForbidden {
		t.Fatalf("want error code %q, got %q", proto.ErrForbidden, e.Code)
	}

	// And the same allowlisted device with NO Origin still connects, so the fix is not
	// blanket paranoia - it targets browsers specifically.
	c2, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatalf("allowlisted native client (no Origin) was rejected: %v", err)
	}
	t.Cleanup(func() { _ = c2.CloseNow() })
}
