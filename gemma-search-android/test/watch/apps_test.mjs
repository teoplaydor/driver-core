// The Xiaomi Vela and Zepp OS watch apps' own page code, run in Node over stand-ins of the watches' system modules
// (Bluetooth LE client, accelerometer, vibration, screen, storage — with the shapes their docs give): the page finds
// the phone among other devices, connects, switches the phone's answers on, says HELLO, finds the turns of a simulated
// wrist itself and sends one packet per turn, one write at a time; vibrates on «next» / «previous»; stops the sensor on
// «pause» and starts it on «stream»; looks for the phone again when the link drops; stops on «Стоп». The packets it
// wrote go to a file for the phone's Java (WristJsTest) to read: right, then left.
// usage: node test/watch/apps_test.mjs WATCH_DIR OUT_DIR
import fs from 'fs'
import path from 'path'
import { pathToFileURL } from 'url'

const [watchDir, out] = process.argv.slice(2)
fs.mkdirSync(out, { recursive: true })
const SERVICE = 'a7b7e001-9ca3-4574-a1f0-3c9840e24d01'
const SAMPLES = 'a7b7e002-9ca3-4574-a1f0-3c9840e24d01'
const EVENTS = 'a7b7e003-9ca3-4574-a1f0-3c9840e24d01'
let bad = 0
const ok = (c, what) => {
  if (!c) bad++
  console.log((c ? 'ok   ' : 'FAIL ') + what)
}
const hex = (buf) => Buffer.from(new Uint8Array(buf)).toString('hex')
const flush = () => new Promise((r) => setImmediate(r))

// ---------------------------------------------------------------- a clock and timers the test moves

let clock = 1_000_000
Date.now = () => clock
let timers = []
globalThis.setTimeout = (fn, ms) => {
  const t = { fn, at: clock + (ms || 0), id: timers.length + 1 }
  timers.push(t)
  return t.id
}
globalThis.clearTimeout = (id) => {
  timers = timers.filter((t) => t.id !== id)
}
async function advance(ms) {
  const end = clock + ms
  for (;;) {
    timers.sort((a, b) => a.at - b.at)
    const t = timers[0]
    if (!t || t.at > end) break
    timers.shift()
    clock = t.at
    t.fn()
    await flush()
  }
  clock = end
}

// ---------------------------------------------------------------- the wrist (Android's axes, face up, left wrist)

let seed = 9
const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff)
function wrist(segs, hz, unit) {
  const s = []
  for (const [deg, sec] of segs) {
    const n = Math.round(sec * hz)
    for (let k = 0; k < n; k++) {
      const th = (deg * Math.PI / 180) * Math.sin(Math.PI * k / n) ** 2
      const g = 9.80665 * unit
      s.push([0.01 * g * (rnd() - 0.5), g * Math.sin(th), g * Math.cos(th)])
    }
  }
  return s
}

// ---------------------------------------------------------------- loading a page with stand-in modules

function load(file, mocks, protoRel, target) {
  let src = fs.readFileSync(file, 'utf8')
  if (file.endsWith('.ux')) src = src.match(/<script>([\s\S]*)<\/script>/)[1]
  const dir = path.join(out, 'mods')
  fs.mkdirSync(dir, { recursive: true })
  fs.copyFileSync(path.join(watchDir, 'vela/src/common/proto.js'), path.join(dir, 'proto.mjs'))
  for (const [name, code] of Object.entries(mocks)) {
    const f = path.join(dir, name.replace(/[@/.]/g, '_') + '.mjs')
    fs.writeFileSync(f, code)
    src = src.split(`'${name}'`).join(`'${pathToFileURL(f).href}'`)
  }
  src = src.split(`'${protoRel}'`).join(`'${pathToFileURL(target || path.join(dir, 'proto.mjs')).href}'`)
  const f = path.join(dir, path.basename(file).replace(/\.\w+$/, '') + '_' + Object.keys(mocks).length + '.mjs')
  fs.writeFileSync(f, src)
  return import(pathToFileURL(f).href)
}

// the watches' system modules, recording what the page does into globalThis.W
const W = (globalThis.W = {})

// ================================================================ Zepp OS

async function zepp() {
  Object.assign(W, { writes: [], desc: [], scans: 0, scanning: false, connects: [], buzz: [], acc: null, accOn: false, outstanding: 0, overlap: false, disconnects: 0, profiles: [] })
  globalThis.Page = (def) => (globalThis.__page = def)
  await load(path.join(watchDir, 'zepp/pages/index.js'), {
    '@zos/ui': `export const widget = { TEXT: 1, BUTTON: 2 }; export const prop = { TEXT: 't' }; export const align = { CENTER_H: 0, CENTER_V: 0 };
      export const text_style = { WRAP: 0 }; export function createWidget(t, o) { return { o, setProperty(p, v) { if (t === 1) W.text = v; else W.button = v } } }`,
    '@zos/ble': `const cb = {}; W.ble = cb;
      export function mstStartScan(f) { W.scans++; W.scanning = true; cb.scan = f; return true }
      export function mstStopScan() { W.scanning = false }
      export function mstConnect(addr, f) { W.connects.push(addr); cb.connect = f }
      export function mstOnPrepare(f) { cb.prepare = f } export function mstOnDescWriteComplete(f) { cb.desc = f }
      export function mstOnCharaWriteComplete(f) { cb.write = f } export function mstOnCharaNotification(f) { cb.notify = f }
      export function mstBuildProfile(o) { W.profiles.push(o); setImmediate(() => cb.prepare && cb.prepare({ profile: 77, status: 0 })); return true }
      export function mstWriteDescriptor(p, ch, d, buf, len) { W.desc.push([p, ch, d, Array.from(new Uint8Array(buf))]); setImmediate(() => cb.desc && cb.desc({ profile: p, chara: ch, desc: d, status: 0 })); return true }
      export function mstWriteCharacteristic(p, uuid, buf, len) {
        if (W.outstanding) W.overlap = true
        W.outstanding = 1; W.writes.push([uuid, hex(buf), len])
        setImmediate(() => { W.outstanding = 0; cb.write && cb.write({ profile: p, uuid, status: 0 }) }) }
      export function mstOffAllCb() { for (const k of Object.keys(cb)) delete cb[k] }
      export function mstDestroyProfileInstance() {} export function mstDisconnect() { W.disconnects++ }
      function hex(b) { return Array.from(new Uint8Array(b)).map((x) => x.toString(16).padStart(2, '0')).join('') }`,
    '@zos/sensor': `export const FREQ_MODE_HIGH = 2, VIBRATOR_SCENE_SHORT_STRONG = 'short', VIBRATOR_SCENE_DURATION = 'long';
      export class Accelerometer { constructor() { W.acc = this } onChange(f) { this.f = f } offChange(f) { if (f === this.f) this.f = null }
        start() { W.accOn = true } stop() { W.accOn = false } setFreqMode(m) { this.mode = m } getCurrent() { return this.last } }
      export class Vibrator { setMode(m) { this.m = m } start() { W.buzz.push(this.m) } stop() {} }`,
    '@zos/display': `export function setPageBrightTime(o) { W.bright = o.brightTime; return 0 } export function pauseDropWristScreenOff() { W.kept = true; return 0 }
      export function resetDropWristScreenOff() { W.kept = false; return 0 }`,
    '@zos/storage': `const m = new Map(); export class LocalStorage { getItem(k, d) { return m.has(k) ? m.get(k) : d } setItem(k, v) { m.set(k, v) } }`,
    '@zos/utils': `export const px = (x) => x`
  }, '../common/proto')
  const def = globalThis.__page
  const page = Object.create(def)
  page.state = JSON.parse(JSON.stringify(def.state))
  page.build()
  ok(W.scanning && W.kept && W.bright > 3_600_000, 'zepp: opened — looking for the phone, the screen kept on')
  // another device first, then the phone
  W.ble.scan({ dev_name: 'scale', dev_addr: new Uint8Array([1, 2, 3, 4, 5, 6]).buffer, service_uuid_array: ['0000ffe0-0000-1000-8000-00805f9b34fb'] })
  ok(W.connects.length === 0, 'zepp: another device is passed by')
  const phone = new Uint8Array([0x5a, 0x11, 0x22, 0x33, 0x44, 0x55]).buffer
  W.ble.scan({ dev_name: '', dev_addr: phone, service_uuid_array: [SERVICE.toUpperCase()] })
  ok(W.connects.length === 1 && !W.scanning, 'zepp: the phone found by its service — scan stopped, connecting')
  W.ble.connect({ connected: 0, connect_id: 5, dev_addr: phone })
  await flush(); await flush(); await flush()
  const prof = W.profiles[0]
  const chars = prof && prof.list[0].list[0].list.map((c) => c.uuid)
  ok(prof && prof.id === 5 && prof.list[0].list[0].uuid === SERVICE && chars.includes(SAMPLES) && chars.includes(EVENTS),
    'zepp: the profile names the phone\'s service and both characteristics')
  ok(W.desc.length === 1 && W.desc[0][1] === EVENTS && W.desc[0][3].join() === '1,0', 'zepp: the phone\'s answers switched on (CCCD 01 00)')
  ok(W.accOn && W.acc.mode === 2, 'zepp: the accelerometer on, high frequency')
  // the wrist, 25 a second in cm/s²
  for (const [x, y, z] of wrist([[0, 2], [70, 0.6], [0, 1], [-70, 0.6], [0, 1]], 25, 100)) {
    clock += 40
    W.acc.last = { x, y, z }
    W.acc.f && W.acc.f({ x, y, z })
    await flush()
  }
  await flush()
  ok(!W.overlap, 'zepp: one write at a time')
  const sent = W.writes.filter((w) => w[0] === SAMPLES)
  ok(sent.length === 3 && sent[0][1].startsWith('0002') && Buffer.from(sent[0][1].slice(20), 'hex').toString() === 'zepp'
    && sent.slice(1).every((w) => w[1].startsWith('40')),
    'zepp: HELLO (the watch finds the turns) first, then one packet per turn: ' + (sent.length - 1) + ' — ' + W.text)
  ok(sent.every((w) => w[2] <= 20), 'zepp: every write fits 20 bytes')
  fs.writeFileSync(path.join(out, 'zepp_app.hex'), sent.map((w) => w[1]).join('\n') + '\n')
  // the phone's answers
  const say = (b) => W.ble.notify({ profile: 77, uuid: EVENTS, data: new Uint8Array([b]).buffer, length: 1 })
  say(1)
  say(2)
  ok(W.buzz.join() === 'short,short', 'zepp: «next» and «previous» buzz')
  say(4)
  ok(!W.accOn && !W.acc.f, 'zepp: «pause» — the accelerometer off')
  const before = W.writes.length
  W.acc.f && W.acc.f({ x: 0, y: 0, z: 981 })
  ok(W.writes.length === before, 'zepp: nothing sent while paused')
  say(5)
  ok(W.accOn && W.acc.f, 'zepp: «stream» — on again')
  // the link drops: looked for again in 2 s
  const scans = W.scans
  W.ble.connect({ connected: 2, connect_id: 5 })
  ok(!W.accOn && W.disconnects >= 1, 'zepp: the link dropped — sensor off, connection let go')
  await advance(2100)
  ok(W.scans === scans + 1 && W.scanning, 'zepp: looking for the phone again')
  page.stop()
  ok(!W.scanning && !W.kept && W.button === 'Старт', 'zepp: «Стоп» — nothing left running, the screen may sleep')
}

// ================================================================ Xiaomi Vela

async function vela() {
  // this firmware takes no «options» and wants every filter field (code 202 — wrong arguments — otherwise)
  Object.assign(W, { writes: [], notifies: [], scans: 0, scanning: false, gatts: [], buzz: [], acc: null, outstanding: 0, overlap: false, keep: null, closed: 0,
    starts: [], rejectScan: (o) => (o.options || !o.filters.length || o.filters.some((f) => !('deviceId' in f && 'name' in f)) ? 202 : 0) })
  const sysMocks = {
    '@system.bluetooth.ble': `export default {
        createScanner() { const s = { startBLEScan(o) { W.starts.push(o); const code = W.rejectScan ? W.rejectScan(o) : 0
            if (code) { setImmediate(() => o.fail && o.fail(null, code)); return }
            W.scans++; W.scanning = true; W.filters = o.filters; W.duty = o.options && o.options.dutyMode; setImmediate(() => o.success && o.success()) },
          stopBLEScan() { W.scanning = false }, subscribeBLEDeviceFind(o) { W.found = o.callback; return 3 }, unsubscribeBLEDeviceFind() {}, close() {} }; return s },
        createGattClientDevice(id, type) { const g = { id, type,
          connect(o) { W.connecting = g }, disconnect() {}, close() { W.closed++; return true },
          getServices(o) { if (W.hangDiscovery) return; setImmediate(() => o.success([{ serviceUuid: '00001800-0000-1000-8000-00805f9b34fb' }, { serviceUuid: '${SERVICE.toUpperCase()}', isPrimary: true }])) },
          setNotifyCharacteristicChanged(o) { W.notifies.push(o); if (W.hangNotify) return; setImmediate(() => { o.success && o.success(); o.complete && o.complete() }) },
          writeCharacteristicValue(o) {
            if (W.outstanding && !W.hangWrites) W.overlap = true
            W.outstanding = 1; const v = o.characteristic.characteristicValue
            W.writes.push([o.characteristic.serviceUuid, o.characteristic.characteristicUuid, Array.from(new Uint8Array(v)).map((x) => x.toString(16).padStart(2, '0')).join(''), o.characteristic.descriptors])
            if (W.hangWrites) return  // the completion never comes: the watch's write watchdog must keep the stream going
            if (W.failVoice && new Uint8Array(v)[0] === 0x51) { W.failVoice--; W.writes.pop(); W.failed = (W.failed || 0) + 1
              setImmediate(() => { W.outstanding = 0; o.fail && o.fail(null, 10004); o.complete && o.complete() }); return }
            setImmediate(() => { W.outstanding = 0; o.success && o.success(); o.complete && o.complete() }) },
          setBLEMtuSize(o) { W.mtuAsked = o.mtu; setImmediate(() => o.success && o.success()) } }
          W.gatts.push(g); return g } }`,
    '@system.sensor': `export default { subscribeAccelerometer(o) { W.acc = o }, unsubscribeAccelerometer() { W.acc = null },
      subscribeCompass(o) { W.compass = o }, unsubscribeCompass() { W.compass = null } }`,
    '@system.vibrator': `export default { vibrate(o) { W.buzz.push(o.mode) } }`,
    '@system.brightness': `export default { setKeepScreenOn(o) { W.keep = o.keepScreenOn } }`,
    '@system.storage': `const m = new Map(); W.storage = m; export default { get(o) { o.success(m.get(o.key) || '') }, set(o) { m.set(o.key, o.value) } }`,
    '@system.record': `const R = { start(o) { W.recording = o; W.recStarts = (W.recStarts || 0) + 1; W.recAt = Date.now() },
        stop() { const o = W.recording; W.recording = null; W.recStops = (W.recStops || 0) + 1
          setImmediate(() => { R.onframerecorded && R.onframerecorded({ frameBuffer: new Uint8Array(W.lastFrame || [1]).buffer, isLastFrame: true })
            o && o.success && o.success({}); o && o.complete && o.complete() }) } }
      W.record = R; export default R`,
    '@system.file': `export default { readArrayBuffer(o) { o.fail && o.fail(null, 300) } }`,
    '@system.audio': `const A = { play() { W.audioPlays = (W.audioPlays || 0) + 1; W.audioOn = true; W.audioSrc = A.src; W.audioLoop = A.loop
          W.played = (W.played || []).concat([[A.src, Date.now()]])
          setImmediate(() => A.onplay && A.onplay())
          if (!A.loop) { const src = A.src; setTimeout(() => { if (A.src === src) { W.audioOn = false; A.onended && A.onended() } }, 300) } },
        pause() { W.audioOn = false; A.onpause && A.onpause() },
        stop() { W.audioOn = false; setImmediate(() => A.onstop && A.onstop()) } }
      W.audio = A; export default A`
  }
  // the link (common/link.js) is made by the app (app.ux) with the watch's modules; the page only shows it
  const dir = path.join(out, 'mods')
  fs.mkdirSync(dir, { recursive: true })
  fs.copyFileSync(path.join(watchDir, 'vela/src/common/proto.js'), path.join(dir, 'proto.mjs'))
  const linkFile = path.join(dir, 'link.mjs')
  fs.writeFileSync(linkFile, fs.readFileSync(path.join(watchDir, 'vela/src/common/link.js'), 'utf8')
    .split(`'./proto.js'`).join(`'${pathToFileURL(path.join(dir, 'proto.mjs')).href}'`))
  const appMod = await load(path.join(watchDir, 'vela/src/app.ux'), sysMocks, './common/link.js', linkFile)
  const pageMod = await load(path.join(watchDir, 'vela/src/pages/index/index.ux'), {}, '../../common/proto.js')
  const linkMod = await import(pathToFileURL(linkFile).href)
  const sys = {}
  for (const [k, name] of [['ble', '@system.bluetooth.ble'], ['sensor', '@system.sensor'], ['vibrator', '@system.vibrator'],
    ['brightness', '@system.brightness'], ['storage', '@system.storage'], ['record', '@system.record'], ['file', '@system.file'],
    ['audio', '@system.audio']]) {
    sys[k] = (await import(pathToFileURL(path.join(dir, name.replace(/[@/.]/g, '_') + '.mjs')).href)).default
  }
  /** Another link, as the app makes it (each scenario its own). */
  const newLink = () => {
    const l = linkMod.createLink(sys)
    l.init()
    return l
  }
  const app = appMod.default
  app.onCreate()
  const page = app.link
  // the page: what it shows is the link's
  const pageDef = pageMod.default
  const screen = Object.create(pageDef)
  Object.assign(screen, JSON.parse(JSON.stringify(pageDef.data)))
  screen.$app = { $def: app }
  screen.onInit()
  await flush(); await flush(); await flush(); await flush()
  ok(W.scanning && W.filters[0].serviceUuid === SERVICE && W.keep !== true, 'vela: opened — scanning for the phone\'s service; the screen may sleep')
  ok(W.audioOn && W.audioSrc === '/common/quiet.mp3' && W.audioLoop === true && /в фоне/.test(screen.bg),
    'vela: silence playing round and round — the app runs on in the background («' + screen.bg + '»)')
  ok(screen.on === true && screen.state === page.state, 'vela: the page shows the link («' + screen.state + '»)')
  ok(W.starts.length === 3 && !W.starts[2].options && W.storage.get('wrist_scan') === '2',
    'vela: the parameters the firmware refused (202) — the next shape tried, the one taken kept (' + W.starts.length + ' tries)')
  // an advertisement without our service is passed by; the phone's (with the service in its bytes) taken
  const le = Buffer.from(SERVICE.replace(/-/g, ''), 'hex').reverse()
  W.found([{ deviceId: '11:22:33:44:55:66', rssi: -70, addressType: 'PUBLIC', data: new Uint8Array([2, 1, 6, 3, 3, 0xe0, 0xff]).buffer }])
  clock += 1100 // the count on the screen changes at most once a second
  W.found([{ deviceId: '11:22:33:44:55:67', rssi: -80, addressType: 'RANDOM' }])
  ok(W.gatts.length === 0 && /рядом устройств: 2/.test(page.state), 'vela: other devices (and one without its bytes) passed by — «' + page.state + '»')
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  const g = W.gatts[0]
  ok(g && g.id === '5A:11:22:33:44:55' && g.type === 'RANDOM' && !W.scanning, 'vela: the phone found (random address kept), scan stopped')
  g.onBLEConnectionStateChange(2)
  await flush(); await flush(); await flush()
  const nc = W.notifies[0] && W.notifies[0].characteristic
  ok(W.notifies.length === 1 && W.notifies[0].enable === true && nc.characteristicUuid === EVENTS && nc.serviceUuid === SERVICE
    && Array.isArray(nc.descriptors) && nc.properties && nc.properties.notify === true,
    'vela: services found, the phone\'s answers switched on (every field of the characteristic given)')
  ok(page.state === 'слушаю повороты кисти', 'vela: subscribed — «' + page.state + '»')
  ok(W.acc && W.acc.interval === 'ui', 'vela: the accelerometer on, «ui» pace first')
  // the wrist, about 50 a second in m/s², a little uneven
  for (const [x, y, z] of wrist([[0, 2], [70, 0.6], [0, 1], [-70, 0.6], [0, 1]], 50, 1)) {
    clock += 20 + Math.round((rnd() - 0.5) * 6)
    W.acc && W.acc.callback({ x, y, z })
    await flush()
  }
  await flush()
  ok(!W.overlap, 'vela: one write at a time')
  ok(W.acc && W.acc.interval === 'ui' && page.paceKnown, 'vela: «ui» gave live numbers — kept')
  const sent = W.writes.filter((w) => w[1] === SAMPLES)
  page.showDiag() // (the screen itself is refreshed at most every 0.4 s)
  const d = page.diag.match(/^поворот \d+° за [\d,]+ с · датчик (\d+) \(ui\) · компас ждёт · повороты (\d+) · записи (\d+): ✓(\d+) ✗(\d+) ⏱(\d+)/)
  ok(d && +d[1] > 200 && +d[2] === sent.length - 1 && +d[3] === sent.length && +d[4] === sent.length && +d[5] === 0,
    'vela: the counters on the screen — the last turn, sensor, turns, writes and how they ended («' + page.diag + '»)')
  ok(sent.length === 3 && sent[0][2].startsWith('0002') && Buffer.from(sent[0][2].slice(20), 'hex').toString() === 'vela'
    && sent.slice(1).every((w) => w[2].startsWith('40')),
    'vela: HELLO (the watch finds the turns) first, then one packet per turn: ' + (sent.length - 1))
  ok(sent.every((w) => w[2].length <= 40 && w[0] === SERVICE && Array.isArray(w[3])), 'vela: every write fits 20 bytes, with its service and descriptors')
  fs.writeFileSync(path.join(out, 'vela_app.hex'), sent.map((w) => w[2]).join('\n') + '\n')
  const say = (b) => g.onBLECharacteristicChange({ serviceUuid: SERVICE, characteristicUuid: EVENTS,
    characteristicValue: new Uint8Array(Array.isArray(b) ? b : [b]).buffer })
  say(1)
  say(3)
  ok(W.buzz.join() === 'short,long', 'vela: «next» buzzes short, «learnt» long')
  say([6, 1, 23, 0]) // the phone's settings: the left hand, no voice notes
  ok(page.voiceOn === false && W.storage.get('wrist_voice') === '0', 'vela: the phone\'s settings taken (voice notes off) and kept')
  say(4)
  ok(W.acc === null && W.compass === null && /не играет музыка/.test(page.state) && /телефон: пауза/.test(page.diag),
    'vela: «pause» — the accelerometer off, and the screen says why («' + page.state + '»)')
  say(5)
  ok(W.acc !== null, 'vela: «stream» — on again')
  const scans = W.scans
  g.onBLEConnectionStateChange(0)
  ok(W.acc === null && W.closed >= 1, 'vela: the link dropped — sensor off, connection closed')
  const starts = W.starts.length
  await advance(2100)
  await flush(); await flush()
  ok(W.scans === scans + 1 && W.scanning && W.starts.length === starts + 1, 'vela: looking for the phone again, at once with the shape that worked')
  // the write completion never comes (what stalled the S5 after HELLO): the watchdog keeps the stream flowing
  W.hangWrites = true
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  W.gatts[W.gatts.length - 1].onBLEConnectionStateChange(2)
  await flush(); await flush()
  const w0 = W.writes.length
  for (const [x, y, z] of wrist([[0, 1.5], [70, 0.4], [0, 0.7], [-70, 0.4], [0, 0.7], [70, 0.4], [0, 0.7]], 50, 1)) {
    W.acc && W.acc.callback({ x, y, z })
    await flush()
    await advance(20)
  }
  const wrote = W.writes.length - w0
  ok(wrote === 4, 'vela: writes keep flowing though no completion comes (' + wrote + ' writes: HELLO and three turns)')
  W.hangWrites = false
  page.stop(); await flush(); page.start(); await flush(); await flush(); await flush(); await flush()

  // enabling the phone's answers never completes: after 4 s the watch streams anyway (no vibration/pause), and a turn
  // still switches the track — the remote does not depend on the answers
  W.hangNotify = true
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  W.gatts[W.gatts.length - 1].onBLEConnectionStateChange(2)
  await flush(); await flush()
  ok(/включаю ответы/.test(page.state), 'vela: discovered, enabling answers («' + page.state + '»)')
  await advance(4100); await flush(); await flush()
  ok(page.state === 'слушаю повороты кисти (без отклика)' && W.acc !== null, 'vela: answers did not come — streaming anyway, without feedback («' + page.state + '»)')
  W.hangNotify = false
  page.stop()
  await flush()
  page.start()
  await flush(); await flush(); await flush(); await flush()

  // the link comes up but service discovery never answers (the user's symptom if it gets past connecting): the step
  // watchdog lets go after 8 s and tries again, and the screen shows which step stalled
  W.hangDiscovery = true
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  W.gatts[W.gatts.length - 1].onBLEConnectionStateChange(2)
  await flush(); await flush()
  ok(/узнаю сервисы/.test(page.state), 'vela: connected — now discovering services («' + page.state + '»)')
  await advance(8100)
  ok(/узнаю сервисы.*не отвеча/.test(page.state), 'vela: discovery did not answer in 8 s — says so, lets go')
  await advance(2100); await flush(); await flush()
  W.hangDiscovery = false
  page.tries = 0

  // the phone found but the link never comes up (the watch's connect has no limit of its own): let go after 15 s and
  // look again; after three tries — stop and say what usually helps
  for (let t = 1; t <= 3; t++) {
    W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
    ok(/подключаюсь/.test(page.state) && (t === 1 || page.state.includes('попытка ' + t)), 'vela: connecting, try ' + t + ' — «' + page.state + '»')
    await advance(15100)
    if (t < 3) {
      ok(/ищу снова/.test(page.state), 'vela: no link in 15 s — let go, looking again')
      await advance(2100)
      await flush(); await flush()
    }
  }
  ok(page.on === false && /Mi Fitness/.test(page.state) && !W.audioOn && !W.scanning,
    'vela: three tries without a link — stopped, the screen may sleep, the hint shown')
  page.toggle()
  await flush(); await flush()
  ok(page.on === true && W.scanning && W.audioOn, 'vela: «Старт» again — looking for the phone')
  page.toggle()
  ok(!W.audioOn && page.on === false && screen.on === false, 'vela: «Стоп» — off, the silence stopped (nothing keeps the app running)')
  page.stop()

  // the S5's «game» pace gave one value over and over: a pace whose numbers never change for 2.5 s is given up for the
  // next, slower one, measured again and announced with a new HELLO (the phone then knows the new rate)
  const p2 = newLink()
  await flush(); await flush(); await flush(); await flush()
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  W.gatts[W.gatts.length - 1].onBLEConnectionStateChange(2)
  await flush(); await flush(); await flush()
  ok(W.acc && W.acc.interval === 'ui', 'vela (static sensor): «ui» first')
  const w1 = W.writes.length
  const t1 = clock
  while (clock - t1 < 6000) {
    const ui = W.acc && W.acc.interval === 'ui'
    clock += ui ? 60 : 200
    // the same numbers at «ui»; live ones at «normal»
    W.acc && W.acc.callback(ui ? { x: 1, y: 6, z: 7 } : { x: 1 + rnd() / 10, y: 6, z: 7 })
    await flush()
    await advance(0)
  }
  ok(W.acc && W.acc.interval === 'normal' && p2.paceKnown, 'vela (static sensor): «ui» gave one value over and over — «normal» now')
  const hellos = W.writes.slice(w1).filter((w) => w[1] === SAMPLES && w[2].startsWith('0002'))
  ok(hellos.length === 2 && hellos[0][2].slice(12, 16) === '1100' && hellos[1][2].slice(12, 16) === '0500',
    'vela (static sensor): HELLO at ~17 a second, then again at 5 (' + hellos.map((h) => h[2].slice(12, 16)).join(', ') + ')')
  p2.showDiag()
  ok(/\(normal\)/.test(p2.diag) && /записи [1-9]/.test(p2.diag), 'vela (static sensor): listening at «normal» — «' + p2.diag + '»')
  p2.stop()

  // the Xiaomi Watch S5 as it is: whole m/s², and the samples handed over three at a time with the same Date.now() —
  // the turns are still found (once they were not: «повороты 0»)
  const p3 = newLink()
  await flush(); await flush(); await flush(); await flush()
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  W.gatts[W.gatts.length - 1].onBLEConnectionStateChange(2)
  await flush(); await flush(); await flush()
  const w3 = W.writes.length
  const s5 = wrist([[0, 2], [65, 0.4], [0, 1], [-65, 0.4], [0, 1]], 20, 1)
  for (let i = 0; i < s5.length; i++) {
    if (i % 3 === 0) await advance(150) // a batch every 150 ms…
    const [x, y, z] = s5[i]
    W.acc && W.acc.callback({ x: Math.round(x), y: Math.round(y), z: Math.round(z) }) // …of three, at the same moment
    await flush()
  }
  await advance(300)
  const s5w = W.writes.slice(w3).filter((w) => w[1] === SAMPLES)
  ok(s5w.length === 3 && s5w[0][2].startsWith('0002') && s5w[1][2].startsWith('40') && s5w[2][2].startsWith('40')
    && /повороты 2/.test(p3.diag), 'vela (S5: whole m/s², samples in batches): HELLO and both turns — «' + p3.diag + '»')
  fs.writeFileSync(path.join(out, 'vela_s5_app.hex'), s5w.map((w) => w[2]).join('\n') + '\n')
  p3.stop()

  // the arm hanging along the body: the forearm (x) points down, gravity stays put in a twist, the compass turns —
  // twisted right (east) and back, then left; the compass reports 10 times a second
  const p4 = newLink()
  await flush(); await flush(); await flush(); await flush()
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  W.gatts[W.gatts.length - 1].onBLEConnectionStateChange(2)
  await flush(); await flush(); await flush()
  ok(W.compass && typeof W.compass.callback === 'function', 'vela (arm hanging): the compass on with the accelerometer')
  const w4 = W.writes.length
  const h0 = 1.2
  let hAt = -1e9
  const twists = [[0, 2], [60, 0.5], [0, 1], [-60, 0.5], [0, 1]]
  for (const [deg, sec] of twists) {
    const n = Math.round(sec * 20)
    for (let i = 0; i < n; i++) {
      const th = (deg * Math.PI / 180) * Math.sin(Math.PI * i / n) ** 2
      await advance(50)
      if (clock - hAt >= 100) { W.compass && W.compass.callback({ direction: h0 + th + (rnd() - 0.5) * 0.02, accuracy: 2 }); hAt = clock }
      W.acc && W.acc.callback({ x: -9.8 + (rnd() - 0.5) * 0.1, y: (rnd() - 0.5) * 0.1, z: (rnd() - 0.5) * 0.1 })
      await flush()
    }
  }
  await advance(300)
  const hw = W.writes.slice(w4).filter((w) => w[1] === SAMPLES)
  p4.showDiag()
  ok(hw.length === 3 && hw[1][2].startsWith('40') && hw[2][2].startsWith('40') && /компас \d+°/.test(p4.diag) && /\(компас\)/.test(p4.diag),
    'vela (arm hanging): both twists found by the compass — «' + p4.diag + '»')
  fs.writeFileSync(path.join(out, 'vela_hanging_app.hex'), hw.map((w) => w[2]).join('\n') + '\n')
  p4.stop()
  ok(W.compass === null, 'vela: the compass off when the page goes')

  // a voice note: the music paused (the sensor stays on for the notes), the watch raised to the mouth and held — it
  // records, the frames go to the phone as they come (one write lost and written again), lowered — the end; the phone
  // asks for some again, then says it is saved
  W.storage.set('wrist_voice', '1')
  const p5 = newLink()
  await flush(); await flush(); await flush(); await flush()
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  const g5 = W.gatts[W.gatts.length - 1]
  g5.onBLEConnectionStateChange(2)
  await flush(); await flush(); await flush()
  ok(W.mtuAsked === 247, 'vela (voice): a bigger MTU asked for')
  const say5 = (b) => g5.onBLECharacteristicChange({ serviceUuid: SERVICE, characteristicUuid: EVENTS, characteristicValue: new Uint8Array(b).buffer })
  say5([6, 3, 247, 0]) // the left hand, voice notes on, MTU 247
  const w5 = W.writes.length
  const g9 = 9.81
  const pose = (u, k) => { const m = Math.hypot(...u); return u.map((c) => (c / m) * g9 * (k || 1) + (rnd() - 0.5) * 0.2) }
  const HANG = [-1, 0.05, 0.1]
  const MOUTH = [0.85, 0.35, 0.12]
  const step = async (u, k) => { await advance(50); const [x, y, z] = pose(u, k); W.acc && W.acc.callback({ x, y, z }); await flush() }
  for (let i = 0; i < 40; i++) await step(HANG)
  say5([4]) // «pause»
  ok(W.acc !== null && /поднесите часы ко рту/.test(p5.state), 'vela (voice): «pause» — the sensor stays on for the notes («' + p5.state + '»)')
  const buzz0 = W.buzz.length
  // raised in half a second, held
  for (let i = 0; i <= 10; i++) await step(HANG.map((h, j) => h + (MOUTH[j] - h) * (i / 10)), 1 + 0.3 * Math.sin(Math.PI * i / 10))
  const frames = []
  W.failVoice = 1
  for (let i = 0; i < 60; i++) {
    await step(MOUTH)
    if (W.recording && i % 8 === 0) {
      // a frame every 0.4 s: the recorder's Ogg file in pieces
      const f = new Uint8Array(400)
      for (let k = 0; k < f.length; k++) f[k] = (frames.length * 13 + k * 5) & 0xff
      if (frames.length === 0) f.set([0x4f, 0x67, 0x67, 0x53])
      frames.push(f)
      W.record.onframerecorded({ frameBuffer: f.buffer, isLastFrame: false })
      await flush()
    }
  }
  ok(W.recStarts === 1 && W.recording && W.recording.format === 'opus' && W.recording.sampleRate === 16000 && W.recording.frameSize > 0
    && W.buzz[buzz0] === 'short' && /говорите/.test(p5.note), 'vela (voice): held to the mouth — recording Opus 16 kHz in frames, a short buzz, «' + p5.note + '»')
  const startBells = W.played.filter((p) => p[0] === '/common/start.mp3').pop()
  ok(startBells && startBells[1] < W.recAt && W.recAt - startBells[1] <= 700 && W.played[W.played.length - 1][0] === '/common/quiet.mp3',
    'vela (voice): the rising bells first, the recording as they end (' + (W.recAt - startBells[1]) + ' ms later), then the silence again')
  // the watch paused the silence meanwhile (the recorder took the sound): not started again over the recording
  const plays5 = W.audioPlays
  W.audio.pause()
  await advance(2500)
  ok(W.audioPlays === plays5, 'vela (voice): the background\'s silence not restarted over the recording')
  // lowered
  W.lastFrame = [7, 7, 7]
  frames.push(new Uint8Array(W.lastFrame))
  for (let i = 0; i <= 20; i++) await step(MOUTH.map((m, j) => m + (HANG[j] - m) * Math.min(1, i / 10)))
  await advance(500)
  const vw = W.writes.slice(w5).filter((w) => w[1] === SAMPLES).map((w) => w[2])
  const endBells = W.played.filter((p) => p[0] === '/common/end.mp3').pop()
  ok(endBells && endBells[1] >= W.recAt && W.played[W.played.length - 1][0] === '/common/quiet.mp3' && W.audioOn,
    'vela (voice): the recording over — the falling bells, then the silence plays again')
  ok(W.recStops === 1 && vw.some((w) => w.startsWith('50')) && vw[vw.length - 1].startsWith('52') && W.failed === 1,
    'vela (voice): lowered — the recording stopped, START, the bytes, END (' + vw.filter((w) => w.startsWith('51')).length + ' writes of bytes, one lost and written again)')
  ok(!vw.some((w) => w.startsWith('40')), 'vela (voice): no turns sent meanwhile')
  ok(vw.filter((w) => w.startsWith('51')).every((w) => w.length / 2 <= 244), 'vela (voice): writes as big as the MTU takes (≤ 244 bytes)')
  const noteId = parseInt(vw.find((w) => w.startsWith('50')).slice(2, 4), 16)
  // the phone asks for the bytes from 238 again
  const n0 = W.writes.length
  say5([8, noteId, 238, 0, 0, 0])
  await advance(300)
  const again = W.writes.slice(n0).filter((w) => w[1] === SAMPLES).map((w) => w[2])
  ok(again.length >= 2 && again[0].startsWith('51' + vw.find((w) => w.startsWith('50')).slice(2, 4) + 'ee000000') && again[again.length - 1].startsWith('52'),
    'vela (voice): «again from 238» — sent again from there, and the end')
  fs.writeFileSync(path.join(out, 'vela_voice_app.hex'), vw.concat(again).join('\n') + '\n')
  fs.writeFileSync(path.join(out, 'vela_voice_bytes.hex'), Buffer.concat(frames.map((f) => Buffer.from(f))).toString('hex') + '\n')
  // done: saved
  say5([9, noteId, 0, ...Buffer.from('Сохранено: купить хлеб')])
  ok(W.buzz[W.buzz.length - 1] === 'long' && p5.note === 'Сохранено: купить хлеб' && p5.voices.length === 0,
    'vela (voice): the phone saved it — a long buzz, «' + p5.note + '»')
  // a raise too short to be a note: nothing sent
  const n1 = W.writes.length
  for (let i = 0; i < 40; i++) await step(HANG)
  for (let i = 0; i <= 6; i++) await step(HANG.map((h, j) => h + (MOUTH[j] - h) * (i / 6)))
  for (let i = 0; i < 16; i++) await step(MOUTH)
  for (let i = 0; i <= 20; i++) await step(MOUTH.map((m, j) => m + (HANG[j] - m) * Math.min(1, i / 4)))
  await advance(2000)
  ok(W.recStarts === W.recStops && W.writes.slice(n1).filter((w) => w[1] === SAMPLES).length === 0 && p5.voices.length === 0,
    'vela (voice): held a moment and away — the recording dropped, nothing sent (' + W.recStarts + ' ' + W.recStops + ' '
    + W.writes.slice(n1).filter((w) => w[1] === SAMPLES).map((w) => w[2].slice(0, 6)).join(',') + ')')

  // taught: tapped «Запомнить положение у рта» while looking at the watch, held it to the mouth the way this hand does
  // (the forearm level across the chest — the usual rule does not take it) — remembered; then a note starts from there
  const ACROSS = [0.1, 0.95, 0.3]
  const LOOK = [0.3, 0.05, 0.95]
  const r0 = W.recStarts
  for (let i = 0; i < 40; i++) await step(HANG)
  for (let i = 0; i <= 10; i++) await step(HANG.map((h, j) => h + (ACROSS[j] - h) * (i / 10)))
  for (let i = 0; i < 30; i++) await step(ACROSS)
  for (let i = 0; i <= 10; i++) await step(ACROSS.map((m, j) => m + (HANG[j] - m) * (i / 10)))
  await advance(2000)
  ok(W.recStarts === r0, 'vela (taught): not taught yet — held across the chest, nothing recorded')
  for (let i = 0; i <= 10; i++) await step(HANG.map((h, j) => h + (LOOK[j] - h) * (i / 10)))
  for (let i = 0; i < 10; i++) await step(LOOK)
  p5.teachTalk()
  ok(/замрите/.test(p5.note), 'vela (taught): «Запомнить» — «' + p5.note + '»')
  for (let i = 0; i < 6; i++) await step(LOOK)
  for (let i = 0; i <= 10; i++) await step(LOOK.map((h, j) => h + (ACROSS[j] - h) * (i / 10)))
  for (let i = 0; i < 30; i++) await step(ACROSS)
  const kept = JSON.parse(W.storage.get('wrist_poses') || '[]')
  ok(p5.poses === 1 && kept.length === 1 && W.buzz[W.buzz.length - 1] === 'long' && /запомнил/.test(p5.note),
    'vela (taught): remembered (' + kept[0] + ') — a long buzz, «' + p5.note + '»')
  for (let i = 0; i <= 10; i++) await step(ACROSS.map((m, j) => m + (HANG[j] - m) * (i / 10)))
  for (let i = 0; i < 40; i++) await step(HANG)
  for (let i = 0; i <= 10; i++) await step(HANG.map((h, j) => h + (ACROSS[j] - h) * (i / 10)))
  for (let i = 0; i < 40; i++) await step(ACROSS)
  ok(W.recStarts === r0 + 1 && W.recording, 'vela (taught): raised to the taught pose — recording')
  for (let i = 0; i <= 20; i++) await step(ACROSS.map((m, j) => m + (HANG[j] - m) * Math.min(1, i / 10)))
  await advance(2000)
  ok(!W.recording && W.recStops === W.recStarts, 'vela (taught): lowered — stopped')
  p5.forgetTalk()
  ok(p5.poses === 0 && W.storage.get('wrist_poses') === '[]', 'vela (taught): «Забыть положения» — forgotten')
  p5.stop()

  // in the background: the page left, the link goes on — the turns are still found and sent; the silence stopped by the
  // watch (a call, an alarm) is played again; «Экран: не гаснет» keeps the screen on only while the page is open
  const p6 = newLink()
  const s6 = Object.create(pageDef)
  Object.assign(s6, JSON.parse(JSON.stringify(pageDef.data)))
  s6.$app = { $def: { link: p6 } }
  s6.onInit()
  await flush(); await flush(); await flush(); await flush()
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  W.gatts[W.gatts.length - 1].onBLEConnectionStateChange(2)
  await flush(); await flush(); await flush()
  ok(W.keep !== true && s6.screenOn === false, 'vela (background): the screen may sleep')
  s6.screen()
  ok(W.keep === true && s6.screenOn === true && W.storage.get('wrist_screen') === '1', 'vela: «Экран: не гаснет» — kept on while the page is open (and kept)')
  s6.onHide()
  ok(W.keep === false, 'vela: the page hidden — the screen may sleep again')
  s6.screen()
  s6.onDestroy()
  ok(p6.on === true && W.audioOn, 'vela (background): the page gone — the link still on, the silence playing')
  const w6 = W.writes.length
  for (const [x, y, z] of wrist([[0, 2], [65, 0.4], [0, 1], [-65, 0.4], [0, 1]], 20, 1)) {
    await advance(50)
    W.acc && W.acc.callback({ x, y, z })
    await flush()
  }
  await advance(300)
  const b6 = W.writes.slice(w6).filter((w) => w[1] === SAMPLES)
  ok(b6.length === 3 && b6[0][2].startsWith('0002') && b6[1][2].startsWith('40') && b6[2][2].startsWith('40'),
    'vela (background): no page — HELLO and both turns still sent')
  const plays6 = W.audioPlays
  W.audioOn = false
  W.audio.onstop()
  await advance(2100)
  ok(W.audioPlays === plays6 + 1 && W.audioOn, 'vela (background): the silence stopped by the watch — played again in 2 s')
  W.audio.onerror(); W.audio.onerror(); W.audio.onerror()
  ok(/не работает/.test(p6.bg), 'vela (background): the sound fails again and again — said so («' + p6.bg + '»)')
  p6.stop()
  ok(!W.audioOn, 'vela: «Стоп» — the silence stopped')

  // the log: the phone asks for it (CONFIG with the debug flag) — the watch sends its sensor sample by sample with its
  // own times (RAW) and its doings (DIAG), after the turns, which still go first
  const p8 = newLink()
  await flush(); await flush(); await flush(); await flush()
  W.found([{ deviceId: '5A:11:22:33:44:55', rssi: -60, addressType: 'RANDOM', data: new Uint8Array([2, 1, 6, 17, 7, ...le]).buffer }])
  const g8 = W.gatts[W.gatts.length - 1]
  g8.onBLEConnectionStateChange(2)
  await flush(); await flush(); await flush()
  const w8 = W.writes.length
  g8.onBLECharacteristicChange({ serviceUuid: SERVICE, characteristicUuid: EVENTS, characteristicValue: new Uint8Array([6, 7, 247, 0]).buffer })
  const s8 = wrist([[0, 2], [65, 0.4], [0, 1], [-65, 0.4], [0, 1]], 20, 1)
  for (let i = 0; i < s8.length; i++) {
    if (i % 3 === 0) await advance(150)
    const [x, y, z] = s8[i]
    W.acc && W.acc.callback({ x: Math.round(x), y: Math.round(y), z: Math.round(z) })
    await flush()
  }
  await advance(1500)
  const d8 = W.writes.slice(w8).filter((w) => w[1] === SAMPLES).map((w) => w[2])
  const raws = d8.filter((w) => w.startsWith('60'))
  const lines = d8.filter((w) => w.startsWith('70')).map((w) => Buffer.from(w.slice(2), 'hex').toString('utf8'))
  const rolls = d8.filter((w) => w.startsWith('40'))
  const rawCount = raws.reduce((n, w) => n + parseInt(w.slice(12, 14), 16), 0)
  ok(rolls.length === 2 && raws.length > 0 && rawCount >= s8.length - 23 && rawCount <= s8.length,
    'vela (log): both turns, and the sensor sample by sample (' + rawCount + ' of ' + s8.length + ' in ' + raws.length + ' writes)')
  ok(lines.some((l) => /журнал: включён/.test(l)) && lines.filter((l) => /^поворот .*отправлен/.test(l)).length === 2,
    'vela (log): the watch\'s lines — «' + lines.slice(0, 3).join('» «') + '»…')
  ok(raws.every((w) => w.length / 2 <= 244), 'vela (log): every write within the MTU')
  fs.writeFileSync(path.join(out, 'vela_debug_app.hex'), d8.join('\n') + '\n')
  // the log no longer asked for: nothing more of it
  g8.onBLECharacteristicChange({ serviceUuid: SERVICE, characteristicUuid: EVENTS, characteristicValue: new Uint8Array([6, 3, 247, 0]).buffer })
  const w9 = W.writes.length
  for (let i = 0; i < 40; i++) {
    await advance(50)
    W.acc && W.acc.callback({ x: 0, y: 0, z: 10 })
    await flush()
  }
  ok(W.writes.slice(w9).filter((w) => w[1] === SAMPLES && /^(60|70)/.test(w[2])).length === 0, 'vela (log): off — no more samples or lines')
  p8.stop()
}

await zepp()
await vela()
console.log(bad === 0 ? 'WATCH APPS OK' : bad + ' FAILED')
if (bad) process.exit(1)
