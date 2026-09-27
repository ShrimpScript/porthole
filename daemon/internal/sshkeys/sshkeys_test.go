package sshkeys

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func pubKey(t *testing.T) string {
	t.Helper()
	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	var blob []byte
	for _, part := range [][]byte{[]byte("ssh-ed25519"), pub} {
		blob = binary.BigEndian.AppendUint32(blob, uint32(len(part)))
		blob = append(blob, part...)
	}
	return "ssh-ed25519 " + base64.StdEncoding.EncodeToString(blob) + " porthole@phone"
}

func TestParseAcceptsOnlyEd25519(t *testing.T) {
	good := pubKey(t)
	if k, err := Parse(good); err != nil || strings.Contains(k, "phone") {
		t.Fatalf("Parse(good) = %q, %v", k, err)
	}
	bad := []string{
		"",
		"ssh-rsa AAAAB3NzaC1yc2E=",
		"ssh-ed25519 not-base64!",
		// The type says Ed25519, the body is an RSA blob.
		"ssh-ed25519 AAAAB3NzaC1yc2EAAAADAQABAAAAgQC=",
		// A valid body with a line break smuggled after it.
		strings.Replace(good, " porthole@phone", "\nssh-rsa AAAA", 1),
	}
	for _, k := range bad[:4] {
		if _, err := Parse(k); err == nil {
			t.Errorf("Parse(%q) accepted", k)
		}
	}
	// Fields splits on the newline, so the smuggled line never reaches the file.
	if k, err := Parse(bad[4]); err != nil || strings.ContainsAny(k, "\n\r") {
		t.Errorf("Parse kept a line break: %q, %v", k, err)
	}
}

func TestLineIsFencedIn(t *testing.T) {
	line, err := Line(pubKey(t), []string{"100.101.102.103", "fd7a:115c:a1e0::1"}, "nPhone1CNTRL")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(line, `restrict,pty,from="100.101.102.103,fd7a:115c:a1e0::1" ssh-ed25519 `) {
		t.Errorf("line = %q", line)
	}
	if !strings.HasSuffix(line, " porthole:nPhone1CNTRL") {
		t.Errorf("line = %q", line)
	}
	if _, err := Line(pubKey(t), nil, "nPhone1CNTRL"); err == nil {
		t.Error("a key with no address to tie it to was accepted")
	}
	if _, err := Line(pubKey(t), []string{"100.1.2.3\" ssh-rsa x"}, "n1"); err == nil {
		t.Error("an address carrying quotes was accepted")
	}
	if _, err := Line(pubKey(t), []string{"100.1.2.3"}, "n1 evil"); err == nil {
		t.Error("a device id with a space was accepted")
	}
}

func TestInstallAndRemoveKeepOtherLines(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, ".ssh", "authorized_keys")
	mine := "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMine person@laptop"
	opts := `command="backup",no-pty ssh-rsa AAAAB3Nza backup@server`

	// A first key creates the directory and file, private.
	l1, _ := Line(pubKey(t), []string{"100.1.1.1"}, "nA")
	if err := Install(path, l1, "nA"); err != nil {
		t.Fatal(err)
	}
	if fi, _ := os.Stat(filepath.Dir(path)); fi.Mode().Perm() != 0o700 {
		t.Errorf(".ssh mode %v", fi.Mode().Perm())
	}
	if fi, _ := os.Stat(path); fi.Mode().Perm() != 0o600 {
		t.Errorf("authorized_keys mode %v", fi.Mode().Perm())
	}

	// Someone's own keys, with no final newline, stay byte for byte.
	if err := os.WriteFile(path, []byte(mine+"\n"+l1+"\n"+opts), 0o600); err != nil {
		t.Fatal(err)
	}
	l2, _ := Line(pubKey(t), []string{"100.2.2.2"}, "nB")
	if err := Install(path, l2, "nB"); err != nil {
		t.Fatal(err)
	}
	// Enrolling again replaces, never duplicates.
	l1b, _ := Line(pubKey(t), []string{"100.1.1.1"}, "nA")
	if err := Install(path, l1b, "nA"); err != nil {
		t.Fatal(err)
	}
	if got, want := read1(t, path), mine+"\n"+opts+"\n"+l2+"\n"+l1b+"\n"; got != want {
		t.Fatalf("file =\n%s\nwant\n%s", got, want)
	}
	if !Has(path, "nA") || !Has(path, "nB") || Has(path, "nC") || Count(path) != 2 {
		t.Error("Has/Count wrong")
	}

	if ok, err := Remove(path, "nA"); !ok || err != nil {
		t.Fatalf("Remove = %v, %v", ok, err)
	}
	if ok, _ := Remove(path, "nA"); ok {
		t.Error("removed twice")
	}
	if got, want := read1(t, path), mine+"\n"+opts+"\n"+l2+"\n"; got != want {
		t.Fatalf("after remove =\n%s", got)
	}
	// A device id that is a prefix of another's is not the other.
	if Has(path, "n") {
		t.Error("prefix matched")
	}
}

func TestWriteFollowsSymlink(t *testing.T) {
	dir := t.TempDir()
	real := filepath.Join(dir, "dotfiles", "authorized_keys")
	if err := os.MkdirAll(filepath.Dir(real), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(real, []byte("ssh-ed25519 AAAA mine\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "authorized_keys")
	if err := os.Symlink(real, link); err != nil {
		t.Fatal(err)
	}
	l, _ := Line(pubKey(t), []string{"100.1.1.1"}, "nA")
	if err := Install(link, l, "nA"); err != nil {
		t.Fatal(err)
	}
	if fi, _ := os.Lstat(link); fi.Mode()&os.ModeSymlink == 0 {
		t.Error("the symlink was replaced by a file")
	}
	if !strings.HasSuffix(read1(t, real), " porthole:nA\n") {
		t.Error("the key did not reach the link's target")
	}
}

func read1(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}
