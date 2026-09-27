#!/usr/bin/env bash
# Porthole hook shim.
#
# Contract: this must NEVER block Claude and NEVER fail. A dead or hung daemon may
# cost the user at most PORTHOLE_HOOK_TIMEOUT_MS, after which the session continues as
# if Porthole were not installed.
#
# Reads the hook JSON on stdin, forwards it to the daemon, always exits 0.
SOCK="${PORTHOLE_SOCK:-$XDG_RUNTIME_DIR/portholed.sock}"
TIMEOUT_MS="${PORTHOLE_HOOK_TIMEOUT_MS:-200}"
TIMEOUT_S=$(awk -v m="$TIMEOUT_MS" 'BEGIN{printf "%.3f", m/1000}')

# Truncate: a 4MB PostToolUse payload took 327ms to stall out, and the daemon reads full
# tool results from the transcript anyway - the hook only needs the envelope.
payload=$(head -c 65536)

timeout "$TIMEOUT_S" python3 -c '
import socket, sys, os
sock = sys.argv[1]
payload = sys.stdin.read()
t = float(os.environ.get("PORTHOLE_HOOK_TIMEOUT_MS", "200")) / 1000
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(t)
s.connect(sock)            # ENOENT when the daemon is gone: instant and harmless
s.sendall(payload.encode())
s.close()
' "$SOCK" <<<"$payload" >/dev/null 2>&1

exit 0
