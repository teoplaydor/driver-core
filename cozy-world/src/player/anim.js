// Процедурная анимация персонажа без внешних клипов.
// Походка: фаза от пройденного пути, каждая стопа в опоре «прибита» к земле в мировых координатах,
// в переносе летит по дуге к предсказанной точке постановки — ноги не скользят. Ноги ставятся по
// рельефу двухзвенной IK (таз опускается, чтобы нижняя стопа доставала землю), стопа перекатывается
// с пятки на носок. Поверх: покачивание и поворот таза, противовращение груди, маятник рук,
// стабилизация головы, дыхание, моргание, речь, прыжок/приземление, позы удержания и действия.

import * as THREE from 'three';
import {
  B, armDir, palmDir, gripLocal, HIP_Y, THIGH, SHIN, ANKLE_Y, UPPER, FORE, HEEL_Z, TOE_Z, FOOT_X, aimQuat, solveTwoBone, sstep,
} from './rig.js';
import { MOUTH_DEPTH } from './character-geo.js';
import { clamp, damp, angleDiff } from '../util/math.js';

const TAU = Math.PI * 2;
const LEG = THIGH + SHIN;
const DOWN = new THREE.Vector3(0, -1, 0);
const FWD = new THREE.Vector3(0, 0, -1);
const BACK = new THREE.Vector3(0, 0, 1);
const UPV = new THREE.Vector3(0, 1, 0);
const MINUS_Z = new THREE.Vector3(0, 0, -1);

// длительности действий, с
export const ACTIONS = { wave: 1.8, swing: 0.62, strum: 1.5, toggle: 0.42, throw: 0.62 };
export const HOLD_KINDS = ['two', 'one', 'bat', 'umbrella'];

// Хват кулака в системе кисти: центр «дырки» кулака и смещение ладони
const gripOffset = (side) => gripLocal(side);
const palmOffset = (side) => armDir(side).multiplyScalar(0.055);

// временные объекты (без выделений в кадре)
const _v1 = new THREE.Vector3(), _v2 = new THREE.Vector3(), _v3 = new THREE.Vector3(), _v4 = new THREE.Vector3();
const _d1 = new THREE.Vector3(), _d2 = new THREE.Vector3(), _pole = new THREE.Vector3();
const _q1 = new THREE.Quaternion(), _q2 = new THREE.Quaternion(), _q3 = new THREE.Quaternion(), _q4 = new THREE.Quaternion();
const _e = new THREE.Euler(0, 0, 0, 'YXZ');
const _eX = new THREE.Euler();
const _m = new THREE.Matrix4();
const smoother = (t) => t * t * t * (t * (t * 6 - 15) + 10);
const wrap1 = (x) => x - Math.floor(x);

function yawQuat(yaw, out) { return out.setFromAxisAngle(UPV, yaw); }

class Foot {
  constructor(side) {
    this.side = side;
    this.pos = new THREE.Vector3();    // точка на земле под лодыжкой (мир)
    this.yaw = 0;
    this.mode = 0;                     // 0 — опора, 1 — шаг по фазе походки, 2 — шаг по времени (на месте)
    this.from = new THREE.Vector3();
    this.fromYaw = 0;
    this.target = new THREE.Vector3();
    this.targetYaw = 0;
    this.s0 = 0;
    this.t = 0;
    this.dur = 0.2;
    this.lift = 0.06;
    this.prog = 0;                     // прогресс переноса 0..1
    this.pitch = 0;
    this.gp = 0;                       // уклон земли вдоль стопы
    this.gr = 0;                       // уклон земли поперёк стопы
    this.ankle = new THREE.Vector3();  // цель лодыжки (мир)
    this.air = new THREE.Vector3();    // цель лодыжки в воздухе (мир)
    this.fp = 0;                       // фаза ноги
  }
}

export class Animator {
  constructor(bones, root) {
    this.b = bones;
    this.root = root;
    this.ground = null;
    this.t = Math.random() * 100;
    this.feet = [new Foot(-1), new Foot(1)];
    this.inited = false;
    this.lastPos = new THREE.Vector3();
    this.lastYaw = 0;
    this.vel = new THREE.Vector3();
    this.speed = 0;
    this.accel = 0;
    this.yawRate = 0;
    this.phase = 0;
    this.gaitOn = false;
    this.gaitW = 0;
    this.runW = 0;
    this.beta = 0.6;
    this.stance = 0.3;
    this.cycle = 0.6;
    this.hipYaw = 0;
    this.backward = false;
    this.drop = 0;
    this.crouch = 0;
    this.crouchV = 0;
    this.airW = 0;
    this.airT = 0;
    this.lastVy = 0;
    this.grounded = true;
    this.lean = 0;
    this.turnLean = 0;
    // лицо
    this.blinkT = 1 + Math.random() * 3;
    this.blink = 0;
    this.blinkPhase = -1;
    this.doubleBlink = false;
    this.gaze = new THREE.Vector2();
    this.gazeGoal = new THREE.Vector2();
    this.gazeT = 1;
    this.glance = 0;
    this.glanceGoal = 0;
    this.glanceT = 3 + Math.random() * 4;
    this.surprise = 0;
    this.mouth = 0;
    this.talkPh = Math.random() * 10;
    // удержание и действия
    this.hold = { two: 0, one: 0, bat: 0, umbrella: 0 };
    this.act = null;   // { name, t, dur }
    this.actW = 0;
    this.waveSide = 1;
    this.headLook = new THREE.Vector2();
    this.fingerCurl = [0.3, 0.3];
    // кэш мировых величин
    this.hip = [new THREE.Vector3(), new THREE.Vector3()];
    this.shoulder = [new THREE.Vector3(), new THREE.Vector3()];
    this.qRoot = new THREE.Quaternion();
    this.qPelvis = new THREE.Quaternion();
    this.qChest = new THREE.Quaternion();
    this.qClav = [new THREE.Quaternion(), new THREE.Quaternion()];
    this.chestM = new THREE.Matrix4();
    this.browY0 = [bones[B.browL].position.y, bones[B.browR].position.y];
  }

  play(name) {
    if (!ACTIONS[name]) return;
    this.act = { name, t: 0, dur: ACTIONS[name] };
  }

  _resetFeet(pos, yaw) {
    yawQuat(yaw, _q1);
    for (const f of this.feet) {
      f.pos.set(f.side * FOOT_X, 0, 0).applyQuaternion(_q1).add(pos);
      f.pos.y = this._groundAt(f.pos.x, f.pos.z, pos.y);
      f.yaw = yaw + f.side * 0.08;
      f.mode = 0;
      f.prog = 0;
    }
  }

  _groundAt(x, z, yRef) {
    return this.ground ? this.ground(x, z, yRef) : yRef;
  }

  // st: { speed, grounded, vy, pitch, holdKind, talk, lookAt? }
  update(dt, st, env) {
    const b = this.b;
    const root = this.root;
    const P = root.position, yaw = root.rotation.y;
    this.t += dt;
    const t = this.t;
    if (!this.inited || P.distanceToSquared(this.lastPos) > 9) {
      this.inited = true;
      this.lastPos.copy(P);
      this.lastYaw = yaw;
      this.vel.set(0, 0, 0);
      this._resetFeet(P, yaw);
      this.gaitOn = false;
    }
    const idt = dt > 1e-5 ? 1 / dt : 0;

    // ---------- 1. движение тела ----------
    _v1.subVectors(P, this.lastPos).multiplyScalar(idt);
    _v1.y = 0;
    const prevSpeed = this.speed;
    this.vel.x = damp(this.vel.x, _v1.x, 14, dt);
    this.vel.z = damp(this.vel.z, _v1.z, 14, dt);
    let speed = Math.hypot(this.vel.x, this.vel.z);
    if (st.speed !== undefined && speed > 0.05) speed = (speed + Math.min(st.speed, speed * 1.5)) * 0.5;
    this.speed = speed;
    this.accel = damp(this.accel, (speed - prevSpeed) * idt, 6, dt);
    const yawStep = angleDiff(this.lastYaw, yaw);
    this.yawRate = damp(this.yawRate, yawStep * idt, 8, dt);
    this.lastPos.copy(P);
    this.lastYaw = yaw;
    const grounded = st.grounded !== false;
    const s = speed;

    // направление движения в системе персонажа: 0 — вперёд
    let moveAng = 0;
    if (s > 0.2) moveAng = angleDiff(yaw, Math.atan2(-this.vel.x, -this.vel.z));
    if (s > 0.4) {
      if (!this.backward && Math.abs(moveAng) > 2.0) this.backward = true;
      else if (this.backward && Math.abs(moveAng) < 1.6) this.backward = false;
    }
    const hipGoal = s > 0.4 ? clamp(this.backward ? angleDiff(Math.PI, moveAng) * -1 : moveAng, -0.9, 0.9) : 0;
    this.hipYaw = damp(this.hipYaw, hipGoal, 8, dt);
    const hipYawW = yaw + this.hipYaw;
    yawQuat(yaw, this.qRoot);

    // ---------- 2. параметры походки ----------
    const freq = 1.4 + 0.65 * Math.sqrt(s);
    const cycle = Math.max(0.05, s / freq);
    const stance = Math.min(0.32 + 0.006 * s, 0.65 * cycle);
    const beta = clamp(stance / cycle, 0.14, 0.65);
    this.cycle = cycle; this.stance = stance; this.beta = beta;
    this.gaitW = damp(this.gaitW, grounded ? sstep(0.15, 1.2, s) : 0, 8, dt);
    this.runW = damp(this.runW, sstep(4.6, 7.0, s), 5, dt);
    const flight = sstep(0.5, 0.3, beta);
    const moving = grounded && s > 0.25;

    if (moving) {
      if (!this.gaitOn) {
        // начинаем с той ноги, что сзади относительно движения
        const dirX = this.vel.x / s, dirZ = this.vel.z / s;
        const [l, r] = this.feet;
        const dl = (l.pos.x - P.x) * dirX + (l.pos.z - P.z) * dirZ;
        const dr = (r.pos.x - P.x) * dirX + (r.pos.z - P.z) * dirZ;
        this.phase = dl <= dr ? beta - 0.001 : wrap1(beta - 0.501);
        for (const f of this.feet) f.fp = wrap1(this.phase + (f.side < 0 ? 0 : 0.5));
      }
      this.gaitOn = true;
      this.phase = wrap1(this.phase + Math.min(0.2, (s * dt) / cycle));
    } else if (this.gaitOn) {
      if (this.feet.some((f) => f.mode === 1)) this.phase = wrap1(this.phase + Math.min(0.2, dt * 2.4));
      else this.gaitOn = false;
    }

    // ---------- 3. стопы ----------
    const dirX = s > 0.05 ? this.vel.x / s : 0, dirZ = s > 0.05 ? this.vel.z / s : 0;
    const latX = Math.cos(hipYawW), latZ = -Math.sin(hipYawW);
    const footX = FOOT_X - 0.02 * this.runW;
    const plantAhead = stance * 0.5 * sstep(0.2, 0.9, s);
    for (const f of this.feet) {
      const prevFp = f.fp;
      f.fp = wrap1(this.phase + (f.side < 0 ? 0 : 0.5));
      if (!grounded) continue;
      if (this.gaitOn) {
        const remain = (f.fp >= beta ? 1 - f.fp : 0) * cycle;
        f.target.set(
          P.x + dirX * (remain + plantAhead) + latX * f.side * footX,
          0,
          P.z + dirZ * (remain + plantAhead) + latZ * f.side * footX,
        );
        f.targetYaw = hipYawW + f.side * 0.1;
        if (f.mode === 0 && moving && f.fp >= beta && f.fp < 0.97) {
          f.mode = 1;
          f.from.copy(f.pos);
          f.fromYaw = f.yaw;
          f.s0 = (f.fp - beta) / (1 - beta);
          f.lift = 0.05 + 0.05 * sstep(1, 4, s) + 0.06 * this.runW;
        }
        if (f.mode === 1) {
          const wrapped = f.fp < prevFp - 0.5;
          const sw = wrapped || f.fp < beta ? 1 : (f.fp - beta) / (1 - beta);
          f.prog = clamp((sw - f.s0) / Math.max(1e-3, 1 - f.s0), 0, 1);
          if (f.prog >= 1) this._land(f, P.y);
        }
      } else {
        // стоим: следим, чтобы стопы оставались под тазом и смотрели вперёд
        f.target.set(P.x + latX * f.side * FOOT_X, 0, P.z + latZ * f.side * FOOT_X);
        f.targetYaw = hipYawW + f.side * 0.08;
        if (f.mode === 1) { f.mode = 2; f.t = f.prog; f.dur = 0.16; f.s0 = 0; }
      }
      // принудительный шаг: стопа слишком далеко (резкий разворот, толчок)
      if (f.mode === 0) {
        const dx = f.pos.x - (P.x + latX * f.side * FOOT_X), dz = f.pos.z - (P.z + latZ * f.side * FOOT_X);
        const far = Math.hypot(dx, dz);
        const other = this.feet[f.side < 0 ? 1 : 0];
        const twist = Math.abs(angleDiff(f.yaw, hipYawW + f.side * 0.08));
        const limit = this.gaitOn ? 0.5 : 0.1;
        if ((far > limit || (!this.gaitOn && twist > 0.55)) && other.mode === 0) {
          f.mode = 2; f.t = 0; f.dur = this.gaitOn ? 0.14 : 0.2; f.from.copy(f.pos); f.fromYaw = f.yaw; f.lift = 0.045;
        }
      }
      if (f.mode === 2) {
        f.t += dt / f.dur;
        f.prog = clamp(f.t, 0, 1);
        if (f.prog >= 1) this._land(f, P.y);
      }
    }

    // ---------- 4. корпус ----------
    const gw = this.gaitW;
    const ph = this.phase * TAU;
    const mid = (this.phase - beta * 0.5) * TAU; // середина опоры левой ноги
    const breath = Math.sin(t * 1.7);
    // приземление: пружина приседа
    if (grounded && !this.grounded && this.airT > 0.1) {
      const impact = clamp(-this.lastVy / 9, 0.15, 1);
      this.crouchV -= 0.55 + impact * 1.4;
      this.surprise = Math.max(this.surprise, impact * 0.8);
      for (const f of this.feet) {
        f.pos.copy(f.ankle);
        f.pos.y = this._groundAt(f.pos.x, f.pos.z, P.y);
        f.mode = 0;
        f.yaw = hipYawW + f.side * 0.08;
      }
    }
    if (!grounded && this.grounded && st.vy > 2) this.surprise = Math.max(this.surprise, 0.6);
    if (grounded) this.airT = 0; else this.airT += dt;
    if (!grounded) this.lastVy = st.vy || 0;
    this.grounded = grounded;
    this.crouchV += (-260 * this.crouch - 20 * this.crouchV) * dt;
    this.crouch = clamp(this.crouch + this.crouchV * dt, -0.14, 0.05);
    this.airW = damp(this.airW, grounded ? 0 : 1, grounded ? 20 : 9, dt);
    const air = this.airW;
    const vy = st.vy || 0;
    const rise = sstep(-1, 2.5, vy);

    // наклоны: вперёд от скорости и разгона, вбок — в поворот
    this.lean = damp(this.lean, (0.05 + 0.15 * this.runW) * gw + clamp(this.accel * 0.012, -0.08, 0.1) * gw, 6, dt);
    this.turnLean = damp(this.turnLean, clamp(this.yawRate * s * 0.025, -0.16, 0.16), 6, dt);
    const bob = gw * (0.016 * (1 - flight) - 0.026 * flight) * Math.cos(2 * mid);
    const sway = -gw * 0.02 * (1 - 0.6 * this.runW) * Math.cos(mid);
    const twist = -gw * (0.13 - 0.05 * this.runW) * Math.cos(ph);
    // перенос веса стоя
    const idleW = 1 - gw;
    const shift = Math.sin(t * 0.55) * 0.5 + Math.sin(t * 0.23 + 1) * 0.5;

    b[B.pelvis].position.set(sway + idleW * 0.014 * shift, HIP_Y + bob + this.crouch - 0.012 * idleW, 0);
    _e.set(-this.lean * 0.5, this.hipYaw + twist, -gw * 0.055 * Math.cos(mid) + idleW * 0.02 * shift + this.turnLean * 0.4, 'YXZ');
    b[B.pelvis].quaternion.setFromEuler(_e);
    _e.set(-this.lean * 0.6 + 0.02 * breath * idleW + air * 0.1 * (1 - rise), -this.hipYaw * 0.45 - twist * 0.9, this.turnLean * 0.5 - idleW * 0.012 * shift, 'YXZ');
    b[B.spine].quaternion.setFromEuler(_e);
    _e.set(-0.012 * breath - this.lean * 0.3, -this.hipYaw * 0.45 - twist * 0.7, 0, 'YXZ');
    b[B.chest].quaternion.setFromEuler(_e);
    const bs = 1 + 0.03 * breath;
    b[B.belly].scale.set(1 + 0.012 * breath, 1, bs);
    for (const side of [-1, 1]) {
      const c = b[side < 0 ? B.clavL : B.clavR];
      const shrug = this.act && this.act.name === 'wave' && side === this.waveSide ? 0.16 * this.actW : 0; // плечо приподнято, когда машет
      c.quaternion.setFromEuler(_eX.set(0, 0, side * (0.012 * breath + air * 0.08 * rise + shrug)));
    }

    // действия и удержание: веса
    const hk = st.holdKind || (st.holding ? 'two' : null);
    for (const k of HOLD_KINDS) this.hold[k] = damp(this.hold[k], hk === k ? 1 : 0, 10, dt);
    const act = this.act;
    let actK = 0;
    if (act) {
      act.t += dt;
      const u = act.t / act.dur;
      actK = sstep(0, 0.12, u) * sstep(1, 0.82, u);
      if (u >= 1) this.act = null;
    }
    this.actW = actK;
    const swingU = act && act.name === 'swing' ? act.t / act.dur : -1;
    const throwU = act && act.name === 'throw' ? act.t / act.dur : -1;
    // замах и бросок поворачивают корпус
    if (swingU >= 0) {
      const tw = swingU < 0.3 ? -0.45 * sstep(0, 0.3, swingU) : swingU < 0.55 ? -0.45 + 1.05 * sstep(0.3, 0.5, swingU) : 0.6 * sstep(1, 0.55, swingU);
      b[B.spine].quaternion.multiply(_q1.setFromAxisAngle(UPV, tw * 0.55));
      b[B.chest].quaternion.multiply(_q1.setFromAxisAngle(UPV, tw * 0.45));
      b[B.pelvis].quaternion.multiply(_q1.setFromAxisAngle(UPV, tw * 0.3));
    }
    if (throwU >= 0) {
      const tw = throwU < 0.35 ? -0.4 * sstep(0, 0.35, throwU) : -0.4 + 0.8 * sstep(0.35, 0.55, throwU) - 0.4 * sstep(0.6, 1, throwU);
      b[B.chest].quaternion.multiply(_q1.setFromAxisAngle(UPV, tw * 0.6));
      b[B.spine].quaternion.multiply(_q1.setFromEuler(_eX.set(throwU < 0.4 ? 0.12 * sstep(0, 0.35, throwU) : 0.12 - 0.3 * sstep(0.35, 0.6, throwU) * sstep(1, 0.7, throwU), tw * 0.4, 0)));
    }

    // голова: смотрит по направлению камеры, стабилизирована, поглядывает по сторонам
    this._face(dt, st, env, idleW);
    const pitch = clamp((st.pitch || 0) * 0.55, -0.45, 0.4);
    this.glanceT -= dt;
    if (this.glanceT < 0) {
      this.glanceT = 2.5 + Math.random() * 5;
      this.glanceGoal = Math.random() < 0.45 ? (Math.random() - 0.5) * 1.1 * idleW : 0;
    }
    this.glance = damp(this.glance, this.glanceGoal, 4, dt);
    let lookYaw = this.glance, lookPitch = pitch;
    if (env && env.lookAt) {
      _v1.copy(env.lookAt).sub(P);
      const ly = angleDiff(yaw, Math.atan2(-_v1.x, -_v1.z));
      if (Math.abs(ly) < 1.9) {
        lookYaw = clamp(ly, -0.9, 0.9);
        lookPitch = clamp(Math.atan2(_v1.y - 1.0, Math.hypot(_v1.x, _v1.z)), -0.4, 0.4);
      }
    }
    this.headLook.x = damp(this.headLook.x, lookYaw, 5, dt);
    this.headLook.y = damp(this.headLook.y, lookPitch, 6, dt);
    const talk = st.talk || 0;
    const nod = talk * 0.045 * Math.sin(t * 6.3) + 0.012 * breath * idleW;
    const waveTilt = act && act.name === 'wave' ? 0.1 * actK * (this.waveSide || 1) : 0;
    const strumBob = act && act.name === 'strum' ? 0.08 * actK * Math.sin(act.t * 9) : 0;
    // желаемая ориентация головы в системе корня → локальные шея/голова
    _q1.copy(b[B.pelvis].quaternion).multiply(b[B.spine].quaternion).multiply(b[B.chest].quaternion); // грудь относительно корня
    // голова чуть наклонена вперёд, как в референсе
    _e.set(this.headLook.y - 0.07 + nod + strumBob + air * 0.12 * (1 - rise) - 0.06 * this.runW * gw, this.headLook.x, waveTilt - this.turnLean * 0.3, 'YXZ');
    _q2.setFromEuler(_e);
    _q3.copy(_q1).invert().multiply(_q2); // нужное вращение относительно груди
    b[B.neck].quaternion.identity().slerp(_q3, 0.35);
    b[B.head].quaternion.copy(b[B.neck].quaternion).invert().multiply(_q3);

    // ---------- 5. матрицы корпуса, затем IK ----------
    root.updateMatrixWorld(true);
    for (const side of [0, 1]) {
      this.hip[side].setFromMatrixPosition(b[side ? B.thighR : B.thighL].matrixWorld);
      this.shoulder[side].setFromMatrixPosition(b[side ? B.armR : B.armL].matrixWorld);
    }
    this.qPelvis.copy(this.qRoot).multiply(b[B.pelvis].quaternion);
    this.qChest.copy(this.qPelvis).multiply(b[B.spine].quaternion).multiply(b[B.chest].quaternion);
    this.qClav[0].copy(this.qChest).multiply(b[B.clavL].quaternion);
    this.qClav[1].copy(this.qChest).multiply(b[B.clavR].quaternion);
    this.chestM.copy(b[B.chest].matrixWorld);

    this._legs(dt, P, hipYawW, grounded, vy, rise);
    this._arms(dt, st, hk, gw, air, rise, breath);
  }

  _land(f, yRef) {
    f.pos.copy(f.target);
    f.pos.y = this._groundAt(f.pos.x, f.pos.z, yRef);
    f.yaw = f.targetYaw;
    f.mode = 0;
    f.prog = 0;
  }

  // ---------- ноги ----------
  _legs(dt, P, hipYawW, grounded, vy, rise) {
    const b = this.b;
    const beta = this.beta;
    const walkish = 1 - sstep(0.45, 0.25, beta);
    const air = this.airW;
    // 1) цели лодыжек
    for (const f of this.feet) {
      const i = f.side < 0 ? 0 : 1;
      let gx, gz, gy, fyaw, pitch = 0;
      if (f.mode === 0) {
        gx = f.pos.x; gz = f.pos.z; gy = f.pos.y; fyaw = f.yaw;
        if (this.gaitOn) {
          const sig = clamp(f.fp / beta, 0, 1);
          pitch = 0.2 * walkish * (1 - sstep(0, 0.28, sig)) - 0.5 * sstep(0.6, 1, sig) * (0.6 + 0.4 * this.gaitW);
        }
      } else {
        const p = f.prog, e = smoother(p);
        f.target.y = this._groundAt(f.target.x, f.target.z, P.y);
        gx = f.from.x + (f.target.x - f.from.x) * e;
        gz = f.from.z + (f.target.z - f.from.z) * e;
        gy = f.from.y + (f.target.y - f.from.y) * e + f.lift * Math.sin(Math.PI * Math.min(1, p * 1.08)) ** 0.85;
        fyaw = f.fromYaw + angleDiff(f.fromYaw, f.targetYaw) * e;
        pitch = f.mode === 1 ? -0.55 * (1 - sstep(0, 0.45, p)) + (0.25 * walkish) * sstep(0.45, 1, p) + 0.2 * Math.sin(Math.PI * p) : 0.12 * Math.sin(Math.PI * p);
      }
      // уклон земли под стопой (пятка — носок)
      const sy = Math.sin(fyaw), cy = Math.cos(fyaw);
      if (this.ground && grounded) {
        const hx = gx + sy * HEEL_Z, hz = gz + cy * HEEL_Z;
        const tx = gx + sy * (TOE_Z + 0.02), tz = gz + cy * (TOE_Z + 0.02);
        const hh = this._groundAt(hx, hz, P.y), ht = this._groundAt(tx, tz, P.y);
        const gp = Math.atan2(ht - hh, HEEL_Z - TOE_Z - 0.02);
        f.gp = damp(f.gp, clamp(gp, -0.6, 0.6), 18, dt);
        // поперёк стопы: внешний и внутренний край
        const W = 0.05;
        const hr = this._groundAt(gx + cy * W, gz - sy * W, P.y), hl = this._groundAt(gx - cy * W, gz + sy * W, P.y);
        f.gr = damp(f.gr, clamp(Math.atan2(hr - hl, 2 * W), -0.5, 0.5), 18, dt);
      } else { f.gp = damp(f.gp, 0, 10, dt); f.gr = damp(f.gr, 0, 10, dt); }
      const total = pitch + (f.mode === 0 ? f.gp : f.gp * (1 - f.prog * 0.5));
      f.pitch = total;
      // лодыжка: перекат вокруг пятки (носок вверх) или вокруг подушечки (пятка вверх) — относительно земли,
      // поэтому выбор опоры — по наклону походки без уклона склона (иначе в гору стопа «перекатывалась» на пятке
      // и подушечка повисала над землёй)
      const pz = pitch >= 0 ? HEEL_Z : TOE_Z + 0.045;
      const cp = Math.cos(total), sp = Math.sin(total);
      if (this.ground && grounded) {
        // Точка опоры подошвы (пятка/подушечка) стоит на земле ПОД НЕЙ, а не под лодыжкой: иначе на склоне
        // подошва висит над землёй (лицом в гору — на 4–6 см). Дальний конец подошвы тоже не должен уходить в землю.
        const gPv = this._groundAt(gx + sy * pz, gz + cy * pz, P.y);
        const ez = pz > 0 ? TOE_Z : HEEL_Z;
        const de = pz + (ez - pz) * cp;
        const gEnd = this._groundAt(gx + sy * de, gz + cy * de, P.y) - (pz - ez) * sp;
        const pivotY = Math.max(gPv, gEnd);
        if (f.mode === 0) gy = pivotY;
        else {
          // в переносе — плавно к той же высоте опоры к моменту постановки (без щелчка при касании)
          const base = f.from.y + (f.target.y - f.from.y) * smoother(f.prog);
          gy += sstep(0.55, 1, f.prog) * (pivotY - base);
        }
      }
      // вектор от точки опоры до лодыжки в системе стопы: (0, ANKLE_Y, -pz), повёрнут на pitch вокруг X
      const ly = ANKLE_Y * cp + pz * sp;
      const lz = ANKLE_Y * sp - pz * cp;
      f.ankle.set(gx + sy * (pz + lz), gy + ly, gz + cy * (pz + lz));
      // крен по склону: подошва ложится на землю целиком, лодыжка смещается вбок от центра подошвы
      const roll = f.mode === 0 ? f.gr : f.gr * (1 - f.prog);
      if (roll) {
        const sr = Math.sin(roll);
        f.ankle.x -= cy * ANKLE_Y * sr;
        f.ankle.z += sy * ANKLE_Y * sr;
        f.ankle.y -= ANKLE_Y * (1 - Math.cos(roll));
      }
      // цель в воздухе: под тазом, колени поджаты на взлёте
      const hip = this.hip[i];
      const tuck = rise * (i === 0 ? 1 : 0.75);
      yawQuat(hipYawW, _q1);
      _v1.set(0, -(LEG * (0.92 - 0.3 * tuck)), -0.07 * tuck + 0.03 * (1 - rise) * (i ? 1 : -1)).applyQuaternion(_q1);
      f.air.copy(hip).add(_v1);
      if (air > 0.001) f.ankle.lerp(f.air, air);
    }
    // 2) таз опускаем, если стопа в опоре не достаёт
    let need = 0, needStance = 0;
    for (const f of this.feet) {
      const hip = this.hip[f.side < 0 ? 0 : 1];
      const dx = f.ankle.x - hip.x, dz = f.ankle.z - hip.z;
      const reach = LEG * 0.985;
      const h2 = reach * reach - dx * dx - dz * dz;
      const maxHip = f.ankle.y + Math.sqrt(Math.max(0.0004, h2));
      const w = f.mode === 0 ? 1 : 0.35;
      need = Math.max(need, (hip.y - maxHip) * w);
      if (f.mode === 0) needStance = Math.max(needStance, hip.y - maxHip);
    }
    need = Math.max(0, Math.min(0.22, need)) * (1 - this.airW);
    this.drop = need > this.drop ? damp(this.drop, need, 40, dt) : damp(this.drop, need, 12, dt);
    // опорная стопа обязана доставать землю в тот же кадр (иначе на склоне первый кадр опоры она висит)
    this.drop = Math.max(this.drop, Math.max(0, Math.min(0.22, needStance)) * (1 - this.airW));
    b[B.pelvis].position.y -= this.drop;
    for (const h of this.hip) h.y -= this.drop;
    for (const s of this.shoulder) s.y -= this.drop;
    this.chestM.elements[13] -= this.drop;
    // 3) IK
    for (const f of this.feet) {
      const i = f.side < 0 ? 0 : 1;
      const hip = this.hip[i];
      const bT = b[i ? B.thighR : B.thighL], bS = b[i ? B.shinR : B.shinL], bF = b[i ? B.footR : B.footL];
      yawQuat(f.mode === 0 ? f.yaw : hipYawW, _q1);
      _pole.set(f.side * 0.12, 0, -1).applyQuaternion(_q1);
      solveTwoBone(hip, f.ankle, THIGH, SHIN, _pole, _d1, _d2);
      aimQuat(DOWN, FWD, _d1, _pole, _q2);                 // бедро в мире
      bT.quaternion.copy(this.qPelvis).invert().multiply(_q2);
      aimQuat(DOWN, FWD, _d2, _pole, _q3);                 // голень в мире
      bS.quaternion.copy(_q2).invert().multiply(_q3);
      const fyaw = f.mode === 0 ? f.yaw : f.fromYaw + angleDiff(f.fromYaw, f.targetYaw) * smoother(f.prog);
      const roll = f.mode === 0 ? f.gr : f.gr * (1 - f.prog);
      _e.set(f.pitch + this.airW * (-0.25 * rise + 0.1), fyaw, roll * (1 - this.airW), 'YXZ');
      _q4.setFromEuler(_e);
      bF.quaternion.copy(_q3).invert().multiply(_q4);
    }
  }

  // ---------- руки ----------
  _arms(dt, st, hk, gw, air, rise, breath) {
    const b = this.b;
    const t = this.t;
    const ph = this.phase * TAU;
    const run = this.runW;
    const act = this.act;
    const actK = this.actW;
    const A = (0.3 + 0.42 * run) * gw;
    const h = this.hold;
    if (!act || act.name !== 'wave' || act.t < 0.05) this.waveSide = h.one + h.bat + h.umbrella > 0.3 ? -1 : 1;
    for (const side of [-1, 1]) {
      const i = side < 0 ? 0 : 1;
      const bA = b[i ? B.armR : B.armL], bFo = b[i ? B.foreR : B.foreL], bH = b[i ? B.handR : B.handL];
      // базовая поза: маятник, полёт, покой
      const swing = side * A * Math.cos(ph);
      let ax = 0.06 + swing + 0.02 * breath * (1 - gw) + 0.15 * run * gw;
      let az = side * (0.05 + 0.1 * run * gw);
      let fx = 0.24 + 0.22 * gw + 0.95 * run * gw + 0.18 * run * gw * Math.sin(ph + (side > 0 ? Math.PI : 0));
      const flail = Math.sin(t * 9 + side) * 0.18 * (1 - rise);
      // в воздухе: на взлёте руки вперёд-вверх, при падении — вверх и в стороны, локти согнуты
      ax = ax * (1 - air) + air * (1.15 * rise + 0.45 * (1 - rise));
      az = az * (1 - air) + air * side * (0.55 * rise + 1.75 * (1 - rise) + flail);
      fx = fx * (1 - air) + air * (0.55 * rise + 0.75 * (1 - rise));
      bA.quaternion.setFromEuler(_eX.set(ax, side * 0.1 * gw * run, az));
      bFo.quaternion.setFromEuler(_eX.set(fx, 0, 0));
      bH.quaternion.setFromEuler(_eX.set(0.08 + 0.1 * gw, 0, side * -0.05));
      let curl = 0.32 + 0.5 * run * gw + 0.2 * air;

      // удержание (IK к точке в системе груди)
      let ikW = 0;
      let mode = 'grip';
      const right = side > 0;
      const hold = this.hold;
      const hw = right ? Math.max(hold.two, hold.one, hold.bat, hold.umbrella) : hold.two;
      if (hw > 0.001) {
        // выбираем доминирующий вид для этой руки
        let kind = 'two';
        if (right) for (const k of HOLD_KINDS) if (hold[k] > hold[kind]) kind = k;
        this._holdTarget(kind, side, _v2, _v3, _v4, _pole);
        mode = kind === 'two' ? 'two' : 'grip';
        ikW = hw;
        curl = curl * (1 - hw) + hw * (kind === 'two' ? 0.55 : 1.05);
      }
      // действия
      if (act && actK > 0.001) {
        const u = act.t / act.dur;
        if (this._actTarget(act.name, u, side, _v1, _d1, _d2)) {
          const wave = act.name === 'wave';
          // смешиваем цель действия с целью удержания
          if (ikW > 0.001) {
            _v2.lerp(_v1, actK); _v3.lerp(_d1, actK).normalize(); _v4.lerp(_d2, actK).normalize();
          } else {
            _v2.copy(_v1); _v3.copy(_d1); _v4.copy(_d2);
            this._defaultPole(side, _pole);
          }
          // приветствие: локоть вниз-наружу, открытая ладонь (кисть рядом с головой, а не за ней)
          if (wave) _pole.lerp(_v1.set(side, -0.55, 0.3).transformDirection(this.chestM), actK).normalize();
          ikW = Math.max(ikW, actK);
          mode = wave ? 'two' : 'grip';
          curl = curl * (1 - actK) + actK * (wave ? 0.06 : act.name === 'throw' && u > 0.42 ? 0.05 : act.name === 'strum' && !right ? 0.8 : 0.95);
        }
      }
      if (ikW > 0.001) this._armIK(side, _v2, _v3, _v4, _pole, ikW, mode);
      // пальцы
      this.fingerCurl[i] = damp(this.fingerCurl[i], curl, 14, dt);
      const c = this.fingerCurl[i];
      b[i ? B.fingR : B.fingL].quaternion.setFromEuler(_eX.set(0, 0, -side * c * 0.95));
      b[i ? B.fing2R : B.fing2L].quaternion.setFromEuler(_eX.set(0, 0, -side * c * 1.15));
      b[i ? B.thumbR : B.thumbL].quaternion.setFromEuler(_eX.set(-0.25 * c, side * 0.25 * c, -side * 0.35 * c));
    }
  }

  // Поза удержания: точка хвата (мир), направление «вверх по предмету», нормаль ладони, полюс локтя.
  _holdTarget(kind, side, outPos, outUp, outPalm, outPole) {
    const M = this.chestM;
    const toW = (x, y, z, out) => out.set(x, y, z).applyMatrix4(M);
    const toD = (x, y, z, out) => out.set(x, y, z).transformDirection(M);
    switch (kind) {
      case 'two':
        toW(side * 0.17, -0.12, -0.32, outPos);
        toD(0, 0.25, -1, outUp);           // для «двух рук» up = направление пальцев
        toD(-side, 0, 0, outPalm);
        toD(side * 0.6, -0.75, 0.35, outPole);
        break;
      case 'one':
        toW(0.15, -0.06, -0.35, outPos);
        toD(0, 1, 0.05, outUp);
        toD(-1, 0, -0.25, outPalm);
        toD(0.55, -0.8, 0.3, outPole);
        break;
      case 'bat':
        toW(0.16, 0.0, -0.2, outPos);
        toD(0.45, 0.75, 0.5, outUp);
        toD(-0.45, 0.1, 0.85, outPalm);
        toD(0.75, -0.6, 0.1, outPole);
        break;
      default: // umbrella
        toW(0.16, 0.37, -0.2, outPos);
        toD(0, 1, 0, outUp);
        toD(-1, 0, 0.15, outPalm);
        toD(0.85, -0.35, 0.25, outPole);
    }
  }

  _defaultPole(side, out) {
    return out.set(side * 0.6, -0.7, 0.4).transformDirection(this.chestM);
  }

  // Ключевые точки действий в системе груди. Возвращает false, если рука в действии не участвует.
  _actTarget(name, u, side, outPos, outUp, outPalm) {
    const M = this.chestM;
    const right = side > 0;
    const key = (frames) => { // [[u, x, y, z, ux, uy, uz], ...] — линейно между кадрами со сглаживанием
      let k = 0;
      while (k < frames.length - 2 && u > frames[k + 1][0]) k++;
      const a = frames[k], c = frames[k + 1];
      const f = smoother(clamp((u - a[0]) / Math.max(1e-4, c[0] - a[0]), 0, 1));
      outPos.set(a[1] + (c[1] - a[1]) * f, a[2] + (c[2] - a[2]) * f, a[3] + (c[3] - a[3]) * f).applyMatrix4(M);
      outUp.set(a[4] + (c[4] - a[4]) * f, a[5] + (c[5] - a[5]) * f, a[6] + (c[6] - a[6]) * f).transformDirection(M);
    };
    switch (name) {
      case 'wave': {
        // машем той рукой, что свободна (правая занята битой/зонтом/кружкой — левой)
        if (side !== this.waveSide) return false;
        const wv = Math.sin(u * ACTIONS.wave * 11);
        const a = 0.18 + 0.38 * wv; // кисть качается из стороны в сторону вокруг запястья
        outPos.set(side * (0.43 + 0.02 * wv), 0.23, -0.12).applyMatrix4(M);
        outUp.set(side * Math.sin(a), Math.cos(a), -0.08).transformDirection(M);
        outPalm.set(side * 0.15, 0.05, -1).transformDirection(M);
        return true;
      }
      case 'swing':
        if (!right) return false;
        // руки короткие, а живот большой: кисть не заходит за середину груди и держится перед ней —
        // через тело «проносит» биту поворот корпуса, а не рука (иначе кисть уходила внутрь груди)
        key([[0, 0.16, 0.0, -0.2, 0.45, 0.75, 0.5], [0.28, 0.24, 0.14, -0.05, 0.35, 0.55, 0.75], [0.42, 0.2, -0.01, -0.35, -0.3, 0.2, -0.95],
          [0.55, 0.07, -0.02, -0.33, -0.95, 0.15, -0.25], [0.75, 0.13, 0.05, -0.31, -0.35, 0.8, 0.45], [1, 0.16, 0.0, -0.2, 0.45, 0.75, 0.5]]);
        outPalm.set(-0.4, 0.1, 0.9).transformDirection(M);
        return true;
      case 'throw':
        if (!right) return false;
        key([[0, 0.15, -0.06, -0.35, 0, 1, 0], [0.35, 0.22, 0.3, 0.12, 0, 0.6, 0.8], [0.5, 0.15, 0.2, -0.36, 0, 0.3, -0.95],
          [0.7, 0.14, -0.05, -0.36, 0, -0.4, -0.9], [1, 0.15, -0.06, -0.35, 0, 1, 0]]);
        outPalm.set(-0.6, -0.2, -0.6).transformDirection(M);
        return true;
      case 'strum': {
        if (right) {
          // бьёт по струнам над животом (ниже короткая рука не дотягивается — кисть уходила в живот)
          const y = -0.08 + 0.04 * Math.sin(u * ACTIONS.strum * 18);
          outPos.set(0.12, y, -0.33).applyMatrix4(M);
          outUp.set(-0.3, 0.2, -1).transformDirection(M);
          outPalm.set(-0.2, 0.9, 0).transformDirection(M);
        } else {
          outPos.set(-0.3, -0.06, -0.36).applyMatrix4(M);
          outUp.set(0.2, 0.9, -0.3).transformDirection(M);
          outPalm.set(0.9, 0, -0.3).transformDirection(M);
        }
        return true;
      }
      case 'toggle':
        if (!right) return false;
        key([[0, 0.15, -0.06, -0.35, 0, 1, 0], [0.4, 0.15, 0.04, -0.4, 0, 1, -0.2], [0.6, 0.15, 0.04, -0.4, 0.4, 0.9, -0.1], [1, 0.15, -0.06, -0.35, 0, 1, 0]]);
        outPalm.set(-1, 0, -0.2).transformDirection(M);
        return true;
      default:
        return false;
    }
  }

  // IK руки: хват в точке pos, «вверх по предмету» up, ладонь palm, локоть к pole; w — вес поверх FK.
  _armIK(side, pos, up, palm, pole, w, mode) {
    const b = this.b;
    const i = side < 0 ? 0 : 1;
    const bA = b[i ? B.armR : B.armL], bFo = b[i ? B.foreR : B.foreL], bH = b[i ? B.handR : B.handL];
    const a0 = armDir(side), n0 = palmDir(side);
    const two = mode === 'two';
    // ориентация кисти в мире
    if (two) aimQuat(a0, n0, up, palm, _q1);           // пальцы вдоль up, ладонь к предмету
    else aimQuat(MINUS_Z, n0, up, palm, _q1);          // ось кулака вдоль up
    // запястье = хват − смещение хвата, повёрнутое кистью
    const off = two ? palmOffset(side) : gripOffset(side);
    off.applyQuaternion(_q1);
    _v1.copy(pos).sub(off);
    const S = this.shoulder[i];
    solveTwoBone(S, _v1, UPPER, FORE, pole, _d1, _d2);
    const back = BACK;
    aimQuat(a0, back, _d1, pole, _q2);
    _q3.copy(this.qClav[i]).invert().multiply(_q2);
    bA.quaternion.slerp(_q3, w);
    aimQuat(a0, back, _d2, pole, _q3);
    _q4.copy(_q2).invert().multiply(_q3);
    bFo.quaternion.slerp(_q4, w);
    _q4.copy(_q3).invert().multiply(_q1);
    bH.quaternion.slerp(_q4, w);
  }

  // ---------- лицо ----------
  _face(dt, st, env, idleW) {
    const b = this.b;
    const t = this.t;
    // моргание
    this.blinkT -= dt;
    if (this.blinkT <= 0 && this.blinkPhase < 0) {
      this.blinkPhase = 0;
      this.doubleBlink = Math.random() < 0.2;
      this.blinkT = 2 + Math.random() * 4;
    }
    let close = 0;
    if (this.blinkPhase >= 0) {
      this.blinkPhase += dt / 0.15;
      close = Math.sin(Math.PI * Math.min(1, this.blinkPhase));
      if (this.blinkPhase >= 1) {
        if (this.doubleBlink) { this.doubleBlink = false; this.blinkPhase = -0.6; } else this.blinkPhase = -1;
      }
    } else if (this.blinkPhase > -1 && this.blinkPhase < 0) {
      this.blinkPhase += dt / 0.15;
      if (this.blinkPhase >= 0) this.blinkPhase = 0;
    }
    this.surprise = damp(this.surprise, 0, 2.2, dt);
    const sur = this.surprise;
    const act = this.act;
    const wave = act && act.name === 'wave' ? this.actW : 0;
    const focus = act && (act.name === 'swing' || act.name === 'throw') ? this.actW : 0;
    // глаз — тёмный овал: моргание сплющивает его по вертикали, удивление чуть увеличивает
    const lid = Math.max(0.08, 1 - 0.92 * close) * (1 + 0.18 * sur) * (1 - 0.15 * focus);
    for (const k of [B.lidL, B.lidR]) b[k].scale.set(1 + 0.1 * sur, lid, 1);
    // взгляд: короткие саккады + цель
    this.gazeT -= dt;
    if (this.gazeT <= 0) {
      this.gazeT = 0.7 + Math.random() * 2.2;
      this.gazeGoal.set((Math.random() - 0.5) * 0.5, (Math.random() - 0.5) * 0.22);
    }
    let gx = this.gazeGoal.x, gy = this.gazeGoal.y;
    if (env && env.lookAt) {
      _v1.copy(env.lookAt).sub(this.root.position);
      const ly = angleDiff(this.root.rotation.y + this.headLook.x, Math.atan2(-_v1.x, -_v1.z));
      if (Math.abs(ly) < 1.2) { gx = clamp(ly, -0.35, 0.35) + this.gazeGoal.x * 0.15; gy = clamp(Math.atan2(_v1.y - 1.05, Math.hypot(_v1.x, _v1.z)) - this.headLook.y, -0.25, 0.25); }
    }
    this.gaze.x += (gx - this.gaze.x) * Math.min(1, dt * 25);
    this.gaze.y += (gy - this.gaze.y) * Math.min(1, dt * 25);
    for (const k of [B.eyeL, B.eyeR]) b[k].rotation.set(this.gaze.y, this.gaze.x, 0);
    // рот
    const talk = st.talk || 0;
    this.talkPh += dt * (8 + 4 * Math.sin(t * 1.3));
    const syll = Math.abs(Math.sin(this.talkPh)) * (0.55 + 0.45 * Math.sin(this.talkPh * 0.37 + 1.1));
    const goal = Math.max(talk * (0.25 + 0.75 * syll), sur * 0.85, wave * 0.35, focus * 0.25);
    this.mouth = damp(this.mouth, goal, 18, dt);
    b[B.jaw].rotation.set(MOUTH_DEPTH * (1 - this.mouth), 0, 0);
    // брови
    const browUp = 0.012 * Math.max(sur, wave * 0.7) + 0.004 * talk * syll;
    for (const [k, s] of [[B.browL, -1], [B.browR, 1]]) {
      const bone = b[k];
      bone.position.y = this.browY0[k === B.browL ? 0 : 1] + browUp - 0.004 * focus;
      bone.rotation.set(0, 0, s * (0.12 * focus - 0.06 * sur));
    }
  }
}
