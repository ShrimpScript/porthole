package server

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/session"
)

// The control socket is how `portholed pair|devices|revoke` reach the running daemon,
// and later how hooks deliver events. It is a unix socket owned by the user at 0600 -
// never a TCP port, because nothing here should be reachable from the network.
func ControlSocketPath() string {
	if p := os.Getenv("PORTHOLE_SOCK"); p != "" {
		return p
	}
	if d := os.Getenv("XDG_RUNTIME_DIR"); d != "" {
		return filepath.Join(d, "portholed.sock")
	}
	return filepath.Join(os.TempDir(), fmt.Sprintf("portholed-%d.sock", os.Getuid()))
}

type ctlRequest struct {
	Cmd    string          `json:"cmd"`
	NodeID string          `json:"node_id,omitempty"`
	Event  json.RawMessage `json:"event,omitempty"`
	Wait   int             `json:"wait,omitempty"` // access: seconds to wait for an answer at the Mac
}

type ctlResponse struct {
	OK       bool           `json:"ok"`
	Error    string         `json:"error,omitempty"`
	Code     string         `json:"code,omitempty"`
	Expires  time.Time      `json:"expires,omitempty"`
	Devices  []*deviceView  `json:"devices,omitempty"`
	Status   *statusView    `json:"status,omitempty"`
	Sessions []session.Info `json:"sessions,omitempty"`
	Decision string         `json:"decision,omitempty"`
	Access   []FolderAccess `json:"access,omitempty"`
	Reason   string         `json:"reason,omitempty"`
}

type deviceView struct {
	NodeID   string    `json:"node_id"`
	Name     string    `json:"name"`
	User     string    `json:"user"`
	PairedAt time.Time `json:"paired_at"`
	LastSeen time.Time `json:"last_seen,omitempty"`
	Live     int       `json:"live"`
}

type statusView struct {
	Version string `json:"version"`
	Devices int    `json:"devices"`
	Live    int    `json:"live"`
}

func ListenControl(path string) (net.Listener, error) {
	if path == "" {
		path = ControlSocketPath()
	}
	// A stale socket from an unclean shutdown must not block startup.
	if _, err := os.Stat(path); err == nil {
		if c, err := net.DialTimeout("unix", path, 300*time.Millisecond); err == nil {
			c.Close()
			return nil, fmt.Errorf("portholed is already running (%s)", path)
		}
		_ = os.Remove(path)
	}
	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o600); err != nil {
		ln.Close()
		return nil, err
	}
	return ln, nil
}

func (s *Server) ServeControl(ln net.Listener) error {
	for {
		c, err := ln.Accept()
		if err != nil {
			return err
		}
		go s.handleControl(c)
	}
}

func (s *Server) handleControl(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	dec := json.NewDecoder(bufio.NewReader(c))
	enc := json.NewEncoder(c)

	var req ctlRequest
	if err := dec.Decode(&req); err != nil {
		_ = enc.Encode(ctlResponse{Error: "bad request"})
		return
	}

	switch req.Cmd {
	case "pair":
		code, exp, err := s.BeginPair()
		if err != nil {
			_ = enc.Encode(ctlResponse{Error: err.Error()})
			return
		}
		s.log.Info("pairing code issued", "expires_in", time.Until(exp).Round(time.Second))
		_ = enc.Encode(ctlResponse{OK: true, Code: code, Expires: exp})

	case "devices":
		var out []*deviceView
		for _, d := range s.Devices() {
			out = append(out, &deviceView{
				NodeID: d.NodeID, Name: d.Name, User: d.User,
				PairedAt: d.PairedAt, LastSeen: d.LastSeen, Live: s.LiveCount(d.NodeID),
			})
		}
		_ = enc.Encode(ctlResponse{OK: true, Devices: out})

	case "revoke":
		if req.NodeID == "" {
			_ = enc.Encode(ctlResponse{Error: "revoke needs a node id"})
			return
		}
		ok, err := s.Revoke(req.NodeID)
		if err != nil {
			_ = enc.Encode(ctlResponse{Error: err.Error()})
			return
		}
		if !ok {
			_ = enc.Encode(ctlResponse{Error: "no such paired device"})
			return
		}
		_ = enc.Encode(ctlResponse{OK: true})

	case "hook":
		// A PermissionRequest handler is blocking Claude Code at the desk while this
		// runs, so the deadline set above must not cut it short.
		_ = c.SetDeadline(time.Now().Add(ApprovalWait + 15*time.Second))
		var ev PermissionEvent
		if err := json.Unmarshal(req.Event, &ev); err != nil {
			_ = enc.Encode(ctlResponse{OK: true}) // undecided: Claude asks at the desk
			return
		}
		if ev.HookEventName != "PermissionRequest" {
			_ = enc.Encode(ctlResponse{OK: true})
			return
		}
		d := s.AskDevices(context.Background(), ev)
		_ = enc.Encode(ctlResponse{OK: true, Decision: d.Decision, Reason: d.Reason})

	case "sessions":
		list, err := session.List()
		if err != nil {
			_ = enc.Encode(ctlResponse{Error: err.Error()})
			return
		}
		_ = enc.Encode(ctlResponse{OK: true, Sessions: list})

	case "status":
		devs := s.Devices()
		live := 0
		for _, d := range devs {
			live += s.LiveCount(d.NodeID)
		}
		_ = enc.Encode(ctlResponse{OK: true, Status: &statusView{
			Version: Version, Devices: len(devs), Live: live,
		}})

	case "access":
		wait := time.Duration(req.Wait) * time.Second
		if wait <= 0 || wait > 3*time.Minute {
			wait = 3 * time.Second
		}
		// Each folder may wait on a person at the Mac, one after another.
		_ = c.SetDeadline(time.Now().Add(time.Duration(len(guardedFolders))*wait + 5*time.Second))
		_ = enc.Encode(ctlResponse{OK: true, Access: folderAccess(wait)})

	default:
		_ = enc.Encode(ctlResponse{Error: "unknown command " + req.Cmd})
	}
}

// Call sends one control command to a running daemon, for the quick queries.
func Call(path string, req any) (*ctlResponse, error) {
	return CallWithTimeout(path, req, 10*time.Second)
}

// CallWithTimeout is the same with an explicit deadline.
//
// The hook needs this: it waits on a PERSON reaching for their phone, so the ten
// seconds that suit `status` would silently discard a valid approval and fall back to
// the desk prompt - which looks exactly like the feature not working.
func CallWithTimeout(path string, req any, timeout time.Duration) (*ctlResponse, error) {
	if path == "" {
		path = ControlSocketPath()
	}
	c, err := net.DialTimeout("unix", path, 2*time.Second)
	if err != nil {
		return nil, fmt.Errorf("portholed is not running (%s)", path)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(timeout))
	if err := json.NewEncoder(c).Encode(req); err != nil {
		return nil, err
	}
	var resp ctlResponse
	if err := json.NewDecoder(c).Decode(&resp); err != nil {
		return nil, err
	}
	if resp.Error != "" {
		return &resp, fmt.Errorf("%s", resp.Error)
	}
	return &resp, nil
}
