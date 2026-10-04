package server

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"
)

// Sleeping screens draw no frames, and a Wayland screenshot waits for one: grim hung until
// the daemon's 10-second limit whenever nobody was at the desk (2026-10-03), which is
// exactly when a phone asks for a screenshot. So a capture wakes the screens that are
// asleep, takes its picture, and puts them back.
//
// Hyprland and sway can say which outputs are asleep and switch them. On a Mac,
// caffeinate -u wakes the display, which goes back to sleep on its own timer.

// screenCmd runs a compositor command; a variable so tests never touch a real desktop.
var screenCmd = func(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = captureEnv()
	return cmd.Output()
}

// screenSettle is how long a woken screen gets to draw its first frame.
var screenSettle = 900 * time.Millisecond

// screenOS is the system the waking is for; a variable so tests on a Mac take the
// compositor path with its commands faked.
var screenOS = runtime.GOOS

// wakeLinger keeps woken screens on a little after the last capture, so a capture right
// after another (or one from a second phone) does not find them going dark.
var wakeLinger = 3 * time.Second

// waker shares one waking among captures: two phones, or a still during a clip, wake the
// screens once, and they go back to sleep a moment after the last capture ends - not in
// the middle of another one.
type waker struct {
	mu      sync.Mutex
	n       int
	restore func()
	woke    bool
	timer   *time.Timer
}

func (w *waker) acquire(ctx context.Context) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.timer != nil {
		w.timer.Stop()
		w.timer = nil
	}
	if w.n == 0 && w.restore == nil {
		w.restore, w.woke = wakeScreens(ctx)
	}
	w.n++
	return w.woke
}

func (w *waker) release() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.n > 0 {
		w.n--
	}
	if w.n > 0 || w.restore == nil {
		return
	}
	r := w.restore
	w.timer = time.AfterFunc(wakeLinger, func() {
		w.mu.Lock()
		defer w.mu.Unlock()
		if w.n == 0 && w.restore != nil {
			r()
			w.restore, w.woke, w.timer = nil, false, nil
		}
	})
}

// wakeScreens switches on the outputs that are asleep and returns what puts them back,
// and whether any were woken (the phone's caption says so).
func wakeScreens(ctx context.Context) (restore func(), woke bool) {
	restore = func() {}
	switch {
	case screenOS == "darwin":
		// Declares user activity: the display wakes, and a moment is given for it to light.
		// It then stays on for the display's own sleep timeout; macOS offers no way to put
		// back just the display it woke.
		if c := exec.Command("caffeinate", "-u", "-t", "2"); c.Start() == nil {
			go func() { _ = c.Wait() }()
			time.Sleep(600 * time.Millisecond)
		}
		return restore, false
	case os.Getenv("HYPRLAND_INSTANCE_SIGNATURE") != "" || (os.Getenv("SWAYSOCK") == "" && hyprlandRunning()):
		asleep := hyprlandAsleep(ctx)
		if len(asleep) == 0 {
			return restore, false
		}
		cursor := hyprlandCursor(ctx)
		for _, m := range asleep {
			_, _ = screenCmd(ctx, "hyprctl", "dispatch", "dpms", "on", m)
		}
		time.Sleep(screenSettle)
		return func() {
			bg := context.Background()
			// The mouse moved: someone sat down meanwhile. Their screens stay on.
			if now := hyprlandCursor(bg); cursor != "" && now != "" && now != cursor {
				return
			}
			for _, m := range asleep {
				_, _ = screenCmd(bg, "hyprctl", "dispatch", "dpms", "off", m)
			}
		}, true
	case os.Getenv("SWAYSOCK") != "":
		asleep := swayAsleep(ctx)
		if len(asleep) == 0 {
			return restore, false
		}
		for _, o := range asleep {
			_, _ = screenCmd(ctx, "swaymsg", "output", o, "power", "on")
		}
		time.Sleep(screenSettle)
		return func() {
			bg := context.Background()
			for _, o := range asleep {
				_, _ = screenCmd(bg, "swaymsg", "output", o, "power", "off")
			}
		}, true
	}
	return restore, false
}

// hyprlandCursor is the pointer's position, as text, or "" when it cannot be read.
func hyprlandCursor(ctx context.Context) string {
	out, err := screenCmd(ctx, "hyprctl", "-j", "cursorpos")
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func hyprlandRunning() bool {
	_, err := exec.LookPath("hyprctl")
	return err == nil && os.Getenv("XDG_RUNTIME_DIR") != ""
}

// hyprlandAsleep names the monitors whose DPMS is off.
func hyprlandAsleep(ctx context.Context) []string {
	out, err := screenCmd(ctx, "hyprctl", "-j", "monitors")
	if err != nil {
		return nil
	}
	var mons []struct {
		Name string `json:"name"`
		DPMS *bool  `json:"dpmsStatus"`
	}
	if json.Unmarshal(out, &mons) != nil {
		return nil
	}
	var asleep []string
	for _, m := range mons {
		if m.DPMS != nil && !*m.DPMS && m.Name != "" {
			asleep = append(asleep, m.Name)
		}
	}
	return asleep
}

// swayAsleep names the active outputs that are powered off (sway calls it power, or dpms
// before 1.9).
func swayAsleep(ctx context.Context) []string {
	out, err := screenCmd(ctx, "swaymsg", "-t", "get_outputs", "-r")
	if err != nil {
		return nil
	}
	var outs []struct {
		Name   string `json:"name"`
		Active bool   `json:"active"`
		Power  *bool  `json:"power"`
		DPMS   *bool  `json:"dpms"`
	}
	if json.Unmarshal(out, &outs) != nil {
		return nil
	}
	var asleep []string
	for _, o := range outs {
		on := o.Power
		if on == nil {
			on = o.DPMS
		}
		if o.Active && on != nil && !*on && strings.TrimSpace(o.Name) != "" {
			asleep = append(asleep, o.Name)
		}
	}
	return asleep
}
