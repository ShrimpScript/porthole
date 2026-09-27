package service

import (
	"encoding/xml"
	"strings"
	"testing"
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
