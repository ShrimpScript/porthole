package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/hooks"
	"github.com/shrimpscript/porthole/daemon/internal/server"
	"github.com/shrimpscript/porthole/daemon/internal/service"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// A doctor check: one line, one verdict. "fail" is something the phone cannot work
// without; "warn" is a feature the phone will not have; "info" is context.
type check struct {
	state  string // ok, warn, fail, info
	name   string
	detail string
}

func (c check) String() string { return fmt.Sprintf("%-5s %s: %s", c.state, c.name, c.detail) }

// cmdDoctor prints what this machine can offer a phone and what is missing, in the
// order a person would fix things. Exit 1 when anything failed.
func cmdDoctor() error {
	checks := runDoctor()
	failed := false
	for _, c := range checks {
		fmt.Println(c)
		if c.state == "fail" {
			failed = true
		}
	}
	if failed {
		return fmt.Errorf("something above needs fixing before a phone can use this computer")
	}
	return nil
}

func runDoctor() []check {
	var out []check
	add := func(state, name, detail string) { out = append(out, check{state, name, detail}) }

	// The tmux window is where prompts are typed.
	if v, err := probe("tmux", "-V"); err == nil {
		add("ok", "tmux", strings.TrimSpace(string(v)))
	} else {
		add("fail", "tmux", "not on PATH; Claude Code must run inside tmux for the phone to type to it")
	}

	// Tailscale is the only network the daemon binds.
	if b, err := probe(tailscaleCLI(), "status", "--json"); err == nil {
		var st struct {
			BackendState string `json:"BackendState"`
			Self         struct {
				TailscaleIPs []string  `json:"TailscaleIPs"`
				DNSName      string    `json:"DNSName"`
				KeyExpiry    time.Time `json:"KeyExpiry"`
			} `json:"Self"`
			Peer map[string]struct {
				DNSName   string    `json:"DNSName"`
				OS        string    `json:"OS"`
				KeyExpiry time.Time `json:"KeyExpiry"`
			} `json:"Peer"`
		}
		if json.Unmarshal(b, &st) == nil && st.BackendState == "Running" {
			add("ok", "tailscale", fmt.Sprintf("running as %s (%s)", strings.TrimSuffix(st.Self.DNSName, "."), strings.Join(st.Self.TailscaleIPs, ", ")))
			// A node key that expires while you are away takes the machine off the
			// tailnet, and re-authorising it needs a browser at the keyboard.
			expiries := []struct {
				what string
				when time.Time
			}{{"this computer", st.Self.KeyExpiry}}
			// Only the phones this daemon is paired with: other devices on the tailnet
			// are not Porthole's business, and their expiry is not a trip risk.
			paired := map[string]bool{}
			if resp, err := server.Call("", map[string]string{"cmd": "devices"}); err == nil {
				for _, d := range resp.Devices {
					paired[strings.ToLower(strings.Split(d.Name, ".")[0])] = true
				}
			}
			for _, p := range st.Peer {
				name := strings.Split(p.DNSName, ".")[0]
				if p.OS == "android" && paired[strings.ToLower(name)] {
					expiries = append(expiries, struct {
						what string
						when time.Time
					}{"the phone (" + name + ")", p.KeyExpiry})
				}
			}
			for _, e := range expiries {
				switch {
				case e.when.IsZero():
					add("ok", "key expiry", e.what+": disabled, it will not expire")
				case time.Until(e.when) < 0:
					add("fail", "key expiry", e.what+": expired; re-authorise it in the Tailscale admin console")
				case time.Until(e.when) < 30*24*time.Hour:
					add("warn", "key expiry", fmt.Sprintf("%s: expires %s, in %d days - disable expiry before a long trip", e.what, e.when.Format("2 Jan 2006"), int(time.Until(e.when).Hours()/24)))
				default:
					add("ok", "key expiry", fmt.Sprintf("%s: %s, in %d days", e.what, e.when.Format("2 Jan 2006"), int(time.Until(e.when).Hours()/24)))
				}
			}
		} else {
			add("fail", "tailscale", "installed but not running ("+st.BackendState+"); the daemon has nothing to bind")
		}
	} else {
		add("fail", "tailscale", "not on PATH; the daemon binds tailnet addresses only")
	}

	// Tailscale SSH is the way back in when the daemon itself is the problem.
	if b, err := probe(tailscaleCLI(), "debug", "prefs"); err == nil {
		var prefs struct {
			RunSSH bool `json:"RunSSH"`
		}
		if json.Unmarshal(b, &prefs) == nil {
			if prefs.RunSSH {
				add("ok", "ssh failsafe", "Tailscale SSH is on; the phone can open a shell and restart this daemon")
			} else if runtime.GOOS != "darwin" {
				// On a Mac the Tailscale app has no SSH server to turn on; Remote Login is
				// checked below instead.
				add("warn", "ssh failsafe", "Tailscale SSH is off: if the daemon stops, the phone has no way back in (tailscale set --ssh)")
			}
		}
	}

	// Away from the keyboard, these decide whether the computer is still there tomorrow.
	if runtime.GOOS == "darwin" {
		macAwayChecks(add)
	} else {
		if out, err := probe("loginctl", "show-user", os.Getenv("USER"), "-p", "Linger"); err == nil {
			if strings.Contains(string(out), "yes") {
				add("ok", "after a reboot", "lingering is on: the daemon starts without anyone logging in")
			} else {
				add("warn", "after a reboot", "lingering is off: after a reboot the daemon waits for a login (loginctl enable-linger)")
			}
		}
		// journalctl exits non-zero when its pattern matches nothing, which is the good case.
		slept, err := probe("journalctl", "-b", "-q", "--no-pager", "-g", "Entering sleep state")
		if err == nil || isExitOne(err) {
			if strings.TrimSpace(string(slept)) == "" {
				add("ok", "sleep", "not once since it booted"+uptimeSuffix()+"; a sleeping computer is unreachable from the phone")
			} else {
				add("warn", "sleep", "this machine has slept since booting; while it sleeps the phone cannot reach it")
			}
		}
	}
	if free, total, err := diskFree(server.BuildsDir()); err == nil {
		state := "ok"
		if free < 5<<30 {
			state = "warn"
		}
		add(state, "disk", fmt.Sprintf("%.0f GB free of %.0f GB where transcripts and state live", float64(free)/(1<<30), float64(total)/(1<<30)))
	}

	// Claude Code itself.
	if v, err := probe("claude", "--version"); err == nil {
		add("ok", "claude", strings.TrimSpace(string(v)))
	} else {
		add("warn", "claude", "not on PATH; sessions can still be watched, but not started from the phone")
	}

	// Sessions: the CLI's registry says which process writes which transcript.
	procs := session.Procs()
	if _, err := os.Stat(session.SessionsDir()); err == nil {
		inTmux := 0
		for _, p := range procs {
			if p.Pane != "" {
				inTmux++
			}
		}
		if outside := len(procs) - inTmux; outside > 0 {
			add("warn", "sessions", fmt.Sprintf("%d running now, %d outside tmux: the phone can read those but not type to them - start Claude Code with porthole instead of claude", len(procs), outside))
		} else {
			add("ok", "sessions", fmt.Sprintf("%d running now, all in tmux (registry %s)", len(procs), session.SessionsDir()))
		}
	} else {
		add("warn", "sessions", "no registry at "+session.SessionsDir()+" (older Claude Code): sessions are guessed by directory, two in one directory will merge")
	}
	dirs, _ := filepath.Glob(filepath.Join(transcript.ProjectsDir(), "*"))
	if len(dirs) > 0 {
		add("ok", "transcripts", fmt.Sprintf("%d project directories under %s", len(dirs), transcript.ProjectsDir()))
	} else {
		add("warn", "transcripts", "nothing under "+transcript.ProjectsDir()+"; run claude once in a project")
	}

	// Optional features, by the tools behind them.
	if v, err := probe("git", "--version"); err == nil {
		add("ok", "changes", strings.TrimSpace(string(v))+" (what changed, on the phone)")
	} else {
		add("warn", "changes", "git not on PATH; the phone cannot show what changed")
	}
	cap := []string{}
	for _, tool := range []string{"grim", "wf-recorder"} {
		if _, err := exec.LookPath(tool); err == nil {
			cap = append(cap, tool)
		}
	}
	if len(cap) == 0 {
		add("info", "capture", "neither grim nor wf-recorder: no screenshots or clips of the desktop")
	} else {
		add("ok", "capture", strings.Join(cap, ", "))
	}

	// The daemon and its service.
	if resp, err := server.Call("", map[string]string{"cmd": "status"}); err == nil {
		add("ok", "daemon", fmt.Sprintf("portholed %s answering, %d paired device(s), %d connected now", resp.Status.Version, resp.Status.Devices, resp.Status.Live))
		if resp.Status.Devices == 0 {
			add("info", "pairing", "no phone paired yet: run `portholed pair`")
		}
	} else {
		add("fail", "daemon", "not answering on its control socket: "+err.Error()+" (portholed service status)")
	}
	if line, ok := service.Status(); ok {
		add("ok", "service", line)
	} else {
		add("warn", "service", line)
	}

	// Approvals from the phone need the hook.
	if installed, path := hooks.Installed(); installed {
		add("ok", "approvals", "permission hook registered in "+path)
	} else {
		add("warn", "approvals", "hook not registered: approvals stay at the desk (portholed install-hooks)")
	}

	// Builds offered to phones.
	apks, _ := filepath.Glob(filepath.Join(server.BuildsDir(), "*.apk"))
	add("info", "builds", fmt.Sprintf("%d published in %s", len(apks), server.BuildsDir()))
	return out
}

// uptimeSuffix reads how long the machine has been up, for the sleep line.
func uptimeSuffix() string {
	b, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return ""
	}
	var secs float64
	if _, err := fmt.Sscanf(string(b), "%f", &secs); err != nil {
		return ""
	}
	if secs < 86400 {
		return fmt.Sprintf(" (%d hours)", int(secs/3600))
	}
	return fmt.Sprintf(" (%d days)", int(secs/86400))
}

// diskFree is the space left where the daemon keeps its state, which is also where
// Claude Code's transcripts grow.
func diskFree(path string) (free, total uint64, err error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, 0, err
	}
	return st.Bavail * uint64(st.Bsize), st.Blocks * uint64(st.Bsize), nil
}

// isExitOne is a command that ran and said "no": journalctl and grep use it for "nothing
// matched", which is not a failure to report.
func isExitOne(err error) bool {
	var ee *exec.ExitError
	return errors.As(err, &ee) && ee.ExitCode() == 1
}

// probe runs a command for doctor and returns its output. Every check is bounded: a tool
// that hangs (a broken tailscaled, a claude wrapper waiting on input) must not stop the
// rest of the report.
func probe(name string, args ...string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return exec.CommandContext(ctx, name, args...).Output()
}
