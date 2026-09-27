//go:build darwin

package platform

import (
	"os"
	"os/exec"
	"strconv"
	"strings"
)

// EphemeralFrom is where macOS hands out ports; a listener up there was not chosen.
const EphemeralFrom = 49152

// ProcStart is a process's start time exactly as Claude Code records it in its session
// registry on macOS: `LC_ALL=C TZ=UTC ps -o lstart= -p PID`, trimmed. ok is false when
// there is no such process.
func ProcStart(pid int) (string, bool) {
	cmd := exec.Command("ps", "-o", "lstart=", "-p", strconv.Itoa(pid))
	cmd.Env = append(os.Environ(), "LC_ALL=C", "TZ=UTC")
	out, err := cmd.Output()
	s := strings.TrimSpace(string(out))
	if err != nil || s == "" {
		return "", false
	}
	return s, true
}

// HasChildren reports whether any process names pid as its parent. pgrep exits 1 for
// none; anything else it cannot answer counts as busy, the safe answer.
func HasChildren(pid int) bool {
	err := exec.Command("pgrep", "-P", strconv.Itoa(pid)).Run()
	if err == nil {
		return true
	}
	if ee, ok := err.(*exec.ExitError); ok && ee.ExitCode() == 1 {
		return false
	}
	return true
}

// CanListListeners reports whether Listeners can see anything here.
func CanListListeners() bool {
	_, err := exec.LookPath("lsof")
	return err == nil
}

// Listeners lists this user's TCP listeners on loopback or every address, port 1024 or
// more, from lsof - the one tool on a Mac that maps a socket to its process.
func Listeners() []Listener {
	out, err := exec.Command("lsof", "-nP", "-a", "-u", strconv.Itoa(os.Getuid()),
		"-iTCP", "-sTCP:LISTEN", "-F", "pcn").Output()
	if err != nil && len(out) == 0 {
		return nil
	}
	ls := parseLsof(string(out))
	for i := range ls {
		if c, err := exec.Command("ps", "-o", "command=", "-p", strconv.Itoa(ls[i].PID)).Output(); err == nil {
			ls[i].Cmdline = []byte(strings.Join(strings.Fields(string(c)), "\x00"))
		}
	}
	return ls
}
