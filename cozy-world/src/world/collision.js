// Статические коллизии: вертикальные круги (стволы, камни, столбы) и повёрнутые прямоугольники
// (скамейки, столы, стены). Хранятся в сетке по 8 м. Игрок и предметы выталкиваются в плоскости XZ,
// а верх прямоугольников работает как опора (можно встать на стол).

import { heightAt } from './terrain-data.js';

const CELL = 8;
const cellKey = (ix, iz) => (ix + 512) * 1024 + (iz + 512);

export class CollisionWorld {
  constructor() {
    this.grid = new Map();
    this.nextId = 1;
    this.stamp = 1;
    this.boxes = [];
  }

  _insert(o, x0, z0, x1, z1) {
    const ix0 = Math.floor(x0 / CELL), ix1 = Math.floor(x1 / CELL);
    const iz0 = Math.floor(z0 / CELL), iz1 = Math.floor(z1 / CELL);
    for (let iz = iz0; iz <= iz1; iz++) {
      for (let ix = ix0; ix <= ix1; ix++) {
        const k = cellKey(ix, iz);
        let arr = this.grid.get(k);
        if (!arr) { arr = []; this.grid.set(k, arr); }
        arr.push(o);
      }
    }
  }

  addCircle(x, z, r, y0 = -1e4, y1 = 1e4) {
    const o = { type: 0, x, z, r, y0, y1, id: this.nextId++, mark: 0 };
    this._insert(o, x - r, z - r, x + r, z + r);
    return o;
  }

  // rot - поворот вокруг Y как у Object3D.rotation.y; y1 - верх (на него можно встать).
  addBox(x, z, hx, hz, rot, y0, y1, solid = true) {
    const c = Math.cos(rot), s = Math.sin(rot);
    const o = { type: 1, x, z, hx, hz, c, s, y0, y1, solid, id: this.nextId++, mark: 0 };
    const ex = Math.abs(c) * hx + Math.abs(s) * hz, ez = Math.abs(s) * hx + Math.abs(c) * hz;
    this._insert(o, x - ex, z - ez, x + ex, z + ez);
    this.boxes.push(o);
    return o;
  }

  _query(x, z, r, fn) {
    const stamp = ++this.stamp;
    const ix0 = Math.floor((x - r) / CELL), ix1 = Math.floor((x + r) / CELL);
    const iz0 = Math.floor((z - r) / CELL), iz1 = Math.floor((z + r) / CELL);
    for (let iz = iz0; iz <= iz1; iz++) {
      for (let ix = ix0; ix <= ix1; ix++) {
        const arr = this.grid.get(cellKey(ix, iz));
        if (!arr) continue;
        for (const o of arr) {
          if (o.mark === stamp) continue;
          o.mark = stamp;
          fn(o);
        }
      }
    }
  }

  // Выталкивает окружность pos(x,z) радиуса r из препятствий. Объект занимает по высоте [yFeet, yFeet+height].
  // Возвращает true, если было касание.
  resolve(pos, r, yFeet, height) {
    let hit = false;
    for (let pass = 0; pass < 3; pass++) {
      let moved = false;
      this._query(pos.x, pos.z, r + 0.5, (o) => {
        if (yFeet >= o.y1 - 0.12 || yFeet + height <= o.y0) return;
        if (o.type === 1 && !o.solid) return;
        if (o.type === 0) {
          const dx = pos.x - o.x, dz = pos.z - o.z;
          const rr = r + o.r;
          const d2 = dx * dx + dz * dz;
          if (d2 >= rr * rr) return;
          const d = Math.sqrt(d2);
          if (d < 1e-6) { pos.x += rr; } else { const k = (rr - d) / d; pos.x += dx * k; pos.z += dz * k; }
          moved = true;
        } else {
          const dx = pos.x - o.x, dz = pos.z - o.z;
          const lx = dx * o.c - dz * o.s, lz = dx * o.s + dz * o.c;
          const cx = Math.max(-o.hx, Math.min(o.hx, lx)), cz = Math.max(-o.hz, Math.min(o.hz, lz));
          const ox = lx - cx, oz = lz - cz;
          const d2 = ox * ox + oz * oz;
          if (d2 >= r * r) return;
          let px, pz;
          if (d2 > 1e-8) {
            const d = Math.sqrt(d2), k = (r - d) / d;
            px = ox * k; pz = oz * k;
          } else {
            const penX = o.hx - Math.abs(lx), penZ = o.hz - Math.abs(lz);
            if (penX < penZ) { px = (lx >= 0 ? 1 : -1) * (penX + r); pz = 0; }
            else { pz = (lz >= 0 ? 1 : -1) * (penZ + r); px = 0; }
          }
          pos.x += px * o.c + pz * o.s;
          pos.z += -px * o.s + pz * o.c;
          moved = true;
        }
      });
      if (!moved) break;
      hit = true;
    }
    return hit;
  }

  // Высота опоры под точкой: рельеф или верх ящика/стола, если он не выше yFeet + stepUp.
  supportY(x, z, yFeet, stepUp = 0.45, pad = 0.12) {
    let best = heightAt(x, z);
    this._query(x, z, 1, (o) => {
      if (o.type !== 1) return;
      const dx = x - o.x, dz = z - o.z;
      const lx = dx * o.c - dz * o.s, lz = dx * o.s + dz * o.c;
      if (Math.abs(lx) > o.hx + pad || Math.abs(lz) > o.hz + pad) return;
      if (o.y1 <= yFeet + stepUp && o.y1 > best) best = o.y1;
    });
    return best;
  }
}
