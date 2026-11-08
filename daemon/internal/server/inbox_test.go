package server

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/inbox"
	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
)

// The phone reads the inbox, answers one question and puts another aside. The answer is
// recorded in the project's decisions file and typed to the session running there.
func TestInboxOverTheSocket(t *testing.T) {
	tmuxtest.Fresh(t)
	var out []byte
	var err error
	for try := 0; try < 40; try++ {
		if out, err = exec.Command("tmux", "new-session", "-d", "-s", "inbox", "-x", "120", "-y", "30", "sleep 600").CombinedOutput(); err == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("tmux: %v %s", err, out)
	}
	ref, err := exec.Command("tmux", "display-message", "-p", "-t", "inbox", "#{session_name}:#{window_id}.#{pane_id}").Output()
	if err != nil {
		t.Fatal(err)
	}
	liveSessionIn(t, "sess1", strings.TrimSpace(string(ref)))
	root := os.Getenv("CLAUDE_CONFIG_DIR") // liveSessionIn's session runs in root/proj
	proj := filepath.Join(root, "proj")
	write(t, proj, ".claude/progress.md", "## Waiting on you\n- Which engine: one | two\n- Name the release\n")
	state := t.TempDir()
	t.Setenv("PORTHOLE_STATE_DIR", state)
	if err := inbox.SaveConfig(state, inbox.Config{Folders: []string{root}}); err != nil {
		t.Fatal(err)
	}

	c, ctx := dialPaired(t)
	var got inboxFrame
	read := func() {
		t.Helper()
		got = inboxFrame{}
		if err := json.Unmarshal(readType(t, ctx, c, proto.TypeInbox), &got); err != nil {
			t.Fatal(err)
		}
	}
	sendJSON(t, ctx, c, map[string]any{"type": proto.TypeInboxGet})
	read()
	if !got.Configured || len(got.Projects) != 1 || !got.Projects[0].Live || len(got.Projects[0].Items) != 2 {
		t.Fatalf("inbox = %+v", got)
	}
	var engine, release inbox.Item
	for _, it := range got.Projects[0].Items {
		if len(it.Options) == 2 {
			engine = it
		} else {
			release = it
		}
	}
	if engine.Text != "Which engine: one | two" || release.Text != "Name the release" {
		t.Fatalf("items = %+v", got.Projects[0].Items)
	}

	dir := got.Projects[0].Dir
	sendJSON(t, ctx, c, map[string]any{"type": proto.TypeInboxAnswer, "dir": dir, "item": engine.ID, "text": "two"})
	var ans inboxAnswered
	if err := json.Unmarshal(readType(t, ctx, c, proto.TypeInboxAnswered), &ans); err != nil {
		t.Fatal(err)
	}
	if ans.Error != "" || ans.Decisions != "DECISIONS.md" || ans.Session == "" || ans.Item != engine.ID || ans.Dir != dir {
		t.Fatalf("answered = %+v", ans)
	}
	read()
	if len(got.Projects) != 1 || len(got.Projects[0].Items) != 1 {
		t.Fatalf("after the answer = %+v", got)
	}
	b, _ := os.ReadFile(filepath.Join(proj, "DECISIONS.md"))
	if !strings.Contains(string(b), "Answer: two <!-- inbox:"+engine.ID+" -->") {
		t.Fatalf("decisions file:\n%s", b)
	}
	screen, _ := exec.Command("tmux", "capture-pane", "-p", "-J", "-t", "inbox").Output()
	if !strings.Contains(string(screen), `Answer from the Porthole inbox to "Which engine: one | two"`) {
		t.Fatalf("the session was not told; its screen:\n%s", screen)
	}

	// An item that is gone is refused, and nothing is written.
	sendJSON(t, ctx, c, map[string]any{"type": proto.TypeInboxAnswer, "dir": dir, "item": engine.ID, "text": "one"})
	if err := json.Unmarshal(readType(t, ctx, c, proto.TypeInboxAnswered), &ans); err != nil || ans.Error == "" {
		t.Fatalf("answering twice = %+v %v", ans, err)
	}
	if b2, _ := os.ReadFile(filepath.Join(proj, "DECISIONS.md")); string(b2) != string(b) {
		t.Fatal("a second answer was written")
	}

	sendJSON(t, ctx, c, map[string]any{"type": proto.TypeInboxDismiss, "dir": dir, "item": release.ID})
	// The refused answer's own refresh may come first: the requests are handled side by side.
	for read(); len(got.Projects) != 0; read() {
	}
	if !inbox.Dismissed(state)[inbox.DismissKey(dir, release.ID)] {
		t.Fatal("not dismissed")
	}
}

func TestInboxIsAdvertised(t *testing.T) {
	found := false
	for _, c := range detectCaps() {
		found = found || c == proto.CapInbox
	}
	if !found {
		t.Fatal("inbox is not among the caps")
	}
}
