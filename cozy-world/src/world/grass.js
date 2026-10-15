// Трава чанками 16 м вокруг камеры. Пучки изогнутых сужающихся травинок (3-4 сегмента вблизи,
// 2 сегмента вдали) рисуются InstancedBufferGeometry: на чанк один буфер экземпляров и два меша
// (ближний и дальний LOD). Порядок экземпляров случайный, поэтому "первые k" - равномерная выборка:
// плотность плавно падает к краю радиуса (CPU урезает count, шейдер плавно ужимает лишние пучки).
// Ветер (бегущие порывы + дрожь) и приминание игроками (C6) - в вершинном шейдере.

import * as THREE from 'three';
import { mulberry32, hash2, smoothstep, TAU, Noise2D, lerp } from '../util/math.js';
import {
  heightAt, pathDist, zoneWeights, slopeAt, isInterior, WATER_Y, SITES, LAKE,
} from './terrain-data.js';
import { groundColor } from './terrain.js';
import {
  NATURE, GLSL_NOISE, GLSL_WIND, MAX_BENDERS, GLSL_NORMAL_NOFLIP, glslTranslucency, bindNature,
} from './nature/shared.js';
import { canopyDensity, flowerPatch, inLake } from './nature/forest.js';
import { chunkKey } from './nature/shared.js';

const patchNoise = new Noise2D(909);
const tallNoise = new Noise2D(4711);

// Пучок: blades травинок по segs сегментов. Атрибут aBlade = (доля высоты, случайное, сторона).
function buildTuft(blades, segs, seed) {
  const rand = mulberry32(seed);
  const pos = [], nor = [], bl = [], wide = [], idx = [];
  for (let b = 0; b < blades; b++) {
    const a = (b / blades) * TAU + rand() * 0.9;
    const r0 = 0.02 + rand() * 0.07;
    const cx = Math.cos(a) * r0, cz = Math.sin(a) * r0;
    // наклон наружу + немного вбок, изгиб растёт к кончику
    const lean = 0.22 + rand() * 0.38;
    const dx = Math.cos(a + (rand() - 0.5) * 0.8), dz = Math.sin(a + (rand() - 0.5) * 0.8);
    const h = 0.42 + rand() * 0.36;
    const w = 0.02 + rand() * 0.011;
    const px = -dz, pz = dx; // поперёк лезвия
    // лицевая нормаль лезвия: смотрит по направлению наклона
    const rnd = rand();
    const base = pos.length / 3;
    for (let s = 0; s <= segs; s++) {
      const t = s / segs;
      const off = lean * h * t * t;
      const y = h * t * (1 - 0.18 * t * t * lean);
      const x = cx + dx * off, z = cz + dz * off;
      if (s < segs) {
        const ww = w * (1 - Math.pow(t, 1.4) * 0.82);
        pos.push(x - px * ww, y, z - pz * ww, x + px * ww, y, z + pz * ww);
        wide.push(-px * ww, -pz * ww, px * ww, pz * ww);
        // скруглённая нормаль: края слегка развёрнуты в стороны
        const nx = dx * 0.6, nz = dz * 0.6;
        nor.push(nx - px * 0.45, 0.65, nz - pz * 0.45, nx + px * 0.45, 0.65, nz + pz * 0.45);
        bl.push(t, rnd, -1, t, rnd, 1);
      } else {
        pos.push(x + dx * 0.02, y, z + dz * 0.02);
        wide.push(0, 0);
        nor.push(dx * 0.6, 0.7, dz * 0.6);
        bl.push(1, rnd, 0);
      }
    }
    for (let s = 0; s < segs - 1; s++) {
      const i0 = base + s * 2;
      idx.push(i0, i0 + 1, i0 + 2, i0 + 1, i0 + 3, i0 + 2);
    }
    const last = base + (segs - 1) * 2;
    idx.push(last, last + 1, last + 2);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('aBlade', new THREE.Float32BufferAttribute(bl, 3));
  g.setAttribute('aWide', new THREE.Float32BufferAttribute(wide, 2));
  g.setIndex(idx);
  return g;
}

function grassMaterial(far, receive) {
  const mat = new THREE.MeshLambertMaterial({ color: 0xffffff, side: THREE.DoubleSide });
  mat.onBeforeCompile = (shader) => {
    bindNature(shader, ['uTime', 'uWindDir', 'uCamPos', 'uBenders']);
    shader.uniforms.uGrassFade = mat.userData.fade;
    shader.uniforms.uGrassFar = { value: far ? 1 : 0 };
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', `#include <common>
attribute vec3 aBlade;
attribute vec2 aWide;
attribute vec4 iPos;
attribute vec4 iPrm;
attribute vec4 iBase;
attribute vec4 iTip;
uniform vec3 uCamPos;
uniform vec4 uBenders[${MAX_BENDERS}];
uniform vec4 uGrassFade; // начало спада, конец, граница LOD, запас
uniform float uGrassFar; // 1 - дальний LOD (одна программа на оба LOD)
varying vec3 vGrassCol;
varying float vGrassT;
${GLSL_NOISE}
${GLSL_WIND}`)
      .replace('#include <beginnormal_vertex>', `
float gcs = cos(iPos.w), gsn = sin(iPos.w);
vec3 objectNormal = normal;
objectNormal.xz = vec2(gcs * normal.x + gsn * normal.z, -gsn * normal.x + gcs * normal.z);
objectNormal = normalize(mix(objectNormal, vec3(0.0, 1.0, 0.0), 0.45));`)
      .replace('#include <begin_vertex>', `
float gd = distance(iPos.xz, uCamPos.xz);
// вдали травинки шире: иначе они тоньше пикселя и поле "лысеет" (по 3D-расстоянию - и при виде сверху)
vec3 transformed = position;
transformed.xz += aWide * smoothstep(5.0, 45.0, distance(iPos.xyz, uCamPos)) * 1.8;
transformed = vec3(transformed.x * iPrm.x, transformed.y * iPrm.y, transformed.z * iPrm.x);
transformed.xz = vec2(gcs * transformed.x + gsn * transformed.z, -gsn * transformed.x + gcs * transformed.z);
float gT = aBlade.x;
// плотность по расстоянию: ранг экземпляра против доли видимых
float dens = 1.0 - smoothstep(uGrassFade.x, uGrassFade.y, gd);
float keep = clamp((dens - iPrm.z) * 14.0, 0.0, 1.0);
// граница LOD с дизерингом по рангу, чтобы не было ровной линии
float split = uGrassFade.z + (iPrm.z - 0.5) * 3.0;
keep *= mix(step(gd, split), step(split, gd), uGrassFar);
float shrink = mix(1.0, 0.78, smoothstep(uGrassFade.x, uGrassFade.y, gd));
transformed *= keep * shrink;
// ветер: большие волны порывов + мелкая дрожь
vec2 wp = iPos.xz;
float gust = windGust(wp);
vec2 flut = vec2(sin(uTime * 3.7 + aBlade.y * 6.28 + wp.x * 1.7), cos(uTime * 3.1 + aBlade.y * 4.1 + wp.y * 1.3));
vec2 bend = uWindDir * (0.1 + 0.55 * gust) + flut * (0.06 + 0.07 * gust);
// приминание игроками
vec2 push = vec2(0.0);
float gFlat = 0.0;
for (int i = 0; i < ${MAX_BENDERS}; i++) {
  vec4 b = uBenders[i];
  if (b.w <= 0.0) break; // точки идут подряд, дальше пусто
  vec2 dd = wp - b.xz;
  float dist = length(dd);
  float fb = (1.0 - smoothstep(b.w * 0.3, b.w, dist)) * (1.0 - step(1.6, abs(iPos.y - b.y)));
  push += dd / max(dist, 0.05) * fb;
  gFlat = max(gFlat, fb);
}
bend = bend * (1.0 - gFlat) + push * 1.7;
transformed.xz += bend * transformed.y * gT;
transformed.y *= (1.0 - min(0.55, dot(bend, bend) * 0.3 * gT)) * (1.0 - gFlat * 0.5 * gT);
transformed += iPos.xyz;
// цвет: тёмное основание -> сочная середина -> светлый кончик
vec3 gb = iBase.rgb, gtip = iTip.rgb;
vec3 gmid = mix(gb, gtip, 0.35) * 1.15;
vec3 gc = gT < 0.5 ? mix(gb, gmid, gT * 2.0) : mix(gmid, gtip, (gT - 0.5) * 2.0);
gc *= mix(0.55, 1.0, smoothstep(0.0, 0.45, gT));
// у каждой травинки свой оттенок: зеленее или желтее, светлее или темнее
gc *= mix(vec3(0.86, 1.0, 0.84), vec3(1.1, 1.02, 0.82), fract(aBlade.y * 7.31)) * (0.84 + aBlade.y * 0.3);
gc += gust * gT * 0.07 * gtip; // порыв "серебрит" поле
vGrassCol = gc;
vGrassT = gT;`);
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', `#include <common>
varying vec3 vGrassCol;
varying float vGrassT;`)
      .replace('#include <color_fragment>', 'diffuseColor.rgb *= vGrassCol;')
      .replace('#include <normal_fragment_begin>', GLSL_NORMAL_NOFLIP)
      .replace('#include <lights_fragment_begin>', `#include <lights_fragment_begin>
float natTransK = vGrassT * vGrassT;
${glslTranslucency('0.5', '0.1', '0.35', '14.0')}`);
  };
  mat.customProgramCacheKey = () => `grass-${receive ? 1 : 0}`;
  return mat;
}

// Цвета кончиков по зонам
const TIP = {
  meadow: [new THREE.Color('#9cc64a'), new THREE.Color('#c4d662')],
  autumn: [new THREE.Color('#e4b04c'), new THREE.Color('#d98a3a')],
  rain: [new THREE.Color('#6e9a48'), new THREE.Color('#87ad55')],
  canyon: [new THREE.Color('#e8d27c'), new THREE.Color('#d6b864')],
};

export class GrassField {
  constructor(scene, {
    radius = 56, density = 7, lodDist = 13, fadeStart = 18, receiveShadow = true, exclusions = [], chunk = 16,
    blades = [8, 4, 5],
  } = {}) {
    this.scene = scene;
    this.ch = chunk;
    this.radius = radius;
    this.density = density;
    this.exclusions = exclusions;
    this.fade = new THREE.Vector4(fadeStart, radius, lodDist, 0);
    // [травинок в ближнем пучке, сегментов травинки, травинок в дальнем пучке]
    this.nearGeo = buildTuft(blades[0], blades[1], 3);
    this.farGeo = buildTuft(blades[2], 2, 4);
    this.nearMat = grassMaterial(false, receiveShadow);
    this.farMat = grassMaterial(true, receiveShadow);
    this.nearMat.userData.fade = { value: this.fade };
    this.farMat.userData.fade = { value: this.fade };
    this.receive = receiveShadow;
    this.active = new Map();
    this.pool = [];
    this.cap = Math.ceil(density * this.ch * this.ch * 1.05);
    this._w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
    this._c = new THREE.Color();
    this._t = new THREE.Color();
    this.group = new THREE.Group();
    this.group.name = 'grass';
    scene.add(this.group);
    this.cx = 0; this.cz = 0;
  }

  setRadius(r) { this.radius = r; this.fade.y = r; }

  _acquire() {
    let e = this.pool.pop();
    if (!e) {
      const n = this.cap;
      const iPos = new THREE.InstancedBufferAttribute(new Float32Array(n * 4), 4);
      const iPrm = new THREE.InstancedBufferAttribute(new Float32Array(n * 4), 4);
      const iBase = new THREE.InstancedBufferAttribute(new Uint8Array(n * 4), 4, true);
      const iTip = new THREE.InstancedBufferAttribute(new Uint8Array(n * 4), 4, true);
      const mk = (tpl, mat) => {
        const g = new THREE.InstancedBufferGeometry();
        g.index = tpl.index;
        for (const k of ['position', 'normal', 'aBlade', 'aWide']) g.setAttribute(k, tpl.getAttribute(k));
        g.setAttribute('iPos', iPos); g.setAttribute('iPrm', iPrm);
        g.setAttribute('iBase', iBase); g.setAttribute('iTip', iTip);
        g.instanceCount = 0;
        g.boundingSphere = new THREE.Sphere(new THREE.Vector3(), this.ch);
        const m = new THREE.Mesh(g, mat);
        m.receiveShadow = this.receive;
        m.castShadow = false;
        m.matrixAutoUpdate = false;
        return m;
      };
      e = { near: mk(this.nearGeo, this.nearMat), far: mk(this.farGeo, this.farMat), iPos, iPrm, iBase, iTip, n: 0, cx: 0, cz: 0 };
    }
    this.group.add(e.near, e.far);
    return e;
  }

  _release(key) {
    const e = this.active.get(key);
    if (!e) return;
    this.group.remove(e.near, e.far);
    this.active.delete(key);
    this.pool.push(e);
    this._full = false;
  }

  _excluded(x, z) {
    for (const s of SITES) {
      if (s.id !== 'hub' && (x - s.x) ** 2 + (z - s.z) ** 2 < (s.r * 0.8) ** 2) return true;
    }
    for (const ex of this.exclusions) {
      if ((x - ex.x) ** 2 + (z - ex.z) ** 2 < ex.r * ex.r) return true;
    }
    return false;
  }

  _fill(e, cx, cz) {
    e.cx = cx; e.cz = cz;
    const rand = mulberry32(Math.floor(hash2(cx, cz, 17) * 4294967296));
    const x0 = cx * this.ch, z0 = cz * this.ch;
    const w = this._w, c = this._c, t = this._t;
    const P = e.iPos.array, R = e.iPrm.array, B = e.iBase.array, T = e.iTip.array;
    let n = 0;
    let ymin = 1e9, ymax = -1e9;
    const tries = isInterior(x0) ? 0 : this.cap;
    // сетка 5x5 образцов цвета земли/зон - дорогие функции не считаем на каждый пучок
    const G = 5, step = this.ch / (G - 1);
    const gc = new Float32Array(G * G * 3), gw = new Float32Array(G * G * 5), gs = new Float32Array(G * G * 2);
    for (let j = 0; j < G; j++) {
      for (let i = 0; i < G; i++) {
        const sx = x0 + i * step, sz = z0 + j * step;
        const k = j * G + i;
        const sl = slopeAt(sx, sz);
        groundColor(sx, sz, heightAt(sx, sz), sl, c);
        gc[k * 3] = c.r; gc[k * 3 + 1] = c.g; gc[k * 3 + 2] = c.b;
        zoneWeights(sx, sz, w);
        gw[k * 5] = w.meadow; gw[k * 5 + 1] = w.autumn; gw[k * 5 + 2] = w.winter; gw[k * 5 + 3] = w.rain; gw[k * 5 + 4] = w.canyon;
        gs[k * 2] = sl;
        gs[k * 2 + 1] = canopyDensity(sx, sz);
      }
    }
    const bil = (arr, stride, off, fx, fz) => {
      const gx = Math.min(G - 1.001, fx / step), gz = Math.min(G - 1.001, fz / step);
      const ix = Math.floor(gx), iz = Math.floor(gz), ax = gx - ix, az = gz - iz;
      const a = arr[(iz * G + ix) * stride + off], b = arr[(iz * G + ix + 1) * stride + off];
      const cc = arr[((iz + 1) * G + ix) * stride + off], d = arr[((iz + 1) * G + ix + 1) * stride + off];
      return lerp(lerp(a, b, ax), lerp(cc, d, ax), az);
    };
    for (let i = 0; i < tries; i++) {
      const fx = rand() * this.ch, fz = rand() * this.ch;
      const x = x0 + fx, z = z0 + fz;
      const r1 = rand(), r2 = rand(), r3 = rand(), r4 = rand();
      const y = heightAt(x, z);
      if (y < WATER_Y + 0.12 && inLake(x, z)) continue; // вода только в озере, низины в других местах сухие
      const winter = bil(gw, 5, 2, fx, fz);
      if (winter > 0.55) continue; // под снегом травы нет
      const pd = pathDist(x, z);
      if (pd < 1.15) continue;
      const edge = smoothstep(1.15, 2.6, pd); // у тропы трава реже и ниже
      if (r1 > edge * edge * 0.9 + 0.1 && pd < 2.6) continue;
      // песчаный берег озера: травы почти нет
      if (y < WATER_Y + 0.9 && (x - LAKE.x) ** 2 + (z - LAKE.z) ** 2 < (LAKE.r * 1.9) ** 2 && r1 < 0.93) continue;
      const rr = x * x + z * z;
      // площадка у костра: край рваный, как у земли в шейдере (там 8.0-8.5 м + шум)
      if (rr < 64 || (rr < 110 && Math.sqrt(rr) < 8.3 + patchNoise.get(x * 0.9, z * 0.9) * 0.9 + r4 * 0.6)) continue;
      const meadow = bil(gw, 5, 0, fx, fz), autumn = bil(gw, 5, 1, fx, fz), rain = bil(gw, 5, 3, fx, fz), canyon = bil(gw, 5, 4, fx, fz);
      const patch = patchNoise.fbm(x * 0.06, z * 0.06, 2) * 0.5 + 0.5;
      const forest = bil(gs, 2, 1, fx, fz);
      let dens = meadow + autumn * 0.85 + rain * 0.95 + canyon * 0.16;
      dens *= 0.72 + 0.4 * smoothstep(0.25, 0.6, patch);
      dens *= 1 - forest * 0.6; // в тени леса трава реже
      if (canyon > 0.3) dens *= smoothstep(0.62, 0.72, patch) * 1.5 + 0.12;
      if (r2 > dens) continue;
      const sl = bil(gs, 2, 0, fx, fz);
      if (sl > 0.95) continue;
      if (this._excluded(x, z)) continue;

      const tall = tallNoise.fbm(x * 0.045, z * 0.045, 2) * 0.5 + 0.5;
      // обычно по колено (~0.35-0.6 м), пятнами выше; в цветочных полянах ниже, чтобы цветы торчали
      let hs = (0.62 + tall * 0.6) * (0.8 + r3 * 0.4);
      hs *= 1 - 0.35 * smoothstep(0.12, 0.4, flowerPatch(x, z)) * meadow;
      hs *= 0.55 + 0.45 * edge;
      hs *= 1 - canyon * 0.35 - rain * 0.05 - forest * 0.3;
      hs *= rr < 200 ? 0.7 + 0.3 * smoothstep(74, 200, rr) : 1;
      const ws = 0.85 + r4 * 0.3;
      P[n * 4] = x; P[n * 4 + 1] = y - 0.03; P[n * 4 + 2] = z; P[n * 4 + 3] = rand() * TAU;
      R[n * 4] = ws; R[n * 4 + 1] = hs; R[n * 4 + 2] = 0; R[n * 4 + 3] = 0;
      // основание - цвет земли (поле вдали сливается с травой), кончик - по зонам
      c.setRGB(bil(gc, 3, 0, fx, fz), bil(gc, 3, 1, fx, fz), bil(gc, 3, 2, fx, fz)).multiplyScalar(0.62);
      const tn = rand();
      t.setRGB(0, 0, 0);
      const acc = (pair, k) => { if (k > 0.001) { t.r += lerp(pair[0].r, pair[1].r, tn) * k; t.g += lerp(pair[0].g, pair[1].g, tn) * k; t.b += lerp(pair[0].b, pair[1].b, tn) * k; } };
      const sum = meadow + autumn + rain + canyon + 1e-4;
      acc(TIP.meadow, meadow / sum); acc(TIP.autumn, autumn / sum); acc(TIP.rain, rain / sum); acc(TIP.canyon, canyon / sum);
      // пятна оттенка и яркости
      const hueK = (patch - 0.5) * 0.25;
      t.r *= 1 + hueK; t.b *= 1 - hueK * 0.5;
      B[n * 4] = Math.min(255, c.r * 255); B[n * 4 + 1] = Math.min(255, c.g * 255); B[n * 4 + 2] = Math.min(255, c.b * 255);
      T[n * 4] = Math.min(255, t.r * 255); T[n * 4 + 1] = Math.min(255, t.g * 255); T[n * 4 + 2] = Math.min(255, t.b * 255);
      if (y < ymin) ymin = y;
      if (y > ymax) ymax = y;
      n++;
    }
    // ранг = порядок экземпляра: "первые k" - равномерная случайная выборка
    for (let i = 0; i < n; i++) R[i * 4 + 2] = (i + 0.5) / Math.max(1, n);
    e.n = n;
    for (const a of [e.iPos, e.iPrm, e.iBase, e.iTip]) {
      a.clearUpdateRanges();
      a.addUpdateRange(0, n * 4);
      a.needsUpdate = true;
    }
    const cy = n ? (ymin + ymax) / 2 : 0;
    const rad = Math.hypot(this.ch / 2, this.ch / 2, n ? (ymax - ymin) / 2 + 1.2 : 1);
    for (const m of [e.near, e.far]) {
      m.geometry.boundingSphere.center.set(x0 + this.ch / 2, cy, z0 + this.ch / 2);
      m.geometry.boundingSphere.radius = rad;
    }
  }

  // Видимая доля и LOD чанка по расстоянию до камеры.
  _apply(e, px, pz) {
    const x0 = e.cx * this.ch, z0 = e.cz * this.ch;
    const nx = Math.max(x0 - px, 0, px - (x0 + this.ch)), nz = Math.max(z0 - pz, 0, pz - (z0 + this.ch));
    const dNear = Math.hypot(nx, nz);
    const fx = Math.max(Math.abs(px - x0), Math.abs(px - x0 - this.ch)), fz = Math.max(Math.abs(pz - z0), Math.abs(pz - z0 - this.ch));
    const dFar = Math.hypot(fx, fz);
    const dens = 1 - smoothstep(this.fade.x, this.fade.y, dNear);
    const cnt = Math.min(e.n, Math.ceil(e.n * Math.min(1, dens + 0.03)));
    const split = this.fade.z;
    e.near.geometry.instanceCount = dNear < split + 1.6 ? cnt : 0;
    e.far.geometry.instanceCount = dFar > split - 1.6 ? cnt : 0;
    e.near.visible = e.near.geometry.instanceCount > 0;
    e.far.visible = e.far.geometry.instanceCount > 0;
  }

  // Строит не больше `budget` недостающих чанков за вызов (чтобы не было рывков).
  // Без выделений памяти: ближайший недостающий чанк ищется перебором окна вокруг камеры.
  update(px, pz, budget = 1) {
    NATURE.uCamPos.value.set(px, NATURE.uCamPos.value.y, pz);
    const ch = this.ch;
    const cx = Math.floor(px / ch), cz = Math.floor(pz / ch);
    const maxD = this.radius + 1;
    const R = Math.ceil(maxD / ch) + 1;
    for (const e of this.active.values()) {
      const nx = Math.max(e.cx * ch - px, 0, px - (e.cx + 1) * ch), nz = Math.max(e.cz * ch - pz, 0, pz - (e.cz + 1) * ch);
      if (Math.hypot(nx, nz) > maxD + 6) this._release(e.key);
      else this._apply(e, px, pz);
    }
    // всё вокруг уже построено, а камера сдвинулась меньше чем на метр - искать нечего
    if (this._full && Math.abs(px - this._fpx) + Math.abs(pz - this._fpz) < 1) return 0;
    let missing = 0;
    for (;;) {
      missing = 0;
      let bd = Infinity, bx = 0, bz = 0;
      for (let dz = -R; dz <= R; dz++) {
        for (let dx = -R; dx <= R; dx++) {
          const ix = cx + dx, iz = cz + dz;
          if (this.active.has(chunkKey(ix, iz))) continue;
          const nx = Math.max(ix * ch - px, 0, px - (ix + 1) * ch), nz = Math.max(iz * ch - pz, 0, pz - (iz + 1) * ch);
          const d = Math.hypot(nx, nz);
          if (d > maxD) continue;
          missing++;
          if (d < bd) { bd = d; bx = ix; bz = iz; }
        }
      }
      if (!missing || budget <= 0) break;
      const e = this._acquire();
      e.key = chunkKey(bx, bz);
      this._fill(e, bx, bz);
      this._apply(e, px, pz);
      this.active.set(e.key, e);
      budget--;
    }
    this._full = missing === 0;
    this._fpx = px; this._fpz = pz;
    return missing;
  }

  // Заполнить всё вокруг сразу (на загрузке).
  prefill(px, pz) {
    let guard = 300;
    while (this.update(px, pz, 8) > 0 && guard-- > 0);
  }

  // Перестроить чанки, задетые новой зоной исключения.
  invalidate(x, z, r) {
    for (const e of this.active.values()) {
      const x0 = e.cx * this.ch, z0 = e.cz * this.ch;
      if (x + r > x0 && x - r < x0 + this.ch && z + r > z0 && z - r < z0 + this.ch) this._release(e.key);
    }
  }
}
