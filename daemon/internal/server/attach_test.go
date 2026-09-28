package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/shrimpscript/porthole/daemon/internal/platform"
	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/store"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// liveSession registers this test's own process as a running Claude Code session in a
// scratch config directory, the way the CLI's registry would.
func liveSession(t *testing.T, id string) string {
	t.Helper()
	root := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", root)
	cwd := filepath.Join(root, "proj")
	write(t, filepath.Join(root, "projects", transcript.ProjectSlug(cwd)), id+".jsonl",
		fmt.Sprintf(`{"type":"user","cwd":%q,"sessionId":%q,"message":{"role":"user","content":"hello"}}`+"\n", cwd, id))
	start, ok := platform.ProcStart(os.Getpid())
	if !ok || start == "" {
		t.Skip("cannot read this process's start time here")
	}
	write(t, filepath.Join(root, "sessions"), "one.json", fmt.Sprintf(`{"pid":%d,"sessionId":%q,"cwd":%q,"procStart":%q,"kind":"interactive","startedAt":1000}`, os.Getpid(), id, cwd, start))
	return cwd
}

func dialPaired(t *testing.T) (*websocket.Conn, context.Context) {
	t.Helper()
	_, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	t.Cleanup(cancel)
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.CloseNow() })
	c.SetReadLimit(64 << 20)
	if _, _, err := c.Read(ctx); err != nil { // hello
		t.Fatal(err)
	}
	return c, ctx
}

// nextError reads until the daemon says something went wrong, and returns its code.
func nextError(t *testing.T, ctx context.Context, c *websocket.Conn) string {
	t.Helper()
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("the connection closed: %v", err)
		}
		var e proto.Error
		if json.Unmarshal(data, &e) == nil && e.Type == proto.TypeError {
			return e.Code + ": " + e.Message
		}
	}
}

// waitGone waits up to two seconds for nothing to match pattern.
func waitGone(t *testing.T, pattern string) {
	t.Helper()
	for i := 0; ; i++ {
		left, _ := filepath.Glob(pattern)
		if len(left) == 0 {
			return
		}
		if i == 100 {
			t.Fatalf("still on disk: %v", left)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func sendJSON(t *testing.T, ctx context.Context, c *websocket.Conn, v any) {
	t.Helper()
	b, _ := json.Marshal(v)
	if err := c.Write(ctx, websocket.MessageText, b); err != nil {
		t.Fatal(err)
	}
}

// nextErrorFrame reads until the daemon says something went wrong.
func nextErrorFrame(t *testing.T, ctx context.Context, c *websocket.Conn) proto.Error {
	t.Helper()
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("the connection closed: %v", err)
		}
		var e proto.Error
		if json.Unmarshal(data, &e) == nil && e.Type == proto.TypeError {
			return e
		}
	}
}

func pieces(t *testing.T, ctx context.Context, c *websocket.Conn, id, name string, body []byte) {
	const piece = 128 << 10
	for seq, off := 0, 0; off < len(body); seq, off = seq+1, off+piece {
		end := min(off+piece, len(body))
		m := map[string]any{"type": "upload.chunk", "upload": id, "seq": seq, "last": end == len(body),
			"data": base64.StdEncoding.EncodeToString(body[off:end])}
		if seq == 0 {
			m["name"], m["media"] = name, "text/plain"
		}
		sendJSON(t, ctx, c, m)
	}
}

// Assembled from pieces, a file is whole, under its own name, with no part left.
func TestUploadAssembledFromPieces(t *testing.T) {
	u := newUploads()
	root := t.TempDir()
	u.dir = func() string { return root }
	body := []byte(strings.Repeat("a line of a report\n", 3<<20/19))
	const piece = 128 << 10
	for seq, off := 0, 0; off < len(body); seq, off = seq+1, off+piece {
		end := min(off+piece, len(body))
		if err := u.chunk("u1", seq, base64.StdEncoding.EncodeToString(body[off:end]), "Q3 report.txt", "text/plain", end == len(body)); err != nil {
			t.Fatal(err)
		}
	}
	files, err := u.take([]string{"u1"})
	if err != nil || len(files) != 1 || !strings.HasSuffix(files[0].path, "-Q3-report.txt") {
		t.Fatalf("take: %+v %v", files, err)
	}
	if b, _ := os.ReadFile(files[0].path); len(b) != len(body) {
		t.Fatalf("saved %d bytes, sent %d", len(b), len(body))
	}
	if parts, _ := filepath.Glob(filepath.Join(root, "*", ".part-*")); len(parts) != 0 {
		t.Fatalf("parts left: %v", parts)
	}
	// A finished upload waiting for its prompt cannot be started over.
	if err := u.chunk("u2", 0, "YQ==", "b.txt", "text/plain", true); err != nil {
		t.Fatal(err)
	}
	if err := u.chunk("u2", 0, "Yg==", "again.txt", "text/plain", true); err == nil {
		t.Fatal("a finished upload was started over")
	}
}

// Over the socket: a file in pieces reaches the prompt, and a prompt that cannot be
// typed (no tmux pane here) is refused naming the upload, with the file let go - the
// phone gets the message back and sends it again.
func TestUploadInPiecesReachesThePrompt(t *testing.T) {
	state := t.TempDir()
	t.Setenv("PORTHOLE_STATE_DIR", state)
	liveSession(t, "sess1")
	c, ctx := dialPaired(t)
	pieces(t, ctx, c, "u1", "report.txt", []byte(strings.Repeat("x", 400<<10)))
	sendJSON(t, ctx, c, map[string]any{"type": "prompt.send", "session_id": "sess1", "text": "Summarise this", "uploads": []string{"u1"}})
	if e := nextErrorFrame(t, ctx, c); e.Code != "send_failed" || e.Ref != "u1" {
		t.Fatalf("want send_failed about u1, got %+v", e)
	}
	// The refusal goes out first and the files right after it.
	waitGone(t, filepath.Join(state, "uploads", "*", "*"))
}

// Photos from apps before 0.32.0 come inside the prompt, one message of hundreds of KB:
// the socket takes it rather than closing at the library's 32 KB.
func TestOldAppsPhotoInsideThePromptArrives(t *testing.T) {
	state := t.TempDir()
	t.Setenv("PORTHOLE_STATE_DIR", state)
	liveSession(t, "sess1")
	c, ctx := dialPaired(t)
	photo := make([]byte, 400<<10)
	sendJSON(t, ctx, c, map[string]any{"type": "prompt.send", "session_id": "sess1", "text": "",
		"attachments": []map[string]string{{"name": "photo-1.jpg", "media": "image/jpeg", "data": base64.StdEncoding.EncodeToString(photo)}}})
	if e := nextError(t, ctx, c); !strings.HasPrefix(e, "send_failed") {
		t.Fatalf("want the prompt to reach the typing step, got %q", e)
	}
	if saved, _ := filepath.Glob(filepath.Join(state, "uploads", "*", "*photo-1.jpg")); len(saved) != 1 {
		t.Fatalf("saved: %v", saved)
	}
}

// Pieces out of order are refused once, naming the upload, and the rest of its pieces
// are dropped quietly; a prompt naming it is refused too; files for a session that is
// not running are let go; and a connection that closes takes its unused files with it.
func TestUploadsThatGoWrong(t *testing.T) {
	state := t.TempDir()
	t.Setenv("PORTHOLE_STATE_DIR", state)
	liveSession(t, "sess1")
	c, ctx := dialPaired(t)
	piece := base64.StdEncoding.EncodeToString([]byte("some bytes"))

	sendJSON(t, ctx, c, map[string]any{"type": "upload.chunk", "upload": "u1", "seq": 0, "data": piece, "name": "a.txt", "media": "text/plain"})
	sendJSON(t, ctx, c, map[string]any{"type": "upload.chunk", "upload": "u1", "seq": 2, "data": piece})
	sendJSON(t, ctx, c, map[string]any{"type": "upload.chunk", "upload": "u1", "seq": 3, "data": piece, "last": true})
	if e := nextErrorFrame(t, ctx, c); !strings.Contains(e.Message, "out of order") || e.Ref != "u1" {
		t.Fatalf("out of order: %+v", e)
	}
	sendJSON(t, ctx, c, map[string]any{"type": "prompt.send", "session_id": "sess1", "text": "x", "uploads": []string{"u1"}})
	if e := nextErrorFrame(t, ctx, c); e.Code != "upload_failed" || e.Ref != "u1" {
		t.Fatalf("the next error should be the prompt's, not another piece's: %+v", e)
	}

	pieces(t, ctx, c, "u2", "b.txt", []byte("finished"))
	sendJSON(t, ctx, c, map[string]any{"type": "prompt.send", "session_id": "gone", "text": "x", "uploads": []string{"u2"}})
	if e := nextErrorFrame(t, ctx, c); e.Code != "no_session" || e.Ref != "u2" {
		t.Fatalf("a session that is not there: %+v", e)
	}
	waitGone(t, filepath.Join(state, "uploads", "*", "*"))

	pieces(t, ctx, c, "u3", "c.txt", []byte("never used"))
	sendJSON(t, ctx, c, map[string]any{"type": "upload.chunk", "upload": "u9", "seq": 1, "data": piece})
	nextErrorFrame(t, ctx, c) // u3 is done by the time this arrives
	c.Close(websocket.StatusNormalClosure, "")
	waitGone(t, filepath.Join(state, "uploads", "*", "*"))
}

func TestTidyUploads(t *testing.T) {
	root := t.TempDir()
	now := time.Now()
	old := filepath.Join(root, now.AddDate(0, 0, -16).Format("2006-01-02"))
	day := filepath.Join(root, now.Format("2006-01-02"))
	other := filepath.Join(root, "not-a-day")
	write(t, old, "120000-1-old.pdf", "x")    // fresh times, but its day is past
	write(t, old, "unpacked/inside.txt", "x") // what Claude unzipped next to it
	write(t, day, "120000-1-new.pdf", "x")
	write(t, day, ".part-aaaa", "x")
	write(t, day, ".part-bbbb", "x")
	write(t, other, "keep.txt", "x")
	os.Chtimes(filepath.Join(day, ".part-aaaa"), now, now.Add(-2*time.Hour))
	os.Chtimes(filepath.Join(day, "120000-1-new.pdf"), now, now.Add(-400*24*time.Hour)) // an old time, a current day
	tidyUploads(root, now)
	var left []string
	filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			left = append(left, filepath.Base(p))
		}
		return nil
	})
	sort.Strings(left)
	if !reflect.DeepEqual(left, []string{".part-bbbb", "120000-1-new.pdf", "keep.txt"}) {
		t.Fatalf("left: %v", left)
	}
}
