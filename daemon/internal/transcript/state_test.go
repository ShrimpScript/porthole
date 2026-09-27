package transcript

import (
	"strings"
	"testing"
)

// A hand-written transcript: prompt -> tool call -> result -> reply (end_turn).
const stateSample = `{"type":"permission-mode","permissionMode":"bypassPermissions"}
{"type":"user","timestamp":"2026-09-11T08:00:00Z","message":{"role":"user","content":"Run the tests"}}
{"type":"assistant","timestamp":"2026-09-11T08:00:02Z","message":{"model":"claude-fable-5-1","stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":20,"cache_read_input_tokens":1000,"cache_creation_input_tokens":50,"output_tokens_details":{"thinking_tokens":5}},"content":[{"type":"tool_use","id":"tu_1","name":"Bash","input":{"command":"cargo test"}}]}}
{"type":"user","timestamp":"2026-09-11T08:00:05Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tu_1","content":"41 passed"}]}}
{"type":"assistant","timestamp":"2026-09-11T08:00:07Z","message":{"model":"claude-fable-5-1","stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":30,"cache_read_input_tokens":1100,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"All **41** pass."}]}}
`

func TestStateFollowsTheTurn(t *testing.T) {
	lines := strings.Split(strings.TrimSpace(stateSample), "\n")
	// Feed the records one at a time through ParseFrom, as the tailer does.
	var st State
	check := func(i int, working bool, pending string) {
		res, err := ParseFrom(strings.NewReader(lines[i]+"\n"), st)
		if err != nil {
			t.Fatal(err)
		}
		st = res.State
		if st.Working != working || st.PendingTool != pending {
			t.Fatalf("after record %d: working=%v pending=%q, want %v %q", i, st.Working, st.PendingTool, working, pending)
		}
	}
	check(0, false, "")
	check(1, true, "")     // the prompt starts a turn
	check(2, true, "Bash") // a tool call is in flight
	check(3, true, "")     // its result is in; Claude continues
	check(4, false, "")    // end_turn
	if st.PermissionMode != "bypassPermissions" || st.Model != "claude-fable-5-1" {
		t.Fatalf("mode/model not carried: %+v", st)
	}
	if st.Turns != 1 || st.Prompts != 1 || st.Replies != 1 || st.Tools["Bash"] != 1 {
		t.Fatalf("counts wrong: %+v", st)
	}
	if st.Usage != (Usage{Input: 15, Output: 50, CacheRead: 2100, CacheWrite: 50, Thinking: 5}) {
		t.Fatalf("usage wrong: %+v", st.Usage)
	}
	if st.LastContext != 1105 {
		t.Fatalf("last context = %d, want 1105", st.LastContext)
	}
}

func TestToolRowsPairWithResults(t *testing.T) {
	res, err := Parse(strings.NewReader(stateSample))
	if err != nil {
		t.Fatal(err)
	}
	var call, result *Row
	for i := range res.Rows {
		switch res.Rows[i].Kind {
		case KindTool:
			call = &res.Rows[i]
		case KindResult:
			result = &res.Rows[i]
		}
	}
	if call == nil || result == nil || call.ToolID != "tu_1" || result.ToolID != "tu_1" {
		t.Fatalf("tool ids not paired: call=%+v result=%+v", call, result)
	}
}

func TestInterruptEndsTheTurn(t *testing.T) {
	src := lines(stateSample, 0, 3) + `{"type":"user","timestamp":"2026-09-11T08:00:06Z","message":{"role":"user","content":"[Request interrupted by user]"}}` + "\n"
	res, err := Parse(strings.NewReader(src))
	if err != nil {
		t.Fatal(err)
	}
	if res.State.Working {
		t.Fatalf("interrupt should end the turn: %+v", res.State)
	}
}

func lines(s string, from, to int) string {
	ls := strings.Split(strings.TrimSpace(s), "\n")
	return strings.Join(ls[from:to+1], "\n") + "\n"
}

func TestZeroFailuresIsNotAFailure(t *testing.T) {
	for _, ok := range []string{"35 tests run, 0 failed", "no errors found", "failures: 0", "Errors=0, warnings=2"} {
		if looksFailed(ok) {
			t.Fatalf("%q should not look failed", ok)
		}
	}
	for _, bad := range []string{"1 failed", "Error: cannot find module", "build failed"} {
		if !looksFailed(bad) {
			t.Fatalf("%q should look failed", bad)
		}
	}
}
