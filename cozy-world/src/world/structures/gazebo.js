// Беседка: каменный цоколь, дощатый пол, ступени, точёные балясины перил, резные кронштейны, шатровая крыша
// из рядов гонта с коньками и навершием (отдельный меш - C5), фонарь под крышей; внутри лавки, чайный
// столик с вазой и свечой, табуреты, по углам ящики с цветами.

import * as THREE from 'three';
import { heightAt } from '../terrain-data.js';
import { mulberry32, hash2, TAU } from '../../util/math.js';
import { GAZEBO } from '../../../shared/layout.js';
import { SURF, PAT, glow, T, lathe, slabGeo, tube, stoneGeo } from './kit.js';
import { stoneColor } from './hub.js';

const WOOD = '#c99a66', WOOD_D = '#8a5d38', TRIM = '#efe2c6', ROOF = '#a8483a';

export function buildGazebo(ctx) {
  const { kit, col } = ctx;
  const { x: cx, z: cz } = GAZEBO;
  const y0 = heightAt(cx, cz);
  const FL = GAZEBO.floorTop - y0; // высота пола над землёй (0.3)
  const HW = 3.2;                  // полуширина пола
  const PH = 2.45;                 // высота столбов над полом
  const rand = mulberry32(27);
  kit.group('gazebo', { cx, cz, r: 5 });
  kit.push(T(cx, y0, cz));

  // ---------- цоколь и пол ----------
  kit.setLayer('solid');
  // каменный цоколь по периметру: ряд скруглённых блоков
  const blocks = kit.seg(8, 12, 14);
  for (let side = 0; side < 4; side++) {
    for (let i = 0; i < blocks; i++) {
      const t = -HW + ((i + 0.5) / blocks) * HW * 2;
      const w = (HW * 2) / blocks - 0.02;
      const c = new THREE.Color('#9a958c').multiplyScalar(0.85 + rand() * 0.25);
      const m = side % 2 === 0 ? T(t, FL / 2 - 0.04, side === 0 ? -HW : HW) : T(side === 1 ? HW : -HW, FL / 2 - 0.04, t, 0, Math.PI / 2, 0);
      kit.box(w, FL - 0.02, 0.24, c, SURF.stone, m, { bevel: 0.04, shadow: false });
    }
  }
  // тень от цоколя с полом - одним бруском
  kit.shadowOnly(new THREE.BoxGeometry(HW * 2 + 0.1, FL, HW * 2 + 0.1), T(0, FL / 2 - 0.02, 0));
  // настил: доски вдоль X с зазорами, обвязка по краю
  const plank = 0.15;
  const np = Math.floor((HW * 2) / plank);
  for (let i = 0; i < np; i++) {
    const z = -HW + (i + 0.5) * plank;
    const c = new THREE.Color(WOOD).multiplyScalar(0.84 + hash2(i, 5) * 0.26);
    kit.box(HW * 2 - 0.1, 0.04, plank - 0.012, c, SURF.wood, T((hash2(i, 9) - 0.5) * 0.02, FL - 0.02, z), { bevel: 0.01, shadow: false });
  }
  for (let side = 0; side < 4; side++) {
    const m = side % 2 === 0 ? T(0, FL - 0.06, side === 0 ? -HW - 0.02 : HW + 0.02) : T(side === 1 ? HW + 0.02 : -HW - 0.02, FL - 0.06, 0, 0, Math.PI / 2, 0);
    kit.box(HW * 2 + 0.08, 0.14, 0.08, WOOD_D, SURF.wood, m, { bevel: 0.015 });
  }
  col.addBox(cx, cz, HW + 0.1, HW + 0.1, 0, y0 - 1, y0 + FL);
  // ступени у входов (запад и юг)
  for (const [sx, sz, ry] of [[-HW - 0.3, 0, Math.PI / 2], [0, HW + 0.3, 0]]) {
    kit.box(1.9, FL / 2, 0.36, '#8f8a82', SURF.stone, T(sx, FL / 4, sz, 0, ry, 0), { bevel: 0.03 });
    kit.box(1.7, 0.035, 0.32, WOOD, SURF.wood, T(sx, FL / 2 + 0.017, sz, 0, ry, 0), { bevel: 0.008 });
    col.addBox(cx + sx, cz + sz, 0.95, 0.18, ry, y0 - 0.5, y0 + FL / 2);
  }

  // ---------- столбы, балки, перила ----------
  const posts = [];
  const sideT = [-HW + 0.1, -1.0, 1.0, HW - 0.1];
  for (const t of sideT) { posts.push([t, -HW + 0.1]); posts.push([t, HW - 0.1]); }
  for (const t of [-1.0, 1.0]) { posts.push([-HW + 0.1, t]); posts.push([HW - 0.1, t]); }
  for (const [px, pz] of posts) {
    kit.box(0.24, 0.12, 0.24, WOOD_D, SURF.wood, T(px, FL + 0.06, pz), { bevel: 0.02 });
    kit.box(0.17, PH, 0.17, TRIM, SURF.paint, T(px, FL + PH / 2, pz), { bevel: 0.03 });
    kit.box(0.24, 0.1, 0.24, TRIM, SURF.paint, T(px, FL + PH - 0.05, pz), { bevel: 0.02 });
    kit.box(0.21, 0.05, 0.21, WOOD_D, SURF.wood, T(px, FL + 0.95, pz), { bevel: 0.01 });
    col.addCircle(cx + px, cz + pz, 0.13, y0, y0 + FL + PH + 0.3);
  }
  // верхняя обвязка
  const BY = FL + PH + 0.1;
  for (let side = 0; side < 4; side++) {
    const m = side % 2 === 0 ? T(0, BY, side === 0 ? -HW + 0.1 : HW - 0.1) : T(side === 1 ? HW - 0.1 : -HW + 0.1, BY, 0, 0, Math.PI / 2, 0);
    kit.box(HW * 2 + 0.3, 0.22, 0.17, WOOD_D, SURF.wood, m, { bevel: 0.02 });
  }
  // перила в пролётах (кроме средних пролётов запада и юга - входы)
  const bays = [];
  const spans = [[-HW + 0.1, -1.0], [-1.0, 1.0], [1.0, HW - 0.1]];
  for (let side = 0; side < 4; side++) {
    spans.forEach(([a, b], k) => {
      if (k === 1 && (side === 1 || side === 3)) {
        // side: 0 - север (z-), 1 - юг (z+), 2 - восток (x+), 3 - запад (x-)
        return;
      }
      bays.push({ side, a, b });
    });
  }
  const RH = 0.72; // высота перил над полом
  const balProf = [[0.03, 0], [0.03, 0.04], [0.021, 0.07], [0.036, 0.17], [0.018, 0.3], [0.04, 0.4], [0.02, 0.5], [0.03, 0.54], [0.03, RH - 0.12]];
  const balSeg = kit.seg(4, 6, 8);
  const balGeo = kit.q === 0 ? null : lathe(balProf, balSeg);
  const balShadow = new THREE.BoxGeometry(0.045, RH - 0.12, 0.045).translate(0, (RH - 0.12) / 2, 0);
  for (const { side, a, b } of bays) {
    const len = b - a - 0.17, mid = (a + b) / 2;
    const fixed = side === 0 ? -HW + 0.1 : side === 1 ? HW - 0.1 : side === 2 ? HW - 0.1 : -HW + 0.1;
    const alongX = side < 2;
    const frame = alongX ? T(mid, 0, fixed) : T(fixed, 0, mid, 0, Math.PI / 2, 0);
    kit.push(frame);
    kit.box(len, 0.06, 0.12, WOOD_D, SURF.wood, T(0, FL + RH, 0), { bevel: 0.015 });
    kit.box(len, 0.05, 0.08, WOOD_D, SURF.wood, T(0, FL + 0.1, 0), { bevel: 0.012 });
    const nb = Math.max(2, Math.round(len / 0.16));
    for (let i = 0; i < nb; i++) {
      const x = -len / 2 + ((i + 0.5) / nb) * len;
      if (balGeo) kit.add(balGeo, TRIM, SURF.paint, T(x, FL + 0.125, 0), { uv: (px, py) => [py, px], shadow: balShadow });
      else kit.box(0.05, RH - 0.12, 0.05, TRIM, SURF.paint, T(x, FL + 0.125 + (RH - 0.12) / 2, 0));
    }
    kit.pop();
    const wx = alongX ? cx + mid : cx + fixed, wz = alongX ? cz + fixed : cz + mid;
    col.addBox(wx, wz, (b - a) / 2, 0.07, alongX ? 0 : Math.PI / 2, y0 + FL, y0 + FL + RH + 0.04);
  }
  // резные кронштейны у столбов под обвязкой
  kit.setLayer('detail');
  // профиль кронштейна: прямой угол у столба и обвязки, снизу - вогнутая четверть круга
  const brW = 0.42, brH = 0.42;
  const prof = [[0, brH], [brW, brH]];
  for (let i = 1; i <= 9; i++) {
    const a = (Math.PI / 2) * (1 - i / 9);
    prof.push([brW - brW * Math.cos(a), brH * Math.sin(a) * 0.92 + brH * 0.08 * (1 - i / 9)]);
  }
  const brGeo = slabGeo(prof, 0.05, 0);
  for (const [px, pz] of posts) {
    const dirs = [];
    const onX = Math.abs(Math.abs(pz) - (HW - 0.1)) < 0.01; // столб на северной/южной стороне
    const onZ = Math.abs(Math.abs(px) - (HW - 0.1)) < 0.01;
    if (onX) { if (px > -HW + 0.2) dirs.push([-1, 0]); if (px < HW - 0.2) dirs.push([1, 0]); }
    if (onZ) { if (pz > -HW + 0.2) dirs.push([0, -1]); if (pz < HW - 0.2) dirs.push([0, 1]); }
    for (const [dx, dz] of dirs) {
      const ry = Math.atan2(-dz, dx);
      kit.add(brGeo, TRIM, SURF.paint, T(px + dx * 0.085, FL + PH - brH, pz + dz * 0.085, 0, ry, 0), { uv: (x, y) => [x, y] });
    }
  }

  // ---------- внутри: лавки вдоль северной и восточной стороны, чайный столик, табуреты ----------
  kit.setLayer('solid');
  const benchAlong = (side) => {
    const alongX = side === 0;
    const fixed = side === 0 ? -HW + 0.42 : HW - 0.42;
    for (const [a, b] of spans) {
      const len = b - a - 0.25, mid = (a + b) / 2;
      const m = alongX ? T(mid, 0, fixed) : T(fixed, 0, mid, 0, Math.PI / 2, 0);
      kit.push(m);
      for (let j = 0; j < 3; j++) kit.box(len, 0.04, 0.12, new THREE.Color(WOOD).multiplyScalar(0.9 + hash2(j, side) * 0.2), SURF.wood, T(0, FL + 0.42, -0.13 + j * 0.13), { bevel: 0.01 });
      for (const sx of [-1, 1]) kit.box(0.06, 0.4, 0.34, WOOD_D, SURF.wood, T(sx * (len / 2 - 0.12), FL + 0.2, 0), { bevel: 0.012 });
      kit.pop();
      const wx = alongX ? cx + mid : cx + fixed, wz = alongX ? cz + fixed : cz + mid;
      col.addBox(wx, wz, len / 2, 0.2, alongX ? 0 : Math.PI / 2, y0 + FL, y0 + FL + 0.44);
    }
  };
  benchAlong(0);
  benchAlong(2);
  // чайный столик: круглая столешница на ножке-тумбе
  const TT = FL + 0.5;
  const tseg = kit.seg(12, 18, 24);
  kit.add(new THREE.CylinderGeometry(0.58, 0.58, 0.05, tseg), WOOD, SURF.lacq, T(0, TT - 0.025, 0), { uv: (x, y, z) => [x, z] });
  kit.add(new THREE.TorusGeometry(0.58, 0.022, 4, tseg).rotateX(Math.PI / 2), WOOD_D, SURF.lacq, T(0, TT - 0.03, 0));
  kit.add(lathe([[0.0, 0], [0.09, 0], [0.09, 0.03], [0.05, 0.08], [0.06, 0.2], [0.04, 0.32], [0.07, 0.42], [0.1, 0.45], [0.0, 0.45]], kit.seg(6, 8, 12)), WOOD_D, SURF.wood, T(0, FL, 0), { uv: (x, y) => [y, x] });
  for (let i = 0; i < 3; i++) {
    const a = (i / 3) * TAU;
    kit.box(0.34, 0.05, 0.06, WOOD_D, SURF.wood, T(Math.cos(a) * 0.15, FL + 0.03, Math.sin(a) * 0.15, 0, -a, 0), { bevel: 0.012 });
  }
  col.addBox(cx, cz, 0.5, 0.5, 0, y0 + FL, y0 + TT);
  // скатерть-дорожка, ваза с цветами, свеча в банке
  kit.setLayer('detail');
  kit.add(new THREE.CylinderGeometry(0.4, 0.4, 0.006, tseg), '#f2ead8', SURF.fabric, T(0, TT + 0.003, 0), { uv: (x, y, z) => [x, z] });
  kit.add(lathe([[0, 0], [0.05, 0], [0.07, 0.05], [0.065, 0.1], [0.035, 0.16], [0.045, 0.2], [0, 0.2]], kit.seg(6, 8, 10)), '#4f8fb8', SURF.ceramic, T(0.18, TT + 0.006, -0.12), { uv: (x, y) => [y, x] });
  const flowers = ['#f4d24a', '#f07a8a', '#ffffff', '#f2a03a', '#c77be0'];
  for (let i = 0; i < 6; i++) {
    const a = (i / 6) * TAU, r = 0.03 + (i % 2) * 0.03;
    const fx = 0.18 + Math.cos(a) * r, fz = -0.12 + Math.sin(a) * r, fy = TT + 0.24 + (i % 3) * 0.04;
    kit.add(tube([new THREE.Vector3(0.18, TT + 0.15, -0.12), new THREE.Vector3(fx, fy, fz)], 0.005, 3), '#4f8a3a', SURF.plain, null);
    kit.sphere(0.028, flowers[i % 5], SURF.fabric, T(fx, fy, fz, 0, 0, 0, 1, 0.7, 1), { seg: 6 });
  }
  kit.cyl(0.045, 0.045, 0.09, '#d9e6ea', SURF.glass, T(-0.2, TT + 0.05, 0.1), { seg: 8 });
  kit.cyl(0.03, 0.03, 0.05, '#fbf3e0', SURF.plain, T(-0.2, TT + 0.035, 0.1), { seg: 8 });
  kit.add(new THREE.SphereGeometry(0.014, 6, 4), '#ffd27a', glow(5, PAT.plain, true), T(-0.2, TT + 0.085, 0.1, 0, 0, 0, 1, 1.8, 1));
  // табуреты
  kit.setLayer('solid');
  for (const [sx, sz] of [[-1.0, 0.75], [0.75, 1.0]]) {
    kit.add(new THREE.CylinderGeometry(0.2, 0.2, 0.05, kit.seg(8, 10, 14)), WOOD, SURF.wood, T(sx, FL + 0.38, sz), { uv: (x, y, z) => [x, z] });
    for (let i = 0; i < 3; i++) {
      const a = (i / 3) * TAU + 0.5;
      kit.cyl(0.02, 0.025, 0.38, WOOD_D, SURF.wood, T(sx + Math.cos(a) * 0.12, FL + 0.19, sz + Math.sin(a) * 0.12, Math.sin(a) * 0.12, 0, -Math.cos(a) * 0.12), { seg: 5 });
    }
    col.addBox(cx + sx, cz + sz, 0.17, 0.17, 0, y0 + FL, y0 + FL + 0.4);
  }

  // тропа от хаба упирается в юго-западный угол: от неё к обоим входам - плоские камни-ступеньки
  kit.setLayer('detail');
  {
    const from = [-HW - 0.9, HW + 0.9];
    for (const to of [[-HW - 0.75, 0.9], [-0.9, HW + 0.75]]) {
      for (let i = 0; i < 4; i++) {
        const t = (i + 0.5) / 4;
        const px = from[0] + (to[0] - from[0]) * t + (rand() - 0.5) * 0.15, pz = from[1] + (to[1] - from[1]) * t + (rand() - 0.5) * 0.15;
        const gy = heightAt(cx + px, cz + pz) - y0;
        kit.add(stoneGeo(0.24 + rand() * 0.06, 60 + i * 3 + to[0], kit.seg(0, 1, 1), 0.7, 0.25), stoneColor('#a39e94', 80 + i), SURF.stone, T(px, gy - 0.04, pz, 0, rand() * TAU, 0), { shadow: false });
        ctx.exclude(cx + px, cz + pz, 0.4);
      }
    }
  }
  // ящики с цветами у углов снаружи (кроме юго-западного - туда приходит тропа)
  for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1]]) {
    const px = sx * (HW + 0.55), pz = sz * (HW + 0.55);
    kit.setLayer('solid');
    kit.box(0.55, 0.4, 0.55, '#7f5634', SURF.planks, T(px, 0.2, pz, 0, 0.0, 0), { bevel: 0.02 });
    kit.box(0.6, 0.05, 0.6, '#6a4528', SURF.wood, T(px, 0.41, pz), { bevel: 0.01 });
    kit.setLayer('detail');
    kit.add(stoneGeo(0.25, 40 + sx * 3 + sz, kit.seg(0, 1, 1), 0.5, 0.45), '#3f7a34', SURF.fabric, T(px, 0.36, pz));
    for (let i = 0; i < kit.seg(4, 7, 9); i++) {
      const a = rand() * TAU, r = rand() * 0.2;
      kit.sphere(0.04 + rand() * 0.02, flowers[i % 5], SURF.fabric, T(px + Math.cos(a) * r, 0.5 + rand() * 0.08, pz + Math.sin(a) * r), { seg: 5 });
    }
    col.addBox(cx + px, cz + pz, 0.3, 0.3, 0, y0 - 0.3, y0 + 0.43);
  }

  // ---------- крыша (C5): гонт рядами, коньки, навершие, подшивка снизу, стропила, фонарь ----------
  kit.roof('gazebo', { x0: cx - HW - 0.4, x1: cx + HW + 0.4, z0: cz - HW - 0.4, z1: cz + HW + 0.4 });
  const E = HW + 0.62;          // полуширина свеса
  const EY = BY + 0.12;         // высота свеса
  const RISE = 2.35;
  const PY = EY + RISE;
  const rows = kit.seg(7, 9, 11);
  for (let side = 0; side < 4; side++) {
    const ry = (side * Math.PI) / 2;
    kit.push(T(0, 0, 0, 0, ry, 0));
    // скат: от свеса (z = E) к коньку (z = 0); строим ряды в координатах ската
    const slopeLen = Math.hypot(E, RISE);
    const pitch = Math.atan2(RISE, E);
    for (let r = 0; r < rows; r++) {
      const t0 = r / rows, t1 = (r + 1.25) / rows;
      const z0 = E * (1 - t0), z1 = Math.max(0, E * (1 - Math.min(1, t1)));
      const yA = EY + RISE * t0, yB = EY + RISE * Math.min(1, t1);
      const w0 = z0, w1 = z1; // полуширина ряда сужается к коньку (пирамида)
      // трапеция: нижний край чуть отогнут наружу (ступенька ряда)
      const th = 0.045;
      const pos = [
        -w0, yA - th, z0 + 0.04, w0, yA - th, z0 + 0.04, w1, yB, z1, -w1, yB, z1,
        -w0, yA, z0 + 0.02, w0, yA, z0 + 0.02, w1, yB + th, z1, -w1, yB + th, z1,
      ];
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
      // верх (4,5,6,7), низ (0,1,2,3), передний торец (0,1,5,4)
      g.setIndex([4, 5, 6, 4, 6, 7, 0, 2, 1, 0, 3, 2, 0, 1, 5, 0, 5, 4]);
      g.computeVertexNormals();
      const c = new THREE.Color(ROOF).multiplyScalar(0.9 + hash2(r, side, 3) * 0.18);
      kit.add(g, c, SURF.shingle, null, {
        uv: (x, y, z) => [x, (E - z) / Math.cos(pitch) + r * 0.013], bake: false,
      });
      void slopeLen;
    }
    // подшивка снизу (видна изнутри): доски по скату
    const under = new THREE.BufferGeometry();
    under.setAttribute('position', new THREE.Float32BufferAttribute([-E, EY - 0.05, E, E, EY - 0.05, E, 0, PY - 0.08, 0], 3));
    under.setIndex([0, 2, 1]);
    under.computeVertexNormals();
    kit.add(under, '#c79a68', SURF.planks, null, { uv: (x, y, z) => [x, (E - z) * 1.2], bake: false });
    // лобовая доска по краю свеса
    kit.box(E * 2 + 0.05, 0.16, 0.04, TRIM, SURF.paint, T(0, EY - 0.06, E + 0.02), { bevel: 0.01, bake: false });
    // стропила (видны снизу)
    for (const t of [-0.5, 0, 0.5]) {
      const a = new THREE.Vector3(t * E, EY - 0.08, E - 0.05), b = new THREE.Vector3(0, PY - 0.15, 0);
      const d = b.clone().sub(a), mid = a.clone().add(b).multiplyScalar(0.5);
      const m = new THREE.Matrix4().lookAt(a, b, new THREE.Vector3(0, 1, 0)).setPosition(mid);
      kit.box(0.08, 0.12, d.length(), WOOD_D, SURF.wood, m, { bevel: 0.012, bake: false });
    }
    kit.pop();
  }
  // коньки по рёбрам пирамиды
  for (let k = 0; k < 4; k++) {
    const sx = k & 1 ? 1 : -1, sz = k & 2 ? 1 : -1;
    const a = new THREE.Vector3(sx * (E + 0.05), EY + 0.02, sz * (E + 0.05)), b = new THREE.Vector3(0, PY + 0.05, 0);
    kit.add(tube([a, b], 0.06, kit.seg(4, 6, 8), true), '#7e3328', SURF.plain, null, { bake: false });
  }
  // навершие
  kit.add(lathe([[0, 0], [0.16, 0], [0.12, 0.08], [0.06, 0.12], [0.1, 0.22], [0.06, 0.32], [0.02, 0.36], [0.015, 0.6], [0, 0.66]], kit.seg(6, 8, 12)), '#d9b45a', SURF.metal, T(0, PY - 0.02, 0), { uv: (x, y) => [y, x], bake: false });
  // фонарь на цепочке в центре
  const LY = EY - 0.9;
  kit.add(tube([new THREE.Vector3(0, PY - 0.2, 0), new THREE.Vector3(0, LY + 0.42, 0)], 0.012, 4), '#2e3034', SURF.iron, null, { bake: false });
  kit.cyl(0.13, 0.17, 0.12, '#2e3034', SURF.iron, T(0, LY + 0.38, 0), { seg: 6, bake: false });
  kit.cyl(0.14, 0.12, 0.3, '#ffd9a0', glow(1.3, PAT.glass), T(0, LY + 0.17, 0), { seg: 6, open: true, bake: false });
  for (let i = 0; i < 6; i++) {
    const a = (i / 6) * TAU;
    kit.box(0.02, 0.32, 0.02, '#2e3034', SURF.iron, T(Math.cos(a) * 0.135, LY + 0.17, Math.sin(a) * 0.135, 0, -a, 0), { bevel: 0.004, bake: false });
  }
  kit.cyl(0.12, 0.08, 0.05, '#2e3034', SURF.iron, T(0, LY, 0), { seg: 6, bake: false });
  kit.add(new THREE.SphereGeometry(0.06, 6, 4), '#ffe2b0', glow(4.5, PAT.plain, true), T(0, LY + 0.15, 0, 0, 0, 0, 1, 1.5, 1), { bake: false });

  // дальний вид: пол и столбы (крыша-меш остаётся всегда)
  kit.setLayer('far');
  kit.add(new THREE.BoxGeometry(HW * 2, FL, HW * 2), WOOD_D, SURF.wood, T(0, FL / 2, 0), { bake: false });
  for (const [px, pz] of posts) kit.add(new THREE.BoxGeometry(0.2, PH, 0.2), TRIM, SURF.paint, T(px, FL + PH / 2, pz), { bake: false });
  kit.add(new THREE.BoxGeometry(HW * 2 + 0.3, 0.22, HW * 2 + 0.3), WOOD_D, SURF.wood, T(0, BY, 0), { bake: false });
  kit.pop();
  ctx.exclude(cx, cz, HW + 1.0);
}
