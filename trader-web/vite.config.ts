import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    // Build output goes straight into the jar's static directory: the app port serves the page, same origin, no CORS
    // needed. The directory is gitignored.
    outDir: '../trader-app/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    // Port allocation: Vite on 5174 (5173 is taken by another project); API calls are proxied to the local dev instance on 8083
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8083',
      '/actuator': 'http://127.0.0.1:8083',
    },
  },
})
