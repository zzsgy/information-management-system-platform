import { defineConfig } from "@playwright/test";

/** 浏览器测试仅允许专用本机测试端口，必须显式确认隔离数据环境。 */
const baseURL = process.env.IMS_E2E_URL ?? "http://127.0.0.1:18091";
if (
  baseURL !== "http://127.0.0.1:18091" ||
  process.env.IMS_E2E_ALLOW_WRITES !== "yes"
)
  throw new Error(
    "浏览器测试仅可在已初始化的隔离库18091端口运行，并设置 IMS_E2E_ALLOW_WRITES=yes",
  );
/** 使用本机Edge，不下载额外浏览器；关闭含请求凭据的跟踪文件。 */
export default defineConfig({
  testDir: "tests/e2e",
  workers: 1,
  fullyParallel: false,
  timeout: 90000,
  use: {
    baseURL,
    channel: "msedge",
    headless: true,
    actionTimeout: 15000,
    viewport: { width: 1440, height: 1000 },
    trace: "off",
    screenshot: "only-on-failure",
  },
  reporter: "list",
});
