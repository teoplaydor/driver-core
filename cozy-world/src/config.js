// Настройки качества и общие константы клиента.

export const QUALITY = {
  high: { dpr: 2, shadow: 2048, shadowRange: 60, grassChunks: 3, grassPerChunk: 1000, trees: 1 },
  medium: { dpr: 1.5, shadow: 1024, shadowRange: 48, grassChunks: 2, grassPerChunk: 700, trees: 0.8 },
  low: { dpr: 1, shadow: 0, shadowRange: 40, grassChunks: 2, grassPerChunk: 380, trees: 0.55 },
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
