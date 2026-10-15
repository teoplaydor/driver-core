// Одиночный режим без сервера (превью, нет связи): серверные модули активностей (server/modules) крутятся
// прямо в браузере. Снаружи это выглядит как сервер: send(m) — сообщение «серверу», ответы приходят в deliver(m)
// асинхронно, как по сети. В комнате один живой игрок (self); ботов модули заводят сами.

import { MODULES, modByPrefix, safeCall, util } from '../../server/modules/index.js';

export class LocalRoom {
  // self: { id, name, st: { p: [x, y, z], r, ... } } — игра обновляет self.st сама
  constructor(self, deliver) {
    this.self = self;
    this.deliver = deliver;
    this.clients = new Map([[self.id, self]]);
    this.mods = {};
    this._timer = setInterval(() => this.tick(), 66);
  }

  _out(msg) {
    const copy = JSON.parse(JSON.stringify(msg)); // как по сети: без общих ссылок на состояние модуля
    queueMicrotask(() => this.deliver(copy));
  }

  mod(md) {
    let ctx = this.mods[md.name];
    if (!ctx) {
      const room = this;
      ctx = this.mods[md.name] = {
        room,
        state: {},
        util,
        send(to, msg) {
          const id = typeof to === 'string' ? to : to?.id;
          if (id === room.self.id) room._out(msg);
        },
        broadcast(msg, except) {
          const id = typeof except === 'string' ? except : except?.id;
          if (id !== room.self.id) room._out(msg);
        },
        clients: () => [...room.clients.values()],
      };
    }
    return ctx;
  }

  // Данные модулей для «welcome» (как у сервера).
  welcome() {
    const mods = {};
    for (const md of MODULES) {
      const data = safeCall(md, 'welcome', this.mod(md), this.self);
      if (data !== undefined) mods[md.name] = data;
    }
    return mods;
  }

  // Сообщение от игрока «серверу»: только модульные типы, остальное в одиночке не нужно.
  send(m) {
    if (!m || typeof m.t !== 'string') return;
    const md = modByPrefix(m.t);
    if (md) safeCall(md, 'message', this.mod(md), this.self, JSON.parse(JSON.stringify(m)));
  }

  tick() {
    const now = Date.now();
    for (const md of MODULES) if (md.tick) safeCall(md, 'tick', this.mod(md), now);
  }

  dispose() {
    clearInterval(this._timer);
    for (const md of MODULES) safeCall(md, 'leave', this.mod(md), this.self);
  }
}
