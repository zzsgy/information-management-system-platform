import { test, expect, type Page } from "@playwright/test";
import { readFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";

/** 口令仅从用户专有配置读取，不出现在测试数据快照或日志里。 */
const config = JSON.parse(
  readFileSync(process.env.IMS_PLATFORM_TEST_CONFIG!, "utf8"),
);
/** 截图只覆盖非秘密页面，单独保存在被忽略的验收目录。 */
const screenshots = resolve("platform-test-results/screenshots");
mkdirSync(screenshots, { recursive: true });

/** 通过真实表单登录，不注入 localStorage 或绕过服务端会话。 */
async function signIn(page: Page) {
  await page.goto("/");
  await page
    .getByLabel("工号或姓名", { exact: true })
    .fill(config.IMS_PLATFORM_ADMIN_NO);
  await page
    .getByLabel("登录密码", { exact: true })
    .fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(
    page.getByRole("heading", { name: /好，平台管理员/ }),
  ).toBeVisible();
}

/** 原生对话框的再验证走真实后端，取消和关闭都不能产生写入。 */
async function verify(page: Page) {
  const dialog = page
    .getByRole("dialog")
    .filter({ has: page.getByRole("heading", { name: "验证本人身份" }) });
  await expect(dialog).toBeVisible();
  await dialog
    .getByLabel("本人登录密码")
    .fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await dialog.getByRole("button", { name: "确认并继续" }).click();
}

test("人员与部门真实创建、角色分配、权限查看及审计查询", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const suffix = Date.now().toString(36);
  await signIn(page);
  await page.getByRole("link", { name: "组织与人员", exact: true }).click();
  await page.getByRole("tab", { name: "部门管理" }).click();
  await page.getByRole("button", { name: "新增部门" }).click();
  const departmentDialog = page
    .getByRole("dialog")
    .filter({ has: page.getByRole("heading", { name: "新增部门" }) });
  await departmentDialog.getByLabel("部门名称").fill("网页验收部门" + suffix);
  await departmentDialog.getByLabel("部门编码").fill("WEB" + suffix);
  await departmentDialog
    .getByRole("button", { name: "保存", exact: true })
    .click();
  await verify(page);
  await expect(departmentDialog).not.toBeVisible();
  await page.getByRole("tab", { name: "人员管理" }).click();
  await page.getByRole("button", { name: "新增人员" }).click();
  const userDialog = page
    .getByRole("dialog")
    .filter({ has: page.getByRole("heading", { name: "新增人员" }) });
  await userDialog.getByLabel("公司工号").fill("WEB" + suffix);
  await userDialog
    .getByLabel("姓名", { exact: true })
    .fill("网页验收人员" + suffix);
  await userDialog
    .getByLabel("所属部门")
    .selectOption({ label: "网页验收部门" + suffix });
  await userDialog.getByLabel("公司邮箱").fill("web" + suffix + "@biolink.com");
  await userDialog.getByRole("button", { name: "保存", exact: true }).click();
  await verify(page);
  await expect(userDialog).not.toBeVisible();
  await page.getByLabel("搜索姓名或工号").fill("WEB" + suffix);
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "网页验收人员" + suffix + " WEB" + suffix }),
  ).toBeVisible();
  await page.getByRole("link", { name: "角色与权限", exact: true }).click();
  await page.getByRole("button", { name: "分配人员角色" }).click();
  const roleDialog = page
    .getByRole("dialog")
    .filter({ has: page.getByRole("heading", { name: "分配人员角色" }) });
  await roleDialog.getByPlaceholder("输入姓名或工号").fill("WEB" + suffix);
  await roleDialog.getByRole("button", { name: "查找" }).click();
  await roleDialog
    .getByRole("button", { name: new RegExp("网页验收人员" + suffix) })
    .click();
  await roleDialog.getByRole("checkbox", { name: "职员", exact: true }).check();
  await roleDialog.getByLabel("批准依据").fill("WEB-QA");
  await roleDialog.getByLabel("调整原因").fill("隔离浏览器验收");
  await roleDialog.getByRole("button", { name: "保存角色" }).click();
  await verify(page);
  await expect(roleDialog).not.toBeVisible();
  await page.getByRole("button", { name: "查看权限" }).first().click();
  await expect(page.getByRole("heading", { name: /权限配置/ })).toBeVisible();
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await page.getByRole("link", { name: "操作记录", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "分配角色", exact: true }).first(),
  ).toBeVisible();
  await page.screenshot({
    path: resolve(screenshots, "audit-desktop.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("电脑浏览器排版、Cookie 与浏览器存储边界", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  await expect(
    page.getByText("登录已失效，请重新登录", { exact: true }),
  ).toHaveCount(0);
  await page.screenshot({
    path: resolve(screenshots, "login-desktop.png"),
    fullPage: true,
  });
  await signIn(page);
  // 用户确认本批仅验收电脑端；不继续手机端专项适配或验收。
  for (const width of [1440, 1280, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.getByRole("link", { name: "工作台", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: /好，平台管理员/ }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: resolve(screenshots, `workspace-${width}.png`),
      fullPage: true,
    });
    await page.getByRole("link", { name: "组织与人员", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "组织与人员" }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: resolve(screenshots, `people-${width}.png`),
      fullPage: true,
    });
  }
  const storage = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage),
    cookie: document.cookie,
  }));
  expect(storage.local).toEqual([]);
  expect(storage.session).toEqual([]);
  expect(storage.cookie).not.toContain("IMS_SESSION");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
  expect(errors).toEqual([]);
});
