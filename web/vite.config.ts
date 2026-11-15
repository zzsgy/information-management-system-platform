import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

/** 开发代理只在本机生效；生产资源由 Java 同源提供，避免开放跨域认证。 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { "/api": "http://127.0.0.1:8000" },
  },
  build: { outDir: "dist", sourcemap: false, chunkSizeWarningLimit: 1600 },
});
