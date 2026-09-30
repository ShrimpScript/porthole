// The film's small toolkit: time, easing, keyframes, noise, and the rounded shapes every
// model is built from. Every frame is a pure function of t, so nothing here keeps a clock.
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';

export const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
export const lerp = (a, b, t) => a + (b - a) * t;
export const span = (t, a, b) => clamp((t - a) / (b - a));
export const smooth = (t) => t * t * (3 - 2 * t);
export const inOut = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
export const out = (t) => 1 - Math.pow(1 - t, 3);
export const out5 = (t) => 1 - Math.pow(1 - t, 5);
export const inn = (t) => t * t * t;
// overshoots and settles: for anything alive landing on a pose
export const back = (t, s = 1.7) => 1 + (s + 1) * Math.pow(t - 1, 3) + s * Math.pow(t - 1, 2);
// a spring settling from 0 to 1: squash and stretch, happy bounces
export const spring = (t, f = 3.2, d = 5.5) => (t <= 0 ? 0 : 1 - Math.exp(-d * t) * Math.cos(f * Math.PI * 2 * t * 0.5));
// a bump that rises and falls back over [a, b]
export const bump = (t, a, b) => { const u = span(t, a, b); return Math.sin(u * Math.PI); };
export const pulse = (t, a, b, e = 0.2) => Math.min(smooth(span(t, a, a + e)), 1 - smooth(span(t, b - e, b)));

// Keyframes: [[time, value, ease?], ...]; value a number or an array of numbers. The ease on a
// key shapes the move that arrives at it (default inOut).
export function keys(t, list) {
  if (t <= list[0][0]) return list[0][1];
  for (let i = 1; i < list.length; i++) {
    const [t1, v1, e = inOut] = list[i];
    if (t <= t1) {
      const [t0, v0] = list[i - 1];
      const u = e(span(t, t0, t1));
      if (Array.isArray(v0)) return v0.map((a, k) => lerp(a, v1[k], u));
      return lerp(v0, v1, u);
    }
  }
  return list[list.length - 1][1];
}

// Seeded value noise, smooth in time: handheld cameras, breathing, idle life.
const P = new Float32Array(512);
{ let s = 7; for (let i = 0; i < 512; i++) { s = (s * 16807) % 2147483647; P[i] = s / 2147483647 * 2 - 1; } }
export function noise(x, seed = 0) {
  const i = Math.floor(x), f = x - i, u = f * f * (3 - 2 * f);
  const a = P[(i + seed * 57) & 511], b = P[(i + 1 + seed * 57) & 511];
  return lerp(a, b, u);
}
export const fbm = (x, seed = 0) => noise(x, seed) * 0.6 + noise(x * 2.1, seed + 3) * 0.3 + noise(x * 4.3, seed + 5) * 0.1;
export function rng(seed) { let s = seed >>> 0 || 1; return () => (s = (s * 16807) % 2147483647) / 2147483647; }

// ---- materials: soft toy plastic, clay, cloth ---------------------------------------------
const cache = new Map();
export function mat(color, o = {}) {
  const key = color + JSON.stringify(o);
  if (!o.fresh && cache.has(key)) return cache.get(key);
  const m = o.physical || o.sheen || o.clearcoat
    ? new THREE.MeshPhysicalMaterial({ color, roughness: 0.6, metalness: 0, ...strip(o) })
    : new THREE.MeshStandardMaterial({ color, roughness: 0.62, metalness: 0, ...strip(o) });
  if (!o.fresh) cache.set(key, m);
  return m;
}
const strip = (o) => { const c = { ...o }; delete c.physical; delete c.fresh; return c; };

// ---- shapes -----------------------------------------------------------------------------
export function mesh(geo, material, parent, pos, rot, scale) {
  const m = new THREE.Mesh(geo, material);
  m.castShadow = true; m.receiveShadow = true;
  if (pos) m.position.set(...pos);
  if (rot) m.rotation.set(...rot);
  if (scale) (typeof scale === 'number' ? m.scale.setScalar(scale) : m.scale.set(...scale));
  if (parent) parent.add(m);
  return m;
}
export const box = (w, h, d, r = 0.02, seg = 4) => new RoundedBoxGeometry(w, h, d, seg, Math.min(r, w / 2 - 1e-4, h / 2 - 1e-4, d / 2 - 1e-4));
export const sphere = (r, ws = 40, hs = 28) => new THREE.SphereGeometry(r, ws, hs);
// a capsule standing on its base, from y=0 to y=len (so a limb hangs from its joint when flipped)
export function limb(r0, r1, len, seg = 24) {
  // a lathe of a tapered capsule: rounded ends, radius r0 at the top joint, r1 at the far end
  const pts = [];
  const n = 8;
  for (let i = 0; i <= n; i++) { const a = -Math.PI / 2 + (i / n) * Math.PI / 2; pts.push(new THREE.Vector2(Math.cos(a) * r1, r1 + Math.sin(a) * r1)); }
  for (let i = 0; i <= n; i++) { const a = (i / n) * Math.PI / 2; pts.push(new THREE.Vector2(Math.cos(a) * r0, len - r0 + Math.sin(a) * r0)); }
  const g = new THREE.LatheGeometry(pts, seg);
  g.computeVertexNormals();
  return g;
}
// a disc with a soft rounded edge (bases, plates, clocks, rugs)
export function puck(r, h, bevel = 0.2, seg = 64) {
  const b = Math.min(h / 2, r) * bevel * 2;
  const pts = [new THREE.Vector2(0, 0)];
  const n = 6;
  for (let i = 0; i <= n; i++) { const a = -Math.PI / 2 + (i / n) * Math.PI / 2; pts.push(new THREE.Vector2(r - b + Math.cos(a) * b, b + Math.sin(a) * b)); }
  for (let i = 0; i <= n; i++) { const a = (i / n) * Math.PI / 2; pts.push(new THREE.Vector2(r - b + Math.cos(a) * b, h - b + Math.sin(a) * b)); }
  pts.push(new THREE.Vector2(0, h));
  return new THREE.LatheGeometry(pts, seg);
}
export function group(parent, pos, rot) {
  const g = new THREE.Group();
  if (pos) g.position.set(...pos);
  if (rot) g.rotation.set(...rot);
  if (parent) parent.add(g);
  return g;
}
// a soft round shadow on the floor: grounds a character where the shadow map is too soft
let blobTex;
export function blob(parent, r, opacity = 0.35) {
  if (!blobTex) {
    const c = document.createElement('canvas'); c.width = c.height = 128;
    const x = c.getContext('2d'); const g = x.createRadialGradient(64, 64, 0, 64, 64, 64);
    g.addColorStop(0, 'rgba(0,0,0,1)'); g.addColorStop(0.5, 'rgba(0,0,0,0.55)'); g.addColorStop(1, 'rgba(0,0,0,0)');
    x.fillStyle = g; x.fillRect(0, 0, 128, 128);
    blobTex = new THREE.CanvasTexture(c);
  }
  const m = new THREE.Mesh(new THREE.PlaneGeometry(2 * r, 2 * r), new THREE.MeshBasicMaterial({ map: blobTex, transparent: true, opacity, depthWrite: false }));
  m.rotation.x = -Math.PI / 2; m.position.y = 0.002; m.renderOrder = 1;
  parent.add(m);
  return m;
}
