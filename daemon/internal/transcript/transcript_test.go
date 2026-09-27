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

func TestSlashCommandBecomesANamedEvent(t *testing.T) {
	res := parse(t, `{"type":"user","message":{"content":"<command-name>compact</command-name><args/>"}}`)
	if len(res.Rows) != 1 || res.Rows[0].Text != "You ran /compact" {
		t.Fatalf("got %+v", res.Rows)
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
