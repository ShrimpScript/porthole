package server

import (
	"context"
	"fmt"
	"log/slog"
	"os/exec"
	"strings"
)

// A session's window can be left far too small to read. A terminal attached at some point
// (a phone's, an SSH client's) sized it to its own viewport - one row, seen 2026-10-03 -
// and with nobody attached at the desk, nothing sizes it back. Claude Code then draws a
// single line, and everything the daemon reads off its screen goes with the rest: a
// question picker the phone could answer, the status, the input box.
const (
	minReadableCols = 40
	minReadableRows = 12
	readableCols    = 120
	readableRows    = 40
)

// ensureReadable gives such a window a readable size - only while no client is attached
// to its session, since someone at the desk decides their own window's size. tmux pins a
// window it was told to resize (window-size manual), so the option is put back after:
// the next client to attach sizes the window as it always did. Reports whether it resized.
func ensureReadable(ctx context.Context, target string, log *slog.Logger) bool {
	if target == "" {
		return false
	}
	// Anyone looking counts: a client on this session, on another member of its group,
	// or on the window itself. An open phone terminal (its window-size override parked
	// on the window) sizes the window itself, and is left to.
	out, err := exec.CommandContext(ctx, "tmux", "display-message", "-p", "-t", target,
		"#{window_id} #{window_width} #{window_height} #{session_attached} #{?session_grouped,#{session_group_attached},0} #{window_active_clients} [#{@porthole-prev-window-size}]").Output()
	if err != nil {
		return false
	}
	var id, bridge string
	var w, h, attached, groupAttached, active int
	if n, _ := fmt.Sscanf(strings.TrimSpace(string(out)), "%s %d %d %d %d %d %s", &id, &w, &h, &attached, &groupAttached, &active, &bridge); n != 7 {
		return false
	}
	if (w >= minReadableCols && h >= minReadableRows) || attached > 0 || groupAttached > 0 || active > 0 || bridge != "[]" {
		return false
	}
	prev := ""
	if o, err := exec.CommandContext(ctx, "tmux", "show-options", "-w", "-t", id, "window-size").Output(); err == nil {
		if f := strings.Fields(string(o)); len(f) == 2 {
			prev = f[1]
		}
	}
	nw, nh := max(w, readableCols), max(h, readableRows)
	if err := exec.CommandContext(ctx, "tmux", "resize-window", "-t", id, "-x", fmt.Sprint(nw), "-y", fmt.Sprint(nh)).Run(); err != nil {
		return false
	}
	if prev == "" || prev == "manual" {
		_ = exec.CommandContext(ctx, "tmux", "set-option", "-w", "-u", "-t", id, "window-size").Run()
	} else {
		_ = exec.CommandContext(ctx, "tmux", "set-option", "-w", "-t", id, "window-size", prev).Run()
	}
	if log != nil {
		log.Info("window too small to read, resized", "window", id, "was", fmt.Sprintf("%dx%d", w, h), "now", fmt.Sprintf("%dx%d", nw, nh))
	}
	return true
}
