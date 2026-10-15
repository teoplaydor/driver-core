// Погода и атмосферные частицы целиком на видеокарте: позиции считаются в вершинном шейдере (частица
// «падает» и заворачивается в коробку вокруг камеры), CPU ничего не делает. Каждая система - один draw call
// и рисуется, только когда нужна.
//  - дождь: штрихи разной длины и прозрачности по направлению ветра; всплески-кольца на земле около камеры;
//  - снег: мягкие хлопья разного размера с покачиванием;
//  - листья (осень): кувыркающиеся листочки с прожилкой, просвечивают против солнца;
//  - пылинки в лучах (каньон), пыльца (луг) - ярче, когда смотришь в сторону солнца;
//  - светлячки у костра (светятся - их подхватывает bloom).

import * as THREE from 'three';
import { getHeightGrid, N, CELL, MIN } from './terrain-data.js';
import { CAMPFIRE, HUB_H } from '../../shared/layout.js';

const COMMON = /* glsl */`
uniform float uTime;
uniform vec3 uCam;
uniform float uBox;
uniform float uAmount;
uniform vec3 uSunDir;
attribute vec4 aRand;
vec3 wrapPos(vec3 base, vec3 motion) {
  vec3 p = base * uBox + motion;
  vec3 rel = mod(p - uCam + 0.5 * uBox, uBox) - 0.5 * uBox;
  return uCam + rel;
}
float edgeFade(vec3 w) {
  vec3 r = w - uCam;
  float d = length(r.xz) / (0.5 * uBox);
  return (1.0 - smoothstep(0.65, 1.0, d)) * (1.0 - smoothstep(0.6, 1.0, abs(r.y) / (0.5 * uBox)));
}
`;

const OUT = /* glsl */`
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
`;

// ---------- дождь: штрихи ----------
const RAIN_VERT = /* glsl */`
${COMMON}
uniform vec2 uWind;
uniform float uPx; // размер пикселя на 1 м расстояния
varying float vAlpha;
varying vec2 vQ;
void main() {
  float fall = 15.0 + 6.0 * aRand.x;
  vec3 vel = vec3(uWind.x, -fall, uWind.y);
  vec3 head = wrapPos(aRand.xyz, vel * uTime * vec3(1.0, 1.0, 1.0));
  vec3 axis = normalize(vel);
  float len = 0.35 + 0.6 * fract(aRand.w * 7.31);
  vec3 toCam = uCam - head;
  float dist = length(toCam);
  vec3 side = normalize(cross(axis, toCam));
  float width = max(0.006 + 0.008 * aRand.w, dist * uPx * 0.9);
  vec3 w = head - axis * len * position.y + side * position.x * width;
  vAlpha = step(aRand.z, uAmount * 0.999 + 0.001) * edgeFade(head) * (0.35 + 0.55 * aRand.w)
    * min(1.0, 0.012 / width * 1.4) * smoothstep(0.6, 2.0, dist);
  vQ = position.xy;
  gl_Position = projectionMatrix * viewMatrix * vec4(w, 1.0);
}`;

const RAIN_FRAG = /* glsl */`
uniform vec3 uColor;
varying float vAlpha;
varying vec2 vQ;
void main() {
  float a = vAlpha * (1.0 - abs(vQ.x) * 2.0) * mix(1.0, 0.15, vQ.y);
  if (a < 0.003) discard;
  gl_FragColor = vec4(uColor, a);
  ${OUT}
}`;

// ---------- всплески: кольца на земле ----------
const SPLASH_VERT = /* glsl */`
uniform float uTime;
uniform vec3 uCam;
uniform float uAmount;
uniform sampler2D uHeight;
attribute vec4 aRand;
varying float vT;
varying vec2 vQ;
varying float vAlpha;
float hAt(vec2 p) {
  vec2 g = (p - vec2(${MIN.toFixed(1)})) / ${CELL.toFixed(2)};
  vec2 i = floor(g), f = fract(g);
  vec2 s = 1.0 / vec2(${N}.0);
  vec2 u = (i + 0.5) * s;
  float a = texture2D(uHeight, u).r, b = texture2D(uHeight, u + vec2(s.x, 0.0)).r;
  float c = texture2D(uHeight, u + vec2(0.0, s.y)).r, d = texture2D(uHeight, u + s).r;
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float rh(float n) { return fract(sin(n * 12.9898) * 43758.5453); }
void main() {
  float period = 0.45 + 0.4 * aRand.x;
  float tt = uTime / period + aRand.y * 7.0;
  float cyc = floor(tt);
  vT = fract(tt);
  float seed = aRand.z * 100.0 + cyc * 1.618;
  float ang = rh(seed) * 6.2832, rad = sqrt(rh(seed + 3.7)) * 13.0;
  vec2 p = uCam.xz + vec2(cos(ang), sin(ang)) * rad;
  float y = hAt(p) + 0.025;
  float size = (0.06 + 0.16 * vT) * (0.7 + 0.6 * aRand.w);
  vec3 w = vec3(p.x + position.x * size, y, p.y + position.z * size);
  vQ = position.xz * 2.0;
  vAlpha = step(aRand.w, uAmount) * (1.0 - vT) * (1.0 - smoothstep(9.0, 13.0, rad));
  gl_Position = projectionMatrix * viewMatrix * vec4(w, 1.0);
}`;

const SPLASH_FRAG = /* glsl */`
uniform vec3 uColor;
varying float vT;
varying vec2 vQ;
varying float vAlpha;
void main() {
  float d = length(vQ);
  float ring = smoothstep(0.16, 0.0, abs(d - 0.82)) + smoothstep(0.12, 0.0, abs(d - 0.45)) * (1.0 - vT) * 0.6;
  float a = ring * vAlpha * 0.55;
  if (a < 0.004) discard;
  gl_FragColor = vec4(uColor, a);
  ${OUT}
}`;

// ---------- точки: снег, пылинки, пыльца ----------
const POINT_VERT = /* glsl */`
${COMMON}
uniform float uSize;
uniform float uScale;
uniform vec3 uMotion; // скорость падения, размах покачивания, частота покачивания
uniform float uForward; // усиление к солнцу (рассеяние вперёд)
varying float vAlpha;
varying float vSeed;
void main() {
  float ph = aRand.x * 40.0;
  float sw = uMotion.z;
  vec3 motion = vec3(sin(uTime * 0.8 * sw + ph) * uMotion.y + uTime * 0.25 * uMotion.y,
    -uTime * uMotion.x * (0.7 + 0.6 * aRand.y),
    cos(uTime * 0.6 * sw + ph * 1.3) * uMotion.y);
  vec3 w = wrapPos(aRand.xyz, motion);
  vec4 mv = viewMatrix * vec4(w, 1.0);
  vec3 dir = normalize(w - uCam);
  float fwd = 1.0 + uForward * pow(max(dot(dir, uSunDir), 0.0), 3.0);
  float tw = 0.65 + 0.35 * sin(uTime * (1.3 + aRand.w * 2.0) + ph);
  vAlpha = step(aRand.z, uAmount) * edgeFade(w) * fwd * mix(1.0, tw, step(0.01, uForward)) * smoothstep(0.25, 1.2, -mv.z);
  vSeed = aRand.w;
  gl_Position = projectionMatrix * mv;
  gl_PointSize = clamp(uSize * uScale / max(0.1, -mv.z) * (0.45 + 1.1 * aRand.w * aRand.w), 1.0, 40.0);
}`;

const POINT_FRAG = /* glsl */`
uniform vec3 uColor;
uniform float uSoft;
varying float vAlpha;
varying float vSeed;
void main() {
  float d = length(gl_PointCoord - 0.5);
  if (d > 0.5) discard;
  float a = vAlpha * smoothstep(0.5, mix(0.3, 0.0, uSoft), d);
  if (a < 0.004) discard;
  gl_FragColor = vec4(uColor * (0.85 + 0.3 * vSeed), min(a, 1.0));
  ${OUT}
}`;

// ---------- листья: кувыркающиеся квадраты с формой листа ----------
const LEAF_VERT = /* glsl */`
${COMMON}
varying float vAlpha;
varying vec2 vQ;
varying float vSeed;
varying float vLight;
vec3 rot(vec3 v, vec3 k, float a) {
  float c = cos(a), s = sin(a);
  return v * c + cross(k, v) * s + k * dot(k, v) * (1.0 - c);
}
void main() {
  float ph = aRand.x * 40.0;
  vec3 motion = vec3(sin(uTime * 0.7 + ph) * 1.6 + uTime * 0.9, -uTime * (0.7 + 0.45 * aRand.y), cos(uTime * 0.55 + ph * 1.3) * 1.3 + uTime * 0.35);
  vec3 c = wrapPos(aRand.xyz, motion);
  vec3 axis = normalize(vec3(sin(ph), cos(ph * 1.7), sin(ph * 0.6 + 1.0)));
  float ang = uTime * (2.0 + 3.0 * aRand.w) + ph;
  float size = 0.07 + 0.07 * aRand.w;
  vec3 corner = vec3(position.x * size * 0.62, position.y * size, 0.0);
  vec3 w = c + rot(corner, axis, ang);
  vec3 n = rot(vec3(0.0, 0.0, 1.0), axis, ang);
  vec3 toCam = normalize(uCam - c);
  // двусторонний свет: лицом к солнцу ярче, против солнца - просвечивает
  float facing = abs(dot(n, uSunDir));
  float back = pow(max(dot(-toCam, uSunDir), 0.0), 3.0);
  vLight = 0.55 + 0.45 * facing + back * 0.8;
  vAlpha = step(aRand.z, uAmount) * edgeFade(c);
  vQ = position.xy;
  vSeed = aRand.w;
  gl_Position = projectionMatrix * viewMatrix * vec4(w, 1.0);
}`;

const LEAF_FRAG = /* glsl */`
uniform vec3 uColor;
varying float vAlpha;
varying vec2 vQ;
varying float vSeed;
varying float vLight;
void main() {
  // лист: заострённый овал с черешком
  vec2 q = vQ * 2.0;
  float wdt = (1.0 - q.y * q.y) * (0.9 - 0.25 * q.y);
  if (abs(q.x) > wdt || q.y > 0.98) discard;
  if (vAlpha < 0.01) discard;
  vec3 c = mix(vec3(0.86, 0.33, 0.09), vec3(0.96, 0.7, 0.18), fract(vSeed * 3.7));
  c = mix(c, vec3(0.62, 0.17, 0.07), step(0.8, fract(vSeed * 5.3)));
  c = mix(c, vec3(0.75, 0.62, 0.2), step(0.9, fract(vSeed * 9.1)));
  c *= 1.0 - 0.3 * smoothstep(0.06, 0.0, abs(q.x)); // прожилка
  gl_FragColor = vec4(c * uColor * vLight, vAlpha);
  ${OUT}
}`;

// ---------- светлячки ----------
const FLY_VERT = /* glsl */`
uniform float uTime;
uniform vec3 uCam;
uniform float uAmount;
uniform float uScale;
uniform vec3 uCenter;
attribute vec4 aRand;
varying float vGlow;
void main() {
  float ph = aRand.x * 60.0;
  float ang = aRand.y * 6.2832 + uTime * 0.06 * (aRand.w - 0.5);
  float rad = 3.2 + aRand.z * 11.0;
  vec3 p = uCenter + vec3(cos(ang) * rad, 0.35 + aRand.w * 2.2, sin(ang) * rad);
  p += vec3(sin(uTime * 0.37 + ph), sin(uTime * 0.53 + ph * 1.7) * 0.5, cos(uTime * 0.41 + ph * 0.7)) * 0.9;
  vec4 mv = viewMatrix * vec4(p, 1.0);
  float blink = pow(max(0.0, sin(uTime * (0.8 + aRand.w * 0.9) + ph)), 3.0);
  vGlow = blink * uAmount * (1.0 - smoothstep(30.0, 45.0, -mv.z));
  gl_Position = projectionMatrix * mv;
  gl_PointSize = clamp(0.14 * uScale / max(0.1, -mv.z), 1.5, 24.0);
}`;

const FLY_FRAG = /* glsl */`
uniform vec3 uColor;
varying float vGlow;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float core = smoothstep(0.18, 0.0, d), halo = smoothstep(0.5, 0.0, d);
  float a = (core + halo * 0.35) * vGlow;
  if (a < 0.004) discard;
  gl_FragColor = vec4(uColor * (core * 4.0 + halo * 0.8), a);
  ${OUT}
}`;

function heightTexture() {
  const tex = new THREE.DataTexture(getHeightGrid(), N, N, THREE.RedFormat, THREE.FloatType);
  tex.magFilter = tex.minFilter = THREE.NearestFilter;
  tex.needsUpdate = true;
  return tex;
}

function randAttr(count) {
  const a = new Float32Array(count * 4);
  for (let i = 0; i < a.length; i++) a[i] = Math.random();
  return new THREE.InstancedBufferAttribute(a, 4);
}

// Квадрат на экземпляр (InstancedBufferGeometry).
function quads(count, positions) {
  const g = new THREE.InstancedBufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  g.setIndex([0, 1, 2, 0, 2, 3]);
  g.setAttribute('aRand', randAttr(count));
  g.instanceCount = count;
  return g;
}

function points(count) {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(count * 3), 3)); // нужен для boundingSphere
  const a = new Float32Array(count * 4);
  for (let i = 0; i < a.length; i++) a[i] = Math.random();
  g.setAttribute('aRand', new THREE.BufferAttribute(a, 4));
  return g;
}

// двусторонние: квадраты разворачиваются к камере в шейдере, их обход заранее не известен
function material(vert, frag, uniforms, extra = {}) {
  return new THREE.ShaderMaterial({
    uniforms, vertexShader: vert, fragmentShader: frag, transparent: true, depthWrite: false, fog: false, side: THREE.DoubleSide, ...extra,
  });
}

function baseUniforms(box) {
  return {
    uTime: { value: 0 }, uCam: { value: new THREE.Vector3() }, uBox: { value: box }, uAmount: { value: 0 },
    uSunDir: { value: new THREE.Vector3(0, 1, 0) }, uColor: { value: new THREE.Color(1, 1, 1) }, uScale: { value: 500 },
  };
}

export class Weather {
  // quality - доля частиц; motes - пылинки и пыльца (на слабых устройствах выключены)
  constructor(quality = 1, { motes = true } = {}) {
    this.group = new THREE.Group();
    this.group.name = 'weather';
    const n = (v) => Math.max(8, Math.round(v * quality));
    this.systems = [];
    const add = (name, obj, uniforms) => {
      obj.frustumCulled = false;
      obj.visible = false;
      obj.name = name;
      this.group.add(obj);
      const s = { obj, u: uniforms };
      this.systems.push(s);
      return s;
    };

    // дождь
    {
      const u = { ...baseUniforms(30), uWind: { value: new THREE.Vector2(1.6, 0.7) }, uPx: { value: 0.001 } };
      const g = quads(n(4200), [-0.5, 0, 0, 0.5, 0, 0, 0.5, 1, 0, -0.5, 1, 0]);
      this.rain = add('rain', new THREE.Mesh(g, material(RAIN_VERT, RAIN_FRAG, u)), u);
    }
    // всплески
    if (quality >= 0.5) {
      const u = { ...baseUniforms(0), uHeight: { value: heightTexture() } };
      const g = quads(n(300), [-0.5, 0, -0.5, 0.5, 0, -0.5, 0.5, 0, 0.5, -0.5, 0, 0.5]);
      this.splash = add('rain-splash', new THREE.Mesh(g, material(SPLASH_VERT, SPLASH_FRAG, u)), u);
    }
    // снег
    {
      const u = { ...baseUniforms(30), uSize: { value: 0.085 }, uMotion: { value: new THREE.Vector3(1.2, 0.8, 1) }, uForward: { value: 0 }, uSoft: { value: 0.75 } };
      this.snow = add('snow', new THREE.Points(points(n(5200)), material(POINT_VERT, POINT_FRAG, u)), u);
    }
    // листья
    {
      const u = baseUniforms(44);
      const g = quads(n(420), [-0.5, -0.5, 0, 0.5, -0.5, 0, 0.5, 0.5, 0, -0.5, 0.5, 0]);
      this.leaves = add('leaves', new THREE.Mesh(g, material(LEAF_VERT, LEAF_FRAG, u)), u);
    }
    // пылинки в лучах (каньон)
    if (motes) {
      const u = { ...baseUniforms(24), uSize: { value: 0.018 }, uMotion: { value: new THREE.Vector3(-0.05, 0.35, 0.4) }, uForward: { value: 5 }, uSoft: { value: 0.6 } };
      this.dust = add('dust', new THREE.Points(points(n(800)), material(POINT_VERT, POINT_FRAG, u, { blending: THREE.AdditiveBlending })), u);
    }
    // пыльца (луг)
    if (motes) {
      const u = { ...baseUniforms(22), uSize: { value: 0.014 }, uMotion: { value: new THREE.Vector3(-0.08, 0.5, 0.5) }, uForward: { value: 3 }, uSoft: { value: 0.4 } };
      this.pollen = add('pollen', new THREE.Points(points(n(420)), material(POINT_VERT, POINT_FRAG, u, { blending: THREE.AdditiveBlending })), u);
    }
    // светлячки у костра
    {
      const u = { ...baseUniforms(0), uCenter: { value: new THREE.Vector3(CAMPFIRE.x, HUB_H, CAMPFIRE.z) } };
      this.flies = add('fireflies', new THREE.Points(points(n(46)), material(FLY_VERT, FLY_FRAG, u, { blending: THREE.AdditiveBlending })), u);
    }
    this._px = 0.001;
  }

  update(time, camera, atm, viewportH) {
    const scale = viewportH / (2 * Math.tan((camera.fov * Math.PI) / 360));
    const out = 1 - atm.indoor;
    const near = (s, amount) => {
      s.obj.visible = amount > 0.02;
      if (!s.obj.visible) return false;
      const u = s.u;
      u.uTime.value = time;
      u.uAmount.value = amount;
      u.uCam.value.copy(camera.position);
      u.uScale.value = scale;
      u.uSunDir.value.copy(atm.sunDir);
      return true;
    };
    // дождь: светлее дымки, на свету - с оттенком неба
    if (near(this.rain, atm.rain)) {
      this.rain.u.uPx.value = 1 / scale;
      this.rain.u.uColor.value.copy(atm.fogColor).lerp(atm.hemiSky, 0.4).multiplyScalar(1.6);
    }
    if (this.splash && near(this.splash, atm.rain)) this.splash.u.uColor.value.copy(atm.fogColor).lerp(atm.hemiSky, 0.5).multiplyScalar(1.3);
    if (near(this.snow, atm.snow)) this.snow.u.uColor.value.copy(atm.hemiSky).lerp(atm.sunColor, 0.4).multiplyScalar(0.55 + atm.sunIntensity * 0.15 + atm.hemiIntensity * 0.2);
    if (near(this.leaves, atm.leaves)) this.leaves.u.uColor.value.copy(atm.sunColor).multiplyScalar(0.55 + atm.sunIntensity * 0.12);
    if (this.dust && near(this.dust, atm.dust * 0.9)) this.dust.u.uColor.value.copy(atm.sunColor).multiplyScalar(0.55);
    if (this.pollen && near(this.pollen, atm.pollen * 0.8)) this.pollen.u.uColor.value.setRGB(1, 0.95, 0.7).multiplyScalar(0.5);
    // светлячки - только у костра
    const dx = camera.position.x - CAMPFIRE.x, dz = camera.position.z - CAMPFIRE.z;
    const fly = out * (1 - Math.min(1, Math.max(0, (Math.hypot(dx, dz) - 40) / 20)));
    if (near(this.flies, fly)) this.flies.u.uColor.value.setRGB(1.0, 0.92, 0.42);
  }
}
