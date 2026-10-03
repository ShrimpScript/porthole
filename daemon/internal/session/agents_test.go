package session

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Three agents as Claude Code writes them: one mid-tool, one finished, one that went quiet.
func TestAgentsReadsEachAgentsProgress(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "sess", "subagents")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 9, 28, 12, 0, 0, 0, time.UTC)
	ts := func(ago time.Duration) string { return now.Add(-ago).Format(time.RFC3339Nano) }
	write := func(id, meta string, lines ...string) {
		_ = os.WriteFile(filepath.Join(dir, "agent-"+id+".jsonl"), []byte(strings.Join(lines, "\n")+"\n"), 0o600)
		if meta != "" {
			_ = os.WriteFile(filepath.Join(dir, "agent-"+id+".meta.json"), []byte(meta), 0o600)
		}
	}
	write("run", `{"agentType":"general-purpose","description":"Fix the flaky test","toolUseId":"toolu_1","spawnDepth":1,"requestShape":"background","model":"sonnet"}`,
		`{"type":"user","timestamp":"`+ts(3*time.Minute)+`","message":{"content":"Fix it"}}`,
		`{"type":"assistant","timestamp":"`+ts(2*time.Minute)+`","message":{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"Read","input":{"file_path":"/srv/app/login.test.ts"}}]}}`,
		`{"type":"user","timestamp":"`+ts(2*time.Minute)+`","message":{"content":[{"type":"tool_result","tool_use_id":"a"}]}}`,
		// a long build: nothing written for 20 minutes, but its call is still open
		`{"type":"assistant","timestamp":"`+ts(20*time.Minute)+`","message":{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"b","name":"Bash","input":{"command":"npm test"}}]}}`,
	)
	write("done", `{"agentType":"Explore","description":"Find the config","toolUseId":"toolu_2","spawnDepth":1,"requestShape":"foreground"}`,
		`{"type":"user","timestamp":"`+ts(10*time.Minute)+`","message":{"content":"Find it"}}`,
		`{"type":"assistant","timestamp":"`+ts(9*time.Minute)+`","message":{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"c","name":"Grep","input":{"pattern":"config"}}]}}`,
		`{"type":"user","timestamp":"`+ts(9*time.Minute)+`","message":{"content":[{"type":"tool_result","tool_use_id":"c"}]}}`,
		`{"type":"assistant","timestamp":"`+ts(8*time.Minute)+`","message":{"stop_reason":"end_turn","content":[{"type":"text","text":"It is in app.json."}]}}`,
	)
	write("quiet", "",
		`{"type":"user","timestamp":"`+ts(3*time.Hour)+`","message":{"content":"Look around"}}`,
		`{"type":"assistant","timestamp":"`+ts(3*time.Hour)+`","message":{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"d","name":"Glob","input":{"pattern":"*.go"}}]}}`,
		`{"type":"user","timestamp":"`+ts(3*time.Hour)+`","message":{"content":[{"type":"tool_result","tool_use_id":"d"}]}}`,
	)
	got := map[string]Agent{}
	for _, a := range agentsIn(dir, "", now) {
		got[a.ID] = a
	}
	run, done, quiet := got["run"], got["done"], got["quiet"]
	if run.State != "running" || run.Tools != 2 || run.Doing != "Bash: npm test" || !run.Background || run.Model != "sonnet" || run.ToolID != "toolu_1" || run.Description != "Fix the flaky test" {
		t.Errorf("running agent: %+v", run)
	}
	if done.State != "done" || done.Tools != 1 || done.Type != "Explore" || done.Background {
		t.Errorf("finished agent: %+v", done)
	}
	if quiet.State != "stopped" {
		t.Errorf("an agent silent for hours with nothing open has stopped: %+v", quiet)
	}
	// Reading again picks up only what was appended: the running one finishes.
	f, _ := os.OpenFile(filepath.Join(dir, "agent-run.jsonl"), os.O_APPEND|os.O_WRONLY, 0)
	_, _ = f.WriteString(`{"type":"user","timestamp":"` + ts(time.Minute) + `","message":{"content":[{"type":"tool_result","tool_use_id":"b"}]}}` + "\n" +
		`{"type":"assistant","timestamp":"` + ts(0) + `","message":{"stop_reason":"end_turn","content":[{"type":"text","text":"Fixed."}]}}` + "\n")
	f.Close()
	for _, a := range agentsIn(dir, "", now) {
		if a.ID == "run" && (a.State != "done" || a.Tools != 2) {
			t.Errorf("after its final answer: %+v", a)
		}
	}
}

// The session's own transcript has the last word: an agent it heard completed is done even if
// its own file ends without a final answer, and one it heard was killed has stopped. An agent
// interrupted or cut off by an API error has stopped, without waiting out the idle time.
func TestTheSessionsWordOnAnAgentWins(t *testing.T) {
	root := t.TempDir()
	parent := filepath.Join(root, "sess.jsonl")
	dir := SubagentsDir(parent)
	if err := os.MkdirAll(filepath.Join(dir, "workflows", "wf_1"), 0o700); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	ts := func(ago time.Duration) string { return now.Add(-ago).Format(time.RFC3339Nano) }
	write := func(rel string, lines ...string) {
		_ = os.WriteFile(filepath.Join(dir, rel), []byte(strings.Join(lines, "\n")+"\n"), 0o600)
	}
	quietText := `{"type":"assistant","timestamp":"` + ts(time.Minute) + `","message":{"stop_reason":null,"content":[{"type":"text","text":"Here is what I found."}]}}`
	write("agent-finished.jsonl", `{"type":"user","timestamp":"`+ts(3*time.Minute)+`","message":{"content":"Look"}}`, quietText)
	write("agent-killed.jsonl", `{"type":"user","timestamp":"`+ts(3*time.Minute)+`","message":{"content":"Look"}}`, quietText)
	write("agent-interrupted.jsonl", `{"type":"user","timestamp":"`+ts(3*time.Minute)+`","message":{"content":"Look"}}`,
		`{"type":"user","timestamp":"`+ts(time.Minute)+`","message":{"content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}`)
	write("agent-limited.jsonl", `{"type":"user","timestamp":"`+ts(3*time.Minute)+`","message":{"content":"Look"}}`,
		`{"type":"assistant","isApiErrorMessage":true,"timestamp":"`+ts(time.Minute)+`","message":{"stop_reason":"stop_sequence","content":[{"type":"text","text":"You've hit your limit"}]}}`)
	// out of order: the newest record is not the last line
	write("agent-busy.jsonl", `{"type":"user","timestamp":"`+ts(3*time.Minute)+`","message":{"content":"Look"}}`,
		`{"type":"assistant","timestamp":"`+ts(30*time.Second)+`","message":{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"x","name":"Read","input":{"file_path":"/a"}}]}}`,
		`{"type":"user","timestamp":"`+ts(20*time.Second)+`","message":{"content":[{"type":"tool_result","tool_use_id":"x"}]}}`,
		`{"type":"attachment","timestamp":"`+ts(15*time.Minute)+`"}`)
	write("workflows/wf_1/agent-wf.jsonl", `{"type":"user","timestamp":"`+ts(time.Minute)+`","message":{"content":"Step"}}`)
	notif := func(id, status string) string {
		return `{"type":"user","message":{"content":"<task-notification>\n<task-id>` + id + `</task-id>\n<status>` + status + `</status>\n</task-notification>"}}`
	}
	_ = os.WriteFile(parent, []byte(notif("finished", "completed")+"\n"+notif("killed", "killed")+"\n"), 0o600)

	got := map[string]string{}
	for _, a := range agentsIn(dir, parent, now) {
		got[a.ID] = a.State
	}
	want := map[string]string{"finished": "done", "killed": "stopped", "interrupted": "stopped", "limited": "stopped", "busy": "running", "wf": "running"}
	for id, w := range want {
		if got[id] != w {
			t.Errorf("%s: %q, want %q (all: %v)", id, got[id], w, got)
		}
	}
}
