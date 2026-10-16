// Вид сверху на ПК (WS6) и контракты вокруг него: C переключает fp → tp → top; ПКМ крутит камеру, колесо — зум,
// клик — идти к предмету; WASD относительно камеры; руки не рисуются вне 1-го лица (C1); растворение листвы (C4)
// и крыши (C5) только в виде сверху; объект мира из реестра (C7) — цель и E; бросок — по направлению персонажа.
// BASE_URL=http://127.0.0.1:5173/ node tests/topdown.mjs

import { chromium } from 'playwright-core';
import fs from 'node:fs';
import { markLegacyRoofs } from './_roofs.mjs';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173/';
fs.mkdirSync('tests/out', { recursive: true });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 960, height: 540 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.addInitScript(() => { window.COZY_SOLO = true; });

const results = [];
const check = (name, ok, extra = '') => { results.push(!!ok); console.log(ok ? 'ok  ' : 'FAIL', name, extra); };
const ev = (fn, arg) => page.evaluate(fn, arg);
const frames = async (n) => { const f0 = await ev(() => window.__cozy.frames); await page.waitForFunction((k) => window.__cozy.frames >= k, f0 + n, { timeout: 90000 }); };
const place = (x, z, yaw = 0, cyaw = yaw) => ev(([x, z, yaw, cyaw]) => {
  const G = window.__cozy.game, P = G.player;
  P.pos.set(x, G.world.collisions.supportY(x, z, x > 600 ? 1 : 60), z); P.vel.set(0, 0, 0); P.yaw = yaw; P.onGround = true;
  P.topCam.yaw = cyaw; P.topCam.snap();
  G.topView.cancelWalk();
}, [x, z, yaw, cyaw]);

await page.goto(`${BASE}?q=low&dpr=1&autostart=1&nick=ПК`, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy?.game?.state === 'play' && window.__cozy.frames >= 6, null, { timeout: 120000 });
await markLegacyRoofs(page); // крыши по C5 (до слияния с WS5b — разметка только в тесте)
// следим, рисуются ли руки (на low — отдельный render сцены рук), и за растворением листвы
await ev(() => {
  const G = window.__cozy.game, r = G.renderer, orig = r.render.bind(r);
  window.__handsDrawn = 0;
  r.render = (scene, cam) => { if (scene === G.hands.scene) window.__handsDrawn++; return orig(scene, cam); };
  const so = G.world.setOcclusion.bind(G.world);
  G.world.setOcclusion = (a, b, on) => { window.__occ = !!on; return so(a, b, on); };
});

check('на ПК по умолчанию 1-е лицо', await ev(() => window.__cozy.game.player.cameraMode === 'fp'));
await frames(3);
check('в 1-м лице руки рисуются', await ev(() => window.__handsDrawn > 0));

await page.keyboard.press('KeyC');
await frames(2);
check('C: от третьего лица', await ev(() => window.__cozy.game.player.cameraMode === 'tp'));
await page.keyboard.press('KeyC');
await frames(2);
check('C: вид сверху', await ev(() => window.__cozy.game.player.cameraMode === 'top'));
await frames(14); // перелёт камеры закончился
await ev(() => { window.__handsDrawn = 0; });
await frames(3);
check('сверху руки не рисуются, тело видно', await ev(() => window.__handsDrawn === 0 && window.__cozy.game.avatar.body.visible));
check('сверху без паузы и прицела, листва растворяется (C4)', await ev(() => document.getElementById('pause').hidden
  && getComputedStyle(document.getElementById('crosshair')).display === 'none' && window.__occ === true));

// ПКМ — поворот камеры, колесо — зум
await place(0, 9.5, 0, 0);
await frames(2);
const yaw0 = await ev(() => window.__cozy.game.player.topCam.yaw);
await page.mouse.move(480, 270);
await page.mouse.down({ button: 'right' });
for (let i = 1; i <= 8; i++) await page.mouse.move(480 + i * 15, 270);
await page.mouse.up({ button: 'right' });
await frames(2);
const yaw1 = await ev(() => window.__cozy.game.player.topCam.yaw);
check('ПКМ поворачивает камеру', Math.abs(yaw1 - yaw0) > 0.2, `dyaw=${(yaw1 - yaw0).toFixed(2)}`);
const dist0 = await ev(() => window.__cozy.game.player.topCam.dist);
await page.mouse.wheel(0, 400);
await frames(2);
const dist1 = await ev(() => window.__cozy.game.player.topCam.dist);
check('колесо меняет дистанцию', dist1 > dist0 + 1 && dist1 <= 20, `${dist0.toFixed(1)} → ${dist1.toFixed(1)}`);
await ev(() => { window.__cozy.game.player.topCam.dist = 11; });

// WASD относительно камеры: камера повёрнута на 90° (смотрит на запад) — W ведёт на запад (-x)
await place(0, 14, 0, Math.PI / 2);
await frames(2);
const w0 = await ev(() => window.__cozy.game.player.pos.toArray());
await page.keyboard.down('KeyW');
await page.waitForFunction((p) => { const q = window.__cozy.game.player.pos; return Math.hypot(q.x - p[0], q.z - p[2]) > 1.2; }, w0, { timeout: 60000 }).catch(() => {});
await page.keyboard.up('KeyW');
const w1 = await ev(() => window.__cozy.game.player.pos.toArray());
check('W идёт от камеры (на запад)', w1[0] - w0[0] < -1 && Math.abs(w1[2] - w0[2]) < 0.4, `dx=${(w1[0] - w0[0]).toFixed(2)} dz=${(w1[2] - w0[2]).toFixed(2)}`);
check('персонаж смотрит по ходу движения', await ev(() => Math.abs(Math.atan2(Math.sin(window.__cozy.game.player.yaw - Math.PI / 2), Math.cos(window.__cozy.game.player.yaw - Math.PI / 2))) < 0.4));

// курсор-«рука» над предметом; клик по мячу — подходит и берёт
await place(-6.5, 8.5, 0, 0);
await frames(3);
const bxy = await ev(() => { const G = window.__cozy.game, v = G.props.byId.get('ball2').pos.clone().project(G.camera); return [(v.x * 0.5 + 0.5) * innerWidth, (-v.y * 0.5 + 0.5) * innerHeight]; });
await page.mouse.move(bxy[0], bxy[1]);
await frames(5);
check('курсор над мячом — «рука»', await ev(() => window.__cozy.game.canvas.style.cursor === 'pointer'));
await page.mouse.click(bxy[0], bxy[1]);
await page.waitForFunction(() => window.__cozy.game.props.holding?.id === 'ball2', null, { timeout: 120000 }).catch(() => {});
check('клик по мячу: подошёл и взял', await ev(() => window.__cozy.game.props.holding?.id === 'ball2'));

// бросок — по направлению персонажа (камера смотрит вниз)
await ev(() => { const G = window.__cozy.game; G.player.yaw = -Math.PI / 2; G.props.release(0.9); }); // лицом на восток
await frames(1);
const bv = await ev(() => window.__cozy.game.props.byId.get('ball2').vel.toArray());
check('бросок летит туда, куда смотрит персонаж', bv[0] > 3 && Math.abs(bv[2]) < 1.5 && bv[1] > 0, `v=${bv.map((v) => v.toFixed(1))}`);
await ev(() => { const P = window.__cozy.game.props; P._rest(P.byId.get('ball2')); });

// клик по земле — идёт в точку, маркер виден
await place(0, 14, 0, 0);
await frames(2);
await page.mouse.click(480, 170);
await frames(2);
check('клик по земле: идёт в точку с маркером', await ev(() => window.__cozy.game.topView.walk?.type === 'ground' && window.__cozy.game.topView.mark.visible));
await page.keyboard.down('KeyS');
await frames(2);
await page.keyboard.up('KeyS');
check('клавиши отменяют автодвижение', await ev(() => window.__cozy.game.topView.walk === null));

// объект мира (C7): реестр WS5a, а если его ещё нет — минимальная заглушка по контракту
await ev(async () => {
  const G = window.__cozy.game;
  const THREE = await import('/node_modules/three/build/three.module.js');
  if (!G.world.interactables) {
    const items = new Map();
    G.world.interactables = {
      items,
      add(it) { items.set(it.id, it); return it; },
      remove(id) { items.delete(id); },
      nearest(pos, dir) {
        let best = null, bd = 1e9;
        for (const it of items.values()) {
          if (it.enabled && !it.enabled()) continue;
          const d = Math.hypot(it.pos.x - pos.x, it.pos.z - pos.z);
          if (d < (it.radius || 2) && d < bd) { best = it; bd = d; }
        }
        void dir;
        return best;
      },
    };
  }
  window.__used = 0;
  const y = G.world.collisions.supportY(-8, 16, 60);
  G.world.interactables.add({ id: 'ws6-test', label: 'Сесть на скамейку', pos: new THREE.Vector3(-8, y + 0.4, 16), radius: 2, use: () => { window.__used++; } });
});
await place(-8, 17.4, 0, 0);
await frames(3);
check('объект мира — цель с подписью (C7)', await ev(() => window.__cozy.game.topView.target?.type === 'it' && document.getElementById('tlabel').textContent.includes('скамейку')));
await page.keyboard.press('KeyE');
await frames(2);
check('E использует объект мира', await ev(() => window.__used === 1));
await ev(() => window.__cozy.game.world.interactables.remove('ws6-test'));

// крыши (C5): в беседке скрыта, снаружи — на месте
await place(26.2, -17.6, 0, 0.5);
await frames(10);
check('в беседке крыша скрыта (C5)', await ev(() => { const r = window.__cozy.game.topView.roofs || []; return r.length > 0 && r.some((x) => Math.abs((x.min.x + x.max.x) / 2 - 27) < 1.5 && x.k < 0.2); }));
await page.screenshot({ path: 'tests/out/topdown-gazebo.png', timeout: 180000 });
await place(10, 4, 0, 0);
await frames(14);
check('вне беседки крыша на месте', await ev(() => (window.__cozy.game.topView.roofs || []).every((x) => x.k > 0.95)));

// многоэтажка: стоим вплотную к фасаду, камера со стороны дома — она не внутри дома и персонаж виден
// (встаёт над домом и отворачивается к улице); крыша закрытого дома не прячется
const tall = await ev(() => {
  const G = window.__cozy.game, col = G.world.collisions, po = G.world.structures.portals.find((p) => p.id === 'apt-in');
  let b = null;
  for (const o of col.boxes) if (o.solid && o.y1 - o.y0 > 10 && Math.hypot(o.x - po.x, o.z - po.z) < 20 && (!b || o.hx * o.hz > b.hx * b.hz)) b = o;
  if (!b) return null;
  const dx = b.x - po.x, dz = b.z - po.z, l = Math.hypot(dx, dz);
  // шагаем от двери к дому, пока до стены не останется ~0.5 м
  let x = po.x, z = po.z;
  for (let t = 0; t < 8; t += 0.05) {
    const px = po.x + (dx / l) * t, pz = po.z + (dz / l) * t;
    const ox = px - b.x, oz = pz - b.z, ax = ox * b.c - oz * b.s, az = ox * b.s + oz * b.c;
    if (Math.hypot(Math.max(0, Math.abs(ax) - b.hx), Math.max(0, Math.abs(az) - b.hz)) < 0.5) break;
    x = px; z = pz;
  }
  return { x, z, cyaw: Math.atan2(dx, dz) };
});
if (tall) {
  await place(tall.x, tall.z, tall.cyaw + Math.PI, tall.cyaw);
  await frames(30);
  const st = await ev(() => {
    const G = window.__cozy.game, c = G.camera.position, p = G.player.pos;
    const inside = (x, y, z) => G.world.collisions.boxes.some((o) => {
      if (!o.solid || y < o.y0 || y > o.y1) return false;
      const ox = x - o.x, oz = z - o.z;
      return Math.abs(ox * o.c - oz * o.s) < o.hx && Math.abs(ox * o.s + oz * o.c) < o.hz;
    });
    let seen = true;
    for (let i = 1; i < 20; i++) {
      const t = i / 20;
      if (inside(p.x + (c.x - p.x) * t, p.y + 1.1 + (c.y - p.y - 1.1) * t, p.z + (c.z - p.z) * t)) seen = false;
    }
    // камера не жмётся к стене выше себя (тогда низ кадра смотрит в фасад изнутри)
    const hug = G.world.collisions.boxes.some((o) => {
      if (!o.solid || o.y1 < c.y) return false;
      const ox = c.x - o.x, oz = c.z - o.z, ax = ox * o.c - oz * o.s, az = ox * o.s + oz * o.c;
      return Math.hypot(Math.max(0, Math.abs(ax) - o.hx), Math.max(0, Math.abs(az) - o.hz)) < 1;
    });
    return { inCam: inside(c.x, c.y, c.z), hug, seen, cam: c.toArray().map((v) => +v.toFixed(1)) };
  });
  check('у фасада многоэтажки камера не в доме и не у стены, персонаж виден', !st.inCam && !st.hug && st.seen, JSON.stringify(st));
}

// обратно в 1-е лицо: руки, без растворения листвы, курсор захватывается
await page.keyboard.press('KeyC');
await frames(16);
await ev(() => { window.__handsDrawn = 0; });
await frames(3);
check('C: снова 1-е лицо, руки на месте, листва не растворяется', await ev(() => window.__cozy.game.player.cameraMode === 'fp' && window.__handsDrawn > 0 && window.__occ === false && Math.abs(window.__cozy.game.camera.fov - 78) < 0.5));

check('нет ошибок', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
const bad = results.filter((r) => !r).length;
console.log(bad ? `${bad} проверок не прошло` : 'вид сверху на ПК ок');
process.exit(bad ? 1 : 0);
