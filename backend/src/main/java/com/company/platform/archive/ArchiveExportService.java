package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** 受批下载与打印：独立动作、固定版本、个人水印、原子额度和交付前再鉴权。 */
@Service
public class ArchiveExportService implements FileDomainJobs {
    /** 输出每任务128MiB、每侧缓存512MiB；大批次按300个实际输出页限制。 */
    private static final long MAX_BYTES=128L*1024*1024,CACHE_BYTES=512L*1024*1024;
    /** 两条流式交付通道限制慢客户端下载，不把多个128MiB副本同时装入服务堆。 */
    private final java.util.concurrent.Semaphore deliveries=new java.util.concurrent.Semaphore(2);
    /** 只能由本服务在完成回执后签发的进程内交付票；不接收HTTP反序列化。 */
    public static final class Delivery {
        private final Actor actor;private final UUID usage,asset,request;private final String action,sha;private final long size;
        private Delivery(Actor actor,UUID usage,UUID asset,String action,String sha,long size,UUID request){this.actor=actor;this.usage=usage;this.asset=asset;this.action=action;this.sha=sha;this.size=size;this.request=request;}
        /** 固定Content-Length，使客户端拒绝保存中途失败或截断的文件。 */ public long size(){return size;}
        /** 实际成品摘要，可由客户端独立校验。 */ public String sha(){return sha;}
    }
    /** 正式服务与短事务边界；PDF解析仅在共享的有界子进程中发生。 */
    private final AuditSecurity security;private final Sql db;private final IdentityService identity;private final ArchiveAuthority authority;private final StorageBoundaries gate;private final StorageFiles originals;private final ExportFiles outputs;private final ReadingRuntime runtime;private final DeliveryReceipts receipts;private final Audit audit;private final TransactionTemplate tx;
    public ArchiveExportService(AuditSecurity security,Sql db,IdentityService identity,ArchiveAuthority authority,StorageBoundaries gate,StorageFiles originals,ExportFiles outputs,ReadingRuntime runtime,DeliveryReceipts receipts,Audit audit,PlatformTransactionManager manager) {this.security=security;this.db=db;this.identity=identity;this.authority=authority;this.gate=gate;this.originals=originals;this.outputs=outputs;this.runtime=runtime;this.receipts=receipts;this.audit=audit;this.tx=new TransactionTemplate(manager);}
    @Override public String jobType() {return "ARCHIVE_EXPORT";}
    /** 创建固定动作任务；相同操作号先重放结果，再考虑验证令牌和打印额度。 */
    @Transactional public Map<String,Object> create(Actor actor,String action,Map<String,Object> body,UUID operation,UUID request) {
        Inputs.choice(action,"DOWNLOAD","PRINT");Inputs.only(body,"items","borrowBatchId","verificationToken","unwatermarked");
        if(!(body.getOrDefault("unwatermarked",false) instanceof Boolean unwatermarked))throw ApiFault.invalid("无水印选项格式不正确");
        identity.lockActor(actor);actor.require("ARCHIVE.ACCESS");gate.requireWritable("ARCHIVE","EXPORT");
        var input=Inputs.objects(body.get("items"),50);if(input.isEmpty()) throw ApiFault.invalid("请选择至少一份档案");
        var digestBody=new LinkedHashMap<>(body);digestBody.remove("verificationToken");String digest=Crypto.hash(Crypto.json(digestBody));
        var prior=db.one("SELECT id,request_digest FROM archive.export_request WHERE user_id=? AND action=? AND operation_id=?",actor.userId(),action,operation);
        if(prior!=null) {if(!Crypto.equal(digest,(String)prior.get("request_digest"))) throw ApiFault.conflict("同一操作号不能更换动作范围或版本");return manifest(actor,(UUID)prior.get("id"),action);}
        if(db.count("SELECT count(*) FROM archive.export_request u JOIN ops.job j ON j.id=u.job_id WHERE u.state IN ('PENDING','RUNNING') AND j.state IN ('PENDING','RUNNING') AND u.expires_at>CURRENT_TIMESTAMP AND u.user_id=?",actor.userId())>=3) throw new ApiFault(429,"EXPORT_QUEUE_FULL","本人最多同时处理3个副本任务");
        // 全局排队名额串行检查，避免多员工并发突破硬上限；不在此锁内解析PDF。
        db.rows("SELECT pg_advisory_xact_lock(61427005)");
        if(db.count("SELECT count(*) FROM archive.export_request u JOIN ops.job j ON j.id=u.job_id WHERE u.state IN ('PENDING','RUNNING') AND j.state IN ('PENDING','RUNNING') AND u.expires_at>CURRENT_TIMESTAMP")>=20) throw new ApiFault(429,"EXPORT_QUEUE_FULL","副本处理队列已满，请稍后重试");
        UUID batch=body.get("borrowBatchId")==null?null:Inputs.uuid(body.get("borrowBatchId")),usage=UUID.randomUUID(),job=UUID.randomUUID();
        Instant expires=Instant.now().plusSeconds(900);List<Map<String,Object>> items=new ArrayList<>();Set<UUID> seen=new HashSet<>();boolean sensitive=false;int outputPages=0;
        for(var item:input.stream().sorted(Comparator.comparing(v->Objects.toString(v.get("recordRevisionId")))).toList()) {
            Inputs.only(item,"recordRevisionId","pdfVersionId","pageRange","copies");UUID revision=Inputs.uuid(item.get("recordRevisionId")),pdf=Inputs.uuid(item.get("pdfVersionId"));var source=authority.published(revision,pdf);
            if(!seen.add((UUID)source.get("id"))) throw ApiFault.invalid("同一任务不能重复指定文件");
            if(action.equals("DOWNLOAD")&&(item.get("pageRange")!=null||item.get("copies")!=null)) throw ApiFault.invalid("下载不接受打印页码或份数");
            int copies=action.equals("PRINT")?Inputs.integer(item.get("copies"),1,1,100):1;Map<String,Object> chosen=null;List<Integer> pages=null;
            // 锁住实际来源后挑选足额批准；同人会话锁与行锁共同约束跨会话竞争。
            var grants=db.rows("SELECT g.* FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE g.user_id=? AND g.record_id=? AND g.record_revision_id=? AND g.pdf_version_id=? AND g.action=? AND archive.grant_valid(g.id,?) AND (NOT ? OR archive.unwatermarked_grant(g.id,g.user_id)) AND (?::uuid IS NULL OR q.batch_id=?) ORDER BY g.valid_until,g.id FOR UPDATE OF g",actor.userId(),source.get("id"),revision,pdf,action,actor.userId(),unwatermarked,batch,batch);
            for(var grant:grants) {
                List<Integer> allowed=action.equals("PRINT")?UsagePages.list(grant.get("approved_pages")):UsagePages.all(((Number)source.get("page_count")).intValue());
                try {var requested=UsagePages.parse(item.get("pageRange"),allowed);if(action.equals("PRINT")&&((Number)grant.get("approved_copies")).intValue()-((Number)grant.get("consumed_copies")).intValue()<copies) continue;chosen=grant;pages=requested;break;}
                catch(ApiFault invalid) {if(invalid.status!=400) throw invalid;}
            }
            if(chosen==null) throw ApiFault.denied();
            if(action.equals("PRINT")&&db.update("UPDATE archive.access_grant SET consumed_copies=consumed_copies+? WHERE id=? AND consumed_copies+?<=approved_copies",copies,chosen.get("id"),copies)!=1) throw ApiFault.conflict("打印份数额度不足");
            expires=expires.isBefore(Instant.parse(chosen.get("valid_until").toString()))?expires:Instant.parse(chosen.get("valid_until").toString());
            sensitive|=!"GENERAL".equals(source.get("classification"));outputPages+=pages.size()*copies;
            items.add(Sql.map("source",source,"revision",revision,"pdf",pdf,"grant",chosen,"pages",pages,"copies",copies));
        }
        if(outputPages>(action.equals("PRINT")?50:300)) throw ApiFault.invalid("单任务下载最多300页、打印最多50个实际输出页，请缩小范围");
        if(sensitive||items.size()>1) identity.consumeMany(actor,Objects.toString(body.get("verificationToken"),null),items.stream().map(item->Sql.map("objectType","ARCHIVE_REVISION","objectId",item.get("revision").toString(),"actions",List.of(action))).toList());
        // 已批准的固定清单不重复计入异常批量；实际计数由成功受理/交付审计驱动。
        security.requireAvailable(actor.userId(),action.equals("PRINT")?"BULK_PRINT":"BULK_DOWNLOAD");
        db.update("INSERT INTO archive.export_request(id,user_id,session_id,auth_version,action,operation_id,request_digest,borrow_batch_id,job_id,expires_at,watermark_name,watermark_employee_no,unwatermarked) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",usage,actor.userId(),actor.sessionId(),actor.authVersion(),action,operation,digest,batch,null,java.sql.Timestamp.from(expires),actor.user().get("name"),actor.user().get("employeeNo"),unwatermarked);
        int sequence=0;for(var item:items) {var source=map(item.get("source"));var grant=map(item.get("grant"));db.update("INSERT INTO archive.export_item(id,usage_id,sequence_no,record_id,record_revision_id,pdf_version_id,object_id,grant_id,source_version,file_no,pages,copies) VALUES(?,?,?,?,?,?,?,?,?,?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb)::integer),?)",UUID.randomUUID(),usage,++sequence,source.get("id"),item.get("revision"),item.get("pdf"),source.get("object_id"),grant.get("id"),grant.get("policy_version"),source.get("file_no"),Crypto.json(item.get("pages")),item.get("copies"));}
        db.update("INSERT INTO ops.job(id,job_type,module,requester_id,business_ref,input_version,authorization_version,idempotency_key,state,requester_session_id) VALUES(?,'ARCHIVE_EXPORT','ARCHIVE',?,?,1,?,?,'PENDING',?)",job,actor.userId(),usage.toString(),actor.authVersion(),"ARCHIVE_"+action+":"+operation,actor.sessionId());
        db.update("UPDATE archive.export_request SET job_id=? WHERE id=?",job,usage);audit.event(actor.userId(),"ARCHIVE","USAGE",usage,action+"_REQUESTED","SUCCEEDED",request);return manifest(actor,usage,action);
    }
    /** 异步生成全部水印副本；任一来源失效则整批失败，不返回部分成功包。 */
    @Override public void process(Actor actor,UUID job,UUID request) {
        if(!runtime.tryAcquire()) throw ApiFault.protection();UUID lease=UUID.randomUUID();List<Path> temporary=new ArrayList<>();List<String> written=new ArrayList<>();
        try {
            UUID usage=tx.execute(status->{identity.lockActor(actor);gate.requireWritable("ARCHIVE","EXPORT");var task=db.required("SELECT *,lease_until<CURRENT_TIMESTAMP AS expired FROM ops.job WHERE id=? AND job_type='ARCHIVE_EXPORT' FOR UPDATE",job);if(!("PENDING".equals(task.get("state"))||("RUNNING".equals(task.get("state"))&&Boolean.TRUE.equals(task.get("expired"))))) throw ApiFault.conflict("副本任务已被领取或完成");UUID id=Inputs.uuid(task.get("business_ref"));bound(actor,id,null,false);db.update("UPDATE ops.job SET state='RUNNING',lease_owner=?,lease_until=CURRENT_TIMESTAMP+interval '90 seconds',attempt_count=attempt_count+1 WHERE id=?",lease.toString(),job);db.update("UPDATE archive.export_request SET state='RUNNING',lease_token=?,lease_until=CURRENT_TIMESTAMP+interval '90 seconds',generated_at=CURRENT_TIMESTAMP WHERE id=?",lease,id);return id;});
            try {
                var usageRow=bound(actor,usage,null,false);List<Map<String,Object>> plans=items(usage);List<Map<String,Object>> assets=new ArrayList<>();long total=0;PdfRenderClient client=runtime.get();
                for(var item:plans) {
                    tx.executeWithoutResult(status->{identity.lockActor(actor);bound(actor,usage,null,false);if(db.update("UPDATE ops.job SET lease_until=CURRENT_TIMESTAMP+interval '90 seconds' WHERE id=? AND lease_owner=? AND lease_until>CURRENT_TIMESTAMP",job,lease.toString())!=1) throw ApiFault.conflict("处理租约已失效");});
                    var source=source(actor,item,(String)usageRow.get("action"));Properties plan=new Properties();plan.setProperty("name",usageRow.get("watermark_name").toString());plan.setProperty("employeeNo",usageRow.get("watermark_employee_no").toString());plan.setProperty("fileNo",item.get("file_no").toString());plan.setProperty("at",java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx").format(Instant.parse(usageRow.get("generated_at").toString()).atZone(WorkCalendarService.ZONE)));plan.setProperty("usage",usage.toString());plan.setProperty("action",usageRow.get("action").toString());plan.setProperty("pages",String.join(",",UsagePages.list(item.get("pages")).stream().map(Object::toString).toList()));plan.setProperty("copies",item.get("copies").toString());
                    plan.setProperty("unwatermarked",usageRow.get("unwatermarked").toString());
                    byte[] pdf=client.exportPdf(original(source),(String)source.get("sha256"),plan);original(source);bound(actor,usage,null,false);total+=pdf.length;if(total>MAX_BYTES) throw ApiFault.protection();Path file=Files.createTempFile(outputs.temporary(),"usage-",".pdf");temporary.add(file);Files.write(file,pdf);assets.add(Sql.map("file",file,"kind","PDF","sequence",item.get("sequence_no"),"name",item.get("file_no")+".pdf","pages",UsagePages.list(item.get("pages")).size()*((Number)item.get("copies")).intValue()));
                }
                if(plans.size()>1&&"DOWNLOAD".equals(usageRow.get("action"))) {
                    Path zip=Files.createTempFile(outputs.temporary(),"usage-",".zip");temporary.add(zip);
                    // 清单摘要来自实际生成的PDF字节，接收方可以独立核对包内文件而不依赖原件地址。
                    List<Map<String,Object>> manifestItems=new ArrayList<>();for(int index=0;index<plans.size();index++){var description=Sql.view(plans.get(index),"file_no,record_revision_id,pdf_version_id,pages,copies");Path pdf=(Path)assets.get(index).get("file");description.put("sha256",hash(pdf));description.put("byteSize",size(pdf));manifestItems.add(description);}
                    try(ZipOutputStream stream=new ZipOutputStream(Files.newOutputStream(zip),java.nio.charset.StandardCharsets.UTF_8)) {
                        for(var asset:assets){stream.putNextEntry(new ZipEntry(asset.get("name").toString()));Files.copy((Path)asset.get("file"),stream);stream.closeEntry();}
                        stream.putNextEntry(new ZipEntry("manifest.json"));stream.write(Crypto.json(Sql.map("usageId",usage.toString(),"action","DOWNLOAD","generatedAt",usageRow.get("generated_at"),"watermarkTemplateVersion",Boolean.TRUE.equals(usageRow.get("unwatermarked"))?null:1,"items",manifestItems,"notice","个人授权使用副本，不代表原件签名验证通过")).getBytes(java.nio.charset.StandardCharsets.UTF_8));stream.closeEntry();
                    }
                    if(Files.size(zip)>MAX_BYTES) throw ApiFault.protection();assets.add(Sql.map("file",zip,"kind","ZIP","sequence",0,"name","档案使用副本.zip","pages",0));
                }
                tx.executeWithoutResult(status->{identity.lockActor(actor);bound(actor,usage,null,false);gate.requireWritable("ARCHIVE","EXPORT");db.rows("SELECT pg_advisory_xact_lock(61427006)");
                    long extra=assets.stream().mapToLong(asset->size((Path)asset.get("file"))).sum();if(db.count("SELECT COALESCE(sum(byte_size),0) FROM archive.export_asset WHERE storage_state='LIVE'")+extra>CACHE_BYTES) throw new ApiFault(503,"EXPORT_CACHE_FULL","使用副本缓存已满，等待过期清理后重试");
                    if(db.count("SELECT count(*) FROM ops.job WHERE id=? AND lease_owner=? AND state='RUNNING' AND lease_until>CURRENT_TIMESTAMP",job,lease.toString())!=1) throw ApiFault.conflict("处理租约已失效");
                    for(var asset:assets) {Path file=(Path)asset.get("file");String sha=hash(file);long bytes=size(file);try(InputStream input=Files.newInputStream(file)){String key=outputs.get().receive(input,bytes,sha);written.add(key);db.update("INSERT INTO archive.export_asset(id,usage_id,lease_token,kind,sequence_no,storage_key,sha256,byte_size,media_type,purge_after) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP+interval '24 hours')",UUID.randomUUID(),usage,lease,asset.get("kind"),asset.get("sequence"),key,sha,bytes,"ZIP".equals(asset.get("kind"))?"application/zip":"application/pdf");}catch(IOException failure){throw ApiFault.protection();}}
                    bound(actor,usage,null,false);gate.requireWritable("ARCHIVE","EXPORT");db.update("UPDATE archive.export_request SET state='READY',lease_until=NULL WHERE id=? AND lease_token=?",usage,lease);db.update("UPDATE ops.job SET state='SUCCEEDED',lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",job,lease.toString());
                });written.clear();
            } catch(Exception failure) {tx.executeWithoutResult(status->{if(db.update("UPDATE ops.job SET state='FAILED',error_code='ARCHIVE_EXPORT_FAILED',lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",job,lease.toString())==1) db.update("UPDATE archive.export_request SET state='FAILED',error_code='ARCHIVE_EXPORT_FAILED',lease_until=NULL WHERE id=? AND lease_token=?",usage,lease);});throw failure instanceof RuntimeException runtimeFailure?runtimeFailure:ApiFault.protection();}
        } finally {
            // 提交结果未知时不能删除可能已被数据库引用的成品；数据库不可达则保留私有孤儿供核对。
            for(String key:written)try{if(db.count("SELECT count(*) FROM archive.export_asset WHERE storage_key=?",key)==0)outputs.get().rollbackNew(key);}catch(RuntimeException uncertain){/* 不用清理动作扩大未知提交造成的损失。 */}
            for(Path file:temporary)try{Files.deleteIfExists(file);}catch(IOException ignored){/* 仅清理已知本次临时文件，禁止扫描原件目录。 */}runtime.release();
        }
    }
    /** 只返回本人任务的动作、页数和受控地址标识，绝不返回私有键或文件系统路径。 */
    public Map<String,Object> manifest(Actor actor,UUID usage,String action) {
        var row=bound(actor,usage,action,false);var plans=items(usage);return Sql.map("usageId",usage,"jobId",row.get("job_id"),"action",row.get("action"),"unwatermarked",row.get("unwatermarked"),"state",row.get("state"),"errorCode",row.get("error_code"),"expiresAt",row.get("expires_at"),"pageCount",plans.stream().mapToInt(item->UsagePages.list(item.get("pages")).size()*((Number)item.get("copies")).intValue()).sum(),"items",plans.stream().map(item->Sql.view(item,"file_no,record_revision_id,pdf_version_id,sequence_no,pages,copies")).toList());
    }
    @Override public Map<String,Object> status(Actor actor,Map<String,Object> job) {var result=manifest(actor,Inputs.uuid(job.get("business_ref")),null);return Sql.map("id",job.get("id"),"jobType",jobType(),"state",job.get("state"),"errorCode",job.get("error_code"),"result",result);}
    /** 最近50条本人交付记录；历史存在不代表当前仍可交付。 */
    public Map<String,Object> history(Actor actor) {
        actor.require("ARCHIVE.ACCESS");
        // 一次有界查询聚合文件号与实时可打开状态，不能为50条历史记录逐一请求正文。
        return Sql.map("items",db.rows("SELECT u.id,u.action,CASE WHEN j.state IN ('FAILED','CANCELLED') THEN 'FAILED' ELSE u.state END AS state,u.created_at,u.expires_at,(SELECT array_agg(i.file_no ORDER BY i.sequence_no) FROM archive.export_item i WHERE i.usage_id=u.id) AS file_nos,(u.session_id=? AND u.auth_version=? AND u.expires_at>CURRENT_TIMESTAMP AND u.state IN ('PENDING','RUNNING','READY') AND j.state NOT IN ('FAILED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM archive.export_item i JOIN archive.access_grant g ON g.id=i.grant_id WHERE i.usage_id=u.id AND (g.policy_version<>i.source_version OR NOT archive.grant_valid(g.id,u.user_id)))) AS can_open,(SELECT count(*) FROM archive.export_delivery d WHERE d.usage_id=u.id) AS delivery_count,(SELECT f.feedback FROM archive.print_feedback f WHERE f.usage_id=u.id ORDER BY recorded_at DESC LIMIT 1) AS feedback FROM archive.export_request u LEFT JOIN ops.job j ON j.id=u.job_id WHERE u.user_id=? ORDER BY u.created_at DESC,u.id LIMIT 50",actor.sessionId(),actor.authVersion(),actor.userId()).stream().map(row->Sql.view(row,"id,action,state,created_at,expires_at,file_nos,can_open,delivery_count,feedback")).toList());
    }
    /** 内容完整校验后取得刷盘回执；返回有界字节，生成失败和失效批准均不交付部分包。 */
    public byte[] content(Actor actor,UUID usage,String action,UUID operation,UUID request) {
        ByteArrayOutputStream output=new ByteArrayOutputStream();stream(prepare(actor,usage,action,operation,request),output);return output.toByteArray();
    }
    /** HTTP开始前核验内容资格；流式回调仍会重新检查，地址不构成永久取件凭证。 */
    public Delivery prepare(Actor actor,UUID usage,String action,UUID operation,UUID request) {
        if(action.equals("DOWNLOAD"))security.requireAvailable(actor.userId(),"BULK_DOWNLOAD");
        var row=bound(actor,usage,action,true);for(var item:items(usage))original(source(actor,item,action));var asset=contentAsset(usage,action);derived(asset);if(((Number)asset.get("byte_size")).longValue()>MAX_BYTES)throw ApiFault.protection();
        // 库外回执不可用必须在HTTP成功头之前返回错误，不能以200空文件掩盖交付失败。
        authorizeDelivery(actor,row,asset,operation,request,0);return new Delivery(actor,usage,(UUID)asset.get("id"),action,asset.get("sha256").toString(),((Number)asset.get("byte_size")).longValue(),request);
    }
    /** 64KiB流式交付，每1MiB检查当前会话、动作、版本和保护事实；撤权中止后续字节。 */
    public void stream(Delivery ticket,OutputStream output) {
        if(!deliveries.tryAcquire()) throw new ApiFault(429,"DELIVERY_BUSY","交付通道繁忙，请重试原任务");
        try {
            Actor actor=ticket.actor;UUID usage=ticket.usage;String action=ticket.action;bound(actor,usage,action,true);for(var item:items(usage)) original(source(actor,item,action));var asset=contentAsset(usage,action);if(!ticket.asset.equals(asset.get("id"))||!ticket.sha.equals(asset.get("sha256")))throw ApiFault.protection();Path path=derived(asset);
            try(InputStream input=Files.newInputStream(path)) {byte[] block=new byte[65536];int count;long sinceCheck=0;Instant checked=Instant.now();while((count=input.read(block))!=-1){if(sinceCheck>=1048576||checked.plusSeconds(1).isBefore(Instant.now())){bound(actor,usage,action,true);sinceCheck=0;checked=Instant.now();}output.write(block,0,count);sinceCheck+=count;}}
            audit.event(actor.userId(),"ARCHIVE","USAGE",usage,"EXPORT_DELIVERED","SUCCEEDED",ticket.request);
        }catch(IOException failure){throw ApiFault.protection();}finally{deliveries.release();}
    }
    /** 批量打印走逐页接口，批量下载只能交付完整ZIP。 */
    private Map<String,Object> contentAsset(UUID usage,String action) {var plans=items(usage);if(action.equals("PRINT")&&plans.size()>1)throw ApiFault.invalid("批量打印请使用受控打印页面");String kind=plans.size()>1?"ZIP":"PDF";return asset(usage,kind,kind.equals("ZIP")?0:1);}
    /** 受控打印按冻结顺序逐页取图；不能通过DOWNLOAD任务调用此接口。 */
    public byte[] page(Actor actor,UUID usage,int page,UUID operation,UUID request) {
        var row=bound(actor,usage,"PRINT",true);int offset=page;if(page<1||page>300) throw ApiFault.invalid("打印页码无效");
        for(var item:items(usage)) original(source(actor,item,"PRINT"));
        for(var item:items(usage)) {int count=UsagePages.list(item.get("pages")).size()*((Number)item.get("copies")).intValue();if(offset>count){offset-=count;continue;}
            var asset=asset(usage,"PDF",((Number)item.get("sequence_no")).intValue());int selected=offset;byte[] png=runtime.withClient(client->client.renderPage(derived(asset),asset.get("sha256").toString(),selected,120));authorizeDelivery(actor,row,asset,operation,request,page);return png;
        }throw new ApiFault(416,"PAGE_OUT_OF_RANGE","打印页码超出范围");
    }
    /** 浏览器反馈只作为过程声明，不接受冒充可信打印设备的VERIFIED_PRINTED。 */
    @Transactional public Map<String,Object> feedback(Actor actor,UUID usage,Map<String,Object> body,UUID operation) {
        Inputs.only(body,"feedback","submittedAt","evidenceRef","clientJobRef");identity.lockActor(actor);bound(actor,usage,"PRINT",true);gate.requireWritable("ARCHIVE","EXPORT");
        String feedback=Inputs.choice(body.get("feedback"),"SUBMITTED","CLIENT_FAILED","UNKNOWN"),digest=Crypto.hash(Crypto.json(body));var prior=db.one("SELECT id,request_digest FROM archive.print_feedback WHERE usage_id=? AND operation_id=?",usage,operation);
        if(prior!=null) {if(!Crypto.equal(digest,(String)prior.get("request_digest"))) throw ApiFault.conflict("同一反馈操作号不能改变内容");return Sql.map("id",prior.get("id"),"source","BROWSER_CLAIM");}
        if(db.count("SELECT count(*) FROM archive.export_delivery WHERE usage_id=?",usage)==0) throw ApiFault.conflict("请先取得受控打印内容");UUID id=UUID.randomUUID();
        String evidence=optional(body,"evidenceRef",256),client=optional(body,"clientJobRef",128);Instant submitted=body.get("submittedAt")==null?null:Instant.parse(Inputs.text(body,"submittedAt",64));if(submitted!=null&&submitted.isAfter(Instant.now().plusSeconds(300))) throw ApiFault.invalid("客户端提交时间超前");
        db.update("INSERT INTO archive.print_feedback(id,usage_id,operator_id,operation_id,feedback,submitted_at,evidence_ref,client_job_ref,request_digest) VALUES(?,?,?,?,?,?,?,?,?)",id,usage,actor.userId(),operation,feedback,submitted==null?null:java.sql.Timestamp.from(submitted),evidence,client,digest);return Sql.map("id",id,"source","BROWSER_CLAIM");
    }
    /** 所有内容出口在短事务内锁定身份和批准来源，再写库外回执及数据库交付事件。 */
    private void authorizeDelivery(Actor actor,Map<String,Object> usage,Map<String,Object> asset,UUID operation,UUID request,int page) {
        tx.executeWithoutResult(status->{identity.lockActor(actor);bound(actor,(UUID)usage.get("id"),(String)usage.get("action"),true);var plans=items((UUID)usage.get("id"));UUID receipt=receipts.export(actor,(UUID)usage.get("id"),usage.get("action").toString(),plans.stream().map(item->Sql.view(item,"file_no,record_revision_id,pdf_version_id,pages,copies")).toList(),asset.get("sha256").toString(),((Number)asset.get("byte_size")).longValue(),page,request);
            db.update("INSERT INTO archive.export_delivery(id,usage_id,asset_id,operation_id,receipt_id,request_id) VALUES(?,?,?,?,?,?) ON CONFLICT(usage_id,asset_id,operation_id) DO NOTHING",UUID.randomUUID(),usage.get("id"),asset.get("id"),operation,receipt,request);audit.event(actor.userId(),"ARCHIVE","USAGE",(UUID)usage.get("id"),Boolean.TRUE.equals(usage.get("unwatermarked"))?"UNWATERMARKED_EXPORT_AUTHORIZED":"EXPORT_DELIVERY_AUTHORIZED","SUCCEEDED",request);
        });
    }
    /** 既有任务固定原来源；不因另一个批准仍有效而保活已撤销的旧副本。 */
    private Map<String,Object> bound(Actor actor,UUID usage,String action,boolean ready) {
        Actor fresh=identity.background(actor.sessionId());fresh.require("ARCHIVE.ACCESS");gate.requireReadable("ARCHIVE");var row=db.required("SELECT * FROM archive.export_request WHERE id=? AND user_id=? AND session_id=?",usage,actor.userId(),actor.sessionId());
        if(fresh.authVersion()!=((Number)row.get("auth_version")).intValue()||!Instant.parse(row.get("expires_at").toString()).isAfter(Instant.now())||(action!=null&&!action.equals(row.get("action")))) throw ApiFault.hidden();if(ready&&!"READY".equals(row.get("state"))) throw ApiFault.conflict("水印使用副本尚未生成完成");for(var item:items(usage)) source(fresh,item,(String)row.get("action"));return row;
    }
    /** 当前正式PDF、原批准动作和来源代次必须全部一致，额度只在受理时消费。 */
    private Map<String,Object> source(Actor actor,Map<String,Object> item,String action) {
        var source=authority.published((UUID)item.get("record_revision_id"),(UUID)item.get("pdf_version_id"));String lock=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()?" FOR SHARE":"";var grant=db.required("SELECT * FROM archive.access_grant WHERE id=? AND user_id=? AND action=? AND policy_version=? AND record_id=? AND record_revision_id=? AND pdf_version_id=? AND archive.grant_valid(id,?)"+lock,item.get("grant_id"),actor.userId(),action,item.get("source_version"),item.get("record_id"),item.get("record_revision_id"),item.get("pdf_version_id"),actor.userId());
        if(!source.get("id").equals(item.get("record_id"))||!source.get("object_id").equals(item.get("object_id"))||!Instant.parse(grant.get("valid_until").toString()).isAfter(Instant.now())) throw ApiFault.denied();
        if(Boolean.TRUE.equals(item.get("unwatermarked"))&&!Boolean.TRUE.equals(db.required(
            "SELECT archive.unwatermarked_grant(?,?) AS approved",grant.get("id"),actor.userId()).get("approved")))throw ApiFault.denied();
        return source;
    }
    /** 冻结清单以序号排序，生成、ZIP清单和打印页面使用同一顺序。 */
    private List<Map<String,Object>> items(UUID usage) {return db.rows("SELECT i.*,u.unwatermarked FROM archive.export_item i JOIN archive.export_request u ON u.id=i.usage_id WHERE i.usage_id=? ORDER BY i.sequence_no",usage);}
    /** 只取当前成功租约的成品，重试工作器的历史输出不能混入本次交付。 */
    private Map<String,Object> asset(UUID usage,String kind,int sequence) {return db.required("SELECT a.* FROM archive.export_asset a JOIN archive.export_request u ON u.id=a.usage_id AND u.lease_token=a.lease_token WHERE a.usage_id=? AND a.kind=? AND a.sequence_no=? AND a.storage_state='LIVE'",usage,kind,sequence);}
    /** 原件两侧均重新测量大小与摘要，任一损坏即拒绝生成和交付。 */
    private Path original(Map<String,Object> source) {return originals.get().verifiedPrimary(source.get("storage_key").toString(),((Number)source.get("byte_size")).longValue(),source.get("sha256").toString());}
    /** 派生副本也需要双侧验证，不能仅检查原件就交付损坏成品。 */
    private Path derived(Map<String,Object> asset) {return outputs.get().verifiedPrimary(asset.get("storage_key").toString(),((Number)asset.get("byte_size")).longValue(),asset.get("sha256").toString());}
    /** 返回字节单位的实存大小；文件消失不作为零长度成功。 */
    private long size(Path path) {try{return Files.size(path);}catch(IOException failure){throw ApiFault.protection();}}
    /** 固定64KiB缓冲计算成品摘要，不把完整副本装入内存。 */
    private String hash(Path path) {try(InputStream input=Files.newInputStream(path)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];int count;while((count=input.read(block))!=-1)digest.update(block,0,count);return HexFormat.of().formatHex(digest.digest());}catch(Exception failure){throw ApiFault.protection();}}
    /** 客户端声明字段有明确长度上限，并保持空值语义。 */
    private String optional(Map<String,Object> body,String key,int limit) {return body.get(key)==null?null:Inputs.text(body,key,limit);}
    /** 内部计划由本服务构造，禁止将任意JSON直接当作源文件元数据。 */
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value) {return (Map<String,Object>)value;}
}
