import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite 配置：base 设为 '/' 保证子路径下资源使用绝对路径；
// dev 服务器把 /auth、/portal、/agent、/todos、/classes、/activities、/forms、
// /public、/analytics 等前缀代理到后端 Spring Boot（默认 :8080，可用 API_TARGET 覆盖），
// 从而保持同源会话 cookie。
const target = process.env.API_TARGET || 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [react()],
  base: '/',
  server: {
    port: 5173,
    proxy: {
      '/participation/activities': target,
      '/business-templates': target,
      '/analytics': target,
      '/behavior': target,
      '/me/favorites': target,
      '/notifications': target,
      '/runs': target,
      '/knowledge/': target,
      '/auth': target,
      '/portal': target,
      '/agent': target,
      '/todos': target,
      '/classes': target,
      '/activities': target,
      '/forms': target,
      '/public': target,
      '/integrations': target,
      '/audit-logs': target,
      '/accounts': target,
      '/reminders': target,
      '/tasks': target,
      '/health': target,
      '/static': target,
      '/account': target,
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
