import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";

/** 沿用平台隔离环境和真实密码再验证，不录制认证请求。 */
const config = JSON.parse(readFileSync(process.env.IMS_PLATFORM_TEST_CONFIG!, "utf8"));
test("IT在网页维护服务授权，保存持久化且运行环境失败不伪装通过", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("公司工号", { exact: true }).fill(config.IMS_PLATFORM_ADMIN_NO);
  await page.getByLabel("登录密码", { exact: true }).fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await page.getByRole("button", { name: "登录工作台" }).click();
  await page.getByRole("link", { name: "进入运维与保护配置服务", exact: true }).click();
  await expect(page.getByRole("heading", { name: "文件处理服务配置" })).toBeVisible();
  const card = page.locator(".services-grid article").filter({has: page.getByRole("heading", {name: "公盘文件检测", exact: true})});
  await card.getByRole("button").click();
  const form = page.getByRole("dialog").filter({has: page.getByRole("heading", {name: "公盘文件检测授权配置"})});
  const name = "网页服务配置验收" + Date.now();
  await form.getByLabel("服务名称").fill(name);
  await form.getByRole("button", {name: "选择当前全部部门"}).click();
  await form.getByLabel("配置原因").fill("独立库网页配置验收");
  await form.getByRole("button", {name: "保存并生效"}).click();
  const verification = page.getByRole("dialog").filter({has: page.getByRole("heading", {name: "验证本人身份"})});
  await verification.getByLabel("本人登录密码").fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await verification.getByRole("button", {name: "确认并继续"}).click();
  await expect(card.getByText(name, {exact: true})).toBeVisible();
  await expect(page.getByText("服务授权已保存并生效，运行环境检查结果见下方。", {exact: true})).toBeVisible();
  await expect(page.getByText("文件检测程序与病毒库：未通过", {exact: true})).toBeVisible();
  await expect(page.locator(".service-configuration").getByText(/检查请求编号：/)).toBeVisible();
  await page.reload();
  await expect(card.getByText(name, {exact: true})).toBeVisible();
  await card.getByRole("button").click();
  await form.getByLabel("启停状态").selectOption("DISABLED");
  await form.getByLabel("配置原因").fill("验证网页停用立即生效");
  await form.getByRole("button", {name: "保存并生效"}).click();
  await verification.getByLabel("本人登录密码").fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
  await verification.getByRole("button", {name: "确认并继续"}).click();
  await expect(card.getByText("已停用", {exact: true})).toBeVisible();
});

/** 分组表单沿真实保存、再验证、刷新读取链路，安装包不含当前环境凭据。 */
test("IT部署参数可保存并刷新，安装包包含可用脚本", async ({page})=>{
 await page.goto("/");
 await page.getByLabel("公司工号", {exact:true}).fill(config.IMS_PLATFORM_ADMIN_NO);
 await page.getByLabel("登录密码", {exact:true}).fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
 await page.getByRole("button",{name:"登录工作台"}).click();
 await page.getByRole("link",{name:"进入运维与保护配置服务",exact:true}).click();
 const panel=page.getByRole("region",{name:"部署运行配置"});
 await expect(panel.getByRole("heading",{name:"部署运行配置",exact:true})).toBeVisible();
 await panel.getByLabel("检测超时（秒）",{exact:true}).fill("301");
 await panel.getByLabel("修改原因",{exact:true}).fill("隔离环境运行参数验收");
 await panel.getByRole("button",{name:"保存文件检测配置",exact:true}).click();
 const verify=page.getByRole("dialog").filter({has:page.getByRole("heading",{name:"验证本人身份"})});
 await verify.getByLabel("本人登录密码").fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
 await verify.getByRole("button",{name:"确认并继续"}).click();
 await expect(panel.getByRole("status")).toContainText("配置已保存并生效");
 await page.reload();await expect(panel.getByLabel("检测超时（秒）",{exact:true})).toHaveValue("301");
 await panel.getByRole("button",{name:"备份与维护",exact:true}).click();
 const download=page.waitForEvent("download");await panel.getByRole("link",{name:"下载独立执行器安装包与说明"}).click();
 expect((await download).suggestedFilename()).toBe("IMS-operations-agent.zip");
});
