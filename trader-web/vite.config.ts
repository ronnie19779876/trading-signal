import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    // 产物直接进 jar 的静态目录：访问应用端口就是页面，同源、不需要 CORS。该目录已 gitignore。
    outDir: '../trader-app/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    // 端口分配：Vite 5174（5173 已被其它项目占用），接口转发到本机开发实例 8083
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8083',
      '/actuator': 'http://127.0.0.1:8083',
    },
  },
})
