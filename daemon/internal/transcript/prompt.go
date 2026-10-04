package transcript

import (
	"path/filepath"
	"regexp"
	"strings"
)

// A prompt as the CLI records it carries more than the person wrote (Claude Code
// 2.1.289, measured 2026-10-03): each image path it attached becomes "[Image #N]" (the
// image itself follows as its own block, an image row here), a long paste is wrapped in
// <pasted_content id="..."> tags, and a message sent from the phone ends with the
// daemon's "Attached image (read it with the Read tool): <path>" and "Attached file, saved
// at: <path>" lines. On a phone, the bubble should hold what the person wrote, with the
// files beside it.

var (
	pastedTag    = regexp.MustCompile(`</?pasted_content(?:\s+id="[^"]*")?>`)
	imageToken   = regexp.MustCompile(`\[Image #\d+\][ \t]*`)
	attachedLine = regexp.MustCompile(`(?m)^[ \t]*Attached (?:image \(read it with the Read tool\)|file, saved at):[ \t]*(.*)$`)
	blankRun     = regexp.MustCompile(`\n{3,}`)
	// What the daemon writes when the phone sent files without words.
	fillerPrompt = map[string]bool{
		"Look at the attached image.": true, "Look at the attached file.": true, "Look at the attached files.": true,
	}
)

// cleanPrompt is a prompt's text for its bubble, and the paths of the files it named.
func cleanPrompt(s string) (string, []string) {
	var files []string
	attached := false
	s = attachedLine.ReplaceAllStringFunc(s, func(line string) string {
		attached = true
		if m := attachedLine.FindStringSubmatch(line); m != nil {
			// Only a real path: a token the CLI left in its place is not a file.
			if p := strings.TrimSpace(m[1]); filepath.IsAbs(p) {
				files = append(files, p)
			}
		}
		return ""
	})
	s = imageToken.ReplaceAllString(s, "")
	s = pastedTag.ReplaceAllString(s, "")
	s = strings.TrimSpace(blankRun.ReplaceAllString(s, "\n\n"))
	// The daemon's filler, only when it came with files: typed alone, it is the message.
	if attached && fillerPrompt[s] {
		s = ""
	}
	return s, files
}
