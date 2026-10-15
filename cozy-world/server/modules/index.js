// Серверные модули активностей (Codenames, Уно, кино, переноска игроков, арена).
// Каждый модуль — объект (все поля, кроме name и prefix, необязательны):
//   name                    — ключ: состояние комнаты лежит в ctx.state, данные для новичка уходят в welcome.mods[name]
//   prefix                  — префикс типов сообщений модуля ('uno_', 'cn_'...): такие сообщения получает только он
//   welcome(ctx, client)    → данные для нового игрока (JSON) или undefined
//   message(ctx, client, m) — сообщение от игрока с типом на prefix (формат проверяет сам модуль!)
//   leave(ctx, client)      — игрок вышел (вызывается до рассылки 'leave')
//   tick(ctx, now)          — каждые ~66 мс, пока в комнате есть игроки
// ctx: { room, state, send(clientOrId, msg), broadcast(msg, except), clients(), util }
//   state — объект модуля в этой комнате (пустой при первом обращении, живёт, пока жива комната);
//   clients() — массив { id, name, st: {p, r, ...} } игроков комнаты; util — { num, round, cleanText, parseVec }.
// Модули не импортируют ничего серверного (ws, fs...): тот же код крутится в браузере в одиночном режиме
// (src/net/local.js), поэтому общая логика — в shared/, а здесь только состояние и рассылка.
// Каждый модуль живёт в своём файле; новый модуль — новая строка ниже.

import codenames from './codenames.js';
import uno from './uno.js';
import cinema from './cinema.js';
import carry from './carry.js';
import arena from './arena.js';

export const MODULES = [codenames, uno, cinema, carry, arena];

// Общие проверки для модулей (те же, что у ядра сервера).
export const num = (v, min, max) => (typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : null);
export const round = (v, k = 100) => Math.round(v * k) / k;
export const cleanText = (s, max) => String(s).replace(/[\u0000-\u001f\u007f<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, max);
export function parseVec(a, n, lim) {
  if (!Array.isArray(a) || a.length !== n) return null;
  const out = [];
  for (const v of a) {
    const x = num(v, -lim, lim);
    if (x === null) return null;
    out.push(x);
  }
  return out;
}
export const util = { num, round, cleanText, parseVec };

export const modByPrefix = (t) => MODULES.find((md) => md.prefix && t.startsWith(md.prefix)) || null;

// Вызов обработчика модуля без риска уронить комнату: ошибка одного модуля только пишется в лог.
export function safeCall(mod, fn, ...args) {
  if (typeof mod[fn] !== 'function') return undefined;
  try {
    return mod[fn](...args);
  } catch (e) {
    console.error(`[module ${mod.name}] ${fn}:`, e);
    return undefined;
  }
}
