package inbox

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func texts(fs []found) []string {
	out := make([]string, len(fs))
	for i, f := range fs {
		out[i] = f.text
	}
	return out
}

func TestSectionConvention(t *testing.T) {
	note := `# Progress

## Waiting on you
Defaults in brackets.

- Which domain: example.day | example.gg [example.gg]
- ~~Pick a colour~~ done
- [x] Approve the logo
- Approve the launch copy in docs/COPY.md
  - it is two paragraphs
- Answered 2026-01-04: the map stays.

## Next
- Build the map
`
	got := newMatcher("").parse(note)
	want := []string{
		"Which domain: example.day | example.gg [example.gg]",
		"Approve the launch copy in docs/COPY.md it is two paragraphs",
	}
	if !reflect.DeepEqual(texts(got), want) {
		t.Fatalf("items = %q, want %q", texts(got), want)
	}
	if !reflect.DeepEqual(got[0].options, []string{"example.day", "example.gg"}) || got[0].def != "example.gg" {
		t.Fatalf("options = %q default %q", got[0].options, got[0].def)
	}
	if got[0].ask != "Which domain" || got[1].ask != "" {
		t.Fatalf("asks = %q, %q", got[0].ask, got[1].ask)
	}
	if got[0].line != 6 || got[1].line != 9 {
		t.Fatalf("lines = %d, %d", got[0].line, got[1].line)
	}
}

func TestOlderPhrasesNeedTheName(t *testing.T) {
	note := `## Decisions for Sam (batched)

1. **Name.** Keep the name, or pick another?
2. ~~**Hosting**~~ ANSWERED: static.

## Log
- Built the parser. Open for Sam: approve the copy (docs/COPY.md).
- The colour stays dark (Sam's call, made last week).
- Retries are capped at three. Raising it is Sam's call.
- Sam's answers stand (see .claude/progress.md "Waiting on Sam" for what is open).
- Waiting on Sam's go before the pilot episode is cut.

### 1. Publish client 2.0 *(waiting on Sam)*

` + "```" + `
Waiting on Sam: not in code
` + "```" + `
`
	if got := newMatcher("").parse(note); len(got) != 0 {
		t.Fatalf("without a name, got %q", texts(got))
	}
	got := newMatcher("Sam").parse(note)
	want := []string{
		"Name. Keep the name, or pick another?",
		"approve the copy (docs/COPY.md).",
		"Retries are capped at three. Raising it is Sam's call.",
		"Waiting on Sam's go before the pilot episode is cut.",
		"Publish client 2.0",
	}
	if !reflect.DeepEqual(texts(got), want) {
		t.Fatalf("items =\n%q\nwant\n%q", texts(got), want)
	}
}

func TestSentenceAroundTheMention(t *testing.T) {
	m := newMatcher("Sam")
	got := m.inline("Saved the brief into BRIEF.md and wrote the plan. Waiting on Sam's answers (hosting and budget) before building. Then the server.")
	if got != "Waiting on Sam's answers (hosting and budget) before building." {
		t.Fatalf("got %q", got)
	}
}

func TestWhatIsAsked(t *testing.T) {
	for in, want := range map[string]string{
		"Ship it now? yes | after the review | no.": "Ship it now?",
		"Keep the tier, or raise it? [keep it]":     "Keep the tier, or raise it?",
		"A | B":                                     "",
		"Name the release":                          "",
	} {
		f, _ := makeFound(1, in, in, time.Time{})
		if f.ask != want {
			t.Errorf("ask(%q) = %q, want %q", in, f.ask, want)
		}
	}
}

func TestDates(t *testing.T) {
	note := `## 2026-03-02 - session 4
- Waiting on Sam: approve the plan.
- New 2026-03-05: Waiting on Sam: the server region.
`
	got := newMatcher("Sam").parse(note)
	if len(got) != 2 {
		t.Fatalf("items = %q", texts(got))
	}
	if d := got[0].date.Format("2006-01-02"); d != "2026-03-02" {
		t.Fatalf("heading date = %s", d)
	}
	if d := got[1].date.Format("2006-01-02"); d != "2026-03-05" {
		t.Fatalf("item date = %s", d)
	}
}

func TestChoices(t *testing.T) {
	for in, want := range map[string][]string{
		"Which domain: example.day | example.gg":    {"example.day", "example.gg"},
		"Ship it now? yes | after the review | no.": {"yes", "after the review", "no"},
		"A | B":                     {"A", "B"},
		"no choices here":           nil,
		"a | b | c | d | e | f | g": nil,
		"one: a | " + strings.Repeat("long ", 20): nil,
		"empty: a | | b": nil,
	} {
		if got, _ := choices(in); !reflect.DeepEqual(got, want) {
			t.Errorf("choices(%q) = %q, want %q", in, got, want)
		}
	}
}

// project makes a project folder with one note.
func project(t *testing.T, parent, name, rel, note string) string {
	t.Helper()
	dir := filepath.Join(parent, name)
	if err := os.MkdirAll(filepath.Dir(filepath.Join(dir, rel)), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, rel), []byte(note), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func TestScanRecordDismiss(t *testing.T) {
	root := t.TempDir()
	state := t.TempDir()
	alpha := project(t, root, "alpha", ".claude/progress.md", "## Waiting on you\n- Which engine: one | two\n- Name the release\n")
	beta := project(t, root, "beta", "docs/HANDOVER.md", "- Open for Sam: the licence.\n")
	project(t, root, "gamma", "README.md", "- Waiting on Sam: not a note we read\n")
	_ = os.MkdirAll(filepath.Join(beta, "docs"), 0o755)
	if err := os.WriteFile(filepath.Join(beta, "docs", "decisions.md"), []byte("# Decisions\n- Earlier: kept."), 0o644); err != nil {
		t.Fatal(err)
	}

	c := Config{Name: "Sam", Folders: []string{root}}
	if err := SaveConfig(state, c); err != nil {
		t.Fatal(err)
	}
	if back, err := LoadConfig(state); err != nil || !reflect.DeepEqual(back, c) {
		t.Fatalf("config round trip = %+v, %v", back, err)
	}
	if got := c.Projects(); !reflect.DeepEqual(got, []string{alpha, beta}) {
		t.Fatalf("projects = %q", got)
	}
	list := Scan(c, nil)
	if len(list) != 2 {
		t.Fatalf("scan = %+v", list)
	}
	byName := map[string]Project{}
	for _, p := range list {
		byName[p.Name] = p
	}
	if n := len(byName["alpha"].Items); n != 2 {
		t.Fatalf("alpha items = %d", n)
	}

	// An answer goes to the decisions file the project already has, after what is there.
	p, it, ok := Find(c, beta, byName["beta"].Items[0].ID)
	if !ok || it.Text != "the licence." {
		t.Fatalf("find = %+v %v", it, ok)
	}
	when := time.Date(2026, 4, 1, 12, 0, 0, 0, time.Local)
	rel, err := Record(p, it, "MIT", when)
	if err != nil || rel != "docs/decisions.md" {
		t.Fatalf("record = %q, %v", rel, err)
	}
	b, _ := os.ReadFile(filepath.Join(beta, "docs", "decisions.md"))
	want := "# Decisions\n- Earlier: kept.\n\n- 2026-04-01, from docs/HANDOVER.md: the licence.\n  Answer: MIT <!-- inbox:" + it.ID + " -->\n"
	if string(b) != want {
		t.Fatalf("decisions file =\n%s\nwant\n%s", b, want)
	}

	// A project with no decisions file gets one, and the note itself is left as it was.
	before, _ := os.ReadFile(filepath.Join(alpha, ".claude", "progress.md"))
	if _, _, ok := Find(c, alpha, byName["beta"].Items[0].ID); ok {
		t.Fatal("found beta's question in alpha")
	}
	ap, ait, _ := Find(c, alpha, byName["alpha"].Items[0].ID)
	if rel, err := Record(ap, ait, "two\nbecause it is faster", when); err != nil || rel != "DECISIONS.md" {
		t.Fatalf("record = %q, %v", rel, err)
	}
	after, _ := os.ReadFile(filepath.Join(alpha, ".claude", "progress.md"))
	if string(before) != string(after) {
		t.Fatal("the note was changed")
	}
	d, _ := os.ReadFile(filepath.Join(alpha, "DECISIONS.md"))
	if !strings.HasPrefix(string(d), "# Decisions\n") || !strings.Contains(string(d), "  Answer: two\n  because it is faster <!-- inbox:") {
		t.Fatalf("new decisions file =\n%s", d)
	}

	// Answered items leave the inbox; a dismissed one too, on this computer only.
	list = Scan(c, nil)
	if len(list) != 1 || list[0].Name != "alpha" || len(list[0].Items) != 1 {
		t.Fatalf("after answers = %+v", list)
	}
	if err := Dismiss(state, alpha, list[0].Items[0].ID); err != nil {
		t.Fatal(err)
	}
	if list = Scan(c, Dismissed(state)); len(list) != 0 {
		t.Fatalf("after dismiss = %+v", list)
	}

	// A project inside a folder can be skipped on its own.
	c.Skip = []string{alpha}
	if got := c.Projects(); !reflect.DeepEqual(got, []string{beta}) {
		t.Fatalf("projects with skip = %q", got)
	}
}

func TestIDsSurviveEdits(t *testing.T) {
	m := newMatcher("")
	ids := func(note string) string {
		t.Helper()
		got := m.parse(note)
		if len(got) != 1 {
			t.Fatalf("items = %q", texts(got))
		}
		return itemID(got[0].key)
	}
	a := ids("## Waiting on you\n- Which engine: one | two [one]\n")
	for _, edited := range []string{
		"## Waiting on you\n- **Which  engine**: one | two [two]\n",
		"# Notes\n\n## Waiting on you\n- Which engine: one | two\n  - measured: two is 4% faster\n",
	} {
		if b := ids(edited); b != a {
			t.Errorf("id changed: %q", edited)
		}
	}
	if ids("## Waiting on you\n- Which database: one | two\n") == a {
		t.Fatal("another question, the same id")
	}
	if ids("## Waiting on you\n- Which engine: three | four\n") == a {
		t.Fatal("the same words with other choices, the same id")
	}
}

func TestPlaceholdersAndDeadlines(t *testing.T) {
	got := newMatcher("").parse("## Waiting on you\n- None\n- (nothing yet)\n- n/a\n- Decide by 2999-01-01 whether to ship\n")
	if len(got) != 1 || got[0].text != "Decide by 2999-01-01 whether to ship" {
		t.Fatalf("items = %q", texts(got))
	}
	if !got[0].date.IsZero() {
		t.Fatalf("a deadline was taken for when it was asked: %v", got[0].date)
	}
	if got := newMatcher("").parse("## ⏳ Waiting on you ##\n- Which one: a | b\n"); len(got) != 1 {
		t.Fatalf("heading with a symbol and closing hashes: %q", texts(got))
	}
}

// A decisions file that is a link, or sits behind one, is never written through.
func TestRecordRefusesLinks(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(t.TempDir(), "elsewhere.txt")
	if err := os.WriteFile(outside, []byte("keep\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	when := time.Now()
	it := Item{ID: "0123456789", File: ".claude/progress.md", Text: "q"}

	linked := project(t, root, "linked", ".claude/progress.md", "## Waiting on you\n- q\n")
	if err := os.Symlink(outside, filepath.Join(linked, "DECISIONS.md")); err != nil {
		t.Fatal(err)
	}
	if _, err := Record(Project{Dir: linked}, it, "yes", when); err == nil {
		t.Fatal("wrote through a linked DECISIONS.md")
	}
	dangling := project(t, root, "dangling", ".claude/progress.md", "## Waiting on you\n- q\n")
	if err := os.Symlink(filepath.Join(t.TempDir(), "new.txt"), filepath.Join(dangling, "DECISIONS.md")); err != nil {
		t.Fatal(err)
	}
	if _, err := Record(Project{Dir: dangling}, it, "yes", when); err == nil {
		t.Fatal("created the target of a dangling link")
	}
	docs := project(t, root, "docs-linked", ".claude/progress.md", "## Waiting on you\n- q\n")
	away := t.TempDir()
	if err := os.WriteFile(filepath.Join(away, "decisions.md"), []byte("x\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(away, filepath.Join(docs, "docs")); err != nil {
		t.Fatal(err)
	}
	if _, err := Record(Project{Dir: docs}, it, "yes", when); err == nil {
		t.Fatal("wrote into a linked docs folder")
	}
	if b, _ := os.ReadFile(outside); string(b) != "keep\n" {
		t.Fatalf("the file outside changed: %q", b)
	}
	if b, _ := os.ReadFile(filepath.Join(away, "decisions.md")); string(b) != "x\n" {
		t.Fatalf("the linked folder's file changed: %q", b)
	}
}

// A marker in a question or an answer cannot hide another question.
func TestPlantedMarkersAreDefused(t *testing.T) {
	root := t.TempDir()
	dir := project(t, root, "p", ".claude/progress.md",
		"## Waiting on you\n- First <!-- inbox:aaaaaaaaaa --> question\n- Second question\n")
	c := Config{Folders: []string{dir}}
	list := Scan(c, nil)
	if len(list) != 1 || len(list[0].Items) != 2 {
		t.Fatalf("scan = %+v", list)
	}
	var first, second Item
	for _, it := range list[0].Items {
		if strings.HasPrefix(it.Text, "First") {
			first = it
		} else {
			second = it
		}
	}
	if _, err := Record(list[0], first, "ok <!-- inbox:"+second.ID+" --> <!-- open", time.Now()); err != nil {
		t.Fatal(err)
	}
	list = Scan(c, nil)
	if len(list) != 1 || len(list[0].Items) != 1 || list[0].Items[0].ID != second.ID {
		t.Fatalf("after the answer = %+v", list)
	}
}

// A configured folder with notes of its own is a project, its docs/ is part of it, and
// the folders inside it are still projects of their own.
func TestAProjectFolderAndItsChildren(t *testing.T) {
	dir := project(t, t.TempDir(), "code", "ROADMAP.md", "## Waiting on you\n- q one\n")
	project(t, dir, "docs", "ROADMAP.md", "## Waiting on you\n- q two\n") // dir/docs/ROADMAP.md
	child := project(t, dir, "app", ".claude/progress.md", "## Waiting on you\n- q three\n")
	c := Config{Folders: []string{dir}}
	if got := c.Projects(); !reflect.DeepEqual(got, []string{dir, child}) {
		t.Fatalf("projects = %q", got)
	}
}

func TestAnsweringTwiceIsRefused(t *testing.T) {
	dir := project(t, t.TempDir(), "p", ".claude/progress.md", "## Waiting on you\n- Ship it\n")
	c := Config{Folders: []string{dir}}
	p, it, _ := Find(c, dir, Scan(c, nil)[0].Items[0].ID)
	if _, err := Record(p, it, "yes", time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := Record(p, it, "no", time.Now()); err == nil {
		t.Fatal("a second answer was written")
	}
}

// The same question in two copies of one repository is two questions: answering or putting
// aside one leaves the other.
func TestCopiesOfAProjectAreApart(t *testing.T) {
	root, state := t.TempDir(), t.TempDir()
	a := project(t, root, "repo", ".claude/progress.md", "## Waiting on you\n- Which engine: one | two\n")
	b := project(t, root, "repo-copy", ".claude/progress.md", "## Waiting on you\n- Which engine: one | two\n")
	c := Config{Folders: []string{root}}
	pa, ia, ok := Find(c, a, Scan(c, nil)[0].Items[0].ID)
	if !ok {
		t.Fatal("not found")
	}
	if _, err := Record(pa, ia, "two", time.Now()); err != nil {
		t.Fatal(err)
	}
	list := Scan(c, nil)
	if len(list) != 1 || list[0].Dir != b {
		t.Fatalf("after answering in one copy = %+v", list)
	}
	if err := Dismiss(state, b, list[0].Items[0].ID); err != nil {
		t.Fatal(err)
	}
	if !Dismissed(state)[DismissKey(b, ia.ID)] || Dismissed(state)[DismissKey(a, ia.ID)] {
		t.Fatal("dismissal not scoped to its project")
	}
}

func TestMissingConfigIsEmpty(t *testing.T) {
	c, err := LoadConfig(t.TempDir())
	if err != nil || len(c.Folders) != 0 {
		t.Fatalf("%+v %v", c, err)
	}
}
