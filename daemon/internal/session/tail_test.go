package session

import (
	"context"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

func write(t *testing.T, f *os.File, s string) {
	t.Helper()
	if _, err := f.WriteString(s); err != nil {
		t.Fatal(err)
	}
	if err := f.Sync(); err != nil {
		t.Fatal(err)
	}
}

const userRec = `{"type":"user","message":{"content":"hello there"}}` + "\n"

// A tail necessarily catches partial writes, and real transcripts contain truncated
// lines from crashes. A half-written line must be held, not parsed and not dropped.
func TestTailerHoldsPartialLines(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.jsonl")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()

	tl := NewTailer(path)
	if err := tl.SeekEnd(); err != nil {
		t.Fatal(err)
	}

	// A line with no newline yet: nothing may be emitted.
	write(t, f, `{"type":"user","message":{"content":"half`)
	rows, err := tl.readNew()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 0 {
		t.Fatalf("partial line produced rows: %+v", rows)
	}

	// Complete it: now exactly one row, with the whole text intact.
	write(t, f, ` written"}}`+"\n")
	rows, err = tl.readNew()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 row after completion, got %d: %+v", len(rows), rows)
	}
	if rows[0].Text != "half written" {
		t.Fatalf("text lost across the split: %q", rows[0].Text)
	}
}

func TestTailerOnlyReadsWhatIsNew(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.jsonl")
	f, _ := os.Create(path)
	defer f.Close()
	write(t, f, userRec+userRec)

	tl := NewTailer(path) // offset 0: everything is new
	rows, err := tl.readNew()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("want 2 rows, got %d", len(rows))
	}
	// Nothing appended: a second read must be empty, not a re-read of the file.
	rows, err = tl.readNew()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 0 {
		t.Fatalf("re-read the whole file: %d rows", len(rows))
	}
	write(t, f, userRec)
	rows, _ = tl.readNew()
	if len(rows) != 1 {
		t.Fatalf("want just the appended row, got %d", len(rows))
	}
}

// A truncated or replaced file must reset rather than emit garbage from a stale offset.
func TestTailerRecoversFromTruncation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.jsonl")
	f, _ := os.Create(path)
	write(t, f, userRec+userRec)
	tl := NewTailer(path)
	if _, err := tl.readNew(); err != nil {
		t.Fatal(err)
	}
	f.Close()

	if err := os.WriteFile(path, []byte(userRec), 0o600); err != nil {
		t.Fatal(err)
	}
	rows, err := tl.readNew()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 row after truncation, got %d", len(rows))
	}
}

func TestWatchEmitsAppendedRows(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.jsonl")
	f, _ := os.Create(path)
	defer f.Close()

	tl := NewTailer(path)
	if err := tl.SeekEnd(); err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	var got []transcript.Row
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	go func() {
		_ = tl.Watch(ctx, func(rows []transcript.Row, _ transcript.State) {
			mu.Lock()
			got = append(got, rows...)
			mu.Unlock()
		})
	}()
	time.Sleep(200 * time.Millisecond) // let the watcher arm

	write(t, f, userRec)
	write(t, f, `{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}`+"\n")

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(got)
		mu.Unlock()
		if n >= 2 {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(got) < 2 {
		t.Fatalf("watch emitted %d rows, want 2: %+v", len(got), got)
	}
	if got[0].Kind != transcript.KindUser || got[1].Kind != transcript.KindTool {
		t.Fatalf("wrong kinds: %+v", got)
	}
	if got[1].Text != "Ran ls" {
		t.Fatalf("tool row wrong: %q", got[1].Text)
	}
}

func TestListFindsRealSessions(t *testing.T) {
	if _, err := os.Stat(transcript.ProjectsDir()); err != nil {
		t.Skip("no Claude projects directory on this machine")
	}
	list, err := List()
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range list {
		if s.ID == "" || s.Transcript == "" {
			t.Fatalf("session with no id/transcript: %+v", s)
		}
		if s.Title == "" {
			t.Fatalf("session with no title: %+v", s)
		}
		if s.Cwd == "" {
			t.Errorf("session %s has no cwd - peek missed it", s.ID)
		}
	}
	t.Logf("found %d sessions", len(list))
}

// A restarted Claude Code writes a new transcript in the same directory. The tailer must
// move to it, or the phone's feed silently stops.
func TestWatchFollowsNewTranscript(t *testing.T) {
	dir := t.TempDir()
	old := filepath.Join(dir, "old.jsonl")
	os.WriteFile(old, []byte(`{"type":"user","message":{"role":"user","content":"hi"}}`+"\n"), 0o644)
	tl := NewTailer(old)
	if _, err := tl.Backfill(0); err != nil {
		t.Fatal(err)
	}
	tl.SeekEnd()
	switched := make(chan string, 1)
	tl.OnSwitch = func(p string) { switched <- p }
	var mu sync.Mutex
	var got []transcript.Row
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		_ = tl.Watch(ctx, func(rows []transcript.Row, _ transcript.State) {
			mu.Lock()
			got = append(got, rows...)
			mu.Unlock()
		})
	}()
	time.Sleep(150 * time.Millisecond)
	fresh := filepath.Join(dir, "fresh.jsonl")
	os.WriteFile(fresh, []byte(`{"type":"user","message":{"role":"user","content":"second session"}}`+"\n"), 0o644)
	select {
	case p := <-switched:
		if p != fresh {
			t.Fatalf("switched to %s, want %s", p, fresh)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("tailer never switched to the new transcript")
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(got)
		mu.Unlock()
		if n > 0 {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(got) == 0 || got[len(got)-1].Text != "second session" {
		t.Fatalf("rows from the new transcript not delivered: %+v", got)
	}
	if tl.Path() != fresh {
		t.Fatalf("tailer path %s", tl.Path())
	}
}
