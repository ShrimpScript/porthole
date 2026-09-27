package service

import (
	"encoding/xml"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPlistIsWellFormedAndCarriesThePaths(t *testing.T) {
	text := renderPlist("/Users/dev/Library/Application Support/bin/portholed", "/Users/dev",
		launchdPath("/usr/bin:/bin:/Users/dev/.local/bin", "/Users/dev"))
	var probe struct{ XMLName xml.Name }
	if err := xml.Unmarshal([]byte(text), &probe); err != nil || probe.XMLName.Local != "plist" {
		t.Fatalf("not a plist: %v", err)
	}
	for _, want := range []string{
		"<string>dev.shrimpscript.portholed</string>",
		"<string>/Users/dev/Library/Application Support/bin/portholed</string>",
		"<string>/Users/dev/Library/Logs/portholed.log</string>",
		"/opt/homebrew/bin",
	} {
		if !strings.Contains(text, want) {
			t.Errorf("plist lacks %q", want)
		}
	}
}

func TestLaunchdPathKeepsTheShellsOrderAndAddsTheMacDefaults(t *testing.T) {
	got := launchdPath("/Users/dev/.local/bin:/opt/homebrew/bin:/usr/bin", "/Users/dev")
	if !strings.HasPrefix(got, "/Users/dev/.local/bin:/opt/homebrew/bin:/usr/bin:") {
		t.Fatalf("order changed: %s", got)
	}
	if strings.Count(got, "/opt/homebrew/bin") != 1 || !strings.Contains(got, "/usr/local/bin") {
		t.Fatalf("got %s", got)
	}
}

func TestUnitStartsTheBinaryItWasGiven(t *testing.T) {
	unit := strings.ReplaceAll(unitTemplate, "@BIN@", systemdQuote("/opt/my tools/portholed"))
	if !strings.Contains(unit, `ExecStart="/opt/my tools/portholed" serve`) {
		t.Fatalf("ExecStart wrong:\n%s", unit)
	}
	if strings.Contains(unit, "@BIN@") {
		t.Fatal("placeholder left in the unit")
	}
}

func TestRestartCommandQuotesAPathWithSpaces(t *testing.T) {
	if got := shellQuote("/Users/dev/My Apps/portholed"); got != `'/Users/dev/My Apps/portholed'` {
		t.Fatalf("got %s", got)
	}
}

func TestStablePathLeavesTheCellar(t *testing.T) {
	has := func(want string) func(string) bool { return func(p string) bool { return p == want } }
	cases := []struct{ exe, opt, want string }{
		{"/opt/homebrew/Cellar/porthole/0.27.0/bin/portholed", "/opt/homebrew/opt/porthole/bin/portholed", "/opt/homebrew/opt/porthole/bin/portholed"},
		{"/usr/local/Cellar/porthole/0.27.0_1/bin/portholed", "/usr/local/opt/porthole/bin/portholed", "/usr/local/opt/porthole/bin/portholed"},
		// No opt link: keep what is there rather than point at nothing.
		{"/opt/homebrew/Cellar/porthole/0.27.0/bin/portholed", "", "/opt/homebrew/Cellar/porthole/0.27.0/bin/portholed"},
		{"/Users/dev/.local/bin/portholed", "", "/Users/dev/.local/bin/portholed"},
		{"/Cellar/bin", "", "/Cellar/bin"},
	}
	for _, c := range cases {
		if got := stablePath(c.exe, has(c.opt)); got != c.want {
			t.Errorf("stablePath(%q) = %q, want %q", c.exe, got, c.want)
		}
	}
}

// The failsafe's restart runs in whatever shell the SSH login has. A service manager that
// refuses leaves the daemon started on its own.
func TestRestartCommandFallsBackInAnyShell(t *testing.T) {
	for _, sh := range []string{"sh", "bash", "zsh", "fish"} {
		path, err := exec.LookPath(sh)
		if err != nil {
			continue
		}
		dir := t.TempDir()
		bin := filepath.Join(dir, "it's portholed")
		script := "#!/bin/sh\nif [ \"$1\" = service ]; then echo restart >> \"" + dir + "/calls\"; exit 1; fi\necho \"$1\" >> \"" + dir + "/calls\"\n"
		if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
			t.Fatal(err)
		}
		if out, err := exec.Command(path, "-c", restartCommandFor(bin)).CombinedOutput(); err != nil {
			t.Fatalf("%s: %v %s", sh, err, out)
		}
		var calls []byte
		for i := 0; i < 50; i++ {
			calls, _ = os.ReadFile(filepath.Join(dir, "calls"))
			if strings.Contains(string(calls), "serve") {
				break
			}
			time.Sleep(20 * time.Millisecond)
		}
		if string(calls) != "restart\nserve\n" {
			t.Errorf("%s: calls = %q", sh, calls)
		}
	}
}
