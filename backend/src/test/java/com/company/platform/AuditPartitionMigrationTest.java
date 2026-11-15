package com.company.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只在全新55476库执行一次V42到V43迁移，带旧行、幂等回执及意图验证无损转换。 */
@EnabledIfEnvironmentVariable(named="IMS_AUDIT_MIGRATION_INTEGRATION",matches="yes")
class AuditPartitionMigrationTest {
    @Test void preservesLegacyRowsAndGlobalUniqueness(){
        String url=System.getenv("IMS_PLATFORM_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55476/ims_platform_dev_[0-9]+"))throw new IllegalStateException("迁移验收只允许55476隔离库");
        var source=new DriverManagerDataSource(url,System.getenv("IMS_PLATFORM_DB_USER"),System.getenv("IMS_PLATFORM_DB_PASSWORD"));var db=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/platform").defaultSchema("platform_meta").schemas("platform_meta").target("42").load().migrate();
        // 用正式空库引导建立账号、角色与配对账本，再生成迁移前记录，不能伪造已存在系统的账本。
        Sql sql=new Sql(db);
        try{new PlatformSetup().initialize(sql,new SecurityLedger(System.getenv("IMS_PLATFORM_LEDGER_ROOT")),new Audit(sql),new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),System.getenv("IMS_PLATFORM_ADMIN_NO"),"日志验收IT",System.getenv("IMS_PLATFORM_ADMIN_EMAIL"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),"127.0.0.1").run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));}catch(Exception e){throw new IllegalStateException(e);}
        UUID user=UUID.randomUUID(),event=UUID.randomUUID(),intent=UUID.randomUUID();
        db.update("INSERT INTO iam.user_account(id,employee_no,name,password_hash,company_email,department_id) SELECT ?,?,'迁移隔离人员',?,?,id FROM iam.department WHERE code='ROOT'",user,"MIGRATION-"+user,Crypto.password("AuditTest123"),user+"@biolink.com");
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,action,result,request_id,record_kind) VALUES(?,?,'2025-01-15',?,'PLATFORM','MIGRATION_TEST','TEST','SUCCEEDED',?,'BUSINESS_EVENT')",event,UUID.randomUUID(),user,UUID.randomUUID());
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,action,result,request_id,record_kind,operation_key,operation_result) VALUES(?,?,'2025-02-15',?,'PLATFORM','MIGRATION_RECEIPT','TEST','SUCCEEDED',?,'IDEMPOTENCY_RECEIPT','migration-stable-key','{\"state\":\"SUCCEEDED\"}')",UUID.randomUUID(),UUID.randomUUID(),user,UUID.randomUUID());
        db.update("INSERT INTO ops.ui_interaction(id,user_id,request_id,page,action,control,occurred_at) VALUES(?,?,?,'audit','SUBMIT','FORM','2025-01-16')",intent,user,UUID.randomUUID());
        var before=db.queryForList("SELECT row_to_json(t)::text AS data FROM ops.audit_event t ORDER BY id");
        Flyway.configure().dataSource(source).locations("classpath:db/platform").defaultSchema("platform_meta").schemas("platform_meta").load().migrate();
        assertEquals(before,db.queryForList("SELECT row_to_json(t)::text AS data FROM ops.audit_event t ORDER BY id"));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM ops.audit_scope WHERE event_id=?",Integer.class,event));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM ops.ui_interaction WHERE id=?",Integer.class,intent));
        assertEquals(3,db.queryForObject("SELECT count(*) FROM pg_partitioned_table WHERE partrelid IN ('ops.audit_event'::regclass,'ops.audit_scope'::regclass,'ops.ui_interaction'::regclass)",Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO ops.audit_event SELECT id,event_id,occurred_at+interval '1 month',ingested_at,actor_id,on_behalf_of_id,module,resource_type,resource_id,resource_version,action,result,authorization_source,request_id,application_id,retention_until,record_kind,operation_key,request_digest,operation_result,severity,actor_service_id FROM ops.audit_event WHERE id=?",event));
    }
}
