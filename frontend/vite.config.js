import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/api/catalog': { target: 'http://127.0.0.1:8081', changeOrigin: true },
      '/api/orders': { target: 'http://127.0.0.1:8082', changeOrigin: true },
      '/api/inventory': { target: 'http://127.0.0.1:8083', changeOrigin: true }
    }
  }
})
