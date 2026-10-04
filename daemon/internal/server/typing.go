package server

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"os/exec"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/shrimpscript/porthole/daemon/internal/session"
)

// typePrompt types a prompt into the CLI's input box and submits it, and reports whether
// the CLI took it.
//
// Measured on Claude Code 2.1.28x (2026-10-03): a prompt naming image files is attached
// file by file ("[Image #1] [Image #2]") before the box settles, and an Enter that arrives
// meanwhile is lost. The prompt then sat in the box at the desk while the phone was told it
// had been sent. So:
//
//   - the text goes in as one bracketed paste (tmux paste-buffer -p): its newlines stay
//     text, and the CLI knows exactly where it ends;
//   - Enter, then the transcript is watched for the CLI's record of it - the prompt, or
//     its queueing while Claude works - which it writes at once;
//   - no record, and the text visibly still in the box: Enter again, twice at most. Never
//     otherwise: whatever replaced the box (a permission prompt Claude raised the moment
//     it started) would take a stray Enter as an answer.
//
// No record and an empty or unrecognisable box counts as taken (a /clear, a picker, a CLI
// that draws its box differently): that is how every prompt was judged before.
func typePrompt(ctx context.Context, si session.Info, text string) (submitted bool, err error) {
	target := typeTarget(si)
	from := fileSize(si.Transcript)
	if err := pasteText(ctx, target, text); err != nil {
		return false, err
	}
	time.Sleep(settleFor(text))
	for try := 0; ; try++ {
		if err := exec.CommandContext(ctx, "tmux", "send-keys", "-t", target, "Enter").Run(); err != nil {
			return false, err
		}
		if promptRecorded(ctx, si.Transcript, from, 2500*time.Millisecond) {
			return true, nil
		}
		if !inputHolds(paneText(ctx, si), text) {
			return true, nil
		}
		if try == 2 {
			return false, nil
		}
		time.Sleep(400 * time.Millisecond)
	}
}

// pasteText puts the text in the box as one bracketed paste, falling back to typing it.
func pasteText(ctx context.Context, target, text string) error {
	var b [6]byte
	_, _ = rand.Read(b[:])
	name := "porthole-" + hex.EncodeToString(b[:])
	load := exec.CommandContext(ctx, "tmux", "load-buffer", "-b", name, "-")
	load.Stdin = strings.NewReader(text)
	if load.Run() == nil {
		// -p brackets the paste when the application asked for that; -d drops the buffer.
		if exec.CommandContext(ctx, "tmux", "paste-buffer", "-d", "-p", "-b", name, "-t", target).Run() == nil {
			return nil
		}
		_ = exec.CommandContext(ctx, "tmux", "delete-buffer", "-b", name).Run()
	}
	// send-keys -l is literal: no key-name interpretation, so a prompt containing
	// "Enter" or ";" is typed rather than executed. "--" ends tmux's own options.
	return exec.CommandContext(ctx, "tmux", "send-keys", "-t", target, "-l", "--", text).Run()
}

// settleFor is the pause between the paste and Enter: a moment for the box to take it in,
// a little more for a long one or one naming files, which the CLI reads as it goes.
func settleFor(text string) time.Duration {
	d := 150*time.Millisecond + time.Duration(len(text)/40)*time.Millisecond
	d += time.Duration(strings.Count(text, "Attached image")) * 250 * time.Millisecond
	if d > 1500*time.Millisecond {
		d = 1500 * time.Millisecond
	}
	return d
}

func fileSize(path string) int64 {
	if fi, err := os.Stat(path); err == nil {
		return fi.Size()
	}
	return 0
}

// promptRecorded waits up to wait for a record of a submitted prompt after offset in the
// transcript: a user record that is neither a tool result nor one the CLI wrote for itself,
// or a queue-operation enqueue (a prompt submitted while Claude works).
func promptRecorded(ctx context.Context, path string, from int64, wait time.Duration) bool {
	if path == "" {
		return false
	}
	deadline := time.Now().Add(wait)
	for {
		if recordedSince(path, from) {
			return true
		}
		if time.Now().After(deadline) || ctx.Err() != nil {
			return false
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func recordedSince(path string, from int64) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	if fi, err := f.Stat(); err != nil || fi.Size() < from {
		from = 0 // replaced: read it all
	}
	if _, err := f.Seek(from, 0); err != nil {
		return false
	}
	buf := new(bytes.Buffer)
	if _, err := buf.ReadFrom(f); err != nil {
		return false
	}
	for _, line := range bytes.Split(buf.Bytes(), []byte("\n")) {
		if isPromptRecord(line) {
			return true
		}
	}
	return false
}

func isPromptRecord(line []byte) bool {
	switch {
	case bytes.Contains(line, []byte(`"type":"queue-operation"`)):
		return bytes.Contains(line, []byte(`"operation":"enqueue"`))
	case bytes.Contains(line, []byte(`"type":"user"`)):
		return !bytes.Contains(line, []byte(`"tool_result"`)) && !bytes.Contains(line, []byte(`"isMeta":true`))
	}
	return false
}

func paneText(ctx context.Context, si session.Info) string {
	out, err := exec.CommandContext(ctx, "tmux", "capture-pane", "-p", "-t", captureTarget(si)).Output()
	if err != nil {
		return ""
	}
	return string(out)
}

// inputHolds reports whether the CLI's input box - the lines between the last two rules
// of "─" on screen - still shows this prompt: a paste or image placeholder, or the prompt's
// own first words. False when the box is empty or cannot be found.
func inputHolds(pane, text string) bool {
	lines := strings.Split(strings.TrimRight(pane, "\n"), "\n")
	var rules []int
	for i, l := range lines {
		t := strings.TrimSpace(l)
		if utf8.RuneCountInString(t) >= 20 && strings.Trim(t, "─") == "" {
			rules = append(rules, i)
		}
	}
	if len(rules) < 2 {
		return false
	}
	box := strings.Join(lines[rules[len(rules)-2]+1:rules[len(rules)-1]], " ")
	box = strings.TrimSpace(strings.TrimLeft(strings.TrimSpace(box), "❯>"))
	if box == "" {
		return false
	}
	if strings.Contains(box, "[Pasted text") || strings.Contains(box, "[Image #") {
		return true
	}
	first := strings.TrimSpace(strings.SplitN(strings.TrimSpace(text), "\n", 2)[0])
	if r := []rune(first); len(r) > 20 {
		first = string(r[:20])
	}
	return first != "" && strings.Contains(box, first)
}
