// Частицы погоды целиком на видеокарте: дождь, снег, осенние листья. Позиции считаются в вершинном
// шейдере (капля "падает" и заворачивается в коробку вокруг камеры), так что CPU ничего не делает.

import * as THREE from 'three';

const COMMON_VERT = `
  uniform float uTime;
  uniform vec3 uCam;
  uniform float uBox;
  uniform float uAmount;
  attribute vec3 aRand;
  varying float vAlpha;
  varying float vSeed;
  vec3 wrapPos(vec3 base, vec3 motion) {
    vec3 p = base * uBox + motion;
    vec3 rel = mod(p - uCam + 0.5 * uBox, uBox) - 0.5 * uBox;
    return uCam + rel;
  }
  float edgeFade(vec3 w) {
    vec3 r = w - uCam;
    float d = length(r.xz) / (0.5 * uBox);
    return (1.0 - smoothstep(0.7, 1.0, d)) * (1.0 - smoothstep(0.6, 1.0, abs(r.y) / (0.5 * uBox)));
  }
`;

function makeSystem({ count, box, mode, size, color, fall, drift }) {
  const rand = new Float32Array(count * 3 * (mode === 'lines' ? 2 : 1));
  const endFlag = new Float32Array(count * (mode === 'lines' ? 2 : 1));
  for (let i = 0; i < count; i++) {
    const r = [Math.random(), Math.random(), Math.random()];
    if (mode === 'lines') {
      for (let k = 0; k < 2; k++) {
        rand.set(r, (i * 2 + k) * 3);
        endFlag[i * 2 + k] = k;
      }
    } else rand.set(r, i * 3);
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(rand.length), 3)); // нужен для boundingSphere
  geo.setAttribute('aRand', new THREE.BufferAttribute(rand, 3));
  geo.setAttribute('aEnd', new THREE.BufferAttribute(endFlag, 1));

  const uniforms = {
    uTime: { value: 0 }, uCam: { value: new THREE.Vector3() }, uBox: { value: box }, uAmount: { value: 0 },
    uColor: { value: new THREE.Color(color) }, uSize: { value: size }, uScale: { value: 500 },
    uFall: { value: fall }, uDrift: { value: drift },
  };

  const vert = mode === 'lines'
    ? `${COMMON_VERT}
      uniform float uFall; uniform float uDrift;
      attribute float aEnd;
      void main() {
        float sp = uFall * (0.85 + 0.3 * aRand.x);
        vec3 top = wrapPos(aRand, vec3(uDrift * uTime * 0.4, -uTime * sp, 0.0));
        vec3 w = top + aEnd * vec3(0.05 * uDrift, 0.55 + 0.25 * aRand.y, 0.0);
        vAlpha = step(aRand.z, uAmount) * edgeFade(top) * mix(0.9, 0.25, aEnd);
        vSeed = aRand.x;
        gl_Position = projectionMatrix * viewMatrix * vec4(w, 1.0);
      }`
    : `${COMMON_VERT}
      uniform float uSize; uniform float uScale; uniform float uFall; uniform float uDrift;
      void main() {
        float ph = aRand.x * 40.0;
        vec3 motion = vec3(sin(uTime * 0.8 + ph) * uDrift, -uTime * uFall * (0.7 + 0.6 * aRand.y), cos(uTime * 0.6 + ph * 1.3) * uDrift);
        vec3 w = wrapPos(aRand, motion);
        vAlpha = step(aRand.z, uAmount) * edgeFade(w);
        vSeed = aRand.y;
        vec4 mv = viewMatrix * vec4(w, 1.0);
        gl_Position = projectionMatrix * mv;
        gl_PointSize = max(1.0, uSize * uScale / max(0.1, -mv.z) * (0.6 + 0.8 * aRand.x));
      }`;

  const frag = mode === 'lines'
    ? `uniform vec3 uColor; varying float vAlpha;
      void main() { if (vAlpha < 0.01) discard; gl_FragColor = vec4(uColor, vAlpha * 0.55); }`
    : mode === 'leaves'
      ? `uniform vec3 uColor; varying float vAlpha; varying float vSeed;
        void main() {
          if (vAlpha < 0.01) discard;
          vec2 p = gl_PointCoord - 0.5;
          if (abs(p.x) + abs(p.y) * 1.6 > 0.5) discard;
          vec3 c = mix(vec3(0.85, 0.35, 0.1), vec3(0.95, 0.72, 0.2), vSeed);
          c = mix(c, vec3(0.6, 0.2, 0.08), step(0.82, vSeed));
          gl_FragColor = vec4(c, vAlpha);
        }`
      : `uniform vec3 uColor; varying float vAlpha;
        void main() {
          if (vAlpha < 0.01) discard;
          float d = length(gl_PointCoord - 0.5);
          if (d > 0.5) discard;
          gl_FragColor = vec4(uColor, vAlpha * smoothstep(0.5, 0.15, d));
        }`;

  const mat = new THREE.ShaderMaterial({
    uniforms, vertexShader: vert, fragmentShader: frag, transparent: true, depthWrite: false, fog: false,
  });
  const obj = mode === 'lines' ? new THREE.LineSegments(geo, mat) : new THREE.Points(geo, mat);
  obj.frustumCulled = false;
  obj.visible = false;
  return { obj, uniforms };
}

export class Weather {
  constructor(quality = 1) {
    this.group = new THREE.Group();
    this.group.name = 'weather';
    const n = (v) => Math.round(v * quality);
    this.rain = makeSystem({ count: n(2600), box: 34, mode: 'lines', size: 1, color: '#cfe0f5', fall: 17, drift: 1.2 });
    this.snow = makeSystem({ count: n(2200), box: 38, mode: 'points', size: 0.055, color: '#ffffff', fall: 1.5, drift: 0.9 });
    this.leaves = makeSystem({ count: n(260), box: 52, mode: 'leaves', size: 0.16, color: '#ffffff', fall: 0.9, drift: 2.4 });
    this.group.add(this.rain.obj, this.snow.obj, this.leaves.obj);
  }

  update(time, camera, atm, viewportH) {
    const scale = viewportH / (2 * Math.tan((camera.fov * Math.PI) / 360));
    const set = (sys, amount) => {
      sys.obj.visible = amount > 0.02;
      const u = sys.uniforms;
      u.uTime.value = time;
      u.uAmount.value = amount;
      u.uCam.value.copy(camera.position);
      u.uScale.value = scale;
    };
    set(this.rain, atm.rain);
    set(this.snow, atm.snow);
    set(this.leaves, atm.leaves);
  }
}
