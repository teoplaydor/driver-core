// Деревья, кусты и камни. Раскладка детерминированная (одна и та же у всех игроков),
// объекты собираются в InstancedMesh по чанкам, чтобы frustum culling работал и для теней.

import * as THREE from 'three';
import { mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js';
import { Noise2D, mulberry32, smoothstep, lerp, TAU } from '../util/math.js';
import {
  heightAt, pathDist, slopeAt, zoneWeights, SITES, WATER_Y, WORLD_RADIUS,
} from './terrain-data.js';
import {
  mergeAll, colorize, gradientY, jitter, firTier, grayGradientY,
} from './geo.js';

const CHUNK = 100;
const col = (h) => new THREE.Color(h);

// ---------- геометрия ----------

// Параметры яруса ёлки: общие для хвои и снежных шапок, чтобы шапки точно ложились сверху.
const FIR_TIERS = 5;
function firTierParams(i) {
  const t = i / (FIR_TIERS - 1);
  return { t, radius: 2.9 * (1 - t * 0.7), height: 2.7 - t * 0.7, y: 1.3 + i * 1.5, spikes: 16 - i * 2 };
}

function buildFir(cDark, cLight) {
  const tiers = [];
  for (let i = 0; i < FIR_TIERS; i++) {
    const p = firTierParams(i);
    tiers.push(firTier({
      radius: p.radius, height: p.height, y: p.y, spikes: p.spikes, droop: 0.55,
      rot: i * 0.55, seed: 10 + i, cDark, cLight, bright: 0.84 + p.t * 0.24,
    }));
  }
  return mergeAll(tiers);
}

function buildSnowCaps() {
  const white = col('#f4f9ff'), shade = col('#cfe0f2');
  const caps = [];
  for (let i = 0; i < FIR_TIERS; i++) {
    const p = firTierParams(i);
    caps.push(firTier({
      radius: p.radius * 0.66, height: p.height * 0.62, y: p.y + p.height * 0.42 + 0.06, droop: 0.14,
      spikes: Math.max(7, p.spikes - 1), rot: i * 0.55 + 0.2, seed: 40 + i, cDark: white, cLight: shade, bright: 1,
    }));
  }
  return mergeAll(caps);
}

function buildTrunk(h, r0, r1, c0, c1) {
  const g = new THREE.CylinderGeometry(r1, r0, h, 7, 1);
  g.translate(0, h / 2, 0);
  return gradientY(g, c0, c1, 0, h);
}

function buildRoundCrown() {
  const blobs = [
    [0, 3.7, 0, 1.95, 1], [1.15, 3.1, 0.5, 1.4, 0], [-1.05, 3.3, -0.6, 1.45, 0],
    [0.2, 4.9, -0.2, 1.3, 0], [0.5, 2.7, -1.1, 1.15, 0],
  ];
  const parts = blobs.map(([x, y, z, r, d], i) => {
    const g = new THREE.IcosahedronGeometry(r, d);
    g.translate(x, y, z);
    jitter(g, r * 0.1, 7 + i);
    return grayGradientY(g, 0.58, 1.0, y - r, y + r);
  });
  return mergeAll(parts);
}

function buildBush() {
  const parts = [[0, 0.5, 0, 0.75], [0.55, 0.38, 0.2, 0.5], [-0.5, 0.4, -0.15, 0.55]].map(([x, y, z, r], i) => {
    const g = new THREE.IcosahedronGeometry(r, 1);
    g.translate(x, y, z);
    jitter(g, r * 0.1, 21 + i);
    return grayGradientY(g, 0.55, 1.0, y - r, y + r);
  });
  return mergeAll(parts);
}

// Гладкие округлые валуны: индексированная геометрия + пересчёт нормалей.
function buildBoulder(seed, bands) {
  let g = new THREE.IcosahedronGeometry(1, 2);
  g.deleteAttribute('normal');
  g.deleteAttribute('uv');
  g = mergeVertices(g, 1e-4);
  jitter(g, 0.14, seed);
  g.computeVertexNormals();
  colorize(g, (x, y, z, out) => {
    const v = bands ? 0.72 + 0.28 * (Math.sin(y * 6.2 + x * 0.8) * 0.5 + 0.5) : 0.8 + 0.2 * (y * 0.5 + 0.5);
    out.setRGB(v, v, v);
  });
  return g;
}

// Лёгкое покачивание кроны на ветру (вершинный шейдер, без CPU).
function addSway(mat, uTime, strength) {
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uTime = uTime;
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nuniform float uTime;')
      .replace('#include <begin_vertex>', `#include <begin_vertex>
      #ifdef USE_INSTANCING
        vec3 ip = vec3(instanceMatrix[3][0], instanceMatrix[3][1], instanceMatrix[3][2]);
      #else
        vec3 ip = vec3(0.0);
      #endif
      float sw = max(0.0, transformed.y - 1.2) * ${strength.toFixed(3)};
      transformed.x += sin(uTime * 1.1 + ip.x * 0.35 + ip.z * 0.2) * sw;
      transformed.z += cos(uTime * 0.9 + ip.z * 0.3 + ip.x * 0.15) * sw;`);
  };
}

// ---------- раскладка ----------

const MEADOW_GREENS = ['#5fae3c', '#74bd45', '#4f9f3b', '#82c44c'].map(col);
const AUTUMN_COLORS = ['#e48a2a', '#cb4a2b', '#ebb53a', '#bb6b2a', '#dc742e'].map(col);
const RAIN_GREENS = ['#3f7c4b', '#306b46', '#4b8651'].map(col);
const CANYON_GREENS = ['#4f8a46', '#5e9a48'].map(col);

function nearSite(x, z, pad) {
  for (const s of SITES) {
    if (Math.hypot(x - s.x, z - s.z) < s.r + pad) return true;
  }
  return false;
}

function scatter() {
  const rand = mulberry32(1337);
  const noise = new Noise2D(31337);
  const out = { fir: [], firSnow: [], round: [], bush: [], rock: [], boulder: [] };
  const w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
  const STEP = 5;
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
      let dens = smoothstep(0.36, 0.66, f) * 0.95 + 0.035;
      dens *= smoothstep(30, 64, r);
      dens *= 1 - w.canyon * 0.82;
      dens *= slope > 0.85 ? 0.15 : 1;
      if (rand() < dens * 0.85) {
        const pFir = 0.62 + 0.38 * w.winter + 0.1 * w.rain - 0.4 * w.autumn - 0.35 * w.canyon;
        const s = 0.75 + rand() * 0.65;
        const inst = { x, y: y - 0.1, z, ry: rand() * TAU, sx: s * (0.9 + rand() * 0.2), sy: s, sz: s * (0.9 + rand() * 0.2), cr: 0.4 * s };
        if (rand() < pFir) {
          const v = 0.86 + rand() * 0.3;
          inst.color = new THREE.Color(v, v, v);
          if (w.winter > 0.5) inst.color.multiply(new THREE.Color(0.85, 0.97, 1.08));
          out.fir.push(inst);
          if (rand() < w.winter * 1.15 - 0.1) out.firSnow.push(inst);
        } else {
          let c;
          if (rand() < w.autumn) c = AUTUMN_COLORS[Math.floor(rand() * AUTUMN_COLORS.length)];
          else if (rand() < w.rain) c = RAIN_GREENS[Math.floor(rand() * RAIN_GREENS.length)];
          else if (w.canyon > 0.3) c = CANYON_GREENS[Math.floor(rand() * CANYON_GREENS.length)];
          else c = MEADOW_GREENS[Math.floor(rand() * MEADOW_GREENS.length)];
          inst.color = c.clone().offsetHSL((rand() - 0.5) * 0.02, 0, (rand() - 0.5) * 0.06);
          inst.cr = 0.45 * s;
          out.round.push(inst);
        }
        continue;
      }

      // --- кусты и камни в просветах ---
      if (rand() < 0.1 * (1 - w.winter * 0.8) * (1 - w.canyon * 0.6) && r > 14) {
        const s = 0.7 + rand() * 0.9;
        let c;
        if (rand() < w.autumn * 0.8) c = AUTUMN_COLORS[Math.floor(rand() * AUTUMN_COLORS.length)];
        else c = (w.rain > 0.5 ? RAIN_GREENS : MEADOW_GREENS)[Math.floor(rand() * 3)];
        out.bush.push({ x, y: y - 0.05, z, ry: rand() * TAU, sx: s * 1.2, sy: s * 0.9, sz: s * 1.2, color: c.clone().multiplyScalar(0.85), cr: 0 });
      } else if (rand() < 0.05 && r > 12) {
        const s = 0.35 + rand() * 0.9;
        const v = 0.55 + rand() * 0.3;
        const c = w.canyon > 0.3 ? new THREE.Color().setHSL(0.07, 0.55, 0.45 + rand() * 0.15) : new THREE.Color(v, v * 1.02, v * 1.06);
        out.rock.push({ x, y: y - s * 0.2, z, ry: rand() * TAU, sx: s * 1.2, sy: s * 0.75, sz: s, color: c, cr: s * 0.7 });
      }
    }
  }
  return out;
}

// Разложить экземпляры по чанкам и собрать InstancedMesh.
function makeChunks(list, geometry, material, { cast = true, receive = true } = {}) {
  const groups = new Map();
  for (const it of list) {
    const key = `${Math.floor(it.x / CHUNK)},${Math.floor(it.z / CHUNK)}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(it);
  }
  const meshes = [];
  const m = new THREE.Matrix4(), q = new THREE.Quaternion(), p = new THREE.Vector3(), s = new THREE.Vector3();
  const up = new THREE.Vector3(0, 1, 0);
  for (const items of groups.values()) {
    const mesh = new THREE.InstancedMesh(geometry, material, items.length);
    items.forEach((it, i) => {
      q.setFromAxisAngle(up, it.ry);
      m.compose(p.set(it.x, it.y, it.z), q, s.set(it.sx, it.sy, it.sz));
      mesh.setMatrixAt(i, m);
      if (it.color) mesh.setColorAt(i, it.color);
    });
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    mesh.instanceMatrix.setUsage(THREE.StaticDrawUsage);
    mesh.castShadow = cast;
    mesh.receiveShadow = receive;
    mesh.computeBoundingSphere();
    mesh.matrixAutoUpdate = false;
    meshes.push(mesh);
  }
  return meshes;
}

export function buildVegetation({ collisions, uTime, density = 1 }) {
  const group = new THREE.Group();
  group.name = 'vegetation';
  const data = scatter();

  // на слабых устройствах прореживаем леса
  if (density < 1) {
    const rand = mulberry32(5);
    for (const k of ['fir', 'round', 'bush', 'rock']) data[k] = data[k].filter(() => rand() < density);
    data.firSnow = data.firSnow.filter((i) => data.fir.includes(i));
  }

  const foliage = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.9, metalness: 0, side: THREE.DoubleSide });
  const solidMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.95, metalness: 0 });
  const rockMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.95, metalness: 0 });
  addSway(foliage, uTime, 0.012);

  const firGeo = buildFir(col('#2a6a3a'), col('#9ccc4e'));
  const snowGeo = buildSnowCaps();
  const roundGeo = buildRoundCrown();
  const bushGeo = buildBush();
  const trunkFir = buildTrunk(3.4, 0.32, 0.12, '#5a3d28', '#7a5535');
  const trunkRound = buildTrunk(3.0, 0.36, 0.18, '#6a4a30', '#8a6440');
  const rockGeo = buildBoulder(3, false);
  const boulderGeos = [buildBoulder(11, true), buildBoulder(12, true)];

  const add = (list, geo, mat, opts) => makeChunks(list, geo, mat, opts).forEach((m) => group.add(m));
  add(data.fir, firGeo, foliage);
  add(data.fir, trunkFir, solidMat, { receive: false });
  add(data.firSnow.map((i) => ({ ...i, color: new THREE.Color(1, 1, 1) })), snowGeo, foliage, { cast: false });
  add(data.round, roundGeo, foliage);
  add(data.round, trunkRound, solidMat, { receive: false });
  add(data.bush, bushGeo, foliage, { cast: false });
  add(data.rock, rockGeo, rockMat);
  add(data.boulder.filter((_, i) => i % 2 === 0), boulderGeos[0], rockMat);
  add(data.boulder.filter((_, i) => i % 2 === 1), boulderGeos[1], rockMat);

  // коллизии: стволы и камни
  for (const t of data.fir) collisions.addCircle(t.x, t.z, 0.4 * t.sx);
  for (const t of data.round) collisions.addCircle(t.x, t.z, 0.45 * t.sx);
  for (const t of data.rock) collisions.addCircle(t.x, t.z, t.cr);
  for (const t of data.boulder) collisions.addCircle(t.x, t.z, t.cr);

  return { group, data };
}

// Одиночное "героическое" дерево для структур (лиственное, увеличенное).
export function buildHeroTree(scale = 2.4, tint = '#6fbb42') {
  const g = new THREE.Group();
  const mat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.9, side: THREE.DoubleSide });
  const crown = new THREE.Mesh(buildRoundCrown(), mat);
  crown.material = mat.clone();
  crown.material.color = new THREE.Color(tint);
  crown.castShadow = true; crown.receiveShadow = true;
  const trunk = new THREE.Mesh(buildTrunk(3.0, 0.36, 0.18, '#6a4a30', '#8a6440'), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.95 }));
  trunk.castShadow = true;
  g.add(trunk, crown);
  g.scale.setScalar(scale);
  return g;
}
