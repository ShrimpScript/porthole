// Left Running: the director. Every frame is a pure function of t: which set is on screen,
// where the camera is, what everyone is doing, and what is laid over the picture. The times
// come from ../timeline.json, which the sound reads too.
import * as THREE from 'three';
import { clamp, lerp, span, smooth, inOut, out, out5, inn, back, bump, keys, noise, fbm, pulse, mesh, sphere, mat, group } from './kit.js';
import { makeStage } from './stage.js';
import { makeHome, DESK, DOOR, ROOM, FRONT_WINDOW, BED } from './home.js';
import { makePark } from './park.js';
import { makeComputer } from './computer.js';
import { makeHuman, POSES, blend, walk } from './human.js';
import { makeDog } from './dog.js';
import { makePhone } from './phone.js';
import { makePortal } from './porthole.js';
import { FACE_W, FACE_H } from './face.js';
import { toonify, paperCanvas, toothCanvas, FLAT, DRAWINGS_PER_SECOND } from './drawn.js';

const W = 1920, H = 1080;
const BASE = '../../../';
// ?look=drawn: the same film drawn by hand (src/drawn.js); otherwise lit, soft 3D
const DRAWN = new URLSearchParams(location.search).get('look') === 'drawn';
// ?inspect=x,y,z,tx,ty,tz[,fov]: a camera of one's own, for checking contacts and clearances
const INSPECT = new URLSearchParams(location.search).get('inspect')?.split(',').map(Number);
const INSPECT_FLASH = new URLSearchParams(location.search).has('flash');
let TL, E, stage, home, park, comp, hH, dH, hP, dP, phoneH, phoneP, portal, leash, fuzz, ballP;
let homeCam, parkCam, secondCam, defaults;
const V = (x, y, z) => new THREE.Vector3(x, y, z);
const tmp = new THREE.Vector3();

// ---------------------------------------------------------------------------------------------
async function build() {
  TL = await (await fetch('timeline.json')).json();
  E = TL.events;
  window.FPS = DRAWN ? DRAWINGS_PER_SECOND : TL.fps;
  stage = makeStage(W, H, +(new URLSearchParams(location.search).get('scale') || 1), { drawn: DRAWN });
  home = makeHome(stage.renderer);
  park = makePark(stage.renderer);

  comp = makeComputer();
  comp.root.position.set(DESK.x, DESK.top, DESK.z - 0.06);
  comp.keyboard.position.set(DESK.x, DESK.top, DESK.z + 0.24);
  comp.mouse.position.set(DESK.x + 0.33, DESK.top, DESK.z + 0.24);
  home.scene.add(comp.root, comp.keyboard, comp.mouse);

  hH = makeHuman(); home.scene.add(hH.root);
  dH = makeDog(); home.scene.add(dH.root);
  hP = makeHuman(); park.scene.add(hP.root);
  dP = makeDog(); park.scene.add(dP.root);
  ballP = dP.ball; park.scene.add(ballP);
  defaults = { h: structuredClone(hH.pose), d: structuredClone(dH.pose), c: structuredClone(comp.pose), f: structuredClone(comp.face.state) };

  [phoneH, phoneP] = await Promise.all([makePhone(BASE), makePhone(BASE)]);
  home.scene.add(phoneH.root);
  hP.spine.add(phoneP.root);

  portal = makePortal(stage);
  park.scene.add(portal.root);

  // the leash: a soft rope rebuilt each frame from a few points
  leash = new THREE.Mesh(new THREE.BufferGeometry(), mat('#E0604F', { roughness: 0.6 }));
  leash.castShadow = true;
  home.scene.add(leash);
  // a tumbleweed of dust, for the waiting
  fuzz = new THREE.Mesh(new THREE.IcosahedronGeometry(0.035, 3), mat('#B9B1A6', { physical: true, roughness: 1, sheen: 1, sheenColor: new THREE.Color('#ffffff') }));
  { const p = fuzz.geometry.attributes.position; for (let i = 0; i < p.count; i++) { tmp.fromBufferAttribute(p, i); tmp.multiplyScalar(1 + 0.35 * noise(i * 0.37, 3)); p.setXYZ(i, tmp.x, tmp.y, tmp.z); } fuzz.geometry.computeVertexNormals(); }
  fuzz.castShadow = true;
  home.scene.add(fuzz);

  homeCam = new THREE.PerspectiveCamera(32, W / H, 0.02, 120);
  parkCam = new THREE.PerspectiveCamera(32, W / H, 0.02, 200);
  secondCam = new THREE.PerspectiveCamera(32, W / H, 0.02, 200);
  if (DRAWN) {
    toonify(home.scene); toonify(park.scene);
    for (const c of [homeCam, parkCam, secondCam]) c.layers.enable(FLAT);
  }
  overlayInit();
  return TL.duration;
}

// ---- small helpers ---------------------------------------------------------------------------
function aim(cam, pos, target, fov, t = 0, shake = 0.004) {
  cam.position.set(...pos);
  // a hand on the camera: a slow, small drift, never still
  cam.position.x += fbm(t * 0.35, 11) * shake; cam.position.y += fbm(t * 0.31, 12) * shake * 0.7;
  cam.lookAt(...target);
  cam.fov = fov; cam.updateProjectionMatrix();
  cam.clearViewOffset();
}
function track(cam, t, list, fov, shake) {
  const pos = keys(t, list.map((k) => [k[0], k[1], k[4] || inOut]));
  const tgt = keys(t, list.map((k) => [k[0], k[2], k[4] || inOut]));
  const f = keys(t, list.map((k) => [k[0], k[3] ?? fov, k[4] || inOut]));
  aim(cam, pos, tgt, f, t, shake);
}
const beatHit = (t, from = 2.0) => { const ph = ((t - from) * 2) % 1; return Math.exp(-ph * 7); };
const reset = (target, src) => { for (const k in src) target[k] = Array.isArray(src[k]) ? src[k].slice() : src[k]; };
function hops(t, times, h = 0.06, d = 0.36) {
  // each hop: crouch (anticipation), up, land with squash
  let y = 0, sq = 0;
  for (const t0 of times) {
    const u = (t - t0) / d;
    if (u > -0.35 && u < 0) sq = Math.max(sq, 0.6 * Math.sin((u + 0.35) / 0.35 * Math.PI / 2));
    if (u >= 0 && u <= 1) { y = Math.max(y, h * Math.sin(u * Math.PI)); sq = Math.min(sq, -0.7 * Math.sin(u * Math.PI)); }
    if (u > 1 && u < 1.5) sq = Math.max(sq, 0.7 * Math.sin((u - 1) / 0.5 * Math.PI) * (1 - (u - 1) / 0.5));
  }
  return { y, sq };
}

// ---- home: everything in the room at time t ----------------------------------------------------
const CHAIR_Z = DESK.z + 0.68;
function screenPoint(u, v) {
  // a point on the computer's glass, u, v in canvas pixels
  const gw = 0.51, gh = gw * FACE_H / FACE_W;
  return comp.glass.localToWorld(V((u / FACE_W - 0.5) * gw, (0.5 - v / FACE_H) * gh, 0.001));
}

function computerNow(t) {
  const c = comp.pose, f = comp.face.state;
  reset(c, defaults.c); reset(f, defaults.f);
  // ---- the hook: a cursor blinking, then a second, then a face that wakes and stretches ----
  if (t < 2.0) {
    f.eyes = 1 + out(span(t, E.secondEye, E.secondEye + 0.15));
    f.prompt = 1 - span(t, E.secondEye - 0.1, E.secondEye + 0.25);
    const blinkOff = [0.45, 0.95, 1.45].some((b) => t >= b + 0.25 && t < b + 0.5);
    f.cursor = t < E.secondEye && blinkOff ? 0 : 1;
    f.lookX = keys(t, [[1.7, 0], [1.8, -0.8, out5], [2.0, -0.8], [2.05, 0.7, out5], [2.3, 0.7]]);
    f.logAlpha = 0.0;
  }
  if (t >= 2.0 && t < 4.2) {
    const s = span(t, 2.0, 3.2);
    const st = Math.sin(s * Math.PI);
    c.elbow = 0.28 * st; c.lean = -0.12 * st; c.tilt = -0.2 * st;
    c.squash = -0.7 * st + 0.4 * bump(t, 3.2, 3.55);
    f.open = lerp(1, 0.12, clamp(st * 1.6)); f.happy = clamp(st * 2.5);
    f.smile = 0.8 * bump(t, 3.1, 4.1);
    f.lookX = keys(t, [[2.05, 0.7], [2.4, 0]]);
    f.logAlpha = 0.22 * span(t, 3.2, 3.8);
    f.scroll = (t - 3.2) * 1.5;
  }
  // ---- together: bobbing to the beat while they work ---------------------------------------
  if (t >= 4.2 && t < 11.6) {
    const hit = beatHit(t);
    c.tilt = 0.06 * hit; c.squash = 0.12 * hit;
    f.scroll = 1.5 + (t - 4.2) * 3.2;
    f.lookX = 0.15 + 0.1 * Math.sin(t * 1.3); f.lookY = 0.25;
    f.happy = 0;
    // the tests pass: a hop and a line on the glass
    const hp = hops(t, [E.tick], 0.05, 0.34);
    c.hop = hp.y; c.squash += hp.sq;
    f.done = pulse(t, E.tick, E.tick + 1.4, 0.15); f.doneText = '✓  12 passed';
    f.happy = lerp(f.happy, 1, pulse(t, E.tick, E.tick + 1.0, 0.1));
    // the fist bump: it leans into the person's fist and turns its corner to meet it
    const reach = smooth(span(t, E.fistRaise + 0.2, E.fistBump)) * (1 - smooth(span(t, E.fistBump + 0.35, E.fistBump + 0.8)));
    c.lean += 0.22 * reach; c.turn = 0.12 + 0.3 * reach; c.roll = -0.12 * reach; c.tilt += 0.05 * reach;
    c.squash += 0.5 * bump(t, E.fistBump, E.fistBump + 0.25);
    f.lookX = lerp(f.lookX, 0.8, reach); f.happy = lerp(f.happy, 1, smooth(span(t, E.fistBump - 0.1, E.fistBump + 0.1)) * (1 - span(t, 11.2, 11.6)));
    f.wide = 0.3 * bump(t, E.fistRaise, E.fistRaise + 0.5);
  }
  // ---- one day: the dog, the look, the thought, the nudge -----------------------------------
  if (t >= 11.6 && t < 22.0) {
    f.scroll = 26 + (t - 11.6) * 1.2;
    f.happy = 0;
    // it hears the tag jingle and looks at the door
    f.lookX = keys(t, [[11.9, 0.1], [12.2, 0.95, out5], [13.3, 0.95], [13.55, 0.1, out5], [17.5, 0.1], [17.65, 0.55, out5], [18.7, 0.55], [18.85, 0.0, out5], [19.6, 0], [19.9, 0.7], [21.6, 0.95]]);
    f.lookY = keys(t, [[13.4, 0], [13.6, -0.2], [17.5, -0.2], [17.65, 0.9, out5], [18.7, 0.9], [18.85, 0, out5]]);
    // watching the person hesitate: head tilts, a slow blink of sympathy
    const hes = smooth(span(t, 14.0, 14.5)) * (1 - smooth(span(t, 17.3, 17.6)));
    c.roll = 0.12 * hes; c.tilt = 0.05 * hes; f.sad = 0.35 * hes; f.happy = lerp(f.happy, 0, hes);
    // the idea, and the nudge: lean down to the phone and push it across to them
    const idea = bump(t, 17.45, 17.75);
    f.wide = 0.6 * idea; c.hop = 0.02 * idea;
    const push = keys(t, [[17.8, 0], [18.15, 1, out], [18.45, 1], [18.75, 0]]);
    c.lean += 0.34 * push; c.elbow -= 0.2 * push; c.tilt += 0.34 * push; c.turn += 0.42 * push; c.roll -= 0.1 * push;
    // "go on": two nods and happy eyes, then it watches them go and waves
    const nod = bump(t, E.nods[0], E.nods[0] + 0.3) + bump(t, E.nods[1], E.nods[1] + 0.3);
    c.tilt += 0.24 * nod; c.squash += 0.2 * nod;
    f.happy = lerp(f.happy, 1, smooth(span(t, 18.9, 19.1)) * (1 - smooth(span(t, 21.7, 22.0))));
    f.smile = 0.8 * smooth(span(t, 18.9, 19.1)) * (1 - smooth(span(t, 19.6, 19.9)));
    c.turn += 0.35 * smooth(span(t, 19.8, 20.4));
    const wave = smooth(span(t, 20.6, 20.8)) * (1 - smooth(span(t, 21.5, 21.8)));
    c.roll += 0.14 * Math.sin((t - 20.6) * 14) * wave;
  }
  // ---- alone: it works and hums; then a question, and the old waiting pose; then it sends it --
  if (t >= 22.0 && t < 27.6) {
    const hit = beatHit(t, 22.1);
    const hum = pulse(t, 22.1, 23.4, 0.2);
    c.tilt = 0.05 * hit * hum; c.roll = 0.05 * Math.sin((t - 22.1) * Math.PI) * hum;
    f.scroll = 40 + (t - 22) * 2.5 * (1 - span(t, 23.4, 23.6));
    f.happy = smooth(span(t, 22.05, 22.2)) * (1 - smooth(span(t, 23.35, 23.5)));
    f.ask = out(span(t, E.ask, E.ask + 0.45));
    f.wait = smooth(span(t, E.amber, E.amber + 0.3));
    f.wide = 0.5 * bump(t, E.ask, E.ask + 0.6);
    f.lookX = keys(t, [[23.6, 0], [23.8, 0, out5], [24.1, 0.95, out5], [25.4, 0.95], [25.5, 0, out5]]);
    f.lookY = keys(t, [[23.5, 0], [23.6, 0.6, out5], [23.95, 0.6], [24.1, 0, out5]]);
    // the droop: it starts to settle into waiting, as it always has...
    const droop = smooth(span(t, E.droop[0], E.droop[1])) * (1 - out5(span(t, E.perk, E.perk + 0.18)));
    c.lean += 0.3 * droop; c.elbow -= 0.35 * droop; c.tilt += 0.3 * droop; c.turn += 0.25 * droop;
    f.sad = 0.75 * droop; f.open = lerp(1, 0.55, droop);
    // ...then remembers: perk up, and send the question out to the phone
    f.wide = Math.max(f.wide, 1 * bump(t, E.perk, E.perk + 0.7));
    const hp = hops(t, [E.send - 0.1], 0.04, 0.3);
    c.hop = hp.y; c.squash += hp.sq;
    f.pulse = span(t, E.send, E.send + 0.8);
    f.happy = Math.max(f.happy, pulse(t, E.send + 0.1, 27.6, 0.12));
  }
  // ---- seen through the porthole: waiting, amber; then the answer lights up ------------------
  if (t >= 27.6 && t < 38.5) {
    f.ask = 1; f.wait = 1; f.scroll = 44;
    f.lookY = keys(t, [[36.2, 0.2], [36.8, -0.35]]);
    f.sad = 0.35 * (1 - smooth(span(t, 36.4, 37.0)));
    f.wide = 0.5 * smooth(span(t, 36.5, 37.0));
    f.picked = smooth(span(t, E.answerArrives, E.answerArrives + 0.25));
    f.happy = smooth(span(t, 37.85, 38.05));
    c.tilt = -0.08 * smooth(span(t, 36.4, 37.0));
    c.squash = 0.4 * bump(t, E.answerArrives, E.answerArrives + 0.3);
  }
  // ---- the answer: it turns dark, and it is happy ------------------------------------------
  if (t >= 38.5 && t < 46.5) {
    f.dark = inOut(span(t, E.flip, E.flip + 0.65));
    f.wait = 1 - smooth(span(t, E.flip, E.flip + 0.4));
    f.ask = 1 - inOut(span(t, E.flip + 0.1, E.flip + 0.55));
    f.picked = 1;
    f.wide = 0.8 * bump(t, E.flip, E.flip + 0.6);
    f.happy = smooth(span(t, E.flip + 0.35, E.flip + 0.6));
    f.smile = 0.9 * smooth(span(t, E.flip + 0.4, E.flip + 0.7));
    f.scroll = 44 + (t - 38.5) * 6 * (1 - span(t, 41, 42));
    const hp = hops(t, E.joyHops, 0.07, 0.36);
    c.hop = hp.y; c.squash = hp.sq;
    c.tilt = -0.05 * bump(t, 39.0, 40.4);
    f.done = smooth(span(t, E.done, E.done + 0.3)); f.doneText = '✓  Dark mode is in';
    const wig = smooth(span(t, 41.1, 41.3)) * (1 - smooth(span(t, 42.2, 42.5)));
    c.roll = 0.12 * Math.sin((t - 41.1) * 16) * wig; c.turn = 0.15 * Math.sin((t - 41.1) * 8) * wig;
  }
  // ---- dusk: home again ----------------------------------------------------------------------
  if (t >= 46.5) {
    f.dark = 1; f.logAlpha = 0.14; f.scroll = 60 + (t - 46.5) * 0.4;
    f.happy = 0;
    f.lookX = keys(t, [[46.8, 0], [47.0, 0.9, out5], [48.6, 0.9], [48.9, 0.55]]);
    c.turn = 0.5 * smooth(span(t, E.turnToHuman - 0.1, E.turnToHuman + 0.4));
    f.happy = smooth(span(t, E.turnToHuman, E.turnToHuman + 0.15));
    f.wide = 0.5 * bump(t, 46.8, 47.4);
    // two pats on the head: each one squashes it, eyes shut happily
    const pats = bump(t, E.pat[0], E.pat[0] + 0.3) + bump(t, E.pat[0] + 0.42, E.pat[0] + 0.72);
    c.squash = 0.45 * pats; c.tilt = 0.08 * pats;
    f.heart = out(span(t, E.heart, E.heart + 0.5)) * (1 - span(t, 52.5, 53.2));
    f.smile = 0.7 * smooth(span(t, 49.4, 49.8));
  }
  comp.apply();
}

function computerFlash(t) {
  // the last time: night, the lamp off, nobody home; a question on the glass since the afternoon
  const c = comp.pose, f = comp.face.state;
  reset(c, defaults.c); reset(f, defaults.f);
  f.ask = 1; f.wait = 1; f.sad = 0.85; f.open = 0.5; f.lookX = 0.6; f.lookY = 0.3; f.scroll = 44;
  const wah = smooth(span(t, E.flashWah[0], E.flashWah[1]));
  c.lean = 0.3 + 0.08 * wah; c.elbow = -0.38 - 0.1 * wah; c.tilt = 0.34 + 0.16 * wah; c.turn = 0.18;
  c.squash = 0.35 * bump(t, E.flashWah[1] - 0.25, E.flashWah[1] + 0.2);
  f.open = lerp(0.5, 0.3, wah);
  comp.apply();
  comp.glow.intensity = 0.9;
}

function humanHome(t) {
  const p = hH.pose;
  reset(p, defaults.h);
  hH.root.visible = true;
  let chairYaw = 0;
  if (t < E.standUp) {
    // seated at the desk
    p.x = DESK.x; p.z = CHAIR_Z; p.yaw = Math.PI;
    blend(p, [POSES.sit, 1], [POSES.type, 1]);
    p.hipsY = -0.26;
    p.mouth = 'smile';
    p.eyeY = -0.2;
    // typing: small quick motions of both hands
    const typing = pulse(t, 3.6, 7.8, 0.25);
    p.elbowL += 0.07 * Math.sin(t * 23) * typing; p.elbowR += 0.07 * Math.sin(t * 19 + 1) * typing;
    p.armL[0] += 0.04 * Math.sin(t * 17) * typing; p.armR[0] += 0.04 * Math.sin(t * 21 + 2) * typing;
    p.headPitch = 0.05 + 0.03 * Math.sin(t * 2) * typing;
    p.twist = 0.03 * Math.sin(t * 2.1);
    // hands resting, not typing: back a little
    const rest = 1 - typing;
    p.armL[0] += 0.18 * rest; p.armR[0] += 0.18 * rest; p.elbowL -= 0.2 * rest; p.elbowR -= 0.2 * rest; p.lean -= 0.05 * rest;
    // the tests pass: a little cheer
    const cheer = pulse(t, E.tick, E.tick + 0.9, 0.12);
    p.mouth = cheer > 0.3 ? 'grin' : p.mouth; p.mouthOpen = 0.5 * cheer; p.browUp = cheer;
    // the fist bump
    const reach = smooth(span(t, E.fistRaise, E.fistBump - 0.05)) * (1 - smooth(span(t, E.fistBump + 0.3, E.fistBump + 0.9)));
    p.armR = p.armR.map((a, i) => lerp(a, [-1.72, 0.1, -0.12][i], reach));
    p.elbowR = lerp(p.elbowR, 0.12, reach);
    p.lean += 0.22 * reach; p.twist -= 0.12 * reach;
    const hit = bump(t, E.fistBump, E.fistBump + 0.25);
    p.armR[0] += 0.12 * hit;
    if (t > E.fistBump - 0.3 && t < 11.4) { p.mouth = 'grin'; p.mouthOpen = 0.8; p.browUp = 0.6; }
    // a happy stretch after: hands behind the head
    const lounge = smooth(span(t, 10.5, 10.9)) * (1 - smooth(span(t, 11.9, 12.3)));
    p.armL = p.armL.map((a, i) => lerp(a, [-2.6, 0.2, 0.55][i], lounge));
    p.armR = p.armR.map((a, i) => lerp(a, [-2.6, 0.2, 0.55][i], lounge));
    p.elbowL = lerp(p.elbowL, 2.3, lounge); p.elbowR = lerp(p.elbowR, 2.3, lounge);
    p.lean -= 0.18 * lounge; p.headPitch -= 0.12 * lounge;
    // the dog: a look toward the door, a swivel to see it, then back to the computer
    const toDog = smooth(span(t, 12.2, 12.6)) * (1 - smooth(span(t, 13.1, 13.3)));
    p.headYaw = -0.6 * toDog;
    const swivel = keys(t, [[13.1, 0], [13.45, -1.0, out], [13.95, -1.0], [14.35, -0.08, inOut]]);
    p.yaw = Math.PI + swivel; chairYaw = swivel;
    const handsDown = smooth(span(t, 12.8, 13.2));
    p.armL[0] = lerp(p.armL[0], 0.1, handsDown); p.armR[0] = lerp(p.armR[0], 0.1, handsDown);
    p.elbowL = lerp(p.elbowL, 0.9, handsDown); p.elbowR = lerp(p.elbowR, 0.9, handsDown);
    p.lean = lerp(p.lean, 0.05, handsDown);
    if (t > 13.2 && t < 14.1) { p.mouth = 'grin'; p.mouthOpen = 0.7; p.browUp = 0.8; p.headPitch = 0.15; }
    // the hesitation: back to the computer, and the face falls a little
    const hes = smooth(span(t, 14.0, 14.4)) * (1 - smooth(span(t, 18.5, 18.8)));
    p.browSad = 0.9 * hes; p.headPitch += 0.08 * hes;
    if (hes > 0.5) { p.mouth = 'oh'; }
    const thought = smooth(span(t, 14.7, 15.0)) * (1 - smooth(span(t, 17.2, 17.5)));
    p.eyeX = 0.8 * thought; p.eyeY = lerp(-0.2, 0.9, thought); p.headRoll = 0.1 * thought; p.headPitch -= 0.12 * thought;
    // the phone comes across the desk: eyes follow it, then a grin
    const follow = smooth(span(t, 17.8, 18.1)) * (1 - smooth(span(t, 18.7, 18.9)));
    p.eyeY = lerp(p.eyeY, -1, follow); p.headPitch += 0.18 * follow;
    if (t > 18.6) { p.browUp = 0.9 * bump(t, 18.6, 19.2) + 0.3; p.mouth = t < 18.85 ? 'oh' : 'grin'; p.mouthOpen = 0.8; p.browSad = 0; }
    p.headPitch -= 0.2 * (bump(t, E.nods[0] + 0.05, E.nods[0] + 0.3) + bump(t, E.nods[1] + 0.05, E.nods[1] + 0.3)) * -1;
  } else if (t < 22.0) {
    // up, the phone into the pocket, and out of the door with the dog
    const up = smooth(span(t, E.standUp, E.standUp + 0.35));
    const path = [[E.standUp + 0.3, [DESK.x, CHAIR_Z]], [20.35, [DESK.x + 0.62, -1.2]], [21.0, [DOOR.x - 0.1, -1.55]], [21.55, [DOOR.x - 0.04, -2.55]], [22.0, [DOOR.x, -3.3]]];
    const [x, z] = keys(t, path.map(([k, v]) => [k, v, (u) => u]));
    const [x2, z2] = keys(t + 0.08, path.map(([k, v]) => [k, v, (u) => u]));
    p.x = x; p.z = z;
    const moving = span(t, E.standUp + 0.3, E.standUp + 0.45);
    p.yaw = moving > 0 && Math.hypot(x2 - x, z2 - z) > 1e-4 ? lerp(Math.PI - 0.9, Math.atan2(x2 - x, z2 - z), moving) : Math.PI - 0.9 * up;
    chairYaw = -0.9 * up;
    blend(p, [POSES.sit, 1 - up], [POSES.stand, up]);
    p.hipsY = lerp(-0.26, 0, up);
    p.lean = 0.2 * bump(t, E.standUp, E.standUp + 0.4);
    walk(p, (t - E.standUp - 0.3) * 2.5, span(t, E.standUp + 0.3, E.standUp + 0.5), 0.55);
    p.mouth = 'grin'; p.mouthOpen = 0.6;
    // a wave back at the computer on the way to the door: the arm straight up, inside the
    // doorway's width, and down again before they go through
    const wave = smooth(span(t, E.wave - 0.2, E.wave + 0.05)) * (1 - smooth(span(t, 21.1, 21.3)));
    p.armR = p.armR.map((a, i) => lerp(a, [-2.95, 0.15, 0.22][i], wave)); p.elbowR = lerp(p.elbowR, 0.35 + 0.3 * Math.sin((t - 20.6) * 15), wave);
    p.headYaw = lerp(p.headYaw, -0.9, wave); p.twist = lerp(p.twist, -0.25, wave);
    if (t > 21.7) hH.root.visible = false;
  } else if (t >= 46.5) {
    // home at dusk: in through the door, across to the desk, a pat on the head
    const path = [[E.walkIn[0], [DOOR.x, -2.6]], [48.0, [DOOR.x - 0.15, -1.45]], [48.5, [0.45, -1.13]], [E.walkIn[1], [DESK.x + 0.28, DESK.z + 0.53]]];
    const [x, z] = keys(t, path.map(([k, v]) => [k, v, (u) => smooth(u)]));
    p.x = x; p.z = z;
    const walking = span(t, E.walkIn[0], E.walkIn[0] + 0.2) * (1 - span(t, E.walkIn[1] - 0.2, E.walkIn[1]));
    const [x2, z2] = keys(t + 0.1, path.map(([k, v]) => [k, v, (u) => smooth(u)]));
    const face = Math.atan2(DESK.x - x, DESK.z - 0.06 - z);
    p.yaw = walking > 0.3 && Math.hypot(x2 - x, z2 - z) > 1e-4 ? Math.atan2(x2 - x, z2 - z) : face;
    p.yaw = lerp(p.yaw, face, smooth(span(t, E.walkIn[1] - 0.3, E.walkIn[1] + 0.1)));
    blend(p, [POSES.stand, 1]);
    walk(p, (t - E.walkIn[0]) * 2.4, walking, 0.5);
    p.mouth = 'smile';
    // the pat: the left hand up onto the top of the monitor, two soft taps
    const reach = smooth(span(t, E.pat[0] - 0.35, E.pat[0])) * (1 - smooth(span(t, 50.6, 51.1)));
    const tap = bump(t, E.pat[0], E.pat[0] + 0.3) + bump(t, E.pat[0] + 0.42, E.pat[0] + 0.72);
    p.armR = p.armR.map((a, i) => lerp(a, [-2.05 + 0.15 * tap, -0.2, -0.35][i], reach));
    p.elbowR = lerp(p.elbowR, 0.5, reach);
    p.lean = 0.26 * reach;
    if (reach > 0.4) { p.mouth = 'grin'; p.mouthOpen = 0.5; p.blink = 0.7 * smooth(span(t, 49.6, 49.8)) * (1 - span(t, 50.4, 50.6)); }
    if (t < E.walkIn[0] - 0.05) hH.root.visible = false;
  } else hH.root.visible = false;
  // a blink every few seconds, always
  const bl = ((t + 0.7) % 3.3);
  if (bl < 0.12) p.blink = Math.max(p.blink, Math.sin(bl / 0.12 * Math.PI));
  hH.apply();
  handsHome(t);
  home.seat.rotation.y = chairYaw;
  // the chair stays where it was pushed back, and turned, until they sit again
  home.chair.position.z = CHAIR_Z + 0.06 + 0.25 * smooth(span(t, E.standUp, E.standUp + 0.6));
  if (t >= E.standUp + 0.35) home.seat.rotation.y = -0.9;
}

// Hands where they belong: on the keys while typing, resting by the keyboard, a fist on the
// computer's corner, a hand on its head. Blended with the posed arms by how much each applies.
function handsHome(t) {
  const p = hH.pose;
  if (!hH.root.visible) return;
  const kb = comp.keyboard.position;
  const posed = { armL: p.armL.slice(), armR: p.armR.slice(), elbowL: p.elbowL, elbowR: p.elbowR };
  const mixArms = (w, key) => {
    if (w <= 0) { p['arm' + key] = posed['arm' + key]; p['elbow' + key] = posed['elbow' + key]; return; }
    p['arm' + key] = posed['arm' + key].map((a, i) => lerp(a, p['arm' + key][i], w));
    p['elbow' + key] = lerp(posed['elbow' + key], p['elbow' + key], w);
  };
  if (t < E.standUp) {
    // on the keyboard: typing or resting; off it for the bump, the lounge and the dog
    const typing = pulse(t, 3.6, 7.8, 0.25);
    const off = Math.max(smooth(span(t, 10.45, 10.8)) * (1 - smooth(span(t, 17.6, 18.0))), smooth(span(t, 12.7, 13.1)) * (1 - smooth(span(t, 17.6, 18.0))));
    const onKeys = clamp(1 - off);
    const tapL = typing * 0.012 * Math.max(0, Math.sin(t * 23)), tapR = typing * 0.012 * Math.max(0, Math.sin(t * 19 + 1));
    const kx = (dx) => typing * 0.02 * Math.sin(t * 3.1 + dx);
    const Lw = V(kb.x - 0.1 + kx(0), kb.y + 0.074 + tapL, kb.z + 0.02 + (1 - typing) * 0.07);
    const Rw = V(kb.x + 0.1 + kx(2), kb.y + 0.074 + tapR, kb.z + 0.02 + (1 - typing) * 0.07);
    hH.reach(1, Lw); mixArms(onKeys * (t < 3.3 ? smooth(span(t, 2.6, 3.3)) : 1), 'L');
    hH.reach(-1, Rw);
    const bump = smooth(span(t, E.fistRaise, E.fistBump - 0.08)) * (1 - smooth(span(t, E.fistBump + 0.35, E.fistBump + 0.9)));
    const handR = onKeys * (t < 3.3 ? smooth(span(t, 2.6, 3.3)) : 1) * (1 - bump);
    if (bump > 0) {
      const corner = comp.head.localToWorld(V(0.3, -0.06, 0.06 + 0.05 * (1 - bump)));
      const keysArm = { a: p.armR.slice(), e: p.elbowR };
      hH.reach(-1, corner);
      const cA = p.armR.slice(), cE = p.elbowR;
      p.armR = posed.armR.map((a, i) => lerp(lerp(a, keysArm.a[i], handR / Math.max(1e-3, 1 - bump)), cA[i], bump));
      p.elbowR = lerp(lerp(posed.elbowR, keysArm.e, handR / Math.max(1e-3, 1 - bump)), cE, bump);
    } else mixArms(handR, 'R');
  } else if (t >= 46.5) {
    const reachK = smooth(span(t, E.pat[0] - 0.35, E.pat[0])) * (1 - smooth(span(t, 50.6, 51.1)));
    if (reachK > 0) {
      const tap = bump(t, E.pat[0], E.pat[0] + 0.3) + bump(t, E.pat[0] + 0.42, E.pat[0] + 0.72);
      // the near corner of its top, a hand's width in from the edge
      hH.reach(-1, comp.head.localToWorld(V(0.17, 0.235 + 0.03 * tap, -0.005)));
      mixArms(reachK, 'R');
    }
  }
  hH.apply();
}

function dogHome(t) {
  const p = dH.pose;
  reset(p, defaults.d);
  p.t = t;
  dH.root.visible = true;
  let inMouth = false, leashOnFloor = null;
  if (t < 11.2 || (t > 22.0 && t < 46.9)) { dH.root.visible = false; }
  else if (t < 19.3) {
    // in through the door with the leash, a trot to the person, a sit, a woof
    const path = [[11.3, [DOOR.x, -2.9]], [12.1, [DOOR.x - 0.1, -1.75]], [E.dogEnter[1], [0.3, -0.86]]];
    const [x, z] = keys(t, path.map(([k, v]) => [k, v, (u) => u]));
    const [x2, z2] = keys(t + 0.05, path.map(([k, v]) => [k, v, (u) => u]));
    p.x = x; p.z = z;
    const moving = 1 - span(t, E.dogEnter[1] - 0.15, E.dogEnter[1]);
    p.yaw = moving > 0.5 && Math.hypot(x2 - x, z2 - z) > 1e-5 ? Math.atan2(x2 - x, z2 - z) : lerp(Math.atan2(DESK.x - x, CHAIR_Z - z), -2.1, 0);
    if (t > E.dogEnter[1]) p.yaw = Math.atan2(DESK.x - 0.3, CHAIR_Z + 0.86) * 1;
    dH.gait((t - 11.3) * 2.6, moving);
    p.sit = smooth(span(t, E.dogEnter[1], E.dogEnter[1] + 0.25));
    p.wag = 0.6 + 0.4 * p.sit; p.wagSpeed = 16;
    p.tongue = t > E.leashDrop ? 1 : 0;
    p.headPitch = -0.35 * bump(t, E.boof, E.boof + 0.3) + 0.1;
    p.headTilt = 0.35 * smooth(span(t, 13.7, 14.0)) * (1 - smooth(span(t, 18.9, 19.2)));
    p.earUp = 0.6 * bump(t, E.boof, E.boof + 0.5);
    inMouth = t < E.leashDrop;
    if (!inMouth) leashOnFloor = [0.2, -1.02];
    // it watches the phone slide, then heads down for the leash
    p.headYaw = 0.3 * smooth(span(t, 17.9, 18.4)) * (1 - span(t, 18.8, 19.0));
  } else if (t <= 22.0) {
    // leash up again, and out of the door ahead of the person
    const pick = smooth(span(t, 19.3, 19.55)) * (1 - smooth(span(t, 19.55, 19.8)));
    const path = [[19.8, [0.3, -0.86]], [20.6, [DOOR.x - 0.05, -1.5]], [21.2, [DOOR.x, -2.5]], [22.0, [DOOR.x, -3.5]]];
    const [x, z] = keys(t, path.map(([k, v]) => [k, v, (u) => u]));
    const [x2, z2] = keys(t + 0.05, path.map(([k, v]) => [k, v, (u) => u]));
    p.x = x; p.z = z;
    const moving = span(t, 19.75, 19.9);
    p.yaw = moving > 0 && Math.hypot(x2 - x, z2 - z) > 1e-5 ? Math.atan2(x2 - x, z2 - z) : Math.atan2(DESK.x - 0.3, CHAIR_Z + 0.86);
    p.sit = 1 - smooth(span(t, 19.3, 19.6));
    p.headPitch = 0.6 * pick;
    dH.gait((t - 19.8) * 3, moving, 0.3);
    p.wag = 1; p.wagSpeed = 18; p.tongue = 1;
    inMouth = t > 19.5;
    if (!inMouth) leashOnFloor = [0.2, -1.02];
    if (t > 21.7) dH.root.visible = false;
  } else {
    // dusk: in first, straight to the bed, round once, and down
    const path = [[46.9, [DOOR.x, -2.8]], [47.4, [DOOR.x - 0.2, -1.5]], [48.3, [BED.x + 0.1, BED.z + 0.05]]];
    const [x, z] = keys(t, path.map(([k, v]) => [k, v, (u) => u]));
    const [x2, z2] = keys(t + 0.05, path.map(([k, v]) => [k, v, (u) => u]));
    p.x = x; p.z = z;
    const moving = 1 - span(t, 48.2, 48.35);
    p.yaw = moving > 0.5 && Math.hypot(x2 - x, z2 - z) > 1e-5 ? Math.atan2(x2 - x, z2 - z) : lerp(-1.9, -1.9 + Math.PI * 2 * 0.9, smooth(span(t, 48.3, 49.0)));
    dH.gait((t - 46.9) * 3.2, moving, 0.5);
    p.lie = smooth(span(t, 48.9, 49.4));
    p.wag = 1 - 0.7 * p.lie; p.wagSpeed = 14;
    p.blink = 0.8 * smooth(span(t, 50.2, 50.8));
    p.headYaw = 0.6 * p.lie;
    if (t < 46.9) dH.root.visible = false;
  }
  dH.apply();
  dH.root.updateMatrixWorld(true);
  // the leash: from the mouth, a droop and a trailing end; or lying on the floor
  if (dH.root.visible && (inMouth || leashOnFloor)) {
    leash.visible = true;
    let pts;
    if (inMouth) {
      const m = dH.head.localToWorld(V(0.02, -0.06, 0.15));
      const back = V(Math.sin(p.yaw), 0, Math.cos(p.yaw)).multiplyScalar(-1);
      const side = V(Math.cos(p.yaw), 0, -Math.sin(p.yaw));
      pts = [m, m.clone().add(V(0, -0.1, 0)).addScaledVector(side, 0.06), m.clone().add(V(0, -m.y + 0.03, 0)).addScaledVector(back, 0.1).addScaledVector(side, 0.1),
        V(m.x, 0.02, m.z).addScaledVector(back, 0.35).addScaledVector(side, 0.12), V(m.x, 0.02, m.z).addScaledVector(back, 0.55).addScaledVector(side, 0.05)];
    } else {
      const [lx, lz] = leashOnFloor;
      pts = [V(lx - 0.25, 0.015, lz + 0.05), V(lx - 0.1, 0.015, lz - 0.08), V(lx + 0.05, 0.015, lz + 0.06), V(lx + 0.18, 0.015, lz - 0.02), V(lx + 0.3, 0.015, lz + 0.1)];
    }
    leash.geometry.dispose();
    leash.geometry = new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), 40, 0.012, 8, false);
  } else leash.visible = false;
}

function phoneHome(t) {
  // on the desk; nudged across to the person; picked up and pocketed as they stand
  const r = phoneH.root;
  r.visible = t < 19.85;
  const from = V(DESK.x + 0.36, DESK.top + 0.006, DESK.z + 0.06), to = V(DESK.x + 0.2, DESK.top + 0.006, DESK.z + 0.44);
  const k = out(span(t, 18.05, 18.55));
  r.position.lerpVectors(from, to, k);
  r.rotation.set(-Math.PI / 2, 0, 0.5 + 0.9 * k);
  if (t > E.standUp) {
    const up = smooth(span(t, E.standUp + 0.05, 19.85));
    r.position.y += up * 0.25; r.position.z += up * 0.1;
  }
  const s = phoneH.state;
  s.mode = 'welcome'; s.wake = smooth(span(t, E.phoneWake, E.phoneWake + 0.25)) * 0.95; s.notif = 0; s.press = null;
  s.clock = '4:02';
  phoneH.draw();
}

function homeAt(t, variant = 'now') {
  if (variant === 'flash') {
    home.setTime(0.9, 0);
    home.hemi.intensity = 1.1; home.hemi.color.set('#A8BCE6');
    home.door.open(0);
    computerFlash(t);
    hH.root.visible = false; dH.root.visible = false; leash.visible = false; phoneH.root.visible = false;
    home.chair.position.z = DESK.z + 1.25; home.seat.rotation.y = 0.5;
    home.clockHands(18 + (t - 14.6) * 5.5);
    // a tumbleweed of dust rolls across the desk
    const u = span(t, E.tumbleweed[0], E.tumbleweed[1]);
    fuzz.visible = u > 0 && u < 1;
    fuzz.position.set(lerp(DESK.x + 0.75, DESK.x - 0.75, u), DESK.top + 0.035 + 0.03 * Math.abs(Math.sin(u * Math.PI * 5)), DESK.z + 0.36);
    fuzz.rotation.z = u * 14;
    return;
  }
  fuzz.visible = false;
  // the day: morning while they are together, the afternoon passing while it is alone,
  // late afternoon when it is answered, dusk when they come home
  const k = t < 21.9 ? 0.06 : t < 27.6 ? lerp(0.25, 0.42, span(t, 21.9, 27.6)) : t < 46.5 ? 0.5 : 1.0;
  home.setTime(k);
  const doorA = t < 22.5 ? keys(t, [[11.15, 0], [11.55, 1, out], [21.55, 1], [21.9, 0, inn]]) : keys(t, [[46.6, 0], [47.0, 1, out], [49.4, 1], [50.2, 0.12]]);
  home.door.open(doorA);
  home.skyF.visible = t < 50.6;
  home.clockHands(t < 22 ? 10.1 + t / 3600 : t < 46.5 ? 15.2 + (t - 22) * 0.02 : 19.5);
  computerNow(t);
  comp.root.updateMatrixWorld(true);        // the person reaches for it: read where it is now
  humanHome(t);
  dogHome(t);
  phoneHome(t);
  home.scene.updateMatrixWorld(true);
}

// ---- the park -----------------------------------------------------------------------------------
const YAW_THROW = -2.25, YAW_FRONT = 0.55;
const DOG_SIT = V(Math.sin(YAW_FRONT) * 0.78, 0, Math.cos(YAW_FRONT) * 0.78);
function phoneScreen() {
  const box = phoneP.box, m = phoneP.screen;
  const c = new THREE.Vector3(); box.getCenter(c);
  const p = phoneP.root.localToWorld(c.clone().setZ(box.max.z + 0.001));
  const n = phoneP.root.localToWorld(c.clone().setZ(box.max.z + 1)).sub(phoneP.root.localToWorld(c.clone().setZ(box.max.z))).normalize();
  return { p, n };
}
function phonePoint(u, v) {
  // a point on the phone's glass, in canvas pixels (900 x 2000, from the top left)
  const b = phoneP.box;
  return phoneP.root.localToWorld(V(lerp(b.min.x, b.max.x, u / 900), lerp(b.max.y, b.min.y, v / 2000), b.max.z + 0.001));
}
function parabola(p0, p1, h, u) { return V(lerp(p0.x, p1.x, u), lerp(p0.y, p1.y, u) + 4 * h * u * (1 - u), lerp(p0.z, p1.z, u)); }

// Everyone's pose in the park at t. Returns where the ball is: in the hand, in flight, in the mouth.
function posePark(t) {
  const p = hP.pose, d = dP.pose;
  reset(p, defaults.h); reset(d, defaults.d);
  d.t = t;
  p.x = 0; p.z = 0;
  const f = phoneP.state;
  f.clock = '5:12';
  phoneP.root.visible = false;
  let ball = null;
  if (t < 30.4) {
    // the first throw
    p.yaw = keys(t, [[29.0, YAW_THROW], [29.8, YAW_FRONT, inOut]]);
    blend(p, [POSES.stand, 1]);
    const wind = keys(t, [[27.6, 0.2], [28.2, 1, inOut], [E.throw1, 1], [E.throw1 + 0.18, -1, out5], [28.9, -0.3], [29.4, 0]]);
    p.armR = [lerp(-0.2, 0.9, clamp(wind)) + (wind < 0 ? 1.9 * wind : 0), 0, 0.3 + 0.2 * Math.abs(wind)];
    p.elbowR = wind > 0 ? 1.5 : 0.3;
    p.twist = 0.35 * wind; p.lean = -0.08 * wind;
    p.legR = [0.25 * clamp(wind), 0, 0.05]; p.legL = [-0.2 * clamp(-wind), 0, 0.05];
    p.mouth = 'grin'; p.mouthOpen = 0.7; p.browUp = 0.5;
    p.headYaw = keys(t, [[28.6, 0], [29.8, 0.2]]);
    ball = t < E.throw1 + 0.05 ? 'hand' : 'flight1';
    // the dog: bouncing, then off after it, and back with it
    const outPath = [[E.dogRun[0], [0.35, 0.45]], [29.4, [LAND1.x + 0.3, LAND1.z + 0.2]], [29.6, [LAND1.x + 0.3, LAND1.z + 0.2]], [E.dogRun[1], [DOG_SIT.x, DOG_SIT.z]]];
    const [x, z] = keys(t, outPath.map(([k, v]) => [k, v, (u) => smooth(u)]));
    const [x2, z2] = keys(t + 0.05, outPath.map(([k, v]) => [k, v, (u) => smooth(u)]));
    d.x = x; d.z = z;
    const running = span(t, E.dogRun[0], E.dogRun[0] + 0.15) * (1 - span(t, E.dogRun[1] - 0.15, E.dogRun[1]));
    d.yaw = Math.hypot(x2 - x, z2 - z) > 1e-4 ? Math.atan2(x2 - x, z2 - z) : Math.atan2(-DOG_SIT.x, -DOG_SIT.z);
    if (t < E.dogRun[0]) { d.yaw = YAW_THROW + 0.3; d.y = 0.05 * Math.abs(Math.sin(t * 9)); d.earUp = 0.6; }
    dP.gait((t - E.dogRun[0]) * 3.4, running, 1);
    d.wag = 1; d.wagSpeed = 20; d.tongue = 1;
    d.sit = smooth(span(t, E.dogRun[1], E.dogRun[1] + 0.2));
    if (t >= 29.45) ball = 'pickup';
    d.headPitch = 0.55 * bump(t, 29.35, 29.7);
  } else if (t < 42.5) {
    // the buzz: the phone comes out, a notification, a question, a look at the dog, an answer
    p.yaw = YAW_FRONT;
    blend(p, [POSES.stand, 1]);
    const out_ = smooth(span(t, E.phoneUp[0], E.phoneUp[1]));
    const hold = out_;
    phoneP.root.visible = t > E.phoneUp[0] + 0.1;
    // the phone in front of the chest, screen to the face, both hands on it
    phoneP.root.position.set(lerp(-0.12, 0.0, hold), lerp(0.05, 0.28, hold), lerp(0.2, 0.3, hold));
    phoneP.root.rotation.set(lerp(0.2, 0.95, hold) + 0.03 * bump(t, E.tapDark - 0.05, E.tapDark + 0.2), Math.PI, 0);
    p.armL = [lerp(0.05, -0.85, hold), 0.25, lerp(0.12, 0.12, hold)]; p.elbowL = lerp(0.2, 1.25, hold);
    p.armR = [lerp(0.05, -0.85, hold), 0.25, lerp(0.12, 0.12, hold)]; p.elbowR = lerp(0.2, 1.25, hold);
    p.wristL = [0.5 * hold, 0, 0]; p.wristR = [0.5 * hold, 0, 0];
    p.headPitch = 0.35 * hold; p.eyeY = -0.8 * hold;
    // a startle at the buzz
    p.browUp = bump(t, E.buzz[0], E.buzz[0] + 0.6);
    p.mouth = t < 31.4 ? 'oh' : 'smile';
    // thumbs: a tap on the notification, and on "Dark"
    const tap = bump(t, E.notifTap - 0.1, E.notifTap + 0.12) + bump(t, E.tapDark - 0.1, E.tapDark + 0.12);
    p.elbowR += 0.12 * tap; p.armR[0] -= 0.08 * tap;
    // the look at the dog, and the smile that decides it
    const look = smooth(span(t, E.dogLook[0], E.dogLook[0] + 0.3)) * (1 - smooth(span(t, E.dogLook[1] - 0.1, E.dogLook[1] + 0.2)));
    p.headPitch += 0.25 * look; p.headYaw = 0.25 * look; p.eyeY = lerp(p.eyeY, -1, look);
    if (t > E.dogLook[0] + 0.3) { p.mouth = 'grin'; p.mouthOpen = 0.5; }
    // the screen
    f.wake = 1;
    f.mode = t < 33.35 ? 'lock' : 'session';
    f.notif = t < 33.35 ? out(span(t, E.buzz[0] + 0.05, E.buzz[0] + 0.45)) : 0;
    f.press = t < 33.6 ? { x: 450, y: 250, k: span(t, E.notifTap, E.notifTap + 0.4) } : { x: 360, y: 1415, k: span(t, E.tapDark, E.tapDark + 0.4) };
    f.picked = smooth(span(t, E.tapDark, E.tapDark + 0.15));
    f.sent = smooth(span(t, 36.4, 37.0));
    f.spin = t * 6;
    // the dog sits with the ball and tilts its head
    d.x = DOG_SIT.x; d.z = DOG_SIT.z; d.yaw = Math.atan2(-DOG_SIT.x, -DOG_SIT.z);
    d.sit = 1; d.wag = 0.8; d.wagSpeed = 16;
    d.headTilt = 0.4 * smooth(span(t, 34.5, 34.8)) * (1 - smooth(span(t, 35.6, 36.0))) - 0.15 * bump(t, E.buzz[0], E.buzz[0] + 0.8);
    d.earUp = bump(t, E.squeak - 0.1, E.squeak + 0.5) * 0.8;
    d.headPitch = -0.55;
    ball = 'mouth';
  } else {
    // the pocket, the big throw, the catch
    p.yaw = keys(t, [[42.5, YAW_FRONT], [43.0, YAW_THROW + 0.2]]);
    blend(p, [POSES.stand, 1]);
    const pocket = smooth(span(t, 42.5, E.pocket + 0.2));
    phoneP.root.visible = t < E.pocket;
    phoneP.root.position.set(0.05, lerp(0.28, 0.05, pocket), lerp(0.3, 0.2, pocket));
    phoneP.root.rotation.set(lerp(0.95, 0.2, pocket), Math.PI, 0);
    f.mode = 'session'; f.sent = 1; f.picked = 1; f.spin = t * 6;
    p.armL = [lerp(-0.85, 0.05, pocket), 0.25, 0.12]; p.elbowL = lerp(1.25, 0.3, pocket);
    const wind = keys(t, [[42.6, 0], [43.0, 1, inOut], [E.throw2, 1], [E.throw2 + 0.2, -1.2, out5], [43.9, -0.4], [44.4, 0]]);
    p.armR = [lerp(-0.3, 1.0, clamp(wind)) + (wind < 0 ? 2.1 * wind : 0), 0, 0.3];
    p.elbowR = wind > 0 ? 1.4 : 0.2;
    p.twist = 0.3 * wind; p.lean = -0.1 * clamp(-wind) - 0.1 * clamp(wind);
    p.legR = [0.25 * clamp(wind), 0, 0.05];
    // then both arms up: a cheer
    const cheer = smooth(span(t, 44.55, 44.8)) * (1 - smooth(span(t, 45.9, 46.3)));
    p.armL = p.armL.map((a, i) => lerp(a, [-2.8, 0, 0.35][i], cheer)); p.armR = p.armR.map((a, i) => lerp(a, [-2.8, 0, 0.35][i], cheer));
    p.elbowL = lerp(p.elbowL, 0.3, cheer); p.elbowR = lerp(p.elbowR, 0.3, cheer);
    p.y = 0.08 * bump(t, 44.6, 45.0);
    p.mouth = 'grin'; p.mouthOpen = 0.9; p.browUp = 0.7;
    // the dog: set, run, leap, catch
    const catchAt = V(-2.6, 1.05, -2.2);
    const [x, z] = keys(t, [[43.2, [0.45, 0.25]], [44.1, [-2.0, -1.65], (u) => smooth(u)], [44.5, [catchAt.x, catchAt.z]], [45.0, [-3.0, -2.55], out], [46.5, [-3.1, -2.6]]]);
    d.x = x; d.z = z;
    d.yaw = Math.atan2(-2.6 - 0.45, -2.2 - 0.25);
    if (t > 45.0) d.yaw = lerp(d.yaw, Math.atan2(3.1, 2.6), smooth(span(t, 45.2, 45.8)));
    const run = span(t, 43.25, 43.4) * (1 - span(t, 43.95, 44.1));
    dP.gait((t - 43.2) * 3.6, run, 1);
    const leap = span(t, 44.1, 44.95);
    d.y = 1.05 * Math.sin(leap * Math.PI) * (leap > 0 && leap < 1 ? 1 : 0);
    d.pitch = leap > 0 && leap < 1 ? lerp(-0.5, 0.45, leap) : 0;
    d.stretch = leap > 0 && leap < 1 ? Math.sin(leap * Math.PI) : 0;
    d.legs = leap > 0 && leap < 1 ? [-0.9, -0.9, 0.9, 0.9] : d.legs;
    d.headPitch = leap > 0 && leap < 0.5 ? -0.4 : 0;
    d.wag = 1; d.wagSpeed = 22; d.tongue = t < 44.5 ? 1 : 0; d.earFlop = leap > 0 && leap < 1 ? 1 : d.earFlop;
    d.sit = smooth(span(t, 45.3, 45.6));
    ball = t < E.throw2 + 0.02 ? 'hand' : t < E.catch ? 'flight2' : 'mouth';
  }
  // a blink every so often
  const bl = ((t + 1.3) % 3.1);
  if (bl < 0.12) p.blink = Math.max(p.blink, Math.sin(bl / 0.12 * Math.PI));
  hP.apply(); dP.apply();
  hP.root.updateMatrixWorld(true); dP.root.updateMatrixWorld(true);
  // both hands on the phone while it is out, a thumb on the glass for each tap
  const holding = (t >= E.phoneUp[0] && t < 42.5 ? smooth(span(t, E.phoneUp[0] + 0.1, E.phoneUp[1])) : 0)
    + (t >= 42.5 && t < E.pocket + 0.05 ? 1 - smooth(span(t, 42.5, E.pocket)) : 0);
  if (holding > 0 && phoneP.root.visible) {
    const posed = { L: p.armL.slice(), R: p.armR.slice(), eL: p.elbowL, eR: p.elbowR };
    // hold it from below, clear of the glass; the thumb hand comes up to tap
    const gripA = phoneP.root.localToWorld(V(0.036, -0.088, -0.022)), gripB = phoneP.root.localToWorld(V(-0.036, -0.088, -0.022));
    const inSpine = (w) => hP.spine.worldToLocal(w.clone());
    const [leftGrip, rightGrip] = inSpine(gripA).x > inSpine(gripB).x ? [gripA, gripB] : [gripB, gripA];
    const n = phoneScreen().n, down = phonePoint(450, 1000).sub(phonePoint(450, 0)).normalize();
    const tapN = bump(t, E.notifTap - 0.1, E.notifTap + 0.1), tapD = bump(t, E.tapDark - 0.1, E.tapDark + 0.1);
    const rightV = phonePoint(900, 1000).sub(phonePoint(0, 1000)).normalize();
    // the notification is tapped from below; "Dark" from the right edge, so the card stays in view
    const thumb = tapN > 0 ? phonePoint(450, 250).addScaledVector(down, 0.068).addScaledVector(n, 0.018)
      : phonePoint(640, 1415).addScaledVector(rightV, 0.058).addScaledVector(down, 0.012).addScaledVector(n, 0.016);
    hP.reach(1, leftGrip);
    hP.reach(-1, rightGrip.clone().lerp(thumb, tapN));
    const w = holding * (t >= 42.5 ? 1 : 1);
    p.armL = posed.L.map((a, i) => lerp(a, p.armL[i], w)); p.elbowL = lerp(posed.eL, p.elbowL, w);
    if (t < 42.5) { p.armR = posed.R.map((a, i) => lerp(a, p.armR[i], w)); p.elbowR = lerp(posed.eR, p.elbowR, w); }
    else { p.armR = posed.R; p.elbowR = posed.eR; }
    hP.apply();
    hP.root.updateMatrixWorld(true);
  }
  return ball;
}

// The ball: in a hand, in a mouth, or on one arc between them. A throw leaves from where the
// hand really is at the release, and the catch lands where the mouth really is at the catch.
const LAND1 = V(-4.4, 0.05, -3.4);
const handBall = () => hP.handR.localToWorld(V(0, -0.07, 0.03));
const mouthBall = () => dP.head.localToWorld(V(0, -0.1, 0.165));
function parkAt(t) {
  let from = null, to = null;
  if (t >= E.throw1 + 0.05 && t < 29.45) { posePark(E.throw1 + 0.05); from = handBall(); }
  if (t >= E.throw2 + 0.02 && t < E.catch) { posePark(E.throw2 + 0.02); from = handBall(); posePark(E.catch); to = mouthBall(); }
  const mode = posePark(t);
  let ball = null;
  if (mode === 'hand') ball = handBall();
  else if (mode === 'mouth') ball = mouthBall();
  else if (mode === 'flight1') ball = t < 29.3 ? parabola(from, LAND1, 1.3, span(t, E.throw1 + 0.05, 29.3)) : LAND1.clone().add(V(0, 0.07 * bump(t, 29.3, 29.42), 0));
  else if (mode === 'pickup') ball = LAND1.clone().lerp(mouthBall(), smooth(span(t, 29.45, 29.6)));
  else if (mode === 'flight2') ball = parabola(from, to, 1.6, span(t, E.throw2 + 0.02, E.catch));
  if (ball) { ballP.visible = true; ballP.position.copy(ball); ballP.rotation.set(t * 3, t * 2, 0); } else ballP.visible = false;
  phoneP.draw();
  park.focus(lerp(0, -1.5, span(t, 43, 44.5)), lerp(0, -1.2, span(t, 43, 44.5)));
  park.scene.updateMatrixWorld(true);
}

// ---- cameras -------------------------------------------------------------------------------------
function homeShot(t) {
  const c = homeCam;
  const eyeL = () => screenPoint(FACE_W / 2 - 150, FACE_H * 0.47);
  const mid = () => screenPoint(FACE_W / 2, FACE_H * 0.47);
  if (t < 4.2) {
    // the hook: close on the cursor, then back as it wakes
    const e = screenPoint(262, FACE_H * 0.47), m = mid();
    const close = e.clone().add(V(0.0, 0.0, 0.33)), closeT = e.clone();
    // stay between the screen and the person's head on the way back, then swing out to their right
    const both = m.clone().add(V(0.0, 0.02, 0.36)), bothT = m.clone();
    const wide = V(0.62, 1.2, -1.02), wideT = V(DESK.x - 0.2, 0.9, DESK.z + 0.18);
    const k1 = inOut(span(t, 1.45, 2.0)), k2 = inOut(span(t, 2.0, 4.1));
    const pos = close.clone().lerp(both, k1).lerp(wide, k2), tgt = closeT.clone().lerp(bothT, k1).lerp(wideT, k2);
    pos.z += 0.03 * t / 2 * (1 - k1);
    aim(c, pos.toArray(), tgt.toArray(), lerp(24, 34, k2), t, 0.001 + 0.004 * k2);
    return;
  }
  if (t < 11.6) {
    // together: the three-quarter from their right, drifting in slowly; a closer two-shot for the bump
    if (t < 5.8) track(c, t, [[4.2, [0.62, 1.2, -1.02], [DESK.x - 0.2, 0.9, DESK.z + 0.18], 34], [5.8, [0.95, 1.38, -0.5], [DESK.x - 0.12, 0.88, DESK.z + 0.1], 32]]);
    else if (t < 7.8) track(c, t, [[5.8, [DESK.x + 0.5, 1.08, DESK.z - 0.25], [DESK.x - 0.05, 1.0, CHAIR_Z], 38], [7.8, [DESK.x + 0.5, 1.08, DESK.z - 0.2], [DESK.x - 0.05, 1.0, CHAIR_Z], 36]]);
    else if (t < 9.0) track(c, t, [[7.8, [1.05, 1.4, -0.45], [DESK.x - 0.12, 0.88, DESK.z + 0.1], 31], [9.0, [1.0, 1.38, -0.5], [DESK.x - 0.12, 0.88, DESK.z + 0.1], 31]]);
    else track(c, t, [[9.0, [1.85, 1.15, -1.3], [DESK.x - 0.2, 0.95, -1.52], 30], [11.6, [1.78, 1.14, -1.28], [DESK.x - 0.2, 0.95, -1.52], 30]]);
    return;
  }
  if (t < 13.3) {
    // the dog comes in: wide from the front left, the door in view
    track(c, t, [[11.6, [-1.55, 1.4, 1.15], [0.55, 0.62, -1.55], 40], [13.3, [-1.45, 1.32, 1.0], [0.4, 0.6, -1.5], 40]]);
    return;
  }
  if (t < 17.75) {
    // the person's face, from beside the computer: glad, then unsure, then the thought
    track(c, t, [[13.3, [DESK.x + 0.5, 1.1, DESK.z - 0.25], [DESK.x - 0.08, 1.06, CHAIR_Z], 42], [17.75, [DESK.x + 0.5, 1.1, DESK.z - 0.2], [DESK.x - 0.1, 1.07, CHAIR_Z], 40]]);
    return;
  }
  if (t < 19.55) {
    // the nudge: the computer, the phone, the person's hands
    if (t < 18.85) track(c, t, [[17.75, [DESK.x + 0.62, 1.0, DESK.z + 0.95], [DESK.x + 0.18, DESK.top + 0.16, DESK.z + 0.2], 36], [18.85, [DESK.x + 0.58, 0.98, DESK.z + 0.9], [DESK.x + 0.18, DESK.top + 0.16, DESK.z + 0.22], 35]]);
    else track(c, t, [[18.85, [1.25, 1.12, -1.15], [DESK.x + 0.05, 0.86, DESK.z + 0.18], 32], [19.55, [1.2, 1.1, -1.18], [DESK.x + 0.05, 0.86, DESK.z + 0.18], 31]]);
    return;
  }
  if (t < 22.0) {
    // out they go: wide, the computer waving in the foreground
    track(c, t, [[19.55, [-1.5, 1.3, 1.05], [0.5, 0.75, -1.55], 40], [22.0, [-1.35, 1.25, 0.9], [0.55, 0.78, -1.6], 40]]);
    return;
  }
  if (t < 24.05) {
    // alone: the computer at work, humming; a slow push
    track(c, t, [[22.0, [DESK.x + 0.55, 1.08, DESK.z + 1.05], [DESK.x, 0.92, DESK.z], 32], [24.05, [DESK.x + 0.4, 1.04, DESK.z + 0.85], [DESK.x, 0.93, DESK.z], 32]]);
    return;
  }
  if (t < 25.4) {
    // the lonely wide: the empty chair, the closed door, a small computer waiting
    track(c, t, [[24.05, [2.0, 1.55, 1.35], [DESK.x + 0.1, 0.72, DESK.z], 36], [25.4, [1.95, 1.5, 1.25], [DESK.x + 0.1, 0.74, DESK.z], 36]]);
    return;
  }
  if (t < 38.5) {
    // it remembers, and sends: close; this is also the view the porthole opens on
    if (t < 27.7) { track(c, t, [[25.4, [DESK.x + 0.3, 1.02, DESK.z + 1.15], [DESK.x, 0.9, DESK.z], 30], [27.7, [DESK.x + 0.22, 1.0, DESK.z + 0.95], [DESK.x, 0.92, DESK.z], 30]]); return; }
    track(c, t, [[35.8, [DESK.x + 0.06, 0.99, DESK.z + 1.2], [DESK.x, 0.95, DESK.z], 30], [38.5, [DESK.x + 0.03, 0.98, DESK.z + 0.95], [DESK.x, 0.95, DESK.z], 30]]);
    return;
  }
  if (t < 42.5) {
    // the answer: on in, then round to the three-quarter as it celebrates
    track(c, t, [[38.5, [DESK.x + 0.03, 0.98, DESK.z + 0.95], [DESK.x, 0.95, DESK.z], 30], [40.2, [DESK.x + 0.35, 1.02, DESK.z + 1.0], [DESK.x, 0.93, DESK.z], 32], [42.5, [DESK.x + 0.75, 1.1, DESK.z + 1.2], [DESK.x - 0.05, 0.9, DESK.z], 34]]);
    return;
  }
  if (t < 50.6) {
    // dusk: home again, wide from the front left
    track(c, t, [[46.5, [-1.45, 1.3, 1.25], [0.35, 0.75, -1.5], 40], [49.0, [-1.25, 1.2, 0.95], [0.0, 0.85, -1.55], 38], [50.6, [-1.0, 1.15, 0.75], [DESK.x + 0.2, 0.92, DESK.z + 0.2], 36]]);
    return;
  }
  // the way out: back through the round window in the front wall, into the night
  const win = V(FRONT_WINDOW.x, FRONT_WINDOW.y, ROOM.z1);
  track(c, t, [[50.6, [-1.0, 1.15, 0.75], [DESK.x + 0.2, 0.92, DESK.z + 0.2], 36], [51.9, [FRONT_WINDOW.x, 1.1, ROOM.z1 - 0.5], [DESK.x + 0.1, 0.95, DESK.z], 40],
    [53.4, [FRONT_WINDOW.x, FRONT_WINDOW.y - 0.05, ROOM.z1 + 3.2], [win.x, win.y, win.z - 1], 34], [60, [FRONT_WINDOW.x, FRONT_WINDOW.y - 0.05, ROOM.z1 + 5.5], [win.x, win.y, win.z - 1], 34, (u) => u]], 34, 0.002);
}

function flashShot(t) {
  track(secondCam, t, [[14.6, [DESK.x + 0.42, 1.12, DESK.z + 1.3], [DESK.x + 0.28, 1.1, DESK.z], 50], [17.6, [DESK.x + 0.4, 1.1, DESK.z + 1.16], [DESK.x + 0.28, 1.1, DESK.z], 50]], 50, 0.002);
}

function parkShot(t) {
  const c = parkCam;
  if (t < 30.4) { track(c, t, [[26.6, [2.9, 1.15, 4.2], [-1.2, 0.7, -1.0], 34], [30.4, [2.5, 1.05, 3.6], [-0.8, 0.7, -0.7], 34]]); return; }
  if (t < 31.6) { track(c, t, [[30.4, [1.25, 1.25, 2.1], [0, 1.0, 0], 30], [31.6, [1.05, 1.2, 1.8], [0.05, 0.95, 0.1], 28]]); return; }
  // from the person's eyes: the phone in their hands, near enough to read
  const eye = hP.spine.localToWorld(V(0, 0.6, 0.06));
  const fwd = V(Math.sin(YAW_FRONT), 0, Math.cos(YAW_FRONT));
  const glassC = phoneScreen();
  const pov = eye.clone().addScaledVector(fwd, 0.2);
  if (t < E.dogLook[0] || (t >= E.dogLook[1] && t < 36.0)) {
    // near enough to read: first the notification, then the question card
    const onCard = smooth(span(t, 33.35, 33.85));
    const look = phonePoint(450, 250).lerp(phonePoint(450, 1320), onCard);
    const toward = pov.clone().lerp(glassC.p, 0.1 * span(t, 31.6, 36.0));
    const wider = 7 * Math.max(bump(t, E.notifTap - 0.25, E.notifTap + 0.25), bump(t, E.tapDark - 0.25, E.tapDark + 0.3));
    aim(c, toward.toArray(), look.toArray(), lerp(14, 14.5, onCard) + wider, t, 0.0006);
    return;
  }
  if (t < E.dogLook[1]) {
    // down at the dog, who looks up with the ball
    const dh = dP.head.getWorldPosition(V(0, 0, 0));
    aim(c, pov.clone().addScaledVector(fwd, 0.05).toArray(), dh.clone().add(V(0, -0.08, 0)).toArray(), 34, t, 0.002);
    return;
  }
  if (t < 38.6) {
    // the porthole comes up out of the glass, and the camera goes through it
    const pr = portal.root.position;
    const pos = pov.clone().lerp(glassC.p, 0.12);
    const k = inn(span(t, 36.95, 38.45));
    pos.lerp(pr, 0.95 * k);
    const fov = lerp(lerp(14.5, 50, inOut(span(t, 35.95, 36.8))), 34, inOut(span(t, 36.95, 37.8)));
    const lookAt = phonePoint(450, 1320).lerp(pr, inOut(span(t, 35.95, 36.5)));
    aim(c, pos.toArray(), lookAt.toArray(), fov, t, 0.0008 * (1 - k));
    return;
  }
  // the throw and the catch, low, into the sun
  if (t < 44.0) track(c, t, [[42.5, [1.75, 1.05, 1.5], [0, 0.95, 0], 34], [44.0, [1.8, 1.0, 1.6], [-0.3, 1.1, -0.2], 34]]);
  // the leap in profile, so the catch is seen: the ball into the mouth
  else track(c, t, [[44.0, [-4.35, 0.75, 0.15], [-2.35, 1.05, -1.95], 40], [46.5, [-4.45, 0.7, 0.05], [-2.8, 0.7, -2.4], 40]]);
}

function placePortal(t) {
  const gl = phoneScreen();
  portal.root.visible = true;
  const grow = back(span(t, 36.0, 36.75), 1.3);
  portal.root.position.copy(gl.p).addScaledVector(gl.n, 0.004 + 0.09 * out(span(t, 36.0, 36.9)));
  portal.root.scale.setScalar(lerp(0.03, 0.105, grow));
  portal.root.lookAt(parkCam.position);
  portal.root.updateMatrixWorld();
}

// ---- the overlay: words, and the end --------------------------------------------------------------
let OV;
function overlayInit() {
  OV = {
    sub: document.getElementById('sub'), night: document.getElementById('night'), hole: document.getElementById('hole'),
    ring: document.getElementById('ring'), word: document.getElementById('word'), wordRing: document.getElementById('wordRing'),
    tag: document.getElementById('tag'), url: document.getElementById('url'), fine: document.getElementById('fine'), black: document.getElementById('black'),
  };
  if (DRAWN) {
    // the words are inked too: a turbulence that shifts each drawing, so they boil with the lines
    document.body.insertAdjacentHTML('beforeend', `<svg width="0" height="0" style="position:absolute"><filter id="rough" x="-5%" y="-5%" width="110%" height="110%">
      <feTurbulence id="roughNoise" type="fractalNoise" baseFrequency="0.03" numOctaves="2" seed="1" result="n"/>
      <feDisplacementMap in="SourceGraphic" in2="n" scale="5" xChannelSelector="R" yChannelSelector="G"/></filter></svg>`);
    for (const el of document.querySelectorAll('.layer')) el.style.filter = 'url(#rough)';
    // and the whole picture is on one sheet of paper
    const paper = paperCanvas(W, H);
    Object.assign(paper.style, { position: 'absolute', inset: '0', zIndex: 4, mixBlendMode: 'multiply', pointerEvents: 'none', width: W + 'px', height: H + 'px' });
    paper.id = 'paper';
    document.body.appendChild(paper);
    const tooth = toothCanvas(W, H);
    Object.assign(tooth.style, { position: 'absolute', inset: '0', zIndex: 5, mixBlendMode: 'screen', pointerEvents: 'none', width: W + 'px', height: H + 'px' });
    document.body.appendChild(tooth);
    OV.rough = document.getElementById('roughNoise');
  }
}

let END = null;
function endFrame(t) {
  const r = OV.wordRing.getBoundingClientRect();
  const target = { x: r.left + r.width / 2, y: r.top + r.height / 2, r: r.width / 2 };
  const c = V(FRONT_WINDOW.x, FRONT_WINDOW.y, ROOM.z1 + 0.02);
  const edge = V(FRONT_WINDOW.x + FRONT_WINDOW.r * 0.98, FRONT_WINDOW.y, ROOM.z1 + 0.02);
  homeCam.updateMatrixWorld();
  const pc = c.clone().project(homeCam), pe = edge.clone().project(homeCam);
  const cx = (pc.x + 1) / 2 * W, cy = (1 - pc.y) / 2 * H, cr = Math.abs((pe.x - pc.x) / 2 * W);
  // shift the frame (not the camera) so the window lands on the wordmark's "o"
  const k = inOut(span(t, 51.6, 53.6));
  const dx = (cx - target.x) * k, dy = (cy - target.y) * k;
  homeCam.setViewOffset(W, H, dx, dy, W, H);
  END = { cx: cx - dx, cy: cy - dy, cr, target };
}

function overlay(t) {
  // the computer's words, subtitled
  const s1 = E.super1;
  OV.sub.style.opacity = Math.min(smooth(span(t, s1[0], s1[0] + 0.3)), 1 - smooth(span(t, s1[1] - 0.35, s1[1])));
  OV.sub.style.transform = `translate(-50%, ${6 * (1 - out(span(t, s1[0], s1[0] + 0.4)))}px)`;
  // the end: the round window becomes the "o"
  if (t > 50.9 && END) {
    const { cx, cy, cr, target } = END;
    const R = lerp(cr, target.r, inOut(span(t, 53.0, 54.2)));
    OV.night.style.opacity = smooth(span(t, 52.0, 53.0));
    OV.hole.setAttribute('cx', cx); OV.hole.setAttribute('cy', cy);
    OV.hole.setAttribute('r', Math.max(0, R * (1 - inOut(span(t, 54.4, 55.1)))));
    OV.ring.setAttribute('cx', cx); OV.ring.setAttribute('cy', cy); OV.ring.setAttribute('r', R);
    OV.ring.setAttribute('transform', `rotate(-90 ${cx} ${cy})`);
    OV.ring.style.strokeDashoffset = 100 * (1 - inOut(span(t, E.ringDraw[0], E.ringDraw[1])));
    OV.ring.style.opacity = t > 53.2 ? 1 : 0;
    OV.ring.style.strokeWidth = lerp(3, Math.max(3, target.r * 0.3), span(t, 53.4, 54.2));
  } else { OV.night.style.opacity = 0; OV.ring.style.opacity = 0; }
  // the letters come to the ring; then the line, the address, the small print
  OV.word.style.opacity = smooth(span(t, 53.9, 54.6));
  OV.word.style.letterSpacing = `${lerp(0.08, -0.01, out(span(t, 53.9, 55.0)))}em`;
  OV.wordRing.style.opacity = t > 54.5 ? 1 : 0;
  OV.ring.style.visibility = t > 54.5 ? 'hidden' : 'visible';
  OV.tag.style.opacity = smooth(span(t, E.tagline, E.tagline + 0.5));
  OV.tag.style.transform = `translateY(${10 * (1 - out(span(t, E.tagline, E.tagline + 0.6)))}px)`;
  OV.url.style.opacity = smooth(span(t, E.url, E.url + 0.5));
  OV.fine.style.opacity = smooth(span(t, E.url + 0.4, E.url + 0.9)) * 0.9;
  // in from black at the start, out to black at the end
  OV.black.style.opacity = Math.max(1 - smooth(span(t, E.fadeIn[0], E.fadeIn[1])), smooth(span(t, E.fadeOut[0], E.fadeOut[1])));
}

// ---- a frame ---------------------------------------------------------------------------------------
function frame(t) {
  const M = stage.mix, G = stage.grade;
  M.amount.value = 0; M.ring.value = 0; M.bubbles.value = 0; M.satB.value = 1; M.tintB.value.set(1, 1, 1); M.remap.value = 0; M.zoom.value = 1;
  G.fade.value = 0; G.warm.value = 0; G.sat.value = 1; G.vignette.value = 0.3; G.grain.value = 0.03; G.time.value = t;
  G.gain.value.set(1, 1, 1); G.lift.value.set(0, 0, 0);
  stage.bloom.strength = DRAWN ? 0.3 : 0.5;
  portal.root.visible = false;
  const inPark = (t >= 27.6 && t < 38.5) || (t >= 42.5 && t < 46.5);
  if (!inPark) {
    // the thought: last time, in a bubble over the person's head
    if (t >= 14.6 && t < 17.6) {
      homeAt(t, 'flash');
      flashShot(t);
      stage.drawSecond(home.scene, secondCam);
      const open = back(span(t, E.thoughtOpen[0], E.thoughtOpen[1]), 1.4) * (1 - inOut(span(t, E.thoughtClose[0], E.thoughtClose[1])));
      M.amount.value = 1; M.centre.value.set(0.74, 0.68); M.radius.value = 0.27 * clamp(open, 0, 1.2); M.feather.value = 0.003;
      M.ring.value = 0.012 * clamp(open * 3); M.ringColor.value.set('#FFF8EC');
      M.bubbles.value = clamp(span(t, 14.55, 14.75)) * (1 - span(t, 17.3, 17.5));
      M.bubbleA.value.set(0.56, 0.5, 0.012); M.bubbleB.value.set(0.6, 0.56, 0.02);
      M.tintB.value.set(0.9, 0.95, 1.08); M.satB.value = 0.75; M.remap.value = 1; M.zoom.value = 1.45;
    }
    homeAt(t, INSPECT_FLASH ? 'flash' : 'now');
    homeShot(t);
    if (t > 50.9) endFrame(t);
    if (INSPECT) { aim(homeCam, INSPECT.slice(0, 3), INSPECT.slice(3, 6), INSPECT[6] || 40, 0, 0); M.amount.value = 0; stage.draw(home.scene, homeCam); overlay(0); OV.black.style.opacity = 0; return; }
    // the wipe: the question goes out as an amber ring, and the park is inside it
    if (t >= E.wipe[0] - 0.05 && t < 27.6) {
      parkAt(t); parkShot(t);
      stage.drawSecond(park.scene, parkCam);
      const p = screenPoint(FACE_W / 2, FACE_H * 0.42).project(homeCam);
      const u = inn(span(t, E.wipe[0], E.wipe[1]));
      M.amount.value = 1; M.centre.value.set((p.x + 1) / 2, (p.y + 1) / 2); M.radius.value = lerp(0.0, 1.35, u); M.feather.value = 0.002;
      M.ring.value = 0.018; M.ringColor.value.set('#E7B84A').multiplyScalar(1.6);
    }
    G.warm.value = t < 22 ? 0.6 : t >= 46.5 ? 0.2 : 0.3;
    if (t >= 46.5) { G.vignette.value = 0.38; stage.bloom.strength = 0.8; }
    if (DRAWN) { G.grain.value = 0; G.vignette.value = t >= 46.5 ? 0.22 : 0.12; stage.bloom.strength = t >= 46.5 ? 0.5 : 0.3; G.gain.value.set(1.05, 1.05, 1.04); G.lift.value.set(0.03, 0.025, 0.015); }
    stage.draw(home.scene, homeCam);
  } else {
    parkAt(t);
    parkShot(t);
    if (INSPECT) { aim(parkCam, INSPECT.slice(0, 3), INSPECT.slice(3, 6), INSPECT[6] || 40, 0, 0); M.amount.value = 0; stage.draw(park.scene, parkCam); overlay(0); OV.black.style.opacity = 0; return; }
    // the porthole opens out of the phone's screen, onto the computer at home
    if (t >= 36.0 && t < 38.5) {
      for (let pass = 0; pass < 2; pass++) { placePortal(t); parkShot(t); }
      homeAt(t, 'now'); homeShot(t);
      stage.drawSecond(home.scene, homeCam);
      placePortal(t);
      portal.halo.material.uniforms.k.value = bump(t, 36.0, 37.2) * 0.8 + 0.5 * bump(t, E.answerArrives - 0.1, E.answerArrives + 0.5);
    }
    G.warm.value = 0.5; G.sat.value = 1.05;
    if (DRAWN) { G.grain.value = 0; G.vignette.value = 0.12; G.gain.value.set(1.04, 1.04, 1.03); G.lift.value.set(0.03, 0.025, 0.015); G.sat.value = 0.95; }
    stage.draw(park.scene, parkCam);
  }
  overlay(t);
}

window.ready = build();
window.seek = (t) => {
  if (DRAWN) {
    const k = Math.round(t * DRAWINGS_PER_SECOND);
    t = k / DRAWINGS_PER_SECOND;
    stage.ink.seed.value = (k % 3) + Math.floor(k / 3) * 0.013;   // a three-drawing boil, drifting slowly
    OV.rough.setAttribute('seed', 1 + (k % 3));
  }
  frame(t);
};
