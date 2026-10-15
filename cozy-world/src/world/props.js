// Предметы, которые можно брать и бросать (мячи, ящики, кружки, дубинки, шахматы), и порталы-двери.
//
// Сеть: у предмета в каждый момент либо нет хозяина (лежит), либо хозяин - один из игроков.
// Хозяин считает физику у себя и шлёт состояние; остальные плавно догоняют. Когда предмет
// успокоился, хозяин присылает 'rest' и предмет снова становится "ничьим".

import * as THREE from 'three';
import { PROPS, KINDS } from '../../shared/props.js';
import { BOARD } from '../../shared/layout.js';
import { heightAt } from './terrain-data.js';
import { mergeAll, solid, colorize } from './geo.js';
import { clamp } from '../util/math.js';

const G = 16;
const REACH = 3.0;
const NAMES = { ball: 'мяч', crate: 'ящик', mug: 'кружку', club: 'дубинку', chess: 'фигуру' };

// ---------- геометрия ----------

function tr(geo, x, y, z, rx = 0, ry = 0, rz = 0) {
  const m = new THREE.Matrix4().compose(new THREE.Vector3(x, y, z), new THREE.Quaternion().setFromEuler(new THREE.Euler(rx, ry, rz)), new THREE.Vector3(1, 1, 1));
  geo.applyMatrix4(m);
  return geo;
}

const LATHE = {
  P: [[0, 0], [0.036, 0], [0.036, 0.008], [0.022, 0.02], [0.018, 0.042], [0.03, 0.05], [0.018, 0.056], [0.026, 0.07], [0.024, 0.082], [0.012, 0.092], [0, 0.094]],
  R: [[0, 0], [0.042, 0], [0.042, 0.012], [0.028, 0.026], [0.028, 0.07], [0.042, 0.076], [0.042, 0.1], [0.03, 0.1], [0.03, 0.094], [0, 0.094]],
  B: [[0, 0], [0.04, 0], [0.04, 0.01], [0.024, 0.024], [0.02, 0.06], [0.034, 0.075], [0.028, 0.105], [0.014, 0.125], [0.016, 0.135], [0, 0.14]],
  Q: [[0, 0], [0.044, 0], [0.044, 0.012], [0.026, 0.028], [0.022, 0.07], [0.04, 0.095], [0.046, 0.12], [0.03, 0.128], [0.018, 0.14], [0.026, 0.15], [0, 0.158]],
  K: [[0, 0], [0.046, 0], [0.046, 0.012], [0.028, 0.03], [0.024, 0.076], [0.04, 0.1], [0.034, 0.122], [0.02, 0.13], [0.02, 0.14], [0, 0.145]],
  N: [[0, 0], [0.04, 0], [0.04, 0.01], [0.026, 0.025], [0.022, 0.05], [0, 0.056]],
};

function chessGeometry(piece, hex, r) {
  const lathe = new THREE.LatheGeometry((LATHE[piece] || LATHE.P).map(([x, y]) => new THREE.Vector2(x, y)), 14);
  const parts = [solid(lathe, hex)];
  if (piece === 'K') {
    parts.push(solid(tr(new THREE.BoxGeometry(0.012, 0.04, 0.012), 0, 0.165, 0), hex), solid(tr(new THREE.BoxGeometry(0.034, 0.012, 0.012), 0, 0.172, 0), hex));
  }
  if (piece === 'N') {
    parts.push(solid(tr(new THREE.BoxGeometry(0.032, 0.07, 0.05), 0, 0.085, -0.01, -0.25, 0, 0), hex), solid(tr(new THREE.BoxGeometry(0.03, 0.03, 0.04), 0, 0.11, -0.045, 0.25, 0, 0), hex));
  }
  const g = mergeAll(parts);
  g.translate(0, -r, 0);
  return g;
}

function ballGeometry(hex) {
  const g = new THREE.SphereGeometry(0.32, 20, 14);
  const c = new THREE.Color(hex), w = new THREE.Color('#ffffff');
  return colorize(g, (x, y, z, out) => {
    const sector = Math.floor(((Math.atan2(z, x) + Math.PI) / (Math.PI * 2)) * 6);
    out.copy(sector % 2 ? w : c);
  });
}

function crateGeometry() {
  const parts = [solid(new THREE.BoxGeometry(0.62, 0.62, 0.62), '#c99a5b')];
  const frame = '#8a5a30';
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) parts.push(solid(tr(new THREE.BoxGeometry(0.08, 0.64, 0.08), sx * 0.29, 0, sz * 0.29), frame));
  for (const sy of [-1, 1]) {
    for (const s of [-1, 1]) {
      parts.push(solid(tr(new THREE.BoxGeometry(0.64, 0.08, 0.08), 0, sy * 0.29, s * 0.29), frame));
      parts.push(solid(tr(new THREE.BoxGeometry(0.08, 0.08, 0.64), s * 0.29, sy * 0.29, 0), frame));
    }
  }
  parts.push(solid(tr(new THREE.BoxGeometry(0.58, 0.07, 0.04), 0, 0, 0.31, 0, 0, 0.7), frame), solid(tr(new THREE.BoxGeometry(0.58, 0.07, 0.04), 0, 0, 0.31, 0, 0, -0.7), frame));
  return mergeAll(parts);
}

function mugGeometry(hex) {
  const parts = [
    solid(new THREE.CylinderGeometry(0.08, 0.07, 0.15, 14), hex),
    solid(tr(new THREE.TorusGeometry(0.045, 0.014, 6, 12, Math.PI), 0.085, 0, 0, 0, 0, -Math.PI / 2), hex),
    solid(tr(new THREE.CylinderGeometry(0.068, 0.068, 0.01, 14), 0, 0.072, 0), '#3a2418'),
  ];
  return mergeAll(parts);
}

function clubGeometry(hex) {
  const g = mergeAll([
    solid(tr(new THREE.CylinderGeometry(0.075, 0.05, 0.62, 12), 0.1, 0, 0, 0, 0, Math.PI / 2), hex),
    solid(tr(new THREE.CylinderGeometry(0.035, 0.035, 0.3, 10), -0.36, 0, 0, 0, 0, Math.PI / 2), '#2a2a32'),
    solid(tr(new THREE.SphereGeometry(0.06, 10, 8), 0.43, 0, 0), hex),
  ]);
  return g;
}

function buildMesh(def) {
  const r = KINDS[def.kind].r;
  let geo;
  switch (def.kind) {
    case 'ball': geo = ballGeometry(def.color); break;
    case 'crate': geo = crateGeometry(); break;
    case 'mug': geo = mugGeometry(def.color); geo.translate(0, 0.075 - r, 0); break;
    case 'club': geo = clubGeometry(def.color); break;
    default: geo = chessGeometry(def.piece, def.side === 'w' ? '#f1e6cf' : '#3a2f2c', r);
  }
  const mesh = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ vertexColors: true, roughness: def.kind === 'ball' ? 0.45 : 0.8, metalness: 0 }));
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  return mesh;
}

// ---------- система предметов ----------

const _up = new THREE.Vector3(0, 1, 0);
const _fwd = new THREE.Vector3();
const _v = new THREE.Vector3();
const _q = new THREE.Quaternion();
const _e = new THREE.Euler(0, 0, 0, 'YXZ');

export class Props {
  constructor(game) {
    this.game = game;
    this.world = game.world;
    this.group = new THREE.Group();
    this.group.name = 'props';
    this.world.scene.add(this.group);
    this.list = [];
    this.byId = new Map();
    this.holding = null;
    this.target = null;
    this.portal = null;
    this._sendAt = 0;

    for (const def of PROPS) {
      const k = KINDS[def.kind];
      const mesh = buildMesh(def);
      this.group.add(mesh);
      const y = def.y !== undefined ? def.y + k.r : heightAt(def.x, def.z) + k.r;
      const p = {
        id: def.id, def, k, mesh,
        pos: new THREE.Vector3(def.x, y, def.z),
        quat: new THREE.Quaternion(),
        vel: new THREE.Vector3(),
        ang: new THREE.Vector3(),
        owner: null, // null | 'me' | <id другого игрока>
        held: false,
        grounded: false,
        restT: 0,
        goal: { pos: new THREE.Vector3(def.x, y, def.z), quat: new THREE.Quaternion() },
      };
      if (def.kind === 'club') _q.setFromEuler(new THREE.Euler(0, (def.x * 7.3) % 3, 0));
      p.quat.copy(def.kind === 'club' ? _q : new THREE.Quaternion());
      mesh.position.copy(p.pos);
      mesh.quaternion.copy(p.quat);
      this.list.push(p);
      this.byId.set(def.id, p);
    }

    game.onUse = () => this.use();
    game.onDrop = (power) => this.release(power);
    game.onWelcome = (w) => this.applyWelcome(w);
    game.onOwner = (m) => this.onOwner(m);
    game.onDeny = (m) => this.onDeny(m);
    game.onPropSnap = (list) => this.onSnap(list);
  }

  get net() { return this.game.net && this.game.online ? this.game.net : null; }

  // ---------- выбор цели ----------

  _ray() {
    const g = this.game, p = g.player;
    const cp = Math.cos(p.pitch);
    _fwd.set(-Math.sin(p.yaw) * cp, Math.sin(p.pitch), -Math.cos(p.yaw) * cp);
    const origin = p.thirdPerson ? _v.set(p.pos.x, p.pos.y + 0.95, p.pos.z) : g.camera.position;
    return { origin, dir: _fwd };
  }

  pick() {
    const { origin, dir } = this._ray();
    let best = null, bestT = REACH;
    for (const p of this.list) {
      if (p.held || (p.owner && p.owner !== 'me')) continue;
      const dx = p.pos.x - origin.x, dy = p.pos.y - origin.y, dz = p.pos.z - origin.z;
      const t = dx * dir.x + dy * dir.y + dz * dir.z;
      if (t < 0.15 || t > bestT) continue;
      const px = dx - dir.x * t, py = dy - dir.y * t, pz = dz - dir.z * t;
      const d = Math.hypot(px, py, pz);
      if (d < p.k.r + 0.22) { best = p; bestT = t; }
    }
    return best;
  }

  nearestPortal() {
    const pl = this.game.player.pos;
    let best = null, bd = 1e9;
    for (const po of this.world.structures.portals) {
      const d = Math.hypot(po.x - pl.x, po.z - pl.z);
      if (d < po.r && Math.abs(po.y - pl.y) < 3 && d < bd) { best = po; bd = d; }
    }
    return best;
  }

  // ---------- действия игрока ----------

  use() {
    if (this.holding) { this.release(0); return; }
    const t = this.pick();
    if (t) { this.grab(t); return; }
    const portal = this.nearestPortal();
    if (portal) this.game.teleport(portal.to);
  }

  grab(p) {
    if (this.holding) return;
    this.holding = p;
    this.game.holding = p;
    p.prev = { owner: p.owner, held: p.held }; // на случай отказа сервера
    p.owner = 'me';
    p.held = true;
    p.vel.set(0, 0, 0);
    p.restT = 0;
    this.game.audio.pickup();
    this.net?.send({ t: 'grab', id: p.id });
  }

  // power: 0 - аккуратно поставить, 0.25..1 - бросок
  release(power) {
    const p = this.holding;
    if (!p) return;
    const g = this.game, pl = g.player;
    const { dir } = this._ray();
    this.holding = null;
    g.holding = null;
    p.held = false;
    p.owner = 'me';
    p.restT = 0;
    if (power > 0) {
      const speed = 4 + power * 10;
      p.vel.copy(dir).multiplyScalar(speed).addScaledVector(pl.vel, 0.6);
      p.vel.y += 1.4 + power * 1.2;
      p.ang.set((Math.random() - 0.5) * 12, (Math.random() - 0.5) * 6, (Math.random() - 0.5) * 12);
      g.audio.throwSound();
    } else {
      p.vel.copy(pl.vel).multiplyScalar(0.5).addScaledVector(dir, 0.8);
      p.vel.y = Math.max(0, p.vel.y);
      g.audio.put();
    }
    this.net?.send({ t: 'rel', id: p.id });
  }

  // ---------- физика ----------

  _updateHeld(p, dt) {
    const g = this.game, pl = g.player;
    const cp = Math.cos(pl.pitch);
    _fwd.set(-Math.sin(pl.yaw) * cp, Math.sin(pl.pitch), -Math.cos(pl.yaw) * cp);
    const target = _v;
    if (!pl.thirdPerson) {
      // чем крупнее предмет, тем дальше и ниже его держим, чтобы он не закрывал весь экран
      target.copy(g.camera.position).addScaledVector(_fwd, 0.75 + p.k.r * 1.6);
      target.y -= 0.22 + p.k.r * 0.9;
    } else {
      target.set(pl.pos.x - Math.sin(pl.yaw) * 0.85, pl.pos.y + 0.72, pl.pos.z - Math.cos(pl.yaw) * 0.85);
    }
    p.pos.lerp(target, 1 - Math.exp(-22 * dt));
    p.vel.set(0, 0, 0);
    _q.setFromEuler(_e.set(0, pl.yaw, 0));
    p.quat.slerp(_q, 1 - Math.exp(-12 * dt));
  }

  _simulate(p, dt) {
    const col = this.world.collisions;
    const r = p.k.r;
    p.vel.y -= G * dt;
    p.pos.addScaledVector(p.vel, dt);

    // стены и стволы
    const before = _v.set(p.pos.x, 0, p.pos.z);
    const xz = { x: p.pos.x, z: p.pos.z };
    col.resolve(xz, r, p.pos.y - r, r * 2);
    if (xz.x !== before.x || xz.z !== before.z) {
      const nx = xz.x - before.x, nz = xz.z - before.z;
      const l = Math.hypot(nx, nz) || 1;
      const vn = p.vel.x * (nx / l) + p.vel.z * (nz / l);
      if (vn < 0) {
        const k = (1 + p.k.bounce) * vn;
        p.vel.x -= k * (nx / l);
        p.vel.z -= k * (nz / l);
      }
      p.pos.x = xz.x;
      p.pos.z = xz.z;
    }

    // земля / столы
    const gy = col.supportY(p.pos.x, p.pos.z, p.pos.y - r, 0.35, 0.02) + r;
    p.grounded = false;
    if (p.pos.y <= gy) {
      p.pos.y = gy;
      if (p.vel.y < -1.2) p.vel.y = -p.vel.y * p.k.bounce;
      else p.vel.y = 0;
      const f = Math.exp(-(p.k.shape === 'sphere' ? 1.2 : 7) * dt);
      p.vel.x *= f;
      p.vel.z *= f;
      p.ang.multiplyScalar(Math.exp(-6 * dt));
      p.grounded = p.vel.y < 0.4;
    }

    // вращение: мяч катится, ящик падает кувырком и выравнивается
    if (p.k.shape === 'sphere' && p.grounded) {
      const sp = Math.hypot(p.vel.x, p.vel.z);
      if (sp > 0.01) {
        _v.set(p.vel.z, 0, -p.vel.x).normalize();
        _q.setFromAxisAngle(_v, (sp * dt) / r);
        p.quat.premultiply(_q);
      }
    } else if (p.ang.lengthSq() > 1e-4) {
      _v.copy(p.ang);
      const a = _v.length() * dt;
      _q.setFromAxisAngle(_v.normalize(), a);
      p.quat.premultiply(_q);
    }
    if (p.k.shape === 'box' && p.grounded) {
      _e.setFromQuaternion(p.quat, 'YXZ');
      _q.setFromEuler(_e.set(0, _e.y, 0));
      p.quat.slerp(_q, 1 - Math.exp(-14 * dt));
    }

    // покой => ничьё
    const speed = p.vel.length();
    if (p.grounded && speed < 0.25 && p.ang.lengthSq() < 0.5) p.restT += dt; else p.restT = 0;
    if (p.restT > 0.5) this._rest(p);
    if (p.pos.y < -30) { p.pos.y = heightAt(p.pos.x, p.pos.z) + r; p.vel.set(0, 0, 0); }
  }

  _rest(p) {
    if (p.def.kind === 'chess') this._snapToBoard(p);
    p.owner = null;
    p.vel.set(0, 0, 0);
    p.ang.set(0, 0, 0);
    this.net?.send({ t: 'rest', id: p.id, p: [p.pos.x, p.pos.y, p.pos.z], q: [p.quat.x, p.quat.y, p.quat.z, p.quat.w] });
  }

  // Фигура, поставленная на доску, встаёт ровно в центр клетки.
  _snapToBoard(p) {
    const c = BOARD.cell;
    const fx = (p.pos.x - BOARD.x) / c + 3.5, fz = (p.pos.z - BOARD.z) / c + 3.5;
    if (fx < -0.4 || fx > 7.4 || fz < -0.4 || fz > 7.4) return;
    if (Math.abs(p.pos.y - p.k.r - BOARD.y) > 0.15) return;
    const ix = clamp(Math.round(fx), 0, 7), iz = clamp(Math.round(fz), 0, 7);
    p.pos.set(BOARD.x + (ix - 3.5) * c, BOARD.y + p.k.r, BOARD.z + (iz - 3.5) * c);
    _e.setFromQuaternion(p.quat, 'YXZ');
    p.quat.setFromEuler(_e.set(0, Math.round(_e.y / (Math.PI / 2)) * (Math.PI / 2), 0));
  }

  _follow(p, dt) {
    const k = 1 - Math.exp(-14 * dt);
    p.pos.lerp(p.goal.pos, k);
    p.quat.slerp(p.goal.quat, k);
  }

  update(dt) {
    for (const p of this.list) {
      if (p === this.holding) this._updateHeld(p, dt);
      else if (p.owner === 'me') this._simulate(p, dt);
      else if (p.owner) this._follow(p, dt);
      p.mesh.position.copy(p.pos);
      p.mesh.quaternion.copy(p.quat);
    }

    // отправка состояния своих предметов
    const now = performance.now();
    if (this.net && now - this._sendAt > 66) {
      this._sendAt = now;
      const l = [];
      for (const p of this.list) if (p.owner === 'me') l.push([p.id, p.pos.x, p.pos.y, p.pos.z, p.quat.x, p.quat.y, p.quat.z, p.quat.w]);
      if (l.length) this.net.send({ t: 'pp', l });
    }
    this._hints();
  }

  _hints() {
    const g = this.game, ui = g.ui, touch = g.isTouch;
    const E = touch ? '✋' : 'E', Q = touch ? '↗' : 'Q';
    const hints = [];
    this.target = this.holding ? null : this.pick();
    this.portal = !this.target && !this.holding ? this.nearestPortal() : null;
    if (this.holding) hints.push({ key: E, text: 'Поставить' }, { key: Q, text: 'Бросить (держи — сильнее)' });
    else if (this.target) hints.push({ key: E, text: `Взять ${NAMES[this.target.def.kind]}` });
    else if (this.portal) hints.push({ key: E, text: this.portal.label });
    ui.setHints(hints);
    ui.setCrosshairHot(!!this.target || !!this.portal);
  }

  // ---------- сеть ----------

  applyWelcome(w) {
    for (const p of this.list) { if (p.owner !== 'me') p.owner = null; }
    for (const s of w.props || []) {
      const p = this.byId.get(s.id);
      if (!p || !s.p) continue;
      p.pos.set(...s.p);
      p.goal.pos.set(...s.p);
      if (s.q) { p.quat.set(...s.q); p.goal.quat.set(...s.q); }
      p.owner = s.o ? (s.o === this.net?.id ? 'me' : s.o) : null;
      p.held = !!s.h;
    }
  }

  onOwner(m) {
    const p = this.byId.get(m.id);
    if (!p) return;
    const me = this.net?.id;
    if (m.o === me) { // сервер подтвердил моё владение (или передал мне доигрывание предмета)
      if (p.owner !== 'me') { p.owner = 'me'; p.held = !!m.h; p.restT = 0; }
      return;
    }
    if (this.holding === p) { // у меня забрали
      this.holding = null;
      this.game.holding = null;
    }
    if (m.o) {
      p.owner = m.o;
      p.held = !!m.h;
    } else {
      p.owner = null;
      p.held = false;
      if (m.p) { p.goal.pos.set(...m.p); p.pos.copy(p.goal.pos); }
      if (m.q) { p.goal.quat.set(...m.q); p.quat.copy(p.goal.quat); }
    }
  }

  onDeny(m) {
    const p = this.byId.get(m.id);
    if (!p || this.holding !== p) return;
    this.holding = null;
    this.game.holding = null;
    p.owner = p.prev?.owner && p.prev.owner !== 'me' ? p.prev.owner : '?'; // настоящий хозяин нам уже известен
    p.held = !!p.prev?.held;
    this.game.ui.toast('Это уже держит кто-то другой');
  }

  onSnap(list) {
    for (const e of list) {
      const p = this.byId.get(e[0]);
      if (!p || p.owner === 'me') continue;
      p.goal.pos.set(e[1], e[2], e[3]);
      p.goal.quat.set(e[4], e[5], e[6], e[7]).normalize();
      if (!p.owner) p.owner = '?'; // кадры идут - значит у предмета есть хозяин
    }
  }
}
