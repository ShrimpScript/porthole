#!/usr/bin/env bash
# Boot a headless emulator, install the debug app, and capture the onboarding and
# sessions screens at 411dp and at the 360dp floor.
#
#   tools/screenshots.sh [outdir]
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
AVD="porthole-test"
# NOT an aosp_atd image: ATD builds have the UI-rendering components stripped, so the
# app runs and lays out but nothing is ever composited - every screencap comes back
# solid black with no error anywhere.
IMAGE="system-images;android-36;google_apis;x86_64"
PKG="dev.shrimpscript.porthole"
OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/screenshots}"
APK="$(cd "$(dirname "$0")/.." && pwd)/app-android/app/build/outputs/apk/debug/app-debug.apk"

mkdir -p "$OUT"

PORTHOLED="${PORTHOLED:-$(cd "$(dirname "$0")/.." && pwd)/daemon/portholed}"
portholed_pair_code() {
    PORTHOLE_SOCK="${PORTHOLE_SOCK:-/tmp/porthole-shots.sock}" \
        "$PORTHOLED" pair | grep -oE '[0-9]{3} [0-9]{3}' | tr -d ' '
}

if [ ! -x "$EMU" ]; then
    echo "emulator not installed. Run:"
    echo "  $SDK/cmdline-tools/latest/bin/sdkmanager --install emulator \"$IMAGE\""
    exit 1
fi

if ! "$SDK/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "Name: $AVD"; then
    echo "==> creating AVD $AVD"
    echo "no" | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
        -n "$AVD" -k "$IMAGE" -d pixel_6 --force >/dev/null
fi

cleanup() {
    "$ADB" -s emulator-5554 emu kill >/dev/null 2>&1 || true
    [ -n "${DAEMON_PID:-}" ] && kill "$DAEMON_PID" >/dev/null 2>&1 || true
    rm -rf "${PORTHOLE_STATE_DIR:-}" "${PORTHOLE_SOCK:-}" || true
}
trap cleanup EXIT

# A real daemon on loopback: the emulator reaches it at 10.0.2.2, so pairing and the
# sessions list are genuine rather than fixtures.
export PORTHOLE_SOCK=/tmp/porthole-shots.sock
export PORTHOLE_STATE_DIR=/tmp/porthole-shots-state
rm -rf "$PORTHOLE_STATE_DIR" "$PORTHOLE_SOCK"; mkdir -p "$PORTHOLE_STATE_DIR"
"$PORTHOLED" serve -dev-listen 127.0.0.1:8737 >/tmp/porthole-shots-daemon.log 2>&1 &
DAEMON_PID=$!
sleep 2
echo "==> portholed running on 127.0.0.1:8737 (pid $DAEMON_PID)"

echo "==> booting emulator (headless)"
# Runs on a virtual X display so the window never lands on a real monitor.
xvfb-run -a -s "-screen 0 1600x2600x24" \
    "$EMU" -avd "$AVD" -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    >/tmp/porthole-emulator.log 2>&1 &

"$ADB" wait-for-device
# wait-for-device returns as soon as adb connects; the framework is not up yet.
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done
echo "==> booted"

"$ADB" install -r -g "$APK" >/dev/null
echo "==> installed $PKG"

# 10.0.2.2 is the emulator's alias for the host loopback, so the app talks to a real
# portholed on the host, and the sessions capture shows your own Claude Code sessions.
# Keep these captures out of anything published; published images come from the JVM
# renders (RenderScreensTest), which use neutral sample data.
HOST_ALIAS="10.0.2.2:8737"

shot() {  # shot <name> <route> [pairCode]
    local name="$1" route="$2" code="${3:-}"
    "$ADB" shell am force-stop "$PKG"
    if [ -n "$code" ]; then
        "$ADB" shell am start -n "$PKG/.MainActivity" \
            -e startRoute "$route" -e host "$HOST_ALIAS" -e pairCode "$code" >/dev/null
        sleep 4
    else
        "$ADB" shell am start -n "$PKG/.MainActivity" \
            -e startRoute "$route" -e host "$HOST_ALIAS" >/dev/null
        sleep 3
    fi
    "$ADB" exec-out screencap -p > "$OUT/$name.png"
    # A capture with a single distinct colour means the screen never composited. Fail
    # rather than keep a blank image.
    if ! python3 - "$OUT/$name.png" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
colours = im.getcolors(400000) or []
sys.exit(1 if len(colours) < 8 else 0)
PY
    then
        echo "    !! $name.png is blank - the emulator is not compositing" >&2
        exit 1
    fi
    echo "    $name.png"
}

capture_at() {  # capture_at <label> <density>
    local label="$1" density="$2"
    echo "==> $label (density ${density}dpi)"
    "$ADB" shell wm size 1080x2340 >/dev/null
    "$ADB" shell wm density "$density" >/dev/null
    sleep 1
    for route in welcome needs tailscale setup connect consent pair tour settings sessions-empty failure; do
        shot "${label}-${route}" "$route"
    done
    # The sessions screen is captured against the real daemon, with a real pairing code.
    local code
    code="$(portholed_pair_code)"
    shot "${label}-sessions" "sessions" "$code"
}

capture_at "411dp" 420   # a typical current phone width
capture_at "360dp" 480   # the smallest supported width

"$ADB" shell wm size reset >/dev/null || true
"$ADB" shell wm density reset >/dev/null || true
echo "==> screenshots in $OUT"
