// Ввод: клавиатура + мышь (pointer lock) и тач (джойстик слева, обзор справа, кнопки).
// Игра читает состояние раз в кадр: оси движения, накопленный поворот камеры и "нажатия" (edge).
//
// Раскладка (как в референсе): E / ЛКМ - взять и использовать, Q - бросить (удерживать - бросок),
// F - толкнуть, X - приветствие, V - микрофон, C - камера (1-е лицо → 3-е → сверху), Enter/T - чат, Tab - список игроков.
// Вид сверху (mode === 'top'): без pointer lock; ЛКМ / тап по экрану - идти туда или к предмету (taps),
// ПКМ / перетаскивание - поворот камеры, колесо / щипок - зум (takeZoom), поворот двумя пальцами - takeTwist.

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);

const KEY_ACTIONS = {
  Space: 'jump', KeyE: 'use', KeyF: 'push', KeyX: 'emote', KeyV: 'mic', KeyC: 'view', KeyT: 'chat', Enter: 'chat', KeyM: 'mute',
};

export class Input {
  constructor(canvas) {
    this.canvas = canvas;
    this.keys = new Set();
    this.edges = new Set();
    this.enabled = false;
    this.locked = false;
    this.isTouch = false;
    this.touchAxis = { x: 0, y: 0 };
    this.sprintToggle = false;
    this.lookDX = 0;
    this.lookDY = 0;
    this.sensitivity = 1;
    this.qDownAt = 0;
    this.tab = false;
    this.onLockChange = null;
    this.mode = 'fp'; // режим камеры (C1) — ставит игра
    this.touchMag = 0; // отклонение джойстика 0..1 (сильное — бег в виде сверху)
    this.taps = []; // вид сверху: тапы/клики по экрану { x, y } (CSS-пиксели)
    this.zoomMul = 1; // вид сверху: множитель дистанции (щипок, колесо)
    this.twist = 0; // вид сверху: поворот двумя пальцами, рад
    this.mouse = { x: 0, y: 0, on: false }; // вид сверху на ПК: курсор над холстом
    // счётчик кадров: тап, уложившийся в пару кадров, — всегда тап, даже если кадры шли по секунде (слабое устройство)
    this.rafN = 0;
    const tick = () => { this.rafN++; requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
    this._bind();
  }

  // Короткое касание/клик: по времени или «за пару кадров» (60 к/с — это 50 мс).
  isTap(t0, n0, t1, maxMs) { return t1 - t0 < maxMs || this.rafN - n0 <= 3; }

  // Нажатие, которое можно "забрать" один раз.
  consume(name) {
    if (!this.edges.has(name)) return false;
    this.edges.delete(name);
    return true;
  }

  press(name) { if (this.enabled) this.edges.add(name); }

  get typing() {
    const a = document.activeElement;
    return !!a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA');
  }

  axes() {
    const k = this.keys;
    let x = (k.has('KeyD') || k.has('ArrowRight') ? 1 : 0) - (k.has('KeyA') || k.has('ArrowLeft') ? 1 : 0);
    let y = (k.has('KeyW') || k.has('ArrowUp') ? 1 : 0) - (k.has('KeyS') || k.has('ArrowDown') ? 1 : 0);
    x += this.touchAxis.x;
    y += this.touchAxis.y;
    const l = Math.hypot(x, y);
    if (l > 1) { x /= l; y /= l; }
    return { x, y };
  }

  get sprint() {
    return this.keys.has('ShiftLeft') || this.keys.has('ShiftRight') || this.sprintToggle;
  }

  takeLook() {
    const d = { x: this.lookDX, y: this.lookDY };
    this.lookDX = 0;
    this.lookDY = 0;
    return d;
  }

  takeZoom() {
    const z = this.zoomMul;
    this.zoomMul = 1;
    return z;
  }

  takeTwist() {
    const t = this.twist;
    this.twist = 0;
    return t;
  }

  // Q: короткое нажатие - бросить, удержание - бросок с силой. Возвращает 0 или силу 0..1.
  takeThrow() {
    const t = this._throw;
    this._throw = undefined;
    return t;
  }

  requestLock() {
    if (this.isTouch || this.locked || this.mode === 'top') return;
    try {
      const p = this.canvas.requestPointerLock?.();
      if (p && p.catch) p.catch(() => {});
    } catch { /* игнорируем */ }
  }

  releaseAll() {
    this.keys.clear();
    this.edges.clear();
    this.touchAxis.x = 0;
    this.touchAxis.y = 0;
    this.touchMag = 0;
    this.qDownAt = 0;
    this.taps.length = 0;
    this.rotDrag = false;
    this.lmb = null;
  }

  // Смена режима камеры: в виде сверху курсор свободен (клики по миру), в остальных — pointer lock.
  setMode(mode) {
    if (mode === this.mode) return;
    this.mode = mode;
    this.rotDrag = false;
    this.lmb = null;
    this.dragging = false;
    if (mode === 'top') {
      if (this.locked) document.exitPointerLock?.();
    } else if (this.enabled) this.requestLock();
  }

  _bind() {
    window.addEventListener('keydown', (e) => {
      if (!this.enabled || this.typing) return;
      if (e.code === 'Tab') { e.preventDefault(); this.tab = true; return; }
      if (e.code === 'KeyQ') {
        e.preventDefault();
        if (!e.repeat) this.qDownAt = performance.now();
        return;
      }
      if (e.code.startsWith('Arrow') || e.code === 'Space') e.preventDefault();
      if (!e.repeat) {
        this.keys.add(e.code);
        const act = KEY_ACTIONS[e.code];
        if (act) this.edges.add(act);
      }
    });
    window.addEventListener('keyup', (e) => {
      this.keys.delete(e.code);
      if (e.code === 'Tab') this.tab = false;
      if (e.code === 'KeyQ' && this.qDownAt) {
        const held = (performance.now() - this.qDownAt) / 1000;
        this.qDownAt = 0;
        if (this.enabled) this._throw = held > 0.3 ? Math.min(1, 0.25 + held * 0.75) : 0;
      }
    });
    window.addEventListener('blur', () => this.releaseAll());

    document.addEventListener('pointerlockchange', () => {
      this.locked = document.pointerLockElement === this.canvas;
      if (!this.locked) this.releaseAll();
      this.onLockChange?.(this.locked);
    });
    // Обзор: через pointer lock, а если среда его не даёт (iframe без разрешения) - перетаскиванием мыши
    document.addEventListener('mousemove', (e) => {
      if (!this.enabled) return;
      if (this.mode === 'top') {
        // вид сверху: ПКМ (или ЛКМ с протяжкой) крутит камеру; позиция курсора — для подсветки под ним
        this.mouse.x = e.clientX; this.mouse.y = e.clientY; this.mouse.on = e.target === this.canvas;
        const l = this.lmb;
        if (l) l.moved += Math.abs(e.movementX) + Math.abs(e.movementY);
        if (this.rotDrag || (l && l.moved > 6)) this.lookDX += e.movementX;
        return;
      }
      if (!this.locked && !this.dragging) return;
      this.lookDX += e.movementX;
      this.lookDY += e.movementY;
    });
    window.addEventListener('mouseup', (e) => {
      this.dragging = false;
      if (e.button === 2) this.rotDrag = false;
      const l = this.lmb;
      if (e.button === 0 && l) {
        this.lmb = null;
        if (this.enabled && this.mode === 'top' && l.moved <= 6 && this.isTap(l.t, l.n, e.timeStamp, 1200)) this.taps.push({ x: e.clientX, y: e.clientY });
      }
    });
    this.canvas.addEventListener('mousedown', (e) => {
      if (!this.enabled || this.isTouch) return;
      if (this.mode === 'top') {
        if (e.button === 2) this.rotDrag = true;
        if (e.button === 0) this.lmb = { t: e.timeStamp, n: this.rafN, moved: 0 };
        return;
      }
      if (!this.locked) {
        this.requestLock();
        if (e.button === 0) this.dragging = true;
        return;
      }
      if (e.button === 0) this.edges.add('use');
      if (e.button === 2) this.edges.add('push');
    });
    this.canvas.addEventListener('contextmenu', (e) => e.preventDefault());
    this.canvas.addEventListener('wheel', (e) => {
      if (!this.enabled) return;
      if (this.mode === 'top') this.zoomMul *= Math.pow(1.0015, clamp(e.deltaY, -240, 240)); // тачпад даёт мелкие шаги
      else this.wheel = (this.wheel || 0) + Math.sign(e.deltaY);
    }, { passive: true });
  }
}

// ---------- сенсорное управление ----------

export class TouchControls {
  constructor(root, input) {
    this.root = root;
    this.input = input;
    this.joy = root.querySelector('#joy');
    this.knob = root.querySelector('#knob');
    this.pad = root.querySelector('#lookpad');
    this.joyId = null;
    this.lookId = null;
    this.joyCenter = { x: 0, y: 0 };
    this.last = { x: 0, y: 0 };
    this._bind();
  }

  show(v) { this.root.hidden = !v; }

  _bind() {
    const R = 58;
    const J = this.joy;
    J.addEventListener('pointerdown', (e) => {
      if (this.joyId !== null) return;
      this.joyId = e.pointerId;
      J.setPointerCapture(e.pointerId);
      const r = J.getBoundingClientRect();
      this.joyCenter = { x: r.left + r.width / 2, y: r.top + r.height / 2 };
      this._moveJoy(e, R);
    });
    J.addEventListener('pointermove', (e) => { if (e.pointerId === this.joyId) this._moveJoy(e, R); });
    const endJoy = (e) => {
      if (e.pointerId !== this.joyId) return;
      this.joyId = null;
      this.input.touchAxis.x = 0;
      this.input.touchAxis.y = 0;
      this.input.touchMag = 0;
      this.knob.classList.remove('run');
      this.knob.style.transform = 'translate(0px, 0px)';
    };
    J.addEventListener('pointerup', endJoy);
    J.addEventListener('pointercancel', endJoy);

    // Правая часть экрана (в виде сверху — весь экран вне джойстика и кнопок): один палец — обзор/поворот камеры,
    // короткий тап — «использовать» (в виде сверху — идти туда / к предмету); два пальца — щипок и поворот.
    const P = this.pad;
    const ptrs = this.ptrs = new Map();
    const pair = () => {
      const it = ptrs.values();
      const a = it.next().value, b = it.next().value;
      return { d: Math.hypot(b.x - a.x, b.y - a.y), a: Math.atan2(b.y - a.y, b.x - a.x) };
    };
    P.addEventListener('pointerdown', (e) => {
      if (ptrs.size >= 2) return;
      try { P.setPointerCapture(e.pointerId); } catch { /* указатель уже отпущен */ }
      ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (ptrs.size === 1) {
        this.tapAt = e.timeStamp; // время события, а не обработчика: на медленном кадре тап не превращается в «долгое касание»
        this.tapN = this.input.rafN;
        this.tapPos = { x: e.clientX, y: e.clientY };
        this.moved = 0;
        this.multi = false;
      } else {
        this.multi = true;
        this.gesture = pair();
      }
    });
    P.addEventListener('pointermove', (e) => {
      const q = ptrs.get(e.pointerId);
      if (!q) return;
      const dx = e.clientX - q.x, dy = e.clientY - q.y;
      q.x = e.clientX; q.y = e.clientY;
      if (ptrs.size === 1) {
        this.moved += Math.abs(dx) + Math.abs(dy);
        if (this.multi) return; // второй палец только что убрали — не дёргаем камеру
        this.input.lookDX += dx * 1.9;
        this.input.lookDY += dy * 1.9;
      } else if (this.input.mode === 'top') {
        const g = pair(), g0 = this.gesture;
        if (g0 && g.d > 8 && g0.d > 8) {
          this.input.zoomMul *= g0.d / g.d; // пальцы врозь — ближе
          let da = g.a - g0.a;
          if (da > Math.PI) da -= Math.PI * 2;
          if (da < -Math.PI) da += Math.PI * 2;
          this.input.twist -= da; // мир поворачивается вслед за пальцами
        }
        this.gesture = g;
      }
    });
    const endLook = (e) => {
      if (!ptrs.has(e.pointerId)) return;
      ptrs.delete(e.pointerId);
      if (ptrs.size === 1) { this.gesture = null; return; }
      if (ptrs.size) return;
      // короткий тап по экрану: в виде сверху — идти туда / к предмету, иначе «использовать»
      // (в виде сверху долгое касание без сдвига ничего другого не значит — тапом считаем и его)
      const top = this.input.mode === 'top';
      if (!this.multi && this.moved < 12 && this.input.isTap(this.tapAt, this.tapN, e.timeStamp, top ? 1500 : 300)) {
        if (top) { if (this.input.enabled) this.input.taps.push({ ...this.tapPos }); } else this.input.press('use');
      }
    };
    P.addEventListener('pointerup', endLook);
    P.addEventListener('pointercancel', endLook);

    for (const b of this.root.querySelectorAll('[data-act]')) {
      const act = b.dataset.act;
      b.addEventListener('pointerdown', (e) => {
        e.preventDefault();
        b.classList.add('down');
        if (act === 'sprint') {
          this.input.sprintToggle = !this.input.sprintToggle;
          b.classList.toggle('on', this.input.sprintToggle);
        } else if (act === 'throw') {
          this.input.qDownAt = performance.now();
        } else {
          this.input.press(act);
        }
      });
      const up = () => {
        b.classList.remove('down');
        if (act === 'throw' && this.input.qDownAt) {
          const held = (performance.now() - this.input.qDownAt) / 1000;
          this.input.qDownAt = 0;
          this.input._throw = held > 0.3 ? Math.min(1, 0.25 + held * 0.75) : 0;
        }
      };
      b.addEventListener('pointerup', up);
      b.addEventListener('pointercancel', up);
      b.addEventListener('pointerleave', up);
    }
  }

  _moveJoy(e, R) {
    let dx = e.clientX - this.joyCenter.x, dy = e.clientY - this.joyCenter.y;
    const l = Math.hypot(dx, dy);
    if (l > R) { dx = (dx / l) * R; dy = (dy / l) * R; }
    this.knob.style.transform = `translate(${dx}px, ${dy}px)`;
    const nx = dx / R, ny = dy / R;
    const dead = 0.14;
    const m = Math.hypot(nx, ny);
    const k = m < dead ? 0 : (m - dead) / (1 - dead) / (m || 1);
    this.input.touchAxis.x = nx * k;
    this.input.touchAxis.y = -ny * k;
    this.input.touchMag = Math.min(1, m * k);
    this.knob.classList.toggle('run', this.input.mode === 'top' && this.input.touchMag > 0.86); // бег в виде сверху
  }
}
