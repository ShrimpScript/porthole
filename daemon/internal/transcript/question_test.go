package transcript

import (
	"strings"
	"testing"
)

// Records as Claude Code 2.1.270 writes them for an AskUserQuestion exchange, trimmed.
const askPrompt = `{"type":"user","message":{"role":"user","content":"Use the AskUserQuestion tool to ask me one question."},"timestamp":"2026-09-14T06:05:22.208Z","cwd":"/srv/proj","sessionId":"fixture"}`
const askUse = `{"type":"assistant","message":{"model":"claude-fable-5-1","role":"assistant","content":[{"type":"tool_use","id":"toolu_01Q","name":"AskUserQuestion","input":{"questions":[{"question":"Which colour?","header":"Colour","options":[{"label":"Red","description":"warm"},{"label":"Blue","description":"cool"}],"multiSelect":false}]}}],"stop_reason":"tool_use"},"timestamp":"2026-09-14T06:05:30.000Z","cwd":"/srv/proj","sessionId":"fixture"}`
const askResult = `{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01Q","type":"tool_result","content":"Your questions have been answered: \"Which colour?\"=\"Blue\". You can now continue with these answers in mind."}]},"timestamp":"2026-09-14T06:05:41.000Z","cwd":"/srv/proj","sessionId":"fixture"}`
const askReply = `{"type":"assistant","message":{"model":"claude-fable-5-1","role":"assistant","content":[{"type":"text","text":"Blue"}],"stop_reason":"end_turn"},"timestamp":"2026-09-14T06:05:43.000Z","cwd":"/srv/proj","sessionId":"fixture"}`

func TestAskUserQuestionBecomesAQuestionRow(t *testing.T) {
	res, err := Parse(strings.NewReader(askPrompt + "\n" + askUse + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Rows) != 2 || res.Rows[1].Kind != KindQuestion {
		t.Fatalf("rows: %+v", res.Rows)
	}
	q := res.Rows[1]
	if q.Text != "Which colour?" || q.ToolID != "toolu_01Q" || len(q.Questions) != 1 {
		t.Fatalf("question row: %+v", q)
	}
	if got := q.Questions[0]; got.Header != "Colour" || got.MultiSelect || len(got.Options) != 2 ||
		got.Options[0].Label != "Red" || got.Options[0].Description != "warm" || got.Options[1].Label != "Blue" {
		t.Fatalf("parsed question: %+v", got)
	}
	if !res.State.Working || res.State.Asking != "Which colour?" {
		t.Fatalf("while the question is up the turn is open and Asking is set: %+v", res.State)
	}
}

func TestAnsweringClearsAsking(t *testing.T) {
	res, err := Parse(strings.NewReader(askPrompt + "\n" + askUse + "\n" + askResult + "\n" + askReply + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	kinds := []Kind{}
	for _, r := range res.Rows {
		kinds = append(kinds, r.Kind)
	}
	want := []Kind{KindUser, KindQuestion, KindResult, KindAssistant}
	if len(kinds) != len(want) {
		t.Fatalf("kinds %v", kinds)
	}
	for i := range want {
		if kinds[i] != want[i] {
			t.Fatalf("kinds %v, want %v", kinds, want)
		}
	}
	if res.Rows[2].ToolID != "toolu_01Q" || res.Rows[2].Text != "Answered: Blue" {
		t.Errorf("the answer pairs with the question by tool id and names the choice: %+v", res.Rows[2])
	}
	if res.State.Asking != "" || res.State.Working {
		t.Fatalf("after the answer and the reply: %+v", res.State)
	}
	// Incremental: state carried across parses keeps Asking until the answer lands.
	first, _ := Parse(strings.NewReader(askPrompt + "\n" + askUse + "\n"))
	second, _ := ParseFrom(strings.NewReader(askResult+"\n"), first.State)
	if second.State.Asking != "" {
		t.Error("the tool result must clear Asking in an incremental parse too")
	}
}

func TestAnswerTextFormats(t *testing.T) {
	cases := map[string]string{
		`Your questions have been answered: "Which colour?"="Blue". You can now continue with these answers in mind.`:                        "Blue",
		`Your questions have been answered: "Which toppings?"="Olives, Cheese". You can now continue with these answers in mind.`:            "Olives, Cheese",
		`Your questions have been answered: "Which colour?"="Blue", "Which size?"="Small". You can now continue with these answers in mind.`: "Blue · Small",
		"User answered Claude's questions:\n  · Which colour? → Blue":                                                                        "Blue",
		"nothing here": "",
	}
	for in, want := range cases {
		if got := answerText(in); got != want {
			t.Errorf("%q: got %q want %q", in, got, want)
		}
	}
}
