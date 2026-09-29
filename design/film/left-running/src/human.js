// The person: built from the same rounded pieces as everything else, big-headed and soft, in
// a teal hoodie. A small rig of joints (forward kinematics only); poses are plain numbers
// set from the timeline. The character faces +z; its left is +x.
import * as THREE from 'three';
import { mesh, box, sphere, limb, group, mat, blob, clamp, lerp } from './kit.js';

const SKIN = '#D39A72', SKIN_SHADE = '#C4865F', HAIR = '#3A2A24', HOODIE = '#4DB3A3', HOODIE_DARK = '#3C9A8B',
  PANTS = '#34405A', SHOE = '#F3EEE6', SOLE = '#C9BFB1', INK = '#1E1816', BLUSH = '#EC8F84';

export function makeHuman() {
  const root = new THREE.Group();
  const skin = mat(SKIN, { physical: true, roughness: 0.55, sheen: 0.4, sheenColor: new THREE.Color('#ffd8c0') });
  const hoodie = mat(HOODIE, { physical: true, roughness: 0.85, sheen: 1, sheenRoughness: 0.6, sheenColor: new THREE.Color('#bff5ea') });
  const hoodieDark = mat(HOODIE_DARK, { physical: true, roughness: 0.9, sheen: 1, sheenColor: new THREE.Color('#9ee5d8') });
  const pants = mat(PANTS, { physical: true, roughness: 0.9, sheen: 0.6, sheenColor: new THREE.Color('#8894b8') });
  const hairM = mat(HAIR, { physical: true, roughness: 0.55, sheen: 0.6, sheenColor: new THREE.Color('#8a6a5a') });
  const shoe = mat(SHOE, { roughness: 0.5 }), sole = mat(SOLE, { roughness: 0.7 });
  const ink = mat(INK, { physical: true, roughness: 0.15, clearcoat: 1 });

  const shadow = blob(root, 0.36, 0.3);
  const body = group(root);                 // the whole figure's hop and lean
  const hips = group(body, [0, 0.64, 0]);
  mesh(sphere(0.15), pants, hips, [0, 0.02, 0], null, [1, 0.72, 0.8]);

  // ---- legs --------------------------------------------------------------------------------
  const legs = {};
  for (const side of [1, -1]) {
    const hip = group(hips, [side * 0.085, -0.02, 0]);
    const thigh = mesh(limb(0.072, 0.062, 0.32), pants, hip); thigh.rotation.x = Math.PI;
    const knee = group(hip, [0, -0.3, 0]);
    const shin = mesh(limb(0.062, 0.052, 0.29), pants, knee); shin.rotation.x = Math.PI;
    const ankle = group(knee, [0, -0.27, 0]);
    const foot = group(ankle, [0, -0.02, 0.03]);
    mesh(box(0.1, 0.065, 0.19, 0.03, 4), shoe, foot, [0, 0, 0]);
    mesh(box(0.105, 0.02, 0.195, 0.009, 3), sole, foot, [0, -0.028, 0]);
    legs[side] = { hip, knee, ankle };
  }

  // ---- torso: the hoodie, a lathe wide at the hem and round at the shoulders ---------------
  const spine = group(hips, [0, 0.03, 0]);
  const prof = [];
  const P = [[0.0, 0.0], [0.17, 0.0], [0.19, 0.03], [0.19, 0.08], [0.18, 0.2], [0.175, 0.3], [0.16, 0.36], [0.12, 0.41], [0.06, 0.43], [0.0, 0.435]];
  for (const [r, y] of P) prof.push(new THREE.Vector2(r, y));
  const torsoGeo = new THREE.LatheGeometry(new THREE.SplineCurve(prof).getPoints(40), 48);
  const torso = mesh(torsoGeo, hoodie, spine, [0, -0.02, 0], null, [1, 1, 0.78]);
  mesh(new THREE.TorusGeometry(0.155, 0.03, 16, 48), hoodieDark, spine, [0, -0.005, 0], [Math.PI / 2, 0, 0], [1, 0.78, 1]);   // the hem band
  mesh(box(0.2, 0.1, 0.03, 0.02), hoodieDark, spine, [0, 0.09, 0.135], [-0.12, 0, 0]);                                    // the front pocket
  // the hood lying on the back, and its drawstrings
  mesh(new THREE.TorusGeometry(0.1, 0.045, 16, 36), hoodie, spine, [0, 0.39, -0.035], [Math.PI / 2 + 0.35, 0, 0], [1.05, 1, 1]);
  for (const side of [1, -1]) {
    const cord = mesh(limb(0.008, 0.008, 0.11, 8), mat('#F3EEE6'), spine, [side * 0.035, 0.38, 0.12], [Math.PI - 0.12, 0, 0]);
    mesh(sphere(0.013, 12, 8), mat('#F3EEE6'), cord, [0, 0.11, 0]);
  }

  // ---- head ----------------------------------------------------------------------------------
  const neck = group(spine, [0, 0.4, 0.0]);
  mesh(limb(0.05, 0.05, 0.08), skin, neck, [0, -0.02, 0]);
  const head = group(neck, [0, 0.2, 0.01]);
  mesh(sphere(0.175, 56, 40), skin, head, [0, 0, 0], null, [1, 0.96, 0.94]);
  for (const side of [1, -1]) mesh(sphere(0.035, 20, 14), skin, head, [side * 0.168, -0.01, -0.01], null, [0.6, 1, 1]);   // ears
  mesh(sphere(0.022, 16, 12), mat(SKIN_SHADE, { roughness: 0.5 }), head, [0, -0.025, 0.165]);                           // nose
  // hair: a cap over the back and top, a soft fringe, and a tuft
  const cap = new THREE.SphereGeometry(0.188, 56, 36, 0, Math.PI * 2, 0, Math.PI * 0.6);
  mesh(cap, hairM, head, [0, 0.008, -0.014], [-0.62, 0, 0], [1.03, 1, 1.02]);
  for (const [x, y, z, s, r] of [[-0.09, 0.105, 0.12, 0.085, 0.4], [0.0, 0.12, 0.13, 0.09, 0], [0.095, 0.1, 0.115, 0.08, -0.45], [-0.15, 0.03, 0.06, 0.065, 0.2], [0.15, 0.03, 0.06, 0.065, -0.2]]) {
    mesh(sphere(s, 28, 20), hairM, head, [x, y, z], [0, 0, r], [1.25, 0.62, 0.8]);
  }
  // the face: eyes that blink, brows that lift, cheeks, a mouth
  const eyes = [], brows = [];
  for (const side of [1, -1]) {
    const e = group(head, [side * 0.062, 0.018, 0.158]);
    const ball = mesh(sphere(0.026, 24, 18), ink, e, [0, 0, 0], null, [0.85, 1.15, 0.5]);
    mesh(sphere(0.008, 10, 8), new THREE.MeshBasicMaterial({ color: '#ffffff' }), e, [side * -0.006 + 0.004, 0.011, 0.012]);
    eyes.push(e);
    const b = group(head, [side * 0.066, 0.082, 0.152]);
    mesh(limb(0.009, 0.009, 0.05, 8), hairM, b, [side * 0.025, 0, 0], [0, 0, Math.PI / 2]);
    brows.push({ g: b, side });
    mesh(new THREE.CircleGeometry(0.03, 24), new THREE.MeshBasicMaterial({ color: BLUSH, transparent: true, opacity: 0.45, depthWrite: false }), head,
      [side * 0.105, -0.045, 0.148], [0, side * 0.55, 0]);
  }
  // mouths: a closed smile, an open grin, a small "o"; one shown at a time
  const mouthMat = mat('#6B2E2A', { roughness: 0.6 });
  const smile = mesh(new THREE.TorusGeometry(0.035, 0.0075, 10, 24, Math.PI), mouthMat, head, [0, -0.07, 0.162], [0, 0, Math.PI]);
  const grinShape = new THREE.Shape(); grinShape.absarc(0, 0, 0.042, Math.PI, Math.PI * 2, false); grinShape.closePath();
  const grin = mesh(new THREE.ShapeGeometry(grinShape, 24), mouthMat, head, [0, -0.058, 0.168], [-0.2, 0, 0]);
  mesh(new THREE.CircleGeometry(0.022, 20, Math.PI * 1.1, Math.PI * 0.8), mat('#E77F7A'), grin, [0, -0.02, 0.001]);
  const oh = mesh(sphere(0.018, 16, 12), mouthMat, head, [0, -0.07, 0.16], null, [1, 1.2, 0.4]);

  // ---- arms ----------------------------------------------------------------------------------
  const arms = {};
  for (const side of [1, -1]) {
    const shoulder = group(spine, [side * 0.175, 0.33, 0]);
    mesh(sphere(0.062), hoodie, shoulder);
    const upper = mesh(limb(0.06, 0.052, 0.25), hoodie, shoulder); upper.rotation.x = Math.PI;
    const elbow = group(shoulder, [0, -0.23, 0]);
    const fore = mesh(limb(0.052, 0.046, 0.21), hoodie, elbow); fore.rotation.x = Math.PI;
    mesh(new THREE.TorusGeometry(0.043, 0.014, 10, 24), hoodieDark, elbow, [0, -0.19, 0], [Math.PI / 2, 0, 0]);   // cuff
    const hand = group(elbow, [0, -0.225, 0]);
    mesh(sphere(0.052, 28, 20), skin, hand, [0, -0.02, 0], null, [0.85, 1, 0.95]);
    const thumb = mesh(limb(0.017, 0.016, 0.045, 10), skin, hand, [-side * 0.03, -0.005, 0.03], [0.4, 0, side * 0.9]);
    arms[side] = { shoulder, elbow, hand, thumb };
  }

  // ---- the pose ----------------------------------------------------------------------------
  const pose = {
    x: 0, z: 0, yaw: 0, y: 0, hipsY: 0, lean: 0, twist: 0, sway: 0,
    headPitch: 0, headYaw: 0, headRoll: 0,
    armL: [0, 0, 0], elbowL: 0, armR: [0, 0, 0], elbowR: 0, wristL: [0, 0, 0], wristR: [0, 0, 0],
    legL: [0, 0, 0], kneeL: 0, legR: [0, 0, 0], kneeR: 0, footL: 0, footR: 0,
    blink: 0, browUp: 0, browSad: 0, eyeX: 0, eyeY: 0, mouth: 'smile', mouthOpen: 0,
  };
  const L = 1, R = -1;
  function apply() {
    const p = pose;
    root.position.set(p.x, 0, p.z);
    root.rotation.y = p.yaw;
    body.position.y = p.y;
    shadow.scale.setScalar(1 - clamp(p.y * 1.5, 0, 0.5));
    shadow.position.y = -p.y + 0.002;
    hips.position.y = 0.64 + p.hipsY;
    spine.rotation.set(p.lean, p.twist, p.sway);
    neck.rotation.set(p.headPitch * 0.4, p.headYaw * 0.4, p.headRoll * 0.4);
    head.rotation.set(p.headPitch * 0.6, p.headYaw * 0.6, p.headRoll * 0.6);
    // arms: [forward/back pitch, twist, out to the side]; the side is mirrored for the right
    arms[L].shoulder.rotation.set(p.armL[0], p.armL[1], p.armL[2]);
    arms[R].shoulder.rotation.set(p.armR[0], -p.armR[1], -p.armR[2]);
    arms[L].elbow.rotation.x = -Math.abs(p.elbowL);
    arms[R].elbow.rotation.x = -Math.abs(p.elbowR);
    arms[L].hand.rotation.set(p.wristL[0], p.wristL[1], p.wristL[2]);
    arms[R].hand.rotation.set(p.wristR[0], -p.wristR[1], -p.wristR[2]);
    legs[L].hip.rotation.set(p.legL[0], p.legL[1], p.legL[2]);
    legs[R].hip.rotation.set(p.legR[0], -p.legR[1], -p.legR[2]);
    legs[L].knee.rotation.x = Math.abs(p.kneeL);
    legs[R].knee.rotation.x = Math.abs(p.kneeR);
    legs[L].ankle.rotation.x = p.footL;
    legs[R].ankle.rotation.x = p.footR;
    // the face
    for (const e of eyes) { e.scale.y = Math.max(0.08, 1 - p.blink); e.position.x = Math.sign(e.position.x) * 0.062 + p.eyeX * 0.012; e.position.y = 0.018 + p.eyeY * 0.01; }
    for (const b of brows) { b.g.position.y = 0.082 + p.browUp * 0.018; b.g.rotation.z = b.side * (p.browSad * 0.35 - p.browUp * 0.05); }
    smile.visible = p.mouth === 'smile'; grin.visible = p.mouth === 'grin'; oh.visible = p.mouth === 'oh';
    grin.scale.set(1, 0.6 + 0.6 * p.mouthOpen, 1);
  }
  return { root, pose, apply, head, arms, legs, spine, hips, handL: arms[L].hand, handR: arms[R].hand };
}

// ---- poses ------------------------------------------------------------------------------------
// Each returns a partial pose; blend() mixes them.
export const POSES = {
  stand: { hipsY: 0, lean: 0, armL: [0.05, 0, 0.12], armR: [0.05, 0, 0.12], elbowL: 0.15, elbowR: 0.15, legL: [0, 0, 0.02], legR: [0, 0, 0.02], kneeL: 0, kneeR: 0 },
  sit: { hipsY: -0.29, lean: -0.05, legL: [-1.5, 0, 0.06], legR: [-1.5, 0, 0.06], kneeL: 1.45, kneeR: 1.45, footL: 0.05, footR: 0.05, armL: [0.15, 0, 0.1], armR: [0.15, 0, 0.1], elbowL: 0.4, elbowR: 0.4 },
  type: { lean: 0.12, armL: [-0.55, 0.15, 0.22], armR: [-0.55, 0.15, 0.22], elbowL: 1.05, elbowR: 1.05, wristL: [0.35, 0, 0], wristR: [0.35, 0, 0], headPitch: 0.08 },
};
export function blend(target, ...pairs) {
  // pairs: [poseObject, weight] applied in order, each lerping from what is there toward it
  for (const [p, w] of pairs) {
    if (w <= 0) continue;
    for (const k in p) {
      const v = p[k];
      if (Array.isArray(v)) target[k] = target[k].map((a, i) => lerp(a, v[i], w));
      else if (typeof v === 'number') target[k] = lerp(target[k], v, w);
      else if (w >= 0.5) target[k] = v;
    }
  }
  return target;
}
// a walk: phase in steps (one step = 0.5), amount 0..1
export function walk(p, phase, amount, stride = 0.5) {
  const a = phase * Math.PI;
  const s = Math.sin(a), c = Math.cos(a);
  p.legL[0] = lerp(p.legL[0], stride * s, amount);
  p.legR[0] = lerp(p.legR[0], -stride * s, amount);
  p.kneeL = lerp(p.kneeL, Math.max(0, -c) * 0.7 + 0.05, amount);
  p.kneeR = lerp(p.kneeR, Math.max(0, c) * 0.7 + 0.05, amount);
  p.armL[0] = lerp(p.armL[0], -0.4 * s, amount);
  p.armR[0] = lerp(p.armR[0], 0.4 * s, amount);
  p.elbowL = lerp(p.elbowL, 0.35 + 0.2 * Math.max(0, -s), amount);
  p.elbowR = lerp(p.elbowR, 0.35 + 0.2 * Math.max(0, s), amount);
  p.y = lerp(p.y, Math.abs(Math.sin(a)) * 0.025, amount);
  p.twist = lerp(p.twist, 0.08 * s, amount);
  p.sway = lerp(p.sway, 0.03 * c, amount);
}
