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
@EnabledIfEnvironmentVariable(named="IMS_EXPORT_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class},webEnvironment=SpringBootTest.WebEnvironment.NONE,
 properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArchiveExportIntegrationTest {
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
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:5544[234678]/ims_platform_dev_[0-9]+"));password=Crypto.token();admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence(false);
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


    /** 所有副本任务使用真实批准与受限PDF工作器，不以mock替代权限或水印。 */
    @Autowired ArchiveExportService exports;@Autowired StorageFiles sourceFiles;
    private UUID approveActions(UUID record,List<String> actions,String pages,int copies) {
        policy(record,false,List.of());var item=Sql.map("objectType","RECORD","objectId",record.toString(),"actions",actions);
        if(actions.contains("PRINT")){item.put("pageRange",pages);item.put("copies",copies);}
        UUID batch=(UUID)borrow.submit(plain(applicant,"SUBMIT_BORROW"),Sql.map("form","ELECTRONIC","actions",actions,"purpose","独立动作验收","items",List.of(item),"period",Sql.map("mode","WORKDAYS","amount",3))).get("id");approveBatch(batch);return batch;
    }
    private Map<String,Object> exportBody(UUID record) {var row=authority.record(record);return Sql.map("items",List.of(Sql.map("recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",row.get("current_pdf_version_id").toString())));}
    private String proof(List<UUID> records,String action) {var revisions=records.stream().map(id->authority.record(id).get("current_revision_id").toString()).toList();return (String)identity.reauthenticate(applicant,Sql.map("password",password,"purpose","MANAGE","objectIds",revisions,"scopeItems",revisions.stream().map(id->Sql.map("objectType","ARCHIVE_REVISION","objectId",id,"actions",List.of(action))).toList()),UUID.randomUUID()).get("verificationToken");}
    private UUID generated(UUID record,String action) {var body=exportBody(record);if(!"GENERAL".equals(authority.record(record).get("classification")))body.put("verificationToken",proof(List.of(record),action));var receipt=exports.create(applicant,action,body,UUID.randomUUID(),UUID.randomUUID());exports.process(applicant,(UUID)receipt.get("jobId"),UUID.randomUUID());return (UUID)receipt.get("usageId");}
    /** 一般档案的水印权不能取得无水印内容；总经理批准后仅放开实际获批的动作。 */
    @Test void unwatermarkedActionsRequireGeneralManagerAndRemainVersionBound() throws Exception {
        UUID record=published("GENERAL",b);
        approveActions(record,List.of("VIEW","DOWNLOAD"),null,1);
        var row=authority.record(record);
        var readBody=Sql.map("recordRevisionId",row.get("current_revision_id").toString(),
            "pdfVersionId",row.get("current_pdf_version_id").toString(),"unwatermarked",true);
        var body=exportBody(record);body.put("unwatermarked",true);
        assertThrows(ApiFault.class,()->reading.create(applicant,readBody,UUID.randomUUID(),UUID.randomUUID()));
        assertThrows(ApiFault.class,()->exports.create(applicant,"DOWNLOAD",body,UUID.randomUUID(),UUID.randomUUID()));
        List<String> actions=List.of("VIEW","DOWNLOAD","PRINT");
        UUID batch=(UUID)borrow.submit(plain(applicant,"SUBMIT_BORROW"),Sql.map(
            "form","ELECTRONIC","actions",actions,"purpose","合成无水印审批","unwatermarked",true,
            "items",List.of(Sql.map("objectType","RECORD","objectId",record.toString(),"actions",actions)),
            "period",Sql.map("mode","NATURAL_DAYS","amount",3))).get("id");
        assertEquals(List.of("DEPARTMENT_MANAGER","ARCHIVIST","DEPARTMENT_MANAGER","ARCHIVIST","GENERAL_MANAGER","ARCHIVE_ADMIN"),
            db.rows("SELECT s.duty_code FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=? ORDER BY s.sequence_no",batch)
                .stream().map(step->step.get("duty_code")).toList());
        assertThrows(ApiFault.class,()->reading.create(applicant,readBody,UUID.randomUUID(),UUID.randomUUID()));
        approveBatch(batch);
        var usage=reading.create(applicant,readBody,UUID.randomUUID(),UUID.randomUUID());
        reading.process(applicant,(UUID)usage.get("jobId"),UUID.randomUUID());
        UUID viewId=(UUID)usage.get("usageId");
        byte[] plain=reading.page(applicant,viewId,1,UUID.randomUUID());
        byte[] marked=reading.page(applicant,view(applicant,record),1,UUID.randomUUID());
        assertFalse(Arrays.equals(plain,marked));
        for(String action:List.of("DOWNLOAD","PRINT")){
            var export=exports.create(applicant,action,body,UUID.randomUUID(),UUID.randomUUID());
            assertEquals(true,export.get("unwatermarked"));
            exports.process(applicant,(UUID)export.get("jobId"),UUID.randomUUID());
            UUID id=(UUID)export.get("usageId");
            assertTrue(action.equals("PRINT")?exports.page(applicant,id,1,UUID.randomUUID(),UUID.randomUUID()).length>0:
                exports.content(applicant,id,action,UUID.randomUUID(),UUID.randomUUID()).length>0);
            assertThrows(ApiFault.class,()->exports.manifest(actors.get(people.get("second")),id,action));
        }
        db.update("UPDATE archive.access_grant SET state='REVOKED' WHERE source_request_id IN(SELECT id FROM archive.borrow_request WHERE batch_id=?)",batch);
        assertThrows(ApiFault.class,()->reading.page(applicant,viewId,1,UUID.randomUUID()));
    }
    /** 同一普通电子动作只有核心保密档案增加总经理，重要保密仍使用标准部门顺序。 */
    @Test void coreElectronicBorrowRequiresGeneralManagerWhileImportantUsesDepartmentRoute() throws Exception {
        for(String classification:List.of("IMPORTANT","CORE")){
            UUID record=published(classification,a);
            policy(record,false,List.of());
            UUID batch=submitBorrow(applicant,List.of(record));
            var expected=new ArrayList<>(List.of("DEPARTMENT_MANAGER","ARCHIVIST"));
            if(classification.equals("CORE"))expected.add("GENERAL_MANAGER");
            expected.add("ARCHIVE_ADMIN");
            assertEquals(expected,db.rows("""
                SELECT s.duty_code FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id
                WHERE q.batch_id=? ORDER BY s.sequence_no
                """,batch).stream().map(step->step.get("duty_code")).toList());
            approveBatch(batch);
        }
    }
    @Test @Order(1) void viewAndManagementCannotDownloadOrPrint() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("VIEW"),null,1);
        for(String action:List.of("DOWNLOAD","PRINT")) {assertEquals(403,assertThrows(ApiFault.class,()->exports.create(applicant,action,exportBody(record),UUID.randomUUID(),UUID.randomUUID())).status);assertThrows(ApiFault.class,()->exports.create(supervisor,action,exportBody(record),UUID.randomUUID(),UUID.randomUUID()));}
    }
    @Test @Order(2) void independentActionWatermarkAndOriginalRemainIntact() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("DOWNLOAD"),null,1);assertThrows(ApiFault.class,()->view(applicant,record));assertEquals(403,assertThrows(ApiFault.class,()->exports.create(applicant,"PRINT",exportBody(record),UUID.randomUUID(),UUID.randomUUID())).status);
        UUID usage=generated(record,"DOWNLOAD");byte[] pdf=exports.content(applicant,usage,"DOWNLOAD",UUID.randomUUID(),UUID.randomUUID());assertEquals("%PDF",new String(pdf,0,4,java.nio.charset.StandardCharsets.US_ASCII));assertFalse(Arrays.equals(pdf,Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")))));
        assertThrows(ApiFault.class,()->exports.content(actors.get(people.get("second")),usage,"DOWNLOAD",UUID.randomUUID(),UUID.randomUUID()));assertThrows(ApiFault.class,()->exports.page(applicant,usage,1,UUID.randomUUID(),UUID.randomUUID()));
        var row=authority.published((UUID)authority.record(record).get("current_revision_id"),(UUID)authority.record(record).get("current_pdf_version_id"));assertTrue(sourceFiles.get().verified(row.get("storage_key").toString(),((Number)row.get("byte_size")).longValue(),row.get("sha256").toString()));
        Path evidence=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT"));Files.write(evidence.resolve("approved-download.pdf"),pdf);assertEquals(1,db.count("SELECT count(*) FROM archive.export_delivery WHERE usage_id=?",usage));
    }
    @Test @Order(3) void duplicateSubmissionConsumesPrintQuotaOnce() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("PRINT"),"1",2);var body=exportBody(record);UUID operation=UUID.randomUUID();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var one=pool.submit(()->exports.create(applicant,"PRINT",body,operation,UUID.randomUUID()));var two=pool.submit(()->exports.create(applicant,"PRINT",body,operation,UUID.randomUUID()));assertEquals(one.get().get("usageId"),two.get().get("usageId"));}
        assertEquals(1,db.count("SELECT consumed_copies FROM archive.access_grant WHERE record_id=? AND action='PRINT'",record));assertEquals(1,db.count("SELECT count(*) FROM archive.export_request WHERE user_id=? AND operation_id=?",applicant.userId(),operation));
        var changed=exportBody(record);((Map<String,Object>)((List<?>)changed.get("items")).getFirst()).put("copies",2);assertEquals(409,assertThrows(ApiFault.class,()->exports.create(applicant,"PRINT",changed,operation,UUID.randomUUID())).status);
        exports.create(applicant,"PRINT",body,UUID.randomUUID(),UUID.randomUUID());assertEquals(403,assertThrows(ApiFault.class,()->exports.create(applicant,"PRINT",body,UUID.randomUUID(),UUID.randomUUID())).status);
        // 用例留下的待处理任务终止，不占用后续有界队列名额。
        db.update("UPDATE archive.export_request SET state='FAILED' WHERE user_id=? AND state='PENDING'",applicant.userId());
    }
    @Test @Order(4) void pageRangeAndUnknownFeedbackDoNotResetQuota() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("PRINT"),"2",1);var invalid=exportBody(record);((Map<String,Object>)((List<?>)invalid.get("items")).getFirst()).put("pageRange","1");assertThrows(ApiFault.class,()->exports.create(applicant,"PRINT",invalid,UUID.randomUUID(),UUID.randomUUID()));
        UUID usage=generated(record,"PRINT");byte[] page=exports.page(applicant,usage,1,UUID.randomUUID(),UUID.randomUUID());assertEquals(137,page[0]&255);assertThrows(ApiFault.class,()->exports.page(applicant,usage,2,UUID.randomUUID(),UUID.randomUUID()));
        UUID operation=UUID.randomUUID();var body=Sql.map("feedback","UNKNOWN");var first=exports.feedback(applicant,usage,body,operation);assertEquals(first.get("id"),exports.feedback(applicant,usage,body,operation).get("id"));assertThrows(ApiFault.class,()->exports.feedback(applicant,usage,Sql.map("feedback","VERIFIED_PRINTED"),UUID.randomUUID()));assertThrows(ApiFault.class,()->exports.create(applicant,"PRINT",exportBody(record),UUID.randomUUID(),UUID.randomUUID()));
        Files.write(Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("approved-print-page.png"),page);
    }
    @Test @Order(5) void generatedContentStopsOnRevocationAndExpiry() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("DOWNLOAD"),null,1);UUID usage=generated(record,"DOWNLOAD");var grant=db.required("SELECT * FROM archive.access_grant WHERE record_id=? AND action='DOWNLOAD'",record);
        policies.revoke(context(supervisor,"ARCHIVE_GRANT",(UUID)grant.get("id"),"REVOKE_ARCHIVE_GRANT",1),(UUID)grant.get("id"),Sql.map("reason","验证撤权后停止交付"));assertEquals(401,assertThrows(ApiFault.class,()->exports.content(applicant,usage,"DOWNLOAD",UUID.randomUUID(),UUID.randomUUID())).status);
        refreshActors();UUID another=published("GENERAL",b);approveActions(another,List.of("PRINT"),null,1);UUID print=generated(another,"PRINT");db.update("UPDATE archive.access_grant SET valid_until=CURRENT_TIMESTAMP-interval '1 second',valid_from=CURRENT_TIMESTAMP-interval '1 day' WHERE record_id=?",another);assertThrows(ApiFault.class,()->exports.page(applicant,print,1,UUID.randomUUID(),UUID.randomUUID()));assertEquals(0,db.count("SELECT count(*) FROM archive.export_delivery WHERE usage_id=?",print));
    }
    @Test @Order(6) void versionBindingAndDerivedReplicaFailureDenyDelivery() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("DOWNLOAD"),null,1);var bad=exportBody(record);((Map<String,Object>)((List<?>)bad.get("items")).getFirst()).put("pdfVersionId",UUID.randomUUID().toString());assertThrows(ApiFault.class,()->exports.create(applicant,"DOWNLOAD",bad,UUID.randomUUID(),UUID.randomUUID()));
        UUID usage=generated(record,"DOWNLOAD");var asset=db.required("SELECT storage_key FROM archive.export_asset WHERE usage_id=?",usage);Path second=Path.of(System.getenv("IMS_PLATFORM_EXPORT_SECONDARY")).resolve(asset.get("storage_key").toString());byte[] original=Files.readAllBytes(second);try{Files.write(second,new byte[]{1,2,3});assertEquals(503,assertThrows(ApiFault.class,()->exports.content(applicant,usage,"DOWNLOAD",UUID.randomUUID(),UUID.randomUUID())).status);}finally{Files.write(second,original);}
        assertEquals(0,db.count("SELECT count(*) FROM archive.export_delivery WHERE usage_id=?",usage));
    }
    @Test @Order(7) void batchReauthenticationBindsWholeVersionListAndAllOrNothing() throws Exception {
        UUID one=published("GENERAL",b),two=published("GENERAL",b);approveActions(one,List.of("DOWNLOAD"),null,1);approveActions(two,List.of("DOWNLOAD"),null,1);
        var body=Sql.map("items",List.of(((List<?>)exportBody(one).get("items")).getFirst(),((List<?>)exportBody(two).get("items")).getFirst()));assertEquals("REAUTH_REQUIRED",assertThrows(ApiFault.class,()->exports.create(applicant,"DOWNLOAD",body,UUID.randomUUID(),UUID.randomUUID())).code);
        body.put("verificationToken",proof(List.of(one),"DOWNLOAD"));assertThrows(ApiFault.class,()->exports.create(applicant,"DOWNLOAD",body,UUID.randomUUID(),UUID.randomUUID()));body.put("verificationToken",proof(List.of(one,two),"DOWNLOAD"));var result=exports.create(applicant,"DOWNLOAD",body,UUID.randomUUID(),UUID.randomUUID());UUID usage=(UUID)result.get("usageId");exports.process(applicant,(UUID)result.get("jobId"),UUID.randomUUID());byte[] zip=exports.content(applicant,usage,"DOWNLOAD",UUID.randomUUID(),UUID.randomUUID());assertEquals('P',zip[0]);Files.write(Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("approved-batch.zip"),zip);
        // ZIP内的完整清单必须能用实际PDF内容独立验证，不能仅以PK文件头判断整包成功。
        Map<String,byte[]> entries=new HashMap<>();try(var stream=new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip),java.nio.charset.StandardCharsets.UTF_8)){java.util.zip.ZipEntry entry;while((entry=stream.getNextEntry())!=null)entries.put(entry.getName(),stream.readAllBytes());}
        var manifest=Crypto.JSON.readValue(new String(entries.get("manifest.json"),java.nio.charset.StandardCharsets.UTF_8),Map.class);assertEquals(3,entries.size());for(var item:Inputs.objects(manifest.get("items"),50)){byte[] pdf=entries.get(item.get("fileNo")+".pdf");assertEquals(item.get("sha256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)));assertEquals(((Number)item.get("byteSize")).longValue(),pdf.length);}
        db.update("UPDATE archive.access_grant SET state='REVOKED',policy_version=policy_version+1 WHERE record_id=? AND action='DOWNLOAD'",two);assertThrows(ApiFault.class,()->exports.content(applicant,usage,"DOWNLOAD",UUID.randomUUID(),UUID.randomUUID()));
    }
    @Test @Order(8) void workerFailureRetainsReservedPrintQuota() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("PRINT"),null,1);var result=exports.create(applicant,"PRINT",exportBody(record),UUID.randomUUID(),UUID.randomUUID());db.update("UPDATE archive.access_grant SET state='REVOKED' WHERE record_id=?",record);assertThrows(ApiFault.class,()->exports.process(applicant,(UUID)result.get("jobId"),UUID.randomUUID()));assertEquals(1,db.count("SELECT consumed_copies FROM archive.access_grant WHERE record_id=? AND action='PRINT'",record));assertEquals(0,db.count("SELECT count(*) FROM archive.export_asset WHERE usage_id=?",result.get("usageId")));
    }
    @Test @Order(9) void sensitiveCredentialIsSingleUseAndActionBound() throws Exception {
        UUID record=published("IMPORTANT",b);approveActions(record,List.of("DOWNLOAD","PRINT"),"1",2);var body=exportBody(record);
        assertEquals("REAUTH_REQUIRED",assertThrows(ApiFault.class,()->exports.create(applicant,"DOWNLOAD",body,UUID.randomUUID(),UUID.randomUUID())).code);
        body.put("verificationToken",proof(List.of(record),"PRINT"));assertThrows(ApiFault.class,()->exports.create(applicant,"DOWNLOAD",body,UUID.randomUUID(),UUID.randomUUID()));
        body.put("verificationToken",proof(List.of(record),"DOWNLOAD"));UUID operation=UUID.randomUUID();var first=exports.create(applicant,"DOWNLOAD",body,operation,UUID.randomUUID());assertEquals(first.get("usageId"),exports.create(applicant,"DOWNLOAD",body,operation,UUID.randomUUID()).get("usageId"));assertThrows(ApiFault.class,()->exports.create(applicant,"DOWNLOAD",body,UUID.randomUUID(),UUID.randomUUID()));
        db.update("UPDATE archive.export_request SET state='FAILED' WHERE id=?",first.get("usageId"));
    }
    @Test @Order(10) void independentReceiptFailureDeliversNoBytes() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("DOWNLOAD"),null,1);UUID usage=generated(record,"DOWNLOAD");Path marker=Path.of(System.getenv("IMS_PLATFORM_READING_AUDIT")).resolve("instance");String original=Files.readString(marker);
        try {Files.writeString(marker,UUID.randomUUID().toString());assertEquals(503,assertThrows(ApiFault.class,()->exports.content(applicant,usage,"DOWNLOAD",UUID.randomUUID(),UUID.randomUUID())).status);assertEquals(0,db.count("SELECT count(*) FROM archive.export_delivery WHERE usage_id=?",usage));}finally{Files.writeString(marker,original);}
    }
    @Test @Order(11) void originalReplicaFailureAndProtectionFailureStopGeneratedPrint() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("PRINT"),null,1);UUID usage=generated(record,"PRINT");var object=db.required("SELECT o.* FROM storage.object o JOIN archive.pdf_version p ON p.object_id=o.id JOIN archive.record r ON r.current_pdf_version_id=p.id WHERE r.id=?",record);Path second=Path.of(System.getenv("IMS_PLATFORM_STORAGE_SECONDARY")).resolve(object.get("storage_key").toString());byte[] original=Files.readAllBytes(second);
        try{Files.write(second,new byte[]{1});assertThrows(ApiFault.class,()->exports.page(applicant,usage,1,UUID.randomUUID(),UUID.randomUUID()));}finally{Files.write(second,original);}
        evidence(true);assertThrows(ApiFault.class,()->exports.page(applicant,usage,1,UUID.randomUUID(),UUID.randomUUID()));evidence(false);assertEquals(0,db.count("SELECT count(*) FROM archive.export_delivery WHERE usage_id=?",usage));
    }
    @Test @Order(12) void changedPublishedVersionInvalidatesPreviouslyGeneratedCopy() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("DOWNLOAD"),null,1);UUID usage=generated(record,"DOWNLOAD");var old=authority.record(record);var pdf=db.required("SELECT * FROM archive.pdf_version WHERE id=?",old.get("current_pdf_version_id"));UUID nextPdf=UUID.randomUUID(),revision=UUID.randomUUID();
        db.update("INSERT INTO archive.pdf_version(id,record_id,version_no,object_id,source,review_state,created_by,page_count) VALUES(?,?,2,?,'TEST_VERSION_DRIFT','APPROVED',?,?)",nextPdf,record,pdf.get("object_id"),archivistB.userId(),pdf.get("page_count"));
        db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,pdf_version_id,metadata_snapshot,template_version_id,created_by) VALUES(?,?,99,?,'{}',?,?)",revision,record,nextPdf,old.get("template_version_id"),archivistB.userId());db.update("UPDATE archive.record SET current_revision_id=?,current_pdf_version_id=? WHERE id=?",revision,nextPdf,record);
        assertThrows(ApiFault.class,()->exports.content(applicant,usage,"DOWNLOAD",UUID.randomUUID(),UUID.randomUUID()));assertEquals(0,db.count("SELECT count(*) FROM archive.export_delivery WHERE usage_id=?",usage));
    }
    @Test @Order(13) void expiredDerivedCleanupPreservesOriginalAndLiveOutputs() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("DOWNLOAD"),null,1);UUID expired=generated(record,"DOWNLOAD"),live=generated(record,"DOWNLOAD");var asset=db.required("SELECT * FROM archive.export_asset WHERE usage_id=?",expired);var liveAsset=db.required("SELECT * FROM archive.export_asset WHERE usage_id=?",live);
        db.update("UPDATE archive.export_request SET expires_at=CURRENT_TIMESTAMP-interval '1 day' WHERE id=?",expired);db.update("UPDATE archive.export_asset SET purge_after=CURRENT_TIMESTAMP-interval '1 second' WHERE usage_id=?",expired);
        new ExportCleanup(db,exportFiles,protection,transactionManager).tick();assertEquals("PURGED",db.required("SELECT storage_state FROM archive.export_asset WHERE id=?",asset.get("id")).get("storage_state"));for(String root:List.of("IMS_PLATFORM_EXPORT_PRIMARY","IMS_PLATFORM_EXPORT_SECONDARY"))assertFalse(Files.exists(Path.of(System.getenv(root)).resolve(asset.get("storage_key").toString())));
        assertTrue(exportFiles.get().verified(liveAsset.get("storage_key").toString(),((Number)liveAsset.get("byte_size")).longValue(),liveAsset.get("sha256").toString()));var row=authority.published((UUID)authority.record(record).get("current_revision_id"),(UUID)authority.record(record).get("current_pdf_version_id"));assertTrue(sourceFiles.get().verified(row.get("storage_key").toString(),((Number)row.get("byte_size")).longValue(),row.get("sha256").toString()));
    }
    /** 手动调度仅注入正式服务依赖，验证时不打开后台清理与手动测试竞争。 */
    @Autowired ExportFiles exportFiles;@Autowired StorageBoundaries protection;@Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Test @Order(99) void desktopFixtureHasAllIndependentActions() throws Exception {
        UUID record=published("GENERAL",b);approveActions(record,List.of("VIEW","DOWNLOAD","PRINT"),"1-2",4);var row=authority.record(record);var list=(List<Map<String,Object>>)catalog.records(applicant,Map.of("keyword",row.get("file_no").toString())).get("items");var projection=(Map<?,?>)list.getFirst().get("record");assertEquals(true,projection.get("canView"));assertEquals(true,projection.get("canDownload"));assertEquals(true,projection.get("canPrint"));
        Path fixture=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("export-fixture.private.json");Files.writeString(fixture,Crypto.json(Sql.map("recordId",record.toString(),"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",row.get("current_pdf_version_id").toString(),"title",row.get("title"),"fileNo",row.get("file_no"),"applicantNo","AR-"+applicant.userId(),"superNo","AR-"+supervisor.userId(),"password",password,"people",people.entrySet().stream().map(entry->Sql.map("key",entry.getKey(),"id",entry.getValue().toString(),"employeeNo","AR-"+entry.getValue())).toList())));PrivateFiles.restrict(fixture);
    }
}
