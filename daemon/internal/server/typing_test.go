package server

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/session"
)

// The box as Claude Code 2.1.284 draws it, captured from a pane where the Enter was lost
// while it attached three images, and the same box empty after a prompt went.
const (
	paneStuck = "✻ Brewed for 8s\n\n" + rule + "\n❯ [Image #1] [Image #2] [Image #3][Pasted text #4 +15 lines]\n" + rule + "\n  ⏵⏵ bypass permissions on (shift+tab to cycle)\n"
	paneEmpty = "> run the tests\n\n" + rule + "\n❯ \n" + rule + "\n  ⏵⏵ bypass permissions on (shift+tab to cycle)\n"
	rule      = "──────────────────────────────────────────────────"
)

func TestTheInputBoxIsReadFromTheScreen(t *testing.T) {
	cases := []struct {
		name, pane, text string
		want             bool
	}{
		{"placeholders left in the box", paneStuck, "Look at these\nAttached image (read it with the Read tool): /tmp/a.png", true},
		{"the box emptied", paneEmpty, "run the tests", false},
		{"short text still there", "x\n" + rule + "\n❯ run the tests now\n" + rule + "\n", "run the tests now", true},
		{"someone else's words", "x\n" + rule + "\n❯ something typed at the desk\n" + rule + "\n", "run the tests", false},
		{"no box to read", "a picker\n1. Yes\n2. No\n", "run the tests", false},
	}
	for _, c := range cases {
		if got := inputHolds(c.pane, c.text); got != c.want {
			t.Errorf("%s: inputHolds = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestAPromptRecordIsTheCLIsOwnWord(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.jsonl")
	write := func(line string) {
		f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = f.WriteString(line + "\n")
		f.Close()
	}
	write(`{"type":"user","message":{"content":"earlier"}}`)
	from := fileSize(path)
	// What does not count: a tool result, a reminder the CLI wrote, anything else.
	write(`{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}`)
	write(`{"type":"user","isMeta":true,"message":{"content":"<system-reminder>x</system-reminder>"}}`)
	write(`{"type":"assistant","message":{"content":[]}}`)
	write(`{"type":"queue-operation","operation":"dequeue"}`)
	if promptRecorded(t.Context(), path, from, 150*time.Millisecond) {
		t.Fatal("counted a record that is not a submitted prompt")
	}
	write(`{"type":"queue-operation","operation":"enqueue","content":"run the tests"}`)
	if !promptRecorded(t.Context(), path, from, 150*time.Millisecond) {
		t.Fatal("a prompt queued while Claude works is a submitted prompt")
	}
	from = fileSize(path)
	go func() {
		time.Sleep(200 * time.Millisecond)
		write(`{"type":"user","message":{"content":"and then this"}}`)
	}()
	if !promptRecorded(t.Context(), path, from, 2*time.Second) {
		t.Fatal("missed a prompt written while waiting")
	}
}

func TestTheWaitBeforeEnterGrowsWithTheFiles(t *testing.T) {
	short, withImages := settleFor("run the tests"), settleFor("look\n\nAttached image (read it with the Read tool): /a.png\n\nAttached image (read it with the Read tool): /b.png")
	if short < 150*time.Millisecond || withImages < short+500*time.Millisecond || settleFor(string(make([]byte, 1<<20))) > 1500*time.Millisecond {
		t.Fatalf("settle: short %v, with images %v", short, withImages)
	}
}

// A session outside tmux has no target at all - never "", which tmux reads as the pane used
// last, in another session - and a session name is matched exactly.
func TestASessionOutsideTmuxHasNoTarget(t *testing.T) {
	if got, ok := tmuxTarget(session.Info{Live: true}); ok || got != "" {
		t.Fatalf("outside tmux: got %q, %v; want no target", got, ok)
	}
	if got, ok := tmuxTarget(session.Info{Pane: "%3", TmuxName: "work"}); !ok || got != "%3" {
		t.Fatalf("with a pane: got %q, %v", got, ok)
	}
	if got, ok := tmuxTarget(session.Info{TmuxName: "work"}); !ok || got != "=work" {
		t.Fatalf("by name: got %q, %v; want the exact match =work, never a prefix of workshop", got, ok)
	}
}

// fakeTmux puts a tmux on PATH that logs every call and answers capture-pane with the box
// holding boxText until a Backspace arrives (or for good, when stuck).
func fakeTmux(t *testing.T, boxText string, stuck bool) (logPath string) {
	dir := t.TempDir()
	logPath = filepath.Join(dir, "calls")
	script := `#!/bin/sh
echo "$*" >> "` + logPath + `"
case "$1" in
capture-pane)
  if [ -e "` + dir + `/cleared" ]; then printf 'x\n` + rule + `\n❯ \n` + rule + `\n'
  else printf 'x\n` + rule + `\n❯ %s\n` + rule + `\n' "` + boxText + `"; fi ;;
send-keys)
  case "$*" in *BSpace*) ` + map[bool]string{true: ":", false: `touch "` + dir + `/cleared"`}[stuck] + ` ;; esac ;;
load-buffer) cat > /dev/null ;;
esac
exit 0
`
	if err := os.WriteFile(filepath.Join(dir, "tmux"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
	return logPath
}

func transcriptWith(t *testing.T, lines ...string) string {
	path := filepath.Join(t.TempDir(), "s.jsonl")
	if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestNothingIsTypedIntoASessionOutsideTmux(t *testing.T) {
	calls := fakeTmux(t, "", false)
	_, err := typePrompt(t.Context(), session.Info{Live: true, Transcript: transcriptWith(t, `{"type":"user","message":{"content":"hi"}}`)}, "Continue")
	if !errors.Is(err, errNoTmux) {
		t.Fatalf("err = %v, want errNoTmux", err)
	}
	if b, _ := os.ReadFile(calls); len(b) > 0 {
		t.Fatalf("tmux was called for a session outside tmux:\n%s", b)
	}
}

// Interrupted before it answered, the CLI puts the prompt back in the box; the next one
// clears it rather than being typed after it.
func TestAPromptPutBackAfterAnInterruptIsClearedFirst(t *testing.T) {
	calls := fakeTmux(t, "Looks good", false)
	si := session.Info{Live: true, Pane: "%1", Transcript: transcriptWith(t,
		`{"type":"user","message":{"content":"Continue"}}`,
		`{"type":"user","message":{"content":[{"type":"text","text":"Looks good"}]}}`,
	)}
	if _, err := typePrompt(t.Context(), si, "Stop"); err != nil {
		t.Fatalf("typePrompt: %v", err)
	}
	b, _ := os.ReadFile(calls)
	log := string(b)
	bs, paste := strings.Index(log, "BSpace"), strings.Index(log, "paste-buffer")
	if bs < 0 || paste < 0 || bs > paste {
		t.Fatalf("want the box cleared before the paste:\n%s", log)
	}
	if !strings.Contains(log, "-N 18 BSpace") {
		t.Fatalf("want one Backspace per character plus a margin (10+8):\n%s", log)
	}
}

// Text in the box that will not clear is someone's: nothing is typed after it.
func TestTextThatStaysInTheBoxIsNeverTypedAfter(t *testing.T) {
	calls := fakeTmux(t, "Looks good", true)
	si := session.Info{Live: true, Pane: "%1", Transcript: transcriptWith(t, `{"type":"user","message":{"content":"Looks good"}}`)}
	if _, err := typePrompt(t.Context(), si, "Stop"); !errors.Is(err, errBoxHeld) {
		t.Fatalf("err = %v, want errBoxHeld", err)
	}
	if b, _ := os.ReadFile(calls); strings.Contains(string(b), "paste-buffer") || strings.Contains(string(b), "-l") {
		t.Fatalf("typed after text that stayed in the box:\n%s", b)
	}
}

func TestTheLastPromptIsTheOneTheCLIPutsBack(t *testing.T) {
	path := transcriptWith(t,
		`{"type":"user","message":{"content":"first"}}`,
		`{"type":"assistant","message":{"content":[]}}`,
		`{"type":"user","message":{"content":"run the tests"}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}`,
		`{"type":"user","isMeta":true,"message":{"content":"<system-reminder>x</system-reminder>"}}`,
		`{"type":"user","message":{"content":[{"type":"text","text":"[Request interrupted by user]"}]}}`,
	)
	if got := lastPrompt(path); got != "run the tests" {
		t.Fatalf("lastPrompt = %q", got)
	}
	// An empty box drawn with the CLI's hint is not the prompt.
	if boxShows("x\n"+rule+"\n❯ Try \"run the tests\"\n"+rule+"\n", "run the tests") {
		t.Fatal("a hint in an empty box read as the prompt")
	}
	if !boxShows("x\n"+rule+"\n❯ run the tests\n"+rule+"\n", "run the tests") {
		t.Fatal("missed the prompt back in the box")
	}
}

// While the CLI waits on something at the desk, its screen would take the prompt and the
// Enter as the answer - a permission prompt's "Yes" - so nothing is typed.
func TestNothingIsTypedWhileTheCLIWaitsAtTheDesk(t *testing.T) {
	calls := fakeTmux(t, "", false)
	for _, held := range []string{"permission prompt", "input needed", "dialog open", "something"} {
		si := session.Info{Live: true, Pane: "%1", Held: held, Transcript: transcriptWith(t, `{"type":"user","message":{"content":"hi"}}`)}
		if _, err := typePrompt(t.Context(), si, "Continue"); !errors.Is(err, errHeld) {
			t.Fatalf("%s: err = %v, want errHeld", held, err)
		}
		if msg := heldMessage(held); !strings.Contains(msg, "first") {
			t.Fatalf("%s: the message does not say what to do first: %q", held, msg)
		}
	}
	if b, _ := os.ReadFile(calls); len(b) > 0 {
		t.Fatalf("tmux was called while the CLI waited:\n%s", b)
	}
}
