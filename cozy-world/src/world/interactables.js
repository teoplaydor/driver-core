// Реестр взаимодействий (контракт C7): всё, к чему подходят и жмут E / ✋ / тап, кроме переносимых предметов:
// порталы, места за столами, стенды, проектор... Предметы (props.js) выбирают цель сами и спрашивают реестр,
// когда в руках пусто и рядом нет предмета.
//
// add({ id, label, pos, radius, use(game), enabled?(), hit?, priority? })
//   label    — строка или функция () => строка (подсказка «E — …»)
//   pos      — THREE.Vector3 (можно менять на месте: реестр читает его при каждом запросе)
//   radius   — с какого расстояния (по горизонтали, м) доступно
//   hit      — радиус сферы для тапа/луча (по умолчанию 0.6 м)
//   priority — при равных условиях выше приоритет выигрывает (по умолчанию 0)

import * as THREE from 'three';

const _d = new THREE.Vector3(), _oc = new THREE.Vector3();

export class Interactables {
  constructor() {
    this.items = new Map();
  }

  add(it) {
    if (!it || !it.id || !it.pos || typeof it.use !== 'function') throw new Error('interactables.add: нужны id, pos и use');
    this.items.set(it.id, { radius: 1.5, hit: 0.6, priority: 0, ...it });
    return it.id;
  }

  remove(id) { this.items.delete(id); }

  get(id) { return this.items.get(id) || null; }

  label(it) { return typeof it.label === 'function' ? it.label() : it.label || ''; }

  // Ближайшая доступная цель для игрока в pos; dir (вектор взгляда/движения) — предпочтение тем, что спереди:
  // цель сзади считается вдвое дальше, а совсем сзади (больше ~115°) не берётся.
  nearest(pos, dir) {
    let best = null, bestScore = Infinity;
    for (const it of this.items.values()) {
      if (it.enabled && !it.enabled()) continue;
      _d.set(it.pos.x - pos.x, 0, it.pos.z - pos.z);
      const dist = _d.length();
      if (dist > it.radius || Math.abs(it.pos.y - pos.y) > 2.5) continue;
      let score = dist;
      if (dir && dist > 0.35) {
        const fx = dir.x, fz = dir.z, fl = Math.hypot(fx, fz);
        if (fl > 1e-4) {
          const cos = (_d.x * fx + _d.z * fz) / (dist * fl);
          if (cos < -0.42) continue;
          score *= 1.5 - cos * 0.5;
        }
      }
      score -= it.priority * 0.25;
      if (score < bestScore) { bestScore = score; best = it; }
    }
    return best;
  }

  // Цель под лучом (тап по экрану в виде сверху, взгляд в центр экрана): ближайшая сфера hit на луче.
  raycast(ray, maxDist = 60) {
    let best = null, bestT = maxDist;
    for (const it of this.items.values()) {
      if (it.enabled && !it.enabled()) continue;
      _oc.subVectors(it.pos, ray.origin);
      const t = _oc.dot(ray.direction);
      if (t < 0 || t > bestT) continue;
      const d2 = _oc.lengthSq() - t * t;
      if (d2 > it.hit * it.hit) continue;
      bestT = t;
      best = it;
    }
    return best;
  }
}
