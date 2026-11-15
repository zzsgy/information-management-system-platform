package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 必须连接真实本机文档服务与新隔离库；测试检测器仅替换无害签名库，仍执行原生进程。 */
@EnabledIfEnvironmentVariable(named="IMS_OFFICE_STABILITY_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class},webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties={"spring.config.name=platform","platform.file-worker.enabled=false","platform.scan-worker.enabled=false"})
@TestMethodOrder(MethodOrderer.MethodName.class)
class OfficeStabilityIntegrationTest {
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired DriveAccessService access;@Autowired DriveDocumentService documents;
    @Autowired UploadService uploads;@Autowired UploadAssembly assembly;@Autowired FileInspectionService inspection;@Autowired DriveLifecycle lifecycle;
    @Autowired OfficeConversions conversions;@Autowired OfficeGateway gateway;
    private Actor admin,leader;private UUID department,folder,delegation;private String password,leaderNo,staffNo,viewerNo;private ScheduledExecutorService evidenceTimer;

    @BeforeEach void fixture()throws Exception{
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:55467/ims_platform_dev_[0-9]+"));assertEquals("18467",System.getenv("IMS_PLATFORM_PORT"));
        DriveReadIntegrationTest.NativeScanner.DOWN.set(false);DriveReadIntegrationTest.NativeScanner.scanStarted=null;DriveReadIntegrationTest.NativeScanner.scanRelease=null;
        admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence();evidenceTimer=Executors.newSingleThreadScheduledExecutor();evidenceTimer.scheduleAtFixedRate(()->{try{evidence();}catch(Exception failure){throw new IllegalStateException(failure);}},10,10,TimeUnit.SECONDS);
        department=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",department,"O"+department,"Office联调部门"+department.toString().substring(0,8));password="123456";leaderNo="OL"+UUID.randomUUID();staffNo="OS"+UUID.randomUUID();
        viewerNo="OV"+UUID.randomUUID();person(viewerNo,"Office只读员工","STAFF");UUID leaderId=person(leaderNo,"Office负责人","DEPARTMENT_MANAGER");person(staffNo,"Office协作员工","STAFF");db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'Office本机隔离联调',?)",UUID.randomUUID(),department,leaderId,admin.userId());
        folder=(UUID)access.folder(context(admin,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),Sql.map("zone","DEPARTMENT_SHARED","departmentId",department,"name","Office联调资料"+UUID.randomUUID())).get("id");UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='DRIVE' AND code='DEPARTMENT_MANAGER'").get("id");
        delegation=(UUID)access.delegate(context(admin,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DELEGATION",null),Sql.map("departmentId",department,"folderId",folder,"roleId",role,"allowedActions",List.of("VIEW","DOWNLOAD","UPLOAD","EDIT","DELETE","MOVE","RESTORE","PURGE","FOLDER_MANAGE","COLLABORATE"),"reason","Office真实服务联调")).get("id");
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','Office隔离检测','DRIVE',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope",Inputs.uuid(System.getenv("IMS_PLATFORM_SCANNER_SERVICE_ID")),"{"+department+"}");
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'OFFICE','ONLYOFFICE本机联调','DRIVE',?::uuid[],'{VIEW,EDIT}','isolated-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope,allowed_actions=EXCLUDED.allowed_actions",gateway.service(),"{"+department+"}");
        leader=login(leaderNo,password);gateway.verifyVersion();
    }
    @AfterEach void finish(){if(evidenceTimer!=null)evidenceTimer.shutdownNow();DriveReadIntegrationTest.NativeScanner.DOWN.set(false);DriveReadIntegrationTest.NativeScanner.scanStarted=null;DriveReadIntegrationTest.NativeScanner.scanRelease=null;}
    private UUID person(String no,String name,String role){UUID id=UUID.randomUUID();db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,name,department,no.toLowerCase(Locale.ROOT)+"@biolink.com",Crypto.password(password));UUID roleId=(UUID)db.required("SELECT id FROM iam.role WHERE module='DRIVE' AND code=?",role).get("id");db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,'DRIVE',?)",UUID.randomUUID(),id,roleId,department,admin.userId(),role);return id;}
    private Actor login(String no,String secret){return identity.authenticate((String)identity.login(Sql.map("employeeNo",no,"password",secret),UUID.randomUUID(),"office-real-integration").get("token"));}
    private Commands.Context context(Actor actor,String type,UUID id,String action,Integer version){var proof=identity.reauthenticate(actor,Sql.map("password",actor.userId().equals(admin.userId())?System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"):password,"purpose","MANAGE","objectIds",List.of(id.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",id,"actions",List.of(action)))),UUID.randomUUID());return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST OFFICE "+action,(String)proof.get("verificationToken"),version);}
    private void evidence()throws Exception{Path key=Path.of(System.getenv("IMS_PLATFORM_PROTECTION_KEY"));if(!Files.exists(key)){Files.writeString(key,Crypto.token(),StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(key);}Instant now=Instant.now();var body=Sql.map("instanceId",db.required("SELECT id FROM platform_meta.instance").get("id").toString(),"nodeId",System.getenv("IMS_PLATFORM_NODE_ID"),"epoch",1,"issuedAt",now.toString(),"expiresAt",now.plusSeconds(60).toString(),"mode","NORMAL","oldPrimaryFenced",true,"replicaVerifiedAt",now.toString(),"backupId",UUID.randomUUID().toString(),"backupProtectedAt",now.toString(),"writableModules",List.of("DRIVE","ARCHIVE","PLATFORM"));body.put("signature",Crypto.hmac(Files.readString(key),Crypto.json(body)));Path finalFile=Path.of(System.getenv("IMS_PLATFORM_PROTECTION_EVIDENCE")),next=finalFile.resolveSibling("office-test-evidence.next");Files.writeString(next,Crypto.json(body));Files.move(next,finalFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private Path sample(String extension){return Path.of(System.getenv("IMS_OFFICE_TEST_SAMPLES")).resolve("synthetic."+extension);}
    private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private UUID publish(String extension)throws Exception{Path path=sample(extension);byte[] content=Files.readAllBytes(path);String digest=hash(content),name=(Set.of("doc","xls","ppt").contains(extension)?"旧格式联调原件.":"联调原件.")+extension;UUID request=UUID.randomUUID();UUID upload=(UUID)uploads.create(leader,Sql.map("module","DRIVE","businessContextId",folder,"originalName",name,"expectedSize",content.length,"expectedSha256",digest,"expectedMediaType","application/octet-stream","purpose","DRIVE_FILE"),UUID.randomUUID(),request).get("uploadId");uploads.part(leader,upload,1,"bytes 0-"+(content.length-1)+"/"+content.length,content.length,digest,new ByteArrayInputStream(content),request);UUID job=(UUID)uploads.complete(leader,upload,Sql.map("partCount",1,"finalSha256",digest),UUID.randomUUID(),request).get("jobId");assembly.assemble(leader,job,request);inspection.inspect(leader,job,request);return (UUID)documents.publish(leader,Sql.map("folderId",folder,"uploadSessionId",upload,"name",name),UUID.randomUUID(),request).get("id");}
    private UUID version(UUID document){return (UUID)db.required("SELECT current_version_id FROM drive.document WHERE id=?",document).get("current_version_id");}

    /** 只统计本批独立存储根目录的实际文件，校验数据库之外的双盘副作用。 */
    private long copyCount(String setting)throws IOException{try(var paths=Files.list(Path.of(System.getenv(setting)))){return paths.filter(Files::isRegularFile).count();}}
    /** 同一版本并发预览必须共享结果，不在双盘留下未登记副本。 */
    @Test void aConcurrentPreviewDoesNotLeakCopies() throws Exception {
        UUID document=publish("docx"),v=version(document);
        long primaryBefore=copyCount("IMS_PLATFORM_STORAGE_PRIMARY"),secondaryBefore=copyCount("IMS_PLATFORM_STORAGE_SECONDARY");
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {var tasks=new ArrayList<Future<UUID>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{barrier.await();return conversions.previewPdf(leader,document,v,UUID.randomUUID());}));
            assertEquals(tasks.get(0).get(180,TimeUnit.SECONDS),tasks.get(1).get(180,TimeUnit.SECONDS));
            assertEquals(1,copyCount("IMS_PLATFORM_STORAGE_PRIMARY")-primaryBefore,"主副本目录不能遗留未引用文件");
            assertEquals(1,copyCount("IMS_PLATFORM_STORAGE_SECONDARY")-secondaryBefore,"第二副本目录不能遗留未引用文件");
            assertEquals(1,db.count("SELECT count(*) FROM drive.office_conversion WHERE document_id=? AND state='READY'",document));
        }finally{pool.shutdownNow();}
    }
    /** 检测器不可用时不登记派生对象、不留下双盘原件；恢复后仍能重试同一版本。 */
    @Test void bFailedConversionCanRetryWithoutLeakingCopies()throws Exception {
        UUID doc=publish("xlsx"),v=version(doc);
        long primaryBefore=copyCount("IMS_PLATFORM_STORAGE_PRIMARY"),secondaryBefore=copyCount("IMS_PLATFORM_STORAGE_SECONDARY");
        DriveReadIntegrationTest.NativeScanner.DOWN.set(true);
        try{assertEquals(503,assertThrows(ApiFault.class,()->conversions.previewPdf(leader,doc,v,UUID.randomUUID())).status);}
        finally{DriveReadIntegrationTest.NativeScanner.DOWN.set(false);}
        assertEquals(0,db.count("SELECT count(*) FROM drive.office_conversion WHERE document_id=? AND state='READY'",doc));
        assertEquals(primaryBefore,copyCount("IMS_PLATFORM_STORAGE_PRIMARY"));assertEquals(secondaryBefore,copyCount("IMS_PLATFORM_STORAGE_SECONDARY"));
        assertNotNull(conversions.previewPdf(leader,doc,v,UUID.randomUUID()));
        assertEquals(1,db.count("SELECT count(*) FROM drive.office_conversion WHERE document_id=? AND state='READY'",doc));
        assertEquals(1,copyCount("IMS_PLATFORM_STORAGE_PRIMARY")-primaryBefore);assertEquals(1,copyCount("IMS_PLATFORM_STORAGE_SECONDARY")-secondaryBefore);
    }
    /** 合并转换请求不能改变旧格式的不可变原件，也不能让同一业务操作重复增版。 */
    @Test void cLegacyFormatsRemainIdempotent()throws Exception {
        for(String extension:List.of("doc","xls","ppt")){
            UUID document=publish(extension),original=version(document);
            var source=lifecycle.download(leader,document,original);
            var command=context(leader,"DRIVE_DOCUMENT",document,"EDIT",1);
            var body=Sql.map("sourceVersionId",original,"reason","并发修复后的旧格式回归");
            conversions.convert(command,document,body);UUID converted=version(document);
            conversions.convert(command,document,body);
            assertNotEquals(original,converted);assertEquals(converted,version(document));
            assertEquals(2,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",document));
            assertEquals(source.get("sha256"),lifecycle.download(leader,document,original).get("sha256"));
        }
    }
    /** 缓存命中仍校验第二副本；真实追加损坏后阻断，恢复原字节后可读。 */
    @Test void dCachedPreviewRejectsReplicaMismatch()throws Exception {
        UUID document=publish("pptx"),v=version(document),object=conversions.previewPdf(leader,document,v,UUID.randomUUID());
        var row=db.required("SELECT storage_key FROM storage.object WHERE id=?",object);
        Path secondary=Path.of(System.getenv("IMS_PLATFORM_STORAGE_SECONDARY")).resolve((String)row.get("storage_key"));
        byte[] original=Files.readAllBytes(secondary);
        try{Files.write(secondary,new byte[]{1},StandardOpenOption.APPEND);assertThrows(ApiFault.class,()->conversions.previewPdf(leader,document,v,UUID.randomUUID()));}
        finally{Files.write(secondary,original);}
        assertEquals(object,conversions.previewPdf(leader,document,v,UUID.randomUUID()));
    }
    /** 已生成的缓存不能绕过撤权；员工重新登录后也必须按当前个人拒绝判断。 */
    @Test void eCachedPreviewRejectsRevokedAccess()throws Exception {
        UUID document=publish("docx"),v=version(document);Actor staff=login(staffNo,password);
        assertNotNull(conversions.previewPdf(staff,document,v,UUID.randomUUID()));
        access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId(),"resourceType","DOCUMENT","resourceId",document,"action","VIEW","effect","DENY","delegationId",delegation,"reason","并发修复后的撤权回归"));
        assertThrows(ApiFault.class,()->conversions.previewPdf(staff,document,v,UUID.randomUUID()));
        Actor current=login(staffNo,password);
        assertEquals(403,assertThrows(ApiFault.class,()->conversions.previewPdf(current,document,v,UUID.randomUUID())).status);
    }
    /** 最后创建专用浏览器账号和目录，后续真实HTTP压力只操作这些合成资料。 */
    @Test void zBrowserFixture()throws Exception {
        // 每个真实编辑器使用独立员工，遵守正式系统单账号单会话约束。
        var loadAccounts=new ArrayList<Map<String,Object>>();for(int n=0;n<20;n++){String no="OC"+UUID.randomUUID();loadAccounts.add(Sql.map("employeeNo",no,"id",person(no,"并发验收员工"+(n+1),"STAFF")));}
        Map<String,Object> output=Sql.map("scope","LOCAL_SYNTHETIC_ONLY","baseUrl","https://127.0.0.1:18467","leaderNo",leaderNo,"staffNo",staffNo,"viewerNo",viewerNo,"loadAccounts",loadAccounts,"password",password,"folderId",folder,"delegationId",delegation);Path target=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("browser-fixture.private.json");Files.writeString(target,Crypto.json(output));PrivateFiles.restrict(target);}
}
