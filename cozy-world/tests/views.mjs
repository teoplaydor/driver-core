// Скриншоты игровых видов: от 3-го лица и от 1-го с предметом в руках.
// BASE_URL=http://127.0.0.1:5173/ node tests/views.mjs

import { chromium } from 'playwright-core';
import fs from 'node:fs';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173/';
fs.mkdirSync('tests/out', { recursive: true });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 1100, height: 620 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.goto(`${BASE}?q=medium&dpr=1&autostart=1&nick=Тест&room=v${Date.now().toString(36).slice(-4)}`, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy?.game?.state === 'play', null, { timeout: 120000 });
await page.evaluate(() => { document.getElementById('help').hidden = true; });

const frames = (n) => page.evaluate((n) => new Promise((res) => { const f0 = window.__cozy.frames; const t = setInterval(() => { if (window.__cozy.frames >= f0 + n) { clearInterval(t); res(); } }, 50); }), n);

// 1. от третьего лица рядом с костром, лицом к камере
await page.evaluate(() => {
  const G = window.__cozy.game;
  G.player.pos.set(2.5, G.player.pos.y, 6.5);
  G.player.yaw = 2.6; // смотрим на юг-запад: камера окажется "перед" аватаром
  G.player.pitch = -0.1;
  G.player.wantThird = true;
  G.player.thirdDist = 3.6;
});
await frames(8);
await page.screenshot({ path: 'tests/out/view-third.png' });

// 2. от первого лица, держим мяч
await page.evaluate(() => {
  const G = window.__cozy.game;
  G.player.wantThird = false;
  G.player.pos.set(0, G.player.pos.y, 8);
  G.player.yaw = 0;
  G.player.pitch = -0.05;
  G.props.grab(G.props.byId.get('ball3'));
});
await frames(10);
await page.screenshot({ path: 'tests/out/view-hold.png' });

// 3. шахматный стол вблизи
await page.evaluate(() => {
  const G = window.__cozy.game;
  G.props.release(0);
  G.player.pos.set(27, 0.8, -15.4);
  G.player.yaw = 0;
  G.player.pitch = -0.65;
});
await frames(8);
await page.screenshot({ path: 'tests/out/view-chess.png' });

console.log(errors.length ? `ошибки: ${errors.join(' | ')}` : 'скриншоты готовы, ошибок нет');
await browser.close();
