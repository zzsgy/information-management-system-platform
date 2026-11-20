import {test,expect} from "@playwright/test";
import {readFileSync,mkdirSync} from "node:fs";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";
/** 账号与受邀人来自专用隔离配置；业务创建仍经过真实权限、CSRF和预约校验。 */
const config=JSON.parse(readFileSync(process.env.IMS_PLATFORM_TEST_CONFIG!,"utf8"));
test("本人当前会议和跨会议未完成情况均能下载Excel",async({page,browser,baseURL})=>{
 await page.goto("/");await page.getByLabel("公司工号",{exact:true}).fill(config.IMS_PLATFORM_ADMIN_NO);await page.getByLabel("登录密码",{exact:true}).fill(config.IMS_PLATFORM_ADMIN_PASSWORD);await page.getByRole("button",{name:"登录工作台"}).click();
 await expect(page.getByRole("heading",{name:/好，/})).toBeVisible();
 const me=await (await page.request.get("/api/v1/auth/me")).json();const csrf=me.data.csrfToken;
 const existing=await (await page.request.get("/api/v1/meetings?pageSize=100")).json();
 for(const row of existing.data.items)if(row.subject.startsWith("未完成导出网页验收")&&row.state==="SCHEDULED"){
  const cancelled=await page.request.post(`/api/v1/meetings/${row.id}/cancel`,{headers:{"X-CSRF-Token":csrf},data:{reason:"隔离验收重新安排，保留历史"}});expect(cancelled.ok()).toBeTruthy();
 }
 const ids:string[]=[];try{const start=Date.now()+200*86400000;
 for(let n=0;n<2;n++){
  const response=await page.request.post("/api/v1/meetings",{headers:{"X-CSRF-Token":csrf,"X-Request-Id":crypto.randomUUID()},data:{subject:`未完成导出网页验收${Date.now()}-${n}`,description:"隔离验收临时地点",roomId:null,kind:"ORDINARY",startsAt:new Date(start+n*7200000).toISOString(),endsAt:new Date(start+n*7200000+1800000).toISOString(),attendeeIds:[config.IMS_MEETING_EXPORT_INVITEE],materialTypes:["REPORT","WEEKLY_PLAN","COORDINATION"],shareAll:false,trackFollowUp:true}});
  const body=await response.json();expect(response.ok(),body.message).toBeTruthy();expect(body.code).toBe("OK");ids.push(body.data.id);
 }
 await page.goto("/#/meetings");
 const folder=fileURLToPath(new URL("../../platform-test-results/meeting-incomplete",import.meta.url));mkdirSync(folder,{recursive:true});
 const all=page.waitForEvent("download");await page.getByRole("button",{name:"导出我发起会议的未完成汇总",exact:true}).click();const allFile=await all;expect(allFile.suggestedFilename()).toBe("我发起会议未完成汇总.xlsx");await allFile.saveAs(resolve(folder,"all.xlsx"));
 await page.goto("/#/meetings?id="+ids[0]);
 const current=page.waitForEvent("download");await page.getByRole("button",{name:"导出本场未完成情况",exact:true}).click();const currentFile=await current;expect(currentFile.suggestedFilename()).toBe("本场会议未完成情况.xlsx");await currentFile.saveAs(resolve(folder,"current.xlsx"));
 expect(readFileSync(resolve(folder,"all.xlsx")).subarray(0,2).toString()).toBe("PK");
 expect(readFileSync(resolve(folder,"current.xlsx")).subarray(0,2).toString()).toBe("PK");
 await expect(page.getByText("服务器响应缺少",{exact:false})).toHaveCount(0);
 const other=await browser.newContext({baseURL,ignoreHTTPSErrors:true});
 try{
  const login=await other.request.post("/api/v1/auth/login",{data:{employeeNo:config.IMS_MEETING_EXPORT_INVITEE_NO,password:config.IMS_PLATFORM_ADMIN_PASSWORD}});expect(login.ok()).toBeTruthy();
  const denied=await other.request.get(`/api/v1/meetings/${ids[0]}/incomplete-export`);expect(denied.status()).toBe(404);expect((await denied.json()).code).toBe("RESOURCE_NOT_VISIBLE");
 }finally{await other.close();}
 }finally{
  for(const id of ids)await page.request.post(`/api/v1/meetings/${id}/cancel`,{headers:{"X-CSRF-Token":csrf},data:{reason:"隔离下载验收完成，保留历史"}});
 }
});
