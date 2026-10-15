// "Обход": один раз загружает страницу и снимает несколько ракурсов отладочной камерой.
// Пример: node tests/tour.mjs hub-fir,canyon  (имена из VIEWS; без аргументов - все)
// Результаты: tests/out/tour-<имя>.png

import { chromium } from 'playwright-core';
import fs from 'node:fs';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173/';
const W = Number(process.env.W || 1280), H = Number(process.env.H || 720);

// yaw в градусах: 0 - на север (-z), 90 - на запад (-x), -90 - на восток (+x), 180 - на юг (+z).
const VIEWS = {
  'overview': { x: 0, z: 46, yaw: 0, pitch: -28, h: 26 },
  'campfire': { x: 0, z: 9, yaw: 0, pitch: -3, h: 1.4 },
  'gazebo': { x: 10, z: -4, yaw: -45, pitch: 0, h: 1.4 },
  'chess': { x: 27, z: -17.1, yaw: 0, pitch: -12, h: 1.35 },
  'signpost': { x: -1, z: 12, yaw: 48, pitch: 6, h: 1.5 },
  'hero-tree': { x: -6, z: -8, yaw: 40, pitch: 12, h: 1.4 },
  'apartment': { x: 22, z: 150, yaw: 180, pitch: 6, h: 1.6 },
  'arena': { x: -150, z: 14, yaw: 90, pitch: 4, h: 1.6 },
  'cabin': { x: -16, z: -150, yaw: 0, pitch: 4, h: 1.6 },
  'farm': { x: 130, z: -22, yaw: -90, pitch: 4, h: 1.6 },
  'interior': { x: 700, z: 2.5, yaw: 0, pitch: 0, h: 1.2 },
  'hub-north': { x: 0, z: 14, yaw: 0, pitch: 2 },
  'hub-fir': { x: 38, z: -42, yaw: 20, pitch: 6, h: 1.2 },
  'forest-up': { x: 40, z: -60, yaw: 0, pitch: 28, h: 1.2 },
  'lake': { x: -36, z: 22, yaw: 62, pitch: 0, h: 1.5 },
  'canyon': { x: -128, z: 8, yaw: 90, pitch: 4, h: 1.6 },
  'canyon-high': { x: -112, z: 30, yaw: 60, pitch: 6, h: 14 },
  'autumn': { x: 100, z: -12, yaw: -90, pitch: 2, h: 1.6 },
  'winter': { x: -4, z: -120, yaw: 0, pitch: 2, h: 1.6 },
  'rain': { x: 14, z: 120, yaw: 180, pitch: 2, h: 1.6 },
  'sun-view': { x: 0, z: 10, yaw: 140, pitch: 10, h: 1.6 },
};

const names = process.argv[2] ? process.argv[2].split(',') : Object.keys(VIEWS);
fs.mkdirSync('tests/out', { recursive: true });

const browser = await chromium.launch({
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const page = await browser.newPage({ viewport: { width: W, height: H } });
const problems = [];
page.on('console', (m) => { if (['error', 'warning'].includes(m.type())) problems.push(`[${m.type()}] ${m.text()}`); });
page.on('pageerror', (e) => problems.push(`[pageerror] ${e.message}`));

await page.goto(`${BASE}?q=${process.env.Q || 'medium'}&dpr=1`, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy && window.__cozy.frames >= 2, null, { timeout: 120000 });
// в обходе нужен только мир: прячем меню и персонажа из экрана входа
await page.evaluate(() => {
  document.getElementById('menu').hidden = true;
  window.__cozy.game.avatar.root.visible = false;
});

for (const name of names) {
  const v = VIEWS[name];
  if (!v) { console.log('unknown view', name); continue; }
  await page.evaluate((o) => window.__cozy.setCam({ h: 1.2, pitch: 0, ...o, yaw: (o.yaw * Math.PI) / 180, pitch: ((o.pitch ?? 0) * Math.PI) / 180 }), v);
  const f0 = await page.evaluate(() => window.__cozy.frames);
  await page.waitForFunction((n) => window.__cozy.frames >= n, f0 + 3, { timeout: 120000 });
  await page.screenshot({ path: `tests/out/tour-${name}.png` });
  console.log('saved', name);
}
if (problems.length) console.log('--- console ---\n' + [...new Set(problems)].slice(0, 20).join('\n'));
await browser.close();
