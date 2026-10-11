// The watch apps' protocol (watch/vela/src/common/proto.js, shared by the Vela and Zepp OS apps) run as the watches
// would run it: a simulated wrist — still, a turn right and back, still, a turn left and back — sampled the way each
// platform gives it (Vela: about every 20 ms, in m/s², with jitter; Zepp OS: about 25 a second, in cm/s²); the packets
// it makes — raw samples, and turns found on the watch — are printed as hex, one per line, for the phone's Java
// (WristJsTest) to read and recognise. And the watches' turn finder over the streams the Java test wrote
// (OUT_DIR/finder_in.txt, from WristGestureTest): the turns it finds go to finder_js.txt for WristJsTest to compare
// with the phone's own finder.
// usage: node test/watch/proto_test.mjs PROTO.js OUT_DIR
import fs from 'fs'
import path from 'path'
import { pathToFileURL } from 'url'

const [src, out] = process.argv.slice(2)
fs.mkdirSync(out, { recursive: true })
const tmp = path.join(out, 'proto.mjs')
fs.copyFileSync(src, tmp)
const P = await import(pathToFileURL(path.resolve(tmp)).href)

const hex = (buf) => Buffer.from(new Uint8Array(buf)).toString('hex')
let seed = 5
const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff)
const gauss = () => Math.sqrt(-2 * Math.log(rnd() + 1e-9)) * Math.cos(2 * Math.PI * rnd())

/** Still / turned around the forearm by deg and back in s seconds; samples every dt ms (± jitter), in the unit given. */
function wrist(segs, dt, jitter, unit) {
  const out = []
  let t = 1000
  for (const [deg, s] of segs) {
    const end = t + s * 1000
    const start = t
    while (t < end) {
      const u = (t - start) / (s * 1000)
      const th = (deg * Math.PI / 180) * Math.sin(Math.PI * u) ** 2
      const g = 9.80665
      out.push([0.01 * gauss() * g * unit, g * Math.sin(th) * unit + 0.01 * gauss() * g * unit, g * Math.cos(th) * unit, Math.round(t)])
      t += dt + (rnd() - 0.5) * jitter
    }
  }
  return out
}

const segs = [[0, 2], [70, 0.6], [0, 1], [-70, 0.6], [0, 1]]
for (const [name, dt, jitter, unit] of [['vela', 20, 6, 1], ['zepp', 40, 4, 100]]) {
  const lines = []
  const pk = P.createPacker({ id: 0x0badcafe, platform: name, send: (b) => lines.push(hex(b)) })
  for (const [x, y, z, t] of wrist(segs, dt, jitter, unit)) pk.sample(x, y, z, t)
  fs.writeFileSync(path.join(out, name + '.hex'), lines.join('\n') + '\n')
  console.log(name + ': ' + lines.length + ' packets, hello ' + lines[0])
}

// the same wrist, the turns found on the watch: HELLO v2, then one TURN packet per turn. «vela_s5» is the Xiaomi Watch
// S5 as it is: whole m/s², and the samples handed over a few at a time, all with the same Date.now()
for (const [name, dt, jitter, unit, burst, whole] of [['vela', 60, 12, 1, 1, false], ['zepp', 40, 4, 100, 1, false],
  ['vela_s5', 50, 0, 1, 3, true]]) {
  const lines = []
  const pk = P.createPacker({ id: 0x0badcafe, platform: name.slice(0, 4), turns: true, send: (b) => lines.push(hex(b)) })
  const ss = wrist(segs, dt, jitter, unit)
  for (let i = 0; i < ss.length; i++) {
    const [x, y, z] = ss[i]
    const t = ss[Math.min(ss.length - 1, Math.floor(i / burst) * burst + burst - 1)][3] // the time the batch came
    if (whole) pk.sample(Math.round(x), Math.round(y), Math.round(z), t)
    else pk.sample(x, y, z, t)
  }
  fs.writeFileSync(path.join(out, name + '_turns.hex'), lines.join('\n') + '\n')
  console.log(name + ' (turns found on the watch): ' + lines.length + ' packets — ' + lines.join(' '))
}

// the encoders against fixed values (the Java side builds the same and compares)
fs.writeFileSync(path.join(out, 'fixed.hex'), [
  hex(P.hello(0x0badcafe, 25, 981, 'vela')),
  hex(P.accel(7, [1000, -1000, 32767, -32768, 0, 981, 40000, -40000, 1], 3)),
  hex(P.hello(0x0badcafe, 16, 981, 'vela', P.VERSION_TURNS)),
  hex(P.turnPacket(9, 61.6, 344, [0.6, -0.8, 0])),
  hex(P.swingPacket(10, 58.4, 356, [0, 0.6, 0.8], [-0.1, -0.2, 0.97], 61.25)),
  hex(P.swingPacket(11, 33, 300, [-1, 0, 0], [-0.95, 0.31, 0], NaN)),
].join('\n') + '\n')

// the turn finder over the Java test's streams: what it finds, for WristJsTest to compare with the Java finder
const inFile = path.join(out, 'finder_in.txt')
if (fs.existsSync(inFile)) {
  const found = []
  let f = null
  let name = ''
  for (const l of fs.readFileSync(inFile, 'ascii').split('\n')) {
    if (l.startsWith('# ')) {
      name = l.slice(2).trim()
      f = P.createFinder()
      continue
    }
    if (!l.trim() || !f) continue
    const [t, x, y, z, h] = l.trim().split(' ').map(Number)
    const r = f.sample(x, y, z, t, h)
    if (r) {
      found.push([name, r.peak.toFixed(3), r.took.toFixed(1), Number.isNaN(r.dh) ? 'NaN' : r.dh.toFixed(3),
        r.rest.concat(r.far).map((v) => v.toFixed(5)).join(' ')].join(' '))
    }
  }
  fs.writeFileSync(path.join(out, 'finder_js.txt'), found.join('\n') + '\n')
  console.log('finder: ' + found.length + ' turns in the Java test\'s streams')
}

// the advertisement check: Android's packet with our 128-bit service; another; nothing to check
const uuid = P.SERVICE.replace(/-/g, '')
const le = Buffer.from(uuid, 'hex').reverse()
const ours = Buffer.concat([Buffer.from([2, 1, 6, 17, 7]), le])
const theirs = Buffer.concat([Buffer.from([2, 1, 6, 17, 7]), Buffer.alloc(16, 0x42)])
// the bytes as an ArrayBuffer, a typed array or a plain array; without bytes — not ours (some watches pass every device)
const okAdv = P.advertises(ours, P.SERVICE) && P.advertises(new Uint8Array(ours).buffer, P.SERVICE) && P.advertises(Array.from(ours), P.SERVICE)
  && !P.advertises(theirs, P.SERVICE) && !P.advertises(null, P.SERVICE) && !P.advertises(new ArrayBuffer(0), P.SERVICE)
console.log((okAdv ? 'ok   ' : 'FAIL ') + 'advertisement check')
if (!okAdv) process.exit(1)
