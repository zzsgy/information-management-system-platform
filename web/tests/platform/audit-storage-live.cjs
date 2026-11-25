/** 日志生命周期电脑端验收：真实隔离HTTP、游标交互及布局，不修改日常账号或发送外部消息。 */
const fs = require("node:fs"),
  path = require("node:path"),
  crypto = require("node:crypto"),
  assert = require("node:assert/strict");
const { chromium, expect } = require("@playwright/test");
const config = JSON.parse(
  fs.readFileSync(process.env.IMS_PLATFORM_TEST_CONFIG, "utf8"),
);
const sourceDatabase =
  /^jdbc:postgresql:\/\/127\.0\.0\.1:55476\/ims_platform_dev_[0-9]+$/.test(
    config.IMS_PLATFORM_DB_URL,
  ) && config.IMS_PLATFORM_PORT === "18576";
const recoveredDatabase =
  /^jdbc:postgresql:\/\/127\.0\.0\.1:55477\/ims_platform_test_restore_audit_[0-9]{8}$/.test(
    config.IMS_PLATFORM_DB_URL,
  ) && config.IMS_PLATFORM_PORT === "18577";
assert.ok(
  sourceDatabase || recoveredDatabase,
  "只允许日志专项源库或其专用恢复实例",
);
assert.ok(
  !process.env.IMS_AUDIT_HTTP_SCALE_CONFIG || sourceDatabase,
  "深游标压测夹具只能写入专用源库",
);
const base = `https://127.0.0.1:${config.IMS_PLATFORM_PORT}`,
  out = path.resolve(process.env.IMS_AUDIT_STORAGE_BROWSER_OUTPUT);
assert.ok(!fs.existsSync(out), "验收输出必须是新目录");
fs.mkdirSync(out);
const results = [],
  errors = [];
let browser, page;
let httpScale = null;
/** 可选千万级真实HTTP验收；深位置SQL仅构造测试游标，所有被测请求仍走正式权限及接口。 */
async function scaleHttp(actor) {
  if (!process.env.IMS_AUDIT_HTTP_SCALE_CONFIG) return;
  const maintenance = require("../../../scripts/platform/maintenance.cjs");
  const context = maintenance.settings(process.env.IMS_AUDIT_HTTP_SCALE_CONFIG);
  assert.match(
    context.config.IMS_PLATFORM_DB_URL,
    /^jdbc:postgresql:\/\/127\.0\.0\.1:55476\/ims_platform_dev_[0-9]+$/,
  );
  const tag = maintenance.sql(
    context,
    "SELECT resource_type FROM ops.audit_event WHERE action='LOAD_TEST' LIMIT 1",
  );
  assert.match(tag, /^LOAD-[0-9a-f-]{36}$/);
  const filters = {
    module: "PLATFORM",
    resourceType: tag,
    from: "2025-04-01T00:00:00Z",
    to: "2025-10-01T00:00:00Z",
    pageSize: "20",
  };
  const route = "/api/v1/admin/audit-events?" + new URLSearchParams(filters);
  async function read(url) {
    const response = await page.request.get(base + url);
    assert.equal(response.status(), 200);
    return (await response.json()).data;
  }
  const first = await read(route);
  assert.equal(first.total, null);
  assert.match(first.queryId, /^[0-9a-f-]{36}$/);
  assert.equal(
    (await read(`/api/v1/admin/audit-events/${first.queryId}/count`)).total,
    1000000,
  );
  const intentRoute =
    "/api/v1/admin/audit-events?" +
    new URLSearchParams({
      module: "PLATFORM",
      kind: "INTERACTION",
      from: filters.from,
      to: filters.to,
      pageSize: "20",
    });
  const intent = await read(intentRoute);
  assert.equal(
    (await read(`/api/v1/admin/audit-events/${intent.queryId}/count`)).total,
    9000000,
  );
  const cursor = crypto.randomUUID();
  maintenance.sql(
    context,
    `INSERT INTO ops.audit_page_cursor(id,query_id,anchor_time,anchor_id) SELECT '${cursor}','${first.queryId}',occurred_at,id FROM ops.audit_event WHERE module='PLATFORM' AND resource_type='${tag}' ORDER BY occurred_at DESC,id DESC OFFSET 800000 LIMIT 1`,
  );
  const deep = [];
  for (let n = 0; n < 20; n++) {
    const start = performance.now();
    const data = await read(`/api/v1/admin/audit-events?cursor=${cursor}`);
    assert.equal(data.items.length, 20);
    deep.push(performance.now() - start);
  }
  deep.sort((a, b) => a - b);
  const latencies = [];
  await Promise.all([
    ...Array.from({ length: 8 }, async (_, reader) => {
      for (let n = 0; n < 50; n++) {
        const start = performance.now();
        await read((reader + n) % 2 ? route : intentRoute);
        latencies.push(performance.now() - start);
      }
    }),
    ...Array.from({ length: 2 }, async () => {
      for (let n = 0; n < 100; n++) {
        const response = await page.request.post(
          base + "/api/v1/me/interactions",
          {
            data: {
              id: crypto.randomUUID(),
              requestId: crypto.randomUUID(),
              page: "audit",
              action: "ACTIVATE",
              control: "BUTTON",
            },
            headers: { Origin: base, "X-CSRF-Token": actor.csrfToken },
          },
        );
        assert.equal(response.status(), 200);
      }
    }),
  ]);
  latencies.sort((a, b) => a - b);
  httpScale = {
    factRows: 1000000,
    interactionRows: 9000000,
    readers: 8,
    writers: 2,
    readRequests: 400,
    intentWrites: 200,
    deepCursorP95Millis: deep[18],
    readP50Millis: latencies[199],
    readP95Millis: latencies[379],
    readP99Millis: latencies[395],
  };
  assert.ok(httpScale.deepCursorP95Millis < 1000, "HTTP深游标P95应小于1秒");
  assert.ok(httpScale.readP95Millis < 1000, "混合HTTP查询P95应小于1秒");
  results.push("千万级数据下深游标、显式统计及8读2写真实HTTP验收通过");
}
function listResponse() {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/api/v1/admin/audit-events" &&
      response.request().method() === "GET",
  );
}
async function listData(response) {
  assert.equal(response.status(), 200);
  return (await response.json()).data;
}
async function noOverflow() {
  assert.ok(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth <=
        document.documentElement.clientWidth,
    ),
    "整页不能横向溢出",
  );
}
(async () => {
  try {
    browser = await chromium.launch({ channel: "msedge", headless: true });
    const context = await browser.newContext({
      ignoreHTTPSErrors: true,
      viewport: { width: 1440, height: 1000 },
    });
    page = await context.newPage();
    page.on("pageerror", (error) => errors.push(error.message));
    let countRequests = 0;
    page.on("request", (request) => {
      if (
        /\/admin\/audit-events\/[^/]+\/count$/.test(
          new URL(request.url()).pathname,
        )
      )
        countRequests++;
    });
    await page.goto(base + "/platform.html");
    await page
      .getByLabel("工号或姓名", { exact: true })
      .fill(config.IMS_PLATFORM_ADMIN_NO);
    await page
      .getByLabel("登录密码", { exact: true })
      .fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
    await page.getByRole("button", { name: "登录工作台" }).click();
    // 登录先展示本人信息，再加载角色；等待登录流程完成，避免测试导航被登录后的回首页覆盖。
    await expect(page).toHaveURL(/#\/home$/);
    await expect(
      page.getByRole("button", { name: "退出", exact: true }),
    ).toBeVisible();
    let waiting = listResponse();
    await page.goto(base + "/platform.html#/audit");
    const first = await listData(await waiting);
    await expect(
      page.getByRole("heading", { name: "操作记录", exact: true }),
    ).toBeVisible();
    assert.equal(first.total, null);
    assert.equal(first.items.length, 20);
    assert.ok(first.nextCursor);
    await expect(page.getByText("总数未统计", { exact: true })).toBeVisible();
    await expect(page.getByText(/^第\s*1\s*页$/)).toBeVisible();
    waiting = listResponse();
    await page.getByRole("button", { name: "下一页", exact: true }).click();
    const second = await listData(await waiting);
    assert.equal(second.queryId, first.queryId);
    const ids = new Set(first.items.map((row) => row.id));
    assert.ok(second.items.every((row) => !ids.has(row.id)));
    assert.equal(countRequests, 0);
    waiting = listResponse();
    await page.getByRole("button", { name: "上一页", exact: true }).click();
    const previous = await listData(await waiting);
    assert.deepEqual(
      previous.items.map((row) => row.id),
      first.items.map((row) => row.id),
    );
    await page.getByRole("button", { name: "统计总数", exact: true }).click();
    await expect(page.getByText(/^共\s*\d+\s*条$/)).toBeVisible();
    assert.equal(countRequests, 1);
    results.push("游标前后翻页无重复、截点一致且总数按需统计");
    await noOverflow();
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }));
    await page.screenshot({
      path: path.join(out, "audit-cursor-1440.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "存储与归档", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "日志存储与归档", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "采样容量", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "采样容量", exact: true }),
    ).toBeEnabled();
    await expect(page.getByText("日志与索引", { exact: true })).toBeVisible();
    const profile = await page.request.get(base + "/api/v1/auth/me");
    assert.equal(profile.status(), 200);
    const actor = (await profile.json()).data;
    const hotMonth = new Date().toISOString().slice(0, 7) + "-01";
    const invalid = await page.request.post(
      base + "/api/v1/admin/audit-storage/archives",
      {
        data: { month: hotMonth, path: "D:/should-not-be-accepted" },
        headers: {
          Origin: base,
          "X-CSRF-Token": actor.csrfToken,
          "X-Operation-Id": crypto.randomUUID(),
        },
      },
    );
    assert.equal(invalid.status(), 403);
    const collection = "00000000-0000-0000-0000-000000000001";
    const headers = {
      Origin: base,
      "X-CSRF-Token": actor.csrfToken,
      "X-Operation-Id": crypto.randomUUID(),
    };
    const proofResponse = await page.request.post(
      base + "/api/v1/auth/reauthentications",
      {
        data: {
          password: config.IMS_PLATFORM_ADMIN_PASSWORD,
          purpose: "MANAGE",
          objectIds: [collection],
          scopeItems: [
            {
              objectType: "AUDIT_ARCHIVE",
              objectId: collection,
              actions: ["ARCHIVE_LOGS"],
            },
          ],
        },
        headers,
      },
    );
    assert.equal(proofResponse.status(), 200);
    headers["X-Reauth-Token"] = (
      await proofResponse.json()
    ).data.verificationToken;
    const unknownField = await page.request.post(
      base + "/api/v1/admin/audit-storage/archives",
      {
        data: { month: hotMonth, path: "D:/should-not-be-accepted" },
        headers,
      },
    );
    assert.equal(unknownField.status(), 400);
    const hotResponse = await page.request.post(
      base + "/api/v1/admin/audit-storage/archives",
      {
        data: { month: hotMonth },
        headers: { ...headers, "X-Operation-Id": crypto.randomUUID() },
      },
    );
    assert.equal(hotResponse.status(), 400);
    results.push("存储概览和真实采样可用，再验证、字段白名单和热区限制均生效");
    for (const width of [1024, 1280, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      await noOverflow();
      await page.evaluate(() =>
        window.scrollTo({ top: 0, behavior: "instant" }),
      );
      await page.screenshot({
        path: path.join(out, `audit-storage-${width}.png`),
        fullPage: true,
      });
    }
    assert.deepEqual(errors, []);
    results.push("1024、1280、1920电脑端无整页横向溢出及页面脚本异常");
    await scaleHttp(actor);
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify(
        { status: "passed", results, pageErrors: errors, httpScale },
        null,
        2,
      ),
    );
    console.log(JSON.stringify({ status: "passed", results }));
  } catch (error) {
    if (page)
      await page
        .screenshot({ path: path.join(out, "failure.png"), fullPage: true })
        .catch(() => {});
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify(
        { status: "failed", results, pageErrors: errors, error: error.message },
        null,
        2,
      ),
    );
    console.error(error.message);
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
  }
})();
