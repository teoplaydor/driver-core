// Постройки и обустройство: хаб (костёр, брёвна, указатель, фонари, большое дерево), беседка, пирс с лодкой,
// домик в зиме, ферма, многоэтажка с квартирой, арена. Всё процедурное (structures/*.js).
// Статика каждой зоны склеивается в 1-3 меша одного материала (structures/kit.js), крыши - отдельными мешами
// (контракт C5: userData.roof + roofArea), дальние зоны - упрощённой копией. Коллизии - в world.collisions.

import * as THREE from 'three';
import { QUALITY } from '../config.js';
import { NATURE } from './nature/shared.js';
import { Kit } from './structures/kit.js';
import { structureMaterial } from './structures/material.js';
import { updateParticleScale } from './structures/fx.js';
import { buildHub, buildPaths } from './structures/hub.js';
import { buildGazebo } from './structures/gazebo.js';
import { buildPier, buildCabin, buildFarm } from './structures/sites.js';
import { buildBlock, buildInterior } from './structures/city.js';
import { buildArena } from './structures/arena.js';

// 0 - low, 1 - medium, 2 - high
function qualityLevel(q) {
  if (q === QUALITY.low) return 0;
  if (q === QUALITY.medium) return 1;
  if (q === QUALITY.high) return 2;
  return q.post ? (q.ao ? 2 : 1) : 0;
}

// Расстояния LOD (м от края зоны): ближняя модель / мелкие детали.
const LOD = [
  { near: 95, detail: 45 },
  { near: 130, detail: 65 },
  { near: 170, detail: 90 },
];

// Меш только для карты теней: в обычном кадре число вершин отрицательное (three пропускает отрисовку,
// draw call не тратится), на время отрисовки в карту теней - вся геометрия. Рисуются задние грани
// (так three поступает с FrontSide в тенях), поэтому заменитель чуть крупнее детали не затеняет её саму.
const SHADOW_ONLY = new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: false });
function shadowCaster(geo, name) {
  geo.setDrawRange(0, -1);
  const m = new THREE.Mesh(geo, SHADOW_ONLY);
  m.name = name;
  m.matrixAutoUpdate = false;
  m.castShadow = true;
  m.receiveShadow = false;
  m.onBeforeShadow = () => { geo.drawRange.count = Infinity; };
  m.onAfterShadow = () => { geo.drawRange.count = -1; };
  return m;
}

export function buildStructures(world) {
  const level = qualityLevel(world.quality);
  const kit = new Kit(level, world.quality.shadow > 0);
  const root = new THREE.Group();
  root.name = 'structures';
  const ctx = {
    world, kit, level, col: world.collisions, root, anim: [], portals: [],
    exclude: (x, z, r) => world.excludeNature?.(x, z, r),
  };

  // каждый сборщик должен вернуть стек рамок кита в исходное состояние
  const step = (name, fn) => {
    const r = fn();
    if (kit.stack.length !== 1) { console.warn(`structures: ${name} оставил ${kit.stack.length - 1} рамок`); kit.stack.length = 1; }
    return r;
  };
  step('hub', () => buildHub(ctx));
  step('paths', () => buildPaths(ctx));
  step('gazebo', () => buildGazebo(ctx));
  step('pier', () => buildPier(ctx));
  step('cabin', () => buildCabin(ctx));
  step('farm', () => buildFarm(ctx));
  const apt = step('block', () => buildBlock(ctx));
  step('arena', () => buildArena(ctx));
  step('interior', () => buildInterior(ctx, apt));

  // склейка: группа -> меши слоёв одним материалом
  const mat = structureMaterial();
  const lod = LOD[level];
  const zones = [];
  for (const g of kit.groups.values()) {
    const node = new THREE.Group();
    node.name = `st-${g.name}`;
    const z = { g, node, solid: null, detail: null, far: null, shadow: null, extra: g.extra, near: g.nearDist === Infinity ? lod.near : g.nearDist, det: g.detailDist === Infinity ? lod.detail : g.detailDist, state: -1 };
    for (const [layer, b] of g.buckets) {
      if (!b.n) continue;
      if (layer === 'shadow') {
        z.shadow = shadowCaster(b.toShadowGeometry(), `${g.name}:shadow`);
        node.add(z.shadow);
        continue;
      }
      const mesh = new THREE.Mesh(b.toGeometry(), mat);
      mesh.name = `${g.name}:${layer}`;
      mesh.matrixAutoUpdate = false;
      mesh.receiveShadow = true;
      mesh.castShadow = layer === 'solid' || layer.startsWith('roof:');
      if (layer.startsWith('roof:')) {
        const r = g.roofs.find((o) => o.layer === layer);
        mesh.userData.roof = true;
        mesh.userData.roofArea = { ...r.area };
      } else if (layer === 'far') {
        mesh.castShadow = false;
        z.far = mesh;
      } else if (layer === 'detail') z.detail = mesh;
      else if (layer === 'solid') z.solid = mesh;
      node.add(mesh);
    }
    if (z.shadow && z.solid) z.solid.castShadow = false; // тень за него рисует заменитель
    // группа, собранная в своих координатах (лодка качается на воде): узел ставим в точку опоры
    if (g.pivot) { node.position.set(g.pivot.x, g.pivot.y, g.pivot.z); node.rotation.y = g.pivot.ry || 0; }
    if (g.onNode) g.onNode(node, z);
    for (const e of g.extra) node.add(e.obj);
    root.add(node);
    zones.push(z);
  }

  const cam = NATURE.uCamPos.value;
  return {
    group: root,
    portals: ctx.portals,
    zones,
    tris: kit.tris,
    shadowTris: kit.shadowTris,
    update(dt, t) {
      for (const z of zones) {
        const d = Math.max(0, Math.hypot(cam.x - z.g.cx, cam.z - z.g.cz) - z.g.r);
        const st = d < z.det ? 2 : d < z.near ? 1 : 0;
        if (st === z.state) continue;
        z.state = st;
        if (z.solid) z.solid.visible = st > 0 || !z.far;
        if (z.shadow) z.shadow.visible = st > 0 || !z.far;
        if (z.detail) z.detail.visible = st > 1;
        if (z.far) z.far.visible = st === 0;
        for (const e of z.extra) {
          if (e.lod === 'detail') e.obj.visible = st > 1;
          else if (e.lod === 'near') e.obj.visible = st > 0;
        }
      }
      for (const f of ctx.anim) f(dt, t);
      updateParticleScale(world.renderer.domElement.height, null);
    },
  };
}
