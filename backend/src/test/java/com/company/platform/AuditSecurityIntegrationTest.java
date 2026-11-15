package com.company.platform;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实PostgreSQL验收：正式服务、事务、触发器和权限，不以模拟仓储复制安全判断。 */
@EnabledIfEnvironmentVariable(named="IMS_AUDIT_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.config.name=platform","server.ssl.enabled=false","platform.messaging.schedule-enabled=false","platform.notifications.schedule-enabled=false","platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false","platform.borrow-lifecycle.enabled=false"})
class AuditSecurityIntegrationTest {
    /** 在连接前限制专用端口与随机开发库名，不复用旧预览或正式库。 */
    @DynamicPropertySource static void guard(DynamicPropertyRegistry registry){String url=System.getenv("IMS_PLATFORM_DB_URL");if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:(55464|55476)/ims_platform_dev_[0-9]+"))throw new IllegalStateException("仅允许审计专项隔离库");}
    @Autowired com.company.platform.operations.AccountMessages messages;@Autowired Sql db;@Autowired Audit audit;@Autowired AuditSecurity security;@Autowired IdentityService identity;@Autowired Commands commands;@Autowired PlatformTransactionManager manager;
    private Actor admin,employee,clerk,other;private UUID department,otherDepartment;private final Map<UUID,String> passwords=new HashMap<>();
    @BeforeEach void setup(){admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));passwords.put(admin.userId(),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));department=department();otherDepartment=department();employee=employee(department);clerk=employee(department);other=employee(otherDepartment);assign(clerk.userId(),"ARCHIVE","DEPARTMENT_ARCHIVIST",department);clerk=identity.background(clerk.sessionId());}
    private UUID department(){UUID id=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",id,"A"+id.toString().substring(0,7).toUpperCase(Locale.ROOT),"审计隔离部门");return id;}
    private Actor employee(UUID dept){UUID id=UUID.randomUUID();String no="AUDIT-"+id,password=Crypto.token();passwords.put(id,password);db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,"审计验收员工",dept,no+"@biolink.com",Crypto.password(password));return login(no,password);}
    private void assign(UUID user,String module,String role,UUID dept){db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) SELECT ?,?,id,?,CURRENT_TIMESTAMP,?,?,? FROM iam.role WHERE module=? AND code=?",UUID.randomUUID(),user,dept,admin.userId(),module,role,module,role);}
    private Actor login(String no,String password){return identity.authenticate(identity.login(Sql.map("employeeNo",no,"password",password),UUID.randomUUID(),"audit-test-"+UUID.randomUUID()).get("token").toString());}
    private Commands.Context context(Actor actor,String type,UUID id,String action,Integer version){var proof=identity.reauthenticate(actor,Sql.map("password",passwords.get(actor.userId()),"purpose","MANAGE","objectIds",List.of(id.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",id.toString(),"actions",List.of(action)))),UUID.randomUUID());return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action+" "+id,proof.get("verificationToken").toString(),version);}
    private Map<String,String> filters(String module){return new HashMap<>(Map.of("module",module,"from",Instant.now().minusSeconds(3600).toString(),"to",Instant.now().plusSeconds(3600).toString(),"pageSize","100","includeTotal","true"));}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}
    private void status(int expected,org.junit.jupiter.api.function.Executable call){assertEquals(expected,assertThrows(ApiFault.class,call).status);}
    /** 固定领域测试夹具仅提供合法档案归属，待验证的范围判断由正式审计代码执行。 */
    private UUID box(UUID dept,UUID owner){UUID template=UUID.randomUUID(),id=UUID.randomUUID();db.update("INSERT INTO archive.directory_template(id,template_key,version_no,category_code,object_type,name,created_by) VALUES(?,?,1,'ADMIN','BOX','审计夹具',?)",template,template.toString(),admin.userId());db.update("INSERT INTO archive.box(id,box_no,department_id,title,classification,retention_code,archive_date,expiry_date,category_code,carrier_type,owner_id,number_month,keywords,template_version_id,created_by) VALUES(?,?,?,'不应进入审计的机密题名','GENERAL','YJ',CURRENT_DATE,NULL,'ADMIN','ELECTRONIC',?,date_trunc('month',CURRENT_DATE),'{验收}',?,?)",id,"TEST-"+id,dept,owner,template,admin.userId());return id;}
    private UUID folder(UUID dept){UUID id=UUID.randomUUID();db.update("INSERT INTO drive.folder(id,zone,department_id,folder_level,name,name_key) VALUES(?,'DEPARTMENT_INTERNAL',?,'TOP',?,?)",id,dept,"审计目录"+id,id.toString());return id;}
    private UUID query(Actor actor,Map<String,String> filters){return (UUID)security.query(actor,filters,UUID.randomUUID()).get("queryId");}
    private Map<String,Object> approve(Actor actor,UUID id,Commands.Context ctx){return commands.run(ctx,Map.of(),"AUDIT_EXPORT",id,"EXPORT_AUDIT",()->security.authorizeExport(actor,id),()->security.authorizeExport(actor,id),key->security.authorizeExport(actor,key));}

    /** 历史记录通过原账号取工号，空操作者和无法关联的操作者仍可区分。 */
    @Test void auditRowsResolveOperatorAccount() {
        UUID request=UUID.randomUUID(),missing=UUID.randomUUID();
        audit.event(employee.userId(),"PLATFORM","ACCOUNT_TEST",null,"EMPLOYEE_TEST","SUCCEEDED",request);
        audit.event(null,"PLATFORM","ACCOUNT_TEST",null,"SYSTEM_TEST","SUCCEEDED",request);
        audit.event(missing,"PLATFORM","ACCOUNT_TEST",null,"MISSING_TEST","SUCCEEDED",request);
        var filter=filters("PLATFORM");
        filter.put("requestId",request.toString());
        var rows=items(security.query(admin,filter,UUID.randomUUID()));
        assertEquals(3,rows.size());
        var employeeRow=rows.stream().filter(row->"EMPLOYEE_TEST".equals(row.get("action"))).findFirst().orElseThrow();
        assertEquals("AUDIT-"+employee.userId(),employeeRow.get("actorEmployeeNo"));
        var systemRow=rows.stream().filter(row->"SYSTEM_TEST".equals(row.get("action"))).findFirst().orElseThrow();
        assertNull(systemRow.get("actorId"));
        assertNull(systemRow.get("actorEmployeeNo"));
        var missingRow=rows.stream().filter(row->"MISSING_TEST".equals(row.get("action"))).findFirst().orElseThrow();
        assertEquals(missing,missingRow.get("actorId"));
        assertNull(missingRow.get("actorEmployeeNo"));

        security.interaction(employee,Sql.map("id",UUID.randomUUID().toString(),"requestId",request.toString(),
            "page","audit","action","SUBMIT","control","FORM"));
        filter.put("kind","INTERACTION");
        assertEquals("AUDIT-"+employee.userId(),items(security.query(admin,filter,UUID.randomUUID()))
            .getFirst().get("actorEmployeeNo"));
    }

    /** 已落库的模板路由可显示为具体操作，管理员可按操作者筛选。 */
    @Test void httpAuditLabelsDescribeObjectsFromStoredTemplates(){
        UUID request=UUID.randomUUID();
        audit.http(employee.userId(),"GET",200,"/api/v1/archive/boxes",request);
        audit.http(employee.userId(),"PATCH",200,"/api/v1/admin/users/{id}",request);
        audit.http(employee.userId(),"POST",200,"/api/v1/archive/physical-loans/{id}/return-reviews",request);
        audit.http(other.userId(),"GET",200,"/api/v1/portal/inbox",request);
        audit.http(employee.userId(),"GET",404,"UNMAPPED",request);

        var filter=filters("PLATFORM");
        filter.put("requestId",request.toString());
        filter.put("actorId",employee.userId().toString());
        var result=items(security.query(admin,filter,UUID.randomUUID()));
        assertEquals(4,result.size());
        assertEquals(Set.of("查询档案盒台账","编辑员工资料","复核纸质档案实收"),result.stream()
            .map(row->row.get("operationLabel")).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.stream().noneMatch(row->row.containsKey("authorizationSource")));
        filter.remove("actorId");
        assertEquals(5,items(security.query(admin,filter,UUID.randomUUID())).size());
    }

    /** 按姓名或工号匹配操作人，翻页、计数和 CSV 复用员工筛选。 */
    @Test void employeeKeywordFiltersAuditQueryAndExport(){
        db.update("UPDATE iam.user_account SET name=? WHERE id=?","审计筛选甲",employee.userId());
        UUID request=UUID.randomUUID();
        for(int i=0;i<2;i++)audit.event(employee.userId(),"PLATFORM","FILTER_TEST",null,"MATCHED","SUCCEEDED",request);
        audit.event(other.userId(),"PLATFORM","FILTER_TEST",null,"OTHER","SUCCEEDED",request);
        audit.event(null,"PLATFORM","FILTER_TEST",null,"SYSTEM","SUCCEEDED",request);
        var filter=filters("PLATFORM");
        filter.put("requestId",request.toString());
        filter.put("pageSize","1");
        for(String keyword:List.of("筛选甲",("AUDIT-"+employee.userId()).substring(0,17).toLowerCase(Locale.ROOT))){
            filter.put("employee",keyword);
            var first=security.query(admin,filter,UUID.randomUUID());
            UUID queryId=(UUID)first.get("queryId");
            assertEquals(2L,security.count(admin,queryId).get("total"));
            assertEquals(employee.userId(),items(first).getFirst().get("actorId"));
            var next=security.query(admin,Map.of("cursor",first.get("nextCursor").toString()),UUID.randomUUID());
            assertEquals(employee.userId(),items(next).getFirst().get("actorId"));
            assertEquals(false,next.get("hasNext"));
            approve(admin,queryId,context(admin,"AUDIT_EXPORT",queryId,"EXPORT_AUDIT",null));
            String csv=new String(security.export(admin,queryId,UUID.randomUUID()),StandardCharsets.UTF_8);
            assertEquals(3,csv.lines().count());
            assertTrue(csv.contains(employee.userId().toString()));
            assertFalse(csv.contains(other.userId().toString()));
        }
        filter.put("employee","%");
        assertTrue(items(security.query(admin,filter,UUID.randomUUID())).isEmpty());
    }

    /** 普通员工、公盘部门管理员、档案员及档案超级管理员均不能进入操作记录。 */
    @Test void onlyPlatformItCanReadOperationRecords() {
        assign(other.userId(),"ARCHIVE","SUPER_ADMIN",null);
        var driveManager=employee(department);
        assign(driveManager.userId(),"DRIVE","DEPARTMENT_MANAGER",department);
        for(String module:List.of("PLATFORM","DRIVE","ARCHIVE")){
            UUID request=UUID.randomUUID();
            audit.event(employee.userId(),module,"AUDIT_SCOPE",null,"SCOPE_TEST","SUCCEEDED",request);
            audit.event(null,module,"AUDIT_SCOPE",null,"SCOPE_TEST","SUCCEEDED",request);
            var filter=filters(module);
            filter.put("requestId",request.toString());
            var result=security.query(admin,filter,UUID.randomUUID());
            assertEquals(2L,result.get("total"));
            UUID queryId=(UUID)result.get("queryId");
            assertEquals(2L,security.count(admin,queryId).get("total"));
            for(Actor actor:List.of(employee,driveManager,clerk,other)){
                status(403,()->security.query(actor,filter,UUID.randomUUID()));
                status(403,()->security.query(actor,Map.of("cursor",result.get("cursor").toString()),UUID.randomUUID()));
                status(403,()->security.count(actor,queryId));
                status(403,()->security.authorizeExport(actor,queryId));
                status(403,()->security.export(actor,queryId,UUID.randomUUID()));
            }
        }
        var interactionFilter=filters("PLATFORM");
        interactionFilter.put("kind","INTERACTION");
        status(403,()->security.query(employee,interactionFilter,UUID.randomUUID()));
    }
    @Test void exportRequiresSeparatePermissionOwnerApprovalAndFrozenQuery(){UUID own=box(department,employee.userId());audit.event(admin.userId(),"ARCHIVE","ARCHIVE_BOX",own,"TEST","SUCCEEDED",UUID.randomUUID());UUID id=query(admin,filters("PLATFORM"));status(403,()->security.authorizeExport(employee,id));status(403,()->security.export(admin,id,UUID.randomUUID()));var ctx=context(admin,"AUDIT_EXPORT",id,"EXPORT_AUDIT",null);approve(admin,id,ctx);approve(admin,id,ctx);UUID late=UUID.randomUUID();audit.event(admin.userId(),"PLATFORM","LATE_TEST",late,"TEST","SUCCEEDED",UUID.randomUUID());String csv=new String(security.export(admin,id,UUID.randomUUID()),StandardCharsets.UTF_8);assertFalse(csv.contains(late.toString()));assertTrue(csv.startsWith("\ufeff记录编号"));assertFalse(csv.contains("password"));assertEquals(1,db.count("SELECT count(*) FROM ops.audit_event WHERE resource_id=? AND action='EXPORT_AUDIT' AND record_kind='BUSINESS_EVENT'",id));}
    @Test void exportRechecksRevocationAndExpiry(){UUID id=query(admin,filters("PLATFORM"));approve(admin,id,context(admin,"AUDIT_EXPORT",id,"EXPORT_AUDIT",null));db.update("UPDATE ops.audit_export SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",id);status(404,()->security.export(admin,id,UUID.randomUUID()));UUID current=query(admin,filters("PLATFORM"));approve(admin,current,context(admin,"AUDIT_EXPORT",current,"EXPORT_AUDIT",null));new TransactionTemplate(manager).executeWithoutResult(tx->identity.invalidate(admin.userId(),"AUDIT_TEST_REVOKE",UUID.randomUUID()));status(401,()->security.export(admin,current,UUID.randomUUID()));}
    @Test void interactionRejectsTextPayloadAndReplayDoesNotDuplicate(){UUID id=UUID.randomUUID(),request=UUID.randomUUID();var body=Sql.map("id",id.toString(),"requestId",request.toString(),"page","audit","action","SUBMIT","control","FORM");security.interaction(employee,body);security.interaction(employee,body);assertEquals(1,db.count("SELECT count(*) FROM ops.ui_interaction WHERE id=?",id));body.put("password","never-store-this");status(400,()->security.interaction(employee,body));var f=filters("PLATFORM");f.put("kind","INTERACTION");f.put("requestId",request.toString());assertEquals(1L,security.query(admin,f,UUID.randomUUID()).get("total"));}
    @Test void deniedEventsSurviveBusinessRollbackAndDeduplicateAlerts(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx->{audit.denied(employee.userId(),"PERMISSION_DENIED",UUID.randomUUID());throw new IllegalStateException("rollback");}));assertEquals(1,db.count("SELECT count(*) FROM ops.security_signal WHERE user_id=?",employee.userId()));for(int i=0;i<12;i++)audit.denied(employee.userId(),"PERMISSION_DENIED",UUID.randomUUID());assertEquals(1,db.count("SELECT count(*) FROM ops.security_alert WHERE user_id=? AND kind='ACCESS_DENIED'",employee.userId()));assertEquals(1,db.count("SELECT count(*) FROM ops.account_message WHERE user_id=? AND kind='ACCESS_DENIED'",employee.userId()));status(429,()->security.requireAvailable(employee.userId(),"ACCESS_DENIED"));security.requireAvailable(employee.userId(),"BULK_PRINT");}
    @Test void repeatedDownloadsCountDistinctFilesAndExpiryRestoresAction(){UUID file=UUID.randomUUID();for(int i=0;i<60;i++)audit.event(employee.userId(),"DRIVE","DOCUMENT",file,"DOWNLOAD_DELIVERED","SUCCEEDED",UUID.randomUUID());assertEquals(0,db.count("SELECT count(*) FROM ops.security_alert WHERE user_id=?",employee.userId()));for(int i=0;i<49;i++)audit.event(employee.userId(),"DRIVE","DOCUMENT",UUID.randomUUID(),"DOWNLOAD_DELIVERED","SUCCEEDED",UUID.randomUUID());status(429,()->security.requireAvailable(employee.userId(),"BULK_DOWNLOAD"));assertEquals(1,db.count("SELECT count(*) FROM ops.security_alert WHERE user_id=?",employee.userId()));db.update("UPDATE ops.security_limit SET blocked_until=CURRENT_TIMESTAMP-interval '1 second' WHERE user_id=?",employee.userId());security.requireAvailable(employee.userId(),"BULK_DOWNLOAD");}
    @Test void dispositionIsVersionedIdempotentAndAudited(){for(int i=0;i<10;i++)audit.denied(employee.userId(),"PERMISSION_DENIED",UUID.randomUUID());UUID id=(UUID)db.required("SELECT id FROM ops.security_alert WHERE user_id=?",employee.userId()).get("id");var body=Sql.map("state","RESOLVED","reason","隔离核对完成","release",true);var ctx=context(admin,"SECURITY_ALERT",id,"DISPOSE_SECURITY",1);for(int i=0;i<2;i++)commands.run(ctx,body,"SECURITY_ALERT",id,"DISPOSE_SECURITY",()->admin.require("PLATFORM.SECURITY_MANAGE"),()->security.dispose(ctx,id,body),key->Sql.map("id",key));security.requireAvailable(employee.userId(),"ACCESS_DENIED");assertEquals(1,db.count("SELECT count(*) FROM ops.security_disposition WHERE alert_id=?",id));status(403,()->security.overview(employee,Map.of()));}
    @Test void retentionCannotBeShortenedAndEvidenceCannotBeRewritten(){var before=db.required("SELECT * FROM ops.audit_retention");int years=((Number)before.get("business_years")).intValue(),version=((Number)before.get("row_version")).intValue();var body=Sql.map("interactionYears",before.get("interaction_years"),"businessYears",years+1);var ctx=context(admin,"AUDIT_RETENTION",ApiController.COLLECTION,"EXTEND_RETENTION",version);commands.run(ctx,body,"AUDIT_RETENTION",ApiController.COLLECTION,"EXTEND_RETENTION",()->admin.require("PLATFORM.SECURITY_MANAGE"),()->security.retention(ctx,body),key->Sql.map("id",key));assertThrows(Exception.class,()->db.update("UPDATE ops.audit_retention SET business_years=?",years));assertThrows(Exception.class,()->db.update("UPDATE ops.audit_event SET action='REWRITE' WHERE actor_id=?",admin.userId()));assertThrows(Exception.class,()->db.update("DELETE FROM ops.audit_event WHERE actor_id=?",admin.userId()));assertThrows(Exception.class,()->db.update("TRUNCATE ops.audit_event CASCADE"));}
    @Test void invalidTimeRangeAndUnboundedPageAreRejected(){var f=filters("PLATFORM");f.put("from",Instant.now().minus(Duration.ofDays(367)).toString());status(400,()->security.query(admin,f,UUID.randomUUID()));var invalid=filters("PLATFORM");invalid.put("pageSize","1000");status(400,()->security.query(admin,invalid,UUID.randomUUID()));}
    @Test void printThresholdIsSeparateAndCountsThirtyDistinctFiles(){UUID file=UUID.randomUUID();for(int i=0;i<35;i++)db.rows("SELECT ops.security_signal_add(?,'BULK_PRINT',?,?)",employee.userId(),file,UUID.randomUUID());security.requireAvailable(employee.userId(),"BULK_PRINT");for(int i=0;i<29;i++)db.rows("SELECT ops.security_signal_add(?,'BULK_PRINT',?,?)",employee.userId(),UUID.randomUUID(),UUID.randomUUID());status(429,()->security.requireAvailable(employee.userId(),"BULK_PRINT"));security.requireAvailable(employee.userId(),"BULK_DOWNLOAD");assertEquals(30,db.count("SELECT observed_count FROM ops.security_alert WHERE user_id=? AND kind='BULK_PRINT'",employee.userId()));}
    @Test void criticalProtectionFailuresAreNotMisreportedAsNormalDeniedAccess(){UUID request=UUID.randomUUID();audit.denied(employee.userId(),"PROTECTION_NOT_READY",request);assertEquals("CRITICAL",db.required("SELECT severity FROM ops.audit_event WHERE request_id=?",request).get("severity"));assertEquals(0,db.count("SELECT count(*) FROM ops.security_signal WHERE user_id=?",employee.userId()));}
    @Test void loginLockAlertsUseExistingDurableOutbox(){String bucket=Crypto.hash(employee.userId().toString());for(int i=0;i<5;i++)new TransactionTemplate(manager).executeWithoutResult(tx->messages.locked(bucket,employee.userId()));assertEquals(1,db.count("SELECT count(*) FROM ops.security_alert WHERE user_id=? AND kind='LOGIN_LOCKS'",employee.userId()));assertEquals(1,db.count("SELECT count(*) FROM ops.account_message WHERE user_id=? AND kind='LOGIN_LOCKS'",employee.userId()));security.requireAvailable(employee.userId(),"ACCESS_DENIED");}
    @Test void csvFormulaCellsAreNeutralized(){UUID object=UUID.randomUUID();audit.event(admin.userId(),"PLATFORM","CSV_TEST",object,"=1+1","SUCCEEDED",UUID.randomUUID());var f=filters("PLATFORM");f.put("resourceId",object.toString());UUID id=query(admin,f);approve(admin,id,context(admin,"AUDIT_EXPORT",id,"EXPORT_AUDIT",null));assertTrue(new String(security.export(admin,id,UUID.randomUUID()),StandardCharsets.UTF_8).contains("\"'=1+1\""));}
    @Test void securityGatewayUsesLocalProtocolAndFixedRedactedTemplates() throws Exception {
        var received=new ArrayList<String>();var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/robot",exchange->{received.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] body="{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();
        try{var env=new org.springframework.mock.env.MockEnvironment().withProperty("platform.messaging.dingtalk.webhook","http://127.0.0.1:"+server.getAddress().getPort()+"/robot").withProperty("platform.messaging.dingtalk.secret","isolated-test-key").withProperty("platform.messaging.dingtalk.local-test","true").withProperty("platform.isolation-confirmed","true");var gateway=new com.company.platform.operations.DingTalkGateway(env);for(String kind:List.of("LOGIN_LOCKS","BULK_DOWNLOAD","BULK_PRINT","ACCESS_DENIED"))gateway.send(UUID.randomUUID(),kind);assertEquals(4,received.size());for(String body:received){assertTrue(body.contains("#/security"));assertFalse(body.contains(employee.userId().toString()));assertFalse(body.contains("机密题名"));assertFalse(body.contains("isolated-test-key"));}}finally{server.stop(0);}
    }
    /** 最小合法批准链用于检验真实数据库触发器；不模拟文件检测或宣称生成了真实水印副本。 */
    private UUID exportFixture(String action,boolean batchBound){return new TransactionTemplate(manager).execute(tx->{
        UUID box=box(department,employee.userId()),record=UUID.randomUUID(),template=UUID.randomUUID(),object=UUID.randomUUID(),pdf=UUID.randomUUID(),revision=UUID.randomUUID(),batch=UUID.randomUUID(),request=UUID.randomUUID(),flow=UUID.randomUUID(),route=UUID.randomUUID(),flowTemplate=UUID.randomUUID(),grant=UUID.randomUUID(),usage=UUID.randomUUID();
        db.update("INSERT INTO archive.directory_template(id,template_key,version_no,category_code,object_type,name,created_by) VALUES(?,?,1,'ADMIN','RECORD','受理计数夹具',?)",template,template.toString(),admin.userId());
        db.update("INSERT INTO archive.record(id,file_no,box_id,item_no,department_id,classification,category_code,carrier_type,title,keywords,owner_id,archive_date,retention_code,template_version_id,created_by) VALUES(?,?,?,1,?,'GENERAL','ADMIN','ELECTRONIC','计数夹具','{验收}',?,CURRENT_DATE,'YJ',?,?)",record,record.toString(),box,department,employee.userId(),template,admin.userId());
        db.update("INSERT INTO storage.object(id,module,storage_key,sha256,byte_size,media_type,original_name,object_kind,state,created_by) VALUES(?,'ARCHIVE',?,?,1,'application/pdf','fixture.pdf','ORIGINAL','STAGED',?)",object,object.toString(),"0".repeat(64),employee.userId());
        db.update("INSERT INTO archive.pdf_version(id,record_id,version_no,object_id,source,created_by,page_count) VALUES(?,?,1,?,'UPLOAD',?,1)",pdf,record,object,employee.userId());
        db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,pdf_version_id,metadata_snapshot,template_version_id,created_by) VALUES(?,?,1,?,'{}',?,?)",revision,record,pdf,template,employee.userId());
        db.update("INSERT INTO archive.borrow_batch(id,applicant_id,applicant_department_id,form,actions,purpose,parent_max_classification,item_count,calendar_version,requested_period,state) VALUES(?,?,?,'ELECTRONIC',?::varchar[],'计数验收','GENERAL',1,1,'{}','APPROVED')",batch,employee.userId(),department,"{"+action+"}");
        db.update("INSERT INTO workflow.template_version(id,template_key,version_no,business_type,route_kind,nodes_definition,checksum) VALUES(?,?,1,'BORROW','TEST','[]',?)",flowTemplate,flowTemplate.toString(),"0".repeat(64));
        db.update("INSERT INTO workflow.instance(id,business_type,business_id,applicant_id,template_version_id,route_snapshot_id,state,business_snapshot) VALUES(?,'BORROW',?,?,?,?,'APPROVED','{}')",flow,request,employee.userId(),flowTemplate,route);
        db.update("INSERT INTO workflow.route_snapshot(id,instance_id,batch_id,highest_classification,form,applicant_role,department_context,template_version,nodes_snapshot) VALUES(?,?,?,'GENERAL','ELECTRONIC','STAFF','{}',1,'[]')",route,flow,batch);
        db.update("INSERT INTO archive.borrow_request(id,batch_id,owner_department_id,applicant_id,workflow_instance_id,route_snapshot_id,state,approved_until,approved_actions) VALUES(?,?,?,?,?,?,'APPROVED',CURRENT_TIMESTAMP+interval '1 hour',?::varchar[])",request,batch,department,employee.userId(),flow,route,"{"+action+"}");
        db.update("INSERT INTO archive.request_item(id,batch_id,request_id,record_id,record_revision_id,pdf_version_id,action,classification_snapshot) VALUES(?,?,?,?,?,?,?,'GENERAL')",UUID.randomUUID(),batch,request,record,revision,pdf,action);
        boolean print=action.equals("PRINT");
        db.update("INSERT INTO archive.access_grant(id,user_id,record_id,record_revision_id,pdf_version_id,action,source_type,source_request_id,approval_reference,valid_from,valid_until,approved_pages,approved_copies) VALUES(?,?,?,?,?,?,'BORROW',?,'隔离审批',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '1 hour',?::integer[],?)",grant,employee.userId(),record,revision,pdf,action,request,print?"{1}":null,print?100:null);
        db.update("INSERT INTO archive.export_request(id,user_id,session_id,auth_version,action,operation_id,request_digest,borrow_batch_id,expires_at,watermark_name,watermark_employee_no) VALUES(?,?,?,?,?,?,?, ?,CURRENT_TIMESTAMP+interval '1 hour','验收','TEST')",usage,employee.userId(),employee.sessionId(),employee.authVersion(),action,UUID.randomUUID(),"0".repeat(64),batchBound?batch:null);
        db.update("INSERT INTO archive.export_item(id,usage_id,sequence_no,record_id,record_revision_id,pdf_version_id,object_id,grant_id,source_version,file_no,pages,copies) VALUES(?,?,1,?,?,?,?,?,1,'TEST','{1}',1)",UUID.randomUUID(),usage,record,revision,pdf,object,grant);
        return usage;
    });}
    @Test void acceptedPrintCountsOnceAndApprovedFixedBatchIsExempt(){UUID single=exportFixture("PRINT",false),batch=exportFixture("PRINT",true);for(UUID id:List.of(single,batch))for(int i=0;i<2;i++)audit.event(employee.userId(),"ARCHIVE","USAGE",id,"PRINT_REQUESTED","SUCCEEDED",UUID.randomUUID());assertEquals(1,db.count("SELECT count(DISTINCT file_id) FROM ops.security_signal WHERE user_id=? AND kind='BULK_PRINT'",employee.userId()));assertEquals(0,db.count("SELECT count(*) FROM ops.security_signal WHERE user_id=? AND file_id IN(SELECT record_id FROM archive.export_item WHERE usage_id=?)",employee.userId(),batch));}
    @Test void downloadAuthorizationDoesNotCountUntilOutputCompletes(){UUID id=exportFixture("DOWNLOAD",false);audit.event(employee.userId(),"ARCHIVE","USAGE",id,"DOWNLOAD_REQUESTED","SUCCEEDED",UUID.randomUUID());audit.event(employee.userId(),"ARCHIVE","USAGE",id,"EXPORT_DELIVERY_AUTHORIZED","SUCCEEDED",UUID.randomUUID());assertEquals(0,db.count("SELECT count(*) FROM ops.security_signal WHERE user_id=?",employee.userId()));audit.event(employee.userId(),"ARCHIVE","USAGE",id,"EXPORT_DELIVERED","SUCCEEDED",UUID.randomUUID());assertEquals(1,db.count("SELECT count(*) FROM ops.security_signal WHERE user_id=?",employee.userId()));}
    @Test void physicalItemScopeFollowsOwningBox(){UUID box=box(department,employee.userId()),physical=UUID.randomUUID();db.update("INSERT INTO archive.physical_item(id,box_id,object_type,barcode,location) VALUES(?,?,'BOX',?,'隔离位置')",physical,box,physical.toString());audit.event(admin.userId(),"ARCHIVE","PHYSICAL_ITEM",physical,"PHYSICAL_TEST","SUCCEEDED",UUID.randomUUID());var f=filters("ARCHIVE");f.put("resourceId",physical.toString());status(403,()->security.query(clerk,f,UUID.randomUUID()));assertEquals(1L,security.query(admin,f,UUID.randomUUID()).get("total"));}
}
