// Игровые виды и проверки персонажей: 3-е лицо, руки от 1-го лица с предметами, шахматы, крупные планы,
// плюс численные проверки анимации (стопы не скользят, чужие аватары двигаются, якоря предметов, бюджет).
// Кадры считаем вручную с фиксированным dt — результат не зависит от скорости программного рендера.
// BASE_URL=http://127.0.0.1:5173/ node tests/views.mjs

import { chromium } from 'playwright-core';
import fs from 'node:fs';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173/';
fs.mkdirSync('tests/out', { recursive: true });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 1100, height: 620 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
// одиночный режим с демо-ботами: сервер для этих проверок не нужен
await page.addInitScript(() => { window.COZY_SOLO = true; });
await page.goto(`${BASE}?q=medium&dpr=1&autostart=1&nick=Тест`, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy?.game?.state === 'play' && window.__cozy.game.remotes.size >= 3, null, { timeout: 120000 });

const results = [];
const check = (name, ok, extra = '') => { results.push(!!ok); console.log(ok ? 'ok  ' : 'FAIL', name, extra); };
const shot = async (file) => { await page.waitForTimeout(120); await page.screenshot({ path: `tests/out/${file}`, timeout: 180000 }); };

// останавливаем цикл кадров; дальше шагаем вручную
await page.evaluate(async () => {
  const G = window.__cozy.game;
  document.getElementById('hud').hidden = true;
  G.frame = () => {};
  G.input.requestLock = () => {};
  const THREE = await import('/node_modules/three/build/three.module.js');
  window.__T = THREE;
  const P = G.player;
  window.__ctl = null;
  P.update = function (dt) { this.lookDX = 0; this.lookDY = 0; if (window.__ctl) window.__ctl(this, dt); };
  window.__step = (n, dt = 1 / 60) => { for (let i = 0; i < n; i++) { G.time += dt; G.playUpdate(dt); G.bots?.update(0); } };
  window.__render = () => {
    const r = G.renderer;
    G.world.update(0.0001, G.time, G.camera, G.player.pos);
    r.autoClear = true;
    r.render(G.world.scene, G.camera);
    if (!G.player.thirdPerson && !G.player.wantThird) { r.autoClear = false; r.clearDepth(); r.render(G.hands.scene, G.hands.camera); r.autoClear = true; }
  };
  window.__ground = (x, z) => G.world.collisions.supportY(x, z, 50);
  window.__place = (x, z, yaw) => { P.pos.set(x, window.__ground(x, z), z); P.yaw = yaw; P.vel.set(0, 0, 0); P.speed = 0; P.onGround = true; };
});

// 1. от третьего лица рядом с костром, лицом к камере
await page.evaluate(() => {
  const G = window.__cozy.game, P = G.player;
  window.__place(2.5, 6.5, 2.6);
  P.pitch = -0.1; P.wantThird = true; P.thirdDist = 3.6;
  window.__ctl = (p) => { p.vel.set(0, 0, 0); p.speed = 0; };
  window.__step(40);
  P.applyCamera(G.camera, 1);
  window.__render();
});
await shot('view-third.png');

// 2. от первого лица: держим мяч двумя руками
await page.evaluate(() => {
  const G = window.__cozy.game, P = G.player;
  P.wantThird = false; P.camDist = 0;
  window.__place(0, 8, 0); P.pitch = -0.05;
  G.props.grab(G.props.byId.get('ball3'));
  window.__step(30);
  P.applyCamera(G.camera, 1);
  window.__render();
});
await shot('view-hold.png');

// 3. шахматный стол вблизи
await page.evaluate(() => {
  const G = window.__cozy.game, P = G.player;
  G.props.release(0);
  // мяч возвращаем на место, чтобы не падал в следующие кадры
  const b = G.props.byId.get('ball3');
  b.pos.set(b.def.x, window.__ground(b.def.x, b.def.z) + b.k.r, b.def.z); b.vel.set(0, 0, 0); b.owner = null;
  P.pos.set(27, 0.8, -15.4); P.yaw = 0; P.pitch = -0.65;
  window.__step(10);
  P.applyCamera(G.camera, 1);
  window.__render();
});
await shot('view-chess.png');

// 4. руки от первого лица: бита, середина замаха, зонт, приветствие
for (const [file, setup, steps] of [
  ['view-fp-bat.png', "G.holdKind = 'bat';", 30],
  ['view-fp-swing.png', "G.hands.act('swing');", 14],
  ['view-fp-umbrella.png', "G.holdKind = 'umbrella';", 40],
  ['view-fp-wave.png', "G.holdKind = null; G.hands.act('wave');", 30],
]) {
  await page.evaluate(([setup, steps]) => {
    const G = window.__cozy.game, P = G.player;
    window.__place(0.5, 7.5, 0); P.pitch = -0.05; P.wantThird = false; P.camDist = 0;
    (new Function('G', setup))(G);
    window.__step(steps);
    P.applyCamera(G.camera, 1);
    window.__render();
  }, [setup, steps]);
  await shot(file);
}

// 5. крупный план своего персонажа и чужие персонажи (демо-боты) у костра
await page.evaluate(() => {
  const G = window.__cozy.game, P = G.player;
  G.holdKind = undefined;
  window.__place(0.4, 6.2, 0.4);
  P.wantThird = true; P.camDist = 3;
  window.__step(20);
  const av = G.avatar.root;
  G.camera.position.set(av.position.x - Math.sin(0.4 + 0.5) * 2.6, av.position.y + 1.0, av.position.z - Math.cos(0.4 + 0.5) * 2.6);
  G.camera.lookAt(av.position.x, av.position.y + 0.68, av.position.z);
  G.camera.fov = 42; G.camera.updateProjectionMatrix();
  window.__render();
  G.camera.fov = 78; G.camera.updateProjectionMatrix();
});
await shot('view-avatar.png');

// ---------- численные проверки ----------
const num = await page.evaluate(() => {
  const G = window.__cozy.game, P = G.player, T = window.__T;
  const out = {};
  const A = G.avatar;
  out.tris = A.body.geometry.index.count / 3 + A.gloss.geometry.index.count / 3;
  out.lodTris = A.lo.body.index.count / 3 + A.lo.gloss.index.count / 3;
  out.meshes = A.root.children.filter((c) => c.isMesh).length;
  // стопа в опоре не скользит (ходьба 4.2 м/с и бег 7.4 м/с)
  const foot = A.bones.find((b) => b.name === 'footL');
  const v = new T.Vector3();
  for (const speed of [4.2, 7.4]) {
    window.__place(0.3, 18, 0);
    A.anim.inited = false;
    window.__ctl = (p, dt) => { p.pos.z -= speed * dt; p.vel.set(0, 0, -speed); p.speed = speed; p.onGround = true; p.pos.y = G.world.collisions.supportY(p.pos.x, p.pos.z, p.pos.y + 0.5); };
    let start = null, slide = 0, stances = 0, sink = 0;
    for (let i = 0; i < 120; i++) {
      window.__step(1);
      A.root.updateMatrixWorld(true);
      if (i < 30) continue;
      const f = A.anim.feet[0];
      foot.getWorldPosition(v);
      if (f.mode === 0 && Math.abs(f.pitch) < 0.05) {
        if (!start) { start = v.clone(); stances++; }
        slide = Math.max(slide, Math.hypot(v.x - start.x, v.z - start.z));
        sink = Math.max(sink, (window.__ground(v.x, v.z) + 0.08) - v.y);
      } else start = null;
    }
    out[`slide${speed}`] = slide; out[`stances${speed}`] = stances; out[`sink${speed}`] = sink;
  }
  window.__ctl = (p) => { p.vel.set(0, 0, 0); p.speed = 0; };
  // чужие аватары (боты) анимируются: кости ног двигаются
  const bot = [...G.remotes.map.values()][0].avatar;
  const bf = bot.bones.find((b) => b.name === 'footR');
  const q0 = bf.quaternion.clone(), p0 = bot.root.position.clone();
  for (let i = 0; i < 30; i++) { G.bots.update(1 / 60); G.remotes.update(1 / 60, G.camera); }
  out.botMoved = bot.root.position.distanceTo(p0);
  out.botLegAnim = 1 - Math.abs(bf.quaternion.dot(q0));
  // якоря предметов (C3): есть, следуют за кистью, +Y вверх у зонта
  G.holdKind = 'umbrella';
  window.__step(40);
  A.root.updateMatrixWorld(true);
  const an = A.anchor('umbrella');
  const up = new T.Vector3(0, 1, 0).transformDirection(an.matrixWorld);
  const ap = an.getWorldPosition(new T.Vector3()), hp = A.handR.getWorldPosition(new T.Vector3());
  out.umbrellaUp = up.y;
  out.anchorToHand = ap.distanceTo(hp);
  out.anchorTwo = !!A.anchor('two') && A.anchor('two').parent?.name === 'chest';
  G.holdKind = null;
  // действия не падают
  out.actsOk = true;
  try {
    for (const a of ['wave', 'swing', 'strum', 'toggle', 'throw']) { A.act(a); G.hands.act(a); window.__step(12); }
    A.wave();
  } catch (e) { out.actsOk = e.message; }
  // совместимость внешности: старый массив из 5 чисел
  const look = A.constructor.prototype; void look;
  return out;
});
const compat = await page.evaluate(async () => {
  const m = await import('/src/player/avatar.js');
  const a = m.lookFromArray([1, 2, 3, 1, 2]);
  const b = m.lookToArray({ ...a, hairStyle: 4, facial: 2 });
  return { old: a.hairStyle === 0 && a.facial === 0 && a.hat === 3, len: b.length, hk: m.holdToCode('bat') === 3 && m.holdFromCode(4) === 'umbrella' && m.holdToCode(undefined) === 0 };
});

check('треугольников на персонажа ≤ 30k', num.tris <= 30000, `${num.tris} (LOD ${num.lodTris})`);
check('мешей на персонажа ≤ 24', num.meshes <= 24, `${num.meshes}`);
check('ходьба: стопа в опоре не скользит (< 1.5 см)', num['slide4.2'] < 0.015 && num['stances4.2'] >= 2, `slide=${num['slide4.2'].toFixed(4)} опор=${num['stances4.2']}`);
check('бег: стопа в опоре не скользит (< 1.5 см)', num['slide7.4'] < 0.015 && num['stances7.4'] >= 2, `slide=${num['slide7.4'].toFixed(4)} опор=${num['stances7.4']}`);
check('стопа не проваливается в землю (< 1 см)', Math.max(num['sink4.2'], num['sink7.4']) < 0.01, `${Math.max(num['sink4.2'], num['sink7.4']).toFixed(4)}`);
check('чужой аватар (бот) идёт и двигает ногами', num.botMoved > 0.2 && num.botLegAnim > 1e-4, `путь=${num.botMoved.toFixed(2)} поворот кости=${num.botLegAnim.toExponential(1)}`);
check('якорь зонта: +Y вверх, у кисти', num.umbrellaUp > 0.8 && num.anchorToHand < 0.15, `up=${num.umbrellaUp.toFixed(2)} d=${num.anchorToHand.toFixed(3)}`);
check('якорь «двумя руками» на груди', num.anchorTwo);
check('действия wave/swing/strum/toggle/throw', num.actsOk === true, String(num.actsOk));
check('внешность: старый массив из 5 чисел и hk', compat.old && compat.len === 7 && compat.hk, JSON.stringify(compat));
check('нет ошибок на странице', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
const bad = results.filter((r) => !r).length;
console.log(bad ? `${bad} проверок не прошло` : 'скриншоты готовы, все проверки прошли');
process.exit(bad ? 1 : 0);
