// «Пульт кистью»: the watch's side of the protocol of the phone's WristGesture (SemSearch → Настройки → Часы).
// The same file serves the Xiaomi Vela and the Zepp OS apps (and is checked against the phone's Java in the tests).
// The watch finds the turn itself (createFinder — the phone's WristGesture.Finder, line for line) and sends one SWING
// packet per turn (where «up» was at rest and at the far end, how far the compass went); the phone decides what it
// means. Or it streams the raw samples (ACCEL, 3 a packet) and the phone finds the turn. Little-endian, 20 bytes at
// most.

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
/** HELLO versions: samples follow / the watch finds the turns and TURN packets follow. */
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
 * SWING: how far (degrees), how long (ms), «up» at rest and at the far end (the watch's axes, unit length), how far the
 * compass heading went (degrees; NaN — no compass). The phone works out the twist around the forearm from them.
 */
export function swingPacket(seq, peak, took, rest, far, dh) {
  const b = new Uint8Array(18)
  b[0] = 0x30
  b[1] = seq & 0xff
  b[2] = Math.max(0, Math.min(255, Math.round(peak)))
  b[3] = Math.max(0, Math.min(255, Math.round(took / 10)))
  for (let i = 0; i < 3; i++) {
    put16(b, 4 + 2 * i, clamp16(rest[i] * 10000))
    put16(b, 10 + 2 * i, clamp16(far[i] * 10000))
  }
  put16(b, 16, Number.isNaN(dh) ? -32768 : Math.max(-32767, clamp16(dh * 10)))
  return b.buffer
}

/** TURN: how far (degrees), how long (ms), the axis it went around (the watch's axes, unit length). */
export function turnPacket(seq, peak, took, axis) {
  const b = new Uint8Array(10)
  b[0] = 0x20
  b[1] = seq & 0xff
  b[2] = Math.max(0, Math.min(255, Math.round(peak)))
  b[3] = Math.max(0, Math.min(255, Math.round(took / 10)))
  for (let i = 0; i < 3; i++) put16(b, 4 + 2 * i, clamp16(axis[i] * 10000))
  return b.buffer
}

/**
 * Finds turns in the accelerometer's samples by their real times — the phone's WristGesture.Finder (Java) line for
 * line, see there: the hand still for a moment (where it rests), then away by more than START_DEG and back near it
 * within MAX_MS; the swing that went furthest decides (a quick turn swings a little the other way at its start), and
 * the turn ends when no further swing follows within GRACE_MS. With the forearm upright gravity barely moves in a
 * twist, so the compass heading (passed with each sample, radians, NaN — none) counts as a swing too.
 * sample(x, y, z, t, heading) gives { peak, took, axis, rest, far, dh } when a turn has just ended, otherwise null:
 * «up» at rest and at the far end (the watch's axes), how far the heading went (degrees, NaN — it did not count).
 * Any units.
 */
export function createFinder() {
  const STILL_MS = 320
  const STILL_DEG = 10
  const STILL_MAG = 0.18
  const START_DEG = 14
  const FRESH_MS = 600
  const BACK_DEG = 15
  const BACK_FRAC = 0.5
  const MIN_DEG = 22
  const MIN_MS = 90
  const MAX_MS = 900
  const GRACE_MS = 140
  const CUT_HZ = 5
  const KNOCK_HI = 3.0
  const KNOCK_LO = 0.25
  const UPRIGHT = 0.6
  const FLAT_Y = 0.75
  const N = 64
  const ts = new Float64Array(N)
  const ux = new Float64Array(N)
  const uy = new Float64Array(N)
  const uz = new Float64Array(N)
  const mg = new Float64Array(N)
  const hd = new Float64Array(N)
  const f = [0, 0, 0]
  const rest = [0, 0, 0]
  const peakU = [0, 0, 0]
  let count = 0
  let at = 0
  let primed = false
  let lastT = 0
  let g = 0
  let gSeen = false
  let haveRest = false
  let lastStill = -Infinity
  let state = 0 // 0 waiting, 1 away from the rest, 2 back — waiting whether a bigger swing follows
  let startT = 0
  let endT = 0
  let peak = 0
  let tilt = 0
  let curH = NaN
  let restH = NaN
  let gravPeak = 0
  let headPeak = 0
  let headAt = 0
  let headUsed = false
  const cosStill = Math.cos((STILL_DEG * Math.PI) / 180)

  function wrapDeg(d) {
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return d
  }

  function reset() {
    count = 0
    primed = false
    haveRest = false
    lastStill = -Infinity
    state = 0
    restH = NaN
  }

  // still over the last STILL_MS (3 samples at least); with the forearm upright the compass has to be steady too
  function still(t) {
    let n = 0
    let sx = 0
    let sy = 0
    let sz = 0
    let sm = 0
    for (let i = 0; i < count; i++) {
      const k = (at - 1 - i + N) % N
      if (n >= 3 && t - ts[k] > STILL_MS) break
      sx += ux[k]
      sy += uy[k]
      sz += uz[k]
      sm += mg[k]
      n++
    }
    if (n < 3 || t - ts[(at - n + N) % N] < STILL_MS * 0.66) return false
    const len = Math.sqrt(sx * sx + sy * sy + sz * sz)
    if (len <= 0) return false
    sx /= len
    sy /= len
    sz /= len
    const mm = sm / n
    for (let i = 0; i < n; i++) {
      const k = (at - 1 - i + N) % N
      if (ux[k] * sx + uy[k] * sy + uz[k] * sz < cosStill) return false
      if (Math.abs(mg[k] - mm) > STILL_MAG * mm) return false
    }
    let h = NaN
    if (!Number.isNaN(curH) && Math.abs(sx) >= UPRIGHT && Math.abs(sy) <= FLAT_Y) {
      // the compass counts: steady too, its mean is where the watch faces
      let hc = 0
      let hs = 0
      for (let i = 0; i < n; i++) {
        const k = (at - 1 - i + N) % N
        if (Number.isNaN(hd[k])) continue
        hc += Math.cos(hd[k])
        hs += Math.sin(hd[k])
      }
      h = Math.atan2(hs, hc)
      for (let i = 0; i < n; i++) {
        const k = (at - 1 - i + N) % N
        if (!Number.isNaN(hd[k]) && Math.abs(wrapDeg(((hd[k] - h) * 180) / Math.PI)) > STILL_DEG) return false
      }
    }
    restH = h
    rest[0] = sx
    rest[1] = sy
    rest[2] = sz
    if (!gSeen) {
      g = mm
      gSeen = true
    } else {
      g += 0.1 * (mm - g)
    }
    return true
  }

  function sample(x, y, z, t, heading) {
    curH = heading === undefined || heading === null ? NaN : Number(heading)
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
    const m = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
    if (!(m > 0)) return null
    const k = at
    at = (at + 1) % N
    if (count < N) count++
    ts[k] = t
    ux[k] = f[0] / m
    uy[k] = f[1] / m
    uz[k] = f[2] / m
    mg[k] = m
    hd[k] = curH
    if (gSeen && (m > KNOCK_HI * g || m < KNOCK_LO * g)) {
      // a knock or a throw
      state = 0
      lastStill = -Infinity
      return null
    }
    if (state === 0 && still(t)) {
      // where the hand rests now (and where the watch faces)
      lastStill = t
      haveRest = true
    }
    if (!haveRest) return null
    const c = rest[0] * ux[k] + rest[1] * uy[k] + rest[2] * uz[k]
    const grav = (Math.acos(Math.max(-1, Math.min(1, c))) * 180) / Math.PI
    const head = Number.isNaN(restH) || Number.isNaN(curH) ? NaN : wrapDeg(((curH - restH) * 180) / Math.PI)
    const deg = Number.isNaN(head) ? grav : Math.max(grav, Math.abs(head))
    tilt = deg
    if (state === 0) {
      if (deg > START_DEG && t - lastStill <= FRESH_MS) {
        state = 1
        startT = lastStill
        peak = deg
        gravPeak = grav
        peakU[0] = ux[k]
        peakU[1] = uy[k]
        peakU[2] = uz[k]
        headUsed = !Number.isNaN(head)
        headPeak = headUsed ? Math.abs(head) : 0
        headAt = headUsed ? head : 0
      }
      return null
    }
    if (grav > gravPeak) {
      // gravity's far end
      gravPeak = grav
      peakU[0] = ux[k]
      peakU[1] = uy[k]
      peakU[2] = uz[k]
    }
    if (!Number.isNaN(head) && Math.abs(head) > headPeak) {
      // the compass's far end
      headPeak = Math.abs(head)
      headAt = head
      headUsed = true
    }
    if (deg > peak) {
      // further than before: a bigger swing (after the run-up, or still on the way)
      peak = deg
      state = 1
    }
    if (state === 1) {
      if (t - startT > MAX_MS) {
        // turned and stayed: a new position, not a gesture
        state = 0
        haveRest = false
        lastStill = -Infinity
        return null
      }
      if (deg <= Math.max(BACK_DEG, BACK_FRAC * peak)) {
        if (peak < MIN_DEG) {
          // a wobble: still where it rests
          state = 0
          return null
        }
        state = 2
        endT = t
      }
      return null
    }
    // state 2: back; a further swing would have made state 1 above
    if (t - endT < GRACE_MS) return null
    state = 0
    lastStill = -Infinity // still again before the next one
    const took = endT - startT
    if (took < MIN_MS) return null
    const cx = rest[1] * peakU[2] - rest[2] * peakU[1]
    const cy = rest[2] * peakU[0] - rest[0] * peakU[2]
    const cz = rest[0] * peakU[1] - rest[1] * peakU[0]
    const n = Math.sqrt(cx * cx + cy * cy + cz * cz)
    // the watch turned around this axis (gravity, seen from the watch, turned the other way)
    const axis = n < 1e-6 ? [0, 0, 0] : [-cx / n, -cy / n, -cz / n]
    return { peak, took, axis, rest: rest.slice(), far: peakU.slice(), dh: headUsed ? headAt : NaN }
  }

  return {
    sample,
    reset,
    /** How far from where the hand last rested (degrees, gravity or compass), for the screen. */
    get tilt() {
      return haveRest ? tilt : 0
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
 * every sample at its own time, the compass heading with it if the watch has one) and sends one SWING packet per turn
 * (opts.onTurn hears it too); otherwise pairs are averaged down to about 25 a second and sent, 3 samples a packet.
 */
export function createPacker(opts) {
  const target = opts.target || 25
  const perPacket = opts.perPacket || 3
  const finder = opts.turns ? createFinder() : null
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
  return {
    get ready() {
      return ready
    },
    /** The turn finder (with opts.turns), for the screen: finder.tilt. */
    finder,
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
          if (ready) opts.send(swingPacket(seq++, turn.peak, turn.took, turn.rest, turn.far, turn.dh))
          if (opts.onTurn) opts.onTurn(turn)
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
