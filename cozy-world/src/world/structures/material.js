// Общий материал построек: стандартный PBR, где цвет, шероховатость, металличность, свечение и узор берутся
// из вершин. Узоры (дерево, доски, кирпич, кора, черепица, годичные кольца, ткань...) рисуются в шейдере по
// координатам aPat (метры вдоль детали), поэтому вся статика зоны склеивается в 1-2 меша одного материала.
// Надписи и картинки - из одного canvas-атласа (узор LABEL, aPat - координаты в атласе).

import * as THREE from 'three';
import { NATURE, GLSL_NOISE } from '../nature/shared.js';

// Коды узоров (aMat.w). +16 - свечение мерцает (угли, огонёк фонаря).
export const PAT = {
  plain: 0, wood: 1, planks: 2, brick: 3, bark: 4, stone: 5, shingle: 6, rings: 7, fabric: 8, gingham: 9,
  label: 10, straw: 11, snow: 12, metal: 13, padded: 14, glass: 15, mossy: 16, ash: 17,
};
export const FLICKER = 32;

// ---------- атлас надписей и картинок ----------

const ATLAS = 2048;
let _atlas = null;

export class Atlas {
  constructor() {
    this.canvas = document.createElement('canvas');
    this.canvas.width = this.canvas.height = ATLAS;
    this.g = this.canvas.getContext('2d');
    this.g.fillStyle = '#ffffff';
    this.g.fillRect(0, 0, ATLAS, ATLAS);
    this.x = 0; this.y = 0; this.row = 0;
    this.texture = new THREE.CanvasTexture(this.canvas);
    this.texture.colorSpace = THREE.SRGBColorSpace;
    this.texture.anisotropy = 4;
    this.texture.generateMipmaps = true;
    this.texture.minFilter = THREE.LinearMipmapLinearFilter;
  }

  // Участок w x h пикселей (с полями от просачивания мипов); draw(g, w, h) рисует в его системе координат.
  // Возвращает [u0, v0, u1, v1] (v вверх, как у uv в three).
  region(w, h, draw) {
    const pad = 6;
    if (this.x + w + pad * 2 > ATLAS) { this.x = 0; this.y += this.row; this.row = 0; }
    const x = this.x + pad, y = this.y + pad;
    this.x += w + pad * 2;
    this.row = Math.max(this.row, h + pad * 2);
    if (this.y + h + pad > ATLAS) console.warn('structures atlas overflow');
    const g = this.g;
    g.save();
    g.translate(x, y);
    g.beginPath(); g.rect(-pad, -pad, w + pad * 2, h + pad * 2); g.clip();
    draw(g, w, h);
    g.restore();
    this.texture.needsUpdate = true;
    return [x / ATLAS, 1 - (y + h) / ATLAS, (x + w) / ATLAS, 1 - y / ATLAS];
  }
}

export function atlas() {
  if (!_atlas) _atlas = new Atlas();
  return _atlas;
}

// ---------- шейдер ----------

const PATTERNS = /* glsl */`
${GLSL_NOISE}
float sFbm(vec2 p) { return nzValue(p) * 0.6 + nzValue(p * 2.3 + 7.1) * 0.28 + nzValue(p * 5.1 - 3.7) * 0.12; }

// годичные волокна вдоль u: волнистые линии поперёк v
float sGrain(vec2 p, float aa) {
  float n = nzValue(vec2(p.x * 0.7, p.y * 6.0));
  float l = sin(p.y * 46.0 + n * 9.0 + sin(p.x * 1.3) * 1.5) * 0.5 + 0.5;
  l = l * l * l;
  float fine = nzValue(vec2(p.x * 2.0, p.y * 140.0));
  return 1.0 - (l * 0.17 + n * 0.12 + fine * 0.06 * aa) * aa;
}

vec3 cozyPattern(float pat, vec2 p, vec3 base, vec3 wp, vec3 wn, float fwp) {
  // aa: 1 - узор виден, 0 - деталь мельче пикселя (берём средний тон)
  if (pat < 0.5) {
    float n = nzValue(wp.xz * 0.6 + wp.y * 0.3);
    return base * (0.95 + n * 0.1);
  }
  if (pat < 1.5) { // дерево
    float aa = 1.0 - smoothstep(0.03, 0.12, fwp);
    return base * sGrain(p, aa);
  }
  if (pat < 2.5) { // доски пола/палубы: ряды по v, стыки по u
    float W = 0.155, L = 1.35;
    float row = floor(p.y / W);
    float off = nzHash(vec2(row, 3.0)) * L;
    float col = floor((p.x + off) / L);
    vec2 f = vec2(fract((p.x + off) / L) * L, fract(p.y / W) * W);
    float e = min(min(f.x, L - f.x) * 0.5, min(f.y, W - f.y));
    float aa = 1.0 - smoothstep(0.004, 0.02, fwp);
    float seam = 1.0 - smoothstep(0.003, 0.009, e) * 1.0;
    float tone = 0.86 + 0.24 * nzHash(vec2(row, col) + 11.0);
    vec3 c = base * mix(1.0, tone, aa) * sGrain(vec2(p.x + off, p.y + row * 0.37), 1.0 - smoothstep(0.03, 0.12, fwp));
    return c * (1.0 - seam * 0.5 * aa);
  }
  if (pat < 3.5) { // кирпич (перевязка в полкирпича)
    vec2 b = p / vec2(0.25, 0.085);
    float row = floor(b.y);
    b.x += mod(row, 2.0) * 0.5;
    vec2 id = floor(b), f = fract(b);
    vec2 e2 = min(f, 1.0 - f) * vec2(0.25, 0.085);
    float aa = 1.0 - smoothstep(0.004, 0.012, fwp);
    float inside = smoothstep(0.004, 0.009, min(e2.x, e2.y));
    float tone = 0.8 + 0.32 * nzHash(id + 17.0);
    float speck = nzValue(p * 55.0);
    vec3 brick = base * tone * (0.93 + 0.12 * speck) * (0.92 + 0.08 * smoothstep(0.0, 0.03, min(e2.x, e2.y)));
    vec3 mortar = vec3(0.62, 0.58, 0.54) * (0.9 + 0.1 * speck);
    vec3 c = mix(mortar, brick, inside);
    vec3 avg = mix(mortar, base * 0.95, 0.82);
    return mix(avg, c, aa);
  }
  if (pat < 4.5) { // кора: борозды вдоль u
    float n1 = sFbm(vec2(p.x * 1.6, p.y * 11.0));
    float n2 = nzValue(vec2(p.x * 5.0, p.y * 34.0));
    float crev = smoothstep(0.42, 0.62, n1);
    float aa = 1.0 - smoothstep(0.02, 0.1, fwp);
    return base * mix(1.0, (0.62 + 0.5 * crev) * (0.9 + 0.2 * n2), aa);
  }
  if (pat < 5.5) { // камень: пятна и прожилки
    float n = sFbm(wp.xz * 2.6 + wp.y * 1.7);
    float n2 = nzValue(wp.xz * 9.0 - wp.y * 6.0);
    return base * (0.8 + 0.3 * n) * (0.94 + 0.12 * n2);
  }
  if (pat < 6.5) { // черепица/гонт: ряды по v (вверх по скату), язычки по u
    float H = 0.2, Wd = 0.23;
    float row = floor(p.y / H);
    float u = p.x / Wd + mod(row, 2.0) * 0.5 + nzHash(vec2(row, 1.0)) * 0.3;
    float id = floor(u);
    vec2 f = vec2(fract(u) * Wd, fract(p.y / H) * H);
    float aa = 1.0 - smoothstep(0.005, 0.02, fwp);
    float gap = smoothstep(0.004, 0.012, min(f.x, Wd - f.x));
    float edge = smoothstep(0.0, 0.05, f.y);           // тень под нижним краем верхнего ряда
    float tone = 0.82 + 0.3 * nzHash(vec2(id, row) + 5.0);
    float n = nzValue(vec2(u * 3.0, p.y * 20.0));
    vec3 c = base * tone * (0.92 + 0.12 * n) * mix(0.55, 1.0, edge) * mix(0.6, 1.0, gap);
    return mix(base * 0.86, c, aa);
  }
  if (pat < 7.5) { // годичные кольца на спиле (p - метры от оси)
    float r = length(p);
    float n = nzValue(p * 9.0);
    float ring = sin(r * 115.0 + n * 3.5) * 0.5 + 0.5;
    float aa = 1.0 - smoothstep(0.006, 0.02, fwp);
    float a = atan(p.y, p.x);
    float crack = smoothstep(0.93, 0.99, nzValue(vec2(a * 2.6, r * 3.0)) ) * smoothstep(0.02, 0.06, r);
    vec3 c = base * (0.86 + 0.16 * ring * aa) * (1.0 - 0.35 * crack * aa);
    return c * mix(0.72, 1.0, smoothstep(0.0, 0.025, r));
  }
  if (pat < 8.5) { // ткань: мелкое плетение
    float aa = 1.0 - smoothstep(0.002, 0.008, fwp);
    float w = sin(p.x * 520.0) * sin(p.y * 520.0);
    float n = nzValue(p * 30.0);
    return base * (0.95 + 0.06 * w * aa + 0.06 * n);
  }
  if (pat < 9.5) { // клетка (занавески, плед)
    float s = 0.07;
    float a = smoothstep(0.45, 0.55, abs(fract(p.x / s) - 0.5) * 2.0);
    float b = smoothstep(0.45, 0.55, abs(fract(p.y / s) - 0.5) * 2.0);
    float aa = 1.0 - smoothstep(0.01, 0.04, fwp);
    float k = (a + b) * 0.5;
    vec3 light = mix(base, vec3(1.0, 0.97, 0.9), 0.82);
    vec3 c = mix(light, base, k);
    c = mix(c, base * 0.72, a * b);
    return mix(mix(light, base, 0.55), c, aa);
  }
  if (pat < 10.5) { // надпись/картинка из атласа
    return base * texture2D(uSAtlas, p).rgb;
  }
  if (pat < 11.5) { // солома
    float n = nzValue(vec2(p.x * 2.5, p.y * 55.0));
    float n2 = nzValue(vec2(p.x * 7.0, p.y * 140.0));
    float aa = 1.0 - smoothstep(0.004, 0.02, fwp);
    return base * mix(1.0, 0.72 + 0.38 * n + 0.15 * n2, aa);
  }
  if (pat < 12.5) { // снег: мягкие переливы и голубизна на крутых местах
    float n = sFbm(wp.xz * 1.3 + wp.y);
    vec3 c = base * (0.95 + 0.07 * n);
    c = mix(c, c * vec3(0.86, 0.92, 1.04), (1.0 - clamp(wn.y, 0.0, 1.0)) * 0.6);
    return c;
  }
  if (pat < 13.5) { // металл: лёгкая шлифовка
    float n = nzValue(vec2(p.x * 3.0, p.y * 90.0));
    return base * (0.92 + 0.12 * n);
  }
  if (pat < 14.5) { // мягкие стёганые панели: ромбы со швами и пуговицами
    vec2 q = vec2(p.x + p.y, p.x - p.y) / 0.42;
    vec2 f = fract(q) - 0.5;
    float seam = smoothstep(0.42, 0.5, max(abs(f.x), abs(f.y)));
    float puff = 1.0 - dot(f, f) * 0.9;
    float aa = 1.0 - smoothstep(0.01, 0.04, fwp);
    return base * mix(0.95, (0.84 + 0.18 * puff) * (1.0 - seam * 0.22), aa);
  }
  if (pat > 15.5 && pat < 16.5) { // камень с мхом сверху
    float n = sFbm(wp.xz * 2.6 + wp.y * 1.7);
    float n2 = nzValue(wp.xz * 9.0 - wp.y * 6.0);
    vec3 c = base * (0.8 + 0.3 * n) * (0.94 + 0.12 * n2);
    float moss = smoothstep(0.55, 0.9, wn.y) * smoothstep(0.55, 0.75, nzValue(wp.xz * 1.7 + 4.0));
    return mix(c, vec3(0.2, 0.32, 0.12) * (0.8 + 0.4 * n2), moss * 0.6);
  }
  if (pat > 16.5) { // зола: серые пятна, тёмные угольки
    float n = sFbm(wp.xz * 5.0);
    float n2 = nzValue(wp.xz * 23.0);
    return base * (0.7 + 0.5 * n) * (0.85 + 0.3 * n2);
  }
  // стекло: отражение неба по высоте и косой блик
  float g = smoothstep(-0.2, 1.2, fract(p.y * 0.25 + p.x * 0.12)) ;
  float streak = smoothstep(0.08, 0.0, abs(fract((p.x + p.y * 0.6) * 0.9) - 0.5) - 0.12);
  return base * (0.75 + 0.35 * g) + vec3(0.06, 0.08, 0.1) * streak;
}
`;

const VERT_PARS = /* glsl */`
attribute vec4 aMat;
attribute vec2 aPat;
varying vec4 vSMat;
varying vec2 vSPat;
varying vec3 vSWorld;
varying vec3 vSNrm;
`;

const FRAG_PARS = /* glsl */`
uniform sampler2D uSAtlas;
uniform float uSTime;
uniform vec4 uSFlags; // x: узоры (0 - упрощённо), y: сила неба в отражениях
varying vec4 vSMat;
varying vec2 vSPat;
varying vec3 vSWorld;
varying vec3 vSNrm;
${PATTERNS}
`;

// opts.lite - без узоров (кроме надписей): дешёвый вариант для слабых устройств
export function structureMaterial({ lite = false } = {}) {
  const m = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 1, metalness: 0 });
  const uAtlas = { value: atlas().texture };
  m.onBeforeCompile = (sh) => {
    sh.uniforms.uSAtlas = uAtlas;
    sh.uniforms.uSTime = NATURE.uTime;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', `#include <common>\n${VERT_PARS}`)
      .replace('#include <begin_vertex>', `#include <begin_vertex>
  vSMat = aMat;
  vSPat = aPat;
  vSWorld = (modelMatrix * vec4(transformed, 1.0)).xyz;
  vSNrm = normalize(mat3(modelMatrix) * objectNormal);`);
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', `#include <common>\n${FRAG_PARS}`)
      .replace('#include <color_fragment>', `#include <color_fragment>
  float sPat = floor(vSMat.w + 0.5);
  float sFlick = 0.0;
  if (sPat >= ${FLICKER}.0) { sPat -= ${FLICKER}.0; sFlick = 1.0; }
  {
    vec2 fw2 = fwidth(vSPat);
    float fwp = max(fw2.x, fw2.y);
    ${lite ? 'if (sPat > 9.5 && sPat < 10.5) diffuseColor.rgb = cozyPattern(sPat, vSPat, diffuseColor.rgb, vSWorld, normalize(vSNrm), fwp);'
    : 'diffuseColor.rgb = cozyPattern(sPat, vSPat, diffuseColor.rgb, vSWorld, normalize(vSNrm), fwp);'}
  }`)
      .replace('#include <roughnessmap_fragment>', 'float roughnessFactor = vSMat.x;')
      .replace('#include <metalnessmap_fragment>', 'float metalnessFactor = vSMat.y;')
      .replace('#include <emissivemap_fragment>', `#include <emissivemap_fragment>
  {
    float fl = 1.0;
    if (sFlick > 0.5) {
      float ph = uSTime * 6.0 + vSWorld.x * 7.0 + vSWorld.z * 5.0;
      fl = 0.72 + 0.16 * sin(ph) + 0.12 * sin(ph * 2.7 + 1.3) + 0.1 * nzValue(vec2(uSTime * 3.0, vSWorld.x * 4.0 + vSWorld.y * 9.0));
    }
    totalEmissiveRadiance += diffuseColor.rgb * vSMat.z * fl;
  }`)
      .replace('#include <lights_fragment_maps>', `#include <lights_fragment_maps>
#if NUM_HEMI_LIGHTS > 0
  {
    vec3 skyR = inverseTransformDirection( reflect( - geometryViewDir, geometryNormal ), viewMatrix );
    radiance += mix( hemisphereLights[ 0 ].groundColor, hemisphereLights[ 0 ].skyColor, smoothstep( - 0.25, 0.45, skyR.y ) ) * 0.45;
  }
#endif`);
  };
  m.customProgramCacheKey = () => (lite ? 'cozy-structures-lite' : 'cozy-structures');
  return m;
}
