// Клиентские модули активностей. Каждый — объект { name, prefix?, create(game) → экземпляр или null }.
// name совпадает с серверным модулем (server/modules), если он есть: welcome.mods[name] уходит в его welcome().
// Экземпляр (все методы необязательны):
//   welcome(data)        — состояние комнаты для модуля (и после переподключения, и в одиночном режиме)
//   message(m)           — сообщение сервера с типом на prefix
//   update(dt, time)     — каждый кадр в режиме игры
//   leave(id)            — другой игрок вышел
//   dispose()
// Отправка серверу — game.sendMod(msg): по сети, а в одиночном режиме — в LocalRoom (src/net/local.js).
// Интерфейс (DOM, стили) модуль создаёт сам; взаимодействие с миром — через world.interactables (C7).
// Каждый модуль живёт в своём файле; новый модуль — новая строка ниже.

import chess from './chess.js';
import codenames from './codenames.js';
import markers from './markers.js';
import uno from './uno.js';
import cinema from './cinema.js';
import carry from './carry.js';
import arena from './arena.js';

export const ACTIVITIES = [chess, codenames, markers, uno, cinema, carry, arena];
