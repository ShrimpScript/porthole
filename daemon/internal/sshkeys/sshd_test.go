package sshkeys

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The line Porthole writes, read by a real sshd: a private one on a spare loopback port,
// with its own host key and keys file, so nothing on the machine is touched. It proves
// the options mean what the package says - the key signs in from its address and no
// other, gets a terminal, and cannot forward.
func TestRealSSHDHonoursTheLine(t *testing.T) {
	sshd := findSSHD()
	if sshd == "" {
		t.Skip("no sshd here")
	}
	for _, tool := range []string{"ssh", "ssh-keygen"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Skip("no " + tool + " here")
		}
	}
	dir := t.TempDir()
	for _, k := range []string{"host", "phone"} {
		if out, err := exec.Command("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", filepath.Join(dir, k)).CombinedOutput(); err != nil {
			t.Fatalf("ssh-keygen: %v %s", err, out)
		}
	}
	pub, err := os.ReadFile(filepath.Join(dir, "phone.pub"))
	if err != nil {
		t.Fatal(err)
	}
	keys := filepath.Join(dir, "authorized_keys")
	port := freePort(t)
	cfg := filepath.Join(dir, "sshd_config")
	conf := fmt.Sprintf(`ListenAddress 127.0.0.1
Port %d
HostKey %s
AuthorizedKeysFile %s
PidFile %s
StrictModes no
PasswordAuthentication no
KbdInteractiveAuthentication no
LogLevel VERBOSE
`, port, filepath.Join(dir, "host"), keys, filepath.Join(dir, "sshd.pid"))
	if err := os.WriteFile(cfg, []byte(conf), 0o600); err != nil {
		t.Fatal(err)
	}
	var log bytes.Buffer
	d := exec.Command(sshd, "-D", "-e", "-f", cfg)
	d.Stdout, d.Stderr = &log, &log
	if err := d.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = d.Process.Kill(); _ = d.Wait() })
	waitPort(t, port, &log)

	ssh := func(extra ...string) (string, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		args := append([]string{"-F", "/dev/null", "-i", filepath.Join(dir, "phone"), "-p", fmt.Sprint(port),
			"-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
			"-o", "UserKnownHostsFile=/dev/null", "-o", "LogLevel=ERROR"}, extra...)
		out, err := exec.CommandContext(ctx, "ssh", args...).CombinedOutput()
		return strings.TrimSpace(string(out)), err
	}

	// Tied to another address: refused.
	line, err := Line(string(pub), []string{"100.99.0.1"}, "nPhone")
	if err != nil {
		t.Fatal(err)
	}
	if err := Install(keys, line, "nPhone"); err != nil {
		t.Fatal(err)
	}
	if out, err := ssh("127.0.0.1", "echo in"); err == nil {
		t.Fatalf("signed in from an address the key is not tied to: %q", out)
	}
	if !strings.Contains(log.String(), "not from a permitted host") {
		t.Fatalf("refused, but not by from=:\n%s", log.String())
	}

	// Tied to this one: in, with a terminal.
	line, _ = Line(string(pub), []string{"127.0.0.1"}, "nPhone")
	if err := Install(keys, line, "nPhone"); err != nil {
		t.Fatal(err)
	}
	if out, err := ssh("127.0.0.1", "echo porthole-in"); err != nil || out != "porthole-in" {
		t.Fatalf("the key did not sign in: %q %v\n%s", out, err, log.String())
	}
	if out, err := ssh("-tt", "127.0.0.1", "tty"); err != nil || !strings.HasPrefix(out, "/dev/") {
		t.Fatalf("no terminal: %q %v", out, err)
	}
	// No forwarding of any kind.
	if out, err := ssh("-o", "ExitOnForwardFailure=yes", "-R", "0:127.0.0.1:9", "127.0.0.1", "echo forwarded"); err == nil {
		t.Fatalf("remote forwarding was allowed: %q", out)
	}

	// Removed: out.
	if ok, err := Remove(keys, "nPhone"); !ok || err != nil {
		t.Fatal(ok, err)
	}
	if out, err := ssh("127.0.0.1", "echo in"); err == nil {
		t.Fatalf("signed in after the key was removed: %q", out)
	}
}

// findSSHD is sshd by its absolute path, which it needs to re-execute itself.
func findSSHD() string {
	for _, p := range []string{"/usr/sbin/sshd", "/usr/bin/sshd"} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func waitPort(t *testing.T, port int, log *bytes.Buffer) {
	t.Helper()
	for i := 0; i < 50; i++ {
		if c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port)); err == nil {
			c.Close()
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("sshd did not start:\n%s", log.String())
}
