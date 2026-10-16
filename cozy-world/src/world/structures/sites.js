// Дальние места: пирс на озере с лодкой, зимний бревенчатый домик, ферма с амбаром.
// Каждое - своя группа кита (1-3 draw call), крыши - отдельные меши (C5), вдали - упрощённая копия.

import * as THREE from 'three';
import { heightAt, WATER_Y, LAKE } from '../terrain-data.js';
import { mulberry32, hash2, smoothstep, TAU } from '../../util/math.js';
import { SURF, PAT, glow, T, TY, addLog, stoneGeo, lathe, tube, sagPoints, slabGeo, bevelBox } from './kit.js';
import { plankBench, stumpSeat, barrel, mailbox, lantern, woodLabel, fenceRun, along } from './hub.js';
import { buildParticles } from './fx.js';

const V3 = (x, y, z) => new THREE.Vector3(x, y, z);

// Треугольник/многоугольник-«щипец» из досок: контур [[x, y]...] в плоскости XY, толщина d.
function gableGeo(outline, d) { return slabGeo(outline, d, 0); }

// Сугроб: сплющенная полусфера с шумом.
export function snowMound(kit, x, y, z, rx, ry, rz, seed, xf = null) {
  const g = new THREE.SphereGeometry(1, kit.seg(7, 9, 12), kit.seg(4, 5, 6), 0, TAU, 0, Math.PI / 2);
  const P = g.getAttribute('position');
  for (let i = 0; i < P.count; i++) {
    const px = P.getX(i), py = P.getY(i), pz = P.getZ(i);
    const n = hash2(Math.round(px * 3 + seed), Math.round(pz * 3), seed) * 0.12;
    P.setXYZ(i, px * rx * (1 + n), py * ry * (1 + n * 0.5), pz * rz * (1 + n));
  }
  g.computeVertexNormals();
  kit.add(g, '#f2f6fb', SURF.snow, (xf || new THREE.Matrix4()).clone().multiply(T(x, y - 0.02, z)), { shadow: false });
}

// Сосульки вдоль кромки: от (x0..x1) на высоте y, z - по линии кромки.
function icicles(kit, x0, x1, y, z, n, seed, maxLen = 0.4) {
  const rand = mulberry32(seed);
  for (let i = 0; i < n; i++) {
    const x = x0 + (x1 - x0) * ((i + 0.2 + rand() * 0.6) / n);
    const L = 0.08 + rand() * rand() * maxLen;
    kit.add(new THREE.ConeGeometry(0.025 + L * 0.06, L, 5, 1), '#dcecf8', [0.15, 0, 0.12, PAT.glass], T(x, y - L / 2, z, Math.PI, 0, 0), { shadow: false, bake: false });
  }
}

// ---------- пирс и лодка ----------

export function pierFrame() {
  const dir = new THREE.Vector2(-20 - LAKE.x, 20 - LAKE.z).normalize();
  let shore = LAKE.r + 14;
  for (let r = LAKE.r + 14; r > 0; r -= 0.5) {
    if (heightAt(LAKE.x + dir.x * r, LAKE.z + dir.y * r) < WATER_Y + 0.1) { shore = r; break; }
  }
  const len = 11, topY = WATER_Y + 0.62;
  const cx = LAKE.x + dir.x * (shore + 1.5 - len / 2), cz = LAKE.z + dir.y * (shore + 1.5 - len / 2);
  const ry = Math.atan2(-dir.y, dir.x);
  return { dir, len, topY, cx, cz, ry };
}

export function buildPier(ctx) {
  const { kit, col } = ctx;
  const { dir, len, topY, cx, cz, ry } = pierFrame();
  const rand = mulberry32(91);
  kit.group('pier', { cx, cz, r: 8 });
  kit.push(T(cx, topY, cz, 0, ry, 0)); // x - вдоль пирса к берегу, z - поперёк, y = 0 - верх настила
  // старое дерево: серо-коричневое, у воды - тёмная полоса водорослей
  const old = new THREE.Color('#8a7358'), wet = new THREE.Color('#3b4a33');
  const pileColor = (x, y, z, nx, ny, nz, out, wx, wy) => {
    out.copy(old).multiplyScalar(0.85 + 0.25 * hash2(Math.round(wx * 3), Math.round(wy * 9), 4));
    out.lerp(wet, smoothstep(WATER_Y + 0.25, WATER_Y - 0.05, wy) * 0.85);
  };

  // настил: доски поперёк с зазорами
  const PW = 0.2, GAP = 0.035, HW = 1.2;
  const np = Math.floor(len / (PW + GAP));
  for (let i = 0; i < np; i++) {
    const x = -len / 2 + (i + 0.5) * (len / np);
    const c = new THREE.Color('#a5835c').multiplyScalar(0.78 + rand() * 0.32);
    if (rand() < 0.12) c.lerp(new THREE.Color('#7d7568'), 0.5); // выцветшие
    const l = HW * 2 + (rand() - 0.5) * 0.12;
    kit.box(PW, 0.045, l, c, SURF.wood, T(x, -0.0225 + (rand() - 0.5) * 0.008, (rand() - 0.5) * 0.06, 0, (rand() - 0.5) * 0.03, (rand() - 0.5) * 0.02), { bevel: 0.01, shadow: false });
  }
  kit.shadowOnly(new THREE.BoxGeometry(len, 0.05, HW * 2), T(0, -0.025, 0));
  // гвозди рядами
  if (kit.q > 1) {
    kit.setLayer('detail');
    for (let i = 0; i < np; i += 1) {
      const x = -len / 2 + (i + 0.5) * (len / np);
      for (const z of [-0.95, 0, 0.95]) kit.cyl(0.007, 0.007, 0.004, '#2c2a28', SURF.iron, T(x, 0.002, z), { seg: 4 });
    }
    kit.setLayer('solid');
  }
  // лаги и поперечины
  for (const z of [-0.95, 0, 0.95]) kit.box(len, 0.16, 0.11, '#6b5640', SURF.wood, T(0, -0.13, z), { bevel: 0.015 });
  const pilesX = [-len / 2 + 0.3, -len / 2 + 3.0, -len / 2 + 5.7, -len / 2 + 8.4];
  for (const x of pilesX) kit.box(0.14, 0.14, HW * 2 + 0.4, '#5f4c38', SURF.wood, T(x, -0.27, 0), { bevel: 0.015 });
  // сваи: у края - высокие швартовые с канатом
  const pileTop = (i, s) => (i === 0 ? 0.75 : s > 0 ? 0.9 : -0.06);
  pilesX.forEach((x, i) => {
    for (const s of [-1, 1]) {
      const top = pileTop(i, s);
      const bottom = -3.0;
      const h = top - bottom;
      addLog(kit, h, 0.11, T(x, bottom + h / 2, s * (HW + 0.1), 0, 0, Math.PI / 2), { seed: 950 + i * 2 + (s > 0 ? 1 : 0), bark: '#7a6550', barkDark: '#4f4234', wood: '#b49a78', seg: kit.seg(6, 8, 10), rings: 2 });
      // водоросли/мокрая полоса поверх коры
      kit.cyl(0.118, 0.118, 0.32, pileColor, SURF.bark, T(x, WATER_Y + 0.02 - topY, s * (HW + 0.1)), { seg: kit.seg(6, 8, 10), open: true, shadow: false });
      if (top > 0.1) {
        kit.setLayer('detail');
        for (const y of [top - 0.18, top - 0.26]) kit.add(new THREE.TorusGeometry(0.12, 0.022, 4, kit.seg(8, 10, 12)), '#c9b28a', SURF.fabric, T(x, y, s * (HW + 0.1), Math.PI / 2, 0, 0));
        kit.setLayer('solid');
      }
    }
  });
  // перила из каната вдоль правого борта (z+)
  kit.setLayer('detail');
  for (let i = 0; i < pilesX.length - 1; i++) {
    const a = V3(pilesX[i], pileTop(i, 1) - 0.2, HW + 0.1), b = V3(pilesX[i + 1], pileTop(i + 1, 1) - 0.2, HW + 0.1);
    kit.add(tube(sagPoints(a, b, 0.22, kit.seg(5, 7, 9)), 0.022, kit.seg(4, 4, 6)), '#c9b28a', SURF.fabric, null, { uv: (x, y, z) => [x * 3, y * 3] });
  }
  kit.setLayer('solid');
  // лестница в воду на дальнем конце
  for (const s of [-0.28, 0.28]) kit.box(0.06, 1.3, 0.06, '#5f4c38', SURF.wood, T(-len / 2 - 0.08, -0.55, s, 0, 0, -0.1), { bevel: 0.01 });
  for (let k = 0; k < 4; k++) kit.box(0.05, 0.04, 0.6, '#6b5640', SURF.wood, T(-len / 2 - 0.1 + k * 0.03, -0.25 - k * 0.3, 0), { bevel: 0.008, shadow: false });

  // на пирсе: ведро, удочка, бухта каната, ящик, фонарь на швартовой свае
  kit.setLayer('detail');
  {
    const bx = -2.0, bz = 0.7;
    kit.add(lathe([[0, 0], [0.12, 0], [0.13, 0.02], [0.15, 0.24], [0.155, 0.25], [0.145, 0.25], [0.135, 0.03], [0, 0.03]], kit.seg(8, 10, 14)), '#8d969e', SURF.metal, T(bx, 0, bz), { uv: (x, y) => [y, x] });
    kit.add(new THREE.TorusGeometry(0.15, 0.006, 3, 10, Math.PI), '#5d646a', SURF.metal, T(bx, 0.25, bz, 0, 0.4, 0));
    kit.cyl(0.13, 0.13, 0.01, '#2c4654', [0.6, 0, 0, PAT.plain], T(bx, 0.2, bz), { seg: kit.seg(8, 10, 14) });
    // удочка прислонена к свае
    const rodA = V3(pilesX[1] + 0.25, 0.02, HW - 0.05), rodB = V3(pilesX[1] - 1.4, 1.65, HW + 0.2);
    kit.add(tube([rodA, rodB], (t) => 0.018 * (1 - t) + 0.005 * t, 4), '#4a3424', SURF.lacq, null);
    kit.cyl(0.035, 0.035, 0.05, '#999fa5', SURF.metal, along(rodA.clone().lerp(rodB, 0.12), rodB.clone().sub(rodA)).multiply(T(0, 0, 0, 0, 0, Math.PI / 2)), { seg: 8 });
    // бухта каната
    for (let k = 0; k < 3; k++) kit.add(new THREE.TorusGeometry(0.2 - k * 0.035, 0.022, 4, kit.seg(8, 12, 14)), '#c9b28a', SURF.fabric, T(1.2, 0.022 + k * 0.03, -0.7, Math.PI / 2, 0, 0));
  }
  kit.setLayer('solid');
  // ящик с рыбацкими снастями
  kit.box(0.6, 0.36, 0.42, '#7a5a3a', SURF.planks, T(0.4, 0.18, -0.75, 0, 0.3, 0), { bevel: 0.02 });
  kit.box(0.64, 0.04, 0.46, '#6a4a2e', SURF.wood, T(0.4, 0.38, -0.75, 0, 0.3, 0), { bevel: 0.01 });
  col.addBox(cx + Math.cos(ry) * 0.4 + Math.sin(ry) * -0.75, cz - Math.sin(ry) * 0.4 + Math.cos(ry) * -0.75, 0.32, 0.23, ry + 0.3, topY - 0.1, topY + 0.4);
  // фонарь на крюке у дальней сваи
  {
    const x = pilesX[0], z = HW + 0.1, top = pileTop(0, 1);
    kit.box(0.04, 0.04, 0.32, '#2e3034', SURF.iron, T(x, top - 0.06, z - 0.16), { sharp: true, shadow: false });
    kit.setLayer('detail');
    kit.add(new THREE.TorusGeometry(0.03, 0.006, 3, 8), '#2e3034', SURF.iron, T(x, top - 0.1, z - 0.3, 0, Math.PI / 2, 0));
    kit.setLayer('solid');
    const ly = top - 0.36;
    kit.cyl(0.07, 0.09, 0.05, '#2e3034', SURF.iron, T(x, ly + 0.2, z - 0.3), { seg: 6, shadow: false });
    kit.cyl(0.08, 0.08, 0.18, '#ffd9a0', glow(1.2, PAT.glass), T(x, ly + 0.09, z - 0.3), { seg: 6, open: true, shadow: false });
    kit.add(new THREE.SphereGeometry(0.035, 5, 4), '#ffe2b0', glow(4.5, PAT.plain, true), T(x, ly + 0.08, z - 0.3, 0, 0, 0, 1, 1.5, 1), { shadow: false });
    kit.cyl(0.08, 0.06, 0.03, '#2e3034', SURF.iron, T(x, ly - 0.01, z - 0.3), { seg: 6, shadow: false });
  }
  kit.pop();
  col.addBox(cx, cz, len / 2, HW, ry, topY - 0.3, topY);
  const along2 = (t, s) => [cx + Math.cos(ry) * t + Math.sin(ry) * s, cz - Math.sin(ry) * t + Math.cos(ry) * s];
  pilesX.forEach((x, i) => {
    for (const s of [-1, 1]) {
      if (pileTop(i, s) > 0.1) { const [px, pz] = along2(x, s * (HW + 0.1)); col.addCircle(px, pz, 0.13, topY - 1, topY + pileTop(i, s)); }
    }
  });

  // скамейка на берегу у начала пирса - смотрит на озеро
  {
    const [bx, bz] = along2(len / 2 + 1.6, -2.4);
    plankBench(ctx, bx, bz, Math.atan2(dir.x, dir.y), 977);
    kit.group('pier');
  }

  // дальний вид
  kit.setLayer('far');
  kit.push(T(cx, topY, cz, 0, ry, 0));
  kit.add(new THREE.BoxGeometry(len, 0.2, HW * 2), '#8f7556', SURF.wood, T(0, -0.1, 0), { bake: false });
  kit.pop();

  // лодка носом к пирсу, привязана канатом к утке на краю настила
  const BL = 3.3, bry = ry + Math.PI + 0.06;
  const [bx, bz] = along2(-2.6, -(HW + 0.28 + BL / 2));
  {
    kit.setLayer('solid');
    const cl = along2(-2.45, -HW + 0.12);
    kit.push(T(cl[0], topY, cl[1], 0, ry, 0));
    kit.box(0.26, 0.05, 0.07, '#3a3c40', SURF.iron, T(0, 0.045, 0), { bevel: 0.012, shadow: false });
    kit.box(0.06, 0.05, 0.05, '#3a3c40', SURF.iron, T(0, 0.02, 0), { sharp: true, shadow: false });
    kit.add(new THREE.TorusGeometry(0.035, 0.016, 4, 8), '#c9b28a', SURF.fabric, T(0.05, 0.05, 0, Math.PI / 2, 0, 0), { shadow: false });
    kit.pop();
    const bow = V3(bx + Math.sin(bry) * (BL / 2 - 0.02), WATER_Y + 0.3, bz + Math.cos(bry) * (BL / 2 - 0.02));
    const tie = V3(cl[0], topY + 0.06, cl[1]);
    kit.setLayer('detail');
    kit.add(tube(sagPoints(tie, bow, 0.18, kit.seg(4, 6, 8)), 0.016, 4), '#c9b28a', SURF.fabric, null, { uv: (x, y, z) => [x * 3 + z * 3, y * 3] });
    kit.setLayer('solid');
  }
  buildBoat(ctx, [bx, bz], bry);
  ctx.exclude(bx, bz, 2.2); // кувшинки и камыш не прорастают сквозь лодку
  ctx.exclude(...along2(len / 2 + 1.6, -2.4), 1.0);
}

// Лодка-плоскодонка: обшивка внахлёст (узор досок), банки, вёсла; качается на воде (своя группа с опорой).
function buildBoat(ctx, [bx, bz], ry) {
  const { kit, col, anim } = ctx;
  const y0 = WATER_Y;
  const L = 3.3, B = 1.15, D = 0.5;
  let node = null;
  kit.group('boat', {
    cx: bx, cz: bz, r: 2.5, pivot: { x: bx, y: y0, z: bz, ry }, bake: () => [1, 0],
    onNode: (n) => { node = n; },
  });
  // ширина и седловатость по длине: t = -1 корма (транец), +1 нос (острый)
  const half = (t) => (t > 0 ? (B / 2) * Math.pow(Math.max(0, 1 - t * t), 0.55) : (B / 2) * (1 - 0.42 * t * t));
  const sheer = (t) => 0.06 * t * t + (t > 0 ? 0.1 * t * t * t : 0);
  const NX = kit.seg(8, 12, 16), NS = kit.seg(5, 6, 8);
  // сечение: j = 0..NS от левого планширя через киль к правому
  const sec = (t, j, inset) => {
    const w = Math.max(0.02, half(t) - inset);
    const a = (j / NS) * Math.PI; // 0 - левый борт, PI - правый
    const yTop = 0.22 + sheer(t) - inset * 0.3;
    const yBot = -D + 0.22 + inset + 0.05 * Math.abs(t) * Math.abs(t);
    const s = Math.sin(a), c = Math.cos(a);
    // борт почти вертикальный, дно плоское: суперэллипс
    const k = 0.32;
    const x = -Math.sign(c) * Math.pow(Math.abs(c), k) * w;
    const y = yTop + (yBot - yTop) * Math.pow(s, 0.45);
    return [x, y];
  };
  const shell = (inset, flip) => {
    const pos = [], uv = [], idx = [];
    for (let i = 0; i <= NX; i++) {
      const t = -1 + (2 * i) / NX;
      const zz = (t * L) / 2;
      let arc = 0, prev = null;
      for (let j = 0; j <= NS; j++) {
        const [x, y] = sec(t, j, inset);
        if (prev) arc += Math.hypot(x - prev[0], y - prev[1]);
        prev = [x, y];
        pos.push(x, y, zz);
        uv.push(zz, arc);
      }
    }
    const R = NS + 1;
    for (let i = 0; i < NX; i++) {
      for (let j = 0; j < NS; j++) {
        const a = i * R + j, b = a + 1, c = a + R, d = c + 1;
        if (flip) idx.push(a, c, b, b, c, d); else idx.push(a, b, c, b, d, c);
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setIndex(idx);
    g.computeVertexNormals();
    return g;
  };
  const paint = new THREE.Color('#3f7f96'), stripe = new THREE.Color('#f2ebdc'), bottom = new THREE.Color('#9a3a2c');
  const outer = shell(0, false);
  kit.add(outer, (x, y, z, nx, ny, nz, out) => {
    out.copy(y > 0.12 + sheer((2 * z) / L) ? stripe : y < -0.12 ? bottom : paint);
  }, SURF.planks, null, { uv: 'keep' });
  const inner = shell(0.035, true);
  kit.add(inner, '#b88b5a', SURF.planks, null, { uv: 'keep', shadow: false });
  // планширь: кромка поверх борта
  const rim = [];
  for (let s = -1; s <= 1; s += 2) {
    const pts = [];
    for (let i = 0; i <= NX; i++) {
      const t = -1 + (2 * i) / NX;
      const [x, y] = sec(t, s < 0 ? 0 : NS, 0.017);
      pts.push(V3(x, y + 0.012, (t * L) / 2));
    }
    rim.push(pts);
    kit.add(tube(pts, 0.026, kit.seg(4, 5, 6)), '#6e4a2c', SURF.lacq, null, { uv: (x, y, z) => [z, x] });
  }
  // транец (корма)
  const tw = half(-1);
  kit.add(slabGeo([[-tw, 0.24], [tw, 0.24], [tw * 0.7, -D + 0.3], [-tw * 0.7, -D + 0.3]], 0.04, 0), paint, SURF.planks, T(0, 0, -L / 2 + 0.01), { uv: (x, y) => [x, y] });
  // банки (сиденья) и вёсла
  for (const t of [-0.55, 0.2]) {
    const w = half(t) * 2 - 0.08;
    kit.box(w, 0.035, 0.24, '#c49a68', SURF.wood, T(0, 0.04, (t * L) / 2), { bevel: 0.01 });
  }
  kit.setLayer('detail');
  for (const s of [-1, 1]) {
    kit.push(T(s * 0.22, 0.1, -0.15, 0, 0.06 * s, 0));
    kit.cyl(0.022, 0.022, 2.1, '#d2ab78', SURF.wood, T(0, 0, 0, Math.PI / 2, 0, 0), { seg: 6 });
    kit.box(0.14, 0.02, 0.5, '#d2ab78', SURF.wood, T(0, 0, 1.15), { bevel: 0.008 });
    kit.pop();
    // уключины
    kit.add(new THREE.TorusGeometry(0.035, 0.008, 3, 8, Math.PI), '#4d5257', SURF.metal, T(s * (half(0) - 0.01), 0.26, 0.05, 0, Math.PI / 2, 0));
  }
  // канат от носа к швартовой свае
  const bowIn = V3(0, 0.3, L / 2 - 0.05);
  kit.add(new THREE.TorusGeometry(0.04, 0.01, 3, 8), '#4d5257', SURF.metal, T(0, 0.3, L / 2 - 0.02, 0, 0, 0));
  void bowIn;
  kit.setLayer('solid');
  // качка: медленно и чуть-чуть
  anim.push((dt, t) => {
    if (!node) return;
    node.position.y = y0 + Math.sin(t * 0.9) * 0.025 + Math.sin(t * 1.7 + 1) * 0.012;
    node.rotation.set(Math.sin(t * 0.8 + 0.5) * 0.02, ry + Math.sin(t * 0.23) * 0.03, Math.sin(t * 1.1) * 0.03, 'YXZ');
  });
  // коллизия: дно и борта (можно запрыгнуть внутрь)
  const c = Math.cos(ry), s = Math.sin(ry);
  col.addBox(bx, bz, B / 2 - 0.05, L / 2 - 0.25, ry, y0 - 0.6, y0 - 0.14);
  for (const sd of [-1, 1]) {
    const ox = sd * (B / 2 - 0.04);
    col.addBox(bx + ox * c, bz - ox * s, 0.04, L / 2 - 0.3, ry, y0 - 0.6, y0 + 0.24);
  }
  void rim;
}

// ---------- зимний домик ----------

export function buildCabin(ctx) {
  const { kit, col } = ctx;
  const cx = -16, cz = -176;
  const y = heightAt(cx, cz);
  const rand = mulberry32(41);
  const W = 8, D = 6, H = 3.1, R = 0.155, OV = 0.3;
  const step = R * 2 * 0.92;
  kit.group('cabin', { cx, cz, r: 7 });
  kit.push(T(cx, y, cz));

  // проёмы: стена f (фасад z+), b (зад z-), l (x-), r (x+); a - вдоль стены
  const holes = {
    f: [{ a0: -0.6, a1: 0.6, y0: 0, y1: 2.15 }, { a0: -2.95, a1: -1.85, y0: 1.0, y1: 2.15 }, { a0: 1.85, a1: 2.95, y0: 1.0, y1: 2.15 }],
    b: [{ a0: 1.2, a1: 2.2, y0: 1.1, y1: 2.1 }],
    l: [{ a0: -1.6, a1: -0.6, y0: 1.0, y1: 2.1 }],
    r: [],
  };
  const walls = {
    f: { len: W, at: (a, yy) => T(a, yy, D / 2), axis: 0 },
    b: { len: W, at: (a, yy) => T(a, yy, -D / 2), axis: 0 },
    l: { len: D, at: (a, yy) => T(-W / 2, yy, a, 0, Math.PI / 2, 0), axis: 1 },
    r: { len: D, at: (a, yy) => T(W / 2, yy, a, 0, Math.PI / 2, 0), axis: 1 },
  };
  const bark = '#7b5434', barkD = '#4a3020', woodC = '#d9aa6c';
  // брёвна: у фасада/зада на целых высотах, у боков - со сдвигом на полбревна (врубка в углах)
  let li = 0;
  for (const key of ['f', 'b', 'l', 'r']) {
    const w = walls[key];
    const off = w.axis === 0 ? 0 : 0.5;
    for (let k = 0; ; k++) {
      const yy = R + (k + off) * step;
      if (yy + R * 0.5 > H) break;
      const a0 = -w.len / 2 - OV, a1 = w.len / 2 + OV;
      // вырезы под проёмы
      const cuts = holes[key].filter((h) => yy + R * 0.6 > h.y0 && yy - R * 0.6 < h.y1).sort((p, q) => p.a0 - q.a0);
      let s = a0;
      const pieces = [];
      for (const h of cuts) { pieces.push([s, h.a0 - 0.02]); s = h.a1 + 0.02; }
      pieces.push([s, a1]);
      for (const [p0, p1] of pieces) {
        const L = p1 - p0;
        if (L < 0.2) continue;
        const m = (p0 + p1) / 2;
        const xf = w.at(m, yy).multiply(T(0, 0, 0, (rand() - 0.5) * 0.4, 0, 0));
        addLog(kit, L, R * (0.94 + rand() * 0.1), xf, {
          seed: 1200 + li++, bark, barkDark: barkD, wood: woodC, seg: kit.seg(7, 9, 12), rings: L > 3 ? kit.seg(2, 4, 6) : kit.seg(1, 2, 3), taper: 0.04,
        });
      }
    }
  }
  // конопатка: светлый раствор между брёвнами - стенка внутри с проёмами
  const chink = '#d6cbb5';
  for (const key of ['f', 'b', 'l', 'r']) {
    const w = walls[key];
    const hs = holes[key].slice().sort((p, q) => p.a0 - q.a0);
    let s = -w.len / 2;
    const rects = [];
    for (const h of hs) {
      rects.push([s, h.a0, 0, H]);
      if (h.y0 > 0) rects.push([h.a0, h.a1, 0, h.y0]);
      rects.push([h.a0, h.a1, h.y1, H]);
      s = h.a1;
    }
    rects.push([s, w.len / 2, 0, H]);
    for (const [a0, a1, y0, y1] of rects) {
      if (a1 - a0 < 0.01 || y1 - y0 < 0.01) continue;
      kit.add(new THREE.BoxGeometry(a1 - a0, y1 - y0, 0.2), chink, SURF.plain, w.at((a0 + a1) / 2, (y0 + y1) / 2), { shadow: false });
    }
  }
  kit.shadowOnly(new THREE.BoxGeometry(W + 0.2, H, D + 0.2), T(0, H / 2, 0));
  // тёмная «комната» за окнами и дверью
  kit.add(new THREE.BoxGeometry(W - 0.5, H - 0.1, D - 0.5), '#2a1d14', SURF.plain, T(0, H / 2, 0), { shadow: false, bake: false });

  // окна: наличники, переплёт, подоконник со снегом, тёплые занавески
  const windowAt = (key, h) => {
    const w = walls[key];
    const cw = h.a1 - h.a0, ch = h.y1 - h.y0;
    kit.push(w.at((h.a0 + h.a1) / 2, (h.y0 + h.y1) / 2).multiply(T(0, 0, key === 'b' || key === 'l' ? 0 : 0, 0, key === 'b' || key === 'l' ? Math.PI : 0, 0)));
    // свет из комнаты: занавески в клетку по бокам, тёплое окно посередине
    kit.add(new THREE.PlaneGeometry(cw, ch), '#ffcf88', glow(2.4), T(0, 0, -0.06), { shadow: false, bake: false, emis: (x, yy) => 0.6 * (1 - Math.min(1, Math.hypot(x / cw, yy / ch) * 1.6)) });
    for (const s of [-1, 1]) {
      const cg = new THREE.PlaneGeometry(cw * 0.32, ch * 0.96, 6, 1);
      const P = cg.getAttribute('position');
      for (let i = 0; i < P.count; i++) P.setZ(i, Math.sin(P.getX(i) * 40) * 0.012);
      cg.computeVertexNormals();
      kit.add(cg, '#c4483a', glow(1.15, PAT.gingham), T(s * cw * 0.34, 0.0, -0.04), { shadow: false, bake: false, uv: (x, yy) => [x, yy] });
    }
    // переплёт
    kit.box(0.045, ch, 0.05, '#efe6d2', SURF.paint, T(0, 0, 0.0), { sharp: true, shadow: false });
    kit.box(cw, 0.045, 0.05, '#efe6d2', SURF.paint, T(0, ch * 0.1, 0.0), { sharp: true, shadow: false });
    // наличники
    const tc = '#6b4426';
    kit.box(cw + 0.28, 0.12, 0.06, tc, SURF.wood, T(0, ch / 2 + 0.06, R + 0.02), { bevel: 0.015, shadow: false });
    for (const s of [-1, 1]) kit.box(0.12, ch + 0.1, 0.06, tc, SURF.wood, T(s * (cw / 2 + 0.06), 0.0, R + 0.02), { bevel: 0.015, shadow: false });
    // резной верх наличника
    kit.add(slabGeo([[-cw / 2 - 0.16, 0], [cw / 2 + 0.16, 0], [cw / 2 + 0.05, 0.1], [0.12, 0.13], [0, 0.2], [-0.12, 0.13], [-cw / 2 - 0.05, 0.1]], 0.05, 0), tc, SURF.wood, T(0, ch / 2 + 0.12, R + 0.02), { shadow: false, uv: (x, yy) => [x, yy] });
    // подоконник и снег на нём
    kit.box(cw + 0.32, 0.06, 0.26, tc, SURF.wood, T(0, -ch / 2 - 0.03, R + 0.06), { bevel: 0.015, shadow: false });
    kit.box(cw + 0.26, 0.07, 0.22, '#f4f8fc', SURF.snow, T(0, -ch / 2 + 0.03, R + 0.07), { bevel: 0.03, shadow: false, bake: false });
    kit.box(cw + 0.2, 0.05, 0.08, '#f4f8fc', SURF.snow, T(0, ch / 2 + 0.24, R + 0.03), { bevel: 0.02, shadow: false, bake: false });
    kit.pop();
  };
  for (const key of ['f', 'b', 'l']) for (const h of holes[key]) if (h.y0 > 0) windowAt(key, h);

  // дверь: доски, косая укосина, петли, ручка, венок
  {
    const h = holes.f[0];
    kit.push(T(0, 0, D / 2));
    const dw = h.a1 - h.a0, dh = h.y1;
    for (let i = 0; i < 5; i++) {
      kit.box(dw / 5 - 0.008, dh - 0.02, 0.05, new THREE.Color('#6e4528').multiplyScalar(0.85 + rand() * 0.25), SURF.wood, T(-dw / 2 + (i + 0.5) * (dw / 5), dh / 2, -0.02), { bevel: 0.008, shadow: false });
    }
    for (const yy of [0.35, dh - 0.35]) kit.box(dw - 0.06, 0.12, 0.04, '#5a3820', SURF.wood, T(0, yy, 0.025), { bevel: 0.01, shadow: false });
    kit.box(0.1, Math.hypot(dw - 0.2, dh - 0.82), 0.035, '#5a3820', SURF.wood, T(0, dh / 2, 0.03, 0, 0, Math.atan2(dw - 0.2, dh - 0.82)), { bevel: 0.01, shadow: false });
    kit.setLayer('detail');
    for (const yy of [0.35, dh - 0.35]) kit.box(0.42, 0.05, 0.012, '#2a2a2c', SURF.iron, T(-dw / 2 + 0.22, yy, 0.05), { bevel: 0.006 });
    kit.add(new THREE.TorusGeometry(0.05, 0.01, 4, 10), '#2a2a2c', SURF.iron, T(dw / 2 - 0.16, 1.0, 0.06));
    // венок с ягодами и бантом
    kit.add(new THREE.TorusGeometry(0.2, 0.06, kit.seg(5, 6, 8), kit.seg(10, 14, 18)), (x, yy, z, nx, ny, nz, out) => out.set('#2f5a2c').multiplyScalar(0.8 + 0.4 * hash2(Math.round(x * 30), Math.round(yy * 30), 2)), SURF.fabric, T(0, 1.65, 0.07));
    for (let i = 0; i < 9; i++) {
      const a = (i / 9) * TAU + 0.3;
      kit.sphere(0.022, '#c8202a', SURF.gloss, T(Math.cos(a) * 0.21, 1.65 + Math.sin(a) * 0.21, 0.12), { seg: 5 });
    }
    kit.box(0.14, 0.08, 0.03, '#c8202a', SURF.fabric, T(0, 1.44, 0.11), { bevel: 0.02 });
    kit.setLayer('solid');
    // косяки
    for (const s of [-1, 1]) kit.box(0.12, dh + 0.1, 0.08, '#5e3c22', SURF.wood, T(s * (dw / 2 + 0.06), dh / 2, R), { bevel: 0.015, shadow: false });
    kit.box(dw + 0.36, 0.14, 0.09, '#5e3c22', SURF.wood, T(0, dh + 0.07, R), { bevel: 0.015, shadow: false });
    // фонарь у двери
    kit.box(0.04, 0.04, 0.26, '#2e3034', SURF.iron, T(dw / 2 + 0.45, 2.05, R + 0.13), { sharp: true, shadow: false });
    kit.cyl(0.08, 0.07, 0.05, '#2e3034', SURF.iron, T(dw / 2 + 0.45, 2.05, R + 0.3), { seg: 6, shadow: false });
    kit.cyl(0.075, 0.075, 0.2, '#ffd9a0', glow(1.3, PAT.glass), T(dw / 2 + 0.45, 1.92, R + 0.3), { seg: 6, open: true, shadow: false });
    kit.add(new THREE.SphereGeometry(0.035, 5, 4), '#ffe2b0', glow(5, PAT.plain, true), T(dw / 2 + 0.45, 1.9, R + 0.3, 0, 0, 0, 1, 1.5, 1), { shadow: false });
    kit.cyl(0.07, 0.05, 0.04, '#2e3034', SURF.iron, T(dw / 2 + 0.45, 1.8, R + 0.3), { seg: 6, shadow: false });
    kit.pop();
  }

  // щипцы (x = +-W/2) из вертикальных досок, круглое оконце
  const E = 0.55, GO = 0.45, RISE = 2.15;
  const RY = H + RISE; // конёк
  for (const s of [-1, 1]) {
    const outline = [[-D / 2 - 0.05, 0], [D / 2 + 0.05, 0], [0, RISE + 0.02]];
    kit.add(gableGeo(outline, 0.08), '#7a5232', SURF.planks, T(s * (W / 2 + 0.02), H - 0.02, 0, 0, Math.PI / 2, 0).multiply(T(0, 0, 0, 0, 0, 0)), { uv: (x, yy) => [yy, x] });
    kit.cyl(0.24, 0.24, 0.1, '#5e3c22', SURF.wood, T(s * (W / 2 + 0.06), H + 0.85, 0, 0, 0, Math.PI / 2), { seg: kit.seg(8, 12, 16) });
    kit.add(new THREE.CircleGeometry(0.18, kit.seg(8, 12, 16)), '#ffcf88', glow(2.0), T(s * (W / 2 + 0.115), H + 0.85, 0, 0, s * Math.PI / 2, 0), { shadow: false, bake: false });
  }
  // крыша (C5): доски-скаты, толстый снег с закруглённой кромкой, сосульки, причелины
  kit.roof('cabin', { x0: cx - W / 2 - GO, x1: cx + W / 2 + GO, z0: cz - D / 2 - E, z1: cz + D / 2 + E });
  const run = D / 2 + E, pitch = Math.atan2(RISE, D / 2);
  const slopeLen = run / Math.cos(pitch);
  const eaveY = H - E * Math.tan(pitch);
  for (const s of [-1, 1]) {
    // скат: центр посередине ската
    const mz = s * run / 2, my = (eaveY + RY) / 2;
    const rot = T(0, my, mz, s * pitch, 0, 0);
    kit.push(rot);
    kit.box(W + GO * 2, 0.1, slopeLen, '#5a3c26', SURF.planks, T(0, 0, 0), { bevel: 0.02, uv: (x, yy, z) => [x, z], bake: false });
    // снег сверху
    kit.box(W + GO * 2 - 0.05, 0.2, slopeLen - 0.05, '#f4f8fc', SURF.snow, T(0, 0.14, -s * 0.0), { bevel: 0.08, bake: false });
    kit.pop();
    // снежный карниз на кромке свеса
    const ez = s * (run + 0.02), ey = eaveY + 0.06;
    kit.cyl(0.13, 0.13, W + GO * 2 - 0.1, '#f4f8fc', SURF.snow, T(0, ey, ez, 0, 0, Math.PI / 2), { seg: kit.seg(6, 8, 10), bake: false, shadow: false });
    if (s < 0) icicles(kit, -W / 2 - GO + 0.2, W / 2 + GO - 0.2, ey - 0.08, ez, kit.seg(8, 14, 18), 300 + s);
    else {
      // над крыльцом сосулек нет - они бы протыкали его навес
      icicles(kit, -W / 2 - GO + 0.2, -1.9, ey - 0.08, ez, kit.seg(4, 6, 8), 302);
      icicles(kit, 1.9, W / 2 + GO - 0.2, ey - 0.08, ez, kit.seg(4, 6, 8), 303);
    }
  }
  // снежный гребень на коньке и причелины по скатам щипцов
  kit.cyl(0.17, 0.17, W + GO * 2, '#f4f8fc', SURF.snow, T(0, RY + 0.12, 0, 0, 0, Math.PI / 2), { seg: kit.seg(6, 8, 10), bake: false });
  for (const sx of [-1, 1]) {
    for (const s of [-1, 1]) {
      kit.box(0.06, 0.22, slopeLen + 0.05, '#4a2f1c', SURF.wood, T(sx * (W / 2 + GO), (eaveY + RY) / 2 - 0.05, s * run / 2, s * pitch, 0, 0), { bevel: 0.01, bake: false });
    }
  }

  // крыльцо: настил, ступень, столбы, перила, навес со снегом
  kit.setLayer('solid');
  const PD = 1.5, PWd = 3.2, PH = 0.32;
  {
    kit.push(T(0, 0, D / 2));
    for (let i = 0; i < 8; i++) {
      kit.box(PWd / 8 - 0.015, 0.05, PD, new THREE.Color('#8b6440').multiplyScalar(0.85 + rand() * 0.25), SURF.wood, T(-PWd / 2 + (i + 0.5) * (PWd / 8), PH - 0.025, PD / 2 + 0.05), { bevel: 0.01, shadow: false });
    }
    kit.box(PWd, PH - 0.05, PD, '#5e4028', SURF.wood, T(0, (PH - 0.05) / 2, PD / 2 + 0.05), { bevel: 0.02 });
    kit.box(1.3, 0.16, 0.36, '#7a5636', SURF.wood, T(0, 0.08, PD + 0.23), { bevel: 0.015 });
    snowMound(kit, 0.45, 0.16, PD + 0.3, 0.32, 0.06, 0.15, 7);
    for (const s of [-1, 1]) {
      kit.box(0.14, 2.0, 0.14, '#6e4a2c', SURF.wood, T(s * (PWd / 2 - 0.1), PH + 1.0, PD - 0.05), { bevel: 0.02 });
      // перила по бокам и спереди до ступени
      kit.box(0.08, 0.06, PD - 0.1, '#6e4a2c', SURF.wood, T(s * (PWd / 2 - 0.1), PH + 0.82, PD / 2), { bevel: 0.01 });
      kit.box(PWd / 2 - 0.75, 0.06, 0.08, '#6e4a2c', SURF.wood, T(s * (PWd / 4 + 0.32), PH + 0.82, PD - 0.05), { bevel: 0.01 });
      kit.box(0.1, 0.05, PD - 0.1, '#f4f8fc', SURF.snow, T(s * (PWd / 2 - 0.1), PH + 0.875, PD / 2), { bevel: 0.02, shadow: false, bake: false });
      for (let k = 0; k < 5; k++) kit.box(0.045, 0.72, 0.045, '#7a5636', SURF.wood, T(s * (PWd / 2 - 0.1), PH + 0.42, 0.25 + k * 0.25), { sharp: kit.q < 2, shadow: false });
      for (let k = 0; k < 3; k++) kit.box(0.045, 0.72, 0.045, '#7a5636', SURF.wood, T(s * (0.75 + k * 0.24), PH + 0.42, PD - 0.05), { sharp: kit.q < 2, shadow: false });
      col.addCircle(cx + s * (PWd / 2 - 0.1), cz + D / 2 + PD - 0.05, 0.1, y, y + 2.8);
      col.addBox(cx + s * (PWd / 2 - 0.1), cz + D / 2 + PD / 2, 0.05, PD / 2, 0, y + PH, y + PH + 0.86);
      col.addBox(cx + s * (PWd / 4 + 0.32), cz + D / 2 + PD - 0.05, PWd / 4 - 0.38, 0.05, 0, y + PH, y + PH + 0.86);
    }
    // лавочка на крыльце
    kit.box(0.9, 0.05, 0.3, '#9a6e44', SURF.wood, T(-0.95, PH + 0.42, 0.35), { bevel: 0.01 });
    for (const s of [-1, 1]) kit.box(0.05, 0.42, 0.26, '#6e4a2c', SURF.wood, T(-0.95 + s * 0.38, PH + 0.21, 0.35), { bevel: 0.01, shadow: false });
    kit.pop();
    col.addBox(cx, cz + D / 2 + PD / 2 + 0.05, PWd / 2, PD / 2, 0, y - 0.5, y + PH);
    col.addBox(cx, cz + D / 2 + PD + 0.23, 0.65, 0.18, 0, y - 0.5, y + 0.16);
    col.addBox(cx - 0.95, cz + D / 2 + 0.35, 0.45, 0.15, 0, y + PH, y + PH + 0.44);
  }
  kit.roof('cabin-porch', { x0: cx - PWd / 2 - 0.2, x1: cx + PWd / 2 + 0.2, z0: cz + D / 2, z1: cz + D / 2 + PD + 0.3 });
  {
    const y0p = 2.62, y1p = 2.3, z0p = D / 2 + R, z1p = D / 2 + PD + 0.25;
    const pl = Math.hypot(z1p - z0p, y0p - y1p), pa = Math.atan2(y0p - y1p, z1p - z0p);
    const m = T(0, (y0p + y1p) / 2, (z0p + z1p) / 2, pa, 0, 0);
    kit.push(m);
    kit.box(PWd + 0.4, 0.08, pl, '#5a3c26', SURF.planks, T(0, 0, 0), { bevel: 0.015, uv: (x, yy, z) => [x, z], bake: false });
    kit.box(PWd + 0.32, 0.16, pl - 0.04, '#f4f8fc', SURF.snow, T(0, 0.11, 0), { bevel: 0.06, bake: false });
    kit.pop();
    kit.box(PWd + 0.1, 0.16, 0.12, '#5e3c22', SURF.wood, T(0, y1p - 0.1, z1p - 0.18), { bevel: 0.015, bake: false });
    icicles(kit, -PWd / 2, PWd / 2, y1p - 0.05, z1p + 0.02, kit.seg(5, 8, 10), 311, 0.3);
  }

  // каменная труба у правого щипца: широкое основание, сужение, оголовок со снегом
  kit.setLayer('solid');
  {
    const tx = W / 2 + R + 0.55, tz = -0.9;
    kit.box(1.1, 1.7, 1.0, '#8f8a84', SURF.masonry, T(tx, 0.85, tz), { bevel: 0.05 });
    kit.add(slabGeo([[-0.55, 0], [0.55, 0], [0.42, 0.35], [-0.42, 0.35]], 1.0, 0.02), '#8f8a84', SURF.masonry, T(tx, 1.7, tz, 0, Math.PI / 2, 0), { uv: (x, yy, z) => [x, yy] });
    kit.box(0.84, RY + 0.9 - 2.05, 0.84, '#8f8a84', SURF.masonry, T(tx - 0.0, 2.05 + (RY + 0.9 - 2.05) / 2, tz), { bevel: 0.04 });
    kit.box(1.0, 0.12, 1.0, '#6f6b66', SURF.stone, T(tx, RY + 0.96, tz), { bevel: 0.03 });
    kit.box(0.94, 0.1, 0.94, '#f4f8fc', SURF.snow, T(tx, RY + 1.06, tz), { bevel: 0.045, shadow: false, bake: false });
    kit.cyl(0.16, 0.18, 0.3, '#7d5a46', SURF.plain, T(tx, RY + 1.2, tz), { seg: kit.seg(6, 8, 10) });
    col.addBox(cx + tx, cz + tz, 0.55, 0.5, 0, y - 1, y + RY + 1.3);
    const smoke = buildParticles([{ x: 0, y: 0, z: 0, smoke: kit.seg(8, 12, 16), type: 2, life: 7, size: 0.75, spread: 0.2 }], 12);
    smoke.position.set(cx + tx, y + RY + 0.6, cz + tz);
    kit.cur.extra.push({ obj: smoke, lod: 'near' });
  }

  // поленница под свесом у левого щипца
  {
    kit.push(T(-W / 2 - 0.6, 0, 1.6, 0, Math.PI / 2, 0));
    kit.box(2.0, 0.1, 0.5, '#5a3c26', SURF.wood, T(0, 0.05, 0), { bevel: 0.01 });
    for (const s of [-1, 1]) kit.box(0.08, 1.25, 0.08, '#6e4a2c', SURF.wood, T(s * 1.02, 0.62, 0), { bevel: 0.01 });
    kit.setLayer('detail');
    const rows = kit.seg(4, 5, 6), cols = kit.seg(8, 10, 12);
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const rr = 0.07 + rand() * 0.025;
        const x = -0.92 + (c + (r % 2) * 0.5) * (1.84 / cols);
        if (x > 0.95) continue;
        addLog(kit, 0.45 + rand() * 0.06, rr, TY(x, 0.17 + r * 0.17, (rand() - 0.5) * 0.05, Math.PI / 2, rand() * TAU), { seed: 1500 + r * 20 + c, seg: kit.seg(5, 6, 7), rings: 1, wood: '#e2bc84' });
      }
    }
    kit.box(2.1, 0.08, 0.6, '#f4f8fc', SURF.snow, T(0, 0.17 + rows * 0.17, 0), { bevel: 0.035, bake: false });
    kit.setLayer('solid');
    kit.shadowOnly(new THREE.BoxGeometry(1.95, rows * 0.17, 0.45), T(0, 0.1 + rows * 0.085, 0));
    kit.pop();
    col.addBox(cx - W / 2 - 0.6, cz + 1.6, 0.3, 1.05, 0, y - 0.3, y + 1.3);
  }
  kit.pop();

  // снеговик, санки, колода с топором, сугробы, фонарь у тропы
  kit.setLayer('solid');
  {
    const sx = cx + 3.6, sz = cz + 6.0, sy = heightAt(sx, sz);
    kit.push(T(sx, sy, sz, 0, -0.5, 0));
    const sn = '#f6f9fc';
    kit.add(new THREE.SphereGeometry(0.42, kit.seg(8, 10, 14), kit.seg(6, 8, 10)), sn, SURF.snow, T(0, 0.36, 0, 0, 0, 0, 1, 0.9, 1));
    kit.add(new THREE.SphereGeometry(0.31, kit.seg(8, 10, 14), kit.seg(6, 8, 10)), sn, SURF.snow, T(0, 0.92, 0));
    kit.add(new THREE.SphereGeometry(0.22, kit.seg(8, 10, 14), kit.seg(6, 8, 10)), sn, SURF.snow, T(0, 1.36, 0));
    kit.setLayer('detail');
    kit.add(new THREE.ConeGeometry(0.035, 0.22, 6), '#f07a22', SURF.paint, T(0, 1.37, 0.28, Math.PI / 2, 0, 0));
    for (const s of [-1, 1]) kit.sphere(0.022, '#1c1a1a', SURF.gloss, T(s * 0.075, 1.44, 0.19), { seg: 5 });
    for (let i = 0; i < 5; i++) { const a = -0.6 + i * 0.3; kit.sphere(0.016, '#1c1a1a', SURF.gloss, T(Math.sin(a) * 0.15, 1.26 - Math.cos(a) * 0.03, 0.17 + Math.cos(a) * 0.03), { seg: 4 }); }
    for (let i = 0; i < 3; i++) kit.sphere(0.025, '#1c1a1a', SURF.gloss, T(0, 0.82 + i * 0.13, 0.3 - Math.abs(i - 1) * 0.015), { seg: 5 });
    // шарф и ведро-шляпа
    kit.add(new THREE.TorusGeometry(0.2, 0.05, kit.seg(4, 6, 8), kit.seg(10, 14, 16)), '#c43c3c', SURF.gingham, T(0, 1.17, 0, Math.PI / 2, 0, 0));
    kit.box(0.08, 0.32, 0.03, '#c43c3c', SURF.gingham, T(0.12, 1.02, 0.2, 0.2, 0, 0.15), { bevel: 0.012 });
    kit.add(lathe([[0, 0], [0.15, 0], [0.16, 0.02], [0.13, 0.2], [0, 0.2]], kit.seg(8, 10, 12)), '#5a6a78', SURF.metal, T(0.02, 1.5, -0.02, -0.15, 0, 0.12), { uv: (x, yy) => [yy, x] });
    // руки-веточки
    for (const s of [-1, 1]) {
      const a = V3(s * 0.26, 0.98, 0), b = V3(s * 0.62, 1.22, 0.05), c2 = V3(s * 0.7, 1.32, 0.02);
      kit.add(tube([a, b, c2], (t) => 0.018 * (1 - t) + 0.008, 4), '#4a3424', SURF.bark, null);
      kit.add(tube([b, V3(s * 0.66, 1.12, 0.12)], 0.008, 3), '#4a3424', SURF.bark, null);
    }
    kit.setLayer('solid');
    kit.pop();
    col.addCircle(sx, sz, 0.4, sy, sy + 1.6);
    ctx.exclude(sx, sz, 0.6);
  }
  {
    // санки прислонены к стене у крыльца
    const kx = 2.6, kz = D / 2 + 0.35;
    kit.push(T(cx + kx, y, cz + kz, -0.0, 0, 0).multiply(T(0, 0, 0, -1.2, 0, 0)));
    for (let i = 0; i < 4; i++) kit.box(0.42, 0.025, 0.1, '#b0502e', SURF.lacq, T(0, 0.12, -0.34 + i * 0.22), { bevel: 0.006, shadow: false });
    for (const s of [-1, 1]) {
      const pts = [V3(s * 0.2, 0.02, -0.5), V3(s * 0.2, 0.0, 0.3), V3(s * 0.2, 0.05, 0.48), V3(s * 0.2, 0.15, 0.5), V3(s * 0.2, 0.14, 0.4)];
      kit.add(tube(pts, 0.014, 4), '#3c3c40', SURF.metal, null, { shadow: false });
      for (const zz of [-0.3, 0.2]) kit.box(0.025, 0.1, 0.025, '#3c3c40', SURF.metal, T(s * 0.2, 0.07, zz), { sharp: true, shadow: false });
    }
    kit.pop();
  }
  stumpSeat(ctx, cx - 6.0, cz + 4.2, 0.9, 1402);
  {
    const ky = heightAt(cx - 6.0, cz + 4.2) + 0.38;
    kit.group('cabin');
    kit.setLayer('detail');
    kit.push(T(cx - 6.0, ky, cz + 4.2, 0, 2.2, 0).multiply(T(0.02, 0, 0, 0, 0, -0.45)));
    kit.cyl(0.018, 0.022, 0.6, '#b98a55', SURF.wood, T(0, 0.3, 0), { seg: 6 });
    kit.box(0.035, 0.11, 0.17, '#55595e', SURF.metal, T(0, 0.0, 0.05), { bevel: 0.008 });
    kit.box(0.012, 0.13, 0.05, '#c9ccd0', SURF.metal, T(0, -0.005, 0.15), { bevel: 0.004 });
    kit.pop();
    snowMound(kit, cx - 6.0, ky + 0.02, cz + 4.2, 0.22, 0.04, 0.22, 3);
    kit.setLayer('solid');
  }
  // сугробы у стен и вокруг
  {
    kit.group('cabin');
    const drifts = [[-W / 2 - 0.3, -2.2, 0.9, 0.35, 0.6], [W / 2 + 0.25, 1.4, 0.6, 0.3, 1.0], [-2.6, -D / 2 - 0.3, 1.2, 0.3, 0.45], [2.4, -D / 2 - 0.3, 0.9, 0.38, 0.5],
      [-3.4, D / 2 + 0.35, 0.8, 0.28, 0.4], [3.3, D / 2 + 0.6, 0.55, 0.22, 0.35], [-6.2, 5.5, 1.3, 0.4, 0.9], [5.6, -4.2, 1.5, 0.5, 1.1]];
    drifts.forEach(([dx, dz, rx, ryy, rz], i) => snowMound(kit, cx + dx, y, cz + dz, rx, ryy, rz, 20 + i));
  }
  kit.group('cabin');
  lantern(ctx, cx + 2.6, cz + 9.3, { seed: 1444, snow: true });
  kit.group('cabin');

  // дальний вид
  kit.setLayer('far');
  kit.push(T(cx, y, cz));
  kit.add(new THREE.BoxGeometry(W + 0.4, H, D + 0.4), '#6e4a2c', SURF.plain, T(0, H / 2, 0), { bake: false });
  kit.add(slabGeo([[-run, 0], [run, 0], [0, RISE + E * Math.tan(pitch)]], W + GO * 2, 0), '#eef3f8', SURF.plain, T(0, eaveY, 0, 0, Math.PI / 2, 0), { bake: false });
  kit.add(new THREE.BoxGeometry(0.85, RY + 1, 0.85), '#8f8a84', SURF.plain, T(W / 2 + 0.5, (RY + 1) / 2, -0.9), { bake: false });
  kit.pop();

  col.addBox(cx, cz, W / 2 + OV, D / 2 + 0.2, 0, y - 1, y + RY);
  ctx.exclude(cx, cz, 6.2);
  ctx.exclude(cx, cz + D / 2 + 1.2, 2.2);
}

// ---------- ферма ----------

// Тыква: рёбра, приплюснутая, с хвостиком. Возвращает геометрию с низом на y = 0.
function pumpkinGeo(r, seg) {
  const g = new THREE.SphereGeometry(1, seg, Math.max(5, Math.round(seg * 0.6)));
  const P = g.getAttribute('position');
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i);
    const a = Math.atan2(z, x);
    const rib = 1 - 0.09 * Math.pow(Math.abs(Math.cos(a * 5)), 0.6);
    const k = r * rib;
    let yy = y * 0.72;
    yy -= 0.12 * Math.pow(Math.max(0, Math.abs(y) * 0 + 1 - Math.hypot(x, z)), 3) * Math.sign(y); // ямки сверху и снизу
    P.setXYZ(i, x * k, yy * r + r * 0.72, z * k);
  }
  g.computeVertexNormals();
  return g;
}

function pumpkin(kit, x, y, z, r, seed, rot = 0) {
  const rand = mulberry32(seed);
  const base = new THREE.Color(['#e8822a', '#f09434', '#d9701e', '#e6a23a'][seed % 4]);
  const g = pumpkinGeo(r, kit.seg(8, 12, 16));
  kit.add(g, (px, py, pz, nx, ny, nz, out) => {
    const a = Math.atan2(pz, px);
    out.copy(base).multiplyScalar(0.8 + 0.25 * Math.pow(Math.abs(Math.sin(a * 5)), 0.5));
  }, SURF.gloss, T(x, y, z, 0, rot, (rand() - 0.5) * 0.15), { shadow: kit.q > 1 ? new THREE.IcosahedronGeometry(r * 0.85, 0).translate(0, r * 0.7, 0) : false });
  const top = y + r * 1.42;
  kit.add(tube([V3(0, 0, 0), V3(0.01, r * 0.25, 0.01), V3(r * 0.15, r * 0.38, 0.02)], (t) => r * (0.11 - t * 0.05), 4), '#5e6a2a', SURF.wood, T(x, top - r * 0.08, z, 0, rand() * TAU, 0), { shadow: false, layer: 'detail' });
}

export function buildFarm(ctx) {
  const { kit, col } = ctx;
  const cx = 156, cz = -30;
  const y = heightAt(cx, cz);
  const rand = mulberry32(31);
  kit.group('farm', { cx, cz, r: 14 });
  kit.push(T(cx, y, cz));

  // ---------- амбар: конёк вдоль Z, ворота на юг (z+) ----------
  const W = 8, D = 10, H = 3.5;
  const prof = [[-W / 2, H], [-2.75, H + 1.9], [0, H + 2.8], [2.75, H + 1.9], [W / 2, H]]; // ломаная крыша
  const red = new THREE.Color('#a8402f'), white = '#f1ebdf';
  const boardColor = (x, yy, z, nx, ny, nz, out, wx, wy, wz) => {
    const k = Math.floor((Math.abs(nz) > 0.5 ? wx : wz) / 0.155 + 1000);
    out.copy(red).multiplyScalar(0.84 + 0.24 * hash2(k, 7, 3));
    out.lerp(new THREE.Color('#6e3a2c'), smoothstep(y + 0.6, y, wy) * 0.4);
  };
  // боковые стены (вертикальные доски: u - вверх)
  for (const s of [-1, 1]) {
    kit.add(new THREE.BoxGeometry(0.16, H, D), boardColor, SURF.planks, T(s * W / 2, H / 2, 0), { uv: (x, yy, z) => [yy, z] });
  }
  // щипцы: прямоугольник + ломаный верх
  const gable = [[-W / 2, 0], [W / 2, 0], [W / 2, H], [2.75, H + 1.9], [0, H + 2.8], [-2.75, H + 1.9], [-W / 2, H]];
  for (const s of [-1, 1]) kit.add(slabGeo(gable, 0.16, 0), boardColor, SURF.planks, T(0, 0, s * D / 2), { uv: (x, yy) => [yy, x] });
  kit.shadowOnly(new THREE.BoxGeometry(W, H, D), T(0, H / 2, 0));
  // цоколь из камня
  kit.box(W + 0.24, 0.45, D + 0.24, '#8d8880', SURF.masonry, T(0, 0.2, 0), { bevel: 0.03, shadow: false });
  // белые угловые доски и обвязка
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) kit.box(0.2, H, 0.2, white, SURF.paint, T(sx * W / 2, H / 2 + 0.2, sz * D / 2), { bevel: 0.02, shadow: false });
  for (const s of [-1, 1]) {
    kit.box(0.2, 0.18, D + 0.2, white, SURF.paint, T(s * (W / 2 + 0.02), H - 0.05, 0), { bevel: 0.02, shadow: false });
    // кромки щипцов по ломаной
    for (let i = 0; i < prof.length - 1; i++) {
      const a = V3(prof[i][0], prof[i][1], s * (D / 2 + 0.1)), b = V3(prof[i + 1][0], prof[i + 1][1], s * (D / 2 + 0.1));
      kit.box(a.distanceTo(b) + 0.1, 0.2, 0.08, white, SURF.paint, along(a.clone().add(b).multiplyScalar(0.5), b.clone().sub(a)), { bevel: 0.015, shadow: false });
    }
  }
  // ворота: две створки с белыми рамами и крестовинами
  {
    const gw = 3.4, gh = 3.0;
    kit.push(T(0, 0.12, D / 2 + 0.1));
    for (const s of [-1, 1]) {
      const m = s * gw / 4;
      kit.box(gw / 2 - 0.04, gh, 0.08, '#8e3427', SURF.planks, T(m, gh / 2, 0), { bevel: 0.01, shadow: false, uv: (x, yy) => [yy, x] });
      // рама
      kit.box(gw / 2 - 0.04, 0.14, 0.06, white, SURF.paint, T(m, gh - 0.07, 0.06), { bevel: 0.012, shadow: false });
      kit.box(gw / 2 - 0.04, 0.14, 0.06, white, SURF.paint, T(m, 0.07, 0.06), { bevel: 0.012, shadow: false });
      kit.box(gw / 2 - 0.04, 0.12, 0.06, white, SURF.paint, T(m, gh / 2, 0.06), { bevel: 0.012, shadow: false });
      for (const e of [-1, 1]) kit.box(0.14, gh, 0.06, white, SURF.paint, T(m + e * (gw / 4 - 0.09), gh / 2, 0.06), { bevel: 0.012, shadow: false });
      // крестовины в обеих половинах
      for (const yy of [gh * 0.25, gh * 0.75]) {
        const hh = gh / 2 - 0.2, ww = gw / 2 - 0.3;
        const ang = Math.atan2(hh, ww), l = Math.hypot(hh, ww);
        kit.box(l, 0.11, 0.05, white, SURF.paint, T(m, yy, 0.065, 0, 0, ang), { bevel: 0.01, shadow: false });
        kit.box(l, 0.11, 0.05, white, SURF.paint, T(m, yy, 0.07, 0, 0, -ang), { bevel: 0.01, shadow: false });
      }
    }
    // рельс сдвижных ворот и ролики
    kit.box(gw * 1.9, 0.08, 0.1, '#3a3a3e', SURF.iron, T(0, gh + 0.12, 0.08), { sharp: true, shadow: false });
    kit.setLayer('detail');
    for (const s of [-1, 1]) for (const e of [-1, 1]) kit.cyl(0.06, 0.06, 0.04, '#3a3a3e', SURF.iron, T(s * gw / 4 + e * 0.5, gh + 0.06, 0.12, Math.PI / 2, 0, 0), { seg: 8 });
    kit.box(0.04, 0.4, 0.04, '#3a3a3e', SURF.iron, T(-0.15, 1.3, 0.12), { sharp: true });
    kit.box(0.04, 0.4, 0.04, '#3a3a3e', SURF.iron, T(0.15, 1.3, 0.12), { sharp: true });
    kit.setLayer('solid');
    kit.pop();
  }
  // сеновал: открытая дверца, сено, балка с блоком и верёвкой
  {
    const hy = H + 0.95, hw = 1.3, hh = 1.25;
    kit.push(T(0, hy, D / 2 + 0.09));
    kit.add(new THREE.PlaneGeometry(hw, hh), '#1e140e', SURF.plain, T(0, 0, 0), { shadow: false, bake: false });
    kit.add(stoneGeo(0.62, 77, kit.seg(0, 1, 1), 0.55, 0.45), '#d8b554', SURF.straw, T(0, -hh / 2 + 0.02, -0.05, 0, 0, 0, 1, 1, 0.6), { shadow: false });
    for (let i = 0; i < 6; i++) kit.add(new THREE.ConeGeometry(0.06, 0.35, 4), '#e0c060', SURF.straw, T(-0.4 + i * 0.16, -hh / 2 + 0.35, 0.12, 1.2 + rand() * 0.4, 0, (rand() - 0.5) * 0.8), { shadow: false, layer: 'detail' });
    for (const s of [-1, 1]) kit.box(0.12, hh + 0.2, 0.06, white, SURF.paint, T(s * (hw / 2 + 0.06), 0, 0.03), { bevel: 0.012, shadow: false });
    kit.box(hw + 0.36, 0.12, 0.06, white, SURF.paint, T(0, hh / 2 + 0.06, 0.03), { bevel: 0.012, shadow: false });
    kit.box(hw + 0.36, 0.12, 0.1, white, SURF.paint, T(0, -hh / 2 - 0.06, 0.05), { bevel: 0.012, shadow: false });
    // распахнутая створка
    kit.box(hw / 2, hh, 0.06, '#8e3427', SURF.planks, T(-hw / 2 - hw / 4 - 0.05, 0, 0.25, 0, 1.25, 0), { bevel: 0.01, shadow: false, uv: (x, yy) => [yy, x] });
    // балка с блоком
    kit.box(0.16, 0.16, 1.3, '#6e4a2c', SURF.wood, T(0, hh / 2 + 0.75, 0.55), { bevel: 0.02 });
    kit.setLayer('detail');
    kit.add(new THREE.TorusGeometry(0.1, 0.03, 4, 10), '#3a3a3e', SURF.iron, T(0, hh / 2 + 0.55, 1.1, 0, Math.PI / 2, 0));
    kit.add(tube([V3(0, hh / 2 + 0.5, 1.12), V3(0.02, -0.2, 1.15)], 0.015, 4), '#c9b28a', SURF.fabric, null);
    kit.add(new THREE.TorusGeometry(0.06, 0.012, 3, 8, Math.PI * 1.4), '#3a3a3e', SURF.iron, T(0.02, -0.26, 1.15, 0, 0, Math.PI * 0.8));
    kit.setLayer('solid');
    kit.pop();
  }
  // окна на боковых стенах
  for (const s of [-1, 1]) {
    for (const z of [-2.6, 2.4]) {
      kit.push(T(s * (W / 2 + 0.09), 2.0, z, 0, s * Math.PI / 2, 0));
      kit.add(new THREE.PlaneGeometry(0.9, 0.9), '#2b2420', [0.1, 0, 0, PAT.glass], T(0, 0, 0), { shadow: false, bake: false, uv: (x, yy) => [x + z, yy] });
      for (const e of [-1, 1]) {
        kit.box(0.1, 1.05, 0.05, white, SURF.paint, T(e * 0.5, 0, 0.02), { sharp: true, shadow: false });
        kit.box(1.1, 0.1, 0.05, white, SURF.paint, T(0, e * 0.5, 0.02), { sharp: true, shadow: false });
      }
      kit.box(0.05, 0.9, 0.04, white, SURF.paint, T(0, 0, 0.02), { sharp: true, shadow: false });
      kit.box(0.9, 0.05, 0.04, white, SURF.paint, T(0, 0, 0.02), { sharp: true, shadow: false });
      kit.pop();
    }
  }

  // крыша (C5): четыре ската гонта, конёк, купол-вентиляция с флюгером
  kit.roof('barn', { x0: cx - W / 2 - 0.4, x1: cx + W / 2 + 0.4, z0: cz - D / 2 - 0.45, z1: cz + D / 2 + 0.45 });
  const RL = D + 0.9;
  const roofC = new THREE.Color('#57504c');
  for (let i = 0; i < prof.length - 1; i++) {
    const [x0, y0] = prof[i], [x1, y1] = prof[i + 1];
    const ext = i === 0 || i === 3 ? 0.42 : 0.0; // свес нижних скатов
    const dx = x1 - x0, dy = y1 - y0, L = Math.hypot(dx, dy);
    const ux = dx / L, uy = dy / L;
    const ax = x0 - (i < 2 ? ux * ext : 0), ay = y0 - (i < 2 ? uy * ext : 0);
    const bx2 = x1 + (i >= 2 ? ux * ext : 0), by2 = y1 + (i >= 2 ? uy * ext : 0);
    const ll = Math.hypot(bx2 - ax, by2 - ay);
    const ang = Math.atan2(by2 - ay, bx2 - ax);
    const nx = -uy, ny = ux; // наружная нормаль сечения
    const mx = (ax + bx2) / 2 + nx * 0.06, my = (ay + by2) / 2 + ny * 0.06;
    kit.box(ll, 0.12, RL, roofC.clone().multiplyScalar(0.92 + i * 0.03), SURF.shingle, T(mx, my, 0, 0, 0, ang), {
      bevel: 0.02, bake: false, uv: (x, yy, z) => [z, (i < 2 ? x : -x) + ll / 2],
    });
  }
  kit.cyl(0.11, 0.11, RL, '#3e3835', SURF.metal, T(0, H + 2.92, 0, Math.PI / 2, 0, 0), { seg: kit.seg(6, 8, 8), bake: false });
  {
    // купол на коньке
    kit.push(T(0, H + 2.85, 0));
    kit.box(0.9, 0.75, 0.9, white, SURF.paint, T(0, 0.37, 0), { bevel: 0.02, bake: false });
    for (const s of [-1, 1]) for (let k = 0; k < 4; k++) {
      kit.box(0.7, 0.05, 0.04, '#9a948a', SURF.paint, T(0, 0.2 + k * 0.12, s * 0.46, s * 0.5, 0, 0), { sharp: true, bake: false, shadow: false });
      kit.box(0.04, 0.05, 0.7, '#9a948a', SURF.paint, T(s * 0.46, 0.2 + k * 0.12, 0, 0, 0, -s * 0.5), { sharp: true, bake: false, shadow: false });
    }
    kit.add(new THREE.ConeGeometry(0.78, 0.55, 4, 1), '#4b4441', SURF.shingle, T(0, 1.02, 0, 0, Math.PI / 4, 0), { bake: false });
    // флюгер: шпиль, стрелка, петушок
    kit.cyl(0.015, 0.015, 0.9, '#2e2e30', SURF.iron, T(0, 1.65, 0), { seg: 5, bake: false });
    kit.sphere(0.04, '#c8a040', SURF.metal, T(0, 1.32, 0), { seg: 6, bake: false });
    kit.push(T(0, 1.9, 0, 0, 0.6, 0));
    kit.box(0.7, 0.025, 0.025, '#2e2e30', SURF.iron, T(0, 0, 0), { sharp: true, bake: false, shadow: false });
    kit.add(slabGeo([[0.35, -0.08], [0.48, 0], [0.35, 0.08]], 0.02, 0), '#2e2e30', SURF.iron, null, { bake: false, shadow: false });
    kit.add(slabGeo([[-0.35, 0], [-0.45, 0.12], [-0.3, 0.06], [-0.3, -0.06], [-0.45, -0.12]], 0.02, 0), '#2e2e30', SURF.iron, null, { bake: false, shadow: false });
    // петушок
    const cock = [[-0.12, 0.02], [0.02, 0.02], [0.08, 0.1], [0.1, 0.2], [0.14, 0.22], [0.12, 0.27], [0.07, 0.24], [0.04, 0.16], [-0.04, 0.14], [-0.12, 0.25], [-0.16, 0.12]];
    kit.add(slabGeo(cock, 0.02, 0), '#2e2e30', SURF.iron, T(0, 0.04, 0), { bake: false, shadow: false });
    kit.pop();
    kit.pop();
  }
  kit.setLayer('solid');
  col.addBox(cx, cz, W / 2 + 0.1, D / 2 + 0.1, 0, y - 1, y + H + 2.8);

  // ---------- вокруг амбара ----------
  // круглые тюки: бока соломой, торцы - спираль
  const bales = [[-7.5, 2.5, 0.4], [-8.2, 4.4, 1.6], [-6.4, 5.8, 0.9], [7.0, 6.5, 2.3], [-9.2, -3.0, 0.1]];
  for (const [bx, bz, a] of bales) {
    const by = heightAt(cx + bx, cz + bz) - y;
    kit.push(T(bx, by + 0.72, bz, 0, a, Math.PI / 2));
    kit.add(new THREE.CylinderGeometry(0.72, 0.72, 1.25, kit.seg(10, 14, 18), 1, true), (x, yy, z, nx, ny, nz, out) => out.set('#d4b25a').multiplyScalar(0.9 + 0.15 * hash2(Math.round(Math.atan2(x, z) * 4), 2, 8)), SURF.straw, null, { uv: (x, yy, z) => [Math.atan2(x, z) * 0.72, yy] });
    for (const e of [-1, 1]) {
      kit.add(new THREE.CircleGeometry(0.72, kit.seg(10, 14, 18)).rotateX(e * -Math.PI / 2), '#c9a650', [0.95, 0, 0, PAT.rings], T(0, e * 0.625, 0), { uv: (x, yy, z) => [x * 0.45, z * 0.45], shadow: false });
    }
    // шпагат
    if (kit.q > 0) for (const t of [-0.35, 0.35]) kit.add(new THREE.TorusGeometry(0.725, 0.008, 3, kit.seg(12, 16, 20)).rotateX(Math.PI / 2), '#8a6a3a', SURF.fabric, T(0, t, 0), { layer: 'detail' });
    kit.pop();
    col.addCircle(cx + bx, cz + bz, 0.75, y - 0.5, y + by + 1.4);
  }
  // квадратные тюки штабелем у стены
  {
    kit.push(T(W / 2 + 0.9, 0, -2.0, 0, Math.PI / 2, 0));
    const pos = [[-1.05, 0], [0, 0], [1.05, 0], [-0.52, 1], [0.52, 1], [0, 2]];
    for (const [px, row] of pos) {
      const yy = 0.24 + row * 0.47;
      kit.box(1.0, 0.46, 0.5, new THREE.Color('#d8b65e').multiplyScalar(0.92 + rand() * 0.12), SURF.straw, T(px, yy, (rand() - 0.5) * 0.05, 0, (rand() - 0.5) * 0.06, 0), { bevel: 0.04, uv: (x, yy2, z) => [x, yy2 + z] });
      if (kit.q > 0) for (const e of [-0.25, 0.25]) kit.box(0.015, 0.47, 0.51, '#7a5a2a', SURF.fabric, T(px + e, yy, 0), { sharp: true, layer: 'detail' });
    }
    kit.pop();
    col.addBox(cx + W / 2 + 0.9, cz - 2.0, 0.3, 1.6, 0, y - 0.3, y + 1.4);
  }
  // стога с жердью
  for (const [sx, sz, s] of [[-12, -7, 1], [-14.5, -2.5, 0.85], [11.5, -8, 0.95]]) {
    const sy = heightAt(cx + sx, cz + sz) - y;
    const pr = [[0, 0], [1.75 * s, 0], [1.85 * s, 0.6 * s], [1.6 * s, 1.5 * s], [1.05 * s, 2.35 * s], [0.45 * s, 2.85 * s], [0.08 * s, 3.0 * s], [0, 3.02 * s]];
    const g = lathe(pr, kit.seg(9, 12, 16));
    const P = g.getAttribute('position');
    for (let i = 0; i < P.count; i++) {
      const x = P.getX(i), yy = P.getY(i), z = P.getZ(i);
      const a = Math.atan2(z, x), k = 1 + 0.05 * Math.sin(a * 7 + sx) + 0.03 * Math.sin(yy * 5 + a * 3);
      P.setXYZ(i, x * k, yy, z * k);
    }
    g.computeVertexNormals();
    kit.add(g, (x, yy, z, nx, ny, nz, out) => out.set('#d6b45c').lerp(new THREE.Color('#a08440'), smoothstep(0.5, 0, yy) * 0.5), SURF.straw, T(sx, sy - 0.05, sz), { uv: (x, yy, z) => [Math.atan2(z, x) * 1.2, yy] });
    kit.cyl(0.04, 0.05, 1.2, '#6e4a2c', SURF.wood, T(sx + 0.05, sy + 3.3 * s, sz, 0.08, 0, 0.05), { seg: 5 });
    col.addCircle(cx + sx, cz + sz, 1.6 * s, y - 0.5, y + sy + 2.6 * s);
    ctx.exclude(cx + sx, cz + sz, 2.0 * s);
  }
  // грядка с тыквами, плетями и листьями
  {
    const gx = 3.5, gz = 10.0, gw = 6.0, gd = 4.0;
    const gy = heightAt(cx + gx, cz + gz) - y;
    kit.add(new THREE.BoxGeometry(gw, 0.12, gd, kit.seg(1, 6, 10), 1, kit.seg(1, 4, 6)), '#5a3e2a', SURF.soil, T(gx, gy + 0.03, gz), { uv: (x, yy, z) => [x, z], shadow: false });
    for (const sz2 of [-1, 1]) kit.box(gw + 0.2, 0.18, 0.12, '#7a5636', SURF.wood, T(gx, gy + 0.06, gz + sz2 * (gd / 2 + 0.06)), { bevel: 0.015, shadow: false });
    for (const sx2 of [-1, 1]) kit.box(0.12, 0.18, gd, '#7a5636', SURF.wood, T(gx + sx2 * (gw / 2 + 0.06), gy + 0.06, gz), { bevel: 0.015, shadow: false });
    let pi = 0;
    for (let r = 0; r < 3; r++) {
      for (let c = 0; c < 4; c++) {
        const px = gx - gw / 2 + 0.8 + c * 1.45 + (rand() - 0.5) * 0.4, pz = gz - gd / 2 + 0.7 + r * 1.3 + (rand() - 0.5) * 0.3;
        const pr = 0.2 + rand() * 0.16;
        pumpkin(kit, px, gy + 0.08, pz, pr, 60 + pi++, rand() * TAU);
        // листья
        kit.setLayer('detail');
        for (let k = 0; k < 2; k++) {
          const a = rand() * TAU;
          kit.add(new THREE.CircleGeometry(0.16 + rand() * 0.06, 7).rotateX(-Math.PI / 2 + 0.25), '#4f7a30', SURF.fabric, T(px + Math.cos(a) * (pr + 0.15), gy + 0.11, pz + Math.sin(a) * (pr + 0.15), 0, a, 0), { shadow: false });
        }
        kit.setLayer('solid');
      }
    }
    // плети между тыквами
    kit.setLayer('detail');
    for (let r = 0; r < 3; r++) {
      const pts = [];
      for (let k = 0; k <= 8; k++) pts.push(V3(gx - gw / 2 + 0.4 + k * (gw - 0.8) / 8, gy + 0.1 + Math.sin(k * 1.7 + r) * 0.02, gz - gd / 2 + 0.7 + r * 1.3 + Math.sin(k * 1.3 + r * 2) * 0.25));
      kit.add(tube(pts, 0.015, 3), '#4a6a28', SURF.plain, null, { shadow: false });
    }
    kit.setLayer('solid');
    col.addBox(cx + gx, cz + gz, gw / 2 + 0.1, gd / 2 + 0.1, 0, y - 0.3, y + gy + 0.18);
    ctx.exclude(cx + gx, cz + gz, 3.6);
  }
  // тачка с тыквами
  {
    const tx = -1.5, tz = 9.2, tr = -0.6;
    const ty = heightAt(cx + tx, cz + tz) - y;
    kit.push(T(tx, ty, tz, 0, tr, 0));
    // корыто-кузов: трапеция из досок
    const tub = [[-0.55, 0.0], [0.55, 0.0], [0.7, 0.32], [-0.75, 0.32]];
    for (const s of [-1, 1]) kit.add(slabGeo(tub, 0.03, 0), '#8f6a44', SURF.wood, T(0, 0.38, s * 0.3), { uv: (x, yy) => [x, yy] });
    kit.box(1.1, 0.03, 0.6, '#7a5636', SURF.planks, T(0, 0.39, 0), { bevel: 0.008 });
    kit.box(0.03, 0.32, 0.6, '#8f6a44', SURF.wood, T(-0.66, 0.54, 0, 0, 0, -0.3), { bevel: 0.006 });
    kit.box(0.03, 0.32, 0.6, '#8f6a44', SURF.wood, T(0.62, 0.54, 0, 0, 0, 0.42), { bevel: 0.006 });
    for (const s of [-1, 1]) {
      kit.box(1.9, 0.05, 0.05, '#6e4a2c', SURF.wood, T(-0.25, 0.36, s * 0.24, 0, 0, 0.12), { bevel: 0.01 });
      kit.box(0.05, 0.38, 0.05, '#6e4a2c', SURF.wood, T(-0.45, 0.19, s * 0.24), { bevel: 0.01 });
    }
    kit.push(T(0.68, 0.22, 0, Math.PI / 2, 0, 0));
    kit.add(new THREE.TorusGeometry(0.2, 0.045, kit.seg(4, 6, 6), kit.seg(10, 14, 16)).rotateX(Math.PI / 2), '#2e2e30', SURF.rubber, null);
    for (let k = 0; k < 6; k++) kit.box(0.03, 0.02, 0.36, '#9a6a3c', SURF.wood, T(0, 0, 0, 0, (k / 6) * Math.PI, 0), { sharp: true, shadow: false });
    kit.cyl(0.04, 0.04, 0.12, '#3a3a3e', SURF.iron, null, { seg: 6 });
    kit.pop();
    pumpkin(kit, -0.1, 0.4, 0.06, 0.2, 90, 0.5);
    pumpkin(kit, 0.32, 0.42, -0.08, 0.17, 91, 2);
    kit.pop();
    col.addBox(cx + tx, cz + tz, 0.95, 0.35, tr, y - 0.3, y + ty + 0.75);
  }
  // пугало с вороной
  {
    const sx = 6.5, sz = 13.6;
    const sy = heightAt(cx + sx, cz + sz) - y;
    kit.push(T(sx, sy, sz, 0, -0.4, 0));
    kit.cyl(0.05, 0.06, 2.4, '#6e4a2c', SURF.wood, T(0, 1.2, 0), { seg: 6 });
    kit.cyl(0.04, 0.04, 1.7, '#6e4a2c', SURF.wood, T(0, 1.75, 0, 0, 0, Math.PI / 2), { seg: 6 });
    // рубашка в клетку с рукавами
    kit.box(0.5, 0.65, 0.26, '#3f6fb0', SURF.gingham, T(0, 1.5, 0), { bevel: 0.08 });
    for (const s of [-1, 1]) kit.add(new THREE.CylinderGeometry(0.11, 0.13, 0.6, kit.seg(6, 8, 10)), '#3f6fb0', SURF.gingham, T(s * 0.5, 1.75, 0, 0, 0, Math.PI / 2), { uv: (x, yy, z) => [x, yy] });
    // соломенные пучки из рукавов и ворота
    kit.setLayer('detail');
    for (const s of [-1, 1]) for (let k = 0; k < 4; k++) kit.add(new THREE.ConeGeometry(0.04, 0.22, 4), '#e0c060', SURF.straw, T(s * 0.86, 1.75 + (k - 1.5) * 0.04, (k % 2 - 0.5) * 0.06, 0, 0, -s * (1.3 + k * 0.1)));
    kit.setLayer('solid');
    // голова-мешок с нашитым лицом, шляпа
    kit.add(new THREE.SphereGeometry(0.22, kit.seg(8, 10, 12), kit.seg(6, 8, 10)), '#c8a978', SURF.fabric, T(0, 2.06, 0, 0, 0, 0, 1, 1.1, 0.95));
    kit.setLayer('detail');
    for (const s of [-1, 1]) kit.box(0.06, 0.06, 0.02, '#2a1e16', SURF.fabric, T(s * 0.08, 2.1, 0.205, 0, 0, Math.PI / 4), { sharp: true });
    for (let k = 0; k < 5; k++) kit.box(0.012, 0.04, 0.012, '#2a1e16', SURF.fabric, T(-0.08 + k * 0.04, 1.98, 0.2), { sharp: true });
    kit.box(0.24, 0.015, 0.012, '#2a1e16', SURF.fabric, T(0, 1.98, 0.2), { sharp: true });
    kit.setLayer('solid');
    kit.cyl(0.4, 0.4, 0.03, '#d2b060', SURF.straw, T(0, 2.25, 0, -0.1, 0, 0.08), { seg: kit.seg(8, 12, 16) });
    kit.add(lathe([[0, 0], [0.18, 0], [0.16, 0.18], [0.1, 0.24], [0, 0.25]], kit.seg(8, 10, 12)), '#d2b060', SURF.straw, T(0, 2.26, 0, -0.1, 0, 0.08), { uv: (x, yy) => [yy, x] });
    // ворона на перекладине
    kit.push(T(-0.6, 1.8, 0, 0, 0.8, 0));
    kit.add(new THREE.SphereGeometry(0.08, 7, 5), '#1d1d22', SURF.gloss, T(0, 0.06, 0, 0, 0, 0, 1.4, 1, 0.9));
    kit.add(new THREE.SphereGeometry(0.05, 7, 5), '#1d1d22', SURF.gloss, T(0.1, 0.13, 0));
    kit.add(new THREE.ConeGeometry(0.018, 0.07, 4), '#3a3530', SURF.plain, T(0.17, 0.13, 0, 0, 0, -Math.PI / 2), { shadow: false });
    kit.box(0.12, 0.02, 0.07, '#1d1d22', SURF.gloss, T(-0.13, 0.07, 0, 0, 0, 0.3), { sharp: true, shadow: false });
    kit.pop();
    kit.pop();
    col.addCircle(cx + sx, cz + sz, 0.12, y + sy, y + sy + 2.4);
    ctx.exclude(cx + sx, cz + sz, 0.5);
  }
  // бидоны и вилы у ворот, корыто, вывеска, почтовый ящик
  for (const [mx, mz] of [[2.4, D / 2 + 0.6], [2.85, D / 2 + 0.45]]) {
    kit.add(lathe([[0, 0], [0.17, 0], [0.18, 0.04], [0.18, 0.42], [0.12, 0.52], [0.08, 0.56], [0.08, 0.64], [0.1, 0.65], [0, 0.66]], kit.seg(8, 10, 14)), '#b9c0c6', SURF.metal, T(mx, 0.0, mz), { uv: (x, yy) => [yy, x] });
    col.addCircle(cx + mx, cz + mz, 0.18, y, y + 0.66);
  }
  kit.setLayer('detail');
  {
    // вилы прислонены к стене
    const a = V3(-2.4, 0.02, D / 2 + 0.55), b = V3(-2.25, 1.55, D / 2 + 0.16);
    kit.add(tube([a, b], 0.018, 4), '#b98a55', SURF.wood, null);
    for (let k = -1; k <= 1; k++) kit.add(tube([b.clone().add(V3(k * 0.06, 0, 0)), b.clone().add(V3(k * 0.065, 0.32, -0.06))], 0.008, 3), '#4d5257', SURF.metal, null);
    kit.box(0.16, 0.02, 0.02, '#4d5257', SURF.metal, T(b.x, b.y + 0.01, b.z), { sharp: true });
  }
  kit.setLayer('solid');
  kit.pop();

  // загон с жердевым забором и корытом
  fenceRun(ctx, [[cx + 9, cz - 4], [cx + 9, cz - 10], [cx + 9, cz - 16], [cx + 15, cz - 16], [cx + 21, cz - 16], [cx + 21, cz - 10], [cx + 21, cz - 4], [cx + 17, cz - 4]], 64, 'farm');
  kit.group('farm');
  {
    const tx = cx + 15, tz = cz - 10, ty = heightAt(tx, tz);
    kit.push(T(tx, ty, tz, 0, 0.3, 0));
    kit.box(1.6, 0.06, 0.6, '#6e4a2c', SURF.wood, T(0, 0.12, 0), { bevel: 0.01 });
    for (const s of [-1, 1]) kit.box(1.6, 0.36, 0.06, '#7a5636', SURF.planks, T(0, 0.3, s * 0.3), { bevel: 0.01, uv: (x, yy) => [x, yy] });
    for (const s of [-1, 1]) kit.box(0.06, 0.36, 0.6, '#7a5636', SURF.wood, T(s * 0.8, 0.3, 0), { bevel: 0.01 });
    for (const s of [-1, 1]) kit.box(0.08, 0.12, 0.7, '#5a3c26', SURF.wood, T(s * 0.6, 0.06, 0), { bevel: 0.01 });
    kit.add(new THREE.PlaneGeometry(1.5, 0.52).rotateX(-Math.PI / 2), '#4b7a8a', [0.05, 0, 0, PAT.glass], T(0, 0.4, 0), { shadow: false, bake: false, uv: (x, yy, z) => [x, z] });
    kit.pop();
    col.addBox(tx, tz, 0.85, 0.35, 0.3, ty - 0.3, ty + 0.48);
  }
  // вывеска фермы у тропы
  {
    const px = cx - 7.5, pz = cz + 9.5, py = heightAt(px, pz);
    kit.push(T(px, py, pz, 0, 0.75, 0));
    for (const s of [-1, 1]) kit.box(0.12, 1.9, 0.12, '#6e4a2c', SURF.wood, T(s * 0.85, 0.95, 0), { bevel: 0.02 });
    const lab = woodLabel('Ферма «Тыква»', 640, 150, { bg: '#c99a5e' });
    kit.box(1.9, 0.5, 0.06, '#b08050', SURF.wood, T(0, 1.55, 0), { bevel: 0.015 });
    for (const sgn of [1, -1]) {
      kit.add(new THREE.PlaneGeometry(1.82, 0.44), '#ffffff', SURF.label, T(0, 1.55, sgn * 0.032, 0, sgn > 0 ? 0 : Math.PI, 0), {
        shadow: false, uv: (x, yy) => { const u = x / 1.82 + 0.5, v = yy / 0.44 + 0.5; return [lab[0] + (lab[2] - lab[0]) * u, lab[1] + (lab[3] - lab[1]) * v]; },
      });
    }
    kit.box(2.1, 0.06, 0.16, '#5a3c26', SURF.wood, T(0, 1.83, 0), { bevel: 0.01 });
    pumpkin(kit, 0.5, 0, 0.35, 0.22, 95, 1);
    pumpkin(kit, 0.9, 0, 0.1, 0.16, 96, 2);
    kit.pop();
    col.addCircle(px + Math.cos(0.75) * 0.85, pz - Math.sin(0.75) * 0.85, 0.1, py, py + 1.9);
    col.addCircle(px - Math.cos(0.75) * 0.85, pz + Math.sin(0.75) * 0.85, 0.1, py, py + 1.9);
  }
  mailbox(ctx, cx - 9.4, cz + 7.6, 2.4, '#3d6ea8', '#e8452e');
  kit.group('farm');
  barrel(ctx, cx - 4.6, cz + 5.6, 1, 1601);
  kit.group('farm');

  // дальний вид
  kit.setLayer('far');
  kit.push(T(cx, y, cz));
  kit.add(new THREE.BoxGeometry(W, H, D), red, SURF.plain, T(0, H / 2, 0), { bake: false });
  kit.add(slabGeo([[-W / 2 - 0.3, 0], [W / 2 + 0.3, 0], [2.75, 1.9], [0, 2.8], [-2.75, 1.9]], D + 0.8, 0), roofC, SURF.plain, T(0, H, 0), { bake: false });
  for (const [bx, bz] of bales) kit.add(new THREE.CylinderGeometry(0.72, 0.72, 1.25, 6).rotateZ(Math.PI / 2), '#d4b25a', SURF.plain, T(bx, heightAt(cx + bx, cz + bz) - y + 0.72, bz), { bake: false });
  kit.pop();
  ctx.exclude(cx, cz, 7.5);
}

void bevelBox;
