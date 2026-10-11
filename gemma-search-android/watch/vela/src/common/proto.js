// «Пульт кистью»: the watch's side of the protocol of the phone's WristGesture (SemSearch → Настройки → Часы).
// The same file serves the Xiaomi Vela and the Zepp OS apps (and is checked against the phone's Java in the tests).
// HELLO and ACCEL packets, little-endian, 20 bytes at most: 3 samples a packet.

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

function put16(b, o, v) {
  v = v & 0xffff
  b[o] = v & 0xff
  b[o + 1] = (v >> 8) & 0xff
}

export function clamp16(v) {
  v = Math.round(v)
  return v > 32767 ? 32767 : v < -32768 ? -32768 : v
}

/** HELLO: who the watch is (a random id it keeps), samples a second, counts per g, the platform's name. */
export function hello(id, rate, perG, platform) {
  const name = String(platform || '').slice(0, 10)
  const b = new Uint8Array(10 + name.length)
  b[0] = 0x00
  b[1] = 1
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

/** Does an advertisement (raw bytes) carry this 128-bit service? Unknown (no bytes): yes — the scan filtered already. */
export function advertises(data, uuid) {
  if (!data) return true
  const a = new Uint8Array(data)
  if (a.length === 0) return true
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
 * are), then HELLO goes out with the measured rate; pairs are averaged down to about 25 a second; 3 samples a packet.
 */
export function createPacker(opts) {
  const target = opts.target || 25
  const perPacket = opts.perPacket || 3
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
    /** One sample; now in ms. */
    sample(x, y, z, now) {
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
          opts.send(hello(opts.id, Math.max(1, Math.round(rate / factor)), clamp16(m), opts.platform))
          ready = true
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
      sx = sy = sz = 0
      sn = 0
      buf = []
    }
  }
}
