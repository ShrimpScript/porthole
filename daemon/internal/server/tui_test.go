package server

import "testing"

func TestParseTUIWorking(t *testing.T) {
	screen := "❯ Reply with pong\n✽ Nebulizing… (12s · ↑ 1.2k tokens · esc to interrupt)\n────\n❯ \n────\n  ⏵⏵ bypass permissions on (shift+tab to cycle) · esc to interrupt · ← for ag…\n"
	f := parseTUI(screen)
	if !f.Working || f.Text != "Nebulizing…" || f.Elapsed != "12s" || f.Tokens != "1.2k tokens" || !f.Interruptible {
		t.Fatalf("got %+v", f)
	}
	if f.PermissionMode != "bypass permissions on" {
		t.Fatalf("permission mode: %q", f.PermissionMode)
	}
}

func TestParseTUIIdle(t *testing.T) {
	screen := "● pong\n✻ Baked for 1s · done 1:04 AM\n────\n❯ \n────\n  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents\n"
	f := parseTUI(screen)
	if f.Working || f.Text != "" || f.Interruptible {
		t.Fatalf("idle screen parsed as working: %+v", f)
	}
}

func TestParseTUIBareSpinner(t *testing.T) {
	f := parseTUI("· Razzmatazzing…\n")
	if !f.Working || f.Text != "Razzmatazzing…" {
		t.Fatalf("got %+v", f)
	}
}

func TestPanelTabsSignature(t *testing.T) {
	if !panelTabs.MatchString("  Settings  Status  Config  Usage  Stats\n") {
		t.Fatal("the status panel header should match")
	}
	if panelTabs.MatchString("❯ Reply with pong\n✽ Nebulizing…\n") {
		t.Fatal("a working screen must not match")
	}
}

// The usage-limit lines are the documented ones (interactive-mode, "Wait for a usage
// limit to reset"); no live limit was available to capture.
func TestParseTUIUsageLimit(t *testing.T) {
	f := parseTUI("Usage limit reached · continuing automatically at 3:45pm · esc to cancel\n")
	if !f.LimitWaiting || f.LimitResumeAt != "3:45pm" || f.LimitEnter || f.LimitStopped {
		t.Fatalf("waiting line: %+v", f)
	}
	f = parseTUI("Your usage limit has reset · press enter to continue\n")
	if !f.LimitEnter || f.LimitWaiting {
		t.Fatalf("enter line: %+v", f)
	}
	f = parseTUI("Automatic continue stopped after repeated usage-limit hits · /rate-limit-options to try again\n")
	if !f.LimitStopped {
		t.Fatalf("stopped line: %+v", f)
	}
	f = parseTUI("You've hit your session limit · resets 3:45pm\n")
	if f.LimitText == "" || f.LimitWaiting {
		t.Fatalf("hit line: %+v", f)
	}
	if f := parseTUI("❯ \n"); f.LimitText != "" {
		t.Fatalf("idle screen has no limit: %+v", f)
	}
}

func TestParseTUIEffort(t *testing.T) {
	screen := "  ⎿  Set effort level to high (saved as your default for new sessions):\n                                                              ● high · /effort\n────\n❯ \n────\n  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents\n"
	if f := parseTUI(screen); f.Effort != "high" || f.PermissionMode != "bypass permissions on" {
		t.Fatalf("got %+v", f)
	}
	if f := parseTUI("✻ Churned for 3s · done 11:05 PM\n                                                             ◉ xhigh · /effort\n"); f.Effort != "xhigh" {
		t.Fatalf("default marker: %+v", f)
	}
	if f := parseTUI("❯ \n"); f.Effort != "" {
		t.Fatalf("no effort line: %+v", f)
	}
}

func TestParseTUIPermissionModes(t *testing.T) {
	// Every line Shift-Tab produced on 2.1.270, verbatim.
	cases := map[string]string{
		"  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents": "bypass permissions on",
		"  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents":          "auto mode on",
		"  ⏸ manual mode on · ? for shortcuts · ← for agents":            "manual mode on",
		"  ⏵⏵ accept edits on (shift+tab to cycle) · ← for agents":       "accept edits on",
		"  ⏸ plan mode on (shift+tab to cycle) · ← for agents":           "plan mode on",
	}
	for line, want := range cases {
		if f := parseTUI("❯ \n" + line + "\n"); f.PermissionMode != want {
			t.Errorf("%q: got %q want %q", line, f.PermissionMode, want)
		}
	}
}
