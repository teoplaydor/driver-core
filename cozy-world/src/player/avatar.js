// Персонаж в духе референса: пухлый «боб» с большой головой, детальной одеждой и лицом.
// Всё тело — одна SkinnedMesh (цвет в вершинах) + маленькая глянцевая для глаз/стёкол/пряжки на общем
// скелете: на игрока 2 draw call-а (+1 в тени), локти/колени/живот гнутся плавно.
// Анимация процедурная (anim.js): стопы не скользят и стоят на рельефе, есть позы удержания и действия.

import * as THREE from 'three';
import { clamp, TAU } from '../util/math.js';
import { createSkeleton, B, armDir, gripLocal } from './rig.js';
import { Animator, ACTIONS } from './anim.js';
import {
  buildCharacter, SKINS, HAIRS, OUTFITS, HATS, GLASSES, HAIR_STYLES, FACIAL, sleeveColor,
} from './character-geo.js';

export { SKINS, HAIRS, OUTFITS, HATS, GLASSES, HAIR_STYLES, FACIAL, sleeveColor };

export const AVATAR = { height: 1.3, eye: 1.05, radius: 0.36 };

// Вид удержания предмета по сети (контракт C2): поле hk в состоянии 's' и в снапшоте.
export const HOLD_CODES = [null, 'two', 'one', 'bat', 'umbrella'];
export const holdToCode = (kind) => Math.max(0, HOLD_CODES.indexOf(kind || null));
export const holdFromCode = (n) => HOLD_CODES[n | 0] || null;

const rnd = (n) => Math.floor(Math.random() * n);
export const randomLook = () => ({
  skin: rnd(SKINS.length), outfit: rnd(OUTFITS.length), hat: rnd(HATS.length), glasses: rnd(GLASSES.length),
  hair: rnd(HAIRS.length), hairStyle: rnd(HAIR_STYLES.length), facial: Math.random() < 0.55 ? 0 : 1 + rnd(FACIAL.length - 1),
});

// Внешность по сети — массив из 7 чисел. Старые клиенты шлют 5 — недостающие считаем нулями.
export const lookToArray = (l) => [l.skin | 0, l.outfit | 0, l.hat | 0, l.glasses | 0, l.hair | 0, l.hairStyle | 0, l.facial | 0];
export function lookFromArray(a) {
  const f = (v, n) => (Number.isInteger(v) && v >= 0 && v < n ? v : 0);
  return {
    skin: f(a?.[0], SKINS.length), outfit: f(a?.[1], OUTFITS.length), hat: f(a?.[2], HATS.length),
    glasses: f(a?.[3], GLASSES.length), hair: f(a?.[4], HAIRS.length), hairStyle: f(a?.[5], HAIR_STYLES.length),
    facial: f(a?.[6], FACIAL.length),
  };
}

// ---------- вывеска с ником и пузырь чата ----------

const FONT = '"Baloo 2", "Fredoka", system-ui, sans-serif';

function drawName(canvas, name, speaking) {
  const g = canvas.getContext('2d');
  const W = canvas.width, H = canvas.height;
  g.clearRect(0, 0, W, H);
  g.font = `700 52px ${FONT}`;
  const tw = Math.min(W - 60, g.measureText(name).width);
  const dot = speaking ? 34 : 0;
  const pw = tw + 52 + dot, ph = 76;
  const x = (W - pw) / 2, y = (H - ph) / 2;
  g.fillStyle = 'rgba(24, 18, 30, 0.58)';
  g.beginPath();
  g.roundRect(x, y, pw, ph, ph / 2);
  g.fill();
  g.strokeStyle = 'rgba(255, 255, 255, 0.18)';
  g.lineWidth = 3;
  g.stroke();
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillStyle = '#ffffff';
  g.fillText(name, W / 2 + dot / 2, H / 2 + 3, W - 60);
  if (speaking) {
    g.fillStyle = '#5bf07a';
    g.beginPath();
    g.arc(x + 34, H / 2, 11, 0, TAU);
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
  g.font = `600 34px ${FONT}`;
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

const bodyMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.74, metalness: 0 });
const glossMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.36, metalness: 0 });
const TAG_H = 0.3;

// ---------- сам аватар ----------

export class Avatar {
  constructor(look, { name = '', local = false } = {}) {
    this.local = local;
    this.root = new THREE.Group();
    this.root.name = 'avatar';
    // у своего аватара материалы свои: в 1-м лице тело скрываем (но тень остаётся)
    this.mat = local ? bodyMat.clone() : bodyMat;
    this.glossMat = local ? glossMat.clone() : glossMat;
    this.look = look;
    this.speaking = false;
    this.bubble = null;
    this.talk = 0;

    const { bones, skeleton } = createSkeleton();
    this.bones = bones;
    this.skeleton = skeleton;
    this.root.add(bones[0]);
    this.handR = bones[B.handR];
    this.handL = bones[B.handL];
    this.head = bones[B.head];

    const geo = buildCharacter(look);
    this.top = geo.top;
    this.body = new THREE.SkinnedMesh(geo.body, this.mat);
    this.gloss = new THREE.SkinnedMesh(geo.gloss, this.glossMat);
    for (const m of [this.body, this.gloss]) {
      m.bind(skeleton, new THREE.Matrix4());
      m.boundingSphere = new THREE.Sphere(new THREE.Vector3(0, 0.75, 0), 1.25);
      m.frustumCulled = true;
      this.root.add(m);
    }
    this.body.castShadow = true;
    this.body.receiveShadow = true;
    this.gloss.castShadow = false;

    // точки крепления предметов (контракт C3): +Y — вверх по предмету, −Z — вперёд
    this.anchors = {};
    const two = new THREE.Object3D();
    two.name = 'anchor-two';
    two.position.set(0, -0.12, -0.33);
    bones[B.chest].add(two);
    this.anchors.two = two;
    const a0 = armDir(1);
    const gy = new THREE.Vector3(0, 0, -1), gz = a0.clone().negate();
    const gx = new THREE.Vector3().crossVectors(gy, gz);
    const gq = new THREE.Quaternion().setFromRotationMatrix(new THREE.Matrix4().makeBasis(gx, gy, gz));
    for (const k of ['one', 'bat', 'umbrella']) {
      const o = new THREE.Object3D();
      o.name = `anchor-${k}`;
      o.position.copy(gripLocal(1));
      o.quaternion.copy(gq);
      this.handR.add(o);
      this.anchors[k] = o;
    }

    this.anim = new Animator(bones, this.root);
    this._env = { lookAt: null };

    // ник: белый текст на тёмной «пилюле», читается издалека; пузырь чата всегда выше
    this.tagCanvas = document.createElement('canvas');
    this.tagCanvas.width = 512; this.tagCanvas.height = 128;
    this.tagTex = new THREE.CanvasTexture(this.tagCanvas);
    this.tagTex.colorSpace = THREE.SRGBColorSpace;
    this.tagTex.anisotropy = 4;
    this.tag = new THREE.Sprite(new THREE.SpriteMaterial({ map: this.tagTex, transparent: true, depthTest: false, depthWrite: false, fog: false }));
    this.tag.scale.set(TAG_H * 4, TAG_H, 1);
    this.tag.renderOrder = 20;
    this.tag.visible = !local;
    this.root.add(this.tag);
    this._tagK = 1;
    this._placeTag();

    this.setName(name);
  }

  _placeTag() {
    this.tag.position.set(0, this.top + 0.2 + (TAG_H * (this._tagK - 1)) / 2, 0);
    this.tag.scale.set(TAG_H * 4 * this._tagK, TAG_H * this._tagK, 1);
    if (this.bubble) this.bubble.sprite.position.set(0, this.tag.position.y + (TAG_H * this._tagK) / 2 + 0.04, 0);
  }

  setLook(look) {
    this.look = look;
    const geo = buildCharacter(look);
    this.body.geometry.dispose();
    this.gloss.geometry.dispose();
    this.body.geometry = geo.body;
    this.gloss.geometry = geo.gloss;
    this.top = geo.top;
    this._placeTag();
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

  // Функция высоты опоры fn(x, z, yRef) — стопы ставятся на рельеф/ступени. null — ровная земля.
  setGround(fn) { this.anim.ground = fn || null; }

  // Куда смотреть головой и глазами (Vector3 в мире) — например, на камеру в меню. null — вперёд.
  lookAt(v) { this._env.lookAt = v || null; }

  // Точка крепления предмета (контракт C3).
  anchor(kind) { return this.anchors[kind] || this.anchors.one; }

  // Действие (контракт C2b): 'wave' | 'swing' | 'strum' | 'toggle' | 'throw'.
  act(name) { if (ACTIONS[name]) this.anim.play(name); }
  wave() { this.act('wave'); }

  // Свой аватар в первом лице невидим, но продолжает отбрасывать тень.
  setBodyVisible(v) {
    for (const m of [this.mat, this.glossMat]) {
      m.colorWrite = v;
      m.depthWrite = v;
    }
  }

  say(text) {
    this.clearBubble();
    const c = bubbleCanvas(text);
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthTest: false, depthWrite: false, fog: false }));
    sp.center.set(0.5, 0);
    sp.scale.set(2.6, 1.3, 1);
    sp.renderOrder = 21;
    this.root.add(sp);
    this.bubble = { sprite: sp, tex, life: 5 + Math.min(4, text.length / 18) };
    this._placeTag();
  }

  clearBubble() {
    if (!this.bubble) return;
    this.root.remove(this.bubble.sprite);
    this.bubble.tex.dispose();
    this.bubble.sprite.material.dispose();
    this.bubble = null;
  }

  // st: { speed, grounded, vy, pitch, holdKind, holding, talk }
  update(dt, st, camera) {
    this.talk = st.talk !== undefined ? st.talk : this.speaking ? 0.6 : 0;
    st.talk = this.talk;
    this.anim.update(dt, st, this._env);

    if (this.bubble) {
      this.bubble.life -= dt;
      this.bubble.sprite.material.opacity = clamp(this.bubble.life, 0, 1);
      if (this.bubble.life <= 0) this.clearBubble();
    }
    // ник: с расстоянием немного растёт (читается с 10+ м) и плавно гаснет вдали
    if (camera && !this.local) {
      const d = camera.position.distanceTo(this.root.position);
      const k = clamp(d / 6.5, 1, 2.3);
      if (Math.abs(k - this._tagK) > 0.01) { this._tagK = k; this._placeTag(); }
      this.tag.material.opacity = clamp(1 - (d - 24) / 10, 0, 1);
      this.tag.visible = d < 34;
      if (this.bubble) this.bubble.sprite.scale.set(2.6 * Math.min(k, 1.7), 1.3 * Math.min(k, 1.7), 1);
    }
  }

  dispose() {
    this.clearBubble();
    this.body.geometry.dispose();
    this.gloss.geometry.dispose();
    this.tagTex.dispose();
    this.tag.material.dispose();
    this.skeleton.dispose();
    if (this.local) { this.mat.dispose(); this.glossMat.dispose(); }
  }
}
