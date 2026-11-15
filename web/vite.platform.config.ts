import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

/** 新平台独立入口和产物；不改写旧档案 dist，也不代理到旧业务服务。 */
export default defineConfig({
  plugins: [vue()],
  server: {
    host: "127.0.0.1",
    port: 5176,
    strictPort: true,
    // 本机后端使用独立自签证书；实际运行通过后端 HTTPS 同源访问。
    proxy: { "/api/v1": { target: "https://127.0.0.1:18443", secure: false } },
  },
  build: {
    outDir: "dist-platform",
    sourcemap: false,
    rolldownOptions: { input: ["platform.html", "platform-print.html"] },
  },
});
