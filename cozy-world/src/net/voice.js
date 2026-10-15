// Голосовой чат по расстоянию. Между игроками поднимается WebRTC-сеть "каждый с каждым" (хватает для
// комнаты до ~12 человек), сервер только пересылает сигнальные сообщения. Входящий звук идёт через
// WebAudio PannerNode: слышно направление, а громкость падает с расстоянием до аватара.

const MAX_HEAR = 38; // дальше этого расстояния собеседника не слышно
const FULL_VOL = 3.5; // ближе - полная громкость

export class Voice {
  constructor(game) {
    this.game = game;
    this.peers = new Map();
    this.state = 'off'; // off | on | denied | none
    this.stream = null;
    this.track = null;
    this.localAnalyser = null;
    this.speaking = false;
    this._speakHold = 0;
    this._buf = new Uint8Array(256);
  }

  get net() { return this.game.net; }
  get ctx() { return this.game.audio.ctx; }

  // ---------- микрофон ----------

  async toggle() {
    if (this.state === 'on') this.disable();
    else await this.enable();
    this.game.ui.setMic(this.state);
    return this.state;
  }

  async enable() {
    await this.game.audio.start();
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      this.state = 'none';
      this.game.ui.toast('Микрофон работает только по HTTPS', 4000);
      return;
    }
    try {
      if (!this.stream) {
        this.stream = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
          video: false,
        });
        this.track = this.stream.getAudioTracks()[0];
        if (this.ctx) {
          const src = this.ctx.createMediaStreamSource(this.stream);
          this.localAnalyser = this.ctx.createAnalyser();
          this.localAnalyser.fftSize = 256;
          src.connect(this.localAnalyser); // только анализ, в колонки не выводим
        }
      }
      this.track.enabled = true;
      for (const p of this.peers.values()) p.sender?.replaceTrack(this.track).catch(() => {});
      this.state = 'on';
    } catch (e) {
      this.state = e && e.name === 'NotAllowedError' ? 'denied' : 'none';
      this.game.ui.toast(this.state === 'denied' ? 'Доступ к микрофону запрещён' : 'Микрофон не найден', 3500);
    }
  }

  disable() {
    if (this.track) this.track.enabled = false;
    this.state = 'off';
    this.speaking = false;
  }

  // ---------- соединения ----------

  // Вызывается при входе в комнату (список игроков) и при появлении нового игрока.
  connect(id) {
    if (!this.net || this.peers.has(id)) return this.peers.get(id);
    const initiator = this.net.id < id; // одинаковое правило на обеих сторонах: нет встречных offer
    const pc = new RTCPeerConnection({ iceServers: this.net.ice });
    const peer = { id, pc, initiator, queue: [], remoteSet: false, level: 0, gain: null, panner: null, analyser: null, audio: null };
    this.peers.set(id, peer);
    const tr = pc.addTransceiver('audio', { direction: 'sendrecv' });
    peer.sender = tr.sender;
    if (this.track && this.state === 'on') tr.sender.replaceTrack(this.track).catch(() => {});

    pc.onicecandidate = (e) => {
      if (e.candidate) this.net.send({ t: 'rtc', to: id, d: { k: 'cand', c: e.candidate.toJSON() } });
    };
    pc.ontrack = (e) => this._attach(peer, e.streams[0] || new MediaStream([e.track]));
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed' && peer.initiator) pc.restartIce?.();
    };
    if (initiator) {
      pc.onnegotiationneeded = async () => {
        try {
          const offer = await pc.createOffer();
          await pc.setLocalDescription(offer);
          this.net.send({ t: 'rtc', to: id, d: { k: 'offer', sdp: pc.localDescription.sdp } });
        } catch { /* сеть закрылась */ }
      };
    }
    return peer;
  }

  async onSignal(m) {
    const d = m.d;
    if (!d || typeof d.k !== 'string') return;
    const peer = this.peers.get(m.from) || this.connect(m.from);
    if (!peer) return;
    const pc = peer.pc;
    try {
      if (d.k === 'offer') {
        await pc.setRemoteDescription({ type: 'offer', sdp: d.sdp });
        peer.remoteSet = true;
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        this.net.send({ t: 'rtc', to: m.from, d: { k: 'answer', sdp: pc.localDescription.sdp } });
        await this._flush(peer);
      } else if (d.k === 'answer') {
        await pc.setRemoteDescription({ type: 'answer', sdp: d.sdp });
        peer.remoteSet = true;
        await this._flush(peer);
      } else if (d.k === 'cand' && d.c) {
        if (peer.remoteSet) await pc.addIceCandidate(d.c).catch(() => {});
        else peer.queue.push(d.c);
      }
    } catch { /* устаревшее сообщение */ }
  }

  async _flush(peer) {
    for (const c of peer.queue.splice(0)) await peer.pc.addIceCandidate(c).catch(() => {});
  }

  _attach(peer, stream) {
    const ctx = this.ctx;
    if (!ctx) { // звуковой контекст ещё не создан (не было жеста) - создаём и повторяем
      this.game.audio.start().then(() => { if (this.ctx) this._attach(peer, stream); });
      return;
    }
    // Chrome отдаёт звук удалённого потока в WebAudio только если он ещё привязан к медиа-элементу
    const el = new Audio();
    el.srcObject = stream;
    el.muted = true;
    el.play().catch(() => {});
    peer.audio = el;
    const src = ctx.createMediaStreamSource(stream);
    const panner = ctx.createPanner();
    panner.panningModel = 'HRTF';
    panner.distanceModel = 'inverse';
    panner.refDistance = 2.5;
    panner.rolloffFactor = 1.1;
    panner.maxDistance = 100;
    const gain = ctx.createGain();
    gain.gain.value = 0;
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 256;
    src.connect(analyser);
    src.connect(panner);
    panner.connect(gain);
    gain.connect(this.game.audio.master);
    Object.assign(peer, { src, panner, gain, analyser });
  }

  drop(id) {
    const p = this.peers.get(id);
    if (!p) return;
    try { p.pc.close(); } catch { /* ignore */ }
    try { p.src?.disconnect(); p.gain?.disconnect(); p.panner?.disconnect(); } catch { /* ignore */ }
    if (p.audio) { p.audio.srcObject = null; }
    this.peers.delete(id);
  }

  reset() {
    for (const id of [...this.peers.keys()]) this.drop(id);
  }

  // ---------- каждый кадр ----------

  _rms(analyser) {
    analyser.getByteTimeDomainData(this._buf);
    let s = 0;
    for (let i = 0; i < this._buf.length; i++) { const v = (this._buf[i] - 128) / 128; s += v * v; }
    return Math.sqrt(s / this._buf.length);
  }

  update(dt) {
    const ctx = this.ctx;
    if (!ctx) return;
    const t = ctx.currentTime;
    const cam = this.game.camera.position;
    for (const p of this.peers.values()) {
      if (!p.gain) continue;
      const r = this.game.remotes.get(p.id);
      let g = 0;
      if (r) {
        const pos = r.avatar.root.position;
        const x = pos.x, y = pos.y + 1.1, z = pos.z;
        if (p.panner.positionX) { p.panner.positionX.value = x; p.panner.positionY.value = y; p.panner.positionZ.value = z; } else p.panner.setPosition(x, y, z);
        const d = Math.hypot(x - cam.x, y - cam.y, z - cam.z);
        if (d < MAX_HEAR) g = d < FULL_VOL ? 1 : Math.pow(1 - (d - FULL_VOL) / (MAX_HEAR - FULL_VOL), 1.4);
      }
      p.gain.gain.setTargetAtTime(g, t, 0.06);
      p.level = this._rms(p.analyser);
    }
    // свой индикатор "говорю": порог с удержанием, чтобы не мигал между словами
    if (this.state === 'on' && this.localAnalyser) {
      const lvl = this._rms(this.localAnalyser);
      if (lvl > 0.02) this._speakHold = 0.3;
      else this._speakHold = Math.max(0, this._speakHold - dt);
      this.speaking = this._speakHold > 0;
    } else this.speaking = false;
    this.game.speakingLocal = this.speaking;
    this.game.avatar.setSpeaking(this.speaking && this.game.player.thirdPerson);
  }
}
