// Лесная плотность: тот же шум, что раскладывает деревья. Нужна траве, земле и мелкому декору
// (папоротники и грибы в тени леса, подстилка, реже трава под кронами).

import { Noise2D, smoothstep } from '../../util/math.js';

const forestNoise = new Noise2D(31337);

// 0..1: насколько точка "в лесу"
export function forestDensity(x, z) {
  const f = forestNoise.fbm(x * 0.017 + 11, z * 0.017 - 7, 3) * 0.5 + 0.5;
  return smoothstep(0.36, 0.66, f);
}

// Плотность реального полога: как forestDensity, но с поляной вокруг хаба, где деревья не растут
// (та же поправка, что в раскладке деревьев vegetation.js).
export function canopyDensity(x, z) {
  return forestDensity(x, z) * smoothstep(30, 64, Math.hypot(x, z));
}

// Пятна цветочных полян (общие для травы и декора): > ~0.16 - цветы, там трава ниже.
const flowerNoise = new Noise2D(5150);
export function flowerPatch(x, z) {
  return flowerNoise.fbm(x * 0.065, z * 0.065, 2);
}
