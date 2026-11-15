package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** 仅显式独立库验收：真实领域授权、原生ClamAV测试签名、真实PDF进程和签名保护检查。 */
@EnabledIfEnvironmentVariable(named="IMS_DRIVE_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class},webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false"})
class DriveReadIntegrationTest {
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
    @Autowired DriveAccessService access;@Autowired DriveFilePolicy policy;@Autowired PlatformTransactionManager manager;
    /** 每项测试创建独立部门、员工、委托、对象和阅读会话，不回收历史测试数据。 */
    private Actor admin,leader,staff;private UUID department,folder,delegation;private String leaderNo,staffNo,password;
    /** 合成命中样本不包含病毒或EICAR，只匹配测试目录内自定义完整摘要。 */
    private static byte[] suspicious() {return "%PDF-1.4\nIMS-INTEGRATION-SIGNATURE-ONLY".getBytes(StandardCharsets.UTF_8);}
    private static String digest(byte[] bytes,String algorithm) {try{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    /** 验收必须明确落在本机新平台专用库，配置中的密码不写输出。 */
    @BeforeEach void fixture() throws Exception {
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/ims_platform_(dev|test|trial)[a-z0-9_]*"));
        NativeScanner.DOWN.set(false);NativeScanner.scanStarted=null;NativeScanner.scanRelease=null;admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence("NORMAL",false);
        department=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",department,"T"+department,"合成验收部门"+department.toString().substring(0,8));
        password=Crypto.token();leaderNo="L"+UUID.randomUUID();staffNo="S"+UUID.randomUUID();
        UUID leaderId=person(leaderNo,"验收负责人","DEPARTMENT_MANAGER"),staffId=person(staffNo,"验收员工","STAFF");
        db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'合成验收',?)",UUID.randomUUID(),department,leaderId,admin.userId());
        folder=(UUID)access.folder(context(admin,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),Sql.map("zone","DEPARTMENT_SHARED","departmentId",department.toString(),"name","验收资料"+UUID.randomUUID())).get("id");
        UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='DRIVE' AND code='DEPARTMENT_MANAGER'").get("id");
        delegation=(UUID)access.delegate(context(admin,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DELEGATION",null),Sql.map("departmentId",department.toString(),"folderId",folder.toString(),"roleId",role.toString(),"allowedActions",List.of("VIEW","UPLOAD","DOWNLOAD"),"reason","合成验收委托")).get("id");
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
        return upload(actor,content,"合成验收.pdf","application/pdf");
    }
    private UUID upload(Actor actor,byte[] content,String name,String media) {
        String hash=digest(content,"SHA-256");UUID request=UUID.randomUUID();
        UUID id=(UUID)uploads.create(actor,Sql.map("module","DRIVE","businessContextId",folder.toString(),"originalName",name,"expectedSize",content.length,"expectedSha256",hash,"expectedMediaType",media,"purpose","DRIVE_FILE"),UUID.randomUUID(),request).get("uploadId");
        uploads.part(actor,id,1,"bytes 0-"+(content.length-1)+"/"+content.length,content.length,hash,new ByteArrayInputStream(content),request);
        UUID job=(UUID)uploads.complete(actor,id,Sql.map("partCount",1,"finalSha256",hash),UUID.randomUUID(),request).get("jobId");assembly.assemble(actor,job,request);inspection.inspect(actor,job,request);return id;
    }
    private UUID published() throws Exception {UUID upload=upload(leader,Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF"))));return (UUID)documents.publish(leader,Sql.map("folderId",folder.toString(),"uploadSessionId",upload.toString(),"name","已发布验收.pdf"),UUID.randomUUID(),UUID.randomUUID()).get("id");}
    private UUID preview(Actor actor,UUID document) {UUID job=(UUID)reading.create(actor,document,Map.of(),UUID.randomUUID(),UUID.randomUUID()).get("jobId");reading.prepare(actor,job,UUID.randomUUID());return Inputs.uuid(db.required("SELECT business_ref FROM ops.job WHERE id=?",job).get("business_ref"));}

    /** 实际扫描、正式发布、隔离PDF页数与PNG交付贯通，原件保持原摘要。 */
    @Test void cleanFilePublishesAndDeliversOnlyAuthorizedPng() throws Exception {
        UUID doc=published(),id=preview(staff,doc);var manifest=reading.manifest(staff,id);assertEquals("READY",manifest.get("state"));assertTrue(((Number)manifest.get("pageCount")).intValue()>0);
        byte[] png=reading.page(staff,id,1,UUID.randomUUID());assertArrayEquals(new byte[]{(byte)137,80,78,71},Arrays.copyOf(png,4));
        assertEquals(416,assertThrows(ApiFault.class,()->reading.page(staff,id,10000,UUID.randomUUID())).status);
        assertEquals(404,assertThrows(ApiFault.class,()->reading.manifest(leader,id)).status);
        assertTrue(db.count("SELECT count(*) FROM ops.audit_event WHERE resource_id=? AND action='PAGE_AUTHORIZED'",id)>0);
        assertEquals(403,assertThrows(ApiFault.class,()->policy.requireFolder(admin,folder,"UPLOAD")).status);
        // 后续HTTPS和电脑端验收只复用本项真实检测通过的合成文档，测试凭据置于私有目录。
        Path fixture=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("read-fixture.private.json");
        Files.writeString(fixture,Crypto.json(Sql.map("folderId",folder.toString(),"documentId",doc.toString(),"employeeNo",leaderNo,"password",password)));PrivateFiles.restrict(fixture);
    }
    /** 图片可以只授予VIEW；与PDF相同的撤权边界不因新的渲染路径而失效。 */
    @Test void imagePreviewDoesNotRequireDownloadAndRevocationStopsDelivery() throws Exception {
        var image=new java.awt.image.BufferedImage(60,40,java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();
        try {graphics.setColor(java.awt.Color.GREEN);graphics.fillRect(0,0,60,40);}finally {graphics.dispose();}
        var output=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);
        UUID uploaded=upload(leader,output.toByteArray(),"受控图片.png","image/png");
        UUID doc=(UUID)documents.publish(leader,Sql.map("folderId",folder.toString(),"uploadSessionId",uploaded.toString(),"name","受控图片.png"),UUID.randomUUID(),UUID.randomUUID()).get("id");
        access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","DOWNLOAD","effect","DENY","delegationId",delegation.toString(),"reason","仅允许受控阅读图片"));
        staff=login(staffNo,password);assertEquals(403,assertThrows(ApiFault.class,()->policy.requireDocument(staff,doc,"DOWNLOAD")).status);
        UUID id=preview(staff,doc);assertEquals(1,reading.manifest(staff,id).get("pageCount"));
        var rendered=javax.imageio.ImageIO.read(new ByteArrayInputStream(reading.page(staff,id,1,UUID.randomUUID())));assertEquals(60,rendered.getWidth());assertEquals(java.awt.Color.GREEN.getRGB(),rendered.getRGB(5,5));
        Actor previous=staff;access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","VIEW","effect","DENY","delegationId",delegation.toString(),"reason","撤销图片阅读"));
        assertEquals(401,assertThrows(ApiFault.class,()->reading.page(previous,id,1,UUID.randomUUID())).status);
    }
    /** 真实检测器不可用时不发布；恢复后明确重试并保留失败与成功两次尝试。 */
    @Test void scannerFailureStaysPendingThenExplicitRetryCanComplete() throws Exception {
        NativeScanner.DOWN.set(true);UUID id=upload(leader,Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF"))));var row=uploads.get(leader,id);UUID job=(UUID)row.get("jobId");
        assertEquals("CHECK_PENDING",row.get("state"));assertEquals("SCANNER_UNAVAILABLE",uploads.job(leader,job).get("errorCode"));
        assertEquals(404,assertThrows(ApiFault.class,()->documents.publish(leader,Sql.map("folderId",folder.toString(),"uploadSessionId",id.toString(),"name","不可发布.pdf"),UUID.randomUUID(),UUID.randomUUID())).status);
        NativeScanner.DOWN.set(false);uploads.retry(leader,job,UUID.randomUUID());inspection.inspect(leader,job,UUID.randomUUID());assertEquals("COMPLETE",uploads.get(leader,id).get("state"));assertEquals(2,db.count("SELECT count(*) FROM ops.job_attempt WHERE job_id=?",job));
    }
    /** 原生ClamAV命中无害测试签名后隔离；不允许通过重试把明确拒绝洗成通过。 */
    @Test void suspiciousContentIsQuarantinedAndCannotRetry() {
        UUID id=upload(leader,suspicious());var row=uploads.get(leader,id);assertEquals("REJECTED",row.get("state"));
        assertEquals("QUARANTINED",db.required("SELECT state FROM storage.object WHERE id=?",row.get("objectId")).get("state"));
        assertEquals(409,assertThrows(ApiFault.class,()->uploads.retry(leader,(UUID)row.get("jobId"),UUID.randomUUID())).status);
    }
    /** 当前负责人撤销本人明确上传授权，独立账本与登录代次立即作废旧页面会话。 */
    @Test void revokedGrantInvalidatesExistingPreviewAndUploadRights() throws Exception {
        UUID grant=(UUID)access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","FOLDER","resourceId",folder.toString(),"action","UPLOAD","effect","ALLOW","delegationId",delegation.toString(),"reason","合成验收个人上传")).get("id");
        staff=login(staffNo,password);UUID doc=published(),id=preview(staff,doc);Actor old=staff;
        access.revokeGrant(context(leader,"DRIVE_GRANT",grant,"REVOKE_GRANT",1),grant);
        assertEquals(401,assertThrows(ApiFault.class,()->reading.page(old,id,1,UUID.randomUUID())).status);
        staff=login(staffNo,password);assertEquals(403,assertThrows(ApiFault.class,()->policy.requireFolder(staff,folder,"UPLOAD")).status);
    }
    /** 新增文档明确拒绝覆盖共享区默认读取，旧预览和列表均不可继续泄露。 */
    @Test void explicitDocumentDenyWinsAndRemovesListEntry() throws Exception {
        UUID doc=published(),id=preview(staff,doc);Actor old=staff;
        access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId().toString(),"resourceType","DOCUMENT","resourceId",doc.toString(),"action","VIEW","effect","DENY","delegationId",delegation.toString(),"reason","合成验收禁止阅读"));
        assertEquals(401,assertThrows(ApiFault.class,()->reading.page(old,id,1,UUID.randomUUID())).status);staff=login(staffNo,password);
        assertEquals(403,assertThrows(ApiFault.class,()->documents.detail(staff,doc)).status);assertEquals(0L,documents.documents(staff,Map.of("folderId",folder.toString())).get("total"));
    }
    /** 主副本正常但第二副本损坏时也不能交付页面或追加成功阅读审计。 */
    @Test void secondaryReplicaCorruptionBlocksPageDelivery() throws Exception {
        UUID doc=published(),id=preview(staff,doc);var object=db.required("SELECT o.* FROM storage.preview_session p JOIN storage.object o ON o.id=p.object_id WHERE p.id=?",id);
        Path secondary=Path.of(System.getenv("IMS_PLATFORM_STORAGE_SECONDARY")).resolve((String)object.get("storage_key"));assertTrue(secondary.normalize().startsWith(Path.of(System.getenv("IMS_DRIVE_TEST_ROOT"))));
        Files.write(secondary,new byte[]{1},StandardOpenOption.APPEND);
        assertEquals(503,assertThrows(ApiFault.class,()->reading.page(staff,id,1,UUID.randomUUID())).status);assertEquals(0,db.count("SELECT count(*) FROM ops.audit_event WHERE resource_id=? AND action='PAGE_AUTHORIZED'",id));
    }
    /** 证据过期阻断正文，只读模式允许已核验版本读取但拒绝新发布。 */
    @Test void expiredProtectionBlocksPagesAndReadOnlyBlocksWrites() throws Exception {
        UUID doc=published(),id=preview(staff,doc);evidence("NORMAL",true);assertEquals(503,assertThrows(ApiFault.class,()->reading.page(staff,id,1,UUID.randomUUID())).status);
        evidence("ALL_READ_ONLY",false);assertTrue(reading.page(staff,id,1,UUID.randomUUID()).length>0);
        assertEquals(503,assertThrows(ApiFault.class,()->upload(leader,new byte[]{1})).status);
    }
    /** 撤销委托阻断依赖该委托的个人上传，不保留旧负责人或旧授权的通行权。 */
    @Test void revokedDelegationDisablesCurrentManagerImmediately() {
        access.revokeDelegation(context(admin,"DRIVE_DELEGATION",delegation,"REVOKE_DELEGATION",1),delegation);
        Actor current=login(leaderNo,password);assertEquals(403,assertThrows(ApiFault.class,()->policy.requireFolder(current,folder,"UPLOAD")).status);
    }
    /** 扫描进程尚未返回时撤销委托，最后提交必须再次核权且不能产生READY对象。 */
    @Test void revocationDuringScanningCannotCommitCleanResult() throws Exception {
        NativeScanner.scanStarted=new java.util.concurrent.CountDownLatch(1);NativeScanner.scanRelease=new java.util.concurrent.CountDownLatch(1);
        byte[] pdf=Files.readAllBytes(Path.of(System.getenv("IMS_DRIVE_TEST_PDF")));
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var task=pool.submit(()->upload(leader,pdf));
            try {assertTrue(NativeScanner.scanStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));access.revokeDelegation(context(admin,"DRIVE_DELEGATION",delegation,"REVOKE_DELEGATION",1),delegation);}
            finally {NativeScanner.scanRelease.countDown();}
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->task.get(10,java.util.concurrent.TimeUnit.SECONDS));assertInstanceOf(ApiFault.class,failure.getCause());assertEquals(401,((ApiFault)failure.getCause()).status);
        }
        assertEquals(0,db.count("SELECT count(*) FROM storage.upload_session s JOIN storage.object o ON o.id=s.object_id WHERE s.business_context_id=? AND o.state='READY'",folder));
    }
}
