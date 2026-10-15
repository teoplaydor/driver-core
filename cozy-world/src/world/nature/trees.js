// Процедурные деревья и кусты: подробные (вблизи) и упрощённые (дальний LOD) версии.
// Всё собирается в одну геометрию на тип: ствол, ветви, кроны, карточки листвы из атласа.
// aTint = 1 у листвы (её красит цвет экземпляра), 0 у коры, снега и ягод.

import * as THREE from 'three';
import { mulberry32, TAU, lerp, smoothstep, hash2 } from '../../util/math.js';
import {
  PlantBuilder, tube, blobGeometry, card, noise3, firTier,
} from '../geo.js';
import { ATLAS, WHITE_UV } from './atlas.js';

const V = (x = 0, y = 0, z = 0) => new THREE.Vector3(x, y, z);
const C = (h) => new THREE.Color(h);
const UP = V(0, 1, 0);

const BARK_D = C('#3a281b'), BARK_L = C('#6e4d32');
const SNOW = C('#f2f7fd'), SNOW_SH = C('#c9daee');

// Кора с вертикальными полосами и тёмным низом.
function barkColor(dark = BARK_D, light = BARK_L, seed = 0) {
  return (t, a, i, k, out) => {
    const s = hash2(k, Math.floor(i / 2), seed);
    out.copy(dark).lerp(light, 0.25 + 0.5 * s + 0.2 * t);
  };
}

// Береста: белая с тёмными чёрточками.
function birchColor(seed) {
  const white = C('#e9e5dc'), mark = C('#2b2723'), grayish = C('#bdb8ad');
  return (t, a, i, k, out) => {
    const h = hash2(i * 7 + 3, k, seed);
    if (t < 0.06) out.copy(mark).lerp(grayish, 0.3);
    else if (h < 0.17) out.copy(mark);
    else out.copy(white).lerp(grayish, hash2(k, i, seed + 1) * 0.5);
  };
}

// Корни-лапы у основания ствола.
function roots(b, n, r0, len, seed, color) {
  const rand = mulberry32(seed);
  for (let i = 0; i < n; i++) {
    const a = (i / n) * TAU + rand() * 0.5;
    const d = V(Math.cos(a), 0, Math.sin(a));
    const L = len * (0.75 + rand() * 0.5);
    const pts = [
      V().addScaledVector(d, r0 * 0.4).setY(0.5),
      V().addScaledVector(d, r0 + L * 0.35).setY(0.12),
      V().addScaledVector(d, r0 + L).setY(-0.12),
    ];
    tube(b, pts, (t) => lerp(r0 * 0.55, r0 * 0.12, t), { segs: 5, color, uv: WHITE_UV });
  }
}

// Ствол с корневым "наплывом" у земли.
function trunk(b, h, r0, r1, seed, { color, segs = 8, flare = 0.35, bendAmp = 0.08, rings = 6 } = {}) {
  const pts = [];
  for (let i = 0; i <= rings; i++) {
    const t = i / rings;
    pts.push(V(Math.sin(t * 2.7 + seed) * bendAmp * t, -0.35 + t * (h + 0.35), Math.cos(t * 2.1 + seed * 1.3) * bendAmp * t));
  }
  tube(b, pts, (t, a) => {
    const base = lerp(r0, r1, Math.pow(t, 0.75));
    const fl = t < 0.12 ? flare * (1 - t / 0.12) * (0.65 + 0.35 * Math.sin(a * 5 + seed)) : 0;
    return base * (1 + fl);
  }, { segs, color, uv: WHITE_UV });
  return pts;
}

// ---------- ёлка ----------

const FIR = {
  deep: C('#163a22'), mid: C('#2c6531'), outer: C('#5f9a3a'), tip: C('#9bc957'),
};

// Еловая лапа: изогнутая карточка с гребнем посередине (сечение "домиком"), кончик свисает.
// snowLayer: снежный слой поверх лапы - та же форма, чуть выше, уже и короче, белый.
function firFrond(b, o, a, L, W, lift, droop, crease, segs, colFn, snowLayer = false) {
  const dir = V(Math.cos(a), 0, Math.sin(a));
  const side = V(-Math.sin(a), 0, Math.cos(a));
  const [u0, v0, u1, v1] = ATLAS.fir;
  const um = (u0 + u1) / 2;
  const rows = [];
  const s0 = snowLayer ? 0.08 : 0, s1 = snowLayer ? 0.86 : 1;
  for (let r = 0; r <= segs; r++) {
    const s = s0 + (r / segs) * (s1 - s0);
    const c = o.clone().addScaledVector(dir, L * s).addScaledVector(UP, lift * s - (lift + droop) * s * s);
    const tan = dir.clone().multiplyScalar(L).addScaledVector(UP, lift - 2 * (lift + droop) * s).normalize();
    const nUp = side.clone().cross(tan).normalize();
    if (snowLayer) c.addScaledVector(nUp, crease * 0.6 + 0.07);
    const w = W * (0.55 + 0.45 * Math.min(1, s * 2.5)) * (snowLayer ? 0.78 : 1);
    const left = c.clone().addScaledVector(side, -w / 2).addScaledVector(nUp, -crease * 0.5);
    const right = c.clone().addScaledVector(side, w / 2).addScaledVector(nUp, -crease * 0.5);
    const ridge = c.clone().addScaledVector(nUp, crease * 0.5);
    const v = v0 + s * (v1 - v0);
    rows.push([[left, u0, v], [ridge, um, v], [right, u1, v]].map(([p, u, vv]) => {
      // "сферизованная" нормаль: от оси ствола наружу и вверх
      const rad = V(p.x, 0, p.z).normalize();
      const n = snowLayer
        ? rad.multiplyScalar(0.35).addScaledVector(UP, 1).normalize()
        : rad.multiplyScalar(0.75).addScaledVector(nUp, 0.55).addScaledVector(UP, 0.25).normalize();
      const col = new THREE.Color();
      if (snowLayer) col.copy(SNOW_SH).lerp(SNOW, 0.55 + 0.45 * smoothstep(0.1, 0.6, s)).multiplyScalar(1.12);
      else colFn(s, p, col);
      return { p, n, c: col, uv: [u, vv] };
    }));
  }
  const put = (q) => b.vert(q.p, q.n, q.c, q.uv, snowLayer ? 0 : 1, 0.6);
  for (let r = 0; r < segs; r++) {
    const A = rows[r], B = rows[r + 1];
    for (let j = 0; j < 2; j++) {
      put(A[j]); put(B[j]); put(A[j + 1]);
      put(A[j + 1]); put(B[j]); put(B[j + 1]);
    }
  }
}

export function firDetailed({ seed = 1, snow = false, lod = 1 } = {}) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const H = 10.2;
  const bark = barkColor(C('#33241a'), C('#5f4330'), seed);
  trunk(b, H - 0.6, 0.34, 0.05, seed, { color: bark, segs: lod > 0.7 ? 8 : 6 });
  roots(b, lod > 0.7 ? 5 : 3, 0.3, 0.9, seed + 3, bark);

  // ярусы почти горизонтальные, кончики слегка свисают: классический конус ели
  const T = lod > 0.7 ? 9 : 7;
  const segs = lod > 0.7 ? 4 : 3;
  for (let i = 0; i < T; i++) {
    const t = i / (T - 1);
    const y = 2.1 + Math.pow(t, 0.92) * 7.3;
    const R = 2.9 * (1 - t * 0.82) + 0.3;
    const bright = 0.82 + t * 0.26;
    // внутренний тёмный слой: короткие крутые лапы у ствола закрывают просветы (без плоских конусов)
    const kin = lod > 0.7 ? 5 : 4;
    for (let j = 0; j < kin; j++) {
      const a = i * 1.3 + (j / kin) * TAU + rand() * 0.5;
      const L = R * (0.5 + rand() * 0.12);
      const o = V(Math.cos(a) * 0.08, y + 0.15 + rand() * 0.2, Math.sin(a) * 0.08);
      firFrond(b, o, a, L, L * 0.8, L * 0.4, L * 0.35, L * 0.12, 2, (s, p, out) => {
        out.copy(FIR.deep).lerp(FIR.mid, s * 0.7).multiplyScalar(0.8 + t * 0.15);
      });
    }
    const k = Math.max(4, Math.round(lerp(10, 5, t) * (lod > 0.7 ? 1 : 0.75)));
    const rot = i * 0.95 + rand() * 0.6;
    for (let j = 0; j < k; j++) {
      const a = rot + (j / k) * TAU + (rand() - 0.5) * 0.55;
      const L = R * (0.85 + rand() * 0.3);
      const W = L * (0.6 + rand() * 0.14);
      const o = V(Math.cos(a) * 0.1, y + (rand() - 0.5) * 0.4, Math.sin(a) * 0.1);
      const tone = 0.9 + rand() * 0.2;
      const lift = L * (0.1 + rand() * 0.1), droop = L * (0.24 + rand() * 0.16);
      const frondColor = (s, p, out) => {
        if (s < 0.45) out.copy(FIR.deep).lerp(FIR.mid, s / 0.45);
        else out.copy(FIR.mid).lerp(FIR.outer, (s - 0.45) / 0.55).lerp(FIR.tip, smoothstep(0.75, 1, s) * 0.45);
        out.multiplyScalar(bright * tone);
      };
      firFrond(b, o, a, L, W, lift, droop, W * 0.16, segs, frondColor);
      // снег лежит сверху на лапах: снизу его почти не видно, сбоку и сверху - белые пласты
      if (snow) firFrond(b, o, a, L, W, lift, droop, W * 0.16, segs, frondColor, true);
    }
  }
  // верхушка: две скрещённые вертикальные лапы
  const top = V(0, 9.3, 0);
  for (let j = 0; j < 2; j++) {
    const a = j * Math.PI / 2 + rand();
    const n = V(Math.cos(a), 0, Math.sin(a));
    card(b, top.clone().setY(9.9), n, 1.0, 1.6, 0, ATLAS.fir, {
      color: (p, c) => c.copy(FIR.outer).lerp(FIR.tip, 0.3).multiplyScalar(1.05).lerp(SNOW, snow ? 0.4 : 0),
      sphereN: (p) => V(p.x, 0.6, p.z).normalize(), tint: snow ? 0.4 : 1, wind: 0.4,
    });
  }
  if (snow) {
    const g = blobGeometry(0.35, 1, seed + 99, 0.2, 1.2);
    g.scale(1, 0.7, 1);
    g.translate(0, 9.45, 0);
    b.addGeometry(g, { uv: WHITE_UV, tint: 0, color: (p, n, c) => c.copy(SNOW_SH).lerp(SNOW, smoothstep(-0.3, 0.7, n.y)) });
  }
  return b.toGeometry();
}

// Ярус ели для дальнего LOD: индексированный (общие вершины, гладкие нормали) - втрое меньше вершин,
// чем у firTier с отдельными треугольниками.
function firTierLite({ radius, height, y, spikes, droop, rot, seed, cDark, cLight, bright }) {
  const rand = mulberry32(seed);
  const pos = [], col = [], idx = [];
  const innerR = radius * 0.52, innerY = y + height * 0.38;
  const tmp = new THREE.Color();
  const push = (x, yy, z, t) => {
    pos.push(x, yy, z);
    tmp.copy(cDark).lerp(cLight, t).multiplyScalar(bright);
    col.push(tmp.r, tmp.g, tmp.b);
    return pos.length / 3 - 1;
  };
  const apex = push(0, y + height, 0, 0);
  const ring = [];
  for (let i = 0; i < spikes; i++) {
    const a = rot + (i / spikes) * TAU;
    ring.push(push(Math.cos(a) * innerR, innerY, Math.sin(a) * innerR, 0.45));
  }
  for (let i = 0; i < spikes; i++) {
    const a0 = rot + (i / spikes) * TAU, a1 = rot + ((i + 1) / spikes) * TAU;
    const am = (a0 + a1) / 2 + (rand() - 0.5) * 0.45 * (a1 - a0);
    const len = radius * (0.78 + rand() * 0.38);
    const tip = push(Math.cos(am) * len, y - height * droop * (0.5 + rand() * 0.9), Math.sin(am) * len, 1);
    const r0 = ring[i], r1 = ring[(i + 1) % spikes];
    idx.push(apex, r1, r0, r0, r1, tip);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

const BARK_FAR = C('#4a3424');

export function firFar({ seed = 1, snow = false } = {}) {
  const b = new PlantBuilder();
  trunk(b, 9.2, 0.32, 0.06, seed, { color: (t, a, i, k, out) => out.copy(BARK_FAR), segs: 5, rings: 2, flare: 0.2 });
  for (let i = 0; i < 4; i++) {
    const t = i / 3;
    const g = firTierLite({
      radius: 3.2 * (1 - t * 0.72), height: 2.9 - t * 0.6, y: 2.0 + i * 2.05, spikes: 9, droop: 0.42, rot: i * 0.6, seed: 90 + i,
      cDark: FIR.deep, cLight: FIR.outer, bright: 0.86 + t * 0.2,
    });
    b.addGeometry(g, {
      uv: WHITE_UV, tint: snow ? 0.4 : 1,
      color: snow ? (p, n, c) => c.lerp(SNOW, Math.max(0, n.y) * 0.75) : undefined,
    });
  }
  return b.toGeometry();
}

// ---------- лиственные: обычное, клён, берёза ----------

const CROWNS = {
  broad: [[0, 4.7, 0, 2.05], [1.45, 4.15, 0.55, 1.5], [-1.35, 4.3, -0.5, 1.55], [0.25, 5.95, -0.3, 1.35], [-0.45, 3.8, 1.25, 1.25], [0.6, 3.95, -1.3, 1.2]],
  maple: [[0, 4.9, 0, 2.2], [1.6, 4.2, 0.6, 1.55], [-1.5, 4.4, -0.55, 1.6], [0.2, 6.2, -0.2, 1.45], [-0.5, 3.9, 1.4, 1.3], [0.7, 4.0, -1.45, 1.3]],
  birch: [[0, 5.7, 0, 1.45], [0.95, 6.4, 0.35, 1.1], [-0.95, 5.3, -0.35, 1.2], [0.45, 4.6, 0.95, 1.0], [-0.25, 7.3, -0.15, 0.95],
    [-0.85, 6.4, 0.75, 1.0], [0.85, 5.0, -0.85, 1.0]],
  hero: [[0, 5.0, 0, 2.3], [1.75, 4.4, 0.6, 1.7], [-1.6, 4.6, -0.55, 1.75], [0.3, 6.5, -0.3, 1.6], [-0.5, 4.0, 1.5, 1.45],
    [0.7, 4.2, -1.55, 1.45], [1.3, 5.8, 1.0, 1.2], [-1.2, 6.0, 0.9, 1.25], [-0.9, 5.5, -1.4, 1.2]],
};

// Кроны: серый градиент (снизу темнее) + светлые пятна-блики сверху. Цвет даёт экземпляр (aTint = 1).
function crownShade(p, n, cy, r, seed) {
  const h = smoothstep(cy - r * 1.05, cy + r * 0.85, p.y);
  let v = lerp(0.5, 1.0, h);
  v *= 0.9 + 0.2 * noise3(p.x * 0.9, p.y * 0.9, p.z * 0.9, seed);
  // светлые кластеры на освещённой стороне
  v += 0.2 * smoothstep(0.58, 0.78, noise3(p.x * 0.7 + 5, p.y * 0.7, p.z * 0.7, seed + 9)) * Math.max(0, n.y + 0.2);
  return v;
}

export function broadDetailed({ seed = 1, kind = 'broad', lod = 1, hero = false } = {}) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const birch = kind === 'birch';
  const blobs = CROWNS[hero ? 'hero' : kind];
  const trunkH = birch ? 4.6 : hero ? 3.4 : 3.1;
  const barkC = birch ? birchColor(seed) : barkColor(C('#3f2c1e'), C('#755338'), seed);
  const tp = trunk(b, trunkH, birch ? 0.22 : hero ? 0.46 : 0.38, birch ? 0.12 : 0.2, seed, {
    color: barkC, segs: birch ? 7 : 9, flare: birch ? 0.12 : 0.45, rings: birch ? 10 : 6, bendAmp: birch ? 0.12 : 0.1,
  });
  if (!birch) roots(b, hero ? 6 : 4, hero ? 0.42 : 0.34, hero ? 1.3 : 0.9, seed + 5, barkC);
  const top = tp[tp.length - 1];

  // ветви к центрам крон
  const crownC = V();
  let wsum = 0;
  for (const [x, y, z, r] of blobs) { crownC.addScaledVector(V(x, y, z), r); wsum += r; }
  crownC.multiplyScalar(1 / wsum);
  blobs.forEach(([x, y, z, r], i) => {
    if (i === 0) return;
    const start = V(top.x * 0.8, trunkH * (0.62 + rand() * 0.3), top.z * 0.8);
    const end = V(x * 0.85, y - r * 0.25, z * 0.85);
    const mid = start.clone().lerp(end, 0.5).add(V(0, 0.35, 0));
    tube(b, [start, mid, end], (t) => lerp(birch ? 0.08 : 0.16, 0.04, t), { segs: 5, color: barkC, uv: WHITE_UV });
  });
  // вертикальный лидер в главную крону
  tube(b, [top.clone(), V(blobs[0][0], blobs[0][1], blobs[0][2])], (t) => lerp(birch ? 0.11 : 0.19, 0.06, t), { segs: 6, color: barkC, uv: WHITE_UV });

  const sphereN = (p, n) => {
    const d = p.clone().sub(crownC).normalize();
    return (n ? n.clone().multiplyScalar(0.5).addScaledVector(d, 0.6) : d).addScaledVector(UP, 0.2).normalize();
  };
  const rect = ATLAS[kind === 'maple' ? 'maple' : birch ? 'birch' : 'leaf'];
  // яркость сердцевины и карточек: клёны и берёзы светлее (яркая осенняя/светлая листва)
  const coreK = kind === 'broad' ? 0.52 : kind === 'maple' ? 0.72 : 0.6, cardK = kind === 'broad' ? 1.08 : 1.2;
  blobs.forEach(([x, y, z, r], i) => {
    const detail = (i === 0 || hero) && lod > 0.7 ? 2 : 1;
    // тёмная сердцевина меньше у берёзы: крона у неё ажурнее
    const g = blobGeometry(r * (birch ? 0.45 : 0.6), detail, seed * 31 + i, 0.15, 1.4);
    g.translate(x, y, z);
    b.addGeometry(g, {
      uv: WHITE_UV, tint: 1, wind: 0,
      normal: (p, n) => n.copy(sphereN(p, n)),
      color: (p, n, c) => { const v = crownShade(p, n, crownC.y, r + 1.5, seed) * coreK; c.setRGB(v, v, v); },
    });
    // карточки листвы поверх кроны: рваный силуэт
    const nCards = Math.round(r * r * (hero ? 20 : birch ? 26 : 18) * lod);
    for (let j = 0; j < nCards; j++) {
      const u = rand() * 2 - 1, a = rand() * TAU;
      let dy = u;
      if (dy < -0.6 && !hero) dy = -0.6 + (dy + 0.6) * 0.4; // снизу карточек меньше (у героя снизу тоже густо)
      const sxz = Math.sqrt(Math.max(0, 1 - dy * dy));
      const d = V(Math.cos(a) * sxz, dy, Math.sin(a) * sxz).normalize();
      const p = V(x, y, z).addScaledVector(d, r * (0.62 + Math.sqrt(rand()) * 0.38));
      const s = (birch ? 1.1 : hero ? 1.15 : 1.45) * (0.8 + rand() * 0.45);
      // карточка смотрит наружу с разбросом, чтобы крона не выглядела "чешуйчатой"
      const cn = d.clone().add(V(rand() - 0.5, rand() - 0.3, rand() - 0.5).multiplyScalar(0.9)).normalize();
      card(b, p, cn, s, s, rand() * TAU, rect, {
        sphereN: (q) => sphereN(q),
        color: (q, c) => { const v = Math.min(1.15, crownShade(q, d, crownC.y, r + 1.5, seed) * cardK); c.setRGB(v, v, v); },
        tint: 1, wind: 1, bend: s * 0.14,
      });
    }
  });
  return b.toGeometry();
}

export function broadFar({ seed = 1, kind = 'broad' } = {}) {
  const b = new PlantBuilder();
  const birch = kind === 'birch';
  const blobs = CROWNS[kind];
  trunk(b, birch ? 4.6 : 3.1, birch ? 0.22 : 0.38, birch ? 0.12 : 0.2, seed, {
    color: birch ? (t, a, i, k, out) => out.set('#ddd8ce') : (t, a, i, k, out) => out.copy(BARK_FAR), segs: 5, rings: 2, flare: 0.2,
  });
  const cy = blobs[0][1];
  [0, 1, 2, 3].forEach((i) => {
    const [x, y, z, r] = blobs[i];
    const g = blobGeometry(r * 1.08, i === 0 ? 1 : 0, seed * 31 + i, 0.12, 1.2);
    g.translate(x, y, z);
    b.addGeometry(g, {
      uv: WHITE_UV, tint: 1,
      color: (p, n, c) => { const v = crownShade(p, n, cy, 3.5, seed) * 0.95; c.setRGB(v, v, v); },
    });
  });
  return b.toGeometry();
}

// ---------- голое зимнее дерево ----------

export function bareDetailed({ seed = 1, lod = 1 } = {}) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const barkC = barkColor(C('#3d332d'), C('#6b5d52'), seed);
  const tp = trunk(b, 4.6, 0.3, 0.09, seed, { color: barkC, segs: 7, flare: 0.35 });
  const branch = (start, dir, len, r0, depth) => {
    const pts = [start.clone()];
    const d = dir.clone();
    for (let i = 1; i <= 3; i++) {
      d.add(V((rand() - 0.5) * 0.35, 0.12, (rand() - 0.5) * 0.35)).normalize();
      pts.push(pts[i - 1].clone().addScaledVector(d, len / 3));
    }
    tube(b, pts, (t) => lerp(r0, r0 * 0.3, t), { segs: depth === 0 ? 5 : depth === 1 ? 4 : 3, color: barkC, uv: WHITE_UV });
    if (depth >= (lod > 0.7 ? 2 : 1)) return;
    const kids = depth === 0 ? 3 : 2;
    for (let k = 0; k < kids; k++) {
      const t = 0.45 + rand() * 0.5;
      const i = Math.min(2, Math.floor(t * 3));
      const p = pts[i].clone().lerp(pts[i + 1], t * 3 - i);
      const a = rand() * TAU;
      const nd = dir.clone().multiplyScalar(0.6).add(V(Math.cos(a), 0.7, Math.sin(a))).normalize();
      branch(p, nd, len * (0.45 + rand() * 0.2), r0 * 0.5, depth + 1);
    }
  };
  const n = 5;
  for (let i = 0; i < n; i++) {
    const a = (i / n) * TAU + rand() * 0.6;
    const y = 2.0 + (i / n) * 2.2 + rand() * 0.3;
    const dir = V(Math.cos(a), 0.9 + rand() * 0.6, Math.sin(a)).normalize();
    branch(V(Math.cos(a) * 0.08, y, Math.sin(a) * 0.08), dir, 2.4 + rand() * 1.2, 0.12, 0);
  }
  branch(tp[tp.length - 1], V(0.1, 1, 0.05).normalize(), 1.8, 0.09, 1);
  // снег на верхних гранях веток
  snowOnTop(b, 0.35);
  return b.toGeometry();
}

// Перекрасить в снег грани, смотрящие вверх.
function snowOnTop(b, thr) {
  const P = b.nor;
  for (let i = 0; i < P.length; i += 9) {
    const ny = (P[i + 1] + P[i + 4] + P[i + 7]) / 3;
    if (ny < thr) continue;
    const k = smoothstep(thr, thr + 0.3, ny);
    for (let j = 0; j < 3; j++) {
      const ci = i + j * 3;
      b.col[ci] = lerp(b.col[ci], SNOW.r, k);
      b.col[ci + 1] = lerp(b.col[ci + 1], SNOW.g, k);
      b.col[ci + 2] = lerp(b.col[ci + 2], SNOW.b, k);
      b.tint[ci / 3] = 0;
    }
  }
}

export function bareFar({ seed = 1 } = {}) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const barkC = barkColor(C('#3d332d'), C('#6b5d52'), seed);
  trunk(b, 4.6, 0.3, 0.09, seed, { color: barkC, segs: 5, rings: 2, flare: 0.2 });
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * TAU + rand() * 0.6;
    const y = 2.0 + (i / 5) * 2.2;
    const s = V(0, y, 0), e = V(Math.cos(a) * 2.0, y + 2.0, Math.sin(a) * 2.0);
    tube(b, [s, e], (t) => lerp(0.12, 0.03, t), { segs: 3, color: barkC, uv: WHITE_UV });
  }
  snowOnTop(b, 0.35);
  return b.toGeometry();
}

// ---------- кусты ----------

const BUSH_BLOBS = [[0, 0.52, 0, 0.75], [0.58, 0.4, 0.22, 0.52], [-0.52, 0.42, -0.16, 0.56], [0.05, 0.38, -0.55, 0.45], [-0.15, 0.36, 0.55, 0.45]];

export function bushDetailed({ seed = 1, berries = false, snow = false, lod = 1 } = {}) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const cc = V(0, 0.45, 0);
  const sphereN = (p) => p.clone().sub(cc).normalize().addScaledVector(UP, 0.35).normalize();
  BUSH_BLOBS.forEach(([x, y, z, r], i) => {
    const g = blobGeometry(r * 0.8, 1, seed * 17 + i, 0.16, 1.3);
    g.translate(x, y, z);
    b.addGeometry(g, {
      uv: WHITE_UV, tint: 1,
      normal: (p, n) => n.copy(sphereN(p)),
      color: (p, n, c) => { const v = crownShade(p, n, 0.45, 0.9, seed) * 0.62; c.setRGB(v, v, v); },
    });
    const nCards = Math.round(r * r * 36 * lod);
    for (let j = 0; j < nCards; j++) {
      const a = rand() * TAU, dy = rand() * 1.3 - 0.35;
      const sxz = Math.sqrt(Math.max(0, 1 - Math.min(1, dy * dy)));
      const d = V(Math.cos(a) * sxz, Math.min(1, dy), Math.sin(a) * sxz).normalize();
      const p = V(x, y, z).addScaledVector(d, r * (0.72 + rand() * 0.28));
      const s = 0.72 * (0.8 + rand() * 0.4);
      card(b, p, d, s, s, rand() * TAU, ATLAS.leaf, {
        sphereN, tint: 1, wind: 1, bend: s * 0.12,
        color: (q, c) => { const v = Math.min(1.1, crownShade(q, d, 0.45, 0.9, seed) * 1.06); c.setRGB(v, v, v); },
      });
    }
  });
  if (berries) {
    const red = C('#c4162b'), dark = C('#7a0d1e');
    for (let i = 0; i < 26; i++) {
      const [x, y, z, r] = BUSH_BLOBS[i % BUSH_BLOBS.length];
      const a = rand() * TAU, dy = rand() * 1.1 - 0.2;
      const sxz = Math.sqrt(Math.max(0, 1 - dy * dy));
      const d = V(Math.cos(a) * sxz, dy, Math.sin(a) * sxz);
      const p = V(x, y, z).addScaledVector(d, r * 1.02);
      for (let k = 0; k < 3; k++) {
        const g = new THREE.IcosahedronGeometry(0.028 + rand() * 0.014, 0);
        g.translate(p.x + (rand() - 0.5) * 0.12, p.y + (rand() - 0.5) * 0.1, p.z + (rand() - 0.5) * 0.12);
        b.addGeometry(g, { uv: WHITE_UV, tint: 0, color: (q, n, c) => c.copy(dark).lerp(red, 0.5 + n.y * 0.5) });
      }
    }
  }
  if (snow) snowOnTop(b, 0.45);
  return b.toGeometry();
}

export function bushFar({ seed = 1, snow = false } = {}) {
  const b = new PlantBuilder();
  BUSH_BLOBS.slice(0, 3).forEach(([x, y, z, r], i) => {
    const g = blobGeometry(r * 1.12, 0, seed * 17 + i, 0.12, 1.2);
    g.translate(x, y, z);
    b.addGeometry(g, {
      uv: WHITE_UV, tint: 1,
      color: (p, n, c) => { const v = crownShade(p, n, 0.45, 0.9, seed) * 0.95; c.setRGB(v, v, v); },
    });
  });
  if (snow) snowOnTop(b, 0.45);
  return b.toGeometry();
}

// Радиусы (для отсечения по пирамиде видимости) и высоты типов при масштабе 1.
export const TYPE_BOUNDS = {
  fir: { r: 5.6, cy: 5.2 },
  firSnow: { r: 5.6, cy: 5.2 },
  broad: { r: 4.6, cy: 3.6 },
  maple: { r: 4.8, cy: 3.7 },
  birch: { r: 4.4, cy: 4.4 },
  bare: { r: 4.6, cy: 3.5 },
  bush: { r: 1.4, cy: 0.5 },
  berry: { r: 1.4, cy: 0.5 },
  bushSnow: { r: 1.4, cy: 0.5 },
};
