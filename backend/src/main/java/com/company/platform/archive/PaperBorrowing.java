package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.workflow.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 纸质审批适配器：沿用固定BORROW流程，批准只产生待交付，不产生电子正文授权。 */
@Service
public class PaperBorrowing {
    /** 目录、实物、固定工作日历和真实职责均由服务端读取。 */
    private final PhysicalTime time;private final Sql db;private final ArchiveAuthority authority;private final PhysicalItems items;private final ArchiveCommands commands;private final WorkCalendarService calendar;private final WorkflowEngine workflow;private final WorkflowDuties duties;
    public PaperBorrowing(PhysicalTime time,Sql db,ArchiveAuthority authority,PhysicalItems items,ArchiveCommands commands,WorkCalendarService calendar,WorkflowEngine workflow,WorkflowDuties duties){this.time=time;this.db=db;this.authority=authority;this.items=items;this.commands=commands;this.calendar=calendar;this.workflow=workflow;this.duties=duties;}
    /** 单一纸质动作；客户端选择实际原件，不能把电子查看授权变成实物批准。 */
    public Map<String,Object> submit(Commands.Context context,Map<String,Object> body){
        Inputs.only(body,"form","actions","purpose","items","period");if(!"PAPER".equals(body.get("form"))||!List.of("BORROW_PHYSICAL").equals(body.get("actions")))throw ApiFault.invalid("纸质形式只接受BORROW_PHYSICAL独立动作");Inputs.text(body,"purpose",2000);var period=calendar.period(body.get("period"));var selections=Inputs.objects(body.get("items"),50);if(selections.isEmpty())throw ApiFault.invalid("请选择实际纸质原件");
        return commands.run(context,body,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"SUBMIT_PAPER_BORROW",false,()->context.actor().require("ARCHIVE.PHYSICAL_BORROW"),()->{
            LinkedHashSet<UUID> physicalIds=new LinkedHashSet<>();for(var selection:selections){Inputs.only(selection,"physicalItemId");if(!physicalIds.add(Inputs.uuid(selection.get("physicalItemId"))))throw ApiFault.invalid("原件不能重复选择");}
            var selected=items.lock(physicalIds);Set<UUID> records=new HashSet<>();List<Map<String,Object>> plans=new ArrayList<>();String highest="GENERAL";
            for(var physical:selected){var members=members(physical);if(members.isEmpty())throw ApiFault.invalid("空盒不能申请整盒借阅");for(var member:members){UUID record=Inputs.uuid(member.get("recordId"));if(!records.add(record)||records.size()>50)throw ApiFault.invalid("整批最多50份不同文件，整盒与分件不能重复选择");var detail=authority.detail(context.actor(),record);var row=authority.record(record);member.put("label","TITLE_ONLY".equals(detail.get("representation"))?Objects.toString(row.get("safe_title"),Objects.toString(row.get("file_no"))):row.get("title"));if(rank(member.get("classification"))>rank(highest))highest=member.get("classification").toString();}
                items.assertFree(physical);
                plans.add(Sql.map("physical",physical,"members",members,"label",physical.get("object_type").equals("BOX")?"整盒原件":members.getFirst().get("label")));
            }
            return create(context.actor(),body.get("purpose").toString(),period,highest,plans,null,null);
        },id->batch(context.actor(),id));
    }
    /** 纸质续借按实际借出记录固定原件、原最高等级及原截止；待审不改原期限或占用。 */
    public Map<String,Object> renew(Commands.Context context,UUID loanId,Map<String,Object> body){
        Inputs.only(body,"purpose","reason","period");Inputs.text(body,"purpose",2000);Inputs.text(body,"reason",1000);var period=calendar.period(body.get("period"));
        return commands.run(context,body,"PHYSICAL_LOAN",loanId,"RENEW_PHYSICAL",false,()->{context.actor().require("ARCHIVE.PHYSICAL_BORROW");if(!context.actor().userId().equals(db.required("SELECT borrower_id FROM archive.physical_loan WHERE id=?",loanId).get("borrower_id")))throw ApiFault.hidden();},()->{
            var pre=db.required("SELECT * FROM archive.physical_loan WHERE id=?",loanId);var physical=items.lock(List.of((UUID)pre.get("physical_item_id"))).getFirst();var loan=db.required("SELECT * FROM archive.physical_loan WHERE id=? FOR UPDATE",loanId);if(!"IN_USE".equals(loan.get("state")))throw ApiFault.conflict("已归还原件不能续借");if(db.count("SELECT count(*) FROM archive.physical_return WHERE loan_id=? AND state='PENDING'",loanId)>0)throw ApiFault.conflict("请先完成当前归还复核");
            var request=approved((UUID)loan.get("current_request_id"));if(db.count("SELECT count(*) FROM archive.paper_loan_scope s JOIN archive.borrow_request q ON q.id=s.request_id WHERE s.loan_id=? AND q.state='PENDING'",loanId)>0)throw ApiFault.conflict("该原件已有待审续借");
            var plan=db.required("SELECT * FROM archive.paper_request_item WHERE request_id=? AND physical_item_id=?",loan.get("current_request_id"),physical.get("id"));verifyFrozen((UUID)loan.get("current_request_id"));
            var details=objects(plan.get("members"));for(var member:details){member.put("renewedLoanId",loanId.toString());member.put("previousUntil",loan.get("due_at"));member.put("originalDeliveryAt",loan.get("actual_delivery_at"));}
            var result=create(context.actor(),body.get("purpose").toString(),period,request.get("parent_max_classification").toString(),List.of(Sql.map("physical",physical,"members",details,"label",plan.get("label"))),loanId,body.get("reason").toString());return result;
        },id->batch(context.actor(),id));
    }
    /** 固定父清单后按所属部门拆单；续借保留原必要档案员节点，不能因身份变化降级。 */
    private Map<String,Object> create(Actor actor,String purpose,Map<String,Object> period,String highest,List<Map<String,Object>> plans,UUID renewalLoan,String reason){
        var renewedLoan=renewalLoan==null?null:db.required("SELECT * FROM archive.physical_loan WHERE id=?",renewalLoan);
        boolean currentFlow=renewedLoan==null||renewedLoan.get("reserved_at")!=null;
        int version=calendar.current();UUID department=Inputs.uuid(actor.user().get("departmentId")),batch=UUID.randomUUID();
        if(currentFlow)calendar.paperUntil(version,period,time.now());
        else calendar.until(version,period,time.now());
        int count=plans.stream().mapToInt(plan->castObjects(plan.get("members")).size()).sum();
        db.update("INSERT INTO archive.borrow_batch(id,applicant_id,applicant_department_id,form,actions,purpose,parent_max_classification,item_count,calendar_version,requested_period,renewal_reason) VALUES(?,?,?,'PAPER','{BORROW_PHYSICAL}',?,?,?,?,?::jsonb,?)",batch,actor.userId(),department,purpose,highest,count,version,Crypto.json(period),reason);
        Map<UUID,List<Map<String,Object>>> groups=new TreeMap<>();for(var plan:plans)groups.computeIfAbsent((UUID)map(plan.get("physical")).get("department_id"),key->new ArrayList<>()).add(plan);
        for(var group:groups.entrySet()){
            UUID request=UUID.randomUUID();var members=group.getValue().stream().flatMap(plan->castObjects(plan.get("members")).stream()).toList();List<Map<String,Object>> rows=new ArrayList<>();for(var member:members){var copy=new LinkedHashMap<>(member);copy.put("action","BORROW_PHYSICAL");rows.add(copy);}
            var snapshot=Sql.map("form","PAPER","batchId",batch.toString(),"requestId",request.toString(),"departmentId",group.getKey().toString(),"applicantDepartmentId",department.toString(),"highestClassification",highest,"items",rows,"period",period,"calendarVersion",version,"renewalReason",reason);
            List<FixedBorrowRoute.Duty> route;
            if(currentFlow){
                snapshot.put("paperHandoffs",true);
                route=FixedBorrowRoute.managerFirst(department,group.getKey(),"CORE".equals(highest));
                if(renewedLoan!=null)renewUntil(version,period,renewedLoan);
            }else{
                route=new ArrayList<>(FixedBorrowRoute.plan(highest,department,group.getKey(),false));
                Instant proposed=renewUntil(version,period,renewedLoan);
                boolean leader=overWeek(Instant.parse(renewedLoan.get("actual_delivery_at").toString()),proposed)
                    ||db.count("SELECT count(*) FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.id=? AND s.duty_code='SUPERVISING_LEADER'",renewedLoan.get("current_request_id"))>0;
                if(leader)route.add(route.size()-1,new FixedBorrowRoute.Duty("SUPERVISING_LEADER",group.getKey()));
            }
            UUID flow=workflow.create(actor,"BORROW",request,batch,highest,version,snapshot,route);UUID routeId=(UUID)db.required("SELECT route_snapshot_id FROM workflow.instance WHERE id=?",flow).get("route_snapshot_id");
            db.update("INSERT INTO archive.borrow_request(id,batch_id,owner_department_id,applicant_id,workflow_instance_id,route_snapshot_id,approved_actions) VALUES(?,?,?,?,?,?,'{BORROW_PHYSICAL}')",request,batch,group.getKey(),actor.userId(),flow,routeId);
            for(var plan:group.getValue()){var physical=map(plan.get("physical"));db.update("INSERT INTO archive.paper_request_item(id,request_id,physical_item_id,box_id,box_revision_id,members,label) VALUES(?,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),request,physical.get("id"),physical.get("box_id"),physical.get("box_revision_id"),Crypto.json(plan.get("members")),plan.get("label"));}
            if(renewalLoan!=null)db.update("INSERT INTO archive.paper_loan_scope(request_id,loan_id) VALUES(?,?)",request,renewalLoan);
        }return batch(actor,batch);
    }
    /** 审批和交付重验原申请人部门、当前目录修订及冻结的实物成员，不接受状态字段伪造批准。 */
    public void verify(Map<String,Object> instance){
        var snapshot=WorkflowEngine.snapshot(instance);var q=db.required("SELECT q.*,b.applicant_department_id FROM archive.borrow_request q JOIN archive.borrow_batch b ON b.id=q.batch_id WHERE q.id=?",instance.get("business_id"));if(!"PENDING".equals(q.get("state")))throw ApiFault.conflict("纸质审批已结束");db.required("SELECT id FROM iam.user_account WHERE id=? AND department_id=? AND status='ACTIVE'",q.get("applicant_id"),q.get("applicant_department_id"));requireApplicant((UUID)q.get("applicant_id"));verifyFrozen((UUID)q.get("id"));
        for(var item:castObjects(snapshot.get("items")))if(item.get("renewedLoanId")!=null){var loan=db.required("SELECT * FROM archive.physical_loan WHERE id=?",Inputs.uuid(item.get("renewedLoanId")));if(!"IN_USE".equals(loan.get("state"))||!Objects.equals(loan.get("due_at"),item.get("previousUntil")))throw ApiFault.conflict("原借出已归还或期限已变化，请重新核对");approved((UUID)loan.get("current_request_id"));}
    }
    /** 新申请末级批准即占用，续借继续使用同一件原件；旧审批保留实际交付后起算。 */
    public void finish(Map<String,Object> instance,boolean approved,UUID approver){
        var snapshot=WorkflowEngine.snapshot(instance);UUID request=Inputs.uuid(snapshot.get("requestId"));if(approved){verify(instance);Instant latest=null;
            boolean currentFlow=Boolean.TRUE.equals(snapshot.get("paperHandoffs"));
            boolean requiresLeader = !currentFlow&&!Boolean.TRUE.equals(snapshot.get("itSharedBorrow"));
            // 审批耗时也可能使累计期限跨过七日；先补入同一流程，所有原截止保持不变。
            var actualLoans=db.rows("SELECT l.* FROM archive.paper_loan_scope s JOIN archive.physical_loan l ON l.id=s.loan_id WHERE s.request_id=? ORDER BY l.id FOR UPDATE OF l",request);
            if (requiresLeader) {
                for(var loan:actualLoans)if(overWeek(Instant.parse(loan.get("actual_delivery_at").toString()),renewUntil(((Number)snapshot.get("calendarVersion")).intValue(),map(snapshot.get("period")),loan))&&!leaderApproved((UUID)instance.get("id"))){supplement(db.required("SELECT * FROM archive.borrow_request WHERE id=?",request));return;}
            }
            for(var loan:actualLoans){
                int version=((Number)snapshot.get("calendarVersion")).intValue();Instant until=renewUntil(version,map(snapshot.get("period")),loan);
                if(requiresLeader&&overWeek(Instant.parse(loan.get("actual_delivery_at").toString()),until)&&!leaderApproved((UUID)instance.get("id")))throw ApiFault.conflict("累计借出超七自然日，必须先完成分管领导批准");
                db.update("UPDATE archive.physical_loan SET current_request_id=?,due_at=?,row_version=row_version+1 WHERE id=?",request,java.sql.Timestamp.from(until),loan.get("id"));db.update("UPDATE archive.physical_notice SET state='CANCELLED' WHERE loan_id=? AND state<>'CANCELLED'",loan.get("id"));items.event((UUID)loan.get("physical_item_id"),(UUID)loan.get("id"),approver,"RENEWAL_APPROVED",Sql.map("requestId",request,"previousUntil",loan.get("due_at"),"until",until.toString()));latest=until;
            }
            if(currentFlow&&actualLoans.isEmpty()){
                Instant effective=time.now();
                latest=calendar.paperUntil(((Number)snapshot.get("calendarVersion")).intValue(),map(snapshot.get("period")),effective);
                var plans=db.rows("SELECT physical_item_id FROM archive.paper_request_item WHERE request_id=? ORDER BY physical_item_id",request);
                for(var physical:items.lock(plans.stream().map(plan->(UUID)plan.get("physical_item_id")).toList())){
                    items.assertFree(physical);
                    UUID loan=UUID.randomUUID();
                    db.update("""
                        INSERT INTO archive.physical_loan(id,request_id,current_request_id,physical_item_id,box_id,
                          borrower_id,reserved_at,due_at,actual_quantity,condition)
                        VALUES(?,?,?,?,?,?,?,?,1,'')
                        """,loan,request,request,physical.get("id"),physical.get("box_id"),instance.get("applicant_id"),
                        java.sql.Timestamp.from(effective),java.sql.Timestamp.from(latest));
                    db.update("INSERT INTO archive.paper_loan_scope(request_id,loan_id) VALUES(?,?)",request,loan);
                    items.event((UUID)physical.get("id"),loan,approver,"RESERVED",
                        Sql.map("requestId",request,"dueAt",latest.toString()));
                }
            }
            db.update("UPDATE archive.borrow_request SET state='APPROVED',approved_until=? WHERE id=?",latest==null?null:java.sql.Timestamp.from(latest),request);
        }else db.update("UPDATE archive.borrow_request SET state=? WHERE id=?","WITHDRAWN".equals(instance.get("state"))?"WITHDRAWN":"REJECTED",request);
        refresh(Inputs.uuid(snapshot.get("batchId")));commands.event("BORROW_REQUEST",request,approved?"PAPER_APPROVED":"PAPER_ENDED");
    }
    /** 具体业务的有效批准，纸质动作不能混入电子授权动作。 */
    public Map<String,Object> approved(UUID request){
        var q=db.required("SELECT q.*,b.applicant_department_id,b.parent_max_classification,b.calendar_version,b.requested_period,COALESCE(w.business_snapshot->>'itSharedBorrow'='true',false) AS it_shared_borrow FROM archive.borrow_request q JOIN archive.borrow_batch b ON b.id=q.batch_id JOIN workflow.instance w ON w.id=q.workflow_instance_id JOIN iam.user_account u ON u.id=q.applicant_id WHERE q.id=? AND b.form='PAPER' AND q.state='APPROVED' AND q.approved_actions=ARRAY['BORROW_PHYSICAL']::varchar[] AND b.actions=q.approved_actions AND u.status='ACTIVE' AND u.department_id=b.applicant_department_id AND w.applicant_id=q.applicant_id AND b.applicant_id=q.applicant_id AND q.route_snapshot_id=w.route_snapshot_id AND w.business_snapshot->>'requestId'=q.id::text AND w.business_snapshot->>'highestClassification'=b.parent_max_classification AND (w.business_snapshot->>'calendarVersion')::integer=b.calendar_version AND w.business_snapshot->'period'=b.requested_period AND workflow.approval_valid(w.id,'BORROW',q.id)",request);requireApplicant((UUID)q.get("applicant_id"));verifyFrozen(request);return q;
    }
    /** 先持盒锁再校验冻结清单；目录或成员变化不沿旧批准交付。 */
    public void verifyFrozen(UUID request){for(var frozen:db.rows("SELECT s.*,p.object_type,p.record_id FROM archive.paper_request_item s JOIN archive.physical_item p ON p.id=s.physical_item_id WHERE request_id=? ORDER BY s.physical_item_id",request)){
        var box=db.required("SELECT * FROM archive.box WHERE id=? AND state='PUBLISHED'",frozen.get("box_id"));if(!Objects.equals(box.get("current_revision_id"),frozen.get("box_revision_id")))throw ApiFault.conflict("原件所属盒修订已变化");
        var current=members(Sql.map("id",frozen.get("physical_item_id"),"object_type",frozen.get("object_type"),"record_id",frozen.get("record_id"),"box_id",frozen.get("box_id"),"box_state","PUBLISHED"));
        var old=objects(frozen.get("members"));if(!Crypto.json(compact(current)).equals(Crypto.json(compact(old))))throw ApiFault.conflict("原批准实物成员、目录修订或版本已变化");
    }}
    /** 实际交付时才触发的补批保持原流程和决定，追加关联路线并激活真实领导待办。 */
    public UUID supplement(Map<String,Object> request){
        UUID flow=(UUID)request.get("workflow_instance_id");var instance=db.required("SELECT * FROM workflow.instance WHERE id=? FOR UPDATE",flow);if("PENDING".equals(instance.get("state")))return (UUID)instance.get("current_step_id");
        var old=db.required("SELECT * FROM workflow.route_snapshot WHERE id=?",instance.get("route_snapshot_id"));var leader=duties.resolve(new FixedBorrowRoute.Duty("SUPERVISING_LEADER",(UUID)request.get("owner_department_id")));var nodes=objects(old.get("nodes_snapshot"));UUID step=UUID.randomUUID(),route=UUID.randomUUID();int sequence=nodes.size()+1;nodes.add(Sql.map("id",step,"sequence",sequence,"duty","SUPERVISING_LEADER","departmentId",request.get("owner_department_id"),"userId",leader.user(),"assignmentType",leader.type(),"assignmentRef",leader.reference()));
        db.update("INSERT INTO workflow.route_snapshot(id,instance_id,batch_id,highest_classification,form,applicant_role,department_context,over_week,calendar_version,template_version,nodes_snapshot,parent_snapshot_id,supplement_reason) VALUES(?,?,?,?,?,?,?::jsonb,true,?,?,?::jsonb,?,'实际期限重算累计借出超过七自然日')",route,flow,request.get("batch_id"),old.get("highest_classification"),"PAPER",old.get("applicant_role"),old.get("department_context").toString(),old.get("calendar_version"),old.get("template_version"),Crypto.json(nodes),old.get("id"));
        db.update("INSERT INTO workflow.step(id,instance_id,sequence_no,duty_code,department_id,assignee_id,assignment_type,assignment_ref,state,due_at) VALUES(?,?,?,'SUPERVISING_LEADER',?,?,?,?,'PENDING',CURRENT_TIMESTAMP+interval '24 hours')",step,flow,sequence,request.get("owner_department_id"),leader.user(),leader.type(),leader.reference());
        db.update("UPDATE workflow.instance SET route_snapshot_id=?,state='PENDING',current_step_id=?,row_version=row_version+1 WHERE id=?",route,step,flow);db.update("UPDATE archive.borrow_request SET route_snapshot_id=?,state='PENDING' WHERE id=?",route,request.get("id"));refresh((UUID)request.get("batch_id"));commands.event("BORROW_REQUEST",(UUID)request.get("id"),"PAPER_LEADER_REQUIRED");return step;
    }
    /** 领导批准必须来自当前不可变路线中的真实非本人在线决定。 */
    public boolean leaderApproved(UUID flow){return db.count("SELECT count(*) FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id JOIN workflow.decision d ON d.step_id=s.id WHERE w.id=? AND s.duty_code='SUPERVISING_LEADER' AND s.state='APPROVED' AND d.result='APPROVE' AND d.operator_id=d.original_approver_id AND d.operator_id<>w.applicant_id",flow)>0;}
    /** 本人或合法节点处理人可看相应部门清单；不会通过父批次泄露其他部门子单。 */
    public Map<String,Object> batch(Actor actor,UUID batch){
        actor.require("ARCHIVE.ACCESS");var parent=db.required("SELECT * FROM archive.borrow_batch WHERE id=? AND form='PAPER'",batch);var qs=db.rows("SELECT q.*,w.current_step_id,w.row_version,w.business_snapshot,EXISTS(SELECT 1 FROM archive.paper_loan_scope scope JOIN archive.physical_loan l ON l.id=scope.loan_id WHERE scope.request_id=q.id AND l.state='IN_USE' AND l.actual_delivery_at IS NOT NULL) AS physically_used FROM archive.borrow_request q JOIN workflow.instance w ON w.id=q.workflow_instance_id WHERE q.batch_id=? AND (q.applicant_id=? OR EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id AND s.assignee_id=? AND workflow.assignment_valid(s.assignment_type,s.assignment_ref,s.assignee_id))) ORDER BY q.id",batch,actor.userId(),actor.userId());if(qs.isEmpty())throw ApiFault.hidden();List<Map<String,Object>> frozen=new ArrayList<>();for(var q:qs)for(var item:castObjects(WorkflowEngine.snapshot(q).get("items"))){var row=new LinkedHashMap<>(item);row.put("requestId",q.get("id"));frozen.add(row);}parent.put("requested_period",mapJson(parent.get("requested_period")));
        var batchView=Sql.view(parent,"id,applicant_id,form,purpose,state,parent_max_classification,item_count,calendar_version,requested_period,submitted_at,renewal_reason");
        batchView.put("paperHandoffs",Boolean.TRUE.equals(WorkflowEngine.snapshot(qs.getFirst()).get("paperHandoffs")));
        return Sql.map("id",batch,"batch",batchView,"canRenew",false,"renewals",List.of(),"items",frozen,"requests",qs.stream().map(q->{var value=Sql.view(q,"id,owner_department_id,workflow_instance_id,state,approved_until,row_version,current_step_id");value.put("usageState","APPROVED".equals(q.get("state"))?(Boolean.TRUE.equals(q.get("physically_used"))?"PHYSICAL_IN_USE":"WAITING_DELIVERY"):q.get("state"));return value;}).toList());
    }
    /** 子单与父批次状态按实际办理结果聚合，拒绝/撤回不会释放任何已借实物。 */
    public void refresh(UUID batch){db.required("SELECT id FROM archive.borrow_batch WHERE id=? FOR UPDATE",batch);db.update("UPDATE archive.borrow_batch SET row_version=row_version+1,state=(SELECT CASE WHEN bool_and(state='CLOSED') THEN 'CLOSED' WHEN bool_and(state='APPROVED') THEN 'APPROVED' WHEN bool_and(state='PENDING') THEN 'PENDING' WHEN bool_and(state='REJECTED') THEN 'REJECTED' WHEN bool_and(state='WITHDRAWN') THEN 'WITHDRAWN' ELSE 'PARTIAL' END FROM archive.borrow_request WHERE batch_id=?) WHERE id=?",batch,batch);}
    /** 根据实际交付日至截止的上海日期判断七自然日阈值，不使用固定秒数模拟自然日。 */
    public boolean overWeek(Instant start,Instant until){return ChronoUnit.DAYS.between(start.atZone(WorkCalendarService.ZONE).toLocalDate(),until.atZone(WorkCalendarService.ZONE).toLocalDate())>7;}
    /** 返回固定公司日历和期间下的实际应还时间。 */
    public Instant until(Map<String,Object> request,Instant actual){return calendar.until(((Number)request.get("calendar_version")).intValue(),mapJson(request.get("requested_period")),actual);}
    /** 从原截止或批准时较晚者起算续借，必须真正延长；原实际交付日始终保留。 */
    private Instant renewUntil(int version,Map<String,Object> period,Map<String,Object> loan){
        Instant previous=Instant.parse(loan.get("due_at").toString()),now=time.now(),start=previous.isAfter(now)?previous:now;
        Instant until=loan.get("reserved_at")!=null?calendar.paperUntil(version,period,start):calendar.until(version,period,start);
        if(!until.isAfter(previous))throw ApiFault.invalid("续借必须延长原应还日期");
        return until;
    }
    /** 当前成员按稳定ID排序；纯纸质不要求PDF，混合/电子成员仍绑定其当前完整PDF。 */
    private List<Map<String,Object>> members(Map<String,Object> item){
        if(!"PUBLISHED".equals(item.get("box_state")))throw ApiFault.conflict("所属盒尚未归档");String filter="BOX".equals(item.get("object_type"))?"r.box_id=?":"r.id=?";Object target="BOX".equals(item.get("object_type"))?item.get("box_id"):item.get("record_id");
        var rows=db.rows("SELECT r.id,r.current_revision_id,r.current_pdf_version_id,r.classification,r.state FROM archive.record r WHERE "+filter+" ORDER BY r.id LIMIT 51",target);List<Map<String,Object>> result=new ArrayList<>();for(var row:rows){if(!"PUBLISHED".equals(row.get("state"))||row.get("current_revision_id")==null)throw ApiFault.conflict("整盒内存在未发布成员");result.add(Sql.map("recordId",row.get("id").toString(),"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",Objects.toString(row.get("current_pdf_version_id"),null),"classification",row.get("classification")));}if(result.size()>50)throw ApiFault.invalid("整盒展开超过50份文件，不能绕过上限交付");return result;
    }
    /** 比较只包含授权关键字段，显示题名和续借说明不参与版本匹配。 */
    private List<Map<String,Object>> compact(List<Map<String,Object>> values){return values.stream().map(v->Sql.map("recordId",v.get("recordId"),"recordRevisionId",v.get("recordRevisionId"),"pdfVersionId",v.get("pdfVersionId"),"classification",v.get("classification"))).toList();}
    private int rank(Object value){return switch(Objects.toString(value)){case "GENERAL"->0;case "IMPORTANT"->1;case "CORE"->2;default->throw ApiFault.protection();};}
    /** 交付和扩期使用统一的实时权限判断，识别有效 IT 任职和普通档案授权。 */
    private void requireApplicant(UUID person){
        long allowed=db.count("SELECT CASE WHEN iam.archive_access_enabled(?) AND iam.action_enabled(?,'ARCHIVE.PHYSICAL_BORROW') THEN 1 ELSE 0 END",person,person);
        if(allowed!=1)throw ApiFault.conflict("申请人当前纸质借阅资格已失效，请先核对任职与权限");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> castObjects(Object value){return (List<Map<String,Object>>)value;}
    private Map<String,Object> mapJson(Object value){return value instanceof Map<?,?>?map(value):Crypto.JSON.readValue(value.toString(),Map.class);}
    /** JSONB解析后复制可追加的列表，保持数据库原快照不可改写。 */
    private List<Map<String,Object>> objects(Object value){return new ArrayList<>(value instanceof List<?>?castObjects(value):Crypto.JSON.readValue(value.toString(),List.class));}
}
