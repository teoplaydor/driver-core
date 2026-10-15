// Небесный купол с градиентом, солнцем и облаками. Низ купола сливается с цветом тумана,
// поэтому дальние горы растворяются в дымке без шва на горизонте.

import * as THREE from 'three';
import { mulberry32 } from '../util/math.js';

const SKY_RADIUS = 450;

// Мультяшное кучевое облако: пухлая шапка, голубоватый низ, плоское основание.
function cumulusTexture(seed) {
  const W = 256, H = 128;
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const g = c.getContext('2d');
  const rand = mulberry32(seed);
  const puff = (x, y, r) => {
    const grad = g.createRadialGradient(x, y, 0, x, y, r);
    grad.addColorStop(0, 'rgba(255,255,255,1)');
    grad.addColorStop(0.62, 'rgba(255,255,255,0.96)');
    grad.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = grad;
    g.beginPath();
    g.arc(x, y, r, 0, Math.PI * 2);
    g.fill();
  };
  const n = 7 + Math.floor(rand() * 3);
  for (let i = 0; i < n; i++) {
    const t = (i + 0.5) / n;
    const bump = Math.sin(t * Math.PI);
    const r = 15 + bump * 27 + rand() * 8;
    puff(W * (0.12 + 0.76 * t), H * 0.72 - r * 0.45 - bump * 14 * rand(), r);
  }
  g.globalCompositeOperation = 'source-atop';
  const sh = g.createLinearGradient(0, H * 0.3, 0, H * 0.78);
  sh.addColorStop(0, 'rgba(170,192,225,0)');
  sh.addColorStop(1, 'rgba(135,160,205,0.8)');
  g.fillStyle = sh;
  g.fillRect(0, 0, W, H);
  g.globalCompositeOperation = 'destination-in';
  const cut = g.createLinearGradient(0, H * 0.66, 0, H * 0.82);
  cut.addColorStop(0, 'rgba(0,0,0,1)');
  cut.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = cut;
  g.fillRect(0, 0, W, H);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

export class Sky {
  constructor() {
    this.uniforms = {
      uTop: { value: new THREE.Color('#4a98e0') },
      uMid: { value: new THREE.Color('#8cc5f2') },
      uFog: { value: new THREE.Color('#cfe4f3') },
      uSunColor: { value: new THREE.Color('#ffe3b0') },
      uSunDir: { value: new THREE.Vector3(0, 1, 0) },
      uDim: { value: 0 }, // 1 = в помещении, небо гасим
    };
    const mat = new THREE.ShaderMaterial({
      uniforms: this.uniforms,
      side: THREE.BackSide,
      depthWrite: false,
      fog: false,
      vertexShader: `
        varying vec3 vDir;
        void main() {
          vDir = position;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }`,
      fragmentShader: `
        uniform vec3 uTop, uMid, uFog, uSunColor, uSunDir;
        uniform float uDim;
        varying vec3 vDir;
        void main() {
          vec3 d = normalize(vDir);
          float y = d.y;
          vec3 col = mix(uFog, uMid, smoothstep(0.0, 0.2, y));
          col = mix(col, uTop, smoothstep(0.1, 0.62, y));
          col = mix(col, uFog, smoothstep(0.03, -0.1, y));
          float s = max(dot(d, uSunDir), 0.0);
          col += uSunColor * (pow(s, 700.0) * 3.0 + pow(s, 28.0) * 0.32 + pow(s, 5.0) * 0.1);
          col = mix(col, uFog * 0.15, uDim);
          gl_FragColor = vec4(col, 1.0);
          #include <tonemapping_fragment>
          #include <colorspace_fragment>
        }`,
    });
    this.dome = new THREE.Mesh(new THREE.SphereGeometry(SKY_RADIUS, 32, 16), mat);
    this.dome.frustumCulled = false;
    this.dome.renderOrder = -10;
    this.dome.name = 'sky';

    this.clouds = new THREE.Group();
    const texes = [cumulusTexture(1), cumulusTexture(2), cumulusTexture(3)];
    const rand = mulberry32(99);
    this.cloudMats = texes.map((map) => new THREE.SpriteMaterial({
      map, transparent: true, depthWrite: false, fog: false, opacity: 0.95,
    }));
    for (let i = 0; i < 26; i++) {
      const ang = rand() * Math.PI * 2;
      const dist = 200 + rand() * 190;
      const sp = new THREE.Sprite(this.cloudMats[i % 3]);
      const w = 110 + rand() * 120;
      sp.scale.set(w, w * 0.5, 1);
      sp.position.set(Math.cos(ang) * dist, 55 + rand() * 75, Math.sin(ang) * dist);
      this.clouds.add(sp);
    }
    this.clouds.renderOrder = -9;
    this.cloudTint = new THREE.Color();
  }

  // atm - состояние из Atmosphere.
  update(camera, atm, time) {
    this.dome.position.copy(camera.position);
    this.clouds.position.set(camera.position.x, 0, camera.position.z);
    this.clouds.rotation.y = time * 0.004;

    const u = this.uniforms;
    u.uTop.value.copy(atm.skyTop);
    u.uMid.value.copy(atm.skyMid);
    u.uFog.value.copy(atm.fogColor);
    u.uSunColor.value.copy(atm.sunColor);
    u.uSunDir.value.copy(atm.sunDir);
    u.uDim.value = atm.indoor;

    this.cloudTint.setRGB(1, 1, 1).lerp(atm.skyMid, 0.12).lerp(atm.sunColor, 0.18).multiplyScalar(1 - 0.5 * atm.rain);
    for (const m of this.cloudMats) {
      m.color.copy(this.cloudTint);
      m.opacity = 0.95 * (1 - atm.indoor);
    }
    this.clouds.visible = atm.indoor < 0.95;
  }
}
