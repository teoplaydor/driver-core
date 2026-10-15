// Геометрия персонажа как в референсе: «толстый папа-турист». Голова-груша (шире внизу, без шеи) лежит прямо
// на воротнике, лицо минималистичное (маленькие тёмные глаза, мягкий бугорок носа, короткая улыбка);
// тело-груша с животом вперёд и вниз, белая рубашка с закатанным рукавом и расстёгнутым воротом, жилет
// нараспашку, брюки с заплатками на коленях, небольшие округлые ботинки, причёски, шляпы, очки.
// Всё собирается в две skinned-геометрии: матовое тело и маленькая глянцевая (стёкла очков, пряжка).

import * as THREE from 'three';
import { Sculpt, C, V, tint, mix, sstep, sphereDir } from './sculpt.js';
import {
  B, HEAD_C, headRadius, headPoint, FACE, eyeCenter, armDir, palmDir, SHOULDER, UPPER, FORE, HIP_X, HIP_Y, THIGH, KNUCKLE,
} from './rig.js';

// ---------- палитры и варианты внешности ----------
export const SKINS = ['#f0ad8a', '#f6c0a0', '#e19a74', '#c98058', '#a0623f', '#76482f'];
export const HAIRS = ['#5a3a24', '#3b2a1d', '#7a5536', '#a87a48', '#2a2226', '#b8b0a6'];
// Один крой (рубашка + жилет нараспашку + брюки), разные цвета — как в референсе.
export const OUTFITS = [
  { name: 'Исследователь', shirt: '#f4f1ea', vest: '#6b2c1f', pants: '#d6b77c', shoes: '#4a2f1e', patch: '#5e3a22' },
  { name: 'Лесник', shirt: '#efe7d2', vest: '#56653a', pants: '#7d7a4e', shoes: '#3e2a1b', patch: '#4b4a2c' },
  { name: 'Рыбак', shirt: '#d7e6f0', vest: '#2f3d58', pants: '#8b8a84', shoes: '#33261d', patch: '#5d5c58' },
  { name: 'Ковбой', shirt: '#f3e8b8', vest: '#5a3822', pants: '#7c5838', shoes: '#3b2416', patch: '#4e331e' },
  { name: 'Мятный', shirt: '#e3f0e6', vest: '#2f5648', pants: '#b9b08a', shoes: '#3e2c1e', patch: '#7d7558' },
  { name: 'Клубничный', shirt: '#f6f0ee', vest: '#7b2230', pants: '#8f8576', shoes: '#3a2620', patch: '#5f564b' },
  { name: 'Угольный', shirt: '#f2f2f0', vest: '#26262b', pants: '#6c6c6a', shoes: '#262222', patch: '#454543' },
  { name: 'Закат', shirt: '#f5ecd6', vest: '#b5842c', pants: '#6e4b2e', shoes: '#3b2a1c', patch: '#4a3220' },
];
export const HATS = ['Без шляпы', 'Сафари', 'Ковбойская', 'Каска', 'Кепка', 'Шапка'];
export const GLASSES = ['Без очков', 'Чёрные круглые', 'Квадратные', 'Авиаторы'];
export const HAIR_STYLES = ['Короткая', 'Зачёс', 'Ёжик', 'Лысина'];
export const FACIAL = ['Без усов', 'Усики', 'Щетина'];

// цвет рукава для рук от первого лица
export function sleeveColor(look) {
  return (OUTFITS[look.outfit] || OUTFITS[0]).shirt;
}

// ---------- вспомогательное ----------
const TAU = Math.PI * 2;
const _d = new THREE.Vector3();
const _t = new THREE.Vector3();

function hash3(x, y, z) {
  const s = Math.sin(x * 127.1 + y * 311.7 + z * 74.7) * 43758.5453;
  return s - Math.floor(s);
}

// Тело: профиль торса по высоте (y, полуширина, глубина спереди, глубина сзади).
const TORSO = [
  [0.22, 0.0, 0.0, 0.0],
  [0.232, 0.14, 0.13, 0.12],
  [0.262, 0.24, 0.24, 0.21],
  [0.315, 0.3, 0.318, 0.245],
  [0.39, 0.326, 0.34, 0.26],
  [0.48, 0.326, 0.33, 0.262],
  [0.56, 0.31, 0.298, 0.255],
  [0.64, 0.283, 0.255, 0.24],
  [0.71, 0.245, 0.212, 0.218],
  [0.765, 0.198, 0.174, 0.184],
  [0.805, 0.15, 0.135, 0.14],
  [0.835, 0.085, 0.08, 0.08],
  [0.848, 0.0, 0.0, 0.0],
];
const cr = (p0, p1, p2, p3, t) => 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
const _row = [0, 0, 0, 0];
function torsoRow(v) {
  const n = TORSO.length - 1;
  const s = Math.min(n - 1e-6, Math.max(0, v * n));
  const i = Math.floor(s), t = s - i;
  const r0 = TORSO[Math.max(0, i - 1)], r1 = TORSO[i], r2 = TORSO[i + 1], r3 = TORSO[Math.min(n, i + 2)];
  for (let k = 0; k < 4; k++) _row[k] = Math.max(0, cr(r0[k], r1[k], r2[k], r3[k], t));
  return _row;
}
// таблица y(v) для обратного поиска
const YV = [];
for (let i = 0; i <= 256; i++) YV.push(torsoRow(i / 256)[0]);
function vAtY(y) {
  for (let i = 0; i < 256; i++) {
    if (YV[i + 1] >= y) return (i + (y - YV[i]) / Math.max(1e-6, YV[i + 1] - YV[i])) / 256;
  }
  return 1;
}
// Точка торса: a — угол вокруг вертикали (0 — перед), v — по высоте, off — отступ наружу.
function torsoPt(a, v, off, out) {
  const r = torsoRow(v);
  const sa = Math.sin(a), ca = Math.cos(a);
  const rz = r[3] + (r[2] - r[3]) * sstep(-0.35, 0.35, ca);
  const rad = r[1] < 1e-5 ? 0 : 1 / Math.sqrt((sa / r[1]) ** 2 + (ca / Math.max(1e-5, rz)) ** 2);
  return out.set((rad + off) * sa, r[0], -(rad + off) * ca);
}
const torsoAt = (a, y, off, out = new THREE.Vector3()) => torsoPt(a, vAtY(y), off, out);
function torsoNormal(a, y, out = new THREE.Vector3()) {
  const p0 = torsoAt(a, y, 0, new THREE.Vector3());
  const pa = torsoAt(a + 0.01, y, 0, new THREE.Vector3()).sub(p0);
  const py = torsoAt(a, y + 0.005, 0, new THREE.Vector3()).sub(p0);
  return out.crossVectors(py, pa).normalize();
}

// Веса торса: таз → поясница → грудь плавно, плюс «живот» спереди снизу.
function torsoWeights(x, y, z) {
  const a = Math.atan2(x, -z);
  const ca = Math.cos(a);
  const wb = 0.75 * Math.max(0, ca) ** 1.5 * Math.exp(-(((y - 0.47) / 0.1) ** 2));
  const s1 = sstep(0.4, 0.5, y), s2 = sstep(0.58, 0.68, y);
  const wp = 1 - s1, wc = s2, ws = s1 - s2;
  return [B.pelvis, wp * (1 - wb), B.spine, ws * (1 - wb), B.chest, wc * (1 - wb), B.belly, wb];
}

// Пятно на поверхности торса (карманы, клапаны): центр (a, y), размеры в метрах, рельеф bump.
function torsoPatch(S, ac, yc, w, h, off0, off1, nu, nv, color, round = 0.3) {
  const r = Math.hypot(torsoAt(ac, yc, 0, _t).x, _t.z) || 0.3;
  const da = w / r;
  S.grid((u, v, out) => {
    const k = Math.sin(Math.PI * u) ** round * Math.sin(Math.PI * v) ** round;
    torsoAt(ac + (u - 0.5) * da, yc + (v - 0.5) * h, off0 + (off1 - off0) * k, out);
  }, nu, nv, { wrap: false }, color, torsoWeights);
}

// ---------- построение ----------

export function buildCharacter(look, { lod = false } = {}) {
  const o = OUTFITS[look.outfit] || OUTFITS[0];
  const skin = C(SKINS[look.skin] || SKINS[0]);
  const hair = C(HAIRS[look.hair] || HAIRS[0]);
  const shirt = C(o.shirt), outer = C(o.vest), trim = tint(o.vest, 0.78), pants = C(o.pants), shoes = C(o.shoes);
  const S = new Sculpt(2, lod);
  const ctx = { look, o, skin, hair, shirt, outer, trim, pants, shoes, S, top: 1.3 };

  S.slot = 0;
  buildTorso(ctx);
  buildArms(ctx);
  buildLegs(ctx);
  buildHead(ctx);
  buildHair(ctx);
  buildHat(ctx);
  buildGlasses(ctx);

  return { body: S.build(0), gloss: S.build(1), top: ctx.top };
}

// ---------- торс и одежда ----------

const WAIST = 0.345; // брюки начинаются под животом

function buildTorso(ctx) {
  const { S, shirt, pants } = ctx;
  S.grid((u, v, out) => torsoPt(u * TAU, v, 0, out), 40, 26, { poles: true }, (x, y, z, out) => {
    out.copy(pants).lerp(shirt, sstep(WAIST - 0.01, WAIST + 0.01, y));
    out.multiplyScalar(0.8 + 0.2 * sstep(0.26, 0.34, y));
    // мягкие складки: под грудью, у подмышек, над поясом (живот нависает)
    const a = Math.atan2(x, -z);
    out.multiplyScalar(1 - 0.07 * Math.exp(-(((y - 0.69) / 0.05) ** 2)) * sstep(0.17, 0.26, Math.abs(x)));
    out.multiplyScalar(1 - 0.1 * Math.exp(-(((y - WAIST - 0.03) / 0.03) ** 2)) * Math.max(0, Math.cos(a)));
    out.multiplyScalar(1 - 0.04 * Math.max(0, Math.sin(a * 7 + y * 40)) * sstep(0.45, 0.6, y) * Math.max(0, Math.cos(a)));
  }, torsoWeights);
  // шея (видна, только если голова сильно наклонена)
  S.tube([V(0, 0.76, 0.01), V(0, 0.84, 0.01), V(0, 0.92, 0.0)], [0.1, 0.098, 0.095], { radial: 10 }, tint(ctx.skin, 0.9),
    (x, y) => [B.chest, 1 - sstep(0.78, 0.86, y), B.neck, sstep(0.78, 0.86, y) * (1 - sstep(0.86, 0.9, y)), B.head, sstep(0.86, 0.9, y)]);
  // планка рубашки и пара пуговиц (верх расстёгнут)
  const placket = [];
  for (let y = WAIST + 0.04; y <= 0.72; y += 0.03) placket.push(torsoAt(0, y, 0.003));
  S.tube(placket, 0.0062, { radial: 5, ref: V(0, 0, 1) }, tint(shirt, 0.92), torsoWeights);
  for (const y of [0.46, 0.55, 0.64]) S.disc(torsoAt(0, y, 0.008), torsoNormal(0, y), 0.0105, 0.005, tint(shirt, 0.84), torsoWeights, 10);
  buildBelt(ctx);
  buildVest(ctx);
  buildCollar(ctx);
}

function buildBelt(ctx) {
  const { S, pants } = ctx;
  const leather = C('#3e2818');
  const y0 = WAIST - 0.018, y1 = WAIST + 0.016;
  S.grid((u, v, out) => torsoAt(u * TAU, y0 + (y1 - y0) * v, 0.008, out), 44, 2, { wrap: true }, leather, torsoWeights);
  for (const y of [y0, y1]) {
    const ring = [];
    for (let i = 0; i < 44; i++) ring.push(torsoAt((i / 44) * TAU, y, 0.008));
    S.tube(ring, 0.0035, { radial: 3, closed: true, ref: V(0, 1, 0) }, tint(leather, 0.75), torsoWeights);
  }
  for (const a of [-0.8, 0.8, 2.3, -2.3]) {
    S.tube([torsoAt(a, y1 + 0.006, 0.011), torsoAt(a, y0 - 0.006, 0.011)], 0.007, { radial: 5, squash: [0.45, 1] }, tint(pants, 0.9), torsoWeights);
  }
  // небольшая потёртая пряжка
  S.slot = 1;
  const c = torsoAt(0, WAIST, 0.015);
  const n = torsoNormal(0, WAIST);
  const right = V(1, 0, 0), up = V().crossVectors(n, right).normalize().negate();
  const rect = [];
  const hw = 0.022, hh = 0.017, rr = 0.006;
  for (let i = 0; i < 24; i++) {
    const q = Math.floor(i / 6), f = (i % 6) / 6;
    const ang = q * Math.PI / 2 + f * Math.PI / 2;
    const cx = (q === 0 || q === 3 ? 1 : -1) * (hw - rr), cy = (q < 2 ? 1 : -1) * (hh - rr);
    rect.push(c.clone().addScaledVector(right, cx + Math.cos(ang) * rr).addScaledVector(up, cy + Math.sin(ang) * rr));
  }
  S.tube(rect, 0.0045, { radial: 5, closed: true }, C('#a8894e'), torsoWeights);
  S.slot = 0;
}

// Жилет нараспашку: по центру видна рубашка на животе; мягкая окантовка, неброские карманы, шов на спине.
function buildVest(ctx) {
  const { S, outer, trim } = ctx;
  const off = 0.014;
  const yTop = 0.8;
  const hem = (a) => 0.425 - 0.03 * Math.max(0, Math.cos(a)) ** 2;
  const a0 = (v) => 0.5 + 0.04 * v + 0.42 * sstep(0.5, 1, v);
  const vestPt = (u, v, offs, out) => {
    const aa = a0(v);
    const a = aa + (TAU - 2 * aa) * u;
    const y = hem(a) + (yTop - hem(a)) * v;
    return torsoAt(a, y, offs, out);
  };
  const col = (x, y, z, out) => {
    out.copy(outer).multiplyScalar(0.84 + 0.16 * sstep(0.42, 0.62, y));
    // складки ткани у проймы и на боках
    const a = Math.atan2(x, -z);
    out.multiplyScalar(1 - 0.08 * Math.max(0, Math.sin(a * 5 + y * 18)) * sstep(0.62, 0.45, y));
    out.multiplyScalar(0.98 + 0.04 * hash3(Math.round(x * 90), Math.round(y * 90), Math.round(z * 90)));
  };
  S.grid((u, v, out) => vestPt(u, v, off, out), 40, 14, { wrap: false }, col, torsoWeights);
  // окантовка по краю — того же цвета, чуть темнее: мягкий бортик ткани
  const edge = [];
  const nU = 32, nV = 12;
  for (let j = nV; j >= 0; j--) edge.push(vestPt(0, j / nV, off * 0.6, V()));
  for (let i = 1; i <= nU; i++) edge.push(vestPt(i / nU, 0, off * 0.6, V()));
  for (let j = 1; j <= nV; j++) edge.push(vestPt(1, j / nV, off * 0.6, V()));
  for (let i = nU - 1; i >= 1; i--) edge.push(vestPt(i / nU, 1, off * 0.6, V()));
  S.tube(edge, 0.0095, { radial: 4, closed: true }, tint(outer, 0.9), torsoWeights);
  // карманы: просто прорезь с клапаном, без ярких деталей
  for (const s of [-1, 1]) {
    const a = s * 0.98, y = 0.5;
    torsoPatch(S, a, y + 0.025, 0.085, 0.022, off - 0.002, off + 0.008, 6, 2, trim, 0.3);
  }
  // спинка: шов по центру
  const seam = [];
  for (let y = hem(Math.PI) + 0.01; y <= yTop - 0.03; y += 0.03) seam.push(torsoAt(Math.PI, y, off + 0.002));
  S.tube(seam, 0.003, { radial: 4 }, tint(outer, 0.72), torsoWeights);
}

// Воротник рубашки расстёгнут: уголки разведены в стороны, спереди виден треугольник рубашки.
function buildCollar(ctx) {
  const { S, shirt } = ctx;
  const band = [];
  for (let i = 0; i <= 22; i++) {
    const a = 0.55 + (TAU - 1.1) * (i / 22);
    band.push(torsoAt(a, 0.8, 0.02));
  }
  S.tube(band, 0.022, { radial: 6, squash: [0.55, 1.15] }, tint(shirt, 0.97), torsoWeights);
  for (const s of [-1, 1]) {
    // треугольник: у шеи сзади, у шеи спереди, кончик лежит на груди в стороне
    const A = [s * 0.5, 0.806], Bp = [s * 1.15, 0.785], T = [s * 0.55, 0.71];
    const pt = (u, v, off, out) => {
      const a = (A[0] + (Bp[0] - A[0]) * u) * (1 - v) + T[0] * v;
      const y = (A[1] + (Bp[1] - A[1]) * u) * (1 - v) + T[1] * v;
      return torsoAt(a, y, off, out);
    };
    const base = 0.026;
    S.grid((u, v, out) => pt(u, v, base + 0.006 * Math.sin(Math.PI * u) * (1 - v), out), 6, 7, { wrap: false, flip: s > 0 }, shirt, torsoWeights);
    const rim = [];
    for (let k = 0; k <= 6; k++) rim.push(pt(k / 6, 0, base, V()));
    for (let k = 1; k <= 7; k++) rim.push(pt(1 - k / 7, k / 7, base, V()));
    for (let k = 1; k < 7; k++) rim.push(pt(0, 1 - k / 7, base, V()));
    S.tube(rim, 0.0045, { radial: 5, closed: true }, tint(shirt, 0.88), torsoWeights);
  }
}

// ---------- руки ----------

function buildArms(ctx) {
  const { S, shirt, skin } = ctx;
  const sleeve = shirt;
  const cuffC = tint(shirt, 0.95);
  for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
    const Sh = V(s * SHOULDER.x, SHOULDER.y, SHOULDER.z), a = armDir(s), n0 = palmDir(s), zz = V(0, 0, 1);
    const E = Sh.clone().addScaledVector(a, UPPER), W = E.clone().addScaledVector(a, FORE);
    const bArm = B[`arm${n}`], bFore = B[`fore${n}`], bHand = B[`hand${n}`], bClav = B[`clav${n}`];
    const alongA = (x, y, z) => (x - Sh.x) * a.x + (y - Sh.y) * a.y + (z - Sh.z) * a.z;
    const armW = (x, y, z) => {
      const d = alongA(x, y, z);
      const t = sstep(UPPER - 0.03, UPPER + 0.018, d);
      const c = 1 - sstep(-0.02, 0.05, d);
      return [bClav, c * 0.6, bArm, (1 - t) * (1 - c * 0.6), bFore, t];
    };
    const sleeveCol = (x, y, z, out) => {
      out.copy(sleeve).multiplyScalar(0.9 + 0.1 * sstep(0.02, 0.1, alongA(x, y, z)));
    };
    // плечо
    S.ellipsoid(Sh.clone().add(V(-s * 0.014, 0.006, 0)), [0.088, 0.086, 0.085], 14, 10, sleeveCol, armW);
    // рукав
    const sl = [-0.02, 0.04, 0.09, UPPER, UPPER + 0.024].map((d) => Sh.clone().addScaledVector(a, d));
    S.tube(sl, [0.086, 0.085, 0.08, 0.074, 0.072], { radial: 14, caps: [true, false] }, sleeveCol, armW);
    // закатанная манжета (два валика)
    const ringAt = (d, R, tr, col, sq) => {
      const c = Sh.clone().addScaledVector(a, d);
      const bb = V().crossVectors(a, zz).normalize();
      const pts = [];
      for (let i = 0; i < 14; i++) {
        const f = (i / 14) * TAU;
        pts.push(c.clone().addScaledVector(zz, Math.cos(f) * R).addScaledVector(bb, Math.sin(f) * R));
      }
      S.tube(pts, tr, { radial: 5, closed: true, ref: a, squash: sq }, col, armW);
    };
    ringAt(UPPER + 0.012, 0.074, 0.014, tint(cuffC, 0.96), [1, 0.9]);
    ringAt(UPPER + 0.034, 0.071, 0.018, cuffC, [1, 0.85]);
    // предплечье
    const fa = [E.clone().addScaledVector(a, 0.01), E.clone().addScaledVector(a, 0.07), W.clone().addScaledVector(a, 0.005)];
    S.tube(fa, [0.058, 0.055, 0.05], { radial: 12, caps: [false, false] }, skin, (x, y, z) => {
      const d = alongA(x, y, z) - UPPER - FORE;
      const t = sstep(-0.02, 0.012, d);
      return [bFore, 1 - t, bHand, t];
    });
    buildHand(ctx, s, n, W, a, n0, zz);
  }
}

// Мясистая ладонь: пухлая кисть, подушечка большого пальца, 4 толстых пальца из двух фаланг и большой.
function buildHand(ctx, s, n, W, a, n0, zz) {
  const { S, skin } = ctx;
  const bHand = B[`hand${n}`], bF1 = B[`fing${n}`], bF2 = B[`fing2${n}`], bTh = B[`thumb${n}`];
  const pc = W.clone().addScaledVector(a, 0.052);
  const pal = new THREE.Vector3();
  const palmSide = mix(skin, '#ff9a8a', 0.12);
  const handW = (x, y, z) => {
    const d = (x - W.x) * a.x + (y - W.y) * a.y + (z - W.z) * a.z;
    const t = sstep(KNUCKLE - 0.016, KNUCKLE + 0.008, d);
    return [bHand, 1 - t, bF1, t];
  };
  S.grid((u, v, out) => {
    sphereDir(u, v, pal);
    // ладонь: тыльная сторона площе, внутренняя — пухлая
    const thick = pal.x > 0 ? 0.03 : 0.037;
    const w = 0.057 * (1 + 0.08 * pal.y);
    out.copy(pc).addScaledVector(n0, -pal.x * thick).addScaledVector(a, pal.y * 0.058).addScaledVector(zz, pal.z * w);
  }, 14, 10, { poles: true }, (x, y, z, out) => {
    const k = (x - pc.x) * n0.x + (y - pc.y) * n0.y + (z - pc.z) * n0.z;
    out.copy(skin).lerp(palmSide, sstep(0, 0.02, k));
  }, handW);
  // подушечка большого пальца
  S.ellipsoid(W.clone().addScaledVector(a, 0.04).addScaledVector(zz, -0.026).addScaledVector(n0, 0.018), [0.03, 0.03, 0.026], 10, 8, palmSide, bHand);
  const fz = [-0.041, -0.0135, 0.0135, 0.039];
  const fl = [0.05, 0.054, 0.05, 0.042];
  const fr = [0.0205, 0.021, 0.02, 0.018];
  const K0 = W.clone().addScaledVector(a, KNUCKLE);
  for (let i = 0; i < 4; i++) {
    const K = K0.clone().addScaledVector(zz, fz[i]).addScaledVector(n0, 0.003).addScaledVector(a, -0.005 * Math.abs(i - 1.5));
    const L = fl[i];
    const pts = [
      K.clone().addScaledVector(a, -0.026),
      K.clone(),
      K.clone().addScaledVector(a, L * 0.5).addScaledVector(n0, 0.006),
      K.clone().addScaledVector(a, L * 0.86).addScaledVector(n0, 0.019),
    ];
    S.tube(pts, [fr[i], fr[i], fr[i] * 0.96, fr[i] * 0.9], { radial: 7, caps: [false, true] }, skin, (x, y, z) => {
      const d = (x - K0.x) * a.x + (y - K0.y) * a.y + (z - K0.z) * a.z;
      const t1 = sstep(-0.012, 0.006, d), t2 = sstep(0.019, 0.033, d);
      return [bHand, 1 - t1, bF1, t1 - t2, bF2, t2];
    });
  }
  // большой палец
  const T0 = W.clone().addScaledVector(a, 0.03).addScaledVector(zz, -0.046).addScaledVector(n0, 0.016);
  const dt = V().addScaledVector(a, 0.5).addScaledVector(zz, -0.55).addScaledVector(n0, 0.55).normalize();
  const tp = [T0.clone().addScaledVector(dt, -0.016), T0, T0.clone().addScaledVector(dt, 0.032), T0.clone().addScaledVector(dt, 0.055).addScaledVector(n0, 0.008)];
  S.tube(tp, [0.024, 0.023, 0.021, 0.019], { radial: 8, caps: [false, true] }, skin, (x, y, z) => {
    const d = (x - T0.x) * dt.x + (y - T0.y) * dt.y + (z - T0.z) * dt.z;
    const t = sstep(-0.012, 0.008, d);
    return [bHand, 1 - t, bTh, t];
  });
}

// ---------- ноги и ботинки ----------

function buildLegs(ctx) {
  const { S, pants, shoes, o } = ctx;
  const sole = tint(shoes, 0.6);
  const patch = C(o.patch);
  for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
    const x = s * HIP_X;
    const bT = B[`thigh${n}`], bS = B[`shin${n}`], bF = B[`foot${n}`];
    const KN = HIP_Y - THIGH;
    const legW = (px, y) => {
      const k = sstep(KN + 0.03, KN - 0.03, y), p = sstep(HIP_Y - 0.04, HIP_Y + 0.03, y) * 0.5;
      return [B.pelvis, p, bT, (1 - k) * (1 - p), bS, k];
    };
    const legCol = (px, y, z, out) => {
      out.copy(pants).multiplyScalar(0.82 + 0.18 * sstep(0.28, 0.2, y));
      out.multiplyScalar(1 - 0.08 * Math.exp(-(((y - KN + 0.01) / 0.012) ** 2)) * sstep(-0.02, 0.06, z)); // складка под коленом
      out.multiplyScalar(1 - 0.06 * Math.exp(-(((y - 0.28) / 0.02) ** 2)) * sstep(0.03, -0.06, z));      // складка на бедре
    };
    const pts = [V(x, 0.4, 0.01), V(x * 1.01, 0.31, 0.004), V(x * 1.02, KN, -0.004), V(x * 1.04, 0.165, 0), V(x * 1.05, 0.12, 0.002)];
    S.tube(pts, [0.1, 0.097, 0.087, 0.082, 0.08], { radial: 14, caps: [true, false] }, legCol, legW);
    // небольшой отворот
    const ring = [];
    for (let i = 0; i < 14; i++) {
      const f = (i / 14) * TAU;
      ring.push(V(x * 1.05 + Math.sin(f) * 0.08, 0.125, Math.cos(f) * 0.08));
    }
    S.tube(ring, 0.012, { radial: 5, closed: true, ref: V(0, 1, 0), squash: [0.7, 1.1] }, tint(pants, 0.94), legW);
    // заплатка на колене (как в референсе)
    const kc = V(x * 1.02, KN + 0.004, -0.074);
    S.ellipsoid(kc, [0.05, 0.058, 0.03], 12, 8, (px, py, pz, out) => out.copy(patch).multiplyScalar(0.92 + 0.08 * sstep(kc.y - 0.05, kc.y + 0.05, py)), legW, { rot: [-0.12, 0, 0] });
    // боковой шов
    const seam = [];
    for (let y = 0.33; y >= 0.15; y -= 0.025) {
      const r = y > KN ? 0.087 + (0.097 - 0.087) * ((y - KN) / 0.09) : 0.082 + 0.005 * ((y - 0.165) / 0.06);
      seam.push(V(x * 1.02 + s * (Math.min(0.1, r) + 0.0005), y, 0));
    }
    S.tube(seam, 0.0026, { radial: 4 }, tint(pants, 0.72), legW);

    // ботинок: небольшой, округлый, тёмно-коричневый
    const fx = s * 0.114;
    const shoeCol = (px, py, pz, out) => {
      out.copy(shoes).multiplyScalar(0.85 + 0.2 * sstep(0.02, 0.09, py));
      if (pz < -0.09) out.multiplyScalar(1.08);
    };
    S.ellipsoid(V(fx, 0.05, -0.04), [0.064, 0.052, 0.104], 18, 12, shoeCol, bF, {
      deform: (d, out) => {
        const toe = sstep(-0.2, -0.9, d.z);
        out.x *= 1 + 0.06 * toe - 0.1 * sstep(0.3, 0.9, d.z);
        out.y *= 1 + 0.1 * toe;
        if (out.y < -0.03) out.y = -0.03 + (out.y + 0.03) * 0.25;
      },
    });
    S.tube([V(fx, 0.04, 0.006), V(fx, 0.09, 0.005), V(fx, 0.13, 0.004)], [0.06, 0.06, 0.059], { radial: 14, caps: [false, true] }, shoeCol,
      (px, py) => [bF, 1 - sstep(0.105, 0.14, py), bS, sstep(0.105, 0.14, py)]);
    S.ellipsoid(V(fx, 0.013, -0.04), [0.07, 0.017, 0.114], 18, 6, sole, bF, {
      deform: (d, out) => { out.y = Math.max(out.y, -0.013); out.x *= 1 + 0.05 * sstep(-0.2, -0.9, d.z); },
    });
    // шнуровка: тонкая, в тон ботинку
    for (let k = 0; k < 2; k++) {
      const y = 0.088 + k * 0.016, z = -0.066 + k * 0.012;
      S.tube([V(fx - 0.018, y, z + 0.004), V(fx + 0.018, y + 0.01, z + 0.006)], 0.0035, { radial: 4, capSegs: 1 }, tint(shoes, 1.45), bF);
      S.tube([V(fx + 0.018, y, z + 0.004), V(fx - 0.018, y + 0.01, z + 0.006)], 0.0035, { radial: 4, capSegs: 1 }, tint(shoes, 1.45), bF);
    }
  }
}

// ---------- голова и лицо ----------

function buildHead(ctx) {
  const { S, skin, hair, look } = ctx;
  const facial = look.facial || 0;
  const warm = mix(skin, '#e8705f', 0.14);
  const stubble = mix(skin, mix(tint(hair, 0.5), '#454650', 0.4), 0.46); // «трёхдневная» тень: серо-коричневая
  const dir = new THREE.Vector3();
  S.grid((u, v, out) => {
    sphereDir(u, v, dir);
    return out.copy(dir).multiplyScalar(headRadius(dir.x, dir.y, dir.z)).add(HEAD_C);
  }, 42, 30, { poles: true }, (x, y, z, out) => {
    _d.set(x, y, z).sub(HEAD_C).normalize();
    out.copy(skin);
    // тёплый румянец на брылях и щеках, лёгкая краснота у носа
    const ch = Math.exp(-((_d.y + 0.15) ** 2) / 0.05) * Math.exp(-((Math.abs(_d.x) - 0.55) ** 2) / 0.05) * Math.max(0, -_d.z);
    out.lerp(warm, Math.min(1, ch * 1.1 + 0.25 * Math.exp(-(_d.x * _d.x + (_d.y + 0.06) ** 2) / 0.02) * Math.max(0, -_d.z)));
    if (facial === 2) {
      const m = stubbleMask(_d);
      out.lerp(stubble, m * (0.7 + 0.3 * hash3(Math.round(x * 300), Math.round(y * 300), Math.round(z * 300))));
    }
    // мягкая тень снизу (брыли лежат на воротнике)
    out.multiplyScalar(0.82 + 0.18 * sstep(-0.85, -0.3, _d.y));
  }, B.head);

  // уши: маленькие, низко
  for (const s of [-1, 1]) {
    const c = headPoint(s * FACE.earDir.x, FACE.earDir.y, FACE.earDir.z, -0.004);
    const R = [0.018, 0.04, 0.03];
    S.ellipsoid(c, R, 12, 10, (x, y, z, out) => {
      const lx = (x - c.x) * s, ly = (y - c.y) / R[1], lz = (z - c.z) / R[2];
      const cup = lx > 0 ? Math.exp(-(ly * ly + lz * lz) / 0.35) : 0;
      out.copy(skin).multiplyScalar(1 - 0.22 * cup).lerp(warm, 0.35);
    }, B.head, {
      rot: [0.1, s * 0.3, 0],
      deform: (d, out) => { out.x -= s * 0.014 * Math.max(0, d.x * s) * Math.exp(-(d.y * d.y + d.z * d.z) / 0.35); },
    });
  }
  // нос: небольшой мягкий бугорок цвета кожи
  const nc = headPoint(0, FACE.noseDir.y, FACE.noseDir.z, -0.002);
  S.ellipsoid(nc, [0.032, 0.027, 0.028], 12, 10, (x, y, z, out) => out.copy(skin).lerp(warm, 0.3 * sstep(nc.y + 0.02, nc.y - 0.02, y)), B.head, {
    deform: (d, out) => { if (d.y < 0) out.x *= 1 + 0.1 * -d.y; },
  });

  buildEyes(ctx);
  buildMouth(ctx);
  buildBrows(ctx);
  if (facial === 1) buildMustache(ctx);
}

// Глаза: маленькие тёмные овалы с крошечным бликом. Кость века сплющивает их при моргании.
function buildEyes(ctx) {
  const { S } = ctx;
  for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
    const c = eyeCenter(s);
    const nrm = c.clone().sub(HEAD_C).normalize();
    const q = new THREE.Quaternion().setFromUnitVectors(V(0, 0, -1), V(nrm.x * 0.5, nrm.y * 0.4, nrm.z).normalize());
    const e = new THREE.Euler().setFromQuaternion(q);
    S.ellipsoid(c, [0.0155, 0.022, 0.009], 10, 8, C('#1a1412'), B[`lid${n}`], { rot: [e.x, e.y, e.z] });
    const up = V(0, 1, 0).applyQuaternion(q), rt = V(1, 0, 0).applyQuaternion(q), fw = V(0, 0, -1).applyQuaternion(q);
    S.ellipsoid(c.clone().addScaledVector(up, 0.008).addScaledVector(rt, -0.005 * s).addScaledVector(fw, 0.0075), [0.0042, 0.0042, 0.002], 6, 4, C('#fff8f0'), B[`lid${n}`], { rot: [e.x, e.y, e.z] });
  }
}

// Рот: короткая улыбка-линия + маленький проём. Нижняя часть проёма висит на кости jaw (в центре головы):
// поворот вверх складывает проём в линию по поверхности лица.
export const MOUTH_DEPTH = 0.11; // угловая глубина проёма, рад
function buildMouth(ctx) {
  const { S, skin } = ctx;
  const my = FACE.mouthDir.y, mz = FACE.mouthDir.z;
  const W = 0.105;
  const topDir = (t, out) => out.set(t * W, my + 0.022 * t * t, mz);
  const depth = (t) => MOUTH_DEPTH * Math.max(0, 1 - t * t) ** 0.75;
  const dirAt = (t, f, out) => {
    topDir(t, out).normalize();
    const ang = depth(t) * f;
    const y = out.y * Math.cos(ang) + out.z * Math.sin(ang);
    const z = -out.y * Math.sin(ang) + out.z * Math.cos(ang);
    return out.set(out.x, y, z);
  };
  // вес челюсти = доля угловой глубины точки: при закрытии всё ложится ровно на линию улыбки.
  // Параметр t вершины запоминаем при построении (grid вызывает fn и веса в одном порядке),
  // а не восстанавливаем по x — иначе у уголков рта оставались тёмные «клыки».
  const jawGrid = (tScale, fScale, off, nu, nv, col) => {
    const ts = [];
    let k = 0;
    S.grid((u, v, out) => { const t = (u * 2 - 1) * tScale; ts.push(t); return pt(t, v * fScale, off, out); }, nu, nv, { wrap: false, flip: true }, col, (x, y, z, f) => {
      const t = ts[k++];
      const w = f * fScale * Math.max(0, 1 - t * t) ** 0.75;
      return [B.head, 1 - w, B.jaw, w];
    });
  };
  const pt = (t, f, off, out) => { dirAt(t, f, out); return headPoint(out.x, out.y, out.z, off, out); };
  jawGrid(1, 1, 0.002, 12, 4, C('#4a1a1a'));
  jawGrid(0.6, 0.3, 0.0028, 6, 1, C('#f3ece0'));
  const smile = [];
  for (let i = 0; i <= 12; i++) smile.push(pt(((i / 12) * 2 - 1) * 1.05, 0, 0.003, V()));
  S.tube(smile, smile.map((_, i) => 0.0024 + 0.0012 * Math.sin(Math.PI * i / 12)), { radial: 5 }, mix(skin, '#5a2a22', 0.75), B.head);
  void skin;
}

// Брови: тонкие и неяркие.
function buildBrows(ctx) {
  const { S, hair, skin } = ctx;
  const col = mix(hair, skin, 0.35);
  for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
    const pts = [];
    for (let i = 0; i <= 4; i++) {
      const t = i / 4;
      pts.push(headPoint(s * (0.21 + 0.22 * t), FACE.browDir.y + 0.025 * Math.sin(Math.PI * t) - 0.02 * t, -0.92, 0.003));
    }
    S.tube(pts, 0.0048, { radial: 5, squash: [0.6, 1] }, col, B[`brow${n}`]);
  }
}

function stubbleMask(d) {
  const ax = Math.abs(d.x);
  const line = -0.2 + 0.3 * sstep(0.45, 0.95, ax);
  let m = sstep(line + 0.04, line - 0.04, d.y);
  m *= sstep(0.25, -0.05, d.z);
  const mx = d.x / 0.1, my2 = (d.y - FACE.mouthDir.y) / 0.035;
  m *= 0.35 + 0.65 * sstep(0.8, 1.3, Math.sqrt(mx * mx + my2 * my2)); // у самих губ светлее
  return m;
}

// Аккуратные усики над губой.
function buildMustache(ctx) {
  const { S, hair } = ctx;
  for (const s of [-1, 1]) {
    const pts = [], radii = [];
    for (let i = 0; i <= 5; i++) {
      const t = i / 5;
      // между носом и губой, с зазором над улыбкой; кончики чуть опущены
      pts.push(headPoint(s * (0.012 + 0.115 * t), FACE.mouthDir.y + 0.088 - 0.03 * t * t, FACE.mouthDir.z, 0.005 - 0.0025 * t));
      radii.push(0.0072 * (1 - 0.5 * t) + 0.0018);
    }
    S.tube(pts, radii, { radial: 6, squash: [0.7, 1] }, tint(hair, 0.95), B.head);
  }
}

// ---------- причёски ----------

// Линия роста волос (dy) по азимуту; f = |az|/PI: 0 — лоб, 1 — затылок. Обходит уши сверху.
function hairEdge(az, style) {
  const f = Math.abs(az) / Math.PI;
  const front = style === 2 ? 0.44 : 0.4;
  let e = front;
  const rec = style === 3 ? 0 : 0.07;          // залысины: углы над висками чуть выше центра
  e += rec * sstep(0.03, 0.12, f);
  e += (0.28 - front - rec) * sstep(0.15, 0.28, f);  // виски
  e += -0.22 * sstep(0.32, 0.36, f);           // короткие бакенбарды прямо перед ухом
  e += 0.24 * sstep(0.41, 0.45, f);            // над ухом
  e += -0.5 * sstep(0.6, 0.7, f);              // за ухом вниз
  e += -0.14 * sstep(0.7, 0.95, f);            // затылок
  if (style === 0 || style === 1) e += 0.02 * Math.sin(az * 24) * sstep(0.12, 0.03, f); // чёлка зубчиками
  return e;
}

function buildHair(ctx) {
  const { S, hair, look } = ctx;
  const style = look.hairStyle || 0; // 0 короткая, 1 зачёс, 2 ёжик, 3 лысина
  const capped = look.hat > 0;
  const b0 = hatBand(look.hat);
  const dir = new THREE.Vector3();
  const strands = tint(hair, 1.25);
  const NU = 60, NV = 14;
  const thick = (d, az, el) => {
    switch (style) {
      case 1: return 0.015 + 0.006 * sstep(0, 0.9, d.y) + 0.004 * Math.sin(az * 30 + d.y * 4)
        + 0.07 * Math.exp(-((d.y - 0.55) ** 2) / 0.022) * Math.max(0, -d.z) ** 1.5 * (1 + 0.6 * d.x);
      case 2: return 0.006;
      // венчик сходит на нет к вискам (без «шнурка» через лоб)
      case 3: return (0.016 + 0.005 * Math.sin(az * 9) * Math.sin(el * 7)) * sstep(0.27, 0.42, Math.abs(((az + Math.PI) % TAU) - Math.PI) / Math.PI);
      // короткая: виски и затылок коротко (почти «под машинку»), объём сверху и чуть приподнятая чёлка
      default: return 0.005 + 0.019 * sstep(0.3, 0.72, d.y) + 0.0025 * Math.sin(az * 46 + el * 8) * sstep(0.2, 0.6, d.y)
        + 0.01 * Math.exp(-((d.y - 0.6) ** 2) / 0.03) * Math.max(0, -d.z);
    }
  };
  const topDy = (az) => (capped ? Math.min(1, b0 + 0.11 * Math.cos(az) + 0.05) : 1);
  S.grid((u, v, out) => {
    const az = u * TAU;
    const f = Math.abs(((az + Math.PI) % TAU) - Math.PI) / Math.PI;
    let lo, hi;
    if (style === 3) { // лысина с венчиком: от висков над ушами и низко по затылку (лоб и темя голые)
      lo = 0.1 - 0.56 * sstep(0.52, 0.85, f);
      hi = 0.27 - 0.07 * sstep(0.55, 0.85, f) + 0.02 * Math.sin(az * 11);
      hi = lo + (hi - lo) * sstep(0.27, 0.42, f);
    } else {
      lo = hairEdge(az > Math.PI ? az - TAU : az, style);
      hi = 1;
    }
    hi = Math.max(lo, Math.min(hi, topDy(az)));
    const e0 = Math.asin(Math.max(-0.99, Math.min(1, lo))), e1 = Math.asin(Math.max(-0.99, Math.min(1, hi)));
    const el = e0 + (e1 - e0) * v ** 0.85;
    dir.set(Math.cos(el) * Math.sin(az), Math.sin(el), -Math.cos(el) * Math.cos(az));
    let t = thick(dir, az, el);
    let rim = sstep(0, 1 / NV, v) * (0.6 + 0.4 * sstep(0.06, 0.3, v));
    if (style === 3 || capped) rim *= sstep(1, 1 - 1.5 / NV, v);
    if (capped) t *= 0.8;
    return headPoint(dir.x, dir.y, dir.z, 0.0028 + t * rim, out);
  }, NU, NV, { wrap: true }, (x, y, z, out, tv) => {
    _d.set(x, y, z).sub(HEAD_C).normalize();
    out.copy(hair);
    const az = Math.atan2(_d.x, -_d.z);
    out.lerp(strands, 0.24 * (0.5 + 0.5 * Math.sin(az * 46 + _d.y * 8)) * sstep(0.1, 0.8, _d.y));
    out.multiplyScalar((0.8 + 0.2 * sstep(-0.3, 0.6, _d.y)) * (0.82 + 0.18 * sstep(0, 0.25, tv)));
    if (style === 2) out.lerp(ctx.skin, 0.25); // ёжик просвечивает
    else if (style === 0) out.lerp(ctx.skin, 0.22 * sstep(0.4, 0.1, _d.y)); // короткие виски просвечивают
  }, B.head);
}

// ---------- шляпы ----------

// Высота ленты шляпы (dy на лбу); у каждой шляпы своя посадка.
function hatBand(kind) {
  return [1, 0.26, 0.28, 0.17, 0.3, 0.27][kind] ?? 1;
}

// Кольцо ленты на голове: точки по азимуту на высоте, наклонённой назад (затылок ниже лба).
function bandRing(b0, n, off) {
  const pts = [];
  const d = new THREE.Vector3();
  for (let i = 0; i < n; i++) {
    const az = (i / n) * TAU;
    let el = Math.asin(b0);
    for (let k = 0; k < 4; k++) {
      const dz = -Math.cos(el) * Math.cos(az);
      el = Math.asin(Math.max(-0.99, Math.min(0.99, b0 - 0.12 * dz)));
    }
    d.set(Math.cos(el) * Math.sin(az), Math.sin(el), -Math.cos(el) * Math.cos(az));
    pts.push(headPoint(d.x, d.y, d.z, off));
  }
  return pts;
}

// Выталкивает точку наружу из головы (с зазором под волосы).
function clearHead(p, gap) {
  _t.copy(p).sub(HEAD_C);
  const len = _t.length();
  _t.divideScalar(len || 1);
  const r = headRadius(_t.x, _t.y, _t.z) + gap;
  if (len < r) p.copy(HEAD_C).addScaledVector(_t, r);
  return p;
}

// Лофт тульи: от кольца ленты вверх по оси шляпы; prof — [[высота, масштаб], ...], deform(p, k, i).
function crown(S, ring, axis, center, prof, color, deform) {
  const n = ring.length;
  const rel = ring.map((p) => p.clone().sub(center));
  const rows = prof.length;
  const P = (i, j, out) => {
    const [h, s] = prof[j];
    out.copy(rel[i % n]).multiplyScalar(s).add(center).addScaledVector(axis, h);
    if (deform) deform(out, j, i);
    if (j > 0) clearHead(out, 0.02);
    return out;
  };
  S.grid((u, v, out) => P(Math.round(u * n), Math.round(v * (rows - 1)), out), n, rows - 1, { wrap: true }, color, B.head);
  // верхушку закрываем «пробкой» (последнее кольцо — точка, если масштаб 0)
}

function buildHat(ctx) {
  const { S, look } = ctx;
  const kind = look.hat;
  if (!kind) { ctx.top = 1.29; return; }
  const b0 = hatBand(kind);
  const axis = V(0, 1, 0.12).normalize();
  const ring = bandRing(b0, 40, 0.009);
  const center = ring.reduce((a, p) => a.add(p), V()).multiplyScalar(1 / ring.length);
  const outward = (p, k) => p.clone().sub(center).projectOnPlane(axis).multiplyScalar(k).add(center);
  const brim = (wOut, droop, col, colUnder, curl = null, segs = 5) => {
    // поля: верх, низ и скруглённый край
    const pt = (i, f, dy, out) => {
      const p = outward(ring[i % 40], 1 + (wOut - 1) * f).addScaledVector(axis, -droop * f * f + dy);
      if (curl) curl(p, f, i);
      return out.copy(p);
    };
    S.grid((u, v, out) => pt(Math.round(u * 40), v, 0.008, out), 40, segs, { wrap: true, flip: true }, col, B.head);
    S.grid((u, v, out) => pt(Math.round(u * 40), v, -0.004, out), 40, segs, { wrap: true }, colUnder, B.head);
    const rim = [];
    for (let i = 0; i < 40; i++) rim.push(pt(i, 1, 0.002, V()));
    S.tube(rim, 0.0075, { radial: 5, closed: true }, tint(col, 0.92), B.head);
  };
  const ribbon = (h0, h1, scale, col) => {
    S.grid((u, v, out) => {
      const p = outward(ring[Math.round(u * 40) % 40], scale);
      return out.copy(p).addScaledVector(axis, h0 + (h1 - h0) * v);
    }, 40, 1, { wrap: true }, col, B.head);
  };

  switch (kind) {
    case 1: { // сафари
      const felt = C('#d9c08a');
      crown(S, ring, axis, center, [[-0.006, 1.0], [0.04, 1.015], [0.09, 0.99], [0.13, 0.9], [0.155, 0.66], [0.163, 0.3], [0.165, 0]], (x, y, z, out) => {
        out.copy(felt).multiplyScalar(0.88 + 0.12 * sstep(center.y, center.y + 0.12, y));
      });
      // вдавленный шов на макушке
      ribbon(0.002, 0.046, 1.03, C('#6e4a2c'));
      brim(1.72, 0.035, felt, tint(felt, 0.72), null, 4);
      // дырочки-вентиляция с люверсами
      S.slot = 1;
      for (const s of [-1, 1]) {
        for (const k of [-1, 0, 1]) {
          const i = Math.round((s > 0 ? 10 : 30) + k * 1.6);
          const p = outward(ring[i], 0.965).addScaledVector(axis, 0.085);
          const nrm = p.clone().sub(center).projectOnPlane(axis).normalize();
          S.disc(p.clone().addScaledVector(nrm, 0.003), nrm, 0.0075, 0.004, C('#c7a24c'), B.head, 10);
          S.disc(p.clone().addScaledVector(nrm, 0.0052), nrm, 0.0042, 0.003, C('#2a1d14'), B.head, 8);
        }
      }
      S.slot = 0;
      ctx.top = center.y + 0.18;
      break;
    }
    case 2: { // ковбойская: заломы на тулье, загнутые поля
      const felt = C('#9b6a3d');
      crown(S, ring, axis, center, [[-0.006, 1.0], [0.05, 0.99], [0.11, 0.94], [0.155, 0.84], [0.17, 0.62], [0.172, 0.3], [0.17, 0]], (x, y, z, out) => {
        out.copy(felt).multiplyScalar(0.85 + 0.15 * sstep(center.y, center.y + 0.14, y));
      }, (p, j) => {
        const lx = p.x - center.x;
        if (j >= 3) {
          p.addScaledVector(axis, -0.045 * Math.exp(-(lx * lx) / 0.0035) * sstep(2, 6, j)); // залом по центру
          if (p.z < center.z) p.x = center.x + lx * (1 - 0.18 * sstep(2, 5, j) * sstep(0, -0.2, p.z - center.z)); // щипок спереди
        }
      });
      ribbon(0.002, 0.04, 1.02, C('#3a2418'));
      // поля поменьше, бока симметрично загнуты вверх, перед и зад чуть опущены
      brim(1.62, 0.016, felt, tint(felt, 0.7), (p, f) => {
        const lx = (p.x - center.x) / 0.4;
        p.y += 0.075 * f * f * lx * lx;
        p.y -= 0.022 * f * f * (1 - Math.min(1, lx * lx * 3));
      }, 6);
      S.slot = 1;
      const pc = outward(ring[10], 1.03).addScaledVector(axis, 0.022);
      S.disc(pc, pc.clone().sub(center).projectOnPlane(axis).normalize(), 0.014, 0.005, C('#d8d8de'), B.head, 12);
      S.slot = 0;
      ctx.top = center.y + 0.2;
      break;
    }
    case 3: { // армейская каска: высокий оливковый купол заметно больше головы и рыже-коричневый кожаный обод (как в референсе)
      const olive = C('#5f6b2e'), leather = C('#a8542c');
      const prof = [[0.014, 1.17], [0.065, 1.18], [0.125, 1.14], [0.185, 1.02], [0.235, 0.8], [0.27, 0.48], [0.283, 0]];
      crown(S, ring, axis, center, prof, (x, y, z, out) => {
        out.copy(olive).multiplyScalar(0.8 + 0.2 * sstep(center.y, center.y + 0.18, y) + 0.06 * (hash3(Math.round(x * 30), Math.round(y * 30), Math.round(z * 30)) - 0.5));
      });
      // широкий кожаный обод по краю купола
      const lth = (x, y, z, out) => out.copy(leather).multiplyScalar(0.9 + 0.1 * hash3(Math.round(x * 60), Math.round(y * 60), Math.round(z * 60)));
      S.grid((u, v, out) => {
        const p = outward(ring[Math.round(u * 40) % 40], 1.155 + 0.02 * v);
        return out.copy(p).addScaledVector(axis, -0.03 + 0.046 * v);
      }, 40, 2, { wrap: true }, lth, B.head);
      // снизу обод закрыт кольцом до головы — в щель между каской и головой не видно неба
      S.grid((u, v, out) => {
        const p = outward(ring[Math.round(u * 40) % 40], 1.155 - 0.155 * v);
        return out.copy(p).addScaledVector(axis, -0.03 + 0.03 * v);
      }, 40, 2, { wrap: true, flip: true }, (x, y, z, out) => lth(x, y, z, out).multiplyScalar(0.55), B.head);
      S.tube(ring.map((p) => outward(p, 1.16).addScaledVector(axis, -0.03)), 0.012, { radial: 6, closed: true, squash: [1, 0.8] }, tint(leather, 0.85), B.head);
      S.tube(ring.map((p) => outward(p, 1.172).addScaledVector(axis, 0.016)), 0.007, { radial: 5, closed: true }, tint(olive, 0.7), B.head);
      ctx.top = center.y + 0.29;
      break;
    }
    case 4: { // кепка: мягкая округлая тулья, пуговка, короткий козырёк
      const cap = C(['#6b4a32', '#5d5a3c', '#3f4656', '#7a3e2c'][look.outfit % 4]);
      const prof = [[-0.006, 1.02], [0.04, 1.05], [0.08, 1.0], [0.11, 0.8], [0.13, 0.48], [0.138, 0]];
      crown(S, ring, axis, center, prof, (x, y, z, out) => {
        const az = Math.atan2(x - center.x, -(z - center.z));
        out.copy(cap).multiplyScalar(0.84 + 0.16 * sstep(center.y, center.y + 0.1, y) + 0.03 * Math.cos(az * 6));
      }, (p, j) => { if (j >= 1 && j <= 3 && p.z < center.z) p.addScaledVector(V(0, 0, -1), 0.012 * Math.sin((j / 3) * Math.PI)); });
      S.ellipsoid(center.clone().addScaledVector(axis, 0.14), [0.015, 0.012, 0.015], 8, 6, tint(cap, 0.85), B.head);
      ribbon(-0.004, 0.01, 1.012, tint(cap, 0.82));
      const vpt = (u, f, dy, out) => {
        const i = 40 - 8 + Math.round(u * 16);
        const p = outward(ring[((i % 40) + 40) % 40], 1.0);
        const k = Math.sin(Math.PI * u);
        p.addScaledVector(V(0, 0, -1), f * 0.075 * k ** 0.5).addScaledVector(axis, -0.008 - 0.02 * f * (1 - k) - 0.02 * f + dy);
        return out.copy(p);
      };
      S.grid((u, v, out) => vpt(u, v, 0.005, out), 16, 4, { wrap: false, flip: true }, tint(cap, 0.9), B.head);
      S.grid((u, v, out) => vpt(u, v, -0.003, out), 16, 4, { wrap: false }, tint(cap, 0.6), B.head);
      const rimv = [];
      for (let i = 0; i <= 16; i++) rimv.push(vpt(i / 16, 1, 0.001, V()));
      S.tube(rimv, 0.005, { radial: 5 }, tint(cap, 0.8), B.head);
      ctx.top = center.y + 0.16;
      break;
    }
    case 5: { // шапка: вязаные рубчики, отворот кольцами и помпон
      const knit = C(['#8a3b2e', '#4e5a3a', '#3d4a66', '#b88a3c'][look.outfit % 4]), cuff = tint(knit, 0.82), pom = C('#efe6d6');
      const prof = [[0.0, 1.035], [0.045, 1.05], [0.1, 1.03], [0.15, 0.92], [0.19, 0.68], [0.212, 0.36], [0.218, 0]];
      crown(S, ring.map((p, i) => outward(p, 1 + 0.012 * Math.cos(i * Math.PI))), axis, center, prof, (x, y, z, out) => {
        const az = Math.atan2(x - center.x, -(z - center.z));
        out.copy(knit).multiplyScalar(0.86 + 0.1 * Math.cos(az * 20) + 0.12 * sstep(center.y, center.y + 0.12, y));
      });
      for (const [h, k] of [[-0.004, 1.085], [0.022, 1.1], [0.048, 1.085]]) {
        S.tube(ring.filter((_, i) => i % 4 !== 3).map((p) => outward(p, k).addScaledVector(axis, h)), 0.017, { radial: 5, closed: true, squash: [0.8, 1] }, (x, y, z, out) => {
          const az = Math.atan2(x - center.x, -(z - center.z));
          out.copy(cuff).multiplyScalar(0.9 + 0.1 * Math.cos(az * 20));
        }, B.head);
      }
      const pc = center.clone().addScaledVector(axis, 0.265);
      S.ellipsoid(pc, [0.062, 0.058, 0.062], 16, 12, (x, y, z, out) => out.copy(pom).multiplyScalar(0.82 + 0.25 * hash3(Math.round(x * 300), Math.round(y * 300), Math.round(z * 300))), B.head, {
        deform: (d, out) => out.multiplyScalar(1 + 0.12 * (hash3(Math.round(d.x * 7), Math.round(d.y * 7), Math.round(d.z * 7)) - 0.5)),
      });
      ctx.top = center.y + 0.31;
      break;
    }
    default:
  }
}

// ---------- очки ----------

function buildGlasses(ctx) {
  const { S } = ctx;
  const kind = ctx.look.glasses;
  if (!kind) return;
  S.slot = 1;
  const frameC = [null, C('#151518'), C('#e6d6ab'), C('#c9a556')][kind];
  const lensC = [null, C('#0d0d10'), C('#2c3d4f'), C('#3e2a1d')][kind];
  const lensLow = [null, C('#141418'), C('#3f5468'), C('#8a5b36')][kind];
  const streak = [null, C('#3a3a44'), C('#7f97ab'), C('#b58a5a')][kind];
  const fr = kind === 3 ? 0.0038 : kind === 2 ? 0.0075 : 0.0062;
  const outline = (s, i, n) => { // точки контура линзы в локальных координатах (x наружу, y вверх)
    const f = (i / n) * TAU;
    if (kind === 1) return [Math.cos(f) * 0.053, Math.sin(f) * 0.053];
    if (kind === 2) {
      const ex = 0.065, ey = 0.047, p = 4;
      const c = Math.cos(f), sn = Math.sin(f);
      return [Math.sign(c) * Math.abs(c) ** (2 / p) * ex, Math.sign(sn) * Math.abs(sn) ** (2 / p) * ey];
    }
    // авиаторы: капля, ниже к внешнему краю
    const c = Math.cos(f), sn = Math.sin(f);
    const y = sn > 0 ? sn * 0.036 : sn * (0.05 + 0.012 * c);
    return [c * 0.055 + (sn < 0 ? 0.006 * c : 0), y];
  };
  for (const s of [-1, 1]) {
    const ec = eyeCenter(s);
    const c = V(ec.x * 1.04, ec.y + 0.004, ec.z - 0.05);
    const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(0.04, -s * 0.12, 0));
    const ex = V(s, 0, 0).applyQuaternion(q), ey = V(0, 1, 0).applyQuaternion(q), ez = V(0, 0, -1).applyQuaternion(q);
    const P = (lx, ly, bulge, out) => out.copy(c).addScaledVector(ex, lx).addScaledVector(ey, ly).addScaledVector(ez, bulge);
    const N = 24;
    const rim = [];
    for (let i = 0; i < N; i++) { const [lx, ly] = outline(s, i, N); rim.push(P(lx, ly, 0, V())); }
    S.tube(rim, fr, { radial: 5, closed: true }, frameC, B.head);
    // линза: радиальная сетка с лёгкой выпуклостью
    S.grid((u, v, out) => {
      const [lx, ly] = outline(s, Math.round(u * N), N);
      const k = 1 - v;
      return P(lx * k, ly * k, 0.004 * (1 - k * k), out);
    }, N, 3, { wrap: true, flip: s < 0 }, (x, y, z, out) => {
      out.copy(lensLow).lerp(lensC, sstep(c.y - 0.04, c.y + 0.03, y));
      // диагональный блик-полоса на стекле, как в референсе
      const lx = (x - c.x) * s, ly = y - c.y;
      out.lerp(streak, 0.75 * Math.exp(-(((lx + ly * 0.9) - 0.006) ** 2) / 0.00018));
    }, B.head);
    // дужка: от внешнего края к уху
    const [ox, oy] = outline(s, 0, N);
    const hinge = P(ox, oy + (kind === 3 ? 0.03 : 0.012), 0, V());
    const ear = headPoint(s * 0.97, 0.08, 0.12, 0.012);
    const mid = headPoint(s * 0.86, 0.1, -0.5, 0.014);
    S.tube([hinge, mid, ear, ear.clone().add(V(0, -0.03, 0.03))], kind === 3 ? 0.0032 : 0.0048, { radial: 5 }, frameC, B.head);
  }
  // переносица
  const l = eyeCenter(-1), r = eyeCenter(1);
  const y = (l.y + r.y) / 2 + (kind === 3 ? 0.022 : 0.016), z = l.z - 0.05;
  const span = kind === 2 ? 0.042 : 0.052;
  S.tube([V(-span, y, z), V(0, y + 0.01, z - 0.006), V(span, y, z)], kind === 2 ? 0.006 : 0.0045, { radial: 5 }, frameC, B.head);
  if (kind === 3) S.tube([V(-span, y + 0.026, z), V(0, y + 0.03, z - 0.004), V(span, y + 0.026, z)], 0.003, { radial: 4 }, frameC, B.head);
  S.slot = 0;
}
