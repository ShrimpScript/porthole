package server

import (
	"regexp"
	"strconv"
	"strings"
)

// ScreenQuestion is the CLI's question picker as it stands on the tmux screen right now.
// The transcript records an AskUserQuestion call only once it is answered (measured on
// 2.1.270), so while the person is being asked, the screen is the only source - and the
// honest one: it shows which question of several is up, and which boxes are ticked.
type ScreenQuestion struct {
	Header  string         `json:"header,omitempty"` // the tab name, "Colour"
	Text    string         `json:"text"`             // "Which colour?"
	Multi   bool           `json:"multi,omitempty"`  // options carry checkboxes; a digit toggles, Right moves on
	Options []ScreenOption `json:"options"`          // the numbered choices, without the picker's own entries
	Typed   int            `json:"typed,omitempty"`  // the number of "Type something", 0 when absent
	Review  bool           `json:"review,omitempty"` // the "Ready to submit your answers?" screen
	Index   int            `json:"index,omitempty"`  // 1-based position among the call's questions, from the tabs
	Total   int            `json:"total,omitempty"`
}

type ScreenOption struct {
	N           int    `json:"n"`
	Label       string `json:"label"`
	Description string `json:"description,omitempty"`
	Checked     bool   `json:"checked,omitempty"`
}

var (
	pickerFooter = regexp.MustCompile(`Enter to select\s*·.*Esc to cancel`)
	pickerOption = regexp.MustCompile(`^\s*(?:❯\s*)?(\d+)\.\s+(?:\[([ ✔x])\]\s*)?(.*?)\s*$`)
	pickerRule   = regexp.MustCompile(`^\s*─{5,}\s*$`)
)

// parseQuestion reads the picker out of a captured pane. Nil when no picker is showing.
func parseQuestion(screen string) *ScreenQuestion {
	lines := strings.Split(screen, "\n")
	// A picker ends with its key legend; the review screen draws none, so it is
	// recognised by its own heading and question instead.
	foot := -1
	for i := len(lines) - 1; i >= 0; i-- {
		if pickerFooter.MatchString(lines[i]) {
			foot = i
			break
		}
	}
	start := -1
	for i := len(lines) - 1; i >= 0; i-- {
		if strings.Contains(lines[i], "Ready to submit your answers?") {
			for j := i; j >= 0 && i-j < 40; j-- {
				if strings.Contains(lines[j], "Review your answers") {
					start = j
					break
				}
			}
			if start >= 0 && (foot < 0 || foot < start) {
				foot = len(lines)
			}
			break
		}
	}
	if start < 0 {
		if foot < 0 {
			return nil
		}
		// The picker block: from the tab line down to the legend.
		for i := foot - 1; i >= 0 && foot-i < 60; i-- {
			if strings.Contains(lines[i], "☐") || strings.Contains(lines[i], "☒") {
				start = i
				break
			}
		}
	}
	if start < 0 {
		return nil
	}
	q := &ScreenQuestion{}
	tabLine := lines[start]
	if strings.Contains(tabLine, "Review your answers") {
		q.Review = true
		q.Text = "Ready to submit your answers?"
	} else {
		names, done := tabNames(tabLine)
		q.Total = len(names)
		if q.Total > 0 {
			idx := done
			if idx >= q.Total {
				idx = q.Total - 1 // a ticked multi-select still shows ☒ while it is up
			}
			q.Index = idx + 1
			q.Header = names[idx]
		}
		// The question is the first non-empty line after the tabs that is not an option.
		for i := start + 1; i < foot; i++ {
			t := strings.TrimSpace(lines[i])
			if t == "" {
				continue
			}
			if pickerOption.MatchString(lines[i]) {
				break
			}
			q.Text = t
			break
		}
	}
	// Options: numbered lines; the line after one, if plain, is its description.
	for i := start + 1; i < foot; i++ {
		m := pickerOption.FindStringSubmatch(lines[i])
		if m == nil {
			continue
		}
		n, _ := strconv.Atoi(m[1])
		label := strings.TrimRight(m[3], ".")
		switch {
		case strings.EqualFold(label, "Type something"):
			q.Typed = n
			continue
		case strings.EqualFold(label, "Chat about this"):
			continue
		}
		opt := ScreenOption{N: n, Label: label, Checked: m[2] == "✔" || m[2] == "x"}
		if m[2] != "" {
			q.Multi = true
		}
		if i+1 < foot {
			next := lines[i+1]
			nt := strings.TrimSpace(next)
			if nt != "" && !pickerOption.MatchString(next) && !pickerRule.MatchString(next) &&
				!strings.EqualFold(nt, "Submit") && nt != label {
				opt.Description = nt
			}
		}
		q.Options = append(q.Options, opt)
	}
	if q.Text == "" && !q.Review {
		return nil
	}
	return q
}

// tabNames reads the picker's tab strip "←  ☒ Colour  ☐ Size  ✔ Submit  →": the question
// names in order, and how many are ticked as answered.
func tabNames(line string) (names []string, done int) {
	rs := []rune(line)
	for i := 0; i < len(rs); i++ {
		if rs[i] != '☐' && rs[i] != '☒' {
			continue
		}
		if rs[i] == '☒' {
			done++
		}
		j := i + 1
		for j < len(rs) && !strings.ContainsRune("☐☒✔←→", rs[j]) {
			j++
		}
		if name := strings.TrimSpace(string(rs[i+1 : j])); name != "" {
			names = append(names, name)
		}
		i = j - 1
	}
	return names, done
}
