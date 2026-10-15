// Продакшен-сервер: раздаёт собранный клиент из dist/ и держит игровой WebSocket на /ws.
// Запуск: npm run build && npm start   (PORT=3000 по умолчанию)

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { attachGameServer } from './game.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || '0.0.0.0';

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8', '.map': 'application/json',
};
const COMPRESSIBLE = new Set(['.html', '.js', '.css', '.json', '.svg', '.txt', '.map']);
const cache = new Map();

function load(file) {
  let e = cache.get(file);
  if (e) return e;
  const ext = path.extname(file);
  const buf = fs.readFileSync(file);
  e = { type: TYPES[ext] || 'application/octet-stream', buf, gz: COMPRESSIBLE.has(ext) ? zlib.gzipSync(buf, { level: 9 }) : null };
  cache.set(file, e);
  return e;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, ...game.stats() }));
    return;
  }
  let rel = decodeURIComponent(url.pathname);
  if (rel.endsWith('/')) rel += 'index.html';
  let file = path.join(ROOT, rel);
  if (!file.startsWith(ROOT)) { res.writeHead(403).end(); return; } // защита от ../
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(ROOT, 'index.html');
  try {
    const e = load(file);
    const headers = {
      'content-type': e.type,
      'x-content-type-options': 'nosniff',
      'cache-control': file.includes(`${path.sep}assets${path.sep}`) ? 'public, max-age=31536000, immutable' : 'no-cache',
    };
    const gz = e.gz && /\bgzip\b/.test(req.headers['accept-encoding'] || '');
    if (gz) headers['content-encoding'] = 'gzip';
    headers.vary = 'Accept-Encoding';
    res.writeHead(200, headers);
    res.end(req.method === 'HEAD' ? undefined : (gz ? e.gz : e.buf));
  } catch {
    res.writeHead(404).end('not found');
  }
});

const game = attachGameServer(server);
server.listen(PORT, HOST, () => console.log(`Cozy World: http://${HOST}:${PORT}  (ws: /ws)`));
