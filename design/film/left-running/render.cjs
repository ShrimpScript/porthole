// Draws the film frame by frame in headless Chromium and writes PNGs.
//
//   node render.cjs PAGE.html OUT_DIR [--at 1.5,12,40] [--from N --to M] [--jobs J] [--size 1920x1080]
//
// The page is served from the repository root (it loads the site's fonts and this folder's
// node_modules), exposes window.ready (a promise of its duration in seconds) and window.seek(t),
// which draws the frame at time t. Nothing depends on a clock, so frames can be drawn in any
// order and by several browsers at once (--jobs), and every run draws the same pictures.
// --at draws stills at the given seconds (for review); otherwise every frame from --from to --to.
const http = require('http');
const fs = require('fs');
const path = require('path');
const HERE = __dirname;
const ROOT = path.resolve(HERE, '../../..');
const { chromium } = require(process.env.PW || path.join(HERE, 'node_modules/playwright-core'));

const args = process.argv.slice(2);
const page = path.resolve(args[0]);
const outDir = path.resolve(args[1]);
const opt = (name, dflt) => { const i = args.indexOf('--' + name); return i >= 0 ? args[i + 1] : dflt; };
const [W, H] = opt('size', '1920x1080').split('x').map(Number);
const jobs = +opt('jobs', 1);
const at = opt('at', null);
const query = opt('query', '');
const dpr = +opt('dpr', 1);
const CHROME = process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.webp': 'image/webp', '.glb': 'model/gltf-binary', '.woff2': 'font/woff2', '.css': 'text/css' };
function serve() {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      const p = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
      if (!p.startsWith(ROOT)) { res.writeHead(403); res.end(); return; }
      fs.readFile(p, (e, d) => {
        if (e) { res.writeHead(404); res.end(); return; }
        res.writeHead(200, { 'content-type': TYPES[path.extname(p)] || 'application/octet-stream' });
        res.end(d);
      });
    }).listen(0, '127.0.0.1', () => resolve(srv));
  });
}

async function worker(port, frames, fps, id) {
  const b = await chromium.launch({ executablePath: CHROME, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--disable-gpu-vsync'] });
  const ctx = await b.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: dpr });
  const p = await ctx.newPage();
  p.on('pageerror', (e) => console.error(`[${id}] page error:`, e.message));
  p.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') console.error(`[${id}]`, m.text()); });
  const url = `http://127.0.0.1:${port}/${path.relative(ROOT, page)}${query ? '?' + query : ''}`;
  await p.goto(url, { waitUntil: 'load' });
  await p.evaluate(() => document.fonts.ready);
  await p.evaluate(() => window.ready);
  const t0 = Date.now();
  for (let i = 0; i < frames.length; i++) {
    const f = frames[i];
    await p.evaluate((t) => window.seek(t), f.t);
    await p.screenshot({ path: f.file, type: 'png' });
    if (i % 30 === 29) console.log(`[${id}] ${i + 1}/${frames.length} frames, ${((Date.now() - t0) / (i + 1) / 1000).toFixed(2)} s each`);
  }
  await b.close();
}

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const srv = await serve();
  const port = srv.address().port;
  // ask the page how long it is and at what rate
  const probe = await chromium.launch({ executablePath: CHROME, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const pp = await (await probe.newContext({ viewport: { width: 64, height: 64 } })).newPage();
  await pp.goto(`http://127.0.0.1:${port}/${path.relative(ROOT, page)}?probe=1${query ? '&' + query : ''}`, { waitUntil: 'load' });
  const info = await pp.evaluate(async () => ({ duration: await window.ready, fps: window.FPS || 30 }));
  await probe.close();
  let frames;
  if (at) frames = at.split(',').map((s) => ({ t: +s, file: path.join(outDir, `at-${(+s).toFixed(2).padStart(6, '0')}.png`) }));
  else {
    const n = Math.round(info.duration * info.fps);
    const from = +opt('from', 0), to = Math.min(+opt('to', n), n);
    frames = [];
    const step = +opt('step', 1);
    for (let i = from; i < to; i += step) {
      const file = path.join(outDir, String(i).padStart(5, '0') + '.png');
      if (args.includes('--resume') && fs.existsSync(file)) continue;
      frames.push({ t: i / info.fps, file });
    }
  }
  // interleave frames across workers, so each sees the whole film's range of cost
  const shares = Array.from({ length: jobs }, (_, k) => frames.filter((_, i) => i % jobs === k));
  await Promise.all(shares.map((s, k) => s.length && worker(port, s, info.fps, k)));
  srv.close();
  console.log(`drew ${frames.length} frames into ${outDir}`);
})().catch((e) => { console.error(e); process.exit(1); });
