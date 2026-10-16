// Проверка мобильного режима: эмуляция телефона (альбомная ориентация), вид сверху (WS6).
// По умолчанию камера сверху; джойстик двигает относительно камеры; перетаскивание крутит камеру; щипок — зум;
// тап по мячу — персонаж подходит и берёт; ники других — читаемые HTML-подписи; ✋ у двери — переход;
// потолок квартиры скрыт (C5); кнопка прыжка; 📷 — в 1-е лицо и обратно.
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
const check = (name, ok, extra = '') => { results.push(!!ok); console.log(ok ? 'ok  ' : 'FAIL', name, extra); };
const ev = (fn, arg) => page.evaluate(fn, arg);
const frames = async (n) => { const f0 = await ev(() => window.__cozy.frames); await page.waitForFunction((k) => window.__cozy.frames >= k, f0 + n, { timeout: 90000 }); };

await page.goto(`${BASE}?q=low&dpr=1`, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy?.game, null, { timeout: 90000 });
await page.waitForFunction(() => window.__cozy.frames >= 3, null, { timeout: 90000 });
await page.screenshot({ path: 'tests/out/touch-menu.png', timeout: 180000 });
check('режим тач определён', await ev(() => window.__cozy.game.isTouch));

// вход через кнопку "Играть" (как у пользователя)
await page.fill('#nick', 'Мобильный');
await page.click('#play');
await page.waitForFunction(() => window.__cozy.game.state === 'play', null, { timeout: 60000 });
await page.waitForFunction(() => window.__cozy.frames >= 8, null, { timeout: 60000 });
check('тач-интерфейс виден', await ev(() => !document.getElementById('touch').hidden));
check('по умолчанию вид сверху (C1)', await ev(() => window.__cozy.game.player.cameraMode === 'top'));
check('камера над персонажем, смотрит вниз ~55°', await ev(() => {
  const G = window.__cozy.game, c = G.camera, p = G.player.pos;
  const pitch = -c.rotation.x * 57.3;
  return c.position.y - p.y > 6 && pitch > 45 && pitch < 66 && Math.abs(c.fov - 48) < 20;
}), await ev(() => `h=${(window.__cozy.game.camera.position.y - window.__cozy.game.player.pos.y).toFixed(1)} pitch=${(-window.__cozy.game.camera.rotation.x * 57.3).toFixed(0)}°`));
check('свой ник и прицел скрыты', await ev(() => !window.__cozy.game.avatar.tag.visible && getComputedStyle(document.getElementById('crosshair')).display === 'none'));

// ники других игроков: вместо мелкого спрайта — HTML-подпись над головой
await ev(() => {
  const G = window.__cozy.game, P = G.player, x = P.pos.x + 2.5, z = P.pos.z - 1;
  G.remotes.add({ id: 'ws6-friend', name: 'Ромашка', look: [1, 2, 1, 1, 0, 1, 0], p: [x, G.world.collisions.supportY(x, z, 60), z], r: 0 });
});
await frames(3);
check('ник другого игрока — HTML над головой, спрайт скрыт', await ev(() => {
  const G = window.__cozy.game, r = G.remotes.get('ws6-friend');
  const tags = [...document.querySelectorAll('.ntag')].filter((e) => !e.hidden && e.textContent === 'Ромашка');
  if (tags.length !== 1 || r.avatar.tag.visible) return false;
  const b = tags[0].getBoundingClientRect(), head = r.avatar.root.position.clone();
  head.y += r.avatar.top;
  const v = head.project(G.camera), hx = (v.x * 0.5 + 0.5) * innerWidth, hy = (-v.y * 0.5 + 0.5) * innerHeight;
  return Math.abs((b.left + b.right) / 2 - hx) < 6 && b.bottom <= hy + 2 && b.bottom > hy - 40 && b.height >= 18;
}));
await ev(() => window.__cozy.game.remotes.remove('ws6-friend'));

const cdp = await ctx.newCDPSession(page);
const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y, id], i) => ({ x, y, id: id ?? i + 1 })) });
const joy = await ev(() => { const r = document.getElementById('joy').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; });

// джойстик вверх по экрану = от камеры
async function pushUp() {
  const p0 = await ev(() => window.__cozy.game.player.pos.toArray());
  await touch('touchStart', [[joy.x, joy.y, 1]]);
  await touch('touchMove', [[joy.x, joy.y - 50, 1]]);
  await page.waitForFunction((p) => {
    const q = window.__cozy.game.player.pos;
    return Math.hypot(q.x - p[0], q.z - p[2]) > 1.2;
  }, p0, { timeout: 60000 }).catch(() => {});
  await touch('touchEnd', []);
  const p1 = await ev(() => window.__cozy.game.player.pos.toArray());
  const yaw = await ev(() => window.__cozy.game.player.topCam.yaw);
  const dx = p1[0] - p0[0], dz = p1[2] - p0[2], d = Math.hypot(dx, dz);
  const ex = -Math.sin(yaw), ez = -Math.cos(yaw);
  return { d, ang: (Math.acos(Math.max(-1, Math.min(1, (dx * ex + dz * ez) / (d || 1)))) * 180) / Math.PI };
}
const m1 = await pushUp();
check('джойстик двигает игрока от камеры', m1.d > 1.2 && m1.ang < 20, `d=${m1.d.toFixed(2)} угол=${m1.ang.toFixed(0)}°`);
check('персонаж развернулся по ходу движения', await ev(() => {
  const P = window.__cozy.game.player;
  const d = Math.atan2(Math.sin(P.yaw - P.topCam.yaw), Math.cos(P.yaw - P.topCam.yaw));
  return Math.abs(d) < 0.35;
}));

// перетаскивание одним пальцем по пустому месту экрана крутит камеру (не персонажа)
const yaw0 = await ev(() => window.__cozy.game.player.topCam.yaw);
await touch('touchStart', [[560, 120, 2]]);
for (let i = 1; i <= 8; i++) await touch('touchMove', [[560 - i * 14, 120, 2]]);
await touch('touchEnd', []);
await frames(3);
const yaw1 = await ev(() => window.__cozy.game.player.topCam.yaw);
check('перетаскивание поворачивает камеру', Math.abs(yaw1 - yaw0) > 0.25, `dyaw=${(yaw1 - yaw0).toFixed(2)}`);
const m2 = await pushUp();
check('после поворота камеры джойстик ведёт в новую сторону', m2.d > 1.2 && m2.ang < 20, `d=${m2.d.toFixed(2)} угол=${m2.ang.toFixed(0)}°`);

// щипок: пальцы врозь — ближе, вместе — дальше
const d0 = await ev(() => window.__cozy.game.player.topCam.dist);
await touch('touchStart', [[400, 150, 3], [470, 150, 4]]);
for (let i = 1; i <= 6; i++) await touch('touchMove', [[400 - i * 12, 150, 3], [470 + i * 12, 150, 4]]);
await touch('touchEnd', []);
await frames(2);
const d1 = await ev(() => window.__cozy.game.player.topCam.dist);
await touch('touchStart', [[300, 150, 5], [580, 150, 6]]);
for (let i = 1; i <= 6; i++) await touch('touchMove', [[300 + i * 18, 150, 5], [580 - i * 18, 150, 6]]);
await touch('touchEnd', []);
await frames(2);
const d2 = await ev(() => window.__cozy.game.player.topCam.dist);
check('щипок меняет дистанцию камеры', d1 < d0 - 1 && d2 > d1 + 1 && d1 >= 5 && d2 <= 20, `${d0.toFixed(1)} → ${d1.toFixed(1)} → ${d2.toFixed(1)}`);

// тап по мячу: персонаж сам подходит и берёт его
await ev(() => {
  const G = window.__cozy.game, P = G.player;
  P.pos.set(3, G.world.collisions.supportY(3, 16.2, 50), 16.2); P.vel.set(0, 0, 0); P.yaw = 0;
  P.topCam.yaw = 0; P.topCam.dist = 11; P.topCam.snap();
});
await frames(4);
const ballXY = await ev(() => {
  const G = window.__cozy.game, b = G.props.byId.get('ball3');
  const v = b.pos.clone().project(G.camera);
  return [(v.x * 0.5 + 0.5) * innerWidth, (-v.y * 0.5 + 0.5) * innerHeight];
});
await touch('touchStart', [[ballXY[0], ballXY[1], 7]]);
await touch('touchEnd', []);
await page.waitForFunction(() => window.__cozy.game.topView.walk !== null || window.__cozy.game.props.holding, null, { timeout: 20000 }).catch(() => {});
check('тап по мячу — персонаж пошёл к нему', await ev(() => window.__cozy.game.topView.walk?.obj?.id === 'ball3' || window.__cozy.game.props.holding?.id === 'ball3'));
await page.screenshot({ path: 'tests/out/touch-walk.png', timeout: 180000 });
await page.waitForFunction(() => window.__cozy.game.props.holding?.id === 'ball3', null, { timeout: 120000 }).catch(() => {});
check('подошёл и взял мяч', await ev(() => window.__cozy.game.props.holding?.id === 'ball3'),
  await ev(() => { const G = window.__cozy.game, b = G.props.byId.get('ball3'); return `до мяча ${Math.hypot(b.pos.x - G.player.pos.x, b.pos.z - G.player.pos.z).toFixed(2)} м`; }));

// ↗ — положить; у двери ✋ — переход
const tapBtn = async (sel) => {
  const b = await ev((s) => { const r = document.querySelector(s).getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; }, sel);
  await touch('touchStart', [[b[0], b[1], 8]]);
  await frames(1);
  await touch('touchEnd', []);
};
await tapBtn('.b-throw');
await page.waitForFunction(() => !window.__cozy.game.props.holding, null, { timeout: 30000 }).catch(() => {});
check('↗ кладёт предмет', await ev(() => !window.__cozy.game.props.holding));
await ev(() => {
  const G = window.__cozy.game, P = G.player, ps = G.world.structures.portals;
  const po = ps.find((p) => p.id === 'apt-in') || ps.find((p) => p.to.x > 600);
  P.pos.set(po.x, G.world.collisions.supportY(po.x, po.z, po.y + 2), po.z); P.vel.set(0, 0, 0); P.yaw = Math.PI;
  P.topCam.snap();
});
await frames(4);
check('у двери цель — дверь с подписью', await ev(() => window.__cozy.game.topView.target?.type === 'portal' && !document.getElementById('tlabel').hidden));
check('подсветка цели видна (с постобработкой — HDR-яркая для bloom)', await ev(() => {
  const T = window.__cozy.game.topView, c = T.ringMat.uniforms.uColor.value;
  return T.ring.visible && Math.max(c.r, c.g, c.b) > (T.lite ? 0.9 : 1.5);
}));
await page.screenshot({ path: 'tests/out/touch-door.png', timeout: 180000 });
await tapBtn('.b-use');
await page.waitForFunction(() => window.__cozy.game.player.pos.x > 600, null, { timeout: 30000 }).catch(() => {});
check('✋ у двери — переход в квартиру', await ev(() => window.__cozy.game.player.pos.x > 600));
await page.waitForFunction(() => !window.__cozy.game.teleporting, null, { timeout: 30000 }).catch(() => {});
await frames(6);
check('в квартире потолок скрыт (C5)', await ev(() => (window.__cozy.game.topView.roofs || []).some((r) => r.area.x0 > 600 && r.k < 0.5)));
await page.screenshot({ path: 'tests/out/touch-apartment.png', timeout: 180000 });

// прыжок кнопкой
await tapBtn('.b-jump');
await page.waitForFunction(() => !window.__cozy.game.player.onGround || window.__cozy.game.player.vel.y > 0, null, { timeout: 20000 }).catch(() => {});
check('кнопка прыжка срабатывает', await ev(() => !window.__cozy.game.player.onGround || window.__cozy.game.player.vel.y > 0));

// 📷: в первое лицо (руки рисуются, тело скрыто) и обратно
await tapBtn('.tmini [data-act="view"]');
await frames(2);
check('📷 переключает в 1-е лицо', await ev(() => window.__cozy.game.player.cameraMode === 'fp'));
await tapBtn('.tmini [data-act="view"]');
await frames(2);
check('📷 возвращает вид сверху', await ev(() => window.__cozy.game.player.cameraMode === 'top'));

await page.screenshot({ path: 'tests/out/touch-play.png', timeout: 180000 });
check('нет ошибок', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
const bad = results.filter((r) => !r).length;
console.log(bad ? `${bad} проверок не прошло` : 'мобильный режим ок');
process.exit(bad ? 1 : 0);
