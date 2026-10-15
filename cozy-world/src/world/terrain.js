// Меш земли и воды. Цвета вершин запекаются по биомам, дорожкам, берегу и склонам.

import * as THREE from 'three';
import { Noise2D, smoothstep, lerp } from '../util/math.js';
import {
  N, CELL, MIN, WATER_Y, LAKE, getHeightGrid, heightAt, pathDist, slopeAt, zoneWeights,
} from './terrain-data.js';

const noise = new Noise2D(4242);
const C = (hex) => new THREE.Color(hex);

const PAL = {
  grassA: C('#8cbd52'), grassB: C('#6aa443'), grassC: C('#aacb62'),
  autumnA: C('#c4a24a'), autumnB: C('#cf7c34'), autumnC: C('#8f9140'),
  snowA: C('#f6f9fd'), snowB: C('#d6e4f2'),
  rainA: C('#527f4b'), rainB: C('#406c48'),
  dirt: C('#cda76a'), dirtDark: C('#a98650'), packedSnow: C('#c9d6e2'),
  sand: C('#ebdba8'), rock: C('#8e949b'), snowRock: C('#9aa7b6'),
  plaza: C('#bdb29c'), plazaDark: C('#9d9482'),
  canyonA: C('#dc8d4a'), canyonB: C('#c5612f'), canyonC: C('#ecae70'), canyonRed: C('#a9441f'),
};

const _w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
const _c = new THREE.Color();
const _tmp = new THREE.Color();
// out += c * w (у THREE.Color такого метода нет)
const acc = (out, c, w) => { out.r += c.r * w; out.g += c.g * w; out.b += c.b * w; };

// Цвет земли в точке. Вынесен отдельно, чтобы им же красить траву и камни.
export function groundColor(x, z, h, slope, out = _c) {
  const n1 = noise.fbm(x * 0.045, z * 0.045, 3) * 0.5 + 0.5;
  const n2 = noise.fbm(x * 0.2 + 30, z * 0.2, 2) * 0.5 + 0.5;
  const w = zoneWeights(x, z, _w);

  out.setRGB(0, 0, 0);
  // луг
  _tmp.copy(PAL.grassB).lerp(PAL.grassA, n1).lerp(PAL.grassC, n2 * 0.45);
  acc(out, _tmp,w.meadow);
  // осень
  _tmp.copy(PAL.autumnC).lerp(PAL.autumnA, n1).lerp(PAL.autumnB, n2 * 0.6);
  acc(out, _tmp,w.autumn);
  // зима
  _tmp.copy(PAL.snowB).lerp(PAL.snowA, n1 * 0.9 + 0.1);
  acc(out, _tmp,w.winter);
  // дождливая низина
  _tmp.copy(PAL.rainB).lerp(PAL.rainA, n1);
  acc(out, _tmp,w.rain);
  // каньон: оранжевые пласты песчаника, в ровных местах - пятна травы
  if (w.canyon > 0.001) {
    const strata = Math.sin(h * 1.55 + n1 * 3.2) * 0.5 + 0.5;
    _tmp.copy(PAL.canyonA).lerp(PAL.canyonB, strata * 0.75).lerp(PAL.canyonC, n2 * 0.4);
    _tmp.lerp(PAL.canyonRed, smoothstep(0.35, 0.9, slope) * 0.6);
    const tuft = (1 - smoothstep(0.08, 0.3, slope)) * smoothstep(0.58, 0.72, n2);
    if (tuft > 0 && h > WATER_Y + 1) _tmp.lerp(PAL.grassA, tuft * 0.75);
    acc(out, _tmp,w.canyon);
  }

  // песок у воды (зимой там тоже снег)
  const shore = 1 - smoothstep(WATER_Y + 0.05, WATER_Y + 1.0, h);
  if (shore > 0) out.lerp(w.winter > 0.5 ? PAL.snowB : PAL.sand, shore * (1 - w.winter * 0.6));

  // камень на крутых склонах (в каньоне склоны и так оранжевые)
  const rocky = smoothstep(0.62, 1.15, slope) * (1 - w.canyon);
  if (rocky > 0) out.lerp(w.winter > 0.4 ? PAL.snowRock : PAL.rock, rocky);

  // дорожки
  const pd = pathDist(x, z);
  const path = 1 - smoothstep(1.3, 2.7, pd);
  if (path > 0) {
    _tmp.copy(w.winter > 0.5 ? PAL.packedSnow : PAL.dirt).lerp(PAL.dirtDark, n2 * 0.5);
    out.lerp(_tmp, path);
  }

  // мощёная площадка вокруг костра
  const r = Math.hypot(x, z);
  const plaza = 1 - smoothstep(7.2, 9.0, r);
  if (plaza > 0) {
    _tmp.copy(PAL.plaza).lerp(PAL.plazaDark, n2);
    out.lerp(_tmp, plaza);
  }
  return out;
}

// Мелкая "зернистость" в шейдере, чтобы большие поверхности не были плоскими.
function patchTerrainMaterial(mat) {
  mat.onBeforeCompile = (shader) => {
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vWPos;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvWPos = (modelMatrix * vec4(transformed, 1.0)).xyz;');
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', `#include <common>
varying vec3 vWPos;
float th(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float tn(vec2 p){
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(th(i), th(i + vec2(1.0, 0.0)), f.x), mix(th(i + vec2(0.0, 1.0)), th(i + vec2(1.0, 1.0)), f.x), f.y);
}`)
      .replace('#include <color_fragment>', `#include <color_fragment>
float g = tn(vWPos.xz * 1.4) * 0.45 + tn(vWPos.xz * 4.9) * 0.3 + tn(vWPos.xz * 0.37) * 0.25;
diffuseColor.rgb *= 0.84 + 0.32 * g;`);
  };
}

export function buildTerrain() {
  const H = getHeightGrid();
  const positions = new Float32Array(N * N * 3);
  const colors = new Float32Array(N * N * 3);
  const indices = new Uint32Array((N - 1) * (N - 1) * 6);

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
  geo.setIndex(new THREE.BufferAttribute(indices, 1));
  geo.computeVertexNormals();
  geo.computeBoundingSphere();

  const mat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 1, metalness: 0 });
  patchTerrainMaterial(mat);
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
