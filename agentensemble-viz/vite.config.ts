import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
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
    // The dev server runs on a different port (5173 default) during development.
    // The /api routes are served by the standalone CLI (cli.js) on port 7328.
    // When running under the CLI, the static files are served directly; no proxy is needed.
    // Do NOT proxy /api back to localhost:7328 -- that would create a request loop
    // when the Vite dev server and the CLI server are on the same port.
    port: 5173,
  },
});
