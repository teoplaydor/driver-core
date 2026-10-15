// Общая раскладка мира: используется и клиентом, и сервером (сервер знает, какие предметы существуют).
// Рельеф считается только на клиенте, поэтому здесь лежат плоские координаты и высоты столов.

export const HUB_H = 0.5; // высота ровной площадки вокруг костра

export const SPAWN = { x: 0, z: 9.5, yaw: 0 };
export const CAMPFIRE = { x: 0, z: 0 };
export const GAZEBO = { x: 27, z: -19, floorTop: HUB_H + 0.3 };
export const TABLE = { x: GAZEBO.x, z: GAZEBO.z, top: GAZEBO.floorTop + 0.78 };

// Интерьеры лежат за пределами карты.
export const INTERIOR_X = 700;
export const APARTMENT = { x: INTERIOR_X, z: 0, w: 10, d: 8, h: 3.2 };

// Шахматная доска на столе: центр, верхняя плоскость, шаг клетки.
export const BOARD = { x: TABLE.x, z: TABLE.z, y: TABLE.top + 0.05, cell: 0.115 };
