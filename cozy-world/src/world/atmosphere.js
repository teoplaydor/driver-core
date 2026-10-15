// Освещение, небо и туман. Пресеты на каждый биом плавно смешиваются по позиции игрока,
// а в интерьере (x >= INTERIOR_X) гасится солнце и включается тёплый свет.

import * as THREE from 'three';
import { damp } from '../util/math.js';
import { zoneWeights, isInterior } from './terrain-data.js';

const C = (h) => new THREE.Color(h);

export const PRESETS = {
  // Солнечная хвойная поляна, как на первом скрине референса: насыщенное синее небо, тёплое солнце.
  meadow: {
    skyTop: C('#3f8fe0'), skyMid: C('#84c0f2'), fog: C('#bfdcf3'), fogDensity: 0.0062,
    sun: C('#ffe0a8'), sunIntensity: 3.2, hemiSky: C('#b8d8ff'), hemiGround: C('#c48f55'), hemiIntensity: 1.45, exposure: 1.0,
  },
  // Оранжевый каньон: тёплая пыльная дымка, красноватые тени от отражённого света.
  canyon: {
    skyTop: C('#3f8bdc'), skyMid: C('#8cbfec'), fog: C('#e9c9a6'), fogDensity: 0.0075,
    sun: C('#ffd193'), sunIntensity: 3.4, hemiSky: C('#a9cdf2'), hemiGround: C('#cf7a40'), hemiIntensity: 1.5, exposure: 1.0,
  },
  autumn: {
    skyTop: C('#6399d0'), skyMid: C('#d8c6a6'), fog: C('#e8cca4'), fogDensity: 0.011,
    sun: C('#ffb468'), sunIntensity: 2.9, hemiSky: C('#e2c9a6'), hemiGround: C('#a8642c'), hemiIntensity: 1.3, exposure: 1.0,
  },
  winter: {
    skyTop: C('#7ea5d2'), skyMid: C('#c4d8ec'), fog: C('#dbe7f3'), fogDensity: 0.016,
    sun: C('#f0f5ff'), sunIntensity: 2.2, hemiSky: C('#dcecff'), hemiGround: C('#a9b8cc'), hemiIntensity: 1.5, exposure: 1.0,
  },
  // Дождливые сумерки: серо-синяя дымка.
  rain: {
    skyTop: C('#44556a'), skyMid: C('#6d8196'), fog: C('#7a8a99'), fogDensity: 0.022,
    sun: C('#aebfd6'), sunIntensity: 1.1, hemiSky: C('#9fb2c8'), hemiGround: C('#566a5c'), hemiIntensity: 1.3, exposure: 1.1,
  },
  indoor: {
    skyTop: C('#20181a'), skyMid: C('#20181a'), fog: C('#2a211d'), fogDensity: 0.0,
    sun: C('#ffcf9a'), sunIntensity: 0.0, hemiSky: C('#fff0d8'), hemiGround: C('#b58a5e'), hemiIntensity: 2.1, exposure: 1.1,
  },
};

const KEYS = ['meadow', 'autumn', 'winter', 'rain', 'canyon'];
const UP = new THREE.Vector3(0, 1, 0);

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

    this.sunDir = new THREE.Vector3(-0.55, 0.62, 0.5).normalize();
    this.shadowRange = 56;
    this.shadowSize = 2048;
    this.setShadows(2048, 56);

    this.w = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
    this._tw = { meadow: 1, autumn: 0, winter: 0, rain: 0, canyon: 0 };
    this.indoor = 0;

    this.skyTop = new THREE.Color();
    this.skyMid = new THREE.Color();
    this.fogColor = new THREE.Color();
    this.sunColor = new THREE.Color();
    this.hemiSky = new THREE.Color();
    this.hemiGround = new THREE.Color();
    this.fogDensity = 0.007;
    this.sunIntensity = 3;
    this.hemiIntensity = 1;
    this.exposure = 1;
    // доли эффектов для частиц и звука
    this.rain = 0; this.snow = 0; this.leaves = 0; this.dust = 0;

    this._right = new THREE.Vector3();
    this._up = new THREE.Vector3();
    this._snap = new THREE.Vector3();
  }

  setShadows(size, range) {
    this.shadowSize = size;
    this.shadowRange = range;
    const s = this.sun.shadow;
    if (size <= 0) {
      this.sun.castShadow = false;
      return;
    }
    this.sun.castShadow = true;
    if (s.mapSize.x !== size) {
      s.mapSize.set(size, size);
      if (s.map) { s.map.dispose(); s.map = null; }
    }
    const c = s.camera;
    c.left = -range; c.right = range; c.top = range; c.bottom = -range;
    c.near = 10; c.far = 300;
    c.updateProjectionMatrix();
  }

  _mix(out, key) {
    out.setRGB(0, 0, 0);
    for (const k of KEYS) {
      const p = PRESETS[k][key], w = this.w[k];
      out.r += p.r * w; out.g += p.g * w; out.b += p.b * w;
    }
    out.lerp(PRESETS.indoor[key], this.indoor);
  }

  _mixNum(key) {
    let v = 0;
    for (const k of KEYS) v += PRESETS[k][key] * this.w[k];
    return v + (PRESETS.indoor[key] - v) * this.indoor;
  }

  // focus - позиция игрока (для зоны и для центрирования теней).
  update(dt, focus) {
    const tw = zoneWeights(focus.x, focus.z, this._tw);
    for (const k of KEYS) this.w[k] = damp(this.w[k], tw[k], 2.2, dt);
    this.indoor = damp(this.indoor, isInterior(focus.x) ? 1 : 0, 5, dt);

    this._mix(this.skyTop, 'skyTop');
    this._mix(this.skyMid, 'skyMid');
    this._mix(this.fogColor, 'fog');
    this._mix(this.sunColor, 'sun');
    this._mix(this.hemiSky, 'hemiSky');
    this._mix(this.hemiGround, 'hemiGround');
    this.fogDensity = this._mixNum('fogDensity');
    this.sunIntensity = this._mixNum('sunIntensity');
    this.hemiIntensity = this._mixNum('hemiIntensity');
    this.exposure = this._mixNum('exposure');

    this.rain = this.w.rain * (1 - this.indoor);
    this.snow = this.w.winter * (1 - this.indoor);
    this.leaves = this.w.autumn * (1 - this.indoor);
    this.dust = this.w.canyon * (1 - this.indoor);

    this.scene.fog.color.copy(this.fogColor);
    this.scene.fog.density = this.fogDensity;
    this.renderer.setClearColor(this.fogColor);
    this.renderer.toneMappingExposure = this.exposure;

    this.hemi.color.copy(this.hemiSky);
    this.hemi.groundColor.copy(this.hemiGround);
    this.hemi.intensity = this.hemiIntensity;
    this.sun.color.copy(this.sunColor);
    this.sun.intensity = this.sunIntensity;

    this._placeSun(focus);
  }

  // Центр теневой камеры двигаем шагами в один тексель, иначе тени "дрожат" при ходьбе.
  _placeSun(focus) {
    const d = this.sunDir;
    this._right.crossVectors(UP, d).normalize();
    this._up.crossVectors(d, this._right).normalize();
    const texel = (this.shadowRange * 2) / Math.max(1, this.shadowSize);
    const px = Math.round(focus.dot(this._right) / texel) * texel;
    const py = Math.round(focus.dot(this._up) / texel) * texel;
    const pz = focus.dot(d);
    this._snap.set(0, 0, 0)
      .addScaledVector(this._right, px)
      .addScaledVector(this._up, py)
      .addScaledVector(d, pz);
    this.sun.target.position.copy(this._snap);
    this.sun.position.copy(this._snap).addScaledVector(d, 120);
    this.sun.target.updateMatrixWorld();
  }
}
