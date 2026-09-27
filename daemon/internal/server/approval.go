package server

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"sync"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
)

// ApprovalWait is how long a phone has to answer before the request falls back to the
// prompt at the desk. Well under Claude's own hook timeout, so the fallback is ours to
// control rather than a hard kill.
const ApprovalWait = 90 * time.Second

// PermissionEvent is the part of the PermissionRequest hook payload Porthole uses.
// Field names are the hook's, not ours.
type PermissionEvent struct {
	SessionID      string          `json:"session_id"`
	Cwd            string          `json:"cwd"`
	PermissionMode string          `json:"permission_mode"`
	HookEventName  string          `json:"hook_event_name"`
	ToolName       string          `json:"tool_name"`
	ToolInput      json.RawMessage `json:"tool_input"`
	ToolUseID      string          `json:"tool_use_id"`
}

// Decision is what the hook prints back to Claude Code. An empty Decision means "we did
// not decide" - the hook stays silent and Claude asks at the desk.
type Decision struct {
	Decision string `json:"decision,omitempty"`
	Reason   string `json:"reason,omitempty"`
}

// HookOutput is what the PermissionRequest hook prints for a decision, in the shape
// Claude Code validates: the decision is an object with a behavior, never a bare string
// (a string fails the CLI's schema, and the call silently falls back to the prompt at the
// desk). ok is false for anything but allow or deny, and then the hook prints nothing and
// the desk decides.
func HookOutput(d Decision) (out []byte, ok bool) {
	type decision struct {
		Behavior string `json:"behavior"`
		Message  string `json:"message,omitempty"`
	}
	var dec decision
	switch d.Decision {
	case "allow":
		dec = decision{Behavior: "allow"}
	case "deny":
		msg := "Denied from Porthole"
		if d.Reason != "" {
			msg += ": " + d.Reason
		}
		dec = decision{Behavior: "deny", Message: msg}
	default:
		return nil, false
	}
	b, err := json.Marshal(map[string]any{
		"hookSpecificOutput": map[string]any{
			"hookEventName": "PermissionRequest",
			"decision":      dec,
		},
	})
	return b, err == nil
}

type permissionRequestFrame struct {
	proto.Frame
	ToolUseID string `json:"tool_use_id"`
	SessionID string `json:"session_id"`
	ToolName  string `json:"tool_name"`
	Command   string `json:"command,omitempty"`
	Cwd       string `json:"cwd,omitempty"`
	ExpiresIn int    `json:"expires_in_seconds"`
}

// randomID is a correlation key for one in-flight request.
func randomID() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		return "porthole-fallback"
	}
	return "ph_" + hex.EncodeToString(b)
}

type pending struct {
	ch   chan Decision
	once sync.Once
}

func (p *pending) resolve(d Decision) {
	p.once.Do(func() { p.ch <- d })
}

type approvals struct {
	mu   sync.Mutex
	open map[string]*pending // tool_use_id -> waiter
}

func newApprovals() *approvals { return &approvals{open: map[string]*pending{}} }

func (a *approvals) add(id string) *pending {
	p := &pending{ch: make(chan Decision, 1)}
	a.mu.Lock()
	a.open[id] = p
	a.mu.Unlock()
	return p
}

func (a *approvals) remove(id string) {
	a.mu.Lock()
	delete(a.open, id)
	a.mu.Unlock()
}

// Decide resolves a waiting request. Returns false if nothing was waiting - a late or
// duplicate answer must not crash anything.
func (a *approvals) Decide(id string, d Decision) bool {
	a.mu.Lock()
	p, ok := a.open[id]
	a.mu.Unlock()
	if !ok {
		return false
	}
	p.resolve(d)
	return true
}

// AskDevices forwards a permission request to every connected device and waits.
//
// The contract that matters: if no device answers in time, this returns an EMPTY
// decision. It never returns "allow" on a timeout. A convenience feature that silently
// grants permission because a phone was in a tunnel would be indefensible.
func (s *Server) AskDevices(ctx context.Context, ev PermissionEvent) Decision {
	// A session running in bypassPermissions has already decided not to be asked, and
	// the hook fires there anyway - verified against a real payload. Forwarding those
	// would put a card on the phone for every single tool call and block each one until
	// it is answered. Respect the mode.
	if ev.PermissionMode == "bypassPermissions" {
		return Decision{}
	}

	// The documented payload includes tool_use_id, but this Claude Code build does not
	// send it for PermissionRequest. Correlation is only ever between this daemon and
	// the phone, so mint an id rather than dropping the request - requiring the field
	// meant every real prompt silently fell through to the desk.
	id := ev.ToolUseID
	if id == "" {
		id = randomID()
	}

	// Pull the command out for display. The phone shows it verbatim, so this is the one
	// field that must never be summarised or truncated on the way out.
	var input struct {
		Command string `json:"command"`
	}
	_ = json.Unmarshal(ev.ToolInput, &input)

	frame := permissionRequestFrame{
		Frame:     proto.Frame{V: proto.Version, Type: proto.TypePermissionRequest},
		ToolUseID: id,
		SessionID: ev.SessionID,
		ToolName:  ev.ToolName,
		Command:   input.Command,
		Cwd:       ev.Cwd,
		ExpiresIn: int(ApprovalWait.Seconds()),
	}

	sent := s.broadcast(ctx, frame)
	if sent == 0 {
		// Nobody is connected. Say so immediately instead of making the user at the desk
		// wait 90 seconds for a phone that was never going to answer.
		s.log.Info("permission request with no device connected", "tool", ev.ToolName)
		return Decision{}
	}

	p := s.approvals.add(id)
	defer s.approvals.remove(id)
	s.log.Info("permission request sent to devices",
		"tool", ev.ToolName, "devices", sent, "id", id)

	select {
	case d := <-p.ch:
		s.log.Info("permission decided from device", "decision", d.Decision, "id", id)
		return d
	case <-time.After(ApprovalWait):
		s.log.Info("permission request timed out, falling back to the desk", "id", id)
		return Decision{}
	case <-ctx.Done():
		return Decision{}
	}
}

// broadcast writes a frame to every live socket, returning how many got it.
func (s *Server) broadcast(ctx context.Context, v any) int {
	s.mu.Lock()
	writers := make([]*writer, 0, len(s.conns))
	for _, socks := range s.conns {
		for c := range socks {
			if w, ok := s.writers[c]; ok {
				writers = append(writers, w)
			}
		}
	}
	s.mu.Unlock()

	n := 0
	for _, w := range writers {
		if err := w.send(ctx, v); err == nil {
			n++
		}
	}
	return n
}
