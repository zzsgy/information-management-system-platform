/** 维护工具安全边界测试：不连接任何数据库，不覆盖已有目录。 */
const test = require("node:test"),
  assert = require("node:assert/strict"),
  fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path");
const tool = require("./maintenance.cjs"),
  root = fs.mkdtempSync(path.join(os.tmpdir(), "ims-maintenance-boundaries-"));
/** 仅合成配置，字符串口令不对应任何账号。 */
function config(changes = {}) {
  const file = path.join(
    root,
    "config-" + Math.random().toString(16).slice(2) + ".json",
  );
  fs.writeFileSync(
    file,
    JSON.stringify({
      IMS_PLATFORM_DB_URL:
        "jdbc:postgresql://127.0.0.1:55444/ims_platform_test_bounds",
      IMS_PLATFORM_DB_USER: "synthetic",
      IMS_PLATFORM_DB_PASSWORD: "not-a-real-password",
      IMS_PLATFORM_PORT: "18450",
      IMS_BACKUP_POSTGRES_BIN: root,
      IMS_PLATFORM_STORAGE_PRIMARY: path.join(root, "primary"),
      IMS_PLATFORM_STORAGE_SECONDARY: path.join(root, "secondary"),
      IMS_PLATFORM_LEDGER_ROOT: path.join(root, "ledger"),
      ...changes,
    }),
  );
  return file;
}
test("拒绝远程或业务数据库边界", () => {
  assert.throws(() =>
    tool.settings(
      config({
        IMS_PLATFORM_DB_URL:
          "jdbc:postgresql://127.0.0.1:5432/company_business",
      }),
    ),
  );
  assert.throws(() =>
    tool.settings(
      config({
        IMS_PLATFORM_DB_URL:
          "jdbc:postgresql://192.0.2.5:5432/ims_platform_test_other",
      }),
    ),
  );
});
test("持久目录同名与互相嵌套均拒绝", () => {
  assert.throws(() =>
    tool.settings(
      config({ IMS_PLATFORM_STORAGE_SECONDARY: path.join(root, "primary") }),
    ),
  );
  assert.throws(() =>
    tool.settings(
      config({
        IMS_PLATFORM_STORAGE_SECONDARY: path.join(root, "primary", "nested"),
      }),
    ),
  );
});
test("日志归档双目录纳入备份，缺一侧或嵌套均拒绝", () => {
  const archive = {
    IMS_PLATFORM_AUDIT_ARCHIVE_PRIMARY: path.join(root, "auditArchiveA"),
    IMS_PLATFORM_AUDIT_ARCHIVE_SECONDARY: path.join(root, "auditArchiveB"),
  };
  const settings = tool.settings(config(archive));
  assert.equal(
    settings.roots.auditArchivePrimary,
    archive.IMS_PLATFORM_AUDIT_ARCHIVE_PRIMARY,
  );
  assert.equal(
    settings.roots.auditArchiveSecondary,
    archive.IMS_PLATFORM_AUDIT_ARCHIVE_SECONDARY,
  );
  assert.throws(() =>
    tool.settings(
      config({
        IMS_PLATFORM_AUDIT_ARCHIVE_PRIMARY:
          archive.IMS_PLATFORM_AUDIT_ARCHIVE_PRIMARY,
      }),
    ),
  );
  assert.throws(() =>
    tool.settings(
      config({
        ...archive,
        IMS_PLATFORM_AUDIT_ARCHIVE_SECONDARY: path.join(
          root,
          "primary",
          "archive",
        ),
      }),
    ),
  );
});
test("既有恢复目录保持原文件及摘要", () => {
  const directory = path.join(root, "existing");
  fs.mkdirSync(directory);
  const file = path.join(directory, "original.txt");
  fs.writeFileSync(file, "必须保留的原文件");
  const before = tool.sha(file);
  assert.throws(() => tool.privateDirectory(directory));
  assert.equal(tool.sha(file), before);
});
test("符号链接或junction不能作为恢复路径", () => {
  const target = path.join(root, "target"),
    link = path.join(root, "link");
  fs.mkdirSync(target);
  fs.symlinkSync(
    target,
    link,
    process.platform === "win32" ? "junction" : "dir",
  );
  assert.throws(() => tool.safe(path.join(link, "new-output")));
  fs.unlinkSync(link);
  assert.ok(fs.existsSync(target));
});
test("未知和重复参数不能进入维护操作", () => {
  assert.throws(() =>
    tool.options(["backup", "--config", "a", "--shell", "anything"]),
  );
  assert.throws(() =>
    tool.options(["backup", "--config", "a", "--config", "b"]),
  );
  assert.throws(() => tool.options(["drop", "--config", "a"]));
});
test("合法隔离配置通过并保留独立持久根", () => {
  const current = tool.settings(config());
  assert.equal(current.url.hostname, "127.0.0.1");
  assert.equal(current.url.port, "55444");
  assert.notEqual(current.roots.primary, current.roots.secondary);
});
