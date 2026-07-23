import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 빌드 산출물은 Spring static으로 — 앱과 같은 origin에서 /console 로 서빙 (CORS 없음)
export default defineConfig({
  plugins: [react()],
  base: '/console/',
  build: {
    outDir: '../src/main/resources/static/console',
    emptyOutDir: true,
  },
  server: {
    proxy: Object.fromEntries(
      ['/events', '/internal', '/users', '/auth', '/projects', '/leaderboards'].map((p) => [
        p,
        { target: 'http://localhost:8080', changeOrigin: true },
      ]),
    ),
  },
})
