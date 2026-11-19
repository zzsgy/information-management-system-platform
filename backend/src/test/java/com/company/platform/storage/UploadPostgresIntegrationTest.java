package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** 仅显式启用的本机独立 PostgreSQL 集成验收；允许策略只存在于测试源码。 */
@EnabledIfEnvironmentVariable(named="IMS_STORAGE_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,UploadPostgresIntegrationTest.TestPolicies.class},webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={"spring.config.name=platform","server.ssl.enabled=false"})
class UploadPostgresIntegrationTest {
    /** 测试替身可以模拟权限或保护状态在请求之间收紧。 */
    @TestConfiguration(proxyBeanMethods=false)
    static class TestPolicies {
        static final AtomicBoolean ALLOWED=new AtomicBoolean(true), PROTECTED=new AtomicBoolean(true);
        @Bean StorageDomainPolicy testDomain() { return new StorageDomainPolicy() {
            public boolean supports(String module,String purpose) { return module.equals("PLATFORM") && purpose.equals("EVIDENCE"); }
            public void require(Actor actor,String module,String purpose,UUID context,String action) { if(!ALLOWED.get()) throw ApiFault.denied(); }
        }; }
        @Bean StorageProtectionPolicy testProtection() { return (module,action)-> { if(!PROTECTED.get()) throw SystemFailure.PROTECTION_WRITE_BLOCKED.fault(); }; }
    }
    @Autowired UploadService uploads;
    @Autowired UploadAssembly assembly;
    @Autowired IdentityService identity;
    @Autowired Sql db;
    @Autowired PlatformTransactionManager transactions;
    /** 只接受环境准备脚本生成的专用测试库；用户和口令不输出。 */
    private Actor actor() {
        String url=System.getenv("IMS_PLATFORM_DB_URL");
        assertNotNull(url); assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/ims_platform_(dev|test|trial)[a-z0-9_]*"));
        var result=identity.login(Sql.map("employeeNo",System.getenv("IMS_PLATFORM_ADMIN_NO"),"password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD")),UUID.randomUUID(),"integration-test");
        return identity.authenticate((String)result.get("token"));
    }
    /** 每次均创建独立随机业务上下文和操作标识，不覆盖既有对象。 */
    private Map<String,Object> body(byte[] content) {
        // 组件测试使用独立凭据上下文，避免覆盖已接入的真实档案领域策略。
        return Sql.map("module","PLATFORM","businessContextId",UUID.randomUUID().toString(),"originalName","验收.pdf","expectedSize",content.length,"expectedSha256",hash(content),"expectedMediaType","application/pdf","purpose","EVIDENCE");
    }
    private String hash(byte[] content) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); } catch(Exception e) { throw new IllegalStateException(e); } }
    @BeforeEach void enablePolicies() { TestPolicies.ALLOWED.set(true); TestPolicies.PROTECTED.set(true); }

    /** 实际事务验证幂等、分片实测、完整接收、待检测任务及当前权限变化。 */
    @Test void receivesAndSubmitsWithoutPretendingDetectionPassed() {
        Actor person=actor(); byte[] bytes="%PDF-隔离测试文件".getBytes(StandardCharsets.UTF_8); String sha=hash(bytes);
        var input=body(bytes); UUID operation=UUID.randomUUID(),request=UUID.randomUUID();
        UUID id=(UUID)uploads.create(person,input,operation,request).get("uploadId");
        assertEquals(id,uploads.create(person,input,operation,request).get("uploadId"));
        // 已固定的文件声明不能被后台意外改写。
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE storage.upload_session SET expected_size=expected_size+1 WHERE id=?",id));
        var changed=new LinkedHashMap<>(input); changed.put("originalName","另一个.pdf");
        assertEquals(409,assertThrows(ApiFault.class,()->uploads.create(person,changed,operation,request)).status);
        String range="bytes 0-"+(bytes.length-1)+"/"+bytes.length;
        assertEquals(422,assertThrows(ApiFault.class,()->uploads.part(person,id,1,range,bytes.length,"0".repeat(64),new ByteArrayInputStream(bytes),request)).status);
        assertEquals(true,uploads.part(person,id,1,range,bytes.length,sha,new ByteArrayInputStream(bytes),request).get("accepted"));
        assertEquals(true,uploads.part(person,id,1,range,bytes.length,sha,new ByteArrayInputStream(bytes),request).get("accepted"));
        byte[] different=bytes.clone(); different[different.length-1]^=1;
        assertEquals(409,assertThrows(ApiFault.class,()->uploads.part(person,id,1,range,different.length,hash(different),new ByteArrayInputStream(different),request)).status);
        assertEquals(1,db.count("SELECT count(*) FROM storage.upload_part WHERE upload_session_id=?",id));
        UUID finish=UUID.randomUUID(); var task=uploads.complete(person,id,Sql.map("partCount",1,"finalSha256",sha),finish,request);
        assertEquals("PENDING",task.get("state")); assertEquals(task.get("jobId"),uploads.complete(person,id,Sql.map("partCount",1,"finalSha256",sha),finish,request).get("jobId"));
        assertEquals("CHECK_PENDING",uploads.get(person,id).get("state"));
        UUID object=assembly.assemble(person,(UUID)task.get("jobId"),request);
        assertEquals("CHECK_PENDING",db.required("SELECT state FROM storage.object WHERE id=?",object).get("state"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE storage.object SET sha256=? WHERE id=?","0".repeat(64),object));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("DELETE FROM storage.upload_part WHERE upload_session_id=?",id));
        assertEquals("FILE_CHECK",uploads.job(person,(UUID)task.get("jobId")).get("jobType"));
        // 只断言本次上传，不能把隔离库里其他已通过检测的测试对象视为失败。
        assertEquals(0,db.count("SELECT count(*) FROM storage.object WHERE id=? AND state='READY'",object));
        TestPolicies.ALLOWED.set(false);
        assertEquals(403,assertThrows(ApiFault.class,()->uploads.get(person,id)).status);
        assertEquals(403,assertThrows(ApiFault.class,()->uploads.job(person,(UUID)task.get("jobId"))).status);
    }
    /** 外层事务回滚时，数据库分片和本次创建的两份隔离文件都不保留。 */
    @Test void rolledBackPartDoesNotLeaveNewReplicaFiles() throws Exception {
        Actor person=actor(); byte[] bytes="%PDF-rollback".getBytes(StandardCharsets.UTF_8); UUID request=UUID.randomUUID();
        UUID id=(UUID)uploads.create(person,body(bytes),UUID.randomUUID(),request).get("uploadId");
        java.nio.file.Path first=java.nio.file.Path.of(System.getenv("IMS_PLATFORM_STORAGE_PRIMARY"));
        java.nio.file.Path second=java.nio.file.Path.of(System.getenv("IMS_PLATFORM_STORAGE_SECONDARY"));
        long beforeA,beforeB; try(var a=java.nio.file.Files.list(first);var b=java.nio.file.Files.list(second)){beforeA=a.count();beforeB=b.count();}
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            uploads.part(person,id,1,"bytes 0-"+(bytes.length-1)+"/"+bytes.length,bytes.length,hash(bytes),new ByteArrayInputStream(bytes),request);
            status.setRollbackOnly();
        });
        assertEquals(0,db.count("SELECT count(*) FROM storage.upload_part WHERE upload_session_id=?",id));
        try(var a=java.nio.file.Files.list(first);var b=java.nio.file.Files.list(second)){assertEquals(beforeA,a.count());assertEquals(beforeB,b.count());}
    }
    /** 同一员工并发重试同一创建操作只产生一个上传会话。 */
    @Test void concurrentCreationWithSameOperationProducesOneSession() throws Exception {
        Actor person=actor(); byte[] bytes="%PDF-concurrent".getBytes(StandardCharsets.UTF_8); var input=body(bytes); UUID operation=UUID.randomUUID();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->uploads.create(person,input,operation,UUID.randomUUID()));
            var second=pool.submit(()->uploads.create(person,input,operation,UUID.randomUUID()));
            assertEquals(first.get().get("uploadId"),second.get().get("uploadId"));
        }
        assertEquals(1,db.count("SELECT count(*) FROM storage.upload_session WHERE uploader_id=? AND idempotency_key=?",person.userId(),"CREATE:"+operation));
    }
    /** 客户端暂停发送时不持有账号数据库行锁；后续提交依然在短事务内核权。 */
    @Test void slowClientDoesNotHoldUserLock() throws Exception {
        Actor person=actor(); byte[] bytes="%PDF-slow".getBytes(StandardCharsets.UTF_8); UUID request=UUID.randomUUID();
        UUID id=(UUID)uploads.create(person,body(bytes),UUID.randomUUID(),request).get("uploadId");
        var started=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        java.io.InputStream slow=new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] buffer,int offset,int length) {
                started.countDown(); try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return super.read(buffer,offset,length);
            }
        };
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var submitted=pool.submit(()->uploads.part(person,id,1,"bytes 0-"+(bytes.length-1)+"/"+bytes.length,bytes.length,hash(bytes),slow,request));
            try {
                assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
                new TransactionTemplate(transactions).executeWithoutResult(status->db.required("SELECT id FROM iam.user_account WHERE id=? FOR UPDATE NOWAIT",person.userId()));
            } finally { release.countDown(); }
            assertEquals(true,submitted.get(10,java.util.concurrent.TimeUnit.SECONDS).get("accepted"));
        }
    }
    /** 收流期间业务授权撤销，落库前的第二次检查必须拒绝并回滚暂存。 */
    @Test void revokedAccessDuringStreamingCannotCommitPart() throws Exception {
        Actor person=actor(); byte[] bytes="%PDF-revoked".getBytes(StandardCharsets.UTF_8); UUID request=UUID.randomUUID();
        UUID id=(UUID)uploads.create(person,body(bytes),UUID.randomUUID(),request).get("uploadId");
        var started=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        java.io.InputStream slow=new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] buffer,int offset,int length) {
                started.countDown(); try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return super.read(buffer,offset,length);
            }
        };
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var submitted=pool.submit(()->uploads.part(person,id,1,"bytes 0-"+(bytes.length-1)+"/"+bytes.length,bytes.length,hash(bytes),slow,request));
            try { assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS)); TestPolicies.ALLOWED.set(false); }
            finally { release.countDown(); }
            try {
                var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->submitted.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(ApiFault.class,failure.getCause()); assertEquals(403,((ApiFault)failure.getCause()).status);
                assertEquals(0,db.count("SELECT count(*) FROM storage.upload_part WHERE upload_session_id=?",id));
            } finally { TestPolicies.ALLOWED.set(true); }
        }
    }
    /** 保护异常、缺片和另一主体访问都不能产生文件发布或修改已有数据。 */
    @Test void rejectsMissingPartProtectionFailureAndForeignUser() {
        Actor person=actor(); byte[] bytes="%PDF-test".getBytes(StandardCharsets.UTF_8); var input=body(bytes);
        TestPolicies.PROTECTED.set(false);
        assertEquals(503,assertThrows(ApiFault.class,()->uploads.create(person,input,UUID.randomUUID(),UUID.randomUUID())).status);
        TestPolicies.PROTECTED.set(true);
        UUID id=(UUID)uploads.create(person,input,UUID.randomUUID(),UUID.randomUUID()).get("uploadId");
        assertEquals(422,assertThrows(ApiFault.class,()->uploads.complete(person,id,Sql.map("partCount",1,"finalSha256",hash(bytes)),UUID.randomUUID(),UUID.randomUUID())).status);
        Actor other=new Actor(UUID.randomUUID(),UUID.randomUUID(),1,false,Map.of(),List.of(),Set.of(),"");
        assertEquals(404,assertThrows(ApiFault.class,()->uploads.get(other,id)).status);
        UUID operation=UUID.randomUUID(); assertEquals("SUCCEEDED",uploads.cancel(person,id,operation,UUID.randomUUID()).get("state"));
        assertEquals("SUCCEEDED",uploads.cancel(person,id,operation,UUID.randomUUID()).get("state"));
        assertEquals(409,assertThrows(ApiFault.class,()->uploads.cancel(person,id,UUID.randomUUID(),UUID.randomUUID())).status);
    }
}
