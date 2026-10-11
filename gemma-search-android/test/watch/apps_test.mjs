// The Xiaomi Vela and Zepp OS watch apps' own page code, run in Node over stand-ins of the watches' system modules
// (Bluetooth LE client, accelerometer, vibration, screen, storage — with the shapes their docs give): the page finds
// the phone among other devices, connects, switches the phone's answers on, says HELLO, streams a simulated wrist,
// one write at a time; vibrates on «next» / «previous»; stops the sensor on «pause» and starts it on «stream»; looks
// for the phone again when the link drops; stops on «Стоп». The packets it wrote go to a file for the phone's Java
// (WristJsTest) to recognise: right, then left.
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

function load(file, mocks, protoRel) {
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
  src = src.split(`'${protoRel}'`).join(`'${pathToFileURL(path.join(dir, 'proto.mjs')).href}'`)
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
  ok(sent.length > 20 && sent[0][1].startsWith('0001') && Buffer.from(sent[0][1].slice(20), 'hex').toString() === 'zepp',
    'zepp: HELLO first, then ' + (sent.length - 1) + ' sample packets')
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
  const mod = await load(path.join(watchDir, 'vela/src/pages/index/index.ux'), {
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
            setImmediate(() => { W.outstanding = 0; o.success && o.success(); o.complete && o.complete() }) } }
          W.gatts.push(g); return g } }`,
    '@system.sensor': `export default { subscribeAccelerometer(o) { W.acc = o }, unsubscribeAccelerometer() { W.acc = null } }`,
    '@system.vibrator': `export default { vibrate(o) { W.buzz.push(o.mode) } }`,
    '@system.brightness': `export default { setKeepScreenOn(o) { W.keep = o.keepScreenOn } }`,
    '@system.storage': `const m = new Map(); W.storage = m; export default { get(o) { o.success(m.get(o.key) || '') }, set(o) { m.set(o.key, o.value) } }`
  }, '../../common/proto.js')
  const def = mod.default
  const page = Object.create(def)
  Object.assign(page, JSON.parse(JSON.stringify(def.data)))
  page.onInit()
  await flush(); await flush(); await flush(); await flush()
  ok(W.scanning && W.keep === true && W.filters[0].serviceUuid === SERVICE, 'vela: opened — scanning for the phone\'s service, the screen kept on')
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
  ok(W.acc && W.acc.interval === 'game', 'vela: the accelerometer on, «game» rate')
  // the wrist, about 50 a second in m/s², a little uneven
  for (const [x, y, z] of wrist([[0, 2], [70, 0.6], [0, 1], [-70, 0.6], [0, 1]], 50, 1)) {
    clock += 20 + Math.round((rnd() - 0.5) * 6)
    W.acc && W.acc.callback({ x, y, z })
    await flush()
  }
  await flush()
  ok(!W.overlap, 'vela: one write at a time')
  const sent = W.writes.filter((w) => w[1] === SAMPLES)
  ok(sent.length > 20 && sent[0][2].startsWith('0001') && Buffer.from(sent[0][2].slice(20), 'hex').toString() === 'vela',
    'vela: HELLO first, then ' + (sent.length - 1) + ' sample packets')
  ok(sent.every((w) => w[2].length <= 40 && w[0] === SERVICE && Array.isArray(w[3])), 'vela: every write fits 20 bytes, with its service and descriptors')
  fs.writeFileSync(path.join(out, 'vela_app.hex'), sent.map((w) => w[2]).join('\n') + '\n')
  const say = (b) => g.onBLECharacteristicChange({ serviceUuid: SERVICE, characteristicUuid: EVENTS, characteristicValue: new Uint8Array([b]).buffer })
  say(1)
  say(3)
  ok(W.buzz.join() === 'short,long', 'vela: «next» buzzes short, «learnt» long')
  say(4)
  ok(W.acc === null, 'vela: «pause» — the accelerometer off')
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
  for (let i = 0; i < 40; i++) { clock += 20; W.acc && W.acc.callback({ x: 0, y: 9.8 * Math.sin(i / 6), z: 9.8 * Math.cos(i / 6) }); await flush(); await advance(30) }
  const wrote = W.writes.length - w0
  ok(wrote >= 3, 'vela: writes keep flowing though no completion comes (' + wrote + ' writes)')
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
  ok(page.on === false && /Mi Fitness/.test(page.state) && W.keep === false && !W.scanning,
    'vela: three tries without a link — stopped, the screen may sleep, the hint shown')
  page.toggle()
  await flush(); await flush()
  ok(page.on === true && W.scanning && W.keep === true, 'vela: «Старт» again — looking for the phone')
  page.toggle()
  ok(W.keep === false && page.on === false, 'vela: «Стоп» — off, the screen may sleep')
  page.onDestroy()
}

await zepp()
await vela()
console.log(bad === 0 ? 'WATCH APPS OK' : bad + ' FAILED')
if (bad) process.exit(1)
