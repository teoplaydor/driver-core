// Небо: купол с градиентом (насыщенный зенит, дымка у горизонта как у тумана), солнце с ореолом,
// процедурный слой высоких облаков (в дождь - сплошная серая пелена) и объёмные кучевые облака-билборды
// (атлас форм, мягкое затенение снизу, серебристая кайма против солнца, медленный дрейф) - одним draw call.
// Купол рисуется последним из непрозрачных на дальней плоскости: пиксели под деревьями и землёй не считаются.

import * as THREE from 'three';
import { mulberry32 } from '../util/math.js';

const SKY_RADIUS = 450;
const RAIN_SHADE = new THREE.Color(0.2, 0.23, 0.28);
const CLOUD_SHADE = new THREE.Color(0.46, 0.52, 0.64);
const CELL_W = 256, CELL_H = 160, COLS = 4, ROWS = 2;

// Атлас кучевых облаков: R - освещённость, G - толщина, A - покрытие.
// Форма - купол из пухлых шаров (крупные внизу, «цветная капуста» сверху) с плоским основанием.
// Свет: нормаль из градиента сглаженной плотности + самозатенение (шаги к солнцу по плотности) + светлый верх.
// Формы заданы в «эталонных» пикселях ячейки CELL_W x CELL_H; scale - доля разрешения (на слабых - 0.5:
// считается вчетверо быстрее, облака далеко и крупные - разницы почти не видно).
function cloudAtlas(scale = 1) {
  const cw = Math.round(CELL_W * scale), ch = Math.round(CELL_H * scale);
  const W = cw * COLS, H = ch * ROWS;
  const data = new Uint8Array(W * H * 4);
  const D = new Float32Array(cw * ch), T = new Float32Array(cw * ch), S = new Float32Array(cw * ch);
  const Lx = 0.42, Ly = 0.78, Lz = 0.47;
  const lstep = Math.hypot(Lx, Ly);
  const sp = Math.max(1, Math.round(2 * scale)); // радиус сглаживания и шаг градиента, пикс.
  const gk = (4 * 2.2 * scale) / (2 * sp);
  const at = (x, y) => (x < 0 || y < 0 || x >= cw || y >= ch ? 0 : S[y * cw + x]);
  for (let cell = 0; cell < COLS * ROWS; cell++) {
    const rand = mulberry32(1000 + cell * 77);
    const ox = (cell % COLS) * cw, oy = Math.floor(cell / COLS) * ch;
    const flat = cell >= 6; // вытянутые гряды для горизонта
    const base = CELL_H * 0.16;
    const maxH = flat ? CELL_H * (0.16 + rand() * 0.06) : CELL_H * (0.42 + rand() * 0.3);
    const skew = (rand() - 0.5) * 0.5;
    const env = (t) => maxH * Math.pow(Math.max(0, Math.sin(Math.PI * Math.min(1, Math.max(0, t + skew * (t - 0.5))))), flat ? 0.5 : 0.85);
    const puffs = [];
    // основание: ряд крупных
    const nb = flat ? 9 : 6;
    for (let i = 0; i < nb; i++) {
      const t = 0.08 + 0.84 * (i + 0.5) / nb + (rand() - 0.5) * 0.04;
      const r = (flat ? 9 : 15) + env(t) * 0.22 + rand() * 6;
      puffs.push([CELL_W * t, base + r * 0.7, r]);
    }
    // тело и верх: шары внутри огибающей купола, к верху мельче
    const nt = flat ? 24 : 38;
    for (let i = 0; i < nt; i++) {
      const t = 0.12 + 0.76 * rand();
      const top = env(t);
      const h = Math.pow(rand(), 0.75) * top;
      const r = (flat ? 6 : 9) + (1 - h / Math.max(1, maxH)) * (flat ? 6 : 12) + rand() * 6;
      puffs.push([CELL_W * t, base + Math.max(r * 0.6, h), r]);
    }
    D.fill(0);
    for (const [cx, cy, r] of puffs) {
      const x0 = Math.max(0, Math.floor((cx - r) * scale)), x1 = Math.min(cw - 1, Math.ceil((cx + r) * scale));
      const y0 = Math.max(0, Math.floor((cy - r) * scale)), y1 = Math.min(ch - 1, Math.ceil((cy + r) * scale));
      const ir2 = 1 / (r * r);
      for (let y = y0; y <= y1; y++) {
        const dy = (y + 0.5) / scale - cy;
        for (let x = x0; x <= x1; x++) {
          const dx = (x + 0.5) / scale - cx;
          const q = 1 - (dx * dx + dy * dy) * ir2;
          if (q > 0) D[y * cw + x] += q * q * 1.6;
        }
      }
    }
    // плоское основание
    for (let y = 0; y < ch; y++) {
      const b = Math.min(1, Math.max(0, ((y + 0.5) / scale - base * 0.6) / (base * 0.55)));
      const k = b * b * (3 - 2 * b);
      for (let x = 0; x < cw; x++) D[y * cw + x] = Math.min(2.5, D[y * cw + x] * k);
    }
    // сглаженная плотность для нормалей: прямоугольное размытие, по строкам и по столбцам
    for (let y = 0; y < ch; y++) {
      for (let x = 0; x < cw; x++) {
        let sum = 0, cnt = 0;
        for (let d = -sp; d <= sp; d++) { const xx = x + d; if (xx >= 0 && xx < cw) { sum += D[y * cw + xx]; cnt++; } }
        T[y * cw + x] = sum / cnt;
      }
    }
    for (let y = 0; y < ch; y++) {
      for (let x = 0; x < cw; x++) {
        let sum = 0, cnt = 0;
        for (let d = -sp; d <= sp; d++) { const yy = y + d; if (yy >= 0 && yy < ch) { sum += T[yy * cw + x]; cnt++; } }
        S[y * cw + x] = sum / cnt;
      }
    }
    let top = 0;
    for (const p of puffs) top = Math.max(top, p[1] + p[2]);
    const sx = (Lx / lstep) * 4 * scale, sy = (Ly / lstep) * 4 * scale;
    for (let y = 0; y < ch; y++) {
      for (let x = 0; x < cw; x++) {
        const d = D[y * cw + x];
        const a = Math.min(1, Math.max(0, (d - 0.12) / 0.45));
        const gx = (at(x + sp, y) - at(x - sp, y)) * gk, gy = (at(x, y + sp) - at(x, y - sp)) * gk;
        const nl = Math.hypot(gx, gy, 1);
        const lam = Math.max(0, (-gx * Lx - gy * Ly + Lz) / nl);
        // самозатенение: сколько облака между точкой и солнцем
        let acc = 0;
        for (let k = 1; k <= 7; k++) acc += at(Math.round(x + sx * k), Math.round(y + sy * k));
        const sh = Math.exp(-acc * 0.22);
        const hgt = Math.min(1, Math.max(0, ((y + 0.5) / scale - base) / Math.max(1, top - base)));
        const shade = Math.min(1, 0.1 + 0.5 * lam * (0.35 + 0.65 * sh) + 0.25 * sh + 0.22 * hgt);
        const i = ((oy + y) * W + ox + x) * 4;
        data[i] = Math.round(shade * 255);
        data[i + 1] = Math.round(Math.min(1, d * 0.6) * 255);
        data[i + 2] = 0;
        data[i + 3] = Math.round(a * 255);
      }
    }
  }
  const tex = new THREE.DataTexture(data, W, H, THREE.RGBAFormat, THREE.UnsignedByteType);
  tex.magFilter = THREE.LinearFilter;
  tex.minFilter = THREE.LinearMipmapLinearFilter;
  tex.generateMipmaps = true;
  tex.wrapS = tex.wrapT = THREE.ClampToEdgeWrapping;
  tex.needsUpdate = true;
  return tex;
}

const NOISE = /* glsl */`
float skHash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float skNoise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(skHash(i), skHash(i + vec2(1.0, 0.0)), f.x), mix(skHash(i + vec2(0.0, 1.0)), skHash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float skFbm(vec2 p) {
  float s = 0.0, a = 0.5;
  for (int i = 0; i < OCTAVES; i++) { s += skNoise(p) * a; p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
  return s;
}
`;

export class Sky {
  constructor({ detail = 1 } = {}) {
    const octaves = detail >= 1 ? 5 : detail >= 0.6 ? 4 : 3;
    this.uniforms = {
      uTop: { value: new THREE.Color('#4a98e0') },
      uMid: { value: new THREE.Color('#8cc5f2') },
      uFog: { value: new THREE.Color('#cfe4f3') },
      uSunColor: { value: new THREE.Color('#ffe3b0') },
      uSunDir: { value: new THREE.Vector3(0, 1, 0) },
      uInscatter: { value: new THREE.Vector4(1, 0.9, 0.7, 0.8) },
      uCloud: { value: new THREE.Vector4(0.4, 0, 0, 0) }, // покрытие, темнота, время, -
      uDim: { value: 0 }, // 1 = в помещении, небо гасим
    };
    const mat = new THREE.ShaderMaterial({
      uniforms: this.uniforms,
      side: THREE.BackSide,
      depthWrite: false,
      depthFunc: THREE.LessEqualDepth,
      fog: false,
      // на слабых (detail < 0.6) без процедурного слоя: небо - градиент, солнце и кучевые облака
      defines: detail >= 0.6 ? { OCTAVES: octaves, SKY_CIRRUS: '' } : { OCTAVES: octaves },
      vertexShader: /* glsl */`
        varying vec3 vDir;
        void main() {
          vDir = position;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
          gl_Position.z = gl_Position.w * 0.999999; // у дальней плоскости: виден только там, где нет геометрии
        }`,
      fragmentShader: /* glsl */`
        uniform vec3 uTop, uMid, uFog, uSunColor, uSunDir;
        uniform vec4 uInscatter, uCloud;
        uniform float uDim;
        varying vec3 vDir;
        ${NOISE}
        void main() {
          vec3 d = normalize(vDir);
          float y = d.y, h = max(y, 0.0);
          float s = max(dot(d, uSunDir), 0.0);
          // у горизонта - тот же цвет, что у тумана (с тёплым рассеянием у солнца)
          vec3 hor = uFog + uInscatter.rgb * (uInscatter.a * (s * s * s * s * 0.45 + pow(s, 24.0) * 0.9));
          vec3 col = mix(hor, uMid, smoothstep(0.0, 0.22, h));
          col = mix(col, uTop, smoothstep(0.1, 0.7, h));
          col = mix(col, uTop * vec3(0.86, 0.9, 1.0), smoothstep(0.55, 1.0, h) * 0.5); // насыщеннее в зените
          col = mix(col, uFog, smoothstep(0.02, -0.12, y));
          float cover = uCloud.x;
          // солнце: диск (ярче порога bloom) и ореол; за пеленой - слабее
          float veil = 1.0 - cover * 0.85;
          col += uSunColor * (smoothstep(0.99925, 0.99965, s) * 24.0 * veil + pow(s, 260.0) * 2.2 * veil + pow(s, 32.0) * 0.3 + pow(s, 5.0) * 0.08);
          // процедурный слой: перистые полосы в ясную погоду, сплошная пелена - в дождь
          #ifdef SKY_CIRRUS
          if (y > -0.02 && cover > 0.01) {
            vec2 p = d.xz / (h + 0.09) * 0.55 + vec2(uCloud.z * 0.006, uCloud.z * 0.0025);
            // перистые: вытянуты по диагонали ветра и изогнуты (без прямых «столбов» к горизонту)
            vec2 pw = p + vec2(skNoise(p * 0.7 + 5.0), skNoise(p * 0.7 - 3.0)) * 1.4;
            vec2 ps = mat2(0.8, 0.6, -0.6, 0.8) * pw * vec2(0.8, 2.4);
            float n = skFbm(mix(ps, p * 1.3, smoothstep(0.55, 0.85, cover)));
            float n2 = skFbm(p * 1.3 + uSunDir.xz * 0.12 + 3.1);
            float th = mix(0.72, 0.18, cover);
            float c = smoothstep(th, th + mix(0.22, 0.4, cover), n);
            c *= smoothstep(0.0, 0.3, y) * mix(0.5, 1.0, smoothstep(0.5, 0.9, cover));
            float shade = clamp(0.62 + (n - n2) * 2.2 + (1.0 - cover) * 0.25, 0.0, 1.0);
            vec3 lit = mix(vec3(1.0), uSunColor, 0.35) * mix(1.12, 0.78, uCloud.y);
            vec3 dark = mix(mix(uMid, vec3(0.58, 0.63, 0.7), 0.6), vec3(0.36, 0.41, 0.48), uCloud.y);
            vec3 cc = mix(dark, lit, shade);
            cc = mix(cc, hor, smoothstep(0.25, 0.0, h) * 0.7);
            col = mix(col, cc, c);
          }
          #else
          // без слоя: в пасмурную погоду небо просто серее
          col = mix(col, mix(uMid, vec3(0.42, 0.47, 0.54), uCloud.y), cover * uCloud.y * smoothstep(-0.02, 0.2, y) * 0.8);
          #endif
          col = mix(col, uFog * 0.15, uDim);
          gl_FragColor = vec4(col, 1.0);
          #include <tonemapping_fragment>
          #include <colorspace_fragment>
        }`,
    });
    this.dome = new THREE.Mesh(new THREE.SphereGeometry(SKY_RADIUS, 32, 16), mat);
    this.dome.frustumCulled = false;
    this.dome.renderOrder = 1000; // после всех непрозрачных
    this.dome.name = 'sky';

    this._buildClouds(detail);
  }

  // detail < 0.6: облака без шума по краю (дешевле на слабых устройствах)
  _buildClouds(detail) {
    const rand = mulberry32(99);
    const count = Math.round(26 * Math.max(0.6, detail));
    const quad = new THREE.PlaneGeometry(1, 1, 1, 1);
    quad.translate(0, 0.5, 0);
    const geo = new THREE.InstancedBufferGeometry();
    geo.index = quad.index;
    geo.setAttribute('position', quad.getAttribute('position'));
    geo.setAttribute('uv', quad.getAttribute('uv'));
    const a1 = new Float32Array(count * 4), a2 = new Float32Array(count * 4);
    for (let i = 0; i < count; i++) {
      const horizon = i % 5 === 4; // гряды у горизонта
      const ang = (i / count) * Math.PI * 2 + rand() * 0.5;
      const dist = horizon ? 330 + rand() * 60 : 230 + rand() * 150;
      const w = horizon ? 220 + rand() * 120 : 80 + rand() * 95;
      a1.set([ang, dist, horizon ? 22 + rand() * 25 : 70 + rand() * 80, w], i * 4);
      const cell = horizon ? 6 + (i % 2) : Math.floor(rand() * 6);
      a2.set([cell, horizon ? 0.42 : 0.625, 0.8 + rand() * 0.2, rand()], i * 4);
    }
    geo.setAttribute('aCloud', new THREE.InstancedBufferAttribute(a1, 4));
    geo.setAttribute('aCloud2', new THREE.InstancedBufferAttribute(a2, 4));
    geo.instanceCount = count;
    this.cloudUniforms = {
      uAtlas: { value: cloudAtlas(detail >= 1 ? 1 : 0.5) },
      uCam: { value: new THREE.Vector3() },
      uTime: { value: 0 },
      uSunDir: { value: new THREE.Vector3(0, 1, 0) },
      uSunColor: { value: new THREE.Color() },
      uLit: { value: new THREE.Color(1, 1, 1) },
      uShade: { value: new THREE.Color(0.6, 0.66, 0.78) },
      uFog: { value: new THREE.Color() },
      uState: { value: new THREE.Vector4(0, 0, 1, 0) }, // дождь (ниже и темнее), -, прозрачность, -
    };
    const mat = new THREE.ShaderMaterial({
      uniforms: this.cloudUniforms,
      transparent: true,
      depthWrite: false,
      fog: false,
      defines: detail >= 0.6 ? { CLOUD_EDGE_NOISE: '' } : {},
      vertexShader: /* glsl */`
        attribute vec4 aCloud;  // угол, расстояние, высота, ширина
        attribute vec4 aCloud2; // ячейка атласа, высота/ширина, непрозрачность, зерно
        uniform vec3 uCam;
        uniform float uTime;
        uniform vec4 uState;
        varying vec2 vUv;
        varying vec3 vDir;
        varying float vAlpha;
        varying float vHaze;
        varying float vSeed;
        void main() {
          float ang = aCloud.x + uTime * 0.0025 * (0.6 + aCloud2.w * 0.8);
          float hgt = aCloud.z * mix(1.0, 0.5, uState.x);
          vec3 center = vec3(uCam.x + cos(ang) * aCloud.y, hgt, uCam.z + sin(ang) * aCloud.y);
          vec3 toCam = normalize(vec3(uCam.x - center.x, 0.0, uCam.z - center.z));
          vec3 right = vec3(toCam.z, 0.0, -toCam.x);
          float w = aCloud.w * (1.0 + 0.06 * sin(uTime * 0.05 + aCloud2.w * 20.0));
          vec3 wp = center + right * position.x * w + vec3(0.0, position.y * w * aCloud2.y, 0.0);
          wp.y -= w * aCloud2.y * 0.18;
          vec2 cell = vec2(mod(aCloud2.x, ${COLS}.0), floor(aCloud2.x / ${COLS}.0));
          vUv = (cell + vec2(uv.x, uv.y)) / vec2(${COLS}.0, ${ROWS}.0);
          vDir = wp - uCam;
          vAlpha = aCloud2.z * uState.z;
          vSeed = aCloud2.w;
          float elev = (wp.y - uCam.y) / length(vDir);
          vHaze = smoothstep(0.35, 0.02, elev) * 0.75;
          gl_Position = projectionMatrix * viewMatrix * vec4(wp, 1.0);
        }`,
      fragmentShader: /* glsl */`
        uniform sampler2D uAtlas;
        uniform vec3 uSunDir, uSunColor, uLit, uShade, uFog;
        uniform vec4 uState;
        uniform float uTime;
        varying vec2 vUv;
        varying vec3 vDir;
        varying float vAlpha;
        varying float vHaze;
        varying float vSeed;
        float h2(vec2 p) { p = fract(p * vec2(233.34, 851.73)); p += dot(p, p + 23.45); return fract(p.x * p.y); }
        float n2(vec2 p) {
          vec2 i = floor(p), f = fract(p);
          f = f * f * (3.0 - 2.0 * f);
          return mix(mix(h2(i), h2(i + vec2(1.0, 0.0)), f.x), mix(h2(i + vec2(0.0, 1.0)), h2(i + vec2(1.0, 1.0)), f.x), f.y);
        }
        void main() {
          vec4 t = texture2D(uAtlas, vUv);
          // рваный край: шум «съедает» тонкие места
          #ifdef CLOUD_EDGE_NOISE
          vec2 q = vUv * vec2(48.0, 24.0) + vSeed * 17.0 + uTime * 0.01;
          float n = n2(q) * 0.6 + n2(q * 2.3) * 0.4;
          #else
          float n = 0.5;
          #endif
          float a = smoothstep(0.08, 0.55, t.a - (n - 0.5) * 0.45 * (1.0 - t.g)) * vAlpha;
          if (a < 0.004) discard;
          vec3 dir = normalize(vDir);
          float back = pow(max(dot(dir, uSunDir), 0.0), 3.0);
          vec3 col = mix(uShade, uLit, t.r * (1.0 - back * 0.45));
          col *= 0.93 + 0.14 * n;
          // против солнца: тонкие края светятся (серебристая кайма), толща темнее
          float thin = (1.0 - t.g) * (1.0 - t.g);
          col += uSunColor * (back * back * back) * thin * 0.9;
          col *= 1.0 - 0.45 * uState.x * (1.0 - t.r);
          col = mix(col, uFog, vHaze);
          gl_FragColor = vec4(col, a);
          #include <tonemapping_fragment>
          #include <colorspace_fragment>
        }`,
    });
    this.clouds = new THREE.Mesh(geo, mat);
    this.clouds.frustumCulled = false;
    this.clouds.renderOrder = -9;
    this.clouds.name = 'clouds';
  }

  // atm - состояние из Atmosphere.
  update(camera, atm, time) {
    this.dome.position.copy(camera.position);
    const v = atm.v;
    const u = this.uniforms;
    u.uTop.value.copy(atm.skyTop);
    u.uMid.value.copy(atm.skyMid);
    u.uFog.value.copy(atm.fogColor);
    u.uSunColor.value.copy(atm.sunColor);
    u.uSunDir.value.copy(atm.sunDir);
    u.uInscatter.value.set(atm.sunColor.r, atm.sunColor.g * 0.92, atm.sunColor.b * 0.75, (v ? v.inscatter : 0.8) * (1 - atm.indoor));
    u.uCloud.value.set(v ? v.clouds : 0.4, v ? v.cloudDark : 0, time, 0);
    u.uDim.value = atm.indoor;

    const c = this.cloudUniforms;
    c.uCam.value.copy(camera.position);
    c.uTime.value = time;
    c.uSunDir.value.copy(atm.sunDir);
    c.uSunColor.value.copy(atm.sunColor);
    const dark = v ? v.cloudDark : 0;
    // освещённая сторона - белая с оттенком солнца; теневая - голубовато-серая, в дождь - тёмно-серая
    c.uLit.value.setRGB(1.1, 1.08, 1.06).lerp(atm.sunColor, 0.2).multiplyScalar(1 - 0.45 * dark);
    c.uShade.value.copy(CLOUD_SHADE).lerp(atm.skyMid, 0.28).lerp(RAIN_SHADE, dark * 0.85);
    c.uFog.value.copy(atm.fogColor);
    // в дождь кучевые тонут в сплошной пелене
    c.uState.value.set(dark, 0, (1 - atm.indoor) * (1 - 0.65 * dark), 0);
    this.clouds.visible = atm.indoor < 0.95;
  }
}
