package server

import (
	"context"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"os"
	"path/filepath"
	"testing"
	"time"
)

const turnLine = `{"type":"system","subtype":"turn_duration","durationMs":1900,"timestamp":"2026-09-11T20:00:00.000Z","uuid":"t1","sessionId":"s1"}` + "\n"

func TestToolLabel(t *testing.T) {
	cases := map[string]string{
		`[{"type":"text","text":"ok"},{"type":"tool_use","name":"Bash","input":{"command":"ls -la /tmp/x"}}]`: "Bash: ls -la /tmp/x",
		`[{"type":"tool_use","name":"Read","input":{"file_path":"/srv/p/src/App.tsx"}}]`:                      "Read: App.tsx",
		`[{"type":"tool_use","name":"Glob","input":{"pattern":"**/*.kt"}}]`:                                   "Glob: **/*.kt",
		`[{"type":"text","text":"just text"}]`:                                                                "",
	}
	for in, want := range cases {
		if got := session.ToolLabel([]byte(in)); got != want {
			t.Errorf("%s -> %q, want %q", in, got, want)
		}
	}
}

func TestWatchTurnsAnnouncesOnlyNewTurns(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "s1.jsonl")
	// A turn that already happened must not be announced: tailing starts at the end.
	if err := os.WriteFile(path, []byte(turnLine), 0o600); err != nil {
		t.Fatal(err)
	}
	got := make(chan string, 4)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = watchTurns(ctx, path, func(text string) { got <- text }, nil) }()
	time.Sleep(300 * time.Millisecond) // let the watch attach
	select {
	case text := <-got:
		t.Fatalf("history announced: %q", text)
	case <-time.After(300 * time.Millisecond):
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = f.WriteString(turnLine)
	f.Close()
	select {
	case text := <-got:
		if text != "Worked for 1.9s" {
			t.Fatalf("announced %q", text)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("new turn not announced")
	}
}
