import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite 配置：base 设为 '/' 保证子路径下资源使用绝对路径；
// dev 服务器代理 /auth、/portal、/agent、/todos、/classes、/activities、/forms、
// /public、/integrations、/audit-logs、/accounts、/reminders、/tasks、/health、/static
// 到后端 Spring Boot :8080，实现同源会话 cookie。
export default defineConfig({
  plugins: [react()],
  base: '/',
  server: {
    port: 5173,
    proxy: {
      '/auth': 'http://127.0.0.1:8080',
      '/portal': 'http://127.0.0.1:8080',
      '/agent': 'http://127.0.0.1:8080',
      '/todos': 'http://127.0.0.1:8080',
      '/classes': 'http://127.0.0.1:8080',
      '/activities': 'http://127.0.0.1:8080',
      '/forms': 'http://127.0.0.1:8080',
      '/public': 'http://127.0.0.1:8080',
      '/integrations': 'http://127.0.0.1:8080',
      '/audit-logs': 'http://127.0.0.1:8080',
      '/accounts': 'http://127.0.0.1:8080',
      '/reminders': 'http://127.0.0.1:8080',
      '/tasks': 'http://127.0.0.1:8080',
      '/health': 'http://127.0.0.1:8080',
      '/static': 'http://127.0.0.1:8080',
      '/account': 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
