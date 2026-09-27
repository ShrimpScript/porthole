package server

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/store"
)

func testPublicKey(t *testing.T) string {
	t.Helper()
	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	var blob []byte
	for _, part := range [][]byte{[]byte("ssh-ed25519"), pub} {
		blob = binary.BigEndian.AppendUint32(blob, uint32(len(part)))
		blob = append(blob, part...)
	}
	return "ssh-ed25519 " + base64.StdEncoding.EncodeToString(blob) + " porthole"
}

// readType reads frames until one of the wanted type arrives.
func readType(t *testing.T, ctx context.Context, c *websocket.Conn, typ string) []byte {
	t.Helper()
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("waiting for %s: %v", typ, err)
		}
		var f proto.Frame
		if json.Unmarshal(data, &f) == nil && f.Type == typ {
			return data
		}
	}
}

// A paired phone adds its failsafe key, tied to its tailnet addresses; it can take it
// out again, and revoking the phone takes it out too.
func TestFailsafeKeyFollowsThePairing(t *testing.T) {
	p := peer()
	p.Addrs = []string{"100.99.0.1", "fd7a:115c:a1e0::99"}
	s, hs, st := newTestServer(t, fakeResolver{peer: p})
	keys := filepath.Join(t.TempDir(), ".ssh", "authorized_keys")
	s.authorizedKeys = keys
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })

	var h proto.Hello
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypeHello), &h)
	if h.Failsafe != "" {
		t.Fatalf("hello failsafe = %q before any key", h.Failsafe)
	}
	hasCap := false
	for _, c := range h.Caps {
		hasCap = hasCap || c == proto.CapSSHKey
	}
	if !hasCap {
		t.Fatal("hello does not advertise ssh_key")
	}

	send := func(key string) proto.SSHKeyState {
		b, _ := json.Marshal(map[string]string{"type": proto.TypeSSHKey, "public_key": key})
		if err := c.Write(ctx, websocket.MessageText, b); err != nil {
			t.Fatal(err)
		}
		var st proto.SSHKeyState
		_ = json.Unmarshal(readType(t, ctx, c, proto.TypeSSHKeyState), &st)
		return st
	}

	if got := send("ssh-rsa AAAAB3NzaC1yc2E="); got.Error == "" || got.Failsafe != "" {
		t.Fatalf("an RSA key was accepted: %+v", got)
	}
	if got := send(testPublicKey(t)); got.Error != "" || got.Failsafe != "key" {
		t.Fatalf("add: %+v", got)
	}
	b, _ := os.ReadFile(keys)
	line := strings.TrimSpace(string(b))
	if !strings.HasPrefix(line, `restrict,pty,from="100.99.0.1,fd7a:115c:a1e0::99" ssh-ed25519 `) ||
		!strings.HasSuffix(line, " porthole:"+testNode) {
		t.Fatalf("authorized_keys = %q", b)
	}

	if got := send(""); got.Error != "" || got.Failsafe != "" {
		t.Fatalf("remove: %+v", got)
	}
	if b, _ := os.ReadFile(keys); len(b) != 0 {
		t.Fatalf("key left behind: %q", b)
	}

	if got := send(testPublicKey(t)); got.Failsafe != "key" {
		t.Fatalf("re-add: %+v", got)
	}
	if ok, err := s.Revoke(testNode); !ok || err != nil {
		t.Fatalf("revoke: %v %v", ok, err)
	}
	_ = c.CloseNow()
	if b, _ := os.ReadFile(keys); len(b) != 0 {
		t.Fatalf("revoking the phone left its key: %q", b)
	}
}
