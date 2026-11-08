package inbox

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// DecisionFiles are where a project may already keep its decisions, in the order they are
// preferred. A project with none of them gets the first.
var DecisionFiles = []string{"DECISIONS.md", "docs/decisions.md", "docs/DECISIONS.md", "decisions.md", ".claude/decisions.md"}

var answeredRe = regexp.MustCompile(`<!-- inbox:([0-9a-f]{10}) -->`)

// answeredIDs are the items already answered in this project.
func answeredIDs(dir string) map[string]bool {
	set := map[string]bool{}
	for _, rel := range DecisionFiles {
		if !regular(filepath.Join(dir, rel)) {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, rel))
		if err != nil {
			continue
		}
		for _, m := range answeredRe.FindAllSubmatch(b, -1) {
			set[string(m[1])] = true
		}
	}
	return set
}

// DecisionsFile is the file this project's answers go to, relative to it.
func DecisionsFile(dir string) string {
	for _, rel := range DecisionFiles {
		if _, err := os.Lstat(filepath.Join(dir, rel)); err == nil {
			return rel // a link here is refused when written, not passed over for another file
		}
	}
	return DecisionFiles[0]
}

// safeTarget checks that path is a plain file inside dir, or nothing yet in a folder inside
// dir: never a link, and never somewhere a link in the path leads out of the project.
func safeTarget(dir, path string) error {
	st, err := os.Lstat(path)
	switch {
	case err == nil && !st.Mode().IsRegular():
		return fmt.Errorf("%s is not a plain file; nothing was written", filepath.Base(path))
	case err != nil && !errors.Is(err, os.ErrNotExist):
		return err
	}
	root, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return err
	}
	parent, err := filepath.EvalSymlinks(filepath.Dir(path))
	if err != nil {
		return err
	}
	if rel, err := filepath.Rel(root, parent); err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return fmt.Errorf("%s leads out of the project; nothing was written", filepath.Dir(path))
	}
	return nil
}

// unmark keeps text from carrying an HTML comment: a planted "<!-- inbox:… -->" would mark
// another question answered, and an open "<!--" would hide the rest of the file.
func unmark(s string) string {
	s = strings.ReplaceAll(s, "<!--", "<! --")
	return strings.ReplaceAll(s, "-->", "-- >")
}

// Record appends the answer to the project's decisions file, creating the file if the
// project has none, and returns the file's path relative to the project. Nothing else in
// the project is touched.
func Record(p Project, it Item, answer string, now time.Time) (string, error) {
	answer = strings.TrimSpace(answer)
	if answer == "" {
		return "", errors.New("the answer is empty")
	}
	rel := DecisionsFile(p.Dir)
	path := filepath.Join(p.Dir, rel)
	if err := safeTarget(p.Dir, path); err != nil {
		return "", err
	}
	// O_NOFOLLOW: a link swapped in since the check is refused, not followed.
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE|syscall.O_NOFOLLOW, 0o644)
	if err != nil {
		return "", err
	}
	defer f.Close()
	// The daemon and `portholed inbox answer` are separate processes: the lock and the
	// check under it keep one question from being answered twice.
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX); err != nil {
		return "", err
	}
	defer syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
	old, err := io.ReadAll(f)
	if err != nil {
		return "", err
	}
	if bytes.Contains(old, []byte("<!-- inbox:"+it.ID+" -->")) {
		return "", errors.New("that question was answered already")
	}
	var buf bytes.Buffer
	if len(old) == 0 {
		buf.WriteString("# Decisions\n\nAnswers given from the Porthole inbox, newest last.\n")
	} else if !bytes.HasSuffix(old, []byte("\n")) {
		buf.WriteString("\n")
	}
	fmt.Fprintf(&buf, "\n- %s, from %s: %s\n  Answer: %s <!-- inbox:%s -->\n",
		now.Format("2006-01-02"), it.File, unmark(it.Text), strings.ReplaceAll(unmark(answer), "\n", "\n  "), it.ID)
	if _, err := f.Write(buf.Bytes()); err != nil {
		return "", err
	}
	return rel, nil
}

// Message is what a session working in the project is told when the person answers.
func Message(it Item, answer, decisions string) string {
	return fmt.Sprintf("Answer from the Porthole inbox to %q (%s line %d): %s\n(Recorded in %s.)",
		it.Text, it.File, it.Line, strings.TrimSpace(answer), decisions)
}

// blameTimes maps each committed line of a note to when it was written. Lines not yet
// committed, and notes outside git, are left out.
func blameTimes(dir, rel string) map[int]time.Time {
	out := map[int]time.Time{}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if !gitUsable() {
		return out
	}
	// Read-only: no index refresh, no lock, no fsmonitor command the project configured.
	cmd := exec.CommandContext(ctx, "git", "-c", "core.fsmonitor=false", "-C", dir, "blame", "--line-porcelain", "--", rel)
	cmd.Env = append(os.Environ(), "GIT_OPTIONAL_LOCKS=0")
	b, err := cmd.Output()
	if err != nil {
		return out
	}
	line := 0
	sc := bufio.NewScanner(bytes.NewReader(b))
	sc.Buffer(make([]byte, 64*1024), 4<<20)
	for sc.Scan() {
		t := sc.Text()
		switch {
		case strings.HasPrefix(t, "\t"):
			line = 0
		case line == 0:
			// "<sha> <orig line> <final line> [count]" opens every line's block.
			f := strings.Fields(t)
			if len(f) >= 3 {
				line, _ = strconv.Atoi(f[2])
				if strings.Trim(f[0], "0") == "" {
					line = -1 // not committed yet
				}
			}
		case line > 0 && strings.HasPrefix(t, "author-time "):
			if sec, err := strconv.ParseInt(strings.TrimPrefix(t, "author-time "), 10, 64); err == nil {
				out[line] = time.Unix(sec, 0)
			}
		}
	}
	return out
}

var gitOK = sync.OnceValue(func() bool {
	if _, err := exec.LookPath("git"); err != nil {
		return false
	}
	if runtime.GOOS == "darwin" {
		// Without the Command Line Tools, /usr/bin/git opens an install dialog on the Mac's screen.
		return exec.Command("xcode-select", "-p").Run() == nil
	}
	return true
})

func gitUsable() bool { return gitOK() }
