// Звук целиком синтезируется в WebAudio, поэтому файлов не нужно: ветер, дождь, костёр с потрескиванием,
// птицы, плеск озера, шаги по разным поверхностям и короткие интерфейсные звуки.

import { smoothstep } from '../util/math.js';
import { CAMPFIRE } from '../../shared/layout.js';
import { LAKE } from '../world/terrain-data.js';

function noiseBuffer(ctx, seconds) {
  const buf = ctx.createBuffer(1, ctx.sampleRate * seconds, ctx.sampleRate);
  const d = buf.getChannelData(0);
  for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
  return buf;
}

export class AudioEngine {
  constructor() {
    this.ctx = null;
    this.muted = false;
    this.volume = 0.85;
    this.nearFire = false;
    this.birdsOn = false;
    this._timers = [];
  }

  get ready() { return !!this.ctx && this.ctx.state === 'running'; }

  async start() {
    if (this.ctx) {
      if (this.ctx.state === 'suspended') await this.ctx.resume().catch(() => {});
      return;
    }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    const ctx = this.ctx = new AC({ latencyHint: 'interactive' });
    this.master = ctx.createGain();
    this.master.gain.value = this.muted ? 0 : this.volume;
    const comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -20;
    comp.ratio.value = 4;
    this.master.connect(comp);
    comp.connect(ctx.destination);
    this.noise = noiseBuffer(ctx, 4);
    this._ambience();
    await ctx.resume().catch(() => {});
  }

  setMuted(m) {
    this.muted = m;
    if (this.ctx) this.master.gain.setTargetAtTime(m ? 0 : this.volume, this.ctx.currentTime, 0.05);
  }

  toggleMute() { this.setMuted(!this.muted); return this.muted; }

  // ---------- постоянные слои ----------

  _loop(dest, type, freq, q) {
    const ctx = this.ctx;
    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    src.loop = true;
    src.start(0, Math.random() * 3);
    const f = ctx.createBiquadFilter();
    f.type = type;
    f.frequency.value = freq;
    f.Q.value = q;
    const g = ctx.createGain();
    g.gain.value = 0;
    src.connect(f);
    f.connect(g);
    g.connect(dest);
    return { src, f, g };
  }

  _lfo(rate, depth, param) {
    const ctx = this.ctx;
    const o = ctx.createOscillator();
    o.frequency.value = rate;
    const g = ctx.createGain();
    g.gain.value = depth;
    o.connect(g);
    g.connect(param);
    o.start();
  }

  _ambience() {
    const ctx = this.ctx;
    this.wind = this._loop(this.master, 'bandpass', 420, 0.6);
    this._lfo(0.11, 170, this.wind.f.frequency);
    this._lfo(0.07, 0.03, this.wind.g.gain);

    this.rain = this._loop(this.master, 'highpass', 900, 0.5);
    this.rainLP = ctx.createBiquadFilter();
    this.rainLP.type = 'lowpass';
    this.rainLP.frequency.value = 6500;
    this.rain.f.disconnect();
    this.rain.f.connect(this.rainLP);
    this.rainLP.connect(this.rain.g);

    // костёр: пространственный источник в точке огня
    this.fireP = ctx.createPanner();
    this.fireP.panningModel = 'HRTF';
    this.fireP.distanceModel = 'inverse';
    this.fireP.refDistance = 2.5;
    this.fireP.rolloffFactor = 1.5;
    this.fireP.maxDistance = 80;
    this._setPos(this.fireP, CAMPFIRE.x, 1, CAMPFIRE.z);
    this.fireP.connect(this.master);
    this.fire = this._loop(this.fireP, 'lowpass', 260, 0.7);
    this._lfo(0.9, 0.12, this.fire.g.gain);

    this.water = this._loop(this.master, 'lowpass', 520, 0.5);
    this._lfo(0.23, 0.03, this.water.g.gain);

    this._crackle();
    this._birds();
  }

  _setPos(node, x, y, z) {
    if (node.positionX) {
      node.positionX.value = x; node.positionY.value = y; node.positionZ.value = z;
    } else node.setPosition(x, y, z);
  }

  _crackle() {
    const loop = () => {
      if (this.ready && this.nearFire && !this.muted) {
        const ctx = this.ctx, t = ctx.currentTime;
        const src = ctx.createBufferSource();
        src.buffer = this.noise;
        const f = ctx.createBiquadFilter();
        f.type = 'bandpass';
        f.frequency.value = 1800 + Math.random() * 3200;
        f.Q.value = 1.2;
        const g = ctx.createGain();
        const peak = 0.18 + Math.random() * 0.25;
        g.gain.setValueAtTime(0, t);
        g.gain.linearRampToValueAtTime(peak, t + 0.003);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.02 + Math.random() * 0.04);
        src.connect(f);
        f.connect(g);
        g.connect(this.fireP);
        src.start(t, Math.random() * 3);
        src.stop(t + 0.1);
      }
      this._timers.push(setTimeout(loop, 70 + Math.random() * 330));
    };
    loop();
  }

  _birds() {
    const loop = () => {
      if (this.ready && this.birdsOn && !this.muted) {
        const ctx = this.ctx;
        const n = 2 + Math.floor(Math.random() * 3);
        const base = 2400 + Math.random() * 1600;
        const pan = ctx.createStereoPanner ? ctx.createStereoPanner() : null;
        if (pan) { pan.pan.value = Math.random() * 2 - 1; pan.connect(this.master); }
        for (let i = 0; i < n; i++) {
          const t = ctx.currentTime + i * 0.13;
          const o = ctx.createOscillator();
          o.type = 'sine';
          o.frequency.setValueAtTime(base, t);
          o.frequency.exponentialRampToValueAtTime(base * (1.25 + Math.random() * 0.3), t + 0.07);
          o.frequency.exponentialRampToValueAtTime(base * 0.9, t + 0.1);
          const g = ctx.createGain();
          g.gain.setValueAtTime(0, t);
          g.gain.linearRampToValueAtTime(0.045, t + 0.01);
          g.gain.exponentialRampToValueAtTime(0.0001, t + 0.11);
          o.connect(g);
          g.connect(pan || this.master);
          o.start(t);
          o.stop(t + 0.14);
        }
      }
      this._timers.push(setTimeout(loop, 2500 + Math.random() * 6500));
    };
    this._timers.push(setTimeout(loop, 1500));
  }

  // ---------- обновление каждый кадр ----------

  update(atm, camera, pos) {
    if (!this.ready) return;
    const ctx = this.ctx, t = ctx.currentTime;
    const out = 1 - atm.indoor;
    const w = atm.w;
    const tc = 0.35;

    this.wind.g.gain.setTargetAtTime(out * (0.04 + 0.1 * w.winter + 0.05 * w.canyon + 0.02 * w.meadow + 0.03 * w.rain), t, tc);
    if (atm.indoor > 0.5) {
      this.rain.g.gain.setTargetAtTime(0.13, t, tc); // дождь за окном квартиры
      this.rainLP.frequency.setTargetAtTime(750, t, 0.2);
    } else {
      this.rain.g.gain.setTargetAtTime(atm.rain * 0.3, t, tc);
      this.rainLP.frequency.setTargetAtTime(6500, t, 0.2);
    }
    const dFire = Math.hypot(pos.x - CAMPFIRE.x, pos.z - CAMPFIRE.z);
    this.nearFire = dFire < 38 && atm.indoor < 0.5;
    this.fire.g.gain.setTargetAtTime(out * 0.5, t, tc);
    const dLake = Math.hypot(pos.x - LAKE.x, pos.z - LAKE.z);
    this.water.g.gain.setTargetAtTime(out * 0.11 * (1 - smoothstep(LAKE.r + 4, LAKE.r + 34, dLake)), t, tc);
    this.birdsOn = (w.meadow + w.autumn * 0.6) > 0.45 && atm.indoor < 0.3 && atm.rain < 0.2;

    // слушатель - камера
    const l = ctx.listener;
    const e = camera.matrixWorld.elements;
    const fx = -e[8], fy = -e[9], fz = -e[10], ux = e[4], uy = e[5], uz = e[6];
    if (l.positionX) {
      l.positionX.value = camera.position.x; l.positionY.value = camera.position.y; l.positionZ.value = camera.position.z;
      l.forwardX.value = fx; l.forwardY.value = fy; l.forwardZ.value = fz;
      l.upX.value = ux; l.upY.value = uy; l.upZ.value = uz;
    } else {
      l.setPosition(camera.position.x, camera.position.y, camera.position.z);
      l.setOrientation(fx, fy, fz, ux, uy, uz);
    }
  }

  // ---------- разовые звуки ----------

  _burst({ type = 'bandpass', f = 1000, q = 0.8, dur = 0.1, g = 0.2, attack = 0.004, f2 = null, delay = 0 }) {
    if (!this.ready || this.muted) return;
    const ctx = this.ctx, t = ctx.currentTime + delay;
    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    const flt = ctx.createBiquadFilter();
    flt.type = type;
    flt.frequency.setValueAtTime(f, t);
    if (f2) flt.frequency.exponentialRampToValueAtTime(f2, t + dur);
    flt.Q.value = q;
    const gn = ctx.createGain();
    gn.gain.setValueAtTime(0, t);
    gn.gain.linearRampToValueAtTime(g, t + attack);
    gn.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    src.connect(flt);
    flt.connect(gn);
    gn.connect(this.master);
    src.start(t, Math.random() * 3);
    src.stop(t + dur + 0.05);
  }

  _tone({ f0, f1 = f0, dur = 0.1, g = 0.12, type = 'sine', delay = 0 }) {
    if (!this.ready || this.muted) return;
    const ctx = this.ctx, t = ctx.currentTime + delay;
    const o = ctx.createOscillator();
    o.type = type;
    o.frequency.setValueAtTime(f0, t);
    o.frequency.exponentialRampToValueAtTime(Math.max(20, f1), t + dur);
    const gn = ctx.createGain();
    gn.gain.setValueAtTime(0, t);
    gn.gain.linearRampToValueAtTime(g, t + 0.006);
    gn.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.connect(gn);
    gn.connect(this.master);
    o.start(t);
    o.stop(t + dur + 0.05);
  }

  footstep(ground, sprint = false) {
    const v = sprint ? 1.25 : 1;
    const r = 0.92 + Math.random() * 0.16;
    switch (ground) {
      case 'path': this._burst({ type: 'lowpass', f: 1800 * r, dur: 0.07, g: 0.26 * v }); this._tone({ f0: 120 * r, f1: 70, dur: 0.07, g: 0.1 * v }); break;
      case 'snow': this._burst({ type: 'highpass', f: 2600 * r, q: 0.4, dur: 0.12, g: 0.2 * v }); this._burst({ type: 'highpass', f: 3200, q: 0.4, dur: 0.08, g: 0.12 * v, delay: 0.05 }); break;
      case 'sand': this._burst({ type: 'lowpass', f: 2600 * r, dur: 0.1, g: 0.2 * v }); break;
      case 'water': this._burst({ type: 'bandpass', f: 700 * r, q: 1, dur: 0.2, g: 0.28 * v }); this._burst({ type: 'highpass', f: 3000, dur: 0.08, g: 0.1 * v, delay: 0.04 }); break;
      case 'wood': this._tone({ f0: 170 * r, f1: 105, dur: 0.07, g: 0.22 * v }); this._burst({ type: 'bandpass', f: 2200, q: 1.5, dur: 0.03, g: 0.1 * v }); break;
      default: this._burst({ type: 'bandpass', f: 1500 * r, q: 0.7, dur: 0.09, g: 0.2 * v });
    }
  }

  jump() { this._burst({ type: 'bandpass', f: 300, f2: 900, q: 0.8, dur: 0.16, g: 0.12 }); }
  land(power = 0.5) { this._tone({ f0: 100, f1: 45, dur: 0.14, g: 0.2 * power + 0.06 }); this._burst({ type: 'lowpass', f: 900, dur: 0.1, g: 0.18 * power + 0.05 }); }
  pickup() { this._tone({ f0: 480, f1: 760, dur: 0.09, g: 0.14 }); }
  put() { this._tone({ f0: 320, f1: 220, dur: 0.08, g: 0.14 }); }
  throwSound() { this._burst({ type: 'bandpass', f: 1600, f2: 380, q: 0.9, dur: 0.26, g: 0.17 }); }
  chat() { this._tone({ f0: 880, dur: 0.07, g: 0.07 }); this._tone({ f0: 1320, dur: 0.09, g: 0.06, delay: 0.07 }); }
  join() { this._tone({ f0: 660, dur: 0.12, g: 0.09 }); this._tone({ f0: 990, dur: 0.16, g: 0.08, delay: 0.1 }); }
  whoosh() { this._burst({ type: 'bandpass', f: 400, f2: 2200, q: 0.7, dur: 0.4, g: 0.16 }); }
}
