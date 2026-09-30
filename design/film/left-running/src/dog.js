// The dog: a round, dark-coated pup with a cream bib and muzzle, tan brow spots, floppy ears
// and a teal collar with a tag that jingles. The same rounded pieces as the person. Faces +z.
import * as THREE from 'three';
import { mesh, sphere, limb, group, mat, blob, clamp, lerp } from './kit.js';

const COAT = '#3D4352', COAT_DARK = '#2F3441', CREAM = '#F2E6D2', TAN = '#C98B55', NOSE = '#161214', TONGUE = '#E9837D';

export function makeDog() {
  const root = new THREE.Group();
  const coat = mat(COAT, { physical: true, roughness: 0.75, sheen: 1, sheenRoughness: 0.5, sheenColor: new THREE.Color('#8e9ab5') });
  const coatDark = mat(COAT_DARK, { physical: true, roughness: 0.8, sheen: 1, sheenColor: new THREE.Color('#7a86a0') });
  const cream = mat(CREAM, { physical: true, roughness: 0.75, sheen: 1, sheenColor: new THREE.Color('#ffffff') });
  const tan = mat(TAN, { roughness: 0.7 });
  const shiny = mat(NOSE, { physical: true, roughness: 0.12, clearcoat: 1 });

  const shadow = blob(root, 0.34, 0.3);
  const lift = group(root);                                // hops and leaps
  const body = group(lift, [0, 0.24, 0]);                  // pitch for sitting and bounding
  // the body: a capsule lying along z
  const trunk = mesh(limb(0.135, 0.13, 0.46, 32), coat, body, [0, 0, -0.23]); trunk.rotation.x = Math.PI / 2;
  mesh(sphere(0.12), cream, body, [0, -0.03, 0.14], null, [0.95, 0.95, 0.9]);        // the bib
  mesh(sphere(0.11), cream, body, [0, -0.07, -0.02], null, [0.9, 0.6, 1.6]);         // the belly

  // the head
  const neck = group(body, [0, 0.07, 0.19]);
  const head = group(neck, [0, 0.12, 0.04]);
  mesh(sphere(0.135, 48, 36), coat, head, [0, 0, 0], null, [1, 0.95, 0.98]);
  mesh(sphere(0.075, 32, 24), cream, head, [0, -0.045, 0.1], null, [1.05, 0.78, 0.95]);   // the muzzle
  mesh(sphere(0.022, 20, 16), cream, head, [0, 0.055, 0.118], null, [0.55, 1.3, 0.5]);    // the blaze
  mesh(sphere(0.024, 20, 16), shiny, head, [0, -0.02, 0.17], null, [1.15, 0.85, 0.9]);    // the nose
  const tongue = mesh(sphere(0.03, 16, 12), mat(TONGUE, { roughness: 0.4 }), head, [0, -0.1, 0.13], [0.5, 0, 0], [0.8, 0.35, 1.2]);
  const eyes = [], brows = [];
  for (const side of [1, -1]) {
    const e = group(head, [side * 0.055, 0.025, 0.112]);
    mesh(sphere(0.022, 20, 16), shiny, e, [0, 0, 0], null, [1, 1.1, 0.7]);
    mesh(sphere(0.006, 10, 8), new THREE.MeshBasicMaterial({ color: '#ffffff' }), e, [0.004, 0.01, 0.013]);
    eyes.push(e);
    brows.push(mesh(sphere(0.016, 14, 10), tan, head, [side * 0.052, 0.068, 0.108], null, [1.2, 0.8, 0.5]));
  }
  const ears = [];
  for (const side of [1, -1]) {
    const pivot = group(head, [side * 0.1, 0.07, -0.01]);
    const ear = mesh(sphere(0.06, 24, 18), coatDark, pivot, [side * 0.025, -0.06, 0], null, [0.45, 1.15, 0.8]);
    ear.rotation.z = side * 0.25;
    ears.push({ pivot, side });
  }

  // the collar and its tag
  const collar = mesh(new THREE.TorusGeometry(0.085, 0.018, 12, 32), mat('#3FD4C0', { roughness: 0.45 }), neck, [0, 0.03, 0.0], [Math.PI / 2 - 0.35, 0, 0]);
  const tag = group(neck, [0, -0.05, 0.07]);
  mesh(new THREE.CylinderGeometry(0.022, 0.022, 0.006, 20), mat('#E7B84A', { metalness: 0.8, roughness: 0.25 }), tag, [0, -0.02, 0], [Math.PI / 2, 0, 0]);

  // legs, with cream paws
  const legs = [];
  for (const [x, z] of [[0.085, 0.16], [-0.085, 0.16], [0.085, -0.18], [-0.085, -0.18]]) {
    const hip = group(body, [x, -0.05, z]);
    const l = mesh(limb(0.045, 0.04, 0.17), coat, hip); l.rotation.x = Math.PI;
    mesh(sphere(0.045, 20, 14), cream, hip, [0, -0.17, 0.012], null, [1, 0.75, 1.25]);
    legs.push(hip);
  }
  // the tail, a curl that wags; its root sits just inside the rump, so it grows out of the body
  const tailBase = group(body, [0, 0.06, -0.2]);
  const tail = mesh(limb(0.035, 0.025, 0.16, 16), coat, tailBase, [0, 0, 0], [-0.7, 0, 0]);
  mesh(sphere(0.03, 14, 10), cream, tail, [0, 0.155, 0]);

  // a toy: the ball, held in the mouth or free in the world
  const ball = mesh(sphere(0.05, 28, 20), mat('#F2C94C', { physical: true, roughness: 0.6, sheen: 1, sheenColor: new THREE.Color('#fff4b0') }), null);
  const ballSeam = mesh(new THREE.TorusGeometry(0.05, 0.004, 8, 40), mat('#FFFFFF', { roughness: 0.6 }), ball, [0, 0, 0], [0.5, 0.3, 0]);
  ballSeam.castShadow = false;

  const pose = {
    x: 0, z: 0, yaw: 0, y: 0, pitch: 0, roll: 0, bodyY: 0,
    headPitch: 0, headYaw: 0, headTilt: 0, earFlop: 0, earUp: 0,
    wag: 0, wagSpeed: 10, t: 0,
    legs: [0, 0, 0, 0], sit: 0, lie: 0,
    blink: 0, tongue: 0, happy: 0, stretch: 0,
  };
  function apply() {
    const p = pose;
    root.position.set(p.x, 0, p.z);
    root.rotation.y = p.yaw;
    lift.position.y = p.y;
    shadow.scale.setScalar(1 - clamp(p.y * 1.4, 0, 0.55));
    shadow.position.y = -p.y + 0.002;
    // sitting: the body pitches up at the front, the rear drops, the hind legs fold forward
    const sit = clamp(p.sit), lie = clamp(p.lie);
    body.position.y = 0.24 + p.bodyY - 0.06 * sit - 0.13 * lie;
    body.position.z = -0.05 * sit;
    body.rotation.x = p.pitch - 0.55 * sit;
    body.rotation.z = p.roll;
    body.scale.set(1, 1, 1 + 0.12 * p.stretch);
    neck.rotation.x = 0.35 * sit + p.headPitch * 0.4 - 0.25 * lie;
    head.rotation.set(p.headPitch * 0.6 + 0.1 * lie, p.headYaw, p.headTilt);
    legs[0].rotation.x = p.legs[0] + 0.5 * sit - 1.3 * lie;
    legs[1].rotation.x = p.legs[1] + 0.5 * sit - 1.3 * lie;
    legs[2].rotation.x = p.legs[2] - 0.9 * sit - 1.4 * lie;
    legs[3].rotation.x = p.legs[3] - 0.9 * sit - 1.4 * lie;
    for (const e of ears) {
      e.pivot.rotation.z = e.side * (0.15 + p.earFlop * 0.5 - p.earUp * 0.6);
      e.pivot.rotation.x = -p.earUp * 0.4 + p.earFlop * 0.2;
    }
    tailBase.rotation.set(0.2 - 0.3 * lie, Math.sin(p.t * p.wagSpeed) * 0.7 * p.wag, 0);
    for (const e of eyes) e.scale.y = Math.max(0.1, 1 - p.blink - 0.5 * p.happy);
    brows[0].position.y = brows[1].position.y = 0.068 + 0.012 * p.happy;
    tongue.visible = p.tongue > 0.05;
    tongue.scale.set(0.8, 0.35, 0.6 + 0.8 * p.tongue);
    tag.rotation.x = Math.sin(p.t * 13) * 0.25 * clamp(Math.abs(p.legs[0]) * 4 + p.wag * 0.3);
  }
  // a trot or a run: phase in strides, amount 0..1; gallop bunches front and back legs
  function gait(phase, amount, gallop = 0) {
    const a = phase * Math.PI * 2;
    const trot = [Math.sin(a), Math.sin(a + Math.PI), Math.sin(a + Math.PI), Math.sin(a)];
    const gal = [Math.sin(a), Math.sin(a + 0.4), Math.sin(a + Math.PI), Math.sin(a + Math.PI + 0.4)];
    for (let i = 0; i < 4; i++) pose.legs[i] = lerp(pose.legs[i], 0.55 * lerp(trot[i], gal[i], gallop), amount);
    pose.bodyY = lerp(pose.bodyY, Math.abs(Math.sin(a)) * lerp(0.02, 0.07, gallop), amount);
    pose.pitch = lerp(pose.pitch, Math.sin(a) * 0.12 * gallop, amount);
    pose.earFlop = lerp(pose.earFlop, 0.5 + 0.5 * Math.sin(a + 1), amount);
  }
  return { root, pose, apply, gait, head, ball, mouth: head, neck, body };
}
