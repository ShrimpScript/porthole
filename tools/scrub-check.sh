#!/usr/bin/env bash
# Pre-publish gate: fails if any tracked or untracked file contains an identifier of the
# machine it runs on - host and user names, tailnet device names and MagicDNS id, CGNAT
# addresses, home paths.
#
# The identifiers are read from the machine at runtime rather than written down here. A
# gate that hardcodes the secret it looks for publishes the secret - this one works on
# anybody's machine and names nothing.
#
# -a is not optional. A single raw control byte makes git classify a file as binary and
# skip its contents in silence, so the gate would pass a file it never read.
# tools/check-bytes.py runs first and fails on any such byte in a text file.
#
# Names that cannot be read from the machine, such as other private project names, are
# covered only when listed: PORTHOLE_SCRUB_EXTRA may name a file of extra patterns
# (extended regular expressions, one per line, # for comments), kept outside the repo so
# the gate can check for them without publishing them.
set -uo pipefail
extra="${PORTHOLE_SCRUB_EXTRA:+$(realpath -m -- "$PORTHOLE_SCRUB_EXTRA")}"
cd "$(dirname "${BASH_SOURCE[0]}")/.."

fail=0
python3 tools/check-bytes.py || fail=1

# Synthetic fixtures that match on purpose: an example address in a unit test, a fictional
# path in a transcript fixture. Listed by path rather than by loosening the patterns, so a
# real identifier appearing in either file still fails.
ALLOW='daemon/internal/server/server_test.go|daemon/internal/server/failsafe_test.go|daemon/internal/sshkeys/sshkeys_test.go|daemon/internal/sshkeys/sshd_test.go|daemon/internal/transcript/transcript_test.go'

# Fixed patterns: tailnet CGNAT addresses, home paths, and a real MagicDNS tailnet id.
patterns=('100\.[0-9]+\.[0-9]+\.[0-9]+' '/home/[a-z]' 'tail[0-9a-f]{6,}\.ts\.net')

# Machine-derived: the host's names, the current user, and the tailnet's device names.
#
# Generic names are skipped: a tailnet device called "server" or "laptop" would turn a
# common word into a forbidden pattern and bury the real hits, and such a name identifies
# nobody anyway.
GENERIC=' server laptop desktop phone pc host home router nas pi mac linux windows work '
add() {
    local n="${1:-}"
    [ -n "$n" ] && [ ${#n} -ge 3 ] || return 0
    case "$GENERIC" in *" $(printf '%s' "$n" | tr 'A-Z' 'a-z') "*) return 0 ;; esac
    patterns+=("\\b$(printf '%s' "$n" | sed 's/[.[\*^$()+?{|]/\\&/g')\\b")
}
# Several sources, because `hostname` is not installed everywhere - and a gate that
# silently skips a check it could not run is worse than no gate. uname is POSIX.
add "$(uname -n 2>/dev/null | cut -d. -f1)"
add "$(cat /etc/hostname 2>/dev/null | tr -d '[:space:]')"
add "${HOSTNAME:-}"
command -v hostname >/dev/null 2>&1 && add "$(hostname -s 2>/dev/null)"
add "${USER:-$(whoami 2>/dev/null)}"
if command -v tailscale >/dev/null 2>&1; then
    while read -r name; do add "${name%%.*}"; done < <(
        tailscale status --json 2>/dev/null |
        python3 -c 'import json,sys
try: d = json.load(sys.stdin)
except Exception: raise SystemExit
peers = list((d.get("Peer") or {}).values()) + [d.get("Self") or {}]
for p in peers:
    n = (p.get("DNSName") or "").strip(".")
    if n: print(n)' 2>/dev/null
    )
fi
if [ -n "$extra" ]; then
    [ -f "$extra" ] || { echo "PORTHOLE_SCRUB_EXTRA: $extra not found" >&2; exit 1; }
    while IFS= read -r p || [ -n "$p" ]; do
        case "$p" in ''|'#'*) continue ;; esac
        patterns+=("$p")
    done < "$extra"
fi

joined=$(IFS='|'; echo "${patterns[*]}")
# --untracked: a new file is scanned before it is ever added, not after it is pushed.
# Font files are skipped: -a would read their compressed glyph data as text, where a short
# name can match by chance.
hits=$(git grep --untracked -anE "$joined" -- . \
    ':(exclude)*.ttf' ':(exclude)*.otf' ':(exclude)*.woff' ':(exclude)*.woff2' |
    grep -vE "^($ALLOW):" || true)

if [ -n "$hits" ]; then
    echo "machine-specific identifiers found - scrub before publishing:"
    echo "$hits" | sed 's/^/  /'
    fail=1
else
    echo "clean: no machine, user or tailnet identifiers outside the allowlisted fixtures"
fi
exit $fail
