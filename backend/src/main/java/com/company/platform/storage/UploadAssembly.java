package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 合并任务处理边界：短事务领取、事务外流式合并、再次鉴权后短事务挂接。 */
@Service
public class UploadAssembly {
    private final Sql db; private final IdentityService identity; private final StorageBoundaries boundaries;
    private final StorageFiles files; private final Audit audit; private final TransactionTemplate transaction;
    public UploadAssembly(Sql db,IdentityService identity,StorageBoundaries boundaries,StorageFiles files,Audit audit,PlatformTransactionManager manager) {
        this.db=db; this.identity=identity; this.boundaries=boundaries; this.files=files; this.audit=audit; this.transaction=new TransactionTemplate(manager);
    }
    /** 领取结果只携带受控清单和固定元数据，不携带用户请求的本地路径。 */
    private record Work(UUID job,UUID upload,String lease,String module,String purpose,UUID context,
                        String name,String media,long size,String sha,List<QuarantineFiles.SourcePart> parts) { }
    /** 接收由可信工作器重新认证的原始发起主体，不向HTTP开放执行入口。 */
    public UUID assemble(Actor actor,UUID jobId,UUID request) {
        Work work=transaction.execute(status->claim(actor,jobId));
        if(work==null) throw SystemFailure.UPLOAD_ASSEMBLY_FAILED.fault();
        String key=null;
        try {
            QuarantineFiles store=files.get();
            // 大文件流式复制发生在数据库事务外，避免长时间占用连接与行锁。
            key=store.assemble(work.parts(),work.size(),work.sha());
            String savedKey=key;
            UUID object=transaction.execute(status->finish(actor,work,savedKey,request));
            if(object==null) throw SystemFailure.UPLOAD_ASSEMBLY_FAILED.fault();
            return object;
        } catch(RuntimeException failure) {
            ApiFault fault=failure instanceof ApiFault known?known:SystemFailure.UPLOAD_ASSEMBLY_FAILED.fault(failure);
            FailureDiagnostics.log(request,"FILE_ASSEMBLY task="+work.job(),fault);
            // 提交结果不确定时不能删除新原件：数据库可能已提交引用；无引用孤儿以后受控核查。
            try { transaction.executeWithoutResult(status->db.update("UPDATE ops.job SET state='FAILED',error_code=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=? AND state='RUNNING'",fault.code,work.job(),work.lease())); }
            catch(Exception ignored) { /* 租约超时后由受控恢复流程识别，不伪造完成。 */ }
            throw failure;
        }
    }
    /** 数据库行锁保证一个有效租约；拒绝授权已收紧或会话已失效的任务。 */
    private Work claim(Actor actor,UUID id) {
        identity.lockActor(actor);
        var job=db.required("SELECT *,lease_until<CURRENT_TIMESTAMP AS expired FROM ops.job WHERE id=? AND requester_id=? FOR UPDATE",id,actor.userId());
        if(!"FILE_ASSEMBLY_CHECK".equals(job.get("job_type")) || !("PENDING".equals(job.get("state")) || ("RUNNING".equals(job.get("state")) && Boolean.TRUE.equals(job.get("expired"))))) throw ApiFault.conflict("合并任务不可领取");
        if(((Number)job.get("authorization_version")).intValue()!=actor.authVersion()) throw ApiFault.denied();
        UUID upload=Inputs.uuid(job.get("business_ref")); var row=db.required("SELECT * FROM storage.upload_session WHERE id=? AND uploader_id=? FOR UPDATE",upload,actor.userId());
        if(!"CHECK_PENDING".equals(row.get("state")) || !id.equals(row.get("job_id")) || row.get("object_id")!=null) throw ApiFault.conflict("上传状态不允许合并");
        String module=(String)row.get("module"),purpose=(String)row.get("purpose"); UUID context=(UUID)row.get("business_context_id");
        boundaries.require(actor,module,purpose,context,"UPLOAD_ASSEMBLE",true); files.get();
        var rows=db.rows("SELECT part_no,\"offset\",byte_size,sha256,staging_key FROM storage.upload_part WHERE upload_session_id=? ORDER BY part_no",upload);
        long total=((Number)row.get("expected_size")).longValue(); if(rows.size()!=UploadRules.count(total)) throw ApiFault.conflict("合并清单不完整");
        List<QuarantineFiles.SourcePart> parts=new ArrayList<>();
        for(int i=0;i<rows.size();i++) {
            var part=rows.get(i); long offset=i*UploadRules.PART_SIZE, size=Math.min(UploadRules.PART_SIZE,total-offset);
            if(((Number)part.get("part_no")).intValue()!=i+1 || ((Number)part.get("offset")).longValue()!=offset || ((Number)part.get("byte_size")).longValue()!=size) throw ApiFault.conflict("合并清单的范围不连续");
            parts.add(new QuarantineFiles.SourcePart((String)part.get("staging_key"),size,(String)part.get("sha256")));
        }
        String lease=UUID.randomUUID().toString();
        db.update("UPDATE ops.job SET state='RUNNING',lease_owner=?,lease_until=CURRENT_TIMESTAMP+interval '30 minutes',attempt_count=attempt_count+1,error_code=NULL WHERE id=?",lease,id);
        return new Work(id,upload,lease,module,purpose,context,(String)row.get("original_name"),(String)row.get("expected_media_type"),total,(String)row.get("expected_sha256"),List.copyOf(parts));
    }
    /** 输出完成后重新验证会话、授权、保护及未过期租约，才登记待检测原件。 */
    private UUID finish(Actor actor,Work work,String key,UUID request) {
        identity.lockActor(actor); boundaries.require(actor,work.module(),work.purpose(),work.context(),"UPLOAD_ASSEMBLE",true);
        var job=db.required("SELECT *,lease_until>CURRENT_TIMESTAMP AS valid_lease FROM ops.job WHERE id=? FOR UPDATE",work.job());
        var row=db.required("SELECT * FROM storage.upload_session WHERE id=? AND uploader_id=? FOR UPDATE",work.upload(),actor.userId());
        if(!"CHECK_PENDING".equals(row.get("state")) || row.get("object_id")!=null || !"RUNNING".equals(job.get("state")) || !work.lease().equals(job.get("lease_owner")) || !Boolean.TRUE.equals(job.get("valid_lease")) || ((Number)job.get("authorization_version")).intValue()!=actor.authVersion()) throw ApiFault.conflict("合并结果已过期或授权发生变化");
        UUID object=UUID.randomUUID();
        db.update("INSERT INTO storage.object(id,module,storage_key,sha256,byte_size,media_type,original_name,object_kind,state,created_by) VALUES(?,?,?,?,?,?,?,'ORIGINAL','CHECK_PENDING',?)",object,work.module(),key,work.sha(),work.size(),work.media(),work.name(),actor.userId());
        db.update("UPDATE storage.upload_session SET object_id=? WHERE id=?",object,work.upload());
        db.update("UPDATE ops.job SET job_type='FILE_CHECK',state='PENDING',lease_owner=NULL,lease_until=NULL,result_ref=? WHERE id=?",object.toString(),work.job());
        audit.event(actor.userId(),work.module(),"UPLOAD",work.upload(),"UPLOAD_ASSEMBLED","SUCCEEDED",request);
        return object;
    }
}
