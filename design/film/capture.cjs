// Captures a film page frame by frame, headless, and encodes it.
//   node design/film/capture.cjs PAGE.html OUT_BASENAME [FPS] [WIDTH HEIGHT]
// The page exposes window.ready (a promise of its duration in seconds) and window.seek(t),
// which draws the frame at time t. Every frame is drawn by seek, so the result is the same
// on every run and on any machine: no clocks, no requestAnimationFrame. Writes
// OUT_BASENAME.mp4 (H.264, for feeds) and OUT_BASENAME.webm (VP9, for the site).
const path = require('path');
const fs = require('fs');
const { execFileSync } = require('child_process');
const pw = process.env.PW || path.join(process.env.HOME, 'node_modules/playwright-core');
const { chromium } = require(pw);
const [page, out, fpsArg, wArg, hArg] = process.argv.slice(2);
const fps = +(fpsArg || 30), w = +(wArg || 1080), h = +(hArg || 1350);
(async () => {
  const frames = fs.mkdtempSync(path.join(path.dirname(path.resolve(out)), '.frames-'));
  const b = await chromium.launch({ channel: 'chrome', headless: true, args: ['--ozone-platform=headless'] });
  const p = await (await b.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1 })).newPage();
  await p.goto('file://' + path.resolve(page), { waitUntil: 'networkidle' });
  await p.evaluate(() => document.fonts.ready);
  const duration = await p.evaluate(() => window.ready);
  const n = Math.round(duration * fps);
  for (let i = 0; i < n; i++) {
    await p.evaluate((t) => window.seek(t), i / fps);
    await p.screenshot({ path: path.join(frames, String(i).padStart(5, '0') + '.png') });
  }
  await b.close();
  const input = ['-y', '-loglevel', 'error', '-framerate', String(fps), '-i', path.join(frames, '%05d.png')];
  execFileSync('ffmpeg', [...input, '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out + '.mp4']);
  execFileSync('ffmpeg', [...input, '-c:v', 'libvpx-vp9', '-b:v', '0', '-crf', '32', '-row-mt', '1', out + '.webm']);
  fs.rmSync(frames, { recursive: true, force: true });
  console.log('wrote', out + '.mp4', out + '.webm', n, 'frames at', fps, 'fps');
})().catch((e) => { console.error(e); process.exit(1); });
