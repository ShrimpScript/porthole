// Renders an ad page to PNG at an exact size, headless.
//   node design/ads/capture.cjs PAGE.html OUT.png WIDTH HEIGHT
// SCALE=2 writes the same layout at twice the pixels, the master a channel's size is cut from.
// Pages are plain HTML that load the site's own fonts and the phone renders beside them.
const path = require('path');
const pw = process.env.PW || path.join(process.env.HOME, 'node_modules/playwright-core');
const { chromium } = require(pw);
const [page, out, w, h] = process.argv.slice(2);
(async () => {
  const b = await chromium.launch({ channel: 'chrome', headless: true, args: ['--ozone-platform=headless'] });
  const p = await (await b.newContext({ viewport: { width: +w, height: +h }, deviceScaleFactor: +(process.env.SCALE || 1) })).newPage();
  await p.goto('file://' + path.resolve(page), { waitUntil: 'networkidle' });
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(200);
  await p.screenshot({ path: out, omitBackground: false });
  console.log('wrote', out, w + 'x' + h, 'at', process.env.SCALE || 1, 'x');
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
