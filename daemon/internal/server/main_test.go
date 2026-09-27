package server

import (
	"testing"

	"github.com/shrimpscript/porthole/daemon/internal/tmuxtest"
)

// Every test here runs against a tmux server of its own, never the one the person
// running them works in.
func TestMain(m *testing.M) { tmuxtest.Main(m) }
