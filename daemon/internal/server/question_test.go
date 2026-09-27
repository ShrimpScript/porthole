package server

import "testing"

// Captured from Claude Code 2.1.270 in tmux, verbatim.
const singleScreen = `❯ Use the AskUserQuestion tool to ask me one question with header Colour,
  question "Which colour?", options Red (warm) and Blue (cool). Then reply with
  exactly the word I chose.
────────────────────────────────────────────────────────────────────────────────
 ☐ Colour
Which colour?
❯ 1. Red
     warm
  2. Blue
     cool
  3. Type something.
────────────────────────────────────────────────────────────────────────────────
  4. Chat about this
Enter to select · ↑/↓ to navigate · Esc to cancel
`

const multiScreen = `←  ☒ Toppings  ✔ Submit  →
Which toppings?
❯ 1. [ ] Cheese
  Cheese
  2. [✔] Olives
  Olives
  3. [ ] Basil
  Basil
  4. [ ] Type something
     Submit
────────────────────────────────────────────────────────────────────────────────
  5. Chat about this
Enter to select · ↑/↓ to navigate · Esc to cancel
`

const twoScreen = `←  ☒ Colour  ☐ Size  ✔ Submit  →
Which size?
❯ 1. Small
     Small
  2. Large
     Large
  3. Type something.
────────────────────────────────────────────────────────────────────────────────
  4. Chat about this
Enter to select · Tab/Arrow keys to navigate · Esc to cancel
`

const reviewScreen = `←  ☒ Colour  ☒ Size  ✔ Submit  →
Review your answers
 ● Which colour?
   → Blue
 ● Which size?
   → Small
Ready to submit your answers?
❯ 1. Submit answers
  2. Cancel
`

const idleScreen = `● Blue
✻ Churned for 3s · done 11:05 PM
────────────────────────────────────────────────────────────────────────────────
❯
────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents
`

func TestParseQuestionSingle(t *testing.T) {
	q := parseQuestion(singleScreen)
	if q == nil {
		t.Fatal("no question")
	}
	if q.Header != "Colour" || q.Text != "Which colour?" || q.Multi || q.Review || q.Typed != 3 || q.Index != 1 || q.Total != 1 {
		t.Fatalf("%+v", q)
	}
	if len(q.Options) != 2 || q.Options[0].N != 1 || q.Options[0].Label != "Red" || q.Options[0].Description != "warm" ||
		q.Options[1].N != 2 || q.Options[1].Label != "Blue" || q.Options[1].Description != "cool" {
		t.Fatalf("options %+v", q.Options)
	}
}

func TestParseQuestionMulti(t *testing.T) {
	q := parseQuestion(multiScreen)
	if q == nil || !q.Multi || q.Text != "Which toppings?" || q.Header != "Toppings" || q.Typed != 4 {
		t.Fatalf("%+v", q)
	}
	if len(q.Options) != 3 || q.Options[1].Checked != true || q.Options[0].Checked || q.Options[2].Label != "Basil" {
		t.Fatalf("options %+v", q.Options)
	}
	// the CLI repeats the label as the description on a multi-select; not worth showing twice
	if q.Options[0].Description != "" {
		t.Errorf("duplicate description kept: %+v", q.Options[0])
	}
}

func TestParseQuestionSecondOfTwo(t *testing.T) {
	q := parseQuestion(twoScreen)
	if q == nil || q.Index != 2 || q.Total != 2 || q.Header != "Size" || q.Text != "Which size?" || len(q.Options) != 2 {
		t.Fatalf("%+v", q)
	}
}

func TestParseQuestionReview(t *testing.T) {
	q := parseQuestion(reviewScreen)
	if q == nil || !q.Review || len(q.Options) != 2 || q.Options[0].Label != "Submit answers" || q.Options[1].Label != "Cancel" {
		t.Fatalf("%+v", q)
	}
}

func TestParseQuestionAbsent(t *testing.T) {
	if q := parseQuestion(idleScreen); q != nil {
		t.Fatalf("idle screen parsed as a question: %+v", q)
	}
	if q := parseQuestion(""); q != nil {
		t.Fatal("empty screen")
	}
}
