// Набор для процедурных построек. Детали (любая three-геометрия) сразу пишутся в общие буферы:
// позиция и нормаль в мировых координатах, цвет, «поверхность» aMat (шероховатость, металличность, свечение,
// узор) и координаты узора aPat. Буферы разложены по группам (зона: хаб, беседка, домик...) и слоям:
//   solid  - крупное, отбрасывает тень;      detail - мелочь без тени (исчезает дальше detailDist);
//   far    - упрощённая копия зоны вдали;    roof:* - отдельные меши крыш (контракт C5).
// В итоге группа - 1-3 draw call одним материалом. Видимость слоёв переключается по расстоянию до камеры.

import * as THREE from 'three';
import { heightAt } from '../terrain-data.js';
import { hash2, mulberry32, smoothstep, TAU } from '../../util/math.js';
import { noise3 } from '../geo.js';
import { PAT, FLICKER } from './material.js';

export { PAT, FLICKER };

// Поверхности: [шероховатость, металличность, свечение, узор]
export const SURF = {
  plain: [0.8, 0, 0, PAT.plain], paint: [0.62, 0, 0, PAT.plain], gloss: [0.3, 0, 0, PAT.plain],
  wood: [0.74, 0, 0, PAT.wood], lacq: [0.42, 0, 0, PAT.wood], planks: [0.76, 0, 0, PAT.planks],
  brick: [0.88, 0, 0, PAT.brick], bark: [0.92, 0, 0, PAT.bark], stone: [0.9, 0, 0, PAT.stone],
  shingle: [0.8, 0, 0, PAT.shingle], rings: [0.8, 0, 0, PAT.rings], fabric: [0.95, 0, 0, PAT.fabric],
  gingham: [0.95, 0, 0, PAT.gingham], label: [0.7, 0, 0, PAT.label], straw: [0.95, 0, 0, PAT.straw],
  snow: [0.7, 0, 0, PAT.snow], metal: [0.38, 0.85, 0, PAT.metal], iron: [0.55, 0.6, 0, PAT.metal],
  padded: [0.9, 0, 0, PAT.padded], glass: [0.08, 0, 0, PAT.glass], rubber: [0.95, 0, 0, PAT.plain],
  mossy: [0.92, 0, 0, PAT.mossy], ash: [0.98, 0, 0, PAT.ash],
  ceramic: [0.22, 0, 0, PAT.plain], plastic: [0.45, 0, 0, PAT.plain],
};
// светящаяся поверхность: k - сила свечения (HDR: цвет x k > 1, чтобы ловил bloom)
export const glow = (k, pat = PAT.plain, flicker = false) => [0.5, 0, k, pat + (flicker ? FLICKER : 0)];

// Матрица из позиции, поворота (Euler XYZ) и масштаба.
const _q = new THREE.Quaternion(), _e = new THREE.Euler(), _p = new THREE.Vector3(), _s = new THREE.Vector3();
export function T(x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0, sx = 1, sy = sx, sz = sx) {
  return new THREE.Matrix4().compose(_p.set(x, y, z), _q.setFromEuler(_e.set(rx, ry, rz)), _s.set(sx, sy, sz));
}
// Поворот сначала вокруг Y, потом наклон: Euler 'YXZ' (удобно для наклонённых брёвен вокруг центра).
export function TY(x, y, z, ry, rx = 0, rz = 0, s = 1) {
  return new THREE.Matrix4().compose(_p.set(x, y, z), _q.setFromEuler(_e.set(rx, ry, rz, 'YXZ')), _s.set(s, s, s));
}

const _c = new THREE.Color();
const _v = new THREE.Vector3(), _n = new THREE.Vector3();
const _m3 = new THREE.Matrix3();
const _box = new THREE.Box3();

class Bucket {
  constructor() { this.pos = []; this.nrm = []; this.col = []; this.mat = []; this.pat = []; this.idx = []; this.n = 0; }

  toGeometry() {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(this.pos, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(this.nrm, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(this.col, 3));
    g.setAttribute('aMat', new THREE.Float32BufferAttribute(this.mat, 4));
    g.setAttribute('aPat', new THREE.Float32BufferAttribute(this.pat, 2));
    g.setIndex(this.n > 65535 ? new THREE.Uint32BufferAttribute(this.idx, 1) : new THREE.Uint16BufferAttribute(this.idx, 1));
    g.computeBoundingSphere();
    g.computeBoundingBox();
    return g;
  }
}

// Затенение у земли: низ стен и ножек темнее (вместо дорогого AO), только снаружи.
export function groundAO(wx, wy, wz, nx, ny) {
  const d = wy - heightAt(wx, wz);
  const k = smoothstep(-0.05, 0.55, d);
  return [0.6 + 0.4 * k + (ny < -0.5 ? -0.12 : 0), 0];
}

export class Kit {
  // q: 0 - low, 1 - medium, 2 - high
  constructor(q = 2) {
    this.q = q;
    this.groups = new Map();
    this.cur = null;
    this.layer = 'solid';
    this.stack = [new THREE.Matrix4()];
    this.tris = 0;
  }

  // Детализация по уровню качества.
  seg(low, mid, high) { return this.q === 0 ? low : this.q === 1 ? mid : high; }

  // Группа = зона с общим LOD. cx, cz, r - центр и радиус (для расстояния до камеры).
  group(name, opts = {}) {
    let g = this.groups.get(name);
    if (!g) {
      g = {
        name, cx: opts.cx ?? 0, cz: opts.cz ?? 0, r: opts.r ?? 10, buckets: new Map(), bake: opts.bake ?? groundAO,
        nearDist: opts.nearDist ?? Infinity, detailDist: opts.detailDist ?? Infinity, roofs: [], extra: [],
      };
      this.groups.set(name, g);
    }
    this.cur = g;
    this.layer = 'solid';
    return g;
  }

  setLayer(name) { this.layer = name; return this; }

  push(m) { this.stack.push(this.stack[this.stack.length - 1].clone().multiply(m)); return this; }
  pop() { this.stack.pop(); return this; }
  get frame() { return this.stack[this.stack.length - 1]; }

  _bucket(layer) {
    const g = this.cur;
    let b = g.buckets.get(layer);
    if (!b) { b = new Bucket(); g.buckets.set(layer, b); }
    return b;
  }

  // Крыша (C5): отдельный меш; area - мировой прямоугольник под ней {x0, x1, z0, z1}.
  roof(name, area) {
    const layer = `roof:${name}`;
    this.cur.roofs.push({ layer, area });
    this.layer = layer;
    return this;
  }

  // geo - three-геометрия в локальных координатах детали; xf - Matrix4 детали (поверх текущей рамки).
  // color: hex | Color | fn(lx, ly, lz, nx, ny, nz, out, wx, wy, wz); surf: [r, m, e, pat];
  // uv: 'box' (метры, вдоль длинной оси) | 'keep' (из geo.uv) | fn(lx, ly, lz, nx, ny, nz) -> [u, v];
  // layer - слой (по умолчанию текущий); bake: false - без затенения группы.
  add(geo, color = '#ffffff', surf = SURF.plain, xf = null, opts = {}) {
    const m = xf ? this.frame.clone().multiply(xf) : this.frame;
    const b = this._bucket(opts.layer || this.layer);
    const P = geo.getAttribute('position');
    if (!geo.getAttribute('normal')) geo.computeVertexNormals();
    const N = geo.getAttribute('normal');
    const U = geo.getAttribute('uv');
    const n = P.count;
    _m3.getNormalMatrix(m);
    const uvMode = opts.uv || 'box';
    // для проекции 'box': длинная ось детали
    let long = 0, ext = null;
    if (uvMode === 'box') {
      geo.computeBoundingBox();
      _box.copy(geo.boundingBox);
      ext = [_box.max.x - _box.min.x, _box.max.y - _box.min.y, _box.max.z - _box.min.z];
      long = ext[0] >= ext[1] && ext[0] >= ext[2] ? 0 : ext[1] >= ext[2] ? 1 : 2;
    }
    const fixed = typeof color === 'function' ? null : _c.set(color).clone();
    const bake = opts.bake === false ? null : this.cur.bake;
    const e = m.elements;
    const base = b.n;
    const sm = surf;
    const lit = opts.lit;
    for (let i = 0; i < n; i++) {
      const lx = P.getX(i), ly = P.getY(i), lz = P.getZ(i);
      const nx = N.getX(i), ny = N.getY(i), nz = N.getZ(i);
      _v.set(lx, ly, lz).applyMatrix4(m);
      _n.set(nx, ny, nz).applyMatrix3(_m3).normalize();
      if (fixed) _c.copy(fixed); else color(lx, ly, lz, nx, ny, nz, _c, _v.x, _v.y, _v.z);
      let em = sm[2];
      if (bake) {
        const [k, gl] = bake(_v.x, _v.y, _v.z, _n.x, _n.y, _n.z);
        _c.multiplyScalar(k);
        em += gl;
      }
      if (lit) em += lit;
      if (opts.emis) em += opts.emis(lx, ly, lz, _v.x, _v.y, _v.z);
      b.pos.push(_v.x, _v.y, _v.z);
      b.nrm.push(_n.x, _n.y, _n.z);
      b.col.push(_c.r, _c.g, _c.b);
      b.mat.push(sm[0], sm[1], em, sm[3]);
      let u = 0, w = 0;
      if (uvMode === 'keep') { if (U) { u = U.getX(i); w = U.getY(i); } } else if (typeof uvMode === 'function') {
        const r = uvMode(lx, ly, lz, nx, ny, nz); u = r[0]; w = r[1];
      } else {
        // проекция по доминирующей оси нормали; u - вдоль длинной оси детали, если она лежит в плоскости
        const ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        const dom = ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
        const c = [lx, ly, lz];
        const a = dom === 0 ? [1, 2] : dom === 1 ? [0, 2] : [0, 1];
        if (a[0] === long) { u = c[a[0]]; w = c[a[1]]; } else if (a[1] === long) { u = c[a[1]]; w = c[a[0]]; } else { u = c[a[0]]; w = c[a[1]]; }
      }
      b.pat.push(u, w);
    }
    const I = geo.index;
    if (I) {
      for (let i = 0; i < I.count; i++) b.idx.push(base + I.getX(i));
      this.tris += I.count / 3;
    } else {
      for (let i = 0; i < n; i++) b.idx.push(base + i);
      this.tris += n / 3;
    }
    b.n += n;
    return this;
  }

  // ---------- удобные детали ----------

  // Брусок с фаской (на low - простой). w x h x d, центр в начале координат детали.
  box(w, h, d, color, surf, xf, opts = {}) {
    const bev = opts.bevel ?? Math.min(0.02, Math.min(w, h, d) * 0.18);
    const g = this.q === 0 || bev <= 0 || opts.sharp ? new THREE.BoxGeometry(w, h, d) : bevelBox(w, h, d, bev);
    return this.add(g, color, surf, xf, opts);
  }

  cyl(rt, rb, h, color, surf, xf, opts = {}) {
    const seg = opts.seg ?? this.seg(6, 8, 12);
    return this.add(new THREE.CylinderGeometry(rt, rb, h, seg, opts.hseg ?? 1, !!opts.open), color, surf, xf, { uv: cylUV, ...opts });
  }

  sphere(r, color, surf, xf, opts = {}) {
    const ws = opts.seg ?? this.seg(6, 8, 12);
    return this.add(new THREE.SphereGeometry(r, ws, Math.max(3, Math.round(ws * 0.7))), color, surf, xf, opts);
  }
}

// uv цилиндра: u - вдоль оси Y (длина), v - по окружности (метры)
export function cylUV(x, y, z, nx, ny) {
  if (Math.abs(ny) > 0.9) return [x, z];
  return [y, Math.atan2(x, z) * Math.hypot(x, z)];
}

// ---------- геометрия ----------

// Брусок с фасками: 6 граней + 12 фасок + 8 уголков. Нормали фасок плавно переходят между гранями
// (выглядит как скругление, ловит блик), 44 треугольника.
export function bevelBox(w, h, d, b) {
  const hx = w / 2, hy = h / 2, hz = d / 2;
  b = Math.min(b, hx * 0.9, hy * 0.9, hz * 0.9);
  const pos = [], nrm = [], idx = [];
  // вершина: угол (sx, sy, sz) и грань f (0 - x, 1 - y, 2 - z)
  const vid = (sx, sy, sz, f) => ((((sx > 0) << 2) | ((sy > 0) << 1) | (sz > 0)) * 3 + f);
  for (let k = 0; k < 8; k++) {
    const sx = k & 4 ? 1 : -1, sy = k & 2 ? 1 : -1, sz = k & 1 ? 1 : -1;
    pos.push(sx * hx, sy * (hy - b), sz * (hz - b)); nrm.push(sx, 0, 0);
    pos.push(sx * (hx - b), sy * hy, sz * (hz - b)); nrm.push(0, sy, 0);
    pos.push(sx * (hx - b), sy * (hy - b), sz * hz); nrm.push(0, 0, sz);
  }
  const tri = (a, c, e) => {
    // ориентация по средней нормали
    const ax = pos[a * 3], ay = pos[a * 3 + 1], az = pos[a * 3 + 2];
    const ux = pos[c * 3] - ax, uy = pos[c * 3 + 1] - ay, uz = pos[c * 3 + 2] - az;
    const vx = pos[e * 3] - ax, vy = pos[e * 3 + 1] - ay, vz = pos[e * 3 + 2] - az;
    const cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
    const mx = nrm[a * 3] + nrm[c * 3] + nrm[e * 3], my = nrm[a * 3 + 1] + nrm[c * 3 + 1] + nrm[e * 3 + 1], mz = nrm[a * 3 + 2] + nrm[c * 3 + 2] + nrm[e * 3 + 2];
    if (cx * mx + cy * my + cz * mz >= 0) idx.push(a, c, e); else idx.push(a, e, c);
  };
  const quad = (a, c, e, f) => { tri(a, c, e); tri(a, e, f); };
  const S = [-1, 1];
  // грани
  for (const sx of S) quad(vid(sx, -1, -1, 0), vid(sx, 1, -1, 0), vid(sx, 1, 1, 0), vid(sx, -1, 1, 0));
  for (const sy of S) quad(vid(-1, sy, -1, 1), vid(1, sy, -1, 1), vid(1, sy, 1, 1), vid(-1, sy, 1, 1));
  for (const sz of S) quad(vid(-1, -1, sz, 2), vid(1, -1, sz, 2), vid(1, 1, sz, 2), vid(-1, 1, sz, 2));
  // фаски вдоль Z (между гранями x и y), вдоль X (y и z), вдоль Y (x и z)
  for (const sx of S) for (const sy of S) quad(vid(sx, sy, -1, 0), vid(sx, sy, 1, 0), vid(sx, sy, 1, 1), vid(sx, sy, -1, 1));
  for (const sy of S) for (const sz of S) quad(vid(-1, sy, sz, 1), vid(1, sy, sz, 1), vid(1, sy, sz, 2), vid(-1, sy, sz, 2));
  for (const sx of S) for (const sz of S) quad(vid(sx, -1, sz, 0), vid(sx, 1, sz, 0), vid(sx, 1, sz, 2), vid(sx, -1, sz, 2));
  // уголки
  for (const sx of S) for (const sy of S) for (const sz of S) tri(vid(sx, sy, sz, 0), vid(sx, sy, sz, 1), vid(sx, sy, sz, 2));
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3));
  g.setIndex(idx);
  return g;
}

// Профиль вращения [[r, y], ...] вокруг оси Y.
export function lathe(prof, seg) {
  return new THREE.LatheGeometry(prof.map(([r, y]) => new THREE.Vector2(Math.max(0, r), y)), seg);
}

// Трубка вдоль ломаной: radius - число или fn(t). Индексированная, с гладкими нормалями.
export function tube(points, radius, radial = 6, caps = false) {
  const n = points.length;
  const pos = [], idx = [];
  const T0 = new THREE.Vector3(), Nn = new THREE.Vector3(), B = new THREE.Vector3(), tmp = new THREE.Vector3();
  const tang = points.map((p, i) => new THREE.Vector3().subVectors(points[Math.min(n - 1, i + 1)], points[Math.max(0, i - 1)]).normalize());
  T0.copy(tang[0]);
  Nn.set(0, 1, 0);
  if (Math.abs(Nn.dot(T0)) > 0.9) Nn.set(1, 0, 0);
  Nn.addScaledVector(T0, -Nn.dot(T0)).normalize();
  for (let i = 0; i < n; i++) {
    Nn.addScaledVector(tang[i], -Nn.dot(tang[i])).normalize();
    B.crossVectors(tang[i], Nn);
    const r = typeof radius === 'function' ? radius(i / (n - 1)) : radius;
    for (let k = 0; k <= radial; k++) {
      const a = (k / radial) * TAU;
      tmp.copy(points[i]).addScaledVector(Nn, Math.cos(a) * r).addScaledVector(B, Math.sin(a) * r);
      pos.push(tmp.x, tmp.y, tmp.z);
    }
  }
  const R = radial + 1;
  for (let i = 0; i < n - 1; i++) {
    for (let k = 0; k < radial; k++) {
      const a = i * R + k, b = a + 1, c = a + R, d = c + 1;
      idx.push(a, b, c, b, d, c);
    }
  }
  if (caps) {
    for (const [i, flip] of [[0, false], [n - 1, true]]) {
      const ci = pos.length / 3;
      pos.push(points[i].x, points[i].y, points[i].z);
      for (let k = 0; k < radial; k++) {
        const a = i * R + k, b = a + 1;
        if (flip) idx.push(ci, a, b); else idx.push(ci, b, a);
      }
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

// Провисающий провод/верёвка между двумя точками (цепная линия приближённо параболой).
export function sagPoints(a, b, sag, n = 8) {
  const out = [];
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    out.push(new THREE.Vector3().lerpVectors(a, b, t).add(new THREE.Vector3(0, -sag * 4 * t * (1 - t), 0)));
  }
  return out;
}

// Бревно вдоль оси X длиной len: неровный профиль, кора, сучки; торцы - отдельные геометрии со спилом.
// flat - срезанный верх (сиденье), возвращает { side, capA, capB, r(t, a) }.
export function logParts(len, r, { seg = 10, rings = 6, seed = 1, flat = 0, taper = 0.06, bend = 0 } = {}) {
  const rand = mulberry32(seed);
  const ph = rand() * 10;
  const rad = (t, a) => {
    const n = noise3(Math.cos(a) * 1.3 + ph, Math.sin(a) * 1.3, t * len * 1.6, seed) - 0.5;
    return r * (1 + n * 0.22 + 0.05 * Math.sin(a * 3 + ph) - taper * t);
  };
  const pos = [], idx = [];
  const R = seg + 1;
  for (let i = 0; i <= rings; i++) {
    const t = i / rings;
    const x = (t - 0.5) * len;
    const yb = bend * 4 * t * (1 - t);
    for (let k = 0; k <= seg; k++) {
      const a = (k / seg) * TAU;
      const rr = rad(t, a);
      let y = Math.cos(a) * rr + yb, z = Math.sin(a) * rr;
      if (flat > 0 && y > r * (1 - flat)) y = r * (1 - flat) + (y - r * (1 - flat)) * 0.08;
      pos.push(x, y, z);
    }
  }
  for (let i = 0; i < rings; i++) {
    for (let k = 0; k < seg; k++) {
      const a = i * R + k, b = a + 1, c = a + R, d = c + 1;
      idx.push(a, b, c, b, d, c);
    }
  }
  const side = new THREE.BufferGeometry();
  side.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  side.setIndex(idx);
  side.computeVertexNormals();
  // торцы: кольцо коры по краю + спил
  const cap = (end) => {
    const t = end;
    const x = (t - 0.5) * len;
    const yb = bend * 4 * t * (1 - t);
    const cp = [x, yb, 0], ip = [];
    const p2 = [];
    for (let k = 0; k <= seg; k++) {
      const a = (k / seg) * TAU;
      const rr = rad(t, a);
      let y = Math.cos(a) * rr, z = Math.sin(a) * rr;
      if (flat > 0 && y > r * (1 - flat)) y = r * (1 - flat) + (y - r * (1 - flat)) * 0.08;
      p2.push([x, y + yb, z]);
      ip.push([x + (end ? -0.006 : 0.006), y * 0.86 + yb, z * 0.86]);
    }
    const P = [cp[0] + (end ? -0.01 : 0.01), cp[1], cp[2]];
    const pp = [...P];
    for (const q of ip) pp.push(...q);
    for (const q of p2) pp.push(...q);
    const id = [];
    for (let k = 0; k < seg; k++) {
      const a = 1 + k, b = 2 + k, c = 1 + R + k, d = 2 + R + k;
      if (end) { id.push(0, a, b, a, c, b, b, c, d); } else { id.push(0, b, a, a, b, c, b, d, c); }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pp, 3));
    g.setIndex(id);
    const nn = [];
    for (let i = 0; i < pp.length / 3; i++) nn.push(end ? 1 : -1, 0, 0);
    g.setAttribute('normal', new THREE.Float32BufferAttribute(nn, 3));
    g.userData.rim = r * 0.86;
    return g;
  };
  return { side, capA: cap(0), capB: cap(1), rad };
}

// Добавить бревно целиком: кора + спилы с кольцами. xf - положение центра бревна (ось X бревна).
export function addLog(kit, len, r, xf, { bark = '#7a5232', barkDark = '#4a3020', wood = '#d9a86a', seed = 1, flat = 0, seg, rings, char = 0, bend = 0, layer } = {}) {
  const parts = logParts(len, r, { seg: seg ?? kit.seg(7, 9, 12), rings: rings ?? kit.seg(2, 4, 6), seed, flat, bend });
  const bc = new THREE.Color(bark), bd = new THREE.Color(barkDark), wc = new THREE.Color(wood), charC = new THREE.Color('#1c1612');
  const flatY = r * (1 - flat);
  kit.add(parts.side, (x, y, z, nx, ny, nz, out) => {
    const a = Math.atan2(z, y);
    const n = hash2(Math.round(a * 3 + seed), Math.round(x * 2), seed);
    out.copy(bc).lerp(bd, 0.25 + n * 0.35);
    if (flat > 0 && y >= flatY - 0.004) out.copy(wc).multiplyScalar(0.92);
    if (char > 0) {
      const t = x / len + 0.5;
      const k = smoothstep(1 - char, 1 - char * 0.4, t);
      out.lerp(charC, k * 0.92);
    }
  }, SURF.bark, xf, { uv: (x, y, z) => [x, Math.atan2(z, y) * r], layer });
  for (const cap of [parts.capA, parts.capB]) {
    const end = cap === parts.capB;
    const rim = cap.userData.rim;
    kit.add(cap, (x, y, z, nx, ny, nz, out) => {
      const rr = Math.hypot(y, z);
      if (rr > rim * 0.97) out.copy(bd); else out.copy(wc);
      if (char > 0 && end) out.lerp(charC, 0.85);
    }, SURF.rings, xf, { uv: (x, y, z) => [y, z], layer });
  }
}

// Сглаженный камень: шумный икосаэдр с плоским низом. Индексированный.
export function stoneGeo(r, seed, detail = 1, flatBottom = 0.35, squash = 0.75) {
  const g = new THREE.IcosahedronGeometry(1, detail);
  // склеиваем вершины (Icosahedron без индекса)
  const P = g.getAttribute('position');
  const map = new Map(), pos = [], idx = [];
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i);
    const k = `${Math.round(x * 1e4)},${Math.round(y * 1e4)},${Math.round(z * 1e4)}`;
    let j = map.get(k);
    if (j === undefined) { j = pos.length / 3; map.set(k, j); pos.push(x, y, z); }
    idx.push(j);
  }
  for (let i = 0; i < pos.length; i += 3) {
    let x = pos[i], y = pos[i + 1], z = pos[i + 2];
    const n = noise3(x * 1.4 + seed, y * 1.4, z * 1.4 - seed, seed) - 0.5;
    const n2 = noise3(x * 3 - seed, y * 3, z * 3, seed + 7) - 0.5;
    const k = r * (1 + n * 0.42 + n2 * 0.12);
    x *= k; y *= k * squash; z *= k * (0.85 + (seed % 5) * 0.06);
    if (y < -r * squash * (1 - flatBottom)) y = -r * squash * (1 - flatBottom) + (y + r * squash * (1 - flatBottom)) * 0.15;
    pos[i] = x; pos[i + 1] = y; pos[i + 2] = z;
  }
  const out = new THREE.BufferGeometry();
  out.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  out.setIndex(idx);
  out.computeVertexNormals();
  out.computeBoundingBox();
  out.translate(0, -out.boundingBox.min.y, 0); // низ на y = 0
  return out;
}

// Плоский многоугольник (контур [[x, y], ...] против часовой) с толщиной depth вдоль Z, центр по Z = 0.
export function slabGeo(outline, depth, bevel = 0) {
  const s = new THREE.Shape(outline.map(([x, y]) => new THREE.Vector2(x, y)));
  const g = new THREE.ExtrudeGeometry(s, {
    depth: depth - bevel * 2, bevelEnabled: bevel > 0, bevelThickness: bevel, bevelSize: bevel, bevelSegments: 1, curveSegments: 6,
  });
  g.translate(0, 0, -(depth - bevel * 2) / 2);
  g.deleteAttribute('uv');
  return g;
}

// Прямоугольная сетка w x h (по X и Y, нормаль +Z), nx x ny ячеек: для полов и стен с запечённым затенением.
export function gridPlane(w, h, nx, ny) {
  return new THREE.PlaneGeometry(w, h, Math.max(1, nx), Math.max(1, ny));
}
