package session

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/fsnotify/fsnotify"

	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// Tailer follows one transcript and emits new feed rows as they are written.
//
// Two properties matter and both come from evidence rather than caution:
//   - Only complete newline-terminated lines are parsed. Real transcripts contain
//     truncated lines from crashes mid-write, and a tail necessarily catches partial
//     writes, so a half-line is held in the buffer until its newline arrives.
//   - A write event carries no payload, so the tailer reads from its own offset rather
//     than re-reading the file. On a 65MB transcript, re-parsing per event is not an
//     option.
type Tailer struct {
	total  int // rows in the file at the last Backfill
	path   string
	offset int64
	buf    []byte
	// state carries working/usage across incremental parses.
	state transcript.State
	// OnSwitch is called when Claude Code starts a new session in the same directory
	// and the tailer moves to its transcript. A restarted CLI writes a NEW file; a
	// tailer pinned to the old one would show a phone a feed that never moves again.
	OnSwitch func(newPath string)
	// ShouldSwitch decides whether a transcript that appeared in the directory is this
	// session's successor. Nil follows any new file (the pre-registry behaviour).
	ShouldSwitch func(newPath string) bool
}

// Path is the transcript currently being followed.
func (t *Tailer) Path() string { return t.path }

// switchTo retargets the tailer at a fresh transcript, from its first byte.
func (t *Tailer) switchTo(path string) {
	t.path = path
	t.offset = 0
	t.buf = nil
	t.state = transcript.State{}
}

// State is the running picture after the latest parse.
func (t *Tailer) State() transcript.State { return t.state }

func NewTailer(path string) *Tailer { return &Tailer{path: path} }

// SeekEnd starts from the current end of file, for "watch from now on".
func (t *Tailer) SeekEnd() error {
	fi, err := os.Stat(t.path)
	if os.IsNotExist(err) {
		t.offset = 0 // a session that has not written yet: everything it writes is new
		return nil
	}
	if err != nil {
		return err
	}
	t.offset = fi.Size()
	return nil
}

// Backfill parses the whole transcript and returns at most last rows, so a phone that
// attaches mid-session sees history rather than an empty screen.
func (t *Tailer) Backfill(last int) (*transcript.Result, error) {
	res, err := transcript.ParseFile(t.path)
	if os.IsNotExist(err) {
		// Registered and running, nothing said yet. An empty feed is the truth.
		t.state, t.total = transcript.State{}, 0
		return &transcript.Result{State: transcript.State{}}, nil
	}
	if err != nil {
		return nil, err
	}
	t.state = res.State
	t.total = len(res.Rows)
	if last > 0 && len(res.Rows) > last {
		res.Rows = res.Rows[len(res.Rows)-last:]
	}
	return res, nil
}

// Total is how many rows the transcript held at the last Backfill, so a caller can say
// how much history it did not send.
func (t *Tailer) Total() int { return t.total }

// readNew consumes whatever complete lines have been appended since the last read.
func (t *Tailer) readNew() ([]transcript.Row, error) {
	f, err := os.Open(t.path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	fi, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if fi.Size() < t.offset {
		// Truncated or replaced: start over rather than emit garbage from a stale offset.
		t.offset, t.buf = 0, nil
	}
	if fi.Size() == t.offset {
		return nil, nil
	}
	if _, err := f.Seek(t.offset, io.SeekStart); err != nil {
		return nil, err
	}
	chunk, err := io.ReadAll(f)
	if err != nil {
		return nil, err
	}
	t.offset += int64(len(chunk))
	data := append(t.buf, chunk...)

	// Keep any trailing partial line for the next round.
	cut := bytes.LastIndexByte(data, '\n')
	if cut < 0 {
		t.buf = data
		return nil, nil
	}
	complete := data[:cut]
	t.buf = append([]byte(nil), data[cut+1:]...)

	res, err := transcript.ParseFrom(bytes.NewReader(complete), t.state)
	if err != nil {
		return nil, err
	}
	t.state = res.State
	return res.Rows, nil
}

// Watch follows the transcript until ctx is cancelled, calling onRows for each batch.
// It also watches the containing directory, because a new session writes a new file and
// the daemon must notice without a restart.
func (t *Tailer) Watch(ctx context.Context, onRows func([]transcript.Row, transcript.State)) error {
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}
	defer w.Close()

	// The project directory may not exist until Claude writes its first record; making
	// it is harmless (the CLI would) and lets the watch start now.
	_ = os.MkdirAll(filepath.Dir(t.path), 0o700)
	if err := w.Add(filepath.Dir(t.path)); err != nil {
		return err
	}

	// Debounce: Claude writes several records in a burst, and one frame per record would
	// be needless chatter on a phone radio.
	const debounce = 120 * time.Millisecond
	var timer *time.Timer
	fire := make(chan struct{}, 1)
	schedule := func() {
		if timer == nil {
			timer = time.AfterFunc(debounce, func() {
				select {
				case fire <- struct{}{}:
				default:
				}
			})
			return
		}
		timer.Reset(debounce)
	}

	flush := func() {
		rows, err := t.readNew()
		if err == nil && len(rows) > 0 {
			onRows(rows, t.state)
		}
	}

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case ev, ok := <-w.Events:
			if !ok {
				return nil
			}
			if ev.Name == t.path && (ev.Op&(fsnotify.Write|fsnotify.Create) != 0) {
				schedule()
			} else if ev.Op&fsnotify.Create != 0 && strings.HasSuffix(ev.Name, ".jsonl") &&
				filepath.Dir(ev.Name) == filepath.Dir(t.path) {
				if t.ShouldSwitch != nil && !t.ShouldSwitch(ev.Name) {
					continue // another session's transcript, not this one's next chapter
				}
				// This session restarted here: follow it.
				t.switchTo(ev.Name)
				if t.OnSwitch != nil {
					t.OnSwitch(ev.Name)
				}
				schedule()
			}
		case <-fire:
			flush()
		case err, ok := <-w.Errors:
			if !ok {
				return nil
			}
			return err
		}
	}
}

// MarshalRows is a small helper for the frame pump.
func MarshalRows(rows []transcript.Row) ([]byte, error) { return json.Marshal(rows) }
