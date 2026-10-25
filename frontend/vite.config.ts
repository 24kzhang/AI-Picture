import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_BASE_URL || 'http://127.0.0.1:8080'
  return {
  cacheDir: '.cache/vite',
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api/video-editor': { target: env.VIDEO_API_BASE_URL || 'http://127.0.0.1:8091', changeOrigin: false, ws: true },
      '/api': { target: apiTarget, changeOrigin: false, ws: true },
    },
  },
  plugins: [vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  }
})
