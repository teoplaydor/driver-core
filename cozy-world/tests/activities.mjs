// Каркас активностей: одиночный режим гоняет серверные модули в браузере (LocalRoom) — welcome, сообщения туда и
// обратно, tick; клиентская активность получает их по префиксу; объект мира из реестра (C7) в 1-м лице — подсказка и E.
// Онлайн-путь ядра сервера проверяется тем же форматом модулей (см. server/modules/index.js).
// BASE_URL=http://127.0.0.1:5173/ node tests/activities.mjs

import { chromium } from 'playwright-core';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173/';
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 640, height: 360 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.addInitScript(() => { window.COZY_SOLO = true; });

const results = [];
const check = (name, ok, extra = '') => { results.push(!!ok); console.log(ok ? 'ok  ' : 'FAIL', name, extra); };
const ev = (fn, arg) => page.evaluate(fn, arg);
const frames = async (n) => { const f0 = await ev(() => window.__cozy.frames); await page.waitForFunction((k) => window.__cozy.frames >= k, f0 + n, { timeout: 90000 }); };

await page.goto(`${BASE}?q=low&dpr=1&autostart=1&nick=Тест&cam=fp`, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy?.game?.state === 'play' && window.__cozy.frames >= 4, null, { timeout: 120000 });

check('одиночный режим поднял LocalRoom', await ev(() => !!window.__cozy.game.localRoom && window.__cozy.game.selfId === 'me'));

// Подменяем серверный модуль и клиентскую активность «на лету» (тот же объект модуля, что у LocalRoom).
const round = await ev(async () => {
  const G = window.__cozy.game;
  const mod = (await import('/server/modules/arena.js')).default;
  Object.assign(mod, {
    welcome: (ctx) => ({ hits: ctx.state.hits || 0 }),
    message: (ctx, c, m) => {
      if (m.t !== 'arena_hit') return;
      ctx.state.hits = (ctx.state.hits || 0) + 1;
      ctx.broadcast({ t: 'arena_score', hits: ctx.state.hits, from: c.id, x: ctx.clients()[0].st.p[0] });
    },
    tick: (ctx) => { ctx.state.ticks = (ctx.state.ticks || 0) + 1; },
  });
  const got = [];
  const def = { name: 'arena', prefix: 'arena_' };
  G.activities.push({ _def: def, message: (m) => got.push(m), welcome: (d) => got.push({ welcome: d }) });
  G.player.pos.x = 1.25;
  const f0 = window.__cozy.frames;
  while (window.__cozy.frames < f0 + 2) await new Promise((r) => setTimeout(r, 20)); // позиция уходит в LocalRoom в кадре
  G.localRoom.send({ t: 'arena_hit' });
  G.sendMod({ t: 'arena_hit' });
  G.sendMod({ t: 'zzz_other' });
  await new Promise((r) => setTimeout(r, 1500)); // программный GL медленный: таймеры ждут кадров
  const w = G.localRoom.welcome();
  return { got, w, ticks: G.localRoom.mods.arena.state.ticks };
});
check('сообщения дошли до модуля и вернулись активности по префиксу', round.got.filter((m) => m.t === 'arena_score').length === 2, JSON.stringify(round.got));
check('модуль видит игрока и его позицию (ctx.clients)', round.got.some((m) => m.from === 'me' && Math.abs(m.x - 1.25) < 0.3));
check('welcome модуля отражает состояние', round.w.arena?.hits === 2, JSON.stringify(round.w));
check('tick модуля идёт', round.ticks >= 2, `ticks=${round.ticks}`);

// Реестр C7 в 1-м лице: подсказка и E
await ev(() => {
  const G = window.__cozy.game, P = G.player;
  P.pos.set(0, G.world.collisions.supportY(0, 12, 60), 12); P.vel.set(0, 0, 0); P.yaw = 0; P.pitch = 0;
  window.__used = 0;
  G.world.interactables.add({ id: 'test-sign', label: 'Прочитать табличку', pos: P.pos.clone().add({ x: 0, y: 0.8, z: -1.2 }), radius: 2, use: () => { window.__used++; } });
});
await frames(4);
const hint = await ev(() => document.getElementById('hints')?.textContent || '');
check('подсказка объекта мира в 1-м лице', hint.includes('Прочитать табличку'), hint);
await page.keyboard.press('KeyE');
await frames(3);
check('E использует объект мира', await ev(() => window.__used === 1));
await ev(() => { window.__cozy.game.player.yaw = Math.PI; });
await frames(4);
check('за спиной объект не предлагается', !(await ev(() => document.getElementById('hints')?.textContent || '')).includes('Прочитать табличку'));
await ev(() => window.__cozy.game.world.interactables.remove('test-sign'));

check('нет ошибок', errors.filter((e) => !/KHR_parallel|favicon/.test(e)).length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
if (results.every(Boolean)) console.log('активности ок');
else { console.log('есть ошибки'); process.exitCode = 1; }
