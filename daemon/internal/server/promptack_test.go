package server

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/platform"
	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// A text prompt that names itself is answered either way: prompt.sent when it was typed,
// an error naming it when it was not. The phone keeps the message in its box until then.
func TestATextPromptIsAnsweredByItsRef(t *testing.T) {
	tmuxtest.Fresh(t)
	// A pane of the tests' own tmux server for the session to live in; what it runs
	// does not matter, only that keys can be typed into it.
	// Fresh's kill-server returns before the old server is gone; a command in that moment
	// meets it dying ("server exited unexpectedly"), so the first one is tried again.
	var out []byte
	var err error
	for try := 0; try < 40; try++ {
		if out, err = exec.Command("tmux", "new-session", "-d", "-s", "acks", "-x", "80", "-y", "24", "sleep 600").CombinedOutput(); err == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("tmux: %v %s", err, out)
	}
	ref, err := exec.Command("tmux", "display-message", "-p", "-t", "acks", "#{session_name}:#{window_id}.#{pane_id}").Output()
	if err != nil {
		t.Fatal(err)
	}
	liveSessionIn(t, "sess1", strings.TrimSpace(string(ref)))
	c, ctx := dialPaired(t)

	sendJSON(t, ctx, c, map[string]any{"type": "prompt.send", "session_id": "sess1", "text": "run the tests", "ref": "p1"})
	var sent struct {
		Type string `json:"type"`
		Ref  string `json:"ref"`
	}
	if err := json.Unmarshal(readType(t, ctx, c, proto.TypePromptSent), &sent); err != nil || sent.Ref != "p1" {
		t.Fatalf("prompt.sent: %+v %v", sent, err)
	}

	// Not typed: the refusal names it.
	sendJSON(t, ctx, c, map[string]any{"type": "prompt.send", "session_id": "gone", "text": "and this", "ref": "p2"})
	if e := nextErrorFrame(t, ctx, c); e.Code != "no_session" || e.Ref != "p2" {
		t.Fatalf("want no_session about p2, got %+v", e)
	}
}

func TestPromptAckIsAdvertised(t *testing.T) {
	found := false
	for _, c := range detectCaps() {
		found = found || c == proto.CapPromptAck
	}
	if !found {
		t.Fatal("prompt_ack is not among the caps")
	}
}

// liveSessionIn is liveSession with the registry naming a tmux pane ("session:@w.%p").
func liveSessionIn(t *testing.T, id, tmuxRef string) {
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
	write(t, filepath.Join(root, "sessions"), "one.json", fmt.Sprintf(`{"pid":%d,"sessionId":%q,"cwd":%q,"procStart":%q,"kind":"interactive","startedAt":1000,"tmux":%q}`, os.Getpid(), id, cwd, start, tmuxRef))
}
