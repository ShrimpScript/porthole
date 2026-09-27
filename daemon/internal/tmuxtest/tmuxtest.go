// Package tmuxtest gives a package's tests a tmux server of their own, so no test ever
// reaches the tmux server of the person running them - their Claude Code sessions live
// there. Use it from TestMain:
//
//	func TestMain(m *testing.M) { tmuxtest.Main(m) }
package tmuxtest

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// Main runs the tests against a private tmux server and stops only that server after.
func Main(m *testing.M) {
	os.Exit(run(m))
}

func run(m *testing.M) int {
	// $TMUX names the server of the shell the tests run in, and it beats TMUX_TMPDIR, so
	// it has to go. The private socket lives under a short directory: a unix socket path
	// is limited to about 104 bytes on macOS, which a test's temp directory can exceed.
	os.Unsetenv("TMUX")
	dir, err := os.MkdirTemp("/tmp", "pt")
	if err != nil {
		fmt.Fprintln(os.Stderr, "tmuxtest:", err)
		return 1
	}
	defer os.RemoveAll(dir)
	os.Setenv("TMUX_TMPDIR", dir)
	code := m.Run()
	// By its socket path, never by the default: this cannot reach any other server.
	_ = exec.Command("tmux", "-S", Socket(), "kill-server").Run()
	return code
}

// Socket is the private server's socket.
func Socket() string {
	return filepath.Join(os.Getenv("TMUX_TMPDIR"), fmt.Sprintf("tmux-%d", os.Getuid()), "default")
}

// Require stops a test that would otherwise reach a tmux server other than the private one.
func Require(t *testing.T) {
	t.Helper()
	if os.Getenv("TMUX") != "" || os.Getenv("TMUX_TMPDIR") == "" {
		t.Fatal("tmux is not isolated for this test: the package needs TestMain(m) { tmuxtest.Main(m) }")
	}
}

// Fresh stops the private server, so the next tmux command starts one with this test's
// environment: a server keeps the PATH it was started with, and hands it to every pane.
func Fresh(t *testing.T) {
	t.Helper()
	Require(t)
	_ = exec.Command("tmux", "-S", Socket(), "kill-server").Run()
}
