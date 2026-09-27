// Package sshkeys lets a paired phone sign in over ordinary SSH, for the failsafe on a
// computer where Tailscale SSH is not there to do it: a Mac running the Tailscale app, or
// a Linux machine that uses its own sshd.
//
// Each phone's key is one line in ~/.ssh/authorized_keys, added by the daemon when the
// phone asks over its paired connection and taken out when the phone asks or is revoked.
// The line is fenced in: it works only from that phone's own tailnet addresses, and it
// gets a shell and a terminal but no forwarding of any kind. Nothing else in the file is
// touched.
package sshkeys

import (
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

// tag starts the comment on Porthole's lines; the device id follows it.
const tag = "porthole:"

var (
	mu       sync.Mutex
	deviceID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
)

// Path is the file sshd reads a user's keys from by default.
func Path() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".ssh", "authorized_keys")
}

// Parse checks a public key in OpenSSH form and returns it as "ssh-ed25519 BASE64",
// without its comment. Only Ed25519 is accepted: it is what the app makes.
func Parse(key string) (string, error) {
	f := strings.Fields(key)
	if len(f) < 2 || f[0] != "ssh-ed25519" {
		return "", errors.New("not an ssh-ed25519 public key")
	}
	blob, err := base64.StdEncoding.DecodeString(f[1])
	if err != nil {
		return "", errors.New("the key is not valid base64")
	}
	kind, rest, ok := sshString(blob)
	if !ok || string(kind) != "ssh-ed25519" {
		return "", errors.New("the key's body is not an Ed25519 key")
	}
	pub, rest, ok := sshString(rest)
	if !ok || len(pub) != 32 || len(rest) != 0 {
		return "", errors.New("the key's body is not an Ed25519 key")
	}
	return f[0] + " " + f[1], nil
}

// sshString reads one length-prefixed field of the SSH wire format.
func sshString(b []byte) ([]byte, []byte, bool) {
	if len(b) < 4 {
		return nil, nil, false
	}
	n := binary.BigEndian.Uint32(b)
	if uint64(len(b)-4) < uint64(n) {
		return nil, nil, false
	}
	return b[4 : 4+n], b[4+n:], true
}

// Line is the authorized_keys line for one device: the key, usable only from the given
// addresses, with a terminal and nothing else.
func Line(key string, from []string, device string) (string, error) {
	k, err := Parse(key)
	if err != nil {
		return "", err
	}
	if !deviceID.MatchString(device) {
		return "", fmt.Errorf("unusable device id %q", device)
	}
	var addrs []string
	for _, a := range from {
		ip, err := netip.ParseAddr(a)
		if err != nil {
			return "", fmt.Errorf("not an address: %q", a)
		}
		addrs = append(addrs, ip.Unmap().String())
	}
	if len(addrs) == 0 {
		return "", errors.New("no tailnet address to tie the key to")
	}
	return fmt.Sprintf(`restrict,pty,from="%s" %s %s%s`, strings.Join(addrs, ","), k, tag, device), nil
}

// Install puts line in the file as the device's only key, replacing any it had.
func Install(path, line, device string) error {
	mu.Lock()
	defer mu.Unlock()
	lines, mode, err := read(path)
	if err != nil {
		return err
	}
	lines, _ = without(lines, device)
	return write(path, append(lines, line), mode)
}

// Remove takes the device's key out of the file. It reports whether there was one.
func Remove(path, device string) (bool, error) {
	mu.Lock()
	defer mu.Unlock()
	lines, mode, err := read(path)
	if err != nil || len(lines) == 0 {
		return false, err
	}
	kept, removed := without(lines, device)
	if !removed {
		return false, nil
	}
	return true, write(path, kept, mode)
}

// Has reports whether the device has a key in the file.
func Has(path, device string) bool {
	mu.Lock()
	defer mu.Unlock()
	lines, _, _ := read(path)
	_, found := without(lines, device)
	return found
}

// Count is how many devices have a key in the file.
func Count(path string) int {
	mu.Lock()
	defer mu.Unlock()
	lines, _, _ := read(path)
	n := 0
	for _, l := range lines {
		if strings.HasPrefix(comment(l), tag) {
			n++
		}
	}
	return n
}

func read(path string) ([]string, os.FileMode, error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, 0o600, nil
	}
	if err != nil {
		return nil, 0, err
	}
	mode := os.FileMode(0o600)
	if fi, err := os.Stat(path); err == nil {
		mode = fi.Mode().Perm()
	}
	s := strings.TrimRight(string(b), "\n")
	if s == "" {
		return nil, mode, nil
	}
	return strings.Split(s, "\n"), mode, nil
}

// without drops the device's lines and leaves every other line exactly as it was.
func without(lines []string, device string) ([]string, bool) {
	var kept []string
	found := false
	for _, l := range lines {
		if comment(l) == tag+device {
			found = true
			continue
		}
		kept = append(kept, l)
	}
	return kept, found
}

// comment is a line's last field, where Porthole puts its tag.
func comment(line string) string {
	f := strings.Fields(line)
	if len(f) == 0 {
		return ""
	}
	return f[len(f)-1]
}

// write replaces the file in one step, so sshd never reads it half-written. A symlinked
// file is written where it points, keeping the link.
func write(path string, lines []string, mode os.FileMode) error {
	target := path
	if fi, err := os.Lstat(path); err == nil && fi.Mode()&os.ModeSymlink != 0 {
		if t, err := filepath.EvalSymlinks(path); err == nil {
			target = t
		}
	}
	dir := filepath.Dir(target)
	// sshd refuses keys in a directory others can write to; a new one is made private.
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".authorized_keys.porthole-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	body := strings.Join(lines, "\n")
	if body != "" {
		body += "\n"
	}
	if _, err := tmp.WriteString(body); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), target)
}
