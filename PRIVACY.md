# Privacy

Short, because the architecture makes it short: **Porthole has no servers.** There is no
account, no sign-in, no backend, and no analytics. Nothing you do in the app reaches
ShrimpScript, because there is nowhere for it to go.

## What the app talks to

Your own computer, inside your own tailnet, and GitHub for Porthole's own updates:

| Destination | What for | When |
|---|---|---|
| `ws://<your computer>:8737` | the daemon: sessions, feed, approvals, terminal, builds of your projects | while the app is connected |
| SSH to `<your computer>` | the failsafe shell | only when you open it |
| `api.github.com` | asks whether a newer Porthole release exists | at most once a day, and when you tap Check now |
| `github.com`, `*.githubusercontent.com` | downloads the new Porthole APK | only when you tap Update |

The update check is a single request for the project's latest release. It carries nothing
about you, the phone, or your computer beyond what any web request carries - your IP
address reaches GitHub, as it does when you open any web page. Turn it off in
**Settings > Updates**. A build of the app made for a store has no update check at all.

Links in the app (Tailscale's store page, links in Claude's replies, a dev server you
choose to open) are things you tap, not requests the app makes on its own. Switching the
computer's Claude account opens Claude's own sign-in page in the phone's browser, where you
sign in with Anthropic as on any device; the code that page shows goes from the phone to
Claude Code on your computer across your tailnet, and Porthole keeps no copy of it or of
the sign-in. While that sign-in waits for its code, the app reads the clipboard whenever its
window has focus, only to recognise that page's code, and does nothing else with it. Scanning a
pairing QR code uses Google's code scanner, part of Google Play services on the phone;
Porthole receives only the scanned text and needs no camera permission. Play services runs
the scanner under Google's own terms, which let it send Google usage metrics; typing the
6-digit code instead avoids it.

## What leaves your computer

Your session transcripts, file contents, command output and terminal bytes travel from your
computer to your phone across your tailnet, which is a direct WireGuard connection between
two devices you own. Traffic does not pass through ShrimpScript, and it does not pass
through Tailscale's servers either except as encrypted relay traffic when a direct path
cannot be established - the standard DERP behaviour, documented by Tailscale.

## What the app stores on the phone

- the computers it is paired with, their addresses, and the username the SSH failsafe logs
  in as (learned from the daemon)
- if you add one, the phone's own SSH key for the failsafe, encrypted under a key in
  Android's Keystore
- your settings: theme, terminal text size, notification choices, muted sessions, quick
  replies, and whether to check GitHub for updates
- when each session was last opened (for the "since you left" line), and which builds you
  put away
- the result of the last update check
- the last session list the app saw (titles, folders, branches, and what each was doing or
  asking), for the home-screen widget, the quick tile, and the list while the computer cannot
  be reached
- a copy of the recent feed of the 20 sessions you opened most recently, up to the last 200
  rows of each, so a session opens at once and stays readable while the computer cannot be
  reached. A copy holds what the feed showed, tool output and file contents included, so
  anything a session printed (a secret among it) is in its copy too. Images are not kept,
  only where they were
- what you have typed in a session's message box and not sent yet, until it is sent or you
  clear it
- in the app's cache, APKs you downloaded and screen clips you opened, which Android clears
  as needed
- if the app crashes, a report of that crash; it is sent to your own computer the next time
  the app connects (it lands in `~/.config/porthole/crashes/`) and then deleted from the phone

Nothing beyond those recent copies, and no credentials of the app's own. All of it is in the
app's private storage, which other apps cannot read. Forgetting a computer (or unpairing)
deletes the copies of its sessions and their drafts. Uninstalling the app removes all of it. Revoking the device with `portholed revoke` on the computer cuts it off immediately,
whether or not the phone cooperates.

## Permissions the app asks for

| Permission | Why |
|---|---|
| `INTERNET` | to reach your computer over the tailnet, and GitHub for updates |
| `ACCESS_NETWORK_STATE` | to reconnect when your phone gets a network back |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | to hold the connection open while you are away from the app |
| `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS` | the connection, finished turns, questions and approvals |
| `REQUEST_INSTALL_PACKAGES` | to hand a downloaded APK (a Porthole update or a build of your project) to Android's installer, which asks you first |

The app requests no location, no contacts, no camera, no microphone, and no storage
access. Photos you attach come through Android's photo picker, which shares only the
photos you pick.

## What it can do on your computer

Everything the consent screen lists, because that is the point of the product: run
commands as your user, read and write files your user can reach, approve tool calls, and
read your Claude Code session transcripts. It is worth being plain about this - a device
paired with Porthole is a device that can act as you on that computer. Pair only your own
phone, and revoke it if you lose the device.

## Third-party code

The app bundles no analytics or advertising SDKs. Its dependencies are AndroidX and
Jetpack Compose (UI), OkHttp (the connection and downloads), sshj with BouncyCastle and
eddsa (the SSH failsafe), Glance (the widget) and Google's code scanner (pairing QR codes).
Porthole uses none of them to send data anywhere else; the code scanner's own reporting to
Google is described above.

## This website

The site at porthole-one.vercel.app sets no cookies and runs no analytics, and is hosted on
Vercel. Its fonts are served from the site itself. The front page loads three.js from
cdn.jsdelivr.net and GSAP from cdnjs.cloudflare.com to draw and animate the phone; those
servers see your IP address, as any server a page loads files from does. Your browser keeps
two things for the site, which never leave it: whether you have seen the opening, and whether
you picked Mac or Linux in the install steps.

## Contact

Questions about this policy: open an issue on the repository.
