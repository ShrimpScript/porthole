package platform

import (
	"strconv"
	"strings"
)

// parseLsof reads `lsof -F pcn` output: "p<pid>" and "c<command>" start a process, and
// each "n<address>:<port>" after them is one of its listening sockets. Only loopback and
// wildcard addresses with a port of 1024 or more are kept, one entry per port.
func parseLsof(out string) []Listener {
	var res []Listener
	seen := map[int]bool{}
	pid, comm := 0, ""
	for _, line := range strings.Split(out, "\n") {
		if line == "" {
			continue
		}
		switch line[0] {
		case 'p':
			pid, _ = strconv.Atoi(line[1:])
			comm = ""
		case 'c':
			comm = line[1:]
		case 'n':
			addr := line[1:]
			i := strings.LastIndexByte(addr, ':')
			if i < 0 {
				continue
			}
			host, port := addr[:i], addr[i+1:]
			p, err := strconv.Atoi(port)
			if err != nil || p < 1024 || seen[p] || !loopbackOrAny(host) {
				continue
			}
			seen[p] = true
			res = append(res, Listener{Port: p, PID: pid, Process: comm})
		}
	}
	return res
}

func loopbackOrAny(host string) bool {
	h := strings.Trim(host, "[]")
	return h == "*" || h == "127.0.0.1" || h == "::1" || h == "localhost" || h == "0.0.0.0" || h == "::" || strings.HasPrefix(h, "127.")
}
