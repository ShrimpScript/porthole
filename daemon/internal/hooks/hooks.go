// Package hooks installs and removes Porthole's entries in Claude Code's settings.
//
// Nothing here runs until the user has accepted the consent screen. The installer
// merges rather than replaces: a user's own hooks are theirs, and clobbering them
// would be the fastest way to lose their trust in a tool that already asks for a lot.
package hooks

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// Marker identifies entries Porthole owns, so uninstall removes exactly its own and
// leaves everything else untouched.
const Marker = "portholed hook"

// Event is the hook Porthole registers. Only one, deliberately: PermissionRequest is
// what makes remote approval real, and every extra hook is latency on every tool call
// at the user's desk.
const Event = "PermissionRequest"

// HookTimeout is what Claude Code will wait for our handler, in seconds. It must exceed
// the daemon's own ApprovalWait so that OUR timeout fires first and we fall back
// deliberately, rather than being killed mid-decision.
const HookTimeout = 120

func settingsPath() string {
	if d := os.Getenv("CLAUDE_CONFIG_DIR"); d != "" {
		return filepath.Join(d, "settings.json")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".claude", "settings.json")
}

func load(path string) (map[string]any, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return map[string]any{}, nil
		}
		return nil, err
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, fmt.Errorf("%s is not valid JSON; refusing to touch it: %w", path, err)
	}
	return m, nil
}

func save(path string, m map[string]any) error {
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	// Write-then-rename: a crash must never leave the user with a truncated settings
	// file and a Claude Code that will not start.
	tmp := path + ".porthole-tmp"
	if err := os.WriteFile(tmp, append(b, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// entry is one Porthole hook handler.
func entry(binary string) map[string]any {
	return map[string]any{
		"type":    "command",
		"command": binary + " hook",
		"timeout": HookTimeout,
	}
}

func isOurs(h any) bool {
	m, ok := h.(map[string]any)
	if !ok {
		return false
	}
	cmd, _ := m["command"].(string)
	return len(cmd) >= len(Marker) && contains(cmd, Marker)
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// Install adds Porthole's PermissionRequest hook, preserving anything already there.
func Install(binary string) (string, error) {
	path := settingsPath()
	m, err := load(path)
	if err != nil {
		return path, err
	}

	hooksAny, _ := m["hooks"].(map[string]any)
	if hooksAny == nil {
		hooksAny = map[string]any{}
	}
	matchers, _ := hooksAny[Event].([]any)

	// Drop any previous Porthole entry so re-installing does not stack duplicates.
	cleaned := removeOurs(matchers)

	// No "matcher" key at all. A matcher of "*" is NOT a wildcard here - it is compiled
	// as a regex, and a bare "*" matches nothing, so the hook silently never fires.
	// Omitting the key is what means "every tool".
	cleaned = append(cleaned, map[string]any{
		"hooks": []any{entry(binary)},
	})
	hooksAny[Event] = cleaned
	m["hooks"] = hooksAny
	return path, save(path, m)
}

// Uninstall removes only Porthole's entries.
func Uninstall() (string, bool, error) {
	path := settingsPath()
	m, err := load(path)
	if err != nil {
		return path, false, err
	}
	hooksAny, _ := m["hooks"].(map[string]any)
	if hooksAny == nil {
		return path, false, nil
	}
	matchers, _ := hooksAny[Event].([]any)
	cleaned := removeOurs(matchers)
	if len(cleaned) == len(matchers) {
		return path, false, nil
	}
	if len(cleaned) == 0 {
		delete(hooksAny, Event)
	} else {
		hooksAny[Event] = cleaned
	}
	if len(hooksAny) == 0 {
		delete(m, "hooks")
	} else {
		m["hooks"] = hooksAny
	}
	return path, true, save(path, m)
}

func removeOurs(matchers []any) []any {
	out := make([]any, 0, len(matchers))
	for _, mm := range matchers {
		group, ok := mm.(map[string]any)
		if !ok {
			out = append(out, mm)
			continue
		}
		list, _ := group["hooks"].([]any)
		kept := make([]any, 0, len(list))
		for _, h := range list {
			if !isOurs(h) {
				kept = append(kept, h)
			}
		}
		if len(kept) == 0 {
			continue // the whole group was ours
		}
		group["hooks"] = kept
		out = append(out, group)
	}
	return out
}

// Installed reports whether Porthole's hook is currently registered.
func Installed() (bool, string) {
	path := settingsPath()
	m, err := load(path)
	if err != nil {
		return false, path
	}
	hooksAny, _ := m["hooks"].(map[string]any)
	matchers, _ := hooksAny[Event].([]any)
	for _, mm := range matchers {
		group, ok := mm.(map[string]any)
		if !ok {
			continue
		}
		list, _ := group["hooks"].([]any)
		for _, h := range list {
			if isOurs(h) {
				return true, path
			}
		}
	}
	return false, path
}
