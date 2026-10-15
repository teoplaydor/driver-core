// Звук целиком синтезируется в WebAudio, поэтому файлов не нужно: ветер, дождь, костёр с потрескиванием,
// птицы, плеск озера, шаги по разным поверхностям и короткие интерфейсные звуки; звуки предметов в их точке
// (замах и удар битой, зонт, щелчки), гитара (Карплус–Стронг) и лоу-фай из бумбокса.

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
    this._lis = camera.position;
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

  _burst({ type = 'bandpass', f = 1000, q = 0.8, dur = 0.1, g = 0.2, attack = 0.004, f2 = null, delay = 0, dest = null }) {
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
    gn.connect(dest || this.master);
    src.start(t, Math.random() * 3);
    src.stop(t + dur + 0.05);
  }

  _tone({ f0, f1 = f0, dur = 0.1, g = 0.12, type = 'sine', delay = 0, dest = null }) {
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
    gn.connect(dest || this.master);
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

  // ---------- предметы (звук в точке предмета) ----------

  // Пространственный выход для разового звука; через пару секунд отключается сам.
  _at(pos, ref = 2.5, roll = 1.2) {
    const p = this.ctx.createPanner();
    p.panningModel = 'equalpower';
    p.distanceModel = 'inverse';
    p.refDistance = ref;
    p.rolloffFactor = roll;
    p.maxDistance = 90;
    this._setPos(p, pos.x, pos.y, pos.z);
    p.connect(this.master);
    setTimeout(() => p.disconnect(), 3500);
    return p;
  }

  _ok() { return this.ready && !this.muted; }

  swing(pos) {
    if (!this._ok()) return;
    const dest = this._at(pos);
    this._burst({ type: 'bandpass', f: 320, f2: 1900, q: 1.6, dur: 0.26, g: 0.34, attack: 0.07, dest });
    this._burst({ type: 'lowpass', f: 600, f2: 200, q: 0.7, dur: 0.22, g: 0.16, attack: 0.05, delay: 0.05, dest });
  }

  // Бита по предмету: деревянный «тук» (по мячу — пружинистый «бум»).
  thwack(pos, kind = '') {
    if (!this._ok()) return;
    const dest = this._at(pos, 3);
    if (kind === 'ball' || kind === 'teddy') {
      this._tone({ f0: 180, f1: 420, dur: 0.16, g: 0.26, type: 'triangle', dest });
      this._burst({ type: 'lowpass', f: 900, dur: 0.06, g: 0.25, dest });
      return;
    }
    this._tone({ f0: 260, f1: 140, dur: 0.1, g: 0.3, type: 'triangle', dest });
    this._burst({ type: 'bandpass', f: 1500, q: 1.3, dur: 0.07, g: 0.34, dest });
  }

  // Мультяшный «бонк» по игроку.
  bonk(pos, k = 1) {
    if (!this._ok()) return;
    const dest = this._at(pos, 3);
    this._tone({ f0: 620, f1: 170, dur: 0.24, g: 0.2 * k, type: 'triangle', dest });
    this._tone({ f0: 130, f1: 55, dur: 0.16, g: 0.32 * k, dest });
    this._burst({ type: 'bandpass', f: 1100, q: 1, dur: 0.06, g: 0.3 * k, dest });
  }

  // Предмет упал на землю.
  thud(pos, power = 0.5, kind = '') {
    if (!this._ok()) return;
    const dest = this._at(pos, 2);
    const hi = kind === 'mug' || kind === 'cup' || kind === 'teapot' || kind === 'lantern';
    if (hi) this._tone({ f0: 1300, f1: 1100, dur: 0.12, g: 0.06 * power, dest });
    this._tone({ f0: 150, f1: 70, dur: 0.09, g: 0.18 * power, dest });
    this._burst({ type: 'lowpass', f: 1100, dur: 0.07, g: 0.2 * power, dest });
  }

  // Зонт: раскрылся — хлопок ткани, сложился — шорох; и щелчок защёлки.
  umbrella(pos, open) {
    if (!this._ok()) return;
    const dest = this._at(pos, 2);
    if (open) {
      this._burst({ type: 'lowpass', f: 1400, f2: 260, q: 0.8, dur: 0.24, g: 0.42, attack: 0.03, delay: 0.05, dest });
      this._tone({ f0: 1900, f1: 1500, dur: 0.025, g: 0.1, dest });
    } else {
      this._burst({ type: 'bandpass', f: 500, f2: 1600, q: 0.9, dur: 0.2, g: 0.22, attack: 0.05, dest });
      this._tone({ f0: 1700, f1: 1400, dur: 0.025, g: 0.09, delay: 0.17, dest });
    }
  }

  click(pos) {
    if (!this._ok()) return;
    const dest = this._at(pos, 1.5);
    this._tone({ f0: 2700, f1: 2000, dur: 0.028, g: 0.12, dest });
    this._burst({ type: 'highpass', f: 3800, dur: 0.018, g: 0.12, dest });
  }

  squeak(pos) {
    if (!this._ok()) return;
    const dest = this._at(pos, 1.5);
    this._tone({ f0: 900, f1: 1500, dur: 0.12, g: 0.07, dest });
    this._tone({ f0: 1500, f1: 1050, dur: 0.14, g: 0.06, delay: 0.11, dest });
  }

  // ---------- гитара: Карплус–Стронг ----------

  _pluck(freq) {
    const cache = this._plucks || (this._plucks = new Map());
    const key = Math.round(freq * 10);
    if (cache.has(key)) return cache.get(key);
    const ctx = this.ctx, sr = ctx.sampleRate, len = Math.floor(sr * 2.4);
    const buf = ctx.createBuffer(1, len, sr), d = buf.getChannelData(0);
    const N = Math.max(2, Math.round(sr / freq));
    const ring = new Float32Array(N);
    let lp = 0;
    for (let i = 0; i < N; i++) { lp = lp * 0.55 + (Math.random() * 2 - 1) * 0.45; ring[i] = lp; } // мягкий щипок медиатором
    const decay = 0.997 - Math.min(0.004, freq / 150000); // верхние струны гаснут быстрее
    let idx = 0;
    for (let i = 0; i < len; i++) {
      const cur = ring[idx], nxt = ring[idx + 1 === N ? 0 : idx + 1];
      d[i] = cur;
      ring[idx] = (cur + nxt) * 0.5 * decay;
      idx = idx + 1 === N ? 0 : idx + 1;
    }
    cache.set(key, buf);
    return buf;
  }

  // Аккорд «сверху вниз» по струнам с корпусным резонансом.
  strum(chord, pos) {
    if (!this._ok()) return;
    const ctx = this.ctx, t = ctx.currentTime + 0.01;
    const notes = STRUMS[chord % STRUMS.length];
    const dest = this._at(pos, 2.5, 1.05);
    const body = ctx.createBiquadFilter();
    body.type = 'peaking'; body.frequency.value = 190; body.Q.value = 1.1; body.gain.value = 7;
    const air = ctx.createBiquadFilter();
    air.type = 'lowpass'; air.frequency.value = 5200;
    body.connect(air);
    air.connect(dest);
    notes.forEach((f, i) => {
      const src = ctx.createBufferSource();
      src.buffer = this._pluck(f);
      const g = ctx.createGain();
      g.gain.value = 0.3 * (0.85 + Math.random() * 0.3);
      src.connect(g);
      g.connect(body);
      src.start(t + i * 0.017 + Math.random() * 0.004);
    });
    setTimeout(() => { body.disconnect(); air.disconnect(); }, 3200);
  }

  // ---------- бумбокс: генеративный лоу-фай ----------
  // Музыка детерминирована от общих часов (Date.now): у всех игроков в комнате играет одно и то же, в одной фазе.
  // Один общий генератор, из каждого включённого бумбокса — свой пространственный выход.

  music(id, on, pos) {
    const boxes = this._boxes || (this._boxes = new Map());
    if (on) {
      if (!boxes.has(id)) boxes.set(id, { pos: { x: pos.x, y: pos.y, z: pos.z }, out: null });
    } else {
      const b = boxes.get(id);
      if (b?.out && this.ctx) {
        const g = b.out.g, t = this.ctx.currentTime;
        g.gain.setTargetAtTime(0, t, 0.08);
        setTimeout(() => { g.disconnect(); b.out.p.disconnect(); }, 600);
      }
      boxes.delete(id);
    }
    if (boxes.size && !this._musicTimer) this._musicTimer = setInterval(() => this._schedule(), 80);
  }

  musicPos(id, pos) {
    const b = this._boxes?.get(id);
    if (!b) return;
    b.pos.x = pos.x; b.pos.y = pos.y; b.pos.z = pos.z;
    if (b.out) this._setPos(b.out.p, pos.x, pos.y + 0.1, pos.z);
  }

  // 0..1: огибающая бочки — для «подскока» бумбокса в такт.
  beat() {
    if (!this.ready || this._kickAt === undefined) return 0;
    const dt = this.ctx.currentTime - this._kickAt;
    return dt < 0 ? 0 : Math.exp(-dt * 10);
  }

  _musicBus() {
    if (this.mBus) return this.mBus;
    const ctx = this.ctx;
    const bus = ctx.createGain();
    bus.gain.value = 0.9;
    const lofi = ctx.createBiquadFilter(); // лоу-фай: глухой верх и чуть подчёркнутый низ
    lofi.type = 'lowpass'; lofi.frequency.value = 3400; lofi.Q.value = 0.5;
    const warm = ctx.createBiquadFilter();
    warm.type = 'lowshelf'; warm.frequency.value = 180; warm.gain.value = 3;
    bus.connect(lofi);
    lofi.connect(warm);
    this.mOut = warm;
    // «плавание» плёнки: общий медленный LFO на расстройку нот
    const wow = ctx.createOscillator();
    wow.frequency.value = 0.35;
    this.wow = ctx.createGain();
    this.wow.gain.value = 7;
    wow.connect(this.wow);
    wow.start();
    // винил: шипение и щелчки — петля из буфера
    const len = ctx.sampleRate * 3, vb = ctx.createBuffer(1, len, ctx.sampleRate), d = vb.getChannelData(0);
    let lp = 0;
    for (let i = 0; i < len; i++) {
      lp = lp * 0.82 + (Math.random() * 2 - 1) * 0.18;
      d[i] = lp * 0.05 + (Math.random() < 0.00035 ? (Math.random() - 0.5) * 0.9 : 0);
    }
    const vs = ctx.createBufferSource();
    vs.buffer = vb; vs.loop = true;
    const vg = ctx.createGain();
    vg.gain.value = 0.35;
    vs.connect(vg);
    vg.connect(bus);
    vs.start();
    this.mBus = bus;
    return bus;
  }

  _schedule() {
    const boxes = this._boxes;
    if (!boxes || !boxes.size) { clearInterval(this._musicTimer); this._musicTimer = 0; return; }
    if (!this.ready) return;
    const ctx = this.ctx;
    this._musicBus();
    let near = false;
    for (const b of boxes.values()) {
      if (!b.out) {
        const p = ctx.createPanner();
        p.panningModel = 'equalpower'; p.distanceModel = 'inverse';
        p.refDistance = 3; p.rolloffFactor = 1.35; p.maxDistance = 80;
        this._setPos(p, b.pos.x, b.pos.y + 0.1, b.pos.z);
        const g = ctx.createGain();
        g.gain.value = 0;
        g.gain.setTargetAtTime(0.62, ctx.currentTime, 0.15);
        this.mOut.connect(g);
        g.connect(p);
        p.connect(this.master);
        b.out = { p, g };
      }
      const l = this._lis;
      if (!l || Math.hypot(l.x - b.pos.x, l.z - b.pos.z) < 70) near = true;
    }
    const step = 60 / 78 / 4;
    const ep = Date.now() / 1000;
    const now = ctx.currentTime;
    let k = Math.floor(ep / step);
    if (this._step !== undefined && this._step >= k && this._step - k < 8) k = this._step + 1;
    for (; k * step - ep < 0.3; k++) {
      const t = now + (k * step - ep);
      this._step = k;
      if (t < now - 0.01 || !near || this.muted) continue; // далеко — ноты не играем, но время идёт
      this._musicStep(k, Math.max(now, t), step);
    }
  }

  _musicStep(k, t, step) {
    const s = k % 16, bar = Math.floor(k / 16);
    const r = (n) => { let h = Math.imul(k * 31 + n, 2654435761) >>> 0; h ^= h >>> 15; h = Math.imul(h, 2246822519) >>> 0; return (h >>> 0) / 4294967296; };
    const prog = PROGS[Math.floor(bar / 8) % PROGS.length];
    const ch = prog[bar % 4];
    const tt = t + (s % 2 ? step * 0.2 : 0); // свинг
    // барабаны «бум-бэп»
    if (s === 0 || s === 10 || (s === 7 && r(1) > 0.55)) { this._kick(tt); this._kickAt = tt; }
    if (s === 4 || s === 12) this._snare(tt);
    if (s % 2 === 0) this._hat(tt, s % 4 === 2 ? 0.8 : 0.5, s === 14 && r(2) > 0.6);
    else if (r(3) > 0.75) this._hat(tt, 0.28, false);
    // электропиано: аккорд на сильную долю, иногда синкопа
    if (s === 0) for (const n of ch) this._keys(n, t, 2.4, 0.11);
    if (s === 11 && r(4) > 0.45) for (const n of ch.slice(1)) this._keys(n + 12, tt, 0.9, 0.05);
    // бас
    const root = ch[0] - 12;
    if (s === 0) this._bass(root, t, step * 5);
    if (s === 6 && r(5) > 0.3) this._bass(root, tt, step * 2);
    if (s === 10) this._bass(root + 7, tt, step * 3);
    if (s === 14 && r(6) > 0.5) this._bass(root + (r(7) > 0.5 ? 5 : 3), tt, step * 2);
    // мелодия: «калимба» по пентатонике
    if (s % 2 === 0 && r(8) > 0.7) {
      const scale = [0, 2, 4, 7, 9, 12, 14, 16];
      this._pluckNote(72 + scale[Math.floor(r(9) * scale.length)], tt);
    }
  }

  _note(midi) { return 440 * 2 ** ((midi - 69) / 12); }

  _env(g, t, a, peak, dur) {
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(peak, t + a);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  }

  _keys(midi, t, dur, vol) {
    const ctx = this.ctx, f = this._note(midi);
    const g = ctx.createGain();
    this._env(g, t, 0.012, vol, dur);
    for (const [mul, amp, type] of [[1, 1, 'sine'], [2, 0.22, 'sine'], [3.01, 0.06, 'triangle']]) {
      const o = ctx.createOscillator();
      o.type = type;
      o.frequency.value = f * mul;
      this.wow.connect(o.detune);
      const og = ctx.createGain();
      og.gain.value = amp;
      o.connect(og);
      og.connect(g);
      o.start(t);
      o.stop(t + dur + 0.05);
      o.onended = () => { og.disconnect(); this.wow.disconnect(o.detune); };
    }
    g.connect(this.mBus);
    setTimeout(() => g.disconnect(), (t - ctx.currentTime + dur + 0.3) * 1000);
  }

  _bass(midi, t, dur) {
    const ctx = this.ctx;
    const o = ctx.createOscillator();
    o.type = 'triangle';
    o.frequency.value = this._note(midi);
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass'; lp.frequency.value = 420;
    const g = ctx.createGain();
    this._env(g, t, 0.01, 0.34, dur + 0.15);
    o.connect(lp); lp.connect(g); g.connect(this.mBus);
    o.start(t); o.stop(t + dur + 0.2);
    o.onended = () => { lp.disconnect(); g.disconnect(); };
  }

  _pluckNote(midi, t) {
    const ctx = this.ctx;
    const o = ctx.createOscillator();
    o.type = 'sine';
    o.frequency.value = this._note(midi);
    const o2 = ctx.createOscillator();
    o2.type = 'sine';
    o2.frequency.value = this._note(midi) * 4.02;
    const g = ctx.createGain(), g2 = ctx.createGain();
    this._env(g, t, 0.004, 0.07, 0.7);
    this._env(g2, t, 0.002, 0.02, 0.12);
    o.connect(g); o2.connect(g2); g.connect(this.mBus); g2.connect(this.mBus);
    o.start(t); o2.start(t); o.stop(t + 0.75); o2.stop(t + 0.15);
    o.onended = () => { g.disconnect(); g2.disconnect(); };
  }

  _kick(t) {
    const ctx = this.ctx;
    const o = ctx.createOscillator();
    o.frequency.setValueAtTime(115, t);
    o.frequency.exponentialRampToValueAtTime(42, t + 0.16);
    const g = ctx.createGain();
    this._env(g, t, 0.003, 0.75, 0.3);
    o.connect(g); g.connect(this.mBus);
    o.start(t); o.stop(t + 0.32);
    o.onended = () => g.disconnect();
  }

  _snare(t) {
    this._burst({ type: 'bandpass', f: 1700, q: 0.7, dur: 0.17, g: 0.24, delay: Math.max(0, t - this.ctx.currentTime), dest: this.mBus });
    this._tone({ f0: 210, f1: 150, dur: 0.07, g: 0.12, delay: Math.max(0, t - this.ctx.currentTime), dest: this.mBus });
  }

  _hat(t, v, open) {
    this._burst({ type: 'highpass', f: 6500, q: 0.6, dur: open ? 0.2 : 0.035, g: 0.07 * v, attack: 0.002, delay: Math.max(0, t - this.ctx.currentTime), dest: this.mBus });
  }

  // ---------- дождь по раскрытому зонту ----------

  umbrellaRain(level) {
    if (!this.ready) return;
    const ctx = this.ctx;
    if (!this._patter) {
      if (level < 0.02) return;
      // петля из коротких «капель» с разной громкостью
      const len = ctx.sampleRate * 2, buf = ctx.createBuffer(1, len, ctx.sampleRate), d = buf.getChannelData(0);
      for (let n = 0; n < 260; n++) {
        const at = Math.floor(Math.random() * (len - 400)), a = 0.15 + Math.random() * 0.85, w = 40 + Math.random() * 140;
        for (let i = 0; i < 400; i++) d[at + i] += (Math.random() * 2 - 1) * a * Math.exp(-i / w);
      }
      const src = ctx.createBufferSource();
      src.buffer = buf; src.loop = true;
      const f = ctx.createBiquadFilter();
      f.type = 'bandpass'; f.frequency.value = 2600; f.Q.value = 0.6;
      const g = ctx.createGain();
      g.gain.value = 0;
      src.connect(f); f.connect(g); g.connect(this.master);
      src.start();
      this._patter = g;
    }
    this._patter.gain.setTargetAtTime(this.muted ? 0 : level * 0.5, ctx.currentTime, 0.25);
  }
}

// Аккорды гитары (частоты открытых струн): C, G, Am, F.
const STRUMS = [
  [130.81, 164.81, 196.0, 261.63, 329.63],
  [98.0, 123.47, 146.83, 196.0, 246.94, 392.0],
  [110.0, 164.81, 220.0, 261.63, 329.63],
  [87.31, 130.81, 174.61, 220.0, 261.63, 349.23],
];
// Последовательности септаккордов для лоу-фая (MIDI): ii–V–I–vi и т.п.
const PROGS = [
  [[62, 65, 69, 72], [55, 59, 62, 65], [60, 64, 67, 71], [57, 60, 64, 67]],
  [[53, 57, 60, 64], [52, 55, 59, 62], [50, 53, 57, 60], [55, 59, 62, 65]],
  [[57, 60, 64, 67], [53, 57, 60, 64], [60, 64, 67, 71], [55, 59, 62, 65]],
];
