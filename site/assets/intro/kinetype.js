// Type that lives in the scene with the phone: a word drawn once, in the site's own face,
// then cut into one plane per letter, so each letter can move on its own while the phone
// passes in front of it or behind it by real depth - not an overlay on top of the film.
import * as THREE from 'three';

export const SANS = '"Schibsted Grotesk", system-ui, sans-serif';
export const MONO = '"Iosevka Term", ui-monospace, monospace';

/**
 * A word as a group of letter planes, centred on its origin.
 *   height:   the cap height, in scene units (metres)
 *   weight:   the font weight
 *   fill:     a CSS colour, or [top, bottom] for a vertical gradient
 *   tracking: extra space between letters, in ems
 *   face:     SANS or MONO
 *   lit:      true for letters the scene's lights fall on; false for letters that are
 *             simply their own colour
 * The group's userData.letters holds the planes in reading order, each with
 * userData.home (its resting position), for animation.
 */
export async function word(text, { height = 0.05, weight = 650, fill = '#F2F7F6', tracking = -0.04, face = SANS, lit = false } = {}) {
  // A face that is not ready would draw in the fallback, and stay that way on the texture.
  await document.fonts.load(`${weight} 200px ${face}`, text);
  const px = 220;                         // drawing size: sharp at the sizes the film uses
  const c = document.createElement('canvas');
  const g = c.getContext('2d');
  const font = `${weight} ${px}px ${face}`;
  g.font = font;
  // Where each letter sits in the word: the width of everything before it, so kerning survives.
  const at = [0];
  for (let i = 1; i <= text.length; i++) at.push(g.measureText(text.slice(0, i)).width + tracking * px * i);
  // On the texture each letter gets a cell of its own, with room around it, so a letter
  // that moves away from its neighbours never carries a sliver of them along.
  const gap = Math.round(px * 0.5), margin = Math.round(px * 0.22);
  const cell = i => gap + at[i] + i * gap;
  const w = Math.ceil(cell(text.length)) + gap;
  const h = Math.ceil(px * 1.4);
  c.width = w; c.height = h;
  g.font = font;
  g.textBaseline = 'alphabetic';
  const base = Math.round(px * 1.08);
  if (Array.isArray(fill)) {
    const grad = g.createLinearGradient(0, base - px * 0.74, 0, base);
    grad.addColorStop(0, fill[0]); grad.addColorStop(1, fill[1]);
    g.fillStyle = grad;
  } else g.fillStyle = fill;
  for (let i = 0; i < text.length; i++) g.fillText(text[i], cell(i), base);

  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 8;
  const unit = height / (px * 0.72);      // scene units per canvas pixel (cap height ~0.72 em)
  const total = at[text.length] * unit;
  const group = new THREE.Group();
  const letters = [];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === ' ') continue;
    const x0 = cell(i) - margin, x1 = cell(i) + (at[i + 1] - at[i]) + margin;
    const geo = new THREE.PlaneGeometry((x1 - x0) * unit, h * unit);
    const uv = geo.attributes.uv;
    for (let k = 0; k < uv.count; k++) uv.setX(k, (uv.getX(k) ? x1 : x0) / w);
    const mat = lit
      ? new THREE.MeshStandardMaterial({ map: tex, transparent: true, depthWrite: false, roughness: 0.92, metalness: 0 })
      : new THREE.MeshBasicMaterial({ map: tex, transparent: true, toneMapped: false, depthWrite: false });
    const m = new THREE.Mesh(geo, mat);
    // Centred on the word's middle, with the cap line centred on the group's origin.
    m.position.set((at[i] + at[i + 1]) / 2 * unit - total / 2, (h / 2 - base) * unit + height / 2, 0);
    m.userData.home = m.position.clone();
    m.userData.index = letters.length;
    group.add(m);
    letters.push(m);
  }
  group.userData = { letters, width: total, height };
  return group;
}

/** Set every letter's opacity (and the group's visibility with it). */
export function fade(group, opacity) {
  for (const l of group.userData.letters) l.material.opacity = opacity;
  group.visible = opacity > 0.001;
}
