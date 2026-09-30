// The porthole: the brand's round window as a thing in the world. Its glass shows the second
// view (stage.second) in screen space, so looking through it is looking into the other place,
// and pushing the camera into it is arriving there.
import * as THREE from 'three';
import { portRim } from './home.js';

export function makePortal(stage) {
  const root = new THREE.Group();
  const rim = portRim(1, root, [0, 0, 0]);
  const glassMat = new THREE.ShaderMaterial({
    uniforms: { tView: { value: stage.second.texture }, res: { value: new THREE.Vector2(stage.W, stage.H) }, open: { value: 1 } },
    vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
    fragmentShader: `
      uniform sampler2D tView; uniform vec2 res; uniform float open; varying vec2 vUv;
      void main(){
        vec3 c = texture2D(tView, gl_FragCoord.xy / res).rgb;
        // the glass: a faint cool sheen towards its edge, and a soft highlight at the top left
        float r = length(vUv - 0.5) * 2.0;
        c = mix(c, c * vec3(0.9, 1.0, 1.02), smoothstep(0.6, 1.0, r) * 0.4);
        float hl = smoothstep(0.5, 0.0, length((vUv - vec2(0.3, 0.74)) * vec2(1.0, 2.2)));
        c += hl * 0.12 * open;
        gl_FragColor = vec4(c, 1.0);
      }`,
  });
  const glass = new THREE.Mesh(new THREE.CircleGeometry(1.0, 128), glassMat);
  glass.position.z = 0.002;
  root.add(glass);
  // a soft glow round the rim while it opens (teal: it is live)
  const halo = new THREE.Mesh(new THREE.RingGeometry(1.08, 1.6, 96), new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    uniforms: { k: { value: 0 }, color: { value: new THREE.Color('#3FD4C0') } },
    vertexShader: 'varying vec2 vP; void main(){ vP = position.xy; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
    fragmentShader: 'uniform float k; uniform vec3 color; varying vec2 vP; void main(){ float r = length(vP); float a = smoothstep(1.6, 1.1, r) * k; gl_FragColor = vec4(color * a * 4.0, a); }',
  }));
  halo.position.z = -0.01;
  root.add(halo);
  return { root, rim, glass, halo };
}
