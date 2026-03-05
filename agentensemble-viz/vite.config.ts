import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom'],
          flow: ['@xyflow/react'],
          dagre: ['@dagrejs/dagre'],
        },
      },
    },
  },
  server: {
    port: 7329,
    proxy: {
      '/api': {
        target: 'http://localhost:7329',
        changeOrigin: false,
      },
    },
  },
});
