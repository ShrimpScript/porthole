#!/usr/bin/env bash
# The failsafe key on a real Android device or emulator, against a real sshd.
#
# 1. The device makes its Keystore-sealed key and reports the public half.
# 2. A private sshd starts on this machine (loopback, a spare port, its own host key and
#    keys file), trusting that key with exactly the options the daemon writes.
# 3. The device signs in through the emulator's alias for this machine, 10.0.2.2.
#
# Needs one emulator running (adb devices), sshd and ssh-keygen. Touches nothing outside
# a temporary directory; the device keeps its test key in a preferences file of its own.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../app-android"

ADB="${ADB:-adb}"
RUNNER=dev.shrimpscript.porthole.test/androidx.test.runner.AndroidJUnitRunner
TEST=dev.shrimpscript.porthole.net.FailsafeDeviceTest
SSHD=$(command -v /usr/sbin/sshd /usr/bin/sshd 2>/dev/null | head -1)
[ -n "$SSHD" ] || { echo "no sshd here" >&2; exit 1; }

./gradlew -q :app:installDebug :app:installDebugAndroidTest

echo "1. the device makes its key"
out=$("$ADB" shell am instrument -w -r -e class "$TEST#keystoreKeepsTheKey" "$RUNNER")
echo "$out" | grep -q "OK (1 test)" || { echo "$out" >&2; exit 1; }
PUB=$(echo "$out" | sed -n 's/^INSTRUMENTATION_STATUS: pubkey=//p' | head -1 | tr -d '\r')
[ -n "$PUB" ] || { echo "no public key reported" >&2; exit 1; }
echo "   ${PUB:0:40}..."

echo "2. a private sshd trusts it"
DIR=$(mktemp -d)
trap 'kill "$SSHD_PID" 2>/dev/null || true; rm -rf "$DIR"' EXIT
ssh-keygen -q -t ed25519 -N "" -f "$DIR/host"
# The emulator's traffic to 10.0.2.2 arrives on this machine's loopback.
echo "restrict,pty,from=\"127.0.0.1\" ${PUB% *} porthole:device-test" > "$DIR/authorized_keys"
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
cat > "$DIR/sshd_config" <<EOF
ListenAddress 127.0.0.1
Port $PORT
HostKey $DIR/host
AuthorizedKeysFile $DIR/authorized_keys
PidFile $DIR/sshd.pid
StrictModes no
PasswordAuthentication no
KbdInteractiveAuthentication no
EOF
"$SSHD" -D -e -f "$DIR/sshd_config" 2> "$DIR/sshd.log" &
SSHD_PID=$!
sleep 1

echo "3. the device signs in"
out=$("$ADB" shell am instrument -w -r -e class "$TEST#signsInToARealSshd" \
    -e sshPort "$PORT" -e sshUser "$(id -un)" "$RUNNER")
if echo "$out" | grep -q "OK (1 test)"; then
    grep -E "Accepted publickey" "$DIR/sshd.log" | sed 's/^/   sshd: /'
    echo "passed: a Keystore-sealed key signed in to a real sshd from Android"
else
    echo "$out" >&2
    cat "$DIR/sshd.log" >&2
    exit 1
fi
