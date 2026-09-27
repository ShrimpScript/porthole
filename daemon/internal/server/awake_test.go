package server

import (
	"os/exec"
	"testing"
)

func TestKeepAwakeHoldsOnlyWhileAPhoneOrATurnNeedsIt(t *testing.T) {
	starts := 0
	k := &keepAwake{working: map[string]bool{}, newHold: func() *exec.Cmd {
		starts++
		return exec.Command("sleep", "30") // stands in for caffeinate
	}}
	if k.holding() {
		t.Fatal("nothing needs it yet")
	}
	k.phone(+1)
	k.session("a", true)
	if !k.holding() || starts != 1 {
		t.Fatalf("one hold for both reasons, got holding=%v starts=%d", k.holding(), starts)
	}
	k.phone(-1)
	if !k.holding() {
		t.Fatal("a working session still needs it")
	}
	k.session("a", false)
	if k.holding() {
		t.Fatal("nothing needs it any more")
	}
	k.phone(-1) // a stray disconnect must not go negative and pin it later
	k.session("b", true)
	k.session("b", false)
	if k.holding() || starts != 2 {
		t.Fatalf("holding=%v starts=%d", k.holding(), starts)
	}
}

func TestKeepAwakeIsANoOpWithoutAHold(t *testing.T) {
	k := &keepAwake{working: map[string]bool{}}
	k.phone(+1)
	k.session("a", true)
	if k.holding() {
		t.Fatal("no hold on this platform")
	}
}
