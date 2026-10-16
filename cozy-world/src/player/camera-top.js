// Камера «вид сверху» (C1: player.cameraMode === 'top') — на телефоне по умолчанию, на ПК по клавише C.
// Наклон ~55° (чуть положе вблизи, круче вдали), дистанция 5–20 м (щипок / колесо), поворот по yaw
// (перетаскивание по экрану, поворот двумя пальцами, ПКМ), упреждение по скорости, плавное следование.
// Камера не уходит под рельеф и не прячется за стенами: сначала поднимается круче; если стена выше камеры
// (многоэтажка, обрыв) — встаёт над персонажем выше неё и сама отворачивается к открытой стороне.
// В квартире — ближе и круче, под большой крышей (арена) — чуть круче; крыши над игроком скрывает top-view.js (C5).

import { angleDiff, clamp, damp, dampAngle, smoothstep } from '../util/math.js';
import { heightAt, isInterior } from '../world/terrain-data.js';

const D2R = Math.PI / 180;

export const TOP = {
  dist: 11, min: 5, max: 20,
  pitchNear: 50 * D2R, pitchFar: 61 * D2R, // наклон при минимальной и максимальной дистанции
  pitchMax: 88 * D2R, // у стены — почти отвесно: так видно персонажа и без выреза стены
  turn: 3.2, // скорость, с которой камера отворачивается от слишком высокой стены
  focusY: 0.85, // точка, на которую смотрим: середина персонажа
  lead: 0.34, leadMax: 1.7, // упреждение: секунды скорости и предел, м
  roomDist: 8.5, roomPitch: 10 * D2R, // в квартире: не дальше и на столько круче
  roomInset: 2.5, // в квартире фокус не ближе к стене: в кадре комната, а не пустота за ней
  hallPitch: 5 * D2R, // под большой крышей (арена): чуть круче, дистанция своя — зал большой
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
    this.room = 0; // 0..1 — насколько мы в квартире
    this.hall = 0; // 0..1 — насколько мы под большой крышей
    this.indoor = false; // под большой крышей (арена) — ставит top-view.js
    this.roomArea = null; // комната-интерьер под крышей C5 { x0, x1, z0, z1 } — ставит top-view.js
    this._pickYaw = false; // после телепорта: развернуться туда, где обзор не закрыт стеной
    this.over = false; // стена выше камеры: смотрим строго сверху, над ней
    this._top = 0; // верх самого высокого препятствия на пути взгляда (за кадр)
    this._turnTo = null; // куда отворачиваемся от такой стены
    this.autoYaw = 0; // накопленный автоповорот — player.js держит по нему направление джойстика, пока тот нажат
    this.userT = 0; // пользователь только что крутил камеру — автоповорот не вмешивается
    this.fov = 50;
    this._snap = true;
    this.justSnapped = false; // в этом кадре камера встала на место без сглаживания (вход в режим, телепорт)
    this._cand = [];
    this._seg = { fx: 0, fy: 0, fz: 0 };
  }

  zoom(f) { this.dist = clamp(this.dist * f, TOP.min, TOP.max); }

  // Следующий кадр без сглаживания (вход в режим, телепорт). pickYaw — ещё и повернуть камеру,
  // если с текущей стороны обзор закрыт (вышли из дома — камера не над его крышей, а перед фасадом).
  snap(pickYaw = false) { this._snap = true; this._pickYaw = pickYaw; }

  // Вертикальный угол обзора по пропорциям экрана: в альбомной ориентации телефона ~48°, в портретной шире.
  fovFor(aspect) {
    return clamp((2 * Math.atan(Math.tan(42 * D2R) / Math.max(aspect, 0.3))) / D2R, 48, 72);
  }

  update(camera, dt, player) {
    const p = player.pos;
    const inRoom = isInterior(p.x);
    this.userT = Math.max(0, this.userT - dt);
    // отворачиваемся от слишком высокой стены (цель выбрана в прошлом кадре), когда камера уже строго над
    // персонажем: тогда поворот — вращение на месте, камера не чиркает по стене; пользователь крутит сам — не мешаем
    if (this._turnTo !== null) {
      if (this.userT > 0 || this._snap) this._turnTo = null;
      else if (this.pitch > 87.5 * D2R) {
        const y0 = this.yaw;
        this.yaw = dampAngle(this.yaw, this._turnTo, TOP.turn, dt);
        this.autoYaw += this.yaw - y0;
        if (Math.abs(angleDiff(this.yaw, this._turnTo)) < 0.03) this._turnTo = null;
      }
    }
    // фокус: середина персонажа + упреждение по скорости
    let lx = player.vel.x * TOP.lead, lz = player.vel.z * TOP.lead;
    const ll = Math.hypot(lx, lz);
    if (ll > TOP.leadMax) { lx *= TOP.leadMax / ll; lz *= TOP.leadMax / ll; }
    const tx = p.x, ty = p.y + TOP.focusY, tz = p.z;
    if (this._snap || Math.hypot(tx - this.fx, tz - this.fz) > 25) {
      this._snap = true;
      this.fx = tx; this.fy = ty; this.fz = tz; this.lx = lx; this.lz = lz;
      this.curDist = this.dist;
      this.room = inRoom ? 1 : 0;
      this.hall = this.indoor ? 1 : 0;
    } else {
      this.lx = damp(this.lx, lx, 2.6, dt);
      this.lz = damp(this.lz, lz, 2.6, dt);
      this.fx = damp(this.fx, tx, 12, dt);
      this.fz = damp(this.fz, tz, 12, dt);
      this.fy = damp(this.fy, ty, 5, dt); // прыжки не дёргают камеру
      this.curDist = damp(this.curDist, this.dist, 8, dt);
      this.room = damp(this.room, inRoom ? 1 : 0, 4, dt);
      this.hall = damp(this.hall, this.indoor ? 1 : 0, 4, dt);
    }
    let fx = this.fx + this.lx, fz = this.fz + this.lz;
    const fy = this.fy;
    const ra = inRoom ? this.roomArea : null;
    if (ra) {
      const ix = Math.min(TOP.roomInset, (ra.x1 - ra.x0) / 2), iz = Math.min(TOP.roomInset, (ra.z1 - ra.z0) / 2);
      fx = clamp(fx, ra.x0 + ix, ra.x1 - ix);
      fz = clamp(fz, ra.z0 + iz, ra.z1 - iz);
    }

    // желаемые наклон и дистанция
    let dist = this.curDist;
    const k = (dist - TOP.min) / (TOP.max - TOP.min);
    let pitch = TOP.pitchNear + (TOP.pitchFar - TOP.pitchNear) * smoothstep(0, 1, k);
    if (this.room > 0.01) {
      dist = Math.min(dist, dist + (TOP.roomDist - dist) * this.room);
      pitch += TOP.roomPitch * this.room;
    }
    pitch += TOP.hallPitch * this.hall;
    this.fov = this.fovFor(camera.aspect);
    const vfov = this.fov * D2R;

    // препятствия: круче, вплоть до почти отвесного взгляда; если стена выше камеры и низ кадра упёрся бы в неё
    // (камера между персонажем и стеной) — строго сверху, над стеной, и отворачиваемся к открытой стороне
    this._gather(fx, fy, fz, dist + 1.5);
    if (this._snap && this._pickYaw) this._clearYaw(fx, fy, fz, pitch, dist, inRoom, vfov);
    this._pickYaw = false;
    const pad = this.over ? 0.6 : 0.3; // выйти из «строго сверху» труднее, чем войти — без дрожания на границе
    let wantP = -1, wantD = dist;
    this._top = 0;
    for (let pp = pitch; pp < TOP.pitchMax + 4 * D2R; pp += 4 * D2R) {
      const p2 = Math.min(pp, TOP.pitchMax);
      if (this._ok(fx, fy, fz, p2, dist, inRoom, pad, vfov)) { wantP = p2; break; }
    }
    this.over = wantP < 0;
    if (this.over) {
      wantP = Math.PI / 2;
      wantD = clamp(this._top - fy + 3, dist, TOP.max + 6); // крыша дома — не вплотную под камерой
      if (!inRoom && this._turnTo === null && this.userT <= 0 && !this._snap) this._turnTo = this._openYaw(fx, fy, fz, pitch, dist, inRoom, vfov);
    }
    if (this._snap) {
      this.pitch = wantP; this.effDist = wantD;
    } else {
      // к препятствию — быстро, обратно — плавно
      this.pitch = damp(this.pitch, wantP, wantP > this.pitch ? 10 : 2.4, dt);
      this.effDist = damp(this.effDist, wantD, wantD > this.effDist ? 8 : 2.4, dt);
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
    return this;
  }

  // Ракурс годится: линия до камеры свободна и низ кадра не смотрит в стену выше камеры у неё за спиной.
  _ok(fx, fy, fz, pitch, dist, inRoom, pad, vfov) {
    return this._clear(fx, fy, fz, pitch, dist, inRoom, pad) && this._back(fx, fy, fz, pitch, dist, vfov);
  }

  // Ближайший поворот камеры (шагами по 30°), с которого обзор открыт; null — такого нет.
  _openYaw(fx, fy, fz, pitch, dist, inRoom, vfov) {
    const y0 = this.yaw;
    let res = null;
    for (let i = 1; i <= 6 && res === null; i++) {
      for (const s of i === 6 ? [1] : [1, -1]) {
        this.yaw = y0 + (s * i * Math.PI) / 6;
        if (this._ok(fx, fy, fz, pitch, dist, inRoom, 0.6, vfov) || this._ok(fx, fy, fz, pitch + 12 * D2R, dist, inRoom, 0.6, vfov)) { res = this.yaw; break; }
      }
    }
    this.yaw = y0;
    return res;
  }

  // Низ кадра: при крутом наклоне камера видит и то, что у неё за спиной. Стена выше камеры там — фасад
  // в лоб снизу вверх (или вовсе изнутри здания) — не годится.
  _back(fx, fy, fz, pitch, dist, vfov) {
    const over = pitch + vfov / 2 - Math.PI / 2;
    if (over <= 0) return true;
    const cp = Math.cos(pitch), bx = Math.sin(this.yaw), bz = Math.cos(this.yaw);
    const cx = fx + bx * cp * dist, cy = fy + Math.sin(pitch) * dist, cz = fz + bz * cp * dist;
    const reach = (cy - fy + TOP.focusY) * Math.tan(over) + 0.5; // насколько далеко за спину достаёт низ кадра
    for (const o of this._cand) {
      if (o.y1 < cy - 0.3) continue;
      let vx, vz;
      if (o.type === 1) {
        const ox = cx - o.x, oz = cz - o.z;
        const ax = ox * o.c - oz * o.s, az = ox * o.s + oz * o.c;
        vx = clamp(ax, -o.hx, o.hx) - ax; vz = clamp(az, -o.hz, o.hz) - az;
        const lbx = bx * o.c - bz * o.s, lbz = bx * o.s + bz * o.c; // направление «за спину» в осях прямоугольника
        const d = Math.hypot(vx, vz);
        if (d < reach && vx * lbx + vz * lbz > -0.2 * d) { this._top = Math.max(this._top, o.y1); return false; }
      } else {
        vx = o.x - cx; vz = o.z - cz;
        const l = Math.hypot(vx, vz), d = Math.max(0, l - o.r);
        if (d < reach && vx * bx + vz * bz > -0.2 * l) { this._top = Math.max(this._top, o.y1); return false; }
      }
    }
    return true;
  }

  // Поворот без закрытого обзора: как есть, назад (лицом к двери, из которой вышли), вбок, наискосок.
  _clearYaw(fx, fy, fz, pitch, dist, inRoom, vfov) {
    const y0 = this.yaw;
    for (const k of [0, 4, 2, -2, 1, -1, 3, -3]) {
      this.yaw = y0 + (k * Math.PI) / 4;
      if (this._ok(fx, fy, fz, pitch, dist, inRoom, 0.3, vfov)) return;
    }
    this.yaw = y0;
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
  _clear(fx, fy, fz, pitch, dist, inRoom, pad) {
    const cp = Math.cos(pitch), sp = Math.sin(pitch);
    const dx = Math.sin(this.yaw) * cp * dist, dy = sp * dist, dz = Math.cos(this.yaw) * cp * dist;
    if (!inRoom) {
      for (let i = 2; i <= 10; i++) {
        const t = i / 10;
        const x = fx + dx * t, z = fz + dz * t;
        if (!isInterior(x) && heightAt(x, z) + 0.45 > fy + dy * t) { this._top = Math.max(this._top, heightAt(x, z) + 0.45); return false; }
      }
    }
    for (const o of this._cand) {
      if (!this._span(o, fx, fz, dx, dz, pad)) continue;
      if (this._t0 <= 0) {
        // фокус в запасе вокруг препятствия: рядом со стеной считаем по самой стене (без запаса),
        // а внутри неё (стоим на столе, на настиле) — препятствия нет
        if (!this._span(o, fx, fz, dx, dz, 0)) continue;
        if (this._t0 <= 0 && fy < o.y1) continue;
      }
      const yIn = fy + dy * this._t0, yOut = fy + dy * this._t1;
      if (yIn < o.y1 + 0.2 && yOut > o.y0) { this._top = Math.max(this._top, o.y1); return false; }
    }
    return true;
  }

  // Отрезок фокус -> камера (по горизонтали) против препятствия с запасом pad: доли отрезка входа и выхода
  // в this._t0 / this._t1; false — не пересекает.
  _span(o, fx, fz, dx, dz, pad) {
    let t0 = 0, t1 = 1;
    if (o.type === 1) {
      // прямоугольник в своей системе координат (как в CollisionWorld.resolve)
      const ox = fx - o.x, oz = fz - o.z;
      const ax = ox * o.c - oz * o.s, az = ox * o.s + oz * o.c;
      const bx = dx * o.c - dz * o.s, bz = dx * o.s + dz * o.c;
      const hx = o.hx + pad, hz = o.hz + pad;
      if (Math.abs(bx) < 1e-6) { if (Math.abs(ax) > hx) return false; } else {
        let a = (-hx - ax) / bx, b = (hx - ax) / bx;
        if (a > b) { const s = a; a = b; b = s; }
        t0 = Math.max(t0, a); t1 = Math.min(t1, b);
      }
      if (Math.abs(bz) < 1e-6) { if (Math.abs(az) > hz) return false; } else {
        let a = (-hz - az) / bz, b = (hz - az) / bz;
        if (a > b) { const s = a; a = b; b = s; }
        t0 = Math.max(t0, a); t1 = Math.min(t1, b);
      }
    } else {
      // вертикальный цилиндр (крупный валун)
      const ox = fx - o.x, oz = fz - o.z, rr = o.r + pad;
      const A = dx * dx + dz * dz, C = ox * ox + oz * oz - rr * rr;
      if (A < 1e-9) { if (C > 0) return false; } else {
        const B = 2 * (ox * dx + oz * dz), disc = B * B - 4 * A * C;
        if (disc < 0) return false;
        const q = Math.sqrt(disc);
        t0 = Math.max(t0, (-B - q) / (2 * A)); t1 = Math.min(t1, (-B + q) / (2 * A));
      }
    }
    this._t0 = t0; this._t1 = t1;
    return t0 <= t1;
  }
}
