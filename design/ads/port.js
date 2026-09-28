// Draws a porthole's rim round an element: <div class="port" data-r="232" data-rim="26" data-bolts="10">
// holding a .glass. The rim is the house colour; its outside edge takes the silhouette weight
// (3), the inside edge the form weight (2), and the bolts the detail weight (1).
for (const port of document.querySelectorAll('.port')) {
  const r = +port.dataset.r, rim = +port.dataset.rim, n = +(port.dataset.bolts || 10);
  const size = 2 * r, NS = 'http://www.w3.org/2000/svg';
  Object.assign(port.style, { width: size + 'px', height: size + 'px' });
  const glass = port.querySelector('.glass');
  Object.assign(glass.style, { inset: rim + 'px' });
  const layer = (z) => {
    const s = document.createElementNS(NS, 'svg');
    s.setAttribute('width', size); s.setAttribute('height', size); s.setAttribute('viewBox', `0 0 ${size} ${size}`);
    Object.assign(s.style, { position: 'absolute', inset: 0, zIndex: z });
    port.appendChild(s);
    return s;
  };
  const el = (svg, name, attrs) => { const e = document.createElementNS(NS, name); for (const k in attrs) e.setAttribute(k, attrs[k]); svg.appendChild(e); };
  const under = layer(0), over = layer(2);
  glass.style.zIndex = 1;
  el(under, 'circle', { cx: r, cy: r, r: r - 1.5, fill: '#22463F', stroke: '#E8F0EE', 'stroke-width': 3 });
  el(over, 'circle', { cx: r, cy: r, r: r - rim, fill: 'none', stroke: '#E8F0EE', 'stroke-width': 2 });
  for (let k = 0; k < n; k++) {
    const a = (k + 0.5) / n * Math.PI * 2, br = r - rim / 2;
    el(under, 'circle', { cx: r + br * Math.cos(a), cy: r + br * Math.sin(a), r: 3.5, fill: '#17352F', stroke: '#E8F0EE', 'stroke-width': 1 });
  }
}
