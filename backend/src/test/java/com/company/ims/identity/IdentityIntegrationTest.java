package com.company.ims.identity;

import com.company.ims.shared.AuditService;
import com.company.ims.shared.BusinessException;
import com.company.ims.shared.Db;
import jakarta.servlet.http.Cookie;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** 在隔离H2数据库中验证真实SQL、会话撤销和授权边界，不依赖已有业务数据。 */
class IdentityIntegrationTest {
    /** 仅用于隔离测试的初始口令，生产启动器不会读取此常量。 */
    private static final String TEST_PASSWORD = "Only_For_Test_123!";
    /** 每项测试独立的数据库访问器。 */
    private Db db;
    /** 执行真实授权查询的本地身份适配器。 */
    private LocalIdentityProvider identities;
    /** 被测试的会话生命周期服务。 */
    private AuthenticationService authentication;
    /** 被测试的组织和权限委派服务。 */
    private OrganizationService organization;
    /** 用于验证失败回滚的真实JDBC事务模板。 */
    private TransactionTemplate transactions;
    /** 已初始化系统管理员的不可变身份。 */
    private Actor admin;
    /** 保持迁移会话存活的真实连接池，避免H2约束引用已关闭会话。 */
    private HikariDataSource dataSource;

    /** 每个测试重新执行身份迁移脚本，再显式初始化测试管理员。 */
    @BeforeEach
    void prepareDatabase() {
        // 随机数据库名称保证测试互不污染；PostgreSQL模式发现常见SQL兼容问题。
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:identity_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setMaximumPoolSize(4);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__identity.sql"),
                new ClassPathResource("db/migration/V2__archive.sql")).execute(dataSource);
        db = new Db(new JdbcTemplate(dataSource));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        identities = new LocalIdentityProvider(db);
        // 使用真实审计表和服务，一并验证认证变更的审计外键与事务完整性。
        AuditService audit = new AuditService(db);
        authentication = new AuthenticationService(db, identities, audit, 12);
        organization = new OrganizationService(db, authentication, identities, audit);
        inTransaction(() -> {
            new IdentityBootstrap(db, TEST_PASSWORD).run(new DefaultApplicationArguments());
            return null;
        });
        admin = identities.actorFor(db.required("SELECT * FROM ims_user WHERE username='admin'"));
    }

    /** 释放每个测试拥有的连接池，不影响其他测试数据库。 */
    @AfterEach
    void closeDatabase() { if (dataSource != null) dataSource.close(); }

    /** 密码不能有生产默认值，已有管理员也不能被环境变量反复重置。 */
    @Test
    void bootstrapDoesNotResetExistingPassword() {
        new IdentityBootstrap(db, "A_different_password_123").run(new DefaultApplicationArguments());
        assertNotNull(login("admin", TEST_PASSWORD).get("access_token"));
        assertThrows(BusinessException.class, () -> login("admin", "A_different_password_123"));
    }

    /** 空库未明确提供管理员密码时只保留基础角色，不生成任何默认账号。 */
    @Test
    void emptyBootstrapPasswordDoesNotCreateDefaultAccount() {
        db.execute("DELETE FROM ims_user_role");
        db.execute("DELETE FROM ims_user");
        new IdentityBootstrap(db, "").run(new DefaultApplicationArguments());
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM ims_user"));
    }

    /** 初始密码过短必须明确拒绝，不能因部署错误创建弱口令管理员。 */
    @Test
    void weakBootstrapPasswordIsRejected() {
        db.execute("DELETE FROM ims_user_role");
        db.execute("DELETE FROM ims_user");
        assertThrows(BusinessException.class, () -> new IdentityBootstrap(db, "123456").run(new DefaultApplicationArguments()));
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM ims_user"));
    }

    /** 随机凭证只返回调用者，数据库仅保存摘要，摘要本身不能再次登录。 */
    @Test
    void loginPersistsOnlyTokenHash() {
        // 登录兼容数据保留旧客户端所需字段。
        Map<String, Object> result = login("admin", TEST_PASSWORD);
        // 仅返回登录方的随机会话凭证，禁止写入审计或日志。
        String token = (String) result.get("access_token");
        // 数据库内保存的令牌摘要，用于验证未持久化明文。
        String persistedHash = (String) db.required("SELECT token_hash FROM ims_session").get("token_hash");
        assertEquals(43, token.length());
        assertNotEquals(token, persistedHash);
        assertEquals(LocalIdentityProvider.hash(token), persistedHash);
        assertEquals("bearer", result.get("token_type"));
        assertTrue(identities.resolve(token).orElseThrow().has("*"));
        assertTrue(identities.resolve(persistedHash).isEmpty());
        assertFalse(result.containsKey("password_hash"));
    }

    /** 档案管理员权限不能充当用户管理或跨部门通行证。 */
    @Test
    void archiveAdministratorIsNotGlobalWildcard() {
        // 只授予档案高级管理，用于独立检查权限匹配语义。
        Actor archiveAdmin = new Actor(20, 1, "archive", "档案管理员", Set.of("archive_admin"), Set.of("archive.admin"));
        assertTrue(archiveAdmin.has("archive.admin"));
        assertFalse(archiveAdmin.has("user.write"));
        assertFalse(archiveAdmin.has("archive.manage.all"));
        assertFalse(archiveAdmin.has("*"));
    }

    /** 管理员禁用账号后，已签发凭证即刻失效，无需等待原有效期。 */
    @Test
    void disabledUserCannotUseExistingToken() {
        // 目标用户的稳定标识，用于账号关联和会话撤销。
        long userId = createEmployee("employee1", admin.deptId());
        // 仅返回登录方的随机会话凭证，禁止写入审计或日志。
        String token = (String) login("employee1", TEST_PASSWORD).get("access_token");
        assertTrue(identities.resolve(token).isPresent());
        inTransaction(() -> { organization.disableUser(admin, userId); return null; });
        assertTrue(identities.resolve(token).isEmpty());
        assertThrows(BusinessException.class, () -> login("employee1", TEST_PASSWORD));
        assertNotNull(db.required("SELECT revoked_at FROM ims_session WHERE user_id=?", userId).get("revoked_at"));
    }

    /** 即使运维直接改停用状态而未撤销会话，过滤查询也能阻断旧凭证。 */
    @Test
    void accountStatusIsCheckedOnEveryRequest() {
        // 目标用户的稳定标识，用于账号关联和会话撤销。
        long userId = createEmployee("employee2", admin.deptId());
        // 仅返回登录方的随机会话凭证，禁止写入审计或日志。
        String token = (String) login("employee2", TEST_PASSWORD).get("access_token");
        db.execute("UPDATE ims_user SET account_status='disabled' WHERE id=?", userId);
        assertTrue(identities.resolve(token).isEmpty());
    }

    /** 改密撤销所有设备会话，并仅接受新口令。 */
    @Test
    void passwordChangeRevokesEverySession() {
        createEmployee("employee3", admin.deptId());
        // 同一账号的第一台设备会话，用于验证撤销范围。
        String first = (String) login("employee3", TEST_PASSWORD).get("access_token");
        // 同一账号的第二台设备会话，用于验证多端登录。
        String second = (String) login("employee3", TEST_PASSWORD).get("access_token");
        // 由真实凭证解析出的普通员工身份。
        Actor employee = identities.resolve(first).orElseThrow();
        inTransaction(() -> { authentication.changePassword(employee, TEST_PASSWORD, "New_Password_456!"); return null; });
        assertTrue(identities.resolve(first).isEmpty());
        assertTrue(identities.resolve(second).isEmpty());
        assertThrows(BusinessException.class, () -> login("employee3", TEST_PASSWORD));
        assertNotNull(login("employee3", "New_Password_456!").get("access_token"));
    }

    /** 当前会话退出不会误撤销同一用户的另一个设备会话。 */
    @Test
    void logoutRevokesOnlyCurrentToken() {
        // 同一账号的第一台设备会话，用于验证撤销范围。
        String first = (String) login("admin", TEST_PASSWORD).get("access_token");
        // 同一账号的第二台设备会话，用于验证多端登录。
        String second = (String) login("admin", TEST_PASSWORD).get("access_token");
        inTransaction(() -> { authentication.logout(admin, first); return null; });
        assertTrue(identities.resolve(first).isEmpty());
        assertTrue(identities.resolve(second).isPresent());
    }

    /** 角色权限变化会在下一次请求生效，不缓存登录时的旧授权。 */
    @Test
    void permissionsAreReevaluatedForExistingSession() {
        createEmployee("employee4", admin.deptId());
        // 仅返回登录方的随机会话凭证，禁止写入审计或日志。
        String token = (String) login("employee4", TEST_PASSWORD).get("access_token");
        assertTrue(identities.resolve(token).orElseThrow().has("file.download"));
        db.execute("DELETE FROM ims_role_permission WHERE role_id=(SELECT id FROM ims_role WHERE role_code='employee') AND permission_id=(SELECT id FROM ims_permission WHERE perm_code='file.download')");
        assertFalse(identities.resolve(token).orElseThrow().has("file.download"));
    }

    /** 到期凭证不能继续读取API，即使数据库未运行清理任务。 */
    @Test
    void expiredTokenIsRejected() {
        // 仅返回登录方的随机会话凭证，禁止写入审计或日志。
        String token = (String) login("admin", TEST_PASSWORD).get("access_token");
        db.execute("UPDATE ims_session SET expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        assertTrue(identities.resolve(token).isEmpty());
    }

    /** 自己没有的权限不能通过创建角色或者分配超级角色间接获得。 */
    @Test
    void delegatedUserManagerCannotEscalatePrivileges() {
        // 该管理身份仅拥有账号读写和本部门数据范围。
        Actor limited = new Actor(100, admin.deptId(), "manager", "部门管理员", Set.of(), Set.of("user.read", "user.write"));
        assertThrows(BusinessException.class, () -> inTransaction(() -> organization.createRole(limited,
                Map.of("role_code", "escalation", "role_name", "非法角色", "permission_codes", List.of("*")))));
        // 系统管理员种子角色的主键，用于验证禁止越权委派。
        long superRoleId = db.scalarLong("SELECT id FROM ims_role WHERE role_code='super_admin'");
        assertThrows(BusinessException.class, () -> inTransaction(() -> organization.createUser(limited,
                Map.of("username", "escalation", "real_name", "越权账号", "dept_id", admin.deptId(),
                        "password", TEST_PASSWORD, "role_ids", List.of(superRoleId)))));
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM ims_user WHERE username='escalation'"));
    }

    /** 本部门用户管理员不能修改已有的系统管理员资料或组织归属。 */
    @Test
    void cannotModifyHigherPrivilegedAccount() {
        // 只有本部门用户管理能力的操作者，用于验证授权上限。
        Actor limited = new Actor(100, admin.deptId(), "manager", "部门管理员", Set.of(), Set.of("user.read", "user.write"));
        assertThrows(BusinessException.class, () -> inTransaction(() -> {
            organization.updateUser(limited, admin.id(), Map.of("real_name", "越权修改")); return null;
        }));
    }

    /** 默认用户列表遵守部门范围，管理员也必须获得显式跨部门能力。 */
    @Test
    void userListsAndUserCreationRespectDepartmentScope() {
        // 与当前操作者不同的部门，作为范围隔离反例。
        long otherDepartment = ((Number) inTransaction(() -> organization.createDepartment(admin,
                Map.of("dept_code", "OTHER", "dept_name", "其他部门"))).get("id")).longValue();
        createEmployee("other_employee", otherDepartment);
        // 只有本部门用户管理能力的操作者，用于验证授权上限。
        Actor limited = new Actor(100, admin.deptId(), "manager", "部门管理员", Set.of(), Set.of("user.read", "user.write"));
        // 接口返回的安全字段列表，用于检查范围过滤和敏感字段排除。
        List<?> rows = (List<?>) organization.users(limited, 1, 100).get("items");
        assertEquals(1, rows.size());
        assertThrows(BusinessException.class, () -> inTransaction(() -> organization.createUser(limited,
                Map.of("username", "other_new", "real_name", "越权跨部门", "dept_id", otherDepartment,
                        "password", TEST_PASSWORD, "role_ids", List.of()))));
    }

    /** 移除最后一个系统管理员的角色时必须事务回滚，避免系统被锁死。 */
    @Test
    void lastAdministratorCannotBeRemoved() {
        assertThrows(BusinessException.class, () -> inTransaction(() -> {
            organization.updateUser(admin, admin.id(), Map.of("role_ids", List.of())); return null;
        }));
        assertTrue(identities.actorFor(db.required("SELECT * FROM ims_user WHERE id=?", admin.id())).has("*"));
    }

    /** 账号响应使用白名单，不泄露密码哈希。 */
    @Test
    void userResponsesDoNotLeakPasswordHashes() {
        // 接口返回的安全字段列表，用于检查范围过滤和敏感字段排除。
        List<?> rows = (List<?>) organization.users(admin, 1, 20).get("items");
        assertFalse(((Map<?, ?>) rows.getFirst()).containsKey("password_hash"));
    }

    /** API不接受浏览器自动附带的会话Cookie，避免跨站表单复用身份。 */
    @Test
    void apiRequiresExplicitBearerRatherThanCookie() throws Exception {
        // 仅返回登录方的随机会话凭证，禁止写入审计或日志。
        String token = (String) login("admin", TEST_PASSWORD).get("access_token");
        // 模拟浏览器或客户端的HTTP请求，不连接真实服务。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users");
        request.setServletPath("/api/users");
        request.setCookies(new Cookie("ims_session", token));
        // 捕获过滤器输出的HTTP状态和内容。
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthenticationFilter(identities).doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertNull(request.getAttribute(CurrentUser.ATTRIBUTE));
    }

    /** 网页查询允许使用限定于/web的会话Cookie。 */
    @Test
    void webPageCookieResolvesCurrentIdentity() throws Exception {
        // 仅返回登录方的随机会话凭证，禁止写入审计或日志。
        String token = (String) login("admin", TEST_PASSWORD).get("access_token");
        // 模拟浏览器或客户端的HTTP请求，不连接真实服务。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/web/");
        request.setServletPath("/web/");
        request.setCookies(new Cookie("ims_session", token));
        // 捕获过滤器输出的HTTP状态和内容。
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthenticationFilter(identities).doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
        assertEquals(admin.id(), ((Actor) request.getAttribute(CurrentUser.ATTRIBUTE)).id());
    }

    /** Web编辑详情保留角色且不泄露敏感字段，猜测跨部门用户主键必须失败。 */
    @Test
    void webUserDetailKeepsRolesAndDepartmentBoundary() {
        // 创建两个部门的员工验证详情接口边界。
        long sameUser = createEmployee("same_web", admin.deptId());
        long otherDepartment = db.insert("ims_department", Map.of("dept_code", "WEB_OTHER", "dept_name", "其他部门"));
        long otherUser = createEmployee("other_web", otherDepartment);
        Actor reader = new Actor(sameUser, admin.deptId(), "same_web", "测试员工", Set.of(), Set.of("user.read"));
        Map<String, Object> detail = organization.user(reader, sameUser); // 同部门公开字段与角色集合。
        assertFalse(((List<?>) detail.get("role_ids")).isEmpty());
        assertFalse(detail.containsKey("password_hash"));
        assertFalse(detail.containsKey("access_token"));
        assertThrows(BusinessException.class, () -> organization.user(reader, otherUser));
        assertEquals(otherUser, ((Number) organization.user(admin, otherUser).get("id")).longValue());
    }

    /** 测试辅助：在事务中创建属于指定部门的普通员工。 */
    private long createEmployee(String username, long departmentId) {
        // 普通员工种子角色标识，不使用测试中硬编码的数据库序号。
        long roleId = db.scalarLong("SELECT id FROM ims_role WHERE role_code='employee'");
        // 从数据库读取或创建的目标账号记录，使用前必须校验权限。
        Map<String, Object> user = inTransaction(() -> organization.createUser(admin,
                Map.of("username", username, "real_name", "测试员工", "dept_id", departmentId,
                        "password", TEST_PASSWORD, "role_ids", List.of(roleId))));
        return ((Number) user.get("id")).longValue();
    }

    /** 测试辅助：执行真实登录SQL并提交成功会话。 */
    private Map<String, Object> login(String username, String password) {
        return inTransaction(() -> authentication.login(username, password));
    }

    /** 测试辅助：用JDBC事务验证服务失败后的原子回滚行为。 */
    private <T> T inTransaction(Supplier<T> action) { return transactions.execute(status -> action.get()); }
}
