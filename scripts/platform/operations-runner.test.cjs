/** 执行器边界使用真实监听端口，验证在线时不领取任务、不创建输出。 */
const test = require("node:test"),
  assert = require("node:assert/strict"),
  fs = require("node:fs"),
  path = require("node:path"),
  os = require("node:os"),
  net = require("node:net");
const runner = require("./operations-runner.cjs");
test("只接受固定配置和任务编号，拒绝浏览器式命令注入", () => {
  assert.throws(() =>
    runner.options(["--config", "x", "--run", "anything; drop"]),
  );
  assert.throws(() => runner.options(["--config", "x", "--shell", "command"]));
  assert.throws(() =>
    runner.options(["--config", "x", "--run", "a", "extra", "b"]),
  );
});
test("实际源端口在线时保留等待窗口状态", async () => {
  const server = net.createServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ims-ops-window-")),
    output = path.join(root, "outputs"),
    config = path.join(root, "config.json");
  fs.writeFileSync(
    config,
    JSON.stringify({
      IMS_PLATFORM_DB_URL:
        "jdbc:postgresql://127.0.0.1:55465/ims_platform_test_window",
      IMS_PLATFORM_DB_USER: "synthetic",
      IMS_PLATFORM_DB_PASSWORD: "not-a-real-password",
      IMS_PLATFORM_PORT: String(server.address().port),
      IMS_PLATFORM_OPERATIONS_ENABLED: "true",
      IMS_BACKUP_POSTGRES_BIN: root,
      IMS_PLATFORM_STORAGE_PRIMARY: path.join(root, "primary"),
      IMS_PLATFORM_STORAGE_SECONDARY: path.join(root, "secondary"),
      IMS_PLATFORM_LEDGER_ROOT: path.join(root, "ledger"),
      IMS_OPS_RUN_ROOT: output,
    }),
  );
  try {
    assert.deepEqual(await runner.main(["--config", config]), {
      state: "WAITING_WINDOW",
    });
    assert.equal(fs.existsSync(output), false);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
