import * as THREE from 'three';
import { Game } from './game.js';

const game = new Game();

// Отладочные хуки для тестов и скриншотов (tests/*.mjs).
window.__cozy = {
  game,
  get frames() { return game.frames; },
  renderer: game.renderer,
  world: game.world,
  camera: game.camera,
  // Свободная камера: yaw/pitch в радианах, h - высота над землёй. null - выключить.
  setCam(o) {
    game.debugCam = o ? { x: 0, z: 0, h: 1.2, yaw: 0, pitch: 0, ...o } : null;
    if (game.debugCam) {
      const d = game.debugCam;
      const f = new THREE.Vector3(d.x, 0, d.z);
      game.world.atmosphere.update(5, f);
      game.world.prefill(f);
    }
  },
};

game.start();

const params = new URLSearchParams(location.search);
if (params.has('autostart')) {
  game.ui.onPlay({ nick: params.get('nick') || 'Tester', room: params.get('room') || '', look: { ...game.ui.look } });
}
