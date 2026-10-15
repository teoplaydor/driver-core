// Общие для природы униформы и куски шейдеров: ветер, приминание травы игроками (C6),
// растворение листвы между камерой и игроком (C4), LOD деревьев.
// Униформы - синглтоны модуля: их читают все материалы листвы, включая героическое дерево из structures.js.

import * as THREE from 'three';

export const MAX_BENDERS = 8;

// Числовой ключ чанка (без строк в каждом кадре); |ix|, |iz| < 32768.
export const chunkKey = (ix, iz) => (ix + 32768) * 65536 + (iz + 32768);

export const NATURE = {
  uTime: { value: 0 },
  uWindDir: { value: new THREE.Vector2(0.86, 0.5) },
  uCamPos: { value: new THREE.Vector3() },
  // C4: капсула вокруг отрезка камера -> цель
  uOccA: { value: new THREE.Vector3() },
  uOccB: { value: new THREE.Vector3() },
  uOccOn: { value: 0 },
  uOccR: { value: 2.2 },
  // C6: точки, приминающие траву (xyz + радиус)
  uBenders: { value: Array.from({ length: MAX_BENDERS }, () => new THREE.Vector4(0, -1e4, 0, 0)) },
  // граница ближнего/дальнего LOD деревьев (xz центра + радиус)
  uLod: { value: new THREE.Vector3(0, 0, 70) },
};

// Шум и хэши (вершинный и фрагментный шейдеры).
export const GLSL_NOISE = /* glsl */`
// целочисленный хэш: без полос и повторов, которые даёт fract(sin(...)) на больших координатах
float nzHash(vec2 p) {
  uvec2 q = uvec2(ivec2(floor(p)) + 65536);
  uint h = q.x * 1597334677u ^ q.y * 3812015801u;
  h = (h ^ (h >> 15)) * 2246822519u;
  h ^= h >> 13;
  return float(h) * (1.0 / 4294967296.0);
}
float nzValue(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = nzHash(i), b = nzHash(i + vec2(1.0, 0.0)), c = nzHash(i + vec2(0.0, 1.0)), d = nzHash(i + vec2(1.0, 1.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
`;

// Ветер: большие порывы бегут по полю (скроллящийся шум), плюс мелкая дрожь.
export const GLSL_WIND = /* glsl */`
uniform float uTime;
uniform vec2 uWindDir;
// 0..1 - сила порыва в точке
// (аналитическая волна из синусов: дешевле шума, а для ветра выглядит так же)
float windGust(vec2 wp) {
  vec2 q = wp * 0.045 - uWindDir * uTime * 0.42;
  float g = sin(q.x * 3.1 + sin(q.y * 2.3) * 1.3) * 0.32 + sin(q.y * 4.7 - q.x * 1.9 + 1.7) * 0.2 + sin((q.x + q.y) * 7.3) * 0.08 + 0.5;
  return smoothstep(0.3, 0.85, g);
}
`;

// Растворение дизерингом листвы между камерой и целью (C4).
export const GLSL_OCCLUSION_PARS = /* glsl */`
uniform vec3 uOccA;
uniform vec3 uOccB;
uniform float uOccOn;
uniform float uOccR;
varying vec3 vNatW;
float natBayer(vec2 fc) {
  ivec2 p = ivec2(mod(fc, 4.0));
  int i = p.x + p.y * 4;
  float m[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
  return (m[i] + 0.5) / 16.0;
}
float natOcclusion() {
  if (uOccOn < 0.5) return 0.0;
  vec3 ab = uOccB - uOccA;
  float len = max(length(ab), 1e-3);
  vec3 dir = ab / len;
  float t = clamp(dot(vNatW - uOccA, dir), 0.0, len);
  float d = length(vNatW - (uOccA + dir * t));
  // только между камерой и целью: у самой цели (последние ~1.5 м) не трогаем
  float along = smoothstep(0.6, 2.2, len - t) * smoothstep(0.0, 1.0, t);
  return (1.0 - smoothstep(uOccR * 0.55, uOccR, d)) * along;
}
`;

export const GLSL_OCCLUSION_DISCARD = /* glsl */`
  {
    float occ = natOcclusion();
    if (occ > 0.0 && occ * 1.08 > natBayer(gl_FragCoord.xy)) discard;
  }
`;

// Мировая позиция вершины после всех смещений (для окклюзии).
export const GLSL_WORLDPOS_VERTEX = /* glsl */`
  {
    vec4 nw = vec4(transformed, 1.0);
    #ifdef USE_INSTANCING
      nw = instanceMatrix * nw;
    #endif
    vNatW = (modelMatrix * nw).xyz;
  }
`;

// Ложная просвечиваемость, мягкий "заворот" света за терминатор и лёгкий блик для растений.
// Вставляется после lights_fragment_begin: там directLight - солнце с уже учтённой тенью.
// natTransK (float) задаёт шейдер-потребитель: сила эффекта в этом фрагменте.
export function glslTranslucency(trans, spec, wrap = '0.0', specPow = '18.0') {
  return /* glsl */`
  #if NUM_DIR_LIGHTS > 0
  {
    vec3 tL = directLight.direction;
    vec3 tV = normalize(vViewPosition);
    float back = pow(clamp(dot(-tV, tL), 0.0, 1.0), 3.0);
    float ndl = dot(normal, tL);
    float wrapE = max(0.0, (ndl + 0.6) / 1.6) - max(ndl, 0.0);
    vec3 dl = directLight.color * RECIPROCAL_PI;
    reflectedLight.directDiffuse += diffuseColor.rgb * dl * (back * ${trans} * natTransK + wrapE * ${wrap});
    vec3 tH = normalize(tL + tV);
    float sp = pow(max(dot(normal, tH), 0.0), ${specPow});
    reflectedLight.directDiffuse += dl * sp * ${spec} * natTransK;
  }
  #endif
`;
}

// Подмешать общие униформы в шейдер.
export function bindNature(shader, keys) {
  for (const k of keys) shader.uniforms[k] = NATURE[k];
}

// Освещение растений: нормаль не переворачивается на изнанке (листья, карточки и травинки
// светятся одинаково с обеих сторон).
export const GLSL_NORMAL_NOFLIP = /* glsl */`
float faceDirection = 1.0;
vec3 normal = normalize(vNormal);
vec3 nonPerturbedNormal = normal;
`;
