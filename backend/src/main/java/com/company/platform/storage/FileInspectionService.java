package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;

/** 将真实检测结果与租约、固定摘要和当前业务授权绑定，检测通过后仍不自动发布文档。 */
@Service
public class FileInspectionService {
    private final Sql db; private final IdentityService identity; private final StorageBoundaries boundaries;
    private final StorageFiles files; private final FileScanEngine scanner; private final TransactionTemplate transaction;
    private final String serviceIdentity;
    /** 档案扫描使用独立模块和部门范围身份，不复用公盘服务的权限。 */
    private final String archiveIdentity;
    /** 平台台账检测独立身份，不能复用公盘或档案范围。 */
    private final String platformIdentity;
    private final ServiceIdentities services;
    public FileInspectionService(Sql db,IdentityService identity,StorageBoundaries boundaries,StorageFiles files,FileScanEngine scanner,
        PlatformTransactionManager manager,@Value("${platform.scanner.service-id:}") String serviceIdentity,@Value("${platform.scanner.archive-service-id:}") String archiveIdentity,@Value("${platform.scanner.platform-service-id:}") String platformIdentity,ServiceIdentities services) {
        this.db=db;this.identity=identity;this.boundaries=boundaries;this.files=files;this.scanner=scanner;this.transaction=new TransactionTemplate(manager);this.serviceIdentity=serviceIdentity;this.archiveIdentity=archiveIdentity;this.platformIdentity=platformIdentity;this.services=services;
    }
    /** 所有字段来自已锁定的原任务和文件对象，不接受浏览器提供检测结论。 */
    private record Work(UUID job,UUID upload,UUID object,UUID attempt,UUID service,String lease,String module,String purpose,UUID context,
                        String key,String sha,long size,String name,String media,int inputVersion) { }
    /** 只由可信调度器调用，文件扫描与类型检查都在数据库事务之外执行。 */
    public void inspect(Actor actor,UUID jobId,UUID request) {
        Work work=transaction.execute(status->claim(actor,jobId)); if(work==null) throw SystemFailure.FILE_CHECK_FAILED.fault();
        try {
            QuarantineFiles store=files.get(); Path source=store.verifiedPrimary(work.key(),work.size(),work.sha());
            ClamFileScanner.Verdict verdict=scanner.scan(source);
            String media=verdict==ClamFileScanner.Verdict.CLEAN?FileMediaCheck.detect(source,work.name(),work.media()):null;
            // 台账导入仍执行专门的受限工作簿规则，不能因公盘允许普通Office而放宽导入解析。
            if(media!=null&&Set.of("IMPORT_XLSX","ARCHIVE_CHECKLIST","ARCHIVE_REGISTRATION_CHECKLIST").contains(work.purpose())&&!com.company.platform.intake.ImportWorkbook.validContainer(source))media=null;
            if(!store.verified(work.key(),work.size(),work.sha())) throw SystemFailure.FILE_CHECK_FAILED.fault();
            String checkedMedia=media;
            transaction.executeWithoutResult(status->finish(actor,work,verdict,checkedMedia,request));
        } catch(Exception failure) {
            ApiFault fault=failure instanceof ApiFault known?known:SystemFailure.FILE_CHECK_FAILED.fault(failure);
            FailureDiagnostics.log(request,"FILE_CHECK task="+work.job(),fault);
            transaction.executeWithoutResult(status->{
                db.update("UPDATE ops.job SET state='FAILED',error_code=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=? AND state='RUNNING'",fault.code,work.job(),work.lease());
                db.update("UPDATE ops.job_attempt SET state='FAILED',ended_at=CURRENT_TIMESTAMP,sanitized_error=? WHERE id=? AND state='RUNNING'",fault.code,work.attempt());
            });
            throw fault;
        }
    }
    /** 领取时再次检查服务身份、用户会话、任务输入和当前领域授权。 */
    private Work claim(Actor actor,UUID id) {
        identity.lockActor(actor);
        var job=db.required("SELECT *,lease_until<CURRENT_TIMESTAMP AS expired FROM ops.job WHERE id=? AND requester_id=? FOR UPDATE",id,actor.userId());
        if(!"FILE_CHECK".equals(job.get("job_type")) || !("PENDING".equals(job.get("state")) || ("RUNNING".equals(job.get("state"))&&Boolean.TRUE.equals(job.get("expired"))))) throw ApiFault.conflict("检测任务不可领取");
        if(((Number)job.get("authorization_version")).intValue()!=actor.authVersion()) throw ApiFault.denied();
        UUID upload=Inputs.uuid(job.get("business_ref")); var session=db.required("SELECT * FROM storage.upload_session WHERE id=? AND uploader_id=? FOR UPDATE",upload,actor.userId());
        if(!"CHECK_PENDING".equals(session.get("state")) || !id.equals(session.get("job_id")) || session.get("object_id")==null) throw ApiFault.conflict("上传状态不允许检测");
        var object=db.required("SELECT * FROM storage.object WHERE id=? AND state='CHECK_PENDING' FOR UPDATE",session.get("object_id"));
        String module=(String)session.get("module"),purpose=(String)session.get("purpose"); UUID context=(UUID)session.get("business_context_id");
        boundaries.require(actor,module,purpose,context,"FILE_CHECK",true); UUID service=worker(module,purpose,context);
        int attempt=((Number)job.get("attempt_count")).intValue()+1; String lease=UUID.randomUUID().toString(); UUID attemptId=UUID.randomUUID();
        db.update("UPDATE ops.job_attempt SET state='ABANDONED',ended_at=CURRENT_TIMESTAMP,sanitized_error='LEASE_EXPIRED' WHERE job_id=? AND state='RUNNING'",id);
        db.update("UPDATE ops.job SET state='RUNNING',lease_owner=?,lease_until=CURRENT_TIMESTAMP+interval '11 minutes',attempt_count=?,error_code=NULL WHERE id=?",lease,attempt,id);
        db.update("INSERT INTO ops.job_attempt(id,job_id,attempt_no,worker_identity_id,lease_token) VALUES(?,?,?,?,?)",attemptId,id,attempt,service,Crypto.hash(lease));
        return new Work(id,upload,(UUID)object.get("id"),attemptId,service,lease,module,purpose,context,(String)object.get("storage_key"),(String)object.get("sha256"),((Number)object.get("byte_size")).longValue(),(String)object.get("original_name"),(String)session.get("expected_media_type"),((Number)job.get("input_version")).intValue());
    }
    /** 服务身份只允许明确模块和部门，不能作为全公司上传或正文读取通行证。 */
    private UUID worker(String module,String purpose,UUID context) {
        UUID service; try { service=Inputs.uuid(services.configured(module.equals("ARCHIVE")?"platform.scanner.archive-service-id":module.equals("PLATFORM")?"platform.scanner.platform-service-id":"platform.scanner.service-id")); } catch(RuntimeException e) { throw new ApiFault(503,"SERVICE_IDENTITY_UNAVAILABLE","文件检测服务身份尚未配置，请联系管理员"); }
        if(!Set.of("DRIVE","ARCHIVE","PLATFORM").contains(module)) throw SystemFailure.FILE_CHECK_FAILED.fault();
        // 凭据上下文是固定审批节点；总经理节点没有所属部门，沿用申请人所属部门限制检测身份。
        String scope=Set.of("IMPORT_XLSX","ARCHIVE_REGISTRATION_CHECKLIST").contains(purpose)?"SELECT id AS department_id FROM iam.department WHERE id=? AND status='ACTIVE'":purpose.equals("EVIDENCE")?"SELECT COALESCE(s.department_id,u.department_id) AS department_id FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id JOIN iam.user_account u ON u.id=w.applicant_id WHERE s.id=?":module.equals("ARCHIVE")?"SELECT department_id FROM archive.record WHERE id=?":"SELECT department_id FROM drive.folder WHERE id=?";
        if(purpose.equals("DRIVE_VERSION"))scope="SELECT publishing_department_id AS department_id FROM drive.document WHERE id=? AND status='ACTIVE'";
        if(purpose.equals("ARCHIVE_CHECKLIST"))scope="SELECT department_id FROM archive.box WHERE id=?";
        var folder=db.required(scope,context);
        return services.require(service,"FILE_CHECK",module,(UUID)folder.get("department_id"),"FILE_CHECK");
    }
    /** 确认当前租约和权限仍有效后才提交检测结论，签名库不可用保持待检测。 */
    private void finish(Actor actor,Work work,ClamFileScanner.Verdict verdict,String media,UUID request) {
        identity.lockActor(actor); boundaries.require(actor,work.module(),work.purpose(),work.context(),"FILE_CHECK",true); worker(work.module(),work.purpose(),work.context());
        var job=db.required("SELECT *,lease_until>CURRENT_TIMESTAMP AS valid FROM ops.job WHERE id=? FOR UPDATE",work.job());
        var session=db.required("SELECT * FROM storage.upload_session WHERE id=? FOR UPDATE",work.upload());
        if(!"RUNNING".equals(job.get("state")) || !work.lease().equals(job.get("lease_owner")) || !Boolean.TRUE.equals(job.get("valid")) || ((Number)job.get("input_version")).intValue()!=work.inputVersion() || !work.object().equals(session.get("object_id")) || !"CHECK_PENDING".equals(session.get("state"))) throw ApiFault.conflict("检测结果已失效");
        boolean clean=verdict==ClamFileScanner.Verdict.CLEAN && media!=null;
        String error=clean?null:verdict==ClamFileScanner.Verdict.UNAVAILABLE?"SCANNER_UNAVAILABLE":verdict==ClamFileScanner.Verdict.SUSPICIOUS?"FILE_SUSPICIOUS":"MEDIA_TYPE_NOT_ALLOWED";
        if(clean) {
            db.update("UPDATE storage.object SET state='READY',media_type=?,check_result_ref=? WHERE id=? AND state='CHECK_PENDING'",media,work.attempt().toString(),work.object());
            db.update("UPDATE storage.upload_session SET state='COMPLETE',result_code='CLEAN' WHERE id=?",work.upload());
        } else if(verdict!=ClamFileScanner.Verdict.UNAVAILABLE) {
            db.update("UPDATE storage.object SET state='QUARANTINED',check_result_ref=? WHERE id=? AND state='CHECK_PENDING'",work.attempt().toString(),work.object());
            db.update("UPDATE storage.upload_session SET state='REJECTED',result_code=? WHERE id=?",error,work.upload());
        }
        db.update("UPDATE ops.job SET state=?,error_code=?,lease_owner=NULL,lease_until=NULL WHERE id=?",clean?"SUCCEEDED":"FAILED",error,work.job());
        db.update("UPDATE ops.job_attempt SET state=?,ended_at=CURRENT_TIMESTAMP,result_ref=?,sanitized_error=? WHERE id=?",clean?"SUCCEEDED":"FAILED",work.object().toString(),error,work.attempt());
        // 与人工审计共用真实入库时点，较早启动的检查事务不能越过已有查询截点。
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,module,resource_type,resource_id,action,result,request_id,record_kind,severity,actor_service_id,ingested_at) VALUES(?,?,CURRENT_TIMESTAMP,?,'OBJECT',?,'FILE_CHECK',?,?,'BUSINESS_EVENT',?,?,clock_timestamp())",UUID.randomUUID(),UUID.randomUUID(),work.module(),work.object(),clean?"SUCCEEDED":"DENIED",request,clean?"INFO":"WARN",work.service());
    }
}
