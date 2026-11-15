/** 运维管理电脑端验收：真实HTTP、真实数据库与Edge，测试账号统一使用本机预览口令。 */
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict"),
  crypto = require("node:crypto");
const { chromium, expect } = require("@playwright/test");
const maintenance = require("../../../scripts/platform/maintenance.cjs");
const configPath = process.env.IMS_PLATFORM_TEST_CONFIG,
  config = JSON.parse(fs.readFileSync(configPath, "utf8"));
assert.match(
  config.IMS_PLATFORM_DB_URL,
  /^jdbc:postgresql:\/\/127\.0\.0\.1:55465\/ims_platform_dev_[0-9]+$/,
);
const out = path.resolve(process.env.IMS_OPS_TEST_OUTPUT),
  base = `https://127.0.0.1:${config.IMS_PLATFORM_PORT}`,
  collection = "00000000-0000-0000-0000-000000000001";
fs.mkdirSync(out, { recursive: true });
const results = [],
  errors = [],
  contexts = [];
let browser, active;
async function page() {
  const ctx = await browser.newContext({
    ignoreHTTPSErrors: true,
    viewport: { width: 1440, height: 1000 },
  });
  contexts.push(ctx);
  const p = await ctx.newPage();
  p.on("pageerror", (e) => errors.push(e.message));
  await p.goto(base + "/platform.html");
  active = p;
  return p;
}
async function login(p, no) {
  await p.getByLabel("公司工号", { exact: true }).fill(no);
  await p
    .getByLabel("登录密码", { exact: true })
    .fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await p.getByRole("button", { name: "登录工作台" }).click();
  await expect(p.getByRole("navigation", { name: "主导航" })).toBeVisible();
}
async function get(p, route) {
  const response = await p.request.get(base + "/api/v1" + route);
  assert.equal(response.status(), 200, route);
  return (await response.json()).data;
}
async function manage(
  p,
  route,
  body,
  id,
  type,
  action,
  version,
  method = "POST",
) {
  const self = await get(p, "/auth/me"),
    headers = {
      Origin: base,
      "X-CSRF-Token": self.csrfToken,
      "X-Operation-Id": crypto.randomUUID(),
    };
  const proof = await p.request.post(base + "/api/v1/auth/reauthentications", {
    headers,
    data: {
      password: config.IMS_PLATFORM_ADMIN_PASSWORD,
      purpose: "MANAGE",
      objectIds: [id],
      scopeItems: [{ objectType: type, objectId: id, actions: [action] }],
    },
  });
  assert.equal(proof.status(), 200);
  headers["X-Reauth-Token"] = (await proof.json()).data.verificationToken;
  if (version) headers["X-Expected-Version"] = String(version);
  const response = await p.request.fetch(base + "/api/v1" + route, {
    headers,
    method,
    data: body,
  });
  assert.equal(response.status(), 200, route + " " + (await response.text()));
  return (await response.json()).data;
}
async function verify(p) {
  const d = p
    .getByRole("dialog")
    .filter({ has: p.getByRole("heading", { name: "验证本人身份" }) });
  await d.getByLabel("本人登录密码").fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await d.getByRole("button", { name: "确认并继续" }).click();
}
function passed(name) {
  results.push(name);
  console.log(name);
}
async function create(p, kind, sourceBackupId) {
  return manage(
    p,
    "/admin/operations/runs",
    {
      kind,
      reason: "本机运维浏览器隔离验收",
      ...(sourceBackupId ? { sourceBackupId } : {}),
    },
    collection,
    "MAINTENANCE_RUN",
    "CREATE_MAINTENANCE",
  );
}
(async () => {
  try {
    browser = await chromium.launch({ channel: "msedge", headless: true });
    const admin = await page();
    await login(admin, config.IMS_PLATFORM_ADMIN_NO);
    await admin.locator(".portal-management-menu summary").click();
    await admin.getByRole("link", { name: "运维与保护", exact: true }).click();
    await expect(
      admin.getByRole("heading", { name: "运行状态与维护中心" }),
    ).toBeVisible();
    const phase = process.env.IMS_OPS_BROWSER_PHASE || "initial";
    if (phase === "initial") {
      // 关闭本轮测试遗留的等待请求，保留记录及审计，确保队列仍可操作。
      for (const state of ["WAITING_WINDOW", "QUEUED"]) {
        const pending = await get(
          admin,
          "/admin/operations/lists/runs?state=" + state,
        );
        for (const r of pending.items)
          await manage(
            admin,
            `/admin/operations/runs/${r.id}/cancel`,
            { reason: "集成验收完成，关闭合成排队记录" },
            r.id,
            "MAINTENANCE_RUN",
            "CANCEL_MAINTENANCE",
            r.rowVersion,
          );
      }
      const reason = "页面保护检查-" + crypto.randomUUID().slice(0, 8);
      await admin.getByLabel(/^任务类型/).selectOption("PROTECTION_CHECK");
      await admin.getByLabel("申请依据", { exact: true }).fill(reason);
      await admin
        .getByRole("button", { name: "提交申请", exact: true })
        .click();
      await verify(admin);
      let check;
      await expect
        .poll(
          async () => {
            const list = await get(admin, "/admin/operations/lists/runs");
            check = list.items.find((r) => r.reason === reason);
            return check?.state;
          },
          { timeout: 20000 },
        )
        .toBe("SUCCEEDED");
      const detail = await get(admin, "/admin/operations/runs/" + check.id);
      assert.equal(detail.summary.health, "DEGRADED");
      assert.equal(detail.summary.pdf, "VERIFIED");
      assert.equal(detail.summary.protection.ready, false);
      assert.ok(!JSON.stringify(detail).includes("D:/"));
      await admin
        .getByRole("button", { name: "刷新状态", exact: true })
        .click();
      await expect(admin.getByText("最近检查：发现未就绪项")).toBeVisible();
      passed("真实保护探针与PDF子进程；未配置项不伪报保护成功");
      const replica = await create(admin, "REPLICA_CHECK");
      await expect
        .poll(
          async () =>
            (await get(admin, "/admin/operations/runs/" + replica.id)).state,
          { timeout: 20000 },
        )
        .toBe("SUCCEEDED");
      await admin
        .getByRole("navigation", { name: "运维分类" })
        .getByRole("button", { name: "副本状态", exact: true })
        .click();
      await expect(
        admin.getByRole("cell", { name: "核验一致", exact: true }).first(),
      ).toBeVisible();
      passed("双副本后台检查与页面真实结果");
      await admin
        .getByRole("navigation", { name: "运维分类" })
        .getByRole("button", { name: "业务处理任务", exact: true })
        .click();
      await expect(
        admin.getByText(
          "文件检测、预览和导出任务继续由原业务入口核对权限后重试",
          { exact: false },
        ),
      ).toBeVisible();
      const waiting = await create(admin, "BACKUP");
      assert.equal(waiting.state, "WAITING_WINDOW");
      const cancelled = await manage(
        admin,
        `/admin/operations/runs/${waiting.id}/cancel`,
        { reason: "验证取消排队" },
        waiting.id,
        "MAINTENANCE_RUN",
        "CANCEL_MAINTENANCE",
        waiting.rowVersion,
      );
      assert.equal(cancelled.state, "CANCELLED");
      passed("维护排队和取消保留完整记录");
      await admin.getByLabel("保留天数", { exact: true }).fill("30");
      await admin
        .getByLabel("变更依据", { exact: true })
        .fill("确认本机默认保留期限");
      await admin.getByRole("button", { name: "保存计划" }).click();
      await verify(admin);
      await expect(admin.getByRole("dialog")).not.toBeVisible();
      assert.equal(
        (await get(admin, "/admin/operations")).backupPlan.retentionDays,
        30,
      );
      passed("备份计划本人再验证和版本更新");
      const db = maintenance.settings(configPath),
        employeeNo = maintenance.sql(
          db,
          "SELECT employee_no FROM iam.user_account u WHERE employee_no LIKE 'OPS-%' AND NOT EXISTS(SELECT 1 FROM iam.user_role WHERE user_id=u.id) LIMIT 1",
        );
      assert.ok(employeeNo);
      const staff = await page();
      await login(staff, employeeNo);
      assert.equal(
        await staff
          .getByRole("link", { name: "运维与保护", exact: true })
          .count(),
        0,
      );
      assert.equal(
        (await staff.request.get(base + "/api/v1/admin/operations")).status(),
        403,
      );
      passed("普通员工不可见入口且直接请求被拒绝");
      active = admin;
      await admin
        .getByRole("navigation", { name: "运维分类" })
        .getByRole("button", { name: "维护任务", exact: true })
        .click();
      for (const width of [1100, 1280, 1440]) {
        await admin.setViewportSize({ width, height: 1000 });
        assert.equal(
          await admin.evaluate(
            () => document.documentElement.scrollWidth > window.innerWidth,
          ),
          false,
        );
        await admin.screenshot({
          path: path.join(out, `operations-${width}.png`),
          fullPage: true,
        });
      }
      passed("1100/1280/1440电脑端布局无横向溢出");
      const backup = await create(admin, "BACKUP");
      fs.writeFileSync(
        path.join(out, "pending-backup.json"),
        JSON.stringify({ id: backup.id }),
      );
    } else if (phase === "queue-restore") {
      const backup = JSON.parse(
        fs.readFileSync(path.join(out, "pending-backup.json"), "utf8"),
      );
      assert.equal(
        (await get(admin, "/admin/operations/runs/" + backup.id)).state,
        "SUCCEEDED",
      );
      const restore = await create(admin, "RESTORE_VERIFY", backup.id);
      const failure = await create(admin, "RESTORE_VERIFY", backup.id);
      const denied = await create(admin, "BACKUP");
      fs.writeFileSync(
        path.join(out, "pending-restores.json"),
        JSON.stringify({
          restore: restore.id,
          failure: failure.id,
          denied: denied.id,
          backup: backup.id,
        }),
      );
      passed("已验证备份可用于独立恢复申请");
    } else {
      const ids = JSON.parse(
          fs.readFileSync(path.join(out, "pending-restores.json"), "utf8"),
        ),
        restore = await get(admin, "/admin/operations/runs/" + ids.restore),
        failure = await get(admin, "/admin/operations/runs/" + ids.failure);
      assert.equal(restore.state, "SUCCEEDED");
      assert.equal(restore.summary.recordDigestsMatched, true);
      assert.equal(restore.summary.fileDigestsMatched, true);
      assert.equal(failure.state, "FAILED");
      await admin
        .getByRole("navigation", { name: "运维分类" })
        .getByRole("button", { name: "独立备份", exact: true })
        .click();
      await expect(
        admin.locator("tbody tr").filter({ hasText: ids.backup.slice(0, 8) }),
      ).toBeVisible();
      await admin.screenshot({
        path: path.join(out, "backup-verified.png"),
        fullPage: true,
      });
      await admin
        .getByRole("navigation", { name: "运维分类" })
        .getByRole("button", { name: "维护任务", exact: true })
        .click();
      await admin
        .locator("tbody tr")
        .filter({ hasText: ids.restore.slice(0, 8) })
        .getByRole("button", { name: "查看详情" })
        .click();
      await expect(
        admin.getByText(
          "技术恢复核验通过，原入口未切换；请继续核对业务内容、附件对应和权限。",
        ),
      ).toBeVisible();
      await admin.screenshot({
        path: path.join(out, "restore-verified.png"),
        fullPage: true,
      });
      passed("实际备份与跨实例恢复结果已在页面核验，受损备份恢复被拒绝");
    }
    assert.deepEqual(errors, []);
    fs.writeFileSync(
      path.join(out, `browser-${phase}-report.json`),
      JSON.stringify(
        { scope: "LOCAL_ISOLATED_REHEARSAL", results, pageErrors: errors },
        null,
        2,
      ),
    );
  } catch (e) {
    console.error(e);
    if (active)
      await active
        .screenshot({ path: path.join(out, "failure.png"), fullPage: true })
        .catch(() => {});
    process.exitCode = 1;
  } finally {
    for (const ctx of contexts) await ctx.close();
    if (browser) await browser.close();
  }
})();
