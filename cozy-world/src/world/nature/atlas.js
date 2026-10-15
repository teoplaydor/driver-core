// Процедурный атлас листвы (canvas): пучок листьев, еловая лапа, кленовые листья, лист папоротника,
// мелкие листья берёзы и белый непрозрачный участок для "сплошных" частей (стволы, ягоды).
// Текстура серая (оттенок даёт цвет вершин/экземпляра), альфа вырезает рваные края.

import * as THREE from 'three';
import { mulberry32, TAU } from '../../util/math.js';

const S = 1024;

// Прямоугольники в пикселях холста: [x0, y0, x1, y1]
const RECTS = {
  leaf: [0, 0, 512, 512],
  fir: [512, 0, 1024, 512],
  maple: [0, 512, 512, 1024],
  fern: [512, 512, 768, 1024],
  birch: [768, 512, 1024, 768],
  white: [768, 768, 1024, 1024],
};

// uv-прямоугольники: [u0, v0, u1, v1] (v вверх; холст переворачивается при загрузке)
export const ATLAS = {};
for (const [k, [x0, y0, x1, y1]] of Object.entries(RECTS)) {
  ATLAS[k] = [x0 / S, 1 - y1 / S, x1 / S, 1 - y0 / S];
}
// точка внутри белого участка - для непрозрачных деталей
export const WHITE_UV = [(ATLAS.white[0] + ATLAS.white[2]) / 2, (ATLAS.white[1] + ATLAS.white[3]) / 2];
export const ATLAS_SIZE = S;

const gray = (v, a = 1) => {
  const c = Math.round(Math.max(0, Math.min(1, v)) * 255);
  return `rgba(${c},${c},${c},${a})`;
};

// Заострённый лист вдоль -y из (0,0)
function leafPath(g, len, wid, tipBend = 0) {
  g.beginPath();
  g.moveTo(0, 0);
  g.bezierCurveTo(wid * 0.75, -len * 0.18, wid * 0.62 + tipBend, -len * 0.72, tipBend, -len);
  g.bezierCurveTo(-wid * 0.62 + tipBend, -len * 0.72, -wid * 0.75, -len * 0.18, 0, 0);
  g.closePath();
}

function drawLeaf(g, x, y, ang, len, wid, v, rand) {
  g.save();
  g.translate(x, y);
  g.rotate(ang);
  leafPath(g, len, wid, (rand() - 0.5) * wid * 0.4);
  // светлый край, тёмная середина - объём листа
  const gr = g.createLinearGradient(-wid / 2, 0, wid / 2, 0);
  gr.addColorStop(0, gray(v * 0.9));
  gr.addColorStop(0.5, gray(v * 1.06));
  gr.addColorStop(1, gray(v * 0.8));
  g.fillStyle = gr;
  g.fill();
  g.strokeStyle = gray(v * 0.62, 0.8);
  g.lineWidth = Math.max(1, wid * 0.07);
  g.beginPath();
  g.moveTo(0, -len * 0.05);
  g.lineTo(0, -len * 0.8);
  g.stroke();
  g.restore();
}

// Пучок листьев: тёмные снизу, светлые сверху, края рваные.
function drawClump(g, [x0, y0, x1, y1], seed, { count = 110, size = 1, kind = 'leaf' } = {}) {
  const rand = mulberry32(seed);
  const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
  const R = (x1 - x0) * 0.33;
  for (let i = 0; i < count; i++) {
    const t = i / count;
    // ранние (нижние, тёмные) листья - ближе к центру, поздние (светлые) - по всему пучку
    const r = R * Math.sqrt(rand()) * (0.55 + 0.45 * t);
    const a = rand() * TAU;
    const x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r * 0.92;
    const out = Math.atan2(y - cy, x - cx) + Math.PI / 2;
    const ang = r < R * 0.3 ? rand() * TAU : out + (rand() - 0.5) * 1.2;
    const v = 0.46 + t * 0.52 + (rand() - 0.5) * 0.12;
    const len = (kind === 'birch' ? 30 : 56) * size * (0.75 + rand() * 0.5);
    // лист рисуется от основания: сдвигаем основание, чтобы центр листа попал в (x, y)
    const bx = x - Math.sin(ang) * len * 0.45, by = y + Math.cos(ang) * len * 0.45;
    if (kind === 'maple') drawMaple(g, x, y, ang, len * 0.62, v, rand);
    else drawLeaf(g, bx, by, ang, len, len * (kind === 'birch' ? 0.62 : 0.48), v, rand);
  }
}

function drawMaple(g, x, y, ang, r, v, rand) {
  g.save();
  g.translate(x, y);
  g.rotate(ang);
  g.beginPath();
  const lobes = [[0, 1], [0.95, 0.82], [1.85, 0.62], [-0.95, 0.82], [-1.85, 0.62]];
  // звёздочка из 5 лопастей с зубчиками
  const pts = [];
  for (const [la, lr] of lobes) {
    const a = -Math.PI / 2 + la;
    pts.push([Math.cos(a - 0.32) * r * lr * 0.5, Math.sin(a - 0.32) * r * lr * 0.5]);
    pts.push([Math.cos(a - 0.12) * r * lr * 0.86, Math.sin(a - 0.12) * r * lr * 0.86]);
    pts.push([Math.cos(a) * r * lr, Math.sin(a) * r * lr]);
    pts.push([Math.cos(a + 0.12) * r * lr * 0.86, Math.sin(a + 0.12) * r * lr * 0.86]);
  }
  pts.sort((p, q) => Math.atan2(p[1], p[0]) - Math.atan2(q[1], q[0]));
  g.moveTo(0, r * 0.25);
  for (const [px, py] of pts) g.lineTo(px, py);
  g.closePath();
  g.fillStyle = gray(v);
  g.fill();
  g.strokeStyle = gray(v * 0.7, 0.7);
  g.lineWidth = 1.5;
  for (const [la] of lobes) {
    const a = -Math.PI / 2 + la;
    g.beginPath();
    g.moveTo(0, 0);
    g.lineTo(Math.cos(a) * r * 0.7, Math.sin(a) * r * 0.7);
    g.stroke();
  }
  g.restore();
}

// Перистая ветка (ёлка/папоротник): стебель снизу вверх, листочки с зубчатыми краями.
function drawFrond(g, [x0, y0, x1, y1], seed, o) {
  const rand = mulberry32(seed);
  const cx = (x0 + x1) / 2;
  const bottom = y1 - 10, top = y0 + 12;
  const L = bottom - top;
  const W = (x1 - x0) / 2 - 8;
  // стебель
  g.strokeStyle = gray(0.42);
  g.lineCap = 'round';
  for (let k = 0; k < 2; k++) {
    g.lineWidth = o.stem * (1 - k * 0.6);
    g.beginPath();
    g.moveTo(cx, bottom);
    g.quadraticCurveTo(cx + o.bend * W, top + L * 0.5, cx + o.bend * W * 0.5, top + L * k * 0.5);
    g.stroke();
  }
  const N = o.leaflets;
  // сначала нижний (тёмный) слой, потом верхний светлый
  for (const layer of [0, 1]) {
    for (let i = 0; i < N; i++) {
      const t = (i + 0.5) / N;
      const y = bottom - t * L * 0.97;
      const sx = cx + o.bend * W * Math.sin(t * Math.PI * 0.9) * 0.6;
      for (const side of [-1, 1]) {
        const prof = o.profile(t);
        const len = W * prof * (0.85 + rand() * 0.3) * (layer ? 0.92 : 1.05);
        if (len < 4) continue;
        const ang = side * (o.angle - t * o.angleDrop + (rand() - 0.5) * 0.15) ;
        const v = layer ? 0.66 + t * 0.26 + rand() * 0.1 : 0.42 + rand() * 0.08;
        drawLeaflet(g, sx, y, ang, len, Math.max(4, o.width * (1 - t * 0.55)), v, o.serr, rand);
      }
    }
  }
}

// Листочек с зубчатыми краями (хвоинки / доли папоротника)
function drawLeaflet(g, x, y, ang, len, wid, v, serr, rand) {
  g.save();
  g.translate(x, y);
  g.rotate(ang);
  const teeth = Math.max(3, Math.round(len / serr));
  g.beginPath();
  g.moveTo(0, 0);
  for (let i = 1; i <= teeth; i++) {
    const t = i / teeth;
    const w = wid * Math.sin(Math.min(1, t * 1.15) * Math.PI) * 0.5 + 1;
    g.lineTo(w * 1.35, -len * (t - 0.5 / teeth));
    g.lineTo(w * 0.55, -len * t);
  }
  g.lineTo(0, -len * 1.04);
  for (let i = teeth; i >= 1; i--) {
    const t = i / teeth;
    const w = wid * Math.sin(Math.min(1, t * 1.15) * Math.PI) * 0.5 + 1;
    g.lineTo(-w * 0.55, -len * t);
    g.lineTo(-w * 1.35, -len * (t - 0.5 / teeth));
  }
  g.closePath();
  const gr = g.createLinearGradient(0, 0, 0, -len);
  gr.addColorStop(0, gray(v * 0.82));
  gr.addColorStop(1, gray(Math.min(1, v * 1.12)));
  g.fillStyle = gr;
  g.fill();
  g.strokeStyle = gray(v * 0.6, 0.6);
  g.lineWidth = 1.2;
  g.beginPath();
  g.moveTo(0, 0);
  g.lineTo(0, -len * 0.85);
  g.stroke();
  g.restore();
}

let _tex = null;

export function foliageAtlas() {
  if (_tex) return _tex;
  const c = document.createElement('canvas');
  c.width = c.height = S;
  const g = c.getContext('2d');
  g.clearRect(0, 0, S, S);

  drawClump(g, RECTS.leaf, 11, { count: 150, size: 1.0 });
  drawFrond(g, RECTS.fir, 21, {
    stem: 8, bend: 0.0, leaflets: 19, angle: 1.0, angleDrop: 0.35, width: 22, serr: 4,
    profile: (t) => Math.pow(1 - t, 0.55) * Math.min(1, 0.55 + t * 3),
  });
  drawClump(g, RECTS.maple, 31, { count: 70, size: 1.1, kind: 'maple' });
  drawFrond(g, RECTS.fern, 41, {
    stem: 6, bend: 0.12, leaflets: 17, angle: 1.15, angleDrop: 0.3, width: 22, serr: 6,
    profile: (t) => Math.sin(Math.min(1, t * 1.05) * Math.PI * 0.92 + 0.25) * (1 - t * 0.35),
  });
  drawClump(g, RECTS.birch, 51, { count: 120, size: 0.8, kind: 'birch' });
  const [wx0, wy0, wx1, wy1] = RECTS.white;
  g.fillStyle = '#ffffff';
  g.fillRect(wx0 + 8, wy0 + 8, wx1 - wx0 - 16, wy1 - wy0 - 16);

  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.NoColorSpace;
  tex.anisotropy = 4;
  tex.generateMipmaps = true;
  tex.minFilter = THREE.LinearMipmapLinearFilter;
  tex.magFilter = THREE.LinearFilter;
  _tex = tex;
  return tex;
}

// Для отладки: сам холст атласа
export const atlasCanvas = () => foliageAtlas().image;
