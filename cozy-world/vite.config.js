import { defineConfig } from 'vite';
import { attachGameServer } from './server/game.js';

// В dev-режиме игровой WebSocket-сервер живёт внутри Vite: один процесс, ничего поднимать отдельно не нужно.
function gameServer() {
  return {
    name: 'cozy-game-server',
    configureServer(server) {
      if (server.httpServer) attachGameServer(server.httpServer);
    },
    configurePreviewServer(server) {
      attachGameServer(server.httpServer);
    },
  };
}

export default defineConfig({
  plugins: [gameServer()],
  server: { host: true, port: 5173 },
  build: { target: 'es2020', chunkSizeWarningLimit: 1500 },
});
