// «Пульт кистью»: the watch's side of the protocol of the phone's WristGesture (SemSearch → Настройки → Часы).
// The same file serves the Xiaomi Vela and the Zepp OS apps (and is checked against the phone's Java in the tests).
// The watch finds the turn itself (createFinder — the phone's WristGesture.Finder, line for line) and sends one ROLL
// packet per turn (how far it twisted around the forearm, how long, seen by gravity or the compass); the phone decides
// what it means. Or it streams the raw samples (ACCEL, 3 a packet) and the phone finds the turn. And a voice note: the
// watch held to the mouth (createTalk) records, and the recording goes in VOICE packets (start, the bytes, end —
// createVoice); the phone makes it a note and says what became of it. Little-endian, 20 bytes at most (a voice note's
// bytes in bigger writes when the link takes them).

export const SERVICE = 'a7b7e001-9ca3-4574-a1f0-3c9840e24d01'
export const SAMPLES = 'a7b7e002-9ca3-4574-a1f0-3c9840e24d01'
export const EVENTS = 'a7b7e003-9ca3-4574-a1f0-3c9840e24d01'
export const CCCD = '00002902-0000-1000-8000-00805f9b34fb'
export const EV_IGNORED = 0
export const EV_NEXT = 1
export const EV_PREVIOUS = 2
export const EV_LEARNED = 3
export const EV_PAUSE = 4
export const EV_STREAM = 5
export const EV_CONFIG = 6
export const EV_VOICE_FROM = 8
export const EV_VOICE_DONE = 9
export const CFG_LEFT_HAND = 1
export const CFG_VOICE = 2
export const CFG_DEBUG = 4
export const VOICE_SAVED = 0
export const VOICE_SILENT = 1
export const VOICE_KEPT = 2
export const VOICE_ANSWER = 3
export const VOICE_FAILED = 4
export const CODEC_RECORDED = 1
export const CODEC_PCM16 = 2
export const CODEC_WAV = 3
export const CODEC_OPUS_PACKETS = 4
export const CODEC_ADPCM = 5
export const SOURCE_GRAVITY = 1
export const SOURCE_COMPASS = 2
/** HELLO versions: samples follow / the watch finds the turns and ROLL packets follow. */
export const VERSION = 1
export const VERSION_TURNS = 2

function put16(b, o, v) {
  v = v & 0xffff
  b[o] = v & 0xff
  b[o + 1] = (v >> 8) & 0xff
}

export function clamp16(v) {
  v = Math.round(v)
  return v > 32767 ? 32767 : v < -32768 ? -32768 : v
}

/** HELLO: who the watch is (a random id it keeps), samples a second, counts per g, the platform's name; the version. */
export function hello(id, rate, perG, platform, version) {
  const name = String(platform || '').slice(0, 10)
  const b = new Uint8Array(10 + name.length)
  b[0] = 0x00
  b[1] = version || VERSION
  put16(b, 2, id & 0xffff)
  put16(b, 4, (id >>> 16) & 0xffff)
  put16(b, 6, rate)
  put16(b, 8, perG)
  for (let i = 0; i < name.length; i++) b[10 + i] = name.charCodeAt(i) & 0x7f
  return b.buffer
}

/** ACCEL: n samples {x, y, z, x, y, z…} as int16. */
export function accel(seq, xyz, n) {
  const b = new Uint8Array(2 + 6 * n)
  b[0] = 0x10 | n
  b[1] = seq & 0xff
  for (let i = 0; i < 3 * n; i++) put16(b, 2 + 2 * i, clamp16(xyz[i]))
  return b.buffer
}

/**
 * ROLL: the twist (degrees, right-handed around the watch's x — the forearm), how long (ms), seen by gravity (1) or
 * the compass (2), the forearm's slope at rest (cos between «up» and x) and how far it changed meanwhile (degrees).
 */
export function rollPacket(seq, twist, took, source, slope, slopeChange) {
  const b = new Uint8Array(8)
  b[0] = 0x40
  b[1] = seq & 0xff
  put16(b, 2, clamp16(twist * 10))
  b[4] = Math.max(0, Math.min(255, Math.round(took / 10)))
  b[5] = source
  b[6] = Math.max(-100, Math.min(100, Math.round(slope * 100))) & 0xff
  b[7] = Math.max(0, Math.min(255, Math.round(slopeChange)))
  return b.buffer
}

function put32(b, o, v) {
  put16(b, o, v & 0xffff)
  put16(b, o + 2, (v >>> 16) & 0xffff)
}

/** VOICE start: the note's id, its codec (1 — as recorded), samples a second, bytes in all (0 — not known yet). */
export function voiceStart(id, codec, rate, bytes) {
  const b = new Uint8Array(9)
  b[0] = 0x50
  b[1] = id & 0xff
  b[2] = codec
  put16(b, 3, rate)
  put32(b, 5, bytes || 0)
  return b.buffer
}

/** VOICE data: the note's id, the offset of these bytes, the bytes (a Uint8Array). */
export function voiceData(id, offset, bytes) {
  const b = new Uint8Array(6 + bytes.length)
  b[0] = 0x51
  b[1] = id & 0xff
  put32(b, 2, offset)
  b.set(bytes, 6)
  return b.buffer
}

/** VOICE end: the note's id, bytes in all, how long it was (ms). */
export function voiceEnd(id, bytes, ms) {
  const b = new Uint8Array(10)
  b[0] = 0x52
  b[1] = id & 0xff
  put32(b, 2, bytes)
  put32(b, 6, Math.round(ms))
  return b.buffer
}

/** RAW samples in one packet at most (10 bytes each after a 7-byte head). */
export const RAW_MAX = 23

/**
 * RAW (for the phone's log, while it asks — CFG_DEBUG): the sensor's samples as they came, with the watch's own times:
 * [{ t (ms, Date.now()), x, y, z (its units), h (the compass heading, radians; NaN — none) }…]. The times are the
 * callbacks' (a batch of samples shares one), so the phone can play the stream through the finder exactly as the watch.
 */
export function rawPacket(seq, samples) {
  const n = Math.min(samples.length, RAW_MAX)
  const b = new Uint8Array(7 + 10 * n)
  const t0 = n ? samples[0].t : 0
  b[0] = 0x60
  b[1] = seq & 0xff
  put32(b, 2, t0 >>> 0)
  b[6] = n
  for (let i = 0; i < n; i++) {
    const s = samples[i]
    const o = 7 + 10 * i
    put16(b, o, Math.max(0, Math.min(65535, Math.round(s.t - t0))))
    put16(b, o + 2, clamp16(s.x * 100))
    put16(b, o + 4, clamp16(s.y * 100))
    put16(b, o + 6, clamp16(s.z * 100))
    const h = Number(s.h)
    put16(b, o + 8, Number.isNaN(h) ? -32768 : Math.round(((((h * 180) / Math.PI) % 360) + 360) % 360 * 10))
  }
  return b.buffer
}

/** UTF-8 bytes of a string. */
export function utf8Bytes(s) {
  const out = []
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i)
    if (c >= 0xd800 && c < 0xdc00 && i + 1 < s.length) c = 0x10000 + ((c - 0xd800) << 10) + (s.charCodeAt(++i) - 0xdc00)
    if (c < 0x80) out.push(c)
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63))
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
  }
  return out
}

/** DIAG (for the phone's log): a line about what the watch did, UTF-8, cut to max bytes in all (whole letters). */
export function diagPacket(text, max) {
  let bytes = utf8Bytes(String(text))
  let n = Math.min(bytes.length, Math.max(0, (max || 20) - 1))
  while (n > 0 && n < bytes.length && (bytes[n] & 0xc0) === 0x80) n--
  const b = new Uint8Array(1 + n)
  b[0] = 0x70
  for (let i = 0; i < n; i++) b[1 + i] = bytes[i]
  return b.buffer
}

/**
 * A long line in as many DIAG packets as it takes (each max bytes in all, cut on whole letters; at most parts of them):
 * on a small MTU a line would otherwise be its first twenty bytes.
 */
export function diagPackets(text, max, parts) {
  const bytes = utf8Bytes(String(text))
  const room = Math.max(4, (max || 20) - 1)
  const out = []
  let o = 0
  while (o < bytes.length && out.length < (parts || 20)) {
    let n = Math.min(room, bytes.length - o)
    while (n > 0 && o + n < bytes.length && (bytes[o + n] & 0xc0) === 0x80) n--
    const b = new Uint8Array(1 + n)
    b[0] = 0x70
    for (let i = 0; i < n; i++) b[1 + i] = bytes[o + i]
    out.push(b.buffer)
    o += n
  }
  return out
}

/** UTF-8 bytes (a Uint8Array, from..to) as a string (the watch's JavaScript may have no TextDecoder). */
export function utf8(b, from, to) {
  let s = ''
  let i = from || 0
  const end = to === undefined ? b.length : to
  while (i < end) {
    const c = b[i++]
    let cp
    if (c < 0x80) cp = c
    else if (c >= 0xc0 && c < 0xe0 && i < end) cp = ((c & 0x1f) << 6) | (b[i++] & 0x3f)
    else if (c >= 0xe0 && c < 0xf0 && i + 1 < end) {
      cp = ((c & 0x0f) << 12) | ((b[i] & 0x3f) << 6) | (b[i + 1] & 0x3f)
      i += 2
    } else if (c >= 0xf0 && i + 2 < end) {
      cp = ((c & 0x07) << 18) | ((b[i] & 0x3f) << 12) | ((b[i + 1] & 0x3f) << 6) | (b[i + 2] & 0x3f)
      i += 3
    } else continue
    if (cp > 0xffff) {
      cp -= 0x10000
      s += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff))
    } else s += String.fromCharCode(cp)
  }
  return s
}

/**
 * What the phone said (the bytes of a notification on EVENTS): { ev } for the one-byte answers; EV_CONFIG with
 * { leftHand, voice, mtu }; EV_VOICE_FROM with { id, offset }; EV_VOICE_DONE with { id, result, line }.
 */
export function parseEvent(v) {
  const ev = v[0]
  const e = { ev }
  if (ev === EV_CONFIG && v.length >= 2) {
    e.leftHand = (v[1] & CFG_LEFT_HAND) !== 0
    e.voice = (v[1] & CFG_VOICE) !== 0
    e.debug = (v[1] & CFG_DEBUG) !== 0
    e.mtu = v.length >= 4 ? v[2] | (v[3] << 8) : 0
  } else if (ev === EV_VOICE_FROM && v.length >= 6) {
    e.id = v[1]
    e.offset = (v[2] | (v[3] << 8) | (v[4] << 16) | (v[5] << 24)) >>> 0
  } else if (ev === EV_VOICE_DONE && v.length >= 3) {
    e.id = v[1]
    e.result = v[2]
    e.line = utf8(v, 3)
  }
  return e
}

const ADPCM_STEP = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
  107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060,
  1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
  8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767]
const ADPCM_INDEX = [-1, -1, -1, -1, 2, 4, 6, 8]

/**
 * IMA ADPCM (4 bits a sample, a quarter of 16-bit PCM — 8 KB a second at 16 kHz): the recorder's PCM made small enough
 * for Bluetooth, in a form the phone surely reads. One stream for the whole note (the state goes on from frame to
 * frame), two samples a byte, the first in the low half. encode(bytes of 16-bit PCM, little-endian) gives the bytes
 * so far (an odd byte, an odd sample wait for the next frame); flush() the last; peak — the loudest sample (0…1).
 */
export function createAdpcm() {
  let pred = 0
  let index = 0
  let odd = -1 // a byte of a sample cut by the frame
  let half = -1 // a code waiting for its pair
  let peak = 0
  function code(s) {
    let diff = s - pred
    let step = ADPCM_STEP[index]
    let c = 0
    if (diff < 0) {
      c = 8
      diff = -diff
    }
    let vp = step >> 3
    if (diff >= step) {
      c |= 4
      diff -= step
      vp += step
    }
    step >>= 1
    if (diff >= step) {
      c |= 2
      diff -= step
      vp += step
    }
    step >>= 1
    if (diff >= step) {
      c |= 1
      vp += step
    }
    pred = c & 8 ? pred - vp : pred + vp
    if (pred > 32767) pred = 32767
    else if (pred < -32768) pred = -32768
    index += ADPCM_INDEX[c & 7]
    if (index < 0) index = 0
    else if (index > 88) index = 88
    return c
  }
  return {
    get peak() {
      return peak / 32768
    },
    encode(bytes) {
      const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
      const out = new Uint8Array(((b.length + 2) >> 2) + 2)
      let n = 0
      let i = 0
      const one = (lo, hi) => {
        const s = ((hi << 8) | lo) << 16 >> 16
        const a = s < 0 ? -s : s
        if (a > peak) peak = a
        const c = code(s)
        if (half < 0) half = c
        else {
          out[n++] = half | (c << 4)
          half = -1
        }
      }
      if (odd >= 0 && b.length) {
        one(odd, b[0])
        odd = -1
        i = 1
      }
      for (; i + 1 < b.length; i += 2) one(b[i], b[i + 1])
      if (i < b.length) odd = b[i]
      return out.subarray(0, n)
    },
    flush() {
      if (half < 0) return new Uint8Array(0)
      const last = new Uint8Array([half])
      half = -1
      return last
    }
  }
}

/**
 * A voice note on its way to the phone: the recorder's frames kept in order (add) until the end (end); next(room)
 * gives the next write — START once the codec is known, the bytes from where the phone has them (DATA, at most room
 * bytes each), END once all are out — or null while there is nothing to send. from(offset): the phone asks again from
 * there; again(): the link was new, all of it again. The codec is told by the first frame: the recorder's own Ogg file
 * goes as it is, Opus packets without one each after its length (u16), so the phone can still take them apart.
 */
export function createVoice(id, format, rate) {
  // kept in pieces of 16 KB: a long note never needs one big block, nor a copy of all of it to grow (the watch's memory)
  const CHUNK = 16384
  const chunks = []
  function write(bytes, at) {
    let o = 0
    while (o < bytes.length) {
      const c = Math.floor(at / CHUNK)
      while (chunks.length <= c) chunks.push(new Uint8Array(CHUNK))
      const off = at % CHUNK
      const n = Math.min(bytes.length - o, CHUNK - off)
      chunks[c].set(bytes.subarray(o, o + n), off)
      o += n
      at += n
    }
  }
  function read(from, n) {
    const out = new Uint8Array(n)
    let o = 0
    while (o < n) {
      const at = from + o
      const off = at % CHUNK
      const k = Math.min(n - o, CHUNK - off)
      out.set(chunks[Math.floor(at / CHUNK)].subarray(off, off + k), o)
      o += k
    }
    return out
  }
  // PCM from the recorder goes as IMA ADPCM (a quarter of the bytes, a format the phone surely reads)
  const enc = format === 'adpcm' ? createAdpcm() : null
  const v = {
    /** The loudest sample so far (0…1; for ADPCM — what the recorder gave), for the watch's log. */
    get peak() {
      return enc ? enc.peak : 0
    },
    id: id & 0xff,
    rate,
    codec: 0,
    len: 0,
    ended: false,
    ms: 0,
    sent: 0,
    startSent: false,
    endSent: false,
    add(frame) {
      let f = frame instanceof Uint8Array ? frame : new Uint8Array(frame)
      if (enc) {
        v.codec = CODEC_ADPCM
        f = enc.encode(f)
      }
      v.put(f)
    },
    /** Bytes as they go (already encoded). */
    put(f) {
      if (!f.length) return
      if (!v.codec) {
        const ogg = f.length >= 4 && f[0] === 0x4f && f[1] === 0x67 && f[2] === 0x67 && f[3] === 0x53
        const riff = f.length >= 4 && f[0] === 0x52 && f[1] === 0x49 && f[2] === 0x46 && f[3] === 0x46
        v.codec = ogg || riff ? CODEC_RECORDED : format === 'pcm' ? CODEC_PCM16 : format === 'opus' ? CODEC_OPUS_PACKETS : CODEC_RECORDED
      }
      const extra = v.codec === CODEC_OPUS_PACKETS ? 2 : 0
      if (extra) {
        write(new Uint8Array([f.length & 0xff, (f.length >> 8) & 0xff]), v.len)
        v.len += 2
      }
      write(f, v.len)
      v.len += f.length
    },
    end(ms) {
      if (enc) {
        v.codec = CODEC_ADPCM
        v.put(enc.flush())
      }
      if (!v.codec) v.codec = format === 'pcm' ? CODEC_PCM16 : CODEC_RECORDED
      v.ended = true
      v.ms = ms
    },
    next(room) {
      if (!v.codec) return null
      if (!v.startSent) {
        v.startSent = true
        return voiceStart(v.id, v.codec, v.rate, 0)
      }
      if (v.sent < v.len) {
        const n = Math.max(1, Math.min(room, v.len - v.sent))
        const p = voiceData(v.id, v.sent, read(v.sent, n))
        v.sent += n
        return p
      }
      if (v.ended && !v.endSent) {
        v.endSent = true
        return voiceEnd(v.id, v.len, v.ms)
      }
      return null
    },
    from(offset) {
      v.sent = Math.max(0, Math.min(offset, v.len))
      v.endSent = false
    },
    again() {
      v.startSent = false
      v.sent = 0
      v.endSent = false
    },
    /** The bytes kept (for the tests). */
    bytes() {
      return read(0, v.len)
    }
  }
  return v
}

/**
 * The watch held to the mouth as one talks into it: the forearm raised (the fingers up) from below a moment before, the
 * dial upright (facing the face, not the sky as when one looks at it), held so for a moment — a hand resting up high
 * (on a steering wheel) is not raised to the mouth. sample(x, y, z, t) gives 'start' once it has been
 * held long enough, 'stop' once the watch is taken away (or after a minute), 'cancel' when taken away too soon to be a
 * note, otherwise null; busy() — between «held» and «taken away» (no turns of the wrist then). Which way along the
 * watch's x the fingers are: +1 on the left hand (the crown toward the hand), −1 on the right — setHand(); and then
 * learnt from where the arm hangs most of the time (the fingers down): fingers(), learnt(). Any units.
 *
 * Or taught: teach(t), then within two seconds the watch held to the mouth as one talks into it, kept still — 'taught'
 * (or after 9 s 'teach-failed' — moved but not held still — or 'teach-unmoved' — left where it was). With taught poses (up to 3, kept in poses), «at the mouth» is the watch within 28° of
 * one of them (taken away — farther than 45°), whatever the hand, and it starts sooner (0.45 s); each note said and
 * saved moves its pose a little toward how the watch was held (nudge).
 */
export function createTalk() {
  const RAISED = 0.55 // «up» along the fingers at least this (cos): the forearm raised 35° above level or more
  const VERTICAL = 0.97 // …but not straight up (a hanging arm, if the side were wrong)
  const UPRIGHT = 0.6 // |«up» across the dial| at most this: the dial facing sideways, to the face
  const STEADY = 0.25 // the pull away from gravity's size at most this (of g): held there, not swung
  const HOLD_MS = 600
  const RAISE_MS = 2000 // and the hand was down (or flat) this shortly before: raised to the mouth, not resting so
  const AWAY_FINGERS = 0.35
  const AWAY_UPRIGHT = 0.8
  const AWAY_MS = 450
  const MIN_MS = 900
  const MAX_MS = 30000
  const REST_MS = 1500
  const SMOOTH_MS = 150
  const LEARN_S = 20
  const LEARN_SHARE = 0.75
  const KEEP_S = 600
  const CONE_START = 28 // a taught pose: the watch within this of it (degrees)…
  const CONE_STOP = 45 // …taken away: farther than this
  const CONE_LOW = 55 // «down»: farther than this from every taught pose
  const HOLD_TAUGHT_MS = 450
  const TEACH_WAIT_MS = 2000 // teaching: two seconds to bring the watch to the mouth after the tap…
  const TEACH_MOVE = 12 // …away from where it was at the tap (not the screen being looked at)…
  const TEACH_STILL_MS = 600 // …and held still so long
  const TEACH_MS = 9000
  const TEACH_SAME = 15 // a pose this near one already taught replaces it (their mean)
  const MAX_POSES = 3
  const NUDGE = 0.2
  let side = 1
  let learnt = false
  let down = [0, 0] // seconds the arm hung with the fingers along +x / along −x
  let g = 0
  let sx = 0
  let sy = 0
  let sz = 0
  let lastT = NaN
  let state = 0 // 0 waiting, 1 held (counting), 2 talking, 3 after a note (until the hand leaves)
  let since = 0
  let startAt = 0
  let awaySince = NaN
  let restAt = 0
  let lowAt = -Infinity
  let poses = []
  let teaching = null
  let talkSum = [0, 0, 0]
  let talkN = 0
  const unit = (v) => {
    const m = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) || 1
    return [v[0] / m, v[1] / m, v[2] / m]
  }
  const angle = (a, b) => (Math.acos(Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]))) * 180) / Math.PI
  /** How far (degrees) the watch is from the nearest taught pose, and which. */
  function nearest(u) {
    let best = 180
    let at = -1
    for (let i = 0; i < poses.length; i++) {
      const d = angle(u, poses[i])
      if (d < best) {
        best = d
        at = i
      }
    }
    return { d: best, i: at }
  }
  function addPose(p) {
    const n = nearest(p)
    if (n.i >= 0 && n.d <= TEACH_SAME) poses[n.i] = unit([poses[n.i][0] + p[0], poses[n.i][1] + p[1], poses[n.i][2] + p[2]])
    else {
      poses.push(p)
      if (poses.length > MAX_POSES) poses.shift()
    }
  }
  function learn(ux, dt) {
    // the arm hanging (x within 25° of plumb), not while held to the mouth
    if (Math.abs(ux) < 0.9 || dt <= 0 || state === 1 || state === 2) return
    // «up» against x: x points down, so the fingers are along +x
    down[ux < 0 ? 0 : 1] += dt / 1000
    const all = down[0] + down[1]
    if (all > KEEP_S) {
      down = [(down[0] * KEEP_S) / all, (down[1] * KEEP_S) / all]
    }
    if (all >= LEARN_S) {
      const share = Math.max(down[0], down[1]) / all
      if (share >= LEARN_SHARE) {
        side = down[0] >= down[1] ? 1 : -1
        learnt = true
      }
    }
  }
  return {
    setHand(leftHand) {
      if (!learnt) side = leftHand ? 1 : -1
    },
    fingers() {
      return side
    },
    learnt() {
      return learnt
    },
    /** What was learnt, to keep: [seconds along +x, along −x]; and given back. */
    get down() {
      return down.slice()
    },
    set down(d) {
      if (d && d.length === 2 && d[0] >= 0 && d[1] >= 0) {
        down = [d[0], d[1]]
        const all = down[0] + down[1]
        if (all >= LEARN_S && Math.max(down[0], down[1]) / all >= LEARN_SHARE) {
          side = down[0] >= down[1] ? 1 : -1
          learnt = true
        }
      }
    },
    busy() {
      return state === 1 || state === 2
    },
    /** Teach how the watch is held to the mouth: the next still pose away from where it is now (t — now). */
    teach(t) {
      teaching = { at: t, u0: null, since: 0, sum: [0, 0, 0], moved: false }
      if (state === 1 || state === 2) {
        state = 3
        restAt = t
      }
    },
    teaching() {
      return teaching !== null
    },
    /** The taught poses («up» in the watch's axes, unit vectors), to keep; and given back. */
    get poses() {
      return poses.map((p) => p.slice())
    },
    set poses(list) {
      poses = []
      if (!Array.isArray(list)) return
      for (const p of list) {
        if (Array.isArray(p) && p.length === 3 && p.every((v) => typeof v === 'number' && isFinite(v))) poses.push(unit(p))
      }
      poses = poses.slice(-MAX_POSES)
    },
    forget() {
      poses = []
    },
    /** How the watch was held through the last note said (unit «up»; null — none). */
    lastPose() {
      return talkN > 0 ? unit(talkSum) : null
    },
    /** A note said there was saved: the nearest taught pose moved a little toward it. */
    nudge(p) {
      if (!p || !poses.length) return false
      const n = nearest(p)
      if (n.d > CONE_STOP) return false
      const q = poses[n.i]
      poses[n.i] = unit([q[0] * (1 - NUDGE) + p[0] * NUDGE, q[1] * (1 - NUDGE) + p[1] * NUDGE, q[2] * (1 - NUDGE) + p[2] * NUDGE])
      return true
    },
    talking() {
      return state === 2
    },
    sample(x, y, z, t) {
      const n = Math.sqrt(x * x + y * y + z * z)
      if (!(n > 0)) return null
      const dt = Number.isNaN(lastT) ? -1 : t - lastT
      lastT = t
      if (dt < 0 || dt > 1000) {
        sx = x
        sy = y
        sz = z
        g = g || n
      } else {
        const k = Math.min(1, dt / SMOOTH_MS)
        sx += (x - sx) * k
        sy += (y - sy) * k
        sz += (z - sz) * k
        g += (n - g) * Math.min(1, dt / 3000)
      }
      const m = Math.sqrt(sx * sx + sy * sy + sz * sz) || 1
      const ux = sx / m
      const uz = sz / m
      const u = [ux, sy / m, uz]
      const steady = Math.abs(n - g) <= STEADY * g
      learn(ux, dt)
      if (teaching) {
        // teaching: two seconds to bring the watch to the mouth, then still there — that is the pose (whichever hand,
        // whichever way the watch's axes run)
        if (!teaching.u0) teaching.u0 = u
        if (t - teaching.at > TEACH_MS) {
          const moved = teaching.moved
          teaching = null
          return moved ? 'teach-failed' : 'teach-unmoved'
        }
        if (angle(u, teaching.u0) >= TEACH_MOVE) teaching.moved = true
        // not the arm let down: the forearm plumb, fingers down (if known where down is) or either way (if not) — a
        // forearm straight up is no usual way to talk into a watch either
        const hanging = learnt ? side * ux < -0.8 : Math.abs(ux) > 0.95
        if (t - teaching.at >= TEACH_WAIT_MS && angle(u, teaching.u0) >= TEACH_MOVE && steady && !hanging) {
          if (!teaching.since) {
            teaching.since = t
            teaching.sum = [0, 0, 0]
          }
          teaching.sum = [teaching.sum[0] + u[0], teaching.sum[1] + u[1], teaching.sum[2] + u[2]]
          if (t - teaching.since >= TEACH_STILL_MS) {
            addPose(unit(teaching.sum))
            teaching = null
            state = 3
            restAt = t
            return 'taught'
          }
        } else teaching.since = 0
        return null
      }
      const f = side * ux
      const near = poses.length ? nearest(u).d : 180
      if (poses.length ? near > CONE_LOW : f < AWAY_FINGERS) lowAt = t
      const raised = poses.length ? near <= CONE_START : f >= RAISED && f <= VERTICAL && Math.abs(uz) <= UPRIGHT
      const hold = poses.length ? HOLD_TAUGHT_MS : HOLD_MS
      if (state === 2) {
        talkSum = [talkSum[0] + u[0], talkSum[1] + u[1], talkSum[2] + u[2]]
        talkN++
      }
      if (state === 0) {
        if (raised && steady && t - lowAt <= RAISE_MS) {
          state = 1
          since = t
        }
      } else if (state === 1) {
        if (!raised) state = 0
        else if (!steady) since = t
        else if (t - since >= hold) {
          state = 2
          startAt = t
          awaySince = NaN
          talkSum = [0, 0, 0]
          talkN = 0
          return 'start'
        }
      } else if (state === 2) {
        const away = poses.length ? near > CONE_STOP : f < AWAY_FINGERS || Math.abs(uz) > AWAY_UPRIGHT
        if (!away) awaySince = NaN
        else if (Number.isNaN(awaySince)) awaySince = t
        if ((!Number.isNaN(awaySince) && t - awaySince >= AWAY_MS) || t - startAt >= MAX_MS) {
          state = 3
          restAt = t
          return Number.isNaN(awaySince) || awaySince - startAt >= MIN_MS ? 'stop' : 'cancel'
        }
      } else if (state === 3) {
        if (!raised && t - restAt >= REST_MS) state = 0
      }
      return null
    },
    /** The note is over from outside (the page stopped it): wait for the hand to leave before the next. */
    done(t) {
      if (state === 1 || state === 2) {
        state = 3
        restAt = t
      }
    }
  }
}

/**
 * Finds turns in the accelerometer's samples (and the compass's heading) by their real times — the phone's
 * WristGesture.Finder (Java) line for line, see there: one number — how far the watch has twisted around its x axis,
 * the forearm — swinging away from where it has been lately and back; two such numbers side by side (gravity's roll,
 * and the twist from the orientation that gravity and the compass give), whichever sees the turn tells it. Nothing has
 * to be still before. sample(x, y, z, t, heading) gives { twist, took, source, slope, slopeChange } when a turn has
 * just ended, otherwise null (heading in radians, from north toward east; NaN or omitted — none). Any units.
 */
export function createFinder(trace) {
  const START_DEG = 14
  const CALM_DEG = 10
  const BACK_DEG = 15
  const BACK_FRAC = 0.4
  const MIN_DEG = 22
  const MIN_MS = 90
  const MAX_MS = 800
  const GRACE_MS = 140
  const BASE_MS = 600
  const CALM_MS = 300
  const SETTLE_MS = 250
  const QUIET_MS = 400
  const CUT_HZ = 5
  const LEVER_GRAVITY = 0.3
  const LEVER_KEEP = 0.25
  const FLAT_Y = 0.92
  const KEEP_Y = 0.95
  const KNOCK_HI = 3.0
  const KNOCK_LO = 0.25
  const DEG = 180 / Math.PI

  function clamp(c) {
    return Math.max(-1, Math.min(1, c))
  }

  function wrapDeg(d) {
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return d
  }

  // one number watched for a swing away and back
  function track(name) {
    return { name, good: false, baseSet: false, goodT: 0, raw: 0, theta: 0, base: 0, calm: 0, state: 0, b0: 0, peak: 0, startT: 0, endT: 0, slope0: 0, slopeMax: 0, told: false }
  }

  // what the finder saw but did not count, for a log (trace is given only when someone looks)
  function why(k, what, t, more) {
    if (trace) trace(Object.assign({ what, ch: k.name, t, peak: k.peak, took: t - k.startT }, more || {}))
  }

  function lose(k) {
    k.good = false
    k.baseSet = false
    k.state = 0
  }

  function settle(k) {
    k.state = 0
    k.base = k.theta
    k.calm = 1.5 * CALM_DEG
  }

  function doneAlready(k) {
    return k.state === 2 && Math.abs(k.peak) >= MIN_DEG && k.endT - k.startT >= MIN_MS
  }

  // the number now (an angle, or an increment when inc), at t; true when a turn has just ended
  function step(k, v, inc, t, dt, ux) {
    if (!k.good) {
      k.good = true
      k.goodT = t
      k.baseSet = false
    }
    if (!k.baseSet) {
      k.raw = v
      k.theta = inc ? 0 : v
      k.base = k.theta
      k.calm = 0
      k.baseSet = true
    } else if (inc) {
      k.theta += v
    } else {
      k.theta += wrapDeg(v - k.raw)
      k.raw = v
    }
    if (k.state === 0) {
      const d = k.theta - k.base
      if (trace) {
        // away as far as a turn, but not allowed to start one: why (once for each time away)
        if (Math.abs(d) > START_DEG && !(k.calm <= CALM_DEG && t - k.goodT >= SETTLE_MS && t >= quietUntil)) {
          if (!k.told) {
            k.told = true
            why(k, k.calm > CALM_DEG ? 'restless' : t < quietUntil ? 'quiet-after-turn' : 'settling', t,
              { d, calm: k.calm, peak: d, took: 0 })
          }
        } else if (Math.abs(d) < START_DEG) k.told = false
      }
      if (Math.abs(d) > START_DEG && k.calm <= CALM_DEG && t - k.goodT >= SETTLE_MS && t >= quietUntil) {
        k.state = 1
        k.b0 = k.base
        k.peak = d
        k.startT = t
        k.slope0 = ux
        k.slopeMax = 0
        return false
      }
      // where it has been lately, and how restless
      k.calm += (1 - Math.exp(-dt / CALM_MS)) * (Math.abs(d) - k.calm)
      k.base += (1 - Math.exp(-dt / BASE_MS)) * d
      return false
    }
    const d = k.theta - k.b0
    k.slopeMax = Math.max(k.slopeMax, Math.abs((Math.acos(clamp(ux)) - Math.acos(clamp(k.slope0))) * DEG))
    if (Math.abs(d) > Math.abs(k.peak)) {
      // further than before: a bigger swing (after the run-up, or still on the way)
      k.peak = d
      k.state = 1
    }
    if (k.state === 1) {
      if (t - k.startT > MAX_MS) {
        // turned and stayed: a new position, not a gesture
        why(k, 'not-back-in-time', t, { d })
        settle(k)
        return false
      }
      if (Math.abs(d) <= Math.max(BACK_DEG, BACK_FRAC * Math.abs(k.peak))) {
        if (Math.abs(k.peak) < MIN_DEG) {
          why(k, 'small', t)
          k.state = 0 // a wobble
          return false
        }
        k.state = 2
        k.endT = t
      }
      return false
    }
    // state 2: back; a further swing would have made state 1 above
    if (t - k.endT < GRACE_MS) return false
    settle(k)
    if (k.endT - k.startT < MIN_MS) why(k, 'too-quick', t, { took: k.endT - k.startT })
    return k.endT - k.startT >= MIN_MS
  }

  const f = [0, 0, 0]
  let primed = false
  let lastT = 0
  let g = 0
  let gSeen = false
  const grav = track('gravity')
  const comp = track('compass')
  let quietUntil = -Infinity
  let tilt = 0
  // the orientation: rows east, north, up — the world's axes in the watch's own
  const m = new Float64Array(9)
  const mPrev = new Float64Array(9)
  let mSet = false

  function reset() {
    primed = false
    lose(grav)
    lose(comp)
    mSet = false
  }

  // the watch's orientation from «up» and the heading of its y axis, and how far it twisted around x since the last
  function orient(ux, uy, uz, h) {
    let yx = -uy * ux
    let yy = 1 - uy * uy
    let yz = -uy * uz
    const yn = Math.sqrt(yx * yx + yy * yy + yz * yz)
    yx /= yn
    yy /= yn
    yz /= yn
    const cx = yy * uz - yz * uy
    const cy = yz * ux - yx * uz
    const cz = yx * uy - yy * ux
    const ch = Math.cos(h)
    const sh = Math.sin(h)
    mPrev.set(m)
    m[0] = ch * cx + sh * yx
    m[1] = ch * cy + sh * yy
    m[2] = ch * cz + sh * yz
    m[3] = ch * yx - sh * cx
    m[4] = ch * yy - sh * cy
    m[5] = ch * yz - sh * cz
    m[6] = ux
    m[7] = uy
    m[8] = uz
    if (!mSet) {
      mSet = true
      return 0
    }
    let q21 = 0
    let q12 = 0
    let tr = 0
    for (let i = 0; i < 3; i++) {
      q21 += mPrev[3 * i + 2] * m[3 * i + 1]
      q12 += mPrev[3 * i + 1] * m[3 * i + 2]
      tr += mPrev[3 * i] * m[3 * i] + mPrev[3 * i + 1] * m[3 * i + 1] + mPrev[3 * i + 2] * m[3 * i + 2]
    }
    const ang = Math.acos(clamp((tr - 1) / 2))
    const kk = ang < 1e-6 ? 0.5 : ang / (2 * Math.sin(ang))
    return kk * (q21 - q12) * DEG
  }

  function sample(x, y, z, t, heading) {
    const h = heading === undefined || heading === null ? NaN : Number(heading)
    let dt = primed ? t - lastT : 0
    if (primed && (dt < 0 || dt > 1000)) {
      // the clock went back or the samples stopped: start afresh (samples at the same time are still samples)
      reset()
      dt = 0
    }
    lastT = t
    if (!primed) {
      f[0] = x
      f[1] = y
      f[2] = z
      primed = true
    } else {
      const a = 1 - Math.exp((-2 * Math.PI * CUT_HZ * dt) / 1000)
      f[0] += a * (x - f[0])
      f[1] += a * (y - f[1])
      f[2] += a * (z - f[2])
    }
    const mm = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
    if (!(mm > 0)) return null
    if (!gSeen) {
      g = mm
      gSeen = true
    } else if (Math.abs(mm - g) < 0.2 * g) {
      g += (1 - Math.exp(-dt / 2000)) * (mm - g)
    }
    if (mm > KNOCK_HI * g || mm < KNOCK_LO * g) {
      // a knock or a throw: not a turn
      if (trace && (grav.state || comp.state)) why(grav.state ? grav : comp, 'knock', t)
      grav.state = 0
      comp.state = 0
      return null
    }
    const ux = f[0] / mm
    const uy = f[1] / mm
    const uz = f[2] / mm
    const lever = Math.sqrt(uy * uy + uz * uz)
    let found = false
    let by = 0
    // gravity's number
    if (lever >= (grav.good ? LEVER_KEEP : LEVER_GRAVITY)) {
      if (step(grav, Math.atan2(uy, uz) * DEG, false, t, dt, ux)) {
        found = true
        by = SOURCE_GRAVITY
      }
    } else {
      if (doneAlready(grav)) {
        found = true
        by = SOURCE_GRAVITY
      } else if (grav.state) why(grav, 'forearm-upright', t)
      lose(grav)
    }
    // the compass's number
    if (!Number.isNaN(h) && Math.abs(uy) <= (comp.good ? KEEP_Y : FLAT_Y)) {
      const inc = orient(ux, uy, uz, h)
      if (step(comp, inc, true, t, dt, ux) && !found) {
        found = true
        by = SOURCE_COMPASS
      }
    } else {
      mSet = false
      if (doneAlready(comp) && !found) {
        found = true
        by = SOURCE_COMPASS
      } else if (comp.state && !doneAlready(comp)) why(comp, Number.isNaN(h) ? 'compass-silent' : 'dial-sideways', t)
      lose(comp)
    }
    let tr = by === SOURCE_COMPASS ? comp : grav
    const other = by === SOURCE_COMPASS ? grav : comp
    tilt = Math.max(grav.good && grav.baseSet ? Math.abs(grav.theta - grav.base) : 0,
      comp.good && comp.baseSet ? Math.abs(comp.theta - comp.base) : 0)
    if (!found) return null
    if (other.good && other.state !== 0 && Math.abs(other.peak) > Math.abs(tr.peak) && other.peak * tr.peak > 0) {
      // the other number is in the middle of the same turn and saw more of it: its measure
      tr = other
      by = by === SOURCE_COMPASS ? SOURCE_GRAVITY : SOURCE_COMPASS
      tr.endT = t
    }
    const out = { twist: tr.peak, took: tr.endT - tr.startT, source: by, slope: tr.slope0, slopeChange: tr.slopeMax }
    // one turn told once: both numbers start afresh and wait a moment
    quietUntil = t + QUIET_MS
    settle(grav)
    settle(comp)
    return out
  }

  return {
    sample,
    reset,
    /** How far the watch is twisted from where it has been lately (degrees), for the screen. */
    get tilt() {
      return tilt
    },
    get gravity() {
      return gSeen ? g : 0
    }
  }
}

/**
 * Does an advertisement (raw bytes: an ArrayBuffer, a typed array or an array of numbers) carry this 128-bit service?
 * Without the bytes: no (some watches pass every device around whatever the scan's filter says).
 */
export function advertises(data, uuid) {
  if (!data) return false
  let a
  if (data instanceof ArrayBuffer) a = new Uint8Array(data)
  else if (data.buffer instanceof ArrayBuffer) a = new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength)
  else if (data.length !== undefined) {
    a = new Uint8Array(data.length)
    for (let i = 0; i < data.length; i++) a[i] = data[i] & 0xff
  } else return false
  if (a.length === 0) return false
  const hex = uuid.replace(/-/g, '').toLowerCase()
  const want = []
  for (let i = 15; i >= 0; i--) want.push(parseInt(hex.substr(2 * i, 2), 16)) // little-endian in the air
  for (let o = 0; o + 1 < a.length;) {
    const len = a[o]
    if (len === 0) break
    const type = a[o + 1]
    if (type === 0x06 || type === 0x07) {
      for (let p = o + 2; p + 16 <= o + 1 + len; p += 16) {
        let same = true
        for (let k = 0; k < 16 && same; k++) same = a[p + k] === want[k]
        if (same) return true
      }
    }
    o += len + 1
  }
  return false
}

/**
 * Takes the accelerometer as the watch gives it and makes packets: the first second measures how often samples come
 * (the platforms only promise «about 20 ms») and their units (m/s² are sent in hundredths, cm/s² and milli-g as they
 * are), then HELLO goes out with the measured rate. With opts.turns the watch finds the turns itself (createFinder,
 * every sample at its own time, the compass heading with it if the watch has one) and sends one ROLL packet per turn
 * (opts.onTurn hears it too); otherwise pairs are averaged down to about 25 a second and sent, 3 samples a packet.
 */
export function createPacker(opts) {
  const target = opts.target || 25
  const perPacket = opts.perPacket || 3
  const finder = opts.turns ? createFinder(opts.trace) : null
  // the samples' times for the finder: a watch may hand several over at once, all with the same Date.now() (the
  // Xiaomi Watch S5 does), so they are spread back over the pace measured — never later than now, never far behind it
  let tFirst = -1
  let tN = 0
  let step = 0
  let fedT = -Infinity
  function retime(now) {
    if (tFirst < 0) {
      tFirst = now
      tN = 0
    }
    tN++
    if (tN >= 5 && now > tFirst) step = (now - tFirst) / (tN - 1)
    if (!step) {
      fedT = Math.max(now, fedT + 1)
      return fedT
    }
    let t = fedT + step
    if (t > now) t = now
    const lag = Math.max(300, 4 * step)
    if (t < now - lag) t = now - lag
    if (t <= fedT) t = fedT + step / 4
    fedT = t
    return t
  }
  let t0 = -1
  let count = 0
  let factor = 1
  let scale = 0
  let ready = false
  let seq = 0
  let sx = 0
  let sy = 0
  let sz = 0
  let sn = 0
  let buf = []
  let mute = false
  return {
    get ready() {
      return ready
    },
    /** The turn finder (with opts.turns), for the screen: finder.tilt. */
    finder,
    /** Turns found are not sent (the music paused, a voice note being said); the samples still go to the finder. */
    get mute() {
      return mute
    },
    set mute(on) {
      mute = !!on
    },
    /** One sample; now in ms; the compass heading then (radians, north 0, east π/2; omitted — none). */
    sample(x, y, z, now, heading) {
      if (!scale) {
        const m = Math.sqrt(x * x + y * y + z * z)
        if (m === 0) return
        scale = m < 50 ? 100 : 1
      }
      if (!ready) {
        if (t0 < 0) t0 = now
        count++
        if (now - t0 >= 1000 && count >= 5) {
          const rate = ((count - 1) * 1000) / (now - t0)
          factor = Math.max(1, Math.round(rate / target))
          const m = Math.sqrt(x * x + y * y + z * z) * scale
          if (finder) opts.send(hello(opts.id, Math.max(1, Math.round(rate)), clamp16(m), opts.platform, VERSION_TURNS))
          else opts.send(hello(opts.id, Math.max(1, Math.round(rate / factor)), clamp16(m), opts.platform))
          ready = true
        }
        if (!finder) return
      }
      if (finder) {
        const turn = finder.sample(x, y, z, retime(now), heading)
        if (turn) {
          // found; sent unless muted (the music paused, a voice note) — told either way, with whether it went
          if (ready && !mute) opts.send(rollPacket(seq++, turn.twist, turn.took, turn.source, turn.slope, turn.slopeChange))
          if (opts.onTurn && !mute) opts.onTurn(turn)
          if (opts.onFound) opts.onFound(turn, ready && !mute)
        }
        return
      }
      sx += x * scale
      sy += y * scale
      sz += z * scale
      sn++
      if (sn < factor) return
      buf.push(sx / sn, sy / sn, sz / sn)
      sx = sy = sz = 0
      sn = 0
      if (buf.length >= 3 * perPacket) {
        opts.send(accel(seq++, buf, perPacket))
        buf = []
      }
    },
    /** After a pause: what was gathered before is dropped (the rate and units stay). */
    restart() {
      if (finder) finder.reset()
      tFirst = -1 // after a pause: measured again (the pace known so far is kept meanwhile)
      sx = sy = sz = 0
      sn = 0
      buf = []
    }
  }
}
