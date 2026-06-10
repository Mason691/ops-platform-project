import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig(({ mode }) => ({
  // dev 模式用 /，生产构建用 /subsystem/ops/（与 qiankun 激活路由一致）
  base: mode === 'production' ? '/subsystem/ops/' : '/',
  plugins: [uni()],
  server: {
    host: '0.0.0.0',
    port: 3035,
    // Vite 5.4+ 安全校验：允许外部域名访问 dev server
    allowedHosts: ['a.15891589.xyz', 'localhost', '127.0.0.1'],
    cors: true,
    headers: { 'Access-Control-Allow-Origin': '*' },
    proxy: {
      '/api': { target: 'http://localhost:8084', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8084', changeOrigin: true }
    }
  }
}))
