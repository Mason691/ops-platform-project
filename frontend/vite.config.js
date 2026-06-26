import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig(({ mode }) => ({
  // VITE_BASE env var allows overriding at build time (e.g. /ops/ for Docker).
  // Falls back to /ops/ for production and / for local dev.
  base: process.env.VITE_BASE || (mode === 'production' ? '/ops/' : '/'),
  plugins: [uni()],
  server: {
    host: '0.0.0.0',
    port: 3035,
    // Vite 5.4+ 安全校验：允许外部域名访问 dev server
    allowedHosts: ['boss.jdzn99.com', 'a.15891589.xyz', 'localhost', '127.0.0.1'],
    cors: true,
    headers: { 'Access-Control-Allow-Origin': '*' },
    proxy: {
      '/api': { target: 'http://localhost:8084', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8084', changeOrigin: true }
    }
  }
}))
