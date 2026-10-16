// Дождливый район: кирпичная многоэтажка (окна с рамами и подоконниками, тёплый свет в части окон, балконы,
// кондиционеры, водостоки, подъезд с козырьком и лампой, номер дома, урна, лавочка, кафе с маркизой) и
// квартира внутри (за картой, x >= 700): уютная комната с запечённым в вершины затенением и тёплым светом.

import * as THREE from 'three';
import { heightAt } from '../terrain-data.js';
import { mulberry32, hash2, smoothstep, TAU } from '../../util/math.js';
import { APARTMENT } from '../../../shared/layout.js';
import { SURF, PAT, glow, T, lathe, tube, sagPoints, slabGeo, groundAO } from './kit.js';
import { atlas } from './material.js';
import { plankBench, along } from './hub.js';
import { rainViewMaterial } from './fx.js';

const V3 = (x, y, z) => new THREE.Vector3(x, y, z);

// Картинка из атласа на плоскости w x h (нормаль +Z локально).
function atlasPlane(kit, r, w, h, xf, surf = SURF.label, opts = {}) {
  kit.add(new THREE.PlaneGeometry(w, h), '#ffffff', surf, xf, {
    shadow: false, bake: false, ...opts,
    uv: (x, y) => { const u = x / w + 0.5, v = y / h + 0.5; return [r[0] + (r[2] - r[0]) * u, r[1] + (r[3] - r[1]) * v]; },
  });
}

// Табличка/вывеска: фон, текст, рамка.
function signTex(text, w, h, { bg = '#2f5d9a', fg = '#ffffff', font, border = '#ffffff', sub = null } = {}) {
  return atlas().region(w, h, (g) => {
    g.fillStyle = bg;
    g.fillRect(0, 0, w, h);
    if (border) { g.strokeStyle = border; g.lineWidth = Math.max(3, h * 0.05); g.strokeRect(h * 0.08, h * 0.08, w - h * 0.16, h * 0.16 > 0 ? h - h * 0.16 : h); }
    g.fillStyle = fg;
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    let fs = Math.round(h * (sub ? 0.46 : 0.6));
    g.font = font || `800 ${fs}px "Baloo 2", "Trebuchet MS", system-ui, sans-serif`;
    const tw = g.measureText(text).width;
    if (!font && tw > w * 0.84) { fs = Math.floor(fs * w * 0.84 / tw); g.font = `800 ${fs}px "Baloo 2", "Trebuchet MS", system-ui, sans-serif`; }
    g.fillText(text, w / 2, sub ? h * 0.4 : h / 2 + h * 0.03);
    if (sub) {
      g.font = `700 ${Math.round(h * 0.2)}px "Baloo 2", "Trebuchet MS", system-ui, sans-serif`;
      g.fillText(sub, w / 2, h * 0.78);
    }
  });
}

// ---------- многоэтажка ----------

export function buildBlock(ctx) {
  const { kit, col } = ctx;
  const cx = 22, cz = 181, y = heightAt(cx, cz - 6);
  const rand = mulberry32(77);
  const W = 13, D = 12, G = 3.4, FH = 3.2, NF = 4; // ширина, глубина, первый этаж, этаж, этажей сверху
  const H = G + FH * NF; // 16.2
  const PAR = 0.8;
  kit.group('block', { cx, cz, r: 10, detailDist: 30 });
  kit.push(T(cx, y, cz));
  const brick = '#a95b43', band = '#d8cfbf', trim = '#f1ece2';
  const wallUV = (x, yy, z, nx, ny, nz) => (Math.abs(nz) > 0.5 ? [x, yy] : Math.abs(nx) > 0.5 ? [z, yy] : [x, z]);

  // стены, цоколь, пояса между этажами, карниз и парапет
  kit.add(new THREE.BoxGeometry(W, H + PAR, D), brick, SURF.brick, T(0, (H + PAR) / 2, 0), { uv: wallUV, bake: false });
  kit.box(W + 0.16, 0.75, D + 0.16, '#77736d', SURF.masonry, T(0, 0.3, 0), { bevel: 0.03, uv: wallUV, shadow: false });
  for (let f = 0; f <= NF; f++) {
    const fy = f === 0 ? G : G + FH * f;
    const big = f === NF;
    kit.box(W + (big ? 0.5 : 0.14), big ? 0.32 : 0.16, D + (big ? 0.5 : 0.14), band, SURF.paint, T(0, fy - (big ? 0.0 : 0.12), 0), { bevel: 0.02, uv: wallUV, shadow: false, bake: false });
  }
  kit.box(W + 0.3, 0.12, D + 0.3, band, SURF.paint, T(0, H + PAR + 0.04, 0), { bevel: 0.02, uv: wallUV, shadow: false, bake: false });

  // окна: стена (sx, sz) - нормаль, a - координата вдоль стены, fy - низ окна
  const lit = [];
  const curtains = ['#c4483a', '#3f6fb0', '#d9a03a', '#5a8a4a', '#9a5aa8', '#e8dcc8'];
  const win = (face, a, fy, w = 1.25, h = 1.65, door = false) => {
    // face: 0 - север (фасад, -z), 1 - юг, 2 - восток (+x), 3 - запад
    const nrm = [[0, -1], [0, 1], [1, 0], [-1, 0]][face];
    const half = face < 2 ? D / 2 : W / 2;
    const px = face < 2 ? a : nrm[0] * half, pz = face < 2 ? nrm[1] * half : a;
    const ry = Math.atan2(nrm[0], nrm[1]);
    kit.push(T(px, fy + h / 2, pz, 0, ry, 0)); // локально: +z наружу, x вдоль стены
    const on = rand() < 0.48;
    if (on) {
      const cc = curtains[Math.floor(rand() * curtains.length)];
      kit.add(new THREE.PlaneGeometry(w, h), '#ffc77a', glow(1.6 + rand() * 1.2), T(0, 0, 0.012), { shadow: false, bake: false, emis: (x, yy) => 0.5 * (1 - Math.min(1, Math.hypot(x / w, (yy + h * 0.1) / h) * 1.5)) });
      const pat = rand() < 0.5 ? PAT.gingham : PAT.fabric;
      for (const s of [-1, 1]) if (rand() < 0.85) kit.add(new THREE.PlaneGeometry(w * 0.28, h * 0.95), cc, glow(0.9, pat), T(s * w * 0.36, 0.0, 0.016), { shadow: false, bake: false, uv: (x, yy) => [x, yy] });
      lit.push([px, fy + h / 2, pz, ry, w, h]);
    } else {
      kit.add(new THREE.PlaneGeometry(w, h), '#556677', [0.06, 0, 0, PAT.glass], T(0, 0, 0.012), { shadow: false, bake: false, uv: (x, yy) => [x + a * 0.37, yy + fy * 0.29] });
      if (rand() < 0.4) kit.add(new THREE.PlaneGeometry(w * 0.3, h * 0.95), '#7a6a5a', SURF.fabric, T((rand() < 0.5 ? -1 : 1) * w * 0.35, 0, 0.016), { shadow: false, bake: false });
    }
    // рама, импост, форточка
    const fc = trim;
    kit.box(w + 0.12, 0.07, 0.1, fc, SURF.paint, T(0, h / 2 + 0.03, 0.04), { sharp: true, shadow: false, bake: false });
    kit.box(w + 0.12, 0.07, 0.1, fc, SURF.paint, T(0, -h / 2 - 0.03, 0.04), { sharp: true, shadow: false, bake: false });
    for (const s of [-1, 1]) kit.box(0.07, h, 0.1, fc, SURF.paint, T(s * (w / 2 + 0.03), 0, 0.04), { sharp: true, shadow: false, bake: false });
    kit.box(0.06, h, 0.06, fc, SURF.paint, T(door ? 0 : w * 0.12, 0, 0.03), { sharp: true, shadow: false, bake: false });
    if (!door) kit.box(w * 0.62, 0.05, 0.06, fc, SURF.paint, T(-w * 0.19, h * 0.22, 0.03), { sharp: true, shadow: false, bake: false });
    // подоконник и перемычка
    if (!door) kit.box(w + 0.3, 0.07, 0.24, band, SURF.paint, T(0, -h / 2 - 0.1, 0.1), { bevel: kit.q > 1 ? 0.015 : 0, shadow: false, bake: false });
    kit.box(w + 0.36, 0.2, 0.06, '#8e4a36', SURF.brick, T(0, h / 2 + 0.17, 0.03), { sharp: true, shadow: false, bake: false, uv: (x, yy) => [x, yy] });
    // цветы на подоконнике (снаружи)
    if (!door && kit.q > 0 && rand() < 0.22) {
      kit.setLayer('detail');
      kit.box(w * 0.8, 0.16, 0.18, '#7a5236', SURF.planks, T(0, -h / 2 + 0.02, 0.18), { bevel: 0.01 });
      for (let i = 0; i < 5; i++) kit.sphere(0.07 + rand() * 0.03, i % 2 ? '#4f8a3a' : ['#f07a8a', '#f4d24a', '#ffffff'][i % 3], SURF.fabric, T(-w * 0.32 + i * w * 0.16, -h / 2 + 0.14, 0.18), { seg: 5 });
      kit.setLayer('solid');
    }
    kit.pop();
  };
  const cols = [-4.6, -1.55, 1.55, 4.6];
  const sideCols = [-4.0, -1.35, 1.35, 4.0];
  // балконы - по краям фасада; над кафе на нижнем этаже вместо балкона окно: под ним вывеска «Кафе»
  const hasBalcony = (f, a) => Math.abs(a) > 4 && !(f === 0 && a < 0);
  for (let f = 0; f < NF; f++) {
    const fy = G + FH * f + 0.85;
    for (const a of cols) {
      const balcony = hasBalcony(f, a);
      win(0, a, balcony ? fy - 0.62 : fy, balcony ? 0.95 : 1.25, balcony ? 2.25 : 1.65, balcony);
      win(1, a, fy);
    }
    for (const a of sideCols) { win(2, a, fy); win(3, a, fy); }
  }
  // первый этаж: окна по бокам и сзади, справа от подъезда - окно, слева - витрина кафе
  for (const a of cols) win(1, a, 1.1);
  for (const a of sideCols) { win(2, a, 1.1); if (a > -3) win(3, a, 1.1); }
  win(0, 4.6, 1.1);

  // балконы на фасаде
  for (let f = 0; f < NF; f++) {
    const fy = G + FH * f + 0.2;
    for (const s of [-1, 1]) {
      const bx = s * 4.6;
      if (!hasBalcony(f, bx)) continue;
      kit.push(T(bx, fy, -D / 2));
      kit.box(1.9, 0.14, 1.0, '#bdb6aa', SURF.plain, T(0, 0, -0.5), { bevel: 0.02 });
      // ограждение: стойки, поручень, низ
      const rail = '#3a3d42';
      kit.box(1.86, 0.05, 0.05, rail, SURF.iron, T(0, 1.0, -0.98), { sharp: true, shadow: false });
      kit.box(1.86, 0.04, 0.04, rail, SURF.iron, T(0, 0.12, -0.98), { sharp: true, shadow: false });
      for (const e of [-1, 1]) {
        kit.box(0.05, 0.05, 0.96, rail, SURF.iron, T(e * 0.93, 1.0, -0.5), { sharp: true, shadow: false });
        kit.box(0.04, 0.04, 0.96, rail, SURF.iron, T(e * 0.93, 0.12, -0.5), { sharp: true, shadow: false });
      }
      kit.setLayer('detail');
      for (let i = 0; i <= 12; i++) kit.box(0.02, 0.88, 0.02, rail, SURF.iron, T(-0.9 + i * 0.15, 0.56, -0.98), { sharp: true });
      for (let i = 1; i < 6; i++) for (const e of [-1, 1]) kit.box(0.02, 0.88, 0.02, rail, SURF.iron, T(e * 0.93, 0.56, -0.98 + i * 0.16), { sharp: true });
      // ящик с цветами на перилах, иногда стул
      if (rand() < 0.7) {
        kit.box(0.9, 0.18, 0.2, '#9a5a3a', SURF.plain, T(0, 1.12, -1.02), { bevel: 0.015 });
        for (let i = 0; i < 6; i++) kit.sphere(0.07 + rand() * 0.03, i % 2 ? '#4f8a3a' : ['#f07a8a', '#f4d24a', '#ffffff', '#c77be0'][i % 4], SURF.fabric, T(-0.36 + i * 0.145, 1.25, -1.02), { seg: 5 });
      }
      if (rand() < 0.5) {
        kit.box(0.4, 0.04, 0.4, '#2f6a8a', SURF.paint, T(0.4, 0.45, -0.45), { bevel: 0.01 });
        kit.box(0.4, 0.45, 0.04, '#2f6a8a', SURF.paint, T(0.4, 0.68, -0.27), { bevel: 0.01 });
        for (const e of [-1, 1]) for (const e2 of [-1, 1]) kit.box(0.025, 0.45, 0.025, '#2a2a2c', SURF.metal, T(0.4 + e * 0.17, 0.22, -0.45 + e2 * 0.17), { sharp: true });
      }
      kit.setLayer('solid');
      kit.pop();
    }
  }

  // кондиционеры на боковых стенах
  for (const [face, a, f] of [[2, -4.0, 0], [2, 1.35, 2], [3, 4.0, 1], [3, -1.35, 3], [1, 1.55, 1], [2, 4.0, 3]]) {
    const nrm = [[0, -1], [0, 1], [1, 0], [-1, 0]][face];
    const half = face < 2 ? D / 2 : W / 2;
    const px = face < 2 ? a : nrm[0] * half, pz = face < 2 ? nrm[1] * half : a;
    const ry = Math.atan2(nrm[0], nrm[1]);
    kit.push(T(px, G + FH * f + 0.35, pz, 0, ry, 0));
    kit.box(0.8, 0.52, 0.32, '#e6e3dc', SURF.plastic, T(0.15, 0, 0.2), { bevel: 0.025 });
    kit.setLayer('detail');
    kit.add(new THREE.CircleGeometry(0.19, kit.seg(8, 10, 14)), '#3a3d42', SURF.metal, T(0.25, 0, 0.362), { shadow: false });
    for (let i = 0; i < 4; i++) kit.box(0.4, 0.012, 0.012, '#d0ccc4', SURF.plastic, T(0.25, -0.15 + i * 0.1, 0.37), { sharp: true });
    kit.add(tube([V3(-0.2, -0.1, 0.1), V3(-0.3, -0.1, 0.06), V3(-0.3, -0.9, 0.06)], 0.015, 4), '#e0ddd6', SURF.plastic, null);
    kit.box(0.04, 0.04, 0.34, '#55595e', SURF.metal, T(-0.15, -0.28, 0.17), { sharp: true });
    kit.box(0.04, 0.04, 0.34, '#55595e', SURF.metal, T(0.5, -0.28, 0.17), { sharp: true });
    kit.setLayer('solid');
    kit.pop();
  }
  // водосточные трубы по углам фасада
  for (const [sx, sz] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
    const px = sx * (W / 2 + 0.12), pz = sz * (D / 2 - 0.25);
    kit.cyl(0.075, 0.075, H + PAR - 0.6, '#8e9499', SURF.metal, T(px, (H + PAR - 0.6) / 2 + 0.3, pz), { seg: kit.seg(6, 8, 10) });
    kit.cyl(0.16, 0.08, 0.3, '#8e9499', SURF.metal, T(px, H + PAR - 0.2, pz), { seg: kit.seg(6, 8, 10) });
    kit.add(tube([V3(px, 0.35, pz), V3(px, 0.2, pz), V3(px + sx * 0.25, 0.08, pz)], 0.075, kit.seg(5, 6, 8)), '#8e9499', SURF.metal, null);
    if (kit.q > 0) for (let k = 1; k < 5; k++) kit.box(0.04, 0.04, 0.2, '#6e7479', SURF.metal, T(px - sx * 0.08, k * 3.3, pz), { sharp: true, layer: 'detail' });
  }

  // ---------- подъезд ----------
  {
    kit.push(T(0, 0, -D / 2));
    // ступени
    kit.box(3.6, 0.16, 1.4, '#9a958c', SURF.stone, T(0, 0.08, -0.7), { bevel: 0.02 });
    kit.box(3.0, 0.16, 0.9, '#a39e95', SURF.stone, T(0, 0.24, -0.45), { bevel: 0.02 });
    // ниша и двустворчатая дверь со стеклом
    kit.box(2.5, 2.9, 0.05, '#3a2a22', SURF.plain, T(0, 0.32 + 1.45, 0.0), { sharp: true, shadow: false });
    for (const s of [-1, 1]) {
      kit.box(1.02, 2.5, 0.07, '#5a3a28', SURF.lacq, T(s * 0.53, 0.32 + 1.25, -0.04), { bevel: 0.015, shadow: false });
      kit.add(new THREE.PlaneGeometry(0.66, 1.4), '#ffd59a', glow(1.6, PAT.glass), T(s * 0.53, 0.32 + 1.55, -0.08), { shadow: false, bake: false, uv: (x, yy) => [x * 2, yy] });
      kit.box(0.04, 0.4, 0.04, '#c8a040', SURF.metal, T(s * 0.12, 0.32 + 1.15, -0.12), { sharp: true, shadow: false });
    }
    kit.box(2.3, 0.45, 0.05, '#ffd59a', glow(1.3, PAT.glass), T(0, 0.32 + 2.75, -0.03), { sharp: true, shadow: false, bake: false });
    for (const s of [-1, 1]) kit.box(0.14, 3.2, 0.16, trim, SURF.paint, T(s * 1.27, 0.32 + 1.6, -0.06), { bevel: 0.015, shadow: false });
    kit.box(2.7, 0.16, 0.18, trim, SURF.paint, T(0, 0.32 + 3.1, -0.07), { bevel: 0.015, shadow: false });
    // номер дома и табличка с улицей
    // (лицом наружу: фасад смотрит в -z)
    const num = signTex('12', 180, 180, { bg: '#2f5d9a', border: '#ffffff' });
    kit.box(0.46, 0.46, 0.02, '#ffffff', SURF.paint, T(1.78, 2.45, -0.01), { sharp: true, shadow: false });
    atlasPlane(kit, num, 0.42, 0.42, T(1.78, 2.45, -0.022, 0, Math.PI, 0));
    const street = signTex('ул. Дождливая', 520, 120, { bg: '#2f5d9a', border: '#ffffff' });
    kit.box(0.94, 0.24, 0.02, '#ffffff', SURF.paint, T(-1.87, 2.3, -0.01), { sharp: true, shadow: false });
    atlasPlane(kit, street, 0.9, 0.2, T(-1.87, 2.3, -0.022, 0, Math.PI, 0));
    // домофон
    kit.box(0.18, 0.3, 0.05, '#2a2c30', SURF.metal, T(1.55, 1.45, -0.03), { bevel: 0.01, shadow: false });
    kit.setLayer('detail');
    for (let i = 0; i < 6; i++) kit.box(0.035, 0.025, 0.012, '#d8d4c8', SURF.metal, T(1.51 + (i % 2) * 0.08, 1.38 + Math.floor(i / 2) * 0.05, -0.06), { sharp: true });
    kit.box(0.1, 0.05, 0.01, '#6ad0ff', glow(1.6), T(1.55, 1.55, -0.058), { sharp: true });
    kit.setLayer('solid');
    // козырёк на тягах (C5 - отдельный меш) с лампой
    kit.pop();
    kit.roof('block-canopy', { x0: cx - 1.9, x1: cx + 1.9, z0: cz - D / 2 - 1.7, z1: cz - D / 2 });
    kit.push(T(0, 0, -D / 2));
    kit.box(3.8, 0.16, 1.7, '#5d6670', SURF.metal, T(0, 3.72, -0.85), { bevel: 0.02 });
    kit.box(3.84, 0.24, 0.05, '#3f454c', SURF.metal, T(0, 3.68, -1.72), { sharp: true });
    for (const s of [-1, 1]) kit.add(tube([V3(s * 1.7, 3.78, -1.62), V3(s * 1.7, 5.0, -0.02)], 0.022, 4), '#3f454c', SURF.metal, null);
    // лампа под козырьком
    kit.box(0.4, 0.06, 0.4, '#3f454c', SURF.metal, T(0, 3.6, -0.75), { sharp: true });
    kit.add(new THREE.SphereGeometry(0.16, kit.seg(6, 8, 10), kit.seg(4, 5, 6), 0, TAU, Math.PI / 2, Math.PI / 2), '#ffe0a8', glow(5.0), T(0, 3.58, -0.75), { shadow: false, bake: false });
    kit.pop();
    kit.setLayer('solid');
    col.addBox(cx, cz - D / 2 - 0.7, 1.8, 0.7, 0, y - 0.5, y + 0.16);
    col.addBox(cx, cz - D / 2 - 0.45, 1.5, 0.45, 0, y - 0.5, y + 0.32);
  }
  // кафе слева от подъезда: витрина, маркиза в полоску, светящаяся вывеска, столик со стульями
  {
    kit.push(T(-4.2, 0, -D / 2));
    // «зал» за витриной: тёплая стена с градиентом, полка с банками, стойка, подвесные лампы
    kit.add(new THREE.PlaneGeometry(3.4, 2.0, 1, 4), (x, yy, z, nx, ny, nz, out) => out.set('#ffc684').lerp(new THREE.Color('#d8875a'), smoothstep(0.3, -1.0, yy) * 0.6), glow(1.15), T(0, 1.55, -0.02, 0, Math.PI, 0), { shadow: false, bake: false, emis: (x, yy) => 0.35 * (1 - Math.min(1, Math.hypot(x / 3.4, (yy - 0.3) / 2.0) * 1.6)) });
    kit.box(3.2, 0.05, 0.08, '#5a3a28', SURF.wood, T(0, 2.0, -0.04), { sharp: true, shadow: false, bake: false });
    for (let i = 0; i < 9; i++) kit.box(0.14, 0.16 + rand() * 0.1, 0.06, ['#c4483a', '#3f6fb0', '#f4d24a', '#ffffff', '#5a8a4a'][i % 5], glow(0.5), T(-1.45 + i * 0.36, 2.11, -0.05), { sharp: true, shadow: false, bake: false });
    kit.box(2.4, 0.85, 0.08, '#4a2d1c', SURF.lacq, T(0.4, 0.98, -0.05), { sharp: true, shadow: false, bake: false });
    kit.box(2.5, 0.05, 0.1, '#c8a070', SURF.wood, T(0.4, 1.42, -0.06), { sharp: true, shadow: false, bake: false });
    kit.box(0.34, 0.42, 0.06, '#9aa0aa', SURF.metal, T(1.1, 1.66, -0.07), { sharp: true, shadow: false, bake: false });
    for (let i = 0; i < 3; i++) {
      const lx2 = -1.1 + i * 1.1;
      kit.box(0.012, 0.42, 0.012, '#2a2a2c', SURF.plain, T(lx2, 2.33, -0.06), { sharp: true, shadow: false, bake: false });
      kit.add(new THREE.SphereGeometry(0.09, 7, 5, 0, TAU, 0, Math.PI / 2), '#2f6a5a', SURF.paint, T(lx2, 2.1, -0.08), { shadow: false, bake: false });
      kit.add(new THREE.CircleGeometry(0.08, 8).rotateX(Math.PI / 2), '#fff0c8', glow(5), T(lx2, 2.095, -0.08), { shadow: false, bake: false });
    }
    // в глубине: меловая доска с меню над стойкой, кофемашина с огоньком, торт под колпаком,
    // силуэты столика и стульев на фоне тёплой стены
    const board = signTex('Меню', 300, 186, { bg: '#26302c', fg: '#f4f0e0', border: '#c8a070', sub: 'кофе • пирог' });
    kit.box(0.82, 0.54, 0.03, '#6a4a30', SURF.wood, T(-0.75, 1.72, -0.04), { sharp: true, shadow: false, bake: false });
    atlasPlane(kit, board, 0.74, 0.46, T(-0.75, 1.72, -0.056, 0, Math.PI, 0), [0.8, 0, 0.2, PAT.label]);
    kit.add(new THREE.SphereGeometry(0.012, 5, 4), '#ff4030', glow(4), T(1.0, 1.58, -0.11), { shadow: false, bake: false });
    kit.add(lathe([[0, 0], [0.16, 0], [0.17, 0.02], [0.03, 0.03], [0.03, 0.05], [0.13, 0.06], [0, 0.06]], 10), '#e8e2d4', SURF.ceramic, T(0.0, 1.45, -0.05), { uv: (x, yy) => [yy, x], shadow: false, bake: false });
    kit.add(new THREE.CylinderGeometry(0.11, 0.11, 0.1, 10), '#f2d0d8', SURF.fabric, T(0.0, 1.56, -0.05), { shadow: false, bake: false });
    kit.add(new THREE.CylinderGeometry(0.1, 0.1, 0.02, 10), '#fff4e0', SURF.fabric, T(0.0, 1.62, -0.05), { shadow: false, bake: false });
    for (let i = 0; i < 5; i++) kit.sphere(0.018, '#d8303a', SURF.gloss, T(Math.cos(i * 1.26) * 0.06, 1.645, -0.05 + Math.sin(i * 1.26) * 0.06), { seg: 5, shadow: false, bake: false });
    {
      const sil = '#5a3826';
      kit.cyl(0.3, 0.3, 0.03, sil, SURF.wood, T(-1.15, 0.78, -0.12), { seg: 10, shadow: false, bake: false });
      kit.cyl(0.03, 0.03, 0.74, sil, SURF.wood, T(-1.15, 0.41, -0.12), { seg: 5, shadow: false, bake: false });
      for (const s of [-1, 1]) {
        kit.box(0.34, 0.03, 0.06, sil, SURF.wood, T(-1.15 + s * 0.5, 0.48, -0.1), { sharp: true, shadow: false, bake: false });
        kit.box(0.03, 0.42, 0.04, sil, SURF.wood, T(-1.15 + s * 0.66, 0.7, -0.1), { sharp: true, shadow: false, bake: false });
        kit.box(0.03, 0.46, 0.04, sil, SURF.wood, T(-1.15 + s * 0.36, 0.25, -0.1), { sharp: true, shadow: false, bake: false });
      }
    }
    // доска-меню у входа
    kit.push(T(1.6, 0, -0.95, 0, 0.5, 0));
    const menu = signTex('Кофе • Какао', 300, 200, { bg: '#26302c', fg: '#f4f0e0', border: '#c8a070', sub: 'пирог дня' });
    for (const sgn of [-1, 1]) {
      kit.box(0.6, 0.8, 0.03, '#7a5236', SURF.wood, T(0, 0.42, sgn * 0.14, sgn * 0.18, 0, 0), { bevel: 0.01, shadow: false });
      atlasPlane(kit, menu, 0.5, 0.66, T(0, 0.43, sgn * 0.158, sgn * 0.18, sgn > 0 ? 0 : Math.PI, 0));
    }
    kit.pop();
    kit.box(3.6, 0.1, 0.16, '#4a2d1c', SURF.lacq, T(0, 0.52, -0.06), { sharp: true, shadow: false });
    kit.box(3.6, 0.1, 0.16, '#4a2d1c', SURF.lacq, T(0, 2.58, -0.06), { sharp: true, shadow: false });
    for (const s of [-1, 0, 1]) kit.box(0.1, 2.06, 0.14, '#4a2d1c', SURF.lacq, T(s * 1.75, 1.55, -0.06), { sharp: true, shadow: false });
    // маркиза: наклонная полоса с волнистым краем
    const aw = new THREE.PlaneGeometry(3.9, 1.3, 1, 1);
    const awM = T(0, 2.87, -0.6, 0.95, 0, 0);
    kit.add(aw, '#c23b32', SURF.stripes, awM, { shadow: new THREE.BoxGeometry(3.9, 1.3, 0.04), uv: (x, yy) => [x, yy] });
    kit.add(aw, '#c23b32', SURF.stripes, awM.clone().multiply(T(0, 0, 0, 0, Math.PI, 0)), { shadow: false, uv: (x, yy) => [-x, yy] });
    for (let i = 0; i < 12; i++) {
      kit.add(slabGeo([[-0.163, 0], [0.163, 0], [0, -0.16]], 0.01, 0), i % 2 ? '#f5f0e6' : '#c23b32', SURF.fabric, T(-1.79 + i * 0.326, 2.49, -1.13), { shadow: false });
    }
    // вывеска «Кафе» светится
    const cafe = signTex('Кафе', 360, 120, { bg: '#3a2a22', fg: '#ffd27a', border: '#ffd27a' });
    kit.box(1.42, 0.52, 0.06, '#3a2a22', SURF.plain, T(0, 3.76, -0.0), { sharp: true, shadow: false });
    atlasPlane(kit, cafe, 1.3, 0.43, T(0, 3.76, -0.035, 0, Math.PI, 0), glow(2.2, PAT.label));
    kit.pop();
    col.addCircle(cx - 4.2 + 1.6, cz - D / 2 - 0.95, 0.3, y, y + 0.85);
    // столик и стулья под маркизой
    const tx = cx - 4.2, tz = cz - D / 2 - 1.1, ty = heightAt(tx, tz);
    kit.push(T(-4.2, 0, -D / 2 - 1.1));
    kit.cyl(0.35, 0.35, 0.03, '#e6e1d6', SURF.metal, T(0, 0.74, 0), { seg: kit.seg(8, 12, 16) });
    kit.cyl(0.03, 0.03, 0.72, '#2a2a2c', SURF.metal, T(0, 0.37, 0), { seg: 6 });
    kit.cyl(0.2, 0.22, 0.03, '#2a2a2c', SURF.metal, T(0, 0.02, 0), { seg: 8 });
    kit.setLayer('detail');
    kit.add(lathe([[0, 0], [0.04, 0], [0.045, 0.08], [0.04, 0.09], [0, 0.09]], 8), '#ffffff', SURF.ceramic, T(0.1, 0.755, 0.05), { uv: (x, yy) => [yy, x] });
    kit.setLayer('solid');
    for (const s of [-1, 1]) {
      kit.push(T(s * 0.62, 0, 0, 0, s * Math.PI / 2, 0));
      kit.box(0.4, 0.04, 0.4, '#2f6a8a', SURF.paint, T(0, 0.45, 0), { bevel: 0.01 });
      kit.box(0.4, 0.42, 0.04, '#2f6a8a', SURF.paint, T(0, 0.68, 0.19), { bevel: 0.01 });
      for (const e of [-1, 1]) for (const e2 of [-1, 1]) kit.box(0.025, 0.45, 0.025, '#2a2a2c', SURF.metal, T(e * 0.17, 0.22, e2 * 0.17), { sharp: true, shadow: false });
      kit.pop();
    }
    kit.pop();
    col.addCircle(tx, tz, 0.36, ty, ty + 0.76);
  }
  // крыша (C5): плоская кровля внутри парапета
  kit.roof('block', { x0: cx - W / 2, x1: cx + W / 2, z0: cz - D / 2, z1: cz + D / 2 });
  kit.add(new THREE.PlaneGeometry(W - 0.5, D - 0.5).rotateX(-Math.PI / 2), '#55575a', SURF.soil, T(0, H + 0.04 + PAR, 0), { bake: false, uv: (x, yy, z) => [x, z] });
  kit.setLayer('solid');
  // на крыше: выход на кровлю, бак на опорах, вентиляция, антенны, тарелка
  {
    const ry0 = H + PAR + 0.04;
    kit.box(2.6, 2.4, 2.8, brick, SURF.brick, T(-3.2, ry0 + 1.2, 2.0), { bevel: 0.02, uv: wallUV, bake: false });
    kit.box(2.8, 0.14, 3.0, band, SURF.paint, T(-3.2, ry0 + 2.45, 2.0), { bevel: 0.02, bake: false });
    kit.box(0.9, 1.9, 0.06, '#5d6670', SURF.metal, T(-3.2, ry0 + 0.95, 0.58), { bevel: 0.01, bake: false });
    kit.cyl(0.95, 0.95, 1.6, '#8a8f94', SURF.metal, T(3.2, ry0 + 2.1, 2.6), { seg: kit.seg(8, 12, 16), bake: false });
    kit.add(new THREE.ConeGeometry(1.0, 0.35, kit.seg(8, 12, 16)), '#6e7378', SURF.metal, T(3.2, ry0 + 3.07, 2.6), { bake: false });
    for (const [e, e2] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) kit.box(0.08, 1.3, 0.08, '#3f454c', SURF.metal, T(3.2 + e * 0.6, ry0 + 0.65, 2.6 + e2 * 0.6), { sharp: true, bake: false });
    kit.box(1.0, 0.8, 0.7, '#9a9da0', SURF.metal, T(2.5, ry0 + 0.4, -3.0), { bevel: 0.02, bake: false });
    kit.box(0.7, 0.6, 0.7, '#9a9da0', SURF.metal, T(-1.0, ry0 + 0.3, -3.6), { bevel: 0.02, bake: false });
    kit.setLayer('detail');
    kit.cyl(0.03, 0.04, 3.2, '#3f454c', SURF.metal, T(4.8, ry0 + 1.6, -4.4), { seg: 5, bake: false });
    for (let k = 0; k < 4; k++) kit.box(0.9 - k * 0.15, 0.02, 0.02, '#3f454c', SURF.metal, T(4.8, ry0 + 2.0 + k * 0.35, -4.4), { sharp: true, bake: false });
    kit.add(lathe([[0, 0], [0.3, 0.06], [0.45, 0.16], [0.47, 0.18], [0, 0.06]], 10), '#e6e3dc', SURF.plastic, T(-5.4, ry0 + 1.0, -4.6, 1.0, 0.6, 0), { bake: false });
    kit.cyl(0.03, 0.03, 1.0, '#3f454c', SURF.metal, T(-5.4, ry0 + 0.5, -4.6), { seg: 5, bake: false });
    kit.setLayer('solid');
  }
  kit.pop();

  // урна и лавочка у подъезда, фонарь
  {
    const ux = cx + 2.3, uz = cz - D / 2 - 1.5, uy = heightAt(ux, uz);
    kit.add(lathe([[0, 0], [0.2, 0], [0.24, 0.1], [0.24, 0.7], [0.26, 0.72], [0.26, 0.76], [0, 0.76]], kit.seg(8, 10, 14)), '#5b6b5e', SURF.metal, T(ux, uy, uz), { uv: (x, yy) => [yy, x] });
    kit.add(new THREE.CircleGeometry(0.2, kit.seg(8, 10, 14)).rotateX(-Math.PI / 2), '#1a1a1c', SURF.plain, T(ux, uy + 0.765, uz), { shadow: false, layer: 'detail' });
    col.addCircle(ux, uz, 0.26, uy, uy + 0.76);
    plankBench(ctx, cx + 3.9, cz - D / 2 - 1.3, 0, 1777, { color: '#7a5a3e' });
    kit.group('block');
    ctx.exclude(cx, cz - D / 2 - 1.5, 4.5);
  }

  // дальний вид: коробка, кровля и светящиеся окна
  kit.setLayer('far');
  kit.push(T(cx, y, cz));
  kit.add(new THREE.BoxGeometry(W, H + PAR, D), brick, SURF.plain, T(0, (H + PAR) / 2, 0), { bake: false });
  for (const [px, py, pz, ry, w, h] of lit) kit.add(new THREE.PlaneGeometry(w, h), '#ffc77a', glow(2.0), T(px, py, pz, 0, ry, 0).multiply(T(0, 0, 0.03)), { bake: false });
  kit.pop();

  col.addBox(cx, cz, W / 2 + 0.1, D / 2 + 0.1, 0, y - 1, y + H + PAR);
  ctx.exclude(cx, cz, 8.5);
  const doorZ = cz - D / 2;
  ctx.portals.push({ id: 'apt-in', x: cx, y, z: doorZ - 1.3, r: 2.0, label: 'Войти в квартиру', to: { x: APARTMENT.x, y: 0, z: APARTMENT.d / 2 - 1.2, yaw: 0 } });
  return { exit: { x: cx, y, z: doorZ - 3.2, yaw: 0 } };
}

// ---------- квартира ----------

// Ковёр, картины - рисунки в атлас.
function rugTex() {
  return atlas().region(512, 400, (g, w, h) => {
    g.fillStyle = '#b8483a'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#e8d2a8'; g.fillRect(18, 18, w - 36, h - 36);
    g.fillStyle = '#2f5d7a'; g.fillRect(30, 30, w - 60, h - 60);
    g.fillStyle = '#b8483a'; g.fillRect(44, 44, w - 88, h - 88);
    // ромбы-медальоны
    const cx = w / 2, cy = h / 2;
    for (const [s, c] of [[150, '#e8d2a8'], [120, '#2f5d7a'], [90, '#f0b04a'], [55, '#b8483a'], [25, '#e8d2a8']]) {
      g.fillStyle = c;
      g.beginPath(); g.moveTo(cx, cy - s * 0.8); g.lineTo(cx + s * 1.25, cy); g.lineTo(cx, cy + s * 0.8); g.lineTo(cx - s * 1.25, cy); g.closePath(); g.fill();
    }
    // узор каймы
    g.fillStyle = '#f0b04a';
    for (let x = 40; x < w - 30; x += 22) { g.beginPath(); g.arc(x, 24, 4, 0, TAU); g.fill(); g.beginPath(); g.arc(x, h - 24, 4, 0, TAU); g.fill(); }
    for (let yy = 40; yy < h - 30; yy += 22) { g.beginPath(); g.arc(24, yy, 4, 0, TAU); g.fill(); g.beginPath(); g.arc(w - 24, yy, 4, 0, TAU); g.fill(); }
    // бахрома и ворс
    const rand = mulberry32(5);
    for (let i = 0; i < 2600; i++) {
      g.fillStyle = `rgba(${rand() < 0.5 ? '0,0,0' : '255,240,220'},${0.04 + rand() * 0.05})`;
      g.fillRect(rand() * w, rand() * h, 2, 2);
    }
  });
}

function paintingTex(kind) {
  return atlas().region(300, 220, (g, w, h) => {
    if (kind === 0) {
      // горы на закате
      const sky = g.createLinearGradient(0, 0, 0, h);
      sky.addColorStop(0, '#f6a76a'); sky.addColorStop(0.6, '#f8d79a'); sky.addColorStop(1, '#f8e6b8');
      g.fillStyle = sky; g.fillRect(0, 0, w, h);
      g.fillStyle = '#fff2c0'; g.beginPath(); g.arc(w * 0.68, h * 0.42, 26, 0, TAU); g.fill();
      for (const [c, y0, amp] of [['#7f6aa8', 0.55, 60], ['#5a4f8a', 0.68, 45], ['#3f6a52', 0.82, 25]]) {
        g.fillStyle = c; g.beginPath(); g.moveTo(0, h);
        for (let x = 0; x <= w; x += 10) g.lineTo(x, h * y0 - Math.abs(Math.sin(x * 0.021 + y0 * 9)) * amp);
        g.lineTo(w, h); g.closePath(); g.fill();
      }
    } else if (kind === 1) {
      // кот на подоконнике (простая открытка)
      g.fillStyle = '#9fc4d8'; g.fillRect(0, 0, w, h);
      g.fillStyle = '#e8d8b8'; g.fillRect(0, h * 0.72, w, h * 0.28);
      g.fillStyle = '#3a3a44';
      g.beginPath(); g.ellipse(w * 0.5, h * 0.62, 50, 38, 0, 0, TAU); g.fill();
      g.beginPath(); g.arc(w * 0.5, h * 0.38, 26, 0, TAU); g.fill();
      g.beginPath(); g.moveTo(w * 0.42, h * 0.3); g.lineTo(w * 0.44, h * 0.18); g.lineTo(w * 0.48, h * 0.28); g.fill();
      g.beginPath(); g.moveTo(w * 0.58, h * 0.3); g.lineTo(w * 0.56, h * 0.18); g.lineTo(w * 0.52, h * 0.28); g.fill();
      g.strokeStyle = '#3a3a44'; g.lineWidth = 9; g.beginPath(); g.moveTo(w * 0.66, h * 0.7); g.quadraticCurveTo(w * 0.85, h * 0.7, w * 0.8, h * 0.45); g.stroke();
      g.fillStyle = '#f4d24a'; g.beginPath(); g.arc(w * 0.46, h * 0.37, 4, 0, TAU); g.fill(); g.beginPath(); g.arc(w * 0.54, h * 0.37, 4, 0, TAU); g.fill();
    } else {
      // абстракция пятнами
      g.fillStyle = '#f2ead8'; g.fillRect(0, 0, w, h);
      const cols = ['#e07a5f', '#3d405b', '#81b29a', '#f2cc8f', '#5a8ac4'];
      const rand = mulberry32(9);
      for (let i = 0; i < 9; i++) {
        g.fillStyle = cols[i % 5];
        g.beginPath(); g.arc(rand() * w, rand() * h, 18 + rand() * 40, 0, TAU); g.fill();
      }
    }
  });
}

export function buildInterior(ctx, apt) {
  const { kit, col, anim } = ctx;
  const { x: ox, d, w, h } = APARTMENT;
  const hw = w / 2, hd = d / 2;
  const x0 = ox - hw, x1 = ox + hw, z0 = -hd, z1 = hd;
  const rand = mulberry32(700);
  // тени под мебелью и тёплые пятна света (запекаются в цвет вершин)
  const blocks = []; // [x0, x1, z0, z1, высота]
  const lamps = [];  // [x, y, z, радиус, сила]
  const bake = (wx, wy, wz, nx, ny, nz) => {
    let k = 1, gl = 0;
    const ax = Math.min(wx - x0, x1 - wx), az = Math.min(wz - z0, z1 - wz);
    if (ny > 0.5 && wy < 0.05) {
      // пол: углы и края темнее, под мебелью - контактная тень
      k *= 0.62 + 0.38 * smoothstep(0, 0.55, ax) * smoothstep(0, 0.55, az);
      for (const [bx0, bx1, bz0, bz1, bh] of blocks) {
        const dx = Math.max(bx0 - wx, wx - bx1, 0), dz = Math.max(bz0 - wz, wz - bz1, 0);
        const inside = wx > bx0 && wx < bx1 && wz > bz0 && wz < bz1;
        const dd = inside ? 0 : Math.hypot(dx, dz);
        k *= 1 - (inside ? 0.45 : 0.4 * (1 - smoothstep(0, 0.12 + bh * 0.25, dd)));
      }
    } else if (ny < -0.5 && wy > h - 0.05) {
      // потолок: отражённый от пола и стен тёплый свет (иначе над гирляндой тёмно-бурая полоса)
      k *= 0.7 + 0.3 * smoothstep(0, 0.6, ax) * smoothstep(0, 0.6, az);
      gl += 0.16 * (0.6 + 0.4 * smoothstep(0, 1.2, Math.min(ax, az)));
    } else if (Math.abs(nx) > 0.5 || Math.abs(nz) > 0.5) {
      // стены и бока мебели: у пола и потолка темнее, в углах комнаты темнее
      const other = Math.abs(nx) > 0.5 ? az : ax;
      const atWall = Math.abs(nx) > 0.5 ? ax < 0.05 : az < 0.05;
      k *= 0.6 + 0.4 * smoothstep(0, 0.4, wy);
      if (atWall) {
        k *= 0.72 + 0.28 * smoothstep(0, 0.6, other);
        k *= 0.82 + 0.18 * smoothstep(0, 0.4, h - wy);
      }
    } else {
      k *= 0.7 + 0.3 * smoothstep(0, 0.3, wy);
    }
    for (const [lx, ly, lz, r, s] of lamps) {
      const dist = Math.hypot(wx - lx, (wy - ly) * 0.8, wz - lz);
      const f = 1 - smoothstep(0, r, dist);
      k *= 1 + f * s * 0.5;
      gl += f * f * s * 0.08;
    }
    return [k, gl];
  };
  kit.group('interior', { cx: ox, cz: 0, r: 8, bake });

  // мебель регистрируем заранее: blocks нужны полу
  const sofa = { x: ox + 0.7, z: -0.4, ry: Math.PI / 2 }; // смотрит на запад, на ТВ
  blocks.push([sofa.x - 0.5, sofa.x + 0.5, sofa.z - 1.2, sofa.z + 1.2, 0.8]);
  blocks.push([ox - 2.1, ox - 0.9, -0.75, -0.05, 0.4]);              // журнальный столик
  blocks.push([x0, x0 + 0.5, -1.7, 0.9, 0.5]);                        // тумба ТВ
  blocks.push([x1 - 0.4, x1, 1.0, 3.4, 2.0]);                         // стеллаж
  blocks.push([x1 - 2.9, x1, z0, z0 + 0.65, 0.9]);                    // кухня вдоль севера
  blocks.push([x1 - 0.65, x1, z0, z0 + 2.4, 0.9]);                    // кухня вдоль востока
  blocks.push([x1 - 0.75, x1, -1.55, -0.8, 1.9]);                     // холодильник
  blocks.push([ox - 3.15, ox - 2.05, 1.85, 2.95, 0.7]);               // кресло
  blocks.push([ox + 2.65, ox + 3.55, 0.15, 1.05, 0.7]);               // столик у окна
  lamps.push([ox + 1.4, 1.5, 1.3, 2.4, 0.9], [ox + 3.1, 2.4, 0.6, 1.8, 0.6], [ox, 2.0, z0 + 0.3, 1.6, 0.5]);

  // ---------- пол, стены, потолок ----------
  const seg = (len) => Math.max(1, Math.round(len * kit.seg(1.5, 3, 4)));
  kit.add(new THREE.PlaneGeometry(w, d, seg(w), seg(d)).rotateX(-Math.PI / 2), '#b07c52', [0.55, 0, 0, PAT.planks], T(ox, 0, 0), { uv: (x, yy, z) => [x, z], shadow: false });
  // стены с проёмами (окно на севере, дверь на юге): прямоугольники, разбитые на ячейки
  const wall = '#ecdcc0';
  const WIN = { a0: -1.8, a1: 1.8, y0: 0.9, y1: 2.5 }, DOOR = { a0: -0.55, a1: 0.55, y0: 0, y1: 2.15 };
  const wallRects = (len, hole) => {
    if (!hole) return [[-len / 2, len / 2, 0, h]];
    return [[-len / 2, hole.a0, 0, h], [hole.a1, len / 2, 0, h], [hole.a0, hole.a1, hole.y1, h], ...(hole.y0 > 0 ? [[hole.a0, hole.a1, 0, hole.y0]] : [])];
  };
  const walls = [
    { m: T(ox, 0, z0, 0, 0, 0), len: w, hole: WIN },             // север, нормаль +z (внутрь)
    { m: T(ox, 0, z1, 0, Math.PI, 0), len: w, hole: DOOR },      // юг
    { m: T(x0, 0, 0, 0, Math.PI / 2, 0), len: d, hole: null },   // запад
    { m: T(x1, 0, 0, 0, -Math.PI / 2, 0), len: d, hole: null },  // восток
  ];
  for (const wl of walls) {
    kit.push(wl.m);
    for (const [a0, a1, y0, y1] of wallRects(wl.len, wl.hole)) {
      if (a1 - a0 < 0.01 || y1 - y0 < 0.01) continue;
      kit.add(new THREE.PlaneGeometry(a1 - a0, y1 - y0, seg(a1 - a0), seg(y1 - y0)), wall, SURF.wallpaper, T((a0 + a1) / 2, (y0 + y1) / 2, 0), { uv: (x, yy) => [x + (a0 + a1) / 2, yy + (y0 + y1) / 2], shadow: false });
    }
    // плинтус и карниз
    const L = wl.len;
    const parts = wl.hole && wl.hole.y0 === 0 ? [[-L / 2, wl.hole.a0], [wl.hole.a1, L / 2]] : [[-L / 2, L / 2]];
    for (const [a0, a1] of parts) kit.box(a1 - a0, 0.1, 0.03, '#f4efe4', SURF.paint, T((a0 + a1) / 2, 0.05, 0.015), { sharp: true, shadow: false });
    kit.box(L, 0.09, 0.07, '#f4efe4', SURF.paint, T(0, h - 0.045, 0.035), { bevel: 0.01, shadow: false });
    kit.pop();
  }
  // толщина откосов окна и двери
  kit.add(new THREE.BoxGeometry(WIN.a1 - WIN.a0 + 0.1, 0.06, 0.3), '#f4efe4', SURF.paint, T(ox, WIN.y0 - 0.0, z0 - 0.15), { shadow: false });
  kit.add(new THREE.BoxGeometry(WIN.a1 - WIN.a0 + 0.1, 0.06, 0.3), '#f4efe4', SURF.paint, T(ox, WIN.y1, z0 - 0.15), { shadow: false });
  for (const s of [-1, 1]) kit.add(new THREE.BoxGeometry(0.06, WIN.y1 - WIN.y0, 0.3), '#f4efe4', SURF.paint, T(ox + s * (WIN.a1 + 0.02), (WIN.y0 + WIN.y1) / 2, z0 - 0.15), { shadow: false });
  for (const s of [-1, 1]) kit.add(new THREE.BoxGeometry(0.06, DOOR.y1, 0.3), '#f4efe4', SURF.paint, T(ox + s * (DOOR.a1 + 0.02), DOOR.y1 / 2, z1 + 0.15), { shadow: false });
  kit.add(new THREE.BoxGeometry(DOOR.a1 - DOOR.a0 + 0.1, 0.06, 0.3), '#f4efe4', SURF.paint, T(ox, DOOR.y1, z1 + 0.15), { shadow: false });
  // наружная оболочка: тёмные стены снаружи (видно только при отладке)
  // потолок (C5)
  kit.roof('apartment', { x0, x1, z0, z1 });
  kit.add(new THREE.PlaneGeometry(w, d, seg(w), seg(d)).rotateX(Math.PI / 2), '#f6efe2', SURF.plain, T(ox, h, 0), { shadow: false });
  // светильник-тарелка в центре
  kit.cyl(0.32, 0.36, 0.06, '#f4efe4', SURF.plain, T(ox - 1.4, h - 0.03, -0.4), { seg: kit.seg(10, 14, 18), shadow: false });
  kit.add(new THREE.CircleGeometry(0.28, kit.seg(10, 14, 18)).rotateX(Math.PI / 2), '#fff1d6', glow(3.2), T(ox - 1.4, h - 0.065, -0.4), { shadow: false, bake: false });
  kit.setLayer('solid');

  // ---------- окно с дождём ----------
  {
    kit.push(T(ox, 0, z0));
    // рама и переплёт
    const fw = WIN.a1 - WIN.a0, fh = WIN.y1 - WIN.y0, my = (WIN.y0 + WIN.y1) / 2;
    for (const s of [-1, 1]) kit.box(0.08, fh, 0.12, '#f4efe4', SURF.paint, T(s * (fw / 2 - 0.04), my, 0.0), { sharp: true, shadow: false });
    kit.box(fw, 0.08, 0.12, '#f4efe4', SURF.paint, T(0, WIN.y1 - 0.04, 0), { sharp: true, shadow: false });
    for (const a of [-fw / 6, fw / 6]) kit.box(0.05, fh, 0.06, '#f4efe4', SURF.paint, T(a, my, 0.0), { sharp: true, shadow: false });
    kit.box(fw, 0.05, 0.06, '#f4efe4', SURF.paint, T(0, my + 0.35, 0.0), { sharp: true, shadow: false });
    // широкий подоконник
    kit.box(fw + 0.3, 0.06, 0.38, '#e8dcc8', SURF.paint, T(0, WIN.y0 + 0.03, 0.13), { bevel: 0.015, shadow: false });
    // карниз и шторы в клетку со складками
    kit.cyl(0.018, 0.018, fw + 1.2, '#6a4a30', SURF.lacq, T(0, WIN.y1 + 0.22, 0.14, 0, 0, Math.PI / 2), { seg: 6, shadow: false });
    for (const s of [-1, 1]) {
      const cw = 0.75, ch = WIN.y1 + 0.2 - 0.05;
      const g = new THREE.PlaneGeometry(cw, ch, kit.seg(6, 10, 14), 4);
      const P = g.getAttribute('position');
      for (let i = 0; i < P.count; i++) {
        const xx = P.getX(i), yy = P.getY(i);
        const t = (yy + ch / 2) / ch; // 0 низ, 1 верх
        // складки и подхват на трети высоты
        const pinch = 1 - 0.35 * Math.exp(-((t - 0.35) ** 2) * 60);
        P.setXYZ(i, xx * pinch, yy, 0.05 * Math.sin(xx * 26) * (0.6 + 0.4 * t));
      }
      g.computeVertexNormals();
      kit.add(g, '#c4483a', SURF.gingham, T(s * (fw / 2 + 0.22), 0.05 + ch / 2, 0.18), { shadow: false, uv: (x, yy) => [x, yy] });
      kit.add(new THREE.TorusGeometry(0.1, 0.012, 3, 8), '#e8c060', SURF.metal, T(s * (fw / 2 + 0.22), 0.05 + ch * 0.35, 0.22, 0, 0, Math.PI / 2), { shadow: false });
    }
    // на подоконнике: кактус, кружка, свеча
    kit.add(lathe([[0, 0], [0.07, 0], [0.085, 0.12], [0.09, 0.13], [0, 0.13]], 10), '#c8643c', SURF.ceramic, T(-1.1, WIN.y0 + 0.06, 0.12), { uv: (x, yy) => [yy, x] });
    kit.add(new THREE.CapsuleGeometry(0.045, 0.12, 3, 8), '#4f8a3a', SURF.plain, T(-1.1, WIN.y0 + 0.28, 0.12));
    kit.add(new THREE.CapsuleGeometry(0.025, 0.05, 3, 6), '#4f8a3a', SURF.plain, T(-1.06, WIN.y0 + 0.3, 0.12, 0, 0, -0.9));
    kit.add(lathe([[0, 0], [0.045, 0], [0.05, 0.1], [0.045, 0.1], [0, 0.01]], 10), '#f2ead8', SURF.ceramic, T(0.6, WIN.y0 + 0.06, 0.15), { uv: (x, yy) => [yy, x] });
    kit.add(new THREE.TorusGeometry(0.028, 0.008, 4, 8), '#f2ead8', SURF.ceramic, T(0.655, WIN.y0 + 0.11, 0.15));
    kit.cyl(0.035, 0.035, 0.08, '#fbf3e0', SURF.plain, T(1.0, WIN.y0 + 0.1, 0.14), { seg: 8 });
    kit.add(new THREE.SphereGeometry(0.012, 5, 4), '#ffd27a', glow(5, PAT.plain, true), T(1.0, WIN.y0 + 0.155, 0.14, 0, 0, 0, 1, 1.8, 1), { bake: false, shadow: false });
    kit.pop();
    // вид на дождливый город за окном - отдельный меш со своим шейдером
    const view = new THREE.Mesh(new THREE.PlaneGeometry(WIN.a1 - WIN.a0 + 6, 5), rainViewMaterial());
    view.position.set(ox, 1.7, z0 - 2.6);
    view.name = 'rain-view';
    kit.cur.extra.push({ obj: view, lod: 'always' });
  }

  // ---------- гостиная ----------
  // ковёр
  const rug = rugTex();
  kit.push(T(ox - 1.4, 0.008, -0.4, -Math.PI / 2, 0, Math.PI / 2));
  atlasPlane(kit, rug, 3.2, 2.5, null, [0.95, 0, 0, PAT.label], { bake: undefined });
  kit.pop();
  // тумба и экран на западной стене
  {
    kit.push(T(x0 + 0.24, 0, -0.4));
    kit.box(0.44, 0.48, 2.5, '#7a5236', SURF.wood, T(0, 0.26, 0), { bevel: 0.02 });
    for (const s of [-1, 0, 1]) kit.box(0.02, 0.36, 0.78, '#8a6040', SURF.wood, T(0.225, 0.27, s * 0.81), { bevel: 0.006 });
    for (const s of [-1, 0, 1]) kit.box(0.02, 0.025, 0.14, '#c8a040', SURF.metal, T(0.24, 0.4, s * 0.81), { sharp: true });
    for (const s of [-1, 1]) kit.cyl(0.02, 0.015, 0.04, '#3a2a20', SURF.wood, T(0.15, 0.02, s * 1.15), { seg: 5 });
    // экран (место для проектора/видео)
    kit.box(0.08, 1.25, 2.2, '#16161c', [0.25, 0, 0, PAT.plain], T(-0.18, 1.5, 0), { bevel: 0.015 });
    kit.box(0.02, 1.15, 2.1, '#0c0c12', [0.15, 0, 0.02, PAT.glass], T(-0.13, 1.5, 0), { sharp: true });
    // колонки, книги, растение
    for (const s of [-1, 1]) {
      kit.box(0.22, 0.4, 0.2, '#2a2a2e', SURF.plain, T(0, 0.7, s * 1.0), { bevel: 0.015 });
      kit.cyl(0.06, 0.06, 0.01, '#4a4a50', SURF.metal, T(0.11, 0.75, s * 1.0, 0, 0, Math.PI / 2), { seg: 10 });
    }
    for (let i = 0; i < 4; i++) kit.box(0.17, 0.035, 0.24, ['#c94b3b', '#4a8bd0', '#e8c84a', '#5fae5a'][i], SURF.plain, T(0, 0.52 + i * 0.036, -0.45, 0, (rand() - 0.5) * 0.3, 0), { sharp: true });
    kit.add(lathe([[0, 0], [0.06, 0], [0.08, 0.14], [0, 0.14]], 10), '#e8dcc8', SURF.ceramic, T(0, 0.5, 0.45), { uv: (x, yy) => [yy, x] });
    for (let i = 0; i < 5; i++) kit.add(new THREE.SphereGeometry(0.06, 6, 4), '#4f8a3a', SURF.fabric, T((rand() - 0.5) * 0.06, 0.7 + rand() * 0.08, 0.45 + (rand() - 0.5) * 0.08, 0, 0, 0, 1, 0.7, 1));
    kit.pop();
    col.addBox(x0 + 0.24, -0.4, 0.24, 1.25, 0, -0.5, 0.5);
  }
  // диван с подушками и пледом
  {
    kit.push(T(sofa.x, 0, sofa.z, 0, sofa.ry, 0)); // локально: сидящий смотрит на -z
    const fab = '#5f8a8c', fabD = '#4e7577';
    const SL = 2.3;
    kit.box(SL, 0.28, 0.9, fabD, SURF.fabric, T(0, 0.22, 0), { bevel: 0.05 });
    for (const s of [-1, 1]) {
      kit.box(0.22, 0.62, 0.92, fab, SURF.fabric, T(s * (SL / 2 - 0.11), 0.39, 0), { bevel: 0.08 });
      kit.cyl(0.025, 0.02, 0.08, '#3a2a20', SURF.wood, T(s * (SL / 2 - 0.1), 0.04, 0.35), { seg: 5 });
      kit.cyl(0.025, 0.02, 0.08, '#3a2a20', SURF.wood, T(s * (SL / 2 - 0.1), 0.04, -0.35), { seg: 5 });
    }
    kit.box(SL - 0.1, 0.55, 0.24, fab, SURF.fabric, T(0, 0.6, 0.34), { bevel: 0.08 });
    for (const s of [-1, 1]) {
      kit.box(0.92, 0.16, 0.66, fab, SURF.fabric, T(s * 0.47, 0.43, -0.08), { bevel: 0.07 });
      kit.box(0.88, 0.48, 0.2, fab, SURF.fabric, T(s * 0.47, 0.72, 0.18, -0.15, 0, 0), { bevel: 0.08 });
    }
    // декоративные подушки
    kit.box(0.42, 0.4, 0.14, '#e8b04a', SURF.fabric, T(-0.72, 0.68, 0.0, -0.2, 0.3, 0.1), { bevel: 0.07 });
    kit.box(0.4, 0.38, 0.13, '#d8d0c0', SURF.gingham, T(0.75, 0.68, 0.0, -0.25, -0.25, -0.1), { bevel: 0.07 });
    // плед: свисает с подлокотника
    const pl = new THREE.PlaneGeometry(0.75, 1.3, 4, 10);
    const P = pl.getAttribute('position');
    for (let i = 0; i < P.count; i++) {
      const px = P.getX(i), py = P.getY(i);
      const t = (py + 0.65) / 1.3; // 0 - на сиденье, 1 - снаружи до пола
      let yy, zz;
      if (t < 0.35) { yy = 0.52 + 0.02 * Math.sin(px * 12); zz = -0.3 + t * 1.7; } else if (t < 0.6) { const a = (t - 0.35) / 0.25 * Math.PI * 0.9; yy = 0.52 + Math.sin(a) * 0.2; zz = 0.3 + (1 - Math.cos(a)) * 0.0; } else { yy = 0.72 - (t - 0.6) * 1.5; zz = 0.48 + Math.sin(px * 9 + t * 4) * 0.02; }
      P.setXYZ(i, px, yy, zz);
    }
    pl.computeVertexNormals();
    kit.add(pl, '#b84a3a', SURF.gingham, T(SL / 2 - 0.45, 0, 0, 0, Math.PI / 2, 0).multiply(T(0, 0, 0)), { shadow: false, uv: (x, yy) => [x, yy] });
    kit.add(pl.clone().scale(-1, 1, 1), '#b84a3a', SURF.gingham, T(SL / 2 - 0.45, 0, 0, 0, Math.PI / 2, 0), { shadow: false, uv: (x, yy) => [x, yy] });
    kit.pop();
    col.addBox(sofa.x, sofa.z, 0.46, SL / 2, 0, -0.5, 0.48);
    col.addBox(sofa.x + 0.36, sofa.z, 0.12, SL / 2, 0, 0.48, 0.88);
  }
  // журнальный столик: кружки, книги, свеча, ваза
  {
    const tx = ox - 1.5, tz = -0.4;
    kit.push(T(tx, 0, tz));
    kit.box(0.62, 0.05, 1.1, '#9a6a42', SURF.lacq, T(0, 0.4, 0), { bevel: 0.015 });
    kit.box(0.54, 0.03, 1.0, '#8a5a36', SURF.wood, T(0, 0.12, 0), { bevel: 0.01 });
    for (const [e, e2] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) kit.box(0.05, 0.38, 0.05, '#6e4a2c', SURF.wood, T(e * 0.26, 0.19, e2 * 0.5), { bevel: 0.01 });
    for (const [mx, mz, c] of [[0.1, -0.3, '#e86a4a'], [-0.12, -0.18, '#4a8bd0']]) {
      kit.add(lathe([[0, 0], [0.045, 0], [0.05, 0.1], [0.045, 0.1], [0, 0.01]], 10), c, SURF.ceramic, T(mx, 0.425, mz), { uv: (x, yy) => [yy, x] });
      kit.add(new THREE.TorusGeometry(0.028, 0.008, 4, 8), c, SURF.ceramic, T(mx + 0.055, 0.475, mz));
    }
    for (let i = 0; i < 3; i++) kit.box(0.2, 0.035, 0.28, ['#2f5d7a', '#c4483a', '#e8c84a'][i], SURF.plain, T(0.05, 0.442 + i * 0.036, 0.25, 0, (rand() - 0.5) * 0.4, 0), { sharp: true });
    kit.cyl(0.05, 0.05, 0.09, '#fbf3e0', SURF.plain, T(-0.15, 0.47, 0.32), { seg: 8 });
    kit.add(new THREE.SphereGeometry(0.014, 5, 4), '#ffd27a', glow(5, PAT.plain, true), T(-0.15, 0.53, 0.32, 0, 0, 0, 1, 1.8, 1), { bake: false, shadow: false });
    kit.pop();
    col.addBox(tx, tz, 0.31, 0.55, 0, -0.5, 0.42);
  }
  // кресло у торшера
  {
    const ax = ox - 2.6, az = 2.4;
    kit.push(T(ax, 0, az, 0, 0.71, 0)); // смотрит на ТВ
    const fab = '#c98a4a';
    kit.box(0.9, 0.3, 0.85, '#b07a40', SURF.fabric, T(0, 0.24, 0), { bevel: 0.06 });
    kit.box(0.62, 0.15, 0.62, fab, SURF.fabric, T(0, 0.45, -0.05), { bevel: 0.06 });
    kit.box(0.86, 0.6, 0.2, fab, SURF.fabric, T(0, 0.7, 0.32, -0.12, 0, 0), { bevel: 0.08 });
    for (const s of [-1, 1]) kit.box(0.16, 0.42, 0.82, fab, SURF.fabric, T(s * 0.38, 0.5, 0), { bevel: 0.07 });
    for (const [e, e2] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) kit.cyl(0.025, 0.02, 0.1, '#3a2a20', SURF.wood, T(e * 0.38, 0.05, e2 * 0.35), { seg: 5 });
    kit.box(0.36, 0.34, 0.12, '#5f8a8c', SURF.gingham, T(0, 0.66, 0.18, -0.25, 0, 0), { bevel: 0.06 });
    kit.pop();
    col.addBox(ax, az, 0.45, 0.42, 0.71, -0.5, 0.5);
  }
  // торшер: тёплый абажур светится (HDR), круг света на полу запечён
  {
    const lx = ox + 1.4, lz = 1.3;
    kit.cyl(0.18, 0.2, 0.03, '#2e2e30', SURF.metal, T(lx, 0.015, lz), { seg: kit.seg(8, 12, 14) });
    kit.cyl(0.015, 0.015, 1.5, '#2e2e30', SURF.metal, T(lx, 0.78, lz), { seg: 6 });
    kit.add(new THREE.CylinderGeometry(0.17, 0.26, 0.32, kit.seg(10, 14, 18), 1, true), '#f2d8a8', glow(1.9, PAT.fabric), T(lx, 1.62, lz), { shadow: false, uv: (x, yy, z) => [Math.atan2(x, z) * 0.2, yy] });
    kit.add(new THREE.CylinderGeometry(0.17, 0.26, 0.32, kit.seg(10, 14, 18), 1, true).scale(-0.99, 1, 0.99), '#ffd9a0', glow(3.0), T(lx, 1.62, lz), { shadow: false, bake: false });
    kit.add(new THREE.SphereGeometry(0.05, 6, 5), '#fff0c8', glow(6), T(lx, 1.55, lz), { shadow: false, bake: false });
    col.addCircle(lx, lz, 0.2, -0.5, 1.8);
  }
  // стеллаж с книгами у восточной стены
  {
    const bx = x1 - 0.18, bz = 2.2;
    kit.push(T(bx, 0, bz, 0, Math.PI / 2, 0)); // локально: лицом на -z = на запад
    const wd = '#8a5a36';
    for (const s of [-1, 1]) kit.box(0.04, 2.1, 0.34, wd, SURF.wood, T(s * 1.2, 1.05, 0), { bevel: 0.008 });
    kit.box(2.44, 0.04, 0.34, wd, SURF.wood, T(0, 2.08, 0), { bevel: 0.008 });
    kit.box(2.4, 2.1, 0.02, '#7a4e2e', SURF.wood, T(0, 1.05, 0.16), { sharp: true, shadow: false });
    const books = ['#c94b3b', '#4a8bd0', '#e8c84a', '#5fae5a', '#8a5ac0', '#e88a3a', '#3f5f7a', '#d8d0c0', '#7a3a2a'];
    for (let sh = 0; sh < 5; sh++) {
      const sy = 0.06 + sh * 0.42;
      kit.box(2.36, 0.03, 0.32, wd, SURF.wood, T(0, sy, 0), { bevel: 0.006 });
      let x = -1.15;
      while (x < 1.1) {
        const bw = 0.03 + rand() * 0.04, bh = 0.22 + rand() * 0.12;
        if (rand() < 0.1 && x < 0.8) {
          // вещица: ваза, горшочек или рамка
          const kind = Math.floor(rand() * 3);
          if (kind === 0) kit.add(lathe([[0, 0], [0.05, 0], [0.07, 0.08], [0.03, 0.18], [0.04, 0.2], [0, 0.2]], 8), '#5aa0b0', SURF.ceramic, T(x + 0.08, sy + 0.015, -0.02), { uv: (a, b) => [b, a] });
          else if (kind === 1) { kit.add(lathe([[0, 0], [0.05, 0], [0.06, 0.08], [0, 0.08]], 8), '#c8643c', SURF.ceramic, T(x + 0.08, sy + 0.015, -0.02), { uv: (a, b) => [b, a] }); kit.sphere(0.07, '#4f8a3a', SURF.fabric, T(x + 0.08, sy + 0.14, -0.02), { seg: 6 }); } else { kit.box(0.14, 0.18, 0.02, '#3a2a20', SURF.wood, T(x + 0.08, sy + 0.1, 0.05, -0.15, 0, 0), { sharp: true }); }
          x += 0.2;
          continue;
        }
        const lean = rand() < 0.08 ? 0.25 : 0;
        kit.box(bw, bh, 0.22 + rand() * 0.05, new THREE.Color(books[Math.floor(rand() * books.length)]).multiplyScalar(0.8 + rand() * 0.3), SURF.plain, T(x + bw / 2, sy + 0.015 + bh / 2, -0.02, 0, 0, lean), { sharp: true, shadow: false });
        x += bw + 0.004 + lean * 0.1;
      }
    }
    kit.pop();
    col.addBox(bx, bz, 0.18, 1.22, 0, -0.5, 2.1);
  }
  // кухонный уголок: шкафы, столешница, мойка, плита, вытяжка, верхние шкафы, плитка, холодильник
  {
    const cw = '#e8e2d4', top = '#a87a52', H0 = 0.88;
    const run = (ax, az, len, ry, items) => {
      kit.push(T(ax, 0, az, 0, ry, 0)); // локально: x вдоль, фасады смотрят на -z
      kit.box(len, H0 - 0.1, 0.58, cw, SURF.paint, T(len / 2, 0.1 + (H0 - 0.1) / 2, 0), { bevel: 0.01 });
      kit.box(len, 0.1, 0.52, '#3a3430', SURF.plain, T(len / 2, 0.05, 0.03), { sharp: true, shadow: false });
      kit.box(len + 0.02, 0.04, 0.62, top, SURF.lacq, T(len / 2, H0 + 0.02, -0.01), { bevel: 0.01 });
      const nd = Math.round(len / 0.5);
      for (let i = 0; i < nd; i++) {
        const dx = (i + 0.5) * (len / nd);
        kit.box(len / nd - 0.02, H0 - 0.16, 0.02, '#f2ede2', SURF.paint, T(dx, 0.1 + (H0 - 0.1) / 2, -0.3), { bevel: 0.006, shadow: false });
        kit.box(0.12, 0.02, 0.02, '#8e9499', SURF.metal, T(dx, H0 - 0.15, -0.32), { sharp: true, shadow: false });
      }
      // плитка над столешницей и верхние шкафы
      kit.add(new THREE.PlaneGeometry(len, 0.6), '#9fc4c8', SURF.tiles, T(len / 2, H0 + 0.34, 0.285), { shadow: false, uv: (x, yy) => [x, yy] });
      kit.box(len, 0.7, 0.34, cw, SURF.paint, T(len / 2, H0 + 1.0, 0.13), { bevel: 0.01 });
      for (let i = 0; i < nd; i++) {
        const dx = (i + 0.5) * (len / nd);
        kit.box(len / nd - 0.02, 0.66, 0.02, '#f2ede2', SURF.paint, T(dx, H0 + 1.0, -0.05), { bevel: 0.006, shadow: false });
        kit.box(0.02, 0.1, 0.02, '#8e9499', SURF.metal, T(dx + (i % 2 ? -1 : 1) * (len / nd / 2 - 0.06), H0 + 0.76, -0.07), { sharp: true, shadow: false });
      }
      items();
      kit.pop();
    };
    // вдоль северной стены: мойка и плита
    run(x1, z0 + 0.3, 2.9, Math.PI, () => {
      // локально после поворота: x идёт с востока на запад... фасады смотрят на юг (-z локально = +z мира)
      // мойка
      kit.box(0.5, 0.02, 0.4, '#b9c0c6', SURF.metal, T(0.8, H0 + 0.045, 0), { sharp: true });
      kit.box(0.44, 0.12, 0.34, '#8e969c', SURF.metal, T(0.8, H0 - 0.02, 0), { sharp: true, shadow: false });
      kit.add(tube([V3(0.8, H0 + 0.04, 0.2), V3(0.8, H0 + 0.32, 0.2), V3(0.8, H0 + 0.36, 0.12), V3(0.8, H0 + 0.3, 0.06)], 0.014, 5), '#c8ced3', SURF.metal, null);
      // плита
      kit.box(0.6, 0.02, 0.56, '#1e1e22', SURF.gloss, T(2.1, H0 + 0.045, 0), { sharp: true });
      for (const [bx, bz] of [[-0.14, -0.12], [0.14, -0.12], [-0.14, 0.13], [0.14, 0.13]]) kit.add(new THREE.RingGeometry(0.05, 0.08, 12).rotateX(-Math.PI / 2), '#555', SURF.metal, T(2.1 + bx, H0 + 0.057, bz), { shadow: false });
      kit.box(0.56, 0.45, 0.02, '#2a2a2e', [0.1, 0, 0, PAT.glass], T(2.1, 0.45, -0.31), { sharp: true, shadow: false });
      kit.box(0.4, 0.02, 0.02, '#8e9499', SURF.metal, T(2.1, 0.72, -0.33), { sharp: true, shadow: false });
      // кастрюля и чайник
      kit.add(lathe([[0, 0], [0.12, 0], [0.12, 0.14], [0.125, 0.14], [0, 0.14]], 12), '#c8443a', SURF.gloss, T(2.24, H0 + 0.06, -0.12), { uv: (x, yy) => [yy, x] });
      kit.add(lathe([[0, 0], [0.09, 0], [0.1, 0.06], [0.08, 0.15], [0.03, 0.18], [0.035, 0.2], [0, 0.21]], 12), '#e8e0cc', SURF.ceramic, T(1.95, H0 + 0.06, 0.12), { uv: (x, yy) => [yy, x] });
      kit.add(tube([V3(2.03, H0 + 0.1, 0.12), V3(2.1, H0 + 0.16, 0.12), V3(2.13, H0 + 0.2, 0.12)], 0.012, 4), '#e8e0cc', SURF.ceramic, null);
      // доска, миска с фруктами, банки
      kit.box(0.36, 0.02, 0.24, '#c8a070', SURF.wood, T(1.35, H0 + 0.05, -0.05, 0, 0.2, 0), { bevel: 0.006 });
      kit.add(lathe([[0, 0], [0.06, 0], [0.13, 0.07], [0.14, 0.08], [0, 0.02]], 12), '#5aa0b0', SURF.ceramic, T(0.25, H0 + 0.04, -0.05), { uv: (x, yy) => [yy, x] });
      for (const [fx, fz, c] of [[0.22, -0.02, '#e8452e'], [0.28, -0.08, '#f2c84b'], [0.24, -0.1, '#7ab04a'], [0.3, 0.0, '#e8452e']]) kit.sphere(0.04, c, SURF.gloss, T(fx, H0 + 0.11, fz), { seg: 6 });
      for (let i = 0; i < 3; i++) kit.add(lathe([[0, 0], [0.05, 0], [0.05, 0.14], [0.04, 0.15], [0.04, 0.17], [0, 0.17]], 8), ['#e8c84a', '#c87a3a', '#8a5a36'][i], SURF.glass, T(1.6 + i * 0.12, H0 + 0.04, 0.2), { uv: (x, yy) => [yy, x] });
    });
    // вдоль восточной стены
    run(x1 - 0.3, z0 + 2.42, 1.8, Math.PI / 2, () => {
      kit.add(lathe([[0, 0], [0.1, 0], [0.1, 0.24], [0, 0.24]], 12), '#d8d4c8', SURF.metal, T(0.5, H0 + 0.04, 0.05), { uv: (x, yy) => [yy, x] });
      for (let i = 0; i < 4; i++) kit.cyl(0.11, 0.11, 0.015, '#f4f0e6', SURF.ceramic, T(1.2, H0 + 0.05 + i * 0.016, 0.0), { seg: 12 });
    });
    // холодильник
    kit.push(T(x1 - 0.36, 0, -1.18, 0, Math.PI / 2, 0));
    kit.box(0.7, 1.9, 0.66, '#eef0f0', SURF.gloss, T(0, 0.95, 0), { bevel: 0.03 });
    kit.box(0.68, 0.01, 0.02, '#c8ccd0', SURF.metal, T(0, 1.25, -0.335), { sharp: true, shadow: false });
    kit.box(0.03, 0.4, 0.04, '#b9c0c6', SURF.metal, T(-0.28, 1.5, -0.35), { sharp: true, shadow: false });
    kit.box(0.03, 0.3, 0.04, '#b9c0c6', SURF.metal, T(-0.28, 0.95, -0.35), { sharp: true, shadow: false });
    // магнитики и записка
    kit.box(0.12, 0.15, 0.005, '#fff6c8', SURF.plain, T(0.1, 1.55, -0.335, 0, 0, 0.1), { sharp: true, shadow: false });
    for (const [mx, my, c] of [[0.1, 1.62, '#e8452e'], [-0.1, 1.4, '#4a8bd0'], [0.18, 1.1, '#f2c84b']]) kit.cyl(0.02, 0.02, 0.012, c, SURF.gloss, T(mx, my, -0.338, Math.PI / 2, 0, 0), { seg: 8, shadow: false });
    kit.pop();
    col.addBox(x1 - 1.45, z0 + 0.3, 1.45, 0.3, 0, -0.5, 0.9);
    col.addBox(x1 - 0.3, z0 + 1.5, 0.3, 0.9, 0, -0.5, 0.9);
    col.addBox(x1 - 0.36, -1.18, 0.35, 0.34, 0, -0.5, 1.9);
  }
  // столик у окна с двумя стульями и подвесной лампой
  {
    const tx = ox + 3.1, tz = 0.6;
    kit.push(T(tx, 0, tz));
    kit.cyl(0.45, 0.45, 0.04, '#b8865a', SURF.lacq, T(0, 0.74, 0), { seg: kit.seg(12, 16, 20) });
    kit.add(lathe([[0, 0], [0.22, 0], [0.22, 0.03], [0.05, 0.06], [0.04, 0.4], [0.06, 0.7], [0.08, 0.72], [0, 0.72]], kit.seg(8, 10, 12)), '#6e4a2c', SURF.wood, null, { uv: (x, yy) => [yy, x] });
    kit.add(lathe([[0, 0], [0.04, 0], [0.05, 0.08], [0.02, 0.14], [0.025, 0.16], [0, 0.16]], 8), '#e8dcc8', SURF.ceramic, T(0.1, 0.76, -0.05), { uv: (x, yy) => [yy, x] });
    for (let i = 0; i < 3; i++) kit.sphere(0.03, ['#f07a8a', '#f4d24a', '#ffffff'][i], SURF.fabric, T(0.1 + (i - 1) * 0.03, 0.95 + (i % 2) * 0.03, -0.05), { seg: 5 });
    for (const a of [Math.PI * 0.75, -Math.PI * 0.25]) {
      kit.push(T(Math.cos(a) * 0.62, 0, Math.sin(a) * 0.62, 0, -a + Math.PI / 2, 0));
      kit.box(0.42, 0.04, 0.42, '#b8865a', SURF.wood, T(0, 0.46, 0), { bevel: 0.01 });
      kit.box(0.42, 0.42, 0.04, '#b8865a', SURF.wood, T(0, 0.7, 0.2, -0.08, 0, 0), { bevel: 0.01 });
      for (const [e, e2] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) kit.box(0.035, 0.46, 0.035, '#6e4a2c', SURF.wood, T(e * 0.18, 0.23, e2 * 0.18), { bevel: 0.006 });
      kit.pop();
    }
    // подвесная лампа над столом
    kit.add(tube([V3(0, h, 0), V3(0, 1.85, 0)], 0.006, 3), '#2a2a2c', SURF.plain, null, { shadow: false });
    kit.add(lathe([[0.03, 0.22], [0.06, 0.2], [0.18, 0.05], [0.2, 0], [0.19, 0], [0.17, 0.04], [0.05, 0.19], [0.03, 0.2]], kit.seg(10, 14, 16)), '#2f6a5a', SURF.paint, T(0, 1.62, 0), { shadow: false, uv: (x, yy) => [yy, x] });
    kit.add(new THREE.CircleGeometry(0.17, 12).rotateX(Math.PI / 2), '#ffe8b8', glow(4.0), T(0, 1.635, 0), { shadow: false, bake: false });
    kit.pop();
    col.addCircle(tx, tz, 0.46, -0.5, 0.76);
  }
  // растения: монстера в углу и подвесное у окна
  {
    const px = x0 + 0.5, pz = z0 + 0.5;
    kit.add(lathe([[0, 0], [0.2, 0], [0.26, 0.42], [0.28, 0.44], [0, 0.44]], kit.seg(8, 12, 14)), '#d8d0c0', SURF.ceramic, T(px, 0, pz), { uv: (x, yy) => [yy, x] });
    kit.add(new THREE.CircleGeometry(0.25, 10).rotateX(-Math.PI / 2), '#3a2a20', SURF.soil, T(px, 0.42, pz), { shadow: false });
    for (let i = 0; i < 9; i++) {
      const a = (i / 9) * TAU + rand() * 0.5, tilt = 0.4 + rand() * 0.6, L = 0.6 + rand() * 0.5;
      const tip = V3(px + Math.cos(a) * Math.sin(tilt) * L, 0.42 + Math.cos(tilt) * L, pz + Math.sin(a) * Math.sin(tilt) * L);
      kit.add(tube([V3(px, 0.42, pz), V3(px + (tip.x - px) * 0.5, 0.42 + (tip.y - 0.42) * 0.62, pz + (tip.z - pz) * 0.5), tip], 0.01, 3), '#3f6a2a', SURF.plain, null, { shadow: false });
      // лист-сердце с прорезями
      const leaf = slabGeo([[0, 0], [0.12, 0.08], [0.18, 0.2], [0.14, 0.3], [0.05, 0.34], [0, 0.3], [-0.05, 0.34], [-0.14, 0.3], [-0.18, 0.2], [-0.12, 0.08]], 0.008, 0);
      kit.add(leaf, new THREE.Color('#3f7a34').multiplyScalar(0.8 + rand() * 0.35), SURF.gloss, along(tip, tip.clone().sub(V3(px, 0.42, pz))).multiply(T(0, 0, 0, 0, 0, -Math.PI / 2)).multiply(T(0, -0.02, 0, 1.2, 0, 0)), { shadow: false });
    }
    col.addCircle(px, pz, 0.28, -0.5, 1.4);
    // подвесное кашпо с ниспадающими плетями
    const hx = ox + 1.25, hz = z0 + 0.5, hy = 2.3;
    for (let i = 0; i < 3; i++) { const a = (i / 3) * TAU; kit.add(tube([V3(hx, h, hz), V3(hx + Math.cos(a) * 0.14, hy + 0.12, hz + Math.sin(a) * 0.14)], 0.004, 3), '#c9b28a', SURF.fabric, null, { shadow: false }); }
    kit.add(lathe([[0, 0], [0.1, 0], [0.16, 0.1], [0.17, 0.14], [0, 0.14]], 10), '#c8643c', SURF.ceramic, T(hx, hy, hz), { uv: (x, yy) => [yy, x], shadow: false });
    for (let i = 0; i < 7; i++) {
      const a = (i / 7) * TAU, L = 0.4 + rand() * 0.6;
      const pts = [];
      for (let k = 0; k <= 5; k++) { const t = k / 5; pts.push(V3(hx + Math.cos(a) * (0.15 + t * 0.12), hy + 0.12 - t * L, hz + Math.sin(a) * (0.15 + t * 0.12))); }
      kit.add(tube(pts, 0.006, 3), '#3f6a2a', SURF.plain, null, { shadow: false });
      for (let k = 1; k <= 5; k++) kit.add(new THREE.CircleGeometry(0.035, 5), '#4f8a3a', SURF.gloss, T(pts[k].x, pts[k].y, pts[k].z, rand() * 3, a, 0), { shadow: false });
    }
  }
  // картины
  {
    const frame = (px, py, pz, ry, pw, ph, kind) => {
      kit.push(T(px, py, pz, 0, ry, 0));
      kit.box(pw + 0.1, ph + 0.1, 0.04, '#5a3a24', SURF.lacq, T(0, 0, 0.02), { bevel: 0.01, shadow: false });
      kit.box(pw + 0.02, ph + 0.02, 0.01, '#f2ead8', SURF.plain, T(0, 0, 0.042), { sharp: true, shadow: false });
      atlasPlane(kit, paintingTex(kind), pw - 0.06, ph - 0.06, T(0, 0, 0.048), [0.6, 0, 0, PAT.label], { bake: undefined });
      kit.pop();
    };
    frame(ox - 2.6, 1.65, z1, Math.PI, 0.9, 0.66, 0);
    frame(x1, 1.6, 0.6, -Math.PI / 2, 0.6, 0.45, 1);
    frame(ox + 2.6, 1.75, z1, Math.PI, 0.5, 0.65, 2);
  }
  // гирлянда вдоль окна и западной стены
  {
    const pts0 = [V3(x0 + 0.05, h - 0.12, z1 - 0.6), V3(x0 + 0.05, h - 0.12, -1.2), V3(x0 + 0.05, h - 0.12, z0 + 0.05), V3(ox - 1.9, h - 0.12, z0 + 0.05), V3(ox + 1.9, h - 0.12, z0 + 0.05), V3(x1 - 3.0, h - 0.12, z0 + 0.05)];
    const warm = ['#ffd38a', '#ffc070', '#ffe2a8', '#ffb35c'];
    let bi = 0;
    for (let i = 0; i < pts0.length - 1; i++) {
      const sp = sagPoints(pts0[i], pts0[i + 1], 0.22, 10);
      kit.add(tube(sp, 0.004, 3), '#3a3028', SURF.plain, null, { shadow: false, bake: false });
      for (let k = 1; k < sp.length; k++) {
        kit.add(new THREE.IcosahedronGeometry(0.03, kit.q > 1 ? 1 : 0), warm[bi++ % 4], glow(4.0), T(sp[k].x, sp[k].y - 0.035, sp[k].z, 0, 0, 0, 1, 1.3, 1), { shadow: false, bake: false });
      }
    }
  }
  // дверь на улицу: полотно, наличник, ручка, коврик, вешалка с курткой и зонтом
  {
    kit.push(T(ox, 0, z1, 0, Math.PI, 0)); // локально +z - внутрь комнаты
    kit.box(1.08, 2.12, 0.05, '#7a4e30', SURF.lacq, T(0, 1.06, -0.01), { bevel: 0.01, shadow: false });
    for (const [px, py] of [[0, 1.55], [0, 0.6]]) kit.box(0.8, 0.7, 0.012, '#6a4228', SURF.wood, T(px, py, 0.02), { bevel: 0.01, shadow: false });
    for (const s of [-1, 1]) kit.box(0.08, 2.2, 0.06, '#f4efe4', SURF.paint, T(s * 0.6, 1.1, 0.02), { sharp: true, shadow: false });
    kit.box(1.28, 0.08, 0.06, '#f4efe4', SURF.paint, T(0, 2.2, 0.02), { sharp: true, shadow: false });
    kit.cyl(0.03, 0.03, 0.06, '#c8a040', SURF.metal, T(0.4, 1.0, 0.05, Math.PI / 2, 0, 0), { seg: 8, shadow: false });
    kit.box(0.12, 0.02, 0.02, '#c8a040', SURF.metal, T(0.36, 1.0, 0.08), { sharp: true, shadow: false });
    kit.box(0.9, 0.015, 0.55, '#8a6a4a', SURF.fabric, T(0, 0.008, 0.45), { sharp: true, shadow: false });
    // вешалка
    kit.box(0.7, 0.08, 0.03, '#6e4a2c', SURF.wood, T(-1.3, 1.75, 0.015), { bevel: 0.01, shadow: false });
    for (let i = 0; i < 3; i++) kit.cyl(0.012, 0.012, 0.08, '#c8a040', SURF.metal, T(-1.55 + i * 0.25, 1.73, 0.06, Math.PI / 2 - 0.4, 0, 0), { seg: 5, shadow: false });
    kit.box(0.4, 0.75, 0.1, '#c8752a', SURF.fabric, T(-1.55, 1.33, 0.08), { bevel: 0.05, shadow: false });
    kit.add(tube([V3(-1.05, 1.68, 0.08), V3(-1.05, 0.15, 0.12)], 0.012, 4), '#2a2a2e', SURF.plain, null, { shadow: false });
    kit.add(new THREE.ConeGeometry(0.07, 0.6, 8), '#3f6fb0', SURF.fabric, T(-1.05, 0.5, 0.12), { shadow: false });
    kit.pop();
  }

  // свет: один тёплый точечный у торшера (остальное - свечение и запечённые пятна)
  const lamp = new THREE.PointLight('#ffb468', 8, 11, 2);
  lamp.position.set(ox + 1.2, 1.9, 1.1);
  kit.cur.extra.push({ obj: lamp, lod: 'always' });
  void anim;

  // коллизии комнаты
  col.addBox(ox, 0, hw + 0.4, hd + 0.4, 0, -1, 0.0, false);
  col.addBox(ox, hd + 0.25, hw + 0.5, 0.25, 0, 0, h);
  col.addBox(x0 - 0.25, 0, 0.25, hd + 0.5, 0, 0, h);
  col.addBox(x1 + 0.25, 0, 0.25, hd + 0.5, 0, 0, h);
  col.addBox(ox, -hd - 0.25, hw + 0.5, 0.25, 0, 0, h);
  ctx.portals.push({ id: 'apt-out', x: ox, y: 0, z: hd - 0.5, r: 1.8, label: 'Выйти на улицу', to: { ...apt.exit } });
  void groundAO; void hash2;
}
