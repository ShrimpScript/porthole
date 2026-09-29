// The park at golden hour: soft hills, round trees, a path, a bench, a pond, flowers, a low
// warm sun behind the trees and a sky that goes from peach at the horizon to blue.
// The person and the dog play on the lawn around the origin; the camera mostly looks +x/-z.
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { mesh, box, sphere, limb, puck, group, mat, rng, lerp, noise } from './kit.js';

export function makePark(renderer) {
  const scene = new THREE.Scene();
  const R = rng(19);
  // the sky: a dome with a warm band at the horizon and the sun's glow
  const sunDir = new THREE.Vector3(-0.55, 0.16, -0.82).normalize();
  const skyMat = new THREE.ShaderMaterial({
    side: THREE.BackSide, depthWrite: false,
    uniforms: { sunDir: { value: sunDir } },
    vertexShader: 'varying vec3 vD; void main(){ vD = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
    fragmentShader: `
      uniform vec3 sunDir; varying vec3 vD;
      void main(){
        float h = clamp(vD.y, -0.2, 1.0);
        vec3 horizon = vec3(1.0, 0.80, 0.58), mid = vec3(0.72, 0.84, 0.93), top = vec3(0.42, 0.64, 0.86);
        vec3 c = mix(horizon, mid, smoothstep(0.0, 0.22, h));
        c = mix(c, top, smoothstep(0.22, 0.8, h));
        float s = max(dot(normalize(vD), sunDir), 0.0);
        c += vec3(1.0, 0.72, 0.42) * (pow(s, 12.0) * 0.55 + pow(s, 400.0) * 3.0);
        gl_FragColor = vec4(c, 1.0);
      }`,
  });
  const sky = new THREE.Mesh(new THREE.SphereGeometry(80, 48, 24), skyMat);
  sky.castShadow = sky.receiveShadow = false;
  scene.add(sky);
  scene.fog = new THREE.Fog('#F4D9B8', 14, 60);

  // the ground: gentle hills, grass colours varying
  const geo = new THREE.PlaneGeometry(120, 120, 180, 180);
  geo.rotateX(-Math.PI / 2);
  const pos = geo.attributes.position, col = [];
  const cA = new THREE.Color('#86C46F'), cB = new THREE.Color('#6DB064'), cC = new THREE.Color('#A6CF76');
  const heightAt = (x, z) => {
    const d = Math.hypot(x, z);
    const flat = Math.min(1, Math.max(0, (d - 5) / 10));
    return flat * (noise(x * 0.08 + 10, 1) * 1.6 + noise(z * 0.07 + 3, 2) * 1.4 + (d > 30 ? (d - 30) * 0.08 : 0));
  };
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i), z = pos.getZ(i);
    pos.setY(i, heightAt(x, z));
    const n = noise(x * 0.4, 4) * 0.5 + 0.5, m = noise(z * 0.3 + x * 0.1, 6) * 0.5 + 0.5;
    const c = cA.clone().lerp(cB, n).lerp(cC, m * 0.5);
    col.push(c.r, c.g, c.b);
  }
  geo.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  geo.computeVertexNormals();
  const ground = mesh(geo, new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.95 }), scene);
  ground.castShadow = false;

  // the path: a soft sand ribbon curving across the lawn behind them
  const pathPts = [];
  for (let i = 0; i <= 60; i++) { const u = i / 60; pathPts.push(new THREE.Vector3(lerp(-18, 18, u), 0, -3.2 + Math.sin(u * 3.2) * 1.6)); }
  const curve = new THREE.CatmullRomCurve3(pathPts);
  const pv = [], pi = [];
  for (let i = 0; i <= 200; i++) {
    const u = i / 200, p = curve.getPointAt(u), tn = curve.getTangentAt(u), nn = new THREE.Vector3(-tn.z, 0, tn.x).normalize();
    for (const s of [-0.75, 0.75]) { const q = p.clone().addScaledVector(nn, s); pv.push(q.x, heightAt(q.x, q.z) + 0.012, q.z); }
    if (i) { const a = (i - 1) * 2; pi.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
  }
  const pg = new THREE.BufferGeometry(); pg.setAttribute('position', new THREE.Float32BufferAttribute(pv, 3)); pg.setIndex(pi); pg.computeVertexNormals();
  const path = mesh(pg, mat('#EBD6B2', { roughness: 0.95, side: THREE.DoubleSide }), scene);
  path.castShadow = false;

  // trees: a trunk and a cluster of soft balls
  const trunkM = mat('#8A5E44', { roughness: 0.8 });
  const leafMs = ['#5FA46A', '#6DB372', '#4F9460', '#7DBB6E'].map((c) => mat(c, { roughness: 0.85 }));
  const trees = [];
  function tree(x, z, s = 1) {
    const g = group(scene, [x, heightAt(x, z), z]);
    mesh(limb(0.13 * s, 0.1 * s, 1.5 * s, 16), trunkM, g);
    const n = 4 + Math.floor(R() * 3);
    for (let i = 0; i < n; i++) {
      const a = R() * Math.PI * 2, rr = (0.3 + R() * 0.45) * s;
      mesh(sphere((0.6 + R() * 0.35) * s, 24, 18), leafMs[Math.floor(R() * leafMs.length)], g, [Math.cos(a) * rr, (1.8 + R() * 0.7) * s, Math.sin(a) * rr]);
    }
    mesh(sphere(0.85 * s, 24, 18), leafMs[0], g, [0, 2.3 * s, 0]);
    trees.push(g);
    return g;
  }
  for (const [x, z, s] of [[-7, -8, 1.3], [-3.5, -11, 1.5], [1.5, -9.5, 1.2], [5.5, -12, 1.6], [9, -7.5, 1.25], [-11, -5, 1.4], [13, -10, 1.5], [-15, -12, 1.8], [7.5, 4, 1.2], [-9, 5, 1.3], [16, -3, 1.4], [-5, -17, 2], [3, -20, 2.2]]) tree(x, z, s);
  // bushes
  for (let i = 0; i < 14; i++) {
    const a = R() * Math.PI * 2, d = 6 + R() * 10;
    const x = Math.cos(a) * d, z = Math.sin(a) * d - 3;
    const g = group(scene, [x, heightAt(x, z), z]);
    for (let k = 0; k < 3; k++) mesh(sphere(0.3 + R() * 0.25, 18, 12), leafMs[k % 4], g, [(R() - 0.5) * 0.6, 0.18, (R() - 0.5) * 0.5], null, [1, 0.8, 1]);
  }
  // a bench by the path
  const bench = group(scene, [-2.6, 0, -4.6], [0, 0.25, 0]);
  const benchWood = mat('#C98B55', { roughness: 0.6 }), benchIron = mat('#22463F', { roughness: 0.4 });
  for (let k = 0; k < 3; k++) mesh(box(1.6, 0.04, 0.12, 0.015), benchWood, bench, [0, 0.45, -0.14 + k * 0.14]);
  for (let k = 0; k < 2; k++) mesh(box(1.6, 0.12, 0.04, 0.015), benchWood, bench, [0, 0.7 + k * 0.16, -0.26], [-0.15, 0, 0]);
  for (const sx of [-0.7, 0.7]) { mesh(box(0.05, 0.45, 0.4, 0.015), benchIron, bench, [sx, 0.22, -0.02]); mesh(box(0.05, 0.5, 0.05, 0.015), benchIron, bench, [sx, 0.7, -0.25], [-0.15, 0, 0]); }
  // a pond with reeds
  const pond = mesh(puck(2.2, 0.02, 0.3, 64), new THREE.MeshPhysicalMaterial({ color: '#8CC7D8', roughness: 0.08, metalness: 0, clearcoat: 1, envMapIntensity: 1.2 }), scene, [6.5, 0.0, -2.5], null, [1.4, 1, 0.8]);
  pond.castShadow = false;
  for (let i = 0; i < 12; i++) { const a = R() * Math.PI * 2; mesh(limb(0.015, 0.012, 0.4 + R() * 0.3, 6), mat('#5E9A66'), scene, [6.5 + Math.cos(a) * 3.0, 0, -2.5 + Math.sin(a) * 1.75], [(R() - 0.5) * 0.3, 0, (R() - 0.5) * 0.3]); }
  // flowers: little bright dots in the grass
  const flowerGeo = new THREE.SphereGeometry(0.04, 8, 6);
  const flowerCols = ['#F7F2E6', '#F5B8C4', '#F2C94C', '#E7A08A'];
  for (const c of flowerCols) {
    const inst = new THREE.InstancedMesh(flowerGeo, mat(c, { roughness: 0.6 }), 90);
    const m4 = new THREE.Matrix4();
    for (let i = 0; i < 90; i++) {
      const x = (R() - 0.5) * 26, z = (R() - 0.5) * 18 - 2;
      if (Math.hypot(x, z) < 1.2) { m4.makeScale(0, 0, 0); inst.setMatrixAt(i, m4); continue; }
      m4.makeTranslation(x, heightAt(x, z) + 0.05, z); inst.setMatrixAt(i, m4);
    }
    inst.castShadow = false; inst.receiveShadow = true;
    scene.add(inst);
  }
  // grass tufts close by
  // a tuft: three thin blades leaning apart
  const blade = (a, lean) => { const g = new THREE.ConeGeometry(0.014, 0.15, 4); g.translate(0, 0.075, 0); g.rotateZ(lean); g.rotateY(a); return g; };
  const tuftGeo = mergeGeometries([blade(0, 0.25), blade(2.1, 0.3), blade(4.2, 0.2), blade(1.0, -0.05)]);
  const tufts = new THREE.InstancedMesh(tuftGeo, mat('#74B866', { roughness: 0.9 }), 1400);
  { const m4 = new THREE.Matrix4(), q = new THREE.Quaternion(), e = new THREE.Euler();
    for (let i = 0; i < 1400; i++) {
      const x = (R() - 0.5) * 16, z = (R() - 0.5) * 12 - 1;
      e.set((R() - 0.5) * 0.5, R() * 3, (R() - 0.5) * 0.5); q.setFromEuler(e);
      const s = 0.6 + R() * 0.9;
      m4.compose(new THREE.Vector3(x, heightAt(x, z), z), q, new THREE.Vector3(s, s, s)); tufts.setMatrixAt(i, m4);
    } }
  tufts.castShadow = false; tufts.receiveShadow = true;
  scene.add(tufts);
  // clouds
  const cloudM = new THREE.MeshStandardMaterial({ color: '#FFF3E6', roughness: 1, emissive: '#FFE3C8', emissiveIntensity: 0.35, fog: false });
  const clouds = group(scene);
  for (const [x, y, z, s] of [[-20, 16, -45, 3], [8, 19, -50, 3.6], [28, 14, -40, 2.6], [-38, 12, -30, 2.8]]) {
    const c = group(clouds, [x, y, z]);
    for (let k = 0; k < 6; k++) { const m = mesh(sphere(s * (0.5 + R() * 0.5), 20, 14), cloudM, c, [(k - 2.5) * s * 0.55, (R() - 0.3) * s * 0.4, (R() - 0.5) * s * 0.4], null, [1, 0.7, 0.8]); m.castShadow = m.receiveShadow = false; }
  }

  // light: a low warm sun behind and to the left (rims on the characters), a sky fill
  const hemi = new THREE.HemisphereLight('#CFE4F5', '#7FA864', 1.1);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight('#FFD29A', 5.5);
  sun.position.copy(sunDir).multiplyScalar(20);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -7, right: 7, top: 7, bottom: -7, near: 1, far: 50 });
  sun.shadow.bias = -0.0004; sun.shadow.normalBias = 0.03; sun.shadow.radius = 3;
  scene.add(sun, sun.target);
  const front = new THREE.DirectionalLight('#FFE9D2', 1.3);
  front.position.set(3, 4, 8); scene.add(front);
  // an environment for soft reflections, drawn from this sky
  const pm = new THREE.PMREMGenerator(renderer);
  const envScene = new THREE.Scene(); envScene.add(new THREE.Mesh(new THREE.SphereGeometry(10, 32, 16), skyMat));
  scene.environment = pm.fromScene(envScene, 0.05).texture;
  scene.environmentIntensity = 0.45;

  // follow the action: keep the sun's shadow box on the characters
  function focus(x, z) {
    sun.target.position.set(x, 0, z);
    sun.position.copy(sun.target.position).addScaledVector(sunDir, 20);
  }
  focus(0, 0);
  return { scene, heightAt, focus, sun, trees };
}
