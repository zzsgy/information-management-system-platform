package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.operations.DeploymentSettings;
import com.company.platform.intake.ImportWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.zip.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** 仅显式独立库验收：真实领域授权、原生ClamAV测试签名、真实PDF进程和签名保护检查。 */
@EnabledIfEnvironmentVariable(named="IMS_DRIVE_LIFECYCLE_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveLifecycleIntegrationTest.NativeScanner.class},webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false","platform.office.checkpoint-enabled=true"})
@TestMethodOrder(MethodOrderer.MethodName.class)
class DriveLifecycleIntegrationTest {
    // 仅模拟外部文档服务协议；授权、检测、双副本与版本提交全部调用真实实现。
    private static HttpServer officeServer;
    private static byte[] officeCandidate;
    private static final String outbox="isolated-office-outbox-test-key-20261029";
    @DynamicPropertySource static void officeProperties(DynamicPropertyRegistry registry)throws Exception {
        Path root=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT"));Path inbox=root.resolve("office-inbox-test.key"),out=root.resolve("office-outbox-test.key");Files.writeString(inbox,"isolated-office-inbox-test-key-20261029");Files.writeString(out,outbox);PrivateFiles.restrict(inbox);PrivateFiles.restrict(out);
        officeServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        officeServer.createContext("/command",exchange->{exchange.getRequestBody().readAllBytes();byte[] response="{\"error\":0,\"version\":\"contract-test\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);try(var output=exchange.getResponseBody()){output.write(response);}});
        officeServer.createContext("/cache/files/",exchange->{exchange.sendResponseHeaders(200,officeCandidate.length);try(var output=exchange.getResponseBody()){output.write(officeCandidate);}});officeServer.start();
        String origin="http://127.0.0.1:"+officeServer.getAddress().getPort();
        registry.add("platform.office.enabled",()->true);registry.add("platform.office.browser-origin",()->origin);registry.add("platform.office.server-origin",()->origin);registry.add("platform.office.callback-origin",()->"https://127.0.0.1:18455");registry.add("platform.office.expected-version",()->"contract-test");registry.add("platform.office.inbox-key-file",inbox::toString);registry.add("platform.office.outbox-key-file",out::toString);registry.add("platform.office.temporary",()->root.resolve("office-temp").toString());registry.add("platform.office.service-id",()->"002d31cf-a9e3-47b8-9da4-c6c343e88f09");
    }
    @AfterAll static void stopOffice(){if(officeServer!=null)officeServer.stop(0);}
    /** 替换范围仅限测试签名库；真实程序扫描，不把生产官方库要求改成可配置的关闭开关。 */
    @TestConfiguration(proxyBeanMethods=false)
    static class NativeScanner {
        /** 模拟引擎暂不可用，正常路径仍调用原生进程。 */
        static final AtomicBoolean DOWN=new AtomicBoolean(false);
        /** 并发撤权验收只在扫描IO阶段暂停，不能占用数据库行锁。 */
        static volatile java.util.concurrent.CountDownLatch scanStarted,scanRelease;
        @Bean @Primary FileScanEngine nativeTestScanner() throws Exception {
            Path exe=Path.of(System.getenv("IMS_CLAM_TEST_EXE")),root=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")),db=Files.createDirectory(root.resolve("signature-"+UUID.randomUUID())),temp=Files.createDirectory(root.resolve("scan-"+UUID.randomUUID()));
            byte[] marker=suspicious();Files.writeString(db.resolve("synthetic.hdb"),digest(marker,"MD5")+":"+marker.length+":IMS.Integration.Test\n");
            ClamFileScanner engine=new ClamFileScanner(exe,db,temp,digest(Files.readAllBytes(exe),"SHA-256"),Duration.ofSeconds(30),false);
            return source->{
                if(scanStarted!=null) {scanStarted.countDown();try {if(!scanRelease.await(10,java.util.concurrent.TimeUnit.SECONDS)) return ClamFileScanner.Verdict.UNAVAILABLE;}catch(InterruptedException error){Thread.currentThread().interrupt();return ClamFileScanner.Verdict.UNAVAILABLE;}}
                return DOWN.get()?ClamFileScanner.Verdict.UNAVAILABLE:engine.scan(source);
            };
        }
    }
    /** 测试使用真实事务服务，不使用全允许领域策略。 */
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired UploadService uploads;@Autowired UploadAssembly assembly;
    @Autowired FileInspectionService inspection;@Autowired DriveDocumentService documents;@Autowired ControlledReadService reading;
    @Autowired DriveController driveController;
    @Autowired DriveAccessService access;@Autowired DriveFilePolicy policy;@Autowired PlatformTransactionManager manager;
    @Autowired DriveLifecycle lifecycle;
    @Autowired OfficeSessions office;@Autowired OfficeGateway gateway;@Autowired OfficeReview officeReview;
    @Autowired DriveFolders folderLifecycle;
    @Autowired DeploymentSettings deployment;
    @Autowired Commands commands;
    /** 每项测试创建独立部门、员工、委托、对象和阅读会话，不回收历史测试数据。 */
    private Actor admin,leader,staff;private UUID department,folder,delegation;private String leaderNo,staffNo,password;
    /** 合成命中样本不包含病毒或EICAR，只匹配测试目录内自定义完整摘要。 */
    private static byte[] suspicious() {return "%PDF-1.4\nIMS-INTEGRATION-SIGNATURE-ONLY".getBytes(StandardCharsets.UTF_8);}
    private static String digest(byte[] bytes,String algorithm) {try{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    /** 验收必须明确落在本机新平台专用库，配置中的密码不写输出。 */
    @BeforeEach void fixture() throws Exception {
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:554(49|50)/ims_platform_dev_[0-9]+"));
        NativeScanner.DOWN.set(false);NativeScanner.scanStarted=null;NativeScanner.scanRelease=null;admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence("NORMAL",false);
        department=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",department,"T"+department,"合成验收部门"+department.toString().substring(0,8));
        password=Crypto.token();leaderNo="L"+UUID.randomUUID();staffNo="S"+UUID.randomUUID();
        UUID leaderId=person(leaderNo,"验收负责人","DEPARTMENT_MANAGER"),staffId=person(staffNo,"验收员工","STAFF");
        db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'合成验收',?)",UUID.randomUUID(),department,leaderId,admin.userId());
        folder=(UUID)access.folder(context(admin,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),Sql.map("zone","DEPARTMENT_SHARED","departmentId",department.toString(),"name","验收资料"+UUID.randomUUID())).get("id");
        UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='DRIVE' AND code='DEPARTMENT_MANAGER'").get("id");
        delegation=(UUID)access.delegate(context(admin,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DELEGATION",null),Sql.map("departmentId",department.toString(),"folderId",folder.toString(),"roleId",role.toString(),"allowedActions",List.of("VIEW","UPLOAD","DOWNLOAD","EDIT","DELETE","MOVE","RESTORE","PURGE","FOLDER_MANAGE","COLLABORATE"),"reason","合成验收委托")).get("id");
        UUID service=Inputs.uuid(System.getenv("IMS_PLATFORM_SCANNER_SERVICE_ID"));
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','隔离验收扫描','DRIVE',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope",service,"{"+department+"}");
        leader=login(leaderNo,password);staff=login(staffNo,password);assertNotEquals(staffId,leader.userId());
    }
    /** 用户资料由真实数据库角色关系生成。 */
    private UUID person(String no,String name,String roleCode) {
        UUID id=UUID.randomUUID();db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,name,department,no.toLowerCase(Locale.ROOT)+"@biolink.com",Crypto.password(password));
        UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='DRIVE' AND code=?",roleCode).get("id");
        db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,'DRIVE',?)",UUID.randomUUID(),id,role,department,admin.userId(),roleCode);return id;
    }
    /** 通过本人密码再验证取得与实际对象和动作绑定的一次性凭据。 */
    private Commands.Context context(Actor actor,String type,UUID target,String action,Integer version) {
        String secret=actor.userId().equals(admin.userId())?System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"):password;
        var proof=identity.reauthenticate(actor,Sql.map("password",secret,"purpose","MANAGE","objectIds",List.of(target.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",target.toString(),"actions",List.of(action)))),UUID.randomUUID());
        return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action,(String)proof.get("verificationToken"),version);
    }
    private Actor login(String no,String secret) {return identity.authenticate((String)identity.login(Sql.map("employeeNo",no,"password",secret),UUID.randomUUID(),"drive-integration").get("token"));}
    /** 保护证据只在合成测试中签发，不能作为正式备份或主备验证报告。 */
    private void evidence(String mode,boolean expired) throws Exception {
        Path key=Path.of(System.getenv("IMS_PLATFORM_PROTECTION_KEY"));if(!Files.exists(key)) {Files.writeString(key,Crypto.token(),StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(key);}
        Instant now=Instant.now().minusSeconds(expired?120:0);
        var state=Sql.map("instanceId",db.required("SELECT id FROM platform_meta.instance").get("id").toString(),"nodeId",System.getenv("IMS_PLATFORM_NODE_ID"),"epoch",1,"issuedAt",now.toString(),"expiresAt",now.plusSeconds(60).toString(),"mode",mode,"oldPrimaryFenced",true,"replicaVerifiedAt",now.toString(),"backupId",UUID.randomUUID().toString(),"backupProtectedAt",now.toString(),"writableModules",List.of("DRIVE"));
        state.put("signature",Crypto.hmac(Files.readString(key),Crypto.json(state)));Files.writeString(Path.of(System.getenv("IMS_PLATFORM_PROTECTION_EVIDENCE")),Crypto.json(state));
    }
    /** 按真实分片、合并与扫描步骤进入检测，不直接改对象READY状态。 */
    private UUID upload(Actor actor,byte[] content) {
        var submitted=queueUpload(actor,"合成验收.pdf","application/pdf",content);
        UUID job=(UUID)submitted.get("jobId"), request=UUID.randomUUID();
        assembly.assemble(actor,job,request);
        inspection.inspect(actor,job,request);
        return (UUID)submitted.get("uploadId");
    }

    private Map<String,Object> queueUpload(Actor actor,String name,String media,byte[] content) {
        String hash=digest(content,"SHA-256");
        UUID request=UUID.randomUUID();
        UUID upload=(UUID)uploads.create(actor,Sql.map("module","DRIVE","businessContextId",folder.toString(),"originalName",name,
            "expectedSize",content.length,"expectedSha256",hash,"expectedMediaType",media,"purpose","DRIVE_FILE"),UUID.randomUUID(),request).get("uploadId");
        uploads.part(actor,upload,1,"bytes 0-"+(content.length-1)+"/"+content.length,content.length,hash,new ByteArrayInputStream(content),request);
        UUID job=(UUID)uploads.complete(actor,upload,Sql.map("partCount",1,"finalSha256",hash),UUID.randomUUID(),request).get("jobId");
        return Sql.map("uploadId",upload,"jobId",job);
    }
    private UUID published() throws Exception {UUID upload=upload(leader,Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF"))));return (UUID)documents.publish(leader,Sql.map("folderId",folder.toString(),"uploadSessionId",upload.toString(),"name","已发布验收.pdf"),UUID.randomUUID(),UUID.randomUUID()).get("id");}
    private UUID preview(Actor actor,UUID document) {UUID job=(UUID)reading.create(actor,document,Map.of(),UUID.randomUUID(),UUID.randomUUID()).get("jobId");reading.prepare(actor,job,UUID.randomUUID());return Inputs.uuid(db.required("SELECT business_ref FROM ops.job WHERE id=?",job).get("business_ref"));}

    /** 员工不能因共享可读和本人上传自动获得编辑/删除；IT也不默认拥有内容写权。 */
    @Test void currentRightsKeepReadAndContentWriteSeparate() throws Exception {
        UUID doc=published();assertNotNull(policy.requireDocument(staff,doc,"VIEW"));assertNotNull(policy.requireDocument(admin,doc,"DOWNLOAD"));
        for(String action:List.of("EDIT","DELETE","MOVE","RESTORE","PURGE"))assertThrows(ApiFault.class,()->policy.requireDocument(staff,doc,action));
        assertThrows(ApiFault.class,()->policy.requireDocument(admin,doc,"EDIT"));assertThrows(ApiFault.class,()->policy.requireFolder(staff,folder,"FOLDER_MANAGE"));
    }

    /** 本部门普通员工可完成上传和发布，祖先拒绝、跨部门和共享分区仍按对象校验。 */
    @Test void staffCanPublishInsideOwnDepartmentUnlessExplicitlyDenied() throws Exception {
        UUID shared=folder, otherDepartment=UUID.randomUUID(), otherInternal=UUID.randomUUID();
        folder=UUID.randomUUID();
        db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",
                otherDepartment,"INT-"+otherDepartment,"隔离外部部门");
        String otherFolderName="其他部门内部验收"+otherInternal;
        db.update("""
                INSERT INTO drive.folder(id,parent_id,zone,department_id,folder_level,name,name_key) VALUES
                (?,?,'DEPARTMENT_INTERNAL',?,'CHILD','内部上传验收','内部上传验收'),
                (?,NULL,'DEPARTMENT_INTERNAL',?,'TOP',?,?)
                """,folder,shared,department,otherInternal,otherDepartment,otherFolderName,otherFolderName);
        var query=Map.of("folderId",folder.toString(),"space","OWN_INTERNAL");
        assertEquals(true,documents.documents(staff,query).get("folderCanUpload"));
        assertEquals(false,documents.documents(staff,Map.of("folderId",shared.toString())).get("folderCanUpload"));
        assertEquals(403,assertThrows(ApiFault.class,()->policy.requireFolder(staff,otherInternal,"UPLOAD")).status);
        assertEquals(403,assertThrows(ApiFault.class,()->policy.requireFolder(staff,folder,"FOLDER_MANAGE")).status);

        byte[] content=Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")));
        UUID approved=upload(staff,content);
        var published=documents.publish(staff,Sql.map("folderId",folder.toString(),"uploadSessionId",approved.toString(),
                "name","本部门员工上传.pdf"),UUID.randomUUID(),UUID.randomUUID());
        assertEquals(staff.userId(),db.required("SELECT uploaded_by FROM drive.document WHERE id=?",published.get("id")).get("uploaded_by"));

        db.update("""
                INSERT INTO drive.resource_grant(id,subject_id,resource_type,resource_id,action,effect,delegation_id,granted_by)
                VALUES(?,?,'FOLDER',?,'UPLOAD','DENY',?,?)
                """,UUID.randomUUID(),staff.userId(),shared,delegation,leader.userId());
        assertEquals(false,documents.documents(staff,query).get("folderCanUpload"));
        assertEquals(403,assertThrows(ApiFault.class,()->upload(staff,content)).status);
    }
    /** 同操作号不会重复增加版本，异参重用和并发旧版本不能覆盖现有文档。 */
    @Test void renameUsesExpectedVersionAndStableOperation() throws Exception {
        UUID doc=published();var ctx=context(leader,"DRIVE_DOCUMENT",doc,"EDIT",1);var body=Sql.map("name","已更名.pdf","reason","验收重命名");
        assertEquals(2,lifecycle.rename(ctx,doc,body).get("rowVersion"));assertEquals(2,lifecycle.rename(ctx,doc,body).get("rowVersion"));
        assertEquals(409,assertThrows(ApiFault.class,()->lifecycle.rename(ctx,doc,Sql.map("name","重复异参.pdf","reason","同号异参"))).status);
        assertEquals(409,assertThrows(ApiFault.class,()->lifecycle.rename(context(leader,"DRIVE_DOCUMENT",doc,"EDIT",1),doc,body)).status);
        assertEquals(400,assertThrows(ApiFault.class,()->lifecycle.rename(context(leader,"DRIVE_DOCUMENT",doc,"EDIT",2),doc,Sql.map("name","伪装.docx","reason","禁止改扩展名"))).status);
    }
    /** 恢复历史追加版本，旧版本从数据库层也不能改写；撤权后历史下载立即失效。 */
    @Test void immutableHistoryAppendsAndUsesCurrentPermission() throws Exception {
        UUID doc=published(),first=(UUID)documents.detail(leader,doc).get("currentVersionId");
        lifecycle.restoreVersion(context(leader,"DRIVE_DOCUMENT",doc,"EDIT",1),doc,Sql.map("sourceVersionId",first.toString(),"reason","恢复验收"));
        assertEquals(2L,lifecycle.versions(staff,doc,Map.of()).get("total"));assertEquals(first,lifecycle.download(staff,doc,first).get("version_id"));
        UUID readJob=(UUID)reading.create(staff,doc,Sql.map("versionId",first),UUID.randomUUID(),UUID.randomUUID()).get("jobId");reading.prepare(staff,readJob,UUID.randomUUID());UUID preview=Inputs.uuid(db.required("SELECT business_ref FROM ops.job WHERE id=?",readJob).get("business_ref"));assertTrue(reading.page(staff,preview,1,UUID.randomUUID()).length>0);
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE drive.document_version SET source_type='BAD' WHERE id=?",first));
        access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","DOWNLOAD","effect","DENY","delegationId",delegation.toString(),"reason","撤销下载"));staff=login(staffNo,password);
        assertEquals(403,assertThrows(ApiFault.class,()->lifecycle.download(staff,doc,first)).status);assertNotNull(documents.detail(staff,doc));
    }
    /** 回收后旧预览与原件都关闭，同号重试不重复回收，30天内禁止永久清理。 */
    @Test void recycleRetainsVersionsAndClosesOldDelivery() throws Exception {
        UUID doc=published(),preview=preview(staff,doc);var ctx=context(leader,"DRIVE_DOCUMENT",doc,"DELETE",1);var body=Sql.map("reason","回收验收");
        assertEquals("RECYCLED",lifecycle.recycle(ctx,doc,body).get("status"));assertEquals("RECYCLED",lifecycle.recycle(ctx,doc,body).get("status"));
        assertThrows(ApiFault.class,()->reading.manifest(staff,preview));assertThrows(ApiFault.class,()->lifecycle.download(staff,doc,null));assertEquals(1L,lifecycle.recycleList(leader,Map.of()).get("total"));
        assertEquals(409,assertThrows(ApiFault.class,()->lifecycle.purge(context(leader,"DRIVE_DOCUMENT",doc,"PURGE",2),doc,body)).status);
        assertEquals("ACTIVE",lifecycle.restore(context(leader,"DRIVE_DOCUMENT",doc,"RESTORE",2),doc,body).get("status"));assertEquals(1,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",doc));
    }
    /** 员工DELETE/MOVE授权仍只能操作本人原始上传，不能删除负责人或其他员工文件。 */
    @Test void employeeDeleteGrantCannotDeleteSomeoneElsesUpload() throws Exception {
        UUID doc=published();access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","FOLDER","resourceId",folder.toString(),"action","DELETE","effect","ALLOW","delegationId",delegation.toString(),"reason","允许管理本人文件"));staff=login(staffNo,password);
        assertEquals(403,assertThrows(ApiFault.class,()->lifecycle.recycle(context(staff,"DRIVE_DOCUMENT",doc,"DELETE",1),doc,Sql.map("reason","越权验收"))).status);
    }
    /** 移动确认绑定目录与授权；目标变化时失败且原文档位置及版本保持不变。 */
    @Test void moveConfirmationCannotSurviveTargetChanges() throws Exception {
        UUID doc=published(),target=(UUID)access.folder(context(leader,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),Sql.map("parentId",folder.toString(),"zone","DEPARTMENT_SHARED","departmentId",department.toString(),"name","目标目录")).get("id");
        String digest=(String)lifecycle.moveCheck(leader,doc,target).get("confirmationDigest");db.update("UPDATE drive.folder SET row_version=row_version+1 WHERE id=?",target);
        var body=Sql.map("targetFolderId",target.toString(),"confirmationDigest",digest,"reason","移动验收");assertEquals(409,assertThrows(ApiFault.class,()->lifecycle.move(context(leader,"DRIVE_DOCUMENT",doc,"MOVE",1),doc,body)).status);
        body.put("confirmationDigest",lifecycle.moveCheck(leader,doc,target).get("confirmationDigest"));assertEquals(target,lifecycle.move(context(leader,"DRIVE_DOCUMENT",doc,"MOVE",1),doc,body).get("folderId"));
    }
    /** VIEW拒绝在关键词匹配、结果计数与分页前生效。 */
    @Test void searchDoesNotCountDeniedFiles() throws Exception {
        UUID doc=published();access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","VIEW","effect","DENY","delegationId",delegation.toString(),"reason","检索越权验收"));staff=login(staffNo,password);
        assertEquals(0L,documents.documents(staff,Map.of("folderId",folder.toString(),"keyword","已发布")).get("total"));assertEquals(0L,documents.documents(staff,Map.of("departmentId",department.toString(),"fileType","pdf")).get("total"));
    }
    /** 已检测双副本损坏时原件交付失败，不能因DOWNLOAD授权而跳过完整性检查。 */
    @Test void damagedReplicaStopsOriginalDownload() throws Exception {
        UUID doc=published();var object=lifecycle.download(staff,doc,null);Path secondary=Path.of(System.getenv("IMS_PLATFORM_STORAGE_SECONDARY")).resolve((String)object.get("storage_key"));Files.write(secondary,new byte[]{1},StandardOpenOption.APPEND);
        assertEquals(503,assertThrows(ApiFault.class,()->lifecycle.stream(staff,doc,(UUID)object.get("version_id"),0,1,new ByteArrayOutputStream(),UUID.randomUUID())).status);
    }
    private UUID officeDocument()throws Exception {
        byte[] content=officeBytes("原件");String hash=digest(content,"SHA-256");UUID request=UUID.randomUUID();UUID upload=(UUID)uploads.create(leader,Sql.map("module","DRIVE","businessContextId",folder.toString(),"originalName","协作.docx","expectedSize",content.length,"expectedSha256",hash,"expectedMediaType","application/octet-stream","purpose","DRIVE_FILE"),UUID.randomUUID(),request).get("uploadId");
        uploads.part(leader,upload,1,"bytes 0-"+(content.length-1)+"/"+content.length,content.length,hash,new ByteArrayInputStream(content),request);UUID job=(UUID)uploads.complete(leader,upload,Sql.map("partCount",1,"finalSha256",hash),UUID.randomUUID(),request).get("jobId");assembly.assemble(leader,job,request);inspection.inspect(leader,job,request);
        UUID doc=(UUID)documents.publish(leader,Sql.map("folderId",folder.toString(),"uploadSessionId",upload.toString(),"name","协作.docx"),UUID.randomUUID(),request).get("id");lifecycle.collaboration(context(leader,"DRIVE_DOCUMENT",doc,"COLLABORATE",1),doc,Sql.map("collaborationEnabled",true,"reason","开启测试协作"));
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'OFFICE','协议验收文档服务','DRIVE',?::uuid[],'{EDIT}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope",gateway.service(),"{"+department+"}");officeCandidate=officeBytes("编辑后的内容");return doc;
    }
    private UUID openOffice(Actor actor,UUID doc){return (UUID)office.open(actor,doc,Sql.map("baseVersionId",documents.detail(actor,doc).get("currentVersionId")),UUID.randomUUID()).get("sessionId");}
    private int callback(UUID session,int status,UUID receipt){return callback(session,status,receipt,null);}
    private int callback(UUID session,int status,UUID receipt,UUID author){var payload=Sql.map("key",db.required("SELECT editor_key FROM drive.edit_session WHERE id=?",session).get("editor_key"),"status",status,"url","http://127.0.0.1:"+officeServer.getAddress().getPort()+"/cache/files/candidate.docx");if(receipt!=null)payload.put("userdata",receipt.toString());if(author!=null)payload.put("users",List.of(author.toString()));return office.callback(session,gateway.nativePayload(OfficeGateway.jwt(payload,outbox),false));}
    private byte[] officeBytes(String text)throws Exception {var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var entry:Map.of("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>","_rels/.rels","<Relationships/>","word/document.xml","<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>"+text+"</w:t></w:r></w:p></w:body></w:document>").entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return bytes.toByteArray();}
    @Test void nativeCheckpointPublishesOnceAndBrowserTokenCannotFetchOriginal()throws Exception {
        UUID doc=officeDocument(),session=openOffice(leader,doc),operation=UUID.randomUUID();UUID receipt=(UUID)office.checkpoint(leader,session,operation,UUID.randomUUID()).get("id");assertEquals(receipt,office.checkpoint(leader,session,operation,UUID.randomUUID()).get("id"));assertEquals(0,callback(session,6,receipt));assertEquals(0,callback(session,6,receipt));assertEquals(2,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",doc));assertEquals("SAVED",db.required("SELECT state FROM drive.save_receipt WHERE id=?",receipt).get("state"));
        assertThrows(ApiFault.class,()->gateway.nativePayload(gateway.sign(Sql.map("payload",Sql.map("url",gateway.source(session)))),true));
    }
    /** 两人共用同一批次，主动保存按申请人署名，最终保存仅接受已登记编辑者。 */
    @Test void officeTwoParticipantsKeepAttributionAndFinalSaveSeparate()throws Exception {
        UUID doc=officeDocument();access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","EDIT","effect","ALLOW","delegationId",delegation.toString(),"reason","双人协作署名验收"));staff=login(staffNo,password);
        UUID session=openOffice(leader,doc);assertEquals(session,openOffice(staff,doc));assertEquals(2,((List<?>)office.detail(leader,session).get("participants")).size());
        UUID receipt=(UUID)office.checkpoint(staff,session,UUID.randomUUID(),UUID.randomUUID()).get("id");assertEquals(0,callback(session,6,receipt));
        assertEquals(staff.userId(),db.required("SELECT created_by FROM drive.document_version WHERE document_id=? AND version_no=2",doc).get("created_by"));
        assertEquals("OPEN",office.leave(leader,session).get("state"));assertThrows(ApiFault.class,()->office.checkpoint(leader,session,UUID.randomUUID(),UUID.randomUUID()));
        assertEquals("CLOSING",office.leave(staff,session).get("state"));assertEquals("CLOSING",office.detail(staff,session).get("state"));
        assertThrows(ApiFault.class,()->callback(session,2,null,admin.userId()));assertEquals(0,callback(session,2,null,staff.userId()));assertEquals(0,callback(session,2,null,staff.userId()));
        assertEquals("CLOSED",office.detail(staff,session).get("state"));assertEquals(3,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",doc));
        assertEquals(staff.userId(),db.required("SELECT created_by FROM drive.document_version WHERE document_id=? AND version_no=3",doc).get("created_by"));
    }
    /** 即使发起人仍在线，另一个参与者登录失效也使整个候选批次待确认。 */
    @Test void officeParticipantExpiryStopsEditingBeforeFinalCallback()throws Exception {
        UUID doc=officeDocument();access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","EDIT","effect","ALLOW","delegationId",delegation.toString(),"reason","多人登录失效验收"));staff=login(staffNo,password);
        UUID session=openOffice(leader,doc);assertEquals(session,openOffice(staff,doc));db.update("UPDATE iam.session SET last_activity_at=CURRENT_TIMESTAMP-interval '31 minutes' WHERE id=?",staff.sessionId());
        assertEquals("UNKNOWN",office.detail(leader,session).get("state"));assertThrows(ApiFault.class,()->office.checkpoint(leader,session,UUID.randomUUID(),UUID.randomUUID()));
        assertEquals(0,callback(session,2,null,staff.userId()));assertEquals(1,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",doc));
        var receipt=db.required("SELECT state,object_id FROM drive.save_receipt WHERE session_id=?",session);assertEquals("UNKNOWN",receipt.get("state"));assertNotNull(receipt.get("object_id"));
    }
    @Test void revokedParticipantCannotPublishAndManagerMustReviewCandidate()throws Exception {
        UUID doc=officeDocument();UUID grant=(UUID)access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","EDIT","effect","ALLOW","delegationId",delegation.toString(),"reason","协作验收")).get("id");staff=login(staffNo,password);UUID session=openOffice(leader,doc);assertEquals(session,openOffice(staff,doc));access.revokeGrant(context(leader,"DRIVE_GRANT",grant,"REVOKE_GRANT",1),grant);
        assertEquals(0,callback(session,2,null));assertEquals(1,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",doc));var receipt=db.required("SELECT * FROM drive.save_receipt WHERE session_id=?",session);assertEquals("UNKNOWN",receipt.get("state"));UUID id=(UUID)receipt.get("id");int version=((Number)documents.detail(leader,doc).get("rowVersion")).intValue();var body=Sql.map("resolution","ACCEPT","reviewedObjectId",receipt.get("object_id"),"reason","核对撤权前候选内容");
        assertEquals(409,assertThrows(ApiFault.class,()->officeReview.resolve(context(leader,"SAVE_RECEIPT",id,"RESOLVE",version),id,body)).status);
        var output=new ByteArrayOutputStream();officeReview.stream(leader,id,output,UUID.randomUUID());assertArrayEquals(officeCandidate,output.toByteArray());officeReview.resolve(context(leader,"SAVE_RECEIPT",id,"RESOLVE",version),id,body);assertEquals(2,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",doc));
    }
    @Test void officeDetectionFailureRetainsCandidateWithoutPublishing()throws Exception {
        UUID doc=officeDocument(),session=openOffice(leader,doc);NativeScanner.DOWN.set(true);assertEquals(0,callback(session,2,null));assertEquals(1,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",doc));var receipt=db.required("SELECT * FROM drive.save_receipt WHERE session_id=?",session);assertEquals("UNKNOWN",receipt.get("state"));assertThrows(ApiFault.class,()->officeReview.candidate(leader,(UUID)receipt.get("id")));
        int version=((Number)documents.detail(leader,doc).get("rowVersion")).intValue();officeReview.resolve(context(leader,"SAVE_RECEIPT",(UUID)receipt.get("id"),"RESOLVE",version),(UUID)receipt.get("id"),Sql.map("resolution","DISCARD","reason","检测失败舍弃"));
    }
    @Test void driveUploadPauseReleasesValidFilesAndResumesVirusChecks() throws Exception {
        UUID scannerGroup=(UUID)db.required("SELECT id FROM ops.deployment_settings WHERE section='SCANNER'").get("id");
        byte[] pdf=Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")));
        NativeScanner.DOWN.set(true);
        UUID blocked=upload(leader,pdf);
        assertEquals("SCANNER_UNAVAILABLE",db.required("SELECT j.error_code FROM ops.job j JOIN storage.upload_session s ON j.id=s.job_id WHERE s.id=?",blocked).get("error_code"));

        configureDriveFileProcessing(scannerGroup,false,false);
        try {
            UUID valid=upload(leader,pdf);
            var checked=db.required("SELECT state,result_code,object_id FROM storage.upload_session WHERE id=?",valid);
            assertEquals("COMPLETE",checked.get("state"));
            assertEquals("VIRUS_SCAN_SKIPPED",checked.get("result_code"));
            assertEquals(1,db.count("SELECT count(*) FROM ops.audit_event WHERE resource_id=? AND action='FILE_VALIDATION_SCAN_SKIPPED' AND result='SUCCEEDED'",checked.get("object_id")));
            assertNotNull(documents.publish(leader,Sql.map("folderId",folder.toString(),"uploadSessionId",valid.toString(),"name","暂停期间上传.pdf"),UUID.randomUUID(),UUID.randomUUID()).get("id"));

            UUID invalid=upload(leader,"ordinary text".getBytes(StandardCharsets.UTF_8));
            assertEquals(Sql.map("state","REJECTED","result_code","MEDIA_TYPE_NOT_ALLOWED"),
                db.required("SELECT state,result_code FROM storage.upload_session WHERE id=?",invalid));
        } finally {
            configureDriveFileProcessing(scannerGroup,true,false);
        }

        UUID blockedAgain=upload(leader,pdf);
        assertEquals("SCANNER_UNAVAILABLE",db.required("SELECT j.error_code FROM ops.job j JOIN storage.upload_session s ON j.id=s.job_id WHERE s.id=?",blockedAgain).get("error_code"));
        NativeScanner.DOWN.set(false);
        UUID scanned=upload(leader,pdf);
        assertEquals("CLEAN",db.required("SELECT result_code FROM storage.upload_session WHERE id=?",scanned).get("result_code"));
    }

    private void configureDriveFileProcessing(UUID id,boolean scan,boolean worker) {
        var group=deployment.get(admin,id);
        var update=new LinkedHashMap<Object,Object>((Map<?,?>)group.get("values"));
        update.put(DeploymentSettings.DRIVE_UPLOAD_VIRUS_SCAN,Boolean.toString(scan));
        update.put(DeploymentSettings.FILE_WORKER_ENABLED,Boolean.toString(worker));
        var body=Sql.map("values",update,"reason","隔离验收切换公盘上传病毒检测");
        var ctx=context(admin,"DEPLOYMENT_SETTINGS",id,"CONFIGURE_DEPLOYMENT",((Number)group.get("rowVersion")).intValue());
        commands.run(ctx,body,"DEPLOYMENT_SETTINGS",id,"CONFIGURE_DEPLOYMENT",()->deployment.get(admin,id),()->deployment.save(ctx,id,body),key->deployment.get(admin,key));
    }

    @Test void scheduledWorkerPublishesDriveFilesWithVirusScanPaused() throws Exception {
        UUID scannerGroup=(UUID)db.required("SELECT id FROM ops.deployment_settings WHERE section='SCANNER'").get("id");
        byte[] docx=DriveMediaAndProtocolTest.relocatedWordPackage();
        byte[] pdf=Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")));
        byte[] xlsx=ImportWorkbook.template("EMPLOYEE");
        var candidates = new LinkedHashMap<>(Map.of("自定义主部件.docx",docx,"标准PDF.pdf",pdf,"工作簿.xlsx",xlsx,
            "伪装文件.docx","非Word内容".getBytes(StandardCharsets.UTF_8)));
        String docxFixture=System.getenv("IMS_DRIVE_TEST_DOCX");
        if(docxFixture!=null) {
            Path input=Path.of(docxFixture);
            candidates.put(input.getFileName().toString(),Files.readAllBytes(input));
        }
        String presentation=System.getenv("IMS_DRIVE_TEST_PPTX");
        if(presentation!=null) candidates.put("演示文稿.pptx",Files.readAllBytes(Path.of(presentation)));
        var submitted=new LinkedHashMap<String,Map<String,Object>>();
        for(var candidate : candidates.entrySet()) {
            submitted.put(candidate.getKey(),queueUpload(leader,candidate.getKey(),"application/octet-stream",candidate.getValue()));
        }
        UUID first=(UUID)submitted.get("自定义主部件.docx").get("jobId");
        var waiting=uploads.job(leader,first);
        assertEquals("FILE_ASSEMBLY_CHECK",waiting.get("jobType"));
        assertEquals("PENDING",waiting.get("state"));
        assertEquals("FILE_WORKER_DISABLED",waiting.get("errorCode"));
        assertEquals(0,waiting.get("attemptCount"));

        NativeScanner.DOWN.set(true);
        configureDriveFileProcessing(scannerGroup,false,true);
        try {
            Instant deadline=Instant.now().plusSeconds(45);
            for(var entry : submitted.entrySet()) {
                UUID id=(UUID)entry.getValue().get("uploadId"), job=(UUID)entry.getValue().get("jobId");
                var status=uploads.job(leader,job);
                while(Set.of("PENDING","RUNNING").contains(status.get("state")) && Instant.now().isBefore(deadline)) {
                    Thread.sleep(100);
                    status=uploads.job(leader,job);
                }
                if(entry.getKey().equals("伪装文件.docx")) {
                    assertEquals("FAILED",status.get("state"),status.toString());
                    assertEquals("MEDIA_TYPE_NOT_ALLOWED",status.get("errorCode"));
                    continue;
                }
                assertEquals("SUCCEEDED",status.get("state"),status.toString());
                assertEquals("VIRUS_SCAN_SKIPPED",db.required("SELECT result_code FROM storage.upload_session WHERE id=?",id).get("result_code"));
                UUID document=(UUID)documents.publish(leader,Sql.map("folderId",folder.toString(),"uploadSessionId",id.toString(),
                    "name",entry.getKey()),UUID.randomUUID(),UUID.randomUUID()).get("id");
                byte[] expected=candidates.get(entry.getKey());
                var delivered=new ByteArrayOutputStream();
                lifecycle.stream(leader,document,null,0,expected.length,delivered,UUID.randomUUID());
                assertArrayEquals(expected,delivered.toByteArray());
            }
        } finally {
            configureDriveFileProcessing(scannerGroup,true,false);
        }
    }

    @Test void versionUploadBindsEditPermissionAndDocument()throws Exception {
        UUID doc=published();byte[] content=Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")));String hash=digest(content,"SHA-256");var body=Sql.map("module","DRIVE","purpose","DRIVE_VERSION","businessContextId",doc.toString(),"originalName","新版本.pdf","expectedSize",content.length,"expectedSha256",hash,"expectedMediaType","application/pdf");
        assertEquals(403,assertThrows(ApiFault.class,()->uploads.create(staff,body,UUID.randomUUID(),UUID.randomUUID())).status);
        UUID id=(UUID)uploads.create(leader,body,UUID.randomUUID(),UUID.randomUUID()).get("uploadId");uploads.part(leader,id,1,"bytes 0-"+(content.length-1)+"/"+content.length,content.length,hash,new ByteArrayInputStream(content),UUID.randomUUID());UUID job=(UUID)uploads.complete(leader,id,Sql.map("partCount",1,"finalSha256",hash),UUID.randomUUID(),UUID.randomUUID()).get("jobId");assembly.assemble(leader,job,UUID.randomUUID());inspection.inspect(leader,job,UUID.randomUUID());
        var ctx=context(leader,"DRIVE_DOCUMENT",doc,"EDIT",1);var append=Sql.map("uploadSessionId",id.toString(),"reason","版本上传验收");lifecycle.append(ctx,doc,append);lifecycle.append(ctx,doc,append);assertEquals(2,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",doc));
    }
    @Test void zzDesktopFixtureUsesRealCheckedDocuments()throws Exception {
        UUID pdf=published(),officeDoc=officeDocument();
        Path file=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("browser-fixture.private.json");Files.writeString(file,Crypto.json(Sql.map("leaderNo",leaderNo,"staffNo",staffNo,"password",password,"folderId",folder,"departmentId",department,"pdfId",pdf,"officeId",officeDoc)));PrivateFiles.restrict(file);
        assertEquals(2L,documents.documents(leader,Map.of("folderId",folder.toString())).get("total"));
    }
    @Test void folderManagementRejectsEmployeesAndNonEmptyDeletion()throws Exception {
        var body=Sql.map("parentId",folder.toString(),"zone","DEPARTMENT_SHARED","departmentId",department.toString(),"name","空目录");
        assertEquals(403,assertThrows(ApiFault.class,()->access.folder(context(staff,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),body)).status);
        UUID child=(UUID)access.folder(context(leader,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),body).get("id");var reason=Sql.map("reason","目录维护验收");
        assertEquals("RECYCLED",folderLifecycle.change(context(leader,"FOLDER",child,"RECYCLE_FOLDER",1),child,reason,"RECYCLE_FOLDER").get("state"));
        assertEquals("ACTIVE",folderLifecycle.change(context(leader,"FOLDER",child,"RESTORE_FOLDER",2),child,reason,"RESTORE_FOLDER").get("state"));
        published();assertEquals(409,assertThrows(ApiFault.class,()->folderLifecycle.change(context(admin,"FOLDER",folder,"RECYCLE_FOLDER",1),folder,reason,"RECYCLE_FOLDER")).status);
    }
    @Test void permissionFilteredPagingRemainsBoundedWithManyVersions()throws Exception {
        UUID source=published();var object=db.required("SELECT object_id FROM drive.document_version WHERE document_id=?",source);
        new TransactionTemplate(manager).executeWithoutResult(status->{db.update("INSERT INTO drive.document(id,folder_id,publishing_department_id,uploaded_by,name,name_key) SELECT gen_random_uuid(),?,?,?,'分页验收'||n||'.pdf','paging-'||n FROM generate_series(1,1000) n",folder,department,leader.userId());
            db.update("INSERT INTO drive.document_version(id,document_id,version_no,object_id,source_type,created_by) SELECT gen_random_uuid(),id,1,?,'LOCAL_UPLOAD',? FROM drive.document WHERE folder_id=? AND current_version_id IS NULL",object.get("object_id"),leader.userId(),folder);
            db.update("UPDATE drive.document d SET current_version_id=v.id FROM drive.document_version v WHERE v.document_id=d.id AND d.folder_id=? AND d.current_version_id IS NULL",folder);
            db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) SELECT gen_random_uuid(),object_id,'DRIVE_VERSION',id,'BUSINESS' FROM drive.document_version WHERE document_id IN(SELECT id FROM drive.document WHERE folder_id=? AND id<>?)",folder,source);
        });
        new TransactionTemplate(manager).executeWithoutResult(status->{db.update("SET LOCAL statement_timeout='3s'");var page=documents.documents(staff,Map.of("folderId",folder.toString(),"keyword","分页验收","pageSize","20"));assertEquals(1000L,page.get("total"));assertEquals(20,((List<?>)page.get("items")).size());});
    }
    @Test void crossDepartmentDocumentGrantDoesNotExposeParentFolder()throws Exception {
        UUID doc=published(),other=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",other,"X"+other,"外部合成部门");db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",other,staff.userId());db.update("UPDATE drive.folder SET zone='DEPARTMENT_INTERNAL' WHERE id=?",folder);
        access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","VIEW","effect","ALLOW","delegationId",delegation.toString(),"reason","只允许具体文档"));staff=login(staffNo,password);
        assertEquals(403,assertThrows(ApiFault.class,()->policy.requireFolder(staff,folder,"VIEW")).status);assertNotNull(documents.detail(staff,doc));assertEquals(1L,documents.documents(staff,Map.of("departmentId",department.toString())).get("total"));
        var rows=(List<?>)documents.documents(staff,Map.of("departmentId",department.toString())).get("items");var item=(Map<?,?>)rows.getFirst();assertNull(item.get("folderName"));assertEquals("验收负责人",item.get("uploadedByName"));assertNotNull(item.get("createdAt"));
        assertEquals(0L,documents.documents(staff,Map.of("departmentId",department.toString(),"folderName",db.required("SELECT name FROM drive.folder WHERE id=?",folder).get("name").toString())).get("total"));
    }
    @Test void listMetadataShowsOriginalUploaderAndCurrentFolder()throws Exception {
        UUID document=published();var detail=documents.detail(leader,document);
        var page=documents.documents(staff,Map.of("folderId",folder.toString()));var row=(Map<?,?>)((List<?>)page.get("items")).getFirst();
        assertEquals("验收负责人",row.get("uploadedByName"));assertEquals(detail.get("createdAt"),row.get("createdAt"));assertEquals(db.required("SELECT name FROM drive.folder WHERE id=?",folder).get("name"),row.get("folderName"));
        UUID child=(UUID)access.folder(context(leader,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),Sql.map("parentId",folder,"zone","DEPARTMENT_SHARED","departmentId",department,"name","合同资料")).get("id");
        var check=lifecycle.moveCheck(leader,document,child);
        lifecycle.move(context(leader,"DRIVE_DOCUMENT",document,"MOVE",1),document,Sql.map("targetFolderId",child,"confirmationDigest",check.get("confirmationDigest"),"reason","核对列表反映移动后的目录"));
        var results=(List<?>)documents.documents(staff,Map.of("departmentId",department.toString())).get("items");assertEquals(1,results.size());var moved=(Map<?,?>)results.getFirst();
        assertEquals("合同资料",moved.get("folderName"));assertEquals("验收负责人",moved.get("uploadedByName"));assertEquals(detail.get("createdAt"),moved.get("createdAt"));
    }
    /** 多条合成目录引用已检测原件；不复制业务查询逻辑，也不向日常环境写夹具。 */
    private void copies(UUID source,Actor uploader,String prefix,int count,String created,String deleted){
        var original=db.required("SELECT d.*,v.object_id FROM drive.document d JOIN drive.document_version v ON v.id=d.current_version_id WHERE d.id=?",source);
        for(int number=1;number<=count;number++){
            UUID id=UUID.randomUUID();String name=prefix+number+".pdf";
            db.update("INSERT INTO drive.document(id,folder_id,publishing_department_id,uploaded_by,name,name_key,status,created_at,deleted_at,deleted_by) VALUES(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?)",id,original.get("folder_id"),original.get("publishing_department_id"),uploader.userId(),name,name.toLowerCase(Locale.ROOT),deleted==null?"ACTIVE":"RECYCLED",created,deleted,deleted==null?null:leader.userId());
            lifecycle.version(uploader,db.required("SELECT * FROM drive.document WHERE id=?",id),(UUID)original.get("object_id"),null,"LOCAL_UPLOAD");
        }
    }
    @Test void documentFiltersCombineMetadataAndDateBoundaries()throws Exception{
        UUID document=published();String directory=db.required("SELECT name FROM drive.folder WHERE id=?",folder).get("name").toString();
        db.update("UPDATE drive.document SET created_at='2026-11-01T16:00:00Z' WHERE id=?",document);
        copies(document,leader,"筛选验收",25,"2026-11-02T15:59:59.999Z",null);
        copies(document,leader,"日界前验收",1,"2026-11-01T15:59:59.999Z",null);
        copies(document,leader,"次日验收",1,"2026-11-02T16:00:00Z",null);
        copies(document,staff,"员工验收",1,"2026-11-02T01:00:00Z",null);
        Map<String,String> filter=new HashMap<>(Map.of("departmentId",department.toString(),"keyword","验收","fileType","pdf","uploader",leaderNo,"folderName",directory,"uploadedFrom","2026-11-02","uploadedTo","2026-11-02","pageSize","20"));
        var first=documents.documents(staff,filter);assertEquals(26L,first.get("total"));assertEquals(20,((List<?>)first.get("items")).size());assertEquals(true,first.get("hasNext"));
        filter.put("page","2");var second=documents.documents(staff,filter);assertEquals(26L,second.get("total"));assertEquals(6,((List<?>)second.get("items")).size());assertEquals(false,second.get("hasNext"));
        Set<Object> ids=new HashSet<>();for(Object row:(List<?>)first.get("items"))assertTrue(ids.add(((Map<?,?>)row).get("id")));for(Object row:(List<?>)second.get("items"))assertTrue(ids.add(((Map<?,?>)row).get("id")));assertEquals(26,ids.size());
        filter.put("page","1");filter.put("uploader",staffNo);assertEquals(1L,documents.documents(staff,filter).get("total"));filter.put("uploader","验收负责人");assertEquals(26L,documents.documents(staff,filter).get("total"));
        filter.put("folderName","无此目录");assertEquals(0L,documents.documents(staff,filter).get("total"));filter.put("folderName",directory);filter.put("fileType","docx");assertEquals(0L,documents.documents(staff,filter).get("total"));
        assertEquals(400,assertThrows(ApiFault.class,()->documents.documents(staff,Map.of("uploadedFrom","2026-02-30"))).status);
        assertEquals(400,assertThrows(ApiFault.class,()->documents.documents(staff,Map.of("uploadedFrom","2026-11-03","uploadedTo","2026-11-02"))).status);
        access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId(),"resourceType","DOCUMENT","resourceId",document,"action","VIEW","effect","DENY","delegationId",delegation,"reason","筛选不得计入不可见文件"));staff=login(staffNo,password);filter.put("fileType","pdf");assertEquals(25L,documents.documents(staff,filter).get("total"));
        Path fixture=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("filter-browser-fixture.private.json");Files.writeString(fixture,Crypto.json(Sql.map("leaderNo",leaderNo,"password",password,"folderId",folder,"folderName",directory,"departmentId",department,"date","2026-11-02")));PrivateFiles.restrict(fixture);
    }
    /** 内部子目录能从空间入口打开，跨部门共享仍以原有授权为准。 */
    @Test void spaceFiltersUseFolderZoneAndKeepAuthorization()throws Exception{
        UUID source=published();
        UUID object=(UUID)db.required("SELECT object_id FROM drive.document_version WHERE document_id=?",source).get("object_id");
        UUID other=UUID.randomUUID(),internal=UUID.randomUUID(),otherShared=UUID.randomUUID(),otherInternal=UUID.randomUUID(),company=UUID.randomUUID();
        String prefix="空间验收"+UUID.randomUUID();
        db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",other,"X"+other,prefix);
        db.update("""
            INSERT INTO drive.folder(id,parent_id,zone,department_id,folder_level,name,name_key) VALUES
            (?,?,'DEPARTMENT_INTERNAL',?,'CHILD',?,?),
            (?,NULL,'DEPARTMENT_SHARED',?,'TOP',?,?),
            (?,?,'DEPARTMENT_INTERNAL',?,'CHILD',?,?),
            (?,NULL,'COMPANY_SHARED',?,'TOP',?,?)
            """,internal,folder,department,prefix+"内部",prefix+"-internal",
            otherShared,other,prefix+"外部共享",prefix+"-other-shared",
            otherInternal,otherShared,other,prefix+"外部内部",prefix+"-other-internal",
            company,other,prefix+"企业",prefix+"-company");
        for(UUID location:List.of(internal,folder,otherShared,otherInternal,company)){
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO drive.document(id,folder_id,publishing_department_id,uploaded_by,name,name_key) VALUES(?,?,?,?,?,?)",
                    id,location,List.of(internal,folder).contains(location)?department:other,leader.userId(),prefix+".pdf",prefix);
            lifecycle.version(leader,db.required("SELECT * FROM drive.document WHERE id=?",id),object,null,"LOCAL_UPLOAD");
        }
        for(var entry:Map.of("OWN_INTERNAL",internal,"COMPANY_SHARED",company,"OTHER_DEPARTMENT_SHARED",otherShared).entrySet()){
            var page=documents.documents(staff,Map.of("space",entry.getKey(),"keyword",prefix));
            assertEquals(1L,page.get("total"));
            assertEquals(entry.getValue(),((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("folderId"));
        }
        assertEquals(4L,documents.documents(staff,Map.of("space","ALL","keyword",prefix)).get("total"));
        var shared=Map.of("space","OTHER_DEPARTMENT_SHARED","keyword",prefix,"departmentId",other.toString());
        assertEquals(1L,documents.documents(staff,shared).get("total"));
        assertEquals(0L,documents.documents(staff,Map.of("space","OTHER_DEPARTMENT_SHARED","keyword",prefix,"departmentId",department.toString())).get("total"));
        assertEquals(403,assertThrows(ApiFault.class,()->documents.documents(staff,Map.of("space","OTHER_DEPARTMENT_SHARED","folderId",otherInternal.toString()))).status);
        assertEquals(400,assertThrows(ApiFault.class,()->documents.documents(staff,Map.of("space","INVALID"))).status);
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(HttpBoundary.ACTOR,staff);
        var internalFolders=(Map<?,?>)driveController.folders(Map.of("space","OWN_INTERNAL"),request).get("data");
        var internalRoot=(Map<?,?>)((List<?>)internalFolders.get("items")).getFirst();
        assertEquals(1L,internalFolders.get("total"));
        assertEquals(internal,internalRoot.get("id"));
        assertEquals(folder,internalRoot.get("parentId"));
        var sharedFolders=(Map<?,?>)driveController.folders(Map.of("space","OTHER_DEPARTMENT_SHARED","departmentId",other.toString()),request).get("data");
        var sharedRoot=(Map<?,?>)((List<?>)sharedFolders.get("items")).getFirst();
        assertEquals(otherShared,sharedRoot.get("id"));
        assertEquals(prefix,sharedRoot.get("departmentName"));
    }
    @Test void recycleFiltersApplyToFilesAndFoldersAndPageCounts()throws Exception{
        UUID document=published();lifecycle.recycle(context(leader,"DRIVE_DOCUMENT",document,"DELETE",1),document,Sql.map("reason","筛选回收项目"));
        db.update("UPDATE drive.document SET deleted_at='2026-09-01T16:00:00Z' WHERE id=?",document);
        copies(document,leader,"回收验收文件",25,"2026-08-01T00:00:00Z","2026-09-02T15:59:59.999Z");
        copies(document,leader,"保留验收文件",1,"2026-08-01T00:00:00Z","2026-11-02T01:00:00Z");
        Map<String,String> filter=new HashMap<>(Map.of("keyword","验收","deletedFrom","2026-09-02","deletedTo","2026-09-02","retention","ELAPSED","pageSize","20"));
        var first=lifecycle.recycleList(leader,filter);assertEquals(26L,first.get("total"));assertEquals(20,((List<?>)first.get("items")).size());filter.put("page","2");assertEquals(6,((List<?>)lifecycle.recycleList(leader,filter).get("items")).size());
        assertEquals(0L,lifecycle.recycleList(staff,filter).get("total"));assertEquals(1L,lifecycle.recycleList(leader,Map.of("retention","RETAINED")).get("total"));
        UUID child=(UUID)access.folder(context(leader,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),Sql.map("parentId",folder,"zone","DEPARTMENT_SHARED","departmentId",department,"name","回收验收目录")).get("id");
        folderLifecycle.change(context(leader,"FOLDER",child,"RECYCLE_FOLDER",1),child,Sql.map("reason","目录筛选验收"),"RECYCLE_FOLDER");db.update("UPDATE drive.folder SET deleted_at='2026-09-02T00:00:00Z' WHERE id=?",child);
        filter.put("page","1");assertEquals(1L,folderLifecycle.recycledFolders(leader,filter).get("total"));assertEquals(0L,folderLifecycle.recycledFolders(staff,filter).get("total"));
        assertEquals(400,assertThrows(ApiFault.class,()->lifecycle.recycleList(leader,Map.of("retention","INVALID"))).status);assertEquals(400,assertThrows(ApiFault.class,()->folderLifecycle.recycledFolders(leader,Map.of("deletedFrom","invalid"))).status);
    }
}
