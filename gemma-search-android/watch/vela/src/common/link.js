/**
 * «Пульт кистью» on Xiaomi Vela, the whole of it but the screen: finds the phone (SemSearch → Настройки → Часы) by its
 * Bluetooth LE service, connects, finds the turns in its own accelerometer (and compass: with the arm hanging only it
 * sees a twist) and sends one short packet per turn, vibrates on the phone's answer; the watch held to the mouth
 * records a voice note and sends it. Made once by the app (app.ux), not by the page, so it keeps running when the page
 * is gone: Vela lets an app run on in the background while it plays sound (its manifest's «background» features), so
 * while it is on, a silent sound plays round and round (keepAlive). The page only shows it (watch) and switches it.
 * When the music stops the phone says «pause»: no turns are sent then, and the sensor is switched off unless voice
 * notes are on.
 */
import {
  SERVICE, SAMPLES, EVENTS, EV_NEXT, EV_PREVIOUS, EV_LEARNED, EV_IGNORED, EV_PAUSE, EV_STREAM, EV_CONFIG, EV_VOICE_FROM,
  EV_VOICE_DONE, VOICE_SAVED, VOICE_SILENT, VOICE_KEPT, VOICE_ANSWER, SOURCE_COMPASS, RAW_MAX, advertises, createPacker,
  parseEvent, createTalk, createVoice, rawPacket, diagPacket, diagPackets, EV_TALK_AUDIO, EV_TALK_SEG, EV_TALK_TEXT, EV_TALK_END,
  voicePlayed, CODEC_ADPCM, CODEC_LIVE, VERSION_MP3, TALK_MP3, PLAYED, NOT_PLAYED, NOT_WHOLE, NOT_WRITTEN, SILENT
} from './proto.js'

/** The silence that keeps the app running in the background, and how soon it is started again when stopped. */
const QUIET = '/common/quiet.mp3'
const ALIVE_RETRY_MS = 2000

/** A voice note's sounds: two bells up — speak now; down — done (made by tools/chimes.py); at most this long. */
const CHIME_START = '/common/start.mp3'
const CHIME_END = '/common/end.mp3'
const CHIME_MS = 700

/** How long a connection may take before it is let go and the phone looked for again. */
const CONNECT_MS = 15000

/** How long each step after connecting (service discovery, enabling answers) may take before a retry. */
const STEP_MS = 8000

/** If enabling the phone's answers does not complete in this time, stream anyway (the remote works without them). */
const NOTIFY_MS = 4000

/** A write's completion may not arrive; move on after this long so the sample stream keeps flowing. */
const WRITE_MS = 150

/** The same for a voice note's bytes (many writes in a row: the link's own queue keeps them in order). */
const VOICE_WRITE_MS = 60

/** Between one write's end and the next: a breath for the watch (at most ~100 writes a second). */
const WRITE_GAP_MS = 8

/** The sensor's samples go to the log only on a link that takes them in big writes (else ~20 tiny writes a second). */
const RAW_MIN_MTU = 100

/**
 * A voice note as the S5 records it: Opus, 16 kHz mono at 22 kbit/s (the top of the range the docs give for it — the
 * clearer the consonants, the better the recognition), ~2.7 KB a second; its frames handed over as they are recorded
 * (small ones: the last of a note is not left in the recorder), so most of it is on the phone by the time the hand is
 * down.
 * But what the S5 hands over in Opus frames is not documented, and the phone could not read it («запись с часов не
 * разобрать»); so the recorder gives plain 16-bit PCM (16 kHz mono — the format there is no doubt about) and the watch
 * makes it IMA ADPCM itself (createAdpcm: a quarter of the bytes, 8 KB a second). Opus only if the watch takes no PCM.
 * Its frames 100 ms each (frameSize is bytes of PCM): ten callbacks a second, not twenty-five — less for the watch to do.
 */
const PCM_REC = { format: 'pcm', sampleRate: 16000, numberOfChannels: 1, encodeBitRate: 256000, frameSize: 3200, voice: 'adpcm' }
const OPUS = { format: 'opus', sampleRate: 16000, numberOfChannels: 1, encodeBitRate: 22000, frameSize: 512 }

/** A conversation's answer: its pieces kept as files (a few, in turn), played one after another. */
const TALK_FILES = 4
/** A piece that does not say it ended is taken for played this long after its own length. */
const TALK_SLACK_MS = 3000

/** The phone's answer about a note may take a while (recognition): after this long the end is said again. */
const DONE_MS = 40000

/** How many bytes of a note go in one write: before the phone tells the link's MTU, a long write is tried. */
const CHUNK_UNKNOWN = 180
const CHUNK_MIN = 14

/**
 * The accelerometer's pace: «ui» (~16 a second) first — a quick turn needs it; on the S5 «game» gave one value over and
 * over, so a pace whose values never change at all in PROBE_MS is given up for «normal» (~5 a second), which the S5 does
 * deliver live (there a quick turn can fall between two samples).
 */
const PACES = ['ui', 'normal']
const PROBE_MS = 2500

/** Shapes of the search's parameters, most documented first (see startScan). */
const SCAN_VARIANTS = [
  () => ({ filters: [{ deviceId: '', name: '', serviceUuid: SERVICE }], options: { interval: 0, dutyMode: 1 } }),
  () => ({ filters: [{ deviceId: '', name: '', serviceUuid: SERVICE }], options: { dutyMode: 1 } }),
  () => ({ filters: [{ deviceId: '', name: '', serviceUuid: SERVICE }] }),
  () => ({ filters: [{ serviceUuid: SERVICE }] }),
  () => ({ filters: [] }),
  () => ({ filters: [{ deviceId: '', name: '', serviceUuid: '' }] })
]

/** A characteristic as Vela's interface describes it: every field given (descriptors and properties too). */
function characteristic(uuid, value, props) {
  return {
    serviceUuid: SERVICE,
    characteristicUuid: uuid,
    characteristicValue: value,
    descriptors: [],
    properties: {
      read: !!props.read,
      write: !!props.write,
      writeNoResponse: !!props.writeNoResponse,
      notify: !!props.notify,
      indicate: false
    }
  }
}

/**
 * The link, with the watch's modules it works with ({ ble, sensor, vibrator, brightness, storage, record, file, audio }).
 * What the screen shows is in view (on, state, diag, note, voiceOn, screenOn, bg); watch(fn) calls fn(view) on every
 * change (null — nobody looks).
 */
export function createLink(sys) {
  const { ble, sensor, vibrator, brightness, storage, record, file, audio } = sys
  const view = { on: false, state: 'выключено', diag: '', note: '', voiceOn: true, screenOn: false, bg: '', poses: 0, soundsOn: true,
    err: '' }
  let onChange = null
  function changed() {
    if (onChange) onChange(view)
  }
  /** The fields the screen shows: set — the screen told. */
  function shown(name) {
    return {
      get() {
        return view[name]
      },
      set(v) {
        if (view[name] === v) return
        view[name] = v
        changed()
      },
      enumerable: true
    }
  }
  /**
   * A callback the watch's system calls (Bluetooth, the sensor, the recorder, the player): an error in it shown, kept and
   * logged like one in a method (see oops) — never let out into the system, which would end the app.
   */
  /** What a system callback said (an error object, a code, data) as a short line for the log. */
  function describe(x) {
    if (x === undefined || x === null) return 'без подробностей'
    if (typeof x !== 'object') return String(x)
    try {
      const s = JSON.stringify(x)
      if (s && s !== '{}') return s.length > 120 ? s.slice(0, 120) + '…' : s
    } catch (e) {}
    return x.message ? String(x.message) : String(x)
  }

  function safe(where, fn) {
    return function () {
      try {
        return fn.apply(this, arguments)
      } catch (e) {
        L.oops(where, e)
        return undefined
      }
    }
  }
  const L = {
    /** An error in a method: on the screen, in storage (seen after a restart), in the phone's log. */
    oops(where, e) {
      this.errors = (this.errors || 0) + 1
      const msg = where + ': ' + (e && e.message ? e.message : String(e))
      if (this.errors <= 3 || Date.now() - (this.errorAt || 0) > 10000) {
        this.errorAt = Date.now()
        view.err = 'ошибка ' + this.errors + ' — ' + msg
        changed()
        try {
          storage.set({ key: 'wrist_error', value: new Date().toISOString() + ' ' + msg })
        } catch (x) {}
        try {
          this.sendLine('ошибка ' + msg + (e && e.stack ? ' | ' + String(e.stack).split('\n').slice(0, 3).join(' / ') : ''))
        } catch (x) {}
      }
    },

    /** A line to the phone's log even without the log asked for (an error, how the last run ended). */
    sendLine(text) {
      const room = (this.mtu ? Math.min(this.mtu, 512) : 23) - 3
      for (const p of diagPackets(text, room, 20)) this.diagOut.push(p)
      while (this.diagOut.length > 40) this.diagOut.shift()
      this.forceLines = true
      this.pump()
    },

    /**
     * What the link was doing, kept every few seconds: after a crash or a restart of the watch the next run tells it (on
     * the screen and to the phone's log) — the last thing seen before it went.
     */
    trail(now, force) {
      if (!force && now - (this.trailAt || 0) < 5000) return
      this.trailAt = now
      const t = (this.on ? 'вкл' : 'выкл') + ', связь ' + (this.linkedOk ? 'есть' : 'нет') + ', датчик ' + (this.listening ? PACES[this.pace] : 'выкл')
        + ', отсчётов ' + (this.n ? this.n.s : 0) + ', записей ' + (this.n ? this.n.w : 0) + ', ' + (this.rec ? 'идёт запись ' + this.rec.v.len + ' байт'
        : this.pending ? 'колокольчик перед записью' : 'не пишет') + ', заметок в очереди ' + this.voices.length + ', журнал ' + (this.debug ? 'вкл' : 'выкл')
        + ', MTU ' + this.mtu + ', звук фона ' + (this.audioPlaying ? 'играет' : 'нет') + ', ошибок ' + (this.errors || 0)
        + (this.phaseNow ? '; шаг: ' + this.phaseNow : '')
      try {
        storage.set({ key: 'wrist_trail', value: new Date(now).toISOString() + ' ' + t })
      } catch (e) {}
      // and to the phone's log, with no log asked for too: every 5 s for the first minute on the link, then each minute
      // (a step at most once a second) — if the watch hangs, the phone has its last word
      const every = force ? 1000 : now - (this.linkedAt || 0) < 60000 ? 5000 : 60000
      if (this.linkedOk && now - (this.trailSentAt || 0) >= every) {
        this.trailSentAt = now
        this.sendLine('сейчас: ' + t)
      }
    },

    /** Where the run is now (the search, the link, the sensor, a chime, a recording…): kept at once (see trail). */
    phase(what) {
      this.phaseNow = what
      this.trail(Date.now(), true)
    },

    /** The screen: fn(view) on every change (null — none now). */
    watch(fn) {
      onChange = fn
      if (fn) fn(view)
    },

    /** The page is on the screen or not: the screen kept on only while it is, if asked to (setScreen). */
    visible(on) {
      this.visibleNow = !!on
      this.screen()
    },

    /** Keep the screen on while the page is open (for a watch on which the background does not work). */
    setScreen(on) {
      this.screenOn = !!on
      storage.set({ key: 'wrist_screen', value: on ? '1' : '0' })
      this.screen()
    },

    screen() {
      const keep = !!(this.on && this.screenOn && this.visibleNow)
      if (keep === this.kept) return
      this.kept = keep
      try {
        brightness.setKeepScreenOn({ keepScreenOn: keep })
      } catch (e) {}
    },

    /**
     * The sound that keeps the app running when its page is gone: Vela runs an app on in the background while it plays
     * («background» in the manifest). Silence, round and round, from «Старт» to «Стоп»; stopped by something else (a
     * call, an alarm), it is started again — but not over a voice note being recorded.
     */
    keepAlive(on) {
      this.alive = on
      if (this.aliveTimer) clearTimeout(this.aliveTimer)
      this.aliveTimer = 0
      if (!audio) {
        this.bg = on ? 'в фоне не работает: нет звука' : ''
        return
      }
      if (!on) {
        try {
          audio.stop()
        } catch (e) {}
        this.bg = ''
        return
      }
      this.hookAudio()
      this.play()
    },

    /**
     * The player's events, once (whether or not the silence keeps the app running): the background's silence, the
     * bells, the answer's pieces — a piece's start, its end, the player's error are heard even with «В фоне» off.
     */
    hookAudio() {
      if (!audio || this.audioHooked) return
      this.audioHooked = true
      const self = this
      const again = function (why) {
        self.audioPlaying = false
        if (!self.alive) return
        if (!self.alive || self.aliveTimer) return
        if (why) self.bg = why
        self.aliveTimer = setTimeout(() => {
          self.aliveTimer = 0
          if (!self.alive) return
          if (self.rec) again() // not over a recording: later
          else self.play()
        }, ALIVE_RETRY_MS)
      }
      audio.onplay = safe('звук play', function () {
        const tp = self.talkPlaying
        if (tp && !tp.started) {
          tp.started = true
          self.talkLog('кусок ' + tp.seg + ': звук пошёл через ' + (Date.now() - tp.t0) + ' мс')
        }
        if (self.chiming || self.talkPlaying) return
        if (!self.audioPlaying) self.logLine('фон: тишина играет')
        self.audioPlaying = true
        self.aliveErrors = 0
        self.bg = 'работает и в фоне — можно выйти из приложения'
      })
      audio.onpause = safe('звук pause', function () {
        if (self.chiming || self.talkPlaying || Date.now() < (self.quietSwitchUntil || 0)) return
        again('фон: звук приостановлен — включаю снова')
      })
      audio.onstop = safe('звук stop', function () {
        if (self.chiming || self.talkPlaying || Date.now() < (self.quietSwitchUntil || 0)) return
        self.logLine('фон: звук остановлен')
        again('фон: звук остановлен — включаю снова')
      })
      audio.onended = safe('звук ended', function () {
        if (self.chiming) {
          self.chimed()
          return
        }
        if (self.talkPlaying) {
          const tp = self.talkPlaying
          self.talkLog('кусок ' + tp.seg + ': доиграл за ' + (Date.now() - tp.t0) + ' мс (длина ' + tp.ms + ' мс)')
          self.talkEnded(tp, PLAYED)
          return
        }
        if (Date.now() < (self.quietSwitchUntil || 0)) return
        again('')
      })
      audio.onerror = safe('звук error', function (err) {
        if (self.chiming) {
          self.chimed()
          return
        }
        if (self.talkPlaying) {
          self.talkFailed(self.talkPlaying, describe(err))
          return
        }
        self.logLine('фон: ошибка звука')
        self.aliveErrors = (self.aliveErrors || 0) + 1
        if (self.aliveErrors >= 3) {
          self.audioPlaying = false
          self.bg = 'в фоне не работает (звук не играет) — держите страницу открытой, «Экран: не гаснет»'
          return
        }
        again('фон: ошибка звука — пробую снова')
      })
    },

    /**
     * A short sound (a note starts, ends) through the one player there is — the background's — then the silence again
     * and then(). Without a player, then() at once.
     */
    chime(src, then) {
      if (!audio || !this.sounds || this.talkPlaying) {
        if (then) then()
        return
      }
      if (this.chiming) this.chimed()
      const self = this
      this.chiming = { then }
      this.phase(src === CHIME_START ? 'звук перед записью' : src === CHIME_END ? 'звук после записи' : 'звук')
      this.chimeTimer = setTimeout(() => self.chimed(), CHIME_MS)
      try {
        // the player stopped first: a source changed while playing was kept looping on the S5 (a beep without end)
        this.halt()
        audio.src = src
        audio.loop = false
        audio.play()
      } catch (e) {
        this.chimed()
      }
    },

    /** The player stopped on purpose: its stop and pause told then are not «stopped by the watch». */
    halt() {
      this.quietSwitchUntil = Date.now() + 800
      try {
        audio.stop()
      } catch (e) {}
    },

    /** The note's sounds on or off (the page's button); kept. */
    setSounds(on) {
      this.sounds = !!on
      this.soundsOn = this.sounds
      storage.set({ key: 'wrist_sounds', value: on ? '1' : '0' })
    },

    /** The chime over (ended, failed, or took too long): the silence again, then what waited for it. */
    chimed() {
      const c = this.chiming
      if (!c) return
      this.chiming = null
      if (this.chimeTimer) clearTimeout(this.chimeTimer)
      this.chimeTimer = 0
      this.audioPlaying = false
      this.halt()
      if (this.talkQueue && this.talkQueue.length) this.playTalk()
      else if (this.alive) this.play()
      if (c.then) c.then()
    },

    play() {
      try {
        audio.src = QUIET
        audio.loop = true
        try {
          audio.notificationVisible = false // no player card for the silence, where the watch has the setting
        } catch (e) {}
        audio.play()
      } catch (e) {
        this.bg = 'в фоне не работает: ' + (e && e.message ? e.message : 'звук')
      }
    },

    /** Sets itself up (what was kept on the watch) and starts. */
    init() {
      this.id = 0
      this.gatt = null
      this.scanner = null
      this.findId = -1
      this.queue = []
      this.writing = false
      this.listening = false
      this.packer = null
      this.timer = 0
      this.pace = 0
      this.paceKnown = false
      this.writeId = 0
      this.playing = true
      this.leftHand = true
      this.mtu = 0
      this.chunk = CHUNK_UNKNOWN
      this.talk = createTalk()
      this.rec = null
      this.voices = []
      this.voiceFails = 0
      this.doneTimer = 0
      this.noteId = Math.floor(Math.random() * 256)
      this.alive = false
      this.aliveTimer = 0
      this.aliveErrors = 0
      this.audioPlaying = false
      this.visibleNow = false
      this.kept = false
      const self = this
      this.variant = 0
      this.chiming = null
      this.chimeTimer = 0
      this.pending = null
      this.debug = false
      this.rawBuf = []
      this.rawOut = []
      this.diagOut = []
      this.talkMode = false
      this.talkWatch = false
      this.talkHeadset = false
      this.hs = null
      this.talkIn = null
      this.talkQueue = []
      this.talkPlaying = null
      this.talkTimer = 0
      this.sounds = true
      storage.get({
        key: 'wrist_sounds',
        success(v) {
          if (v === '0') self.sounds = false
          self.soundsOn = self.sounds
        }
      })
      storage.get({
        key: 'wrist_poses',
        success(v) {
          // how this hand holds the watch to the mouth, taught (see createTalk)
          try {
            const d = JSON.parse(v)
            if (Array.isArray(d)) self.talk.poses = d
          } catch (e) {}
          self.poses = self.talk.poses.length
        }
      })
      // how the last run ended: a trail not marked clean — it was cut off (a crash, the watch restarted)
      storage.get({
        key: 'wrist_trail',
        success(v) {
          if (v && v.indexOf('чисто') !== 0) {
            self.lastRun = 'прошлый запуск оборвался: ' + v
            storage.get({
              key: 'wrist_error',
              success(err) {
                if (err) self.lastRun += '; последняя ошибка: ' + err
                self.note = self.lastRun
              }
            })
          }
          storage.set({ key: 'wrist_trail', value: 'запущено ' + new Date().toISOString() })
        }
      })
      storage.get({
        key: 'wrist_screen',
        success(v) {
          self.screenOn = v === '1'
          self.screen()
        }
      })
      storage.get({
        key: 'wrist_voice',
        success(v) {
          // what the phone said last (it says again on every connection)
          if (v === '0' || v === '1') self.voiceOn = v === '1'
        }
      })
      storage.get({
        key: 'wrist_fingers',
        success(v) {
          // where the arm has hung: which way along x the fingers are (see createTalk)
          try {
            const d = JSON.parse(v)
            if (Array.isArray(d)) self.talk.down = d
          } catch (e) {}
        }
      })
      storage.get({
        key: 'wrist_scan',
        success(v) {
          const n = parseInt(v, 10)
          if (n >= 0 && n < SCAN_VARIANTS.length) self.variant = n
        }
      })
      storage.get({
        key: 'wrist_id',
        success(v) {
          self.id = parseInt(v, 10) || 0
          if (!self.id) {
            // who this watch is to the phone: random, kept (the phone listens only to the watch it met first)
            self.id = (Math.floor(Math.random() * 0x7ffffffe) + 1) | 1
            storage.set({ key: 'wrist_id', value: String(self.id) })
          }
          self.start()
        },
        fail() {
          self.id = (Math.floor(Math.random() * 0x7ffffffe) + 1) | 1
          self.start()
        }
      })
    },

    toggle() {
      if (this.on) this.stop()
      else this.start()
    },

    start() {
      if (this.on) return
      this.on = true
      this.phase('старт: фоновый звук')
      this.keepAlive(true)
      this.screen()
      this.scan()
    },

    stop() {
      this.on = false
      try {
        storage.set({ key: 'wrist_trail', value: 'чисто: выключено' })
      } catch (e) {}
      this.stopScan()
      this.drop()
      if (this.timer) clearTimeout(this.timer)
      this.timer = 0
      this.keepAlive(false)
      this.screen()
      this.state = 'выключено'
    },

    // ---------------------------------------------------------------- finding the phone

    scan() {
      if (!this.on || this.gatt) return
      const self = this
      this.seen = 0
      this.seenAt = 0
      this.state = 'ищу телефон…'
      this.phase('ищу телефон')
      this.scanner = ble.createScanner()
      this.findId = this.scanner.subscribeBLEDeviceFind({
        callback: safe('поиск', function (results) {
          if (self.gatt || !results) return
          for (let i = 0; i < results.length; i++) {
            self.seen++
            if (advertises(results[i].data, SERVICE)) {
              self.stopScan()
              self.connect(results[i])
              return
            }
          }
          // what the search sees, at most once a second: devices around, but not the phone — the phone is not announcing
          const now = Date.now()
          if (now - self.seenAt > 1000) {
            self.seenAt = now
            self.state = 'ищу телефон… рядом устройств: ' + self.seen
          }
        }),
        fail() {
          self.state = 'поиск прервался — повторю'
          self.stopScan()
          self.later(() => self.scan(), 5000)
        }
      })
      this.startScan(this.variant || 0, 0)
    },

    /**
     * The search started with the parameters the watch accepts. Vela's documented ones are not what every firmware takes
     * (code 202: «wrong arguments»), so a few shapes are tried in turn, and the one that works is kept for next time.
     */
    startScan(v, tried) {
      const self = this
      const scanner = this.scanner
      if (!scanner) return
      let params
      try {
        params = SCAN_VARIANTS[v]()
      } catch (e) {
        params = SCAN_VARIANTS[0]()
      }
      params.success = function () {
        if (scanner !== self.scanner) return
        if (self.variant !== v) {
          self.variant = v
          storage.set({ key: 'wrist_scan', value: String(v) })
        }
        // nothing at all for a while (the watch's own filter may hide even the phone): the next shape
        self.later(() => {
          if (self.scanner === scanner && !self.gatt && self.seen === 0) {
            self.stopScan()
            self.variant = (v + 1) % SCAN_VARIANTS.length
            self.scan()
          }
        }, 12000)
      }
      params.fail = function (data, code) {
        if (scanner !== self.scanner) return
        if (code === 10001) {
          self.state = 'Bluetooth выключен'
          self.stopScan()
          self.later(() => self.scan(), 10000)
          return
        }
        if (tried + 1 < SCAN_VARIANTS.length) {
          self.startScan((v + 1) % SCAN_VARIANTS.length, tried + 1)
          return
        }
        self.state = 'поиск не удался (' + code + '), повторю'
        self.stopScan()
        self.later(() => self.scan(), 10000)
      }
      this.state = 'ищу телефон…' + (tried ? ' (' + (v + 1) + ')' : '')
      scanner.startBLEScan(params)
    },

    stopScan() {
      if (!this.scanner) return
      try {
        this.scanner.stopBLEScan()
        if (this.findId >= 0) this.scanner.unsubscribeBLEDeviceFind(this.findId)
        this.scanner.close()
      } catch (e) {}
      this.scanner = null
      this.findId = -1
    },

    later(fn, ms) {
      if (this.timer) clearTimeout(this.timer)
      this.timer = setTimeout(() => {
        this.timer = 0
        if (this.on) fn()
      }, ms)
    },

    // ---------------------------------------------------------------- the link

    connect(found) {
      const self = this
      this.phase('подключаюсь')
      const g = ble.createGattClientDevice(found.deviceId, found.addressType)
      this.gatt = g
      this.up = false
      this.linkedOk = false
      this.tries = (this.tries || 0) + 1
      this.state = 'подключаюсь…' + (this.tries > 1 ? ' (попытка ' + this.tries + ')' : '')
      const up = function () {
        if (g !== self.gatt || self.up) return
        self.up = true
        self.tries = 0
        self.step('подключено, узнаю сервисы…')
        self.discover()
      }
      g.onBLEConnectionStateChange = safe('связь', function (state) {
        if (g !== self.gatt) return
        if (state === 2) up()
        else if (state === 0) self.up ? self.lost() : self.failed()
      })
      g.onBLECharacteristicChange = safe('ответ телефона', function (c) {
        if (g !== self.gatt || !c || !c.characteristicValue) return
        const v = new Uint8Array(c.characteristicValue)
        if (v.length > 0) self.heard(v)
      })
      // the watch's connect waits for the link with no limit of its own: 15 s, then let go and look again
      this.later(() => {
        if (g !== self.gatt || self.up) return
        self.failed()
      }, CONNECT_MS)
      g.connect({
        success: up,
        fail() {
          if (g === self.gatt && !self.up) self.failed()
        }
      })
    },

    /** The link did not come up: let go, look again; after a few tries, say what usually helps. */
    failed(reason) {
      this.drop()
      if (!this.on) return
      this.state = this.tries >= 3
        ? 'телефон найден, но не подключается. Часы уже связаны с ним через Mi Fitness — закройте Mi Fitness и нажмите «Старт»'
        : (reason || 'не подключилось') + ' — ищу снова'
      if (this.tries >= 3) {
        this.tries = 0
        this.on = false
        this.stopScan()
        this.keepAlive(false)
        this.screen()
        return
      }
      this.later(() => this.scan(), 2000)
    },

    /** Show a step of the connection and give it a time limit: if it does not answer, let go and try again. */
    step(text) {
      this.state = text
      if (this.stepTimer) clearTimeout(this.stepTimer)
      const self = this
      const g = this.gatt
      this.stepTimer = setTimeout(() => {
        this.stepTimer = 0
        if (g === self.gatt && !self.linkedOk) self.failed(text + ' — не отвечает')
      }, STEP_MS)
    },

    discover() {
      const self = this
      const g = this.gatt
      g.getServices({
        success(services) {
          if (g !== self.gatt) return
          let ok = false
          for (let i = 0; services && i < services.length; i++) {
            if (String(services[i].serviceUuid).toLowerCase() === SERVICE) ok = true
          }
          if (!ok) {
            self.state = 'это не тот телефон — ищу дальше'
            self.failed()
            return
          }
          self.step('включаю ответы…')
          // the phone's answers (vibration, pause/stream) are a nice-to-have; the remote itself works without them.
          // So: try to subscribe, but go on and stream anyway if it does not complete soon.
          self.notifyDone = false
          const proceed = function (feedback) {
            if (g !== self.gatt || self.notifyDone) return
            self.notifyDone = true
            self.feedback = feedback
            if (self.notifyTimer) clearTimeout(self.notifyTimer)
            self.notifyTimer = 0
            self.linked()
          }
          g.setNotifyCharacteristicChanged({
            characteristic: characteristic(EVENTS, new ArrayBuffer(0), { read: true, notify: true }),
            enable: true,
            success() { proceed(true) },
            complete() { proceed(false) }
          })
          self.notifyTimer = setTimeout(() => { self.notifyTimer = 0; proceed(false) }, NOTIFY_MS)
        },
        fail() {
          if (g === self.gatt) self.failed()
        }
      })
    },

    linked() {
      this.linkedOk = true
      this.linkedAt = Date.now()
      if (this.stepTimer) clearTimeout(this.stepTimer)
      this.stepTimer = 0
      this.state = this.feedback === false ? 'подключено (без вибро-отклика)' : 'подключено'
      // what happens on the way, on the screen: samples from the sensor, turns found, writes and how they ended
      this.n = { s: 0, p: 0, w: 0, ok: 0, bad: 0, to: 0, code: '' }
      this.ev = ''
      this.turn = ''
      this.playing = true
      if (this.lastRun) {
        // to the phone's log, so the reason can be seen there
        this.sendLine(this.lastRun)
        this.lastRun = ''
      }
      // the link's MTU is told again by the phone (CONFIG)
      this.mtu = 0
      this.chunk = CHUNK_UNKNOWN
      this.voiceFails = 0
      // a voice note not yet confirmed: all of it again (the phone of this link has none of it)
      for (let i = 0; i < this.voices.length; i++) this.voices[i].again()
      // bigger writes for voice notes, if the link takes them (the phone tells the result)
      try {
        this.gatt.setBLEMtuSize({ mtu: 247, success() {}, fail() {} })
      } catch (e) {}
      this.newPacker()
      this.listen(true)
    },

    /** A fresh packer: it measures the pace again and says HELLO with it (after a change of pace too); turns found here. */
    newPacker() {
      const self = this
      this.packer = createPacker({
        id: this.id,
        platform: 'vela',
        // HELLO says this app plays a conversation's answer from MP3 pieces (the watch's player takes no ADPCM WAV)
        version: VERSION_MP3,
        turns: true,
        send(buf) {
          self.send(buf)
        },
        onTurn(t) {
          self.turn = Math.round(Math.abs(t.twist)) + '° за ' + String(Math.round(t.took / 100) / 10).replace('.', ',') + ' с'
            + (t.source === SOURCE_COMPASS ? ' (компас)' : '')
          self.showDiag()
        },
        onFound(t, sent) {
          self.logLine('поворот ' + t.twist.toFixed(1) + '° за ' + Math.round(t.took) + ' мс ' + (t.source === SOURCE_COMPASS ? 'компас' : 'гравитация')
            + ' наклон ' + t.slope.toFixed(2) + ' Δ' + Math.round(t.slopeChange) + '°'
            + (sent ? ' — отправлен' : ' — не отправлен: ' + (!self.playing ? 'музыка на паузе' : 'заметка у рта')))
        },
        trace(e) {
          // what the finder saw but did not count (only while the phone keeps a log)
          if (!self.debug) return
          self.logLine('не поворот: ' + e.what + ' (' + e.ch + ') ' + Number(e.peak).toFixed(1) + '° за ' + Math.round(e.took) + ' мс'
            + (e.calm !== undefined ? ' неспокойно ' + Number(e.calm).toFixed(1) + '°' : ''), true)
        }
      })
    },

    // ---------------------------------------------------------------- the log (while the phone asks)

    /** The phone keeps a log: the sensor's samples and the watch's doings go to it (RAW, DIAG). */
    setDebug(on) {
      on = !!on
      if (on === !!this.debug) return
      this.debug = on
      this.rawBuf = []
      this.rawOut = []
      this.diagOut = []
      this.rawSeq = 0
      this.rawLost = 0
      if (on) {
        this.logLine('журнал: включён; темп ' + PACES[this.pace] + (this.paceKnown ? '' : ' (проверяется)') + ', компас '
          + (this.compassCode ? 'нет (' + this.compassCode + ')' : this.headingN ? 'есть' : 'ждёт') + ', заметки '
          + (this.voiceOn ? 'вкл' : 'выкл') + ', положений у рта ' + this.talk.poses.length + ', MTU ' + this.mtu)
      }
    },

    /** How many samples fit one RAW write on this link. */
    rawN() {
      const room = (this.mtu ? Math.min(this.mtu, 512) : 23) - 3
      return Math.max(1, Math.min(RAW_MAX, Math.floor((room - 7) / 10)))
    },

    rawSample(t, x, y, z, h) {
      if (this.mtu < RAW_MIN_MTU) {
        if (!this.rawRefused) {
          this.rawRefused = true
          this.logLine('отсчёты в журнал не шлю: MTU связи ' + (this.mtu || 23) + ' — каждая запись по одному отсчёту, часам тяжело')
        }
        return
      }
      const b = this.rawBuf
      b.push({ t, x: Number(x) || 0, y: Number(y) || 0, z: Number(z) || 0, h })
      if (b.length >= this.rawN() || t - b[0].t >= 1000) {
        this.rawOut.push(rawPacket(this.rawSeq++, b))
        this.rawBuf = []
        // the link slower than the sensor: the oldest dropped (told in the next line)
        while (this.rawOut.length > 40) {
          this.rawOut.shift()
          this.rawLost++
        }
        this.pump()
      }
    },

    /** A line for the phone's log (only while it keeps one); a finder's line at most a few a second. */
    logLine(text, chatty) {
      if (!this.debug) return
      const now = Date.now()
      if (chatty) {
        if (now - (this.chattyAt || 0) > 1000) {
          this.chattyAt = now
          this.chattyN = 0
        }
        if (++this.chattyN > 4) return
      }
      const room = (this.mtu ? Math.min(this.mtu, 512) : 23) - 3
      if (this.rawLost) {
        text = '(потеряно отсчётных пакетов: ' + this.rawLost + ') ' + text
        this.rawLost = 0
      }
      for (const p of diagPackets(text, room, 6)) this.diagOut.push(p)
      while (this.diagOut.length > 40) this.diagOut.shift()
      this.pump()
    },

    /**
     * The counters line: the last turn found · sensor samples and pace · turns sent · writes (✓ done, ✗ failed, ⏱ no
     * answer in time) · the phone's last word.
     */
    showDiag() {
      const n = this.n
      if (!n) return
      const h = this.headingNow(Date.now())
      const compass = this.compassCode ? 'компаса нет (' + this.compassCode + ')'
        : Number.isNaN(h) ? (this.headingN ? 'компас молчит' : 'компас ждёт') : 'компас ' + Math.round(((h * 180) / Math.PI + 360) % 360) + '°'
      this.diag = (this.turn ? 'поворот ' + this.turn + ' · ' : '') + 'датчик ' + n.s + ' (' + PACES[this.pace] + ') · ' + compass
        + ' · повороты ' + n.p + ' · записи ' + n.w + ': ✓' + n.ok + ' ✗' + n.bad + (n.code !== '' ? ' (' + n.code + ')' : '')
        + ' ⏱' + n.to + (this.ev ? ' · телефон: ' + this.ev : '')
    },

    lost() {
      this.drop()
      if (!this.on) return
      this.state = 'связь потеряна — ищу'
      this.phase('связь потеряна')
      this.later(() => this.scan(), 2000)
    },

    drop() {
      if (this.stepTimer) clearTimeout(this.stepTimer)
      this.stepTimer = 0
      if (this.notifyTimer) clearTimeout(this.notifyTimer)
      this.notifyTimer = 0
      if (this.writeTimer) clearTimeout(this.writeTimer)
      this.writeTimer = 0
      if (this.doneTimer) clearTimeout(this.doneTimer)
      this.doneTimer = 0
      this.writing = false
      this.linkedOk = false
      this.listen(false)
      this.queue = []
      this.writing = false
      if (this.rec || this.pending || this.hs) this.stopNote(false)
      const g = this.gatt
      this.gatt = null
      if (g) {
        try {
          g.disconnect({})
          g.close()
        } catch (e) {}
      }
    },

    /** What the phone said (one byte, or more for the settings and the voice notes). */
    heard(v) {
      const e = parseEvent(v)
      const ev = e.ev
      if (ev === EV_PAUSE) {
        this.ev = 'пауза'
        this.playing = false
        this.mute()
        if (this.voiceOn) {
          // the sensor stays on for the voice notes; no turns are sent
          this.state = 'музыка не играет · поднесите часы ко рту — заметка'
        } else {
          this.listen(false)
          this.state = 'на телефоне не играет музыка — жду'
        }
      } else if (ev === EV_STREAM) {
        this.ev = 'играет'
        this.playing = true
        if (this.packer) this.packer.restart()
        this.mute()
        this.listen(true)
      } else if (ev === EV_CONFIG) {
        this.leftHand = e.leftHand
        this.talk.setHand(e.leftHand)
        if (e.mtu > 23) this.mtu = e.mtu
        this.chunk = this.mtu ? Math.max(CHUNK_MIN, Math.min(this.mtu, 512) - 3 - 6) : CHUNK_UNKNOWN
        this.setDebug(e.debug)
        this.talkMode = e.talk
        this.talkWatch = e.talkWatch
        this.talkHeadset = e.talkHeadset
        this.talk.setTalk(e.talk)
        this.logLine('настройки: ' + (e.leftHand ? 'левая' : 'правая') + ' рука, заметки ' + (e.voice ? 'вкл' : 'выкл') + (e.talk
          ? ', у рта — разговор с Gemini (' + (e.talkHeadset ? 'часы как гарнитура' : 'ответ ' + (e.talkWatch ? 'в часах' : 'в телефоне')) + ')'
          : '') + ', MTU ' + e.mtu)
        if (e.voice !== this.voiceOn) {
          this.voiceOn = e.voice
          storage.set({ key: 'wrist_voice', value: e.voice ? '1' : '0' })
          if (!e.voice && (this.rec || this.pending || this.hs)) this.stopNote(true)
        }
        if (this.voiceOn && !this.listening && this.linkedOk) this.listen(true)
        else if (!this.voiceOn && !this.playing) this.listen(false)
        return
      } else if (ev === EV_VOICE_FROM) {
        const n = this.voices[0]
        if (n && n.id === e.id) {
          // a write lost on the way: again from there (and the writes smaller, if they keep getting lost there)
          if (n.askedAt === e.offset && ++n.asked >= 3 && this.chunk > CHUNK_MIN) {
            this.chunk = Math.max(CHUNK_MIN, Math.floor(this.chunk / 3))
            n.asked = 0
          } else if (n.askedAt !== e.offset) {
            n.askedAt = e.offset
            n.asked = 1
          }
          n.from(e.offset)
          n.endAt = 0
          this.pump()
        }
        return
      } else if (ev === EV_VOICE_DONE) {
        this.voiceDone(e)
        return
      } else if (ev === EV_TALK_AUDIO) {
        this.talkAudio(e)
        return
      } else if (ev === EV_TALK_SEG) {
        this.talkSegment(e)
        return
      } else if (ev === EV_TALK_TEXT) {
        this.note = e.line
        this.logLine('Gemini: ' + e.line, true)
        return
      } else if (ev === EV_TALK_END) {
        this.logLine('разговор окончен')
        this.phase('разговор окончен')
        return
      } else if (ev === EV_NEXT || ev === EV_PREVIOUS) {
        this.ev = ev === EV_NEXT ? 'вперёд' : 'назад'
        vibrator.vibrate({ mode: 'short' })
      } else if (ev === EV_LEARNED) {
        this.ev = 'выучено'
        vibrator.vibrate({ mode: 'long' })
      } else if (ev === EV_IGNORED) {
        // a turn while nothing plays: nothing to feel
        this.ev = 'поворот без музыки'
      }
      this.logLine('телефон сказал: ' + this.ev)
      this.showDiag()
    },

    // ---------------------------------------------------------------- the samples

    listen(on) {
      if (on === this.listening) return
      this.listening = on
      if (!on) {
        if (this.reSub) clearTimeout(this.reSub)
        this.reSub = 0
        try {
          sensor.unsubscribeAccelerometer()
        } catch (e) {}
        try {
          sensor.unsubscribeCompass()
        } catch (e) {}
        return
      }
      const self = this
      this.state = 'слушаю повороты кисти' + (this.feedback === false ? ' (без отклика)' : '')
      this.subscribe()
      this.compass()
      // revive the sensor only when it actually stalls (no sample for 1.5 s) — a healthy stream is never interrupted,
      // so the writes to the phone are not disturbed; a pace that gives nothing at all is given up for the next
      const guard = function () {
        if (!self.listening) return
        const now = Date.now()
        if (!self.paceKnown && self.paceN === 0 && now - self.subAt >= PROBE_MS && self.pace < PACES.length - 1) {
          self.switchPace()
        } else if (now - self.lastSampleAt > 1500) {
          self.logLine('датчик молчал ' + (now - self.lastSampleAt) + ' мс — подписываюсь заново')
          try {
            sensor.unsubscribeAccelerometer()
          } catch (e) {}
          self.subscribe()
        }
        self.reSub = setTimeout(guard, 1000)
      }
      this.reSub = setTimeout(guard, 1000)
    },

    /** The accelerometer on at the current pace. */
    subscribe() {
      const self = this
      this.firstRaw = null
      this.moved = false
      this.paceN = 0
      this.subAt = Date.now()
      this.lastSampleAt = Date.now()
      this.phase('датчик ' + PACES[this.pace])
      sensor.subscribeAccelerometer({
        interval: PACES[this.pace],
        callback(ret) {
          self.sampled(ret)
        },
        fail(data, code) {
          self.state = 'акселерометр: ошибка ' + code
        }
      })
    },

    /**
     * The compass: with the arm hanging along the body (the forearm upright) a twist turns the watch around gravity, which
     * the accelerometer cannot see — but the heading turns by the twist. Its latest value goes with each sample.
     */
    compass() {
      const self = this
      this.heading = NaN
      this.headingAt = 0
      this.headingN = 0
      this.compassCode = ''
      try {
        sensor.subscribeCompass({
          callback: safe('компас', function (ret) {
            if (!ret || typeof ret.direction !== 'number') return
            self.headingN++
            // accuracy −1: the sensor is gone; the rest (even «unreliable, reason unknown») still turns with the watch
            if (ret.accuracy === -1) return
            self.heading = ret.direction
            self.headingAt = Date.now()
          }),
          fail(data, code) {
            self.compassCode = String(code)
            self.logLine('компас: ошибка ' + code)
          }
        })
      } catch (e) {
        this.compassCode = 'нет'
      }
    },

    /** The heading to go with a sample: the latest, if it is fresh (radians), else NaN. */
    headingNow(now) {
      return now - this.headingAt <= 1000 ? this.heading : NaN
    },

    /** The pace's values never changed (or none came): the next, slower pace, measured again with a new HELLO. */
    switchPace() {
      this.pace = Math.min(this.pace + 1, PACES.length - 1)
      this.logLine('темп датчика: ' + PACES[this.pace] + ' — прежний не отдавал живых чисел')
      try {
        sensor.unsubscribeAccelerometer()
      } catch (e) {}
      this.newPacker()
      this.subscribe()
    },

    sampled(ret) {
      if (!this.listening || !this.packer || !ret) return
      const now = Date.now()
      this.lastSampleAt = now
      this.paceN++
      if (this.n) this.n.s++
      if (!this.firstRaw) {
        this.firstRaw = [ret.x, ret.y, ret.z]
      } else if (ret.x !== this.firstRaw[0] || ret.y !== this.firstRaw[1] || ret.z !== this.firstRaw[2]) {
        this.moved = true
      }
      if (!this.paceKnown && now - this.subAt >= PROBE_MS) {
        // a real sensor never gives exactly the same numbers for seconds; the S5's «game» did
        if (this.moved || this.pace === PACES.length - 1) {
          this.paceKnown = true
        } else {
          this.switchPace()
          return
        }
      }
      if (this.voiceOn) this.talked(this.talk.sample(ret.x, ret.y, ret.z, now), now)
      this.mute()
      this.trail(now)
      if (this.debug) this.rawSample(now, ret.x, ret.y, ret.z, this.headingNow(now))
      this.packer.sample(ret.x, ret.y, ret.z, now, this.headingNow(now))
      if (now - (this.rawAt || 0) > 400) {
        this.rawAt = now
        // the raw values (one decimal), and whether any differed from the first — «✓» live, «стоит» static
        const f1 = function (v) {
          return String(Math.round((Number(v) || 0) * 10) / 10)
        }
        // and how far the hand is from where it last rested (what a turn is measured by)
        const fd = this.packer.finder
        this.state = 'x' + f1(ret.x) + ' y' + f1(ret.y) + ' z' + f1(ret.z) + (this.moved ? ' ✓' : ' стоит')
          + (fd ? ' · наклон ' + Math.round(fd.tilt) + '°' : '')
        this.showDiag()
      }
    },

    /** One write at a time (a packet per turn; queued, in case turns come faster than the link). */
    send(buf) {
      if (this.n && new Uint8Array(buf)[0] !== 0) this.n.p++
      this.queue.push(buf)
      while (this.queue.length > 20) this.queue.splice(1, 1) // the HELLO in front stays
      this.pump()
    },

    pump() {
      if (this.writing || !this.gatt || !this.linkedOk) return
      const self = this
      const g = this.gatt
      // the turns first (short, now or never), the watch's lines for the log, a voice note's next bytes, the sensor's
      // samples for the log
      let buf = null
      let voice = null
      if (this.queue.length) buf = this.queue.shift()
      else if ((this.debug || this.forceLines) && this.diagOut.length) {
        buf = this.diagOut.shift()
        if (!this.diagOut.length) this.forceLines = false
      }
      else {
        if (this.voices[0]) {
          buf = this.voiceNext(this.voices[0])
          if (buf) voice = this.voices[0]
        }
        if (!buf && this.debug && this.rawOut.length) buf = this.rawOut.shift()
      }
      if (!buf) return
      const head = new Uint8Array(buf)
      const at = head[0] === 0x51 ? (head[2] | (head[3] << 8) | (head[4] << 16) | (head[5] << 24)) >>> 0 : -1
      const id = ++this.writeId
      const n = this.n
      this.writing = true
      if (n) n.w++
      // the write's completion comes as a separate event on Vela and may not arrive; don't let that stall the stream —
      // the next write goes on completion, or after a short time, whichever comes first (a late completion of an
      // earlier write is counted but does not release a newer one)
      const next = function () {
        if (g !== self.gatt || id !== self.writeId || !self.writing) return
        self.writing = false
        if (self.writeTimer) clearTimeout(self.writeTimer)
        self.writeTimer = 0
        // the next write a moment later, never from inside this one's callback (a completion told at once would
        // otherwise call write in write in write — a deep stack and a busy watch)
        setTimeout(() => self.pump(), WRITE_GAP_MS)
      }
      let settled = false
      const settle = function (bad, code) {
        if (settled) return
        settled = true
        if (n && bad) {
          n.bad++
          n.code = code === undefined ? '?' : code
          self.logLine('запись в Bluetooth не прошла: код ' + n.code + ' (' + head[0].toString(16) + ')', true)
        } else if (n) {
          n.ok++
        }
        if (bad && at >= 0 && voice && self.voices[0] === voice) {
          // a note's bytes not written: from there again, smaller if it keeps failing (no long writes on this link)
          voice.from(Math.min(voice.sent, at))
          if (++self.voiceFails >= 2 && self.chunk > CHUNK_MIN) {
            self.chunk = CHUNK_MIN
            self.voiceFails = 0
          }
        }
        next()
      }
      try {
        g.writeCharacteristicValue({
          characteristic: characteristic(SAMPLES, buf, { write: true, writeNoResponse: true }),
          success() {
            settle(false)
          },
          fail(data, code) {
            settle(true, code)
          },
          complete() {
            settle(false)
          }
        })
      } catch (e) {
        // refused at once: a failed write, and the next one after the usual pause
        settle(true, 'исключение ' + (e && e.message ? e.message : e))
      }
      if (id === this.writeId && this.writing) {
        this.writeTimer = setTimeout(() => {
          if (!settled && n) n.to++
          next()
        }, voice ? VOICE_WRITE_MS : WRITE_MS)
      }
    },

    // ---------------------------------------------------------------- voice notes

    /** No turns sent while the music is paused or a note is being said. */
    mute() {
      if (this.packer) this.packer.mute = !this.playing || this.talk.busy() || this.talk.teaching()
    },

    /** What the hand did (createTalk): held to the mouth — record; taken away — the note goes to the phone. */
    talked(what, now) {
      if (what) this.logLine('у рта: ' + what + (this.talk.poses.length ? ' (по запомненному положению)' : ''))
      if (what === 'start') this.startNote(now)
      else if (what === 'stop') this.stopNote(false)
      else if (what === 'cancel') this.stopNote(true)
      else if (what === 'taught') {
        this.savePoses()
        vibrator.vibrate({ mode: 'long' })
        this.chime(CHIME_END)
        this.note = 'запомнил, как вы подносите часы ко рту (' + this.poses + ' из 3) — опустите руку'
      } else if (what === 'teach-failed') {
        this.note = 'не получилось: часы не замерли у рта — попробуйте ещё раз'
      } else if (what === 'teach-unmoved') {
        this.note = 'не получилось: часы остались, где были, — после нажатия поднесите их ко рту'
      }
      // where the arm hangs, kept now and then (which way the fingers are, see createTalk)
      if (now - (this.fingersSaved || 0) > 30000) {
        this.fingersSaved = now
        storage.set({ key: 'wrist_fingers', value: JSON.stringify(this.talk.down.map((s) => Math.round(s))) })
      }
    },

    /**
     * At the mouth: a short buzz and the rising bells — speak now; the recording starts as the bells end, so they are
     * not in it.
     */
    startNote(now) {
      if (this.rec || this.pending || this.hs) return
      this.noteId = (this.noteId + 1) & 0xff
      vibrator.vibrate({ mode: 'short' })
      if (this.talkMode && this.talkHeadset) {
        // the watch as a headset: the voice and the answer go through its Bluetooth call channel — here only «started»
        // (the phone opens the channel and gives a tone when Gemini listens) and later «ended»
        const v = createVoice(this.noteId, 'none', PCM_REC.sampleRate, true)
        v.codec = CODEC_LIVE
        this.voices.push(v)
        this.hs = { v, at: now }
        this.note = 'говорите с Gemini — через гарнитуру часов'
        this.phase('разговор через гарнитуру')
        this.pump()
        return
      }
      const live = !!this.talkMode
      this.note = live ? 'говорите с Gemini… опустите руку — конец' : 'говорите… опустите руку — отправлю'
      const pending = { at: now }
      if (live) {
        // a conversation: its start told at once, so the phone connects to Gemini while the bells ring
        const v = createVoice(this.noteId, PCM_REC.voice, PCM_REC.sampleRate, true)
        v.codec = CODEC_ADPCM
        this.voices.push(v)
        pending.v = v
        this.pump()
      }
      this.pending = pending
      const self = this
      this.chime(CHIME_START, function () {
        if (self.pending !== pending) return // taken away before the bells ended
        self.pending = null
        self.record(PCM_REC, Date.now(), live, pending.v)
      })
    },

    /** Teach how this hand holds the watch to the mouth (the page's button): the next still pose there. */
    teachTalk() {
      if (!this.voiceOn) {
        this.note = 'заметки голосом выключены на телефоне'
        return
      }
      if (!this.listening) {
        this.note = 'сначала подключитесь к телефону — датчик включается с ним'
        return
      }
      this.talk.teach(Date.now())
      vibrator.vibrate({ mode: 'short' })
      this.note = 'поднесите часы ко рту, как будто говорите…'
      // in two seconds: a buzz — hold still there
      const self = this
      if (this.teachTimer) clearTimeout(this.teachTimer)
      this.teachTimer = setTimeout(() => {
        self.teachTimer = 0
        if (!self.talk.teaching()) return
        vibrator.vibrate({ mode: 'short' })
        self.note = 'держите так…'
      }, 2000)
    },

    /** Forget the taught poses: «at the mouth» by the usual rule again. */
    forgetTalk() {
      this.talk.forget()
      this.savePoses()
      this.note = 'положения забыты — снова по общему правилу'
    },

    savePoses() {
      const p = this.talk.poses
      this.poses = p.length
      storage.set({ key: 'wrist_poses', value: JSON.stringify(p.map((v) => v.map((c) => Math.round(c * 1000) / 1000))) })
    },

    /** Recording in one format; PCM not taken (202 — wrong parameters) — Opus. */
    record(opts, now, live, started) {
      const self = this
      const v = started || createVoice(this.noteId, opts.voice || opts.format, opts.sampleRate, live)
      const rec = { v, opts, at: now, ms: 0, stopping: false, cancel: false, frames: 0, raw: 0, live: !!live }
      this.rec = rec
      if (!started) this.voices.push(v)
      record.onframerecorded = safe('кадр записи', function (res) {
        if (self.rec !== rec || !res) return
        if (res.frameBuffer && !rec.cancel) {
          const f = res.frameBuffer instanceof Uint8Array ? res.frameBuffer : new Uint8Array(res.frameBuffer)
          rec.frames++
          rec.raw += f.length
          if (rec.frames <= 3) {
            // what the recorder gives, for the log (the first frames' size and first bytes)
            let h = ''
            for (let i = 0; i < Math.min(12, f.length); i++) h += (f[i] < 16 ? '0' : '') + f[i].toString(16)
            self.logLine('кадр записи ' + rec.frames + ': ' + f.length + ' байт, ' + h)
          }
          v.add(f)
          self.pump()
        }
        if (res.isLastFrame) self.endNote(rec)
      })
      this.logLine('запись: ' + opts.format + ' ' + opts.sampleRate + ' Гц')
      this.phase('запись ' + opts.format)
      const params = {
        format: opts.format,
        sampleRate: opts.sampleRate,
        numberOfChannels: opts.numberOfChannels,
        encodeBitRate: opts.encodeBitRate,
        frameSize: opts.frameSize,
        success(data) {
          // over (stopped, or cut short by a call or an alarm); no frames came — then the file it wrote
          if (self.rec !== rec) return
          if (v.len === 0 && data && data.uri) self.readNote(rec, data.uri)
          else self.endNote(rec)
        },
        fail(data, code) {
          if (self.rec !== rec) return
          self.logLine('запись не началась: код ' + code)
          self.rec = null
          if (live) {
            // a conversation needs the recorder's PCM (the phone streams ADPCM on): its end told, nothing more
            v.end(0)
            self.pump()
            self.note = 'запись не началась (' + code + ') — разговор не получится'
            self.talk.done(Date.now())
            return
          }
          self.dropVoice(v)
          if (code === 202 && opts === PCM_REC) {
            self.record(OPUS, now, live)
            return
          }
          self.note = 'запись не началась (' + code + ')'
          self.talk.done(Date.now())
        }
      }
      try {
        record.start(params)
      } catch (e) {
        params.fail(null, 'нет')
      }
    },

    /** Taken away from the mouth (cancel: too soon, or voice notes switched off): the recording stops. */
    stopNote(cancel) {
      if (this.hs) {
        // the conversation through the headset: its end told (the answer may still be coming)
        const hs = this.hs
        this.hs = null
        hs.v.end(Date.now() - hs.at)
        this.pump()
        this.talk.done(Date.now())
        this.note = cancel ? '' : 'Gemini отвечает…'
        if (!cancel) vibrator.vibrate({ mode: 'short' })
        return
      }
      if (!this.rec && this.pending) {
        // taken away while the bells still rang: no note (a conversation already told — its end told too)
        if (this.pending.v) {
          this.pending.v.end(0)
          this.pump()
        }
        this.pending = null
        this.note = ''
        this.talk.done(Date.now())
        return
      }
      const rec = this.rec
      if (!rec || rec.stopping) return
      rec.v.pose = this.talk.lastPose()
      rec.stopping = true
      rec.cancel = cancel
      rec.ms = Date.now() - rec.at
      try {
        record.stop()
      } catch (e) {}
      if (!cancel) vibrator.vibrate({ mode: 'short' })
      // the last frame (or the success callback) ends it; if neither comes, it ends anyway
      const self = this
      setTimeout(() => {
        if (self.rec === rec) self.endNote(rec)
      }, 1500)
    },

    readNote(rec, uri) {
      const self = this
      file.readArrayBuffer({
        uri,
        success(data) {
          if (data && data.buffer) rec.v.add(data.buffer)
          self.endNote(rec)
        },
        fail() {
          self.endNote(rec)
        }
      })
    },

    endNote(rec) {
      if (this.rec !== rec) return
      this.rec = null
      if (!rec.ms) rec.ms = Date.now() - rec.at
      this.phase('запись кончилась: ' + rec.v.len + ' байт к отправке')
      this.talk.done(Date.now())
      this.mute()
      if (this.packer) this.packer.restart()
      this.logLine('запись кончилась: кадров ' + rec.frames + ', ' + rec.raw + ' байт от записи → ' + rec.v.len + ' к отправке, ' + rec.ms
        + ' мс, уровень ' + rec.v.peak.toFixed(2) + (rec.cancel ? ', отменена (коротко)' : rec.stopping ? '' : ', прервана часами'))
      if (rec.cancel || rec.v.len === 0) {
        this.resumeAlive()
        this.dropVoice(rec.v)
        this.note = rec.cancel ? '' : 'запись пустая — микрофон не дал звука'
        return
      }
      rec.v.end(rec.ms)
      rec.v.endAt = 0
      if (rec.live) {
        // a conversation: the end said, the answer comes as it is made (no bells over it)
        this.note = 'Gemini отвечает…'
        this.pump()
        this.resumeAlive()
        return
      }
      this.note = 'отправляю заметку…'
      this.pump()
      // the falling bells: the recording is over (played now, so they are not in it)
      this.chime(CHIME_END)
    },

    /** After a recording: the background's sound again, if the recorder stopped it. */
    resumeAlive() {
      if (this.alive && !this.audioPlaying && !this.aliveTimer && !this.chiming && !this.talkPlaying && audio) this.play()
    },

    // ---------------------------------------------------------------- a conversation's answer

    /** A piece of the answer's WAV file: kept in order (anything else — a lost write — spoils the piece). */
    talkAudio(e) {
      let t = this.talkIn
      if (!t || t.seg !== e.seg) {
        t = { seg: e.seg, parts: [], len: 0, bad: false }
        this.talkIn = t
      }
      if (e.offset !== t.len) {
        t.bad = true
        return
      }
      t.parts.push(new Uint8Array(e.data))
      t.len += e.data.length
    },

    /** A piece whole: written to a file and played after those before it; spoilt — told so (the phone moves on). */
    talkSegment(e) {
      const t = this.talkIn
      this.talkIn = null
      if (!t || t.seg !== e.seg || t.bad || t.len !== e.total) {
        this.talkLog('кусок ' + e.seg + ' не целый (' + (t ? t.len : 0) + ' из ' + e.total + ' Б)')
        this.send(voicePlayed(this.noteId, e.seg, NOT_WHOLE))
        return
      }
      const buf = new Uint8Array(t.len)
      let o = 0
      for (const p of t.parts) {
        buf.set(p, o)
        o += p.length
      }
      const uri = 'internal://files/talk' + (e.seg % TALK_FILES) + (e.format === TALK_MP3 ? '.mp3' : '.wav')
      const self = this
      const seg = e.seg
      const ms = e.ms
      const kind = e.format === TALK_MP3 ? 'MP3' : 'WAV'
      this.talkLog('кусок ' + seg + ': ' + buf.length + ' Б ' + kind + ', ' + ms + ' мс → ' + uri)
      file.writeArrayBuffer({
        uri,
        buffer: buf,
        success: safe('ответ: файл', function () {
          self.talkQueue.push({ seg, uri, ms, buf, kind })
          self.playTalk()
        }),
        fail: safe('ответ: файл', function (data, code) {
          self.talkLog('кусок ' + seg + ': файл не записан (' + code + ' ' + describe(data) + ')')
          self.send(voicePlayed(self.noteId, seg, NOT_WRITTEN))
        })
      })
    },

    /** The next piece of the answer, if none plays (the bells first, if they ring). */
    playTalk() {
      if (this.talkPlaying || this.chiming || !audio) return
      const next = this.talkQueue.shift()
      if (!next) {
        this.resumeAlive()
        return
      }
      this.talkPlaying = next
      next.t0 = Date.now()
      next.started = false
      this.phase('ответ: кусок ' + next.seg)
      this.hookAudio()
      const self = this
      // a piece that never says it ended: played, if the player said it started; else the player said nothing at all
      this.talkTimer = setTimeout(() => {
        self.talkLog('кусок ' + next.seg + ': конца нет за ' + (Date.now() - next.t0) + ' мс' + (next.started ? ' — считаю сыгранным' : ', и начала не было'))
        self.talkEnded(next, next.started ? PLAYED : SILENT)
      }, next.ms + TALK_SLACK_MS)
      try {
        this.halt()
        this.audioPlaying = false
        audio.src = next.uri
        audio.loop = false
        audio.play()
        this.talkLog('кусок ' + next.seg + ': играю ' + next.uri)
      } catch (e) {
        this.talkFailed(next, describe(e))
      }
    },

    /**
     * The player would not play a piece: once more from the cache folder (where the watch's own recordings are played
     * from), then told so.
     */
    talkFailed(piece, why) {
      if (!piece || this.talkPlaying !== piece) return
      this.talkLog('кусок ' + piece.seg + ': плеер не играет ' + piece.uri + ' — ' + why)
      if (piece.buf && piece.uri.indexOf('internal://files/') === 0) {
        const self = this
        const uri = piece.uri.replace('internal://files/', 'internal://cache/')
        this.talkPlaying = null
        if (this.talkTimer) clearTimeout(this.talkTimer)
        this.talkTimer = 0
        file.writeArrayBuffer({
          uri,
          buffer: piece.buf,
          success: safe('ответ: файл', function () {
            self.talkQueue.unshift({ seg: piece.seg, uri, ms: piece.ms, kind: piece.kind })
            self.playTalk()
          }),
          fail: safe('ответ: файл', function (data, code) {
            self.talkLog('кусок ' + piece.seg + ': и в ' + uri + ' не записан (' + code + ')')
            self.send(voicePlayed(self.noteId, piece.seg, NOT_PLAYED))
            self.playTalk()
          })
        })
        return
      }
      this.talkEnded(piece, NOT_PLAYED)
    },

    /** What happens to the answer's pieces, always to the phone's log (a few lines a piece), not only while debugging. */
    talkLog(text) {
      const room = (this.mtu ? Math.min(this.mtu, 512) : 23) - 3
      for (const p of diagPackets('ответ: ' + text, room, 6)) this.diagOut.push(p)
      while (this.diagOut.length > 40) this.diagOut.shift()
      this.forceLines = true // sent with debugging off too
      this.pump()
    },

    /** A piece played (or not — the outcome, PLAYED…): the phone told, the next one played. */
    talkEnded(piece, outcome) {
      if (!piece || this.talkPlaying !== piece) return
      this.talkPlaying = null
      if (this.talkTimer) clearTimeout(this.talkTimer)
      this.talkTimer = 0
      this.send(voicePlayed(this.noteId, piece.seg, outcome))
      this.playTalk()
    },

    dropVoice(v) {
      const i = this.voices.indexOf(v)
      if (i >= 0) this.voices.splice(i, 1)
    },

    /** The next write of the first note on its way; after the end, the phone's answer is waited for (and asked again). */
    voiceNext(v) {
      const p = v.next(this.chunk)
      if (v.live) {
        // a conversation: no outcome waited for — once its end is out, it is done here
        if (!p && v.ended && v.endSent) this.dropVoice(v)
        return p
      }
      if (p) {
        if (v.ended && v.endSent && !v.endAt) v.endAt = Date.now()
        if (v.len) this.note = this.rec && this.rec.v === v ? 'говорите… опустите руку — отправлю'
          : 'отправляю заметку… ' + Math.min(99, Math.round((100 * v.sent) / v.len)) + '%'
        return p
      }
      if (v.ended && v.endSent && v.endAt && Date.now() - v.endAt > DONE_MS) {
        // no answer: the end said again (the phone may have missed it); after three — given up
        v.tries = (v.tries || 0) + 1
        if (v.tries >= 3) {
          this.dropVoice(v)
          this.note = 'телефон не ответил — заметка не сохранена'
          return null
        }
        v.endSent = false
        v.endAt = 0
        return this.voiceNext(v)
      }
      if (v.ended && v.endSent && !this.doneTimer) {
        // look again when the answer is due
        const self = this
        this.doneTimer = setTimeout(() => {
          self.doneTimer = 0
          self.pump()
        }, DONE_MS + 100)
      }
      return null
    },

    /** What became of a note: a vibration and its line (a long buzz — a note made). */
    voiceDone(e) {
      const v = this.voices[0]
      if (!v || v.id !== e.id) return
      this.logLine('заметка ' + e.id + ': итог ' + e.result)
      this.dropVoice(v)
      if (e.result === VOICE_SAVED || e.result === VOICE_KEPT || e.result === VOICE_ANSWER) {
        vibrator.vibrate({ mode: 'long' })
        // said there and understood: the taught pose moves a little toward how the watch was held
        if (e.result !== VOICE_KEPT && this.talk.nudge(v.pose)) this.savePoses()
      } else vibrator.vibrate({ mode: 'short' })
      this.note = e.line || (e.result === VOICE_SILENT ? 'ничего не расслышал' : e.result === VOICE_SAVED ? 'заметка сохранена' : 'не вышло')
      this.pump()
    }
  }
  for (const k of Object.keys(view)) Object.defineProperty(L, k, shown(k))
  // every method guarded: an error in one (a sensor's or Bluetooth's callback, a timer) is shown, kept and logged, and
  // the app goes on — an uncaught error here would end the whole app (it lives in app.ux, not in the page)
  for (const k of Object.keys(L)) {
    const f = L[k]
    if (typeof f !== 'function' || k === 'oops') continue
    L[k] = function () {
      try {
        return f.apply(this, arguments)
      } catch (e) {
        L.oops(k, e)
        return undefined
      }
    }
  }
  return L
}
