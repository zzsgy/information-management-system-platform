package com.company.platform.storage;

import com.company.platform.*;
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
@EnabledIfEnvironmentVariable(named="IMS_CATALOG_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class},webEnvironment=SpringBootTest.WebEnvironment.NONE,
 properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogMaintenanceIntegrationTest {
    @Autowired PhysicalLoans loans;
    @Autowired CatalogMaintenance maintenance; @Autowired CatalogTemplates templateService; @Autowired com.company.platform.operations.BusinessNotifications messages;
    /** 业务服务全部使用正式实现；替换范围仅为测试专用无害签名库。 */
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired ArchiveCatalogService catalog;@Autowired ArchiveAuthority authority;
    @Autowired ArchivePolicyService policies;@Autowired BorrowService borrow;@Autowired WorkflowEngine workflow;@Autowired ArchiveReadingService reading;
    @Autowired UploadService uploads;@Autowired UploadAssembly assembly;@Autowired FileInspectionService inspection;
    @Autowired OrganizationDuties organization;
    /** 真实到期状态和站内通知工作器，测试不会替换授权判定。 */
    @Autowired BorrowLifecycleService lifecycle;
    /** 改派必须同时满足真实任职与当前模块权限，不只检查角色名称。 */
    @Autowired WorkflowDuties dutyResolver;
    /** 每次测试独立建档；统一合成部门和员工不含正式业务数据。 */
    private UUID a,b,templateBox,templateRecord;private String password;private final Map<String,UUID> people=new HashMap<>();private final Map<UUID,Actor> actors=new HashMap<>();private Actor admin,supervisor,applicant,archivistA,archivistB;
    private final UUID scanner=UUID.fromString("42210210-46cc-4ce8-8624-5c0da61a7760");
    /** 验证地址后建立合成员工；工作日历通过正式API服务发布，不假定生产周末规则。 */
    @BeforeAll void setup() throws Exception {
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:(55446|55447|55448)/ims_platform_dev_[0-9]+"));password=Crypto.token();admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence(false);
        // 复跑仅结束本专用库上一轮合成超级管理员任职，历史批准和文件全部保留。
        db.update("UPDATE iam.user_role a SET state='ENDED',valid_until=CURRENT_TIMESTAMP FROM iam.user_account u WHERE a.user_id=u.id AND a.module='ARCHIVE' AND a.role_code='SUPER_ADMIN' AND a.state='ACTIVE' AND u.employee_no LIKE 'AR-%'");
        a=department("A");b=department("B");person("super",a,"SUPER_ADMIN");person("archA",a,"DEPARTMENT_ARCHIVIST");person("archB",b,"DEPARTMENT_ARCHIVIST");person("managerA",a,"STAFF");person("managerB",b,"STAFF");person("gm",a,"STAFF");person("applicant",a,"STAFF");person("second",a,"STAFF");person("outsider",b,"STAFF");
        for(var pair:Map.of(a,people.get("managerA"),b,people.get("managerB")).entrySet()) db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'合成验收',?)",UUID.randomUUID(),pair.getKey(),pair.getValue(),admin.userId());
        // 公司总经理职责与公盘角色分离，避免拿公盘管理员当档案批准人。
        db.update("UPDATE iam.company_duty SET valid_until=CURRENT_TIMESTAMP WHERE duty_code='GENERAL_MANAGER' AND valid_until IS NULL");db.update("INSERT INTO iam.company_duty(id,duty_code,user_id,valid_from,changed_by,change_reason) VALUES(?,'GENERAL_MANAGER',?,CURRENT_TIMESTAMP,?,'合成验收')",UUID.randomUUID(),people.get("gm"),admin.userId());
        refreshActors();
        templateBox=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","BOX_"+UUID.randomUUID(),"name","电子档案盒基础模板","categoryCode","ADMIN","objectType","BOX","fields",List.of())).get("id");
        templateRecord=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","RECORD_"+UUID.randomUUID(),"name","电子文件基础模板","categoryCode","ADMIN","objectType","RECORD","fields",List.of())).get("id");
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','档案隔离扫描','ARCHIVE',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope,valid_until=EXCLUDED.valid_until",scanner,"{"+a+","+b+"}");
    }
    @BeforeEach void fresh() throws Exception {DriveReadIntegrationTest.NativeScanner.DOWN.set(false);DriveReadIntegrationTest.NativeScanner.scanStarted=null;DriveReadIntegrationTest.NativeScanner.scanRelease=null;evidence(false);refreshActors();}
    private UUID department(String prefix) {UUID id=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",id,prefix+id.toString().substring(0,7).toUpperCase(Locale.ROOT),"合成部门"+prefix);return id;}
    private void person(String key,UUID department,String roleCode) {UUID id=UUID.randomUUID();people.put(key,id);String no="AR-"+id;db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,"验收"+key,department,no.toLowerCase(Locale.ROOT)+"@biolink.com",Crypto.password(password));UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='ARCHIVE' AND code=?",roleCode).get("id");db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,'ARCHIVE',?)",UUID.randomUUID(),id,role,roleCode.equals("DEPARTMENT_ARCHIVIST")?department:null,admin.userId(),roleCode);}
    private void refreshActors() {for(var person:people.entrySet()) actors.put(person.getValue(),login("AR-"+person.getValue(),password));supervisor=actors.get(people.get("super"));applicant=actors.get(people.get("applicant"));archivistA=actors.get(people.get("archA"));archivistB=actors.get(people.get("archB"));}
    private Actor login(String no,String secret) {return identity.authenticate((String)identity.login(Sql.map("employeeNo",no,"password",secret),UUID.randomUUID(),"archive-test").get("token"));}
    /** 凭据按本人、对象及精确动作签发；普通借阅无需高风险管理凭据。 */
    private Commands.Context context(Actor actor,String type,UUID target,String action,Integer version) {String secret=actor.userId().equals(admin.userId())?System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"):password;var proof=identity.reauthenticate(actor,Sql.map("password",secret,"purpose","MANAGE","objectIds",List.of(target.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",target.toString(),"actions",List.of(action)))),UUID.randomUUID());return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action+" "+target,(String)proof.get("verificationToken"),version);}
    private Commands.Context plain(Actor actor,String action) {return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action,null,null);}
    /** 只签发合成保护事实以验收消费者；这不是生产备份和主备事实。 */
    private void evidence(boolean expired) throws Exception {Path root=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")),key=root.resolve("protection.key");if(!Files.exists(key)){Files.writeString(key,Crypto.token(),StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(key);}Instant now=Instant.now().minusSeconds(expired?120:0);var state=Sql.map("instanceId",db.required("SELECT id FROM platform_meta.instance").get("id").toString(),"nodeId","archive-isolated-node","epoch",1,"issuedAt",now.toString(),"expiresAt",now.plusSeconds(60).toString(),"mode","NORMAL","oldPrimaryFenced",true,"replicaVerifiedAt",now.toString(),"backupId",UUID.randomUUID().toString(),"backupProtectedAt",now.toString(),"writableModules",List.of("ARCHIVE","DRIVE"));state.put("signature",Crypto.hmac(Files.readString(key),Crypto.json(state)));Files.writeString(root.resolve("protection.json"),Crypto.json(state));}
    /** 逐必要节点调用真实决定事务；不会直接修改流程为APPROVED。 */
    private void approve(UUID instance) {for(int round=0;round<10;round++){var flow=db.required("SELECT * FROM workflow.instance WHERE id=?",instance);if(flow.get("current_step_id")==null){assertEquals("APPROVED",flow.get("state"));return;}UUID id=(UUID)flow.get("current_step_id");var step=db.required("SELECT * FROM workflow.step WHERE id=?",id);Actor actor=actors.get(step.get("assignee_id"));assertNotNull(actor);workflow.decide(context(actor,"WORKFLOW_STEP",id,"DECIDE_STEP",((Number)step.get("row_version")).intValue()),id,Sql.map("result","APPROVE","comment","合成验收同意"));}fail("流程未在固定节点数内结束");}
    private Map<String,Object> objectRef(String type,UUID id,UUID pdf) {var row=db.required("SELECT * FROM "+(type.equals("BOX")?"archive.box":"archive.record")+" WHERE id=?",id);return Sql.map("objectType",type,"objectId",id.toString(),"expectedVersion",row.get("row_version"),"candidateRevisionId",row.get("candidate_revision_id").toString(),"candidatePdfVersionId",pdf==null?null:pdf.toString());}
    private UUID review(Actor actor,String type,UUID id,UUID pdf) {var ref=objectRef(type,id,pdf);int pages=pdf==null?0:((Number)db.required("SELECT page_count FROM archive.pdf_version WHERE id=?",pdf).get("page_count")).intValue();UUID quality=(UUID)catalog.quality(context(actor,type.equals("BOX")?"ARCHIVE_BOX":"ARCHIVE_RECORD",id,"CONFIRM_QUALITY",null),Sql.map("objectType",type,"objectId",id.toString(),"candidateRevisionId",ref.get("candidateRevisionId"),"candidatePdfVersionId",ref.get("candidatePdfVersionId"),"checkedPageCount",pages,"confirmed",true,"notes","合成人工确认完整页序与清晰度")).get("id");return (UUID)catalog.submit(context(actor,type.equals("BOX")?"ARCHIVE_BOX":"ARCHIVE_RECORD",id,"SUBMIT_REGISTRATION",null),Sql.map("objectRefs",List.of(ref),"qualityEvidenceRef",quality.toString())).get("id");}
    /** 新对象从草稿、分片、原生扫描到独立复核全程通过正式服务。 */
    private UUID published(String classification,UUID department) throws Exception {
        evidence(false);Actor clerk=department.equals(a)?archivistA:archivistB;UUID owner=people.get(department.equals(a)?"managerA":"managerB");LocalDate today=LocalDate.now(WorkCalendarService.ZONE);
        UUID box=(UUID)catalog.box(context(clerk,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),Sql.map("departmentId",department.toString(),"title","测试电子档案盒","safeTitle","测试公开盒","classification",classification,"retentionCode","10","archiveDate",today.toString(),"physicalFileCount",0,"keywords",List.of("合成"),"templateVersionId",templateBox.toString(),"categoryCode","ADMIN","ownerId",owner.toString(),"numberMonth",today.withDayOfMonth(1).toString(),"carrierType","ELECTRONIC")).get("id");approve(review(clerk,"BOX",box,null));
        UUID record=(UUID)catalog.record(context(clerk,"ARCHIVE_BOX",box,"CREATE_RECORD",null),Sql.map("boxId",box.toString(),"itemNo",1,"categoryCode","ADMIN","carrierType","ELECTRONIC","title","秘密合同正文题名"+UUID.randomUUID(),"safeTitle","可申请的合成资料","keywords",List.of("秘密关键词"),"abstract","不允许通过安全题名检索命中的秘密摘要","ownerId",owner.toString(),"archiveDate",today.toString(),"templateVersionId",templateRecord.toString())).get("id");
        UUID pdf=upload(clerk,record);approve(review(clerk,"RECORD",record,pdf));return record;
    }
    private UUID upload(Actor clerk,UUID record) throws Exception {byte[] content=Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")));String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));UUID request=UUID.randomUUID();UUID upload=(UUID)uploads.create(clerk,Sql.map("module","ARCHIVE","businessContextId",record.toString(),"purpose","ARCHIVE_PDF","originalName","合成档案.pdf","expectedSize",content.length,"expectedSha256",hash,"expectedMediaType","application/pdf"),UUID.randomUUID(),request).get("uploadId");uploads.part(clerk,upload,1,"bytes 0-"+(content.length-1)+"/"+content.length,content.length,hash,new ByteArrayInputStream(content),request);UUID job=(UUID)uploads.complete(clerk,upload,Sql.map("partCount",1,"finalSha256",hash),UUID.randomUUID(),request).get("jobId");assembly.assemble(clerk,job,request);inspection.inspect(clerk,job,request);assertEquals("COMPLETE",uploads.get(clerk,upload).get("state"));int version=((Number)authority.record(record).get("row_version")).intValue();return (UUID)catalog.bindPdf(context(clerk,"ARCHIVE_RECORD",record,"BIND_PDF",version),record,Sql.map("uploadSessionId",upload.toString(),"source","UPLOAD")).get("id");}
    private UUID policy(UUID record,boolean owner,List<UUID> users) {var row=authority.record(record);Actor clerk=row.get("department_id").equals(a)?archivistA:archivistB;UUID flow=(UUID)policies.propose(context(clerk,"ARCHIVE_POLICY",record,"REQUEST_ACCESS_POLICY",null),Sql.map("objectType","RECORD","objectId",record.toString(),"departmentScope",List.of(a.toString(),b.toString()),"titleVisibility","SAFE_TITLE","safeSearchFields",List.of("safeTitle"),"allowOwnerDepartmentView",owner,"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",row.get("current_pdf_version_id").toString(),"userIds",users.stream().map(UUID::toString).toList(),"validFrom",Instant.now().minusSeconds(1).toString(),"validUntil",Instant.now().plusSeconds(864000).toString(),"reason","合成基础开放申请")).get("id");approve(flow);return (UUID)db.required("SELECT id FROM archive.access_policy WHERE workflow_instance_id=?",flow).get("id");}
    private UUID submitBorrow(Actor actor,List<UUID> records) {return (UUID)borrow.submit(plain(actor,"SUBMIT_BORROW"),Sql.map("form","ELECTRONIC","actions",List.of("VIEW"),"purpose","合成业务查看","items",records.stream().map(id->Sql.map("objectType","RECORD","objectId",id.toString(),"actions",List.of("VIEW"))).toList(),"period",Sql.map("mode","WORKDAYS","amount",3))).get("id");}
    private void approveBatch(UUID batch) {for(var row:db.rows("SELECT workflow_instance_id FROM archive.borrow_request WHERE batch_id=? ORDER BY id",batch)) approve((UUID)row.get("workflow_instance_id"));}
    private UUID view(Actor actor,UUID record) {var row=authority.record(record);var receipt=reading.create(actor,Sql.map("recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",row.get("current_pdf_version_id").toString()),UUID.randomUUID(),UUID.randomUUID());UUID id=(UUID)receipt.get("usageId");reading.process(actor,(UUID)receipt.get("jobId"),UUID.randomUUID());return id;}

    /** 半微秒输入在送审时标准化，批准快照与数据库必须仍严格相等。 */

    private UUID candidateUpload(Actor clerk,UUID record) throws Exception {byte[] content=Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")));String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));UUID request=UUID.randomUUID();UUID upload=(UUID)uploads.create(clerk,Sql.map("module","ARCHIVE","businessContextId",record.toString(),"purpose","ARCHIVE_PDF","originalName","合成档案.pdf","expectedSize",content.length,"expectedSha256",hash,"expectedMediaType","application/pdf"),UUID.randomUUID(),request).get("uploadId");uploads.part(clerk,upload,1,"bytes 0-"+(content.length-1)+"/"+content.length,content.length,hash,new ByteArrayInputStream(content),request);UUID job=(UUID)uploads.complete(clerk,upload,Sql.map("partCount",1,"finalSha256",hash),UUID.randomUUID(),request).get("jobId");assembly.assemble(clerk,job,request);inspection.inspect(clerk,job,request);assertEquals("COMPLETE",uploads.get(clerk,upload).get("state"));return upload;}
    /** 正式更正入口的本人确认与原目录版本。 */
    private Map<String,Object> change(UUID id,Map<String,Object> patch,String kind) {return maintenance.create(context(archivistB,"ARCHIVE_RECORD",id,"CREATE_CATALOG_CHANGE",((Number)authority.record(id).get("row_version")).intValue()),"RECORD",id,Sql.map("changes",patch,"kind",kind,"reason","合成目录更正验收"));}
    private UUID submitChange(Map<String,Object> c){UUID id=(UUID)c.get("id");var result=maintenance.submit(context(archivistB,"ARCHIVE_REVISION",id,"SUBMIT_CATALOG_CHANGE",((Number)c.get("rowVersion")).intValue()),id,Map.of());return (UUID)result.get("workflowId");}
    private UUID draftBox() {
        LocalDate day=LocalDate.now(WorkCalendarService.ZONE);
        return (UUID)catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),Sql.map("departmentId",b.toString(),"title","登记维护验收盒","classification","GENERAL","retentionCode","10","archiveDate",day.toString(),"physicalFileCount",0,"keywords",List.of("登记验收"),"templateVersionId",templateBox.toString(),"categoryCode","ADMIN","ownerId",people.get("managerB").toString(),"numberMonth",day.withDayOfMonth(1).toString(),"carrierType","ELECTRONIC")).get("id");
    }
    private UUID draftRecord(UUID box) {
        return draftRecord(box,1);
    }
    private UUID draftRecord(UUID box,int itemNo) {
        return (UUID)catalog.record(context(archivistB,"ARCHIVE_BOX",box,"CREATE_RECORD",null),Sql.map("boxId",box.toString(),"itemNo",itemNo,"categoryCode","ADMIN","carrierType","ELECTRONIC","title","登记维护验收文件","keywords",List.of("登记验收"),"ownerId",people.get("managerB").toString(),"archiveDate",LocalDate.now(WorkCalendarService.ZONE).toString(),"templateVersionId",templateRecord.toString())).get("id");
    }
    /** 同一通用台账跨业务类别新增和修改盒件，切换类别不丢失原Excel字段。 */
    @Test void commonLedgerSupportsAllCategoriesForCreationAndDraftChanges() throws Exception {
        UUID commonBox=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_BOX_LEDGER' AND state='ACTIVE'").get("id");
        UUID commonRecord=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_RECORD_LEDGER' AND state='ACTIVE'").get("id");
        var extras=Sql.map("fileType","采购合同","cabinetNo","13","columnNo","A","layerNo","3","copyCount",45);
        LocalDate today=LocalDate.now(WorkCalendarService.ZONE);
        for(String category:List.of("COMMON","ADMIN","SALES_CONTRACT","PURCHASE_CONTRACT","PROJECT_CONTRACT","QUALITY","WORK_RECORD")){
            evidence(false);
            var box=catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),Sql.map("departmentId",b.toString(),"title","通用台账类别验收-"+category,"classification","GENERAL","retentionCode","10","archiveDate",today.toString(),"keywords",List.of("通用台账"),"templateVersionId",commonBox.toString(),"categoryCode",category,"ownerId",people.get("managerB").toString(),"numberMonth",today.withDayOfMonth(1).toString(),"carrierType","ELECTRONIC","extensionValues",extras));
            UUID boxId=(UUID)box.get("id");
            var record=catalog.record(context(archivistB,"ARCHIVE_BOX",boxId,"CREATE_RECORD",null),Sql.map("boxId",boxId.toString(),"itemNo",1,"categoryCode",category,"carrierType","ELECTRONIC","title","通用台账文件-"+category,"keywords",List.of("通用台账"),"ownerId",people.get("managerB").toString(),"archiveDate",today.toString(),"templateVersionId",commonRecord.toString(),"extensionValues",extras));
            UUID recordId=(UUID)record.get("id");
            assertEquals(category,maintenance.detail(archivistB,"BOX",boxId).get("categoryCode"));assertEquals(category,maintenance.detail(archivistB,"RECORD",recordId).get("categoryCode"));
            String changedCategory=category.equals("PURCHASE_CONTRACT")?"QUALITY":"PURCHASE_CONTRACT";
            for(String type:List.of("BOX","RECORD")){
                UUID id=type.equals("BOX")?boxId:recordId;var before=maintenance.detail(archivistB,type,id);
                var changed=maintenance.updateDraft(context(archivistB,type.equals("BOX")?"ARCHIVE_BOX":"ARCHIVE_RECORD",id,"UPDATE_DRAFT",((Number)before.get("rowVersion")).intValue()),type,id,Sql.map("changes",Sql.map("categoryCode",changedCategory),"reason","业务类别调整沿用通用模板"));
                assertEquals(changedCategory,changed.get("categoryCode"));assertEquals(before.get("templateVersionId"),changed.get("templateVersionId"));assertEquals(extras,changed.get("extensionValues"));
            }
        }
    }
    /** 通用只放开业务类别，盒件类型、启用状态及字段格式仍由真实模板校验。 */
    @Test void commonLedgerRetainsTemplateTypeStateAndFieldValidation() {
        UUID commonBox=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_BOX_LEDGER' AND state='ACTIVE'").get("id");
        assertThrows(ApiFault.class,()->templateService.validate(commonBox,"PURCHASE_CONTRACT","RECORD",Map.of(),Map.of(),true));
        assertThrows(ApiFault.class,()->templateService.validate(commonBox,"PURCHASE_CONTRACT","BOX",Sql.map("undefinedField","不支持"),Map.of(),true));
        assertThrows(ApiFault.class,()->templateService.validate(commonBox,"PURCHASE_CONTRACT","BOX",Sql.map("copyCount","四十五"),Map.of(),true));
        assertThrows(ApiFault.class,()->templateService.validate(commonBox,"PURCHASE_CONTRACT","BOX",Sql.map("sourceExpiryDate","无效日期"),Map.of(),true));
        UUID temporary=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","COMMON_STATE_"+UUID.randomUUID(),"name","合成通用模板状态验收","categoryCode","COMMON","objectType","BOX","fields",List.of())).get("id");
        templateService.state(context(supervisor,"ARCHIVE_REVISION",temporary,"SET_DIRECTORY_TEMPLATE_STATE",1),temporary,Sql.map("state","DISABLED"));
        assertThrows(ApiFault.class,()->templateService.validate(temporary,"PURCHASE_CONTRACT","BOX",Map.of(),Map.of(),true));
        assertEquals(Map.of(),templateService.validate(temporary,"QUALITY","BOX",Map.of(),Map.of(),false));
        assertThrows(ApiFault.class,()->templateService.validate(templateBox,"PURCHASE_CONTRACT","BOX",Map.of(),Map.of(),true));
    }
    /** 已归档目录更换为通用模板后，业务类别更正仍须经真实独立审批。 */
    @Test void publishedCommonLedgerCategoryChangeUsesExistingApproval() throws Exception {
        UUID record=published("GENERAL",b);var before=authority.record(record);UUID common=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_RECORD_LEDGER' AND state='ACTIVE'").get("id");
        var pending=change(record,Sql.map("categoryCode","PURCHASE_CONTRACT","templateVersionId",common.toString(),"extensionValues",Sql.map("fileType","采购合同","copyCount",2)),"CORRECTION");
        assertEquals("ADMIN",authority.record(record).get("category_code"));approve(submitChange(pending));
        assertEquals("PURCHASE_CONTRACT",authority.record(record).get("category_code"));assertEquals(common,authority.record(record).get("template_version_id"));
        approve(submitChange(change(record,Sql.map("categoryCode","QUALITY"),"CORRECTION")));
        assertEquals("QUALITY",authority.record(record).get("category_code"));assertEquals(before.get("current_pdf_version_id"),authority.record(record).get("current_pdf_version_id"));assertEquals(2,CatalogTemplates.map(authority.record(record).get("extension_values")).get("copyCount"));
    }
    @Test void draftMaintenanceAppendsRevisionAndDeletesWithoutErasingHistory() {
        UUID box=draftBox(),record=draftRecord(box);var initial=maintenance.detail(archivistB,"BOX",box);
        Object prior=db.required("SELECT candidate_revision_id FROM archive.box WHERE id=?",box).get("candidate_revision_id");
        var body=Sql.map("changes",Sql.map("title","修改后的登记盒"),"reason","更正登记题名");var ctx=context(archivistB,"ARCHIVE_BOX",box,"UPDATE_DRAFT",1);
        var changed=maintenance.updateDraft(ctx,"BOX",box,body);assertEquals("修改后的登记盒",changed.get("title"));assertEquals(2,db.count("SELECT count(*) FROM archive.box_revision WHERE box_id=?",box));assertNotEquals(prior,db.required("SELECT candidate_revision_id FROM archive.box WHERE id=?",box).get("candidate_revision_id"));
        assertEquals(changed.get("id"),maintenance.updateDraft(ctx,"BOX",box,body).get("id"));assertEquals(2,db.count("SELECT count(*) FROM archive.box_revision WHERE box_id=?",box));
        maintenance.updateDraft(context(archivistB,"ARCHIVE_RECORD",record,"UPDATE_DRAFT",1),"RECORD",record,Sql.map("changes",Sql.map("title","修改后的文件","itemNo",2),"reason","更正文件登记"));
        assertEquals(2,db.count("SELECT item_no FROM archive.record WHERE id=?",record));
        maintenance.deleteDraft(context(archivistB,"ARCHIVE_RECORD",record,"DELETE_DRAFT",2),"RECORD",record,Sql.map("reason","删除误登记文件"));
        assertEquals(0,((Number)catalog.records(archivistB,Map.of("boxId",box.toString())).get("total")).intValue());
        maintenance.deleteDraft(context(archivistB,"ARCHIVE_BOX",box,"DELETE_DRAFT",2),"BOX",box,Sql.map("reason","删除误登记盒"));
        assertEquals("WITHDRAWN",maintenance.detail(archivistB,"BOX",box).get("state"));assertEquals(2,db.count("SELECT count(*) FROM archive.catalog_change WHERE object_id=?",box));assertEquals(2,db.count("SELECT count(*) FROM archive.box_revision WHERE box_id=?",box));assertEquals(initial.get("boxNo"),maintenance.detail(archivistB,"BOX",box).get("boxNo"));
    }
    @Test void deletingDraftBoxWithdrawsAllDraftsAndKeepsEachHistory() {
        UUID box=draftBox(),first=draftRecord(box),second=draftRecord(box,2),withdrawn=draftRecord(box,3);
        maintenance.deleteDraft(context(archivistB,"ARCHIVE_RECORD",withdrawn,"DELETE_DRAFT",1),"RECORD",withdrawn,Sql.map("reason","先前已撤回的条目"));
        var numbers=db.rows("SELECT id,file_no,item_no,candidate_revision_id FROM archive.record WHERE box_id=? ORDER BY id",box);
        var summary=CatalogTemplates.map(maintenance.detail(archivistB,"BOX",box).get("draftDeletion"));assertEquals(2,((Number)summary.get("draftCount")).intValue());assertEquals(0,((Number)summary.get("attachmentCount")).intValue());
        var ctx=context(archivistB,"ARCHIVE_BOX",box,"DELETE_DRAFT",1);var body=Sql.map("reason","确认整盒及盒内草稿撤回");
        maintenance.deleteDraft(ctx,"BOX",box,body);maintenance.deleteDraft(ctx,"BOX",box,body);
        assertEquals("WITHDRAWN",maintenance.detail(archivistB,"BOX",box).get("state"));assertEquals(3,db.count("SELECT count(*) FROM archive.record WHERE box_id=? AND state='WITHDRAWN'",box));
        assertEquals(numbers,db.rows("SELECT id,file_no,item_no,candidate_revision_id FROM archive.record WHERE box_id=? ORDER BY id",box));
        for(UUID record:List.of(first,second,withdrawn))assertEquals(1,db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type='RECORD' AND object_id=? AND kind='WITHDRAWAL' AND state='APPLIED'",record));
        assertEquals(1,db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type='BOX' AND object_id=? AND kind='WITHDRAWAL'",box));
        assertEquals(0L,catalog.records(archivistB,Map.of("boxId",box.toString())).get("total"));assertEquals(1,db.count("SELECT count(*) FROM archive.box_revision WHERE box_id=?",box));
    }
    /** 合成旧数据的盒件状态不一致，不能把已有状态标记当成真实审批已经通过。 */
    @Test void deletingBoxWithReviewingOrPublishedChildNeverPartiallyWithdrawsDrafts() {
        UUID box=draftBox(),draft=draftRecord(box),other=draftRecord(box,2);
        for(String state:List.of("PENDING_REVIEW","PUBLISHED")) {
            db.update("UPDATE archive.record SET state=?,current_revision_id=CASE WHEN ?='PUBLISHED' THEN candidate_revision_id ELSE NULL END WHERE id=?",state,state,other);
            var before=db.rows("SELECT id,state,row_version FROM archive.record WHERE box_id=? ORDER BY id",box);
            assertThrows(ApiFault.class,()->maintenance.deleteDraft(context(archivistB,"ARCHIVE_BOX",box,"DELETE_DRAFT",1),"BOX",box,Sql.map("reason","在审或已归档资料仍须原审批流程")));
            assertEquals(before,db.rows("SELECT id,state,row_version FROM archive.record WHERE box_id=? ORDER BY id",box));assertEquals(0,db.count("SELECT count(*) FROM archive.catalog_change WHERE object_id IN (?,?)",box,draft));
        }
        assertEquals("DRAFT",authority.record(draft).get("state"));assertEquals("DRAFT",maintenance.detail(archivistB,"BOX",box).get("state"));
    }
    @Test void changedCatalogNotifiesCurrentManagersIncludingItWithoutOtherDepartmentClerks() {
        UUID box=draftBox();approve(review(archivistB,"BOX",box,null));int version=((Number)maintenance.detail(archivistB,"BOX",box).get("rowVersion")).intValue();
        var changed=maintenance.create(context(archivistB,"ARCHIVE_BOX",box,"CREATE_CATALOG_CHANGE",version),"BOX",box,Sql.map("changes",Sql.map("notes","真实通知范围验收"),"reason","普通目录备注修改"));
        var recipients=db.rows("SELECT user_id FROM ops.business_notification WHERE source_kind='CATALOG_CHANGE' AND source_id=?",changed.get("id")).stream().map(row->row.get("user_id")).toList();
        assertTrue(recipients.containsAll(List.of(admin.userId(),supervisor.userId(),archivistB.userId())));assertFalse(recipients.contains(archivistA.userId()));assertFalse(recipients.contains(applicant.userId()));assertFalse(recipients.contains(people.get("managerB")));
    }
    /** 桌面验收仅生成隔离草稿盒，目标文件只含对象标识和题名，不输出账号凭据。 */
    @Test @EnabledIfEnvironmentVariable(named="IMS_DRAFT_BOX_UI_FIXTURE",matches=".+") void desktopDraftBoxFixture() throws Exception {
        UUID box=draftBox(),first=draftRecord(box),second=draftRecord(box,2);upload(archivistB,second);String title="整盒撤回界面验收-"+box.toString().substring(0,8);
        maintenance.updateDraft(context(archivistB,"ARCHIVE_BOX",box,"UPDATE_DRAFT",1),"BOX",box,Sql.map("changes",Sql.map("title",title),"reason","生成隔离界面验收对象"));
        Path target=Path.of(System.getenv("IMS_DRAFT_BOX_UI_FIXTURE"));Files.writeString(target,Crypto.json(Sql.map("boxId",box,"title",title,"recordIds",List.of(first,second))));PrivateFiles.restrict(target);
    }
    @Test void deletingWholeDraftBoxChecksAuthorityAndVersionBeforeAnyChildChanges() {
        UUID box=draftBox(),record=draftRecord(box);
        assertThrows(ApiFault.class,()->maintenance.deleteDraft(context(archivistA,"ARCHIVE_BOX",box,"DELETE_DRAFT",1),"BOX",box,Sql.map("reason","其他部门不能撤回")));
        assertThrows(ApiFault.class,()->maintenance.deleteDraft(context(archivistB,"ARCHIVE_BOX",box,"DELETE_DRAFT",99),"BOX",box,Sql.map("reason","过期版本不能撤回")));
        assertEquals("DRAFT",authority.record(record).get("state"));assertEquals("DRAFT",maintenance.detail(archivistB,"BOX",box).get("state"));assertEquals(0,db.count("SELECT count(*) FROM archive.catalog_change WHERE object_id IN (?,?)",box,record));
    }
    @Test void draftMaintenanceRejectsOtherDepartmentsStaleVersionsAndReviewingParents() {
        UUID box=draftBox(),record=draftRecord(box);var body=Sql.map("changes",Sql.map("title","合法修改"),"reason","维护校验");
        assertThrows(ApiFault.class,()->maintenance.updateDraft(context(archivistA,"ARCHIVE_BOX",box,"UPDATE_DRAFT",1),"BOX",box,body));
        maintenance.updateDraft(context(archivistB,"ARCHIVE_BOX",box,"UPDATE_DRAFT",1),"BOX",box,body);
        assertThrows(ApiFault.class,()->maintenance.updateDraft(context(archivistB,"ARCHIVE_BOX",box,"UPDATE_DRAFT",1),"BOX",box,body));
        assertThrows(ApiFault.class,()->maintenance.updateDraft(context(archivistB,"ARCHIVE_BOX",box,"UPDATE_DRAFT",2),"BOX",box,Sql.map("changes",Sql.map("departmentId",a.toString()),"reason","禁止更改归属")));
        review(archivistB,"BOX",box,null);
        assertThrows(ApiFault.class,()->maintenance.updateDraft(context(archivistB,"ARCHIVE_RECORD",record,"UPDATE_DRAFT",1),"RECORD",record,body));
        assertEquals(1,db.count("SELECT count(*) FROM archive.record_revision WHERE record_id=?",record));
    }
    @Test void boxListPaginatesAndExcludesDeletedDrafts() {
        String title="分页登记验收"+UUID.randomUUID();List<UUID> ids=new ArrayList<>();
        for(int i=0;i<3;i++){UUID box=draftBox();ids.add(box);maintenance.updateDraft(context(archivistB,"ARCHIVE_BOX",box,"UPDATE_DRAFT",1),"BOX",box,Sql.map("changes",Sql.map("title",title+"-"+i),"reason","分页目录验收"));}
        var first=catalog.boxes(archivistB,Map.of("keyword",title,"page","1","pageSize","2"));assertEquals(3L,first.get("total"));assertEquals(2,((List<?>)first.get("items")).size());
        var second=catalog.boxes(archivistB,Map.of("keyword",title,"page","2","pageSize","2"));assertEquals(1,((List<?>)second.get("items")).size());
        maintenance.deleteDraft(context(archivistB,"ARCHIVE_BOX",ids.getFirst(),"DELETE_DRAFT",2),"BOX",ids.getFirst(),Sql.map("reason","分页删除验收"));
        assertEquals(2L,catalog.boxes(archivistB,Map.of("keyword",title)).get("total"));assertEquals(1L,catalog.boxes(archivistB,Map.of("keyword",title,"state","WITHDRAWN")).get("total"));
        assertEquals(0L,catalog.boxes(archivistA,Map.of("keyword",title)).get("total"));
    }
    /** 台账列在真实查询中分别命中，组合条件与总数在分页前生效。 */
    @Test void boxListFiltersEveryDisplayedColumnBeforePagination() throws Exception {
        String prefix="筛选验收-"+UUID.randomUUID(),customType="纸质+电子";
        var first=filterBox(prefix+"_合同100%","IMPORTANT","10",Sql.map("originalNumber",prefix+"_%","preservationType","纸质","copyCount",40,"cabinetNo","13","columnNo","A","layerNo","3"));
        var second=filterBox(prefix+"-合同二","IMPORTANT","10",Sql.map("originalNumber",prefix+"AX","preservationType","纸质","copyCount",45,"cabinetNo","13","columnNo","B","layerNo","4"));
        var third=filterBox(prefix+"-合同三","GENERAL","05",Sql.map("preservationType","电子","copyCount",1,"cabinetNo","历史库房西区"));
        var permanent=filterBox(prefix+"-永久合同","CORE","YJ",Sql.map("preservationType",customType,"copyCount",7));
        UUID firstId=(UUID)first.get("id"),secondId=(UUID)second.get("id");
        assertEquals(List.of(firstId),boxIds(archivistB,Map.of("boxNo",prefix+"_%")));
        assertEquals(List.of(firstId),boxIds(archivistB,Map.of("boxNo",first.get("boxNo").toString())));
        assertEquals(List.of(firstId),boxIds(archivistB,Map.of("title",prefix+"_合同100%")));
        assertEquals(List.of(firstId),boxIds(archivistB,Map.of("title",prefix,"location","13柜 / A列 / 3层")));
        assertEquals(2,boxIds(archivistB,Map.of("title",prefix,"location","13柜")).size());
        assertEquals(List.of((UUID)third.get("id")),boxIds(archivistB,Map.of("title",prefix,"location","历史库房西区","classification","GENERAL","retentionCode","05","preservationType","电子","copyCountMin","1","copyCountMax","1")));
        assertEquals(List.of(firstId),boxIds(archivistB,Map.of("title",prefix,"preservationType","纸质","classification","IMPORTANT","copyCountMin","40","copyCountMax","40","retentionCode","10","expiryFrom","2035-09-23","expiryTo","2035-09-23","state","DRAFT")));
        assertEquals(List.of(firstId),boxIds(archivistB,Map.of("title",prefix,"copyCountMax","40","expiryFrom","2035-09-23")));
        assertEquals(List.of(secondId),boxIds(archivistB,Map.of("title",prefix,"copyCountMin","45","expiryTo","2035-09-23")));
        assertEquals(List.of((UUID)permanent.get("id")),boxIds(archivistB,Map.of("title",prefix,"retentionCode","YJ")));
        assertEquals(0L,catalog.boxes(archivistB,Map.of("title",prefix,"retentionCode","YJ","expiryFrom","2030-01-01")).get("total"));
        var pageOne=catalog.boxes(archivistB,Map.of("title",prefix,"preservationType","纸质","pageSize","1"));
        var pageTwo=catalog.boxes(archivistB,Map.of("title",prefix,"preservationType","纸质","pageSize","1","page","2"));
        assertEquals(2L,pageOne.get("total"));assertEquals(2L,pageTwo.get("total"));assertEquals(1,((List<?>)pageOne.get("items")).size());assertNotEquals(pageOne.get("items"),pageTwo.get("items"));
        assertEquals(0L,catalog.boxes(archivistA,Map.of("title",prefix,"preservationType",customType)).get("total"));
        assertTrue(((List<?>)catalog.boxFilterOptions(archivistB).get("preservationTypes")).contains(customType));
        assertFalse(((List<?>)catalog.boxFilterOptions(archivistA).get("preservationTypes")).contains(customType));
        approve(review(archivistB,"BOX",secondId,null));
        assertEquals(List.of(secondId),boxIds(archivistB,Map.of("title",prefix,"state","PUBLISHED","preservationType","纸质")));
        String fixturePath=System.getenv("IMS_LEDGER_FILTER_UI_FIXTURE");
        if(fixturePath!=null){Path target=Path.of(fixturePath);Files.writeString(target,Crypto.json(Sql.map("prefix",prefix,"targetId",firstId,"title",first.get("title"),"originalNumber",prefix+"_%","boxNo",first.get("boxNo"),"customType",customType)));PrivateFiles.restrict(target);}
    }
    /** 非法查询在入口返回参数错误；零下限和只填一端的有效条件可以正常查询。 */
    @Test void boxListRejectsInvalidFilterRangesAndUnauthorizedOptions() {
        for(var query:List.of(Map.of("copyCountMin","-1"),Map.of("copyCountMax","1.5"),Map.of("copyCountMin","50","copyCountMax","40"),Map.of("expiryFrom","2035-02-30"),Map.of("expiryFrom","2036-01-01","expiryTo","2035-01-01"),Map.of("classification","UNKNOWN"),Map.of("retentionCode","99")))assertThrows(ApiFault.class,()->catalog.boxes(archivistB,query));
        assertDoesNotThrow(()->catalog.boxes(archivistB,Map.of("copyCountMin","0")));
        assertThrows(ApiFault.class,()->catalog.boxFilterOptions(applicant));
    }
    /** 筛选用盒通过正式登记，完整保留实际扩展字段及自动计算的到期日期。 */
    private Map<String,Object> filterBox(String title,String classification,String retention,Map<String,Object> extensions) {
        UUID common=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_BOX_LEDGER' AND state='ACTIVE'").get("id");
        String carrier=switch(Objects.toString(extensions.get("preservationType"))){case "电子"->"ELECTRONIC";case "纸质+电子"->"MIXED";default->"PAPER";};
        extensions.put("paperTags",carrier.equals("ELECTRONIC")?List.of():List.of("纸质原件"));
        return catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),Sql.map("departmentId",b.toString(),"title",title,"classification",classification,"retentionCode",retention,"archiveDate","2025-09-23","keywords",List.of("筛选验收"),"templateVersionId",common.toString(),"categoryCode","COMMON","ownerId",people.get("managerB").toString(),"numberMonth","2025-09-01","carrierType",carrier,"location","历史库房西区","extensionValues",extensions));
    }
    /** 仅提取真实服务结果中的ID，不在测试中另写筛选逻辑。 */
    @SuppressWarnings("unchecked") private List<UUID> boxIds(Actor actor,Map<String,String> query) {
        return ((List<Map<String,Object>>)catalog.boxes(actor,query).get("items")).stream().map(row->(UUID)row.get("id")).toList();
    }
    @Test void draftFileCannotBypassPendingBoxCorrection() {
        UUID box=draftBox();approve(review(archivistB,"BOX",box,null));UUID file=draftRecord(box);
        int version=((Number)maintenance.detail(archivistB,"BOX",box).get("rowVersion")).intValue();
        maintenance.create(context(archivistB,"ARCHIVE_BOX",box,"CREATE_CATALOG_CHANGE",version),"BOX",box,Sql.map("changes",Sql.map("title","待更正盒名"),"reason","父盒更正验收","kind","CORRECTION"));
        assertThrows(ApiFault.class,()->maintenance.updateDraft(context(archivistB,"ARCHIVE_RECORD",file,"UPDATE_DRAFT",1),"RECORD",file,Sql.map("changes",Sql.map("title","不应写入"),"reason","父盒冻结期间禁止改件")));
        assertThrows(ApiFault.class,()->maintenance.deleteDraft(context(archivistB,"ARCHIVE_RECORD",file,"DELETE_DRAFT",1),"RECORD",file,Sql.map("reason","父盒冻结期间禁止删件")));
        assertEquals("DRAFT",maintenance.detail(archivistB,"RECORD",file).get("state"));
    }
    @Test void publishedBoxCannotBeDeletedThroughDraftEndpoint() {
        UUID box=draftBox();approve(review(archivistB,"BOX",box,null));
        int version=((Number)maintenance.detail(archivistB,"BOX",box).get("rowVersion")).intValue();
        assertThrows(ApiFault.class,()->maintenance.deleteDraft(context(archivistB,"ARCHIVE_BOX",box,"DELETE_DRAFT",version),"BOX",box,Sql.map("reason","不能绕过归档撤下复核")));
        assertEquals("PUBLISHED",maintenance.detail(archivistB,"BOX",box).get("state"));
    }
    @Test void ordinaryEditKeepsApprovedVersionAndReplayIsStable() throws Exception {UUID id=published("GENERAL",b);policy(id,false,List.of(applicant.userId()));UUID old=(UUID)authority.record(id).get("current_revision_id");int version=((Number)authority.record(id).get("row_version")).intValue();var ctx=context(archivistB,"ARCHIVE_RECORD",id,"CREATE_CATALOG_CHANGE",version);var body=Sql.map("changes",Sql.map("notes","新的目录备注"),"reason","普通字段更正");var first=maintenance.create(ctx,"RECORD",id,body);assertEquals("APPLIED",first.get("state"));assertEquals(first.get("id"),maintenance.create(ctx,"RECORD",id,body).get("id"));assertEquals(old,authority.record(id).get("current_revision_id"));assertEquals("新的目录备注",authority.record(id).get("notes"));assertTrue(reading.page(applicant,view(applicant,id),1,UUID.randomUUID()).length>0);assertThrows(ApiFault.class,()->maintenance.create(ctx,"RECORD",id,Sql.map("changes",Sql.map("notes","不同载荷"),"reason","更正")));}
    @Test void criticalCorrectionPublishesOnlyAfterApprovalAndOldGrantStops() throws Exception {UUID id=published("GENERAL",b);policy(id,false,List.of(applicant.userId()));UUID usage=view(applicant,id);Object old=authority.record(id).get("title");var c=change(id,Sql.map("title","更正后题名"),"CORRECTION");UUID flow=submitChange(c);assertEquals(old,authority.record(id).get("title"));assertTrue(reading.page(applicant,usage,1,UUID.randomUUID()).length>0);approve(flow);assertEquals("更正后题名",authority.record(id).get("title"));assertThrows(ApiFault.class,()->reading.page(applicant,usage,1,UUID.randomUUID()));assertThrows(ApiFault.class,()->view(applicant,id));assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant g WHERE g.record_id=? AND archive.grant_valid(g.id,g.user_id)",id));}
    @Test void approvalAndRejectionAcceptOmittedOrBlankOpinion() throws Exception {
        UUID record=published("GENERAL",b);
        for(String result:List.of("APPROVE","REJECT")) for(boolean omitted:List.of(true,false)) {
            var correction=change(record,Sql.map("title","选填审批意见验收"+UUID.randomUUID()),"CORRECTION");UUID flow=submitChange(correction);
            var step=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);
            UUID id=(UUID)step.get("id");Actor actor=actors.get(step.get("assignee_id"));
            var body=omitted?Sql.map("result",result):Sql.map("result",result,"comment","   ");
            workflow.decide(context(actor,"WORKFLOW_STEP",id,"DECIDE_STEP",((Number)step.get("row_version")).intValue()),id,body);
            var decision=db.required("SELECT result,comment FROM workflow.decision WHERE step_id=?",id);
            assertEquals(result,decision.get("result"));assertEquals("",decision.get("comment"));
            if(result.equals("APPROVE")) {
                assertNotEquals(id,db.required("SELECT current_step_id FROM workflow.instance WHERE id=?",flow).get("current_step_id"));
                UUID change=(UUID)correction.get("id");int version=(int)db.count("SELECT row_version FROM archive.catalog_change WHERE id=?",change);
                maintenance.cancel(context(archivistB,"ARCHIVE_REVISION",change,"CANCEL_CATALOG_CHANGE",version),change,Sql.map("reason","选填意见节点验收完成"));
            } else assertEquals("REJECTED",db.required("SELECT state FROM archive.catalog_change WHERE workflow_id=?",flow).get("state"));
        }
    }
    @Test void rejectedCorrectionLeavesPublishedContent() throws Exception {UUID id=published("GENERAL",b);Object old=authority.record(id).get("title");UUID flow=submitChange(change(id,Sql.map("title","不应生效"),"CORRECTION"));var s=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);Actor actor=actors.get(s.get("assignee_id"));workflow.decide(context(actor,"WORKFLOW_STEP",(UUID)s.get("id"),"DECIDE_STEP",((Number)s.get("row_version")).intValue()),(UUID)s.get("id"),Sql.map("result","REJECT","comment","不同意此更正"));assertEquals(old,authority.record(id).get("title"));assertEquals("REJECTED",db.required("SELECT state FROM archive.catalog_change WHERE workflow_id=?",flow).get("state"));}
    @Test void unauthorizedAndConcurrentChangesAreRejected() throws Exception {UUID id=published("GENERAL",b);assertThrows(ApiFault.class,()->maintenance.detail(applicant,"RECORD",id));assertThrows(ApiFault.class,()->maintenance.detail(archivistA,"RECORD",id));assertThrows(ApiFault.class,()->maintenance.create(context(archivistA,"ARCHIVE_RECORD",id,"CREATE_CATALOG_CHANGE",1),"RECORD",id,Sql.map("changes",Sql.map("notes","越权"),"reason","越权")));change(id,Sql.map("title","待批标题"),"CORRECTION");assertThrows(ApiFault.class,()->change(id,Sql.map("notes","不能覆盖候选"),"CORRECTION"));}
    @Test void withdrawalBlocksUseButPreservesPdfAndPendingRequests() throws Exception {UUID id=published("GENERAL",b);policy(id,false,List.of(applicant.userId()));UUID usage=view(applicant,id),batch=submitBorrow(applicant,List.of(id));long pdfs=db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",id);UUID flow=submitChange(change(id,Map.of(),"WITHDRAWAL"));approve(flow);assertEquals("WITHDRAWN",authority.record(id).get("state"));assertEquals(pdfs,db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",id));assertEquals(1,db.count("SELECT count(*) FROM archive.borrow_batch WHERE id=?",batch));assertThrows(ApiFault.class,()->reading.page(applicant,usage,1,UUID.randomUUID()));assertThrows(ApiFault.class,()->authority.detail(applicant,id));}
    @Test void pdfCorrectionNeedsQualityAndKeepsOldBytes() throws Exception {UUID id=published("GENERAL",b);policy(id,false,List.of(applicant.userId()));Object old=authority.record(id).get("current_pdf_version_id");UUID upload=candidateUpload(archivistB,id);var c=maintenance.create(context(archivistB,"ARCHIVE_RECORD",id,"CREATE_CATALOG_CHANGE",((Number)authority.record(id).get("row_version")).intValue()),"RECORD",id,Sql.map("changes",Map.of(),"reason","完整PDF更正","uploadSessionId",upload.toString(),"source","CORRECTION_TEST"));UUID change=(UUID)c.get("id");assertThrows(ApiFault.class,()->submitChange(c));assertThrows(ApiFault.class,()->maintenance.review(applicant,change,(UUID)c.get("candidatePdfId")));var preview=reading.create(archivistB,Sql.map("reviewContext",Sql.map("type","CATALOG_CHANGE","id",change.toString(),"candidatePdfVersionId",c.get("candidatePdfId").toString())),UUID.randomUUID(),UUID.randomUUID());reading.process(archivistB,(UUID)preview.get("jobId"),UUID.randomUUID());assertTrue(reading.page(archivistB,(UUID)preview.get("usageId"),1,UUID.randomUUID()).length>0);var checked=maintenance.quality(context(archivistB,"ARCHIVE_REVISION",change,"CONFIRM_CATALOG_QUALITY",((Number)c.get("rowVersion")).intValue()),change,Sql.map("confirmed",true,"checkedPageCount",c.get("pageCount"),"notes","已核对全部页面"));approve(submitChange(checked));assertNotEquals(old,authority.record(id).get("current_pdf_version_id"));assertEquals(2,db.count("SELECT count(*) FROM archive.pdf_version WHERE record_id=?",id));assertThrows(ApiFault.class,()->reading.page(archivistB,(UUID)preview.get("usageId"),1,UUID.randomUUID()));}
    @Test void revokedApplicantCannotSubmitOrReadCandidate() throws Exception {UUID id=published("GENERAL",b);var c=change(id,Sql.map("title","尚未送审"),"CORRECTION");UUID role=(UUID)db.required("SELECT id FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",archivistB.userId()).get("id");db.update("UPDATE iam.user_role SET state='ENDED' WHERE id=?",role);try{assertThrows(ApiFault.class,()->submitChange(c));assertThrows(ApiFault.class,()->maintenance.change(archivistB,(UUID)c.get("id")));}finally{db.update("UPDATE iam.user_role SET state='ACTIVE' WHERE id=?",role);}}
    @Test void staleObjectCannotBeApproved() throws Exception {UUID id=published("GENERAL",b);UUID flow=submitChange(change(id,Sql.map("title","过期候选"),"CORRECTION"));db.update("UPDATE archive.record SET row_version=row_version+1 WHERE id=?",id);var s=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);assertThrows(ApiFault.class,()->workflow.decide(context(actors.get(s.get("assignee_id")),"WORKFLOW_STEP",(UUID)s.get("id"),"DECIDE_STEP",((Number)s.get("row_version")).intValue()),(UUID)s.get("id"),Sql.map("result","APPROVE","comment","已过期")));assertEquals(0,db.count("SELECT count(*) FROM workflow.decision WHERE step_id=?",s.get("id")));}
    @Test void templatesPreserveHistoryAndValidateFields() {var f=Sql.map("code","contractCode","name","合同号","type","TEXT","required",true,"critical",true,"visible",true,"searchable",true,"enabled",true,"order",0,"options",List.of());String key="EXT_"+UUID.randomUUID();var body=Sql.map("templateKey",key,"name","合同目录","categoryCode","ADMIN","objectType","RECORD","fields",List.of(f));var template=templateService.save(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),null,body);UUID id=(UUID)template.get("id");assertThrows(ApiFault.class,()->templateService.validate(id,"ADMIN","RECORD",Map.of(),Map.of(),true));assertEquals("A001",templateService.validate(id,"ADMIN","RECORD",Sql.map("contractCode","A001"),Map.of(),true).get("contractCode"));var next=templateService.save(context(supervisor,"ARCHIVE_REVISION",id,"REVISE_DIRECTORY_TEMPLATE",1),id,body);assertNotEquals(id,next.get("id"));var reduced=new LinkedHashMap<>(f);reduced.put("critical",false);assertThrows(ApiFault.class,()->templateService.save(context(supervisor,"ARCHIVE_REVISION",(UUID)next.get("id"),"REVISE_DIRECTORY_TEMPLATE",1),(UUID)next.get("id"),Sql.map("templateKey",key,"name","错误降级","categoryCode","ADMIN","objectType","RECORD","fields",List.of(reduced))));templateService.state(context(supervisor,"ARCHIVE_REVISION",id,"SET_DIRECTORY_TEMPLATE_STATE",1),id,Sql.map("state","DISABLED"));assertEquals("A001",templateService.validate(id,"ADMIN","RECORD",Sql.map("contractCode","A001"),Sql.map("contractCode","A001"),false).get("contractCode"));assertThrows(ApiFault.class,()->templateService.validate(id,"ADMIN","RECORD",Sql.map("contractCode","A001"),Map.of(),true));}
    @Test void retentionRemindersDeduplicateAndNeverWithdraw() throws Exception {UUID id=published("GENERAL",b);LocalDate expiry=LocalDate.now(WorkCalendarService.ZONE);approve(submitChange(change(id,Sql.map("archiveDate",expiry.minusYears(10).toString()),"CORRECTION")));maintenance.reminders();long first=db.count("SELECT count(*) FROM ops.business_notification WHERE source_kind='RETENTION' AND source_id=?",id);assertTrue(first>=2);maintenance.reminders();assertEquals(first,db.count("SELECT count(*) FROM ops.business_notification WHERE source_kind='RETENTION' AND source_id=?",id));assertEquals("PUBLISHED",authority.record(id).get("state"));assertEquals(0L,maintenance.retention(archivistA,Map.of()).get("total"));assertThrows(ApiFault.class,()->maintenance.retention(applicant,Map.of()));}
    @Test void restoredCatalogWatermarkCannotReviveOldMetadata() throws Exception {UUID id=published("GENERAL",b);approve(submitChange(change(id,Map.of(),"WITHDRAWAL")));int epoch=(int)db.count("SELECT catalog_epoch FROM platform_meta.instance");db.update("UPDATE platform_meta.instance SET catalog_epoch=?",epoch-1);try{assertEquals(503,assertThrows(ApiFault.class,()->identity.background(applicant.sessionId())).status);}finally{db.update("UPDATE platform_meta.instance SET catalog_epoch=?",epoch);}}

    private UUID paperBox(UUID department,String carrier) {Actor clerk=department.equals(a)?archivistA:archivistB;LocalDate today=LocalDate.now(WorkCalendarService.ZONE);UUID box=(UUID)catalog.box(context(clerk,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),Sql.map("departmentId",department.toString(),"title","合成实物盒","safeTitle","合成可申请盒","classification","GENERAL","retentionCode","10","archiveDate",today.toString(),"physicalFileCount",0,"location","合成库房A-01","keywords",List.of("实物"),"templateVersionId",templateBox.toString(),"categoryCode","ADMIN","ownerId",people.get(department.equals(a)?"managerA":"managerB").toString(),"numberMonth",today.withDayOfMonth(1).toString(),"carrierType",carrier,"extensionValues",Sql.map("paperTags",carrier.equals("ELECTRONIC")?List.of():List.of("纸质原件")))).get("id");approve(review(clerk,"BOX",box,null));return box;}
    private UUID paperRecord(UUID box,int number) {var parent=db.required("SELECT * FROM archive.box WHERE id=?",box);Actor clerk=parent.get("department_id").equals(a)?archivistA:archivistB;UUID record=(UUID)catalog.record(context(clerk,"ARCHIVE_BOX",box,"CREATE_RECORD",null),Sql.map("boxId",box.toString(),"itemNo",number,"categoryCode","ADMIN","carrierType","PAPER","title","合成纸质合同"+number,"safeTitle","可申请原件"+number,"location","合成库房A-01-"+number,"keywords",List.of("纸质"),"ownerId",people.get(parent.get("department_id").equals(a)?"managerA":"managerB").toString(),"archiveDate",LocalDate.now(WorkCalendarService.ZONE).toString(),"templateVersionId",templateRecord.toString())).get("id");approve(review(clerk,"RECORD",record,null));paperPolicy(record,clerk);return record;}
    private void paperPolicy(UUID record,Actor clerk) {var row=authority.record(record);UUID flow=(UUID)policies.propose(context(clerk,"ARCHIVE_POLICY",record,"REQUEST_ACCESS_POLICY",null),Sql.map("objectType","RECORD","objectId",record.toString(),"departmentScope",List.of(a.toString(),b.toString()),"titleVisibility","SAFE_TITLE","safeSearchFields",List.of("safeTitle"),"allowOwnerDepartmentView",false,"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",null,"userIds",List.of(),"validFrom",Instant.now().minusSeconds(1).toString(),"validUntil",Instant.now().plusSeconds(864000).toString(),"reason","仅开放纸质安全题名")).get("id");approve(flow);}
    private UUID physical(UUID record) {return (UUID)db.required("SELECT id FROM archive.physical_item WHERE record_id=?",record).get("id");}
    private UUID submitPaper(List<UUID> ids,int days) {return (UUID)borrow.submit(plain(applicant,"SUBMIT_PAPER_BORROW"),Sql.map("form","PAPER","actions",List.of("BORROW_PHYSICAL"),"purpose","合成实物借阅验收","period",Sql.map("mode","NATURAL_DAYS","amount",days),"items",ids.stream().map(id->Sql.map("physicalItemId",id.toString())).toList())).get("id");}
    private UUID request(UUID batch) {return (UUID)db.required("SELECT id FROM archive.borrow_request WHERE batch_id=?",batch).get("id");}
    private Map<String,Object> deliveryBody(UUID item) {return Sql.map("physicalItemIds",List.of(item.toString()),"actualDeliveryAt",Instant.now().toString(),"actualQuantity",1,"condition","已核对实际原件、附件和完好情况");}
    private Map<String,Object> deliver(UUID request,UUID item) {return loans.deliver(context(archivistB,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,deliveryBody(item));}
    private UUID loan(Map<String,Object> result) {return (UUID)((Map<?,?>)((List<?>)result.get("loans")).getFirst()).get("id");}
    private int version(UUID loan) {return ((Number)db.required("SELECT row_version FROM archive.physical_loan WHERE id=?",loan).get("row_version")).intValue();}
    private UUID returnRequest(UUID loan,int count) {var ctx=new Commands.Context(applicant,UUID.randomUUID(),UUID.randomUUID(),"TEST RETURN "+loan,null,version(loan));return (UUID)loans.requestReturn(ctx,loan,Sql.map("actualQuantity",count,"condition",count==1?"原件及附件交回待核对":"少件待核对","note","合成归还")).get("id");}
    private Map<String,Object> reviewBody(UUID returned,String result,int quantity) {return Sql.map("returnRequestId",returned.toString(),"result",result,"actualReceivedAt",Instant.now().toString(),"actualQuantity",quantity,"condition","管理员实收核对记录","allAttachmentsConfirmed",quantity==1,"intactConfirmed",quantity==1);}
    @Test void boxClassificationChangesAllMembersAndRetiresOldGrants() throws Exception {UUID record=published("GENERAL",b);policy(record,false,List.of(applicant.userId()));UUID box=(UUID)authority.record(record).get("box_id");var row=db.required("SELECT * FROM archive.box WHERE id=?",box);var c=maintenance.create(context(archivistB,"ARCHIVE_BOX",box,"CREATE_CATALOG_CHANGE",((Number)row.get("row_version")).intValue()),"BOX",box,Sql.map("changes",Sql.map("classification","IMPORTANT"),"reason","合成整盒等级更正"));approve(submitChange(c));assertEquals("IMPORTANT",authority.record(record).get("classification"));assertEquals("IMPORTANT",db.required("SELECT classification FROM archive.box WHERE id=?",box).get("classification"));assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant WHERE record_id=? AND state='ACTIVE'",record));}
    @Test void withdrawnPhysicalRecordStillAcceptsActualReturn() throws Exception {UUID box=paperBox(b,"PAPER"),record=paperRecord(box,1),item=physical(record),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item));approve(submitChange(change(record,Map.of(),"WITHDRAWAL")));assertEquals("IN_USE",db.required("SELECT state FROM archive.physical_loan WHERE id=?",loan).get("state"));UUID returned=returnRequest(loan,1);assertEquals("IN_USE",db.required("SELECT state FROM archive.physical_loan WHERE id=?",loan).get("state"));loans.review(context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,reviewBody(returned,"CONFIRM",1));assertEquals("RETURNED",db.required("SELECT state FROM archive.physical_loan WHERE id=?",loan).get("state"));assertEquals("WITHDRAWN",authority.record(record).get("state"));}
    @Test void concurrentSameVersionHasExactlyOneWinner() throws Exception {UUID id=published("GENERAL",b);int version=((Number)authority.record(id).get("row_version")).intValue();var c1=context(archivistB,"ARCHIVE_RECORD",id,"CREATE_CATALOG_CHANGE",version);var c2=context(archivistB,"ARCHIVE_RECORD",id,"CREATE_CATALOG_CHANGE",version);try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var start=new java.util.concurrent.CountDownLatch(1);var one=pool.submit(()->{start.await();try{maintenance.create(c1,"RECORD",id,Sql.map("changes",Sql.map("notes","并发甲"),"reason","并发验收"));return true;}catch(ApiFault e){assertEquals(409,e.status);return false;}});var two=pool.submit(()->{start.await();try{maintenance.create(c2,"RECORD",id,Sql.map("changes",Sql.map("notes","并发乙"),"reason","并发验收"));return true;}catch(ApiFault e){assertEquals(409,e.status);return false;}});start.countDown();assertNotEquals(one.get(),two.get());}assertEquals(1,db.count("SELECT count(*) FROM archive.catalog_change WHERE object_id=?",id));}
    @Test void leapDayRetentionAndPermanentAreNotDayMultiplication() throws Exception {UUID id=published("GENERAL",b);approve(submitChange(change(id,Sql.map("archiveDate","2024-02-29","retentionCode","05"),"CORRECTION")));assertEquals("2029-02-28",authority.record(id).get("expiry_date"));approve(submitChange(change(id,Sql.map("retentionCode","YJ"),"CORRECTION")));assertNull(authority.record(id).get("expiry_date"));}
    @Test void revokedSubmitterStopsPendingApproval() throws Exception {UUID id=published("GENERAL",b);UUID flow=submitChange(change(id,Sql.map("title","失权人提交的候选"),"CORRECTION"));UUID role=(UUID)db.required("SELECT id FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",archivistB.userId()).get("id");db.update("UPDATE iam.user_role SET state='ENDED' WHERE id=?",role);try{var step=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);assertThrows(ApiFault.class,()->workflow.decide(context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",(UUID)step.get("id"),"DECIDE_STEP",((Number)step.get("row_version")).intValue()),(UUID)step.get("id"),Sql.map("result","APPROVE","comment","申请人已失权")));}finally{db.update("UPDATE iam.user_role SET state='ACTIVE' WHERE id=?",role);}}
    /** 为真实浏览器提供无正式数据的待更正事项，凭据只写入受限本机私有文件。 */
    @AfterAll void browserFixture() throws Exception {evidence(false);refreshActors();UUID id=published("GENERAL",b);var c=change(id,Sql.map("title","浏览器验收待复核标题"),"CORRECTION");var values=Sql.map("clerk","AR-"+archivistB.userId(),"supervisor","AR-"+supervisor.userId(),"manager","AR-"+people.get("managerB"),"staff","AR-"+applicant.userId(),"password",password,"recordId",id,"title",authority.record(id).get("title"),"changeId",c.get("id"));Path file=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("browser-fixture.private.json");Files.writeString(file,Crypto.json(values));PrivateFiles.restrict(file);}


    /** 版本列表仅返回固定摘要，分页与越权校验仍使用当前维护范围。 */
    @Test void revisionHistoryIsPaginatedAndRequiresCurrentManagement() throws Exception {
        UUID id=published("GENERAL",b);long before=db.count("SELECT count(*) FROM archive.record_revision WHERE record_id=?",id);approve(submitChange(change(id,Sql.map("title","历史版本验收"),"CORRECTION")));
        var history=maintenance.revisions(archivistB,"RECORD",id,Map.of("pageSize","1"));
        assertEquals(before+1,history.get("total"));assertEquals(1,((List<?>)history.get("items")).size());
        assertThrows(ApiFault.class,()->maintenance.revisions(applicant,"RECORD",id,Map.of()));
        assertThrows(ApiFault.class,()->maintenance.revisions(archivistA,"RECORD",id,Map.of()));
        assertThrows(ApiFault.class,()->maintenance.revisions(archivistB,"RECORD",id,Map.of("pageSize","101")));
    }

    /** 展示和检索分别服从固定模板，历史列表不批量展开完整快照。 */
    @Test void extensionSearchAndDisplayRespectIndependentFlags() throws Exception {
        UUID record=published("GENERAL",b);String token="SHOW"+UUID.randomUUID(),hidden="HIDE"+UUID.randomUUID();
        var visible=Sql.map("code","contractCode","name","合同号","type","TEXT","visible",true,"searchable",true);
        var secret=Sql.map("code","privateMemo","name","内部备注","type","TEXT","visible",false,"searchable",false);
        var t=templateService.save(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),null,Sql.map("templateKey","DISPLAY_"+UUID.randomUUID(),"name","展示模板","categoryCode","ADMIN","objectType","RECORD","fields",List.of(visible,secret)));
        approve(submitChange(change(record,Sql.map("templateVersionId",t.get("id").toString(),"extensionValues",Sql.map("contractCode",token,"privateMemo",hidden)),"CORRECTION")));
        var result=maintenance.list(archivistB,"RECORD",Map.of("keyword",token));assertEquals(1L,result.get("total"));
        var row=(Map<?,?>)((List<?>)result.get("items")).get(0);assertEquals(1,((List<?>)row.get("displayFields")).size());assertFalse(Crypto.json(row).contains(hidden));
        assertEquals(0L,maintenance.list(archivistB,"RECORD",Map.of("keyword",hidden)).get("total"));
        var summary=(Map<?,?>)((List<?>)maintenance.history(archivistB,"RECORD",record,Map.of()).get("items")).get(0);assertFalse(summary.containsKey("before"));
    }
    /** 复核与撤回竞争时只有一个终态胜出，不形成反向加锁或半生效。 */
    @Test void concurrentFinalApprovalAndCancellationRemainConsistent() throws Exception {
        UUID record=published("GENERAL",b);var c=change(record,Map.of(),"WITHDRAWAL");UUID id=(UUID)c.get("id"),flow=submitChange(c);
        var pending=maintenance.change(archivistB,id);var step=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);
        var decision=context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",(UUID)step.get("id"),"DECIDE_STEP",((Number)step.get("row_version")).intValue());
        var cancel=context(archivistB,"ARCHIVE_REVISION",id,"CANCEL_CATALOG_CHANGE",((Number)pending.get("rowVersion")).intValue());
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);
            var one=pool.submit(()->{start.await();try{workflow.decide(decision,(UUID)step.get("id"),Sql.map("result","APPROVE","comment","同意撤下"));return true;}catch(ApiFault e){assertEquals(409,e.status);return false;}});
            var two=pool.submit(()->{start.await();try{maintenance.cancel(cancel,id,Sql.map("reason","撤回申请"));return true;}catch(ApiFault e){assertEquals(409,e.status);return false;}});
            start.countDown();assertNotEquals(one.get(20,java.util.concurrent.TimeUnit.SECONDS),two.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        String state=(String)maintenance.change(archivistB,id).get("state");assertEquals(state.equals("APPLIED")?"WITHDRAWN":"PUBLISHED",authority.record(record).get("state"));assertTrue(Set.of("APPLIED","WITHDRAWN").contains(state));
    }

    /** 待审期间目的盒撤下，旧申请不得把文件移入已停用范围。 */
    @Test void destinationWithdrawalInvalidatesPendingMove() throws Exception {
        UUID source=published("GENERAL",b),other=published("GENERAL",b),target=(UUID)authority.record(other).get("box_id");Object oldBox=authority.record(source).get("box_id");
        UUID flow=submitChange(change(source,Sql.map("boxId",target.toString(),"itemNo",2),"CORRECTION"));
        db.update("UPDATE archive.box SET state='WITHDRAWN',row_version=row_version+1 WHERE id=?",target);
        assertThrows(ApiFault.class,()->approve(flow));assertEquals(oldBox,authority.record(source).get("box_id"));assertEquals("PENDING",db.required("SELECT state FROM workflow.instance WHERE id=?",flow).get("state"));
    }
}
