package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.archive.*;
import com.company.platform.portal.PortalWork;
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
@EnabledIfEnvironmentVariable(named="IMS_ARCHIVE_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class},webEnvironment=SpringBootTest.WebEnvironment.NONE,
 properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveWorkflowIntegrationTest {
    /** 业务服务全部使用正式实现；替换范围仅为测试专用无害签名库。 */
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired ArchiveCatalogService catalog;@Autowired ArchiveAuthority authority;
    @Autowired ArchivePolicyService policies;@Autowired BorrowService borrow;@Autowired WorkflowEngine workflow;@Autowired ArchiveReadingService reading;
    @Autowired WorkCalendarService calendar;@Autowired UploadService uploads;@Autowired UploadAssembly assembly;@Autowired FileInspectionService inspection;
    @Autowired OrganizationDuties organization;
    /** 真实到期状态和站内通知工作器，测试不会替换授权判定。 */
    @Autowired BorrowLifecycleService lifecycle;
    /** 改派必须同时满足真实任职与当前模块权限，不只检查角色名称。 */
    @Autowired WorkflowDuties dutyResolver;
    @Autowired PortalWork portalWork;

    /** 已处理节点退出待审批队列，但真实决定可由有权的本人查询。 */
    @Test void completedApprovalRemainsInOperatorsPortalHistory() throws Exception {
        LocalDate today=LocalDate.now(WorkCalendarService.ZONE);
        UUID box=(UUID)catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),
            Sql.map("departmentId",b.toString(),"title","已办历史测试盒","classification","GENERAL","retentionCode","10",
                "archiveDate",today.toString(),"physicalFileCount",0,"keywords",List.of("合成"),"templateVersionId",templateBox.toString(),
                "categoryCode","ADMIN","ownerId",people.get("managerB").toString(),"numberMonth",today.withDayOfMonth(1).toString(),
                "carrierType","ELECTRONIC")).get("id");
        UUID flow=review(archivistB,"BOX",box,null);
        var pending=db.required("SELECT * FROM workflow.step WHERE instance_id=? AND state='PENDING'",flow);
        UUID step=(UUID)pending.get("id");
        Actor operator=actors.get(pending.get("assignee_id"));
        workflow.decide(context(operator,"WORKFLOW_STEP",step,"DECIDE_STEP",1),step,Sql.map("result","REJECT","comment","合成办理意见"));

        var filter=Map.of("category","COMPLETED","keyword","归档审批","pageSize","100");
        var completed=(List<?>)portalWork.tasks(operator,filter).get("items");
        var item=completed.stream().map(Map.class::cast).filter(row->step.equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals("REJECTED",item.get("state"));
        assertNotNull(item.get("completedAt"));
        assertFalse(((List<?>)portalWork.tasks(applicant,filter).get("items")).stream().map(Map.class::cast).anyMatch(row->step.equals(row.get("id"))));
        assertFalse(((List<?>)portalWork.tasks(operator,Map.of("category","APPROVAL","pageSize","100")).get("items")).stream().map(Map.class::cast).anyMatch(row->step.equals(row.get("id"))));
        assertEquals("合成办理意见",((Map<?,?>)workflow.step(operator,step).get("ownDecision")).get("comment"));
    }
    /** 每次测试独立建档；统一合成部门和员工不含正式业务数据。 */
    private UUID a,b,templateBox,templateRecord;private String password;private final Map<String,UUID> people=new HashMap<>();private final Map<UUID,Actor> actors=new HashMap<>();private Actor admin,supervisor,applicant,archivistA,archivistB;
    private final UUID scanner=UUID.fromString("42210210-46cc-4ce8-8624-5c0da61a7760");
    /** 验证地址后建立合成员工；工作日历通过正式API服务发布，不假定生产周末规则。 */
    @BeforeAll void setup() throws Exception {
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:(5544[01234678]|55473)/ims_platform_dev_[0-9]+"));password=Crypto.token();admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence(false);
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

    /** 平台 IT 角色本身不发放档案正文权限。 */
    @Test void platformItRoleDoesNotGrantArchiveBody() throws Exception {
        UUID record=published("CORE",b),person=people.get("second"),assignment=UUID.randomUUID();
        db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code) SELECT ?,?,id,CURRENT_TIMESTAMP,?,'PLATFORM','IT_ADMIN' FROM iam.role WHERE module='PLATFORM' AND code='IT_ADMIN'",assignment,person,admin.userId());
        try {
            Actor operator=login("AR-"+person,password);
            assertEquals(1,db.count("SELECT CASE WHEN iam.system_administrator(?) THEN 1 ELSE 0 END",person));
            assertEquals(403,assertThrows(ApiFault.class,()->view(operator,record)).status);
        } finally {
            db.update("UPDATE iam.user_role SET valid_until=CURRENT_TIMESTAMP WHERE id=?",assignment);
        }
    }
    /** 截图中的斜杠部门码原样进入盒号，不能创建部门后才发现无法建档。 */
    @Test void builtInSafetyDepartmentCanCreateNumberedArchiveBox() {
        UUID department=(UUID)db.required("SELECT id FROM iam.department WHERE code='DHS/MEP'").get("id");
        person("safetyOwner",department,"STAFF");LocalDate today=LocalDate.now(WorkCalendarService.ZONE);
        var box=catalog.box(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),Sql.map("departmentId",department.toString(),"title","隔离编号验证","classification","GENERAL","retentionCode","10","archiveDate",today.toString(),"physicalFileCount",0,"keywords",List.of("合成"),"templateVersionId",templateBox.toString(),"categoryCode","ADMIN","ownerId",people.get("safetyOwner").toString(),"numberMonth",today.withDayOfMonth(1).toString(),"carrierType","ELECTRONIC"));
        assertTrue(box.get("boxNo").toString().startsWith("BLK27-DHS/MEP10-"));
    }

    /** 半微秒输入在送审时标准化，批准快照与数据库必须仍严格相等。 */
    @Test void approvalTimestampPrecisionUsesSameFrozenValue() throws Exception {
        UUID record=published("GENERAL",b);var row=authority.record(record);
        Instant from=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(123456500);
        UUID flow=(UUID)policies.propose(context(archivistB,"ARCHIVE_POLICY",record,"REQUEST_ACCESS_POLICY",null),Sql.map("objectType","RECORD","objectId",record.toString(),"departmentScope",List.of(a.toString()),"titleVisibility","SAFE_TITLE","safeSearchFields",List.of("safeTitle"),"allowOwnerDepartmentView",false,"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",row.get("current_pdf_version_id").toString(),"userIds",List.of(),"validFrom",from.toString(),"validUntil",from.plusSeconds(864000).toString(),"reason","半微秒精度回归")).get("id");
        approve(flow);assertEquals(1,db.count("SELECT count(*) FROM archive.access_policy WHERE workflow_instance_id=? AND archive.policy_approved(id)",flow));
        assertEquals("TITLE_ONLY",authority.detail(applicant,record).get("representation"));
    }
    @Test void publishedManagementDoesNotGrantBodyAndRealBorrowWatermarkWorks() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());var row=authority.record(record);
        assertEquals("TITLE_ONLY",authority.detail(applicant,record).get("representation"));assertEquals(403,assertThrows(ApiFault.class,()->view(supervisor,record)).status);assertEquals(403,assertThrows(ApiFault.class,()->view(admin,record)).status);assertEquals(403,assertThrows(ApiFault.class,()->view(applicant,record)).status);
        UUID batch=submitBorrow(applicant,List.of(record));assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant WHERE user_id=? AND record_id=?",applicant.userId(),record));approveBatch(batch);
        UUID usage=view(applicant,record);byte[] png=reading.page(applicant,usage,1,UUID.randomUUID());assertArrayEquals(new byte[]{(byte)137,80,78,71},Arrays.copyOf(png,4));assertEquals(404,assertThrows(ApiFault.class,()->reading.page(actors.get(people.get("second")),usage,1,UUID.randomUUID())).status);
        assertEquals(0L,catalog.records(applicant,Map.of("keyword","不存在的秘密摘要片段")).get("total"));
        Path fixture=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("archive-fixture.private.json");Files.writeString(fixture,Crypto.json(Sql.map("recordId",record.toString(),"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",row.get("current_pdf_version_id").toString(),"title",row.get("title"),"fileNo",row.get("file_no"),"applicantNo","AR-"+applicant.userId(),"superNo","AR-"+supervisor.userId(),"password",password,"people",people.entrySet().stream().map(entry->Sql.map("key",entry.getKey(),"id",entry.getValue().toString(),"employeeNo","AR-"+entry.getValue())).toList())));PrivateFiles.restrict(fixture);
    }
    @Test void safeTitleSearchCannotMatchSecretFieldsAndDepartmentScopeCannotRead() throws Exception {UUID record=published("IMPORTANT",b);policy(record,false,List.of());assertEquals("TITLE_ONLY",authority.detail(applicant,record).get("representation"));assertEquals(0L,catalog.records(actors.get(people.get("outsider")),Map.of("keyword","秘密摘要")).get("total"));assertTrue(((Number)catalog.records(applicant,Map.of("keyword","可申请的合成资料")).get("total")).longValue()>0);assertEquals(403,assertThrows(ApiFault.class,()->view(applicant,record)).status);}
    @Test void rejectedAndWithdrawnRequestsCannotBeApprovedLater() throws Exception {UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));var request=db.required("SELECT * FROM archive.borrow_request WHERE batch_id=?",batch);UUID flow=(UUID)request.get("workflow_instance_id"),step=(UUID)db.required("SELECT current_step_id FROM workflow.instance WHERE id=?",flow).get("current_step_id");var current=db.required("SELECT * FROM workflow.step WHERE id=?",step);Actor approver=actors.get(current.get("assignee_id"));workflow.decide(context(approver,"WORKFLOW_STEP",step,"DECIDE_STEP",1),step,Sql.map("result","REJECT","comment","合成驳回"));assertEquals(409,assertThrows(ApiFault.class,()->workflow.decide(context(approver,"WORKFLOW_STEP",step,"DECIDE_STEP",2),step,Sql.map("result","APPROVE","comment","不应覆盖驳回"))).status);assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant WHERE source_request_id=?",request.get("id")));
        UUID second=submitBorrow(applicant,List.of(record));var pending=db.required("SELECT q.workflow_instance_id,w.row_version FROM archive.borrow_request q JOIN workflow.instance w ON w.id=q.workflow_instance_id WHERE q.batch_id=?",second);UUID pendingFlow=(UUID)pending.get("workflow_instance_id");var ctx=new Commands.Context(applicant,UUID.randomUUID(),UUID.randomUUID(),"TEST WITHDRAW",null,((Number)pending.get("row_version")).intValue());workflow.withdraw(ctx,pendingFlow,Sql.map("reason","合成撤回"));assertEquals("WITHDRAWN",db.required("SELECT state FROM workflow.instance WHERE id=?",pendingFlow).get("state"));}
    @Test void revocationInvalidatesOldSessionButPreservesAnotherApprovedSource() throws Exception {UUID record=published("GENERAL",b);policy(record,false,List.of(applicant.userId()));UUID batch=submitBorrow(applicant,List.of(record));approveBatch(batch);UUID usage=view(applicant,record);var grant=db.required("SELECT * FROM archive.access_grant WHERE user_id=? AND record_id=? AND source_type='BORROW'",applicant.userId(),record);Actor old=applicant;policies.revoke(context(supervisor,"ARCHIVE_GRANT",(UUID)grant.get("id"),"REVOKE_ARCHIVE_GRANT",1),(UUID)grant.get("id"),Sql.map("reason","合成撤权"));assertEquals(401,assertThrows(ApiFault.class,()->reading.page(old,usage,1,UUID.randomUUID())).status);Actor fresh=login("AR-"+old.userId(),password);assertNotNull(view(fresh,record));assertEquals(1,db.count("SELECT count(*) FROM archive.access_grant WHERE user_id=? AND record_id=? AND source_type='BASELINE' AND state='ACTIVE'",old.userId(),record));}
    @Test void activeFlagCannotOverrideExpiredOrInvalidApproval() throws Exception {UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));approveBatch(batch);UUID usage=view(applicant,record);var grant=db.required("SELECT * FROM archive.access_grant WHERE user_id=? AND record_id=?",applicant.userId(),record);db.update("UPDATE archive.access_grant SET valid_from=CURRENT_TIMESTAMP-interval '2 days',valid_until=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",grant.get("id"));assertEquals(403,assertThrows(ApiFault.class,()->reading.page(applicant,usage,1,UUID.randomUUID())).status);assertEquals("ACTIVE",db.required("SELECT state FROM archive.access_grant WHERE id=?",grant.get("id")).get("state"));}
    /** 审批展示原借阅人，既不能误取审批人或档案部门，也不能向无关员工泄露身份。 */
    @Test @SuppressWarnings("unchecked") void electronicApprovalShowsApplicantIdentityWithinExistingScope() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));
        var step=db.required("SELECT s.* FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=? AND s.state='PENDING'",batch);UUID id=(UUID)step.get("id");Actor approver=actors.get(step.get("assignee_id"));
        var detail=workflow.step(approver,id);var person=(Map<String,Object>)detail.get("applicant");
        assertEquals(Set.of("id","name","employeeNo","departmentId","departmentName","submittedAt"),person.keySet());
        assertEquals(applicant.userId(),person.get("id"));assertNotEquals(approver.userId(),person.get("id"));assertEquals("验收applicant",person.get("name"));assertEquals("AR-"+applicant.userId(),person.get("employeeNo"));
        assertEquals(a,person.get("departmentId"));assertNotEquals(b,person.get("departmentId"));assertEquals("合成部门A",person.get("departmentName"));assertEquals(db.required("SELECT submitted_at FROM archive.borrow_batch WHERE id=?",batch).get("submitted_at"),person.get("submittedAt"));
        assertEquals(person,workflow.step(applicant,id).get("applicant"));assertEquals(403,assertThrows(ApiFault.class,()->workflow.step(actors.get(people.get("outsider")),id)).status);
        assertEquals(0,db.count("SELECT count(*) FROM workflow.decision WHERE step_id=?",id));assertEquals("PENDING",db.required("SELECT state FROM workflow.step WHERE id=?",id).get("state"));
        // 浏览器夹具只导出隔离库合成账号，未配置时不写文件。
        if(System.getenv("IMS_APPROVAL_UI_FIXTURE")!=null){Path target=Path.of(System.getenv("IMS_APPROVAL_UI_FIXTURE"));Files.writeString(target,Crypto.json(Sql.map("stepId",id.toString(),"applicant",person,"employeeNo","AR-"+approver.userId(),"password",password)));PrivateFiles.restrict(target);}
    }
    @Test void duplicateDecisionIsIdempotentAndWrongNodeCannotApprove() throws Exception {UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));var step=db.required("SELECT s.* FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=? AND s.state='PENDING'",batch);UUID id=(UUID)step.get("id");Actor assignee=actors.get(step.get("assignee_id"));var ctx=context(assignee,"WORKFLOW_STEP",id,"DECIDE_STEP",1);var body=Sql.map("result","APPROVE","comment","合成同意");var first=workflow.decide(ctx,id,body);assertEquals(first.get("id"),workflow.decide(ctx,id,body).get("id"));assertEquals(1,db.count("SELECT count(*) FROM workflow.decision WHERE step_id=?",id));assertEquals(403,assertThrows(ApiFault.class,()->workflow.decide(context(applicant,"WORKFLOW_STEP",id,"DECIDE_STEP",1),id,body)).status);assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE workflow.decision SET result='REJECT' WHERE step_id=?",id));}
    @Test void mixedDepartmentBatchKeepsCoreHighestClassification() throws Exception {UUID general=published("GENERAL",a),core=published("CORE",b);policy(general,false,List.of());policy(core,false,List.of());UUID batch=submitBorrow(applicant,List.of(general,core));assertEquals("CORE",db.required("SELECT parent_max_classification FROM archive.borrow_batch WHERE id=?",batch).get("parent_max_classification"));assertEquals(2,db.count("SELECT count(*) FROM archive.borrow_request WHERE batch_id=?",batch));assertEquals(2,db.count("SELECT count(*) FROM archive.borrow_request q JOIN workflow.route_snapshot s ON s.id=q.route_snapshot_id WHERE q.batch_id=? AND s.highest_classification='CORE'",batch));}
    @Test void brokenSecondaryAndExpiredProtectionDeliverNoPage() throws Exception {UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));approveBatch(batch);UUID usage=view(applicant,record);evidence(true);assertEquals(503,assertThrows(ApiFault.class,()->reading.page(applicant,usage,1,UUID.randomUUID())).status);evidence(false);var object=db.required("SELECT o.storage_key FROM archive.usage_request u JOIN storage.object o ON o.id=u.object_id WHERE u.id=?",usage);Path copy=Path.of(System.getenv("IMS_PLATFORM_STORAGE_SECONDARY")).resolve((String)object.get("storage_key"));assertTrue(copy.normalize().startsWith(Path.of(System.getenv("IMS_DRIVE_TEST_ROOT"))));Files.write(copy,new byte[]{1},StandardOpenOption.APPEND);assertEquals(503,assertThrows(ApiFault.class,()->reading.page(applicant,usage,1,UUID.randomUUID())).status);}
    /** 来源流程失效不能靠仍为ACTIVE的授权行继续读取。 */
    @Test void invalidatedApprovalCannotKeepOldGrantAlive() throws Exception {UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));approveBatch(batch);UUID usage=view(applicant,record);var request=db.required("SELECT * FROM archive.borrow_request WHERE batch_id=?",batch);db.update("UPDATE workflow.instance SET state='WITHDRAWN' WHERE id=?",request.get("workflow_instance_id"));assertEquals(403,assertThrows(ApiFault.class,()->reading.page(applicant,usage,1,UUID.randomUUID())).status);assertEquals(1,db.count("SELECT count(*) FROM archive.access_grant WHERE source_request_id=? AND state='ACTIVE'",request.get("id")));}
    /** 本部门一般文件也须真实基础批准；撤销原策略立即关闭旧阅读且不自动恢复。 */
    @Test void ownerDepartmentBaselineRequiresApprovalAndSupportsImmediateRevocation() throws Exception {UUID record=published("GENERAL",a);assertEquals(404,assertThrows(ApiFault.class,()->authority.detail(applicant,record)).status);UUID source=policy(record,true,List.of());UUID usage=view(applicant,record);assertTrue(reading.page(applicant,usage,1,UUID.randomUUID()).length>0);Actor old=applicant;policies.revokePolicy(context(supervisor,"ARCHIVE_POLICY",source,"REVOKE_ARCHIVE_POLICY",1),source,Sql.map("reason","合成基础阅读撤销"));assertEquals(401,assertThrows(ApiFault.class,()->reading.page(old,usage,1,UUID.randomUUID())).status);Actor fresh=login("AR-"+old.userId(),password);assertEquals(403,assertThrows(ApiFault.class,()->view(fresh,record)).status);}
    /** 申请人不能批准必要经理节点；更替职责后仍须合法改派，不能自动跳过。 */
    /** IT 电子借阅及续借保留独立超管批准，并实际生成对应来源授权。 */
    @Test void itSharedElectronicBorrowAndRenewalUseOnlyArchiveAdmin() throws Exception {
        UUID record = published("GENERAL", b);
        assertEquals("TITLE_ONLY", authority.detail(admin, record).get("representation"));
        assertEquals(403, assertThrows(ApiFault.class, () -> view(admin, record)).status);
        UUID original = submitBorrow(admin, List.of(record));
        var progress = (List<?>) workflow.progress(admin, Map.of()).get("items");
        var current = (Map<?,?>) progress.getFirst();
        assertEquals("PENDING", current.get("state"));
        assertEquals(Set.of("id","businessType","state","createdAt","dutyCode"), current.keySet());
        assertEquals(403, assertThrows(ApiFault.class, () -> workflow.progress(applicant, Map.of())).status);
        assertEquals(List.of("ARCHIVE_ADMIN"), db.rows("SELECT s.duty_code FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=?", original).stream().map(row -> row.get("duty_code")).toList());
        approveBatch(original);
        assertNotNull(view(admin, record));
        UUID renewal = renew(original, plain(admin, "RENEW_BORROW"));
        assertEquals(List.of("ARCHIVE_ADMIN"), db.rows("SELECT s.duty_code FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=?", renewal).stream().map(row -> row.get("duty_code")).toList());
        approveBatch(renewal);
        assertEquals(1, db.count("SELECT count(*) FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=? AND archive.grant_valid(g.id,?)", renewal, admin.userId()));
    }

    @Test void missingApproverIdentifiesDepartmentAndConfiguration() {
        UUID unconfigured = department("Z");
        var failure = assertThrows(ApiFault.class, () ->
            dutyResolver.resolve(new FixedBorrowRoute.Duty("DEPARTMENT_MANAGER", unconfigured)));
        assertEquals(409, failure.status);
        assertTrue(failure.getMessage().contains("合成部门Z"));
        assertTrue(failure.getMessage().contains("部门负责人"));
        assertTrue(failure.getMessage().contains("组织审批职责"));
    }

    @Test void selfApprovalPausesUntilLegalDutyChangeAndReassignment() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());Actor manager=actors.get(people.get("managerA"));UUID batch=submitBorrow(manager,List.of(record));UUID flow=(UUID)db.required("SELECT workflow_instance_id FROM archive.borrow_request WHERE batch_id=?",batch).get("workflow_instance_id");UUID conflict=(UUID)db.required("SELECT current_step_id FROM workflow.instance WHERE id=?",flow).get("current_step_id");
        assertEquals(409,assertThrows(ApiFault.class,()->workflow.decide(context(manager,"WORKFLOW_STEP",conflict,"DECIDE_STEP",1),conflict,Sql.map("result","APPROVE","comment","本人不应批准"))).status);
        try {
            int version=((Number)db.required("SELECT row_version FROM iam.department WHERE id=?",a).get("row_version")).intValue();organization.department(context(admin,"DEPARTMENT",a,"MANAGEMENT_DUTIES",version),a,Sql.map("duties",List.of(Sql.map("dutyType","DEPARTMENT_MANAGER","userId",people.get("second").toString(),"validFrom",Instant.now().toString())),"changeReason","合成职责更替","approvalRef","TEST-ORG"));refreshActors();Actor former=actors.get(people.get("managerA"));
            assertEquals(403,assertThrows(ApiFault.class,()->workflow.decide(context(former,"WORKFLOW_STEP",conflict,"DECIDE_STEP",1),conflict,Sql.map("result","APPROVE","comment","旧职责不应批准"))).status);
            workflow.reassign(context(supervisor,"WORKFLOW_STEP",conflict,"REASSIGN_STEP",1),conflict,Sql.map("assigneeId",people.get("second").toString(),"reason","按当前合法职责改派","approvalReference","TEST-REASSIGN"));approve(flow);assertEquals(1,db.count("SELECT count(*) FROM workflow.assignment_event WHERE step_id=?",conflict));
        } finally {int version=((Number)db.required("SELECT row_version FROM iam.department WHERE id=?",a).get("row_version")).intValue();organization.department(context(admin,"DEPARTMENT",a,"MANAGEMENT_DUTIES",version),a,Sql.map("duties",List.of(Sql.map("dutyType","DEPARTMENT_MANAGER","userId",people.get("managerA").toString(),"validFrom",Instant.now().toString())),"changeReason","恢复合成测试职责","approvalRef","TEST-ORG-RESTORE"));refreshActors();}
    }
    /** 模拟审批期间出现另一发布修订，原申请不能对新PDF或旧失效快照发权。 */
    @Test void replacedVersionCannotInheritPendingOrApprovedBorrow() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));var request=db.required("SELECT * FROM archive.borrow_request WHERE batch_id=?",batch);UUID flow=(UUID)request.get("workflow_instance_id");
        while(true){UUID id=(UUID)db.required("SELECT current_step_id FROM workflow.instance WHERE id=?",flow).get("current_step_id");var step=db.required("SELECT * FROM workflow.step WHERE id=?",id);if(step.get("duty_code").equals("ARCHIVE_ADMIN"))break;workflow.decide(context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",id,"DECIDE_STEP",1),id,Sql.map("result","APPROVE","comment","合成前置同意"));}
        var old=authority.record(record);var pdf=db.required("SELECT * FROM archive.pdf_version WHERE id=?",old.get("current_pdf_version_id"));UUID nextPdf=UUID.randomUUID(),nextRevision=UUID.randomUUID();db.update("INSERT INTO archive.pdf_version(id,record_id,version_no,object_id,source,review_state,created_by,page_count) VALUES(?,?,2,?,'TEST_VERSION_DRIFT','APPROVED',?,?)",nextPdf,record,pdf.get("object_id"),archivistB.userId(),pdf.get("page_count"));int revision=(int)db.count("SELECT max(revision_no)+1 FROM archive.record_revision WHERE record_id=?",record);db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,pdf_version_id,metadata_snapshot,template_version_id,created_by) VALUES(?,?,?,?,'{}',?,?)",nextRevision,record,revision,nextPdf,old.get("template_version_id"),archivistB.userId());db.update("UPDATE archive.record SET current_revision_id=?,current_pdf_version_id=?,row_version=row_version+1 WHERE id=?",nextRevision,nextPdf,record);
        UUID last=(UUID)db.required("SELECT current_step_id FROM workflow.instance WHERE id=?",flow).get("current_step_id");assertThrows(ApiFault.class,()->workflow.decide(context(supervisor,"WORKFLOW_STEP",last,"DECIDE_STEP",1),last,Sql.map("result","APPROVE","comment","不应对换版输入发权")));assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant WHERE source_request_id=?",request.get("id")));assertEquals(404,assertThrows(ApiFault.class,()->authority.detail(applicant,record)).status);
    }
    /** 复核上下文只对当前必要处理人有效；节点推进后旧候选阅读停止。 */
    @Test void candidateReviewEndsWhenCurrentStepChanges() throws Exception {
        UUID record=published("GENERAL",b);var source=authority.record(record);UUID box=(UUID)source.get("box_id");UUID draft=(UUID)catalog.record(context(archivistB,"ARCHIVE_BOX",box,"CREATE_RECORD",null),Sql.map("boxId",box.toString(),"itemNo",2,"categoryCode","ADMIN","carrierType","ELECTRONIC","title","待审合成候选","safeTitle","候选安全题名","keywords",List.of("质量检查"),"ownerId",people.get("managerB").toString(),"archiveDate",LocalDate.now(WorkCalendarService.ZONE).toString(),"templateVersionId",templateRecord.toString())).get("id");UUID pdf=upload(archivistB,draft);
        var before=reading.create(archivistB,Sql.map("reviewContext",Sql.map("type","SCAN_QUALITY","id",draft.toString(),"candidatePdfVersionId",pdf.toString())),UUID.randomUUID(),UUID.randomUUID());reading.process(archivistB,(UUID)before.get("jobId"),UUID.randomUUID());assertEquals(403,assertThrows(ApiFault.class,()->reading.create(applicant,Sql.map("reviewContext",Sql.map("type","SCAN_QUALITY","id",draft.toString(),"candidatePdfVersionId",pdf.toString())),UUID.randomUUID(),UUID.randomUUID())).status);
        UUID flow=review(archivistB,"RECORD",draft,pdf);assertEquals(404,assertThrows(ApiFault.class,()->reading.page(archivistB,(UUID)before.get("usageId"),1,UUID.randomUUID())).status);Actor manager=actors.get(people.get("managerB"));var receipt=reading.create(manager,Sql.map("reviewContext",Sql.map("type","REGISTRATION","id",flow.toString(),"candidatePdfVersionId",pdf.toString())),UUID.randomUUID(),UUID.randomUUID());reading.process(manager,(UUID)receipt.get("jobId"),UUID.randomUUID());UUID step=(UUID)db.required("SELECT current_step_id FROM workflow.instance WHERE id=?",flow).get("current_step_id");workflow.decide(context(manager,"WORKFLOW_STEP",step,"DECIDE_STEP",1),step,Sql.map("result","APPROVE","comment","合成复核通过"));assertEquals(403,assertThrows(ApiFault.class,()->reading.page(manager,(UUID)receipt.get("usageId"),1,UUID.randomUUID())).status);
    }
    /** 公司调休日历是版本化事实；新版本不能重新计算既有批准的截止时间。 */
    @Test void nationalCalendarKeepsLegacyVersionAndApprovedDeadline() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));approveBatch(batch);var prior=db.required("SELECT * FROM archive.borrow_request WHERE batch_id=?",batch);Object deadline=prior.get("approved_until");
        int legacyVersion = (int) db.count("SELECT COALESCE(max(calendar_version),0)+1 FROM iam.work_calendar");
        LocalDate holiday = LocalDate.of(2026, 11, 6);
        db.update("INSERT INTO iam.work_calendar(calendar_version,calendar_date,is_workday,published_by,change_reason) VALUES(?,?,?,?,?)",
            legacyVersion, holiday.plusDays(1), true, supervisor.userId(), "合成历史补班");
        Instant from = holiday.atTime(10,0).atZone(WorkCalendarService.ZONE).toInstant();
        assertEquals(holiday.plusDays(1).atTime(23,59,59).atZone(WorkCalendarService.ZONE).toInstant(),
            calendar.until(legacyVersion, Map.of("mode","WORKDAYS","amount",1), from));
        assertEquals(holiday.plusDays(3).atTime(23,59,59).atZone(WorkCalendarService.ZONE).toInstant(),
            calendar.until(calendar.current(), Map.of("mode","WORKDAYS","amount",1), from));
        assertEquals(deadline,db.required("SELECT approved_until FROM archive.borrow_request WHERE id=?",prior.get("id")).get("approved_until"));
    }
    /** 清单上限在查询对象前拒绝，不能通过请求携带一个自报密级降低路线。 */
    @Test void oversizedOrClientChosenClassificationIsRejected() {
        var body=Sql.map("form","ELECTRONIC","actions",List.of("VIEW"),"purpose","越界测试","items",java.util.stream.IntStream.range(0,51).mapToObj(index->Sql.map("objectType","RECORD","objectId",UUID.randomUUID().toString(),"actions",List.of("VIEW"))).toList(),"period",Sql.map("mode","WORKDAYS","amount",3));assertEquals(400,assertThrows(ApiFault.class,()->borrow.submit(plain(applicant,"SUBMIT_BORROW"),body)).status);body.put("classification","GENERAL");assertEquals(400,assertThrows(ApiFault.class,()->borrow.submit(plain(applicant,"SUBMIT_BORROW"),body)).status);
    }
    /** 续借拒绝不延长原授权；原批准和决定历史完整保留。 */
    @Test void rejectedRenewalKeepsOriginalDeadline() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);
        var old=db.required("SELECT g.* FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=?",original);
        UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));assertEquals(old.get("valid_until"),db.required("SELECT valid_until FROM archive.access_grant WHERE id=?",old.get("id")).get("valid_until"));
        var step=current(renewal);workflow.decide(context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",(UUID)step.get("id"),"DECIDE_STEP",1),(UUID)step.get("id"),Sql.map("result","REJECT","comment","合成续借驳回"));
        assertEquals("REJECTED",db.required("SELECT state FROM archive.borrow_batch WHERE id=?",renewal).get("state"));assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=?",renewal));assertNotNull(view(applicant,record));
        assertEquals(old.get("valid_until"),db.required("SELECT valid_until FROM archive.access_grant WHERE id=?",old.get("id")).get("valid_until"));assertNotNull(renew(original,plain(applicant,"RENEW_BORROW")));
    }
    /** 相同操作重放同一结果，不同内容和另一个待审操作均不能重复创建续借。 */
    @Test void renewalOperationReplayAndDuplicateSubmissionAreBound() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);var command=plain(applicant,"RENEW_BORROW");UUID first=renew(original,command);assertEquals(first,renew(original,command));
        assertEquals(409,assertThrows(ApiFault.class,()->borrow.renew(command,original,Sql.map("period",Sql.map("mode","WORKDAYS","amount",2),"purpose","合成续借用途","reason","合成续借原因"))).status);
        assertEquals(409,assertThrows(ApiFault.class,()->renew(original,plain(applicant,"RENEW_BORROW"))).status);assertEquals(1,db.count("SELECT count(*) FROM archive.borrow_batch WHERE renewal_of_batch_id=?",original));
    }
    /** 完整必要节点批准才创建新来源，原截止不改写，不能沿同一原批次反复叠加续借。 */
    @Test void approvedRenewalUsesNewGrantAndImmutableOriginalDeadline() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);var old=db.required("SELECT g.* FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=?",original);
        UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));assertEquals(5,db.count("SELECT count(*) FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=?",renewal));approveBatch(renewal);
        var added=db.required("SELECT g.* FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=?",renewal);assertEquals(old.get("id"),added.get("parent_grant_id"));assertTrue(Instant.parse(added.get("valid_until").toString()).isAfter(Instant.parse(old.get("valid_until").toString())));assertEquals(old.get("valid_until"),db.required("SELECT valid_until FROM archive.access_grant WHERE id=?",old.get("id")).get("valid_until"));assertEquals(1,db.count("SELECT CASE WHEN archive.grant_valid(?,?) THEN 1 ELSE 0 END",added.get("id"),applicant.userId()));assertEquals(409,assertThrows(ApiFault.class,()->renew(original,plain(applicant,"RENEW_BORROW"))).status);
    }
    /** 未运行到期清理也须实时拒绝；在途续借完成真实批准后才生成新的可读来源。 */
    @Test void expiryDuringRenewalDoesNotKeepOldSessionAlive() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);UUID usage=view(applicant,record);var old=db.required("SELECT g.* FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=?",original);
        db.update("UPDATE archive.access_grant SET valid_until=CURRENT_TIMESTAMP+interval '3 seconds' WHERE id=?",old.get("id"));UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));Thread.sleep(3200);
        assertEquals("ACTIVE",db.required("SELECT state FROM archive.access_grant WHERE id=?",old.get("id")).get("state"));assertEquals(403,assertThrows(ApiFault.class,()->reading.page(applicant,usage,1,UUID.randomUUID())).status);
        lifecycle.tick();assertEquals("EXPIRED",db.required("SELECT state FROM archive.access_grant WHERE id=?",old.get("id")).get("state"));approveBatch(renewal);assertNotNull(view(applicant,record));assertEquals(403,assertThrows(ApiFault.class,()->reading.page(applicant,usage,1,UUID.randomUUID())).status);
    }
    /** 自然到期的旧来源不能重新发起续借；他人也不能以原批次UUID代申请。 */
    @Test void expiredOrForeignBorrowCannotStartRenewal() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);
        assertEquals(404,assertThrows(ApiFault.class,()->renew(original,plain(actors.get(people.get("outsider")),"RENEW_BORROW"))).status);
        db.update("UPDATE archive.access_grant SET valid_from=CURRENT_TIMESTAMP-interval '2 days',valid_until=CURRENT_TIMESTAMP-interval '1 second' WHERE source_request_id IN (SELECT id FROM archive.borrow_request WHERE batch_id=?)",original);assertEquals(409,assertThrows(ApiFault.class,()->renew(original,plain(applicant,"RENEW_BORROW"))).status);
    }
    /** 续借送审后撤销原来源，已有节点决定不能继续完成扩权；驳回仍可结束失效申请。 */
    @Test void originalRevocationBlocksPendingRenewalApproval() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));var old=db.required("SELECT g.* FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=?",original);
        policies.revoke(context(supervisor,"ARCHIVE_GRANT",(UUID)old.get("id"),"REVOKE_ARCHIVE_GRANT",1),(UUID)old.get("id"),Sql.map("reason","合成原来源撤权"));var step=current(renewal);Actor holder=actors.get(step.get("assignee_id"));assertEquals(409,assertThrows(ApiFault.class,()->workflow.decide(context(holder,"WORKFLOW_STEP",(UUID)step.get("id"),"DECIDE_STEP",1),(UUID)step.get("id"),Sql.map("result","APPROVE","comment","不应沿撤权来源扩权"))).status);
        workflow.decide(context(holder,"WORKFLOW_STEP",(UUID)step.get("id"),"DECIDE_STEP",1),(UUID)step.get("id"),Sql.map("result","REJECT","comment","原来源撤权，结束申请"));assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=?",renewal));
    }
    /** 审批人更替后旧节点暂停，合法改派不跳节点、不改原路线快照。 */
    @Test void renewalManagerChangeRequiresLawfulReassignment() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));var step=current(renewal);UUID stepId=(UUID)step.get("id");UUID manager=people.get("managerA"),replacement=people.get("second");
        try {
            int version=((Number)db.required("SELECT row_version FROM iam.department WHERE id=?",a).get("row_version")).intValue();organization.department(context(admin,"DEPARTMENT",a,"MANAGEMENT_DUTIES",version),a,Sql.map("duties",List.of(Sql.map("dutyType","DEPARTMENT_MANAGER","userId",replacement.toString(),"validFrom",Instant.now().toString())),"changeReason","合成续借负责人交接","approvalRef","TEST-RENEW-CHANGE"));
            actors.put(manager,login("AR-"+manager,password));actors.put(replacement,login("AR-"+replacement,password));assertEquals(403,assertThrows(ApiFault.class,()->workflow.decide(context(actors.get(manager),"WORKFLOW_STEP",stepId,"DECIDE_STEP",1),stepId,Sql.map("result","APPROVE","comment","旧任职不能审批"))).status);
            assertTrue(((List<?>)workflow.attention(supervisor).get("items")).stream().anyMatch(item->((Map<?,?>)item).get("id").equals(stepId)));
            workflow.reassign(context(supervisor,"WORKFLOW_STEP",stepId,"REASSIGN_STEP",1),stepId,Sql.map("assigneeId",replacement.toString(),"reason","交接给实际负责人","approvalReference","TEST-REASSIGN"));approveBatch(renewal);assertEquals(1,db.count("SELECT count(*) FROM workflow.assignment_event WHERE step_id=?",stepId));assertEquals(manager,step.get("assignee_id"));
        } finally {int version=((Number)db.required("SELECT row_version FROM iam.department WHERE id=?",a).get("row_version")).intValue();organization.department(context(admin,"DEPARTMENT",a,"MANAGEMENT_DUTIES",version),a,Sql.map("duties",List.of(Sql.map("dutyType","DEPARTMENT_MANAGER","userId",manager.toString(),"validFrom",Instant.now().toString())),"changeReason","恢复合成负责人","approvalRef","TEST-RESTORE"));}
    }
    /** 真实到期提醒按接收人和期限去重，已读不续权，其他员工不能确认本人通知。 */
    @Test void remindersAreDurableDeduplicatedAndScoped() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);UUID request=(UUID)db.required("SELECT id FROM archive.borrow_request WHERE batch_id=?",original).get("id");Instant until=LocalDate.now(WorkCalendarService.ZONE).plusDays(1).atTime(23,59,59).atZone(WorkCalendarService.ZONE).toInstant();db.update("UPDATE archive.access_grant SET valid_until=? WHERE source_request_id=?",java.sql.Timestamp.from(until),request);db.update("UPDATE archive.borrow_request SET approved_until=? WHERE id=?",java.sql.Timestamp.from(until),request);
        evidence(true);assertEquals(503,assertThrows(ApiFault.class,()->lifecycle.tick()).status);assertEquals(0,db.count("SELECT count(*) FROM ops.inbox_notification WHERE request_id=?",request));evidence(false);
        lifecycle.tick();lifecycle.tick();assertEquals(1,db.count("SELECT count(*) FROM ops.inbox_notification WHERE request_id=?",request));var notice=db.required("SELECT * FROM ops.inbox_notification WHERE request_id=?",request);assertEquals(1,((Number)notice.get("days_before")).intValue());UUID id=(UUID)notice.get("id");assertEquals(404,assertThrows(ApiFault.class,()->lifecycle.read(actors.get(people.get("outsider")),id)).status);lifecycle.read(applicant,id);Object readAt=db.required("SELECT read_at FROM ops.inbox_notification WHERE id=?",id).get("read_at");lifecycle.read(applicant,id);assertEquals(readAt,db.required("SELECT read_at FROM ops.inbox_notification WHERE id=?",id).get("read_at"));assertEquals(until.toString(),db.required("SELECT valid_until FROM archive.access_grant WHERE source_request_id=?",request).get("valid_until"));
        Path fixture=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("lifecycle-fixture.private.json");var row=authority.record(record);Files.writeString(fixture,Crypto.json(Sql.map("recordId",record.toString(),"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",row.get("current_pdf_version_id").toString(),"fileNo",row.get("file_no"),"batchId",original.toString(),"requestId",request.toString(),"password",password,"people",people.entrySet().stream().map(entry->Sql.map("key",entry.getKey(),"id",entry.getValue().toString(),"employeeNo","AR-"+entry.getValue())).toList())));PrivateFiles.restrict(fixture);
    }
    /** 已批准续借使旧期限提醒立刻不可见，后台随后保留取消记录而不是重复提醒。 */
    @Test void renewalApprovalCancelsOldDeadlineReminder() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);UUID request=(UUID)db.required("SELECT id FROM archive.borrow_request WHERE batch_id=?",original).get("id");Instant until=LocalDate.now(WorkCalendarService.ZONE).atTime(23,59,59).atZone(WorkCalendarService.ZONE).toInstant();db.update("UPDATE archive.access_grant SET valid_until=? WHERE source_request_id=?",java.sql.Timestamp.from(until),request);lifecycle.tick();assertEquals(1,db.count("SELECT count(*) FROM ops.inbox_notification WHERE request_id=? AND days_before=0",request));UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));approveBatch(renewal);assertEquals(0,db.count("SELECT CASE WHEN archive.reminder_current(?,?,?::timestamptz) THEN 1 ELSE 0 END",request,applicant.userId(),until.toString()));lifecycle.tick();assertEquals("CANCELLED",db.required("SELECT state FROM ops.inbox_notification WHERE request_id=?",request).get("state"));
    }
    /** 管理查询按平台组织和档案配置能力分别授权，批准依据必须写入实际历史。 */
    @Test void organizationReadBoundariesAreEnforced() {
        assertFalse(((List<?>)organization.read(admin).get("departments")).isEmpty());assertEquals(403,assertThrows(ApiFault.class,()->organization.read(supervisor)).status);
    }
    /** 不同操作号并发点击同一原批次，必须只创建一个续借结果。 */
    @Test void concurrentRenewalCreatesOnlyOneBatch() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);List<java.util.concurrent.Future<Integer>> results=new ArrayList<>();
            for(int index=0;index<2;index++) results.add(workers.submit(()->{start.await();try{renew(original,plain(applicant,"RENEW_BORROW"));return 201;}catch(ApiFault conflict){return conflict.status;}}));
            start.countDown();List<Integer> codes=new ArrayList<>();for(var result:results) codes.add(result.get(15,java.util.concurrent.TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(201,409),codes);assertEquals(1,db.count("SELECT count(*) FROM archive.borrow_batch WHERE renewal_of_batch_id=?",original));
        }
    }
    /** 申请人新任档案员不能利用身份变化减少原续借路线的必要职责。 */
    @Test void renewalDoesNotDropOriginalArchivistDutyAfterRoleChange() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);
        UUID old=(UUID)db.required("SELECT id FROM iam.user_role WHERE user_id=? AND module='ARCHIVE' AND role_code='DEPARTMENT_ARCHIVIST' AND state='ACTIVE'",archivistA.userId()).get("id"),assignment=UUID.randomUUID();
        try {
            db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE id=?",old);UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='ARCHIVE' AND code='DEPARTMENT_ARCHIVIST'").get("id");db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,'ARCHIVE','DEPARTMENT_ARCHIVIST')",assignment,applicant.userId(),role,a,admin.userId());
            UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));assertEquals(5,db.count("SELECT count(*) FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=?",renewal));var first=current(renewal);Actor manager=actors.get(first.get("assignee_id"));workflow.decide(context(manager,"WORKFLOW_STEP",(UUID)first.get("id"),"DECIDE_STEP",1),(UUID)first.get("id"),Sql.map("result","APPROVE"));var step=current(renewal);assertEquals(applicant.userId(),step.get("assignee_id"));assertEquals(409,assertThrows(ApiFault.class,()->workflow.decide(context(applicant,"WORKFLOW_STEP",(UUID)step.get("id"),"DECIDE_STEP",1),(UUID)step.get("id"),Sql.map("result","APPROVE","comment","本人不得审批必要节点"))).status);
        } finally {db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE id=?",assignment);db.update("UPDATE iam.user_role SET state='ACTIVE',valid_until=NULL WHERE id=?",old);}
    }
    /** 到期归档后的原来源仍能明确撤销，从而阻止已在途续借继续扩权。 */
    @Test void expiredSourceCanBeRevokedWhileRenewalIsPending() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);var grant=db.required("SELECT g.* FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE q.batch_id=?",original);db.update("UPDATE archive.access_grant SET valid_until=CURRENT_TIMESTAMP+interval '2 seconds' WHERE id=?",grant.get("id"));UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));Thread.sleep(2200);lifecycle.tick();assertEquals("EXPIRED",db.required("SELECT state FROM archive.access_grant WHERE id=?",grant.get("id")).get("state"));
        policies.revoke(context(supervisor,"ARCHIVE_GRANT",(UUID)grant.get("id"),"REVOKE_ARCHIVE_GRANT",1),(UUID)grant.get("id"),Sql.map("reason","到期后停止在途续借依据"));assertEquals("REVOKED",db.required("SELECT state FROM archive.access_grant WHERE id=?",grant.get("id")).get("state"));var step=current(renewal);assertEquals(409,assertThrows(ApiFault.class,()->workflow.decide(context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",(UUID)step.get("id"),"DECIDE_STEP",1),(UUID)step.get("id"),Sql.map("result","APPROVE","comment","撤权后不得续权"))).status);
    }
    /** 人员候选按具体文件目录管理权限过滤，跨部门查人属于档案超级管理员职责。 */
    @Test void policySubjectSelectionUsesRecordManagementScope() throws Exception {
        UUID record=published("GENERAL",b);assertFalse(((List<?>)policies.subjects(archivistB,record,"AR-"+applicant.userId()).get("items")).isEmpty());assertEquals(403,assertThrows(ApiFault.class,()->policies.subjects(applicant,record,"")).status);assertEquals(403,assertThrows(ApiFault.class,()->policies.subjects(archivistA,record,"")).status);assertEquals(403,assertThrows(ApiFault.class,()->policies.subjects(admin,record,"")).status);assertFalse(((List<?>)policies.subjects(supervisor,record,"AR-"+applicant.userId()).get("items")).isEmpty());
    }
    /** 同一员工重新任命产生新任职记录时，必要节点仍需显式交接而不会永久卡住。 */
    @Test void sameManagerReappointmentCanTransferToNewAssignment() throws Exception {
        UUID record=published("GENERAL",b);policy(record,false,List.of());UUID original=submitBorrow(applicant,List.of(record));approveBatch(original);UUID renewal=renew(original,plain(applicant,"RENEW_BORROW"));var before=current(renewal);UUID id=(UUID)before.get("id"),manager=people.get("managerA");
        int version=((Number)db.required("SELECT row_version FROM iam.department WHERE id=?",a).get("row_version")).intValue();organization.department(context(admin,"DEPARTMENT",a,"MANAGEMENT_DUTIES",version),a,Sql.map("duties",List.of(Sql.map("dutyType","DEPARTMENT_MANAGER","userId",manager.toString(),"validFrom",Instant.now().toString())),"changeReason","合成同人新任命","approvalRef","TEST-REAPPOINT"));actors.put(manager,login("AR-"+manager,password));
        assertTrue(((List<?>)workflow.assignmentOptions(supervisor,id).get("items")).stream().anyMatch(item->((Map<?,?>)item).get("id").equals(manager)));
        workflow.reassign(context(supervisor,"WORKFLOW_STEP",id,"REASSIGN_STEP",1),id,Sql.map("assigneeId",manager.toString(),"reason","交接到新的有效任职","approvalReference","TEST-REAPPOINT-HANDOVER"));assertNotEquals(before.get("assignment_ref"),db.required("SELECT assignment_ref FROM workflow.step WHERE id=?",id).get("assignment_ref"));approveBatch(renewal);
    }
    /** 显式拒绝模块入口的超级管理员不参与分配，也不能被改派为必要处理人。 */
    @Test void deniedArchiveEntryExcludesSuperFromAssignment() throws Exception {
        person("deniedSuper",a,"STAFF");UUID user=people.get("deniedSuper"),denyRole=UUID.randomUUID(),superRole=(UUID)db.required("SELECT id FROM iam.role WHERE module='ARCHIVE' AND code='SUPER_ADMIN'").get("id");
        UUID assignment=new UUID(0,Math.abs(UUID.randomUUID().getLeastSignificantBits()));
        db.update("INSERT INTO iam.role(id,module,code,name) VALUES(?,'ARCHIVE',?,'合成入口拒绝角色')",denyRole,"DENY_"+denyRole.toString().substring(0,8));
        db.update("INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by) SELECT ?,id,'COMPANY','DENY',? FROM iam.permission WHERE code='ARCHIVE.ACCESS'",denyRole,admin.userId());
        db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,CURRENT_TIMESTAMP,?,'ARCHIVE','SUPER_ADMIN')",assignment,user,superRole,admin.userId());
        UUID deniedAssignment=UUID.randomUUID();db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,CURRENT_TIMESTAMP,?,'ARCHIVE',?)",deniedAssignment,user,denyRole,admin.userId(),"DENY_"+denyRole.toString().substring(0,8));
        UUID personalDeny=UUID.randomUUID();
        try {
            assertEquals(supervisor.userId(),dutyResolver.resolve(new FixedBorrowRoute.Duty("ARCHIVE_ADMIN",null)).user());
            UUID record=published("GENERAL",b);policy(record,false,List.of());UUID batch=submitBorrow(applicant,List.of(record));for(int index=0;index<4;index++){var step=current(batch);workflow.decide(context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",(UUID)step.get("id"),"DECIDE_STEP",1),(UUID)step.get("id"),Sql.map("result","APPROVE","comment","到最终必要节点"));}
            var step=current(batch);UUID id=(UUID)step.get("id");assertFalse(((List<?>)workflow.assignmentOptions(supervisor,id).get("items")).stream().anyMatch(item->((Map<?,?>)item).get("id").equals(user)));
            assertEquals(403,assertThrows(ApiFault.class,()->workflow.reassign(context(supervisor,"WORKFLOW_STEP",id,"REASSIGN_STEP",1),id,Sql.map("assigneeId",user.toString(),"reason","显式拒绝入口不能成为合法处理人","approvalReference","TEST-DENY"))).status);
            db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE id=?",deniedAssignment);
            db.update("INSERT INTO iam.permission_assignment(id,user_id,permission_id,scope_type,effect,valid_from,granted_by) SELECT ?,?,id,'COMPANY','DENY',CURRENT_TIMESTAMP,? FROM iam.permission WHERE code='ARCHIVE.ACCESS'",personalDeny,user,admin.userId());
            assertEquals(supervisor.userId(),dutyResolver.resolve(new FixedBorrowRoute.Duty("ARCHIVE_ADMIN",null)).user());
            assertEquals(403,assertThrows(ApiFault.class,()->workflow.reassign(context(supervisor,"WORKFLOW_STEP",id,"REASSIGN_STEP",1),id,Sql.map("assigneeId",user.toString(),"reason","个人拒绝同样优先","approvalReference","TEST-PERSONAL-DENY"))).status);assertEquals(0,db.count("SELECT count(*) FROM workflow.assignment_event WHERE step_id=?",id));
        } finally {db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE id IN (?,?)",assignment,deniedAssignment);db.update("UPDATE iam.permission_assignment SET state='REVOKED' WHERE id=?",personalDeny);}
    }
    /** 续借输入固定只有期限、用途和原因；服务端决定原范围及路由。 */
    private UUID renew(UUID original,Commands.Context context) {return (UUID)borrow.renew(context,original,Sql.map("period",Sql.map("mode","WORKDAYS","amount",3),"purpose","合成续借用途","reason","合成续借原因")).get("id");}
    /** 获取真实当前节点供负例和交接验证，不直接改流程状态。 */
    private Map<String,Object> current(UUID batch) {return db.required("SELECT s.* FROM archive.borrow_request q JOIN workflow.instance w ON w.id=q.workflow_instance_id JOIN workflow.step s ON s.id=w.current_step_id WHERE q.batch_id=?",batch);}
    /** 即使有人改写策略布尔值，也不能把未获批准的部门正文开放洗成有效。 */
    @Test void policyFlagsCannotExceedImmutableApprovalSnapshot() throws Exception {UUID record=published("GENERAL",a);UUID policy=policy(record,false,List.of());db.update("UPDATE archive.access_policy SET allow_owner_department_view=true WHERE id=?",policy);assertEquals(0,db.count("SELECT CASE WHEN archive.policy_approved(?) THEN 1 ELSE 0 END",policy));assertEquals(403,assertThrows(ApiFault.class,()->view(applicant,record)).status);}
}
