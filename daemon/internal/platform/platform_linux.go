//go:build linux

package platform

import (
	"bufio"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// EphemeralFrom is where Linux hands out ports; a listener up there was not chosen.
const EphemeralFrom = 32768

// ProcStart is a process's start time exactly as Claude Code records it in its session
// registry on Linux: field 22 of /proc/<pid>/stat. ok is false when there is no such
// process.
func ProcStart(pid int) (string, bool) {
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return "", false
	}
	// "pid (comm) state ppid ... starttime ..." - comm may hold spaces and parentheses,
	// so count fields after the last ')': starttime is field 22, index 19 from there.
	i := strings.LastIndexByte(string(b), ')')
	if i < 0 {
		return "", false
	}
	f := strings.Fields(string(b[i+1:]))
	if len(f) < 20 {
		return "", false
	}
	return f[19], true
}

// HasChildren reports whether any process names pid as its parent. Unreadable entries
// count as none; an unreadable /proc as busy, the safe answer.
func HasChildren(pid int) bool {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return true
	}
	for _, e := range entries {
		if !e.IsDir() || e.Name()[0] < '0' || e.Name()[0] > '9' {
			continue
		}
		b, err := os.ReadFile(filepath.Join("/proc", e.Name(), "stat"))
		if err != nil {
			continue
		}
		// "pid (comm) state ppid ..." - comm may hold spaces, so parse after the last ')'.
		i := strings.LastIndexByte(string(b), ')')
		if i < 0 {
			continue
		}
		f := strings.Fields(string(b[i+1:]))
		if len(f) >= 2 && f[1] == strconv.Itoa(pid) {
			return true
		}
	}
	return false
}

// CanListListeners reports whether Listeners can see anything here.
func CanListListeners() bool {
	_, err := os.Stat("/proc/net/tcp")
	return err == nil
}

// Listeners lists LISTEN sockets on loopback or the wildcard with a port of 1024 or
// more, resolved to the owning process through /proc. Sockets of processes that are not
// ours are unreadable and therefore absent, which is the intent: what the user runs, not
// what the system does.
func Listeners() []Listener {
	portByInode := map[uint64]int{}
	for _, f := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		readProcNet(f, portByInode)
	}
	if len(portByInode) == 0 {
		return nil
	}
	pidByPort := map[int]int{}
	procs, _ := os.ReadDir("/proc")
	for _, e := range procs {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		fds, err := os.ReadDir(fmt.Sprintf("/proc/%d/fd", pid))
		if err != nil {
			continue // another user's process
		}
		for _, fd := range fds {
			link, err := os.Readlink(fmt.Sprintf("/proc/%d/fd/%s", pid, fd.Name()))
			if err != nil || !strings.HasPrefix(link, "socket:[") {
				continue
			}
			ino, err := strconv.ParseUint(strings.TrimSuffix(strings.TrimPrefix(link, "socket:["), "]"), 10, 64)
			if err != nil {
				continue
			}
			if port, ok := portByInode[ino]; ok {
				if _, seen := pidByPort[port]; !seen {
					pidByPort[port] = pid
				}
			}
		}
	}
	out := make([]Listener, 0, len(pidByPort))
	for port, pid := range pidByPort {
		comm, _ := os.ReadFile(fmt.Sprintf("/proc/%d/comm", pid))
		cmdline, _ := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
		out = append(out, Listener{Port: port, PID: pid, Process: strings.TrimSpace(string(comm)), Cmdline: cmdline})
	}
	return out
}

// readProcNet collects LISTEN sockets on loopback/wildcard from one /proc/net table.
func readProcNet(path string, portByInode map[uint64]int) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Scan() // header
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 10 || fields[3] != "0A" { // 0A = LISTEN
			continue
		}
		hostHex, portHex, ok := strings.Cut(fields[1], ":")
		if !ok || !localOrAny(hostHex) {
			continue
		}
		port64, err := strconv.ParseUint(portHex, 16, 16)
		if err != nil || port64 < 1024 {
			continue
		}
		ino, err := strconv.ParseUint(fields[9], 10, 64)
		if err != nil {
			continue
		}
		portByInode[ino] = int(port64)
	}
}

// localOrAny: /proc writes addresses as little-endian hex words. 127.0.0.1 is
// 0100007F, ::1 ends in 01000000, and any-address is all zeros in either width.
func localOrAny(h string) bool {
	b, err := hex.DecodeString(h)
	if err != nil {
		return false
	}
	switch len(b) {
	case 4:
		return (b[3] == 127) || (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0)
	case 16:
		allZero := true
		for i := 0; i < 15; i++ {
			if b[i] != 0 {
				allZero = false
				break
			}
		}
		return allZero && (b[15] == 0 || b[12] == 1 && b[13] == 0 && b[14] == 0 && b[15] == 0) || isProcV6Loopback(b)
	}
	return false
}

func isProcV6Loopback(b []byte) bool {
	// ::1 as /proc prints it: three zero words then 01000000.
	for i := 0; i < 12; i++ {
		if b[i] != 0 {
			return false
		}
	}
	return b[12] == 1 && b[13] == 0 && b[14] == 0 && b[15] == 0
}
