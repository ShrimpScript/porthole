package server

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"slices"
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

func write(t *testing.T, dir, rel, body string) {
	t.Helper()
	p := filepath.Join(dir, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

// In a repository the list is git's: tracked and untracked files, not ignored ones, and
// relative to the session's directory even when that is below the repository's top.
func TestFilesListInARepository(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("no git")
	}
	dir := t.TempDir()
	run(t, dir, "git", "init", "-q", "-b", "main")
	write(t, dir, ".gitignore", "*.log\n")
	write(t, dir, "app/main.go", "package main\n")
	write(t, dir, "app/util/strings.go", "package util\n")
	write(t, dir, "README.md", "hi\n")
	run(t, dir, "git", "add", ".")
	run(t, dir, "git", "commit", "-q", "-m", "init")
	write(t, dir, "app/new.go", "package main\n") // untracked
	write(t, dir, "app/debug.log", "noise\n")     // ignored

	paths, truncated, err := listFiles(context.Background(), dir)
	if err != nil || truncated {
		t.Fatalf("list: %v %v", err, truncated)
	}
	sort.Strings(paths)
	want := []string{".gitignore", "README.md", "app/main.go", "app/new.go", "app/util/strings.go"}
	if !reflect.DeepEqual(paths, want) {
		t.Fatalf("paths:\n got %q\nwant %q", paths, want)
	}

	sub, _, err := listFiles(context.Background(), filepath.Join(dir, "app"))
	sort.Strings(sub)
	if err != nil || !reflect.DeepEqual(sub, []string{"main.go", "new.go", "util/strings.go"}) {
		t.Fatalf("below the top: %q %v", sub, err)
	}
}

// Outside a repository: a walk that leaves out hidden folders and node_modules, and stops
// at a depth.
func TestFilesListOutsideARepository(t *testing.T) {
	dir := t.TempDir()
	write(t, dir, "notes.txt", "x")
	write(t, dir, ".git-credentials", "x") // a home directory's hidden files are never offered
	write(t, dir, "src/a.py", "x")
	write(t, dir, ".cache/big.bin", "x")
	write(t, dir, "node_modules/lib/index.js", "x")
	write(t, dir, "d1/d2/d3/d4/d5/shallow.txt", "x")
	write(t, dir, "d1/d2/d3/d4/d5/d6/deep.txt", "x")

	paths, _, err := walkFiles(context.Background(), dir)
	if err != nil {
		t.Fatal(err)
	}
	sort.Strings(paths)
	want := []string{"d1/d2/d3/d4/d5/shallow.txt", "notes.txt", "src/a.py"}
	if !reflect.DeepEqual(paths, want) {
		t.Fatalf("walk:\n got %q\nwant %q", paths, want)
	}
}

func TestFilesRanking(t *testing.T) {
	paths := []string{
		"docs/server-notes.md",
		"internal/server/server.go",
		"internal/server/server_test.go",
		"cmd/portholed/main.go",
		"internal/observer/observe.go",
		"site/assets/intro/intro.js",
		"serve.sh",
	}
	got := rankFiles(paths, "serve")
	want := []string{
		"serve.sh",                       // the name starts with it; shortest path first
		"docs/server-notes.md",           // ...
		"internal/server/server.go",      // ...
		"internal/server/server_test.go", // ...
		"internal/observer/observe.go",   // letters in order only
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("serve:\n got %q\nwant %q", got, want)
	}
	if got := rankFiles(paths, "intro/in"); !reflect.DeepEqual(got, []string{"site/assets/intro/intro.js"}) {
		t.Fatalf("a path fragment: %q", got)
	}
	// Letters in order count within a file's name, not scattered along its path.
	if got := rankFiles([]string{"src/game/rivalry.ts", "cmd/srv/server.go"}, "srvgo"); !reflect.DeepEqual(got, []string{"cmd/srv/server.go"}) {
		t.Fatalf("letters in order: %q", got)
	}
	if got := rankFiles([]string{"src/game/rivalry.ts"}, "serv"); len(got) != 0 {
		t.Fatalf("scattered letters matched: %q", got)
	}
	if got := rankFiles(paths, "zzz"); len(got) != 0 {
		t.Fatalf("no match should be empty, got %q", got)
	}
}

// A file deleted in the working tree is still in git's index; it is not offered. With
// nothing typed, the newest files come first.
func TestFilesPickDropsGoneAndOrdersRecent(t *testing.T) {
	dir := t.TempDir()
	write(t, dir, "old.txt", "x")
	write(t, dir, "mid.txt", "x")
	write(t, dir, "new.txt", "x")
	now := time.Now()
	os.Chtimes(filepath.Join(dir, "old.txt"), now, now.Add(-3*time.Hour))
	os.Chtimes(filepath.Join(dir, "mid.txt"), now, now.Add(-2*time.Hour))
	os.Chtimes(filepath.Join(dir, "new.txt"), now, now.Add(-1*time.Hour))
	paths := []string{"old.txt", "gone.txt", "mid.txt", "new.txt"}

	if got := pickFiles(dir, paths, "", 10); !reflect.DeepEqual(got, []string{"new.txt", "mid.txt", "old.txt"}) {
		t.Fatalf("recent: %q", got)
	}
	if got := pickFiles(dir, paths, "@txt", 2); len(got) != 2 || slices.Contains(got, "gone.txt") {
		t.Fatalf("query with a limit: %q", got)
	}
}

// A home directory is listed one level deep: its own files, not everything below.
func TestFilesHomeIsListedOneLevelDeep(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	write(t, home, "todo.txt", "x")
	write(t, home, "Downloads/big.iso", "x")
	write(t, home, "Library/Containers/app/data.db", "x")
	paths, _, err := listFiles(context.Background(), home)
	if err != nil || !reflect.DeepEqual(paths, []string{"todo.txt"}) {
		t.Fatalf("home: %q %v", paths, err)
	}
}

// A session directory reached through a symlink is walked as the real one.
func TestFilesThroughASymlinkedDirectory(t *testing.T) {
	root := t.TempDir()
	write(t, root, "real/a.txt", "x")
	link := filepath.Join(root, "link")
	if err := os.Symlink(filepath.Join(root, "real"), link); err != nil {
		t.Skip("no symlinks here")
	}
	paths, _, err := listFiles(context.Background(), link)
	if err != nil || !reflect.DeepEqual(paths, []string{"a.txt"}) {
		t.Fatalf("through the link: %q %v", paths, err)
	}
}

// A repository inside the session's repository is git's "inner/", a folder, not a file.
func TestFilesSkipANestedRepository(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("no git")
	}
	dir := t.TempDir()
	run(t, dir, "git", "init", "-q", "-b", "main")
	write(t, dir, "top.go", "package top\n")
	inner := filepath.Join(dir, "inner")
	write(t, inner, "x.go", "package x\n")
	run(t, inner, "git", "init", "-q", "-b", "main")
	paths, _, err := listFiles(context.Background(), dir)
	if err != nil || !reflect.DeepEqual(paths, []string{"top.go"}) {
		t.Fatalf("nested: %q %v", paths, err)
	}
}

// A folder that never answers holds up only its own askers, and only as long as they
// wait; another folder is listed meanwhile.
func TestFilesAStuckFolderStopsOnlyItself(t *testing.T) {
	release := make(chan struct{})
	defer close(release)
	s := &Server{listFiles: func(ctx context.Context, dir string) ([]string, bool, error) {
		if strings.HasSuffix(dir, "stuck") {
			<-release // a read that ignores every deadline
		}
		return listFiles(ctx, dir)
	}}
	root := t.TempDir()
	write(t, root, "fine/ok.txt", "x")
	stuck := filepath.Join(root, "stuck")

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	began := time.Now()
	if _, err := s.fileList(ctx, stuck); err == nil {
		t.Fatal("a stuck folder answered")
	}
	if waited := time.Since(began); waited > 2*time.Second {
		t.Fatalf("the asker waited %v, past its own deadline", waited)
	}
	l, err := s.fileList(context.Background(), filepath.Join(root, "fine"))
	if err != nil || !reflect.DeepEqual(l.paths, []string{"ok.txt"}) {
		t.Fatalf("another folder while one is stuck: %+v %v", l, err)
	}
}

// End to end over the socket: a paired phone asks for files in a known session's
// directory and gets them, matched to its query.
func TestFilesGetOverTheSocket(t *testing.T) {
	root := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", root)
	cwd := filepath.Join(root, "proj")
	write(t, cwd, "src/server.go", "package main\n")
	write(t, cwd, "src/client.go", "package main\n")
	slug := filepath.Join(root, "projects", transcript.ProjectSlug(cwd))
	write(t, slug, "sess1.jsonl", fmt.Sprintf(`{"type":"user","cwd":%q,"sessionId":"sess1","timestamp":"2026-09-28T10:00:00Z","message":{"role":"user","content":"hello"}}`+"\n", cwd))
	// The session is this test's own process, as the CLI's registry would name it.
	start, ok := platform.ProcStart(os.Getpid())
	if !ok || start == "" {
		t.Skip("cannot read this process's start time here")
	}
	write(t, filepath.Join(root, "sessions"), "one.json", fmt.Sprintf(`{"pid":%d,"sessionId":"sess1","cwd":%q,"procStart":%q,"kind":"interactive","startedAt":1000}`, os.Getpid(), cwd, start))

	_, hs, st := newTestServer(t, fakeResolver{peer: peer()})
	if err := st.Add(&store.Device{NodeID: testNode, Name: "pixel-test"}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, wsURL(hs, ""), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	_, data, err := c.Read(ctx) // hello
	if err != nil {
		t.Fatal(err)
	}
	var h proto.Hello
	_ = json.Unmarshal(data, &h)
	if !slices.Contains(h.Caps, proto.CapFiles) {
		t.Fatalf("hello does not advertise %q: %v", proto.CapFiles, h.Caps)
	}
	_ = c.Write(ctx, websocket.MessageText, []byte(`{"type":"files.get","session_id":"sess1","query":"serv"}`))
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("no files answer: %v", err)
		}
		var f filesFrame
		if json.Unmarshal(data, &f) != nil || f.Type != proto.TypeFiles {
			continue // other frames the daemon sends on its own
		}
		if f.SessionID != "sess1" || f.Query != "serv" || f.Error != "" || !reflect.DeepEqual(f.Files, []string{"src/server.go"}) {
			t.Fatalf("answer: %+v", f)
		}
		return
	}
}
