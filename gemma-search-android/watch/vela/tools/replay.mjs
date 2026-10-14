#!/usr/bin/env node
// A watch log played back (Настройки → Часы → «Журнал для отладки», then «Отправить журнал часов»): the watch's own
// samples (the S lines, with the watch's times — batches as they came) through the very turn finder the watch runs
// (src/common/proto.js), and the phone's rules after it (big enough, the forearm's slope kept, which side) — what was
// found, what the watch actually sent (ROLL), what the phone pressed (PRESS), and what the finder saw but did not count
// and why (too small, not back in time, restless, the forearm upright…). Also the stream itself: its pace, its batches,
// its gaps, the compass.
// usage: node watch/vela/tools/replay.mjs wrist-log.txt [--all]   (--all: every near miss, not only the big ones)
import fs from 'fs'
import path from 'path'
import { pathToFileURL, fileURLToPath } from 'url'

const here = path.dirname(fileURLToPath(import.meta.url))
const P = await import(pathToFileURL(path.join(here, '..', 'src', 'common', 'proto.js')).href)

/** The log's lines: { at (phone ms), tag, text, kv }. */
export function parse(text) {
  const out = []
  for (const line of text.split('\n')) {
    const m = line.match(/^(\d{4}-\d\d-\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}) (\S+) ?(.*)$/)
    if (!m) continue
    const at = Date.UTC(+m[1].slice(0, 4), +m[1].slice(5, 7) - 1, +m[1].slice(8, 10), +m[2], +m[3], +m[4], +m[5])
    const kv = {}
    for (const p of m[7].matchAll(/(\w+)=(\S+)/g)) kv[p[1]] = p[2]
    out.push({ at, tag: m[6], text: m[7], kv, clock: m[2] + ':' + m[3] + ':' + m[4] + '.' + m[5] })
  }
  return out
}

/** The phone's rules for a turn (WristGesture.decide): what it would make of it. */
export function decide(t, turn, sign) {
  const twist = Math.abs(t.twist)
  if ((t.source !== P.SOURCE_COMPASS && t.slopeChange > Math.max(25, 0.6 * twist)) || twist > 150) return 'NONE (not-forearm)'
  if (twist < turn) return 'NONE (small, needs ' + turn + '°)'
  return t.twist * sign > 0 ? 'RIGHT → next' : 'LEFT → previous'
}

/** Plays the log back; the report as text. */
export function replay(text, all) {
  const lines = parse(text)
  const out = []
  const say = (s) => out.push(s)
  const samples = lines.filter((l) => l.tag === 'S' && l.kv.wt !== undefined)
  const settings = lines.filter((l) => l.tag === 'SETTINGS').pop()
  const turn = settings && settings.kv.turn ? +settings.kv.turn : 40
  const roll = lines.find((l) => l.tag === 'ROLL' && l.kv.sign)
  const sign = roll ? (roll.kv.sign === '-' ? -1 : 1) : settings && settings.kv.hand === 'right' ? -1 : 1
  say('журнал: ' + lines.length + ' строк, ' + samples.length + ' отсчётов датчика; порог ' + turn + '°, вправо = ' + (sign > 0 ? '+' : '−'))
  if (!samples.length) {
    say('отсчётов нет: журнал для отладки не был включён (или часы его не слали — версия часов ниже 1.6)')
  } else {
    // the stream: pace, batches, gaps, compass, gravity
    const wts = samples.map((l) => +l.kv.wt)
    const distinct = []
    let batch = 1
    const batches = {}
    for (let i = 1; i < wts.length; i++) {
      if (wts[i] === wts[i - 1]) batch++
      else {
        batches[batch] = (batches[batch] || 0) + 1
        batch = 1
        distinct.push(wts[i] - wts[i - 1])
      }
    }
    batches[batch] = (batches[batch] || 0) + 1
    const span = (wts[wts.length - 1] - wts[0]) / 1000
    const gaps = distinct.filter((d) => d > 300)
    const withH = samples.filter((l) => l.kv.h !== '-').length
    const g = samples.map((l) => Math.hypot(+l.kv.x, +l.kv.y, +l.kv.z))
    const gMean = g.reduce((a, b) => a + b, 0) / g.length
    say('поток: ' + span.toFixed(1) + ' с, ' + (samples.length / Math.max(span, 0.001)).toFixed(1) + ' отсчётов/с; пачки по '
      + Object.entries(batches).map(([k, v]) => k + '×' + v).join(', ') + '; разрывов >0,3 с: ' + gaps.length
      + (gaps.length ? ' (до ' + Math.max(...gaps) + ' мс)' : '') + '; компас в ' + Math.round((100 * withH) / samples.length)
      + '% отсчётов; |g| в среднем ' + gMean.toFixed(2))
  }
  // through the watch's own finder, the watch's times as they were
  const events = []
  let current = null
  const packer = P.createPacker({
    id: 1,
    platform: 'replay',
    turns: true,
    send() {},
    onFound(t) {
      events.push({ at: current.at, clock: current.clock, kind: 'found', text: 'найден поворот ' + t.twist.toFixed(1) + '° за '
        + Math.round(t.took) + ' мс (' + (t.source === P.SOURCE_COMPASS ? 'компас' : 'гравитация') + ', наклон ' + t.slope.toFixed(2)
        + ', Δнаклона ' + Math.round(t.slopeChange) + '°) → телефон: ' + decide(t, turn, sign) })
    },
    trace(e) {
      if (!all && Math.abs(e.peak) < 15) return
      events.push({ at: current.at, clock: current.clock, kind: 'miss', text: '  не поворот: ' + e.what + ' (' + e.ch + ') '
        + Number(e.peak).toFixed(1) + '° за ' + Math.round(e.took) + ' мс' + (e.calm !== undefined ? ', неспокойно ' + Number(e.calm).toFixed(1) + '°' : '') })
    }
  })
  for (const l of samples) {
    current = l
    const h = l.kv.h === '-' || l.kv.h === undefined ? NaN : (+l.kv.h * Math.PI) / 180
    packer.sample(+l.kv.x, +l.kv.y, +l.kv.z, +l.kv.wt, h)
  }
  // with what the watch sent and the phone did
  for (const l of lines) {
    if (l.tag === 'ROLL') events.push({ at: l.at, clock: l.clock, kind: 'roll', text: 'часы прислали ROLL: ' + l.text })
    else if (l.tag === 'PRESS') events.push({ at: l.at, clock: l.clock, kind: 'press', text: 'телефон нажал: ' + l.text })
    else if (l.tag === 'W' && !/^не поворот/.test(l.text)) events.push({ at: l.at, clock: l.clock, kind: 'watch', text: 'часы: ' + l.text })
    else if (['HELLO', 'CONN', 'MUSIC', 'CFG', 'DEBUG', 'OLD'].includes(l.tag)) events.push({ at: l.at, clock: l.clock, kind: 'link', text: l.tag + ' ' + l.text })
  }
  events.sort((a, b) => a.at - b.at)
  const found = events.filter((e) => e.kind === 'found').length
  const sent = events.filter((e) => e.kind === 'roll').length
  const pressed = events.filter((e) => e.kind === 'press' && /next|previous/.test(e.text)).length
  say('итог: найдено при проигрывании ' + found + ', часы прислали ' + sent + ', телефон нажал ' + pressed)
  say('')
  for (const e of events) say(e.clock + '  ' + e.text)
  return out.join('\n')
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const file = process.argv[2]
  if (!file) {
    console.error('usage: node replay.mjs wrist-log.txt [--all]')
    process.exit(2)
  }
  console.log(replay(fs.readFileSync(file, 'utf8'), process.argv.includes('--all')))
}
