// Мелкие детали природы: цветы (ромашки, маки, колокольчики, одуванчики, клевер), папоротники,
// грибы, галька и камни, брёвна и пни (с коллизиями), камыш и кувшинки, опавшие листья и тыквы,
// сугробы и заснеженные камни, полынь и камни каньона.
// Чанки 40 м вокруг камеры; всё в чанке склеено в две геометрии (мелочь без теней и крупное с тенями),
// то есть 2 draw call на чанк. Раскладка детерминированная (у всех игроков одинаковая).

import * as THREE from 'three';
import { mulberry32, hash2, smoothstep, TAU, Noise2D, lerp } from '../util/math.js';
import {
  heightAt, pathDist, zoneWeights, slopeAt, isInterior, WATER_Y, SITES, LAKE, WORLD_RADIUS,
} from './terrain-data.js';
import {
  PlantBuilder, tube, blobGeometry, noise3, card, mergeVerticesSimple,
} from './geo.js';
import { ATLAS, WHITE_UV } from './nature/atlas.js';
import { decorMaterial } from './nature/materials.js';
import { canopyDensity as forestDensity, flowerPatch, inLake } from './nature/forest.js';
import { chunkKey } from './nature/shared.js';

const CH = 40;
const V = (x = 0, y = 0, z = 0) => new THREE.Vector3(x, y, z);
const C = (h) => new THREE.Color(h);
const UP = V(0, 1, 0);

const speciesNoise = new Noise2D(6262);
const lilyNoise = new Noise2D(7373);

// ---------- шаблоны ----------

const STEM = C('#4f8a2c'), STEM_D = C('#3a6b22'), LEAF = C('#5f9e35');

// Плоский лепесток/лист: от основания o в направлении dir (в плоскости, наклон up), ширина wid.
function petal(b, o, ang, len, wid, tilt, color, { tip = null, wind = 1, cup = 0 } = {}) {
  const d = V(Math.cos(ang) * Math.cos(tilt), Math.sin(tilt), Math.sin(ang) * Math.cos(tilt));
  const side = V(-Math.sin(ang), 0, Math.cos(ang));
  const n = side.clone().cross(d).normalize();
  if (n.y < 0) n.negate();
  const p0 = o.clone();
  const pm = o.clone().addScaledVector(d, len * 0.5);
  const l = pm.clone().addScaledVector(side, -wid / 2).addScaledVector(n, cup);
  const r = pm.clone().addScaledVector(side, wid / 2).addScaledVector(n, cup);
  const p1 = o.clone().addScaledVector(d, len);
  const c0 = color, c1 = tip || color;
  const nn = n.clone().lerp(UP, 0.4).normalize();
  b.vert(p0, nn, c0, WHITE_UV, 0, wind); b.vert(r, nn, c1, WHITE_UV, 0, wind); b.vert(l, nn, c1, WHITE_UV, 0, wind);
  b.vert(l, nn, c1, WHITE_UV, 0, wind); b.vert(r, nn, c1, WHITE_UV, 0, wind); b.vert(p1, nn, c1, WHITE_UV, 0, wind);
}

function stem(b, base, top, r, color = STEM, bend = 0.3) {
  const mid = base.clone().lerp(top, 0.55);
  mid.x += (top.x - base.x) * bend;
  mid.z += (top.z - base.z) * bend;
  tube(b, [base, mid, top], () => r, { segs: 3, color: (t, a, i, k, o) => o.copy(color).lerp(STEM_D, 0.3 * (1 - t)), uv: WHITE_UV, wind: 1 });
}

function blob(b, r, detail, seed, pos, scale, color, wind = 0, amp = 0.15) {
  const g = blobGeometry(r, detail, seed, amp, 1.3);
  g.scale(scale.x, scale.y, scale.z);
  g.translate(pos.x, pos.y, pos.z);
  b.addGeometry(g, { uv: WHITE_UV, wind, color });
}

function baseLeaves(b, rand, n, h) {
  for (let i = 0; i < n; i++) {
    petal(b, V(0, 0.005, 0), rand() * TAU, h * (0.7 + rand() * 0.5), 0.022, 0.55 + rand() * 0.4, LEAF, { tip: STEM, cup: 0.004 });
  }
}

function daisy(b, x, z, h, rand) {
  const top = V(x + (rand() - 0.5) * 0.05, h, z + (rand() - 0.5) * 0.05);
  stem(b, V(x, 0, z), top, 0.005);
  const white = C('#fbfbf6'), tipc = C('#f2eee8');
  const n = 11;
  const a0 = rand() * TAU;
  for (let i = 0; i < n; i++) petal(b, top, a0 + (i / n) * TAU, 0.072, 0.022, 0.18, white, { tip: tipc, cup: 0.003 });
  blob(b, 0.024, 0, 1, top.clone().add(V(0, 0.006, 0)), V(1, 0.55, 1), C('#f3c21c'), 1, 0.05);
}

function poppy(b, x, z, h, rand) {
  const top = V(x + (rand() - 0.5) * 0.08, h, z + (rand() - 0.5) * 0.08);
  stem(b, V(x, 0, z), top, 0.006, STEM, 0.5);
  const red = C('#e3241e'), deep = C('#a5120f');
  const a0 = rand() * TAU;
  for (let i = 0; i < 4; i++) petal(b, top, a0 + (i / 4) * TAU, 0.085, 0.085, 0.7, deep, { tip: red, cup: -0.01 });
  for (let i = 0; i < 4; i++) petal(b, top.clone().add(V(0, 0.005, 0)), a0 + ((i + 0.5) / 4) * TAU, 0.07, 0.07, 0.9, deep, { tip: red, cup: -0.008 });
  blob(b, 0.018, 0, 2, top.clone().add(V(0, 0.012, 0)), V(1, 0.8, 1), C('#2a2018'), 1, 0.05);
}

function bluebell(b, x, z, h, rand) {
  const lean = rand() * TAU;
  const dx = Math.cos(lean), dz = Math.sin(lean);
  const pts = [V(x, 0, z), V(x + dx * 0.02, h * 0.6, z + dz * 0.02), V(x + dx * 0.07, h, z + dz * 0.07), V(x + dx * 0.13, h * 0.9, z + dz * 0.13)];
  tube(b, pts, () => 0.004, { segs: 3, color: (t, a, i, k, o) => o.copy(STEM), uv: WHITE_UV, wind: 1 });
  const blue = C('#5b63dc'), blueL = C('#8c90f0');
  for (let i = 0; i < 4; i++) {
    const t = 0.35 + i * 0.2;
    const p = pts[1].clone().lerp(pts[3], t);
    const g = new THREE.CylinderGeometry(0.006, 0.028, 0.045, 6, 1, true);
    g.translate(0, -0.035, 0);
    g.rotateZ((rand() - 0.5) * 0.4);
    g.translate(p.x + (rand() - 0.5) * 0.02, p.y, p.z + (rand() - 0.5) * 0.02);
    b.addGeometry(g, { uv: WHITE_UV, wind: 1, color: (q, n, c) => c.copy(blue).lerp(blueL, Math.max(0, n.y) * 0.6 + 0.2) });
  }
}

function dandelion(b, x, z, h, rand, puff) {
  const top = V(x + (rand() - 0.5) * 0.04, h, z + (rand() - 0.5) * 0.04);
  stem(b, V(x, 0, z), top, 0.004, C('#6a9a3a'));
  if (puff) {
    // пушок: белые лучики-парашютики во все стороны + серая серединка
    const c = top.clone().add(V(0, 0.035, 0));
    const white = C('#fbfbf7'), gray = C('#dedcd0');
    const nP = 46;
    for (let i = 0; i < nP; i++) {
      const yy = 1 - (2 * (i + 0.5)) / nP, rr = Math.sqrt(1 - yy * yy), a = i * 2.39996;
      const d = V(Math.cos(a) * rr, yy, Math.sin(a) * rr);
      const tip = c.clone().addScaledVector(d, 0.045);
      const side = d.clone().cross(Math.abs(d.y) > 0.9 ? V(1, 0, 0) : UP).normalize().multiplyScalar(0.007);
      const n = d;
      b.vert(c, n, gray, WHITE_UV, 0, 1);
      b.vert(tip.clone().add(side), n, white, WHITE_UV, 0, 1);
      b.vert(tip.clone().sub(side), n, white, WHITE_UV, 0, 1);
    }
    blob(b, 0.012, 0, 3, c, V(1, 1, 1), gray, 1, 0.1);
  } else {
    const yel = C('#f7c514'), yl = C('#ffe066');
    const n = 14;
    for (let i = 0; i < n; i++) petal(b, top, (i / n) * TAU, 0.032, 0.012, 0.25, yel, { tip: yl });
    for (let i = 0; i < 9; i++) petal(b, top.clone().add(V(0, 0.005, 0)), (i / 9) * TAU + 0.3, 0.02, 0.01, 0.6, yel, { tip: yl });
  }
}

function cloverPatch(b, rand) {
  const green = C('#4e9a35'), light = C('#7cbf4f');
  for (let i = 0; i < 7; i++) {
    const x = (rand() - 0.5) * 0.3, z = (rand() - 0.5) * 0.3, h = 0.05 + rand() * 0.06;
    const top = V(x, h, z);
    stem(b, V(x, 0, z), top, 0.003, STEM, 0.1);
    const a0 = rand() * TAU;
    for (let k = 0; k < 3; k++) {
      const a = a0 + (k / 3) * TAU;
      petal(b, top, a - 0.25, 0.034, 0.03, 0.1, green, { tip: light, cup: -0.003 });
      petal(b, top, a + 0.25, 0.034, 0.03, 0.1, green, { tip: light, cup: -0.003 });
    }
  }
  for (let i = 0; i < 2; i++) {
    const x = (rand() - 0.5) * 0.25, z = (rand() - 0.5) * 0.25, h = 0.12 + rand() * 0.05;
    const top = V(x, h, z);
    stem(b, V(x, 0, z), top, 0.003, STEM, 0.1);
    const pink = rand() < 0.5;
    blob(b, 0.022, 1, 4 + i, top, V(1, 1.1, 1), (q, n, c) => c.set(pink ? '#e58fbf' : '#f4f1e6').multiplyScalar(0.8 + 0.2 * Math.max(0, n.y)), 1, 0.3);
  }
}

function flowerCluster(kind, seed) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  baseLeaves(b, rand, 4, 0.12);
  const n = kind === 'poppy' ? 3 : kind === 'bluebell' ? 4 : kind === 'dandelion' ? 3 : 5;
  for (let i = 0; i < n; i++) {
    const a = rand() * TAU, r = i === 0 ? 0 : 0.06 + rand() * 0.14;
    const x = Math.cos(a) * r, z = Math.sin(a) * r;
    if (kind === 'daisy') daisy(b, x, z, 0.3 + rand() * 0.16, rand);
    else if (kind === 'poppy') poppy(b, x, z, 0.42 + rand() * 0.2, rand);
    else if (kind === 'bluebell') bluebell(b, x, z, 0.32 + rand() * 0.12, rand);
    else dandelion(b, x, z, 0.3 + rand() * 0.16, rand, i % 2 === 0);
  }
  return b;
}

function fern(seed, lod) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const n = lod > 0.7 ? 8 : 6;
  const [u0, v0, u1, v1] = ATLAS.fern;
  const dark = C('#2c5a24'), light = C('#79ad45');
  for (let i = 0; i < n; i++) {
    const a = (i / n) * TAU + rand() * 0.4;
    const L = 0.75 + rand() * 0.35, W = 0.34;
    const dir = V(Math.cos(a), 0, Math.sin(a)), side = V(-Math.sin(a), 0, Math.cos(a));
    const rise = 0.9 + rand() * 0.3;
    const segs = 4;
    const rows = [];
    for (let s = 0; s <= segs; s++) {
      const t = s / segs;
      // дуга: вверх-наружу, кончик свисает
      const c = dir.clone().multiplyScalar(L * t * 0.85).add(V(0, L * (rise * t - 0.95 * t * t) * 0.9, 0));
      const tan = dir.clone().multiplyScalar(0.85).add(V(0, rise - 1.9 * t, 0)).normalize();
      const nUp = side.clone().cross(tan).normalize();
      const w = W * (0.35 + 0.65 * Math.sin(Math.min(1, t * 1.3) * Math.PI * 0.85 + 0.15));
      const col = dark.clone().lerp(light, t);
      const nn = nUp.clone().multiplyScalar(0.7).addScaledVector(dir, 0.3).addScaledVector(UP, 0.3).normalize();
      rows.push([
        { p: c.clone().addScaledVector(side, -w / 2), uv: [u0, v0 + t * (v1 - v0)] },
        { p: c.clone().addScaledVector(side, w / 2), uv: [u1, v0 + t * (v1 - v0)] },
      ].map((q) => ({ ...q, n: nn, c: col })));
    }
    const put = (q) => b.vert(q.p, q.n, q.c, q.uv, 0, 0.7);
    for (let s = 0; s < segs; s++) {
      const A = rows[s], B = rows[s + 1];
      put(A[0]); put(B[0]); put(A[1]);
      put(A[1]); put(B[0]); put(B[1]);
    }
  }
  return b;
}

function mushroom(b, rand, x, z, s, red) {
  const h = (red ? 0.11 : 0.08) * s, r = (red ? 0.07 : 0.065) * s;
  const stemC = red ? C('#f1ece0') : C('#d8c6a4');
  tube(b, [V(x, 0, z), V(x, h * 0.5, z), V(x, h, z)], (t) => (red ? lerp(0.022, 0.017, t) : lerp(0.03, 0.022, t)) * s, {
    segs: 6, color: (t, a, i, k, o) => o.copy(stemC).multiplyScalar(0.85 + 0.15 * t), uv: WHITE_UV,
  });
  // шляпка: приплюснутая полусфера, снизу пластинки
  const cap = new THREE.SphereGeometry(r, 9, 4, 0, TAU, 0, Math.PI / 2);
  cap.scale(1, red ? 0.62 : 0.72, 1);
  cap.translate(x, h * 0.92, z);
  const capC = red ? C('#d8261b') : C('#7d4b26'), capL = red ? C('#f0442c') : C('#a46a3a');
  b.addGeometry(cap, { uv: WHITE_UV, color: (p, n, c) => c.copy(capC).lerp(capL, Math.max(0, n.y) * 0.6) });
  const under = new THREE.CircleGeometry(r * 0.98, 9);
  under.rotateX(Math.PI / 2);
  under.translate(x, h * 0.92, z);
  b.addGeometry(under, { uv: WHITE_UV, color: red ? C('#f3e6c8') : C('#c9a66a') });
  if (red) {
    for (let i = 0; i < 6; i++) {
      const a = rand() * TAU, e = 0.3 + rand() * 0.9;
      const d = V(Math.cos(a) * Math.cos(e), Math.sin(e) * 0.62, Math.sin(a) * Math.cos(e));
      blob(b, 0.009 * s, 0, 7 + i, V(x, h * 0.92, z).addScaledVector(d, r * 1.0), V(1, 0.45, 1), C('#fbf8ef'), 0, 0.1);
    }
  }
}

function mushroomCluster(seed, red) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const n = red ? 2 : 3;
  for (let i = 0; i < n; i++) {
    const a = rand() * TAU, r = i === 0 ? 0 : 0.08 + rand() * 0.08;
    mushroom(b, rand, Math.cos(a) * r, Math.sin(a) * r, i === 0 ? 1.1 : 0.6 + rand() * 0.35, red);
  }
  return b;
}

function rockGeo(seed, detail = 1) {
  const g = mergeVerticesSimple(new THREE.IcosahedronGeometry(1, detail));
  const P = g.getAttribute('position');
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i);
    const k = 0.8 + 0.4 * noise3(x * 1.6 + 2, y * 1.6, z * 1.6, seed);
    let yy = y * k * 0.62;
    if (yy < -0.3) yy = -0.3 + (yy + 0.3) * 0.2; // плоское дно без вырожденных граней
    P.setXYZ(i, x * k, yy, z * k);
  }
  g.computeVertexNormals();
  return g;
}

function pebbles(seed) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  for (let i = 0; i < 4; i++) {
    const g = rockGeo(seed + i, 0);
    const s = 0.04 + rand() * 0.07;
    g.scale(s * (1 + rand() * 0.4), s, s);
    g.rotateY(rand() * TAU);
    g.translate((rand() - 0.5) * 0.4, s * 0.15, (rand() - 0.5) * 0.4);
    const v = 0.5 + rand() * 0.4;
    const warm = rand() < 0.5;
    b.addGeometry(g, { uv: WHITE_UV, color: (p, n, c) => c.setRGB(v * (warm ? 1.05 : 0.95), v, v * (warm ? 0.9 : 1.02)).multiplyScalar(0.8 + 0.25 * Math.max(0, n.y)) });
  }
  return b;
}

function stone(seed, snow = false, moss = false) {
  const b = new PlantBuilder();
  const g = rockGeo(seed, 1);
  const mossC = C('#6a9a3a'), snowC = C('#f3f7fc');
  b.addGeometry(g, {
    uv: WHITE_UV,
    color: (p, n, c) => {
      const v = 0.46 + 0.26 * noise3(p.x * 3, p.y * 3, p.z * 3, seed);
      c.setRGB(v, v * 1.01, v * 1.04);
      if (moss) c.lerp(mossC, smoothstep(0.5, 0.85, n.y) * 0.8);
      if (snow) c.lerp(snowC, smoothstep(0.25, 0.6, n.y));
    },
  });
  return b;
}

// Бревно вдоль оси X длиной 1 и радиусом 1 (масштабируется под экземпляр).
function logGeo(seed) {
  const b = new PlantBuilder();
  const bark = C('#6e4c30'), barkL = C('#8f6a44'), wood = C('#e0bb86'), ring = C('#b4864f'), moss = C('#6f9a3a');
  const segs = 10;
  tube(b, [V(-0.5, 0, 0), V(-0.17, 0, 0), V(0.17, 0, 0), V(0.5, 0, 0)], (t, a) => 1 + 0.05 * Math.sin(a * 3 + t * 5), {
    segs,
    color: (t, a, i, k, o) => {
      o.copy(bark).lerp(barkL, hash2(k, i, seed) * 0.7);
      // мох сверху
      const up = Math.cos(a) > 0.55 && hash2(k + 3, i, seed) > 0.35;
      if (up) o.lerp(moss, 0.7);
    },
    uv: WHITE_UV,
  });
  for (const s of [-1, 1]) {
    const g = new THREE.CircleGeometry(1.0, segs);
    g.rotateY(s * Math.PI / 2);
    g.translate(s * 0.5, 0, 0);
    b.addGeometry(g, {
      uv: WHITE_UV,
      color: (p, n, c) => {
        const r = Math.hypot(p.y, p.z);
        c.copy(wood).lerp(ring, (Math.sin(r * 22) * 0.5 + 0.5) * 0.5).lerp(bark, smoothstep(0.85, 1.0, r));
      },
    });
  }
  return b;
}

// Пень: радиус 1, высота 1, корни-наплывы, срез с годовыми кольцами.
function stumpGeo(seed) {
  const b = new PlantBuilder();
  const bark = C('#6a4a32'), barkL = C('#8c6646'), wood = C('#e2bd88'), ring = C('#b08048');
  const segs = 11;
  tube(b, [V(0, -0.15, 0), V(0, 0.3, 0), V(0, 1, 0)], (t, a) => (1 + (1 - smoothstep(0, 0.45, t)) * 0.45 * (0.6 + 0.4 * Math.sin(a * 5 + seed))) * (1 + 0.04 * Math.sin(a * 7)), {
    segs,
    color: (t, a, i, k, o) => o.copy(bark).lerp(barkL, hash2(k, i, seed) * 0.8),
    uv: WHITE_UV,
  });
  const top = new THREE.CircleGeometry(1.0, segs);
  top.rotateX(-Math.PI / 2);
  top.translate(0, 1, 0);
  b.addGeometry(top, {
    uv: WHITE_UV,
    color: (p, n, c) => {
      const r = Math.hypot(p.x, p.z);
      c.copy(wood).lerp(ring, (Math.sin(r * 18) * 0.5 + 0.5) * 0.55).lerp(bark, smoothstep(0.88, 1.0, r));
    },
  });
  return b;
}

function reeds(seed) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const g0 = C('#5d7f32'), g1 = C('#b2b864');
  for (let i = 0; i < 11; i++) {
    const a = rand() * TAU, r = rand() * 0.22;
    const x = Math.cos(a) * r, z = Math.sin(a) * r;
    const h = 0.9 + rand() * 0.7, w = 0.022 + rand() * 0.012;
    const la = rand() * TAU, lean = 0.08 + rand() * 0.2;
    const dx = Math.cos(la), dz = Math.sin(la), px = -dz, pz = dx;
    const pts = [];
    for (let s = 0; s <= 3; s++) {
      const t = s / 3;
      pts.push(V(x + dx * lean * h * t * t, h * t, z + dz * lean * h * t * t));
    }
    for (let s = 0; s < 3; s++) {
      const t0 = s / 3, t1 = (s + 1) / 3;
      const w0 = w * (1 - t0 * 0.8), w1 = w * (1 - t1 * 0.8);
      const a0 = pts[s].clone().add(V(-px * w0, 0, -pz * w0)), b0 = pts[s].clone().add(V(px * w0, 0, pz * w0));
      const a1 = pts[s + 1].clone().add(V(-px * w1, 0, -pz * w1)), b1 = pts[s + 1].clone().add(V(px * w1, 0, pz * w1));
      const n = V(dx, 0.6, dz).normalize();
      const c0 = g0.clone().lerp(g1, t0), c1 = g0.clone().lerp(g1, t1);
      b.vert(a0, n, c0, WHITE_UV, 0, 1); b.vert(b0, n, c0, WHITE_UV, 0, 1); b.vert(a1, n, c1, WHITE_UV, 0, 1);
      b.vert(a1, n, c1, WHITE_UV, 0, 1); b.vert(b0, n, c0, WHITE_UV, 0, 1); b.vert(b1, n, c1, WHITE_UV, 0, 1);
    }
  }
  // рогоз: стебель + коричневый "початок"
  for (let i = 0; i < 4; i++) {
    const a = rand() * TAU, r = rand() * 0.18;
    const x = Math.cos(a) * r, z = Math.sin(a) * r, h = 1.2 + rand() * 0.5;
    stem(b, V(x, 0, z), V(x + 0.03, h, z), 0.007, C('#6e8a3a'), 0.2);
    const g = new THREE.CapsuleGeometry(0.028, 0.13, 2, 6);
    g.translate(x + 0.03, h - 0.06, z);
    b.addGeometry(g, { uv: WHITE_UV, wind: 1, color: (p, n, c) => c.set('#6a3f22').multiplyScalar(0.8 + 0.3 * Math.max(0, n.y + 0.3)) });
    stem(b, V(x + 0.03, h, z), V(x + 0.035, h + 0.12, z), 0.003, C('#8a7a4a'), 0);
  }
  return b;
}

function lilyPad(seed, flower) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const segs = 22, notch = 0.42;
  const green = C('#46943a'), light = C('#86c457'), edge = C('#4f8a30'), rim = C('#7a5a3a');
  const vein = green.clone().lerp(light, 0.3);
  const n = V(0, 1, 0);
  // лист: светлый центр, жилки по границам секторов (светлые) и тёмная середина сектора,
  // край чуть загнут вверх и темнее (у кувшинки он красноватый)
  const ring = (a, r, y) => V(Math.cos(a) * r, y, Math.sin(a) * r);
  const ctr = V(0, 0.022, 0);
  for (let i = 0; i < segs; i++) {
    const a0 = notch / 2 + (i / segs) * (TAU - notch), a1 = notch / 2 + ((i + 1) / segs) * (TAU - notch), am = (a0 + a1) / 2;
    const wob = 1 + (rand() - 0.5) * 0.04;
    const m0 = ring(a0, 0.55, 0.02), m1 = ring(a1, 0.55, 0.02), mm = ring(am, 0.55, 0.02);
    const o0 = ring(a0, 0.93 * wob, 0.018), o1 = ring(a1, 0.93 * wob, 0.018), om = ring(am, 0.93 * wob, 0.018);
    const r0 = ring(a0, 1.0 * wob, 0.04), r1 = ring(a1, 1.0 * wob, 0.04), rm = ring(am, 1.0 * wob, 0.04);
    const mid = green.clone().multiplyScalar(0.94 + rand() * 0.08);
    const tri = (p, cp, q, cq, r, cr) => { b.vert(p, n, cp, WHITE_UV, 1, 0); b.vert(q, n, cq, WHITE_UV, 1, 0); b.vert(r, n, cr, WHITE_UV, 1, 0); };
    tri(ctr, light, m1, vein, mm, mid); tri(ctr, light, mm, mid, m0, vein);
    tri(m0, vein, mm, mid, o0, vein); tri(mm, mid, om, mid, o0, vein);
    tri(mm, mid, m1, vein, om, mid); tri(m1, vein, o1, vein, om, mid);
    tri(o0, edge, om, edge, r0, rim); tri(om, edge, rm, rim, r0, rim);
    tri(om, edge, o1, edge, rm, rim); tri(o1, edge, r1, rim, rm, rim);
  }
  if (flower) {
    const pink = C('#f29ac0'), white = C('#fdf3f6');
    const o = V((rand() - 0.5) * 0.3, 0.03, (rand() - 0.5) * 0.3);
    for (let i = 0; i < 8; i++) petal(b, o, (i / 8) * TAU, 0.42, 0.2, 0.55, pink, { tip: white, cup: -0.03, wind: 0 });
    for (let i = 0; i < 6; i++) petal(b, o.clone().add(V(0, 0.03, 0)), ((i + 0.5) / 6) * TAU, 0.3, 0.16, 0.95, pink, { tip: white, cup: -0.02, wind: 0 });
    blob(b, 0.08, 0, 9, o.clone().add(V(0, 0.08, 0)), V(1, 0.6, 1), C('#f7cf3a'), 0, 0.1);
  }
  return b;
}

const LEAF_COLORS = ['#e07a26', '#c9452a', '#ecb43c', '#a5602a', '#d8962e'].map(C);
function leafLitter(seed) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  for (let i = 0; i < 16; i++) {
    const a = rand() * TAU, r = Math.sqrt(rand()) * 0.9;
    const o = V(Math.cos(a) * r, 0.012 + rand() * 0.015, Math.sin(a) * r);
    const s = 0.06 + rand() * 0.05;
    const c = LEAF_COLORS[Math.floor(rand() * LEAF_COLORS.length)].clone().multiplyScalar(0.85 + rand() * 0.3);
    const ang = rand() * TAU;
    petal(b, o.clone().addScaledVector(V(Math.cos(ang), 0, Math.sin(ang)), -s), ang, s * 2, s * 1.1, (rand() - 0.5) * 0.3, c, { wind: 0, cup: 0.004 });
  }
  return b;
}

function pumpkin(seed) {
  const b = new PlantBuilder();
  const g = new THREE.SphereGeometry(1, 16, 9);
  const P = g.getAttribute('position');
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i);
    const a = Math.atan2(z, x);
    const rib = 0.9 + 0.1 * Math.abs(Math.cos(a * 4));
    P.setXYZ(i, x * rib, y * 0.72 + 0.62, z * rib);
  }
  g.computeVertexNormals();
  const o1 = C('#e8761c'), o2 = C('#c45a12');
  b.addGeometry(g, {
    uv: WHITE_UV,
    color: (p, n, c) => {
      const a = Math.atan2(p.z, p.x);
      c.copy(o1).lerp(o2, 1 - Math.abs(Math.cos(a * 4)) ** 0.5 * 0.9);
    },
  });
  tube(b, [V(0, 1.25, 0), V(0.05, 1.45, 0.02), V(0.15, 1.55, 0.05)], (t) => lerp(0.1, 0.06, t), {
    segs: 5, color: (t, a, i, k, o) => o.set('#5c6a2a'), uv: WHITE_UV,
  });
  petal(b, V(0, 1.2, 0), seed, 0.7, 0.5, 0.1, C('#3f7a2a'), { tip: C('#5a9a35'), wind: 0, cup: 0.05 });
  return b;
}

// Сугроб: гладкий холмик, край касается земли по касательной и уходит под неё -
// нормаль у края смотрит вверх, поэтому сугроб сливается со снегом рельефа без "блина".
function snowdrift(seed) {
  const b = new PlantBuilder();
  const RINGS = 6, SEG = 20, SINK = 0.35;
  const pos = [], idx = [];
  const outline = (a) => 1 + 0.45 * (noise3(Math.cos(a) * 1.2 + 4, Math.sin(a) * 1.2, 0.5, seed) - 0.5);
  for (let i = 0; i <= RINGS; i++) {
    const r = i / RINGS;
    for (let j = 0; j < (i ? SEG : 1); j++) {
      const a = (j / SEG) * TAU;
      const k = 1 - r * r;
      // гребень сдвинут к одной стороне - сугроб "надут" ветром
      const lump = 0.8 + 0.45 * noise3(Math.cos(a) * r * 1.6, Math.sin(a) * r * 1.6, 2.5, seed + 1) + 0.25 * Math.cos(a) * r;
      const h = (1 + SINK) * k * k * lump - SINK;
      const R = r * outline(a);
      pos.push(Math.cos(a) * R * 1.45, h, Math.sin(a) * R); // вытянут вдоль ветра
    }
  }
  const at = (i, j) => (i === 0 ? 0 : 1 + (i - 1) * SEG + (j % SEG));
  for (let j = 0; j < SEG; j++) idx.push(0, at(1, j + 1), at(1, j));
  for (let i = 1; i < RINGS; i++) {
    for (let j = 0; j < SEG; j++) {
      const a = at(i, j), bb = at(i, j + 1), c = at(i + 1, j), d = at(i + 1, j + 1);
      idx.push(a, bb, c, bb, d, c);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setIndex(idx);
  g.computeVertexNormals();
  // верх - в тон снегу рельефа (он темнее чистого белого из-за шума и голубых теней)
  const w = C('#e9eff8'), sh = C('#c2d2e8');
  b.addGeometry(g, { uv: WHITE_UV, color: (p, n, c) => c.copy(sh).lerp(w, smoothstep(0.1, 0.9, n.y)) });
  return b;
}

function sagebrush(seed) {
  const b = new PlantBuilder();
  const rand = mulberry32(seed);
  const twig = C('#6e6048');
  for (let i = 0; i < 6; i++) {
    const a = rand() * TAU;
    const top = V(Math.cos(a) * 0.2, 0.1 + rand() * 0.06, Math.sin(a) * 0.2);
    stem(b, V(0, 0, 0), top, 0.01, twig, 0.2);
  }
  // плотный серо-зелёный шар из мелких карточек, прижатый к земле, с тёмной сердцевиной
  const sage = C('#7c8a5c'), sageL = C('#aab486');
  const cc = V(0, 0.17, 0);
  blob(b, 0.2, 1, seed + 3, cc, V(1.1, 0.75, 1.1), (q, n, c) => c.copy(sage).multiplyScalar(0.62 + 0.25 * Math.max(0, n.y)), 0, 0.2);
  for (let i = 0; i < 20; i++) {
    const a = rand() * TAU, e = rand() * 1.3 - 0.25;
    const d = V(Math.cos(a) * Math.cos(e), Math.sin(e), Math.sin(a) * Math.cos(e));
    const p = cc.clone().addScaledVector(d, 0.2 + rand() * 0.08);
    p.y = Math.max(0.05, p.y);
    const s = 0.3 + rand() * 0.12;
    card(b, p, d, s, s, rand() * TAU, ATLAS.birch, {
      color: (q, c) => c.copy(sage).lerp(sageL, smoothstep(0.05, 0.45, q.y)),
      sphereN: (q) => q.clone().sub(V(0, 0.1, 0)).normalize(), tint: 0, wind: 0.5, bend: 0.03,
    });
  }
  return b;
}

// Шаблон -> компактные индексированные массивы
function finish(b, big) {
  const n = b.count;
  const key = new Map();
  const remap = new Uint32Array(n);
  const keep = [];
  for (let i = 0; i < n; i++) {
    const k = [
      Math.round(b.pos[i * 3] * 2000), Math.round(b.pos[i * 3 + 1] * 2000), Math.round(b.pos[i * 3 + 2] * 2000),
      Math.round(b.nor[i * 3] * 50), Math.round(b.nor[i * 3 + 1] * 50), Math.round(b.nor[i * 3 + 2] * 50),
      Math.round(b.col[i * 3] * 200), Math.round(b.col[i * 3 + 1] * 200), Math.round(b.col[i * 3 + 2] * 200),
      Math.round(b.uv[i * 2] * 4000), Math.round(b.uv[i * 2 + 1] * 4000), Math.round(b.wind[i] * 20), Math.round(b.tint[i]),
    ].join(',');
    let j = key.get(k);
    if (j === undefined) { j = keep.length; key.set(k, j); keep.push(i); }
    remap[i] = j;
  }
  const m = keep.length;
  const t = {
    big, nv: m, ni: n,
    pos: new Float32Array(m * 3), nor: new Float32Array(m * 3), col: new Float32Array(m * 3),
    uv: new Float32Array(m * 2), wind: new Float32Array(m), tint: new Float32Array(m), index: remap,
  };
  keep.forEach((i, j) => {
    for (let c = 0; c < 3; c++) {
      t.pos[j * 3 + c] = b.pos[i * 3 + c];
      t.nor[j * 3 + c] = b.nor[i * 3 + c];
      t.col[j * 3 + c] = b.col[i * 3 + c];
    }
    // вырожденные грани дают нулевую нормаль -> NaN в освещении (чёрные пятна)
    const nl = Math.hypot(t.nor[j * 3], t.nor[j * 3 + 1], t.nor[j * 3 + 2]);
    if (!(nl > 1e-4)) { t.nor[j * 3] = 0; t.nor[j * 3 + 1] = 1; t.nor[j * 3 + 2] = 0; }
    t.uv[j * 2] = b.uv[i * 2]; t.uv[j * 2 + 1] = b.uv[i * 2 + 1];
    t.wind[j] = b.wind[i];
    t.tint[j] = b.tint[i];
  });
  return t;
}

function buildTemplates(lod) {
  const T = {};
  const variants = (name, n, fn, big = false) => {
    T[name] = [];
    for (let i = 0; i < n; i++) T[name].push(finish(fn(i), big));
  };
  variants('daisy', 3, (i) => flowerCluster('daisy', 100 + i));
  variants('poppy', 3, (i) => flowerCluster('poppy', 200 + i));
  variants('bluebell', 2, (i) => flowerCluster('bluebell', 300 + i));
  variants('dandelion', 2, (i) => flowerCluster('dandelion', 400 + i));
  variants('clover', 2, (i) => { const b = new PlantBuilder(); cloverPatch(b, mulberry32(500 + i)); return b; });
  variants('fern', 2, (i) => fern(600 + i, lod), true);
  variants('agaric', 2, (i) => mushroomCluster(700 + i, true));
  variants('bolete', 2, (i) => mushroomCluster(750 + i, false));
  variants('pebbles', 3, (i) => pebbles(800 + i));
  variants('stone', 2, (i) => stone(850 + i), true);
  variants('stoneMoss', 2, (i) => stone(860 + i, false, true), true);
  variants('stoneSnow', 2, (i) => stone(870 + i, true), true);
  variants('log', 1, (i) => logGeo(900 + i), true);
  variants('stump', 1, (i) => stumpGeo(950 + i), true);
  variants('reeds', 2, (i) => reeds(1000 + i));
  variants('lily', 2, (i) => lilyPad(1100 + i, false));
  variants('lilyFlower', 2, (i) => lilyPad(1150 + i, true));
  variants('leaves', 3, (i) => leafLitter(1200 + i));
  variants('pumpkin', 1, (i) => pumpkin(1300 + i), true);
  variants('drift', 2, (i) => snowdrift(1400 + i), true);
  variants('sage', 2, (i) => sagebrush(1500 + i), true);
  return T;
}

// ---------- раскладка ----------

// Пирс (повторяет расчёт в structures.js), чтобы кувшинки и камыш его не протыкали.
function pierSegment() {
  const dir = new THREE.Vector2(-20 - LAKE.x, 20 - LAKE.z).normalize();
  let shore = LAKE.r + 14;
  for (let r = LAKE.r + 14; r > 0; r -= 0.5) {
    if (heightAt(LAKE.x + dir.x * r, LAKE.z + dir.y * r) < WATER_Y + 0.1) { shore = r; break; }
  }
  const a = shore + 2, b = shore + 1.5 - 11;
  return [LAKE.x + dir.x * a, LAKE.z + dir.y * a, LAKE.x + dir.x * b, LAKE.z + dir.y * b];
}

function segDist(px, pz, [ax, az, bx, bz]) {
  const vx = bx - ax, vz = bz - az;
  const t = Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / (vx * vx + vz * vz)));
  return Math.hypot(px - ax - vx * t, pz - az - vz * t);
}

export class Decor {
  constructor(scene, {
    radius = 60, density = 1, lod = 1, collisions = null, exclusions = [], trees = [], shadows = true,
  } = {}) {
    this.scene = scene;
    this.radius = radius;
    this.density = density;
    this.exclusions = exclusions;
    this.shadows = shadows;
    this.T = buildTemplates(lod);
    this.fade = { value: new THREE.Vector2(radius * 0.62, radius) };
    this.mat = decorMaterial(this.fade);
    this.active = new Map();
    this.group = new THREE.Group();
    this.group.name = 'decor';
    scene.add(this.group);
    this.pier = pierSegment();
    this._w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
    this._m = new THREE.Matrix4();
    this._q = new THREE.Quaternion();
    this._e = new THREE.Euler();
    this._p = new THREE.Vector3();
    this._s = new THREE.Vector3();
    this._nm = new THREE.Matrix3();
    this.logs = this._scatterLogs(trees, collisions);
  }

  _blocked(x, z, pad = 0) {
    for (const s of SITES) {
      const r = s.id === 'hub' ? 11 : s.r * 0.95;
      if ((x - s.x) ** 2 + (z - s.z) ** 2 < (r + pad) ** 2) return true;
    }
    for (const ex of this.exclusions) {
      if ((x - ex.x) ** 2 + (z - ex.z) ** 2 < (ex.r + pad) ** 2) return true;
    }
    return false;
  }

  // Брёвна и пни раскладываются на весь мир сразу: им нужны коллизии.
  _scatterLogs(trees, collisions) {
    const rand = mulberry32(4242);
    const out = [];
    const w = this._w;
    const grid = new Map();
    for (const t of trees) {
      const k = `${Math.floor(t.x / 4)},${Math.floor(t.z / 4)}`;
      if (!grid.has(k)) grid.set(k, []);
      grid.get(k).push(t);
    }
    const nearTree = (x, z, r) => {
      for (let dz = -1; dz <= 1; dz++) {
        for (let dx = -1; dx <= 1; dx++) {
          const arr = grid.get(`${Math.floor(x / 4) + dx},${Math.floor(z / 4) + dz}`);
          if (arr) for (const t of arr) if ((t.x - x) ** 2 + (t.z - z) ** 2 < r * r) return true;
        }
      }
      return false;
    };
    const STEP = 6;
    for (let gz = -WORLD_RADIUS; gz < WORLD_RADIUS; gz += STEP) {
      for (let gx = -WORLD_RADIUS; gx < WORLD_RADIUS; gx += STEP) {
        const x = gx + rand() * STEP, z = gz + rand() * STEP;
        const r0 = rand(), r1 = rand(), r2 = rand(), r3 = rand(), r4 = rand();
        if (x * x + z * z > (WORLD_RADIUS - 10) ** 2) continue;
        const forest = forestDensity(x, z);
        if (forest < 0.45) continue;
        zoneWeights(x, z, w);
        if (w.canyon > 0.4) continue;
        const y = heightAt(x, z);
        if ((y < WATER_Y + 0.5 && inLake(x, z)) || pathDist(x, z) < 3 || slopeAt(x, z) > 0.4 || this._blocked(x, z, 3)) continue;
        if (r0 < 0.1 * forest) {
          // пень
          if (nearTree(x, z, 1.6)) continue;
          const r = 0.26 + r1 * 0.2, h = 0.3 + r2 * 0.35;
          out.push({ t: 'stump', x, y: y - 0.05, z, ry: r3 * TAU, sx: r, sy: h, sz: r, rank: r4, snow: w.winter });
          collisions?.addCircle(x, z, r * 1.1, y - 0.5, y + h);
        } else if (r0 < 0.17 * forest) {
          // упавшее бревно: лежит по склону
          const len = 2.6 + r1 * 2.2, rad = 0.2 + r2 * 0.13, ry = r3 * TAU;
          const cx = Math.cos(ry), sz = -Math.sin(ry);
          const ax = x - cx * len / 2, az = z - sz * len / 2, bx = x + cx * len / 2, bz = z + sz * len / 2;
          if (nearTree(x, z, 1.5) || nearTree(ax, az, 1.2) || nearTree(bx, bz, 1.2)) continue;
          if (pathDist(ax, az) < 2.5 || pathDist(bx, bz) < 2.5 || this._blocked(ax, az) || this._blocked(bx, bz)) continue;
          const ha = heightAt(ax, az), hb = heightAt(bx, bz);
          const pitch = Math.atan2(hb - ha, len);
          const ly = (ha + hb) / 2 + rad * 0.75;
          out.push({ t: 'log', x, y: ly, z, ry, rz: pitch, sx: len, sy: rad, sz: rad, rank: r4, snow: w.winter });
          collisions?.addBox(x, z, len / 2, rad * 0.95, ry, Math.min(ha, hb) - 0.3, ly + rad * 0.85);
        }
      }
    }
    return out;
  }

  // Предметы чанка (детерминированно от координат чанка).
  _items(cx, cz) {
    const x0 = cx * CH, z0 = cz * CH;
    const items = [];
    if (isInterior(x0)) return items;
    const rand = mulberry32(Math.floor(hash2(cx, cz, 91) * 4294967296));
    const w = this._w, D = this.density;
    const T = this.T;
    const add = (t, x, y, z, s, o = {}) => {
      const vars = T[t];
      items.push({ t, v: vars[Math.floor(rand() * vars.length)], x, y, z, ry: rand() * TAU, sx: s, sy: s * (o.sy || 1), sz: s, rank: rand(), rx: o.rx || 0, rz: o.rz || 0, tint: o.tint || null });
    };
    const env = (x, z) => {
      zoneWeights(x, z, w);
      return w;
    };
    // зоны в центре чанка: если зоны тут нет совсем, её предметы не перебираем
    const wc = { ...zoneWeights(x0 + CH / 2, z0 + CH / 2, w) };
    const lakeNear = Math.hypot(x0 + CH / 2 - LAKE.x, z0 + CH / 2 - LAKE.z) < LAKE.r * 1.7 + CH * 0.72;

    // цветы пятнами
    const nFlower = Math.round(330 * D);
    for (let i = 0; i < nFlower; i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      const r1 = rand(), r2 = rand();
      const patch = flowerPatch(x, z);
      if (patch < 0.16) continue;
      const y = heightAt(x, z);
      if (y < WATER_Y + 0.4 && inLake(x, z)) continue;
      env(x, z);
      const pref = w.meadow + w.rain * 0.35 + w.autumn * 0.25;
      if (r1 > pref * smoothstep(0.16, 0.42, patch)) continue;
      if (pathDist(x, z) < 1.7 || this._blocked(x, z)) continue;
      const forest = forestDensity(x, z);
      if (r2 < forest * 0.8 || slopeAt(x, z) > 0.6) continue;
      const sp = speciesNoise.fbm(x * 0.04, z * 0.04, 2) * 0.5 + 0.5;
      let kind;
      if (w.autumn > 0.5) kind = sp < 0.5 ? 'dandelion' : 'poppy';
      else if (w.rain > 0.5) kind = sp < 0.5 ? 'bluebell' : 'daisy';
      else kind = sp < 0.24 ? 'daisy' : sp < 0.42 ? 'poppy' : sp < 0.6 ? 'bluebell' : sp < 0.78 ? 'dandelion' : 'daisy';
      add(kind, x, y - 0.01, z, 0.95 + rand() * 0.45);
    }
    // клевер ковриками
    for (let i = 0; i < Math.round(70 * D); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      if (speciesNoise.get(x * 0.11 + 9, z * 0.11) < 0.25) continue;
      const y = heightAt(x, z);
      if ((y < WATER_Y + 0.4 && inLake(x, z)) || env(x, z).meadow + w.rain < 0.5) continue;
      if (pathDist(x, z) < 1.5 || this._blocked(x, z) || forestDensity(x, z) > 0.6) continue;
      add('clover', x, y, z, 0.9 + rand() * 0.5);
    }
    // папоротники в тени леса, грибы
    for (let i = 0; i < Math.round(130 * D); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      const forest = forestDensity(x, z);
      const r1 = rand();
      if (r1 > forest * 0.85) continue;
      env(x, z);
      if (w.winter > 0.45 || w.canyon > 0.4) continue;
      const y = heightAt(x, z);
      if ((y < WATER_Y + 0.5 && inLake(x, z)) || pathDist(x, z) < 2 || this._blocked(x, z) || slopeAt(x, z) > 0.7) continue;
      if (rand() < 0.22 + w.autumn * 0.25) {
        add(rand() < 0.45 ? 'agaric' : 'bolete', x, y - 0.01, z, 0.9 + rand() * 0.6);
      } else {
        add('fern', x, y - 0.02, z, 0.8 + rand() * 0.6, { sy: 0.8 + rand() * 0.4 });
      }
    }
    // галька вдоль троп и берега, отдельные камни
    for (let i = 0; i < Math.round(110 * D); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      const y = heightAt(x, z);
      const pd = pathDist(x, z);
      const shore = lakeNear && y > WATER_Y - 0.3 && y < WATER_Y + 0.8 && inLake(x, z);
      const edge = pd > 1.0 && pd < 2.8;
      const r1 = rand();
      if (!(shore && r1 < 0.7) && !(edge && r1 < 0.45) && r1 > 0.02) continue;
      if (this._blocked(x, z, -2)) continue;
      env(x, z);
      if (w.winter > 0.6) continue;
      add('pebbles', x, y, z, 0.8 + rand() * 0.8, { tint: w.canyon > 0.4 ? C('#e0a070') : null });
    }
    for (let i = 0; i < Math.round(12 * D); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      if (rand() > 0.5) continue;
      const y = heightAt(x, z);
      if ((y < WATER_Y + 0.1 && inLake(x, z)) || pathDist(x, z) < 2.2 || this._blocked(x, z)) continue;
      env(x, z);
      const s = 0.2 + rand() * 0.35;
      if (w.winter > 0.55) add('stoneSnow', x, y - s * 0.1, z, s * 1.4);
      else if (w.canyon > 0.4) add('stone', x, y - s * 0.1, z, s * 1.6, { tint: C('#e39a62') });
      else add(forestDensity(x, z) > 0.4 ? 'stoneMoss' : 'stone', x, y - s * 0.1, z, s);
    }
    // берег озера: камыш и кувшинки
    if (lakeNear) {
      for (let i = 0; i < Math.round(240 * D); i++) {
        const x = x0 + rand() * CH, z = z0 + rand() * CH;
        const y = heightAt(x, z);
        const ln = lilyNoise.fbm(x * 0.09, z * 0.09, 2);
        const r1 = rand();
        if (segDist(x, z, this.pier) < 2.6 || this._blocked(x, z)) continue; // WS5b: и вне кругов excludeNature (лодка у пирса)
        if (inLake(x, z) && y > WATER_Y - 0.5 && y < WATER_Y + 0.15) {
          if (ln < -0.05 || r1 > 0.55) continue;
          add('reeds', x, y - 0.05, z, 0.8 + rand() * 0.5);
        }
      }
      // кувшинки - отдельной выборкой: колониями в неглубокой воде
      for (let i = 0; i < Math.round(420 * D); i++) {
        const x = x0 + rand() * CH, z = z0 + rand() * CH;
        const r1 = rand(), r2 = rand(), r3 = rand();
        const y = heightAt(x, z);
        if (!(y < WATER_Y - 0.45 && y > WATER_Y - 2.6) || !inLake(x, z)) continue;
        if (lilyNoise.fbm(x * 0.07 + 5, z * 0.07, 2) < -0.02 || r1 > 0.7) continue;
        if (segDist(x, z, this.pier) < 2.4 || this._blocked(x, z, 0.5)) continue; // WS5b: кувшинки не прорастают сквозь лодку
        add(r2 < 0.22 ? 'lilyFlower' : 'lily', x, WATER_Y + 0.02, z, 0.36 + r3 * 0.3, { sy: 1 });
      }
    }
    // осень: ковёр листьев и тыквы
    for (let i = 0; i < Math.round(150 * D); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      const r1 = rand();
      env(x, z);
      if (wc.autumn < 0.12) break;
      if (w.autumn < 0.3) continue;
      const forest = forestDensity(x, z);
      if (r1 > w.autumn * (0.25 + forest * 0.75)) continue;
      const y = heightAt(x, z);
      if ((y < WATER_Y + 0.3 && inLake(x, z)) || this._blocked(x, z, -3)) continue;
      add('leaves', x, y, z, 0.9 + rand() * 0.6);
    }
    for (let i = 0; i < Math.round(30 * D); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      env(x, z);
      if (wc.autumn < 0.3) break;
      if (w.autumn < 0.55) continue;
      if (speciesNoise.get(x * 0.05 + 40, z * 0.05) < 0.35 || forestDensity(x, z) > 0.3) continue;
      const y = heightAt(x, z);
      if (pathDist(x, z) < 2 || this._blocked(x, z) || slopeAt(x, z) > 0.4) continue;
      add('pumpkin', x, y - 0.05, z, 0.22 + rand() * 0.18);
    }
    // зима: сугробы
    for (let i = 0; i < Math.round(26 * D); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      env(x, z);
      if (wc.winter < 0.35) break;
      if (w.winter < 0.6) continue;
      if (rand() > 0.6) continue;
      const y = heightAt(x, z);
      if (pathDist(x, z) < 2.4 || this._blocked(x, z) || slopeAt(x, z) > 0.6) continue;
      const s = 0.9 + rand() * 1.3;
      add('drift', x, y - 0.04, z, s, { sy: 0.2 + rand() * 0.14 });
    }
    // каньон: полынь
    for (let i = 0; i < Math.round(60 * D); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      env(x, z);
      if (wc.canyon < 0.15) break;
      if (w.canyon < 0.4) continue;
      if (rand() > 0.5) continue;
      const y = heightAt(x, z);
      if ((y < WATER_Y + 0.5 && inLake(x, z)) || pathDist(x, z) < 2 || this._blocked(x, z) || slopeAt(x, z) > 0.5) continue;
      add('sage', x, y - 0.03, z, 0.8 + rand() * 0.7);
    }
    // брёвна и пни из общего списка
    for (const l of this.logs) {
      if (l.x < x0 || l.x >= x0 + CH || l.z < z0 || l.z >= z0 + CH) continue;
      items.push({ ...l, v: T[l.t][0] });
    }
    return items;
  }

  _build(cx, cz) {
    const items = this._items(cx, cz);
    const meshes = [];
    for (const big of [false, true]) {
      const list = items.filter((it) => it.v.big === big);
      if (!list.length) continue;
      let nv = 0, ni = 0;
      for (const it of list) { nv += it.v.nv; ni += it.v.ni; }
      const pos = new Float32Array(nv * 3), nor = new Int8Array(nv * 3), col = new Uint8Array(nv * 3);
      const uv = new Uint16Array(nv * 2), wind = new Uint8Array(nv), anc = new Float32Array(nv * 4);
      const index = nv > 65535 ? new Uint32Array(ni) : new Uint16Array(ni);
      let vo = 0, io = 0;
      const m = this._m, q = this._q, nm = this._nm;
      for (const it of list) {
        const t = it.v;
        q.setFromEuler(this._e.set(it.rx || 0, it.ry, it.rz || 0, 'YXZ'));
        m.compose(this._p.set(it.x, it.y, it.z), q, this._s.set(it.sx, it.sy, it.sz));
        nm.getNormalMatrix(m);
        const e = m.elements, ne = nm.elements;
        const tint = it.tint;
        const snow = it.snow > 0.55;
        // сугроб "накинут" на рельеф: высота каждой вершины - над землёй под ней (на склоне край не висит)
        const drape = it.t === 'drift';
        for (let k = 0; k < t.nv; k++) {
          const px = t.pos[k * 3], py = t.pos[k * 3 + 1], pz = t.pos[k * 3 + 2];
          const o = (vo + k) * 3;
          pos[o] = e[0] * px + e[4] * py + e[8] * pz + e[12];
          pos[o + 1] = e[1] * px + e[5] * py + e[9] * pz + e[13];
          pos[o + 2] = e[2] * px + e[6] * py + e[10] * pz + e[14];
          if (drape) pos[o + 1] += heightAt(pos[o], pos[o + 2]) - e[13] - 0.03;
          const nx = t.nor[k * 3], ny = t.nor[k * 3 + 1], nz = t.nor[k * 3 + 2];
          let wx = ne[0] * nx + ne[3] * ny + ne[6] * nz, wy = ne[1] * nx + ne[4] * ny + ne[7] * nz, wz = ne[2] * nx + ne[5] * ny + ne[8] * nz;
          const L = Math.hypot(wx, wy, wz);
          if (L > 1e-6) { wx /= L; wy /= L; wz /= L; } else { wx = 0; wy = 1; wz = 0; }
          nor[o] = wx * 127; nor[o + 1] = wy * 127; nor[o + 2] = wz * 127;
          let r = t.col[k * 3], g = t.col[k * 3 + 1], bb = t.col[k * 3 + 2];
          if (tint) { r *= tint.r * 1.25; g *= tint.g * 1.25; bb *= tint.b * 1.25; }
          if (snow && wy > 0.4) { const s = Math.min(1, (wy - 0.4) * 2.5); r += (0.93 - r) * s; g += (0.95 - g) * s; bb += (0.98 - bb) * s; }
          col[o] = Math.min(255, r * 255); col[o + 1] = Math.min(255, g * 255); col[o + 2] = Math.min(255, bb * 255);
          uv[(vo + k) * 2] = t.uv[k * 2] * 65535; uv[(vo + k) * 2 + 1] = t.uv[k * 2 + 1] * 65535;
          wind[vo + k] = t.wind[k] * 255;
          const ao = (vo + k) * 4;
          anc[ao] = it.x; anc[ao + 1] = it.y; anc[ao + 2] = it.z; anc[ao + 3] = it.rank;
        }
        for (let k = 0; k < t.ni; k++) index[io + k] = t.index[k] + vo;
        vo += t.nv; io += t.ni;
      }
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
      g.setAttribute('normal', new THREE.BufferAttribute(nor, 3, true));
      g.setAttribute('color', new THREE.BufferAttribute(col, 3, true));
      g.setAttribute('uv', new THREE.BufferAttribute(uv, 2, true));
      g.setAttribute('aWind', new THREE.BufferAttribute(wind, 1, true));
      g.setAttribute('aAnchor', new THREE.BufferAttribute(anc, 4));
      g.setIndex(new THREE.BufferAttribute(index, 1));
      g.computeBoundingSphere();
      g.boundingSphere.radius += 1;
      const mesh = new THREE.Mesh(g, this.mat);
      mesh.castShadow = big && this.shadows;
      if (mesh.castShadow) mesh.customDepthMaterial = this.mat.userData.depth;
      mesh.receiveShadow = true;
      mesh.matrixAutoUpdate = false;
      mesh.name = big ? 'decor-big' : 'decor-small';
      meshes.push(mesh);
    }
    return { meshes, cx, cz };
  }

  _release(key) {
    const e = this.active.get(key);
    if (!e) return;
    for (const m of e.meshes) { this.group.remove(m); m.geometry.dispose(); }
    this.active.delete(key);
    this._full = false;
  }

  // Строит не больше `budget` недостающих чанков за вызов; без выделений памяти в обычном кадре.
  update(px, pz, budget = 1) {
    const maxD = this.radius + 2;
    for (const e of this.active.values()) {
      const nx = Math.max(e.cx * CH - px, 0, px - (e.cx + 1) * CH), nz = Math.max(e.cz * CH - pz, 0, pz - (e.cz + 1) * CH);
      if (Math.hypot(nx, nz) > maxD + 8) this._release(e.key);
    }
    const cx = Math.floor(px / CH), cz = Math.floor(pz / CH);
    if (this._full && Math.abs(px - this._fpx) + Math.abs(pz - this._fpz) < 1) return 0;
    const R = Math.ceil(maxD / CH) + 1;
    let missing = 0;
    for (;;) {
      missing = 0;
      let bd = Infinity, bx = 0, bz = 0;
      for (let dz = -R; dz <= R; dz++) {
        for (let dx = -R; dx <= R; dx++) {
          const ix = cx + dx, iz = cz + dz;
          if (this.active.has(chunkKey(ix, iz))) continue;
          const nx = Math.max(ix * CH - px, 0, px - (ix + 1) * CH), nz = Math.max(iz * CH - pz, 0, pz - (iz + 1) * CH);
          const d = Math.hypot(nx, nz);
          if (d > maxD) continue;
          missing++;
          if (d < bd) { bd = d; bx = ix; bz = iz; }
        }
      }
      if (!missing || budget <= 0) break;
      const e = this._build(bx, bz);
      e.key = chunkKey(bx, bz);
      for (const m of e.meshes) this.group.add(m);
      this.active.set(e.key, e);
      budget--;
    }
    this._full = missing === 0;
    this._fpx = px; this._fpz = pz;
    return missing;
  }

  prefill(px, pz) {
    let guard = 100;
    while (this.update(px, pz, 4) > 0 && guard-- > 0);
  }

  invalidate(x, z, r) {
    for (const e of this.active.values()) {
      const x0 = e.cx * CH, z0 = e.cz * CH;
      if (x + r > x0 && x - r < x0 + CH && z + r > z0 && z - r < z0 + CH) this._release(e.key);
    }
  }
}
