// Меш земли и воды. В вершинах - "природный" цвет земли по биомам (под травой он совпадает
// с основанием травинок, поэтому дальние поля читаются как трава) и маски зон.
// Мелкое (тропы с камешками и колеями, берег, полосы песчаника, искры снега, лужи) и ложный
// рельеф (bump из шума через производные) рисует фрагментный шейдер.

import * as THREE from 'three';
import { Noise2D, smoothstep } from '../util/math.js';
import {
  N, CELL, MIN, WATER_Y, LAKE, getHeightGrid, heightAt, pathDist, slopeAt, zoneWeights, GRID_HALF,
} from './terrain-data.js';
import { canopyDensity } from './nature/forest.js';
import { GLSL_NOISE, NATURE } from './nature/shared.js';

const noise = new Noise2D(4242);
const C = (hex) => new THREE.Color(hex);

const PAL = {
  grassA: C('#86b04a'), grassB: C('#6e9d40'), grassC: C('#a3c25a'),
  forestFloor: C('#5d6a2f'),
  autumnA: C('#b59a42'), autumnB: C('#c27b36'), autumnC: C('#8f8a3c'),
  snowA: C('#f6f9fd'), snowB: C('#d6e4f2'),
  rainA: C('#4e7a47'), rainB: C('#3d6542'),
  rock: C('#8e949b'), snowRock: C('#9aa7b6'),
  canyonA: C('#dc8d4a'), canyonB: C('#c5612f'), canyonC: C('#ecae70'), canyonRed: C('#a9441f'),
  sand: C('#e3cf98'),
};

const _w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
const _c = new THREE.Color();
const _tmp = new THREE.Color();
// out += c * w (у THREE.Color такого метода нет)
const acc = (out, c, w) => { out.r += c.r * w; out.g += c.g * w; out.b += c.b * w; };

// "Природный" цвет земли в точке (без троп и площадок - их рисует шейдер).
// Им же красятся основания травинок, поэтому земля под травой одного цвета с травой.
export function groundColor(x, z, h, slope, out = _c) {
  const n1 = noise.fbm(x * 0.045, z * 0.045, 3) * 0.5 + 0.5;
  const n2 = noise.fbm(x * 0.2 + 30, z * 0.2, 2) * 0.5 + 0.5;
  const w = zoneWeights(x, z, _w);
  const forest = canopyDensity(x, z);

  out.setRGB(0, 0, 0);
  // луг: под кронами - подстилка темнее и оливковее
  _tmp.copy(PAL.grassB).lerp(PAL.grassA, n1).lerp(PAL.grassC, n2 * 0.4).lerp(PAL.forestFloor, forest * 0.45);
  acc(out, _tmp, w.meadow);
  // осень
  _tmp.copy(PAL.autumnC).lerp(PAL.autumnA, n1).lerp(PAL.autumnB, n2 * 0.6);
  acc(out, _tmp, w.autumn);
  // зима
  _tmp.copy(PAL.snowB).lerp(PAL.snowA, n1 * 0.9 + 0.1);
  acc(out, _tmp, w.winter);
  // дождливая низина
  _tmp.copy(PAL.rainB).lerp(PAL.rainA, n1).lerp(PAL.forestFloor, forest * 0.3);
  acc(out, _tmp, w.rain);
  // каньон: оранжевые пласты песчаника, в ровных местах - пятна травы
  if (w.canyon > 0.001) {
    const strata = Math.sin(h * 1.55 + n1 * 3.2) * 0.5 + 0.5;
    _tmp.copy(PAL.canyonA).lerp(PAL.canyonB, strata * 0.75).lerp(PAL.canyonC, n2 * 0.4);
    _tmp.lerp(PAL.canyonRed, smoothstep(0.35, 0.9, slope) * 0.6);
    const tuft = (1 - smoothstep(0.08, 0.3, slope)) * smoothstep(0.58, 0.72, n2);
    if (tuft > 0 && h > WATER_Y + 1) _tmp.lerp(PAL.grassA, tuft * 0.75);
    acc(out, _tmp, w.canyon);
  }

  // камень на крутых склонах (в каньоне склоны и так оранжевые)
  const rocky = smoothstep(0.62, 1.15, slope) * (1 - w.canyon);
  if (rocky > 0) out.lerp(w.winter > 0.4 ? PAL.snowRock : PAL.rock, rocky);
  return out;
}

// Карта расстояний до троп (шаг 2 м) для шейдера: края троп чёткие, а не в шаг сетки рельефа.
const PATH_STEP = 2;
const PATH_N = Math.round((GRID_HALF * 2) / PATH_STEP) + 1;
function buildPathTexture() {
  const data = new Uint8Array(PATH_N * PATH_N);
  for (let j = 0; j < PATH_N; j++) {
    for (let i = 0; i < PATH_N; i++) {
      const d = pathDist(MIN + i * PATH_STEP, MIN + j * PATH_STEP);
      data[j * PATH_N + i] = Math.max(0, Math.min(255, Math.round((d / 8) * 255)));
    }
  }
  const tex = new THREE.DataTexture(data, PATH_N, PATH_N, THREE.RedFormat, THREE.UnsignedByteType);
  tex.magFilter = THREE.LinearFilter;
  tex.minFilter = THREE.LinearFilter;
  tex.wrapS = tex.wrapT = THREE.ClampToEdgeWrapping;
  tex.needsUpdate = true;
  return tex;
}

const f = (v) => Number(v).toFixed(4);
const v3 = (c) => `vec3(${f(c.r)}, ${f(c.g)}, ${f(c.b)})`;
const L = (hex) => v3(C(hex));

function patchTerrainMaterial(mat, pathTex) {
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uPathTex = { value: pathTex };
    shader.uniforms.uCamPos = NATURE.uCamPos;
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', `#include <common>
attribute vec4 aMask;
varying vec3 vWPos;
varying vec3 vWN;
varying vec4 vMask;`)
      .replace('#include <begin_vertex>', `#include <begin_vertex>
vWPos = (modelMatrix * vec4(transformed, 1.0)).xyz;
vWN = normalize(mat3(modelMatrix) * objectNormal);
vMask = aMask;`);

    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', `#include <common>
uniform sampler2D uPathTex;
uniform vec3 uCamPos;
varying vec3 vWPos;
varying vec3 vWN;
varying vec4 vMask;
${GLSL_NOISE}
float tFbm(vec2 p) { return nzValue(p) * 0.5 + nzValue(p * 2.03 + 3.7) * 0.3 + nzValue(p * 4.1 - 1.3) * 0.2; }
// камешки: одна точка на ячейку сетки
// fw - заранее посчитанная доля видимости (производные нельзя брать внутри ветвлений)
vec3 tPebble(vec2 p, float scale, float prob, float fw) {
  vec2 pc = p * scale;
  vec2 ci = floor(pc), cf = fract(pc);
  float h = nzHash(ci);
  vec2 o = vec2(nzHash(ci + 17.1), nzHash(ci + 31.7)) * 0.44 + 0.28;
  float r = 0.11 + 0.13 * nzHash(ci + 5.3);
  float d = length((cf - o) * vec2(1.0, 1.0 + 0.4 * nzHash(ci + 9.1)));
  float m = h < prob ? (1.0 - smoothstep(r * 0.65, r, d)) * fw : 0.0;
  float bump = h < prob ? sqrt(max(0.0, 1.0 - (d / r) * (d / r))) * fw : 0.0;
  return vec3(m, bump, nzHash(ci + 2.2));
}
vec3 tPerturb(vec3 sx, vec3 sy, vec3 surfNorm, vec2 dHdxy) {
  vec3 r1 = cross(sy, surfNorm), r2 = cross(surfNorm, sx);
  float det = dot(sx, r1);
  vec3 grad = sign(det) * (dHdxy.x * r1 + dHdxy.y * r2);
  vec3 n = abs(det) * surfNorm - grad;
  float l = length(n);
  return l > 1e-12 ? n / l : surfNorm;
}`)
      .replace('#include <color_fragment>', `#include <color_fragment>
vec2 tp = vWPos.xz;
// все производные - до ветвлений
vec3 tSx = dFdx(-vViewPosition), tSy = dFdy(-vViewPosition);
float tViewD = length(vViewPosition);
float tFw1 = 1.0 - smoothstep(14.0, 32.0, tViewD);
float tFw2 = 1.0 - smoothstep(6.0, 14.0, tViewD);
float tCanyon = vMask.x, tSnow = vMask.y, tRain = vMask.z;
float n1 = tFbm(tp * 0.35);
float n2 = nzValue(tp * 1.7);
float n3 = nzValue(tp * 6.3);
// макро-пятна и микро-зерно
diffuseColor.rgb *= 0.86 + 0.2 * n1 + 0.1 * (n2 - 0.5) + 0.08 * (n3 - 0.5);
float tH = n2 * 0.5 + n3 * 0.35; // высота для bump
float tBumpK = 0.6;
float tRough = 1.0;
float tPuddle = 0.0;
float tSpark = 0.0;
vec3 tN = normalize(vWN);
float steep = 1.0 - smoothstep(0.5, 0.85, tN.y);

// --- каньон: чёткие полосы песчаника, тёмные потёки на обрывах, светлые верха ---
if (tCanyon > 0.01) {
  float warp = n1 * 2.4 + n2 * 0.6;
  float st1 = sin(vWPos.y * 1.9 + warp) * 0.5 + 0.5;
  float st2 = sin(vWPos.y * 6.1 + warp * 1.7) * 0.5 + 0.5;
  vec3 sA = ${L('#e19555')}, sB = ${L('#c8642f')}, sC = ${L('#9e4423')}, sTop = ${L('#f0bd7e')};
  vec3 cc = mix(sA, sB, smoothstep(0.35, 0.65, st1));
  cc = mix(cc, sC, smoothstep(0.7, 0.9, st2) * 0.45);
  cc *= 0.92 + 0.16 * n3;
  float streak = nzValue(vec2(dot(tp, vec2(0.71, 0.7)) * 2.2 + n2, vWPos.y * 0.12));
  float crack = smoothstep(0.62, 0.66, nzValue(vec2(dot(tp, vec2(-0.7, 0.71)) * 3.1, vWPos.y * 0.5 + n1 * 2.0)));
  cc *= 1.0 - steep * (smoothstep(0.5, 0.85, streak) * 0.32 + crack * 0.4);
  cc = mix(cc, sTop, smoothstep(0.9, 0.98, tN.y) * 0.45);
  float k = tCanyon * clamp(steep * 1.2 + 0.25, 0.0, 1.0);
  diffuseColor.rgb = mix(diffuseColor.rgb, cc, k);
  tH += (st2 * 0.6 + crack * -0.8) * steep * tCanyon;
  tBumpK += steep * tCanyon * 1.2;
}

// --- снег: голубые тени в низинах и на склонах, искры ---
if (tSnow > 0.4) {
  float sk = smoothstep(0.4, 0.7, tSnow);
  vec3 blue = ${L('#b7cbe6')};
  float hollow = smoothstep(0.35, 0.75, 1.0 - n1) * 0.35 + steep * 0.35;
  diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * blue, hollow * sk);
  vec2 sc = floor(tp * 26.0);
  float tw = nzHash(sc + floor(uCamPos.xz * 1.7));
  tSpark = step(0.992, nzHash(sc)) * step(0.4, tw) * sk;
  tH = n1 * 0.7 + n2 * 0.25;
  tBumpK = 0.7;
  tRough = 0.8;
}

// --- берег озера: мокрая тёмная полоса + песок ---
float lakeD = distance(tp, vec2(${f(LAKE.x)}, ${f(LAKE.z)}));
float lakeK = 1.0 - smoothstep(${f(LAKE.r * 1.45)}, ${f(LAKE.r * 1.9)}, lakeD);
if (lakeK > 0.0) {
  float yy = vWPos.y - ${f(WATER_Y)};
  float sandK = (1.0 - smoothstep(0.55 + n2 * 0.35, 0.95 + n2 * 0.35, yy)) * lakeK * (1.0 - tSnow);
  vec3 sand = mix(${L('#e6d39c')}, ${L('#d9bf86')}, n3);
  diffuseColor.rgb = mix(diffuseColor.rgb, sand, sandK);
  float wet = (1.0 - smoothstep(0.05, 0.22 + n3 * 0.06, yy)) * lakeK;
  diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * ${L('#8d7c5c')}, wet * 0.85);
  float under = (1.0 - smoothstep(-0.25, 0.0, yy)) * lakeK;
  diffuseColor.rgb = mix(diffuseColor.rgb, ${L('#6f7a52')}, under * 0.6);
  tRough = mix(tRough, 0.35, wet);
  tH = mix(tH, n3 * 0.4, sandK);
}

// --- тропы: утоптанная земля, крап камешков, тёмные колеи, рваные травянистые края ---
vec2 tuv = ((tp - vec2(${f(MIN)})) / ${f(PATH_STEP)} + 0.5) / ${f(PATH_N)};
float pd = texture2D(uPathTex, tuv).r * 8.0;
float pathM = 1.0 - smoothstep(0.95, 1.6, pd + (n2 - 0.5) * 0.7 + (n3 - 0.5) * 0.25);
// площадка у костра
float plazaR = length(tp);
float plazaM = 1.0 - smoothstep(8.0, 8.5, plazaR + (n2 - 0.5) * 0.5);
float walkM = max(pathM, plazaM);
if (walkM > 0.001) {
  vec3 dirt = mix(${L('#c8a56b')}, ${L('#b18c56')}, n2);
  dirt = mix(dirt, ${L('#d6bb86')}, smoothstep(0.55, 0.9, n3) * 0.4);
  vec3 plaza = mix(${L('#c2b49a')}, ${L('#a99d86')}, n2);
  vec3 ground = mix(dirt, plaza, plazaM);
  // колеи
  float rut = (1.0 - smoothstep(0.1, 0.32, abs(pd - 0.62))) * (0.6 + 0.4 * n2) * (1.0 - plazaM);
  ground *= 1.0 - rut * 0.22;
  // камешки
  vec3 pb = tPebble(tp, 3.1, 0.2 - plazaM * 0.08, tFw1);
  vec3 pbc = mix(${L('#a49b8c')}, ${L('#e1d8c8')}, pb.z);
  ground = mix(ground, pbc, pb.x * 0.85);
  vec3 pb2 = tPebble(tp + 7.3, 8.0, 0.14 * (1.0 - plazaM * 0.7), tFw2);
  ground = mix(ground, mix(${L('#9a8f80')}, ${L('#d9cdb8')}, pb2.z), pb2.x * 0.45);
  // зимой тропа - утоптанный снег
  vec3 packed = mix(${L('#c9d6e2')}, ${L('#b3c3d3')}, n2);
  ground = mix(ground, packed, smoothstep(0.45, 0.6, tSnow));
  // лужи в дождливой зоне
  float pz = smoothstep(0.58, 0.66, tFbm(tp * 0.45 + 11.0) + (n3 - 0.5) * 0.1) * smoothstep(0.35, 0.6, tRain) * pathM;
  tPuddle = pz;
  ground = mix(ground, ground * ${L('#4a4640')}, pz);
  diffuseColor.rgb = mix(diffuseColor.rgb, ground, walkM);
  tH = mix(tH, n3 * 0.3 + pb.y * 1.4 + pb2.y * 0.6 - rut * 0.8, walkM * (1.0 - pz));
  tBumpK = mix(tBumpK, 1.1, walkM);
  tRough = mix(tRough, 0.95, walkM);
}
tRough = mix(tRough, 0.06, tPuddle);
tH *= 1.0 - tPuddle;
// травяной "ковёр": штрихи по ветру и светлый налёт кончиков вдали, где трава уже не рисуется
{
  float grassy = clamp(diffuseColor.g * 1.6 - diffuseColor.r * 1.2 - diffuseColor.b * 0.4, 0.0, 1.0) * (1.0 - walkM) * (1.0 - smoothstep(0.4, 0.6, tSnow));
  float streak = nzValue(vec2(dot(tp, vec2(0.86, 0.5)) * 9.0, dot(tp, vec2(-0.5, 0.86)) * 2.2));
  diffuseColor.rgb *= 1.0 + (streak - 0.5) * 0.18 * grassy;
  float farK = smoothstep(22.0, 60.0, tViewD) * grassy;
  diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * vec3(1.12, 1.1, 0.86), farK * 0.6);
}`)
      .replace('#include <normal_fragment_maps>', `#include <normal_fragment_maps>
{
  // ложный рельеф: высота из шума -> возмущение нормали через производные
  float fadeB = 1.0 - smoothstep(25.0, 70.0, length(vViewPosition));
  vec2 dH = vec2(dFdx(tH), dFdy(tH)) * tBumpK * 0.06 * fadeB;
  normal = tPerturb(tSx, tSy, normal, dH);
}`)
      .replace('#include <roughnessmap_fragment>', `#include <roughnessmap_fragment>
roughnessFactor = min(roughnessFactor, tRough);`)
      .replace('#include <emissivemap_fragment>', `#include <emissivemap_fragment>
#ifdef USE_FOG
{
  // лужи отражают небо (окружения нет - берём цвет дымки)
  float fres = 0.25 + 0.75 * pow(1.0 - clamp(dot(normal, normalize(vViewPosition)), 0.0, 1.0), 3.0);
  totalEmissiveRadiance += fogColor * fres * tPuddle * 0.3;
}
#endif
totalEmissiveRadiance += vec3(tSpark) * 1.6;`);
  };
  mat.customProgramCacheKey = () => 'terrain-v2';
}

export function buildTerrain() {
  const H = getHeightGrid();
  const positions = new Float32Array(N * N * 3);
  const colors = new Float32Array(N * N * 3);
  const masks = new Uint8Array(N * N * 4);
  const indices = new Uint32Array((N - 1) * (N - 1) * 6);
  const w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };

  for (let iz = 0; iz < N; iz++) {
    for (let ix = 0; ix < N; ix++) {
      const i = iz * N + ix;
      const x = MIN + ix * CELL, z = MIN + iz * CELL, h = H[i];
      positions[i * 3] = x;
      positions[i * 3 + 1] = h;
      positions[i * 3 + 2] = z;
      groundColor(x, z, h, slopeAt(x, z), _c);
      colors[i * 3] = _c.r;
      colors[i * 3 + 1] = _c.g;
      colors[i * 3 + 2] = _c.b;
      zoneWeights(x, z, w);
      masks[i * 4] = Math.round(w.canyon * 255);
      masks[i * 4 + 1] = Math.round(w.winter * 255);
      masks[i * 4 + 2] = Math.round(w.rain * 255);
      masks[i * 4 + 3] = Math.round(w.autumn * 255);
    }
  }
  // Диагональ a-d должна совпадать с heightAt().
  let k = 0;
  for (let iz = 0; iz < N - 1; iz++) {
    for (let ix = 0; ix < N - 1; ix++) {
      const a = iz * N + ix, b = a + 1, c = a + N, d = c + 1;
      indices[k++] = a; indices[k++] = c; indices[k++] = d;
      indices[k++] = a; indices[k++] = d; indices[k++] = b;
    }
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  geo.setAttribute('aMask', new THREE.BufferAttribute(masks, 4, true));
  geo.setIndex(new THREE.BufferAttribute(indices, 1));
  geo.computeVertexNormals();
  geo.computeBoundingSphere();

  const mat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 1, metalness: 0 });
  patchTerrainMaterial(mat, buildPathTexture());
  const mesh = new THREE.Mesh(geo, mat);
  mesh.receiveShadow = true;
  mesh.name = 'terrain';
  return mesh;
}

// Вода: прозрачность берётся из карты глубин, так берег получается мягким без depth-текстуры.
export function buildWater(timeUniform) {
  const size = LAKE.r * 3.6;
  const res = 96;
  const data = new Uint8Array(res * res);
  for (let j = 0; j < res; j++) {
    for (let i = 0; i < res; i++) {
      const x = LAKE.x + (i / (res - 1) - 0.5) * size;
      const z = LAKE.z + (j / (res - 1) - 0.5) * size;
      const depth = WATER_Y - heightAt(x, z);
      data[j * res + i] = Math.max(0, Math.min(255, Math.round((depth / 3.5) * 255)));
    }
  }
  const tex = new THREE.DataTexture(data, res, res, THREE.RedFormat, THREE.UnsignedByteType);
  tex.magFilter = THREE.LinearFilter;
  tex.minFilter = THREE.LinearFilter;
  tex.needsUpdate = true;

  const uniforms = THREE.UniformsUtils.merge([
    THREE.UniformsLib.fog,
    {
      uDepth: { value: tex },
      uTime: timeUniform,
      uShallow: { value: C('#7fd0c4') },
      uDeep: { value: C('#2f7f9c') },
      uFoam: { value: C('#ffffff') },
    },
  ]);
  const mat = new THREE.ShaderMaterial({
    uniforms,
    transparent: true,
    depthWrite: false,
    fog: true,
    vertexShader: `
      #include <common>
      #include <fog_pars_vertex>
      varying vec2 vUv;
      varying vec3 vWPos;
      void main() {
        vUv = uv;
        vec4 wp = modelMatrix * vec4(position, 1.0);
        vWPos = wp.xyz;
        vec4 mvPosition = viewMatrix * wp;
        gl_Position = projectionMatrix * mvPosition;
        #include <fog_vertex>
      }`,
    fragmentShader: `
      #include <common>
      #include <fog_pars_fragment>
      uniform sampler2D uDepth;
      uniform float uTime;
      uniform vec3 uShallow, uDeep, uFoam;
      varying vec2 vUv;
      varying vec3 vWPos;
      float wh(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
      float wn(vec2 p){
        vec2 i = floor(p), f = fract(p);
        f = f * f * (3.0 - 2.0 * f);
        return mix(mix(wh(i), wh(i + vec2(1.0, 0.0)), f.x), mix(wh(i + vec2(0.0, 1.0)), wh(i + vec2(1.0, 1.0)), f.x), f.y);
      }
      void main() {
        float d = texture2D(uDepth, vec2(vUv.x, 1.0 - vUv.y)).r;
        float alpha = smoothstep(0.0, 0.06, d);
        vec3 col = mix(uShallow, uDeep, smoothstep(0.02, 0.55, d));
        vec2 p = vWPos.xz;
        float rip = wn(p * 0.9 + vec2(uTime * 0.25, uTime * 0.18)) * 0.5 + wn(p * 2.3 - vec2(uTime * 0.32, uTime * 0.2)) * 0.5;
        col *= 0.9 + 0.2 * rip;
        float glint = pow(smoothstep(0.72, 0.95, rip), 3.0) * 0.55;
        float foam = (1.0 - smoothstep(0.02, 0.11, d)) * (0.55 + 0.45 * sin(uTime * 1.3 + p.x * 1.2 + p.y * 0.9));
        col = mix(col, uFoam, clamp(foam, 0.0, 1.0) * 0.7) + glint;
        gl_FragColor = vec4(col, alpha * 0.88);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
        #include <fog_fragment>
      }`,
  });
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(size, size), mat);
  mesh.rotation.x = -Math.PI / 2;
  mesh.position.set(LAKE.x, WATER_Y, LAKE.z);
  mesh.renderOrder = 1;
  mesh.name = 'water';
  return mesh;
}
