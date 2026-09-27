# Porthole

Run and supervise Claude Code on your own Mac or Linux computer from your Android phone, over
your own Tailscale network. No account, and no Porthole server in between: your sessions travel only
between your phone and your computer.

**[Website](https://porthole-one.vercel.app)** ·
**[Docs](https://porthole-one.vercel.app/docs)** ·
**[Download for Android](https://github.com/ShrimpScript/porthole/releases/latest/download/porthole.apk)** ·
**[Changelog](https://porthole-one.vercel.app/changelog)**

![Claude Code asking a question in tmux on a computer, and the same question on the phone](https://porthole-one.vercel.app/og.png)

## What the phone can do

- **Every session at a glance.** One row per running Claude Code, grouped into Needs you,
  Live and Recent, with what each is doing and for how long - across more than one computer.
- **Answer Claude's questions by tap.** When Claude Code asks a multiple-choice question,
  the choices appear as buttons exactly as they stand on the computer's screen.
- **Approve or deny tool calls** from anywhere, with the command in full and a countdown.
  There is no "always allow".
- **The feed.** The conversation as rendered markdown with tool calls, timings and turn
  lines, a line where you left off, quick replies, slash commands and photo attachments.
- **What changed.** The git diff of the session's directory, file by file.
- **A real terminal.** The tmux pane at the desk's width, with a key row built for
  Claude Code.
- **Start, resume, or start another.** The + on the session list starts a fresh Claude Code in
  any project folder, in a tmux session of its own, like `porthole` at the desk.
- **Notifications** while a turn runs, when it finishes (with Reply from the shade), when a
  question waits, when a usage limit hits, and when the computer comes back.
- **Builds of your own Android projects**, published on the computer, installed on the
  phone in one tap.
- **Preview a dev server** from the computer in the phone's browser.
- **Screenshots and clips** of the computer's screen, when you ask.
- **A failsafe.** If the daemon stops answering, open a shell over SSH and restart it from the
  phone: Tailscale SSH on Linux, or on a Mac (whose Tailscale app has no SSH server) Remote
  Login with a key the phone makes and the daemon adds to `authorized_keys`, tied to the
  phone's tailnet addresses. The shell is your login shell with `PORTHOLE_FAILSAFE=1` set; if
  your shell profile attaches to tmux on every SSH login, skip that when the variable is set.

## How it works

```
Android app  ──WebSocket──►  portholed (Go)  ──►  tmux session running claude
(Kotlin,          tailnet     ├ transcript tailer
 Compose)      ──SSH────►     ├ hook receiver
                 failsafe     └ tmux/PTY bridge
```

- The phone does not embed Tailscale; the official Tailscale app already routes the
  tailnet.
- The daemon binds the computer's tailnet addresses only - never the LAN - identifies every
  peer through tailscaled's WhoIs, and admits a new phone only with a 6-digit code printed
  at the desk (or its QR code).
- Any WebSocket request carrying an `Origin` header is refused, so a web page open on a
  paired phone cannot drive the daemon.
- Session state comes from three places, each doing what it is best at: the Claude Code
  transcript (history), Claude Code hooks (approval requests), and the tmux pane (the
  terminal and what is on screen right now).

## Install

You need a Mac with macOS 13 or later, or a Linux computer with systemd user services, with
[Tailscale](https://tailscale.com), tmux and [Claude Code](https://code.claude.com/docs); and an
Android 10+ phone with Tailscale on the same tailnet.

**The phone.** Download
[`porthole.apk`](https://github.com/ShrimpScript/porthole/releases/latest/download/porthole.apk)
from the latest release and open it; Android asks once to allow installs from your browser.

### On a Mac

1. Install the [Tailscale app](https://tailscale.com/download/mac) and sign in. Turn on
   **Remote Login** (System Settings > General > Sharing): the Tailscale app has no SSH
   server, so this is the phone's way back in if the daemon stops. Once paired, the phone
   adds its own key for it from Settings.
2. With [Homebrew](https://brew.sh), which also brings tmux:

   ```sh
   brew install shrimpscript/tap/porthole
   portholed setup                 # the launchd agent and the approval hook
   portholed doctor                # checks everything the phone will depend on
   portholed pair                  # prints a code and a QR code for the phone
   ```

The daemon is a launchd agent: it starts when you log in (after an unattended reboot, only
once someone logs in, unless the Mac logs in by itself) and keeps the Mac awake while a
session works or a phone is connected, on the power adapter. Allow portholed to record the
screen when macOS asks, for screenshots. Log: `~/Library/Logs/portholed.log`. No Homebrew?
The installer below works on a Mac too.

### On Linux

1. Install Tailscale and sign in with Tailscale SSH on - the phone's way back in if the
   daemon stops - and install tmux from your package manager:

   ```sh
   sudo tailscale up --ssh          # already signed in: sudo tailscale set --ssh
   ```
2. Clone the repository, read the installer, run it:

   ```sh
   git clone https://github.com/ShrimpScript/porthole
   cd porthole
   ./tools/install.sh --dry-run    # read what it will do
   ./tools/install.sh              # builds with Go if you have it; --prebuilt uses the release binary
   sudo loginctl enable-linger $USER   # keep it running after you log out
   portholed doctor
   portholed pair
   ```

The daemon is a systemd user service, `portholed.service`. Log: `journalctl --user -u
portholed -f`. Homebrew works on Linux too: `brew install shrimpscript/tap/porthole`, then
`portholed setup`.

### Then

Then start Claude Code with `porthole` instead of `claude`, in your project's folder. It
runs Claude Code inside tmux - which is what lets the phone see its terminal and type to it -
and running it again in the same folder brings that session back instead of starting a
second one. Arguments go straight to `claude` (`porthole --resume`). Already inside tmux?
Plain `claude` works there too. The phone can also start or resume a session itself.

Neither setup is a `curl | sh` one-liner, deliberately: both install a background service
that can run commands as you, plus a hook into your Claude Code settings. `--no-hooks` (on
`install.sh`) or `-no-hooks` (on `portholed setup`) skips the settings change, at the cost
of remote approval.

| Command | |
|---|---|
| `porthole [ARGS]` | start Claude Code inside tmux, where the phone can reach it (ARGS go to claude) |
| `portholed setup` | install the background service and the approval hook, once |
| `portholed service install\|restart\|status\|uninstall` | the background service: launchd on a Mac, systemd on Linux |
| `portholed serve` | run the daemon (the background service does this) |
| `portholed pair` | print a single-use code and its QR code for a new phone |
| `portholed devices` / `revoke ID` | who is paired and connected; cut a device off at once |
| `portholed doctor` | check Tailscale, tmux, Claude Code, the service, key expiry, the failsafe, sleep and disk |
| `portholed publish APK VERSION APP` | offer a build of your Android project to the phone |
| `portholed install-hooks` / `uninstall-hooks` | add or remove the approval hook |
| `portholed sessions` | the sessions the phone would see |

## Updates

The app checks this repository's latest release at most once a day - one request to
`api.github.com`, off in **Settings > Updates** - and offers a newer version with an Update
button. Android installs it only if it is signed with the same key as the app you have.

## Builds of your projects

If Claude Code is building an Android app on the computer, publish each build and the
phone offers it with an install banner:

```sh
portholed publish app/build/outputs/apk/release/app-release.apk 1.4.0 shopping-list
```

The phone shows "Shopping list 1.4.0 is on the computer" with Install and Not now; the
build stays listed under Settings > Other apps on the computer.

## Building from source

```sh
cd daemon && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o portholed ./cmd/portholed && go test ./...
```

```sh
cd app-android && ./gradlew :app:assembleDebug     # Kotlin + Compose, minSdk 29
```

A release build is signed only when `app-android/keystore.properties` exists (it is never
committed); a fresh clone builds unsigned. An APK signed with a different key cannot update
the release app - uninstall that first.

## Security and privacy

- [`SECURITY.md`](SECURITY.md) - the security model, how connections are gated, and how to
  report a vulnerability privately.
- [`PRIVACY.md`](PRIVACY.md) - what the app talks to, stores and can do. Short, because there
  are no servers.

## Licence

Source-available: you may build, modify and run Porthole on your own devices, but not
redistribute it or builds of it. See [`LICENSE`](LICENSE).

Porthole is an independent project and is not affiliated with or endorsed by Anthropic or
Tailscale. Claude and Claude Code are trademarks of Anthropic, PBC; Tailscale is a trademark
of Tailscale Inc.
