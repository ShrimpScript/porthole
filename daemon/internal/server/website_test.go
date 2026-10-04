package server

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/store"
)

// The website button end to end, as the phone drives it: a real dev server on the
// computer (Python's http.server) is listed, shared, and its page fetched through the
// share by the computer's short MagicDNS name - the name many phones were paired with,
// which a share used to refuse - and by the IP the share now names.
func TestTheWebsiteButtonEndToEnd(t *testing.T) {
	py, err := exec.LookPath("python3")
	if err != nil {
		t.Skip("python3 not installed")
	}
	site := t.TempDir()
	if err := os.WriteFile(filepath.Join(site, "index.html"), []byte("<h1>dev site works</h1>"), 0o600); err != nil {
		t.Fatal(err)
	}
	// A port where dev servers live: the scan leaves out the ephemeral range on purpose.
	upstream := 0
	for p := 18100; p < 18900 && upstream == 0; p++ {
		if ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p)); err == nil {
			ln.Close()
			upstream = p
		}
	}
	if upstream == 0 {
		t.Skip("no free port for a dev server")
	}
	dev := exec.Command(py, "-m", "http.server", fmt.Sprint(upstream), "--bind", "127.0.0.1", "--directory", site)
	if err := dev.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = dev.Process.Kill(); _ = dev.Wait() })
	for i := 0; i < 50; i++ {
		if c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", upstream)); err == nil {
			c.Close()
			break
		}
		time.Sleep(100 * time.Millisecond)
	}

	s, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if !s.hasCap(proto.CapPreview) {
		t.Skip("this machine cannot map sockets to processes")
	}
	s.SetBind([]string{"127.0.0.1"}, 8737, []string{"box.example.ts.net."})
	// Loopback is never named as the address to open (the emulator setup opens its own).
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	readType(t, ctx, c, proto.TypeHello)
	send := func(v any) {
		b, _ := json.Marshal(v)
		if err := c.Write(ctx, websocket.MessageText, b); err != nil {
			t.Fatal(err)
		}
	}

	// Listed: the button's sheet shows the dev server.
	send(map[string]any{"type": "preview.list"})
	var list struct {
		Servers []devServer `json:"servers"`
	}
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypePreviewList), &list)
	found := false
	for _, d := range list.Servers {
		found = found || d.Port == upstream
	}
	if !found {
		t.Fatalf("the dev server on %d is not listed: %+v", upstream, list.Servers)
	}

	// Shared: the frame names the port and the address to open.
	send(map[string]any{"type": "preview.open", "port": upstream})
	var state struct {
		Open bool `json:"open"`
		previewShare
	}
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypePreviewState), &state)
	if !state.Open || state.Port == 0 || state.Host != "" {
		t.Fatalf("share: %+v", state)
	}

	// Opened in the browser: by the short name the phone was paired with, and by the IP.
	for _, host := range []string{"box", "127.0.0.1"} {
		req, _ := http.NewRequest("GET", fmt.Sprintf("http://127.0.0.1:%d/", state.Port), nil)
		req.Host = fmt.Sprintf("%s:%d", host, state.Port)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s: %v", host, err)
		}
		body, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 200 || !strings.Contains(string(body), "dev site works") {
			t.Fatalf("%s: %d %s", host, res.StatusCode, body)
		}
	}
	send(map[string]any{"type": "preview.close", "port": upstream})
	_ = json.Unmarshal(readType(t, ctx, c, proto.TypePreviewState), &state)
	if state.Open {
		t.Fatal("the share did not close")
	}
}
