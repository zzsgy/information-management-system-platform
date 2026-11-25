import {test, expect} from "@playwright/test";
import {readFileSync} from "node:fs";

/** 使用独立库的档案员测试配置，经真实表单登录，不注入会话或放宽前端校验。 */
const config=JSON.parse(readFileSync(process.env.IMS_PLATFORM_TEST_CONFIG!,"utf8"));
test("目录维护四个页签不再因响应缺字段阻断",async({page})=>{
 const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
 await page.goto("/");
 await page.getByLabel("工号或姓名",{exact:true}).fill(config.IMS_PLATFORM_ADMIN_NO);
 await page.getByLabel("登录密码",{exact:true}).fill(config.IMS_PLATFORM_ADMIN_PASSWORD);
 await page.getByRole("button",{name:"登录工作台"}).click();
 await expect(page.getByRole("heading",{name:/好，/})).toBeVisible();
 const initial=page.waitForResponse(r=>new URL(r.url()).pathname==="/api/v1/archive/catalog/RECORD");
 await page.goto("/#/catalog");
 expect((await initial).status()).toBe(200);
 await expect(page.getByRole("heading",{name:"目录维护与保管",exact:true})).toBeVisible();
 for(const [label,path] of [["档案盒","/api/v1/archive/catalog/BOX"],["保管到期","/api/v1/archive/retention"],["目录模板","/api/v1/admin/archive/directory-template-versions"],["文件目录","/api/v1/archive/catalog/RECORD"]]){
  const response=page.waitForResponse(r=>new URL(r.url()).pathname===path);
  await page.getByRole("navigation",{name:"档案目录分类"}).getByRole("button",{name:label,exact:true}).click();
  expect((await response).status()).toBe(200);
  await expect(page.locator(".catalog-page [role='alert']")).toHaveCount(0);
 }
 expect(errors).toEqual([]);
});
