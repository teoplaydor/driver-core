// Данные рельефа без привязки к three.js: высоты, зоны, дорожки.
// Всё считается детерминированно, поэтому у всех игроков мир идентичен,
// а сервер ничего про рельеф знать не должен.

import { Noise2D, smoothstep, lerp } from '../util/math.js';
import { HUB_H, INTERIOR_X as INTERIOR_ORIGIN } from '../../shared/layout.js';

export const WORLD_RADIUS = 245; // за этой границей игрока не пускаем
export const GRID_HALF = 260;
export const CELL = 2.5; // шаг сетки высот, м
export const N = Math.round((GRID_HALF * 2) / CELL) + 1; // вершин по стороне
export const MIN = -GRID_HALF;
export const WATER_Y = -0.5;

// Интерьеры лежат далеко за пределами карты: x >= INTERIOR_X.
export const INTERIOR_X = INTERIOR_ORIGIN;
export const isInterior = (x) => x >= INTERIOR_X - 60;

export const LAKE = { x: -58, z: 42, r: 22 };

// Биомы: центр и радиус влияния. Всё остальное - луг.
export const ZONES = {
  autumn: { x: 150, z: -15, r: 105 },
  winter: { x: 5, z: -175, r: 105 },
  rain: { x: 25, z: 175, r: 100 },
  canyon: { x: -170, z: 10, r: 110 },
};
const ZONE_KEYS = Object.keys(ZONES);

// Ровные площадки под постройки. h: 'auto' - взять естественную высоту в центре.
export const SITES = [
  { id: 'hub', x: 0, z: 0, r: 30, blend: 28, h: HUB_H },
  { id: 'gazebo', x: 27, z: -19, r: 9, blend: 8, h: HUB_H },
  { id: 'cabin', x: -16, z: -176, r: 14, blend: 12, h: 'auto' },
  { id: 'farm', x: 156, z: -30, r: 18, blend: 14, h: 'auto' },
  { id: 'block', x: 22, z: 175, r: 16, blend: 12, h: 'auto' },
  { id: 'arena', x: -180, z: 14, r: 26, blend: 16, h: 'auto' },
];

// Дорожки (ломаные, потом сглаживаются сплайном Catmull-Rom).
export const PATHS = [
  { id: 'to-winter', pts: [[0, 0], [-6, -45], [10, -95], [-4, -140], [-16, -169]] },
  { id: 'to-autumn', pts: [[0, 0], [45, 6], [85, -18], [120, -8], [150, -24]] },
  { id: 'to-rain', pts: [[0, 0], [8, 45], [-6, 95], [16, 135], [22, 172]] },
  { id: 'to-arena', pts: [[0, 0], [-40, -4], [-85, 14], [-130, 6], [-168, 14]] },
  { id: 'gazebo', pts: [[0, 0], [12, -6], [27, -19]] },
  { id: 'lake', pts: [[0, 0], [-22, 18], [-42, 34]] },
];

const noiseA = new Noise2D(20241015);
const noiseB = new Noise2D(777);

const PATH_CELL = 2;
const PATH_MAX = 8;
const PN = Math.round((GRID_HALF * 2) / PATH_CELL) + 1;

const H = new Float32Array(N * N);
const PATHD = new Float32Array(PN * PN).fill(PATH_MAX);
let ready = false;

const _w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };

// Веса биомов в точке: сумма всегда 1.
export function zoneWeights(x, z, out = {}) {
  let sum = 0;
  for (const k of ZONE_KEYS) {
    const Z = ZONES[k];
    out[k] = smoothstep(Z.r, Z.r * 0.45, Math.hypot(x - Z.x, z - Z.z));
    sum += out[k];
  }
  if (sum > 1) {
    for (const k of ZONE_KEYS) out[k] /= sum;
    sum = 1;
  }
  out.meadow = 1 - sum;
  return out;
}

function baseHeight(x, z) {
  const r = Math.hypot(x, z);
  const w = zoneWeights(x, z, _w);
  const amp = 1 + w.winter * 0.7 - w.rain * 0.45;
  let h = noiseA.fbm(x * 0.0075, z * 0.0075, 4) * 10 * amp
    + noiseB.fbm(x * 0.03, z * 0.03, 3) * 1.4
    + w.winter * 3 - w.rain * 0.8;
  if (w.canyon > 0.001) {
    // Каньон: гребни и ступенчатые мезы с крутыми уступами, как оранжевые скалы в референсе.
    const ridge = 1 - Math.abs(noiseA.fbm(x * 0.011 + 40, z * 0.011 + 40, 3));
    const hh = (h + ridge * 30 + 2) / 8;
    const f = hh - Math.floor(hh);
    const terraced = (Math.floor(hh) + smoothstep(0.55, 0.78, f)) * 8;
    h = lerp(h, terraced, w.canyon * 0.92);
  }
  // Горный пояс по краю мира, чтобы карта была "замкнута" и тонула в тумане.
  const edge = smoothstep(WORLD_RADIUS - 60, WORLD_RADIUS + 5, r);
  h += edge * (22 + 10 * (noiseB.fbm(x * 0.02 + 9, z * 0.02 - 4, 3) * 0.5 + 0.5));
  return h;
}

function finalHeight(x, z) {
  let h = baseHeight(x, z);
  // Озеро всегда уходит ниже уровня воды, пологие берега.
  const dl = Math.hypot(x - LAKE.x, z - LAKE.z);
  h = lerp(h, WATER_Y - 3, 1 - smoothstep(LAKE.r * 0.5, LAKE.r * 1.6, dl));
  for (const s of SITES) {
    const d = Math.hypot(x - s.x, z - s.z);
    if (d < s.r + s.blend) h = lerp(h, s.h, 1 - smoothstep(s.r, s.r + s.blend, d));
  }
  return h;
}

function catmull(p0, p1, p2, p3, t) {
  const t2 = t * t, t3 = t2 * t;
  return [0, 1].map((i) => 0.5 * (
    2 * p1[i] + (-p0[i] + p2[i]) * t
    + (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t2
    + (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t3));
}

// Точки вдоль дорожки с шагом ~1.5 м. Нужны и для карты расстояний, и для фонарей.
export function samplePath(path, step = 1.5) {
  const pts = path.pts;
  const out = [];
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
    const len = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
    const n = Math.max(2, Math.ceil(len / step));
    for (let k = 0; k < n; k++) out.push(catmull(p0, p1, p2, p3, k / n));
  }
  out.push(pts[pts.length - 1].slice());
  return out;
}

export function initTerrain() {
  if (ready) return;
  for (const s of SITES) {
    if (s.h === 'auto') s.h = baseHeight(s.x, s.z);
  }
  for (let iz = 0; iz < N; iz++) {
    for (let ix = 0; ix < N; ix++) {
      H[iz * N + ix] = finalHeight(MIN + ix * CELL, MIN + iz * CELL);
    }
  }
  // Карта расстояний до дорожек: штампуем окрестность каждой точки сплайна.
  const reach = Math.ceil(PATH_MAX / PATH_CELL);
  for (const path of PATHS) {
    for (const [px, pz] of samplePath(path)) {
      const cx = Math.round((px - MIN) / PATH_CELL), cz = Math.round((pz - MIN) / PATH_CELL);
      for (let dz = -reach; dz <= reach; dz++) {
        for (let dx = -reach; dx <= reach; dx++) {
          const ix = cx + dx, iz = cz + dz;
          if (ix < 0 || iz < 0 || ix >= PN || iz >= PN) continue;
          const d = Math.hypot(MIN + ix * PATH_CELL - px, MIN + iz * PATH_CELL - pz);
          const idx = iz * PN + ix;
          if (d < PATHD[idx]) PATHD[idx] = d;
        }
      }
    }
  }
  ready = true;
}

export const getHeightGrid = () => H;

// Высота рельефа ровно по тем же треугольникам, что рисует меш (диагональ 00-11).
export function heightAt(x, z) {
  const gx = (x - MIN) / CELL, gz = (z - MIN) / CELL;
  if (gx < 0 || gz < 0 || gx >= N - 1 || gz >= N - 1) return 0; // интерьеры и "за краем"
  const ix = Math.floor(gx), iz = Math.floor(gz);
  const fx = gx - ix, fz = gz - iz;
  const i = iz * N + ix;
  const h00 = H[i], h10 = H[i + 1], h01 = H[i + N], h11 = H[i + N + 1];
  return fx <= fz
    ? h00 + (h11 - h01) * fx + (h01 - h00) * fz
    : h00 + (h10 - h00) * fx + (h11 - h10) * fz;
}

// Расстояние до ближайшей дорожки (не больше PATH_MAX).
export function pathDist(x, z) {
  const gx = (x - MIN) / PATH_CELL, gz = (z - MIN) / PATH_CELL;
  if (gx < 0 || gz < 0 || gx >= PN - 1 || gz >= PN - 1) return PATH_MAX;
  const ix = Math.floor(gx), iz = Math.floor(gz);
  const fx = gx - ix, fz = gz - iz;
  const i = iz * PN + ix;
  return lerp(lerp(PATHD[i], PATHD[i + 1], fx), lerp(PATHD[i + PN], PATHD[i + PN + 1], fx), fz);
}

export function slopeAt(x, z) {
  const e = 1.2;
  const dx = heightAt(x + e, z) - heightAt(x - e, z);
  const dz = heightAt(x, z + e) - heightAt(x, z - e);
  return Math.hypot(dx, dz) / (2 * e);
}

export const isWater = (x, z) => heightAt(x, z) < WATER_Y;

// Тип поверхности под ногами - для звуков шагов.
export function groundTypeAt(x, z) {
  if (x >= INTERIOR_X - 60) return 'wood';
  if (pathDist(x, z) < 1.7) return 'path';
  const w = zoneWeights(x, z, _w);
  if (w.winter > 0.5) return 'snow';
  if (heightAt(x, z) < WATER_Y + 0.5 || w.canyon > 0.5) return 'sand';
  return 'grass';
}
