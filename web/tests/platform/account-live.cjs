/** 电脑端账号自助专项；仅连接本批独立库，秘密只在私有配置、收件箱和内存中使用。 */
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
  /^jdbc:postgresql:\/\/127\.0\.0\.1:55461\/ims_platform_dev_[0-9]+$/,
);
const base = `https://127.0.0.1:${config.IMS_PLATFORM_PORT}`,
  out = path.resolve(process.env.IMS_ACCOUNT_TEST_OUTPUT);
fs.mkdirSync(out, { recursive: true });
const results = [],
  pageErrors = [],
  contexts = [];
const no = `WEB-${crypto.randomUUID().slice(0, 8)}`,
  email = no.toLowerCase() + "@biolink.com",
  employeePassword = crypto.randomUUID();
let browser;
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
  return p;
}
async function login(p, account, password) {
  await p.getByLabel("工号或姓名", { exact: true }).fill(account);
  await p.getByLabel("登录密码", { exact: true }).fill(password);
  await p.getByRole("button", { name: "登录工作台" }).click();
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
async function management(p, route, body, target, type, action, version) {
  const profile = await get(p, "/auth/me"),
    headers = {
      "X-CSRF-Token": profile.csrfToken,
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
  return post(p, route, body, headers);
}
/** 只读取本次合成员工的私有开发收件，不把凭据放入截图或报告。 */
async function delivered(field, exclude) {
  let value;
  await expect
    .poll(
      () => {
        const files = fs
          .readdirSync(config.IMS_PLATFORM_MAILBOX_ROOT)
          .filter((f) => f.endsWith(".json"))
          .map((f) => path.join(config.IMS_PLATFORM_MAILBOX_ROOT, f))
          .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
        for (const file of files) {
          const item = JSON.parse(fs.readFileSync(file, "utf8"));
          if (item.to === email && item[field] && item[field] !== exclude) {
            value = item[field];
            return true;
          }
        }
        return false;
      },
      { timeout: 25000 },
    )
    .toBe(true);
  return value;
}
async function verify(p) {
  const d = p
    .getByRole("dialog")
    .filter({ has: p.getByRole("heading", { name: "验证本人身份" }) });
  await d.getByLabel("本人登录密码").fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await d.getByRole("button", { name: "确认并继续" }).click();
}
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
      admin.getByRole("link", { name: "组织与人员", exact: true }),
    ).toBeVisible();
    const self = await get(admin, "/auth/me");
    await admin.locator(".profile-button").click();
    const card = admin.getByRole("dialog", { name: "个人信息" });
    await expect(card.getByText("公司", { exact: true })).toBeVisible();
    await expect(card.getByText(self.user.companyEmail, { exact: true })).toBeVisible();
    await card.getByRole("button", { name: "关闭个人信息" }).click();
    passed("个人中心显示真实部门、邮箱和角色");
    const user = await management(
      admin,
      "/admin/users",
      {
        employeeNo: no,
        name: "账号网页验收",
        departmentId: self.user.departmentId,
        companyEmail: email,
        jobTitle: "验收员工",
        supervisorId: self.user.id,
        mobilePhone: "+86 13800000000",
      },
      "00000000-0000-0000-0000-000000000001",
      "COLLECTION",
      "CREATE_USER",
    );
    const initial = await delivered("temporaryPassword");
    const employee = await page();
    await login(employee, no, initial);
    await expect(
      employee.getByRole("heading", { name: "设置你的密码" }),
    ).toBeVisible();
    await employee
      .getByLabel("设置新密码", { exact: true })
      .fill(employeePassword);
    await employee
      .getByLabel("再次输入新密码", { exact: true })
      .fill(employeePassword);
    await employee
      .getByRole("button", { name: "设置密码", exact: true })
      .click();
    await expect(
      employee.getByRole("heading", { name: "欢迎回来" }),
    ).toBeVisible();
    await login(employee, no, employeePassword);
    await expect(
      employee.getByRole("link", { name: "工作台", exact: true }),
    ).toBeVisible();
    passed("临时密码登录、首次设密和重新登录");
    const denied = await employee.request.post(
      base + `/api/v1/admin/users/${user.id}/password-reset`,
      { data: { reason: "越权测试" } },
    );
    assert.equal(denied.status(), 403);
    const forged = await employee.request.post(
      base + "/api/v1/auth/password-recovery",
      {
        headers: { Origin: "https://outside.invalid" },
        data: { employeeNo: no },
      },
    );
    assert.equal(forged.status(), 403);
    passed("员工越权重置和跨站找回请求被拒绝");
    await admin.getByRole("link", { name: "组织与人员", exact: true }).click();
    await admin.getByLabel("搜索姓名或工号").fill(no);
    await admin.getByRole("button", { name: "搜索", exact: true }).click();
    await admin
      .getByRole("row")
      .filter({ hasText: no })
      .getByRole("button", { name: "重置密码", exact: true })
      .click();
    const reset = admin
      .getByRole("dialog")
      .filter({ has: admin.getByRole("heading", { name: "重置员工密码" }) });
    await reset.getByLabel("重置原因").fill("本机隔离网页验收");
    await reset.getByRole("button", { name: "确认重置", exact: true }).click();
    await verify(admin);
    await expect(reset).not.toBeVisible();
    assert.equal(
      (await employee.request.get(base + "/api/v1/auth/me")).status(),
      401,
    );
    await delivered("temporaryPassword", initial);
    passed("管理员表单重置密码，员工旧会话立即失效");
    const recovery = await page();
    await recovery
      .getByRole("button", { name: "忘记密码？通过公司邮箱找回" })
      .click();
    await recovery.getByLabel("公司工号", { exact: true }).fill(no);
    await recovery
      .getByRole("button", { name: "发送验证码", exact: true })
      .click();
    await expect(
      recovery.getByLabel("邮箱验证码", { exact: true }),
    ).toBeVisible();
    await expect(
      recovery.getByRole("button", { name: /秒后可重发/ }),
    ).toBeDisabled();
    for (const width of [1440, 1280]) {
      await recovery.setViewportSize({ width, height: 1000 });
      assert.ok(
        await recovery.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      );
      await recovery.screenshot({
        path: path.join(out, `recovery-${width}.png`),
        fullPage: true,
      });
    }
    const code = await delivered("code"),
      newPassword = crypto.randomUUID();
    await recovery.getByLabel("邮箱验证码", { exact: true }).fill(code);
    await recovery
      .locator(".login-form-inner")
      .getByLabel("新密码", { exact: true })
      .fill(newPassword);
    await recovery
      .getByLabel("再次输入新密码", { exact: true })
      .fill(newPassword);
    await recovery.getByRole("button", { name: "设置密码并返回登录" }).click();
    await expect(
      recovery.getByRole("heading", { name: "欢迎回来" }),
    ).toBeVisible();
    await login(recovery, no, newPassword);
    await expect(
      recovery.getByRole("link", { name: "工作台", exact: true }),
    ).toBeVisible();
    passed("邮箱验证码找回全流程及1280/1440电脑布局");
    assert.deepEqual(
      await recovery.evaluate(() => ({
        local: localStorage.length,
        session: sessionStorage.length,
      })),
      { local: 0, session: 0 },
    );
    passed("浏览器存储未保存密码、验证码或登录令牌");
    await admin.getByRole("link", { name: "消息服务", exact: true }).click();
    await expect(
      admin.getByRole("heading", { name: "消息服务", exact: true }),
    ).toBeVisible();
    await expect(
      admin.getByText("本机隔离收件箱", { exact: true }),
    ).toBeVisible();
    await admin.screenshot({
      path: path.join(out, "message-service.png"),
      fullPage: true,
    });
    passed("IT消息服务展示真实通道和投递结果");
    assert.deepEqual(pageErrors, []);
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify({ passed: true, results, pageErrors }, null, 2),
    );
  } catch (error) {
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify(
        {
          passed: false,
          results,
          pageErrors,
          error: String(error.message).slice(0, 1200),
        },
        null,
        2,
      ),
    );
    throw error;
  } finally {
    for (const c of contexts) await c.close();
    if (browser) await browser.close();
  }
})();
