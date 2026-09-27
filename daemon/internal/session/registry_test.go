package session

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseTmuxRef(t *testing.T) {
	cases := []struct{ in, sess, win, pane string }{
		{"0:@0.%0", "0", "@0", "%0"},
		{"dev:@12.%40", "dev", "@12", "%40"},
		{"my-work:@3", "my-work", "@3", ""},
		{"", "", "", ""},
		{"garbage", "", "", ""},
	}
	for _, c := range cases {
		s, w, p := parseTmuxRef(c.in)
		if s != c.sess || w != c.win || p != c.pane {
			t.Errorf("%q: got %q %q %q", c.in, s, w, p)
		}
	}
}

// ownStart is this process's start time as /proc reports it, the value the CLI stores.
func ownStart(t *testing.T) string {
	b, err := os.ReadFile("/proc/self/stat")
	if err != nil {
		t.Skip("no /proc")
	}
	f := strings.Fields(string(b[strings.LastIndexByte(string(b), ')')+1:]))
	return f[19]
}

func writeReg(t *testing.T, dir string, pid int, start, id, cwd, tmux, kind string) {
	body := fmt.Sprintf(`{"pid":%d,"sessionId":%q,"cwd":%q,"startedAt":1789344486512,"procStart":%q,"kind":%q,"tmux":%q,"name":"proj-9a","status":"busy"}`,
		pid, id, cwd, start, kind, tmux)
	if err := os.WriteFile(filepath.Join(dir, fmt.Sprintf("%d.json", pid)), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	// The CLI keeps a key file beside each entry; it must be ignored.
	_ = os.WriteFile(filepath.Join(dir, fmt.Sprintf("%d.abc.key", pid)), []byte(`{"peerToken":"x"}`), 0o600)
}

func TestProcsInKeepsOnlyLiveInteractive(t *testing.T) {
	dir := t.TempDir()
	start := ownStart(t)
	writeReg(t, dir, os.Getpid(), start, "live-id", "/srv/proj", "work:@2.%9", "interactive")
	writeReg(t, dir, os.Getpid()+1000000, "1", "dead-id", "/srv/proj", "work:@3.%10", "interactive")
	writeReg(t, dir, os.Getpid(), start, "print-id", "/srv/proj", "", "print")
	// a reused pid: right pid, wrong start time
	writeReg(t, dir, os.Getpid(), "1", "reused-id", "/srv/other", "", "interactive")
	got := procsIn(dir, procAlive)
	// the two entries for our own pid share a file name, so only the last write survives;
	// write the reused one under a distinct name to keep both cases
	_ = got
	os.Remove(filepath.Join(dir, fmt.Sprintf("%d.json", os.Getpid())))
	writeReg(t, dir, os.Getpid(), start, "live-id", "/srv/proj", "work:@2.%9", "interactive")
	b := fmt.Sprintf(`{"pid":%d,"sessionId":"reused-id","cwd":"/srv/other","procStart":"1","kind":"interactive"}`, os.Getpid())
	_ = os.WriteFile(filepath.Join(dir, "reused.json"), []byte(b), 0o600)
	got = procsIn(dir, procAlive)
	if len(got) != 1 || got[0].SessionID != "live-id" {
		t.Fatalf("want only live-id, got %+v", got)
	}
	p := got[0]
	if p.TmuxSess != "work" || p.Window != "@2" || p.Pane != "%9" || p.Name != "proj-9a" || p.Status != "busy" {
		t.Errorf("fields: %+v", p)
	}
}

func TestSuccessorOnlyFollowsARestartInTheSamePane(t *testing.T) {
	old := Info{ID: "a", Cwd: "/srv/proj", Pane: "%1"}
	procs := []Proc{{SessionID: "a", Cwd: "/srv/proj", Pane: "%1"}, {SessionID: "b", Cwd: "/srv/proj", Pane: "%2"}}
	if successor(old, "/x/b.jsonl", procs) {
		t.Error("a second live session beside a live one must not take the feed")
	}
	// a died; c started in a's pane, d in another pane
	procs = []Proc{{SessionID: "b", Cwd: "/srv/proj", Pane: "%2"}, {SessionID: "c", Cwd: "/srv/proj", Pane: "%1"}, {SessionID: "d", Cwd: "/srv/proj", Pane: "%3"}}
	if !successor(old, "/x/c.jsonl", procs) {
		t.Error("the session restarted in the same pane is the successor")
	}
	if successor(old, "/x/d.jsonl", procs) {
		t.Error("a session in another pane is not")
	}
	if successor(old, "/x/nobody.jsonl", procs) {
		t.Error("a file with no process behind it is not a session")
	}
	// no registry at all: the old behaviour, follow the directory's newest
	if !successor(old, "/x/any.jsonl", nil) {
		t.Error("without a registry the newest file is followed")
	}
	// outside tmux: same directory is the best available match
	old = Info{ID: "a", Cwd: "/srv/proj"}
	procs = []Proc{{SessionID: "e", Cwd: "/srv/proj"}}
	if !successor(old, "/x/e.jsonl", procs) {
		t.Error("without panes, a restart in the same directory is followed")
	}
}

func TestListUsesTheRegistry(t *testing.T) {
	root := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", root)
	sessions := filepath.Join(root, "sessions")
	projects := filepath.Join(root, "projects")
	if err := os.MkdirAll(sessions, 0o700); err != nil {
		t.Fatal(err)
	}
	start := ownStart(t)
	// Two live sessions in one directory: both listed, with their own panes.
	cwd := filepath.Join(root, "proj")
	slug := filepath.Join(projects, ProjectSlugForTest(cwd))
	if err := os.MkdirAll(slug, 0o700); err != nil {
		t.Fatal(err)
	}
	rec := `{"type":"user","cwd":%q,"sessionId":%q,"timestamp":"2026-09-13T10:00:00Z","message":{"role":"user","content":"hello from %s"}}` + "\n"
	body := func(id, who string) string {
		var b strings.Builder
		for i := 0; i < 8; i++ {
			b.WriteString(fmt.Sprintf(rec, cwd, id, who))
		}
		return b.String()
	}
	_ = os.WriteFile(filepath.Join(slug, "one.jsonl"), []byte(body("one", "one")), 0o600)
	// "two" has registered but not written a transcript yet
	writeReg(t, sessions, os.Getpid(), start, "one", cwd, "work:@1.%1", "interactive")
	b := fmt.Sprintf(`{"pid":%d,"sessionId":"two","cwd":%q,"procStart":%q,"kind":"interactive","tmux":"work:@2.%%2","name":"proj-ab","startedAt":1789344486512}`, os.Getpid(), cwd, start)
	_ = os.WriteFile(filepath.Join(sessions, "two.json"), []byte(b), 0o600)
	// An idle directory with an old transcript: listed once, not live.
	idle := filepath.Join(projects, "-srv-idle")
	_ = os.MkdirAll(idle, 0o700)
	_ = os.WriteFile(filepath.Join(idle, "old.jsonl"), []byte(strings.ReplaceAll(body("old", "old"), cwd, "/srv/idle")), 0o600)

	list, err := List()
	if err != nil {
		t.Fatal(err)
	}
	byID := map[string]Info{}
	for _, si := range list {
		byID[si.ID] = si
	}
	if len(list) != 3 {
		t.Fatalf("want 3 entries (two live in one dir, one idle), got %d: %+v", len(list), list)
	}
	one, two, old := byID["one"], byID["two"], byID["old"]
	if !one.Live || one.Pane != "%1" || one.TmuxName != "work" || !one.Tmux {
		t.Errorf("one: %+v", one)
	}
	if !two.Live || two.Pane != "%2" || two.Title != "proj-ab" || two.Size != 0 {
		t.Errorf("two (no transcript yet): %+v", two)
	}
	if old.Live || old.Pane != "" || old.ID != "old" {
		t.Errorf("old: %+v", old)
	}
	if rememberedPane("one") != "%1" {
		t.Error("live panes are remembered for Resolve")
	}
}

func TestPeekFindsATitleBeyondTheWindows(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "big.jsonl")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	pad := strings.Repeat("x", 4000)
	rec := func(i int) string {
		return fmt.Sprintf(`{"type":"user","cwd":"/srv/proj","sessionId":"big","timestamp":"2026-09-13T10:00:00Z","message":{"role":"user","content":"%d %s"}}`+"\n", i, pad)
	}
	for i := 0; i < 100; i++ {
		fmt.Fprint(f, rec(i))
	}
	// the title lands after the head window (60 lines) ...
	fmt.Fprint(f, `{"type":"ai-title","aiTitle":"Add dark mode to settings","sessionId":"big"}`+"\n")
	// ... and more than 512 KB before the end
	for i := 100; i < 300; i++ {
		fmt.Fprint(f, rec(i))
	}
	f.Close()
	st, _ := os.Stat(path)
	if st.Size() < 700<<10 {
		t.Fatalf("test file too small to leave the title outside the tail window: %d", st.Size())
	}
	m, err := peek(path, st.Size())
	if err != nil {
		t.Fatal(err)
	}
	if m.Title != "Add dark mode to settings" {
		t.Fatalf("title %q", m.Title)
	}
	// cached: a second peek does not need the file
	os.Remove(path)
	if m2, _ := peek(path, st.Size()); m2.Title != "" {
		// peek itself opens the file; only scanTitle is cached. Check the cache directly.
		t.Log("peek reopened the file, fine")
	}
	if scanTitle(path, st.Size()) != "Add dark mode to settings" {
		t.Fatal("the scan result is not remembered")
	}
}

func TestListCollapsesOneSessionResumedTwice(t *testing.T) {
	root := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", root)
	sessions := filepath.Join(root, "sessions")
	projects := filepath.Join(root, "projects")
	if err := os.MkdirAll(sessions, 0o700); err != nil {
		t.Fatal(err)
	}
	start := ownStart(t)
	cwd := filepath.Join(root, "proj")
	slug := filepath.Join(projects, ProjectSlugForTest(cwd))
	if err := os.MkdirAll(slug, 0o700); err != nil {
		t.Fatal(err)
	}
	var b strings.Builder
	for i := 0; i < 8; i++ {
		b.WriteString(fmt.Sprintf(`{"type":"user","cwd":%q,"sessionId":"same","timestamp":"2026-09-21T10:00:00Z","message":{"role":"user","content":"hello"}}`+"\n", cwd))
	}
	_ = os.WriteFile(filepath.Join(slug, "same.jsonl"), []byte(b.String()), 0o600)

	// The same transcript open in two panes: an older process and a newer one.
	older := fmt.Sprintf(`{"pid":%d,"sessionId":"same","cwd":%q,"procStart":%q,"kind":"interactive","tmux":"work:@1.%%1","startedAt":1000}`, os.Getpid(), cwd, start)
	newer := fmt.Sprintf(`{"pid":%d,"sessionId":"same","cwd":%q,"procStart":%q,"kind":"interactive","tmux":"work:@9.%%9","startedAt":2000}`, os.Getpid(), cwd, start)
	_ = os.WriteFile(filepath.Join(sessions, "one.json"), []byte(older), 0o600)
	_ = os.WriteFile(filepath.Join(sessions, "two.json"), []byte(newer), 0o600)

	list, err := List()
	if err != nil {
		t.Fatal(err)
	}
	same := 0
	var kept Info
	for _, si := range list {
		if si.ID == "same" {
			same++
			kept = si
		}
	}
	if same != 1 {
		t.Fatalf("one session resumed twice must be listed once, got %d: %+v", same, list)
	}
	if kept.Pane != "%9" {
		t.Errorf("the newest process should win, got pane %q", kept.Pane)
	}
}
