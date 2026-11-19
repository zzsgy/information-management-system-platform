package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 电子借阅固定原清单、最高等级和日历版本；按所属部门拆单但不降低审批路线。 */
@Service
public class BorrowService implements WorkflowBusiness {
    /** 授权、期限、工作流与业务事件使用同一数据库事务。 */
    private final PaperBorrowing paper;
    private final ArchiveBorrowDirectory directory;
    private final Sql db;private final ArchiveAuthority authority;private final WorkCalendarService calendar;private final ArchiveCommands commands;private final WorkflowEngine workflows;
    public BorrowService(PaperBorrowing paper,Sql db,ArchiveAuthority authority,WorkCalendarService calendar,ArchiveCommands commands,WorkflowEngine workflows,ArchiveBorrowDirectory directory) {this.paper=paper;this.db=db;this.authority=authority;this.calendar=calendar;this.commands=commands;this.workflows=workflows;this.directory=directory;}
    /** 电子借阅专用业务类型，禁止借道改写其他审批流程。 */
    @Override public String type() {return "BORROW";}
    /** 电子借阅逐项固定独立动作与打印范围；批准查看不推导下载或打印。 */
    @Transactional
    public Map<String,Object> submit(Commands.Context context,Map<String,Object> body) {
        if("PAPER".equals(body.get("form")))return paper.submit(context,body);
        if("MIXED".equals(body.get("form")))return mixed(context,body);
        if(!"ELECTRONIC".equals(body.get("form")))throw ApiFault.invalid("请选择电子、纸质或电子+纸质借阅");
        return electronic(context,body);
    }
    private Map<String,Object> electronic(Commands.Context context,Map<String,Object> body) {
        Inputs.only(body,"form","actions","purpose","items","period","unwatermarked");var actions=UsagePages.actions(body.get("actions"));
        if(!(body.getOrDefault("unwatermarked",false) instanceof Boolean unwatermarked))
            throw ApiFault.invalid("请选择是否申请无水印版本");
        Inputs.text(body,"purpose",2000);var selections=Inputs.objects(body.get("items"),50);if(selections.isEmpty()) throw ApiFault.invalid("请指定借阅文件");var period=calendar.period(body.get("period"));
        return commands.run(context,body,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"SUBMIT_BORROW",false,()->context.actor().require("ARCHIVE.ACCESS"),()->{
            LinkedHashMap<UUID,Map<String,Object>> selected=new LinkedHashMap<>();
            for(var selection:selections) {
                Inputs.only(selection,"objectType","objectId","actions","pageRange","copies");if(!actions.equals(UsagePages.actions(selection.get("actions")))) throw ApiFault.invalid("逐项动作必须与父批次一致");if(!actions.contains("PRINT")&&(selection.get("pageRange")!=null||selection.get("copies")!=null)) throw ApiFault.invalid("仅打印动作接受页码和份数");UUID target=Inputs.uuid(selection.get("objectId"));String type=Inputs.choice(selection.get("objectType"),"RECORD","BOX");
                if(type.equals("BOX")&&!Boolean.TRUE.equals(directory.options(context.actor(),type,target).get("electronic")))throw ApiFault.hidden();
                List<UUID> ids=type.equals("RECORD")?List.of(target):db.rows("SELECT id FROM archive.record WHERE box_id=? AND state='PUBLISHED' AND current_pdf_version_id IS NOT NULL ORDER BY id LIMIT 51",target).stream().map(row->(UUID)row.get("id")).toList();
                if(ids.isEmpty()) throw ApiFault.hidden();for(UUID id:ids) {var record=authority.requireRequestable(context.actor(),id);record.put("printPages",actions.contains("PRINT")?UsagePages.parse(selection.get("pageRange"),UsagePages.all(((Number)db.required("SELECT page_count FROM archive.pdf_version WHERE id=?",record.get("current_pdf_version_id")).get("page_count")).intValue())):null);record.put("copies",actions.contains("PRINT")?Inputs.integer(selection.get("copies"),1,1,100):null);if(selected.putIfAbsent(id,record)!=null) throw ApiFault.invalid("展开清单不能重复指定文件");if(selected.size()>50) throw ApiFault.invalid("整批展开后最多50份不同文件，不能拆单绕过上限");}
            }
            int version=calendar.current();calendar.until(version,period,Instant.now());UUID applicantDepartment=Inputs.uuid(context.actor().user().get("departmentId"));
            String highest=selected.values().stream().map(row->(String)row.get("classification")).max(Comparator.comparingInt(this::rank)).orElseThrow();UUID batch=UUID.randomUUID();
            db.update("INSERT INTO archive.borrow_batch(id,applicant_id,applicant_department_id,form,actions,purpose,parent_max_classification,item_count,calendar_version,requested_period,requested_until) VALUES(?,?,?,'ELECTRONIC',ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),?,?,?,?,?::jsonb,?)",batch,context.actor().userId(),applicantDepartment,Crypto.json(actions),body.get("purpose"),highest,selected.size(),version,Crypto.json(period),period.get("requestedUntil")==null?null:java.sql.Timestamp.from(Instant.parse(period.get("requestedUntil").toString())));
            Map<UUID,List<Map<String,Object>>> byDepartment=new TreeMap<>();for(var row:selected.values()) byDepartment.computeIfAbsent((UUID)row.get("department_id"),key->new ArrayList<>()).add(row);
            for(var group:byDepartment.entrySet()) {
                UUID request=UUID.randomUUID();List<Map<String,Object>> items=new ArrayList<>();
                for(var row:group.getValue()) {var visible=authority.detail(context.actor(),(UUID)row.get("id"));String label="TITLE_ONLY".equals(visible.get("representation"))?Objects.toString(row.get("safe_title"),Objects.toString(row.get("file_no"))):Objects.toString(row.get("title"));for(String action:actions) items.add(Sql.map("recordId",row.get("id").toString(),"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",row.get("current_pdf_version_id").toString(),"classification",row.get("classification"),"label",label,"action",action,"printPages",action.equals("PRINT")?row.get("printPages"):null,"copies",action.equals("PRINT")?row.get("copies"):null));}
                var snapshot=Sql.map("batchId",batch.toString(),"requestId",request.toString(),"departmentId",group.getKey().toString(),"applicantDepartmentId",applicantDepartment.toString(),"highestClassification",highest,"items",items,"period",period,"calendarVersion",version);
                snapshot.put("unwatermarked",unwatermarked);
                UUID instance=workflows.create(context.actor(),"BORROW",request,batch,highest,version,snapshot,
                    FixedBorrowRoute.managerFirst(applicantDepartment,group.getKey(),unwatermarked||"CORE".equals(highest)));
                UUID route=(UUID)db.required("SELECT route_snapshot_id FROM workflow.instance WHERE id=?",instance).get("route_snapshot_id");
                db.update("INSERT INTO archive.borrow_request(id,batch_id,owner_department_id,applicant_id,workflow_instance_id,route_snapshot_id,approved_actions) VALUES(?,?,?,?,?,?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb)))",request,batch,group.getKey(),context.actor().userId(),instance,route,Crypto.json(actions));
                for(var item:items) db.update("INSERT INTO archive.request_item(id,batch_id,request_id,record_id,record_revision_id,pdf_version_id,action,classification_snapshot,approved_pages,copies) VALUES(?,?,?,?,?,?,?,?,CASE WHEN ?::jsonb IS NULL THEN NULL ELSE ARRAY(SELECT jsonb_array_elements_text(?::jsonb)::integer) END,?)",UUID.randomUUID(),batch,request,Inputs.uuid(item.get("recordId")),Inputs.uuid(item.get("recordRevisionId")),Inputs.uuid(item.get("pdfVersionId")),item.get("action"),item.get("classification"),item.get("printPages")==null?null:Crypto.json(item.get("printPages")),item.get("printPages")==null?null:Crypto.json(item.get("printPages")),item.get("copies"));
            }
            return batch(context.actor(),batch);
        },id->batch(context.actor(),id));
    }
    /** 同一份混合申请原子提交电子和纸质两批，各自沿用独立批准与交付。 */
    private Map<String,Object> mixed(Commands.Context context,Map<String,Object> body) {
        context.actor().require("ARCHIVE.PHYSICAL_BORROW");
        var electronic=electronic(context,body);
        var selections=Inputs.objects(body.get("items"),50);
        var originals=new ArrayList<Map<String,Object>>();
        for(var selection:selections){
            String type=Inputs.choice(selection.get("objectType"),"BOX","RECORD");
            UUID id=Inputs.uuid(selection.get("objectId"));
            var original=db.one(type.equals("BOX")
                ?"SELECT id FROM archive.physical_item WHERE box_id=? AND object_type='BOX'"
                :"SELECT id FROM archive.physical_item WHERE record_id=?",id);
            if(original==null)throw ApiFault.conflict("当前档案没有对应纸质原件");
            originals.add(Sql.map("physicalItemId",original.get("id")));
        }
        // 主回执保留混合请求原文，纸质分支使用同一操作号的内部回执。
        var paperContext=new Commands.Context(context.actor(),context.request(),context.operation(),context.route()+":MIXED:PAPER",null,null);
        var physical=paper.submit(paperContext,Sql.map("form","PAPER",
            "actions",List.of("BORROW_PHYSICAL"),"purpose",body.get("purpose"),"period",body.get("period"),"items",originals));
        return Sql.map("form","MIXED","electronicBatch",electronic,"paperBatch",physical);
    }
    /** 原借阅人对原批准范围发起续借；待审和驳回不改变任何原授权截止时间。 */
    public Map<String,Object> renew(Commands.Context context,UUID original,Map<String,Object> body) {
        if("PAPER".equals(db.required("SELECT form FROM archive.borrow_batch WHERE id=?",original).get("form")))throw ApiFault.conflict("纸质续借请按实际借出记录发起，不能混用电子续借");
        Inputs.only(body,"period","purpose","reason");Inputs.text(body,"purpose",2000);Inputs.text(body,"reason",1000);
        var period=calendar.period(body.get("period"));
        return commands.run(context,body,"ARCHIVE_COLLECTION",original,"RENEW_BORROW",false,()->{
            if(!context.actor().userId().equals(db.required("SELECT applicant_id FROM archive.borrow_batch WHERE id=?",original).get("applicant_id"))) throw ApiFault.hidden();
        },()->{
            // 原批次行锁与唯一索引共同约束不同操作号并发续借，回执重放在此分支之前返回。
            var parent=db.required("SELECT * FROM archive.borrow_batch WHERE id=? FOR UPDATE",original);if(!List.of("VIEW").equals(parent.get("actions"))) throw ApiFault.conflict("下载与打印额度不自动续期，请重新申请独立动作批准");
            if(!Set.of("APPROVED","PARTIAL").contains(parent.get("state"))||!parent.get("applicant_department_id").equals(Inputs.uuid(context.actor().user().get("departmentId")))) throw ApiFault.conflict("原借阅尚未批准或申请人部门已变化");
            if(db.count("SELECT count(*) FROM archive.borrow_batch WHERE renewal_of_batch_id=? AND state IN ('PENDING','PARTIAL','APPROVED')",original)>0) throw ApiFault.conflict("已有待审或成功续借，请查看原结果或续借最新批准批次");
            if(db.count("SELECT count(*) FROM archive.borrow_request WHERE batch_id=? AND state='PENDING'",original)>0) throw ApiFault.conflict("请等待原批次全部部门完成办理");
            var grants=db.rows("SELECT g.*,r.department_id,r.classification,r.title,r.safe_title FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id JOIN archive.record r ON r.id=g.record_id WHERE q.batch_id=? AND q.state='APPROVED' AND g.user_id=? ORDER BY r.department_id,g.record_id FOR SHARE OF g",original,context.actor().userId());
            if(grants.isEmpty()||grants.size()>50) throw ApiFault.conflict("原批次没有可续借的批准范围");
            int version=calendar.current();String highest=(String)parent.get("parent_max_classification");
            boolean unwatermarked=db.count("""
                SELECT count(*) FROM archive.borrow_request q JOIN workflow.instance w ON w.id=q.workflow_instance_id
                WHERE q.batch_id=? AND w.business_snapshot->>'unwatermarked'='true'
                """,original)>0;
            for(var grant:grants) {
                if(db.count("SELECT CASE WHEN archive.grant_valid(?,?) THEN 1 ELSE 0 END",grant.get("id"),context.actor().userId())!=1) throw ApiFault.conflict("原授权已到期、撤销或版本变化，请重新申请借阅");
                if(rank((String)grant.get("classification"))>rank(highest)) highest=(String)grant.get("classification");
                renewalUntil(version,period,Instant.now(),Instant.parse(grant.get("valid_until").toString()));
            }
            UUID batch=UUID.randomUUID(),department=Inputs.uuid(context.actor().user().get("departmentId"));
            db.update("INSERT INTO archive.borrow_batch(id,applicant_id,applicant_department_id,form,actions,purpose,parent_max_classification,item_count,calendar_version,requested_period,renewal_of_batch_id,renewal_reason) VALUES(?,?,?,'ELECTRONIC','{VIEW}',?,?,?,?,?::jsonb,?,?)",batch,context.actor().userId(),department,body.get("purpose"),highest,grants.size(),version,Crypto.json(period),original,body.get("reason"));
            Map<UUID,List<Map<String,Object>>> groups=new TreeMap<>();for(var grant:grants) groups.computeIfAbsent((UUID)grant.get("department_id"),key->new ArrayList<>()).add(grant);
            for(var group:groups.entrySet()) {
                UUID request=UUID.randomUUID();List<Map<String,Object>> items=new ArrayList<>();
                for(var grant:group.getValue()) items.add(Sql.map("recordId",grant.get("record_id").toString(),"recordRevisionId",grant.get("record_revision_id").toString(),"pdfVersionId",grant.get("pdf_version_id").toString(),"classification",grant.get("classification"),"label",grant.get("title"),"renewedGrantId",grant.get("id").toString(),"sourcePolicyVersion",grant.get("policy_version"),"previousUntil",grant.get("valid_until").toString()));
                var snapshot=Sql.map("batchId",batch.toString(),"requestId",request.toString(),"departmentId",group.getKey().toString(),"applicantDepartmentId",department.toString(),"highestClassification",highest,"items",items,"period",period,"calendarVersion",version,"renewalOfBatchId",original.toString(),"renewalReason",body.get("reason"));
                snapshot.put("unwatermarked",unwatermarked);
                UUID flow=workflows.create(context.actor(),"BORROW",request,batch,highest,version,snapshot,
                    FixedBorrowRoute.managerFirst(department,group.getKey(),unwatermarked||"CORE".equals(highest)));
                UUID route=(UUID)db.required("SELECT route_snapshot_id FROM workflow.instance WHERE id=?",flow).get("route_snapshot_id");
                db.update("INSERT INTO archive.borrow_request(id,batch_id,owner_department_id,applicant_id,workflow_instance_id,route_snapshot_id,approved_actions) VALUES(?,?,?,?,?,?,'{VIEW}')",request,batch,group.getKey(),context.actor().userId(),flow,route);
                for(var item:items) db.update("INSERT INTO archive.request_item(id,batch_id,request_id,record_id,record_revision_id,pdf_version_id,action,classification_snapshot,renewed_grant_id) VALUES(?,?,?,?,?,?,'VIEW',?,?)",UUID.randomUUID(),batch,request,Inputs.uuid(item.get("recordId")),Inputs.uuid(item.get("recordRevisionId")),Inputs.uuid(item.get("pdfVersionId")),item.get("classification"),Inputs.uuid(item.get("renewedGrantId")));
            }
            return batch(context.actor(),batch);
        },id->batch(context.actor(),id));
    }
    /** 每次决定与最终发权均重验申请人、固定对象及实际等级；审批时换版必须重新送审。 */
    @Override public void verify(Map<String,Object> instance) {
        if("PAPER".equals(WorkflowEngine.snapshot(instance).get("form"))){paper.verify(instance);return;}
        var snapshot=WorkflowEngine.snapshot(instance);var request=db.required("SELECT q.*,b.applicant_department_id FROM archive.borrow_request q JOIN archive.borrow_batch b ON b.id=q.batch_id WHERE q.id=? FOR UPDATE OF q",instance.get("business_id"));
        if(!"PENDING".equals(request.get("state"))) throw ApiFault.conflict("借阅申请已结束");
        db.required("SELECT id FROM iam.user_account WHERE id=? AND department_id=? AND status='ACTIVE'",request.get("applicant_id"),request.get("applicant_department_id"));
        for(var item:Inputs.objects(snapshot.get("items"),150)) {
            var row=authority.published(Inputs.uuid(item.get("recordRevisionId")),Inputs.uuid(item.get("pdfVersionId")));if(!row.get("id").equals(Inputs.uuid(item.get("recordId")))||!row.get("classification").equals(item.get("classification"))||rank((String)row.get("classification"))>rank((String)snapshot.get("highestClassification"))) throw ApiFault.conflict("原借阅清单或密级已变化，请重新送审");
            if(item.get("renewedGrantId")!=null) {
                var source=db.required("SELECT * FROM archive.access_grant WHERE id=? FOR SHARE",Inputs.uuid(item.get("renewedGrantId")));
                if(!Objects.equals(source.get("policy_version"),item.get("sourcePolicyVersion"))||!source.get("record_id").equals(row.get("id"))||!source.get("valid_until").toString().equals(item.get("previousUntil"))||db.count("SELECT CASE WHEN archive.borrow_proof_valid(?,?) THEN 1 ELSE 0 END",source.get("id"),instance.get("applicant_id"))!=1) throw ApiFault.conflict("原借阅来源已收紧或发生变化，不能沿旧申请续权");
            }
        }
    }
    /** 最后一个必要节点批准后分别发放具体版本动作授权；打印额度独立固定。 */
    @Override public void finish(Map<String,Object> instance,boolean approved,UUID approver) {
        if("PAPER".equals(WorkflowEngine.snapshot(instance).get("form"))){paper.finish(instance,approved,approver);return;}
        var snapshot=WorkflowEngine.snapshot(instance);UUID request=Inputs.uuid(snapshot.get("requestId")),batch=Inputs.uuid(snapshot.get("batchId"));
        if(approved) {
            verify(instance);var period=castMap(snapshot.get("period"));Instant effective=Instant.now(),latest=effective;int version=((Number)snapshot.get("calendarVersion")).intValue();
            for(var item:Inputs.objects(snapshot.get("items"),150)) {
                UUID parent=item.get("renewedGrantId")==null?null:Inputs.uuid(item.get("renewedGrantId"));
                Instant until=parent==null?calendar.until(version,period,effective):renewalUntil(version,period,effective,Instant.parse(item.get("previousUntil").toString()));if(until.isAfter(latest)) latest=until;
                db.update("INSERT INTO archive.access_grant(id,user_id,record_id,record_revision_id,pdf_version_id,action,source_type,source_request_id,approval_reference,valid_from,valid_until,parent_grant_id,approved_pages,approved_copies) VALUES(?,?,?,?,?,?,'BORROW',?,?,?,?,?,CASE WHEN ?::jsonb IS NULL THEN NULL ELSE ARRAY(SELECT jsonb_array_elements_text(?::jsonb)::integer) END,?)",UUID.randomUUID(),instance.get("applicant_id"),Inputs.uuid(item.get("recordId")),Inputs.uuid(item.get("recordRevisionId")),Inputs.uuid(item.get("pdfVersionId")),item.getOrDefault("action","VIEW"),request,"workflow:"+instance.get("id"),java.sql.Timestamp.from(effective),java.sql.Timestamp.from(until),parent,item.get("printPages")==null?null:Crypto.json(item.get("printPages")),item.get("printPages")==null?null:Crypto.json(item.get("printPages")),item.get("copies"));
            }
            db.update("UPDATE archive.borrow_request SET state='APPROVED',approved_until=? WHERE id=?",java.sql.Timestamp.from(latest),request);
        } else db.update("UPDATE archive.borrow_request SET state=? WHERE id=? AND state='PENDING'","WITHDRAWN".equals(instance.get("state"))?"WITHDRAWN":"REJECTED",request);
        // 同一父批次的最终状态按所有子单更新；批准子单不会因其他部门驳回而降级路线或删除批准。
        db.required("SELECT id FROM archive.borrow_batch WHERE id=? FOR UPDATE",batch);
        db.update("UPDATE archive.borrow_batch SET row_version=row_version+1,state=(SELECT CASE WHEN bool_and(state='APPROVED') THEN 'APPROVED' WHEN bool_and(state='REJECTED') THEN 'REJECTED' WHEN bool_and(state='WITHDRAWN') THEN 'WITHDRAWN' WHEN bool_and(state='PENDING') THEN 'PENDING' ELSE 'PARTIAL' END FROM archive.borrow_request WHERE batch_id=?) WHERE id=?",batch,batch);
        commands.event("BORROW_REQUEST",request,approved?"BORROW_APPROVED":"BORROW_ENDED");
    }
    /** 本人查看原申请，审批人仅取得其合法节点对应的部门子单。 */
    public Map<String,Object> batch(Actor actor,UUID id) {
        if("PAPER".equals(db.required("SELECT form FROM archive.borrow_batch WHERE id=?",id).get("form")))return paper.batch(actor,id);
        actor.require("ARCHIVE.ACCESS");var batch=db.required("SELECT * FROM archive.borrow_batch WHERE id=?",id);
        var requests=db.rows("SELECT q.*,w.state AS workflow_state,w.row_version,w.current_step_id,w.business_snapshot,CASE WHEN q.state<>'APPROVED' THEN 'UNAPPROVED' WHEN q.approved_until<=CURRENT_TIMESTAMP THEN 'EXPIRED' WHEN EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.source_request_id=q.id AND archive.grant_valid(g.id,q.applicant_id)) THEN 'ACTIVE' ELSE 'UNAVAILABLE' END AS usage_state FROM archive.borrow_request q JOIN workflow.instance w ON w.id=q.workflow_instance_id WHERE q.batch_id=? AND (q.applicant_id=? OR EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id AND s.assignee_id=? AND workflow.assignment_valid(s.assignment_type,s.assignment_ref,s.assignee_id))) ORDER BY q.id",id,actor.userId(),actor.userId());
        if(requests.isEmpty()) throw ApiFault.hidden();List<Map<String,Object>> items=new ArrayList<>();
        for(var request:requests) {var snapshot=WorkflowEngine.snapshot(request);for(var item:Inputs.objects(snapshot.get("items"),150)) {var projection=new LinkedHashMap<>(item);projection.put("requestId",request.get("id"));items.add(projection);}}
        // JSONB只在明确业务投影处解析，不能把JDBC包装对象直接交给浏览器。
        batch.put("requested_period",Crypto.JSON.readValue(batch.get("requested_period").toString(),Map.class));
        boolean pending=db.count("SELECT count(*) FROM archive.borrow_request WHERE batch_id=? AND state='PENDING'",id)>0;
        // 来源有效性一次聚合，避免每个部门子单额外查询一次正文授权。
        var sources=db.required("SELECT count(*) AS total,count(*) FILTER(WHERE archive.grant_valid(g.id,?)) AS valid FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=? AND q.state='APPROVED'",batch.get("applicant_id"),id);
        boolean renew=List.of("VIEW").equals(batch.get("actions"))&&actor.userId().equals(batch.get("applicant_id"))&&!pending&&Set.of("APPROVED","PARTIAL").contains(batch.get("state"))&&db.count("SELECT count(*) FROM archive.borrow_batch WHERE renewal_of_batch_id=? AND state IN ('PENDING','PARTIAL','APPROVED')",id)==0&&((Number)sources.get("total")).longValue()>0&&sources.get("total").equals(sources.get("valid"));
        return Sql.map("id",id,"batch",Sql.view(batch,"id,applicant_id,form,actions,purpose,parent_max_classification,item_count,calendar_version,requested_period,state,submitted_at,row_version,renewal_of_batch_id,renewal_reason"),"canRenew",renew,"renewals",db.rows("SELECT id,state,submitted_at FROM archive.borrow_batch WHERE renewal_of_batch_id=? ORDER BY submitted_at DESC LIMIT 20",id).stream().map(row->Sql.view(row,"id,state,submitted_at")).toList(),"requests",requests.stream().map(row->Sql.view(row,"id,owner_department_id,workflow_instance_id,state,approved_until,row_version,current_step_id,usage_state")).toList(),"items",items);
    }
    /** 子单必须位于调用人可见批次范围内，否则隐藏其存在。 */
    public Map<String,Object> request(Actor actor,UUID id) {var row=db.required("SELECT batch_id FROM archive.borrow_request WHERE id=?",id);var result=batch(actor,(UUID)row.get("batch_id"));@SuppressWarnings("unchecked") var requests=(List<Map<String,Object>>)result.get("requests");if(requests.stream().noneMatch(value->id.equals(value.get("id")))) throw ApiFault.hidden();return Sql.map("id",id,"detail",result);}
    /** 审批摘要沿用子单授权投影，不能读取其他部门拆分清单。 */
    @Override public Map<String,Object> view(Actor actor,Map<String,Object> instance) {
        var result=request(actor,(UUID)instance.get("business_id"));
        result.put("unwatermarked",Boolean.TRUE.equals(WorkflowEngine.snapshot(instance).get("unwatermarked")));
        return result;
    }
    /** 本人申请列表从真实父批次与子单进度派生，不制造第二套审批状态。 */
    public Map<String,Object> mine(Actor actor,Map<String,String> query) {
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"page","pageSize","state","businessType","keyword","form","submittedFrom","submittedTo");
        if(query.containsKey("businessType")) Inputs.choice(query.get("businessType"),"BORROW");
        // 状态条件与总数共用同一谓词，避免分页显示未经筛选的数量。
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        String where=" FROM archive.borrow_batch WHERE applicant_id=?";List<Object> args=new ArrayList<>(List.of(actor.userId()));
        if(query.containsKey("state")){where+=" AND state=?";args.add(Inputs.choice(query.get("state"),"PENDING","APPROVED","REJECTED","WITHDRAWN","PARTIAL","CLOSED"));}
        where+=new ListConditions(query,args)
            .contains("keyword","purpose").choice("form","form","ELECTRONIC","PAPER")
            .dates("submitted","submitted_at").sql();
        long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);
        return Sql.map("items",db.rows("SELECT id,form,purpose,state,parent_max_classification,item_count,submitted_at,renewal_of_batch_id,renewal_reason"+where+" ORDER BY submitted_at DESC,id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->Sql.view(row,"id,form,purpose,state,parent_max_classification,item_count,submitted_at,renewal_of_batch_id,renewal_reason")).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 等级排序只用于整批最高等级计算，未知值按保护失败处理。 */
    private int rank(String value) {return switch(value){case "GENERAL"->0;case "IMPORTANT"->1;case "CORE"->2;default->throw SystemFailure.CLASSIFICATION_INVALID.fault();};}
    /** 未到期从原截止继续计算，已在途到期从新批准计算；每次新授权最多30自然日且须真正延长。 */
    private Instant renewalUntil(int version,Map<String,Object> period,Instant effective,Instant previous) {
        Instant start=previous.isAfter(effective)?previous:effective,until=calendar.until(version,period,start);
        if(!until.isAfter(previous)||until.isAfter(effective.atZone(WorkCalendarService.ZONE).toLocalDate().plusDays(30).atTime(23,59,59).atZone(WorkCalendarService.ZONE).toInstant())) throw ApiFault.invalid("续借须延长原截止且新批准最长30自然日，请调整期限或临近到期再申请");return until;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> castMap(Object value) {return (Map<String,Object>)value;}
}
