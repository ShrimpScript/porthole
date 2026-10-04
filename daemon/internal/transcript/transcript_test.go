package transcript

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func parse(t *testing.T, lines ...string) *Result {
	t.Helper()
	res, err := Parse(strings.NewReader(strings.Join(lines, "\n")))
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func TestUserPromptBecomesARow(t *testing.T) {
	res := parse(t, `{"type":"user","message":{"content":"why is the pathfinder stalling?"}}`)
	if len(res.Rows) != 1 || res.Rows[0].Kind != KindUser {
		t.Fatalf("want one user row, got %+v", res.Rows)
	}
	if res.Rows[0].Text != "why is the pathfinder stalling?" {
		t.Fatalf("text mangled: %q", res.Rows[0].Text)
	}
}

// Every feed row comes from a real record, and this is the bug that would have shipped:
// system envelopes arrive with role=user and must never render as chat.
func TestInjectedEnvelopesAreNotUserMessages(t *testing.T) {
	res := parse(t,
		`{"type":"user","message":{"content":"<task-notification>\n<task-id>abc</task-id>"}}`,
		`{"type":"user","message":{"content":"<system-reminder>be careful</system-reminder>"}}`,
		`{"type":"user","message":{"content":"<local-command-stdout>hi</local-command-stdout>"}}`,
		`{"type":"user","message":{"content":"Caveat: The messages below were generated..."}}`,
	)
	if len(res.Rows) != 0 {
		t.Fatalf("injected envelopes rendered as rows: %+v", res.Rows)
	}
	if res.Stats.Silent != 4 {
		t.Fatalf("want 4 silent records, got %d", res.Stats.Silent)
	}
}

func TestInterruptBecomesASessionEvent(t *testing.T) {
	res := parse(t, `{"type":"user","message":{"content":"[Request interrupted by user]"}}`)
	if len(res.Rows) != 1 || res.Rows[0].Kind != KindEvent {
		t.Fatalf("want one event row, got %+v", res.Rows)
	}
	if res.Rows[0].Text != "You interrupted" {
		t.Fatalf("want plain language, got %q", res.Rows[0].Text)
	}
}

func TestToolCallAndResult(t *testing.T) {
	res := parse(t,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"cargo test"}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","content":"line1\nline2\nline3"}]}}`,
	)
	if len(res.Rows) != 2 {
		t.Fatalf("want 2 rows, got %+v", res.Rows)
	}
	if res.Rows[0].Kind != KindTool || res.Rows[0].Text != "Ran cargo test" {
		t.Fatalf("tool row wrong: %+v", res.Rows[0])
	}
	if res.Rows[1].Kind != KindResult || res.Rows[1].Glyph != "✓" || res.Rows[1].Metric != "3 lines" {
		t.Fatalf("result row wrong: %+v", res.Rows[1])
	}
}

func TestFailedResultIsMarkedFailed(t *testing.T) {
	res := parse(t,
		`{"type":"user","message":{"content":[{"type":"tool_result","content":"error: could not compile"}]}}`,
	)
	if res.Rows[0].Glyph != "✗" || res.Rows[0].Text != "failed" {
		t.Fatalf("want a failed row, got %+v", res.Rows[0])
	}
}

// A prompt queued while Claude was busy must show as pending, then resolve into the real
// message rather than appearing twice.
func TestQueuedPromptResolvesInsteadOfDuplicating(t *testing.T) {
	res := parse(t,
		`{"type":"queue-operation","operation":"enqueue","content":"remember to check the ACL"}`,
	)
	if len(res.Rows) != 1 || res.Rows[0].Kind != KindQueued || res.Rows[0].Metric != "queued" {
		t.Fatalf("want a pending row, got %+v", res.Rows)
	}

	res2 := parse(t,
		`{"type":"queue-operation","operation":"enqueue","content":"remember to check the ACL"}`,
		`{"type":"user","message":{"content":"remember to check the ACL"}}`,
	)
	if len(res2.Rows) != 1 {
		t.Fatalf("queued prompt duplicated on delivery: %+v", res2.Rows)
	}
	if res2.Rows[0].Kind != KindUser {
		t.Fatalf("pending row should have resolved to a user message, got %+v", res2.Rows[0])
	}
}

func TestMetaAndTitle(t *testing.T) {
	res := parse(t,
		`{"type":"user","sessionId":"abc","cwd":"/home/x/proj","gitBranch":"main","version":"2.1.241","message":{"content":"hi"}}`,
		`{"type":"agent-name","agentName":"Port the ragdoll mod"}`,
	)
	if res.Meta.SessionID != "abc" || res.Meta.GitBranch != "main" {
		t.Fatalf("meta wrong: %+v", res.Meta)
	}
	if res.Meta.Title != "Port the ragdoll mod" {
		t.Fatalf("title should come from agent-name, got %q", res.Meta.Title)
	}
}

func TestUnknownRecordTypeIsReportedNotInvented(t *testing.T) {
	res := parse(t, `{"type":"some-future-record","content":"?"}`)
	if len(res.Rows) != 0 {
		t.Fatal("an unknown record produced a row")
	}
	if res.Stats.Unmapped["some-future-record"] != 1 {
		t.Fatalf("unknown type should be counted as unmapped: %+v", res.Stats.Unmapped)
	}
}

// The real proof: the Go port and tools/replay.py must agree on every transcript on this
// machine. Skips cleanly where those inputs do not exist (CI, another machine).
func TestParityWithReplayPy(t *testing.T) {
	py, err := exec.LookPath("python3")
	if err != nil {
		t.Skip("python3 not available")
	}
	script, err := filepath.Abs("../../../tools/replay.py")
	if err != nil || !exists(script) {
		t.Skip("tools/replay.py not found")
	}
	files, _ := filepath.Glob(filepath.Join(ProjectsDir(), "*", "*.jsonl"))
	if len(files) == 0 {
		t.Skip("no transcripts on this machine")
	}

	type pyStats struct {
		Records  int            `json:"records"`
		Rows     int            `json:"rows"`
		ByKind   map[string]int `json:"by_kind"`
		Unmapped map[string]int `json:"unmapped"`
	}
	// python label -> Go row kind
	label := map[string]Kind{
		"user message": KindUser, "assistant text": KindAssistant,
		"tool call": KindTool, "result": KindResult,
		"queued user message": KindQueued, "session event": KindEvent,
		"turn summary": KindTurn, "image": KindImage, "question": KindQuestion,
		"command": KindCommand,
	}

	checked, totalRecords, totalRows := 0, 0, 0
	snapDir := t.TempDir()
	for _, live := range files {
		// Both sides read the same snapshot: a running session appends to its transcript
		// between the two reads, and the counts would then differ for no fault of either.
		b, err := os.ReadFile(live)
		if err != nil || len(b) < 400 {
			continue
		}
		f := filepath.Join(snapDir, filepath.Base(live))
		if err := os.WriteFile(f, b, 0o600); err != nil {
			t.Fatal(err)
		}
		out, err := exec.Command(py, script, f, "--json").Output()
		if err != nil {
			t.Fatalf("replay.py failed on %s: %v", filepath.Base(f), err)
		}
		var want pyStats
		if err := json.Unmarshal(out, &want); err != nil {
			t.Fatalf("replay.py json on %s: %v", filepath.Base(f), err)
		}
		got, err := ParseFile(f)
		_ = os.Remove(f)
		if err != nil {
			t.Fatalf("go parse %s: %v", filepath.Base(f), err)
		}
		name := filepath.Base(f)
		if got.Stats.Records != want.Records {
			t.Errorf("%s: records go=%d py=%d", name, got.Stats.Records, want.Records)
		}
		if got.Stats.Rows != want.Rows {
			t.Errorf("%s: rows go=%d py=%d", name, got.Stats.Rows, want.Rows)
		}
		for lbl, kind := range label {
			if want.ByKind[lbl] != got.Stats.ByKind[string(kind)] {
				t.Errorf("%s: %s go=%d py=%d", name, lbl,
					got.Stats.ByKind[string(kind)], want.ByKind[lbl])
			}
		}
		if len(got.Stats.Unmapped) != len(want.Unmapped) {
			t.Errorf("%s: unmapped go=%v py=%v", name, got.Stats.Unmapped, want.Unmapped)
		}
		checked++
		totalRecords += got.Stats.Records
		totalRows += got.Stats.Rows
	}
	t.Logf("parity verified across %d transcripts, %d records, %d feed rows",
		checked, totalRecords, totalRows)
}

func exists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func TestEnvelopesTheCLIWritesForLocalAndShellCommands(t *testing.T) {
	cases := []struct{ in, kind, display string }{
		{"<local-command-caveat>Caveat: The messages below were generated by the user</local-command-caveat>", "silent", ""},
		{"<local-command-stderr>oops</local-command-stderr>", "silent", ""},
		{"<bash-input>xbox-pair</bash-input>", "event", "You ran ! xbox-pair"},
		{"<bash-stdout>paired</bash-stdout><bash-stderr></bash-stderr>", "silent", ""},
		{"<bash-stderr>denied</bash-stderr>", "silent", ""},
		{"Run the tests", "user", "Run the tests"},
	}
	for _, c := range cases {
		kind, display := ClassifyUser(c.in)
		if kind != c.kind || (c.kind != "silent" && display != c.display) {
			t.Errorf("ClassifyUser(%q) = (%q, %q), want (%q, %q)", c.in, kind, display, c.kind, c.display)
		}
	}
}

// Current Claude Code names the subagent tool Agent (it was Task): its row says what was
// delegated and carries the call, so the phone can follow the agent it started.
func TestAnAgentCallIsDelegatedWork(t *testing.T) {
	res := parse(t, `{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_9","name":"Agent","input":{"description":"Fix the flaky test","subagent_type":"general-purpose","model":"sonnet","run_in_background":true,"prompt":"The login test fails one run in five."}}]}}`)
	if len(res.Rows) != 1 {
		t.Fatalf("want one row, got %+v", res.Rows)
	}
	r := res.Rows[0]
	if r.Text != "Delegated Fix the flaky test" || r.ToolID != "toolu_9" || r.Detail != "The login test fails one run in five." {
		t.Errorf("row: %+v", r)
	}
	if r.Agent == nil || r.Agent.Type != "general-purpose" || r.Agent.Model != "sonnet" || !r.Agent.Background || r.Agent.Description != "Fix the flaky test" {
		t.Errorf("agent call: %+v", r.Agent)
	}
}

// /rename writes custom-title: the person's name for the session, which a title Claude
// Code generates later does not replace.
func TestTheNameThePersonGaveWins(t *testing.T) {
	res := parse(t,
		`{"type":"ai-title","aiTitle":"Probe some commands"}`,
	)
	if res.Meta.Title != "Probe some commands" {
		t.Fatalf("ai-title: %q", res.Meta.Title)
	}
	res = parse(t,
		`{"type":"ai-title","aiTitle":"Probe some commands"}`,
		`{"type":"custom-title","customTitle":"probe commands","sessionId":"s"}`,
		`{"type":"ai-title","aiTitle":"Probe some commands again"}`,
	)
	if res.Meta.Title != "probe commands" {
		t.Fatalf("title %q", res.Meta.Title)
	}
	if len(res.Stats.Unmapped) != 0 {
		t.Fatalf("custom-title is a known record: %v", res.Stats.Unmapped)
	}
}

// A tool row names its tool, and an edit's result says how many lines it added and
// removed: from the patch the CLI recorded, else estimated from the call's input.
func TestAToolRowNamesItsToolAndItsResultItsLines(t *testing.T) {
	res := parse(t,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"/srv/a.go","old_string":"x\ny","new_string":"x\nY\nz"}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"The file has been updated."}]},"toolUseResult":{"filePath":"/srv/a.go","structuredPatch":[{"oldStart":1,"oldLines":3,"newStart":1,"newLines":4,"lines":[" x","-y","+Y","+z"," w"]}]}}`,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"Write","input":{"file_path":"/srv/b.go","content":"one\ntwo\nthree\n"}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"File created"}]},"toolUseResult":{"type":"create","filePath":"/srv/b.go","content":"one\ntwo\nthree\n","structuredPatch":[]}}`,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"/srv/c.go","old_string":"a\nb","new_string":"a\nb\nc\nd"}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t3","content":"The file has been updated."}]}}`,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t4","name":"Edit","input":{"file_path":"/srv/d.go","old_string":"q","new_string":"r"}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t4","is_error":true,"content":"<tool_use_error>String to replace not found in file.</tool_use_error>"}]},"toolUseResult":"Error: String to replace not found in file."}`,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t5","name":"Bash","input":{"command":"go test ./..."}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t5","content":"ok"}]}}`,
	)
	want := []struct {
		kind     Kind
		tool     string
		add, del int
		hasDiff  bool
	}{
		{KindTool, "Edit", 0, 0, false}, {KindResult, "", 2, 1, true},
		{KindTool, "Write", 0, 0, false}, {KindResult, "", 3, 0, true},
		{KindTool, "Edit", 0, 0, false}, {KindResult, "", 2, 0, true}, // no patch: the input, less the shared lines
		{KindTool, "Edit", 0, 0, false}, {KindResult, "", 0, 0, false}, // failed: nothing changed
		{KindTool, "Bash", 0, 0, false}, {KindResult, "", 0, 0, false},
	}
	if len(res.Rows) != len(want) {
		t.Fatalf("rows: %d", len(res.Rows))
	}
	for i, w := range want {
		r := res.Rows[i]
		if r.Kind != w.kind || r.Tool != w.tool || (r.Diff != nil) != w.hasDiff || (r.Diff != nil && (r.Diff.Add != w.add || r.Diff.Del != w.del)) {
			t.Errorf("row %d: %s %q %+v, want %+v", i, r.Kind, r.Tool, r.Diff, w)
		}
	}
}

// The tailer reads a transcript in batches: an edit's call and its result can land in
// different ones, and the count still comes out.
func TestAnEditCountsAcrossBatches(t *testing.T) {
	first, err := Parse(strings.NewReader(`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"/srv/a.go","old_string":"a","new_string":"b\nc"}}]}}` + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	next, err := ParseFrom(strings.NewReader(`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}`+"\n"), first.State)
	if err != nil {
		t.Fatal(err)
	}
	if r := next.Rows[0]; r.Diff == nil || r.Diff.Add != 2 || r.Diff.Del != 1 {
		t.Fatalf("result: %+v %+v", r, r.Diff)
	}
	if len(first.State.edits) != 1 || len(next.State.edits) != 0 {
		t.Fatalf("waiting edits: %d then %d", len(first.State.edits), len(next.State.edits))
	}
}
