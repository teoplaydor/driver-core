// HTML-интерфейс: вход, HUD, чат, подсказки. Игровая логика сюда не пишется:
// UI только показывает состояние и вызывает колбэки.

import { SKINS, OUTFITS, HATS, GLASSES, HAIRS, HAIR_STYLES, FACIAL, randomLook } from '../player/avatar.js';

// WS1: добавлены причёска и борода (look из 7 элементов)
const LOOK_ROWS = [
  { key: 'skin', label: 'Кожа', values: SKINS.map((_, i) => `Тон ${i + 1}`), colors: SKINS },
  { key: 'outfit', label: 'Одежда', values: OUTFITS.map((o) => o.name) },
  { key: 'hat', label: 'Шляпа', values: HATS },
  { key: 'glasses', label: 'Очки', values: GLASSES },
  { key: 'hairStyle', label: 'Причёска', values: HAIR_STYLES },
  { key: 'hair', label: 'Волосы', values: HAIRS.map((_, i) => `Цвет ${i + 1}`), colors: HAIRS },
  { key: 'facial', label: 'Борода', values: FACIAL },
];

const $ = (id) => document.getElementById(id);

export class UI {
  constructor() {
    this.menu = $('menu');
    this.hud = $('hud');
    this.nick = $('nick');
    this.room = $('room');
    this.play = $('play');
    this.status = $('status');
    this.crosshair = $('crosshair');
    this.hints = $('hints');
    this.help = $('help');
    this.chatlog = $('chatlog');
    this.chatform = $('chatform');
    this.chatinput = $('chatinput');
    this.playersEl = $('players');
    this.toastEl = $('toast');
    this.pauseEl = $('pause');
    this.micEl = $('mic');
    this.fadeEl = $('fade');
    this.tlabel = $('tlabel');
    this._tlKey = '';
    this._tlBelow = false;
    this._hintKey = '';
    this._toastT = 0;
    this.onPlay = null;
    this.onChat = null;
    this.onResume = null;
    this.onLookChange = null;
    this.look = randomLook();
    this.rows = [];
    this._buildLooks();

    $('dice').addEventListener('click', () => {
      this.look = randomLook();
      this._refreshLooks();
      this.onLookChange?.(this.look);
    });
    this.play.addEventListener('click', () => this._play());
    for (const el of [this.nick, this.room]) {
      el.addEventListener('keydown', (e) => { if (e.key === 'Enter') this._play(); });
    }
    $('copy').addEventListener('click', () => this.onCopy?.());
    this.pauseEl.addEventListener('click', () => this.onResume?.());
    this.chatform.addEventListener('submit', (e) => {
      e.preventDefault();
      const text = this.chatinput.value.trim();
      this.chatinput.value = '';
      this.closeChat();
      if (text) this.onChat?.(text);
    });
    this.chatinput.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { this.chatinput.value = ''; this.closeChat(); }
      e.stopPropagation();
    });

    try {
      this.nick.value = localStorage.getItem('cozy.nick') || '';
      const saved = JSON.parse(localStorage.getItem('cozy.look') || 'null');
      if (saved && typeof saved === 'object') {
        for (const r of LOOK_ROWS) if (Number.isInteger(saved[r.key]) && saved[r.key] < r.values.length) this.look[r.key] = saved[r.key];
        this._refreshLooks();
      }
    } catch { /* localStorage может быть недоступен */ }
    const q = new URLSearchParams(location.search).get('room');
    if (q) this.room.value = q.slice(0, 12);
  }

  _buildLooks() {
    const box = $('looks');
    for (const r of LOOK_ROWS) {
      const row = document.createElement('div');
      row.className = 'row';
      row.innerHTML = `<span class="lbl">${r.label}</span><button class="arrow" type="button" aria-label="назад">‹</button><span class="val"></span><button class="arrow" type="button" aria-label="вперёд">›</button>`;
      const [prev, next] = row.querySelectorAll('button');
      const step = (d) => {
        const n = r.values.length;
        this.look[r.key] = (this.look[r.key] + d + n) % n;
        this._refreshLooks();
        this.onLookChange?.(this.look);
      };
      prev.addEventListener('click', () => step(-1));
      next.addEventListener('click', () => step(1));
      box.appendChild(row);
      this.rows.push({ r, val: row.querySelector('.val'), lbl: row.querySelector('.lbl') });
    }
    // WS1: строк внешности стало 7 — на невысоких экранах раскладываем их в две колонки без подписей
    const compact = () => {
      const on = window.innerHeight < 860 && window.innerWidth > 560;
      if (on === this._compact) return;
      this._compact = on;
      box.style.gridTemplateColumns = on ? 'minmax(0, 1fr) minmax(0, 1fr)' : '';
      box.style.gap = on ? '5px 10px' : '';
      for (const { lbl, val } of this.rows) {
        lbl.style.display = on ? 'none' : '';
        val.style.fontSize = on ? '15px' : '';
        val.style.minWidth = on ? '0' : '';
        for (const b of val.parentNode.querySelectorAll('.arrow')) b.style.width = on ? '32px' : '';
      }
      this._refreshLooks();
    };
    compact();
    window.addEventListener('resize', compact);
    this._refreshLooks();
  }

  _refreshLooks() {
    for (const { r, val } of this.rows) {
      const i = this.look[r.key];
      if (!r.colors) { val.textContent = r.values[i]; continue; }
      // тон кожи и цвет волос — с образцом цвета; в компактном режиме подпись строки вместо «Тон N»
      const sw = document.createElement('i');
      sw.style.cssText = `display:inline-block;width:14px;height:14px;border-radius:50%;margin-right:6px;vertical-align:-1px;border:2px solid #fff;box-shadow:0 0 0 1px rgba(60,40,20,.25);background:${r.colors[i]}`;
      val.textContent = '';
      val.append(sw, document.createTextNode(this._compact ? `${r.label} ${i + 1}` : r.values[i]));
    }
  }

  _play() {
    if (this.play.disabled) return;
    const nick = this.nick.value.trim().slice(0, 16) || `Гость${Math.floor(100 + Math.random() * 900)}`;
    const room = this.room.value.trim().toLowerCase().replace(/[^a-zа-я0-9_-]/g, '').slice(0, 12);
    try {
      localStorage.setItem('cozy.nick', nick);
      localStorage.setItem('cozy.look', JSON.stringify(this.look));
    } catch { /* ignore */ }
    this.onPlay?.({ nick, room, look: { ...this.look } });
  }

  setStatus(t) { this.status.textContent = t || ''; }
  setPlayEnabled(v) { this.play.disabled = !v; }
  showMenu(v) { this.menu.hidden = !v; }
  showHud(v) { this.hud.hidden = !v; }

  toast(text, ms = 2200) {
    this.toastEl.textContent = text;
    this.toastEl.classList.add('show');
    clearTimeout(this._toastT);
    this._toastT = setTimeout(() => this.toastEl.classList.remove('show'), ms);
  }

  chat(name, text, sys = false) {
    const el = document.createElement('div');
    el.className = sys ? 'msg sys' : 'msg';
    if (sys) el.textContent = text;
    else {
      const b = document.createElement('b');
      b.textContent = `${name}: `;
      el.append(b, document.createTextNode(text)); // только textContent: чат не может внедрить HTML
    }
    this.chatlog.appendChild(el);
    while (this.chatlog.childElementCount > 8) this.chatlog.firstChild.remove();
    setTimeout(() => { el.style.opacity = '0'; }, 14000);
    setTimeout(() => el.remove(), 15500);
  }

  openChat() {
    this.chatform.classList.add('open');
    this.chatinput.focus();
  }

  closeChat() {
    this.chatform.classList.remove('open');
    this.chatinput.blur();
  }

  get chatOpen() { return this.chatform.classList.contains('open'); }

  setHints(list) {
    const key = list.map((h) => `${h.key}|${h.text}`).join(';');
    if (key === this._hintKey) return;
    this._hintKey = key;
    this.hints.innerHTML = '';
    for (const h of list) {
      const d = document.createElement('div');
      const k = document.createElement('kbd');
      k.textContent = h.key;
      d.append(document.createTextNode(h.text), k);
      this.hints.appendChild(d);
    }
  }

  setCrosshairHot(v) { this.crosshair.classList.toggle('hot', v); }

  // WS6: подпись над целью в виде сверху (x, y — точка над целью в CSS-пикселях; below — под целью,
  // хвостик вверх); text = null — скрыть.
  setTargetLabel(text, key, x, y, below = false) {
    const el = this.tlabel;
    if (!el) return;
    if (!text) { if (!el.hidden) el.hidden = true; return; }
    this._tlText(text, key);
    if (below !== this._tlBelow) { this._tlBelow = below; el.classList.toggle('below', below); }
    el.style.transform = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px) translate(-50%, ${below ? '0' : '-100%'})`;
    if (el.hidden) el.hidden = false;
  }

  _tlText(text, key) {
    const k = `${key}|${text}`;
    if (k === this._tlKey) return;
    this._tlKey = k;
    this._tlSize = null;
    const kb = document.createElement('kbd');
    kb.textContent = key;
    this.tlabel.replaceChildren(kb, document.createTextNode(text));
  }

  // Размер подписи цели (для раскладки над/под целью); меряем один раз на текст.
  targetLabelSize(text) {
    const el = this.tlabel;
    if (!el || el.hidden || this._tlKey.slice(this._tlKey.indexOf('|') + 1) !== text) return null;
    if (!this._tlSize) this._tlSize = { w: el.offsetWidth || 160, h: el.offsetHeight || 34 };
    return this._tlSize;
  }

  // WS6: ники других игроков в виде сверху — HTML-подписи над головами (list[i]: { name, speaking, x, y, a }).
  setNameTags(list, n) {
    const pool = this._ntags || (this._ntags = []);
    for (let i = 0; i < n; i++) {
      const it = list[i];
      let e = pool[i];
      if (!e) {
        const el = document.createElement('div');
        el.className = 'ntag';
        $('wlabels').appendChild(el);
        e = pool[i] = { el, name: '', speaking: null, a: -1, shown: true, w: 0 };
      }
      if (e.name !== it.name || e.speaking !== it.speaking) {
        e.name = it.name; e.speaking = it.speaking;
        e.el.textContent = it.name;
        e.el.classList.toggle('speaking', it.speaking);
        e.w = 0;
      }
      if (!e.shown) { e.shown = true; e.el.hidden = false; }
      if (!e.w) e.w = e.el.offsetWidth; // ширину меряем один раз на текст: ник у края экрана не обрезается
      const x = Math.min(Math.max(it.x, e.w / 2 + 4), window.innerWidth - e.w / 2 - 4);
      e.el.style.transform = `translate(${x.toFixed(1)}px, ${it.y.toFixed(1)}px) translate(-50%, -100%)`;
      const a = Math.round(it.a * 20) / 20;
      if (a !== e.a) { e.a = a; e.el.style.opacity = String(a); }
    }
    for (let i = n; i < pool.length; i++) if (pool[i].shown) { pool[i].shown = false; pool[i].el.hidden = true; }
  }

  // WS6: режим камеры (C1) — кнопка 📷 подсвечена в виде сверху.
  setCameraMode(mode) {
    for (const b of document.querySelectorAll('.tmini [data-act="view"]')) {
      b.classList.toggle('on', mode === 'top');
      b.setAttribute('aria-label', mode === 'top' ? 'Камера: от первого лица' : 'Камера: вид сверху');
    }
    if (mode !== 'top') { this.setTargetLabel(null); this.setNameTags(null, 0); }
  }
  setRoom(code) { $('roomcode').textContent = code; }
  setCount(n) { $('count').textContent = `${n} в сети`; }

  setMic(state) { // 'off' | 'on' | 'denied' | 'none'
    this.micEl.classList.toggle('on', state === 'on');
    const t = { off: 'микрофон выкл', on: 'микрофон вкл', denied: 'нет доступа к микрофону', none: 'нет микрофона' }[state];
    this.micEl.innerHTML = `🎤 ${t} <kbd>V</kbd>`;
    for (const b of document.querySelectorAll('.tmini [data-act="mic"]')) b.classList.toggle('on', state === 'on');
  }

  showHelp(isTouch) {
    if (isTouch) { this.help.hidden = true; return; }
    const rows = [['WASD', 'ходить'], ['Shift', 'бежать'], ['Пробел', 'прыжок'], ['E', 'взять / использовать'], ['Q', 'бросить (держи — сильнее)'], ['X', 'помахать'], ['V', 'микрофон'], ['T', 'чат'], ['C', 'камера: 1-е лицо → 3-е → сверху'], ['M', 'звук вкл/выкл'], ['Tab', 'кто в комнате']];
    this.help.hidden = false;
    this.help.style.opacity = '1';
    this.help.innerHTML = rows.map(([k, t]) => `<div><kbd>${k}</kbd>${t}</div>`).join('');
    setTimeout(() => { this.help.style.opacity = '0'; }, 22000);
  }

  setPlayers(list, show) {
    this.playersEl.hidden = !show;
    if (!show) return;
    this.playersEl.innerHTML = '';
    for (const p of list) {
      const d = document.createElement('div');
      const a = document.createElement('span'), b = document.createElement('span');
      a.textContent = p.name + (p.me ? ' (ты)' : '');
      b.textContent = p.speaking ? '🔊' : '';
      d.append(a, b);
      this.playersEl.appendChild(d);
    }
  }

  setPause(v) { this.pauseEl.hidden = !v; }
  fade(on) { this.fadeEl.classList.toggle('on', on); }
}
