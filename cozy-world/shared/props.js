// Список предметов, которые лежат в мире. Раскладка детерминированная: клиенты создают предметы сами,
// а сервер хранит только отличия (кто что передвинул). id стабильны между клиентами и сервером.

import { BOARD, GAZEBO, CAMPFIRE } from './layout.js';

// kind -> физический радиус (шар, который аппроксимирует предмет) и вид столкновения
export const KINDS = {
  ball: { r: 0.32, shape: 'sphere', bounce: 0.62 },
  crate: { r: 0.34, shape: 'box', bounce: 0.15 },
  mug: { r: 0.11, shape: 'box', bounce: 0.1 },
  club: { r: 0.15, shape: 'sphere', bounce: 0.25 },
  chess: { r: 0.075, shape: 'box', bounce: 0.05 },
};

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

export const PROPS = [
  // мячи у костра и на поляне
  { id: 'ball1', kind: 'ball', color: '#ff6b5a', x: 5.5, z: 7.5 },
  { id: 'ball2', kind: 'ball', color: '#4aa8ff', x: -6.5, z: 4.5 },
  { id: 'ball3', kind: 'ball', color: '#ffd23f', x: 3, z: 12.5 },
  { id: 'ball4', kind: 'ball', color: '#6bd36b', x: -3, z: 13.5 },
  // ящики
  { id: 'crate1', kind: 'crate', x: -8, z: -3 },
  { id: 'crate2', kind: 'crate', x: -8.8, z: -3.9 },
  { id: 'crate3', kind: 'crate', x: -7.4, z: -4.2 },
  { id: 'crate4', kind: 'crate', x: 9.5, z: 3 },
  { id: 'crate5', kind: 'crate', x: 10.4, z: 3.8 },
  // кружки на брёвнах у костра
  { id: 'mug1', kind: 'mug', color: '#e86a4a', x: CAMPFIRE.x + 2.2, z: CAMPFIRE.z - 3.2 },
  { id: 'mug2', kind: 'mug', color: '#4a9be8', x: CAMPFIRE.x - 3.1, z: CAMPFIRE.z - 2.4 },
  { id: 'mug3', kind: 'mug', color: '#e8c84a', x: CAMPFIRE.x - 3.4, z: CAMPFIRE.z + 2.2 },
  // мягкие дубинки для арены (стоят у входа в беседку, перетащат куда надо)
  { id: 'club1', kind: 'club', color: '#ff8a3c', x: GAZEBO.x - 5.5, z: GAZEBO.z + 6.0 },
  { id: 'club2', kind: 'club', color: '#5aa0ff', x: GAZEBO.x - 6.2, z: GAZEBO.z + 6.4 },
  { id: 'club3', kind: 'club', color: '#7ad36a', x: GAZEBO.x - 6.8, z: GAZEBO.z + 5.8 },
  { id: 'club4', kind: 'club', color: '#ff6b9a', x: GAZEBO.x - 7.3, z: GAZEBO.z + 6.5 },
  ...chessSet(),
];

export const PROP_IDS = new Set(PROPS.map((p) => p.id));
