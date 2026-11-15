import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";

/** 独立 UI 原型构建和预览；不配置业务 API 代理，不改写正式 web/dist。 */
export default defineConfig({
  plugins: [vue()], // 继续使用当前项目的 Vue 技术栈。
  server: {
    host: "127.0.0.1", // 仅供本机查看，不向公司网络发布原型。
    port: 5174, // 与正在运行的业务服务及常规开发端口分开。
    strictPort: true, // 端口冲突时明确失败，不悄悄切换预览地址。
  },
  build: {
    outDir: "../.dev/platform-preview", // 构建产物与业务程序分离，处于 Git 忽略范围。
    emptyOutDir: false, // 不自动删除工作区内已有文件。
    sourcemap: false, // 预览无需额外生成源映射文件。
    rollupOptions: {
      input: fileURLToPath(new URL("./platform-preview.html", import.meta.url)), // 只构建平台原型入口。
    },
  },
});
