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
@EnabledIfEnvironmentVariable(named="IMS_PHYSICAL_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class,PhysicalLoanIntegrationTest.TimeConfiguration.class},webEnvironment=SpringBootTest.WebEnvironment.NONE,
 properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PhysicalLoanIntegrationTest {
    /** 业务服务全部使用正式实现；替换范围仅为测试专用无害签名库。 */
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired ArchiveCatalogService catalog;@Autowired ArchiveAuthority authority;
    @Autowired ArchivePolicyService policies;@Autowired BorrowService borrow;@Autowired WorkflowEngine workflow;@Autowired ArchiveReadingService reading;
    @Autowired LegacyWorkCalendarFixture calendar;@Autowired UploadService uploads;@Autowired UploadAssembly assembly;@Autowired FileInspectionService inspection;
    @Autowired OrganizationDuties organization;
    @Autowired com.company.platform.portal.PortalWork portalWork;
    /** 真实到期状态和站内通知工作器，测试不会替换授权判定。 */
    @Autowired BorrowLifecycleService lifecycle;
    /** 改派必须同时满足真实任职与当前模块权限，不只检查角色名称。 */
    @Autowired WorkflowDuties dutyResolver;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    /** 每次测试独立建档；统一合成部门和员工不含正式业务数据。 */
    private UUID a,b,templateBox,templateRecord;private String password;private final Map<String,UUID> people=new HashMap<>();private final Map<UUID,Actor> actors=new HashMap<>();private Actor admin,supervisor,applicant,archivistA,archivistB;
    private final UUID scanner=UUID.fromString("42210210-46cc-4ce8-8624-5c0da61a7760");
    /** 验证地址后建立合成员工；工作日历通过正式API服务发布，不假定生产周末规则。 */
    @BeforeAll void setup() throws Exception {
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:5544[34678]/ims_platform_dev_[0-9]+"));password=Crypto.token();admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence(false);
        // 复跑仅结束本专用库上一轮合成超级管理员任职，历史批准和文件全部保留。
        db.update("UPDATE iam.user_role a SET state='ENDED',valid_until=CURRENT_TIMESTAMP FROM iam.user_account u WHERE a.user_id=u.id AND a.module='ARCHIVE' AND a.role_code='SUPER_ADMIN' AND a.state='ACTIVE' AND u.employee_no LIKE 'AR-%'");
        a=department("A");b=department("B");person("super",a,"SUPER_ADMIN");person("archA",a,"DEPARTMENT_ARCHIVIST");person("archB",b,"DEPARTMENT_ARCHIVIST");person("managerA",a,"STAFF");person("managerB",b,"STAFF");person("gm",a,"STAFF");person("applicant",a,"STAFF");person("second",a,"STAFF");person("outsider",b,"STAFF");
        for(var pair:Map.of(a,people.get("managerA"),b,people.get("managerB")).entrySet()) db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'合成验收',?)",UUID.randomUUID(),pair.getKey(),pair.getValue(),admin.userId());
        // 公司总经理职责与公盘角色分离，避免拿公盘管理员当档案批准人。
        db.update("UPDATE iam.company_duty SET valid_until=CURRENT_TIMESTAMP WHERE duty_code='GENERAL_MANAGER' AND valid_until IS NULL");db.update("INSERT INTO iam.company_duty(id,duty_code,user_id,valid_from,changed_by,change_reason) VALUES(?,'GENERAL_MANAGER',?,CURRENT_TIMESTAMP,?,'合成验收')",UUID.randomUUID(),people.get("gm"),admin.userId());
        person("leaderA",a,"STAFF");person("leaderB",b,"STAFF");for(var unit:Map.of(a,people.get("leaderA"),b,people.get("leaderB")).entrySet())db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'SUPERVISING_LEADER',CURRENT_TIMESTAMP,'合成分管领导',?)",UUID.randomUUID(),unit.getKey(),unit.getValue(),admin.userId());
        refreshActors();
        templateBox=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","BOX_"+UUID.randomUUID(),"name","电子档案盒基础模板","categoryCode","ADMIN","objectType","BOX","fields",List.of())).get("id");
        templateRecord=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","RECORD_"+UUID.randomUUID(),"name","电子文件基础模板","categoryCode","ADMIN","objectType","RECORD","fields",List.of())).get("id");
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','档案隔离扫描','ARCHIVE',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope,valid_until=EXCLUDED.valid_until",scanner,"{"+a+","+b+"}");
    }
    @BeforeEach void fresh() throws Exception {testTime.offset=0;calendar.reset();DriveReadIntegrationTest.NativeScanner.DOWN.set(false);DriveReadIntegrationTest.NativeScanner.scanStarted=null;DriveReadIntegrationTest.NativeScanner.scanRelease=null;evidence(false);refreshActors();}
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


    /** 时钟不模拟权限、批准或业务结果，所有决定仍经过正式事务。 */
    static class TestTime extends PhysicalTime {volatile long offset;@Override public Instant now(){return Instant.now().plusSeconds(offset);}}
    @org.springframework.boot.test.context.TestConfiguration static class TimeConfiguration {@org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary TestTime testTime(){return new TestTime();} @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary LegacyWorkCalendarFixture legacyCalendar(Sql db){return new LegacyWorkCalendarFixture(db);}}
    @Autowired ArchiveBorrowDirectory borrowDirectory;
    @Autowired TestTime testTime;@Autowired PhysicalItems physicalItems;@Autowired PhysicalLoans loans;@Autowired PaperBorrowing paper;
    private UUID paperBox(UUID department,String carrier) {Actor clerk=department.equals(a)?archivistA:archivistB;LocalDate today=LocalDate.now(WorkCalendarService.ZONE);UUID box=(UUID)catalog.box(context(clerk,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),Sql.map("departmentId",department.toString(),"title","合成实物盒","safeTitle","合成可申请盒","classification","GENERAL","retentionCode","10","archiveDate",today.toString(),"physicalFileCount",0,"location","合成库房A-01","keywords",List.of("实物"),"templateVersionId",templateBox.toString(),"categoryCode","ADMIN","ownerId",people.get(department.equals(a)?"managerA":"managerB").toString(),"numberMonth",today.withDayOfMonth(1).toString(),"carrierType",carrier,"extensionValues",Sql.map("paperTags",carrier.equals("ELECTRONIC")?List.of():List.of("纸质原件")))).get("id");approve(review(clerk,"BOX",box,null));return box;}
    private UUID paperRecord(UUID box,int number) {var parent=db.required("SELECT * FROM archive.box WHERE id=?",box);Actor clerk=parent.get("department_id").equals(a)?archivistA:archivistB;UUID record=(UUID)catalog.record(context(clerk,"ARCHIVE_BOX",box,"CREATE_RECORD",null),Sql.map("boxId",box.toString(),"itemNo",number,"categoryCode","ADMIN","carrierType","PAPER","title","合成纸质合同"+number,"safeTitle","可申请原件"+number,"location","合成库房A-01-"+number,"keywords",List.of("纸质"),"ownerId",people.get(parent.get("department_id").equals(a)?"managerA":"managerB").toString(),"archiveDate",LocalDate.now(WorkCalendarService.ZONE).toString(),"templateVersionId",templateRecord.toString())).get("id");approve(review(clerk,"RECORD",record,null));paperPolicy(record,clerk);return record;}
    private void paperPolicy(UUID record,Actor clerk) {var row=authority.record(record);UUID flow=(UUID)policies.propose(context(clerk,"ARCHIVE_POLICY",record,"REQUEST_ACCESS_POLICY",null),Sql.map("objectType","RECORD","objectId",record.toString(),"departmentScope",List.of(a.toString(),b.toString()),"titleVisibility","SAFE_TITLE","safeSearchFields",List.of("safeTitle"),"allowOwnerDepartmentView",false,"recordRevisionId",row.get("current_revision_id").toString(),"pdfVersionId",null,"userIds",List.of(),"validFrom",Instant.now().minusSeconds(1).toString(),"validUntil",Instant.now().plusSeconds(864000).toString(),"reason","仅开放纸质安全题名")).get("id");approve(flow);}
    private UUID physical(UUID record) {return (UUID)db.required("SELECT id FROM archive.physical_item WHERE record_id=?",record).get("id");}
    private UUID submitCurrentPaper(List<UUID> ids,int days) {return (UUID)borrow.submit(plain(applicant,"SUBMIT_PAPER_BORROW"),Sql.map("form","PAPER","actions",List.of("BORROW_PHYSICAL"),"purpose","合成实物借阅验收","period",Sql.map("mode","NATURAL_DAYS","amount",days),"items",ids.stream().map(id->Sql.map("physicalItemId",id.toString())).toList())).get("id");}
    private UUID submitPaper(List<UUID> ids,int days) {
        return legacyPaper(submitCurrentPaper(ids,days),applicant,days>7);
    }
    /** 旧版兼容用例固定旧版路线和送审快照，其后的审批、交付和归还仍调用正式服务。 */
    private UUID legacyPaper(UUID batch,Actor borrower,boolean supervising){
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            var row=db.required("""
                SELECT q.id,q.workflow_instance_id,q.owner_department_id,b.applicant_department_id,b.calendar_version,
                       b.parent_max_classification,w.business_snapshot
                FROM archive.borrow_request q JOIN archive.borrow_batch b ON b.id=q.batch_id
                JOIN workflow.instance w ON w.id=q.workflow_instance_id WHERE q.batch_id=?
                """,batch);
            var snapshot=new LinkedHashMap<>(WorkflowEngine.snapshot(row));
            snapshot.remove("paperHandoffs");
            UUID owner=(UUID)row.get("owner_department_id");
            String classification=row.get("parent_max_classification").toString();
            var route=new ArrayList<>(FixedBorrowRoute.plan(classification,(UUID)row.get("applicant_department_id"),owner,false));
            if(supervising)route.add(route.size()-1,new FixedBorrowRoute.Duty("SUPERVISING_LEADER",owner));
            UUID oldFlow=(UUID)row.get("workflow_instance_id");
            db.update("UPDATE workflow.instance SET state='CLOSED',current_step_id=NULL WHERE id=?",oldFlow);
            db.update("UPDATE workflow.step SET state='SKIPPED' WHERE instance_id=?",oldFlow);
            UUID flow=workflow.create(borrower,"BORROW",(UUID)row.get("id"),batch,classification,
                ((Number)row.get("calendar_version")).intValue(),snapshot,route);
            db.update("UPDATE archive.borrow_request SET workflow_instance_id=?,route_snapshot_id=(SELECT route_snapshot_id FROM workflow.instance WHERE id=?) WHERE id=?",flow,flow,row.get("id"));
        });
        return batch;
    }
    private UUID request(UUID batch) {return (UUID)db.required("SELECT id FROM archive.borrow_request WHERE batch_id=?",batch).get("id");}
    private Map<String,Object> deliveryBody(UUID item) {return Sql.map("physicalItemIds",List.of(item.toString()),"actualDeliveryAt",testTime.now().toString(),"actualQuantity",1,"condition","已核对实际原件、附件和完好情况");}
    private Map<String,Object> deliver(UUID request,UUID item) {return loans.deliver(context(archivistB,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,deliveryBody(item));}
    private UUID loan(Map<String,Object> result) {return (UUID)((Map<?,?>)((List<?>)result.get("loans")).getFirst()).get("id");}
    private int version(UUID loan) {return ((Number)db.required("SELECT row_version FROM archive.physical_loan WHERE id=?",loan).get("row_version")).intValue();}
    private UUID returnRequest(UUID loan,int count) {var ctx=new Commands.Context(applicant,UUID.randomUUID(),UUID.randomUUID(),"TEST RETURN "+loan,null,version(loan));return (UUID)loans.requestReturn(ctx,loan,Sql.map("actualQuantity",count,"condition",count==1?"原件及附件交回待核对":"少件待核对","note","合成归还")).get("id");}
    private Map<String,Object> reviewBody(UUID returned,String result,int quantity) {return Sql.map("returnRequestId",returned.toString(),"result",result,"actualReceivedAt",testTime.now().toString(),"actualQuantity",quantity,"condition","管理员实收核对记录","allAttachmentsConfirmed",quantity==1,"intactConfirmed",quantity==1);}
    /** 跨部门新借阅、续借和归还由实际账号依次办理，交接的两次确认分别校验职责。 */
    @Test void newCrossDepartmentPaperHandoffs() {
        UUID record=paperRecord(paperBox(b,"PAPER"),1),original=physical(record);
        UUID batch=submitCurrentPaper(List.of(original),3),request=request(batch);
        UUID flow=(UUID)db.required("SELECT workflow_instance_id FROM archive.borrow_request WHERE id=?",request).get("workflow_instance_id");
        List<String> route=List.of("DEPARTMENT_MANAGER","ARCHIVIST","DEPARTMENT_MANAGER","ARCHIVIST","ARCHIVE_ADMIN");
        assertEquals(route,db.rows("SELECT duty_code FROM workflow.step WHERE instance_id=? ORDER BY sequence_no",flow)
            .stream().map(row->row.get("duty_code")).toList());
        approve(flow);
        var held=db.required("SELECT * FROM archive.physical_loan WHERE request_id=?",request);
        UUID loan=(UUID)held.get("id");
        assertNull(held.get("actual_delivery_at"));
        assertEquals(Duration.ofDays(3),Duration.between(Instant.parse(held.get("reserved_at").toString()),Instant.parse(held.get("due_at").toString())));
        assertNull(borrowDirectory.options(actors.get(people.get("second")),"RECORD",record).get("physicalItemId"));
        assertThrows(ApiFault.class,()->submitCurrentPaper(List.of(original),3));
        var handoff=deliveryBody(original);
        assertEquals("WAITING_OTHER_HANDLER",loans.deliver(context(archivistB,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,handoff).get("state"));
        assertThrows(ApiFault.class,()->loans.deliver(context(archivistB,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,handoff));
        assertThrows(ApiFault.class,()->loans.deliver(context(archivistA,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,handoff));
        assertEquals("DELIVERED",loans.deliver(context(supervisor,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,handoff).get("state"));
        var renewal=paper.renew(plain(applicant,"RENEW_PHYSICAL"),loan,Sql.map(
            "period",Sql.map("mode","NATURAL_DAYS","amount",8),"purpose","合成续借","reason","合成核验尚未完成"));
        UUID renewalRequest=request((UUID)renewal.get("id"));
        UUID renewalFlow=(UUID)db.required("SELECT workflow_instance_id FROM archive.borrow_request WHERE id=?",renewalRequest).get("workflow_instance_id");
        assertEquals(route,db.rows("SELECT duty_code FROM workflow.step WHERE instance_id=? ORDER BY sequence_no",renewalFlow)
            .stream().map(row->row.get("duty_code")).toList());
        approve(renewalFlow);
        assertEquals(Instant.parse(held.get("due_at").toString()).plus(Duration.ofDays(8)),
            Instant.parse(db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at").toString()));
        UUID returned=returnRequest(loan,1);
        UUID returnFlow=(UUID)db.required("SELECT workflow_instance_id FROM archive.physical_return WHERE id=?",returned).get("workflow_instance_id");
        assertEquals(route,db.rows("SELECT duty_code FROM workflow.step WHERE instance_id=? ORDER BY sequence_no",returnFlow)
            .stream().map(row->row.get("duty_code")).toList());
        var receipt=reviewBody(returned,"CONFIRM",1);
        assertThrows(ApiFault.class,()->loans.review(context(archivistB,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt));
        approve(returnFlow);
        assertThrows(ApiFault.class,()->loans.review(context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt));
        assertEquals("WAITING_SUPER_ADMIN",loans.review(context(archivistB,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt).get("state"));
        assertEquals("CONFIRMED",loans.review(context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt).get("state"));
        assertEquals(original,borrowDirectory.options(applicant,"RECORD",record).get("physicalItemId"));
    }

    /** 未取件仍到期提醒双方部门，三步实收处理完成后才释放原件。 */
    @Test void newUncollectedPaperReturnAndBothDepartmentReminders() {
        UUID record=paperRecord(paperBox(b,"PAPER"),1),original=physical(record);
        UUID batch=submitCurrentPaper(List.of(original),1),request=request(batch);
        testTime.offset=-172800;
        approveBatch(batch);
        testTime.offset=0;
        UUID loan=(UUID)db.required("SELECT id FROM archive.physical_loan WHERE request_id=?",request).get("id");
        for(String key:List.of("applicant","managerA","managerB","archA","archB","super")){
            var tasks=(List<?>)portalWork.tasks(actors.get(people.get(key)),Map.of("pageSize","100")).get("items");
            assertTrue(tasks.stream().map(item->(Map<?,?>)item).anyMatch(item->loan.equals(item.get("id"))&&"纸质借阅已到期".equals(item.get("title"))),key);
        }
        assertEquals(0,db.count("SELECT count(*) FROM archive.physical_loan WHERE id=? AND archive.paper_due_recipient(id,?)",loan,people.get("second")));
        UUID returned=returnRequest(loan,0);
        UUID returnFlow=(UUID)db.required("SELECT workflow_instance_id FROM archive.physical_return WHERE id=?",returned).get("workflow_instance_id");
        approve(returnFlow);
        var receipt=reviewBody(returned,"CONFIRM",0);
        loans.review(context(archivistB,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt);
        assertNull(borrowDirectory.options(applicant,"RECORD",record).get("physicalItemId"));
        loans.review(context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt);
        assertEquals(original,borrowDirectory.options(applicant,"RECORD",record).get("physicalItemId"));
        assertEquals(1,db.count("SELECT count(*) FROM archive.loan_event WHERE loan_id=? AND event_type='RETURN_UNCOLLECTED'",loan));
    }
    /** 同部门负责人兼任档案员时，按实际配置逐个验证首页待办、详情和提交权限。 */
    @Test @Order(0) void wholeBoxBorrowWithManagerAlsoServingAsArchivist() throws Exception {
        UUID box=paperBox(a,"PAPER");
        paperRecord(box,1);
        UUID item=(UUID)db.required("SELECT id FROM archive.physical_item WHERE box_id=? AND object_type='BOX'",box).get("id");
        UUID duty=(UUID)db.required("SELECT id FROM iam.department_manager WHERE department_id=? AND duty_type='DEPARTMENT_MANAGER' AND valid_until IS NULL",a).get("id");
        db.update("UPDATE iam.department_manager SET user_id=? WHERE id=?",archivistA.userId(),duty);
        try {
            assertEquals(true,rowWithId(physicalItems.list(applicant,Map.of("boxId",box.toString())),item).get("canApply"));
            UUID batch=(UUID)borrow.submit(plain(applicant,"SUBMIT_PAPER_BORROW"),Sql.map(
                "form","PAPER","actions",List.of("BORROW_PHYSICAL"),"purpose","同部门整盒验收",
                "period",Sql.map("mode","WORKDAYS","amount",3),"items",List.of(Sql.map("physicalItemId",item.toString())))).get("id");
            UUID request=request(batch);
            for(String code:List.of("DEPARTMENT_MANAGER","ARCHIVIST","ARCHIVE_ADMIN")) {
                Actor approver=code.equals("ARCHIVE_ADMIN")?supervisor:archivistA;
                var step=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON s.id=w.current_step_id JOIN archive.borrow_request r ON r.workflow_instance_id=w.id WHERE r.id=?",request);
                UUID id=(UUID)step.get("id");
                assertEquals(code,step.get("duty_code"));
                assertEquals(approver.userId(),step.get("assignee_id"));
                assertEquals("#/workflow?step="+id,rowWithId(portalWork.tasks(approver,Map.of("category","APPROVAL")),id).get("entry"));
                assertEquals(false,rowWithId(workflow.tasks(approver,Map.of("businessType","BORROW")),id).get("requiresReassignment"));
                assertNotNull(workflow.step(approver,id).get("business"));
                assertThrows(ApiFault.class,()->workflow.step(actors.get(people.get("outsider")),id));
                workflow.decide(context(approver,"WORKFLOW_STEP",id,"DECIDE_STEP",((Number)step.get("row_version")).intValue()),id,Sql.map("result","APPROVE"));
            }
            assertEquals("APPROVED",db.required("SELECT state FROM archive.borrow_request WHERE id=?",request).get("state"));
            assertEquals(true,rowWithId(physicalItems.list(applicant,Map.of("boxId",box.toString())),item).get("occupied"));
            var filter=Map.of("requestId",request.toString());
            for(Actor handler:List.of(archivistA,supervisor)) {
                assertEquals("#/workflow?tab=physical&view=requests&request="+request,handlingTask(handler,"交付已批准原件",request).get("entry"));
                assertEquals(true,rowWithId(loans.requests(handler,filter),request).get("canDeliver"));
            }
            assertEquals(false,rowWithId(loans.requests(applicant,filter),request).get("canDeliver"));
            var handoff=deliveryBody(item);
            loans.deliver(context(archivistA,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,handoff);
            loans.deliver(context(supervisor,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,handoff);
            UUID loan=(UUID)db.required("SELECT id FROM archive.physical_loan WHERE request_id=?",request).get("id");
            assertEquals("IN_USE",CatalogTemplates.map(loans.detail(applicant,loan).get("loan")).get("state"));
            assertEquals(true,rowWithId(physicalItems.list(applicant,Map.of("boxId",box.toString())),item).get("occupied"));

            UUID returned=returnRequest(loan,1);
            approve((UUID)db.required("SELECT workflow_instance_id FROM archive.physical_return WHERE id=?",returned).get("workflow_instance_id"));
            assertEquals(true,CatalogTemplates.map(loans.detail(archivistA,loan).get("loan")).get("canRegisterReturn"));
            var receipt=reviewBody(returned,"CONFIRM",1);
            loans.review(context(archivistA,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt);
            assertEquals("#/workflow?tab=physical&loan="+loan,handlingTask(supervisor,"核验原件归还",returned).get("entry"));
            assertEquals(true,CatalogTemplates.map(loans.detail(supervisor,loan).get("loan")).get("canReview"));
            assertEquals(false,CatalogTemplates.map(loans.detail(archivistA,loan).get("loan")).get("canReview"));
            assertEquals(true,rowWithId(physicalItems.list(applicant,Map.of("boxId",box.toString())),item).get("occupied"));
            loans.review(context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt);
            assertEquals("RETURNED",CatalogTemplates.map(loans.detail(applicant,loan).get("loan")).get("state"));
            assertEquals("CLOSED",db.required("SELECT state FROM archive.borrow_batch WHERE id=?",batch).get("state"));
            assertEquals("PUBLISHED",db.required("SELECT state FROM archive.box WHERE id=?",box).get("state"));
            assertEquals(false,rowWithId(physicalItems.list(applicant,Map.of("boxId",box.toString())),item).get("occupied"));
        } finally {
            db.update("UPDATE iam.department_manager SET user_id=? WHERE id=?",people.get("managerA"),duty);
        }
    }

    /** 从各真实列表中查找本轮记录，缺少对应待办或条目时直接失败。 */
    private Map<String,Object> rowWithId(Map<String,Object> page,UUID id) {
        return ((List<?>)page.get("items")).stream().map(CatalogTemplates::map)
            .filter(item->id.equals(item.get("id"))).findFirst().orElseThrow(()->new AssertionError("列表缺少 "+id));
    }
    /** 反复运行的隔离库保留旧待办，按实际分页定位本次记录。 */
    private Map<String,Object> handlingTask(Actor handler,String title,UUID id) {
        for(int page=1;;page++){
            var result=portalWork.tasks(handler,Map.of("category","HANDLING","keyword",title,"pageSize","100","page",String.valueOf(page)));
            var found=((List<?>)result.get("items")).stream().map(CatalogTemplates::map)
                .filter(item->id.equals(item.get("id"))).findFirst();
            if(found.isPresent())return found.get();
            if(page*100>=((Number)result.get("total")).intValue())throw new AssertionError("待办缺少 "+id);
        }
    }

    /** 纸质借阅与电子借阅共用身份摘要，审批查看不会提前交付或创建实物占用。 */
    @Test @Order(0) @SuppressWarnings("unchecked") void paperApprovalShowsApplicantIdentityWithoutDeliveringOriginal() throws Exception {
        UUID box=paperBox(b,"PAPER"),record=paperRecord(box,1),batch=submitPaper(List.of(physical(record)),3);
        var step=db.required("SELECT s.* FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=? AND s.state='PENDING'",batch);UUID id=(UUID)step.get("id");
        var detail=workflow.step(actors.get(step.get("assignee_id")),id);var person=(Map<String,Object>)detail.get("applicant");
        assertEquals(Set.of("id","name","employeeNo","departmentId","departmentName","submittedAt"),person.keySet());
        assertEquals(applicant.userId(),person.get("id"));assertEquals("验收applicant",person.get("name"));assertEquals("AR-"+applicant.userId(),person.get("employeeNo"));assertEquals(a,person.get("departmentId"));assertEquals("合成部门A",person.get("departmentName"));
        assertEquals(db.required("SELECT submitted_at FROM archive.borrow_batch WHERE id=?",batch).get("submitted_at"),person.get("submittedAt"));assertEquals(person,workflow.step(applicant,id).get("applicant"));
        assertEquals(403,assertThrows(ApiFault.class,()->workflow.step(actors.get(people.get("outsider")),id)).status);
        assertEquals(0,db.count("SELECT count(*) FROM workflow.decision WHERE step_id=?",id));assertEquals(0,db.count("SELECT count(*) FROM archive.paper_loan_scope WHERE request_id=?",request(batch)));
    }
    /** IT 纸质初借、实际交付和续借都只需要另一位档案超管批准。 */
    @Test @Order(1) void itSharedPaperBorrowAndRenewalUseOnlyArchiveAdmin() throws Exception {
        UUID item = physical(paperRecord(paperBox(b, "PAPER"), 1));
        UUID batch = (UUID) borrow.submit(plain(admin, "SUBMIT_PAPER_BORROW"), Sql.map(
            "form", "PAPER", "actions", List.of("BORROW_PHYSICAL"), "purpose", "IT 实物借阅验收",
            "period", Sql.map("mode", "NATURAL_DAYS", "amount", 10),
            "items", List.of(Sql.map("physicalItemId", item.toString())))).get("id");
        UUID request = request(legacyPaper(batch,admin,false));
        var step = db.required("SELECT s.id,s.assignee_id,s.duty_code FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.id=?", request);
        assertEquals("ARCHIVE_ADMIN", step.get("duty_code"));
        assertEquals(supervisor.userId(), step.get("assignee_id"));
        assertEquals(1, db.count("SELECT count(*) FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.id=?", request));
        UUID stepId = (UUID) step.get("id");
        assertEquals(403, assertThrows(ApiFault.class, () -> workflow.decide(
            plain(admin, "DECIDE_STEP"), stepId, Sql.map("result", "APPROVE"))).status);
        approveBatch(batch);
        var delivered = deliver(request, item);
        assertEquals("DELIVERED", delivered.get("state"));
        UUID loan = loan(delivered);
        Object oldDeadline = db.required("SELECT due_at FROM archive.physical_loan WHERE id=?", loan).get("due_at");
        UUID renewal = (UUID) paper.renew(plain(admin, "RENEW_PHYSICAL"), loan, Sql.map(
            "purpose", "IT 续借验收", "reason", "继续使用原件",
            "period", Sql.map("mode", "NATURAL_DAYS", "amount", 2))).get("id");
        approveBatch(renewal);
        assertEquals(List.of("ARCHIVE_ADMIN"), db.rows("SELECT s.duty_code FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=?", renewal).stream().map(row -> row.get("duty_code")).toList());
        assertTrue(Instant.parse(db.required("SELECT due_at FROM archive.physical_loan WHERE id=?", loan).get("due_at").toString()).isAfter(Instant.parse(oldDeadline.toString())));
    }

    @Test @Order(1) void paperCatalogNeedsNoPdfAndSafeTitleDoesNotGrantElectronicAccess() throws Exception {
        UUID box=paperBox(b,"PAPER"),record=paperRecord(box,1);assertNull(authority.record(record).get("current_pdf_version_id"));var listed=(List<Map<String,Object>>)catalog.records(archivistB,Map.of("boxId",box.toString())).get("items");assertNotNull(((Map<?,?>)listed.getFirst().get("record")).get("qualityCheckId"));assertEquals("TITLE_ONLY",authority.detail(applicant,record).get("representation"));
        var result=(List<Map<String,Object>>)physicalItems.list(applicant,Map.of("recordId",record.toString())).get("items");assertEquals(1,result.size());assertFalse(result.getFirst().containsKey("fileNo"));assertNull(result.getFirst().get("location"));String barcode=(String)db.required("SELECT barcode FROM archive.physical_item WHERE record_id=?",record).get("barcode");assertEquals(0L,physicalItems.list(applicant,Map.of("recordId",record.toString(),"keyword",barcode)).get("total"));assertThrows(ApiFault.class,()->reading.create(applicant,Sql.map("recordRevisionId",authority.record(record).get("current_revision_id").toString(),"pdfVersionId",null),UUID.randomUUID(),UUID.randomUUID()));assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant WHERE record_id=?",record));
    }
    @Test @Order(2) void approvedPaperDoesNotOccupyUntilActualDeliveryAndDuplicateIsIdempotent() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),3),request=request(batch);assertThrows(ApiFault.class,()->deliver(request,item));approveBatch(batch);assertEquals(0,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=?",item));var ctx=context(archivistB,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null);var body=deliveryBody(item);var first=loans.deliver(ctx,request,body);assertEquals(first.get("id"),loans.deliver(ctx,request,body).get("id"));assertEquals(1,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=?",item));
        assertThrows(ApiFault.class,()->loans.deliver(context(archivistA,"BORROW_REQUEST",request,"DELIVER_PHYSICAL",null),request,body));assertThrows(ApiFault.class,()->deliver(request,item));var changed=new LinkedHashMap<>(body);changed.put("condition","另一套交接描述");assertEquals(409,assertThrows(ApiFault.class,()->loans.deliver(ctx,request,changed)).status);
    }
    @Test @Order(3) void concurrentWholeBoxAndMemberDeliveryOnlyOneCanOccupy() throws Exception {
        UUID box=paperBox(b,"PAPER"),record=paperRecord(box,1);UUID member=physical(record),whole=(UUID)db.required("SELECT id FROM archive.physical_item WHERE box_id=? AND object_type='BOX'",box).get("id");UUID one=submitPaper(List.of(whole),3),two=submitPaper(List.of(member),3);approveBatch(one);approveBatch(two);UUID r1=request(one),r2=request(two);var c1=context(archivistB,"BORROW_REQUEST",r1,"DELIVER_PHYSICAL",null);var c2=context(archivistB,"BORROW_REQUEST",r2,"DELIVER_PHYSICAL",null);var b1=deliveryBody(whole);var b2=deliveryBody(member);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var f1=pool.submit(()->{start.await();try{loans.deliver(c1,r1,b1);return true;}catch(ApiFault failure){assertEquals(409,failure.status);return false;}});var f2=pool.submit(()->{start.await();try{loans.deliver(c2,r2,b2);return true;}catch(ApiFault failure){assertEquals(409,failure.status);return false;}});start.countDown();assertNotEquals(f1.get(),f2.get());}assertEquals(1,db.count("SELECT count(*) FROM archive.physical_loan WHERE box_id=? AND state='IN_USE'",box));
    }
    @Test @Order(4) void returnRequestAndExceptionKeepOccupancyUntilSupervisorActuallyConfirms() throws Exception {
        UUID box=paperBox(b,"PAPER"),item=physical(paperRecord(box,1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item));UUID returned=returnRequest(loan,0);assertEquals("IN_USE",db.required("SELECT state FROM archive.physical_loan WHERE id=?",loan).get("state"));assertThrows(ApiFault.class,()->loans.review(context(archivistB,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,reviewBody(returned,"CONFIRM",1)));
        var exceptional=context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan));assertEquals(false,loans.review(exceptional,loan,reviewBody(returned,"EXCEPTION",0)).get("occupancyReleased"));assertEquals("IN_USE",db.required("SELECT state FROM archive.physical_loan WHERE id=?",loan).get("state"));
        UUID retry=returnRequest(loan,1);var reviewed=context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan));var body=reviewBody(retry,"CONFIRM",1);assertEquals(true,loans.review(reviewed,loan,body).get("occupancyReleased"));assertEquals(true,loans.review(reviewed,loan,body).get("occupancyReleased"));assertEquals("CLOSED",db.required("SELECT state FROM archive.borrow_batch WHERE id=?",batch).get("state"));
        var detail=loans.detail(applicant,loan);var fact=(Map<?,?>)detail.get("loan");var rounds=(List<?>)detail.get("returns");var latest=(Map<?,?>)rounds.getFirst();
        assertEquals("可申请原件1",fact.get("label"));assertEquals("RETURNED",fact.get("state"));assertEquals(false,fact.get("overdue"));assertEquals("CONFIRMED",latest.get("state"));assertEquals("验收super",latest.get("reviewedByName"));assertEquals(1,latest.get("receivedQuantity"));assertEquals("管理员实收核对记录",latest.get("reviewCondition"));assertEquals(fact.get("returnedAt"),latest.get("actualReceivedAt"));assertEquals("EXCEPTION",((Map<?,?>)rounds.getLast()).get("state"));
    }
    @Test @Order(5) void loanBlocksMemberChangesAndLocationsAndOtherPeopleCannotReturn() throws Exception {
        UUID box=paperBox(b,"PAPER"),item=physical(paperRecord(box,1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item));assertThrows(ApiFault.class,()->paperRecord(box,2));var move=context(archivistB,"PHYSICAL_ITEM",item,"MOVE_PHYSICAL",1);assertThrows(ApiFault.class,()->physicalItems.location(move,item,Sql.map("location","另一库位","reason","不应移动借出原件")));Actor other=actors.get(people.get("second"));assertThrows(ApiFault.class,()->loans.detail(other,loan));var bad=new Commands.Context(other,UUID.randomUUID(),UUID.randomUUID(),"TEST FOREIGN RETURN",null,version(loan));assertThrows(ApiFault.class,()->loans.requestReturn(bad,loan,Sql.map("actualQuantity",1,"condition","他人归还")));
    }
    @Test @Order(6) void overSevenDaysNeedsRealSupervisingLeaderAndRenewalRetainsOneOccupancy() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),10);assertEquals(1,db.count("SELECT count(*) FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE q.batch_id=? AND s.duty_code='SUPERVISING_LEADER'",batch));approveBatch(batch);UUID loan=loan(deliver(request(batch),item));Object previous=db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at");var body=Sql.map("purpose","继续使用原件","reason","合成续借","period",Sql.map("mode","NATURAL_DAYS","amount",2));UUID renewal=(UUID)paper.renew(plain(applicant,"RENEW_PHYSICAL"),loan,body).get("id");assertEquals(previous,db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at"));approveBatch(renewal);assertTrue(Instant.parse(db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at").toString()).isAfter(Instant.parse(previous.toString())));assertEquals(1,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=? AND state='IN_USE'",item));
    }
    @Test @Order(7) void deliveryCannotUseChangedBoxMembersOrFutureTime() throws Exception {
        UUID box=paperBox(b,"PAPER"),one=paperRecord(box,1),whole=(UUID)db.required("SELECT id FROM archive.physical_item WHERE box_id=? AND object_type='BOX'",box).get("id");UUID batch=submitPaper(List.of(whole),3);approveBatch(batch);paperRecord(box,2);assertThrows(ApiFault.class,()->deliver(request(batch),whole));
        UUID item=physical(one),next=submitPaper(List.of(item),3);approveBatch(next);var future=deliveryBody(item);future.put("actualDeliveryAt",testTime.now().plusSeconds(60).toString());assertThrows(ApiFault.class,()->loans.deliver(context(archivistB,"BORROW_REQUEST",request(next),"DELIVER_PHYSICAL",null),request(next),future));
    }
    @Test @Order(8) void deliveryTimeCanAddLeaderWithoutOverwritingOriginalApproval() throws Exception {
        // 固定相对日期而不依赖运行当天星期：第6至10日为公司假期。
        calendar.useDelayedDeliveryCalendar(supervisor.userId());
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1));UUID batch=(UUID)borrow.submit(plain(applicant,"SUBMIT_PAPER_BORROW"),Sql.map("form","PAPER","actions",List.of("BORROW_PHYSICAL"),"purpose","跨实际日期交付","period",Sql.map("mode","WORKDAYS","amount",5),"items",List.of(Sql.map("physicalItemId",item.toString())))).get("id");legacyPaper(batch,applicant,false);approveBatch(batch);UUID q=request(batch);Object original=db.required("SELECT route_snapshot_id FROM archive.borrow_request WHERE id=?",q).get("route_snapshot_id");testTime.offset=4*86400;
        var ctx=context(archivistB,"BORROW_REQUEST",q,"DELIVER_PHYSICAL",null);var body=deliveryBody(item);var waiting=loans.deliver(ctx,q,body);assertEquals("WAITING_APPROVAL",waiting.get("state"));assertEquals(0,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=?",item));assertEquals(waiting.get("id"),loans.deliver(ctx,q,body).get("id"));assertEquals(1,db.count("SELECT count(*) FROM workflow.route_snapshot WHERE parent_snapshot_id=?",original));approveBatch(batch);body.put("actualDeliveryAt",testTime.now().toString());assertEquals("DELIVERED",loans.deliver(ctx,q,body).get("state"));assertEquals(1,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=?",item));
    }
    @Test @Order(9) void overdueNoticesAreDeduplicatedAndNeverReleaseOccupancy() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item));db.update("UPDATE archive.physical_loan SET due_at=CURRENT_TIMESTAMP-interval '1 minute',actual_delivery_at=CURRENT_TIMESTAMP-interval '2 days' WHERE id=?",loan);loans.tick();loans.tick();assertEquals(1,db.count("SELECT count(*) FROM archive.physical_notice WHERE loan_id=? AND user_id=? AND kind='OVERDUE'",loan,applicant.userId()));assertEquals("IN_USE",db.required("SELECT state FROM archive.physical_loan WHERE id=?",loan).get("state"));
    }
    /** 续借驳回不延长原借出；同操作重放只返回原申请，异体重放与并行续借均被拒绝。 */
    @Test @Order(10) void rejectedRenewalAndDuplicateSubmissionKeepOriginalDeadline() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item));Object due=db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at");
        var body=Sql.map("purpose","继续核对原件","reason","合成续借驳回","period",Sql.map("mode","NATURAL_DAYS","amount",2));var ctx=plain(applicant,"RENEW_PHYSICAL");UUID renewal=(UUID)paper.renew(ctx,loan,body).get("id");assertEquals(renewal,paper.renew(ctx,loan,body).get("id"));assertThrows(ApiFault.class,()->paper.renew(plain(applicant,"RENEW_PHYSICAL"),loan,body));
        var changed=new LinkedHashMap<>(body);changed.put("reason","不同的续借原因");assertEquals(409,assertThrows(ApiFault.class,()->paper.renew(ctx,loan,changed)).status);
        var step=db.required("SELECT s.* FROM workflow.step s JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id JOIN workflow.instance w ON w.current_step_id=s.id WHERE q.batch_id=?",renewal);UUID id=(UUID)step.get("id");workflow.decide(context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",id,"DECIDE_STEP",1),id,Sql.map("result","REJECT","comment","本次不予续借"));assertEquals(due,db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at"));assertEquals("IN_USE",db.required("SELECT state FROM archive.physical_loan WHERE id=?",loan).get("state"));
    }
    /** 库外凭证无法刷盘时整个交付事务回滚，恢复凭证目录后原操作可以重试。 */
    @Test @Order(11) void custodyReceiptFailureRollsBackDelivery() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID q=request(batch);var ctx=context(archivistB,"BORROW_REQUEST",q,"DELIVER_PHYSICAL",null);var body=deliveryBody(item);
        Path marker=Path.of(System.getenv("IMS_PLATFORM_READING_AUDIT")).resolve("instance");String original=Files.readString(marker);try{Files.writeString(marker,UUID.randomUUID().toString());assertEquals(503,assertThrows(ApiFault.class,()->loans.deliver(ctx,q,body)).status);assertEquals(0,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=?",item));assertEquals(0,db.count("SELECT count(*) FROM archive.physical_delivery WHERE request_id=?",q));}finally{Files.writeString(marker,original);}assertEquals("DELIVERED",loans.deliver(ctx,q,body).get("state"));
    }
    /** 归还申请重复提交和不完整实收核对不能提前释放占用。 */
    @Test @Order(12) void incompleteReturnConfirmationCannotReleaseOccupancy() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item)),returned=returnRequest(loan,1);assertThrows(ApiFault.class,()->returnRequest(loan,1));var body=reviewBody(returned,"CONFIRM",1);body.put("allAttachmentsConfirmed",false);assertThrows(ApiFault.class,()->loans.review(context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,body));assertEquals("IN_USE",db.required("SELECT state FROM archive.physical_loan WHERE id=?",loan).get("state"));
    }
    /** 分管领导更替使原任职失效，超级管理员显式改派到新有效任职后才可继续。 */
    @Test @Order(13) void supervisingLeaderChangeRequiresExplicitReassignment() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),10);UUID flow=(UUID)db.required("SELECT workflow_instance_id FROM archive.borrow_request WHERE batch_id=?",batch).get("workflow_instance_id");Map<String,Object> step;
        while(true){step=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);if("SUPERVISING_LEADER".equals(step.get("duty_code")))break;UUID id=(UUID)step.get("id");workflow.decide(context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",id,"DECIDE_STEP",1),id,Sql.map("result","APPROVE","comment","合成前序同意"));}
        UUID id=(UUID)step.get("id"),old=people.get("leaderB"),replacement=people.get("outsider"),assignment=(UUID)step.get("assignment_ref");try{db.update("UPDATE iam.department_manager SET valid_until=CURRENT_TIMESTAMP WHERE id=?",assignment);db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'SUPERVISING_LEADER',CURRENT_TIMESTAMP,'合成职责交接',?)",UUID.randomUUID(),b,replacement,admin.userId());assertEquals(403,assertThrows(ApiFault.class,()->workflow.decide(context(actors.get(old),"WORKFLOW_STEP",id,"DECIDE_STEP",1),id,Sql.map("result","APPROVE","comment","旧任职不能批准"))).status);workflow.reassign(context(supervisor,"WORKFLOW_STEP",id,"REASSIGN_STEP",1),id,Sql.map("assigneeId",replacement.toString(),"reason","交接至新分管领导","approvalReference","TEST-PHYSICAL-LEADER"));approveBatch(batch);assertEquals(1,db.count("SELECT count(*) FROM workflow.assignment_event WHERE step_id=?",id));}finally{db.update("UPDATE iam.department_manager SET valid_until=CURRENT_TIMESTAMP WHERE department_id=? AND duty_type='SUPERVISING_LEADER' AND user_id=? AND valid_until IS NULL",b,replacement);db.update("UPDATE iam.department_manager SET valid_until=NULL WHERE id=?",assignment);}
    }
    /** 混合载体仍要求完整电子正文，不能借纯纸质免PDF分支绕过检测。 */
    @Test @Order(14) void mixedRecordCannotPublishWithoutPdf() throws Exception {
        UUID box=paperBox(b,"MIXED"),record=(UUID)catalog.record(context(archivistB,"ARCHIVE_BOX",box,"CREATE_RECORD",null),Sql.map("boxId",box.toString(),"itemNo",1,"categoryCode","ADMIN","carrierType","MIXED","title","混合载体原件","keywords",List.of("合成混合载体"),"safeTitle","可申请混合资料","location","合成库房B-01","ownerId",people.get("managerB").toString(),"archiveDate",LocalDate.now(WorkCalendarService.ZONE).toString(),"templateVersionId",templateRecord.toString())).get("id");assertThrows(ApiFault.class,()->review(archivistB,"RECORD",record,null));assertNull(authority.record(record).get("current_revision_id"));
    }
    /** 续借在审批过程中跨过累计七日也进入同流程补批，补批前不改期限。 */
    @Test @Order(15) void delayedRenewalAddsLeaderAndKeepsDeadlineUntilSupplementApproved() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item));Object due=db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at");UUID renewal=(UUID)paper.renew(plain(applicant,"RENEW_PHYSICAL"),loan,Sql.map("purpose","跨日续借核对","reason","验证延迟批准","period",Sql.map("mode","NATURAL_DAYS","amount",2))).get("id"),flow=(UUID)db.required("SELECT workflow_instance_id FROM archive.borrow_request WHERE batch_id=?",renewal).get("workflow_instance_id");testTime.offset=8*86400;
        for(int count=0;count<10;count++){var step=db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);if("SUPERVISING_LEADER".equals(step.get("duty_code")))break;UUID id=(UUID)step.get("id");workflow.decide(context(actors.get(step.get("assignee_id")),"WORKFLOW_STEP",id,"DECIDE_STEP",1),id,Sql.map("result","APPROVE","comment","合成延迟批准"));}
        assertEquals(due,db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at"));assertEquals(1,db.count("SELECT count(*) FROM workflow.route_snapshot WHERE instance_id=? AND parent_snapshot_id IS NOT NULL",flow));approveBatch(renewal);assertNotEquals(due,db.required("SELECT due_at FROM archive.physical_loan WHERE id=?",loan).get("due_at"));assertEquals(1,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=?",item));
    }
    /** 保护事实失效或借阅资格被撤销时，历史批准不能建立实际借出。 */
    @Test @Order(16) void staleProtectionAndRevokedBorrowerQualificationBlockDelivery() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID request=request(batch),denial=UUID.randomUUID();evidence(true);try{assertEquals(503,assertThrows(ApiFault.class,()->deliver(request,item)).status);}finally{evidence(false);}
        try{db.update("INSERT INTO iam.permission_assignment(id,user_id,permission_id,scope_type,effect,valid_from,granted_by) SELECT ?,?,id,'COMPANY','DENY',CURRENT_TIMESTAMP,? FROM iam.permission WHERE code='ARCHIVE.PHYSICAL_BORROW'",denial,applicant.userId(),admin.userId());assertThrows(ApiFault.class,()->deliver(request,item));assertEquals(0,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=?",item));}finally{db.update("UPDATE iam.permission_assignment SET state='REVOKED',valid_until=CURRENT_TIMESTAMP WHERE id=?",denial);}assertEquals("DELIVERED",deliver(request,item).get("state"));
    }
    /** 实际归还早于续借批准时，原申请和在途续借一并结束，不补造任何决定。 */
    @Test @Order(17) void actualReturnClosesPendingRenewalWithoutApprovingIt() throws Exception {
        UUID item=physical(paperRecord(paperBox(b,"PAPER"),1)),batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item)),renewal=(UUID)paper.renew(plain(applicant,"RENEW_PHYSICAL"),loan,Sql.map("purpose","待审续借","reason","验证先归还","period",Sql.map("mode","NATURAL_DAYS","amount",2))).get("id");UUID flow=(UUID)db.required("SELECT workflow_instance_id FROM archive.borrow_request WHERE batch_id=?",renewal).get("workflow_instance_id"),returned=returnRequest(loan,1);loans.review(context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,reviewBody(returned,"CONFIRM",1));assertEquals("CLOSED",db.required("SELECT state FROM archive.borrow_batch WHERE id=?",renewal).get("state"));assertEquals("CLOSED",db.required("SELECT state FROM workflow.instance WHERE id=?",flow).get("state"));assertEquals(0,db.count("SELECT count(*) FROM workflow.decision d JOIN workflow.step s ON s.id=d.step_id WHERE s.instance_id=?",flow));assertEquals(0,db.count("SELECT count(*) FROM workflow.step WHERE instance_id=? AND state IN ('PENDING','WAITING')",flow));
    }
    /** 桌面验收专用账号和对象写入受限文件，报告中不包含密码。 */
    /** 超管本人完成归档检查才直接发布，登记人与他人草稿及检索权限分开验证。 */
    @Test @Order(96) void archiveSuperPublishesOwnDraftAndSampleTitleDoesNotGrantReading() throws Exception {
        LocalDate today=LocalDate.now(WorkCalendarService.ZONE);
        var fields=Sql.map("departmentId",a.toString(),"title","合成直接归档盒","safeTitle","合成直接归档盒",
            "classification","GENERAL","retentionCode","10","archiveDate",today.toString(),"physicalFileCount",0,
            "keywords",List.of("合成"),"templateVersionId",templateBox.toString(),"categoryCode","ADMIN",
            "ownerId",people.get("managerA").toString(),"numberMonth",today.withDayOfMonth(1).toString(),"carrierType","ELECTRONIC");
        UUID box=(UUID)catalog.box(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),fields).get("id");
        var ref=objectRef("BOX",box,null);
        var command=context(supervisor,"ARCHIVE_BOX",box,"SUBMIT_REGISTRATION",null);
        assertThrows(ApiFault.class,()->catalog.submit(command,Sql.map("objectRefs",List.of(ref),"qualityEvidenceRef",UUID.randomUUID().toString())));
        assertEquals("DRAFT",db.required("SELECT state FROM archive.box WHERE id=?",box).get("state"));
        UUID proof=(UUID)catalog.quality(context(supervisor,"ARCHIVE_BOX",box,"CONFIRM_QUALITY",null),Sql.map("objectType","BOX",
            "objectId",box.toString(),"candidateRevisionId",ref.get("candidateRevisionId"),"candidatePdfVersionId",null,
            "checkedPageCount",0,"confirmed",true,"notes","合成盒已核对")).get("id");
        var body=Sql.map("objectRefs",List.of(ref),"qualityEvidenceRef",proof.toString());
        var result=catalog.submit(command,body);
        assertEquals("PUBLISHED",result.get("state"));
        assertNull(result.get("workflowInstanceId"));
        assertEquals(result,catalog.submit(command,body));
        assertEquals(0,db.count("SELECT count(*) FROM workflow.instance WHERE business_type='REGISTRATION' AND business_id=?",box));

        UUID file=(UUID)catalog.record(context(supervisor,"ARCHIVE_BOX",box,"CREATE_RECORD",null),Sql.map("boxId",box.toString(),
            "itemNo",1,"categoryCode","ADMIN","carrierType","ELECTRONIC","title","合成限定检索样例",
            "safeTitle","合成限定检索样例","keywords",List.of("合成"),"ownerId",people.get("managerA").toString(),
            "archiveDate",today.toString(),"templateVersionId",templateRecord.toString())).get("id");
        UUID pdf=upload(supervisor,file);
        assertEquals(file,review(supervisor,"RECORD",file,pdf));
        assertEquals("PUBLISHED",authority.record(file).get("state"));
        assertEquals(0,db.count("SELECT count(*) FROM workflow.instance WHERE business_type='REGISTRATION' AND business_id=?",file));
        UUID revision=(UUID)authority.record(file).get("current_revision_id");
        db.update("INSERT INTO archive.sample_search_access(record_id,revision_id,user_id,valid_until) VALUES(?,?,?,CURRENT_TIMESTAMP+interval '1 day')",file,revision,applicant.userId());
        assertEquals("TITLE_ONLY",authority.detail(applicant,file).get("representation"));
        assertEquals(404,assertThrows(ApiFault.class,()->authority.detail(actors.get(people.get("second")),file)).status);
        assertEquals(403,assertThrows(ApiFault.class,()->authority.source(applicant,authority.record(file),null,null)).status);
        assertEquals(true,borrowDirectory.options(applicant,"RECORD",file).get("electronic"));
        assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant WHERE record_id=?",file));

        fields.put("title","合成他人登记草稿");
        UUID other=(UUID)catalog.box(context(archivistA,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),fields).get("id");
        UUID flow=review(supervisor,"BOX",other,null);
        assertEquals("PENDING",db.required("SELECT state FROM workflow.instance WHERE id=?",flow).get("state"));
        assertEquals("PENDING_REVIEW",db.required("SELECT state FROM archive.box WHERE id=?",other).get("state"));
    }
    @Test @Order(97) void archiveDirectoryMixedBorrowRunsBothLifecycles() throws Exception {
        UUID box=paperBox(b,"MIXED");
        String safeTitle="混合借阅验收"+box;
        UUID record=(UUID)catalog.record(context(archivistB,"ARCHIVE_BOX",box,"CREATE_RECORD",null),Sql.map(
            "boxId",box.toString(),"itemNo",1,"categoryCode","ADMIN","carrierType","MIXED","title","内部混合题名",
            "safeTitle",safeTitle,"keywords",List.of("合成混合借阅"),"location","合成库房B-01","ownerId",people.get("managerB").toString(),
            "archiveDate",LocalDate.now(WorkCalendarService.ZONE).toString(),"templateVersionId",templateRecord.toString())).get("id");
        UUID pdf=upload(archivistB,record);
        approve(review(archivistB,"RECORD",record,pdf));
        assertEquals(0L,borrowDirectory.boxes(applicant,Map.of("keyword",safeTitle)).get("total"));
        assertEquals(404,assertThrows(ApiFault.class,()->borrowDirectory.options(applicant,"RECORD",record)).status);
        policy(record,false,List.of());
        var boxes=borrowDirectory.boxes(applicant,Map.of("keyword",safeTitle,"pageSize","1"));
        assertEquals(1L,boxes.get("total"));
        var visible=(Map<?,?>)((List<?>)boxes.get("items")).getFirst();
        assertEquals(box,visible.get("id"));
        assertEquals(safeTitle+"（所在档案盒）",visible.get("label"));
        assertNull(visible.get("boxNo"));
        var fileOptions=borrowDirectory.options(applicant,"RECORD",record);
        assertEquals(true,fileOptions.get("electronic"));
        assertEquals(physical(record),fileOptions.get("physicalItemId"));
        var boxOptions=borrowDirectory.options(applicant,"BOX",box);
        assertEquals(true,boxOptions.get("electronic"));
        UUID whole=(UUID)boxOptions.get("physicalItemId");
        assertNotNull(whole);

        var body=Sql.map("form","MIXED","actions",List.of("VIEW"),"purpose","电子与原件合成验收",
            "items",List.of(Sql.map("objectType","BOX","objectId",box.toString(),"actions",List.of("VIEW"))),
            "period",Sql.map("mode","NATURAL_DAYS","amount",3));
        var operation=plain(applicant,"MIXED BORROW");
        var result=borrow.submit(operation,body);
        UUID electronicBatch=(UUID)((Map<?,?>)result.get("electronicBatch")).get("id");
        UUID paperBatch=(UUID)((Map<?,?>)result.get("paperBatch")).get("id");
        var replay=borrow.submit(operation,body);
        assertEquals(electronicBatch,((Map<?,?>)replay.get("electronicBatch")).get("id"));
        assertEquals(paperBatch,((Map<?,?>)replay.get("paperBatch")).get("id"));
        var electronicOnlyBody=new LinkedHashMap<>(body);
        electronicOnlyBody.put("form","ELECTRONIC");
        assertEquals(409,assertThrows(ApiFault.class,()->borrow.submit(operation,electronicOnlyBody)).status);
        approveBatch(paperBatch);
        assertEquals(0,db.count("SELECT count(*) FROM archive.access_grant WHERE record_id=?",record));
        assertEquals(1,db.count("SELECT count(*) FROM archive.physical_loan WHERE physical_item_id=? AND actual_delivery_at IS NULL",whole));
        approveBatch(electronicBatch);
        assertTrue(reading.page(applicant,view(applicant,record),1,UUID.randomUUID()).length>0);
        UUID physicalRequest=request(paperBatch);
        var handoff=deliveryBody(whole);
        loans.deliver(context(archivistB,"BORROW_REQUEST",physicalRequest,"DELIVER_PHYSICAL",null),physicalRequest,handoff);
        loans.deliver(context(supervisor,"BORROW_REQUEST",physicalRequest,"DELIVER_PHYSICAL",null),physicalRequest,handoff);
        UUID loan=(UUID)db.required("SELECT id FROM archive.physical_loan WHERE request_id=?",physicalRequest).get("id");
        assertNull(borrowDirectory.options(applicant,"RECORD",record).get("physicalItemId"));
        assertNull(borrowDirectory.options(applicant,"BOX",box).get("physicalItemId"));
        UUID returned=returnRequest(loan,1);
        approve((UUID)db.required("SELECT workflow_instance_id FROM archive.physical_return WHERE id=?",returned).get("workflow_instance_id"));
        var receipt=reviewBody(returned,"CONFIRM",1);
        loans.review(context(archivistB,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),loan,receipt);
        assertEquals(true,loans.review(context(supervisor,"PHYSICAL_LOAN",loan,"REVIEW_PHYSICAL_RETURN",version(loan)),
            loan,receipt).get("occupancyReleased"));
        assertEquals(whole,borrowDirectory.options(applicant,"BOX",box).get("physicalItemId"));
        assertEquals("CLOSED",db.required("SELECT state FROM archive.borrow_batch WHERE id=?",paperBatch).get("state"));

        UUID electronicOnly=published("GENERAL",b);
        policy(electronicOnly,false,List.of());
        long before=db.count("SELECT count(*) FROM archive.borrow_batch WHERE applicant_id=?",applicant.userId());
        body.put("items",List.of(Sql.map("objectType","RECORD","objectId",electronicOnly.toString(),"actions",List.of("VIEW"))));
        assertEquals(409,assertThrows(ApiFault.class,()->borrow.submit(plain(applicant,"MIXED WITHOUT PAPER"),body)).status);
        assertEquals(before,db.count("SELECT count(*) FROM archive.borrow_batch WHERE applicant_id=?",applicant.userId()));
    }
    @Test @Order(98) void loanDetailShowsOriginalApplicationAndPendingReturnWithinScope() throws Exception {
        UUID box=paperBox(b,"PAPER");paperRecord(box,1);paperRecord(box,2);UUID item=(UUID)db.required("SELECT id FROM archive.physical_item WHERE box_id=? AND object_type='BOX'",box).get("id");
        UUID batch=submitPaper(List.of(item),3);approveBatch(batch);UUID loan=loan(deliver(request(batch),item));returnRequest(loan,1);
        var detail=loans.detail(supervisor,loan);var fact=(Map<?,?>)detail.get("loan");var round=(Map<?,?>)((List<?>)detail.get("returns")).getFirst();
        assertEquals("整盒原件",fact.get("label"));assertEquals(Set.of("可申请原件1","可申请原件2"),new HashSet<>((List<?>)fact.get("memberLabels")));
        assertEquals("验收applicant",fact.get("borrowerName"));assertEquals("AR-"+applicant.userId(),fact.get("borrowerEmployeeNo"));assertEquals("合成部门A",fact.get("borrowerDepartmentName"));assertEquals("合成实物借阅验收",fact.get("purpose"));assertEquals("验收archB",fact.get("deliveredByName"));assertEquals(1,fact.get("actualQuantity"));assertNotNull(fact.get("actualDeliveryAt"));assertFalse(fact.containsKey("location"));
        assertEquals("IN_USE",fact.get("state"));assertEquals("PENDING",round.get("state"));assertEquals("合成归还",round.get("note"));assertNull(round.get("actualReceivedAt"));
        assertEquals(fact.get("label"),((Map<?,?>)loans.detail(applicant,loan).get("loan")).get("label"));assertEquals(403,assertThrows(ApiFault.class,()->loans.detail(archivistA,loan)).status);
        var manage=(List<?>)loans.list(supervisor,Map.of("scope","MANAGE")).get("items");assertTrue(manage.stream().map(value->(Map<?,?>)value).anyMatch(value->loan.equals(value.get("id"))&&"验收applicant".equals(value.get("borrowerName"))&&Boolean.TRUE.equals(value.get("returnPending"))));assertEquals(0L,loans.list(supervisor,Map.of("scope","MINE")).get("total"));
        String deliveryDay=Instant.parse(fact.get("actualDeliveryAt").toString()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        var filters=Map.of("scope","MANAGE","keyword",fact.get("barcode").toString(),"borrower",fact.get("borrowerEmployeeNo").toString(),
                "state","IN_USE","deliveryFrom",deliveryDay,"deliveryTo",deliveryDay,"pageSize","1");
        var filtered=loans.list(supervisor,filters);
        assertEquals(1L,filtered.get("total"));
        assertEquals(loan,((Map<?,?>)((List<?>)filtered.get("items")).getFirst()).get("id"));
        assertEquals(0L,loans.list(archivistA,filters).get("total"));
        if(System.getenv("IMS_PHYSICAL_DETAIL_UI_FIXTURE")!=null){Path target=Path.of(System.getenv("IMS_PHYSICAL_DETAIL_UI_FIXTURE"));Files.writeString(target,Crypto.json(Sql.map("loanId",loan.toString(),"employeeNo","AR-"+supervisor.userId(),"password",password,"barcode",fact.get("barcode"))));PrivateFiles.restrict(target);}
    }
    /** 桌面验收专用待交付账号和对象写入受限文件。 */
    @Test @Order(99) void desktopFixture() throws Exception {
        UUID box=paperBox(b,"PAPER"),record=paperRecord(box,1);paperRecord(box,2);UUID item=physical(record),batch=submitPaper(List.of(item),3);approveBatch(batch);var fixture=Sql.map("boxId",box.toString(),"recordId",record.toString(),"physicalItemId",item.toString(),"batchId",batch.toString(),"requestId",request(batch).toString(),"password",password,"people",people.entrySet().stream().map(entry->Sql.map("key",entry.getKey(),"id",entry.getValue().toString(),"employeeNo","AR-"+entry.getValue())).toList());Path target=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("physical-fixture.private.json");Files.writeString(target,Crypto.json(fixture));PrivateFiles.restrict(target);
    }
}
