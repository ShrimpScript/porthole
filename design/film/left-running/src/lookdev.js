// Look development: the cast on a warm sweep, from a few sides. seek(t) picks the view.
import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { makeStage } from './stage.js';
import { makeComputer } from './computer.js';
import { makeHuman, POSES, blend } from './human.js';
import { makeDog } from './dog.js';
import { mesh, mat } from './kit.js';

const W = innerWidth, H = innerHeight;
const stage = makeStage(W, H);
const scene = new THREE.Scene();
scene.background = new THREE.Color('#F1E2CC');
const pm = new THREE.PMREMGenerator(stage.renderer);
scene.environment = pm.fromScene(new RoomEnvironment(), 0.04).texture;
scene.environmentIntensity = 0.35;
const floor = mesh(new THREE.PlaneGeometry(30, 30), mat('#EAD3B5', { roughness: 0.95 }), scene, [0, 0, 0], [-Math.PI / 2, 0, 0]);
floor.castShadow = false;
const hemi = new THREE.HemisphereLight('#FFF3E0', '#B98E6A', 0.9); scene.add(hemi);
const sun = new THREE.DirectionalLight('#FFE6C4', 2.6); sun.position.set(-2.5, 4, 3); sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048); Object.assign(sun.shadow.camera, { left: -3, right: 3, top: 3, bottom: -3 }); sun.shadow.bias = -0.0004; sun.shadow.normalBias = 0.02; sun.shadow.radius = 4;
scene.add(sun);
const rim = new THREE.DirectionalLight('#CFE8FF', 1.2); rim.position.set(2, 2.5, -3); scene.add(rim);

const comp = makeComputer();
comp.root.position.set(0.9, 0.6, 0); scene.add(comp.root);
comp.keyboard.position.set(0.9, 0.6, 0.3); scene.add(comp.keyboard);
const table = mesh(new THREE.BoxGeometry(1.2, 0.6, 0.8), mat('#C98B55', { roughness: 0.6 }), scene, [0.9, 0.3, 0.1]);
const human = makeHuman(); scene.add(human.root);
const human2 = makeHuman(); scene.add(human2.root);
const dog = makeDog(); scene.add(dog.root);
dog.root.add(dog.ball); dog.ball.position.set(0, 0.05, 0.5);

const cam = new THREE.PerspectiveCamera(30, W / H, 0.05, 50);
const VIEWS = [
  [[0.2, 1.1, 3.4], [0.2, 0.7, 0]],
  [[0.9, 1.0, 1.25], [0.9, 0.95, 0]],
  [[-0.6, 1.3, 1.0], [-0.6, 1.2, 0]],
  [[-1.55, 0.5, 1.4], [-1.55, 0.3, 0]],
  [[2.5, 1.4, 2.6], [0.2, 0.6, 0]],
  [[-0.6, 1.2, -1.4], [-0.6, 1.0, 0]],
];
window.FPS = 30;
window.ready = document.fonts.ready.then(() => 6);
window.seek = (t) => {
  const v = VIEWS[Math.min(VIEWS.length - 1, Math.floor(t))];
  cam.position.set(...v[0]); cam.lookAt(...v[1]);
  Object.assign(comp.face.state, { happy: t >= 2 && t < 3 ? 1 : 0, wait: t >= 1 && t < 2 ? 0 : 0, dark: t >= 4 ? 1 : 0 });
  comp.pose.lean = 0.05; comp.pose.tilt = 0.05;
  comp.apply();
  const p = human.pose; Object.assign(p, { x: -0.6, z: 0, yaw: 0.25 }); blend(p, [POSES.stand, 1]); p.armR = [-0.2, 0, 0.5]; p.elbowR = 1.9; p.mouth = 'grin'; p.mouthOpen = 0.6; human.apply();
  const q = human2.pose; Object.assign(q, { x: 0.35, z: 0.75, yaw: Math.PI }); blend(q, [POSES.sit, 1], [POSES.type, 1]); human2.apply();
  const d = dog.pose; Object.assign(d, { x: -1.55, z: 0.1, yaw: 0.5, sit: t >= 3 ? 1 : 0, wag: 1, t: 0.3, tongue: 1, happy: 0.3, headTilt: 0.25 }); dog.apply();
  stage.mix.amount.value = 0;
  stage.draw(scene, cam);
};
