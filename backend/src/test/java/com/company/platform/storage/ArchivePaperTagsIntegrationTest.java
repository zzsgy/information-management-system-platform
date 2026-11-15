package com.company.platform.storage;
import com.company.platform.*;
import com.company.platform.archive.*;
import com.company.platform.workflow.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 纸质登记的真实事务验收，仅连接新建隔离库。 */
@EnabledIfEnvironmentVariable(named="IMS_PAPER_TAG_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
 properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchivePaperTagsIntegrationTest {
    @Autowired CatalogMaintenance maintenance;
    @Autowired WorkflowEngine workflow;
    @Autowired Sql db; @Autowired IdentityService identity; @Autowired ArchiveCatalogService catalog;
    private UUID a,b,templateBox,templateRecord;
    private String password;
    private final Map<String,UUID> people=new HashMap<>();
    private final Map<UUID,Actor> actors=new HashMap<>();
    private Actor admin,supervisor,applicant,archivistA,archivistB;
    @BeforeAll void setup() throws Exception {
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:(55446|55447|55448)/ims_platform_dev_[0-9]+"));password=Crypto.token();admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence(false);
        // 复跑仅结束本专用库上一轮合成超级管理员任职，历史批准和文件全部保留。
        db.update("UPDATE iam.user_role a SET state='ENDED',valid_until=CURRENT_TIMESTAMP FROM iam.user_account u WHERE a.user_id=u.id AND a.module='ARCHIVE' AND a.role_code='SUPER_ADMIN' AND a.state='ACTIVE' AND u.employee_no LIKE 'AR-%'");
        a=department("A");b=department("B");person("super",a,"SUPER_ADMIN");person("archA",a,"DEPARTMENT_ARCHIVIST");person("archB",b,"DEPARTMENT_ARCHIVIST");person("managerA",a,"STAFF");person("managerB",b,"STAFF");person("gm",a,"STAFF");person("applicant",a,"STAFF");person("second",a,"STAFF");person("outsider",b,"STAFF");
        for(var pair:Map.of(a,people.get("managerA"),b,people.get("managerB")).entrySet()) db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'合成验收',?)",UUID.randomUUID(),pair.getKey(),pair.getValue(),admin.userId());
        // 公司总经理职责与公盘角色分离，避免拿公盘管理员当档案批准人。
        db.update("UPDATE iam.company_duty SET valid_until=CURRENT_TIMESTAMP WHERE duty_code='GENERAL_MANAGER' AND valid_until IS NULL");db.update("INSERT INTO iam.company_duty(id,duty_code,user_id,valid_from,changed_by,change_reason) VALUES(?,'GENERAL_MANAGER',?,CURRENT_TIMESTAMP,?,'合成验收')",UUID.randomUUID(),people.get("gm"),admin.userId());
        refreshActors();
        templateBox=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","BOX_"+UUID.randomUUID(),"name","电子档案盒基础模板","categoryCode","ADMIN","objectType","BOX","fields",List.of(Sql.map("code","preservationType","name","保存类型","type","TEXT","critical",true)))).get("id");
        templateRecord=(UUID)catalog.template(context(supervisor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DIRECTORY_TEMPLATE",null),Sql.map("templateKey","RECORD_"+UUID.randomUUID(),"name","电子文件基础模板","categoryCode","ADMIN","objectType","RECORD","fields",List.of())).get("id");
    }
    private UUID department(String prefix) {UUID id=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",id,prefix+id.toString().substring(0,7).toUpperCase(Locale.ROOT),"合成部门"+prefix);return id;}
    private void person(String key,UUID department,String roleCode) {UUID id=UUID.randomUUID();people.put(key,id);String no="AR-"+id;db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,"验收"+key,department,no.toLowerCase(Locale.ROOT)+"@biolink.com",Crypto.password(password));UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='ARCHIVE' AND code=?",roleCode).get("id");db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,'ARCHIVE',?)",UUID.randomUUID(),id,role,roleCode.equals("DEPARTMENT_ARCHIVIST")?department:null,admin.userId(),roleCode);}
    private void refreshActors() {for(var person:people.entrySet()) actors.put(person.getValue(),login("AR-"+person.getValue(),password));supervisor=actors.get(people.get("super"));applicant=actors.get(people.get("applicant"));archivistA=actors.get(people.get("archA"));archivistB=actors.get(people.get("archB"));}
    private Actor login(String no,String secret) {return identity.authenticate((String)identity.login(Sql.map("employeeNo",no,"password",secret),UUID.randomUUID(),"archive-test").get("token"));}
    /** 凭据按本人、对象及精确动作签发；普通借阅无需高风险管理凭据。 */
    private Commands.Context context(Actor actor,String type,UUID target,String action,Integer version) {String secret=actor.userId().equals(admin.userId())?System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"):password;var proof=identity.reauthenticate(actor,Sql.map("password",secret,"purpose","MANAGE","objectIds",List.of(target.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",target.toString(),"actions",List.of(action)))),UUID.randomUUID());return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action+" "+target,(String)proof.get("verificationToken"),version);}
    private Commands.Context plain(Actor actor,String action) {return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action,null,null);}
    /** 只签发合成保护事实以验收消费者；这不是生产备份和主备事实。 */
    private void evidence(boolean expired) throws Exception {Path root=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")),key=root.resolve("protection.key");if(!Files.exists(key)){Files.writeString(key,Crypto.token(),StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(key);}Instant now=Instant.now().minusSeconds(expired?120:0);var state=Sql.map("instanceId",db.required("SELECT id FROM platform_meta.instance").get("id").toString(),"nodeId","archive-isolated-node","epoch",1,"issuedAt",now.toString(),"expiresAt",now.plusSeconds(60).toString(),"mode","NORMAL","oldPrimaryFenced",true,"replicaVerifiedAt",now.toString(),"backupId",UUID.randomUUID().toString(),"backupProtectedAt",now.toString(),"writableModules",List.of("ARCHIVE","DRIVE"));state.put("signature",Crypto.hmac(Files.readString(key),Crypto.json(state)));Files.writeString(root.resolve("protection.json"),Crypto.json(state));}
    /** 两种登记角色均受纸质标签规则约束；拒绝时实物和盒数据一起回滚。 */
    @Test void paperTagRegistrationRequiresBindingAndPersistsSnapshot() {
        LocalDate day=LocalDate.now(WorkCalendarService.ZONE);
        for(Actor actor:List.of(archivistB,supervisor))for(String carrier:List.of("PAPER","MIXED","ELECTRONIC")) {
            var body=Sql.map("departmentId",b.toString(),"title","纸质标签登记验收","classification","GENERAL","retentionCode","10","archiveDate",day.toString(),"keywords",List.of("标签验收"),"templateVersionId",templateBox.toString(),"categoryCode","ADMIN","ownerId",people.get("managerB").toString(),"numberMonth",day.withDayOfMonth(1).toString(),"carrierType",carrier,"location","验收柜A");
            if(!carrier.equals("ELECTRONIC")) {
                long boxes=db.count("SELECT count(*) FROM archive.box"),items=db.count("SELECT count(*) FROM archive.physical_item");
                assertThrows(ApiFault.class,()->catalog.box(context(actor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),body));
                assertEquals(boxes,db.count("SELECT count(*) FROM archive.box"));assertEquals(items,db.count("SELECT count(*) FROM archive.physical_item"));
            }
            var tags=carrier.equals("ELECTRONIC")?List.of():List.of("纸质原件","纸质复印件");body.put("extensionValues",Sql.map("paperTags",tags,"preservationType","历史填写"));
            var created=catalog.box(context(actor,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),body);
            assertEquals(tags,CatalogTemplates.map(created.get("extensionValues")).get("paperTags"));
            var snapshot=CatalogTemplates.map(db.required("SELECT metadata_snapshot FROM archive.box_revision WHERE box_id=?",created.get("id")).get("metadata_snapshot"));
            assertEquals(tags,CatalogTemplates.map(snapshot.get("extensionValues")).get("paperTags"));
            String label=carrier.equals("PAPER")?"纸质":carrier.equals("MIXED")?"纸质+电子":"电子";
            assertEquals(label,CatalogTemplates.map(created.get("extensionValues")).get("preservationType"));
            assertTrue(((List<?>)catalog.boxFilterOptions(actor).get("preservationTypes")).contains(label));
            assertTrue(((List<?>)catalog.boxes(actor,Map.of("preservationType",label)).get("items")).stream().anyMatch(item->((Map<?,?>)item).get("id").equals(created.get("id"))));
            if(!carrier.equals("ELECTRONIC")) {
                UUID id=(UUID)created.get("id");int version=((Number)created.get("rowVersion")).intValue();
                assertThrows(ApiFault.class,()->maintenance.updateDraft(context(actor,"ARCHIVE_BOX",id,"UPDATE_DRAFT",version),"BOX",id,Sql.map("reason","验证不能清空标签","changes",Sql.map("extensionValues",Sql.map("paperTags",List.of(),"preservationType",label)))));
                assertEquals(tags,CatalogTemplates.map(db.required("SELECT extension_values FROM archive.box WHERE id=?",id).get("extension_values")).get("paperTags"));
            }
        }
    }

    /** 当前审批人仍有权驳回失去经办权限的旧单；新单可以沿同一审批路线通过。 */
    @Test void staleCatalogCustodianBlocksOnlyApproval() throws Exception {
        evidence(false);
        LocalDate day=LocalDate.now(WorkCalendarService.ZONE);
        var box=catalog.box(context(archivistB,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),
            Sql.map("departmentId",b.toString(),"title","更正流程验收盒","classification","GENERAL","retentionCode","10",
                "archiveDate",day.toString(),"keywords",List.of("审批验收"),"templateVersionId",templateBox.toString(),
                "categoryCode","ADMIN","ownerId",people.get("managerB").toString(),"numberMonth",day.withDayOfMonth(1).toString(),"carrierType","ELECTRONIC"));
        UUID id=(UUID)box.get("id");
        Actor manager=actors.get(people.get("managerB"));
        String revision=box.get("candidateRevisionId").toString();
        var quality=catalog.quality(context(archivistB,"ARCHIVE_BOX",id,"CONFIRM_QUALITY",null),
            Sql.map("objectType","BOX","objectId",id.toString(),"candidateRevisionId",revision,
                "checkedPageCount",0,"confirmed",true,"notes","已确认盒目录"));
        UUID registration=(UUID)catalog.submit(context(archivistB,"ARCHIVE_BOX",id,"SUBMIT_REGISTRATION",null),
            Sql.map("objectRefs",List.of(Sql.map("objectType","BOX","objectId",id.toString(),"expectedVersion",box.get("rowVersion"),"candidateRevisionId",revision)),
                "qualityEvidenceRef",quality.get("id").toString())).get("id");
        decide(manager,activeStep(registration),"APPROVE");
        decide(supervisor,activeStep(registration),"APPROVE");

        UUID stale=submitTitleChange(id,"不应生效的旧更正");
        var step=activeStep(stale);
        UUID stepId=(UUID)step.get("id");
        db.update("UPDATE iam.user_role SET state='ENDED' WHERE user_id=? AND role_code='DEPARTMENT_ARCHIVIST'",archivistB.userId());
        try {
            var detail=CatalogTemplates.map(workflow.step(manager,stepId).get("business"));
            String reason=(String)detail.get("approvalBlockReason");
            assertTrue(reason.contains("经办人已无相关部门的档案维护权限"));
            assertEquals(reason,assertThrows(ApiFault.class,()->decide(manager,step,"APPROVE")).getMessage());
            assertEquals(0,db.count("SELECT count(*) FROM workflow.decision WHERE step_id=?",stepId));
            decide(manager,step,"REJECT");
            assertEquals("REJECTED",db.required("SELECT state FROM workflow.instance WHERE id=?",stale).get("state"));
            assertEquals("更正流程验收盒",db.required("SELECT title FROM archive.box WHERE id=?",id).get("title"));
        } finally {
            db.update("UPDATE iam.user_role SET state='ACTIVE' WHERE user_id=? AND role_code='DEPARTMENT_ARCHIVIST'",archivistB.userId());
        }
        UUID fresh=submitTitleChange(id,"授权有效的新更正");
        var freshStep=activeStep(fresh);
        assertNull(CatalogTemplates.map(workflow.step(manager,(UUID)freshStep.get("id")).get("business")).get("approvalBlockReason"));
        decide(manager,freshStep,"APPROVE");
        decide(supervisor,activeStep(fresh),"APPROVE");
        assertEquals("授权有效的新更正",db.required("SELECT title FROM archive.box WHERE id=?",id).get("title"));
    }

    /** 通过正式更正入口登记标题修改并送审。 */
    private UUID submitTitleChange(UUID box,String title) {
        int version=((Number)db.required("SELECT row_version FROM archive.box WHERE id=?",box).get("row_version")).intValue();
        var change=maintenance.create(context(archivistB,"ARCHIVE_BOX",box,"CREATE_CATALOG_CHANGE",version),"BOX",box,
            Sql.map("kind","CORRECTION","changes",Sql.map("title",title),"reason","验证目录更正权限"));
        return (UUID)maintenance.submit(context(archivistB,"ARCHIVE_REVISION",(UUID)change.get("id"),"SUBMIT_CATALOG_CHANGE",
            ((Number)change.get("rowVersion")).intValue()),(UUID)change.get("id"),Map.of()).get("workflowId");
    }

    /** 使用当前节点和版本，经本人再验证提交真实决定。 */
    private void decide(Actor actor,Map<String,Object> step,String result) {
        UUID id=(UUID)step.get("id");
        workflow.decide(context(actor,"WORKFLOW_STEP",id,"DECIDE_STEP",((Number)step.get("row_version")).intValue()),id,
            Sql.map("result",result,"comment","合成验收"));
    }

    private Map<String,Object> activeStep(UUID flow) {
        return db.required("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE w.id=?",flow);
    }
}
