//go:build !linux && !darwin

package platform

// EphemeralFrom is the IANA dynamic range, where most systems hand out ports.
const EphemeralFrom = 49152

// ProcStart cannot be read here; a registered pid is taken as alive.
func ProcStart(pid int) (string, bool) { return "", true }

// HasChildren cannot be read here; busy is the safe answer.
func HasChildren(pid int) bool { return true }

func CanListListeners() bool { return false }

func Listeners() []Listener { return nil }
