package server

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
)

// The working tree as git sees it, for the session's directory: what the agent changed
// since the last commit, file by file, with the diff itself. Read-only: the daemon runs
// only `git status`, `git diff` and `git rev-parse`, and only in a directory that
// belongs to a listed session.
type changedFile struct {
	Path      string `json:"path"`
	From      string `json:"from,omitempty"` // a rename or copy: the previous path
	Status    string `json:"status"`         // porcelain XY, "??" for untracked
	Added     int    `json:"added"`
	Removed   int    `json:"removed"`
	Binary    bool   `json:"binary,omitempty"`
	Diff      string `json:"diff,omitempty"` // unified diff of this file against HEAD
	Truncated bool   `json:"truncated,omitempty"`
	Error     string `json:"error,omitempty"` // git could not produce this file's diff
}

type changesFrame struct {
	proto.Frame
	SessionID string        `json:"session_id"`
	Root      string        `json:"root,omitempty"`
	Branch    string        `json:"branch,omitempty"`
	Files     []changedFile `json:"files"`
	Added     int           `json:"added"`
	Removed   int           `json:"removed"`
	Truncated bool          `json:"truncated,omitempty"` // more files than listed
	NotRepo   bool          `json:"not_repo,omitempty"`
	Error     string        `json:"error,omitempty"` // git failed; Files is not an answer
}

const (
	changesMaxFiles   = 80
	changesFileBytes  = 96 << 10
	changesTotalBytes = 768 << 10
	changesListBytes  = 4 << 20 // status / numstat output: thousands of paths, never a file body
)

func (s *Server) changes(ctx context.Context, w *writer, id string) {
	if !s.hasCap(proto.CapChanges) {
		_ = w.send(ctx, proto.NewError("no_changes", "git is not installed on the computer"))
		return
	}
	si, ok := s.findSession(id)
	if !ok {
		_ = w.send(ctx, proto.NewError("no_session", "that session is no longer here"))
		return
	}
	// One run per session at a time: a tapped Refresh while one runs is answered by it.
	if !s.beginChanges(si.ID) {
		return
	}
	defer s.endChanges(si.ID)
	cctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	f := gitChanges(cctx, si.Cwd)
	f.Frame = proto.Frame{V: proto.Version, Type: proto.TypeChanges}
	f.SessionID = si.ID
	_ = w.send(ctx, f)
}

func (s *Server) beginChanges(id string) bool {
	s.askMu.Lock()
	defer s.askMu.Unlock()
	if s.changesBusy == nil {
		s.changesBusy = map[string]bool{}
	}
	if s.changesBusy[id] {
		return false
	}
	s.changesBusy[id] = true
	return true
}

func (s *Server) endChanges(id string) {
	s.askMu.Lock()
	delete(s.changesBusy, id)
	s.askMu.Unlock()
}

// git runs one read-only git command and returns at most limit bytes of its output.
// Output is read through a pipe with a ceiling, so a 64 MB log the agent left untracked
// costs the daemon 96 KB, not 64 MB; past the ceiling the process is killed. The
// daemon's own environment may carry GIT_DIR/GIT_WORK_TREE from a hook: dropped, or
// every command would run against that repository instead of the session's.
func git(ctx context.Context, dir string, limit int, args ...string) ([]byte, bool, error) {
	if dir == "" {
		return nil, false, errors.New("no directory")
	}
	cmd := exec.CommandContext(ctx, "git", append([]string{"-C", dir, "--no-pager"}, args...)...)
	env := os.Environ()[:0]
	for _, kv := range os.Environ() {
		if strings.HasPrefix(kv, "GIT_DIR=") || strings.HasPrefix(kv, "GIT_WORK_TREE=") || strings.HasPrefix(kv, "GIT_INDEX_FILE=") {
			continue
		}
		env = append(env, kv)
	}
	cmd.Env = append(env, "GIT_OPTIONAL_LOCKS=0", "LC_ALL=C")
	cmd.WaitDelay = 2 * time.Second
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, false, err
	}
	if err := cmd.Start(); err != nil {
		return nil, false, err
	}
	b, rerr := io.ReadAll(io.LimitReader(out, int64(limit)+1))
	over := len(b) > limit
	if over {
		b = b[:limit]
		_ = cmd.Process.Kill()
	}
	werr := cmd.Wait()
	if rerr != nil {
		return b, over, rerr
	}
	if werr != nil && !over {
		var ee *exec.ExitError
		if errors.As(werr, &ee) {
			return b, over, &gitError{code: ee.ExitCode(), stderr: strings.TrimSpace(stderr.String())}
		}
		return b, over, werr
	}
	return b, over, nil
}

// gitError keeps git's exit code and what it printed, which is where "not a git
// repository" and the like are said.
type gitError struct {
	code   int
	stderr string
}

func (e *gitError) Error() string {
	if e.stderr != "" {
		return e.stderr
	}
	return "git exited " + strconv.Itoa(e.code)
}

// gitChanges gathers status, per-file counts and diffs for the repository holding dir.
func gitChanges(ctx context.Context, dir string) changesFrame {
	f := changesFrame{Files: []changedFile{}}
	if dir == "" {
		f.Error = "the session has no directory"
		return f
	}
	root, _, err := git(ctx, dir, 4096, "rev-parse", "--show-toplevel")
	if err != nil {
		if notARepo(err) {
			f.NotRepo = true
		} else {
			f.Error = "git could not open the directory: " + gitErr(err)
		}
		return f
	}
	f.Root = strings.TrimSpace(string(root))
	if b, _, err := git(ctx, f.Root, 4096, "rev-parse", "--abbrev-ref", "HEAD"); err == nil {
		f.Branch = strings.TrimSpace(string(b))
	}
	status, over, err := git(ctx, f.Root, changesListBytes, "status", "--porcelain=v1", "-z", "--untracked-files=all")
	if err != nil {
		f.Error = "git status failed: " + gitErr(err)
		return f
	}
	files := parseStatus(status)
	if over {
		f.Truncated = true
	}
	// Counts against HEAD for tracked changes (staged and not), by path.
	counts := map[string][2]int{}
	binary := map[string]bool{}
	if b, _, err := git(ctx, f.Root, changesListBytes, "diff", "HEAD", "-M", "--numstat", "-z", "--"); err == nil {
		parseNumstat(b, counts, binary)
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Path < files[j].Path })
	if len(files) > changesMaxFiles {
		files = files[:changesMaxFiles]
		f.Truncated = true
	}
	total := 0
	for i := range files {
		cf := &files[i]
		if c, ok := counts[cf.Path]; ok {
			cf.Added, cf.Removed = c[0], c[1]
		}
		cf.Binary = binary[cf.Path]
		var d []byte
		var over bool
		var derr error
		switch {
		case cf.Status == "??":
			// Untracked: the whole file as an addition, the way a reviewer would see it.
			// --no-index exits 1 when the sides differ, which here they always do.
			d, over, derr = git(ctx, f.Root, changesFileBytes, "diff", "--no-index", "--", "/dev/null", cf.Path)
			if isExit(derr, 1) {
				derr = nil
			}
			// The count comes from git, not from a body that may be cut at the ceiling.
			if n, _, err := git(ctx, f.Root, 4096, "diff", "--no-index", "--numstat", "--", "/dev/null", cf.Path); err == nil || isExit(err, 1) {
				c := map[string][2]int{}
				bin := map[string]bool{}
				parseNumstat(n, c, bin)
				for _, v := range c {
					cf.Added, cf.Removed = v[0], v[1]
				}
				for range bin {
					cf.Binary = true
				}
			}
		case cf.From != "":
			// Both sides named, so git can pair them and show the edit, not a whole
			// file added.
			d, over, derr = git(ctx, f.Root, changesFileBytes, "diff", "HEAD", "-M", "--", cf.Path, cf.From)
		default:
			d, over, derr = git(ctx, f.Root, changesFileBytes, "diff", "HEAD", "--", cf.Path)
		}
		if derr != nil && ctx.Err() != nil {
			f.Error = "git took too long; the list is what was gathered before the cut"
			cf.Error = "not gathered"
			f.Files = files[:i+1]
			return f
		}
		if derr != nil {
			cf.Error = "git diff failed: " + gitErr(derr)
			d = nil
		}
		if cf.Binary || binaryDiff(d) {
			cf.Binary = true
			d = nil
		}
		if over {
			cf.Truncated = true
		}
		if total+len(d) > changesTotalBytes {
			d = nil
			cf.Truncated = true
		}
		total += len(d)
		cf.Diff = string(d)
		f.Added += cf.Added
		f.Removed += cf.Removed
	}
	f.Files = files
	return f
}

// binaryDiff is git's own "Binary files a/x and b/x differ" line; content lines carry a
// leading space, "+", "-", "@" or "\", so a text file mentioning the phrase does not match.
func binaryDiff(d []byte) bool {
	for _, line := range bytes.Split(d, []byte("\n")) {
		if bytes.HasPrefix(line, []byte("Binary files ")) {
			return true
		}
	}
	return false
}

func notARepo(err error) bool {
	var ge *gitError
	return errors.As(err, &ge) && ge.code == 128 && strings.Contains(strings.ToLower(ge.stderr), "not a git repository")
}

func isExit(err error, code int) bool {
	var ge *gitError
	return errors.As(err, &ge) && ge.code == code
}

func gitErr(err error) string {
	return err.Error()
}

// parseStatus reads `git status --porcelain=v1 -z`: "XY path\0", with a rename or copy
// followed by the original path as a second NUL-terminated field.
func parseStatus(b []byte) []changedFile {
	var out []changedFile
	fields := bytes.Split(b, []byte{0})
	for i := 0; i < len(fields); i++ {
		e := fields[i]
		if len(e) < 4 {
			continue
		}
		st, path := string(e[:2]), string(e[3:])
		cf := changedFile{Path: path, Status: st}
		if st[0] == 'R' || st[0] == 'C' || st[1] == 'R' || st[1] == 'C' {
			i++ // the original path follows
			if i < len(fields) {
				cf.From = string(fields[i])
			}
		}
		out = append(out, cf)
	}
	return out
}

// parseNumstat reads `git diff --numstat -z`: "added\tremoved\tpath\0", "-" for binary;
// a rename is "added\tremoved\t\0old\0new\0" and is keyed by the new path.
func parseNumstat(b []byte, counts map[string][2]int, binary map[string]bool) {
	fields := bytes.Split(b, []byte{0})
	for i := 0; i < len(fields); i++ {
		parts := strings.SplitN(string(fields[i]), "\t", 3)
		if len(parts) != 3 {
			continue
		}
		path := parts[2]
		if path == "" && i+2 < len(fields) {
			path = string(fields[i+2]) // old, then new
			i += 2
		}
		if parts[0] == "-" || parts[1] == "-" {
			binary[path] = true
			continue
		}
		a, _ := strconv.Atoi(parts[0])
		r, _ := strconv.Atoi(parts[1])
		counts[path] = [2]int{a, r}
	}
}
