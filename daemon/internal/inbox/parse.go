package inbox

import (
	"crypto/sha1"
	"encoding/hex"
	"regexp"
	"strings"
	"time"
	"unicode"
)

// found is one question as the parser read it, before it is tied to a project.
type found struct {
	line    int
	text    string
	ask     string // text less its choices and its [suggestion], when that leaves something
	key     string // what names it: the item's own words (not the points under it), its question only
	options []string
	def     string
	date    time.Time
}

// matcher holds the phrases for one person.
type matcher struct {
	name string
	// section: a heading that starts with one of these opens a list of questions.
	section *regexp.Regexp
	// mention: a heading or note that says, somewhere, that it waits on the person.
	mention *regexp.Regexp
	// call: "Sam's call" - a decision left to them, unless it is said in passing, in brackets.
	call *regexp.Regexp
}

const waitWords = `(?:waiting\s+on|waiting\s+for|awaiting|open\s+for|decisions?\s+for|questions?\s+for)`

func newMatcher(name string) matcher {
	who := `you`
	if n := strings.TrimSpace(name); n != "" {
		who = `(?:you|` + regexp.QuoteMeta(n) + `)`
	}
	m := matcher{
		name:    strings.TrimSpace(name),
		section: regexp.MustCompile(`(?i)^` + waitWords + `\s+` + who + `\b`),
	}
	if n := strings.TrimSpace(name); n != "" {
		q := regexp.QuoteMeta(n)
		// Prose that says "you" is too often about something else: only the name counts there.
		m.mention = regexp.MustCompile(`(?i)\b` + waitWords + `\s+` + q + `\b`)
		m.call = regexp.MustCompile(`(?i)\b` + q + `['’]s\s+call\b`)
	}
	return m
}

var (
	headingRe  = regexp.MustCompile(`^(#{1,6})\s+(.*?)(?:\s+#+)?\s*$`)
	listRe     = regexp.MustCompile(`^(\s*)(?:[-*+]|\d+[.)])\s+(.*)$`)
	dateRe     = regexp.MustCompile(`\b(20\d\d-[01]\d-[0-3]\d)\b`)
	leadNumRe  = regexp.MustCompile(`^\d+[.)]\s*`)
	leadDateRe = regexp.MustCompile(`^20\d\d-[01]\d-[0-3]\d(?:\s+[0-2]?\d:[0-5]\d)?\s*(?:[-–—:,]\s*)?`)
	checkboxRe = regexp.MustCompile(`^\[[ xX]\]\s*`)
	bracketRe  = regexp.MustCompile(`\s*\[([^\[\]]+)\]\s*\.?\s*$`)
	spacesRe   = regexp.MustCompile(`\s+`)
)

// block is a list item (with what is written under it) or a paragraph.
type block struct {
	line   int
	lines  []string
	list   bool
	indent int
	own    int // how many of lines are the item's own words, before the points under it
}

type heading struct {
	level int
	date  time.Time
}

// parse reads one note and returns the open questions in it, in the order they appear.
func (m matcher) parse(text string) []found {
	var out []found
	var cur *block
	var heads []heading
	section := 0 // the level of the "Waiting on you" heading we are under; 0: none
	fence := ""

	headDate := func() time.Time {
		for i := len(heads) - 1; i >= 0; i-- {
			if !heads[i].date.IsZero() {
				return heads[i].date
			}
		}
		return time.Time{}
	}
	flush := func() {
		if cur == nil {
			return
		}
		b := cur
		cur = nil
		raw := strings.TrimSpace(strings.Join(b.lines, " "))
		if raw == "" || closed(raw) || placeholder(raw) {
			return
		}
		own := raw
		if b.own > 0 {
			own = strings.TrimSpace(strings.Join(b.lines[:b.own], " "))
		}
		var t string
		switch {
		case section > 0:
			if !b.list {
				return // a line of explanation under the heading, not a question
			}
			t = raw
		default:
			t = m.inline(raw)
			if t == "" {
				return
			}
		}
		if f, ok := makeFound(b.line, t, raw, headDate()); ok {
			if t == raw {
				f.key = questionKey(own)
			}
			out = append(out, f)
		}
	}

	for i, ln := range strings.Split(text, "\n") {
		n := i + 1
		trimmed := strings.TrimSpace(ln)
		if fence != "" {
			if strings.HasPrefix(trimmed, fence) {
				fence = ""
			}
			continue
		}
		if strings.HasPrefix(trimmed, "```") || strings.HasPrefix(trimmed, "~~~") {
			flush()
			fence = trimmed[:3]
			continue
		}
		if h := headingRe.FindStringSubmatch(ln); h != nil && !strings.HasPrefix(ln, " ") {
			flush()
			level := len(h[1])
			for len(heads) > 0 && heads[len(heads)-1].level >= level {
				heads = heads[:len(heads)-1]
			}
			heads = append(heads, heading{level: level, date: pastDate(h[2])})
			if section > 0 && level <= section {
				section = 0
			}
			title := cleanHeading(h[2])
			switch {
			case m.section.MatchString(title):
				section = level
			case section == 0 && !closed(title) && m.mentioned(title):
				if f, ok := makeFound(n, dropAside(title, m), title, headDate()); ok {
					out = append(out, f)
				}
			}
			continue
		}
		if trimmed == "" {
			flush()
			continue
		}
		if strings.HasPrefix(trimmed, "|") || strings.HasPrefix(trimmed, "<!--") {
			flush() // tables and comments are not questions
			continue
		}
		if l := listRe.FindStringSubmatch(ln); l != nil {
			indent := len(strings.ReplaceAll(l[1], "\t", "    "))
			if cur != nil && cur.list && indent > cur.indent+1 {
				if cur.own == 0 {
					cur.own = len(cur.lines)
				}
				cur.lines = append(cur.lines, l[2]) // a point under the item belongs to it
				continue
			}
			flush()
			cur = &block{line: n, lines: []string{l[2]}, list: true, indent: indent}
			continue
		}
		if cur == nil {
			cur = &block{line: n}
		}
		cur.lines = append(cur.lines, trimmed)
	}
	flush()
	return out
}

// mentioned: does this text say it waits on the person?
func (m matcher) mentioned(s string) bool {
	if m.mention == nil {
		return false
	}
	return m.mentionAt(s) != nil || m.callOpen(s) >= 0
}

// mentionAt finds where the text says it waits on the person, passing over a mention in
// quotes, which names the place questions are kept ("see \"Waiting on Sam\"") rather than
// asking one.
func (m matcher) mentionAt(s string) []int {
	for _, loc := range m.mention.FindAllStringIndex(s, -1) {
		if strings.Count(s[:loc[0]], `"`)%2 == 0 {
			return loc
		}
	}
	return nil
}

// callOpen finds "Sam's call" said as an open decision, not "(Sam's call)" told in passing.
func (m matcher) callOpen(s string) int {
	for _, loc := range m.call.FindAllStringIndex(s, -1) {
		if strings.Count(s[:loc[0]], "(") <= strings.Count(s[:loc[0]], ")") {
			return loc[0]
		}
	}
	return -1
}

// inline returns the question a note outside a "Waiting on you" section asks, or "".
func (m matcher) inline(raw string) string {
	if m.mention == nil {
		return ""
	}
	if loc := m.mentionAt(raw); loc != nil {
		// "Open for Sam: approve the copy" - the question is what follows the colon.
		rest := strings.TrimLeft(raw[loc[1]:], "*_ ")
		if strings.HasPrefix(rest, ":") {
			if q := strings.TrimSpace(strings.TrimLeft(rest[1:], "*_ ")); len(q) > 3 {
				return q
			}
		}
		return sentenceAt(raw, loc[0])
	}
	if m.callOpen(raw) >= 0 {
		return raw // "Remedy = Sam's call." needs the whole note to make sense
	}
	return ""
}

// sentenceAt returns the sentence of s that holds byte i, or all of s when that
// sentence is too short to stand alone.
func sentenceAt(s string, i int) string {
	start, end := 0, len(s)
	for j := 0; j+2 < len(s); j++ {
		if (s[j] == '.' || s[j] == '?' || s[j] == '!') && s[j+1] == ' ' && isUpper(s[j+2]) {
			if j < i {
				start = j + 2
			} else {
				end = j + 1
				break
			}
		}
	}
	if sent := strings.TrimSpace(s[start:end]); len(sent) >= 30 {
		return sent
	}
	return s
}

func isUpper(c byte) bool { return c >= 'A' && c <= 'Z' }

// closed: struck through, ticked off or marked answered.
func closed(s string) bool {
	s = strings.TrimSpace(strings.TrimLeft(strings.TrimSpace(s), "*_"))
	low := strings.ToLower(s)
	return (strings.HasPrefix(s, "~~") && strings.Contains(s[2:], "~~")) || strings.HasPrefix(s, "[x]") || strings.HasPrefix(s, "[X]") ||
		strings.Contains(s, "ANSWERED") || strings.HasPrefix(low, "answered") || strings.Contains(low, "(answered")
}

var placeholderRe = regexp.MustCompile(`(?i)^[(\[]?\s*(none|nothing|n/a|na|tbd|-|—|empty)\b[^a-z]*(right now|yet|for now|at the moment|currently)?[.)\]]*$`)

// placeholder: "None", "(nothing yet)" - what an empty "Waiting on you" section says.
func placeholder(s string) bool { return placeholderRe.MatchString(strings.Trim(stripMarks(s), "*_ ")) }

// pastDate is the first date in s that is not in the future ("decide by 2026-12-01" is a
// deadline, not when it was asked).
func pastDate(s string) time.Time {
	now := time.Now()
	for _, dm := range dateRe.FindAllString(s, -1) {
		if d, err := time.ParseInLocation("2006-01-02", dm, time.Local); err == nil && !d.After(now) {
			return d
		}
	}
	return time.Time{}
}

// questionKey reduces an item to its question and choices: no marks, no [suggestion], no
// case or spacing. Rewording a suggestion, adding a point under the item or moving it to
// another note keeps its name, so its recorded answer still hides it; the same words with
// other choices are another question.
func questionKey(s string) string {
	t := stripMarks(checkboxRe.ReplaceAllString(strings.TrimSpace(s), ""))
	t = strings.Trim(t, "* ")
	if b := bracketRe.FindStringIndex(t); b != nil {
		t = t[:b[0]]
	}
	return normalize(t)
}

func cleanHeading(s string) string {
	s = strings.TrimLeftFunc(stripMarks(s), func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) })
	s = leadNumRe.ReplaceAllString(s, "")
	s = leadDateRe.ReplaceAllString(s, "")
	return strings.TrimSpace(s)
}

// dropAside removes "(waiting on Sam)" from a heading that names its subject first.
func dropAside(s string, m matcher) string {
	for {
		open := strings.LastIndex(s, "(")
		if open < 0 {
			return s
		}
		close := strings.Index(s[open:], ")")
		if close < 0 {
			return s
		}
		inner := s[open : open+close+1]
		rest := strings.TrimSpace(s[:open] + s[open+close+1:])
		if !m.mentioned(inner) || rest == "" {
			return s
		}
		s = rest
	}
}

func stripMarks(s string) string {
	s = strings.ReplaceAll(s, "**", "")
	s = strings.ReplaceAll(s, "__", "")
	s = strings.ReplaceAll(s, "`", "")
	return spacesRe.ReplaceAllString(strings.TrimSpace(s), " ")
}

// makeFound tidies a question and reads its choices and its suggestion; its date comes
// from the whole note it was found in (whole), else from the heading it is under.
func makeFound(line int, text, whole string, headDate time.Time) (found, bool) {
	t := stripMarks(checkboxRe.ReplaceAllString(strings.TrimSpace(text), ""))
	t = strings.Trim(t, "* ")
	if len(t) < 4 {
		return found{}, false
	}
	f := found{line: line, text: t, date: headDate, key: questionKey(t)}
	if d := pastDate(whole); !d.IsZero() {
		f.date = d
	}
	body := t
	if b := bracketRe.FindStringSubmatchIndex(body); b != nil {
		f.def = strings.TrimSpace(body[b[2]:b[3]])
		body = body[:b[0]]
	}
	var ask string
	f.options, ask = choices(body)
	if ask = strings.TrimSpace(ask); ask != "" && ask != t {
		f.ask = ask
	}
	if f.def != "" && len(f.options) > 0 {
		// "[B]" names the suggested choice; anything longer is advice, kept as written.
		for _, o := range f.options {
			if strings.EqualFold(o, f.def) {
				f.def = o
			}
		}
	}
	return f, true
}

// choices reads "Which domain: .day | .gg" as two options, and returns what is asked
// before them ("Which domain"). Anything that is not cleanly a short list of choices is
// answered in words instead, and the whole of s is what is asked.
func choices(s string) ([]string, string) {
	bar := strings.Index(s, "|")
	if bar < 0 {
		return nil, s
	}
	list, ask := s, ""
	if colon := strings.LastIndex(s[:bar], ": "); colon >= 0 {
		list, ask = s[colon+2:], s[:colon]
	} else if q := strings.LastIndex(s[:bar], "? "); q >= 0 {
		list, ask = s[q+2:], s[:q+1]
	}
	parts := strings.Split(list, "|")
	if len(parts) < 2 || len(parts) > 6 {
		return nil, s
	}
	out := make([]string, 0, len(parts))
	for i, p := range parts {
		p = strings.TrimSpace(p)
		if i == len(parts)-1 {
			p = strings.TrimRight(p, ".?")
		}
		if p == "" || len(p) > 80 {
			return nil, s
		}
		out = append(out, p)
	}
	return out, ask
}

func normalize(s string) string {
	return strings.ToLower(spacesRe.ReplaceAllString(strings.TrimSpace(s), " "))
}

// itemID names a question within its project by its key, so it survives lines moving
// around it, and changes only when the question itself is rewritten.
func itemID(key string) string {
	h := sha1.Sum([]byte(key))
	return hex.EncodeToString(h[:])[:10]
}
