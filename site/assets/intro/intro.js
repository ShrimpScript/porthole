// Porthole's opening shot: a phone under a spotlight, the app waking on its screen, and
// the moments that matter lifting out of it. Then scrolling carries the camera into the
// screen and down into the page.
//
// Real-time three.js. The page does not depend on it: the hero's words and buttons are
// ordinary HTML, a still of the settled frame stands in until the scene is ready, and
// anything that fails here leaves that still in place.
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';

const root = document.querySelector('.cine');
const gsap = window.gsap;
const ScrollTrigger = window.ScrollTrigger;
if (root && gsap && ScrollTrigger) start().catch(err => {
  // The still stays; the page reads the same.
  console.warn('porthole intro:', err);
  root.classList.add('cine-failed');
  document.documentElement.classList.remove('cine-pending');
});

async function start() {
  gsap.registerPlugin(ScrollTrigger);
  const Q = new URLSearchParams(location.search);
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const small = () => innerWidth < 760;
  let seen = false;
  try { seen = localStorage.getItem('porthole-intro') === 'seen'; } catch (e) { /* private window */ }
  if (Q.has('intro')) seen = false;          // ?intro plays it again
  if (Q.has('settled')) seen = true;         // ?settled starts at the end (for stills)

  // Whether the title card is already showing, put up by the page while this loaded.
  const titleUp = document.documentElement.classList.contains('cine-pending');
  const canvas = root.querySelector('.cine-canvas');
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
  // Sharp enough, and no sharper: bloom at 2x costs a mid laptop its frame rate.
  let dprCap = small() ? 1.25 : 1.5;
  renderer.setPixelRatio(Math.min(devicePixelRatio, dprCap));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFShadowMap;

  const INK = new THREE.Color('#030807');
  const TEAL = new THREE.Color('#3FD4C0');
  const scene = new THREE.Scene();
  scene.background = INK.clone();
  scene.fog = new THREE.Fog(INK, 0.9, 2.2);
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.32;

  const camera = new THREE.PerspectiveCamera(26, 1, 0.005, 10);
  const look = new THREE.Vector3(0, 0, 0);

  // ------------------------------------------------------------------ stage ---
  const floorY = -0.112;
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(6, 6),
    new THREE.MeshStandardMaterial({ color: '#030606', roughness: 0.96, metalness: 0 }));
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = floorY;
  floor.receiveShadow = true;
  scene.add(floor);

  const spot = new THREE.SpotLight('#fff6ea', 0, 3, 0.2, 0.9, 1.4);
  spot.position.set(0.02, 0.95, 0.12);
  spot.target.position.set(0, 0, 0);
  spot.castShadow = true;
  spot.shadow.mapSize.set(1024, 1024);
  spot.shadow.bias = -0.0004;
  spot.shadow.radius = 6;
  scene.add(spot, spot.target);

  const rim = new THREE.DirectionalLight(TEAL, 0);
  rim.position.set(-0.6, 0.25, -0.5);
  const rim2 = new THREE.DirectionalLight('#cfe9ff', 0);
  rim2.position.set(0.7, 0.35, -0.4);
  // A soft key from the front, so the frame reads when the phone faces the camera.
  const key = new THREE.DirectionalLight('#ffffff', 0);
  key.position.set(0.3, 0.4, 0.8);
  scene.add(rim, rim2, key);
  const sweep = new THREE.PointLight('#ffffff', 0, 0.6, 1.2);
  sweep.position.set(-0.3, 0.1, -0.22);
  scene.add(sweep);

  // The beam itself: an open cone, brightest near the lamp and at its heart.
  const beam = new THREE.Mesh(new THREE.ConeGeometry(0.3, 1.05, 64, 1, true), new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide,
    uniforms: { uOpacity: { value: 0 }, uTime: { value: 0 }, uColor: { value: new THREE.Color('#e8f6f2') } },
    vertexShader: `varying vec3 vN; varying vec3 vV; varying float vH;
      void main(){ vec4 w = modelMatrix * vec4(position,1.0); vH = uv.y;
        vN = normalize(normalMatrix * normal); vV = normalize(-(viewMatrix * w).xyz);
        gl_Position = projectionMatrix * viewMatrix * w; }`,
    fragmentShader: `uniform float uOpacity; uniform float uTime; uniform vec3 uColor;
      varying vec3 vN; varying vec3 vV; varying float vH;
      void main(){ float edge = pow(abs(dot(vN, vV)), 2.2);
        float fall = smoothstep(0.0, 0.85, vH) * (0.35 + 0.65 * vH);
        float shimmer = 0.9 + 0.1 * sin(uTime * 0.7 + vH * 9.0);
        gl_FragColor = vec4(uColor, edge * fall * shimmer * uOpacity * 0.16); }`,
  }));
  beam.position.set(0.02, 0.95 - 0.525, 0.12);
  scene.add(beam);

  // A pool of light where the beam lands, and the phone's soft contact shadow.
  const radial = (inner, outer) => {
    const c = document.createElement('canvas'); c.width = c.height = 256;
    const g = c.getContext('2d'), gr = g.createRadialGradient(128, 128, 0, 128, 128, 128);
    gr.addColorStop(0, inner); gr.addColorStop(1, outer);
    g.fillStyle = gr; g.fillRect(0, 0, 256, 256);
    const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
  };
  const pool = new THREE.Mesh(new THREE.PlaneGeometry(0.9, 0.9), new THREE.MeshBasicMaterial({
    map: radial('rgba(210,240,232,0.32)', 'rgba(0,0,0,0)'), transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, opacity: 0 }));
  pool.rotation.x = -Math.PI / 2;
  pool.position.set(0.01, floorY + 0.0005, 0.06);
  scene.add(pool);

  // Dust in the beam, drifting and catching the light.
  const DUST = small() ? 140 : 320;
  const dustGeo = new THREE.BufferGeometry();
  const dp = new Float32Array(DUST * 3), ds = new Float32Array(DUST);
  for (let i = 0; i < DUST; i++) {
    const h = Math.random(), r = Math.sqrt(Math.random()) * (0.04 + 0.26 * (1 - h)), a = Math.random() * Math.PI * 2;
    dp[i * 3] = 0.02 + Math.cos(a) * r; dp[i * 3 + 1] = floorY + 0.02 + h * 0.8; dp[i * 3 + 2] = 0.12 + Math.sin(a) * r;
    ds[i] = Math.random();
  }
  dustGeo.setAttribute('position', new THREE.BufferAttribute(dp, 3));
  dustGeo.setAttribute('seed', new THREE.BufferAttribute(ds, 1));
  const dust = new THREE.Points(dustGeo, new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    uniforms: { uTime: { value: 0 }, uOpacity: { value: 0 }, uScale: { value: renderer.getPixelRatio() } },
    vertexShader: `attribute float seed; uniform float uTime; uniform float uScale; varying float vA;
      void main(){ vec3 p = position; p.y += mod(uTime * 0.006 * (0.5 + seed), 0.8) - 0.4 * seed;
        p.x += sin(uTime * 0.2 + seed * 40.0) * 0.01; p.z += cos(uTime * 0.17 + seed * 30.0) * 0.01;
        vec4 mv = modelViewMatrix * vec4(p, 1.0); gl_Position = projectionMatrix * mv;
        gl_PointSize = (1.2 + 2.2 * seed) * uScale * (0.35 / -mv.z);
        vA = 0.35 + 0.65 * abs(sin(uTime * (0.4 + seed) + seed * 12.0)); }`,
    fragmentShader: `uniform float uOpacity; varying float vA;
      void main(){ float d = length(gl_PointCoord - 0.5); if (d > 0.5) discard;
        gl_FragColor = vec4(vec3(0.92, 0.98, 0.96), (1.0 - d * 2.0) * vA * uOpacity * 0.7); }`,
  }));
  scene.add(dust);

  // ------------------------------------------------------------------ phone ---
  const tex = new THREE.TextureLoader();
  // The model's UVs follow glTF (image top at v = 0), a plain plane's the opposite.
  const load = (url, flip = false) => new Promise((res, rej) => tex.load(url, t => {
    t.colorSpace = THREE.SRGBColorSpace; t.flipY = flip;
    t.anisotropy = renderer.capabilities.getMaxAnisotropy(); res(t);
  }, undefined, rej));
  const base = '/assets/intro/';
  const [gltf, manifest, ...maps] = await Promise.all([
    new GLTFLoader().setMeshoptDecoder(MeshoptDecoder).loadAsync(base + 'phone.glb'),
    fetch(base + 'tex/manifest.json').then(r => r.json()),
    ...['welcome', 'sessions', 'session-question', 'approval', 'terminal', 'session-working']
      .map(n => load(`${base}tex/screen-${n}.webp`)),
    ...['question', 'approval', 'terminal', 'notify'].map(n => load(`${base}tex/card-${n}.webp`, true)),
  ]);
  const S = Object.fromEntries(['welcome', 'sessions', 'question', 'approval', 'terminal', 'working'].map((k, i) => [k, maps[i]]));
  const C = Object.fromEntries(['question', 'approval', 'terminal', 'notify'].map((k, i) => [k, maps[6 + i]]));

  const phone = new THREE.Group();
  const spin = new THREE.Group();   // turns about the phone's own centre
  spin.add(gltf.scene);
  phone.add(spin);
  scene.add(phone);
  let screen = null;
  gltf.scene.traverse(o => {
    if (!o.isMesh) return;
    o.castShadow = true;
    if (o.material?.name === 'Screen') screen = o;
    if (o.material?.name === 'Frame' || o.material?.name === 'Visor') o.material.envMapIntensity = 1.6;
  });
  if (!screen) throw new Error('no screen in the model');

  // The display: the current screen, and a second layer the next one fades in on.
  const screenMat = new THREE.MeshBasicMaterial({ map: S.welcome, toneMapped: false, color: new THREE.Color(0, 0, 0) });
  screen.material = screenMat;
  const nextScreen = screen.clone();
  const nextMat = new THREE.MeshBasicMaterial({ map: S.sessions, toneMapped: false, transparent: true, opacity: 0, depthWrite: false });
  nextScreen.material = nextMat;
  nextScreen.renderOrder = 2;
  screen.parent.add(nextScreen);
  nextScreen.position.copy(screen.position);
  nextScreen.translateOnAxis(new THREE.Vector3(0, 0, 1), 0); // same place; drawn after
  // Glass over the display: it catches the light as the phone turns.
  const glass = screen.clone();
  // Added on top, so it only ever brightens: reflections, never a grey film over the app.
  glass.material = new THREE.MeshPhysicalMaterial({ color: '#000000', roughness: 0.05, metalness: 0,
    transparent: true, opacity: 0.3, envMapIntensity: 1.6, clearcoat: 1, depthWrite: false,
    blending: THREE.AdditiveBlending });
  glass.renderOrder = 3;
  screen.parent.add(glass);

  // A new screen fades in over the old, then takes its place. Plain values rather than
  // callbacks: a jump through the timeline (Skip, a still, a returning visit) skips
  // callbacks, but always lands every value where it belongs.
  const swapTo = (tl, map, at, dur = 0.5) => tl
    .set(nextMat, { map }, at)
    .fromTo(nextMat, { opacity: 0 }, { opacity: 1, duration: dur, ease: 'power2.inOut' }, at)
    .set(screenMat, { map }, at + dur)
    .set(nextMat, { opacity: 0 }, at + dur);

  // Screen pixels to metres on the phone's face (the display is 66.8 mm across 360 px).
  const box = new THREE.Box3().setFromObject(screen);
  const PX = (box.max.x - box.min.x) / manifest.screen.w;
  const FACE = box.max.z;
  const onScreen = (r, lift = 0.0006) => new THREE.Vector3(
    (r.x + r.w / 2 - manifest.screen.w / 2) * PX, (manifest.screen.h / 2 - r.y - r.h / 2) * PX, FACE + lift);

  // A soft teal glow behind a lifted card: the motion-graphic halo.
  const glowTex = (() => {
    const c = document.createElement('canvas'); c.width = 256; c.height = 256;
    const g = c.getContext('2d'); g.filter = 'blur(26px)'; g.fillStyle = 'rgba(63,212,192,0.9)';
    g.beginPath(); g.roundRect(58, 58, 140, 140, 28); g.fill();
    const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
  })();
  const card = (name, map) => {
    const r = manifest.cards[name];
    const g = new THREE.Group();
    const face = new THREE.Mesh(new THREE.PlaneGeometry(r.w * PX, r.h * PX),
      new THREE.MeshBasicMaterial({ map, transparent: true, toneMapped: false, opacity: 0, depthWrite: false }));
    const halo = new THREE.Mesh(new THREE.PlaneGeometry(r.w * PX * 1.9, r.h * PX * 1.9),
      new THREE.MeshBasicMaterial({ map: glowTex, transparent: true, opacity: 0, depthWrite: false, blending: THREE.AdditiveBlending, toneMapped: false }));
    halo.position.z = -0.0015;
    g.add(halo, face);
    g.position.copy(onScreen(r));
    g.userData = { home: g.position.clone(), face, halo };
    face.renderOrder = 6; halo.renderOrder = 5;
    spin.add(g);   // in the phone's own frame, so a card stays on its screen as it turns
    return g;
  };
  const qCard = card('question', C.question);
  const aCard = card('approval', C.approval);
  const tCard = card('terminal', C.terminal);
  const nCard = card('notify', C.notify);

  // A tap: a ring that opens where a finger would land.
  const tap = new THREE.Mesh(new THREE.RingGeometry(0.0024, 0.003, 48),
    new THREE.MeshBasicMaterial({ color: TEAL, transparent: true, opacity: 0, toneMapped: false, depthWrite: false }));
  tap.renderOrder = 8;
  // The Porthole ring, opening off the screen as it wakes.
  const halo = new THREE.Mesh(new THREE.RingGeometry(0.0118, 0.0124, 96),
    new THREE.MeshBasicMaterial({ color: TEAL, transparent: true, opacity: 0, toneMapped: false, depthWrite: false, blending: THREE.AdditiveBlending }));
  // Centred on the ring the welcome screen draws (its logo, 228 px from the top).
  halo.position.set(0, (manifest.screen.h / 2 - 228) * PX, FACE + 0.002);
  halo.renderOrder = 7;
  spin.add(tap, halo);

  // ------------------------------------------------------------ composition ---
  const composer = new EffectComposer(renderer);
  composer.addPass(new RenderPass(scene, camera));
  const bloom = new UnrealBloomPass(new THREE.Vector2(1, 1), 0.55, 0.6, 0.82);
  composer.addPass(bloom);
  composer.addPass(new OutputPass());
  const useBloom = () => !small();

  // Where things rest once the intro is over: the phone to the right of the words on a
  // wide screen, above them on a narrow one.
  const rest = () => small()
    ? { x: 0, y: 0.13, rotY: -0.28, rotX: 0.06, cam: new THREE.Vector3(0, 0.02, 0.95), look: new THREE.Vector3(0, 0.01, 0) }
    : { x: 0.085, y: 0.004, rotY: -0.42, rotX: 0.05, cam: new THREE.Vector3(0.012, 0.02, 0.6), look: new THREE.Vector3(0.028, 0, 0) };

  const stage = root.querySelector('.cine-stage');
  function resize() {
    const w = stage.clientWidth, h = stage.clientHeight;
    renderer.setSize(w, h, false);
    composer.setSize(w, h);
    bloom.setSize(w, h);
    camera.aspect = w / h;
    // A tall, narrow screen needs the camera further back to keep the phone whole.
    camera.fov = w / h < 0.8 ? 34 : 26;
    camera.updateProjectionMatrix();
    dust.material.uniforms.uScale.value = renderer.getPixelRatio();
  }
  addEventListener('resize', resize);
  resize();

  // ----------------------------------------------------------------- beats ---
  const ui = {
    skip: root.querySelector('.cine-skip'),
    replay: root.querySelector('.cine-replay'),
    caps: [...root.querySelectorAll('.cine-cap')],
    hero: root.querySelector('.cine-hero'),
    cue: root.querySelector('.cine-cue'),
    title: root.querySelector('.cine-title'),
  };
  const cap = (i, at, tl, hold = 1.4) => {
    const el = ui.caps[i];
    if (!el) return;
    tl.fromTo(el, { autoAlpha: 0, y: 14 }, { autoAlpha: 1, y: 0, duration: 0.6, ease: 'power3.out' }, at)
      .to(el, { autoAlpha: 0, y: -10, duration: 0.45, ease: 'power2.in' }, at + hold);
  };
  const lift = (g, to, at, tl, hold) => {
    const { face, halo: h, home } = g.userData;
    // On a narrow screen there is no room to the sides: cards come forward instead.
    if (small()) to = { ...to, x: to.x * 0.28, z: Math.max(to.z, 0) + 0.045, ry: (to.ry || 0) * 0.5 };
    tl.set(face.material, { opacity: 1 }, at)
      .to(g.position, { x: to.x, y: to.y, z: home.z + to.z, duration: 0.9, ease: 'power3.out' }, at)
      .to(g.rotation, { y: to.ry || 0, x: to.rx || 0, duration: 0.9, ease: 'power3.out' }, at)
      .to(g.scale, { x: to.s, y: to.s, z: to.s, duration: 0.9, ease: 'power3.out' }, at)
      .to(h.material, { opacity: 0.55, duration: 0.7, ease: 'power2.out' }, at + 0.15)
      .to(h.material, { opacity: 0, duration: 0.45 }, at + hold)
      .to(g.position, { x: home.x, y: home.y, z: home.z, duration: 0.6, ease: 'power2.in' }, at + hold)
      .to(g.rotation, { x: 0, y: 0, duration: 0.6, ease: 'power2.in' }, at + hold)
      .to(g.scale, { x: 1, y: 1, z: 1, duration: 0.6, ease: 'power2.in' }, at + hold)
      .set(face.material, { opacity: 0 }, at + hold + 0.6);
  };

  const intro = gsap.timeline({ paused: true, defaults: { ease: 'power2.inOut' } });
  // Start: dark, the phone low and turned away.
  intro.set(phone.position, { x: 0, y: -0.03, z: 0 }, 0)
    .set(spin.rotation, { y: Math.PI + 0.75, x: 0.02 }, 0)
    .set(camera.position, { x: 0, y: 0.035, z: 0.76 }, 0)
    .set(look, { x: 0, y: 0, z: 0 }, 0)
    .set(screenMat, { map: S.welcome }, 0)
    .set(screenMat.color, { r: 0, g: 0, b: 0 }, 0)
    .set(nextMat, { opacity: 0 }, 0)
    .set([ui.hero, ui.cue], { autoAlpha: 0 }, 0)
    // The film has the screen to itself; the site's header comes back with the words.
    .set(document.querySelector('.site-h'), { autoAlpha: 0 }, 0)
    // The title card, in the dark, before anything else.
    .fromTo(ui.title, titleUp ? { autoAlpha: 1, scale: 1, filter: 'blur(0px)' } : { autoAlpha: 0, scale: 1.06, filter: 'blur(10px)' },
      { autoAlpha: 1, scale: 1, filter: 'blur(0px)', duration: titleUp ? 0.01 : 1.3, ease: 'power3.out' }, 0.15)
    .to(ui.title, { autoAlpha: 0, y: -24, filter: 'blur(6px)', duration: 0.8, ease: 'power2.in' }, 1.9)
    // The light comes on, with a stutter, as a real lamp does.
    .to(spot, { intensity: 1.1, duration: 0.12, ease: 'none' }, 0.25)
    .to(spot, { intensity: 0.35, duration: 0.08, ease: 'none' }, 0.37)
    .to(spot, { intensity: 2.6, duration: 0.9, ease: 'power2.out' }, 0.5)
    .to(beam.material.uniforms.uOpacity, { value: 1, duration: 1.2, ease: 'power2.out' }, 0.45)
    .to(pool.material, { opacity: 1, duration: 1.2 }, 0.5)
    .to(dust.material.uniforms.uOpacity, { value: 1, duration: 2 }, 0.8)
    .to(rim, { intensity: 2.2, duration: 1.6 }, 1.0)
    .to(rim2, { intensity: 1.2, duration: 1.6 }, 1.2)
    .to(key, { intensity: 0.5, duration: 1.4 }, 3.4)
    // The phone rises and turns, back first; a light slides across the camera bar.
    .to(phone.position, { y: 0, duration: 2.6, ease: 'power3.out' }, 0.7)
    .to(spin.rotation, { y: Math.PI - 0.4, duration: 2.6, ease: 'power2.inOut' }, 0.7)
    .to(camera.position, { z: 0.64, y: 0.02, duration: 3.4, ease: 'power2.inOut' }, 0.5)
    .fromTo(sweep.position, { x: -0.32 }, { x: 0.32, duration: 1.8, ease: 'power1.inOut' }, 1.5)
    .fromTo(sweep, { intensity: 0 }, { intensity: 0.9, duration: 0.4, yoyo: true, repeat: 1, repeatDelay: 1.0 }, 1.5)
    // It faces you, and the screen wakes on the Porthole ring.
    .to(spin.rotation, { y: 0.14, duration: 1.3, ease: 'power3.inOut' }, 3.3)
    .to(screenMat.color, { r: 1, g: 1, b: 1, duration: 0.7, ease: 'power2.out' }, 4.25)
    .fromTo(halo.scale, { x: 1, y: 1 }, { x: 2.6, y: 2.6, duration: 1.4, ease: 'power2.out' }, 4.4)
    .fromTo(halo.material, { opacity: 0.9 }, { opacity: 0, duration: 1.3, ease: 'power2.out' }, 4.4)
    .to(camera.position, { z: 0.56, duration: 2.4 }, 5.0);
  swapTo(intro, S.sessions, 5.1);
  // Claude asks; the question lifts out, and a finger taps an answer.
  swapTo(intro, S.question, 5.9);
  cap(0, 5.1, intro, 0.7);
  lift(qCard, { x: -0.062, y: 0.004, z: 0.035, s: 1.22, ry: 0.32 }, 6.25, intro, 1.55);
  // Where option 2 sits on the lifted card: the tap lands there.
  const opt2 = small()
    ? { x: -0.062 * 0.28, y: 0.004 - 0.017, z: qCard.userData.home.z + 0.047 }
    : { x: -0.062, y: 0.004 - 0.018, z: qCard.userData.home.z + 0.036 };
  intro.set(tap.position, opt2, 7.15)
    .fromTo(tap.scale, { x: 0.4, y: 0.4 }, { x: 3, y: 3, duration: 0.55, ease: 'power2.out' }, 7.15)
    .fromTo(tap.material, { opacity: 0.9 }, { opacity: 0, duration: 0.55 }, 7.15);
  cap(1, 6.3, intro, 1.4);
  // A command needs approval; it lifts out the other side, and is allowed.
  swapTo(intro, S.approval, 8.2);
  lift(aCard, { x: 0.064, y: -0.006, z: 0.03, s: 1.25, ry: -0.32 }, 8.45, intro, 1.35);
  cap(2, 8.5, intro, 1.2);
  // The terminal at the desk: the same session, floating beside the phone.
  swapTo(intro, S.terminal, 10.1);
  lift(tCard, { x: -0.078, y: 0.012, z: -0.03, s: 0.95, ry: 0.42 }, 10.2, intro, 1.35);
  cap(3, 10.25, intro, 1.2);
  // Settle: the phone steps aside for the words.
  const R = rest();
  swapTo(intro, S.sessions, 11.7, 0.4);
  intro.to(phone.position, { x: R.x, y: R.y, duration: 1.6, ease: 'power3.inOut' }, 11.7)
    .to(spin.rotation, { y: R.rotY, x: R.rotX, duration: 1.6, ease: 'power3.inOut' }, 11.7)
    .to(camera.position, { x: R.cam.x, y: R.cam.y, z: R.cam.z, duration: 1.6, ease: 'power3.inOut' }, 11.7)
    .to(look, { x: R.look.x, y: R.look.y, z: R.look.z, duration: 1.6, ease: 'power3.inOut' }, 11.7)
    .fromTo(ui.hero, { autoAlpha: 0, y: 18 }, { autoAlpha: 1, y: 0, duration: 1.0, ease: 'power3.out' }, 12.4)
    .to(document.querySelector('.site-h'), { autoAlpha: 1, duration: 0.8 }, 12.6)
    .fromTo(ui.cue, { autoAlpha: 0 }, { autoAlpha: 1, duration: 0.8 }, 13.2)
    .set(ui.skip, { autoAlpha: 0 }, 13.3)
    .set(ui.replay, { autoAlpha: 1 }, 13.3);
  const END = intro.duration();

  // ---------------------------------------------------------------- scroll ---
  // Down the page, the camera goes into the screen, and the screen becomes the page.
  // Every tween states where it starts, so scrubbing back always returns to the rest pose.
  const scroll = gsap.timeline({ paused: true, defaults: { ease: 'none', immediateRender: false } });
  scroll.fromTo(ui.hero, { autoAlpha: 1, y: 0 }, { autoAlpha: 0, y: -40, duration: 0.25 }, 0)
    .fromTo(ui.cue, { autoAlpha: 1 }, { autoAlpha: 0, duration: 0.1 }, 0)
    .fromTo(spin.rotation, { y: R.rotY, x: R.rotX }, { y: 0, x: 0, duration: 0.45, ease: 'power2.inOut' }, 0.05)
    .fromTo(phone.position, { x: R.x, y: R.y }, { x: 0, y: 0, duration: 0.45, ease: 'power2.inOut' }, 0.05)
    .fromTo(look, { x: R.look.x, y: R.look.y, z: R.look.z }, { x: 0, y: 0, z: 0, duration: 0.45, ease: 'power2.inOut' }, 0.05)
    .fromTo(camera.position, { x: R.cam.x, y: R.cam.y, z: R.cam.z }, { x: 0, y: 0, z: 0.3, duration: 0.45, ease: 'power2.inOut' }, 0.05)
    .fromTo(camera.position, { z: 0.3 }, { z: 0.085, duration: 0.4, ease: 'power2.in' }, 0.5)
    .fromTo(spot, { intensity: 2.6 }, { intensity: 0.8, duration: 0.4 }, 0.5)
    // Close to the screen, a glow on its text would only blur it.
    .fromTo(bloom, { strength: 0.55 }, { strength: 0, duration: 0.3 }, 0.45)
    .fromTo(root.querySelector('.cine-fade'), { autoAlpha: 0 }, { autoAlpha: 1, duration: 0.2 }, 0.8);

  const played = () => { try { localStorage.setItem('porthole-intro', 'seen'); } catch (e) { /* fine */ } };
  let introDone = false;
  const finish = () => {
    intro.progress(1, false).pause();
    introDone = true;
    played();
  };
  intro.eventCallback('onComplete', () => { introDone = true; played(); });
  ScrollTrigger.create({
    trigger: root, start: 'top top', end: 'bottom bottom', scrub: 0.6,
    onUpdate: st => {
      // Scrolling is the viewer moving on: whatever of the opening is left is skipped.
      if (!introDone && st.progress > 0.002) finish();
      if (introDone) scroll.progress(st.progress);
    },
  });
  // The header floats on the stage, and is itself again once the stage has gone by.
  const header = document.querySelector('.site-h');
  header?.classList.add('over-stage', 'on-stage');
  ScrollTrigger.create({
    trigger: root, start: 'top top', end: 'bottom top+=64',
    onToggle: st => header?.classList.toggle('on-stage', st.isActive),
  });
  ui.skip?.addEventListener('click', finish);
  ui.replay?.addEventListener('click', () => {
    scrollTo({ top: root.offsetTop, behavior: 'instant' });
    scroll.progress(0);
    introDone = false;
    gsap.set(ui.replay, { autoAlpha: 0 });
    gsap.set(ui.skip, { autoAlpha: 1 });
    intro.restart();
  });

  // ------------------------------------------------------------------ loop ---
  let visible = true;
  new IntersectionObserver(es => { visible = es[0].isIntersecting; }).observe(root);
  const t0 = performance.now();
  // A governor: if frames run long, draw fewer pixels before anything else suffers.
  let slow = 0, last = performance.now();
  function govern(now) {
    const dt = now - last; last = now;
    if (dt > 24 && dt < 200) slow++; else if (slow > 0) slow--;
    if (slow > 45 && dprCap > 1) {
      dprCap = Math.max(1, dprCap - 0.25); slow = 0;
      renderer.setPixelRatio(Math.min(devicePixelRatio, dprCap)); resize();
    }
  }
  function frame() {
    requestAnimationFrame(frame);
    if (!visible || document.hidden) { last = performance.now(); return; }
    govern(performance.now());
    const t = (performance.now() - t0) / 1000;
    beam.material.uniforms.uTime.value = t;
    dust.material.uniforms.uTime.value = t;
    // Breathing: a phone held in the light is never quite still.
    spin.position.y = Math.sin(t * 0.8) * 0.0012;
    camera.lookAt(look);
    if (useBloom()) composer.render(); else renderer.render(scene, camera);
  }

  root.classList.add('cine-ready');
  // The head script held a first visit in the dark for the opening; if it gave up waiting,
  // the page is already being read, and the opening would only get in the way.
  const html = document.documentElement;
  const waited = html.classList.contains('cine-pending');
  html.classList.remove('cine-pending');
  if (reduce || seen || !waited) {
    finish();
    gsap.set(ui.skip, { autoAlpha: 0 });
    gsap.set(ui.replay, { autoAlpha: 1 });
  } else {
    // The still is the settled frame; under an opening that starts in the dark it would
    // show through while the canvas fades in.
    gsap.set(root.querySelector('.cine-still'), { autoAlpha: 0 });
    canvas.style.transition = 'none';
    gsap.set(ui.skip, { autoAlpha: 1 });
    intro.play(0);
  }
  if (Q.has('t')) { intro.pause(); intro.seek(Math.min(+Q.get('t'), END), false); }
  frame();
  // For stills and checks: the scene's clock and position, from outside.
  window.__porthole = { intro, scroll, END, renderer, phone, spin, camera, look };
}
