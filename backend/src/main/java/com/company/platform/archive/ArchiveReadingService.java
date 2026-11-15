package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** 档案个人水印阅读；每页固定原批准或候选职责，拒绝跨人、跨会话、跨来源和跨版本复用。 */
@Service
public class ArchiveReadingService implements FileDomainJobs {
    /** 共享文件、隔离进程和库外回执；正文只在受限内存中流转。 */
    private final Sql db;private final IdentityService identity;private final ArchiveAuthority authority;private final StorageBoundaries protection;private final StorageFiles files;private final ReadingRuntime runtime;private final DeliveryReceipts receipts;private final Audit audit;private final TransactionTemplate tx;
    public ArchiveReadingService(Sql db,IdentityService identity,ArchiveAuthority authority,StorageBoundaries protection,StorageFiles files,ReadingRuntime runtime,DeliveryReceipts receipts,Audit audit,PlatformTransactionManager manager) {this.db=db;this.identity=identity;this.authority=authority;this.protection=protection;this.files=files;this.runtime=runtime;this.receipts=receipts;this.audit=audit;this.tx=new TransactionTemplate(manager);}
    /** 档案专用技术任务类型，由单一领域处理器承接。 */
    @Override public String jobType() {return "ARCHIVE_PREVIEW";}
    /** 普通阅读与候选检查严格二选一，202只说明已建立本人技术任务。 */
    @Transactional public Map<String,Object> create(Actor actor,Map<String,Object> body,UUID operation,UUID request) {
        Inputs.only(body,"recordRevisionId","pdfVersionId","reviewContext","unwatermarked");
        if(!(body.getOrDefault("unwatermarked",false) instanceof Boolean unwatermarked))throw ApiFault.invalid("无水印选项格式不正确");
        identity.lockActor(actor);actor.require("ARCHIVE.ACCESS");protection.requireReadable("ARCHIVE");
        String key="ARCHIVE_VIEW:"+operation,digest=Crypto.hash(Crypto.json(body));db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.userId()+":"+key);
        var prior=db.one("SELECT j.business_ref,u.request_digest FROM ops.job j JOIN archive.usage_request u ON j.business_ref=u.id::text WHERE j.requester_id=? AND j.job_type='ARCHIVE_PREVIEW' AND j.idempotency_key=?",actor.userId(),key);
        if(prior!=null) {if(!Crypto.equal(digest,(String)prior.get("request_digest"))) throw ApiFault.conflict("同一阅读操作号不能指向不同版本");return manifest(actor,Inputs.uuid(prior.get("business_ref")));}
        UUID revision,pdf,context=null,grant=null,policy=null;String contextType=null;int sourceVersion;Map<String,Object> source;Instant expires=Instant.now().plusSeconds(900);
        if(body.get("reviewContext")!=null) {
            if(unwatermarked||body.get("recordRevisionId")!=null||body.get("pdfVersionId")!=null||!(body.get("reviewContext") instanceof Map<?,?> raw)) throw ApiFault.invalid("候选检查须使用本人水印");
            @SuppressWarnings("unchecked") Map<String,Object> review=(Map<String,Object>)raw;Inputs.only(review,"type","id","candidatePdfVersionId");contextType=Inputs.choice(review.get("type"),"REGISTRATION","SCAN_QUALITY","CATALOG_CHANGE");context=Inputs.uuid(review.get("id"));pdf=Inputs.uuid(review.get("candidatePdfVersionId"));source=authority.review(actor,contextType,context,pdf);revision=(UUID)source.get("revision_id");sourceVersion=((Number)source.get("sourceVersion")).intValue();
        } else {
            revision=Inputs.uuid(body.get("recordRevisionId"));pdf=Inputs.uuid(body.get("pdfVersionId"));source=authority.published(revision,pdf);var allowed=authority.source(actor,source,null,null,unwatermarked);grant=(UUID)allowed.get("grantId");policy=(UUID)allowed.get("policyId");sourceVersion=((Number)allowed.get("sourceVersion")).intValue();
            if(allowed.get("validUntil")!=null) {Instant until=Instant.parse(allowed.get("validUntil").toString());if(until.isBefore(expires)) expires=until;}
        }
        UUID usage=UUID.randomUUID(),job=UUID.randomUUID();
        db.update("INSERT INTO archive.usage_request(id,user_id,session_id,auth_version,record_id,record_revision_id,pdf_version_id,object_id,grant_id,source_policy_id,review_context_id,review_context_type,source_version,action,expires_at,request_digest,unwatermarked) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'VIEW',?,?,?)",usage,actor.userId(),actor.sessionId(),actor.authVersion(),source.get("id"),revision,pdf,source.get("object_id"),grant,policy,context,contextType,sourceVersion,java.sql.Timestamp.from(expires),digest,unwatermarked);
        db.update("INSERT INTO ops.job(id,job_type,module,requester_id,business_ref,input_version,authorization_version,idempotency_key,state,requester_session_id) VALUES(?,'ARCHIVE_PREVIEW','ARCHIVE',?,?,?,?,?,'PENDING',?)",job,actor.userId(),usage.toString(),sourceVersion,actor.authVersion(),key,actor.sessionId());
        db.update("UPDATE archive.usage_request SET job_id=? WHERE id=?",job,usage);audit.event(actor.userId(),"ARCHIVE","USAGE",usage,"VIEW_REQUESTED","SUCCEEDED",request);return manifest(actor,usage);
    }
    /** 元数据在主服务以外解析，租约及最终授权在两个短事务中分别核验。 */
    @Override public void process(Actor actor,UUID job,UUID request) {
        String lease=UUID.randomUUID().toString();UUID usage=tx.execute(status->{
            identity.lockActor(actor);var task=db.required("SELECT *,lease_until<CURRENT_TIMESTAMP AS expired FROM ops.job WHERE id=? AND requester_id=? FOR UPDATE",job,actor.userId());
            if(!jobType().equals(task.get("job_type"))||!("PENDING".equals(task.get("state"))||("RUNNING".equals(task.get("state"))&&Boolean.TRUE.equals(task.get("expired"))))) throw ApiFault.conflict("阅读任务当前不能领取");UUID id=Inputs.uuid(task.get("business_ref"));bound(actor,id,false);
            db.update("UPDATE ops.job SET state='RUNNING',lease_owner=?,lease_until=CURRENT_TIMESTAMP+interval '1 minute',attempt_count=attempt_count+1 WHERE id=?",lease,job);return id;
        });
        try {
            int count=runtime.withClient(client->{var source=bound(actor,usage,false);return client.pageCount(path(source),(String)source.get("sha256"));});
            tx.executeWithoutResult(status->{identity.lockActor(actor);bound(actor,usage,false);if(db.update("UPDATE ops.job SET state='SUCCEEDED',error_code=NULL,lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=? AND state='RUNNING' AND lease_until>CURRENT_TIMESTAMP",job,lease)!=1) throw ApiFault.conflict("阅读任务租约已失效");db.update("UPDATE archive.usage_request SET state='READY',page_count=? WHERE id=?",count,usage);});
        } catch(RuntimeException failure) {tx.executeWithoutResult(status->{if(db.update("UPDATE ops.job SET state='FAILED',error_code='ARCHIVE_PREVIEW_FAILED',lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=? AND state='RUNNING'",job,lease)==1) db.update("UPDATE archive.usage_request SET state='FAILED' WHERE id=?",usage);});throw failure;}
    }
    /** 读取本人仍获权会话，不暴露原件地址、物理键、签名密钥或他人图像。 */
    public Map<String,Object> manifest(Actor actor,UUID usage) {var row=bound(actor,usage,false);return Sql.map("usageId",usage,"jobId",row.get("job_id"),"state",row.get("state"),"expiresAt",row.get("expires_at"),"allowedAction","VIEW","itemCount",1,"pageCount",row.get("page_count"),"versionNo",db.count("SELECT version_no FROM archive.pdf_version WHERE id=?",row.get("pdf_version_id")));}
    /** 技术任务状态须先重新核验本人阅读来源，任务号本身不授予权限。 */
    @Override public Map<String,Object> status(Actor actor,Map<String,Object> job) {UUID usage=Inputs.uuid(job.get("business_ref"));var receipt=manifest(actor,usage);return Sql.map("id",job.get("id"),"jobType",jobType(),"state",job.get("state"),"errorCode",job.get("error_code"),"result",receipt);}
    /** 当前请求逐页生成本人水印，完成后再校验副本与原批准并取得库外刷盘回执。 */
    public byte[] page(Actor actor,UUID usage,int page,UUID request) {
        var initial=bound(actor,usage,true);if(page<1||page>((Number)initial.get("page_count")).intValue()) throw new ApiFault(416,"PAGE_OUT_OF_RANGE","页码超出范围");
        return runtime.withClient(client->{
            var source=bound(actor,usage,true);
            byte[] image=Boolean.TRUE.equals(source.get("unwatermarked"))
                ?client.renderPage(path(source),(String)source.get("sha256"),page,120)
                :client.render(path(source),(String)source.get("sha256"),page,120,new WatermarkedPage.Stamp((String)actor.user().get("name"),(String)actor.user().get("employeeNo"),(String)source.get("file_no"),Instant.now()));
            path(source); // 副本在解析后再核验，不能因主副本可读就忽略坏的次副本。
            tx.executeWithoutResult(status->{identity.lockActor(actor);var current=bound(actor,usage,true);receipts.persist(actor,(UUID)current.get("object_id"),(UUID)current.get("pdf_version_id"),page,request);audit.event(actor.userId(),"ARCHIVE","USAGE",usage,Boolean.TRUE.equals(current.get("unwatermarked"))?"UNWATERMARKED_PAGE_AUTHORIZED":"PAGE_AUTHORIZED","SUCCEEDED",request);});return image;
        });
    }
    /** 每次命中都检查原来源，不在撤权后改用其他批准来保活旧阅读地址。 */
    private Map<String,Object> bound(Actor actor,UUID usage,boolean ready) {
        Actor currentActor=identity.background(actor.sessionId());currentActor.require("ARCHIVE.ACCESS");protection.requireReadable("ARCHIVE");
        var session=db.required("SELECT *,expires_at>CURRENT_TIMESTAMP AS valid FROM archive.usage_request WHERE id=? AND user_id=? AND session_id=?",usage,actor.userId(),actor.sessionId());
        if(!Boolean.TRUE.equals(session.get("valid"))||((Number)session.get("auth_version")).intValue()!=actor.authVersion()) throw ApiFault.hidden();if(ready&&!"READY".equals(session.get("state"))) throw ApiFault.conflict("阅读页面尚未就绪");
        if(Boolean.TRUE.equals(session.get("system_admin_source"))) throw ApiFault.hidden();
        Map<String,Object> current;
        if(session.get("review_context_id")!=null) {current=authority.review(currentActor,(String)session.get("review_context_type"),(UUID)session.get("review_context_id"),(UUID)session.get("pdf_version_id"));if(!session.get("record_revision_id").equals(current.get("revision_id"))) throw ApiFault.hidden();}
        else {current=authority.published((UUID)session.get("record_revision_id"),(UUID)session.get("pdf_version_id"));var source=authority.source(currentActor,current,(UUID)session.get("grant_id"),(UUID)session.get("source_policy_id"),Boolean.TRUE.equals(session.get("unwatermarked")));current.put("sourceVersion",source.get("sourceVersion"));}
        if(!session.get("record_id").equals(current.get("id"))||!session.get("object_id").equals(current.get("object_id"))||!session.get("source_version").equals(current.get("sourceVersion"))) throw ApiFault.hidden();
        // 只把内部文件定位字段带给受控进程；对外方法始终显式投影。
        session.put("storage_key",current.get("storage_key"));session.put("sha256",current.get("sha256"));session.put("byte_size",current.get("byte_size"));session.put("file_no",current.get("file_no"));return session;
    }
    /** 定位前核验两个副本和预期摘要，路径仅供内部隔离工作器使用。 */
    private Path path(Map<String,Object> source) {return files.get().verifiedPrimary((String)source.get("storage_key"),((Number)source.get("byte_size")).longValue(),(String)source.get("sha256"));}
}
