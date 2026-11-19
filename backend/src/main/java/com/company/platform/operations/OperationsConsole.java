package com.company.platform.operations;

import com.company.platform.*;
import com.company.platform.storage.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 技术运维只处理最小状态与固定任务，不向IT返回档案正文、路径或数据库凭据。 */
@Service
public class OperationsConsole {
 private final Sql db;private final IdentityService identity;private final Audit audit;private final StorageFiles files;private final StorageBoundaries boundary;private final ObjectProvider<SignedProtectionGate> protection;private final OfficeGateway office;private final FileScanEngine scanner;private final ReadingRuntime reading;private final TransactionTemplate tx;private final boolean enabled,scheduleEnabled;private final String probeRoot;
 /** 每进程固定领取标识；重启不冒充旧执行器，过期任务进入人工核对。 */
 private final UUID worker=UUID.randomUUID();
 private static final String RUN_FIELDS="id,kind,state,requester_id,reason,source_backup_id,parent_run_id,created_at,started_at,finished_at,checked_count,failed_count,total_count,error_code,row_version";
 public OperationsConsole(Sql db,IdentityService identity,Audit audit,StorageFiles files,StorageBoundaries boundary,ObjectProvider<SignedProtectionGate> protection,OfficeGateway office,FileScanEngine scanner,ReadingRuntime reading,PlatformTransactionManager manager,Environment env){this.db=db;this.identity=identity;this.audit=audit;this.files=files;this.boundary=boundary;this.protection=protection;this.office=office;this.scanner=scanner;this.reading=reading;tx=new TransactionTemplate(manager);enabled=env.getProperty("platform.operations.enabled",Boolean.class,false);scheduleEnabled=env.getProperty("platform.operations.schedule-enabled",Boolean.class,true);probeRoot=env.getProperty("platform.operations.probe-directory","");}
 /** 每项操作另验对应能力，查看运维状态不会自动获得备份或恢复权。 */
 public static String permission(String kind){return "PLATFORM."+switch(kind){case "BACKUP"->"BACKUP_MANAGE";case "RESTORE_VERIFY"->"RESTORE_VERIFY";default->"OPERATIONS_MANAGE";};}
 private Actor live(Actor subject){Actor actor=identity.background(subject.sessionId());actor.require("PLATFORM.OPERATIONS_MANAGE");return actor;}
 /** 状态页只读取既有记录，耗时探测和文件摘要不在HTTP线程中执行。 */
 public Map<String,Object> overview(Actor subject){live(subject);var gate=protection.getIfUnique();
  return Sql.map("enabled",enabled,"protection",gate==null?Sql.map("ready",false,"mode","NOT_CONFIGURED"):gate.status(),"office",office.status(),"storage",db.required("SELECT count(*) AS objects,COALESCE(sum(byte_size),0) AS bytes FROM storage.object WHERE object_kind='ORIGINAL' AND state IN ('READY','RETAINED')"),"queues",db.rows("SELECT kind,state,count(*) AS count FROM ops.maintenance_run GROUP BY kind,state"),"latestCheck",db.one("SELECT summary::text,finished_at FROM ops.maintenance_run WHERE kind='PROTECTION_CHECK' AND finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1"),"backupPlan",Sql.view(db.required("SELECT * FROM ops.backup_plan"),"enabled,hour_shanghai,retention_days,row_version"),"latestBackup",db.one("SELECT id,verified_at,retain_until,table_count,object_count FROM ops.backup_point ORDER BY created_at DESC LIMIT 1"));
 }
 /** 固定分页和投影避免运维列表泄露任务载荷、原件名及本地文件键。 */
 public Map<String,Object> list(Actor subject,String section,Map<String,String> query){
  live(subject);
  Inputs.only(query,"page","state","number","kind","module","error","createdFrom","createdTo","verifiedFrom","verifiedTo","retainedFrom","retainedTo","checkedFrom","checkedTo");
  int page=Inputs.integer(query.get("page"),1,1,100000);
  String table,columns,order,where=" WHERE true";
  var args=new ArrayList<Object>();
  String state=query.get("state");
  if(state!=null&&!section.equals("backups")){where+=" AND state=?";args.add(Inputs.text(query,"state",24));}
  var filters=new ListConditions(query,args);
  switch(section){
   case "runs" -> {
    table="ops.maintenance_run";columns=RUN_FIELDS;order="created_at DESC,id";
    filters.contains("number","id::text").choice("kind","kind","PROTECTION_CHECK","REPLICA_CHECK","BACKUP","RESTORE_VERIFY").dates("created","created_at");
   }
   case "backups" -> {
    table="ops.backup_point";columns="id,created_at,verified_at,retain_until,table_count,object_count,file_count,scope";order="created_at DESC,id";
    filters.contains("number","id::text").dates("verified","verified_at").dates("retained","retain_until");
   }
   case "replicas" -> {
    table="ops.replica_check";columns="object_id,run_id,state,checked_at,byte_size,error_code";order="checked_at DESC,object_id";
    filters.contains("number","object_id::text").contains("error","error_code").dates("checked","checked_at");
   }
   case "jobs" -> {
    table="ops.job";columns="id,job_type,module,state,attempt_count,next_run_at,lease_until,error_code";order="id DESC";
    filters.contains("number","id::text").contains("error","error_code")
        .choice("module","module","ARCHIVE","DRIVE")
        .choice("kind","job_type","ARCHIVE_EXPORT","ARCHIVE_PREVIEW","SCAN_FILE_CHECK","PREVIEW_METADATA","FILE_ASSEMBLY_CHECK");
   }
   default -> throw ApiFault.hidden();
  }
  where+=filters.sql();
  long total=db.count("SELECT count(*) FROM "+table+where,args.toArray());
  args.add((page-1)*20);
  return Sql.map("items",db.rows("SELECT "+columns+" FROM "+table+where+" ORDER BY "+order+" LIMIT 20 OFFSET ?",args.toArray()).stream().map(r->{var item=Sql.view(r,columns);if(item.get("errorCode") instanceof String errorCode)item.put("errorMessage",SystemFailure.taskMessage(errorCode));return item;}).toList(),"page",page,"total",total);
 }
 /** 创建固定任务，不接受文件路径、命令、数据库名或客户端提交的检查结果。 */
 public Map<String,Object> create(Commands.Context ctx,Map<String,Object> body,UUID parent){Inputs.only(body,"kind","reason","sourceBackupId");String kind=Inputs.choice(body.get("kind"),"PROTECTION_CHECK","REPLICA_CHECK","BACKUP","RESTORE_VERIFY");ctx.actor().require(permission(kind));String reason=Inputs.text(body,"reason",1000);UUID source=body.get("sourceBackupId")==null?null:Inputs.uuid(body.get("sourceBackupId"));if(!enabled)throw new ApiFault(503,"OPERATIONS_DISABLED","本环境未启用维护执行");
  if(kind.equals("RESTORE_VERIFY")){if(source==null)throw ApiFault.invalid("请选择已验证备份");db.required("SELECT id FROM ops.backup_point WHERE id=?",source);}else if(source!=null)throw ApiFault.invalid("只有恢复演练可以指定备份");
  if(db.count("SELECT count(*) FROM ops.maintenance_run WHERE state IN ('QUEUED','WAITING_WINDOW','RUNNING')")>=20)throw new ApiFault(429,"OPERATIONS_QUEUE_FULL","维护队列已满，请先核对已有任务");
  UUID id=UUID.randomUUID();String state=Set.of("BACKUP","RESTORE_VERIFY").contains(kind)?"WAITING_WINDOW":"QUEUED";
  db.update("INSERT INTO ops.maintenance_run(id,kind,state,requester_id,auth_version,reason,source_backup_id,parent_run_id) VALUES(?,?,?,?,?,?,?,?)",id,kind,state,ctx.actor().userId(),ctx.actor().authVersion(),reason,source,parent);return run(ctx.actor(),id);
 }
 public Map<String,Object> run(Actor actor,UUID id){live(actor);var row=db.required("SELECT *,summary::text AS report FROM ops.maintenance_run WHERE id=?",id);var result=Sql.view(row,RUN_FIELDS);result.put("summary",Crypto.JSON.readValue(row.get("report").toString(),Map.class));return result;}
 /** 失败任务重试产生新记录和新目录，不覆盖原失败证据；业务文件任务由原业务入口重试。 */
 public Map<String,Object> retry(Commands.Context ctx,UUID id,Map<String,Object> body){Inputs.only(body,"reason");var row=db.required("SELECT * FROM ops.maintenance_run WHERE id=? FOR UPDATE",id);Commands.version(ctx,row);if(!Set.of("FAILED","MANUAL","CANCELLED").contains(row.get("state")))throw ApiFault.conflict("只有失败或已停止的任务可以重新申请");var next=Sql.map("kind",row.get("kind"),"reason",Inputs.text(body,"reason",1000));if(row.get("source_backup_id")!=null)next.put("sourceBackupId",row.get("source_backup_id").toString());return create(ctx,next,id);}
 public Map<String,Object> cancel(Commands.Context ctx,UUID id){var row=db.required("SELECT * FROM ops.maintenance_run WHERE id=? FOR UPDATE",id);ctx.actor().require(permission(row.get("kind").toString()));Commands.version(ctx,row);if(!Set.of("QUEUED","WAITING_WINDOW").contains(row.get("state")))throw ApiFault.conflict("执行中的任务须先核对实际结果，不能直接取消");db.update("UPDATE ops.maintenance_run SET state='CANCELLED',finished_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=?",id);return run(ctx.actor(),id);}
 /** 默认三十天，仅控制新备份保留期限；不提供自动删除和正式入口切换。 */
 public Map<String,Object> plan(Commands.Context ctx,Map<String,Object> body){Inputs.only(body,"enabled","hourShanghai","retentionDays","reason");ctx.actor().require("PLATFORM.BACKUP_MANAGE");Inputs.text(body,"reason",1000);if(!(body.get("enabled") instanceof Boolean active))throw ApiFault.invalid("请选择计划启用状态");int hour=Inputs.integer(body.get("hourShanghai"),2,0,23),days=Inputs.integer(body.get("retentionDays"),30,1,3650);Commands.version(ctx,db.required("SELECT * FROM ops.backup_plan FOR UPDATE"));db.update("UPDATE ops.backup_plan SET enabled=?,hour_shanghai=?,retention_days=?,changed_by=?,row_version=row_version+1",active,hour,days,ctx.actor().userId());return Sql.map("id",ApiController.COLLECTION);}
 /** 领取与每批确认均核验账号和权限代次，撤权不能被排队任务绕过。 */
 public boolean authorized(Map<String,Object> run){return db.count("SELECT count(*) FROM iam.user_account u WHERE u.id=? AND u.status='ACTIVE' AND NOT u.password_setup_required AND u.auth_version=? AND EXISTS(SELECT 1 FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id JOIN iam.role_permission rp ON rp.role_id=r.id JOIN iam.permission p ON p.id=rp.permission_id WHERE ur.user_id=u.id AND ur.state='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP) AND r.status='ACTIVE' AND rp.scope_policy='COMPANY' AND rp.effect='ALLOW' AND p.module=r.module AND p.code=?) AND NOT EXISTS(SELECT 1 FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id JOIN iam.role_permission rp ON rp.role_id=r.id JOIN iam.permission p ON p.id=rp.permission_id AND p.module=r.module WHERE ur.user_id=u.id AND ur.state='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP) AND r.status='ACTIVE' AND rp.scope_policy='COMPANY' AND rp.effect='DENY' AND p.code=?) AND NOT EXISTS(SELECT 1 FROM iam.permission_assignment pa JOIN iam.permission p ON p.id=pa.permission_id WHERE pa.user_id=u.id AND pa.state='ACTIVE' AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP) AND pa.effect='DENY' AND p.code=?)",run.get("requester_id"),run.get("auth_version"),permission(run.get("kind").toString()),permission(run.get("kind").toString()),permission(run.get("kind").toString()))==1;}
 /** 每轮最多二十个原件，I/O不持有数据库事务；文件处理使用已有64KiB流式摘要。 */
 @Scheduled(fixedDelayString="${platform.operations.delay-ms:3000}") public void scheduled(){if(scheduleEnabled)tick();}
 public void tick(){if(!enabled)return;try{
  tx.executeWithoutResult(status->{for(var run:db.rows("UPDATE ops.maintenance_run SET state='MANUAL',error_code='EXECUTION_RESULT_UNKNOWN',finished_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE state='RUNNING' AND lease_until<CURRENT_TIMESTAMP RETURNING *"))notice(run,"OPS_TASK");});
  var run=tx.execute(status->{var row=db.one("SELECT * FROM ops.maintenance_run WHERE kind IN ('PROTECTION_CHECK','REPLICA_CHECK') AND (state='QUEUED' OR (state='RUNNING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP)) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",worker);if(row==null)return null;if(!authorized(row)){finish(row,"FAILED","REQUESTER_AUTHORIZATION_INVALID",Map.of());return null;}if(row.get("state").equals("QUEUED"))db.update("UPDATE ops.maintenance_run SET state='RUNNING',started_at=CURRENT_TIMESTAMP,lease_token=?,lease_until=CURRENT_TIMESTAMP+interval '30 minutes',total_count=(SELECT count(*) FROM storage.object WHERE object_kind='ORIGINAL' AND state IN ('READY','RETAINED') AND created_at<=?::timestamptz),row_version=row_version+1 WHERE id=?",worker,row.get("created_at"),row.get("id"));return db.required("SELECT * FROM ops.maintenance_run WHERE id=?",row.get("id"));});
  if(run==null)return;
  if(run.get("kind").equals("PROTECTION_CHECK")){Map<String,Object> report=probe();tx.executeWithoutResult(status->{finish(run,"SUCCEEDED",null,report);if(!"READY".equals(report.get("health")))notice(run,"OPS_PROTECTION");});}
  else {var batch=run;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);for(int n=0;n<20&&System.nanoTime()<deadline;n++){if(!checkReplica(batch))break;batch=db.required("SELECT * FROM ops.maintenance_run WHERE id=?",run.get("id"));}}
 }catch(RuntimeException error){FailureDiagnostics.log(UUID.randomUUID(),"OPERATIONS_CHECK",ApiErrors.classify(error));}}
 /** 状态探测不签发保护证据；界面绝不能点击把失败状态改成NORMAL。 */
 private Map<String,Object> probe(){Map<String,Object> report=new LinkedHashMap<>();var gate=protection.getIfUnique();report.put("protection",gate==null?Sql.map("ready",false,"mode","NOT_CONFIGURED"):gate.status());for(String module:List.of("DRIVE","ARCHIVE")){boolean read=true,write=true;try{boundary.requireReadable(module);}catch(RuntimeException e){read=false;}try{boundary.requireWritable(module,"OPERATIONS_CHECK");}catch(RuntimeException e){write=false;}report.put(module,Sql.map("readable",read,"writable",write));}
  boolean storage=true;try{files.get();}catch(RuntimeException e){storage=false;}report.put("storage",storage?"ROOTS_VERIFIED":"NOT_READY");String officeState="NOT_CONFIGURED";if(Boolean.TRUE.equals(office.status().get("enabled")))try{office.verifyVersion();officeState="VERIFIED";}catch(RuntimeException e){officeState="NOT_READY";}report.put("office",officeState);
  String scan="NOT_CONFIGURED",pdf="NOT_CONFIGURED";if(!probeRoot.isBlank()){Path file=null;try{file=Files.createTempFile(PrivateFiles.directory(probeRoot),"ops-probe-",".pdf");Files.writeString(file,probeDocument(),java.nio.charset.StandardCharsets.US_ASCII);scan=scanner.scan(file).name();final Path source=file;String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));try{pdf=reading.withClient(client->client.pageCount(source,digest))==1?"VERIFIED":"NOT_READY";}catch(RuntimeException e){pdf="NOT_READY";}}catch(Exception e){scan="NOT_READY";}finally{if(file!=null)try{Files.deleteIfExists(file);}catch(Exception ignored){}}}report.put("scanner",scan);report.put("pdf",pdf);
  boolean ready=List.of("DRIVE","ARCHIVE").stream().allMatch(m->Boolean.TRUE.equals(((Map<?,?>)report.get(m)).get("writable")))&&storage&&gate!=null&&Boolean.TRUE.equals(gate.status().get("ready"))&&"VERIFIED".equals(officeState)&&"CLEAN".equals(scan)&&"VERIFIED".equals(pdf);report.put("health",ready?"READY":"DEGRADED");report.put("checkedAt",Instant.now().toString());return report;
 }
 /** 固定空白PDF探针不含外部输入，解析和渲染仍通过原有隔离子进程。 */
 private String probeDocument(){StringBuilder pdf=new StringBuilder("%PDF-1.4\n");List<Integer> offsets=new ArrayList<>();String[] objects={"<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Contents 4 0 R >>","<< /Length 0 >>\nstream\nendstream"};for(int i=0;i<objects.length;i++){offsets.add(pdf.length());pdf.append(i+1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");}int xref=pdf.length();pdf.append("xref\n0 5\n0000000000 65535 f \n");for(int offset:offsets)pdf.append(String.format(Locale.ROOT,"%010d 00000 n \n",offset));return pdf.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n").toString();}
 private boolean checkReplica(Map<String,Object> run){var object=db.one("SELECT id,storage_key,byte_size,sha256 FROM storage.object WHERE object_kind='ORIGINAL' AND state IN ('READY','RETAINED') AND created_at<=?::timestamptz AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT 1",run.get("created_at"),run.get("cursor_id"),run.get("cursor_id"));if(object==null){tx.executeWithoutResult(status->{var current=db.required("SELECT * FROM ops.maintenance_run WHERE id=? FOR UPDATE",run.get("id"));boolean failed=((Number)current.get("failed_count")).longValue()>0;finish(run,failed?"FAILED":"SUCCEEDED",failed?"REPLICA_MISMATCH":null,Sql.map("checked",current.get("checked_count"),"failed",current.get("failed_count")));if(failed)notice(run,"OPS_REPLICA");});return false;}
  boolean passed;try{passed=files.get().verified(object.get("storage_key").toString(),((Number)object.get("byte_size")).longValue(),object.get("sha256").toString());}catch(RuntimeException e){passed=false;}final boolean verified=passed;
  tx.executeWithoutResult(status->{var current=db.required("SELECT * FROM ops.maintenance_run WHERE id=? FOR UPDATE",run.get("id"));if(!worker.equals(current.get("lease_token"))||!"RUNNING".equals(current.get("state")))return;if(!authorized(current)){finish(current,"FAILED","REQUESTER_AUTHORIZATION_INVALID",Map.of());return;}db.update("INSERT INTO ops.replica_check(object_id,run_id,state,byte_size,sha256,error_code) VALUES(?,?,?,?,?,?) ON CONFLICT(object_id) DO UPDATE SET run_id=EXCLUDED.run_id,state=EXCLUDED.state,checked_at=CURRENT_TIMESTAMP,byte_size=EXCLUDED.byte_size,sha256=EXCLUDED.sha256,error_code=EXCLUDED.error_code",object.get("id"),run.get("id"),verified?"PASSED":"FAILED",object.get("byte_size"),object.get("sha256"),verified?null:"REPLICA_MISMATCH");db.update("UPDATE ops.maintenance_run SET checked_count=checked_count+1,failed_count=failed_count+?,cursor_id=?,lease_until=CURRENT_TIMESTAMP+interval '30 minutes',row_version=row_version+1 WHERE id=?",verified?0:1,object.get("id"),run.get("id"));});return true;
 }
 private void finish(Map<String,Object> run,String state,String error,Map<String,Object> report){db.update("UPDATE ops.maintenance_run SET state=?,error_code=?,summary=?::jsonb,finished_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,row_version=row_version+1 WHERE id=?",state,error,Crypto.json(report),run.get("id"));audit.event((UUID)run.get("requester_id"),"PLATFORM","MAINTENANCE_RUN",(UUID)run.get("id"),"OPERATIONS_RESULT",state,UUID.randomUUID());}
 /** 固定类别告警沿用事务发件箱；同账号同类一小时最多一条，不投递报告内容。 */
 private void notice(Map<String,Object> run,String kind){db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,61427041))",kind);if(db.count("SELECT count(*) FROM ops.security_alert WHERE user_id=? AND kind=? AND created_at>CURRENT_TIMESTAMP-interval '1 hour'",run.get("requester_id"),kind)>0)return;UUID id=UUID.randomUUID();db.update("INSERT INTO ops.security_alert(id,user_id,kind,observed_count,threshold_count) VALUES(?,?,?,1,1)",id,run.get("requester_id"),kind);db.update("INSERT INTO ops.account_message(id,user_id,kind,channel,expires_at) VALUES(?,?,?,'DINGTALK',CURRENT_TIMESTAMP+interval '1 day')",id,run.get("requester_id"),kind);}
 /** 每日只排一个维护任务；运行时点由停机窗口决定，不把排队当作已经完成备份。 */
 @Scheduled(fixedDelay=60000) public void scheduledBackup(){if(scheduleEnabled)scheduleBackup();}
 public void scheduleBackup(){if(!enabled)return;tx.executeWithoutResult(status->{var plan=db.required("SELECT * FROM ops.backup_plan FOR UPDATE");if(!Boolean.TRUE.equals(plan.get("enabled")))return;var now=ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));if(now.getHour()<((Number)plan.get("hour_shanghai")).intValue())return;var user=db.required("SELECT id,auth_version FROM iam.user_account WHERE id=?",plan.get("changed_by"));var candidate=Sql.map("requester_id",user.get("id"),"auth_version",user.get("auth_version"),"kind","BACKUP");if(!authorized(candidate))return;UUID id=UUID.randomUUID();if(db.update("INSERT INTO ops.maintenance_run(id,kind,state,requester_id,auth_version,reason,schedule_key) VALUES(?,'BACKUP','WAITING_WINDOW',?,?,'每日独立备份计划',?) ON CONFLICT(schedule_key) DO NOTHING",id,user.get("id"),user.get("auth_version"),"BACKUP:"+now.toLocalDate())==1)audit.event((UUID)user.get("id"),"PLATFORM","MAINTENANCE_RUN",id,"SCHEDULE_BACKUP","SUCCEEDED",UUID.randomUUID());});}
}
