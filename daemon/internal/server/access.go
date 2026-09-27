package server

import (
	"io"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

// FolderAccess is whether the daemon itself may read one of the folders a Mac guards.
type FolderAccess struct {
	Name  string `json:"name"`
	Path  string `json:"path"`
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

// guardedFolders are the home folders macOS asks about before a program reads them.
var guardedFolders = []string{"Desktop", "Documents", "Downloads"}

// folderAccess reads each guarded folder as the daemon, on a Mac; elsewhere there is
// nothing to ask. To macOS, a Claude Code the daemon starts belongs to the daemon - when
// the daemon started its tmux server, as it does after a reboot - so it is portholed that
// needs the folder, and the question comes up on the Mac's screen. Reading them here,
// while someone is at the Mac, puts that question to them then rather than while they
// are away, when Claude Code would only see "Operation not permitted". The read waits at
// most wait for an answer; an unanswered question is reported as such.
func folderAccess(wait time.Duration) []FolderAccess {
	if runtime.GOOS != "darwin" {
		return nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	var out []FolderAccess
	for _, name := range guardedFolders {
		p := filepath.Join(home, name)
		if _, err := os.Lstat(p); err != nil {
			continue // not there: nothing to ask
		}
		fa := FolderAccess{Name: name, Path: p}
		done := make(chan error, 1)
		go func() {
			f, err := os.Open(p)
			if err == nil {
				_, err = f.Readdirnames(1)
				if err == io.EOF {
					err = nil // empty, and readable
				}
				f.Close()
			}
			done <- err
		}()
		select {
		case err := <-done:
			fa.OK = err == nil
			if err != nil {
				fa.Error = err.Error()
			}
		case <-time.After(wait):
			fa.Error = "not answered yet: macOS is asking at the Mac"
		}
		out = append(out, fa)
	}
	return out
}
