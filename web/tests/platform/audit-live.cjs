/** 审计安全浏览器验收：新建隔离库、真实HTTP和页面，不向公司邮件或第三方站点发送消息。 */
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict"),
  crypto = require("node:crypto");
const { chromium, expect } = require("@playwright/test");
const config = JSON.parse(
  fs.readFileSync(process.env.IMS_PLATFORM_TEST_CONFIG, "utf8"),
);
assert.match(
  config.IMS_PLATFORM_DB_URL,
  /^jdbc:postgresql:\/\/127\.0\.0\.1:55464\/ims_platform_dev_[0-9]+$/,
);
const base = `https://127.0.0.1:${config.IMS_PLATFORM_PORT}`,
  out = path.resolve(process.env.IMS_AUDIT_TEST_OUTPUT),
  collection = "00000000-0000-0000-0000-000000000001";
fs.mkdirSync(out, { recursive: true });
const results = [],
  pageErrors = [],
  contexts = [],
  suffix = crypto.randomUUID().slice(0, 8),
  no = `WEB-AUDIT-${suffix}`,
  email = no.toLowerCase() + "@biolink.com",
  password = crypto.randomUUID();
let browser, activePage;
function passed(name) {
  results.push(name);
  console.log(name);
}
async function page() {
  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    viewport: { width: 1440, height: 1000 },
  });
  contexts.push(context);
  const p = await context.newPage();
  p.on("pageerror", (e) => pageErrors.push(e.message));
  await p.goto(base + "/platform.html");
  activePage = p;
  return p;
}
async function get(p, route) {
  const r = await p.request.get(base + "/api/v1" + route);
  assert.equal(r.status(), 200, route);
  return (await r.json()).data;
}
async function post(p, route, body, headers = {}) {
  const r = await p.request.post(base + "/api/v1" + route, {
    data: body,
    headers: { Origin: base, ...headers },
  });
  assert.ok(r.ok(), route + " " + r.status());
  return (await r.json()).data;
}
async function login(p, account, pass) {
  await p.getByLabel("工号或姓名", { exact: true }).fill(account);
  await p.getByLabel("登录密码", { exact: true }).fill(pass);
  await p.getByRole("button", { name: "登录工作台" }).click();
}
async function verify(p) {
  const d = p
    .getByRole("dialog")
    .filter({ has: p.getByRole("heading", { name: "验证本人身份" }) });
  await d.getByLabel("本人登录密码").fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await d.getByRole("button", { name: "确认并继续" }).click();
}
async function management(
  p,
  route,
  body,
  target,
  type,
  action,
  version,
  method = "POST",
) {
  const self = await get(p, "/auth/me"),
    headers = {
      "X-CSRF-Token": self.csrfToken,
      "X-Operation-Id": crypto.randomUUID(),
    };
  const proof = await post(
    p,
    "/auth/reauthentications",
    {
      password: config.IMS_PLATFORM_ADMIN_PASSWORD,
      purpose: "MANAGE",
      objectIds: [target],
      scopeItems: [{ objectType: type, objectId: target, actions: [action] }],
    },
    headers,
  );
  headers["X-Reauth-Token"] = proof.verificationToken;
  if (version) headers["X-Expected-Version"] = String(version);
  const r = await p.request.fetch(base + "/api/v1" + route, {
    method,
    data: body,
    headers: { Origin: base, ...headers },
  });
  assert.ok(r.ok(), route + " " + r.status());
  return (await r.json()).data;
}
async function goto(p, hash) {
  activePage = p;
  const url = base + "/platform.html#/" + hash;
  if (p.url() === url) await p.reload();
  else await p.goto(url);
}
async function screenshot(p, name) {
  await p.screenshot({ path: path.join(out, name + ".png"), fullPage: true });
}
/** 凭据从本次合成账号的私有本机收件中读取，不写入验收报告和截图。 */
async function temporary() {
  let secret;
  await expect
    .poll(
      () => {
        for (const file of fs
          .readdirSync(config.IMS_PLATFORM_MAILBOX_ROOT)
          .filter((f) => f.endsWith(".json"))) {
          const message = JSON.parse(
            fs.readFileSync(
              path.join(config.IMS_PLATFORM_MAILBOX_ROOT, file),
              "utf8",
            ),
          );
          if (message.to === email && message.temporaryPassword) {
            secret = message.temporaryPassword;
            return true;
          }
        }
        return false;
      },
      { timeout: 25000 },
    )
    .toBe(true);
  return secret;
}
/** 浏览器验收只连接明确隔离的新库；合成账号和报告不包含真实业务资料。 */
(async () => {
  try {
    browser = await chromium.launch({ channel: "msedge", headless: true });
    const admin = await page();
    await login(
      admin,
      config.IMS_PLATFORM_ADMIN_NO,
      config.IMS_PLATFORM_ADMIN_PASSWORD,
    );
    await expect(
      admin.getByRole("navigation", { name: "主导航" }),
    ).toBeVisible();
    const self = await get(admin, "/auth/me");
    assert.ok(self.actions.includes("PLATFORM.SECURITY_MANAGE"));
    assert.ok(self.actions.includes("DRIVE.AUDIT_READ"));
    assert.ok(!self.actions.includes("ARCHIVE.AUDIT_READ"));
    await management(
      admin,
      "/admin/users",
      {
        employeeNo: no,
        name: "审计浏览器验收-" + suffix,
        departmentId: self.user.departmentId,
        companyEmail: email,
        jobTitle: "验收员工",
        supervisorId: self.user.id,
        mobilePhone: "+86 13800000000",
      },
      collection,
      "COLLECTION",
      "CREATE_USER",
    );
    const employee = await page();
    await login(employee, no, await temporary());
    await expect(
      employee.getByRole("heading", { name: "设置你的密码" }),
    ).toBeVisible();
    await employee.getByLabel("设置新密码", { exact: true }).fill(password);
    await employee.getByLabel("再次输入新密码", { exact: true }).fill(password);
    await employee
      .getByRole("button", { name: "设置密码", exact: true })
      .click();
    await expect(
      employee.getByRole("heading", { name: "欢迎回来" }),
    ).toBeVisible();
    await login(employee, no, password);
    await expect(
      employee.getByRole("heading", { name: "我的待办", exact: true }),
    ).toBeVisible();
    await expect(
      employee.getByRole("link", { name: "安全管理", exact: true }),
    ).toHaveCount(0);
    for (let i = 0; i < 10; i++) {
      const r = await employee.request.get(base + "/api/v1/admin/security");
      assert.equal(r.status(), 403);
    }
    assert.equal(
      (
        await employee.request.get(base + "/api/v1/portal/announcements")
      ).status(),
      429,
    );
    passed("普通员工无管理入口，直接越权10次后告警并限流");
    await goto(admin, "security");
    await expect(
      admin.getByRole("heading", { name: "告警与日志保留" }),
    ).toBeVisible();
    const row = admin
      .getByRole("row")
      .filter({ hasText: "审计浏览器验收-" + suffix });
    await expect(row).toHaveCount(1);
    await row.getByRole("button").click();
    await admin.getByLabel("处理说明").fill("本机隔离浏览器核对完成 " + suffix);
    await admin.getByLabel(/已核对，立即解除/).check();
    await admin.getByLabel("核对结果").selectOption("RESOLVED");
    await admin.getByRole("button", { name: "保存处置" }).click();
    await verify(admin);
    await expect(admin.getByRole("button", { name: "保存处置" })).toHaveCount(
      0,
    );
    assert.equal(
      (
        await employee.request.get(base + "/api/v1/portal/announcements")
      ).status(),
      200,
    );
    const queue = await get(admin, "/admin/security?state=RESOLVED"),
      alert = queue.items.find(
        (a) => a.userName === "审计浏览器验收-" + suffix,
      );
    assert.ok(alert);
    const history = await get(admin, `/admin/security/${alert.id}/history`);
    assert.equal(history.length, 1);
    assert.equal(history[0].released, true);
    passed("IT真实再验证处置，解除限流，处理历史可核查");
    for (const width of [1100, 1280, 1440]) {
      await admin.setViewportSize({ width, height: 1000 });
      assert.ok(
        await admin.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      );
      await screenshot(admin, "security-" + width);
    }
    await goto(admin, "audit");
    await expect(
      admin.getByRole("heading", { name: "操作记录", exact: true }),
    ).toBeVisible();
    await expect(
      admin.getByRole("button", { name: "导出本次查询" }),
    ).toBeEnabled();
    await admin.getByLabel("开始日期").fill("");
    await expect(
      admin.getByRole("button", { name: "导出本次查询" }),
    ).toBeDisabled();
    await admin
      .getByLabel("开始日期")
      .fill(new Date(Date.now() - 86400000).toISOString().slice(0, 10));
    await admin.getByLabel("记录类型").selectOption("INTERACTION");
    await admin.getByRole("button", { name: "查询", exact: true }).click();
    await expect(
      admin.getByRole("button", { name: "导出本次查询" }),
    ).toBeEnabled();
    await expect(admin.getByRole("table")).toContainText("用户意图");
    const downloadPromise = admin.waitForEvent("download");
    await admin.getByRole("button", { name: "导出本次查询" }).click();
    await verify(admin);
    const download = await downloadPromise;
    const csvPath = path.join(out, "interaction-export.csv");
    await download.saveAs(csvPath);
    const csv = fs.readFileSync(csvPath, "utf8");
    assert.ok(csv.includes("INTENT"));
    assert.ok(!csv.includes(config.IMS_PLATFORM_ADMIN_PASSWORD));
    assert.ok(!csv.includes(password));
    await screenshot(admin, "audit-interactions");
    passed("操作意图查询与真实CSV导出，清空日期不崩溃，凭据不进入日志");
    const from = new Date(Date.now() - 3600000).toISOString(),
      to = new Date(Date.now() + 3600000).toISOString();
    const q = new URLSearchParams({
      module: "PLATFORM",
      kind: "INTERACTION",
      from,
      to,
      pageSize: "100",
    });
    const intents = await get(admin, "/admin/audit-events?" + q);
    const linked = intents.items.find((r) => r.action === "audit:SUBMIT");
    assert.ok(linked);
    const events = await get(
      admin,
      "/admin/audit-events?" +
        new URLSearchParams({
          module: "PLATFORM",
          from,
          to,
          requestId: linked.requestId,
          pageSize: "100",
        }),
    );
    assert.ok(events.items.some((r) => r.resourceType === "HTTP_REQUEST"));
    passed("界面操作与服务端实际响应使用同一请求号关联");
    for (const width of [1100, 1280, 1440]) {
      await admin.setViewportSize({ width, height: 1000 });
      assert.ok(
        await admin.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      );
      await screenshot(admin, "audit-" + width);
    }
    assert.deepEqual(pageErrors, []);
    passed("1100、1280、1440桌面视口无全页溢出，浏览器零脚本异常");
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify(
        { status: "passed", results, pageErrors, url: base + "/platform.html" },
        null,
        2,
      ),
    );
    // 便于用户打开本次新预览；密码仅写原有私有隔离目录，不进入仓库或报告。
    fs.writeFileSync(
      path.join(
        path.dirname(process.env.IMS_PLATFORM_TEST_CONFIG),
        "audit-preview-access.private.json",
      ),
      JSON.stringify(
        {
          url: base + "/platform.html",
          administrator: {
            employeeNo: config.IMS_PLATFORM_ADMIN_NO,
            password: config.IMS_PLATFORM_ADMIN_PASSWORD,
          },
          employee: { employeeNo: no, password },
        },
        null,
        2,
      ),
    );
  } catch (error) {
    if (activePage) await screenshot(activePage, "failure").catch(() => {});
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify(
        { status: "failed", results, pageErrors, error: error.message },
        null,
        2,
      ),
    );
    console.error(error.message);
    process.exitCode = 1;
  } finally {
    for (const context of contexts) await context.close();
    if (browser) await browser.close();
  }
})();
