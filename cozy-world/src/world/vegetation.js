// Деревья, кусты и камни. Раскладка детерминированная (одна и та же у всех игроков).
// Деревья и кусты рисуются в два уровня детализации:
//  - вблизи (радиус uLod) - подробные модели с карточками листвы; экземпляры каждый кадр отбираются
//    по пирамиде видимости (с запасом под тени) и пишутся в один InstancedMesh на тип;
//  - дальше - упрощённые модели, статичные InstancedMesh по чанкам 120 м; шейдер прячет те, что ближе uLod.
// Граница считается от одного и того же центра (NATURE.uLod) и на CPU, и в шейдере, поэтому дыр и двойников нет.

import * as THREE from 'three';
import { mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js';
import { Noise2D, mulberry32, smoothstep, TAU } from '../util/math.js';
import {
  heightAt, pathDist, slopeAt, zoneWeights, SITES, WATER_Y, WORLD_RADIUS,
} from './terrain-data.js';
import { colorize, jitter, compactTemplate, mergeTemplates } from './geo.js';
import {
  firDetailed, firFar, broadDetailed, broadFar, bareDetailed, bareFar, bushDetailed, bushFar, TYPE_BOUNDS,
} from './nature/trees.js';
import { plantMaterial } from './nature/materials.js';
import { NATURE } from './nature/shared.js';

const FAR_CHUNK = 120;
const ROCK_CHUNK = 120;
const GRID = 16; // сетка для быстрого отбора ближних деревьев
const col = (h) => new THREE.Color(h);

export { forestDensity } from './nature/forest.js';

// ---------- камни ----------

// Гладкие округлые валуны: индексированная геометрия + пересчёт нормалей, плоское дно.
function buildBoulder(seed, bands, moss = false) {
  let g = new THREE.IcosahedronGeometry(1, 2);
  g.deleteAttribute('normal');
  g.deleteAttribute('uv');
  g = mergeVertices(g, 1e-4);
  jitter(g, 0.12, seed);
  const P = g.getAttribute('position');
  for (let i = 0; i < P.count; i++) {
    const y = P.getY(i);
    if (y < -0.35) P.setY(i, -0.35 + (y + 0.35) * 0.25); // приплюснутое дно - камень лежит, а не висит
  }
  g.computeVertexNormals();
  const N = g.getAttribute('normal');
  const mossC = col('#6f9a3c');
  colorize(g, (x, y, z, out) => {
    const v = bands ? 0.72 + 0.28 * (Math.sin(y * 6.2 + x * 0.8) * 0.5 + 0.5) : 0.78 + 0.22 * (y * 0.5 + 0.5);
    out.setRGB(v, v, v);
  });
  if (moss) {
    const C = g.getAttribute('color');
    for (let i = 0; i < C.count; i++) {
      const k = smoothstep(0.45, 0.85, N.getY(i)) * 0.85;
      C.setXYZ(i, C.getX(i) + (mossC.r - C.getX(i)) * k, C.getY(i) + (mossC.g - C.getY(i)) * k, C.getZ(i) + (mossC.b - C.getZ(i)) * k);
    }
  }
  return g;
}

// ---------- раскладка ----------

const MEADOW_GREENS = ['#5aa83a', '#6db843', '#4f9c39', '#7cbf48'].map(col);
const AUTUMN_COLORS = ['#e8862a', '#d0482b', '#efb73a', '#dc6a2c', '#f2c94a', '#c4402a'].map(col);
const AUTUMN_GREENS = ['#a7a83a', '#c29a34'].map(col);
const RAIN_GREENS = ['#3f7c4b', '#306b46', '#4b8651'].map(col);
const CANYON_GREENS = ['#5f9446', '#76a24c'].map(col);
const BIRCH_GREENS = ['#a6d05a', '#b9da64', '#92c54e'].map(col);
const BUSH_GREENS = ['#4c963a', '#5aa53f', '#3f8a37'].map(col);
const pick = (arr, rand) => arr[Math.floor(rand() * arr.length)];

function nearSite(x, z, pad) {
  for (const s of SITES) {
    if (Math.hypot(x - s.x, z - s.z) < s.r + pad) return true;
  }
  return false;
}

function scatter() {
  const rand = mulberry32(1337);
  const noise = new Noise2D(31337);
  const out = { trees: [], rock: [], rockMoss: [], boulder: [] };
  const w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
  const STEP = 5;
  const add = (type, o) => { o.type = type; out.trees.push(o); return o; };
  for (let gz = -WORLD_RADIUS; gz < WORLD_RADIUS; gz += STEP) {
    for (let gx = -WORLD_RADIUS; gx < WORLD_RADIUS; gx += STEP) {
      const x = gx + (rand() - 0.5) * STEP * 0.95, z = gz + (rand() - 0.5) * STEP * 0.95;
      const r = Math.hypot(x, z);
      if (r > WORLD_RADIUS - 4) continue;
      const y = heightAt(x, z);
      if (y < WATER_Y + 0.45) continue;
      zoneWeights(x, z, w);
      const slope = slopeAt(x, z);
      const pd = pathDist(x, z);
      const site = nearSite(x, z, 3);

      // --- валуны каньона: крупные оранжевые глыбы по склонам и россыпью ---
      if (w.canyon > 0.25 && !site && pd > 5) {
        const rockN = noise.fbm(x * 0.03 + 3, z * 0.03 - 9, 2) * 0.5 + 0.5;
        const p = w.canyon * (0.1 + smoothstep(0.35, 0.9, slope) * 0.9) * (0.4 + rockN);
        if (rand() < p * 0.55) {
          const s = 1.6 + rand() * rand() * 6.5;
          out.boulder.push({ x, y: y - s * 0.3, z, ry: rand() * TAU, sx: s * (0.9 + rand() * 0.5), sy: s * (0.6 + rand() * 0.45), sz: s * (0.9 + rand() * 0.5), color: new THREE.Color().setHSL(0.045 + rand() * 0.03, 0.72 + rand() * 0.15, 0.42 + rand() * 0.1), cr: s * 0.78 });
          continue;
        }
      }

      if (site || pd < 4.2) continue;

      // --- деревья: пятна леса, поляна вокруг хаба ---
      const f = noise.fbm(x * 0.017 + 11, z * 0.017 - 7, 3) * 0.5 + 0.5;
      const forest = smoothstep(0.36, 0.66, f);
      let dens = forest * 0.95 + 0.035;
      dens *= smoothstep(30, 64, r);
      dens *= 1 - w.canyon * 0.82;
      dens *= slope > 0.85 ? 0.15 : 1;
      if (rand() < dens * 0.85) {
        const pFir = 0.62 + 0.38 * w.winter + 0.1 * w.rain - 0.4 * w.autumn - 0.35 * w.canyon;
        const s = 0.75 + rand() * 0.65;
        const inst = { x, y: y - 0.1, z, ry: rand() * TAU, sx: s * (0.9 + rand() * 0.2), sy: s, sz: s * (0.9 + rand() * 0.2) };
        if (rand() < pFir) {
          const v = 0.88 + rand() * 0.26;
          inst.color = new THREE.Color(v, v, v);
          const snowy = w.winter > 0.5 || rand() < w.winter * 1.15 - 0.1;
          if (w.winter > 0.4) inst.color.multiply(new THREE.Color(0.86, 0.97, 1.06));
          add(snowy ? 'firSnow' : 'fir', inst);
        } else if (w.winter > 0.55) {
          inst.color = new THREE.Color(1, 1, 1);
          add('bare', inst);
        } else if (rand() < w.autumn) {
          inst.color = (rand() < 0.82 ? pick(AUTUMN_COLORS, rand) : pick(AUTUMN_GREENS, rand)).clone().offsetHSL((rand() - 0.5) * 0.02, 0, (rand() - 0.5) * 0.05);
          inst.sx *= 1.05; inst.sz *= 1.05;
          add('maple', inst);
        } else if (w.meadow > 0.5 && forest < 0.55 && rand() < 0.5) {
          // берёзы по краям луга
          inst.color = pick(BIRCH_GREENS, rand).clone().offsetHSL((rand() - 0.5) * 0.02, 0, (rand() - 0.5) * 0.05);
          inst.sx *= 0.95; inst.sz *= 0.95;
          add('birch', inst);
        } else {
          let c;
          if (rand() < w.rain) c = pick(RAIN_GREENS, rand);
          else if (w.canyon > 0.3) c = pick(CANYON_GREENS, rand);
          else c = pick(MEADOW_GREENS, rand);
          inst.color = c.clone().offsetHSL((rand() - 0.5) * 0.02, 0, (rand() - 0.5) * 0.06);
          add('broad', inst);
        }
        continue;
      }

      // --- кусты и камни в просветах (у опушек гуще) ---
      const pBush = (0.1 + forest * 0.16) * (1 - w.winter * 0.6) * (1 - w.canyon * 0.6);
      if (rand() < pBush && r > 14) {
        const s = 0.7 + rand() * 0.8;
        const inst = { x, y: y - 0.05, z, ry: rand() * TAU, sx: s * 1.15, sy: s * (0.85 + rand() * 0.25), sz: s * 1.15 };
        if (w.winter > 0.5) {
          inst.color = new THREE.Color(0.75, 0.82, 0.78);
          add('bushSnow', inst);
        } else {
          let c;
          if (rand() < w.autumn * 0.8) c = pick(AUTUMN_COLORS, rand).clone().multiplyScalar(0.92);
          else c = (w.rain > 0.5 ? RAIN_GREENS : BUSH_GREENS)[Math.floor(rand() * 3)].clone();
          inst.color = c.offsetHSL((rand() - 0.5) * 0.02, 0, (rand() - 0.5) * 0.05);
          add(rand() < 0.28 && w.autumn < 0.5 ? 'berry' : 'bush', inst);
        }
      } else if (rand() < 0.05 && r > 12) {
        const s = 0.35 + rand() * 0.9;
        const v = 0.62 + rand() * 0.28;
        const c = w.canyon > 0.3 ? new THREE.Color().setHSL(0.07, 0.55, 0.45 + rand() * 0.15) : new THREE.Color(v, v * 1.02, v * 1.06);
        const mossy = w.canyon < 0.3 && w.winter < 0.5 && rand() < 0.5;
        (mossy ? out.rockMoss : out.rock).push({ x, y: y - s * 0.2, z, ry: rand() * TAU, sx: s * 1.2, sy: s * 0.75, sz: s, color: c, cr: s * 0.7 });
      }
    }
  }
  return out;
}

function chunked(list, size) {
  const groups = new Map();
  for (const it of list) {
    const key = `${Math.floor(it.x / size)},${Math.floor(it.z / size)}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(it);
  }
  return groups.values();
}

// Камни всех видов: склеены по чанкам в статичные меши.
function buildRocks(lists, group) {
  const mat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.92, metalness: 0 });
  const tpls = {
    rock: compactTemplate(buildBoulder(3, false)),
    rockMoss: compactTemplate(buildBoulder(4, false, true)),
    boulderA: compactTemplate(buildBoulder(11, true)),
    boulderB: compactTemplate(buildBoulder(12, true)),
  };
  const all = [];
  for (const it of lists.rock) all.push({ ...it, kind: 'rock' });
  for (const it of lists.rockMoss) all.push({ ...it, kind: 'rockMoss' });
  lists.boulder.forEach((it, i) => all.push({ ...it, kind: i % 2 ? 'boulderB' : 'boulderA' }));
  const m = new THREE.Matrix4(), q = new THREE.Quaternion(), p = new THREE.Vector3(), s = new THREE.Vector3();
  const up = new THREE.Vector3(0, 1, 0);
  for (const items of chunked(all, ROCK_CHUNK)) {
    const list = items.map((it) => ({
      tpl: tpls[it.kind],
      matrix: m.compose(p.set(it.x, it.y, it.z), q.setFromAxisAngle(up, it.ry), s.set(it.sx, it.sy, it.sz)).clone(),
      color: it.color,
    }));
    const mesh = new THREE.Mesh(mergeTemplates(list), mat);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.matrixAutoUpdate = false;
    mesh.name = 'rocks';
    group.add(mesh);
  }
}

// ---------- система деревьев с двумя LOD ----------

const TYPES = ['fir', 'firSnow', 'broad', 'maple', 'birch', 'bare', 'bush', 'berry', 'bushSnow'];
const SWAY = {
  fir: [0.007, 2.0], firSnow: [0.005, 2.0], broad: [0.012, 2.2], maple: [0.012, 2.2], birch: [0.016, 2.4],
  bare: [0.01, 2.0], bush: [0.035, 0.2], berry: [0.035, 0.2], bushSnow: [0.02, 0.2],
};

function detailedGeometry(type, lod) {
  switch (type) {
    case 'fir': return firDetailed({ seed: 3, lod });
    case 'firSnow': return firDetailed({ seed: 3, snow: true, lod });
    case 'broad': return broadDetailed({ seed: 5, kind: 'broad', lod });
    case 'maple': return broadDetailed({ seed: 7, kind: 'maple', lod });
    case 'birch': return broadDetailed({ seed: 9, kind: 'birch', lod });
    case 'bare': return bareDetailed({ seed: 11, lod });
    case 'bush': return bushDetailed({ seed: 13, lod });
    case 'berry': return bushDetailed({ seed: 15, berries: true, lod });
    default: return bushDetailed({ seed: 17, snow: true, lod });
  }
}

function farGeometry(type) {
  switch (type) {
    case 'fir': return firFar({ seed: 3 });
    case 'firSnow': return firFar({ seed: 3, snow: true });
    case 'broad': return broadFar({ seed: 5, kind: 'broad' });
    case 'maple': return broadFar({ seed: 7, kind: 'maple' });
    case 'birch': return broadFar({ seed: 9, kind: 'birch' });
    case 'bare': return bareFar({ seed: 11 });
    case 'bushSnow': return bushFar({ seed: 17, snow: true });
    default: return bushFar({ seed: 13 });
  }
}

class TreeLOD {
  constructor(trees, group, { nearR = 70, detail = 1, shadows = true }) {
    this.nearR = nearR;
    this.shadows = shadows;
    this.center = new THREE.Vector2(1e9, 1e9);
    this.frustum = new THREE.Frustum();
    this._pm = new THREE.Matrix4();
    this.sunDir = new THREE.Vector3(-0.55, 0.62, 0.5).normalize();
    this.byType = {};
    this.grid = new Map();
    const m = new THREE.Matrix4(), q = new THREE.Quaternion(), p = new THREE.Vector3(), s = new THREE.Vector3();
    const up = new THREE.Vector3(0, 1, 0);

    for (const type of TYPES) {
      const list = trees.filter((t) => t.type === type);
      if (!list.length) continue;
      const n = list.length;
      const mats = new Float32Array(n * 16), cols = new Float32Array(n * 3);
      const b = TYPE_BOUNDS[type];
      const sph = new Float32Array(n * 4); // центр (xyz) + радиус
      list.forEach((it, i) => {
        q.setFromAxisAngle(up, it.ry);
        m.compose(p.set(it.x, it.y, it.z), q, s.set(it.sx, it.sy, it.sz));
        m.toArray(mats, i * 16);
        cols[i * 3] = it.color.r; cols[i * 3 + 1] = it.color.g; cols[i * 3 + 2] = it.color.b;
        sph[i * 4] = it.x; sph[i * 4 + 1] = it.y + b.cy * it.sy; sph[i * 4 + 2] = it.z;
        sph[i * 4 + 3] = b.r * Math.max(it.sx, it.sy);
        const key = `${Math.floor(it.x / GRID)},${Math.floor(it.z / GRID)}`;
        let cell = this.grid.get(key);
        if (!cell) { cell = []; this.grid.set(key, cell); }
        cell.push(type, i);
      });
      const [sw, sy0] = SWAY[type];
      const nearMat = plantMaterial({ sway: sw, swayY0: sy0, key: `tree-${type}` });
      const near = new THREE.InstancedMesh(detailedGeometry(type, detail), nearMat, n);
      near.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
      near.setColorAt(0, new THREE.Color(1, 1, 1));
      near.instanceColor.setUsage(THREE.DynamicDrawUsage);
      near.count = 0;
      near.frustumCulled = false; // отсекаем сами, поштучно
      near.castShadow = true;
      near.receiveShadow = true;
      near.name = `trees-${type}`;
      near.matrixAutoUpdate = false;
      group.add(near);

      this.byType[type] = { list, mats, cols, sph, near, cand: new Int32Array(n), nc: 0, height: b.cy * 2 };
    }

    // дальний LOD: все типы чанка склеены в одну статичную геометрию (1 draw call на чанк)
    const farMat = plantMaterial({ sway: 0.006, swayY0: 2.0, far: true, occlusion: false, textured: false, anchored: true, key: 'treefar' });
    const tpls = {};
    for (const type of Object.keys(this.byType)) tpls[type] = compactTemplate(farGeometry(type));
    for (const items of chunked(trees, FAR_CHUNK)) {
      const list = items.map((it) => ({
        tpl: tpls[it.type],
        matrix: m.compose(p.set(it.x, it.y, it.z), q.setFromAxisAngle(up, it.ry), s.set(it.sx, it.sy, it.sz)).clone(),
        color: it.color,
        anchor: [it.x, it.y, it.z],
      }));
      const mesh = new THREE.Mesh(mergeTemplates(list, { anchors: true }), farMat);
      mesh.castShadow = false;
      mesh.receiveShadow = false;
      mesh.matrixAutoUpdate = false;
      mesh.name = 'trees-far';
      group.add(mesh);
    }
  }

  // Пересобрать списки кандидатов в ближний LOD вокруг (x, z).
  _recenter(x, z) {
    this.center.set(x, z);
    NATURE.uLod.value.set(x, z, this.nearR);
    for (const t of Object.values(this.byType)) t.nc = 0;
    const R = this.nearR, R2 = R * R;
    const c0x = Math.floor((x - R) / GRID), c1x = Math.floor((x + R) / GRID);
    const c0z = Math.floor((z - R) / GRID), c1z = Math.floor((z + R) / GRID);
    for (let cz = c0z; cz <= c1z; cz++) {
      for (let cx = c0x; cx <= c1x; cx++) {
        const cell = this.grid.get(`${cx},${cz}`);
        if (!cell) continue;
        for (let k = 0; k < cell.length; k += 2) {
          const t = this.byType[cell[k]], i = cell[k + 1];
          // ровно то же условие, что в шейдере дальнего LOD (distance(ip.xz, uLod.xy) < uLod.z)
          const ix = t.mats[i * 16 + 12] - x, iz = t.mats[i * 16 + 14] - z;
          if (ix * ix + iz * iz < R2) t.cand[t.nc++] = i;
        }
      }
    }
  }

  update(camera, force = false) {
    const cp = camera.position;
    if (force || (cp.x - this.center.x) ** 2 + (cp.z - this.center.y) ** 2 > 9) this._recenter(cp.x, cp.z);
    camera.updateMatrixWorld();
    this._pm.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse);
    const planes = this.frustum.setFromProjectionMatrix(this._pm).planes;
    const sd = this.sunDir;
    const k = Math.hypot(sd.x, sd.z) / Math.max(0.2, sd.y);
    const shx = -sd.x / Math.hypot(sd.x, sd.z), shz = -sd.z / Math.hypot(sd.x, sd.z);
    const inside = (x, y, z, r) => {
      for (let p = 0; p < 6; p++) {
        const pl = planes[p];
        if (pl.normal.x * x + pl.normal.y * y + pl.normal.z * z + pl.constant < -r) return false;
      }
      return true;
    };
    for (const t of Object.values(this.byType)) {
      const { near, mats, cols, sph, cand } = t;
      const dstM = near.instanceMatrix.array, dstC = near.instanceColor.array;
      let n = 0;
      for (let j = 0; j < t.nc; j++) {
        const i = cand[j];
        const x = sph[i * 4], y = sph[i * 4 + 1], z = sph[i * 4 + 2], r = sph[i * 4 + 3];
        let vis = inside(x, y, z, r);
        if (!vis && this.shadows) {
          // тень дерева может упасть в кадр, даже если само оно за кадром
          const L = (y - mats[i * 16 + 13]) * 2 * k * 0.5;
          vis = inside(x + shx * L, y - (y - mats[i * 16 + 13]), z + shz * L, r + L);
        }
        if (!vis) continue;
        dstM.set(mats.subarray(i * 16, i * 16 + 16), n * 16);
        dstC[n * 3] = cols[i * 3]; dstC[n * 3 + 1] = cols[i * 3 + 1]; dstC[n * 3 + 2] = cols[i * 3 + 2];
        n++;
      }
      near.count = n;
      near.visible = n > 0;
      if (n > 0) {
        near.instanceMatrix.clearUpdateRanges();
        near.instanceMatrix.addUpdateRange(0, n * 16);
        near.instanceMatrix.needsUpdate = true;
        near.instanceColor.clearUpdateRanges();
        near.instanceColor.addUpdateRange(0, n * 3);
        near.instanceColor.needsUpdate = true;
      }
    }
  }
}

export function buildVegetation({ collisions, density = 1, nearR = 70, detail = 1, shadows = true }) {
  const group = new THREE.Group();
  group.name = 'vegetation';
  const data = scatter();

  // на слабых устройствах прореживаем леса
  if (density < 1) {
    const rand = mulberry32(5);
    data.trees = data.trees.filter(() => rand() < density);
    for (const k of ['rock', 'rockMoss']) data[k] = data[k].filter(() => rand() < density);
  }

  const lod = new TreeLOD(data.trees, group, { nearR, detail, shadows });

  buildRocks(data, group);

  // коллизии: стволы и камни
  const trunkR = { fir: 0.4, firSnow: 0.4, broad: 0.45, maple: 0.45, birch: 0.3, bare: 0.35 };
  for (const t of data.trees) if (trunkR[t.type]) collisions.addCircle(t.x, t.z, trunkR[t.type] * t.sx);
  for (const t of data.rock) collisions.addCircle(t.x, t.z, t.cr);
  for (const t of data.rockMoss) collisions.addCircle(t.x, t.z, t.cr);
  for (const t of data.boulder) collisions.addCircle(t.x, t.z, t.cr);

  return {
    group,
    data,
    lod,
    update(camera) { lod.update(camera); },
    prefill(camera) { lod.update(camera, true); },
  };
}

// Одиночное "героическое" дерево для структур (лиственное, увеличенное): подробная крона с карточками листвы.
export function buildHeroTree(scale = 2.4, tint = '#6fbb42') {
  const g = new THREE.Group();
  const geo = broadDetailed({ seed: 77, kind: 'broad', lod: 1, hero: true });
  // оттенок кроны запекаем в цвет вершин (у одиночного меша нет цвета экземпляра)
  const t = new THREE.Color(tint);
  const C = geo.getAttribute('color'), A = geo.getAttribute('aTint');
  for (let i = 0; i < C.count; i++) {
    const k = A.getX(i);
    C.setXYZ(i, C.getX(i) * (1 + (t.r - 1) * k), C.getY(i) * (1 + (t.g - 1) * k), C.getZ(i) * (1 + (t.b - 1) * k));
  }
  const mesh = new THREE.Mesh(geo, plantMaterial({ sway: 0.006, swayY0: 2.6, key: 'hero' }));
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  mesh.name = 'hero-tree';
  g.add(mesh);
  g.scale.setScalar(scale);
  return g;
}
