// The room: a desk against the back wall, a round window in the left wall that the sun comes
// through, a wall clock, a shelf, a door, a dog bed, a rug. And a second round window in the
// front wall, which is only there for the last shot, when the camera leaves through it.
// setTime(k) moves the light from morning (0) through late afternoon (0.6) to dusk (1).
import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { mesh, box, sphere, limb, puck, group, mat, lerp, clamp, rng, smooth } from './kit.js';

export const ROOM = { x0: -2.6, x1: 2.6, z0: -2.2, z1: 1.9, h: 2.6 };
export const DESK = { x: -0.55, top: 0.64, z: -1.85 };
export const WINDOW = { z: -1.05, y: 1.4, r: 0.44 };            // on the left wall
export const FRONT_WINDOW = { x: -0.55, y: 1.12, r: 0.5 };        // on the front wall
export const DOOR = { x: 1.65, w: 0.86, h: 1.95 };
export const BED = { x: -1.72, z: -0.05 };

function planks(w, h) {
  // a canvas of warm boards, each a little different
  const c = document.createElement('canvas'); c.width = 1024; c.height = 1024;
  const g = c.getContext('2d');
  const R = rng(41);
  const rows = 12;
  for (let i = 0; i < rows; i++) {
    let x = -R() * 400;
    while (x < 1024) {
      const len = 300 + R() * 420;
      const l = 0.9 + R() * 0.16;
      g.fillStyle = `rgb(${Math.round(201 * l)},${Math.round(146 * l)},${Math.round(98 * l)})`;
      g.fillRect(x, i * (1024 / rows), len, 1024 / rows);
      g.fillStyle = 'rgba(120,70,40,0.35)';
      g.fillRect(x, i * (1024 / rows), 2, 1024 / rows);
      // grain
      g.strokeStyle = 'rgba(150,95,55,0.12)'; g.lineWidth = 1.5;
      for (let k = 0; k < 4; k++) { g.beginPath(); const y = i * (1024 / rows) + 8 + R() * (1024 / rows - 16); g.moveTo(x, y); g.bezierCurveTo(x + len / 3, y + 3 - R() * 6, x + 2 * len / 3, y + 3 - R() * 6, x + len, y); g.stroke(); }
      x += len;
    }
    g.fillStyle = 'rgba(110,65,35,0.45)';
    g.fillRect(0, i * (1024 / rows), 1024, 2);
  }
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(w / 2.4, h / 2.4); t.anisotropy = 8;
  return t;
}

// a wall with round holes cut in it, as a thin slab
function wall(w, h, holes, material, depth = 0.1) {
  const s = new THREE.Shape();
  s.moveTo(-w / 2, 0); s.lineTo(w / 2, 0); s.lineTo(w / 2, h); s.lineTo(-w / 2, h); s.closePath();
  for (const hl of holes) {
    const p = new THREE.Path();
    if (hl.r) p.absarc(hl.x, hl.y, hl.r, 0, Math.PI * 2, true);
    else { p.moveTo(hl.x - hl.w / 2, 0.001); p.lineTo(hl.x - hl.w / 2, hl.h); p.lineTo(hl.x + hl.w / 2, hl.h); p.lineTo(hl.x + hl.w / 2, 0.001); }
    s.holes.push(p);
  }
  const g = new THREE.ExtrudeGeometry(s, { depth, bevelEnabled: false, curveSegments: 64 });
  g.translate(0, 0, -depth);
  const m = new THREE.Mesh(g, material);
  m.castShadow = true; m.receiveShadow = true;
  return m;
}

// a porthole's rim: a deep ring with bolts, the brand's window
export function portRim(r, parent, pos, rot, color = '#22463F') {
  const g = group(parent, pos, rot);
  const tube = r * 0.1;
  mesh(new THREE.TorusGeometry(r + tube * 0.4, tube, 20, 96), mat(color, { physical: true, roughness: 0.35, clearcoat: 0.6, clearcoatRoughness: 0.3 }), g);
  mesh(new THREE.TorusGeometry(r - tube * 0.15, tube * 0.28, 12, 96), mat('#E8F0EE', { roughness: 0.4, metalness: 0.2 }), g, [0, 0, tube * 0.35]);
  const boltM = mat('#C9B58A', { metalness: 0.7, roughness: 0.3 });
  for (let k = 0; k < 10; k++) {
    const a = (k + 0.5) / 10 * Math.PI * 2;
    mesh(new THREE.SphereGeometry(tube * 0.32, 12, 8), boltM, g, [Math.cos(a) * (r + tube * 0.4), Math.sin(a) * (r + tube * 0.4), tube * 0.8], null, [1, 1, 0.6]);
  }
  return g;
}

export function makeHome(renderer) {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color('#1b1a24');
  const pm = new THREE.PMREMGenerator(renderer);
  scene.environment = pm.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.3;
  const { x0, x1, z0, z1, h } = ROOM;
  const W = x1 - x0, D = z1 - z0;

  // ---- floor, walls ------------------------------------------------------------------------
  const floorMat = new THREE.MeshStandardMaterial({ map: planks(W, D), roughness: 0.55, color: '#ffffff' });
  const floor = mesh(new THREE.PlaneGeometry(W, D), floorMat, scene, [(x0 + x1) / 2, 0, (z0 + z1) / 2], [-Math.PI / 2, 0, 0]);
  floor.castShadow = false;
  const wallMat = mat('#F2E2CB', { roughness: 0.92 });
  const wainMat = mat('#E3C7A4', { roughness: 0.85 });
  const trimMat = mat('#FBF3E6', { roughness: 0.6 });
  // back wall, with the doorway
  const back = wall(W, h, [{ x: DOOR.x, w: DOOR.w, h: DOOR.h }], wallMat);
  back.position.set((x0 + x1) / 2, 0, z0);
  scene.add(back);
  // left wall, with the round window (the shape is drawn in the wall's own x, which runs along -z)
  const left = wall(D, h, [{ x: -(WINDOW.z - (z0 + z1) / 2), y: WINDOW.y, r: WINDOW.r }], wallMat);
  left.rotation.y = Math.PI / 2;
  left.position.set(x0, 0, (z0 + z1) / 2);
  scene.add(left);
  // the right wall, plain
  const right = wall(D, h, [], wallMat);
  right.rotation.y = -Math.PI / 2;
  right.position.set(x1, 0, (z0 + z1) / 2);
  scene.add(right);
  mesh(box(0.03, 0.82, D, 0.01), wainMat, scene, [x1 - 0.016, 0.41, (z0 + z1) / 2]);
  mesh(box(0.05, 0.03, D, 0.01), trimMat, scene, [x1 - 0.03, 0.83, (z0 + z1) / 2]);
  // the front wall, with the round window the camera leaves through at the end
  const front = wall(W + 0.2, h, [{ x: -(FRONT_WINDOW.x - (x0 + x1) / 2), y: FRONT_WINDOW.y, r: FRONT_WINDOW.r }], mat('#EFE0CA', { roughness: 0.9 }), 0.16);
  front.rotation.y = Math.PI;
  front.position.set((x0 + x1) / 2, 0, z1);
  scene.add(front);
  // outside, the front window wears the porthole's rim
  const frontRim = portRim(FRONT_WINDOW.r, front, [-(FRONT_WINDOW.x - (x0 + x1) / 2), FRONT_WINDOW.y, 0.012]);
  // wainscot and its rail: on the back wall either side of the door, and along the left wall
  const leftOfDoor = DOOR.x - DOOR.w / 2 - x0, rightOfDoor = x1 - (DOOR.x + DOOR.w / 2);
  mesh(box(leftOfDoor, 0.82, 0.03, 0.01), wainMat, scene, [x0 + leftOfDoor / 2, 0.41, z0 + 0.016]);
  mesh(box(rightOfDoor, 0.82, 0.03, 0.01), wainMat, scene, [x1 - rightOfDoor / 2, 0.41, z0 + 0.016]);
  mesh(box(leftOfDoor, 0.03, 0.05, 0.01), trimMat, scene, [x0 + leftOfDoor / 2, 0.83, z0 + 0.03]);
  mesh(box(rightOfDoor, 0.03, 0.05, 0.01), trimMat, scene, [x1 - rightOfDoor / 2, 0.83, z0 + 0.03]);
  mesh(box(0.03, 0.82, D, 0.01), wainMat, scene, [x0 + 0.016, 0.41, (z0 + z1) / 2]);
  mesh(box(0.05, 0.03, D, 0.01), trimMat, scene, [x0 + 0.03, 0.83, (z0 + z1) / 2]);

  // the round window in the left wall: a porthole rim, glass, and a sky beyond
  portRim(WINDOW.r, scene, [x0 + 0.012, WINDOW.y, WINDOW.z], [0, Math.PI / 2, 0]);
  const skyMat = new THREE.ShaderMaterial({
    uniforms: { top: { value: new THREE.Color('#9CCBE8') }, bottom: { value: new THREE.Color('#FCE3B8') }, k: { value: 1 } },
    vertexShader: 'varying vec3 vP; void main(){ vP = (modelMatrix * vec4(position,1.0)).xyz; gl_Position = projectionMatrix * viewMatrix * vec4(vP, 1.0); }',
    fragmentShader: 'uniform vec3 top, bottom; uniform float k; varying vec3 vP; void main(){ float t = smoothstep(0.4, 2.8, vP.y); gl_FragColor = vec4(mix(bottom, top, t) * k, 1.0); }',
    side: THREE.DoubleSide,
  });
  const skyL = mesh(new THREE.PlaneGeometry(14, 8), skyMat, scene, [x0 - 3.5, 1.5, -1], [0, Math.PI / 2, 0]);
  skyL.castShadow = skyL.receiveShadow = false;
  // and the view out of the front window: the same sky, a garden
  const skyF = mesh(new THREE.PlaneGeometry(12, 8), skyMat, scene, [(x0 + x1) / 2, 1.5, z1 + 3.2], [0, Math.PI, 0]);
  skyF.castShadow = skyF.receiveShadow = false;
  // ground outside: under the trees beyond the left window, and in front of the house
  const grassOut = mat('#7DB070', { roughness: 0.95 });
  mesh(new THREE.PlaneGeometry(9, 14), grassOut, scene, [x0 - 4.6, 0, (z0 + z1) / 2], [-Math.PI / 2, 0, 0]).castShadow = false;
  const frontGround = mesh(new THREE.PlaneGeometry(14, 12), grassOut, scene, [(x0 + x1) / 2, 0, z1 + 6.2], [-Math.PI / 2, 0, 0]);
  frontGround.castShadow = false;
  for (const [bx, s] of [[FRONT_WINDOW.x - 1.3, 0.55], [FRONT_WINDOW.x + 1.2, 0.45], [FRONT_WINDOW.x + 2.3, 0.6]]) {
    mesh(sphere(s, 20, 14), mat('#5E9A66', { roughness: 0.9 }), scene, [bx, s * 0.55, z1 + 0.5], null, [1.3, 0.9, 1]).castShadow = false;
  }
  // trees seen through the window
  const R = rng(5);
  const outside = group(scene);
  for (let i = 0; i < 7; i++) {
    const tz = -3.4 + i * 0.75 + R() * 0.3, tx = x0 - 1.4 - R() * 1.2;
    const tr = 0.5 + R() * 0.3, th = 0.5 + R() * 0.6;
    mesh(sphere(tr, 20, 14), mat(i % 2 ? '#7DB77B' : '#6AA56E', { roughness: 0.9 }), outside, [tx, th, tz]).castShadow = false;
    if (th - tr > 0.02) mesh(limb(0.06, 0.05, th - tr + 0.1, 8), mat('#8A5E44', { roughness: 0.8 }), outside, [tx, 0, tz]).castShadow = false;
  }

  // ---- the desk ------------------------------------------------------------------------------
  const wood = mat('#B97A4B', { roughness: 0.5 });
  const woodDark = mat('#9C6440', { roughness: 0.55 });
  const deskW = 1.55, deskD = 0.72;
  mesh(box(deskW, 0.05, deskD, 0.02), wood, scene, [DESK.x, DESK.top - 0.025, DESK.z + 0.02]);
  mesh(box(0.42, DESK.top - 0.05, deskD - 0.06, 0.02), woodDark, scene, [DESK.x + deskW / 2 - 0.23, (DESK.top - 0.05) / 2, DESK.z + 0.02]);   // drawers
  for (const dy of [0.14, 0.33, 0.5]) mesh(box(0.2, 0.02, 0.02, 0.008), mat('#E8D9C2'), scene, [DESK.x + deskW / 2 - 0.23, dy, DESK.z + 0.02 + deskD / 2 - 0.02]);
  for (const dx of [-deskW / 2 + 0.05]) for (const dz of [-deskD / 2 + 0.06, deskD / 2 - 0.04]) mesh(limb(0.025, 0.025, DESK.top - 0.04), woodDark, scene, [DESK.x + dx, 0, DESK.z + 0.02 + dz]);

  // things on the desk: a mug, a plant, a lamp, a notebook
  const mug = group(scene, [DESK.x - 0.52, DESK.top, DESK.z + 0.2]);
  mesh(new THREE.CylinderGeometry(0.042, 0.038, 0.1, 32), mat('#E7A08A', { roughness: 0.4 }), mug, [0, 0.05, 0]);
  mesh(new THREE.TorusGeometry(0.026, 0.008, 10, 24), mat('#E7A08A', { roughness: 0.4 }), mug, [0.048, 0.05, 0], [0, 0, 0]);
  mesh(new THREE.CircleGeometry(0.036, 24), mat('#5A3622', { roughness: 0.2 }), mug, [0, 0.092, 0], [-Math.PI / 2, 0, 0]);
  const plant = group(scene, [DESK.x + 0.72, DESK.top, DESK.z - 0.14]);
  mesh(new THREE.CylinderGeometry(0.07, 0.055, 0.12, 28), mat('#F1EDE4', { roughness: 0.6 }), plant, [0, 0.06, 0]);
  const leafM = mat('#6FAF7B', { roughness: 0.6 });
  const leaves = [];
  for (let i = 0; i < 7; i++) {
    const a = i / 7 * Math.PI * 2;
    const stem = group(plant, [0, 0.11, 0], [0.5 + 0.2 * (i % 2), a, 0]);
    const lf = mesh(sphere(0.05, 16, 12), leafM, stem, [0, 0.1, 0], null, [0.5, 1.4, 0.18]);
    leaves.push(stem);
  }
  const lamp = group(scene, [DESK.x - 0.6, DESK.top, DESK.z - 0.16]);
  mesh(puck(0.07, 0.02, 0.4), mat('#22463F'), lamp);
  mesh(limb(0.01, 0.01, 0.32), mat('#22463F'), lamp, [0, 0.01, 0], [0.25, 0, 0]);
  const shade = group(lamp, [0, 0.32, 0.08], [0.9, 0, 0]);
  mesh(new THREE.ConeGeometry(0.1, 0.12, 32, 1, true), mat('#E7B84A', { roughness: 0.5, side: THREE.DoubleSide }), shade, [0, 0, 0]);
  const bulb = mesh(sphere(0.03, 16, 12), new THREE.MeshStandardMaterial({ color: '#fff4dc', emissive: '#ffd9a0', emissiveIntensity: 0 }), shade, [0, -0.03, 0]);
  const lampLight = new THREE.PointLight('#FFC98A', 0, 4.5, 1.5);
  lampLight.position.set(DESK.x - 0.6, DESK.top + 0.26, DESK.z + 0.06);
  lampLight.castShadow = true; lampLight.shadow.mapSize.set(512, 512); lampLight.shadow.bias = -0.002; lampLight.shadow.radius = 6;
  scene.add(lampLight);
  const book = group(scene, [DESK.x + 0.52, DESK.top, DESK.z + 0.22], [0, 0.3, 0]);
  mesh(box(0.2, 0.025, 0.14, 0.006), mat('#4DB3A3'), book);
  mesh(box(0.19, 0.018, 0.13, 0.004), mat('#FBF7EF'), book, [0.006, 0.004, 0]);

  // ---- the chair ------------------------------------------------------------------------------
  const chair = group(scene, [DESK.x, 0, DESK.z + 0.8]);
  const cm = mat('#34405A', { roughness: 0.7 });
  const seat = group(chair, [0, 0, 0]);
  mesh(box(0.5, 0.08, 0.46, 0.04), mat('#E7A08A', { physical: true, roughness: 0.8, sheen: 0.8, sheenColor: new THREE.Color('#ffd0c0') }), seat, [0, 0.33, 0]);
  mesh(box(0.48, 0.5, 0.07, 0.04), mat('#E7A08A', { physical: true, roughness: 0.8, sheen: 0.8, sheenColor: new THREE.Color('#ffd0c0') }), seat, [0, 0.66, 0.24], [-0.1, 0, 0]);
  mesh(limb(0.025, 0.025, 0.3), cm, seat, [0, 0.0, 0]);
  for (let k = 0; k < 5; k++) {
    const a = k / 5 * Math.PI * 2;
    mesh(box(0.28, 0.03, 0.04, 0.012), cm, chair, [Math.cos(a) * 0.14, 0.04, Math.sin(a) * 0.14], [0, -a, 0]);
    mesh(sphere(0.025, 12, 8), mat('#1E2432'), chair, [Math.cos(a) * 0.27, 0.025, Math.sin(a) * 0.27]);
  }

  // ---- the wall clock (its hands turn in the thought) --------------------------------------------
  const clock = group(scene, [DESK.x + 0.74, 1.6, z0 + 0.03]);
  mesh(puck(0.2, 0.05, 0.4), mat('#22463F', { roughness: 0.4 }), clock, [0, 0, 0], [Math.PI / 2, 0, 0]);
  mesh(new THREE.CircleGeometry(0.17, 48), mat('#FBF7EF', { roughness: 0.6 }), clock, [0, 0, 0.052]);
  for (let k = 0; k < 12; k++) {
    const a = k / 12 * Math.PI * 2;
    mesh(box(0.012, k % 3 ? 0.02 : 0.035, 0.004, 0.002), mat('#22463F'), clock, [Math.sin(a) * 0.145, Math.cos(a) * 0.145, 0.055], [0, 0, -a]);
  }
  const hourHand = group(clock, [0, 0, 0.058]);
  mesh(box(0.014, 0.09, 0.005, 0.003), mat('#1E2432'), hourHand, [0, 0.04, 0]);
  const minHand = group(clock, [0, 0, 0.062]);
  mesh(box(0.009, 0.13, 0.005, 0.003), mat('#1E2432'), minHand, [0, 0.06, 0]);
  mesh(sphere(0.012, 12, 8), mat('#E7B84A', { metalness: 0.6, roughness: 0.3 }), clock, [0, 0, 0.066]);

  // ---- a shelf with books and a photo of the three of them -----------------------------------
  const shelf = group(scene, [DESK.x - 0.15, 1.52, z0 + 0.11]);
  mesh(box(0.8, 0.035, 0.2, 0.01), wood, shelf);
  const bookCols = ['#E7A08A', '#4DB3A3', '#34405A', '#E7B84A', '#8FB9A0', '#F1EDE4'];
  let bx = -0.36;
  for (let i = 0; i < 6; i++) {
    const bh = 0.17 + (i * 37 % 7) * 0.012, bw = 0.035 + (i % 3) * 0.008;
    mesh(box(bw, bh, 0.15, 0.006), mat(bookCols[i], { roughness: 0.7 }), shelf, [bx + bw / 2, 0.018 + bh / 2, 0], [0, 0, i === 5 ? -0.25 : 0]);
    bx += bw + 0.006;
  }
  const photo = group(shelf, [0.2, 0.13, 0.0], [-0.08, -0.2, 0]);
  mesh(box(0.2, 0.24, 0.02, 0.008), mat('#FBF7EF'), photo);
  const pc = document.createElement('canvas'); pc.width = 256; pc.height = 320;
  { const g = pc.getContext('2d');
    const gr = g.createLinearGradient(0, 0, 0, 320); gr.addColorStop(0, '#9CCBE8'); gr.addColorStop(1, '#FCE3B8'); g.fillStyle = gr; g.fillRect(0, 0, 256, 320);
    g.fillStyle = '#7BC96F'; g.fillRect(0, 230, 256, 90);
    g.fillStyle = '#4DB3A3'; g.beginPath(); g.ellipse(95, 210, 40, 55, 0, 0, Math.PI * 2); g.fill();
    g.fillStyle = '#D39A72'; g.beginPath(); g.arc(95, 130, 36, 0, Math.PI * 2); g.fill();
    g.fillStyle = '#3A2A24'; g.beginPath(); g.arc(95, 118, 36, Math.PI, 0); g.fill();
    g.fillStyle = '#3D4352'; g.beginPath(); g.ellipse(180, 250, 38, 26, 0, 0, Math.PI * 2); g.fill(); g.beginPath(); g.arc(200, 215, 24, 0, Math.PI * 2); g.fill();
    g.fillStyle = '#F2E6D2'; g.beginPath(); g.arc(207, 222, 11, 0, Math.PI * 2); g.fill();
    g.fillStyle = '#EFE8DC'; g.fillRect(150, 110, 70, 50); g.fillStyle = '#139C8A'; g.fillRect(170, 125, 8, 16); g.fillRect(190, 125, 8, 16);
  }
  const ptex = new THREE.CanvasTexture(pc); ptex.colorSpace = THREE.SRGBColorSpace;
  mesh(new THREE.PlaneGeometry(0.16, 0.2), new THREE.MeshStandardMaterial({ map: ptex, roughness: 0.4 }), photo, [0, 0, 0.011]);

  // ---- the door ------------------------------------------------------------------------------
  const doorFrame = group(scene, [DOOR.x, 0, z0]);
  for (const sx of [-1, 1]) mesh(box(0.07, DOOR.h + 0.05, 0.14, 0.015), trimMat, doorFrame, [sx * (DOOR.w / 2 + 0.02), (DOOR.h + 0.05) / 2, 0]);
  mesh(box(DOOR.w + 0.11, 0.07, 0.14, 0.015), trimMat, doorFrame, [0, DOOR.h + 0.03, 0]);
  // the hall beyond: a floor, walls and its own warm light, so going out is going somewhere
  const hallMat = mat('#D9BF9C', { roughness: 0.9 });
  const hallBox = group(doorFrame, [0, 0, -0.1]);
  mesh(new THREE.PlaneGeometry(2.2, 2.6), new THREE.MeshStandardMaterial({ map: planks(2.2, 2.6), roughness: 0.6 }), hallBox, [0, 0.001, -1.3], [-Math.PI / 2, 0, 0]).castShadow = false;
  mesh(new THREE.PlaneGeometry(2.2, 2.6), hallMat, hallBox, [0, 1.3, -2.6]).castShadow = false;
  for (const sx of [-1, 1]) mesh(new THREE.PlaneGeometry(2.6, 2.6), hallMat, hallBox, [sx * 1.1, 1.3, -1.3], [0, -sx * Math.PI / 2, 0]).castShadow = false;
  mesh(new THREE.PlaneGeometry(2.2, 2.6), hallMat, hallBox, [0, 2.6, -1.3], [Math.PI / 2, 0, 0]).castShadow = false;
  const hall = new THREE.PointLight('#FFD9A8', 1.2, 4, 1.6);
  hall.position.set(0, 2.2, -1.4); hallBox.add(hall);
  const hinge = group(doorFrame, [DOOR.w / 2 - 0.01, 0, -0.03]);
  const doorMat = mat('#E8D2B5', { roughness: 0.6 });
  mesh(box(DOOR.w - 0.02, DOOR.h - 0.01, 0.045, 0.012), doorMat, hinge, [-DOOR.w / 2, DOOR.h / 2, 0]);
  for (const [py, ph] of [[0.5, 0.62], [1.35, 0.72]]) mesh(box(DOOR.w - 0.24, ph, 0.02, 0.01), mat('#DEC4A3', { roughness: 0.6 }), hinge, [-DOOR.w / 2, py, 0.03]);
  mesh(sphere(0.03, 16, 12), mat('#C9B58A', { metalness: 0.7, roughness: 0.3 }), hinge, [-DOOR.w + 0.09, 0.98, 0.06]);

  // ---- the dog's bed and the rug ---------------------------------------------------------------
  const bed = group(scene, [BED.x, 0, BED.z]);
  mesh(puck(0.42, 0.07, 0.5), mat('#8FB9A0', { physical: true, roughness: 0.9, sheen: 1, sheenColor: new THREE.Color('#dff5e8') }), bed);
  mesh(new THREE.TorusGeometry(0.42, 0.085, 20, 64), mat('#7DA88E', { physical: true, roughness: 0.9, sheen: 1, sheenColor: new THREE.Color('#dff5e8') }), bed, [0, 0.08, 0], [Math.PI / 2, 0, 0]);
  const rug = mesh(puck(0.95, 0.012, 0.5, 96), mat('#E9B8A0', { physical: true, roughness: 1, sheen: 1, sheenColor: new THREE.Color('#fff0e6') }), scene, [DESK.x + 0.1, 0, DESK.z + 1.25]);
  rug.castShadow = false;
  mesh(new THREE.TorusGeometry(0.8, 0.012, 8, 96), mat('#E7A08A', { roughness: 1 }), scene, [DESK.x + 0.1, 0.013, DESK.z + 1.25], [Math.PI / 2, 0, 0]).castShadow = false;
  // a big plant in the corner
  const fig = group(scene, [x0 + 0.45, 0, z0 + 0.45]);
  mesh(new THREE.CylinderGeometry(0.2, 0.16, 0.36, 32), mat('#F1EDE4', { roughness: 0.6 }), fig, [0, 0.18, 0]);
  for (let i = 0; i < 9; i++) {
    const a = i / 9 * Math.PI * 2 + 0.3;
    const st = group(fig, [0, 0.34, 0], [0.35 + 0.25 * (i % 3) / 2, a, 0]);
    mesh(limb(0.01, 0.01, 0.45 + (i % 3) * 0.15), mat('#5E9A66'), st);
    mesh(sphere(0.12, 18, 12), mat(i % 2 ? '#6FAF7B' : '#5E9A66', { roughness: 0.6 }), st, [0, 0.5 + (i % 3) * 0.15, 0.05], [0.3, 0, 0], [1, 1.3, 0.25]);
  }

  // ---- light -------------------------------------------------------------------------------------
  const hemi = new THREE.HemisphereLight('#FFF1DE', '#B98A64', 0.8);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight('#FFE2B5', 6);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -4, right: 4, top: 4, bottom: -4, near: 0.5, far: 20 });
  sun.shadow.bias = -0.0005; sun.shadow.normalBias = 0.02; sun.shadow.radius = 3;
  scene.add(sun, sun.target);
  const fill = new THREE.DirectionalLight('#FFF4E8', 0.6);
  fill.position.set(1.5, 2.2, 4); scene.add(fill);
  const bounce = new THREE.PointLight('#FFD2A0', 0.8, 5, 2);
  bounce.position.set(-1.2, 0.4, -0.6); scene.add(bounce);

  // k: 0 morning, 0.6 late afternoon, 1 dusk
  function setTime(k, lampOverride = null) {
    // the sun comes in low through the round window and moves as the day goes
    const az = lerp(0.35, -0.2, clamp(k / 0.7));        // along z
    const el = lerp(0.62, 0.3, clamp(k / 0.8));
    const dir = new THREE.Vector3(1, -Math.tan(el), az).normalize();
    sun.target.position.set(DESK.x - 0.5, 0.4, WINDOW.z + 0.3);
    sun.position.copy(sun.target.position).addScaledVector(dir, -8);
    const day = 1 - smooth(clamp((k - 0.6) / 0.35));
    sun.intensity = 8 * day * lerp(1, 0.75, clamp(k / 0.6));
    sun.color.set('#FFE2B5').lerp(new THREE.Color('#FFB07A'), smooth(clamp(k / 0.8)));
    hemi.intensity = lerp(0.62, 0.3, smooth(clamp(k)));
    hemi.color.set('#FFF1DE').lerp(new THREE.Color('#7E8FB8'), smooth(clamp((k - 0.5) / 0.5)));
    hemi.groundColor.set('#B98A64').lerp(new THREE.Color('#3A3550'), smooth(clamp((k - 0.5) / 0.5)));
    fill.intensity = lerp(0.6, 0.12, smooth(clamp(k)));
    fill.color.set('#FFF4E8').lerp(new THREE.Color('#8AA0D0'), smooth(clamp(k)));
    bounce.intensity = 0.8 * day;
    scene.environmentIntensity = lerp(0.3, 0.08, smooth(clamp(k)));
    skyMat.uniforms.top.value.set('#9CCBE8').lerp(new THREE.Color('#2A3563'), smooth(clamp((k - 0.4) / 0.6)));
    skyMat.uniforms.bottom.value.set('#FCE3B8').lerp(new THREE.Color('#F2A07A'), smooth(clamp(k / 0.7))).lerp(new THREE.Color('#5B4A78'), smooth(clamp((k - 0.75) / 0.25)));
    const dusk = lampOverride ?? smooth(clamp((k - 0.75) / 0.25));
    lamp.userData.on = dusk;
    lampLight.intensity = 2.2 * dusk;
    lampLight.castShadow = dusk > 0.01;   // a point light's shadow is six renders: only when it is on
    bulb.material.emissiveIntensity = 3 * dusk;
    hall.intensity = lerp(1.2, 2.4, dusk);
    hall.color.set('#FFE2BC').lerp(new THREE.Color('#FFB066'), dusk);
  }
  setTime(0);

  const door = { hinge, open: (a) => { hinge.rotation.y = a * 1.7; } };
  const clockHands = (hours) => { hourHand.rotation.z = -hours / 12 * Math.PI * 2; minHand.rotation.z = -(hours % 1) * Math.PI * 2; };
  clockHands(10.1);
  return { scene, setTime, door, clockHands, front, skyF, chair, seat, mug, plant, leaves, lamp: lampLight, sun, hemi, outside, hall };
}
