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

// ---------- построитель "богатой" геометрии растений ----------
// Вершины: position, normal, color, uv (атлас листвы), aTint (0 - цвет экземпляра не применять,
// 1 - применять), aWind (сила трепетания листа). Треугольники без индексов: цвет можно задавать по граням.

const _v0 = new THREE.Vector3();
const _n = new THREE.Vector3();

export class PlantBuilder {
  constructor() {
    this.pos = []; this.nor = []; this.col = []; this.uv = []; this.tint = []; this.wind = [];
  }

  get count() { return this.pos.length / 3; }

  vert(p, n, c, uv, tint = 0, wind = 0) {
    this.pos.push(p.x, p.y, p.z);
    this.nor.push(n.x, n.y, n.z);
    this.col.push(c.r, c.g, c.b);
    this.uv.push(uv[0], uv[1]);
    this.tint.push(tint);
    this.wind.push(wind);
  }

  // Добавить готовую three-геометрию. color: hex | fn(p, n, out, i) (out уже содержит цвет вершины
  // из атрибута color, если он есть) | undefined - взять цвет из геометрии.
  addGeometry(geo, { color, uv = null, tint = 0, wind = 0, matrix = null, normal = null } = {}) {
    const g = geo.index ? geo.toNonIndexed() : geo;
    if (!g.getAttribute('normal')) g.computeVertexNormals();
    const P = g.getAttribute('position'), N = g.getAttribute('normal'), U = g.getAttribute('uv');
    const C = g.getAttribute('color');
    const nm = matrix ? new THREE.Matrix3().getNormalMatrix(matrix) : null;
    const c = new THREE.Color();
    for (let i = 0; i < P.count; i++) {
      _v0.fromBufferAttribute(P, i);
      _n.fromBufferAttribute(N, i);
      if (matrix) { _v0.applyMatrix4(matrix); _n.applyMatrix3(nm).normalize(); }
      if (normal) normal(_v0, _n);
      if (C) c.fromBufferAttribute(C, i); else c.setRGB(1, 1, 1);
      if (typeof color === 'function') color(_v0, _n, c, i);
      else if (color !== undefined) c.set(color);
      const t = typeof tint === 'function' ? tint(_v0, _n, i) : tint;
      const w = typeof wind === 'function' ? wind(_v0, _n, i) : wind;
      this.vert(_v0, _n, c, uv || (U ? [U.getX(i), U.getY(i)] : [0, 0]), t, w);
    }
    return this;
  }

  toGeometry() {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(this.pos, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(this.nor, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(this.col, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(this.uv, 2));
    g.setAttribute('aTint', new THREE.Float32BufferAttribute(this.tint, 1));
    g.setAttribute('aWind', new THREE.Float32BufferAttribute(this.wind, 1));
    g.computeBoundingSphere();
    g.computeBoundingBox();
    return g;
  }
}

// Компактный шаблон для склейки многих копий: совпадающие вершины объединены, есть индекс.
// Возвращает { nv, ni, pos, nor, col, tint, wind, index } (Float32Array / Uint32Array).
export function compactTemplate(geo) {
  const P = geo.getAttribute('position'), N = geo.getAttribute('normal'), Cc = geo.getAttribute('color');
  const T = geo.getAttribute('aTint'), W = geo.getAttribute('aWind');
  const src = geo.index ? geo.index.array : null;
  const n = src ? src.length : P.count;
  const key = new Map();
  const keep = [];
  const index = new Uint32Array(n);
  for (let t = 0; t < n; t++) {
    const i = src ? src[t] : t;
    const k = `${Math.round(P.getX(i) * 2000)},${Math.round(P.getY(i) * 2000)},${Math.round(P.getZ(i) * 2000)},`
      + `${Math.round(N.getX(i) * 60)},${Math.round(N.getY(i) * 60)},${Math.round(N.getZ(i) * 60)},`
      + (Cc ? `${Math.round(Cc.getX(i) * 200)},${Math.round(Cc.getY(i) * 200)},${Math.round(Cc.getZ(i) * 200)},` : '')
      + (T ? Math.round(T.getX(i) * 20) : '') + ',' + (W ? Math.round(W.getX(i) * 20) : '');
    let j = key.get(k);
    if (j === undefined) { j = keep.length; key.set(k, j); keep.push(i); }
    index[t] = j;
  }
  const m = keep.length;
  const out = {
    nv: m, ni: n, index,
    pos: new Float32Array(m * 3), nor: new Float32Array(m * 3), col: new Float32Array(m * 3),
    tint: new Float32Array(m), wind: new Float32Array(m),
  };
  keep.forEach((i, j) => {
    out.pos[j * 3] = P.getX(i); out.pos[j * 3 + 1] = P.getY(i); out.pos[j * 3 + 2] = P.getZ(i);
    let nx = N.getX(i), ny = N.getY(i), nz = N.getZ(i);
    const l = Math.hypot(nx, ny, nz);
    if (l > 1e-4) { nx /= l; ny /= l; nz /= l; } else { nx = 0; ny = 1; nz = 0; }
    out.nor[j * 3] = nx; out.nor[j * 3 + 1] = ny; out.nor[j * 3 + 2] = nz;
    if (Cc) { out.col[j * 3] = Cc.getX(i); out.col[j * 3 + 1] = Cc.getY(i); out.col[j * 3 + 2] = Cc.getZ(i); } else out.col.fill(1, j * 3, j * 3 + 3);
    out.tint[j] = T ? T.getX(i) : 1;
    out.wind[j] = W ? W.getX(i) : 0;
  });
  return out;
}

// Склеить много копий компактных шаблонов в одну геометрию (статичные дальние деревья, камни).
// items: [{ tpl, matrix (Matrix4), color (Color|null), anchor: [x, y, z] }]
// Цвет экземпляра применяется к вершинам пропорционально их aTint. Нормали - Int8, цвет - Uint8.
export function mergeTemplates(items, { anchors = false } = {}) {
  let nv = 0, ni = 0;
  for (const it of items) { nv += it.tpl.nv; ni += it.tpl.ni; }
  const pos = new Float32Array(nv * 3), nor = new Int8Array(nv * 3), col = new Uint8Array(nv * 3);
  const anc = anchors ? new Float32Array(nv * 3) : null;
  const index = nv > 65535 ? new Uint32Array(ni) : new Uint16Array(ni);
  const nm = new THREE.Matrix3();
  let vo = 0, io = 0;
  for (const it of items) {
    const t = it.tpl, e = it.matrix.elements;
    const ne = nm.getNormalMatrix(it.matrix).elements;
    const ic = it.color;
    for (let k = 0; k < t.nv; k++) {
      const px = t.pos[k * 3], py = t.pos[k * 3 + 1], pz = t.pos[k * 3 + 2];
      const o = (vo + k) * 3;
      pos[o] = e[0] * px + e[4] * py + e[8] * pz + e[12];
      pos[o + 1] = e[1] * px + e[5] * py + e[9] * pz + e[13];
      pos[o + 2] = e[2] * px + e[6] * py + e[10] * pz + e[14];
      const nx = t.nor[k * 3], ny = t.nor[k * 3 + 1], nz = t.nor[k * 3 + 2];
      let wx = ne[0] * nx + ne[3] * ny + ne[6] * nz, wy = ne[1] * nx + ne[4] * ny + ne[7] * nz, wz = ne[2] * nx + ne[5] * ny + ne[8] * nz;
      const L = Math.hypot(wx, wy, wz);
      if (L > 1e-6) { wx /= L; wy /= L; wz /= L; } else { wx = 0; wy = 1; wz = 0; }
      nor[o] = Math.round(wx * 127); nor[o + 1] = Math.round(wy * 127); nor[o + 2] = Math.round(wz * 127);
      const tk = t.tint[k];
      let r = t.col[k * 3], g = t.col[k * 3 + 1], b = t.col[k * 3 + 2];
      if (ic) { r *= 1 + (ic.r - 1) * tk; g *= 1 + (ic.g - 1) * tk; b *= 1 + (ic.b - 1) * tk; }
      col[o] = Math.min(255, Math.round(r * 255)); col[o + 1] = Math.min(255, Math.round(g * 255)); col[o + 2] = Math.min(255, Math.round(b * 255));
      if (anc) { anc[o] = it.anchor[0]; anc[o + 1] = it.anchor[1]; anc[o + 2] = it.anchor[2]; }
    }
    for (let k = 0; k < t.ni; k++) index[io + k] = t.index[k] + vo;
    vo += t.nv; io += t.ni;
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.BufferAttribute(nor, 3, true));
  g.setAttribute('color', new THREE.BufferAttribute(col, 3, true));
  if (anc) g.setAttribute('aAnchor', new THREE.BufferAttribute(anc, 3));
  g.setIndex(new THREE.BufferAttribute(index, 1));
  g.computeBoundingSphere();
  return g;
}

// Гладкий 3D-шум (значения в узлах решётки) для "шумных" крон и камней.
export function noise3(x, y, z, seed = 0) {
  const ix = Math.floor(x), iy = Math.floor(y), iz = Math.floor(z);
  const fx = x - ix, fy = y - iy, fz = z - iz;
  const u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy), w = fz * fz * (3 - 2 * fz);
  const h = (a, bb, c) => hash2(a * 73856093 + c * 83492791, bb * 19349663 + seed, seed);
  const l = (a, bb, t) => a + (bb - a) * t;
  return l(
    l(l(h(ix, iy, iz), h(ix + 1, iy, iz), u), l(h(ix, iy + 1, iz), h(ix + 1, iy + 1, iz), u), v),
    l(l(h(ix, iy, iz + 1), h(ix + 1, iy, iz + 1), u), l(h(ix, iy + 1, iz + 1), h(ix + 1, iy + 1, iz + 1), u), v),
    w,
  );
}

// Сужающаяся труба вдоль ломаной (ствол, ветка, корень). radius(t, angle) -> радиус.
// color(t, angle, ring, seg, out) - цвет по граням (можно рисовать пятна коры).
export function tube(b, pts, radius, { segs = 7, color, uv, tint = 0, wind = 0, capEnd = false } = {}) {
  const n = pts.length;
  const T = [], Nn = [], B = [];
  for (let i = 0; i < n; i++) {
    const a = pts[Math.max(0, i - 1)], c = pts[Math.min(n - 1, i + 1)];
    T.push(new THREE.Vector3().subVectors(c, a).normalize());
  }
  // параллельный перенос рамки вдоль оси
  const ref = Math.abs(T[0].y) < 0.9 ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(1, 0, 0);
  let N0 = new THREE.Vector3().crossVectors(T[0], ref).normalize();
  const q = new THREE.Quaternion();
  for (let i = 0; i < n; i++) {
    if (i > 0) N0 = N0.clone().applyQuaternion(q.setFromUnitVectors(T[i - 1], T[i])).normalize();
    Nn.push(N0.clone());
    B.push(new THREE.Vector3().crossVectors(T[i], N0).normalize());
  }
  const rings = [];
  for (let i = 0; i < n; i++) {
    const row = [];
    for (let k = 0; k <= segs; k++) {
      const a = (k / segs) * TAU;
      const d = new THREE.Vector3().addScaledVector(Nn[i], Math.cos(a)).addScaledVector(B[i], Math.sin(a));
      row.push({ p: pts[i].clone().addScaledVector(d, radius(i / (n - 1), a)), n: d });
    }
    rings.push(row);
  }
  const c = new THREE.Color();
  const W = uv || [0.875, 0.125]; // по умолчанию - непрозрачный белый участок атласа листвы
  for (let i = 0; i < n - 1; i++) {
    const t0 = typeof tint === 'function' ? tint(i / (n - 1)) : tint;
    const w0 = typeof wind === 'function' ? wind(i / (n - 1)) : wind;
    for (let k = 0; k < segs; k++) {
      const a = rings[i][k], bb = rings[i][k + 1], cc = rings[i + 1][k], d = rings[i + 1][k + 1];
      color((i + 0.5) / (n - 1), ((k + 0.5) / segs) * TAU, i, k, c);
      b.vert(a.p, a.n, c, W, t0, w0); b.vert(cc.p, cc.n, c, W, t0, w0); b.vert(bb.p, bb.n, c, W, t0, w0);
      b.vert(bb.p, bb.n, c, W, t0, w0); b.vert(cc.p, cc.n, c, W, t0, w0); b.vert(d.p, d.n, c, W, t0, w0);
    }
  }
  if (capEnd) {
    const tip = pts[n - 1];
    color(1, 0, n - 1, 0, c);
    for (let k = 0; k < segs; k++) {
      const a = rings[n - 1][k], bb = rings[n - 1][k + 1];
      b.vert(a.p, T[n - 1], c, W); b.vert(bb.p, T[n - 1], c, W); b.vert(tip, T[n - 1], c, W);
    }
  }
  return b;
}

// Склейка совпадающих вершин: нужна для гладких нормалей у "шумных" шаров.
export function mergeVerticesSimple(geo) {
  const P = geo.getAttribute('position');
  const map = new Map();
  const pos = [];
  const idx = [];
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i);
    const key = `${Math.round(x * 1e4)},${Math.round(y * 1e4)},${Math.round(z * 1e4)}`;
    let j = map.get(key);
    if (j === undefined) { j = pos.length / 3; map.set(key, j); pos.push(x, y, z); }
    idx.push(j);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setIndex(idx);
  return g;
}

// Гладкий "шумный" шар (крона, куст, сугроб): икосаэдр со сглаженными нормалями.
export function blobGeometry(radius, detail, seed, amp = 0.16, freq = 1.3) {
  const g = mergeVerticesSimple(new THREE.IcosahedronGeometry(radius, detail));
  const P = g.getAttribute('position');
  for (let i = 0; i < P.count; i++) {
    _v0.fromBufferAttribute(P, i);
    const f = freq / radius;
    const k = 1 + (noise3(_v0.x * f + 3.1, _v0.y * f, _v0.z * f - 1.7, seed) - 0.5) * 2 * amp;
    _v0.multiplyScalar(k);
    P.setXYZ(i, _v0.x, _v0.y, _v0.z);
  }
  g.computeVertexNormals();
  return g;
}

// Плоская карточка с текстурой из атласа: центр, нормаль "наружу" n, поворот вокруг нормали rot.
// sphereN(p) -> нормаль вершины ("сферизованная", чтобы свет ложился как на объёмную крону).
export function card(b, center, n, w, h, rot, rect, { color, sphereN = null, tint = 1, wind = 1, bend = 0 } = {}) {
  const up = Math.abs(n.y) > 0.95 ? new THREE.Vector3(1, 0, 0) : new THREE.Vector3(0, 1, 0);
  const ax = new THREE.Vector3().crossVectors(up, n).normalize();
  const ay = new THREE.Vector3().crossVectors(n, ax).normalize();
  const cr = Math.cos(rot), sr = Math.sin(rot);
  const X = ax.clone().multiplyScalar(cr).addScaledVector(ay, sr);
  const Y = ay.clone().multiplyScalar(cr).addScaledVector(ax, -sr);
  const [u0, v0, u1, v1] = rect;
  const corners = [[-1, -1, u0, v0], [1, -1, u1, v0], [1, 1, u1, v1], [-1, 1, u0, v1]];
  const vs = corners.map(([sx, sy, u, v]) => {
    const p = center.clone().addScaledVector(X, sx * w / 2).addScaledVector(Y, sy * h / 2);
    // края карточки слегка уходят назад - лист не выглядит фанеркой
    p.addScaledVector(n, -bend);
    const nn = sphereN ? sphereN(p) : n.clone();
    const c = new THREE.Color();
    if (typeof color === 'function') color(p, c); else c.set(color ?? 0xffffff);
    return { p, n: nn, c, uv: [u, v] };
  });
  const mid = {
    p: center.clone(), n: sphereN ? sphereN(center) : n.clone(), c: new THREE.Color(), uv: [(u0 + u1) / 2, (v0 + v1) / 2],
  };
  if (typeof color === 'function') color(mid.p, mid.c); else mid.c.set(color ?? 0xffffff);
  // веер из 4 треугольников вокруг центра: центр выпуклый, края загнуты
  const put = (v) => b.vert(v.p, v.n, v.c, v.uv, tint, wind);
  for (let i = 0; i < 4; i++) { put(vs[i]); put(vs[(i + 1) % 4]); put(mid); }
  return b;
}
