package com.company.platform.workflow;

import com.company.platform.*;
import com.company.platform.archive.*;
import com.company.platform.storage.StorageBoundaries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 人员连续性：档案员替换与未完事项原子交接，转岗回收旧个人权限而保留实物责任。 */
@Service
public class PersonnelContinuity {
    /** 通用管理事务协调身份，档案权限独立判断。 */
    private final Sql db;private final Commands commands;private final IdentityService identity;private final ArchiveAuthority authority;
    private final WorkflowEngine workflow;private final DirectoryService directory;private final StorageBoundaries protection;private final Audit audit;
    /** 注入同一数据库事务内的各领域服务，独立账本只承担安全收紧。 */
    public PersonnelContinuity(Sql db,Commands commands,IdentityService identity,ArchiveAuthority authority,WorkflowEngine workflow,DirectoryService directory,StorageBoundaries protection,Audit audit){this.db=db;this.commands=commands;this.identity=identity;this.authority=authority;this.workflow=workflow;this.directory=directory;this.protection=protection;this.audit=audit;}
    /** 交接候选仅返回姓名、工号及部门，不开放账号密钥或个人借阅清单。 */
    public Map<String,Object> options(Actor actor,UUID department){authority.requireSuper(identity.background(actor.sessionId()),"ARCHIVE.CONFIG_MANAGE");return Sql.map("departments",db.rows("SELECT id,name FROM iam.department WHERE status='ACTIVE' ORDER BY code LIMIT 200"),"people",department==null?List.of():db.rows("SELECT id,name,employee_no FROM iam.user_account WHERE department_id=? AND status='ACTIVE' AND NOT password_setup_required ORDER BY employee_no LIMIT 200",department).stream().map(r->Sql.view(r,"id,name,employee_no")).toList(),"current",department==null?null:db.one("SELECT a.user_id,u.name FROM iam.user_role a JOIN iam.user_account u ON u.id=a.user_id WHERE a.module='ARCHIVE' AND a.role_code='DEPARTMENT_ARCHIVIST' AND a.state='ACTIVE' AND a.department_id=?",department));}
    /** 预览固定事项版本和人员代次；接任人不必预先任职，避免一部门一档案员约束形成死锁。 */
    public Map<String,Object> preview(Actor actor,Map<String,Object> body){authority.requireSuper(identity.background(actor.sessionId()),"ARCHIVE.CONFIG_MANAGE");Inputs.only(body,"departmentId","fromUserId","toUserId");return previewData(Inputs.uuid(body.get("departmentId")),Inputs.uuid(body.get("fromUserId")),Inputs.uuid(body.get("toUserId")));}
    /** 稳定排序后计算摘要；超过单次事务上限明确拒绝，不能截断后假称完成交接。 */
    private Map<String,Object> previewData(UUID department,UUID from,UUID to){
        if(from.equals(to))throw ApiFault.invalid("原任与接任人不能相同");db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE'",department);
        var original=db.required("SELECT a.id,a.user_id,u.auth_version,u.row_version,u.name FROM iam.user_role a JOIN iam.user_account u ON u.id=a.user_id WHERE a.module='ARCHIVE' AND a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=? AND a.user_id=? AND a.state='ACTIVE'",department,from);
        var target=db.required("SELECT id,name,auth_version,row_version FROM iam.user_account WHERE id=? AND department_id=? AND status='ACTIVE' AND NOT password_setup_required",to,department);
        if(db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND module='ARCHIVE' AND state='ACTIVE' AND role_code<>'STAFF'",to)>0)throw ApiFault.conflict("接任人已有其他档案管理任职，请先核对职责");
        var tasks=db.rows("SELECT s.id,s.instance_id,s.state,s.row_version,w.row_version AS workflow_version,w.applicant_id,s.duty_code FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id WHERE s.assignee_id=? AND s.department_id=? AND s.duty_code='ARCHIVIST' AND s.state IN ('PENDING','WAITING') AND w.state='PENDING' ORDER BY s.instance_id,s.sequence_no LIMIT 1001",from,department);
        var changes=db.rows("SELECT id,object_type,object_id,state,row_version,workflow_id FROM archive.catalog_change WHERE department_id=? AND COALESCE(managed_by,created_by)=? AND state IN ('DRAFT','PENDING') ORDER BY id LIMIT 1001",department,from);
        if(tasks.size()>1000||changes.size()>1000)throw ApiFault.conflict("未完事项超过单次交接上限，请由管理员分批核对后再办理");
        if(tasks.stream().anyMatch(task->to.equals(task.get("applicant_id"))))throw ApiFault.conflict("接任人是待移交事项申请人，不能继承本人审批节点；请先完成或撤回该申请");
        long physical=db.count("SELECT count(*) FROM archive.physical_loan l JOIN archive.box b ON b.id=l.box_id WHERE b.department_id=? AND l.state='IN_USE'",department);
        var snapshot=Sql.map("departmentId",department,"fromUserId",from,"toUserId",to,"originalRoleId",original.get("id"),"fromName",original.get("name"),"toName",target.get("name"),"fromAuthVersion",original.get("auth_version"),"toAuthVersion",target.get("auth_version"),"fromVersion",original.get("row_version"),"toVersion",target.get("row_version"),"steps",tasks.stream().map(r->Sql.view(r,"id,instance_id,state,row_version,workflow_version,applicant_id,duty_code")).toList(),"catalogChanges",changes.stream().map(r->Sql.view(r,"id,object_type,object_id,state,row_version,workflow_id")).toList(),"outstandingPhysicalLoans",physical);
        snapshot.put("previewDigest",Crypto.hash(Crypto.json(snapshot)));return snapshot;
    }
    /** 交接与任职一起提交；失败不留半套新任职，个人授权不随职责继承。 */
    @Transactional public Map<String,Object> handover(Commands.Context ctx,Map<String,Object> body){
        Inputs.only(body,"departmentId","fromUserId","toUserId","previewDigest","approvalReference","reason");UUID department=Inputs.uuid(body.get("departmentId")),from=Inputs.uuid(body.get("fromUserId")),to=Inputs.uuid(body.get("toUserId"));String digest=Inputs.text(body,"previewDigest",64);Inputs.text(body,"approvalReference",256);Inputs.text(body,"reason",1000);
        db.rows("SELECT pg_advisory_xact_lock(61427002)");
        // 账号按 UUID 顺序，与决定接口保持一致；低频交接仅锁实际受影响的流程相关人员。
        db.rows("SELECT id FROM iam.user_account WHERE id IN (?,?,?) OR id IN (SELECT w.applicant_id FROM workflow.instance w JOIN workflow.step s ON s.instance_id=w.id WHERE s.assignee_id=? AND w.state='PENDING') ORDER BY id FOR UPDATE",ctx.actor().userId(),from,to,from);
        return commands.run(ctx,body,"DEPARTMENT",department,"HANDOVER_ARCHIVIST",()->authority.requireSuper(identity.background(ctx.actor().sessionId()),"ARCHIVE.CONFIG_MANAGE"),()->{
            protection.requireWritable("ARCHIVE","HANDOVER_ARCHIVIST");
            // 与固定路线创建共用短事务锁，防止预览后新增流程仍绑定即将结束的任职。
            db.rows("SELECT pg_advisory_xact_lock(61427003)");
            for(var row:db.rows("SELECT DISTINCT w.id FROM workflow.instance w JOIN workflow.step s ON s.instance_id=w.id WHERE w.state='PENDING' AND s.assignee_id=? ORDER BY w.id",from))db.required("SELECT id FROM workflow.instance WHERE id=? FOR UPDATE",row.get("id"));
            var snapshot=previewData(department,from,to);if(!Crypto.equal(digest,(String)snapshot.get("previewDigest")))throw ApiFault.conflict("交接清单或人员状态已变化，请重新预览确认");
            UUID batch=UUID.randomUUID(),assignment=UUID.randomUUID();db.update("INSERT INTO workflow.archivist_handover(id,department_id,from_user_id,to_user_id,operator_id,preview_digest,approval_reference,reason,items_snapshot) VALUES(?,?,?,?,?,?,?,?,?::jsonb)",batch,department,from,to,ctx.actor().userId(),digest,body.get("approvalReference"),body.get("reason"),Crypto.json(snapshot));
            db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP,handover_ref=? WHERE id=? AND state='ACTIVE'",batch.toString(),snapshot.get("originalRoleId"));
            UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='ARCHIVE' AND code='DEPARTMENT_ARCHIVIST' AND status='ACTIVE'").get("id");
            db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,approval_ref,handover_ref,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,?,?,'ARCHIVE','DEPARTMENT_ARCHIVIST')",assignment,to,role,department,ctx.actor().userId(),body.get("approvalReference"),batch.toString());
            ensureStaff(from,ctx.actor().userId(),batch);
            if(db.count("SELECT CASE WHEN workflow.assignment_valid('ROLE',?,?) THEN 1 ELSE 0 END",assignment,to)!=1)throw ApiFault.conflict("接任人档案入口被禁用，不能移交待办");
            for(var item:Inputs.objects(snapshot.get("steps"),1000)){
                UUID step=Inputs.uuid(item.get("id"));db.update("INSERT INTO workflow.assignment_event(id,step_id,from_user_id,to_user_id,operator_id,reason,approval_reference,department_id,handover_batch_id) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),step,from,to,ctx.actor().userId(),body.get("reason"),body.get("approvalReference"),department,batch);
                db.update("UPDATE workflow.step SET assignee_id=?,assignment_type='ROLE',assignment_ref=?,row_version=row_version+1 WHERE id=? AND state IN ('PENDING','WAITING')",to,assignment,step);
            }
            db.update("UPDATE workflow.instance SET row_version=row_version+1 WHERE id IN (SELECT s.instance_id FROM workflow.assignment_event e JOIN workflow.step s ON s.id=e.step_id WHERE e.handover_batch_id=?)",batch);
            for(var item:Inputs.objects(snapshot.get("catalogChanges"),1000))db.update("UPDATE archive.catalog_change SET managed_by=?,row_version=row_version+1 WHERE id=?",to,Inputs.uuid(item.get("id")));
            db.update("UPDATE iam.user_account SET row_version=row_version+1 WHERE id IN (?,?)",from,to);
            audit.event(ctx.actor().userId(),"ARCHIVE","DEPARTMENT",department,"HANDOVER_ARCHIVIST","SUCCEEDED",ctx.request());
            // 所有关系验证和 SQL 写入完成后才推进独立安全账本；故障时按收紧方向关闭旧会话。
            identity.invalidate(from,"ARCHIVIST_HANDOVER",ctx.operation());identity.invalidate(to,"ARCHIVIST_HANDOVER",ctx.operation());return handoverResult(batch);
        },this::handoverResult);
    }
    /** 原档案员回归普通员工，保留本人已有合法借阅；不会得到接任人的使用权限。 */
    private void ensureStaff(UUID person,UUID operator,UUID batch){if(db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND module='ARCHIVE' AND role_code='STAFF' AND state='ACTIVE'",person)==0)db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code,handover_ref) SELECT ?,?,id,CURRENT_TIMESTAMP,?,'ARCHIVE','STAFF',? FROM iam.role WHERE module='ARCHIVE' AND code='STAFF' AND status='ACTIVE'",UUID.randomUUID(),person,operator,batch.toString());}
    /** 幂等回执只返回交接事实，不返回历史授权快照。 */
    private Map<String,Object> handoverResult(UUID id){return Sql.view(db.required("SELECT * FROM workflow.archivist_handover WHERE id=?",id),"id,department_id,from_user_id,to_user_id,created_at,approval_reference,reason");}
    /** 交接事件按部门或批次有界检索；普通员工和平台 IT 不获得档案交接清单。 */
    public Map<String,Object> events(Actor actor,Map<String,String> query){authority.requireSuper(identity.background(actor.sessionId()),"ARCHIVE.CONFIG_MANAGE");Inputs.only(query,"departmentId","handoverBatchId","page","pageSize");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String where=" FROM workflow.assignment_event e WHERE 1=1";List<Object> args=new ArrayList<>();if(query.containsKey("departmentId")){where+=" AND e.department_id=?";args.add(Inputs.uuid(query.get("departmentId")));}if(query.containsKey("handoverBatchId")){where+=" AND e.handover_batch_id=?";args.add(Inputs.uuid(query.get("handoverBatchId")));}long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);return Sql.map("items",db.rows("SELECT e.*"+where+" ORDER BY e.created_at DESC,e.id LIMIT ? OFFSET ?",args.toArray()).stream().map(r->Sql.view(r,"id,step_id,department_id,handover_batch_id,from_user_id,to_user_id,operator_id,reason,approval_reference,created_at")).toList(),"total",total,"page",page,"pageSize",size);}
    /** 转岗预览仅显示授权和实物责任数量，不向 IT 泄露档案正文或借阅题名。 */
    public Map<String,Object> transferPreview(Actor actor,UUID user){identity.background(actor.sessionId()).require("PLATFORM.USERS_MANAGE");return Sql.map("user",directory.user(user),"counts",counts(user),"blockers",blockers(user));}
    /** 转岗部门选择只需人员管理权，返回最小名称，不借道开放组织维护配置。 */
    public Map<String,Object> departmentOptions(Actor actor){identity.background(actor.sessionId()).require("PLATFORM.USERS_MANAGE");return Sql.map("items",db.rows("SELECT id,name FROM iam.department WHERE status='ACTIVE' ORDER BY code LIMIT 1000"));}
    /** 高风险转岗先收回旧个人权限和在审申请，再修改部门；新岗位权限另按批准依据配置。 */
    @Transactional public Map<String,Object> transfer(Commands.Context ctx,UUID user,Map<String,Object> body){
        Inputs.only(body,"targetDepartmentId","approvalRef","handoverNote");UUID target=Inputs.uuid(body.get("targetDepartmentId"));Inputs.text(body,"approvalRef",256);Inputs.text(body,"handoverNote",2000);
        db.rows("SELECT pg_advisory_xact_lock(61427002)");db.rows("SELECT id FROM iam.user_account WHERE id IN (?,?) ORDER BY id FOR UPDATE",ctx.actor().userId(),user);
        return commands.run(ctx,body,"USER",user,"CHANGE_DEPARTMENT",()->identity.background(ctx.actor().sessionId()).require("PLATFORM.USERS_MANAGE"),()->{
            if(user.equals(ctx.actor().userId()))throw ApiFault.denied();var person=db.required("SELECT * FROM iam.user_account WHERE id=? FOR UPDATE",user);Commands.version(ctx,person);
            UUID change=moveDepartment(ctx,person,target,(String)body.get("approvalRef"),(String)body.get("handoverNote"),false);
            identity.invalidate(user,"DEPARTMENT_CHANGE",ctx.operation());return transferResult(change);
        },this::transferResult);
    }
    /** 人员编辑复用转岗回收规则，保留公司级角色；调用者已校验字段、IT 身份和人员版本。 */
    public void changeDepartmentForProfile(Commands.Context ctx,Map<String,Object> person,UUID target,String reason) {
        moveDepartment(ctx,person,target,"PROFILE-"+ctx.operation(),reason,true);
    }
    /** 同一事务处理部门、旧授权、在审申请和交接记录；身份代次由调用者在所有资料保存后推进。 */
    private UUID moveDepartment(Commands.Context ctx,Map<String,Object> person,UUID target,String approval,String note,boolean retainCompanyRoles) {
        UUID user=(UUID)person.get("id");
        if(target.equals(person.get("department_id")))throw ApiFault.invalid("新部门与当前部门相同");db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE'",target);
            var blockers=blockers(user);if(!blockers.isEmpty())throw ApiFault.conflict(String.join("；",blockers));
            // 先锁盒再锁流程，与纸质交付和决定保持同序，原实物借出不撤销。
            for(var box:db.rows("SELECT DISTINCT p.box_id FROM archive.paper_request_item p JOIN workflow.instance w ON w.business_id=p.request_id WHERE w.applicant_id=? AND w.state='PENDING' ORDER BY p.box_id",user))db.required("SELECT id FROM archive.box WHERE id=? FOR UPDATE",box.get("box_id"));
            var before=counts(user);
            if(retainCompanyRoles)before.put("roles",db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE' AND department_id IS NOT NULL",user));
            workflow.cancelForDepartmentChange(user,ctx.actor().userId());
            db.update("UPDATE iam.permission_assignment SET state='REVOKED' WHERE user_id=? AND state='ACTIVE'",user);
            db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE user_id=? AND state='ACTIVE'"+(retainCompanyRoles?" AND department_id IS NOT NULL":""),user);
            db.update("UPDATE archive.access_grant SET state='REVOKED',revoked_by=?,revoked_at=CURRENT_TIMESTAMP,revocation_reason='员工转岗回收旧个人授权',policy_version=policy_version+1 WHERE user_id=? AND state='ACTIVE'",ctx.actor().userId(),user);
            db.update("UPDATE drive.resource_grant SET state='REVOKED',revoked_by=?,revoked_at=CURRENT_TIMESTAMP,version=version+1 WHERE subject_id=? AND state='ACTIVE'",ctx.actor().userId(),user);
            db.update("UPDATE archive.usage_request SET state='INVALIDATED' WHERE user_id=? AND state IN ('PENDING','READY')",user);
            db.update("UPDATE iam.user_account SET department_id=?,row_version=row_version+1 WHERE id=?",target,user);
            UUID change=UUID.randomUUID();db.update("INSERT INTO iam.department_change(id,user_id,from_department_id,to_department_id,operator_id,approval_ref,handover_note,revoked_counts) VALUES(?,?,?,?,?,?,?,?::jsonb)",change,user,person.get("department_id"),target,ctx.actor().userId(),approval,note,Crypto.json(before));
            audit.event(ctx.actor().userId(),"ARCHIVE","USER",user,"DEPARTMENT_CHANGE_REVOKE","SUCCEEDED",ctx.request());audit.event(ctx.actor().userId(),"DRIVE","USER",user,"DEPARTMENT_CHANGE_REVOKE","SUCCEEDED",ctx.request());return change;
    }
    /** 未交接管理职责不允许靠员工转岗接口隐式接管或失联。 */
    private List<String> blockers(UUID user){List<String> result=new ArrayList<>();if(db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND module='ARCHIVE' AND role_code='DEPARTMENT_ARCHIVIST' AND state='ACTIVE'",user)>0)result.add("请先完成档案员交接");if(db.count("SELECT count(*) FROM iam.department_manager WHERE user_id=? AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)",user)>0||db.count("SELECT count(*) FROM iam.company_duty WHERE user_id=? AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)",user)>0)result.add("请先完成部门负责人或公司审批职责调整");return result;}
    /** 只统计需要收回的个人范围；角色签发给他人的业务授权不会被误删。 */
    private Map<String,Object> counts(UUID user){return Sql.map("roles",db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",user),"personalPermissions",db.count("SELECT count(*) FROM iam.permission_assignment WHERE user_id=? AND state='ACTIVE'",user),"archiveGrants",db.count("SELECT count(*) FROM archive.access_grant WHERE user_id=? AND state='ACTIVE'",user),"driveGrants",db.count("SELECT count(*) FROM drive.resource_grant WHERE subject_id=? AND state='ACTIVE'",user),"pendingWorkflows",db.count("SELECT count(*) FROM workflow.instance WHERE applicant_id=? AND state='PENDING' AND (business_type IN ('BORROW','ACCESS_POLICY') OR (business_type='CATALOG_CHANGE' AND EXISTS(SELECT 1 FROM archive.catalog_change c WHERE c.id=business_id AND COALESCE(c.managed_by,c.created_by)=applicant_id)))",user),"outstandingPhysicalLoans",db.count("SELECT count(*) FROM archive.physical_loan WHERE borrower_id=? AND state='IN_USE'",user));}
    /** 转岗记录作为回执，用户再次重试不重复撤权或改变新岗位配置。 */
    private Map<String,Object> transferResult(UUID id){var row=db.required("SELECT * FROM iam.department_change WHERE id=?",id);var result=Sql.view(row,"id,user_id,from_department_id,to_department_id,created_at");result.put("counts",Crypto.JSON.readValue(row.get("revoked_counts").toString(),Map.class));return result;}
}
