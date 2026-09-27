package tailnet

import "testing"

// lsof -F output for the App Store Tailscale app, trimmed: one "n" line names the open
// sameuserproof file, whose name carries the LocalAPI port and token.
const lsofAppStore = `p812
cIPNExtension
u501
fcwd
n/
f3
n/Users/dev/Library/Group Containers/io.tailscale.ipn.macos/sameuserproof-49231-6c3a9ef0b1d2
f4
n127.0.0.1:49231
`

func TestParseSameUserProof(t *testing.T) {
	port, token, ok := parseSameUserProof(lsofAppStore)
	if !ok || port != 49231 || token != "6c3a9ef0b1d2" {
		t.Fatalf("got %d %q %v", port, token, ok)
	}
	if _, _, ok := parseSameUserProof("p1\ncIPNExtension\nn/\n"); ok {
		t.Fatal("no sameuserproof file: nothing to find")
	}
	if _, _, ok := parseSameUserProof("n/x/io.tailscale.ipn.macos/sameuserproof-notaport-abc\n"); ok {
		t.Fatal("a port that is not a number must not be used")
	}
}
