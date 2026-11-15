import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import { randomUUID } from "node:crypto";

/** 前置流程创建的档案供权限反例复核，前置失败时停止后续依赖测试。 */
test.describe.configure({ mode: "serial" });

/** 凭据仅由隔离测试进程注入，不写入源码、截图或请求跟踪。 */
const password = process.env.IMS_E2E_PASSWORD;
if (!password) throw new Error("请提供隔离测试管理员密码 IMS_E2E_PASSWORD");
/** 唯一业务名称避免覆盖以前的测试记录。 */
const suffix = randomUUID().slice(0, 8);
const archiveName = `网页验收-${suffix}`;
const readerName = `web_reader_${suffix}`;
/** 测试夹具的最小标识，不记录认证信息。 */
let adminToken = "";
let categoryName = "";
let ownArchiveId = 0;
let otherArchiveId = 0;
let readerId = 0;
let fileId = 0;
/** HTTP辅助只准备隔离夹具和独立复核结果；错误不含请求凭据。 */
async function call(
  request: APIRequestContext,
  path: string,
  method = "GET",
  data?: unknown,
  token = adminToken,
) {
  const response = await request.fetch(`/api${path}`, {
    method,
    data,
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok())
    throw new Error(`测试接口 ${path} 返回 ${response.status()}`);
  return (await response.json()).data;
}
/** 浏览器经过真实登录页，不能直接注入身份。 */
async function signIn(page: Page, username = "admin") {
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "工作概览", exact: true }),
  ).toBeVisible();
}
/** 根据显式无障碍标签选择下拉项。 */
async function select(page: Page, label: string, option: string) {
  await page.getByRole("combobox", { name: label, exact: true }).click();
  await page.getByRole("option", { name: option, exact: true }).click();
}
/** 业务确认框只点击明确确认按钮。 */
async function confirm(page: Page) {
  await page.getByRole("button", { name: "确认", exact: true }).click();
}
/** 页面标题与加载状态均就绪后再操作。 */
async function navigate(page: Page, name: string) {
  await page.getByRole("link", { name, exact: true }).click();
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
  await expect(page.locator(".el-loading-mask:visible")).toHaveCount(0);
}

test.beforeAll(async ({ request }) => {
  // 验证生产JAR实际返回网页安全策略，未知资源与未登录API不能伪装成功。
  const root = await request.get("/");
  expect(root.headers()["content-security-policy"]).toContain(
    "script-src 'self'",
  );
  expect((await request.get("/missing-web-resource-check")).status()).toBe(404);
  expect((await request.get("/api/archives")).status()).toBe(401);
  // 本地新库中的权限反例和跨部门对象。
  const auth = await request.post("/api/auth/login", {
    data: { username: "admin", password },
  });
  expect(auth.ok()).toBeTruthy();
  expect(auth.headers()["cache-control"]).toBe("no-store");
  const user = (await auth.json()).data;
  adminToken = user.access_token;
  const categories = await call(request, "/categories");
  categoryName = categories[0].category_name;
  const role = await call(request, "/roles", "POST", {
    role_code: `preview_${suffix}`,
    role_name: "仅预览测试",
    permission_codes: [
      "archive.read",
      "file.preview",
      "user.read",
      "notice.read",
    ],
    role_level: 50,
  });
  const reader = await call(request, "/users", "POST", {
    username: readerName,
    real_name: "只读验收员",
    password,
    dept_id: user.dept_id,
    role_ids: [role.id],
  });
  readerId = reader.id;
  const department = await call(request, "/departments", "POST", {
    dept_code: `OTHER_${suffix}`,
    dept_name: "隔离部门",
  });
  const other = await call(request, "/archives", "POST", {
    archive_name: `其他部门秘密-${suffix}`,
    category_id: categories[0].id,
    dept_id: department.id,
  });
  otherArchiveId = other.id;
});

test.afterAll(async ({ request }) => {
  // 测试退出撤销测试管理员会话，避免把验收令牌留作后续调用凭据。
  if (adminToken) await request.post('/api/auth/logout', {headers:{Authorization:`Bearer ${adminToken}`}});
});

test("浏览器完成建档、附件、借阅审批与归还", async ({ page, request }) => {
  // 捕获脚本异常，避免仅依据表面文字判断成功。
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await signIn(page);
  await page.screenshot({
    path: "test-results/dashboard-desktop.png",
    fullPage: true,
  });
  await navigate(page, "档案台账");
  await page.getByRole("button", { name: "新增档案", exact: true }).click();
  await page
    .getByRole("textbox", { name: "档案名称", exact: true })
    .fill(archiveName);
  await select(page, "档案分类", categoryName);
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "新增档案", exact: true }),
  ).toBeHidden();
  const row = page.getByRole("row").filter({ hasText: archiveName });
  await row.getByRole("button", { name: "查看详情" }).click();
  await page.getByRole("tab", { name: "电子附件" }).click();
  await page.getByLabel("上传附件", { exact: true }).setInputFiles({
    name: "验收记录.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("网页附件预览验证：中文内容保持可读。"),
  });
  await expect(page.getByText("验收记录.txt", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "预览", exact: true }).click();
  await expect(
    page.getByText("网页附件预览验证：中文内容保持可读。", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("dialog", { name: "附件预览" })
    .getByRole("button", { name: "关闭此对话框" })
    .click();
  await page.getByRole("button", { name: "正式归档", exact: true }).click();
  await confirm(page);
  await expect(
    page.getByRole("button", { name: "正式归档", exact: true }),
  ).toBeHidden();
  await page
    .getByRole("dialog", { name: "档案详情" })
    .getByRole("button", { name: "关闭此对话框" })
    .click();
  const found = await call(
    request,
    `/archives?keyword=${encodeURIComponent(archiveName)}`,
  );
  ownArchiveId = found.items[0].id;
  expect(found.items[0].status).toBe("archive");
  fileId = (await call(request, `/files?archive_id=${ownArchiveId}`))[0].id;
  await page.screenshot({
    path: "test-results/archives-desktop.png",
    fullPage: true,
  });
  await navigate(page, "借阅管理");
  await page.getByRole("button", { name: "新增申请", exact: true }).click();
  await page
    .getByRole("combobox", { name: "选择档案", exact: true })
    .fill(archiveName);
  await page.getByRole("option").filter({ hasText: archiveName }).click();
  await page
    .getByRole("combobox", { name: "预计归还日期", exact: true })
    .fill(`${new Date().getFullYear() + 1}-12-31`);
  await page
    .getByRole("textbox", { name: "借阅用途", exact: true })
    .fill("网页流程验收");
  await page.getByRole("button", { name: "提交申请", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "新增申请", exact: true }),
  ).toBeHidden();
  await page
    .getByRole("row")
    .filter({ hasText: archiveName })
    .getByRole("button", { name: "查看办理" })
    .click();
  await page.getByRole("button", { name: "同意申请", exact: true }).click();
  await confirm(page);
  await expect(
    page.getByRole("button", { name: "办理借出", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "办理借出", exact: true }).click();
  await confirm(page);
  await page.getByRole("button", { name: "办理归还", exact: true }).click();
  await page
    .getByRole("textbox", { name: "归还情况", exact: true })
    .fill("完整归还");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "办理归还", exact: true }),
  ).toBeHidden();
  expect((await call(request, `/archives/${ownArchiveId}`)).status).toBe(
    "archive",
  );
  expect((await call(request, "/borrows")).items[0].status).toBe("returned");
  expect(errors).toEqual([]);
});

test("基础页面、公告发布及小屏布局可用", async ({ page }) => {
  await signIn(page);
  for (const name of [
    "审批中心",
    "审批规则",
    "组织与账号",
    "基础设置",
    "操作日志",
    "备份与校验",
  ]) {
    await navigate(page, name);
    await expect(page.locator(".el-message--error")).toHaveCount(0);
  }
  await navigate(page, "公告通知");
  await page.getByRole("button", { name: "发布公告", exact: true }).click();
  await page
    .getByRole("textbox", { name: "公告标题", exact: true })
    .fill(`网页公告-${suffix}`);
  await page
    .getByRole("textbox", { name: "公告正文", exact: true })
    .fill("<script>不会执行的普通文本</script>");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await confirm(page);
  await expect(
    page.getByRole("dialog", { name: "发布公告", exact: true }),
  ).toBeHidden();
  await page
    .getByRole("row")
    .filter({ hasText: `网页公告-${suffix}` })
    .getByRole("button", { name: "阅读" })
    .click();
  await expect(
    page.getByText("<script>不会执行的普通文本</script>", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭此对话框" })
    .click();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "展开导航" }).click();
  await navigate(page, "档案台账");
  await page.screenshot({
    path: "test-results/archives-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("审批规则编辑和账号编辑保留已有关系", async ({ page, request }) => {
  await signIn(page);
  await navigate(page, "审批规则");
  await page.getByRole("button", { name: "新增规则", exact: true }).click();
  await page
    .getByRole("textbox", { name: "规则名称", exact: true })
    .fill(`验收规则-${suffix}`);
  // 条件限定独立对象，避免改变后续普通测试档案的默认审批路由。
  await page
    .getByRole("combobox", { name: "条件字段", exact: true })
    .first()
    .click();
  await page.getByRole("option", { name: "档案名称", exact: true }).click();
  await page
    .getByRole("combobox", { name: "匹配方式", exact: true })
    .first()
    .click();
  await page.getByRole("option", { name: "等于", exact: true }).click();
  await page
    .getByRole("textbox", { name: "匹配内容", exact: true })
    .first()
    .fill(`仅匹配规则测试-${suffix}`);
  await select(page, "审批人范围", "系统管理员");
  await page.getByRole("button", { name: "保存规则", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "审批规则配置" })).toBeHidden();
  await page
    .getByRole("row")
    .filter({ hasText: `验收规则-${suffix}` })
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "规则名称", exact: true }),
  ).toHaveValue(`验收规则-${suffix}`);
  await page.getByRole("button", { name: "保存规则", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "审批规则配置" })).toBeHidden();
  await navigate(page, "组织与账号");
  const before = await call(request, `/users/${readerId}`);
  // 用户目录有分页；直接进入包含本次读者的页，仍用真实界面编辑。
  while (
    (await page.getByRole("row").filter({ hasText: readerName }).count()) === 0
  ) {
    await page.getByRole("button", { name: "下一页", exact: true }).click();
    await expect(page.locator(".el-loading-mask:visible")).toHaveCount(0);
  }
  await page
    .getByRole("row")
    .filter({ hasText: readerName })
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "姓名", exact: true })
    .fill("网页只读验收员");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "编辑用户" })).toBeHidden();
  expect((await call(request, `/users/${readerId}`)).role_ids).toEqual(
    before.role_ids,
  );
});

test("异常处理及销毁执行严格遵循批准范围", async ({ page, request }) => {
  // 独立档案用于不可逆的销毁测试，不能销毁前序权限测试仍需使用的附件。
  const actor = await call(request, "/auth/me");
  const categories = await call(request, "/categories");
  const name = `销毁验收-${suffix}`;
  const record = await call(request, "/archives", "POST", {
    archive_name: name,
    dept_id: actor.dept_id,
    category_id: categories[0].id,
  });
  await call(request, `/archives/${record.id}/archive`, "POST");
  await signIn(page);
  await navigate(page, "异常登记");
  await page.getByRole("button", { name: "登记异常", exact: true }).click();
  await page.getByRole("combobox", { name: "选择档案" }).fill(name);
  await page.getByRole("option").filter({ hasText: name }).click();
  await page
    .getByRole("textbox", { name: "异常类型", exact: true })
    .fill("外包装破损");
  await page
    .getByRole("textbox", { name: "异常描述", exact: true })
    .fill("测试处理流程");
  await page.getByRole("button", { name: "提交申请", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "登记异常", exact: true }),
  ).toBeHidden();
  await page
    .getByRole("row")
    .filter({ hasText: name })
    .getByRole("button", { name: "查看办理" })
    .click();
  await page.getByRole("button", { name: "登记处理结果", exact: true }).click();
  await select(page, "处理后状态", "已处理");
  await page
    .getByRole("textbox", { name: "处理方式", exact: true })
    .fill("更换外包装");
  await page
    .getByRole("textbox", { name: "处理结果", exact: true })
    .fill("已检查内容完整");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "登记处理结果", exact: true }),
  ).toBeHidden();
  await page
    .getByRole("dialog", { name: "异常登记 · 详情" })
    .getByRole("button", { name: "关闭此对话框" })
    .click();
  await navigate(page, "销毁管理");
  await page.getByRole("button", { name: "新增申请", exact: true }).click();
  await page.getByRole("combobox", { name: "选择档案" }).fill(name);
  await page.getByRole("option").filter({ hasText: name }).click();
  await page
    .getByRole("textbox", { name: "销毁原因", exact: true })
    .fill(`测试销毁-${suffix}`);
  await page.getByRole("button", { name: "提交申请", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "新增申请", exact: true }),
  ).toBeHidden();
  await page
    .getByRole("row")
    .filter({ hasText: `测试销毁-${suffix}` })
    .getByRole("button", { name: "查看办理" })
    .click();
  await page.getByRole("button", { name: "同意申请", exact: true }).click();
  await confirm(page);
  await expect(
    page.getByRole("button", { name: "登记销毁执行", exact: true }),
  ).toBeDisabled();
  // 组件原生input用于键盘访问，鼠标操作点击可见的关联标签。
  await page.getByText(/我已核对上述全部/).click();
  await expect(page.getByRole("checkbox", {name: /我已核对上述全部/})).toBeChecked();
  await page.getByRole("button", { name: "登记销毁执行", exact: true }).click();
  await page
    .getByRole("textbox", { name: "见证人", exact: true })
    .fill("测试见证员");
  await page
    .getByRole("textbox", { name: "销毁方法", exact: true })
    .fill("隔离测试登记");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await confirm(page);
  await expect(
    page.getByRole("dialog", { name: "登记销毁执行", exact: true }),
  ).toBeHidden();
  expect(
    (await call(request, `/archives?keyword=${encodeURIComponent(name)}`))
      .total,
  ).toBe(0);
  expect((await call(request, `/archives/${ownArchiveId}`)).status).toBe(
    "archive",
  );
});

test("普通用户受部门隔离且仅能预览不能下载", async ({ page, request }) => {
  await signIn(page, readerName);
  await expect(
    page.getByRole("link", { name: "销毁管理", exact: true }),
  ).toHaveCount(0);
  await navigate(page, "档案台账");
  await expect(
    page.getByRole("button", { name: "新增档案", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByText(`其他部门秘密-${suffix}`, { exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("row")
    .filter({ hasText: archiveName })
    .getByRole("button", { name: "查看详情" })
    .click();
  await page.getByRole("tab", { name: "电子附件" }).click();
  await expect(
    page.getByRole("button", { name: "下载", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "预览", exact: true }).click();
  await expect(
    page.getByText("网页附件预览验证：中文内容保持可读。", { exact: true }),
  ).toBeVisible();
  const auth = await request.post("/api/auth/login", {
    data: { username: readerName, password },
  });
  const token = (await auth.json()).data.access_token;
  expect(
    (
      await request.get(`/api/files/${fileId}/download`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).status(),
  ).toBe(403);
  expect([403, 404]).toContain(
    (
      await request.get(`/api/archives/${otherArchiveId}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).status(),
  );
  await page.goto("/#/destroy");
  await expect(
    page.getByText("当前账号没有此页面的访问权限，请从左侧选择可用功能。"),
  ).toBeVisible();
  // 禁用账号后刷新必须回到登录页，而非继续显示旧档案内容。
  await call(request, `/users/${readerId}/disable`, "POST");
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "登录档案工作台" }),
  ).toBeVisible();
});
