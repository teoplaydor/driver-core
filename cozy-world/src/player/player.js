// Контроллер игрока: ходьба/бег/прыжок, коллизии, спуск по склону и камера.
// Режимы камеры (C1, cameraMode): 'fp' — от первого лица, 'tp' — от третьего за спиной, 'top' — вид сверху
// (телефон по умолчанию): там движение относительно камеры, персонаж поворачивается по ходу движения.

import * as THREE from 'three';
import { clamp, damp, dampAngle } from '../util/math.js';
import { heightAt, WATER_Y, WORLD_RADIUS, isInterior, groundTypeAt } from '../world/terrain-data.js';
import { AVATAR } from './avatar.js';
import { TopCamera } from './camera-top.js';

const GRAVITY = 20;
const JUMP = 6.4;
const WALK = 4.2;
const RUN = 7.4;
const THIRD_DIST = 3.4;
const FOV = 78; // от 1-го и 3-го лица (вид сверху подбирает свой по экрану)
const TURN = 13; // скорость разворота персонажа по ходу движения в виде сверху

export class Player {
  constructor(collisions) {
    this.collisions = collisions;
    this.pos = new THREE.Vector3();
    this.vel = new THREE.Vector3();
    this.yaw = 0;
    this.pitch = 0;
    this.onGround = true;
    this.coyote = 0;
    this.jumpBuf = 0;
    this.speed = 0;
    this.stepDist = 0;
    this.camDist = 0;
    this.thirdDist = THIRD_DIST;
    this.cameraMode = 'fp'; // C1: 'fp' | 'tp' | 'top'
    this.topCam = new TopCamera(collisions);
    this.autoMove = null; // вид сверху: { x, z, mag, run } — направление в мире (идём к точке/цели по тапу)
    this.faceYaw = null; // вид сверху: куда повернуться стоя (на цель)
    this._runK = 0;
    this.bob = 0;
    this.roll = 0;
    this.sprinting = false;
    this.frozen = false;
    this.lookDX = 0;
    this.lookDY = 0;
    this.events = { step: null, jump: null, land: null };
    this._tmp = new THREE.Vector3();
    this._dir = new THREE.Vector3();
  }

  spawn(x, z, yaw = 0) {
    this.pos.set(x, heightAt(x, z), z);
    this.vel.set(0, 0, 0);
    this.yaw = yaw;
    this.pitch = 0;
    this.onGround = true;
  }

  teleport(x, y, z, yaw) {
    this.pos.set(x, y, z);
    this.vel.set(0, 0, 0);
    if (yaw !== undefined) this.yaw = yaw;
    this.onGround = false;
  }

  // Совместимость со старым флагом (до C1): true — не от первого лица.
  get wantThird() { return this.cameraMode !== 'fp'; }
  set wantThird(v) { this.cameraMode = v ? (this.cameraMode === 'fp' ? 'tp' : this.cameraMode) : 'fp'; }

  get thirdPerson() { return this.cameraMode === 'top' || this.camDist > 0.5; }

  // Смена режима камеры. Вид сверху начинается за спиной персонажа.
  setCameraMode(mode) {
    if (mode === this.cameraMode) return;
    const was = this.cameraMode;
    this.cameraMode = mode;
    this.autoMove = null;
    this.faceYaw = null;
    if (mode === 'top') {
      if (was !== 'top') this.topCam.yaw = this.yaw;
      this.topCam.snap();
      this.camDist = this.thirdDist;
    } else {
      if (was === 'top') { this.yaw = this.topCam.yaw; this.pitch = -0.12; }
      this.camDist = mode === 'tp' ? this.thirdDist : 0;
    }
  }

  _ground(x, z, y) { return this.collisions.supportY(x, z, y); }

  _tryAt(x, z) {
    const t = this._tmp.set(x, this.pos.y, z);
    this.collisions.resolve(t, AVATAR.radius, this.pos.y, AVATAR.height);
    if (!isInterior(t.x)) {
      const r = Math.hypot(t.x, t.z), maxR = WORLD_RADIUS - 3;
      if (r > maxR) { t.x *= maxR / r; t.z *= maxR / r; }
    }
    const gy = this._ground(t.x, t.z, this.pos.y);
    const deep = !isInterior(t.x) && heightAt(t.x, t.z) < WATER_Y - 1.0;
    if (deep || (this.onGround && gy - this.pos.y > 0.62)) return false;
    this.pos.x = t.x;
    this.pos.z = t.z;
    return true;
  }

  update(dt, input) {
    // обзор
    const look = input.takeLook();
    const top = this.cameraMode === 'top';
    this.lookDX = top ? 0 : look.x;
    this.lookDY = top ? 0 : look.y;
    if (!this.frozen) {
      const sens = 0.0022 * input.sensitivity;
      if (top) {
        // вид сверху: перетаскивание по горизонтали и поворот двумя пальцами крутят камеру, а не персонажа
        this.topCam.yaw -= look.x * sens * 1.6 + (input.takeTwist?.() || 0);
      } else {
        this.yaw -= look.x * sens;
        this.pitch = clamp(this.pitch - look.y * sens, -1.4, 1.4);
      }
    }

    const ax = this.frozen ? { x: 0, y: 0 } : input.axes();
    let mag = Math.min(1, Math.hypot(ax.x, ax.y));
    const wet = !isInterior(this.pos.x) && heightAt(this.pos.x, this.pos.z) < WATER_Y + 0.25;
    let tx, tz;
    if (top) {
      // вверх по экрану = от камеры; персонаж плавно разворачивается по ходу движения
      const cyaw = this.topCam.yaw;
      let dx = 0, dz = 0, run = false;
      if (mag > 0.02) {
        const s = Math.sin(cyaw), c = Math.cos(cyaw);
        dx = c * ax.x - s * ax.y; dz = -s * ax.x - c * ax.y;
        // сильное отклонение джойстика — бег (плавно: 85%..100% хода разгоняет до бега)
        const tm = input.touchMag ?? 0;
        run = input.sprint || tm > 0.86;
        this._runK = tm > 0.86 && !input.sprint ? clamp((tm - 0.86) / 0.12, 0, 1) : run ? 1 : 0;
      } else if (this.autoMove && !this.frozen) {
        const a = this.autoMove;
        mag = a.mag; dx = a.x * mag; dz = a.z * mag; run = !!a.run;
        this._runK = run ? 1 : 0;
      }
      const dl = Math.hypot(dx, dz);
      this.sprinting = run && mag > 0.1 && !this.frozen;
      const sp = (WALK + (RUN - WALK) * (this.sprinting ? this._runK : 0)) * mag * (wet ? 0.55 : 1);
      tx = dl > 1e-6 ? (dx / dl) * sp : 0;
      tz = dl > 1e-6 ? (dz / dl) * sp : 0;
      if (dl > 1e-6 && mag > 0.05 && !this.frozen) {
        this.faceYaw = null;
        this.yaw = dampAngle(this.yaw, Math.atan2(-dx, -dz), TURN, dt);
      } else if (this.faceYaw !== null && !this.frozen) {
        this.yaw = dampAngle(this.yaw, this.faceYaw, TURN * 0.7, dt);
      }
      this.pitch = damp(this.pitch, -0.08, 6, dt); // голова прямо, чуть вниз
    } else {
      this.sprinting = input.sprint && mag > 0.1 && !this.frozen;
      const maxSpeed = (this.sprinting ? RUN : WALK) * mag * (wet ? 0.55 : 1);
      const s = Math.sin(this.yaw), c = Math.cos(this.yaw);
      tx = (c * ax.x - s * ax.y) * (mag ? maxSpeed / mag : 0);
      tz = (-s * ax.x - c * ax.y) * (mag ? maxSpeed / mag : 0);
    }

    const acc = (this.onGround ? (mag ? 46 : 64) : 14) * dt;
    const dx = tx - this.vel.x, dz = tz - this.vel.z;
    const dl = Math.hypot(dx, dz);
    if (dl <= acc || dl < 1e-6) { this.vel.x = tx; this.vel.z = tz; } else { this.vel.x += (dx / dl) * acc; this.vel.z += (dz / dl) * acc; }

    // прыжок: буфер нажатия и "койот-тайм"
    if (!this.frozen && input.consume('jump')) this.jumpBuf = 0.12;
    this.jumpBuf -= dt;
    this.coyote -= dt;
    if (this.jumpBuf > 0 && (this.onGround || this.coyote > 0)) {
      this.vel.y = JUMP * (wet ? 0.7 : 1);
      this.onGround = false;
      this.coyote = 0;
      this.jumpBuf = 0;
      this.events.jump?.();
    }

    // горизонталь: пробуем полный шаг, потом скольжение вдоль осей
    const ox = this.pos.x, oz = this.pos.z;
    const nx = ox + this.vel.x * dt, nz = oz + this.vel.z * dt;
    if (!this._tryAt(nx, nz)) {
      if (this._tryAt(nx, oz)) this.vel.z = 0;
      else if (this._tryAt(ox, nz)) this.vel.x = 0;
      else { this.vel.x = 0; this.vel.z = 0; }
    }

    // вертикаль
    if (!this.onGround) this.vel.y -= GRAVITY * dt;
    this.pos.y += this.vel.y * dt;
    const gy = this._ground(this.pos.x, this.pos.z, this.pos.y);
    if (this.pos.y <= gy) {
      if (!this.onGround && this.vel.y < -6) this.events.land?.(Math.min(1, -this.vel.y / 14));
      this.pos.y = gy;
      this.vel.y = 0;
      if (!this.onGround) this.coyote = 0;
      this.onGround = true;
    } else if (this.onGround && this.vel.y <= 0 && this.pos.y - gy < 0.4) {
      this.pos.y = gy; // прилипаем к склону на спуске
    } else if (this.onGround) {
      this.onGround = false;
      this.coyote = 0.1;
    }

    this.speed = Math.hypot(this.vel.x, this.vel.z);
    if (this.onGround && this.speed > 0.6) {
      this.stepDist += this.speed * dt;
      const stride = this.sprinting ? 2.3 : 1.65;
      if (this.stepDist >= stride) {
        this.stepDist -= stride;
        this.events.step?.(wet ? 'water' : groundTypeAt(this.pos.x, this.pos.z), this.sprinting);
      }
      this.bob += this.speed * dt * 1.55;
    }
    this.roll = damp(this.roll, top ? 0 : -ax.x * 0.012 * Math.min(1, this.speed / 4), 8, dt);
  }

  // Камера: от первого лица глаза в голове, от третьего - за спиной на пружинке, сверху - camera-top.js.
  applyCamera(camera, dt) {
    if (this.cameraMode === 'top') {
      this.topCam.update(camera, dt, this);
      this._fov(camera, this.topCam.fov);
      return;
    }
    this._fov(camera, FOV);
    const target = this.cameraMode === 'tp' ? this.thirdDist : 0;
    this.camDist = damp(this.camDist, target, 10, dt);
    if (Math.abs(this.camDist - target) < 0.01) this.camDist = target;
    const p = this.pos;
    camera.rotation.order = 'YXZ';
    if (this.camDist < 0.05) {
      const bobY = Math.sin(this.bob * 2) * 0.014 * Math.min(1, this.speed / 4);
      camera.position.set(p.x, p.y + AVATAR.eye + bobY, p.z);
      camera.rotation.set(this.pitch, this.yaw, this.roll);
    } else {
      const cp = Math.cos(this.pitch);
      this._dir.set(-Math.sin(this.yaw) * cp, Math.sin(this.pitch), -Math.cos(this.yaw) * cp);
      camera.position.set(p.x, p.y + 0.95, p.z).addScaledVector(this._dir, -this.camDist);
      camera.position.y += 0.25;
      const floor = heightAt(camera.position.x, camera.position.z) + 0.3;
      if (!isInterior(camera.position.x) && camera.position.y < floor) camera.position.y = floor;
      camera.rotation.set(this.pitch, this.yaw, 0);
    }
  }

  _fov(camera, fov) {
    if (Math.abs(camera.fov - fov) < 0.01) return;
    camera.fov = fov;
    camera.updateProjectionMatrix();
  }
}
