package server

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"testing"
	"time"
)

// The Mac capture path against a real Mac. It runs only in CI, on a macOS runner: on a
// person's own Mac it would take a picture of their screen.
func TestMacCapture(t *testing.T) {
	if runtime.GOOS != "darwin" || os.Getenv("CI") != "true" {
		t.Skip("macOS CI only")
	}
	t.Setenv("PORTHOLE_STATE_DIR", t.TempDir())
	for _, tool := range []string{"ffmpeg", "avconvert", "sips"} {
		_, err := exec.LookPath(tool)
		t.Logf("%s on PATH: %v", tool, err == nil)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	b, err := macStill(ctx)
	if err != nil {
		t.Fatalf("still: %v", err)
	}
	if !bytes.HasPrefix(b, []byte{0xFF, 0xD8, 0xFF}) {
		t.Fatalf("still is not a JPEG: % x", b[:min(8, len(b))])
	}
	t.Logf("still: %d bytes", len(b))

	b, err = macClip(ctx, 3)
	if err != nil {
		t.Fatalf("clip: %v", err)
	}
	// An MP4 or QuickTime file starts with a box whose type is ftyp.
	if len(b) < 12 || string(b[4:8]) != "ftyp" {
		t.Fatalf("clip is not an ISO media file: % x", b[:min(12, len(b))])
	}
	t.Logf("clip: %d bytes, brand %q, made by %s", len(b), b[8:12], macClipVia)
	// Every Mac has avconvert, so a clip sent as the raw movie means its arguments broke.
	if strings.HasPrefix(macClipVia, "the movie") {
		out, _ := exec.Command("avconvert", "--help").CombinedOutput()
		t.Fatalf("the clip was not converted: %s\navconvert --help:\n%s", macClipVia, out)
	}
}
