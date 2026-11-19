#!/usr/bin/env node
"use strict";
/** Windows独立维护调度：读管理配置、核验服务、停机执行并在finally恢复服务。 */
const fs = require("node:fs"), path = require("node:path"), child = require("node:child_process"), assert = require("node:assert/strict"), crypto = require("node:crypto");
const maintenance = require("./maintenance.cjs"), runner = require("./operations-runner.cjs");
/** 固定映射，数据库里的其他字段不能变成环境变量或执行命令。 */
const KEYS = {
 "platform.storage.primary":"IMS_PLATFORM_STORAGE_PRIMARY", "platform.storage.secondary":"IMS_PLATFORM_STORAGE_SECONDARY",
 "platform.reading.audit-root":"IMS_PLATFORM_READING_AUDIT", "platform.export.primary":"IMS_PLATFORM_EXPORT_PRIMARY", "platform.export.secondary":"IMS_PLATFORM_EXPORT_SECONDARY",
 "platform.scanner.executable":"IMS_PLATFORM_SCANNER_EXE", "platform.scanner.database":"IMS_PLATFORM_SCANNER_DATABASE", "platform.scanner.temporary":"IMS_PLATFORM_SCANNER_TEMP",
 "platform.scanner.executable-sha256":"IMS_PLATFORM_SCANNER_SHA256", "platform.operations.enabled":"IMS_PLATFORM_OPERATIONS_ENABLED",
 "platform.maintenance.postgres-bin":"IMS_BACKUP_POSTGRES_BIN", "platform.maintenance.backup-root":"IMS_OPS_RUN_ROOT", "platform.maintenance.restore-port":"IMS_OPS_RESTORE_PORT",
 "platform.office.inbox-key-file":"IMS_PLATFORM_OFFICE_INBOX_KEY", "platform.office.outbox-key-file":"IMS_PLATFORM_OFFICE_OUTBOX_KEY",
 "platform.protection.key-file":"IMS_PLATFORM_PROTECTION_KEY"
};
function context(config, bin) {
 const url = new URL(config.IMS_PLATFORM_DB_URL.replace(/^jdbc:/,""));
 assert.equal(url.protocol,"postgresql:");assert.ok(["127.0.0.1","localhost"].includes(url.hostname));assert.match(url.pathname,/^\/[A-Za-z0-9_]+$/);
 assert.ok(config.IMS_PLATFORM_DB_USER && config.IMS_PLATFORM_DB_PASSWORD);
 return {config,url,name:url.pathname.slice(1),bin:maintenance.safe(bin),env:{...process.env,PGPASSWORD:config.IMS_PLATFORM_DB_PASSWORD,PGCLIENTENCODING:"UTF8",PGOPTIONS:"-c timezone=UTC -c DateStyle=ISO,YMD"}};
}
function service(name,action) {
 assert.match(name,/^[A-Za-z0-9_.-]{1,128}$/);
 const result=child.spawnSync("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",["-NoProfile","-NonInteractive","-File",path.join(__dirname,"Set-OperationsService.ps1"),"-ServiceName",name,"-Action",action],{windowsHide:true,encoding:"utf8",timeout:90000});
 assert.equal(result.status,0,"应用Windows服务操作失败："+action);return result.stdout.trim();
}
async function main(argv) {
 assert.equal(process.platform,"win32","自动停机执行器仅支持Windows服务部署");
 assert.equal(argv.length,4);assert.equal(argv[0],"--config");assert.equal(argv[2],"--postgres-bin");
 const bootstrap=maintenance.safe(argv[1]), config=JSON.parse(fs.readFileSync(bootstrap,"utf8")), initial=context(config,argv[3]);
 const rows=JSON.parse(maintenance.sql(initial,"SELECT COALESCE(json_agg(settings),'[]'::json) FROM ops.deployment_settings")), managed=Object.assign({},...rows);
 for(const [key,target] of Object.entries(KEYS))if(Object.hasOwn(managed,key))config[target]=String(managed[key]);
 const restoreFile=managed["platform.maintenance.restore-credentials-file"];
 if(restoreFile){config.IMS_MAINTENANCE_RESTORE_FILE=restoreFile;const restore=JSON.parse(fs.readFileSync(maintenance.safe(restoreFile),"utf8"));for(const key of ["IMS_RESTORE_DB_URL","IMS_RESTORE_DB_USER","IMS_RESTORE_DB_PASSWORD","IMS_RESTORE_APP_USER","IMS_RESTORE_APP_PASSWORD"])if(restore[key])config[key]=restore[key];}
 config.IMS_BACKUP_POSTGRES_BIN ||= argv[3];config.IMS_BACKUP_SOURCE_DATABASE=initial.name;config.IMS_MAINTENANCE_BOOTSTRAP_FILE=bootstrap;
 const ctx=context(config,config.IMS_BACKUP_POSTGRES_BIN), name=managed["platform.maintenance.windows-service"];
 const heartbeat=(state,code=null)=>maintenance.sql(ctx,`INSERT INTO ops.maintenance_agent(id,state,error_code) VALUES(true,'${state}',${code?"'"+code+"'":"NULL"}) ON CONFLICT(id) DO UPDATE SET seen_at=CURRENT_TIMESTAMP,state=EXCLUDED.state,error_code=EXCLUDED.error_code`);
 let stopped=false,effective;
 try {
  assert.equal(config.IMS_PLATFORM_OPERATIONS_ENABLED,"true","页面尚未启用维护任务");
  assert.equal(service(name,"Status"),"Running","应用服务未运行，执行器不擅自改变停机状态");
  const roots=maintenance.settingsFromConfig(config);
  assert.ok(config.IMS_OPS_RUN_ROOT);const root=maintenance.safe(config.IMS_OPS_RUN_ROOT);
  assert.ok(fs.existsSync(root),"备份输出目录不存在，请先从页面检查运行环境");
  for(const source of Object.values(roots.roots))assert.ok(!maintenance.inside(source,root)&&!maintenance.inside(root,source));
  // 配置和程序校验通过才报心跳；没有待执行任务时不停止应用。
  heartbeat("READY");
  const pending=maintenance.sql(ctx,"SELECT count(*) FROM ops.maintenance_run WHERE kind IN ('BACKUP','RESTORE_VERIFY') AND state='WAITING_WINDOW'");
  if(Number(pending)===0)return {state:"IDLE"};
  const working=path.join(path.dirname(bootstrap),"ims-agent-private");if(!fs.existsSync(working))maintenance.privateDirectory(working);
  effective=path.join(working,crypto.randomUUID()+".json");fs.writeFileSync(effective,JSON.stringify(config),{flag:"wx",mode:0o600});
  // 目录继承私有ACL，命令行和日志均不包含数据库密码。
  stopped=true;service(name,"Stop");
  assert.equal(await maintenance.listening(config.IMS_PLATFORM_PORT),false,"服务已停止但应用端口仍有其他进程，拒绝备份");
  return await runner.main(["--config",effective]);
 }catch(failure){
  heartbeat("NOT_READY","MAINTENANCE_AGENT_FAILED");
  const logs=path.join(path.dirname(bootstrap),"ims-agent-private");if(!fs.existsSync(logs))maintenance.privateDirectory(logs);
  fs.appendFileSync(path.join(logs,"agent.log"),JSON.stringify({at:new Date().toISOString(),code:"MAINTENANCE_AGENT_FAILED",name:failure.name,message:failure.message})+"\n",{mode:0o600});throw failure;
 }
 finally{
  try{if(stopped)service(name,"Start");}finally{if(effective)fs.unlinkSync(effective);}
 }
}
if(require.main===module)main(process.argv.slice(2)).then(result=>{process.stdout.write(JSON.stringify(result)+"\n");if(result.state==="FAILED")process.exitCode=1;}).catch(error=>{console.error("MAINTENANCE_AGENT_FAILED: "+error.message);process.exitCode=1;});
module.exports={context,KEYS};
