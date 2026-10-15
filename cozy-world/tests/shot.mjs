// Делает скриншот страницы в headless Chromium (с программным WebGL) и печатает ошибки консоли.
// Пример: node tests/shot.mjs "http://127.0.0.1:5173/?x=0&z=12&yaw=0" tests/out/a.png 1280 720 6

import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';

const [url, out = 'tests/out/shot.png', w = '1280', h = '720', frames = '6'] = process.argv.slice(2);
if (!url) {
  console.error('usage: node tests/shot.mjs <url> [out.png] [width] [height] [frames]');
  process.exit(2);
}

fs.mkdirSync(path.dirname(out), { recursive: true });
const browser = await chromium.launch({
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const page = await browser.newPage({ viewport: { width: Number(w), height: Number(h) } });
const problems = [];
page.on('console', (m) => { if (['error', 'warning'].includes(m.type())) problems.push(`[${m.type()}] ${m.text()}`); });
page.on('pageerror', (e) => problems.push(`[pageerror] ${e.message}`));
page.on('requestfailed', (r) => problems.push(`[requestfailed] ${r.url()}`));

const t0 = Date.now();
await page.goto(url, { waitUntil: 'load' });
try {
  await page.waitForFunction((n) => window.__cozy && window.__cozy.frames >= n, Number(frames), { timeout: 90000 });
} catch (e) {
  console.log('TIMEOUT waiting for frames');
  console.log('--- console ---\n' + [...new Set(problems)].slice(0, 20).join('\n'));
  await browser.close();
  process.exit(1);
}
const info = await page.evaluate(() => {
  const r = window.__cozy.renderer.info;
  return { calls: r.render.calls, triangles: r.render.triangles, geometries: r.memory.geometries, textures: r.memory.textures };
});
await page.screenshot({ path: out, timeout: 180000 });
console.log(`saved ${out} in ${((Date.now() - t0) / 1000).toFixed(1)}s`, JSON.stringify(info));
if (problems.length) console.log('--- console ---\n' + [...new Set(problems)].slice(0, 20).join('\n'));
await browser.close();
