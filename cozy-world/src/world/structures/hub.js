// Хаб: костёр (камни, шалаш из поленьев, угли, пламя-шейдер, искры, дым), брёвна-скамейки и лавки,
// указатель, поленница с колодой, бочки, тележка, почтовый ящик, большое дерево (гирлянды, качели,
// скворечник) и обустройство троп (фонари, заборчики).

import * as THREE from 'three';
import { heightAt, samplePath, PATHS, zoneWeights } from '../terrain-data.js';
import { buildHeroTree } from '../vegetation.js';
import { mulberry32, hash2, smoothstep, TAU } from '../../util/math.js';
import { CAMPFIRE } from '../../../shared/layout.js';
import {
  SURF, PAT, FLICKER, glow, T, TY, addLog, stoneGeo, lathe, tube, sagPoints, slabGeo, cylUV,
} from './kit.js';
import { atlas } from './material.js';
import { buildFlames, buildParticles } from './fx.js';

const _q = new THREE.Quaternion(), _up = new THREE.Vector3(1, 0, 0);
// Матрица: деталь вдоль локальной оси X направлена по dir, центр в p, поворот вокруг оси twist.
export function along(p, dir, twist = 0) {
  _q.setFromUnitVectors(_up, dir.clone().normalize());
  const m = new THREE.Matrix4().makeRotationFromQuaternion(_q);
  if (twist) m.multiply(new THREE.Matrix4().makeRotationX(twist));
  return m.setPosition(p);
}

// Цвет камня: серый с вариациями, у костра - закопчённый с внутренней стороны.
export function stoneColor(base, seed, soot = null) {
  const c0 = new THREE.Color(base);
  const warm = new THREE.Color('#9a8a78'), cool = new THREE.Color('#7f8790'), sootC = new THREE.Color('#2e2a27');
  const k = hash2(seed, 3);
  c0.lerp(k > 0.5 ? warm : cool, (k - 0.5) * 0.8);
  return (x, y, z, nx, ny, nz, out, wx, wy, wz) => {
    out.copy(c0).multiplyScalar(0.88 + 0.24 * hash2(Math.round(x * 20), Math.round(y * 20) + seed, 5));
    if (soot) {
      const d = Math.hypot(wx - soot.x, wz - soot.z);
      out.lerp(sootC, smoothstep(soot.r + 0.12, soot.r - 0.12, d) * 0.75);
    }
  };
}

// Снежная шапка поверх цвета: верхние грани белые.
export function snowCap(base) {
  const sn = new THREE.Color('#f2f6fb');
  return (x, y, z, nx, ny, nz, out, wx, wy, wz) => {
    if (typeof base === 'function') base(x, y, z, nx, ny, nz, out, wx, wy, wz); else out.set(base);
    out.lerp(sn, smoothstep(0.35, 0.65, ny));
  };
}

// Деревянная табличка с вырезанным текстом в атласе: фон - доска с волокнами.
export function woodLabel(text, w, h, { bg = '#c99a5e', fg = '#3d2412', font, arrow = 0 } = {}) {
  return atlas().region(w, h, (g) => {
    g.fillStyle = bg;
    g.fillRect(0, 0, w, h);
    const rand = mulberry32(text.length * 31 + w);
    for (let i = 0; i < 26; i++) {
      g.strokeStyle = `rgba(90,50,20,${0.06 + rand() * 0.1})`;
      g.lineWidth = 1 + rand() * 2.5;
      const y = rand() * h;
      g.beginPath();
      g.moveTo(0, y);
      g.bezierCurveTo(w * 0.3, y + (rand() - 0.5) * 10, w * 0.6, y + (rand() - 0.5) * 10, w, y + (rand() - 0.5) * 6);
      g.stroke();
    }
    // светлая фаска по краю и тень
    g.strokeStyle = 'rgba(255,230,190,0.35)'; g.lineWidth = 4; g.strokeRect(3, 3, w - 6, h - 6);
    g.strokeStyle = 'rgba(60,30,10,0.35)'; g.lineWidth = 3; g.strokeRect(7, 7, w - 14, h - 14);
    // кегль подбираем так, чтобы надпись влезла в доску
    let fs = Math.round(h * 0.52);
    g.font = font || `800 ${fs}px "Baloo 2", "Trebuchet MS", system-ui, sans-serif`;
    const tw = g.measureText(text).width, room = w * (arrow ? 0.8 : 0.86);
    if (!font && tw > room) { fs = Math.floor(fs * room / tw); g.font = `800 ${fs}px "Baloo 2", "Trebuchet MS", system-ui, sans-serif`; }
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    const cx = w / 2 - (arrow ? arrow * h * 0.18 : 0);
    // вырезанные буквы: светлый нижний край + тёмная заливка
    g.fillStyle = 'rgba(255,225,180,0.55)';
    g.fillText(text, cx + 1.5, h / 2 + 3);
    g.fillStyle = fg;
    g.fillText(text, cx, h / 2 + 1);
  });
}

// ---------- костёр ----------

// Пятно кострища: диск из колец с неровным краем (у CircleGeometry нет промежуточных колец, и цвет
// интерполировался бы от центра сразу к краю). Центр чуть приподнят горкой пепла, край утоплен в землю.
function ashBed(kit, R, seed) {
  const seg = kit.seg(18, 30, 40), rings = kit.seg(4, 7, 9);
  const rand = mulberry32(seed);
  const p0 = rand() * TAU, p1 = rand() * TAU, p2 = rand() * TAU;
  const edge = (a) => R * (1 + 0.07 * Math.sin(a * 3 + p0) + 0.05 * Math.sin(a * 5 + p1) + 0.03 * Math.sin(a * 11 + p2));
  const pos = [0, 0.035, 0], idx = [];
  for (let i = 1; i <= rings; i++) {
    const t = Math.pow(i / rings, 0.85);
    for (let k = 0; k < seg; k++) {
      const a = (k / seg) * TAU;
      const r = edge(a) * t;
      pos.push(Math.cos(a) * r, 0.035 * (1 - t * t) - 0.006 * t, Math.sin(a) * r);
    }
  }
  for (let k = 0; k < seg; k++) idx.push(0, 1 + ((k + 1) % seg), 1 + k);
  for (let i = 1; i < rings; i++) {
    const o = 1 + (i - 1) * seg, n = o + seg;
    for (let k = 0; k < seg; k++) {
      const k1 = (k + 1) % seg;
      idx.push(o + k, o + k1, n + k, o + k1, n + k1, n + k);
    }
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.setIndex(idx);
  geo.computeVertexNormals();
  return { geo, edge };
}

function campfire(ctx) {
  const { kit, col, root, anim } = ctx;
  const { x: cx, z: cz } = CAMPFIRE;
  const y0 = heightAt(cx, cz);
  const rand = mulberry32(8);
  kit.group('hub', { cx, cz, r: 14 });
  kit.push(T(cx, y0, cz));

  // кострище: пятно с неровным краем - тлеющие угли в центре, пепел, копоть, край уходит в землю
  const bed = ashBed(kit, 1.12, 8);
  const coal = new THREE.Color('#1d1612'), ember = new THREE.Color('#ff5a12'), ashC = new THREE.Color('#5d5852'),
    ashL = new THREE.Color('#9d978e'), soot = new THREE.Color('#3b322b'), rim = new THREE.Color('#7d6852');
  const bedT = (x, z) => Math.hypot(x, z) / bed.edge(Math.atan2(z, x));
  const glowAt = (x, z) => {
    const t = bedT(x, z);
    const n = hash2(Math.round(x * 14), Math.round(z * 14), 17);
    return smoothstep(0.42, 0.08, t) * (n > 0.45 ? (n - 0.45) * 5.5 : 0.15);
  };
  kit.setLayer('detail').add(bed.geo, (x, y, z, nx, ny, nz, out) => {
    const t = bedT(x, z);
    const n = hash2(Math.round(x * 9), Math.round(z * 9), 5);
    out.copy(ashC).lerp(ashL, n * 0.6 * smoothstep(0.25, 0.6, t));
    out.lerp(coal, smoothstep(0.5, 0.2, t) * 0.85);
    out.lerp(soot, smoothstep(0.62, 0.86, t));
    out.lerp(rim, smoothstep(0.88, 1.0, t));
    const g = glowAt(x, z);
    if (g > 0.2) out.lerp(ember, Math.min(1, g * 0.5));
  }, [0.98, 0, 0, PAT.ash + FLICKER], T(0, 0.012, 0), { emis: (x, y, z) => glowAt(x, z) * 1.6 });
  // угольки и головешки: часть тлеет (свечение мерцает), часть - чёрный уголь
  for (let i = 0; i < kit.seg(8, 16, 22); i++) {
    const a = rand() * TAU, r = Math.sqrt(rand()) * 0.6;
    const s = 0.03 + rand() * 0.045;
    const hotK = smoothstep(0.62, 0.1, r) * (rand() < 0.75 ? 1 : 0);
    const c = new THREE.Color('#231a15').lerp(new THREE.Color('#ff6a1c'), hotK * 0.85);
    kit.add(stoneGeo(s, 30 + i, 0, 0.5, 0.6), c, hotK > 0 ? glow(0.6 + hotK * 2.6, PAT.plain, true) : SURF.ash, T(Math.cos(a) * r, 0.02, Math.sin(a) * r, 0, rand() * 3, 0));
  }
  for (let i = 0; i < kit.seg(1, 3, 4); i++) {
    const a = rand() * TAU, r = 0.55 + rand() * 0.25;
    addLog(kit, 0.24 + rand() * 0.16, 0.035 + rand() * 0.015, TY(Math.cos(a) * r, 0.035, Math.sin(a) * r, -a + Math.PI / 2 + (rand() - 0.5), rand() * TAU), {
      seed: 340 + i, char: 0.45, ember: 0.8, bark: '#4a3424', barkDark: '#241810', seg: kit.seg(5, 6, 8), rings: 2,
    });
  }

  // камни вокруг: крупные разного размера + мелкие в промежутках
  kit.setLayer('solid');
  const N = 13;
  for (let i = 0; i < N; i++) {
    const a = (i / N) * TAU + (rand() - 0.5) * 0.18, r = 1.22 + rand() * 0.1;
    const s = 0.2 + rand() * 0.13;
    const sx = Math.cos(a) * r, sz = Math.sin(a) * r;
    kit.add(stoneGeo(s, 100 + i, kit.seg(1, 2, 2), 0.4, 0.72), stoneColor('#a3a09a', i, { x: cx, z: cz, r: r - s * 0.2 }), SURF.stone,
      T(sx, -0.04, sz, (rand() - 0.5) * 0.3, rand() * TAU, (rand() - 0.5) * 0.3));
  }
  kit.setLayer('detail');
  for (let i = 0; i < 9; i++) {
    const a = ((i + 0.5) / 9) * TAU + rand() * 0.3, r = 1.48 + rand() * 0.12;
    kit.add(stoneGeo(0.08 + rand() * 0.06, 200 + i, kit.seg(0, 1, 1), 0.4, 0.7), stoneColor('#94918a', 50 + i), SURF.stone,
      T(Math.cos(a) * r, -0.02, Math.sin(a) * r, 0, rand() * TAU, 0));
  }

  // шалаш из поленьев: низ на окружности 0.5 м, верх сходится над центром. Ось бревна направлена
  // сверху вниз - обуглен и тлеет нижний конец, обращённый к огню; верхний - светлый спил.
  kit.setLayer('solid');
  const NL = 7;
  for (let i = 0; i < NL; i++) {
    const a = (i / NL) * TAU + 0.3 + (rand() - 0.5) * 0.25;
    const rb = 0.5 + rand() * 0.07, top = 0.8 + rand() * 0.14;
    const b = new THREE.Vector3(Math.cos(a) * rb, 0.05, Math.sin(a) * rb);
    const t = new THREE.Vector3(Math.cos(a + 0.4) * 0.07, top, Math.sin(a + 0.4) * 0.07);
    const dir = b.clone().sub(t);
    const len = dir.length() + 0.14;
    const mid = b.clone().add(t).multiplyScalar(0.5);
    addLog(kit, len, 0.07 + rand() * 0.022, along(mid, dir, rand() * TAU), {
      seed: 300 + i, char: 0.62, ember: 0.9, bark: '#6e5644', barkDark: '#33261c', wood: '#d9a86a', seg: kit.seg(6, 8, 10), rings: kit.seg(2, 4, 6),
    });
  }
  // два полена поперёк у основания (тлеют)
  for (const [a, l] of [[0.9, 0.95], [2.7, 0.85]]) {
    addLog(kit, l, 0.075, T(Math.cos(a) * 0.12, 0.085, Math.sin(a) * 0.12, 0, -a, 0), { seed: 320 + l * 10, char: 1, ember: 1.6, bark: '#4a3424', barkDark: '#241810', seg: kit.seg(6, 8, 10) });
  }
  kit.pop();

  // пламя, искры, дым, свет
  // языки пламени: большой центральный выше шалаша, пара боковых, низкие у основания между поленьями
  const flames = [
    { x: 0, y: 0.05, z: 0, w: 0.46, h: 1.75, b: 0.9, seed: 0.11 },
    { x: 0.16, y: 0.05, z: 0.06, w: 0.3, h: 1.3, b: 0.75, seed: 0.37 },
    { x: -0.15, y: 0.05, z: 0.09, w: 0.3, h: 1.4, b: 0.8, seed: 0.59 },
    { x: 0.03, y: 0.05, z: -0.16, w: 0.28, h: 1.25, b: 0.75, seed: 0.83 },
    { x: 0.12, y: 0.05, z: -0.1, w: 0.22, h: 1.05, b: 0.85, seed: 0.66 },
    { x: -0.04, y: 0.04, z: -0.03, w: 0.32, h: 0.85, b: 1.4, seed: 0.23 },
    { x: 0.36, y: 0.03, z: -0.18, w: 0.16, h: 0.5, b: 1.0, seed: 0.71 },
    { x: -0.3, y: 0.03, z: -0.26, w: 0.15, h: 0.45, b: 1.0, seed: 0.47 },
    { x: -0.12, y: 0.03, z: 0.36, w: 0.16, h: 0.55, b: 1.0, seed: 0.93 },
    { x: 0.3, y: 0.03, z: 0.24, w: 0.13, h: 0.4, b: 1.0, seed: 0.05 },
  ];
  const fire = buildFlames(flames, ctx.level === 0 ? 1.45 : 1.75);
  fire.position.set(cx, y0, cz);
  const parts = buildParticles([
    { x: 0, y: 0.25, z: 0, sparks: kit.seg(16, 26, 32) },
    { x: 0, y: 0.5, z: 0, smoke: kit.seg(8, 14, 18), type: 1, life: 6.5, size: 0.55, spread: 0.35 },
  ], 9);
  parts.position.set(cx, y0, cz);
  // затухание мягче физического (decay 1.3): вблизи огня земля и камни не выгорают в белое,
  // а свет при этом дотягивается до брёвен-скамеек
  // свет - внутри шалаша, на уровне языков: наружные бока поленьев остаются в своей тени
  const light = new THREE.PointLight('#ff9448', 5.5, 24, 1.3);
  light.position.set(cx, y0 + 0.62, cz);
  kit.cur.extra.push({ obj: fire, lod: 'near' }, { obj: parts, lod: 'detail' }, { obj: light, lod: 'always' });
  anim.push((dt, t) => {
    light.intensity = 5.5 + Math.sin(t * 11) * 0.7 + Math.sin(t * 27.3) * 0.45 + Math.sin(t * 5.1) * 0.4;
  });
  void root;
  col.addCircle(cx, cz, 1.55, y0 - 1, y0 + 1.6);
  ctx.exclude(cx, cz, 2.0);
}

// ---------- брёвна-скамейки, лавки, пни ----------

function benches(ctx) {
  const { kit, col } = ctx;
  const { x: cx, z: cz } = CAMPFIRE;
  kit.group('hub');
  const rand = mulberry32(12);
  // восемь брёвен по диагоналям (положения держим: на них стоят кружки и фонари WS3)
  let i = 0;
  for (let k = 0; k < 4; k++) {
    for (const off of [-0.34, 0.34]) {
      const a = Math.PI / 4 + (k * Math.PI) / 2 + off;
      const R = 4.7;
      const px = cx + Math.cos(a) * R, pz = cz + Math.sin(a) * R;
      const ry = -a - Math.PI / 2;
      const gy = heightAt(px, pz);
      const len = 1.9 + (rand() - 0.5) * 0.1;
      kit.setLayer('solid');
      addLog(kit, len, 0.26, TY(px, gy + 0.266, pz, ry, rand() * 0.2 - 0.1), {
        seed: 400 + i, flat: 0.1, bark: '#83552f', barkDark: '#4c3019', wood: '#dfb27a', seg: kit.seg(9, 12, 16), rings: kit.seg(3, 6, 8),
      });
      // сучки и пятна мха
      kit.setLayer('detail');
      const nk = kit.q > 0 ? 1 + (i % 2) : 0;
      for (let j = 0; j < nk; j++) {
        const t = (rand() - 0.5) * len * 0.7, an = (0.5 + rand()) * (rand() < 0.5 ? 1 : -1) + Math.PI / 2;
        const m = T(px, gy + 0.266, pz, 0, ry, 0).multiply(T(t, Math.sin(an) * 0.24, Math.cos(an) * 0.24, an - Math.PI / 2, 0, 0));
        kit.cyl(0.035, 0.05, 0.12, '#6b4426', SURF.bark, m.multiply(T(0, 0.04, 0)), { seg: kit.seg(5, 6, 8) });
        kit.cyl(0.035, 0.035, 0.005, '#d4a46c', SURF.rings, m.clone().multiply(T(0, 0.062, 0)), { seg: kit.seg(5, 6, 8), uv: (x, y, z) => [x, z] });
      }
      col.addBox(px, pz, len / 2, 0.26, ry, gy - 0.3, gy + 0.5);
      ctx.exclude(px, pz, 1.1);
      i++;
    }
  }

  // лавки из досок: на ножках-козлах, смотрят на костёр
  for (const [a, R] of [[-1.05, 8.6], [-1.96, 8.6]]) {
    const px = cx + Math.cos(a) * R, pz = cz + Math.sin(a) * R;
    plankBench(ctx, px, pz, -a - Math.PI / 2, 600 + Math.round(a * 10));
  }

  // пни-сиденья
  for (const [px, pz, s] of [[-2.2, -7.3, 1], [7.6, -0.7, 0.9]]) {
    stumpSeat(ctx, px, pz, s, Math.round(px * 7));
  }
}

export function plankBench(ctx, px, pz, ry, seed, { len = 1.7, color = '#b07a48', y0 = null } = {}) {
  const { kit, col } = ctx;
  const gy = y0 ?? heightAt(px, pz);
  const rand = mulberry32(seed);
  const H = 0.44;
  kit.push(T(px, gy, pz, 0, ry, 0));
  kit.setLayer('solid');
  // сиденье: три доски с зазорами
  for (let j = 0; j < 3; j++) {
    const c = new THREE.Color(color).multiplyScalar(0.88 + rand() * 0.22);
    kit.box(len + (rand() - 0.5) * 0.04, 0.045, 0.11, c, SURF.wood, T((rand() - 0.5) * 0.03, H - 0.022, -0.12 + j * 0.12, 0, (rand() - 0.5) * 0.01, 0), { bevel: 0.012 });
  }
  // спинка на двух стойках
  for (let j = 0; j < 2; j++) {
    const c = new THREE.Color(color).multiplyScalar(0.85 + rand() * 0.2);
    kit.box(len, 0.1, 0.035, c, SURF.wood, T(0, H + 0.2 + j * 0.15, 0.2 + j * 0.02, -0.12, 0, 0), { bevel: 0.01 });
  }
  const dark = new THREE.Color(color).multiplyScalar(0.7);
  for (const sx of [-1, 1]) {
    const x = sx * (len / 2 - 0.16);
    kit.box(0.06, H - 0.045, 0.06, dark, SURF.wood, T(x, (H - 0.045) / 2, -0.12, 0, 0, 0), { bevel: 0.01 });
    kit.box(0.06, H + 0.38, 0.06, dark, SURF.wood, T(x, (H + 0.38) / 2, 0.17, -0.12, 0, 0), { bevel: 0.01 });
    kit.box(0.05, 0.05, 0.4, dark, SURF.wood, T(x, 0.12, 0.02, 0, 0, 0), { bevel: 0.008 });
    kit.box(0.05, 0.04, 0.42, dark, SURF.wood, T(x, H - 0.065, 0.02, 0, 0, 0), { bevel: 0.008 });
    // подлокотник
    kit.box(0.07, 0.035, 0.46, color, SURF.wood, T(x + sx * 0.02, H + 0.2, 0.0, 0, 0, 0), { bevel: 0.01 });
    kit.box(0.045, 0.2, 0.045, dark, SURF.wood, T(x + sx * 0.02, H + 0.1, -0.2, 0, 0, 0), { bevel: 0.008 });
  }
  // шляпки гвоздей
  kit.setLayer('detail');
  if (ctx.level > 0) {
    for (const sx of [-1, 1]) for (let j = 0; j < 3; j++) kit.cyl(0.008, 0.008, 0.006, '#3a3836', SURF.iron, T(sx * (len / 2 - 0.16), H + 0.002, -0.12 + j * 0.12), { seg: 5 });
  }
  kit.pop();
  col.addBox(px, pz, len / 2, 0.2, ry, gy - 0.3, gy + H);
  const bx = px + Math.sin(ry) * 0.19, bz = pz + Math.cos(ry) * 0.19;
  col.addBox(bx, bz, len / 2, 0.05, ry, gy + H, gy + H + 0.42);
  ctx.exclude(px, pz, 1.0);
}

export function stumpSeat(ctx, px, pz, s, seed) {
  const { kit, col } = ctx;
  const gy = heightAt(px, pz);
  const rand = mulberry32(seed);
  const r = 0.3 * s, h = 0.42 * s;
  const seg = kit.seg(9, 12, 16);
  const prof = [[r * 1.35, -0.04], [r * 1.12, 0.03], [r * 1.02, 0.1], [r, h * 0.6], [r * 0.98, h]];
  const g = lathe(prof, seg);
  const P = g.getAttribute('position');
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i), a = Math.atan2(x, z);
    const k = 1 + 0.06 * Math.sin(a * 5 + seed) + 0.03 * Math.sin(a * 11);
    P.setXYZ(i, x * k, y, z * k);
  }
  g.computeVertexNormals();
  kit.setLayer('solid');
  const ry = rand() * TAU;
  kit.add(g, (x, y, z, nx, ny, nz, out) => {
    out.set('#6e4a2c').multiplyScalar(0.85 + hash2(Math.round(Math.atan2(x, z) * 6), seed, 1) * 0.3);
    if (y < 0.06) out.lerp(new THREE.Color('#4d5a2a'), 0.4);
  }, SURF.bark, T(px, gy, pz, 0, ry, 0), { uv: (x, y, z) => [y, Math.atan2(x, z) * r] });
  const top = new THREE.CircleGeometry(r * 0.98, seg, 0, TAU).rotateX(-Math.PI / 2);
  const TP = top.getAttribute('position');
  for (let i = 0; i < TP.count; i++) {
    const x = TP.getX(i), z = TP.getZ(i), a = Math.atan2(x, z);
    const k = 1 + 0.06 * Math.sin(a * 5 + seed) + 0.03 * Math.sin(a * 11);
    TP.setXYZ(i, x * k, 0, z * k);
  }
  kit.add(top, (x, y, z, nx, ny, nz, out) => {
    out.set(Math.hypot(x, z) > r * 0.9 ? '#5a3b22' : '#d8ad74');
  }, SURF.rings, T(px, gy + h, pz, 0, ry, 0), { uv: (x, y, z) => [x, z] });
  col.addBox(px, pz, r * 0.95, r * 0.95, 0, gy - 0.3, gy + h);
  ctx.exclude(px, pz, r + 0.3);
}

// ---------- указатель ----------

function signpost(ctx) {
  const { kit, col } = ctx;
  const px = -5.2, pz = 8.2;
  const y = heightAt(px, pz);
  kit.group('hub');
  kit.setLayer('solid');
  const postC = '#7d5534';
  kit.box(0.17, 3.1, 0.17, postC, SURF.wood, T(px, y + 1.55, pz), { bevel: 0.025 });
  // шапка-крыша на столбе
  kit.add(new THREE.ConeGeometry(0.2, 0.2, 4, 1), '#5a3a24', SURF.wood, T(px, y + 3.2, pz, 0, Math.PI / 4, 0));
  kit.box(0.24, 0.05, 0.24, '#5a3a24', SURF.wood, T(px, y + 3.08, pz), { bevel: 0.01 });
  kit.sphere(0.035, '#5a3a24', SURF.wood, T(px, y + 3.33, pz), { seg: 6 });
  col.addCircle(px, pz, 0.25);

  const items = [
    { t: 'Зима', dx: -6, dz: -45 }, { t: 'Осень', dx: 45, dz: 6 }, { t: 'Дождь', dx: 8, dz: 45 },
    { t: 'Каньон и арена', dx: -40, dz: -4 }, { t: 'Озеро', dx: -22, dz: 18 }, { t: 'Беседка', dx: 12, dz: -6 },
  ];
  const tones = ['#c99a5e', '#d4a66a', '#c08e55', '#cfa064', '#c4945a', '#d0a265'];
  items.forEach((it, i) => {
    const th = Math.atan2(-(it.dz - pz), it.dx - px);
    // длинная надпись - длиннее доска (и шире участок атласа, чтобы буквы не сжимались)
    const long = it.t.length > 8;
    const L = long ? 2.1 : 1.5, H = 0.3, D = 0.05, tip = 0.22, PW = long ? 704 : 512;
    const front = woodLabel(`${it.t}  →`, PW, 96, { bg: tones[i], arrow: 1 });
    const back = woodLabel(`←  ${it.t}`, PW, 96, { bg: tones[i], arrow: -1 });
    const y0 = y + 2.78 - i * 0.37;
    // контур стрелки (локально: x - вдоль стрелки от столба, y - вверх)
    const x0 = 0.1, x1 = x0 + L;
    const outline = [[x0, -H / 2], [x1 - tip, -H / 2], [x1, 0], [x1 - tip, H / 2], [x0, H / 2]];
    const frame = T(px, y0, pz, 0, th, (hash2(i, 9) - 0.5) * 0.04);
    kit.push(frame);
    kit.setLayer('solid');
    // торцы-грани доски
    const side = slabGeo(outline, D, 0);
    kit.add(side, tones[i], SURF.wood, null, { uv: (x, yy, z) => [x, yy] });
    // лицевые стороны с надписями (чуть выступают)
    const face = (sgn, r) => {
      const s = new THREE.ShapeGeometry(new THREE.Shape(outline.map(([a, b]) => new THREE.Vector2(a, b))));
      s.deleteAttribute('uv');
      if (sgn < 0) s.scale(1, 1, -1).rotateY(0);
      const P = s.getAttribute('position');
      for (let k = 0; k < P.count; k++) P.setZ(k, sgn * (D / 2 + 0.002));
      if (sgn < 0) { const I = s.index.array; for (let k = 0; k < I.length; k += 3) { const t = I[k]; I[k] = I[k + 1]; I[k + 1] = t; } }
      s.computeVertexNormals();
      kit.add(s, '#ffffff', SURF.label, null, {
        uv: (x, yy) => {
          const u = (x - x0) / L, v = (yy + H / 2) / H;
          const uu = sgn > 0 ? u : 1 - u;
          return [r[0] + (r[2] - r[0]) * uu, r[1] + (r[3] - r[1]) * v];
        },
      });
    };
    face(1, front);
    face(-1, back);
    // гвозди
    kit.setLayer('detail');
    if (ctx.level > 0) for (const sz of [-1, 1]) kit.cyl(0.012, 0.012, 0.012, '#2f2b28', SURF.iron, T(0.05 + 0.08, 0, sz * (D / 2 + 0.004), Math.PI / 2, 0, 0), { seg: 6 });
    kit.pop();
  });
  // камни у основания
  kit.setLayer('detail');
  const rand = mulberry32(55);
  for (let i = 0; i < 6; i++) {
    const a = (i / 6) * TAU + rand() * 0.5, r = 0.2 + rand() * 0.12;
    kit.add(stoneGeo(0.07 + rand() * 0.06, 500 + i, kit.seg(0, 1, 1), 0.4, 0.7), stoneColor('#8e8b84', 70 + i), SURF.mossy, T(px + Math.cos(a) * r, y - 0.02, pz + Math.sin(a) * r, 0, rand() * 3, 0));
  }
  ctx.exclude(px, pz, 0.35);
}

// ---------- поленница, колода с топором, бочки, тележка, почтовый ящик ----------

function hubProps(ctx) {
  const { kit, col } = ctx;
  kit.group('hub');
  const rand = mulberry32(77);

  // поленница под навесиком
  {
    const px = -8.2, pz = -6.4, ry = 0.75;
    const gy = heightAt(px, pz);
    kit.push(T(px, gy, pz, 0, ry, 0));
    kit.setLayer('solid');
    const W = 1.8, H = 1.0;
    // каркас: стойки, крыша из досок
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) kit.box(0.08, H + 0.35 + (sz < 0 ? 0.15 : 0), 0.08, '#6c4a2e', SURF.wood, T(sx * (W / 2 + 0.05), (H + 0.35 + (sz < 0 ? 0.15 : 0)) / 2, sz * 0.32), { bevel: 0.012 });
    for (let j = 0; j < 5; j++) kit.box(0.4, 0.03, W + 0.4, new THREE.Color('#8a603a').multiplyScalar(0.85 + rand() * 0.25), SURF.wood, T(0, H + 0.44 - j * 0.0 + 0.0, -0.32 + j * 0.16, -0.0, Math.PI / 2, 0).multiply(T(0, 0, 0, 0.0, 0, 0.2)), { bevel: 0.008 });
    kit.box(W + 0.1, 0.05, 0.06, '#5d3f27', SURF.wood, T(0, 0.04, 0.0), { bevel: 0.01 });
    // поленья: торцами наружу, рядами
    kit.setLayer('detail');
    const rows = kit.seg(2, 4, 5), cols = kit.seg(6, 10, 12);
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const rr = 0.065 + rand() * 0.025;
        const x = -W / 2 + 0.09 + (c + (r % 2) * 0.5) * ((W - 0.18) / cols);
        if (x > W / 2 - 0.08) continue;
        const yy = 0.12 + r * 0.17 + rand() * 0.02;
        addLog(kit, 0.55 + rand() * 0.08, rr, TY(x, yy, (rand() - 0.5) * 0.06, Math.PI / 2 + (rand() - 0.5) * 0.08, rand() * TAU), {
          seed: 700 + r * 20 + c, seg: kit.seg(5, 6, 7), rings: 1, bark: '#6f4c2e', barkDark: '#3f2a18', wood: '#e2bc84',
        });
      }
    }
    kit.pop();
    col.addBox(px, pz, W / 2 + 0.1, 0.4, ry, gy - 0.3, gy + H + 0.5);
    ctx.exclude(px, pz, 1.3);

    // колода с топором
    const kx = -6.3, kz = -5.6;
    stumpSeat(ctx, kx, kz, 0.95, 901);
    const ky = heightAt(kx, kz) + 0.4;
    kit.setLayer('detail');
    kit.push(T(kx, ky, kz, 0, 0.6, 0));
    kit.push(T(0.02, 0.0, 0, 0, 0, -0.5));
    kit.cyl(0.018, 0.022, 0.6, '#b98a55', SURF.wood, T(0, 0.3, 0), { seg: 6 });
    kit.box(0.035, 0.11, 0.17, '#55595e', SURF.metal, T(0, 0.0, 0.05), { bevel: 0.008 });
    kit.box(0.012, 0.13, 0.05, '#c9ccd0', SURF.metal, T(0, -0.005, 0.15), { bevel: 0.004 });
    kit.pop();
    kit.pop();
    // пара расколотых поленьев рядом
    addLog(kit, 0.5, 0.07, T(kx + 0.55, heightAt(kx, kz) + 0.07, kz + 0.1, 0, 0.3, 0), { seed: 911, seg: kit.seg(5, 6, 8), rings: 1 });
  }

  // бочки у ящиков
  for (const [bx, bz, s] of [[-10.0, -2.1, 1], [-10.6, -3.25, 0.9]]) barrel(ctx, bx, bz, s, Math.round(bx * 10));

  // тележка с дровами
  cart(ctx, -10.1, -6.0, 2.2, 31);

  // почтовый ящик у тропы на юг
  mailbox(ctx, 5.1, 14.7, Math.PI * 0.55, '#c8423a');
}

export function barrel(ctx, px, pz, s, seed) {
  const { kit, col } = ctx;
  const gy = heightAt(px, pz);
  const rand = mulberry32(seed);
  const r = 0.3 * s, h = 0.82 * s;
  const seg = kit.seg(10, 14, 20);
  const prof = [];
  for (let i = 0; i <= 8; i++) { const t = i / 8; prof.push([r * (0.86 + 0.14 * Math.sin(t * Math.PI)), t * h]); }
  const g = lathe(prof, seg);
  const wood = new THREE.Color('#9a6638');
  kit.setLayer('solid');
  kit.add(g, (x, y, z, nx, ny, nz, out) => {
    const a = Math.atan2(x, z);
    const stave = Math.floor((a / TAU + 0.5) * 14);
    out.copy(wood).multiplyScalar(0.82 + hash2(stave, seed, 3) * 0.3);
  }, SURF.wood, T(px, gy, pz, 0, rand() * TAU, 0), { uv: (x, y, z) => [y, Math.atan2(x, z) * r] });
  kit.setLayer('detail');
  for (const t of [0.12, 0.88, 0.32, 0.68]) {
    const rr = r * (0.86 + 0.14 * Math.sin(t * Math.PI)) + 0.006;
    kit.cyl(rr, rr, 0.035 * s, '#3c3a38', SURF.iron, T(px, gy + t * h, pz), { seg, open: true });
  }
  kit.add(new THREE.CircleGeometry(r * 0.86, seg, 0, TAU).rotateX(-Math.PI / 2), '#8a5a32', SURF.planks, T(px, gy + h - 0.02, pz), { uv: (x, y, z) => [x * 2, z * 2] });
  col.addCircle(px, pz, r, gy - 0.3, gy + h);
  ctx.exclude(px, pz, r + 0.2);
}

function cart(ctx, px, pz, ry, seed) {
  const { kit, col } = ctx;
  const gy = heightAt(px, pz);
  const rand = mulberry32(seed);
  kit.push(T(px, gy, pz, 0, ry, 0));
  kit.setLayer('solid');
  const L = 1.2, W = 0.7, Hb = 0.42;
  // кузов из досок
  for (let j = 0; j < 4; j++) kit.box(L, 0.03, W / 4 - 0.01, new THREE.Color('#a27346').multiplyScalar(0.85 + rand() * 0.25), SURF.wood, T(0, Hb, -W / 2 + W / 8 + j * W / 4), { bevel: 0.008 });
  for (const sz of [-1, 1]) for (let j = 0; j < 2; j++) kit.box(L, 0.1, 0.03, '#8d6038', SURF.wood, T(0, Hb + 0.07 + j * 0.12, sz * W / 2), { bevel: 0.008 });
  for (const sx of [-1, 1]) for (let j = 0; j < 2; j++) kit.box(0.03, 0.1, W, '#8d6038', SURF.wood, T(sx * L / 2, Hb + 0.07 + j * 0.12, 0), { bevel: 0.008 });
  // ручки и ножка
  for (const sz of [-1, 1]) {
    kit.box(0.9, 0.045, 0.045, '#7a5232', SURF.wood, T(-L / 2 - 0.35, Hb - 0.02, sz * 0.25, 0, 0, -0.18), { bevel: 0.01 });
    kit.box(0.045, Hb, 0.045, '#7a5232', SURF.wood, T(-L / 2 + 0.1, Hb / 2, sz * 0.25), { bevel: 0.01 });
  }
  // колесо: обод, спицы, ступица
  for (const sz of [-1, 1]) {
    kit.push(T(L / 2 - 0.25, 0.32, sz * (W / 2 + 0.06), Math.PI / 2, 0, 0));
    kit.add(new THREE.TorusGeometry(0.3, 0.035, kit.seg(4, 6, 6), kit.seg(12, 16, 20)).rotateX(Math.PI / 2), '#6a4528', SURF.wood, null);
    kit.add(new THREE.TorusGeometry(0.315, 0.012, 4, kit.seg(12, 16, 20)).rotateX(Math.PI / 2), '#3a3836', SURF.iron, null, { layer: 'detail' });
    for (let k = 0; k < 6; k++) kit.box(0.04, 0.025, 0.56, '#7a5232', SURF.wood, T(0, 0, 0, 0, (k / 6) * Math.PI, 0), { bevel: 0.006 });
    kit.cyl(0.06, 0.06, 0.1, '#5a3a22', SURF.wood, T(0, 0, 0), { seg: 8 });
    kit.pop();
  }
  // дрова в кузове
  kit.setLayer('detail');
  for (let j = 0; j < kit.seg(3, 6, 8); j++) {
    addLog(kit, 0.6 + rand() * 0.15, 0.06 + rand() * 0.02, TY((rand() - 0.5) * 0.3, Hb + 0.09 + Math.floor(j / 3) * 0.12, -0.2 + (j % 3) * 0.2, (rand() - 0.5) * 0.3, rand() * TAU), { seed: 800 + j, seg: kit.seg(5, 6, 7), rings: 1 });
  }
  kit.pop();
  col.addBox(px, pz, 0.85, 0.45, ry, gy - 0.3, gy + Hb + 0.2);
  ctx.exclude(px, pz, 1.1);
}

export function mailbox(ctx, px, pz, ry, color = '#c8423a', flag = '#f2c84b') {
  const { kit, col } = ctx;
  const gy = heightAt(px, pz);
  kit.push(T(px, gy, pz, 0, ry, 0));
  kit.setLayer('solid');
  kit.box(0.09, 1.0, 0.09, '#7a5232', SURF.wood, T(0, 0.5, 0), { bevel: 0.015 });
  kit.box(0.24, 0.04, 0.5, '#6a4628', SURF.wood, T(0, 1.0, 0), { bevel: 0.01 });
  // корпус: короб + полукруглая крыша
  kit.box(0.24, 0.16, 0.46, color, SURF.paint, T(0, 1.1, 0), { bevel: 0.01 });
  kit.add(new THREE.CylinderGeometry(0.12, 0.12, 0.46, kit.seg(8, 10, 14), 1, false, -Math.PI / 2, Math.PI), color, SURF.paint, T(0, 1.18, 0, Math.PI / 2, 0, 0));
  kit.setLayer('detail');
  kit.box(0.2, 0.2, 0.01, new THREE.Color(color).multiplyScalar(0.8), SURF.paint, T(0, 1.15, 0.236), { bevel: 0.004 });
  kit.box(0.04, 0.025, 0.02, '#d9d4c8', SURF.metal, T(0, 1.2, 0.25), { bevel: 0.004 });
  // флажок
  kit.box(0.015, 0.22, 0.03, flag, SURF.paint, T(0.13, 1.22, -0.08), { bevel: 0.004 });
  kit.box(0.012, 0.07, 0.11, flag, SURF.paint, T(0.13, 1.3, -0.02), { bevel: 0.004 });
  kit.pop();
  col.addCircle(px, pz, 0.12, gy - 0.3, gy + 1.3);
  ctx.exclude(px, pz, 0.3);
}

// ---------- большое дерево: гирлянды, качели, скворечник ----------

function heroTree(ctx) {
  const { kit, col } = ctx;
  const x = -17, z = -21;
  const y = heightAt(x, z);
  kit.group('tree', { cx: x, cz: z, r: 8 });
  const tree = buildHeroTree(2.6, '#7cc650');
  tree.position.set(x, y - 0.1, z);
  tree.rotation.y = 0.6;
  kit.cur.extra.push({ obj: tree, lod: 'always' });
  col.addCircle(x, z, 1.05);

  // толстая ветка для качелей: от ствола наружу, к кроне
  const bark = new THREE.Color('#6b4128'), barkD = new THREE.Color('#3f2616');
  const bpts = [
    new THREE.Vector3(x + 0.15, y + 3.9, z + 0.05), new THREE.Vector3(x + 1.4, y + 4.55, z + 0.25),
    new THREE.Vector3(x + 2.8, y + 4.85, z + 0.45), new THREE.Vector3(x + 4.0, y + 5.05, z + 0.55), new THREE.Vector3(x + 5.0, y + 5.55, z + 0.6),
  ];
  kit.setLayer('solid');
  const br = tube(bpts, (t) => 0.3 * (1 - t) + 0.07 * t, kit.seg(6, 8, 10), true);
  kit.add(br, (px, py, pz, nx, ny, nz, out) => out.copy(bark).lerp(barkD, 0.3 + 0.4 * hash2(Math.round(px * 8), Math.round(py * 8), 4)), SURF.bark, null, { bake: false });
  // точка подвеса на ветке
  const sx = -13.6, sz = -20.5;
  const hang = (px) => {
    // y ветки над точкой px (интерполяция по ломаной)
    for (let i = 0; i < bpts.length - 1; i++) {
      const a = bpts[i], b = bpts[i + 1];
      if (px >= a.x && px <= b.x) { const t = (px - a.x) / (b.x - a.x); return a.y + (b.y - a.y) * t - 0.16 * (1 - (px - x) / 6); }
    }
    return y + 5;
  };
  const top = y + 1.04;
  // сиденье: доска (верх на top - на неё сажает WS3) и верёвки с узлами
  kit.box(1.0, 0.06, 0.32, '#a87644', SURF.wood, T(sx, top - 0.03, sz), { bevel: 0.012 });
  kit.setLayer('detail');
  for (const ox of [-0.42, 0.42]) {
    const hx = sx + ox * 0.6, hy = hang(hx);
    for (const oz of [-0.11, 0.11]) {
      const pts = [new THREE.Vector3(hx, hy, sz + oz * 0.3), new THREE.Vector3(sx + ox, top + 0.06, sz + oz)];
      kit.add(tube(pts, 0.016, 5, false), '#c9b38a', SURF.fabric, null, { uv: (a, b, c) => [b * 3, a * 3] });
    }
    kit.sphere(0.035, '#b8a078', SURF.fabric, T(sx + ox, top + 0.05, sz), { seg: 6 });
    // обмотка вокруг ветки
    kit.add(new THREE.TorusGeometry(0.14 - Math.abs(hx - x) * 0.012, 0.022, 4, 10), '#c9b38a', SURF.fabric, T(hx, hy + 0.05, sz + 0.05, 0, Math.PI / 2, 0));
  }

  // гирлянды: провисающие провода между точками вокруг кроны, тёплые лампочки
  const rand = mulberry32(21);
  const bulbs = [];
  const strands = [
    { r: 3.6, y0: 4.9, a0: 0.2, n: 7 },
    { r: 4.4, y0: 5.8, a0: 1.0, n: 8 },
  ];
  const wire = '#2c2620';
  for (const s of strands) {
    const anchors = [];
    for (let i = 0; i <= s.n; i++) {
      const a = s.a0 + (i / s.n) * TAU * 0.92;
      anchors.push(new THREE.Vector3(x + Math.cos(a) * s.r * (0.9 + rand() * 0.2), y + s.y0 + (rand() - 0.5) * 0.5, z + Math.sin(a) * s.r * (0.9 + rand() * 0.2)));
    }
    for (let i = 0; i < anchors.length - 1; i++) {
      const pts = sagPoints(anchors[i], anchors[i + 1], 0.45 + rand() * 0.2, kit.seg(5, 7, 9));
      kit.add(tube(pts, 0.008, 3, false), wire, SURF.plain, null, { bake: false });
      const nb = kit.seg(2, 4, 5);
      for (let k = 1; k <= nb; k++) bulbs.push(pts[Math.round((k / (nb + 1)) * (pts.length - 1))]);
    }
  }
  const warm = ['#ffd38a', '#ffc070', '#ffe2a8', '#ffb35c'];
  const bulbGeo = kit.q > 1 ? new THREE.SphereGeometry(0.055, 7, 5) : new THREE.IcosahedronGeometry(0.055, 0);
  bulbs.forEach((p, i) => {
    kit.add(bulbGeo, warm[i % 4], glow(3.2, PAT.plain), T(p.x, p.y - 0.06, p.z, 0, 0, 0, 1, 1.25, 1), { bake: false });
    if (kit.q > 1) kit.cyl(0.02, 0.02, 0.04, '#3a3530', SURF.plain, T(p.x, p.y - 0.005, p.z), { seg: 5, bake: false });
  });

  // скворечник на стволе (смотрит на хаб)
  {
    const a = 0.75; // направление от центра ствола
    const bx = x + Math.cos(a) * 0.62, bz = z + Math.sin(a) * 0.62, by = y + 3.1;
    kit.setLayer('solid');
    kit.push(T(bx, by, bz, 0, -a + Math.PI / 2, 0));
    kit.box(0.24, 0.3, 0.22, '#b88a58', SURF.wood, T(0, 0, 0.05), { bevel: 0.01 });
    kit.add(slabGeo([[-0.17, 0], [0.17, 0], [0, 0.12]], 0.3, 0), '#a0503a', SURF.shingle, T(0, 0.15, 0.05, 0, Math.PI / 2, 0).multiply(T(0, 0, 0, 0, 0, 0, 1, 1, 1)), { uv: (px, py, pz) => [pz, py] });
    kit.add(new THREE.BoxGeometry(0.2, 0.025, 0.34), '#8f4433', SURF.shingle, T(-0.08, 0.2, 0.05, 0, 0, 0.62));
    kit.add(new THREE.BoxGeometry(0.2, 0.025, 0.34), '#8f4433', SURF.shingle, T(0.08, 0.2, 0.05, 0, 0, -0.62));
    kit.setLayer('detail');
    kit.cyl(0.045, 0.045, 0.01, '#1a120c', SURF.plain, T(0, 0.03, 0.166, Math.PI / 2, 0, 0), { seg: 10 });
    kit.cyl(0.01, 0.01, 0.07, '#7a5232', SURF.wood, T(0, -0.06, 0.19, Math.PI / 2, 0, 0), { seg: 5 });
    kit.box(0.06, 0.4, 0.04, '#6a4628', SURF.wood, T(0, -0.05, -0.08), { bevel: 0.008 });
    kit.pop();
  }
  ctx.exclude(sx, sz, 0.8);
}

// ---------- тропы: фонари и заборчики ----------

const CELL = 48;
function pathGroup(kit, x, z) {
  const ix = Math.floor(x / CELL), iz = Math.floor(z / CELL);
  return kit.group(`path:${ix},${iz}`, { cx: (ix + 0.5) * CELL, cz: (iz + 0.5) * CELL, r: CELL * 0.72, detailDist: 22 });
}

// snow - зимний вариант: на тумбе, камнях и крыше фонаря лежит снег
export function lantern(ctx, lx, lz, { h = 2.25, seed = 1, base = true, snow = false } = {}) {
  const { kit, col } = ctx;
  const y = heightAt(lx, lz);
  const rand = mulberry32(seed);
  const iron = '#2e3034', ironL = '#44474d';
  const layer0 = kit.layer;
  kit.push(T(lx, y, lz, 0, rand() * TAU, 0));
  // основание: тумба из камня и пара камней
  if (base) {
    kit.box(0.32, 0.26, 0.32, '#8d8a84', SURF.stone, T(0, 0.11, 0), { bevel: 0.04 });
    kit.box(0.22, 0.06, 0.22, '#77746e', SURF.stone, T(0, 0.27, 0), { bevel: 0.015, shadow: false });
    if (kit.q > 0) {
      kit.setLayer('detail');
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU + rand(), r = 0.3 + rand() * 0.08;
        const sc = stoneColor('#8e8b84', seed + i);
        kit.add(stoneGeo(0.07 + rand() * 0.05, seed * 7 + i, 0, 0.4, 0.7), snow ? snowCap(sc) : sc, snow ? SURF.stone : SURF.mossy, T(Math.cos(a) * r, -0.02, Math.sin(a) * r));
      }
      kit.setLayer(layer0);
    }
    if (snow) kit.box(0.3, 0.05, 0.3, '#f2f6fb', SURF.snow, T(0, 0.255, 0), { bevel: 0.02, shadow: false, bake: false });
  }
  // кованый столб: квадратный с фаской, кольца-воротники, завитки под фонарём
  kit.box(0.065, h - 0.3, 0.065, iron, SURF.iron, T(0, 0.3 + (h - 0.3) / 2, 0), { bevel: 0.012 });
  // фонарь: шестигранник - дно, рамка, стёкла, огонёк, крыша с навершием
  const hy = h;
  kit.cyl(0.13, 0.11, 0.05, iron, SURF.iron, T(0, hy, 0), { seg: 6, shadow: false });
  kit.cyl(0.14, 0.14, 0.32, '#ffd9a0', glow(1.0, PAT.glass), T(0, hy + 0.19, 0), { seg: 6, open: true, shadow: new THREE.CylinderGeometry(0.13, 0.13, 0.4, 6) });
  for (let i = 0; i < 6; i++) {
    const a = (i / 6) * TAU;
    kit.box(0.022, 0.34, 0.022, iron, SURF.iron, T(Math.cos(a) * 0.14, hy + 0.19, Math.sin(a) * 0.14, 0, -a, 0), { sharp: kit.q < 2, bevel: 0.004, shadow: false });
  }
  kit.add(new THREE.SphereGeometry(0.06, kit.seg(5, 5, 8), 4), '#ffe2b0', glow(4.2, PAT.plain, true), T(0, hy + 0.16, 0, 0, 0, 0, 1, 1.5, 1), { shadow: false });
  kit.add(new THREE.ConeGeometry(0.21, 0.15, 6, 1), iron, SURF.iron, T(0, hy + 0.43, 0));
  if (snow) kit.add(new THREE.ConeGeometry(0.2, 0.1, 6, 1), '#f2f6fb', SURF.snow, T(0, hy + 0.47, 0), { shadow: false, bake: false });
  kit.cyl(0.15, 0.15, 0.025, ironL, SURF.iron, T(0, hy + 0.36, 0), { seg: 6, shadow: false });
  // мелочь - только вблизи
  kit.setLayer('detail');
  kit.cyl(0.055, 0.06, 0.06, ironL, SURF.iron, T(0, 0.33, 0), { seg: kit.seg(6, 6, 8) });
  kit.cyl(0.05, 0.045, 0.05, ironL, SURF.iron, T(0, h - 0.12, 0), { seg: kit.seg(6, 6, 8) });
  if (kit.q > 0) {
    for (const s of [-1, 1]) {
      const pts = [];
      for (let i = 0; i <= 8; i++) {
        const t = i / 8, a = t * Math.PI * 1.6;
        pts.push(new THREE.Vector3(s * (0.03 + Math.sin(a) * 0.09 * (1 - t * 0.5)), h - 0.5 + t * 0.32 + Math.cos(a) * 0.04, 0));
      }
      kit.add(tube(pts, 0.011, kit.seg(3, 3, 4), true), iron, SURF.iron, null);
    }
  }
  kit.cyl(0.02, 0.025, 0.06, '#f4ead8', SURF.plain, T(0, hy + 0.06, 0), { seg: 5 });
  kit.sphere(0.03, ironL, SURF.iron, T(0, hy + 0.53, 0), { seg: 6 });
  if (kit.q > 1) kit.add(new THREE.TorusGeometry(0.03, 0.008, 4, 8), iron, SURF.iron, T(0, hy + 0.58, 0));
  kit.setLayer(layer0);
  kit.pop();
  col.addCircle(lx, lz, 0.17, y - 0.3, y + h + 0.6);
  ctx.exclude?.(lx, lz, 0.35);
}

// pts: точки линии забора [[x, z], ...]; столбы на точках, две жерди между ними.
// group - имя группы кита (по умолчанию - ячейка троп по положению столба).
export function fenceRun(ctx, pts, seed, group = null) {
  const { kit, col } = ctx;
  const rand = mulberry32(seed);
  for (let i = 0; i < pts.length; i++) {
    const [x, z] = pts[i];
    const y = heightAt(x, z);
    if (group) kit.group(group); else pathGroup(kit, x, z);
    kit.box(0.1, 1.0, 0.1, new THREE.Color('#7a5434').multiplyScalar(0.85 + rand() * 0.25), SURF.wood, T(x, y + 0.45, z, (rand() - 0.5) * 0.06, rand() * 3, (rand() - 0.5) * 0.06), { bevel: 0.02 });
    kit.add(new THREE.ConeGeometry(0.075, 0.08, 4), '#6a482c', SURF.wood, T(x, y + 0.98, z, 0, Math.PI / 4, 0));
    if (i === 0) continue;
    const [px, pz] = pts[i - 1];
    const py = heightAt(px, pz);
    const dx = x - px, dz = z - pz, len = Math.hypot(dx, dz);
    const ry = Math.atan2(-dz, dx);
    for (const hh of [0.42, 0.78]) {
      const mx = (x + px) / 2, mz = (z + pz) / 2, my = (y + py) / 2 + hh;
      const tilt = Math.atan2(y - py, len);
      addLog(kit, len + 0.12, 0.04 + rand() * 0.01, T(mx, my + (rand() - 0.5) * 0.04, mz, 0, ry, tilt), {
        seed: seed * 13 + i * 2 + hh * 10, seg: kit.seg(5, 6, 7), rings: 1, bark: '#8a6040', barkDark: '#5a3c24', wood: '#d3a874', caps: kit.q > 0,
      });
    }
    col.addBox((x + px) / 2, (z + pz) / 2, len / 2, 0.06, ry, Math.min(y, py) - 0.3, Math.max(y, py) + 0.85);
  }
}

export function buildPaths(ctx) {
  const { kit } = ctx;
  let n = 0;
  for (const path of PATHS) {
    if (!['to-winter', 'to-autumn', 'to-rain', 'to-arena', 'lake'].includes(path.id)) continue;
    const pts = samplePath(path, 1.5);
    let dist = 0, next = 11;
    for (let i = 1; i < pts.length; i++) {
      dist += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
      if (dist < next || dist > 120) continue;
      next += 15;
      const dx = pts[i][0] - pts[i - 1][0], dz = pts[i][1] - pts[i - 1][1];
      const l = Math.hypot(dx, dz) || 1;
      const side = n++ % 2 ? 1 : -1;
      const lx = pts[i][0] + (-dz / l) * 2.6 * side, lz = pts[i][1] + (dx / l) * 2.6 * side;
      pathGroup(kit, lx, lz);
      kit.setLayer('solid');
      lantern(ctx, lx, lz, { seed: 1000 + n, snow: zoneWeights(lx, lz).winter > 0.5 });
    }
  }
  // заборчики вдоль части троп: к дождю (восточная сторона) и к озеру (северная)
  const fenceAlong = (id, d0, d1, off, seed) => {
    const path = PATHS.find((p) => p.id === id);
    const pts = samplePath(path, 0.5);
    let dist = 0, next = d0;
    const out = [];
    for (let i = 1; i < pts.length; i++) {
      dist += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
      if (dist < next || dist > d1) continue;
      next += 2.4;
      const dx = pts[i][0] - pts[i - 1][0], dz = pts[i][1] - pts[i - 1][1];
      const l = Math.hypot(dx, dz) || 1;
      out.push([pts[i][0] + (-dz / l) * off, pts[i][1] + (dx / l) * off]);
    }
    kit.setLayer('solid');
    fenceRun(ctx, out, seed);
  };
  fenceAlong('to-rain', 18, 44, -2.05, 61);
  fenceAlong('lake', 14, 34, 2.0, 62);
  fenceAlong('to-autumn', 16, 38, 2.05, 63);
}

export function buildHub(ctx) {
  campfire(ctx);
  benches(ctx);
  signpost(ctx);
  hubProps(ctx);
  heroTree(ctx);
  void cylUV;
}
