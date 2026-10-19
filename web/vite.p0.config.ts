import { defineConfig } from 'vite';
export default defineConfig({
  server: {
    host: '127.0.0.1',
    port: 4181,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:4180', changeOrigin: false } },
  },
});
