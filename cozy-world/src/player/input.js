// Ввод: клавиатура + мышь (pointer lock) и тач (джойстик слева, обзор справа, кнопки).
// Игра читает состояние раз в кадр: оси движения, накопленный поворот камеры и "нажатия" (edge).
//
// Раскладка (как в референсе): E / ЛКМ - взять и использовать, Q - бросить (удерживать - бросок),
// F - толкнуть, X - приветствие, V - микрофон, C - вид от 3-го лица, Enter/T - чат, Tab - список игроков.

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
    this._bind();
  }

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

  // Q: короткое нажатие - бросить, удержание - бросок с силой. Возвращает 0 или силу 0..1.
  takeThrow() {
    const t = this._throw;
    this._throw = undefined;
    return t;
  }

  requestLock() {
    if (this.isTouch || this.locked) return;
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
    this.qDownAt = 0;
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
      if ((!this.locked && !this.dragging) || !this.enabled) return;
      this.lookDX += e.movementX;
      this.lookDY += e.movementY;
    });
    window.addEventListener('mouseup', () => { this.dragging = false; });
    this.canvas.addEventListener('mousedown', (e) => {
      if (!this.enabled || this.isTouch) return;
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
      if (this.enabled) this.wheel = (this.wheel || 0) + Math.sign(e.deltaY);
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
      this.knob.style.transform = 'translate(0px, 0px)';
    };
    J.addEventListener('pointerup', endJoy);
    J.addEventListener('pointercancel', endJoy);

    const P = this.pad;
    P.addEventListener('pointerdown', (e) => {
      if (this.lookId !== null) return;
      this.lookId = e.pointerId;
      P.setPointerCapture(e.pointerId);
      this.last = { x: e.clientX, y: e.clientY };
      this.tapAt = performance.now();
      this.moved = 0;
    });
    P.addEventListener('pointermove', (e) => {
      if (e.pointerId !== this.lookId) return;
      const dx = e.clientX - this.last.x, dy = e.clientY - this.last.y;
      this.last = { x: e.clientX, y: e.clientY };
      this.moved += Math.abs(dx) + Math.abs(dy);
      this.input.lookDX += dx * 1.9;
      this.input.lookDY += dy * 1.9;
    });
    const endLook = (e) => {
      if (e.pointerId !== this.lookId) return;
      this.lookId = null;
      // короткий тап по экрану = "использовать"
      if (performance.now() - this.tapAt < 220 && this.moved < 8) this.input.press('use');
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
  }
}
