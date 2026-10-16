// Эффекты построек на GPU: языки пламени (билборды с шумом, HDR для bloom), искры и дым (одни точки,
// анимация по времени в шейдере - процессор их не трогает), вид из окна квартиры на дождливый город.

import * as THREE from 'three';
import { NATURE, GLSL_NOISE } from '../nature/shared.js';

// ---------- пламя ----------

const FLAME_VERT = /* glsl */`
attribute vec2 aCorner;
attribute vec4 aFlame; // ширина, высота, зерно, яркость
uniform float uTime;
varying vec2 vUv;
varying float vSeed;
varying float vB;
varying float vH;
void main() {
  vec3 c = (modelMatrix * vec4(position, 1.0)).xyz;
  vec3 dir = cameraPosition - c;
  dir = length(dir) > 1e-4 ? normalize(dir) : vec3(0.0, 0.0, 1.0);
  vec3 r = cross(vec3(0.0, 1.0, 0.0), dir);
  r = length(r) > 0.02 ? normalize(r) : vec3(1.0, 0.0, 0.0);
  vec3 upS = normalize(cross(dir, r));
  // при взгляде сверху язык наклоняется к камере, чтобы не превращаться в линию
  vec3 up = normalize(mix(vec3(0.0, 1.0, 0.0), upS, 0.6));
  float s = aFlame.z * 17.0;
  float h = aFlame.y * (0.86 + 0.1 * sin(uTime * 5.3 + s) + 0.06 * sin(uTime * 11.7 + s * 1.7));
  float sway = (sin(uTime * 2.3 + s) * 0.06 + sin(uTime * 4.1 + s * 0.7) * 0.03) * aCorner.y * aCorner.y;
  vec3 p = c + r * (aCorner.x * aFlame.x + sway) + up * aCorner.y * h + dir * 0.02 * aFlame.w;
  gl_Position = projectionMatrix * viewMatrix * vec4(p, 1.0);
  vUv = aCorner;
  vSeed = aFlame.z;
  vB = aFlame.w;
  vH = h;
}`;

const FLAME_FRAG = /* glsl */`
uniform float uTime;
uniform float uIntensity;
varying vec2 vUv;
varying float vSeed;
varying float vB;
varying float vH;
${GLSL_NOISE}
void main() {
  float y = vUv.y;
  float t = uTime;
  vec2 q = vec2(vUv.x * 1.4 + vSeed * 13.0, y * 1.7 * vH - t * 2.4);
  float n = nzValue(q * 2.0) * 0.6 + nzValue(q * 4.3 + 3.1) * 0.3 + nzValue(q * 8.7 - 1.7) * 0.1;
  // капля: широкая у основания, острая сверху; извивается сильнее к верху
  float w = 0.95 * pow(max(0.0, 1.0 - y), 0.62) * smoothstep(-0.15, 0.16, y);
  float x = vUv.x + (n - 0.5) * 0.7 * y;
  float shape = w - abs(x) - (n - 0.42) * 0.5 * y;
  float a = smoothstep(0.0, 0.16, shape);
  // верх рвётся на отдельные языки
  a *= smoothstep(-0.05, 0.22, n * 1.3 + 0.5 - y);
  if (a < 0.004) discard;
  float core = smoothstep(0.1, 0.5, shape) * (1.0 - y * 0.75);
  vec3 cO = vec3(1.0, 0.36, 0.06), cM = vec3(1.0, 0.62, 0.16), cC = vec3(1.0, 0.92, 0.62);
  vec3 col = mix(cO, cM, smoothstep(0.0, 0.25, shape));
  col = mix(col, cC, clamp(core * vB, 0.0, 1.0));
  col *= 1.0 - y * 0.25;
  float I = uIntensity * (0.75 + vB * 0.35);
  // предумноженная альфа: края частично закрывают фон (огонь виден и днём), середина светится
  gl_FragColor = vec4(col * a * I, a * 0.5);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
}`;

// flames: [{ x, y, z, w, h, b }] в локальных координатах; один draw call на всё пламя.
export function buildFlames(flames, intensity = 2.6) {
  const n = flames.length;
  const pos = new Float32Array(n * 4 * 3), cor = new Float32Array(n * 4 * 2), prm = new Float32Array(n * 4 * 4);
  const idx = [];
  flames.forEach((f, i) => {
    const corners = [[-1, 0], [1, 0], [1, 1], [-1, 1]];
    corners.forEach(([cx, cy], k) => {
      const v = i * 4 + k;
      pos.set([f.x, f.y, f.z], v * 3);
      cor.set([cx, cy], v * 2);
      prm.set([f.w, f.h, f.seed ?? i * 0.137 + 0.05, f.b ?? 1], v * 4);
    });
    const o = i * 4;
    idx.push(o, o + 1, o + 2, o, o + 2, o + 3);
  });
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('aCorner', new THREE.BufferAttribute(cor, 2));
  g.setAttribute('aFlame', new THREE.BufferAttribute(prm, 4));
  g.setIndex(idx);
  g.boundingSphere = new THREE.Sphere(new THREE.Vector3(0, 0.7, 0), 1.6);
  const m = new THREE.ShaderMaterial({
    uniforms: { uTime: NATURE.uTime, uIntensity: { value: intensity } },
    vertexShader: FLAME_VERT,
    fragmentShader: FLAME_FRAG,
    transparent: true,
    depthWrite: false,
    blending: THREE.CustomBlending,
    blendSrc: THREE.OneFactor,
    blendDst: THREE.OneMinusSrcAlphaFactor,
  });
  const mesh = new THREE.Mesh(g, m);
  mesh.name = 'flames';
  mesh.renderOrder = 2;
  return mesh;
}

// ---------- искры и дым ----------

const PART_VERT = /* glsl */`
attribute vec4 aSeed; // зерно, тип (0 - искра, 1 - дым, 2 - пар/дым трубы), длительность, размер
uniform float uTime;
uniform float uScale;
uniform vec2 uWind;
varying float vA;
varying vec3 vCol;
varying float vType;
void main() {
  float s = aSeed.x, type = aSeed.y, L = aSeed.z;
  float life = fract(uTime / L + s * 7.31);
  float a = s * 61.0 + floor(uTime / L + s * 7.31) * 2.4;
  vec3 p = (modelMatrix * vec4(position, 1.0)).xyz;
  float size;
  if (type < 0.5) {
    // искра: вверх с завихрением, быстро гаснет
    float r = 0.25 + 0.35 * fract(s * 13.7);
    p += vec3(sin(a) * r * life + sin(uTime * 3.0 + s * 40.0) * 0.18 * life, 0.5 + life * (2.4 + 1.6 * fract(s * 5.3)), cos(a) * r * life + cos(uTime * 2.6 + s * 31.0) * 0.18 * life);
    p.xz += uWind * life * life * 0.8;
    size = aSeed.w * (1.0 - life * 0.7);
    vA = smoothstep(0.0, 0.05, life) * (1.0 - smoothstep(0.55, 1.0, life)) * (0.6 + 0.4 * sin(uTime * 23.0 + s * 50.0));
    vCol = mix(vec3(4.2, 1.9, 0.5), vec3(2.2, 0.55, 0.12), life);
  } else {
    // дым: поднимается, расплывается и сносится ветром
    float rise = type < 1.5 ? 4.2 : 5.5;
    p += vec3(sin(a) * 0.15 + sin(uTime * 0.7 + s * 9.0) * 0.35 * life, 0.9 + life * rise, cos(a) * 0.15 + cos(uTime * 0.6 + s * 7.0) * 0.35 * life);
    p.xz += uWind * life * (type < 1.5 ? 2.4 : 3.5);
    size = aSeed.w * (0.45 + life * 1.9);
    vA = smoothstep(0.0, 0.18, life) * (1.0 - life) * (type < 1.5 ? 0.26 : 0.4);
    vCol = type < 1.5 ? vec3(0.42, 0.4, 0.4) : vec3(0.82, 0.84, 0.88);
  }
  vType = type;
  vec4 mv = viewMatrix * vec4(p, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = clamp(size * uScale / max(0.1, -mv.z), 1.0, 256.0);
}`;

const PART_FRAG = /* glsl */`
varying float vA;
varying vec3 vCol;
varying float vType;
void main() {
  vec2 d = gl_PointCoord - 0.5;
  float r = length(d) * 2.0;
  if (r > 1.0) discard;
  float a = vType < 0.5 ? smoothstep(1.0, 0.2, r) : exp(-r * r * 3.0) * (1.0 - r);
  a *= vA;
  // искры - аддитивно (альфа 0), дым - обычное наложение (предумноженная альфа)
  gl_FragColor = vec4(vCol * a, vType < 0.5 ? 0.0 : a);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
}`;

const _wind = { value: new THREE.Vector2(0.5, 0.3) };
const _scale = { value: 400 };

// emitters: [{ x, y, z, sparks, smoke, type }] -> один Points
export function buildParticles(emitters, radius = 8) {
  const pos = [], seed = [];
  let k = 0;
  for (const e of emitters) {
    for (let i = 0; i < (e.sparks || 0); i++, k++) {
      pos.push(e.x, e.y, e.z);
      seed.push(((k * 0.618034) % 1), 0, 1.1 + ((k * 0.377) % 1) * 0.9, 0.035 + ((k * 0.21) % 1) * 0.025);
    }
    for (let i = 0; i < (e.smoke || 0); i++, k++) {
      pos.push(e.x + (((k * 0.71) % 1) - 0.5) * (e.spread || 0.3), e.y, e.z + (((k * 0.43) % 1) - 0.5) * (e.spread || 0.3));
      seed.push(((k * 0.618034) % 1), e.type ?? 1, (e.life || 6) * (0.8 + ((k * 0.29) % 1) * 0.4), e.size || 0.5);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('aSeed', new THREE.Float32BufferAttribute(seed, 4));
  g.computeBoundingSphere();
  g.boundingSphere.radius += radius;
  const m = new THREE.ShaderMaterial({
    uniforms: { uTime: NATURE.uTime, uScale: _scale, uWind: _wind },
    vertexShader: PART_VERT,
    fragmentShader: PART_FRAG,
    transparent: true,
    depthWrite: false,
    blending: THREE.CustomBlending,
    blendSrc: THREE.OneFactor,
    blendDst: THREE.OneMinusSrcAlphaFactor,
  });
  const pts = new THREE.Points(g, m);
  pts.name = 'particles';
  pts.renderOrder = 3;
  return pts;
}

// Пиксели на метр на расстоянии 1 м (для размеров точек) и ветер - обновляются из update.
export function updateParticleScale(heightPx, camera) {
  const f = camera && camera.isPerspectiveCamera ? 1 / Math.tan((camera.fov * Math.PI) / 360) : 1.235;
  _scale.value = heightPx * 0.5 * f;
  _wind.value.copy(NATURE.uWindDir.value).multiplyScalar(0.6);
}

// ---------- вид из окна квартиры ----------

export function rainViewMaterial() {
  return new THREE.ShaderMaterial({
    uniforms: { uTime: NATURE.uTime },
    vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
    fragmentShader: /* glsl */`
      uniform float uTime; varying vec2 vUv;
      float h(float n){ return fract(sin(n * 91.345) * 47453.5453); }
      void main(){
        vec2 uv = vUv;
        vec3 col = mix(vec3(0.05, 0.07, 0.13), vec3(0.2, 0.25, 0.36), uv.y);
        // силуэты домов с окнами
        float bx = floor(uv.x * 9.0);
        float bh = 0.25 + 0.35 * h(bx + 4.0);
        if (uv.y < bh) {
          col = vec3(0.07, 0.08, 0.12);
          vec2 w = vec2(fract(uv.x * 9.0 * 4.0), fract(uv.y * 22.0));
          float on = step(0.55, h(floor(uv.x * 36.0) * 7.0 + floor(uv.y * 22.0) * 13.0));
          float win = step(0.25, w.x) * step(w.x, 0.75) * step(0.3, w.y) * step(w.y, 0.8);
          col += vec3(1.4, 0.95, 0.5) * win * on * (0.8 + 0.2 * sin(uTime * 0.5 + bx));
        }
        for (int i = 0; i < 14; i++) {
          float fi = float(i);
          vec2 c = vec2(h(fi), 0.05 + 0.5 * h(fi * 3.1));
          float r = 0.012 + 0.025 * h(fi * 7.7);
          float d = length((uv - c) * vec2(2.4, 1.0));
          vec3 lc = mix(vec3(1.0, 0.75, 0.4), vec3(0.5, 0.7, 1.0), step(0.5, h(fi * 5.3)));
          col += lc * smoothstep(r, r * 0.3, d) * (0.6 + 0.25 * sin(uTime * 2.0 + fi)) * 1.6;
        }
        float x = uv.x * 70.0, cid = floor(x), rnd = h(cid);
        float y = fract(uv.y * 2.4 + uTime * (0.9 + rnd) + rnd * 9.0);
        float streak = smoothstep(0.0, 0.02, y) * smoothstep(0.22, 0.0, y) * step(0.7, h(cid + 3.0)) * (1.0 - abs(fract(x) - 0.5) * 2.0);
        col += vec3(0.55, 0.65, 0.85) * streak * 0.6;
        // капли на стекле
        vec2 g = uv * vec2(26.0, 14.0);
        vec2 gi = floor(g), gf = fract(g) - 0.5;
        float dr = h(gi.x * 17.0 + gi.y * 3.0);
        float drop = smoothstep(0.16, 0.08, length(gf + vec2(0.0, fract(uTime * 0.05 * dr + dr) - 0.5) * 0.6)) * step(0.75, dr);
        col += vec3(0.25, 0.3, 0.38) * drop;
        gl_FragColor = vec4(col, 1.0);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
  });
}
