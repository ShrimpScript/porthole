// The computer's screen: its face, drawn over its work. Two block-cursor eyes, teal while it
// runs and amber while it waits for you (the app's own colours for "live" and "needs you"),
// with its work scrolling faintly behind them, and the question it asks. It starts the film
// in a light theme; the answer it gets from the park turns it dark, in a circle that opens from
// the middle of the screen.
import * as THREE from 'three';
import { clamp, lerp } from './kit.js';

export const TEAL = '#3FD4C0', AMBER = '#E7B84A', PAPER = '#E8F0EE', QUIET = '#8FB1A9', NIGHT = '#07100F';
// the light theme's inks: the same hues, deep enough to read on paper
const TEAL_INK = '#139C8A', AMBER_INK = '#D0901A';
export const FACE_W = 1024, FACE_H = 656;
const W = FACE_W, H = FACE_H;
const MONO = '"Iosevka Term", monospace', SANS = '"Schibsted Grotesk", sans-serif';

// what the agent is doing while they work: a plain log of reads, edits and tests
const LOG = [
  ['•', 'read   src/settings/Settings.tsx', '0.1s'],
  ['·', '142 lines', ''],
  ['•', 'search prefers-color-scheme', '0.4s'],
  ['·', '2 files', ''],
  ['•', 'edit   src/theme/tokens.ts', ''],
  ['+', '  --surface-dark: #07100F;', ''],
  ['+', '  --ink-dark: #E8F0EE;', ''],
  ['•', 'edit   src/settings/Settings.tsx', ''],
  ['+', '  <ThemeToggle value={theme} />', ''],
  ['•', 'run    npm test -- theme', '6.4s'],
  ['✓', '12 passed', ''],
  ['•', 'write  src/theme/useTheme.ts', ''],
  ['+', '  const system = matchMedia(q)', ''],
  ['•', 'read   src/app/App.tsx', '0.1s'],
  ['·', '88 lines', ''],
  ['•', 'edit   src/app/App.tsx', ''],
  ['+', '  <ThemeProvider>', ''],
];

const hex = (h) => [16, 8, 0].map((s) => (parseInt(h.slice(1), 16) >> s) & 255);
const mixRGB = (a, b, t) => { const A = hex(a), B = hex(b); return A.map((v, i) => Math.round(lerp(v, B[i], t))); };
const css = (c, a = 1) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;
const mix = (a, b, t) => css(mixRGB(a, b, t));

export function makeFace() {
  const canvas = document.createElement('canvas');
  canvas.width = W; canvas.height = H;
  const g = canvas.getContext('2d');
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 8;
  const state = {
    power: 1,        // 0: dark glass; 1: lit
    dark: 0,         // the theme: 0 light, 1 dark (drawn as a circle opening from the middle)
    scroll: 0,       // lines of the log gone by
    logAlpha: 0.22,  // how visible the work behind the face is
    wait: 0,         // 0 teal .. 1 amber
    open: 1,         // eyelids: 1 open, 0 shut
    eyes: 2,         // 1: only the left eye is on (the cursor, before it wakes)
    cursor: 1,       // the left eye's visibility, for the cursor's blink
    lookX: 0, lookY: 0, // -1..1
    happy: 0,        // eyes turn to ^ ^
    sad: 0,          // outer lids droop
    wide: 0,         // surprise: bigger eyes
    squash: 0,       // -1 tall .. 1 flat
    smile: 0,        // a small mouth: -1 frown .. 1 smile; 0 hidden
    ask: 0,          // the question card, 0..1
    picked: 0,       // the answer arriving: "Dark" lit
    done: 0,         // the finished line
    doneText: '✓  Dark mode is in',
    heart: 0,        // a small heart over the eyes
    pulse: 0,        // a ring going out from the screen as the question is sent (0..1)
    prompt: 0,       // a shell prompt beside the first cursor, before it is an eye
  };

  function roundRect(x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    g.beginPath();
    g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath();
  }

  function eye(cx, cy, side, s, col, glow) {
    const w = 104 * (1 + 0.22 * s.wide) * (1 + 0.16 * s.squash);
    const hFull = 168 * (1 + 0.22 * s.wide) * (1 - 0.28 * s.squash);
    const h = Math.max(12, hFull * clamp(s.open));
    g.save();
    if (glow > 0) { g.shadowColor = col; g.shadowBlur = 40 * glow; }
    g.fillStyle = col; g.strokeStyle = col;
    if (s.happy > 0.02) {
      g.globalAlpha *= s.happy;
      g.lineWidth = 36; g.lineCap = 'round'; g.lineJoin = 'round';
      g.beginPath();
      g.moveTo(cx - w * 0.55, cy + 24); g.quadraticCurveTo(cx, cy - 90, cx + w * 0.55, cy + 24);
      g.stroke();
      g.globalAlpha /= s.happy;
      g.globalAlpha *= 1 - s.happy;
    }
    if (s.happy < 0.98) {
      g.translate(cx, cy);
      g.save();
      roundRect(-w / 2, -h / 2, w, h, 30);
      g.clip();
      g.fillRect(-w / 2 - 50, -h / 2 - 50, w + 100, h + 100);
      if (s.sad > 0.01) {
        // a sloping upper lid, heavier at the outside corner
        g.globalCompositeOperation = 'destination-out';
        g.shadowBlur = 0;
        const drop = s.sad * h * 0.62;
        g.beginPath();
        g.moveTo(-w, -h); g.lineTo(w, -h);
        g.lineTo(w, -h / 2 + (side > 0 ? drop : drop * 0.12));
        g.lineTo(-w, -h / 2 + (side > 0 ? drop * 0.12 : drop));
        g.closePath();
        g.fill();
        g.globalCompositeOperation = 'source-over';
      }
      g.restore();
      // a glint: what turns a block into a look
      if (h > 40) {
        g.shadowBlur = 0;
        g.fillStyle = 'rgba(255,255,255,0.6)';
        roundRect(-w / 2 + 17, -h / 2 + 15 + s.sad * h * 0.3, 22, Math.min(36, h * 0.28), 10);
        g.fill();
      }
    }
    g.restore();
  }

  // everything on the glass, in one theme
  function layer(s, dk) {
    const bgA = dk ? '#12302B' : '#FBF7EF', bgB = dk ? '#07130F' : '#E9E1D2';
    const bg = g.createRadialGradient(W / 2, H * 0.42, 40, W / 2, H / 2, W * 0.72);
    bg.addColorStop(0, bgA); bg.addColorStop(1, bgB);
    g.fillStyle = bg; g.fillRect(0, 0, W, H);
    const ink = dk ? PAPER : '#1E3A35', quiet = dk ? QUIET : '#7E968F', live = dk ? TEAL : TEAL_INK;

    if (s.logAlpha > 0.005) {
      g.save();
      g.globalAlpha = s.logAlpha * (dk ? 1 : 0.55);
      g.font = `28px ${MONO}`;
      const lh = 40, first = Math.floor(s.scroll), frac = s.scroll - first;
      for (let i = -1; i < 18; i++) {
        const k = ((first + i) % LOG.length + LOG.length) % LOG.length;
        const [m, text, time] = LOG[k];
        const y = 62 + (i - frac) * lh;
        if (y < 20 || y > H - 12) continue;
        g.fillStyle = m === '+' || m === '✓' ? live : ink;
        g.fillText(m, 50, y);
        g.fillStyle = m === '·' || m === '+' ? quiet : ink;
        g.fillText(text, 90, y);
        if (time) { g.fillStyle = quiet; g.fillText(time, W - 50 - g.measureText(time).width, y); }
      }
      g.restore();
      // the log fades out under the face, so the eyes read first
      const c = dk ? [16, 38, 34] : [250, 246, 238];
      const veil = g.createRadialGradient(W / 2, H * 0.42, 80, W / 2, H * 0.42, 430);
      veil.addColorStop(0, css(c, 0.9)); veil.addColorStop(1, css(c, 0));
      g.fillStyle = veil; g.fillRect(0, 0, W, H);
    }

    const col = mix(dk ? TEAL : TEAL_INK, dk ? AMBER : AMBER_INK, s.wait);
    const lift = lerp(0, -118, s.ask);
    const cy = H * 0.47 + lift + s.lookY * 36;
    const cx = W / 2 + s.lookX * 76;
    const sc = lerp(1, 0.6, s.ask);
    g.save();
    g.translate(cx, cy); g.scale(sc, sc); g.translate(-cx, -cy);
    const glow = dk ? 1 : 0.25;
    if (s.prompt > 0.01) {
      g.save(); g.globalAlpha = clamp(s.prompt); g.fillStyle = col; g.font = `600 110px ${MONO}`;
      g.fillText('~ $', cx - 150 - 52 - 230, cy + 38); g.restore();
    }
    g.save(); g.globalAlpha *= s.cursor; eye(cx - 150, cy, -1, s, col, glow); g.restore();
    if (s.eyes > 1) {
      g.save();
      g.globalAlpha = clamp(s.eyes - 1);
      eye(cx + 150, cy, 1, s, col, glow);
      g.restore();
    }
    if (Math.abs(s.smile) > 0.02) {
      g.save();
      g.globalAlpha = clamp(Math.abs(s.smile) * 2);
      g.strokeStyle = col; g.lineWidth = 22; g.lineCap = 'round';
      if (dk) { g.shadowColor = col; g.shadowBlur = 24; }
      g.beginPath();
      const mw = 62, my = cy + 146, bend = 44 * s.smile;
      g.moveTo(cx - mw, my - bend * 0.2); g.quadraticCurveTo(cx, my + bend, cx + mw, my - bend * 0.2);
      g.stroke();
      g.restore();
    }
    if (s.heart > 0.01) {
      g.save();
      const k = clamp(s.heart);
      g.globalAlpha = clamp(k * 1.6);
      g.translate(cx, cy - 170 - 26 * k); g.scale(0.5 + 0.5 * k, 0.5 + 0.5 * k);
      g.fillStyle = live; if (dk) { g.shadowColor = live; g.shadowBlur = 30; }
      g.beginPath();
      g.moveTo(0, 34); g.bezierCurveTo(-66, -8, -34, -66, 0, -30); g.bezierCurveTo(34, -66, 66, -8, 0, 34);
      g.fill();
      g.restore();
    }
    g.restore();

    if (s.ask > 0.005) {
      const a = clamp(s.ask);
      const y0 = lerp(H + 20, H - 296, a);
      g.save();
      g.globalAlpha = clamp(a * 1.4);
      const border = mix(dk ? AMBER : AMBER_INK, dk ? TEAL : TEAL_INK, s.picked);
      roundRect(64, y0, W - 128, 258, 26);
      g.fillStyle = dk ? 'rgba(12,28,25,0.95)' : 'rgba(255,252,246,0.97)'; g.fill();
      g.lineWidth = 5; g.strokeStyle = border; g.stroke();
      g.fillStyle = border; g.font = `600 29px ${SANS}`;
      g.fillText('A question for you', 100, y0 + 50);
      g.fillStyle = ink; g.font = `600 40px ${SANS}`;
      g.fillText('Which theme should new users get?', 100, y0 + 102);
      const opt = (i, label, x, w) => {
        const lit = i === 1 ? s.picked : 0;
        const yy = y0 + 136;
        roundRect(x, yy, w, 86, 18);
        g.fillStyle = lit > 0 ? css(hex(dk ? TEAL : TEAL_INK), 0.15 + 0.6 * lit) : (dk ? 'rgba(232,240,238,0.06)' : 'rgba(30,58,53,0.05)');
        g.fill();
        g.lineWidth = 3; g.strokeStyle = lit > 0 ? live : (dk ? 'rgba(232,240,238,0.25)' : 'rgba(30,58,53,0.22)'); g.stroke();
        g.fillStyle = lit > 0.5 && dk ? NIGHT : ink; g.font = `600 36px ${SANS}`;
        g.fillText(label, x + w / 2 - g.measureText(label).width / 2, yy + 56);
      };
      opt(1, 'Dark', 100, (W - 230) / 2);
      opt(2, 'Light', 130 + (W - 230) / 2, (W - 230) / 2);
      g.restore();
    }

    if (s.done > 0.005) {
      g.save();
      g.globalAlpha = clamp(s.done);
      const y = H - 96;
      roundRect(W / 2 - 250, y - 46, 500, 80, 40);
      g.fillStyle = css(hex(live), 0.16); g.fill();
      g.strokeStyle = live; g.lineWidth = 3; g.stroke();
      g.fillStyle = live; g.font = `600 38px ${SANS}`;
      const txt = s.doneText;
      g.fillText(txt, W / 2 - g.measureText(txt).width / 2, y + 7);
      g.restore();
    }
  }

  function draw() {
    const s = state;
    g.save();
    g.globalAlpha = 1;
    g.globalCompositeOperation = 'source-over';
    g.fillStyle = '#050908'; g.fillRect(0, 0, W, H);
    if (s.power > 0.005) {
      g.globalAlpha = s.power;
      if (s.dark < 0.999) layer(s, false);
      if (s.dark > 0.001) {
        // the dark theme opens as a circle from the middle
        g.save();
        const R = lerp(0, Math.hypot(W, H) / 2 + 10, clamp(s.dark));
        g.beginPath(); g.arc(W / 2, H / 2, R, 0, Math.PI * 2); g.clip();
        layer(s, true);
        g.restore();
        if (s.dark < 0.999) {
          g.strokeStyle = TEAL; g.lineWidth = 10; g.globalAlpha = s.power * 0.9;
          g.beginPath(); g.arc(W / 2, H / 2, R, 0, Math.PI * 2); g.stroke();
        }
      }
      // the question going out: an amber ring from the middle of the glass
      if (s.pulse > 0.001 && s.pulse < 1) {
        for (let k = 0; k < 2; k++) {
          const u = clamp(s.pulse * 1.3 - k * 0.3);
          if (u <= 0 || u >= 1) continue;
          g.globalAlpha = s.power * (1 - u);
          g.strokeStyle = s.dark > 0.5 ? AMBER : AMBER_INK; g.lineWidth = 16 * (1 - u) + 3;
          g.beginPath(); g.arc(W / 2, H * 0.4, 40 + u * 560, 0, Math.PI * 2); g.stroke();
        }
      }
    }
    g.restore();
    tex.needsUpdate = true;
  }

  return { canvas, tex, state, draw };
}
