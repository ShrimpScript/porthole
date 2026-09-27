package server

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func run(t *testing.T, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command(args[0], args[1:]...)
	cmd.Dir = dir
	cmd.Env = append(os.Environ(), "GIT_AUTHOR_NAME=t", "GIT_AUTHOR_EMAIL=t@x", "GIT_COMMITTER_NAME=t", "GIT_COMMITTER_EMAIL=t@x")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%v: %v %s", args, err, out)
	}
}

func TestGitChanges(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("no git")
	}
	dir := t.TempDir()
	run(t, dir, "git", "init", "-q", "-b", "main")
	os.WriteFile(filepath.Join(dir, "a.txt"), []byte("one\ntwo\nthree\n"), 0o644)
	os.WriteFile(filepath.Join(dir, "gone.txt"), []byte("bye\n"), 0o644)
	run(t, dir, "git", "add", ".")
	run(t, dir, "git", "commit", "-q", "-m", "init")
	os.WriteFile(filepath.Join(dir, "a.txt"), []byte("one\n2\nthree\nfour\n"), 0o644) // modified
	os.WriteFile(filepath.Join(dir, "new.txt"), []byte("hello\nworld\n"), 0o644)      // untracked
	os.Remove(filepath.Join(dir, "gone.txt"))                                         // deleted
	sub := filepath.Join(dir, "sub")
	os.MkdirAll(sub, 0o755)

	f := gitChanges(context.Background(), sub) // any directory inside the repo
	if f.NotRepo || f.Root != mustReal(t, dir) || f.Branch != "main" {
		t.Fatalf("root/branch: %+v", f)
	}
	byPath := map[string]changedFile{}
	for _, cf := range f.Files {
		byPath[cf.Path] = cf
	}
	if len(f.Files) != 3 {
		t.Fatalf("files: %+v", f.Files)
	}
	a := byPath["a.txt"]
	if a.Status != " M" || a.Added != 2 || a.Removed != 1 || !strings.Contains(a.Diff, "-two") || !strings.Contains(a.Diff, "+four") {
		t.Errorf("a.txt: %+v", a)
	}
	n := byPath["new.txt"]
	if n.Status != "??" || n.Added != 2 || !strings.Contains(n.Diff, "+hello") {
		t.Errorf("new.txt: %+v", n)
	}
	g := byPath["gone.txt"]
	if g.Status != " D" || g.Removed != 1 || !strings.Contains(g.Diff, "-bye") {
		t.Errorf("gone.txt: %+v", g)
	}
	if f.Added != 4 || f.Removed != 2 {
		t.Errorf("totals +%d -%d", f.Added, f.Removed)
	}
}

func TestGitChangesOutsideARepo(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("no git")
	}
	f := gitChanges(context.Background(), t.TempDir())
	if !f.NotRepo || f.Files == nil {
		t.Fatalf("%+v", f)
	}
}

func TestParseStatusRename(t *testing.T) {
	b := []byte("R  new.txt\x00old.txt\x00 M b.txt\x00?? c.txt\x00")
	got := parseStatus(b)
	if len(got) != 3 || got[0].Path != "new.txt" || got[0].Status != "R " || got[1].Path != "b.txt" || got[2].Status != "??" {
		t.Fatalf("%+v", got)
	}
}

func mustReal(t *testing.T, p string) string {
	r, err := filepath.EvalSymlinks(p)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestGitChangesRenameBinaryAndCaps(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("no git")
	}
	dir := t.TempDir()
	run(t, dir, "git", "init", "-q", "-b", "main")
	var body strings.Builder
	for i := 0; i < 40; i++ {
		body.WriteString("line of a long enough file to be paired as a rename\n")
	}
	os.WriteFile(filepath.Join(dir, "old.txt"), []byte(body.String()), 0o644)
	os.WriteFile(filepath.Join(dir, "words.txt"), []byte("Binary files are fun\nsecond\n"), 0o644)
	os.WriteFile(filepath.Join(dir, "pic.bin"), []byte{0, 1, 2, 3, 0, 255}, 0o644)
	run(t, dir, "git", "add", ".")
	run(t, dir, "git", "commit", "-q", "-m", "init")
	run(t, dir, "git", "mv", "old.txt", "renamed.txt")
	os.WriteFile(filepath.Join(dir, "renamed.txt"), []byte(body.String()+"tail\n"), 0o644)
	run(t, dir, "git", "add", "renamed.txt")
	os.WriteFile(filepath.Join(dir, "words.txt"), []byte("Binary files are fun\nsecond\nthird\n"), 0o644)
	os.WriteFile(filepath.Join(dir, "pic.bin"), []byte{9, 9, 9, 0, 1}, 0o644)
	big := make([]byte, 300<<10)
	for i := range big {
		big[i] = 'x'
		if i%80 == 79 {
			big[i] = '\n'
		}
	}
	os.WriteFile(filepath.Join(dir, "huge.log"), big, 0o644)

	f := gitChanges(context.Background(), dir)
	if f.Error != "" || f.NotRepo {
		t.Fatalf("%+v", f)
	}
	byPath := map[string]changedFile{}
	for _, cf := range f.Files {
		byPath[cf.Path] = cf
	}
	r := byPath["renamed.txt"]
	if r.From != "old.txt" || r.Added != 1 || r.Removed != 0 || !strings.Contains(r.Diff, "+tail") || strings.Count(r.Diff, "\n+") > 3 {
		t.Errorf("rename should show the one-line edit, not a whole file added: from=%q +%d -%d diff=%q", r.From, r.Added, r.Removed, r.Diff)
	}
	w := byPath["words.txt"]
	if w.Binary || !strings.Contains(w.Diff, "+third") {
		t.Errorf("a text file mentioning 'Binary files' is not binary: %+v", w)
	}
	pic := byPath["pic.bin"]
	if !pic.Binary || pic.Diff != "" {
		t.Errorf("pic.bin: %+v", pic)
	}
	h := byPath["huge.log"]
	if !h.Truncated || len(h.Diff) > changesFileBytes || h.Added < 3000 {
		t.Errorf("huge untracked file must be capped at the ceiling and still counted: truncated=%v len=%d added=%d", h.Truncated, len(h.Diff), h.Added)
	}
}

func TestGitChangesReportsFailure(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("no git")
	}
	f := gitChanges(context.Background(), "")
	if f.Error == "" {
		t.Fatal("a blank directory must be an error, not a clean tree")
	}
	gone := filepath.Join(t.TempDir(), "gone")
	f = gitChanges(context.Background(), gone)
	if f.Error == "" || f.NotRepo {
		t.Fatalf("a missing directory is an error, not 'not a repository': %+v", f)
	}
}
