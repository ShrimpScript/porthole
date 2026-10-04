# Security

Porthole grants a phone the ability to act as you on your computer - run commands, read
and write files, approve tool calls. That is the product, so the security model is not an
afterthought. This is what it rests on, and what has been checked.

## The model

Two device-level gates, plus a browser gate in front of them:

- **Gate 0 - no browsers.** Any WebSocket request carrying an `Origin` header is refused
  before anything else. The native client is not a browser and sends no `Origin`; a
  present one means a browser, which has no legitimate reason to reach this socket. See
  below for why this gate exists.
- **Gate 1 - tailnet identity.** The daemon binds its tailnet addresses only, never the
  LAN, and resolves every incoming connection through tailscaled's WhoIs. A caller it
  cannot name is refused.
- **Gate 2 - allowlist or pairing code.** A resolved node is admitted only if it is on the
  device allowlist or presents a valid 6-digit code (`crypto/rand`, single-use, 5-minute
  expiry, 10-attempt budget) shown at the computer.

Revoking a device with `portholed revoke` drops its live sockets immediately, whether or
not the phone cooperates.

**Preview shares** (0.8.0) reuse the same model rather than adding a new surface. A share
is a reverse proxy to a loopback dev server, bound to exactly the addresses the daemon
itself is bound to (so never the LAN), and every request passes gates 1 and 2 again:
WhoIs, with the address-to-node answer cached for one minute, and then the allowlist on
every single request, so a revoke is honoured by the very next one. Gate 0 does not apply
as such - a browser on the phone is the intended caller - but its concern is met another
way: the proxy answers only to a `Host` that names the computer on the share's port (its
tailnet addresses, its MagicDNS name, and that name's first label), and returns 421 to
anything else. The phone opens a share at the computer's tailnet address. Without that, a
page open in the phone's browser could rebind its own hostname to that address and read
the dev server as its own origin, since the proxy presents `Host` as localhost upstream.
The share reaches only what is listed: loopback listeners owned by the daemon's own user,
with the ephemeral range and known tooling hidden. A share whose server has exited is
closed on the next listing. Shares die with the daemon; nothing is written into tailscaled.

**Pictures by path** (0.38.0). The feed shows pictures the phone sent and ones Claude sent
by their path on the computer. The daemon serves such a path only if it is an image (png,
jpeg, webp, gif) of at most 8 MB under the user's home, after the path is cleaned and links
are resolved, so `..` or a link cannot reach outside it.

**The Claude account** (0.40.0). A paired phone can sign Claude Code on the computer out,
or in to another account, and restart running sessions. This is Claude Code's own
`claude auth login` and `logout`, run as the daemon's user: nothing a paired phone could not
already do from the terminal. Under the systemd service they run as a transient user unit of
their own, outside the daemon's sandbox, whose read-only filesystem would keep Claude Code
from writing its sign-in. The sign-in runs with no display and with every command that opens
a browser (`xdg-open`, `open`, `$BROWSER` and the rest) replaced by a stub, so nothing opens
on the computer, where a browser could finish the sign-in with whatever account it holds.
The phone gets the sign-in link the CLI prints for another device; a code is typed into the
waiting CLI only if it ends with that link's own `state`, and is kept nowhere. One sign-in
runs at a time, and one nobody finishes is ended after ten minutes. A restart takes only a
session id of the CLI's own form (a UUID), finds the CLI by the process ID Claude Code
registered (checked against its start time where Claude Code recorded one), and stops it
only if it was started by its tmux pane's own shell - so the pane stays, and nothing else is
stopped. It waits while the session is in a turn, a `!` command, a question or a permission
prompt, presses Escape once on a usage-limit wait, and types `claude --resume <id>` into that
shell only once it is idle again in the session's folder. A restart still waiting can be
called off, and one session is never restarted twice at once.

**The failsafe key** (0.27.0). Where Tailscale SSH does not serve the computer - a Mac,
whose Tailscale app has no SSH server - the failsafe signs in to the system's sshd with a key
of the phone's own. The phone makes an Ed25519 key; the 32-byte seed is stored encrypted with
AES-GCM under a key held in the Android Keystore, which never leaves it. On the phone's request
over its paired connection (gates 0-2), the daemon validates the public key (Ed25519 only, the
wire format checked) and writes one line to `~/.ssh/authorized_keys`:
`restrict,pty,from="<the phone's tailnet addresses>" ssh-ed25519 ... porthole:<node id>`. It
works only from that phone's own tailnet addresses (from tailscaled's WhoIs), gets a terminal
and no port, agent or X11 forwarding, and runs no `~/.ssh/rc`. The line goes when the phone
asks or is revoked, and every other line in the file is kept byte for byte, written by
atomic rename. This grants nothing a paired phone could not already do through Claude Code
and the terminal; what it adds is a way in that survives the daemon stopping. A test runs a
real sshd against the exact line, and the phone's sign-in code is tested against one too,
on an emulator with the real Keystore.

**Installs.** The app hands an APK to Android's installer from two places only. Porthole's
own updates come from GitHub releases over HTTPS, and Android refuses any update not
signed with the same key as the installed app. Builds of your other apps come from the
paired computer's `/builds/`, behind gates 1 and 2. Every install goes through Android's
own installer prompt; nothing installs silently. A paired computer can therefore offer the
phone any APK - pair only computers you control.

## The one finding: cross-site WebSocket hijacking (fixed)

The daemon originally accepted WebSocket upgrades with `InsecureSkipVerify: true` and no
origin check, on the reasoning that "the client is a native app, so same-origin checking
is meaningless." That reasoning was wrong, and the gap was real.

Both device gates are satisfied by the *device*, and a browser running on a paired device
**is** that device. So a malicious web page - opened in any browser on the paired phone -
could script `new WebSocket("ws://<daemon-tailnet-ip>:8737/ws")`, sail through both gates
on the phone's own tailnet identity, and then drive the daemon: enumerate sessions (with
their working directories), open a terminal, run commands, approve permission requests.
No pairing code needed, because the device is already allowlisted.

Demonstrated against the running daemon: a connection carrying a forged
`Origin: https://evil.example` returned `101 Switching Protocols`, received the full
session list, and reached the `pty.open` path. Fixed by Gate 0 - reject any request with a
non-empty `Origin`. Verified: the forged-origin connection now gets `403 forbidden`, the
real app (which sends no `Origin`, confirmed as `okhttp/4.12.0` with an empty header)
still connects, and `TestBrowserOriginIsRefused` fails if the gate is removed.

## Reviewed and sound

- **Arbitrary session ids** cannot traverse the filesystem: `findSession` matches the
  client's id against the actually-discovered session list, and the transcript path comes
  from discovery, never from the client.
- **The control socket** (`pair`, `devices`, `revoke`, hook delivery) is a unix socket at
  `0600`, owned by the user. Only that user - or root - can speak to it; it is never
  exposed on the network.
- **The pairing code** is `crypto/rand` over `[0, 10^6)`, compared in constant time,
  single-use, expiring, and attempt-bounded.
- **Cleartext to the daemon** is `ws://`, not `wss://`, and that is deliberate: every byte already crosses WireGuard with per-peer keys, so
  app-layer TLS would add an Android trust-store problem and buy nothing over a tailnet
  address. The Android cleartext permission is broader than the tailnet only because
  network-security-config scopes by hostname and cannot express the tailnet CGNAT range,
  which the app must accept as a raw IP.

## Updates and releases

- **The app updates from GitHub releases only.** It asks `api.github.com` for the latest
  release, accepts an APK only from `github.com` or `*.githubusercontent.com` over HTTPS,
  and refuses a redirect down to plain HTTP. None of that is the real guarantee: Android
  installs an update only when it is signed with the same key as the installed app, so a
  tampered or substituted APK is refused by the system whatever its source.
- **Builds of your projects** come from your own computer over the tailnet, through the
  same gates as everything else, and Android asks before installing each one.
- **Daemon binaries** on the release page are static, built with `-trimpath`, and listed in
  `SHA256SUMS`; `tools/install.sh --prebuilt` refuses a binary whose checksum does not
  match. Building from source needs only Go.

## Reporting

Found a vulnerability? Report it privately through the repository's **Security > Report a
vulnerability** page on GitHub, not in a public issue. Describe the class of problem and
how to reproduce it; you will get an answer there.
