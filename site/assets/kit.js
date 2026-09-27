/*
 * Porthole device kit. Draws the phone (the app's real screens) and the computer (tmux
 * with Claude Code), and plays scenes through them. See _kit/index.html for every
 * screen and scene, and SHELL.html for how a page includes this file.
 *
 *   <div class="pk-scene" data-scene="question"><noscript><img ...></noscript></div>
 *   <div class="pk-phone" data-screen="sessions"></div>
 *
 * More screens and scenes register from another file loaded after this one:
 *   PK.screen(id, params => html)        a phone screen (the app area, 360 px wide)
 *   PK.view(id, { phone: [screenId, params], over: [screenId, params] })
 *   PK.scene(id, { label, poster, computer, steps: [...] })
 */
(() => {
'use strict';
const PK = window.PK = window.PK || {};
const screens = PK.screens = {}, views = PK.views = {}, scenes = PK.scenes = {};
const Q = new URLSearchParams(location.search);
const RM = matchMedia('(prefers-reduced-motion: reduce)');
if (/^(light|dark)$/.test(Q.get('theme') || '')) document.documentElement.dataset.theme = Q.get('theme');

/* ------------------------------------------------------------ helpers --- */

const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const sp = n => `<i class="sp" style="--h:${n}px"></i>`;
// Icons are CSS masks in kit.css (.i-name): Material Icons outlined, and the tools' own marks.
const ic = (n, s = 22, c = '') => `<i class="ic i-${n} ${c}" style="--z:${s}px" aria-hidden="true"></i>`;

/* The ring: the connection state, and the mark. Shapes as Ring.kt draws them. */
// rv: how much of a live ring is drawn, from the top (the banner's download progress).
function ring(st = 'live', s = 20, w = 2, draw, rv = 1) {
  const r = (s - w) / 2, c = s / 2, C = 2 * Math.PI * r, f = a => (a * C / 360).toFixed(2);
  const o = (col, x = '') => `<circle cx="${c}" cy="${c}" r="${r}" fill="none" stroke="var(--${col})" stroke-width="${w}" stroke-linecap="round" ${x}/>`;
  const rot = a => `transform="rotate(${a} ${c} ${c})"`;
  const g = {
    live: o('ac', rv < 1 ? `stroke-dasharray="${f(360 * rv)} ${C.toFixed(2)}" ${rot(-90)}` : ''), mark: o('ac', draw ? `class="pk-draw" style="--c:${C.toFixed(2)}" ${rot(-90)}` : ''),
    needs: o('wa'), idle: o('fa'),
    connecting: o('ed') + `<g class="pk-rot">${o('ac', `stroke-dasharray="${f(38)} ${f(52)}"`)}</g>`,
    retrying: o('ed') + o('wa', `class="pk-breath" stroke-dasharray="${f(270)} ${f(90)}" ${rot(-90)}`),
    dropped: o('ba', `stroke-dasharray="${f(130)} ${f(50)}" ${rot(-60)}`),
  }[st];
  return `<svg class="ring" width="${s}" height="${s}" viewBox="0 0 ${s} ${s}" aria-hidden="true">${g}</svg>`;
}

/* The CLI spinner as Spinner() draws it: five stars and a dot, 140 ms a frame. */
const spokes = (n, len, rot, r = 8, sw = 1.8, dot = true) => {
  let d = '';
  for (let k = 0; k < n; k++) {
    const a = (rot + k * 360 / n) * Math.PI / 180;
    d += `M8 8L${(8 + r * len * Math.cos(a)).toFixed(2)} ${(8 + r * len * Math.sin(a)).toFixed(2)}`;
  }
  return (dot ? `<circle cx="8" cy="8" r="${n ? 1.08 : 1.98}"/>` : '') + (n ? `<path d="${d}" stroke-width="${sw}" stroke-linecap="round"/>` : '');
};
const SPIN = [[6, 1, 0], [8, .9, 0], [6, 1, 30], [4, .9, 0], [4, .6, 0], [0, 0, 0]].map(f => `<g>${spokes(...f)}</g>`).join('');
const spinner = (col = 'ac') => `<svg class="spin" width="16" height="16" viewBox="0 0 16 16" fill="var(--${col})" stroke="var(--${col})" aria-hidden="true">${SPIN}</svg>`;
const star = () => `<svg class="spin" width="14" height="14" viewBox="1 1 14 14" stroke="var(--fa)" aria-hidden="true">${spokes(6, 1, 0, 7, 1.6, false)}</svg>`;

/* Ticking numbers: data-tick is the value when drawn; while a scene plays it counts on (f 'd' counts down). */
const fmt = (v, f) => {
  v = Math.max(0, v);
  const m = Math.floor(v / 60), s = v % 60, h = Math.floor(m / 60);
  if (f === 'ms') return h ? `${h}:${String(m % 60).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
  if (f === 's') return v < 60 ? `${v}s` : `${m}m ${s}s`;
  return String(v);
};
const tick = (v, f = 's') => `<span data-tick="${v}" data-tf="${f}">${fmt(+v, f)}</span>`;

/* ---------------------------------------------------- app components --- */

const pri = (t, k = '', off) => `<div class="b-pri${off ? ' off' : ''}"${k ? ` data-k="${k}"` : ''}>${t}</div>`;
const gh = (t, k = '') => `<div class="b-gh"${k ? ` data-k="${k}"` : ''}>${t}</div>`;
const icot = (n, c = '') => `<div class="icot ${c}">${ic(n)}</div>`;
const cmd = (c, label) => `${label ? `<span class="ty-m k-fa lbl">${label}</span>` : ''}<div class="cmdb"><p class="ty-mo">${esc(c).replace(/(\w)\//g, '$1<wbr>/')}</p><span class="cp ty-m">copy</span></div>`;
const mark = (n, s = 44, col = 'tx') => `<div class="mark k-${col}" style="width:${s}px;height:${s}px">${ic(n, s / 2)}</div>`;
const pill = (t, on, k = '') => `<span class="pill${on ? ' on' : ''}"${k ? ` data-k="${k}"` : ''}><span>${t}</span></span>`;
const dots = (on, n = 7) => `<div class="dots">${Array.from({ length: n }, (_, i) => `<i${i + 1 === on ? ' class="on"' : ''}></i>`).join('')}</div>`;
const ap = (d, html, x = '') => `<div class="ap" style="--d:${d}ms;${x}">${html}</div>`;
const md = s => s.split('\n\n').map(p => `<p>${esc(p).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')}</p>`).join('');
const wordmark = () => `<div class="row ty-d">P<span style="padding:0 1px">${ring('mark', 18.48, 2.38)}</span>rthole</div>`;

/* OnboardingFrame: back arrow and dots, a scrolling body, an optional bottom bar. */
const frame = (step, body, bot = '', scroll = 0) =>
  `<div class="ob-top">${icot('back')}<div class="f1"></div>${step ? dots(step) : ''}</div>` +
  `<div class="ob-body"><div data-scroll style="transform:translateY(${-scroll}px)">${body}</div></div>` +
  (bot ? `<div class="ob-bot">${bot}</div>` : '');

const toolRow = (icon, name, detail, d) => ap(d, `<div class="toolrow">${mark(icon)}<div class="f1"><p class="ty-r">${name}</p><p class="ty-s k-mu">${detail}</p></div></div>`);

const toolIcon = t => /^Ran\b/.test(t) ? 'terminal' : /^Read /.test(t) ? 'description' : /^(Edited|Wrote) /.test(t) ? 'edit'
  : /^(Searched the web|Fetched)/.test(t) ? 'language' : /^(Searched|Globbed) /.test(t) ? 'search' : 'build';

/*
 * Feed rows, as FeedRowView draws them. Each row is an array:
 *   ['u', text, time]            your prompt       ['a', markdown]           a reply
 *   ['t', text, took, dim]       a tool call ('…' = running)
 *   ['r', text, metric, failed]  its result        ['n', text, time]         a finished turn
 *   ['q', header, question, [[label, description], ...], answered]           a question, as recorded
 *   ['s', text]                  the since-you-left rule
 *   ['c', verb]                  composing          ['e', text]               an event
 */
function feedRow(r, i) {
  const k = `data-k="r${i}${r[0]}"`;
  switch (r[0]) {
    case 'u': return `<div class="fu" ${k}><div class="bub ty-b">${esc(r[1])}</div>${r[2] ? `<p class="ty-m k-fa">${r[2]}</p>` : ''}</div>`;
    case 'a': return `<div class="fa" ${k}><div class="md">${md(r[1])}</div></div>`;
    case 't': return `<div class="ft${r[2] === '…' ? ' pend' : r[3] ? ' dim' : ''}" ${k}>${ic(toolIcon(r[1]), 18)}<p class="ty-s f1 cl2">${esc(r[1])}</p>${r[2] === '…' ? spinner() : r[2] ? `<span class="ty-mo k-fa">${r[2]}</span>` : ''}</div>`;
    case 'r': return `<div class="fr" ${k}>${ic(r[3] ? 'close' : 'check', 18, r[3] ? 'k-ba' : 'k-ok')}<p class="ty-s f1 ${r[4] ? 'k-fa' : 'k-mu'}">${esc(r[1])}</p>${r[2] ? `<span class="ty-mo el ${r[3] ? 'k-ba' : 'k-fa'}" style="max-width:200px">${esc(r[2])}</span>` : ''}</div>`;
    case 'n': return `<div class="fn" ${k}>${star()}<p class="ty-m k-fa">${r[1]}${r[2] ? ` · done ${r[2]}` : ''}</p></div>`;
    case 's': return `<div class="fs" ${k}><i></i><p class="ty-m k-ac">${r[1]}</p><i></i></div>`;
    case 'c': return `<div class="row" style="gap:8px;padding:2px 0" ${k}>${spinner()}<p class="ty-s k-mu">${r[1]}…</p></div>`;
    case 'e': return `<div class="fn" ${k}><i style="width:5px;height:5px;border-radius:3px;background:var(--fa)"></i><p class="ty-m k-fa">${r[1]}</p></div>`;
    case 'q': return `<div class="fq" ${k}><p class="ty-m ${r[4] ? 'k-fa' : 'k-ac'}">${r[4] ? 'Claude asked' : 'Claude is asking'}</p>${r[1] ? `<p class="ty-r">${r[1]}</p>` : ''}<p class="ty-b">${r[2]}</p>${r[3].map(([l, d], j) => `<p class="ty-s k-mu">${j + 1}. ${l}${d ? ` · ${d}` : ''}</p>`).join('')}</div>`;
  }
  return '';
}

/* ------------------------------------------------------------ screens --- */

const S = screens;
// Registering after load (kit-more.js) mounts whatever was waiting for that id.
let ready = false;
const pending = id => ready && document.querySelectorAll(`.pk-phone[data-screen="${id}"]:not(.pk-on)`).forEach(el => mountPhone(el));
PK.screen = (id, fn) => { screens[id] = fn; pending(id); };
PK.view = (id, v) => { views[id] = v; pending(id); };

S.welcome = () => `<div class="center">${ring('mark', 96, 5, 1)}${sp(28)}${ap(300, wordmark())}${sp(28)}` +
  ap(380, `<p class="ty-t">Run Claude Code on your computer, from your phone.</p>`, 'width:100%') + sp(12) +
  ap(440, `<p class="ty-b k-mu">Over your own Tailscale network. Nothing passes through anyone else's server.</p>`, 'width:100%') + sp(40) +
  ap(520, pri('Get started', 'go') + sp(12) + gh('How it works'), 'width:100%') + `</div>`;

// Mac or Linux: chosen on "What you'll need"; the setup screen shows that computer's steps only.
const OS = { mac: ['Mac', 'macOS 13 or later'], linux: ['Linux', 'with systemd'] };
const osCard = (k, on) => `<div class="oscard${on ? ' on' : ''}" data-k="${k}"><p class="ty-r">${OS[k][0]}</p><p class="ty-s k-mu">${OS[k][1]}</p></div>`;
const osSwitch = os => `<div class="seg os${os === 'linux' ? ' t' : ''}"><span${os === 'linux' ? '' : ' class="on"'}>Mac</span><span${os === 'linux' ? ' class="on"' : ''}>Linux</span></div>`;
const DAEMON = {
  mac: 'The small companion service for the Mac, from Homebrew. It listens on your tailnet only, never the internet.',
  linux: 'The small companion service for the computer, a systemd user service. It listens on your tailnet only, never the internet.',
  '': 'The small companion service for the computer. It listens on your tailnet only, never the internet.',
};
S.needs = (p = {}) => frame(1, `<p class="ty-d">What you'll need</p>${sp(12)}<p class="ty-b k-mu">Three things, all on your side. There is no account to create, because there is no service to sign into.</p>${sp(20)}` +
  `<p class="ty-s k-mu">Which computer runs Claude Code?</p>${sp(8)}<div class="oscards">${osCard('mac', p.os === 'mac')}${osCard('linux', p.os === 'linux')}</div>${sp(20)}<div class="col" style="gap:10px">` +
  toolRow('tailscale', 'Tailscale', 'On this phone and on the computer, signed into the same tailnet. Free for personal use.', 0) +
  toolRow('claude', 'Claude Code, inside tmux', 'Running on the computer. Start it with porthole and it lands in tmux for you; Porthole reads its transcript and attaches to that window.', 40) +
  toolRow('tmux', 'portholed', DAEMON[p.os || ''], 80) + `</div>`,
  pri(p.os ? 'Continue' : 'Choose the computer', 'go', !p.os));

S.tailscale = () => frame(2, `<div class="row" style="gap:14px">${mark('tailscale', 56)}<div class="row k-ok" style="gap:6px">${ic('check', 18)}<p class="ty-s">Installed on this phone</p></div></div>${sp(20)}` +
  `<p class="ty-d">Tailscale is installed</p>${sp(12)}<p class="ty-b k-mu">Porthole reaches your computer through Tailscale's private network. Make sure it is connected and signed into the same account as the computer, then continue.</p>${sp(20)}` +
  `<p class="ty-s k-fa">The computer needs Tailscale too, on the same account. The next screen shows how, for a Mac or for Linux.</p>`,
  pri('Continue', 'go') + gh("Open Tailscale to check it's connected"));

const setupStep = (n, icon, title, body) => ap((n - 1) * 40, `<div class="step"><div class="row" style="gap:12px">${mark(icon, 36, 'mu')}<p class="ty-m k-ac">${n}</p><p class="ty-r">${title}</p></div>${sp(10)}${body}</div>`);
S.setup = (p = {}) => {
  const mac = p.os !== 'linux';
  const ts = mac
    ? `<p class="ty-s k-mu">Install the Tailscale app from the Mac App Store or tailscale.com, and sign in to the same account as this phone.</p>${sp(8)}<p class="ty-s k-fa">Turn on Remote Login in System Settings &gt; General &gt; Sharing. It is this phone's way back in if the daemon ever stops; once paired, the phone adds its own key for it.</p>`
    : `<p class="ty-s k-mu">Install Tailscale, then sign in with Tailscale SSH on. SSH is this phone's way back in if the daemon ever stops.</p>${sp(10)}${cmd('sudo tailscale up --ssh')}${sp(8)}<p class="ty-s k-fa">Already signed in? sudo tailscale set --ssh</p>`;
  const inst = mac
    ? `<p class="ty-s k-mu">With Homebrew. portholed setup starts it at every login, where it can run commands as you, and adds a Claude Code hook for remote approvals.</p>${sp(10)}${cmd('brew install shrimpscript/tap/porthole')}${sp(8)}${cmd('portholed setup')}${sp(10)}<p class="ty-s k-fa">It keeps the Mac awake while a session works or this phone is connected, on the power adapter. Allow it to record the screen when macOS asks, for screenshots.</p>`
    : `<p class="ty-s k-mu">Clone the repository and run the installer. Read it first: it installs a user service that can run commands as you, and a Claude Code hook for remote approvals.</p>${sp(10)}${cmd('git clone https://github.com/ShrimpScript/porthole')}${sp(8)}${cmd('cd porthole && ./tools/install.sh')}${sp(8)}${cmd('sudo loginctl enable-linger $USER', 'So it keeps running after you log out:')}${sp(10)}<p class="ty-s k-fa">Homebrew works on Linux too: brew install shrimpscript/tap/porthole, then portholed setup.</p>`;
  return frame(3, `<p class="ty-d">Set up the computer</p>${sp(12)}<p class="ty-b k-mu">Four steps at the keyboard, once. Everything here is copyable.</p>${sp(16)}${osSwitch(p.os)}${sp(16)}` +
    setupStep(1, 'tailscale', 'Tailscale on the computer', ts) +
    setupStep(2, 'github', 'Install portholed', inst) +
    setupStep(3, 'claude', 'Start Claude Code with porthole', `<p class="ty-s k-mu">In your project's folder, run porthole where you would run claude. It starts Claude Code inside tmux, so the terminal on your phone is the same screen as at the desk; running it again in the same folder brings that session back. Already inside tmux? Plain claude works too.</p>${sp(10)}${cmd('porthole')}`) +
    setupStep(4, 'tmux', 'Get a pairing code', `<p class="ty-s k-mu">It prints a 6-digit code that is good for five minutes. The next screens ask for it.</p>${sp(10)}${cmd('portholed pair')}`),
    pri('Continue', 'go') + gh('Already set up'), p.scroll);
};

S.connect = (p = {}) => frame(4, `<p class="ty-d">Which computer?</p>${sp(12)}<p class="ty-b k-mu">Its name on your tailnet, or its 100.x address. Porthole checks the daemon is actually answering before asking you for a code.</p>${sp(24)}` +
  `<div class="field"><p class="ty-mo ${p.host ? '' : 'k-fa'}">${p.host || 'my-pc  or  100.x.y.z'}</p></div>${sp(12)}` +
  (p.found ? `<div class="row" style="gap:10px" data-k="found">${ring('live', 16)}<p class="ty-s k-ok">Found Porthole on ${p.host}</p></div>${sp(16)}${pri('Continue', 'go')}`
    : pri(p.checking ? 'Checking…' : 'Check connection', 'check', !p.host || p.checking)) +
  `${sp(16)}${cmd('portholed serve', 'On the computer, this prints the address it listens on:')}`);

const SCOPES = [['›_', 'Run commands', 'As your user, with full shell access'], ['◧', 'Read and write files', 'Anywhere your user can'],
  ['✓', 'Approve tool calls', 'Decide permission prompts remotely'], ['≡', 'Read session history', 'Transcripts of your Claude Code sessions'],
  ['▣', 'Capture your screen', 'Screenshots and short clips of the desktop, only when you ask'],
  ['⇢', 'Share a dev server', "Open a site running on the computer in this phone's browser, over the tailnet, only when you ask"],
  ['▶', 'Start Claude Code', 'In a project it has been used in before, only when you ask']];
S.consent = (p = {}) => `<div class="col" style="flex:1;min-height:0;padding:24px">${sp(24)}<p class="ty-d">Authorize Porthole</p>${sp(12)}<p class="ty-b k-mu">This phone will be able to do the following on ${p.host || 'workstation'}:</p>${sp(12)}` +
  `<div class="f1 scopes">${SCOPES.map(([g, n, a], i) => ap(i * 40, `<div class="scope"><div class="mark">${g}</div><div class="f1"><p class="ty-r">${n}</p><p class="ty-s k-mu">${a}</p></div></div>`)).join('')}</div>` +
  `<p class="ty-s k-fa">Porthole installs nothing on the computer. These are what portholed - which you installed yourself, at the keyboard - will let this phone do. Revoke it any time with portholed revoke.</p>${sp(16)}` +
  `<div class="row btns" style="gap:12px">${gh('Cancel')}${pri('Authorize', 'go')}</div></div>`;

S.pair = (p = {}) => {
  const code = p.code || '';
  return frame(6, `<p class="ty-d">Pair with ${p.host || 'workstation'}</p>${sp(12)}<p class="ty-b k-mu">On the computer, run the command below. It prints a QR to scan and a 6-digit code to type.</p>${sp(16)}${cmd('portholed pair')}${sp(16)}${gh('Scan the QR')}${sp(24)}` +
    `<p class="ty-s k-mu">Or the pairing code</p>${sp(10)}<div class="cells">${[0, 1, 2, 3, 4, 5].map(i => `<span${i === code.length ? ' class="on"' : ''}>${code[i] || ''}</span>`).join('')}</div>${sp(16)}` +
    `<p class="ty-s k-fa">The code expires after five minutes. Run the command again for a new one.</p>`,
    pri(p.pairing ? 'Pairing…' : 'Pair', 'go', code.length < 6 || p.pairing));
};

S.tour = () => `<div class="row" style="padding:12px 12px 0 24px"><p class="ty-s k-mu f1">How Porthole works</p><p class="ty-s k-ac" style="padding:12px" data-k="skip">Skip</p></div>` +
  `<div class="f1" style="padding:12px 24px;overflow:hidden"><p class="ty-d">The feed</p>${sp(12)}<p class="ty-b k-mu">Everything Claude does, as it happens, read from its own transcript: your prompts, its replies, each tool call and its result. Long replies fold; tap to unfold. Tap a tool row for the full output.</p>${sp(24)}` +
  `<div class="tour-sample">${[['u', 'Run the tests and fix whatever fails.'], ['t', 'Ran cargo test'], ['r', 'done', '41 lines'], ['a', 'Two failures in `solver.rs`, both the same off-by-one. Fixed and **all 41 pass**.']].map(feedRow).join('')}<p class="ty-m k-fa">Example</p></div></div>` +
  `<div class="row" style="padding:16px 24px">${dots(1, 4).replace('class="dots"', 'class="dots f1"')}<div style="width:140px">${pri('Next', 'go')}</div></div>`;

/*
 * The sessions list. p.groups: [[label, [row, ...]], ...]; a row is
 * { k, t: title, r: ring state, m: meta (html), c: meta colour, s: spinner, a: age, u: unseen }.
 * p.ban: an update banner, { own, v, have } or { name, v } or { name, v, pct }.
 */
const banner = b => `<div class="ban" data-k="ban"><div class="row" style="gap:12px">${ic('download', 22, 'k-mu')}<div class="f1"><p class="ty-r">${b.own ? `Porthole ${b.v} is out` : `${b.name} ${b.v} is on the computer`}</p>` +
  `<p class="ty-m k-fa">${b.pct != null ? `Downloading · ${b.pct}%` : b.own ? `You have ${b.have}. Downloads from GitHub, then Android asks to update.` : 'Downloads from your computer, then Android asks to install or update it.'}</p></div></div>` +
  (b.pct != null ? `${sp(10)}<div class="row" style="gap:10px">${ring('live', 18, 2, 0, b.pct / 100)}<p class="ty-m k-fa">${b.pct}% of the download</p></div>`
    : `${sp(6)}<div class="row" style="justify-content:flex-end;gap:8px">${pill('Not now', 0, 'later')}${pill(b.own ? 'Update' : 'Install', 1, 'get')}</div>`) + `</div>`;
const srow = w => `<div class="srow" data-k="${w.k}"><div class="srow-in">${ring(w.r, 18)}<div class="f1"><div class="row" style="gap:8px"><p class="ty-r el">${esc(w.t)}</p>${w.u ? '<i class="unseen"></i>' : ''}</div>` +
  `<div class="row">${w.s ? `<span style="margin-right:2px">${spinner()}</span>` : ''}<p class="ty-s el k-${w.c || 'mu'}">${w.m}</p></div></div><p class="ty-m k-fa">${w.a || ''}</p></div><div class="hr"></div></div>`;
S.sessions = (p = {}) => {
  const g = p.groups || SESS;
  const n = g.reduce((a, x) => a + x[1].length, 0);
  return `<div class="sl-h">${ring(p.ring || 'live', 20)}<div class="f1"><p class="ty-t el">${p.machine || 'workstation'}</p><p class="ty-m k-fa">${p.sub || `${n} session${n === 1 ? '' : 's'} · connected`}</p></div>${icot('refresh')}${icot('settings')}</div>` +
    (p.ban ? banner(p.ban) : '') + `<div class="f1" style="overflow:hidden">${g.map(([l, rows]) => `<p class="ty-s k-mu sec">${l}</p>${rows.map(srow).join('')}`).join('')}</div>`;
};

/*
 * A session: header, Feed/Terminal, the feed, then what sits under it.
 * p = { t: title, b: branch line, r: ring, chip, globe, rows, strip: { x, s, tok },
 *       q: { h, x, o: [[label, desc], ...] }, quick, term (the Terminal tab selected) }
 */
S.session = (p = {}) => {
  const t = p.t || 'Fix flaky login test';
  const st = p.strip;
  return `<div class="ss-h">${icot('back')}${ring(p.r || 'live', 18)}<div class="f1"><p class="ty-r el">${esc(t)}</p><p class="ty-m k-fa el">${p.b || 'main · tmux work'}</p></div>` +
    `${p.globe ? icot('language') : ''}<span class="chip">${p.chip || 'Opus 5 · 4%'}</span></div>` +
    `<div class="seg${p.term ? ' t' : ''}"><span${p.term ? '' : ' class="on"'}>Feed</span><span${p.term ? ' class="on"' : ''}>Terminal</span></div>` +
    `<div class="feed"><div class="feed-in">${(p.rows || []).map(feedRow).join('')}</div></div>` +
    (st ? `<div class="strip" data-k="strip">${spinner()}<div class="f1"><p class="ty-s">${st.x}</p>${st.s != null || st.tok ? `<p class="ty-m k-fa">${[st.s != null ? tick(st.s) : '', st.tok].filter(Boolean).join(' · ')}</p>` : ''}</div>${gh('Interrupt')}</div>` : '') +
    (p.q ? `<div class="lq" data-k="lq"><p class="ty-m k-ac">Claude is asking you</p>${p.q.h ? `<p class="ty-r">${p.q.h}</p>` : ''}<p class="ty-b">${p.q.x}</p>` +
      p.q.o.map(([l, d], i) => `<div class="opt" data-k="o${i + 1}"><p class="ty-b">${i + 1}. ${l}</p>${d ? `<p class="ty-s k-mu">${d}</p>` : ''}</div>`).join('') +
      `<div class="row">${pill('Type something')}</div></div>` : '') +
    (p.quick ? `<div class="qr-row" data-k="quick">${['Continue', 'Yes', 'No', 'Looks good'].map(x => pill(x)).join('')}${icot('edit', 'k-fa')}</div>` : '') +
    `<div class="comp"><div class="rb"><span class="slash">/</span></div><div class="in"><p class="ty-b k-fa el">Message ${esc(t)}</p></div><div class="rb k-fa">${ic('send', 20)}</div></div>`;
};

/* The approval card, as an overlay: p = { title, cmd, cwd, left }. */
S.approval = (p = {}) => `<div class="appr" data-k="appr"><div class="row" style="gap:10px">${ic('warning', 20, 'k-wa')}<p class="ty-r">${p.title || 'Claude wants to run a command'}</p></div>` +
  `<div class="cmdx"><p class="ty-mo">${esc(p.cmd || 'npm publish --access public')}</p>${sp(8)}<p class="ty-m k-fa">in ${p.cwd || '~/code/shop-sdk'}</p></div>` +
  `<p class="ty-s k-mu">Waiting · ${tick(p.left == null ? 90 : p.left, 'd')}s left</p><div class="btns">${gh('Deny', 'deny')}${pri('Allow', 'allow')}</div></div>`;

/* ------------------------------------------------------- sample data --- */

const ROW = {
  dark: { k: 'dark', t: 'Add dark mode to settings', r: 'needs', c: 'ac', a: '1m', m: 'asking you: Which theme should new users get? · main · live · tmux work · Opus 5' },
  flaky: (s, doing = 'Bash: npm test -- login') => ({ k: 'flaky', t: 'Fix flaky login test', r: 'connecting', c: 'tx', s: 1, a: 'now', m: `${tick(s, 'ms')} · ${doing} · fix/login-race · live · tmux work · Opus 5` }),
  flakyDone: { k: 'flaky', t: 'Fix flaky login test', r: 'live', u: 1, a: 'now', m: 'fix/login-race · live · tmux work · Opus 5' },
  pg: { k: 'pg', t: 'Migrate to Postgres 16', r: 'live', a: '22m', m: 'db-16 · live · tmux work · Sonnet 5' },
  inv: { k: 'inv', t: 'Refactor invoice export', r: 'idle', a: '2d', m: 'main · idle · Opus 5' },
};
const SESS = [['Needs you', [ROW.dark]], ['Live', [ROW.flaky(42), ROW.pg]], ['Recent', [ROW.inv]]];

const FLAKY = [
  ['u', 'The login test fails about one run in five. Find out why and fix it.', '10:25'],
  ['t', 'Ran npm test -- login', '7.8s', 1], ['r', 'failed', 'FAIL tests/login.test.ts', 1],
  ['t', 'Read ~/code/shop-api/src/auth/session.ts', '0.1s', 1], ['r', 'done', '88 lines'],
  ['a', 'The test awaits `login()` but not the session write, so the redirect sometimes wins the race. **Fix:** await `saveSession()` before navigating.'],
  ['t', 'Edited ~/code/shop-api/src/auth/session.ts', '0.2s', 1], ['r', 'done', '61 chars'],
  ['t', 'Ran npm test -- login --repeat 20', '41s'], ['r', 'done', '24 lines'],
  ['a', 'Fixed. The login test passed 20 runs in a row.'],
  ['n', 'Worked for 1m 12s', '10:26'],
];

const DARK_Q = { h: 'Default', x: 'Which theme should new users get?', o: [['Follow the system', 'Matches the phone or computer setting'], ['Always light', 'Dark mode stays opt-in']] };
const DARK0 = [
  ['u', 'Add a dark mode toggle to the settings page.', '10:24'],
  ['a', "I'll check how the settings page stores preferences first."],
  ['t', 'Read ~/code/shop-web/src/settings/Settings.tsx', '0.1s', 1], ['r', 'done', '142 lines'],
  ['t', 'Searched prefers-color-scheme', '0.4s'], ['r', 'done', '2 lines'],
];
const DARK_ASKED = ['q', 'Default', DARK_Q.x, DARK_Q.o, 1];
const DARK_SESSION = { t: 'Add dark mode to settings', b: 'main · tmux work', chip: 'Opus 5 · 6%' };

const SDK = { t: 'Release shop-sdk 2.3.0', b: 'main · tmux api', chip: 'Opus 5 · 3%' };
const SDK0 = [
  ['u', 'Bump the version to 2.3.0, run the tests, then publish it.', '10:29'],
  ['t', 'Edited ~/code/shop-sdk/package.json', '0.2s', 1], ['r', 'done', '58 chars'],
  ['t', 'Ran npm test', '6.4s', 1], ['r', 'done', '9 lines'],
];

PK.view('session', { phone: ['session', { rows: FLAKY, quick: 1, b: 'fix/login-race · tmux work' }] });
PK.view('session-working', { phone: ['session', { ...DARK_SESSION, rows: [...DARK0, ['c', 'Pondering']], strip: { x: 'Pondering…', s: 8, tok: '1.1k tokens' } }] });
PK.view('session-question', { phone: ['session', { ...DARK_SESSION, rows: DARK0, strip: { x: 'Waiting for your answer', s: 41 }, q: DARK_Q }] });
PK.view('approval', { phone: ['session', { ...SDK, rows: [...SDK0, ['t', 'Ran npm publish --access public', '…']], strip: { x: 'Running Bash…', s: 52 } }], over: ['approval', { left: 74 }] });
PK.view('pair', { phone: ['pair', { code: '482' }] });
PK.view('connect', { phone: ['connect', { host: 'workstation', found: 1 }] });

/* ----------------------------------------------------------- terminal --- */

/*
 * Terminal lines are strings with {x:text} spans, x being one or more of:
 *   d dim  r rule  o Claude orange  g green  e red  b blue  p pink  v purple  y yellow
 *   a accent  w white  B bold  i italic  u a prompt's band
 * and three tokens: {spin} the CLI spinner, {cur} the cursor, {tick:42,s} a ticking number.
 * A line can also be { type: 'command', pre: '{a:~} $ ' }: typed, one cell at a time.
 */
const TSP = `<span class="tsp">${'✻✽✶✳✢·'.split('').map(c => `<b>${c}</b>`).join('')}</span>`;
// Block elements are drawn as quadrants, so a QR code or the CLI's mascot has no seams between cells.
const Q4 = { '█': 15, '▀': 12, '▄': 3, '▌': 10, '▐': 5, '▛': 14, '▜': 13, '▙': 11, '▟': 7, '▘': 8, '▝': 4, '▖': 2, '▗': 1, '▞': 6, '▚': 9, ' ': 0 };
const blocks = run => {
  let d = '';
  [...run].forEach((c, x) => [8, 4, 2, 1].forEach((b, q) => { if (Q4[c] & b) d += `M${x * 2 + q % 2} ${q >> 1}h1v1h-1z`; }));
  return `<svg class="blk" style="width:${run.length}ch" viewBox="0 0 ${run.length * 2} 2" preserveAspectRatio="none" aria-hidden="true"><path d="${d}"/></svg>`;
};
const tline = s => {
  const keep = [], put = h => `\u0001${keep.push(h) - 1}\u0002`;
  return esc(s)
    .replace(/[▀-▟](?:[▀-▟ ]*[▀-▟])?/g, m => put(blocks(m)))
    .replace(/\{tick:([^}]*)\}/g, (m, v) => put(tick(...v.split(','))))
    .replace(/\{(\w+)(?::([^}]*))?\}/g, (m, k, v = '') => k === 'spin' ? TSP : k === 'cur' ? '<span class="cur"></span>'
      : `<span class="${k.split('').map(c => 't-' + c).join(' ')}">${v}</span>`)
    .replace(/\u0001(\d+)\u0002/g, (m, n) => keep[n]);
};
const lineHtml = l => typeof l === 'string' ? tline(l) : `${tline(l.pre || '')}<span class="ty" style="--n:${l.type.length}">${esc(l.type)} </span>`;

const rule = (n = 88) => `{r:${'─'.repeat(n)}}`;
const T = PK.t = {
  rule,
  banner: (model, cwd) => [' {o:▐▛███▜▌}   {B:Claude Code} {d:v2.1.270}', `{o:▝▜█████▛▘}  {d:${model} · Claude Pro}`, `  {o:▘▘ ▝▝}    {d:${cwd}}`],
  user: s => `{u:❯ ${s}}`,
  say: s => `● ${s}`,
  tool: (name, arg, pending) => `${pending ? '{d:●}' : '{g:●}'} {B:${name}}(${arg})`,
  res: s => `  {d:⎿}  ${s}`,
  spin: (verb, meta) => `{spin} {o:${verb}…} {d:(${meta})}`,
  done: s => `{d:✻ ${s}}`,
  input: (n = 88, mode = '{v:⏵⏵ accept edits on} {d:(shift+tab to cycle)}') => [rule(n), '❯ {cur}', rule(n), `  ${mode}`],
  sh: (cwd = '~') => `{g:${cwd}} $ `,
};

/* ------------------------------------------------------------- frames --- */

const SB = '<span class="pk-sb-i"><i class="sb-w"></i><i class="sb-s"></i><i class="sb-b"></i></span>';
const PHONE = `<div class="pk-fit" style="--nw:380;--nh:820"><div class="pk-native"><div class="pk-dev"><div class="pk-scr"><div class="pk-sb"><span>10:24</span>${SB}</div><i class="pk-cam"></i><div class="pk-app"></div><i class="pk-gp"></i></div></div></div></div>`;
const WINC = '<i class="wc-min"></i><i class="wc-max"></i><i class="wc-x"></i>';
const COMPUTER = `<div class="pk-fit" style="--nw:664;--nh:544"><div class="pk-native"><div class="pk-win"><div class="pk-tb"><span class="pk-tbt"></span><span class="pk-tbc">${WINC}</span></div><div class="pk-term"><div class="pk-panes"></div><div class="pk-tmux"><span></span><span></span></div></div></div></div></div>`;

const fits = new Set();
const ro = 'ResizeObserver' in window ? new ResizeObserver(es => es.forEach(e => fitOne(e.target))) : null;
function fitOne(el) {
  const w = el.clientWidth;
  if (w) el.firstElementChild.style.setProperty('--s', (w / +el.style.getPropertyValue('--nw')).toFixed(4));
}
function fit(el) { fitOne(el); if (ro) ro.observe(el); fits.add(el); }

/* Phone and computer controllers: render a state, animating the change or not. */
function phoneCtl(host) {
  host.insertAdjacentHTML('beforeend', PHONE);
  const f = host.lastElementChild, app = f.querySelector('.pk-app');
  fit(f);
  let id = null, key = null, okey = null, ov = null, route = null, scroll = 0, tapEl = null, tapK = null;
  return {
    root: f,
    render(s, anim, dir) {
      const [sid, p = {}] = s.phone || ['welcome'];
      const k = JSON.stringify(s.phone);
      if (k !== key) {
        const html = (screens[sid] || (() => ''))(p);
        if (sid === id && route) {
          route.classList.remove('run');
          const old = new Set([...route.querySelectorAll('[data-k]')].map(e => e.dataset.k));
          route.innerHTML = html;
          if (anim) route.querySelectorAll('[data-k]').forEach(e => { if (!old.has(e.dataset.k)) e.classList.add('pk-in'); });
        } else {
          const r = document.createElement('div');
          r.className = 'pk-route';
          r.innerHTML = html;
          app.insertBefore(r, ov);
          if (route) {
            const o = route;
            if (anim) { r.classList.add('run', dir < 0 ? 'in-b' : 'in-f'); o.classList.add(dir < 0 ? 'out-b' : 'out-f'); setTimeout(() => o.remove(), 150); }
            else o.remove();
          } else if (anim) r.classList.add('run');
          route = r; scroll = 0;
        }
        id = sid; key = k;
        const sc = route.querySelector('[data-scroll]');
        if (sc && anim && scroll !== (p.scroll || 0)) {
          sc.style.transform = `translateY(${-scroll}px)`;
          sc.getBoundingClientRect();
          sc.style.transition = 'transform 700ms var(--pk-standard)';
          sc.style.transform = `translateY(${-(p.scroll || 0)}px)`;
        }
        scroll = p.scroll || 0;
      }
      const ok = JSON.stringify(s.over || null);
      if (ok !== okey) {
        okey = ok;
        if (ov) {
          const o = ov; ov = null;
          if (anim) { o.className = 'pk-ov out'; setTimeout(() => o.remove(), 150); } else o.remove();
        }
        if (s.over) {
          ov = document.createElement('div');
          ov.className = 'pk-ov' + (anim ? ' in' : '');
          ov.innerHTML = screens[s.over[0]](s.over[1] || {});
          app.appendChild(ov);
        }
      }
      if (s.tap === tapK && tapEl) return;
      tapK = s.tap;
      if (tapEl) { tapEl.remove(); tapEl = null; }
      const t = s.tap && (ov || route).querySelector(s.tap);
      if (t) {
        // Layout offsets, not screen rectangles: a screen still sliding in has its final place.
        let x = t.offsetWidth / 2, y = t.offsetHeight / 2;
        for (let e = t; e && e !== app; e = e.offsetParent) { x += e.offsetLeft; y += e.offsetTop; }
        // Offsets ignore the transform a scrolled screen uses; take its scroll off.
        if (!ov && t.closest('[data-scroll]')) y -= p.scroll || 0;
        tapEl = document.createElement('i');
        tapEl.className = 'pk-tap' + (anim ? ' go' : '');
        tapEl.style.left = x + 'px';
        tapEl.style.top = y + 'px';
        app.appendChild(tapEl);
        if (anim) { setTimeout(() => t.classList.add('pk-press'), 200); setTimeout(() => t.classList.remove('pk-press'), 380); }
      }
    },
  };
}

function computerCtl(host) {
  host.insertAdjacentHTML('beforeend', COMPUTER);
  const f = host.lastElementChild, panes = f.querySelector('.pk-panes'), bar = f.querySelector('.pk-tmux'), title = f.querySelector('.pk-tbt');
  fit(f);
  let kind = null, keys = [], tk = null;
  return {
    root: f,
    render(s, anim) {
      title.textContent = s.title || 'Terminal';
      // A Mac's window: traffic lights on the left, for the scenes that show one.
      f.querySelector('.pk-win').classList.toggle('mac', !!s.mac);
      const tm = s.tmux === undefined ? ['[work] 0:claude*', '"workstation" 10:24'] : s.tmux;
      const k = JSON.stringify(tm);
      if (k !== tk) { tk = k; bar.style.display = tm ? '' : 'none'; if (tm) { bar.children[0].textContent = tm[0]; bar.children[1].textContent = tm[1]; } }
      const term = s.term || [];
      const list = Array.isArray(term) ? [term] : term.split;
      const kd = Array.isArray(term) ? '' : 'split3';
      if (kd !== kind) {
        kind = kd; keys = list.map(() => []);
        panes.className = 'pk-panes ' + kd;
        panes.innerHTML = list.map(() => '<div class="pk-pane"><div class="pl"></div></div>').join('') + (kd ? '<i class="vb"></i><i class="hb"></i>' : '');
      }
      list.forEach((lines, pi) => {
        const pane = panes.children[pi], pl = pane.firstChild, nk = lines.map(l => JSON.stringify(l));
        pane.classList.toggle('act', !kd || (term.act || 0) === pi);
        let p = 0;
        while (p < keys[pi].length && p < nk.length && keys[pi][p] === nk[p]) p++;
        while (pl.children.length > p) pl.lastChild.remove();
        const pm = anim ? (s.print || 0) : 0;
        for (let j = p, n = 0; j < lines.length; j++, n++) {
          const d = document.createElement('div');
          d.className = 'tl';
          d.innerHTML = lineHtml(lines[j]);
          if (anim && typeof lines[j] === 'object') d.classList.add('ty-go');
          if (pm && n) { d.classList.add('pr'); d.style.setProperty('--d', n * pm + 'ms'); }
          pl.appendChild(d);
        }
        keys[pi] = nk;
      });
    },
  };
}

/* ------------------------------------------------------------- scenes --- */

/*
 * A scene is a list of steps. A step says only what changes; everything else carries on
 * from the step before it.
 *   cap    the caption for this step (plain text)            ms     how long it plays (default 3000)
 *   phone  [screenId, params]                                 over   [screenId, params] on a scrim, or null
 *   term   an array of lines, or { split: [a, b, c], act }    tmux   [left, right], or null for no status bar
 *   title  the terminal window's title                        print  ms between printed lines when lines are added
 *   tap    a selector on the phone that a finger taps (this step only)
 *   seq    [[ms, { ...changes }], ...]: changes during the step while it plays; a held or stepped-to
 *          step shows all of them at once
 * The scene itself: { label (for screen readers), poster (the step shown when held still or
 * with reduced motion), computer: false for a phone-only scene, steps }.
 */

PK.scene = (id, def) => { scenes[id] = def; if (ready) document.querySelectorAll(`.pk-scene[data-scene="${id}"]:not(.pk-on)`).forEach(mountScene); };

const btn = (a, label, icon) => `<button type="button" class="pk-b" data-a="${a}" aria-label="${label}">${ic(icon, 20)}</button>`;
const OWN = { cap: 1, ms: 1, seq: 1, tap: 1 };
const take = (s, st) => { for (const k in st) if (!OWN[k]) s[k] = st[k]; return s; };

function mountScene(el) {
  const id = el.dataset.scene, def = scenes[id];
  if (!def || el.classList.contains('pk-on')) return;
  const steps = def.steps, n = steps.length;
  const qs = Q.get('scene') === id && Q.has('step') ? Q.get('step') : null;
  const still = Q.has('still') || qs !== null || el.hasAttribute('data-step');
  const rm = RM.matches;
  const want = qs ?? el.dataset.step ?? (still || rm ? def.poster : 0);
  let i = Math.min(n - 1, Math.max(0, parseInt(want, 10) || 0));

  el.classList.add('pk-on');
  if (still) el.classList.add('pk-still');
  el.setAttribute('role', 'group');
  el.setAttribute('aria-label', def.label || id);
  const duo = def.computer !== false && el.dataset.layout !== 'phone';
  el.innerHTML = `<div class="pk-stage ${duo ? 'duo' : 'solo'}" aria-hidden="true">${duo ? '<div class="pk-cslot"></div>' : ''}<div class="pk-pslot"></div></div>` +
    `<div class="pk-ctl"><div class="pk-btns">${btn('prev', 'Previous step', 'prev')}${still || rm ? '' : btn('play', 'Pause', 'pause')}${btn('next', 'Next step', 'next')}</div>` +
    `<div class="pk-capw"><div class="pk-prog" aria-hidden="true">${'<i></i>'.repeat(n)}</div><p class="pk-cap" aria-live="off"><span class="pk-n"></span><span class="pk-ct"></span></p></div></div>`;
  const comp = duo ? computerCtl(el.querySelector('.pk-cslot')) : null;
  const phone = phoneCtl(el.querySelector('.pk-pslot'));
  const segs = [...el.querySelectorAll('.pk-prog i')], cap = el.querySelector('.pk-cap'), playB = el.querySelector('[data-a="play"]');

  // The state at step k: every earlier step in full, then step k, then its first j timed changes.
  const at = (k, j = Infinity) => {
    const s = {};
    for (let x = 0; x < k; x++) { take(s, steps[x]); (steps[x].seq || []).forEach(q => take(s, q[1])); }
    take(s, steps[k]);
    s.tap = steps[k].tap;
    (steps[k].seq || []).slice(0, j).forEach(q => { take(s, q[1]); if (q[1].tap) s.tap = q[1].tap; });
    return s;
  };
  const draw = (s, anim, dir) => { if (comp) comp.render(s, anim); phone.render(s, anim, dir); };

  // t: time into the step; clk: the scene's own clock, which only runs while it plays.
  let t = 0, clk = 0, sj = 0, last = 0, raf = 0, playing = false, user = false, vis = false;
  const show = (k, anim, dir = 1, full) => {
    i = (k + n) % n; t = 0; last = 0;
    sj = full ? (steps[i].seq || []).length : 0;
    draw(full ? at(i) : at(i, 0), anim, dir);
    cap.querySelector('.pk-n').textContent = `${i + 1} / ${n}`;
    cap.querySelector('.pk-ct').textContent = steps[i].cap || '';
    segs.forEach((g, x) => { g.classList.toggle('done', x < i); g.style.removeProperty('--p'); });
    ticks();
  };
  // A ticking number counts from the moment it was drawn, so it runs on across steps.
  const ticks = () => el.querySelectorAll('[data-tick]').forEach(e => {
    if (!e.dataset.t0) e.dataset.t0 = clk;
    const v = +e.dataset.tick, sec = Math.floor((clk - e.dataset.t0) / 1000), f = e.dataset.tf;
    const txt = fmt(f === 'd' ? v - sec : v + sec, f);
    if (e.textContent !== txt) e.textContent = txt;
  });
  const loop = now => {
    raf = 0;
    if (!playing) return;
    const dt = last ? Math.min(250, now - last) : 0;
    t += dt; clk += dt; last = now;
    const st = steps[i], seq = st.seq || [];
    while (sj < seq.length && seq[sj][0] <= t) { sj++; draw(at(i, sj), true, 1); }
    ticks();
    if (segs[i]) segs[i].style.setProperty('--p', Math.min(1, t / (st.ms || 3000)).toFixed(3));
    if (t >= (st.ms || 3000)) show(i + 1, true, 1);
    raf = requestAnimationFrame(loop);
  };
  const sync = () => {
    const on = !still && !rm && !user && vis && !document.hidden;
    el.classList.toggle('pk-paused', !on);
    if (on === playing) return;
    playing = on;
    if (on) { last = 0; raf = raf || requestAnimationFrame(loop); }
    if (playB) { playB.setAttribute('aria-label', user ? 'Play' : 'Pause'); playB.innerHTML = ic(user ? 'play' : 'pause', 20); }
  };
  el.querySelector('.pk-ctl').addEventListener('click', e => {
    const b = e.target.closest('[data-a]');
    if (!b) return;
    const a = b.dataset.a;
    cap.setAttribute('aria-live', 'polite');
    if (a === 'play') { user = !user; sync(); return; }
    // A step chosen by hand shows its end state; autoplay then carries on from there.
    show(i + (a === 'next' ? 1 : -1), !still && !rm, a === 'next' ? 1 : -1, true);
  });
  if ('IntersectionObserver' in window) new IntersectionObserver(es => { vis = es[es.length - 1].isIntersecting; sync(); }, { threshold: .35 }).observe(el);
  else vis = true;
  document.addEventListener('visibilitychange', sync);
  show(i, false, 1, still || rm);
  sync();
}

/* A still phone: <div class="pk-phone" data-screen="sessions">. */
function mountPhone(el) {
  if (el.classList.contains('pk-on')) return;
  const id = el.dataset.screen, v = views[id] || (screens[id] ? { phone: [id, {}] } : null);
  if (!v) return;
  el.classList.add('pk-on');
  el.innerHTML = '';
  el.setAttribute('role', 'img');
  if (!el.hasAttribute('aria-label')) el.setAttribute('aria-label', `Porthole: ${id.replace(/-/g, ' ')} screen`);
  if (Q.has('still')) el.classList.add('pk-still');
  phoneCtl(el).render(v, false);
}

/* Mac or Linux: every .os-pick on the page shows one choice; the page's <head> has already
   picked a default (this visitor's last choice, else their own system) before it painted. */
function osPick(root) {
  const html = document.documentElement, picks = root.querySelectorAll('.os-pick');
  if (!picks.length) return;
  const show = (os, save) => {
    html.dataset.os = os;
    picks.forEach(p => p.querySelectorAll('button[data-os]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.os === os))));
    if (save) { try { localStorage.setItem('porthole-os', os); } catch (e) { /* private window: this page only */ } }
  };
  picks.forEach(p => p.addEventListener('click', e => {
    const b = e.target.closest('button[data-os]');
    if (!b) return;
    const top = b.getBoundingClientRect().top;
    show(b.dataset.os, true);
    // Keep the switch under the finger: content above it may have changed height.
    window.scrollBy(0, b.getBoundingClientRect().top - top);
  }));
  show(html.dataset.os === 'linux' ? 'linux' : 'mac', false);
}

/* Copy buttons for [data-copy] command blocks; without JavaScript there is no button at all. */
function copies(root) {
  root.querySelectorAll('[data-copy]:not([data-copy-on])').forEach(el => {
    el.setAttribute('data-copy-on', '');
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'copy'; b.textContent = 'copy';
    b.setAttribute('aria-label', 'Copy the command');
    b.addEventListener('click', () => {
      const text = el.dataset.copy || (el.querySelector('code, pre') || el).textContent;
      (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(() => {
        b.textContent = 'copied'; b.setAttribute('data-done', '');
        setTimeout(() => { b.textContent = 'copy'; b.removeAttribute('data-done'); }, 1500);
      }, () => {});
    });
    el.appendChild(b);
  });
}

PK.init = (root = document) => {
  root.querySelectorAll('.pk-scene[data-scene]').forEach(mountScene);
  root.querySelectorAll('.pk-phone[data-screen]').forEach(mountPhone);
  copies(root);
  osPick(root);
};
PK.mountScene = mountScene;
PK.mountPhone = mountPhone;
PK.phone = phoneCtl;
PK.computer = computerCtl;
PK.h = { esc, sp, ic, ring, spinner, star, tick, pri, gh, icot, cmd, mark, pill, dots, ap, md, frame, feedRow, banner, srow, wordmark, rule, line: lineHtml };
PK.data = { ROW, SESS, FLAKY, DARK_Q, DARK0, DARK_ASKED, DARK_SESSION, SDK, SDK0 };

/* ---------------------------------------------------- scenes 1 to 4 --- */

const tp = s => `[data-k="${s}"]`;

// 1. Install and pair.
{
  // The Homebrew way, as on a Mac: the output is what brew and portholed setup print.
  const sh = T.sh();
  const brew = { pre: sh, type: 'brew install shrimpscript/tap/porthole' };
  const brewed = [brew, '{B:==>} Tapping shrimpscript/tap', '{B:==>} Fetching shrimpscript/tap/porthole',
    '{B:==>} Installing porthole from shrimpscript/tap', '{B:==>} Caveats',
    'Start the background service and add the approval hook to Claude Code:', '  portholed setup',
    '{B:==>} Summary', '/opt/homebrew/Cellar/porthole/0.27.0: 5 files, 11.4MB'];
  const inst = { pre: sh, type: 'portholed setup' };
  const out = ['{B:Background service}', 'installed and started: ~/Library/LaunchAgents/dev.shrimpscript.portholed.plist',
    'launchd agent dev.shrimpscript.portholed running, starts at login', '',
    '{B:Claude Code hook}', 'Registered the PermissionRequest hook in ~/.claude/settings.json',
    'Permission prompts will now reach your paired phone.', 'Remove it any time with: portholed uninstall-hooks', '',
    'On a Mac: turn on Remote Login (System Settings > General > Sharing) so the phone',
    'has a way back in if the daemon ever stops. portholed keeps the Mac awake while a',
    'session works or a phone is connected, on the power adapter.', '',
    'Next: portholed doctor, then portholed pair for the phone.',
    "Start Claude Code with porthole instead of claude, in your project's folder."];
  const installed = [...brewed, inst, ...out];
  const pair = { pre: sh, type: 'portholed pair' };
  // The pairing QR (porthole://pair?host=192.0.2.10:8737&code=482913), drawn as qrText() draws it.
  const QR = (() => {
    const h = 'fe308e3fc16326106eb73b2bb75922c5dba2123aec13454507faaaaafe01f4050082dd6be72cb8ce8eaaf6493d1f2e615fc631ffb3152c765db7fe00c33450ea24d5e2f6f81c5e6eadb9e3cc65c96d89be63d1a92895cf4ec56cdaeae7a8f550b44464d7bfb0f980489c47ff9a4a6a90431211dba5d34f85d286687ee8a23927049f7284fe90f2d90', n = 33, bit = (x, y) => y < n && parseInt(h[(y * n + x) >> 2], 16) >> (3 - ((y * n + x) & 3)) & 1;
    const out = ['█'.repeat(n + 4)];
    for (let y = 0; y < n; y += 2) {
      let l = '██';
      for (let x = 0; x < n; x++) l += bit(x, y) ? (bit(x, y + 1) ? ' ' : '▄') : bit(x, y + 1) ? '▀' : '█';
      out.push(l + '██');
    }
    return [...out, out[0]];
  })();
  const paired = [...installed, pair, '', '  Pairing code:  {B:482 913}', '', ...QR.map(l => '  ' + l), '',
    "  Scan with Porthole (Pair > Scan), or with the phone's camera.", '  Or type the code into Porthole. Expires in 5 minutes.', '', sh + '{cur}'];
  const base = { title: 'Terminal', tmux: null, mac: true };
  PK.scene('onboarding', {
    label: 'Installing portholed on the computer and pairing a phone', poster: 6,
    steps: [
      { ...base, cap: 'Open Porthole on the phone. The computer needs a few commands, once.', ms: 2800, phone: ['welcome'], term: [sh + '{cur}'], seq: [[1900, { tap: tp('go') }]] },
      { cap: "What you'll need, and which computer: a Mac here. Tailscale on both devices, Claude Code in tmux, and portholed.", ms: 3200, phone: ['needs'], seq: [[1300, { tap: tp('mac') }], [1500, { phone: ['needs', { os: 'mac' }] }], [2600, { tap: tp('go') }]] },
      { cap: 'Tailscale connects the phone straight to the computer. There is no Porthole account.', ms: 3000, phone: ['tailscale'],
        seq: [[2300, { tap: tp('go') }]] },
      { cap: 'At the computer: install portholed with Homebrew, then portholed setup starts its service.', ms: 7600, phone: ['setup', { os: 'mac', scroll: 250 }], term: [brew],
        seq: [[2000, { term: brewed, print: 45 }], [3000, { term: [...brewed, inst] }], [4300, { term: installed, print: 45 }], [6900, { tap: tp('go') }]] },
      { cap: 'Name the computer. Porthole checks that portholed is answering on the tailnet.', ms: 3400, phone: ['connect', { host: 'workstation' }],
        seq: [[600, { phone: ['connect', { host: 'workstation', checking: 1 }] }], [1300, { phone: ['connect', { host: 'workstation', found: 1 }] }], [2600, { tap: tp('go') }]] },
      { cap: 'Before anything is paired, Porthole lists what this phone will be able to do on the computer.', ms: 3600, phone: ['consent'], seq: [[2900, { tap: tp('go') }]] },
      { cap: 'portholed pair prints a QR code and a 6-digit code. Scan it, or type the code.', ms: 5200, phone: ['pair'], term: [...installed, pair],
        seq: [[900, { term: paired, print: 30 }], [2100, { phone: ['pair', { code: '4' }] }], [2300, { phone: ['pair', { code: '48' }] }], [2500, { phone: ['pair', { code: '482' }] }],
          [2700, { phone: ['pair', { code: '4829' }] }], [2900, { phone: ['pair', { code: '48291' }] }], [3100, { phone: ['pair', { code: '482913' }] }],
          [3900, { tap: tp('go') }], [4200, { phone: ['pair', { code: '482913', pairing: 1 }] }]] },
      { cap: 'Paired. A short tour shows the feed, approvals and the terminal.', ms: 3000, phone: ['tour'], seq: [[2300, { tap: tp('skip') }]] },
      { cap: 'Every Claude Code session on the computer, on the phone.', ms: 3600, phone: ['sessions'] },
    ],
  });
}

// 2. Sessions: three in tmux, one working, one waiting, one done; the working one finishes.
{
  const W = 43, R = 44;
  const headA = [...T.banner('Opus 5', '~/code/shop-api').map(l => l.replace(' · Claude Pro', '')), '',
    '{u:❯ The login test fails about one run in }', '{u:  five. Find out why and fix it.         }', '',
    T.tool('Bash', 'npm test -- login'), T.res('1 failed, 23 passed'), '', T.tool('Read', 'src/auth/session.ts'), T.res('Read {B:88} lines'), ''];
  const A = (extra, spin) => [...headA, ...extra, ...(spin ? [spin, ''] : []), ...T.input(W, '{v:⏵⏵ accept edits on}')];
  const B = [T.tool('Search', 'pattern: "prefers-color-scheme"'), T.res('Found {B:2} files'), rule(R), ' ☐ Default', 'Which theme should new users get?',
    '{b:❯ 1. Follow the system}', '     {d:Matches the phone or computer setting}', '  2. Always light', '     {d:Dark mode stays opt-in}', '  3. Type something.', rule(R), '  4. Chat about this', '{d:Enter to select · ↑/↓ to navigate · Esc}'];
  const C = ['● Updated the migration and the CI image.', '  All 212 tests pass on Postgres 16.', '', T.done('Worked for 6m 30s · done 10:02 AM'), '', ...T.input(R, '{v:⏵⏵ accept edits on}')];
  const fix = [T.say('The test awaits login() but not the'), '  session write. Awaiting it now.', '', T.tool('Update', 'src/auth/session.ts'), T.res('Updated with 1 addition'), ''];
  const run = [...fix, T.tool('Bash', 'npm test -- login --repeat 20'), T.res('24 lines {d:(ctrl+o to expand)}'), '', T.say('Fixed. The login test passed 20 runs'), '  in a row.', '', T.done('Worked for 1m 12s · done 10:26 AM'), ''];
  const term = a => ({ split: [a, B, C], act: 0 });
  const list = (f, doing) => ['sessions', { groups: [['Needs you', [ROW.dark]], ['Live', [f ? ROW.flaky(f, doing) : ROW.flakyDone, ROW.pg]], ['Recent', [ROW.inv]]] }];
  PK.scene('sessions', {
    label: 'Three Claude Code sessions in tmux, and the list on the phone', poster: 0,
    steps: [
      { title: 'Terminal', cap: 'Three Claude Code sessions in tmux. One row each on the phone, with the ones waiting on you first.', ms: 4000,
        phone: list(42), term: term(A([], T.spin('Investigating', '{tick:42,s} · esc to interrupt'))) },
      { cap: 'A working session shows how long it has been at it, and what it is doing right now.', ms: 4200,
        seq: [[1600, { phone: list(47, 'Edit: session.ts'), term: term(A(fix, T.spin('Testing', '{tick:47,s} · esc to interrupt'))) }]] },
      { cap: 'When a turn ends, the spinner stops and a dot marks the session as new since you looked.', ms: 3600, phone: list(0), term: term(A(run)) },
      { cap: 'Open it to see what it did.', ms: 1400, tap: tp('flaky') },
      { cap: 'Prompts, replies and every tool call, with how long each one took.', ms: 4600, phone: ['session', { rows: FLAKY, quick: 1, b: 'fix/login-race · tmux work' }] },
    ],
  });
}

// 3. The hero: a question in the terminal, answered by a tap on the phone.
{
  const head = [...T.banner('Opus 5 with high effort', '~/code/shop-web'), '', T.user('Add a dark mode toggle to the settings page.'), '',
    T.say("I'll check how the settings page stores preferences first."), '', T.tool('Read', 'src/settings/Settings.tsx'), T.res('Read {B:142} lines {d:(ctrl+o to expand)}'), '',
    T.tool('Search', 'pattern: "prefers-color-scheme", path: "src"'), T.res('Found {B:2} files {d:(ctrl+o to expand)}'), ''];
  const mode = '{v:⏵⏵ accept edits on} {d:(shift+tab to cycle) · esc to interrupt}';
  const picker = sel => [rule(), ' ☐ Default', 'Which theme should new users get?',
    sel === 1 ? '{b:❯ 1. Follow the system}' : '  1. Follow the system', '     {d:Matches the phone or computer setting}',
    sel === 2 ? '{b:❯ 2. Always light}' : '  2. Always light', '     {d:Dark mode stays opt-in}', '  3. Type something.', rule(), '  4. Chat about this',
    '{d:Enter to select · ↑/↓ to navigate · Esc to cancel}'];
  const answered = [...head, "● User answered Claude's questions:", '  {d:⎿}  · Which theme should new users get? {d:→} Always light', ''];
  const edits = [T.tool('Update', 'src/settings/theme.ts'), T.res('Updated {B:src/settings/theme.ts} with {B:18} additions and {B:2} removals'), ''];
  const rows1 = [...DARK0, DARK_ASKED, ['r', 'Answered: Always light']];
  const ses = (x) => ['session', { ...DARK_SESSION, ...x }];
  PK.scene('question', {
    label: 'Claude Code asks a question in the terminal and it is answered from the phone', poster: 2,
    steps: [
      { title: 'Terminal', cap: 'Claude Code is working in tmux on the computer. The phone follows the same session.', ms: 3000,
        term: [...head, T.spin('Pondering', '{tick:8,s} · ↓ 1.1k tokens · esc to interrupt'), '', ...T.input(88, mode)],
        phone: ses({ rows: [...DARK0, ['c', 'Pondering']], strip: { x: 'Pondering…', s: 8, tok: '1.1k tokens' } }) },
      { cap: 'Claude asks a multiple-choice question. The picker is on the computer; the same choices arrive on the phone.', ms: 3600,
        term: [...head, ...picker(1)], seq: [[700, { phone: ses({ rows: DARK0, strip: { x: 'Waiting for your answer', s: 11 }, q: DARK_Q }) }]] },
      { cap: 'One tap on option 2. The phone presses that key in the picker.', ms: 1500, tap: tp('o2') },
      { cap: 'The picker on the computer moves to option 2 and submits.', ms: 2600, term: [...head, ...picker(2)],
        seq: [[500, { term: [...answered, T.spin('Wiring the toggle', '{tick:16,s} · ↓ 2.4k tokens · esc to interrupt'), '', ...T.input(88, mode)],
          phone: ses({ rows: [...rows1, ['c', 'Wiring the toggle']], strip: { x: 'Wiring the toggle…', s: 16, tok: '2.4k tokens' } }) }]] },
      { cap: 'Both sides show the answer, and Claude carries on.', ms: 4200,
        term: [...answered, ...edits, T.spin('Wiring the toggle', '{tick:18,s} · ↓ 3.0k tokens · esc to interrupt'), '', ...T.input(88, mode)],
        phone: ses({ rows: [...rows1, ['t', 'Edited ~/code/shop-web/src/settings/theme.ts', '1.2s'], ['r', 'done', '74 chars'], ['t', 'Edited ~/code/shop-web/src/settings/Settings.tsx', '…']], strip: { x: 'Wiring the toggle…', s: 18, tok: '3.0k tokens' } }) },
    ],
  });
}

// 4. An approval: npm publish waits at the desk, Allow on the phone, the terminal proceeds.
{
  const head = [...T.banner('Opus 5 with high effort', '~/code/shop-sdk'), '', T.user('Bump the version to 2.3.0, run the tests, then publish it.'), '',
    T.tool('Update', 'package.json'), T.res('Updated {B:package.json} with {B:1} addition and {B:1} removal'), '',
    T.tool('Bash', 'npm test'), T.res('Tests:       58 passed, 58 total'), '     Time:        6.2 s', ''];
  const dialog = [T.tool('Bash', 'npm publish --access public', 1), '', rule(), ' {B:Bash command}', '', '   npm publish --access public',
    '   {d:Publish @shop/sdk 2.3.0 to the npm registry}', '', ' Do you want to proceed?', ' {b:❯ 1. Yes}',
    "   2. Yes, and don't ask again for {B:npm publish} commands in ~/code/shop-sdk", '   3. No, and tell Claude what to do differently {d:(esc)}'];
  const mode = '{d:⏸ manual mode on · ? for shortcuts}';
  const ran = [...head, T.tool('Bash', 'npm publish --access public'), T.res('+ @shop/sdk@2.3.0'), ''];
  const s0 = [...SDK0, ['t', 'Ran npm publish --access public', '…']];
  const ses = x => ['session', { ...SDK, ...x }];
  PK.scene('approval', {
    label: 'Approving npm publish from the phone while the terminal waits', poster: 1,
    steps: [
      { title: 'Terminal', tmux: ['[api] 0:claude*', '"workstation" 10:30'], cap: 'Claude Code needs permission to run npm publish. The prompt waits at the desk, and the request reaches the phone.', ms: 3600,
        term: [...head, ...dialog], phone: ses({ rows: s0, strip: { x: 'Running Bash…', s: 51 } }), seq: [[800, { over: ['approval', { left: 90 }] }]] },
      { cap: 'The command in full, exactly as it will run, and how long is left. Nothing is approved for you.', ms: 3400 },
      { cap: 'Allow.', ms: 1500, tap: tp('allow') },
      { cap: 'The prompt closes on the computer and the command runs.', ms: 4600, over: null,
        term: [...ran, T.spin('Publishing', '{tick:4,s} · ↓ 1.8k tokens · esc to interrupt'), '', ...T.input(88, mode)],
        phone: ses({ rows: [...SDK0, ['t', 'Ran npm publish --access public', '…']], strip: { x: 'Running Bash…', s: 59 } }),
        seq: [[1800, { term: [...ran, T.say('Published {B:@shop/sdk 2.3.0}. All 58 tests passed before the upload.'), '', T.done('Worked for 1m 48s · done 10:31 AM'), '', ...T.input(88, mode)],
          phone: ses({ rows: [...SDK0, ['t', 'Ran npm publish --access public', '3.9s'], ['r', 'done', '14 lines'], ['a', 'Published **@shop/sdk 2.3.0**. All 58 tests passed before the upload.'], ['n', 'Worked for 1m 48s', '10:31']], quick: 1 }) }]] },
    ],
  });
}

ready = true;
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => PK.init());
else PK.init();
})();
