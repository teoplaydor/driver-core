// Постройки и декорации: хаб (костёр, беседка, указатели, фонари), пирс, домик в зиме, амбар,
// многоэтажка с квартирой и арена. Всё процедурное; коллизии кладём в world.collisions.

import * as THREE from 'three';
import { heightAt, samplePath, PATHS, WATER_Y, LAKE } from './terrain-data.js';
import { buildHeroTree } from './vegetation.js';
import { mulberry32, TAU } from '../util/math.js';
import {
  CAMPFIRE, GAZEBO, TABLE, BOARD, INTERIOR_X, APARTMENT, SPAWN,
} from '../../shared/layout.js';

// ---------- помощники ----------

const _mats = new Map();
const M = (color, extra) => {
  const key = color + (extra ? JSON.stringify(extra) : '');
  let m = _mats.get(key);
  if (!m) {
    m = new THREE.MeshStandardMaterial({ color, roughness: 0.85, metalness: 0, ...extra });
    _mats.set(key, m);
  }
  return m;
};
const B = (w, h, d) => new THREE.BoxGeometry(w, h, d);
const CYL = (rt, rb, h, seg = 12) => new THREE.CylinderGeometry(rt, rb, h, seg);

function put(parent, geo, color, x, y, z, { ry = 0, rx = 0, rz = 0, cast = true, receive = true, extra } = {}) {
  const m = new THREE.Mesh(geo, typeof color === 'string' ? M(color, extra) : color);
  m.position.set(x, y, z);
  m.rotation.set(rx, ry, rz);
  m.castShadow = cast;
  m.receiveShadow = receive;
  parent.add(m);
  return m;
}

// Коробка, стоящая на y (низ) с коллизией.
function solid(parent, col, x, y, z, w, h, d, color, ry = 0, extra) {
  const m = put(parent, B(w, h, d), color, x, y + h / 2, z, { ry, extra });
  col.addBox(x, z, w / 2, d / 2, ry, y, y + h);
  return m;
}

// Призма (двускатная крыша): сечение - треугольник w x h, длина len вдоль оси z.
function prism(w, h, len) {
  const s = new THREE.Shape();
  s.moveTo(-w / 2, 0); s.lineTo(w / 2, 0); s.lineTo(0, h); s.lineTo(-w / 2, 0);
  const g = new THREE.ExtrudeGeometry(s, { depth: len, bevelEnabled: false });
  g.translate(0, 0, -len / 2);
  return g;
}

let _glowTex;
function glowTexture() {
  if (_glowTex) return _glowTex;
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d');
  const r = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  r.addColorStop(0, 'rgba(255,255,255,1)');
  r.addColorStop(0.25, 'rgba(255,255,255,0.45)');
  r.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = r;
  g.fillRect(0, 0, 64, 64);
  _glowTex = new THREE.CanvasTexture(c);
  _glowTex.colorSpace = THREE.SRGBColorSpace;
  return _glowTex;
}

function glow(parent, color, size, x, y, z, opacity = 0.7) {
  const s = new THREE.Sprite(new THREE.SpriteMaterial({
    map: glowTexture(), color, transparent: true, opacity, depthWrite: false, blending: THREE.AdditiveBlending,
  }));
  s.scale.set(size, size, 1);
  s.position.set(x, y, z);
  parent.add(s);
  return s;
}

function labelTexture(text, { w = 256, h = 64, bg = '#c89a62', fg = '#4a2d18', font = '800 34px "Baloo 2", system-ui, sans-serif', align = 'center' } = {}) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const g = c.getContext('2d');
  g.fillStyle = bg;
  g.fillRect(0, 0, w, h);
  g.strokeStyle = 'rgba(70,40,20,0.35)';
  g.lineWidth = 5;
  g.strokeRect(3, 3, w - 6, h - 6);
  g.fillStyle = fg;
  g.font = font;
  g.textBaseline = 'middle';
  g.textAlign = align;
  g.fillText(text, align === 'center' ? w / 2 : 14, h / 2 + 2);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  return t;
}

const WOOD = '#a8764a', WOOD_D = '#6f4a2e', STONE = '#9c9a93', ROOF_RED = '#b5523b';

// WS6 (временно, контракт C5 — при слиянии берётся разметка WS5b): меш закрывает помещение сверху.
function roof(mesh, x0, x1, z0, z1) {
  mesh.userData.roof = true;
  mesh.userData.roofArea = { x0, x1, z0, z1 };
  return mesh;
}

// ---------- основной сборщик ----------

export function buildStructures(world) {
  const group = new THREE.Group();
  group.name = 'structures';
  const col = world.collisions;
  const anim = [];
  const portals = [];
  const lights = [];

  campfire(group, col, anim, lights);
  gazebo(group, col);
  heroTree(group, col, anim);
  signpost(group, col);
  lanterns(group);
  pier(group, col);
  cabin(group, col, anim);
  farm(group, col);
  const apt = apartmentBlock(group, col, portals);
  arena(group, col);
  apartmentInterior(group, col, portals, anim, world.uTime, apt);

  return {
    group,
    portals,
    update(dt, t) { for (const f of anim) f(dt, t); },
  };
}

// ---------- костёр и брёвна ----------

function campfire(g, col, anim, lights) {
  const { x: cx, z: cz } = CAMPFIRE;
  const y = heightAt(cx, cz);
  const root = new THREE.Group();
  root.position.set(cx, y, cz);
  g.add(root);
  const rand = mulberry32(8);

  for (let i = 0; i < 12; i++) {
    const a = (i / 12) * TAU, r = 1.3 + rand() * 0.12;
    const s = put(root, new THREE.DodecahedronGeometry(0.3 + rand() * 0.12, 0), i % 3 ? STONE : '#85837c', Math.cos(a) * r, 0.16, Math.sin(a) * r, { rx: rand() * 3, ry: rand() * 3 });
    s.scale.y = 0.72;
  }
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * TAU + 0.3;
    put(root, CYL(0.1, 0.12, 1.5, 7), WOOD_D, Math.cos(a) * 0.35, 0.5, Math.sin(a) * 0.35, { rz: Math.cos(a) * 0.6, rx: -Math.sin(a) * 0.6 });
  }
  const flames = [0, 1, 2].map((i) => {
    const mat = new THREE.MeshBasicMaterial({
      color: ['#ff6a14', '#ffa826', '#ffe07a'][i], transparent: true, opacity: 0.88 - i * 0.12,
      blending: THREE.AdditiveBlending, depthWrite: false,
    });
    const m = new THREE.Mesh(new THREE.ConeGeometry(0.46 - i * 0.12, 1.25 + i * 0.3, 8, 1, true), mat);
    m.position.y = 0.75 + i * 0.12;
    root.add(m);
    return m;
  });
  const halo = glow(root, '#ff9a3c', 7, 0, 1.2, 0, 0.5);
  const light = new THREE.PointLight('#ff9a4a', 28, 22, 2);
  light.position.set(0, 1.3, 0);
  root.add(light);
  lights.push(light);

  // искры
  const N = 26;
  const pos = new Float32Array(N * 3), seed = new Float32Array(N);
  for (let i = 0; i < N; i++) seed[i] = rand();
  const sparkGeo = new THREE.BufferGeometry();
  sparkGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const sparks = new THREE.Points(sparkGeo, new THREE.PointsMaterial({
    color: '#ffc46a', size: 0.12, transparent: true, opacity: 0.9, blending: THREE.AdditiveBlending, depthWrite: false,
  }));
  sparks.frustumCulled = false;
  root.add(sparks);

  anim.push((dt, t) => {
    const f = 1 + Math.sin(t * 13) * 0.08 + Math.sin(t * 23.7) * 0.06;
    flames.forEach((m, i) => {
      m.scale.set(1 + Math.sin(t * (9 + i * 3) + i) * 0.1, f * (1 + i * 0.04), 1 + Math.cos(t * (8 + i * 2)) * 0.1);
      m.rotation.y = t * (1.2 + i * 0.5);
    });
    light.intensity = 26 + Math.sin(t * 11) * 4 + Math.sin(t * 27) * 2.5;
    halo.material.opacity = 0.42 + Math.sin(t * 9) * 0.08;
    for (let i = 0; i < N; i++) {
      const life = (t * 0.5 + seed[i]) % 1;
      const a = seed[i] * 40;
      pos[i * 3] = Math.cos(a) * 0.35 * life + Math.sin(t * 2 + i) * 0.12 * life;
      pos[i * 3 + 1] = 0.7 + life * 3.2;
      pos[i * 3 + 2] = Math.sin(a) * 0.35 * life;
    }
    sparkGeo.attributes.position.needsUpdate = true;
  });

  col.addCircle(cx, cz, 1.55, y - 1, y + 2);

  // брёвна-скамейки вокруг, оставляя свободными дорожки по сторонам света
  for (let k = 0; k < 4; k++) {
    for (const off of [-0.34, 0.34]) {
      const a = Math.PI / 4 + (k * Math.PI) / 2 + off;
      const R = 4.7;
      const px = cx + Math.cos(a) * R, pz = cz + Math.sin(a) * R;
      const ry = -a - Math.PI / 2;
      const gy = heightAt(px, pz);
      put(g, CYL(0.26, 0.26, 1.9, 10), WOOD, px, gy + 0.26, pz, { ry, rz: Math.PI / 2 });
      put(g, CYL(0.27, 0.27, 0.05, 10), '#d8b07a', px + Math.cos(ry) * 0.95, gy + 0.26, pz - Math.sin(ry) * 0.95, { ry, rz: Math.PI / 2, cast: false });
      col.addBox(px, pz, 0.95, 0.26, ry, gy - 0.3, gy + 0.5);
    }
  }
}

// ---------- беседка с шахматным столом ----------

function gazebo(g, col) {
  const { x, z } = GAZEBO;
  const y0 = heightAt(x, z);
  const top = GAZEBO.floorTop;
  const root = new THREE.Group();
  g.add(root);

  solid(root, col, x, y0, z, 8.6, top - y0, 8.6, '#caa77a');
  for (const sx of [-1, 1]) {
    for (const sz of [-1, 1]) {
      const px = x + sx * 3.95, pz = z + sz * 3.95;
      put(root, CYL(0.22, 0.24, 3.4, 10), '#e6d2ae', px, top + 1.7, pz);
      put(root, B(0.6, 0.18, 0.6), WOOD_D, px, top + 0.09, pz);
      col.addCircle(px, pz, 0.28, top - 1, top + 4);
    }
  }
  // перила с проёмами
  for (const [dx, dz, w, d] of [[0, -3.95, 8.4, 0.12], [0, 3.95, 3.4, 0.12], [-3.95, 0, 0.12, 8.4], [3.95, 0, 0.12, 8.4]]) {
    put(root, B(w, 0.12, d), WOOD, x + dx, top + 0.95, z + dz);
  }
  // крыша-пирамида
  const cone = put(root, new THREE.ConeGeometry(7.2, 2.4, 4, 1, false), ROOF_RED, x, top + 3.4 + 1.2, z, { ry: Math.PI / 4, extra: { side: THREE.DoubleSide } });
  cone.castShadow = true;
  roof(cone, x - 4.45, x + 4.45, z - 4.45, z + 4.45);
  roof(put(root, B(8.9, 0.22, 8.9), WOOD_D, x, top + 3.4 + 0.1, z), x - 4.45, x + 4.45, z - 4.45, z + 4.45);
  roof(put(root, CYL(0.06, 0.12, 0.7, 8), '#e8d6b0', x, top + 3.4 + 2.55, z), x - 4.45, x + 4.45, z - 4.45, z + 4.45);

  // стол и шахматная доска
  put(root, CYL(0.18, 0.28, TABLE.top - top, 10), WOOD_D, x, top + (TABLE.top - top) / 2, z);
  put(root, CYL(0.92, 0.92, 0.08, 24), WOOD, x, TABLE.top - 0.04, z);
  col.addBox(x, z, 0.92, 0.92, 0, top, TABLE.top);

  const cvs = document.createElement('canvas');
  cvs.width = cvs.height = 256;
  const c2 = cvs.getContext('2d');
  for (let r = 0; r < 8; r++) for (let f = 0; f < 8; f++) {
    c2.fillStyle = (r + f) % 2 ? '#7a4a2a' : '#f0d9b0';
    c2.fillRect(f * 32, r * 32, 32, 32);
  }
  const tex = new THREE.CanvasTexture(cvs);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.magFilter = THREE.NearestFilter;
  const cell = BOARD.cell;
  const boardMat = [M('#4a2d18'), M('#4a2d18'), new THREE.MeshStandardMaterial({ map: tex, roughness: 0.7 }), M('#4a2d18'), M('#4a2d18'), M('#4a2d18')];
  put(root, B(cell * 8 + 0.06, 0.05, cell * 8 + 0.06), boardMat, BOARD.x, BOARD.y - 0.025, BOARD.z, { cast: false });
  // опора под фигуры: сама доска, не блокирует ходьбу
  col.addBox(BOARD.x, BOARD.z, cell * 4 + 0.03, cell * 4 + 0.03, 0, TABLE.top, BOARD.y, false);

  // две скамейки внутри
  for (const sz of [-1, 1]) {
    solid(root, col, x, top, z + sz * 2.9, 3.4, 0.45, 0.5, WOOD);
  }
}

// ---------- большое дерево с фонариками ----------

function heroTree(g, col, anim) {
  const x = -17, z = -21;
  const y = heightAt(x, z);
  const tree = buildHeroTree(2.6, '#7cc650');
  tree.position.set(x, y - 0.1, z);
  tree.rotation.y = 0.6;
  g.add(tree);
  col.addCircle(x, z, 1.05);
  const rand = mulberry32(21);
  const bulbs = [];
  for (let i = 0; i < 9; i++) {
    const a = (i / 9) * TAU + rand() * 0.4, r = 3.2 + rand() * 1.6;
    const bx = x + Math.cos(a) * r, bz = z + Math.sin(a) * r, by = y + 5.2 + rand() * 2.8;
    put(g, CYL(0.01, 0.01, 1.4, 4), '#403028', bx, by + 0.7, bz, { cast: false });
    put(g, new THREE.SphereGeometry(0.2, 12, 10), M('#ffe3a6', { emissive: '#ffb84a', emissiveIntensity: 1.6 }), bx, by, bz, { cast: false });
    bulbs.push(glow(g, '#ffc268', 2.6, bx, by, bz, 0.6));
  }
  anim.push((dt, t) => bulbs.forEach((b, i) => { b.material.opacity = 0.52 + Math.sin(t * 1.7 + i * 1.9) * 0.1; }));
  // качели на толстой ветке
  const sx = x + 3.4, sz = z + 0.5;
  put(g, CYL(0.02, 0.02, 4.6, 5), '#4a3828', sx - 0.4, y + 3.3, sz, { cast: false });
  put(g, CYL(0.02, 0.02, 4.6, 5), '#4a3828', sx + 0.4, y + 3.3, sz, { cast: false });
  put(g, B(1.0, 0.08, 0.4), WOOD, sx, y + 1.0, sz);
}

// ---------- указатель ----------

function signpost(g, col) {
  const px = -5.2, pz = 8.2;
  const y = heightAt(px, pz);
  put(g, B(0.2, 3.2, 0.2), WOOD_D, px, y + 1.6, pz);
  col.addCircle(px, pz, 0.28);
  const items = [
    { t: 'Зима', dx: -6, dz: -45 }, { t: 'Осень', dx: 45, dz: 6 }, { t: 'Дождь', dx: 8, dz: 45 },
    { t: 'Каньон и арена', dx: -40, dz: -4 }, { t: 'Озеро', dx: -22, dz: 18 }, { t: 'Беседка', dx: 12, dz: -6 },
  ];
  items.forEach((it, i) => {
    const th = Math.atan2(-(it.dz - pz), it.dx - px);
    const grp = new THREE.Group();
    grp.position.set(px, y + 2.9 - i * 0.4, pz);
    grp.rotation.y = th;
    g.add(grp);
    const front = new THREE.MeshStandardMaterial({ map: labelTexture(`${it.t} →`, { w: 320 }), roughness: 0.8 });
    const back = new THREE.MeshStandardMaterial({ map: labelTexture(`← ${it.t}`, { w: 320 }), roughness: 0.8 });
    const side = M('#c89a62');
    const board = new THREE.Mesh(B(1.9, 0.34, 0.06), [side, side, side, side, front, back]);
    board.position.x = 0.7;
    board.castShadow = true;
    grp.add(board);
    const tip = new THREE.Mesh(B(0.24, 0.34, 0.24), side);
    tip.rotation.y = Math.PI / 4;
    tip.position.x = 1.62;
    grp.add(tip);
  });
}

// ---------- фонари вдоль дорожек ----------

function lanterns(g) {
  const lampMat = M('#ffe2a0', { emissive: '#ffb84a', emissiveIntensity: 1.8 });
  const dark = '#33343a';
  let n = 0;
  for (const path of PATHS) {
    if (path.id === 'to-winter' || path.id === 'to-autumn' || path.id === 'to-rain' || path.id === 'to-arena' || path.id === 'lake') {
      const pts = samplePath(path, 1.5);
      let dist = 0;
      let next = 11;
      for (let i = 1; i < pts.length; i++) {
        dist += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
        if (dist < next || dist > 120) continue;
        next += 15;
        const dx = pts[i][0] - pts[i - 1][0], dz = pts[i][1] - pts[i - 1][1];
        const l = Math.hypot(dx, dz) || 1;
        const side = n++ % 2 ? 1 : -1;
        const lx = pts[i][0] + (-dz / l) * 2.6 * side, lz = pts[i][1] + (dx / l) * 2.6 * side;
        const y = heightAt(lx, lz);
        put(g, CYL(0.06, 0.09, 2.3, 7), dark, lx, y + 1.15, lz);
        put(g, B(0.3, 0.38, 0.3), lampMat, lx, y + 2.4, lz, { cast: false });
        put(g, new THREE.ConeGeometry(0.28, 0.2, 4), dark, lx, y + 2.72, lz, { ry: Math.PI / 4 });
        glow(g, '#ffc268', 3.2, lx, y + 2.4, lz, 0.5);
      }
    }
  }
}

// ---------- пирс на озере ----------

function pier(g, col) {
  // ищем береговую линию по лучу от центра озера в сторону хаба
  const dir = new THREE.Vector2(-20 - LAKE.x, 20 - LAKE.z).normalize();
  let shore = LAKE.r + 14;
  for (let r = LAKE.r + 14; r > 0; r -= 0.5) {
    if (heightAt(LAKE.x + dir.x * r, LAKE.z + dir.y * r) < WATER_Y + 0.1) { shore = r; break; }
  }
  const len = 11, topY = WATER_Y + 0.62;
  const cx = LAKE.x + dir.x * (shore + 1.5 - len / 2), cz = LAKE.z + dir.y * (shore + 1.5 - len / 2);
  const ry = Math.atan2(-dir.y, dir.x);
  put(g, B(len, 0.14, 2.4), '#b98a58', cx, topY - 0.07, cz, { ry });
  col.addBox(cx, cz, len / 2, 1.2, ry, topY - 0.3, topY);
  for (let i = 0; i < 4; i++) {
    for (const s of [-1, 1]) {
      const t = -len / 2 + 0.6 + (i * (len - 1.2)) / 3;
      const along = new THREE.Vector2(Math.cos(ry), -Math.sin(ry));
      const perp = new THREE.Vector2(Math.sin(ry), Math.cos(ry));
      const wx = cx + along.x * t + perp.x * s * 1.1, wz = cz + along.y * t + perp.y * s * 1.1;
      put(g, CYL(0.1, 0.1, 3.2, 7), WOOD_D, wx, topY - 1.5, wz);
    }
  }
}

// ---------- зимний домик ----------

function cabin(g, col, anim) {
  const cx = -16, cz = -176;
  const y = heightAt(cx, cz);
  const root = new THREE.Group();
  root.position.set(cx, y, cz);
  g.add(root);
  put(root, B(8, 3.2, 6), '#8f5d38', 0, 1.6, 0);
  col.addBox(cx, cz, 4, 3, 0, y - 1, y + 3.4);
  // крыша под снегом: белая призма и тёмные доски карниза по краям
  put(root, prism(7.6, 2.3, 9.6), '#f1f6fb', 0, 3.2, 0, { ry: Math.PI / 2 });
  for (const s of [-1, 1]) put(root, B(9.6, 0.16, 0.2), '#6a4a30', 0, 3.2, s * 3.8);
  put(root, B(0.8, 2.8, 0.8), '#8a8780', 2.4, 4.4, -1.2);
  col.addCircle(cx + 2.4, cz - 1.2, 0.6, y, y + 6);
  put(root, B(1.2, 2.1, 0.12), '#4a2d18', 0, 1.05, 3.04);
  const win = M('#ffd27a', { emissive: '#ffb347', emissiveIntensity: 1.4 });
  for (const wx of [-2.4, 2.4]) put(root, B(1.2, 1.0, 0.1), win, wx, 1.8, 3.03, { cast: false });
  put(root, B(1.0, 1.0, 0.1), win, 4.03, 1.8, 0, { cast: false, ry: Math.PI / 2 });
  glow(root, '#ffb347', 4, 0, 1.8, 3.8, 0.45);
  // крыльцо и дрова
  put(root, B(3, 0.2, 1.6), WOOD, 0, 0.1, 3.9);
  for (let i = 0; i < 6; i++) put(root, CYL(0.14, 0.14, 1.2, 7), WOOD, -3.3 + (i % 3) * 0.3, 0.3 + Math.floor(i / 3) * 0.28, 1.5, { rz: Math.PI / 2 });

  // дым
  const puffs = [];
  for (let i = 0; i < 6; i++) {
    const s = glow(root, '#dfe6ee', 1.6, 2.4, 5.8, -1.2, 0.3);
    s.material.blending = THREE.NormalBlending;
    puffs.push(s);
  }
  anim.push((dt, t) => puffs.forEach((s, i) => {
    const life = (t * 0.18 + i / 6) % 1;
    s.position.set(2.4 + life * 2.2 + Math.sin(t + i) * 0.2, 5.8 + life * 5, -1.2);
    s.scale.setScalar(1.2 + life * 3);
    s.material.opacity = 0.32 * (1 - life);
  }));
}

// ---------- ферма ----------

function farm(g, col) {
  const cx = 156, cz = -30;
  const y = heightAt(cx, cz);
  const root = new THREE.Group();
  root.position.set(cx, y, cz);
  g.add(root);
  put(root, B(10, 5.2, 8), '#b04a3a', 0, 2.6, 0);
  col.addBox(cx, cz, 5, 4, 0, y - 1, y + 5.4);
  put(root, prism(11.2, 3.0, 8.8), '#8d9298', 0, 5.2, 0, { ry: Math.PI / 2 }).castShadow = true;
  put(root, B(3.4, 3.8, 0.12), '#40201a', 0, 1.9, 4.05);
  put(root, B(0.2, 3.8, 0.14), '#f0e6d0', 0, 1.9, 4.08);
  const rand = mulberry32(31);
  for (let i = 0; i < 7; i++) {
    const a = rand() * TAU, r = 8 + rand() * 6;
    const bx = Math.cos(a) * r, bz = Math.sin(a) * r;
    const by = heightAt(cx + bx, cz + bz) - y;
    put(root, CYL(0.85, 0.85, 1.2, 14), '#d9b44a', bx, by + 0.85, bz, { rz: Math.PI / 2, ry: rand() * 3 });
    col.addCircle(cx + bx, cz + bz, 1.0, y - 1, y + 3);
  }
  // пугало
  const sx = 8, sz = 9;
  const sy = heightAt(cx + sx, cz + sz) - y;
  put(root, CYL(0.06, 0.06, 2.6, 6), WOOD_D, sx, sy + 1.3, sz);
  put(root, CYL(0.05, 0.05, 1.8, 6), WOOD_D, sx, sy + 2.0, sz, { rz: Math.PI / 2 });
  put(root, new THREE.SphereGeometry(0.28, 12, 10), '#e8d28a', sx, sy + 2.7, sz);
  put(root, new THREE.ConeGeometry(0.4, 0.5, 10), '#6a4a30', sx, sy + 3.1, sz);
  put(root, CYL(0.5, 0.5, 0.04, 14), '#6a4a30', sx, sy + 2.86, sz);
  put(root, B(0.9, 1.1, 0.3), '#7a8fb0', sx, sy + 1.8, sz);
}

// ---------- многоэтажка (дождливый район) ----------

function apartmentBlock(g, col, portals) {
  const cx = 22, cz = 181;
  const y = heightAt(cx, cz - 6);
  const root = new THREE.Group();
  root.position.set(cx, y, cz);
  g.add(root);
  const H = 17, W = 13, D = 12;
  put(root, B(W, H, D), '#b9694a', 0, H / 2, 0);
  col.addBox(cx, cz, W / 2, D / 2, 0, y - 1, y + H);
  put(root, B(W + 0.8, 0.5, D + 0.8), '#6b5a52', 0, H + 0.25, 0);
  // окна: тёмные и тёплые светящиеся
  const rand = mulberry32(77);
  const lit = M('#ffd27a', { emissive: '#ffb347', emissiveIntensity: 1.2 });
  const dark = M('#38445a', { roughness: 0.3 });
  for (let r = 0; r < 4; r++) {
    for (let c = 0; c < 4; c++) {
      const wx = -4.5 + c * 3, wy = 4.2 + r * 3.4;
      put(root, B(1.5, 1.8, 0.1), rand() < 0.45 ? lit : dark, wx, wy, -D / 2 - 0.03, { cast: false });
    }
  }
  // дверь и козырёк
  put(root, B(2.0, 2.8, 0.14), '#3a2a22', 0, 1.4, -D / 2 - 0.05);
  put(root, B(3.4, 0.2, 1.4), '#52413a', 0, 3.1, -D / 2 - 0.7);
  put(root, B(0.12, 0.12, 0.12), '#ffd27a', 0.7, 1.4, -D / 2 - 0.15);
  glow(root, '#ffc268', 3.2, 0, 3.0, -D / 2 - 0.9, 0.5);
  const doorZ = cz - D / 2;
  portals.push({
    id: 'apt-in', x: cx, y, z: doorZ - 1.3, r: 2.0, label: 'Войти в квартиру',
    to: { x: APARTMENT.x, y: 0, z: APARTMENT.d / 2 - 1.2, yaw: 0 },
  });
  return { exit: { x: cx, y, z: doorZ - 3.2, yaw: 0 } };
}

// ---------- квартира внутри (за пределами карты) ----------

function apartmentInterior(g, col, portals, anim, uTime, apt) {
  const { x: ox, d, w, h } = APARTMENT;
  const root = new THREE.Group();
  g.add(root);
  const hw = w / 2, hd = d / 2;
  // пол, потолок, стены
  put(root, B(w + 1, 0.2, d + 1), '#b08256', ox, -0.1, 0, { cast: false });
  roof(put(root, B(w + 1, 0.2, d + 1), '#efe3cf', ox, h + 0.1, 0, { cast: false }), ox - hw - 0.5, ox + hw + 0.5, -hd - 0.5, hd + 0.5);
  const wall = '#efdfc4';
  col.addBox(ox, 0, hw + 0.4, hd + 0.4, 0, -1, 0.0, false);
  solid(root, col, ox, 0, hd + 0.25, w + 1, h, 0.5, wall); // юг: дверь-выход
  solid(root, col, ox - hw - 0.25, 0, 0, 0.5, h, d + 1, wall);
  solid(root, col, ox + hw + 0.25, 0, 0, 0.5, h, d + 1, wall);
  // север с окном 3.6 м: два простенка, подоконник и перемычка
  const gap = 3.6;
  const segW = hw + 0.5 - gap / 2;
  solid(root, col, ox - gap / 2 - segW / 2, 0, -hd - 0.25, segW, h, 0.5, wall);
  solid(root, col, ox + gap / 2 + segW / 2, 0, -hd - 0.25, segW, h, 0.5, wall);
  solid(root, col, ox, 0, -hd - 0.25, gap, 0.9, 0.5, wall);
  solid(root, col, ox, 2.5, -hd - 0.25, gap, h - 2.5, 0.5, wall);
  put(root, B(gap + 0.3, 0.08, 0.6), '#5a3d28', ox, 0.94, -hd + 0.05);
  // стекло и вид на дождливый город
  const rainMat = new THREE.ShaderMaterial({
    uniforms: { uTime: uTime },
    vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
    fragmentShader: `
      uniform float uTime; varying vec2 vUv;
      float h(float n){ return fract(sin(n * 91.345) * 47453.5453); }
      void main(){
        vec2 uv = vUv;
        vec3 col = mix(vec3(0.05, 0.07, 0.13), vec3(0.17, 0.21, 0.32), uv.y);
        for (int i = 0; i < 16; i++) {
          float fi = float(i);
          vec2 c = vec2(h(fi), 0.05 + 0.45 * h(fi * 3.1));
          float r = 0.015 + 0.03 * h(fi * 7.7);
          float d = length((uv - c) * vec2(2.4, 1.0));
          vec3 lc = mix(vec3(1.0, 0.75, 0.4), vec3(0.5, 0.7, 1.0), step(0.5, h(fi * 5.3)));
          col += lc * smoothstep(r, r * 0.35, d) * (0.5 + 0.2 * sin(uTime * 2.0 + fi));
        }
        float x = uv.x * 70.0, cid = floor(x), rnd = h(cid);
        float y = fract(uv.y * 2.4 + uTime * (0.9 + rnd) + rnd * 9.0);
        float streak = smoothstep(0.0, 0.02, y) * smoothstep(0.22, 0.0, y) * step(0.7, h(cid + 3.0)) * (1.0 - abs(fract(x) - 0.5) * 2.0);
        col += vec3(0.55, 0.65, 0.85) * streak * 0.6;
        gl_FragColor = vec4(col, 1.0);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
  });
  const view = new THREE.Mesh(new THREE.PlaneGeometry(gap + 6, 5), rainMat);
  view.position.set(ox, 1.7, -hd - 2.6);
  root.add(view);
  const glass = new THREE.Mesh(new THREE.PlaneGeometry(gap, 1.6), new THREE.MeshStandardMaterial({ color: '#9ec3dd', transparent: true, opacity: 0.12, roughness: 0.1 }));
  glass.position.set(ox, 1.7, -hd + 0.0);
  root.add(glass);

  // мебель
  const rug = put(root, B(3.6, 0.03, 2.6), '#c26a4a', ox - 0.4, 0.015, 0.3, { cast: false });
  void rug;
  solid(root, col, ox - 0.4, 0, 2.3, 2.6, 0.5, 0.95, '#6a8f9a');                 // диван, сиденье
  put(root, B(2.6, 0.7, 0.25), '#5d808a', ox - 0.4, 0.85, 2.72);                  // спинка
  solid(root, col, ox - 0.4, 0, 0.6, 1.3, 0.42, 0.7, '#a8764a');                  // журнальный столик
  solid(root, col, ox + hw - 0.5, 0, -0.5, 0.6, 2.3, 3.2, '#8a5a36');             // стеллаж
  const books = ['#c94b3b', '#4a8bd0', '#e8c84a', '#5fae5a', '#8a5ac0'];
  for (let i = 0; i < 12; i++) put(root, B(0.12, 0.3, 0.45), books[i % 5], ox + hw - 0.85, 0.5 + Math.floor(i / 6) * 0.9, -1.7 + (i % 6) * 0.38, { cast: false });
  // растение
  put(root, CYL(0.25, 0.2, 0.4, 10), '#b85a3a', ox - hw + 0.6, 0.2, -hd + 0.7);
  for (let i = 0; i < 4; i++) put(root, new THREE.IcosahedronGeometry(0.34 - i * 0.04, 0), '#4f9a4a', ox - hw + 0.6 + (i % 2) * 0.12, 0.8 + i * 0.2, -hd + 0.7 + (i % 3) * 0.06, { cast: false });
  col.addCircle(ox - hw + 0.6, -hd + 0.7, 0.3, 0, 2);
  // торшер с тёплым светом
  put(root, CYL(0.03, 0.05, 1.6, 6), '#33343a', ox + 3.2, 0.8, 2.6);
  put(root, new THREE.ConeGeometry(0.3, 0.4, 12, 1, true), M('#ffe2a0', { emissive: '#ffb84a', emissiveIntensity: 1.4, side: THREE.DoubleSide }), ox + 3.2, 1.7, 2.6, { cast: false });
  glow(root, '#ffc268', 3.5, ox + 3.2, 1.7, 2.6, 0.6);
  const lamp = new THREE.PointLight('#ffb468', 9, 12, 2);
  lamp.position.set(ox + 2.6, 2.2, 1.8);
  root.add(lamp);
  // телевизор-экран на западной стене (место для проектора с видео)
  put(root, B(0.08, 1.4, 2.4), '#16161c', ox - hw + 0.08, 1.5, -0.4, { cast: false });

  portals.push({
    id: 'apt-out', x: ox, y: 0, z: hd - 0.5, r: 1.8, label: 'Выйти на улицу',
    to: { x: apt.exit.x, y: apt.exit.y, z: apt.exit.z, yaw: apt.exit.yaw },
  });
  put(root, B(1.8, 2.6, 0.1), '#4a2d18', ox, 1.3, hd - 0.02);
  void anim; void SPAWN; void h;
}

// ---------- арена (белая комната) ----------

function arena(g, col) {
  const cx = -180, cz = 14;
  const y = heightAt(cx, cz);
  const W = 24, D = 24, H = 8, T = 1;
  // белый материал чуть светится сам, иначе стены в тени выглядят серо-фиолетовыми
  const white = M('#f4f4f7', { emissive: '#ffffff', emissiveIntensity: 0.3 });
  const root = new THREE.Group();
  g.add(root);
  put(root, B(W, 0.3, D), '#e9e9ee', cx, y + 0.1, cz, { cast: false });
  col.addBox(cx, cz, W / 2, D / 2, 0, y - 2, y + 0.25, false);
  solid(root, col, cx, y, cz - D / 2 + T / 2, W, H, T, white);
  solid(root, col, cx, y, cz + D / 2 - T / 2, W, H, T, white);
  solid(root, col, cx - W / 2 + T / 2, y, cz, T, H, D, white);
  // восточная стена с проёмом 6 м
  const door = 6;
  const seg = (D - door) / 2;
  solid(root, col, cx + W / 2 - T / 2, y, cz - door / 2 - seg / 2, T, H, seg, white);
  solid(root, col, cx + W / 2 - T / 2, y, cz + door / 2 + seg / 2, T, H, seg, white);
  solid(root, col, cx + W / 2 - T / 2, y + H - 2, cz, T, 2, door, white);
  roof(put(root, B(W, 0.5, D), white, cx, y + H + 0.25, cz), cx - W / 2, cx + W / 2, cz - D / 2, cz + D / 2);
  // потолочные светильники и акцентные полосы
  const panel = M('#ffffff', { emissive: '#fff6e0', emissiveIntensity: 1.2 });
  for (const px of [-6, 6]) for (const pz of [-6, 6]) roof(put(root, B(4, 0.1, 4), panel, cx + px, y + H - 0.05, cz + pz, { cast: false }), cx - W / 2, cx + W / 2, cz - D / 2, cz + D / 2);
  put(root, B(W - 2, 0.08, 0.3), M('#ff8a3c'), cx, y + 0.32, cz - D / 2 + 1.2, { cast: false });
  put(root, B(0.3, 0.08, D - 2), M('#5aa0ff'), cx - W / 2 + 1.2, y + 0.32, cz, { cast: false });
  // вывеска над входом
  const sign = new THREE.Mesh(new THREE.PlaneGeometry(6, 1.2), new THREE.MeshBasicMaterial({ map: labelTexture('АРЕНА', { w: 512, h: 100, bg: '#ff8a3c', fg: '#ffffff', font: '800 64px "Baloo 2", system-ui, sans-serif' }) }));
  sign.position.set(cx + W / 2 + 0.06, y + H - 1.0, cz);
  sign.rotation.y = Math.PI / 2;
  root.add(sign);
}
