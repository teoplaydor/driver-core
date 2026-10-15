// Трава и цветы чанками вокруг игрока: рядом густо, вдалеке не рисуется вовсе.
// Чанки пересоздаются из детерминированного ГСЧ, поэтому у всех трава растёт одинаково.

import * as THREE from 'three';
import { mulberry32, hash2, smoothstep, TAU, Noise2D } from '../util/math.js';
import { heightAt, pathDist, zoneWeights, slopeAt, isInterior, WATER_Y, SITES } from './terrain-data.js';

const CH = 16; // размер чанка, м
const BLADE_H = 0.5;

const noise = new Noise2D(909);

function buildTuft() {
  const pos = [], nor = [], col = [];
  const rand = mulberry32(3);
  const base = new THREE.Color('#3f8a32'), tip = new THREE.Color('#c4dc5e');
  const blades = 6;
  for (let b = 0; b < blades; b++) {
    const a = (b / blades) * TAU + rand() * 0.8;
    const lean = 0.14 + rand() * 0.22, h = BLADE_H * (0.62 + rand() * 0.45), w = 0.038 + rand() * 0.02;
    const cx = Math.cos(a) * 0.06, cz = Math.sin(a) * 0.06;
    const dx = Math.cos(a), dz = Math.sin(a);
    const px = -dz, pz = dx; // поперёк лезвия
    pos.push(
      cx - px * w, 0, cz - pz * w,
      cx + px * w, 0, cz + pz * w,
      cx + dx * lean * h, h, cz + dz * lean * h,
    );
    for (let i = 0; i < 3; i++) nor.push(0, 1, 0);
    col.push(base.r, base.g, base.b, base.r, base.g, base.b, tip.r, tip.g, tip.b);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  return g;
}

function buildFlower() {
  const stem = new THREE.BoxGeometry(0.018, 0.14, 0.018);
  stem.translate(0, 0.07, 0);
  const head = new THREE.IcosahedronGeometry(0.052, 0);
  head.scale(1, 0.5, 1);
  head.translate(0, 0.15, 0);
  const parts = [stem, head].map((g, i) => {
    const gg = g.index ? g.toNonIndexed() : g;
    gg.deleteAttribute('uv');
    const n = gg.getAttribute('position').count;
    const c = new Float32Array(n * 3);
    const k = i === 0 ? [0.55, 0.85, 0.45] : [1, 1, 1];
    for (let v = 0; v < n; v++) { c[v * 3] = k[0]; c[v * 3 + 1] = k[1]; c[v * 3 + 2] = k[2]; }
    gg.setAttribute('color', new THREE.BufferAttribute(c, 3));
    gg.computeVertexNormals();
    return gg;
  });
  const total = parts[0].getAttribute('position').count + parts[1].getAttribute('position').count;
  const pos = new Float32Array(total * 3), nor = new Float32Array(total * 3), col = new Float32Array(total * 3);
  let o = 0;
  for (const p of parts) {
    pos.set(p.getAttribute('position').array, o * 3);
    nor.set(p.getAttribute('normal').array, o * 3);
    col.set(p.getAttribute('color').array, o * 3);
    o += p.getAttribute('position').count;
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return g;
}

function grassMaterial(uTime) {
  const mat = new THREE.MeshLambertMaterial({ vertexColors: true, side: THREE.DoubleSide });
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
      float hf = clamp(position.y / ${BLADE_H.toFixed(2)}, 0.0, 1.0);
      float gust = sin(uTime * 1.7 + ip.x * 0.45 + ip.z * 0.3) * 0.6 + sin(uTime * 3.1 + ip.x * 1.3) * 0.25;
      transformed.x += gust * 0.09 * hf * hf;
      transformed.z += cos(uTime * 1.4 + ip.z * 0.4) * 0.06 * hf * hf;`);
    // трава освещается как "земля": обе стороны лезвия одинаково, без затемнения изнанки
    shader.fragmentShader = shader.fragmentShader.replace(
      '#include <normal_fragment_begin>',
      'float faceDirection = 1.0; vec3 normal = normalize( vNormal ); vec3 nonPerturbedNormal = normal;',
    );
  };
  return mat;
}

const FLOWER_COLORS = ['#ffffff', '#ffd94a', '#ff8ab5', '#b894ff', '#ff6a55'].map((c) => new THREE.Color(c));

export class GrassField {
  constructor(scene, { radius = 3, perChunk = 1000, flowersPerChunk = 36, uTime }) {
    this.scene = scene;
    this.radius = radius;
    this.perChunk = perChunk;
    this.flowersPerChunk = flowersPerChunk;
    this.tuftGeo = buildTuft();
    this.flowerGeo = buildFlower();
    this.grassMat = grassMaterial(uTime);
    this.flowerMat = new THREE.MeshLambertMaterial({ vertexColors: true });
    this.active = new Map();
    this.pool = [];
    this._w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
    this._m = new THREE.Matrix4();
    this._q = new THREE.Quaternion();
    this._p = new THREE.Vector3();
    this._s = new THREE.Vector3();
    this._c = new THREE.Color();
    this._up = new THREE.Vector3(0, 1, 0);
    this.group = new THREE.Group();
    this.group.name = 'grass';
    scene.add(this.group);
  }

  setRadius(r) { this.radius = r; }

  _acquire() {
    let e = this.pool.pop();
    if (!e) {
      const grass = new THREE.InstancedMesh(this.tuftGeo, this.grassMat, this.perChunk);
      grass.setColorAt(0, this._c.set('#ffffff'));
      grass.receiveShadow = true;
      grass.matrixAutoUpdate = false;
      const flowers = new THREE.InstancedMesh(this.flowerGeo, this.flowerMat, this.flowersPerChunk);
      flowers.setColorAt(0, this._c.set('#ffffff'));
      flowers.matrixAutoUpdate = false;
      e = { grass, flowers, cx: 0, cz: 0 };
    }
    this.group.add(e.grass, e.flowers);
    return e;
  }

  _release(key) {
    const e = this.active.get(key);
    if (!e) return;
    this.group.remove(e.grass, e.flowers);
    this.active.delete(key);
    this.pool.push(e);
  }

  _fill(e, cx, cz) {
    e.cx = cx; e.cz = cz;
    const rand = mulberry32(Math.floor(hash2(cx, cz, 17) * 4294967296));
    const x0 = cx * CH, z0 = cz * CH;
    const w = this._w, m = this._m, q = this._q, p = this._p, s = this._s, c = this._c, up = this._up;
    let n = 0;
    const indoors = isInterior(x0);
    for (let i = 0; i < (indoors ? 0 : this.perChunk); i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      const y = heightAt(x, z);
      if (y < WATER_Y + 0.15) continue;
      const pd = pathDist(x, z);
      if (pd < 1.5) continue;
      if (pd < 3 && rand() < ((3 - pd) / 1.5) * 0.7) continue;
      zoneWeights(x, z, w);
      if (w.winter > 0.55) continue; // под снегом травы нет
      const dens = w.meadow + w.autumn * 0.7 + w.rain * 0.95 + w.canyon * 0.22;
      if (rand() > dens) continue;
      if (x * x + z * z < 72) continue; // мощёная площадка
      let blocked = false;
      for (const st of SITES) {
        if (st.id !== 'hub' && Math.hypot(x - st.x, z - st.z) < st.r * 0.8) { blocked = true; break; }
      }
      if (blocked || slopeAt(x, z) > 0.9) continue;
      const sc = 0.75 + rand() * 0.6;
      q.setFromAxisAngle(up, rand() * TAU);
      m.compose(p.set(x, y - 0.02, z), q, s.set(sc, sc * (0.85 + rand() * 0.35), sc));
      e.grass.setMatrixAt(n, m);
      // оттенок: луг почти не меняем, осень/каньон желтеют, дождь темнее
      const v = 0.92 + rand() * 0.2;
      c.setRGB(
        v * (1 + w.autumn * 0.3 + w.canyon * 0.35 - w.rain * 0.3),
        v * (1 - w.autumn * 0.05 + w.canyon * 0.05 - w.rain * 0.12),
        v * (1 - w.autumn * 0.45 - w.canyon * 0.4 - w.rain * 0.22),
      );
      e.grass.setColorAt(n, c);
      n++;
    }
    e.grass.count = n;
    e.grass.instanceMatrix.needsUpdate = true;
    e.grass.instanceColor.needsUpdate = true;

    let f = 0;
    for (let i = 0; i < (indoors ? 0 : this.flowersPerChunk * 3) && f < this.flowersPerChunk; i++) {
      const x = x0 + rand() * CH, z = z0 + rand() * CH;
      const y = heightAt(x, z);
      if (y < WATER_Y + 0.35 || pathDist(x, z) < 2) continue;
      zoneWeights(x, z, w);
      if (w.meadow < 0.5 || x * x + z * z < 110) continue;
      const patch = noise.get(x * 0.07, z * 0.07);
      if (patch < 0.12 || slopeAt(x, z) > 0.6) continue;
      const sc = 0.8 + rand() * 0.6;
      q.setFromAxisAngle(up, rand() * TAU);
      m.compose(p.set(x, y, z), q, s.set(sc, sc, sc));
      e.flowers.setMatrixAt(f, m);
      e.flowers.setColorAt(f, FLOWER_COLORS[Math.floor(((patch * 7.3 + 5) % 1 + 1) % 1 * FLOWER_COLORS.length)]);
      f++;
    }
    e.flowers.count = f;
    e.flowers.instanceMatrix.needsUpdate = true;
    e.flowers.instanceColor.needsUpdate = true;

    const cxm = x0 + CH / 2, czm = z0 + CH / 2;
    const bs = new THREE.Sphere(new THREE.Vector3(cxm, 8, czm), CH * 0.9 + 12);
    e.grass.boundingSphere = bs;
    e.flowers.boundingSphere = bs;
  }

  // Строит не больше `budget` недостающих чанков за вызов (чтобы не было рывков).
  update(px, pz, budget = 1) {
    const cx = Math.floor(px / CH), cz = Math.floor(pz / CH);
    const R = this.radius;
    const maxD = R * CH + CH * 0.5;
    for (const [key, e] of this.active) {
      const dx = (e.cx + 0.5) * CH - px, dz = (e.cz + 0.5) * CH - pz;
      if (Math.hypot(dx, dz) > maxD + CH) this._release(key);
    }
    const need = [];
    for (let dz = -R - 1; dz <= R + 1; dz++) {
      for (let dx = -R - 1; dx <= R + 1; dx++) {
        const ix = cx + dx, iz = cz + dz;
        const key = `${ix},${iz}`;
        if (this.active.has(key)) continue;
        const d = Math.hypot((ix + 0.5) * CH - px, (iz + 0.5) * CH - pz);
        if (d <= maxD) need.push({ key, ix, iz, d });
      }
    }
    need.sort((a, b) => a.d - b.d);
    for (let i = 0; i < Math.min(budget, need.length); i++) {
      const { key, ix, iz } = need[i];
      const e = this._acquire();
      this._fill(e, ix, iz);
      this.active.set(key, e);
    }
    return need.length;
  }

  // Заполнить всё вокруг сразу (на загрузке).
  prefill(px, pz) {
    let guard = 200;
    while (this.update(px, pz, 8) > 0 && guard-- > 0);
  }
}
