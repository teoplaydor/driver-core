// Игровой WebSocket-сервер: комнаты по коду, снапшоты позиций, чат, владение предметами и
// пересылка WebRTC-сигналов (голос идёт напрямую между игроками, сервер только знакомит их).
//
// Модель доверия: клиент сам считает свою позицию (игра кооперативная), поэтому сервер проверяет
// только формат, диапазоны, частоту сообщений и владение предметами.

import { WebSocketServer } from 'ws';
import { PROP_IDS, STATE_IDS, USE_ACTIONS } from '../shared/props.js';

const MAX_PLAYERS = 16;
const TICK_MS = 66; // ~15 снапшотов в секунду
const ROOM_TTL_MS = 5 * 60 * 1000; // пустая комната живёт 5 минут, чтобы не терять переставленные предметы
const ROOM_RE = /^[a-zа-я0-9_-]{1,12}$/;
const HIT_MS = 400; // удар битой по игроку — не чаще раза в 0.4 с от одного игрока по одному и тому же

const num = (v, min, max) => (typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : null);
const round = (v, k = 100) => Math.round(v * k) / k;
const cleanText = (s, max) => String(s).replace(/[\u0000-\u001f\u007f<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, max);

function parseVec(a, n, lim) {
  if (!Array.isArray(a) || a.length !== n) return null;
  const out = [];
  for (const v of a) {
    const x = num(v, -lim, lim);
    if (x === null) return null;
    out.push(x);
  }
  return out;
}

// Внешность: [кожа, наряд, шляпа, очки, цвет волос, причёска, борода]. Старые клиенты шлют 5 чисел.
function parseLook(a) {
  const lim = [6, 8, 6, 4, 6, 4, 3];
  if (!Array.isArray(a) || a.length < 5 || a.length > lim.length) return lim.map(() => 0);
  return lim.map((n, i) => (Number.isInteger(a[i]) && a[i] >= 0 && a[i] < n ? a[i] : 0));
}

class TokenBucket {
  constructor(rate, burst) { this.rate = rate; this.burst = burst; this.tokens = burst; this.t = Date.now(); }
  take(n = 1) {
    const now = Date.now();
    this.tokens = Math.min(this.burst, this.tokens + ((now - this.t) / 1000) * this.rate);
    this.t = now;
    if (this.tokens < n) return false;
    this.tokens -= n;
    return true;
  }
}

class Room {
  constructor(code) {
    this.code = code;
    this.clients = new Map();
    this.props = new Map(); // id -> { p, q, owner, held, dirty, s }
    this.emptyAt = 0;
  }

  broadcast(msg, except) {
    const data = JSON.stringify(msg);
    for (const c of this.clients.values()) if (c !== except && c.ws.readyState === 1) c.ws.send(data);
  }
}

export function attachGameServer(httpServer, { path = '/ws', iceServers } = {}) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 24 * 1024, perMessageDeflate: false });
  const rooms = new Map();
  let nextId = 1;

  let ice = iceServers || [{ urls: 'stun:stun.l.google.com:19302' }];
  if (!iceServers && process.env.ICE_SERVERS) {
    try {
      const parsed = JSON.parse(process.env.ICE_SERVERS);
      if (Array.isArray(parsed) && parsed.length) ice = parsed;
      else console.error('ICE_SERVERS должен быть непустым JSON-массивом, использую STUN по умолчанию');
    } catch (e) {
      console.error('ICE_SERVERS: невалидный JSON, использую STUN по умолчанию:', e.message);
    }
  }

  httpServer.on('upgrade', (req, socket, head) => {
    let pathname = '';
    try { pathname = new URL(req.url, 'http://x').pathname; } catch { return; }
    if (pathname !== path) return; // остальные upgrade (например, HMR у Vite) не трогаем
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });

  function getRoom(code) {
    let r = rooms.get(code);
    if (!r) { r = new Room(code); rooms.set(code, r); }
    r.emptyAt = 0;
    return r;
  }

  function releaseProps(room, client) {
    const rest = [...room.clients.values()].filter((c) => c !== client);
    for (const [id, pr] of room.props) {
      if (pr.owner !== client.id) continue;
      pr.held = false;
      pr.owner = rest.length ? rest[0].id : null; // новый хозяин доводит предмет до покоя
      room.broadcast({ t: 'owner', id, o: pr.owner, h: 0, p: pr.p, q: pr.q });
    }
  }

  wss.on('connection', (ws) => {
    const client = {
      id: (nextId++).toString(36), ws, room: null, name: '', look: [0, 0, 0, 0, 0, 0, 0],
      st: { p: [0, 0, 0], r: 0, q: 0, sp: 0, a: 0, hk: 0 }, dirty: false, alive: true,
      msgRate: new TokenBucket(80, 120), chatRate: new TokenBucket(0.8, 4),
    };
    ws.on('pong', () => { client.alive = true; });

    const send = (msg) => { if (ws.readyState === 1) ws.send(JSON.stringify(msg)); };

    ws.on('message', (raw, isBinary) => {
      if (isBinary || !client.msgRate.take()) return;
      let m;
      try { m = JSON.parse(raw.toString()); } catch { return; }
      if (!m || typeof m !== 'object' || typeof m.t !== 'string') return;

      if (m.t === 'join') {
        if (client.room) return;
        const code = typeof m.room === 'string' ? m.room.toLowerCase() : '';
        if (!ROOM_RE.test(code)) { send({ t: 'error', e: 'bad-room' }); return; }
        const room = getRoom(code);
        if (room.clients.size >= MAX_PLAYERS) { send({ t: 'error', e: 'full' }); return; }
        client.name = cleanText(m.name ?? '', 16) || `Гость${client.id}`;
        client.look = parseLook(m.look);
        client.room = room;
        room.clients.set(client.id, client);
        const players = [...room.clients.values()].filter((c) => c !== client).map((c) => ({
          id: c.id, name: c.name, look: c.look, p: c.st.p, r: c.st.r, q: c.st.q, a: c.st.a, hk: c.st.hk,
        }));
        const props = [...room.props].map(([id, pr]) => ({ id, p: pr.p, q: pr.q, o: pr.owner, h: pr.held ? 1 : 0, s: pr.s }));
        send({ t: 'welcome', id: client.id, room: code, players, props, ice });
        room.broadcast({ t: 'join', id: client.id, name: client.name, look: client.look }, client);
        return;
      }

      const room = client.room;
      if (!room) return;

      switch (m.t) {
        case 's': { // состояние игрока
          const p = parseVec(m.p, 3, 1500);
          const r = num(m.r, -1000, 1000), q = num(m.q, -2, 2), sp = num(m.sp, 0, 40);
          if (!p || r === null || q === null || sp === null) return;
          // hk — вид удержания предмета: 0 нет, 1 two, 2 one, 3 bat, 4 umbrella
          const hk = Number.isInteger(m.hk) && m.hk >= 0 && m.hk <= 4 ? m.hk : 0;
          client.st = { p: [round(p[0]), round(p[1]), round(p[2])], r: round(r, 1000), q: round(q, 1000), sp: round(sp, 10), a: (m.a | 0) & 15, hk };
          client.dirty = true;
          break;
        }
        case 'chat': {
          if (!client.chatRate.take()) return;
          const text = cleanText(m.m ?? '', 160);
          if (text) room.broadcast({ t: 'chat', id: client.id, m: text });
          break;
        }
        case 'emote':
          if (m.e === 'wave') room.broadcast({ t: 'emote', id: client.id, e: 'wave' }, client);
          break;
        case 'look':
          client.look = parseLook(m.look);
          room.broadcast({ t: 'look', id: client.id, look: client.look }, client);
          break;
        case 'grab': {
          if (typeof m.id !== 'string' || !PROP_IDS.has(m.id)) return;
          let pr = room.props.get(m.id);
          if (!pr) { pr = { p: null, q: null, owner: null, held: false, dirty: false }; room.props.set(m.id, pr); }
          const ownerAlive = pr.owner && room.clients.has(pr.owner);
          if (ownerAlive && pr.owner !== client.id) { send({ t: 'deny', id: m.id }); return; }
          pr.owner = client.id;
          pr.held = true;
          room.broadcast({ t: 'owner', id: m.id, o: client.id, h: 1 });
          break;
        }
        case 'pp': { // кадры состояния предметов от владельца: [[id, x,y,z, qx,qy,qz,qw], ...]
          if (!Array.isArray(m.l) || m.l.length > 40) return;
          for (const it of m.l) {
            if (!Array.isArray(it) || it.length !== 8 || typeof it[0] !== 'string') continue;
            const pr = room.props.get(it[0]);
            if (!pr || pr.owner !== client.id) continue;
            const v = parseVec(it.slice(1), 7, 1500);
            if (!v) continue;
            pr.p = [round(v[0]), round(v[1]), round(v[2])];
            pr.q = [round(v[3], 1000), round(v[4], 1000), round(v[5], 1000), round(v[6], 1000)];
            pr.dirty = true;
          }
          break;
        }
        case 'rel': {
          const pr = room.props.get(m.id);
          if (!pr || pr.owner !== client.id) return;
          pr.held = false;
          room.broadcast({ t: 'owner', id: m.id, o: client.id, h: 0 });
          break;
        }
        case 'rest': {
          const pr = room.props.get(m.id);
          if (!pr || pr.owner !== client.id) return;
          const p = parseVec(m.p, 3, 1500), q = parseVec(m.q, 4, 2);
          if (p) pr.p = p.map((v) => round(v));
          if (q) pr.q = q.map((v) => round(v, 1000));
          pr.owner = null;
          pr.held = false;
          pr.dirty = false;
          room.broadcast({ t: 'owner', id: m.id, o: null, h: 0, p: pr.p, q: pr.q });
          break;
        }
        case 'kick': { // удар битой по предмету: доигрывает тот, кто ударил, если предмет не в чужих руках
          if (typeof m.id !== 'string' || !PROP_IDS.has(m.id)) return;
          let pr = room.props.get(m.id);
          if (!pr) { pr = { p: null, q: null, owner: null, held: false, dirty: false }; room.props.set(m.id, pr); }
          const ownerAlive = pr.owner && room.clients.has(pr.owner);
          if (ownerAlive && pr.owner !== client.id) { send({ t: 'deny', id: m.id }); return; }
          pr.owner = client.id;
          pr.held = false;
          room.broadcast({ t: 'owner', id: m.id, o: client.id, h: 0 });
          break;
        }
        case 'use': { // действие предметом в руках — остальным для анимации и звука
          const pr = room.props.get(m.id);
          if (!pr || pr.owner !== client.id || !USE_ACTIONS.includes(m.a)) return;
          const out = { t: 'use', id: m.id, a: m.a, from: client.id };
          if (Number.isInteger(m.c) && m.c >= 0 && m.c < 16) out.c = m.c;
          room.broadcast(out, client);
          break;
        }
        case 'pst': { // состояние предмета (зонт раскрыт, фонарь, музыка): хранится и отдаётся в welcome
          const pr = room.props.get(m.id);
          if (!pr || pr.owner !== client.id || !STATE_IDS.has(m.id) || (m.s !== 0 && m.s !== 1)) return;
          pr.s = m.s;
          room.broadcast({ t: 'pst', id: m.id, s: m.s }, client);
          break;
        }
        case 'hit': { // удар битой по игроку: отдачу считает сам получатель
          const target = typeof m.to === 'string' ? room.clients.get(m.to) : null;
          const v = parseVec(m.v, 3, 12);
          if (!target || target === client || !v) return;
          // лимит на пару «кто — кого»: один замах может задеть двоих, но очередью бить одного нельзя
          const now = Date.now(), hits = client.hitAt || (client.hitAt = new Map());
          if (now - (hits.get(target.id) || 0) < HIT_MS) return;
          hits.set(target.id, now);
          if (target.ws.readyState === 1) target.ws.send(JSON.stringify({ t: 'hit', from: client.id, v: v.map((x) => round(x)) }));
          break;
        }
        case 'rtc': { // сигнальные сообщения WebRTC: просто пересылаем адресату
          if (typeof m.to !== 'string' || typeof m.d !== 'object' || !m.d) return;
          const target = room.clients.get(m.to);
          if (target && target !== client) target.ws.send(JSON.stringify({ t: 'rtc', from: client.id, d: m.d }));
          break;
        }
        case 'ping':
          send({ t: 'pong', ts: m.ts });
          break;
        default:
      }
    });

    const leave = () => {
      const room = client.room;
      if (!room) return;
      room.clients.delete(client.id);
      releaseProps(room, client);
      room.broadcast({ t: 'leave', id: client.id });
      if (!room.clients.size) room.emptyAt = Date.now();
      client.room = null;
    };
    ws.on('close', leave);
    ws.on('error', leave);
  });

  // Снапшоты: каждому игроку шлём чужие обновившиеся состояния и предметы одним сообщением.
  const tick = setInterval(() => {
    for (const room of rooms.values()) {
      if (!room.clients.size) continue;
      const changed = [];
      for (const c of room.clients.values()) {
        if (c.dirty) { changed.push([c.id, ...c.st.p, c.st.r, c.st.q, c.st.sp, c.st.a, c.st.hk]); c.dirty = false; }
      }
      const props = [];
      for (const [id, pr] of room.props) {
        if (pr.dirty && pr.p && pr.q) { props.push([id, ...pr.p, ...pr.q]); pr.dirty = false; }
      }
      if (!changed.length && !props.length) continue;
      for (const c of room.clients.values()) {
        if (c.ws.readyState !== 1) continue;
        const own = changed.filter((e) => e[0] !== c.id);
        const ownProps = props.filter((e) => room.props.get(e[0]).owner !== c.id);
        if (!own.length && !ownProps.length) continue;
        c.ws.send(JSON.stringify({ t: 'snap', pl: own, pr: ownProps }));
      }
    }
    const now = Date.now();
    for (const [code, room] of rooms) if (!room.clients.size && room.emptyAt && now - room.emptyAt > ROOM_TTL_MS) rooms.delete(code);
  }, TICK_MS);

  // Раз в 20 секунд убиваем зависшие соединения.
  const hb = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.__dead) { ws.terminate(); continue; }
      ws.__dead = true;
      ws.once('pong', () => { ws.__dead = false; });
      try { ws.ping(); } catch { /* закрыт */ }
    }
  }, 20000);

  httpServer.on('close', () => { clearInterval(tick); clearInterval(hb); });

  return {
    stats() {
      let players = 0;
      for (const r of rooms.values()) players += r.clients.size;
      return { rooms: rooms.size, players };
    },
    close() { clearInterval(tick); clearInterval(hb); wss.close(); },
  };
}
