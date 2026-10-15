// Вода озера. Глубина в каждой точке известна заранее (рельеф - карта высот), поэтому цвет по глубине,
// пена у берега и прозрачность у кромки берутся из запечённой карты глубин.
// Нормаль - сумма бегущих волн и шума: френель-отражение неба, солнечные блики (ярче порога bloom),
// круги вокруг игроков, стоящих в воде. Лёгкие волны вершинами.
// На high (deferred) вода рисуется отдельным проходом после мира по копии кадра (render/post.js):
// преломление дна, поглощение по толщине слоя вдоль луча, экранные отражения берега и облаков.

import * as THREE from 'three';
import { heightAt, LAKE, WATER_Y } from './terrain-data.js';
import { NATURE, MAX_BENDERS } from './nature/shared.js';

const C = (h) => new THREE.Color(h);
const f = (v) => Number(v).toFixed(4);

const SIZE = LAKE.r * 3.6;
const X0 = LAKE.x - SIZE / 2, Z0 = LAKE.z - SIZE / 2;
const MAX_DEPTH = 3.5;

function depthTexture() {
  const res = 128;
  const data = new Uint8Array(res * res);
  for (let j = 0; j < res; j++) {
    for (let i = 0; i < res; i++) {
      const x = X0 + (i / (res - 1)) * SIZE;
      const z = Z0 + (j / (res - 1)) * SIZE;
      const depth = WATER_Y - heightAt(x, z);
      data[j * res + i] = Math.max(0, Math.min(255, Math.round((depth / MAX_DEPTH) * 255)));
    }
  }
  const tex = new THREE.DataTexture(data, res, res, THREE.RedFormat, THREE.UnsignedByteType);
  tex.magFilter = THREE.LinearFilter;
  tex.minFilter = THREE.LinearFilter;
  tex.wrapS = tex.wrapT = THREE.ClampToEdgeWrapping;
  tex.needsUpdate = true;
  return tex;
}

const WAVES = /* glsl */`
uniform float uTime;
uniform sampler2D uDepth;
float wDepth(vec2 p) { return texture2D(uDepth, (p - vec2(${f(X0)}, ${f(Z0)})) / ${f(SIZE)}).r * ${f(MAX_DEPTH)}; }
// крупные волны: высота и производные по x, z
vec3 wSwell(vec2 p) {
  float t = uTime;
  vec3 r = vec3(0.0);
  vec4 A = vec4(0.035, 0.022, 0.014, 0.009);
  vec4 K = vec4(0.42, 0.77, 1.31, 2.1);
  vec2 d0 = normalize(vec2(0.86, 0.5)), d1 = normalize(vec2(-0.3, 0.95)), d2 = normalize(vec2(0.7, -0.7)), d3 = normalize(vec2(-0.9, -0.2));
  vec4 ph = vec4(dot(p, d0) * K.x - t * 1.1, dot(p, d1) * K.y - t * 1.5, dot(p, d2) * K.z - t * 2.0, dot(p, d3) * K.w - t * 2.6);
  vec4 s = sin(ph), c = cos(ph);
  r.x = dot(A, s);
  vec4 g = A * K * c;
  r.y = g.x * d0.x + g.y * d1.x + g.z * d2.x + g.w * d3.x;
  r.z = g.x * d0.y + g.y * d1.y + g.z * d2.y + g.w * d3.y;
  return r;
}
`;

const VERT = /* glsl */`
#include <common>
#include <fog_pars_vertex>
${WAVES}
varying vec3 vWPos;
varying float vViewZ;
void main() {
  vec4 wp = modelMatrix * vec4(position, 1.0);
  float shore = smoothstep(0.02, 0.5, wDepth(wp.xz));
  #ifdef WAVE_VERTS
  wp.y += wSwell(wp.xz).x * shore;
  #endif
  vWPos = wp.xyz;
  vec4 mvPosition = viewMatrix * wp;
  vViewZ = -mvPosition.z;
  gl_Position = projectionMatrix * mvPosition;
  #include <fog_vertex>
}`;

const FRAG = /* glsl */`
#include <common>
#include <fog_pars_fragment>
${WAVES}
uniform vec3 uShallow, uDeep, uFoam;
uniform vec3 uTop, uMid, uHor, uSunColor, uSunDir, uHemi;
uniform float uSunK;
uniform vec4 uBenders[${MAX_BENDERS}];
#ifdef DEFERRED
uniform sampler2D tCopy;
uniform vec2 uRes;
uniform mat4 uProjM;
#endif
varying vec3 vWPos;
varying float vViewZ;

float wh(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float wn(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(wh(i), wh(i + vec2(1.0, 0.0)), f.x), mix(wh(i + vec2(0.0, 1.0)), wh(i + vec2(1.0, 1.0)), f.x), f.y);
}
// мелкая рябь: градиент двух бегущих слоёв шума
vec2 wRipple(vec2 p) {
  float t = uTime;
  vec2 g = vec2(0.0);
  vec2 q1 = p * 1.7 + vec2(t * 0.32, t * 0.21);
  vec2 q2 = p * 3.9 - vec2(t * 0.45, -t * 0.28);
  float e = 0.08;
  g.x += (wn(q1 + vec2(e, 0.0)) - wn(q1 - vec2(e, 0.0))) * 0.5 + (wn(q2 + vec2(e, 0.0)) - wn(q2 - vec2(e, 0.0))) * 0.35;
  g.y += (wn(q1 + vec2(0.0, e)) - wn(q1 - vec2(0.0, e))) * 0.5 + (wn(q2 + vec2(0.0, e)) - wn(q2 - vec2(0.0, e))) * 0.35;
  return g / (2.0 * e) * 0.06;
}

vec3 skyColor(vec3 d) {
  float h = max(d.y, 0.0);
  float s = max(dot(d, uSunDir), 0.0);
  vec3 col = mix(uHor, uMid, smoothstep(0.0, 0.22, h));
  col = mix(col, uTop, smoothstep(0.1, 0.7, h));
  return col + uSunColor * (pow(s, 32.0) * 0.3 + pow(s, 5.0) * 0.1);
}

void main() {
  vec2 p = vWPos.xz;
  float dep = wDepth(p);
  float t = uTime;
  vec3 V = normalize(cameraPosition - vWPos);
  float dist = vViewZ;
  // нормаль: волны + рябь (вдали рябь гасим - иначе муар)
  vec3 sw = wSwell(p);
  vec2 grad = sw.yz * smoothstep(0.02, 0.5, dep);
  grad += wRipple(p) * (1.0 - smoothstep(25.0, 90.0, dist));
  // круги вокруг тех, кто стоит в воде
  for (int i = 0; i < ${MAX_BENDERS}; i++) {
    vec4 b = uBenders[i];
    if (b.w <= 0.0) break;
    if (b.y > ${f(WATER_Y + 0.05)} || b.y < ${f(WATER_Y - 1.6)}) continue;
    vec2 dd = p - b.xz;
    float d = length(dd);
    float ring = cos(d * 11.0 - t * 5.5) * exp(-d * 0.9) * smoothstep(4.5, 0.25, d);
    grad += dd / max(d, 0.05) * ring * 0.22;
  }
  vec3 N = normalize(vec3(-grad.x, 1.0, -grad.y));
  float ndv = max(dot(N, V), 0.0);
  float fres = 0.02 + 0.98 * pow(1.0 - ndv, 5.0);
  vec3 R = reflect(-V, N);
  R.y = abs(R.y);
  vec3 refl = skyColor(R);
  // блик солнца: острый центр (ярче порога bloom) + мягкий ореол
  float rs = max(dot(R, uSunDir), 0.0);
  vec3 spec = uSunColor * (pow(rs, 900.0) * 60.0 + pow(rs, 120.0) * 2.5) * uSunK;
  // цвет толщи по глубине; освещение - солнце и небо
  float light = 0.45 + 0.4 * uSunK + 0.15;
  vec3 body = mix(uShallow, uDeep, smoothstep(0.05, 2.6, dep)) * light * uHemi;
  // пена у кромки: бегущие к берегу полосы, рваные шумом
  float fn = wn(p * 2.2 + vec2(t * 0.15, -t * 0.1)) * 0.6 + wn(p * 5.3 - t * 0.2) * 0.4;
  float bands = sin(dep * 26.0 - t * 2.2 + fn * 3.0) * 0.5 + 0.5;
  float foam = (1.0 - smoothstep(0.02, 0.32, dep)) * smoothstep(0.45, 0.85, bands * 0.6 + fn * 0.55);
  foam = max(foam, 1.0 - smoothstep(0.0, 0.05 + fn * 0.04, dep));
  vec3 col;
  float alpha = 1.0;
#ifdef DEFERRED
  vec2 suv = gl_FragCoord.xy / uRes;
  float sceneZ = texture2D(tCopy, suv).a;
  float thick = max(sceneZ - dist, 0.0);
  // преломление: сдвиг по нормали, без «затекания» предметов перед водой
  vec2 ruv = suv + grad * (0.9 / max(dist, 1.0)) * min(thick, 2.0);
  vec4 under = texture2D(tCopy, ruv);
  if (under.a < dist) under = texture2D(tCopy, suv);
  // поглощение (красный гаснет первым) по толщине слоя вдоль луча
  vec3 absorb = exp(-min(thick, 30.0) * vec3(0.5, 0.17, 0.12));
  vec3 refr = under.rgb * absorb + body * (1.0 - absorb);
  // экранные отражения берега и неба (с облаками), промах - аналитическое небо
  vec3 Pv = (viewMatrix * vec4(vWPos, 1.0)).xyz;
  vec3 Rv = normalize((viewMatrix * vec4(R, 0.0)).xyz);
  float stepLen = 0.35 + dist * 0.01;
  vec3 rp = Pv;
  float hitK = 0.0;
  vec2 huv = vec2(0.0);
  for (int i = 0; i < 26; i++) {
    rp += Rv * stepLen;
    stepLen *= 1.16;
    vec4 cp = uProjM * vec4(rp, 1.0);
    vec2 uv = cp.xy / cp.w * 0.5 + 0.5;
    if (cp.w <= 0.0 || uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) break;
    float sz = texture2D(tCopy, uv).a;
    float rz = -rp.z;
    if (rz > sz && rz - sz < stepLen * 1.5 + 0.6 && sz < 1000.0) {
      // уточнение
      vec3 a = rp - Rv * stepLen, b = rp;
      for (int k = 0; k < 4; k++) {
        vec3 m = (a + b) * 0.5;
        vec4 mc = uProjM * vec4(m, 1.0);
        vec2 mu = mc.xy / mc.w * 0.5 + 0.5;
        if (-m.z > texture2D(tCopy, mu).a) b = m; else a = m;
      }
      vec4 bc = uProjM * vec4(b, 1.0);
      huv = bc.xy / bc.w * 0.5 + 0.5;
      vec2 e = smoothstep(0.0, 0.08, huv) * smoothstep(1.0, 0.92, huv);
      hitK = e.x * e.y;
      break;
    }
  }
  if (hitK > 0.0) refl = mix(refl, texture2D(tCopy, huv).rgb, hitK);
  else {
    // небо с облаками - из самого кадра по направлению отражения
    vec4 dc = uProjM * vec4(Pv + Rv * 400.0, 1.0);
    vec2 du = dc.xy / dc.w * 0.5 + 0.5;
    if (dc.w > 0.0 && du.x > 0.0 && du.x < 1.0 && du.y > 0.0 && du.y < 1.0) {
      vec4 sky = texture2D(tCopy, du);
      vec2 e = smoothstep(0.0, 0.1, du) * smoothstep(1.0, 0.9, du);
      if (sky.a > 1000.0) refl = mix(refl, sky.rgb, e.x * e.y);
    }
  }
  col = mix(refr, refl, fres) + spec * (1.0 - foam);
  // мягкая кромка: где слой тонкий - пена и плавный переход в дно
  foam = max(foam, (1.0 - smoothstep(0.0, 0.22 + fn * 0.15, thick)) * 0.8);
  col = mix(col, uFoam * light * uHemi * 1.15, clamp(foam, 0.0, 1.0) * 0.85);
#else
  alpha = smoothstep(0.0, 0.07, dep / ${f(MAX_DEPTH)} * 4.0);
  vec3 base = mix(body, refl, fres);
  col = base + spec * (1.0 - foam);
  col = mix(col, uFoam * light * uHemi * 1.15, clamp(foam, 0.0, 1.0) * 0.8);
  alpha = clamp(max(alpha * mix(0.78, 0.96, smoothstep(0.1, 1.5, dep)), foam * 0.9) + fres * 0.2, 0.0, 1.0);
#endif
  gl_FragColor = vec4(col, alpha);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
  #include <fog_fragment>
}`;

export class Water {
  // deferred: вода рисуется постобработкой (high); segments - деление плоскости для волн вершинами
  constructor({ deferred = false, segments = 64 } = {}) {
    this.deferred = deferred;
    const uniforms = THREE.UniformsUtils.merge([
      THREE.UniformsLib.fog,
      {
        uDepth: { value: depthTexture() },
        uShallow: { value: C('#5fd6c8') },
        uDeep: { value: C('#13668c') },
        uFoam: { value: C('#f4fbff') },
        uTop: { value: C('#3f8fe0') },
        uMid: { value: C('#84c0f2') },
        uHor: { value: C('#bfdcf3') },
        uSunColor: { value: C('#ffe0a8') },
        uSunDir: { value: new THREE.Vector3(0, 1, 0) },
        uHemi: { value: new THREE.Color(1, 1, 1) },
        uSunK: { value: 1 },
        tCopy: { value: null },
        uRes: { value: new THREE.Vector2(1, 1) },
        uProjM: { value: new THREE.Matrix4() },
      },
    ]);
    uniforms.uTime = NATURE.uTime;
    uniforms.uBenders = NATURE.uBenders;
    const defines = {};
    if (deferred) defines.DEFERRED = '';
    if (segments > 1) defines.WAVE_VERTS = '';
    this.material = new THREE.ShaderMaterial({
      uniforms, defines, vertexShader: VERT, fragmentShader: FRAG, fog: true,
      transparent: !deferred, depthWrite: deferred,
    });
    const geo = new THREE.PlaneGeometry(SIZE, SIZE, segments, segments);
    geo.rotateX(-Math.PI / 2);
    this.mesh = new THREE.Mesh(geo, this.material);
    this.mesh.position.set(LAKE.x, WATER_Y, LAKE.z);
    this.mesh.renderOrder = 1;
    this.mesh.name = 'water';
    this.mesh.geometry.computeBoundingSphere();
    this.mesh.updateMatrixWorld(true);
    // для deferred - своя сцена с тем же туманом (туман назначает World)
    this.scene = deferred ? new THREE.Scene() : null;
    if (this.scene) this.scene.add(this.mesh);
    this._sphere = new THREE.Sphere();
    this._frustum = new THREE.Frustum();
    this._m = new THREE.Matrix4();
  }

  // object для добавления в мир (forward) или null
  get forwardObject() { return this.deferred ? null : this.mesh; }

  update(atm) {
    const u = this.material.uniforms;
    u.uTop.value.copy(atm.skyTop);
    u.uMid.value.copy(atm.skyMid);
    u.uHor.value.copy(atm.fogColor);
    u.uSunColor.value.copy(atm.sunColor);
    u.uSunDir.value.copy(atm.sunDir);
    u.uSunK.value = atm.sunIntensity / 3.2;
    u.uHemi.value.copy(atm.hemiSky).lerp(atm.sunColor, 0.4).multiplyScalar(Math.min(1.25, 0.35 + atm.hemiIntensity * 0.5));
  }

  // Вода в кадре и не слишком далеко (иначе копию кадра не делаем).
  deferredVisible(camera) {
    if (!this.deferred) return false;
    this._sphere.copy(this.mesh.geometry.boundingSphere).applyMatrix4(this.mesh.matrixWorld);
    if (this._sphere.distanceToPoint(camera.position) > 230) return false;
    camera.updateMatrixWorld();
    this._m.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse);
    this._frustum.setFromProjectionMatrix(this._m);
    return this._frustum.intersectsSphere(this._sphere);
  }

  renderDeferred(renderer, camera, copyTex, w, h, target) {
    const u = this.material.uniforms;
    u.tCopy.value = copyTex;
    u.uRes.value.set(w, h);
    u.uProjM.value.copy(camera.projectionMatrix);
    renderer.setRenderTarget(target);
    renderer.render(this.scene, camera);
  }
}
