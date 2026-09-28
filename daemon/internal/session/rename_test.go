package session

import (
	"os"
	"path/filepath"
	"testing"
)

// A name given with /rename is the session's title, even when the CLI writes its own
// titles after it, and even when the rename is far back: the CLI's file beside the
// transcript has the latest.
func TestRenamedTitleWins(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "abc.jsonl")
	lines := `{"type":"user","cwd":"/p","sessionId":"abc","message":{"role":"user","content":"hello"}}
{"type":"ai-title","aiTitle":"Fix the login bug","sessionId":"abc"}
{"type":"custom-title","customTitle":"login work","sessionId":"abc"}
{"type":"agent-name","agentName":"login work","sessionId":"abc"}
{"type":"ai-title","aiTitle":"Fix the login bug and tests","sessionId":"abc"}
`
	if err := os.WriteFile(path, []byte(lines), 0o600); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(path)
	if m, _ := peek(path, st.Size()); m.Title != "login work" {
		t.Fatalf("a later ai-title overrode the rename: %q", m.Title)
	}
	// Renamed again since; the transcript's record fell out of what is read, the file did not.
	if err := os.MkdirAll(filepath.Join(dir, "abc"), 0o700); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(dir, "abc", "custom-title.json"), []byte(`{"customTitle":"auth rewrite"}`), 0o600)
	if m, _ := peek(path, st.Size()); m.Title != "auth rewrite" {
		t.Fatalf("the file beside the transcript was not read: %q", m.Title)
	}
}
