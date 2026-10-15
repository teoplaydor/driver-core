// Помощники для процедурной геометрии: все детали склеиваются в одну геометрию
// с цветом в вершинах (position + normal + color), поэтому дерево - один draw call.

import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { hash2, mulberry32, TAU } from '../util/math.js';

const _c = new THREE.Color();

// Не-индексированная геометрия без uv, с нормалями: так любые примитивы склеиваются.
export function prep(geo) {
  const g = geo.index ? geo.toNonIndexed() : geo;
  if (g.getAttribute('uv')) g.deleteAttribute('uv');
  if (!g.getAttribute('normal')) g.computeVertexNormals();
  return g;
}

export function colorize(geo, fn) {
  const pos = geo.getAttribute('position');
  const col = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    fn(pos.getX(i), pos.getY(i), pos.getZ(i), _c);
    col[i * 3] = _c.r; col[i * 3 + 1] = _c.g; col[i * 3 + 2] = _c.b;
  }
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return geo;
}

export const solid = (geo, hex) => {
  const c = new THREE.Color(hex);
  return colorize(geo, (x, y, z, out) => out.copy(c));
};

// Вертикальный градиент между двумя цветами (hex или Color) в диапазоне y0..y1.
export function gradientY(geo, bottom, top, y0, y1) {
  const a = new THREE.Color(bottom), b = new THREE.Color(top);
  return colorize(geo, (x, y, z, out) => out.copy(a).lerp(b, Math.min(1, Math.max(0, (y - y0) / (y1 - y0)))));
}

// Детерминированное "помятие" вершин: одинаковые позиции смещаются одинаково, трещин нет.
export function jitter(geo, amount, seed = 0) {
  const pos = geo.getAttribute('position');
  for (let i = 0; i < pos.count; i++) {
    const ix = Math.round(pos.getX(i) * 997), iy = Math.round(pos.getY(i) * 991), iz = Math.round(pos.getZ(i) * 983);
    pos.setXYZ(
      i,
      pos.getX(i) + (hash2(ix, iy, seed) - 0.5) * 2 * amount,
      pos.getY(i) + (hash2(iy, iz, seed + 1) - 0.5) * 2 * amount,
      pos.getZ(i) + (hash2(iz, ix, seed + 2) - 0.5) * 2 * amount,
    );
  }
  pos.needsUpdate = true;
  return geo;
}

// Для не-индексированной геометрии нормали по граням дают гранёный low-poly вид.
export const faceted = (geo) => { geo.computeVertexNormals(); return geo; };

export function mergeAll(list) {
  const geos = list.map(prep);
  const merged = mergeGeometries(geos, false);
  geos.forEach((g) => g.dispose());
  return merged;
}

// Ярус еловой лапы: рваный по краю "зонтик" с опущенными кончиками - как painterly-ёлки в референсе.
// Цвет в вершинах: тёмный у ствола, светлые кончики (ambient occlusion "на глаз").
export function firTier({
  radius, height, spikes = 13, droop = 0.4, y = 0, rot = 0, seed = 1,
  cDark = new THREE.Color('#2b6b3a'), cLight = new THREE.Color('#8cc24c'), bright = 1,
}) {
  const rand = mulberry32(seed);
  const pos = [], col = [];
  const innerR = radius * 0.52, innerY = y + height * 0.38;
  const apex = [0, y + height, 0];
  const tmp = new THREE.Color();
  const push = (p, t) => {
    pos.push(p[0], p[1], p[2]);
    tmp.copy(cDark).lerp(cLight, t).multiplyScalar(bright);
    col.push(tmp.r, tmp.g, tmp.b);
  };
  for (let i = 0; i < spikes; i++) {
    const a0 = rot + (i / spikes) * TAU, a1 = rot + ((i + 1) / spikes) * TAU;
    const am = (a0 + a1) / 2 + (rand() - 0.5) * 0.45 * (a1 - a0);
    const len = radius * (0.78 + rand() * 0.38);
    const tipY = y - height * droop * (0.5 + rand() * 0.9);
    const i0 = [Math.cos(a0) * innerR, innerY, Math.sin(a0) * innerR];
    const i1 = [Math.cos(a1) * innerR, innerY, Math.sin(a1) * innerR];
    const tip = [Math.cos(am) * len, tipY, Math.sin(am) * len];
    push(apex, 0); push(i1, 0.45); push(i0, 0.45);      // верхний конус
    push(i0, 0.45); push(i1, 0.45); push(tip, 1);        // зубец
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  g.computeVertexNormals();
  return g;
}

// Применить серый множитель цвета (вершинный градиент для tint-а экземпляров).
export function grayGradientY(geo, v0, v1, y0, y1) {
  return colorize(geo, (x, y, z, out) => {
    const t = Math.min(1, Math.max(0, (y - y0) / (y1 - y0)));
    const v = v0 + (v1 - v0) * t;
    out.setRGB(v, v, v);
  });
}
