package com.company.platform.workflow;

import com.company.platform.*;
import com.company.platform.archive.*;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 同意凭据领域：申请人提交原件，获权 IT 核验固定事实，只补录当前指定节点。 */
@Service
public class WorkflowEvidence implements StorageDomainPolicy {
    /** 原件最多十份，每份五十 MiB；列表和正文的权限彼此独立。 */
    private static final Set<String> DUTIES=Set.of("DEPARTMENT_MANAGER","GENERAL_MANAGER");
    /** 同事务数据库、身份、命令、审批和原件双副本边界。 */
    private final Sql db; private final IdentityService identity; private final Commands commands; private final ArchiveCommands archiveCommands;
    private final WorkflowEngine workflow; private final WorkflowDuties duties; private final StorageBoundaries protection;
    private final StorageFiles files; private final ArchiveAuthority authority; private final Audit audit;
    /** 服务只接受受控 HTTP 会话，IT 权限不转换为档案全文权限。 */
    /** 参数分别提供身份、事务、业务推进、原件和审计；不建立 IT 全文权限。 */
    public WorkflowEvidence(Sql db,IdentityService identity,Commands commands,ArchiveCommands archiveCommands,WorkflowEngine workflow,WorkflowDuties duties,StorageBoundaries protection,StorageFiles files,ArchiveAuthority authority,Audit audit) {
        this.db=db;this.identity=identity;this.commands=commands;this.archiveCommands=archiveCommands;this.workflow=workflow;this.duties=duties;this.protection=protection;this.files=files;this.authority=authority;this.audit=audit;
    }
    /** 凭据的上下文固定为档案审批节点，不接受平台或公盘上传用途冒用。 */
    @Override public boolean supports(String module,String purpose) {return module.equals("ARCHIVE")&&purpose.equals("EVIDENCE");}
    /** 上传、分片、合并和检测各阶段重验申请人及当前节点；失效节点不能继续收件。 */
    @Override public void require(Actor actor,String module,String purpose,UUID context,String action) {
        Actor live=identity.background(actor.sessionId());live.require("ARCHIVE.ACCESS");var node=node(context);
        if(!live.userId().equals(node.get("applicant_id")))throw ApiFault.hidden();current(node);duties.requireCurrent(node,(UUID)node.get("assignee_id"));
    }
    /** 凭据提交保持申请人、实际同意人、任职和业务快照不可变，提交本身不推进审批。 */
    public Map<String,Object> submit(Commands.Context ctx,UUID step,Map<String,Object> body) {
        Inputs.only(body,"originalApproverId","agreedAt","objectIds","description");UUID approver=Inputs.uuid(body.get("originalApproverId"));
        List<UUID> objects=ids(body.get("objectIds"));Instant agreed=instant(body.get("agreedAt"));Inputs.text(body,"description",2000);
        return archiveCommands.run(ctx,body,"WORKFLOW_STEP",step,"SUBMIT_EVIDENCE",false,()->{if(!ctx.actor().userId().equals(node(step).get("applicant_id")))throw ApiFault.hidden();},()->{
            var node=lockedNode(step);current(node);Commands.version(ctx,node);duties.requireCurrent(node,approver);
            if(!approver.equals(node.get("assignee_id"))||approver.equals(ctx.actor().userId()))throw ApiFault.denied();
            // 既有同意可以早于系统填单，但须发生在该原审批人的真实任职期间。
            String assignment="GENERAL_MANAGER".equals(node.get("duty_code"))?"iam.company_duty":"iam.department_manager";
            if(agreed.isAfter(Instant.now())||db.count("SELECT count(*) FROM "+assignment+" WHERE id=? AND valid_from<=?::timestamptz AND (valid_until IS NULL OR valid_until>?::timestamptz)",node.get("assignment_ref"),agreed.toString(),agreed.toString())!=1)throw ApiFault.invalid("实际同意时间不能晚于当前时间，且须处于原审批人的本次任职期间");
            if(db.count("SELECT count(*) FROM workflow.evidence_submission WHERE step_id=? AND state='PENDING'",step)>0)throw ApiFault.conflict("当前节点已有待核验凭据，请先撤回或等待处理");
            UUID submission=UUID.randomUUID();db.update("INSERT INTO workflow.evidence_submission(id,step_id,submitted_by,original_approver_id,assignment_ref,step_version,agreed_at,description,business_snapshot) VALUES(?,?,?,?,?,?,?::timestamptz,?,?::jsonb)",submission,step,ctx.actor().userId(),approver,node.get("assignment_ref"),node.get("row_version"),agreed.toString(),body.get("description"),node.get("business_snapshot").toString());
            for(UUID object:objects) {
                var source=db.required("SELECT o.* FROM storage.object o JOIN storage.upload_session u ON u.object_id=o.id WHERE o.id=? AND u.uploader_id=? AND u.business_context_id=? AND u.purpose='EVIDENCE' AND u.module='ARCHIVE' AND u.state='COMPLETE' AND o.state='READY' AND o.module='ARCHIVE' AND o.byte_size<=52428800 AND o.check_result_ref IS NOT NULL FOR SHARE OF o,u",object,ctx.actor().userId(),step);
                db.update("INSERT INTO workflow.evidence(id,submission_id,object_id,digest,format,size) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),submission,object,source.get("sha256"),source.get("media_type"),source.get("byte_size"));
            }
            return detail(ctx.actor(),submission);
        },id->detail(ctx.actor(),id));
    }
    /** IT 队列只返回提交过的凭据摘要，分页在数据库完成；无权者不能枚举在审业务。 */
    public Map<String,Object> queue(Actor actor,Map<String,String> query) {
        identity.background(actor.sessionId()).require("PLATFORM.EVIDENCE_RECORD");Inputs.only(query,"page","pageSize","state","applicant","approver","dutyCode","createdFrom","createdTo");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String state=Inputs.choice(query.getOrDefault("state","PENDING"),"PENDING","USED","REJECTED","CANCELLED");
        String from=" FROM workflow.evidence_submission e JOIN workflow.step s ON s.id=e.step_id JOIN workflow.instance w ON w.id=s.instance_id JOIN iam.user_account u ON u.id=e.submitted_by JOIN iam.user_account a ON a.id=e.original_approver_id WHERE e.state=?";
        var args=new ArrayList<Object>(List.of(state));
        from+=new ListConditions(query,args).contains("applicant","concat_ws(' ',u.name,u.employee_no)").contains("approver","concat_ws(' ',a.name,a.employee_no)")
            .choice("dutyCode","s.duty_code","DEPARTMENT_MANAGER","GENERAL_MANAGER").dates("created","e.created_at").sql();
        long total=db.count("SELECT count(*)"+from,args.toArray());
        args.add(size);args.add((page-1)*size);
        return Sql.map("items",db.rows("SELECT e.id,e.step_id,e.state,e.created_at,s.duty_code,w.business_type,u.name AS applicant_name,a.name AS approver_name"+from+" ORDER BY e.created_at,e.id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->Sql.view(row,"id,step_id,state,created_at,duty_code,business_type,applicant_name,approver_name")).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 申请人和当前处理人按节点查询；IT 必须从独立凭据队列进入，不借此枚举任意节点。 */
    public Map<String,Object> forStep(Actor actor,UUID step) {
        Actor live=identity.background(actor.sessionId());live.require("ARCHIVE.ACCESS");var node=node(step);
        if(!live.userId().equals(node.get("applicant_id")))duties.requireCurrent(node,live.userId());
        return Sql.map("supported",DUTIES.contains(node.get("duty_code")),"items",db.rows("SELECT id FROM workflow.evidence_submission WHERE step_id=? ORDER BY created_at DESC LIMIT 30",step).stream().map(row->detail(live,(UUID)row.get("id"))).toList());
    }
    /** 明确提交的范围可供核验；返回固定快照，不查询或授权任何其他档案正文。 */
    public Map<String,Object> detail(Actor actor,UUID id) {
        var row=db.required("SELECT e.*,s.duty_code,s.row_version AS current_step_version,s.state AS step_state,w.state AS workflow_state,w.business_type,w.business_id,u.name AS applicant_name,a.name AS approver_name FROM workflow.evidence_submission e JOIN workflow.step s ON s.id=e.step_id JOIN workflow.instance w ON w.id=s.instance_id JOIN iam.user_account u ON u.id=e.submitted_by JOIN iam.user_account a ON a.id=e.original_approver_id WHERE e.id=?",id);
        authorizeRead(actor,row,false);
        var view=Sql.view(row,"id,step_id,submitted_by,original_approver_id,agreed_at,description,state,review_note,reviewed_at,decision_id,row_version,created_at,duty_code,current_step_version,step_state,workflow_state,applicant_name,approver_name");
        Map<String,Object> scope=Crypto.JSON.readValue(row.get("business_snapshot").toString(),Map.class);
        // 只展开本次提交所指向的不可变候选，不给核验人查询任意档案的入口。
        if("CATALOG_CHANGE".equals(row.get("business_type"))){var change=db.required("SELECT * FROM archive.catalog_change WHERE id=?",row.get("business_id"));var fixed=Sql.view(change,"id,object_type,object_id,kind,state,row_version,created_by,managed_by,reason,candidate_pdf_id,quality_confirmed_at,workflow_id");fixed.put("before",Crypto.JSON.readValue(change.get("before_snapshot").toString(),Map.class));fixed.put("after",Crypto.JSON.readValue(change.get("after_snapshot").toString(),Map.class));fixed.put("memberCount",Crypto.JSON.readValue(change.get("member_snapshot").toString(),List.class).size());scope.put("catalogChange",fixed);}
        if("ACCESS_POLICY".equals(row.get("business_type"))){scope.put("departmentNames",db.rows("SELECT name FROM iam.department WHERE id IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) ORDER BY code",Crypto.json(scope.getOrDefault("departmentScope",List.of()))).stream().map(item->item.get("name")).toList());scope.put("userNames",db.rows("SELECT name,employee_no FROM iam.user_account WHERE id IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) ORDER BY employee_no",Crypto.json(scope.getOrDefault("userIds",List.of()))).stream().map(item->item.get("name")+"（"+item.get("employee_no")+"）").toList());}
        view.put("scope",scope);
        view.put("evidence",db.rows("SELECT e.id,e.object_id,e.digest,e.format,e.size,o.original_name FROM workflow.evidence e JOIN storage.object o ON o.id=e.object_id WHERE e.submission_id=? ORDER BY e.id",id).stream().map(item->Sql.view(item,"id,object_id,digest,format,size,original_name")).toList());return view;
    }
    /** 元数据允许本人复核；原始凭据仅给获权 IT、仍在任的原同意人或档案审计超级管理员。 */
    private void authorizeRead(Actor actor,Map<String,Object> row,boolean content) {
        Actor live=identity.background(actor.sessionId());
        if(live.actions().contains("PLATFORM.EVIDENCE_RECORD"))return;
        if(!content&&live.actions().contains("ARCHIVE.ACCESS")&&live.userId().equals(row.get("submitted_by")))return;
        if(live.userId().equals(row.get("original_approver_id"))) {live.require("ARCHIVE.ACCESS");duties.requireCurrent(node((UUID)row.get("step_id")),live.userId());return;}
        authority.requireSuper(live,"ARCHIVE.AUDIT_READ");
    }
    /** IT 核验必须持有本人再验证；重放仍重新鉴权，不能将补录伪装为在线批准。 */
    @Transactional public Map<String,Object> decide(Commands.Context ctx,UUID step,Map<String,Object> body) {
        Inputs.only(body,"evidenceIds","originalApproverId","agreedAt","comment","reason");List<UUID> evidence=ids(body.get("evidenceIds"));UUID approver=Inputs.uuid(body.get("originalApproverId"));Instant agreed=instant(body.get("agreedAt"));Inputs.text(body,"comment",2000);Inputs.text(body,"reason",1000);
        // IAM 管理锁先于稳定账号顺序；与在线决定一致，先账号、实物盒，最后流程和节点。
        db.rows("SELECT pg_advisory_xact_lock(61427002)");var initial=node(step);
        db.rows("SELECT id FROM iam.user_account WHERE id IN (?,?,?) ORDER BY id FOR UPDATE",ctx.actor().userId(),initial.get("applicant_id"),approver);
        for(var box:db.rows("SELECT DISTINCT box_id FROM archive.paper_request_item WHERE request_id=? ORDER BY box_id",initial.get("business_id")))db.required("SELECT id FROM archive.box WHERE id=? FOR UPDATE",box.get("box_id"));
        return commands.run(ctx,body,"WORKFLOW_STEP",step,"EVIDENCE_RECORD",()->identity.background(ctx.actor().sessionId()).require("PLATFORM.EVIDENCE_RECORD"),()->{
            protection.requireWritable("ARCHIVE","EVIDENCE_RECORD");var node=lockedNode(step);current(node);Commands.version(ctx,node);duties.requireCurrent(node,approver);
            if(ctx.actor().userId().equals(node.get("applicant_id"))||ctx.actor().userId().equals(approver)||approver.equals(node.get("applicant_id"))||!approver.equals(node.get("assignee_id")))throw ApiFault.denied();
            var submission=db.required("SELECT * FROM workflow.evidence_submission WHERE step_id=? AND state='PENDING' FOR UPDATE",step);
            if(!submission.get("original_approver_id").equals(approver)||!submission.get("assignment_ref").equals(node.get("assignment_ref"))||!submission.get("step_version").equals(node.get("row_version"))||!instant(submission.get("agreed_at")).equals(agreed)||!submission.get("business_snapshot").toString().equals(node.get("business_snapshot").toString()))throw ApiFault.conflict("凭据对应的人员、任职、时间或业务范围已变化，请重新提交");
            var attachments=db.rows("SELECT e.*,o.storage_key,o.state AS object_state,o.sha256,o.byte_size FROM workflow.evidence e JOIN storage.object o ON o.id=e.object_id WHERE e.submission_id=? ORDER BY e.id FOR SHARE OF o",submission.get("id"));
            if(!new HashSet<>(evidence).equals(new HashSet<>(attachments.stream().map(row->(UUID)row.get("id")).toList())))throw ApiFault.invalid("必须核验本次完整凭据清单，不得混入其他提交");
            for(var attachment:attachments)verifyFile(attachment);
            var instance=db.required("SELECT * FROM workflow.instance WHERE id=?",node.get("instance_id"));workflow.verifyEvidenceBusiness(instance);
            UUID decision=UUID.randomUUID();db.update("INSERT INTO workflow.decision(id,step_id,result,original_approver_id,operator_id,decision_mode,agreed_at,comment,idempotency_key) VALUES(?,?,'APPROVE',?,?,'EVIDENCE_RECORD',?::timestamptz,?,?)",decision,step,approver,ctx.actor().userId(),agreed.toString(),body.get("comment"),ctx.route()+":"+ctx.operation());
            db.update("UPDATE workflow.evidence_submission SET state='USED',reviewed_by=?,review_note=?,reviewed_at=CURRENT_TIMESTAMP,decision_id=?,row_version=row_version+1 WHERE id=?",ctx.actor().userId(),body.get("reason"),decision,submission.get("id"));
            workflow.advance(instance,step,true,ctx.actor().userId());
            for(UUID recipient:List.of(approver,(UUID)node.get("applicant_id")))db.update("INSERT INTO ops.business_notification(id,event_key,user_id,source_kind,source_id,kind) VALUES(?,?,?,'EVIDENCE_DECISION',?,'EVIDENCE_RECORDED') ON CONFLICT(event_key) DO NOTHING",UUID.randomUUID(),"evidence:"+decision+":"+recipient,recipient,decision);
            audit.event(ctx.actor().userId(),"ARCHIVE","WORKFLOW_STEP",step,"EVIDENCE_RECORD","SUCCEEDED",ctx.request());return detail(ctx.actor(),(UUID)submission.get("id"));
        },id->detail(ctx.actor(),id));
    }
    /** 退回只结束凭据核验，不代替业务驳回；申请人撤回后可重新提交真实材料。 */
    public Map<String,Object> close(Commands.Context ctx,UUID id,Map<String,Object> body,boolean reject) {
        Inputs.only(body,"reason");Inputs.text(body,"reason",2000);var initial=db.required("SELECT step_id FROM workflow.evidence_submission WHERE id=?",id);
        java.util.function.Supplier<Map<String,Object>> mutation=()->{lockedNode((UUID)initial.get("step_id"));var row=db.required("SELECT * FROM workflow.evidence_submission WHERE id=? FOR UPDATE",id);Commands.version(ctx,row);if(!"PENDING".equals(row.get("state")))throw ApiFault.conflict("此凭据已处理");db.update("UPDATE workflow.evidence_submission SET state=?,reviewed_by=?,review_note=?,reviewed_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=?",reject?"REJECTED":"CANCELLED",ctx.actor().userId(),body.get("reason"),id);return detail(ctx.actor(),id);};
        if(reject)return commands.run(ctx,body,"WORKFLOW_STEP",(UUID)initial.get("step_id"),"REJECT_EVIDENCE",()->identity.background(ctx.actor().sessionId()).require("PLATFORM.EVIDENCE_RECORD"),mutation,key->detail(ctx.actor(),key));
        return archiveCommands.run(ctx,body,"WORKFLOW_STEP",(UUID)initial.get("step_id"),"CANCEL_EVIDENCE",false,()->{if(!ctx.actor().userId().equals(db.required("SELECT submitted_by FROM workflow.evidence_submission WHERE id=?",id).get("submitted_by")))throw ApiFault.hidden();},mutation,key->detail(ctx.actor(),key));
    }
    /** 原件响应携带固定长度和媒体类型；不会返回磁盘路径或可转发的公开链接。 */
    public record Content(/** 已验证完整原件。 */ byte[] bytes,/** 实测媒体类型。 */ String media,/** 显示名称。 */ String name) { }
    /** 逐次检查身份、保护和双副本后读取；内存上限为单件五十 MiB，损坏原件停止交付。 */
    @Transactional public Content content(Actor actor,UUID id,UUID request) {
        identity.lockActor(actor);var row=db.required("SELECT e.*,s.step_id,s.submitted_by,s.original_approver_id,o.storage_key,o.state AS object_state,o.sha256,o.byte_size,o.original_name FROM workflow.evidence e JOIN workflow.evidence_submission s ON s.id=e.submission_id JOIN storage.object o ON o.id=e.object_id WHERE e.id=?",id);
        authorizeRead(actor,row,true);protection.requireReadable("ARCHIVE");verifyFile(row);
        try {byte[] bytes=Files.readAllBytes(files.get().verifiedPrimary((String)row.get("storage_key"),((Number)row.get("size")).longValue(),(String)row.get("digest")));if(!Crypto.equal(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),(String)row.get("digest")))throw SystemFailure.WORKFLOW_EVIDENCE_INVALID.fault();audit.event(actor.userId(),"ARCHIVE","EVIDENCE",id,"READ_EVIDENCE","SUCCEEDED",request);return new Content(bytes,(String)row.get("format"),(String)row.get("original_name"));}catch(ApiFault e){throw e;}catch(Exception e){throw SystemFailure.WORKFLOW_EVIDENCE_INVALID.fault(e);}
    }
    /** 元数据与两份原件必须同时匹配提交时摘要；检测失效不能继续补录或读取。 */
    private void verifyFile(Map<String,Object> row) {if(!"READY".equals(row.get("object_state"))||!row.get("digest").equals(row.get("sha256"))||((Number)row.get("size")).longValue()!=((Number)row.get("byte_size")).longValue()||!files.get().verified((String)row.get("storage_key"),((Number)row.get("size")).longValue(),(String)row.get("digest")))throw SystemFailure.WORKFLOW_EVIDENCE_INVALID.fault();}
    /** 节点和流程的最小内部视图，禁止直接返回给浏览器。 */
    private Map<String,Object> node(UUID id) {return db.required("SELECT s.*,w.state AS flow_state,w.current_step_id,w.applicant_id,w.business_id,w.business_snapshot,w.created_at AS flow_created_at FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id WHERE s.id=?",id);}
    /** 流程先于节点加锁，避免补录与在线决定互相倒置锁序。 */
    private Map<String,Object> lockedNode(UUID id) {var first=node(id);db.required("SELECT id FROM workflow.instance WHERE id=? FOR UPDATE",first.get("instance_id"));db.required("SELECT id FROM workflow.step WHERE id=? FOR UPDATE",id);return node(id);}
    /** 结束、非当前、回避和不支持的职责一律拒绝，不能补录旧节点或撤销驳回。 */
    private void current(Map<String,Object> node) {if(!DUTIES.contains(node.get("duty_code"))||!"PENDING".equals(node.get("flow_state"))||!"PENDING".equals(node.get("state"))||!node.get("id").equals(node.get("current_step_id")))throw ApiFault.conflict("仅可为当前待审的部门负责人或总经理节点提交凭据");}
    /** 有界且不重复的原件或凭据清单，不能通过重复标识绕过数量校验。 */
    private List<UUID> ids(Object value) {if(!(value instanceof List<?> list)||list.isEmpty()||list.size()>10)throw ApiFault.invalid("凭据应为一至十份");List<UUID> result=list.stream().map(Inputs::uuid).toList();if(new HashSet<>(result).size()!=result.size())throw ApiFault.invalid("凭据不能重复");return result;}
    /** 数据库精度为微秒，客户端回传必须对应同一实际同意时刻。 */
    private Instant instant(Object value) {try{return Instant.parse(Objects.toString(value,"")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}catch(Exception e){throw ApiFault.invalid("请填写带时区的实际同意时间");}}
}
