// Собирает одностраничное превью без сервера: preview/index.html.
// Игра запускается в одиночном режиме с демо-ботами. three.js подтягивается с CDN (jsdelivr) через import map,
// поэтому в файле остаётся только наш код. Нужно для публикации как Artifact и для быстрого показа.
//
//   npm run build:preview

import { build } from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const three = pkg.dependencies.three;

const result = await build({
  entryPoints: [path.join(root, 'src/main.js')],
  bundle: true,
  format: 'esm',
  platform: 'browser',
  target: 'es2020',
  write: false,
  external: ['three', 'three/addons/*'],
  legalComments: 'none',
  logLevel: 'warning',
});

// закрывающий тег скрипта внутри строки сломал бы страницу
const js = result.outputFiles[0].text.replace(/<\/script/gi, '<\\/script');
const css = fs.readFileSync(path.join(root, 'src/style.css'), 'utf8');
let html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');

const importMap = {
  imports: {
    three: `https://cdn.jsdelivr.net/npm/three@${three}/build/three.module.js`,
    'three/addons/': `https://cdn.jsdelivr.net/npm/three@${three}/examples/jsm/`,
  },
};

const linkTag = '<link rel="stylesheet" href="/src/style.css" />';
const scriptTag = '<script type="module" src="/src/main.js"></script>';
if (!html.includes(linkTag) || !html.includes(scriptTag)) throw new Error('index.html изменился: поправь замену в build-preview.mjs');

html = html
  .replace(linkTag, `<style>\n${css}\n</style>`)
  .replace(scriptTag, `<script>window.COZY_SOLO = true;</script>\n<script type="importmap">${JSON.stringify(importMap)}</script>\n<script type="module">\n${js}\n</script>`);

fs.mkdirSync(path.join(root, 'preview'), { recursive: true });
const out = path.join(root, 'preview/index.html');
fs.writeFileSync(out, html);
console.log(`preview/index.html: ${(html.length / 1024).toFixed(0)} КБ, three@${three} с CDN`);

// Вариант-фрагмент для Artifact: платформа сама оборачивает страницу в html/head/body,
// поэтому оставляем только title, шрифты, стили, разметку и скрипты.
const body = html.match(/<body>([\s\S]*)<\/body>/)[1];
const head = [
  '<title>Cozy World</title>',
  '<link rel="preconnect" href="https://fonts.googleapis.com" />',
  '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />',
  '<link href="https://fonts.googleapis.com/css2?family=Baloo+2:wght@500;700;800&display=swap" rel="stylesheet" />',
  `<style>\n${css}\n</style>`,
].join('\n');
const fragment = `${head}\n${body.trim()}\n`;
fs.writeFileSync(path.join(root, 'preview/artifact.html'), fragment);
console.log(`preview/artifact.html: ${(fragment.length / 1024).toFixed(0)} КБ (фрагмент для Artifact)`);
