// Геометрия персонажа «как в референсе»: пухлый торс-боб с животиком, большая голова с щеками и
// носом-картошкой, рубашка с воротником и пуговицами, закатанные рукава, жилет/куртка/худи с карманами
// и окантовкой, ремень с пряжкой, штаны с отворотами и швом, ботинки со шнурками, причёски, шляпы, очки.
// Всё собирается в две skinned-геометрии: матовое тело и маленькая глянцевая (глаза, стёкла, пряжка).

import * as THREE from 'three';
import { Sculpt, C, V, tint, mix, sstep, sphereDir } from './sculpt.js';
import {
  B, HEAD_C, headRadius, headPoint, FACE, eyeCenter, armDir, palmDir, SHOULDER, UPPER, FORE, HIP_X, HIP_Y, KNUCKLE,
} from './rig.js';

// ---------- палитры и варианты внешности ----------
export const SKINS = ['#f2a77f', '#e89b72', '#d08359', '#a8643f', '#f7c7a3', '#7f4a30'];
export const HAIRS = ['#3b2a1d', '#6b4a2f', '#c9a24a', '#b9482a', '#1f1f24', '#d9d9d9'];
const EYES = ['#5b3a1f', '#3d6fa8', '#4c7a3c', '#7a5530', '#2c2c33', '#5f7f8f'];
// style: vest — жилет поверх рубашки, jacket — куртка (рукава её цвета), hoodie — худи
export const OUTFITS = [
  { name: 'Исследователь', style: 'vest', shirt: '#f3eee4', vest: '#7b3524', trim: '#4f1f14', pants: '#d6c08a', shoes: '#5e3c25', pockets: 2 },
  { name: 'Лесник', style: 'jacket', shirt: '#e9dcc0', vest: '#4f7140', trim: '#33502a', pants: '#6b5a3e', shoes: '#3b2a1d', pockets: 2 },
  { name: 'Рыбак', style: 'vest', shirt: '#f2d75e', vest: '#3f6a86', trim: '#27455a', pants: '#4b6a8a', shoes: '#2c2c33', pockets: 4 },
  { name: 'Ковбой', style: 'vest', shirt: '#c94b3b', vest: '#5c3b25', trim: '#2f1d10', pants: '#3f4f73', shoes: '#4a2c1a', pockets: 0 },
  { name: 'Мятный', style: 'hoodie', shirt: '#f4f1ea', vest: '#5fc4a8', trim: '#3e9a82', pants: '#e6dbc0', shoes: '#5a4532', pockets: 0 },
  { name: 'Клубничный', style: 'hoodie', shirt: '#fbe4ea', vest: '#e0628a', trim: '#b7456a', pants: '#76699f', shoes: '#3a2c40', pockets: 0 },
  { name: 'Угольный', style: 'jacket', shirt: '#ececf0', vest: '#3a3c46', trim: '#22232a', pants: '#5a5b64', shoes: '#1f1f24', pockets: 2 },
  { name: 'Закат', style: 'vest', shirt: '#ffe6bd', vest: '#d97a2b', trim: '#9c4f17', pants: '#8a4b2a', shoes: '#3d2a1c', pockets: 2 },
];
export const HATS = ['Без шляпы', 'Сафари', 'Ковбойская', 'Каска', 'Кепка', 'Шапка', 'Колпак'];
export const GLASSES = ['Без очков', 'Чёрные круглые', 'Квадратные', 'Авиаторы'];
export const HAIR_STYLES = ['Короткая', 'Зачёс', 'Кудри', 'Лысина', 'Хвостик', 'Ёжик'];
export const FACIAL = ['Без бороды', 'Усы', 'Борода', 'Щетина'];

// цвет рукава для рук от первого лица
export function sleeveColor(look) {
  const o = OUTFITS[look.outfit] || OUTFITS[0];
  return o.style === 'vest' ? o.shirt : o.vest;
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
  [0.252, 0.0, 0.0, 0.0],
  [0.262, 0.13, 0.12, 0.115],
  [0.29, 0.225, 0.21, 0.2],
  [0.34, 0.285, 0.27, 0.24],
  [0.41, 0.312, 0.302, 0.255],
  [0.48, 0.32, 0.314, 0.26],
  [0.56, 0.313, 0.3, 0.256],
  [0.64, 0.296, 0.265, 0.245],
  [0.71, 0.268, 0.228, 0.226],
  [0.765, 0.215, 0.185, 0.19],
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
  const shirt = C(o.shirt), outer = C(o.vest), trim = C(o.trim), pants = C(o.pants), shoes = C(o.shoes);
  const style = o.style;
  const S = new Sculpt(2, lod);
  const ctx = { look, o, skin, hair, shirt, outer, trim, pants, shoes, style, S, top: 1.32 };

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

function buildTorso(ctx) {
  const { S, shirt, outer, pants, trim, style } = ctx;
  const hoodie = style === 'hoodie';
  const waist = hoodie ? 0.37 : 0.405;
  const top = hoodie ? outer : shirt;
  const ao = (y, out) => out.multiplyScalar(0.82 + 0.18 * sstep(0.26, 0.34, y));

  // тело
  S.grid((u, v, out) => torsoPt(u * TAU, v, 0, out), 40, 26, { poles: true }, (x, y, z, out) => {
    out.copy(pants).lerp(top, sstep(waist - 0.01, waist + 0.01, y));
    ao(y, out);
    // складки у подмышек и под грудью — лёгкая тень
    out.multiplyScalar(1 - 0.06 * Math.exp(-(((y - 0.7) / 0.05) ** 2)) * sstep(0.18, 0.27, Math.abs(x)));
  }, torsoWeights);

  // шея (видна, только если голова сильно наклонена)
  S.tube([V(0, 0.76, 0.01), V(0, 0.84, 0.01), V(0, 0.92, 0.0)], [0.1, 0.098, 0.095], { radial: 10 }, tint(ctx.skin, 0.9),
    (x, y) => [B.chest, 1 - sstep(0.78, 0.86, y), B.neck, sstep(0.78, 0.86, y) * (1 - sstep(0.86, 0.9, y)), B.head, sstep(0.86, 0.9, y)]);

  if (!hoodie) {
    // планка рубашки и пуговицы
    const placket = [];
    for (let y = 0.43; y <= 0.79; y += 0.03) placket.push(torsoAt(0, y, 0.003));
    S.tube(placket, 0.0065, { radial: 6, ref: V(0, 0, 1) }, tint(shirt, 0.93), torsoWeights);
    for (const y of [0.48, 0.56, 0.64, 0.72]) {
      S.disc(torsoAt(0, y, 0.008), torsoNormal(0, y), 0.0115, 0.006, tint(shirt, 0.8), torsoWeights, 10);
    }
    buildBelt(ctx);
    buildOuter(ctx);
    buildCollar(ctx);
  } else {
    buildHoodie(ctx);
  }

  // шов ширинки
  const fly = [];
  for (let y = 0.395; y >= 0.29; y -= 0.02) fly.push(torsoAt(0, y, 0.002));
  if (!hoodie) S.tube(fly, 0.0035, { radial: 5 }, tint(pants, 0.72), torsoWeights);
  void trim;
}

function buildBelt(ctx) {
  const { S, pants } = ctx;
  const leather = C('#4a2e1c');
  const y0 = 0.386, y1 = 0.424;
  S.grid((u, v, out) => torsoAt(u * TAU, y0 + (y1 - y0) * v, 0.009, out), 48, 2, { wrap: true }, leather, torsoWeights);
  for (const y of [y0, y1]) {
    const ring = [];
    for (let i = 0; i < 48; i++) ring.push(torsoAt((i / 48) * TAU, y, 0.009));
    S.tube(ring, 0.004, { radial: 3, closed: true, ref: V(0, 1, 0) }, tint(leather, 0.75), torsoWeights);
  }
  // шлёвки
  for (const a of [-0.75, 0.75, 2.3, -2.3]) {
    S.tube([torsoAt(a, 0.43, 0.012), torsoAt(a, 0.38, 0.012)], 0.008, { radial: 6, squash: [0.45, 1] }, tint(pants, 0.92), torsoWeights);
  }
  // пряжка (глянцевая)
  S.slot = 1;
  const c = torsoAt(0, 0.405, 0.017);
  const n = torsoNormal(0, 0.405);
  const right = V(1, 0, 0), up = V().crossVectors(n, right).normalize().negate();
  const rect = [];
  const hw = 0.03, hh = 0.022, rr = 0.008;
  for (let i = 0; i < 24; i++) {
    const q = Math.floor(i / 6), f = (i % 6) / 6;
    const ang = q * Math.PI / 2 + f * Math.PI / 2;
    const cx = (q === 0 || q === 3 ? 1 : -1) * (hw - rr), cy = (q < 2 ? 1 : -1) * (hh - rr);
    rect.push(c.clone().addScaledVector(right, cx + Math.cos(ang) * rr).addScaledVector(up, cy + Math.sin(ang) * rr));
  }
  S.tube(rect, 0.0055, { radial: 6, closed: true }, C('#d6ad52'), torsoWeights);
  S.tube([c.clone().addScaledVector(right, -0.022), c.clone().addScaledVector(right, 0.006)], 0.0035, { radial: 5 }, C('#c49a40'), torsoWeights);
  S.slot = 0;
  // кончик ремня
  S.tube([torsoAt(-0.12, 0.405, 0.013), torsoAt(-0.3, 0.405, 0.012)], 0.016, { radial: 6, squash: [0.35, 1] }, tint(leather, 0.85), torsoWeights);
}

// Жилет или куртка: оболочка с V-вырезом, окантовка, карманы, пуговицы.
function buildOuter(ctx) {
  const { S, outer, trim, o, style } = ctx;
  const jacket = style === 'jacket';
  const off = jacket ? 0.017 : 0.014;
  const yTop = 0.8;
  const hem = (a) => (jacket ? 0.355 : 0.432) - 0.035 * Math.max(0, Math.cos(a)) ** 2;
  const a0 = (v) => (jacket ? 0.3 : 0.36) + 0.06 * v + (jacket ? 0.62 : 0.45) * sstep(0.55, 1, v);
  const vestPt = (u, v, offs, out) => {
    const aa = a0(v);
    const a = aa + (TAU - 2 * aa) * u;
    const y = hem(a) + (yTop - hem(a)) * v;
    return torsoAt(a, y, offs, out);
  };
  const col = (x, y, z, out) => {
    out.copy(outer).multiplyScalar(0.86 + 0.14 * sstep(0.4, 0.62, y));
    out.multiplyScalar(0.97 + 0.06 * hash3(Math.round(x * 90), Math.round(y * 90), Math.round(z * 90)));
  };
  S.grid((u, v, out) => vestPt(u, v, off, out), 40, 14, { wrap: false }, col, torsoWeights);

  // окантовка по всему краю
  const edge = [];
  const nU = 32, nV = 12;
  for (let j = nV; j >= 0; j--) edge.push(vestPt(0, j / nV, off * 0.6, V()));
  for (let i = 1; i <= nU; i++) edge.push(vestPt(i / nU, 0, off * 0.6, V()));
  for (let j = 1; j <= nV; j++) edge.push(vestPt(1, j / nV, off * 0.6, V()));
  for (let i = nU - 1; i >= 1; i--) edge.push(vestPt(i / nU, 1, off * 0.6, V()));
  S.tube(edge, jacket ? 0.011 : 0.0095, { radial: 4, closed: true }, trim, torsoWeights);

  // лацканы куртки
  if (jacket) {
    for (const s of [-1, 1]) {
      const A = [s * 0.38, 0.79], Bp = [s * 0.36, 0.6], Cp = [s * 0.82, 0.745];
      S.grid((u, v, out) => {
        const a = A[0] + (Bp[0] - A[0]) * v + (Cp[0] - A[0]) * u * (1 - v);
        const y = A[1] + (Bp[1] - A[1]) * v + (Cp[1] - A[1]) * u * (1 - v);
        return torsoAt(a, y, off + 0.006 + 0.004 * Math.sin(Math.PI * u) * Math.sin(Math.PI * v), out);
      }, 6, 8, { wrap: false, flip: s > 0 }, tint(outer, 1.08), torsoWeights);
      const rim = [];
      for (let k = 0; k <= 8; k++) rim.push(torsoAt(A[0] + (Bp[0] - A[0]) * (k / 8), A[1] + (Bp[1] - A[1]) * (k / 8), off + 0.006));
      for (let k = 1; k <= 8; k++) rim.push(torsoAt(Bp[0] + (Cp[0] - Bp[0]) * (k / 8), Bp[1] + (Cp[1] - Bp[1]) * (k / 8), off + 0.006));
      S.tube(rim, 0.006, { radial: 5 }, tint(outer, 0.85), torsoWeights);
    }
  }

  // карманы с клапанами и пуговками
  const pockets = [];
  if (o.pockets >= 2) pockets.push([-0.7, 0.505, 0.09, 0.072], [0.7, 0.505, 0.09, 0.072]);
  if (o.pockets >= 4) pockets.push([-0.66, 0.635, 0.072, 0.058], [0.66, 0.635, 0.072, 0.058]);
  if (o.pockets === 2 && !jacket) pockets.push([-0.64, 0.645, 0.064, 0.05]);
  for (const [a, y, w, h] of pockets) {
    torsoPatch(S, a, y, w, h, off - 0.002, off + 0.009, 7, 6, tint(outer, 0.95), 0.25);
    torsoPatch(S, a, y + h * 0.42, w * 1.08, h * 0.32, off + 0.002, off + 0.014, 7, 3, tint(outer, 0.8), 0.3);
    S.disc(torsoAt(a, y + h * 0.36, off + 0.016), torsoNormal(a, y + h * 0.36), 0.008, 0.006, tint(trim, 1.2), torsoWeights, 8);
  }
  // ручка в нагрудном кармане
  if (o.pockets === 2 && !jacket) {
    S.slot = 1;
    const p = torsoAt(-0.6, 0.66, off + 0.012);
    S.tube([p, p.clone().add(V(0, 0.045, 0.004))], 0.0055, { radial: 6 }, C('#2f5fb3'), torsoWeights);
    S.slot = 0;
  }
  // спинка: шов по центру и хлястик с пряжкой
  const seam = [];
  for (let y = hem(Math.PI) + 0.01; y <= yTop - 0.03; y += 0.03) seam.push(torsoAt(Math.PI, y, off + 0.002));
  S.tube(seam, 0.003, { radial: 4 }, tint(outer, 0.72), torsoWeights);
  if (!jacket) {
    const strap = [];
    for (let k = 0; k <= 8; k++) strap.push(torsoAt(Math.PI - 0.42 + 0.84 * (k / 8), 0.49, off + 0.006));
    S.tube(strap, 0.012, { radial: 6, squash: [0.35, 1] }, tint(outer, 0.88), torsoWeights);
    S.slot = 1;
    const bc = torsoAt(Math.PI + 0.1, 0.49, off + 0.012), bn = torsoNormal(Math.PI + 0.1, 0.49);
    const br = [];
    for (let i = 0; i < 12; i++) {
      const f = (i / 12) * TAU;
      br.push(bc.clone().add(V(Math.cos(f) * 0.014, Math.sin(f) * 0.011, 0)));
    }
    S.tube(br, 0.0032, { radial: 4, closed: true }, C('#c9a24c'), torsoWeights);
    S.slot = 0;
    void bn;
  }
  // декоративные пуговицы на левой полочке
  if (!jacket) {
    for (const v of [0.18, 0.42, 0.66]) {
      const p = vestPt(0, v, off + 0.006, V());
      const a = Math.atan2(p.x, -p.z) - 0.06;
      S.disc(torsoAt(a, p.y, off + 0.006), torsoNormal(a, p.y), 0.0105, 0.007, tint(trim, 1.25), torsoWeights, 10);
    }
  }
}

// Воротник рубашки: стойка и два уголка на груди.
function buildCollar(ctx) {
  const { S, shirt, style } = ctx;
  const jacket = style === 'jacket';
  const band = [];
  for (let i = 0; i <= 26; i++) {
    const a = 0.28 + (TAU - 0.56) * (i / 26);
    band.push(torsoAt(a, 0.8, 0.02));
  }
  S.tube(band, 0.024, { radial: 7, squash: [0.55, 1.15] }, tint(shirt, 0.97), torsoWeights);
  for (const s of [-1, 1]) {
    // треугольник: у шеи сзади, у шеи спереди, кончик на груди
    const A = [s * 0.3, 0.808], Bp = [s * 0.95, 0.79], T = [s * 0.2, 0.71];
    const pt = (u, v, off, out) => {
      const a = (A[0] + (Bp[0] - A[0]) * u) * (1 - v) + T[0] * v;
      const y = (A[1] + (Bp[1] - A[1]) * u) * (1 - v) + T[1] * v;
      return torsoAt(a, y, off, out);
    };
    const base = jacket ? 0.036 : 0.026;
    S.grid((u, v, out) => pt(u, v, base + 0.006 * Math.sin(Math.PI * u) * (1 - v), out), 6, 7, { wrap: false, flip: s > 0 }, shirt, torsoWeights);
    const rim = [];
    for (let k = 0; k <= 6; k++) rim.push(pt(k / 6, 0, base, V()));
    for (let k = 1; k <= 7; k++) rim.push(pt(1 - k / 7, k / 7, base, V()));
    for (let k = 1; k < 7; k++) rim.push(pt(0, 1 - k / 7, base, V()));
    S.tube(rim, 0.0045, { radial: 5, closed: true }, tint(shirt, 0.88), torsoWeights);
  }
}

function buildHoodie(ctx) {
  const { S, outer, trim, shirt } = ctx;
  // резинка по низу
  const hem = [];
  for (let i = 0; i < 48; i++) hem.push(torsoAt((i / 48) * TAU, 0.375, 0.006));
  S.tube(hem, 0.019, { radial: 7, closed: true, ref: V(0, 1, 0), squash: [0.6, 1.2] }, tint(outer, 0.86), torsoWeights);
  // карман-кенгуру
  torsoPatch(S, 0, 0.475, 0.25, 0.12, -0.002, 0.013, 12, 6, tint(outer, 0.95), 0.18);
  const rim = [];
  for (let k = 0; k <= 10; k++) rim.push(torsoAt(-0.45 + 0.9 * (k / 10), 0.535, 0.011));
  S.tube(rim, 0.0045, { radial: 5 }, trim, torsoWeights);
  for (const s of [-1, 1]) {
    const side = [];
    for (let k = 0; k <= 6; k++) side.push(torsoAt(s * (0.45 + 0.08 * (k / 6)), 0.535 - 0.11 * (k / 6), 0.012));
    S.tube(side, 0.0045, { radial: 5 }, trim, torsoWeights);
  }
  // капюшон, лежащий на спине
  const hood = [];
  for (let i = 0; i <= 18; i++) {
    const a = 0.55 + (TAU - 1.1) * (i / 18);
    const back = Math.max(0, -Math.cos(a));
    hood.push(torsoAt(a, 0.79 - 0.05 * back, 0.035 + 0.035 * back));
  }
  S.tube(hood, hood.map((_, i) => 0.03 + 0.035 * Math.sin(Math.PI * (i / 18))), { radial: 10 }, tint(outer, 0.97), torsoWeights);
  // горловина и шнурки
  const neck = [];
  for (let i = 0; i < 30; i++) neck.push(torsoAt((i / 30) * TAU, 0.805, 0.012));
  S.tube(neck, 0.016, { radial: 6, closed: true, ref: V(0, 1, 0) }, tint(outer, 0.88), torsoWeights);
  for (const s of [-1, 1]) {
    const pts = [torsoAt(s * 0.16, 0.79, 0.016), torsoAt(s * 0.17, 0.72, 0.014), torsoAt(s * 0.15, 0.64, 0.016)];
    S.tube(pts, 0.0055, { radial: 5 }, C(ctx.o.shirt), torsoWeights);
    S.tube([pts[2], torsoAt(s * 0.15, 0.62, 0.017)], 0.0075, { radial: 6 }, tint(trim, 0.9), torsoWeights);
  }
  void shirt;
}

// ---------- руки ----------

function buildArms(ctx) {
  const { S, shirt, outer, skin, style } = ctx;
  const sleeve = style === 'vest' ? shirt : outer;
  const cuffC = style === 'hoodie' ? tint(outer, 0.85) : tint(sleeve, style === 'vest' ? 0.94 : 1.08);
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
    S.ellipsoid(Sh.clone().add(V(-s * 0.012, 0.004, 0)), [0.081, 0.08, 0.079], 14, 10, sleeveCol, armW);
    // рукав
    const sl = [-0.02, 0.04, 0.09, UPPER, UPPER + 0.024].map((d) => Sh.clone().addScaledVector(a, d));
    S.tube(sl, [0.079, 0.077, 0.072, 0.066, 0.065], { radial: 14, caps: [true, false] }, sleeveCol, armW);
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
    ringAt(UPPER + 0.012, 0.067, 0.013, tint(cuffC, 0.96), [1, 0.9]);
    ringAt(UPPER + 0.034, 0.064, 0.017, cuffC, [1, 0.85]);
    // предплечье
    const fa = [E.clone().addScaledVector(a, 0.01), E.clone().addScaledVector(a, 0.07), W.clone().addScaledVector(a, 0.005)];
    S.tube(fa, [0.05, 0.048, 0.044], { radial: 12, caps: [false, false] }, skin, (x, y, z) => {
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
  const { S, pants, shoes } = ctx;
  const sole = C('#2c2119');
  const lace = C('#efe3c6');
  for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
    const x = s * HIP_X;
    const bT = B[`thigh${n}`], bS = B[`shin${n}`], bF = B[`foot${n}`];
    const legW = (px, y) => {
      const k = sstep(0.27, 0.205, y), p = sstep(0.36, 0.43, y) * 0.5;
      return [B.pelvis, p, bT, (1 - k) * (1 - p), bS, k];
    };
    const legCol = (px, y, z, out) => {
      out.copy(pants).multiplyScalar(0.8 + 0.2 * sstep(0.3, 0.2, y));
      // складки под коленом
      out.multiplyScalar(1 - 0.07 * Math.exp(-(((y - 0.225) / 0.012) ** 2)) * sstep(-0.02, 0.06, z));
    };
    const pts = [V(x, 0.43, 0.01), V(x * 1.01, 0.33, 0.004), V(x * 1.02, 0.24, -0.004), V(x * 1.04, 0.17, 0), V(x * 1.05, 0.13, 0.002)];
    const radii = [0.092, 0.088, 0.079, 0.075, 0.074];
    S.tube(pts, radii, { radial: 14, caps: [true, false] }, legCol, legW);
    // отворот
    for (const [y, R, tr, k] of [[0.155, 0.074, 0.013, 0.97], [0.132, 0.077, 0.016, 1.08]]) {
      const ring = [];
      for (let i = 0; i < 14; i++) {
        const f = (i / 14) * TAU;
        ring.push(V(x * 1.05 + Math.sin(f) * R, y, Math.cos(f) * R));
      }
      S.tube(ring, tr, { radial: 5, closed: true, ref: V(0, 1, 0), squash: [0.75, 1.1] }, tint(pants, k), (px, py) => legW(px, py));
    }
    // боковой шов
    const seam = [];
    for (let y = 0.36; y >= 0.16; y -= 0.025) {
      const r = y > 0.24 ? 0.079 + (0.088 - 0.079) * ((y - 0.24) / 0.09) : 0.075 + 0.004 * ((y - 0.17) / 0.07);
      seam.push(V(x * 1.02 + s * (Math.min(0.093, r) + 0.0005), y, 0));
    }
    S.tube(seam, 0.0026, { radial: 4 }, tint(pants, 0.7), legW);

    // ботинок: голенище, носок с подъёмом, подошва
    const fx = s * 0.116;
    const shoeCol = (px, py, pz, out) => {
      out.copy(shoes).multiplyScalar(0.88 + 0.12 * sstep(0.02, 0.09, py));
      if (pz < -0.1) out.multiplyScalar(1.1); // носок посветлее (затёртый)
    };
    S.ellipsoid(V(fx, 0.052, -0.046), [0.069, 0.056, 0.118], 18, 12, shoeCol, bF, {
      deform: (d, out) => {
        const toe = sstep(-0.2, -0.9, d.z);
        out.x *= 1 + 0.08 * toe - 0.12 * sstep(0.3, 0.9, d.z);
        out.y *= 1 + 0.12 * toe;
        if (out.y < -0.03) out.y = -0.03 + (out.y + 0.03) * 0.25;
      },
    });
    S.tube([V(fx, 0.04, 0.008), V(fx, 0.1, 0.006), V(fx, 0.142, 0.004)], [0.062, 0.061, 0.06], { radial: 14, caps: [false, true] }, shoeCol,
      (px, py) => [bF, 1 - sstep(0.115, 0.15, py), bS, sstep(0.115, 0.15, py)]);
    // язычок
    S.ellipsoid(V(fx, 0.122, -0.052), [0.03, 0.03, 0.012], 8, 6, tint(shoes, 1.12), bF, { rot: [-0.35, 0, 0] });
    // подошва и рант
    S.ellipsoid(V(fx, 0.014, -0.047), [0.077, 0.019, 0.13], 18, 6, (px, py, pz, out) => out.copy(sole).multiplyScalar(py > 0.02 ? 1.5 : 1), bF, {
      deform: (d, out) => { out.y = Math.max(out.y, -0.014); out.x *= 1 + 0.06 * sstep(-0.2, -0.9, d.z); },
    });
    // шнуровка: три крестика и бантик
    for (let k = 0; k < 3; k++) {
      const y = 0.083 + k * 0.016, z = -0.072 + k * 0.012;
      S.tube([V(fx - 0.022, y, z + 0.004), V(fx + 0.022, y + 0.012, z + 0.006)], 0.0042, { radial: 4, capSegs: 1 }, lace, bF);
      S.tube([V(fx + 0.022, y, z + 0.004), V(fx - 0.022, y + 0.012, z + 0.006)], 0.0042, { radial: 4, capSegs: 1 }, lace, bF);
    }
    for (const t of [-1, 1]) {
      const c = V(fx + t * 0.016, 0.138, -0.045);
      const loop = [];
      for (let i = 0; i < 8; i++) {
        const f = (i / 8) * TAU;
        loop.push(c.clone().add(V(Math.cos(f) * 0.014, Math.sin(f) * 0.008, -0.004 * Math.sin(f))));
      }
      S.tube(loop, 0.0038, { radial: 4, closed: true }, lace, bF);
    }
  }
}

// ---------- голова и лицо ----------

function buildHead(ctx) {
  const { S, skin, hair, look } = ctx;
  const facial = look.facial || 0;
  const blush = mix(skin, '#ff7b7b', 0.22);
  const stubble = mix(skin, tint(hair, 0.55), 0.38);
  const dir = new THREE.Vector3();
  S.grid((u, v, out) => {
    sphereDir(u, v, dir);
    return out.copy(dir).multiplyScalar(headRadius(dir.x, dir.y, dir.z)).add(HEAD_C);
  }, 42, 30, { poles: true }, (x, y, z, out) => {
    _d.set(x, y, z).sub(HEAD_C).normalize();
    out.copy(skin);
    // румянец на щеках
    const ch = Math.exp(-((_d.y + 0.2) ** 2) / 0.012) * Math.exp(-((Math.abs(_d.x) - 0.5) ** 2) / 0.018) * Math.max(0, -_d.z);
    out.lerp(blush, Math.min(1, ch * 1.4));
    if (facial === 3) {
      const m = beardMask(_d);
      out.lerp(stubble, m * (0.75 + 0.25 * hash3(Math.round(x * 300), Math.round(y * 300), Math.round(z * 300))));
    }
    // мягкая тень снизу и у шеи
    out.multiplyScalar(0.84 + 0.16 * sstep(-0.9, -0.35, _d.y));
  }, B.head);

  // уши
  for (const s of [-1, 1]) {
    const c = headPoint(s * FACE.earDir.x, FACE.earDir.y, FACE.earDir.z, 0.0);
    const R = [0.028, 0.062, 0.045];
    S.ellipsoid(c, R, 14, 12, (x, y, z, out) => {
      const lx = (x - c.x) * s, ly = (y - c.y) / R[1], lz = (z - c.z) / R[2];
      const cup = lx > 0 ? Math.exp(-(ly * ly + lz * lz) / 0.35) : 0;
      out.copy(skin).multiplyScalar(1 - 0.28 * cup).lerp(blush, 0.25);
    }, B.head, {
      rot: [0.12, s * 0.28, 0],
      deform: (d, out) => {
        const cup = Math.max(0, d.x * s) * Math.exp(-(d.y * d.y + d.z * d.z) / 0.35);
        out.x -= s * 0.026 * cup;
      },
    });
  }

  // нос-картошка с крыльями и ноздрями
  const nc = headPoint(0, FACE.noseDir.y, FACE.noseDir.z, 0.028);
  const noseC = mix(skin, '#ff8a7a', 0.16);
  S.ellipsoid(nc, [0.06, 0.053, 0.052], 16, 12, (x, y, z, out) => {
    out.copy(noseC).multiplyScalar(0.95 + 0.07 * sstep(nc.y - 0.04, nc.y + 0.03, y));
  }, B.head, {
    deform: (d, out) => { if (d.y < 0) out.x *= 1 + 0.14 * -d.y; },
  });
  for (const s of [-1, 1]) {
    S.ellipsoid(nc.clone().add(V(s * 0.044, -0.025, 0.015)), [0.028, 0.025, 0.026], 10, 8, noseC, B.head);
    S.ellipsoid(nc.clone().add(V(s * 0.021, -0.044, -0.008)), [0.011, 0.0065, 0.011], 8, 5, C('#5a2a22'), B.head);
  }

  buildEyes(ctx);
  buildMouth(ctx);
  buildBrows(ctx);
  if (facial === 1 || facial === 2) buildMustache(ctx);
  if (facial === 2) buildBeard(ctx);
}

function buildEyes(ctx) {
  const { S, skin, look } = ctx;
  const iris = C(EYES[(look.skin + look.hair * 2 + look.outfit) % EYES.length]);
  const lidC = tint(skin, 0.97);
  for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
    const c = eyeCenter(s);
    const rot = [0.06, -s * 0.2, 0];
    const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(...rot));
    const fwd = V(0, 0, -1).applyQuaternion(q);
    const R = [0.041, 0.045, 0.03];
    S.slot = 1;
    S.ellipsoid(c, R, 14, 10, C('#fbf8f2'), B.head, { rot });
    // радужка, зрачок — на кости глаза (двигаются), блик — на голове (стоит на месте)
    const front = c.clone().addScaledVector(fwd, R[2]);
    S.ellipsoid(front.clone().addScaledVector(fwd, -0.0035), [0.0235, 0.026, 0.006], 12, 6, (x, y, z, out) => {
      const r = Math.hypot(x - front.x, y - front.y, (z - front.z) * 0.3) / 0.025;
      out.copy(iris).multiplyScalar(0.6 + 0.6 * sstep(1.0, 0.45, r));
    }, B[`eye${n}`], { rot });
    S.ellipsoid(front.clone().addScaledVector(fwd, 0.0003), [0.0115, 0.0125, 0.004], 8, 5, C('#0d0b0c'), B[`eye${n}`], { rot });
    const up = V(0, 1, 0).applyQuaternion(q), rt = V(1, 0, 0).applyQuaternion(q);
    S.ellipsoid(front.clone().addScaledVector(up, 0.011).addScaledVector(rt, -0.008).addScaledVector(fwd, 0.002), [0.0062, 0.0062, 0.003], 8, 6, C('#ffffff'), B.head, { rot });
    S.ellipsoid(front.clone().addScaledVector(up, -0.008).addScaledVector(rt, 0.008).addScaledVector(fwd, 0.0015), [0.003, 0.003, 0.002], 6, 4, C('#ffffff'), B.head, { rot });
    S.slot = 0;
    // верхнее веко (кость века поворачивается вниз — моргание) и ресничный край
    const LR = [0.0445, 0.049, 0.0345];
    const edge = 1.08; // полярный угол края века от макушки глаза
    const lidPt = (u, v, out, extra = 0) => {
      const th = edge * (1 - v), ph = u * TAU;
      out.set(Math.sin(th) * Math.sin(ph) * (LR[0] + extra), Math.cos(th) * (LR[1] + extra), -Math.sin(th) * Math.cos(ph) * (LR[2] + extra));
      return out.applyQuaternion(q).add(c);
    };
    S.grid((u, v, out) => lidPt(u, v, out), 16, 5, { wrap: true }, (x, y, z, out) => out.copy(lidC).multiplyScalar(y > c.y + 0.03 ? 1 : 0.94), B[`lid${n}`]);
    // ресничный край — передняя дуга
    const arc = [];
    for (let i = -8; i <= 8; i++) arc.push(lidPt(((i / 30) + 1) % 1, 0, V(), 0.0012));
    S.tube(arc, 0.0034, { radial: 5 }, C('#3a2420'), B[`lid${n}`]);
    // нижнее веко — неподвижное
    S.grid((u, v, out) => {
      const th = Math.PI - 0.62 * (1 - v), ph = u * TAU;
      out.set(Math.sin(th) * Math.sin(ph) * LR[0], Math.cos(th) * LR[1], -Math.sin(th) * Math.cos(ph) * LR[2]);
      return out.applyQuaternion(q).add(c);
    }, 16, 3, { wrap: true, flip: true }, tint(skin, 0.98), B.head);
  }
}

// Рот: улыбка-линия + «D»-образный проём с зубами и языком. Нижняя часть проёма висит на кости jaw
// (в центре головы): поворот вверх складывает проём в линию по поверхности лица.
export const MOUTH_DEPTH = 0.2; // угловая глубина проёма, рад
function buildMouth(ctx) {
  const { S, skin } = ctx;
  const my = FACE.mouthDir.y, mz = FACE.mouthDir.z;
  const W = 0.2;
  const topDir = (t, out) => out.set(t * W, my + 0.035 * t * t, mz);
  const depth = (t) => MOUTH_DEPTH * Math.max(0, 1 - t * t) ** 0.75;
  // направление на нижнем крае: опускаем по сфере на угол depth
  const dirAt = (t, f, out) => {
    topDir(t, out).normalize();
    const ang = depth(t) * f;
    // поворот вокруг оси X, проходящей через центр головы
    const y = out.y * Math.cos(ang) + out.z * Math.sin(ang);
    const z = -out.y * Math.sin(ang) + out.z * Math.cos(ang);
    return out.set(out.x, y, z);
  };
  // вес челюсти = доля угловой глубины точки: при закрытии всё ложится ровно на линию улыбки
  const jawW = (x, f) => {
    const t = Math.min(1, Math.max(-1, (x - HEAD_C.x) / 0.056));
    const w = f * Math.max(0, 1 - t * t) ** 0.75;
    return [B.head, 1 - w, B.jaw, w];
  };
  const pt = (t, f, off, out) => { dirAt(t, f, out); return headPoint(out.x, out.y, out.z, off, out); };
  // проём
  S.grid((u, v, out) => pt(u * 2 - 1, v, 0.0025, out), 16, 6, { wrap: false, flip: true }, C('#4a1418'), (x, y, z, t) => jawW(x, t));
  // зубы и язык складываются вместе с проёмом
  S.grid((u, v, out) => pt((u * 2 - 1) * 0.62, v * 0.3, 0.0034, out), 10, 2, { wrap: false, flip: true }, C('#f7f2e8'), (x, y, z, t) => jawW(x, t * 0.3));
  S.grid((u, v, out) => pt((u * 2 - 1) * 0.5, 0.62 + 0.38 * v, 0.0034, out), 10, 3, { wrap: false, flip: true }, C('#d95f68'), (x, y, z, t) => jawW(x, 0.62 + 0.38 * t));
  // нижняя губа и линия улыбки
  const lip = [], smile = [];
  for (let i = 0; i <= 16; i++) {
    const t = (i / 16) * 2 - 1;
    lip.push(pt(t * 0.92, 1, 0.0035, V()));
    smile.push(pt(t * 1.04, 0, 0.0036, V()));
  }
  S.tube(lip, lip.map((_, i) => 0.0035 + 0.003 * Math.sin(Math.PI * i / 16)), { radial: 5 }, mix(skin, '#e06a62', 0.3), (x) => jawW(x, 1));
  S.tube(smile, smile.map((_, i) => 0.0034 + 0.0018 * Math.sin(Math.PI * i / 16)), { radial: 5 }, C('#5a2620'), B.head);
  // ямочки в уголках
  for (const s of [-1, 1]) S.tube([pt(s * 1.04, 0, 0.003, V()), pt(s * 1.12, -0.05, 0.003, V())], 0.003, { radial: 4 }, tint(skin, 0.8), B.head);
}

function buildBrows(ctx) {
  const { S, hair } = ctx;
  const col = tint(hair, 0.85);
  for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
    const pts = [], radii = [];
    for (let i = 0; i <= 6; i++) {
      const t = i / 6;
      const dx = s * (0.17 + 0.3 * t), dy = 0.32 + 0.05 * Math.sin(Math.PI * t) - 0.04 * t;
      pts.push(headPoint(dx, dy, -0.9, 0.009));
      radii.push(0.011 + 0.006 * Math.sin(Math.PI * (0.35 + 0.6 * t)) - 0.003 * t);
    }
    S.tube(pts, radii, { radial: 6, squash: [0.6, 1] }, col, B[`brow${n}`]);
  }
}

function beardMask(d) {
  const ax = Math.abs(d.x);
  // ниже линии челюсти спереди, выше по бокам (бакенбарды)
  const line = -0.24 + 0.32 * sstep(0.45, 0.95, ax);
  let m = sstep(line + 0.03, line - 0.03, d.y);
  m *= sstep(0.25, -0.05, d.z) ; // только спереди и по бокам
  // вокруг рта — пусто
  const mx = d.x / 0.2, my2 = (d.y - FACE.mouthDir.y + 0.035) / 0.115;
  m *= sstep(0.8, 1.15, Math.sqrt(mx * mx + my2 * my2));
  return m;
}

function buildMustache(ctx) {
  const { S, hair } = ctx;
  for (const s of [-1, 1]) {
    const pts = [], radii = [];
    for (let i = 0; i <= 7; i++) {
      const t = i / 7;
      const dx = s * (0.015 + 0.2 * t), dy = FACE.mouthDir.y + 0.085 - 0.06 * t * t;
      pts.push(headPoint(dx, dy, FACE.mouthDir.z, 0.012 - 0.006 * t));
      radii.push(0.015 + 0.012 * Math.sin(Math.PI * (0.2 + 0.7 * t)) * (1 - 0.6 * t));
    }
    S.tube(pts, radii, { radial: 8, squash: [0.75, 1] }, (x, y, z, out, t) => out.copy(hair).multiplyScalar(0.9 + 0.2 * t), B.head);
  }
}

// Борода: поверхность от линии щёк до подбородка. Верхний край — гладкая кривая: под нижней губой
// спереди и до уровня скул у ушей; к краям толщина сходит на нет, у края — аккуратный бортик.
function buildBeard(ctx) {
  const { S, hair } = ctx;
  const dir = new THREE.Vector3();
  const lip = FACE.mouthDir.y - 0.1;
  const AZ = 1.62;
  S.grid((u, v, out) => {
    const az = (u - 0.5) * 2 * AZ;
    const a = Math.abs(az);
    const topDy = lip + (0.04 - lip) * sstep(0.2, 1.25, a);
    const e0 = Math.asin(topDy), e1 = -1.3;
    const el = e0 + (e1 - e0) * v;
    dir.set(Math.cos(el) * Math.sin(az), Math.sin(el), -Math.cos(el) * Math.cos(az));
    const chin = sstep(-0.35, -0.9, dir.y) * sstep(1.2, 0.3, a);
    const taper = sstep(AZ, AZ - 0.35, a);
    const lumps = 0.005 * Math.sin(az * 13 + el * 5) * Math.sin(el * 11);
    const rim = sstep(0, 0.09, v) * (0.6 + 0.4 * sstep(0.08, 0.3, v));
    return headPoint(dir.x, dir.y, dir.z, 0.0015 + (0.02 + 0.022 * chin + lumps) * rim * taper, out);
  }, 32, 10, { wrap: false, flip: true }, (x, y, z, out, t) => {
    out.copy(hair).multiplyScalar((0.82 + 0.28 * hash3(Math.round(x * 160), Math.round(y * 160), Math.round(z * 160))) * (0.85 + 0.15 * sstep(0, 0.3, t)));
  }, B.head);
}

// ---------- причёски ----------

// Линия роста волос (dy) по азимуту; f = |az|/PI: 0 — лоб, 1 — затылок. Обходит уши сверху.
function hairEdge(az, style) {
  const f = Math.abs(az) / Math.PI;
  const front = style === 4 ? 0.5 : style === 2 ? 0.34 : style === 5 ? 0.46 : 0.42;
  let e = front;
  e += (0.27 - front) * sstep(0.12, 0.26, f);  // виски
  e += -0.4 * sstep(0.26, 0.32, f);            // бакенбарды
  e += 0.44 * sstep(0.34, 0.41, f);            // над ухом
  e += -0.55 * sstep(0.6, 0.7, f);             // за ухом вниз
  e += -0.18 * sstep(0.7, 0.95, f);            // затылок
  if (style === 0 || style === 1) e += 0.035 * Math.sin(az * 24) * sstep(0.2, 0.05, f); // чёлка зубчиками
  if (style === 2) e += 0.03 * Math.sin(az * 20);                                       // кудрявый край
  return e;
}

function buildHair(ctx) {
  const { S, hair, look } = ctx;
  const style = look.hairStyle || 0;
  const capped = look.hat > 0;
  const b0 = hatBand(look.hat);
  const dir = new THREE.Vector3();
  const strands = tint(hair, 1.28);
  const NU = 60, NV = 14;
  // толщина пряди по направлению
  const thick = (d, az, el) => {
    switch (style) {
      case 1: return 0.017 + 0.006 * sstep(0, 0.9, d.y) + 0.004 * Math.sin(az * 30 + d.y * 4)
        + 0.055 * Math.exp(-((d.y - 0.62) ** 2) / 0.03) * Math.max(0, -d.z) * (1 + 0.5 * d.x);
      case 2: return 0.03 + 0.014 * Math.sin(az * 11 + 1.3) * Math.sin(el * 10) + 0.01 * sstep(0.2, -0.2, d.y);
      case 3: return 0.026 + 0.008 * Math.sin(az * 9) * Math.sin(el * 7);
      case 4: return 0.013 + 0.0025 * Math.sin(az * 40);
      case 5: return 0.0065;
      default: return 0.017 + 0.007 * sstep(0, 0.9, d.y) + 0.0028 * Math.sin(az * 46 + el * 8);
    }
  };
  // верхняя граница: под шляпой волосы заканчиваются чуть выше ленты
  const topDy = (az) => (capped ? Math.min(1, b0 + 0.11 * Math.cos(az) + 0.05) : 1);
  S.grid((u, v, out) => {
    const az = u * TAU;
    const f = Math.abs(((az + Math.PI) % TAU) - Math.PI) / Math.PI;
    let lo, hi;
    if (style === 3) { // лысина с венчиком: полоса над ушами и по затылку
      lo = 0.27 - 0.63 * sstep(0.58, 0.7, f);
      hi = 0.36 - 0.18 * sstep(0.55, 0.75, f);
      hi = lo + (hi - lo) * sstep(0.3, 0.42, f);
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
    if (style === 3 || capped) rim *= sstep(1, 1 - 1.5 / NV, v); // верхний край тоже сходит к коже
    if (capped) t *= 0.8;
    return headPoint(dir.x, dir.y, dir.z, 0.0015 + t * rim, out);
  }, NU, NV, { wrap: true }, (x, y, z, out, tv) => {
    _d.set(x, y, z).sub(HEAD_C).normalize();
    out.copy(hair);
    const az = Math.atan2(_d.x, -_d.z);
    out.lerp(strands, 0.28 * (0.5 + 0.5 * Math.sin(az * 46 + _d.y * 8)) * sstep(0.1, 0.8, _d.y));
    out.multiplyScalar((0.8 + 0.2 * sstep(-0.3, 0.6, _d.y)) * (0.82 + 0.18 * sstep(0, 0.25, tv)));
  }, B.head);

  if (style === 4) { // хвостик
    const p0 = headPoint(0, 0.18, 1, -0.01);
    const pts = [p0, p0.clone().add(V(0, -0.02, 0.06)), p0.clone().add(V(0, -0.1, 0.1)), p0.clone().add(V(0, -0.2, 0.085))];
    S.tube(pts, [0.04, 0.046, 0.038, 0.012], { radial: 10 }, (x, y, z, out, t) => out.copy(hair).multiplyScalar(1 - 0.15 * t), B.tail);
    const tc = p0.clone().add(V(0, -0.006, 0.032));
    const ring = [];
    for (let i = 0; i < 12; i++) {
      const f = (i / 12) * TAU;
      ring.push(tc.clone().add(V(Math.cos(f) * 0.036, Math.sin(f) * 0.036 * 0.9, 0)));
    }
    S.tube(ring, 0.011, { radial: 6, closed: true }, C(ctx.o.vest), B.tail);
  }
  if (style === 5 && !capped) { // ёжик: короткие иголки на макушке
    const g = new THREE.ConeGeometry(0.013, 0.034, 5);
    g.translate(0, 0.012, 0);
    for (let i = 0; i < 70; i++) {
      const y = 1 - (i + 0.5) / 70 * 1.25;
      if (y < -0.2) break;
      const r = Math.sqrt(Math.max(0, 1 - y * y)), ph = i * 2.39996;
      dir.set(r * Math.sin(ph), y, -r * Math.cos(ph));
      const az = Math.atan2(dir.x, -dir.z);
      if (dir.y < hairEdge(az, 5) + 0.08) continue;
      const p = headPoint(dir.x, dir.y, dir.z, 0.004);
      const n = dir.clone().add(V(0, 0.15, 0.35)).normalize();
      const m = new THREE.Matrix4().compose(p, new THREE.Quaternion().setFromUnitVectors(V(0, 1, 0), n), V(1, 1, 1));
      S.geo(g.clone(), m, (x, yy, z, out) => out.copy(hair).multiplyScalar(0.9 + 0.35 * sstep(p.y, p.y + 0.04, yy)), B.head);
    }
    g.dispose();
  }
}

// ---------- шляпы ----------

// Высота ленты шляпы (dy на лбу); у каждой шляпы своя посадка.
function hatBand(kind) {
  return [1, 0.27, 0.29, 0.22, 0.3, 0.28, 0.3][kind] ?? 1;
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

// Яркий вариант цвета наряда для шапок: слишком тёмные оттенки осветляем.
function lively(hex, warm = '#c8553d') {
  const c = C(hex);
  const l = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
  return l < 0.05 ? mix(c, warm, 0.75) : c;
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
  const { S, look, o } = ctx;
  const kind = look.hat;
  if (!kind) { ctx.top = 1.32; return; }
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
      brim(1.9, 0.02, felt, tint(felt, 0.7), (p, f) => {
        const lx = (p.x - center.x) / 0.48;
        p.y += 0.085 * f * f * lx * lx * 1.6;
        p.y -= 0.025 * f * f * (1 - Math.min(1, lx * lx * 4));
      }, 6);
      S.slot = 1;
      const pc = outward(ring[10], 1.03).addScaledVector(axis, 0.022);
      S.disc(pc, pc.clone().sub(center).projectOnPlane(axis).normalize(), 0.014, 0.005, C('#d8d8de'), B.head, 12);
      S.slot = 0;
      ctx.top = center.y + 0.2;
      break;
    }
    case 3: { // каска с ремешком и сеткой
      const olive = C('#69773c');
      const prof = [[-0.012, 1.1], [0.035, 1.13], [0.085, 1.11], [0.135, 0.99], [0.175, 0.77], [0.198, 0.46], [0.205, 0]];
      crown(S, ring, axis, center, prof, (x, y, z, out) => {
        out.copy(olive).multiplyScalar(0.82 + 0.16 * sstep(center.y, center.y + 0.15, y) + 0.08 * (hash3(Math.round(x * 40), Math.round(y * 40), Math.round(z * 40)) - 0.5));
      });
      const rim = ring.map((p) => outward(p, 1.14).addScaledVector(axis, -0.014));
      S.tube(rim, 0.016, { radial: 6, closed: true, squash: [1, 0.7] }, tint(olive, 0.85), B.head);
      // сетка
      const netC = tint(olive, 0.62);
      for (let m = 0; m < 10; m++) {
        const i = Math.round((m / 10) * 40) % 40;
        const pts = prof.slice(0, -1).map(([h, s]) => outward(ring[i], s + 0.012).addScaledVector(axis, h + 0.004));
        pts.push(center.clone().addScaledVector(axis, 0.21));
        S.tube(pts, 0.0032, { radial: 3, caps: [false, false] }, netC, B.head);
      }
      for (const j of [1, 3, 4]) {
        const [h, s] = prof[j];
        S.tube(ring.filter((_, i) => i % 2 === 0).map((p) => outward(p, s + 0.014).addScaledVector(axis, h + 0.004)), 0.0032, { radial: 3, closed: true }, netC, B.head);
      }
      // ремешок под подбородком
      const strap = [outward(ring[10], 1.08), headPoint(0.92, -0.25, -0.2, 0.012), headPoint(0.55, -0.72, -0.4, 0.012), headPoint(0, -0.82, -0.57, 0.012),
        headPoint(-0.55, -0.72, -0.4, 0.012), headPoint(-0.92, -0.25, -0.2, 0.012), outward(ring[30], 1.08)];
      S.tube(strap, 0.0055, { radial: 5, squash: [0.5, 1.4] }, C('#4f4a32'), B.head);
      ctx.top = center.y + 0.2;
      break;
    }
    case 4: { // кепка: шесть клиньев со швами, пуговка, изогнутый козырёк
      const cap = lively(o.vest);
      const prof = [[-0.006, 1.01], [0.04, 1.0], [0.08, 0.93], [0.115, 0.72], [0.135, 0.4], [0.14, 0]];
      crown(S, ring, axis, center, prof, (x, y, z, out) => {
        const az = Math.atan2(x - center.x, -(z - center.z));
        out.copy(cap).multiplyScalar(0.86 + 0.14 * sstep(center.y, center.y + 0.1, y) + 0.04 * Math.cos(az * 6));
      });
      for (let m = 0; m < 6; m++) {
        const i = Math.round((m / 6) * 40 + 3.33) % 40;
        const pts = prof.slice(0, -1).map(([h, s]) => outward(ring[i], s + 0.004).addScaledVector(axis, h + 0.003));
        pts.push(center.clone().addScaledVector(axis, 0.142));
        S.tube(pts, 0.0028, { radial: 4, caps: [false, false] }, tint(cap, 0.72), B.head);
      }
      S.ellipsoid(center.clone().addScaledVector(axis, 0.142), [0.017, 0.009, 0.017], 10, 6, tint(cap, 0.8), B.head);
      ribbon(-0.004, 0.012, 1.012, tint(cap, 0.85));
      // козырёк: дуга ленты спереди, вытянутая вперёд и загнутая вниз по бокам
      const vis = C(o.trim);
      const vpt = (u, f, dy, out) => {
        const i = 40 - 9 + Math.round(u * 18);
        const p = outward(ring[((i % 40) + 40) % 40], 1.0);
        const fw = V(0, 0, -1);
        const k = Math.sin(Math.PI * u);
        p.addScaledVector(fw, f * 0.135 * k ** 0.5).addScaledVector(axis, -0.01 - 0.03 * f * (1 - k) - 0.018 * f + dy);
        return out.copy(p);
      };
      S.grid((u, v, out) => vpt(u, v, 0.006, out), 18, 5, { wrap: false, flip: true }, vis, B.head);
      S.grid((u, v, out) => vpt(u, v, -0.004, out), 18, 5, { wrap: false }, tint(vis, 0.6), B.head);
      const rimv = [];
      for (let i = 0; i <= 18; i++) rimv.push(vpt(i / 18, 1, 0.001, V()));
      S.tube(rimv, 0.0065, { radial: 5 }, tint(vis, 0.9), B.head);
      ctx.top = center.y + 0.15;
      break;
    }
    case 5: { // шапка: вязаные рубчики, отворот кольцами и помпон
      const knit = lively(o.vest), cuff = tint(lively(o.vest), 0.8), pom = C(o.shirt);
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
    case 6: { // колпак: полоски, загнутый кончик, меховая опушка и помпон
      const cA = lively(o.vest), cB = C('#fbf3e6');
      const n = 40, rows = 18;
      const spine = (t, out) => {
        const h = 0.5 * t;
        out.copy(center).addScaledVector(axis, h).add(V(0.13 * t * t, -0.2 * sstep(0.55, 1, t) * t, 0.12 * t * t));
        return out;
      };
      const rel = ring.map((p) => p.clone().sub(center));
      const sp = new THREE.Vector3();
      S.grid((u, v, out) => {
        const i = Math.round(u * n) % n;
        const t = v;
        spine(t, sp);
        const s = (1 - t) ** 1.25 * (1 + 0.1 * Math.sin(Math.min(1, t * 2.2) * Math.PI));
        out.copy(rel[i]).multiplyScalar(s).add(sp);
        return v > 0 ? clearHead(out, 0.02) : out;
      }, n, rows, { wrap: true }, (x, y, z, out, t) => {
        out.copy(Math.floor(t * 7 + 0.15) % 2 ? cB : cA).multiplyScalar(0.88 + 0.12 * t);
      }, B.head);
      S.tube(ring.filter((_, i) => i % 4 !== 3).map((p) => outward(p, 1.06)), 0.028, { radial: 6, closed: true, squash: [0.85, 1] }, (x, y, z, out) => {
        out.copy(cB).multiplyScalar(0.86 + 0.18 * hash3(Math.round(x * 250), Math.round(y * 250), Math.round(z * 250)));
      }, B.head);
      const tip = spine(1, V());
      S.ellipsoid(tip, [0.045, 0.045, 0.045], 12, 10, (x, y, z, out) => out.copy(cB).multiplyScalar(0.85 + 0.2 * hash3(Math.round(x * 300), Math.round(y * 300), Math.round(z * 300))), B.head);
      ctx.top = Math.max(center.y + 0.3, tip.y + 0.06);
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
  const frameC = [null, C('#151518'), C('#eadbb0'), C('#d0ad55')][kind];
  const lensC = [null, C('#0f0f14'), C('#3e76b4'), C('#4a2f20')][kind];
  const lensLow = [null, C('#18181f'), C('#6aa0d6'), C('#a8703f')][kind];
  const fr = kind === 3 ? 0.0038 : kind === 2 ? 0.0075 : 0.0062;
  const outline = (s, i, n) => { // точки контура линзы в локальных координатах (x наружу, y вверх)
    const f = (i / n) * TAU;
    if (kind === 1) return [Math.cos(f) * 0.05, Math.sin(f) * 0.05];
    if (kind === 2) {
      const ex = 0.058, ey = 0.042, p = 4;
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
    const c = V(s * 0.1, ec.y + 0.004, ec.z - 0.05);
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
    }, N, 3, { wrap: true, flip: s < 0 }, (x, y, z, out) => out.copy(lensLow).lerp(lensC, sstep(c.y - 0.04, c.y + 0.03, y)), B.head);
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
