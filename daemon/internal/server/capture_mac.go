package server

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// On a Mac the desktop is captured with the system's own screencapture, which needs no
// install. It needs permission instead: macOS asks once, at the Mac, for "Screen &
// System Audio Recording", and until it is given a capture fails or shows only the
// wallpaper.
const macPermissionHint = " - on the Mac, allow portholed in System Settings > Privacy & Security > Screen & System Audio Recording"

// macStill takes a JPEG of the main display. A Retina screen is 5K wide as a PNG of
// several megabytes; the phone shows it on a screen a fraction of that, so it is scaled
// down on the Mac with sips before it is sent.
func macStill(ctx context.Context) ([]byte, error) {
	path := filepath.Join(captureDir(), fmt.Sprintf("still-%d.jpg", time.Now().UnixMilli()))
	defer os.Remove(path)
	if out, err := exec.CommandContext(ctx, "screencapture", "-x", "-m", "-t", "jpg", path).CombinedOutput(); err != nil {
		return nil, errors.New("screencapture: " + firstLine(out, err) + macPermissionHint)
	}
	_ = exec.CommandContext(ctx, "sips", "-Z", "2048", path).Run()
	b, err := os.ReadFile(path)
	if err != nil || len(b) == 0 {
		return nil, errors.New("screencapture wrote no image" + macPermissionHint)
	}
	return b, nil
}

// macClip records the screen for a few seconds and returns an MP4 the phone can play.
// screencapture writes a QuickTime movie at the display's full resolution, too large to
// send as it is, so it is shrunk with ffmpeg when that is installed, or else with the
// system's avconvert.
func macClip(ctx context.Context, seconds int) ([]byte, error) {
	stamp := time.Now().UnixMilli()
	mov := filepath.Join(captureDir(), fmt.Sprintf("clip-%d.mov", stamp))
	mp4 := filepath.Join(captureDir(), fmt.Sprintf("clip-%d.mp4", stamp))
	defer os.Remove(mov)
	defer os.Remove(mp4)

	rctx, cancel := context.WithTimeout(ctx, time.Duration(seconds+20)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(rctx, "screencapture", "-x", "-v", "-V", strconv.Itoa(seconds), mov)
	cmd.Stdin = nil // never let it wait on a question
	if out, err := cmd.CombinedOutput(); err != nil {
		return nil, errors.New("screencapture: " + firstLine(out, err) + macPermissionHint)
	}
	if fi, err := os.Stat(mov); err != nil || fi.Size() == 0 {
		return nil, errors.New("the recording produced no file" + macPermissionHint)
	}

	tctx, cancel2 := context.WithTimeout(ctx, 60*time.Second)
	defer cancel2()
	for _, conv := range macConverters(mov, mp4) {
		if _, err := exec.LookPath(conv[0]); err != nil {
			continue
		}
		if out, err := exec.CommandContext(tctx, conv[0], conv[1:]...).CombinedOutput(); err == nil {
			if b, err := os.ReadFile(mp4); err == nil && len(b) > 0 {
				return b, nil
			}
		} else {
			_ = out // the next converter gets its turn; the raw movie is the last resort
		}
		_ = os.Remove(mp4)
	}
	// Nothing to convert with. A short QuickTime movie of H.264 plays on Android as it
	// is; the caller refuses it if it is too large to send.
	return os.ReadFile(mov)
}

// macConverters are the commands that turn the movie into a small MP4, best first.
func macConverters(mov, mp4 string) [][]string {
	return [][]string{
		{"ffmpeg", "-y", "-loglevel", "error", "-i", mov,
			"-vf", "scale='min(1280,iw)':-2", "-r", "20",
			"-c:v", "libx264", "-crf", "30", "-preset", "veryfast", "-pix_fmt", "yuv420p",
			"-an", "-movflags", "+faststart", mp4},
		{"avconvert", "--source", mov, "--output", mp4, "--preset", "PresetAppleM4V720pHD", "--replace"},
	}
}

func firstLine(out []byte, err error) string {
	s := strings.TrimSpace(string(out))
	if s == "" && err != nil {
		s = err.Error()
	}
	s, _, _ = strings.Cut(s, "\n")
	return s
}
