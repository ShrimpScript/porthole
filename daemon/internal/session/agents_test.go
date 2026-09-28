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
	for _, a := range agentsIn(dir, now) {
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
	for _, a := range agentsIn(dir, now) {
		if a.ID == "run" && (a.State != "done" || a.Tools != 2) {
			t.Errorf("after its final answer: %+v", a)
		}
	}
}
