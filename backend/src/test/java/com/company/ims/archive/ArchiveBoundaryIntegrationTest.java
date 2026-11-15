package com.company.ims.archive;

import com.company.ims.identity.Actor;
import com.company.ims.identity.CurrentUser;
import com.company.ims.shared.AuditService;
import com.company.ims.shared.BusinessException;
import com.company.ims.shared.Db;
import com.company.ims.shared.Input;
import com.company.ims.shared.ApiResponse;
import com.company.ims.workflow.ApprovalService;
import com.company.ims.workflow.BusinessService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** 独立验证共享编辑边界、办理人真实性、销毁内容冻结以及导入限制的原子性。 */
class ArchiveBoundaryIntegrationTest {
    /** 本测试拥有的隔离连接池。 */
    private HikariDataSource pool;
    /** 真实数据访问器，不模拟SQL或业务状态。 */
    private Db db;
    /** 档案业务服务及其真实授权、编号和审计依赖。 */
    private ArchiveService archives;
    /** 真实附件接口，用临时磁盘验证冻结检查发生在文件落盘之前。 */
    private FileController files;
    /** 真实销毁申请和审批服务，拒绝申请时由业务事务解除内容冻结。 */
    private BusinessService business;
    /** JUnit分配的隔离附件目录，测试完成后自动清理。 */
    @TempDir
    private Path attachmentDirectory;
    /** 模拟控制器调用时的真实事务管理器。 */
    private DataSourceTransactionManager manager;
    /** 供断言失败后核对数据库未改变的事务模板。 */
    private TransactionTemplate transaction;
    /** 档案归属部门的办理人。 */
    private Actor owner;
    /** 接收到跨部门编辑共享的用户，具备独立功能权限。 */
    private Actor editor;
    /** 通用分类标识。 */
    private long category;

    /** 为每个测试创建空库、部门和真实账号外键。 */
    @BeforeEach
    void setup() throws IOException {
        pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:archive_boundary_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        pool.setUsername("sa");
        pool.setPassword("");
        pool.setMaximumPoolSize(4);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__identity.sql"),
                new ClassPathResource("db/migration/V2__archive.sql"),
                new ClassPathResource("db/migration/V3__workflow.sql")).execute(pool);
        db = new Db(new JdbcTemplate(pool));
        manager = new DataSourceTransactionManager(pool);
        transaction = new TransactionTemplate(manager);
        archives = new ArchiveService(db, new ArchiveAccess(db), new NumberingService(db), new AuditService(db));
        files = new FileController(db, new ArchiveAccess(db), new LocalAttachmentStorage(attachmentDirectory.toString()), new AuditService(db));
        business = new BusinessService(db, new ArchiveAccess(db), new ApprovalService(db, new ArchiveAccess(db), new AuditService(db)), new AuditService(db));
        // 两个不同部门用于验证编辑共享不会转变成归属权限。
        long ownerDepartment = db.scalarLong("SELECT id FROM ims_department WHERE dept_code='ROOT'");
        long editorDepartment = db.insert("ims_department", Map.of("dept_code", "EDITOR", "dept_name", "接收部门"));
        owner = user("owner", ownerDepartment);
        editor = user("editor", editorDepartment);
        db.execute("INSERT INTO ims_user_role(user_id,role_id) SELECT ?,id FROM ims_role WHERE role_code='archive_admin'", owner.id());
        category = db.scalarLong("SELECT id FROM archive_category WHERE category_code='GENERAL'");
        context(owner);
    }

    /** 清理请求上下文和隔离连接池，避免测试间身份泄露。 */
    @AfterEach
    void close() { RequestContextHolder.resetRequestAttributes(); if (pool != null) pool.close(); }

    /** 共享编辑可修改元数据，但不能把档案搬到自己部门后扩大可见范围。 */
    @Test
    void sharedEditorCannotTakeOwnershipByChangingDepartment() {
        // 建立本人明确收到的查询与编辑共享，排除完全无权用户的简单反例。
        long id = createArchive();
        for (String permission : List.of("archive.read", "archive.write")) inTransaction(() -> archives.grant(id,
                Map.of("grantee_type", "user", "grantee_id", editor.id(), "permission_code", permission)));
        context(editor);
        assertEquals("允许修改标题", inTransaction(() -> archives.update(id,
                Map.of("archive_name", "允许修改标题", "dept_id", owner.deptId()))).get("archive_name"));
        // 真正部门变更是授权升级，必须明确拒绝并保留原归属。
        BusinessException denied = assertThrows(BusinessException.class, () -> inTransaction(() -> archives.update(id,
                Map.of("dept_id", editor.deptId()))));
        assertEquals(403, denied.status());
        assertEquals(owner.deptId(), db.scalarLong("SELECT dept_id FROM archive WHERE id=?", id));
        assertThrows(BusinessException.class, () -> inTransaction(() -> archives.grant(id,
                Map.of("grantee_type", "dept", "grantee_id", editor.deptId(), "permission_code", "archive.read"))));
    }

    /** 归档经办人必须来自实际操作身份，不能由表单伪造。 */
    @Test
    void archivalActorComesFromAuthenticatedRequest() {
        // 请求中的伪造经办人不能进入可编辑字段集合。
        long id = ((Number) inTransaction(() -> archives.create(Map.of("archive_name", "记录真实经办人", "dept_id", owner.deptId(),
                "category_id", category, "archiver_user_id", editor.id()))).get("id")).longValue();
        assertNull(db.required("SELECT archiver_user_id FROM archive WHERE id=?", id).get("archiver_user_id"));
        inTransaction(() -> { archives.archive(id); return null; });
        assertEquals(owner.id(), db.scalarLong("SELECT archiver_user_id FROM archive WHERE id=?", id));
        assertThrows(BusinessException.class, () -> inTransaction(() -> archives.update(id, Map.of("archiver_user_id", editor.id()))));
    }

    /** 原始JSON对象和超长文本必须返回校验错误，而不是进入数据库驱动。 */
    @Test
    void archiveTextFieldsRejectObjectsAndOversizedValues() {
        assertThrows(BusinessException.class, () -> inTransaction(() -> archives.create(Map.of(
                "archive_name", Map.of("bad", "object"), "dept_id", owner.deptId(), "category_id", category))));
        long id = createArchive();
        assertThrows(BusinessException.class, () -> inTransaction(() -> archives.update(id, Map.of("owner_name", "人".repeat(101)))));
        assertThrows(BusinessException.class, () -> Input.requiredText(Map.of("content", List.of("bad")), "content", 100));
    }

    /** CSV超限在任何业务写入前拒绝，不出现失败响应下已导入5000行的情况。 */
    @Test
    void oversizedCsvRejectsBeforeAnyWrites() {
        // 用正常有效列和值构造5001行，确保失败原因是条数而非字段错误。
        String content = "档案名称,档案类别,部门\n" + "条数验证,综合档案,公司总部\n".repeat(5001);
        ArchiveImportController importer = new ArchiveImportController(archives, db, manager);
        assertThrows(BusinessException.class, () -> importer.importCsv(Map.of("content", content)));
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM archive"));
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM numbering_sequence"));
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM operation_log"));
    }

    /** 共享有效期的格式错误需要明确返回400，不泄露内部日期解析错误。 */
    @Test
    void malformedGrantExpiryIsBusinessValidationError() {
        long id = createArchive();
        BusinessException error = assertThrows(BusinessException.class, () -> inTransaction(() -> archives.grant(id,
                Map.of("grantee_type", "user", "grantee_id", editor.id(), "permission_code", "archive.read", "expires_at", "not-a-date"))));
        assertEquals(400, error.status());
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM archive_grant"));
    }

    /**
     * 审批中和获批待执行的销毁申请均冻结题名和附件，失败操作不能生成版本或文件。
     *
     * @param requestStatus 本次验证的活动销毁状态，由真实申请及审批方法产生
     */
    @ParameterizedTest
    @ValueSource(strings = {"pending", "approved"})
    void activeDestructionFreezesMetadataAndAttachments(String requestStatus) throws IOException {
        // 初始版本在提交销毁申请前保存，供校验冻结期没有覆盖或新增内容。
        long id = createArchive();
        inTransaction(() -> { archives.archive(id); return null; });
        upload(id);
        // 使用真实申请和审批链生成待执行状态，不直接伪造生命周期。
        long request = createDestruction(id);
        if ("approved".equals(requestStatus)) inTransaction(() -> business.approveDestroy(request, true, "确认原申请内容", owner));
        // 两个入口必须返回业务冲突，不能留下变更日志或新的附件版本。
        long changes = db.scalarLong("SELECT COUNT(*) FROM archive_update_log WHERE archive_id=?", id);
        BusinessException editDenied = assertThrows(BusinessException.class, () -> inTransaction(() -> archives.update(id, Map.of("archive_name", "审批后替换题名"))));
        BusinessException uploadDenied = assertThrows(BusinessException.class, () -> upload(id));
        assertEquals(409, editDenied.status());
        assertEquals(409, uploadDenied.status());
        assertEquals("源档案", db.required("SELECT archive_name FROM archive WHERE id=?", id).get("archive_name"));
        assertEquals(changes, db.scalarLong("SELECT COUNT(*) FROM archive_update_log WHERE archive_id=?", id));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM archive_file WHERE archive_id=?", id));
        assertEquals(1, db.scalarLong("SELECT version_no FROM archive_file WHERE archive_id=? AND is_current=1", id));
        // 真实磁盘计数排除“数据库已回滚但提前落盘”的残留。
        try (var storedFiles = Files.walk(attachmentDirectory)) {
            assertEquals(1, storedFiles.filter(Files::isRegularFile).count());
        }
    }

    /** 真实审批拒绝同步结束销毁申请后，题名和附件立即恢复可编辑，历史快照仍保持原文。 */
    @Test
    void rejectedDestructionReleasesMetadataAndAttachmentFreeze() {
        // 建立已有附件的正式档案并进入销毁审批。
        long id = createArchive();
        inTransaction(() -> { archives.archive(id); return null; });
        upload(id);
        long request = createDestruction(id);
        assertThrows(BusinessException.class, () -> inTransaction(() -> archives.update(id, Map.of("archive_name", "仍在审批中"))));
        assertThrows(BusinessException.class, () -> upload(id));
        // 拒绝动作通过审批服务修改流程和业务单，解除冻结不依赖额外标志或恢复脚本。
        inTransaction(() -> business.approveDestroy(request, false, "继续保管", owner));
        assertEquals("rejected", db.required("SELECT status FROM destroy_request WHERE id=?", request).get("status"));
        assertEquals("rejected", db.required("SELECT status FROM approval_flow WHERE biz_type='destroy' AND biz_id=?", request).get("status"));
        assertEquals("拒绝销毁后补充", inTransaction(() -> archives.update(id, Map.of("archive_name", "拒绝销毁后补充"))).get("archive_name"));
        upload(id);
        assertEquals(2, db.scalarLong("SELECT version_no FROM archive_file WHERE archive_id=? AND is_current=1", id));
        assertEquals("源档案", db.required("SELECT archive_name FROM destroy_request_detail WHERE request_id=? AND archive_id=?", request, id).get("archive_name"));
    }

    /** 正常流程禁止草稿申请销毁；即使历史关联异常，归档入口也必须遵守活动销毁冻结。 */
    @Test
    void archivalCannotBypassActiveDestructionFreeze() {
        // 未归档时真实申请必须失败，不留下半成品销毁记录。
        long id = createArchive();
        assertThrows(BusinessException.class, () -> createDestruction(id));
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM destroy_request"));
        // 专门构造旧数据异常关联，证明归档入口有独立冻结校验而不是仅依赖status='draft'。
        long request = db.insert("destroy_request", Map.of("applicant_user_id", owner.id(), "reason", "异常历史关联", "status", "pending"));
        db.execute("INSERT INTO destroy_request_detail(request_id,archive_id) VALUES (?,?)", request, id);
        BusinessException denied = assertThrows(BusinessException.class, () -> inTransaction(() -> { archives.archive(id); return null; }));
        assertEquals(409, denied.status());
        assertEquals("draft", db.required("SELECT status FROM archive WHERE id=?", id).get("status"));
        assertNull(db.required("SELECT archiver_user_id FROM archive WHERE id=?", id).get("archiver_user_id"));
    }

    /**
     * 创建隔离测试账号，只开放本组需要的功能权限，不授予跨部门能力。
     * @param name 本测试内唯一的账号名称
     * @param department 测试账号所属部门主键
     * @return 可绑定到模拟请求的身份，数据库密码占位值不可用于登录
     */
    private Actor user(String name, long department) {
        // 测试不登录，占位字段不对应任何可用口令。
        long id = db.insert("ims_user", Map.of("username", name, "password_hash", "not-a-login-hash", "real_name", "测试人员", "dept_id", department));
        return new Actor(id, department, name, "测试人员", Set.of(), Set.of("archive.read", "archive.write", "archive.share", "file.upload", "destroy.apply", "destroy.approve"));
    }

    /**
     * 设置当前测试请求身份，模拟认证过滤器完成后的业务入口。
     * @param actor 本次业务操作的测试主体，只绑定当前线程
     */
    private void context(Actor actor) {
        // 身份绑定范围仅为当前测试线程。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 新建归属于源部门的草稿档案。 */
    private long createArchive() { return ((Number) inTransaction(() -> archives.create(Map.of("archive_name", "源档案", "dept_id", owner.deptId(), "category_id", category))).get("id")).longValue(); }
    /**
     * 通过真实销毁入口提交申请，供冻结和审批拒绝测试使用。
     *
     * @param archiveId 本部门已归档的拟销毁档案，草稿会由业务服务拒绝
     * @return 待审批销毁申请主键
     */
    private long createDestruction(long archiveId) {
        return ((Number) inTransaction(() -> business.createDestroy(Map.of("archive_ids", List.of(archiveId), "reason", "到期鉴定后申请销毁"), owner)).get("id")).longValue();
    }
    /**
     * 在真实事务中上传同名附件，用于校验版本增长及冻结后的无副作用拒绝。
     *
     * @param archiveId 本次附件所属档案主键
     * @return 上传接口的元数据响应
     */
    private ApiResponse upload(long archiveId) {
        return inTransaction(() -> {
            // 每次请求使用独立上传流，失败不能消费或替换既有附件版本。
            MockMultipartFile upload = new MockMultipartFile("file", "原件.txt", "text/plain", "原始内容".getBytes(StandardCharsets.UTF_8));
            try { return files.upload(archiveId, upload); }
            catch (IOException error) { throw new IllegalStateException("隔离测试附件读写失败", error); }
        });
    }
    /**
     * 在真实事务中执行服务操作，异常时完整回滚。
     * @param action 待执行的业务调用，可包含多个需要原子提交的步骤
     * @param <T> 业务调用返回的结果类型
     * @return 事务成功提交后的业务结果
     */
    private <T> T inTransaction(Supplier<T> action) { return transaction.execute(status -> action.get()); }
}
