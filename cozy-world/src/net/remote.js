// Другие игроки: аватары, буфер снапшотов и интерполяция с небольшой задержкой (гладко при ~15 Гц).

import { Avatar, lookFromArray, holdFromCode } from '../player/avatar.js';
import { angleDiff, clamp } from '../util/math.js';

const DELAY = 0.12; // секунд: рисуем немного "в прошлом", чтобы всегда было между чем интерполировать

export class RemotePlayers {
  constructor(scene, ground = null) {
    this.scene = scene;
    this.ground = ground; // WS1: высота опоры для постановки стоп
    this.map = new Map();
  }

  get size() { return this.map.size; }
  get(id) { return this.map.get(id); }

  add(info) {
    if (this.map.has(info.id)) this.remove(info.id);
    const avatar = new Avatar(lookFromArray(info.look), { name: info.name });
    avatar.setGround(this.ground);
    avatar.root.position.set(...(info.p || [0, -100, 0]));
    avatar.root.rotation.y = info.r || 0;
    this.scene.add(avatar.root);
    const r = { id: info.id, name: info.name, avatar, buf: [], lastY: info.p ? info.p[1] : 0, flags: info.a || 0 };
    if (info.p) r.buf.push({ t: performance.now() / 1000, x: info.p[0], y: info.p[1], z: info.p[2], r: info.r || 0, q: info.q || 0, sp: 0, a: info.a || 0, hk: info.hk || 0 });
    this.map.set(info.id, r);
    return r;
  }

  remove(id) {
    const r = this.map.get(id);
    if (!r) return;
    this.scene.remove(r.avatar.root);
    r.avatar.dispose();
    this.map.delete(id);
  }

  clear() { for (const id of [...this.map.keys()]) this.remove(id); }

  // e: [id, x, y, z, yaw, pitch, speed, flags, holdKind]
  push(e) {
    const r = this.map.get(e[0]);
    if (!r) return;
    r.buf.push({ t: performance.now() / 1000, x: e[1], y: e[2], z: e[3], r: e[4], q: e[5], sp: e[6], a: e[7], hk: e[8] | 0 });
    if (r.buf.length > 24) r.buf.shift();
  }

  setLook(id, look) {
    const r = this.map.get(id);
    if (r) r.avatar.setLook(lookFromArray(look));
  }

  list() {
    return [...this.map.values()].map((r) => ({ id: r.id, name: r.name, speaking: r.avatar.speaking }));
  }

  update(dt, camera) {
    const rt = performance.now() / 1000 - DELAY;
    for (const r of this.map.values()) {
      const b = r.buf;
      if (!b.length) continue;
      let i = b.length - 1;
      while (i > 0 && b[i].t > rt) i--;
      const a = b[i], c = b[Math.min(i + 1, b.length - 1)];
      let x, y, z, yaw, pitch;
      const span = c.t - a.t;
      const near = Math.hypot(c.x - a.x, c.z - a.z) < 8; // телепорт (дверь) не интерполируем
      if (a === c || span <= 1e-4 || !near) {
        x = c.x; y = c.y; z = c.z; yaw = c.r; pitch = c.q;
      } else {
        const k = clamp((rt - a.t) / span, 0, 1);
        x = a.x + (c.x - a.x) * k;
        y = a.y + (c.y - a.y) * k;
        z = a.z + (c.z - a.z) * k;
        yaw = a.r + angleDiff(a.r, c.r) * k;
        pitch = a.q + (c.q - a.q) * k;
      }
      while (b.length > 3 && b[1].t < rt) b.shift();

      const av = r.avatar;
      av.root.position.set(x, y, z);
      av.root.rotation.y = yaw;
      const flags = c.a;
      const vy = dt > 0 ? (y - r.lastY) / dt : 0;
      r.lastY = y;
      // WS1: вид удержания из снапшота (без него — «двумя руками» по флагу), речь — по биту «говорит»
      const holdKind = holdFromCode(c.hk) || (flags & 4 ? 'two' : null);
      av.setSpeaking(!!(flags & 2));
      av.update(dt, { speed: c.sp, grounded: !!(flags & 1), vy: clamp(vy, -10, 10), pitch, holding: !!(flags & 4), holdKind, talk: flags & 2 ? 0.6 : 0 }, camera);
      r.flags = flags;
    }
  }
}
