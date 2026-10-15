// Огромные мультяшные руки от первого лица — визитная карточка референса.
// Обе кисти — одна SkinnedMesh (1 draw call): пухлая ладонь с подушечками, костяшки, 4 пальца по 3 фаланги,
// большой палец из 2 фаланг, ногти посветлее, предплечье с закатанной манжетой цвета рукава.
// Рисуются отдельным проходом поверх мира (чистим глубину), поэтому не пролезают сквозь стены.

import * as THREE from 'three';
import { clamp, damp } from '../util/math.js';
import { Sculpt, V, sstep } from './sculpt.js';

const TAU = Math.PI * 2;
// пальцы: x основания (для правой кисти), длины фаланг, радиус
const FINGERS = [
  { x: -0.05, len: [0.035, 0.024, 0.02], r: 0.0192 },
  { x: -0.0165, len: [0.038, 0.026, 0.021], r: 0.0198 },
  { x: 0.0175, len: [0.036, 0.025, 0.02], r: 0.0188 },
  { x: 0.0505, len: [0.029, 0.02, 0.017], r: 0.0166 },
];
const KNUCKLE_Z = -0.112;
const THUMB0 = new THREE.Vector3(-0.054, -0.012, -0.046);
const THUMB_DIR = new THREE.Vector3(-0.55, -0.18, -0.82).normalize();
const THUMB_LEN = [0.034, 0.032, 0.028];
const PARTS = { skin: 0, palm: 1, nail: 2, knuckle: 3, crease: 4, sleeve: 5, cuff: 6 };

// ---------- позы ----------
// Ориентация кисти задаётся двумя направлениями в пространстве камеры: f — куда смотрят пальцы (−Z кисти),
// u — куда смотрит тыльная сторона (+Y кисти). Для левой кисти x зеркалится.
const P = (x, y, z, fx, fy, fz, ux, uy, uz, curl, thumb = 0.25, spread = 0.08) => ({
  pos: new THREE.Vector3(x, y, z), f: new THREE.Vector3(fx, fy, fz).normalize(), u: new THREE.Vector3(ux, uy, uz).normalize(), curl, thumb, spread,
});
// Кулак с предметом: g — «вверх по предмету» (ось кулака к указательному, −X кисти), u — тыльная сторона.
const G = (x, y, z, gx, gy, gz, ux, uy, uz, curl = 1, thumb = 0.85) => {
  const X = new THREE.Vector3(-gx, -gy, -gz).normalize();
  const Y = new THREE.Vector3(ux, uy, uz).addScaledVector(X, -(X.x * ux + X.y * uy + X.z * uz)).normalize();
  const Z = new THREE.Vector3().crossVectors(X, Y);
  return { pos: new THREE.Vector3(x, y, z), f: Z.negate(), u: Y, curl, thumb, spread: 0 };
};
const POSES = {
  idle: P(0.25, -0.2, -0.42, -0.25, 0.75, -0.6, 0.3, 0.35, 0.9, 0.28, 0.25),
  run: P(0.25, -0.21, -0.38, -0.4, 0.6, -0.7, 0.7, 0.3, 0.65, 0.95, 0.8, 0),
  air: P(0.29, -0.13, -0.44, 0, 1, -0.3, 0.2, 0.2, 1, 0.04, 0.05, 0.25),
  reach: P(0.13, -0.12, -0.56, -0.1, 0.25, -1, 0.1, 1, 0.25, 0.1, 0.1, 0.12),
  two: P(0.2, -0.17, -0.42, -0.15, 0.25, -1, 1, 0.15, 0.1, 0.5, 0.3, 0.02),
  one: G(0.17, -0.16, -0.42, 0, 1, 0.1, 0.9, 0, 0.45),
  bat: G(0.2, -0.2, -0.4, -0.5, 0.8, -0.35, 0.7, 0.3, 0.6),
  umbrella: G(0.22, 0.02, -0.46, 0.05, 1, 0.05, 0.85, 0, 0.5),
  wave: P(0.18, -0.05, -0.48, 0.1, 1, 0.05, 0, 0.1, 1, 0.05, 0.05, 0.2),
};

const _q = new THREE.Quaternion(), _q2 = new THREE.Quaternion();
const _m = new THREE.Matrix4();
const _x = new THREE.Vector3(), _y = new THREE.Vector3(), _z = new THREE.Vector3();
const _v = new THREE.Vector3(), _v2 = new THREE.Vector3();
const _e = new THREE.Euler();

function orient(f, u, out) {
  _z.copy(f).negate();
  _y.copy(u).addScaledVector(_z, -u.dot(_z)).normalize();
  _x.crossVectors(_y, _z);
  return out.setFromRotationMatrix(_m.makeBasis(_x, _y, _z));
}
const smoother = (t) => t * t * t * (t * (t * 6 - 15) + 10);

// ---------- геометрия одной кисти (правой; левая — зеркально) ----------
function buildHandParts(S, side, bi) {
  const sx = side; // зеркалим x
  const X = (v) => V(v.x * sx, v.y, v.z);
  // предплечье: от камеры к запястью
  const foreW = (x, y, z) => { const t = sstep(0.035, -0.015, z); return [bi.fore, 1 - t, bi.wrist, t]; };
  // к запястью предплечье становится плоским и широким, как ладонь, и заходит в неё — без шва
  const forePts = [V(0, -0.004, 0.5), V(0, -0.002, 0.3), V(0, 0, 0.12), V(0, 0.0, 0.03), V(0, -0.002, -0.02), V(0, -0.004, -0.05)];
  S.tube(forePts, [0.056, 0.054, 0.048, 0.044, 0.045, 0.04], { radial: 18, caps: [false, false], squash: [1.3, 0.8] },
    (x, y, z, out) => out.setRGB(PARTS.skin / 10, 0.9 + 0.1 * sstep(0.3, 0.05, z), 0), foreW);
  // рукав и закатанная манжета
  S.tube([V(0, 0, 0.62), V(0, 0, 0.3), V(0, 0, 0.2)], [0.07, 0.068, 0.066], { radial: 18, caps: [false, false], squash: [1, 1.1] },
    (x, y, z, out) => out.setRGB(PARTS.sleeve / 10, 0.92, 0), bi.fore);
  for (const [z, R, tr, part] of [[0.205, 0.06, 0.019, PARTS.cuff], [0.174, 0.057, 0.016, PARTS.cuff]]) {
    const ring = [];
    for (let i = 0; i < 24; i++) {
      const f = (i / 24) * TAU;
      ring.push(V(Math.cos(f) * R * 1.12, Math.sin(f) * R, z));
    }
    S.tube(ring, tr, { radial: 8, closed: true, ref: V(0, 0, 1), squash: [1, 0.85] },
      (x, y, zz, out) => out.setRGB(part / 10, z > 0.19 ? 1 : 0.94, 0), bi.fore);
  }
  // ладонь: «скруглённый брусок»
  const pc = V(0, 0.002, -0.058);
  const d = new THREE.Vector3();
  const palmW = (x, y, z) => {
    const t = sstep(-0.012, -0.03, z); // к запястью
    const k = sstep(KNUCKLE_Z + 0.02, KNUCKLE_Z - 0.006, z);
    return [bi.wrist, 1 - k, bi.f[Math.min(3, Math.max(0, Math.round((x * sx + 0.05) / 0.033)))][0], k * 0.5 * (1 - t * 0)];
  };
  S.grid((u, v, out) => {
    const lat = (v - 0.5) * Math.PI, lon = u * TAU;
    d.set(Math.cos(lat) * Math.sin(lon), Math.sin(lat), -Math.cos(lat) * Math.cos(lon));
    const n = 3.2;
    const r = (Math.abs(d.x) ** n + Math.abs(d.y) ** n + Math.abs(d.z) ** n) ** (-1 / n);
    const hy = d.y > 0 ? 0.03 : 0.04;
    const hx = 0.073 * (1 - 0.06 * sstep(0, -1, d.z));
    out.set(d.x * r * hx, d.y * r * hy, d.z * r * 0.066).add(pc);
    // ладонь чуть прогнута к центру
    if (d.y < 0) out.y += 0.01 * (1 - d.x * d.x) * (1 - d.z * d.z);
    out.x *= sx;
    return out;
  }, 24, 16, { poles: true, flip: sx < 0 }, (x, y, z, out) => out.setRGB((y < -0.012 ? PARTS.palm : PARTS.skin) / 10, 1, 0), palmW);
  // подушечки ладони и большого пальца
  for (const [x, z, rx, rz, ry] of [[-0.042, -0.042, 0.034, 0.04, 0.019], [0.045, -0.045, 0.03, 0.044, 0.016], [-0.02, -0.098, 0.034, 0.018, 0.012], [0.028, -0.098, 0.03, 0.018, 0.012]]) {
    S.ellipsoid(V(x * sx, -0.03, z), [rx, ry, rz], 12, 8, (xx, yy, zz, out) => out.setRGB(PARTS.palm / 10, 1.02, 0), bi.wrist);
  }
  // костяшки
  for (const fg of FINGERS) {
    S.ellipsoid(V(fg.x * sx, 0.022, KNUCKLE_Z + 0.006), [fg.r * 1.05, 0.009, fg.r * 1.1], 10, 6,
      (x, y, z, out) => out.setRGB(PARTS.knuckle / 10, 1, 0), bi.wrist);
  }
  // пальцы
  FINGERS.forEach((fg, i) => {
    const bx = fg.x * sx;
    const z0 = KNUCKLE_Z, z1 = z0 - fg.len[0], z2 = z1 - fg.len[1], z3 = z2 - fg.len[2];
    const pts = [V(bx, -0.002, z0 + 0.03), V(bx, 0, z0), V(bx, 0, z1), V(bx, 0, z2), V(bx, 0, z3 + fg.r * 0.4)];
    const r = fg.r;
    const fb = bi.f[i];
    S.tube(pts, [r * 1.05, r * 1.04, r * 0.98, r * 0.93, r * 0.86], { radial: 12, caps: [false, true], capSegs: 4 },
      (x, y, z, out) => {
        // складки на сгибах с внутренней стороны
        const crease = y < -r * 0.3 ? Math.max(Math.exp(-(((z - z1) / 0.004) ** 2)), Math.exp(-(((z - z2) / 0.004) ** 2))) : 0;
        out.setRGB((crease > 0.3 ? PARTS.crease : y < -r * 0.2 ? PARTS.palm : PARTS.skin) / 10, 1, 0);
      },
      (x, y, z) => {
        const a = sstep(z0 + 0.012, z0 - 0.008, z), b = sstep(z1 + 0.007, z1 - 0.007, z), c = sstep(z2 + 0.006, z2 - 0.006, z);
        return [bi.wrist, 1 - a, fb[0], a - b, fb[1], b - c, fb[2], c];
      });
    // ноготь
    S.ellipsoid(V(bx, r * 0.72, z3 + r * 0.95), [r * 0.62, 0.0035, r * 0.85], 10, 6, (x, y, z, out) => out.setRGB(PARTS.nail / 10, 1, 0), fb[2], { rot: [-0.08, 0, 0] });
  });
  // большой палец: пястная кость + 2 фаланги
  const t0 = X(THUMB0), dir = X(THUMB_DIR);
  const t1 = t0.clone().addScaledVector(dir, THUMB_LEN[0]), t2 = t1.clone().addScaledVector(dir, THUMB_LEN[1]);
  const t3 = t2.clone().addScaledVector(dir, THUMB_LEN[2]);
  const tb = bi.t;
  const along = (x, y, z) => (x - t0.x) * dir.x + (y - t0.y) * dir.y + (z - t0.z) * dir.z;
  S.tube([t0.clone().addScaledVector(dir, -0.012), t0, t1, t2, t3.clone().addScaledVector(dir, -0.008)], [0.026, 0.025, 0.022, 0.0205, 0.0185],
    { radial: 12, caps: [false, true], capSegs: 4 }, (x, y, z, out) => out.setRGB(PARTS.skin / 10, 1, 0), (x, y, z) => {
      const s = along(x, y, z);
      const a = sstep(-0.005, 0.01, s), b = sstep(THUMB_LEN[0] - 0.008, THUMB_LEN[0] + 0.008, s), c = sstep(THUMB_LEN[0] + THUMB_LEN[1] - 0.006, THUMB_LEN[0] + THUMB_LEN[1] + 0.006, s);
      return [bi.wrist, 1 - a, tb[0], a - b, tb[1], b - c, tb[2], c];
    });
  // ноготь большого пальца — на тыльной стороне (смотрит наружу-вверх)
  const nUp = V(0.55 * sx, 0.8, 0.1).normalize();
  const nailC = t3.clone().addScaledVector(dir, -0.022).addScaledVector(nUp, 0.016);
  const nq = new THREE.Quaternion().setFromUnitVectors(V(0, 1, 0), nUp);
  _e.setFromQuaternion(nq);
  S.ellipsoid(nailC, [0.012, 0.0035, 0.015], 10, 6, (x, y, z, out) => out.setRGB(PARTS.nail / 10, 1, 0), tb[2], { rot: [_e.x, _e.y, _e.z] });
  return { t0, t1, t2 };
}

export class Hands {
  constructor(skinHex) {
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(58, 1, 0.02, 10);
    this.hemi = new THREE.HemisphereLight(0xffffff, 0x886644, 1.2);
    this.dir = new THREE.DirectionalLight(0xffffff, 2.2);
    this.fill = new THREE.DirectionalLight(0xffe2cc, 0.35);
    this.fill.position.set(-0.3, -0.2, 1);
    this.scene.add(this.hemi, this.dir, this.fill);
    this.mat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.58, metalness: 0 });
    this.skin = new THREE.Color(skinHex);
    this.sleeve = new THREE.Color('#f3eee4');

    // кости: корень → [предплечье → запястье → пальцы/большой] × 2
    const bones = [];
    const mk = (parent, pos, name) => {
      const b = new THREE.Bone();
      b.name = name;
      b.position.copy(pos);
      if (parent) parent.add(b);
      bones.push(b);
      return b;
    };
    const root = mk(null, V(), 'root');
    this.root = root;
    const restWorld = [V()];
    const at = (parentIdx, worldPos, name) => {
      const b = mk(bones[parentIdx], worldPos.clone().sub(restWorld[parentIdx]), name);
      restWorld.push(worldPos.clone());
      return bones.length - 1;
    };
    this.hands = [];
    const S = new Sculpt(1);
    for (const side of [-1, 1]) {
      // кисть строится в своей системе (запястье в 0), смещение задаёт кость fore
      const fore = at(0, V(0, 0, 0.12), `fore${side}`);
      const wrist = at(fore, V(0, 0, 0), `wrist${side}`);
      const f = FINGERS.map((fg, i) => {
        const z0 = KNUCKLE_Z, z1 = z0 - fg.len[0], z2 = z1 - fg.len[1];
        const a = at(wrist, V(fg.x * side, 0, z0), `f${i}a`);
        const b = at(a, V(fg.x * side, 0, z1), `f${i}b`);
        const c = at(b, V(fg.x * side, 0, z2), `f${i}c`);
        return [a, b, c];
      });
      const t0 = V(THUMB0.x * side, THUMB0.y, THUMB0.z), dir = V(THUMB_DIR.x * side, THUMB_DIR.y, THUMB_DIR.z);
      const ta = at(wrist, t0, 'ta');
      const tbn = at(ta, t0.clone().addScaledVector(dir, THUMB_LEN[0]), 'tb');
      const tc = at(tbn, t0.clone().addScaledVector(dir, THUMB_LEN[0] + THUMB_LEN[1]), 'tc');
      buildHandParts(S, side, { fore, wrist, f, t: [ta, tbn, tc] });
      this.hands.push({
        side, fore: bones[fore], wrist: bones[wrist], f: f.map((x) => x.map((k) => bones[k])), t: [bones[ta], bones[tbn], bones[tc]],
        pos: new THREE.Vector3(), quat: new THREE.Quaternion(), curl: 0.25, thumb: 0.2, spread: 0.08, init: false,
      });
    }
    // в покое кость предплечья в (0,0,0.12): её позицию задаёт анимация, тут оставляем покой
    const geo = S.build(0);
    this._prepColors(geo);
    const inverses = restWorld.map((p) => new THREE.Matrix4().makeTranslation(-p.x, -p.y, -p.z));
    this.skeleton = new THREE.Skeleton(bones, inverses);
    this.mesh = new THREE.SkinnedMesh(geo, this.mat);
    this.mesh.add(root);
    this.mesh.bind(this.skeleton, new THREE.Matrix4());
    this.mesh.frustumCulled = false;
    this.scene.add(this.mesh);
    this.recolor();

    // точки крепления предметов в пространстве камеры рук (C3 для первого лица)
    this.anchors = {};
    const grip = new THREE.Object3D();
    grip.position.set(0, -0.034, -0.1);
    // ось +Y якоря вдоль кулака к указательному пальцу (−X кисти), −Z — вперёд по пальцам
    grip.quaternion.setFromRotationMatrix(_m.makeBasis(V(0, 1, 0), V(-1, 0, 0), V(0, 0, 1)));
    this.hands[1].wrist.add(grip);
    for (const k of ['one', 'bat', 'umbrella']) this.anchors[k] = grip;
    this.anchors.two = new THREE.Object3D();
    this.scene.add(this.anchors.two);

    this.t = 0;
    this.lagX = 0; this.lagY = 0;
    this.reach = 0;
    this.airK = 0;
    this.land = 0;
    this.landV = 0;
    this.wasGrounded = true;
    this.action = null;
    this.hold = { two: 0, one: 0, bat: 0, umbrella: 0 };
    this.runK = 0;
    this.moveK = 0;
    this._qi = new THREE.Quaternion();
  }

  // Цвет в вершинах при сборке хранит «часть» и затенение; настоящие цвета считаем здесь.
  _prepColors(geo) {
    const c = geo.getAttribute('color');
    this._part = new Uint8Array(c.count);
    this._shade = new Float32Array(c.count);
    for (let i = 0; i < c.count; i++) {
      this._part[i] = Math.round(c.getX(i) * 10);
      this._shade[i] = c.getY(i);
    }
  }

  recolor() {
    const c = this.mesh.geometry.getAttribute('color');
    const skin = this.skin, palm = skin.clone().lerp(new THREE.Color('#ff9c8c'), 0.16).multiplyScalar(1.03);
    const nail = skin.clone().lerp(new THREE.Color('#fff2ea'), 0.5);
    const knuckle = skin.clone().lerp(new THREE.Color('#ff8f80'), 0.12);
    const crease = skin.clone().multiplyScalar(0.8);
    const sleeve = this.sleeve, cuff = sleeve.clone().multiplyScalar(0.94);
    const cols = [skin, palm, nail, knuckle, crease, sleeve, cuff];
    for (let i = 0; i < c.count; i++) {
      const col = cols[this._part[i]] || skin;
      const k = this._shade[i];
      c.setXYZ(i, col.r * k, col.g * k, col.b * k);
    }
    c.needsUpdate = true;
  }

  setSkin(hex) { this.skin.set(hex); this.recolor(); }
  setSleeve(hex) { this.sleeve.set(hex); this.recolor(); }

  // Действие (C2b): 'swing' | 'strum' | 'toggle' | 'throw' | 'wave'
  act(name) {
    const dur = { wave: 1.5, swing: 0.55, strum: 1.4, toggle: 0.4, throw: 0.6 }[name];
    if (dur) this.action = { name, t: 0, dur };
  }

  anchor(kind) { return this.anchors[kind] || this.anchors.one; }

  // Матрица якоря в мире: поза рук с поправкой на разный FOV камеры рук и мировой камеры.
  anchorWorld(kind, camera, out = new THREE.Matrix4()) {
    const a = this.anchor(kind);
    a.updateWorldMatrix(true, false);
    a.matrixWorld.decompose(_v, _q, _v2);
    const kh = Math.tan(THREE.MathUtils.degToRad(this.camera.fov) / 2), kw = Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2);
    _v.x *= kh / kw; _v.y *= kh / kw;
    out.compose(_v, _q, _v2.set(1, 1, 1));
    return out.premultiply(camera.matrixWorld);
  }

  // st: { speed, phase, holdKind, holding, grounded, vy, sprint, lookDX, lookDY, reach, talk }
  update(dt, st, camera, atm, aspect) {
    this.t += dt;
    const t = this.t;
    this.camera.aspect = aspect;
    this.camera.updateProjectionMatrix();
    // свет как в мире: цвета из атмосферы, солнце — в пространстве камеры
    this.hemi.color.copy(atm.hemiSky);
    this.hemi.groundColor.copy(atm.hemiGround);
    this.hemi.intensity = atm.hemiIntensity * 0.95;
    this.dir.color.copy(atm.sunColor);
    this.dir.intensity = atm.sunIntensity * 0.55;
    this._qi.copy(camera.quaternion).invert();
    this.dir.position.copy(atm.sunDir).applyQuaternion(this._qi).multiplyScalar(4);

    const grounded = st.grounded !== false;
    const move = clamp(st.speed / 4.2, 0, 1.8);
    this.moveK = damp(this.moveK, Math.min(1, move), 8, dt);
    this.runK = damp(this.runK, st.sprint && st.speed > 5 ? 1 : 0, 6, dt);
    this.lagX = damp(this.lagX, clamp(-st.lookDX * 0.0009, -0.07, 0.07), 12, dt);
    this.lagY = damp(this.lagY, clamp(st.lookDY * 0.0009, -0.06, 0.06), 12, dt);
    this.reach = damp(this.reach, st.reach ? 1 : 0, 16, dt);
    this.airK = damp(this.airK, grounded ? 0 : 1, grounded ? 14 : 6, dt);
    if (grounded && !this.wasGrounded) this.landV -= 0.9 + clamp(-(this._vy || 0) / 10, 0, 1) * 0.9;
    this.wasGrounded = grounded;
    this._vy = st.vy || 0;
    this.landV += (-200 * this.land - 18 * this.landV) * dt;
    this.land = clamp(this.land + this.landV * dt, -0.08, 0.04);
    const hk = st.holdKind || (st.holding ? 'two' : null);
    for (const k of Object.keys(this.hold)) this.hold[k] = damp(this.hold[k], hk === k ? 1 : 0, 12, dt);

    const act = this.action;
    if (act) { act.t += dt; if (act.t >= act.dur) this.action = null; }
    const ph = st.phase || 0;
    const breath = Math.sin(t * 1.6);

    for (const h of this.hands) {
      const s = h.side, right = s > 0;
      // 1) базовая поза: покой → бег → полёт → тянемся
      const pose = this._blendPose(h, right, hk);
      const pos = _v.copy(pose.pos);
      _q.copy(pose.q);
      let curl = pose.curl, thumb = pose.thumb, spread = pose.spread;
      // 2) процедурные смещения: шаг, «работа руками», дыхание, лаг обзора, прыжок
      const sw = Math.sin(ph + (right ? 0 : Math.PI));
      const holdAny = Math.max(this.hold.two, right ? Math.max(this.hold.one, this.hold.bat, this.hold.umbrella) : 0);
      const free = 1 - holdAny;
      const runK = this.runK * free;
      pos.y += Math.sin(ph * 2) * 0.012 * this.moveK + breath * 0.004 + this.land * 0.6 + this.airK * 0.03;
      pos.x += s * Math.sin(ph) * 0.008 * this.moveK + this.lagX;
      pos.y += this.lagY;
      pos.z += sw * 0.03 * this.moveK * free;
      // бег: кулаки ходят вверх-вниз и вперёд-назад
      pos.y += runK * (0.06 * sw + 0.02);
      pos.z += runK * 0.05 * sw;
      _q2.setFromEuler(_e.set(runK * 0.35 * sw + this.land * 2 - this.airK * 0.2, 0, 0));
      _q.premultiply(_q2);
      // 3) действия
      if (act) {
        const r = this._actPose(act, right, pos, _q);
        if (r) { curl = r.curl ?? curl; thumb = r.thumb ?? thumb; spread = r.spread ?? spread; }
      }
      // 4) сглаживание и запись в кости
      if (!h.init) { h.pos.copy(pos); h.quat.copy(_q); h.init = true; }
      h.pos.x = damp(h.pos.x, pos.x, 18, dt); h.pos.y = damp(h.pos.y, pos.y, 18, dt); h.pos.z = damp(h.pos.z, pos.z, 18, dt);
      h.quat.slerp(_q, 1 - Math.exp(-16 * dt));
      h.curl = damp(h.curl, curl, 16, dt);
      h.thumb = damp(h.thumb, thumb, 16, dt);
      h.spread = damp(h.spread, spread, 12, dt);
      // предплечье (кость fore) тянется от низа экрана к запястью
      const wristW = h.pos;
      _v2.set(s * 0.22, -0.5, 0.05).sub(wristW).normalize();          // от запястья к локтю
      // ориентируем предплечье: его +Z (к камере) — на локоть, «вверх» — тыльная сторона кисти
      _y.set(0, 1, 0).applyQuaternion(h.quat);
      _z.copy(_v2);
      _y.addScaledVector(_z, -_y.dot(_z)).normalize();
      _x.crossVectors(_y, _z);
      const qFore = _q2.setFromRotationMatrix(_m.makeBasis(_x, _y, _z));
      h.fore.quaternion.copy(qFore);
      h.fore.position.copy(wristW).addScaledVector(_z, 0.12);
      h.wrist.quaternion.copy(qFore).invert().multiply(h.quat);
      h.wrist.position.set(0, 0, -0.12);
      // пальцы: сгиб по фалангам (мизинец сгибается сильнее), веер
      h.f.forEach((fb, i) => {
        const c = clamp(h.curl * (1 + 0.08 * i), 0, 1.15);
        const sp = (i - 1.5) * h.spread * 0.35 * s;
        fb[0].rotation.set(-c * 1.3, -sp, 0);
        fb[1].rotation.set(-c * 1.3, 0, 0);
        fb[2].rotation.set(-c * 0.75, 0, 0);
      });
      // большой палец: поворот через ладонь к указательному (оппозиция) и сгиб фаланг
      const tc = h.thumb;
      h.t[0].rotation.set(-tc * 0.45, -s * tc * 1.15, -s * tc * 0.25, 'YXZ');
      h.t[1].rotation.set(-tc * 0.45, 0, 0);
      h.t[2].rotation.set(-tc * 0.6, 0, 0);
    }
    // якорь «двумя руками»: между ладонями
    const [l, r] = this.hands;
    this.anchors.two.position.copy(l.pos).add(r.pos).multiplyScalar(0.5).add(_v.set(0, -0.02, -0.09));
    this.anchors.two.quaternion.identity();
  }

  _blendPose(h, right, hk) {
    const mirror = (p, out) => {
      out.pos.set(p.pos.x * h.side, p.pos.y, p.pos.z);
      out.f.set(p.f.x * h.side, p.f.y, p.f.z);
      out.u.set(p.u.x * h.side, p.u.y, p.u.z);
      out.curl = p.curl; out.thumb = p.thumb; out.spread = p.spread;
      return out;
    };
    const acc = this._acc || (this._acc = { pos: new THREE.Vector3(), f: new THREE.Vector3(), u: new THREE.Vector3(), curl: 0, thumb: 0, spread: 0, q: new THREE.Quaternion() });
    const tmp = this._tmp || (this._tmp = { pos: new THREE.Vector3(), f: new THREE.Vector3(), u: new THREE.Vector3(), curl: 0, thumb: 0, spread: 0 });
    mirror(POSES.idle, acc);
    const add = (p, w) => {
      if (w <= 0.001) return;
      mirror(p, tmp);
      acc.pos.lerp(tmp.pos, w); acc.f.lerp(tmp.f, w).normalize(); acc.u.lerp(tmp.u, w).normalize();
      acc.curl += (tmp.curl - acc.curl) * w; acc.thumb += (tmp.thumb - acc.thumb) * w; acc.spread += (tmp.spread - acc.spread) * w;
    };
    add(POSES.run, this.runK);
    add(POSES.air, this.airK * 0.8);
    if (right) add(POSES.reach, this.reach);
    add(POSES.two, this.hold.two);
    if (right) { add(POSES.one, this.hold.one); add(POSES.bat, this.hold.bat); add(POSES.umbrella, this.hold.umbrella); }
    orient(acc.f, acc.u, acc.q);
    return acc;
  }

  // Действия: правим позицию и поворот кисти по ключевым кадрам. Возвращает пальцы или null.
  // Кадр: [u, x, y, z, ax, ay, az, ux, uy, uz]; grip = true — a это ось кулака (вверх по предмету), иначе пальцы.
  _actPose(act, right, pos, q) {
    const u = act.t / act.dur;
    const k = sstep(0, 0.12, u) * sstep(1, 0.85, u);
    const s = right ? 1 : -1;
    const apply = (x, y, z, ax, ay, az, ux, uy, uz, grip, w = k) => {
      pos.lerp(_v2.set(x * s, y, z), w);
      if (grip) {
        _x.set(-ax * s, -ay, -az).normalize();
        _y.set(ux * s, uy, uz);
        _y.addScaledVector(_x, -_y.dot(_x)).normalize();
        _z.crossVectors(_x, _y);
        _q2.setFromRotationMatrix(_m.makeBasis(_x, _y, _z));
      } else orient(_x.set(ax * s, ay, az).normalize(), _y.set(ux * s, uy, uz).normalize(), _q2);
      q.slerp(_q2, w);
    };
    const key = (frames, grip) => {
      let i = 0;
      while (i < frames.length - 2 && u > frames[i + 1][0]) i++;
      const a = frames[i], b = frames[i + 1];
      const f = smoother(clamp((u - a[0]) / Math.max(1e-4, b[0] - a[0]), 0, 1));
      const L = (j) => a[j] + (b[j] - a[j]) * f;
      apply(L(1), L(2), L(3), L(4), L(5), L(6), L(7), L(8), L(9), grip);
    };
    switch (act.name) {
      case 'wave': {
        if (!right) return null;
        const w = Math.sin(act.t * 12);
        const p = POSES.wave;
        apply(p.pos.x + 0.02 * w, p.pos.y, p.pos.z, 0.45 * w, 1, 0.05, 0, 0.1, 1, false);
        return { curl: 0.05 * k + 0.28 * (1 - k), thumb: 0.05, spread: 0.2 };
      }
      case 'swing': // диагональный замах битой через весь экран
        if (!right) return null;
        key([[0, 0.2, -0.2, -0.4, -0.5, 0.8, -0.35, 0.7, 0.3, 0.6], [0.3, 0.3, 0.02, -0.36, -0.3, 0.7, 0.65, 0.8, 0.5, 0.2],
          [0.5, 0.02, -0.14, -0.5, -0.95, 0.15, -0.3, 0.1, 0.9, 0.4], [0.72, -0.28, -0.24, -0.38, -0.5, -0.6, 0.6, -0.3, 0.5, 0.8],
          [1, 0.2, -0.2, -0.4, -0.5, 0.8, -0.35, 0.7, 0.3, 0.6]], true);
        return { curl: 1, thumb: 0.85, spread: 0 };
      case 'throw':
        if (!right) return null;
        key([[0, 0.25, -0.2, -0.42, -0.25, 0.75, -0.6, 0.3, 0.35, 0.9], [0.35, 0.34, -0.06, -0.2, 0, 1, 0.4, 0.4, 0, 0.9],
          [0.55, 0.08, -0.02, -0.7, 0, 0.3, -1, 0.2, 1, 0.3], [0.75, 0.14, -0.24, -0.56, 0, -0.4, -1, 0.2, 1, -0.3],
          [1, 0.25, -0.2, -0.42, -0.25, 0.75, -0.6, 0.3, 0.35, 0.9]], false);
        return { curl: u < 0.45 ? 0.8 : 0.05, thumb: u < 0.45 ? 0.7 : 0.05, spread: u < 0.45 ? 0 : 0.25 };
      case 'strum':
        if (right) {
          apply(0.06, -0.24 + 0.035 * Math.sin(act.t * 16), -0.42, -0.6, -0.2, -0.8, 0.2, 0.9, 0.3, false);
          return { curl: 0.7, thumb: 0.7 };
        }
        apply(0.28, -0.12, -0.5, 0.2, 0.9, -0.4, -0.7, 0.2, 0.6, true);
        return { curl: 0.8 + 0.1 * Math.sin(act.t * 5), thumb: 0.7 };
      case 'toggle':
        if (!right) return null;
        key([[0, 0.17, -0.16, -0.42, 0, 1, 0.1, 0.9, 0, 0.45], [0.45, 0.16, -0.1, -0.5, 0.4, 0.9, -0.1, 0.8, -0.2, 0.5],
          [1, 0.17, -0.16, -0.42, 0, 1, 0.1, 0.9, 0, 0.45]], true);
        return { curl: 1, thumb: 0.8 };
      default:
        return null;
    }
  }
}
