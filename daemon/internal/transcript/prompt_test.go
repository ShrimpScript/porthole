package transcript

import (
	"reflect"
	"testing"
)

func TestAPromptIsWhatThePersonWrote(t *testing.T) {
	cases := []struct {
		in, want string
		files    []string
	}{
		// As 2.1.289 recorded the message sent from the phone with three pictures.
		{"[Image #1] [Image #2] [Image #3]\n\n<pasted_content id=\"94cd\">\nPorthole has some outdated features.\n\nAttached image (read it with the Read tool):\n\nAttached image (read it with the Read tool):\n</pasted_content id=\"94cd\">\n\nAnd the second message.",
			"Porthole has some outdated features.\n\nAnd the second message.", nil},
		// A file the CLI left as a path, and one picture it did not attach.
		{"Here is the crash report.\n\nAttached file, saved at: /srv/u/.config/porthole/uploads/2026-10-03/175738-crash.log\n\nAttached image (read it with the Read tool): /srv/u/.config/porthole/uploads/2026-10-03/175741-shot.png",
			"Here is the crash report.", []string{"/srv/u/.config/porthole/uploads/2026-10-03/175738-crash.log", "/srv/u/.config/porthole/uploads/2026-10-03/175741-shot.png"}},
		// Files with no words: the daemon's filler is not a message.
		{"Look at the attached file.\n\nAttached file, saved at: /srv/u/x.pdf", "", []string{"/srv/u/x.pdf"}},
		// Typed by the person, with no files: it is the message.
		{"Look at the attached image.", "Look at the attached image.", nil},
		// A token the CLI left where the path was is not a file.
		{"See this\n\nAttached image (read it with the Read tool): [Image #1]", "See this", nil},
		// Ordinary text is untouched.
		{"Rename the helper and run the tests.", "Rename the helper and run the tests.", nil},
	}
	for _, c := range cases {
		got, files := cleanPrompt(c.in)
		if got != c.want || !reflect.DeepEqual(files, c.files) {
			t.Errorf("cleanPrompt(%q) = %q %q, want %q %q", c.in, got, files, c.want, c.files)
		}
	}
}

func TestAUserRowCarriesItsFiles(t *testing.T) {
	res := parse(t, `{"type":"user","message":{"content":"Here is the log.\n\nAttached file, saved at: /srv/u/run.log"}}`)
	if len(res.Rows) != 1 || res.Rows[0].Text != "Here is the log." || len(res.Rows[0].Files) != 1 {
		t.Fatalf("rows: %+v", res.Rows)
	}
}
