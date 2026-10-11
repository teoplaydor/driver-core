// The watch's side of «tracks by a turn of the wrist»: finds the phone (SemSearch → Настройки → Часы) by its Bluetooth
// LE service, connects, sends the raw accelerometer, vibrates on the phone's answer. The phone recognises the turn.
// Zepp OS keeps a page's sensor and Bluetooth only while the page is open, so the screen is kept on while it works;
// when the music stops the phone says «pause» and the sensor is switched off until it plays again.
import * as hmUI from '@zos/ui'
import * as ble from '@zos/ble'
import { Accelerometer, FREQ_MODE_HIGH, Vibrator, VIBRATOR_SCENE_SHORT_STRONG, VIBRATOR_SCENE_DURATION } from '@zos/sensor'
import { setPageBrightTime, pauseDropWristScreenOff, resetDropWristScreenOff } from '@zos/display'
import { LocalStorage } from '@zos/storage'
import { px } from '@zos/utils'
import {
  SERVICE, SAMPLES, EVENTS, CCCD, EV_NEXT, EV_PREVIOUS, EV_LEARNED, EV_PAUSE, EV_STREAM, createPacker
} from '../common/proto'

const PROFILE = 'semsearch-wrist'
const acc = new Accelerometer()
const vibrator = new Vibrator()

function watchId() {
  try {
    const ls = new LocalStorage()
    let id = parseInt(ls.getItem('wrist_id', '0'), 10) || 0
    if (!id) {
      // who this watch is to the phone: random, kept (the phone listens only to the watch it met first)
      id = (Math.floor(Math.random() * 0x7ffffffe) + 1) | 1
      ls.setItem('wrist_id', String(id))
    }
    return id
  } catch (e) {
    return (Math.floor(Math.random() * 0x7ffffffe) + 1) | 1
  }
}

function buzz(scene) {
  try {
    vibrator.stop()
    vibrator.setMode(scene)
    vibrator.start()
  } catch (e) {}
}

Page({
  state: {
    on: false,
    scanning: false,
    connectId: -1,
    profile: null,
    linked: false,
    listening: false,
    writing: false,
    queue: [],
    packer: null,
    timer: 0
  },

  build() {
    this.stateText = hmUI.createWidget(hmUI.widget.TEXT, {
      x: px(40), y: px(150), w: px(400), h: px(110),
      color: 0x8da6c0, text_size: px(28),
      align_h: hmUI.align.CENTER_H, align_v: hmUI.align.CENTER_V,
      text_style: hmUI.text_style.WRAP, text: ''
    })
    hmUI.createWidget(hmUI.widget.TEXT, {
      x: px(40), y: px(70), w: px(400), h: px(60),
      color: 0xe6edf3, text_size: px(34),
      align_h: hmUI.align.CENTER_H, align_v: hmUI.align.CENTER_V, text: 'Пульт кистью'
    })
    hmUI.createWidget(hmUI.widget.TEXT, {
      x: px(40), y: px(260), w: px(400), h: px(50),
      color: 0x8b96a3, text_size: px(24),
      align_h: hmUI.align.CENTER_H, align_v: hmUI.align.CENTER_V, text: 'Вправо — дальше, влево — назад'
    })
    this.button = hmUI.createWidget(hmUI.widget.BUTTON, {
      x: px(140), y: px(330), w: px(200), h: px(72), radius: px(36),
      normal_color: 0x8da6c0, press_color: 0x6d86a0, color: 0x0e1116, text_size: px(30),
      text: 'Стоп',
      click_func: () => (this.state.on ? this.stop() : this.start())
    })
    this.id = watchId()
    this.start()
  },

  onDestroy() {
    this.stop()
  },

  show(text) {
    if (this.stateText) this.stateText.setProperty(hmUI.prop.TEXT, text)
  },

  start() {
    if (this.state.on) return
    this.state.on = true
    if (this.button) this.button.setProperty(hmUI.prop.TEXT, 'Стоп')
    // the screen kept on while the page works (it would close the page 10 s after the screen goes off)
    setPageBrightTime({ brightTime: 2147483000 })
    pauseDropWristScreenOff({ duration: 0 })
    this.scan()
  },

  stop() {
    this.state.on = false
    this.stopScan()
    this.drop()
    if (this.state.timer) clearTimeout(this.state.timer)
    this.state.timer = 0
    try {
      resetDropWristScreenOff()
    } catch (e) {}
    if (this.button) this.button.setProperty(hmUI.prop.TEXT, 'Старт')
    this.show('выключено')
  },

  later(fn, ms) {
    if (this.state.timer) clearTimeout(this.state.timer)
    this.state.timer = setTimeout(() => {
      this.state.timer = 0
      if (this.state.on) fn()
    }, ms)
  },

  // ---------------------------------------------------------------- finding the phone

  scan() {
    if (!this.state.on || this.state.connectId >= 0) return
    this.show('ищу телефон…')
    this.state.scanning = true
    const ok = ble.mstStartScan((r) => {
      if (!this.state.scanning || !r || !r.service_uuid_array) return
      for (let i = 0; i < r.service_uuid_array.length; i++) {
        if (String(r.service_uuid_array[i]).toLowerCase() === SERVICE) {
          this.stopScan()
          this.connect(r.dev_addr)
          return
        }
      }
    })
    if (!ok) {
      this.state.scanning = false
      this.show('Bluetooth не отвечает')
      this.later(() => this.scan(), 10000)
    }
  },

  stopScan() {
    if (!this.state.scanning) return
    this.state.scanning = false
    try {
      ble.mstStopScan()
    } catch (e) {}
  },

  // ---------------------------------------------------------------- the link

  connect(addr) {
    this.show('подключаюсь…')
    ble.mstConnect(addr, (c) => {
      if (c.connected === 0) {
        this.state.connectId = c.connect_id
        this.prepare(addr, c.connect_id)
      } else {
        this.lost()
      }
    })
  },

  prepare(addr, id) {
    ble.mstOnPrepare((p) => {
      if (p.status !== 0) {
        this.lost()
        return
      }
      this.state.profile = p.profile
      // the phone's answers switched on, then HELLO
      ble.mstWriteDescriptor(p.profile, EVENTS, '2902', new Uint8Array([1, 0]).buffer, 2)
    })
    ble.mstOnDescWriteComplete(() => {
      if (!this.state.linked) this.linked()
    })
    ble.mstOnCharaWriteComplete(() => {
      this.state.writing = false
      this.pump()
    })
    ble.mstOnCharaNotification((n) => {
      if (n && n.data && n.data.byteLength > 0 && String(n.uuid).toLowerCase() === EVENTS) this.heard(new Uint8Array(n.data)[0])
    })
    ble.mstBuildProfile({
      pair: true, id, profile: PROFILE, dev: addr, len: 1,
      list: [{
        uuid: true, size: 1, len: 1,
        list: [{
          uuid: SERVICE, permission: 0, serv: 0, len1: 2, len2: 2,
          list: [
            { uuid: SAMPLES, permission: 32, desc: 0, len: 0 },
            { uuid: EVENTS, permission: 32, desc: 1, len: 1, list: [{ uuid: CCCD, permission: 32 }] }
          ]
        }]
      }]
    })
  },

  linked() {
    this.state.linked = true
    this.show('подключено')
    this.state.packer = createPacker({ id: this.id, platform: 'zepp', send: (b) => this.send(b) })
    this.listen(true)
  },

  lost() {
    this.drop()
    if (!this.state.on) return
    this.show('связь потеряна — ищу')
    this.later(() => this.scan(), 2000)
  },

  drop() {
    this.listen(false)
    this.state.queue = []
    this.state.writing = false
    this.state.linked = false
    try {
      ble.mstOffAllCb()
      if (this.state.profile) ble.mstDestroyProfileInstance(this.state.profile)
      if (this.state.connectId >= 0) ble.mstDisconnect(this.state.connectId)
    } catch (e) {}
    this.state.profile = null
    this.state.connectId = -1
  },

  /** One byte from the phone. */
  heard(ev) {
    if (ev === EV_PAUSE) {
      this.listen(false)
      this.show('музыка не играет')
    } else if (ev === EV_STREAM) {
      if (this.state.packer) this.state.packer.restart()
      this.listen(true)
    } else if (ev === EV_NEXT || ev === EV_PREVIOUS) {
      buzz(VIBRATOR_SCENE_SHORT_STRONG)
    } else if (ev === EV_LEARNED) {
      buzz(VIBRATOR_SCENE_DURATION)
    }
  },

  // ---------------------------------------------------------------- the samples

  listen(on) {
    if (on === this.state.listening) return
    this.state.listening = on
    if (!on) {
      try {
        if (this.onAcc) acc.offChange(this.onAcc)
        acc.stop()
      } catch (e) {}
      return
    }
    this.show('слушаю повороты кисти')
    this.onAcc = (v) => {
      const s = v && v.x !== undefined ? v : acc.getCurrent()
      if (this.state.listening && this.state.packer) this.state.packer.sample(s.x, s.y, s.z, Date.now())
    }
    acc.onChange(this.onAcc)
    try {
      acc.setFreqMode(FREQ_MODE_HIGH)
    } catch (e) {}
    acc.start()
  },

  /** One write at a time; a slow link loses the oldest samples, not the newest. */
  send(buf) {
    this.state.queue.push(buf)
    while (this.state.queue.length > 20) this.state.queue.splice(1, 1)
    this.pump()
  },

  pump() {
    if (this.state.writing || !this.state.profile || this.state.queue.length === 0) return
    const buf = this.state.queue.shift()
    this.state.writing = true
    ble.mstWriteCharacteristic(this.state.profile, SAMPLES, buf, buf.byteLength)
  }
})
