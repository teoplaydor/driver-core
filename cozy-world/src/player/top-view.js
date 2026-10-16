// Вид сверху (C1: cameraMode === 'top') — всё, что не камера:
//  • цель взаимодействия: ближайший предмет / дверь / объект мира (C7) в ~2.5 м, спереди — охотнее;
//    подсветка мягким светящимся кольцом на земле (HDR — подхватывает bloom) и подпись над целью;
//  • тап / клик по миру: по предмету, двери, игроку — подойти (простое рулевое управление) и взаимодействовать,
//    по земле — идти в точку с маркером; джойстик / WASD отменяют автодвижение;
//  • листва между камерой и игроком растворяется (C4), крыши над игроком плавно скрываются (C5);
//  • ники других игроков — чёткими HTML-подписями над головами (спрайт с 11 м мелковат).
// Props.pick() / nearestPortal() в виде сверху берут цель отсюда (targetProp / targetPortal).

import * as THREE from 'three';
import { clamp, damp } from '../util/math.js';
import { heightAt, isInterior, WATER_Y } from '../world/terrain-data.js';
import { AVATAR } from './avatar.js';
import { NAMES } from '../world/props.js';

const REACH = 2.5; // радиус поиска цели вокруг персонажа
const RING_R = 0.72; // радиус кольца в долях квада (см. шейдер)
const D2R = Math.PI / 180;
const STEER = [0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5]; // шаги отклонения от прямой (по 24°)

const _v = new THREE.Vector3();
const _v2 = new THREE.Vector3();
const _ndc = new THREE.Vector2();
const _sphere = new THREE.Sphere();
const _probe = { x: 0, z: 0 };

// Кольцо на земле: тонкая яркая линия + мягкое свечение, бегущие дуги и лёгкая заливка. Вершины придвинуты
// к камере вдоль луча взгляда (на экране кольцо не сдвигается) — травинки не перекрывают его.
function ringMaterial(color, intensity) {
  return new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color(color).multiplyScalar(intensity) },
      uTime: { value: 0 },
      uAlpha: { value: 1 },
      uDash: { value: 1 },
      uFill: { value: 0.12 },
      uWidth: { value: 1 },
    },
    vertexShader: /* glsl */`
      varying vec2 vUv;
      void main() {
        vUv = uv * 2.0 - 1.0;
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        mv.xyz *= max(0.05, 1.0 - 0.5 / max(length(mv.xyz), 0.6));
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: /* glsl */`
      uniform vec3 uColor;
      uniform float uTime, uAlpha, uDash, uFill, uWidth;
      varying vec2 vUv;
      void main() {
        float r = length(vUv);
        float e = fwidth(r) * 1.2;
        float w = 0.035 * uWidth;
        float line = 1.0 - smoothstep(w - e * 0.5, w + e, abs(r - ${RING_R}));
        float glow = exp(-pow((r - ${RING_R}) / 0.14, 2.0)) * 0.42;
        float a = fract(atan(vUv.y, vUv.x) * 0.47746 + uTime * 0.11); // три дуги по кругу
        float seg = smoothstep(0.0, 0.04, a) * (1.0 - smoothstep(0.46, 0.5, a));
        float dash = seg * (1.0 - smoothstep(0.03 - e, 0.03 + e, abs(r - 0.9))) * uDash;
        float fill = (1.0 - smoothstep(0.1, ${RING_R}, r)) * uFill;
        float al = clamp(line + glow + dash * 0.9 + fill, 0.0, 1.0) * uAlpha;
        if (al < 0.004) discard;
        gl_FragColor = vec4(uColor * (0.75 + 0.25 * line), al);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
    transparent: true,
    depthWrite: false,
  });
}

// Стрелка броска на земле: бегущие вперёд шевроны, длина — сила броска (пока держат ↗ / Q).
function aimMaterial() {
  return new THREE.ShaderMaterial({
    uniforms: { uColor: { value: new THREE.Color('#ffd28a').multiplyScalar(2.2) }, uTime: { value: 0 }, uAlpha: { value: 0 }, uLen: { value: 2 } },
    vertexShader: /* glsl */`
      varying vec2 vUv;
      void main() {
        vUv = uv;
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        mv.xyz *= max(0.05, 1.0 - 0.5 / max(length(mv.xyz), 0.6));
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: /* glsl */`
      uniform vec3 uColor;
      uniform float uTime, uAlpha, uLen;
      varying vec2 vUv;
      void main() {
        float x = abs(vUv.x - 0.5) * 2.0; // 0 по оси, 1 у края
        float y = vUv.y * uLen; // метры от начала
        // шевроны: «>» вдоль оси через каждые 0.55 м, бегут вперёд
        float c = fract((y + x * 0.32) / 0.55 - uTime * 1.6);
        float e = fwidth(c) * 1.5;
        float chev = smoothstep(0.0, e + 0.02, c) * (1.0 - smoothstep(0.24, 0.24 + e + 0.02, c));
        float edge = 1.0 - smoothstep(0.7, 1.0, x);
        float ends = smoothstep(0.0, 0.35, y) * (1.0 - smoothstep(uLen - 0.5, uLen, y));
        // наконечник: треугольник в последних 0.45 м
        float tip = step(uLen - 0.45, y) * (1.0 - smoothstep(0.0, 0.08, x - (uLen - y) / 0.45));
        float al = max(chev * edge * ends * 0.9, tip) * uAlpha;
        if (al < 0.004) discard;
        gl_FragColor = vec4(uColor, al);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
    transparent: true,
    depthWrite: false,
  });
}

function ringMesh(mat) {
  const g = new THREE.PlaneGeometry(2, 2);
  g.rotateX(-Math.PI / 2);
  const m = new THREE.Mesh(g, mat);
  m.renderOrder = 8;
  m.frustumCulled = false;
  m.visible = false;
  return m;
}

export class TopView {
  constructor(game) {
    this.game = game;
    this.target = null; // { type: 'prop'|'portal'|'it'|'player', obj, x, y, z, r, label }
    this._t = { type: null, obj: null, x: 0, y: 0, z: 0, r: 0.5, label: '' };
    this.walk = null; // автодвижение по тапу
    this._walk = { type: null, obj: null, x: 0, y: 0, z: 0, stop: 0.2, best: 1e9, stuckT: 0, side: 1, sx: 0, sz: 0 };
    this._move = { x: 0, z: 0, mag: 0, run: false };
    this.ray = new THREE.Ray();
    this.time = 0;
    this.active = false;
    this._hoverN = 0;

    // подсветка цели — тёплое золото; маркер точки — голубой (как кнопка броска)
    this.ringMat = ringMaterial('#ffc86b', 2.6);
    this.ring = ringMesh(this.ringMat);
    this.ring.name = 'top-target-ring';
    this.ringK = 0; // проявление 0..1
    this.ringPos = new THREE.Vector3();
    this.ringR = 0.5;
    this._below = false; // подпись цели под кольцом (над ним стоит свой персонаж)
    this.markMat = ringMaterial('#8fe3ff', 2.4);
    this.markMat.uniforms.uFill.value = 0.2;
    this.mark = ringMesh(this.markMat);
    this.mark.name = 'top-move-marker';
    this.markK = 0;
    this.markAge = 0;
    this.markPos = new THREE.Vector3();
    // «пин» над точкой: перевёрнутый конус, покачивается
    const pinGeo = new THREE.ConeGeometry(0.11, 0.26, 14, 1);
    pinGeo.rotateX(Math.PI);
    pinGeo.translate(0, 0.13, 0);
    this.pinMat = new THREE.MeshBasicMaterial({ color: new THREE.Color('#9fe8ff').multiplyScalar(2.2), transparent: true, depthWrite: false });
    this.pin = new THREE.Mesh(pinGeo, this.pinMat);
    this.pin.renderOrder = 8;
    this.pin.visible = false;
    // стрелка броска: квад 1×1, вытягивается по длине; начало — у ног персонажа
    const aimGeo = new THREE.PlaneGeometry(1, 1);
    aimGeo.rotateX(-Math.PI / 2);
    aimGeo.translate(0, 0, -0.5);
    this.aimMat = aimMaterial();
    this.aim = new THREE.Mesh(aimGeo, this.aimMat);
    this.aim.renderOrder = 8;
    this.aim.frustumCulled = false;
    this.aim.visible = false;
    this.aimK = 0;
    game.world.scene.add(this.ring, this.mark, this.pin, this.aim);

    // крыши (C5): ищем при каждом входе в режим (новые постройки подхватываются, известные — из кэша)
    this.roofs = null;
    this.indoor = false;
    this._shadowOnly = new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: false });
    this._names = []; // подписи ников (переиспользуемые объекты)
    this._box = new THREE.Box3();
    // проверка «крыша между камерой и игроком» по настоящей геометрии (рамка конуса крыши шире самой крыши)
    this._rc = new THREE.Raycaster();
    this._rcMesh = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ side: THREE.DoubleSide }));
    this._rcMesh.matrixAutoUpdate = false;
    this._hits = [];
  }

  get on() { return this.game.state === 'play' && this.game.player.cameraMode === 'top'; }

  // ---------- для Props (C1/C7) ----------

  targetProp() { return this.target?.type === 'prop' ? this.target.obj : null; }
  targetPortal() { return this.target?.type === 'portal' ? this.target.obj : null; }
  targetInteractable() { return this.target?.type === 'it' ? this.target.obj : null; }

  // ✋ / E в виде сверху: объект мира (C7) — здесь; предметы и двери — как обычно, через Props.use().
  use() {
    if (!this.on) return false;
    const it = this.game.props.holding ? null : this.targetInteractable();
    if (!it) return false;
    this.cancelWalk();
    it.use(this.game);
    return true;
  }

  // ---------- кадр ----------

  // До движения игрока: тапы, автодвижение, выбор цели.
  update(dt) {
    const g = this.game, on = this.on;
    this.time += dt;
    if (on !== this.active) this._setActive(on);
    if (!on) { g.input.taps.length = 0; return; }
    for (const t of g.input.taps) this.tap(t.x, t.y);
    g.input.taps.length = 0;
    this._walkStep(dt);
    this._pickTarget();
    this._hover();
  }

  // ПК: курсор-«рука» над тем, по чему можно кликнуть (предмет, дверь, игрок, объект мира); раз в 4 кадра.
  _hover() {
    const g = this.game, m = g.input.mouse;
    if (g.isTouch || (this._hoverN = (this._hoverN + 1) & 3)) return;
    let hot = false;
    if (m.on && !g.input.rotDrag) {
      this._setRay(m.x, m.y);
      const rect = g.canvas.getBoundingClientRect();
      hot = !!this._hitObject(m.x - rect.left, m.y - rect.top, rect, false);
    }
    const c = hot ? 'pointer' : '';
    if (g.canvas.style.cursor !== c) g.canvas.style.cursor = c;
  }

  // После анимации и предметов: подсветка, маркер, подпись, крыши, растворение листвы.
  lateUpdate(dt) {
    const g = this.game;
    if (!this.active) return;
    const pl = g.player, cam = g.camera;
    // C4: листва между камерой и головой персонажа
    _v.set(pl.pos.x, pl.pos.y + 1.1, pl.pos.z);
    if (!g.debugCam) g.world.setOcclusion?.(cam.position, _v, true);
    else g.world.setOcclusion?.(null, null, false);
    this._roofs(dt);
    this._drawTarget(dt);
    this._drawMarker(dt);
    this._drawAim(dt);
    this._drawNames();
  }

  // Ники других игроков: спрайт-вывеску прячем, вместо неё — HTML-подпись постоянного размера над головой
  // (вызывается после remotes.update — он каждый кадр заново включает спрайт).
  _drawNames() {
    const g = this.game, pl = g.player.pos, list = this._names;
    const W = window.innerWidth, H = window.innerHeight;
    let n = 0;
    for (const r of g.remotes.map.values()) {
      const av = r.avatar, p = av.root.position;
      av.tag.visible = false;
      if (g.debugCam) continue;
      const d = Math.hypot(p.x - pl.x, p.z - pl.z);
      if (d > 34) continue;
      _v.set(p.x, p.y + av.top + 0.3, p.z).project(g.camera);
      if (_v.z > 1 || _v.x < -1.1 || _v.x > 1.1 || _v.y < -1.1 || _v.y > 1.15) continue;
      const it = list[n] || (list[n] = { id: '', name: '', speaking: false, x: 0, y: 0, a: 1 });
      it.id = r.id; it.name = r.name; it.speaking = !!av.speaking;
      it.x = (_v.x * 0.5 + 0.5) * W; it.y = (-_v.y * 0.5 + 0.5) * H;
      it.a = clamp(1 - (d - 26) / 8, 0, 1);
      n++;
    }
    g.ui.setNameTags?.(list, n);
  }

  // Пока держат ↗ / Q с предметом в руках — стрелка на земле: куда и как далеко полетит.
  _drawAim(dt) {
    const g = this.game, pl = g.player, input = g.input, h = g.props.holding;
    const held = h && input.qDownAt > 0 && !g.props.pending ? (performance.now() - input.qDownAt) / 1000 : 0;
    const power = held > 0.3 ? Math.min(1, 0.25 + held * 0.75) : 0;
    this.aimK = damp(this.aimK, power > 0 ? 1 : 0, power > 0 ? 12 : 8, dt);
    const vis = this.aimK > 0.01 && !g.debugCam;
    this.aim.visible = vis;
    if (!vis) return;
    if (power > 0) this._aimLen = 1.4 + power * (h.k.glide ? 7 : 4.6);
    const len = this._aimLen || 2;
    const y = g.world.collisions.supportY(pl.pos.x, pl.pos.z, pl.pos.y + 0.3);
    this.aim.position.set(pl.pos.x - Math.sin(pl.yaw) * 0.45, y + 0.04, pl.pos.z - Math.cos(pl.yaw) * 0.45);
    this.aim.rotation.y = pl.yaw;
    this.aim.scale.set(0.62, 1, len);
    this.aimMat.uniforms.uLen.value = len;
    this.aimMat.uniforms.uAlpha.value = this.aimK;
    this.aimMat.uniforms.uTime.value = this.time;
  }

  _setActive(on) {
    const g = this.game;
    this.active = on;
    if (on) {
      this._scanRoofs();
    } else {
      this.cancelWalk(true);
      this.target = null;
      this.ring.visible = this.mark.visible = this.pin.visible = this.aim.visible = false;
      this.ringK = this.markK = 0;
      g.ui.setTargetLabel?.(null);
      g.world.setOcclusion?.(null, null, false);
      for (const r of this.roofs || []) this._roofAlpha(r, 1);
      this.indoor = false;
      g.player.topCam.indoor = false;
      g.ui.setNameTags?.(null, 0);
      g.canvas.style.cursor = '';
    }
  }

  // ---------- цель рядом с персонажем ----------

  _pickTarget() {
    const g = this.game, pl = g.player, props = g.props;
    const prev = this.target;
    this.target = null;
    if (pl.frozen || g.teleporting) return;
    // идём к объекту по тапу — он и есть цель
    const w = this.walk;
    if (w && w.type !== 'ground') { this.target = this._fill(w.type, w.obj); return; }
    if (props.holding || props.pending) return; // в руках предмет: ✋ — действие с ним (WS3)
    const px = pl.pos.x, py = pl.pos.y, pz = pl.pos.z;
    const fx = -Math.sin(pl.yaw), fz = -Math.cos(pl.yaw);
    let best = null, bestS = 1e9, bestType = null;
    const score = (x, y, z, extra) => this._score(pl, fx, fz, x, y, z, extra);
    for (const p of props.list) {
      if (p.held || (p.owner && p.owner !== 'me')) continue;
      const dx = p.pos.x - px, dz = p.pos.z - pz;
      if (dx * dx + dz * dz > REACH * REACH) continue;
      let s = score(p.pos.x, p.pos.y, p.pos.z, 0);
      if (prev?.obj === p) s *= 0.8; // не мигаем между соседями
      if (s < bestS) { best = p; bestS = s; bestType = 'prop'; }
    }
    for (const po of g.world.structures.portals) {
      const d = Math.hypot(po.x - px, po.z - pz);
      if (d > po.r || Math.abs(po.y - py) > 3) continue;
      let s = score(po.x, py + 0.6, po.z, po.r * 0.5) + 0.35;
      if (prev?.obj === po) s *= 0.8;
      if (s < bestS) { best = po; bestS = s; bestType = 'portal'; }
    }
    const reg = g.world.interactables;
    if (reg?.nearest) {
      _v.set(fx, 0, fz);
      const it = reg.nearest(pl.pos, _v);
      if (it && it.pos && (it.enabled?.() ?? true)) {
        const d = Math.hypot(it.pos.x - px, it.pos.z - pz);
        if (d <= Math.max(REACH, it.radius || 0)) {
          let s = score(it.pos.x, it.pos.y, it.pos.z, 0);
          if (prev?.obj === it) s *= 0.8;
          if (s < bestS) { best = it; bestS = s; bestType = 'it'; }
        }
      }
    }
    if (best) this.target = this._fill(bestType, best);
  }

  // Чем меньше, тем лучше: расстояние, спереди — охотнее (extra — «размер» цели: дверь широкая).
  _score(pl, fx, fz, x, y, z, extra) {
    const dx = x - pl.pos.x, dz = z - pl.pos.z, d = Math.hypot(dx, dz);
    if (Math.abs(y - pl.pos.y - 0.6) > 1.5) return 1e9;
    const c = d > 1e-3 ? (dx * fx + dz * fz) / d : 1;
    return Math.max(0, d - extra) * (1.4 - 0.4 * c);
  }

  // Описание цели (один переиспользуемый объект).
  _fill(type, obj) {
    const t = this._t, g = this.game;
    t.type = type; t.obj = obj;
    if (type === 'prop') {
      const sup = obj.k.r;
      t.x = obj.pos.x; t.z = obj.pos.z;
      t.y = g.world.collisions.supportY(obj.pos.x, obj.pos.z, obj.pos.y - sup * 0.5, 0.3, 0.02);
      t.top = obj.pos.y + sup + 0.25;
      t.r = Math.max(0.36, Math.min(0.75, obj.k.r + 0.22));
      t.label = `Взять ${NAMES[obj.kind] || 'предмет'}`;
    } else if (type === 'portal') {
      t.x = obj.x; t.z = obj.z; t.y = obj.y; t.top = obj.y + 2.4; t.r = 0.95;
      t.label = obj.label;
    } else if (type === 'player') {
      const p = obj.avatar.root.position;
      t.x = p.x; t.y = p.y; t.z = p.z; t.top = p.y + 2.1; t.r = 0.62;
      t.label = `Помахать: ${obj.name}`;
    } else {
      const p = obj.pos;
      t.x = p.x; t.z = p.z;
      t.y = g.world.collisions.supportY(p.x, p.z, p.y + 0.2);
      t.top = p.y + 0.6; t.r = clamp((obj.hit ?? 0.6) * 0.9, 0.45, 1.1);
      const reg = g.world.interactables;
      t.label = (reg?.label ? reg.label(obj) : typeof obj.label === 'function' ? obj.label() : obj.label) || 'Использовать';
    }
    return t;
  }

  _drawTarget(dt) {
    const g = this.game, t = this.target;
    const show = !!t && !g.debugCam;
    this.ringK = damp(this.ringK, show ? 1 : 0, show ? 14 : 10, dt);
    if (show) {
      // кольцо скользит к новой цели, а не прыгает
      const jump = this.ringK < 0.05 || this.ringPos.distanceToSquared(_v.set(t.x, t.y, t.z)) > 9;
      if (jump) { this.ringPos.set(t.x, t.y, t.z); this.ringR = t.r; } else {
        this.ringPos.x = damp(this.ringPos.x, t.x, 20, dt);
        this.ringPos.y = damp(this.ringPos.y, t.y, 20, dt);
        this.ringPos.z = damp(this.ringPos.z, t.z, 20, dt);
        this.ringR = damp(this.ringR, t.r, 16, dt);
      }
    }
    const vis = this.ringK > 0.01;
    this.ring.visible = vis;
    if (vis) {
      const breathe = 1 + Math.sin(this.time * 4.2) * 0.04;
      this.ring.position.set(this.ringPos.x, this.ringPos.y + 0.03, this.ringPos.z);
      this.ring.scale.setScalar((this.ringR / RING_R) * breathe * (0.75 + 0.25 * this.ringK));
      this.ringMat.uniforms.uAlpha.value = this.ringK;
      this.ringMat.uniforms.uTime.value = this.time;
    }
    // подпись над целью (HTML: чёткий текст и шрифт интерфейса); если над целью стоит свой персонаж
    // (он дальше от камеры — на экране выше), подпись уходит под кольцо, чтобы его не закрывать
    if (show && this.ringK > 0.3) {
      const W = window.innerWidth, H = window.innerHeight;
      _v.set(t.x, t.top, t.z).project(g.camera);
      if (_v.z < 1) {
        let x = (_v.x * 0.5 + 0.5) * W, y = (-_v.y * 0.5 + 0.5) * H;
        const size = g.ui.targetLabelSize?.(t.label) || { w: 160, h: 34 };
        const pl = g.player.pos;
        _v.set(pl.x, pl.y + 1.35, pl.z).project(g.camera);
        const hx = (_v.x * 0.5 + 0.5) * W, hy = (-_v.y * 0.5 + 0.5) * H;
        _v.set(pl.x, pl.y + 0.1, pl.z).project(g.camera);
        const fy = (-_v.y * 0.5 + 0.5) * H;
        // рамка подписи (якорь — низ по центру, +хвостик) против рамки персонажа на экране; запас m — чтобы
        // подпись не прыгала туда-сюда на границе
        const m = this._below ? 14 : 0;
        const below = this._below = Math.abs(hx - x) < size.w / 2 + 16 + m && hy - 8 - m < y && fy + 4 + m > y - size.h - 10;
        if (below) {
          _v.set(t.x, t.y, t.z).project(g.camera);
          x = (_v.x * 0.5 + 0.5) * W;
          y = (-_v.y * 0.5 + 0.5) * H + this.ringR * 22 + 12;
        }
        g.ui.setTargetLabel?.(t.label, g.isTouch ? '✋' : 'E', x, y, below);
        return;
      }
    }
    g.ui.setTargetLabel?.(null);
  }

  // ---------- тап / клик по миру ----------

  // x, y — CSS-пиксели в окне.
  tap(x, y) {
    const g = this.game;
    if (g.player.frozen || g.teleporting) return;
    const rect = this._setRay(x, y);
    const hit = this._hitObject(x - rect.left, y - rect.top, rect);
    if (hit) { this.startWalk(hit.type, hit.obj); return; }
    if (this._hitGround(_v2)) this.startWalk('ground', null, _v2.x, _v2.y, _v2.z);
  }

  // Луч из камеры через точку экрана (CSS-пиксели окна).
  _setRay(x, y) {
    const g = this.game, cam = g.camera;
    const rect = g.canvas.getBoundingClientRect();
    _ndc.set(((x - rect.left) / rect.width) * 2 - 1, -((y - rect.top) / rect.height) * 2 + 1);
    cam.updateMatrixWorld();
    this.ray.origin.setFromMatrixPosition(cam.matrixWorld);
    this.ray.direction.set(_ndc.x, _ndc.y, 0.5).unproject(cam).sub(this.ray.origin).normalize();
    return rect;
  }

  // Ближайший по лучу предмет / дверь / игрок / объект мира; промах — ближайший на экране в пределах ~38 px
  // (loose = false — только точное попадание: для курсора на ПК).
  _hitObject(sx, sy, rect, loose = true) {
    const g = this.game, ray = this.ray, pl = g.player;
    let best = null, bestT = 1e9;
    let near = null, nearD = 38 * 38;
    const o = ray.origin;
    const test = (type, obj, x, y, z, r) => {
      if ((x - pl.pos.x) ** 2 + (z - pl.pos.z) ** 2 > 60 * 60) return;
      _sphere.center.set(x, y, z);
      _sphere.radius = r;
      if (ray.intersectSphere(_sphere, _v)) {
        const t = _v.distanceTo(o);
        if (t < bestT) { bestT = t; best = { type, obj }; }
      } else if (!best && loose) {
        _v.set(x, y, z).project(g.camera);
        if (_v.z > 1) return;
        const px = (_v.x * 0.5 + 0.5) * rect.width, py = (-_v.y * 0.5 + 0.5) * rect.height;
        const d = (px - sx) ** 2 + (py - sy) ** 2;
        if (d < nearD) { nearD = d; near = { type, obj }; }
      }
    };
    for (const p of g.props.list) {
      if (p.held) continue;
      if (p.kind === 'bat' || p.kind === 'umbrella') {
        for (let i = -1; i <= 1; i++) {
          _v2.set(0, i * 0.3, 0).applyQuaternion(p.quat).add(p.pos);
          test('prop', p, _v2.x, _v2.y, _v2.z, 0.22);
        }
      } else test('prop', p, p.pos.x, p.pos.y, p.pos.z, Math.max(0.24, p.k.r + 0.12));
    }
    for (const po of g.world.structures.portals) test('portal', po, po.x, po.y + 1.2, po.z, 1.25);
    for (const r of g.remotes.map.values()) {
      const p = r.avatar.root.position;
      test('player', r, p.x, p.y + 0.75, p.z, 0.62);
    }
    for (const it of this._interactables()) if (it.pos && (it.enabled?.() ?? true)) test('it', it, it.pos.x, it.pos.y, it.pos.z, Math.max(0.4, it.hit ?? Math.min(1.2, (it.radius || 1) * 0.5)));
    return best || near;
  }

  // Реестр объектов мира (C7) — перебор, если реестр это позволяет.
  _interactables() {
    const reg = this.game.world.interactables;
    if (!reg) return [];
    const src = reg.items ?? reg.list ?? reg.map ?? (typeof reg[Symbol.iterator] === 'function' ? reg : null);
    if (!src) return [];
    if (src instanceof Map) return src.values();
    return src;
  }

  // Точка на земле, полу или верхе постройки под лучом (рельеф — шагами с уточнением).
  _hitGround(out) {
    const g = this.game, o = this.ray.origin, d = this.ray.direction;
    let tGround = 1e9;
    if (isInterior(o.x)) {
      if (d.y < -1e-4) tGround = -o.y / d.y;
    } else {
      let prev = 0;
      const above = (t) => { const y = o.y + d.y * t; return y > Math.max(heightAt(o.x + d.x * t, o.z + d.z * t), WATER_Y); };
      for (let t = 0.5; t < 260; t += t < 30 ? 0.5 : 1.5) {
        if (!above(t)) {
          let a = prev, b = t;
          for (let i = 0; i < 8; i++) { const m = (a + b) / 2; if (above(m)) a = m; else b = m; }
          tGround = b;
          break;
        }
        prev = t;
      }
    }
    // постройки: столы, полы, стены (коллизии-прямоугольники)
    let tBox = tGround, side = false;
    for (const b of g.world.collisions.boxes) {
      const ox = o.x - b.x, oz = o.z - b.z;
      const ax = ox * b.c - oz * b.s, az = ox * b.s + oz * b.c;
      const bx = d.x * b.c - d.z * b.s, bz = d.x * b.s + d.z * b.c;
      let t0 = 0, t1 = tBox, face = 0;
      const slab = (p, v, h, f) => {
        if (Math.abs(v) < 1e-8) return Math.abs(p) <= h;
        let a = (-h - p) / v, c = (h - p) / v;
        if (a > c) { const s = a; a = c; c = s; }
        if (a > t0) { t0 = a; face = f; }
        if (c < t1) t1 = c;
        return t0 <= t1;
      };
      if (!slab(ax, bx, b.hx, 1) || !slab(az, bz, b.hz, 1) || !slab(o.y - (b.y0 + b.y1) / 2, d.y, (b.y1 - b.y0) / 2, 2)) continue;
      if (t0 < tBox) { tBox = t0; side = face === 1; }
    }
    if (tBox >= 1e9) return false;
    out.copy(o).addScaledVector(d, tBox);
    if (side || tBox < tGround) {
      // стена или бок стола: идём к его подножию; верх — встаём на него, если невысоко
      const gy = g.world.collisions.supportY(out.x, out.z, out.y + 0.05);
      out.y = side ? (isInterior(out.x) ? 0 : heightAt(out.x, out.z)) : gy;
    }
    return true;
  }

  // ---------- автодвижение ----------

  startWalk(type, obj, x = 0, y = 0, z = 0) {
    const g = this.game, w = this._walk;
    w.type = type; w.obj = obj;
    w.best = 1e9; w.stuckT = 0;
    const pl = g.player;
    w.sx = -Math.sin(pl.yaw); w.sz = -Math.cos(pl.yaw);
    if (type === 'ground') {
      w.x = x; w.y = y; w.z = z; w.stop = 0.22;
      this.markPos.set(x, y, z);
      this.markAge = 0;
    } else {
      const t = this._fill(type, obj);
      w.x = t.x; w.y = t.y; w.z = t.z;
      w.stop = type === 'prop' ? 0.7 + obj.k.r : type === 'portal' ? Math.min(1.1, obj.r * 0.55) : type === 'player' ? 1.5 : Math.min(1.2, (obj.radius || 2) * 0.6);
    }
    this.walk = w;
    g.audio.click?.(_v.set(w.x, w.y, w.z));
  }

  cancelWalk(silent = false) {
    if (!this.walk) return;
    this.walk = null;
    this.game.player.autoMove = null;
    void silent;
  }

  _walkStep(dt) {
    const g = this.game, pl = g.player, input = g.input, w = this.walk;
    if (!w) { pl.autoMove = null; return; }
    const ax = input.axes();
    if (Math.hypot(ax.x, ax.y) > 0.15 || pl.frozen) { this.cancelWalk(); return; } // джойстик / WASD главнее
    // цель могла сдвинуться (мяч покатился, игрок ушёл) или пропасть
    if (w.type === 'prop') {
      if (w.obj.held && w.obj !== g.props.holding) { this.cancelWalk(); return; }
      w.x = w.obj.pos.x; w.z = w.obj.pos.z;
    } else if (w.type === 'player') {
      if (!g.remotes.map.has(w.obj.id)) { this.cancelWalk(); return; }
      const p = w.obj.avatar.root.position;
      w.x = p.x; w.z = p.z;
    } else if (w.type === 'it') {
      w.x = w.obj.pos.x; w.z = w.obj.pos.z;
    }
    const dx = w.x - pl.pos.x, dz = w.z - pl.pos.z, d = Math.hypot(dx, dz);
    if (d <= w.stop) { this._arrive(w, dx, dz); return; }
    // застряли: за 2 с не приблизились ни на 15 см
    if (d < w.best - 0.15) { w.best = d; w.stuckT = 0; } else w.stuckT += dt;
    if (w.stuckT > 2) {
      this.cancelWalk();
      g.ui.toast?.('Туда не пройти', 1400);
      return;
    }
    // рулевое управление: прямо, а если впереди препятствие — отклоняемся в ту же сторону, что и раньше
    const base = Math.atan2(dx, dz);
    const look = Math.min(1.15, d);
    let ang = null;
    for (const k of STEER) {
      const a = base + (k === 0 ? 0 : Math.sign(k) * w.side * Math.abs(k) * 24 * D2R);
      if (this._free(pl.pos.x + Math.sin(a) * look * 0.5, pl.pos.z + Math.cos(a) * look * 0.5, pl.pos.y)
        && this._free(pl.pos.x + Math.sin(a) * look, pl.pos.z + Math.cos(a) * look, pl.pos.y)) {
        ang = a;
        if (k !== 0 && Math.abs(k) === 1) w.side = Math.sign(k) * w.side;
        break;
      }
    }
    if (ang === null) ang = base;
    // направление сглаживаем — без рывков на краях препятствий
    const kx = 1 - Math.exp(-12 * dt);
    w.sx += (Math.sin(ang) - w.sx) * kx;
    w.sz += (Math.cos(ang) - w.sz) * kx;
    const sl = Math.hypot(w.sx, w.sz) || 1;
    const m = this._move;
    m.x = w.sx / sl; m.z = w.sz / sl;
    m.mag = w.type === 'ground' ? clamp(d / 1.1, 0.3, 1) : clamp((d - w.stop) / 0.8 + 0.35, 0.35, 1);
    m.run = d > 8;
    pl.autoMove = m;
  }

  // Можно ли встать в точку: стены и стволы, слишком высокая ступень, глубокая вода.
  _free(x, z, y) {
    const col = this.game.world.collisions;
    _probe.x = x; _probe.z = z;
    col.resolve(_probe, AVATAR.radius * 0.85, y + 0.1, AVATAR.height - 0.2);
    if (Math.abs(_probe.x - x) + Math.abs(_probe.z - z) > 0.03) return false;
    if (!isInterior(x) && heightAt(x, z) < WATER_Y - 0.9) return false;
    return col.supportY(x, z, y) - y < 0.6;
  }

  _arrive(w, dx, dz) {
    const g = this.game, pl = g.player;
    this.walk = null;
    pl.autoMove = null;
    if (w.type !== 'ground') pl.faceYaw = Math.atan2(-dx, -dz);
    if (w.type === 'prop') {
      const p = w.obj;
      if (!g.props.holding && !p.held && (!p.owner || p.owner === 'me')) g.props.grab(p);
    } else if (w.type === 'portal') {
      g.teleport(w.obj.to);
    } else if (w.type === 'it') {
      if (w.obj.enabled?.() ?? true) w.obj.use(g);
    } else if (w.type === 'player') {
      g.input.press('emote'); // помахать (как X / 👋)
    }
  }

  _drawMarker(dt) {
    const w = this.walk, on = !!w && w.type === 'ground' && !this.game.debugCam;
    this.markAge += dt;
    this.markK = damp(this.markK, on ? 1 : 0, on ? 16 : 7, dt);
    const vis = this.markK > 0.01;
    this.mark.visible = this.pin.visible = vis;
    if (!vis) return;
    const p = this.markPos;
    // «пинг» при появлении: кольцо сжимается к точке, потом мягко дышит
    const ping = Math.exp(-this.markAge * 7);
    const s = (0.32 / RING_R) * (1 + ping * 1.4 + Math.sin(this.time * 5) * 0.05);
    this.mark.position.set(p.x, p.y + 0.03, p.z);
    this.mark.scale.setScalar(s);
    this.markMat.uniforms.uAlpha.value = this.markK;
    this.markMat.uniforms.uTime.value = -this.time;
    this.markMat.uniforms.uDash.value = 0.6;
    this.pin.position.set(p.x, p.y + 0.42 + Math.abs(Math.sin(this.time * 3.4)) * 0.16, p.z);
    this.pin.rotation.y = this.time * 2;
    this.pin.scale.setScalar(0.6 + 0.4 * this.markK);
    this.pinMat.opacity = this.markK;
  }

  // ---------- крыши (C5) ----------

  _scanRoofs() {
    const known = new Map((this.roofs || []).map((r) => [r.mesh, r]));
    const list = this.roofs = [];
    const fresh = [];
    this.game.world.scene.traverse((o) => {
      if (!o.isMesh || !o.userData.roof) return;
      let r = known.get(o);
      if (!r) {
        const a = o.userData.roofArea;
        o.updateWorldMatrix(true, false);
        this._box.setFromObject(o);
        r = {
          mesh: o, base: o.material, fade: null, k: 1,
          shadow: Array.isArray(o.material) ? o.material.map(() => this._shadowOnly) : this._shadowOnly,
          area: a || { x0: this._box.min.x, x1: this._box.max.x, z0: this._box.min.z, z1: this._box.max.z },
          min: this._box.min.clone(), max: this._box.max.clone(),
          hall: false,
        };
        // большой зал (арена): скрытая крыша не оставляет тени — внутри светло, как во дворе;
        // навес (беседка) оставляет — видно, что над головой крыша
        r.hall = (r.area.x1 - r.area.x0) * (r.area.z1 - r.area.z0) > 120;
        // плавное растворение — полупрозрачной копией материала (с теми же патчами шейдера)
        const mk = (m) => {
          const c = m.clone();
          c.onBeforeCompile = m.onBeforeCompile;
          c.customProgramCacheKey = m.customProgramCacheKey;
          c.transparent = true;
          c.depthWrite = false;
          return c;
        };
        r.fade = Array.isArray(r.base) ? r.base.map(mk) : mk(r.base);
        fresh.push(r);
      }
      list.push(r);
    });
    if (!fresh.length) return;
    // прогрев шейдеров копий, чтобы первое растворение не подвисало
    const tmp = new THREE.Group();
    for (const r of fresh) tmp.add(new THREE.Mesh(r.mesh.geometry, r.fade));
    const g = this.game;
    try { g.renderer.compileAsync?.(tmp, g.camera, g.world.scene)?.catch?.(() => {}); } catch { /* не критично */ }
  }

  _roofs(dt) {
    const g = this.game, pl = g.player, p = pl.pos, cam = g.camera.position;
    const snap = pl.topCam.justSnapped; // вход в режим, телепорт — без растворения
    let indoor = isInterior(p.x);
    for (const r of this.roofs || []) {
      const a = r.area, m = 0.25;
      const under = p.x > a.x0 - m && p.x < a.x1 + m && p.z > a.z0 - m && p.z < a.z1 + m && p.y + 0.6 < r.max.y;
      // крыша между камерой и игроком (стоим за беседкой) — тоже прячем
      const between = !under && this._segRoof(cam, p, r);
      const hide = under || between;
      if (under && r.hall) indoor = true;
      let k = snap ? (hide ? 0 : 1) : damp(r.k, hide ? 0 : 1, hide ? 11 : 6, dt);
      if (k < 0.03) k = 0; else if (k > 0.985) k = 1;
      this._roofAlpha(r, k);
    }
    this.indoor = indoor;
    pl.topCam.indoor = indoor && !isInterior(p.x);
  }

  // Отрезок камера -> голова игрока пересекает крышу? Сначала рамка, потом треугольники.
  _segRoof(c, p, r) {
    if (!this._segBox(c, p, r)) return false;
    const rc = this._rc, m = this._rcMesh;
    rc.ray.origin.set(p.x, p.y + 1.1, p.z);
    rc.ray.direction.subVectors(c, rc.ray.origin);
    rc.far = rc.ray.direction.length();
    rc.ray.direction.normalize();
    m.geometry = r.mesh.geometry;
    m.matrixWorld.copy(r.mesh.matrixWorld);
    this._hits.length = 0;
    m.raycast(rc, this._hits);
    return this._hits.length > 0;
  }

  _segBox(c, p, r) {
    const ax = p.x, ay = p.y + 1.1, az = p.z;
    const dx = c.x - ax, dy = c.y - ay, dz = c.z - az;
    let t0 = 0, t1 = 1;
    const lo = r.min, hi = r.max;
    for (let i = 0; i < 3; i++) {
      const o = i === 0 ? ax : i === 1 ? ay : az, v = i === 0 ? dx : i === 1 ? dy : dz;
      const l = i === 0 ? lo.x : i === 1 ? lo.y : lo.z, h = i === 0 ? hi.x : i === 1 ? hi.y : hi.z;
      if (Math.abs(v) < 1e-8) { if (o < l || o > h) return false; continue; }
      let a = (l - o) / v, b = (h - o) / v;
      if (a > b) { const s = a; a = b; b = s; }
      if (a > t0) t0 = a;
      if (b < t1) t1 = b;
      if (t0 > t1) return false;
    }
    return true;
  }

  // Прозрачность крыши: 1 — обычный материал, 0 — только тень (у навеса) или ничего (у зала), между —
  // полупрозрачная копия (тень при этом остаётся полной).
  _roofAlpha(r, k) {
    r.k = k;
    const mesh = r.mesh;
    // видимость трогаем только свою: LOD построек может прятать меши сам
    const hidden = k <= 0 && r.hall;
    if (hidden !== !!r.hid) { r.hid = hidden; mesh.visible = !hidden; }
    if (k >= 1) { if (mesh.material !== r.base) mesh.material = r.base; return; }
    if (k <= 0 || !r.fade) {
      if (mesh.material !== r.shadow) mesh.material = r.shadow;
      return;
    }
    if (mesh.material !== r.fade) mesh.material = r.fade;
    const base = Array.isArray(r.base) ? r.base : null;
    if (Array.isArray(r.fade)) r.fade.forEach((f, i) => { f.opacity = k * (base[i].opacity ?? 1); });
    else r.fade.opacity = k * (r.base.opacity ?? 1);
  }
}
