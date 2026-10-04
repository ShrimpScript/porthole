package server

import (
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// Against a real Claude Code, only when PORTHOLE_REAL_CLAUDE is set (it spends a few
// tokens of Haiku): the prompt that was lost on 2026-10-03 - long, naming three images,
// whose Enter the CLI dropped while attaching them - is typed and submitted, and the CLI
// records it with its images.
func TestAPromptNamingImagesReachesARealClaude(t *testing.T) {
	if os.Getenv("PORTHOLE_REAL_CLAUDE") == "" {
		t.Skip("set PORTHOLE_REAL_CLAUDE=1 to drive a real Claude Code (Haiku)")
	}
	if _, err := exec.LookPath("claude"); err != nil {
		t.Skip("claude is not installed")
	}
	tmuxtest.Fresh(t)
	dir := t.TempDir()
	var paths []string
	for i := 0; i < 3; i++ {
		img := image.NewRGBA(image.Rect(0, 0, 64, 64))
		for x := 0; x < 64; x++ {
			for y := 0; y < 64; y++ {
				img.Set(x, y, color.RGBA{uint8(x * 4), uint8(y * 4), uint8(i * 80), 255})
			}
		}
		p := filepath.Join(dir, fmt.Sprintf("shot-%d.png", i))
		f, _ := os.Create(p)
		_ = png.Encode(f, img)
		f.Close()
		paths = append(paths, p)
	}
	tm := func(args ...string) string {
		out, _ := exec.Command("tmux", args...).CombinedOutput()
		return string(out)
	}
	for try := 0; try < 40; try++ {
		if out, err := exec.Command("tmux", "new-session", "-d", "-s", "real", "-x", "150", "-y", "50", "-c", dir, "claude --model haiku").CombinedOutput(); err == nil {
			break
		} else if try == 39 {
			t.Fatalf("tmux: %v %s", err, out)
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Cleanup(func() {
		_ = exec.Command("tmux", "send-keys", "-t", "real", "C-c").Run()
		time.Sleep(300 * time.Millisecond)
		_ = exec.Command("tmux", "send-keys", "-t", "real", "C-c").Run()
		time.Sleep(time.Second)
		_ = exec.Command("tmux", "kill-session", "-t", "real").Run()
		// The CLI keeps a project for the temporary folder, and writes to it as it exits;
		// once it has, that folder, and only that one, goes.
		time.Sleep(2 * time.Second)
		if slug := transcript.ProjectSlug(dir); strings.Contains(slug, "TestAPromptNamingImages") {
			_ = os.RemoveAll(filepath.Join(transcript.ProjectsDir(), slug))
			time.Sleep(time.Second)
			_ = os.RemoveAll(filepath.Join(transcript.ProjectsDir(), slug))
		}
	})
	ready := false
	for i := 0; i < 60 && !ready; i++ {
		time.Sleep(500 * time.Millisecond)
		pane := tm("capture-pane", "-p", "-t", "real")
		if strings.Contains(pane, "trust this folder") {
			tm("send-keys", "-t", "real", "Down")
			time.Sleep(300 * time.Millisecond)
			tm("send-keys", "-t", "real", "Enter")
			continue
		}
		ready = strings.Contains(pane, "❯") && strings.Count(pane, "────") >= 2
	}
	if !ready {
		t.Fatalf("Claude Code did not come up:\n%s", tm("capture-pane", "-p", "-t", "real"))
	}
	id := registeredSession(t, dir, func() string { return tm("capture-pane", "-p", "-t", "real") })
	pane := strings.TrimSpace(tm("display-message", "-p", "-t", "real", "#{pane_id}"))
	si := session.Info{ID: id, Pane: pane, TmuxName: "real",
		Transcript: filepath.Join(transcript.ProjectsDir(), transcript.ProjectSlug(dir), id+".jsonl")}

	text := "Reply with the single word: ok."
	for i := 1; i <= 14; i++ {
		text += fmt.Sprintf("\nLine %d: filler so the message is long, the way a real request with detail is long.", i)
	}
	for _, p := range paths {
		text += "\n\nAttached image (read it with the Read tool): " + p
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	ok, err := typePrompt(ctx, si, text)
	if err != nil || !ok {
		t.Fatalf("typePrompt = %v, %v\n%s", ok, err, tm("capture-pane", "-p", "-t", "real"))
	}
	b, _ := os.ReadFile(si.Transcript)
	if !strings.Contains(string(b), "Line 14: filler") || !strings.Contains(string(b), `"type":"image"`) {
		t.Fatalf("the transcript does not hold the prompt with its images:\n%s", tm("capture-pane", "-p", "-t", "real"))
	}
}

// registeredSession waits for the CLI's registry to name the session running in dir.
func registeredSession(t *testing.T, dir string, screen func() string) string {
	t.Helper()
	home, _ := os.UserHomeDir()
	reg := filepath.Join(home, ".claude", "sessions")
	if c := os.Getenv("CLAUDE_CONFIG_DIR"); c != "" {
		reg = filepath.Join(c, "sessions")
	}
	for i := 0; i < 40; i++ {
		files, _ := filepath.Glob(filepath.Join(reg, "*.json"))
		for _, f := range files {
			b, _ := os.ReadFile(f)
			var r struct {
				Cwd       string `json:"cwd"`
				SessionID string `json:"sessionId"`
			}
			if json.Unmarshal(b, &r) == nil && r.Cwd == dir && r.SessionID != "" {
				return r.SessionID
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
	files, _ := filepath.Glob(filepath.Join(reg, "*.json"))
	t.Fatalf("the CLI's registry never named the session in %s (%d entries)\n%s", dir, len(files), screen())
	return ""
}
