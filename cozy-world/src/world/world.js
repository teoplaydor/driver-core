// Мир целиком: сцена, рельеф, вода, небо, атмосфера, растительность, коллизии.
// Постройки и предметы подключаются сверху (structures.js, props.js).

import * as THREE from 'three';
import { initTerrain } from './terrain-data.js';
import { buildTerrain, buildWater } from './terrain.js';
import { Sky } from './sky.js';
import { Atmosphere } from './atmosphere.js';
import { buildVegetation } from './vegetation.js';
import { GrassField } from './grass.js';
import { CollisionWorld } from './collision.js';
import { buildStructures } from './structures.js';
import { Weather } from './weather.js';

export class World {
  constructor(renderer, quality) {
    initTerrain();
    this.renderer = renderer;
    this.quality = quality;
    this.scene = new THREE.Scene();
    this.uTime = { value: 0 };
    this.collisions = new CollisionWorld();

    this.atmosphere = new Atmosphere(this.scene, renderer);
    this.atmosphere.setShadows(quality.shadow, quality.shadowRange);

    this.sky = new Sky();
    this.scene.add(this.sky.dome, this.sky.clouds);

    this.terrain = buildTerrain();
    this.scene.add(this.terrain);
    this.water = buildWater(this.uTime);
    this.scene.add(this.water);

    this.vegetation = buildVegetation({ collisions: this.collisions, uTime: this.uTime, density: quality.trees });
    this.scene.add(this.vegetation.group);

    this.grass = new GrassField(this.scene, {
      radius: quality.grassChunks, perChunk: quality.grassPerChunk, uTime: this.uTime,
    });

    this.structures = buildStructures(this);
    this.scene.add(this.structures.group);

    this.weather = new Weather(quality.trees);
    this.scene.add(this.weather.group);
  }

  prefill(focus) {
    this.grass.prefill(focus.x, focus.z);
  }

  update(dt, time, camera, focus) {
    this.uTime.value = time;
    this.atmosphere.update(dt, focus);
    this.sky.update(camera, this.atmosphere, time);
    this.grass.update(focus.x, focus.z, 1);
    this.structures.update(dt, time);
    this.weather.update(time, camera, this.atmosphere, this.renderer.domElement.height);
  }
}
