package server

// Turn announcements: every live session is tailed from its current end, and a finished
// turn in any of them is broadcast to every connected phone. The phone shows "Done" for
// sessions it is not looking at; the attached session's feed already says it.

import (
	"context"
	"os/exec"
	"sync"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

type turnFrame struct {
	proto.Frame
	SessionID string `json:"session_id"`
	Title     string `json:"title"`
	Text      string `json:"text"` // "Worked for 1.9s"
}

type workingFrame struct {
	proto.Frame
	SessionID string    `json:"session_id"`
	Title     string    `json:"title"`
	Working   bool      `json:"working"`
	Since     time.Time `json:"since,omitempty"`
	Doing     string    `json:"doing,omitempty"`
	Asking    string    `json:"asking,omitempty"` // a question waiting on the person
}

const turnScanEvery = 5 * time.Second

// StartTurnWatcher keeps one tailer per live session while any phone is connected.
// Sessions that stop being live are dropped; ones that appear are picked up on the next
// scan. Tailing starts at the end of the transcript, so history is never announced.
func (s *Server) StartTurnWatcher(ctx context.Context) {
	go func() {
		watched := map[string]*watchEntry{} // transcript path -> the watch
		t := time.NewTicker(turnScanEvery)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				for _, e := range watched {
					e.stop()
				}
				return
			case <-t.C:
			}
			s.mu.Lock()
			connected := len(s.conns) > 0
			s.mu.Unlock()
			live := map[string]session.Info{}
			if connected {
				infos, err := session.List()
				if err != nil {
					continue
				}
				for _, si := range infos {
					if si.Live && si.Transcript != "" {
						live[si.Transcript] = si
					}
				}
			}
			for path, e := range watched {
				if _, ok := live[path]; !ok {
					e.stop()
					delete(watched, path)
					id, _ := e.current()
					s.forgetScreenAsking(id)
				}
			}
			// The screen is the only place a question shows while it waits: one
			// capture per live pane per scan, and a working frame when it changes.
			for _, si := range live {
				if si.Pane == "" {
					continue
				}
				out, err := exec.CommandContext(ctx, "tmux", "capture-pane", "-p", "-t", si.Pane).Output()
				if err != nil {
					continue
				}
				q := ""
				if sq := parseQuestion(string(out)); sq != nil {
					q = sq.Text
				}
				if s.setScreenAsking(si.ID, q) {
					s.broadcast(ctx, workingFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionWorking},
						SessionID: si.ID, Title: si.Title, Working: si.Working || q != "", Since: si.WorkingSince, Doing: si.Doing, Asking: q})
				}
			}
			for path, si := range live {
				if e, ok := watched[path]; ok {
					e.update(si.ID, si.Title) // a session names itself after a while
					continue
				}
				wctx, cancel := context.WithCancel(ctx)
				e := &watchEntry{stop: cancel, id: si.ID, title: si.Title}
				watched[path] = e
				go func() {
					_ = watchTurns(wctx, path, func(text string) {
						id, title := e.current()
						n := s.broadcast(wctx, turnFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionTurn},
							SessionID: id, Title: title, Text: text})
						s.log.Info("turn announced", "session", title, "text", text, "to", n)
					}, func(working bool, since time.Time, doing, asking string) {
						id, title := e.current()
						if q, ok := s.screenAsking(id); ok {
							asking = q // the screen knows; the transcript learns later
						}
						s.broadcast(wctx, workingFrame{Frame: proto.Frame{V: proto.Version, Type: proto.TypeSessionWorking},
							SessionID: id, Title: title, Working: working, Since: since, Doing: doing, Asking: asking})
					})
				}()
			}
		}
	}()
}

// watchEntry is one watched transcript; id and title are read at announce time, so a
// session that names itself after the watch began is announced by its name.
type watchEntry struct {
	stop  context.CancelFunc
	mu    sync.Mutex
	id    string
	title string
}

func (e *watchEntry) update(id, title string) {
	e.mu.Lock()
	e.id, e.title = id, title
	e.mu.Unlock()
}

func (e *watchEntry) current() (string, string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.id, e.title
}

// watchTurns tails one transcript from its current end and calls emit for each turn
// row that arrives, and onWorking whenever the working state or the current tool
// changes. It returns when ctx ends or the watch fails.
func watchTurns(ctx context.Context, path string, emit func(text string), onWorking func(working bool, since time.Time, doing, asking string)) error {
	tl := session.NewTailer(path)
	if err := tl.SeekEnd(); err != nil {
		return err
	}
	lastWorking, lastDoing, lastAsking := false, "", ""
	return tl.Watch(ctx, func(rows []transcript.Row, st transcript.State) {
		doing := ""
		for _, r := range rows {
			if r.Kind == transcript.KindTurn {
				emit(r.Text)
			}
			if r.Kind == transcript.KindTool {
				doing = r.Text
			}
		}
		if st.PendingTool == "" {
			doing = ""
		} else if doing == "" {
			doing = lastDoing
		}
		if onWorking != nil && (st.Working != lastWorking || (st.Working && (doing != lastDoing || st.Asking != lastAsking))) {
			onWorking(st.Working, st.WorkingSince, doing, st.Asking)
		}
		lastWorking, lastDoing, lastAsking = st.Working, doing, st.Asking
	})
}
