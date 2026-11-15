import { defineConfig } from "@playwright/test";
import { readFileSync } from "node:fs";

/** 浏览器验收必须显式指定隔离配置，不回退到任何旧业务地址。 */
const path = process.env.IMS_PLATFORM_TEST_CONFIG;
if (!path || process.env.IMS_PLATFORM_ALLOW_TEST_WRITES !== "yes")
  throw new Error("需要隔离平台配置与显式测试写入许可");
/** 读取凭据仅供测试，不启用包含认证请求的录制。 */
const config = JSON.parse(readFileSync(path, "utf8"));
if (
  !/^jdbc:postgresql:\/\/127\.0\.0\.1:\d+\/ims_platform_(dev|test|trial)[a-z0-9_]*$/.test(
    config.IMS_PLATFORM_DB_URL,
  )
)
  throw new Error("测试目标不是本机独立平台库");
export default defineConfig({
  testDir: "tests/platform",
  workers: 1,
  fullyParallel: false,
  timeout: 90000,
  outputDir: "platform-test-results/artifacts",
  use: {
    baseURL: `https://127.0.0.1:${config.IMS_PLATFORM_PORT}`,
    channel: "msedge",
    headless: true,
    ignoreHTTPSErrors: true,
    trace: "off",
    screenshot: "only-on-failure",
    actionTimeout: 10000,
    viewport: { width: 1440, height: 1000 },
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "platform-test-results/results.json" }],
  ],
});
