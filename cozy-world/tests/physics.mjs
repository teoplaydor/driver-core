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

  // 3. шахматы: стол с доской из беседки убран (WS5b; новую доску у спавна ставит WS5a), поэтому
  // а) фигура, поставленная на высоте доски рядом с центром клетки, встаёт ровно в клетку (логика снапа);
  // б) пешка, упавшая в беседке, ложится на чайный столик и не проваливается
  const pawn = P.byId.get('cwP3');
  const c = 0.115, B = { x: 27, z: -19 };
  pawn.owner = null; pawn.vel.set(0, 0, 0);
  pawn.pos.set(B.x + (2 - 3.5) * c + 0.03, 1.705 + 0.02, B.z + (4 - 3.5) * c - 0.02);
  P._snapToBoard(pawn);
  const fx = (pawn.pos.x - B.x) / c + 3.5, fz = (pawn.pos.z - B.z) / c + 3.5;
  out.pawn = { owner: pawn.owner, y: +pawn.pos.y.toFixed(3), cellX: +fx.toFixed(3), cellZ: +fz.toFixed(3) };
  pawn.owner = 'me'; pawn.restT = 0;
  pawn.pos.set(B.x + 0.1, 3.2, B.z - 0.05);
  step(pawn, 6);
  out.pawnTea = { owner: pawn.owner, y: +pawn.pos.y.toFixed(3) };

  // 4. верх чайного столика в беседке держит (на столы и лавки можно запрыгнуть)
  const tableTop = G.world.collisions.supportY(27, -19, 1.2, 0.45);
  out.tableTop = +tableTop.toFixed(3);

  // ось Y предмета в мире: насколько она смотрит вверх
  const upY = (q) => 1 - 2 * (q.x * q.x + q.z * q.z);
  const ground = (x, z) => G.world.collisions.supportY(x, z, 50);

  // 5. бита падает с вращением и ложится на бок, не проваливается и в покое не дрожит
  const bat = P.byId.get('bat4');
  bat.owner = 'me'; bat.held = false; bat.restT = 0;
  bat.pos.set(2, 3, 14); bat.vel.set(1, 0, -1); bat.ang.set(4, 1, 7);
  step(bat, 10);
  out.bat = { owner: bat.owner, axisY: +upY(bat.quat).toFixed(3), h: +(bat.pos.y - ground(bat.pos.x, bat.pos.z)).toFixed(3) };
  const rest0 = bat.pos.clone();
  bat.owner = 'me'; bat.restT = 0;
  step(bat, 1);
  out.bat.jitter = +bat.pos.distanceTo(rest0).toFixed(4);
  out.bat.again = bat.owner;

  // 6. кружка и чайник, брошенные с вращением, встают ровно
  for (const id of ['mug1', 'teapot1']) {
    const m = P.byId.get(id);
    m.owner = 'me'; m.held = false; m.restT = 0;
    m.pos.set(-2, 2.5, 13); m.vel.set(0.5, 1, 0.5); m.ang.set(6, 2, -5);
    step(m, 8);
    out[id] = { owner: m.owner, up: +upY(m.quat).toFixed(4), h: +(m.pos.y - ground(m.pos.x, m.pos.z)).toFixed(3) };
  }

  // 7. фрисби и мяч: одинаковый бросок (сила 1) из одной точки — фрисби планирует и улетает дальше
  const throwFrom = (id) => {
    const p = P.byId.get(id);
    G.player.pos.set(0, ground(0, 9.5), 9.5); G.player.yaw = Math.PI; G.player.pitch = 0; G.player.vel.set(0, 0, 0);
    P.grab(p);
    p.pos.set(0, G.player.pos.y + 1.2, 9.9);
    P._launch(p, 1);
    const x0 = p.pos.x, z0 = p.pos.z;
    let land = null, maxY = p.pos.y;
    for (let i = 0; i < 600 && p.owner === 'me'; i++) {
      const vy = p.vel.y;
      P._simulate(p, 1 / 60);
      maxY = Math.max(maxY, p.pos.y);
      // первое касание земли: падение сменилось отскоком или остановкой
      if (!land && vy < -0.5 && p.vel.y > vy + 0.4) land = Math.hypot(p.pos.x - x0, p.pos.z - z0);
    }
    return { land: +(land || 0).toFixed(2), rest: +Math.hypot(p.pos.x - x0, p.pos.z - z0).toFixed(2), owner: p.owner, maxY: +maxY.toFixed(2) };
  };
  out.frisbee = throwFrom('frisbee1');
  out.ballThrow = throwFrom('ball3');
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
ok(res.pawnTea.owner === null && Math.abs(res.pawnTea.y - (res.tableTop + 0.075)) < 0.01, `пешка не легла на чайный столик: ${res.pawnTea.y}`);
ok(Math.abs(res.tableTop - 1.3) < 0.02, `верх чайного столика в беседке ${res.tableTop}`);
ok(res.bat.owner === null && Math.abs(res.bat.axisY) < 0.1, `бита не легла на бок (ось Y: ${res.bat.axisY})`);
ok(res.bat.h > 0.02 && res.bat.h < 0.08, `бита на высоте ${res.bat.h} над землёй (провалилась или висит)`);
ok(res.bat.jitter < 0.003 && res.bat.again === null, `бита дрожит в покое: сдвиг ${res.bat.jitter}`);
for (const id of ['mug1', 'teapot1']) {
  ok(res[id].owner === null && res[id].up > 0.999, `${id} не встал ровно (up ${res[id].up})`);
  ok(res[id].h > 0.03 && res[id].h < 0.12, `${id} на высоте ${res[id].h} над землёй`);
}
ok(res.frisbee.land > res.ballThrow.land * 1.5, `фрисби (${res.frisbee.land} м) летит не дальше мяча (${res.ballThrow.land} м)`);
ok(res.frisbee.owner === null && res.ballThrow.owner === null, 'после броска предметы не успокоились');
ok(errors.length === 0, `ошибки: ${errors.join(' | ')}`);
await browser.close();
console.log(fails.length ? `ПРОВАЛ:\n- ${fails.join('\n- ')}` : 'физика ок');
process.exit(fails.length ? 1 : 0);
