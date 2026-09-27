#!/usr/bin/env bash
# Turn a crash report from the phone into real class and line names.
#
# A release build is minified, so its stack is unreadable without the mapping R8 wrote
# for that exact build - and the next release overwrites it. tools/release.sh keeps one
# per version; this finds the right one from the report's first line and uses it.
#
#   tools/retrace.sh ~/.config/porthole/crashes/<report>.txt
#   tools/retrace.sh <report> 0.24.1        # say the version yourself
set -euo pipefail

REPORT="${1:-}"
[ -f "$REPORT" ] || { sed -n '2,9p' "$0" | sed 's/^# \?//'; exit 2; }
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEPT="${PORTHOLE_STATE_DIR:-$HOME/.config/porthole}/mappings"

# "Porthole 0.24.1 (38)" -> 0.24.1
VERSION="${2:-$(grep -m1 -oE '^Porthole [0-9]+\.[0-9]+\.[0-9]+' "$REPORT" | awk '{print $2}')}"
[ -n "$VERSION" ] || { echo "no version in $REPORT; pass one as the second argument" >&2; exit 1; }

MAP=""
for candidate in "$KEPT/porthole-$VERSION.txt" "$KEPT/porthole-$VERSION.txt.gz"; do
    [ -f "$candidate" ] && MAP="$candidate" && break
done
if [ -z "$MAP" ]; then
    # The working tree's mapping, but only if it belongs to this build.
    live="$REPO/app-android/app/build/outputs/mapping/release/mapping.txt"
    want=$(grep -m1 -oE 'r8-map-id-[0-9a-f]+' "$REPORT" | sed 's/r8-map-id-//' || true)
    have=$(grep -m1 '# pg_map_id:' "$live" 2>/dev/null | awk '{print $3}' || true)
    if [ -n "$want" ] && [ "$want" = "$have" ]; then
        MAP="$live"
        echo "using the working tree's mapping (id matches)" >&2
    else
        echo "no mapping for $VERSION in $KEPT" >&2
        echo "the report's build is ${want:-unknown}; the tree holds ${have:-none}" >&2
        exit 1
    fi
fi

R8="${ANDROID_HOME:-$HOME/Android/Sdk}/cmdline-tools/latest/lib/r8.jar"
[ -f "$R8" ] || { echo "r8.jar not found at $R8" >&2; exit 1; }

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
case "$MAP" in *.gz) gunzip -c "$MAP" > "$work/mapping.txt"; MAP="$work/mapping.txt" ;; esac
# Only the stack: the report's header and the exit-reason list are already readable.
sed -n '/^[a-z0-9.]*\(Exception\|Error\|Throwable\)/,/^$/p' "$REPORT" > "$work/stack.txt"
[ -s "$work/stack.txt" ] || cp "$REPORT" "$work/stack.txt"

echo "# $REPORT  ->  $VERSION"
sed -n '1,4p' "$REPORT"
echo
java -cp "$R8" com.android.tools.r8.retrace.Retrace "$MAP" "$work/stack.txt"
