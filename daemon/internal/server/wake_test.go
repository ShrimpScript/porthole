package server

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"
)

// Never a real desktop: the compositor's commands go to a recorder.
func fakeScreens(t *testing.T, monitors string) *[]string {
	t.Helper()
	var mu sync.Mutex
	var ran []string
	old, oldSettle, oldOS, oldLinger := screenCmd, screenSettle, screenOS, wakeLinger
	screenOS = "linux"
	screenCmd = func(_ context.Context, name string, args ...string) ([]byte, error) {
		mu.Lock()
		ran = append(ran, name+" "+strings.Join(args, " "))
		mu.Unlock()
		if name == "hyprctl" && len(args) > 1 && args[0] == "-j" && args[1] == "monitors" {
			return []byte(monitors), nil
		}
		if name == "hyprctl" && len(args) > 1 && args[1] == "cursorpos" {
			return []byte(*cursorAt), nil
		}
		return nil, nil
	}
	screenSettle = 0
	t.Cleanup(func() { screenCmd, screenSettle, screenOS, wakeLinger = old, oldSettle, oldOS, oldLinger })
	return &ran
}

// cursorAt is where the faked pointer is.
var cursorAt = new(string)

func TestSleepingScreensAreWokenAndPutBack(t *testing.T) {
	t.Setenv("HYPRLAND_INSTANCE_SIGNATURE", "test")
	t.Setenv("SWAYSOCK", "")
	ran := fakeScreens(t, `[{"name":"HDMI-A-1","dpmsStatus":false},{"name":"DP-1","dpmsStatus":true}]`)
	*cursorAt = `{"x":10,"y":10}`
	restore, woke := wakeScreens(context.Background())
	if !woke {
		t.Fatal("a sleeping monitor was not woken")
	}
	restore()
	want := []string{"hyprctl -j monitors", "hyprctl -j cursorpos", "hyprctl dispatch dpms on HDMI-A-1", "hyprctl -j cursorpos", "hyprctl dispatch dpms off HDMI-A-1"}
	if strings.Join(*ran, "|") != strings.Join(want, "|") {
		t.Fatalf("ran %q, want %q", *ran, want)
	}
}

func TestScreensAwakeAreLeftAlone(t *testing.T) {
	t.Setenv("HYPRLAND_INSTANCE_SIGNATURE", "test")
	ran := fakeScreens(t, `[{"name":"HDMI-A-1","dpmsStatus":true}]`)
	restore, woke := wakeScreens(context.Background())
	restore()
	if woke || len(*ran) != 1 {
		t.Fatalf("woke=%v ran %q", woke, *ran)
	}
}

func TestSwayOutputsReadPowerOrDPMS(t *testing.T) {
	old := screenCmd
	t.Cleanup(func() { screenCmd = old })
	screenCmd = func(context.Context, string, ...string) ([]byte, error) {
		return []byte(`[{"name":"eDP-1","active":true,"power":false},{"name":"HDMI-A-1","active":true,"dpms":false},{"name":"DP-2","active":false,"power":false},{"name":"DP-3","active":true,"power":true}]`), nil
	}
	if got := strings.Join(swayAsleep(context.Background()), ","); got != "eDP-1,HDMI-A-1" {
		t.Fatalf("asleep %q", got)
	}
}

// Someone sat down during the capture: their screens are not switched off under them.
func TestScreensStayOnForSomeoneWhoSatDown(t *testing.T) {
	t.Setenv("HYPRLAND_INSTANCE_SIGNATURE", "test")
	ran := fakeScreens(t, `[{"name":"DP-1","dpmsStatus":false}]`)
	*cursorAt = `{"x":10,"y":10}`
	restore, _ := wakeScreens(context.Background())
	*cursorAt = `{"x":400,"y":300}`
	restore()
	for _, r := range *ran {
		if strings.Contains(r, "dpms off") {
			t.Fatalf("switched off under someone: %q", *ran)
		}
	}
}

// Two captures share one waking, and the screens sleep again only after the last.
func TestCapturesShareOneWaking(t *testing.T) {
	t.Setenv("HYPRLAND_INSTANCE_SIGNATURE", "test")
	ran := fakeScreens(t, `[{"name":"DP-1","dpmsStatus":false}]`)
	*cursorAt = `{"x":1,"y":1}`
	wakeLinger = 50 * time.Millisecond
	var w waker
	ctx := context.Background()
	w.acquire(ctx)
	w.acquire(ctx)
	w.release()
	time.Sleep(120 * time.Millisecond)
	count := func(s string) (n int) {
		for _, r := range *ran {
			if strings.Contains(r, s) {
				n++
			}
		}
		return
	}
	if count("dpms on") != 1 || count("dpms off") != 0 {
		t.Fatalf("one capture still running: %q", *ran)
	}
	w.release()
	time.Sleep(120 * time.Millisecond)
	if count("dpms off") != 1 {
		t.Fatalf("not put back after the last: %q", *ran)
	}
}
