#!/usr/bin/env bash
# Cut a release: bump both versions, test, build the signed APK, the store bundle and the
# daemon binaries, commit and tag, and (optionally) push and publish a GitHub release.
#
#   tools/release.sh 0.26.0                    # everything up to the tag, artefacts in dist/
#   tools/release.sh 0.26.0 --push             # also push, publish the GitHub release and
#                                              # update the Homebrew formula
#   tools/release.sh 0.26.0 --deploy           # also install the new daemon locally
#   tools/release.sh 0.26.0 --dry-run          # print every step, change nothing
#
# The GitHub release is what installed apps update from: it carries porthole.apk, the
# daemon for Linux and macOS on amd64 and arm64, the third-party notices and SHA256SUMS. The APK is
# signed with the key in app-android/keystore.properties (gitignored) - every release must
# use the same key, or Android refuses the update. The store bundle is built with
# -Pporthole.store=true, which compiles the GitHub update check out; point KEYSTORE_PROPS
# at the store upload key's properties to sign it with that key instead.
set -euo pipefail

VERSION="${1:-}"
[ -n "$VERSION" ] || { sed -n '2,15p' "$0" | sed 's/^# \?//'; exit 2; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must be X.Y.Z, got $VERSION" >&2; exit 2; }
shift
DRY=0; DEPLOY=0; PUSH=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY=1 ;;
        --deploy) DEPLOY=1 ;;
        --push) PUSH=1 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
[ -z "${KEYSTORE_PROPS:-}" ] || KEYSTORE_PROPS="$(realpath -m -- "$KEYSTORE_PROPS")"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
DIST="$REPO/dist/$VERSION"
KEEP="${PORTHOLE_STATE_DIR:-$HOME/.config/porthole}/mappings"
run() { echo "+ $*"; [ "$DRY" = 1 ] || "$@"; }

# 1. A clean tree, on main, with what a push, the signing and the notices need.
if [ -n "$(git status --porcelain)" ]; then
    echo "working tree is not clean; commit or stash first" >&2; git status --short >&2; exit 1
fi
branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = main ] || { echo "release from main, not $branch" >&2; exit 1; }
if git rev-parse "v$VERSION" >/dev/null 2>&1; then echo "tag v$VERSION exists" >&2; exit 1; fi
if [ "$PUSH" = 1 ]; then
    command -v gh >/dev/null 2>&1 || { echo "--push needs the GitHub CLI (gh)" >&2; exit 1; }
    gh auth status >/dev/null 2>&1 || { echo "--push needs 'gh auth login' first" >&2; exit 1; }
fi
[ -f app-android/keystore.properties ] || echo "note: no app-android/keystore.properties - the APK will be unsigned and cannot update an installed app" >&2
if [ -n "${KEYSTORE_PROPS:-}" ] && [ ! -f "$KEYSTORE_PROPS" ]; then
    echo "KEYSTORE_PROPS=$KEYSTORE_PROPS not found" >&2; exit 1
fi
for notice in app-android/THIRD_PARTY.md daemon/THIRD_PARTY.md; do
    [ -f "$notice" ] || { echo "$notice is missing; every release carries the third-party notices" >&2; exit 1; }
done

# 2. Versions: the daemon's constant, the app's name and a code one higher than the last.
current_code=$(grep -oE 'versionCode = [0-9]+' app-android/app/build.gradle.kts | grep -oE '[0-9]+')
next_code=$((current_code + 1))
echo "daemon Version -> $VERSION; app versionName -> $VERSION, versionCode $current_code -> $next_code"
if [ "$DRY" = 0 ]; then
    sed -i -E "s/Version = \"[0-9.]+\"/Version = \"$VERSION\"/" daemon/internal/server/server.go
    sed -i -E "s/versionCode = [0-9]+/versionCode = $next_code/; s/versionName = \"[0-9.]+\"/versionName = \"$VERSION\"/" app-android/app/build.gradle.kts
    grep -q "Version = \"$VERSION\"" daemon/internal/server/server.go || { echo "daemon version did not take" >&2; exit 1; }
    grep -q "versionName = \"$VERSION\"" app-android/app/build.gradle.kts || { echo "app version did not take" >&2; exit 1; }
fi

# 3. Daemon: vet, tests, and static binaries. -trimpath keeps the build machine's paths
# out of the binaries, which are published.
run mkdir -p "$DIST"
run bash -c "cd daemon && go vet ./... && go test ./... >/dev/null"
for os in linux darwin; do
    for arch in amd64 arm64; do
        run env -C daemon CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" go build -trimpath -ldflags='-s -w' -o "$DIST/portholed-$os-$arch" ./cmd/portholed
    done
done

# 4. App: unit tests and the APK, whose R8 mapping is kept before the bundle build
# overwrites it - without it a crash report from this APK cannot be read.
MAPPING="app-android/app/build/outputs/mapping/release/mapping.txt"
run bash -c "cd app-android && ./gradlew -q :app:testDebugUnitTest :app:assembleRelease"
run cp app-android/app/build/outputs/apk/release/app-release.apk "$DIST/porthole.apk"
run mkdir -p "$KEEP"
if [ "$DRY" = 0 ]; then
    gzip -c "$MAPPING" > "$KEEP/porthole-$VERSION.txt.gz"
    echo "+ kept mapping -> $KEEP/porthole-$VERSION.txt.gz"
fi

# 5. The store bundle: the update check compiled out, optionally a different key.
STORE="-Pporthole.store=true"
if [ -n "${KEYSTORE_PROPS:-}" ]; then
    echo "bundle signed with $KEYSTORE_PROPS"
    run env -C app-android ./gradlew -q :app:bundleRelease "$STORE" -Pporthole.keystoreProps="$KEYSTORE_PROPS"
else
    echo "no KEYSTORE_PROPS: the bundle is signed with the APK's key (fine for testing, not for a store)"
    run env -C app-android ./gradlew -q :app:bundleRelease "$STORE"
fi
run cp app-android/app/build/outputs/bundle/release/app-release.aab "$DIST/porthole-$VERSION-store.aab"
[ "$DRY" = 1 ] || gzip -c "$MAPPING" > "$KEEP/porthole-$VERSION-store.txt.gz"

# 6. The third-party notices, which the licences of the bundled fonts and Go modules
# require to travel with the binaries, and checksums for everything the release offers.
run bash -c "{ cat app-android/THIRD_PARTY.md; echo; cat daemon/THIRD_PARTY.md; } > '$DIST/THIRD_PARTY.txt'"
run bash -c "cd '$DIST' && sha256sum porthole.apk portholed-linux-amd64 portholed-linux-arm64 portholed-darwin-amd64 portholed-darwin-arm64 THIRD_PARTY.txt > SHA256SUMS"

# 7. Nothing machine-specific leaves the repo.
run ./tools/scrub-check.sh

# 8. Commit and tag, stamped in UTC: a commit records the local UTC offset otherwise.
run git add daemon/internal/server/server.go app-android/app/build.gradle.kts
run env TZ=UTC git -c user.name='ShrimpScript' -c user.email='shrimpscript@users.noreply.github.com' commit -q -m "Release $VERSION"
run env TZ=UTC git -c user.name='ShrimpScript' -c user.email='shrimpscript@users.noreply.github.com' tag -a "v$VERSION" -m "Porthole $VERSION"

# 9. Optional: install the new daemon locally and restart its user service.
if [ "$DEPLOY" = 1 ]; then
    run install -m 0755 "$DIST/portholed-$(uname -s | tr A-Z a-z)-$(uname -m | sed 's/x86_64/amd64/; s/aarch64/arm64/')" "$HOME/.local/bin/portholed.new"
    run mv -f "$HOME/.local/bin/portholed.new" "$HOME/.local/bin/portholed"
    run "$HOME/.local/bin/portholed" service restart
fi

# 10. Optional: push, and publish the release installed apps update from. The notes are
# this version's entry in site/changelog.html, as plain text.
if [ "$PUSH" = 1 ]; then
    run git push origin main "v$VERSION"
    NOTES="$DIST/NOTES.md"
    [ "$DRY" = 1 ] && NOTES=/dev/null
    python3 - "$VERSION" site/changelog.html > "$NOTES" <<'PY'
import html, re, sys
version, path = sys.argv[1], sys.argv[2]
page = open(path, encoding="utf-8").read()
for sec in re.findall(r'<section class="rel">(.*?)</section>', page, re.S):
    if re.search(r'class="v">\s*' + re.escape(version) + r'\s*<', sec):
        head = re.search(r"<h2>(.*?)</h2>", sec, re.S)
        if head:
            print(html.unescape(re.sub(r"<[^>]+>", "", head.group(1))).strip() + "\n")
        for li in re.findall(r"<li>(.*?)</li>", sec, re.S):
            print("- " + " ".join(html.unescape(re.sub(r"<[^>]+>", "", li)).split()))
        break
else:
    print("See the changelog: https://porthole-one.vercel.app/changelog")
print("\nInstall: download porthole.apk on the phone. On the computer, brew install shrimpscript/tap/porthole "
      "then portholed setup, or run tools/install.sh from a clone.")
PY
    run gh release create "v$VERSION" --title "Porthole $VERSION" --notes-file "$NOTES" \
        "$DIST/porthole.apk" "$DIST/portholed-linux-amd64" "$DIST/portholed-linux-arm64" "$DIST/portholed-darwin-amd64" "$DIST/portholed-darwin-arm64" \
        "$DIST/THIRD_PARTY.txt" "$DIST/SHA256SUMS"
fi
# 11. Optional, with --push: the Homebrew formula, in its own tap repository, pointing at
# the binaries and checksums just published.
TAP="${PORTHOLE_TAP:-ShrimpScript/homebrew-tap}"
if [ "$PUSH" = 1 ]; then
    if [ "$DRY" = 1 ]; then
        echo "+ update Formula/porthole.rb in $TAP"
    elif gh repo view "$TAP" >/dev/null 2>&1; then
        TAPDIR=$(mktemp -d)
        gh repo clone "$TAP" "$TAPDIR" -- -q
        mkdir -p "$TAPDIR/Formula"
        ./tools/homebrew-formula.sh "$VERSION" "$DIST/SHA256SUMS" > "$TAPDIR/Formula/porthole.rb"
        git -C "$TAPDIR" add Formula/porthole.rb
        TZ=UTC git -C "$TAPDIR" -c user.name='ShrimpScript' -c user.email='shrimpscript@users.noreply.github.com' commit -q -m "porthole $VERSION"
        git -C "$TAPDIR" push -q origin HEAD
        rm -rf "$TAPDIR"
        echo "+ $TAP: Formula/porthole.rb -> $VERSION"
    else
        echo "note: $TAP does not exist; the Homebrew formula was not updated" >&2
    fi
fi

echo "released $VERSION: $DIST (porthole.apk, porthole-$VERSION-store.aab, daemon binaries, THIRD_PARTY.txt, SHA256SUMS), tag v$VERSION"
