// Проверка мобильного режима: эмуляция телефона (альбомная ориентация), джойстик, обзор, кнопки.
// BASE_URL=http://127.0.0.1:5173/ node tests/touch.mjs

import { chromium } from 'playwright-core';
import fs from 'node:fs';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173/';
fs.mkdirSync('tests/out', { recursive: true });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const ctx = await browser.newContext({
  viewport: { width: 844, height: 390 }, deviceScaleFactor: 1, isMobile: true, hasTouch: true,
  userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
});
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

const results = [];
const check = (name, ok, extra = '') => { results.push(ok); console.log(ok ? 'ok  ' : 'FAIL', name, extra); };

await page.goto(`${BASE}?q=low&dpr=1`, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy?.game, null, { timeout: 90000 });
await page.waitForFunction(() => window.__cozy.frames >= 3, null, { timeout: 90000 });
await page.screenshot({ path: 'tests/out/touch-menu.png' });
check('режим тач определён', await page.evaluate(() => window.__cozy.game.isTouch));

// вход через кнопку "Играть" (как у пользователя), комната не нужна - сервер есть, но берём одиночную
await page.fill('#nick', 'Мобильный');
await page.click('#play');
await page.waitForFunction(() => window.__cozy.game.state === 'play', null, { timeout: 60000 });
await page.waitForFunction(() => window.__cozy.frames >= 8, null, { timeout: 60000 });
check('тач-интерфейс виден', await page.evaluate(() => !document.getElementById('touch').hidden));

const cdp = await ctx.newCDPSession(page);
const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], i) => ({ x, y, id: i + 1 })) });
const box = await page.evaluate(() => { const r = document.getElementById('joy').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; });

// джойстик вперёд
const p0 = await page.evaluate(() => window.__cozy.game.player.pos.toArray());
await touch('touchStart', [[box.x, box.y]]);
await touch('touchMove', [[box.x, box.y - 50]]);
await page.waitForFunction((p) => {
  const q = window.__cozy.game.player.pos;
  return Math.hypot(q.x - p[0], q.z - p[2]) > 0.8;
}, p0, { timeout: 40000 }).catch(() => {});
await touch('touchEnd', []);
const p1 = await page.evaluate(() => window.__cozy.game.player.pos.toArray());
check('джойстик двигает игрока вперёд', Math.hypot(p1[0] - p0[0], p1[2] - p0[2]) > 0.8, `dz=${(p1[2] - p0[2]).toFixed(2)}`);
check('вперёд = на север (-z)', p1[2] < p0[2]);

// обзор правой половиной экрана
const yaw0 = await page.evaluate(() => window.__cozy.game.player.yaw);
await touch('touchStart', [[600, 200]]);
for (let i = 1; i <= 6; i++) await touch('touchMove', [[600 + i * 20, 200]]);
await touch('touchEnd', []);
await page.waitForTimeout(500);
const yaw1 = await page.evaluate(() => window.__cozy.game.player.yaw);
check('перетаскивание поворачивает камеру', Math.abs(yaw1 - yaw0) > 0.1, `dyaw=${(yaw1 - yaw0).toFixed(3)}`);

// прыжок кнопкой
await page.evaluate(() => { window.__cozy.game.player.__maxVy = 0; });
const jb = await page.evaluate(() => { const r = document.querySelector('.b-jump').getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; });
await touch('touchStart', [jb]);
await touch('touchEnd', []);
await page.waitForFunction(() => !window.__cozy.game.player.onGround || window.__cozy.game.player.vel.y > 0, null, { timeout: 20000 }).catch(() => {});
check('кнопка прыжка срабатывает', await page.evaluate(() => !window.__cozy.game.player.onGround || window.__cozy.game.player.vel.y > 0));

await page.screenshot({ path: 'tests/out/touch-play.png' });
check('нет ошибок', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
const bad = results.filter((r) => !r).length;
console.log(bad ? `${bad} проверок не прошло` : 'мобильный режим ок');
process.exit(bad ? 1 : 0);
