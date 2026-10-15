// Проверка одностраничного превью (preview/index.html): three.js с CDN подменяется локальным из node_modules.
// npm run build:preview && node tests/preview.mjs

import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';

const root = path.resolve('.');
const THREE_DIR = path.join(root, 'node_modules/three');
const html = fs.readFileSync(path.join(root, 'preview/index.html'));

const server = http.createServer((req, res) => { res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); res.end(html); });
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/`;
fs.mkdirSync('tests/out', { recursive: true });

const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 1100, height: 620 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error' && !/fonts\.g|ERR_FAILED|Failed to load resource/.test(m.text())) errors.push(m.text()); });
await page.route('**/*', (route) => {
  const u = route.request().url();
  const m = u.match(/^https:\/\/cdn\.jsdelivr\.net\/npm\/three@[^/]+\/(.+)$/);
  if (m) {
    const file = path.join(THREE_DIR, m[1]);
    if (fs.existsSync(file)) return route.fulfill({ body: fs.readFileSync(file), contentType: 'text/javascript' });
    return route.abort();
  }
  if (u.startsWith('https://')) return route.abort(); // шрифты и прочее внешнее
  return route.continue();
});

const results = [];
const check = (name, ok, extra = '') => { results.push(ok); console.log(ok ? 'ok  ' : 'FAIL', name, extra); };

await page.goto(url, { waitUntil: 'load' });
await page.waitForFunction(() => window.__cozy?.game && window.__cozy.frames >= 3, null, { timeout: 120000 });
check('превью загрузилось (меню)', await page.evaluate(() => window.__cozy.game.state === 'menu'));
await page.fill('#nick', 'Гость');
await page.click('#play');
await page.waitForFunction(() => window.__cozy.game.state === 'play', null, { timeout: 30000 });
await page.waitForFunction(() => window.__cozy.frames >= 12, null, { timeout: 60000 });
check('одиночный режим без ожидания сервера', await page.evaluate(() => window.__cozy.game.online === false));
check('демо-боты появились', (await page.evaluate(() => window.__cozy.game.remotes.size)) === 3);
await page.waitForTimeout(1500);
await page.evaluate(() => { document.getElementById('help').hidden = true; });
await page.screenshot({ path: 'tests/out/preview-play.png', timeout: 180000 }); // программный рендер под нагрузкой медленный
check('нет ошибок', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
server.close();
const bad = results.filter((r) => !r).length;
console.log(bad ? `${bad} проверок не прошло` : 'превью ок');
process.exit(bad ? 1 : 0);
