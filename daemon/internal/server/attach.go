package server

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/shrimpscript/porthole/daemon/internal/proto"
	"github.com/shrimpscript/porthole/daemon/internal/transcript"
)

// Files the phone attaches to a prompt: photos, and since 0.32.0 any file - a PDF, a log,
// a CSV. Each is saved under the daemon's uploads folder, never in the session's working
// tree, and named in the prompt by its path, so Claude can open it.

const (
	// maxAttachBytes bounds one attached file; the phone keeps a whole message's to this.
	maxAttachBytes = 10 << 20
	// maxFrameBytes is the most one message from the phone may carry: a prompt with
	// maxAttachBytes of attachments, base64-encoded, and room to spare. The socket
	// library's own default is 32 KB, which no photo fits in.
	maxFrameBytes = 16 << 20
)

// attachFiles saves attachments that came inside the prompt itself - how apps before
// 0.32.0 send photos - and returns the prompt that names them, or "" when one could not
// be saved (the phone has been told why). Newer apps send files in pieces first
// (uploads below), which a slow link and the keepalive both survive.
func (s *Server) attachFiles(ctx context.Context, w *writer, text string, atts []promptImage, device string) string {
	dir := filepath.Join(uploadsDir(), time.Now().Format("2006-01-02"))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		_ = w.send(ctx, proto.NewError("upload_failed", err.Error()))
		return ""
	}
	var files []savedFile
	stamp := time.Now().Format("150405")
	for i, a := range atts {
		b, err := base64.StdEncoding.DecodeString(a.Data)
		if err != nil || len(b) == 0 || len(b) > maxAttachBytes {
			_ = w.send(ctx, proto.NewError("upload_failed", "an attachment was empty or larger than 10 MB"))
			return ""
		}
		name := attachName(a.Name, a.Media, i)
		f, path, err := createUnique(dir, stamp+"-"+name)
		if err == nil {
			_, err = f.Write(b)
			if cerr := f.Close(); err == nil {
				err = cerr
			}
		}
		if err != nil {
			_ = w.send(ctx, proto.NewError("upload_failed", "could not save "+name+" on the computer"))
			return ""
		}
		files = append(files, savedFile{path, transcript.ImageMedia(name) != ""})
	}
	s.log.Info("files attached", "from", device, "count", len(files))
	return attachedPrompt(text, files)
}

type savedFile struct {
	path  string
	image bool
}

// attachedPrompt is the prompt with its attachments named after it: pictures for the Read
// tool, other files by where they are, for Claude to open as suits them.
func attachedPrompt(text string, files []savedFile) string {
	var sb strings.Builder
	sb.WriteString(strings.TrimSpace(text))
	if sb.Len() == 0 {
		switch {
		case len(files) == 1 && files[0].image:
			sb.WriteString("Look at the attached image.")
		case len(files) == 1:
			sb.WriteString("Look at the attached file.")
		default:
			sb.WriteString("Look at the attached files.")
		}
	}
	for _, f := range files {
		if f.image {
			sb.WriteString("\n\nAttached image (read it with the Read tool): " + f.path)
		} else {
			sb.WriteString("\n\nAttached file, saved at: " + f.path)
		}
	}
	return sb.String()
}

// attachName is the name an attachment is saved under: the phone's own name for it, made
// safe for a path (no folders, no spaces, letters and digits of the Latin alphabet), its
// extension kept through that, and one from its media type when it has none - Claude
// reads a file partly by what it is called.
func attachName(name, media string, i int) string {
	base := filepath.Base(strings.ReplaceAll(strings.TrimSpace(name), "\\", "/"))
	ext := filepath.Ext(base)
	stem := safeName(strings.TrimSuffix(base, ext))
	ext = strings.ToLower(safeName(strings.TrimPrefix(ext, ".")))
	if ext == "" || len(ext) > 10 {
		ext = strings.TrimPrefix(extFor(media), ".")
	}
	if stem == "" {
		stem = fmt.Sprintf("attachment-%d", i+1)
	}
	if len(stem) > maxStemBytes { // a file system allows 255 bytes; the stamp and index take some
		stem = strings.TrimRight(stem[:maxStemBytes], ".-_")
	}
	if ext == "" {
		return stem
	}
	return stem + "." + ext
}

// maxStemBytes bounds a saved name before its extension.
const maxStemBytes = 100

// safeName keeps letters, digits and ._- of a name, with spaces as dashes.
func safeName(n string) string {
	var b strings.Builder
	for _, r := range n {
		switch {
		case r == ' ':
			b.WriteRune('-')
		case r == '.' || r == '-' || r == '_' || (r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z'):
			b.WriteRune(r)
		}
	}
	return strings.Trim(b.String(), ".-")
}

// createUnique makes a new file at dir/name, or at name-2, name-3... when that exists:
// two messages in one second may carry the same name, and neither may overwrite the other.
func createUnique(dir, name string) (*os.File, string, error) {
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 1; i < 1000; i++ {
		n := name
		if i > 1 {
			n = fmt.Sprintf("%s-%d%s", stem, i, ext)
		}
		p := filepath.Join(dir, n)
		f, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err == nil {
			return f, p, nil
		}
		if !os.IsExist(err) {
			return nil, "", err
		}
	}
	return nil, "", fmt.Errorf("no free name for %s", name)
}

// extFor is the usual extension for a media type the phone sends, or none.
func extFor(media string) string {
	switch media {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	case "application/pdf":
		return ".pdf"
	case "text/plain":
		return ".txt"
	case "text/markdown":
		return ".md"
	case "text/csv":
		return ".csv"
	case "application/json":
		return ".json"
	}
	// No lookup in the system's own table: it differs between Linux and a Mac, and a
	// file's name should not depend on which computer it went to.
	return ""
}

// ---- uploads in pieces ----------------------------------------------------------------

// A file from the phone arrives as upload.chunk frames, each a few hundred KB, then a
// prompt.send names the finished uploads. Sent as one message, a 10 MB file takes long
// enough on a slow link that neither end's ping gets its answer in time, and the
// connection is dropped with the file half sent.

const (
	uploadsKeepFor = 14 * 24 * time.Hour // then the tidy-up takes them
	partsKeepFor   = time.Hour           // a piece-by-piece upload that never finished
)

type upload struct {
	f     *os.File
	part  string // where it is written as it arrives
	name  string // what it is saved as when complete
	image bool
	next  int // the piece expected next
}

// Limits for one connection. A paired phone can already type into a terminal, so these
// are against a phone gone wrong, not an attacker: the disk should not fill from it.
const (
	maxOpenUploads   = 4
	maxDoneUploads   = 32
	maxConnUploadSum = 50 << 20
)

// uploads are one connection's files in flight and finished, by the phone's id for them.
// Only the read loop touches them, so they need no lock.
type uploads struct {
	dir     func() string
	pending map[string]*upload
	done    map[string]savedFile
	failed  map[string]bool // said once; later pieces of these are dropped quietly
	total   int64           // bytes received on this connection
}

func newUploads() *uploads {
	return &uploads{dir: uploadsDir, pending: map[string]*upload{}, done: map[string]savedFile{}, failed: map[string]bool{}}
}

// chunk takes one piece. The first carries the file's name and type; the last completes
// it. On an error the upload is abandoned, its part removed, and its later pieces dropped.
func (u *uploads) chunk(id string, seq int, data, name, media string, last bool) error {
	if u.failed[id] {
		return nil
	}
	err := u.piece(id, seq, data, name, media, last)
	if err != nil {
		u.drop(id)
		u.failed[id] = true
	}
	return err
}

func (u *uploads) piece(id string, seq int, data, name, media string, last bool) error {
	if id == "" || len(id) > 64 {
		return fmt.Errorf("an upload without a proper id")
	}
	b, err := base64.StdEncoding.DecodeString(data)
	if err != nil {
		return fmt.Errorf("a piece of an attachment arrived damaged")
	}
	up := u.pending[id]
	if seq == 0 {
		if up != nil || u.done[id].path != "" {
			return fmt.Errorf("an attachment was sent twice")
		}
		if len(u.pending) >= maxOpenUploads || len(u.done) >= maxDoneUploads {
			return fmt.Errorf("too many attachments at once; send them in more than one message")
		}
		dir := filepath.Join(u.dir(), time.Now().Format("2006-01-02"))
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return fmt.Errorf("could not make the uploads folder on the computer")
		}
		clean := attachName(name, media, len(u.done))
		// A random name while it arrives: a half-arrived file never carries the real one.
		var rnd [8]byte
		_, _ = rand.Read(rnd[:])
		part := filepath.Join(dir, ".part-"+hex.EncodeToString(rnd[:]))
		f, err := os.OpenFile(part, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err != nil {
			return fmt.Errorf("could not save %s on the computer", clean)
		}
		up = &upload{f: f, part: part, name: time.Now().Format("150405") + "-" + clean, image: transcript.ImageMedia(clean) != ""}
		u.pending[id] = up
	}
	if up == nil || seq != up.next {
		return fmt.Errorf("the pieces of an attachment arrived out of order")
	}
	up.next++
	u.total += int64(len(b))
	if st, _ := up.f.Stat(); st != nil && st.Size()+int64(len(b)) > maxAttachBytes {
		return fmt.Errorf("an attachment was larger than 10 MB")
	}
	if u.total > maxConnUploadSum {
		return fmt.Errorf("too much sent at once; send the rest in another message")
	}
	if _, err := up.f.Write(b); err != nil {
		return fmt.Errorf("could not save an attachment on the computer")
	}
	if !last {
		return nil
	}
	delete(u.pending, id)
	st, _ := up.f.Stat()
	if err := up.f.Close(); err != nil || st == nil || st.Size() == 0 {
		_ = os.Remove(up.part)
		return fmt.Errorf("an attachment was empty or could not be saved")
	}
	f, final, err := createUnique(filepath.Dir(up.part), up.name)
	if err != nil {
		_ = os.Remove(up.part)
		return fmt.Errorf("could not save %s on the computer", up.name)
	}
	f.Close()
	if err := os.Rename(up.part, final); err != nil {
		_ = os.Remove(up.part)
		_ = os.Remove(final)
		return fmt.Errorf("could not save %s on the computer", up.name)
	}
	u.done[id] = savedFile{path: final, image: up.image}
	return nil
}

// take hands over finished uploads for a prompt, in the order the phone named them. If
// one is missing, none is taken and the others are let go: the phone sends them all again.
func (u *uploads) take(ids []string) ([]savedFile, error) {
	var files []savedFile
	for _, id := range ids {
		f, ok := u.done[id]
		if !ok {
			u.discard(ids)
			return nil, fmt.Errorf("an attachment did not arrive whole; send it again")
		}
		files = append(files, f)
	}
	for _, id := range ids {
		delete(u.done, id)
	}
	return files, nil
}

// discard lets go of uploads a prompt will not use: finished files are removed.
func (u *uploads) discard(ids []string) {
	for _, id := range ids {
		u.drop(id)
		if f, ok := u.done[id]; ok {
			_ = os.Remove(f.path)
			delete(u.done, id)
		}
	}
}

func (u *uploads) drop(id string) {
	if up := u.pending[id]; up != nil {
		up.f.Close()
		_ = os.Remove(up.part)
		delete(u.pending, id)
	}
}

// close ends the connection's uploads: half-sent files, and finished ones no prompt
// named, are removed.
func (u *uploads) close() {
	for id := range u.pending {
		u.drop(id)
	}
	for id, f := range u.done {
		_ = os.Remove(f.path)
		delete(u.done, id)
	}
}

// TidyUploads removes what the phone sent more than two weeks ago, and pieces of uploads
// that never finished, now and then once a day until ctx ends.
func TidyUploads(ctx context.Context) {
	for {
		tidyUploads(uploadsDir(), time.Now())
		select {
		case <-ctx.Done():
			return
		case <-time.After(24 * time.Hour):
		}
	}
}

func tidyUploads(root string, now time.Time) {
	days, err := os.ReadDir(root)
	if err != nil {
		return
	}
	for _, d := range days {
		// Only the day folders the daemon makes, and judged by their day: whatever
		// Claude unpacked inside them keeps its archive's old times.
		day, err := time.ParseInLocation("2006-01-02", d.Name(), time.Local)
		if !d.IsDir() || err != nil {
			continue
		}
		dir := filepath.Join(root, d.Name())
		if now.Sub(day) > uploadsKeepFor+24*time.Hour {
			_ = os.RemoveAll(dir)
			continue
		}
		files, _ := os.ReadDir(dir)
		for _, f := range files {
			info, err := f.Info()
			if err == nil && strings.HasPrefix(f.Name(), ".part-") && now.Sub(info.ModTime()) > partsKeepFor {
				_ = os.Remove(filepath.Join(dir, f.Name()))
			}
		}
	}
}
