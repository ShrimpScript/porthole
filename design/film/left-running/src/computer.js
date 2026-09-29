// The computer: a desk monitor on a jointed arm, which is all the body it needs. Like a desk
// lamp in an old short film, it turns, leans, nods and hops from its base; its face is on its
// screen (face.js). With its keyboard and mouse, which the person types on.
import * as THREE from 'three';
import { mesh, box, sphere, limb, puck, group, mat, blob, lerp, clamp } from './kit.js';
import { makeFace, TEAL, AMBER } from './face.js';

const SHELL = '#EFE8DC', SHELL_DARK = '#D9CFBF', STAND = '#22463F', STAND_LIGHT = '#2F5A51';

export function makeComputer() {
  const root = new THREE.Group();
  const shell = mat(SHELL, { physical: true, roughness: 0.38, clearcoat: 0.6, clearcoatRoughness: 0.35 });
  const shellDark = mat(SHELL_DARK, { roughness: 0.5 });
  const stand = mat(STAND, { physical: true, roughness: 0.4, clearcoat: 0.4, clearcoatRoughness: 0.4 });
  const standLight = mat(STAND_LIGHT, { roughness: 0.45 });

  // the base: a heavy rounded plinth, with a soft shadow of its own
  blob(root, 0.22, 0.32);
  const hop = group(root);                                   // lifts off the desk when it hops
  const squash = group(hop);                                 // squash and stretch from the base
  mesh(box(0.25, 0.034, 0.19, 0.016, 5), stand, squash, [0, 0.017, 0]);
  mesh(puck(0.05, 0.01, 0.4), standLight, squash, [0, 0.032, 0]);
  const turn = group(squash, [0, 0.034, 0]);                 // yaw
  const lower = group(turn);                                 // the shoulder: pitch
  mesh(sphere(0.03), standLight, lower);
  mesh(limb(0.022, 0.024, 0.16), stand, lower);
  const elbow = group(lower, [0, 0.15, 0]);                  // pitch
  mesh(sphere(0.028), standLight, elbow);
  mesh(limb(0.02, 0.022, 0.13), stand, elbow);
  const neck = group(elbow, [0, 0.125, 0]);                  // the head's pitch and roll
  mesh(sphere(0.026), standLight, neck);

  // the monitor: its pivot sits behind the middle of its back
  const head = group(neck, [0, 0.02, 0.085]);
  const bezelW = 0.58, bezelH = 0.4, depth = 0.05;
  mesh(box(bezelW, bezelH, depth, 0.035, 6), shell, head);
  mesh(box(0.4, 0.27, 0.07, 0.04, 5), shellDark, head, [0, -0.005, -0.045]);
  mesh(box(0.1, 0.1, 0.06, 0.02), shellDark, head, [0, -0.01, -0.08]);   // where the arm meets it
  // the glass: the face drawn on a canvas, lit from within
  const face = makeFace();
  const glassW = 0.51, glassH = glassW * 656 / 1024;
  const glass = new THREE.Mesh(new THREE.PlaneGeometry(glassW, glassH),
    new THREE.MeshStandardMaterial({ color: '#000000', emissive: '#ffffff', emissiveMap: face.tex, emissiveIntensity: 1.25, roughness: 0.35, metalness: 0 }));
  glass.position.set(0, 0.012, depth / 2 + 0.0015);
  head.add(glass);
  // a rim round the glass, so the screen sits in the shell rather than on it
  const rimShape = new THREE.Shape();
  const rr = (s, w, h, r) => { s.moveTo(-w / 2 + r, -h / 2); s.lineTo(w / 2 - r, -h / 2); s.quadraticCurveTo(w / 2, -h / 2, w / 2, -h / 2 + r); s.lineTo(w / 2, h / 2 - r); s.quadraticCurveTo(w / 2, h / 2, w / 2 - r, h / 2); s.lineTo(-w / 2 + r, h / 2); s.quadraticCurveTo(-w / 2, h / 2, -w / 2, h / 2 - r); s.lineTo(-w / 2, -h / 2 + r); s.quadraticCurveTo(-w / 2, -h / 2, -w / 2 + r, -h / 2); };
  rr(rimShape, glassW + 0.018, glassH + 0.018, 0.02);
  const hole = new THREE.Path(); rr(hole, glassW, glassH, 0.012); rimShape.holes.push(hole);
  mesh(new THREE.ExtrudeGeometry(rimShape, { depth: 0.004, bevelEnabled: true, bevelThickness: 0.002, bevelSize: 0.002, bevelSegments: 3 }), mat('#1B2B28', { roughness: 0.5 }), head, [0, 0.012, depth / 2 - 0.002]);
  // the power light and the camera dot
  const led = mesh(new THREE.SphereGeometry(0.006, 16, 12), new THREE.MeshBasicMaterial({ color: TEAL, toneMapped: false }), head, [bezelW / 2 - 0.045, -bezelH / 2 + 0.018, depth / 2 + 0.001]);
  mesh(new THREE.CylinderGeometry(0.008, 0.008, 0.004, 20), mat('#1D2624', { roughness: 0.2 }), head, [0, bezelH / 2 - 0.016, depth / 2 + 0.001], [Math.PI / 2, 0, 0]);
  // its glow on the desk and the person: teal while it runs, amber while it waits
  const glow = new THREE.PointLight(TEAL, 0.6, 2.6, 2);
  glow.position.set(0, 0.0, 0.14);
  head.add(glow);

  // ---- the keyboard and the mouse (they stay on the desk) --------------------------------
  const keyboard = group(null);
  mesh(box(0.46, 0.02, 0.16, 0.009), shell, keyboard, [0, 0.01, 0]);
  const keyGeo = box(0.028, 0.012, 0.028, 0.006, 2);
  const keyMat = mat('#F8F4EE', { roughness: 0.45 });
  const keys = [];
  for (let r = 0; r < 4; r++) for (let c = 0; c < 13; c++) {
    const k = mesh(keyGeo, keyMat, keyboard, [-0.195 + c * 0.0325, 0.024, -0.052 + r * 0.033]);
    k.castShadow = false;
    keys.push(k);
  }
  const space = mesh(box(0.18, 0.012, 0.026, 0.006), keyMat, keyboard, [0, 0.024, 0.078]);
  space.castShadow = false;
  const enter = mesh(box(0.05, 0.012, 0.028, 0.006), mat(TEAL, { roughness: 0.4 }), keyboard, [0.205, 0.0245, 0.047]);
  enter.castShadow = false;
  const mouse = group(null);
  mesh(sphere(0.035), shell, mouse, [0, 0.012, 0], null, [0.75, 0.42, 1.1]);

  const pose = { hop: 0, squash: 0, turn: 0, lean: 0, elbow: 0, tilt: 0, roll: 0 };
  function apply() {
    const p = pose;
    hop.position.y = Math.max(0, p.hop);
    const s = clamp(p.squash, -1, 1);
    squash.scale.set(1 + 0.08 * s, 1 - 0.14 * s, 1 + 0.08 * s);
    turn.rotation.y = p.turn;
    lower.rotation.x = -0.22 + p.lean;
    elbow.rotation.x = 0.42 + p.elbow;
    neck.rotation.x = -0.2 + p.tilt - p.lean - p.elbow;       // the head keeps level unless told
    neck.rotation.z = p.roll;
    const col = new THREE.Color(TEAL).lerp(new THREE.Color(AMBER), face.state.wait);
    glow.color.copy(col);
    glow.intensity = (0.05 + 0.3 * face.state.dark) * face.state.power;
    glass.material.emissiveIntensity = lerp(0.95, 2.2, face.state.dark);
    led.material.color.copy(col);
    face.draw();
  }
  // key presses while someone types: which keys are down at time t
  function typing(t, amount) {
    for (let i = 0; i < keys.length; i++) {
      const ph = Math.sin(t * (11 + (i % 7)) + i * 2.3);
      keys[i].position.y = 0.024 - (amount > 0 && ph > 0.93 ? 0.004 : 0);
    }
    space.position.y = 0.024 - (amount > 0 && Math.sin(t * 5.3) > 0.9 ? 0.004 : 0);
  }
  return { root, head, glass, face, pose, apply, keyboard, mouse, typing, led, glow };
}
