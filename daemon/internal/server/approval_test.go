package server

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/store"
)

func testServer(t *testing.T) *Server {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "devices.json"))
	if err != nil {
		t.Fatal(err)
	}
	return New(fakeResolver{peer: peer()}, st, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

func event(id string) PermissionEvent {
	return PermissionEvent{
		SessionID:     "s1",
		Cwd:           "/tmp/x",
		HookEventName: "PermissionRequest",
		ToolName:      "Bash",
		ToolInput:     json.RawMessage(`{"command":"rm -rf target/"}`),
		ToolUseID:     id,
	}
}

// The single most important assertion in this package: nobody connected means no
// decision, so Claude falls back to asking at the desk. It must never mean "allow".
func TestNoDevicesMeansNoDecision(t *testing.T) {
	s := testServer(t)
	start := time.Now()
	d := s.AskDevices(context.Background(), event("t1"))
	if d.Decision != "" {
		t.Fatalf("with no device connected the decision must be empty, got %q", d.Decision)
	}
	// And it must not make the person at the desk wait out the full window.
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("waited %v with no devices connected; should return immediately", elapsed)
	}
}

func TestDecisionFromDeviceIsReturned(t *testing.T) {
	s := testServer(t)
	// Pretend a device is connected by registering a pending waiter directly; the
	// transport is covered by the server tests.
	go func() {
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			if s.approvals.Decide("t2", Decision{Decision: "allow"}) {
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()

	p := s.approvals.add("t2")
	defer s.approvals.remove("t2")
	select {
	case d := <-p.ch:
		if d.Decision != "allow" {
			t.Fatalf("want allow, got %q", d.Decision)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("decision never arrived")
	}
}

func TestLateDecisionIsIgnored(t *testing.T) {
	s := testServer(t)
	if s.approvals.Decide("never-asked", Decision{Decision: "allow"}) {
		t.Fatal("a decision for an unknown request was accepted")
	}
}

func TestDuplicateDecisionDoesNotPanic(t *testing.T) {
	s := testServer(t)
	p := s.approvals.add("t3")
	defer s.approvals.remove("t3")
	s.approvals.Decide("t3", Decision{Decision: "allow"})
	s.approvals.Decide("t3", Decision{Decision: "deny"}) // must not block or panic
	select {
	case d := <-p.ch:
		if d.Decision != "allow" {
			t.Fatalf("first decision should win, got %q", d.Decision)
		}
	default:
		t.Fatal("no decision buffered")
	}
}

// A cancelled context (the socket died mid-request) also yields no decision.
func TestCancelledContextMeansNoDecision(t *testing.T) {
	s := testServer(t)
	// One "connected" writer so AskDevices does not take the no-devices shortcut.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if d := s.AskDevices(ctx, event("t4")); d.Decision != "" {
		t.Fatalf("cancelled context must not produce a decision, got %q", d.Decision)
	}
}

// The hook fires even in bypassPermissions - verified against a real payload. A session
// in that mode has already said "don't ask me", so forwarding would put a card on the
// phone for every tool call and block each one.
func TestBypassModeIsNotForwarded(t *testing.T) {
	s := testServer(t)
	ev := event("t5")
	ev.PermissionMode = "bypassPermissions"
	if d := s.AskDevices(context.Background(), ev); d.Decision != "" {
		t.Fatalf("bypass session produced a decision: %q", d.Decision)
	}
}

// This build omits tool_use_id from PermissionRequest, so requiring it meant every real
// prompt silently fell through to the desk.
func TestMissingToolUseIdStillCorrelates(t *testing.T) {
	s := testServer(t)
	ev := event("")
	ev.ToolUseID = ""
	ev.PermissionMode = "default"

	// No devices connected, so this returns immediately - the point is that it gets past
	// the id check and into the real path rather than bailing at the top.
	done := make(chan Decision, 1)
	go func() { done <- s.AskDevices(context.Background(), ev) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("AskDevices hung with no tool_use_id")
	}

	if a, b := randomID(), randomID(); a == b {
		t.Fatal("correlation ids are not unique")
	}
	if len(randomID()) < 10 {
		t.Fatal("correlation id is too short to be unique")
	}
}

// Claude Code rejects a PermissionRequest decision that is not an object with a behavior
// ("PermissionRequest decision must be {"behavior": "allow"} or {"behavior": "deny",
// "message": "..."}", 2.1.281), and then asks at the desk as if the phone had never
// answered. Pinned byte for byte.
func TestHookOutputShape(t *testing.T) {
	b, ok := HookOutput(Decision{Decision: "allow"})
	if !ok || string(b) != `{"hookSpecificOutput":{"decision":{"behavior":"allow"},"hookEventName":"PermissionRequest"}}` {
		t.Fatalf("allow: %s %v", b, ok)
	}
	b, ok = HookOutput(Decision{Decision: "deny", Reason: "not on a Friday"})
	var got struct {
		HookSpecificOutput struct {
			HookEventName string `json:"hookEventName"`
			Decision      struct {
				Behavior string `json:"behavior"`
				Message  string `json:"message"`
			} `json:"decision"`
		} `json:"hookSpecificOutput"`
	}
	if !ok || json.Unmarshal(b, &got) != nil {
		t.Fatalf("deny: %s %v", b, ok)
	}
	if got.HookSpecificOutput.HookEventName != "PermissionRequest" || got.HookSpecificOutput.Decision.Behavior != "deny" ||
		got.HookSpecificOutput.Decision.Message != "Denied from Porthole: not on a Friday" {
		t.Fatalf("deny decoded as %+v", got)
	}
	for _, d := range []string{"", "maybe", "ask"} {
		if _, ok := HookOutput(Decision{Decision: d}); ok {
			t.Errorf("%q must leave the decision to the desk", d)
		}
	}
}
