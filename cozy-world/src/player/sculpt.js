// Мини-«скульптор» для персонажей: собирает процедурные части (параметрические поверхности, трубки,
// примитивы three) в одну индексированную геометрию с цветом в вершинах и весами костей
// (skinIndex/skinWeight). Гладкие нормали считаются по каждой части отдельно, швов на сферах нет.

import * as THREE from 'three';

const _c = new THREE.Color();
const _p = new THREE.Vector3();
const _q = new THREE.Vector3();
const _a = new THREE.Vector3();
const _b = new THREE.Vector3();
const _nm = new THREE.Matrix3();
const _m = new THREE.Matrix4();
const _qt = new THREE.Quaternion();
const _e = new THREE.Euler();
const _s = new THREE.Vector3();

export const C = (hex) => new THREE.Color(hex);
export const V = (x = 0, y = 0, z = 0) => new THREE.Vector3(x, y, z);

// Цвет, умноженный на k (k > 1 — светлее).
export const tint = (c, k) => new THREE.Color(c).multiplyScalar(k);
export const mix = (a, b, t) => new THREE.Color(a).lerp(new THREE.Color(b), t);

// Плавная ступенька, работает и при e0 > e1.
export function sstep(e0, e1, x) {
  const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
  return t * t * (3 - 2 * t);
}

// Матрица из позиции, поворота (Euler XYZ) и масштаба.
export function mat(pos = [0, 0, 0], rot = [0, 0, 0], scl = [1, 1, 1]) {
  return new THREE.Matrix4().compose(
    new THREE.Vector3(...pos), new THREE.Quaternion().setFromEuler(new THREE.Euler(...rot)), new THREE.Vector3(...scl),
  );
}

// Матрица по базису: локальные оси x, y, z (Vector3) и начало координат.
export function basis(origin, x, y, z, scale = 1) {
  const m = new THREE.Matrix4().makeBasis(x.clone().multiplyScalar(scale), y.clone().multiplyScalar(scale), z.clone().multiplyScalar(scale));
  m.setPosition(origin);
  return m;
}

// Сглаженные нормали индексированной части (площадные веса).
function smoothNormals(P, I) {
  const N = new Float32Array(P.length);
  for (let k = 0; k < I.length; k += 3) {
    const ia = I[k] * 3, ib = I[k + 1] * 3, ic = I[k + 2] * 3;
    _a.set(P[ib] - P[ia], P[ib + 1] - P[ia + 1], P[ib + 2] - P[ia + 2]);
    _b.set(P[ic] - P[ia], P[ic + 1] - P[ia + 1], P[ic + 2] - P[ia + 2]);
    _a.cross(_b);
    for (const i of [ia, ib, ic]) { N[i] += _a.x; N[i + 1] += _a.y; N[i + 2] += _a.z; }
  }
  for (let i = 0; i < N.length; i += 3) {
    const l = Math.hypot(N[i], N[i + 1], N[i + 2]) || 1;
    N[i] /= l; N[i + 1] /= l; N[i + 2] /= l;
  }
  return N;
}

// Направление на единичной сфере: u = 0 — вперёд (−Z), u растёт к +X; v = 0 — низ, v = 1 — верх.
export function sphereDir(u, v, out) {
  const lat = (v - 0.5) * Math.PI, lon = u * Math.PI * 2;
  const cl = Math.cos(lat);
  return out.set(cl * Math.sin(lon), Math.sin(lat), -cl * Math.cos(lon));
}

class Slot {
  constructor() {
    this.pos = []; this.nrm = []; this.col = []; this.si = []; this.sw = []; this.idx = [];
  }
}

const _w = [0, 0, 0, 0, 0, 0, 0, 0];

export class Sculpt {
  constructor(slots = 1) {
    this.slots = Array.from({ length: slots }, () => new Slot());
    this.slot = 0;
  }

  // Готовые массивы части. color: THREE.Color | fn(x, y, z, out, t); bone: индекс | fn(x, y, z, t) -> [b0, w0, b1, w1, ...]
  part(P, N, I, color, bone, T) {
    const s = this.slots[this.slot];
    const base = s.pos.length / 3;
    const n = P.length / 3;
    for (let i = 0; i < n; i++) {
      const x = P[i * 3], y = P[i * 3 + 1], z = P[i * 3 + 2];
      const t = T ? T[i] : 0;
      s.pos.push(x, y, z);
      s.nrm.push(N[i * 3], N[i * 3 + 1], N[i * 3 + 2]);
      if (typeof color === 'function') { color(x, y, z, _c, t); s.col.push(_c.r, _c.g, _c.b); } else s.col.push(color.r, color.g, color.b);
      if (typeof bone === 'function') pushWeights(s, bone(x, y, z, t));
      else { s.si.push(bone, 0, 0, 0); s.sw.push(1, 0, 0, 0); }
    }
    for (let i = 0; i < I.length; i++) s.idx.push(I[i] + base);
  }

  // Примитив three (индексированный) с матрицей m.
  geo(g, m, color, bone) {
    const pa = g.getAttribute('position'), na = g.getAttribute('normal');
    _nm.getNormalMatrix(m);
    const P = new Float32Array(pa.count * 3), N = new Float32Array(pa.count * 3);
    for (let i = 0; i < pa.count; i++) {
      _p.fromBufferAttribute(pa, i).applyMatrix4(m);
      _q.fromBufferAttribute(na, i).applyMatrix3(_nm).normalize();
      P[i * 3] = _p.x; P[i * 3 + 1] = _p.y; P[i * 3 + 2] = _p.z;
      N[i * 3] = _q.x; N[i * 3 + 1] = _q.y; N[i * 3 + 2] = _q.z;
    }
    const I = g.index ? Array.from(g.index.array) : Array.from({ length: pa.count }, (_, i) => i);
    g.dispose();
    this.part(P, N, I, color, bone);
  }

  // Параметрическая поверхность fn(u, v, out). wrap — замкнута по u; poles — v = 0 и v = 1 сходятся в точку.
  grid(fn, nu, nv, opts, color, bone) {
    const { wrap = true, poles = false, flip = false } = opts || {};
    const P = [], I = [], T = [];
    const cols = wrap ? nu : nu + 1;
    const v0 = poles ? 1 : 0, v1 = poles ? nv - 1 : nv;
    for (let j = v0; j <= v1; j++) {
      for (let i = 0; i < cols; i++) {
        fn(i / nu, j / nv, _p);
        P.push(_p.x, _p.y, _p.z);
        T.push(j / nv);
      }
    }
    const id = (i, j) => (j - v0) * cols + (wrap ? i % nu : i);
    const tri = (a, b, c) => (flip ? I.push(a, c, b) : I.push(a, b, c));
    for (let j = v0; j < v1; j++) {
      for (let i = 0; i < nu; i++) {
        const a = id(i, j), b = id(i + 1, j), c = id(i, j + 1), d = id(i + 1, j + 1);
        tri(a, c, b); tri(b, c, d);
      }
    }
    if (poles) {
      const pb = P.length / 3;
      fn(0, 0, _p); P.push(_p.x, _p.y, _p.z); T.push(0);
      fn(0, 1, _p); P.push(_p.x, _p.y, _p.z); T.push(1);
      for (let i = 0; i < nu; i++) {
        tri(pb, id(i, v0), id(i + 1, v0));
        tri(id(i, v1), pb + 1, id(i + 1, v1));
      }
    }
    this.part(P, smoothNormals(P, I), I, color, bone, T);
  }

  // Эллипсоид: центр, радиусы, необязательный поворот (Euler) и деформация deform(dir, out) по направлению.
  ellipsoid(c, r, nu, nv, color, bone, { rot = null, deform = null } = {}) {
    const q = rot ? new THREE.Quaternion().setFromEuler(new THREE.Euler(...rot)) : null;
    const d = new THREE.Vector3();
    this.grid((u, v, out) => {
      sphereDir(u, v, d);
      out.set(d.x * r[0], d.y * r[1], d.z * r[2]);
      if (deform) deform(d, out);
      if (q) out.applyQuaternion(q);
      out.add(c);
    }, nu, nv, { poles: true }, color, bone);
  }

  // Трубка вдоль точек pts с радиусами radii (число или массив). Концы скругляются полусферами.
  // opts: radial, caps [начало, конец], closed (кольцо), ref (ось для рамки без закрутки), squash [по N, по B].
  tube(pts, radii, opts, color, bone) {
    const { radial = 8, caps = [true, true], closed = false, ref = null, squash = null, capSegs = 3 } = opts || {};
    const n = pts.length;
    const R = (i) => (typeof radii === 'number' ? radii : radii[i]);
    const T = [], Nn = [], Bn = [];
    for (let i = 0; i < n; i++) {
      const pa = closed ? pts[(i - 1 + n) % n] : pts[Math.max(0, i - 1)];
      const pb = closed ? pts[(i + 1) % n] : pts[Math.min(n - 1, i + 1)];
      T.push(new THREE.Vector3().subVectors(pb, pa).normalize());
    }
    for (let i = 0; i < n; i++) {
      let N;
      if (ref) N = new THREE.Vector3().crossVectors(T[i], ref);
      if (!N || N.lengthSq() < 1e-8) {
        if (i === 0) {
          const t = T[0];
          N = Math.abs(t.y) < 0.9 ? new THREE.Vector3(0, 1, 0).cross(t) : new THREE.Vector3(1, 0, 0).cross(t);
        } else {
          N = Nn[i - 1].clone().addScaledVector(T[i], -T[i].dot(Nn[i - 1]));
        }
      }
      N.normalize();
      Nn.push(N);
      Bn.push(new THREE.Vector3().crossVectors(T[i], N).normalize());
    }
    // кольца: [центр, радиус, i-опорной точки, t]
    const rings = [];
    const sq = squash || [1, 1];
    if (!closed && caps[0]) {
      for (let k = capSegs; k >= 1; k--) {
        const a = (k / (capSegs + 1)) * Math.PI / 2;
        rings.push({ c: pts[0].clone().addScaledVector(T[0], -R(0) * Math.sin(a) * sq[0]), r: R(0) * Math.cos(a), i: 0, t: 0 });
      }
    }
    for (let i = 0; i < n; i++) rings.push({ c: pts[i], r: R(i), i, t: closed ? i / n : i / (n - 1) });
    if (!closed && caps[1]) {
      for (let k = 1; k <= capSegs; k++) {
        const a = (k / (capSegs + 1)) * Math.PI / 2;
        rings.push({ c: pts[n - 1].clone().addScaledVector(T[n - 1], R(n - 1) * Math.sin(a) * sq[0]), r: R(n - 1) * Math.cos(a), i: n - 1, t: 1 });
      }
    }
    const P = [], I = [], TT = [];
    for (const rg of rings) {
      const N = Nn[rg.i], B = Bn[rg.i];
      for (let k = 0; k < radial; k++) {
        const f = (k / radial) * Math.PI * 2;
        const cs = Math.cos(f) * rg.r * sq[0], sn = Math.sin(f) * rg.r * sq[1];
        P.push(rg.c.x + N.x * cs + B.x * sn, rg.c.y + N.y * cs + B.y * sn, rg.c.z + N.z * cs + B.z * sn);
        TT.push(rg.t);
      }
    }
    const nr = rings.length;
    const id = (k, j) => j * radial + (k % radial);
    const segs = closed ? nr : nr - 1;
    for (let j = 0; j < segs; j++) {
      const j1 = (j + 1) % nr;
      for (let k = 0; k < radial; k++) {
        const a = id(k, j), b = id(k + 1, j), c = id(k, j1), d = id(k + 1, j1);
        I.push(a, b, c, b, d, c);
      }
    }
    if (!closed) {
      const p0 = pts[0].clone().addScaledVector(T[0], caps[0] ? -R(0) * sq[0] : 0);
      const p1 = pts[n - 1].clone().addScaledVector(T[n - 1], caps[1] ? R(n - 1) * sq[0] : 0);
      const pb = P.length / 3;
      P.push(p0.x, p0.y, p0.z, p1.x, p1.y, p1.z);
      TT.push(0, 1);
      for (let k = 0; k < radial; k++) {
        I.push(pb, id(k + 1, 0), id(k, 0));
        I.push(id(k, nr - 1), id(k + 1, nr - 1), pb + 1);
      }
    }
    this.part(P, smoothNormals(P, I), I, color, bone, TT);
  }

  // Короткий цилиндр-«таблетка» (пуговицы, заклёпки): центр, нормаль, радиус, толщина.
  disc(c, n, r, h, color, bone, segs = 10) {
    const g = new THREE.CylinderGeometry(r, r * 1.04, h, segs, 1);
    _qt.setFromUnitVectors(_s.set(0, 1, 0), n.clone().normalize());
    _m.compose(c, _qt, _p.set(1, 1, 1));
    this.geo(g, _m.clone(), color, bone);
  }

  build(slot = 0) {
    const s = this.slots[slot];
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(s.pos, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(s.nrm, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(s.col, 3));
    g.setAttribute('skinIndex', new THREE.Uint16BufferAttribute(s.si, 4));
    g.setAttribute('skinWeight', new THREE.Float32BufferAttribute(s.sw, 4));
    const nv = s.pos.length / 3;
    g.setIndex(nv > 65535 ? new THREE.Uint32BufferAttribute(s.idx, 1) : new THREE.Uint16BufferAttribute(s.idx, 1));
    g.computeBoundingSphere();
    return g;
  }
}

// Нормализует до 4 пар (кость, вес) и кладёт в слот.
function pushWeights(s, w) {
  let n = 0;
  for (let i = 0; i < w.length && n < 4; i += 2) {
    if (w[i + 1] <= 1e-4) continue;
    _w[n * 2] = w[i]; _w[n * 2 + 1] = w[i + 1]; n++;
  }
  if (!n) { s.si.push(0, 0, 0, 0); s.sw.push(1, 0, 0, 0); return; }
  let sum = 0;
  for (let i = 0; i < n; i++) sum += _w[i * 2 + 1];
  for (let i = 0; i < 4; i++) {
    if (i < n) { s.si.push(_w[i * 2]); s.sw.push(_w[i * 2 + 1] / sum); } else { s.si.push(0); s.sw.push(0); }
  }
}

// Плавный переход между двумя костями по параметру x в диапазоне [x0, x1].
export const blend2 = (b0, b1, x0, x1) => (x) => {
  const t = sstep(x0, x1, x);
  return [b0, 1 - t, b1, t];
};

export { _e as tmpEuler };
