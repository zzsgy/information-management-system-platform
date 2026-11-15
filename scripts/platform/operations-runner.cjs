#!/usr/bin/env node
/** 独立维护执行器：只执行数据库中的固定备份/恢复任务，复用已验收的停机工具。 */
"use strict";
const fs = require("node:fs"),
  path = require("node:path"),
  crypto = require("node:crypto"),
  assert = require("node:assert/strict"),
  maintenance = require("./maintenance.cjs");
/** SQL值只来自受控记录或配置，统一转义；不拼接浏览器提交的SQL和命令。 */
const literal = (value) =>
  value === null ? "NULL" : "'" + String(value).replaceAll("'", "''") + "'";
function options(argv) {
  assert.ok(argv.length === 2 || argv.length === 4);
  assert.equal(argv[0], "--config");
  if (argv.length === 4) {
    assert.equal(argv[2], "--run");
    assert.match(argv[3], /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
  }
  return { config: argv[1], id: argv[3] };
}
function query(context, sql) {
  const result = maintenance.sql(context, sql);
  return result ? JSON.parse(result) : null;
}
/** 操作时重新检查当前账号代次和独立维护能力，不因任务已排队而忽略撤权。 */
function authorized(context, run) {
  const code =
    run.kind === "BACKUP"
      ? "PLATFORM.BACKUP_MANAGE"
      : "PLATFORM.RESTORE_VERIFY";
  return (
    maintenance.sql(
      context,
      `SELECT EXISTS(SELECT 1 FROM iam.user_account u JOIN iam.user_role ur ON ur.user_id=u.id JOIN iam.role r ON r.id=ur.role_id JOIN iam.role_permission rp ON rp.role_id=r.id JOIN iam.permission p ON p.id=rp.permission_id WHERE u.id=${literal(run.requester_id)}::uuid AND u.status='ACTIVE' AND NOT u.password_setup_required AND u.auth_version=${Number(run.auth_version)} AND ur.state='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP) AND r.status='ACTIVE' AND rp.scope_policy='COMPANY' AND p.module=r.module AND rp.effect='ALLOW' AND p.code=${literal(code)} AND NOT EXISTS(SELECT 1 FROM iam.user_role d JOIN iam.role dr ON dr.id=d.role_id JOIN iam.role_permission dp ON dp.role_id=dr.id WHERE d.user_id=u.id AND d.state='ACTIVE' AND d.valid_from<=CURRENT_TIMESTAMP AND (d.valid_until IS NULL OR d.valid_until>CURRENT_TIMESTAMP) AND dr.status='ACTIVE' AND dr.module=p.module AND dp.scope_policy='COMPANY' AND dp.permission_id=p.id AND dp.effect='DENY') AND NOT EXISTS(SELECT 1 FROM iam.permission_assignment pa WHERE pa.user_id=u.id AND pa.permission_id=p.id AND pa.state='ACTIVE' AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP) AND pa.effect='DENY'))`,
    ) === "t"
  );
}
function auditSql(run, result) {
  return `INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,record_kind,severity) VALUES(gen_random_uuid(),gen_random_uuid(),CURRENT_TIMESTAMP,${literal(run.requester_id)}::uuid,'PLATFORM','MAINTENANCE_RUN',${literal(run.id)}::uuid,'OPERATIONS_RESULT',${literal(result)},gen_random_uuid(),'BUSINESS_EVENT',${literal(result === "SUCCEEDED" ? "INFO" : "ERROR")});`;
}
/** 外部消息只有固定类别和受控入口；失败也保留原目录和任务，重试必须生成新记录。 */
function alertSql(run) {
  const category = run.kind === "BACKUP" ? "OPS_BACKUP" : "OPS_RESTORE";
  return `SELECT pg_advisory_xact_lock(hashtextextended(${literal(category)},61427041)); WITH fresh AS (INSERT INTO ops.security_alert(id,user_id,kind,observed_count,threshold_count) SELECT gen_random_uuid(),${literal(run.requester_id)}::uuid,${literal(category)},1,1 WHERE NOT EXISTS(SELECT 1 FROM ops.security_alert WHERE user_id=${literal(run.requester_id)}::uuid AND kind=${literal(category)} AND created_at>CURRENT_TIMESTAMP-interval '1 hour') RETURNING id,user_id,kind) INSERT INTO ops.account_message(id,user_id,kind,channel,expires_at) SELECT id,user_id,kind,'DINGTALK',CURRENT_TIMESTAMP+interval '1 day' FROM fresh;`;
}
async function main(argv) {
  const args = options(argv),
    context = maintenance.settings(args.config),
    config = context.config;
  assert.equal(
    String(config.IMS_PLATFORM_OPERATIONS_ENABLED),
    "true",
    "本环境未启用运维执行器",
  );
  const root = maintenance.safe(config.IMS_OPS_RUN_ROOT);
  for (const source of Object.values(context.roots))
    assert.ok(
      !maintenance.inside(source, root) && !maintenance.inside(root, source),
      "维护输出不得与持久目录嵌套",
    );
  // 源服务在线时不领取、不改状态；页面明确显示等待停机维护窗口。
  if (await maintenance.listening(config.IMS_PLATFORM_PORT))
    return { state: "WAITING_WINDOW" };
  if (!fs.existsSync(root)) maintenance.privateDirectory(root);
  const lease = crypto.randomUUID(),
    run = query(
      context,
      `WITH selected AS (SELECT id FROM ops.maintenance_run WHERE state='WAITING_WINDOW' AND kind IN ('BACKUP','RESTORE_VERIFY') ${args.id ? `AND id=${literal(args.id)}::uuid` : ""} ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED),claimed AS (UPDATE ops.maintenance_run r SET state='RUNNING',started_at=CURRENT_TIMESTAMP,lease_token=${literal(lease)}::uuid,lease_until=CURRENT_TIMESTAMP+interval '24 hours',row_version=row_version+1 FROM selected s WHERE r.id=s.id RETURNING r.*) SELECT row_to_json(c) FROM claimed c`,
    );
  if (!run) return { state: "IDLE" };
  try {
    assert.ok(authorized(context, run), "维护授权已失效");
    const output = path.join(root, run.id);
    let report,
      extra = "";
    if (run.kind === "BACKUP") {
      report = await maintenance.main([
        "backup",
        "--config",
        args.config,
        "--output",
        output,
      ]);
      assert.equal(report.state, "VERIFIED_BACKUP");
      const manifest = JSON.parse(
        fs.readFileSync(path.join(output, "manifest.json"), "utf8"),
      );
      assert.equal(
        manifest.instance,
        maintenance.sql(context, "SELECT id FROM platform_meta.instance"),
      );
      extra = `INSERT INTO ops.backup_point(id,verified_at,retain_until,dump_sha256,table_count,object_count,file_count) SELECT ${literal(run.id)}::uuid,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+retention_days*interval '1 day',${literal(report.dumpSha256)},${Number(report.tables)},${Number(report.originalObjects)},${Number(report.files)} FROM ops.backup_plan;`;
    } else {
      const backup = query(
        context,
        `SELECT row_to_json(b) FROM ops.backup_point b WHERE id=${literal(run.source_backup_id)}::uuid`,
      );
      assert.ok(backup);
      const bundle = path.join(root, backup.id),
        manifest = JSON.parse(
          fs.readFileSync(path.join(bundle, "manifest.json"), "utf8"),
        );
      assert.equal(manifest.dumpSha256, backup.dump_sha256);
      assert.equal(
        maintenance.sha(path.join(bundle, "database.dump")),
        backup.dump_sha256,
      );
      const port = Number(config.IMS_OPS_RESTORE_PORT);
      assert.ok(
        Number.isInteger(port) &&
          port > 1024 &&
          port < 65536 &&
          port !== Number(config.IMS_PLATFORM_PORT),
      );
      report = await maintenance.main([
        "restore-verify",
        "--config",
        args.config,
        "--bundle",
        bundle,
        "--output",
        output,
        "--database",
        "ims_platform_test_restore_" + run.id.replaceAll("-", ""),
        "--port",
        String(port),
        "--current-ledger",
        context.roots.ledger,
      ]);
      assert.equal(report.recordDigestsMatched, true);
      assert.equal(report.fileDigestsMatched, true);
      assert.equal(report.sourcePreserved, true);
    }
    // 只保存可公开的技术结论，完整私有报告继续留在维护目录；不自动切换入口。
    const summary = {
      scope: "LOCAL_ISOLATED_REHEARSAL",
      tables: report.tables,
      originalObjects: report.originalObjects,
      files: report.files,
      recordDigestsMatched: report.recordDigestsMatched,
      fileDigestsMatched: report.fileDigestsMatched,
      sourcePreserved: true,
      productionHaVerified: false,
      businessReviewRequired: run.kind === "RESTORE_VERIFY",
    };
    maintenance.sql(
      context,
      `BEGIN; DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM ops.maintenance_run WHERE id=${literal(run.id)}::uuid AND lease_token=${literal(lease)}::uuid AND state='RUNNING' FOR UPDATE) THEN RAISE EXCEPTION 'maintenance lease lost'; END IF; END $$; ${extra} UPDATE ops.maintenance_run SET state='SUCCEEDED',summary=${literal(JSON.stringify(summary))}::jsonb,finished_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,row_version=row_version+1 WHERE id=${literal(run.id)}::uuid AND lease_token=${literal(lease)}::uuid; ${auditSql(run, "SUCCEEDED")} COMMIT;`,
    );
    return { id: run.id, kind: run.kind, state: "SUCCEEDED", ...summary };
  } catch (error) {
    const code = authorized(context, run)
      ? "MAINTENANCE_VERIFICATION_FAILED"
      : "REQUESTER_AUTHORIZATION_INVALID";
    maintenance.sql(
      context,
      `BEGIN; UPDATE ops.maintenance_run SET state='FAILED',error_code=${literal(code)},finished_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,row_version=row_version+1 WHERE id=${literal(run.id)}::uuid AND lease_token=${literal(lease)}::uuid; ${auditSql(run, "FAILED")} ${alertSql(run)} COMMIT;`,
    );
    return { id: run.id, kind: run.kind, state: "FAILED", errorCode: code };
  }
}
if (require.main === module)
  main(process.argv.slice(2))
    .then((report) => {
      process.stdout.write(JSON.stringify(report, null, 2) + "\n");
      if (report.state === "FAILED") process.exitCode = 1;
    })
    .catch(() => {
      console.error(
        "维护执行未通过。请核对本机隔离配置、停机窗口和独立恢复目标；原件及已有目录均保留。",
      );
      process.exitCode = 1;
    });
module.exports = { main, options };
