// Скелет персонажа и общие пропорции. Позиции суставов заданы в позе покоя в пространстве модели:
// ноги на y = 0, лицом к −Z, правая сторона персонажа — +X. В покое у всех костей нулевой поворот,
// поэтому анимация крутит кости в «понятных» осях: x — вперёд/назад (+ вперёд для бедра и плеча),
// z — в сторону, y — вокруг вертикали.

import * as THREE from 'three';

// ---------- пропорции ----------
export const ARM_TILT = 0.42; // руки в покое разведены, чтобы не входить в пухлый живот
export const HIP_Y = 0.37, HIP_X = 0.11, THIGH = 0.145, SHIN = 0.145, ANKLE_Y = 0.08, FOOT_X = 0.118; // короткие толстые ноги
export const SHOULDER = new THREE.Vector3(0.265, 0.69, 0); // низкие покатые плечи
export const UPPER = 0.15, FORE = 0.14;
export const HEAD_C = new THREE.Vector3(0, 0.975, 0);
// подошва: пятка и носок относительно лодыжки
export const HEEL_Z = 0.06, TOE_Z = -0.155;

export const armDir = (side) => new THREE.Vector3(side * Math.sin(ARM_TILT), -Math.cos(ARM_TILT), 0);
// нормаль ладони в покое: смотрит внутрь, к телу
export const palmDir = (side) => new THREE.Vector3(-side * Math.cos(ARM_TILT), -Math.sin(ARM_TILT), 0);
// кисть: линия костяшек от запястья и центр «дырки» кулака (хват предмета) в системе кисти
export const KNUCKLE = 0.097;
export const gripLocal = (side) => armDir(side).multiplyScalar(0.075).addScaledVector(palmDir(side), 0.036);

export function sstep(e0, e1, x) {
  const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
  return t * t * (3 - 2 * t);
}

// Форма головы: радиус по направлению (dx, dy, dz) — единичный вектор от центра HEAD_C.
// Яйцо/груша как в референсе: макушка уже, внизу широкие брыли, шеи нет — низ лежит на воротнике.
export function headRadius(dx, dy, dz) {
  // макушка уже (яйцо), но купол круглый: сужение — через полуоси эллипсоида, а не вычитанием |dx|,
  // которое давало на темени «луковичный» конус
  const up = sstep(0, 0.8, dy);
  const rx = 0.288 - 0.016 * up, rz = 0.27 - 0.006 * up;
  const ry = dy > 0 ? 0.272 : 0.26;
  let r = 1 / Math.sqrt((dx / rx) ** 2 + (dy / ry) ** 2 + (dz / rz) ** 2);
  const horiz = Math.sqrt(Math.max(0, 1 - dy * dy));
  const front = Math.max(0, -dz);
  const frontS = sstep(0, 0.7, -dz); // гладко через dz = 0 — без гребня по темени
  r += 0.062 * sstep(0.2, -0.5, dy) * horiz * (0.7 + 0.3 * front);           // брыли и щёки внизу
  r += 0.012 * Math.exp(-((dy + 0.12) ** 2) / 0.04) * Math.exp(-((Math.abs(dx) - 0.55) ** 2) / 0.06) * front; // мягкие щёки
  r -= 0.014 * sstep(0, 0.8, dz) * sstep(-0.3, 0.5, dy);                     // затылок чуть площе
  r -= 0.016 * sstep(0.2, 0.85, dy) * frontS;                                // лоб уходит назад
  r -= 0.03 * sstep(-0.7, -1, dy);                                           // низ поджат (лежит на воротнике)
  return r;
}

const _d = new THREE.Vector3();
// Точка на поверхности головы по направлению + отступ по нормали (приближённо — по радиусу).
export function headPoint(dx, dy, dz, offset = 0, out = new THREE.Vector3()) {
  _d.set(dx, dy, dz).normalize();
  return out.copy(_d).multiplyScalar(headRadius(_d.x, _d.y, _d.z) + offset).add(HEAD_C);
}

// Ориентиры лица (в пространстве модели)
export const FACE = {
  eyeDir: new THREE.Vector3(0.34, 0.06, -0.94).normalize(), // правый глаз; левый — зеркально по x
  browDir: new THREE.Vector3(0.33, 0.22, -0.92).normalize(),
  noseDir: new THREE.Vector3(0, -0.06, -1).normalize(),
  mouthDir: new THREE.Vector3(0, -0.31, -0.95).normalize(),
  earDir: new THREE.Vector3(1, -0.04, 0.1).normalize(),
};
// глаз — маленький тёмный овал на поверхности лица; кость века сплющивает его при моргании
export const eyeCenter = (side) => headPoint(side * FACE.eyeDir.x, FACE.eyeDir.y, FACE.eyeDir.z, -0.003);
export const browCenter = (side) => headPoint(side * FACE.browDir.x, FACE.browDir.y, FACE.browDir.z, 0.006);
export const mouthTop = () => headPoint(0, FACE.mouthDir.y, FACE.mouthDir.z, 0.0035);

// ---------- кости ----------
const DEFS = [];
const def = (name, parent, p) => DEFS.push({ name, parent, pos: p.clone() });
const v = (x, y, z) => new THREE.Vector3(x, y, z);

def('root', null, v(0, 0, 0));
def('pelvis', 'root', v(0, HIP_Y, 0));
def('spine', 'pelvis', v(0, 0.5, 0.01));
def('belly', 'spine', v(0, 0.47, -0.16));
def('chest', 'spine', v(0, 0.64, 0.01));
def('neck', 'chest', v(0, 0.79, 0));
def('head', 'neck', v(0, 0.87, 0));
def('jaw', 'head', HEAD_C); // челюсть вращается вокруг центра головы — проём рта скользит по лицу
def('tail', 'head', headPoint(0, 0.18, 1, -0.01)); // хвостик причёски
for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
  def(`eye${n}`, 'head', eyeCenter(s));
  def(`lid${n}`, 'head', eyeCenter(s));
  def(`brow${n}`, 'head', browCenter(s));
}
for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
  const S = v(s * SHOULDER.x, SHOULDER.y, SHOULDER.z), a = armDir(s);
  const E = S.clone().addScaledVector(a, UPPER), W = E.clone().addScaledVector(a, FORE);
  const n0 = palmDir(s);
  def(`clav${n}`, 'chest', v(s * 0.1, 0.74, 0));
  def(`arm${n}`, `clav${n}`, S);
  def(`fore${n}`, `arm${n}`, E);
  def(`hand${n}`, `fore${n}`, W);
  def(`fing${n}`, `hand${n}`, W.clone().addScaledVector(a, KNUCKLE));
  def(`fing2${n}`, `fing${n}`, W.clone().addScaledVector(a, KNUCKLE + 0.027).addScaledVector(n0, 0.005));
  def(`thumb${n}`, `hand${n}`, W.clone().addScaledVector(a, 0.03).add(v(0, 0, -0.046)).addScaledVector(n0, 0.016));
}
for (const [s, n] of [[-1, 'L'], [1, 'R']]) {
  def(`thigh${n}`, 'pelvis', v(s * HIP_X, HIP_Y, 0));
  def(`shin${n}`, `thigh${n}`, v(s * HIP_X, HIP_Y - THIGH, 0));
  def(`foot${n}`, `shin${n}`, v(s * HIP_X, HIP_Y - THIGH - SHIN, 0));
}

export const BONE_DEFS = DEFS;
export const B = Object.fromEntries(DEFS.map((d, i) => [d.name, i]));

// Создаёт кости (THREE.Bone) с локальными позициями покоя и скелет с обратными матрицами.
export function createSkeleton() {
  const bones = DEFS.map((d) => {
    const b = new THREE.Bone();
    b.name = d.name;
    return b;
  });
  DEFS.forEach((d, i) => {
    if (d.parent) {
      const pi = B[d.parent];
      bones[pi].add(bones[i]);
      bones[i].position.subVectors(d.pos, DEFS[pi].pos);
    } else bones[i].position.copy(d.pos);
  });
  const inverses = DEFS.map((d) => new THREE.Matrix4().makeTranslation(-d.pos.x, -d.pos.y, -d.pos.z));
  return { bones, skeleton: new THREE.Skeleton(bones, inverses) };
}

// ---------- IK ----------
const _x = new THREE.Vector3(), _y = new THREE.Vector3(), _z = new THREE.Vector3();
const _m = new THREE.Matrix4();

// Кватернион ориентации кости: ось кости (dir) и направление «сгиба» (pole) — как в покое (d0, p0).
// Возвращает поворот, переводящий покойный базис в целевой.
export function aimQuat(d0, p0, dir, pole, out) {
  basisQuat(d0, p0, _q0);
  basisQuat(dir, pole, out);
  return out.multiply(_q0.invert());
}
const _q0 = new THREE.Quaternion();
function basisQuat(dir, pole, out) {
  _y.copy(dir).normalize().negate();               // ось кости смотрит по −Y базиса
  _z.copy(pole).addScaledVector(_y, -pole.dot(_y)); // полюс ортогонален оси
  if (_z.lengthSq() < 1e-8) _z.set(0, 0, 1).addScaledVector(_y, -_y.z);
  _z.normalize().negate();
  _x.crossVectors(_y, _z);
  _m.makeBasis(_x, _y, _z);
  return out.setFromRotationMatrix(_m);
}

// Двухзвенная IK: от корня a к цели t; длины l1, l2; pole — куда смотрит сустав (колено/локоть).
// Пишет направления звеньев в d1 и d2. Цель за пределами досягаемости — звенья выпрямляются.
const _t = new THREE.Vector3(), _pp = new THREE.Vector3();
export function solveTwoBone(a, t, l1, l2, pole, d1, d2) {
  _t.subVectors(t, a);
  let dist = _t.length();
  const maxD = (l1 + l2) * 0.9995, minD = Math.abs(l1 - l2) + 1e-3;
  dist = Math.min(maxD, Math.max(minD, dist));
  _t.normalize();
  // угол в корне по теореме косинусов
  const cosA = (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist);
  const sinA = Math.sqrt(Math.max(0, 1 - cosA * cosA));
  _pp.copy(pole).addScaledVector(_t, -pole.dot(_t));
  if (_pp.lengthSq() < 1e-8) _pp.set(0, 0, -1).addScaledVector(_t, -_t.z);
  _pp.normalize();
  d1.copy(_t).multiplyScalar(cosA).addScaledVector(_pp, sinA);
  // второй сустав: из точки сгиба в цель
  const kx = a.x + d1.x * l1, ky = a.y + d1.y * l1, kz = a.z + d1.z * l1;
  d2.set(a.x + _t.x * dist - kx, a.y + _t.y * dist - ky, a.z + _t.z * dist - kz).normalize();
  return dist;
}
