package server

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

// On a Mac, one answer per guarded folder that exists; elsewhere nothing is asked. The
// home is a stand-in, so the folders are ordinary ones and the answers are known.
func TestFolderAccess(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	if err := os.Mkdir(filepath.Join(home, "Documents"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, "Documents", "notes.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(home, "Downloads"), 0o755); err != nil {
		t.Fatal(err)
	}
	got := folderAccess(3 * time.Second)
	if runtime.GOOS != "darwin" {
		if got != nil {
			t.Fatalf("asked about folders on %s: %+v", runtime.GOOS, got)
		}
		return
	}
	// Desktop is not there, so it is not asked about; an empty Downloads still reads.
	if len(got) != 2 || got[0].Name != "Documents" || !got[0].OK || got[1].Name != "Downloads" || !got[1].OK {
		t.Fatalf("folderAccess = %+v", got)
	}
	for _, a := range got {
		t.Logf("%s ok=%v %s", a.Name, a.OK, a.Error)
	}
}
