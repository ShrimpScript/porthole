package server

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/shrimpscript/porthole/daemon/internal/session"
)

func TestPeekLinesKeepTheBottomOfTheScreen(t *testing.T) {
	screen := "first\n" + strings.Repeat("x\n", 20) + "╭─ a box drawn wider than the porthole can hold, so it is cut ────────────╮   \nlast line\n\n\n   \n"
	got := peekLines(screen, 4, 20)
	if len(got) != 4 {
		t.Fatalf("got %d lines, want 4: %q", len(got), got)
	}
	if got[3] != "last line" {
		t.Errorf("trailing blank lines kept: last is %q", got[3])
	}
	if r := []rune(got[2]); len(r) != 20 || !strings.HasPrefix(got[2], "╭─ a box") {
		t.Errorf("long line not cut to 20 runes: %q (%d)", got[2], len(r))
	}
	if peekLines("\n\n  \n", 4, 20) != nil {
		t.Error("a blank screen should have no peek")
	}
}

func TestTheListCarriesWhatThePaneShows(t *testing.T) {
	s := &Server{}
	q := &ScreenQuestion{Text: "Which colour?", Options: []ScreenOption{{N: 1, Label: "Red"}, {N: 2, Label: "Blue"}}, Typed: 3}
	s.setScreenAsking("live", "Which colour?")
	s.setScreenSeen("live", q, []string{"> Add dark mode", "? Which colour?"})
	s.setScreenSeen("gone", q, []string{"stale"})
	entries := s.listEntries([]session.Info{{ID: "live", Live: true}, {ID: "gone", Live: false}, {ID: "unscanned", Live: true}}, nil)
	if entries[0].Question == nil || entries[0].Question.Options[1].Label != "Blue" || len(entries[0].Peek) != 2 {
		t.Fatalf("live session lacks its screen: %+v", entries[0])
	}
	if entries[0].Asking != "Which colour?" {
		t.Errorf("asking not overlaid from the screen: %q", entries[0].Asking)
	}
	if entries[1].Question != nil || entries[1].Peek != nil {
		t.Errorf("a session that is not live must not carry an old screen: %+v", entries[1])
	}
	if entries[2].Question != nil || entries[2].Peek != nil {
		t.Errorf("an unscanned session has nothing to show: %+v", entries[2])
	}
	b, err := json.Marshal(entries)
	if err != nil {
		t.Fatal(err)
	}
	js := string(b)
	for _, want := range []string{`"id":"live"`, `"question":{"text":"Which colour?"`, `"peek":["\u003e Add dark mode"`, `"typed":3`} {
		if !strings.Contains(js, want) {
			t.Errorf("list JSON lacks %s:\n%s", want, js)
		}
	}
	// What the session package marshals stays where it was: an old phone reads the same keys.
	var old []session.Info
	if err := json.Unmarshal(b, &old); err != nil || old[0].ID != "live" || !old[0].Live {
		t.Errorf("an old reader cannot decode the list: %v %+v", err, old)
	}
	s.forgetScreenAsking("live")
	if _, ok := s.screenSeenFor("live"); ok {
		t.Error("forgetting a session must drop its screen")
	}
}

func TestAnAnswerFromAnOldListDoesNotPressTheNextQuestion(t *testing.T) {
	now := &ScreenQuestion{Text: "Delete the files?", Options: []ScreenOption{{N: 1, Label: "Yes"}, {N: 2, Label: "No"}}}
	if stillAsks(now, "Which colour?") {
		t.Error("a tap on the old question's choices must not reach the new question")
	}
	if !stillAsks(now, " Delete the files? ") {
		t.Error("the same question, give or take spaces, is still the one asked")
	}
	if !stillAsks(now, "") {
		t.Error("an app that sends no expectation keeps the old behaviour")
	}
	if stillAsks(nil, "Which colour?") {
		t.Error("nothing on screen is not the question the phone saw")
	}
}

func TestAPaneNotYetScannedIsReadWhenTheListIsBuilt(t *testing.T) {
	s := &Server{}
	reads := 0
	capture := func(pane string) (string, bool) {
		reads++
		if pane != "%3" {
			t.Errorf("captured %q, want the session's own pane", pane)
		}
		return "> Fix the flaky test\n● Bash(./gradlew test)\n  ⎿ Running…\n\n", true
	}
	list := []session.Info{{ID: "fresh", Live: true, Pane: "%3"}, {ID: "idle", Live: false, Pane: "%4"}, {ID: "nopane", Live: true}}
	entries := s.listEntries(list, capture)
	if reads != 1 {
		t.Fatalf("captured %d panes, want only the live one with a pane", reads)
	}
	if got := entries[0].Peek; len(got) != 3 || got[2] != "  ⎿ Running…" {
		t.Errorf("fresh capture not in the list: %q", got)
	}
	// Kept for the next list, so the pane is not read again until the scan updates it.
	s.listEntries(list, capture)
	if reads != 1 {
		t.Errorf("the second list read the pane again (%d reads)", reads)
	}
}
