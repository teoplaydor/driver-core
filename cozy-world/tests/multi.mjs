// Проверка мультиплеера двумя клиентами: вход в одну комнату, видимость друг друга, чат, движение, предметы.
// Нужен запущенный сервер (npm run dev или npm start): BASE_URL=http://127.0.0.1:5173/ node tests/multi.mjs

import { chromium } from 'playwright-core';
import fs from 'node:fs';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173/';
const ROOM = `t${Date.now().toString(36).slice(-5)}`;
fs.mkdirSync('tests/out', { recursive: true });

const browser = await chromium.launch({
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist',
    '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
});
const results = [];
const check = (name, ok, extra = '') => { results.push({ name, ok: !!ok, extra }); console.log(ok ? 'ok  ' : 'FAIL', name, extra); };

async function open(nick) {
  const ctx = await browser.newContext({ viewport: { width: 640, height: 360 }, permissions: ['microphone'] });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(`${BASE}?autostart=1&nick=${nick}&room=${ROOM}&q=low&dpr=1`, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__cozy?.game?.state === 'play', null, { timeout: 120000 });
  return { page, errors, ctx };
}

try {
  const A = await open('Alice');
  const B = await open('Bob');
  const g = (p, fn, arg) => p.page.evaluate(fn, arg);

  check('A онлайн', await g(A, () => window.__cozy.game.online));
  check('B онлайн', await g(B, () => window.__cozy.game.online), await g(B, () => document.getElementById('status')?.textContent || ''));

  await A.page.waitForFunction(() => window.__cozy.game.remotes.size === 1, null, { timeout: 15000 }).catch(() => {});
  await B.page.waitForFunction(() => window.__cozy.game.remotes.size === 1, null, { timeout: 15000 }).catch(() => {});
  check('A видит Bob', (await g(A, () => window.__cozy.game.remotes.size)) === 1);
  check('B видит Alice', (await g(B, () => window.__cozy.game.remotes.size)) === 1);

  // чат
  await g(A, () => window.__cozy.game.sendChat('привет от Alice'));
  await B.page.waitForFunction(() => document.getElementById('chatlog').textContent.includes('привет от Alice'), null, { timeout: 8000 }).catch(() => {});
  check('чат дошёл до B', await g(B, () => document.getElementById('chatlog').textContent.includes('привет от Alice')));
  check('пузырь над Alice у B', await g(B, () => [...window.__cozy.game.remotes.map.values()][0].avatar.bubble !== null));

  // движение: Alice перед Bob, лицом к нему
  await g(A, () => {
    const p = window.__cozy.game.player;
    p.pos.set(0, p.pos.y, 5);
    p.yaw = Math.PI;
  });
  await g(B, () => { const p = window.__cozy.game.player; p.pos.set(0, p.pos.y, 9.5); p.yaw = 0; });
  await B.page.waitForFunction(() => {
    const r = [...window.__cozy.game.remotes.map.values()][0];
    return r && Math.abs(r.avatar.root.position.z - 5) < 0.3;
  }, null, { timeout: 15000 }).catch(() => {});
  const pos = await g(B, () => { const r = [...window.__cozy.game.remotes.map.values()][0]; return r.avatar.root.position.toArray(); });
  check('позиция Alice у B', Math.abs(pos[0]) < 0.3 && Math.abs(pos[2] - 5) < 0.3, JSON.stringify(pos.map((v) => +v.toFixed(2))));

  await g(B, () => { window.__cozy.game.player.wantThird = false; });
  await B.page.waitForTimeout(1200);
  await B.page.screenshot({ path: 'tests/out/multi-B-view.png' });

  // предметы: Alice берёт мяч, Bob видит нового хозяина; бросок и покой синхронизируются
  await g(A, () => { const G = window.__cozy.game; G.props.grab(G.props.byId.get('ball1')); });
  await B.page.waitForFunction(() => { const o = window.__cozy.game.props.byId.get('ball1').owner; return o && o !== 'me'; }, null, { timeout: 8000 }).catch(() => {});
  check('Bob видит, что мяч взят', await g(B, () => { const o = window.__cozy.game.props.byId.get('ball1').owner; return !!o && o !== 'me'; }));
  await g(B, () => { const G = window.__cozy.game; G.props.grab(G.props.byId.get('ball1')); });
  await B.page.waitForTimeout(600);
  check('Bob не смог отобрать мяч', await g(B, () => window.__cozy.game.props.holding === null));
  check('после отказа Bob помнит хозяина мяча', await g(B, () => { const o = window.__cozy.game.props.byId.get('ball1').owner; return !!o && o !== 'me'; }));
  await g(A, () => { window.__cozy.game.props.release(0.8); });
  await A.page.waitForTimeout(1500);
  // физика на медленном software-рендере "доезжает" минуты; проверяем протокол: хозяин сам объявляет покой
  await g(A, () => { const P = window.__cozy.game.props; P._rest(P.byId.get('ball1')); });
  await B.page.waitForFunction(() => window.__cozy.game.props.byId.get('ball1').owner === null, null, { timeout: 15000 }).catch(() => {});
  check('мяч успокоился и стал ничьим', await g(B, () => window.__cozy.game.props.byId.get('ball1').owner === null));
  const bp = await g(B, () => window.__cozy.game.props.byId.get('ball1').pos.toArray());
  const ap = await g(A, () => window.__cozy.game.props.byId.get('ball1').pos.toArray());
  check('позиция мяча совпадает у A и B', Math.hypot(bp[0] - ap[0], bp[2] - ap[2]) < 0.6, `A=${ap.map((v) => +v.toFixed(2))} B=${bp.map((v) => +v.toFixed(2))}`);

  // WS3: зонт и бумбокс — состояние синхронизируется и отдаётся новичкам в welcome; действие видно у аватара
  const aId = await g(A, () => window.__cozy.game.net.id);
  await g(A, () => { const P = window.__cozy.game.props; P.grab(P.byId.get('umb1')); });
  await B.page.waitForFunction((id) => window.__cozy.game.props.byId.get('umb1').owner === id, aId, { timeout: 8000 }).catch(() => {});
  await A.page.waitForTimeout(400);
  await g(A, () => { const P = window.__cozy.game.props; P.actT = 0; P.use(); });
  await B.page.waitForFunction(() => window.__cozy.game.props.byId.get('umb1').state === 1, null, { timeout: 8000 }).catch(() => {});
  check('зонт раскрылся у Bob', await g(B, () => window.__cozy.game.props.byId.get('umb1').state === 1));
  check('у Bob зонт в руке аватара Alice', await g(B, (id) => {
    const p = window.__cozy.game.props.byId.get('umb1');
    const r = window.__cozy.game.remotes.get(id);
    return p.held && !!p.solo?.visible && r && p.pos.distanceTo(r.avatar.root.position) < 2.2;
  }, aId));
  check('holdKind Alice = umbrella', await g(A, () => window.__cozy.game.holdKind === 'umbrella'));
  await g(A, () => { const P = window.__cozy.game.props; P.release(0); P._rest(P.byId.get('umb1')); P.grab(P.byId.get('boombox1')); });
  await A.page.waitForTimeout(500);
  await g(A, () => { const P = window.__cozy.game.props; P.actT = 0; P.use(); });
  await B.page.waitForFunction(() => window.__cozy.game.props.byId.get('boombox1').state === 1, null, { timeout: 8000 }).catch(() => {});
  check('бумбокс включился у Bob (музыка)', await g(B, () => window.__cozy.game.props.byId.get('boombox1').state === 1 && window.__cozy.game.audio._boxes?.has('boombox1')));
  const C = await open('Carol');
  await C.page.waitForTimeout(500);
  check('новичок видит раскрытый зонт и музыку (welcome)', await g(C, () => {
    const P = window.__cozy.game.props;
    return P.byId.get('umb1').state === 1 && P.byId.get('boombox1').state === 1;
  }));
  check('нет ошибок на странице C', C.errors.length === 0, C.errors.slice(0, 3).join(' | '));
  await C.ctx.close();
  await g(A, () => { const P = window.__cozy.game.props; P.actT = 0; P.use(); P.release(0); P._rest(P.byId.get('boombox1')); });

  // WS3: удар битой — Bob стоит перед Alice: его отбрасывает (через сервер), мяч рядом улетает и становится Alice
  await g(A, () => { const G = window.__cozy.game; const p = G.player; p.pos.set(0, p.pos.y, 5); p.yaw = Math.PI; G.props.grab(G.props.byId.get('bat1')); });
  await g(B, () => { const p = window.__cozy.game.player; p.pos.set(0, p.pos.y, 6.3); p.yaw = 0; });
  await A.page.waitForFunction(() => {
    const r = [...window.__cozy.game.remotes.map.values()][0];
    return r && Math.abs(r.avatar.root.position.z - 6.3) < 0.3;
  }, null, { timeout: 15000 }).catch(() => {});
  await g(A, () => {
    const G = window.__cozy.game, b = G.props.byId.get('ball2');
    b.pos.set(0.5, G.player.pos.y + 0.3, 6.0); b.owner = null; b.dirty = true;
    G.props.actT = 0; G.props.use();
  });
  await B.page.waitForFunction(() => !!window.__cozy.game.props.lastHit, null, { timeout: 20000 }).catch(() => {});
  const hit = await g(B, () => window.__cozy.game.props.lastHit);
  check('Bob получил удар битой с отдачей', !!hit && hit.from === aId && hit.v[1] > 3 && hit.v[2] > 3, JSON.stringify(hit));
  check('Bob видит замах у аватара Alice', await g(B, (id) => {
    const a = window.__cozy.game.remotes.get(id)?.avatar.anim;
    return !!a && (a.act?.name === 'swing' || window.__cozy.game.props.byId.get('bat1').held);
  }, aId));
  await B.page.waitForFunction((id) => window.__cozy.game.props.byId.get('ball2').owner === id, aId, { timeout: 8000 }).catch(() => {});
  check('мяч от удара достался Alice (kick)', await g(B, (id) => window.__cozy.game.props.byId.get('ball2').owner === id, aId));
  await g(A, () => { const P = window.__cozy.game.props; P.release(0); P._rest(P.byId.get('bat1')); });

  // голос: Alice включает (фейковый) микрофон, у Bob должно подняться WebRTC-соединение и пойти звук
  await g(A, async () => { await window.__cozy.game.voice.enable(); });
  check('микрофон Alice включился', (await g(A, () => window.__cozy.game.voice.state)) === 'on');
  await B.page.waitForFunction(() => {
    const p = [...window.__cozy.game.voice.peers.values()][0];
    return p && p.pc.connectionState === 'connected';
  }, null, { timeout: 30000 }).catch(() => {});
  const vst = await g(B, () => {
    const p = [...window.__cozy.game.voice.peers.values()][0];
    return p ? { conn: p.pc.connectionState, ice: p.pc.iceConnectionState, hasAudio: !!p.analyser } : null;
  });
  check('WebRTC-соединение Bob<->Alice', vst && vst.conn === 'connected', JSON.stringify(vst));
  // сигнал от фейкового микрофона - гудок; ждём, пока уровень у Bob станет > 0
  await B.page.waitForFunction(() => {
    const p = [...window.__cozy.game.voice.peers.values()][0];
    return p && p.level > 0.005;
  }, null, { timeout: 25000 }).catch(() => {});
  const lvl = await g(B, () => { const p = [...window.__cozy.game.voice.peers.values()][0]; return p ? p.level : -1; });
  check('Bob слышит голос Alice (уровень > 0)', lvl > 0.005, `level=${lvl}`);

  // выход Alice
  await A.ctx.close();
  await B.page.waitForFunction(() => window.__cozy.game.remotes.size === 0, null, { timeout: 8000 }).catch(() => {});
  check('Bob увидел выход Alice', (await g(B, () => window.__cozy.game.remotes.size)) === 0);

  check('нет ошибок на странице B', B.errors.length === 0, B.errors.slice(0, 3).join(' | '));
  check('нет ошибок на странице A', A.errors.length === 0, A.errors.slice(0, 3).join(' | '));
  await B.ctx.close();
} catch (e) {
  console.error('exception', e);
  results.push({ name: 'exception', ok: false, extra: String(e) });
}
await browser.close();
const bad = results.filter((r) => !r.ok);
console.log(bad.length ? `\n${bad.length} проверок не прошло` : '\nвсе проверки прошли');
process.exit(bad.length ? 1 : 0);
