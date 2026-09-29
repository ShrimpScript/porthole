// The drawn look: the same film, as if drawn by hand on paper. Toon paint in a few flat tones
// instead of lit plastic; ink lines traced from depth, normals and colour, wobbling afresh on
// every drawing; pencil grain and hatching in the shadows; the fill printed slightly off the
// line; and a sheet of paper under it all. The film is drawn on twos: 15 drawings a second.
import * as THREE from 'three';

export const DRAWINGS_PER_SECOND = 15;
// layer 1 holds what has no outline: soft shadows under things, glows
export const FLAT = 1;

// ---- paint: every lit material becomes a toon material with the same colours ------------------
let ramp;
function toonRamp() {
  if (ramp) return ramp;
  // four tones: shadow, core, light, highlight
  const data = new Uint8Array([122, 180, 228, 255]);
  ramp = new THREE.DataTexture(data, data.length, 1, THREE.RedFormat);
  ramp.minFilter = ramp.magFilter = THREE.NearestFilter;
  ramp.generateMipmaps = false;
  ramp.needsUpdate = true;
  return ramp;
}

const converted = new Map();
function toToon(m) {
  if (!m || !(m.isMeshStandardMaterial || m.isMeshPhysicalMaterial)) return m;
  if (converted.has(m)) return converted.get(m);
  const t = new THREE.MeshToonMaterial({
    color: m.color, map: m.map, gradientMap: toonRamp(),
    emissive: m.emissive, emissiveMap: m.emissiveMap, emissiveIntensity: m.emissiveIntensity,
    transparent: m.transparent, opacity: m.opacity, side: m.side, vertexColors: m.vertexColors,
    alphaTest: m.alphaTest, depthWrite: m.depthWrite, fog: m.fog,
  });
  t.name = m.name;
  converted.set(m, t);
  return t;
}

export function toonify(root) {
  root.traverse((o) => {
    if (!o.isMesh) return;
    if (Array.isArray(o.material)) o.material = o.material.map(toToon);
    else o.material = toToon(o.material);
    const m = o.material;
    // soft shadows, glows and anything see-through carry no ink line
    if (m.transparent || m.blending === THREE.AdditiveBlending || o.userData.noInk) o.layers.set(FLAT);
  });
}

// ---- the tracing: view normal and distance of every pixel, for the ink ------------------------
export function makeTracing(renderer, w, h) {
  const target = new THREE.WebGLRenderTarget(w, h, { type: THREE.HalfFloatType, samples: 0, colorSpace: THREE.NoColorSpace });
  const mat = new THREE.ShaderMaterial({
    side: THREE.DoubleSide,
    vertexShader: `
      varying vec3 vN; varying float vZ;
      void main() {
        vec4 p = vec4(position, 1.0); vec3 n = normal;
        #ifdef USE_INSTANCING
          p = instanceMatrix * p; n = mat3(instanceMatrix) * n;
        #endif
        vec4 mv = modelViewMatrix * p;
        vN = normalize(normalMatrix * n); vZ = -mv.z;
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: `
      varying vec3 vN; varying float vZ;
      void main() { vec3 n = normalize(vN) * (gl_FrontFacing ? 1.0 : -1.0); gl_FragColor = vec4(n * 0.5 + 0.5, vZ); }`,
  });
  const clear = new THREE.Color(0.5, 0.5, 1.0);
  function trace(scene, camera) {
    const bg = scene.background, fog = scene.fog, auto = renderer.shadowMap.autoUpdate;
    const oldClear = renderer.getClearColor(new THREE.Color()), oldAlpha = renderer.getClearAlpha();
    scene.background = null; scene.fog = null; scene.overrideMaterial = mat;
    renderer.shadowMap.autoUpdate = false;
    const mask = camera.layers.mask;
    camera.layers.disable(FLAT);
    renderer.setRenderTarget(target);
    renderer.setClearColor(clear, 0);
    renderer.clear();
    renderer.render(scene, camera);
    renderer.setRenderTarget(null);
    camera.layers.mask = mask;
    scene.background = bg; scene.fog = fog; scene.overrideMaterial = null;
    renderer.shadowMap.autoUpdate = auto;
    renderer.setClearColor(oldClear, oldAlpha);
  }
  return { target, trace };
}

// ---- ink, pencil and paint: laid over the finished picture ------------------------------------
export const Ink = {
  uniforms: {
    tDiffuse: { value: null }, tND: { value: null },
    res: { value: new THREE.Vector2(1920, 1080) }, seed: { value: 0 }, amount: { value: 1 },
    ink: { value: new THREE.Color('#2A1F1B') }, lines: { value: 1 },
    // where the mix pass has laid a second view in (a thought, a wipe), the tracing is of the
    // wrong place: only the paint is inked there
    centre: { value: new THREE.Vector2(0.5, 0.5) }, radius: { value: 0 }, aspect: { value: 16 / 9 }, other: { value: 0 },
  },
  vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
  fragmentShader: `
    uniform sampler2D tDiffuse, tND; uniform vec2 res; uniform float seed, amount, lines, radius, aspect, other; uniform vec2 centre; uniform vec3 ink;
    varying vec2 vUv;
    float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
    float vnoise(vec2 p) {
      vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
      return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
    }
    float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
    // an edge in the tracing: a jump in distance, or a turn in the surface
    float edge(vec2 uv, float r) {
      vec2 px = r / res;
      vec4 c = texture2D(tND, uv);
      vec3 nc = c.rgb * 2.0 - 1.0;
      float e = 0.0;
      for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7853982;
        vec4 s = texture2D(tND, uv + vec2(cos(a), sin(a)) * px);
        float near = max(min(s.a, c.a), 1e-3);
        float dz = abs(s.a - c.a) / near;
        float dn = 1.0 - dot(s.rgb * 2.0 - 1.0, nc);
        e = max(e, smoothstep(0.03, 0.07, dz));
        e = max(e, smoothstep(0.28, 0.55, dn));
      }
      return e;
    }
    // an edge in the paint: where one colour meets another
    float paintEdge(vec2 uv, float r) {
      vec2 px = r / res;
      float lc = lum(texture2D(tDiffuse, uv).rgb), m = 0.0;
      m = max(m, abs(lum(texture2D(tDiffuse, uv + vec2(px.x, 0.0)).rgb) - lc));
      m = max(m, abs(lum(texture2D(tDiffuse, uv - vec2(px.x, 0.0)).rgb) - lc));
      m = max(m, abs(lum(texture2D(tDiffuse, uv + vec2(0.0, px.y)).rgb) - lc));
      m = max(m, abs(lum(texture2D(tDiffuse, uv - vec2(0.0, px.y)).rgb) - lc));
      return smoothstep(0.09, 0.22, m);
    }
    void main() {
      vec2 fc = vUv * res, k = res / 1920.0;
      // every drawing wobbles a little, and differently: the line boils
      vec2 w1 = (vec2(vnoise(fc / (95.0 * k.x) + seed * 7.13), vnoise(fc / (95.0 * k.x) + 31.7 + seed * 7.13)) - 0.5) * 3.2 * k.x;
      vec2 w2 = (vec2(vnoise(fc / (16.0 * k.x) + seed * 3.31), vnoise(fc / (16.0 * k.x) + 17.3 + seed * 3.31)) - 0.5) * 1.8 * k.x;
      vec2 uvFill = vUv + (w1 + vec2(1.1, -0.8) * k.x) / res;      // the paint, a touch off register
      vec2 uvLine = vUv + (w1 + w2) / res;
      vec3 col = texture2D(tDiffuse, uvFill).rgb;
      vec3 orig = texture2D(tDiffuse, vUv).rgb;
      float L = lum(col);
      // pencil: grain in long diagonal strokes, heavier where it is darker; new strokes each drawing
      vec2 d1 = normalize(vec2(1.0, 0.62)), d2 = vec2(-d1.y, d1.x);
      float g = vnoise(vec2(dot(fc, d1) / (1.4 * k.x), dot(fc, d2) / (10.0 * k.x)) + seed * 13.1);
      float g2 = vnoise(fc / (3.0 * k.x) - seed * 5.7);
      col *= 1.0 - 0.2 * (g - 0.5) * (1.25 - L) - 0.06 * (g2 - 0.5);
      // hatching: one direction in the shadows, crossed in the deepest
      float h1 = (fc.x + fc.y) / (8.5 * k.x) + (vnoise(fc / (34.0 * k.x) + seed) - 0.5) * 1.3;
      float h2 = (fc.x - fc.y) / (9.5 * k.x) + (vnoise(fc / (30.0 * k.x) + seed + 5.0) - 0.5) * 1.3;
      float l1 = 1.0 - smoothstep(0.08, 0.26, abs(fract(h1) - 0.5));
      float l2 = 1.0 - smoothstep(0.08, 0.26, abs(fract(h2) - 0.5));
      col *= 1.0 - 0.3 * l1 * smoothstep(0.34, 0.1, L) - 0.26 * l2 * smoothstep(0.18, 0.03, L);
      // the ink: traced edges drawn twice, a little apart, so the line has life
      float pressure = 0.7 + 0.7 * vnoise(fc / (170.0 * k.x) + seed * 1.37);
      float e = edge(uvLine, 1.35 * pressure * k.x);
      float e2 = edge(vUv + (w1 * 0.6 - w2 + vec2(0.7, 0.5) * k.x) / res, 1.0 * k.x) * 0.5;
      vec2 dc = vUv - centre; dc.x *= aspect;
      float inside = other * smoothstep(radius + 0.004, radius - 0.004, length(dc));
      e *= 1.0 - inside; e2 *= 1.0 - inside;
      float pe = paintEdge(uvLine, 1.15 * k.x) * 0.55;
      float inkAmt = clamp(max(max(e, e2), pe), 0.0, 1.0) * lines;
      // wet paint pools at its edges
      float pool = edge(vUv, 4.5 * k.x) * (1.0 - inside);
      col *= 1.0 - 0.07 * pool;
      col = mix(col, ink, inkAmt * 0.86);
      gl_FragColor = vec4(mix(orig, col, amount), 1.0);
    }`,
};

// ---- the paper's tooth in the darks: pale fibres, laid over with a screen blend ---------------
export function toothCanvas(w, h, seed = 23) {
  const c = document.createElement('canvas'); c.width = w; c.height = h;
  const g = c.getContext('2d');
  let s = seed;
  const r = () => (s = (s * 16807) % 2147483647) / 2147483647;
  g.fillStyle = '#000'; g.fillRect(0, 0, w, h);
  g.lineCap = 'round';
  for (let i = 0; i < 3200; i++) {
    const x = r() * w, y = r() * h, len = 3 + r() * 18, a = r() * Math.PI;
    g.strokeStyle = `rgba(235,220,195,${0.03 + r() * 0.05})`; g.lineWidth = 0.4 + r() * 0.7;
    g.beginPath(); g.moveTo(x, y); g.lineTo(x + Math.cos(a) * len, y + Math.sin(a) * len); g.stroke();
  }
  const img = g.getImageData(0, 0, w, h), d = img.data;
  for (let i = 0; i < d.length; i += 4) { const n = r() * 10; d[i] += n; d[i + 1] += n * 0.95; d[i + 2] += n * 0.85; }
  g.putImageData(img, 0, 0);
  return c;
}

// ---- the paper: one sheet under every drawing ------------------------------------------------
export function paperCanvas(w, h, seed = 9) {
  const c = document.createElement('canvas'); c.width = w; c.height = h;
  const g = c.getContext('2d');
  let s = seed;
  const r = () => (s = (s * 16807) % 2147483647) / 2147483647;
  g.fillStyle = '#FCF7EE'; g.fillRect(0, 0, w, h);
  // the tooth: soft blotches of slightly darker pulp
  for (let i = 0; i < 260; i++) {
    const x = r() * w, y = r() * h, rad = 20 + r() * 140;
    const gr = g.createRadialGradient(x, y, 0, x, y, rad);
    gr.addColorStop(0, `rgba(196,170,130,${0.015 + r() * 0.025})`); gr.addColorStop(1, 'rgba(196,170,130,0)');
    g.fillStyle = gr; g.fillRect(x - rad, y - rad, 2 * rad, 2 * rad);
  }
  // fibres
  g.lineCap = 'round';
  for (let i = 0; i < 2600; i++) {
    const x = r() * w, y = r() * h, len = 4 + r() * 22, a = r() * Math.PI;
    g.strokeStyle = `rgba(150,125,95,${0.04 + r() * 0.07})`; g.lineWidth = 0.4 + r() * 0.8;
    g.beginPath(); g.moveTo(x, y);
    g.quadraticCurveTo(x + Math.cos(a) * len * 0.5 + (r() - 0.5) * 6, y + Math.sin(a) * len * 0.5 + (r() - 0.5) * 6, x + Math.cos(a) * len, y + Math.sin(a) * len);
    g.stroke();
  }
  // grain
  const img = g.getImageData(0, 0, w, h), d = img.data;
  for (let i = 0; i < d.length; i += 4) {
    const n = (r() - 0.5) * 14;
    d[i] += n; d[i + 1] += n; d[i + 2] += n * 0.9;
  }
  g.putImageData(img, 0, 0);
  // the sheet darkens a little towards its edges
  const v = g.createRadialGradient(w / 2, h / 2, h * 0.45, w / 2, h / 2, h * 1.05);
  v.addColorStop(0, 'rgba(120,95,70,0)'); v.addColorStop(1, 'rgba(120,95,70,0.13)');
  g.fillStyle = v; g.fillRect(0, 0, w, h);
  return c;
}
