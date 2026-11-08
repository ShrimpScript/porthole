package server

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/inbox"
	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/session"
	"github.com/shrimpscript/porthole/daemon/internal/store"
)

// inboxMu keeps two answers from writing one decisions file at once; scanMu keeps scans
// from piling up when several phones connect together.
var inboxMu, scanMu sync.Mutex

// inboxProject is one project as the phone sees it.
type inboxProject struct {
	inbox.Project
	Live bool `json:"live"` // a Claude Code session runs in this folder: an answer is also typed to it
}

type inboxFrame struct {
	proto.Frame
	Configured bool           `json:"configured"` // some folders are set; when not, the phone says how
	Projects   []inboxProject `json:"projects"`
}

type inboxAnswered struct {
	proto.Frame
	Dir       string `json:"dir"`
	Item      string `json:"item"`
	Decisions string `json:"decisions,omitempty"` // the file it was recorded in, relative to the project
	Session   string `json:"session,omitempty"`   // the session that was told, by title
	Error     string `json:"error,omitempty"`
}

// InboxList reads the settings and every project's notes.
func InboxList() (inbox.Config, []inbox.Project, error) {
	dir := store.Dir()
	c, err := inbox.LoadConfig(dir)
	if err != nil {
		return c, nil, err
	}
	scanMu.Lock()
	defer scanMu.Unlock()
	return c, inbox.Scan(c, inbox.Dismissed(dir)), nil
}

// inboxSend scans and sends the inbox. A scan that finishes after one asked for later (an
// answer's) is not sent: it could still hold the question just answered.
func (s *Server) inboxSend(ctx context.Context, w *writer) {
	gen := w.inboxGen.Add(1)
	c, list, err := InboxList()
	w.inboxMu.Lock()
	defer w.inboxMu.Unlock()
	if gen < w.inboxSent {
		return
	}
	w.inboxSent = gen
	if err != nil {
		_ = w.send(ctx, proto.NewError("inbox", err.Error()))
		return
	}
	live := liveDirs()
	f := inboxFrame{
		Frame:      proto.Frame{V: proto.Version, Type: proto.TypeInbox},
		Configured: len(c.Folders) > 0,
		Projects:   make([]inboxProject, 0, len(list)),
	}
	for _, p := range list {
		f.Projects = append(f.Projects, inboxProject{Project: p, Live: live[samePath(p.Dir)]})
	}
	_ = w.send(ctx, f)
}

func (s *Server) inboxAnswer(ctx context.Context, w *writer, dir, id, text, device string) {
	res := inboxAnswered{Frame: proto.Frame{V: proto.Version, Type: proto.TypeInboxAnswered}, Dir: dir, Item: id}
	rel, title, err := AnswerInbox(ctx, dir, id, text)
	if err != nil {
		res.Error = err.Error()
		s.log.Warn("inbox answer failed", "from", device, "err", err)
	} else {
		res.Decisions, res.Session = rel, title
		s.log.Info("inbox answered", "from", device, "file", rel, "told", title != "")
	}
	_ = w.send(ctx, res)
	s.inboxSend(ctx, w)
}

func (s *Server) inboxDismiss(ctx context.Context, w *writer, dir, id, device string) {
	if id == "" || dir == "" {
		return
	}
	if err := inbox.Dismiss(store.Dir(), dir, id); err != nil {
		_ = w.send(ctx, proto.NewError("inbox", err.Error()))
		return
	}
	s.log.Info("inbox item dismissed", "from", device)
	s.inboxSend(ctx, w)
}

// AnswerInbox records the answer to one question in its project's decisions file, then
// types it to a Claude Code session running in that project, if there is one. It returns
// the decisions file (relative to the project) and the title of the session told. A
// session that could not be told is not an error: the answer is recorded either way.
func AnswerInbox(ctx context.Context, dir, id, text string) (string, string, error) {
	p, it, rel, err := recordAnswer(dir, id, text)
	if err != nil {
		return "", "", err
	}
	si, ok := runningIn(p.Dir)
	if !ok {
		return rel, "", nil
	}
	ensureReadable(ctx, captureTarget(si), slog.Default())
	dismissPanel(ctx, si)
	if submitted, err := typePrompt(ctx, si, inbox.Message(it, text, rel)); err != nil || !submitted {
		return rel, "", nil
	}
	return rel, si.Title, nil
}

func recordAnswer(dir, id, text string) (inbox.Project, inbox.Item, string, error) {
	inboxMu.Lock()
	defer inboxMu.Unlock()
	c, err := inbox.LoadConfig(store.Dir())
	if err != nil {
		return inbox.Project{}, inbox.Item{}, "", err
	}
	p, it, ok := inbox.Find(c, dir, id)
	if !ok {
		return p, it, "", errors.New("that question is no longer in the notes (answered, or rewritten)")
	}
	rel, err := inbox.Record(p, it, text, time.Now())
	return p, it, rel, err
}

// liveDirs are the folders a Claude Code session is running in.
func liveDirs() map[string]bool {
	set := map[string]bool{}
	list, _ := session.List()
	for _, si := range list {
		if si.Live {
			set[samePath(si.Cwd)] = true
		}
	}
	return set
}

// runningIn is the most recently active running session in dir.
func runningIn(dir string) (session.Info, bool) {
	list, _ := session.List()
	want := samePath(dir)
	var best session.Info
	found := false
	for _, si := range list {
		if si.Live && samePath(si.Cwd) == want && (!found || si.LastActive.After(best.LastActive)) {
			best, found = si, true
		}
	}
	return best, found
}
