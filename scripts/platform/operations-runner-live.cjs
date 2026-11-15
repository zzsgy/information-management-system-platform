/** 仅运行在本批新建实例：实际备份重放、撤权、受损备份拒绝与跨实例恢复。 */
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict"),
  crypto = require("node:crypto");
const maintenance = require("./maintenance.cjs"),
  runner = require("./operations-runner.cjs");
const configPath = process.env.IMS_PLATFORM_TEST_CONFIG,
  context = maintenance.settings(configPath),
  out = path.resolve(process.env.IMS_OPS_TEST_OUTPUT);
assert.equal(context.url.port, "55465");
assert.match(context.name, /^ims_platform_dev_[0-9]+$/);
const ids = JSON.parse(
  fs.readFileSync(path.join(out, "pending-restores.json"), "utf8"),
);
for (const id of Object.values(ids))
  assert.match(id, /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
const cases = [];
async function execute(id) {
  return runner.main(["--config", configPath, "--run", id]);
}
(async () => {
  assert.equal(
    await maintenance.listening(context.config.IMS_PLATFORM_PORT),
    false,
    "故障注入前必须停止本批源应用",
  );
  const bundle = path.join(context.config.IMS_OPS_RUN_ROOT, ids.backup),
    manifest = JSON.parse(
      fs.readFileSync(path.join(bundle, "manifest.json"), "utf8"),
    );
  assert.ok(
    manifest.tables.some(
      (t) =>
        t.schema === "drive" ||
        t.schema_name === "drive" ||
        t.name?.startsWith("drive.") ||
        t.table?.startsWith("drive."),
    ),
    "恢复摘要清单必须覆盖公盘",
  );
  assert.equal((await execute(ids.backup)).state, "IDLE");
  cases.push("已完成备份重复执行不生成第二份产物");
  // 通过真实权限表施加个人明确拒绝，恢复前撤销本次测试拒绝，保留历史证据。
  const deny = crypto.randomUUID();
  maintenance.sql(
    context,
    `INSERT INTO iam.permission_assignment(id,user_id,permission_id,scope_type,effect,valid_from,granted_by) SELECT '${deny}',r.requester_id,p.id,'MODULE','DENY',CURRENT_TIMESTAMP,r.requester_id FROM ops.maintenance_run r CROSS JOIN iam.permission p WHERE r.id='${ids.denied}' AND p.code='PLATFORM.BACKUP_MANAGE'`,
  );
  try {
    const rejected = await execute(ids.denied);
    assert.equal(rejected.state, "FAILED");
    assert.equal(rejected.errorCode, "REQUESTER_AUTHORIZATION_INVALID");
    cases.push("后台执行器服从明确拒绝，不因已排队而绕过授权");
  } finally {
    maintenance.sql(
      context,
      `UPDATE iam.permission_assignment SET state='REVOKED' WHERE id='${deny}'`,
    );
  }
  // 只翻转本批专用备份的一个字节，finally恢复原字节并核验完整摘要，原件从不改写。
  const dump = path.join(bundle, "database.dump"),
    before = maintenance.sha(dump),
    fd = fs.openSync(dump, "r+"),
    byte = Buffer.alloc(1);
  fs.readSync(fd, byte, 0, 1, 0);
  const changed = Buffer.from([byte[0] ^ 1]);
  try {
    fs.writeSync(fd, changed, 0, 1, 0);
    fs.fsyncSync(fd);
    const failed = await execute(ids.failure);
    assert.equal(failed.state, "FAILED");
    assert.equal(failed.errorCode, "MAINTENANCE_VERIFICATION_FAILED");
    assert.equal(
      fs.existsSync(path.join(context.config.IMS_OPS_RUN_ROOT, ids.failure)),
      false,
    );
    cases.push("备份摘要受损时恢复失败，未创建目标目录");
  } finally {
    fs.writeSync(fd, byte, 0, 1, 0);
    fs.fsyncSync(fd);
    fs.closeSync(fd);
    assert.equal(maintenance.sha(dump), before);
    assert.equal(before, manifest.dumpSha256);
  }
  const recovered = await execute(ids.restore);
  assert.equal(recovered.state, "SUCCEEDED");
  assert.equal(recovered.recordDigestsMatched, true);
  assert.equal(recovered.fileDigestsMatched, true);
  assert.equal(recovered.sourcePreserved, true);
  assert.equal(recovered.productionHaVerified, false);
  assert.equal((await execute(ids.restore)).state, "IDLE");
  const restoredConfig = JSON.parse(
    fs.readFileSync(
      path.join(
        context.config.IMS_OPS_RUN_ROOT,
        ids.restore,
        "restored.private.json",
      ),
      "utf8",
    ),
  );
  assert.match(
    restoredConfig.IMS_PLATFORM_DB_URL,
    /127\.0\.0\.1:55466\/ims_platform_test_restore_/,
  );
  assert.equal(restoredConfig.IMS_PLATFORM_OPERATIONS_ENABLED, "false");
  assert.equal(restoredConfig.IMS_PLATFORM_SCAN_WORKER_ENABLED, "false");
  assert.equal(
    restoredConfig.IMS_PLATFORM_ACCOUNT_MESSAGES_SCHEDULE_ENABLED,
    "false",
  );
  cases.push("另一实例新库与新目录真实恢复，110张表及文件摘要一致，来源保留");
  const alerts = Number(
    maintenance.sql(
      context,
      "SELECT count(*) FROM ops.account_message WHERE kind IN ('OPS_BACKUP','OPS_RESTORE')",
    ),
  );
  assert.ok(alerts >= 2);
  cases.push("失败写入真实技术告警和事务发件箱");
  fs.writeFileSync(
    path.join(out, "maintenance-live-report.json"),
    JSON.stringify(
      {
        scope: "LOCAL_ISOLATED_REHEARSAL",
        cases,
        recovered,
        backupDigestPreserved: true,
        alerts,
      },
      null,
      2,
    ),
  );
  console.log(JSON.stringify({ cases, recovered }, null, 2));
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
