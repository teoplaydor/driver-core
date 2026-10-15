// Настройки качества и общие константы клиента.
// Природа (WS2): grassRadius/grassChunk (размер чанка, м)/grassBlades ([травинок вблизи, сегментов, травинок вдали])/grassDensity (пучков на м² вблизи)/grassLod (граница подробных травинок)/
// grassFade (откуда трава начинает редеть), treeNear (радиус подробных деревьев), treeDetail (детализация
// ближних моделей), decorRadius/decorDensity (цветы, папоротники, камешки...), terrainLite (упрощённый шейдер земли).
// Рендер (WS4): post (постобработка: HDR-буфер, bloom, цвет; false - прямой рендер с тонмаппингом), ao (затенение по
// глубине) + aoSamples, bloomLevels/bloomStart (мипов bloom и делитель разрешения первого), aa ('smaa' | 'fxaa' | null),
// shadowCascades (2 - ближний чёткий каскад shadowNear/shadowNearRange + дальний), waterDeferred (вода с экранными
// отражениями и преломлением), waterSegments (волны вершинами), skyDetail (октавы облаков, число облаков),
// renderLite (короткие шейдеры: туман только по расстоянию, без пылинок, пыльцы и светлячков).

export const QUALITY = {
  high: {
    dpr: 2, shadow: 2048, shadowRange: 64, grassChunks: 3, grassPerChunk: 1000, trees: 1,
    grassRadius: 56, grassChunk: 16, grassBlades: [8, 4, 6], grassDensity: 7.5, grassLod: 13, grassFade: 26, treeNear: 75, treeDetail: 1, decorRadius: 60, decorDensity: 1,
    post: true, ao: true, aoSamples: 12, bloomLevels: 6, bloomStart: 2, aa: 'smaa', shadowCascades: 2, shadowNear: 1024, shadowNearRange: 14,
    waterDeferred: true, waterSegments: 96, skyDetail: 1,
  },
  medium: {
    dpr: 1.5, shadow: 1024, shadowRange: 48, grassChunks: 2, grassPerChunk: 700, trees: 0.8,
    grassRadius: 40, grassChunk: 20, grassBlades: [6, 3, 4], grassDensity: 4, grassLod: 9, grassFade: 18, treeNear: 52, treeDetail: 0.65, decorRadius: 42, decorDensity: 0.6,
    post: true, ao: false, bloomLevels: 4, bloomStart: 2, aa: 'fxaa', shadowCascades: 1, waterDeferred: false, waterSegments: 40, skyDetail: 0.7,
  },
  low: {
    dpr: 1, shadow: 0, shadowRange: 40, grassChunks: 2, grassPerChunk: 380, trees: 0.55,
    grassRadius: 24, grassChunk: 24, grassBlades: [5, 3, 3], grassDensity: 2.0, grassLod: 6, grassFade: 10, treeNear: 32, treeDetail: 0.5, decorRadius: 26, decorDensity: 0.35,
    terrainLite: true,
    post: false, ao: false, aa: null, shadowCascades: 1, waterDeferred: false, waterSegments: 1, skyDetail: 0.5, renderLite: true,
  },
};

export const isTouchDevice = () =>
  typeof window !== 'undefined' && ('ontouchstart' in window || navigator.maxTouchPoints > 0) && window.matchMedia('(pointer: coarse)').matches;

// Программный WebGL (SwiftShader, llvmpipe...): видеокарты нет или браузер её не использует.
// Там даже medium идёт секунды на кадр, поэтому по умолчанию - low.
export function isSoftwareGL() {
  try {
    const gl = document.createElement('canvas').getContext('webgl2');
    if (!gl) return false;
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    const name = String(gl.getParameter(ext ? ext.UNMASKED_RENDERER_WEBGL : gl.RENDERER));
    gl.getExtension('WEBGL_lose_context')?.loseContext();
    return /swiftshader|llvmpipe|softpipe|software/i.test(name);
  } catch {
    return false;
  }
}

export function detectQuality() {
  try {
    const saved = localStorage.getItem('cozy.quality');
    if (saved && QUALITY[saved]) return saved;
  } catch { /* приватный режим */ }
  // Превью-страница (одиночный режим) открывается где угодно: без видеокарты сразу low.
  // На dev-сервере по умолчанию прежнее поведение - замеры и скриншоты других потоков без ?q= не меняются.
  if (typeof window !== 'undefined' && window.COZY_SOLO && isSoftwareGL()) return 'low';
  return isTouchDevice() ? 'medium' : 'high';
}
