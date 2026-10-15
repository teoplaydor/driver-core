// Список предметов, которые лежат в мире. Раскладка детерминированная: клиенты создают предметы сами,
// а сервер хранит только отличия (кто что передвинул, кто открыл зонт). id стабильны между клиентами и сервером.

import { BOARD, GAZEBO, CAMPFIRE } from './layout.js';

// kind -> физика и поведение в руках:
//   r — радиус для подбора и столкновений со стенами; rest — как предмет ложится: 'up' стоит ровно, 'side' лежит
//   на боку (бита, зонт), 'flat' / 'flatZ' — плашмя по оси Y / Z (книга, фрисби / гитара), 'free' — катится (мяч);
//   hold — вид удержания (контракт C2); act — действие в руках (C2b); state — есть синхронизируемое состояние;
//   mass — для силы удара битой; drag — сопротивление воздуха; glide — планирует (фрисби).
export const KINDS = {
  ball: { r: 0.27, rest: 'free', hold: 'two', bounce: 0.72, drag: 0.35, mass: 0.5 },
  crate: { r: 0.34, rest: 'up', hold: 'two', bounce: 0.12, mass: 2.4 },
  mug: { r: 0.09, rest: 'up', hold: 'one', bounce: 0.15, mass: 0.4 },
  cup: { r: 0.08, rest: 'up', hold: 'one', bounce: 0.1, mass: 0.3 },
  teapot: { r: 0.14, rest: 'up', hold: 'one', bounce: 0.1, mass: 0.8 },
  bat: { r: 0.14, rest: 'side', hold: 'bat', act: 'swing', bounce: 0.3, mass: 0.9 },
  umbrella: { r: 0.16, rest: 'side', hold: 'umbrella', act: 'toggle', state: true, bounce: 0.2, mass: 0.8 },
  lantern: { r: 0.12, rest: 'up', hold: 'one', act: 'toggle', state: true, bounce: 0.1, mass: 0.9 },
  guitar: { r: 0.24, rest: 'flatZ', hold: 'two', act: 'strum', bounce: 0.2, mass: 1.3 },
  boombox: { r: 0.25, rest: 'up', hold: 'two', act: 'toggle', state: true, bounce: 0.12, mass: 1.8 },
  frisbee: { r: 0.14, rest: 'flat', hold: 'one', glide: true, bounce: 0.3, mass: 0.3 },
  book: { r: 0.12, rest: 'flat', hold: 'one', bounce: 0.08, mass: 0.6 },
  teddy: { r: 0.17, rest: 'up', hold: 'two', bounce: 0.3, mass: 0.6 },
  chess: { r: 0.075, rest: 'up', hold: 'one', bounce: 0.05, mass: 0.3 },
};

// Действия, которые можно переслать другим игрокам ({t:'use', id, a}).
export const USE_ACTIONS = ['swing', 'strum', 'toggle', 'throw', 'wave'];

// Подставки и стойки (статичный декор с коллизиями, строит клиент). ry — поворот вокруг Y.
export const STANDS = [
  { id: 'umbSpawn', type: 'umbrellaStand', x: -1.8, z: 6.9 },
  { id: 'batRack', type: 'batRack', x: 4.45, z: 4.45, ry: -0.72 },
  { id: 'boomCrate', type: 'crateStand', x: 3.3, z: 3.3, ry: -0.49 }, // в просвете между брёвнами у костра
  { id: 'stump', type: 'stump', x: 6.4, z: 5.6 },
  { id: 'table', type: 'campTable', x: -3.4, z: 6.4, ry: 0.5 },
  { id: 'umbRain', type: 'umbrellaStand', x: 2.2, z: 23.5 },
  { id: 'swing', type: 'swingSeat', x: -13.6, z: -20.5 }, // сиденье качелей у большого дерева (structures.js)
];
const stand = (id) => STANDS.find((s) => s.id === id);

// Точка на подставке: смещение (dx, dz) в системе подставки с учётом её поворота.
function on(id, dx, dz) {
  const s = stand(id), c = Math.cos(s.ry || 0), sn = Math.sin(s.ry || 0);
  return { x: s.x + dx * c + dz * sn, z: s.z - dx * sn + dz * c };
}

const CHESS_ORDER = ['R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R'];

function chessSet() {
  const out = [];
  const c = BOARD.cell;
  for (let file = 0; file < 8; file++) {
    const x = BOARD.x + (file - 3.5) * c;
    // белые: ряды 0-1 (ближе к -z), чёрные: 6-7
    out.push({ id: `cw${CHESS_ORDER[file]}${file}`, kind: 'chess', piece: CHESS_ORDER[file], side: 'w', x, z: BOARD.z + 3.5 * c, y: BOARD.y });
    out.push({ id: `cwP${file}`, kind: 'chess', piece: 'P', side: 'w', x, z: BOARD.z + 2.5 * c, y: BOARD.y });
    out.push({ id: `cbP${file}`, kind: 'chess', piece: 'P', side: 'b', x, z: BOARD.z - 2.5 * c, y: BOARD.y });
    out.push({ id: `cb${CHESS_ORDER[file]}${file}`, kind: 'chess', piece: CHESS_ORDER[file], side: 'b', x, z: BOARD.z - 3.5 * c, y: BOARD.y });
  }
  return out;
}

// Стоящие в подставке зонты и биты: rot — поворот (Euler XYZ), y не задан — предмет ставится на опору под ним.
// stand — предмет стоит в подставке (y считается от земли под подставкой: dy — высота нижней точки).
const umb = (id, v, st, dx, dz, tx, tz) => ({ id, kind: 'umbrella', v, ...on(st, dx, dz), rot: [Math.PI + tx, 0, tz], dy: 0.04, s: 0 });
const bat = (id, v, dx, tilt) => ({ id, kind: 'bat', v, ...on('batRack', dx, 0.02), rot: [Math.PI, -0.72, tilt], dy: 0.1 });

export const PROPS = [
  // пляжные мячи у костра и на поляне
  { id: 'ball1', kind: 'ball', v: 'a', x: 5.5, z: 7.5 },
  { id: 'ball2', kind: 'ball', v: 'b', x: -6.5, z: 4.5 },
  { id: 'ball3', kind: 'ball', v: 'b', x: 3, z: 12.5 },
  { id: 'ball4', kind: 'ball', v: 'a', x: -3, z: 13.5 },
  // ящики
  { id: 'crate1', kind: 'crate', x: -8, z: -3 },
  { id: 'crate2', kind: 'crate', x: -8.8, z: -3.9 },
  { id: 'crate3', kind: 'crate', x: -7.4, z: -4.2 },
  { id: 'crate4', kind: 'crate', x: 9.5, z: 3 },
  { id: 'crate5', kind: 'crate', x: 10.4, z: 3.8 },
  // походные эмалированные кружки на брёвнах у костра
  { id: 'mug1', kind: 'mug', v: 'red', x: CAMPFIRE.x + 2.38, z: CAMPFIRE.z - 4.07, rot: [0, 0.8, 0] },
  { id: 'mug2', kind: 'mug', v: 'blue', x: CAMPFIRE.x - 4.42, z: CAMPFIRE.z + 1.66, rot: [0, -2, 0] },
  { id: 'mug3', kind: 'mug', v: 'green', ...on('table', -0.02, 0.13), rot: [0, 2.2, 0] },
  // биты в стойке у костра и одна у беседки
  bat('bat1', 'wood', -0.33, 0.07),
  bat('bat2', 'alu', -0.11, -0.04),
  bat('bat3', 'wood2', 0.11, 0.05),
  { id: 'bat4', kind: 'bat', v: 'alu2', x: GAZEBO.x - 6.2, z: GAZEBO.z + 6.2, rot: [0, 0.8, Math.PI / 2] },
  // зонты: в подставке у спавна и у начала тропы в дождь
  umb('umb1', 'red', 'umbSpawn', 0.05, -0.04, 0.1, 0.06),
  umb('umb2', 'yellow', 'umbSpawn', -0.05, 0.04, -0.08, 0.1),
  umb('umb3', 'blue', 'umbSpawn', 0.03, 0.06, -0.06, -0.1),
  umb('umb4', 'green', 'umbRain', 0.04, -0.03, 0.1, 0.05),
  umb('umb5', 'rainbow', 'umbRain', -0.04, 0.04, -0.08, -0.08),
  // фонари: на столике и на брёвнах у костра (горят)
  { id: 'lantern1', kind: 'lantern', ...on('table', -0.22, -0.04), s: 1 },
  { id: 'lantern2', kind: 'lantern', x: 2.58, z: 3.975, s: 1 }, // на концах брёвен-скамеек
  { id: 'lantern3', kind: 'lantern', x: -2.58, z: -3.975, s: 1 },
  // гитара прислонена к бревну, бумбокс на ящике
  { id: 'guitar1', kind: 'guitar', x: -2.36, z: 4.5, rot: [-0.42, 0.44, 0] },
  { id: 'boombox1', kind: 'boombox', ...on('boomCrate', 0, 0), rot: [0, -0.49, 0], s: 0 },
  // фрисби на траве у спавна
  { id: 'frisbee1', kind: 'frisbee', v: 'orange', x: -1.4, z: 11.2, rot: [0, 0.4, 0] },
  { id: 'frisbee2', kind: 'frisbee', v: 'lime', x: 3.6, z: 9.6, rot: [0, 1.1, 0] },
  // чайник с чашками на пне, книги на столике
  { id: 'teapot1', kind: 'teapot', ...on('stump', 0.02, -0.06), rot: [0, -2.2, 0] },
  { id: 'cup1', kind: 'cup', ...on('stump', 0.17, 0.12), rot: [0, 0.6, 0] },
  { id: 'cup2', kind: 'cup', ...on('stump', -0.16, 0.13), rot: [0, 2.4, 0] },
  // стопка книг: dy — высота низа книги над землёй (столешница 0.575, толщина книги 0.036)
  { id: 'book1', kind: 'book', v: 'red', ...on('table', 0.17, -0.05), rot: [0, 0.2, 0], dy: 0.575 },
  { id: 'book2', kind: 'book', v: 'green', ...on('table', 0.18, -0.04), rot: [0, -0.15, 0], dy: 0.611 },
  { id: 'book3', kind: 'book', v: 'blue', ...on('table', 0.16, -0.06), rot: [0, 0.35, 0], dy: 0.647 },
  // плюшевый мишка на качелях
  { id: 'teddy1', kind: 'teddy', x: -13.6, z: -20.45, rot: [0, 0.35, 0] },
  ...chessSet(),
];

export const PROP_IDS = new Set(PROPS.map((p) => p.id));
// Предметы с состоянием (зонт открыт, фонарь горит, музыка играет) — сервер хранит его и отдаёт в welcome.
export const STATE_IDS = new Set(PROPS.filter((p) => KINDS[p.kind].state).map((p) => p.id));
