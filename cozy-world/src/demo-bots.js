// Демо-боты для одиночного превью: ходят вокруг костра и беседки, машут рукой и пишут в чат,
// чтобы без сервера было видно, как выглядят другие игроки. В обычной игре не используются.

import { heightAt } from './world/terrain-data.js';

const BOTS = [
  { id: 'bot-fox', name: 'Лиса', look: [1, 3, 2, 1, 0, 0, 1], cx: 0, cz: 0, radius: 8.5, speed: 1.6, dir: 1, phase: 0.5,
    lines: ['Привет! Это демо без сервера', 'Подходи к костру, тут уютно', 'Бросай мяч — Q, держи дольше — бросок сильнее'] },
  { id: 'bot-bear', name: 'Мишка', look: [0, 0, 1, 2, 0, 0, 0], cx: 27, cz: -19, radius: 7.5, speed: 1.2, dir: -1, phase: 2.2,
    lines: ['Сыграем в шахматы в беседке?', 'Фигуры сами встают в клетку'] },
  { id: 'bot-owl', name: 'Сова', look: [2, 1, 3, 1, 2, 2, 2], cx: -17, cz: -21, radius: 6.5, speed: 1.4, dir: 1, phase: 4,
    lines: ['Попробуй V — микрофон, на сервере слышно по расстоянию', 'За указателем дорога в зиму и каньон'] },
];

export class DemoBots {
  constructor(game) {
    this.game = game;
    this.t = 0;
    this.state = BOTS.map((b, i) => ({ ...b, say: 6 + i * 4.5, wave: 9 + i * 7, line: 0, talk: 0 }));
  }

  start() {
    for (const b of this.state) {
      const p = this._pos(b, 0);
      this.game.remotes.add({ id: b.id, name: b.name, look: b.look, p: [p.x, p.y, p.z], r: p.yaw });
    }
    return this;
  }

  _pos(b, t) {
    const a = b.phase + b.dir * (t * b.speed) / b.radius;
    const x = b.cx + Math.cos(a) * b.radius, z = b.cz + Math.sin(a) * b.radius;
    // касательная к окружности - направление, куда идёт бот
    const vx = -Math.sin(a) * b.dir, vz = Math.cos(a) * b.dir;
    return { x, y: heightAt(x, z), z, yaw: Math.atan2(-vx, -vz) };
  }

  update(dt) {
    this.t += dt;
    const g = this.game;
    for (const b of this.state) {
      const p = this._pos(b, this.t);
      // пока бот «говорит», ставим бит речи — у аватара двигается рот (WS1)
      b.talk = Math.max(0, b.talk - dt);
      g.remotes.push([b.id, p.x, p.y, p.z, p.yaw, 0, b.speed, 1 | (b.talk > 0 ? 2 : 0), 0]);
      const r = g.remotes.get(b.id);
      if (!r) continue;
      b.say -= dt;
      b.wave -= dt;
      if (b.wave < 0) { b.wave = 22 + Math.random() * 10; r.avatar.wave(); }
      if (b.say < 0) {
        b.say = 20 + Math.random() * 12;
        const text = b.lines[b.line++ % b.lines.length];
        r.avatar.say(text);
        b.talk = 1.2 + text.length * 0.05;
        g.ui.chat(b.name, text);
      }
    }
  }
}
