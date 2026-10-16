// Арена в каньоне: светлый зал с матами на полу (цветные зоны), мягкими стенами-панелями, флажками под
// потолком, табло, пустой стойкой под биты, лавками и конусами. Снаружи - полосы и большая вывеска.
// Потолок с фермами и светильниками - отдельный меш (C5). Отражённый свет в зале запечён в вершины.

import * as THREE from 'three';
import { heightAt } from '../terrain-data.js';
import { mulberry32, smoothstep, TAU } from '../../util/math.js';
import { SURF, PAT, glow, T, tube, sagPoints, slabGeo, groundAO } from './kit.js';
import { atlas } from './material.js';
import { plankBench } from './hub.js';

const V3 = (x, y, z) => new THREE.Vector3(x, y, z);

function boardTex() {
  return atlas().region(640, 256, (g, w, h) => {
    g.fillStyle = '#16181c'; g.fillRect(0, 0, w, h);
    g.strokeStyle = '#3a3e46'; g.lineWidth = 8; g.strokeRect(6, 6, w - 12, h - 12);
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.font = '800 42px "Baloo 2", "Trebuchet MS", system-ui, sans-serif';
    g.fillStyle = '#ff6a4a'; g.fillText('КРАСНЫЕ', w * 0.22, 54);
    g.fillStyle = '#5ab0ff'; g.fillText('СИНИЕ', w * 0.78, 54);
    // цифры «табло»: светящиеся сегменты
    g.font = '800 120px "Courier New", monospace';
    g.fillStyle = '#ffd24a';
    g.fillText('0', w * 0.22, 165); g.fillText('0', w * 0.78, 165);
    g.fillStyle = '#ffffff'; g.fillText(':', w / 2, 160);
    g.font = '700 30px "Baloo 2", "Trebuchet MS", system-ui, sans-serif';
    g.fillStyle = '#9aa0aa'; g.fillText('РАУНД 1', w / 2, 60);
  });
}

function bigSign(text) {
  return atlas().region(1024, 200, (g, w, h) => {
    g.fillStyle = '#ff8a3c'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#ffb36a'; g.fillRect(0, 0, w, 14); g.fillRect(0, h - 14, w, 14);
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.font = '800 140px "Baloo 2", "Trebuchet MS", system-ui, sans-serif';
    g.fillStyle = 'rgba(120,40,0,0.35)'; g.fillText(text, w / 2 + 5, h / 2 + 12);
    g.fillStyle = '#ffffff'; g.fillText(text, w / 2, h / 2 + 6);
  });
}

function atlasPlane(kit, r, w, h, xf, surf = SURF.label) {
  kit.add(new THREE.PlaneGeometry(w, h), '#ffffff', surf, xf, {
    shadow: false, bake: false,
    uv: (x, y) => { const u = x / w + 0.5, v = y / h + 0.5; return [r[0] + (r[2] - r[0]) * u, r[1] + (r[3] - r[1]) * v]; },
  });
}

export function buildArena(ctx) {
  const { kit, col } = ctx;
  const cx = -180, cz = 14;
  const y = heightAt(cx, cz);
  const W = 24, D = 24, H = 8, T0 = 1, DOOR = 6, DH = 6;
  const rand = mulberry32(180);
  const inX = W / 2 - T0, inZ = D / 2 - T0;
  // запечённый свет: в зале - отражённый от светлых стен и ламп (свечение) и затенение у стен и пола
  const bake = (wx, wy, wz, nx, ny, nz) => {
    const lx = wx - cx, lz = wz - cz, ly = wy - y;
    const inside = Math.abs(lx) < inX + 0.02 && Math.abs(lz) < inZ + 0.02 && (ly < H - 0.01 || (ny < -0.5 && ly < H + 0.05));
    if (!inside) {
      // снаружи: светлые стены подсвечены отражённым от песка светом
      const g = groundAO(wx, wy, wz, nx, ny, nz);
      return [g[0], ny > 0.5 ? 0 : 0.14];
    }
    const ax = inX - Math.abs(lx), az = inZ - Math.abs(lz);
    let k = 1;
    if (ny > 0.5 && ly < 0.4) k *= 0.72 + 0.28 * smoothstep(0, 1.2, Math.min(ax, az));
    else if (Math.abs(nx) > 0.5 || Math.abs(nz) > 0.5) k *= 0.72 + 0.28 * smoothstep(0, 0.9, ly);
    if (ny < -0.5) return [k * (0.85 + 0.15 * smoothstep(0, 2, Math.min(ax, az))), 0.85];
    const top = smoothstep(1, H, ly);
    // крыша закрывает солнце: светлые стены в зале держатся на «отражённом» свете
    return [k, 0.42 + 0.2 * top];
  };
  kit.group('arena', { cx, cz, r: 14, bake });
  kit.push(T(cx, y, cz));

  // ---------- пол: маты с цветными зонами ----------
  // зоны - отдельными плоскостями чуть выше основы (чёткие края; швы матов общие по координатам)
  const FY = 0.31;
  const mats = (geo, c, yy) => kit.add(geo, c, SURF.mats, T(0, FY + yy, 0), { uv: (x, y2, z) => [x, z], shadow: false });
  const flat = (g) => g.rotateX(-Math.PI / 2);
  mats(flat(new THREE.PlaneGeometry(W - T0 * 2, D - T0 * 2, kit.seg(4, 8, 12), kit.seg(4, 8, 12))), '#3f7fc8', 0);
  const bw = 1.3;
  for (const s2 of [-1, 1]) {
    mats(flat(new THREE.PlaneGeometry(inX * 2, bw)).translate(0, 0, s2 * (inZ - bw / 2)), '#d8503a', 0.008);
    mats(flat(new THREE.PlaneGeometry(bw, inZ * 2 - bw * 2)).translate(s2 * (inX - bw / 2), 0, 0), '#d8503a', 0.008);
    mats(flat(new THREE.PlaneGeometry(inX * 2 - bw * 2, 0.12)).translate(0, 0, s2 * (inZ - bw - 0.06)), '#f4f2ec', 0.01);
    mats(flat(new THREE.PlaneGeometry(0.12, inZ * 2 - bw * 2 - 0.24)).translate(s2 * (inX - bw - 0.06), 0, 0), '#f4f2ec', 0.01);
    mats(flat(new THREE.PlaneGeometry(0.12, inZ - bw - 0.12 - 4.0)).translate(0, 0, s2 * (4.0 + (inZ - bw - 0.12 - 4.0) / 2)), '#f4f2ec', 0.01);
  }
  mats(flat(new THREE.CircleGeometry(3.6, kit.seg(16, 32, 48))), '#f08a3c', 0.012);
  mats(flat(new THREE.RingGeometry(3.7, 3.88, kit.seg(16, 32, 48))), '#f4f2ec', 0.014);
  mats(flat(new THREE.CircleGeometry(0.35, kit.seg(8, 16, 24))), '#f4f2ec', 0.016);
  kit.box(W, 0.3, D, '#d8d8de', SURF.plain, T(0, 0.15, 0), { sharp: true, shadow: false });

  // ---------- стены ----------
  const wallC = '#f6f1e6';
  const wallBox = (x, z, w, h, d, y0 = 0) => {
    kit.box(w, h, d, wallC, SURF.paint, T(x, y0 + h / 2, z), { bevel: 0.03 });
    col.addBox(cx + x, cz + z, w / 2, d / 2, 0, y + y0, y + y0 + h);
  };
  wallBox(0, -D / 2 + T0 / 2, W, H, T0);
  wallBox(0, D / 2 - T0 / 2, W, H, T0);
  wallBox(-W / 2 + T0 / 2, 0, T0, H, D);
  const seg = (D - DOOR) / 2;
  wallBox(W / 2 - T0 / 2, -DOOR / 2 - seg / 2, T0, H, seg);
  wallBox(W / 2 - T0 / 2, DOOR / 2 + seg / 2, T0, H, seg);
  wallBox(W / 2 - T0 / 2, 0, T0, H - DH, DOOR, DH);
  col.addBox(cx, cz, W / 2, D / 2, 0, y - 2, y + 0.31, false);
  // снаружи: пилястры, карниз, высокие окна-ленты (изнутри светятся дневным светом)
  for (let i = 0; i <= 6; i++) {
    const t = -W / 2 + i * (W / 6);
    for (const s of [-1, 1]) kit.box(0.5, H, 0.24, '#e4e4ea', SURF.paint, T(t, H / 2, s * (D / 2 + 0.1)), { bevel: 0.03, shadow: false });
    kit.box(0.24, H, 0.5, '#e4e4ea', SURF.paint, T(-W / 2 - 0.1, H / 2, t), { bevel: 0.03, shadow: false });
    if (Math.abs(t) > DOOR / 2 + 0.3) kit.box(0.24, H, 0.5, '#e4e4ea', SURF.paint, T(W / 2 + 0.1, H / 2, t), { bevel: 0.03, shadow: false });
  }
  kit.box(W + 0.7, 0.32, D + 0.7, '#e4e4ea', SURF.paint, T(0, H - 0.16, 0), { bevel: 0.03, shadow: false });
  for (let i = 0; i < 6; i++) {
    const t = -W / 2 + (i + 0.5) * (W / 6);
    for (const s of [-1, 1]) {
      kit.add(new THREE.PlaneGeometry(3.0, 0.8), '#46607a', [0.06, 0, 0, PAT.glass], T(t, 5.4, s * (D / 2 + 0.012), 0, s > 0 ? 0 : Math.PI, 0), { shadow: false, bake: false, uv: (x, yy) => [x + t, yy] });
      kit.add(new THREE.PlaneGeometry(3.0, 0.8), '#cfe6ff', glow(1.1, PAT.glass), T(t, 5.4, s * (inZ - 0.012), 0, s > 0 ? Math.PI : 0, 0), { shadow: false, bake: false, uv: (x, yy) => [x + t, yy] });
      kit.box(3.1, 0.08, 0.06, '#9aa0aa', SURF.metal, T(t, 5.0, s * (D / 2 + 0.03)), { sharp: true, shadow: false });
      for (const e of [-1, 0, 1]) kit.box(0.06, 0.82, 0.06, '#9aa0aa', SURF.metal, T(t + e * 1.5, 5.4, s * (D / 2 + 0.03)), { sharp: true, shadow: false });
    }
  }
  // наружные полосы и цоколь
  for (const [yy, c, hh] of [[0.35, '#8e8e96', 0.7], [H - 0.9, '#ff8a3c', 0.45], [H - 1.45, '#3f7fc8', 0.2]]) {
    for (const s of [-1, 1]) kit.box(W + 0.06, hh, 0.04, c, SURF.paint, T(0, yy, s * (D / 2 + 0.02)), { sharp: true, shadow: false });
    kit.box(0.04, hh, D + 0.06, c, SURF.paint, T(-W / 2 - 0.02, yy, 0), { sharp: true, shadow: false });
    for (const s of [-1, 1]) kit.box(0.04, hh, seg, c, SURF.paint, T(W / 2 + 0.02, yy, s * (DOOR / 2 + seg / 2)), { sharp: true, shadow: false });
  }
  // мягкие панели по низу стен внутри (чередуются цвета) и цветная полоса выше
  const panel = (x, z, ry, w, c) => kit.box(w - 0.06, 1.7, 0.12, c, SURF.padded, T(x, 0.31 + 0.85, z, 0, ry, 0), { bevel: 0.05, uv: (px, py) => [px, py], shadow: false });
  const pcols = ['#bcd8f0', '#f4f6f8'];
  for (let i = 0; i < 11; i++) {
    const t = -inX + (i + 0.5) * (inX * 2 / 11);
    panel(t, -inZ + 0.06, 0, inX * 2 / 11, pcols[i % 2]);
    panel(t, inZ - 0.06, 0, inX * 2 / 11, pcols[(i + 1) % 2]);
    panel(-inX + 0.06, t, Math.PI / 2, inZ * 2 / 11, pcols[i % 2]);
    if (Math.abs(t) > DOOR / 2 + 0.4) panel(inX - 0.06, t, Math.PI / 2, inZ * 2 / 11, pcols[(i + 1) % 2]);
  }
  for (const s of [-1, 1]) kit.box(inX * 2, 0.2, 0.03, '#ff8a3c', SURF.paint, T(0, 2.6, s * (inZ - 0.015)), { sharp: true, shadow: false });
  kit.box(0.03, 0.2, inZ * 2, '#ff8a3c', SURF.paint, T(-inX + 0.015, 2.6, 0), { sharp: true, shadow: false });

  // ---------- табло на западной стене ----------
  {
    kit.push(T(-inX + 0.05, 5.0, 0, 0, Math.PI / 2, 0));
    kit.box(4.6, 2.0, 0.2, '#2a2c32', SURF.metal, T(0, 0, 0.1), { bevel: 0.04 });
    atlasPlane(kit, boardTex(), 4.3, 1.72, T(0, 0, 0.205), glow(1.6, PAT.label));
    for (const s of [-1, 1]) kit.box(0.08, 0.9, 0.08, '#3a3d42', SURF.metal, T(s * 1.8, 1.4, 0.05), { sharp: true });
    kit.pop();
  }
  // ---------- стойка под биты (пустая) и лавки ----------
  {
    kit.push(T(-6, 0.31, -inZ + 0.6));
    for (const s of [-1, 1]) kit.box(0.08, 1.05, 0.5, '#9a6a42', SURF.wood, T(s * 0.85, 0.52, 0, 0.0, 0, 0), { bevel: 0.015 });
    kit.box(1.8, 0.06, 0.5, '#8a5a36', SURF.wood, T(0, 0.05, 0), { bevel: 0.01 });
    kit.box(1.8, 0.08, 0.14, '#8a5a36', SURF.wood, T(0, 1.0, -0.12), { bevel: 0.01 });
    // прорези под биты
    for (let i = 0; i < 6; i++) kit.box(0.04, 0.09, 0.12, '#5a3a22', SURF.wood, T(-0.62 + i * 0.25, 1.0, -0.12), { sharp: true, shadow: false });
    kit.box(1.8, 0.04, 0.4, '#8a5a36', SURF.wood, T(0, 0.3, 0.02), { bevel: 0.008 });
    kit.pop();
    col.addBox(cx - 6, cz - inZ + 0.6, 0.92, 0.27, 0, y, y + 1.4);
  }
  kit.pop(); // лавки ставятся в мировых координатах
  for (const [bx, bz, ry] of [[-2, inZ - 0.7, Math.PI], [3, inZ - 0.7, Math.PI], [-inX + 0.8, -6, -Math.PI / 2], [-inX + 0.8, 6, -Math.PI / 2]]) {
    plankBench(ctx, cx + bx, cz + bz, ry, 1800 + Math.round(bx * 10 + bz), { len: 2.0, color: '#c08850', y0: y + 0.31 });
  }
  kit.group('arena');
  kit.push(T(cx, y, cz));
  // конусы и угловые флажки, стопка мягких блоков, кулер с водой
  for (let i = 0; i < 5; i++) {
    const px = -3 + i * 1.5, pz = -5.5;
    kit.add(new THREE.ConeGeometry(0.16, 0.45, kit.seg(6, 8, 12)), '#ff7a2a', SURF.plastic, T(px, 0.31 + 0.25, pz));
    kit.box(0.36, 0.04, 0.36, '#ff7a2a', SURF.plastic, T(px, 0.33, pz), { bevel: 0.01 });
    kit.cyl(0.1, 0.125, 0.07, '#ffffff', SURF.plastic, T(px, 0.31 + 0.22, pz), { seg: kit.seg(6, 8, 12), shadow: false });
    col.addCircle(cx + px, cz + pz, 0.16, y, y + 0.76);
  }
  for (const [sx, sz] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
    const px = sx * (inX - 1.7), pz = sz * (inZ - 1.7);
    kit.cyl(0.02, 0.02, 1.6, '#e8e8ea', SURF.plastic, T(px, 0.31 + 0.8, pz), { seg: 5 });
    kit.add(slabGeo([[0, 0], [0.45, 0.15], [0, 0.32]], 0.01, 0), sx * sz > 0 ? '#e8452e' : '#3f7fc8', SURF.fabric, T(px, 0.31 + 1.25, pz, 0, rand() * TAU, 0), { shadow: false });
  }
  {
    kit.push(T(inX - 2.2, 0.31, inZ - 2.0, 0, 0.4, 0));
    const pc = ['#e8452e', '#f2c84b', '#3f7fc8'];
    for (let i = 0; i < 3; i++) kit.box(1.2 - i * 0.1, 0.32, 0.8 - i * 0.05, pc[i], SURF.padded, T(0, 0.16 + i * 0.32, 0, 0, (rand() - 0.5) * 0.1, 0), { bevel: 0.05, uv: (px, py, pz) => [px, pz + py] });
    kit.pop();
    col.addBox(cx + inX - 2.2, cz + inZ - 2.0, 0.6, 0.4, 0.4, y, y + 1.27);
    // кулер
    kit.push(T(inX - 0.5, 0.31, DOOR / 2 + 1.0));
    kit.box(0.34, 1.0, 0.34, '#f0f0f2', SURF.plastic, T(0, 0.5, 0), { bevel: 0.03 });
    kit.cyl(0.14, 0.14, 0.4, '#7ab8e8', [0.1, 0, 0.05, PAT.glass], T(0, 1.22, 0), { seg: kit.seg(8, 10, 14) });
    kit.box(0.08, 0.06, 0.04, '#3f7fc8', SURF.plastic, T(-0.18, 0.8, 0.0), { sharp: true, shadow: false });
    kit.pop();
    col.addCircle(cx + inX - 0.5, cz + DOOR / 2 + 1.0, 0.22, y, y + 1.7);
  }

  // ---------- вывеска и фонари снаружи над входом ----------
  {
    kit.push(T(W / 2 + 0.06, DH + 1.0, 0, 0, Math.PI / 2, 0));
    kit.box(6.6, 1.5, 0.1, '#e8742a', SURF.paint, T(0, 0, -0.03), { bevel: 0.03 });
    atlasPlane(kit, bigSign('АРЕНА'), 6.4, 1.25, T(0, 0, 0.03), glow(0.45, PAT.label));
    kit.pop();
    for (const s of [-1, 1]) {
      kit.push(T(W / 2 + 0.05, DH - 0.4, s * (DOOR / 2 + 0.8)));
      kit.box(0.1, 0.3, 0.3, '#3a3d42', SURF.metal, T(0.05, 0, 0), { bevel: 0.01 });
      kit.box(0.4, 0.06, 0.06, '#3a3d42', SURF.metal, T(0.25, 0, 0), { sharp: true });
      kit.box(0.24, 0.16, 0.3, '#3a3d42', SURF.metal, T(0.5, -0.05, 0, 0, 0, 0.3), { bevel: 0.01 });
      kit.add(new THREE.PlaneGeometry(0.26, 0.2), '#fff2d0', glow(4.0), T(0.5, -0.14, 0, Math.PI / 2 - 0.3, 0, 0).multiply(T(0, 0, 0, 0, 0, 0)), { shadow: false, bake: false });
      kit.pop();
    }
    // коврик у входа
    kit.box(4.0, 0.03, 1.6, '#3a3d42', SURF.fabric, T(W / 2 + 0.9, 0.0, 0), { sharp: true, shadow: false });
  }

  // ---------- потолок (C5): плита, фермы, светильники, гирлянды флажков ----------
  kit.roof('arena', { x0: cx - inX, x1: cx + inX, z0: cz - inZ, z1: cz + inZ });
  kit.box(W + 0.4, 0.5, D + 0.4, '#e9eef4', SURF.paint, T(0, H + 0.25, 0), { bevel: 0.04 });
  kit.box(W + 0.6, 0.12, D + 0.6, '#ff8a3c', SURF.paint, T(0, H + 0.52, 0), { bevel: 0.02, shadow: false });
  // фермы поперёк зала
  for (let i = 0; i < 4; i++) {
    const tz = -inZ + (i + 0.5) * (inZ * 2 / 4);
    kit.box(inX * 2, 0.12, 0.12, '#dfe2e6', SURF.metal, T(0, H - 0.1, tz), { sharp: true });
    kit.box(inX * 2, 0.1, 0.1, '#dfe2e6', SURF.metal, T(0, H - 0.75, tz), { sharp: true, shadow: false });
    for (let k = 0; k < 12; k++) {
      const x = -inX + (k + 0.5) * (inX * 2 / 12);
      kit.box(0.06, 0.9, 0.06, '#dfe2e6', SURF.metal, T(x, H - 0.42, tz, 0, 0, k % 2 ? 0.6 : -0.6), { sharp: true, shadow: false });
    }
  }
  // светильники-панели (HDR)
  for (const px of [-7, 0, 7]) for (const pz of [-5.5, 5.5]) {
    kit.box(3.4, 0.08, 1.4, '#c8ccd2', SURF.metal, T(px, H - 0.04, pz), { sharp: true, shadow: false });
    kit.add(new THREE.PlaneGeometry(3.2, 1.2).rotateX(Math.PI / 2), '#fffaf0', glow(3.2), T(px, H - 0.085, pz), { shadow: false, bake: false });
  }
  // флажки на провисающих шнурах
  const flagC = ['#e8452e', '#f2c84b', '#3f7fc8', '#5fae5a', '#ffffff', '#ff8a3c'];
  let fi = 0;
  for (const [a, b] of [[V3(-inX, 6.6, -inZ), V3(inX, 6.6, inZ)], [V3(-inX, 6.6, inZ), V3(inX, 6.6, -inZ)], [V3(-inX, 6.9, 0), V3(inX, 6.9, 0)]]) {
    const pts = sagPoints(a, b, 0.9, 24);
    kit.add(tube(pts, 0.01, 3), '#4a4a50', SURF.plain, null, { shadow: false, bake: false });
    for (let k = 1; k < pts.length - 1; k++) {
      const p = pts[k], q = pts[k + 1];
      const ry = Math.atan2(-(q.z - p.z), q.x - p.x);
      kit.add(slabGeo([[-0.17, 0], [0.17, 0], [0, -0.38]], 0.008, 0), flagC[fi++ % flagC.length], SURF.fabric, T(p.x, p.y, p.z, 0, ry, 0), { shadow: false, bake: false });
    }
  }
  kit.pop();

  // дальний вид
  kit.setLayer('far');
  kit.push(T(cx, y, cz));
  kit.add(new THREE.BoxGeometry(W, H + 0.5, D), wallC, SURF.plain, T(0, (H + 0.5) / 2, 0), { bake: false });
  kit.add(new THREE.BoxGeometry(W + 0.1, 0.45, D + 0.1), '#ff8a3c', SURF.plain, T(0, H - 0.9, 0), { bake: false });
  kit.pop();
  ctx.exclude(cx, cz, W * 0.75);
}
