// Package inbox finds the questions waiting on the person across their project folders,
// in the notes agents leave behind (progress files, handovers, backlogs, roadmaps), and
// records the person's answers beside them.
//
// The convention is a "## Waiting on you" section, one item per line, with choices written
// as "A | B | C". Older notes say it in their own words ("Waiting on Sam", "Open for Sam:",
// "Sam's call"); those are read too, with the person's name from the inbox settings.
//
// The only thing ever written into a project is an answer, appended to its decisions file
// after the person gives it. Nothing else in a project is changed.
package inbox

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Config is the inbox's settings, kept in inbox.json beside the daemon's other state.
type Config struct {
	// Name is what the person is called in older notes ("Waiting on Sam"). Notes that say
	// "you" are read without it.
	Name string `json:"name,omitempty"`
	// Folders are read as projects, and so is every folder directly inside them.
	Folders []string `json:"folders"`
	// Skip are folders found inside those that are not to be read.
	Skip []string `json:"skip,omitempty"`
}

// Item is one open question in one project.
type Item struct {
	ID   string `json:"id"`
	File string `json:"file"` // relative to the project
	Line int    `json:"line"`
	Text string `json:"text"`
	// Question is Text less its choices and its [suggestion], for showing above them; empty
	// when that is all of Text.
	Question string   `json:"question,omitempty"`
	Options  []string `json:"options,omitempty"`
	Default  string   `json:"default,omitempty"` // the notes' own suggestion, in [brackets]
	// Since is when the question was written: a date in the item or its heading, else the
	// line's commit, else the file's last change.
	Since time.Time `json:"since"`
}

// Project is one folder's open questions, newest first.
type Project struct {
	Name  string `json:"name"`
	Dir   string `json:"dir"`
	Items []Item `json:"items"`
}

// Notes are the files read in each project, relative to it.
var Notes = []string{
	".claude/progress.md",
	".claude/HANDOVER.md",
	".agent/HANDOVER.md",
	".agent/BACKLOG.md",
	"docs/HANDOVER.md",
	"HANDOVER.md",
	"BACKLOG.md",
	"ROADMAP.md",
	"docs/ROADMAP.md",
}

const maxNoteSize = 4 << 20

// ConfigPath is where the settings live inside the daemon's state folder.
func ConfigPath(stateDir string) string { return filepath.Join(stateDir, "inbox.json") }

func dismissedPath(stateDir string) string { return filepath.Join(stateDir, "inbox-dismissed.json") }

// LoadConfig reads the settings; a missing file is an empty inbox, not an error.
func LoadConfig(stateDir string) (Config, error) {
	var c Config
	b, err := os.ReadFile(ConfigPath(stateDir))
	if errors.Is(err, os.ErrNotExist) {
		return c, nil
	}
	if err != nil {
		return c, err
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return c, errors.New("inbox.json is not valid JSON: " + err.Error())
	}
	return c, nil
}

// SaveConfig writes the settings, replacing the file whole.
func SaveConfig(stateDir string, c Config) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return writeFile(ConfigPath(stateDir), append(b, '\n'))
}

// stateMu keeps the CLI's and the daemon's writers in this process from interleaving; the
// unique temporary file keeps two processes from sharing one.
var stateMu sync.Mutex

func writeFile(path string, b []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		os.Remove(f.Name())
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(f.Name())
		return err
	}
	if err := os.Chmod(f.Name(), 0o600); err != nil {
		os.Remove(f.Name())
		return err
	}
	return os.Rename(f.Name(), path)
}

// Expand turns "~/x" into a path under the home folder and makes it absolute.
func Expand(p string) string {
	if p == "~" || strings.HasPrefix(p, "~/") {
		home, _ := os.UserHomeDir()
		p = filepath.Join(home, strings.TrimPrefix(p, "~"))
	}
	if abs, err := filepath.Abs(p); err == nil {
		p = abs
	}
	return filepath.Clean(p)
}

// DismissKey names an item put aside: its id within its project.
func DismissKey(dir, id string) string { return realPath(dir) + "|" + id }

// Dismissed is the set of items the person put aside without answering, by [DismissKey].
// It is kept on this computer, never in the project.
func Dismissed(stateDir string) map[string]bool {
	set := map[string]bool{}
	b, err := os.ReadFile(dismissedPath(stateDir))
	if err != nil {
		return set
	}
	var ids []string
	if json.Unmarshal(b, &ids) == nil {
		for _, id := range ids {
			set[id] = true
		}
	}
	return set
}

// Dismiss puts an item aside.
func Dismiss(stateDir, dir, id string) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	set := Dismissed(stateDir)
	set[DismissKey(dir, id)] = true
	ids := make([]string, 0, len(set))
	for id := range set {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	b, _ := json.Marshal(ids)
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return err
	}
	return writeFile(dismissedPath(stateDir), b)
}

// Projects lists the project folders the settings name: each folder that has notes, and
// each folder directly inside one that does (but not its docs/, which holds its own notes).
func (c Config) Projects() []string {
	seen := map[string]bool{}
	for _, s := range c.Skip {
		seen[realPath(Expand(s))] = true
	}
	var out []string
	add := func(dir string) {
		key := realPath(dir)
		if seen[key] || !hasNotes(dir) {
			return
		}
		seen[key] = true
		out = append(out, dir)
	}
	for _, f := range c.Folders {
		dir := Expand(f)
		add(dir)
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if strings.HasPrefix(e.Name(), ".") || e.Name() == "docs" {
				continue // a project's own notes folder, not a project of its own
			}
			p := filepath.Join(dir, e.Name())
			if st, err := os.Stat(p); err == nil && st.IsDir() {
				add(p)
			}
		}
	}
	return out
}

func realPath(p string) string {
	if r, err := filepath.EvalSymlinks(p); err == nil {
		return r
	}
	return p
}

func hasNotes(dir string) bool {
	for _, n := range Notes {
		if regular(filepath.Join(dir, n)) {
			return true
		}
	}
	return false
}

// regular: a plain file, not a link to one somewhere else.
func regular(p string) bool {
	st, err := os.Lstat(p)
	return err == nil && st.Mode().IsRegular()
}

// Scan reads every project and returns those with open questions, the project with the
// newest question first. Answered and dismissed items are left out.
func Scan(c Config, dismissed map[string]bool) []Project {
	var out []Project
	for _, dir := range c.Projects() {
		p := ScanProject(dir, c.Name)
		kept := p.Items[:0]
		for _, it := range p.Items {
			if !dismissed[DismissKey(dir, it.ID)] {
				kept = append(kept, it)
			}
		}
		p.Items = kept
		if len(p.Items) > 0 {
			out = append(out, p)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Items[0].Since.After(out[j].Items[0].Since) })
	return out
}

// ScanProject reads one project's notes. name is how older notes call the person ("" reads
// only notes that say "you").
func ScanProject(dir, name string) Project {
	p := Project{Name: filepath.Base(dir), Dir: dir}
	answered := answeredIDs(dir)
	m := newMatcher(name)
	seen := map[string]bool{}
	for _, rel := range Notes {
		n, ok := readNote(dir, rel, m)
		if !ok {
			continue
		}
		for _, f := range n.found {
			if seen[f.key] {
				continue // the same question copied into a second note
			}
			seen[f.key] = true
			it := Item{
				ID:       itemID(f.key),
				File:     rel,
				Line:     f.line,
				Text:     f.text,
				Question: f.ask,
				Options:  f.options,
				Default:  f.def,
				Since:    f.date,
			}
			if answered[it.ID] {
				continue
			}
			if it.Since.IsZero() {
				it.Since = n.blame(f.line)
			}
			if it.Since.IsZero() {
				it.Since = n.mod
			}
			p.Items = append(p.Items, it)
		}
	}
	sort.SliceStable(p.Items, func(i, j int) bool { return p.Items[i].Since.After(p.Items[j].Since) })
	return p
}

// Find looks an item up by its project and id. The project must be one the settings name.
func Find(c Config, dir, id string) (Project, Item, bool) {
	want := realPath(dir)
	for _, d := range c.Projects() {
		if realPath(d) != want {
			continue
		}
		p := ScanProject(d, c.Name)
		for _, it := range p.Items {
			if it.ID == id {
				return p, it, true
			}
		}
	}
	return Project{}, Item{}, false
}

// note is one note as last read: the questions in it, and when each line was written.
type note struct {
	dir, rel string
	mod      time.Time
	size     int64
	found    []found
	mu       sync.Mutex
	blames   map[int]time.Time // nil until a question in it has no date of its own
	blamedAt time.Time         // a commit does not touch the file, so blame is asked again after a while
}

var (
	notesMu sync.Mutex
	notes   = map[string]*note{}
)

// readNote reads a note, or returns the last read while the file is unchanged: every
// phone that connects asks for the whole inbox.
func readNote(dir, rel string, m matcher) (*note, bool) {
	path := filepath.Join(dir, rel)
	st, err := os.Lstat(path)
	if err != nil || !st.Mode().IsRegular() || st.Size() > maxNoteSize {
		return nil, false
	}
	key := path + "\x00" + m.name
	notesMu.Lock()
	n := notes[key]
	notesMu.Unlock()
	if n != nil && n.mod.Equal(st.ModTime()) && n.size == st.Size() {
		return n, true
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, false
	}
	n = &note{dir: dir, rel: rel, mod: st.ModTime(), size: st.Size(), found: m.parse(string(b))}
	notesMu.Lock()
	notes[key] = n
	notesMu.Unlock()
	return n, true
}

// blame is when a line was committed, asked of git once per version of the note.
func (n *note) blame(line int) time.Time {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.blames == nil || time.Since(n.blamedAt) > time.Minute {
		n.blames, n.blamedAt = blameTimes(n.dir, n.rel), time.Now()
	}
	return n.blames[line]
}
