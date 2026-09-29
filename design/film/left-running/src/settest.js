import * as THREE from 'three';
import { makeStage } from './stage.js';
import { makeHome, DESK } from './home.js';
import { makeComputer } from './computer.js';
import { makeHuman, POSES, blend } from './human.js';
import { makeDog } from './dog.js';
const W = innerWidth, H = innerHeight;
const stage = makeStage(W, H);
const home = makeHome(stage.renderer);
const comp = makeComputer();
comp.root.position.set(DESK.x, DESK.top, DESK.z - 0.06); home.scene.add(comp.root);
comp.keyboard.position.set(DESK.x, DESK.top, DESK.z + 0.24); home.scene.add(comp.keyboard);
comp.mouse.position.set(DESK.x + 0.33, DESK.top, DESK.z + 0.24); home.scene.add(comp.mouse);
const human = makeHuman(); home.scene.add(human.root);
const dog = makeDog(); home.scene.add(dog.root);
const cam = new THREE.PerspectiveCamera(32, W / H, 0.05, 60);
const V = [
  [[1.9, 1.55, 1.6], [-0.4, 0.8, -1.5], 0.1],
  [[-0.2, 1.05, -0.45], [DESK.x, 0.98, DESK.z], 0.1],
  [[1.9, 1.55, 1.6], [-0.4, 0.8, -1.5], 0.6],
  [[1.9, 1.55, 1.6], [-0.4, 0.8, -1.5], 1.0],
];
window.FPS = 30;
window.ready = document.fonts.ready.then(() => 4);
window.seek = (t) => {
  const v = V[Math.floor(t)];
  cam.position.set(...v[0]); cam.lookAt(...v[1]);
  home.setTime(v[2]);
  Object.assign(comp.face.state, { dark: v[2] > 0.9 ? 1 : 0, wait: v[2] === 0.6 ? 1 : 0, sad: v[2] === 0.6 ? 0.6 : 0 });
  comp.apply(); comp.typing(t, 1);
  const p = human.pose; Object.assign(p, { x: DESK.x, z: DESK.z + 0.82, yaw: Math.PI }); blend(p, [POSES.sit, 1], [POSES.type, 1]); human.apply();
  const d = dog.pose; Object.assign(d, { x: 1.0, z: -1.2, yaw: -0.6, sit: 1, wag: 1, t, tongue: 1 }); dog.apply();
  stage.draw(home.scene, cam);
};
