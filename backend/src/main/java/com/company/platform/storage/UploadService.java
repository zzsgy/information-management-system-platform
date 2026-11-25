package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.operations.DeploymentSettings;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.InputStream;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;

/** 受控上传事务服务；接收分片和登记检测任务不等同业务发布。 */
@Service
public class UploadService {
    /** 共享数据库、会话版本、业务授权、保护门槛、隔离实体与审计。 */
    private final Sql db; private final IdentityService identity; private final StorageBoundaries boundaries;
    private final StorageFiles files; private final Audit audit; private final TransactionTemplate transaction;
    /** 预览任务按固定阅读会话重新授权，不能套用上传权限。 */
    private final ControlledReadService reading;
    private final ObjectProvider<FileDomainJobs> domainJobs;
    private final Environment environment;
    public UploadService(Sql db,IdentityService identity,StorageBoundaries boundaries,StorageFiles files,Audit audit,PlatformTransactionManager manager,ControlledReadService reading,ObjectProvider<FileDomainJobs> domainJobs,Environment environment) {
        this.db=db; this.identity=identity; this.boundaries=boundaries; this.files=files; this.audit=audit; this.transaction=new TransactionTemplate(manager);this.reading=reading;this.domainJobs=domainJobs;this.environment=environment;
    }
    /** API-STORAGE-001：按操作号去重，任何重放都重新检查上下文授权。 */
    @Transactional
    public Map<String,Object> create(Actor actor,Map<String,Object> body,UUID operation,UUID request) {
        UploadRules.Spec spec=UploadRules.spec(body); identity.lockActor(actor);
        boundaries.require(actor,spec.module(),spec.purpose(),spec.context(),"UPLOAD_CREATE",true);
        files.get(); // 两个隔离路径未就绪时不生成看似可上传的会话。
        // 只串行化同一主体的同一操作号，不阻塞其他文件和其他用户。
        String key="CREATE:"+operation;
        db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.userId()+":"+key);
        String digest=Crypto.hash(Crypto.json(body));
        var old=db.one("SELECT * FROM storage.upload_session WHERE uploader_id=? AND idempotency_key=?",actor.userId(),key);
        if(old!=null) {
            if(!Crypto.equal(digest,(String)old.get("request_digest"))) throw ApiFault.conflict("同一操作号不能用于不同文件");
            return view(old);
        }
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO storage.upload_session(id,module,business_context_id,uploader_id,expected_sha256,expected_size,expected_media_type,idempotency_key,request_digest,expires_at,purpose,original_name) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP+interval '24 hours',?,?)",
            id,spec.module(),spec.context(),actor.userId(),spec.sha256(),spec.size(),spec.mediaType(),key,digest,spec.purpose(),spec.name());
        audit.event(actor.userId(),spec.module(),"UPLOAD",id,"UPLOAD_CREATE","SUCCEEDED",request);
        return view(db.required("SELECT * FROM storage.upload_session WHERE id=?",id));
    }
    /** API-STORAGE-003：仅本人且仍有领域授权时返回状态，不返回磁盘键或其他人员内容。 */
    public Map<String,Object> get(Actor actor,UUID id) {
        var row=session(actor,id,false); authorize(actor,row,"UPLOAD_READ",false); return view(row);
    }
    /** API-STORAGE-002：验证二进制实测内容，事务失败只回收本次随机新文件。 */
    public Map<String,Object> part(Actor actor,UUID id,int number,String range,long length,String hash,InputStream input,UUID request) {
        // 先只读检查，再接收网络流；慢客户端不能长期持有数据库连接或账号锁。
        var preflight=session(actor,id,false); authorize(actor,preflight,"UPLOAD_PART",true); open(preflight);
        UploadRules.Part part=UploadRules.part(((Number)preflight.get("expected_size")).longValue(),number,range,length);
        String digest=UploadRules.sha(hash); QuarantineFiles store=files.get();
        String created=store.receive(input,part.length(),digest);
        // 数据刷盘后再进入短事务；重新锁住账号及会话，确保收流期间撤权立即生效。
        Map<String,Object> result=transaction.execute(status->commitPart(actor,id,part,digest,created,store,request));
        if(result==null) throw SystemFailure.JOB_RESULT_INVALID.fault();
        return result;
    }
    /** 短事务登记分片，遇到重复操作重新验证已保存副本；提交结果未知时保留孤儿核查。 */
    private Map<String,Object> commitPart(Actor actor,UUID id,UploadRules.Part part,String digest,String created,QuarantineFiles store,UUID request) {
        // 文件先同步刷盘，再登记元数据；业务回滚时只删本次新建键。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            // 提交结果未知时可能已经成功，必须保留实体供核查，不能误删已引用内容。
            @Override public void afterCompletion(int status) { if(status==STATUS_ROLLED_BACK) store.rollbackNew(created); }
        });
        identity.lockActor(actor); var row=session(actor,id,true); authorize(actor,row,"UPLOAD_PART",true); open(row);
        // 会话固定信息在数据库中不可被本接口修改，仍以最终读取结果核对范围。
        if(part.offset()+part.length()>((Number)row.get("expected_size")).longValue()) throw ApiFault.conflict("上传范围已发生变化");
        int number=part.number();
        var prior=db.one("SELECT * FROM storage.upload_part WHERE upload_session_id=? AND part_no=?",id,number);
        if(prior!=null) {
            if(!Crypto.equal(digest,(String)prior.get("sha256")) || ((Number)prior.get("byte_size")).longValue()!=part.length()) throw ApiFault.conflict("相同分片编号不能接收不同内容");
            if(!store.verified((String)prior.get("staging_key"),part.length(),digest)) throw SystemFailure.JOB_RESULT_INVALID.fault();
            store.rollbackNew(created); // 已验证的重复分片不产生第二份有效内容。
            return receipt(id,part,digest);
        }
        db.update("INSERT INTO storage.upload_part(upload_session_id,part_no,\"offset\",byte_size,sha256,staging_key) VALUES(?,?,?,?,?,?)",id,number,part.offset(),part.length(),digest,created);
        audit.event(actor.userId(),(String)row.get("module"),"UPLOAD",id,"UPLOAD_PART","SUCCEEDED",request);
        return receipt(id,part,digest);
    }
    /** API-STORAGE-004：核验分片元数据并创建后台任务，完整合并及检测仍由受控工作器完成。 */
    @Transactional
    public Map<String,Object> complete(Actor actor,UUID id,Map<String,Object> body,UUID operation,UUID request) {
        Inputs.only(body,"partCount","finalSha256"); identity.lockActor(actor);
        var row=session(actor,id,true); authorize(actor,row,"UPLOAD_COMPLETE",true);
        files.get(); // 提交处理前再次确认隔离目录可用。
        long size=((Number)row.get("expected_size")).longValue(); int count=Inputs.integer(body.get("partCount"),0,1,640);
        if(count!=UploadRules.count(size) || !Crypto.equal(UploadRules.sha(body.get("finalSha256")),(String)row.get("expected_sha256"))) throw new ApiFault(422,"HASH_OR_PART_MISMATCH","最终摘要或分片数量不匹配");
        String jobKey="COMPLETE:"+id+":"+operation; // 操作号包含目标会话，避免跨文件误重放。
        if("CHECK_PENDING".equals(row.get("state"))) {
            var job=db.required("SELECT * FROM ops.job WHERE id=?",row.get("job_id"));
            if(!Objects.equals(job.get("idempotency_key"),jobKey)) throw ApiFault.conflict("上传已提交其他完成操作");
            return task(job);
        }
        open(row);
        var parts=db.rows("SELECT part_no,\"offset\",byte_size FROM storage.upload_part WHERE upload_session_id=? ORDER BY part_no",id);
        if(parts.size()!=count) throw new ApiFault(422,"HASH_OR_PART_MISMATCH","分片尚未完整接收");
        for(int i=0;i<count;i++) {
            var p=parts.get(i); long offset=i*UploadRules.PART_SIZE, expected=Math.min(UploadRules.PART_SIZE,size-offset);
            if(((Number)p.get("part_no")).intValue()!=i+1 || ((Number)p.get("offset")).longValue()!=offset || ((Number)p.get("byte_size")).longValue()!=expected) throw new ApiFault(422,"HASH_OR_PART_MISMATCH","分片不连续或长度不匹配");
        }
        UUID job=UUID.randomUUID();
        db.update("INSERT INTO ops.job(id,job_type,module,requester_id,business_ref,input_version,authorization_version,idempotency_key,state,requester_session_id) VALUES(?,'FILE_ASSEMBLY_CHECK',?,?,?,?,?,?,'PENDING',?)",job,row.get("module"),actor.userId(),id.toString(),1,actor.authVersion(),jobKey,actor.sessionId());
        db.update("UPDATE storage.upload_session SET state='CHECK_PENDING',job_id=? WHERE id=?",job,id);
        audit.event(actor.userId(),(String)row.get("module"),"UPLOAD",id,"UPLOAD_COMPLETE_REQUESTED","SUCCEEDED",request);
        return task(db.required("SELECT * FROM ops.job WHERE id=?",job));
    }
    /** API-STORAGE-005：仅取消本人未完成会话，保留分片并由以后受控维护流程处理。 */
    @Transactional
    public Map<String,Object> cancel(Actor actor,UUID id,UUID operation,UUID request) {
        identity.lockActor(actor); var row=session(actor,id,true); authorize(actor,row,"UPLOAD_CANCEL",true);
        if("CANCELLED".equals(row.get("state"))) {
            if(!operation.equals(row.get("cancellation_operation_id"))) throw ApiFault.conflict("上传已由其他操作取消");
        } else {
            open(row); db.update("UPDATE storage.upload_session SET state='CANCELLED',cancellation_operation_id=? WHERE id=?",operation,id);
            audit.event(actor.userId(),(String)row.get("module"),"UPLOAD",id,"UPLOAD_CANCEL","SUCCEEDED",request);
        }
        return Sql.map("objectId",id,"state","SUCCEEDED","operationId",operation);
    }
    /** API-OPS-007：只读取本人相关任务，重新核验其固定上传上下文和当前授权。 */
    public Map<String,Object> job(Actor actor,UUID id) {
        var job=db.required("SELECT * FROM ops.job WHERE id=? AND requester_id=?",id,actor.userId());
        if("PREVIEW_METADATA".equals(job.get("job_type"))) return reading.job(actor,job);
        var handlers=domainJobs.orderedStream().filter(handler->handler.jobType().equals(job.get("job_type"))).toList();
        if(handlers.size()==1) return handlers.getFirst().status(actor,job);if(handlers.size()>1) throw SystemFailure.JOB_RESULT_INVALID.fault();
        if(!Set.of("FILE_ASSEMBLY_CHECK","FILE_CHECK").contains(job.get("job_type"))) throw ApiFault.hidden();
        var row=session(actor,Inputs.uuid(job.get("business_ref")),false); authorize(actor,row,"UPLOAD_READ",false);
        Object error=job.get("error_code");
        // 只在读取时报告停用原因，开启后同一排队任务仍可被领取。
        if(error==null && "DRIVE".equals(row.get("module")) && "PENDING".equals(job.get("state"))
            && !environment.getProperty(DeploymentSettings.FILE_WORKER_ENABLED,Boolean.class,false)) {
            error=SystemFailure.FILE_WORKER_DISABLED.name();
        }
        return Sql.map("id",id,"jobType",job.get("job_type"),"module",job.get("module"),"businessRef",job.get("business_ref"),"state",job.get("state"),"attemptCount",job.get("attempt_count"),"nextRunAt",job.get("next_run_at"),"errorCode",error,"result",null);
    }
    /** 检测器恢复后由本人明确重试技术失败；可疑文件和类型拒绝不允许重试洗白。 */
    @Transactional
    public Map<String,Object> retry(Actor actor,UUID id,UUID request) {
        identity.lockActor(actor);var job=db.required("SELECT * FROM ops.job WHERE id=? AND requester_id=? FOR UPDATE",id,actor.userId());
        if(!"FILE_CHECK".equals(job.get("job_type"))) throw ApiFault.hidden();
        var row=session(actor,Inputs.uuid(job.get("business_ref")),true);authorize(actor,row,"FILE_CHECK",true);
        if("PENDING".equals(job.get("state"))) return task(job);
        if(!"FAILED".equals(job.get("state")) || !(Set.of("SCANNER_UNAVAILABLE","CHECK_FAILED").contains(job.get("error_code")) || Arrays.stream(SystemFailure.values()).anyMatch(failure->failure.status>=500 && failure.name().equals(job.get("error_code")))) || !"CHECK_PENDING".equals(row.get("state"))) throw ApiFault.conflict("该任务不可重试");
        db.update("UPDATE ops.job SET state='PENDING',error_code=NULL,requester_session_id=?,authorization_version=?,input_version=input_version+1 WHERE id=?",actor.sessionId(),actor.authVersion(),id);
        audit.event(actor.userId(),(String)job.get("module"),"JOB",id,"FILE_CHECK_RETRY","SUCCEEDED",request);return task(db.required("SELECT * FROM ops.job WHERE id=?",id));
    }
    /** 查询上传所有权时统一隐藏不存在和他人上传，避免按标识枚举。 */
    private Map<String,Object> session(Actor actor,UUID id,boolean lock) {
        return db.required("SELECT *,expires_at>CURRENT_TIMESTAMP AS not_expired FROM storage.upload_session WHERE id=? AND uploader_id=?"+(lock?" FOR UPDATE":""),id,actor.userId());
    }
    /** 会话复用最初固定的模块、用途及上下文，不信任后续请求重新指定。 */
    private void authorize(Actor actor,Map<String,Object> row,String action,boolean write) { boundaries.require(actor,(String)row.get("module"),(String)row.get("purpose"),(UUID)row.get("business_context_id"),action,write); }
    /** 过期或已提交会话不能继续接收分片。 */
    private void open(Map<String,Object> row) { if(!"OPEN".equals(row.get("state")) || !Boolean.TRUE.equals(row.get("not_expired"))) throw ApiFault.conflict("上传会话已结束或过期"); }
    /** 响应只包含续传和状态必需信息。 */
    private Map<String,Object> view(Map<String,Object> row) {
        var parts=db.rows("SELECT part_no FROM storage.upload_part WHERE upload_session_id=? ORDER BY part_no",row.get("id")).stream().map(p->p.get("part_no")).toList();
        return Sql.map("uploadId",row.get("id"),"state",row.get("state"),"partSize",row.get("part_size"),"receivedParts",parts,"expiresAt",row.get("expires_at"),"objectId",row.get("object_id"),"jobId",row.get("job_id"));
    }
    /** 返回服务端真正接收并核验的分片摘要。 */
    private Map<String,Object> receipt(UUID id,UploadRules.Part part,String digest) { return Sql.map("uploadId",id,"partNo",part.number(),"byteSize",part.length(),"sha256",digest,"accepted",true); }
    /** 202 回执是技术任务，不声明文件检测通过或业务发布成功。 */
    private Map<String,Object> task(Map<String,Object> job) { UUID id=(UUID)job.get("id"); return Sql.map("jobId",id,"state",job.get("state"),"statusPath","/api/v1/jobs/"+id); }
}
