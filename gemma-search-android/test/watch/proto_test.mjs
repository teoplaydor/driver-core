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
  hex(P.rollPacket(9, 61.64, 344, P.SOURCE_GRAVITY, 0.37, 12.4)),
  hex(P.rollPacket(10, -48.25, 410, P.SOURCE_COMPASS, -0.93, 3)),
  hex(P.voiceStart(7, P.CODEC_RECORDED, 16000, 0)),
  hex(P.voiceData(7, 70000, new Uint8Array([1, 2, 3, 250, 255]))),
  hex(P.voiceEnd(7, 70005, 4321)),
  hex(P.rawPacket(5, [{ t: 100000, x: 0.5, y: -9.81, z: 0.07, h: 2 }, { t: 100150, x: 1, y: 2, z: 3, h: NaN }])),
  hex(P.diagPacket('поворот 61° — отправлен', 244)),
  hex(P.voiceStart(7, P.CODEC_ADPCM | P.CODEC_LIVE, 16000, 0)),
  hex(P.voicePlayed(7, 3, true)),
  hex(P.voicePlayed(7, 4, false)),
  hex(P.voicePlayed(7, 5, P.NOT_WHOLE)),
  hex(P.voicePlayed(7, 6, P.NOT_WRITTEN)),
  hex(P.hello(0x0badcafe, 25, 1000, 'vela', P.VERSION_MP3)),
  hex(P.voicePlayed(7, 8, P.SILENT)),
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
    if (r) found.push([name, r.twist.toFixed(3), r.took.toFixed(1), r.source, r.slope.toFixed(5), r.slopeChange.toFixed(3)].join(' '))
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
let bad = okAdv ? 0 : 1
const ok = (c, what) => {
  if (!c) bad++
  console.log((c ? 'ok   ' : 'FAIL ') + what)
}

// ---------------------------------------------------------------- voice notes

// the phone's answers as the phone's Java builds them (VoiceNoteTest wrote them)
const evFile = path.join(out, 'events.hex')
if (fs.existsSync(evFile)) {
  const ev = fs.readFileSync(evFile, 'utf8').trim().split('\n').map((l) => P.parseEvent(new Uint8Array(Buffer.from(l, 'hex'))))
  ok(ev[0].ev === P.EV_CONFIG && ev[0].leftHand && ev[0].voice && ev[0].mtu === 247
    && ev[1].ev === P.EV_CONFIG && !ev[1].leftHand && !ev[1].voice && ev[1].mtu === 23,
  'the phone\'s settings read: left hand, voice notes, MTU')
  ok(ev[2].ev === P.EV_VOICE_FROM && ev[2].id === 7 && ev[2].offset === 70000, 'the phone\'s «again from 70000» read')
  ok(ev[3].ev === P.EV_VOICE_DONE && ev[3].id === 7 && ev[3].result === P.VOICE_SAVED
    && ev[3].line === 'Сохранено: купить хлеб · напомнит завтра в 9:00' && ev[4].line === 'Сохранен',
  'the phone\'s «done» read with its line (whole, and cut on a letter): «' + ev[3].line + '»')
}

// a recording on its way: the recorder's Ogg frames go as they are; one write lost, asked for again
const frames = []
for (let i = 0; i < 7; i++) {
  const f = new Uint8Array(300 + i * 37)
  for (let k = 0; k < f.length; k++) f[k] = (i * 31 + k * 7) & 0xff
  frames.push(f)
}
frames[0].set([0x4f, 0x67, 0x67, 0x53], 0) // «OggS»
const v = P.createVoice(300, 'opus', 16000)
ok(v.next(180) === null, 'nothing to send before the first frame (the codec is not known yet)')
const wire = []
const lost = []
let k = 0
for (const f of frames.slice(0, 4)) v.add(f)
for (let p; (p = v.next(180));) {
  const b = new Uint8Array(p)
  if (b[0] === 0x51 && k++ === 3) lost.push(hex(p)) // the fourth write lost on the way
  else wire.push(hex(p))
}
ok(wire[0] === hex(P.voiceStart(300, P.CODEC_RECORDED, 16000, 0)) && v.codec === P.CODEC_RECORDED,
  'Ogg from the recorder: START (as recorded), id 300 → ' + (300 & 0xff))
for (const f of frames.slice(4)) v.add(f)
v.end(4321)
// the phone asks again from the lost bytes' offset
const at = new Uint8Array(Buffer.from(lost[0], 'hex'))
const lostAt = (at[2] | (at[3] << 8) | (at[4] << 16) | (at[5] << 24)) >>> 0
for (let p; (p = v.next(180));) wire.push(hex(p))
const endBefore = wire[wire.length - 1]
v.from(lostAt)
for (let p; (p = v.next(180));) wire.push(hex(p))
const all = Buffer.concat(frames.map((f) => Buffer.from(f)))
ok(Buffer.from(v.bytes()).equals(all) && endBefore.startsWith('52') && wire[wire.length - 1].startsWith('52')
  && wire.every((w) => w.length / 2 <= 186), 'the bytes kept in order; END, and after «again from» the rest and END again; '
  + 'writes ≤ 186 bytes (' + wire.length + ' writes)')
fs.writeFileSync(path.join(out, 'voice_js.hex'), wire.join('\n') + '\n')
fs.writeFileSync(path.join(out, 'voice_js_bytes.hex'), all.toString('hex') + '\n')
v.again()
ok(hex(v.next(20)) === hex(P.voiceStart(300, P.CODEC_RECORDED, 16000, 0)), 'a new link: all of it again from START')
// Opus without a file around it: each packet after its length
const raw = P.createVoice(5, 'opus', 16000)
raw.add(new Uint8Array([0x48, 1, 2]))
raw.add(new Uint8Array([0x48, 3]))
ok(raw.codec === P.CODEC_OPUS_PACKETS && hex(raw.bytes()) === '0300480102' + '02004803', 'Opus packets without Ogg: each after its length')
const pcm = P.createVoice(6, 'pcm', 8000)
pcm.add(new Uint8Array([1, 2, 3, 4]))
ok(pcm.codec === P.CODEC_PCM16 && hex(pcm.next(10)) === hex(P.voiceStart(6, P.CODEC_PCM16, 8000, 0)), 'PCM: as PCM, 8 kHz')

// the watch held to the mouth: «up» in the watch's axes for a pose (x along the forearm to the hand), blended
const norm = (u) => {
  const m = Math.hypot(u[0], u[1], u[2])
  return [u[0] / m, u[1] / m, u[2] / m]
}
const POSE = {
  hanging: [-1, 0.05, 0.1], // the arm along the body, fingers down (left hand: the crown toward the hand)
  looking: [0.3, 0.05, 0.95], // looking at the dial
  mouth: [0.85, 0.35, 0.12], // held to the mouth, the dial to the face
  flat: [0.05, 0.1, 1]
}
/** Poses in turn, [pose, seconds, move-into seconds], 20 a second in m/s², a little shake; what the detector said. */
function talkRun(talk, plan, t0) {
  const said = []
  let t = t0 || 0
  let prev = POSE[plan[0][0]]
  for (const [pose, secs, move] of plan) {
    const to = POSE[pose]
    const n = Math.round(secs * 20)
    const m = Math.round((move || 0) * 20)
    for (let i = 0; i < n; i++) {
      const f = m > 0 ? Math.min(1, i / m) : 1
      const u = norm([0, 1, 2].map((j) => prev[j] + (to[j] - prev[j]) * f))
      // moving: a pull while the arm swings up or down
      const pull = m > 0 && i < m ? 1 + 0.35 * Math.sin(Math.PI * i / m) : 1
      const x = 9.81 * pull * u[0] + 0.15 * gauss()
      const y = 9.81 * pull * u[1] + 0.15 * gauss()
      const z = 9.81 * pull * u[2] + 0.15 * gauss()
      const w = talk.sample(x, y, z, t)
      if (w) said.push([w, t])
      t += 50
    }
    prev = to
  }
  return { said, t }
}
let tk = P.createTalk()
tk.setHand(true)
let r = talkRun(tk, [['hanging', 25], ['mouth', 4, 0.5], ['hanging', 3, 0.5]])
ok(r.said.length === 2 && r.said[0][0] === 'start' && r.said[1][0] === 'stop' && tk.learnt() && tk.fingers() === 1,
  'raised to the mouth and held: «start»; lowered: «stop» (' + r.said.map((s) => s[0] + ' ' + (s[1] / 1000).toFixed(1) + ' s').join(', ')
  + '); the side learnt from the hanging arm')
const startAt = r.said[0][1] - 25000
ok(startAt >= 1000 && startAt <= 1600, 'the recording starts ' + (startAt / 1000).toFixed(2) + ' s after the hand began to rise')
r = talkRun(tk, [['hanging', 2], ['looking', 5, 0.5], ['flat', 5, 0.5], ['hanging', 2, 0.5]], r.t)
ok(r.said.length === 0, 'looking at the dial, the hand on the table: nothing')
r = talkRun(tk, [['hanging', 2], ['mouth', 0.4, 0.3], ['hanging', 2, 0.3]], r.t)
ok(r.said.length === 0, 'the hand passing by the mouth: nothing')
r = talkRun(tk, [['hanging', 2], ['mouth', 1, 0.1], ['hanging', 2, 0.1]], r.t)
ok(r.said.length === 2 && r.said[0][0] === 'start' && r.said[1][0] === 'cancel', 'held just long enough, then away at once: «cancel» — no note')
r = talkRun(tk, [['mouth', 3, 0.5], ['mouth', 20]], r.t + 5000)
ok(r.said.length === 0, 'a hand resting high for long (a steering wheel) — not raised from below: nothing')
// the right hand set, but the watch is on the left (or the crown toward the elbow): the hanging arm must not record,
// and the side is learnt from it
tk = P.createTalk()
tk.setHand(false)
r = talkRun(tk, [['hanging', 30]])
ok(r.said.length === 0 && tk.learnt() && tk.fingers() === 1, 'the side set wrong: the hanging arm records nothing, and the side is learnt')
r = talkRun(tk, [['mouth', 3, 0.5], ['hanging', 2, 0.5]], r.t)
ok(r.said.length === 2 && r.said[0][0] === 'start' && r.said[1][0] === 'stop', '…then the mouth works')
const kept = tk.down
const tk2 = P.createTalk()
tk2.setHand(false)
tk2.down = kept
ok(tk2.learnt() && tk2.fingers() === 1, 'what was learnt kept and given back: ' + kept.map((s) => Math.round(s)).join(' / ') + ' s')

// taught: a way of holding the watch to the mouth the usual rule does not take (the forearm level across the chest,
// the dial to the face) — taught once, then it starts (sooner) and stops there; the usual pose no longer does
POSE.across = [0.1, 0.95, 0.3]
tk = P.createTalk()
tk.setHand(true)
r = talkRun(tk, [['hanging', 3], ['across', 3, 0.5], ['hanging', 3, 0.5]])
ok(r.said.length === 0, 'not taught: the forearm level across the chest is not «at the mouth»')
// the button tapped while looking at the watch
r = talkRun(tk, [['looking', 1, 0.5]], r.t)
tk.teach(r.t)
r = talkRun(tk, [['looking', 0.5], ['across', 2.5, 0.5], ['hanging', 2, 0.5]], r.t)
ok(r.said.length === 1 && r.said[0][0] === 'taught' && tk.poses.length === 1 && !tk.teaching(),
  'taught: looked at the watch, held it to the mouth a second — «taught» (' + tk.poses[0].map((v) => v.toFixed(2)).join(', ') + ')')
r = talkRun(tk, [['hanging', 2], ['across', 3, 0.5], ['hanging', 2, 0.5]], r.t)
const taughtStart = r.said.length ? r.said[0][1] - (r.t - 5000) : NaN
ok(r.said.length === 2 && r.said[0][0] === 'start' && r.said[1][0] === 'stop' && taughtStart < 1050,
  'the taught pose: «start» (' + (taughtStart / 1000).toFixed(2) + ' s after the hand began to rise), «stop»')
r = talkRun(tk, [['hanging', 2], ['mouth', 3, 0.5], ['hanging', 2, 0.5], ['looking', 3, 0.5], ['hanging', 2, 0.5]], r.t)
ok(r.said.length === 0, 'taught: the other ways of holding it (and looking at it) — nothing')
// what was taught kept and given back; a saved note moves its pose a little toward how it was held
const keptPoses = tk.poses
const tk3 = P.createTalk()
tk3.poses = JSON.parse(JSON.stringify(keptPoses))
const near = [0.2, 0.92, 0.33]
const before = keptPoses[0]
tk3.nudge(near)
const after = tk3.poses[0]
const ang = (a, b) => Math.acos(Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])) * 180 / Math.PI
const nb = (() => { const m = Math.hypot(...near); return near.map((v) => v / m) })()
ok(tk3.poses.length === 1 && ang(after, nb) < ang(before, nb) && !tk3.nudge([-1, 0, 0]),
  'kept and given back; a saved note moves the pose toward it, a far one does not')
// teaching that never gets a still pose away from the eyes: given up after 15 s
const tk4 = P.createTalk()
tk4.teach(0)
r = talkRun(tk4, [['looking', 10]])
ok(r.said.length === 1 && r.said[0][0] === 'teach-unmoved' && tk4.poses.length === 0, 'teaching, the watch left where it was: given up after 9 s («unmoved»)')
const tk5 = P.createTalk()
tk5.setHand(true)
talkRun(tk5, [['looking', 1]])
tk5.teach(1000)
r = talkRun(tk5, [['looking', 0.5], ['hanging', 4, 0.5]], 1000)
ok(r.said.length === 0 && tk5.poses.length === 0, 'teaching: the arm let down after the tap is not taken for the pose')
tk.forget()
ok(tk.poses.length === 0, 'forgotten')

// taught on the right hand with the side unknown (the fingers along −x): the pose is taken all the same
POSE.mouthRight = [-0.85, -0.35, 0.12]
const tk6 = P.createTalk()
tk6.setHand(true) // the setting wrong: left
r = talkRun(tk6, [['looking', 1]])
tk6.teach(r.t)
r = talkRun(tk6, [['looking', 0.5], ['mouthRight', 3, 0.5]], r.t)
ok(r.said.length === 1 && r.said[0][0] === 'taught', 'taught on the other hand than set — taken (whichever way the axes run)')

// the recorder's PCM as IMA ADPCM: a quarter of the bytes, the same however the frames are cut (odd sizes too)
const pcmN = 16000
const pcmIn = new Uint8Array(2 * pcmN)
for (let i = 0; i < pcmN; i++) {
  const t = i / 16000
  const v = Math.round(9000 * Math.sin(2 * Math.PI * (200 + 600 * t) * t) * (0.5 + 0.5 * Math.sin(2 * Math.PI * 3 * t)) + 300 * gauss())
  pcmIn[2 * i] = v & 0xff
  pcmIn[2 * i + 1] = (v >> 8) & 0xff
}
const whole = P.createAdpcm()
const encAll = Buffer.concat([Buffer.from(whole.encode(pcmIn)), Buffer.from(whole.flush())])
const cut = P.createAdpcm()
const parts = []
for (let o = 0; o < pcmIn.length;) {
  const n = 1 + Math.floor(rnd() * 999)
  parts.push(Buffer.from(cut.encode(pcmIn.subarray(o, Math.min(pcmIn.length, o + n)))))
  o += n
}
parts.push(Buffer.from(cut.flush()))
const encCut = Buffer.concat(parts)
ok(encAll.length === pcmN / 2 && encCut.equals(encAll) && whole.peak > 0.25,
  'ADPCM: ' + pcmIn.length + ' bytes → ' + encAll.length + ', the same cut in frames of odd sizes; peak ' + whole.peak.toFixed(2))
fs.writeFileSync(path.join(out, 'adpcm_pcm.hex'), Buffer.from(pcmIn).toString('hex') + '\n')
fs.writeFileSync(path.join(out, 'adpcm.hex'), encAll.toString('hex') + '\n')
const va = P.createVoice(3, 'adpcm', 16000)
va.add(pcmIn.subarray(0, 1001))
va.add(pcmIn.subarray(1001))
va.end(1000)
ok(va.codec === P.CODEC_ADPCM && Buffer.from(va.bytes()).equals(encAll) && hex(va.next(200)) === hex(P.voiceStart(3, P.CODEC_ADPCM, 16000, 0)),
  'a voice note from the recorder\'s PCM: START (ADPCM, 16 kHz), the same bytes')

// a conversation's events from the phone, as the watch reads them (the Java side builds the same bytes in GeminiLiveTest)
{
  const u8 = (a) => new Uint8Array(a)
  const cfg = P.parseEvent(u8([6, 2 | 8 | 16, 247, 0]))
  ok(cfg.talk && cfg.talkWatch && cfg.voice && cfg.mtu === 247 && !cfg.talkHeadset, 'config: at the mouth a conversation, the answers on the watch')
  const hcfg = P.parseEvent(u8([6, 2 | 8 | 32, 247, 0]))
  ok(hcfg.talk && hcfg.talkHeadset && !hcfg.talkWatch, 'config: the conversation through the watch as a headset')
  const au = P.parseEvent(u8([10, 3, 0xe8, 3, 0, 0, 9, 8, 7]))
  ok(au.ev === P.EV_TALK_AUDIO && au.seg === 3 && au.offset === 1000 && Array.from(au.data).join() === '9,8,7', 'EV_TALK_AUDIO: piece 3 at 1000, its bytes')
  const sg = P.parseEvent(u8([11, 3, 0x40, 0x20, 0, 0, 21, 0]))
  ok(sg.ev === P.EV_TALK_SEG && sg.seg === 3 && sg.total === 0x2040 && sg.ms === 2100 && sg.format === P.TALK_WAV, 'EV_TALK_SEG: piece 3 whole, 8256 bytes, 2.1 s, a WAV file')
  const sm = P.parseEvent(u8([11, 4, 0x40, 0x20, 0, 0, 21, 1]))
  ok(sm.format === P.TALK_MP3 && P.parseEvent(u8([11, 4, 0x40, 0x20, 0, 0, 21])).format === P.TALK_WAV, 'EV_TALK_SEG: an MP3 piece (format 1); no format byte — WAV')
  const tx = P.parseEvent(u8([12, ...Buffer.from('Готово')]))
  ok(tx.ev === P.EV_TALK_TEXT && tx.line === 'Готово', 'EV_TALK_TEXT: the line')
  ok(P.parseEvent(u8([13])).ev === P.EV_TALK_END, 'EV_TALK_END')
}
console.log(bad === 0 ? 'WATCH PROTOCOL JS OK' : bad + ' FAILED')
if (bad) process.exit(1)
