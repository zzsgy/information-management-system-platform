package com.company.platform.workflow;

import com.company.platform.*;
import com.company.platform.archive.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 固定路线引擎：当前节点独占决定、原人员与职责重验、本人回避、结果和业务原子提交。 */
@Service
public class WorkflowEngine {
    /** 业务适配器延迟解析，目录、借阅及授权配置不产生构造器循环依赖。 */
    private final Sql db;private final WorkflowDuties duties;private final ArchiveCommands commands;private final ArchiveAuthority authority;private final ObjectProvider<WorkflowBusiness> businesses;
    public WorkflowEngine(Sql db,WorkflowDuties duties,ArchiveCommands commands,ArchiveAuthority authority,ObjectProvider<WorkflowBusiness> businesses) {this.db=db;this.duties=duties;this.commands=commands;this.authority=authority;this.businesses=businesses;}
    /** 本方法由调用者已有短事务承接，不对HTTP开放任意路线创建接口。 */
    public UUID create(Actor applicant,String type,UUID business,UUID batch,String highest,int calendar,Map<String,Object> snapshot,List<FixedBorrowRoute.Duty> route) {
        // 纸质、电子及续借统一在送审时固定 IT 通用账号的路线，供后续交付与续借批准读取。
        boolean itSharedBorrow = "BORROW".equals(type) && applicant.sharedItBorrower();
        if (itSharedBorrow) {
            route = Boolean.TRUE.equals(snapshot.get("unwatermarked"))
                ? List.of(new FixedBorrowRoute.Duty("GENERAL_MANAGER",null),new FixedBorrowRoute.Duty("ARCHIVE_ADMIN",null))
                : List.of(new FixedBorrowRoute.Duty("ARCHIVE_ADMIN", null));
            snapshot = new LinkedHashMap<>(snapshot);
            snapshot.put("itSharedBorrow", true);
        }
        // 与档案员整批交接协调路线创建，防止新流程绑定刚结束的旧任职；不影响读取或文件处理。
        db.rows("SELECT pg_advisory_xact_lock(61427003)");
        String templateKey=switch(type){case "CATALOG_CHANGE"->"ARCHIVE_CATALOG_CHANGE_V1";case "REGISTRATION"->"ARCHIVE_REGISTRATION_V1";case "BORROW"->"ARCHIVE_BORROW_V1";case "ACCESS_POLICY"->"ARCHIVE_ACCESS_POLICY_V1";case "PHYSICAL_RETURN"->"ARCHIVE_PHYSICAL_RETURN_V1";default->throw SystemFailure.WORKFLOW_CONFIGURATION_INVALID.fault();};
        int templateVersion="BORROW".equals(type)&&(Boolean.TRUE.equals(snapshot.get("paperHandoffs"))||snapshot.containsKey("unwatermarked"))?2:1;
        var template=db.required("SELECT *,nodes_definition::text AS definition FROM workflow.template_version WHERE business_type=? AND template_key=? AND version_no=? AND state='ACTIVE'",type,templateKey,templateVersion);
        if(!Crypto.equal(Crypto.hash((String)template.get("definition")),(String)template.get("checksum"))) throw SystemFailure.WORKFLOW_CONFIGURATION_INVALID.fault();
        UUID instance=UUID.randomUUID(),routeId=UUID.randomUUID();List<Map<String,Object>> nodes=new ArrayList<>();
        for (int index = 0; index < route.size(); index++) {
            var duty = route.get(index);
            var person = itSharedBorrow ? duties.resolve(duty, applicant.userId()) : duties.resolve(duty);
            nodes.add(Sql.map("id",UUID.randomUUID(),"sequence",index+1,"duty",duty.code(),"departmentId",duty.department(),"userId",person.user(),"assignmentType",person.type(),"assignmentRef",person.reference()));
        }
        if(nodes.isEmpty()) throw SystemFailure.WORKFLOW_CONFIGURATION_INVALID.fault();
        int round=(int)db.count("SELECT COALESCE(max(round_no),0)+1 FROM workflow.instance WHERE business_type=? AND business_id=?",type,business);
        db.update("INSERT INTO workflow.instance(id,business_type,business_id,applicant_id,template_version_id,route_snapshot_id,business_snapshot,round_no) VALUES(?,?,?,?,?,?,?::jsonb,?)",instance,type,business,applicant.userId(),template.get("id"),routeId,Crypto.json(snapshot),round);
        db.update("INSERT INTO workflow.route_snapshot(id,instance_id,batch_id,highest_classification,form,applicant_role,department_context,over_week,calendar_version,template_version,nodes_snapshot) VALUES(?,?,?,?,? ,?,?::jsonb,?,?,?,?::jsonb)",routeId,instance,batch,highest,type.equals("BORROW")?snapshot.getOrDefault("form","ELECTRONIC"):null,authority.role(applicant,"DEPARTMENT_ARCHIVIST",Inputs.uuid(applicant.user().get("departmentId")))?"ARCHIVIST":"MEMBER",Crypto.json(Sql.map("applicantDepartmentId",applicant.user().get("departmentId"),"ownerDepartmentId",snapshot.get("departmentId"))),route.stream().anyMatch(duty->duty.code().equals("SUPERVISING_LEADER")),calendar==0?null:calendar,template.get("version_no"),Crypto.json(nodes));
        for(var node:nodes) db.update("INSERT INTO workflow.step(id,instance_id,sequence_no,duty_code,department_id,assignee_id,assignment_type,assignment_ref,state,due_at) VALUES(?,?,?,?,?,?,?,?,?,CASE WHEN ? THEN CURRENT_TIMESTAMP+interval '24 hours' ELSE NULL END)",node.get("id"),instance,node.get("sequence"),node.get("duty"),node.get("departmentId"),node.get("userId"),node.get("assignmentType"),node.get("assignmentRef"),node.get("sequence").equals(1)?"PENDING":"WAITING",node.get("sequence").equals(1));
        db.update("UPDATE workflow.instance SET current_step_id=? WHERE id=?",nodes.getFirst().get("id"),instance);return instance;
    }
    /** 先按稳定顺序锁实际账号，再进入通用命令，避免审批双方交叉借阅形成账号等待环路。 */
    @Transactional
    public Map<String,Object> decide(Commands.Context context,UUID stepId,Map<String,Object> body) {
        Inputs.only(body,"result","comment");String result=Inputs.choice(body.get("result"),"APPROVE","REJECT");
        Object opinion=body.get("comment");String comment=opinion==null||opinion instanceof String text&&text.isBlank()?"":Inputs.text(body,"comment",2000);
        var preflight=db.required("SELECT s.instance_id,w.applicant_id,w.business_id,w.business_type,w.business_snapshot->>'form' AS form FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id WHERE s.id=?",stepId);
        db.rows("SELECT id FROM iam.user_account WHERE id IN (?,?) ORDER BY id FOR UPDATE",context.actor().userId(),preflight.get("applicant_id"));
        // 纸质决定可能改变实际借出期限，与交付/归还保持身份后盒的同一锁序。
        if("PHYSICAL_RETURN".equals(preflight.get("business_type")))
            db.required("SELECT b.id FROM archive.physical_return r JOIN archive.physical_loan l ON l.id=r.loan_id JOIN archive.box b ON b.id=l.box_id WHERE r.id=? FOR UPDATE OF b",preflight.get("business_id"));
        else if("PAPER".equals(preflight.get("form")))for(var box:db.rows("SELECT DISTINCT box_id FROM archive.paper_request_item WHERE request_id=? ORDER BY box_id",preflight.get("business_id")))db.required("SELECT id FROM archive.box WHERE id=? FOR UPDATE",box.get("box_id"));
        return commands.run(context,body,"WORKFLOW_STEP",stepId,"DECIDE_STEP",true,()->{var step=db.required("SELECT * FROM workflow.step WHERE id=?",stepId);duties.requireCurrent(step,context.actor().userId());},()->{
            var instance=db.required("SELECT * FROM workflow.instance WHERE id=? FOR UPDATE",preflight.get("instance_id"));var step=db.required("SELECT * FROM workflow.step WHERE id=? FOR UPDATE",stepId);Commands.version(context,step);
            if(!"PENDING".equals(instance.get("state"))||!stepId.equals(instance.get("current_step_id"))||!"PENDING".equals(step.get("state"))||context.actor().userId().equals(instance.get("applicant_id"))) throw new ApiFault(409,"STEP_ALREADY_DECIDED_OR_SELF_APPROVAL","节点已处理、并非当前节点或需要本人回避");
            duties.requireCurrent(step,context.actor().userId());WorkflowBusiness business=business((String)instance.get("business_type"));
            // 只有批准可能扩权，需重验固定版本；驳回失效申请不应被原对象变化阻塞。
            if(result.equals("APPROVE")) business.verify(instance);
            UUID decision=UUID.randomUUID();db.update("INSERT INTO workflow.decision(id,step_id,result,original_approver_id,operator_id,decision_mode,comment,idempotency_key) VALUES(?,?,?,?,?,'ONLINE',?,?)",decision,stepId,result,context.actor().userId(),context.actor().userId(),comment,context.route()+":"+context.operation());
            advance(instance,stepId,result.equals("APPROVE"),context.actor().userId());
            return decision(decision,context.actor());
        },id->decision(id,context.actor()));
    }
    /** 补录仅复用业务核验，不暴露绕过当前节点和凭据校验的 HTTP 接口。调用者持有流程与节点锁。 */
    void verifyEvidenceBusiness(Map<String,Object> instance) { business((String)instance.get("business_type")).verify(instance); }
    /** 在线决定与凭据决定共用唯一推进路径；先保存决定和凭据，再生成最终授权。 */
    void advance(Map<String,Object> instance,UUID stepId,boolean approved,UUID operator) {
        db.update("UPDATE workflow.step SET state=?,completed_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=?",approved?"APPROVED":"REJECTED",stepId);
        var next=approved?db.one("SELECT * FROM workflow.step WHERE instance_id=? AND state='WAITING' ORDER BY sequence_no LIMIT 1 FOR UPDATE",instance.get("id")):null;
        if(next!=null) {db.update("UPDATE workflow.step SET state='PENDING',due_at=CURRENT_TIMESTAMP+interval '24 hours' WHERE id=?",next.get("id"));db.update("UPDATE workflow.instance SET current_step_id=?,row_version=row_version+1 WHERE id=?",next.get("id"),instance.get("id"));}
        else {db.update("UPDATE workflow.instance SET state=?,current_step_id=NULL,row_version=row_version+1 WHERE id=?",approved?"APPROVED":"REJECTED",instance.get("id"));business((String)instance.get("business_type")).finish(db.required("SELECT * FROM workflow.instance WHERE id=?",instance.get("id")),approved,operator);}
    }
    /** 员工转岗时终止其旧部门的在审事项，保留决定历史；归还等实物责任不属于此入口。 */
    public void cancelForDepartmentChange(UUID person,UUID operator) {
        for(var instance:db.rows("SELECT * FROM workflow.instance WHERE applicant_id=? AND state='PENDING' AND (business_type IN ('BORROW','ACCESS_POLICY') OR (business_type='CATALOG_CHANGE' AND EXISTS(SELECT 1 FROM archive.catalog_change c WHERE c.id=business_id AND COALESCE(c.managed_by,c.created_by)=applicant_id))) ORDER BY id FOR UPDATE",person)) {
            db.update("UPDATE workflow.instance SET state='WITHDRAWN',current_step_id=NULL,row_version=row_version+1 WHERE id=?",instance.get("id"));
            business((String)instance.get("business_type")).finish(db.required("SELECT * FROM workflow.instance WHERE id=?",instance.get("id")),false,operator);
            db.update("UPDATE workflow.evidence_submission SET state='CANCELLED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,review_note='申请人转岗，旧部门申请终止',row_version=row_version+1 WHERE state='PENDING' AND step_id IN (SELECT id FROM workflow.step WHERE instance_id=?)",operator,instance.get("id"));
        }
    }
    /** 原决定按本人或原申请人范围重读；不把幂等回执当成公开业务查询。 */
    private Map<String,Object> decision(UUID id,Actor actor) {var row=db.required("SELECT d.*,w.applicant_id FROM workflow.decision d JOIN workflow.step s ON s.id=d.step_id JOIN workflow.instance w ON w.id=s.instance_id WHERE d.id=?",id);if(!actor.userId().equals(row.get("operator_id"))&&!actor.userId().equals(row.get("applicant_id"))) throw ApiFault.hidden();return Sql.view(row,"id,step_id,result,original_approver_id,operator_id,decision_mode,agreed_at,recorded_at,comment");}
    /** 当前合法待办；先按人员与任职过滤，再分页和计数，空闲轮询不续期会话。 */
    public Map<String,Object> tasks(Actor actor,Map<String,String> query) {
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"page","pageSize","category","state","businessType","dutyCode");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        if(query.containsKey("category")) Inputs.choice(query.get("category"),"APPROVAL");if(query.containsKey("state")) Inputs.choice(query.get("state"),"PENDING");
        String where=" FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id WHERE s.assignee_id=? AND s.state='PENDING' AND w.state='PENDING' AND w.current_step_id=s.id AND workflow.assignment_valid(s.assignment_type,s.assignment_ref,s.assignee_id)";
        var args=new ArrayList<Object>(List.of(actor.userId()));
        where+=new ListConditions(query,args).choice("businessType","w.business_type","BORROW","REGISTRATION","ACCESS_POLICY","CATALOG_CHANGE","PHYSICAL_RETURN")
            .choice("dutyCode","s.duty_code","ARCHIVIST","DEPARTMENT_MANAGER","GENERAL_MANAGER","SUPERVISING_LEADER","ARCHIVE_ADMIN").sql();
        long total=db.count("SELECT count(*)"+where,args.toArray());
        args.add(size);args.add((page-1)*size);
        var rows=db.rows("SELECT s.id,s.duty_code,s.row_version,s.due_at,w.business_type,w.business_id,w.applicant_id,w.id AS instance_id"+where+" ORDER BY s.due_at,s.id LIMIT ? OFFSET ?",args.toArray());
        return Sql.map("items",rows.stream().map(row->{var item=Sql.view(row,"id,duty_code,row_version,due_at,business_type,business_id,applicant_id,instance_id");item.put("requiresReassignment",actor.userId().equals(row.get("applicant_id")));return item;}).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 节点正文只给原申请人或仍有原职责的处理人；审批不包含原始PDF地址。 */
    public Map<String,Object> step(Actor actor,UUID id) {
        actor.require("ARCHIVE.ACCESS");var step=db.required("SELECT * FROM workflow.step WHERE id=?",id);var instance=db.required("SELECT * FROM workflow.instance WHERE id=?",step.get("instance_id"));
        if(!actor.userId().equals(instance.get("applicant_id"))) duties.requireCurrent(step,actor.userId());
        // 借阅人取自原申请，部门取送审快照，不能把审批人或档案所属部门误当成借阅人。
        var applicant=db.required("SELECT u.id,u.name,u.employee_no,d.id AS department_id,d.name AS department_name,COALESCE(b.submitted_at,w.created_at) AS submitted_at FROM workflow.instance w JOIN iam.user_account u ON u.id=w.applicant_id JOIN workflow.route_snapshot r ON r.id=w.route_snapshot_id LEFT JOIN archive.borrow_request q ON w.business_type='BORROW' AND q.id=w.business_id LEFT JOIN archive.borrow_batch b ON b.id=q.batch_id LEFT JOIN iam.department d ON d.id=COALESCE(b.applicant_department_id,(r.department_context->>'applicantDepartmentId')::uuid,u.department_id) WHERE w.id=?",instance.get("id"));
        var ownDecision=db.one("SELECT d.result,d.comment,d.recorded_at,u.name AS operator_name FROM workflow.decision d JOIN iam.user_account u ON u.id=d.operator_id WHERE d.step_id=? AND d.operator_id=? AND d.decision_mode='ONLINE'",id,actor.userId());
        return Sql.map("step",Sql.view(step,"id,instance_id,sequence_no,duty_code,department_id,assignee_id,state,row_version,due_at"),"workflow",Sql.view(instance,"id,business_type,business_id,state,row_version,applicant_id"),"applicant",Sql.view(applicant,"id,name,employee_no,department_id,department_name,submitted_at"),"business",business((String)instance.get("business_type")).view(actor,instance),"ownDecision",ownDecision==null?null:Sql.view(ownDecision,"result,comment,recorded_at,operator_name"));
    }
    /** 管理人仅取得需要回避或职责失效的节点摘要，不通过异常队列泄露档案题名与正文。 */
    /** 管理员可处理指派配置，在线批准仍核对实际任职。 */
    private void requireWorkflowManagement(Actor actor) {
        if (actor.systemAdministrator()) actor.require("PLATFORM.DEPARTMENTS_MANAGE");
        else authority.requireSuper(actor,"ARCHIVE.CONFIG_MANAGE");
    }
    /** 系统管理员查看状态和当前职责，业务快照仅由原业务详情接口授权。 */
    public Map<String,Object> progress(Actor actor, Map<String,String> query) {
        actor.require("PLATFORM.DEPARTMENTS_MANAGE");
        Inputs.only(query,"page","pageSize","businessType","state","dutyCode","createdFrom","createdTo");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        var args=new ArrayList<Object>();
        String from=" FROM workflow.instance w LEFT JOIN workflow.step s ON s.id=w.current_step_id WHERE true"+new ListConditions(query,args)
            .choice("businessType","w.business_type","BORROW","REGISTRATION","ACCESS_POLICY","CATALOG_CHANGE","PHYSICAL_RETURN")
            .choice("state","w.state","PENDING","APPROVED","REJECTED","WITHDRAWN","CLOSED")
            .choice("dutyCode","s.duty_code","ARCHIVIST","DEPARTMENT_MANAGER","GENERAL_MANAGER","SUPERVISING_LEADER","ARCHIVE_ADMIN")
            .dates("created","w.created_at").sql();
        long total=db.count("SELECT count(*)"+from,args.toArray());
        args.add(size);args.add((page-1)*size);
        var rows=db.rows("SELECT w.id,w.business_type,w.state,w.created_at,s.duty_code"+from+" ORDER BY w.created_at DESC,w.id DESC LIMIT ? OFFSET ?",args.toArray());
        return Sql.map("items",rows.stream().map(row->Sql.view(row,"id,business_type,state,created_at,duty_code")).toList(),
                "total",total);
    }

    public Map<String,Object> attention(Actor actor) {requireWorkflowManagement(actor);return Sql.map("items",db.rows("SELECT s.id,s.duty_code,s.row_version,u.name AS assignee_name,a.name AS applicant_name,w.business_type FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id JOIN iam.user_account u ON u.id=s.assignee_id JOIN iam.user_account a ON a.id=w.applicant_id WHERE w.state='PENDING' AND w.current_step_id=s.id AND s.state='PENDING' AND (s.assignee_id=w.applicant_id OR NOT workflow.assignment_valid(s.assignment_type,s.assignment_ref,s.assignee_id)) ORDER BY s.id LIMIT 100").stream().map(row->Sql.view(row,"id,duty_code,row_version,assignee_name,applicant_name,business_type")).toList());}
    /** 候选必须是当前真实职责持有人；原任职失效但同一人重新任命时可交接到新任职。 */
    public Map<String,Object> assignmentOptions(Actor actor,UUID id) {
        requireWorkflowManagement(actor);var step=db.required("SELECT s.*,w.applicant_id FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id WHERE s.id=? AND s.state='PENDING' AND w.state='PENDING' AND w.current_step_id=s.id",id);List<Map<String,Object>> options;
        if("ARCHIVE_ADMIN".equals(step.get("duty_code"))) options=db.rows("SELECT DISTINCT u.id,u.name FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.module='ARCHIVE' AND a.role_code='SUPER_ADMIN' AND a.state='ACTIVE' AND r.status='ACTIVE' AND u.status='ACTIVE' AND iam.archive_access_enabled(u.id) AND NOT iam.it_shared_borrower(u.id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP) ORDER BY u.id");
        else {var legal=duties.resolve(new FixedBorrowRoute.Duty((String)step.get("duty_code"),(UUID)step.get("department_id")));options=db.rows("SELECT id,name FROM iam.user_account WHERE id=?",legal.user());}
        boolean originalLegal=db.count("SELECT CASE WHEN workflow.assignment_valid(?,?,?) THEN 1 ELSE 0 END",step.get("assignment_type"),step.get("assignment_ref"),step.get("assignee_id"))==1;
        return Sql.map("items",options.stream().filter(person->!person.get("id").equals(step.get("applicant_id"))&&(!person.get("id").equals(step.get("assignee_id"))||!originalLegal)).toList());
    }
    /** 尚未完成流程才允许本人撤回；保留节点与已有决定，不改变历史批准。 */
    public Map<String,Object> withdraw(Commands.Context context,UUID instanceId,Map<String,Object> body) {
        Inputs.only(body,"reason");Inputs.text(body,"reason",1000);
        return commands.run(context,body,"WORKFLOW_INSTANCE",instanceId,"WITHDRAW",false,()->{if(!context.actor().userId().equals(db.required("SELECT applicant_id FROM workflow.instance WHERE id=?",instanceId).get("applicant_id"))) throw ApiFault.hidden();},()->{
            var instance=db.required("SELECT * FROM workflow.instance WHERE id=? FOR UPDATE",instanceId);Commands.version(context,instance);if(!"PENDING".equals(instance.get("state"))) throw ApiFault.conflict("审批已结束，不能撤回");
            db.update("UPDATE workflow.instance SET state='WITHDRAWN',current_step_id=NULL,row_version=row_version+1 WHERE id=?",instanceId);business((String)instance.get("business_type")).finish(db.required("SELECT * FROM workflow.instance WHERE id=?",instanceId),false,context.actor().userId());return Sql.map("objectId",instanceId,"state","WITHDRAWN");
        },id->Sql.map("objectId",id,"state",db.required("SELECT state FROM workflow.instance WHERE id=?",id).get("state")));
    }
    /** 仅改派到当前合法职责持有人，不能用补录或管理员身份跳过本人回避。 */
    public Map<String,Object> reassign(Commands.Context context,UUID id,Map<String,Object> body) {
        Inputs.only(body,"assigneeId","reason","approvalReference");UUID target=Inputs.uuid(body.get("assigneeId"));Inputs.text(body,"reason",1000);Inputs.text(body,"approvalReference",256);
        return commands.run(context,body,"WORKFLOW_STEP",id,"REASSIGN_STEP",true,()->requireWorkflowManagement(context.actor()),()->{
            var old=db.required("SELECT * FROM workflow.step WHERE id=?",id);var instance=db.required("SELECT * FROM workflow.instance WHERE id=? FOR UPDATE",old.get("instance_id"));var step=db.required("SELECT * FROM workflow.step WHERE id=? FOR UPDATE",id);Commands.version(context,step);
            if(!"PENDING".equals(instance.get("state"))||!"PENDING".equals(step.get("state"))||!id.equals(instance.get("current_step_id"))||target.equals(instance.get("applicant_id"))) throw ApiFault.conflict("当前节点不允许此改派");
            WorkflowDuties.Assignment legal;
            if("ARCHIVE_ADMIN".equals(step.get("duty_code"))) {var role=db.required("SELECT a.id FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id WHERE a.user_id=? AND a.module='ARCHIVE' AND a.role_code='SUPER_ADMIN' AND a.state='ACTIVE' AND r.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP) ORDER BY a.id LIMIT 1 FOR SHARE OF a",target);legal=new WorkflowDuties.Assignment(target,"ROLE",(UUID)role.get("id"));}
            else legal=duties.resolve(new FixedBorrowRoute.Duty((String)step.get("duty_code"),(UUID)step.get("department_id")));
            if(!target.equals(legal.user())||(target.equals(step.get("assignee_id"))&&legal.reference().equals(step.get("assignment_ref")))) throw ApiFault.denied();
            // 角色任职记录存在还不够，目标账号的模块入口不能已停用或被显式拒绝。
            if(db.count("SELECT CASE WHEN workflow.assignment_valid(?,?,?) THEN 1 ELSE 0 END",legal.type(),legal.reference(),target)!=1) throw ApiFault.denied();
            db.update("INSERT INTO workflow.assignment_event(id,step_id,from_user_id,to_user_id,operator_id,reason,approval_reference) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),id,step.get("assignee_id"),target,context.actor().userId(),body.get("reason"),body.get("approvalReference"));
            db.update("UPDATE workflow.step SET assignee_id=?,assignment_type=?,assignment_ref=?,row_version=row_version+1 WHERE id=?",target,legal.type(),legal.reference(),id);db.update("UPDATE workflow.instance SET row_version=row_version+1 WHERE id=?",instance.get("id"));return Sql.map("objectId",id,"state","SUCCEEDED");
        },key->Sql.map("objectId",key,"state","SUCCEEDED"));
    }
    /** 只有唯一业务适配器才能提交结果，配置冲突按保护失败处理。 */
    private WorkflowBusiness business(String type) {var matches=businesses.orderedStream().filter(value->value.type().equals(type)).toList();if(matches.size()!=1) throw SystemFailure.WORKFLOW_CONFIGURATION_INVALID.fault();return matches.getFirst();}
    /** 解析数据库JSON快照，避免调用者接受客户端重新指定的清单。 */
    @SuppressWarnings("unchecked") public static Map<String,Object> snapshot(Map<String,Object> instance) {Object value=instance.get("business_snapshot");return value instanceof Map<?,?> map?(Map<String,Object>)map:Crypto.JSON.readValue(value.toString(),Map.class);}
}
