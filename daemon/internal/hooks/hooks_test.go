package hooks

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func withSettings(t *testing.T, initial string) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", dir)
	path := filepath.Join(dir, "settings.json")
	if initial != "" {
		if err := os.WriteFile(path, []byte(initial), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return path
}

func read(t *testing.T, path string) map[string]any {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("settings is not valid JSON after write: %v\n%s", err, b)
	}
	return m
}

func TestInstallCreatesHook(t *testing.T) {
	path := withSettings(t, "")
	if _, err := Install("/usr/local/bin/portholed"); err != nil {
		t.Fatal(err)
	}
	if ok, _ := Installed(); !ok {
		t.Fatal("Installed() says no after Install()")
	}
	m := read(t, path)
	hooks := m["hooks"].(map[string]any)
	if _, ok := hooks[Event]; !ok {
		t.Fatalf("no %s entry: %v", Event, hooks)
	}
}

// The user's own settings must survive. Clobbering them is the fastest way to lose
// trust in a tool that already asks for shell access.
func TestInstallPreservesEverythingElse(t *testing.T) {
	path := withSettings(t, `{
	  "model": "opus",
	  "theme": "dark",
	  "permissions": {"defaultMode": "bypassPermissions"},
	  "hooks": {
	    "PreToolUse": [{"matcher": "*", "hooks": [{"type": "command", "command": "my-own-script"}]}],
	    "PermissionRequest": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "user-checker"}]}]
	  }
	}`)
	if _, err := Install("/usr/local/bin/portholed"); err != nil {
		t.Fatal(err)
	}
	m := read(t, path)
	if m["model"] != "opus" || m["theme"] != "dark" {
		t.Fatalf("unrelated settings lost: %v", m)
	}
	if _, ok := m["permissions"]; !ok {
		t.Fatal("permissions block lost")
	}
	hooks := m["hooks"].(map[string]any)
	if _, ok := hooks["PreToolUse"]; !ok {
		t.Fatal("the user's PreToolUse hook was removed")
	}
	// Their own PermissionRequest handler must still be there alongside ours.
	found := false
	for _, g := range hooks[Event].([]any) {
		for _, h := range g.(map[string]any)["hooks"].([]any) {
			if h.(map[string]any)["command"] == "user-checker" {
				found = true
			}
		}
	}
	if !found {
		t.Fatal("the user's own PermissionRequest hook was removed")
	}
}

func TestInstallIsIdempotent(t *testing.T) {
	path := withSettings(t, "")
	for i := 0; i < 3; i++ {
		if _, err := Install("/usr/local/bin/portholed"); err != nil {
			t.Fatal(err)
		}
	}
	m := read(t, path)
	n := 0
	for _, g := range m["hooks"].(map[string]any)[Event].([]any) {
		for _, h := range g.(map[string]any)["hooks"].([]any) {
			if isOurs(h) {
				n++
			}
		}
	}
	if n != 1 {
		t.Fatalf("installing three times left %d Porthole entries", n)
	}
}

// A matcher of "*" compiles as a regex that matches nothing, so the hook never fires.
// The key must simply be absent.
func TestInstallOmitsMatcher(t *testing.T) {
	path := withSettings(t, "")
	if _, err := Install("/usr/local/bin/portholed"); err != nil {
		t.Fatal(err)
	}
	m := read(t, path)
	for _, g := range m["hooks"].(map[string]any)[Event].([]any) {
		group := g.(map[string]any)
		for _, h := range group["hooks"].([]any) {
			if !isOurs(h) {
				continue
			}
			if v, present := group["matcher"]; present {
				t.Fatalf(`our group set matcher=%v; it must be absent ("*" matches nothing)`, v)
			}
		}
	}
}

func TestUninstallRemovesOnlyOurs(t *testing.T) {
	path := withSettings(t, `{
	  "hooks": {
	    "PermissionRequest": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "user-checker"}]}]
	  }
	}`)
	if _, err := Install("/usr/local/bin/portholed"); err != nil {
		t.Fatal(err)
	}
	_, removed, err := Uninstall()
	if err != nil || !removed {
		t.Fatalf("uninstall: removed=%v err=%v", removed, err)
	}
	m := read(t, path)
	hooks := m["hooks"].(map[string]any)
	groups, _ := hooks[Event].([]any)
	if len(groups) != 1 {
		t.Fatalf("want the user's one group left, got %v", groups)
	}
	for _, g := range groups {
		for _, h := range g.(map[string]any)["hooks"].([]any) {
			if isOurs(h) {
				t.Fatal("a Porthole entry survived uninstall")
			}
		}
	}
	if ok, _ := Installed(); ok {
		t.Fatal("Installed() still true after uninstall")
	}
}

// A settings file we cannot parse must be left alone, not overwritten with a fresh one.
func TestMalformedSettingsIsRefusedNotClobbered(t *testing.T) {
	path := withSettings(t, `{ this is not json `)
	before, _ := os.ReadFile(path)
	if _, err := Install("/usr/local/bin/portholed"); err == nil {
		t.Fatal("Install accepted a malformed settings file")
	}
	after, _ := os.ReadFile(path)
	if string(before) != string(after) {
		t.Fatal("a malformed settings file was modified")
	}
}

// The hook must outlive the daemon's own wait, so OUR timeout fires first and the
// fallback is deliberate rather than a kill.
func TestHookTimeoutExceedsDaemonWait(t *testing.T) {
	if HookTimeout <= 90 {
		t.Fatalf("HookTimeout (%ds) must exceed the daemon's 90s ApprovalWait", HookTimeout)
	}
}
