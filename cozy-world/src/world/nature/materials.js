// Материалы растений: Lambert + атлас листвы с альфа-тестом, качание на ветру, трепетание листьев,
// маска оттенка экземпляра (aTint), растворение между камерой и игроком (C4), просвечиваемость.
// Параметры (сила качания и т.п.) - униформы материала, поэтому все деревья делят одну шейдерную
// программу (меньше компиляций при загрузке). Для теней - свой depth-материал с тем же качанием
// и альфа-тестом: листья отбрасывают "кружевную" тень, а не квадраты карточек.

import * as THREE from 'three';
import { foliageAtlas, ATLAS_SIZE } from './atlas.js';
import {
  NATURE, GLSL_NOISE, GLSL_WIND, GLSL_OCCLUSION_PARS, GLSL_OCCLUSION_DISCARD, GLSL_WORLDPOS_VERTEX,
  GLSL_NORMAL_NOFLIP, glslTranslucency, bindNature,
} from './shared.js';

const f = (v) => Number(v).toFixed(4);

// мипы съедают альфу - компенсируем, иначе листва вдали "лысеет"
const GLSL_ALPHA_MIP = /* glsl */`
#ifdef USE_MAP
  {
    vec2 tdx = dFdx(vMapUv * ${f(ATLAS_SIZE)}), tdy = dFdy(vMapUv * ${f(ATLAS_SIZE)});
    float lod = max(0.0, 0.5 * log2(max(dot(tdx, tdx), dot(tdy, tdy))));
    diffuseColor.a *= 1.0 + lod * 0.3;
  }
#endif
`;

// ---------- деревья и кусты ----------

function plantVertexPars(anchored) {
  return /* glsl */`
attribute float aTint;
attribute float aWind;
${anchored ? 'attribute vec3 aAnchor;' : ''}
uniform vec3 uLod;
uniform vec4 uSway; // сила качания, высота без качания, трепетание, -
${GLSL_NOISE}
${GLSL_WIND}`;
}

// depth: в depth-шейдере нет objectNormal - берём атрибут normal
function plantVertexBody(far, anchored, depth) {
  // дальний LOD (склеенные чанки): только скрытие ближних и простое качание - вершин там много
  if (far) {
    return /* glsl */`
{
  vec3 ip = ${anchored ? 'aAnchor' : 'vec3(modelMatrix[3][0], modelMatrix[3][1], modelMatrix[3][2])'};
  if (distance(ip.xz, uLod.xy) < uLod.z) transformed = vec3(ip.x, -3000.0, ip.z);
  float hgt = max(0.0, transformed.y - ${anchored ? 'ip.y' : '0.0'} - uSway.y);
  float ph = uTime * 1.25 + ip.x * 0.31 + ip.z * 0.23;
  transformed.xz += (uWindDir * 0.6 + vec2(sin(ph), cos(ph * 0.83)) * 0.45) * hgt * uSway.x;
}`;
  }
  return /* glsl */`
{
  #if defined(USE_INSTANCING)
    vec3 ip = vec3(instanceMatrix[3][0], instanceMatrix[3][1], instanceMatrix[3][2]);
    mat3 im = mat3(instanceMatrix);
  #else
    vec3 ip = vec3(modelMatrix[3][0], modelMatrix[3][1], modelMatrix[3][2]);
    mat3 im = mat3(modelMatrix);
  #endif
  ${anchored ? 'ip = aAnchor; im = mat3(1.0);' : ''}
  float baseY = ${anchored ? 'ip.y' : '0.0'};
  // качание: в мировых координатах, чтобы все деревья клонились по ветру согласованно
  float gust = windGust(ip.xz);
  float ph = uTime * 1.25 + ip.x * 0.31 + ip.z * 0.23;
  float hgt = max(0.0, transformed.y - baseY - uSway.y);
  vec2 sw = (uWindDir * (0.25 + gust * 0.9) + vec2(sin(ph), cos(ph * 0.83)) * 0.45) * hgt * uSway.x;
  vec3 ws = vec3(sw.x, -dot(sw, sw) * 0.5, sw.y);
  transformed += transpose(im) * ws / max(dot(im[0], im[0]), 1e-4);
  // трепетание листьев (карточек) вдоль нормали
  transformed += ${depth ? 'normal' : 'objectNormal'} * sin(uTime * (5.0 + gust * 4.0) + dot(position, vec3(2.1, 1.7, 2.9)) + ip.x) * aWind * uSway.z * (0.4 + gust);
}`;
}

// opts: sway (смещение на метр высоты), swayY0 (ниже не качается), far (прятать экземпляры внутри uLod),
// occlusion (C4), textured (атлас + альфа-тест), trans/wrap (просвечиваемость и мягкий терминатор),
// anchored - склеенная геометрия многих растений: основание каждого в атрибуте aAnchor (вместо экземпляров).
// Возвращает материал; mat.userData.depth - материал для теней (customDepthMaterial).
export function plantMaterial({
  sway = 0.01, swayY0 = 1.2, far = false, occlusion = true, textured = true, trans = 0.55, wrap = 0.45, flutter = 0.035,
  anchored = false,
} = {}) {
  const uSway = { value: new THREE.Vector4(sway, swayY0, flutter, 0) };
  const uLight = { value: new THREE.Vector2(trans, wrap) };
  const mat = new THREE.MeshLambertMaterial({
    vertexColors: true,
    side: THREE.DoubleSide,
    map: textured ? foliageAtlas() : null,
    alphaTest: textured ? 0.5 : 0,
  });
  mat.onBeforeCompile = (shader) => {
    bindNature(shader, ['uTime', 'uWindDir', 'uLod', 'uOccA', 'uOccB', 'uOccOn', 'uOccR']);
    shader.uniforms.uSway = uSway;
    shader.uniforms.uPlantLight = uLight;
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', `#include <common>
${plantVertexPars(anchored)}
varying vec3 vNatW;
varying float vNatTint;`)
      .replace('#include <color_vertex>', `
vColor = vec3(1.0);
#ifdef USE_COLOR
  vColor *= color;
#endif
#ifdef USE_INSTANCING_COLOR
  vColor *= mix(vec3(1.0), instanceColor, aTint);
#endif
vNatTint = aTint;`)
      .replace('#include <begin_vertex>', `#include <begin_vertex>
${plantVertexBody(far, anchored, false)}
${GLSL_WORLDPOS_VERTEX}`);

    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', `#include <common>
uniform vec2 uPlantLight;
varying float vNatTint;
${GLSL_OCCLUSION_PARS}`)
      .replace('#include <map_fragment>', `#include <map_fragment>
${GLSL_ALPHA_MIP}`)
      .replace('#include <alphatest_fragment>', `#include <alphatest_fragment>
${occlusion ? GLSL_OCCLUSION_DISCARD : ''}`)
      .replace('#include <normal_fragment_begin>', GLSL_NORMAL_NOFLIP)
      .replace('#include <lights_fragment_begin>', `#include <lights_fragment_begin>
float natTransK = vNatTint;
${glslTranslucency('uPlantLight.x', '0.0', 'uPlantLight.y')}`);
  };
  mat.customProgramCacheKey = () => `plant-${far ? 1 : 0}-${occlusion ? 1 : 0}-${textured ? 1 : 0}-${anchored ? 1 : 0}`;

  // тени: то же качание + альфа-тест по атласу
  const depth = new THREE.MeshDepthMaterial({
    depthPacking: THREE.RGBADepthPacking,
    map: textured ? foliageAtlas() : null,
    alphaTest: textured ? 0.5 : 0,
    side: THREE.DoubleSide,
  });
  depth.onBeforeCompile = (shader) => {
    bindNature(shader, ['uTime', 'uWindDir', 'uLod']);
    shader.uniforms.uSway = uSway;
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', `#include <common>
${plantVertexPars(anchored)}`)
      .replace('#include <begin_vertex>', `#include <begin_vertex>
${plantVertexBody(far, anchored, true)}`);
  };
  depth.customProgramCacheKey = () => `plant-depth-${far ? 1 : 0}-${textured ? 1 : 0}-${anchored ? 1 : 0}`;
  mat.userData.depth = depth;
  return mat;
}

// ---------- мелкий декор ----------

const DECOR_VERTEX_PARS = /* glsl */`
attribute float aWind;
attribute vec4 aAnchor;
uniform vec3 uCamPos;
uniform vec2 uDecorFade;
${GLSL_NOISE}
${GLSL_WIND}`;

const DECOR_VERTEX_BODY = /* glsl */`
{
  vec3 anc = aAnchor.xyz;
  // плавное появление на краю радиуса: предмет "вырастает" из своей точки
  float dd = distance(anc.xz, uCamPos.xz);
  float keep = clamp((1.0 - smoothstep(uDecorFade.x, uDecorFade.y, dd) - aAnchor.w * 0.98) * 8.0, 0.0, 1.0);
  transformed = anc + (transformed - anc) * keep;
  float gust = windGust(anc.xz);
  float ph = uTime * 2.3 + anc.x * 1.3 + anc.z * 0.7;
  vec2 sw = (uWindDir * (0.15 + 0.7 * gust) + vec2(sin(ph), cos(ph * 0.87)) * 0.3) * aWind;
  float hh = max(0.0, transformed.y - anc.y);
  transformed.xz += sw * hh * 0.35;
  transformed.y -= dot(sw, sw) * hh * 0.05;
}`;

// Цветы, папоротники, грибы, камешки, брёвна...: склеенная геометрия чанка.
// aAnchor = (основание предмета xyz, ранг для плавного появления), aWind - сила качания.
export function decorMaterial(fade) {
  const mat = new THREE.MeshLambertMaterial({
    vertexColors: true, side: THREE.DoubleSide, map: foliageAtlas(), alphaTest: 0.5,
  });
  mat.onBeforeCompile = (shader) => {
    bindNature(shader, ['uTime', 'uWindDir', 'uCamPos', 'uOccA', 'uOccB', 'uOccOn', 'uOccR']);
    shader.uniforms.uDecorFade = fade;
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', `#include <common>
${DECOR_VERTEX_PARS}
varying vec3 vNatW;
varying float vNatTint;`)
      .replace('#include <begin_vertex>', `#include <begin_vertex>
${DECOR_VERTEX_BODY}
vNatTint = min(1.0, aWind * 2.0);
${GLSL_WORLDPOS_VERTEX}`);
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', `#include <common>
varying float vNatTint;
${GLSL_OCCLUSION_PARS}`)
      .replace('#include <map_fragment>', `#include <map_fragment>
${GLSL_ALPHA_MIP}`)
      .replace('#include <alphatest_fragment>', `#include <alphatest_fragment>
${GLSL_OCCLUSION_DISCARD}`)
      .replace('#include <normal_fragment_begin>', GLSL_NORMAL_NOFLIP)
      .replace('#include <lights_fragment_begin>', `#include <lights_fragment_begin>
float natTransK = vNatTint;
${glslTranslucency('0.6', '0.0', '0.3')}`);
  };
  mat.customProgramCacheKey = () => 'decor';

  const depth = new THREE.MeshDepthMaterial({
    depthPacking: THREE.RGBADepthPacking, map: foliageAtlas(), alphaTest: 0.5, side: THREE.DoubleSide,
  });
  depth.onBeforeCompile = (shader) => {
    bindNature(shader, ['uTime', 'uWindDir', 'uCamPos']);
    shader.uniforms.uDecorFade = fade;
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', `#include <common>
${DECOR_VERTEX_PARS}`)
      .replace('#include <begin_vertex>', `#include <begin_vertex>
${DECOR_VERTEX_BODY}`);
  };
  depth.customProgramCacheKey = () => 'decor-depth';
  mat.userData.depth = depth;
  return mat;
}

export { NATURE };
