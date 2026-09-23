import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [
    react(),
  ],
  base: '/',    
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 3000,
    host: '0.0.0.0'
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // Keep Vite's production defaults: esbuild minification and Rollup tree
    // shaking. Disabling both made every route (including xterm) ship in the
    // initial dashboard bundle.
    minify: 'esbuild',
    rollupOptions: {
      treeshake: true,
    }
  }
});
