// Лесная плотность: тот же шум, что раскладывает деревья. Нужна траве, земле и мелкому декору
// (папоротники и грибы в тени леса, подстилка, реже трава под кронами).

import { Noise2D, smoothstep } from '../../util/math.js';

const forestNoise = new Noise2D(31337);

// 0..1: насколько точка "в лесу"
export function forestDensity(x, z) {
  const f = forestNoise.fbm(x * 0.017 + 11, z * 0.017 - 7, 3) * 0.5 + 0.5;
  return smoothstep(0.36, 0.66, f);
}
