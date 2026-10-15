// Мир целиком: сцена, рельеф, вода, небо, атмосфера, растительность, коллизии.
// Постройки и предметы подключаются сверху (structures.js, props.js).

import * as THREE from 'three';
import { initTerrain } from './terrain-data.js';
import { buildTerrain, buildWater } from './terrain.js';
import { Sky } from './sky.js';
import { Atmosphere } from './atmosphere.js';
import { buildVegetation, HERO_MESHES } from './vegetation.js';
import { GrassField } from './grass.js';
import { Decor } from './decor.js';
import { CollisionWorld } from './collision.js';
import { buildStructures } from './structures.js';
import { Weather } from './weather.js';
import { NATURE, MAX_BENDERS } from './nature/shared.js';

export class World {
  constructor(renderer, quality) {
    initTerrain();
    this.renderer = renderer;
    this.quality = quality;
    this.scene = new THREE.Scene();
    this.uTime = NATURE.uTime;
    this.collisions = new CollisionWorld();
    // круги, где не растут трава и мелкий декор (см. excludeNature)
    this.natureExclusions = [];

    this.atmosphere = new Atmosphere(this.scene, renderer);
    this.atmosphere.setShadows(quality.shadow, quality.shadowRange);

    this.sky = new Sky();
    this.scene.add(this.sky.dome, this.sky.clouds);

    this.terrain = buildTerrain({ lite: !!quality.terrainLite });
    this.scene.add(this.terrain);
    this.water = buildWater(this.uTime);
    this.scene.add(this.water);

    const shadows = quality.shadow > 0;
    this.vegetation = buildVegetation({
      collisions: this.collisions, density: quality.trees, nearR: quality.treeNear ?? 70, detail: quality.treeDetail ?? 1, shadows,
    });
    this.vegetation.lod.sunDir.copy(this.atmosphere.sunDir);
    this.scene.add(this.vegetation.group);

    this.grass = new GrassField(this.scene, {
      radius: quality.grassRadius ?? quality.grassChunks * 16 + 8,
      density: quality.grassDensity ?? quality.grassPerChunk / 256,
      lodDist: quality.grassLod ?? 10,
      fadeStart: quality.grassFade ?? 14,
      receiveShadow: shadows,
      exclusions: this.natureExclusions,
      chunk: quality.grassChunk ?? 16,
      blades: quality.grassBlades ?? [8, 4, 5],
    });

    this.decor = new Decor(this.scene, {
      radius: quality.decorRadius ?? 40, density: quality.decorDensity ?? 0.6, lod: quality.treeDetail ?? 1,
      collisions: this.collisions, exclusions: this.natureExclusions, trees: this.vegetation.data.trees, shadows,
    });

    this.structures = buildStructures(this);
    this.scene.add(this.structures.group);

    this.weather = new Weather(quality.trees);
    this.scene.add(this.weather.group);
  }

  prefill(focus) {
    this.grass.prefill(focus.x, focus.z);
    this.decor.prefill(focus.x, focus.z);
    if (!this._compiled) {
      // Шейдеры собираем на загрузке, а не в первом кадре. Если браузер умеет компилировать параллельно
      // (KHR_parallel_shader_compile), природа скрыта, пока её программы не готовы: первый кадр не ждёт
      // компиляции (на телефонах это секунды, а в это время уже идёт подключение к комнате).
      this._compiled = true;
      const r = this.renderer, cam = new THREE.PerspectiveCamera();
      if (r.extensions.has('KHR_parallel_shader_compile')) {
        const nature = [this.vegetation.group, this.grass.group, this.decor.group, ...HERO_MESHES];
        for (const o of nature) o.visible = false;
        const show = () => { for (const o of nature) o.visible = true; };
        r.compileAsync(this.scene, cam).then(show, show);
      } else {
        r.compile(this.scene, cam);
      }
    }
  }

  // C4: листва между камерой и целью растворяется дизерингом (для вида сверху).
  setOcclusion(cameraPos, targetPos, on) {
    NATURE.uOccOn.value = on ? 1 : 0;
    if (on && cameraPos && targetPos) {
      NATURE.uOccA.value.copy(cameraPos);
      NATURE.uOccB.value.copy(targetPos);
    }
  }

  // C6: до 8 точек {x, y, z} (игроки), вокруг которых трава приминается (~0.8 м).
  setBenders(points) {
    const arr = NATURE.uBenders.value;
    for (let i = 0; i < MAX_BENDERS; i++) {
      const p = points && points[i];
      if (p) arr[i].set(p.x, p.y, p.z, 0.8);
      else arr[i].set(0, -1e4, 0, 0); // w = 0 - конец списка (шейдер выходит из цикла)
    }
  }

  // Убрать траву и мелкий декор в круге (например, под новой постройкой или площадкой для игры).
  excludeNature(x, z, r) {
    this.natureExclusions.push({ x, z, r });
    this.grass.invalidate(x, z, r);
    this.decor.invalidate(x, z, r);
  }

  update(dt, time, camera, focus) {
    this.uTime.value = time;
    NATURE.uCamPos.value.copy(camera.position);
    this.atmosphere.update(dt, focus);
    this.sky.update(camera, this.atmosphere, time);
    const cx = camera.position.x, cz = camera.position.z;
    this.grass.update(cx, cz, 1);
    this.decor.update(cx, cz, 1);
    this.vegetation.update(camera);
    this.structures.update(dt, time);
    this.weather.update(time, camera, this.atmosphere, this.renderer.domElement.height);
  }
}
