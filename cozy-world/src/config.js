// Настройки качества и общие константы клиента.
// Природа (WS2): grassRadius/grassChunk (размер чанка, м)/grassBlades ([травинок вблизи, сегментов, травинок вдали])/grassDensity (пучков на м² вблизи)/grassLod (граница подробных травинок)/
// grassFade (откуда трава начинает редеть), treeNear (радиус подробных деревьев), treeDetail (детализация
// ближних моделей), decorRadius/decorDensity (цветы, папоротники, камешки...), terrainLite (упрощённый шейдер земли).

export const QUALITY = {
  high: {
    dpr: 2, shadow: 2048, shadowRange: 60, grassChunks: 3, grassPerChunk: 1000, trees: 1,
    grassRadius: 56, grassChunk: 16, grassBlades: [8, 4, 6], grassDensity: 7.5, grassLod: 13, grassFade: 26, treeNear: 75, treeDetail: 1, decorRadius: 60, decorDensity: 1,
  },
  medium: {
    dpr: 1.5, shadow: 1024, shadowRange: 48, grassChunks: 2, grassPerChunk: 700, trees: 0.8,
    grassRadius: 40, grassChunk: 20, grassBlades: [6, 3, 4], grassDensity: 4, grassLod: 9, grassFade: 18, treeNear: 52, treeDetail: 0.65, decorRadius: 42, decorDensity: 0.6,
  },
  low: {
    dpr: 1, shadow: 0, shadowRange: 40, grassChunks: 2, grassPerChunk: 380, trees: 0.55,
    grassRadius: 24, grassChunk: 24, grassBlades: [5, 3, 3], grassDensity: 2.0, grassLod: 6, grassFade: 10, treeNear: 32, treeDetail: 0.5, decorRadius: 26, decorDensity: 0.35,
    terrainLite: true,
  },
};

export const isTouchDevice = () =>
  typeof window !== 'undefined' && ('ontouchstart' in window || navigator.maxTouchPoints > 0) && window.matchMedia('(pointer: coarse)').matches;

export function detectQuality() {
  try {
    const saved = localStorage.getItem('cozy.quality');
    if (saved && QUALITY[saved]) return saved;
  } catch { /* приватный режим */ }
  return isTouchDevice() ? 'medium' : 'high';
}
