// Проверка физики предметов без рендера: фиксированный шаг 1/60 прямо в странице.
// BASE_URL=http://127.0.0.1:5173/ node tests/physics.mjs

import { chromium } from 'playwright-core';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173/';
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 320, height: 180 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.goto(`${BASE}?q=low&dpr=1&autostart=1&room=ph${Date.now().toString(36).slice(-4)}`, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy?.game?.state === 'play', null, { timeout: 90000 });

const res = await page.evaluate(async () => {
  const G = window.__cozy.game;
  const P = G.props;
  const out = {};
  const step = (p, sec) => { for (let i = 0; i < sec * 60; i++) { P._simulate(p, 1 / 60); } };

  // 1. брошенный мяч: должен остановиться на земле и объявить покой
  const ball = P.byId.get('ball2');
  ball.owner = 'me'; ball.held = false; ball.restT = 0;
  ball.pos.set(0, 1.4, 6);
  ball.vel.set(1, 2, -9);
  step(ball, 25);
  out.ball = { owner: ball.owner, speed: +ball.vel.length().toFixed(3), y: +ball.pos.y.toFixed(3), x: +ball.pos.x.toFixed(2), z: +ball.pos.z.toFixed(2) };

  // 2. ящик, упавший с высоты на землю: не проваливается и выравнивается
  const crate = P.byId.get('crate1');
  crate.owner = 'me'; crate.restT = 0;
  crate.pos.set(-8, 4, -3);
  crate.vel.set(0, 0, 0);
  crate.ang.set(3, 2, 1);
  step(crate, 8);
  out.crate = { owner: crate.owner, y: +crate.pos.y.toFixed(3) };

  // 3. шахматная пешка падает на доску рядом с центром клетки: должна встать ровно в клетку
  const pawn = P.byId.get('cwP3');
  pawn.owner = 'me'; pawn.restT = 0;
  const c = 0.115, B = { x: 27, z: -19 };
  pawn.pos.set(B.x + (2 - 3.5) * c + 0.03, 3.2, B.z + (4 - 3.5) * c - 0.02);
  pawn.vel.set(0, 0, 0);
  step(pawn, 6);
  const fx = (pawn.pos.x - B.x) / c + 3.5, fz = (pawn.pos.z - B.z) / c + 3.5;
  out.pawn = { owner: pawn.owner, y: +pawn.pos.y.toFixed(3), cellX: +fx.toFixed(3), cellZ: +fz.toFixed(3) };

  // 4. игрок рядом с ящиком не проходит сквозь него (коллизия ящика у костра не нужна), а вот скамейка в беседке держит
  const tableTop = G.world.collisions.supportY(27, -19, 1.2, 0.45);
  out.tableTop = +tableTop.toFixed(3);
  return out;
});
console.log(JSON.stringify(res, null, 1));

const fails = [];
const ok = (c, m) => { if (!c) fails.push(m); };
ok(res.ball.owner === null && res.ball.speed < 0.01, 'мяч не остановился');
ok(res.ball.y > 0 && res.ball.y < 2.5, `мяч на странной высоте ${res.ball.y}`);
ok(res.crate.owner === null && res.crate.y > 0 && res.crate.y < 3, 'ящик не лёг');
ok(res.pawn.owner === null && Math.abs(res.pawn.cellX - 2) < 0.01 && Math.abs(res.pawn.cellZ - 4) < 0.01, 'пешка не встала в клетку');
ok(Math.abs(res.pawn.y - 1.705) < 0.01, `пешка стоит на высоте ${res.pawn.y}, а должна на доске (1.705)`);
ok(Math.abs(res.tableTop - 1.63) < 0.02, `верх стола с доской ${res.tableTop}`);
ok(errors.length === 0, `ошибки: ${errors.join(' | ')}`);
await browser.close();
console.log(fails.length ? `ПРОВАЛ:\n- ${fails.join('\n- ')}` : 'физика ок');
process.exit(fails.length ? 1 : 0);
