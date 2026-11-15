package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.intake.*;
import com.company.platform.archive.*;
import com.company.platform.workflow.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只在本机新建隔离库执行；真实目录、授权、固定路线、原生扫描和水印进程贯通。 */
@EnabledIfEnvironmentVariable(named="IMS_INTAKE_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class},webEnvironment=SpringBootTest.WebEnvironment.NONE,
 properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntakeIntegrationTest {
    /** 台账与扫描链路使用实际服务和独立 PostgreSQL。 */
    @Autowired ArchiveChecklists checklists; @Autowired ImportService imports; @Autowired ScanIntake scans; @Autowired ScanUpload scanUploads; @Autowired ScanIdentity scanIdentity;
    @Autowired ArchiveFileTypes fileTypes;
    private final UUID platformScanner=UUID.fromString("7711a6a9-532a-4bde-a060-30f7759eac74");
    /** 现有归档复核与权限服务用于跨模块回归。 */
    @Autowired WorkflowEvidence consent; @Autowired PersonnelContinuity continuity;
    @Autowired PhysicalLoans loans;
    @Autowired CatalogMaintenance maintenance; @Autowired CatalogTemplates templateService; @Autowired com.company.platform.operations.BusinessNotifications messages;
    /** 业务服务全部使用正式实现；替换范围仅为测试专用无害签名库。 */
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired ArchiveCatalogService catalog;@Autowired ArchiveAuthority authority;
    @Autowired ArchivePolicyService policies;@Autowired BorrowService borrow;@Autowired WorkflowEngine workflow;@Autowired ArchiveReadingService reading;
    @Autowired UploadService uploads;@Autowired UploadAssembly assembly;@Autowired FileInspectionService inspection;
    @Autowired OrganizationDuties organization;
    @Autowired StorageFiles storageFiles;
    /** 真实到期状态和站内通知工作器，测试不会替换授权判定。 */
    @Autowired BorrowLifecycleService lifecycle;
    /** 改派必须同时满足真实任职与当前模块权限，不只检查角色名称。 */
    @Autowired WorkflowDuties dutyResolver;
    /** 每次测试独立建档；统一合成部门和员工不含正式业务数据。 */
    private UUID a,b,templateBox,templateRecord;private String password;private final Map<String,UUID> people=new HashMap<>();private final Map<UUID,Actor> actors=new HashMap<>();private Actor admin,supervisor,applicant,archivistA,archivistB;
    private final UUID scanner=UUID.fromString("42210210-46cc-4ce8-8624-5c0da61a7760");
    /** 验证地址后建立合成员工；工作日历通过正式API服务发布，不假定生产周末规则。 */
    @BeforeEach void setup() throws Exception {
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:(55448|55449)/ims_platform_dev_[0-9]+"));password=Crypto.token();admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence(false);
        // 复跑仅结束本专用库上一轮合成超级管理员任职，历史批准和文件全部保留。
        db.update("UPDATE iam.user_role a SET state='ENDED',valid_until=CURRENT_TIMESTAMP FROM iam.user_account u WHERE a.user_id=u.id AND a.module='ARCHIVE' AND a.role_code='SUPER_ADMIN' AND a.state='ACTIVE' AND u.employee_no LIKE 'AR-%'");
        a=department("A");b=department("B");person("super",a,"SUPER_ADMIN");person("archA",a,"DEPARTMENT_ARCHIVIST");person("archB",b,"DEPARTMENT_ARCHIVIST");person("managerA",a,"STAFF");person("managerB",b,"STAFF");person("gm",a,"STAFF");person("applicant",a,"STAFF");person("second",a,"STAFF");person("outsider",b,"STAFF");
        for(var pair:Map.of(a,people.get("managerA"),b,people.get("managerB")).entrySet()) db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'合成验收',?)",UUID.randomUUID(),pair.getKey(),pair.getValue(),admin.userId());
        // 公司总经理职责与公盘角色分离，避免拿公盘管理员当档案批准人。
        db.update("UPDATE iam.company_duty SET valid_until=CURRENT_TIMESTAMP WHERE duty_code='GENERAL_MANAGER' AND valid_until IS NULL");db.update("INSERT INTO iam.company_duty(id,duty_code,user_id,valid_from,changed_by,change_reason) VALUES(?,'GENERAL_MANAGER',?,CURRENT_TIMESTAMP,?,'合成验收')",UUID.randomUUID(),people.get("gm"),admin.userId());
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','台账隔离检测','PLATFORM',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope,valid_until=EXCLUDED.valid_until",platformScanner,"{"+a+","+b+"}");
        refreshActors();
        templateBox=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","BOX_"+UUID.randomUUID(),"name","电子档案盒基础模板","categoryCode","ADMIN","objectType","BOX","fields",List.of())).get("id");
        templateRecord=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","RECORD_"+UUID.randomUUID(),"name","电子文件基础模板","categoryCode","ADMIN","objectType","RECORD","fields",List.of())).get("id");
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','档案隔离扫描','ARCHIVE',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope,valid_until=EXCLUDED.valid_until",scanner,"{"+a+","+b+"}");
    }
    private UUID department(String prefix) {UUID id=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",id,prefix+id.toString().substring(0,7).toUpperCase(Locale.ROOT),"合成部门"+prefix);return id;}
    private void person(String key,UUID department,String roleCode) {UUID id=UUID.randomUUID();people.put(key,id);String no="AR-"+id;db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,"验收"+key,department,no.toLowerCase(Locale.ROOT)+"@biolink.com",Crypto.password(password));UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='ARCHIVE' AND code=?",roleCode).get("id");db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,'ARCHIVE',?)",UUID.randomUUID(),id,role,roleCode.equals("DEPARTMENT_ARCHIVIST")?department:null,admin.userId(),roleCode);}
    private void refreshActors() {for(var person:people.entrySet()) actors.put(person.getValue(),login("AR-"+person.getValue(),password));supervisor=actors.get(people.get("super"));applicant=actors.get(people.get("applicant"));archivistA=actors.get(people.get("archA"));archivistB=actors.get(people.get("archB"));}
    private Actor login(String no,String secret) {return identity.authenticate((String)identity.login(Sql.map("employeeNo",no,"password",secret),UUID.randomUUID(),"archive-test").get("token"));}
    /** 凭据按本人、对象及精确动作签发；普通借阅无需高风险管理凭据。 */
    private Commands.Context context(Actor actor,String type,UUID target,String action,Integer version) {String secret=actor.userId().equals(admin.userId())?System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"):password;var proof=identity.reauthenticate(actor,Sql.map("password",secret,"purpose","MANAGE","objectIds",List.of(target.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",target.toString(),"actions",List.of(action)))),UUID.randomUUID());return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action+" "+target,(String)proof.get("verificationToken"),version);}
    private Commands.Context plain(Actor actor,String action) {return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action,null,null);}
    /** 只签发合成保护事实以验收消费者；这不是生产备份和主备事实。 */
    private void evidence(boolean expired) throws Exception {Path root=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")),key=root.resolve("protection.key");if(!Files.exists(key)){Files.writeString(key,Crypto.token(),StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(key);}Instant now=Instant.now().minusSeconds(expired?120:0);var state=Sql.map("instanceId",db.required("SELECT id FROM platform_meta.instance").get("id").toString(),"nodeId","archive-isolated-node","epoch",1,"issuedAt",now.toString(),"expiresAt",now.plusSeconds(60).toString(),"mode","NORMAL","oldPrimaryFenced",true,"replicaVerifiedAt",now.toString(),"backupId",UUID.randomUUID().toString(),"backupProtectedAt",now.toString(),"writableModules",List.of("ARCHIVE","DRIVE","PLATFORM"));state.put("signature",Crypto.hmac(Files.readString(key),Crypto.json(state)));Files.writeString(root.resolve("protection.json"),Crypto.json(state));}
    /** 逐必要节点调用真实决定事务；不会直接修改流程为APPROVED。 */
    private void approve(UUID instance) {for(int round=0;round<10;round++){var flow=db.required("SELECT * FROM workflow.instance WHERE id=?",instance);if(flow.get("current_step_id")==null){assertEquals("APPROVED",flow.get("state"));return;}UUID id=(UUID)flow.get("current_step_id");var step=db.required("SELECT * FROM workflow.step WHERE id=?",id);Actor actor=actors.get(step.get("assignee_id"));assertNotNull(actor);workflow.decide(context(actor,"WORKFLOW_STEP",id,"DECIDE_STEP",((Number)step.get("row_version")).intValue()),id,Sql.map("result","APPROVE","comment","合成验收同意"));}fail("流程未在固定节点数内结束");}
    private Map<String,Object> objectRef(String type,UUID id,UUID pdf) {var row=db.required("SELECT * FROM "+(type.equals("BOX")?"archive.box":"archive.record")+" WHERE id=?",id);return Sql.map("objectType",type,"objectId",id.toString(),"expectedVersion",row.get("row_version"),"candidateRevisionId",row.get("candidate_revision_id").toString(),"candidatePdfVersionId",pdf==null?null:pdf.toString());}
    private UUID review(Actor actor,String type,UUID id,UUID pdf) {var ref=objectRef(type,id,pdf);int pages=pdf==null?0:((Number)db.required("SELECT page_count FROM archive.pdf_version WHERE id=?",pdf).get("page_count")).intValue();UUID quality=(UUID)catalog.quality(context(actor,type.equals("BOX")?"ARCHIVE_BOX":"ARCHIVE_RECORD",id,"CONFIRM_QUALITY",null),Sql.map("objectType",type,"objectId",id.toString(),"candidateRevisionId",ref.get("candidateRevisionId"),"candidatePdfVersionId",ref.get("candidatePdfVersionId"),"checkedPageCount",pages,"confirmed",true,"notes","合成人工确认完整页序与清晰度")).get("id");return (UUID)catalog.submit(context(actor,type.equals("BOX")?"ARCHIVE_BOX":"ARCHIVE_RECORD",id,"SUBMIT_REGISTRATION",null),Sql.map("objectRefs",List.of(ref),"qualityEvidenceRef",quality.toString())).get("id");}

    /** 清单份数保留纸质含义，真实分片检测、PDF解析、整盒审批与重放贯通。 */
    @Test void electronicChecklistUploadAndBoxApproval()throws Exception{
        var batch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(ledgerRow(b,people.get("archB")))),List.of(b));confirm(batch,"ARCHIVE",archivistB);UUID box=imported((UUID)batch.get("id"),"BOX");
        byte[] workbook=ArchiveChecklistWorkbookTest.workbook(false,List.of(List.of("10","2025.4.17","合成合同十","45","纸质四十五份"),List.of("2","","合成合同二","2","")));
        UUID listUpload=checklistUpload(box,"ARCHIVE_CHECKLIST","电子清单.xlsx",workbook);var preview=checklists.preview(archivistB,box,listUpload);var body=Sql.map("uploadSessionId",listUpload.toString(),"digest",preview.get("digest"));var ctx=context(archivistB,"ARCHIVE_BOX",box,"IMPORT_CHECKLIST",((Number)preview.get("rowVersion")).intValue());
        checklists.importChecklist(ctx,box,body);checklists.importChecklist(ctx,box,body);assertEquals(2,db.count("SELECT count(*) FROM archive.record WHERE box_id=?",box));assertThrows(ApiFault.class,()->checklists.items(archivistA,box));
        var records=db.rows("SELECT * FROM archive.record WHERE box_id=? ORDER BY item_no",box);assertEquals(2,records.getFirst().get("item_no"));assertEquals(45,CatalogTemplates.map(records.getLast().get("extension_values")).get("copyCount"));
        var duplicates=checklists.preview(archivistB,box,listUpload);assertEquals(2,duplicates.get("skipped"));assertTrue(((List<?>)duplicates.get("items")).isEmpty());assertThrows(ApiFault.class,()->review(archivistB,"BOX",box,null));
        for(var record:records){evidence(false);UUID id=(UUID)record.get("id"),upload=checklistUpload(id,"ARCHIVE_ATTACHMENT",record.get("item_no")+"_合同.pdf",pdf());var parent=db.required("SELECT row_version FROM archive.box WHERE id=?",box);var bind=Sql.map("items",List.of(Sql.map("recordId",id.toString(),"uploadSessionId",upload.toString(),"expectedVersion",record.get("row_version"))));var binding=context(archivistB,"ARCHIVE_BOX",box,"UPLOAD_CHECKLIST_DOCUMENTS",((Number)parent.get("row_version")).intValue());checklists.bind(binding,box,bind);checklists.bind(binding,box,bind);checklists.prepare(archivistB,id);checklists.prepare(archivistB,id);assertEquals(1,db.count("SELECT count(*) FROM archive.electronic_version WHERE record_id=?",id));assertEquals(1,db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",id));var ref=objectRef("RECORD",id,(UUID)db.required("SELECT p.id FROM archive.pdf_version p WHERE record_id=?",id).get("id"));catalog.quality(context(archivistB,"ARCHIVE_RECORD",id,"CONFIRM_QUALITY",null),Sql.map("objectType","RECORD","objectId",id.toString(),"candidateRevisionId",ref.get("candidateRevisionId"),"candidatePdfVersionId",ref.get("candidatePdfVersionId"),"checkedPageCount",2,"confirmed",true,"notes","逐页合成确认"));}
        evidence(false);UUID flow=review(archivistB,"BOX",box,null);for(var record:records){UUID id=(UUID)record.get("id"),pdf=(UUID)db.required("SELECT pdf_version_id FROM archive.electronic_version WHERE record_id=?",id).get("pdf_version_id");assertEquals("PENDING_REVIEW",authority.record(id).get("state"));assertNotNull(authority.review(archivistB,"REGISTRATION",flow,pdf));}
        var pending=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);
        Actor reviewer=actors.get(pending.get("assignee_id"));
        UUID pdf=(UUID)db.required("SELECT pdf_version_id FROM archive.electronic_version WHERE record_id=?",records.getFirst().get("id")).get("pdf_version_id");
        var usage=reading.create(reviewer,Sql.map("reviewContext",Sql.map("type","REGISTRATION","id",flow.toString(),"candidatePdfVersionId",pdf.toString())),UUID.randomUUID(),UUID.randomUUID());
        reading.process(reviewer,(UUID)usage.get("jobId"),UUID.randomUUID());
        assertEquals(1L,reading.manifest(reviewer,(UUID)usage.get("usageId")).get("versionNo"));
        assertTrue(reading.page(reviewer,(UUID)usage.get("usageId"),2,UUID.randomUUID()).length>100);
        UUID step=(UUID)pending.get("id");
        workflow.decide(context(reviewer,"WORKFLOW_STEP",step,"DECIDE_STEP",((Number)pending.get("row_version")).intValue()),step,Sql.map("result","REJECT","comment","合成退回核对"));
        assertEquals(2,db.count("SELECT count(*) FROM archive.record WHERE box_id=? AND state='DRAFT'",box));

        approve(review(archivistB,"BOX",box,null));
        assertEquals(2,db.count("SELECT count(*) FROM archive.record WHERE box_id=? AND state='PUBLISHED' AND current_pdf_version_id IS NOT NULL",box));
        var published=authority.record((UUID)records.getFirst().get("id"));
        assertEquals(403,assertThrows(ApiFault.class,()->reading.create(admin,Sql.map("recordRevisionId",published.get("current_revision_id").toString(),
            "pdfVersionId",published.get("current_pdf_version_id").toString()),UUID.randomUUID(),UUID.randomUUID())).status);
        assertThrows(ApiFault.class,()->checklists.preview(archivistB,box,listUpload));
    }
    /** 错盒清单、错版本、重复对应不会写入部分数据；修改删除仍保留编号与历史。 */
    @Test void electronicChecklistRejectsConflictsAndKeepsHistory()throws Exception{
        var batch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(ledgerRow(b,people.get("archB")))),List.of(b));confirm(batch,"ARCHIVE",archivistB);UUID box=imported((UUID)batch.get("id"),"BOX");
        UUID wrong=checklistUpload(box,"ARCHIVE_CHECKLIST","错盒清单.xlsx",ArchiveChecklistWorkbookTest.workbook(true,List.of(List.of("OTHER-BOX","1","","合同","2",""))));assertThrows(ApiFault.class,()->checklists.preview(archivistB,box,wrong));assertEquals(0,db.count("SELECT count(*) FROM archive.record WHERE box_id=?",box));
        UUID upload=checklistUpload(box,"ARCHIVE_CHECKLIST","有效清单.xlsx",ArchiveChecklistWorkbookTest.workbook(false,List.of(List.of("1","","合同","2","备注"))));var preview=checklists.preview(archivistB,box,upload);var body=Sql.map("uploadSessionId",upload.toString(),"digest",preview.get("digest"));assertThrows(ApiFault.class,()->checklists.importChecklist(context(archivistB,"ARCHIVE_BOX",box,"IMPORT_CHECKLIST",999999),box,body));checklists.importChecklist(context(archivistB,"ARCHIVE_BOX",box,"IMPORT_CHECKLIST",((Number)preview.get("rowVersion")).intValue()),box,body);
        UUID record=(UUID)db.required("SELECT id FROM archive.record WHERE box_id=?",box).get("id");var metadata=maintenance.detail(archivistB,"RECORD",record);maintenance.updateDraft(context(archivistB,"ARCHIVE_RECORD",record,"UPDATE_DRAFT",((Number)metadata.get("rowVersion")).intValue()),"RECORD",record,Sql.map("changes",Sql.map("notes","修改后的完整备注"),"reason","合成修订"));assertEquals("修改后的完整备注",authority.record(record).get("notes"));
        var parent=maintenance.detail(archivistB,"BOX",box);var values=new LinkedHashMap<>(CatalogTemplates.map(parent.get("extensionValues")));values.put("cabinetNo","17");maintenance.updateDraft(context(archivistB,"ARCHIVE_BOX",box,"UPDATE_DRAFT",((Number)parent.get("rowVersion")).intValue()),"BOX",box,Sql.map("changes",Sql.map("extensionValues",values),"reason","调整存放柜"));assertEquals("17柜 / A列 / 3层",db.required("SELECT location FROM archive.box WHERE id=?",box).get("location"));assertEquals(2,db.count("SELECT count(*) FROM archive.physical_item WHERE box_id=? AND location='17柜 / A列 / 3层'",box));
        metadata=maintenance.detail(archivistB,"RECORD",record);maintenance.deleteDraft(context(archivistB,"ARCHIVE_RECORD",record,"DELETE_DRAFT",((Number)metadata.get("rowVersion")).intValue()),"RECORD",record,Sql.map("reason","清单误登记"));assertEquals("WITHDRAWN",authority.record(record).get("state"));assertTrue(db.count("SELECT count(*) FROM archive.record_revision WHERE record_id=?",record)>1);assertEquals(0,((List<?>)checklists.items(archivistB,box).get("items")).size());
    }

    /** 同一盒先单条再批量追加，原序号、原记录和重复预览均可复验。 */
    @Test void checklistAppendsOnlyNewRowsAndPreservesExistingItems()throws Exception{
        var batch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(ledgerRow(b,people.get("archB")))),List.of(b));
        confirm(batch,"ARCHIVE",archivistB);
        UUID box=imported((UUID)batch.get("id"),"BOX");
        var original=List.of("4","2021","原合同","2","原件");
        UUID first=checklistUpload(box,"ARCHIVE_CHECKLIST","首次清单.xlsx",ArchiveChecklistWorkbookTest.workbook(false,List.of(original)));
        var initial=checklists.preview(archivistB,box,first);
        assertEquals(false,initial.get("append"));
        confirmChecklist(box,first,initial);
        var originalRecord=db.required("SELECT id,file_no,item_no,checklist_id,row_version,candidate_revision_id FROM archive.record WHERE box_id=?",box);
        assertEquals(4,originalRecord.get("item_no"));

        var newRow=List.of("1","2025.4.17","后补合同","1","");
        UUID single=checklistUpload(box,"ARCHIVE_CHECKLIST","单条补录.xlsx",ArchiveChecklistWorkbookTest.workbook(false,List.of(newRow)));
        UUID multiple=checklistUpload(box,"ARCHIVE_CHECKLIST","多条补录.xlsx",ArchiveChecklistWorkbookTest.workbook(false,List.of(
            List.of("1","2021.0","原合同","2.0","原件"),newRow,List.of("1","","新合同","1",""),
            List.of("8","","新合同","1",""),List.of("2","","新合同","2","不同纸质份数"))));

        var stale=checklists.preview(archivistB,box,multiple);
        var singlePreview=checklists.preview(archivistB,box,single);
        assertEquals(true,singlePreview.get("append"));
        assertEquals(List.of(5),previewSequences(singlePreview));
        confirmChecklist(box,single,singlePreview);
        assertEquals(409,assertThrows(ApiFault.class,()->confirmChecklist(box,multiple,stale)).status);

        var preview=checklists.preview(archivistB,box,multiple);
        assertEquals(3,preview.get("skipped"));
        assertEquals(List.of(6,7),previewSequences(preview));
        confirmChecklist(box,multiple,preview);
        assertEquals(List.of(4,5,6,7),db.rows("SELECT item_no FROM archive.record WHERE box_id=? ORDER BY item_no",box)
            .stream().map(row->row.get("item_no")).toList());
        assertEquals(originalRecord,db.required("SELECT id,file_no,item_no,checklist_id,row_version,candidate_revision_id FROM archive.record WHERE id=?",originalRecord.get("id")));
        var duplicateOnly=checklists.preview(archivistB,box,multiple);
        assertEquals(5,duplicateOnly.get("skipped"));
        assertTrue(previewSequences(duplicateOnly).isEmpty());
        confirmChecklist(box,multiple,duplicateOnly);
        assertEquals(duplicateOnly.get("rowVersion"),checklists.preview(archivistB,box,multiple).get("rowVersion"));
        assertEquals(3,db.count("SELECT count(*) FROM archive.electronic_checklist WHERE box_id=?",box));
    }

    /** 补录进度在维护范围内筛选，标记不推进目录修订。 */
    @Test void legacyLedgerStatusFiltersAndMarksBoxes() {
        var body=Sql.map("departmentId",b.toString(),"ownerId",archivistB.userId().toString(),
            "classification","GENERAL","carrierType","ELECTRONIC","retentionCode","10",
            "archiveDate","2026-11-12","numberMonth","2026-11-01","categoryCode","ADMIN",
            "templateVersionId",templateBox.toString(),"keywords",List.of("测试"));
        body.put("title","待补录台账");
        var pending=catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),body);
        body.put("title","已核对台账");
        var completed=catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),body);
        body.put("title","无原台账");
        var notRequired=catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),body);
        assertEquals("PENDING",completed.get("legacyLedgerStatus"));
        UUID completedId=Inputs.uuid(completed.get("id"));
        var mark=Sql.map("status","COMPLETED","expectedStatus","PENDING");
        assertEquals(403,assertThrows(ApiFault.class,
            ()->catalog.markLegacyLedger(plain(archivistA,"SET_LEGACY_LEDGER_STATUS"),completedId,mark)).status);
        var ctx=plain(archivistB,"SET_LEGACY_LEDGER_STATUS");
        var marked=catalog.markLegacyLedger(ctx,completedId,mark);
        assertEquals("COMPLETED",marked.get("legacyLedgerStatus"));
        assertEquals(completed.get("rowVersion"),marked.get("rowVersion"));
        assertEquals(completed.get("candidateRevisionId"),marked.get("candidateRevisionId"));
        assertEquals(marked,catalog.markLegacyLedger(ctx,completedId,mark));
        assertEquals(409,assertThrows(ApiFault.class,
            ()->catalog.markLegacyLedger(plain(archivistB,"SET_LEGACY_LEDGER_STATUS"),completedId,mark)).status);
        var skipped=catalog.markLegacyLedger(plain(archivistB,"SET_LEGACY_LEDGER_STATUS"),
            Inputs.uuid(notRequired.get("id")),Sql.map("status","NOT_REQUIRED","expectedStatus","PENDING"));
        assertEquals("NOT_REQUIRED",skipped.get("legacyLedgerStatus"));
        var filtered=catalog.boxes(archivistB,Map.of("legacyLedgerStatus","PENDING","pageSize","1"));
        assertEquals(1L,filtered.get("total"));
        assertEquals(List.of(pending),filtered.get("items"));
        assertEquals(0L,catalog.boxes(archivistA,Map.of("legacyLedgerStatus","PENDING")).get("total"));
    }

    /** 只有 IT 可以追加选项，档案员可用新选项登记；原台账导入保留自由文本。 */
    @Test void fileTypeOptionsAreManagedByItAndUsedInRegistration() throws Exception {
        var options=(List<?>)fileTypes.options(admin).get("items");
        assertEquals(List.of("合同","行政文件","台账","通知","会议纪要","人事档案","项目建设资料","质量文件","其他"),options.subList(0,9));
        String name="验收类型-"+UUID.randomUUID();
        var body=Sql.map("name"," "+name+" ");
        assertEquals(403,assertThrows(ApiFault.class,()->fileTypes.add(
            context(supervisor,"COLLECTION",ApiController.COLLECTION,"CREATE_ARCHIVE_FILE_TYPE",null),body)).status);
        var add=context(admin,"COLLECTION",ApiController.COLLECTION,"CREATE_ARCHIVE_FILE_TYPE",null);
        var added=fileTypes.add(add,body);
        assertEquals(name,added.get("name"));
        assertEquals(added,fileTypes.add(add,body));
        assertEquals(409,assertThrows(ApiFault.class,()->fileTypes.add(
            context(admin,"COLLECTION",ApiController.COLLECTION,"CREATE_ARCHIVE_FILE_TYPE",null),Sql.map("name",name))).status);
        assertTrue(((List<?>)catalog.options(archivistB).get("fileTypes")).contains(name));

        UUID template=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_BOX_LEDGER' AND state='ACTIVE' ORDER BY version_no DESC LIMIT 1").get("id");
        var registration=Sql.map("departmentId",b.toString(),"ownerId",archivistB.userId().toString(),
            "title","文件类型测试","classification","GENERAL","carrierType","ELECTRONIC",
            "retentionCode","10","archiveDate","2026-11-12","numberMonth","2026-11-01",
            "categoryCode","COMMON","templateVersionId",template.toString(),"keywords",List.of("测试"),
            "extensionValues",Sql.map("fileType","未配置类型"));
        assertThrows(ApiFault.class,()->catalog.box(
            context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),registration));
        registration.put("extensionValues",Sql.map("fileType",name));
        var created=catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),registration);
        assertEquals(name,((Map<?,?>)created.get("extensionValues")).get("fileType"));

        var original=new LinkedHashMap<>(ledgerRow(b,people.get("archB")));
        original.put("fileType","历史自由类型");
        var batch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(original)),List.of(b));
        confirm(batch,"ARCHIVE",archivistB);
        UUID importedBox=imported((UUID)batch.get("id"),"BOX");
        assertEquals("历史自由类型",db.required("SELECT extension_values->>'fileType' AS name FROM archive.box WHERE id=?",importedBox).get("name"));
    }

    /** 登记的原件与条目同事务挂接，补录沿用盒内序号和版本。 */
    @Test void registrationChecklistAndManualAppend() throws Exception {
        UUID template=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_BOX_LEDGER' AND state='ACTIVE' ORDER BY version_no DESC LIMIT 1").get("id");
        var body=Sql.map("departmentId",b.toString(),"ownerId",archivistB.userId().toString(),
            "title","登记清单测试","classification","GENERAL","carrierType","ELECTRONIC",
            "retentionCode","10","archiveDate","2026-11-11","numberMonth","2026-11-01",
            "categoryCode","COMMON","templateVersionId",template.toString(),"keywords",List.of("测试"),
            "extensionValues",Sql.map("copyCount",0,"originalNumber","OLD-REG"));
        long before=db.count("SELECT count(*) FROM archive.box WHERE department_id=?",b);
        var negative=new LinkedHashMap<>(body);
        negative.put("extensionValues",Sql.map("copyCount",-1));
        assertThrows(ApiFault.class,()->catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),negative));
        assertEquals(before,db.count("SELECT count(*) FROM archive.box WHERE department_id=?",b));

        UUID wrong=checklistUpload(b,"ARCHIVE_REGISTRATION_CHECKLIST","错盒清单.xlsx",
            ArchiveChecklistWorkbookTest.workbook(true,List.of(List.of("OTHER-BOX","1","","合同","1",""))));
        body.put("checklistUploadSessionId",wrong.toString());
        assertThrows(ApiFault.class,()->checklists.registerBox(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),body));
        assertEquals(before,db.count("SELECT count(*) FROM archive.box WHERE department_id=?",b));

        UUID upload=checklistUpload(b,"ARCHIVE_REGISTRATION_CHECKLIST","登记清单.xlsx",
            ArchiveChecklistWorkbookTest.workbook(true,List.of(List.of("OLD-REG","4","2021","原合同","2",""))));
        assertThrows(ApiFault.class,()->checklists.registrationPreview(archivistA,b,upload));
        assertEquals(List.of(4),previewSequences(checklists.registrationPreview(archivistB,b,upload)));
        body.put("checklistUploadSessionId",upload.toString());
        var registration=context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null);
        var created=checklists.registerBox(registration,body);
        UUID box=Inputs.uuid(created.get("id"));
        assertEquals(box,Inputs.uuid(checklists.registerBox(registration,body).get("id")));
        assertEquals(before+1,db.count("SELECT count(*) FROM archive.box WHERE department_id=?",b));
        assertEquals(1,db.count("SELECT count(*) FROM archive.electronic_checklist c JOIN storage.object_reference r ON r.owner_id=c.id AND r.object_id=c.object_id WHERE c.box_id=?",box));
        assertEquals(0,((Map<?,?>)created.get("extensionValues")).get("copyCount"));

        int version=((Number)created.get("rowVersion")).intValue();
        var existingItem=Sql.map("title","原合同","date","2025-04-17","paperCopies",3,"notes","不同备注");
        var importedDuplicate=assertThrows(ApiFault.class,()->checklists.appendItem(
            context(archivistB,"ARCHIVE_BOX",box,"APPEND_CHECKLIST_ITEM",version),box,existingItem));
        assertEquals(409,importedDuplicate.status);
        assertEquals("当前档案盒已有同名清单条目，请核对存放内容",importedDuplicate.getMessage());

        var item=Sql.map("title","补录合同","date","2025-04-17","signingEntity","测试主体",
            "contractNumber","0007","paperCopies",3,"notes","补录说明");
        assertThrows(ApiFault.class,()->checklists.appendItem(
            context(archivistB,"ARCHIVE_BOX",box,"APPEND_CHECKLIST_ITEM",version-1),box,item));
        var append=context(archivistB,"ARCHIVE_BOX",box,"APPEND_CHECKLIST_ITEM",version);
        checklists.appendItem(append,box,item);
        checklists.appendItem(append,box,item);
        int updatedVersion=(int)db.count("SELECT row_version FROM archive.box WHERE id=?",box);
        var sameTitle=Sql.map("title","补录合同","date","2026","paperCopies",1);
        var manualDuplicate=assertThrows(ApiFault.class,()->checklists.appendItem(
            context(archivistB,"ARCHIVE_BOX",box,"APPEND_CHECKLIST_ITEM",updatedVersion),box,sameTitle));
        assertEquals(409,manualDuplicate.status);
        assertEquals("当前档案盒已有同名清单条目，请核对存放内容",manualDuplicate.getMessage());
        assertEquals(updatedVersion,db.count("SELECT row_version FROM archive.box WHERE id=?",box));
        assertEquals(2,db.count("SELECT count(*) FROM archive.electronic_checklist WHERE box_id=?",box));
        assertEquals(List.of(4,5),db.rows("SELECT item_no FROM archive.record WHERE box_id=? ORDER BY item_no",box)
            .stream().map(row->row.get("item_no")).toList());
        assertEquals(1,db.count("SELECT count(*) FROM archive.record r JOIN archive.electronic_checklist c ON c.id=r.checklist_id WHERE r.box_id=? AND r.item_no=5 AND c.object_id IS NULL AND r.extension_values->>'contractNumber'='0007'",box));
    }

    private void confirmChecklist(UUID box,UUID upload,Map<String,Object> preview){
        var body=Sql.map("uploadSessionId",upload.toString(),"digest",preview.get("digest"));
        var confirmation=context(archivistB,"ARCHIVE_BOX",box,"IMPORT_CHECKLIST",((Number)preview.get("rowVersion")).intValue());
        checklists.importChecklist(confirmation,box,body);
    }

    private List<Integer> previewSequences(Map<String,Object> preview){
        return ((List<?>)preview.get("items")).stream().map(ArchiveChecklistWorkbook.Entry.class::cast)
            .map(ArchiveChecklistWorkbook.Entry::sequence).toList();
    }

    /** 八列合同清单真实导入，主体和编号可维护，年份不会补造日期，旧模板不被改写。 */
    @Test void contractChecklistPreservesFieldsYearAndBoxOwnership()throws Exception{
        var ledger=ledgerRow(b,people.get("archB"));var batch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(ledger)),List.of(b));confirm(batch,"ARCHIVE",archivistB);UUID box=imported((UUID)batch.get("id"),"BOX");
        var headers=List.of("存放编号","序号","日期","签订主体","存放内容","合同编号","存档份数","备注");String sourceNumber=ledger.get("originalNumber").toString();
        UUID wrong=checklistUpload(box,"ARCHIVE_CHECKLIST","错盒合同清单.xlsx",ArchiveChecklistWorkbookTest.workbook(headers,List.of(List.of("OTHER-BOX","1","2021","合成主体","合同","0001","2","原件")),1));var rejected=assertThrows(ApiFault.class,()->checklists.preview(archivistB,box,wrong));assertTrue(rejected.getMessage().contains("OTHER-BOX"));assertTrue(rejected.getMessage().contains(db.required("SELECT box_no FROM archive.box WHERE id=?",box).get("box_no").toString()));assertEquals(0,db.count("SELECT count(*) FROM archive.record WHERE box_id=?",box));
        UUID upload=checklistUpload(box,"ARCHIVE_CHECKLIST","合同清单.xlsx",ArchiveChecklistWorkbookTest.workbook(headers,List.of(List.of(sourceNumber,"2","2022.5.26","合成主体","合同二","0002","1","原件"),List.of(sourceNumber,"1","2021","合成主体","合同一","0001-A","2","原件")),1));var preview=checklists.preview(archivistB,box,upload);
        checklists.importChecklist(context(archivistB,"ARCHIVE_BOX",box,"IMPORT_CHECKLIST",((Number)preview.get("rowVersion")).intValue()),box,Sql.map("uploadSessionId",upload.toString(),"digest",preview.get("digest")));
        var records=db.rows("SELECT * FROM archive.record WHERE box_id=? ORDER BY item_no",box);assertEquals(2,records.size());var first=records.getFirst();assertNull(first.get("formed_date"));var values=CatalogTemplates.map(first.get("extension_values"));assertEquals("2021",values.get("documentYear"));assertEquals("合成主体",values.get("signingEntity"));assertEquals("0001-A",values.get("contractNumber"));assertEquals(2,values.get("copyCount"));assertEquals("2022-05-26",records.getLast().get("formed_date").toString());
        UUID id=(UUID)first.get("id");var detail=maintenance.detail(archivistB,"RECORD",id);var edited=new LinkedHashMap<>(values);edited.put("contractNumber","0001-B");maintenance.updateDraft(context(archivistB,"ARCHIVE_RECORD",id,"UPDATE_DRAFT",((Number)detail.get("rowVersion")).intValue()),"RECORD",id,Sql.map("changes",Sql.map("extensionValues",edited),"reason","更正合同编号"));assertEquals("0001-B",CatalogTemplates.map(authority.record(id).get("extension_values")).get("contractNumber"));
        assertEquals(0,db.count("SELECT count(*) FROM archive.directory_template WHERE template_key='COMMON_RECORD_LEDGER' AND version_no=1 AND fields @> '[{\"code\":\"contractNumber\"}]'::jsonb"));assertEquals(1,db.count("SELECT count(*) FROM archive.directory_template WHERE id=? AND fields @> '[{\"code\":\"contractNumber\"}]'::jsonb",first.get("template_version_id")));assertTrue(db.count("SELECT count(*) FROM archive.record_revision WHERE record_id=?",id)>1);
    }
    /** 同一清单重新上传追加版本；删除草稿不删除两次上传的原件和PDF历史。 */
    @Test void electronicReplacementKeepsOriginalVersions()throws Exception{
        var batch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(ledgerRow(b,people.get("archB")))),List.of(b));confirm(batch,"ARCHIVE",archivistB);UUID box=imported((UUID)batch.get("id"),"BOX");UUID list=checklistUpload(box,"ARCHIVE_CHECKLIST","清单.xlsx",ArchiveChecklistWorkbookTest.workbook(false,List.of(List.of("1","","合同","45",""))));var preview=checklists.preview(archivistB,box,list);checklists.importChecklist(context(archivistB,"ARCHIVE_BOX",box,"IMPORT_CHECKLIST",((Number)preview.get("rowVersion")).intValue()),box,Sql.map("uploadSessionId",list.toString(),"digest",preview.get("digest")));UUID record=(UUID)db.required("SELECT id FROM archive.record WHERE box_id=?",box).get("id");
        for(int number=1;number<=2;number++){UUID upload=checklistUpload(record,"ARCHIVE_ATTACHMENT","合同-第"+number+"版.pdf",pdf());var current=authority.record(record);int version=((Number)db.required("SELECT row_version FROM archive.box WHERE id=?",box).get("row_version")).intValue();checklists.bind(context(archivistB,"ARCHIVE_BOX",box,"UPLOAD_CHECKLIST_DOCUMENTS",version),box,Sql.map("items",List.of(Sql.map("recordId",record.toString(),"uploadSessionId",upload.toString(),"expectedVersion",current.get("row_version")))));if(number==2){UUID prior=(UUID)db.required("SELECT id FROM archive.pdf_version WHERE record_id=? ORDER BY version_no DESC LIMIT 1",record).get("id");assertThrows(ApiFault.class,()->authority.review(archivistB,"SCAN_QUALITY",record,prior));}checklists.prepare(archivistB,record);}
        assertEquals(2,db.count("SELECT count(*) FROM archive.electronic_version WHERE record_id=? AND state='READY'",record));assertEquals(2,db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",record));assertEquals(2,db.count("SELECT count(*) FROM storage.object_reference WHERE owner_type='ARCHIVE_ELECTRONIC_VERSION' AND reference_kind='BUSINESS' AND owner_id IN(SELECT id FROM archive.electronic_version WHERE record_id=?)",record));
        var versions=db.rows("SELECT * FROM archive.electronic_version WHERE record_id=? ORDER BY id",record);var pdfs=db.rows("SELECT * FROM archive.pdf_version WHERE record_id=? ORDER BY id",record);
        var objects=db.rows("SELECT * FROM storage.object WHERE id IN(SELECT original_object_id FROM archive.electronic_version WHERE record_id=? UNION SELECT object_id FROM archive.pdf_version WHERE record_id=?) ORDER BY id",record,record);
        var refs=db.rows("SELECT * FROM storage.object_reference WHERE object_id IN(SELECT original_object_id FROM archive.electronic_version WHERE record_id=? UNION SELECT object_id FROM archive.pdf_version WHERE record_id=?) ORDER BY id",record,record);
        var metadata=maintenance.detail(archivistB,"BOX",box);var summary=CatalogTemplates.map(metadata.get("draftDeletion"));assertEquals(1,((Number)summary.get("draftCount")).intValue());assertEquals(1,((Number)summary.get("attachmentCount")).intValue());
        maintenance.deleteDraft(context(archivistB,"ARCHIVE_BOX",box,"DELETE_DRAFT",((Number)metadata.get("rowVersion")).intValue()),"BOX",box,Sql.map("reason","整盒撤回验证保留原件及历史版本"));
        assertEquals("WITHDRAWN",maintenance.detail(archivistB,"BOX",box).get("state"));assertEquals("WITHDRAWN",authority.record(record).get("state"));
        assertEquals(versions,db.rows("SELECT * FROM archive.electronic_version WHERE record_id=? ORDER BY id",record));assertEquals(pdfs,db.rows("SELECT * FROM archive.pdf_version WHERE record_id=? ORDER BY id",record));
        assertEquals(objects,db.rows("SELECT * FROM storage.object WHERE id IN(SELECT original_object_id FROM archive.electronic_version WHERE record_id=? UNION SELECT object_id FROM archive.pdf_version WHERE record_id=?) ORDER BY id",record,record));
        assertEquals(refs,db.rows("SELECT * FROM storage.object_reference WHERE object_id IN(SELECT original_object_id FROM archive.electronic_version WHERE record_id=? UNION SELECT object_id FROM archive.pdf_version WHERE record_id=?) ORDER BY id",record,record));
        for(var object:objects)assertTrue(storageFiles.get().verified(object.get("storage_key").toString(),((Number)object.get("byte_size")).longValue(),object.get("sha256").toString()));
    }

    /** 文件先走真实接收、拼装与检测，不能手工伪造READY附件行。 */
    private UUID checklistUpload(UUID target,String purpose,String name,byte[] bytes)throws Exception{
        evidence(false);UUID upload=(UUID)uploads.create(archivistB,Sql.map("module","ARCHIVE","businessContextId",target.toString(),"purpose",purpose,"originalName",name,"expectedSize",bytes.length,"expectedSha256",hash(bytes),"expectedMediaType","application/octet-stream"),UUID.randomUUID(),UUID.randomUUID()).get("uploadId");
        uploads.part(archivistB,upload,1,"bytes 0-"+(bytes.length-1)+"/"+bytes.length,bytes.length,hash(bytes),new ByteArrayInputStream(bytes),UUID.randomUUID());UUID job=(UUID)uploads.complete(archivistB,upload,Sql.map("partCount",1,"finalSha256",hash(bytes)),UUID.randomUUID(),UUID.randomUUID()).get("jobId");assembly.assemble(archivistB,job,UUID.randomUUID());inspection.inspect(archivistB,job,UUID.randomUUID());assertEquals("COMPLETE",uploads.get(archivistB,upload).get("state"));return upload;
    }

    /** 正常档案导入只新增草稿，重复确认返回同一批次，旧号映射和实物记录完整。 */
    /** 权限范围先于分页生效：调离原部门后旧批次不计入统计。 */
    @Test void revokedDepartmentIsFilteredBeforePagination() throws Exception {
        preflight("ARCHIVE",archivistB,List.of(boxRow()));
        assertEquals(1L,imports.list(archivistB,Map.of("importType","ARCHIVE")).get("total"));
        db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",a,archivistB.userId());
        // 模拟已完成岗位交接，先结束接收部门原档案员，遵守唯一任职约束。
        db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE user_id=? AND role_code='DEPARTMENT_ARCHIVIST'",archivistA.userId());
        db.update("UPDATE iam.user_role SET department_id=? WHERE user_id=? AND role_code='DEPARTMENT_ARCHIVIST'",a,archivistB.userId());
        refreshActors();
        assertEquals(0L,imports.list(archivistB,Map.of("importType","ARCHIVE")).get("total"));
    }
    @Test void archiveAtomicImportAndReplay()throws Exception{var batch=preflight("ARCHIVE",archivistB,List.of(boxRow(),recordRow()));assertEquals("READY",batch.get("state"));long before=db.count("SELECT count(*) FROM archive.record");UUID id=(UUID)batch.get("id");var context=context(archivistB,"IMPORT_BATCH",id,"CONFIRM_IMPORT",1);var body=Sql.map("validationDigest",batch.get("validationDigest"));assertEquals("ALL_CREATED_AWAITING_REVIEW",imports.confirm(context,id,body).get("outcome"));imports.confirm(context,id,body);assertEquals(before+1,db.count("SELECT count(*) FROM archive.record"));assertEquals(2,db.count("SELECT count(*) FROM archive.legacy_number_map WHERE batch_id=?",id));UUID record=imported(id,"RECORD");assertEquals("DRAFT",authority.record(record).get("state"));assertNull(authority.record(record).get("current_revision_id"));assertEquals(1,db.count("SELECT count(*) FROM archive.physical_item WHERE record_id=?",record));}
    /** 单行字段错不会创建任意盒件，错误可定位到原表行。 */
    @Test void invalidRowWritesNoBusinessRecords()throws Exception{var bad=recordRow();bad.put("ownerEmployeeNo","missing");long before=db.count("SELECT count(*) FROM archive.box");var batch=preflight("ARCHIVE",archivistB,List.of(boxRow(),bad));assertEquals("INVALID",batch.get("state"));assertEquals(before,db.count("SELECT count(*) FROM archive.box"));assertEquals(1,db.count("SELECT count(*) FROM ops.import_row WHERE batch_id=? AND row_no=4 AND errors->0->>'field'='ownerEmployeeNo'",batch.get("id")));}
    /** 同一中文台账整批跨部门登记，保留来源字段、旧号和实物；重复提交不重复新增。 */
    @Test void commonBoxLedgerAcrossDepartmentsAndReplay()throws Exception{
        var first=ledgerRow(b,people.get("archB"));var second=ledgerRow(a,people.get("archA"));
        long before=db.count("SELECT count(*) FROM archive.box");var batch=preflight("ARCHIVE",supervisor,ArchiveBoxWorkbook.workbook(List.of(first,second)),List.of(a,b));assertEquals("READY",batch.get("state"));assertEquals(before,db.count("SELECT count(*) FROM archive.box"));
        UUID id=(UUID)batch.get("id");var ctx=context(supervisor,"IMPORT_BATCH",id,"CONFIRM_IMPORT",1);var body=Sql.map("validationDigest",batch.get("validationDigest"));imports.confirm(ctx,id,body);imports.confirm(ctx,id,body);assertEquals(before+2,db.count("SELECT count(*) FROM archive.box"));
        for(var row:db.rows("SELECT b.* FROM archive.box b JOIN ops.import_row r ON r.object_id=b.id WHERE r.batch_id=?",id)){
            assertEquals("DRAFT",row.get("state"));assertNull(row.get("current_revision_id"));assertEquals("COMMON",row.get("category_code"));assertEquals("PAPER",row.get("carrier_type"));assertEquals("柜 13 / 列 A / 层 3",row.get("location"));
            var values=CatalogTemplates.map(row.get("extension_values"));assertEquals(45,values.get("copyCount"));assertEquals("合同",values.get("fileType"));assertEquals("2035-09-21",values.get("sourceExpiryDate"));assertEquals("正常",values.get("sourceStatus"));assertEquals("2026-11-03 10:00",values.get("sourceUpdatedAt"));assertEquals(0,((Number)row.get("physical_file_count")).intValue());
            assertEquals("2035-09-23",row.get("expiry_date").toString());assertEquals(1,db.count("SELECT count(*) FROM archive.physical_item WHERE box_id=?",row.get("id")));
        }
        assertEquals(2,db.count("SELECT count(*) FROM archive.legacy_number_map WHERE batch_id=?",id));
    }
    /** 中文名称不会扩张权限，错误人员与跨范围部门使整批无业务写入。 */
    @Test void commonBoxLedgerRejectsUnknownOwnersAndOutsideDepartments()throws Exception{
        var good=ledgerRow(b,people.get("archB"));var bad=ledgerRow(a,people.get("archA"));long before=db.count("SELECT count(*) FROM archive.box");
        var batch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(good,bad)),List.of(b));assertEquals("INVALID",batch.get("state"));assertEquals(before,db.count("SELECT count(*) FROM archive.box"));assertThrows(ApiFault.class,()->confirm(batch,"ARCHIVE",archivistB));
        good.put("ownerName","系统中不存在的负责人");var ownerBatch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(good)),List.of(b));assertEquals("INVALID",ownerBatch.get("state"));assertEquals(before,db.count("SELECT count(*) FROM archive.box"));
    }
    /** 通用目录停用后旧预检不能继续提交，沿用原有版本和当前权限检查。 */
    @Test void commonBoxLedgerRechecksTemplateAtConfirmation()throws Exception{
        var batch=preflight("ARCHIVE",archivistB,ArchiveBoxWorkbook.workbook(List.of(ledgerRow(b,people.get("archB")))),List.of(b));assertEquals("READY",batch.get("state"));
        UUID template=UUID.fromString("cc450000-0000-4000-8000-000000000001");db.update("UPDATE archive.directory_template SET state='DISABLED' WHERE id=?",template);
        try{assertThrows(ApiFault.class,()->confirm(batch,"ARCHIVE",archivistB));assertEquals(0,db.count("SELECT count(*) FROM ops.import_row WHERE batch_id=? AND object_id IS NOT NULL",batch.get("id")));}finally{db.update("UPDATE archive.directory_template SET state='ACTIVE' WHERE id=?",template);}
    }
    /** 使用本次合成部门唯一名称，避免既有测试历史中同名部门影响姓名型台账验收。 */
    private Map<String,Object> ledgerRow(UUID department,UUID owner){String name="台账验收"+department;db.update("UPDATE iam.department SET name=? WHERE id=?",name,department);return Sql.map("originalNumber","OLD-"+UUID.randomUUID(),"title","中文台账合成档案","fileType","合同","departmentName",name,"ownerName",db.required("SELECT name FROM iam.user_account WHERE id=?",owner).get("name"),"cabinetNo","13","columnNo","A","layerNo","3","inventory","原件清单","preservationType","纸质原件","copyCount","45","classification","重要","archiveDate","2025-09-23","retentionCode","10","sourceExpiryDate","2035-09-21","sourceStatus","正常","notes","台账批量登记验收","sourceUpdatedAt","2026-11-03 10:00");}
    /** 跨部门不能预检、查看其他人的批次或确认。 */
    @Test void crossDepartmentAndItCannotImportArchive()throws Exception{var batch=preflight("ARCHIVE",archivistB,List.of(boxRow()));assertThrows(ApiFault.class,()->imports.detail(archivistA,(UUID)batch.get("id")));assertEquals(403,assertThrows(ApiFault.class,()->imports.template(admin,"ARCHIVE")).status);var row=boxRow();row.put("departmentCode",db.required("SELECT code FROM iam.department WHERE id=?",a).get("code"));assertEquals("INVALID",preflight("ARCHIVE",archivistB,List.of(row)).get("state"));}
    /** 模板在预检后停用，确认必须全部失败而不是跳过失效行。 */
    @Test void templateChangeBetweenValidateAndConfirm()throws Exception{var batch=preflight("ARCHIVE",archivistB,List.of(boxRow(),recordRow()));db.update("UPDATE archive.directory_template SET state='DISABLED' WHERE id=?",templateRecord);long count=db.count("SELECT count(*) FROM archive.box");assertThrows(ApiFault.class,()->confirm(batch,"ARCHIVE",archivistB));assertEquals(count,db.count("SELECT count(*) FROM archive.box"));assertEquals("READY",imports.detail(archivistB,(UUID)batch.get("id")).get("state"));}
    /** 确认时失去角色后不能用旧预检扩权。 */
    @Test void revokedImporterCannotConfirm()throws Exception{var batch=preflight("ARCHIVE",archivistB,List.of(boxRow()));var context=context(archivistB,"IMPORT_BATCH",(UUID)batch.get("id"),"CONFIRM_IMPORT",1);db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE user_id=?",archivistB.userId());assertThrows(ApiFault.class,()->imports.confirm(context,(UUID)batch.get("id"),Sql.map("validationDigest",batch.get("validationDigest"))));assertEquals("READY",db.required("SELECT state FROM ops.import_batch WHERE id=?",batch.get("id")).get("state"));}
    /** 未发布导入可整体撤回且保留旧号和历史；重复撤回不产生第二批。 */
    @Test void withdrawPreservesHistory()throws Exception{var batch=confirm(preflight("ARCHIVE",archivistB,List.of(boxRow(),recordRow())),"ARCHIVE",archivistB);UUID id=(UUID)batch.get("id");var ctx=context(archivistB,"IMPORT_BATCH",id,"WITHDRAW_IMPORT",2);var body=Sql.map("reason","合成批次撤回");assertEquals("WITHDRAWN",imports.withdraw(ctx,id,body).get("state"));imports.withdraw(ctx,id,body);assertEquals("WITHDRAWN",authority.record(imported(id,"RECORD")).get("state"));assertEquals(2,db.count("SELECT count(*) FROM archive.legacy_number_map WHERE batch_id=?",id));}
    /** 向已有盒新增文件，撤回时只撤本批文件，原有盒继续保留。 */
    @Test void appendExistingBoxWithdrawDoesNotWithdrawBox()throws Exception{var first=confirm(preflight("ARCHIVE",archivistB,List.of(boxRow())),"ARCHIVE",archivistB);UUID box=imported((UUID)first.get("id"),"BOX");var record=recordRow();record.remove("boxKey");record.put("boxNo",db.required("SELECT box_no FROM archive.box WHERE id=?",box).get("box_no"));var batch=confirm(preflight("ARCHIVE",archivistB,List.of(record)),"ARCHIVE",archivistB);UUID id=(UUID)batch.get("id");imports.withdraw(context(archivistB,"IMPORT_BATCH",id,"WITHDRAW_IMPORT",2),id,Sql.map("reason","只撤本批件"));assertEquals("DRAFT",db.required("SELECT state FROM archive.box WHERE id=?",box).get("state"));}
    /** 已发布对象和包含其他批次成员的盒不能被通用撤回带走。 */
    @Test void withdrawalRejectsPublishedAndOutsideMembers()throws Exception{var first=confirm(preflight("ARCHIVE",archivistB,List.of(boxRow())),"ARCHIVE",archivistB);UUID box=imported((UUID)first.get("id"),"BOX");approve(review(archivistB,"BOX",box,null));UUID id=(UUID)first.get("id");assertThrows(ApiFault.class,()->imports.withdraw(context(archivistB,"IMPORT_BATCH",id,"WITHDRAW_IMPORT",2),id,Sql.map("reason","不得撤已发布")));assertEquals("PUBLISHED",db.required("SELECT state FROM archive.box WHERE id=?",box).get("state"));}
    /** 员工工号、单部门和公司邮箱都由领域重新验证；口令不出现在批次结果。 */
    @Test void employeeImportDeliversCredentialsPrivately()throws Exception{String no="IMPORT-"+UUID.randomUUID();var row=Sql.map("employeeNo",no,"name","合成员工","departmentCode",db.required("SELECT code FROM iam.department WHERE id=?",b).get("code"),"companyEmail",no.toLowerCase()+"@biolink.com");var batch=confirm(preflight("EMPLOYEE",admin,List.of(row)),"EMPLOYEE",admin);UUID id=(UUID)batch.get("id");assertEquals("ALL_CREATED",batch.get("outcome"));assertEquals(1,db.count("SELECT count(*) FROM iam.user_account WHERE employee_no=? AND password_setup_required",no));assertFalse(Crypto.json(imports.rows(admin,id,Map.of())).contains("password"));assertThrows(ApiFault.class,()->imports.withdraw(context(admin,"IMPORT_BATCH",id,"WITHDRAW_IMPORT",2),id,Sql.map("reason","禁止通用删除")));}
    /** 部门乱序可以按依赖创建；本批环和重复编码必须在预检阶段报告。 */
    @Test void departmentTreeOrderedAndCycleRejected()throws Exception{String parent="P"+UUID.randomUUID().toString().substring(0,7).toUpperCase(),child="C"+UUID.randomUUID().toString().substring(0,7).toUpperCase();var batch=confirm(preflight("DEPARTMENT",admin,List.of(Sql.map("code",child,"name","子部门","parentCode",parent),Sql.map("code",parent,"name","父部门"))),"DEPARTMENT",admin);assertEquals("CONFIRMED",batch.get("state"));assertEquals(1,db.count("SELECT count(*) FROM iam.department c JOIN iam.department p ON p.id=c.parent_id WHERE c.code=? AND p.code=?",child,parent));String x="X"+child.substring(1),y="Y"+parent.substring(1);assertEquals("INVALID",preflight("DEPARTMENT",admin,List.of(Sql.map("code",x,"name","环甲","parentCode",y),Sql.map("code",y,"name","环乙","parentCode",x))).get("state"));}
    /** 部分接收、重发相同分片和重复回执只形成一个 PDF 候选；合格仍是草稿。 */
    @Test void deviceRetryReceiptQualityAndIndependentPublication()throws Exception{var fixture=scanFixture(false);byte[] pdf=pdf();UUID upload=createScanUpload(fixture,pdf);part(fixture,upload,pdf);part(fixture,upload,pdf);assertEquals(1,db.count("SELECT count(*) FROM storage.upload_part WHERE upload_session_id=?",upload));var status=scanUploads.complete(fixture.service,upload,Sql.map("partCount",1,"finalSha256",hash(pdf)));scanUploads.process((UUID)status.get("jobId"));var body=receiptBody(fixture,upload,pdf);var receipt=scanUploads.receive(fixture.service,body);assertEquals(receipt,scanUploads.receive(fixture.service,body));assertEquals(1,db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",fixture.record));assertThrows(ApiFault.class,()->catalog.quality(context(archivistB,"ARCHIVE_RECORD",fixture.record,"CONFIRM_QUALITY",null),Sql.map("objectType","RECORD","objectId",fixture.record.toString())));quality(fixture,"PASS");assertEquals("DRAFT",authority.record(fixture.record).get("state"));var ref=objectRef("RECORD",fixture.record,(UUID)db.required("SELECT pdf_version_id FROM archive.scan_item WHERE id=?",fixture.item).get("pdf_version_id"));UUID proof=(UUID)db.required("SELECT quality_check_id FROM archive.scan_item WHERE id=?",fixture.item).get("quality_check_id");UUID flow=(UUID)catalog.submit(context(archivistB,"ARCHIVE_RECORD",fixture.record,"SUBMIT_REGISTRATION",null),Sql.map("objectRefs",List.of(ref),"qualityEvidenceRef",proof.toString())).get("id");approve(flow);assertEquals("PUBLISHED",authority.record(fixture.record).get("state"));}
    /** 错设备、错部门、错项和停用设备都不能借有效文件绕过范围。 */
    @Test void wrongDeviceAndDisabledDeviceRejected()throws Exception{var fixture=scanFixture(false);byte[] content=pdf();UUID upload=createScanUpload(fixture,content);assertThrows(ApiFault.class,()->scanUploads.status(UUID.randomUUID(),upload));var bad=scanBody(fixture,content);bad.put("itemId",UUID.randomUUID().toString());assertThrows(ApiFault.class,()->scanUploads.create(fixture.service,UUID.randomUUID(),bad));var device=db.required("SELECT row_version FROM archive.scan_device WHERE id=?",fixture.device);scans.device(context(admin,"SCAN_DEVICE",fixture.device,"UPDATE_SCAN_DEVICE",((Number)device.get("row_version")).intValue()),fixture.device,Sql.map("state","DISABLED","reason","合成停用"));assertThrows(ApiFault.class,()->part(fixture,upload,content));assertEquals(0,db.count("SELECT count(*) FROM storage.upload_part WHERE upload_session_id=?",upload));}
    /** 接收后替换 PDF 必须走扫描返工，普通挂接接口不能覆盖。 */
    @Test void reworkInvalidatesOldUploadAndReading()throws Exception{var fixture=scanFixture(false);UUID first=receive(fixture);var candidate=authority.record(fixture.record);UUID pdfId=(UUID)db.required("SELECT pdf_version_id FROM archive.scan_item WHERE id=?",fixture.item).get("pdf_version_id");var usage=reading.create(archivistB,Sql.map("reviewContext",Sql.map("type","SCAN_QUALITY","id",fixture.record.toString(),"candidatePdfVersionId",pdfId.toString())),UUID.randomUUID(),UUID.randomUUID());reading.process(archivistB,(UUID)usage.get("jobId"),UUID.randomUUID());quality(fixture,"REWORK");assertThrows(ApiFault.class,()->reading.page(archivistB,(UUID)usage.get("usageId"),1,UUID.randomUUID()));assertThrows(ApiFault.class,()->catalog.bindPdf(context(archivistB,"ARCHIVE_RECORD",fixture.record,"BIND_PDF",1),fixture.record,Map.of()));receive(fixture);assertEquals(2,db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",fixture.record));assertEquals(2,db.count("SELECT count(*) FROM archive.scan_receipt WHERE item_id=?",fixture.item));assertEquals("RECEIVED",db.required("SELECT state FROM archive.scan_item WHERE id=?",fixture.item).get("state"));}
    /** 未取件与特殊原件未获独立批准都不可开始扫描接收。 */
    @Test void specialHandlingRequiresSeparateApproval()throws Exception{var fixture=scanFixture(true);assertThrows(ApiFault.class,()->createScanUpload(fixture,pdf()));event(fixture,supervisor,"SPECIAL_APPROVAL");assertNotNull(createScanUpload(fixture,pdf()));}
    /** 检测服务不可用保持待检测，显式重试才重新处理同一任务。 */
    @Test void detectionFailureAndRetry()throws Exception{var fixture=scanFixture(false);byte[] content=pdf();UUID upload=createScanUpload(fixture,content);part(fixture,upload,content);var result=scanUploads.complete(fixture.service,upload,Sql.map("partCount",1,"finalSha256",hash(content)));DriveReadIntegrationTest.NativeScanner.DOWN.set(true);try{assertThrows(ApiFault.class,()->scanUploads.process((UUID)result.get("jobId")));assertEquals("CHECK_PENDING",scanUploads.status(fixture.service,upload).get("state"));assertEquals(0,db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",fixture.record));}finally{DriveReadIntegrationTest.NativeScanner.DOWN.set(false);}scanUploads.complete(fixture.service,upload,Sql.map("partCount",1,"finalSha256",hash(content)));scanUploads.process((UUID)result.get("jobId"));assertEquals("COMPLETE",scanUploads.status(fixture.service,upload).get("state"));}
    /** 原件未归还不能关闭；暂存清理需要先质检和独立批准。 */
    @Test void originalsAndCleanupLifecycle()throws Exception{var fixture=scanFixture(false);assertThrows(ApiFault.class,()->event(fixture,supervisor,"CLEANUP_APPROVAL"));receive(fixture);quality(fixture,"PASS");assertThrows(ApiFault.class,()->event(fixture,archivistB,"CLOSE"));assertThrows(ApiFault.class,()->event(fixture,archivistB,"CLEANUP_APPROVAL"));event(fixture,supervisor,"CLEANUP_APPROVAL");event(fixture,archivistB,"CLEANUP_COMPLETED");event(fixture,archivistB,"RETURN");event(fixture,archivistB,"CLOSE");assertEquals("CLOSED",scans.detail(archivistB,fixture.batch).get("state"));}
    /** 异常暂停立即阻断接收，登记本轮处置后由独立管理员恢复。 */
    @Test void pauseResolveResume()throws Exception{var fixture=scanFixture(false);event(fixture,archivistB,"PAUSE");assertThrows(ApiFault.class,()->createScanUpload(fixture,pdf()));assertThrows(ApiFault.class,()->event(fixture,supervisor,"RESUME"));event(fixture,archivistB,"RESOLVE");event(fixture,supervisor,"RESUME");assertNotNull(createScanUpload(fixture,pdf()));}
    /** 设备签名防重放、过期、错路由；有效签名也不创建员工会话。 */
    @Test void serviceSignatureRejectsReplayAndWrongPath()throws Exception{var fixture=scanFixture(false);String secret=Files.readString(Path.of(System.getenv("IMS_PLATFORM_SCAN_KEY_DIRECTORY")).resolve(fixture.service+".key")),time=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString(),path="/internal/archive/scan-batches/"+fixture.batch,hash=Crypto.hash("");String signature=Crypto.hmac(secret,"GET\n"+path+"\n"+time+"\n"+nonce+"\n"+hash);assertEquals(fixture.service,scanIdentity.authenticate(fixture.service.toString(),time,nonce,"GET",path,hash,signature));assertThrows(ApiFault.class,()->scanIdentity.authenticate(fixture.service.toString(),time,nonce,"GET",path,hash,signature));assertThrows(ApiFault.class,()->scanIdentity.authenticate(fixture.service.toString(),time,UUID.randomUUID().toString(),"GET",path+"x",hash,signature));assertThrows(ApiFault.class,()->scanIdentity.authenticate(fixture.service.toString(),"1",UUID.randomUUID().toString(),"GET",path,hash,signature));}
    /** 丢失副本时不得生成扫描回执，更不能自动降级为单副本交付。 */
    @Test void missingReplicaPreventsReceipt()throws Exception{var fixture=scanFixture(false);byte[] content=pdf();UUID upload=createScanUpload(fixture,content);part(fixture,upload,content);var task=scanUploads.complete(fixture.service,upload,Sql.map("partCount",1,"finalSha256",hash(content)));scanUploads.process((UUID)task.get("jobId"));var object=db.required("SELECT o.* FROM storage.object o JOIN storage.upload_session s ON s.object_id=o.id WHERE s.id=?",upload);Path secondary=Path.of(System.getenv("IMS_PLATFORM_STORAGE_SECONDARY")).resolve((String)object.get("storage_key"));byte[] backup=Files.readAllBytes(secondary);try{Files.write(secondary,new byte[]{1});assertEquals(503,assertThrows(ApiFault.class,()->scanUploads.receive(fixture.service,receiptBody(fixture,upload,content))).status);assertEquals(0,db.count("SELECT count(*) FROM archive.scan_receipt WHERE item_id=?",fixture.item));}finally{Files.write(secondary,backup);}}

    /** 同一操作并发确认最多执行一次新增，第二请求读取原回执。 */
    @Test void concurrentConfirmationCreatesOnce()throws Exception{var batch=preflight("ARCHIVE",archivistB,List.of(boxRow(),recordRow()));UUID id=(UUID)batch.get("id");var ctx=context(archivistB,"IMPORT_BATCH",id,"CONFIRM_IMPORT",1);var body=Sql.map("validationDigest",batch.get("validationDigest"));try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var first=pool.submit(()->imports.confirm(ctx,id,body));var second=pool.submit(()->imports.confirm(ctx,id,body));assertEquals(first.get(30,java.util.concurrent.TimeUnit.SECONDS).get("id"),second.get(30,java.util.concurrent.TimeUnit.SECONDS).get("id"));}assertEquals(2,db.count("SELECT count(*) FROM ops.import_row WHERE batch_id=? AND object_id IS NOT NULL",id));}
    /** 已进入本人归档复核的未发布导入可整体撤回，批准历史保留。 */
    @Test void withdrawAlsoClosesOwnPendingReview()throws Exception{var batch=confirm(preflight("ARCHIVE",archivistB,List.of(boxRow())),"ARCHIVE",archivistB);UUID id=(UUID)batch.get("id"),box=imported(id,"BOX"),flow=review(archivistB,"BOX",box,null);imports.withdraw(context(archivistB,"IMPORT_BATCH",id,"WITHDRAW_IMPORT",2),id,Sql.map("reason","在正式发布前撤回合成批次"));assertEquals("WITHDRAWN",db.required("SELECT state FROM workflow.instance WHERE id=?",flow).get("state"));assertEquals("WITHDRAWN",db.required("SELECT state FROM archive.box WHERE id=?",box).get("state"));}
    /** 检测 I/O 期间停用设备，结束时的再鉴权拒绝保存可用对象。 */
    @Test void deviceDisabledDuringInspectionFailsClosed()throws Exception{var fixture=scanFixture(false);byte[] bytes=pdf();UUID upload=createScanUpload(fixture,bytes);part(fixture,upload,bytes);UUID job=(UUID)scanUploads.complete(fixture.service,upload,Sql.map("partCount",1,"finalSha256",hash(bytes))).get("jobId");DriveReadIntegrationTest.NativeScanner.scanStarted=new java.util.concurrent.CountDownLatch(1);DriveReadIntegrationTest.NativeScanner.scanRelease=new java.util.concurrent.CountDownLatch(1);try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){var future=pool.submit(()->scanUploads.process(job));try{assertTrue(DriveReadIntegrationTest.NativeScanner.scanStarted.await(10,java.util.concurrent.TimeUnit.SECONDS));scans.device(context(admin,"SCAN_DEVICE",fixture.device,"UPDATE_SCAN_DEVICE",1),fixture.device,Sql.map("state","DISABLED","reason","检测中停用验收"));}finally{DriveReadIntegrationTest.NativeScanner.scanRelease.countDown();}assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(20,java.util.concurrent.TimeUnit.SECONDS));}finally{DriveReadIntegrationTest.NativeScanner.scanStarted=null;DriveReadIntegrationTest.NativeScanner.scanRelease=null;}assertEquals("FAILED",db.required("SELECT state FROM ops.job WHERE id=?",job).get("state"));assertEquals(0,db.count("SELECT count(*) FROM storage.object o JOIN storage.upload_session s ON s.object_id=o.id WHERE s.id=? AND o.state='READY'",upload));}
    /** 错误人工页数或未勾选内容检查，不能形成 PASS 责任记录。 */
    @Test void wrongQualityPagesRejected()throws Exception{var fixture=scanFixture(false);receive(fixture);var item=db.required("SELECT row_version FROM archive.scan_item WHERE id=?",fixture.item);assertThrows(ApiFault.class,()->scans.quality(context(archivistB,"SCAN_ITEM",fixture.item,"SCAN_QUALITY",((Number)item.get("row_version")).intValue()),fixture.item,Sql.map("result","PASS","receivedPages",1,"contentChecked",true,"pageOrderChecked",true,"condition","漏核对一页")));assertEquals(0,db.count("SELECT count(*) FROM archive.quality_check WHERE object_id=?",fixture.record));quality(fixture,"PASS");}
    /** 纠正预期页数会使旧上传失效，接收后的返工只能改同一档案的事实。 */
    @Test void correctionInvalidatesOldUpload()throws Exception{var fixture=scanFixture(false);byte[] bytes=pdf();UUID old=createScanUpload(fixture,bytes);scans.correct(context(archivistB,"SCAN_ITEM",fixture.item,"CORRECT_SCAN_ITEM",1),fixture.item,Sql.map("recordId",fixture.record.toString(),"expectedPages",2,"sourceCondition","重新核对折页","reason","校正原件状态"));assertEquals(409,assertThrows(ApiFault.class,()->part(fixture,old,bytes)).status);receive(fixture);quality(fixture,"REWORK");int version=((Number)db.required("SELECT row_version FROM archive.scan_item WHERE id=?",fixture.item).get("row_version")).intValue();scans.correct(context(archivistB,"SCAN_ITEM",fixture.item,"CORRECT_SCAN_ITEM",version),fixture.item,Sql.map("recordId",fixture.record.toString(),"expectedPages",2,"sourceCondition","返工后原件状态","reason","保留绑定的页数纠正"));receive(fixture);quality(fixture,"PASS");assertEquals(2,db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",fixture.record));}
    /** 无害测试签名模拟恶意文件，命中后保持隔离，不能重试洗成通过。 */
    @Test void suspiciousDeviceFileQuarantined()throws Exception{var fixture=scanFixture(false);byte[] bytes="%PDF-1.4\nIMS-INTEGRATION-SIGNATURE-ONLY".getBytes(java.nio.charset.StandardCharsets.UTF_8);UUID upload=createScanUpload(fixture,bytes);part(fixture,upload,bytes);UUID job=(UUID)scanUploads.complete(fixture.service,upload,Sql.map("partCount",1,"finalSha256",hash(bytes))).get("jobId");scanUploads.process(job);assertEquals("REJECTED",scanUploads.status(fixture.service,upload).get("state"));assertThrows(ApiFault.class,()->scanUploads.receive(fixture.service,receiptBody(fixture,upload,bytes)));assertThrows(ApiFault.class,()->scanUploads.complete(fixture.service,upload,Sql.map("partCount",1,"finalSha256",hash(bytes))));}
    /** 供真实浏览器与设备参考代理使用的独立合成夹具，凭据只写私有目录。 */
    @AfterAll void browserFixture()throws Exception{setup();var fixture=scanFixture(false);receive(fixture);var pending=confirm(preflight("ARCHIVE",archivistB,List.of(boxRow(),recordRow())),"ARCHIVE",archivistB);var ready=preflight("ARCHIVE",archivistB,List.of(boxRow()));Path root=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT"));String employee="WEB-"+UUID.randomUUID();Files.write(root.resolve("browser-employees.xlsx"),ImportWorkbook.workbook("EMPLOYEE",List.of(Sql.map("employeeNo",employee,"name","浏览器合成员工","departmentCode",db.required("SELECT code FROM iam.department WHERE id=?",b).get("code"),"companyEmail",employee.toLowerCase()+"@biolink.com"))));var values=Sql.map("clerk","AR-"+archivistB.userId(),"supervisor","AR-"+supervisor.userId(),"staff","AR-"+applicant.userId(),"password",password,"departmentId",b,"scanBatchId",fixture.batch,"scanItemId",fixture.item,"deviceId",fixture.device,"serviceId",fixture.service,"recordId",fixture.record,"pendingRecordId",imported((UUID)pending.get("id"),"RECORD"),"readyImportId",ready.get("id"),"employeeNo",employee);Path output=root.resolve("browser-fixture.private.json");Files.writeString(output,Crypto.json(values));PrivateFiles.restrict(output);}

    /** 规范台账样本，原件、部门和旧号均为合成数据。 */
    private Map<String,Object> boxRow(){return Sql.map("objectType","BOX","rowKey","box","departmentCode",db.required("SELECT code FROM iam.department WHERE id=?",b).get("code"),"title","导入合成盒","safeTitle","导入合成盒","classification","GENERAL","retentionCode","10","archiveDate","2025-09-23","numberMonth","2025-09-01","categoryCode","ADMIN","carrierType","MIXED","extensionValues",Crypto.json(Sql.map("paperTags",List.of("纸质原件"))),"ownerEmployeeNo","AR-"+people.get("managerB"),"templateVersionId",templateBox.toString(),"location","合成库房B-01","keywords","合成;导入","originalNumber","OLD-BOX-"+UUID.randomUUID());}
    private Map<String,Object> recordRow(){var row=boxRow();row.put("objectType","RECORD");row.put("rowKey","record");row.put("boxKey","box");row.put("itemNo","1");row.put("title","导入合成文件");row.put("templateVersionId",templateRecord.toString());row.put("originalNumber","OLD-FILE-"+UUID.randomUUID());row.remove("numberMonth");row.remove("extensionValues");return row;}
    /** 真实分片上传、合并和原生病毒检测后预检，不直接构造 READY 对象。 */
    private Map<String,Object> preflight(String type,Actor actor,List<Map<String,Object>> rows)throws Exception{return preflight(type,actor,ImportWorkbook.workbook(type,rows),List.of(b));}
    /** 上传真实字节、技术检测和批次预检共用同一流程，中文台账不绕过文件服务。 */
    private Map<String,Object> preflight(String type,Actor actor,byte[] bytes,List<UUID> scope)throws Exception{evidence(false);UUID upload=(UUID)uploads.create(actor,Sql.map("module",type.equals("ARCHIVE")?"ARCHIVE":"PLATFORM","businessContextId",b.toString(),"purpose","IMPORT_XLSX","originalName","合成台账.xlsx","expectedSize",bytes.length,"expectedSha256",hash(bytes),"expectedMediaType",ImportWorkbook.MEDIA),UUID.randomUUID(),UUID.randomUUID()).get("uploadId");uploads.part(actor,upload,1,"bytes 0-"+(bytes.length-1)+"/"+bytes.length,bytes.length,hash(bytes),new ByteArrayInputStream(bytes),UUID.randomUUID());UUID job=(UUID)uploads.complete(actor,upload,Sql.map("partCount",1,"finalSha256",hash(bytes)),UUID.randomUUID(),UUID.randomUUID()).get("jobId");assembly.assemble(actor,job,UUID.randomUUID());inspection.inspect(actor,job,UUID.randomUUID());UUID object=(UUID)uploads.get(actor,upload).get("objectId");return imports.create(type.equals("ARCHIVE")?plain(actor,"CREATE_IMPORT"):context(actor,"IMPORT_BATCH",ApiController.COLLECTION,"CREATE_IMPORT",null),Sql.map("importType",type,"uploadObjectId",object.toString(),"templateVersion",ImportWorkbook.VERSION,"departmentScope",scope.stream().map(UUID::toString).toList()));}
    private Map<String,Object> confirm(Map<String,Object> batch,String type,Actor actor){UUID id=(UUID)batch.get("id");return imports.confirm(context(actor,"IMPORT_BATCH",id,"CONFIRM_IMPORT",((Number)batch.get("rowVersion")).intValue()),id,Sql.map("validationDigest",batch.get("validationDigest")));}
    private UUID imported(UUID batch,String type){return (UUID)db.required("SELECT object_id FROM ops.import_row WHERE batch_id=? AND object_type=?",batch,type).get("object_id");}
    /** 每个测试独立设备、固定清单和原件取出记录。 */
    private record Fixture(UUID device,UUID service,UUID batch,UUID item,UUID record) { }
    private Fixture scanFixture(boolean special)throws Exception{var imported=confirm(preflight("ARCHIVE",archivistB,List.of(boxRow(),recordRow())),"ARCHIVE",archivistB);UUID batch=(UUID)imported.get("id"),box=imported(batch,"BOX"),record=imported(batch,"RECORD");approve(review(archivistB,"BOX",box,null));var device=scans.device(context(admin,"SCAN_DEVICE",ApiController.COLLECTION,"CREATE_SCAN_DEVICE",null),null,Sql.map("departmentId",b.toString(),"name","合成扫描设备","software","验收代理 1.0","stagingLocation","受控扫描暂存区","allowedOutputNotes","仅允许受控 HTTPS 出口；合成验收"));UUID id=(UUID)scans.create(context(archivistB,"SCAN_BATCH",ApiController.COLLECTION,"CREATE_SCAN_BATCH",null),Sql.map("departmentId",b.toString(),"deviceId",device.get("id").toString(),"externalKey","SCAN-"+UUID.randomUUID(),"expectedCount",1,"expectedPages",2,"items",List.of(Sql.map("recordId",record.toString(),"expectedPages",2,"sourceCondition","合成原件完好","attachments","附件一份，来源已核对","specialHandling",special)))).get("id");UUID item=(UUID)db.required("SELECT id FROM archive.scan_item WHERE batch_id=?",id).get("id");Fixture fixture=new Fixture((UUID)device.get("id"),(UUID)device.get("serviceIdentityId"),id,item,record);event(fixture,archivistB,"TAKE_OUT");return fixture;}
    private Map<String,Object> scanBody(Fixture fixture,byte[] bytes){return Sql.map("batchId",fixture.batch.toString(),"itemId",fixture.item.toString(),"originalName","合成扫描.pdf","expectedSize",bytes.length,"expectedSha256",hash(bytes),"expectedMediaType","application/pdf");}
    private UUID createScanUpload(Fixture fixture,byte[] bytes){return (UUID)scanUploads.create(fixture.service,UUID.randomUUID(),scanBody(fixture,bytes)).get("id");}
    private void part(Fixture fixture,UUID upload,byte[] bytes){scanUploads.part(fixture.service,upload,1,"bytes 0-"+(bytes.length-1)+"/"+bytes.length,bytes.length,hash(bytes),new ByteArrayInputStream(bytes));}
    private Map<String,Object> receiptBody(Fixture fixture,UUID upload,byte[] bytes){return Sql.map("eventId",UUID.randomUUID().toString(),"deviceId",fixture.device.toString(),"batchId",fixture.batch.toString(),"itemId",fixture.item.toString(),"uploadSessionId",upload.toString(),"receiptKey","RECEIPT-"+UUID.randomUUID(),"receivedPages",2,"sha256",hash(bytes));}
    private UUID receive(Fixture fixture)throws Exception{byte[] bytes=pdf();UUID upload=createScanUpload(fixture,bytes);part(fixture,upload,bytes);var task=scanUploads.complete(fixture.service,upload,Sql.map("partCount",1,"finalSha256",hash(bytes)));scanUploads.process((UUID)task.get("jobId"));scanUploads.receive(fixture.service,receiptBody(fixture,upload,bytes));return upload;}
    private void quality(Fixture fixture,String result){var item=db.required("SELECT row_version FROM archive.scan_item WHERE id=?",fixture.item);scans.quality(context(archivistB,"SCAN_ITEM",fixture.item,"SCAN_QUALITY",((Number)item.get("row_version")).intValue()),fixture.item,Sql.map("result",result,"receivedPages",2,"contentChecked",true,"pageOrderChecked",true,"condition","已逐页核对原件及附件","reason","合成质量检查"));}
    private void event(Fixture fixture,Actor actor,String type){var batch=scans.detail(actor,fixture.batch);scans.event(context(actor,"SCAN_BATCH",fixture.batch,"SCAN_EVENT",((Number)batch.get("rowVersion")).intValue()),fixture.batch,Sql.map("eventType",type,"fromPerson","合成交出人","toPerson","合成接收人","actualQuantity",1,"condition","数量和状态已核对","decision","合成处理决定","reason","合成验收依据"));}
    private byte[] pdf()throws Exception{return Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")));}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
