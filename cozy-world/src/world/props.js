// Предметы: бита, зонт, фонарь, гитара, бумбокс, фрисби, посуда, книги, мишка, мячи, ящики, шахматы — и порталы-двери.
//
// Рендер: все предметы и подставки мира — экземпляры одного BatchedMesh (1 draw call + тень), с LOD по
// расстоянию. Предмет в чьих-то руках рисуется отдельным мешем, прикреплённым к якорю аватара (C3), а свой
// предмет от первого лица — в сцене рук (не протыкает стены, пальцы обхватывают рукоять).
//
// Сеть: у предмета в каждый момент либо нет хозяина (лежит), либо хозяин - один из игроков.
// Хозяин считает физику у себя и шлёт состояние; остальные плавно догоняют. Когда предмет
// успокоился, хозяин присылает 'rest' и предмет снова становится "ничьим". Состояние предмета
// (зонт раскрыт, фонарь горит, музыка играет) — 'pst', действия в руках — 'use', удар битой — 'kick'/'hit'.

import * as THREE from 'three';
import { PROPS, KINDS, STANDS } from '../../shared/props.js';
import { BOARD } from '../../shared/layout.js';
import { heightAt } from './terrain-data.js';
import * as PG from './prop-geo.js';
import { clamp, damp } from '../util/math.js';

const G = 16;
const REACH = 3.0;
// Дальности по качеству: lod — дальше упрощённые модели; shadow / shadowSmall — дальше предметы (крупные / мелкие)
// не отбрасывают тень; hide — дальше мелочь (чашки, книги, фигуры) не рисуется совсем.
const RANGES = {
  high: { lod: 12, shadow: 30, shadowSmall: 14, hide: 1e9 },
  medium: { lod: 5, shadow: 11, shadowSmall: 6, hide: 45 },
  low: { lod: 4, shadow: 0, shadowSmall: 0, hide: 26 },
};
const SMALL = new Set(['cup', 'mug', 'teapot', 'book', 'chess', 'frisbee']);
const SWING_HIT_AT = 0.27; // с начала замаха до удара, с
const THROW_AT = 0.28; // с начала броска одной рукой до вылета предмета
const NAMES = {
  ball: 'мяч', crate: 'ящик', mug: 'кружку', cup: 'чашку', teapot: 'чайник', bat: 'биту', umbrella: 'зонт', lantern: 'фонарь',
  guitar: 'гитару', boombox: 'бумбокс', frisbee: 'фрисби', book: 'книгу', teddy: 'мишку', chess: 'фигуру',
};
const CHORDS = 4; // гитара перебирает C–G–Am–F

// Как предмет сидит в руке: смещение центра модели и поворот в системе якоря; s — масштаб.
// tp — якорь аватара (3-е лицо и чужие игроки), fp — якорь рук от первого лица (своя сцена с камерой рук).
// Поворот задаётся направлениями осей Y и Z модели в системе якоря (оси якорей сняты с поз WS1).
const V3 = (a) => new THREE.Vector3(...a);
function BQ(y, z) {
  const Y = V3(y).normalize(), Z = V3(z);
  Z.addScaledVector(Y, -Z.dot(Y)).normalize();
  return new THREE.Quaternion().setFromRotationMatrix(new THREE.Matrix4().makeBasis(new THREE.Vector3().crossVectors(Y, Z), Y, Z));
}
const H = (p, q = [0, 1, 0], z = [0, 0, 1], s = 1) => ({ p: V3(p), q: BQ(q, z), s });
const ID = [0, 1, 0];
const HOLD = {
  bat: { tp: H([0, 0.3, 0]), fp: H([0, 0.3, 0]) },
  // зонт в кулаке перед грудью, купол над головой; от первого лица (уменьшен) видна ручка и край купола сверху
  umbrella: { tp: H([0.076, 0.531, 0.054], [0.14, 0.985, 0.1], [0, 0, 1], 1.15), fp: H([-0.068, 0.288, -0.038], [-0.227, 0.968, -0.128], [0, 0, 1], 0.62) },
  // от первого лица фонарь держим снизу за бак (иначе он висит ниже кадра)
  lantern: { tp: H([0, -0.205, 0], ID, [0.5, 0, 0.85]), fp: H([-0.01, 0.085, -0.03], ID, [0.45, 0, 0.9], 0.68) },
  mug: { tp: H([-0.085, 0, 0]), fp: H([-0.105, 0, 0.01]) },
  cup: { tp: H([-0.07, 0, 0]), fp: H([-0.085, 0, 0.01]) },
  teapot: { tp: H([-0.148, 0, 0], ID, [0, 0, -1]), fp: H([-0.13, 0, 0.01], ID, [0, 0, -1], 0.85) },
  book: { tp: H([-0.073, 0.05, 0.018], [0.24, 0.06, 0.97], [-0.01, 1, -0.06]), fp: H([-0.078, 0.048, 0.012], [0.44, 0.1, 0.89], [-0.04, 1, -0.09]) },
  frisbee: { tp: H([-0.08, 0, -0.1]), fp: H([-0.133, -0.03, -0.042], [0.15, 0.94, 0.29]) },
  chess: { tp: H([0, 0.05, 0]), fp: H([0, 0.05, 0]) },
  crate: { tp: H([0, 0.04, -0.16]), fp: H([0, -0.06, -0.3], ID, [0.3, 0, 1], 0.62) },
  ball: { tp: H([0, 0.04, -0.15]), fp: H([0, -0.04, -0.3], ID, [0, 0, 1], 0.62) },
  teddy: { tp: H([0, 0.0, -0.1], ID, [0, 0, -1]), fp: H([0, -0.03, -0.16], [0, 1, 0.12], [0, -0.12, 1], 0.9) },
  boombox: { tp: H([0, 0.02, -0.1], ID, [0, 0, -1]), fp: H([0, -0.02, -0.2], [0, 0.97, 0.25], [0, -0.25, 0.97], 0.8) },
  // гитара поперёк живота: гриф влево-вверх; от первого лица дека повёрнута к камере
  guitar: { tp: H([0.08, -0.09, -0.04], [-0.9, 0.4, -0.15], [0.05, 0.15, -1]), fp: H([0.1, -0.11, -0.07], [-0.9, 0.42, -0.1], [0.1, 0.65, 0.75], 0.75) },
};

// ---------- геометрии ----------

function geoKey(def, state) {
  switch (def.kind) {
    case 'bat': return `bat.${def.v || 'wood'}`;
    case 'umbrella': return `umb.${def.v || 'red'}.${state ? 1 : 0}`;
    case 'lantern': return `lantern.${state ? 1 : 0}`;
    case 'boombox': return `boombox.${state ? 1 : 0}`;
    case 'chess': return `chess.${def.piece}${def.side}`;
    default: return def.v ? `${def.kind}.${def.v}` : def.kind;
  }
}

function makeGeo(key, d) {
  const [k, a, b] = key.split('.');
  switch (k) {
    case 'bat': return PG.batGeo(a, d);
    case 'umb': return PG.umbrellaGeo(a, +b, d);
    case 'lantern': return PG.lanternGeo(a === '1', d);
    case 'boombox': return PG.boomboxGeo(a === '1', d);
    case 'chess': return PG.chessGeo(a[0], a[1], d);
    case 'guitar': return PG.guitarGeo(d);
    case 'frisbee': return PG.frisbeeGeo(a, d);
    case 'teapot': return PG.teapotGeo(d);
    case 'cup': return PG.cupGeo(d);
    case 'mug': return PG.mugGeo(a, d);
    case 'book': return PG.bookGeo(a, d);
    case 'teddy': return PG.teddyGeo(d);
    case 'ball': return PG.ballGeo(a, d);
    case 'crate': return PG.crateGeo(d);
    case 'stand': {
      switch (a) {
        case 'batRack': return PG.batRackGeo(d);
        case 'umbrellaStand': return PG.umbrellaStandGeo(d);
        case 'stump': return PG.stumpGeo(d);
        case 'campTable': return PG.campTableGeo(d);
        default: { const g = PG.crateGeo(d, '#b98a55', 3); g.translate(0, 0.3, 0); return g; }
      }
    }
    default: throw new Error(`нет геометрии ${key}`);
  }
}

// Опорные точки предмета: крайние вершины по 26 направлениям — по ним ищется самая нижняя точка при любом повороте.
const DIRS = [];
for (let x = -1; x <= 1; x++) for (let y = -1; y <= 1; y++) for (let z = -1; z <= 1; z++) if (x || y || z) DIRS.push(new THREE.Vector3(x, y, z).normalize());
function hullOf(geo) {
  const P = geo.attributes.position;
  const best = DIRS.map(() => [-1e9, 0]);
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i);
    for (let k = 0; k < DIRS.length; k++) {
      const v = x * DIRS[k].x + y * DIRS[k].y + z * DIRS[k].z;
      if (v > best[k][0]) { best[k][0] = v; best[k][1] = i; }
    }
  }
  const idx = [...new Set(best.map((b) => b[1]))];
  const out = new Float32Array(idx.length * 3);
  idx.forEach((i, k) => { out[k * 3] = P.getX(i); out[k * 3 + 1] = P.getY(i); out[k * 3 + 2] = P.getZ(i); });
  return out;
}
// Глубина самой нижней точки под центром при повороте q.
function support(hull, q) {
  const { x, y, z, w } = q;
  const m10 = 2 * (x * y + w * z), m11 = 1 - 2 * (x * x + z * z), m12 = 2 * (y * z - w * x);
  let lo = 1e9;
  for (let i = 0; i < hull.length; i += 3) {
    const v = m10 * hull[i] + m11 * hull[i + 1] + m12 * hull[i + 2];
    if (v < lo) lo = v;
  }
  return -lo;
}

function glowTexture() {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d');
  const gr = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  gr.addColorStop(0, 'rgba(255,240,200,1)');
  gr.addColorStop(0.25, 'rgba(255,200,110,0.55)');
  gr.addColorStop(1, 'rgba(255,160,60,0)');
  g.fillStyle = gr;
  g.fillRect(0, 0, 64, 64);
  // нижняя половина гаснет быстрее: ореол не обрезается землёй/столом жёсткой линией
  const img = g.getImageData(0, 0, 64, 64), d = img.data;
  for (let y = 33; y < 64; y++) {
    const k = Math.max(0, 1 - ((y - 32) / 22) ** 1.5);
    for (let x = 0; x < 64; x++) d[(y * 64 + x) * 4 + 3] *= k;
  }
  g.putImageData(img, 0, 0);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

// ---------- система предметов ----------

const _up = new THREE.Vector3(0, 1, 0);
const _fwd = new THREE.Vector3();
const _v = new THREE.Vector3();
const _v2 = new THREE.Vector3();
const _q = new THREE.Quaternion();
const _q2 = new THREE.Quaternion();
const _e = new THREE.Euler(0, 0, 0, 'YXZ');
const _m = new THREE.Matrix4();
const _m2 = new THREE.Matrix4();
const _s = new THREE.Vector3();
const _one = new THREE.Vector3(1, 1, 1);

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
    this.pending = null; // бросок одной рукой: предмет вылетает на середине замаха
    this.swingT = -1; // таймер до удара битой
    this.actT = 0; // пауза между действиями
    this.chord = 0;
    this.shake = 0;
    this.lastHit = null;

    this.mat = PG.propMaterial();
    this.soloMat = PG.propMaterial();
    this.fpMat = PG.propMaterial();
    this.shadowMat = new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: false });
    this.glowTex = glowTexture();
    this.ranges = RANGES[game.qualityName] || RANGES.high;
    this.lodDist = this.ranges.lod;
    this._buildBatch();

    for (const st of STANDS) this._addStand(st); // сначала подставки: предметы ставятся на их коллизии
    for (const def of PROPS) this._addProp(def);
    for (const p of this.list) this._sync(p, true);

    game.onUse = () => this.use();
    game.onDrop = (power) => this.release(power);
    game.onWelcome = (w) => this.applyWelcome(w);
    game.onOwner = (m) => this.onOwner(m);
    game.onDeny = (m) => this.onDeny(m);
    game.onPropSnap = (list) => this.onSnap(list);
    game.onPropUse = (m) => this.onRemoteUse(m);
    game.onPropState = (m) => this.onRemoteState(m);
    game.onHit = (m) => this.onHit(m);
    game.holdKind = null;
  }

  get net() { return this.game.net && this.game.online ? this.game.net : null; }

  // ---------- сборка ----------

  _buildBatch() {
    const keys = new Set();
    for (const def of PROPS) {
      keys.add(geoKey(def, def.s || 0));
      if (KINDS[def.kind].state) { keys.add(geoKey(def, 0)); keys.add(geoKey(def, 1)); }
    }
    for (const st of STANDS) if (st.type !== 'swingSeat') keys.add(`stand.${st.type}`);
    this.geos = new Map();
    let verts = 0, index = 0;
    for (const key of keys) {
      const hi = makeGeo(key, 1);
      const lo = makeGeo(key, 0);
      const e = { hi: { geo: hi, id: -1 }, lo: { geo: lo, id: -1 }, hull: hullOf(hi) };
      this.geos.set(key, e);
      for (const g of hi === lo ? [hi] : [hi, lo]) { verts += g.attributes.position.count; index += g.index.count; }
    }
    const count = PROPS.length + STANDS.length + 4;
    const b = this.batch = new THREE.BatchedMesh(count, verts, index, this.mat);
    b.name = 'props-batch';
    b.castShadow = true;
    b.receiveShadow = true;
    b.frustumCulled = false; // предметы разлетаются по миру; каждый экземпляр отсекается сам
    // тень отбрасывают только предметы рядом с камерой: дальние тени в карте теней — пара текселей
    const base = THREE.BatchedMesh.prototype.onBeforeShadow;
    const hidden = [];
    b.onBeforeShadow = (renderer, object, camera, ...rest) => {
      const c = camera.position, R = this.ranges;
      hidden.length = 0;
      for (const p of this.list) {
        if (!p.vis) continue;
        const lim = SMALL.has(p.kind) ? R.shadowSmall : R.shadow;
        if (p.pos.distanceToSquared(c) > lim * lim) { b.setVisibleAt(p.inst, false); hidden.push(p.inst); }
      }
      base.call(b, renderer, object, camera, ...rest);
      for (const id of hidden) b.setVisibleAt(id, true);
    };
    for (const e of this.geos.values()) {
      e.hi.id = b.addGeometry(e.hi.geo);
      e.lo.id = e.lo.geo === e.hi.geo ? e.hi.id : b.addGeometry(e.lo.geo);
    }
    this.group.add(b);
  }

  _addProp(def) {
    const k = KINDS[def.kind];
    const state = def.s || 0;
    const p = {
      id: def.id, def, kind: def.kind, k, state, openK: state,
      pos: new THREE.Vector3(), quat: new THREE.Quaternion(), vel: new THREE.Vector3(), ang: new THREE.Vector3(),
      owner: null, held: false, grounded: false, restT: 0,
      goal: { pos: new THREE.Vector3(), quat: new THREE.Quaternion() },
      key: geoKey(def, state), keySt: state, gid: -1, lod: 0, vis: true, solo: null, scale: 1, dirty: true,
    };
    p.hull = this.geos.get(p.key).hull;
    if (def.rot) p.quat.setFromEuler(_e.set(def.rot[0], def.rot[1], def.rot[2], 'YXZ'));
    const sup = support(p.hull, p.quat);
    let y;
    if (def.y !== undefined) y = def.y + sup;
    else if (def.dy !== undefined) y = heightAt(def.x, def.z) + def.dy + sup;
    else y = this.world.collisions.supportY(def.x, def.z, heightAt(def.x, def.z) + 2, 0.6, 0.02) + sup;
    p.pos.set(def.x, y, def.z);
    p.goal.pos.copy(p.pos);
    p.goal.quat.copy(p.quat);
    p.inst = this.batch.addInstance(this.geos.get(p.key).hi.id);
    p.gid = this.geos.get(p.key).hi.id;
    this.list.push(p);
    this.byId.set(def.id, p);
  }

  // Подставки: статичные экземпляры в том же BatchedMesh + коллизии; трава под ними не растёт.
  _addStand(st) {
    const col = this.world.collisions;
    const y0 = heightAt(st.x, st.z), ry = st.ry || 0;
    if (st.type === 'swingSeat') {
      // доска качелей висит на высоте 1 м над корнем дерева (см. structures.js: heroTree)
      const top = heightAt(-17, -21) + 1.04;
      col.addBox(st.x, st.z, 0.5, 0.2, 0, top - 0.08, top, false);
      return;
    }
    const e = this.geos.get(`stand.${st.type}`);
    const id = this.batch.addInstance(e.hi.id);
    this.batch.setMatrixAt(id, _m.compose(_v.set(st.x, y0, st.z), _q.setFromAxisAngle(_up, ry), _one));
    (this.stands || (this.stands = [])).push({ st, id, e, lod: 0, x: st.x, y: y0, z: st.z });
    switch (st.type) {
      case 'batRack':
        col.addBox(st.x, st.z, 0.5, 0.17, ry, y0 - 0.3, y0 + 0.14);
        col.addBox(st.x + Math.sin(ry) * -0.08, st.z + Math.cos(ry) * -0.08, 0.5, 0.05, ry, y0 + 0.14, y0 + 0.8);
        break;
      case 'umbrellaStand': col.addCircle(st.x, st.z, 0.2, y0 - 0.3, y0 + 0.5); break;
      case 'stump': col.addBox(st.x, st.z, 0.31, 0.31, 0, y0 - 0.3, y0 + 0.42); break;
      case 'campTable': col.addBox(st.x, st.z, 0.37, 0.23, ry, y0 - 0.3, y0 + 0.575); break;
      default: col.addBox(st.x, st.z, 0.3, 0.3, ry, y0 - 0.3, y0 + 0.6);
    }
    this.world.excludeNature?.(st.x, st.z, st.type === 'batRack' ? 0.9 : 0.6);
  }

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
      // длинные предметы — отрезок вдоль оси Y модели (бита, зонт), остальные — шар
      const long = p.kind === 'bat' || p.kind === 'umbrella';
      const n = long ? 3 : 1;
      for (let i = 0; i < n; i++) {
        const off = long ? (i - 1) * 0.3 : 0;
        _v2.set(0, off, 0).applyQuaternion(p.quat).add(p.pos);
        const dx = _v2.x - origin.x, dy = _v2.y - origin.y, dz = _v2.z - origin.z;
        const t = dx * dir.x + dy * dir.y + dz * dir.z;
        if (t < 0.15 || t > bestT) continue;
        const d = Math.hypot(dx - dir.x * t, dy - dir.y * t, dz - dir.z * t);
        if (d < (long ? 0.1 : p.k.r) + 0.2) { best = p; bestT = t; }
      }
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

  // E / ЛКМ / ✋: в руках предмет с действием — действие, иначе положить; руки пусты — взять или войти в дверь.
  use() {
    if (this.pending) return;
    const h = this.holding;
    if (h) {
      if (h.k.act) this.action(h);
      else this.release(0);
      return;
    }
    const t = this.pick();
    if (t) { this.grab(t); return; }
    const portal = this.nearestPortal();
    if (portal) this.game.teleport(portal.to);
  }

  _setHolding(p) {
    this.holding = p;
    this.game.holding = p;
    this.game.holdKind = p ? p.k.hold : null; // C2
  }

  grab(p) {
    if (this.holding) return;
    this._setHolding(p);
    p.prev = { owner: p.owner, held: p.held }; // на случай отказа сервера
    p.owner = 'me';
    p.held = true;
    p.vel.set(0, 0, 0);
    p.ang.set(0, 0, 0);
    p.restT = 0;
    this.game.audio.pickup();
    if (p.kind === 'teddy') this.game.audio.squeak?.(p.pos);
    this.net?.send({ t: 'grab', id: p.id });
  }

  // Действие предметом в руках (C2b): замах битой, зонт/фонарь/бумбокс — вкл/выкл, гитара — аккорд.
  action(p) {
    if (this.actT > 0) return;
    const g = this.game, a = p.k.act;
    const msg = { t: 'use', id: p.id, a };
    if (a === 'swing') {
      this.actT = 0.58;
      this.swingT = SWING_HIT_AT;
      g.audio.swing?.(p.pos);
    } else if (a === 'toggle') {
      this.actT = 0.4;
      this.setState(p, p.state ? 0 : 1, true);
    } else if (a === 'strum') {
      this.actT = 0.45;
      this.chord = (this.chord + 1) % CHORDS;
      msg.c = this.chord;
      g.audio.strum?.(this.chord, p.pos);
    }
    g.avatar.act?.(a);
    g.hands.act?.(a);
    this.net?.send(msg);
  }

  // Состояние предмета: зонт раскрыт, фонарь горит, бумбокс играет. local — меняем сами (шлём всем);
  // quiet — без щелчков (состояние комнаты при входе).
  setState(p, s, local, quiet = false) {
    s = s ? 1 : 0;
    if (p.state === s) return;
    p.state = s;
    if (local) this.net?.send({ t: 'pst', id: p.id, s });
    const a = this.game.audio;
    if (!quiet) {
      if (p.kind === 'umbrella') a.umbrella?.(p.pos, !!s);
      else a.click?.(p.pos);
    }
    if (p.kind === 'boombox') a.music?.(p.id, !!s, p.pos);
    p.dirty = true;
  }

  // power: 0 - аккуратно поставить, 0.25..1 - бросок
  release(power) {
    const p = this.holding;
    if (!p || this.pending) return;
    if (power > 0 && p.k.hold !== 'two') {
      // одной рукой: замах, предмет вылетает на середине движения
      this.pending = { p, power, t: THROW_AT };
      this.game.avatar.act?.('throw');
      this.game.hands.act?.('throw');
      this.net?.send({ t: 'use', id: p.id, a: 'throw' });
      return;
    }
    this._launch(p, power);
  }

  _launch(p, power) {
    const g = this.game, pl = g.player;
    const { dir } = this._ray();
    // от первого лица мелкий предмет вылетает оттуда, где его видно в руке; крупный (двумя руками) —
    // от груди чуть вперёд: в сцене рук он уменьшен, в полный размер перед камерой он бы её закрыл
    if (this._fp() && this.fpProp === p && this.fpMesh?.parent) {
      if (p.k.hold !== 'two') this._fpWorldPose(p);
      else p.pos.addScaledVector(_v.set(-Math.sin(pl.yaw), 0, -Math.cos(pl.yaw)), 0.25);
    }
    this._setHolding(null);
    p.held = false;
    p.owner = 'me';
    p.restT = 0;
    // не оставляем предмет в стене
    const xz = { x: p.pos.x, z: p.pos.z };
    this.world.collisions.resolve(xz, Math.min(p.k.r, 0.25), p.pos.y - 0.2, 0.4);
    p.pos.x = xz.x; p.pos.z = xz.z;
    if (power > 0) {
      const m = Math.sqrt(p.k.mass || 1);
      if (p.k.glide) {
        // фрисби: плоско, быстро и с сильным вращением
        const hx = -Math.sin(pl.yaw), hz = -Math.cos(pl.yaw);
        const speed = 8 + power * 9;
        p.vel.set(hx * speed, 1.2 + Math.max(0, pl.pitch) * 6, hz * speed).addScaledVector(pl.vel, 0.5);
        _e.set(0.06, pl.yaw, 0.12, 'YXZ');
        p.quat.setFromEuler(_e);
        p.ang.set(0, 22 + power * 10, 0);
      } else {
        const speed = (4 + power * 10) * clamp(1.15 - 0.2 * m, 0.7, 1.1); // тяжёлое летит ближе
        p.vel.copy(dir).multiplyScalar(speed).addScaledVector(pl.vel, 0.6);
        p.vel.y += 1.4 + power * 1.2;
        p.ang.set((Math.random() - 0.5) * 12, (Math.random() - 0.5) * 6, (Math.random() - 0.5) * 12);
        if (p.kind === 'bat') p.ang.set((Math.random() - 0.5) * 4, 0, 14 + Math.random() * 6).applyQuaternion(_q.setFromAxisAngle(_up, pl.yaw));
      }
      g.audio.throwSound();
    } else {
      p.vel.copy(pl.vel).multiplyScalar(0.5).addScaledVector(_v.set(dir.x, 0, dir.z).normalize(), 0.8);
      p.vel.y = Math.max(0, p.vel.y);
      p.ang.set(0, 0, 0);
      g.audio.put();
    }
    p.dirty = true;
    this.net?.send({ t: 'rel', id: p.id });
  }

  // ---------- удар битой ----------

  _impact() {
    const g = this.game, pl = g.player;
    const fx = -Math.sin(pl.yaw), fz = -Math.cos(pl.yaw);
    const ox = pl.pos.x, oy = pl.pos.y + 0.7, oz = pl.pos.z;
    const cosHalf = Math.cos((35 * Math.PI) / 180);
    const inCone = (x, y, z, r) => {
      const dx = x - ox, dz = z - oz, dy = y - oy;
      const dh = Math.hypot(dx, dz);
      if (dh > 1.8 + r || dy < -1.1 || dy > 1.2) return false;
      return dh < 0.3 || (dx * fx + dz * fz) / dh >= cosHalf;
    };
    let hits = 0;
    for (const p of this.list) {
      if (p.held || p === this.holding) continue;
      if (p.owner && p.owner !== 'me') continue; // предмет в чужой симуляции не трогаем
      if (!inCone(p.pos.x, p.pos.y, p.pos.z, p.k.r)) continue;
      this._kick(p, fx, fz);
      hits++;
    }
    for (const r of g.remotes.map.values()) {
      const ap = r.avatar.root.position;
      if (!inCone(ap.x, ap.y + 0.6, ap.z, 0.4)) continue;
      // отдача игроку: через сервер, он сам добавит себе скорость (себя мы не бьём — только чужие аватары)
      if (this.net) this.net.send({ t: 'hit', to: r.id, v: [+(fx * 7.5).toFixed(2), 4.6, +(fz * 7.5).toFixed(2)] });
      g.audio.bonk?.(_v.set(ap.x, ap.y + 0.9, ap.z), 1);
      hits++;
    }
    if (hits) this.shake = Math.max(this.shake, 0.12);
    return hits;
  }

  _kick(p, fx, fz) {
    const m = Math.sqrt(p.k.mass || 1);
    const s = 8 / m;
    p.vel.set(fx * s, 2.5 + 2 / m, fz * s);
    p.ang.set((Math.random() - 0.5) * 14, (Math.random() - 0.5) * 8, (Math.random() - 0.5) * 14);
    if (p.owner !== 'me') {
      p.prev = { owner: p.owner, held: false };
      p.owner = 'me';
      p.kicked = true;
      this.net?.send({ t: 'kick', id: p.id });
    }
    p.restT = 0;
    p.dirty = true;
    this.game.audio.thwack?.(p.pos, p.kind);
  }

  // Удар битой от другого игрока: отдача, тряска камеры, звук.
  onHit(m) {
    const pl = this.game.player;
    if (!Array.isArray(m.v) || m.v.length !== 3) return;
    const v = m.v.map((x) => clamp(Number(x) || 0, -12, 12));
    pl.vel.x += v[0];
    pl.vel.y = Math.max(pl.vel.y, v[1]);
    pl.vel.z += v[2];
    pl.onGround = false;
    this.shake = 0.45;
    this.lastHit = { from: m.from, v, t: performance.now() };
    this.game.audio.bonk?.(_v.set(pl.pos.x, pl.pos.y + 1, pl.pos.z), 1.2);
  }

  // ---------- физика ----------

  _restMode(p) { return p.kind === 'umbrella' && p.state ? 'tilt' : p.k.rest; }

  // Устойчивое положение, ближайшее к текущему повороту (out = цель).
  _restTarget(p, out) {
    const q = p.quat;
    switch (this._restMode(p)) {
      case 'up':
        _e.setFromQuaternion(q, 'YXZ');
        return out.setFromEuler(_e.set(0, _e.y, 0, 'YXZ'));
      case 'side': case 'tilt': {
        const a = _v.set(0, 1, 0).applyQuaternion(q);
        _v2.set(a.x, 0, a.z);
        if (_v2.lengthSq() < 1e-6) _v2.set(1, 0, 0).applyQuaternion(q).setY(0);
        _v2.normalize();
        if (p.kind === 'umbrella' && p.state) { const t = 0.73; _v2.multiplyScalar(Math.cos(t)).addScaledVector(_up, Math.sin(t)); }
        return out.setFromUnitVectors(a, _v2).multiply(q);
      }
      case 'flat': case 'flatZ': {
        const a = _v.set(0, p.k.rest === 'flatZ' ? 0 : 1, p.k.rest === 'flatZ' ? 1 : 0).applyQuaternion(q);
        _v2.set(0, a.y >= 0 || p.kind === 'frisbee' ? 1 : -1, 0);
        return out.setFromUnitVectors(a, _v2).multiply(q);
      }
      default: return out.copy(q);
    }
  }

  _simulate(p, dt) {
    const col = this.world.collisions;
    const k = p.k;
    const sup0 = support(p.hull, p.quat);
    const gy0 = col.supportY(p.pos.x, p.pos.z, p.pos.y - sup0, 0.35, 0.02);
    const airborne = p.pos.y - sup0 > gy0 + 0.02;
    if (k.glide && airborne) {
      // фрисби: подъёмная сила растёт со скоростью, плоский диск сопротивляется вертикальному движению
      const sp = Math.hypot(p.vel.x, p.vel.z);
      const lift = Math.min(1, sp / 12);
      p.vel.y -= G * (1 - 0.82 * lift * lift) * dt;
      p.vel.y *= Math.exp(-1.4 * dt);
      const f = Math.exp(-0.16 * dt);
      p.vel.x *= f; p.vel.z *= f;
    } else {
      p.vel.y -= G * dt;
      if (k.drag) p.vel.multiplyScalar(Math.exp(-k.drag * dt));
    }
    p.pos.addScaledVector(p.vel, dt);

    // стены и стволы
    const sup = support(p.hull, p.quat);
    const bx = p.pos.x, bz = p.pos.z;
    const xz = { x: bx, z: bz };
    const cr = Math.min(k.r, 0.3);
    col.resolve(xz, cr, p.pos.y - sup, Math.max(0.1, sup * 2));
    if (xz.x !== bx || xz.z !== bz) {
      const nx = xz.x - bx, nz = xz.z - bz;
      const l = Math.hypot(nx, nz) || 1;
      const vn = p.vel.x * (nx / l) + p.vel.z * (nz / l);
      if (vn < 0) {
        const kk = (1 + k.bounce) * vn;
        p.vel.x -= kk * (nx / l);
        p.vel.z -= kk * (nz / l);
      }
      p.pos.x = xz.x;
      p.pos.z = xz.z;
    }

    // земля / столы: касается самая нижняя точка предмета
    const gy = col.supportY(p.pos.x, p.pos.z, p.pos.y - sup, 0.35, 0.02);
    p.grounded = false;
    if (p.pos.y - sup <= gy) {
      p.pos.y = gy + sup;
      const impact = -p.vel.y;
      if (impact > 1.6) {
        p.vel.y = impact * k.bounce;
        if (impact > 3) this.game.audio.thud?.(p.pos, Math.min(1, impact / 9), p.kind);
      } else p.vel.y = Math.max(0, p.vel.y);
      const f = Math.exp(-(k.rest === 'free' ? 2.2 : k.glide ? 5 : 7) * dt);
      p.vel.x *= f;
      p.vel.z *= f;
      p.ang.multiplyScalar(Math.exp(-(k.rest === 'free' ? 2 : 7) * dt));
      p.grounded = p.vel.y < 0.5;
    }

    // вращение: мяч катится; остальные кувыркаются в полёте, а на земле ложатся в устойчивое положение
    if (k.rest === 'free' && p.grounded) {
      const sp = Math.hypot(p.vel.x, p.vel.z);
      if (sp > 0.01) {
        _v.set(p.vel.z, 0, -p.vel.x).normalize();
        _q.setFromAxisAngle(_v, (sp * dt) / k.r);
        p.quat.premultiply(_q);
      }
    } else if (p.ang.lengthSq() > 1e-4) {
      _v.copy(p.ang);
      const a = _v.length() * dt;
      _q.setFromAxisAngle(_v.normalize(), a);
      p.quat.premultiply(_q);
      if (k.glide && airborne) {
        // гироскоп: вращающийся диск держит горизонт
        this._restTarget(p, _q2);
        p.quat.slerp(_q2, 1 - Math.exp(-2.5 * dt));
      }
    }
    if (p.grounded && k.rest !== 'free') {
      this._restTarget(p, _q2);
      p.quat.slerp(_q2, 1 - Math.exp(-11 * dt));
      // после поворота самая нижняя точка снова на земле
      p.pos.y = Math.max(p.pos.y, gy + support(p.hull, p.quat));
    }
    p.quat.normalize();
    p.dirty = true;

    // покой => ничьё
    const speed = p.vel.length();
    if (p.grounded && speed < 0.25 && p.ang.lengthSq() < 0.6) p.restT += dt; else p.restT = 0;
    if (p.restT > 0.45) this._rest(p);
    if (p.pos.y < -30) { p.pos.y = heightAt(p.pos.x, p.pos.z) + 1; p.vel.set(0, 0, 0); }
  }

  _rest(p) {
    // точно в устойчивое положение и на опору: в покое предмет не дрожит
    if (p.k.rest !== 'free') p.quat.copy(this._restTarget(p, _q2));
    const sup = support(p.hull, p.quat);
    p.pos.y = this.world.collisions.supportY(p.pos.x, p.pos.z, p.pos.y - sup, 0.35, 0.02) + sup;
    if (p.kind === 'chess') this._snapToBoard(p);
    p.owner = null;
    p.kicked = false;
    p.vel.set(0, 0, 0);
    p.ang.set(0, 0, 0);
    p.dirty = true;
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
    p.quat.setFromEuler(_e.set(0, Math.round(_e.y / (Math.PI / 2)) * (Math.PI / 2), 0, 'YXZ'));
  }

  _follow(p, dt) {
    const k = 1 - Math.exp(-14 * dt);
    p.pos.lerp(p.goal.pos, k);
    p.quat.slerp(p.goal.quat, k);
    p.dirty = true;
  }

  // Шаг до обновления аватаров: физика своих предметов, догоняние чужих, таймеры действий, отправка в сеть.
  update(dt) {
    this.actT = Math.max(0, this.actT - dt);
    if (this.swingT >= 0) {
      this.swingT -= dt;
      if (this.swingT < 0 && this.holding?.kind === 'bat') this._impact();
    }
    if (this.pending) {
      this.pending.t -= dt;
      if (this.pending.t <= 0) {
        const { p, power } = this.pending;
        this.pending = null;
        if (this.holding === p) this._launch(p, power);
      }
    }
    for (const p of this.list) {
      if (p.held) continue; // поза в руках — в lateUpdate, после аватаров
      if (p.owner === 'me') this._simulate(p, dt);
      else if (p.owner) this._follow(p, dt);
    }

    // отправка состояния своих предметов
    const now = performance.now();
    if (this.net && now - this._sendAt > 66) {
      this._sendAt = now;
      const l = [];
      for (const p of this.list) if (p.owner === 'me') l.push([p.id, p.pos.x, p.pos.y, p.pos.z, p.quat.x, p.quat.y, p.quat.z, p.quat.w]);
      if (l.length) this.net.send({ t: 'pp', l });
    }
  }

  // ---------- в руках ----------

  _fp() {
    const pl = this.game.player;
    if (pl.cameraMode) return pl.cameraMode === 'fp' && !pl.thirdPerson; // C1
    return !pl.thirdPerson && !pl.wantThird;
  }

  // Мировая поза по якорю: якорь × смещение предмета.
  _poseFrom(anchorMatrix, hold, p) {
    _m2.compose(hold.p, hold.q, _one);
    _m.multiplyMatrices(anchorMatrix, _m2).decompose(p.pos, p.quat, _s);
  }

  // Поза предмета у своих рук от первого лица, в мире (через камеру рук) — для броска.
  _fpWorldPose(p) {
    const g = this.game, hold = HOLD[p.kind].fp;
    g.hands.anchorWorld(p.k.hold, g.camera, _m);
    _m2.compose(hold.p, hold.q, _one);
    _m.multiply(_m2).decompose(p.pos, p.quat, _s);
  }

  _attach(p, avatar) {
    const hold = HOLD[p.kind].tp;
    p.holdScale = hold.s;
    if (avatar?.anchor) {
      const a = avatar.anchor(p.k.hold);
      a.updateWorldMatrix(true, false);
      this._poseFrom(a.matrixWorld, hold, p);
    } else {
      // запасной вариант: перед грудью
      const r = avatar?.root;
      if (!r) return false;
      p.pos.set(r.position.x - Math.sin(r.rotation.y) * 0.4, r.position.y + 0.72, r.position.z - Math.cos(r.rotation.y) * 0.4);
      p.quat.setFromAxisAngle(_up, r.rotation.y);
    }
    p.dirty = true;
    return true;
  }

  // После обновления аватаров и рук: предметы в руках, анимации состояния, батч, звук, подсказки.
  lateUpdate(dt) {
    const g = this.game, fp = this._fp();
    for (const p of this.list) {
      if (p.held) {
        if (p === this.holding) this._attach(p, g.avatar);
        else if (p.owner && p.owner !== 'me') {
          const r = g.remotes.get(p.owner);
          if (!r || !this._attach(p, r.avatar)) this._follow(p, dt);
        }
      }
      if (p.kind === 'umbrella' && p.openK !== p.state) {
        p.openK = p.state ? Math.min(1, p.openK + dt / 0.32) : Math.max(0, p.openK - dt / 0.26);
        p.dirty = true;
      }
      if (p.kind === 'boombox' && p.state) {
        const b = g.audio.beat?.() || 0; // лёгкий «подскок» в такт музыке
        p.scale = 1 + b * 0.025;
        p.dirty = true;
      } else p.scale = 1;
      this._sync(p);
    }
    this._syncStands();
    this._fpUpdate(fp);
    this._glows(fp);
    // звук: музыка из бумбокса следует за ним; дождь стучит по раскрытому зонту в руках
    for (const p of this.list) if (p.kind === 'boombox' && p.state) g.audio.musicPos?.(p.id, p.pos);
    const h = this.holding;
    g.audio.umbrellaRain?.(h && h.kind === 'umbrella' && h.state ? g.world.atmosphere.rain : 0);
    // тряска камеры после удара
    if (this.shake > 0.001) {
      const s = this.shake, t = g.time * 47;
      g.camera.rotation.z += Math.sin(t) * 0.03 * s;
      g.camera.rotation.x += Math.sin(t * 1.3 + 1) * 0.02 * s;
      g.camera.position.y += Math.sin(t * 0.9 + 2) * 0.03 * s;
      this.shake = Math.max(0, this.shake - dt * 1.6);
    }
    this._hints();
  }

  // Экземпляр в батче: геометрия по состоянию и расстоянию, видимость, матрица.
  _sync(p, force = false) {
    const cam = this.game.camera;
    const st = p.kind === 'umbrella' ? (p.openK > 0.5 ? 1 : 0) : p.state;
    if (st !== p.keySt) {
      p.keySt = st;
      p.key = geoKey(p.def, st);
      p.hull = this.geos.get(p.key).hull;
    }
    const entry = this.geos.get(p.key);
    const d2 = cam.position.distanceToSquared(p.pos);
    const lod = d2 > (this.lodDist + (p.lod ? -1 : 1)) ** 2 ? 1 : 0;
    p.lod = lod;
    const gid = lod ? entry.lo.id : entry.hi.id;
    if (gid !== p.gid) { this.batch.setGeometryIdAt(p.inst, gid); p.gid = gid; }
    // в руках предмет рисует отдельный меш (у зонта ещё и анимация раскрытия); мелочь вдали не рисуем
    const solo = p.held;
    const show = !solo && !(SMALL.has(p.kind) && d2 > this.ranges.hide ** 2);
    if (show !== p.vis || force) { this.batch.setVisibleAt(p.inst, show); p.vis = show; }
    if (solo) this._solo(p);
    else if (p.solo) p.solo.visible = false;
    if (p.dirty || force) {
      this.batch.setMatrixAt(p.inst, _m.compose(p.pos, p.quat, _s.setScalar(p.scale)));
      p.dirty = false;
    }
  }

  _syncStands() {
    const cam = this.game.camera;
    for (const s of this.stands || []) {
      const d2 = (cam.position.x - s.x) ** 2 + (cam.position.z - s.z) ** 2;
      const lod = d2 > (this.lodDist + 2) ** 2 ? 1 : 0;
      if (lod !== s.lod) { s.lod = lod; this.batch.setGeometryIdAt(s.id, lod ? s.e.lo.id : s.e.hi.id); }
    }
  }

  // Отдельный меш предмета в руках (свой — только тень от первого лица).
  _solo(p) {
    const umb = p.kind === 'umbrella';
    if (!p.solo) {
      const geo = umb ? this._umbMorph(p.def.v) : this.geos.get(p.key).hi.geo;
      p.solo = new THREE.Mesh(geo, this.soloMat);
      p.solo.castShadow = true;
      p.solo.receiveShadow = true;
      p.solo.frustumCulled = !umb;
      this.group.add(p.solo);
    }
    const m = p.solo;
    if (!umb && m.geometry !== this.geos.get(p.key).hi.geo) m.geometry = this.geos.get(p.key).hi.geo;
    if (umb) this._morph(m, p.openK);
    m.material = p === this.holding && this._fp() ? this.shadowMat : this.soloMat;
    m.visible = true;
    m.position.copy(p.pos);
    m.quaternion.copy(p.quat);
    m.scale.setScalar(p.scale * (p.holdScale || 1));
  }

  _umbMorph(style) {
    const c = this._morphGeos || (this._morphGeos = new Map());
    if (!c.has(style)) c.set(style, PG.umbrellaMorphGeo(style));
    return c.get(style);
  }

  _morph(mesh, t) {
    const s = t * t * (3 - 2 * t);
    const inf = mesh.morphTargetInfluences;
    if (!inf) return;
    inf[0] = s < 0.5 ? s * 2 : 2 - s * 2;
    inf[1] = s < 0.5 ? 0 : s * 2 - 1;
  }

  // Свой предмет от первого лица: меш в сцене рук на якоре кисти.
  _fpUpdate(fp) {
    const hands = this.game.hands;
    const p = fp && !this.game.debugCam ? this.holding : null;
    const umb = p?.kind === 'umbrella';
    const geo = p ? (umb ? this._umbMorph(p.def.v) : this.geos.get(p.key).hi.geo) : null;
    if (p !== this.fpProp || (p && this.fpMesh.geometry !== geo)) {
      this.fpMesh?.parent?.remove(this.fpMesh);
      this.fpProp = p;
      if (p) {
        if (!this.fpMesh) {
          this.fpMesh = new THREE.Mesh(geo, this.fpMat);
          this.fpMesh.frustumCulled = false;
        }
        if (this.fpMesh.geometry !== geo) { this.fpMesh.geometry = geo; this.fpMesh.updateMorphTargets(); }
        const hold = HOLD[p.kind].fp;
        this.fpMesh.position.copy(hold.p);
        this.fpMesh.quaternion.copy(hold.q);
        this.fpMesh.scale.setScalar(hold.s);
        hands.anchor?.(p.k.hold)?.add(this.fpMesh);
      }
    }
    if (p && umb) this._morph(this.fpMesh, p.openK);
  }

  // Ореолы горящих фонарей (и свой фонарь в руках — в сцене рук).
  _glows(fp) {
    if (!this.glowSprites) {
      this.glowSprites = [];
      this.glowMat = new THREE.SpriteMaterial({ map: this.glowTex, color: '#ffc77a', transparent: true, opacity: 0.6, blending: THREE.AdditiveBlending, depthWrite: false, fog: false });
    }
    let n = 0;
    const cam = this.game.camera;
    for (const p of this.list) {
      if (p.kind !== 'lantern' || !p.state) continue;
      if (p === this.holding && fp) continue;
      _v.copy(PG.LANTERN_CORE).applyQuaternion(p.quat).add(p.pos);
      if (_v.distanceToSquared(cam.position) > 70 * 70) continue;
      let s = this.glowSprites[n];
      if (!s) {
        s = new THREE.Sprite(this.glowMat);
        s.renderOrder = 5;
        this.glowSprites.push(s);
        this.group.add(s);
      }
      s.visible = true;
      s.position.copy(_v).addScaledVector(_v2.subVectors(cam.position, _v).normalize(), 0.22); // чуть ближе к камере
      const fl = 1 + Math.sin(this.game.time * 9 + n * 2) * 0.04 + Math.sin(this.game.time * 23 + n) * 0.03;
      s.scale.setScalar(0.72 * fl);
      n++;
    }
    for (let i = n; i < (this.glowSprites?.length || 0); i++) this.glowSprites[i].visible = false;
    // фонарь в руках от первого лица
    const lit = fp && this.holding?.kind === 'lantern' && this.holding.state && this.fpMesh?.parent;
    if (lit && !this.fpGlow) {
      this.fpGlow = new THREE.Sprite(this.glowMat);
      this.fpGlow.position.copy(PG.LANTERN_CORE);
      this.fpGlow.scale.setScalar(0.6);
    }
    if (this.fpGlow) {
      if (lit && this.fpGlow.parent !== this.fpMesh) this.fpMesh.add(this.fpGlow);
      if (!lit && this.fpGlow.parent) this.fpGlow.parent.remove(this.fpGlow);
    }
  }

  _hints() {
    const g = this.game, ui = g.ui, touch = g.isTouch;
    const E = touch ? '✋' : 'E', Q = touch ? '↗' : 'Q';
    const hints = [];
    const h = this.holding;
    this.target = h ? null : this.pick();
    this.portal = !this.target && !h ? this.nearestPortal() : null;
    if (h && !this.pending) {
      const act = h.k.act;
      if (act) {
        const label = act === 'swing' ? 'Замах битой'
          : act === 'strum' ? 'Сыграть аккорд'
            : h.kind === 'umbrella' ? (h.state ? 'Сложить зонт' : 'Раскрыть зонт')
              : h.kind === 'lantern' ? (h.state ? 'Погасить фонарь' : 'Зажечь фонарь')
                : h.state ? 'Выключить музыку' : 'Включить музыку';
        hints.push({ key: touch ? E : 'E / ЛКМ', text: label }, { key: Q, text: 'Положить · держи — бросок' });
      } else hints.push({ key: E, text: 'Поставить' }, { key: Q, text: 'Бросить (держи — сильнее)' });
    } else if (this.target) hints.push({ key: E, text: `Взять ${NAMES[this.target.kind]}` });
    else if (this.portal) hints.push({ key: E, text: this.portal.label });
    ui.setHints(hints);
    ui.setCrosshairHot(!!this.target || !!this.portal);
  }

  // ---------- сеть ----------

  applyWelcome(w) {
    for (const p of this.list) { if (p.owner !== 'me') p.owner = null; }
    for (const s of w.props || []) {
      const p = this.byId.get(s.id);
      if (!p) continue;
      if (Number.isInteger(s.s)) { this.setState(p, s.s, false, true); p.openK = p.state; }
      if (!s.p) continue;
      p.pos.set(...s.p);
      p.goal.pos.set(...s.p);
      if (s.q) { p.quat.set(...s.q); p.goal.quat.set(...s.q); }
      p.owner = s.o ? (s.o === this.net?.id ? 'me' : s.o) : null;
      p.held = !!s.h;
      p.dirty = true;
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
      this._setHolding(null);
      if (this.pending?.p === p) this.pending = null;
    }
    p.kicked = false;
    if (m.o) {
      p.owner = m.o;
      p.held = !!m.h;
    } else {
      p.owner = null;
      p.held = false;
      if (m.p) { p.goal.pos.set(...m.p); p.pos.copy(p.goal.pos); }
      if (m.q) { p.goal.quat.set(...m.q); p.quat.copy(p.goal.quat); }
    }
    p.dirty = true;
  }

  onDeny(m) {
    const p = this.byId.get(m.id);
    if (!p) return;
    if (p.kicked) { // удар по предмету, который уже кто-то держит/кидает: возвращаем ему
      p.kicked = false;
      p.owner = p.prev?.owner && p.prev.owner !== 'me' ? p.prev.owner : '?';
      return;
    }
    if (this.holding !== p) return;
    this._setHolding(null);
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

  // Чужое действие предметом: анимация у его аватара и звук в точке предмета.
  onRemoteUse(m) {
    const p = this.byId.get(m.id);
    const r = this.game.remotes.get(m.from);
    r?.avatar.act?.(m.a);
    if (!p) return;
    const a = this.game.audio;
    if (m.a === 'swing') a.swing?.(p.pos);
    else if (m.a === 'strum') a.strum?.((m.c | 0) % CHORDS, p.pos);
    else if (m.a === 'throw') a.throwSound?.();
  }

  onRemoteState(m) {
    const p = this.byId.get(m.id);
    if (p) this.setState(p, m.s, false);
  }
}
