package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.*;
import com.company.platform.workflow.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 实物借还命令：实际交付建立唯一占用，归还申请和逾期均不释放，实际复核才结束。 */
@Service
public class PhysicalLoans implements WorkflowBusiness {
    /** 实时身份、原批准、盒锁及库外回执共同约束交付，不接收前端状态结论。 */
    private final PhysicalTime time;private final Sql db;private final IdentityService identity;private final ArchiveAuthority authority;private final PhysicalItems items;private final PaperBorrowing paper;private final ArchiveCommands commands;private final StorageBoundaries gate;private final DeliveryReceipts receipts;private final Audit audit;private final TransactionTemplate tx;private final WorkflowEngine workflow;private final boolean enabled;
    public PhysicalLoans(PhysicalTime time,Sql db,IdentityService identity,ArchiveAuthority authority,PhysicalItems items,PaperBorrowing paper,ArchiveCommands commands,StorageBoundaries gate,DeliveryReceipts receipts,Audit audit,PlatformTransactionManager manager,WorkflowEngine workflow,@Value("${platform.borrow-lifecycle.enabled:false}") boolean enabled){this.time=time;this.db=db;this.identity=identity;this.authority=authority;this.items=items;this.paper=paper;this.commands=commands;this.gate=gate;this.receipts=receipts;this.audit=audit;this.tx=new TransactionTemplate(manager);this.workflow=workflow;this.enabled=enabled;}
    /** 清单固定且最多50原件；待补批回执允许原操作号继续，交付完成后载荷不可再改。 */
    @Transactional public Map<String,Object> deliver(Commands.Context context,UUID request,Map<String,Object> body){
        Inputs.only(body,"physicalItemIds","actualDeliveryAt","actualQuantity","condition");var ids=ids(body.get("physicalItemIds"));int quantity=Inputs.integer(body.get("actualQuantity"),0,1,50);if(quantity!=ids.size())throw ApiFault.invalid("实际交接数量必须与本次盒/件原件数一致");Inputs.text(body,"condition",2000);Instant actual=Instant.parse(Inputs.text(body,"actualDeliveryAt",64));if(actual.isAfter(time.now()))throw ApiFault.invalid("实际交付时间不能晚于服务端当前时间");
        var pre=db.required("SELECT q.*,b.form FROM archive.borrow_request q JOIN archive.borrow_batch b ON b.id=q.batch_id WHERE q.id=?",request);if(!"PAPER".equals(pre.get("form")))throw ApiFault.invalid("电子批准不能用于纸质交付");lockPeople(context.actor(),(UUID)pre.get("applicant_id"));identity.lockActor(context.actor());var fresh=identity.background(context.actor().sessionId());fresh.require("ARCHIVE.ACCESS");fresh.require("ARCHIVE.PHYSICAL_MANAGE");gate.requireWritable("ARCHIVE","PHYSICAL_DELIVERY");authority.requireManage(fresh,(UUID)pre.get("owner_department_id"));if(context.actor().userId().equals(pre.get("applicant_id")))throw ApiFault.denied();
        var physical=items.lock(ids);for(var item:physical)if(!item.get("department_id").equals(pre.get("owner_department_id")))throw ApiFault.hidden();
        if(Boolean.TRUE.equals(db.required("""
            SELECT business_snapshot->>'paperHandoffs'='true' AS current_flow
            FROM workflow.instance WHERE id=?
            """,pre.get("workflow_instance_id")).get("current_flow")))
            return confirmPickup(context,fresh,pre,ids,actual.truncatedTo(java.time.temporal.ChronoUnit.MICROS),body);
        var scope=new LinkedHashMap<>(body);scope.remove("actualDeliveryAt");String scopeDigest=Crypto.hash(Crypto.json(scope)),digest=Crypto.hash(Crypto.json(body));
        var prior=db.one("SELECT * FROM archive.physical_delivery WHERE actor_id=? AND operation_id=? FOR UPDATE",context.actor().userId(),context.operation());
        if(prior!=null){if(!request.equals(prior.get("request_id"))||!Crypto.equal(scopeDigest,prior.get("scope_digest").toString())||("DELIVERED".equals(prior.get("state"))&&!Crypto.equal(digest,prior.get("final_digest").toString())))throw ApiFault.conflict("同一交付操作号不能更换清单、数量或已完成时间");if("DELIVERED".equals(prior.get("state")))return delivery((UUID)prior.get("id"));}
        else identity.consume(context.actor(),context.reauth(),"BORROW_REQUEST",request,"DELIVER_PHYSICAL");
        var q=db.required("SELECT * FROM archive.borrow_request WHERE id=? FOR UPDATE",request);for(UUID id:ids)db.required("SELECT id FROM archive.paper_request_item WHERE request_id=? AND physical_item_id=?",request,id);
        UUID command=prior==null?UUID.randomUUID():(UUID)prior.get("id");if(prior==null)db.update("INSERT INTO archive.physical_delivery(id,request_id,actor_id,operation_id,scope_digest,body_snapshot,state) VALUES(?,?,?,?,?,?::jsonb,'WAITING_APPROVAL')",command,request,context.actor().userId(),context.operation(),scopeDigest,Crypto.json(body));
        if("PENDING".equals(q.get("state"))){var waiting=db.required("SELECT s.* FROM workflow.instance w JOIN workflow.step s ON s.id=w.current_step_id WHERE w.id=? AND w.state='PENDING' AND s.duty_code='SUPERVISING_LEADER'",q.get("workflow_instance_id"));return waiting(command,waiting.get("id"));}
        q=paper.approved(request);var decision=db.required("SELECT max(d.agreed_at) AS approved_at FROM workflow.decision d JOIN workflow.step s ON s.id=d.step_id WHERE s.instance_id=?",q.get("workflow_instance_id"));
        if(actual.isBefore(Instant.parse(decision.get("approved_at").toString())))throw ApiFault.invalid("实际交付不能早于全部必要批准完成；待补批后请按实际交付时刻重试");
        Instant until=paper.until(q,actual);if(!Boolean.TRUE.equals(q.get("it_shared_borrow"))&&paper.overWeek(actual,until)&&!paper.leaderApproved((UUID)q.get("workflow_instance_id"))){UUID step=paper.supplement(q);audit.event(context.actor().userId(),"ARCHIVE","PHYSICAL_DELIVERY",command,"WAITING_LEADER_APPROVAL","SUCCEEDED",context.request());return waiting(command,step);}
        for(var item:physical){items.assertFree(item);if(db.count("SELECT count(*) FROM archive.paper_loan_scope s JOIN archive.physical_loan l ON l.id=s.loan_id WHERE s.request_id=? AND l.physical_item_id=?",request,item.get("id"))>0)throw ApiFault.conflict("同一批准原件已交付，不能重复借出");
            UUID loan=UUID.randomUUID(),receipt=receipts.custody(context.actor(),(UUID)item.get("id"),loan,"PHYSICAL_DELIVER",context.request());db.update("INSERT INTO archive.physical_loan(id,request_id,current_request_id,physical_item_id,box_id,borrower_id,delivered_by,delivery_receipt_id,actual_delivery_at,due_at,actual_quantity,condition) VALUES(?,?,?,?,?,?,?,?,?,?,1,?)",loan,request,request,item.get("id"),item.get("box_id"),q.get("applicant_id"),context.actor().userId(),receipt,java.sql.Timestamp.from(actual),java.sql.Timestamp.from(until),body.get("condition"));db.update("INSERT INTO archive.paper_loan_scope(request_id,loan_id) VALUES(?,?)",request,loan);db.update("INSERT INTO archive.physical_delivery_loan(delivery_id,loan_id) VALUES(?,?)",command,loan);items.event((UUID)item.get("id"),loan,context.actor(),"DELIVERED",Sql.map("requestId",request,"actualDeliveryAt",actual.toString(),"dueAt",until.toString(),"receiptId",receipt));
        }
        db.update("UPDATE archive.physical_delivery SET state='DELIVERED',final_digest=? WHERE id=?",digest,command);db.update("UPDATE archive.borrow_request SET approved_until=(SELECT max(due_at) FROM archive.physical_loan WHERE request_id=?) WHERE id=?",request,request);audit.event(context.actor().userId(),"ARCHIVE","PHYSICAL_DELIVERY",command,"PHYSICAL_DELIVERED","SUCCEEDED",context.request());commands.event("BORROW_REQUEST",request,"PHYSICAL_DELIVERED");gate.requireWritable("ARCHIVE","PHYSICAL_DELIVERY");return delivery(command);
    }
    /** 两个不同账号核对同一交付时间、清单和说明；占用和应还日期已由末级审批写入。 */
    private Map<String,Object> confirmPickup(Commands.Context context,Actor actor,Map<String,Object> request,List<UUID> ids,Instant actual,Map<String,Object> body){
        UUID requestId=(UUID)request.get("id"),owner=(UUID)request.get("owner_department_id");
        boolean archivist=authority.role(actor,"DEPARTMENT_ARCHIVIST",owner),superAdmin=authority.role(actor,"SUPER_ADMIN",null);
        return commands.run(context,body,"BORROW_REQUEST",requestId,"DELIVER_PHYSICAL",true,()->{
            if(!archivist&&!superAdmin)throw ApiFault.denied();
        },()->{
            paper.approved(requestId);
            boolean completed=true;
            for(UUID id:ids){
                var loan=db.required("SELECT * FROM archive.physical_loan WHERE request_id=? AND physical_item_id=? FOR UPDATE",requestId,id);
                UUID loanId=(UUID)loan.get("id");
                if(!"IN_USE".equals(loan.get("state"))||loan.get("actual_delivery_at")!=null||
                    db.count("SELECT count(*) FROM archive.physical_return WHERE loan_id=? AND state='PENDING'",loanId)>0)
                    throw ApiFault.conflict("当前原件已交付或正在归还");
                Instant deadline=Instant.parse(loan.get("due_at").toString());
                if(actual.isBefore(Instant.parse(loan.get("reserved_at").toString()))||
                    !actual.isBefore(deadline)||(loan.get("pickup_by")==null&&!time.now().isBefore(deadline)))
                    throw ApiFault.conflict("请在批准后的有效借阅期内交付");
                if(loan.get("pickup_by")==null){
                    String role=archivist?"ARCHIVIST":"ARCHIVE_ADMIN";
                    db.update("UPDATE archive.physical_loan SET pickup_by=?,pickup_role=?,pickup_at=?,condition=?,row_version=row_version+1 WHERE id=?",
                        actor.userId(),role,java.sql.Timestamp.from(actual),body.get("condition"),loanId);
                    items.event(id,loanId,actor,"PICKUP_REGISTERED",Sql.map("role",role,"actualDeliveryAt",actual.toString()));
                    completed=false;
                }else{
                    if(actor.userId().equals(loan.get("pickup_by"))||
                        !("ARCHIVIST".equals(loan.get("pickup_role"))?superAdmin:archivist))throw ApiFault.denied();
                    if(!actual.equals(Instant.parse(loan.get("pickup_at").toString()))||
                        !Objects.equals(body.get("condition"),loan.get("condition")))
                        throw ApiFault.conflict("请按另一位经办人登记的交付时间和说明确认");
                    UUID receipt=receipts.custody(actor,id,loanId,"PHYSICAL_DELIVER",context.request());
                    db.update("UPDATE archive.physical_loan SET delivered_by=?,delivery_receipt_id=?,actual_delivery_at=?,row_version=row_version+1 WHERE id=?",
                        actor.userId(),receipt,java.sql.Timestamp.from(actual),loanId);
                    items.event(id,loanId,actor,"DELIVERED",Sql.map("requestId",requestId,"receiptId",receipt));
                }
            }
            return Sql.map("id",requestId,"state",completed?"DELIVERED":"WAITING_OTHER_HANDLER");
        },id->Sql.map("id",id,"state",db.count("""
            SELECT count(*) FROM archive.physical_loan WHERE request_id=?
              AND physical_item_id IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) AND actual_delivery_at IS NULL
            """,id,Crypto.json(ids))==0?"DELIVERED":"WAITING_OTHER_HANDLER"));
    }
    /** 新流程先送归还审批，待取件的占用也可提交；旧流程仍直接送实收复核。 */
    @Transactional
    public Map<String,Object> requestReturn(Commands.Context context,UUID loan,Map<String,Object> body){
        Inputs.only(body,"actualQuantity","condition","note");int quantity=Inputs.integer(body.get("actualQuantity"),0,0,1);Inputs.text(body,"condition",2000);if(body.get("note")!=null)Inputs.text(body,"note",2000);
        return commands.run(context,body,"PHYSICAL_LOAN",loan,"REQUEST_PHYSICAL_RETURN",false,()->{if(!context.actor().userId().equals(db.required("SELECT borrower_id FROM archive.physical_loan WHERE id=?",loan).get("borrower_id")))throw ApiFault.hidden();},()->{
            var row=lockedLoan(loan);version(context,row);if(!"IN_USE".equals(row.get("state")))throw ApiFault.conflict("原件已归还");if(db.count("SELECT count(*) FROM archive.physical_return WHERE loan_id=? AND state='PENDING'",loan)>0)throw ApiFault.conflict("已有归还申请等待实际复核");
            boolean currentFlow=row.get("reserved_at")!=null;
            boolean uncollected=row.get("actual_delivery_at")==null&&row.get("pickup_at")==null;
            if(currentFlow&&uncollected&&quantity!=0)throw ApiFault.invalid("未取件时归还数量应填0");
            UUID id=UUID.randomUUID();db.update("INSERT INTO archive.physical_return(id,loan_id,reported_quantity,condition,note) VALUES(?,?,?,?,?)",id,loan,quantity,body.get("condition"),body.get("note"));db.update("UPDATE archive.physical_loan SET row_version=row_version+1 WHERE id=?",loan);items.event((UUID)row.get("physical_item_id"),loan,context.actor(),"RETURN_REQUESTED",Sql.map("returnRequestId",id,"reportedQuantity",quantity));
            if(currentFlow){
                var request=db.required("""
                    SELECT q.owner_department_id,q.batch_id,b.applicant_department_id,b.parent_max_classification,plan.members
                    FROM archive.borrow_request q JOIN archive.borrow_batch b ON b.id=q.batch_id
                    JOIN archive.paper_request_item plan ON plan.request_id=q.id
                    WHERE q.id=? AND plan.physical_item_id=?
                    """,row.get("request_id"),row.get("physical_item_id"));
                UUID applicantDepartment=(UUID)request.get("applicant_department_id"),owner=(UUID)request.get("owner_department_id");
                var members=Crypto.JSON.readValue(request.get("members").toString(),List.class);
                var snapshot=Sql.map("form","PAPER","loanId",loan,"departmentId",owner,"applicantDepartmentId",applicantDepartment,
                    "purpose",body.get("condition"),"uncollected",uncollected,"items",members,
                    "highestClassification",request.get("parent_max_classification"));
                UUID flow=workflow.create(context.actor(),"PHYSICAL_RETURN",id,(UUID)request.get("batch_id"),
                    request.get("parent_max_classification").toString(),0,snapshot,FixedBorrowRoute.managerFirst(applicantDepartment,owner,false));
                db.update("UPDATE archive.physical_return SET workflow_instance_id=? WHERE id=?",flow,id);
                return Sql.map("id",id,"loanId",loan,"state","PENDING_APPROVAL","occupancyReleased",false);
            }
            for(var person:db.rows("SELECT DISTINCT a.user_id FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id WHERE a.module='ARCHIVE' AND a.role_code='SUPER_ADMIN' AND a.state='ACTIVE' AND r.status='ACTIVE' AND iam.archive_access_enabled(a.user_id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)"))notice(row,(UUID)person.get("user_id"),"RETURN_REQUESTED");return Sql.map("id",id,"loanId",loan,"state","PENDING_REVIEW","occupancyReleased",false);
        },id->returnView(context.actor(),id));
    }
    /** 新流程由所属部门档案员登记，再由另一个档案超管实收；旧流程保留直接复核。 */
    @Transactional public Map<String,Object> review(Commands.Context context,UUID loan,Map<String,Object> body){
        Inputs.only(body,"returnRequestId","result","actualReceivedAt","actualQuantity","condition","allAttachmentsConfirmed","intactConfirmed");UUID returnId=Inputs.uuid(body.get("returnRequestId"));String result=Inputs.choice(body.get("result"),"CONFIRM","EXCEPTION");int quantity=Inputs.integer(body.get("actualQuantity"),-1,0,1);Inputs.text(body,"condition",2000);
        Instant actual=Instant.parse(Inputs.text(body,"actualReceivedAt",64));if(actual.isAfter(time.now()))throw ApiFault.invalid("实际收到时间不能晚于服务端当前时间");
        var pre=db.required("SELECT l.borrower_id,l.reserved_at,b.department_id FROM archive.physical_loan l JOIN archive.box b ON b.id=l.box_id WHERE l.id=?",loan);
        lockPeople(context.actor(),(UUID)pre.get("borrower_id"));
        boolean currentFlow=pre.get("reserved_at")!=null;
        boolean archivist=currentFlow&&context.actor().actions().contains("ARCHIVE.PHYSICAL_MANAGE")&&
            authority.role(context.actor(),"DEPARTMENT_ARCHIVIST",(UUID)pre.get("department_id"));
        boolean superAdmin=context.actor().actions().contains("ARCHIVE.PHYSICAL_REVIEW")&&authority.role(context.actor(),"SUPER_ADMIN",null);
        return commands.run(context,body,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",true,()->{
            if((!archivist&&!superAdmin)||context.actor().userId().equals(pre.get("borrower_id")))throw ApiFault.denied();
        },()->{
            var row=lockedLoan(loan);version(context,row);var returned=db.required("SELECT * FROM archive.physical_return WHERE id=? AND loan_id=? FOR UPDATE",returnId,loan);if(!"IN_USE".equals(row.get("state"))||!"PENDING".equals(returned.get("state")))throw ApiFault.conflict("本轮归还已复核或借出已结束");
            Object pickup=row.get("actual_delivery_at")!=null?row.get("actual_delivery_at"):row.get("pickup_at");
            boolean uncollected=pickup==null;
            Object earliest=uncollected?row.get("reserved_at"):pickup;
            if(actual.isBefore(Instant.parse(earliest.toString())))throw ApiFault.invalid("收到时间不能早于批准或实际取件");
            if(result.equals("CONFIRM")&&(uncollected?quantity!=0:quantity!=1||!Boolean.TRUE.equals(body.get("allAttachmentsConfirmed"))||!Boolean.TRUE.equals(body.get("intactConfirmed"))))
                throw ApiFault.invalid("请核对实际收到数量、附件及完好情况；未取件数量应为0");
            if(currentFlow){
                if(!Boolean.TRUE.equals(db.required("SELECT workflow.approval_valid(?,'PHYSICAL_RETURN',?) AS approved",returned.get("workflow_instance_id"),returnId).get("approved")))
                    throw ApiFault.conflict("归还审批通过后才能登记交接");
                if(returned.get("registered_by")==null){
                    if(!archivist)throw ApiFault.denied();
                    db.update("UPDATE archive.physical_return SET registered_by=?,registration=?::jsonb WHERE id=?",context.actor().userId(),Crypto.json(body),returnId);
                    db.update("UPDATE archive.physical_loan SET row_version=row_version+1 WHERE id=?",loan);
                    items.event((UUID)row.get("physical_item_id"),loan,context.actor(),"RETURN_HANDOFF_REGISTERED",Sql.map("returnRequestId",returnId,"uncollected",uncollected));
                    return Sql.map("id",returnId,"loanId",loan,"state","WAITING_SUPER_ADMIN","occupancyReleased",false);
                }
                if(!superAdmin||context.actor().userId().equals(returned.get("registered_by")))throw ApiFault.denied();
                var registered=Crypto.JSON.readValue(returned.get("registration").toString(),Map.class);
                if(!body.equals(registered))throw ApiFault.conflict("请核对档案员登记的同一交接信息后确认");
            }
            String event=uncollected&&result.equals("CONFIRM")?"RETURN_UNCOLLECTED":"RETURN_"+result;
            UUID receipt=receipts.custody(context.actor(),(UUID)row.get("physical_item_id"),loan,event,context.request());db.update("UPDATE archive.physical_return SET state=?,reviewed_by=?,receipt_id=?,actual_received_at=?,received_quantity=?,review_condition=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",result.equals("CONFIRM")?"CONFIRMED":"EXCEPTION",context.actor().userId(),receipt,java.sql.Timestamp.from(actual),quantity,body.get("condition"),returnId);
            db.update("UPDATE archive.physical_loan SET state=?,returned_at=?,row_version=row_version+1 WHERE id=?",result.equals("CONFIRM")?"RETURNED":"IN_USE",result.equals("CONFIRM")?java.sql.Timestamp.from(actual):null,loan);items.event((UUID)row.get("physical_item_id"),loan,context.actor(),event,Sql.map("returnRequestId",returnId,"actualQuantity",quantity,"receiptId",receipt));
            db.update("UPDATE archive.physical_notice SET state='CANCELLED' WHERE loan_id=? AND state<>'CANCELLED'",loan);notice(row,(UUID)row.get("borrower_id"),result.equals("CONFIRM")?"RETURN_CONFIRMED":"RETURN_EXCEPTION");
            if(result.equals("CONFIRM"))closeCompleted(loan);return Sql.map("id",returnId,"loanId",loan,"state",result.equals("CONFIRM")?"CONFIRMED":"EXCEPTION","occupancyReleased",result.equals("CONFIRM"));
        },id->returnView(context.actor(),id));
    }
    /** 归还审批仅批准交接资格，拒绝或撤回后可重新申请，所有状态仍保持原件占用。 */
    @Override public String type(){return "PHYSICAL_RETURN";}
    @Override public void verify(Map<String,Object> instance){
        db.required("""
            SELECT r.id FROM archive.physical_return r JOIN archive.physical_loan l ON l.id=r.loan_id
            WHERE r.id=? AND r.workflow_instance_id=? AND r.state='PENDING' AND l.state='IN_USE' AND l.borrower_id=?
            """,instance.get("business_id"),instance.get("id"),instance.get("applicant_id"));
    }
    @Override public void finish(Map<String,Object> instance,boolean approved,UUID approver){
        if(approved)verify(instance);
        else db.update("UPDATE archive.physical_return SET state='EXCEPTION' WHERE id=? AND state='PENDING'",instance.get("business_id"));
        db.update("UPDATE archive.physical_loan SET row_version=row_version+1 WHERE id=(SELECT loan_id FROM archive.physical_return WHERE id=?)",instance.get("business_id"));
    }
    @Override public Map<String,Object> view(Actor actor,Map<String,Object> instance){
        var snapshot=WorkflowEngine.snapshot(instance);
        return Sql.map("type",type(),"form","PAPER","purpose",snapshot.get("purpose"),"items",snapshot.get("items"),
            "highestClassification",snapshot.get("highestClassification"),"uncollected",snapshot.get("uncollected"));
    }
    /** 个人借还或职责范围清单；服务端直接计算逾期和归还待复核，不依赖定时器改主状态。 */
    public Map<String,Object> list(Actor actor,Map<String,String> query){
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"page","pageSize","scope","state","keyword","borrower","deliveryFrom","deliveryTo","dueFrom","dueTo");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);boolean manage="MANAGE".equals(query.get("scope"));if(query.containsKey("scope"))Inputs.choice(query.get("scope"),"MINE","MANAGE");boolean all=authority.role(actor,"SUPER_ADMIN",null);UUID department=Inputs.uuid(actor.user().get("departmentId"));if(manage){actor.require("ARCHIVE.PHYSICAL_MANAGE");authority.requireManage(actor,department);}
        String where=" FROM archive.physical_loan l JOIN archive.physical_item p ON p.id=l.physical_item_id JOIN archive.box b ON b.id=l.box_id WHERE "+(manage?"(? OR b.department_id=?)":"l.borrower_id=?");List<Object> args=new ArrayList<>(manage?List.of(all,department):List.of(actor.userId()));if(query.containsKey("state")){where+=" AND l.state=?";args.add(Inputs.choice(query.get("state"),"IN_USE","RETURNED"));}where+=new ListConditions(query,args)
            .match("keyword","concat_ws(' ',p.barcode,(SELECT plan.label FROM archive.paper_request_item plan WHERE plan.request_id=l.request_id AND plan.physical_item_id=l.physical_item_id)) ILIKE ?")
            .match("borrower","EXISTS(SELECT 1 FROM iam.user_account borrower WHERE borrower.id=l.borrower_id AND concat_ws(' ',borrower.name,borrower.employee_no) ILIKE ?)")
            .dates("delivery","COALESCE(l.actual_delivery_at,l.reserved_at)").dates("due","l.due_at").sql();
        long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);
        // 分页后再补题名和借阅人；计数仍只查原授权范围。
        var rows=db.rows("SELECT page.*,plan.label,u.name AS borrower_name FROM (SELECT l.*,p.barcode,p.object_type,EXISTS(SELECT 1 FROM archive.physical_return pr WHERE pr.loan_id=l.id AND pr.state='PENDING') AS return_pending,l.due_at<CURRENT_TIMESTAMP AND l.state='IN_USE' AS overdue"+where+" ORDER BY COALESCE(l.actual_delivery_at,l.reserved_at) DESC,l.id LIMIT ? OFFSET ?) page LEFT JOIN archive.paper_request_item plan ON plan.request_id=page.request_id AND plan.physical_item_id=page.physical_item_id JOIN iam.user_account u ON u.id=page.borrower_id ORDER BY COALESCE(page.actual_delivery_at,page.reserved_at) DESC,page.id",args.toArray());return Sql.map("items",rows.stream().map(row->Sql.view(row,"id,request_id,current_request_id,physical_item_id,borrower_id,borrower_name,label,barcode,object_type,actual_delivery_at,reserved_at,pickup_at,due_at,state,row_version,return_pending,overdue")).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 借阅人或实际目录职责范围内经办人取得历史；知道loanId不授予其他人的实物详情。 */
    public Map<String,Object> detail(Actor actor,UUID loan){
        actor.require("ARCHIVE.ACCESS");var row=db.required("SELECT l.*,p.barcode,p.object_type,b.department_id,l.due_at<CURRENT_TIMESTAMP AND l.state='IN_USE' AS overdue FROM archive.physical_loan l JOIN archive.physical_item p ON p.id=l.physical_item_id JOIN archive.box b ON b.id=l.box_id WHERE l.id=?",loan);
        UUID department=(UUID)row.get("department_id");
        boolean borrower=actor.userId().equals(row.get("borrower_id"));
        boolean archivist=actor.actions().contains("ARCHIVE.PHYSICAL_MANAGE")&&authority.role(actor,"DEPARTMENT_ARCHIVIST",department);
        boolean superAdmin=actor.actions().contains("ARCHIVE.PHYSICAL_REVIEW")&&authority.role(actor,"SUPER_ADMIN",null);
        boolean manager=actor.actions().contains("ARCHIVE.PHYSICAL_MANAGE")&&authority.role(actor,"SUPER_ADMIN",null);
        if(!borrower&&!archivist&&!superAdmin&&!manager&&!Boolean.TRUE.equals(db.required(
            "SELECT archive.paper_due_recipient(?,?) AS allowed",loan,actor.userId()).get("allowed"))){
            actor.require("ARCHIVE.PHYSICAL_MANAGE");
            authority.requireManage(actor,department);
        }
        // 初借用途、部门及原件题名均取自原申请；安全题名不会被当前档案完整题名替换。
        var context=db.required("""
            SELECT batch.purpose,batch.submitted_at,u.name AS borrower_name,u.employee_no AS borrower_employee_no,
                   d.name AS borrower_department_name,handler.name AS delivered_by_name,plan.label,
                   CASE WHEN ?='BOX' THEN ARRAY(SELECT member->>'label' FROM jsonb_array_elements(plan.members) WITH ORDINALITY AS m(member,position) ORDER BY position)
                        ELSE ARRAY[]::text[] END AS member_labels
            FROM archive.borrow_request q JOIN archive.borrow_batch batch ON batch.id=q.batch_id
            JOIN iam.user_account u ON u.id=batch.applicant_id JOIN iam.department d ON d.id=batch.applicant_department_id
            LEFT JOIN iam.user_account handler ON handler.id=?
            LEFT JOIN archive.paper_request_item plan ON plan.request_id=q.id AND plan.physical_item_id=?
            WHERE q.id=?
            """,row.get("object_type"),row.get("delivered_by"),row.get("physical_item_id"),row.get("request_id"));
        var returns=db.rows("""
            SELECT r.*,u.name AS reviewed_by_name,w.state AS approval_state
            FROM archive.physical_return r LEFT JOIN iam.user_account u ON u.id=r.reviewed_by
            LEFT JOIN workflow.instance w ON w.id=r.workflow_instance_id WHERE r.loan_id=?
            ORDER BY r.submitted_at DESC,r.id LIMIT 50
            """,loan);
        var pending=returns.stream().filter(value->"PENDING".equals(value.get("state"))).findFirst().orElse(null);
        boolean currentFlow=row.get("reserved_at")!=null,inUse="IN_USE".equals(row.get("state"));
        boolean approved=pending!=null&&"APPROVED".equals(pending.get("approval_state"));
        Object registered=pending==null?null:pending.get("registered_by");
        var projection=Sql.view(row,"id,request_id,current_request_id,physical_item_id,borrower_id,barcode,object_type,actual_delivery_at,reserved_at,pickup_at,due_at,actual_quantity,condition,state,returned_at,row_version,overdue");
        projection.putAll(Sql.view(context,"label,member_labels,purpose,submitted_at,borrower_name,borrower_employee_no,borrower_department_name,delivered_by_name"));
        projection.put("canRequestReturn",borrower&&inUse&&pending==null);
        projection.put("canRegisterReturn",!borrower&&archivist&&approved&&registered==null);
        projection.put("canReview",!borrower&&superAdmin&&pending!=null&&(!currentFlow||approved&&registered!=null&&!registered.equals(actor.userId())));
        projection.put("canRenew",borrower&&actor.actions().contains("ARCHIVE.PHYSICAL_BORROW")&&inUse&&pending==null);
        return Sql.map("loan",projection,"returns",returns.stream().map(value->{
            var result=Sql.view(value,"id,reported_quantity,condition,note,state,submitted_at,reviewed_by,actual_received_at,received_quantity,review_condition,reviewed_at,reviewed_by_name,approval_state,registered_by");
            if(value.get("registration")!=null)result.put("registration",Crypto.JSON.readValue(value.get("registration").toString(),Map.class));
            return result;
        }).toList(),"events",db.rows("SELECT id,event_type,actor_id,occurred_at FROM archive.loan_event WHERE loan_id=? ORDER BY occurred_at,id LIMIT 100",loan).stream().map(value->Sql.view(value,"id,event_type,actor_id,occurred_at")).toList());
    }
    /** 本人申请或所属部门经办人查询待交付范围，范围不从电子权限推导。 */
    public Map<String,Object> requests(Actor actor,Map<String,String> query){
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"page","pageSize","requestId","keyword","state");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        boolean all=authority.role(actor,"SUPER_ADMIN",null),manage=actor.actions().contains("ARCHIVE.PHYSICAL_MANAGE")&&(all||authority.role(actor,"DEPARTMENT_ARCHIVIST",Inputs.uuid(actor.user().get("departmentId"))));
        // 计数和数据共用授权谓词，申请人只看本人，档案员只看本部门。
        String from="""
             FROM archive.borrow_request q JOIN archive.borrow_batch b ON b.id=q.batch_id
             JOIN archive.paper_request_item s ON s.request_id=q.id
             JOIN archive.physical_item p ON p.id=s.physical_item_id
             WHERE b.form='PAPER' AND (q.applicant_id=? OR (? AND (? OR q.owner_department_id=?)))
            """;
        List<Object> parameters=new ArrayList<>(List.of(actor.userId(),manage,all,Inputs.uuid(actor.user().get("departmentId"))));
        // 首页只传稳定申请标识，筛选仍叠加本人或实际部门经办范围。
        if(query.containsKey("requestId")){from+=" AND q.id=?";parameters.add(Inputs.uuid(query.get("requestId")));}
        from+=new ListConditions(query,parameters)
            .contains("keyword","concat_ws(' ',s.label,p.barcode)")
            .choice("state","q.state","PENDING","APPROVED","REJECTED","CLOSED","WITHDRAWN").sql();
        long total=db.count("SELECT count(*)"+from,parameters.toArray());parameters.add(size);parameters.add((page-1)*size);
        var rows=db.rows("""
            SELECT q.id,q.batch_id,q.applicant_id,q.state,b.purpose,q.owner_department_id,s.id AS plan_id,
                   s.physical_item_id,s.label,p.barcode,p.object_type,handoff.*
            FROM (
              SELECT q.id AS request_id,s.id AS plan_id
            """+from+"""
              ORDER BY b.submitted_at DESC,q.id,s.id LIMIT ? OFFSET ?
            ) selected JOIN archive.borrow_request q ON q.id=selected.request_id
            JOIN archive.borrow_batch b ON b.id=q.batch_id
            JOIN archive.paper_request_item s ON s.id=selected.plan_id
            JOIN archive.physical_item p ON p.id=s.physical_item_id
            LEFT JOIN LATERAL (
              SELECT (l.actual_delivery_at IS NOT NULL OR l.request_id<>q.id) AS delivered,l.reserved_at,l.pickup_at,l.pickup_by,l.pickup_role,
                     l.condition AS pickup_condition,l.due_at,l.state AS loan_state,
                     EXISTS(SELECT 1 FROM archive.physical_return r WHERE r.loan_id=l.id AND r.state='PENDING') AS return_pending
              FROM archive.paper_loan_scope scope JOIN archive.physical_loan l ON l.id=scope.loan_id
              WHERE scope.request_id=q.id AND l.physical_item_id=s.physical_item_id
            ) handoff ON true ORDER BY b.submitted_at DESC,q.id,s.id
            """,parameters.toArray());
        // 一次查询本页所有待补批操作，支持批量交付从原完整清单恢复，不按每行查询。
        var pending=new HashMap<String,Map<String,Object>>();String requestIds=Crypto.json(rows.stream().map(row->row.get("id").toString()).distinct().toList());
        for(var command:db.rows("SELECT request_id,operation_id,body_snapshot FROM archive.physical_delivery WHERE actor_id=? AND state='WAITING_APPROVAL' AND request_id IN(SELECT jsonb_array_elements_text(?::jsonb)::uuid) ORDER BY created_at DESC",actor.userId(),requestIds)){
            Map<?,?> saved=Crypto.JSON.readValue(command.get("body_snapshot").toString(),Map.class);for(Object id:(List<?>)saved.get("physicalItemIds"))pending.putIfAbsent(command.get("request_id")+":"+id,Sql.map("operationId",command.get("operation_id"),"condition",saved.get("condition"),"physicalItemIds",saved.get("physicalItemIds")));
        }
        UUID actorDepartment=Inputs.uuid(actor.user().get("departmentId"));
        return Sql.map("items",rows.stream().map(row->{
            var result=Sql.view(row,"id,batch_id,applicant_id,state,purpose,owner_department_id,physical_item_id,label,barcode,object_type,delivered,pickup_at,reserved_at");
            var prior=pending.get(row.get("id")+":"+row.get("physical_item_id"));
            if(prior!=null){result.put("pendingOperationId",prior.get("operationId"));result.put("pendingCondition",prior.get("condition"));result.put("pendingPhysicalItemIds",prior.get("physicalItemIds"));}
            Object pickupBy=row.get("pickup_by");
            boolean canDeliver=manage&&!actor.userId().equals(row.get("applicant_id"))&&"APPROVED".equals(row.get("state"))&&!Boolean.TRUE.equals(row.get("delivered"));
            if(row.get("reserved_at")!=null)canDeliver=canDeliver&&"IN_USE".equals(row.get("loan_state"))&&!Boolean.TRUE.equals(row.get("return_pending"))&&
                (pickupBy==null?time.now().isBefore(Instant.parse(row.get("due_at").toString())):
                    !actor.userId().equals(pickupBy)&&("ARCHIVIST".equals(row.get("pickup_role"))?all:
                        actorDepartment.equals(row.get("owner_department_id"))&&authority.role(actor,"DEPARTMENT_ARCHIVIST",actorDepartment)));
            result.put("canDeliver",canDeliver);
            if(canDeliver&&pickupBy!=null)result.put("pendingCondition",row.get("pickup_condition"));
            return result;
        }).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 每轮最多200笔，租约式事务锁让多实例不会重复产生同日催还；逾期始终保留占用。 */
    public void tick(){gate.requireWritable("ARCHIVE","PHYSICAL_REMINDERS");tx.executeWithoutResult(status->{if(!Boolean.TRUE.equals(db.required("SELECT pg_try_advisory_xact_lock(61427008) AS acquired").get("acquired")))return;LocalDate today=time.now().atZone(WorkCalendarService.ZONE).toLocalDate();
        db.update("WITH stale AS(SELECT n.id FROM archive.physical_notice n JOIN archive.physical_loan l ON l.id=n.loan_id WHERE n.state<>'CANCELLED' AND n.kind IN ('DUE_TOMORROW','DUE_TODAY','OVERDUE') AND (l.state<>'IN_USE' OR l.due_at<>n.deadline) LIMIT 200) UPDATE archive.physical_notice n SET state='CANCELLED' FROM stale WHERE n.id=stale.id");
        for(var loan:db.rows("SELECT l.*,b.department_id FROM archive.physical_loan l JOIN archive.box b ON b.id=l.box_id WHERE l.state='IN_USE' AND l.reserved_at IS NULL AND l.due_at<? AND NOT EXISTS(SELECT 1 FROM archive.physical_notice n WHERE n.loan_id=l.id AND n.user_id=l.borrower_id AND n.deadline=l.due_at AND n.notice_day=? AND n.kind=CASE WHEN l.due_at<? THEN 'OVERDUE' WHEN (l.due_at AT TIME ZONE 'Asia/Shanghai')::date=?::date THEN 'DUE_TODAY' ELSE 'DUE_TOMORROW' END AND n.state<>'CANCELLED') ORDER BY l.due_at,l.id LIMIT 200",java.sql.Timestamp.from(today.plusDays(2).atStartOfDay(WorkCalendarService.ZONE).toInstant()),today,java.sql.Timestamp.from(time.now()),today)){
            LocalDate deadline=Instant.parse(loan.get("due_at").toString()).atZone(WorkCalendarService.ZONE).toLocalDate();String kind=Instant.parse(loan.get("due_at").toString()).isBefore(time.now())?"OVERDUE":deadline.equals(today)?"DUE_TODAY":"DUE_TOMORROW";notice(loan,(UUID)loan.get("borrower_id"),kind);
            for(var clerk:db.rows("SELECT a.user_id FROM iam.user_role a WHERE a.module='ARCHIVE' AND a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=? AND a.state='ACTIVE' AND iam.archive_access_enabled(a.user_id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)",loan.get("department_id")))notice(loan,(UUID)clerk.get("user_id"),kind);
        }gate.requireWritable("ARCHIVE","PHYSICAL_REMINDERS");});}
    @Scheduled(fixedDelayString="${platform.borrow-lifecycle.delay-ms:60000}") public void scheduled(){if(enabled)try{tick();}catch(ApiFault failure){if(failure.status!=503)throw failure;}}
    /** 站内事项仅返回本人及当前仍有职责的记录，不包含正文或秘密题名。 */
    public Map<String,Object> notices(Actor actor){actor.require("ARCHIVE.ACCESS");boolean manage=actor.actions().contains("ARCHIVE.PHYSICAL_MANAGE")&&(authority.role(actor,"SUPER_ADMIN",null)||authority.role(actor,"DEPARTMENT_ARCHIVIST",Inputs.uuid(actor.user().get("departmentId"))));return Sql.map("items",db.rows("SELECT n.* FROM archive.physical_notice n JOIN archive.physical_loan l ON l.id=n.loan_id JOIN archive.box b ON b.id=l.box_id WHERE n.user_id=? AND n.state<>'CANCELLED' AND (l.borrower_id=? OR (? AND (? OR b.department_id=?))) AND (n.kind IN ('RETURN_CONFIRMED','RETURN_EXCEPTION') OR (l.state='IN_USE' AND n.deadline=l.due_at)) ORDER BY n.created_at DESC,n.id LIMIT 100",actor.userId(),actor.userId(),manage,authority.role(actor,"SUPER_ADMIN",null),Inputs.uuid(actor.user().get("departmentId"))).stream().map(row->Sql.view(row,"id,loan_id,kind,deadline,notice_day,state,created_at")).toList());}
    /** 统一锁住实际经办人和借阅人后才锁盒，避免复核与续借互相倒置身份锁。 */
    private void lockPeople(Actor actor,UUID borrower){db.rows("SELECT id FROM iam.user_account WHERE id IN (?,?) ORDER BY id FOR UPDATE",actor.userId(),borrower);}
    /** 返回时同样先锁盒后锁借出，不能只依赖单件唯一索引。 */
    private Map<String,Object> lockedLoan(UUID loan){var pre=db.required("SELECT physical_item_id FROM archive.physical_loan WHERE id=?",loan);items.lock(List.of((UUID)pre.get("physical_item_id")));return db.required("SELECT * FROM archive.physical_loan WHERE id=? FOR UPDATE",loan);}
    /** 重复归还操作回读同一轮结果，不重复释放或删除历史事件。 */
    private Map<String,Object> returnView(Actor actor,UUID returned){var row=db.required("SELECT * FROM archive.physical_return WHERE id=?",returned);detail(actor,(UUID)row.get("loan_id"));return Sql.map("id",returned,"loanId",row.get("loan_id"),"state",row.get("state"),"occupancyReleased","CONFIRMED".equals(row.get("state")));}
    /** 回执只包含本次已完成的实际借出，不把待批准状态缓存为永久失败。 */
    private Map<String,Object> delivery(UUID id){var row=db.required("SELECT id,state FROM archive.physical_delivery WHERE id=?",id);return Sql.map("id",id,"state",row.get("state"),"loans",db.rows("SELECT l.id,l.physical_item_id,l.due_at,l.row_version FROM archive.physical_delivery_loan s JOIN archive.physical_loan l ON l.id=s.loan_id WHERE s.delivery_id=? ORDER BY l.id",id).stream().map(value->Sql.view(value,"id,physical_item_id,due_at,row_version")).toList());}
    private Map<String,Object> waiting(UUID id,Object step){return Sql.map("id",id,"state","WAITING_APPROVAL","requiredStepId",step,"occupancyCreated",false);}
    /** 所有相关原件实际收到后才结束原审批与续借；每份决定仍完整保留。 */
    private void closeCompleted(UUID loan){for(var q:db.rows("SELECT DISTINCT q.id,q.batch_id,q.workflow_instance_id FROM archive.paper_loan_scope scope JOIN archive.borrow_request q ON q.id=scope.request_id WHERE scope.loan_id=? AND q.state IN ('APPROVED','PENDING') ORDER BY q.id",loan)){
        if(db.count("SELECT count(*) FROM archive.paper_loan_scope s JOIN archive.physical_loan l ON l.id=s.loan_id WHERE s.request_id=? AND l.state='IN_USE'",q.get("id"))!=0)continue;
        if(db.count("SELECT count(*) FROM archive.paper_request_item WHERE request_id=?",q.get("id"))>db.count("SELECT count(*) FROM archive.paper_loan_scope WHERE request_id=?",q.get("id")))continue;
        // 原件已全部实收时，在途续借也结束；未处理节点标为跳过，绝不补造批准决定。
        db.update("UPDATE archive.borrow_request SET state='CLOSED' WHERE id=?",q.get("id"));db.update("UPDATE workflow.instance SET state='CLOSED',current_step_id=NULL,row_version=row_version+1 WHERE id=? AND state IN ('APPROVED','PENDING')",q.get("workflow_instance_id"));db.update("UPDATE workflow.step SET state='SKIPPED',completed_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE instance_id=? AND state IN ('WAITING','PENDING')",q.get("workflow_instance_id"));paper.refresh((UUID)q.get("batch_id"));commands.event("BORROW_REQUEST",(UUID)q.get("id"),"PHYSICAL_RETURN_CLOSED");
    }}
    /** 通知重跑按原件、收件人、当前截止和日期去重，消息不改变业务状态。 */
    private void notice(Map<String,Object> loan,UUID user,String kind){db.update("INSERT INTO archive.physical_notice(id,loan_id,user_id,kind,deadline,notice_day) VALUES(?,?,?,?,?,?) ON CONFLICT(loan_id,user_id,kind,deadline,notice_day) DO UPDATE SET state='UNREAD' WHERE archive.physical_notice.state='CANCELLED'",UUID.randomUUID(),loan.get("id"),user,kind,java.sql.Timestamp.from(Instant.parse(loan.get("due_at").toString())),time.now().atZone(WorkCalendarService.ZONE).toLocalDate());}
    private void version(Commands.Context context,Map<String,Object> row){if(context.version()==null||context.version()!=((Number)row.get("row_version")).intValue())throw ApiFault.conflict("借还记录已变化，请刷新后核对");}
    /** UUID清单严格去重，不能靠重复原件夸大实际交付数量。 */
    private List<UUID> ids(Object value){if(!(value instanceof List<?> list)||list.isEmpty()||list.size()>50)throw ApiFault.invalid("请选择1至50项实际原件");List<UUID> ids=list.stream().map(Inputs::uuid).sorted().toList();if(new HashSet<>(ids).size()!=ids.size())throw ApiFault.invalid("交付清单不能重复原件");return ids;}
}
