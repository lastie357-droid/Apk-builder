import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const backendModules = path.resolve(__dirname, 'node_modules');

export default defineConfig({
  plugins: [react()],
  root: path.resolve(__dirname, '../react-dashboard'),
  resolve: {
    alias: {
      'react/jsx-runtime': path.resolve(backendModules, 'react/jsx-runtime.js'),
      'react/jsx-dev-runtime': path.resolve(backendModules, 'react/jsx-dev-runtime.js'),
      'react': path.resolve(backendModules, 'react/index.js'),
      'react-dom/client': path.resolve(backendModules, 'react-dom/client.js'),
      'react-dom': path.resolve(backendModules, 'react-dom/index.js'),
    }
  },
  build: {
    outDir: path.resolve(__dirname, 'public'),
    emptyOutDir: true
  },
  server: {
    host: '0.0.0.0',
    port: 3000,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:5000',
        changeOrigin: true
      },
      '/ws': {
        target: 'ws://localhost:5000',
        ws: true,
        changeOrigin: true
      }
    }
  }
});
