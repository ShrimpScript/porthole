package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
)

// cmdClaude starts Claude Code where the phone can see it and type into it: inside tmux.
// It is what `porthole` runs (the same binary, called by that name), so a person swaps
// `claude` for `porthole` and nothing else changes:
//
//   - already inside tmux: this pane is visible to the daemon, so claude just runs here;
//   - a tmux session already open in this directory: attach to it, where Claude may
//     already be running, instead of starting a second one;
//   - otherwise: a new tmux session named after the directory, with claude typed into
//     its shell - so the user's PATH and aliases apply, and when Claude exits the shell
//     is still there and the session survives.
func cmdClaude(args []string) error {
	tmux, err := exec.LookPath("tmux")
	if err != nil {
		how := "Install it with your package manager (for example: sudo apt install tmux, sudo dnf install tmux,\n" +
			"sudo pacman -S tmux), then run this again"
		if runtime.GOOS == "darwin" {
			how = "Install it with Homebrew (brew install tmux), then run this again"
		}
		return errors.New("Porthole runs Claude Code inside tmux so the phone can type to it, and tmux is not installed.\n" + how)
	}
	if os.Getenv("TMUX") != "" {
		claude, err := exec.LookPath("claude")
		if err != nil {
			return errors.New("claude is not on your PATH - install Claude Code first")
		}
		return syscall.Exec(claude, append([]string{"claude"}, args...), os.Environ())
	}
	cwd, err := os.Getwd()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	name := sessionIn(ctx, cwd)
	if name == "" {
		name = freeSessionName(ctx, cwd)
		if out, err := exec.CommandContext(ctx, "tmux", "new-session", "-d", "-s", name, "-c", cwd).CombinedOutput(); err != nil {
			return fmt.Errorf("could not start tmux: %s", strings.TrimSpace(string(out)))
		}
		line := strings.Join(append([]string{"claude"}, quoteArgs(args)...), " ")
		// A moment for the shell to draw its prompt, as a person would wait.
		time.Sleep(250 * time.Millisecond)
		_ = exec.CommandContext(ctx, "tmux", "send-keys", "-t", "="+name+":", "-l", line).Run()
		_ = exec.CommandContext(ctx, "tmux", "send-keys", "-t", "="+name+":", "Enter").Run()
	} else if len(args) > 0 {
		fmt.Fprintf(os.Stderr, "porthole: attaching to the tmux session already open here (%s); the arguments were not used\n", name)
	}
	return syscall.Exec(tmux, []string{"tmux", "attach-session", "-t", "=" + name}, os.Environ())
}

// sessionIn is a tmux session whose start directory is cwd, if one is open.
func sessionIn(ctx context.Context, cwd string) string {
	out, err := exec.CommandContext(ctx, "tmux", "list-sessions", "-F", "#{session_name}\t#{session_path}").Output()
	if err != nil {
		return ""
	}
	want := filepath.Clean(cwd)
	for _, l := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		name, path, ok := strings.Cut(l, "\t")
		// Porthole's own mirror sessions (a phone watching a window) are not places to work.
		if ok && filepath.Clean(path) == want && !strings.HasSuffix(name, "-porthole") {
			return name
		}
	}
	return ""
}

// freeSessionName is the directory's name as a tmux session name, numbered if taken.
func freeSessionName(ctx context.Context, cwd string) string {
	base := sessionSlug(filepath.Base(cwd))
	taken := map[string]bool{}
	if out, err := exec.CommandContext(ctx, "tmux", "list-sessions", "-F", "#{session_name}").Output(); err == nil {
		for _, l := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			taken[l] = true
		}
	}
	name := base
	for i := 2; taken[name]; i++ {
		name = fmt.Sprintf("%s-%d", base, i)
	}
	return name
}

func sessionSlug(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		} else {
			b.WriteRune('-')
		}
	}
	if name := strings.Trim(b.String(), "-"); name != "" {
		return name
	}
	return "claude"
}

// quoteArgs makes arguments safe to type into a shell. Plain words pass as they are;
// anything else is single-quoted, with a quote inside written as '"'"' - which bash, zsh
// and fish all read back as one quote, since adjacent quoted strings join.
func quoteArgs(args []string) []string {
	out := make([]string, len(args))
	for i, a := range args {
		if a != "" && strings.Trim(a, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._/=:@%+-,") == "" {
			out[i] = a
		} else {
			out[i] = "'" + strings.ReplaceAll(a, "'", `'"'"'`) + "'"
		}
	}
	return out
}
