// Постобработка (high/medium; на low мир рисуется напрямую).
// Сцена рендерится в HDR-буфер (half float) с текстурой глубины, затем:
//   [high] вода с отражениями (water.js, по копии кадра) -> AO по глубине основного прохода (полразрешения,
//   билатеральное размытие) -> руки от 1-го лица в свой буфер -> bloom (цепочка мипов) -> сведение:
//   AO, руки, bloom, экспозиция, насыщенность, тонмаппинг, тёплые света/холодные тени, lift/gamma/gain,
//   виньетка, дизеринг -> SMAA/FXAA.
// AO считается по глубине основного прохода, а не отдельным рендером нормалей: так не удваиваются draw calls,
// и листва с альфа-тестом и качающаяся трава дают правильную глубину (без ореолов от «квадратов» карточек).

import * as THREE from 'three';
import { SMAAPass } from 'three/addons/postprocessing/SMAAPass.js';
import { FXAAShader } from 'three/addons/shaders/FXAAShader.js';

const VERT = /* glsl */`
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.0, 1.0);
}`;

// Полноэкранный треугольник: один на все проходы.
const TRI = new THREE.BufferGeometry();
TRI.setAttribute('position', new THREE.Float32BufferAttribute([-1, -1, 0, 3, -1, 0, -1, 3, 0], 3));
TRI.setAttribute('uv', new THREE.Float32BufferAttribute([0, 0, 2, 0, 0, 2], 2));

class Quad {
  constructor() {
    this.mesh = new THREE.Mesh(TRI, null);
    this.mesh.frustumCulled = false;
    this.cam = new THREE.Camera();
  }

  draw(renderer, mat, target, clear = false) {
    this.mesh.material = mat;
    renderer.setRenderTarget(target);
    if (clear) renderer.clear(true, false, false);
    renderer.render(this.mesh, this.cam);
  }
}

function pass(fragmentShader, uniforms, extra = {}) {
  return new THREE.ShaderMaterial({
    uniforms, vertexShader: VERT, fragmentShader, depthTest: false, depthWrite: false, toneMapped: false, ...extra,
  });
}

const GLSL_DEPTH = /* glsl */`
uniform vec4 uProj; // 1/P00, 1/P11, near, far
float cozyViewZ(float d) { return (uProj.z * uProj.w) / ((uProj.w - uProj.z) * d - uProj.w); } // < 0
vec3 cozyViewPos(vec2 uv, float z) { return vec3((uv * 2.0 - 1.0) * uProj.xy * -z, z); }
`;

// ---------- AO: SAO-подобная выборка по спирали, нормаль из глубины ----------
const AO_FRAG = /* glsl */`
uniform sampler2D tDepth;
uniform vec2 uDepthTexel;
uniform vec2 uAOSize;
uniform float uRadius, uIntensity, uBias, uScale, uMaxPx, uFadeFar;
varying vec2 vUv;
${GLSL_DEPTH}
float zAt(vec2 uv) { return cozyViewZ(texture2D(tDepth, uv).r); }
// позиции восстанавливаем строго в центрах текселей глубины: иначе на пологих плоскостях - полосы
vec2 snapUv(vec2 uv) { return (floor(uv / uDepthTexel) + 0.5) * uDepthTexel; }
#define SAMPLES ${'${SAMPLES}'}
void main() {
  vec2 uv0 = snapUv(vUv);
  float d = texture2D(tDepth, uv0).r;
  if (d >= 0.99999) { gl_FragColor = vec4(1.0, uProj.w, 0.0, 1.0); return; }
  float z = cozyViewZ(d);
  vec3 P = cozyViewPos(uv0, z);
  // нормаль: с той стороны, где перепад глубины меньше (без ореолов на краях)
  vec2 tx = uDepthTexel;
  vec3 pr = cozyViewPos(uv0 + vec2(tx.x, 0.0), zAt(uv0 + vec2(tx.x, 0.0)));
  vec3 pl = cozyViewPos(uv0 - vec2(tx.x, 0.0), zAt(uv0 - vec2(tx.x, 0.0)));
  vec3 pt = cozyViewPos(uv0 + vec2(0.0, tx.y), zAt(uv0 + vec2(0.0, tx.y)));
  vec3 pb = cozyViewPos(uv0 - vec2(0.0, tx.y), zAt(uv0 - vec2(0.0, tx.y)));
  vec3 dx = abs(pr.z - P.z) < abs(P.z - pl.z) ? pr - P : P - pl;
  vec3 dy = abs(pt.z - P.z) < abs(P.z - pb.z) ? pt - P : P - pb;
  vec3 N = normalize(cross(dx, dy));
  float rPx = min(uScale * uRadius / -z, uMaxPx);
  if (rPx < 1.0) { gl_FragColor = vec4(1.0, -z, 0.0, 1.0); return; }
  float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
  float r2 = uRadius * uRadius;
  float sum = 0.0;
  for (int i = 0; i < SAMPLES; i++) {
    float a = (float(i) + 0.5) / float(SAMPLES);
    float ang = a * 43.98 + noise * 6.2832; // 7 витков
    vec2 off = vec2(cos(ang), sin(ang)) * (a * a * 0.85 + 0.15) * rPx;
    vec2 suv = snapUv(uv0 + off / uAOSize);
    vec3 Q = cozyViewPos(suv, zAt(suv));
    vec3 v = Q - P;
    float vv = dot(v, v);
    float vn = dot(v, N);
    float fo = max(r2 - vv, 0.0);
    sum += fo * fo * fo * max((vn - uBias * -z * 0.01) / (0.01 + vv), 0.0);
  }
  float ao = max(0.0, 1.0 - sum * uIntensity / (r2 * r2 * r2) * (5.0 / float(SAMPLES)));
  ao = mix(ao, 1.0, smoothstep(uFadeFar * 0.55, uFadeFar, -z));
  gl_FragColor = vec4(ao, -z, 0.0, 1.0);
}`;

// Билатеральное размытие AO (по глубине в канале G).
const AO_BLUR_FRAG = /* glsl */`
uniform sampler2D tAO;
uniform vec2 uDir;
varying vec2 vUv;
void main() {
  vec4 c = texture2D(tAO, vUv);
  float z = c.g;
  float sum = c.r * 0.2, ws = 0.2;
  for (int i = 1; i <= 6; i++) {
    float g = exp(-float(i * i) / 18.0) * 0.2;
    vec4 a = texture2D(tAO, vUv + uDir * float(i));
    vec4 b = texture2D(tAO, vUv - uDir * float(i));
    float wa = g * max(0.0, 1.0 - abs(a.g - z) / (z * 0.04 + 0.05));
    float wb = g * max(0.0, 1.0 - abs(b.g - z) / (z * 0.04 + 0.05));
    sum += a.r * wa + b.r * wb;
    ws += wa + wb;
  }
  gl_FragColor = vec4(sum / ws, z, 0.0, 1.0);
}`;

// ---------- bloom ----------
const BLOOM_PRE_FRAG = /* glsl */`
uniform sampler2D tScene;
uniform sampler2D tHands;
uniform float uHands;
uniform vec2 uTexel;
uniform vec3 uCurve; // порог, мягкое колено, экспозиция
varying vec2 vUv;
vec3 fetch(vec2 uv) {
  vec3 c = texture2D(tScene, uv).rgb;
  if (uHands > 0.5) { vec4 h = texture2D(tHands, uv); c = mix(c, h.rgb, h.a); }
  return min(c * uCurve.z, vec3(24.0));
}
void main() {
  vec3 c = (fetch(vUv + uTexel * vec2(-1.0, -1.0)) + fetch(vUv + uTexel * vec2(1.0, -1.0))
    + fetch(vUv + uTexel * vec2(-1.0, 1.0)) + fetch(vUv + uTexel * vec2(1.0, 1.0))) * 0.25;
  float br = max(c.r, max(c.g, c.b));
  float knee = uCurve.y;
  float soft = clamp(br - uCurve.x + knee, 0.0, 2.0 * knee);
  soft = soft * soft / (4.0 * knee + 1e-4);
  c *= max(soft, br - uCurve.x) / max(br, 1e-4);
  gl_FragColor = vec4(c, 1.0);
}`;

// 13 выборок (как в CoD: AW) - без мерцания на мелких ярких точках
const BLOOM_DOWN_FRAG = /* glsl */`
uniform sampler2D tSrc;
uniform vec2 uTexel;
varying vec2 vUv;
vec3 t(float x, float y) { return texture2D(tSrc, vUv + uTexel * vec2(x, y)).rgb; }
void main() {
  vec3 c = t(0.0, 0.0) * 0.125;
  c += (t(-1.0, -1.0) + t(1.0, -1.0) + t(-1.0, 1.0) + t(1.0, 1.0)) * 0.125;
  c += (t(-2.0, -2.0) + t(2.0, -2.0) + t(-2.0, 2.0) + t(2.0, 2.0)) * 0.03125;
  c += (t(0.0, -2.0) + t(-2.0, 0.0) + t(2.0, 0.0) + t(0.0, 2.0)) * 0.0625;
  gl_FragColor = vec4(c, 1.0);
}`;

const BLOOM_UP_FRAG = /* glsl */`
uniform sampler2D tSrc;
uniform vec2 uTexel;
uniform float uWeight;
varying vec2 vUv;
vec3 t(float x, float y) { return texture2D(tSrc, vUv + uTexel * vec2(x, y)).rgb; }
void main() {
  vec3 c = t(0.0, 0.0) * 4.0;
  c += (t(-1.0, 0.0) + t(1.0, 0.0) + t(0.0, -1.0) + t(0.0, 1.0)) * 2.0;
  c += t(-1.0, -1.0) + t(1.0, -1.0) + t(-1.0, 1.0) + t(1.0, 1.0);
  gl_FragColor = vec4(c * (uWeight / 16.0), 1.0);
}`;

// ---------- лучи солнца (сквозь листву и края облаков) ----------
// Маска: видимое небо рядом с солнцем (глубина = дальняя плоскость), четверть разрешения.
const RAYS_MASK_FRAG = /* glsl */`
uniform sampler2D tDepth;
uniform sampler2D tScene;
uniform vec2 uSun;      // солнце на экране (uv)
uniform vec2 uAspect;
uniform vec2 uTexel;    // 1/размер буфера глубины
varying vec2 vUv;
void main() {
  float sky = 0.0;
  vec3 col = vec3(0.0);
  for (int i = 0; i < 4; i++) {
    vec2 o = vec2(float(i - (i / 2) * 2) - 0.5, float(i / 2) - 0.5) * uTexel * 2.0;
    float d = texture2D(tDepth, vUv + o).r;
    float k = step(0.99999, d);
    sky += k;
    col += texture2D(tScene, vUv + o).rgb * k;
  }
  sky *= 0.25; col *= 0.25;
  float r = length((vUv - uSun) * uAspect);
  float near = exp(-r * r * 14.0);
  // ярче там, где небо светлее (облака у солнца перекрывают лучи частично)
  float br = clamp(dot(col, vec3(0.3, 0.5, 0.2)) * 0.9, 0.0, 2.0);
  gl_FragColor = vec4(vec3(sky * near * (0.35 + br)), 1.0);
}`;

const RAYS_BLUR_FRAG = /* glsl */`
uniform sampler2D tSrc;
uniform vec2 uSun;
uniform float uLen;
varying vec2 vUv;
void main() {
  vec2 d = (uSun - vUv) * uLen / 24.0;
  vec2 uv = vUv;
  float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
  uv += d * jitter;
  vec3 sum = vec3(0.0);
  float w = 1.0, ws = 0.0;
  for (int i = 0; i < 24; i++) {
    sum += texture2D(tSrc, uv).rgb * w;
    ws += w;
    w *= 0.955;
    uv += d;
  }
  gl_FragColor = vec4(sum / ws, 1.0);
}`;

// ---------- сведение ----------
const COMPOSITE_FRAG = /* glsl */`
uniform sampler2D tScene;
uniform sampler2D tBloom;
uniform sampler2D tAO;
uniform sampler2D tDepth;
uniform sampler2D tHands;
uniform sampler2D tRays;
uniform vec3 uRays; // цвет и сила лучей (0 - выкл.)
uniform vec4 uFlags; // AO, руки, bloom, отладка
uniform vec2 uAOSize;
uniform vec2 uRes;
uniform float uTime;
uniform vec4 uTone; // экспозиция, насыщенность, контраст, виньетка
uniform vec4 uMix;  // сила bloom, сила AO, -, -
uniform vec3 uLift, uGamma, uGain, uShadowTint, uHighTint;
varying vec2 vUv;
${GLSL_DEPTH}

float aoAt(vec2 uv) {
  float zf = -cozyViewZ(texture2D(tDepth, uv).r);
  vec2 p = uv * uAOSize - 0.5;
  vec2 i0 = floor(p), fr = p - i0;
  vec2 t = 1.0 / uAOSize, b = (i0 + 0.5) * t;
  vec4 s00 = texture2D(tAO, b), s10 = texture2D(tAO, b + vec2(t.x, 0.0));
  vec4 s01 = texture2D(tAO, b + vec2(0.0, t.y)), s11 = texture2D(tAO, b + t);
  vec4 w = vec4((1.0 - fr.x) * (1.0 - fr.y), fr.x * (1.0 - fr.y), (1.0 - fr.x) * fr.y, fr.x * fr.y);
  vec4 dz = abs(vec4(s00.g, s10.g, s01.g, s11.g) - zf) / max(zf, 0.1);
  w *= 1.0 / (0.02 + dz * 60.0);
  float sw = w.x + w.y + w.z + w.w;
  return sw > 1e-6 ? dot(w, vec4(s00.r, s10.r, s01.r, s11.r)) / sw : 1.0;
}

// Khronos PBR Neutral (как NeutralToneMapping в three)
vec3 neutralTone(vec3 color) {
  const float startC = 0.76;
  const float desat = 0.15;
  float x = min(color.r, min(color.g, color.b));
  float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
  color -= offset;
  float peak = max(color.r, max(color.g, color.b));
  if (peak < startC) return color;
  float d = 1.0 - startC;
  float newPeak = 1.0 - d * d / (peak + d - startC);
  color *= newPeak / peak;
  float g = 1.0 - 1.0 / (desat * (peak - newPeak) + 1.0);
  return mix(color, vec3(newPeak), g);
}

vec3 toSRGB(vec3 c) {
  c = clamp(c, 0.0, 1.0);
  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

void main() {
  vec3 c = texture2D(tScene, vUv).rgb;
  if (uFlags.x > 0.5) c *= mix(1.0, aoAt(vUv), uMix.y);
  if (uFlags.y > 0.5) { vec4 h = texture2D(tHands, vUv); c = mix(c, h.rgb, h.a); }
  if (uFlags.z > 0.5) c += texture2D(tBloom, vUv).rgb * uMix.x;
  if (uRays.r + uRays.g + uRays.b > 0.0) c += texture2D(tRays, vUv).r * uRays;
  c *= uTone.x;
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = max(mix(vec3(l), c, uTone.y), 0.0);
  c = toSRGB(neutralTone(c));
  // холодные тени, тёплые света
  float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c += uShadowTint * (1.0 - smoothstep(0.0, 0.5, lum)) + uHighTint * smoothstep(0.5, 1.0, lum);
  // lift / gamma / gain
  c = clamp(c * uGain + uLift * (1.0 - c), 0.0, 1.0);
  c = pow(c, 1.0 / uGamma);
  // мягкий S-контраст вокруг середины
  c = clamp(mix(c, c * c * (3.0 - 2.0 * c), uTone.z), 0.0, 1.0);
  // виньетка
  vec2 q = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  c *= mix(1.0, smoothstep(1.15, 0.35, length(q)), uTone.w);
  // дизеринг против полос в небе
  c += (fract(sin(dot(gl_FragCoord.xy + fract(uTime) * 17.0, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;
  // отладка (?postdebug=ao|bloom)
  if (uFlags.w > 1.5) c = toSRGB(texture2D(tBloom, vUv).rgb);
  else if (uFlags.w > 0.5) c = vec3(uFlags.x > 0.5 ? aoAt(vUv) : 1.0);
  gl_FragColor = vec4(c, 1.0);
}`;

// Копия кадра для воды: цвет + линейная глубина в альфе.
const COPY_FRAG = /* glsl */`
uniform sampler2D tScene;
uniform sampler2D tDepth;
varying vec2 vUv;
${GLSL_DEPTH}
void main() {
  float d = texture2D(tDepth, vUv).r;
  gl_FragColor = vec4(texture2D(tScene, vUv).rgb, d >= 0.99999 ? 1e4 : -cozyViewZ(d));
}`;

// доля крупных (размытых) уровней bloom относительно мелких
const BLOOM_SCATTER = 0.72;

const hdr = (w, h, extra = {}) => new THREE.WebGLRenderTarget(w, h, {
  type: THREE.HalfFloatType, format: THREE.RGBAFormat, depthBuffer: false, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter,
  generateMipmaps: false, ...extra,
});

// Можно ли рисовать в half float (WebGL2 + EXT_color_buffer_float); иначе - без постобработки.
export function postSupported(renderer) {
  return renderer.capabilities.isWebGL2 !== false && renderer.extensions.has('EXT_color_buffer_float');
}

export class PostFX {
  // opts: { ao: bool, aoSamples, bloomLevels, bloomStart (делитель разрешения), aa: 'smaa' | 'fxaa' | null }
  constructor(renderer, opts) {
    this.renderer = renderer;
    this.opts = opts;
    this.quad = new Quad();
    this.w = 1; this.h = 1;
    this.handsOn = false;
    this.time = 0;

    const depthTex = opts.ao || opts.water ? new THREE.DepthTexture(1, 1, THREE.UnsignedIntType) : null;
    this.rtScene = hdr(1, 1, { depthBuffer: true, depthTexture: depthTex, minFilter: THREE.NearestFilter, magFilter: THREE.NearestFilter });
    if (depthTex) {
      depthTex.minFilter = depthTex.magFilter = THREE.NearestFilter;
    }
    // руки - в свой буфер, если есть AO (иначе - поверх сцены с очисткой глубины)
    this.rtHands = opts.ao ? hdr(1, 1, { depthBuffer: true }) : null;
    this.rtLDR = opts.aa ? new THREE.WebGLRenderTarget(1, 1, { depthBuffer: false, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter }) : null;

    const projU = { value: new THREE.Vector4() };
    this.projU = projU;
    if (opts.ao) {
      this.rtAO = hdr(1, 1, { minFilter: THREE.NearestFilter, magFilter: THREE.NearestFilter });
      this.rtAO2 = hdr(1, 1, { minFilter: THREE.NearestFilter, magFilter: THREE.NearestFilter });
      this.aoMat = pass(AO_FRAG.replace('${SAMPLES}', String(opts.aoSamples || 12)), {
        tDepth: { value: depthTex }, uDepthTexel: { value: new THREE.Vector2() }, uAOSize: { value: new THREE.Vector2() },
        uProj: projU, uRadius: { value: 0.9 }, uIntensity: { value: 1.0 }, uBias: { value: 1.2 }, uScale: { value: 300 },
        uMaxPx: { value: 48 }, uFadeFar: { value: 90 },
      });
      this.blurMat = pass(AO_BLUR_FRAG, { tAO: { value: null }, uDir: { value: new THREE.Vector2() } });
    }

    // bloom: мипы от 1/bloomStart разрешения
    this.bloomLevels = opts.bloomLevels || 5;
    this.bloomStart = opts.bloomStart || 2;
    // сумма уровней с затуханием крупных: нормируем, чтобы сила не зависела от их числа
    let norm = 0;
    for (let i = 0; i < this.bloomLevels; i++) norm += BLOOM_SCATTER ** i;
    this.bloomNorm = 1 / norm;
    this.mips = [];
    for (let i = 0; i < this.bloomLevels; i++) this.mips.push(hdr(1, 1));
    this.preMat = pass(BLOOM_PRE_FRAG, {
      tScene: { value: this.rtScene.texture }, tHands: { value: this.rtHands?.texture ?? null }, uHands: { value: 0 },
      uTexel: { value: new THREE.Vector2() }, uCurve: { value: new THREE.Vector3(1.4, 0.5, 1) },
    });
    this.downMat = pass(BLOOM_DOWN_FRAG, { tSrc: { value: null }, uTexel: { value: new THREE.Vector2() } });
    this.upMat = pass(BLOOM_UP_FRAG, { tSrc: { value: null }, uTexel: { value: new THREE.Vector2() }, uWeight: { value: 1 } }, {
      blending: THREE.CustomBlending, blendEquation: THREE.AddEquation, blendSrc: THREE.OneFactor, blendDst: THREE.OneFactor,
    });

    this.compMat = pass(COMPOSITE_FRAG, {
      tScene: { value: this.rtScene.texture }, tBloom: { value: this.mips[0].texture }, tAO: { value: this.rtAO?.texture ?? null },
      tDepth: { value: depthTex }, tHands: { value: this.rtHands?.texture ?? null },
      uFlags: { value: new THREE.Vector4() }, uAOSize: { value: new THREE.Vector2(1, 1) }, uRes: { value: new THREE.Vector2(1, 1) },
      uTime: { value: 0 }, uProj: projU, tRays: { value: null }, uRays: { value: new THREE.Vector3() },
      uTone: { value: new THREE.Vector4(1, 1.1, 0.1, 0.25) }, uMix: { value: new THREE.Vector4(0.6, 0.8, 0, 0) },
      uLift: { value: new THREE.Vector3() }, uGamma: { value: new THREE.Vector3(1, 1, 1) }, uGain: { value: new THREE.Vector3(1, 1, 1) },
      uShadowTint: { value: new THREE.Vector3() }, uHighTint: { value: new THREE.Vector3() },
    });

    if (opts.ao) {
      // лучи солнца - там же, где есть текстура глубины (high)
      this.rtRays = hdr(1, 1);
      this.rtRays2 = hdr(1, 1);
      this.raysMaskMat = pass(RAYS_MASK_FRAG, {
        tDepth: { value: depthTex }, tScene: { value: this.rtScene.texture }, uSun: { value: new THREE.Vector2() },
        uAspect: { value: new THREE.Vector2(1, 1) }, uTexel: { value: new THREE.Vector2() },
      });
      this.raysBlurMat = pass(RAYS_BLUR_FRAG, { tSrc: { value: null }, uSun: { value: new THREE.Vector2() }, uLen: { value: 0.6 } });
      this.compMat.uniforms.tRays.value = this.rtRays.texture;
      this._sun = new THREE.Vector3();
      this._fwd = new THREE.Vector3();
    }

    if (opts.water) {
      this.rtCopy = hdr(1, 1, { minFilter: THREE.NearestFilter, magFilter: THREE.NearestFilter });
      this.copyMat = pass(COPY_FRAG, { tScene: { value: this.rtScene.texture }, tDepth: { value: depthTex }, uProj: projU });
    }

    if (opts.aa === 'smaa') {
      this.smaa = new SMAAPass(1, 1);
      this.smaa.renderToScreen = true;
    } else if (opts.aa === 'fxaa') {
      this.fxaaMat = new THREE.ShaderMaterial({
        uniforms: THREE.UniformsUtils.clone(FXAAShader.uniforms), vertexShader: FXAAShader.vertexShader, fragmentShader: FXAAShader.fragmentShader,
        depthTest: false, depthWrite: false, toneMapped: false,
      });
    }
    this.stats = { passes: 0 };
    this.debug = typeof location !== 'undefined' ? new URLSearchParams(location.search).get('postdebug') : null;
  }

  setSize(w, h) {
    w = Math.max(1, Math.round(w)); h = Math.max(1, Math.round(h));
    if (w === this.w && h === this.h) return;
    this.w = w; this.h = h;
    this.rtScene.setSize(w, h);
    this.rtHands?.setSize(w, h);
    this.rtLDR?.setSize(w, h);
    this.rtCopy?.setSize(w, h);
    if (this.rtAO) {
      const aw = Math.max(1, Math.ceil(w / 2)), ah = Math.max(1, Math.ceil(h / 2));
      this.rtAO.setSize(aw, ah);
      this.rtAO2.setSize(aw, ah);
      this.aoMat.uniforms.uDepthTexel.value.set(1 / w, 1 / h);
      this.aoMat.uniforms.uAOSize.value.set(aw, ah);
      this.compMat.uniforms.uAOSize.value.set(aw, ah);
    }
    if (this.rtRays) {
      const rw = Math.max(1, Math.ceil(w / 4)), rh = Math.max(1, Math.ceil(h / 4));
      this.rtRays.setSize(rw, rh);
      this.rtRays2.setSize(rw, rh);
      this.raysMaskMat.uniforms.uTexel.value.set(1 / w, 1 / h);
      this.raysMaskMat.uniforms.uAspect.value.set(w / h, 1);
    }
    let mw = Math.max(1, Math.round(w / this.bloomStart)), mh = Math.max(1, Math.round(h / this.bloomStart));
    for (const m of this.mips) {
      m.setSize(mw, mh);
      mw = Math.max(1, Math.round(mw / 2)); mh = Math.max(1, Math.round(mh / 2));
    }
    this.preMat.uniforms.uTexel.value.set(0.5 / w, 0.5 / h);
    this.compMat.uniforms.uRes.value.set(w, h);
    this.smaa?.setSize(w, h);
    if (this.fxaaMat) this.fxaaMat.uniforms.resolution.value.set(1 / w, 1 / h);
  }

  _proj(camera) {
    const e = camera.projectionMatrix.elements;
    this.projU.value.set(1 / e[0], 1 / e[5], camera.near, camera.far);
  }

  // scene/camera - мир; hands - { scene, camera } или null; water - объект воды (water.js) или null;
  // grade - параметры цвета из Atmosphere (atm.grade).
  render(scene, camera, { hands = null, water = null, grade, dt = 0 } = {}) {
    const r = this.renderer, q = this.quad;
    this.time += dt;
    let passes = 0;
    const prevAuto = r.autoClear;
    r.autoClear = false;
    this._proj(camera);

    // 1. мир
    r.setRenderTarget(this.rtScene);
    r.clear(true, true, false);
    r.render(scene, camera);
    passes++;

    // 2. вода с отражениями и преломлением (по копии кадра), пишет глубину - AO видит её поверхность
    if (water && this.copyMat && water.deferredVisible(camera)) {
      q.draw(r, this.copyMat, this.rtCopy);
      water.renderDeferred(r, camera, this.rtCopy.texture, this.w, this.h, this.rtScene);
      passes += 2;
    }

    // 3. AO (полразрешения) + размытие
    const ao = !!this.aoMat && grade.ao > 0.01;
    if (ao) {
      const u = this.aoMat.uniforms;
      u.uScale.value = (this.rtAO.height * 0.5) * camera.projectionMatrix.elements[5];
      u.uRadius.value = grade.aoRadius;
      u.uIntensity.value = grade.aoIntensity;
      q.draw(r, this.aoMat, this.rtAO);
      const b = this.blurMat.uniforms;
      b.tAO.value = this.rtAO.texture; b.uDir.value.set(1 / this.rtAO.width, 0);
      q.draw(r, this.blurMat, this.rtAO2);
      b.tAO.value = this.rtAO2.texture; b.uDir.value.set(0, 1 / this.rtAO.height);
      q.draw(r, this.blurMat, this.rtAO);
      passes += 3;
    }

    // 4. руки
    let handsInBuffer = false;
    if (hands) {
      if (this.rtHands) {
        r.setRenderTarget(this.rtHands);
        r.setClearColor(0x000000, 0);
        r.clear(true, true, false);
        r.render(hands.scene, hands.camera);
        handsInBuffer = true;
      } else {
        r.setRenderTarget(this.rtScene);
        r.clearDepth();
        r.render(hands.scene, hands.camera);
      }
      passes++;
    }

    // 5. bloom
    const bloom = grade.bloom > 0.001;
    if (bloom) {
      const pu = this.preMat.uniforms;
      pu.uHands.value = handsInBuffer ? 1 : 0;
      pu.uCurve.value.set(grade.bloomThreshold, grade.bloomKnee, grade.exposure);
      q.draw(r, this.preMat, this.mips[0]);
      for (let i = 1; i < this.mips.length; i++) {
        const src = this.mips[i - 1];
        this.downMat.uniforms.tSrc.value = src.texture;
        this.downMat.uniforms.uTexel.value.set(1 / src.width, 1 / src.height);
        q.draw(r, this.downMat, this.mips[i]);
      }
      for (let i = this.mips.length - 1; i > 0; i--) {
        const src = this.mips[i];
        this.upMat.uniforms.tSrc.value = src.texture;
        this.upMat.uniforms.uTexel.value.set(1 / src.width, 1 / src.height);
        this.upMat.uniforms.uWeight.value = BLOOM_SCATTER;
        q.draw(r, this.upMat, this.mips[i - 1]);
      }
      passes += this.mips.length * 2 - 1;
    }

    // 5b. лучи солнца: только когда солнце перед камерой
    const cu = this.compMat.uniforms;
    cu.uRays.value.set(0, 0, 0);
    if (this.rtRays && grade.rays > 0.01 && grade.sunDir) {
      camera.getWorldDirection(this._fwd);
      const facing = this._fwd.dot(grade.sunDir);
      const sp = facing > 0.3 ? this._sun.copy(camera.position).addScaledVector(grade.sunDir, 1000).project(camera) : null;
      const su = sp ? sp.x * 0.5 + 0.5 : 0, sv = sp ? sp.y * 0.5 + 0.5 : 0;
      // солнце в кадре или рядом с его краем
      const off = sp ? Math.max(0, Math.abs(su - 0.5) - 0.5, Math.abs(sv - 0.5) - 0.5) : 1;
      if (off < 0.25) {
        this.raysMaskMat.uniforms.uSun.value.set(su, sv);
        q.draw(r, this.raysMaskMat, this.rtRays);
        const bu = this.raysBlurMat.uniforms;
        bu.uSun.value.set(su, sv);
        bu.tSrc.value = this.rtRays.texture; bu.uLen.value = 0.55;
        q.draw(r, this.raysBlurMat, this.rtRays2);
        bu.tSrc.value = this.rtRays2.texture; bu.uLen.value = 0.25;
        q.draw(r, this.raysBlurMat, this.rtRays);
        const k = grade.rays * (1 - off / 0.25);
        cu.uRays.value.set(grade.sunColor.r * k, grade.sunColor.g * k, grade.sunColor.b * k);
        passes += 3;
      }
    }

    // 6. сведение и цвет
    cu.uFlags.value.set(ao ? 1 : 0, handsInBuffer ? 1 : 0, bloom ? 1 : 0, this.debug === 'ao' ? 1 : this.debug === 'bloom' ? 2 : 0);
    cu.uTime.value = this.time;
    cu.uTone.value.set(grade.exposure, grade.saturation, grade.contrast, grade.vignette);
    cu.uMix.value.set(grade.bloom * this.bloomNorm, grade.ao, 0, 0);
    cu.uLift.value.copy(grade.lift); cu.uGamma.value.copy(grade.gamma); cu.uGain.value.copy(grade.gain);
    cu.uShadowTint.value.copy(grade.shadowTint); cu.uHighTint.value.copy(grade.highTint);
    q.draw(r, this.compMat, this.rtLDR ?? null);
    passes++;

    // 7. сглаживание
    if (this.smaa) {
      this.smaa.render(r, null, this.rtLDR);
      passes += 3;
    } else if (this.fxaaMat) {
      this.fxaaMat.uniforms.tDiffuse.value = this.rtLDR.texture;
      q.draw(r, this.fxaaMat, null);
      passes++;
    }
    r.autoClear = prevAuto;
    this.stats.passes = passes;
  }

  // Для отчёта/отладки: буферы и их размеры.
  info() {
    const list = [];
    const add = (name, rt, bpp) => { if (rt) list.push(`${name} ${rt.width}x${rt.height} ${bpp}`); };
    add('scene', this.rtScene, this.rtScene.depthTexture ? 'rgba16f+depthTex' : 'rgba16f+depth');
    add('hands', this.rtHands, 'rgba16f+depth');
    add('copy', this.rtCopy, 'rgba16f');
    add('ao', this.rtAO, 'rgba16f'); add('ao2', this.rtAO2, 'rgba16f');
    this.mips.forEach((m, i) => add(`bloom${i}`, m, 'rgba16f'));
    add('rays', this.rtRays, 'rgba16f'); add('rays2', this.rtRays2, 'rgba16f');
    add('ldr', this.rtLDR, 'rgba8');
    if (this.smaa) { add('smaaEdges', this.smaa.edgesRT, 'rgba16f'); add('smaaWeights', this.smaa.weightsRT, 'rgba16f'); }
    return { passes: this.stats.passes, targets: list };
  }

  dispose() {
    for (const rt of [this.rtScene, this.rtHands, this.rtLDR, this.rtAO, this.rtAO2, this.rtCopy, this.rtRays, this.rtRays2, ...this.mips]) rt?.dispose();
    this.smaa?.dispose();
  }
}
