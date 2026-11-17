/** 员工首页浏览器验收：新建隔离库、真实HTTP和页面，不向公司邮件或第三方站点发送消息。 */
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
  /^jdbc:postgresql:\/\/127\.0\.0\.1:55462\/ims_platform_dev_[0-9]+$/,
);
const base = `https://127.0.0.1:${config.IMS_PLATFORM_PORT}`,
  out = path.resolve(process.env.IMS_PORTAL_TEST_OUTPUT),
  collection = "00000000-0000-0000-0000-000000000001";
fs.mkdirSync(out, { recursive: true });
const results = [],
  pageErrors = [],
  contexts = [],
  suffix = crypto.randomUUID().slice(0, 8),
  no = `WEB-PORTAL-${suffix}`,
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
  await p.getByLabel("公司工号", { exact: true }).fill(account);
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
      admin
        .getByRole("navigation", { name: "主导航" })
        .getByRole("link", { name: "工作台", exact: true }),
    ).toBeVisible();
    const self = await get(admin, "/auth/me");
    const createdUser = await management(
      admin,
      "/admin/users",
      {
        employeeNo: no,
        name: "员工首页验收",
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
    const roles = await get(admin, "/admin/roles?module=DRIVE&pageSize=100");
    const staff = roles.items.find((r) => r.code === "STAFF");
    assert.ok(staff);
    await management(
      admin,
      `/admin/users/${createdUser.id}/roles`,
      {
        roles: [{ roleId: staff.id, departmentId: null }],
        reason: "员工首页隔离验收",
        approvalRef: "LOCAL-PORTAL-TEST",
      },
      createdUser.id,
      "USER",
      "ASSIGN_ROLES",
      1,
      "PUT",
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
    assert.equal(
      (
        await employee.request.get(base + "/api/v1/admin/portal/announcements")
      ).status(),
      403,
    );
    assert.equal(
      (
        await employee.request.post(base + "/api/v1/portal/feedback", {
          headers: { Origin: base },
          data: { category: "OTHER", content: "缺CSRF应拒绝" },
        })
      ).status(),
      403,
    );
    passed("真实账号登录、首次设密、员工管理越权与CSRF拒绝");
    await goto(admin, "portal-admin");
    await admin.getByRole("button", { name: "新建公告", exact: true }).click();
    const title = "关于员工工作台试用的通知 " + suffix;
    await admin.getByLabel("公告标题", { exact: true }).fill(title);
    await admin
      .getByLabel("正文", { exact: true })
      .fill(
        "各位同事：\n\n员工工作台已开放公告阅读、待办查看和意见反馈。请在试用过程中记录建议，帮助我们持续改进。\n\n<script>window.portalInjected=true</script>\n以上标记用于验证纯文本显示。\n\n特此通知。",
      );
    await admin.getByLabel(/^状态/).selectOption("PUBLISHED");
    await admin.getByLabel("置顶展示", { exact: true }).check();
    await admin.getByRole("button", { name: "保存公告", exact: true }).click();
    await verify(admin);
    await expect(admin.locator(".portal-letter-content h1")).toHaveText(title);
    const published = (
      await get(admin, "/admin/portal/announcements?pageSize=100")
    ).items.find((x) => x.title === title);
    assert.ok(published);
    await goto(employee, "home");
    await expect(
      employee.locator(".portal-home-notice").filter({ hasText: title }),
    ).toBeVisible();
    const row = employee
      .locator(".portal-home-notice")
      .filter({ hasText: title });
    await expect(row).toContainText(config.IMS_PLATFORM_ADMIN_NAME);
    await expect(row).toContainText("公司");
    await expect(row.locator("time")).toHaveText(
      /\d{4}\/\d{2}\/\d{2}.*\d{2}:\d{2}:\d{2}/,
    );
    await expect(
      employee.locator(".portal-site").filter({ hasText: "百林客" }),
    ).toHaveAttribute("href", "https://www.biolink.city/");
    await screenshot(employee, "employee-home-1440");
    for (const width of [1280, 1100]) {
      await employee.setViewportSize({ width, height: 1000 });
      assert.equal(
        await employee.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
        `桌面横向溢出 ${width}`,
      );
      await screenshot(employee, `employee-home-${width}`);
    }
    await employee.setViewportSize({ width: 1440, height: 1000 });
    await row.click();
    await expect(employee.locator(".portal-letter-content h1")).toHaveText(
      title,
    );
    assert.equal(
      await employee.evaluate(() => window.portalInjected),
      undefined,
    );
    await expect(employee.locator(".portal-letter-body")).toContainText(
      "<script>",
    );
    await screenshot(employee, "announcement-letterhead");
    passed("公告真实发布、完整元数据、信纸详情、纯文本安全与三档桌面布局");
    await goto(employee, "feedback");
    await employee
      .getByRole("button", { name: "提交反馈", exact: true })
      .click();
    await employee.getByLabel(/^反馈类型/).selectOption("ISSUE");
    await employee
      .getByLabel("反馈内容", { exact: true })
      .fill("希望待办状态更清楚，隔离验收 " + suffix);
    await employee.getByRole("button", { name: "提交", exact: true }).click();
    await expect(
      employee.getByText("反馈已收到，等待 IT 处理。"),
    ).toBeVisible();
    const feedback = (await get(employee, "/portal/feedback")).items[0];
    await goto(admin, `portal-admin?tab=feedback&id=${feedback.id}`);
    await expect(admin.locator(".portal-plain-text").first()).toContainText(
      suffix,
    );
    await admin
      .getByLabel("回复员工", { exact: true })
      .fill("已核对并完成处理，谢谢反馈。");
    await admin.getByRole("button", { name: "发送回复", exact: true }).click();
    await verify(admin);
    await expect(
      admin.getByText("已核对并完成处理，谢谢反馈。", { exact: true }),
    ).toBeVisible();
    await screenshot(admin, "feedback-managed");
    await goto(employee, "home");
    await expect(employee.locator(".portal-message-card")).toContainText(
      "1 条未读消息",
    );
    await employee.locator(".portal-message-card").click();
    await expect(
      employee.getByRole("button").filter({ hasText: "你的意见反馈有了回复" }),
    ).toBeVisible();
    await employee
      .getByRole("button")
      .filter({ hasText: "你的意见反馈有了回复" })
      .click();
    await expect(
      employee.getByText("已核对并完成处理，谢谢反馈。", { exact: true }),
    ).toBeVisible();
    await expect(
      employee.locator(".chip").filter({ hasText: "已办结" }),
    ).toBeVisible();
    assert.equal(
      (await get(employee, "/portal/inbox?unreadOnly=true")).total,
      0,
    );
    passed("员工反馈、IT办理、本人未读消息、已读及详情跳转闭环");
    await goto(admin, "portal-admin");
    await admin.getByRole("button", { name: "常用网站", exact: true }).click();
    await admin.getByRole("button", { name: "添加网站", exact: true }).click();
    await admin
      .getByLabel("网站名称", { exact: true })
      .fill("停用链接验收 " + suffix);
    await admin
      .getByLabel("完整网址", { exact: true })
      .fill("https://www.biolink.city/");
    await admin.getByLabel("启用", { exact: true }).uncheck();
    await admin.getByRole("button", { name: "保存网站", exact: true }).click();
    await verify(admin);
    await expect(
      admin.getByText("停用链接验收 " + suffix, { exact: true }),
    ).toBeVisible();
    assert.ok(
      !(await get(employee, "/portal/sites")).items.some((s) =>
        s.name.includes(suffix),
      ),
    );
    passed("IT配置网站、停用配置不进入员工首页");
    await goto(employee, `announcements?id=${published.id}`);
    await expect(employee.locator(".portal-letter-content h1")).toHaveText(
      title,
    );
    const current = await get(
        admin,
        `/admin/portal/announcements/${published.id}`,
      ),
      body = {};
    for (const key of [
      "title",
      "category",
      "body",
      "state",
      "scope",
      "validFrom",
      "validUntil",
      "pinned",
      "departmentIds",
    ])
      body[key] = current[key];
    body.state = "WITHDRAWN";
    await management(
      admin,
      `/admin/portal/announcements/${published.id}`,
      body,
      published.id,
      "PORTAL_ANNOUNCEMENT",
      "SAVE_ANNOUNCEMENT",
      current.rowVersion,
      "PUT",
    );
    await employee.evaluate(() => window.dispatchEvent(new Event("focus")));
    await expect(employee.locator(".portal-letterhead")).toHaveCount(0);
    assert.equal(
      (
        await employee.request.get(
          base + `/api/v1/portal/announcements/${published.id}`,
        )
      ).status(),
      404,
    );
    passed("公告撤下后详情重新鉴权并清除已展示正文");
    await goto(employee, "home");
    const searchResult = employee.waitForResponse(
      (r) =>
        r.url().includes("/api/v1/drive/documents?") &&
        new URL(r.url()).searchParams.get("keyword") === "隔离搜索 & 验收",
    );
    await employee
      .getByLabel("搜索关键词", { exact: true })
      .fill("隔离搜索 & 验收");
    await employee
      .locator(".portal-search")
      .getByRole("button", { name: "搜索", exact: true })
      .click();
    assert.equal((await searchResult).status(), 200);
    await expect(employee).toHaveURL(/#\/drive\?keyword=/);
    await goto(admin, "todos");
    await admin
      .locator(".portal-list-row")
      .filter({ hasText: "处理员工反馈" })
      .first()
      .click();
    await expect(
      admin.getByRole("heading", { name: "反馈办理", exact: true }),
    ).toBeVisible();
    await admin.locator(".portal-management-menu summary").click();
    await expect(
      admin
        .getByRole("navigation", { name: "管理中心" })
        .getByRole("link", { name: "首页管理", exact: true }),
    ).toBeVisible();
    await admin.setViewportSize({ width: 1100, height: 1000 });
    assert.equal(
      await admin.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await screenshot(admin, "management-menu-1100");
    await admin.locator(".portal-management-menu summary").click();
    await admin.setViewportSize({ width: 1440, height: 1000 });
    passed("首页搜索复用原授权查询、待办直达反馈及管理菜单窄桌面验证");
    const longText =
      Array.from(
        { length: 80 },
        (_, i) =>
          `第${i + 1}段：本机隔离验证长公告的段落换行与连续阅读，正文应当完整展示，不能被固定纸张高度裁掉。`,
      ).join("\n\n") + "\n最后一行完整保留。";
    const longBody = {
      title: "长公告排版验收 " + suffix,
      category: "通知公告",
      body: longText,
      state: "PUBLISHED",
      scope: "COMPANY",
      departmentIds: [],
      validFrom: new Date(Date.now() - 5000).toISOString(),
      validUntil: null,
      pinned: false,
    };
    const longNotice = await management(
      admin,
      "/admin/portal/announcements",
      longBody,
      collection,
      "PORTAL_ANNOUNCEMENT",
      "SAVE_ANNOUNCEMENT",
    );
    await goto(employee, `announcements?id=${longNotice.id}`);
    await expect(employee.locator(".portal-letter-body")).toHaveText(longText);
    const boxes = await employee.evaluate(() => {
      const a = document
          .querySelector(".portal-letterhead")
          .getBoundingClientRect(),
        s = document
          .querySelector(".portal-letter-signature")
          .getBoundingClientRect();
      return {
        height: a.height,
        footerGap: a.bottom - s.bottom,
        overflow: document.documentElement.scrollWidth > innerWidth,
      };
    });
    assert.ok(boxes.height > 2000);
    assert.ok(boxes.footerGap >= 127);
    assert.equal(boxes.overflow, false);
    await employee.locator(".portal-letter-signature").scrollIntoViewIfNeeded();
    await employee.screenshot({
      path: path.join(out, "long-announcement-footer.png"),
    });
    await management(
      admin,
      `/admin/portal/announcements/${longNotice.id}`,
      { ...longBody, state: "WITHDRAWN" },
      longNotice.id,
      "PORTAL_ANNOUNCEMENT",
      "SAVE_ANNOUNCEMENT",
      longNotice.rowVersion,
      "PUT",
    );
    passed("长公告全文保留，落款与信纸页脚不重叠");
    // 保留一份明确标注的隔离演示公告，便于用户登录查看本批结果。
    body.state = "PUBLISHED";
    body.title = "员工工作台试用说明（本机隔离演示）";
    body.body =
      "各位同事：\n\n员工首页已接通公告、常用网站、意见反馈和待办消息。\n本页面为本机隔离演示，所有记录仅用于验收。\n\n特此通知。";
    await management(
      admin,
      `/admin/portal/announcements/${published.id}`,
      body,
      published.id,
      "PORTAL_ANNOUNCEMENT",
      "SAVE_ANNOUNCEMENT",
      current.rowVersion + 1,
      "PUT",
    );
    // 只撤下本批合成的重复公告，保留正文修订和审计，不删除数据。
    for (const item of (
      await get(admin, "/admin/portal/announcements?pageSize=100")
    ).items) {
      if (
        item.id === published.id ||
        item.state !== "PUBLISHED" ||
        ![
          "隔离公告 ",
          "关于员工工作台试用的通知 ",
          "员工工作台试用说明（本机隔离演示）",
        ].some((prefix) => item.title.startsWith(prefix))
      )
        continue;
      const previous = await get(
          admin,
          `/admin/portal/announcements/${item.id}`,
        ),
        withdraw = {};
      for (const key of [
        "title",
        "category",
        "body",
        "state",
        "scope",
        "validFrom",
        "validUntil",
        "pinned",
        "departmentIds",
      ])
        withdraw[key] = previous[key];
      withdraw.state = "WITHDRAWN";
      await management(
        admin,
        `/admin/portal/announcements/${item.id}`,
        withdraw,
        item.id,
        "PORTAL_ANNOUNCEMENT",
        "SAVE_ANNOUNCEMENT",
        previous.rowVersion,
        "PUT",
      );
    }
    await goto(employee, "home");
    await expect(employee.locator(".portal-home-notice")).toHaveCount(1);
    await screenshot(employee, "preview-home-clean");
    await employee.locator(".portal-home-notice").click();
    await expect(employee.locator(".portal-letter-body")).toContainText(
      "所有记录仅用于验收",
    );
    await screenshot(employee, "preview-letterhead-clean");
    fs.writeFileSync(
      path.join(
        path.dirname(process.env.IMS_PLATFORM_TEST_CONFIG),
        "portal-preview-access.private.json",
      ),
      JSON.stringify(
        {
          url: base + "/platform.html",
          employeeNo: no,
          password,
          administrator: {
            employeeNo: config.IMS_PLATFORM_ADMIN_NO,
            password: config.IMS_PLATFORM_ADMIN_PASSWORD,
          },
        },
        null,
        2,
      ),
    );
    assert.deepEqual(pageErrors, []);
    passed("浏览器无未捕获脚本异常");
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify({ status: "passed", base, results, pageErrors }, null, 2),
    );
  } catch (error) {
    if (activePage)
      await activePage
        .screenshot({ path: path.join(out, "failure.png"), fullPage: true })
        .catch(() => {});
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify(
        { status: "failed", results, pageErrors, error: error.stack },
        null,
        2,
      ),
    );
    console.error(error.stack);
    process.exitCode = 1;
  } finally {
    for (const c of contexts) await c.close();
    if (browser) await browser.close();
  }
})();
