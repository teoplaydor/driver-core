// Процедурная геометрия предметов и подставок. Каждая модель — одна индексированная геометрия с гладкими
// нормалями, цветом в вершинах и атрибутом aMat (шероховатость, металличность, свечение) — так все предметы
// рисуются одним материалом и складываются в один BatchedMesh (1 draw call на все предметы мира).
// Модели строятся в двух детализациях: d = 1 (вблизи и в руках), d = 0 (дальше LOD_DIST).

import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { hash2 } from '../util/math.js';

const TAU = Math.PI * 2;
const C = (hex) => new THREE.Color(hex);
const sst = (e0, e1, x) => { const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); };
const _c = new THREE.Color();

// материалы: [шероховатость, металличность, свечение]
export const MT = {
  matte: [0.86, 0, 0], wood: [0.72, 0, 0], lacq: [0.38, 0, 0], plastic: [0.42, 0, 0], vinyl: [0.3, 0, 0],
  gloss: [0.2, 0, 0], ceramic: [0.16, 0, 0], metal: [0.3, 0.85, 0], chrome: [0.14, 1, 0], paint: [0.36, 0.45, 0],
  rubber: [0.93, 0, 0], fabric: [0.9, 0, 0], glass: [0.06, 0, 0], plush: [1, 0, 0],
};
const glow = (k) => [0.4, 0, k];

// Матрица из позиции, поворота (XYZ) и масштаба.
export function T(x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0, sx = 1, sy = sx, sz = sx) {
  return new THREE.Matrix4().compose(new THREE.Vector3(x, y, z), new THREE.Quaternion().setFromEuler(new THREE.Euler(rx, ry, rz)), new THREE.Vector3(sx, sy, sz));
}
// То же, но поворот сначала вокруг X, потом вокруг Y (наклон, затем разворот по кругу).
const TY = (x, y, z, rx, ry) => new THREE.Matrix4().makeRotationY(ry).multiply(new THREE.Matrix4().makeRotationX(rx)).setPosition(x, y, z);

// ---------- сборщик ----------

export class Builder {
  constructor() { this.parts = []; }

  // color: hex | Color | fn(x, y, z, out, nx, ny, nz) в локальных координатах детали (до xf);
  // mat: [r, m, e] | fn(x, y, z) -> [r, m, e]; xf: Matrix4.
  add(geo, color, mat = MT.matte, xf = null) {
    const g = geo;
    for (const k of Object.keys(g.attributes)) if (k !== 'position' && k !== 'normal') g.deleteAttribute(k);
    if (!g.index) {
      const n = g.attributes.position.count, idx = new Uint32Array(n);
      for (let i = 0; i < n; i++) idx[i] = i;
      g.setIndex(new THREE.BufferAttribute(idx, 1));
    }
    if (!g.attributes.normal) g.computeVertexNormals();
    const P = g.attributes.position, N = g.attributes.normal, n = P.count;
    const col = new Float32Array(n * 3), mt = new Float32Array(n * 3);
    const fixed = typeof color === 'function' ? null : new THREE.Color(color);
    for (let i = 0; i < n; i++) {
      const x = P.getX(i), y = P.getY(i), z = P.getZ(i);
      if (fixed) _c.copy(fixed); else color(x, y, z, _c, N.getX(i), N.getY(i), N.getZ(i));
      col[i * 3] = _c.r; col[i * 3 + 1] = _c.g; col[i * 3 + 2] = _c.b;
      const m = typeof mat === 'function' ? mat(x, y, z) : mat;
      mt[i * 3] = m[0]; mt[i * 3 + 1] = m[1]; mt[i * 3 + 2] = m[2];
    }
    g.setAttribute('color', new THREE.BufferAttribute(col, 3));
    g.setAttribute('aMat', new THREE.BufferAttribute(mt, 3));
    if (xf) g.applyMatrix4(xf);
    this.parts.push(g);
    return g;
  }

  build() {
    const g = mergeGeometries(this.parts, false);
    for (const p of this.parts) p.dispose();
    this.parts = [];
    g.computeBoundingBox();
    g.computeBoundingSphere();
    return g;
  }
}

// ---------- примитивы ----------

// Профиль вращения [[r, y], ...] с досэмплированием (чаще кольца — плавнее узоры в цвете вершин).
export function lathe(prof, seg, step = 0) {
  const pts = [];
  for (let i = 0; i < prof.length; i++) {
    const [r, y] = prof[i];
    pts.push(new THREE.Vector2(Math.max(0, r), y));
    if (step > 0 && i < prof.length - 1) {
      const [r2, y2] = prof[i + 1];
      const n = Math.floor(Math.hypot(r2 - r, y2 - y) / step);
      for (let k = 1; k < n; k++) pts.push(new THREE.Vector2(Math.max(0, r + ((r2 - r) * k) / n), y + ((y2 - y) * k) / n));
    }
  }
  return new THREE.LatheGeometry(pts, seg);
}

const cyl = (rt, rb, h, seg = 12, open = false) => new THREE.CylinderGeometry(rt, rb, h, seg, 1, open);
const sph = (r, ws = 16, hs = 12) => new THREE.SphereGeometry(r, ws, hs);
const ell = (rx, ry, rz, ws = 16, hs = 12) => new THREE.SphereGeometry(1, ws, hs).scale(rx, ry, rz);
const rbox = (w, h, d, r, seg = 2) => new RoundedBoxGeometry(w, h, d, seg, Math.min(r, w / 2 - 1e-4, h / 2 - 1e-4, d / 2 - 1e-4));
const box = (w, h, d) => new THREE.BoxGeometry(w, h, d);

// Трубка вдоль ломаной (параллельный перенос рамки), радиус — число или fn(t). caps — заглушки на концах.
export function sweep(points, radius, radial = 8, caps = [true, true]) {
  const n = points.length;
  const pos = [], idx = [];
  const T0 = new THREE.Vector3(), Nn = new THREE.Vector3(), B = new THREE.Vector3(), tmp = new THREE.Vector3();
  const tang = points.map((p, i) => {
    const a = points[Math.max(0, i - 1)], b = points[Math.min(n - 1, i + 1)];
    return new THREE.Vector3().subVectors(b, a).normalize();
  });
  T0.copy(tang[0]);
  Nn.set(0, 1, 0);
  if (Math.abs(Nn.dot(T0)) > 0.9) Nn.set(1, 0, 0);
  Nn.addScaledVector(T0, -Nn.dot(T0)).normalize();
  let len = 0;
  const total = points.reduce((s, p, i) => (i ? s + p.distanceTo(points[i - 1]) : 0), 0) || 1;
  for (let i = 0; i < n; i++) {
    if (i) {
      len += points[i].distanceTo(points[i - 1]);
      Nn.addScaledVector(tang[i], -Nn.dot(tang[i])).normalize();
    }
    B.crossVectors(tang[i], Nn);
    const r = typeof radius === 'function' ? radius(len / total) : radius;
    for (let k = 0; k < radial; k++) {
      const a = (k / radial) * TAU;
      tmp.copy(points[i]).addScaledVector(Nn, Math.cos(a) * r).addScaledVector(B, Math.sin(a) * r);
      pos.push(tmp.x, tmp.y, tmp.z);
    }
  }
  for (let i = 0; i < n - 1; i++) {
    for (let k = 0; k < radial; k++) {
      const a = i * radial + k, b = i * radial + ((k + 1) % radial), c = a + radial, d = b + radial;
      idx.push(a, c, b, b, c, d);
    }
  }
  const capAt = (i, flip) => {
    const ci = pos.length / 3;
    pos.push(points[i].x, points[i].y, points[i].z);
    for (let k = 0; k < radial; k++) {
      const a = i * radial + k, b = i * radial + ((k + 1) % radial);
      if (flip) idx.push(ci, b, a); else idx.push(ci, a, b);
    }
  };
  if (caps[0]) capAt(0, false);
  if (caps[1]) capAt(n - 1, true);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

// Плита по контуру (гитара): кольца контура со скруглением кромки + крышки. outline — [[x, y], ...] против часовой.
function slab(outline, half, round, rings = 4) {
  const n = outline.length;
  // нормали контура наружу
  const nrm = outline.map((p, i) => {
    const a = outline[(i - 1 + n) % n], b = outline[(i + 1) % n];
    const tx = b[0] - a[0], ty = b[1] - a[1], l = Math.hypot(tx, ty) || 1;
    return [ty / l, -tx / l];
  });
  // профиль кромки сверху вниз: скругление, прямая стенка, скругление
  const prof = [];
  for (let k = 0; k <= rings; k++) { const a = (k / rings) * Math.PI / 2; prof.push([round * (1 - Math.sin(a)), half - round * (1 - Math.cos(a))]); }
  for (let k = 0; k <= rings; k++) { const a = (k / rings) * Math.PI / 2; prof.push([round * (1 - Math.cos(a)), -half + round * (1 - Math.sin(a))]); }
  const pos = [], idx = [];
  for (const [inset, z] of prof) {
    for (let i = 0; i < n; i++) pos.push(outline[i][0] - nrm[i][0] * inset, outline[i][1] - nrm[i][1] * inset, z);
  }
  const R = prof.length;
  for (let r = 0; r < R - 1; r++) {
    for (let i = 0; i < n; i++) {
      const a = r * n + i, b = r * n + ((i + 1) % n), c = a + n, d = b + n;
      idx.push(a, c, b, b, c, d);
    }
  }
  // крышки: триангуляция контура
  const contour = outline.map((p, i) => new THREE.Vector2(p[0] - nrm[i][0] * round, p[1] - nrm[i][1] * round));
  const tris = THREE.ShapeUtils.triangulateShape(contour, []);
  for (const [a, b, c] of tris) idx.push(a, b, c);
  const last = (R - 1) * n;
  for (const [a, b, c] of tris) idx.push(last + a, last + c, last + b);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

// дерево: продольные волокна вдоль оси Y детали
function grain(light, dark, k = 1) {
  const a = C(light), b = C(dark);
  return (x, y, z, out) => {
    const ang = Math.atan2(x, z);
    const n = Math.sin(ang * 5 + Math.sin(y * 7 * k) * 1.4) * 0.55 + Math.sin(ang * 13 - y * 4 * k) * 0.3 + Math.sin(ang * 2 + y * 1.3) * 0.15;
    out.copy(a).lerp(b, sst(0.1, 0.95, n * 0.5 + 0.5) * 0.6);
  };
}
// доска: волокна вдоль оси (axis 0 — x, 2 — z), свой оттенок у каждой доски
function plank(base, seed, axis = 0, amp = 0.16) {
  const c0 = C(base).multiplyScalar(0.9 + hash2(seed, 7) * 0.2);
  const c1 = c0.clone().multiplyScalar(0.72);
  return (x, y, z, out) => {
    const u = axis === 0 ? x : z, v = axis === 0 ? z + y : x + y;
    const n = Math.sin(v * 90 + Math.sin(u * 7 + seed) * 2.2) * 0.5 + 0.5;
    out.copy(c0).lerp(c1, n * amp * 2);
  };
}

// ---------- бита ----------

export function batGeo(variant, d) {
  const B = new Builder();
  const alu = variant.startsWith('alu');
  const seg = d ? 18 : 8;
  const prof = alu
    ? [[0, -0.42], [0.02, -0.4195], [0.03, -0.415], [0.0335, -0.408], [0.033, -0.398], [0.026, -0.392], [0.0215, -0.389],
      [0.0215, -0.17], [0.0195, -0.165], [0.02, -0.08], [0.024, -0.02], [0.031, 0.04], [0.039, 0.1], [0.045, 0.16],
      [0.0485, 0.22], [0.0495, 0.3], [0.0495, 0.4], [0.0485, 0.408], [0.046, 0.414], [0.04, 0.418], [0, 0.42]]
    : [[0, -0.42], [0.018, -0.4195], [0.027, -0.416], [0.032, -0.41], [0.0325, -0.402], [0.029, -0.395], [0.022, -0.39],
      [0.0215, -0.386], [0.0215, -0.186], [0.0195, -0.181], [0.0198, -0.12], [0.022, -0.06], [0.0265, 0.0], [0.032, 0.06],
      [0.038, 0.12], [0.0432, 0.18], [0.0465, 0.24], [0.0482, 0.3], [0.0486, 0.36], [0.048, 0.395], [0.045, 0.41],
      [0.039, 0.4175], [0.031, 0.4198], [0.014, 0.418], [0, 0.414]];
  const geo = lathe(prof, seg, d ? 0.016 : 0.07);
  if (!alu) {
    const blond = variant === 'wood2';
    const wood = C(blond ? '#e8c48a' : '#c98b4e'), dark = C(blond ? '#c0905a' : '#9a5f30');
    const tape = C(blond ? '#2e3a5c' : '#2b2729'), tapeL = C(blond ? '#4a5a86' : '#4d454b');
    const logo = C('#4a2a14');
    const g = grain(wood, dark, 1.2);
    B.add(geo, (x, y, z, out) => {
      const a = Math.atan2(x, z);
      if (y < -0.186 && y > -0.388) { // обмотка рукояти по спирали
        const s = (((a / TAU + y / 0.03) % 1) + 1) % 1;
        out.copy(s < 0.16 ? tapeL : tape);
        return;
      }
      g(x, y, z, out);
      // выжженное клеймо на бочке
      const ly = (y - 0.2) / 0.06, la = a / 0.5, e = ly * ly + la * la;
      if (e < 1) {
        out.lerp(logo, 0.85);
        if (e < 0.55 && Math.abs(Math.sin(ly * 7)) < 0.45) out.lerp(wood, 0.45);
      }
      if (y > 0.405) out.multiplyScalar(0.86); // торец темнее
    }, (x, y) => (y < -0.186 && y > -0.388 ? MT.rubber : MT.lacq));
  } else {
    const paint = C(variant === 'alu2' ? '#2f6fd6' : '#d93a2e'), dark = C('#1d1d22'), silver = C('#cfd3d8'), white = C('#f4f4f0');
    B.add(geo, (x, y, z, out) => {
      if (y < -0.165 && y > -0.389) { // резиновая рукоять с насечкой
        const a = Math.atan2(x, z);
        out.copy(dark).multiplyScalar(Math.sin(a * 9 + y * 160) > 0.6 ? 1.6 : 1);
        return;
      }
      if (y <= -0.389 || y > 0.404) { out.copy(dark); return; }
      if (y < 0.02) { out.copy(silver); return; }
      out.copy(paint);
      if ((y > 0.13 && y < 0.145) || (y > 0.16 && y < 0.166)) out.copy(white);
    }, (x, y) => ((y < -0.165 && y > -0.389) || y <= -0.389 || y > 0.404 ? MT.rubber : y < 0.02 ? MT.chrome : MT.paint));
  }
  return B.build();
}

// ---------- зонт ----------
// Купол из 8 панелей между спицами. Форма считается от «раскрытости» o (0 — сложен, 1 — раскрыт); у всех
// состояний одинаковая топология, поэтому из них же собираются morph-цели для анимации в руках.

export const UMB_STYLES = {
  red: ['#e3443a', '#f7f1e6'], yellow: ['#ffc23a', '#ffe08a'], blue: ['#2e66c9', '#1e3f86'],
  green: ['#3fae5a', '#f2f0e4'], rainbow: ['#e8473a', '#ff9a2e', '#ffd23a', '#5cc45a', '#2fa3d8', '#3a5fd0', '#8a4fd0', '#e0509e'],
};
export const UMB = { top: 0.86, rib: 0.8, center: 0.42 }; // центр модели — середина сложенного зонта

function ribPoint(s, o, out, steps = 24) {
  // спица — дуга: угол касательной от оси -Y плавно меняется от «вдоль стержня» до купола
  let x = 0, y = 0;
  const n = Math.max(1, Math.round(steps * s));
  for (let i = 0; i < n; i++) {
    const t = ((i + 0.5) / n) * s;
    const a = 0.05 + (Math.PI / 2 - 0.05 - t * 0.82) * o;
    x += Math.sin(a) * (s / n);
    y -= Math.cos(a) * (s / n);
  }
  return out.set(x * UMB.rib, UMB.top + y * UMB.rib);
}

// ribs — спицы, распорки и бусины (у сложенного зонта в подставке они спрятаны в ткани — не нужны).
export function umbrellaGeo(style, o, d, ribs = o > 0) {
  const B = new Builder();
  const pal = UMB_STYLES[style].map(C);
  const rows = d ? 9 : 4, cols = d ? 5 : 2, R = 8;
  const e = o * o * (3 - 2 * o);
  const runY = 0.17 + 0.37 * e;
  const rp = new THREE.Vector2();
  const ribAt = (i, s, out) => {
    ribPoint(s, e, rp);
    const f = (i / R) * TAU;
    return out.set(Math.sin(f) * rp.x, rp.y, Math.cos(f) * rp.x);
  };
  const A = new THREE.Vector3(), Bv = new THREE.Vector3(), P = new THREE.Vector3();
  // купол: верх и изнанка
  for (const under of [false, true]) {
    for (let i = 0; i < R; i++) {
      const pos = [], idx = [];
      const fm = ((i + 0.5) / R) * TAU;
      const rx = Math.sin(fm), rz = Math.cos(fm);
      for (let r = 0; r <= rows; r++) {
        for (let c = 0; c <= cols; c++) {
          const u = c / cols, w = Math.sin(Math.PI * u);
          const s = (r / rows) * (1 - 0.075 * e * w); // зубчатый край: ткань между концами спиц подтянута
          ribAt(i, s, A); ribAt(i + 1, s, Bv);
          P.lerpVectors(A, Bv, u);
          // сложенный: ткань собирается складками и обмотана вокруг стержня, у ремешка перетянута;
          // раскрытый: слегка провисает между спицами
          const cinch = 1 - 0.55 * Math.exp(-(((s - 0.42) / 0.07) ** 2));
          const fold = 0.036 * sst(0, 0.45, s) * (1 - 0.25 * sst(0.8, 1, s)) * cinch * (0.6 + 0.4 * w);
          const bulge = fold * (1 - e) + 0.012 * e * w;
          P.x += rx * bulge; P.z += rz * bulge;
          P.y -= 0.01 * e * w * s;
          const tw = 0.55 * (1 - e) * s; // обмотка по спирали
          if (tw) { const c = Math.cos(tw), sn = Math.sin(tw), px = P.x; P.x = px * c + P.z * sn; P.z = -px * sn + P.z * c; }
          if (under) { P.y -= 0.004 * e + 0.0015; P.x -= P.x * 0.08 * (1 - e); P.z -= P.z * 0.08 * (1 - e); }
          pos.push(P.x, P.y, P.z);
        }
      }
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c < cols; c++) {
          const a = r * (cols + 1) + c, b = a + 1, cc = a + cols + 1, dd = cc + 1;
          if (under) idx.push(a, b, cc, b, dd, cc); else idx.push(a, cc, b, b, cc, dd);
        }
      }
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
      g.setIndex(idx);
      g.computeVertexNormals();
      const base = pal[i % pal.length];
      const vi = (k) => ({ r: Math.floor(k / (cols + 1)), c: k % (cols + 1) });
      let k = 0;
      B.add(g, (x, y, z, out) => {
        const { r, c } = vi(k++);
        out.copy(base);
        if (r === rows) out.multiplyScalar(0.8); // подшитый край
        if (c === 0 || c === cols) out.multiplyScalar(0.9); // швы по спицам
        if (under) out.multiplyScalar(0.84);
      }, MT.fabric);
    }
  }
  // спицы под тканью, бусины на концах, распорки к бегунку
  const ribC = C('#3a3a40'), tipC = C('#efe9dc');
  for (let i = 0; i < (d && ribs ? R : 0); i++) {
    const pts = [];
    const f = (i / R) * TAU;
    for (let r = 0; r <= rows; r++) {
      ribAt(i, r / rows, A);
      A.y -= 0.007;
      A.x -= Math.sin(f) * 0.004 * (1 - e); A.z -= Math.cos(f) * 0.004 * (1 - e);
      pts.push(A.clone());
    }
    B.add(sweep(pts, 0.0035, d ? 4 : 3, [false, false]), ribC, MT.metal);
    ribAt(i, 1, A);
    B.add(sph(0.0075, 5, 4), tipC, MT.plastic, T(A.x, A.y, A.z));
    ribAt(i, 0.42, A);
    A.y -= 0.008;
    const S = new THREE.Vector3(Math.sin(f) * 0.012, runY + 0.02, Math.cos(f) * 0.012);
    B.add(sweep([S, S.clone().lerp(A, 0.5), A.clone()], 0.0028, d ? 4 : 3, [false, false]), ribC, MT.metal);
  }
  // бегунок на стержне
  B.add(cyl(0.012, 0.014, 0.05, d ? 10 : 6), '#2c2c30', MT.plastic, T(0, runY, 0));
  // ремешок с кнопкой: на сложенном охватывает купол, на раскрытом — колечко под бегунком
  const sR = 0.42;
  ribPoint(sR, e, rp);
  const strapR = (rp.x + 0.036 * 0.45 * 0.65 + 0.007) * (1 - e) + 0.013 * e;
  const strapY = rp.y * (1 - e) + (runY - 0.05) * e;
  const strapC = pal[0].clone().multiplyScalar(0.7);
  B.add(cyl(strapR, strapR, 0.026, d ? 16 : 8, true), strapC, MT.fabric, T(0, strapY, 0));
  B.add(cyl(strapR * 0.97, strapR * 0.97, 0.026, d ? 16 : 8, true), strapC, MT.fabric, T(0, strapY, 0, Math.PI));
  B.add(sph(0.008, 8, 6), '#d8c070', MT.metal, T(strapR + 0.002, strapY, 0, 0, 0, 0, 1, 1, 0.6));
  // стержень, наконечник, колпачок купола
  B.add(cyl(0.0075, 0.0075, UMB.top + 0.03, d ? 10 : 6), '#55565c', MT.metal, T(0, (UMB.top + 0.03) / 2, 0));
  B.add(lathe([[0, UMB.top - 0.006], [0.026, UMB.top - 0.004], [0.03, UMB.top + 0.012], [0.022, UMB.top + 0.03], [0, UMB.top + 0.045]], d ? 14 : 8),
    pal[0].clone().multiplyScalar(0.85), MT.plastic);
  B.add(lathe([[0, UMB.top + 0.04], [0.009, UMB.top + 0.045], [0.007, UMB.top + 0.1], [0.004, UMB.top + 0.145], [0, UMB.top + 0.15]], d ? 10 : 6), '#e8e2d2', MT.metal);
  // ручка-крюк из лакированного дерева и металлическое кольцо
  const hook = [];
  for (let y = 0.005; y > -0.12; y -= 0.02) hook.push(new THREE.Vector3(0, y, 0));
  for (let k = 0; k <= (d ? 14 : 7); k++) {
    const a = Math.PI + (k / (d ? 14 : 7)) * Math.PI * 1.05;
    hook.push(new THREE.Vector3(0.05 + Math.cos(a) * 0.05, -0.12 + Math.sin(a) * 0.05, 0));
  }
  B.add(sweep(hook, (t) => 0.016 - 0.003 * t, d ? 9 : 5), grain('#7a4526', '#4e2a14', 3), MT.lacq);
  B.add(sph(0.0135, d ? 10 : 6, d ? 8 : 4), '#4e2a14', MT.lacq, T(hook[hook.length - 1].x, hook[hook.length - 1].y, 0));
  B.add(cyl(0.0185, 0.0175, 0.022, d ? 14 : 8), '#c9a45a', MT.metal, T(0, 0.006, 0));
  const g = B.build();
  g.translate(0, -UMB.center, 0);
  return g;
}

// ---------- фонарь ----------

export function lanternGeo(lit, d) {
  const B = new Builder();
  const s = d ? 16 : 10;
  const paint = C('#b9372d'), brass = C('#caa052'), wire = C('#2f2d2c');
  // бак, горелка, верхний колпак
  B.add(lathe([[0, -0.13], [0.07, -0.13], [0.078, -0.126], [0.081, -0.115], [0.081, -0.09], [0.074, -0.08], [0.05, -0.074], [0, -0.074]], s, d ? 0.01 : 0),
    (x, y, z, out) => out.copy(paint).multiplyScalar(y < -0.124 ? 0.7 : 1), MT.paint);
  B.add(cyl(0.044, 0.046, 0.02, s), brass, MT.metal, T(0, -0.066, 0));
  B.add(lathe([[0.036, 0.074], [0.06, 0.078], [0.067, 0.088], [0.064, 0.098], [0.046, 0.112], [0.022, 0.124], [0.012, 0.13], [0, 0.131]], s),
    (x, y, z, out) => { out.copy(paint); if (y < 0.081) out.multiplyScalar(0.75); }, MT.paint);
  // вентиляционные отверстия на колпаке
  for (let i = 0; i < (d ? 8 : 0); i++) {
    const a = (i / 8) * TAU;
    B.add(ell(0.007, 0.004, 0.003, 6, 4), '#2a1714', MT.matte, TY(Math.sin(a) * 0.056, 0.1, Math.cos(a) * 0.056, -0.6, a));
  }
  // стекло-колба
  const glassOff = C('#cfe2e8'), glassOn = C('#ffd27a'), core = C('#fff1c4');
  B.add(lathe([[0.04, -0.06], [0.052, -0.045], [0.06, -0.015], [0.062, 0.015], [0.057, 0.045], [0.046, 0.066], [0.038, 0.076]], s, d ? 0.01 : 0),
    (x, y, z, out) => {
      if (!lit) { out.copy(glassOff).multiplyScalar(0.85 + 0.15 * Math.sin(Math.atan2(x, z) * 3)); return; }
      out.copy(glassOn).lerp(core, sst(0.05, -0.02, Math.abs(y + 0.01)) * 0.6);
    }, lit ? (x, y) => [0.3, 0, 1.1 + 0.9 * sst(0.07, 0, Math.abs(y + 0.01))] : MT.glass);
  // фитиль и огонёк
  B.add(cyl(0.004, 0.004, 0.012, 6), '#2a2220', MT.matte, T(0, -0.05, 0));
  if (lit) B.add(lathe([[0, -0.046], [0.008, -0.04], [0.011, -0.028], [0.009, -0.014], [0.004, 0.0], [0, 0.008]], 8), (x, y, z, out) => out.copy(C('#ffb43a')).lerp(C('#fff6c8'), sst(-0.04, -0.01, y)), glow(4));
  // проволочная защита: 4 дуги и 2 кольца
  for (let i = 0; i < (d ? 4 : 0); i++) {
    const a = (i / 4) * TAU + TAU / 8, pts = [];
    for (let k = 0; k <= 8; k++) {
      const y = -0.07 + (k / 8) * 0.15;
      const r = 0.07 + 0.012 * Math.sin((k / 8) * Math.PI);
      pts.push(new THREE.Vector3(Math.sin(a) * r, y, Math.cos(a) * r));
    }
    B.add(sweep(pts, 0.0032, d ? 5 : 3, [false, false]), wire, MT.metal);
  }
  for (const [y, r] of [[-0.064, 0.072], [0.07, 0.07]]) B.add(new THREE.TorusGeometry(r, 0.0035, d ? 5 : 3, s), wire, MT.metal, T(0, y, 0, Math.PI / 2));
  // боковые ушки и дужка-ручка с деревянной рукояткой
  for (const sx of [-1, 1]) B.add(cyl(0.012, 0.012, 0.008, 10), brass, MT.metal, T(sx * 0.084, 0.06, 0, 0, 0, Math.PI / 2));
  const bail = [];
  for (let k = 0; k <= (d ? 16 : 8); k++) {
    const a = (k / (d ? 16 : 8)) * Math.PI;
    bail.push(new THREE.Vector3(Math.cos(a) * 0.087, 0.06 + Math.sin(a) * 0.15, 0));
  }
  B.add(sweep(bail, 0.0035, d ? 5 : 3), wire, MT.metal);
  B.add(cyl(0.011, 0.011, 0.05, d ? 12 : 6), grain('#9b6a3e', '#6d4426', 6), MT.lacq, T(0, 0.21, 0, 0, 0, Math.PI / 2));
  // ручка регулировки фитиля
  if (d) B.add(cyl(0.01, 0.01, 0.012, 10), brass, MT.metal, T(0, -0.066, 0.05, Math.PI / 2));
  return B.build();
}
export const LANTERN_CORE = new THREE.Vector3(0, -0.01, 0); // центр огонька в модели — для ореола

// ---------- гитара ----------
// Ось Y модели — вдоль грифа (от низа корпуса к голове), +Z — лицевая дека.

const GSC = 0.8;
function guitarOutline(n) {
  const y0 = -0.2927, y1 = 0.2157;
  const e = (y, c, rx, ry) => { const t = (y - c) / ry; return t * t < 1 ? rx * Math.sqrt(1 - t * t) : 0; };
  const w = (y) => {
    const a = e(y, -0.115, 0.188, 0.178), b = e(y, 0.088, 0.142, 0.128);
    const k = 0.045, h = Math.max(k - Math.abs(a - b), 0) / k;
    return Math.max(a, b) + h * h * k * 0.25 - 0.012 * Math.exp(-(((y + 0.005) / 0.05) ** 2)) * (a > 0 && b > 0 ? 1 : 0);
  };
  const right = [], left = [];
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    const y = y0 + (y1 - y0) * (0.5 - 0.5 * Math.cos(Math.PI * t));
    right.push([w(y) + 0.0005, y]);
  }
  for (let i = n - 1; i >= 1; i--) left.push([-right[i][0], right[i][1]]);
  return [...right, ...left].map(([x, y]) => [x * GSC, y * GSC]);
}

export function guitarGeo(d) {
  const B = new Builder();
  const top = C('#f0b867'), burst = C('#8a3f17'), side = C('#7a3a1c'), binding = C('#f3e6c8');
  const half = 0.048 * GSC;
  const outline = guitarOutline(d ? 40 : 16);
  const body = slab(outline, half, 0.012, d ? 4 : 2);
  B.add(body, (x, y, z, out) => {
    if (z > half - 0.004) {
      // санбёрст: светлый центр, тёмный край
      const r = Math.hypot(x / (0.17 * GSC), (y + 0.04 * GSC) / (0.24 * GSC));
      out.copy(top).lerp(burst, sst(0.62, 1.12, r));
    } else if (z > half - 0.011) out.copy(binding);
    else out.copy(side).multiplyScalar(z < -half + 0.004 ? 0.9 : 1);
  }, (x, y, z) => (z > half - 0.004 ? MT.lacq : MT.gloss));
  const zt = half + 0.0006;
  // розетка, резонаторное отверстие, накладка
  const hy = 0.08 * GSC;
  B.add(new THREE.RingGeometry(0.046 * GSC, 0.058 * GSC, d ? 36 : 16), (x, y, z, out) => {
    const r = Math.hypot(x, y) / GSC;
    out.copy(Math.floor((r - 0.046) / 0.003) % 2 ? C('#2a1a12') : C('#e9d6ac'));
  }, MT.lacq, T(0, hy, zt));
  B.add(new THREE.CircleGeometry(0.046 * GSC, d ? 32 : 14), '#1a0f0a', MT.matte, T(0, hy, zt - 0.0004));
  const guard = new THREE.Shape();
  guard.moveTo(0.05, -0.02); guard.quadraticCurveTo(0.12, 0.0, 0.1, 0.07); guard.quadraticCurveTo(0.085, 0.11, 0.055, 0.1); guard.quadraticCurveTo(0.07, 0.04, 0.05, -0.02);
  const gg = new THREE.ShapeGeometry(guard, d ? 8 : 3);
  gg.scale(GSC, GSC, 1);
  B.add(gg, (x, y, z, out) => out.copy(C('#5a2a16')).lerp(C('#2a120a'), (Math.sin(x * 300) * Math.sin(y * 260) + 1) * 0.35), MT.gloss, T(0, hy - 0.035 * GSC, zt + 0.0002));
  // подставка струн
  const by = -0.165 * GSC;
  B.add(d ? rbox(0.15 * GSC, 0.032 * GSC, 0.012, 0.005, 2) : box(0.15 * GSC, 0.032 * GSC, 0.012), '#3a1f12', MT.lacq, T(0, by, zt + 0.004));
  B.add(box(0.07 * GSC, 0.004, 0.006), '#f5efe0', MT.plastic, T(0, by + 0.006, zt + 0.011));
  for (let i = 0; i < (d ? 6 : 0); i++) B.add(sph(0.0028, 6, 4), '#f2ead6', MT.plastic, T((i - 2.5) * 0.0105 * GSC, by - 0.009, zt + 0.011));
  // гриф: шейка (полуцилиндр), накладка с ладами и точками, порожек
  const n0 = 0.19 * GSC, n1 = 0.64 * GSC, nl = n1 - n0;
  const neck = cyl(0.023 * GSC, 0.026 * GSC, nl, d ? 12 : 6, true);
  neck.scale(1, 1, 0.75);
  B.add(neck, side, MT.gloss, T(0, (n0 + n1) / 2, zt - 0.012));
  const fb0 = 0.15 * GSC;
  const fbl = n1 - fb0;
  B.add(box(0.05 * GSC, fbl, 0.007), '#2e1c13', MT.wood, T(0, fb0 + fbl / 2, zt + 0.0035));
  const nutY = n1;
  const scale = nutY - (by + 0.006); // мензура: от порожка до седла
  for (let f = 1; f <= (d ? 14 : 0); f++) {
    const y = nutY - scale * (1 - 2 ** (-f / 12));
    if (y < fb0 + 0.005) break;
    B.add(box(0.05 * GSC, 0.0022, 0.0024), '#d9dbe0', MT.chrome, T(0, y, zt + 0.0075));
    if ([3, 5, 7, 9, 12].includes(f) && d) {
      const yc = (y + nutY - scale * (1 - 2 ** (-(f - 1) / 12))) / 2;
      for (const sx of f === 12 ? [-0.012, 0.012] : [0]) B.add(new THREE.CircleGeometry(0.004, 10), '#f4efe2', MT.gloss, T(sx, yc, zt + 0.0072));
    }
  }
  B.add(box(0.05 * GSC, 0.005, 0.01), '#f5efe0', MT.plastic, T(0, nutY + 0.002, zt + 0.006));
  // голова грифа, отклонена назад; колки
  const hd = new THREE.Group();
  hd.position.set(0, nutY + 0.004, zt + 0.001);
  hd.rotation.x = -0.22;
  hd.updateMatrixWorld();
  const H = (x, y, z, rx = 0, ry = 0, rz = 0) => new THREE.Matrix4().multiplyMatrices(hd.matrixWorld, T(x, y, z, rx, ry, rz));
  const hl = 0.15 * GSC;
  B.add(d ? rbox(0.075 * GSC, hl, 0.016, 0.008, 2) : box(0.075 * GSC, hl, 0.016), (x, y, z, out) => out.copy(C('#2a1810')).lerp(C('#4a2c1a'), sst(-hl / 2, hl / 2, y) * 0.4), MT.gloss, H(0, hl / 2, -0.008));
  for (let i = 0; i < (d ? 6 : 0); i++) {
    const sx = i < 3 ? -1 : 1, k = i % 3;
    const y = hl * (0.28 + k * 0.26);
    B.add(cyl(0.0035, 0.0035, 0.014, 6), '#d9dbe0', MT.chrome, H(sx * 0.018, y, 0.006, Math.PI / 2));
    B.add(box(0.012, 0.016, 0.012), '#c9ccd2', MT.chrome, H(sx * 0.036 * GSC + sx * 0.008, y, -0.008));
    B.add(cyl(0.002, 0.002, 0.012, 5), '#c9ccd2', MT.chrome, H(sx * 0.052, y, -0.008, 0, 0, Math.PI / 2));
    B.add(ell(0.004, 0.009, 0.007, 8, 6), '#f3ecdc', MT.gloss, H(sx * 0.062, y, -0.008));
  }
  // струны: от подставки до порожка и дальше к колкам
  if (d) {
    for (let i = 0; i < 6; i++) {
      const xb = (i - 2.5) * 0.0105 * GSC, xn = (i - 2.5) * 0.0074 * GSC;
      const a = new THREE.Vector3(xb, by + 0.006, zt + 0.0135), b = new THREE.Vector3(xn, nutY + 0.002, zt + 0.0115);
      const r = 0.0007 + (5 - i) * 0.00014;
      B.add(sweep([a, b], r, 3, [false, false]), i > 2 ? '#d8d0bc' : '#c79d62', MT.metal);
      const sx = i < 3 ? -1 : 1, k = i < 3 ? 2 - i : i - 3;
      const pe = new THREE.Vector3(sx * 0.018, hl * (0.28 + k * 0.26), 0.012).applyMatrix4(hd.matrixWorld);
      B.add(sweep([b, pe], r, 3, [false, false]), i > 2 ? '#d8d0bc' : '#c79d62', MT.metal);
    }
  }
  return B.build();
}

// ---------- бумбокс ----------

export function boomboxGeo(on, d) {
  const B = new Builder();
  const W = 0.5, H = 0.27, D = 0.13;
  const body = C('#c9c6bf'), dark = C('#26262a'), chrome = C('#e2e4e8');
  B.add(rbox(W, H, D, 0.024, d ? 2 : 1), body, MT.paint);
  const zf = D / 2;
  B.add(rbox(W - 0.03, H - 0.07, 0.01, 0.008, 1), '#34343a', MT.plastic, T(0, -0.012, zf - 0.003)); // лицевая панель
  // динамики: хромовое кольцо, подвес, диффузор с решёткой, пылезащитный колпак
  const spk = [[0.0, 0.0], [0.084, 0.0], [0.088, 0.004], [0.084, 0.008], [0.077, 0.006], [0.07, 0.002], [0.05, -0.012], [0.03, -0.022], [0.027, -0.021],
    [0.022, -0.016], [0.01, -0.012], [0, -0.011]];
  for (const sx of [-1, 1]) {
    const g = lathe(spk.map(([r, y]) => [r, y]), d ? 22 : 10);
    B.add(g, (x, y, z, out) => {
      const r = Math.hypot(x, z);
      if (r > 0.076) { out.copy(chrome); return; }
      if (r > 0.068) { out.copy(C('#1c1c20')); return; }
      if (r < 0.028) { out.copy(C('#4a4a52')); return; }
      out.copy(C('#2a2a30')).multiplyScalar(Math.floor(r / 0.006) % 2 ? 1.25 : 1);
    }, (x, y, z) => (Math.hypot(x, z) > 0.076 ? MT.chrome : MT.rubber), T(sx * 0.15, -0.005, zf, Math.PI / 2));
    // пищалка над динамиком
    if (d) B.add(lathe([[0, 0], [0.022, 0], [0.024, 0.003], [0.02, 0.004], [0.008, 0.001], [0, 0.002]], d ? 16 : 8),
      (x, y, z, out) => out.copy(Math.hypot(x, z) > 0.019 ? chrome : C('#202024')), MT.chrome, T(sx * 0.21, 0.095, zf, Math.PI / 2));
  }
  // кассетная дека: окно с катушками и наклейкой
  B.add(rbox(0.13, 0.08, 0.012, 0.006, 1), '#18181c', MT.gloss, T(0, -0.012, zf));
  B.add(box(0.09, 0.022, 0.002), '#efe4c6', MT.plastic, T(0, 0.004, zf + 0.0065));
  for (const sx of [-1, 1]) {
    if (!d) break;
    B.add(new THREE.TorusGeometry(0.011, 0.003, 6, 14), '#f2f2ee', MT.plastic, T(sx * 0.028, -0.022, zf + 0.0065));
    B.add(cyl(0.005, 0.005, 0.003, 6), '#f2f2ee', MT.plastic, T(sx * 0.028, -0.022, zf + 0.0065, Math.PI / 2));
  }
  // шкала тюнера (светится, когда играет) со стрелкой
  const dial = C(on ? '#ffb347' : '#8a6a3a');
  B.add(box(0.2, 0.026, 0.004), dial, on ? glow(1.4) : MT.gloss, T(0, 0.098, zf + 0.001));
  for (let i = 0; i < (d ? 13 : 0); i++) B.add(box(0.0015, i % 3 ? 0.006 : 0.011, 0.002), '#4a2a10', on ? glow(0.4) : MT.matte, T(-0.09 + i * 0.015, 0.101, zf + 0.0035));
  B.add(box(0.002, 0.022, 0.002), '#e0302a', on ? glow(1) : MT.plastic, T(0.032, 0.098, zf + 0.004));
  // светодиоды и ручки громкости
  B.add(sph(0.005, d ? 8 : 4, d ? 6 : 3), on ? '#7dff6a' : '#2f5a2a', on ? glow(3) : MT.gloss, T(-0.09, 0.07, zf + 0.002));
  B.add(sph(0.005, d ? 8 : 4, d ? 6 : 3), on ? '#ff5a4a' : '#5a2a2a', on ? glow(2.2) : MT.gloss, T(-0.075, 0.07, zf + 0.002));
  for (const x of d ? [0.085, 0.11] : []) {
    B.add(cyl(0.011, 0.012, 0.012, d ? 14 : 8), chrome, MT.chrome, T(x, 0.07, zf + 0.004, Math.PI / 2));
    B.add(box(0.002, 0.009, 0.002), '#202024', MT.matte, T(x, 0.074, zf + 0.011));
  }
  // клавиши сверху
  const keys = ['#d33a2c', '#2a2a2e', '#2a2a2e', '#2a2a2e', '#2a2a2e', '#c9ccd2'];
  keys.forEach((c, i) => B.add(d ? rbox(0.026, 0.014, 0.03, 0.003, 1) : box(0.026, 0.014, 0.03), c, MT.plastic, T(-0.07 + i * 0.028, H / 2 + 0.004, -0.02)));
  // ручка для переноски
  const hp = [];
  for (let k = 0; k <= (d ? 14 : 8); k++) {
    const t = k / (d ? 14 : 8);
    const x = -0.17 + 0.34 * t;
    const y = H / 2 + 0.005 + 0.07 * Math.min(1, Math.sin(Math.PI * t) * 2.2);
    hp.push(new THREE.Vector3(x, y, 0));
  }
  B.add(sweep(hp, 0.011, d ? 8 : 4), chrome, MT.chrome);
  // антенна
  const a0 = new THREE.Vector3(0.21, H / 2 + 0.005, -0.03), a1 = a0.clone().add(new THREE.Vector3(-0.12, 0.3, 0));
  B.add(sweep([a0, a0.clone().lerp(a1, 0.5), a1], (t) => 0.005 - 0.0025 * t, d ? 6 : 3), chrome, MT.chrome);
  B.add(sph(0.007, 8, 6), chrome, MT.chrome, T(a1.x, a1.y, a1.z));
  B.add(cyl(0.012, 0.012, 0.012, 10), '#2a2a2e', MT.plastic, T(a0.x, a0.y, a0.z));
  // ножки
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) B.add(cyl(0.014, 0.016, 0.01, d ? 8 : 4), '#1c1c1e', MT.rubber, T(sx * 0.2, -H / 2 - 0.004, sz * 0.04));
  return B.build();
}

// ---------- фрисби ----------

export const FRISBEE_COLORS = { orange: '#ff7a2a', lime: '#7fd34a', blue: '#3a8ff0' };
export function frisbeeGeo(variant, d) {
  const B = new Builder();
  const c = C(FRISBEE_COLORS[variant] || '#ff7a2a'), light = c.clone().lerp(C('#ffffff'), 0.35), deep = c.clone().multiplyScalar(0.72);
  const prof = [[0, 0.0095], [0.06, 0.0085], [0.105, 0.004], [0.12, -0.008], [0.121, -0.019], [0.126, -0.024], [0.133, -0.023], [0.137, -0.017],
    [0.137, -0.009], [0.131, -0.002], [0.118, 0.004], [0.09, 0.0095], [0.05, 0.0132], [0, 0.014]];
  B.add(lathe(prof, d ? 36 : 14, d ? 0.012 : 0), (x, y, z, out, nx, ny) => {
    const r = Math.hypot(x, z), a = Math.atan2(x, z);
    out.copy(c);
    if (ny < 0 && r < 0.12) { out.copy(deep); return; } // изнанка
    if (Math.abs(r - 0.075) < 0.004 || Math.abs(r - 0.098) < 0.004) out.copy(light); // кольца
    const star = 0.03 * (0.55 + 0.45 * Math.cos(5 * a));
    if (r < star) out.copy(C('#ffffff'));
  }, MT.plastic);
  return B.build();
}

// ---------- посуда ----------

const TEA = { body: '#7cc8ae', dot: '#fdfaf2', gold: '#d9b45a', inner: '#fbf6ec' };
export function teapotGeo(d) {
  const B = new Builder();
  const s = d ? 22 : 12;
  const band = C(TEA.dot), gold = C(TEA.gold), mint = C(TEA.body);
  const bodyC = (x, y, z, out) => {
    out.copy(mint);
    if (y > -0.028 && y < 0.016) {
      out.copy(band);
      // ромбики по пояску
      const a = (Math.atan2(x, z) / TAU) * 14, f = Math.abs(a - Math.round(a)) * 2, v = Math.abs(y + 0.006) / 0.016;
      if (f + v < 0.75) out.copy(mint).multiplyScalar(0.85);
    }
    if (Math.abs(y + 0.03) < 0.003 || Math.abs(y - 0.018) < 0.003) out.copy(gold);
  };
  B.add(lathe([[0, -0.075], [0.058, -0.075], [0.066, -0.071], [0.068, -0.064], [0.088, -0.05], [0.104, -0.025], [0.109, 0.0], [0.105, 0.024],
    [0.092, 0.044], [0.074, 0.056], [0.064, 0.06], [0.064, 0.066], [0.06, 0.066], [0.058, 0.058], [0, 0.058]], s, d ? 0.012 : 0), (x, y, z, out) => {
    if (y < -0.062 || y > 0.058) { out.copy(C(TEA.gold)); return; }
    bodyC(x, y, z, out);
  }, (x, y) => (y < -0.062 || y > 0.058 ? MT.metal : MT.ceramic));
  // крышка с шишечкой
  B.add(lathe([[0.061, 0.06], [0.064, 0.064], [0.06, 0.072], [0.046, 0.082], [0.026, 0.09], [0.014, 0.092], [0.014, 0.096], [0.019, 0.104], [0.017, 0.114], [0.008, 0.12], [0, 0.121]],
    s, d ? 0.008 : 0), (x, y, z, out) => out.copy(y > 0.091 || y < 0.066 ? C(TEA.gold) : C(TEA.body)), (x, y) => (y > 0.091 || y < 0.066 ? MT.metal : MT.ceramic));
  // носик — сужающаяся изогнутая трубка
  const sp = [];
  for (let k = 0; k <= 10; k++) {
    const t = k / 10;
    sp.push(new THREE.Vector3(0.085 + 0.1 * t, -0.03 + 0.11 * t * t + 0.02 * t, 0));
  }
  B.add(sweep(sp, (t) => 0.024 - 0.013 * t, d ? 12 : 6, [false, false]), TEA.body, MT.ceramic);
  B.add(new THREE.TorusGeometry(0.0115, 0.0025, 5, d ? 12 : 6), TEA.gold, MT.metal, T(sp[10].x, sp[10].y, 0, Math.PI / 2 - 0.9, 0, 0));
  // ручка
  const hp = [];
  for (let k = 0; k <= 12; k++) {
    const a = -0.35 + (k / 12) * (Math.PI + 0.7);
    hp.push(new THREE.Vector3(-0.098 - Math.sin(a) * 0.05, 0.005 + Math.cos(a) * 0.045, 0));
  }
  B.add(sweep(hp, 0.0105, d ? 10 : 5), TEA.body, MT.ceramic);
  return B.build();
}

export function cupGeo(d) {
  const B = new Builder();
  const s = d ? 24 : 10;
  const outer = (x, y, z, out) => out.copy(C(y > -0.004 && y < 0.018 ? TEA.dot : TEA.body));
  B.add(lathe([[0, -0.034], [0.024, -0.034], [0.027, -0.031], [0.026, -0.027], [0.036, -0.016], [0.046, 0.002], [0.052, 0.02], [0.056, 0.032],
    [0.055, 0.035], [0.052, 0.034], [0.048, 0.02], [0.042, 0.004], [0.03, -0.014], [0, -0.02]], s, d ? 0.007 : 0), (x, y, z, out, nx, ny, nz) => {
    const r = Math.hypot(x, z);
    const inside = (nx * x + nz * z) < 0 && y > -0.022;
    if (inside) { out.copy(C(TEA.inner)); return; }
    if (y > 0.031 || y < -0.029) { out.copy(C(TEA.gold)); return; }
    outer(x, y, z, out);
    void r;
  }, MT.ceramic);
  B.add(new THREE.CircleGeometry(0.047, s), '#a35a26', MT.gloss, T(0, 0.016, 0, -Math.PI / 2)); // чай
  B.add(new THREE.TorusGeometry(0.017, 0.0055, 6, d ? 12 : 6, Math.PI * 1.3), TEA.body, MT.ceramic, T(0.052, 0.006, 0, 0, 0, -Math.PI * 0.65));
  return B.build();
}

export const MUG_COLORS = { red: '#d9443a', blue: '#3a78d0', green: '#45a05a' };
export function mugGeo(variant, d) {
  const B = new Builder();
  const s = d ? 18 : 10;
  const enamel = C(MUG_COLORS[variant] || '#d9443a'), rim = C('#1f2a44'), white = C('#f6f3ea');
  B.add(lathe([[0, -0.05], [0.044, -0.05], [0.048, -0.047], [0.05, -0.04], [0.05, 0.046], [0.053, 0.05], [0.051, 0.054], [0.047, 0.052],
    [0.046, 0.046], [0.046, -0.04], [0, -0.042]], s, d ? 0.012 : 0), (x, y, z, out, nx, ny, nz) => {
    const inside = (nx * x + nz * z) < 0 && y > -0.042;
    if (y > 0.045 || y < -0.046) { out.copy(rim); return; }
    if (inside) { out.copy(white); return; }
    out.copy(enamel);
    if (hash2(Math.round(Math.atan2(x, z) * 30), Math.round(y * 300), 3) > 0.93) out.lerp(white, 0.7); // крапинки эмали
  }, MT.gloss);
  B.add(new THREE.CircleGeometry(0.046, s), '#3b2114', MT.gloss, T(0, 0.028, 0, -Math.PI / 2)); // какао
  // D-образная ручка со скруглёнными углами
  const hp = [];
  for (let k = 0; k <= (d ? 16 : 8); k++) {
    const a = -Math.PI / 2 + (k / (d ? 16 : 8)) * Math.PI;
    const c = Math.cos(a), sn = Math.sin(a);
    hp.push(new THREE.Vector3(0.047 + 0.034 * Math.sign(c) * Math.abs(c) ** 0.5, 0.032 * Math.sign(sn) * Math.abs(sn) ** 0.6, 0));
  }
  B.add(sweep(hp, 0.0075, d ? 8 : 4), enamel, MT.gloss);
  return B.build();
}

// ---------- книга ----------
// Лежит плашмя: X — ширина страницы (корешок на -X), Z — высота страницы, Y — толщина.

export const BOOK_COLORS = { red: '#a8352c', green: '#2f6a4a', blue: '#2c437e' };
export function bookGeo(variant, d) {
  const B = new Builder();
  const cov = C(BOOK_COLORS[variant] || '#a8352c'), gold = C('#e2bf5c'), paper = C('#f2e8cf');
  const W = 0.15, Hh = 0.21, th = 0.036, ct = 0.0045;
  for (const sy of [-1, 1]) B.add(rbox(W + 0.006, ct, Hh + 0.008, 0.0018, 1), cov, MT.wood, T(0.003, sy * (th / 2 - ct / 2), 0));
  // тиснение на обложке: рамка и ромб
  const yt = th / 2 + 0.0003;
  for (const sz of [-1, 1]) B.add(box(0.11, 0.0008, 0.005), gold, MT.metal, T(0.006, yt, sz * 0.085));
  for (const sx of [-1, 1]) B.add(box(0.005, 0.0008, 0.165), gold, MT.metal, T(0.006 + sx * 0.0525, yt, 0));
  B.add(box(0.03, 0.0008, 0.03), gold, MT.metal, T(0.006, yt, 0, 0, Math.PI / 4, 0, 1, 1, 1.5));
  // блок страниц с полосками-листами
  B.add(box(W - 0.006, th - ct * 2, Hh - 0.004, 1, d ? 8 : 1, 1), (x, y, z, out) => {
    out.copy(paper).multiplyScalar(0.93 + 0.07 * Math.sin(y * 2200));
  }, MT.matte, T(0.002, 0, 0));
  // скруглённый корешок с золотыми полосками
  const sp = new THREE.CylinderGeometry(th / 2, th / 2, Hh + 0.008, d ? 12 : 6, d ? 8 : 1, true, Math.PI, Math.PI);
  sp.scale(0.55, 1, 1);
  B.add(sp, (x, y, z, out) => {
    out.copy(cov);
    if (Math.abs(Math.abs(y) - 0.08) < 0.006) out.copy(gold);
    else if (Math.abs(y) < 0.035) out.lerp(gold, 0.35);
  }, MT.wood, T(-W / 2, 0, 0, Math.PI / 2));
  // закладка-ленточка
  if (d) B.add(box(0.008, 0.0012, 0.04), '#d63a3a', MT.fabric, T(-0.03, -0.002, Hh / 2 + 0.018));
  return B.build();
}

// ---------- плюшевый мишка (сидит) ----------

export function teddyGeo(d) {
  const B = new Builder();
  const ws = d ? 14 : 7, hs = d ? 11 : 5;
  const fur = C('#b8783f'), light = C('#ecc795'), dark = C('#2a1a14');
  const fuzzy = (base, seed) => (x, y, z, out) => out.copy(base).multiplyScalar(0.94 + hash2(Math.round(x * 400), Math.round(y * 400) + Math.round(z * 400), seed) * 0.1);
  // тело с пузиком
  B.add(ell(0.105, 0.12, 0.095, ws, hs), (x, y, z, out) => {
    fuzzy(fur, 1)(x, y, z, out);
    const b = (x / 0.07) ** 2 + ((y + 0.01) / 0.085) ** 2;
    if (z > 0.04 && b < 1) out.copy(light);
  }, MT.plush, T(0, -0.02, 0));
  // голова, мордочка, нос, глаза
  B.add(sph(0.095, ws, hs), fuzzy(fur, 2), MT.plush, T(0, 0.155, 0.005));
  B.add(ell(0.05, 0.038, 0.042, ws, hs), fuzzy(light, 3), MT.plush, T(0, 0.132, 0.078));
  B.add(ell(0.019, 0.013, 0.012, d ? 10 : 5, d ? 8 : 4), dark, MT.gloss, T(0, 0.148, 0.118));
  B.add(box(0.003, 0.016, 0.003), dark, MT.matte, T(0, 0.128, 0.119));
  for (const sx of [-1, 1]) {
    B.add(sph(0.012, d ? 10 : 5, d ? 8 : 4), dark, MT.gloss, T(sx * 0.036, 0.18, 0.082));
    if (d) B.add(sph(0.0035, 6, 4), '#ffffff', MT.gloss, T(sx * 0.036 + 0.004, 0.185, 0.093));
    // уши
    B.add(ell(0.036, 0.034, 0.016, ws, hs), fuzzy(fur, 4), MT.plush, T(sx * 0.068, 0.232, -0.01, 0, 0, -sx * 0.4));
    if (d) B.add(ell(0.022, 0.02, 0.008, 12, 8), light, MT.plush, T(sx * 0.07, 0.229, 0.001, 0, 0, -sx * 0.4));
    // лапы: руки вперёд-вбок, ноги вытянуты вперёд с подушечками
    B.add(ell(0.032, 0.07, 0.034, ws, hs), fuzzy(fur, 5), MT.plush, T(sx * 0.108, 0.0, 0.035, 0.55, 0, sx * 0.5));
    B.add(ell(0.042, 0.042, 0.078, ws, hs), fuzzy(fur, 6), MT.plush, T(sx * 0.062, -0.1, 0.07, -0.1, sx * 0.15, 0));
    B.add(ell(0.028, 0.03, 0.006, d ? 12 : 6, d ? 8 : 4), light, MT.plush, T(sx * 0.068, -0.098, 0.146, -0.1, sx * 0.15, 0));
  }
  // бантик на шее
  for (const sx of [-1, 1]) B.add(new THREE.ConeGeometry(0.026, 0.05, d ? 10 : 6), '#d8343a', MT.fabric, T(sx * 0.026, 0.085, 0.085, 0, 0, sx * Math.PI / 2));
  B.add(sph(0.013, d ? 10 : 5, d ? 8 : 4), '#b82a30', MT.fabric, T(0, 0.085, 0.09));
  return B.build();
}

// ---------- пляжный мяч ----------

const BALL_PAL = {
  a: ['#ef4a3c', '#fff7ea', '#ffc531', '#fff7ea', '#2f86e6', '#fff7ea'],
  b: ['#38b85a', '#ffe066', '#9a54d6', '#ffe066', '#ff7a36', '#ffe066'],
};
// Шесть клиньев — отдельные лоскуты сферы (чёткие швы), белые шапочки на полюсах и клапан.
export function ballGeo(variant, d) {
  const B = new Builder();
  const pal = BALL_PAL[variant] || BALL_PAL.a;
  const r = 0.27, cap = 0.34;
  for (let k = 0; k < 6; k++) {
    B.add(new THREE.SphereGeometry(r, d ? 8 : 3, d ? 18 : 7, (k / 6) * TAU, TAU / 6, cap, Math.PI - cap * 2), pal[k], MT.vinyl);
  }
  for (const top of [true, false]) {
    B.add(new THREE.SphereGeometry(r * 1.003, d ? 24 : 9, d ? 4 : 2, 0, TAU, top ? 0 : Math.PI - cap, cap), '#fbf8f2', MT.vinyl);
  }
  B.add(cyl(0.011, 0.014, 0.014, 8), '#f4f4f4', MT.vinyl, T(0, r + 0.004, 0));
  return B.build();
}

// ---------- ящик ----------

export function crateGeo(d, tint = '#c99a5b', seed = 0) {
  const B = new Builder();
  const S = 0.6, h = S / 2;
  const frame = C(tint).multiplyScalar(0.66), gap = C('#3a2716');
  B.add(box(S - 0.03, S - 0.03, S - 0.03), gap, MT.matte); // тёмная изнанка в щелях
  const pb = (w, hh, dd, r) => (d ? rbox(w, hh, dd, r, 1) : box(w, hh, dd));
  const pl = (w, hh, dd) => box(w, hh, dd);
  // по 3 доски на гранях
  for (let f = 0; f < 4; f++) {
    const ry = (f * Math.PI) / 2;
    for (let i = 0; i < 3; i++) {
      const y = -h + 0.06 + 0.085 + i * 0.17;
      B.add(pl(S - 0.1, 0.16, 0.022), plank(tint, f * 3 + i + seed * 17), MT.wood, T(Math.sin(ry) * (h - 0.011), y, Math.cos(ry) * (h - 0.011), 0, ry, 0));
    }
  }
  for (const sy of [-1, 1]) for (let i = 0; i < 3; i++) B.add(pl(S - 0.1, 0.022, 0.16), plank(tint, 20 + i + sy * 5 + seed * 17, 0), MT.wood, T(0, sy * (h - 0.011), -0.17 + i * 0.17));
  // рамка: стойки и обвязка
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) B.add(pb(0.06, S, 0.06, 0.008), plank('#' + frame.getHexString(), 40 + sx + sz * 3, 2, 0.1), MT.wood, T(sx * (h - 0.03), 0, sz * (h - 0.03)));
  for (const sy of [-1, 1]) {
    for (const s of [-1, 1]) {
      B.add(pb(S - 0.12, 0.06, 0.06, 0.008), frame, MT.wood, T(0, sy * (h - 0.03), s * (h - 0.03)));
      B.add(pb(0.06, 0.06, S - 0.12, 0.008), frame, MT.wood, T(s * (h - 0.03), sy * (h - 0.03), 0));
    }
  }
  // гвозди на концах досок
  if (d) {
    for (let f = 0; f < 4; f++) {
      const ry = (f * Math.PI) / 2;
      for (let i = 0; i < 3; i++) {
        for (const sx of [-1, 1]) {
          const y = -h + 0.145 + i * 0.17, lx = sx * (h - 0.03);
          const x = Math.sin(ry) * (h + 0.0005) + Math.cos(ry) * lx, z = Math.cos(ry) * (h + 0.0005) - Math.sin(ry) * lx;
          B.add(cyl(0.006, 0.006, 0.004, 6), '#5d5a57', MT.metal, TY(x, y, z, Math.PI / 2, ry));
        }
      }
    }
  }
  return B.build();
}

// ---------- шахматы ----------

const CHESS_LATHE = {
  P: [[0, 0], [0.036, 0], [0.036, 0.008], [0.022, 0.02], [0.018, 0.042], [0.03, 0.05], [0.018, 0.056], [0.026, 0.07], [0.024, 0.082], [0.012, 0.092], [0, 0.094]],
  R: [[0, 0], [0.042, 0], [0.042, 0.012], [0.028, 0.026], [0.028, 0.07], [0.042, 0.076], [0.042, 0.1], [0.03, 0.1], [0.03, 0.094], [0, 0.094]],
  B: [[0, 0], [0.04, 0], [0.04, 0.01], [0.024, 0.024], [0.02, 0.06], [0.034, 0.075], [0.028, 0.105], [0.014, 0.125], [0.016, 0.135], [0, 0.14]],
  Q: [[0, 0], [0.044, 0], [0.044, 0.012], [0.026, 0.028], [0.022, 0.07], [0.04, 0.095], [0.046, 0.12], [0.03, 0.128], [0.018, 0.14], [0.026, 0.15], [0, 0.158]],
  K: [[0, 0], [0.046, 0], [0.046, 0.012], [0.028, 0.03], [0.024, 0.076], [0.04, 0.1], [0.034, 0.122], [0.02, 0.13], [0.02, 0.14], [0, 0.145]],
  N: [[0, 0], [0.04, 0], [0.04, 0.01], [0.026, 0.025], [0.022, 0.05], [0, 0.056]],
};
export function chessGeo(piece, side, d = 1) {
  const B = new Builder();
  const hex = side === 'w' ? '#f1e6cf' : '#3a2f2c';
  B.add(lathe(CHESS_LATHE[piece] || CHESS_LATHE.P, d ? 16 : 6), hex, MT.lacq);
  if (piece === 'K') {
    B.add(box(0.012, 0.04, 0.012), hex, MT.lacq, T(0, 0.165, 0));
    B.add(box(0.034, 0.012, 0.012), hex, MT.lacq, T(0, 0.172, 0));
  }
  if (piece === 'N') {
    B.add(rbox(0.032, 0.07, 0.05, 0.008, 1), hex, MT.lacq, T(0, 0.085, -0.01, -0.25, 0, 0));
    B.add(rbox(0.03, 0.03, 0.04, 0.008, 1), hex, MT.lacq, T(0, 0.11, -0.045, 0.25, 0, 0));
  }
  const g = B.build();
  g.translate(0, -0.075, 0);
  return g;
}

// ---------- подставки (статичный декор) ----------

// Стойка для бит: поддон с ячейками, две стойки и перекладина с прорезями. Начало координат — на земле.
export function batRackGeo(d) {
  const B = new Builder();
  const wood = '#b07a46', dark = '#7a4f2c';
  const pb = (w, h, dd) => (d ? rbox(w, h, dd, 0.008, 1) : box(w, h, dd));
  B.add(pb(1.0, 0.06, 0.34), plank(wood, 1), MT.wood, T(0, 0.03, 0));
  B.add(pb(0.98, 0.08, 0.03), plank(dark, 2), MT.wood, T(0, 0.1, 0.155));
  B.add(pb(0.98, 0.08, 0.03), plank(dark, 3), MT.wood, T(0, 0.1, -0.155));
  for (const sx of [-1, 1]) {
    B.add(pb(0.06, 0.78, 0.06), plank(dark, 4 + sx, 2), MT.wood, T(sx * 0.47, 0.42, -0.1));
    B.add(pb(0.03, 0.08, 0.3), plank(dark, 6 + sx), MT.wood, T(sx * 0.475, 0.1, 0));
  }
  // перекладина с прорезями
  const slots = [-0.33, -0.11, 0.11, 0.33];
  const xs = [-0.5, ...slots.flatMap((s) => [s - 0.03, s + 0.03]), 0.5];
  for (let i = 0; i < xs.length; i += 2) {
    const w = xs[i + 1] - xs[i];
    B.add(pb(w, 0.06, 0.09), plank(wood, 10 + i), MT.wood, T((xs[i] + xs[i + 1]) / 2, 0.76, -0.07));
  }
  B.add(pb(1.0, 0.03, 0.04), plank(dark, 30), MT.wood, T(0, 0.725, -0.1));
  // ячейки в поддоне — тёмные углубления
  for (const s of slots) B.add(cyl(0.05, 0.05, 0.004, 16), '#3a2716', MT.matte, T(s, 0.061, 0.02));
  // бейсбольный мяч в поддоне: белая кожа и красные стежки по «теннисной» кривой
  const seam = [];
  for (let i = 0; i < 64; i++) {
    const t = (i / 64) * TAU;
    seam.push(new THREE.Vector3(0.7 * Math.cos(t) + 0.3 * Math.cos(3 * t), 0.7 * Math.sin(t) - 0.3 * Math.sin(3 * t), 0.917 * Math.sin(2 * t)).normalize());
  }
  const q = new THREE.Vector3();
  B.add(sph(0.036, d ? 20 : 8, d ? 14 : 6), (x, y, z, out) => {
    q.set(x, y, z).normalize();
    let m = 9;
    for (const p of seam) m = Math.min(m, p.distanceToSquared(q));
    out.copy(m < 0.012 ? C('#d8342c') : C('#f5f0e6'));
  }, MT.matte, T(0.0, 0.098, 0.06));
  return B.build();
}

// Корзина для зонтов (плетёная), начало координат — на земле.
export function umbrellaStandGeo(d) {
  const B = new Builder();
  const a = C('#c99b5c'), b = C('#9a6b36'), rim = C('#6e4724');
  const weave = (x, y, z, out) => {
    const ang = Math.atan2(x, z);
    const u = Math.sin(ang * 14 + y * 60), v = Math.sin(ang * 14 - y * 60);
    out.copy(a).lerp(b, (u * v > 0 ? 0.15 : 0.6) * (0.75 + 0.25 * Math.sin(y * 140)));
  };
  B.add(lathe([[0.15, 0.0], [0.165, 0.02], [0.18, 0.25], [0.19, 0.48], [0.19, 0.5]], d ? 28 : 12, d ? 0.024 : 0), weave, MT.fabric);
  B.add(lathe([[0.176, 0.49], [0.168, 0.25], [0.15, 0.03], [0, 0.03]], d ? 20 : 10, d ? 0.06 : 0), (x, y, z, out) => { weave(x, y, z, out); out.multiplyScalar(0.55); }, MT.fabric);
  B.add(new THREE.TorusGeometry(0.185, 0.016, d ? 7 : 4, d ? 26 : 12), rim, MT.wood, T(0, 0.5, 0, Math.PI / 2));
  B.add(new THREE.TorusGeometry(0.16, 0.014, d ? 6 : 4, d ? 26 : 12), rim, MT.wood, T(0, 0.02, 0, Math.PI / 2));
  B.add(new THREE.TorusGeometry(0.182, 0.008, 5, d ? 26 : 12), rim, MT.wood, T(0, 0.3, 0, Math.PI / 2));
  return B.build();
}

// Пень: кора с вертикальными бороздами, годичные кольца сверху, корни.
export function stumpGeo(d) {
  const B = new Builder();
  const s = d ? 28 : 12;
  const bark = C('#6a4a2e'), barkD = C('#3e2a1a'), ring = C('#d9b07a'), ringD = C('#b58650');
  const g = lathe([[0.42, -0.04], [0.37, 0.02], [0.33, 0.1], [0.32, 0.25], [0.318, 0.4], [0.31, 0.42]], s, d ? 0.05 : 0);
  const P = g.attributes.position;
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i), a = Math.atan2(x, z);
    const k = 1 + 0.05 * Math.sin(a * 5 + 1) + 0.03 * Math.sin(a * 11);
    P.setXYZ(i, x * k, y, z * k);
  }
  g.computeVertexNormals();
  B.add(g, (x, y, z, out) => {
    const a = Math.atan2(x, z);
    out.copy(bark).lerp(barkD, sst(0.2, 1, Math.sin(a * 23 + Math.sin(y * 9) * 1.5) * 0.5 + 0.5) * 0.7);
  }, MT.matte);
  const top = new THREE.CircleGeometry(0.31, s, 0, TAU);
  const TP = top.attributes.position;
  for (let i = 0; i < TP.count; i++) {
    const x = TP.getX(i), y = TP.getY(i), a = Math.atan2(x, -y);
    const k = 1 + 0.05 * Math.sin(a * 5 + 1) + 0.03 * Math.sin(a * 11);
    TP.setXY(i, x * k, y * k);
  }
  B.add(top, (x, y, z, out) => {
    const r = Math.hypot(x, y);
    if (r > 0.29) { out.copy(barkD); return; }
    out.copy(ring).lerp(ringD, Math.sin(r * 160 + Math.sin(Math.atan2(x, y) * 3) * 0.6) * 0.5 + 0.5);
    out.lerp(C('#7a5230'), sst(0.03, 0, r) * 0.6);
  }, MT.wood, T(0, 0.42, 0, -Math.PI / 2));
  // корни
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * TAU + 0.4;
    B.add(ell(0.14, 0.07, 0.07, d ? 10 : 6, d ? 8 : 4), bark, MT.matte, T(Math.sin(a) * 0.36, 0.0, Math.cos(a) * 0.36, 0, a + Math.PI / 2, 0.25));
  }
  return B.build();
}

// Походный столик: столешница из реек и X-образные ножки.
export function campTableGeo(d) {
  const B = new Builder();
  const wood = '#c2894f', dark = '#7a5232', metal = '#5a5c60';
  const pb = (w, h, dd) => (d ? rbox(w, h, dd, 0.006, 1) : box(w, h, dd));
  for (let i = 0; i < 5; i++) B.add(pb(0.74, 0.025, 0.088), plank(wood, i + 3), MT.wood, T(0, 0.56, -0.18 + i * 0.09));
  for (const sz of [-1, 1]) B.add(pb(0.72, 0.035, 0.03), plank(dark, 9 + sz), MT.wood, T(0, 0.53, sz * 0.2));
  for (const sx of [-1, 1]) {
    for (const t of [-1, 1]) {
      const g = cyl(0.014, 0.014, 0.68, 8);
      B.add(g, metal, MT.metal, T(sx * 0.3, 0.27, 0, t * 0.62, 0, 0));
    }
    B.add(cyl(0.01, 0.01, 0.42, 8), metal, MT.metal, T(sx * 0.3, 0.27, 0, Math.PI / 2, 0, 0));
    for (const sz of [-1, 1]) B.add(cyl(0.02, 0.02, 0.02, 8), '#2a2a2c', MT.rubber, T(sx * 0.3, 0.01, sz * 0.2));
  }
  B.add(cyl(0.008, 0.008, 0.62, 8), metal, MT.metal, T(0, 0.27, 0, 0, 0, Math.PI / 2));
  return B.build();
}

// Сиденье качелей невидимо (его рисует structures.js) — только коллизия; геометрии нет.

// ---------- материал ----------
// Стандартный PBR-материал, где шероховатость, металличность и свечение берутся из вершин (aMat).
// Карты окружения в игре нет, поэтому металл и глазурь отражают «небо» — цвета полусферического света:
// хром и алюминий выглядят металлом, а не чёрным пластиком.
export function propMaterial() {
  const m = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 1, metalness: 0 });
  m.onBeforeCompile = (sh) => {
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nattribute vec3 aMat;\nvarying vec3 vMat;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\n  vMat = aMat;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vMat;')
      .replace('#include <roughnessmap_fragment>', 'float roughnessFactor = vMat.x;')
      .replace('#include <metalnessmap_fragment>', 'float metalnessFactor = vMat.y;')
      .replace('#include <emissivemap_fragment>', '#include <emissivemap_fragment>\n  totalEmissiveRadiance += diffuseColor.rgb * vMat.z;')
      .replace('#include <lights_fragment_maps>', `#include <lights_fragment_maps>
#if NUM_HEMI_LIGHTS > 0
  {
    vec3 skyR = inverseTransformDirection( reflect( - geometryViewDir, geometryNormal ), viewMatrix );
    radiance += mix( hemisphereLights[ 0 ].groundColor, hemisphereLights[ 0 ].skyColor, smoothstep( - 0.25, 0.45, skyR.y ) ) * 0.5;
  }
#endif`);
  };
  m.customProgramCacheKey = () => 'cozy-props';
  return m;
}

// Геометрия зонта для анимации раскрытия: сложенный + morph-цели «наполовину» и «раскрыт».
export function umbrellaMorphGeo(style) {
  const base = umbrellaGeo(style, 0, 1, true), half = umbrellaGeo(style, 0.5, 1, true), open = umbrellaGeo(style, 1, 1, true);
  base.morphAttributes.position = [half.attributes.position, open.attributes.position];
  base.morphAttributes.normal = [half.attributes.normal, open.attributes.normal];
  base.boundingSphere = open.boundingSphere.clone();
  base.boundingSphere.radius += 0.1;
  return base;
}
