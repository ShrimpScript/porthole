//go:build darwin

package main

import (
	"regexp"
	"strings"
)

// macAwayChecks is doctor's "will it still be there tomorrow" section on a Mac: whether
// anything starts it after a reboot, how soon the Mac sleeps, and what the failsafe can
// log in to.
func macAwayChecks(add func(state, name, detail string)) {
	// A launchd agent runs inside a login session. After a reboot nothing runs until
	// someone logs in, unless the Mac logs in by itself.
	if out, err := probe("defaults", "read", "/Library/Preferences/com.apple.loginwindow", "autoLoginUser"); err == nil && strings.TrimSpace(string(out)) != "" {
		add("ok", "after a reboot", "the Mac logs in by itself, so the daemon starts without you")
	} else {
		add("info", "after a reboot", "the daemon starts when you log in; after an unattended reboot the phone waits until someone does")
	}
	if out, err := probe("pmset", "-g"); err == nil {
		mins := pmsetSleep(string(out))
		switch {
		case mins == 0:
			add("ok", "sleep", "the Mac never sleeps on its own")
		case mins > 0:
			add("info", "sleep", "the Mac sleeps after "+itoa(mins)+" idle minutes; portholed keeps it awake while a session works or a phone is connected, on the power adapter")
		}
	}
}

var pmsetSleepLine = regexp.MustCompile(`(?m)^\s*sleep\s+(\d+)`)

// pmsetSleep reads "sleep N" (minutes of idle before system sleep) from `pmset -g`; -1
// when it is not there.
func pmsetSleep(out string) int {
	m := pmsetSleepLine.FindStringSubmatch(out)
	if m == nil {
		return -1
	}
	n := 0
	for _, r := range m[1] {
		n = n*10 + int(r-'0')
	}
	return n
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for ; n > 0; n /= 10 {
		b = append([]byte{byte('0' + n%10)}, b...)
	}
	return string(b)
}
