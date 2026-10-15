// Огромные мультяшные руки от первого лица - визитная карточка референса.
// Рисуются отдельным проходом поверх мира (чистим глубину), поэтому не пролезают сквозь стены.

import * as THREE from 'three';
import { clamp, damp } from '../util/math.js';

const FINGERS = [
  { x: -0.052, len: 0.062, tilt: 0.1 },
  { x: -0.017, len: 0.074, tilt: 0.03 },
  { x: 0.018, len: 0.07, tilt: -0.04 },
  { x: 0.052, len: 0.056, tilt: -0.12 },
];

export class Hands {
  constructor(skinHex) {
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(58, 1, 0.02, 10);
    this.hemi = new THREE.HemisphereLight(0xffffff, 0x886644, 1.2);
    this.dir = new THREE.DirectionalLight(0xffffff, 2.2);
    this.scene.add(this.hemi, this.dir);
    this.mat = new THREE.MeshStandardMaterial({ color: skinHex, roughness: 0.62, metalness: 0 });
    this.left = this._hand(-1);
    this.right = this._hand(1);
    this.scene.add(this.left.root, this.right.root);
    this.curl = 0.15;
    this.t = 0;
    this.lagX = 0; this.lagY = 0;
    this.reach = 0;
    this._qi = new THREE.Quaternion();
    this._v = new THREE.Vector3();
  }

  setSkin(hex) { this.mat.color.set(hex); }

  // side: -1 левая, +1 правая; показываем тыльную сторону, большой палец смотрит внутрь
  _hand(side) {
    const root = new THREE.Group();
    const inner = new THREE.Group();
    root.add(inner);
    const palm = new THREE.Mesh(new THREE.SphereGeometry(0.08, 18, 14), this.mat);
    palm.scale.set(1.12, 0.78, 0.62);
    inner.add(palm);

    const fingers = FINGERS.map((f) => {
      const base = new THREE.Group();
      base.position.set(f.x, 0.052, 0);
      base.rotation.z = f.tilt * side;
      const seg1 = new THREE.Mesh(new THREE.CapsuleGeometry(0.0255, f.len * 0.55, 6, 10), this.mat);
      seg1.position.y = f.len * 0.32;
      const knuckle = new THREE.Group();
      knuckle.position.y = f.len * 0.65;
      const seg2 = new THREE.Mesh(new THREE.CapsuleGeometry(0.0235, f.len * 0.42, 6, 10), this.mat);
      seg2.position.y = f.len * 0.26;
      knuckle.add(seg2);
      base.add(seg1, knuckle);
      inner.add(base);
      return { base, knuckle };
    });

    const thumbBase = new THREE.Group();
    thumbBase.position.set(-side * 0.07, -0.005, 0.0);
    thumbBase.rotation.z = side * 0.95;
    const t1 = new THREE.Mesh(new THREE.CapsuleGeometry(0.03, 0.04, 6, 10), this.mat);
    t1.position.y = 0.03;
    const tk = new THREE.Group();
    tk.position.y = 0.062;
    const t2 = new THREE.Mesh(new THREE.CapsuleGeometry(0.027, 0.03, 6, 10), this.mat);
    t2.position.y = 0.022;
    tk.add(t2);
    thumbBase.add(t1, tk);
    inner.add(thumbBase);
    return { root, inner, fingers, thumb: { base: thumbBase, knuckle: tk }, side };
  }

  // st: { speed, phase, holding, grounded, lookDX, lookDY }
  update(dt, st, camera, atm, aspect) {
    this.t += dt;
    this.camera.aspect = aspect;
    this.camera.updateProjectionMatrix();

    // свет как в мире: цвета берём из атмосферы, направление солнца переводим в пространство камеры
    this.hemi.color.copy(atm.hemiSky);
    this.hemi.groundColor.copy(atm.hemiGround);
    this.hemi.intensity = atm.hemiIntensity * 0.95;
    this.dir.color.copy(atm.sunColor);
    this.dir.intensity = atm.sunIntensity * 0.55;
    this._qi.copy(camera.quaternion).invert();
    this.dir.position.copy(atm.sunDir).applyQuaternion(this._qi).multiplyScalar(4);

    const move = clamp(st.speed / 5, 0, 1.4);
    this.lagX = damp(this.lagX, clamp(-st.lookDX * 0.0009, -0.06, 0.06), 12, dt);
    this.lagY = damp(this.lagY, clamp(st.lookDY * 0.0009, -0.05, 0.05), 12, dt);
    const targetCurl = st.holding ? 1 : 0.12;
    this.curl = damp(this.curl, targetCurl, 14, dt);
    this.reach = damp(this.reach, st.reach ? 1 : 0, 16, dt);

    const bobY = Math.sin(st.phase * 2) * 0.012 * move + Math.sin(this.t * 1.7) * 0.003;
    const bobX = Math.sin(st.phase) * 0.01 * move;
    const air = st.grounded ? 0 : 0.03;

    for (const h of [this.left, this.right]) {
      const s = h.side;
      const holding = st.holding;
      const x = s * (holding ? 0.17 : 0.27) + bobX * s + this.lagX;
      const y = (holding ? -0.2 : -0.27) + bobY + this.lagY + air;
      const z = (holding ? -0.36 : -0.44) - (s > 0 ? this.reach * 0.14 : 0);
      h.root.position.set(x, y, z);
      h.root.rotation.set(
        -0.45 - (holding ? 0.35 : 0) - (s > 0 ? this.reach * 0.35 : 0),
        s * (holding ? -0.5 : -0.18),
        -s * (holding ? 0.3 : 0.12),
      );
      const c = this.curl;
      h.fingers.forEach((f, i) => {
        f.base.rotation.x = -c * 1.15 - i * 0.03 * c;
        f.knuckle.rotation.x = -c * 1.25;
      });
      h.thumb.base.rotation.x = -c * 0.6;
      h.thumb.knuckle.rotation.x = -c * 0.7;
    }
  }
}
