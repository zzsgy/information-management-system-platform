package com.company.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 在独立数据库验证 V76 存量账号升级及原有任职保留。 */
@EnabledIfEnvironmentVariable(named = "IMS_EMPLOYEE_ROLES_MIGRATION_INTEGRATION", matches = "yes")
class EmployeeDefaultRolesMigrationTest {
    @Test void fillsOnlyPreviouslyUnconfiguredModules() throws Exception {
        String url = System.getenv("IMS_PLATFORM_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55478/ims_platform_dev_[0-9]+"))
            throw new IllegalStateException("员工角色迁移验收只允许 55478 隔离库");
        var source = new DriverManagerDataSource(url, System.getenv("IMS_PLATFORM_DB_USER"), System.getenv("IMS_PLATFORM_DB_PASSWORD"));
        var db = new Sql(new JdbcTemplate(source));
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/platform")
                .defaultSchema("platform_meta").schemas("platform_meta");
        flyway.target("76").load().migrate();
        new PlatformSetup().initialize(db, new SecurityLedger(System.getenv("IMS_PLATFORM_LEDGER_ROOT")),
                new Audit(db), new DataSourceTransactionManager(source), System.getenv("IMS_PLATFORM_ADMIN_NO"),
                "隔离迁移 IT", System.getenv("IMS_PLATFORM_ADMIN_EMAIL"), System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),
                "127.0.0.1").run(new DefaultApplicationArguments(new String[0]));

        UUID admin = (UUID) db.required("SELECT id FROM iam.user_account WHERE it_shared_account").get("id");
        UUID pending = employee(db, "ACTIVE");
        UUID configured = employee(db, "ACTIVE");
        UUID disabled = employee(db, "DISABLED");
        db.update("""
                INSERT INTO iam.user_role (id,user_id,role_id,valid_from,assigned_by,module,role_code,state,valid_until)
                SELECT gen_random_uuid(),?,id,CURRENT_TIMESTAMP - interval '1 day',?,module,code,
                    CASE WHEN module='ARCHIVE' THEN 'ENDED' ELSE 'ACTIVE' END,
                    CASE WHEN module='ARCHIVE' THEN CURRENT_TIMESTAMP END
                FROM iam.role WHERE (module,code) IN (('DRIVE','GENERAL_MANAGER'),('ARCHIVE','STAFF'))
                """, configured, admin);
        String originalRoles = "SELECT * FROM iam.user_role WHERE user_id=? AND module IN ('DRIVE','ARCHIVE') ORDER BY id";
        var configuredBefore = db.rows(originalRoles, configured);
        var adminBefore = db.rows("SELECT * FROM iam.user_role WHERE user_id=? ORDER BY id", admin);

        flyway.target("77").load().migrate();

        String defaults = "SELECT module || '.' || role_code AS role FROM iam.user_role WHERE user_id=? AND approval_ref='员工默认角色' AND state='ACTIVE' AND assigned_by=? ORDER BY module";
        assertEquals(List.of("ARCHIVE.STAFF", "DRIVE.STAFF", "PLATFORM.MEMBER"),
                db.rows(defaults, pending, admin).stream().map(row -> row.get("role")).toList());
        assertEquals(List.of("PLATFORM.MEMBER"),
                db.rows(defaults, configured, admin).stream().map(row -> row.get("role")).toList());
        assertEquals(configuredBefore, db.rows(originalRoles, configured));
        assertEquals(adminBefore, db.rows("SELECT * FROM iam.user_role WHERE user_id=? ORDER BY id", admin));
        assertEquals(0, db.count("SELECT count(*) FROM iam.user_role WHERE user_id=?", disabled));
        db.rows("SELECT iam.assign_employee_default_roles(?,?)", configured, admin);
        assertEquals(3, db.count("SELECT count(*) FROM iam.user_role WHERE user_id=?", configured));
    }

    private UUID employee(Sql db, String status) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO iam.user_account (id,employee_no,name,company_email,password_hash,department_id,status)
                SELECT ?,?,'隔离迁移员工',?,?,id,? FROM iam.department WHERE code='ROOT'
                """, id, "ROLES-" + id, id + "@biolink.com", Crypto.password("IsolatedRoles123"), status);
        return id;
    }
}
