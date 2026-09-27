// Package service installs, restarts and removes portholed as a per-user background
// service: a systemd user unit on Linux, a launchd agent on macOS. The files are written
// with the path of the running binary, so a Homebrew install, ~/.local/bin and a build
// in a checkout all get a service that starts the program they actually have.
package service

import (
	_ "embed"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

// Label is the launchd label and the systemd unit's name.
const (
	Label    = "dev.shrimpscript.portholed"
	unitName = "portholed.service"
)

//go:embed portholed.service
var unitTemplate string

//go:embed portholed.plist
var plistTemplate string

// Binary is the running executable with links resolved: the path a service must start.
func Binary() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	if r, err := filepath.EvalSymlinks(exe); err == nil {
		exe = r
	}
	return exe, nil
}

// RestartCommand is the shell command that restarts the service on this computer, for
// the failsafe to run over SSH. Absolute, because a non-interactive SSH login has a
// short PATH that often leaves out where portholed lives.
func RestartCommand() string {
	bin, err := Binary()
	if err != nil {
		return ""
	}
	return shellQuote(bin) + " service restart"
}

// File is where the service definition lives on this OS.
func File() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	switch runtime.GOOS {
	case "darwin":
		return filepath.Join(home, "Library", "LaunchAgents", Label+".plist"), nil
	case "linux":
		return filepath.Join(home, ".config", "systemd", "user", unitName), nil
	}
	return "", fmt.Errorf("no service support on %s: run `portholed serve` yourself", runtime.GOOS)
}

// Render is the service definition for this OS, for the binary at bin.
func Render(bin string) (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	switch runtime.GOOS {
	case "darwin":
		return renderPlist(bin, home, launchdPath(os.Getenv("PATH"), home)), nil
	case "linux":
		return strings.ReplaceAll(unitTemplate, "@BIN@", systemdQuote(bin)), nil
	}
	return "", fmt.Errorf("no service support on %s", runtime.GOOS)
}

func renderPlist(bin, home, path string) string {
	r := strings.NewReplacer("@LABEL@", xmlEscape(Label), "@BIN@", xmlEscape(bin),
		"@PATH@", xmlEscape(path), "@LOG@", xmlEscape(filepath.Join(home, "Library", "Logs", "portholed.log")))
	return r.Replace(plistTemplate)
}

// launchdPath is the PATH the agent runs with. launchd starts agents with only the
// system directories, where neither tmux (Homebrew) nor claude (~/.local/bin) lives, so
// the installing shell's PATH is kept, with the usual Mac locations added if missing.
func launchdPath(current, home string) string {
	var out []string
	seen := map[string]bool{}
	add := func(d string) {
		if d != "" && !seen[d] {
			seen[d] = true
			out = append(out, d)
		}
	}
	for _, d := range strings.Split(current, ":") {
		add(d)
	}
	for _, d := range []string{filepath.Join(home, ".local", "bin"), "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"} {
		add(d)
	}
	return strings.Join(out, ":")
}

// Install writes the service definition and starts it, replacing a running one.
func Install() (string, error) {
	bin, err := Binary()
	if err != nil {
		return "", err
	}
	file, err := File()
	if err != nil {
		return "", err
	}
	text, err := Render(bin)
	if err != nil {
		return "", err
	}
	home, _ := os.UserHomeDir()
	// The state directory must exist before the service starts (the Linux unit makes
	// it writable, and systemd refuses to start at all if it is missing).
	if err := os.MkdirAll(filepath.Join(home, ".config", "porthole"), 0o700); err != nil {
		return "", err
	}
	// Where the failsafe key goes. The unit may write here only if it exists when the
	// service starts, and sshd wants it private.
	if err := os.MkdirAll(filepath.Join(home, ".ssh"), 0o700); err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		return "", err
	}
	if err := os.WriteFile(file, []byte(text), 0o644); err != nil {
		return "", err
	}
	switch runtime.GOOS {
	case "darwin":
		_ = run("launchctl", "bootout", domain()+"/"+Label) // not loaded yet is fine
		if err := run("launchctl", "bootstrap", domain(), file); err != nil {
			return file, err
		}
		_ = run("launchctl", "enable", domain()+"/"+Label)
		return file, run("launchctl", "kickstart", "-k", domain()+"/"+Label)
	default:
		if err := run("systemctl", "--user", "daemon-reload"); err != nil {
			return file, err
		}
		if err := run("systemctl", "--user", "enable", unitName); err != nil {
			return file, err
		}
		return file, run("systemctl", "--user", "restart", unitName)
	}
}

// Restart restarts the running service.
func Restart() error {
	switch runtime.GOOS {
	case "darwin":
		return run("launchctl", "kickstart", "-k", domain()+"/"+Label)
	case "linux":
		return run("systemctl", "--user", "restart", unitName)
	}
	return fmt.Errorf("no service support on %s", runtime.GOOS)
}

// Uninstall stops the service and removes its definition. The state directory - the
// paired devices - is left alone.
func Uninstall() error {
	file, err := File()
	if err != nil {
		return err
	}
	switch runtime.GOOS {
	case "darwin":
		_ = run("launchctl", "bootout", domain()+"/"+Label)
	default:
		_ = run("systemctl", "--user", "disable", "--now", unitName)
	}
	if err := os.Remove(file); err != nil && !os.IsNotExist(err) {
		return err
	}
	if runtime.GOOS == "linux" {
		_ = run("systemctl", "--user", "daemon-reload")
	}
	return nil
}

// Status is a one-line description of the service's state, and whether it is running.
func Status() (string, bool) {
	file, err := File()
	if err != nil {
		return err.Error(), false
	}
	if _, err := os.Stat(file); err != nil {
		return "not installed (portholed service install)", false
	}
	switch runtime.GOOS {
	case "darwin":
		out, err := exec.Command("launchctl", "print", domain()+"/"+Label).CombinedOutput()
		if err != nil {
			return "installed but not loaded (portholed service install)", false
		}
		if strings.Contains(string(out), "state = running") {
			return "launchd agent " + Label + " running, starts at login", true
		}
		return "launchd agent " + Label + " loaded but not running", false
	default:
		out, _ := exec.Command("systemctl", "--user", "is-active", unitName).Output()
		state := strings.TrimSpace(string(out))
		enabled, _ := exec.Command("systemctl", "--user", "is-enabled", unitName).Output()
		if state == "active" {
			return unitName + " active, " + map[bool]string{true: "starts at login", false: "not enabled at login"}[strings.TrimSpace(string(enabled)) == "enabled"], true
		}
		return unitName + " " + state, false
	}
}

// domain is the launchd domain of this user's login session.
func domain() string { return "gui/" + strconv.Itoa(os.Getuid()) }

func run(name string, args ...string) error {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %s: %s", name, strings.Join(args, " "), strings.TrimSpace(string(out)))
	}
	return nil
}

func shellQuote(s string) string {
	if s != "" && strings.Trim(s, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._/-+") == "" {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'"'"'`) + "'"
}

// systemdQuote quotes a path for ExecStart, where spaces split arguments.
func systemdQuote(s string) string {
	if !strings.ContainsAny(s, " \t\"'\\") {
		return s
	}
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(s) + `"`
}

func xmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;").Replace(s)
}
