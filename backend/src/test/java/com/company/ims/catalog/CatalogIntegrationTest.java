package com.company.ims.catalog;

import com.company.ims.archive.NumberingService;
import com.company.ims.identity.Actor;
import com.company.ims.identity.CurrentUser;
import com.company.ims.shared.ApiResponse;
import com.company.ims.shared.AuditService;
import com.company.ims.shared.BusinessException;
import com.company.ims.shared.Db;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** 使用隔离数据库验证目录完整性、编号兼容及公告/日志的数据范围。 */
class CatalogIntegrationTest {
    /** 每项测试独立的数据库访问器。 */
    private Db db;
    /** 负责真实事务回滚的测试模板。 */
    private TransactionTemplate transaction;
    /** 被测试的基础目录业务。 */
    private CatalogService catalogs;
    /** 被测试的公告范围控制业务。 */
    private NoticeService notices;
    /** 与档案创建共用的编号生成器。 */
    private NumberingService numbering;
    /** 本部门系统管理员身份，对应隔离库中的真实用户行。 */
    private Actor admin;
    /** 同部门公告收件人。 */
    private Actor recipient;
    /** 其他部门的用户，作为越权访问反例。 */
    private Actor outsider;
    /** 保持迁移会话存活的数据库连接池，模拟服务端连接生命周期。 */
    private HikariDataSource dataSource;

    /** 每项测试使用全新内存库，只执行基础身份、档案和通用字典迁移。 */
    @BeforeEach
    void setup() {
        // 隔离测试库，不读取或修改现有业务文件。
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:catalog_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setMaximumPoolSize(4);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__identity.sql"),
                new ClassPathResource("db/migration/V2__archive.sql"), new ClassPathResource("db/migration/V4__catalog_seed.sql")).execute(dataSource);
        db = new Db(new JdbcTemplate(dataSource));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        numbering = new NumberingService(db);
        // 使用真实审计写入，保证配置变更与操作留痕共用事务。
        AuditService audit = new AuditService(db);
        catalogs = new CatalogService(db, numbering, audit);
        notices = new NoticeService(db, audit);
        // 真实外键依赖的部门与账号均为本次测试创建。
        long department = db.scalarLong("SELECT id FROM ims_department WHERE dept_code='ROOT'");
        long otherDepartment = db.insert("ims_department", Map.of("dept_code", "OTHER", "dept_name", "其他部门"));
        admin = user("admin", department, Set.of("*"));
        recipient = user("recipient", department, Set.of("notice.read", "archive.read", "log.read"));
        outsider = user("outsider", otherDepartment, Set.of("notice.read", "archive.read", "log.read"));
    }

    /** 关闭本测试创建的连接池，所有约束仍按真实DDL执行。 */
    @AfterEach
    void closeDatabase() { if (dataSource != null) dataSource.close(); }

    /** 档案高级管理不能替代独立字典维护权限。 */
    @Test
    void dictionaryWritesNeedExactPermission() {
        // 只有档案高级管理，不能隐式获得平台字典管理权限。
        Actor limited = new Actor(admin.id(), admin.deptId(), "limited", "受限管理员", Set.of(), Set.of("archive.admin"));
        assertThrows(BusinessException.class, () -> inTransaction(() -> catalogs.save(limited, "security-levels", null,
                Map.of("code", "NEW", "name", "新密级"))));
    }

    /** 停用字典仅对明确请求且持管理权限的界面可见，防止停用后无法重新启用。 */
    @Test
    void disabledDictionaryRequiresManagementPermission() {
        inTransaction(() -> catalogs.save(admin, "dict-items", null, Map.of("type_code", "web_test", "item_code", "disabled", "item_name", "停用项", "enabled", 0)));
        assertTrue(catalogs.list(admin, "dict-items", "web_test").isEmpty());
        assertEquals(1, catalogs.list(admin, "dict-items", "web_test", true).size());
        assertThrows(BusinessException.class, () -> catalogs.list(recipient, "dict-items", "web_test", true));
    }

    /** 分类树循环和有子分类删除都必须拒绝。 */
    @Test
    void categoryCycleAndReferencedDeletionAreRejected() {
        // 已有根分类和新建子分类构成最小树，用于验证反向关联不能形成循环。
        long parent = db.scalarLong("SELECT id FROM archive_category WHERE category_code='GENERAL'");
        long child = ((Number) inTransaction(() -> catalogs.save(admin, "categories", null,
                Map.of("category_code", "CHILD", "category_name", "子分类", "parent_id", parent))).get("id")).longValue();
        assertThrows(BusinessException.class, () -> inTransaction(() -> catalogs.save(admin, "categories", parent, Map.of("parent_id", child))));
        assertThrows(BusinessException.class, () -> inTransaction(() -> { catalogs.delete(admin, "categories", parent); return null; }));
        assertNull(db.required("SELECT parent_id FROM archive_category WHERE id=?", parent).get("parent_id"));
    }

    /** 目录接口不能写入内部列，也不能把小数或字符串静默转成整数。 */
    @Test
    void catalogRejectsUnexpectedFieldsAndInvalidTypes() {
        assertThrows(BusinessException.class, () -> inTransaction(() -> catalogs.save(admin, "security-levels", null,
                Map.of("code", "NEW", "name", "新密级", "password_hash", "injected"))));
        assertThrows(BusinessException.class, () -> inTransaction(() -> catalogs.save(admin, "retention-periods", null,
                Map.of("code", "NEW", "name", "新期限", "years", 1.5))));
        assertThrows(BusinessException.class, () -> inTransaction(() -> catalogs.save(admin, "retention-periods", null,
                Map.of("code", "NEW", "name", "新期限", "years", "10"))));
    }

    /** 默认配置外键必须真实存在。 */
    @Test
    void missingCategoryReferenceIsRejected() {
        assertThrows(BusinessException.class, () -> inTransaction(() -> catalogs.save(admin, "categories", null,
                Map.of("category_code", "MISSING", "category_name", "错误分类", "default_retention_id", 999999))));
    }

    /** 兼容旧客户端NEVER，预览不占号，已使用规则不能被删除重置。 */
    @Test
    void numberingNeverRoundTripsAndPreviewDoesNotConsumeSequence() {
        // 分段规则必须包含一个流水段，固定前缀便于断言输出。
        Map<String, Object> rule = inTransaction(() -> catalogs.save(admin, "numbering-rules", null, Map.of(
                "rule_code", "TEST", "rule_name", "测试规则", "priority", 100, "scope_type", "global", "reset_cycle", "NEVER",
                "segments", List.of(Map.of("type", "fixed", "value", "T-"), Map.of("type", "seq", "length", 3, "pad", "0")))));
        long id = ((Number) rule.get("id")).longValue();
        long category = db.scalarLong("SELECT id FROM archive_category WHERE category_code='GENERAL'");
        assertEquals("NEVER", rule.get("reset_cycle"));
        assertEquals("NONE", db.required("SELECT reset_cycle FROM numbering_rule WHERE id=?", id).get("reset_cycle"));
        assertEquals("T-001", catalogs.preview(admin, admin.deptId(), category, null).get("archive_no"));
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM numbering_sequence"));
        assertEquals("T-001", inTransaction(() -> numbering.generate(admin.deptId(), category, null, false)));
        assertEquals("T-002", catalogs.preview(admin, admin.deptId(), category, null).get("archive_no"));
        assertThrows(BusinessException.class, () -> inTransaction(() -> { catalogs.delete(admin, "numbering-rules", id); return null; }));
    }

    /** 编号缺少流水段将产生重复编号风险，配置阶段直接拒绝。 */
    @Test
    void numberingWithoutSequenceIsRejected() {
        assertThrows(BusinessException.class, () -> inTransaction(() -> catalogs.save(admin, "numbering-rules", null,
                Map.of("rule_code", "BAD", "rule_name", "错误规则", "segments", List.of(Map.of("type", "fixed", "value", "SAME"))))));
    }

    /** 公开参数只允许首页与编号设置，不能借接口读取或保存任意机密键。 */
    @Test
    void publicParameterKeysAndCardStructureAreValidated() {
        assertThrows(BusinessException.class, () -> catalogs.parameter(admin, "database_password"));
        assertThrows(BusinessException.class, () -> inTransaction(() -> catalogs.setParameter(admin, "dashboard_cards", Map.of("value", "{\"bad\":true}"))));
        assertEquals("[\"archives\"]", inTransaction(() -> catalogs.setParameter(admin, "dashboard_cards", Map.of("value", "[\"archives\"]"))).get("value"));
    }

    /** 指定收件人公告不能被其他部门通过列表、详情或已读接口看到。 */
    @Test
    void targetedNoticeIsVisibleOnlyToRecipientAndPublisher() {
        // 公告只指定一个收件人，发送者仍可查看自己发布的内容。
        long noticeId = ((Number) inTransaction(() -> notices.publish(admin,
                Map.of("title", "定向公告", "content", "仅发给指定员工", "receiver_user_ids", List.of(recipient.id())))).get("id")).longValue();
        assertEquals(1L, notices.list(recipient, 1, 20).get("total"));
        assertEquals(1L, notices.list(admin, 1, 20).get("total"));
        assertEquals(0L, notices.list(outsider, 1, 20).get("total"));
        assertThrows(BusinessException.class, () -> notices.detail(outsider, noticeId));
        assertThrows(BusinessException.class, () -> inTransaction(() -> { notices.read(outsider, noticeId); return null; }));
        inTransaction(() -> { notices.read(recipient, noticeId); return null; });
        assertNotNull(db.required("SELECT read_at FROM notice_receiver WHERE notice_id=? AND user_id=?", noticeId, recipient.id()).get("read_at"));
    }

    /** 全员公告读取无需预先生成所有收件人，失效后统一不可见。 */
    @Test
    void globalNoticeAndExpiryAreEnforced() {
        // 空收件人列表保留旧客户端全员发布语义。
        long noticeId = ((Number) inTransaction(() -> notices.publish(admin, Map.of("title", "全员公告"))).get("id")).longValue();
        assertEquals(1L, notices.list(outsider, 1, 20).get("total"));
        db.execute("UPDATE notice SET expire_time=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' WHERE id=?", noticeId);
        assertEquals(0L, notices.list(outsider, 1, 20).get("total"));
    }

    /** 非系统通配用户即便有日志查看权限，也只能取得本部门用户事件。 */
    @Test
    void auditQueriesRespectDepartmentScope() {
        db.insert("operation_log", Map.of("user_id", admin.id(), "module", "archive", "action", "same-department"));
        db.insert("operation_log", Map.of("user_id", outsider.id(), "module", "archive", "action", "other-department"));
        // 请求上下文仅在本测试范围内设置，finally确保不污染后续测试。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTRIBUTE, recipient);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            // 直接调用真实控制器查询，保留真实SQL的范围与计数逻辑。
            ApiResponse response = (ApiResponse) new NoticeLogController(notices, db).logs(null, null, null, 1, 50);
            Map<?, ?> data = (Map<?, ?>) response.data();
            assertEquals(1L, data.get("total"));
            assertEquals("same-department", ((Map<?, ?>) ((List<?>) data.get("items")).getFirst()).get("action"));
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    /** 创建有真实外键对应关系的测试身份，不启用任何真实登录口令。 */
    private Actor user(String name, long department, Set<String> permissions) {
        // 占位哈希仅满足测试表约束，未调用登录接口，也不是有效密码。
        long id = db.insert("ims_user", Map.of("username", name, "password_hash", "not-a-login-hash", "real_name", "测试用户", "dept_id", department));
        return new Actor(id, department, name, "测试用户", Set.of(), permissions);
    }

    /** 在真实JDBC事务内执行测试操作，异常会回滚全部写入。 */
    private <T> T inTransaction(Supplier<T> action) { return transaction.execute(status -> action.get()); }
}
