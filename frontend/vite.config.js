import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端开发代理：/chat /kb /confirm /actuator 转发到后端 Spring Boot（8080）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/chat': { target: 'http://localhost:8080', changeOrigin: true },
      '/kb': { target: 'http://localhost:8080', changeOrigin: true },
      '/confirm': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
