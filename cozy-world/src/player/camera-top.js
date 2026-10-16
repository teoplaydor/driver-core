// Камера «вид сверху» (C1: player.cameraMode === 'top') — на телефоне по умолчанию, на ПК по клавише C.
// Наклон ~55° (чуть положе вблизи, круче вдали), дистанция 5–20 м (щипок / колесо), поворот по yaw
// (перетаскивание по экрану, поворот двумя пальцами, ПКМ), упреждение по скорости, плавное следование.
// Камера не уходит под рельеф и не прячется за стенами: сначала поднимается круче, потом подъезжает ближе.
// В помещениях (квартира, арена) — ближе и круче, крыши над игроком скрывает top-view.js (C5).

import { clamp, damp, smoothstep } from '../util/math.js';
import { heightAt, isInterior } from '../world/terrain-data.js';

const D2R = Math.PI / 180;

export const TOP = {
  dist: 11, min: 5, max: 20,
  pitchNear: 50 * D2R, pitchFar: 61 * D2R, // наклон при минимальной и максимальной дистанции
  pitchMax: 84 * D2R,
  focusY: 0.85, // точка, на которую смотрим: середина персонажа
  lead: 0.34, leadMax: 1.7, // упреждение: секунды скорости и предел, м
  roomDist: 8.5, roomPitch: 10 * D2R, // в помещении: не дальше и на столько круче
};

export class TopCamera {
  constructor(collisions) {
    this.col = collisions;
    this.yaw = 0; // поворот камеры вокруг Y (как Object3D.rotation.y): 0 — смотрим на север
    this.dist = TOP.dist; // желаемая дистанция (зум)
    this.curDist = TOP.dist; // сглаженная желаемая
    this.effDist = TOP.dist; // с учётом препятствий
    this.pitch = 55 * D2R;
    this.fx = 0; this.fy = 0; this.fz = 0; // сглаженная точка фокуса
    this.lx = 0; this.lz = 0; // упреждение
    this.room = 0; // 0..1 — насколько мы «в помещении»
    this.indoor = false; // под большой крышей (арена) — ставит top-view.js
    this.fov = 50;
    this._snap = true;
    this.justSnapped = false; // в этом кадре камера встала на место без сглаживания (вход в режим, телепорт)
    this._cand = [];
    this._seg = { fx: 0, fy: 0, fz: 0 };
  }

  zoom(f) { this.dist = clamp(this.dist * f, TOP.min, TOP.max); }

  // Следующий кадр без сглаживания (вход в режим, телепорт).
  snap() { this._snap = true; }

  // Вертикальный угол обзора по пропорциям экрана: в альбомной ориентации телефона ~48°, в портретной шире.
  fovFor(aspect) {
    return clamp((2 * Math.atan(Math.tan(42 * D2R) / Math.max(aspect, 0.3))) / D2R, 48, 72);
  }

  update(camera, dt, player) {
    const p = player.pos;
    const inRoom = isInterior(p.x);
    const roomish = inRoom || this.indoor;
    // фокус: середина персонажа + упреждение по скорости
    let lx = player.vel.x * TOP.lead, lz = player.vel.z * TOP.lead;
    const ll = Math.hypot(lx, lz);
    if (ll > TOP.leadMax) { lx *= TOP.leadMax / ll; lz *= TOP.leadMax / ll; }
    const tx = p.x, ty = p.y + TOP.focusY, tz = p.z;
    if (this._snap || Math.hypot(tx - this.fx, tz - this.fz) > 25) {
      this._snap = true;
      this.fx = tx; this.fy = ty; this.fz = tz; this.lx = lx; this.lz = lz;
      this.curDist = this.dist;
      this.room = roomish ? 1 : 0;
    } else {
      this.lx = damp(this.lx, lx, 2.6, dt);
      this.lz = damp(this.lz, lz, 2.6, dt);
      this.fx = damp(this.fx, tx, 12, dt);
      this.fz = damp(this.fz, tz, 12, dt);
      this.fy = damp(this.fy, ty, 5, dt); // прыжки не дёргают камеру
      this.curDist = damp(this.curDist, this.dist, 8, dt);
      this.room = damp(this.room, roomish ? 1 : 0, 4, dt);
    }
    const fx = this.fx + this.lx, fy = this.fy, fz = this.fz + this.lz;

    // желаемые наклон и дистанция
    let dist = this.curDist;
    const k = (dist - TOP.min) / (TOP.max - TOP.min);
    let pitch = TOP.pitchNear + (TOP.pitchFar - TOP.pitchNear) * smoothstep(0, 1, k);
    if (this.room > 0.01) {
      dist = Math.min(dist, dist + (TOP.roomDist - dist) * this.room);
      pitch += TOP.roomPitch * this.room;
    }

    // препятствия между фокусом и камерой: круче, потом ближе
    this._gather(fx, fy, fz, dist + 1.5);
    let wantP = pitch, wantD = dist;
    if (!this._clear(fx, fy, fz, pitch, dist, inRoom)) {
      let ok = false;
      for (let pp = pitch + 5 * D2R; pp <= TOP.pitchMax + 1e-4; pp += 5 * D2R) {
        if (this._clear(fx, fy, fz, pp, dist, inRoom)) { wantP = pp; ok = true; break; }
      }
      if (!ok) {
        wantP = TOP.pitchMax;
        wantD = 2.2;
        for (let dd = dist * 0.85; dd > 2.2; dd *= 0.85) {
          if (this._clear(fx, fy, fz, wantP, dd, inRoom)) { wantD = dd; break; }
        }
      }
    }
    if (this._snap) {
      this.pitch = wantP; this.effDist = wantD;
    } else {
      // к препятствию — быстро, обратно — плавно
      this.pitch = damp(this.pitch, wantP, wantP > this.pitch ? 10 : 2.4, dt);
      this.effDist = damp(this.effDist, wantD, wantD < this.effDist ? 12 : 2.4, dt);
    }
    this.justSnapped = this._snap;
    this._snap = false;

    const cp = Math.cos(this.pitch), sp = Math.sin(this.pitch);
    const sy = Math.sin(this.yaw), cy = Math.cos(this.yaw);
    let x = fx + sy * cp * this.effDist, y = fy + sp * this.effDist, z = fz + cy * cp * this.effDist;
    // не под землю и не под пол
    const floor = (isInterior(x) ? 0 : heightAt(x, z)) + 0.6;
    if (y < floor) y = floor;
    camera.position.set(x, y, z);
    camera.rotation.order = 'YXZ';
    camera.rotation.set(-this.pitch, this.yaw, 0);

    this.fov = this.fovFor(camera.aspect);
    return this;
  }

  // Стены и крупные объекты возле линии взгляда (один раз за кадр).
  _gather(fx, fy, fz, r) {
    const c = this._cand;
    c.length = 0;
    // CollisionWorld._query — выборка по сетке (без аллокаций)
    this.col._query(fx, fz, r, (o) => {
      if (o.type === 1) {
        if (!o.solid || o.y1 - o.y0 < 0.9 || o.y1 < fy - 0.2) return;
      } else if (o.r < 1.1 || o.y1 < fy - 0.2) return; // стволы тонкие — их прячет растворение листвы (C4)
      c.push(o);
    });
  }

  // Свободна ли линия фокус -> камера (рельеф и выбранные препятствия).
  _clear(fx, fy, fz, pitch, dist, inRoom) {
    const cp = Math.cos(pitch), sp = Math.sin(pitch);
    const dx = Math.sin(this.yaw) * cp * dist, dy = sp * dist, dz = Math.cos(this.yaw) * cp * dist;
    if (!inRoom) {
      for (let i = 2; i <= 10; i++) {
        const t = i / 10;
        const x = fx + dx * t, z = fz + dz * t;
        if (!isInterior(x) && heightAt(x, z) + 0.45 > fy + dy * t) return false;
      }
    }
    for (const o of this._cand) {
      let t0 = 0, t1 = 1;
      if (o.type === 1) {
        // прямоугольник в своей системе координат (как в CollisionWorld.resolve)
        const ox = fx - o.x, oz = fz - o.z;
        const ax = ox * o.c - oz * o.s, az = ox * o.s + oz * o.c;
        const bx = dx * o.c - dz * o.s, bz = dx * o.s + dz * o.c;
        const hx = o.hx + 0.3, hz = o.hz + 0.3;
        if (Math.abs(bx) < 1e-6) { if (Math.abs(ax) > hx) continue; } else {
          let a = (-hx - ax) / bx, b = (hx - ax) / bx;
          if (a > b) { const s = a; a = b; b = s; }
          t0 = Math.max(t0, a); t1 = Math.min(t1, b);
        }
        if (Math.abs(bz) < 1e-6) { if (Math.abs(az) > hz) continue; } else {
          let a = (-hz - az) / bz, b = (hz - az) / bz;
          if (a > b) { const s = a; a = b; b = s; }
          t0 = Math.max(t0, a); t1 = Math.min(t1, b);
        }
      } else {
        // вертикальный цилиндр (крупный валун)
        const ox = fx - o.x, oz = fz - o.z, rr = o.r + 0.3;
        const A = dx * dx + dz * dz, B = 2 * (ox * dx + oz * dz), C = ox * ox + oz * oz - rr * rr;
        const disc = B * B - 4 * A * C;
        if (A < 1e-9 || disc < 0) continue;
        const q = Math.sqrt(disc);
        t0 = Math.max(t0, (-B - q) / (2 * A)); t1 = Math.min(t1, (-B + q) / (2 * A));
      }
      if (t0 > t1) continue;
      if (t0 <= 0 && fy < o.y1) continue; // фокус внутри препятствия — не из-за чего подъезжать
      const yIn = fy + dy * t0, yOut = fy + dy * t1;
      if (yIn < o.y1 + 0.2 && yOut > o.y0) return false;
    }
    return true;
  }
}
