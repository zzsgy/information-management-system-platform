package com.company.ims.operations;

import com.company.ims.archive.LocalAttachmentStorage;
import com.company.ims.identity.Actor;
import com.company.ims.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 运维安全回归：仅操作临时目录和短生命周期测试子进程，不连接现有数据库。 */
class OperationsSafetyTest {
    /** JUnit管理的独立临时目录，每个测试自动隔离。 */
    @TempDir Path directory;

    /** 有效清单必须与压缩包文件完全对应，并可无损提取到新目录。 */
    @Test
    void completeBackupVerifiesAndExtractsDatabaseAndAttachments() throws Exception {
        Path backup=packageFile("ims_test.imsbackup.zip",Map.of("database.dump","dump-content","attachments/1/file","原始附件"),null); // 自包含测试包。
        OfflineRestore.Manifest manifest=OfflineRestore.inspect(backup,true); // 逐项核验后的清单。
        Path target=Files.createDirectory(directory.resolve("restored")); // 独立提取目录。
        OfflineRestore.extract(backup,manifest,target);
        assertEquals(2,manifest.entries().size());
        assertEquals("原始附件",Files.readString(target.resolve("attachments/1/file")));
        assertEquals("dump-content",Files.readString(target.resolve("database.dump")));
    }

    /** 数据实际内容被修改而清单未更新时必须拒绝，不能只验证ZIP能否打开。 */
    @Test
    void corruptedContentIsRejected() throws Exception {
        Path backup=packageFile("ims_tampered.imsbackup.zip",Map.of("database.dump","tampered"),List.of(entry("database.dump","original"))); // 哈希与真实内容不同。
        assertThrows(IOException.class,() -> OfflineRestore.inspect(backup,true));
    }

    /** 清单不能夹带父目录路径或没有列入清单的额外文件。 */
    @Test
    void traversalAndUnlistedMembersAreRejected() throws Exception {
        Path traversal=packageFile("ims_traversal.imsbackup.zip",Map.of("database.dump","dump","attachments/../../outside","bad"),null); // 路径穿越包。
        assertThrows(IOException.class,() -> OfflineRestore.inspect(traversal,true));
        Path extra=packageFile("ims_extra.imsbackup.zip",Map.of("database.dump","dump","attachments/extra","secret"),List.of(entry("database.dump","dump"))); // 清单未声明成员。
        assertThrows(IOException.class,() -> OfflineRestore.inspect(extra,true));
        assertFalse(Files.exists(directory.getParent().resolve("outside")));
    }

    /** 解压后的长度不可超过清单声明，防止小声明掩盖超大压缩成员。 */
    @Test
    void declaredLengthIsEnforcedWhileReading() throws Exception {
        OfflineRestore.Entry shortEntry=new OfflineRestore.Entry("database.dump",1,hash("longer-content")); // 故意缩小声明长度。
        Path backup=packageFile("ims_length.imsbackup.zip",Map.of("database.dump","longer-content"),List.of(shortEntry)); // 内容哈希正确但长度错误。
        assertThrows(IOException.class,() -> OfflineRestore.inspect(backup,true));
    }

    /** 独占维护期间所有普通请求都拒绝进入；释放后恢复正常服务。 */
    @Test
    void maintenanceRejectsNewRequestsAndReleasesCleanly() {
        MaintenanceGate gate=new MaintenanceGate(); // 无其他实例共享的真实门禁。
        try(MaintenanceGate.Lease maintenance=gate.exclusive()) {
            BusinessException failure=assertThrows(BusinessException.class,gate::enterRequest); // 维护期间普通入口。
            assertEquals(503,failure.status());
            assertEquals(409,assertThrows(BusinessException.class,gate::exclusive).status());
        }
        assertDoesNotThrow(() -> { try(MaintenanceGate.Lease request=gate.enterRequest()) { /* 请求可继续。 */ } });
    }

    /** 备份必须等待已有上传/写请求离开过滤器链后才能进入独占阶段。 */
    @Test
    void maintenanceWaitsForInflightRequestToFinish() throws Exception {
        MaintenanceGate gate=new MaintenanceGate(); // 当前共享请求与维护任务的协调器。
        MaintenanceGate.Lease request=gate.enterRequest(); // 模拟仍在处理中的上传请求。
        CountDownLatch attempting=new CountDownLatch(1); // 维护线程已经开始尝试。
        CountDownLatch acquired=new CountDownLatch(1); // 取得独占锁才允许备份。
        try(ExecutorService executor=Executors.newSingleThreadExecutor()) {
            Future<?> future=executor.submit(() -> {
                attempting.countDown();
                try(MaintenanceGate.Lease maintenance=gate.exclusive()) { acquired.countDown(); }
            });
            assertTrue(attempting.await(1,TimeUnit.SECONDS));
            assertFalse(acquired.await(100,TimeUnit.MILLISECONDS));
            request.close();
            future.get(2,TimeUnit.SECONDS);
            assertEquals(0,acquired.getCount());
        }
    }

    /** 过滤器维护响应统一返回503和重试提示，不调用业务链。 */
    @Test
    void maintenanceFilterReturnsExplicitServiceUnavailable() throws Exception {
        MaintenanceGate gate=new MaintenanceGate(); // 真实门禁状态。
        MaintenanceFilter filter=new MaintenanceFilter(gate); // 待验证请求过滤器。
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/archives"); // 普通写请求。
        request.setServletPath("/api/archives");
        MockHttpServletResponse response=new MockHttpServletResponse(); // 捕获状态和JSON响应。
        try(MaintenanceGate.Lease maintenance=gate.exclusive()) {
            filter.doFilter(request,response,(req,res) -> fail("维护期间不应调用业务链"));
        }
        assertEquals(503,response.getStatus()); assertEquals("30",response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("备份"));
    }

    /** API恢复只校验并返回offline_required，不能返回成功或修改已有附件。 */
    @Test
    void onlineRestoreIsExplicitlyRefusedAfterValidation() throws Exception {
        Path backups=Files.createDirectory(directory.resolve("backups")); // 配置备份根目录。
        Path temporary=packageFile("ims_valid.imsbackup.zip",Map.of("database.dump","dump"),null); // 有效测试格式包。
        Files.move(temporary,backups.resolve(temporary.getFileName()));
        BackupService service=service(backups,new TestDb()); // 不调用任何PostgreSQL工具的请求服务。
        BusinessException failure=assertThrows(BusinessException.class,() -> service.requestRestore(Map.of("file","ims_valid.imsbackup.zip","confirm",true),admin())); // 仅验证。
        assertEquals(409,failure.status()); assertTrue(failure.getMessage().contains("offline_required"));
        assertEquals(1,service.list().size());
    }

    /** 备份API既不接受任意文件路径，也不递归删除目录或其他文件。 */
    @Test
    void pathsAndDeletionAreBoundToSingleBackupFile() throws Exception {
        Path backups=Files.createDirectory(directory.resolve("backups")); // 独立备份目录。
        BackupService service=service(backups,new TestDb()); // 安全路径解析器。
        Path outside=Files.writeString(directory.resolve("keep.txt"),"keep"); // 必须保留的边界外文件。
        assertThrows(IOException.class,() -> service.safeBackup("../keep.txt"));
        assertThrows(IOException.class,() -> service.safeBackup(outside.toString()));
        assertThrows(BusinessException.class,() -> service.delete("../keep.txt",true,admin()));
        assertEquals("keep",Files.readString(outside));
        Path backup=Files.writeString(backups.resolve("ims_delete.imsbackup.zip"),"test"); // 删除只校验明确包名，不执行包内容。
        assertThrows(BusinessException.class,() -> service.delete(backup.getFileName().toString(),false,admin()));
        service.delete(backup.getFileName().toString(),true,admin());
        assertFalse(Files.exists(backup)); assertTrue(Files.exists(outside));
    }

    /** 不支持的数据库配置不能生成看似成功的空备份文件。 */
    @Test
    void unsupportedDatabaseNeverProducesSuccessfulBackup() throws Exception {
        Path backups=Files.createDirectory(directory.resolve("backups")); // 初始空备份目录。
        BackupService service=service(backups,new TestDb()); // 故意使用H2配置。
        assertThrows(BusinessException.class,() -> service.create(admin()));
        assertTrue(service.list().isEmpty());
    }

    /** 附件长度或哈希变化必须逐项报告异常，不能把文件存在等同于完整。 */
    @Test
    void integrityReportsChangedAndMissingFiles() throws Exception {
        Path backups=Files.createDirectory(directory.resolve("backups")); // 独立备份根目录。
        TestDb db=new TestDb(); // 只提供测试附件元数据。
        db.attachments=List.of(
                Map.of("id",1L,"file_name","changed.txt","storage_key","1/changed","file_size",4L,"sha256",hash("good")),
                Map.of("id",2L,"file_name","missing.txt","storage_key","1/missing","file_size",4L,"sha256",hash("good")));
        BackupService service=service(backups,db); // 目录由实际存储适配器建立。
        Files.createDirectories(directory.resolve("attachments/1")); Files.writeString(directory.resolve("attachments/1/changed"),"modified");
        Map<String,Object> result=service.integrity(admin()); // 正常完成检测，但应明确存在异常。
        assertEquals(2,result.get("total")); assertEquals(0,result.get("ok")); assertEquals(2,result.get("failed"));
    }

    /** PostgreSQL工具解析不得丢失JDBC显式TLS要求，也不把密码放进命令参数。 */
    @Test
    void postgresConnectionPreservesTlsAndKeepsPasswordOffCommandLine() throws Exception {
        OfflineRestore.Connection connection=OfflineRestore.connection("jdbc:postgresql://localhost:5432/ims_restore?ssl=true","tester","sensitive-password"); // 显式TLS配置。
        assertEquals("verify-full",connection.environment().get("PGSSLMODE"));
        assertFalse(connection.arguments().toString().contains("sensitive-password"));
        assertEquals("sensitive-password",connection.environment().get("PGPASSWORD"));
        assertThrows(IOException.class,() -> OfflineRestore.connection("jdbc:postgresql://localhost/db?sslFactory=unknown","tester",""));
    }

    /** 工具失败必须检查非零退出码，即使日志文字不包含error也不允许继续。 */
    @Test
    void processRunnerRejectsNonzeroExitCode() {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString(); // 当前测试JDK，避免依赖系统PATH。
        assertThrows(IOException.class,() -> OfflineRestore.run(List.of(java,"--ims-invalid-option"),Map.of(),directory.resolve("process.log"),5));
    }

    /** 按当前环境构造运维服务，测试只使用独立临时附件目录。 */
    private BackupService service(Path backups,Db db) throws Exception {
        LocalAttachmentStorage storage=new LocalAttachmentStorage(directory.resolve("attachments").toString()); // 临时附件存储。
        return new BackupService(db,storage,new MaintenanceGate(),new AuditService(db),backups.toString(),"jdbc:h2:mem:only-test","sa","","",30);
    }

    /** 测试系统管理员身份，不绑定任何真实账号。 */
    private Actor admin() { return new Actor(1,1,"test-admin","测试管理员",Set.of(),Set.of("*")); }

    /** 写入可被独立恢复工具读取的测试包，可故意传入不一致清单验证拒绝路径。 */
    private Path packageFile(String filename,Map<String,String> contents,List<OfflineRestore.Entry> supplied) throws Exception {
        Path result=directory.resolve(filename); // 本测试压缩包位置。
        List<OfflineRestore.Entry> entries=new ArrayList<>(); // 按实际输入生成的正确清单，可由参数覆盖。
        for(Map.Entry<String,String> item:contents.entrySet()) entries.add(entry(item.getKey(),item.getValue()));
        OfflineRestore.Manifest manifest=new OfflineRestore.Manifest("2026-10-19T00:00:00Z",0,0,supplied==null?entries:supplied); // 测试库没有业务对象。
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(result),StandardCharsets.UTF_8)) {
            for(Map.Entry<String,String> item:contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey())); zip.write(item.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("manifest.properties")); zip.write(OfflineRestore.manifestBytes(manifest)); zip.closeEntry();
        }
        return result;
    }

    /** 由UTF8测试内容生成一份清单条目。 */
    private OfflineRestore.Entry entry(String path,String content) throws Exception { return new OfflineRestore.Entry(path,content.getBytes(StandardCharsets.UTF_8).length,hash(content)); }
    /** 计算测试内容哈希，避免将固定正确值与实现偶然绑定。 */
    private String hash(String content) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }

    /** 最小显式数据库替身，只供应附件校验记录并接收审计写入，不启动驱动或字节码代理。 */
    private static final class TestDb extends Db {
        /** 当前测试指定的附件元数据，默认无附件。 */
        private List<Map<String,Object>> attachments=List.of();
        /** 已发生审计事件，保留给测试调试，不模拟业务数据库状态。 */
        private final List<Map<String,?>> auditEvents=new ArrayList<>();
        /** 父类模板不会执行，本替身明确覆盖本组测试需要的两个方法。 */
        TestDb() { super(new JdbcTemplate()); }
        /** 仅允许完整性校验的固定SQL，其他查询直接失败以发现意外依赖。 */
        @Override public List<Map<String,Object>> list(String sql,Object... parameters) {
            if(!sql.equals("select id,file_name,storage_key,file_size,sha256 from archive_file order by id")) throw new AssertionError("未预期的测试数据库查询");
            return attachments;
        }
        /** 运维服务只需记录审计，不执行真实数据修改。 */
        @Override public long insert(String table,Map<String,?> values) {
            if(!"operation_log".equals(table)) throw new AssertionError("未预期的测试数据库写入");
            auditEvents.add(new LinkedHashMap<>(values)); return auditEvents.size();
        }
    }
}
