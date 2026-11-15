import { defineConfig } from "vitest/config";

/** 单元测试只读取浏览器逻辑，不连接数据库；端到端测试使用独立命令。 */
export default defineConfig({
  test: {
    include: ["tests/unit/**/*.test.ts"],
    environment: "node",
    clearMocks: true,
  },
});
