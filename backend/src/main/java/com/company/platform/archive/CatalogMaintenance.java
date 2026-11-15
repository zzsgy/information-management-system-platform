package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.*;
import com.company.platform.workflow.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 已发布目录的普通编辑、关键更正及撤下。候选与当前内容分离，历史原件和在途借还永不删除。 */
@Service
public class CatalogMaintenance implements WorkflowBusiness {
    /** 共用正式授权、短事务、固定审批、隔离解析及库外收紧账本。 */
    private final Sql db;private final ArchiveAuthority authority;private final ArchiveCommands commands;private final WorkflowEngine workflow;private final CatalogTemplates templates;private final StorageFiles files;private final ReadingRuntime runtime;private final SecurityLedger ledger;
    /** 更新字段白名单，不允许客户端指定SQL列名。 */
    private static final Map<String,String> COLUMNS=Map.ofEntries(Map.entry("title","title"),Map.entry("safeTitle","safe_title"),Map.entry("categoryCode","category_code"),Map.entry("ownerId","owner_id"),Map.entry("retentionCode","retention_code"),Map.entry("archiveDate","archive_date"),Map.entry("expiryDate","expiry_date"),Map.entry("keywords","keywords"),Map.entry("abstract","abstract"),Map.entry("notes","notes"),Map.entry("formedDate","formed_date"),Map.entry("templateVersionId","template_version_id"),Map.entry("extensionValues","extension_values"),Map.entry("departmentId","department_id"),Map.entry("classification","classification"),Map.entry("boxId","box_id"),Map.entry("itemNo","item_no"),Map.entry("fileNo","file_no"),Map.entry("boxNo","box_no"));
    /** 普通字段仅更改目录信息，不更换批准所绑定的关键修订。 */
    private static final Set<String> GENERAL=Set.of("keywords","abstract","notes","extensionValues");
    /** 注入当前授权、短事务及库外水位服务，避免恢复旧库后重启旧权限。 */
    public CatalogMaintenance(Sql db,ArchiveAuthority authority,ArchiveCommands commands,WorkflowEngine workflow,CatalogTemplates templates,StorageFiles files,ReadingRuntime runtime,SecurityLedger ledger){this.db=db;this.authority=authority;this.commands=commands;this.workflow=workflow;this.templates=templates;this.files=files;this.runtime=runtime;this.ledger=ledger;}
    /** 固定业务标识，不接受客户端自定义审批类型。 */
    @Override public String type(){return "CATALOG_CHANGE";}
    /** 维护列表先按现任部门过滤，再匹配基础字段和模板明确允许检索的扩展字段。 */
    public Map<String,Object> list(Actor actor,String type,Map<String,String> query){actor.require("ARCHIVE.CATALOG_MANAGE");Inputs.only(query,"page","pageSize","keyword","state");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String keyword=query.getOrDefault("keyword","");if(keyword.length()>100)throw ApiFault.invalid("查询条件过长");String match="%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%",number=type.equals("BOX")?"box_no":"file_no";String where=" FROM "+table(type)+" o JOIN archive.directory_template t ON t.id=o.template_version_id WHERE archive.catalog_manager(?,o.department_id) AND (concat_ws(' ',o.title,o."+number+",array_to_string(o.keywords,' '),o.abstract) ILIKE ? OR EXISTS(SELECT 1 FROM jsonb_array_elements(t.fields) f WHERE f->>'searchable'='true' AND f->>'enabled'='true' AND o.extension_values->>(f->>'code') ILIKE ?))";var args=new ArrayList<Object>(List.of(actor.userId(),match,match));
        where+=new ListConditions(query,args).choice("state","o.state","DRAFT","PENDING_REVIEW","PUBLISHED","WITHDRAWN").sql();
        long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);return Sql.map("items",db.rows("SELECT o.*,t.fields AS template_fields"+where+" ORDER BY o.created_at DESC,o.id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->{var summary=Sql.view(row,"id,title,"+number+",state,row_version,department_id");summary.put("displayFields",displayFields(row));return summary;}).toList(),"total",total,"page",page,"pageSize",size);}
    /** 列表仅展示模板开启的字段；管理编辑和审批仍保留停用字段的完整历史。 */
    private List<Map<String,Object>> displayFields(Map<String,Object> row){
        var values=CatalogTemplates.map(row.get("extension_values"));List<Map<String,Object>> result=new ArrayList<>();
        for(var field:CatalogTemplates.definitions(CatalogTemplates.json(row.get("template_fields"))))if(Boolean.TRUE.equals(field.get("enabled"))&&Boolean.TRUE.equals(field.get("visible"))&&values.get(field.get("code"))!=null)result.add(Sql.map("name",field.get("name"),"value",values.get(field.get("code"))));return result;
    }
    /** 管理详情包含历史模板、当前元数据和未完更正；每次按实际部门鉴权。 */
    public Map<String,Object> detail(Actor actor,String type,UUID id){var row=object(type,id);authority.requireManage(actor,(UUID)row.get("department_id"));var result=snapshot(row,type);result.put("template",templates.view((UUID)row.get("template_version_id")));result.put("pendingChange",db.one("SELECT id,state,row_version,workflow_id FROM archive.catalog_change WHERE object_type=? AND object_id=? AND state IN ('DRAFT','PENDING')",type,id));if(type.equals("BOX")&&row.get("state").equals("DRAFT"))result.put("draftDeletion",draftDeletionSummary(id));return result;}
    /** 历史记录分页且与当前维护权绑定，不借历史题名暴露已移出范围的资料。 */
    public Map<String,Object> revisions(Actor actor,String type,UUID id,Map<String,String> query){detail(actor,type,id);Inputs.only(query,"page","pageSize");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String table=type.equals("BOX")?"archive.box_revision":"archive.record_revision",key=type.equals("BOX")?"box_id":"record_id";String sql=type.equals("BOX")?"SELECT v.id,v.revision_no,v.created_at,NULL::integer AS pdf_version_no,NULL::text AS review_state FROM archive.box_revision v WHERE v.box_id=?":"SELECT v.id,v.revision_no,v.created_at,p.version_no AS pdf_version_no,p.review_state FROM archive.record_revision v LEFT JOIN archive.pdf_version p ON p.id=v.pdf_version_id WHERE v.record_id=?";return Sql.map("items",db.rows(sql+" ORDER BY v.revision_no DESC LIMIT ? OFFSET ?",id,size,(page-1)*size).stream().map(r->Sql.view(r,"id,revision_no,created_at,pdf_version_no,review_state")).toList(),"total",db.count("SELECT count(*) FROM "+table+" WHERE "+key+"=?",id),"page",page,"pageSize",size);}
    /** 普通字段前后值与关键更正流程按同一历史入口分页。 */
    public Map<String,Object> history(Actor actor,String type,UUID id,Map<String,String> query){detail(actor,type,id);Inputs.only(query,"page","pageSize");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);return Sql.map("items",db.rows("SELECT id,reason,state,created_at FROM archive.catalog_change WHERE object_type=? AND object_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",type,id,size,(page-1)*size).stream().map(r->Sql.view(r,"id,reason,state,created_at")).toList(),"total",db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type=? AND object_id=?",type,id),"page",page,"pageSize",size);}
    /** 本人或当前维护范围的更正事项；流程审核者通过当前节点读取固定快照。 */
    public Map<String,Object> change(Actor actor,UUID id){var row=changeRow(id);var current=object((String)row.get("object_type"),(UUID)row.get("object_id"));authority.requireManage(actor,(UUID)current.get("department_id"));return view(id);}
    /** 草稿编辑沿用目录校验并追加不可变修订；已发布资料仍走更正流程。 */
    public Map<String,Object> updateDraft(Commands.Context context,String type,UUID id,Map<String,Object> body){
        Inputs.only(body,"changes","reason");String reason=Inputs.text(body,"reason",2000);
        var patch=CatalogTemplates.map(body.get("changes"));
        Set<String> allowed=Set.of("title","safeTitle","categoryCode","ownerId","retentionCode","archiveDate","keywords","abstract","notes","formedDate","templateVersionId","extensionValues","itemNo");
        if(patch.isEmpty()||!allowed.containsAll(patch.keySet())||(type.equals("BOX")&&patch.containsKey("itemNo")))throw ApiFault.invalid("请修改可编辑的草稿字段，编号、部门、密级和所属盒保持登记时的归属");
        return commands.run(context,body,scope(type),id,"UPDATE_DRAFT",true,()->authority.requireManage(context.actor(),(UUID)object(type,id).get("department_id")),()->{
            lockObjects(type,id,Map.of());var row=editableDraft(context,type,id);var before=snapshot(row,type);var next=new LinkedHashMap<>(before);next.putAll(patch);normalize(context.actor(),type,next,before);update(type,id,next,context.actor().userId());
            UUID revision=UUID.randomUUID();String revisionTable=type.equals("BOX")?"archive.box_revision":"archive.record_revision",foreignKey=type.equals("BOX")?"box_id":"record_id";
            int number=(int)db.count("SELECT COALESCE(max(revision_no),0)+1 FROM "+revisionTable+" WHERE "+foreignKey+"=?",id);
            if(type.equals("BOX"))db.update("INSERT INTO archive.box_revision(id,box_id,revision_no,metadata_snapshot,template_version_id,created_by) VALUES(?,?,?,?::jsonb,?,?)",revision,id,number,Crypto.json(next),Inputs.uuid(next.get("templateVersionId")),context.actor().userId());
            else{var prior=db.required("SELECT pdf_version_id FROM archive.record_revision WHERE id=?",row.get("candidate_revision_id"));db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,pdf_version_id,metadata_snapshot,template_version_id,created_by) VALUES(?,?,?,?,?::jsonb,?,?)",revision,id,number,prior.get("pdf_version_id"),Crypto.json(next),Inputs.uuid(next.get("templateVersionId")),context.actor().userId());}
            db.update("UPDATE "+table(type)+" SET candidate_revision_id=? WHERE id=?",revision,id);draftHistory(context.actor(),type,id,before,next,reason,"GENERAL");return detail(context.actor(),type,id);
        },key->detail(context.actor(),type,key));
    }
    /** 草稿盒及盒内草稿在同一事务撤回；已归档、在审资料仍走原流程，原件和历史不清除。 */
    public Map<String,Object> deleteDraft(Commands.Context context,String type,UUID id,Map<String,Object> body){
        Inputs.only(body,"reason");String reason=Inputs.text(body,"reason",2000);
        return commands.run(context,body,scope(type),id,"DELETE_DRAFT",true,()->authority.requireManage(context.actor(),(UUID)object(type,id).get("department_id")),()->{
            lockObjects(type,id,Map.of());var row=editableDraft(context,type,id);
            if(type.equals("BOX"))for(var record:draftBoxRecords(id))withdrawDraft(context.actor(),"RECORD",record,reason);
            withdrawDraft(context.actor(),type,row,reason);return detail(context.actor(),type,id);
        },key->detail(context.actor(),type,key));
    }
    /** 一次聚合取得确认弹窗的范围；附件按当前条目计数，不把同一文件的历史版本重复计入。 */
    private Map<String,Object> draftDeletionSummary(UUID box){
        var summary=db.required("SELECT count(*) FILTER(WHERE r.state='DRAFT' AND r.current_revision_id IS NULL) AS draft_count,count(*) FILTER(WHERE r.state='DRAFT' AND r.current_revision_id IS NULL AND (r.electronic_version_id IS NOT NULL OR v.pdf_version_id IS NOT NULL)) AS attachment_count,count(*) FILTER(WHERE r.state<>'DRAFT' OR r.current_revision_id IS NOT NULL) AS blocked_count FROM archive.record r LEFT JOIN archive.record_revision v ON v.id=r.candidate_revision_id WHERE r.box_id=? AND r.state<>'WITHDRAWN'",box);
        return Sql.view(summary,"draft_count,attachment_count,blocked_count");
    }
    /** 先检查全部成员再写入，任何已归档或原流程未结束的成员都不能被草稿删除绕过。 */
    private List<Map<String,Object>> draftBoxRecords(UUID box){
        var records=db.rows("SELECT * FROM archive.record WHERE box_id=? AND state<>'WITHDRAWN' ORDER BY id",box);
        if(records.stream().anyMatch(row->!row.get("state").equals("DRAFT")||row.get("current_revision_id")!=null))throw ApiFault.conflict("盒内含已归档或正在归档审批的资料，请按原审批流程办理撤下或撤回后再删除草稿盒");
        if(db.count("SELECT count(*) FROM archive.catalog_change c JOIN archive.record r ON r.id=c.object_id WHERE c.object_type='RECORD' AND r.box_id=? AND r.state<>'WITHDRAWN' AND c.state IN ('DRAFT','PENDING')",box)>0)throw ApiFault.conflict("盒内文件有未完成的目录更正，请先在原流程办理");
        if(db.count("SELECT count(*) FROM archive.scan_item s JOIN archive.record r ON r.id=s.record_id WHERE r.box_id=? AND r.state<>'WITHDRAWN'",box)>0)throw ApiFault.conflict("盒内文件已有扫描交接，请先在扫描作业中处理");
        return records;
    }
    /** 只撤回登记状态并追加每条记录的前后快照，不修改附件版本或存储引用。 */
    private void withdrawDraft(Actor actor,String type,Map<String,Object> row,String reason){
        UUID id=(UUID)row.get("id");var before=snapshot(row,type);var next=new LinkedHashMap<>(before);next.put("state","WITHDRAWN");next.put("rowVersion",((Number)row.get("row_version")).intValue()+1);
        db.update("UPDATE "+table(type)+" SET state='WITHDRAWN',row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        draftHistory(actor,type,id,before,next,reason,"WITHDRAWAL");
    }
    /** 扫描交接及归档复核须先在原流程处理，防止目录维护绕过已冻结责任。 */
    private Map<String,Object> editableDraft(Commands.Context context,String type,UUID id){
        var row=object(type,id);Commands.version(context,row);if(!"DRAFT".equals(row.get("state"))||row.get("current_revision_id")!=null)throw ApiFault.conflict("仅未发布草稿可直接修改或删除；已归档资料请办理更正或撤下");
        if(db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type=? AND object_id=? AND state IN ('DRAFT','PENDING')",type,id)>0)throw ApiFault.conflict("请先处理未完成的目录更正");
        if(type.equals("RECORD")){var parent=object("BOX",(UUID)row.get("box_id"));if(!Set.of("DRAFT","PUBLISHED").contains(parent.get("state")))throw ApiFault.conflict("所属盒正在复核或已撤下，暂不能修改盒内文件");if(db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type='BOX' AND object_id=? AND state IN ('DRAFT','PENDING')",row.get("box_id"))>0)throw ApiFault.conflict("所属盒正在更正，请先处理盒的更正事项");if(db.count("SELECT count(*) FROM archive.scan_item WHERE record_id=?",id)>0)throw ApiFault.conflict("该文件已有扫描交接，请在扫描作业中处理");}
        return row;
    }
    private void draftHistory(Actor actor,String type,UUID id,Map<String,Object> before,Map<String,Object> next,String reason,String kind){
        db.update("INSERT INTO archive.catalog_change(id,object_type,object_id,department_id,kind,state,before_snapshot,after_snapshot,base_version,reason,created_by,applied_at) VALUES(?,?,?,?,?,'APPLIED',?::jsonb,?::jsonb,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID(),type,id,Inputs.uuid(before.get("departmentId")),kind,Crypto.json(before),Crypto.json(next),before.get("rowVersion"),reason,actor.userId());
    }
    /** 创建固定候选；新PDF在事务外检测解析，提交时再次验证上传归属、对象版本与实际权限。 */
    public Map<String,Object> create(Commands.Context context,String type,UUID id,Map<String,Object> body){
        Inputs.only(body,"changes","reason","kind","uploadSessionId","source");String kind=Inputs.choice(body.getOrDefault("kind","CORRECTION"),"CORRECTION","WITHDRAWAL"),reason=Inputs.text(body,"reason",2000);
        Map<String,Object> patch=body.get("changes")==null?Map.of():CatalogTemplates.map(body.get("changes"));if(patch.size()>COLUMNS.size())throw ApiFault.invalid("目录更正字段过多");for(String field:patch.keySet())if(!COLUMNS.containsKey(field)||field.equals("expiryDate"))throw ApiFault.invalid("字段不能直接更正："+field);
        var before=object(type,id);authority.requireManage(context.actor(),(UUID)before.get("department_id"));UUID upload=body.get("uploadSessionId")==null?null:Inputs.uuid(body.get("uploadSessionId"));Map<String,Object> file=null;int pageCount=0;
        if(upload!=null){if(!type.equals("RECORD")||"PAPER".equals(before.get("carrier_type"))||kind.equals("WITHDRAWAL"))throw ApiFault.invalid("此对象不支持PDF更正");file=upload(context.actor(),id,upload);var store=files.get();var pdf=file;pageCount=runtime.withClient(client->client.pageCount(store.verifiedPrimary((String)pdf.get("storage_key"),((Number)pdf.get("byte_size")).longValue(),(String)pdf.get("sha256")),(String)pdf.get("sha256")));Inputs.text(body,"source",64);}
        if(kind.equals("WITHDRAWAL")&&!patch.isEmpty())throw ApiFault.invalid("撤下申请不能同时更改目录");if(kind.equals("CORRECTION")&&patch.isEmpty()&&upload==null)throw ApiFault.invalid("请填写更正内容");
        final Map<String,Object> candidate=file;final int pages=pageCount;
        return commands.run(context,body,scope(type),id,"CREATE_CATALOG_CHANGE",true,()->authority.requireManage(context.actor(),(UUID)object(type,id).get("department_id")),()->{
            lockObjects(type,id,patch);var row=object(type,id);Commands.version(context,row);if(!"PUBLISHED".equals(row.get("state")))throw ApiFault.conflict("仅已发布档案可办理更正或撤下");if(db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type=? AND object_id=? AND state IN ('DRAFT','PENDING')",type,id)>0)throw ApiFault.conflict("已有未完成更正，请先办理或撤回");
            Map<String,Object> old=snapshot(row,type),next=new LinkedHashMap<>(old);next.putAll(patch);normalize(context.actor(),type,next,old);if(type.equals("RECORD")&&db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type='BOX' AND object_id=? AND state IN ('DRAFT','PENDING')",row.get("box_id"))>0)throw ApiFault.conflict("所属盒正在更正");
            List<Map<String,Object>> members=type.equals("BOX")?members(id):List.of();if(type.equals("BOX")&&db.count("SELECT count(*) FROM archive.catalog_change c JOIN archive.record r ON r.id=c.object_id WHERE c.object_type='RECORD' AND r.box_id=? AND c.state IN ('DRAFT','PENDING')",id)>0)throw ApiFault.conflict("盒内文件有未完成更正");
            boolean general=kind.equals("CORRECTION")&&upload==null&&GENERAL.containsAll(patch.keySet())&&!templates.critical(Inputs.uuid(next.get("templateVersionId")),CatalogTemplates.map(old.get("extensionValues")),CatalogTemplates.map(next.get("extensionValues")));
            UUID change=UUID.randomUUID(),pdf=null,revision=null;
            if(candidate!=null){upload(context.actor(),id,upload);pdf=UUID.randomUUID();revision=UUID.randomUUID();db.update("INSERT INTO archive.pdf_version(id,record_id,version_no,object_id,source,correction_reason,created_by,page_count) VALUES(?,?,(SELECT COALESCE(max(version_no),0)+1 FROM archive.pdf_version WHERE record_id=?),?,?,?,?,?)",pdf,id,id,candidate.get("id"),body.get("source"),reason,context.actor().userId(),pages);db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,pdf_version_id,metadata_snapshot,template_version_id,created_by) VALUES(?,?,(SELECT COALESCE(max(revision_no),0)+1 FROM archive.record_revision WHERE record_id=?),?,?::jsonb,?,?)",revision,id,id,pdf,Crypto.json(next),Inputs.uuid(next.get("templateVersionId")),context.actor().userId());db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'ARCHIVE_PDF',?,'BUSINESS')",UUID.randomUUID(),candidate.get("id"),pdf);}
            db.update("INSERT INTO archive.catalog_change(id,object_type,object_id,department_id,kind,state,before_snapshot,after_snapshot,member_snapshot,base_version,candidate_pdf_id,candidate_revision_id,reason,created_by) VALUES(?,?,?,?,?,'DRAFT',?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?)",change,type,id,row.get("department_id"),general?"GENERAL":kind,Crypto.json(old),Crypto.json(next),Crypto.json(members),row.get("row_version"),pdf,revision,reason,context.actor().userId());
            if(general){update(type,id,next,context.actor().userId());db.update("UPDATE archive.catalog_change SET state='APPLIED',applied_at=CURRENT_TIMESTAMP WHERE id=?",change);notifyManagers(change,(UUID)row.get("department_id"));}return view(change);
        },this::view);
    }
    /** 候选PDF人工逐页确认；真实页数、操作者、固定候选都由服务端核对。 */
    public Map<String,Object> quality(Commands.Context context,UUID id,Map<String,Object> body){Inputs.only(body,"checkedPageCount","confirmed","notes");String notes=Inputs.text(body,"notes",2000);return commands.run(context,body,"ARCHIVE_REVISION",id,"CONFIRM_CATALOG_QUALITY",true,()->requireCreator(context.actor(),id),()->{var c=db.required("SELECT * FROM archive.catalog_change WHERE id=? FOR UPDATE",id);Commands.version(context,c);if(!"DRAFT".equals(c.get("state"))||c.get("candidate_pdf_id")==null||!Boolean.TRUE.equals(body.get("confirmed")))throw ApiFault.conflict("当前候选不能确认");long pages=db.count("SELECT page_count FROM archive.pdf_version WHERE id=?",c.get("candidate_pdf_id"));if(Inputs.integer(body.get("checkedPageCount"),0,1,10000)!=pages)throw ApiFault.invalid("请核验候选PDF的全部页数");db.update("UPDATE archive.catalog_change SET quality_confirmed_at=CURRENT_TIMESTAMP,quality_notes=?,row_version=row_version+1 WHERE id=?",notes,id);return view(id);},this::view);}
    /** 送审固定范围，撤下由独立档案超级管理员复核；关键更正经过所属部门负责人及超级管理员。 */
    public Map<String,Object> submit(Commands.Context context,UUID id,Map<String,Object> body){Inputs.only(body);return commands.run(context,body,"ARCHIVE_REVISION",id,"SUBMIT_CATALOG_CHANGE",true,()->requireCreator(context.actor(),id),()->{var c=db.required("SELECT * FROM archive.catalog_change WHERE id=? FOR UPDATE",id);Commands.version(context,c);if(!"DRAFT".equals(c.get("state")))throw ApiFault.conflict("事项已送审或已结束");verifyChange(c);if(c.get("candidate_pdf_id")!=null&&c.get("quality_confirmed_at")==null)throw ApiFault.invalid("先完成候选PDF逐页质量确认");var next=CatalogTemplates.map(c.get("after_snapshot"));var route=new ArrayList<FixedBorrowRoute.Duty>();if(!"WITHDRAWAL".equals(c.get("kind")))route.add(new FixedBorrowRoute.Duty("DEPARTMENT_MANAGER",(UUID)c.get("department_id")));route.add(new FixedBorrowRoute.Duty("ARCHIVE_ADMIN",null));UUID flow=workflow.create(context.actor(),type(),id,null,strictest((String)next.get("classification"),(String)CatalogTemplates.map(c.get("before_snapshot")).get("classification")),0,Sql.map("objectType",c.get("object_type"),"objectId",c.get("object_id"),"departmentId",c.get("department_id"),"changeId",id,"kind",c.get("kind"),"title",next.get("title"),"reason",c.get("reason"),"pdfVersionId",c.get("candidate_pdf_id")),route);db.update("UPDATE archive.catalog_change SET state='PENDING',workflow_id=?,row_version=row_version+1 WHERE id=?",flow,id);return view(id);},this::view);}
    /** 未审批完可由提交人撤回；先锁流程再锁事项，与审批保持相同顺序，避免互等；已有决定保留。 */
    public Map<String,Object> cancel(Commands.Context context,UUID id,Map<String,Object> body){Inputs.only(body,"reason");Inputs.text(body,"reason",1000);return commands.run(context,body,"ARCHIVE_REVISION",id,"CANCEL_CATALOG_CHANGE",true,()->requireCreator(context.actor(),id),()->{var initial=changeRow(id);if(initial.get("workflow_id")!=null)db.required("SELECT id FROM workflow.instance WHERE id=? FOR UPDATE",initial.get("workflow_id"));var c=db.required("SELECT * FROM archive.catalog_change WHERE id=? FOR UPDATE",id);Commands.version(context,c);if(!Set.of("DRAFT","PENDING").contains(c.get("state")))throw ApiFault.conflict("事项已经结束");if(c.get("workflow_id")!=null)db.update("UPDATE workflow.instance SET state='WITHDRAWN',current_step_id=NULL,row_version=row_version+1 WHERE id=? AND state='PENDING'",c.get("workflow_id"));db.update("UPDATE archive.catalog_change SET state='WITHDRAWN',row_version=row_version+1 WHERE id=?",id);return view(id);},this::view);}
    /** 每一个批准节点都重新锁定原对象和固定成员，原对象变化后旧申请不能生效。 */
    @Override public void verify(Map<String,Object> instance){var c=changeRow((UUID)instance.get("business_id"));if(!"PENDING".equals(c.get("state"))||!instance.get("id").equals(c.get("workflow_id")))throw ApiFault.conflict("更正申请已失效");verifyChange(c);}
    /** 最终复核原子切换版本；撤下保留物理占用、归还复核及所有原件引用。 */
    @Override public void finish(Map<String,Object> instance,boolean approved,UUID approver){UUID id=(UUID)instance.get("business_id");var c=db.required("SELECT * FROM archive.catalog_change WHERE id=? FOR UPDATE",id);if(!approved){db.update("UPDATE archive.catalog_change SET state=?,row_version=row_version+1 WHERE id=?","WITHDRAWN".equals(instance.get("state"))?"WITHDRAWN":"REJECTED",id);return;}verify(instance);
        String type=(String)c.get("object_type");UUID object=(UUID)c.get("object_id");Map<String,Object> next=CatalogTemplates.map(c.get("after_snapshot"));
        if("WITHDRAWAL".equals(c.get("kind"))){db.update("UPDATE "+table(type)+" SET state='WITHDRAWN',row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",object);}
        else {update(type,object,next,approver);UUID pdf=c.get("candidate_pdf_id")==null?nullable(next.get("currentPdfVersionId")):(UUID)c.get("candidate_pdf_id");if(c.get("candidate_pdf_id")!=null)db.update("UPDATE archive.pdf_version SET review_state='APPROVED' WHERE id=?",pdf);
            publishRevision(type,object,next,pdf,approver);
            if(type.equals("BOX")){
        var old=CatalogTemplates.map(c.get("before_snapshot"));if(!Objects.equals(old.get("departmentId"),next.get("departmentId"))||!Objects.equals(old.get("classification"),next.get("classification"))){for(var m:members(object)){UUID record=(UUID)m.get("id");var metadata=snapshot(object("RECORD",record),"RECORD");metadata.put("departmentId",next.get("departmentId"));if(!Objects.equals(old.get("departmentId"),next.get("departmentId")))metadata.put("ownerId",next.get("ownerId"));metadata.put("classification",next.get("classification"));update("RECORD",record,metadata,approver);publishRevision("RECORD",record,metadata,nullable(metadata.get("currentPdfVersionId")),approver);}}}
            else db.update("UPDATE archive.physical_item SET box_id=? WHERE record_id=?",Inputs.uuid(next.get("boxId")),object);
        }
        db.update("UPDATE archive.catalog_change SET state='APPLIED',applied_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=?",id);notifyManagers(id,Inputs.uuid(next.get("departmentId")));
        // 库外水位先落盘；恢复到更正/撤下之前的库时拒绝访问，不复活旧内容。失败回滚时同样保守拒绝。
        var epoch=db.required("UPDATE platform_meta.instance SET catalog_epoch=catalog_epoch+1 RETURNING id,catalog_epoch");ledger.restrict((UUID)epoch.get("id"),((Number)epoch.get("catalog_epoch")).intValue(),"CATALOG_SAFETY_CHANGE",id);
    }
    /** 当前复核节点取得固定前后差异，原件仍通过单独的水印阅读接口。 */
    @Override public Map<String,Object> view(Actor actor,Map<String,Object> instance){
        UUID id=(UUID)instance.get("business_id");
        var result=Sql.map("snapshot",WorkflowEngine.snapshot(instance),"change",view(id));
        if("PENDING".equals(instance.get("state")))result.put("approvalBlockReason",approvalBlockReason(changeRow(id)));
        return result;
    }

    /** 发起人或交接后的经办人必须仍有原部门和目标部门的目录维护权。 */
    private String approvalBlockReason(Map<String,Object> change){
        var target=Inputs.uuid(CatalogTemplates.map(change.get("after_snapshot")).get("departmentId"));
        boolean permitted=db.count("SELECT CASE WHEN archive.catalog_manager(?,?) AND archive.catalog_manager(?,?) THEN 1 ELSE 0 END",
            custodian(change),change.get("department_id"),custodian(change),target)==1;
        return permitted?null:"该更正事项的经办人已无相关部门的档案维护权限。可驳回此单；如确需更正，请由有权限的档案员重新发起。";
    }
    /** 候选阅读不获得普通下载/打印权；送审后只允许申请人及当前合法复核人。 */
    public Map<String,Object> review(Actor actor,UUID id,UUID pdf){var c=changeRow(id);actor.require("ARCHIVE.ACCESS");if(!Objects.equals(pdf,c.get("candidate_pdf_id")))throw ApiFault.hidden();if("DRAFT".equals(c.get("state")))requireCreator(actor,id);else if("PENDING".equals(c.get("state"))){var flow=db.required("SELECT w.*,s.assignee_id,workflow.assignment_valid(s.assignment_type,s.assignment_ref,s.assignee_id) AS legal FROM workflow.instance w JOIN workflow.step s ON s.id=w.current_step_id WHERE w.id=? AND w.state='PENDING' AND s.state='PENDING'",c.get("workflow_id"));if(actor.userId().equals(custodian(c)))requireCreator(actor,id);else if(!actor.userId().equals(flow.get("assignee_id"))||!Boolean.TRUE.equals(flow.get("legal")))throw ApiFault.denied();}else throw ApiFault.hidden();
        var row=object("RECORD",(UUID)c.get("object_id"));var data=db.required("SELECT p.object_id,o.storage_key,o.sha256,o.byte_size FROM archive.pdf_version p JOIN storage.object o ON o.id=p.object_id WHERE p.id=? AND o.state='READY'",pdf);row.putAll(data);row.put("revision_id",c.get("candidate_revision_id"));row.put("sourceVersion",c.get("row_version"));return row;}
    /** 保管清单仅向现任目录管理员显示，分页在数据库完成，永久档案不会进入到期清单。 */
    public Map<String,Object> retention(Actor actor,Map<String,String> query){actor.require("ARCHIVE.CATALOG_MANAGE");Inputs.only(query,"page","pageSize");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String source=" FROM (SELECT 'BOX' AS object_type,id,box_no AS number,title,department_id,expiry_date,state FROM archive.box UNION ALL SELECT 'RECORD',r.id,r.file_no,r.title,r.department_id,r.expiry_date,CASE WHEN b.state='PUBLISHED' THEN r.state ELSE b.state END FROM archive.record r JOIN archive.box b ON b.id=r.box_id) o WHERE state='PUBLISHED' AND expiry_date<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date+30 AND archive.catalog_manager(?,department_id)";return Sql.map("items",db.rows("SELECT *"+source+" ORDER BY expiry_date,id LIMIT ? OFFSET ?",actor.userId(),size,(page-1)*size).stream().map(row->Sql.view(row,"object_type,id,number,title,expiry_date")).toList(),"total",db.count("SELECT count(*)"+source,actor.userId()),"page",page,"pageSize",size);}
    /** 被可靠通知工作器调用；按期限和接收人去重，新任职不会沿用前任通知。 */
    public void reminders(){for(var row:db.rows("SELECT o.id,o.department_id,o.expiry_date,u.id AS user_id,CASE WHEN o.expiry_date<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date THEN 'DUE' ELSE 'SOON' END AS stage FROM (SELECT id,department_id,expiry_date,state FROM archive.box UNION ALL SELECT r.id,r.department_id,r.expiry_date,CASE WHEN b.state='PUBLISHED' THEN r.state ELSE b.state END FROM archive.record r JOIN archive.box b ON b.id=r.box_id) o JOIN iam.user_account u ON archive.catalog_manager(u.id,o.department_id) WHERE o.state='PUBLISHED' AND o.expiry_date<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date+30 AND NOT EXISTS(SELECT 1 FROM ops.business_notification n WHERE n.event_key='RETENTION:'||o.id||':'||o.expiry_date||':'||u.id||':'||CASE WHEN o.expiry_date<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date THEN 'DUE' ELSE 'SOON' END) ORDER BY o.expiry_date,o.id,u.id LIMIT 100")){db.rows("SELECT ops.publish_notification(?,?,'RETENTION',?,?,'PUBLISHED',?)","RETENTION:"+row.get("id")+":"+row.get("expiry_date")+":"+row.get("user_id")+":"+row.get("stage"),row.get("user_id"),row.get("id"),row.get("expiry_date"),"RETENTION_"+row.get("stage"));}}
    /** 冻结的候选在任何批准节点均不得漂移；跨部门纠错需超级管理员且不带走在途原件。 */
    private void verifyChange(Map<String,Object> c){String type=(String)c.get("object_type");UUID id=(UUID)c.get("object_id");var next=CatalogTemplates.map(c.get("after_snapshot"));String block=approvalBlockReason(c);if(block!=null)throw ApiFault.conflict(block);lockObjects(type,id,next);var row=object(type,id);if(!"PUBLISHED".equals(row.get("state"))||!Objects.equals(row.get("row_version"),c.get("base_version")))throw ApiFault.conflict("原目录已变化，请撤回后重新更正");if(type.equals("BOX")&&!Crypto.JSON.readValue(Crypto.json(members(id)),Object.class).equals(CatalogTemplates.json(c.get("member_snapshot"))))throw ApiFault.conflict("盒内成员或版本已变化");
        if(type.equals("RECORD")){var parent=object("BOX",Inputs.uuid(next.get("boxId")));if(!"PUBLISHED".equals(parent.get("state"))||!parent.get("department_id").toString().equals(next.get("departmentId").toString())||!parent.get("classification").equals(next.get("classification")))throw ApiFault.conflict("目标盒已变化");}
        var old=CatalogTemplates.map(c.get("before_snapshot"));if(!Objects.equals(old.get("departmentId"),next.get("departmentId"))||!Objects.equals(old.get("boxId"),next.get("boxId"))){UUID box=type.equals("BOX")?id:(UUID)row.get("box_id");if(db.count("SELECT count(*) FROM archive.physical_loan WHERE box_id=? AND state='IN_USE'",box)>0||db.count("SELECT count(*) FROM archive.paper_request_item p JOIN archive.borrow_request r ON r.id=p.request_id WHERE p.box_id=? AND r.state IN ('PENDING','APPROVED')",box)>0)throw ApiFault.conflict("有在途纸质借阅，先完成交接归还后再纠正归属");}
        // 申请期间人员和模板可能变更，目的范围也须保有真实管理资格。
        UUID department=Inputs.uuid(next.get("departmentId"));
        db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE'",department);db.required("SELECT id FROM iam.user_account WHERE id=? AND department_id=? AND status='ACTIVE'",Inputs.uuid(next.get("ownerId")),department);
        templates.validate(Inputs.uuid(next.get("templateVersionId")),(String)next.get("categoryCode"),type,next.get("extensionValues"),CatalogTemplates.map(old.get("extensionValues")),!old.get("templateVersionId").toString().equals(next.get("templateVersionId").toString()));
        if(c.get("candidate_pdf_id")!=null){var pdf=db.required("SELECT o.* FROM archive.pdf_version p JOIN storage.object o ON o.id=p.object_id WHERE p.id=? AND o.state='READY'",c.get("candidate_pdf_id"));files.get().verifiedPrimary((String)pdf.get("storage_key"),((Number)pdf.get("byte_size")).longValue(),(String)pdf.get("sha256"));}
    }
    /** 统一固定字段校验，客户端不能通过普通编辑降低密级或改变过期计算。 */
    private void normalize(Actor actor,String type,Map<String,Object> next,Map<String,Object> old){Inputs.text(next,"title",512);for(String k:List.of("safeTitle","abstract","notes"))if(next.get(k)!=null)next.put(k,Inputs.text(next,k,k.equals("safeTitle")?512:20000));Inputs.choice(next.get("categoryCode"),"COMMON","ADMIN","SALES_CONTRACT","PURCHASE_CONTRACT","PROJECT_CONTRACT","QUALITY","WORK_RECORD");Inputs.choice(next.get("classification"),"GENERAL","IMPORTANT","CORE");String retention=Inputs.choice(next.get("retentionCode"),"05","10","30","YJ");LocalDate date=LocalDate.parse(Inputs.text(next,"archiveDate",10));next.put("expiryDate",retention.equals("YJ")?null:date.plusYears(Integer.parseInt(retention)).toString());if(next.get("formedDate")!=null)LocalDate.parse(next.get("formedDate").toString());
        if(!(next.get("keywords") instanceof List<?> keys)||keys.isEmpty()||keys.size()>20)throw ApiFault.invalid("请填写1至20个关键词");next.put("keywords",((List<?>)next.get("keywords")).stream().map(v->Inputs.text(Sql.map("v",v),"v",100)).distinct().toList());
        if(type.equals("RECORD")){UUID box=Inputs.uuid(next.get("boxId"));var parent=object("BOX",box);authority.requireManage(actor,(UUID)parent.get("department_id"));if(!("PUBLISHED".equals(parent.get("state"))||("DRAFT".equals(old.get("state"))&&"DRAFT".equals(parent.get("state")))))throw ApiFault.conflict("目标盒未发布或正在复核");if(!Objects.equals(parent.get("classification"),next.get("classification"))||!Objects.equals(parent.get("department_id").toString(),next.get("departmentId").toString()))throw ApiFault.invalid("文件等级和部门须与所属盒一致；整盒变更请在盒上发起");Inputs.integer(next.get("itemNo"),0,1,1000000);if(!Inputs.text(next,"fileNo",64).matches("BLK27-F-[0-9]{8,}"))throw ApiFault.invalid("文件编号格式无效");if(!Objects.equals(parent.get("carrier_type"),next.get("carrierType"))&&!"MIXED".equals(parent.get("carrier_type"))&&!("PAPER".equals(parent.get("carrier_type"))&&"MIXED".equals(next.get("carrierType"))&&Objects.equals(old.get("boxId"),next.get("boxId"))&&db.count("SELECT count(*) FROM archive.record WHERE id=? AND checklist_id IS NOT NULL",Inputs.uuid(old.get("id")))==1))throw ApiFault.invalid("目标盒载体不兼容");}
        else if(!Inputs.text(next,"boxNo",64).matches(CompanyIdentity.CODE+"-[A-Z0-9]{1,12}(?:/[A-Z0-9]{1,12})?(?:05|10|30|YJ)-[0-9]{6}-[0-9]{3}"))throw ApiFault.invalid("盒号格式无效");
        UUID department=Inputs.uuid(next.get("departmentId"));db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE'",department);if(!Objects.equals(old.get("departmentId").toString(),department.toString()))authority.requireSuper(actor,"ARCHIVE.CATALOG_MANAGE");db.required("SELECT id FROM iam.user_account WHERE id=? AND department_id=? AND status='ACTIVE'",Inputs.uuid(next.get("ownerId")),department);
        UUID template=Inputs.uuid(next.get("templateVersionId"));next.put("extensionValues",templates.validate(template,(String)next.get("categoryCode"),type,next.get("extensionValues"),CatalogTemplates.map(old.get("extensionValues")),!old.get("templateVersionId").toString().equals(template.toString())));
        if(type.equals("BOX")) {
            var values=CatalogTemplates.map(next.get("extensionValues"));var previous=CatalogTemplates.map(old.get("extensionValues"));
            // 无关字段更正保留历史缺失标签；修改纸质信息时才要求补齐。
            if(!Objects.equals(values.get("paperTags"),previous.get("paperTags"))||!Objects.equals(values.get("preservationType"),previous.get("preservationType")))ArchivePreservation.normalize((String)next.get("carrierType"),values);
        }
        // 盒字段不接受件专属值，件也不能修改盒号。
        if(type.equals("BOX")&&(next.containsKey("boxId")||next.containsKey("itemNo")||next.containsKey("fileNo")))throw ApiFault.invalid("盒不接受文件专属字段");if(type.equals("RECORD")&&!Objects.equals(next.get("boxNo"),old.get("boxNo")))throw ApiFault.invalid("文件不能修改盒号");
    }
    /** 按盒UUID稳定顺序锁定，兼容实物交付先盒后原件的锁顺序。 */
    private void lockObjects(String type,UUID id,Map<String,Object> patch){TreeSet<UUID> boxes=new TreeSet<>();var row=object(type,id);boxes.add(type.equals("BOX")?id:(UUID)row.get("box_id"));if(type.equals("RECORD")&&patch.get("boxId")!=null)boxes.add(Inputs.uuid(patch.get("boxId")));for(UUID box:boxes)db.required("SELECT id FROM archive.box WHERE id=? FOR UPDATE",box);if(type.equals("BOX"))db.rows("SELECT id FROM archive.record WHERE box_id=? ORDER BY id FOR UPDATE",id);else db.required("SELECT id FROM archive.record WHERE id=? FOR UPDATE",id);}
    /** 写入经过验证的字段；主文件与物理库存由各自明确步骤更新。 */
    private void update(String type,UUID id,Map<String,Object> next,UUID actor){
        if(type.equals("BOX")&&!"ELECTRONIC".equals(next.get("carrierType"))){
            var values=CatalogTemplates.map(next.get("extensionValues"));String location=java.util.stream.Stream.of(new String[]{"cabinetNo","柜"},new String[]{"columnNo","列"},new String[]{"layerNo","层"}).filter(part->values.get(part[0])!=null&&!values.get(part[0]).toString().isBlank()).map(part->values.get(part[0])+part[1]).collect(java.util.stream.Collectors.joining(" / "));
            var old=object("BOX",id);if(!location.isBlank()&&!location.equals(old.get("location"))){
                if(db.count("SELECT count(*) FROM archive.physical_loan WHERE box_id=? AND state='IN_USE'",id)>0)throw ApiFault.conflict("档案盒仍有借出原件，请归还后再调整存放位置");
                db.update("UPDATE archive.box SET location=? WHERE id=?",location,id);next.put("location",location);
                // 只同步原先随盒保管的原件；单独登记在其他位置的分件保留实际位置。
                for(var item:db.rows("SELECT id,location FROM archive.physical_item WHERE box_id=? AND (object_type='BOX' OR location IS NOT DISTINCT FROM ?)",id,old.get("location"))){db.update("UPDATE archive.physical_item SET location=?,row_version=row_version+1 WHERE id=?",location,item.get("id"));db.update("INSERT INTO archive.loan_event(id,physical_item_id,actor_id,event_type,details) VALUES(?,?,?,'LOCATION_CHANGED',?::jsonb)",UUID.randomUUID(),item.get("id"),actor,Crypto.json(Sql.map("from",item.get("location"),"to",location,"reason","档案盒台账位置调整")));}
            }
        }
        List<String> sets=new ArrayList<>();List<Object> values=new ArrayList<>();for(var pair:COLUMNS.entrySet()){String key=pair.getKey();if(!next.containsKey(key)||(type.equals("BOX")&&Set.of("fileNo","boxId","itemNo").contains(key))||(type.equals("RECORD")&&key.equals("boxNo")))continue;Object value=next.get(key);if(key.equals("keywords")){sets.add("keywords=ARRAY(SELECT jsonb_array_elements_text(?::jsonb))");value=Crypto.json(value);}else if(key.equals("extensionValues")){sets.add(pair.getValue()+"=?::jsonb");value=Crypto.json(value);}else{sets.add(pair.getValue()+"=?");if(key.endsWith("Id"))value=nullable(value);else if(Set.of("archiveDate","expiryDate","formedDate").contains(key))value=value==null?null:LocalDate.parse(value.toString());}values.add(value);}values.add(id);db.update("UPDATE "+table(type)+" SET "+String.join(",",sets)+",row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",values.toArray());}
    /** 批准更正建立新的不可变修订；旧批准自然失效，不复制旧授权到新修订。 */
    private void publishRevision(String type,UUID id,Map<String,Object> next,UUID pdf,UUID actor){UUID revision=UUID.randomUUID();if(type.equals("RECORD")){db.update("UPDATE archive.access_policy SET state='REVOKED',policy_version=policy_version+1 WHERE object_type='RECORD' AND object_id=? AND state='ACTIVE'",id);db.update("UPDATE archive.access_grant SET state='REVOKED',revoked_by=?,revoked_at=CURRENT_TIMESTAMP,revocation_reason='批准目录更正使旧版本授权失效',policy_version=policy_version+1 WHERE record_id=? AND state='ACTIVE'",actor,id);}if(type.equals("RECORD")){db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,pdf_version_id,metadata_snapshot,template_version_id,created_by) VALUES(?,?,(SELECT COALESCE(max(revision_no),0)+1 FROM archive.record_revision WHERE record_id=?),?,?::jsonb,?,?)",revision,id,id,pdf,Crypto.json(next),Inputs.uuid(next.get("templateVersionId")),actor);db.update("UPDATE archive.record SET current_revision_id=?,candidate_revision_id=?,current_pdf_version_id=? WHERE id=?",revision,revision,pdf,id);}else{db.update("INSERT INTO archive.box_revision(id,box_id,revision_no,metadata_snapshot,template_version_id,created_by) VALUES(?,?,(SELECT COALESCE(max(revision_no),0)+1 FROM archive.box_revision WHERE box_id=?),?::jsonb,?,?)",revision,id,id,Crypto.json(next),Inputs.uuid(next.get("templateVersionId")),actor);db.update("UPDATE archive.box SET current_revision_id=?,candidate_revision_id=? WHERE id=?",revision,revision,id);}}
    /** 通知仅保存引用，按当前合法管理范围投递，不包含档案题名及正文。 */
    /** 先限定可能的管理任职再调用正式授权函数，避免逐个扫描全部账号导致审批事务超时。 */
    private void notifyManagers(UUID id,UUID department){for(var u:db.rows("WITH candidates AS MATERIALIZED(SELECT DISTINCT a.user_id FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id WHERE a.state='ACTIVE' AND ((r.module='PLATFORM' AND r.code='IT_ADMIN') OR (a.module='ARCHIVE' AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=?))))) SELECT user_id AS id FROM candidates WHERE archive.catalog_manager(user_id,?)",department,department))db.rows("SELECT ops.publish_notification(?,?,'CATALOG_CHANGE',?,'1','APPLIED','CATALOG_CHANGED')","CATALOG:"+id+":"+u.get("id"),u.get("id"),id);}
    /** 申请人仍须有当前维护权，原有身份不形成永久权限。 */
    /** 经办人随明确交接更新；原创建人及历史审批始终保留。 */
    private Object custodian(Map<String,Object> row){return row.get("managed_by")==null?row.get("created_by"):row.get("managed_by");}
    private void requireCreator(Actor actor,UUID id){var c=changeRow(id);var row=object((String)c.get("object_type"),(UUID)c.get("object_id"));authority.requireManage(actor,(UUID)row.get("department_id"));if(!actor.userId().equals(custodian(c)))throw ApiFault.denied();}
    /** 上传必须本人、当前文件、已检测及合格大小，不能引用另一文件的会话。 */
    private Map<String,Object> upload(Actor actor,UUID id,UUID upload){return db.required("SELECT o.* FROM storage.upload_session s JOIN storage.object o ON o.id=s.object_id WHERE s.id=? AND s.uploader_id=? AND s.business_context_id=? AND s.module='ARCHIVE' AND s.purpose='ARCHIVE_PDF' AND s.state='COMPLETE' AND o.state='READY' AND o.module='ARCHIVE' AND o.media_type='application/pdf' AND o.check_result_ref IS NOT NULL AND o.byte_size<=1073741824",upload,actor.userId(),id);}
    /** 冻结全盒成员；大盒应先分批整理，不能在短事务中无限加载。 */
    private List<Map<String,Object>> members(UUID id){var rows=db.rows("SELECT id,row_version,current_revision_id,current_pdf_version_id,department_id,classification,state FROM archive.record WHERE box_id=? ORDER BY id LIMIT 1001",id);if(rows.size()>1000)throw ApiFault.conflict("整盒更正最多1000件，请联系管理员安排分批维护");return rows;}
    /** 内部对象读取仅固定表名。 */
    /** 对象类型经白名单映射后读取当前状态。 */
    private Map<String,Object> object(String type,UUID id){return db.required("SELECT * FROM "+table(type)+" WHERE id=?",id);}
    /** 将盒件类型转换为固定表名，禁止拼接客户端表名。 */
    private String table(String type){return switch(type){case "BOX"->"archive.box";case "RECORD"->"archive.record";default->throw ApiFault.invalid("档案对象类型无效");};}
    /** 选择命令审计的盒件权限域。 */
    private String scope(String type){table(type);return type.equals("BOX")?"ARCHIVE_BOX":"ARCHIVE_RECORD";}
    /** 读取更正事项，未找到时使用统一隐藏错误。 */
    private Map<String,Object> changeRow(UUID id){return db.required("SELECT * FROM archive.catalog_change WHERE id=?",id);}
    /** 管理快照的明确白名单，不含存储路径或原文件内容。 */
    /** 生成冻结前后值，扩展字段解码为稳定 JSON 对象。 */
    private Map<String,Object> snapshot(Map<String,Object> row,String type){var value=Sql.view(row,"id,department_id,title,safe_title,classification,category_code,carrier_type,owner_id,archive_date,retention_code,expiry_date,keywords,abstract,notes,formed_date,template_version_id,state,row_version,current_revision_id"+(type.equals("BOX")?",box_no,number_month,location":",file_no,box_id,item_no,current_pdf_version_id"));value.put("extensionValues",CatalogTemplates.json(row.get("extension_values")));return value;}
    /** 输出已获权事项的快照与人工核验页数。 */
    private Map<String,Object> view(UUID id){var row=changeRow(id);var value=Sql.view(row,"id,object_type,object_id,department_id,kind,state,base_version,candidate_pdf_id,candidate_revision_id,reason,created_by,managed_by,created_at,quality_confirmed_at,quality_notes,workflow_id,applied_at,row_version");value.put("before",CatalogTemplates.json(row.get("before_snapshot")));value.put("after",CatalogTemplates.json(row.get("after_snapshot")));value.put("beforeDisplay",displaySnapshot(CatalogTemplates.map(row.get("before_snapshot"))));value.put("afterDisplay",displaySnapshot(CatalogTemplates.map(row.get("after_snapshot"))));value.put("memberCount",((List<?>)CatalogTemplates.json(row.get("member_snapshot"))).size());value.put("pageCount",row.get("candidate_pdf_id")==null?0:db.count("SELECT page_count FROM archive.pdf_version WHERE id=?",row.get("candidate_pdf_id")));return value;}
    /** 审批对比按固定快照解析名称；不借展示名称改变权限或版本绑定。 */
    private Map<String,Object> displaySnapshot(Map<String,Object> source){
        Map<String,Object> result=new LinkedHashMap<>(source);
        for(String key:List.of("departmentId","ownerId","boxId","templateVersionId")){
            if(source.get(key)==null)continue;
            String sql=switch(key){case "departmentId"->"SELECT name FROM iam.department WHERE id=?";case "ownerId"->"SELECT name FROM iam.user_account WHERE id=?";case "boxId"->"SELECT box_no||' · '||title AS name FROM archive.box WHERE id=?";default->"SELECT name||' V'||version_no AS name FROM archive.directory_template WHERE id=?";};
            var row=db.one(sql,Inputs.uuid(source.get(key)));result.put(key,row==null?"历史对象（已不可用）":row.get("name"));
        }
        Map<String,Object> extensions=new LinkedHashMap<>();var values=CatalogTemplates.map(source.get("extensionValues"));
        for(var field:CatalogTemplates.definitions(templates.view(Inputs.uuid(source.get("templateVersionId"))).get("fields")))if(values.containsKey(field.get("code")))extensions.put(field.get("name").toString(),values.get(field.get("code")));
        if(values.containsKey("paperTags"))extensions.put("纸质标签",values.get("paperTags"));
        result.put("extensionValues",extensions);return result;
    }
    /** 可空标识仍需通过统一 UUID 校验。 */
    private UUID nullable(Object value){return value==null?null:Inputs.uuid(value);}
    /** 使用更正前后较严密级计算审批约束。 */
    private String strictest(String a,String b){return List.of("GENERAL","IMPORTANT","CORE").indexOf(a)>=List.of("GENERAL","IMPORTANT","CORE").indexOf(b)?a:b;}
}
