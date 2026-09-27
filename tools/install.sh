#!/usr/bin/env bash
# Install portholed as a per-user service, on Linux (systemd) or macOS (launchd).
#
# Deliberately not a curl | sh one-liner. This installs a background service that can run
# commands as you, and a hook into your Claude Code settings; piping that from a URL into
# a shell is how you end up unable to say what is on your machine. Clone, read this file,
# then run it.
#
#   ./tools/install.sh              # install (builds with Go if it is installed)
#   ./tools/install.sh --prebuilt   # use the binary from the latest GitHub release instead
#   ./tools/install.sh --dry-run    # print every action, change nothing
#   ./tools/install.sh --no-hooks   # skip the Claude Code hook (approvals stay off)
set -euo pipefail

DRY=0
HOOKS=1
PREBUILT=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY=1 ;;
        --no-hooks) HOOKS=0 ;;
        --prebuilt) PREBUILT=1 ;;
        -h|--help) sed -n '2,12p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
RELEASES="https://github.com/ShrimpScript/porthole/releases/latest/download"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="$HOME/.local/bin"
case "$(uname -s)" in
    Linux) OS=linux ;;
    Darwin) OS=darwin ;;
    *) echo "portholed runs on Linux and macOS, not $(uname -s)" >&2; exit 1 ;;
esac

# The Tailscale app on a Mac keeps its CLI inside the app bundle unless it was installed
# from the app's menu.
TAILSCALE=tailscale
if ! command -v tailscale >/dev/null 2>&1 && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then
    TAILSCALE=/Applications/Tailscale.app/Contents/MacOS/Tailscale
fi

say()  { printf '  %s\n' "$*"; }
step() { printf '\n%s\n' "$*"; }
run()  { if [ "$DRY" = 1 ]; then printf '  would run: %s\n' "$*"; else "$@"; fi }

step "Checking what this needs"
missing=0
if command -v "$TAILSCALE" >/dev/null 2>&1; then
    say "tailscale: found"
else
    say "tailscale: MISSING - install it from https://tailscale.com/download and sign in"
    missing=1
fi
if command -v tmux >/dev/null 2>&1; then
    say "tmux: found"
elif [ "$OS" = darwin ]; then
    say "tmux: MISSING - brew install tmux"
    missing=1
else
    say "tmux: MISSING - install it with your package manager"
    missing=1
fi
if command -v claude >/dev/null 2>&1; then
    say "claude: found"
else
    say "claude: not on PATH - the daemon still runs, but there are no sessions to show"
fi
[ "$missing" = 0 ] || { echo; echo "Install the missing tools first." >&2; exit 1; }

# A daemon that binds the tailnet cannot start without one.
if "$TAILSCALE" status >/dev/null 2>&1; then
    say "tailscale: connected"
else
    say "tailscale: NOT connected - run 'tailscale up' before starting the daemon"
fi

# The release binary, checked against the release's SHA256SUMS before it is used.
fetch_prebuilt() {
    local arch asset
    case "$(uname -m)" in
        x86_64|amd64) arch=amd64 ;;
        aarch64|arm64) arch=arm64 ;;
        *) echo "  no prebuilt daemon for $(uname -m); install Go and run without --prebuilt" >&2; exit 1 ;;
    esac
    asset="portholed-$OS-$arch"
    command -v curl >/dev/null 2>&1 || { echo "  --prebuilt needs curl" >&2; exit 1; }
    if [ "$DRY" = 1 ]; then
        say "would download $RELEASES/$asset"
        say "would verify it against $RELEASES/SHA256SUMS"
        return
    fi
    say "downloading $RELEASES/$asset"
    local tmp; tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/$asset" "$RELEASES/$asset"
    curl -fsSL -o "$tmp/SHA256SUMS" "$RELEASES/SHA256SUMS"
    # sha256sum on Linux, shasum on a Mac; both read the same list.
    local check="sha256sum -c --quiet -"
    command -v sha256sum >/dev/null 2>&1 || check="shasum -a 256 -c --quiet -"
    (cd "$tmp" && grep " $asset\$" SHA256SUMS | $check) ||
        { echo "  checksum mismatch - not installing it" >&2; rm -rf "$tmp"; exit 1; }
    install -m 0755 "$tmp/$asset" "$REPO/daemon/portholed"
    rm -rf "$tmp"
    say "verified and saved to daemon/portholed"
}

step "Building portholed"
# Built fresh whenever Go is here, so running this again after a git pull installs the new
# daemon rather than whatever an earlier build left behind.
if [ "$PREBUILT" = 1 ]; then
    fetch_prebuilt
elif command -v go >/dev/null 2>&1; then
    run env -C "$REPO/daemon" CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o portholed ./cmd/portholed
    [ "$DRY" = 1 ] || say "built daemon/portholed"
elif [ -x "$REPO/daemon/portholed" ] && [ "$DRY" = 0 ]; then
    say "Go is not installed; using the binary already at daemon/portholed"
else
    say "Go is not installed; using the latest release's binary instead"
    fetch_prebuilt
fi

step "Installing to $BIN_DIR"
run mkdir -p "$BIN_DIR"
run install -m 0755 "$REPO/daemon/portholed" "$BIN_DIR/portholed"
# `porthole` is the same program under the name you start Claude Code with: it runs
# claude inside tmux, where the phone can see it and type to it.
run ln -sf "$BIN_DIR/portholed" "$BIN_DIR/porthole"
case ":$PATH:" in
    *":$BIN_DIR:"*) say "$BIN_DIR is on your PATH" ;;
    *) say "NOTE: $BIN_DIR is not on your PATH - add it, or the commands below will not resolve" ;;
esac

step "Installing the background service"
if [ "$OS" = darwin ]; then
    say "a launchd agent ($HOME/Library/LaunchAgents/dev.shrimpscript.portholed.plist) that starts"
    say "at login and restarts on failure; it keeps this shell's PATH so it finds tmux and claude"
else
    say "a systemd user service (~/.config/systemd/user/portholed.service)"
fi
run "$BIN_DIR/portholed" service install
if [ "$OS" = darwin ]; then
    say "logs: tail -f ~/Library/Logs/portholed.log"
else
    say "logs: journalctl --user -u portholed -f"
fi

if [ "$OS" = linux ]; then
    step "Surviving logout"
    if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null || echo no)" = "yes" ]; then
        say "linger is already enabled"
    else
        say "Run this, or the daemon dies when you log out - which is exactly when you are away:"
        say "    loginctl enable-linger $USER      (with sudo if it asks)"
    fi
else
    step "Staying reachable"
    say "portholed keeps the Mac awake while a session works or a phone is connected, on the"
    say "power adapter. It runs in your login session, so after a reboot it starts when you log in."
    say "For the failsafe shell, turn on Remote Login: System Settings > General > Sharing."
fi

if [ "$HOOKS" = 1 ]; then
    step "Claude Code hook"
    say "This edits ~/.claude/settings.json to add a PermissionRequest hook, which is what"
    say "lets you approve tool calls from the phone. Without it everything else still works."
    run "$BIN_DIR/portholed" install-hooks
    say "undo at any time with: portholed uninstall-hooks"
else
    step "Claude Code hook"
    say "skipped (--no-hooks); remote approval will be unavailable until you run:"
    say "    portholed install-hooks"
fi

step "Start Claude Code where the phone can reach it"
say "In a project's folder, run porthole instead of claude. It starts Claude Code inside"
say "tmux (running it again in the same folder brings that session back). Already in tmux?"
say "Plain claude works there too."

step "Pair your phone"
say "1. install Porthole on the phone (https://github.com/ShrimpScript/porthole/releases/latest),"
say "   and Tailscale if it is not there already"
say "2. run:  portholed pair"
say "3. scan the QR code it prints with the phone, or type the 6-digit code"
say ""
say "who is paired:   portholed devices"
say "cut a device off: portholed revoke <id>"
