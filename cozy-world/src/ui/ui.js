// HTML-интерфейс: вход, HUD, чат, подсказки. Игровая логика сюда не пишется:
// UI только показывает состояние и вызывает колбэки.

import { SKINS, OUTFITS, HATS, GLASSES, HAIRS, randomLook } from '../player/avatar.js';

const LOOK_ROWS = [
  { key: 'skin', label: 'Кожа', values: SKINS.map((_, i) => `Тон ${i + 1}`) },
  { key: 'outfit', label: 'Одежда', values: OUTFITS.map((o) => o.name) },
  { key: 'hat', label: 'Шляпа', values: HATS },
  { key: 'glasses', label: 'Очки', values: GLASSES },
  { key: 'hair', label: 'Волосы', values: HAIRS.map((_, i) => `Цвет ${i + 1}`) },
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
      this.rows.push({ r, val: row.querySelector('.val') });
    }
    this._refreshLooks();
  }

  _refreshLooks() {
    for (const { r, val } of this.rows) val.textContent = r.values[this.look[r.key]];
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
    const rows = [['WASD', 'ходить'], ['Shift', 'бежать'], ['Пробел', 'прыжок'], ['E', 'взять / использовать'], ['Q', 'бросить (держи — сильнее)'], ['X', 'помахать'], ['V', 'микрофон'], ['T', 'чат'], ['C', 'вид от 3-го лица'], ['M', 'звук вкл/выкл'], ['Tab', 'кто в комнате']];
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
