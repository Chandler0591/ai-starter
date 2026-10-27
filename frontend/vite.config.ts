/**
 * Vite 构建配置：Vue 插件、@ 路径别名，以及开发期把 /api 与 /actuator 代理到后端，避开 CORS。
 * 生产环境交给 Nginx/网关同域反代；SSE 依赖代理不缓冲响应，网关侧需关掉 buffering。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 代理目标写死 localhost:8080，与后端 server.port 一致；改端口时这里与 application.yml 要一起改
// （docker-compose 目前只编排 PostgreSQL，后端仍按本地进程 / jar 启动）
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
