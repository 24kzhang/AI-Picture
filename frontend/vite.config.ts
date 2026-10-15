import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  cacheDir: '.cache/vite',
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api/video-editor': { target: 'http://127.0.0.1:8091', changeOrigin: false, ws: true },
      '/api': { target: 'http://127.0.0.1:8090', changeOrigin: false, ws: true },
    },
  },
  plugins: [vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
