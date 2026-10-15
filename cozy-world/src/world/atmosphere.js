// Освещение, небо, туман и цвет кадра. Пресеты на каждый биом плавно смешиваются по позиции игрока,
// а в интерьере (x >= INTERIOR_X) гасится солнце и включается тёплый свет.
// Свет: тёплое солнце (на high - два каскада теней), полусфера небо/земля и мягкая холодная заливка с теневой
// стороны (имитация отражённого света, см. render/chunks.js). Туман - экспоненциальный по расстоянию плюс
// слой у земли (в низинах гуще), над озером - утренняя дымка. Параметры цвета (grade) читает постобработка.

import * as THREE from 'three';
import { damp } from '../util/math.js';
import { zoneWeights, isInterior } from './terrain-data.js';
import { RENDER_UNIFORMS, cascadesSupported } from '../render/chunks.js';

const C = (h) => new THREE.Color(h);
const V = (x, y, z) => new THREE.Vector3(x, y, z);

// Поля пресета:
//  небо и туман: skyTop, skyMid, fog (цвет дымки у горизонта), fogDensity (по расстоянию), fogHeight (плотность у
//    земли тумана), fogBase (высота «земли» тумана), fogFalloff (спад с высотой, 1/м), inscatter (тёплое рассеяние
//    у солнца), lakeMist (дымка над озером), clouds (доля облачности 0..1), cloudDark (облака темнее, ниже - дождь);
//  свет: sun/sunIntensity, hemiSky/hemiGround/hemiIntensity, fill/fillIntensity (холодная заливка), sheen (отблеск неба);
//  цвет кадра: exposure, saturation, contrast, vignette, lift/gamma/gain, shadowTint/highTint (тонировка теней и светов),
//    bloom (сила), bloomThreshold, ao (сила AO).
export const PRESETS = {
  // Солнечная хвойная поляна, как на первом скрине референса: насыщенное синее небо, тёплое солнце.
  meadow: {
    skyTop: C('#2f7fdc'), skyMid: C('#7fbcf0'), fog: C('#c4dff2'), fogDensity: 0.0046, fogHeight: 0.0024, fogBase: -3, fogFalloff: 0.1,
    inscatter: 0.9, lakeMist: 0.03, clouds: 0.42, cloudDark: 0,
    sun: C('#ffdcaa'), sunIntensity: 3.35, hemiSky: C('#b4d4ff'), hemiGround: C('#c08a52'), hemiIntensity: 1.2,
    fill: C('#8fb0e8'), fillIntensity: 0.45, sheen: 0.22,
    exposure: 1.02, saturation: 1.12, contrast: 0.16, vignette: 0.32,
    lift: V(0.012, 0.016, 0.03), gamma: V(1.0, 1.0, 0.98), gain: V(1.03, 1.0, 0.95), shadowTint: V(-0.01, 0.0, 0.025), highTint: V(0.02, 0.01, -0.012),
    bloom: 0.9, bloomThreshold: 1.25, ao: 0.85,
  },
  // Оранжевый каньон: тёплая пыльная дымка, красноватые тени от отражённого света.
  canyon: {
    skyTop: C('#2f7fd8'), skyMid: C('#88bdeb'), fog: C('#ecc9a2'), fogDensity: 0.0052, fogHeight: 0.0035, fogBase: -4, fogFalloff: 0.07,
    inscatter: 1.1, lakeMist: 0.0, clouds: 0.35, cloudDark: 0,
    sun: C('#ffd093'), sunIntensity: 3.5, hemiSky: C('#a9cdf2'), hemiGround: C('#d27a3e'), hemiIntensity: 1.25,
    fill: C('#b98e78'), fillIntensity: 0.5, sheen: 0.18,
    exposure: 1.0, saturation: 1.1, contrast: 0.18, vignette: 0.32,
    lift: V(0.02, 0.012, 0.022), gamma: V(1.0, 0.99, 0.97), gain: V(1.04, 1.0, 0.94), shadowTint: V(0.0, -0.004, 0.02), highTint: V(0.025, 0.01, -0.015),
    bloom: 0.8, bloomThreshold: 1.3, ao: 0.9,
  },
  autumn: {
    skyTop: C('#3f86d4'), skyMid: C('#a9c6dc'), fog: C('#e6cfac'), fogDensity: 0.0056, fogHeight: 0.003, fogBase: -2, fogFalloff: 0.09,
    inscatter: 1.1, lakeMist: 0.0, clouds: 0.3, cloudDark: 0.05,
    sun: C('#ffb468'), sunIntensity: 3.0, hemiSky: C('#e2c9a6'), hemiGround: C('#a8642c'), hemiIntensity: 1.15,
    fill: C('#9fa9d8'), fillIntensity: 0.45, sheen: 0.2,
    exposure: 1.02, saturation: 1.14, contrast: 0.16, vignette: 0.36,
    lift: V(0.018, 0.012, 0.03), gamma: V(1.0, 0.99, 0.97), gain: V(1.05, 1.0, 0.92), shadowTint: V(-0.006, 0.0, 0.03), highTint: V(0.03, 0.012, -0.02),
    bloom: 0.95, bloomThreshold: 1.25, ao: 0.85,
  },
  // Бело-голубая морозная дымка.
  winter: {
    skyTop: C('#6f9fd6'), skyMid: C('#c6daee'), fog: C('#dce8f4'), fogDensity: 0.0095, fogHeight: 0.016, fogBase: 0, fogFalloff: 0.07,
    inscatter: 0.6, lakeMist: 0.0, clouds: 0.62, cloudDark: 0.12,
    sun: C('#f2f5ff'), sunIntensity: 2.3, hemiSky: C('#dcecff'), hemiGround: C('#a9b8cc'), hemiIntensity: 1.35,
    fill: C('#a7c0ec'), fillIntensity: 0.4, sheen: 0.3,
    exposure: 0.98, saturation: 1.0, contrast: 0.12, vignette: 0.3,
    lift: V(0.01, 0.018, 0.036), gamma: V(1.0, 1.0, 1.01), gain: V(0.99, 1.0, 1.03), shadowTint: V(-0.012, 0.0, 0.03), highTint: V(0.0, 0.004, 0.012),
    bloom: 0.7, bloomThreshold: 1.6, ao: 0.75,
  },
  // Дождливые сумерки: низкие серые облака, серо-синяя дымка, мокрые блики.
  rain: {
    skyTop: C('#45566b'), skyMid: C('#6f8297'), fog: C('#7c8c9b'), fogDensity: 0.012, fogHeight: 0.022, fogBase: -2, fogFalloff: 0.06,
    inscatter: 0.25, lakeMist: 0.0, clouds: 0.95, cloudDark: 1,
    sun: C('#aebfd6'), sunIntensity: 1.15, hemiSky: C('#a3b5ca'), hemiGround: C('#566a5c'), hemiIntensity: 1.25,
    fill: C('#8a9fbf'), fillIntensity: 0.35, sheen: 0.4,
    exposure: 1.1, saturation: 0.92, contrast: 0.14, vignette: 0.42,
    lift: V(0.008, 0.016, 0.03), gamma: V(1.0, 1.0, 1.02), gain: V(0.98, 1.0, 1.04), shadowTint: V(-0.01, 0.002, 0.03), highTint: V(0.012, 0.008, 0.0),
    bloom: 1.3, bloomThreshold: 0.95, ao: 0.8,
  },
  indoor: {
    skyTop: C('#20181a'), skyMid: C('#20181a'), fog: C('#2a211d'), fogDensity: 0.0, fogHeight: 0.0, fogBase: 0, fogFalloff: 0.1,
    inscatter: 0, lakeMist: 0, clouds: 0, cloudDark: 0,
    sun: C('#ffcf9a'), sunIntensity: 0.0, hemiSky: C('#fff0d8'), hemiGround: C('#b58a5e'), hemiIntensity: 1.9,
    fill: C('#ffd2a8'), fillIntensity: 0.35, sheen: 0.15,
    exposure: 1.12, saturation: 1.08, contrast: 0.14, vignette: 0.4,
    lift: V(0.02, 0.012, 0.008), gamma: V(1.0, 0.99, 0.98), gain: V(1.03, 1.0, 0.95), shadowTint: V(0.0, -0.004, 0.012), highTint: V(0.02, 0.01, -0.012),
    bloom: 1.0, bloomThreshold: 2.4, ao: 0.6,
  },
};

const KEYS = ['meadow', 'autumn', 'winter', 'rain', 'canyon'];
const UP = new THREE.Vector3(0, 1, 0);
const FIELDS = Object.keys(PRESETS.meadow);
const _qi = new THREE.Quaternion();

export class Atmosphere {
  constructor(scene, renderer) {
    this.scene = scene;
    this.renderer = renderer;
    scene.fog = new THREE.FogExp2(PRESETS.meadow.fog.clone(), PRESETS.meadow.fogDensity);

    this.hemi = new THREE.HemisphereLight(0xffffff, 0x888888, 1);
    this.sun = new THREE.DirectionalLight(0xffffff, 3);
    this.sun.castShadow = true;
    this.sun.shadow.bias = -0.0004;
    this.sun.shadow.normalBias = 0.05;
    scene.add(this.hemi, this.sun, this.sun.target);
    // носитель дальнего каскада теней (сам не светит; включается на high)
    this.sunFar = null;

    this.sunDir = new THREE.Vector3(-0.55, 0.62, 0.5).normalize();
    // заливка: с противоположной от солнца стороны и чуть сверху
    this.fillDir = new THREE.Vector3(-this.sunDir.x, 0.35, -this.sunDir.z).normalize();
    this.shadowRange = 56;
    this.shadowSize = 2048;
    this.cascades = 1;
    this.setShadows(2048, 56);

    this.w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
    this._tw = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
    this.indoor = 0;

    // текущие (смешанные) значения всех полей пресета: this.v.<поле>
    this.v = {};
    for (const k of FIELDS) {
      const p = PRESETS.meadow[k];
      this.v[k] = typeof p === 'number' ? p : p.clone();
    }
    // совместимость: этими полями пользуются руки, звук, небо, погода
    this.skyTop = this.v.skyTop;
    this.skyMid = this.v.skyMid;
    this.fogColor = this.v.fog;
    this.sunColor = this.v.sun;
    this.hemiSky = this.v.hemiSky;
    this.hemiGround = this.v.hemiGround;
    this.fogDensity = 0.007;
    this.sunIntensity = 3;
    this.hemiIntensity = 1;
    this.exposure = 1;
    // доли эффектов для частиц и звука
    this.rain = 0; this.snow = 0; this.leaves = 0; this.dust = 0; this.pollen = 0;
    this.time = 0;

    // параметры кадра для постобработки (render/post.js)
    this.grade = {
      exposure: 1, saturation: 1, contrast: 0, vignette: 0, bloom: 0, bloomThreshold: 1.3, bloomKnee: 0.3, ao: 0,
      aoRadius: 0.65, aoIntensity: 0.9,
      lift: new THREE.Vector3(), gamma: new THREE.Vector3(1, 1, 1), gain: new THREE.Vector3(1, 1, 1),
      shadowTint: new THREE.Vector3(), highTint: new THREE.Vector3(),
    };

    this._right = new THREE.Vector3();
    this._up = new THREE.Vector3();
    this._snap = new THREE.Vector3();
    this._c = new THREE.Vector3();
    this._fwd = new THREE.Vector3();
    this._last = new THREE.Vector3(1e9, 0, 0);
    this.frame = 0;
  }

  // size - карта теней (0 - без теней), range - полуширина области теней (м),
  // cascades = 2: ближний чёткий каскад (nearSize, nearRange) + дальний (size, range), дальний обновляется через кадр.
  setShadows(size, range, { cascades = 1, nearSize = 1024, nearRange = 14 } = {}) {
    this.shadowSize = size;
    this.shadowRange = range;
    const s = this.sun.shadow;
    if (size <= 0) {
      this.sun.castShadow = false;
      if (this.sunFar) this.sunFar.castShadow = false;
      return;
    }
    this.sun.castShadow = true;
    this.cascades = cascades === 2 && cascadesSupported() ? 2 : 1;
    if (this.cascades === 2) {
      if (!this.sunFar) {
        this.sunFar = new THREE.DirectionalLight(0x000000, 0);
        this.sunFar.name = 'sun-far-cascade';
        // добавляется после солнца: среди теневых источников он второй (так его ждёт шейдер)
        this.scene.add(this.sunFar, this.sunFar.target);
      }
      this.sunFar.castShadow = true;
      this._setupShadow(this.sunFar.shadow, size, range, -0.0006, 0.06);
      this.sunFar.shadow.autoUpdate = false;
      this.sunFar.shadow.needsUpdate = true;
      this._setupShadow(s, nearSize, nearRange, -0.00025, 0.025);
      s.autoUpdate = false;
      s.needsUpdate = true;
      this.nearRange = nearRange;
      this.nearSize = nearSize;
    } else {
      if (this.sunFar) this.sunFar.castShadow = false;
      s.autoUpdate = true;
      this._setupShadow(s, size, range, -0.0004, 0.05);
    }
  }

  _setupShadow(s, size, range, bias, normalBias) {
    if (s.mapSize.x !== size) {
      s.mapSize.set(size, size);
      if (s.map) { s.map.dispose(); s.map = null; }
    }
    s.bias = bias;
    s.normalBias = normalBias;
    const c = s.camera;
    c.left = -range; c.right = range; c.top = range; c.bottom = -range;
    c.near = 1; c.far = 420;
    c.updateProjectionMatrix();
  }

  // focus - позиция игрока (зона и центр теней); camera - для заливки и ближнего каскада (может не быть).
  update(dt, focus, camera) {
    this.time += dt;
    const tw = zoneWeights(focus.x, focus.z, this._tw);
    for (const k of KEYS) this.w[k] = damp(this.w[k], tw[k], 2.2, dt);
    this.indoor = damp(this.indoor, isInterior(focus.x) ? 1 : 0, 5, dt);

    // смешивание всех полей пресетов
    const v = this.v, w = this.w, ind = this.indoor;
    for (const f of FIELDS) {
      const cur = v[f];
      if (typeof cur === 'number') {
        let s = 0;
        for (const k of KEYS) s += PRESETS[k][f] * w[k];
        v[f] = s + (PRESETS.indoor[f] - s) * ind;
      } else if (cur.isColor) {
        cur.setRGB(0, 0, 0);
        for (const k of KEYS) {
          const p = PRESETS[k][f], wk = w[k];
          cur.r += p.r * wk; cur.g += p.g * wk; cur.b += p.b * wk;
        }
        cur.lerp(PRESETS.indoor[f], ind);
      } else {
        cur.set(0, 0, 0);
        for (const k of KEYS) cur.addScaledVector(PRESETS[k][f], w[k]);
        cur.lerp(PRESETS.indoor[f], ind);
      }
    }
    this.fogDensity = v.fogDensity;
    this.sunIntensity = v.sunIntensity;
    this.hemiIntensity = v.hemiIntensity;
    this.exposure = v.exposure;

    this.rain = w.rain * (1 - ind);
    this.snow = w.winter * (1 - ind);
    this.leaves = w.autumn * (1 - ind);
    this.dust = w.canyon * (1 - ind);
    this.pollen = w.meadow * (1 - ind);

    this.scene.fog.color.copy(v.fog);
    this.scene.fog.density = v.fogDensity;
    this.renderer.setClearColor(v.fog);
    this.renderer.toneMappingExposure = v.exposure;

    this.hemi.color.copy(v.hemiSky);
    this.hemi.groundColor.copy(v.hemiGround);
    this.hemi.intensity = v.hemiIntensity;
    this.sun.color.copy(v.sun);
    this.sun.intensity = v.sunIntensity;

    // общие униформы шейдеров (туман, заливка, отблеск, мокрость)
    const U = RENDER_UNIFORMS;
    U.cozyFogA.value.set(Math.max(1e-5, v.fogDensity), v.fogHeight, v.fogFalloff, v.fogBase);
    U.cozyFogB.value.set(v.sun.r, v.sun.g * 0.92, v.sun.b * 0.75, v.inscatter * (1 - ind));
    U.cozyFogC.value.set(this.sunDir.x, this.sunDir.y, this.sunDir.z, v.lakeMist);
    U.cozyFillColor.value.copy(v.fill);
    if (camera) {
      _qi.copy(camera.quaternion).invert();
      this._c.copy(this.fillDir).applyQuaternion(_qi);
      U.cozyFill.value.set(this._c.x, this._c.y, this._c.z, v.fillIntensity);
    } else {
      U.cozyFill.value.w = v.fillIntensity;
    }
    U.cozySky.value.set(v.skyMid.r * 0.6 + v.fog.r * 0.4, v.skyMid.g * 0.6 + v.fog.g * 0.4, v.skyMid.b * 0.6 + v.fog.b * 0.4, v.sheen);
    U.cozyWet.value.set(1 - ind, this.time, 0, 0);

    const g = this.grade;
    g.exposure = v.exposure; g.saturation = v.saturation; g.contrast = v.contrast; g.vignette = v.vignette;
    g.bloom = v.bloom; g.bloomThreshold = v.bloomThreshold; g.bloomKnee = v.bloomThreshold * 0.22; g.ao = v.ao;
    g.lift.copy(v.lift); g.gamma.copy(v.gamma); g.gain.copy(v.gain);
    g.shadowTint.copy(v.shadowTint); g.highTint.copy(v.highTint);

    this._placeSun(focus, camera);
  }

  // Центр теневой камеры двигаем шагами в один тексель, иначе тени «дрожат» при ходьбе.
  _placeLight(light, center, range, size) {
    const d = this.sunDir;
    this._right.crossVectors(UP, d).normalize();
    this._up.crossVectors(d, this._right).normalize();
    const texel = (range * 2) / Math.max(1, size);
    const px = Math.round(center.dot(this._right) / texel) * texel;
    const py = Math.round(center.dot(this._up) / texel) * texel;
    const pz = center.dot(d);
    this._snap.set(0, 0, 0)
      .addScaledVector(this._right, px)
      .addScaledVector(this._up, py)
      .addScaledVector(d, pz);
    light.target.position.copy(this._snap);
    light.position.copy(this._snap).addScaledVector(d, 200);
    light.target.updateMatrixWorld();
  }

  _placeSun(focus, camera) {
    this.frame++;
    if (this.cascades === 2 && this.sun.castShadow) {
      // каскады обновляются по очереди (каждый - через кадр): тени чёткие, а draw calls за кадр - как у одной карты;
      // при скачке позиции (телепорт, отладочная камера) - оба сразу
      const jump = this._last.distanceToSquared(focus) > 25;
      const odd = (this.frame & 1) === 1;
      if (jump || !odd) {
        // ближний каскад сдвинут вперёд по взгляду (при взгляде сверху - не сдвигается)
        const c = this._c.copy(focus);
        if (camera) {
          camera.getWorldDirection(this._fwd);
          const flat = Math.hypot(this._fwd.x, this._fwd.z);
          if (flat > 1e-3) c.addScaledVector(this._fwd.set(this._fwd.x / flat, 0, this._fwd.z / flat), this.nearRange * 0.45 * flat);
        }
        this._placeLight(this.sun, c, this.nearRange, this.nearSize);
        this.sun.shadow.needsUpdate = true;
      }
      if (jump || odd) {
        this._placeLight(this.sunFar, focus, this.shadowRange, this.shadowSize);
        this.sunFar.shadow.needsUpdate = true;
        this._last.copy(focus);
      }
    } else {
      this._placeLight(this.sun, focus, this.shadowRange, this.shadowSize);
    }
  }
}
