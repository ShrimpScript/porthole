// The phone: the site's own model (site/assets/intro/phone.glb) with a screen drawn live in
// the app's own style: its welcome ring, the notification (the app's real one), and the
// session with the question card, as the app shows it, with two answers.
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';
import { clamp, lerp, out } from './kit.js';

const TEAL = '#3FD4C0', AMBER = '#E7B84A', PAPER = '#E8F0EE', QUIET = '#8FB1A9';
const SANS = '"Schibsted Grotesk", sans-serif', MONO = '"Iosevka Term", monospace';
const SW = 900, SH = 2000;

export async function makePhone(base) {
  const [gltf, notify] = await Promise.all([
    new GLTFLoader().setMeshoptDecoder(MeshoptDecoder).loadAsync(base + 'site/assets/intro/phone.glb'),
    new THREE.TextureLoader().loadAsync(base + 'site/assets/intro/tex/card-notify.webp').then((t) => t.image),
  ]);
  const model = gltf.scene;
  let screen = null;
  model.traverse((o) => {
    if (!o.isMesh) return;
    o.castShadow = true; o.receiveShadow = false;
    if (o.material?.name === 'Screen') screen = o;
  });
  const canvas = document.createElement('canvas'); canvas.width = SW; canvas.height = SH;
  const g = canvas.getContext('2d');
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = 8; tex.flipY = false;
  screen.material = new THREE.MeshBasicMaterial({ map: tex, toneMapped: false });
  screen.castShadow = false;
  // a group whose origin is the phone's centre, face toward +z, top toward +y
  const root = new THREE.Group();
  root.add(model);
  const box = new THREE.Box3().setFromObject(screen);
  const state = {
    wake: 1,          // screen brightness
    mode: 'welcome',  // welcome | lock | session
    notif: 0,         // the notification sliding down
    press: null,      // { x, y, k }: a tap's ripple in screen pixels, k 0..1
    picked: 0,        // "Dark" chosen
    sent: 0,          // the card folding away into the running strip
    clock: '5:12',
    spin: 0,          // the running strip's spinner
  };

  function rr(x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }
  function bar(dark = true) {
    g.fillStyle = dark ? PAPER : '#1E2A28'; g.font = `500 44px ${SANS}`;
    g.fillText(state.clock, 60, 78);
    // wifi, signal, battery as simple shapes
    g.beginPath(); g.moveTo(735, 62); g.lineTo(765, 40); g.lineTo(765, 62); g.closePath(); g.fill();
    g.beginPath(); g.moveTo(700, 44); g.arc(705, 70, 30, -Math.PI * 0.75, -Math.PI * 0.25); g.lineTo(705, 70); g.closePath(); g.fill();
    rr(790, 38, 24, 40, 5); g.fill();
  }
  function welcome() {
    g.fillStyle = '#070F0E'; g.fillRect(0, 0, SW, SH);
    bar();
    g.strokeStyle = TEAL; g.lineWidth = 18;
    g.beginPath(); g.arc(SW / 2, 700, 140, 0, Math.PI * 2); g.stroke();
    g.fillStyle = PAPER; g.font = `600 96px ${SANS}`;
    const w = g.measureText('Porthole').width;
    g.fillText('P', SW / 2 - w / 2, 960);
    g.fillText('rthole', SW / 2 - w / 2 + g.measureText('Po').width, 960);
    g.strokeStyle = TEAL; g.lineWidth = 9;
    g.beginPath(); g.arc(SW / 2 - w / 2 + g.measureText('P').width + g.measureText('o').width / 2, 928, 28, 0, Math.PI * 2); g.stroke();
    g.fillStyle = QUIET; g.font = `400 44px ${SANS}`;
    const line = 'workstation · connected';
    g.fillText(line, SW / 2 - g.measureText(line).width / 2, 1060);
    g.fillStyle = TEAL; g.beginPath(); g.arc(SW / 2 - g.measureText(line).width / 2 - 34, 1045, 12, 0, Math.PI * 2); g.fill();
  }
  function lock() {
    // the wallpaper: the three of them, soft
    const gr = g.createLinearGradient(0, 0, 0, SH);
    gr.addColorStop(0, '#2E4E6B'); gr.addColorStop(0.55, '#E7A08A'); gr.addColorStop(1, '#F2C9A0');
    g.fillStyle = gr; g.fillRect(0, 0, SW, SH);
    g.fillStyle = 'rgba(255,255,255,0.9)'; g.font = `300 210px ${SANS}`;
    g.fillText(state.clock, SW / 2 - g.measureText(state.clock).width / 2, 560);
    g.font = `500 44px ${SANS}`;
    const d = 'Saturday, 3 October';
    g.fillText(d, SW / 2 - g.measureText(d).width / 2, 640);
    bar();
  }
  function session() {
    g.fillStyle = '#070F0E'; g.fillRect(0, 0, SW, SH);
    bar();
    // the header: back, the session's ring and title
    g.strokeStyle = PAPER; g.lineWidth = 7; g.lineCap = 'round';
    g.beginPath(); g.moveTo(88, 160); g.lineTo(58, 190); g.lineTo(88, 220); g.moveTo(58, 190); g.lineTo(110, 190); g.stroke();
    const running = state.sent > 0.5;
    g.strokeStyle = running ? TEAL : AMBER; g.lineWidth = 7;
    g.beginPath(); g.arc(175, 172, 24, 0, Math.PI * 2); g.stroke();
    g.fillStyle = PAPER; g.font = `600 50px ${SANS}`; g.fillText('Add dark mode to …', 222, 186);
    g.fillStyle = QUIET; g.font = `400 36px ${SANS}`; g.fillText('main · tmux work', 222, 236);
    // the feed, faint
    g.fillStyle = PAPER; g.font = `400 44px ${SANS}`;
    g.fillText('I\'ll check how the settings page', 60, 400);
    g.fillText('stores preferences first.', 60, 458);
    g.font = `400 38px ${MONO}`; g.fillStyle = QUIET;
    const rows = [['Read src/settings/Settings.tsx', '0.1s'], ['done', '142 lines'], ['Searched prefers-color-scheme', '0.4s'], ['done', '2 lines']];
    rows.forEach(([a, b], i) => { g.fillText(a, 110, 560 + i * 72); g.fillText(b, SW - 60 - g.measureText(b).width, 560 + i * 72); });
    // the question card, as the app draws it, folding away once it is answered
    const fold = out(clamp(state.sent));
    const cardY = 900, cardH = lerp(860, 150, fold);
    g.save();
    g.globalAlpha = 1;
    rr(40, cardY, SW - 80, cardH, 44);
    g.fillStyle = '#152523'; g.fill();
    g.lineWidth = 5; g.strokeStyle = TEAL; g.stroke();
    g.clip();
    if (fold < 0.95) {
      g.globalAlpha = 1 - fold;
      g.fillStyle = TEAL; g.font = `500 44px ${SANS}`; g.fillText('Claude is asking you', 96, cardY + 96);
      g.fillStyle = PAPER; g.font = `600 64px ${SANS}`; g.fillText('Theme', 96, cardY + 196);
      g.font = `400 52px ${SANS}`; g.fillText('Which theme should new', 96, cardY + 290); g.fillText('users get?', 96, cardY + 356);
      const opt = (i, title, sub, y) => {
        const lit = i === 1 ? clamp(state.picked) : 0;
        rr(96, y, SW - 192, 190, 34);
        g.fillStyle = lit ? `rgba(63,212,192,${0.12 + 0.75 * lit})` : '#0E1A19'; g.fill();
        g.lineWidth = 3; g.strokeStyle = lit ? TEAL : '#2A3B39'; g.stroke();
        g.fillStyle = lit > 0.6 ? '#07100F' : PAPER; g.font = `500 56px ${SANS}`; g.fillText(title, 140, y + 84);
        g.fillStyle = lit > 0.6 ? '#0B2A26' : QUIET; g.font = `400 42px ${SANS}`; g.fillText(sub, 140, y + 146);
      };
      opt(1, '1. Dark', 'Easy on the eyes at night', cardY + 420);
      opt(2, '2. Light', 'As it looks today', cardY + 636);
    }
    if (fold > 0.05) {
      g.globalAlpha = fold;
      g.fillStyle = TEAL; g.font = `500 46px ${SANS}`; g.fillText('✓  You answered: Dark', 96, cardY + 90);
    }
    g.restore();
    // the running strip, once it is on its way again
    if (state.sent > 0.3) {
      const k = clamp((state.sent - 0.3) / 0.5);
      g.save(); g.globalAlpha = k;
      rr(40, 1100, SW - 80, 150, 34); g.fillStyle = '#122120'; g.fill();
      g.strokeStyle = TEAL; g.lineWidth = 7;
      for (let s = 0; s < 8; s++) { const a = s / 8 * Math.PI * 2 + state.spin; g.beginPath(); g.moveTo(130 + Math.cos(a) * 12, 1175 + Math.sin(a) * 12); g.lineTo(130 + Math.cos(a) * 30, 1175 + Math.sin(a) * 30); g.stroke(); }
      g.fillStyle = PAPER; g.font = `500 48px ${SANS}`; g.fillText('Working…', 190, 1170);
      g.fillStyle = QUIET; g.font = `400 36px ${SANS}`; g.fillText('adding the dark theme', 190, 1222);
      g.restore();
    }
    // the message box at the bottom
    rr(40, SH - 240, SW - 80, 130, 65); g.fillStyle = '#122120'; g.fill();
    g.fillStyle = QUIET; g.font = `400 44px ${SANS}`; g.fillText('Message Add dark mode…', 110, SH - 160);
  }
  function draw() {
    const s = state;
    g.save();
    if (s.mode === 'welcome') welcome();
    else if (s.mode === 'lock') lock();
    else session();
    // the notification: the app's own, sliding down from the top
    if (s.notif > 0.001) {
      const k = out(clamp(s.notif));
      const w = SW - 60, h = w * notify.height / notify.width;
      const y = lerp(-h - 20, 110, k);
      g.save();
      g.shadowColor = 'rgba(0,0,0,0.45)'; g.shadowBlur = 40; g.shadowOffsetY = 12;
      rr(30, y, w, h, 58); g.fillStyle = '#1B1F1E'; g.fill();
      g.restore();
      g.drawImage(notify, 30, y, w, h);
    }
    if (s.press && s.press.k > 0 && s.press.k < 1) {
      const R = lerp(20, 220, out(s.press.k));
      g.fillStyle = `rgba(232,240,238,${0.28 * (1 - s.press.k)})`;
      g.beginPath(); g.arc(s.press.x, s.press.y, R, 0, Math.PI * 2); g.fill();
    }
    // brightness
    if (s.wake < 1) { g.fillStyle = `rgba(0,0,0,${1 - clamp(s.wake)})`; g.fillRect(0, 0, SW, SH); }
    g.restore();
    tex.needsUpdate = true;
  }
  draw();
  return { root, model, screen, state, draw, box };
}
