// The camera's film stock: the renderer, a second view drawn to a texture (for the porthole,
// the thought and the wipes), bloom, tone mapping, and a grade laid over the finished frame
// (colour, vignette, grain, fades). Colour in the passes before the output is linear.
import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { ShaderPass } from 'three/addons/postprocessing/ShaderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';

// The second view, laid into the first inside a circle (a wipe, an iris, a thought): linear.
const Mix = {
  uniforms: {
    tDiffuse: { value: null }, tB: { value: null },
    centre: { value: new THREE.Vector2(0.5, 0.5) }, radius: { value: 0 }, feather: { value: 0.002 },
    aspect: { value: 16 / 9 }, amount: { value: 0 },
    ring: { value: 0 }, ringColor: { value: new THREE.Color('#E7B84A') },
    tintB: { value: new THREE.Vector3(1, 1, 1) }, satB: { value: 1 },
    bubbles: { value: 0 }, bubbleA: { value: new THREE.Vector3(0, 0, 0) }, bubbleB: { value: new THREE.Vector3(0, 0, 0) },
    bubbleColor: { value: new THREE.Color('#FFF8EC') },
    remap: { value: 0 }, zoom: { value: 1 },
  },
  vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
  fragmentShader: `
    uniform sampler2D tDiffuse, tB; uniform vec2 centre; uniform float radius, feather, aspect, amount, ring, satB, bubbles, remap, zoom;
    uniform vec3 ringColor, tintB, bubbleA, bubbleB, bubbleColor; varying vec2 vUv;
    float circ(vec2 p, vec3 c){ vec2 d = p - c.xy; d.x *= aspect; return smoothstep(c.z + 0.0015, c.z - 0.0015, length(d)); }
    void main(){
      vec4 a = texture2D(tDiffuse, vUv);
      if (amount <= 0.0) { gl_FragColor = a; return; }
      // the second view either lines up with the first (a wipe) or is shown whole inside the circle
      vec2 uvB = mix(vUv, 0.5 + (vUv - centre) * zoom, remap);
      vec3 b = texture2D(tB, uvB).rgb;
      float l = dot(b, vec3(0.2126, 0.7152, 0.0722));
      b = mix(vec3(l), b, satB) * tintB;
      vec2 d = vUv - centre; d.x *= aspect;
      float r = length(d);
      float m = smoothstep(radius + feather, radius - feather, r) * amount;
      vec3 c = mix(a.rgb, b, m);
      // a rim round the circle
      if (ring > 0.0) {
        float w = ring;
        float band = smoothstep(radius + w + 0.0015, radius + w - 0.0015, r) * smoothstep(radius - 0.0015, radius + 0.0015, r);
        c = mix(c, ringColor, band * amount);
      }
      // a thought's two small bubbles leading back to the one thinking
      if (bubbles > 0.0) {
        float k = max(circ(vUv, bubbleA), circ(vUv, bubbleB)) * bubbles;
        c = mix(c, bubbleColor, k);
      }
      gl_FragColor = vec4(c, a.a);
    }`,
};

// The grade, on the finished frame (display colours): lift, warmth, saturation, vignette,
// grain, and a fade to a colour.
const Grade = {
  uniforms: {
    tDiffuse: { value: null }, time: { value: 0 }, aspect: { value: 16 / 9 },
    warm: { value: 0 }, sat: { value: 1 }, lift: { value: new THREE.Vector3(0, 0, 0) }, gain: { value: new THREE.Vector3(1, 1, 1) },
    vignette: { value: 0.28 }, grain: { value: 0.035 },
    fade: { value: 0 }, fadeColor: { value: new THREE.Color('#000000') },
  },
  vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
  fragmentShader: `
    uniform sampler2D tDiffuse; uniform float time, aspect, warm, sat, vignette, grain, fade;
    uniform vec3 lift, gain, fadeColor; varying vec2 vUv;
    float hash(vec2 p){ p = fract(p * vec2(443.897, 441.423)); p += dot(p, p.yx + 19.19); return fract((p.x + p.y) * p.x); }
    void main(){
      vec3 c = texture2D(tDiffuse, vUv).rgb;
      c = c * gain + lift * (1.0 - c);
      c += warm * vec3(0.035, 0.012, -0.03);
      float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
      c = mix(vec3(l), c, sat);
      vec2 d = (vUv - 0.5) * vec2(aspect, 1.0);
      c *= 1.0 - vignette * smoothstep(0.35, 1.15, length(d));
      float n = hash(vUv * vec2(1920.0, 1080.0) + time * 61.7) - 0.5;
      c += n * grain * (1.0 - 0.6 * l);
      c = mix(c, fadeColor, fade);
      gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
    }`,
};

export function makeStage(W, H, scale = 1) {
  const iw = Math.round(W * scale), ih = Math.round(H * scale);
  const renderer = new THREE.WebGLRenderer({ antialias: false, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(1);
  renderer.setSize(iw, ih, false);
  Object.assign(renderer.domElement.style, { width: W + 'px', height: H + 'px' });
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.NeutralToneMapping;
  renderer.toneMappingExposure = 1.0;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFShadowMap;
  document.body.appendChild(renderer.domElement);

  const rtOpts = { type: THREE.HalfFloatType, samples: 4, colorSpace: THREE.LinearSRGBColorSpace };
  const composer = new EffectComposer(renderer, new THREE.WebGLRenderTarget(iw, ih, rtOpts));
  const main = new RenderPass(new THREE.Scene(), new THREE.PerspectiveCamera());
  const mix = new ShaderPass(Mix);
  const bloom = new UnrealBloomPass(new THREE.Vector2(iw / 2, ih / 2), 0.5, 0.45, 2.2);
  const output = new OutputPass();
  const grade = new ShaderPass(Grade);
  mix.uniforms.aspect.value = grade.uniforms.aspect.value = W / H;
  composer.addPass(main); composer.addPass(mix); composer.addPass(bloom); composer.addPass(output); composer.addPass(grade);

  // the second view: its own target, linear, sampled by the mix pass and by the porthole's glass
  const second = new THREE.WebGLRenderTarget(iw, ih, rtOpts);
  mix.uniforms.tB.value = second.texture;

  function drawSecond(scene, camera) {
    renderer.setRenderTarget(second);
    renderer.clear();
    renderer.render(scene, camera);
    renderer.setRenderTarget(null);
  }
  function draw(scene, camera) {
    main.scene = scene; main.camera = camera;
    composer.render();
  }
  return { renderer, composer, draw, drawSecond, second, mix: mix.uniforms, grade: grade.uniforms, bloom, W: iw, H: ih };
}
