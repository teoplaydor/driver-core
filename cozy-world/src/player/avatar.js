// Мультяшный персонаж в духе референса: пухлое "бобовое" тело, большая голова без шеи,
// короткие ручки-ножки, жилетка, шляпы и очки. Каждая часть тела - один меш с цветом в вершинах,
// поэтому на игрока приходится ~7 draw call-ов, а внешность меняется пересборкой геометрии.

import * as THREE from 'three';
import { mergeAll, solid } from '../world/geo.js';
import { clamp, damp, TAU } from '../util/math.js';

export const SKINS = ['#f2a77f', '#e89b72', '#d08359', '#a8643f', '#f7c7a3', '#7f4a30'];
export const HAIRS = ['#3b2a1d', '#6b4a2f', '#c9a24a', '#b9482a', '#1f1f24', '#d9d9d9'];
export const OUTFITS = [
  { name: 'Исследователь', shirt: '#f4efe6', vest: '#7a2e22', pants: '#d8c48f', shoes: '#4a3425' },
  { name: 'Лесник', shirt: '#ddd6c2', vest: '#3f6b3a', pants: '#6a5a3f', shoes: '#3b2a1d' },
  { name: 'Рыбак', shirt: '#f3d65a', vest: '#2f5f8f', pants: '#4a6a8a', shoes: '#2c2c33' },
  { name: 'Ковбой', shirt: '#c94b3b', vest: '#5a3a24', pants: '#3f4f73', shoes: '#2e2118' },
  { name: 'Мятный', shirt: '#cfeee3', vest: '#2d8f7b', pants: '#e8dcc2', shoes: '#4c3b2a' },
  { name: 'Клубничный', shirt: '#fbe4ea', vest: '#d9547a', pants: '#7b6ea8', shoes: '#3a2c40' },
  { name: 'Угольный', shirt: '#e8e8ec', vest: '#33343c', pants: '#55565f', shoes: '#1f1f24' },
  { name: 'Закат', shirt: '#ffe3b3', vest: '#d97a2b', pants: '#8a4b2a', shoes: '#3d2a1c' },
];
export const HATS = ['Без шляпы', 'Сафари', 'Ковбойская', 'Каска', 'Кепка', 'Шапка', 'Колпак'];
export const GLASSES = ['Без очков', 'Чёрные круглые', 'Квадратные'];

export const AVATAR = { height: 1.27, eye: 1.05, radius: 0.36 };

export const randomLook = () => ({
  skin: Math.floor(Math.random() * SKINS.length),
  outfit: Math.floor(Math.random() * OUTFITS.length),
  hat: Math.floor(Math.random() * HATS.length),
  glasses: Math.floor(Math.random() * GLASSES.length),
  hair: Math.floor(Math.random() * HAIRS.length),
});

export const lookToArray = (l) => [l.skin, l.outfit, l.hat, l.glasses, l.hair];
export function lookFromArray(a) {
  const f = (v, n) => (Number.isInteger(v) && v >= 0 && v < n ? v : 0);
  return {
    skin: f(a?.[0], SKINS.length), outfit: f(a?.[1], OUTFITS.length), hat: f(a?.[2], HATS.length),
    glasses: f(a?.[3], GLASSES.length), hair: f(a?.[4], HAIRS.length),
  };
}

// ---------- сборка частей ----------

const _m = new THREE.Matrix4(), _q = new THREE.Quaternion(), _e = new THREE.Euler();
const _p = new THREE.Vector3(), _s = new THREE.Vector3();

function prim(geo, color, { pos = [0, 0, 0], rot = [0, 0, 0], scl = [1, 1, 1] } = {}) {
  _m.compose(_p.set(...pos), _q.setFromEuler(_e.set(...rot)), _s.set(...scl));
  geo.applyMatrix4(_m);
  return solid(geo, color);
}

const shade = (hex, k) => `#${new THREE.Color(hex).multiplyScalar(k).getHexString()}`;

function bodyGeometry(o, skin) {
  const g = new THREE.SphereGeometry(0.4, 22, 16);
  g.scale(1.0, 0.95, 0.9);
  g.translate(0, 0.52, 0);
  const pants = new THREE.Color(o.pants), vest = new THREE.Color(o.vest), shirt = new THREE.Color(o.shirt);
  const t = new THREE.Color();
  const ss = (a, b, x) => { const k = clamp((x - a) / (b - a), 0, 1); return k * k * (3 - 2 * k); };
  const parts = [g];
  const body = parts[0];
  const pos = body.getAttribute('position');
  const col = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i), y = pos.getY(i), z = pos.getZ(i);
    // низ - штаны, выше - жилетка, спереди по центру выглядывает рубашка
    t.copy(vest);
    const front = ss(-0.04, -0.14, z) * (1 - ss(0.1, 0.17, Math.abs(x)));
    t.lerp(shirt, front);
    t.lerp(pants, 1 - ss(0.3, 0.38, y));
    col[i * 3] = t.r; col[i * 3 + 1] = t.g; col[i * 3 + 2] = t.b;
  }
  body.setAttribute('color', new THREE.BufferAttribute(col, 3));
  // воротник и ремень
  const belt = prim(new THREE.TorusGeometry(0.385, 0.018, 6, 24), shade(o.shoes, 1), { pos: [0, 0.335, 0], rot: [Math.PI / 2, 0, 0], scl: [1, 0.9, 1] });
  const collar = prim(new THREE.TorusGeometry(0.2, 0.03, 6, 18), o.shirt, { pos: [0, 0.86, -0.1], rot: [Math.PI / 2 - 0.5, 0, 0] });
  void skin;
  return mergeAll([body, belt, collar]);
}

function armGeometry(o, skin, side) {
  const x = side * 0.0;
  const sleeve = prim(new THREE.CapsuleGeometry(0.088, 0.13, 4, 10), o.shirt, { pos: [x, -0.15, 0] });
  const cuff = prim(new THREE.CylinderGeometry(0.095, 0.095, 0.03, 12), shade(o.shirt, 0.88), { pos: [x, -0.25, 0] });
  const hand = prim(new THREE.SphereGeometry(0.1, 12, 10), skin, { pos: [x, -0.33, 0] });
  return mergeAll([sleeve, cuff, hand]);
}

function legGeometry(o) {
  const leg = prim(new THREE.CapsuleGeometry(0.11, 0.05, 4, 10), o.pants, { pos: [0, -0.1, 0] });
  const shoe = prim(new THREE.SphereGeometry(0.13, 12, 10), o.shoes, { pos: [0, -0.205, -0.05], scl: [1.02, 0.62, 1.45] });
  return mergeAll([leg, shoe]);
}

function hatParts(kind, o, hairHex) {
  const hatY = 0.2; // макушка головы относительно центра
  switch (kind) {
    case 1: { // сафари
      return [
        prim(new THREE.CylinderGeometry(0.44, 0.44, 0.022, 28), '#d9c28f', { pos: [0, hatY, 0] }),
        prim(new THREE.CylinderGeometry(0.255, 0.285, 0.15, 22), '#dcc695', { pos: [0, hatY + 0.085, 0] }),
        prim(new THREE.SphereGeometry(0.255, 16, 6, 0, TAU, 0, Math.PI / 2), '#dcc695', { pos: [0, hatY + 0.16, 0], scl: [1, 0.4, 1] }),
        prim(new THREE.CylinderGeometry(0.288, 0.29, 0.05, 22), '#7a5a3a', { pos: [0, hatY + 0.04, 0] }),
      ];
    }
    case 2: { // ковбойская
      return [
        prim(new THREE.CylinderGeometry(0.5, 0.5, 0.02, 28), '#8a5a33', { pos: [0, hatY, 0], scl: [1, 1, 0.82] }),
        prim(new THREE.BoxGeometry(0.2, 0.05, 0.4), '#8a5a33', { pos: [0.5, hatY + 0.03, 0], rot: [0, 0, 0.55] }),
        prim(new THREE.BoxGeometry(0.2, 0.05, 0.4), '#8a5a33', { pos: [-0.5, hatY + 0.03, 0], rot: [0, 0, -0.55] }),
        prim(new THREE.CylinderGeometry(0.2, 0.28, 0.22, 20), '#96623a', { pos: [0, hatY + 0.11, 0] }),
        prim(new THREE.CylinderGeometry(0.285, 0.288, 0.045, 20), '#4b3020', { pos: [0, hatY + 0.04, 0] }),
      ];
    }
    case 3: { // каска
      return [
        prim(new THREE.SphereGeometry(0.34, 18, 10, 0, TAU, 0, Math.PI * 0.43), '#6e7d3c', { pos: [0, 0, 0] }),
        prim(new THREE.TorusGeometry(0.332, 0.034, 6, 24), '#b0663a', { pos: [0, 0.076, 0], rot: [Math.PI / 2, 0, 0] }),
      ];
    }
    case 4: { // кепка (цвет жилетки)
      return [
        prim(new THREE.SphereGeometry(0.315, 16, 8, 0, TAU, 0, Math.PI * 0.5), o.vest, { pos: [0, 0.02, 0] }),
        prim(new THREE.BoxGeometry(0.3, 0.025, 0.24), shade(o.vest, 0.85), { pos: [0, 0.075, -0.33], rot: [0.12, 0, 0] }),
        prim(new THREE.SphereGeometry(0.03, 8, 6), shade(o.vest, 0.7), { pos: [0, 0.335, 0] }),
      ];
    }
    case 5: { // шапка
      return [
        prim(new THREE.SphereGeometry(0.325, 16, 10, 0, TAU, 0, Math.PI * 0.58), o.shirt, { pos: [0, 0, 0] }),
        prim(new THREE.TorusGeometry(0.31, 0.05, 8, 22), shade(o.vest, 1), { pos: [0, 0.06, 0], rot: [Math.PI / 2, 0, 0] }),
        prim(new THREE.SphereGeometry(0.075, 10, 8), shade(o.vest, 1.1), { pos: [0, 0.34, 0] }),
      ];
    }
    case 6: { // колпак
      return [
        prim(new THREE.ConeGeometry(0.17, 0.42, 16), o.vest, { pos: [0.05, hatY + 0.19, 0], rot: [0, 0, -0.18] }),
        prim(new THREE.TorusGeometry(0.165, 0.018, 6, 18), shade(o.shirt, 1), { pos: [0.01, hatY + 0.01, 0], rot: [Math.PI / 2, 0, -0.18] }),
        prim(new THREE.SphereGeometry(0.045, 8, 6), '#ffffff', { pos: [0.09, hatY + 0.4, 0] }),
      ];
    }
    default: { // без шляпы - просто причёска
      return [
        prim(new THREE.SphereGeometry(0.305, 16, 8, 0, TAU, 0, Math.PI * 0.42), hairHex, { pos: [0, 0.01, 0.025], rot: [0.25, 0, 0] }),
      ];
    }
  }
}

function glassesParts(kind) {
  if (kind === 1) { // чёрные круглые
    return [
      prim(new THREE.CylinderGeometry(0.078, 0.078, 0.02, 18), '#111114', { pos: [-0.105, 0.04, -0.285], rot: [Math.PI / 2, 0, 0] }),
      prim(new THREE.CylinderGeometry(0.078, 0.078, 0.02, 18), '#111114', { pos: [0.105, 0.04, -0.285], rot: [Math.PI / 2, 0, 0] }),
      prim(new THREE.BoxGeometry(0.08, 0.014, 0.014), '#111114', { pos: [0, 0.055, -0.285] }),
      prim(new THREE.BoxGeometry(0.012, 0.012, 0.22), '#111114', { pos: [-0.185, 0.055, -0.19], rot: [0, -0.18, 0] }),
      prim(new THREE.BoxGeometry(0.012, 0.012, 0.22), '#111114', { pos: [0.185, 0.055, -0.19], rot: [0, 0.18, 0] }),
    ];
  }
  if (kind === 2) { // квадратные со светлой оправой
    return [
      prim(new THREE.BoxGeometry(0.17, 0.12, 0.016), '#f0e2b0', { pos: [-0.105, 0.04, -0.287] }),
      prim(new THREE.BoxGeometry(0.17, 0.12, 0.016), '#f0e2b0', { pos: [0.105, 0.04, -0.287] }),
      prim(new THREE.BoxGeometry(0.145, 0.095, 0.018), '#2e4a73', { pos: [-0.105, 0.04, -0.2965] }),
      prim(new THREE.BoxGeometry(0.145, 0.095, 0.018), '#2e4a73', { pos: [0.105, 0.04, -0.2965] }),
      prim(new THREE.BoxGeometry(0.06, 0.014, 0.014), '#f0e2b0', { pos: [0, 0.06, -0.287] }),
    ];
  }
  return [];
}

function headGeometry(look) {
  const skin = SKINS[look.skin], o = OUTFITS[look.outfit], hair = HAIRS[look.hair];
  const parts = [
    prim(new THREE.SphereGeometry(0.3, 22, 16), skin, { scl: [1, 0.96, 0.97] }),
    prim(new THREE.SphereGeometry(0.052, 10, 8), shade(skin, 0.97), { pos: [-0.285, -0.01, 0.0], scl: [0.55, 1, 1] }),
    prim(new THREE.SphereGeometry(0.052, 10, 8), shade(skin, 0.97), { pos: [0.285, -0.01, 0.0], scl: [0.55, 1, 1] }),
    prim(new THREE.SphereGeometry(0.058, 12, 10), shade(skin, 0.93), { pos: [0, -0.04, -0.285] }),
    // румянец
    prim(new THREE.SphereGeometry(0.05, 8, 6), shade(skin, 1.08), { pos: [-0.17, -0.07, -0.245], scl: [1, 0.7, 0.5] }),
    prim(new THREE.SphereGeometry(0.05, 8, 6), shade(skin, 1.08), { pos: [0.17, -0.07, -0.245], scl: [1, 0.7, 0.5] }),
    // улыбка
    prim(new THREE.TorusGeometry(0.05, 0.009, 5, 12, Math.PI), '#7a2a22', { pos: [0, -0.115, -0.273], rot: [0, 0, Math.PI] }),
  ];
  if (look.glasses === 0) {
    parts.push(
      prim(new THREE.SphereGeometry(0.03, 10, 8), '#16161a', { pos: [-0.1, 0.035, -0.27], scl: [0.8, 1.25, 0.5] }),
      prim(new THREE.SphereGeometry(0.03, 10, 8), '#16161a', { pos: [0.1, 0.035, -0.27], scl: [0.8, 1.25, 0.5] }),
      prim(new THREE.SphereGeometry(0.011, 6, 5), '#ffffff', { pos: [-0.092, 0.05, -0.283] }),
      prim(new THREE.SphereGeometry(0.011, 6, 5), '#ffffff', { pos: [0.108, 0.05, -0.283] }),
      prim(new THREE.BoxGeometry(0.085, 0.016, 0.016), shade(hair, 1), { pos: [-0.1, 0.1, -0.265], rot: [0, 0, 0.12] }),
      prim(new THREE.BoxGeometry(0.085, 0.016, 0.016), shade(hair, 1), { pos: [0.1, 0.1, -0.265], rot: [0, 0, -0.12] }),
    );
  }
  parts.push(...glassesParts(look.glasses), ...hatParts(look.hat, o, hair));
  return mergeAll(parts);
}

// ---------- вывеска с ником и пузырь чата ----------

function drawName(canvas, name, speaking) {
  const g = canvas.getContext('2d');
  g.clearRect(0, 0, canvas.width, canvas.height);
  g.font = '600 38px "Baloo 2", "Fredoka", system-ui, sans-serif';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.lineJoin = 'round';
  g.lineWidth = 8;
  g.strokeStyle = 'rgba(30,22,40,0.55)';
  const x = canvas.width / 2 + (speaking ? 14 : 0);
  g.strokeText(name, x, canvas.height / 2);
  g.fillStyle = '#ffffff';
  g.fillText(name, x, canvas.height / 2);
  if (speaking) {
    const w = g.measureText(name).width;
    g.fillStyle = '#5bf07a';
    g.beginPath();
    g.arc(x - w / 2 - 20, canvas.height / 2, 9, 0, TAU);
    g.fill();
  }
}

function wrapText(g, text, maxW) {
  const words = text.split(/\s+/);
  const lines = [];
  let line = '';
  for (const w of words) {
    const test = line ? `${line} ${w}` : w;
    if (g.measureText(test).width > maxW && line) { lines.push(line); line = w; } else line = test;
  }
  if (line) lines.push(line);
  return lines.slice(0, 4);
}

function bubbleCanvas(text) {
  const c = document.createElement('canvas');
  c.width = 512; c.height = 256;
  const g = c.getContext('2d');
  g.font = '600 34px "Baloo 2", "Fredoka", system-ui, sans-serif';
  const lines = wrapText(g, text, 420);
  const lh = 42;
  const w = Math.min(480, Math.max(...lines.map((l) => g.measureText(l).width)) + 44);
  const h = lines.length * lh + 30;
  const x = (c.width - w) / 2, y = c.height - h - 26;
  g.fillStyle = 'rgba(255,255,255,0.96)';
  g.strokeStyle = 'rgba(40,30,60,0.25)';
  g.lineWidth = 4;
  g.beginPath();
  g.roundRect(x, y, w, h, 22);
  g.fill();
  g.stroke();
  g.beginPath(); // хвостик
  g.moveTo(c.width / 2 - 14, y + h - 1);
  g.lineTo(c.width / 2, y + h + 18);
  g.lineTo(c.width / 2 + 14, y + h - 1);
  g.fill();
  g.fillStyle = '#2a2433';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  lines.forEach((l, i) => g.fillText(l, c.width / 2, y + 15 + lh * (i + 0.5)));
  return c;
}

const sharedMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.78, metalness: 0 });

// ---------- сам аватар ----------

export class Avatar {
  constructor(look, { name = '', local = false } = {}) {
    this.local = local;
    this.root = new THREE.Group();
    this.root.name = 'avatar';
    this.mat = local ? sharedMat.clone() : sharedMat; // у своего аватара материал свой: в 1-м лице тело скрываем
    this.look = look;
    this.phase = Math.random() * 6;
    this.t = 0;
    this.speed = 0;
    this.lean = 0;
    this.waveT = 0;
    this.speaking = false;
    this.bubble = null;
    this.geos = [];

    this.body = new THREE.Group();
    this.root.add(this.body);
    this.torso = new THREE.Mesh(undefined, this.mat);
    this.head = new THREE.Mesh(undefined, this.mat);
    this.head.position.set(0, 0.98, 0);
    this.armL = new THREE.Mesh(undefined, this.mat);
    this.armR = new THREE.Mesh(undefined, this.mat);
    this.armL.position.set(-0.4, 0.72, 0);
    this.armR.position.set(0.4, 0.72, 0);
    this.legL = new THREE.Mesh(undefined, this.mat);
    this.legR = new THREE.Mesh(undefined, this.mat);
    this.legL.position.set(-0.17, 0.27, 0);
    this.legR.position.set(0.17, 0.27, 0);
    this.parts = [this.torso, this.head, this.armL, this.armR, this.legL, this.legR];
    for (const p of this.parts) { p.castShadow = true; p.frustumCulled = true; }
    this.body.add(this.torso, this.head, this.armL, this.armR, this.legL, this.legR);

    this.tagCanvas = document.createElement('canvas');
    this.tagCanvas.width = 320; this.tagCanvas.height = 80;
    this.tagTex = new THREE.CanvasTexture(this.tagCanvas);
    this.tagTex.colorSpace = THREE.SRGBColorSpace;
    this.tag = new THREE.Sprite(new THREE.SpriteMaterial({ map: this.tagTex, transparent: true, depthTest: false, depthWrite: false, fog: false }));
    this.tag.scale.set(1.5, 0.375, 1);
    this.tag.position.set(0, 1.78, 0);
    this.tag.renderOrder = 20;
    this.tag.visible = !local;
    this.root.add(this.tag);

    this.setLook(look);
    this.setName(name);
  }

  setLook(look) {
    this.look = look;
    for (const g of this.geos) g.dispose();
    const o = OUTFITS[look.outfit], skin = SKINS[look.skin];
    this.geos = [
      bodyGeometry(o, skin), headGeometry(look), armGeometry(o, skin, -1), armGeometry(o, skin, 1),
      legGeometry(o), legGeometry(o),
    ];
    this.torso.geometry = this.geos[0];
    this.head.geometry = this.geos[1];
    this.armL.geometry = this.geos[2];
    this.armR.geometry = this.geos[3];
    this.legL.geometry = this.geos[4];
    this.legR.geometry = this.geos[5];
  }

  setName(name) {
    this.name = name;
    drawName(this.tagCanvas, name, this.speaking);
    this.tagTex.needsUpdate = true;
  }

  setSpeaking(v) {
    if (v === this.speaking) return;
    this.speaking = v;
    drawName(this.tagCanvas, this.name, v);
    this.tagTex.needsUpdate = true;
  }

  // Свой аватар в первом лице невидим, но продолжает отбрасывать тень.
  setBodyVisible(v) {
    this.mat.colorWrite = v;
    this.mat.depthWrite = v;
  }

  say(text) {
    this.clearBubble();
    const c = bubbleCanvas(text);
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthTest: false, depthWrite: false, fog: false }));
    sp.scale.set(3.0, 1.5, 1);
    sp.position.set(0, 2.38, 0);
    sp.renderOrder = 21;
    this.root.add(sp);
    this.bubble = { sprite: sp, tex, life: 5 + Math.min(4, text.length / 18) };
  }

  clearBubble() {
    if (!this.bubble) return;
    this.root.remove(this.bubble.sprite);
    this.bubble.tex.dispose();
    this.bubble.sprite.material.dispose();
    this.bubble = null;
  }

  wave() { this.waveT = 1.6; }

  // st: { speed, grounded, vy, pitch, holding }
  update(dt, st, camera) {
    this.t += dt;
    this.speed = damp(this.speed, st.speed, 10, dt);
    const moving = Math.min(1, this.speed / 3.2);
    this.phase += dt * (3.2 + this.speed * 1.5) * (st.grounded ? 1 : 0.3);
    const sw = Math.sin(this.phase);
    const swing = sw * 0.75 * moving;

    this.legL.rotation.x = swing;
    this.legR.rotation.x = -swing;
    const air = st.grounded ? 0 : clamp(st.vy / 6, -1, 1);
    if (!st.grounded) {
      this.legL.rotation.x = -0.5 - air * 0.2; this.legR.rotation.x = 0.35 + air * 0.2;
    }
    const bob = Math.abs(sw) * 0.045 * moving + Math.sin(this.t * 2.2) * 0.008;
    this.body.position.y = bob;
    this.body.rotation.z = sw * 0.06 * moving;
    this.lean = damp(this.lean, Math.min(0.22, this.speed * 0.03), 8, dt);
    this.body.rotation.x = this.lean;

    // руки: ходьба, полёт, несём предмет, приветствие
    let aL = -swing * 0.9, aR = swing * 0.9, zL = 0.12, zR = -0.12;
    if (!st.grounded) { aL = -2.2 - air * 0.4; aR = -2.2 - air * 0.4; zL = 0.5; zR = -0.5; }
    if (st.holding) { aL = -1.25; aR = -1.25; zL = -0.35; zR = 0.35; }
    if (this.waveT > 0) {
      this.waveT -= dt;
      aR = -2.7; zR = -0.35 - Math.sin(this.t * 14) * 0.45;
    }
    this.armL.rotation.x = aL; this.armL.rotation.z = zL;
    this.armR.rotation.x = aR; this.armR.rotation.z = zR;
    this.head.rotation.x = clamp(-st.pitch * 0.55, -0.5, 0.5);
    this.head.rotation.z = Math.sin(this.t * 1.3) * 0.015;

    if (this.bubble) {
      this.bubble.life -= dt;
      this.bubble.sprite.material.opacity = clamp(this.bubble.life, 0, 1);
      if (this.bubble.life <= 0) this.clearBubble();
    }
    // ник плавно гаснет с расстоянием
    if (camera && !this.local) {
      const d = camera.position.distanceTo(this.root.position);
      this.tag.material.opacity = clamp(1 - (d - 12) / 14, 0, 1);
      this.tag.visible = d < 26;
    }
  }

  dispose() {
    this.clearBubble();
    for (const g of this.geos) g.dispose();
    this.tagTex.dispose();
    this.tag.material.dispose();
    if (this.local) this.mat.dispose();
  }
}
