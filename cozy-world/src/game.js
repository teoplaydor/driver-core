// Главный класс клиента: собирает мир, игрока, сеть и интерфейс и крутит цикл кадров.
// Режимы: 'menu' (экран входа, камера смотрит на персонажа) и 'play'.

import * as THREE from 'three';
import { QUALITY, detectQuality, isTouchDevice, isSoftwareGL } from './config.js';
import { World } from './world/world.js';
import { heightAt } from './world/terrain-data.js';
import { Player } from './player/player.js';
import { Avatar, SKINS, lookToArray, sleeveColor, holdToCode } from './player/avatar.js';
import { Hands } from './player/hands.js';
import { Input, TouchControls } from './player/input.js';
import { UI } from './ui/ui.js';
import { Net } from './net/client.js';
import { RemotePlayers } from './net/remote.js';
import { Props } from './world/props.js';
import { AudioEngine } from './audio/engine.js';
import { Voice } from './net/voice.js';
import { DemoBots } from './demo-bots.js';
import { clamp, TAU } from './util/math.js';
import { SPAWN } from '../shared/layout.js';
import { PostFX, postSupported } from './render/post.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const randomRoom = () => Math.random().toString(36).slice(2, 7);

export class Game {
  constructor() {
    const params = this.params = new URLSearchParams(location.search);
    this.qualityName = QUALITY[params.get('q')] ? params.get('q') : detectQuality();
    this.quality = QUALITY[this.qualityName];
    this.isTouch = isTouchDevice() || params.has('touch');
    document.body.classList.toggle('touch', this.isTouch);

    const canvas = this.canvas = document.getElementById('game');
    // WS4: с постобработкой сглаживание делает SMAA/FXAA, MSAA экранного буфера не нужен
    const wantPost = !!this.quality.post && params.get('post') !== '0';
    const r = this.renderer = new THREE.WebGLRenderer({ canvas, antialias: !wantPost, powerPreference: 'high-performance' });
    this.dpr = params.has('dpr') ? Number(params.get('dpr')) : Math.min(window.devicePixelRatio || 1, this.quality.dpr);
    r.setPixelRatio(this.dpr);
    r.shadowMap.enabled = this.quality.shadow > 0;
    r.shadowMap.type = THREE.PCFSoftShadowMap;
    r.toneMapping = params.get('tm') === 'aces' ? THREE.ACESFilmicToneMapping : THREE.NeutralToneMapping;
    // WS4: счётчики draw calls - за весь кадр (мир + руки + проходы постобработки), сброс в начале кадра
    r.info.autoReset = false;
    const q = this.quality;
    this.post = wantPost && postSupported(r) ? new PostFX(r, {
      ao: !!q.ao, aoSamples: q.aoSamples, bloomLevels: q.bloomLevels, bloomStart: q.bloomStart, aa: q.aa, water: !!q.waterDeferred,
    }) : null;
    // адаптивное разрешение: при долгих кадрах снижаем pixelRatio ступенями до 0.6x, при запасе - возвращаем
    this.resScale = 1;
    this.adaptive = !params.has('dpr') && !isSoftwareGL(); // на программном GL всё медленно - подстраивать нечего
    this._slowT = 0; this._fastT = 0; this._ftAvg = 16;

    this.camera = new THREE.PerspectiveCamera(78, 1, 0.05, 520);
    this.camera.rotation.order = 'YXZ';
    this.world = new World(r, this.quality, { post: !!this.post });

    this.ui = new UI();
    this.input = new Input(canvas);
    this.input.isTouch = this.isTouch;
    this.touch = new TouchControls(document.getElementById('touch'), this.input);
    this.player = new Player(this.world.collisions);
    this.player.spawn(SPAWN.x, SPAWN.z, SPAWN.yaw);

    this.look = { ...this.ui.look };
    this.nick = '';
    Avatar.lodDistance = { high: 9, medium: 5.5, low: 4 }[this.qualityName] ?? 9; // WS1: детализация персонажей
    Avatar.shadows = this.quality.shadow > 0;
    this.avatar = new Avatar(this.look, { name: '', local: true });
    this.world.scene.add(this.avatar.root);
    // WS1: стопы аватаров ставятся на рельеф и верх ящиков/столов
    const ground = (x, z, y) => this.world.collisions.supportY(x, z, y, 0.5);
    this.avatar.setGround(ground);
    this.hands = new Hands(SKINS[this.look.skin]);
    this.hands.setSleeve(sleeveColor(this.look));
    this.remotes = new RemotePlayers(this.world.scene, ground);
    this.holding = null;
    this.audio = new AudioEngine();
    // браузер разрешает звук только после действия пользователя
    const unlock = () => this.audio.start();
    window.addEventListener('pointerdown', unlock, { once: true });
    window.addEventListener('keydown', unlock, { once: true });
    this.player.events.step = (ground, sprint) => this.audio.footstep(ground, sprint);
    this.player.events.jump = () => this.audio.jump();
    this.player.events.land = (power) => this.audio.land(power);
    this.voice = new Voice(this);
    this.onToggleMic = () => {
      if (this.online) this.voice.toggle();
      else this.ui.toast('Голос работает, когда ты в комнате на сервере');
    };
    this.onRtc = (m) => this.voice.onSignal(m);
    this.props = new Props(this);

    this.net = null;
    this.online = false;
    this.roomCode = '';
    this._sendT = 0;
    this._lastState = null;
    this._lastSentAt = 0;

    this.state = 'menu';
    this.time = 0;
    this.menuT = 0;
    this.frames = 0;
    this.reachT = 0;
    this.speakingLocal = false;

    this.ui.onLookChange = (look) => this.setLook(look);
    this.ui.onPlay = (o) => this.tryJoin(o);
    this.ui.onChat = (text) => this.sendChat(text);
    this.ui.onCopy = () => this.copyLink();
    this.ui.onResume = () => this.input.requestLock();
    this.input.onLockChange = (locked) => {
      if (this.state === 'play' && !this.isTouch && !this.ui.chatOpen) this.ui.setPause(!locked);
    };

    window.addEventListener('resize', () => this.resize());
    this.resize();
    this.placeMenuAvatar();
    this.world.atmosphere.update(5, this.avatar.root.position);
    this.world.prefill(this.avatar.root.position);
  }

  resize() {
    const w = window.innerWidth, h = window.innerHeight;
    this.renderer.setSize(w, h, false);
    if (this.post) this.post.setSize(this.renderer.domElement.width, this.renderer.domElement.height); // WS4
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.aspect = w / h;
  }

  setLook(look) {
    this.look = { ...look };
    this.avatar.setLook(this.look);
    this.hands.setSkin(SKINS[this.look.skin]);
    this.hands.setSleeve(sleeveColor(this.look)); // WS1
  }

  placeMenuAvatar() {
    const x = 1.2, z = 5.2;
    this.avatar.root.position.set(x, heightAt(x, z), z);
    this.avatar.root.rotation.y = Math.PI + 0.35;
  }

  // ---------- вход в комнату ----------

  async tryJoin(o) {
    const ui = this.ui;
    ui.setPlayEnabled(false);
    ui.setStatus('Подключаюсь к комнате…');
    const code = o.room || randomRoom();
    const net = new Net();
    this._bindNet(net);
    try {
      // превью без сервера (window.COZY_SOLO) сразу играет в одиночном режиме с демо-ботами
      if (window.COZY_SOLO) throw new Error('solo');
      const w = await net.connect({ room: code, name: o.nick, look: lookToArray(o.look) });
      this.net = net;
      this.online = true;
      this.roomCode = w.room;
      this._welcome(w);
      try { history.replaceState(null, '', `?room=${w.room}`); } catch { /* file:// и т.п. */ }
    } catch (e) {
      net.close();
      const known = { full: 'Комната заполнена — попробуй другой код', 'bad-room': 'Неверный код комнаты' }[e.message];
      if (known) {
        ui.setStatus(known);
        ui.setPlayEnabled(true);
        return;
      }
      this.net = null;
      this.online = false;
      this.roomCode = '';
      if (!window.COZY_SOLO) {
        ui.setStatus('Сервер недоступен — запускаю одиночный режим');
        await sleep(900);
      }
    }
    ui.setPlayEnabled(true);
    ui.setStatus('');
    this.startPlaying(o);
  }

  // Состояние комнаты от сервера: игроки, предметы, голосовые соединения.
  _welcome(w) {
    this.remotes.clear();
    for (const p of w.players) this.remotes.add(p);
    this.voice.reset();
    for (const p of w.players) this.voice.connect(p.id);
    this.onWelcome?.(w);
  }

  _bindNet(net) {
    net.on('join', (m) => {
      this.remotes.add(m);
      this.voice.connect(m.id);
      this.ui.chat('', `${m.name} зашёл(а) в комнату`, true);
      this.audio.join();
    })
      .on('leave', (m) => {
        const r = this.remotes.get(m.id);
        if (r) this.ui.chat('', `${r.name} вышел(а)`, true);
        this.remotes.remove(m.id);
        this.voice.drop(m.id);
      })
      .on('snap', (m) => {
        for (const e of m.pl || []) this.remotes.push(e);
        this.onPropSnap?.(m.pr || []);
      })
      .on('chat', (m) => {
        if (m.id === net.id) { this.avatar.say(m.m); this.ui.chat(this.nick, m.m); return; }
        const r = this.remotes.get(m.id);
        if (!r) return;
        r.avatar.say(m.m);
        this.ui.chat(r.name, m.m);
        this.audio.chat();
      })
      .on('emote', (m) => this.remotes.get(m.id)?.avatar.act('wave'))
      .on('look', (m) => this.remotes.setLook(m.id, m.look))
      .on('owner', (m) => this.onOwner?.(m))
      .on('deny', (m) => this.onDeny?.(m))
      .on('rtc', (m) => this.onRtc?.(m))
      .on('use', (m) => this.onPropUse?.(m)) // WS3: действия предметами, состояние, удар битой
      .on('pst', (m) => this.onPropState?.(m))
      .on('hit', (m) => this.onHit?.(m))
      .on('close', () => this.reconnect());
  }

  async reconnect() {
    if (!this.net || this.reconnecting) return;
    this.reconnecting = true;
    this.ui.toast('Связь потеряна, переподключаюсь…', 5000);
    for (let i = 1; i <= 6; i++) {
      await sleep(1000 * i);
      try {
        this.net.closedByUs = false;
        const w = await this.net.connect({ room: this.roomCode, name: this.nick, look: lookToArray(this.look) });
        this._welcome(w);
        this.ui.toast('Подключение восстановлено');
        this.reconnecting = false;
        return;
      } catch { /* пробуем ещё */ }
    }
    this.reconnecting = false;
    this.online = false;
    this.remotes.clear();
    this.ui.toast('Не удалось переподключиться — одиночный режим', 5000);
    this.ui.setRoom('одиночная');
  }

  // Дверь/портал: затемнение, перенос, возврат света.
  async teleport(to) {
    if (this.teleporting) return;
    this.teleporting = true;
    this.player.frozen = true;
    this.ui.fade(true);
    this.audio.whoosh();
    await sleep(380);
    const y = to.y === undefined ? heightAt(to.x, to.z) : to.y;
    this.player.teleport(to.x, y, to.z, to.yaw);
    this.player.pitch = 0;
    this.world.atmosphere.update(5, this.player.pos);
    this.world.prefill(this.player.pos);
    await sleep(120);
    this.ui.fade(false);
    this.player.frozen = false;
    this.teleporting = false;
  }

  copyLink() {
    const url = `${location.origin}${location.pathname}?room=${this.roomCode}`;
    const done = () => this.ui.toast('Ссылка скопирована — отправь друзьям');
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(url).then(done, () => window.prompt('Ссылка на комнату:', url));
    else window.prompt('Ссылка на комнату:', url);
  }

  startPlaying({ nick, look }) {
    this.nick = nick;
    this.setLook(look);
    this.avatar.setName(nick);
    this.avatar.lookAt(null); // WS1: в меню персонаж смотрел на камеру
    this.state = 'play';
    this.player.spawn(SPAWN.x, SPAWN.z, SPAWN.yaw);
    this.player.pitch = 0;
    this.input.enabled = true;
    this.ui.showMenu(false);
    this.ui.showHud(true);
    this.touch.show(this.isTouch);
    this.ui.showHelp(this.isTouch);
    this.ui.setHints([]);
    this.ui.setMic('off');
    document.getElementById('mic').hidden = !this.online; // голос только на сервере
    const demo = !this.online && (window.COZY_SOLO || this.params.has('bots'));
    if (demo) this.bots = new DemoBots(this).start();
    this.ui.setRoom(this.online ? this.roomCode : demo ? 'демо' : 'одиночная');
    this.ui.setCount(1 + this.remotes.size);
    $copyVisible(this.online);
    if (this.online) this.ui.chat('', `Ты в комнате «${this.roomCode}». Отправь ссылку друзьям!`, true);
    else if (demo) this.ui.chat('', 'Демо без сервера: рядом гуляют боты. С сервером тут будут твои друзья.', true);
    else this.ui.chat('', 'Одиночный режим: сервер недоступен.', true);
    this.input.requestLock();
    this.onStart?.();
  }

  sendChat(text) {
    if (this.online && this.net?.connected) {
      this.net.send({ t: 'chat', m: text }); // эхо придёт от сервера
      return;
    }
    this.avatar.say(text);
    this.ui.chat(this.nick, text);
  }

  // ---------- состояние для сети ----------

  sendState() {
    if (!this.online || !this.net?.connected) return;
    // реальное время, а не dt: на слабом устройстве dt зажат, и отправка шла бы слишком редко
    const t = performance.now();
    if (t - this._sendT < 66) return;
    this._sendT = t;
    const p = this.player;
    const flags = (p.onGround ? 1 : 0) | (this.speakingLocal ? 2 : 0) | (this.holding ? 4 : 0);
    const hk = holdToCode(this.holdKind); // WS1: вид удержания (контракт C2)
    const yaw = ((p.yaw % TAU) + TAU) % TAU;
    const cur = [p.pos.x, p.pos.y, p.pos.z, yaw, p.pitch, Math.round(p.speed * 10) / 10, flags];
    const last = this._lastState;
    const now = performance.now();
    if (last) {
      const changed = Math.abs(cur[0] - last[0]) + Math.abs(cur[1] - last[1]) + Math.abs(cur[2] - last[2]) > 0.01
        || Math.abs(cur[3] - last[3]) > 0.004 || Math.abs(cur[4] - last[4]) > 0.004 || cur[6] !== last[6] || hk !== this._lastHk;
      if (!changed && now - this._lastSentAt < 1000) return;
    }
    this._lastState = cur;
    this._lastHk = hk;
    this._lastSentAt = now;
    this.net.send({ t: 's', p: [cur[0], cur[1], cur[2]], r: cur[3], q: cur[4], sp: cur[5], a: cur[6], hk });
  }

  // ---------- кадр ----------

  menuUpdate(dt) {
    this.menuT += dt;
    const cam = this.camera;
    // WS1: кадр 3/4 под нового персонажа; он стоит, моргает и поглядывает в камеру
    cam.position.set(3.05, 1.3, 7.75);
    cam.lookAt(0.55, 0.84, 5.2);
    cam.fov = 42;
    cam.updateProjectionMatrix();
    this.avatar.lookAt(cam.position);
    this.avatar.update(dt, { speed: 0, grounded: true, vy: 0, pitch: 0, holdKind: null, talk: 0 }, this.camera);
  }

  playUpdate(dt) {
    const input = this.input, player = this.player, ui = this.ui;
    // ввод отключён, пока печатаем в чате или показана пауза
    input.enabled = !ui.chatOpen && ui.pauseEl.hidden;

    if (input.consume('chat')) {
      ui.openChat();
      input.releaseAll();
    }
    if (input.consume('view')) player.wantThird = !player.wantThird;
    if (input.consume('emote')) {
      this.avatar.act('wave');
      this.hands.act('wave'); // WS1: в 1-м лице машем рукой в кадре
      if (this.online) this.net?.send({ t: 'emote', e: 'wave' });
    }
    if (input.consume('mic')) this.onToggleMic?.();
    if (input.consume('mute')) ui.toast(this.audio.toggleMute() ? 'Звук выключен' : 'Звук включён');
    if (input.consume('use')) { this.reachT = 0.28; this.onUse?.(); }
    const thr = input.takeThrow();
    if (thr !== undefined) this.onDrop?.(thr);
    if (input.consume('push')) this.onPush?.();
    if (input.wheel) {
      if (player.wantThird) player.thirdDist = clamp(player.thirdDist + input.wheel * 0.4, 1.8, 6);
      input.wheel = 0;
    }

    player.update(dt, input);
    player.applyCamera(this.camera, dt);
    this.props.update(dt);

    const third = player.thirdPerson || player.wantThird; // руки прячем сразу, не дожидаясь отъезда камеры
    const av = this.avatar;
    av.root.position.copy(player.pos);
    av.root.rotation.y = player.yaw;
    av.setBodyVisible(third);
    // WS1: вид удержания (C2) и речь передаём персонажу и рукам
    const holdKind = this.holdKind !== undefined ? this.holdKind : this.holding ? 'two' : null;
    const talk = this.speakingLocal ? 0.7 : 0;
    av.update(dt, {
      speed: player.speed, grounded: player.onGround, vy: player.vel.y, pitch: player.pitch, holding: !!this.holding, holdKind, talk,
    }, this.camera);

    this.reachT = Math.max(0, this.reachT - dt);
    if (!third) {
      this.hands.update(dt, {
        speed: player.speed, phase: player.bob, holding: !!this.holding, holdKind, grounded: player.onGround, vy: player.vel.y,
        sprint: player.sprinting, lookDX: player.lookDX, lookDY: player.lookDY, reach: this.reachT > 0, talk,
      }, this.camera, this.world.atmosphere, this.aspect);
    }

    this.bots?.update(dt);
    this.remotes.update(dt, this.camera);
    this.props.lateUpdate?.(dt); // WS3: предметы в руках крепятся к якорям после анимации аватаров
    this.voice.update(dt);
    this.audio.update(this.world.atmosphere, this.camera, player.pos);
    this.sendState();
    ui.setCount(1 + this.remotes.size);
    ui.setPlayers([{ name: this.nick, me: true, speaking: this.speakingLocal }, ...this.remotes.list()], input.tab);
    this.onPlayFrame?.(dt);
  }

  frame(now) {
    // rAF-метка может оказаться чуть раньше performance.now() из start(), поэтому dt зажимаем с обеих сторон
    const rawDt = (now - this._last) / 1000;
    const dt = clamp(rawDt, 0, 0.05);
    this.renderer.info.reset(); // WS4
    this._adapt(rawDt);
    this._last = now;
    this.time += dt;
    this.frames++;

    if (this.state === 'menu') this.menuUpdate(dt);
    else this.playUpdate(dt);

    let focus = this.state === 'play' ? this.player.pos : this.avatar.root.position;
    if (this.debugCam) { // отладочная камера для скриншотов
      const d = this.debugCam;
      this._dbgFocus = (this._dbgFocus || new THREE.Vector3()).set(d.x, heightAt(d.x, d.z), d.z);
      this.camera.position.set(d.x, this._dbgFocus.y + d.h, d.z);
      this.camera.rotation.set(d.pitch, d.yaw, 0);
      focus = this._dbgFocus;
    }
    // WS2 (C6): трава приминается под своим игроком и чужими
    const benders = this._benders || (this._benders = []);
    benders.length = 0;
    benders.push(this.state === 'play' ? this.player.pos : this.avatar.root.position);
    for (const r of this.remotes.map.values()) {
      if (benders.length >= 8) break;
      benders.push(r.avatar.root.position);
    }
    this.world.setBenders(benders);
    this.world.update(dt, this.time, this.camera, focus);

    this.renderView(dt);
    requestAnimationFrame((t) => this.frame(t));
  }

  // WS4: мир -> [вода, AO] -> руки от 1-го лица поверх (до bloom и цвета, чтобы выглядели так же) -> постобработка.
  // Без постобработки (low) - прямой рендер с тонмаппингом, руки поверх с очисткой глубины.
  renderView(dt = 0) {
    const r = this.renderer, p = this.player;
    const fp = p.cameraMode ? p.cameraMode === 'fp' : !p.wantThird; // C1
    const handsOn = this.state === 'play' && fp && !p.thirdPerson && !this.debugCam;
    if (this.post) {
      const o = this._postOpts || (this._postOpts = { hands: null, water: this.world.water, grade: this.world.atmosphere.grade, dt: 0 });
      o.hands = handsOn ? this.hands : null;
      o.dt = dt;
      this.post.render(this.world.scene, this.camera, o);
      return;
    }
    r.setRenderTarget(null);
    r.autoClear = true;
    r.render(this.world.scene, this.camera);
    if (handsOn) {
      r.autoClear = false;
      r.clearDepth();
      r.render(this.hands.scene, this.hands.camera);
      r.autoClear = true;
    }
  }

  // WS4: адаптивное разрешение. rawDt - реальное время кадра (не зажатое).
  _adapt(rawDt) {
    if (!this.adaptive || !(rawDt > 0) || rawDt > 0.5 || document.hidden) return;
    this._ftAvg += (rawDt * 1000 - this._ftAvg) * 0.08;
    this._slowT = this._ftAvg > 22 ? this._slowT + rawDt : 0;
    this._fastT = this._ftAvg < 14 ? this._fastT + rawDt : 0;
    let s = this.resScale;
    if (this._slowT > 2.5 && s > 0.6) s = Math.max(0.6, s * 0.85);
    else if (this._fastT > 5 && s < 1) s = Math.min(1, s / 0.85);
    else return;
    this._slowT = 0; this._fastT = 0;
    this.resScale = s;
    this.renderer.setPixelRatio(this.dpr * s);
    this.resize();
  }

  start() {
    this._last = performance.now();
    requestAnimationFrame((t) => this.frame(t));
  }
}

// Кнопка "Ссылка" имеет смысл только онлайн.
function $copyVisible(v) {
  const b = document.getElementById('copy');
  if (b) b.hidden = !v;
}
