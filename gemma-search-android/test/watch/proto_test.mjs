// The watch apps' protocol (watch/vela/src/common/proto.js, shared by the Vela and Zepp OS apps) run as the watches
// would run it: a simulated wrist — still, a turn right and back, still, a turn left and back — sampled the way each
// platform gives it (Vela: about every 20 ms, in m/s², with jitter; Zepp OS: about 25 a second, in cm/s²); the packets
// it makes are printed as hex, one per line, for the phone's Java (WristJsTest) to read and recognise.
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

// the encoders against fixed values (the Java side builds the same and compares)
fs.writeFileSync(path.join(out, 'fixed.hex'), [
  hex(P.hello(0x0badcafe, 25, 981, 'vela')),
  hex(P.accel(7, [1000, -1000, 32767, -32768, 0, 981, 40000, -40000, 1], 3)),
].join('\n') + '\n')

// the advertisement check: Android's packet with our 128-bit service; another; nothing to check
const uuid = P.SERVICE.replace(/-/g, '')
const le = Buffer.from(uuid, 'hex').reverse()
const ours = Buffer.concat([Buffer.from([2, 1, 6, 17, 7]), le])
const theirs = Buffer.concat([Buffer.from([2, 1, 6, 17, 7]), Buffer.alloc(16, 0x42)])
const okAdv = P.advertises(ours, P.SERVICE) && !P.advertises(theirs, P.SERVICE) && P.advertises(null, P.SERVICE)
console.log((okAdv ? 'ok   ' : 'FAIL ') + 'advertisement check')
if (!okAdv) process.exit(1)
