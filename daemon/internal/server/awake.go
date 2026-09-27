package server

import (
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"sync"
)

// keepAwake holds the computer out of idle sleep while it matters: a phone is connected,
// or a session is working. A Mac sleeps after a few idle minutes by default, and a
// sleeping computer is unreachable from the phone. On a Mac the hold is
// `caffeinate -s -w <daemon pid>`: an assertion that only applies on the power adapter
// (a laptop on battery still sleeps) and that ends with the daemon, so a crash can never
// leave the Mac awake. Elsewhere nothing is held; a Linux machine's sleep is the user's
// own setting, and doctor reports it.
type keepAwake struct {
	mu      sync.Mutex
	phones  int
	working map[string]bool
	hold    *exec.Cmd
	newHold func() *exec.Cmd // nil where there is nothing to hold with
}

func newKeepAwake() *keepAwake {
	k := &keepAwake{working: map[string]bool{}}
	if runtime.GOOS == "darwin" {
		pid := strconv.Itoa(os.Getpid())
		k.newHold = func() *exec.Cmd { return exec.Command("caffeinate", "-s", "-w", pid) }
	}
	return k
}

func (k *keepAwake) phone(delta int) {
	if k == nil {
		return
	}
	k.mu.Lock()
	defer k.mu.Unlock()
	k.phones += delta
	if k.phones < 0 {
		k.phones = 0
	}
	k.apply()
}

func (k *keepAwake) session(id string, working bool) {
	if k == nil {
		return
	}
	k.mu.Lock()
	defer k.mu.Unlock()
	if working {
		k.working[id] = true
	} else {
		delete(k.working, id)
	}
	k.apply()
}

// apply starts or ends the hold to match the state; callers hold k.mu.
func (k *keepAwake) apply() {
	want := k.phones > 0 || len(k.working) > 0
	switch {
	case want && k.hold == nil && k.newHold != nil:
		c := k.newHold()
		if c.Start() == nil {
			k.hold = c
			go func() { _ = c.Wait() }()
		}
	case !want && k.hold != nil:
		if k.hold.Process != nil {
			_ = k.hold.Process.Kill()
		}
		k.hold = nil
	}
}

// holding reports whether the hold is on, for tests and doctor.
func (k *keepAwake) holding() bool {
	k.mu.Lock()
	defer k.mu.Unlock()
	return k.hold != nil
}
