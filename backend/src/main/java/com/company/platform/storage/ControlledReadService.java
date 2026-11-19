package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;

/** 公盘固定版本受控阅读：隔离解析、逐页重验、双副本核验和库外交付回执缺一不可。 */
@Service
public class ControlledReadService {
    /** 当前身份、领域授权及保护提供者，所有结果都只在本次请求内使用。 */
    private final Sql db; private final IdentityService identity; private final DriveFilePolicy policy; private final StorageBoundaries boundaries;
    /** 原件仅在内部路径访问；页面在内存交付，不写公开目录或缓存。 */
    private final StorageFiles files; private final ReadingRuntime runtime; private final DeliveryReceipts receipts; private final Audit audit;
    private final OfficeConversions conversions;
    private static final Set<String> IMAGE_MEDIA=Set.of("image/png","image/jpeg","image/tiff");
    private static final Set<String> OFFICE_MEDIA=Set.of("application/msword","application/vnd.ms-excel","application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation");
    /** 文件IO在短事务外执行，最多两个隔离渲染进程保护主服务资源。 */
    private final TransactionTemplate transaction;
    public ControlledReadService(Sql db,IdentityService identity,DriveFilePolicy policy,StorageBoundaries boundaries,StorageFiles files,ReadingRuntime runtime,DeliveryReceipts receipts,Audit audit,OfficeConversions conversions,PlatformTransactionManager manager) {
        this.db=db;this.identity=identity;this.policy=policy;this.boundaries=boundaries;this.files=files;this.runtime=runtime;this.receipts=receipts;this.audit=audit;this.conversions=conversions;this.transaction=new TransactionTemplate(manager);
    }
    /** 固定已发布版本创建15分钟阅读会话；Office转换与图片解析都在后台，202不声称成功。 */
    public Map<String,Object> create(Actor actor,UUID document,Map<String,Object> body,UUID operation,UUID request) {
        Inputs.only(body,"versionId");
        return transaction.execute(status->{
            identity.lockActor(actor); var doc=policy.requireDocument(actor,document,"VIEW"); boundaries.requireReadable("DRIVE");
            UUID version=body.get("versionId")==null?(UUID)doc.get("current_version_id"):Inputs.uuid(body.get("versionId"));
            var object=db.required("SELECT o.* FROM drive.document_version v JOIN storage.object o ON o.id=v.object_id WHERE v.id=? AND v.document_id=? AND o.state='READY' AND o.check_result_ref IS NOT NULL",version,document);
            String media=(String)object.get("media_type");
            if(!"application/pdf".equals(media)&&!OFFICE_MEDIA.contains(media)&&!IMAGE_MEDIA.contains(media)) throw new ApiFault(415,"PREVIEW_UNSUPPORTED","当前格式仅支持原件管理，不能受控阅读");
            String key="PREVIEW:"+document+":"+operation;
            db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.userId()+":"+key);
            var prior=db.one("SELECT * FROM ops.job WHERE requester_id=? AND idempotency_key=?",actor.userId(),key);
            if(prior!=null) { bound(actor,Inputs.uuid(prior.get("business_ref")),false);return task(prior); }
            UUID preview=UUID.randomUUID(),job=UUID.randomUUID();
            db.update("INSERT INTO storage.preview_session(id,user_id,session_id,module,document_id,version_id,object_id,auth_version,document_version,expires_at) VALUES(?,?,?,'DRIVE',?,?,?,?,?,CURRENT_TIMESTAMP+interval '15 minutes')",preview,actor.userId(),actor.sessionId(),document,version,object.get("id"),actor.authVersion(),doc.get("row_version"));
            db.update("INSERT INTO ops.job(id,job_type,module,requester_id,business_ref,input_version,authorization_version,idempotency_key,state,requester_session_id) VALUES(?,'PREVIEW_METADATA','DRIVE',?,?,?,?,?,'PENDING',?)",job,actor.userId(),preview.toString(),1,actor.authVersion(),key,actor.sessionId());
            db.update("UPDATE storage.preview_session SET job_id=? WHERE id=?",job,preview);
            audit.event(actor.userId(),"DRIVE","DOCUMENT",document,"PREVIEW_REQUESTED","SUCCEEDED",request);
            return task(db.required("SELECT * FROM ops.job WHERE id=?",job));
        });
    }
    /** 元数据处理绑定原会话、任务租约和同一版本；超时或损坏不产生READY页面。 */
    public void prepare(Actor actor,UUID jobId,UUID request) {
        String lease=UUID.randomUUID().toString();
        UUID preview=transaction.execute(status->{
            identity.lockActor(actor);
            var job=db.required("SELECT *,lease_until<CURRENT_TIMESTAMP AS expired FROM ops.job WHERE id=? AND requester_id=? FOR UPDATE",jobId,actor.userId());
            if(!"PREVIEW_METADATA".equals(job.get("job_type")) || !("PENDING".equals(job.get("state")) || ("RUNNING".equals(job.get("state"))&&Boolean.TRUE.equals(job.get("expired"))))) throw ApiFault.conflict("阅读任务不可领取");
            UUID id=Inputs.uuid(job.get("business_ref"));bound(actor,id,false);
            // Office转换包含远程处理和真实文件检测，租约需覆盖现有检测器最长处理窗口。
            db.update("UPDATE ops.job SET state='RUNNING',attempt_count=attempt_count+1,lease_owner=?,lease_until=CURRENT_TIMESTAMP+interval '15 minutes' WHERE id=?",lease,jobId);return id;
        });
        if(preview==null) throw SystemFailure.PREVIEW_NOT_READY.fault();
        try {
            var fixed=bound(actor,preview,false);
            if(OFFICE_MEDIA.contains(fixed.get("media_type"))) {
                UUID rendered=conversions.previewPdf(actor,(UUID)fixed.get("document_id"),(UUID)fixed.get("version_id"),request);
                fixed.put("render_object_id",rendered);
            }
            RenderInput input=renderInput(fixed);
            int pages=runtime.withClient(renderer->input.image()?renderer.imagePageCount(input.path(),input.sha()):renderer.pageCount(input.path(),input.sha()));
            renderInput(fixed);
            transaction.executeWithoutResult(status->{
                identity.lockActor(actor);bound(actor,preview,false);
                if(db.update("UPDATE ops.job SET state='SUCCEEDED',lease_owner=NULL,lease_until=NULL,error_code=NULL WHERE id=? AND lease_owner=? AND state='RUNNING' AND lease_until>CURRENT_TIMESTAMP",jobId,lease)!=1) throw ApiFault.conflict("阅读任务租约失效");
                db.update("UPDATE storage.preview_session SET state='READY',page_count=?,render_object_id=? WHERE id=?",pages,fixed.get("render_object_id"),preview);
                audit.event(actor.userId(),"DRIVE","PREVIEW",preview,"PREVIEW_READY","SUCCEEDED",request);
            });
        } catch(ApiFault failure) { FailureDiagnostics.log(request,"PREVIEW task="+jobId,failure);fail(jobId,preview,lease,failure.code);throw failure; }
        catch(RuntimeException failure) { FailureDiagnostics.log(request,"PREVIEW task="+jobId,ApiErrors.classify(failure));fail(jobId,preview,lease,"PREVIEW_FAILED");throw failure; }
    }
    /** 查询本人固定阅读会话；撤权后不能继续取得旧的页数与版本信息。 */
    public Map<String,Object> manifest(Actor actor,UUID preview) {
        var row=bound(actor,preview,false);return Sql.map("previewId",preview,"documentId",row.get("document_id"),"versionId",row.get("version_id"),"state",row.get("state"),"pageCount",row.get("page_count"),"expiresAt",row.get("expires_at"),"jobId",row.get("job_id"));
    }
    /** 每次交付前再次锁定会话并重验业务权；渲染期间撤权、换版或坏副本都拒绝交付。 */
    public byte[] page(Actor actor,UUID preview,int page,UUID request) {
        var fixed=bound(actor,preview,true);
        if(page<1 || page>((Number)fixed.get("page_count")).intValue()) throw new ApiFault(416,"PAGE_OUT_OF_RANGE","页码超出范围");
        if(!runtime.tryAcquire()) throw SystemFailure.READING_BUSY.fault();
        try {
            RenderInput input=renderInput(fixed);var renderer=runtime.get();
            byte[] image=input.image()?renderer.renderImagePage(input.path(),input.sha(),page):renderer.renderPage(input.path(),input.sha(),page,120);
            renderInput(fixed); // 原件与派生PDF都须复验，不能用仍正常的派生文件掩盖原件坏副本。
            transaction.executeWithoutResult(status->{
                identity.lockActor(actor);var current=bound(actor,preview,true);
                UUID receipt=receipts.persist(actor,(UUID)current.get("object_id"),(UUID)current.get("version_id"),page,request);
                audit.event(actor.userId(),"DRIVE","PREVIEW",preview,"PAGE_AUTHORIZED","SUCCEEDED",receipt);
            });
            return image;
        } finally { runtime.release(); }
    }
    /** 任务查询复用同一授权，返回允许客户端跳转的受控预览标识。 */
    public Map<String,Object> job(Actor actor,Map<String,Object> job) {
        UUID preview=Inputs.uuid(job.get("business_ref"));bound(actor,preview,false);
        return Sql.map("id",job.get("id"),"jobType",job.get("job_type"),"state",job.get("state"),"errorCode",job.get("error_code"),"result",Sql.map("previewId",preview));
    }
    /** 正文不可跨人、跨会话、跨授权代次或跨版本复用；正式文档状态每次重读。 */
    private Map<String,Object> bound(Actor actor,UUID id,boolean ready) {
        identity.background(actor.sessionId());
        var row=db.required("SELECT p.*,o.storage_key,o.byte_size,o.sha256,o.media_type,o.state AS object_state,p.expires_at>CURRENT_TIMESTAMP AS valid FROM storage.preview_session p JOIN storage.object o ON o.id=p.object_id WHERE p.id=? AND p.user_id=? AND p.session_id=?",id,actor.userId(),actor.sessionId());
        var doc=policy.requireDocument(actor,(UUID)row.get("document_id"),"VIEW");boundaries.requireReadable("DRIVE");
        if(!Boolean.TRUE.equals(row.get("valid")) || ((Number)row.get("auth_version")).intValue()!=actor.authVersion() || !"READY".equals(row.get("object_state")) || !row.get("document_version").equals(doc.get("row_version"))) throw ApiFault.hidden();
        if(ready && !"READY".equals(row.get("state"))) throw ApiFault.conflict("阅读页面尚未就绪");return row;
    }
    /** 仅内部受控存储产生可信路径，外部API不接受路径参数。 */
    private Path source(Map<String,Object> row) { return files.get().verifiedPrimary((String)row.get("storage_key"),((Number)row.get("byte_size")).longValue(),(String)row.get("sha256")); }
    private record RenderInput(Path path,String sha,boolean image) { }
    /** 预览始终先验证原件，转换PDF只是内部渲染输入，不改变正文的授权对象。 */
    private RenderInput renderInput(Map<String,Object> row) {
        Path original=source(row);
        if(row.get("render_object_id")==null) {
            if(OFFICE_MEDIA.contains(row.get("media_type")))throw ApiFault.conflict("Office预览转换尚未就绪");
            return new RenderInput(original,(String)row.get("sha256"),IMAGE_MEDIA.contains(row.get("media_type")));
        }
        var rendered=db.required("SELECT * FROM storage.object WHERE id=? AND state='READY' AND media_type='application/pdf' AND check_result_ref IS NOT NULL",row.get("render_object_id"));
        return new RenderInput(source(rendered),(String)rendered.get("sha256"),false);
    }
    /** 只修改仍属于本租约的任务，防止旧进程覆盖新进程处理结果。 */
    private void fail(UUID job,UUID preview,String lease,String error) { transaction.executeWithoutResult(status->{
        if(db.update("UPDATE ops.job SET state='FAILED',error_code=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=? AND state='RUNNING'",error,job,lease)==1) db.update("UPDATE storage.preview_session SET state='FAILED' WHERE id=?",preview);
    }); }
    /** 接口回执只暴露业务任务地址，不暴露物理文件位置。 */
    private Map<String,Object> task(Map<String,Object> job) { return Sql.map("jobId",job.get("id"),"state",job.get("state"),"statusPath","/api/v1/jobs/"+job.get("id")); }
}
