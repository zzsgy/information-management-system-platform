package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.*;
import com.company.platform.workflow.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 两层档案目录与独立归档复核；技术检测和复核通过分别提交，候选不覆盖已发布原件。 */
@Service
public class ArchiveCatalogService implements WorkflowBusiness {
    /** 编号、目录、授权及隔离文件都由真实服务提供，运行包没有自动批准或干净文件替身。 */
    private final PhysicalItems physical;
    /** 模板定义与历史字段校验独立于目录发布。 */
    private final CatalogTemplates templates;
    private final Sql db;private final ArchiveAuthority authority;private final ArchiveCommands commands;private final WorkflowEngine workflows;private final StorageFiles files;private final ReadingRuntime runtime;
    private final ArchiveFileTypes fileTypes;
    public ArchiveCatalogService(CatalogTemplates templates,PhysicalItems physical,Sql db,ArchiveAuthority authority,ArchiveCommands commands,WorkflowEngine workflows,StorageFiles files,ReadingRuntime runtime,ArchiveFileTypes fileTypes) {this.templates=templates;this.physical=physical;this.db=db;this.authority=authority;this.commands=commands;this.workflows=workflows;this.files=files;this.runtime=runtime;this.fileTypes=fileTypes;}
    /** 归档复核适配器标识，禁止客户端指定任意业务处理器。 */
    @Override public String type() {return "REGISTRATION";}
    /** 目录模板按六类和盒件分开登记，字段定义经独立模板服务校验。 */
    public Map<String,Object> template(Commands.Context context,Map<String,Object> body) {
        return templates.save(context,null,body);
    }
    /** 返回有权维护的基础模板摘要，不暴露任意动态字段执行能力。 */
    public Map<String,Object> templates(Actor actor) {return templates.list(actor,false);}
    /** 管理表单仅取得当前可维护部门的最小对象，不暴露员工账号管理资料。 */
    public Map<String,Object> options(Actor actor) {
        actor.require("ARCHIVE.CATALOG_MANAGE");boolean all=authority.role(actor,"SUPER_ADMIN",null);UUID department=Inputs.uuid(actor.user().get("departmentId"));
        if(!all&&!authority.role(actor,"DEPARTMENT_ARCHIVIST",department)) throw ApiFault.denied();
        return Sql.map("departments",db.rows("SELECT id,code,name FROM iam.department WHERE status='ACTIVE' AND (? OR id=?) ORDER BY code LIMIT 200",all,department),"templates",templates(actor).get("items"),"fileTypes",fileTypes.names());
    }
    /** 责任人选择先限制到可维护部门，再按工号或姓名匹配，最多100条。 */
    public Map<String,Object> owners(Actor actor,UUID department,String keyword) {authority.requireManage(actor,department);if(keyword.length()>100) throw ApiFault.invalid("检索条件过长");String match="%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";return Sql.map("items",db.rows("SELECT id,name,employee_no FROM iam.user_account WHERE department_id=? AND status='ACTIVE' AND (name ILIKE ? OR employee_no ILIKE ?) ORDER BY employee_no LIMIT 100",department,match,match).stream().map(row->Sql.view(row,"id,name,employee_no")).toList());}
    /** 创建电子盒草稿并占用不可复用业务编号；有纸质原件的登记不在本轮伪造实物记录。 */
    public Map<String,Object> box(Commands.Context context,Map<String,Object> body) {
        UUID department=Inputs.uuid(body.get("departmentId"));return commands.run(context,body,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",true,()->authority.requireManage(context.actor(),department),()->insertRegisteredBox(context.actor(),body),this::boxView);
    }
    /** 普通登记和带清单登记共用下拉校验；原台账批量导入仍保留历史文本。 */
    Map<String,Object> insertRegisteredBox(Actor actor,Map<String,Object> body) {
        if("COMMON".equals(body.get("categoryCode"))&&body.get("extensionValues") instanceof Map<?,?> extensions)
            fileTypes.validateRegistration(extensions.get("fileType"));
        return insertBox(actor,body);
    }
    /** 同事务新增盒：调用方须先取得有效维护范围和整批事务，编号、模板及实物规则与普通登记一致。 */
    Map<String,Object> insertBox(Actor actor,Map<String,Object> body) {
        Inputs.only(body,"departmentId","title","safeTitle","classification","retentionCode","archiveDate","physicalFileCount","location","keywords","abstract","templateVersionId","extensionValues","categoryCode","ownerId","numberMonth","carrierType","formedDate","notes");
        UUID department=Inputs.uuid(body.get("departmentId"));String classification=Inputs.choice(body.get("classification"),"GENERAL","IMPORTANT","CORE"),retention=Inputs.choice(body.get("retentionCode"),"05","10","30","YJ");carrier(body);List<String> keywords=keywords(body.get("keywords"));
        LocalDate archived=LocalDate.parse(Inputs.text(body,"archiveDate",10)),month=LocalDate.parse(Inputs.text(body,"numberMonth",10));if(month.getDayOfMonth()!=1) throw ApiFault.invalid("编号月份必须保存月首日期");
        String category=category(body.get("categoryCode"));UUID template=Inputs.uuid(body.get("templateVersionId")),owner=Inputs.uuid(body.get("ownerId"));

            var unit=db.required("SELECT * FROM iam.department WHERE id=? AND status='ACTIVE' FOR SHARE",department);String code=(String)unit.get("code");if(!code.matches("[A-Z0-9]{1,12}(?:/[A-Z0-9]{1,12})?")) throw ApiFault.invalid("部门编码不符合当前编号规则");requireTemplate(template,category,"BOX");owner(owner,department);
            long number=number("BOX:"+department+":"+retention+":"+month,999);String boxNo=CompanyIdentity.CODE+"-"+code+retention+"-"+month.toString().substring(0,7).replace("-","")+"-"+String.format(Locale.ROOT,"%03d",number);UUID id=UUID.randomUUID();
            db.update("INSERT INTO archive.box(id,box_no,department_id,title,safe_title,classification,retention_code,archive_date,expiry_date,category_code,carrier_type,owner_id,number_month,keywords,abstract,formed_date,notes,template_version_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?, ?,?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),?,?,?,?,?)",id,boxNo,department,Inputs.text(body,"title",512),optional(body,"safeTitle",512),classification,retention,archived,expiry(archived,retention),category,body.get("carrierType"),owner,month,Crypto.json(keywords),optional(body,"abstract",20000),date(body,"formedDate"),optional(body,"notes",20000),template,actor.userId());
            if(!"ELECTRONIC".equals(body.get("carrierType"))) {physical.register("BOX",id,Inputs.text(body,"location",512));db.update("UPDATE archive.box SET location=? WHERE id=?",body.get("location"),id);}
            Map<String,Object> extensions=templates.validate(template,category,"BOX",body.getOrDefault("extensionValues",Map.of()),Map.of(),true);ArchivePreservation.normalize((String)body.get("carrierType"),extensions);db.update("UPDATE archive.box SET extension_values=?::jsonb WHERE id=?",Crypto.json(extensions),id);
            UUID revision=UUID.randomUUID();db.update("INSERT INTO archive.box_revision(id,box_id,revision_no,metadata_snapshot,template_version_id,created_by) VALUES(?,?,1,?::jsonb,?,?)",revision,id,Crypto.json(boxView(id)),template,actor.userId());db.update("UPDATE archive.box SET candidate_revision_id=? WHERE id=?",revision,id);return boxView(id);
    }
    /** 同盒件号、部门和密级由唯一约束及复合外键保护；电子件未完成复核不得发布。 */
    public Map<String,Object> record(Commands.Context context,Map<String,Object> body) {
        UUID box=Inputs.uuid(body.get("boxId"));return commands.run(context,body,"ARCHIVE_BOX",box,"CREATE_RECORD",true,()->authority.requireManage(context.actor(),(UUID)db.required("SELECT department_id FROM archive.box WHERE id=?",box).get("department_id")),()->insertRecord(context.actor(),body),this::recordView);
    }
    /** 同事务新增件：调用方须先取得有效维护范围和整批事务，编号、模板及实物规则与普通登记一致。 */
    Map<String,Object> insertRecord(Actor actor,Map<String,Object> body) {
        return insertRecord(actor,body,false);
    }
    /** 纸质盒的电子清单允许登记其扫描电子副本，纸质保存类型和份数仍独立保留。 */
    Map<String,Object> insertChecklistRecord(Actor actor,Map<String,Object> body){return insertRecord(actor,body,true);}
    private Map<String,Object> insertRecord(Actor actor,Map<String,Object> body,boolean checklist) {
        Inputs.only(body,"boxId","itemNo","categoryCode","carrierType","title","safeTitle","keywords","abstract","ownerId","archiveDate","templateVersionId","extensionValues","retentionCode","formedDate","notes","location");UUID box=Inputs.uuid(body.get("boxId"));carrier(body);List<String> keywords=keywords(body.get("keywords"));String category=category(body.get("categoryCode"));

            var parent=db.required("SELECT * FROM archive.box WHERE id=? FOR UPDATE",box);if(("ELECTRONIC".equals(parent.get("carrier_type"))&&!"ELECTRONIC".equals(body.get("carrierType")))||("PAPER".equals(parent.get("carrier_type"))&&!"PAPER".equals(body.get("carrierType"))&&!checklist)) throw ApiFault.invalid("载体与所属盒不兼容，请在混合盒中登记混合资料");if(db.count("SELECT count(*) FROM archive.physical_loan WHERE box_id=? AND state='IN_USE'",box)>0)throw ApiFault.conflict("借出期间不能调整盒内实物成员");if(!Set.of("DRAFT","PUBLISHED").contains(parent.get("state"))) throw ApiFault.conflict("盒正在复核或已撤下，不能增加文件");
            UUID template=Inputs.uuid(body.get("templateVersionId")),owner=Inputs.uuid(body.get("ownerId"));requireTemplate(template,category,"RECORD");owner(owner,(UUID)parent.get("department_id"));
            LocalDate archived=LocalDate.parse(Inputs.text(body,"archiveDate",10));String retention=Inputs.choice(body.getOrDefault("retentionCode",parent.get("retention_code")),"05","10","30","YJ");UUID id=UUID.randomUUID();String fileNo=CompanyIdentity.CODE+"-F-"+String.format(Locale.ROOT,"%08d",number("FILE",999999999999L));
            db.update("INSERT INTO archive.record(id,file_no,box_id,item_no,department_id,classification,category_code,carrier_type,title,safe_title,keywords,abstract,owner_id,archive_date,retention_code,expiry_date,template_version_id,formed_date,notes,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb)),?,?,?,?,?,?,?,?,?)",id,fileNo,box,Inputs.integer(body.get("itemNo"),0,1,1000000),parent.get("department_id"),parent.get("classification"),category,body.get("carrierType"),Inputs.text(body,"title",512),optional(body,"safeTitle",512),Crypto.json(keywords),optional(body,"abstract",20000),owner,archived,retention,expiry(archived,retention),template,date(body,"formedDate"),optional(body,"notes",20000),actor.userId());
            if(!"ELECTRONIC".equals(body.get("carrierType"))) physical.register("RECORD",id,Inputs.text(body,"location",512));
            Map<String,Object> extensions=templates.validate(template,category,"RECORD",body.getOrDefault("extensionValues",Map.of()),Map.of(),true);db.update("UPDATE archive.record SET extension_values=?::jsonb WHERE id=?",Crypto.json(extensions),id);
            UUID revision=UUID.randomUUID();db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,metadata_snapshot,template_version_id,created_by) VALUES(?,?,1,?::jsonb,?,?)",revision,id,Crypto.json(recordView(id)),template,actor.userId());db.update("UPDATE archive.record SET candidate_revision_id=? WHERE id=?",revision,id);return recordView(id);
    }
    /** 导入预检复用目录值规则；只读校验不占编号、不创建草稿或实物。 */
    void validateNew(String type,Map<String,Object> body,Map<String,Object> parent) {
        carrier(body);keywords(body.get("keywords"));String category=category(body.get("categoryCode"));
        UUID template=Inputs.uuid(body.get("templateVersionId"));requireTemplate(template,category,type);
        var extensions=templates.validate(template,category,type,body.getOrDefault("extensionValues",Map.of()),Map.of(),true);if(type.equals("BOX"))ArchivePreservation.normalize((String)body.get("carrierType"),extensions);
        UUID department=Inputs.uuid(type.equals("BOX")?body.get("departmentId"):parent.get("departmentId"));owner(Inputs.uuid(body.get("ownerId")),department);
        Inputs.text(body,"title",512);optional(body,"safeTitle",512);optional(body,"abstract",20000);optional(body,"notes",20000);date(body,"formedDate");
        LocalDate archived=LocalDate.parse(Inputs.text(body,"archiveDate",10));String retention=Inputs.choice(body.get("retentionCode"),"05","10","30","YJ");expiry(archived,retention);
        if(type.equals("BOX")){Inputs.choice(body.get("classification"),"GENERAL","IMPORTANT","CORE");if(LocalDate.parse(Inputs.text(body,"numberMonth",10)).getDayOfMonth()!=1)throw ApiFault.invalid("numberMonth 必须为月首日期");}
        else{Inputs.integer(body.get("itemNo"),0,1,1000000);String carrier=Objects.toString(parent.get("carrierType"));if((carrier.equals("ELECTRONIC")&&!body.get("carrierType").equals("ELECTRONIC"))||(carrier.equals("PAPER")&&!body.get("carrierType").equals("PAPER")))throw ApiFault.invalid("carrierType 与所属盒不兼容");}
    }
    /** 完整PDF先验证双副本及隔离解析，再在短事务内绑定候选，不改变当前生效版本。 */
    public Map<String,Object> bindPdf(Commands.Context context,UUID record,Map<String,Object> body) {
        if(db.count("SELECT count(*) FROM archive.scan_item WHERE record_id=?",record)>0)throw ApiFault.conflict("该候选属于扫描批次，请通过扫描返工接收新版本");
        Inputs.only(body,"uploadSessionId","source","sourceSignatureInfo","correctionReason");UUID upload=Inputs.uuid(body.get("uploadSessionId"));var entry=authority.record(record);if("PAPER".equals(entry.get("carrier_type")))throw ApiFault.invalid("纯纸质档案不接收PDF，请按真实载体登记混合档案");authority.requireManage(context.actor(),(UUID)entry.get("department_id"));
        if(body.get("sourceSignatureInfo")!=null&&!Map.of().equals(body.get("sourceSignatureInfo"))) throw ApiFault.invalid("本轮尚未启用来源签名验证，不接受自报签名通过");
        var object=cleanUpload(context.actor(),record,upload);var store=files.get();int pageCount=runtime.withClient(client->client.pageCount(store.verifiedPrimary((String)object.get("storage_key"),((Number)object.get("byte_size")).longValue(),(String)object.get("sha256")),(String)object.get("sha256")));
        return commands.run(context,body,"ARCHIVE_RECORD",record,"BIND_PDF",true,()->authority.requireManage(context.actor(),(UUID)authority.record(record).get("department_id")),()->{
            var row=db.required("SELECT * FROM archive.record WHERE id=? FOR UPDATE",record);if(db.count("SELECT count(*) FROM archive.scan_item WHERE record_id=?",record)>0)throw ApiFault.conflict("该候选已交扫描批次办理");Commands.version(context,row);if(!"DRAFT".equals(row.get("state"))) throw ApiFault.conflict("当前记录不允许替换候选PDF");cleanUpload(context.actor(),record,upload);
            int previous=(int)db.count("SELECT COALESCE(max(version_no),0) FROM archive.pdf_version WHERE record_id=?",record);if(previous>0) Inputs.text(body,"correctionReason",2000);
            UUID pdf=UUID.randomUUID();db.update("INSERT INTO archive.pdf_version(id,record_id,version_no,object_id,source,correction_reason,created_by,page_count) VALUES(?,?,?,?,?,?,?,?)",pdf,record,previous+1,object.get("id"),Inputs.text(body,"source",64),optional(body,"correctionReason",2000),context.actor().userId(),pageCount);
            UUID revision=UUID.randomUUID();int next=(int)db.count("SELECT COALESCE(max(revision_no),0)+1 FROM archive.record_revision WHERE record_id=?",record);
            db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,pdf_version_id,metadata_snapshot,template_version_id,created_by) VALUES(?,?,?,?,?::jsonb,?,?)",revision,record,next,pdf,Crypto.json(recordView(record)),row.get("template_version_id"),context.actor().userId());
            db.update("UPDATE archive.record SET candidate_revision_id=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",revision,record);
            db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'ARCHIVE_PDF',?,'BUSINESS')",UUID.randomUUID(),object.get("id"),pdf);return pdfView(pdf);
        },this::pdfView);
    }
    /** 固定候选及人工质量证明；本人登记的超管草稿直接归档，其余送独立复核。 */
    public Map<String,Object> submit(Commands.Context context,Map<String,Object> body) {
        Inputs.only(body,"objectRefs","qualityEvidenceRef");String quality=Inputs.text(body,"qualityEvidenceRef",256);var refs=Inputs.objects(body.get("objectRefs"),50);if(refs.isEmpty()) throw ApiFault.invalid("请指定送审对象");
        // 本轮单对象送审，防止跨对象命令回执仅记录部分实例；后续批量用独立批次聚合。
        if(refs.size()!=1) throw ApiFault.invalid("本轮请按对象分别送归档复核");var ref=refs.getFirst();Inputs.only(ref,"objectType","objectId","expectedVersion","candidateRevisionId","candidatePdfVersionId");String type=Inputs.choice(ref.get("objectType"),"BOX","RECORD");UUID id=Inputs.uuid(ref.get("objectId"));
        return commands.run(context,body,type.equals("BOX")?"ARCHIVE_BOX":"ARCHIVE_RECORD",id,"SUBMIT_REGISTRATION",true,()->authority.requireManage(context.actor(),(UUID)object(type,id).get("department_id")),()->{
            String table=table(type);var row=db.required("SELECT * FROM "+table+" WHERE id=? FOR UPDATE",id);if(type.equals("RECORD"))scanQuality(id);if(!"DRAFT".equals(row.get("state"))||Inputs.integer(ref.get("expectedVersion"),0,1,Integer.MAX_VALUE)!=((Number)row.get("row_version")).intValue()||!Objects.equals(Inputs.uuid(ref.get("candidateRevisionId")),row.get("candidate_revision_id"))) throw ApiFault.conflict("送审状态或候选修订已变化");
            if(type.equals("RECORD")){if(!"PUBLISHED".equals(db.required("SELECT state FROM archive.box WHERE id=?",row.get("box_id")).get("state")))throw ApiFault.conflict("请先完成所属盒的归档复核");if("PAPER".equals(row.get("carrier_type"))&&ref.get("candidatePdfVersionId")!=null)throw ApiFault.invalid("纯纸质目录不接收虚构PDF引用");}
            UUID pdf=null;if(type.equals("RECORD")&&!"PAPER".equals(row.get("carrier_type"))) {var candidate=db.required("SELECT * FROM archive.record_revision WHERE id=? AND record_id=?",row.get("candidate_revision_id"),id);pdf=Inputs.uuid(ref.get("candidatePdfVersionId"));if(!pdf.equals(candidate.get("pdf_version_id"))) throw ApiFault.conflict("候选PDF与目录修订不一致");db.required("SELECT p.id FROM archive.pdf_version p JOIN storage.object o ON o.id=p.object_id WHERE p.id=? AND p.record_id=? AND p.review_state='CHECK_PASSED' AND o.state='READY'",pdf,id);if(!"PUBLISHED".equals(db.required("SELECT state FROM archive.box WHERE id=?",row.get("box_id")).get("state"))) throw ApiFault.conflict("请先完成所属盒的归档复核");}
            db.required("SELECT id FROM archive.quality_check WHERE id=? AND object_type=? AND object_id=? AND revision_id=? AND pdf_version_id IS NOT DISTINCT FROM ?::uuid AND result='PASS'",Inputs.uuid(quality),type,id,row.get("candidate_revision_id"),pdf);
            var snapshot=Sql.map("objectType",type,"objectId",id.toString(),"revisionId",row.get("candidate_revision_id").toString(),"pdfVersionId",pdf==null?null:pdf.toString(),"departmentId",row.get("department_id").toString(),"classification",row.get("classification"),"qualityEvidenceRef",quality,"title",row.get("title"));
            if(!"ELECTRONIC".equals(row.get("carrier_type")))db.required("SELECT id FROM archive.physical_item WHERE "+(type.equals("BOX")?"box_id=? AND object_type='BOX'":"record_id=?"),id);
            if(type.equals("BOX")){snapshot.put("members",db.rows("SELECT id,candidate_revision_id FROM archive.record WHERE box_id=? ORDER BY id",id).stream().map(member->Sql.map("id",member.get("id").toString(),"revisionId",Objects.toString(member.get("candidate_revision_id"),null))).toList());snapshot.put("electronicMembers",checklistMembers(id,"DRAFT"));}
            if(context.actor().userId().equals(row.get("created_by")) && authority.role(context.actor(),"SUPER_ADMIN",null)){
                publish(snapshot);
                commands.event(type,id,"ARCHIVE_DIRECT_REGISTRATION");
                return directRegistration(id);
            }
            int round=(int)db.count("SELECT COALESCE(max(round_no),0)+1 FROM workflow.instance WHERE business_type='REGISTRATION' AND business_id=?",id);
            UUID flow=workflows.create(context.actor(),"REGISTRATION",id,null,(String)row.get("classification"),0,snapshot,List.of(new FixedBorrowRoute.Duty("DEPARTMENT_MANAGER",(UUID)row.get("department_id")),new FixedBorrowRoute.Duty("ARCHIVE_ADMIN",null)));
            // 复核轮次递增不能覆盖先前驳回决定；引擎创建时使用本对象下一轮。
            if(round>1) db.update("UPDATE workflow.instance SET round_no=? WHERE id=?",round,flow);
            db.update("UPDATE "+table+" SET state='PENDING_REVIEW',row_version=row_version+1 WHERE id=?",id);if(pdf!=null) db.update("UPDATE archive.pdf_version SET review_state='PENDING_REVIEW' WHERE id=?",pdf);
            if(type.equals("BOX"))for(var member:(List<Map<String,Object>>)snapshot.get("electronicMembers")){db.update("UPDATE archive.record SET state='PENDING_REVIEW',row_version=row_version+1 WHERE id=?",Inputs.uuid(member.get("id")));db.update("UPDATE archive.pdf_version SET review_state='PENDING_REVIEW' WHERE id=?",Inputs.uuid(member.get("pdfVersionId")));}
            return Sql.map("id",flow,"jobId",null,"state","PENDING","workflowInstanceId",flow,"instances",List.of(flow));
        },resultId->resultId.equals(id)?directRegistration(id):Sql.map("id",resultId,
            "state",db.required("SELECT state FROM workflow.instance WHERE id=?",resultId).get("state"),"workflowInstanceId",resultId));
    }
    /** 人工确认与真实候选绑定；电子件确认页数必须等于隔离进程实测页数。 */
    public Map<String,Object> quality(Commands.Context context,Map<String,Object> body) {
        if("RECORD".equals(body.get("objectType")))scanQuality(Inputs.uuid(body.get("objectId")));
        Inputs.only(body,"objectType","objectId","candidateRevisionId","candidatePdfVersionId","checkedPageCount","confirmed","notes");String type=Inputs.choice(body.get("objectType"),"BOX","RECORD");UUID id=Inputs.uuid(body.get("objectId")),revision=Inputs.uuid(body.get("candidateRevisionId"));int checked=Inputs.integer(body.get("checkedPageCount"),0,0,10000);if(!Boolean.TRUE.equals(body.get("confirmed"))) throw ApiFault.invalid("请完成人工检查后明确确认");Inputs.text(body,"notes",2000);
        return commands.run(context,body,type.equals("BOX")?"ARCHIVE_BOX":"ARCHIVE_RECORD",id,"CONFIRM_QUALITY",true,()->authority.requireManage(context.actor(),(UUID)object(type,id).get("department_id")),()->{
            var row=db.required("SELECT * FROM "+table(type)+" WHERE id=? FOR UPDATE",id);if(type.equals("RECORD"))scanQuality(id);if(!"DRAFT".equals(row.get("state"))||!revision.equals(row.get("candidate_revision_id"))) throw ApiFault.conflict("受检候选已变化");UUID pdf=null;
            if(type.equals("RECORD")&&!"PAPER".equals(row.get("carrier_type"))) {pdf=Inputs.uuid(body.get("candidatePdfVersionId"));var version=db.required("SELECT p.page_count FROM archive.record_revision r JOIN archive.pdf_version p ON p.id=r.pdf_version_id WHERE r.id=? AND r.record_id=? AND p.id=? AND p.review_state='CHECK_PASSED'",revision,id,pdf);if(checked<1||checked!=((Number)version.get("page_count")).intValue()) throw ApiFault.invalid("人工确认须覆盖候选PDF全部页数");}
            else if(checked!=0||body.get("candidatePdfVersionId")!=null) throw ApiFault.invalid("盒或纯纸质确认不混入文件PDF页数");
            UUID proof=UUID.randomUUID();db.update("INSERT INTO archive.quality_check(id,object_type,object_id,revision_id,pdf_version_id,checked_by,checked_page_count,result,notes) VALUES(?,?,?,?,?,?,?,'PASS',?)",proof,type,id,revision,pdf,context.actor().userId(),checked,body.get("notes"));return Sql.map("id",proof,"result","PASS");
        },proof->Sql.map("id",proof,"result","PASS"));
    }
    /** 决定前再次核对固定候选和成员，不能审A版本却发布B版本。 */
    @Override public void verify(Map<String,Object> instance) {
        var scope=com.company.platform.workflow.WorkflowEngine.snapshot(instance);if("RECORD".equals(scope.get("objectType")))scanQuality(Inputs.uuid(scope.get("objectId")));
        var snapshot=WorkflowEngine.snapshot(instance);String type=(String)snapshot.get("objectType");UUID id=Inputs.uuid(snapshot.get("objectId"));var row=db.required("SELECT * FROM "+table(type)+" WHERE id=? FOR UPDATE",id);
        if(type.equals("BOX")&&snapshot.containsKey("electronicMembers")&&!Crypto.json(snapshot.get("electronicMembers")).equals(Crypto.json(checklistMembers(id,"PENDING_REVIEW"))))throw ApiFault.conflict("电子清单或文件候选已变化，归档审批不能继续");
        if(!"PENDING_REVIEW".equals(row.get("state"))||!Inputs.uuid(snapshot.get("revisionId")).equals(row.get("candidate_revision_id"))||!snapshot.get("classification").equals(row.get("classification"))) throw ApiFault.conflict("归档候选已失效");
        if(type.equals("RECORD")&&!"PAPER".equals(row.get("carrier_type"))) {var candidate=db.required("SELECT * FROM archive.record_revision WHERE id=? AND record_id=?",row.get("candidate_revision_id"),id);if(!Inputs.uuid(snapshot.get("pdfVersionId")).equals(candidate.get("pdf_version_id"))) throw ApiFault.conflict("候选PDF已变化");db.required("SELECT p.id FROM archive.pdf_version p JOIN storage.object o ON o.id=p.object_id WHERE p.id=? AND p.review_state='PENDING_REVIEW' AND o.state='READY'",candidate.get("pdf_version_id"));}
        else if(type.equals("BOX")) {var members=db.rows("SELECT id,candidate_revision_id FROM archive.record WHERE box_id=? ORDER BY id",id).stream().map(member->Sql.map("id",member.get("id").toString(),"revisionId",Objects.toString(member.get("candidate_revision_id"),null))).toList();if(!Crypto.json(members).equals(Crypto.json(snapshot.get("members")))) throw ApiFault.conflict("送审盒成员已变化");}
    }
    /** 直接归档以档案对象为幂等回执，不创建虚构的审批实例。 */
    private Map<String,Object> directRegistration(UUID id) {
        return Sql.map("id",id,"jobId",null,"state","PUBLISHED","workflowInstanceId",null,"instances",List.of());
    }

    /** 固定候选通过直接归档校验或全部审批后，切换同一批对象的当前版本。 */
    private void publish(Map<String,Object> snapshot) {
        String type=(String)snapshot.get("objectType");
        UUID id=Inputs.uuid(snapshot.get("objectId"));
        if(type.equals("BOX")){
            db.update("UPDATE archive.box SET state='PUBLISHED',current_revision_id=candidate_revision_id,row_version=row_version+1 WHERE id=?",id);
        } else {
            publishRecord(id,snapshot.get("pdfVersionId")==null?null:Inputs.uuid(snapshot.get("pdfVersionId")));
        }
        commands.event(type,id,"ARCHIVE_PUBLISHED");
        if(snapshot.get("electronicMembers") instanceof List<?> members){
            for(Object value:members){
                var member=(Map<?,?>)value;
                publishRecord(Inputs.uuid(member.get("id")),Inputs.uuid(member.get("pdfVersionId")));
            }
        }
    }

    /** 发布文件和该文件已核对的 PDF 版本。 */
    private void publishRecord(UUID record,UUID pdf) {
        if(pdf!=null)db.update("UPDATE archive.pdf_version SET review_state='APPROVED' WHERE id=?",pdf);
        db.update("UPDATE archive.record SET state='PUBLISHED',current_revision_id=candidate_revision_id,current_pdf_version_id=?,row_version=row_version+1 WHERE id=?",pdf,record);
    }

    /** 独立审批通过时重验候选；驳回保留原有历史和候选。 */
    @Override public void finish(Map<String,Object> instance,boolean approved,UUID approver) {
        var snapshot=WorkflowEngine.snapshot(instance);
        if(approved){
            verify(instance);
            publish(snapshot);
            return;
        }
        String type=(String)snapshot.get("objectType");
        UUID id=Inputs.uuid(snapshot.get("objectId"));
        db.update("UPDATE "+table(type)+" SET state='DRAFT',row_version=row_version+1 WHERE id=? AND state='PENDING_REVIEW'",id);
        if(snapshot.get("pdfVersionId")!=null)
            db.update("UPDATE archive.pdf_version SET review_state='CHECK_PASSED' WHERE id=?",Inputs.uuid(snapshot.get("pdfVersionId")));
        commands.event(type,id,"ARCHIVE_REVIEW_ENDED");
        if(snapshot.get("electronicMembers") instanceof List<?> members){
            for(Object value:members){
                var member=(Map<?,?>)value;
                db.update("UPDATE archive.record SET state='DRAFT',row_version=row_version+1 WHERE id=? AND state='PENDING_REVIEW'",Inputs.uuid(member.get("id")));
                db.update("UPDATE archive.pdf_version SET review_state='CHECK_PASSED' WHERE id=?",Inputs.uuid(member.get("pdfVersionId")));
            }
        }
    }
    /** 同盒归档冻结清单条目、电子文件和人工核对证明，纸质份数不会展开为重复电子版本。 */
    private List<Map<String,Object>> checklistMembers(UUID box,String state){List<Map<String,Object>> result=new ArrayList<>();for(var row:db.rows("SELECT r.id,r.title,r.state,r.candidate_revision_id,c.pdf_version_id,v.state AS electronic_state,v.pdf_version_id AS prepared_pdf FROM archive.record r LEFT JOIN archive.record_revision c ON c.id=r.candidate_revision_id LEFT JOIN archive.electronic_version v ON v.id=r.electronic_version_id WHERE r.box_id=? AND r.state<>'WITHDRAWN' AND (r.checklist_id IS NOT NULL OR r.electronic_version_id IS NOT NULL) ORDER BY r.item_no,r.id",box)){if(!state.equals(row.get("state"))||!"READY".equals(row.get("electronic_state"))||row.get("pdf_version_id")==null||!row.get("pdf_version_id").equals(row.get("prepared_pdf")))throw ApiFault.conflict("电子清单仍有未上传或未处理完成的文件，请核对后送审");db.required("SELECT q.id FROM archive.quality_check q JOIN archive.pdf_version p ON p.id=q.pdf_version_id JOIN storage.object o ON o.id=p.object_id WHERE q.object_id=? AND q.revision_id=? AND q.pdf_version_id=? AND q.result='PASS' AND p.review_state IN ('CHECK_PASSED','PENDING_REVIEW') AND o.state='READY'",row.get("id"),row.get("candidate_revision_id"),row.get("pdf_version_id"));result.add(Sql.map("id",row.get("id").toString(),"title",row.get("title"),"revisionId",row.get("candidate_revision_id").toString(),"pdfVersionId",row.get("pdf_version_id").toString()));}return result;}
    /** 当前必要复核节点读取冻结候选摘要，不等同于正式阅读批准。 */
    @Override public Map<String,Object> view(Actor actor,Map<String,Object> instance) {return Sql.map("snapshot",WorkflowEngine.snapshot(instance),"currentState",object((String)WorkflowEngine.snapshot(instance).get("objectType"),Inputs.uuid(WorkflowEngine.snapshot(instance).get("objectId"))).get("state"));}
    /** 查询在SQL中先确定字段表示再匹配和计数；安全题名受众不能通过秘密摘要检索侧漏。 */
    public Map<String,Object> records(Actor actor,Map<String,String> query) {
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"page","pageSize","keyword","boxId","departmentId","state","classification");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String keyword=query.getOrDefault("keyword","");if(keyword.length()>200) throw ApiFault.invalid("检索条件过长");
        List<Object> args=new ArrayList<>(List.of(actor.userId(),actor.actions().contains("ARCHIVE.CATALOG_MANAGE")));String filter=query.containsKey("state")?"":" AND r.state<>'WITHDRAWN'";if(query.containsKey("boxId")){filter+=" AND r.box_id=?";args.add(Inputs.uuid(query.get("boxId")));}if(query.containsKey("departmentId")){filter+=" AND r.department_id=?";args.add(Inputs.uuid(query.get("departmentId")));}
        if(query.containsKey("state")){filter+=" AND r.state=?";args.add(Inputs.choice(query.get("state"),"DRAFT","PENDING_REVIEW","PUBLISHED","WITHDRAWN"));}
        String scope="WITH actor_scope AS (SELECT ?::uuid AS person,?::boolean AS catalog_allowed), scoped AS (SELECT r.*,b.box_no,EXISTS(SELECT 1 FROM archive.physical_item item WHERE item.record_id=r.id) AS has_original,archive.record_paper_occupied(r.id,r.box_id) AS paper_occupied,ARRAY(SELECT DISTINCT g.action FROM archive.access_grant g WHERE g.record_id=r.id AND g.user_id=actor_scope.person AND archive.unwatermarked_grant(g.id,actor_scope.person)) AS unwatermarked_actions,archive.visibility(r.id,actor_scope.person,actor_scope.catalog_allowed) AS representation,archive.action_allowed(r.id,actor_scope.person,'VIEW') AS can_view,archive.action_allowed(r.id,actor_scope.person,'DOWNLOAD') AS can_download,archive.action_allowed(r.id,actor_scope.person,'PRINT') AS can_print,cv.pdf_version_id AS candidate_pdf_version_id,pv.page_count AS candidate_page_count,(SELECT q.id FROM archive.quality_check q WHERE q.object_id=r.id AND q.revision_id=r.candidate_revision_id AND q.pdf_version_id IS NOT DISTINCT FROM cv.pdf_version_id ORDER BY q.created_at DESC LIMIT 1) AS quality_check_id,(SELECT w.id FROM workflow.instance w WHERE w.business_type='REGISTRATION' AND w.business_id=r.id AND w.state='PENDING' ORDER BY w.round_no DESC LIMIT 1) AS review_context_id FROM archive.record r JOIN archive.box b ON b.id=r.box_id CROSS JOIN actor_scope LEFT JOIN archive.record_revision cv ON cv.id=r.candidate_revision_id LEFT JOIN archive.pdf_version pv ON pv.id=cv.pdf_version_id WHERE true"+filter+") ";
        String where=" FROM scoped WHERE representation<>'HIDDEN' AND (CASE WHEN representation='TITLE_ONLY' THEN coalesce(safe_title,file_no) ELSE concat_ws(' ',title,file_no,array_to_string(keywords,' '),abstract) END) ILIKE ?";
        args.add("%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");if(query.containsKey("classification")){
            where+=" AND representation<>'TITLE_ONLY' AND classification=?";
            args.add(Inputs.choice(query.get("classification"),"GENERAL","IMPORTANT","CORE"));
        }
        long total=db.count(scope+"SELECT count(*)"+where,args.toArray());List<Object> paged=new ArrayList<>(args);paged.add(size);paged.add((page-1)*size);
        var rows=db.rows(scope+"SELECT *"+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",paged.toArray());
        return Sql.map("items",rows.stream().map(row->{if("TITLE_ONLY".equals(row.get("representation"))) return Sql.map("representation","TITLE_ONLY","summary",Sql.map("objectId",row.get("id"),"safeTitle",Objects.toString(row.get("safe_title"),Objects.toString(row.get("file_no"))),"canApply",row.get("current_pdf_version_id")!=null,"paperOccupied",row.get("paper_occupied"),"canBorrowPaper",actor.actions().contains("ARCHIVE.PHYSICAL_BORROW")&&Boolean.TRUE.equals(row.get("has_original"))&&!Boolean.TRUE.equals(row.get("paper_occupied"))));var record=Sql.view(row,"id,file_no,box_id,box_no,item_no,department_id,title,safe_title,classification,category_code,carrier_type,state,current_revision_id,current_pdf_version_id,can_view,can_download,can_print,paper_occupied,unwatermarked_actions");if("MANAGE".equals(row.get("representation"))) record.putAll(Sql.view(row,"row_version,candidate_revision_id,candidate_pdf_version_id,candidate_page_count,quality_check_id,review_context_id"));return Sql.map("representation",row.get("representation"),"record",record);}).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 盒目录本轮只按真实维护范围返回；不从盒安全检索权推导全盒正文权。 */
    public Map<String,Object> boxes(Actor actor) {return boxes(actor,Map.of());}
    /** 盒目录先按当前维护权及台账字段筛选，再计数和分页。 */
    public Map<String,Object> boxes(Actor actor,Map<String,String> query) {
        actor.require("ARCHIVE.CATALOG_MANAGE");Inputs.only(query,"page","pageSize","keyword","state","boxNo","title","location","preservationType","classification","retentionCode","copyCountMin","copyCountMax","expiryFrom","expiryTo","legacyLedgerStatus");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),100,1,100);
        List<Object> args=new ArrayList<>(List.of(actor.userId()));String where=" FROM archive.box WHERE archive.catalog_manager(?,department_id)";
        where+=boxFilters(query,args);long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);
        return Sql.map("items",db.rows("SELECT id"+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->boxView((UUID)row.get("id"))).toList(),"total",total,"page",page,"pageSize",size);
    }
    /** 有权维护的有效档案盒可标记补录进度；状态已变时拒绝覆盖，不推进档案修订。 */
    public Map<String,Object> markLegacyLedger(Commands.Context context,UUID box,Map<String,Object> body) {
        Inputs.only(body,"status","expectedStatus");
        String status=legacyLedgerStatus(body.get("status")),expected=legacyLedgerStatus(body.get("expectedStatus"));
        return commands.run(context,body,"ARCHIVE_BOX",box,"SET_LEGACY_LEDGER_STATUS",false,()->{
            var row=db.required("SELECT department_id FROM archive.box WHERE id=? AND state<>'WITHDRAWN' FOR UPDATE",box);
            authority.requireManage(context.actor(),(UUID)row.get("department_id"));
        },()->{
            if(db.update("UPDATE archive.box SET legacy_ledger_status=? WHERE id=? AND legacy_ledger_status=?",status,box,expected)!=1)
                throw ApiFault.conflict("原台账补录状态已变更，请刷新后重试");
            return boxView(box);
        },this::boxView);
    }
    /** 筛选和标记使用同一组状态，独立于归档审批状态。 */
    private String legacyLedgerStatus(Object value) {
        return Inputs.choice(value,"PENDING","COMPLETED","NOT_REQUIRED");
    }
    /** 保存类型来自全部获权台账，避免只从当前页取值或泄露其他部门的类型。 */
    public Map<String,Object> boxFilterOptions(Actor actor) {
        actor.require("ARCHIVE.CATALOG_MANAGE");
        return Sql.map("preservationTypes",db.rows("SELECT DISTINCT CASE carrier_type WHEN 'PAPER' THEN '纸质' WHEN 'ELECTRONIC' THEN '电子' ELSE '纸质+电子' END AS value FROM archive.box WHERE state<>'WITHDRAWN' AND archive.catalog_manager(?,department_id) ORDER BY value",actor.userId()).stream().map(row->row.get("value")).toList());
    }
    /** 查询入口一次校验范围，SQL表达式固定；同一条件同时用于总数和分页。 */
    private String boxFilters(Map<String,String> query,List<Object> args) {
        StringBuilder where=new StringBuilder();
        if(query.containsKey("state")){where.append(" AND state=?");args.add(Inputs.choice(query.get("state"),"DRAFT","PENDING_REVIEW","PUBLISHED","WITHDRAWN"));}else where.append(" AND state<>'WITHDRAWN'");
        if(query.containsKey("legacyLedgerStatus")){where.append(" AND legacy_ledger_status=?");args.add(legacyLedgerStatus(query.get("legacyLedgerStatus")));}
        var textFields=Map.of("keyword","concat_ws(' ',title,box_no,location,abstract,notes,array_to_string(keywords,' '),extension_values::text)","boxNo","concat_ws(' ',box_no,extension_values->>'originalNumber')","title","title","location","coalesce(nullif(concat_ws(' / ',nullif(extension_values->>'cabinetNo','')||'柜',nullif(extension_values->>'columnNo','')||'列',nullif(extension_values->>'layerNo','')||'层'),''),location)");
        for(var field:textFields.entrySet()){
            String value=query.getOrDefault(field.getKey(),"").trim();if(value.isEmpty())continue;if(value.length()>200)throw ApiFault.invalid("检索条件过长");
            where.append(" AND ").append(field.getValue()).append(" ILIKE ?");args.add("%"+value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
        }
        if(query.containsKey("preservationType")){where.append(" AND CASE carrier_type WHEN 'PAPER' THEN '纸质' WHEN 'ELECTRONIC' THEN '电子' ELSE '纸质+电子' END=?");args.add(Inputs.text(query,"preservationType",200));}
        if(query.containsKey("classification")){where.append(" AND classification=?");args.add(Inputs.choice(query.get("classification"),"GENERAL","IMPORTANT","CORE"));}
        if(query.containsKey("retentionCode")){where.append(" AND retention_code=?");args.add(Inputs.choice(query.get("retentionCode"),"05","10","30","YJ"));}
        Integer minimum=query.containsKey("copyCountMin")?Inputs.integer(query.get("copyCountMin"),0,0,Integer.MAX_VALUE):null,maximum=query.containsKey("copyCountMax")?Inputs.integer(query.get("copyCountMax"),0,0,Integer.MAX_VALUE):null;
        if(minimum!=null&&maximum!=null&&minimum>maximum)throw ApiFault.invalid("保存份数最小值不能大于最大值");
        if(minimum!=null){where.append(" AND (extension_values->>'copyCount')::numeric>=?");args.add(minimum);}
        if(maximum!=null){where.append(" AND (extension_values->>'copyCount')::numeric<=?");args.add(maximum);}
        LocalDate from,to;
        try{from=query.containsKey("expiryFrom")?LocalDate.parse(query.get("expiryFrom")):null;to=query.containsKey("expiryTo")?LocalDate.parse(query.get("expiryTo")):null;}catch(java.time.format.DateTimeParseException failure){throw ApiFault.invalid("到期日期请填写有效年月日");}
        if(from!=null&&to!=null&&from.isAfter(to))throw ApiFault.invalid("到期日期开始日期不能晚于结束日期");
        if(from!=null){where.append(" AND expiry_date>=?");args.add(from);}
        if(to!=null){where.append(" AND expiry_date<=?");args.add(to);}
        return where.toString();
    }
    /** 原件来自本人和同一档案文件上下文，不接受公盘干净文件冒充档案原件。 */
    private Map<String,Object> cleanUpload(Actor actor,UUID record,UUID upload) {return db.required("SELECT o.* FROM storage.upload_session s JOIN storage.object o ON o.id=s.object_id WHERE s.id=? AND s.uploader_id=? AND s.module='ARCHIVE' AND s.purpose='ARCHIVE_PDF' AND s.business_context_id=? AND s.state='COMPLETE' AND o.state='READY' AND o.module='ARCHIVE' AND o.media_type='application/pdf' AND o.check_result_ref IS NOT NULL AND o.byte_size<=1073741824",upload,actor.userId(),record);}
    /** 扫描候选必须通过当前轮次人工检查且未暂停，不能从普通目录入口绕过质检。 */
    private void scanQuality(UUID record){if(db.count("SELECT count(*) FROM archive.scan_item i JOIN archive.scan_batch b ON b.id=i.batch_id JOIN archive.record r ON r.id=i.record_id JOIN archive.record_revision v ON v.id=r.candidate_revision_id WHERE i.record_id=? AND (i.state<>'PASSED' OR b.paused OR i.pdf_version_id IS DISTINCT FROM v.pdf_version_id)",record)>0)throw ApiFault.conflict("扫描候选未通过当前轮次质检或批次已暂停");}
    /** 依据已校验的盒件类型定位业务对象；不存在按隐藏资源处理。 */
    private Map<String,Object> object(String type,UUID id) {return db.required("SELECT * FROM "+table(type)+" WHERE id=?",id);}
    /** 只允许固定盒件表名，客户端字符串不能拼接为数据库标识符。 */
    private String table(String type) {return switch(type){case "BOX"->"archive.box";case "RECORD"->"archive.record";default->throw ApiFault.invalid("档案对象类型不受支持");};}
    /** 盒目录对外投影，不返回内部修订快照和存储键。 */
    Map<String,Object> boxView(UUID id) {var row=db.required("SELECT b.*,(SELECT count(*) FROM archive.record r WHERE r.box_id=b.id AND r.state<>'WITHDRAWN') AS record_count FROM archive.box b WHERE b.id=?",id);row.put("extension_values",CatalogTemplates.json(row.get("extension_values")));return Sql.view(row,"id,box_no,department_id,title,safe_title,classification,retention_code,archive_date,expiry_date,category_code,carrier_type,physical_file_count,location,owner_id,number_month,keywords,state,legacy_ledger_status,row_version,current_revision_id,candidate_revision_id,template_version_id,extension_values,abstract,notes,formed_date,record_count,updated_at");}
    /** 件目录对外投影，正文读取另走精确批准来源。 */
    Map<String,Object> recordView(UUID id) {var row=authority.record(id);row.put("extension_values",CatalogTemplates.json(row.get("extension_values")));return Sql.view(row,"id,file_no,box_id,box_no,item_no,department_id,title,safe_title,classification,category_code,carrier_type,keywords,abstract,owner_id,archive_date,retention_code,expiry_date,state,row_version,current_revision_id,current_pdf_version_id,candidate_revision_id,template_version_id,extension_values,abstract,notes,formed_date");}
    /** 返回PDF技术和复核状态摘要，不公开源文件定位。 */
    private Map<String,Object> pdfView(UUID id) {return Sql.view(db.required("SELECT * FROM archive.pdf_version WHERE id=?",id),"id,record_id,version_no,review_state,created_at");}
    /** 返回固定模板版本，不包含可执行表达式。 */
    private Map<String,Object> templateView(UUID id) {return Sql.view(db.required("SELECT * FROM archive.directory_template WHERE id=?",id),"id,template_key,version_no,category_code,object_type,name,state");}
    /** 通用台账跨业务类别使用，仍须匹配盒件类型并处于有效状态。 */
    private void requireTemplate(UUID id,String category,String type) {db.required("SELECT id FROM archive.directory_template WHERE id=? AND (category_code='COMMON' OR category_code=?) AND object_type=? AND state='ACTIVE' FOR SHARE",id,category,type);}
    /** 责任人必须为该部门当前有效员工，不能跨部门挂名。 */
    private void owner(UUID person,UUID department) {db.required("SELECT id FROM iam.user_account WHERE id=? AND department_id=? AND status='ACTIVE'",person,department);}
    /** 短事务原子分配编号并限制最大值，已使用流水不回退。 */
    private long number(String scope,long maximum) {db.update("INSERT INTO archive.number_rule(scope_key,last_value) VALUES(?,0) ON CONFLICT DO NOTHING",scope);var value=db.one("UPDATE archive.number_rule SET last_value=last_value+1 WHERE scope_key=? AND last_value<? RETURNING last_value",scope,maximum);if(value==null) throw ApiFault.conflict("编号流水已用尽，请先扩展编号规则");return ((Number)value.get("last_value")).longValue();}
    /** 业务类别白名单与六类固定目录设计一致。 */
    private String category(Object value) {return Inputs.choice(value,"COMMON","ADMIN","SALES_CONTRACT","PURCHASE_CONTRACT","PROJECT_CONTRACT","QUALITY","WORK_RECORD");}
    /** 载体与实际原件一致；目录扩展字段另由所选固定模板验证。 */
    private void carrier(Map<String,Object> body) {String carrier=Inputs.choice(body.get("carrierType"),"ELECTRONIC","PAPER","MIXED");if(!carrier.equals("ELECTRONIC"))Inputs.text(body,"location",512);if(body.get("physicalFileCount")!=null&&Inputs.integer(body.get("physicalFileCount"),0,0,1000000)!=0)throw ApiFault.invalid("实物件数由已登记的件目录累计，不能预填未登记原件");}

    /** 关键字按项限制数量和长度，不接受无界列表。 */
    private List<String> keywords(Object input) {if(!(input instanceof List<?> values)||values.isEmpty()||values.size()>20) throw ApiFault.invalid("请填写1至20个关键词");return values.stream().map(value->Inputs.text(Sql.map("value",value),"value",100)).distinct().toList();}
    /** 可选短文本保留缺省语义，有值时仍执行长度校验。 */
    private String optional(Map<String,Object> body,String key,int max) {return body.get(key)==null?null:Inputs.text(body,key,max);}
    /** 可选业务日期使用ISO日历日期，不混入本机时区时刻。 */
    private LocalDate date(Map<String,Object> body,String key) {return body.get(key)==null?null:LocalDate.parse(Inputs.text(body,key,10));}
    /** 保管期按归档日增加日历年，永久保存没有截止日期。 */
    private LocalDate expiry(LocalDate archive,String code) {return code.equals("YJ")?null:archive.plusYears(Integer.parseInt(code));}
}
