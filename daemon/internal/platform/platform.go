// Package platform is what the daemon reads from the operating system differently on
// Linux and on macOS: when a process started, whether it has children, and which of the
// user's processes listen on a local TCP port. Everything else in the daemon is the same
// on both.
package platform

// Listener is a TCP port some process of this user listens on, on loopback or on every
// address, with the process behind it.
type Listener struct {
	Port    int
	PID     int
	Process string // the executable's short name
	Cmdline []byte // its arguments, NUL-separated as /proc/<pid>/cmdline has them
}
