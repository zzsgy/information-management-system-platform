package com.company.platform.intake;

import com.company.platform.*;
import com.company.platform.archive.*;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 扫描批次、原件交接及人工质量管理；接收成功永远不直接发布。 */
@Service
public class ScanIntake {
    /** 设备配置与档案业务各自使用原有事务和权限边界。 */
    private final Sql db;private final ArchiveAuthority authority;private final ArchiveCommands archive;private final Commands commands;private final IdentityService identity;private final ScanIdentity services;private final StorageBoundaries protection;private final StorageFiles files;
    public ScanIntake(Sql db,ArchiveAuthority authority,ArchiveCommands archive,Commands commands,IdentityService identity,ScanIdentity services,StorageBoundaries protection,StorageFiles files){this.db=db;this.authority=authority;this.archive=archive;this.commands=commands;this.identity=identity;this.services=services;this.protection=protection;this.files=files;}
    /** 配置设备只需要最小部门选择，不开放员工或档案详情。 */
    public Map<String,Object> options(Actor actor){identity.background(actor.sessionId()).require("PLATFORM.SCAN_DEVICES_MANAGE");return Sql.map("departments",db.rows("SELECT id,code,name FROM iam.department WHERE status='ACTIVE' ORDER BY code LIMIT 1000"));}
    /** IT 查询设备配置；档案员只能读取自己可维护部门的设备，不返回密钥引用。 */
    public Map<String,Object> devices(Actor actor){Actor live=identity.background(actor.sessionId());boolean admin=live.actions().contains("PLATFORM.SCAN_DEVICES_MANAGE");if(!admin)live.require("ARCHIVE.CATALOG_MANAGE");return Sql.map("items",db.rows("SELECT d.*,s.valid_until FROM archive.scan_device d JOIN iam.service_identity s ON s.id=d.service_identity_id WHERE ? OR EXISTS (SELECT 1 FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id JOIN iam.user_account u ON u.id=ur.user_id WHERE ur.user_id=? AND ur.module='ARCHIVE' AND ur.state='ACTIVE' AND r.status='ACTIVE' AND u.status='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP) AND (ur.role_code='SUPER_ADMIN' OR (ur.role_code='DEPARTMENT_ARCHIVIST' AND ur.department_id=d.department_id AND ur.department_id=u.department_id))) ORDER BY d.name,d.id LIMIT 1000",admin,live.userId()).stream().map(this::deviceView).toList());}
    /** 配置设备并绑定获准身份；自动新建身份的秘密通过私有部署目录交付，不在 API 输出。 */
    public Map<String,Object> device(Commands.Context context,UUID id,Map<String,Object> body){
        Inputs.only(body,"serviceIdentityId","departmentId","name","software","stagingLocation","allowedOutputNotes","state","reason");
        return commands.run(context,body,"SCAN_DEVICE",id==null?ApiController.COLLECTION:id,id==null?"CREATE_SCAN_DEVICE":"UPDATE_SCAN_DEVICE",()->{identity.background(context.actor().sessionId()).require("PLATFORM.SCAN_DEVICES_MANAGE");protection.requireWritable("PLATFORM","SCAN_CONFIG");},()->{
            if(id==null){UUID department=Inputs.uuid(body.get("departmentId"));db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE' FOR SHARE",department);String name=Inputs.text(body,"name",128);UUID service=body.get("serviceIdentityId")==null?services.provision(department,name):Inputs.uuid(body.get("serviceIdentityId"));var scope=services.active(service,true);if(!((List<?>)scope.get("department_scope")).contains(department))throw ApiFault.denied();UUID created=UUID.randomUUID();db.update("INSERT INTO archive.scan_device(id,service_identity_id,department_id,name,software,staging_location,allowed_output_notes,created_by) VALUES(?,?,?,?,?,?,?,?)",created,service,department,name,Inputs.text(body,"software",256),Inputs.text(body,"stagingLocation",512),Inputs.text(body,"allowedOutputNotes",2000),context.actor().userId());return deviceView(db.required("SELECT d.*,s.valid_until FROM archive.scan_device d JOIN iam.service_identity s ON s.id=d.service_identity_id WHERE d.id=?",created));}
            Inputs.only(body,"name","software","stagingLocation","allowedOutputNotes","state","reason");Inputs.text(body,"reason",1000);var prior=db.required("SELECT * FROM archive.scan_device WHERE id=? FOR UPDATE",id);Commands.version(context,prior);
            db.update("UPDATE archive.scan_device SET name=?,software=?,staging_location=?,allowed_output_notes=?,state=?,row_version=row_version+1 WHERE id=?",value(body,"name",prior,"name",128),value(body,"software",prior,"software",256),value(body,"stagingLocation",prior,"staging_location",512),value(body,"allowedOutputNotes",prior,"allowed_output_notes",2000),Inputs.choice(body.getOrDefault("state",prior.get("state")),"ACTIVE","DISABLED"),id);return deviceView(db.required("SELECT d.*,s.valid_until FROM archive.scan_device d JOIN iam.service_identity s ON s.id=d.service_identity_id WHERE d.id=?",id));
        },key->deviceView(db.required("SELECT d.*,s.valid_until FROM archive.scan_device d JOIN iam.service_identity s ON s.id=d.service_identity_id WHERE d.id=?",key)));
    }
    /** 创建固定清单，每批最多 50 件；重复外部键必须对应同一清单。 */
    public Map<String,Object> create(Commands.Context context,Map<String,Object> body){
        Inputs.only(body,"departmentId","deviceId","expectedCount","expectedPages","externalKey","items");UUID department=Inputs.uuid(body.get("departmentId")),device=Inputs.uuid(body.get("deviceId"));var items=Inputs.objects(body.get("items"),50);if(items.isEmpty()||Inputs.integer(body.get("expectedCount"),0,1,50)!=items.size())throw ApiFault.invalid("批次件数与原件清单不一致");String external=Inputs.text(body,"externalKey",128),digest=Crypto.hash(Crypto.json(body));
        return archive.run(context,body,"SCAN_BATCH",ApiController.COLLECTION,"CREATE_SCAN_BATCH",true,()->manage(context.actor(),department),()->{
            db.required("SELECT d.id FROM archive.scan_device d JOIN iam.service_identity s ON s.id=d.service_identity_id WHERE d.id=? AND d.department_id=? AND d.state='ACTIVE' AND s.status='ACTIVE' AND s.valid_until>CURRENT_TIMESTAMP FOR SHARE OF d,s",device,department);
            db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","scan-batch:"+device+":"+external);var prior=db.one("SELECT id,request_digest FROM archive.scan_batch WHERE device_id=? AND external_key=?",device,external);if(prior!=null){if(!Crypto.equal(digest,(String)prior.get("request_digest")))throw ApiFault.conflict("同一设备批次键不能改写清单");return detail(context.actor(),(UUID)prior.get("id"));}
            UUID batch=UUID.randomUUID();int pages=0;Set<UUID> records=new HashSet<>();
            for(var item:items){Inputs.only(item,"recordId","expectedPages","sourceCondition","attachments","specialHandling");UUID record=Inputs.uuid(item.get("recordId"));if(!records.add(record))throw ApiFault.invalid("清单内档案重复");var candidate=record(record,department,true);if(db.count("SELECT count(*) FROM archive.scan_item WHERE record_id=?",record)>0||db.count("SELECT count(*) FROM archive.record_revision WHERE id=? AND pdf_version_id IS NOT NULL",candidate.get("candidate_revision_id"))>0)throw ApiFault.conflict("该文件已有候选或扫描批次，请在原批次办理返工");pages+=Inputs.integer(item.get("expectedPages"),0,1,10000);Inputs.text(item,"sourceCondition",2000);Inputs.text(item,"attachments",2000);}
            if(pages!=Inputs.integer(body.get("expectedPages"),0,1,500000))throw ApiFault.invalid("批次总页数与清单不一致");
            db.update("INSERT INTO archive.scan_batch(id,department_id,device_id,external_key,request_digest,expected_count,expected_pages,created_by) VALUES(?,?,?,?,?,?,?,?)",batch,department,device,external,digest,items.size(),pages,context.actor().userId());
            for(var item:items)db.update("INSERT INTO archive.scan_item(id,batch_id,record_id,expected_pages,source_condition,attachments,special_handling) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),batch,Inputs.uuid(item.get("recordId")),Inputs.integer(item.get("expectedPages"),0,1,10000),item.get("sourceCondition"),item.get("attachments"),Boolean.TRUE.equals(item.get("specialHandling")));
            event(batch,null,"REGISTER",context.actor().userId(),null,Sql.map("externalKey",external,"count",items.size(),"pages",pages));return detail(context.actor(),batch);
        },key->detail(context.actor(),key));
    }
    /** 分页前约束到实际可管理部门，可直接查看原件未归还队列。 */
    public Map<String,Object> list(Actor actor,UUID department,Map<String,String> query){manage(actor,department);Inputs.only(query,"departmentId","page","pageSize","unreturned","paused","keyword","state","originalsState","createdFrom","createdTo");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);boolean pending=Boolean.parseBoolean(query.get("unreturned")),paused=Boolean.parseBoolean(query.get("paused"));String where=" WHERE department_id=? AND (NOT ? OR originals_state='OUT') AND (NOT ? OR paused)";var args=new ArrayList<Object>(List.of(department,pending,paused));
        where+=new ListConditions(query,args).contains("keyword","external_key").choice("state","state","OPEN","RECEIVING","QUALITY_REVIEW","READY","CLOSED")
            .choice("originalsState","originals_state","STORED","OUT","RETURNED").dates("created","created_at").sql();
        long total=db.count("SELECT count(*) FROM archive.scan_batch"+where,args.toArray());
        args.add(size);args.add((page-1)*size);
        return Sql.map("items",db.rows("SELECT * FROM archive.scan_batch"+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",args.toArray()).stream().map(this::batchView).toList(),"total",total);}
    /** 档案员批次详情含人工记录，设备端另用最小回执接口。 */
    public Map<String,Object> detail(Actor actor,UUID id){var batch=batch(id,false);manage(actor,(UUID)batch.get("department_id"));var view=batchView(batch);view.put("items",db.rows("SELECT i.*,r.file_no,r.title,v.pdf_version_id AS candidate_pdf_version_id,r.candidate_revision_id FROM archive.scan_item i JOIN archive.record r ON r.id=i.record_id LEFT JOIN archive.record_revision v ON v.id=r.candidate_revision_id WHERE i.batch_id=? ORDER BY r.file_no,i.id",id).stream().map(row->Sql.view(row,"id,record_id,file_no,title,expected_pages,source_condition,attachments,special_handling,state,generation,pdf_version_id,received_pages,quality_check_id,row_version,candidate_revision_id")).toList());view.put("events",db.rows("SELECT id,item_id,event_type,actor_id,actor_service_id,details,occurred_at FROM archive.scan_event WHERE batch_id=? ORDER BY occurred_at DESC,id LIMIT 100",id).stream().map(row->{row.put("details",Crypto.JSON.readValue(row.get("details").toString(),Map.class));return Sql.view(row,"id,item_id,event_type,actor_id,actor_service_id,details,occurred_at");}).toList());return view;}
    /** 原件事件分别控制批准职责、数量、暂停和归还；有异常先暂停，处理后显式恢复。 */
    public Map<String,Object> event(Commands.Context context,UUID id,Map<String,Object> body){
        Inputs.only(body,"eventType","fromPerson","toPerson","actualQuantity","condition","decision","reason");String type=Inputs.choice(body.get("eventType"),"TAKE_OUT","TEMPORARY_CUSTODY","RETURN","SPECIAL_APPROVAL","PAUSE","RESOLVE","RESUME","CLEANUP_APPROVAL","CLEANUP_COMPLETED","CLOSE");Inputs.text(body,"condition",2000);Inputs.text(body,"decision",2000);Inputs.text(body,"reason",1000);var initial=batch(id,false);
        return archive.run(context,body,"SCAN_BATCH",id,"SCAN_EVENT",true,()->manage(context.actor(),(UUID)initial.get("department_id")),()->{
            var batch=batch(id,true);Commands.version(context,batch);if(batch.get("state").equals("CLOSED"))throw ApiFault.conflict("批次已关闭，历史不能改写");int count=Inputs.integer(body.get("actualQuantity"),0,0,50);
            if(Set.of("SPECIAL_APPROVAL","CLEANUP_APPROVAL","RESUME").contains(type)){authority.requireSuper(context.actor(),"ARCHIVE.CONFIG_MANAGE");if(context.actor().userId().equals(batch.get("created_by")))throw ApiFault.denied();}
            if(Set.of("TAKE_OUT","TEMPORARY_CUSTODY","RETURN").contains(type)){Inputs.text(body,"fromPerson",128);Inputs.text(body,"toPerson",128);if(body.get("fromPerson").equals(body.get("toPerson")))throw ApiFault.invalid("交接双方不能相同");if(count!=((Number)batch.get("expected_count")).intValue())throw ApiFault.invalid("数量不符，请登记暂停异常，不能确认完整交接");}
            switch(type){
                case "TAKE_OUT" -> {if(!"STORED".equals(batch.get("originals_state"))||Boolean.TRUE.equals(batch.get("paused")))throw ApiFault.conflict("当前不能取出原件");db.update("UPDATE archive.scan_batch SET originals_state='OUT' WHERE id=?",id);}
                case "TEMPORARY_CUSTODY" -> {if(!"OUT".equals(batch.get("originals_state")))throw ApiFault.conflict("原件当前不在外借扫描状态");}
                case "RETURN" -> {if(!"OUT".equals(batch.get("originals_state")))throw ApiFault.conflict("原件未取出或已经归还");db.update("UPDATE archive.scan_batch SET originals_state='RETURNED' WHERE id=?",id);}
                case "SPECIAL_APPROVAL" -> db.update("UPDATE archive.scan_batch SET special_approved=true WHERE id=?",id);
                case "PAUSE" -> db.update("UPDATE archive.scan_batch SET paused=true WHERE id=?",id);
                case "RESOLVE" -> {if(!Boolean.TRUE.equals(batch.get("paused")))throw ApiFault.conflict("仅暂停异常允许登记处置");}
                case "RESUME" -> {if(!Boolean.TRUE.equals(batch.get("paused"))||db.count("SELECT count(*) FROM archive.scan_event WHERE batch_id=? AND event_type='RESOLVE' AND occurred_at>(SELECT max(occurred_at) FROM archive.scan_event WHERE batch_id=? AND event_type='PAUSE')",id,id)==0)throw ApiFault.conflict("请先登记本轮异常处置");db.update("UPDATE archive.scan_batch SET paused=false WHERE id=?",id);}
                case "CLEANUP_APPROVAL" -> {ready(batch);db.update("UPDATE archive.scan_batch SET cleanup_approved=true WHERE id=?",id);}
                case "CLEANUP_COMPLETED" -> {ready(batch);if(!Boolean.TRUE.equals(batch.get("cleanup_approved")))throw ApiFault.conflict("暂存清理尚未获批准");}
                case "CLOSE" -> {ready(batch);if(!"RETURNED".equals(batch.get("originals_state")))throw ApiFault.conflict("原件尚未归还");db.update("UPDATE archive.scan_batch SET state='CLOSED' WHERE id=?",id);}
            }
            event(id,null,type,context.actor().userId(),null,body);db.update("UPDATE archive.scan_batch SET row_version=row_version+1 WHERE id=?",id);return detail(context.actor(),id);
        },key->detail(context.actor(),key));
    }
    /** 待处理项可纠正绑定；旧上传轮次随纠正失效，已接收候选不能移挂到别的档案。 */
    public Map<String,Object> correct(Commands.Context context,UUID itemId,Map<String,Object> body){
        Inputs.only(body,"recordId","expectedPages","sourceCondition","reason");Inputs.text(body,"reason",1000);var initial=item(itemId);UUID id=(UUID)initial.get("batch_id");var initialBatch=batch(id,false);
        return archive.run(context,body,"SCAN_ITEM",itemId,"CORRECT_SCAN_ITEM",true,()->manage(context.actor(),(UUID)initialBatch.get("department_id")),()->{
            var batch=batch(id,true);var item=db.required("SELECT * FROM archive.scan_item WHERE id=? FOR UPDATE",itemId);Commands.version(context,item);
            if(!Set.of("WAITING","REWORK").contains(item.get("state"))||"CLOSED".equals(batch.get("state")))throw ApiFault.conflict("仅待接收或返工项目可以纠正");
            UUID target=Inputs.uuid(body.get("recordId"));boolean moving=!target.equals(item.get("record_id"));if(moving&&item.get("pdf_version_id")!=null)throw ApiFault.conflict("已接收候选不能移动到其它档案，请保留本批异常记录并走目录更正");
            var record=record(target,(UUID)batch.get("department_id"),true);if(moving&&(db.count("SELECT count(*) FROM archive.scan_item WHERE record_id=?",target)>0||db.count("SELECT count(*) FROM archive.record_revision WHERE id=? AND pdf_version_id IS NOT NULL",record.get("candidate_revision_id"))>0))throw ApiFault.conflict("纠正目标已有候选或扫描事项");
            int pages=Inputs.integer(body.get("expectedPages"),((Number)item.get("expected_pages")).intValue(),1,10000);
            db.update("UPDATE archive.scan_item SET record_id=?,expected_pages=?,source_condition=?,generation=generation+1,row_version=row_version+1 WHERE id=?",target,pages,Inputs.text(body,"sourceCondition",2000),itemId);
            event(id,itemId,"CORRECT",context.actor().userId(),null,Sql.map("before",Sql.view(item,"record_id,expected_pages,source_condition,generation"),"changes",body));
            db.update("UPDATE archive.scan_batch SET expected_pages=(SELECT sum(expected_pages) FROM archive.scan_item WHERE batch_id=?),row_version=row_version+1 WHERE id=?",id,id);return Sql.map("id",itemId,"batchId",id);
        },key->Sql.map("id",key,"batchId",id));
    }
    /** 人工质检固定候选全部页面，返工使旧上传失效；通过后仍需原有独立归档复核。 */
    public Map<String,Object> quality(Commands.Context context,UUID itemId,Map<String,Object> body){
        Inputs.only(body,"result","receivedPages","contentChecked","pageOrderChecked","condition","reason");String result=Inputs.choice(body.get("result"),"PASS","REWORK");Inputs.text(body,"condition",2000);if(result.equals("REWORK"))Inputs.text(body,"reason",1000);var initial=item(itemId);UUID id=(UUID)initial.get("batch_id");var initialBatch=batch(id,false);
        return archive.run(context,body,"SCAN_ITEM",itemId,"SCAN_QUALITY",true,()->manage(context.actor(),(UUID)initialBatch.get("department_id")),()->{
            var batch=batch(id,true);if(Boolean.TRUE.equals(batch.get("paused")))throw ApiFault.conflict("批次已暂停");var item=db.required("SELECT * FROM archive.scan_item WHERE id=? FOR UPDATE",itemId);Commands.version(context,item);if(!"RECEIVED".equals(item.get("state")))throw ApiFault.conflict("请先完成文件接收");var record=record((UUID)item.get("record_id"),(UUID)batch.get("department_id"),true);
            var pdf=db.required("SELECT p.*,v.id AS revision_id,o.storage_key,o.sha256,o.byte_size FROM archive.pdf_version p JOIN archive.record_revision v ON v.pdf_version_id=p.id JOIN storage.object o ON o.id=p.object_id WHERE p.id=? AND v.id=? AND p.review_state='CHECK_PASSED' AND o.state='READY'",item.get("pdf_version_id"),record.get("candidate_revision_id"));files.get().verifiedPrimary((String)pdf.get("storage_key"),((Number)pdf.get("byte_size")).longValue(),(String)pdf.get("sha256"));
            if(result.equals("PASS")){int pages=Inputs.integer(body.get("receivedPages"),0,1,10000);if(!Boolean.TRUE.equals(body.get("contentChecked"))||!Boolean.TRUE.equals(body.get("pageOrderChecked"))||pages!=((Number)pdf.get("page_count")).intValue()||pages!=((Number)item.get("expected_pages")).intValue())throw ApiFault.invalid("请逐件核对内容、页序和预期页数，存在差异应返工");UUID quality=UUID.randomUUID();db.update("INSERT INTO archive.quality_check(id,object_type,object_id,revision_id,pdf_version_id,checked_by,checked_page_count,result,notes) VALUES(?,'RECORD',?,?,?,?,?,'PASS',?)",quality,record.get("id"),pdf.get("revision_id"),pdf.get("id"),context.actor().userId(),pages,body.get("condition"));db.update("UPDATE archive.scan_item SET state='PASSED',quality_check_id=?,row_version=row_version+1 WHERE id=?",quality,itemId);}
            else {db.update("UPDATE archive.record SET row_version=row_version+1 WHERE id=?",record.get("id"));db.update("UPDATE archive.scan_item SET state='REWORK',generation=generation+1,row_version=row_version+1,quality_check_id=NULL WHERE id=?",itemId);}
            event(id,itemId,"QUALITY_"+result,context.actor().userId(),null,Sql.map("input",body,"pdfVersionId",pdf.get("id"),"generation",item.get("generation")));refresh(id);return Sql.map("id",itemId,"batchId",id,"state",result.equals("PASS")?"PASSED":"REWORK");
        },key->Sql.map("id",key,"batchId",id,"state",item(key).get("state")));
    }
    /** 设备只查询自己的获准批次和最小接收状态，不返回题名、位置、人员与正文。 */
    public Map<String,Object> receipt(UUID service,UUID id){var batch=serviceBatch(service,id,false,false);return Sql.map("id",id,"state",batch.get("state"),"paused",batch.get("paused"),"items",db.rows("SELECT i.id,i.state,i.generation,i.received_pages,r.receipt_key FROM archive.scan_item i LEFT JOIN archive.scan_receipt r ON r.pdf_version_id=i.pdf_version_id WHERE i.batch_id=? ORDER BY i.id",id).stream().map(row->Sql.view(row,"id,state,generation,received_pages,receipt_key")).toList());}
    /** 专用身份和设备、部门、批次必须同时匹配；读取旧回执不等于允许重新上传。 */
    public Map<String,Object> serviceBatch(UUID service,UUID id,boolean write,boolean lock){var identity=services.active(service,lock);var batch=batch(id,lock);var device=db.required("SELECT * FROM archive.scan_device WHERE id=? AND service_identity_id=?"+(lock?" FOR SHARE":""),batch.get("device_id"),service);if(!"ACTIVE".equals(device.get("state"))||!((List<?>)identity.get("department_scope")).contains(batch.get("department_id")))throw ApiFault.denied();if(write){protection.requireWritable("ARCHIVE","SCAN_UPLOAD");if(Boolean.TRUE.equals(batch.get("paused"))||Set.of("READY","CLOSED").contains(batch.get("state"))||!"OUT".equals(batch.get("originals_state")))throw ApiFault.conflict("批次暂停、已完成或原件不在作业状态");if(!Boolean.TRUE.equals(batch.get("special_approved"))&&db.count("SELECT count(*) FROM archive.scan_item WHERE batch_id=? AND special_handling",id)>0)throw ApiFault.conflict("特殊原件尚未获得作业批准");}return batch;}
    /** 绑定必须是同部门、具有实体原件的未发布混合档案。 */
    Map<String,Object> record(UUID id,UUID department,boolean lock){var row=db.required("SELECT * FROM archive.record WHERE id=? AND department_id=?"+(lock?" FOR UPDATE":""),id,department);if(!"DRAFT".equals(row.get("state"))||!"MIXED".equals(row.get("carrier_type")))throw ApiFault.conflict("扫描仅接收本部门未发布的混合载体档案");db.required("SELECT id FROM archive.physical_item WHERE record_id=?",id);return row;}
    /** 事务内追加事件，不能覆盖先前的人工结论。 */
    void event(UUID batch,UUID item,String type,UUID actor,UUID service,Map<String,Object> details){db.update("INSERT INTO archive.scan_event(id,batch_id,item_id,event_type,actor_id,actor_service_id,details) VALUES(?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),batch,item,type,actor,service,Crypto.json(details));}
    /** 批次状态由逐件结果聚合，不由浏览器直接赋值。 */
    void refresh(UUID id){db.update("UPDATE archive.scan_batch SET state=CASE WHEN NOT EXISTS(SELECT 1 FROM archive.scan_item WHERE batch_id=? AND state<>'PASSED') THEN 'READY' WHEN EXISTS(SELECT 1 FROM archive.scan_item WHERE batch_id=? AND state='RECEIVED') THEN 'QUALITY_REVIEW' ELSE 'RECEIVING' END,row_version=row_version+1 WHERE id=?",id,id,id);}
    /** 固定批次按调用阶段加锁。 */
    Map<String,Object> batch(UUID id,boolean lock){return db.required("SELECT * FROM archive.scan_batch WHERE id=?"+(lock?" FOR UPDATE":""),id);}
    /** 内部扫描明细读取不返回任何文件路径。 */
    Map<String,Object> item(UUID id){return db.required("SELECT * FROM archive.scan_item WHERE id=?",id);}
    /** 业务操作必须同时有当前档案管理能力和当前部门任职。 */
    private void manage(Actor actor,UUID department){authority.requireManage(identity.background(actor.sessionId()),department);}
    /** 清理和关闭只允许全部通过且无暂停，不把上传成功当作检查完成。 */
    private void ready(Map<String,Object> batch){if(!"READY".equals(batch.get("state"))||Boolean.TRUE.equals(batch.get("paused")))throw ApiFault.conflict("全部文件质量通过且无暂停后才能办理");}
    /** 设备 API 投影没有 credential_ref。 */
    private Map<String,Object> deviceView(Map<String,Object> row){return Sql.view(row,"id,service_identity_id,department_id,name,software,staging_location,allowed_output_notes,state,row_version,valid_until");}
    /** 批次汇总区分实体原件与电子检查。 */
    private Map<String,Object> batchView(Map<String,Object> row){return Sql.view(row,"id,department_id,device_id,external_key,expected_count,expected_pages,state,paused,originals_state,special_approved,cleanup_approved,row_version,created_by,created_at");}
    /** PATCH 省略字段保持已有值，显式空白仍需拒绝。 */
    private String value(Map<String,Object> body,String key,Map<String,Object> prior,String column,int limit){return body.containsKey(key)?Inputs.text(body,key,limit):(String)prior.get(column);}
}
