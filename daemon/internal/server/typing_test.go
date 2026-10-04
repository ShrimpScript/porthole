package server

import (
	"os"
	"path/filepath"
	"testing"
	"time"
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
