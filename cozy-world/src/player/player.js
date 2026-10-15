// Контроллер игрока: ходьба/бег/прыжок, коллизии, спуск по склону и камера (1-е / 3-е лицо).

import * as THREE from 'three';
import { clamp, damp } from '../util/math.js';
import { heightAt, WATER_Y, WORLD_RADIUS, isInterior, groundTypeAt } from '../world/terrain-data.js';
import { AVATAR } from './avatar.js';

const GRAVITY = 20;
const JUMP = 6.4;
const WALK = 4.2;
const RUN = 7.4;
const THIRD_DIST = 3.4;

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
    this.wantThird = false;
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

  get thirdPerson() { return this.camDist > 0.5; }

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
    this.lookDX = look.x;
    this.lookDY = look.y;
    if (!this.frozen) {
      const sens = 0.0022 * input.sensitivity;
      this.yaw -= look.x * sens;
      this.pitch = clamp(this.pitch - look.y * sens, -1.4, 1.4);
    }

    const ax = this.frozen ? { x: 0, y: 0 } : input.axes();
    const mag = Math.min(1, Math.hypot(ax.x, ax.y));
    const wet = !isInterior(this.pos.x) && heightAt(this.pos.x, this.pos.z) < WATER_Y + 0.25;
    this.sprinting = input.sprint && mag > 0.1 && !this.frozen;
    const maxSpeed = (this.sprinting ? RUN : WALK) * mag * (wet ? 0.55 : 1);
    const s = Math.sin(this.yaw), c = Math.cos(this.yaw);
    const tx = (c * ax.x - s * ax.y) * (mag ? maxSpeed / mag : 0);
    const tz = (-s * ax.x - c * ax.y) * (mag ? maxSpeed / mag : 0);

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
    this.roll = damp(this.roll, -ax.x * 0.012 * Math.min(1, this.speed / 4), 8, dt);
  }

  // Камера: от первого лица глаза в голове, от третьего - за спиной на пружинке.
  applyCamera(camera, dt) {
    const target = this.wantThird ? this.thirdDist : 0;
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
}
