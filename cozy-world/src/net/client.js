// Клиент игрового WebSocket: вход в комнату, отправка состояния, разбор сообщений сервера.
// События назначаются через net.on('имя', fn).

export class Net {
  constructor() {
    this.ws = null;
    this.id = null;
    this.room = null;
    this.connected = false;
    this.handlers = {};
    this.ice = [{ urls: 'stun:stun.l.google.com:19302' }];
    this.rtt = 0;
    this.closedByUs = false;
  }

  on(name, fn) {
    this.handlers[name] = fn;
    return this;
  }

  static serverUrl() {
    const p = new URLSearchParams(location.search);
    if (p.get('server')) return p.get('server');
    if (window.COZY_SERVER) return window.COZY_SERVER;
    return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
  }

  // Разрешается welcome-сообщением; отклоняется строкой-причиной ('timeout', 'network', 'full', 'bad-room').
  connect({ room, name, look }) {
    return new Promise((resolve, reject) => {
      let settled = false;
      let ws;
      const fail = (why) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        try { ws?.close(); } catch { /* ignore */ }
        reject(new Error(why));
      };
      const timer = setTimeout(() => fail('timeout'), 7000);
      try { ws = new WebSocket(Net.serverUrl()); } catch { fail('network'); return; }
      this.ws = ws;
      ws.onopen = () => ws.send(JSON.stringify({ t: 'join', room, name, look }));
      ws.onmessage = (ev) => {
        let m;
        try { m = JSON.parse(ev.data); } catch { return; }
        if (m.t === 'welcome') {
          settled = true;
          clearTimeout(timer);
          this.id = m.id;
          this.room = m.room;
          this.connected = true;
          if (Array.isArray(m.ice) && m.ice.length) this.ice = m.ice;
          this._startPing();
          resolve(m);
          return;
        }
        if (m.t === 'error') { fail(m.e); return; }
        this._handle(m);
      };
      ws.onerror = () => fail('network');
      ws.onclose = () => {
        const was = this.connected;
        this.connected = false;
        clearInterval(this._ping);
        if (!settled) fail('closed');
        else if (was && !this.closedByUs) this.handlers.close?.();
      };
    });
  }

  _startPing() {
    clearInterval(this._ping);
    this._ping = setInterval(() => this.send({ t: 'ping', ts: performance.now() }), 5000);
  }

  _handle(m) {
    switch (m.t) {
      case 'pong': this.rtt = performance.now() - m.ts; break;
      case 'join': this.handlers.join?.(m); break;
      case 'leave': this.handlers.leave?.(m); break;
      case 'snap': this.handlers.snap?.(m); break;
      case 'chat': this.handlers.chat?.(m); break;
      case 'emote': this.handlers.emote?.(m); break;
      case 'look': this.handlers.look?.(m); break;
      case 'owner': this.handlers.owner?.(m); break;
      case 'deny': this.handlers.deny?.(m); break;
      case 'rtc': this.handlers.rtc?.(m); break;
      default:
    }
  }

  send(obj) {
    if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(obj));
  }

  close() {
    this.closedByUs = true;
    clearInterval(this._ping);
    try { this.ws?.close(); } catch { /* ignore */ }
    this.connected = false;
  }
}
