/* Porthole device kit, part two: more screens, and scenes 5 to 11. Load after kit.js; ids and params are in _kit/index.html. */
(() => {
'use strict';
const { esc, sp, ic, ring, tick, pri, gh, icot, pill, ap, line } = PK.h;
const { ROW, DARK0, DARK_ASKED, DARK_SESSION } = PK.data;
const S = PK.screens, T = PK.t, tp = k => `[data-k="${k}"]`;
const P = (c, t, x = '') => `<p class="${c}"${x}>${t}</p>`, M = (t, x) => P('ty-m k-fa', t, x), pt = ' style="padding-top:4px"';
const chip = (t, on) => `<span class="chip${on ? ' on' : ''}">${t}</span>`;
const card = (t, b, r) => `<div class="card${r ? 3 : 2}">${M(t)}${sp(r ? 8 : 10)}${b}</div>`;
const kv = (k, v) => `<div class="kv">${P('ty-s k-mu f1', k)}${P('ty-s', v)}</div>`, kvs = a => a.map(x => kv(...x)).join('');
const tog = (t, d, on, k, big, go) => `<div class="row" style="gap:12px"><div class="f1">${P(big ? 'ty-b' : 'ty-s', t)}${M(d)}</div><i class="sw${on ? ' on' : ''}${go ? ' go' : ''}"${k ? ` data-k="${k}"` : ''}></i></div>`;
const nums = (a, r) => `<span class="nums ty-mo"><b class="k-ok">+${a}</b><b class="k-ba">−${r}</b></span>`;
const bar = (w, x = '') => `<div class="bar${x}"><i style="width:${w}%"></i></div>`;
const sheet = b => `<i class="shx" data-k="shx"></i><div class="sheet" data-k="sheet"><i class="hdl"></i><div class="shc">${b}</div></div>`;
const scroller = (y, b, c) => `<div class="col ${c}" data-scroll style="transform:translateY(${-(y || 0)}px)">${b}</div>`;
const keys = () => `<div class="krow">${'Esc Tab ⇧Tab Ctrl Alt ^C / @ ! ←'.split(' ').map((k, i) => `<span data-k="k${i}">${k}</span>`).join('')}</div>`;
// The desk's cells at 14.4 px, scaled: fit (all 88 columns) or a text size; a zoomed grid grows in from fit.
const grid = (L, k, w = 88) => `<div class="ptm" data-k="tb"><div class="ptm-g${k > .6 && w > 60 ? ' zi' : ''}" style="--k:${k};width:${w}ch"><div class="pl">${L.map(l => `<div class="tl">${line(l)}</div>`).join('')}</div></div></div>`;

/* ------------------------------------------------------------ Changes --- */

const FILES = [['M', 'src/settings/Settings.test.tsx', 16, 0], ['M', 'src/settings/Settings.tsx', 9, 3], ['M', 'src/settings/theme.ts', 18, 2],
  ['M', 'src/styles/tokens.css', 22, 1], ['n', 'public/icons/moon.png'], ['n', 'src/settings/ThemeToggle.tsx', 34, 0]];
const DIFF = `--- a/src/settings/theme.ts
+++ b/src/settings/theme.ts
@@ -1,7 +1,23 @@
-export type Theme = 'light' | 'dark';
+export type Theme = 'light' | 'dark' | 'system';

 export const defaultTheme: Theme = 'light';
+export const themes: Theme[] = ['light', 'dark', 'system'];
+const KEY = 'settings.theme';
+
+export function savedTheme(): Theme {
+  const t = localStorage.getItem(KEY);
+  if (t === 'dark' || t === 'system') return t;
+  return defaultTheme;
+}

 export function applyTheme(t: Theme) {
-  document.documentElement.dataset.theme = t;
+  const dark = t === 'dark' ||
+    (t === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
+  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
+}
+
+export function setTheme(t: Theme) {
+  localStorage.setItem(KEY, t);
+  applyTheme(t);
+  document.dispatchEvent(new Event('themechange'));
 }`;
const CH_S = { ...DARK_SESSION, quick: 1, rows: [...DARK0, DARK_ASKED, ['r', 'Answered: Always light'], ['t', 'Edited ~/code/shop-web/src/settings/theme.ts', '1.2s', 1], ['r', 'done', '74 chars'],
  ['t', 'Ran npm test', '9.8s'], ['r', 'done', '31 lines'], ['a', 'Added the toggle. New users get **light**.'], ['n', 'Worked for 3m 40s', '10:28']] };
PK.screen('changes', (p = {}) => S.session(CH_S) + (p.sheet === 0 ? '' : sheet(p.file == null
  ? `<div class="row" style="gap:10px;padding:4px 20px"><div class="f1">${P('ty-d', 'Changes')}${P('ty-s k-mu', '6 files · main · uncommitted')}</div>${nums(99, 6)}${pill('Refresh')}</div>${sp(4)}` +
    FILES.map(([s, n, a, r], i) => `<div class="chrow" data-k="f${i}">${P(`ty-m st k-${s < 'N' ? 'ac' : 'ok'}`, s < 'N' ? 'modified' : 'new')}${P('ty-mo el f1', n)}${a == null ? M('binary') : nums(a, r)}</div>`).join('')
  : `<div class="row" style="gap:8px;padding:0 16px 6px 4px">${icot('back')}${P('ty-mo el f1', FILES[p.file][1])}${nums(18, 2)}</div><div class="diff ty-mo">` +
    DIFF.split('\n').map(l => `<p class="${/^(\+\+|--)/.test(l) ? 'k-fa' : { '@': 'dh', '+': 'da', '-': 'dr' }[l[0]] || 'k-mu'}">${esc(l) || ' '}</p>`).join('') + '</div>')));

/* ---------------------------------------------------- Session details --- */

const PG_S = { t: 'Migrate to Postgres 16', b: 'db-16 · tmux work', chip: 'Sonnet 5 · 4%', quick: 1, rows: [['s', '3 turns since you left · 4h ago'],
  ['u', 'Run the full suite against Postgres 16.', '10:02'], ['t', 'Ran npm test', '52s', 1], ['r', 'failed', '3 failed, 209 passed', 1],
  ['a', 'Three queries cast `text` to `int` implicitly. Made it explicit; all 212 pass.'], ['n', 'Worked for 2m 40s', '10:05'],
  ['u', 'Note the upgrade in the README.', '10:06'], ['t', 'Edited ~/code/shop-api/README.md', '0.2s'], ['n', 'Worked for 31s', '10:07'],
  ['u', 'Open a pull request.', '10:07'], ['t', 'Ran gh pr create --fill', '2.1s'], ['n', 'Worked for 48s', '10:08']] };
const pick = (n, cur, cs, on) => `<div class="row">${P('ty-s k-mu', n, ' style="width:96px;flex:none"')}${P('ty-b', cur)}</div>${sp(6)}<div class="row chips">${cs.split(',').map((c, i) => chip(c, i === on)).join('')}</div>`;
PK.screen('details', (p = {}) => S.session(PG_S).replace('"feed"', `"feed${p.top ? ' top' : ''}"`) + (p.sheet === 0 ? '' : sheet(scroller(p.scroll,
  P('ty-t', PG_S.t) + `<div class="row" style="gap:8px">${chip('Sonnet 5')}${chip('accept edits on')}</div>` +
  `<div>${kv('Context window', '42k of 1.0M')}${sp(8)}${bar(4.2)}${M('The last request carried 42k tokens: prompt, history, files, and cache.', ' style="padding-top:6px"')}</div>` +
  card('Tokens this session', kvs([['Input', '2.4k'], ['Output', '31k'], ['Thinking', '12k'], ['Cache read', '1.8M'], ['Cache written', '214k']]), 1) +
  card('Activity', kvs([['Turns', '9'], ['Your prompts', '9'], ['Replies', '14'], ['Tool calls', '60']]) +
    [['terminal', 'Bash', 22], ['description', 'Read', 18], ['edit', 'Edit', 14], ['search', 'Grep', 6]].map(([i, n, v]) => `<div class="row tbar">${ic(i, 16, 'k-mu')}${P('ty-m k-mu', n)}${bar(v / .22, ' f1')}${P('ty-m', v)}</div>`).join(''), 1) +
  card('Switches', pick('Model', 'Sonnet 5', 'Fable 5.1,Opus 5,Sonnet 5,Haiku 4.5', 2) + sp(10) + pick('Effort', 'high', 'low,medium,high,xhigh', 2) + sp(10) +
    pick('Permissions', 'accept edits on', 'Cycle (Shift-Tab)') + sp(8) + M('Model and effort also become the defaults for new sessions; the CLI saves them.'), 1) +
  card('Notifications', p.muted ? tog('Muted', 'It still shows in the list and in Needs you — it just stays silent.', 0, 'mute', 0, 1)
    : tog('Tell me about this session', 'The working timer, the finished turn, and a question waiting on you.', 1, 'mute'), 1), 'shs'))));

/* ------------------------------------------------- Terminal and SSH --- */

const TS = { t: 'Rate-limit the login endpoint', b: 'main · tmux api', chip: 'Opus 5 · 3%' }, ASK = 'Rate-limit the login endpoint: five tries a minute per address.';
const tHead = [...T.banner('Opus 5 with high effort', '~/code/shop-api'), '', T.user(ASK), '', T.tool('Read', 'src/routes/login.ts'), T.res('Read {B:64} lines {d:(ctrl+o to expand)}'), '',
  T.tool('Update', 'src/routes/login.ts'), T.res('Updated {B:src/routes/login.ts} with {B:14} additions'), ''];
const ACC = '{v:⏵⏵ accept edits on} {d:(shift+tab to cycle)}';
const tDone = m => [...tHead, T.tool('Bash', 'npm test -- login'), T.res('Tests:       26 passed, 26 total'), '', T.say('Done. A sixth try inside a minute now gets a 429 with Retry-After.'), '',
  T.done('Worked for 1m 52s · done 10:34 AM'), '', ...T.input(88, m)];
PK.screen('terminal', (p = {}) => p.feed ? S.session({ ...TS, ...p.s }) :
  S.session({ ...TS, term: 1 }).split('<div class="feed">')[0].replace('seg t"', `seg t${p.go ? ' go' : ''}"`) +
  `<div class="tstrip">${M('88×26 · tap to type', ' style="flex:1"')}<span class="sbt${p.z ? '' : ' on'}">${p.z ? p.z + 'sp' : 'fit'}</span><span class="sbt">−</span><span class="sbt" data-k="zoom">+</span></div>` +
  grid(p.lines || tDone(ACC), p.z ? p.z / 14.4 : 360 / 633.6) + keys());

const SH = '{g:dev@workstation}:{b:~}$ ';
PK.screen('failsafe', (p = {}) => `<div class="fsh"><p class="ty-t k-mu">‹</p>${ring(p.down ? 'retrying' : 'live', 18)}<div class="f1">${P('ty-r', 'workstation · SSH')}` +
  P(`ty-m k-${p.down ? 'wa' : 'fa'}`, `Failsafe shell — ${p.down ? 'the daemon is not answering' : 'a plain SSH login, not portholed'}`) + '</div></div>' +
  grid(p.lines || [SH + 'systemctl --user status portholed', '{g:●} portholed.service - Porthole daemon (remote Claude Code over Tailscale)',
    '     Active: {g:active (running)}', '   Main PID: 4121 (portholed)', SH + '{cur}'], 13 / 14.4, 56) + keys());

/* -------------------------------------------------- Failure, Settings --- */

PK.screen('failure', (p = {}) => `<div class="fail">${ring('retrying', 44, 3)}${sp(24)}${ap(0, P('ty-d', "Can't reach workstation"))}${sp(12)}${ap(40, P('ty-b k-mu', 'connection refused'))}${sp(8)}` +
  P('ty-s k-fa', 'Still trying. This clears itself when the computer answers.') + sp(28) + pri('Retry') + sp(12) + gh('Open terminal anyway') + sp(12) + gh('Restart the daemon over SSH', 'rs') + sp(12) +
  P('ty-s k-fa', 'Both go over Tailscale SSH, which does not depend on Porthole running.') + (p.busy ? sp(16) + `<p class="ty-s k-mu" data-k="busy">${p.busy}</p>` : '') + '</div>');

PK.screen('settings', (p = {}) => `<div class="row" style="padding:4px 16px 4px 4px">${icot('back')}${P('ty-t', 'Settings')}</div><div class="stg">` + scroller(p.scroll,
  card('Computer', kvs([['Host', 'workstation'], ['This phone is known as', 'phone'], ['portholed', '0.26.0']])) +
  card('Updates', tog('Check GitHub for new versions', 'Once a day, one request to api.github.com. It carries nothing about you, this phone or your computer.', 1, 'chk', 1) + sp(10) +
    kv('Porthole', p.rel ? `${p.rel} is out · you have 0.26.0` : '0.26.0 · up to date, checked 3h ago') + sp(8) + (p.rel ? gh('Update Porthole', 'upd') : gh('Check now', 'now'))) +
  card('Another computer', M('Pair this phone with a second computer that runs portholed; its sessions join the list, named.') + sp(8) + gh('Add a computer')) +
  card('Other apps on the computer', `<div class="row"><div class="f1">${P('ty-b', 'Shopping list')}${M('1.4.0 · 12 MB')}</div>${pill('Install', 1)}</div>`) +
  card('While you are away', P('ty-b', 'Android can stop Porthole in the background') + M('Battery optimisation is on for Porthole, so a long-running connection can be cut while the phone is idle and notifications stop arriving. Exempting it keeps the socket alive while you are away.', pt) + sp(8) + gh('Open battery settings')) +
  card('If Porthole cannot connect', M('Open a shell on workstation over Tailscale SSH. It does not go through portholed, so it still works when the daemon is stopped — that is how you restart it from here.') + sp(8) + gh('Open a shell over SSH', 'ssh')),
  'stc') + '</div>');

/* ------------------------------------------- Android: installer, shade --- */

PK.screen('ainst', (p = {}) => `<div class="adlg"><div class="row" style="gap:16px"><i class="aic${p.upd ? ' po' : ''}">${p.upd ? ring('mark', 26, 3) : ''}</i>${P('adt', p.upd ? 'Porthole' : 'Shopping list')}</div>` +
  P('adm', `Do you want to ${p.upd ? 'update' : 'install'} this app?`) + `<div class="row adb"><span>Cancel</span><span data-k="inst">${p.upd ? 'Update' : 'Install'}</span></div></div>`);

const note = n => `<div class="nt" data-k="${n.k}"><div class="nti">${ring('mark', 16, 2)}</div><div class="f1">${P('ty-m k-mu', 'Porthole · ' + (n.w ? tick(n.w, 'ms') : 'now'))}${P('ntt', n.t)}${P('ty-s k-mu', n.x)}` +
  (n.w ? '<i class="ntp"></i>' : n.ri != null ? `<div class="ntr" data-k="${n.k}i">${P('ty-s f1', n.ri || '<span class="k-fa">Reply to Claude</span>')}${ic('send', 20, 'k-ac')}</div>` : n.r ? `<span class="nta" data-k="${n.k}r">Reply</span>` : '') + '</div></div>';
const NF = { k: 'flaky', t: 'Fix flaky login test' }, NQ = { k: 'q', t: 'Add dark mode to settings is asking you', x: 'Which theme should new users get?' };
const DONE = { ...NF, x: 'Done · worked for 4m 12s', r: 1 };
const qt = (i, t, d) => `<div class="qt on">${i}<div>${P('ty-s', t)}${P('ty-m', d)}</div></div>`;
PK.screen('shade', (p = {}) => { const n = p.n || [NQ, DONE, { k: 'pg', t: 'Migrate to Postgres 16', x: 'Bash: npm test', w: 83 }], w = n.filter(x => x.w).length;
  return `<div class="shade"><div class="qs">${qt(ic('tailscale', 20), 'Tailscale', 'Connected')}${qt(`<i class="qtr">${ring('mark', 18, 2.4)}</i>`, 'Porthole', w ? w + ' working' : 'Connected')}</div>${n.map(note).join('')}</div>`; });

/* ------------------------------------------------------------ views --- */

const BAN_U = { own: 1, v: '0.27.0', have: '0.26.0' }, BAN_B = { name: 'Shopping list', v: '1.4.0' };
PK.view('changes-diff', { phone: ['changes', { file: 2 }] });
PK.view('details-switches', { phone: ['details', { scroll: 314 }] });
PK.view('session-since', { phone: ['details', { sheet: 0, top: 1 }] });
PK.view('settings-update', { phone: ['settings', { rel: '0.27.0' }] });
PK.view('settings-away', { phone: ['settings', { scroll: 358 }] });
PK.view('banner-update', { phone: ['sessions', { ban: BAN_U }] });
PK.view('banner-build', { phone: ['sessions', { ban: BAN_B }] });
PK.view('install', { phone: ['sessions', { ban: BAN_B }], over: ['ainst'] });

/* ----------------------------------------------------- scenes 5 to 11 --- */

PK.scene('changes', {
  label: 'The Changes sheet and a diff', poster: 1, computer: false,
  steps: [
    { cap: 'Claude has finished. What did it change?', ms: 2400, phone: ['changes', { sheet: 0 }] },
    { cap: 'The Changes sheet: what git sees as changed in the session’s folder.', ms: 4000, phone: ['changes'] },
    { cap: 'Tap a file for its diff.', ms: 1300, tap: tp('f2') },
    { cap: 'The diff, as git prints it on the computer. Nothing is summarised.', ms: 5200, phone: ['changes', { file: 2 }] },
  ],
});

{
  const s = { rows: [['u', ASK, '10:32'], ['t', 'Read ~/code/shop-api/src/routes/login.ts', '0.1s', 1], ['r', 'done', '64 lines'], ['t', 'Edited ~/code/shop-api/src/routes/login.ts', '0.3s', 1],
    ['r', 'done', '14 lines'], ['c', 'Testing']], strip: { x: 'Testing…', s: 34, tok: '2.2k tokens' } };
  const work = [...tHead, T.spin('Testing', '{tick:34,s} · ↓ 2.2k tokens · esc to interrupt'), '', ...T.input(88, ACC + ' {d:· esc to interrupt}')];
  const done = tDone(ACC), plan = tDone('{a:⏸ plan mode on} {d:(shift+tab to cycle)}'), ph = (l, x) => ['terminal', { lines: l, ...x }];
  PK.scene('terminal', {
    label: 'The Terminal tab and its key row', poster: 2,
    steps: [
      { title: 'Terminal', tmux: ['[api] 0:claude*', '"workstation" 10:33'], cap: 'Claude Code at work in tmux, and the Feed on the phone.', ms: 2800, term: work, phone: ['terminal', { feed: 1, s }] },
      { cap: 'Switch to Terminal.', ms: 1200, tap: '.seg span:last-child' },
      { cap: 'The Terminal tab is the tmux window itself, at the desk’s width.', ms: 4800, phone: ph(work, { go: 1 }), seq: [[2200, { term: done, phone: ph(done) }]] },
      { cap: 'The key row sends Claude Code’s keys. ⇧Tab cycles the permission mode.', ms: 3800,
        seq: [[400, { tap: tp('k2') }], [900, { term: plan, phone: ph(plan) }]] },
      { cap: 'Bigger text scrolls sideways; it never rewraps.', ms: 4200, seq: [[400, { tap: tp('zoom') }], [800, { phone: ph(plan, { z: 13 }) }]] },
    ],
  });
}

{
  const head = [...T.banner('Opus 5', '~/code/shopping-list'), '', T.user('Build a release and put it on my phone.'), ''], inp = T.input(88, ACC);
  const pub = d => [`{${d ? 'd' : 'g'}:●} {B:Bash}(portholed publish app/build/outputs/apk/release/app-release.apk 1.4.0`, '       shopping-list)'];
  const built = [...head, T.tool('Bash', './gradlew assembleRelease'), T.res('BUILD SUCCESSFUL in 1m 12s'), ''];
  const spin = (v, n) => [T.spin(v, `{tick:${n},s} · ↓ 1.4k tokens · esc to interrupt`), '', ...inp];
  const list = (s, d, ban) => ['sessions', { ban, groups: [['Live', [{ k: 'rel', t: 'Build the 1.4.0 release', r: s ? 'connecting' : 'live', c: s ? 'tx' : 'mu', s, u: !s, a: 'now',
    m: `${s ? tick(s, 'ms') + ' · Bash: ' + d + ' · ' : ''}main · live · tmux app · Opus 5` }, ROW.pg]], ['Recent', [ROW.inv]]] }];
  const dl = n => ({ phone: list(0, 0, { ...BAN_B, pct: n }) });
  PK.scene('builds', {
    label: 'Installing a build published on the computer', poster: 1,
    steps: [
      { title: 'Terminal', tmux: ['[app] 0:claude*', '"workstation" 10:40'], cap: 'Claude Code builds the Android app you are working on.', ms: 3400,
        term: [...head, T.tool('Bash', './gradlew assembleRelease', 1), '', ...spin('Building', 58)], phone: list(58, './gradlew assembleRelease'),
        seq: [[1900, { term: [...built, ...pub(1), '', ...spin('Publishing', 71)], phone: list(71, 'portholed publish app/build/outputs/apk') }]] },
      { cap: 'portholed publish offers it to paired phones: the banner.', ms: 4200, print: 60, phone: list(0, 0, BAN_B),
        term: [...built, ...pub(), T.res('published Shopping list 1.4.0 as shopping-list-1.4.0.apk (12582912 bytes) to'), '     ~/.config/porthole/builds',
          '     paired phones will offer it on their next connection', '     {d:… +1 line (ctrl+o to expand)}', '', T.say('Published {B:Shopping list 1.4.0}. Your phone will offer to install it.'), '',
          T.done('Worked for 1m 31s · done 10:41 AM'), '', ...inp] },
      { cap: 'Install.', ms: 1300, tap: tp('get') },
      { cap: 'It downloads from your computer, over your tailnet.', ms: 2600, ...dl(14), seq: [[600, dl(43)], [1200, dl(76)], [1800, dl(100)]] },
      { cap: 'Then Android installs it. The first time, it asks you to allow installs from Porthole.', ms: 4600, phone: list(0, 0, BAN_B), over: ['ainst'],
        seq: [[3400, { tap: tp('inst') }]] },
    ],
  });
}

{
  // The desk: part one's panes for the other two sessions; the login fix in the first.
  const [, B, C] = PK.scenes.sessions.steps[0].term.split, u = s => `{u:${s}}`, inp = T.input(43, '{v:⏵⏵ accept edits on}');
  const A0 = [u('❯ The login test fails about one run in'), u('  five. Find out why and fix it.'), '', T.tool('Bash', 'npm test -- login'), T.res('1 failed, 23 passed'), '',
    T.tool('Update', 'src/auth/session.ts'), T.res('Updated with 1 addition'), ''];
  const A1 = [...A0, T.tool('Bash', 'npm test -- login --repeat 20'), T.res('24 lines {d:(ctrl+o to expand)}'), '', T.say('Fixed: 20 runs in a row pass.'), '', T.done('Worked for 4m 12s · done 10:41 AM'), ''];
  const term = a => ({ split: [a, B, C], act: 0 }), R = 'Now run the whole suite.', sh = n => ['shade', { n }];
  PK.scene('notify', {
    label: 'Porthole’s notifications', poster: 1,
    steps: [
      { title: 'Terminal', tmux: ['[work] 0:claude*', '"workstation" 10:41'], cap: 'Away from the desk: a question for you, and a working timer.', ms: 3800,
        term: term([...A0, T.spin('Testing', '{tick:248,s} · esc to interrupt'), '', ...inp]), phone: sh([NQ, { ...NF, x: 'Bash: npm test -- login --repeat 20', w: 248 }]) },
      { cap: 'The turn ends: how long it took, and Reply.', ms: 3600, term: term([...A1, ...inp]), phone: sh([NQ, DONE]) },
      { cap: 'Reply from the shade.', ms: 3000, seq: [[300, { tap: tp('flakyr') }], [700, { phone: sh([NQ, { ...DONE, ri: '' }]) }], [1500, { phone: sh([NQ, { ...DONE, ri: R }]) }]] },
      { cap: 'It arrives as that session’s next prompt.', ms: 4400, seq: [[200, { tap: tp('flakyi') }],
        [600, { phone: sh([NQ, { ...NF, x: 'Sent · ' + R }]), term: term([...A1, u('❯ ' + R), '', T.spin('Running', '{tick:1,s} · esc to interrupt'), '', ...inp]) }]] },
    ],
  });
}

{
  const d = x => ['details', { top: 1, ...x }];
  PK.scene('since', {
    label: 'Since you left, and muting a session', poster: 1, computer: false,
    steps: [
      { cap: 'Hours later, a dot marks what changed since you last looked.', ms: 3200,
        phone: ['sessions', { groups: [['Live', [{ ...ROW.pg, u: 1, a: '12m' }, { ...ROW.flakyDone, u: 0, a: '4h' }]], ['Recent', [ROW.inv]]] }], seq: [[2400, { tap: tp('pg') }]] },
      { cap: 'It opens where you left off. Below the line is what happened since.', ms: 4600, phone: d({ sheet: 0 }) },
      { cap: 'The chip opens Session details.', ms: 1200, tap: '.ss-h .chip' },
      { cap: 'Context, tokens and activity; model, effort and permissions as switches.', ms: 5600, phone: d(), seq: [[2600, { phone: d({ scroll: 314 }) }]] },
      { cap: 'Muted: still listed, never notifying.', ms: 3800,
        seq: [[400, { tap: tp('mute') }], [700, { phone: d({ scroll: 314, muted: 1 }) }]] },
    ],
  });
}

{
  const unit = 'portholed.service - Porthole daemon (remote Claude Code over Tailscale).', su = 'portholed.service: ';
  const log = [T.sh() + 'journalctl --user -u portholed -f -o cat', 'Started ' + unit];
  const failed = [...log, su + 'Main process exited, code=exited, status=1/FAILURE', su + 'Start request repeated too quickly.', su + "Failed with result 'exit-code'.", `{e:Failed to start ${unit}}`];
  const list = (r, w) => ['sessions', { ring: r, sub: '4 sessions · ' + w }], busy = b => ({ phone: ['failure', { busy: b }] });
  PK.scene('failsafe', {
    label: 'Restarting the daemon over SSH', poster: 1,
    steps: [
      { title: 'Terminal', tmux: ['[work] 1:logs*', '"workstation" 11:02'], cap: 'portholed stops. The phone notices and keeps trying.', ms: 3600,
        term: log, phone: ['sessions'], seq: [[900, { term: failed, print: 90 }], [1900, { phone: list('retrying', 'reconnecting') }]] },
      { cap: 'Then it says so, and offers Tailscale SSH, which needs no portholed.', ms: 4200, phone: ['failure'] },
      { cap: 'This runs systemctl --user restart portholed over SSH.', ms: 2800, seq: [[400, { tap: tp('rs') }], [800, busy('Restarting portholed over SSH…')]] },
      { cap: 'The daemon is back, and so is the list.', ms: 4600, seq: [[500, { term: [...failed, 'Started ' + unit] }], [800, busy('Restarted. Reconnecting…')],
        [1900, { phone: list('connecting', 'connecting') }], [2800, { phone: ['sessions'] }]] },
    ],
  });
}

PK.scene('update', {
  label: 'Updating Porthole from GitHub', poster: 0, computer: false,
  steps: [
    { cap: 'Once a day Porthole asks GitHub for its latest release.', ms: 3600, phone: ['sessions'], seq: [[800, { phone: ['sessions', { ban: BAN_U }] }]] },
    { cap: 'Settings > Updates: the same update, and a switch to stop checking.', ms: 5200,
      seq: [[300, { tap: '.sl-h .icot:last-child' }], [800, { phone: ['settings', { rel: '0.27.0' }] }]] },
    { cap: 'Android installs it only if it is signed with the same key.', ms: 4800,
      seq: [[400, { tap: tp('upd') }], [1000, { over: ['ainst', { upd: 1 }] }]] },
  ],
});
})();
