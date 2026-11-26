package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.operations.DeploymentSettings;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;

/** 显式启用的有界文件工作器；大文件扫描不占用HTTP线程，任务仍受当前用户授权约束。 */
@Component

public class FileJobWorker {
    /** 有界工作器共享服务，阅读与扫描分别执行各自的授权。 */
    private final Sql db;private final IdentityService identity;private final UploadAssembly assembly;private final FileInspectionService inspection;private final ControlledReadService reading;
    /** 新领域自己检查技术任务来源，公共工作器不能代替其业务批准。 */
    private final ObjectProvider<FileDomainJobs> domains;
    public FileJobWorker(Sql db,IdentityService identity,UploadAssembly assembly,FileInspectionService inspection,ControlledReadService reading,ObjectProvider<FileDomainJobs> domains) { this.db=db;this.identity=identity;this.assembly=assembly;this.inspection=inspection;this.reading=reading;this.domains=domains; }
    /** 每轮最多处理两件；行锁和租约负责多实例竞争，不在内存无限堆积。 */
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.core.env.Environment environment;
    @Scheduled(fixedDelayString="${platform.file-worker.delay-ms:3000}")
    public void tick() {
        run("job_type IN ('FILE_ASSEMBLY_CHECK','FILE_CHECK','ARCHIVE_PREVIEW','ARCHIVE_EXPORT')",2);
    }
    /** 公盘阅读单独领取，上传合并和文件检测繁忙时也能及时开始预览。 */
    @Scheduled(fixedDelayString="${platform.file-worker.preview-delay-ms:500}")
    public void previewTick() {
        run("job_type='PREVIEW_METADATA'",1);
    }
    private void run(String jobFilter,int limit) {
        if(!environment.getProperty(DeploymentSettings.FILE_WORKER_ENABLED,Boolean.class,false))return;
        for(var job:db.rows("SELECT id,job_type,requester_session_id FROM ops.job WHERE "+jobFilter
            +" AND (state='PENDING' OR (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP)) AND requester_session_id IS NOT NULL"
            +" AND (next_run_at IS NULL OR next_run_at<=CURRENT_TIMESTAMP) ORDER BY id LIMIT ?",limit)) {
            UUID request=UUID.randomUUID();
            try {
                Actor actor=identity.background((UUID)job.get("requester_session_id"));
                if(job.get("job_type").equals("FILE_ASSEMBLY_CHECK")) assembly.assemble(actor,(UUID)job.get("id"),request);
                else if(job.get("job_type").equals("FILE_CHECK")) inspection.inspect(actor,(UUID)job.get("id"),request);
                else if(job.get("job_type").equals("PREVIEW_METADATA")) reading.prepare(actor,(UUID)job.get("id"),request);
                else {var handlers=domains.orderedStream().filter(handler->handler.jobType().equals(job.get("job_type"))).toList();if(handlers.size()!=1) throw SystemFailure.JOB_HANDLER_UNAVAILABLE.fault();handlers.getFirst().process(actor,(UUID)job.get("id"),request);}
            } catch(ApiFault fault) {
                FailureDiagnostics.log(request,"FILE_JOB task="+job.get("id"),fault);
                if(fault.status>=500)db.update("UPDATE ops.job SET error_code=?,next_run_at=CURRENT_TIMESTAMP+interval '30 seconds' WHERE id=? AND (state='PENDING' OR (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP))",fault.code,job.get("id"));
                if(fault.status==401 || fault.status==403 || fault.status==404) db.update("UPDATE ops.job SET state='FAILED',error_code='REQUESTER_AUTHORIZATION_INVALID' WHERE id=? AND (state='PENDING' OR (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP))",job.get("id"));
                // 保护未就绪与并发领取不伪造结论，后续周期按当前状态重新检查。
            } catch(RuntimeException failure) {
                FailureDiagnostics.log(request,"FILE_JOB task="+job.get("id"),ApiErrors.classify(failure));
            }
        }
    }
}
