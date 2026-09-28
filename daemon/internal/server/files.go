package server

import (
	"bytes"
	"context"
	"errors"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
)

// Files for the composer's @ picker: paths in the session's directory, ranked against
// what has been typed after the "@". Names only, never contents, and only for a directory
// that belongs to a listed session. In a git repository the list is git's own - tracked
// files and untracked ones that are not ignored - which is what the CLI's own @ offers;
// outside one, a bounded walk.

type filesFrame struct {
	proto.Frame
	SessionID string   `json:"session_id"`
	Query     string   `json:"query"`               // echoed, so the phone can match an answer to what it asked
	Files     []string `json:"files"`               // relative to the session's directory, with forward slashes
	Truncated bool     `json:"truncated,omitempty"` // the directory holds more files than were read
	Error     string   `json:"error,omitempty"`
}

const (
	filesMax       = 40      // answers to one query
	filesListMax   = 50000   // paths read from one directory
	filesListBytes = 8 << 20 // git ls-files output read at most
	filesWalkDepth = 6       // folders deep, outside a repository
	filesCacheFor  = 15 * time.Second
	filesListFor   = 8 * time.Second // one listing, at most
	filesRecentMax = 5000            // an empty query ranks by modification time: files looked at, at most
	filesQueryMax  = 200
)

// Folders a walk outside a repository never enters; in a repository, .gitignore decides.
var filesSkipDirs = map[string]bool{"node_modules": true, "__pycache__": true}

// fileList is one directory's paths, listed once for everyone asking and kept for a few
// seconds: a person types a few letters after the "@", and each one asks again. done is
// closed when the listing is over, with files or with an error.
type fileList struct {
	started   time.Time
	done      chan struct{}
	at        time.Time // when it finished
	paths     []string
	truncated bool
	err       error
}

func (s *Server) files(ctx context.Context, w *writer, id, query string) {
	si, ok := s.findSession(id)
	if !ok {
		_ = w.send(ctx, proto.NewError("no_session", "that session is no longer here"))
		return
	}
	if len(query) > filesQueryMax {
		query = query[:filesQueryMax]
	}
	f := filesFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeFiles}, SessionID: si.ID, Query: query, Files: []string{}}
	if si.Cwd == "" {
		f.Error = "the session has no directory"
		_ = w.send(ctx, f)
		return
	}
	cctx, cancel := context.WithTimeout(ctx, filesListFor)
	defer cancel()
	l, err := s.fileList(cctx, si.Cwd)
	if err != nil {
		f.Error = "could not list the folder: " + err.Error()
	} else {
		f.Files = pickFiles(si.Cwd, l.paths, query, filesMax)
		f.Truncated = l.truncated
	}
	_ = w.send(ctx, f)
}

// fileList lists dir once for everyone asking, and reuses the answer for filesCacheFor.
// The listing runs on its own, with its own deadline; a caller waits only as long as its
// own context allows. A read can block past any deadline - a Mac holding a folder behind
// a permission prompt nobody is there to answer, a network mount that went away - and
// then only this one folder stops answering, not the picker in every other session.
func (s *Server) fileList(ctx context.Context, dir string) (*fileList, error) {
	s.filesMu.Lock()
	if s.filesCache == nil {
		s.filesCache = map[string]*fileList{}
	}
	for d, old := range s.filesCache {
		select {
		case <-old.done:
			if time.Since(old.at) >= filesCacheFor {
				delete(s.filesCache, d)
			}
		default:
		}
	}
	l := s.filesCache[dir]
	if l == nil {
		list := s.listFiles // a test's folder that never answers
		if list == nil {
			list = listFiles
		}
		l = &fileList{started: time.Now(), done: make(chan struct{})}
		s.filesCache[dir] = l
		go func() {
			lctx, cancel := context.WithTimeout(context.Background(), filesListFor)
			defer cancel()
			paths, truncated, err := list(lctx, dir)
			l.paths, l.truncated, l.err, l.at = paths, truncated, err, time.Now()
			close(l.done)
		}()
	}
	s.filesMu.Unlock()
	select {
	case <-l.done:
		return l, l.err
	default:
	}
	if time.Since(l.started) > filesListFor+time.Second {
		return nil, errors.New("the folder is not answering") // stuck: waiting again would not help
	}
	select {
	case <-l.done:
		return l, l.err
	case <-ctx.Done():
		return nil, errors.New("the folder took too long to list")
	}
}

// gitUsable says whether git can be run without anyone at the computer. On a Mac without
// the developer tools, /usr/bin/git is a stub that puts up an install dialog on the Mac's
// screen each time it runs; xcode-select says whether they are there without one.
var gitUsable = sync.OnceValue(func() bool {
	p, err := exec.LookPath("git")
	if err != nil {
		return false
	}
	if runtime.GOOS == "darwin" && p == "/usr/bin/git" {
		return exec.Command("xcode-select", "-p").Run() == nil
	}
	return true
})

// listFiles returns the files under dir, relative to it: git's list inside a
// repository, a bounded walk outside one or when git cannot say.
func listFiles(ctx context.Context, dir string) ([]string, bool, error) {
	// A directory reached through a symlink is walked as the real one (/tmp on a Mac).
	if real, err := filepath.EvalSymlinks(dir); err == nil {
		dir = real
	}
	if gitUsable() {
		out, over, gerr := git(ctx, dir, filesListBytes, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
		if gerr == nil {
			var paths []string
			seen := map[string]bool{}
			for _, b := range bytes.Split(out, []byte{0}) {
				p := string(b)
				// A path in conflict is listed once per stage; a repository inside this
				// one is listed as its folder ("inner/"), which is not a file.
				if p == "" || seen[p] || strings.HasSuffix(p, "/") {
					continue
				}
				seen[p] = true
				paths = append(paths, p)
			}
			truncated := over
			if over && len(paths) > 0 {
				paths = paths[:len(paths)-1] // the output was cut, most likely inside this one
			}
			if len(paths) > filesListMax {
				paths, truncated = paths[:filesListMax], true
			}
			return paths, truncated, nil
		}
		// Not a repository, or git could not say (a directory it does not trust, a tree
		// too big for the time): the folder's own files will do.
	}
	return walkFiles(ctx, dir)
}

func walkFiles(ctx context.Context, dir string) ([]string, bool, error) {
	var paths []string
	truncated := false
	depth := filesWalkDepth
	if isHomeOrRoot(dir) {
		depth = 1 // everything the person owns: only what sits in it, not every folder below
	}
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			if p == dir {
				return err
			}
			return nil // a folder it may not read: its neighbours still count
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if p == dir {
			return nil
		}
		rel, _ := filepath.Rel(dir, p)
		if d.IsDir() {
			if strings.HasPrefix(d.Name(), ".") || filesSkipDirs[d.Name()] ||
				strings.Count(rel, string(filepath.Separator)) >= depth-1 {
				return filepath.SkipDir
			}
			return nil
		}
		// Hidden files too, not just hidden folders: in a home directory they are
		// credentials and shell history, never what a prompt is about.
		if !d.Type().IsRegular() || strings.HasPrefix(d.Name(), ".") {
			return nil
		}
		if len(paths) >= filesListMax {
			truncated = true
			return filepath.SkipAll
		}
		paths = append(paths, filepath.ToSlash(rel))
		return nil
	})
	if errors.Is(err, context.DeadlineExceeded) && len(paths) > 0 {
		return paths, true, nil // what was found in the time is still an answer
	}
	return paths, truncated, err
}

func isHomeOrRoot(dir string) bool {
	if dir == string(filepath.Separator) {
		return true
	}
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return false
	}
	if real, err := filepath.EvalSymlinks(home); err == nil {
		home = real
	}
	return filepath.Clean(dir) == filepath.Clean(home)
}

// pickFiles answers one query: the best matches, or with nothing typed yet, the files
// changed most recently. Files that have gone since the list was read are dropped.
func pickFiles(dir string, paths []string, query string, limit int) []string {
	q := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(query, "@")))
	if q == "" {
		return recentFiles(dir, paths, limit)
	}
	out := make([]string, 0, limit)
	for _, p := range rankFiles(paths, q) {
		if _, err := os.Stat(filepath.Join(dir, filepath.FromSlash(p))); err != nil {
			continue // deleted in the working tree, still in git's index
		}
		out = append(out, p)
		if len(out) == limit {
			break
		}
	}
	return out
}

// rankFiles orders the paths that match q (lower case) by how well: the file's own name
// starting with it, then containing it, then the path containing it, then q's letters in
// order within the file's name ("srvgo" for server.go). A q with a slash is a piece of a
// path, matched as it is typed. Within a rank, shorter paths first.
func rankFiles(paths []string, q string) []string {
	type hit struct {
		p    string
		rank int
	}
	var hits []hit
	for _, p := range paths {
		lp := strings.ToLower(p)
		base := lp[strings.LastIndexByte(lp, '/')+1:]
		rank := -1
		switch {
		case strings.HasPrefix(base, q):
			rank = 0
		case strings.Contains(base, q):
			rank = 1
		case strings.Contains(lp, q):
			rank = 2
		case !strings.Contains(q, "/") && inOrder(base, q):
			rank = 3
		}
		if rank >= 0 {
			hits = append(hits, hit{p, rank})
		}
	}
	sort.Slice(hits, func(i, j int) bool {
		a, b := hits[i], hits[j]
		if a.rank != b.rank {
			return a.rank < b.rank
		}
		if len(a.p) != len(b.p) {
			return len(a.p) < len(b.p)
		}
		return a.p < b.p
	})
	out := make([]string, len(hits))
	for i, h := range hits {
		out[i] = h.p
	}
	return out
}

// inOrder reports whether q's runes appear in s in order ("srvgo" in "server.go").
func inOrder(s, q string) bool {
	want := []rune(q)
	i := 0
	for _, r := range s {
		if i < len(want) && r == want[i] {
			i++
		}
	}
	return i == len(want)
}

// recentFiles is the answer before anything is typed: the files last written, newest
// first - usually the ones being worked on.
func recentFiles(dir string, paths []string, limit int) []string {
	type stamped struct {
		p string
		t time.Time
	}
	var fs []stamped
	for i, p := range paths {
		if i == filesRecentMax {
			break
		}
		if st, err := os.Stat(filepath.Join(dir, filepath.FromSlash(p))); err == nil {
			fs = append(fs, stamped{p, st.ModTime()})
		}
	}
	sort.SliceStable(fs, func(i, j int) bool { return fs[i].t.After(fs[j].t) })
	out := make([]string, 0, limit)
	for _, f := range fs {
		out = append(out, f.p)
		if len(out) == limit {
			break
		}
	}
	return out
}
