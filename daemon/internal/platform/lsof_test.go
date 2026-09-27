package platform

import "testing"

// lsof -nP -iTCP -sTCP:LISTEN -F pcn, trimmed from a Mac: a vite server on the wildcard,
// the same port again over IPv6, a port below 1024, and one on a LAN address.
const lsofListen = `p4312
cnode
n*:5173
n[::1]:5173
p911
cControlCe
n*:7000
p77
csshd
n*:22
p5120
cpython3.12
n127.0.0.1:8000
n192.168.1.20:9000
`

func TestParseLsof(t *testing.T) {
	got := parseLsof(lsofListen)
	want := map[int]string{5173: "node", 7000: "ControlCe", 8000: "python3.12"}
	if len(got) != len(want) {
		t.Fatalf("got %+v", got)
	}
	for _, l := range got {
		if want[l.Port] != l.Process || l.PID == 0 {
			t.Errorf("unexpected %+v", l)
		}
	}
}
