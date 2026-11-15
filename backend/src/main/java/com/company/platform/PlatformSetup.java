package com.company.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.net.URI;
import java.util.*;

/** 新平台初始化：迁移前校验隔离库，空库单次引导，不运行任何旧系统初始化逻辑。 */
@Configuration(proxyBeanMethods=false)
public class PlatformSetup {
    /** 在 Flyway 创建 schema 前拒绝非隔离地址和存在旧表的数据库。 */
    @Bean
    FlywayMigrationStrategy isolatedMigration(DataSource source, @Value("${spring.datasource.url}") String url,
            @Value("${platform.isolation-confirmed:false}") boolean confirmed) {
        return flyway -> {
            URI uri=URI.create(url.replaceFirst("^jdbc:",""));
            if (!confirmed || !"postgresql".equals(uri.getScheme()) || !Set.of("127.0.0.1","localhost").contains(uri.getHost())
                    || !uri.getPath().matches("/ims_platform_(dev|test|trial)[a-z0-9_]*")) throw new IllegalStateException("必须使用显式确认的本机独立平台库");
            JdbcTemplate jdbc=new JdbcTemplate(source);
            Long oldTables=jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'",Long.class);
            if (oldTables!=null && oldTables>0) throw new IllegalStateException("目标库已有 public 业务表，拒绝初始化");
            flyway.migrate();
        };
    }

    /** 空库引导；管理员密码从环境注入，不生成通用默认密码，也不写入日志。 */
    @Bean
    ApplicationRunner initialize(Sql db, SecurityLedger ledger, Audit audit, PlatformTransactionManager manager,
            @Value("${platform.bootstrap.employee-no:}") String employeeNo, @Value("${platform.bootstrap.name:平台管理员}") String name,
            @Value("${platform.bootstrap.email:}") String email, @Value("${platform.bootstrap.password:}") String password,
            @Value("${server.address:127.0.0.1}") String bindAddress) {
        return args -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            // 初始化锁只保护低频建库和种子，不阻塞正常业务读请求。
            db.rows("SELECT pg_advisory_xact_lock(61427001)");
            boolean fresh=db.count("SELECT count(*) FROM iam.user_account")==0;
            var instanceRow=db.one("SELECT id FROM platform_meta.instance");
            UUID instance=instanceRow==null?UUID.randomUUID():(UUID)instanceRow.get("id");
            if (instanceRow==null) db.update("INSERT INTO platform_meta.instance(id) VALUES(?)",instance);
            ledger.bind(instance,fresh);
            if (!fresh) return;
            if ("123456".equals(password) && !Set.of("127.0.0.1", "::1", "localhost").contains(bindAddress))
                throw new IllegalStateException("本机默认管理员密码仅可用于回环地址");
            Map<String,Object> initial=Sql.map("employeeNo",employeeNo,"name",name,"companyEmail",email,"password",password);
            Inputs.text(initial,"employeeNo",64); Inputs.text(initial,"name",128); Inputs.email(initial); Inputs.password(initial,"password");
            UUID department=(UUID)db.required("SELECT id FROM iam.department WHERE code='ROOT'").get("id"), user=UUID.randomUUID();
            db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required,it_shared_account) VALUES(?,?,?,?,?,?,false,true)",user,employeeNo,name,department,email.toLowerCase(Locale.ROOT),Crypto.password(password));
            seed(db,user);
            for (String module:List.of("PLATFORM","DRIVE")) {
                var role=db.required("SELECT id,code FROM iam.role WHERE module=? AND code='IT_ADMIN'",module);
                db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,CURRENT_TIMESTAMP,?,?,?)",UUID.randomUUID(),user,role.get("id"),user,module,role.get("code"));
            }
            audit.event(user,"PLATFORM","USER",user,"INITIALIZE","SUCCEEDED",UUID.randomUUID());
        });
    }

    /** 固定基础角色与管理动作；平台 IT 的固定管理能力在实时身份服务中展开。 */
    private void seed(Sql db, UUID creator) {
        // 模块、稳定动作、显示名称；每项动作严格归属一个模块。
        String[][] permissions={{"PLATFORM","USERS_MANAGE","人员管理"},{"PLATFORM","DEPARTMENTS_MANAGE","组织管理"},{"PLATFORM","ROLES_MANAGE","平台角色管理"},{"PLATFORM","AUDIT_READ","平台审计查询"},
            {"DRIVE","ROLES_MANAGE","公盘角色管理"},{"DRIVE","ACCESS","公盘入口"},{"ARCHIVE","ROLES_MANAGE","档案角色管理"},{"ARCHIVE","ACCESS","档案入口"},{"ARCHIVE","AUDIT_READ","档案审计查询"}};
        for (String[] p:permissions) db.update("INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(module,code) DO NOTHING",UUID.randomUUID(),p[0],p[0]+"."+p[1],p[2],"MODULE",p[1],!p[1].equals("ACCESS"),"MODULE");
        String[][] roles={{"PLATFORM","IT_ADMIN","IT管理员（系统管理）"},{"PLATFORM","MEMBER","平台成员"},{"PLATFORM","CONTENT_EDITOR","内容维护人员"},{"PLATFORM","MEETING_ORGANIZER","周会组织人员"},{"PLATFORM","MEETING_URGENT","紧急会议发起人员"},{"DRIVE","IT_ADMIN","公盘管理员"},{"DRIVE","GENERAL_MANAGER","总经理"},{"DRIVE","DEPARTMENT_MANAGER","部门负责人"},{"DRIVE","STAFF","职员"},
            {"ARCHIVE","SUPER_ADMIN","档案超级管理员"},{"ARCHIVE","DEPARTMENT_ARCHIVIST","部门档案员"},{"ARCHIVE","STAFF","普通职员"}};
        for (String[] r:roles) {
            UUID id=UUID.randomUUID(); db.update("INSERT INTO iam.role(id,module,code,name) VALUES(?,?,?,?)",id,r[0],r[1],r[2]);
            boolean admin=r[1].equals("IT_ADMIN") || r[1].equals("SUPER_ADMIN");
            for (var p:db.rows("SELECT id,action FROM iam.permission WHERE module=?",r[0])) if ((p.get("action").equals("AUDIT_READ") && (r[1].equals("DEPARTMENT_ARCHIVIST") || r[1].equals("DEPARTMENT_MANAGER"))) || (r[1].equals("CONTENT_EDITOR") && p.get("action").equals("ANNOUNCEMENTS_MANAGE")) || (r[1].equals("MEETING_ORGANIZER") && p.get("action").equals("MEETING_WEEKLY")) || (r[1].equals("MEETING_URGENT") && p.get("action").equals("MEETING_URGENT")) || (admin && !Set.of("MEETING_WEEKLY","MEETING_URGENT").contains(p.get("action"))) || (r[0].equals("ARCHIVE") && p.get("action").equals("PHYSICAL_BORROW")) || (r[0].equals("ARCHIVE") && r[1].equals("DEPARTMENT_ARCHIVIST") && p.get("action").equals("PHYSICAL_MANAGE")) || p.get("action").equals("ACCESS") || (r[0].equals("ARCHIVE") && r[1].equals("DEPARTMENT_ARCHIVIST") && p.get("action").equals("CATALOG_MANAGE"))) {
                db.update("INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by) VALUES(?,?,'COMPANY','ALLOW',?)",id,p.get("id"),creator);
            }
        }
    }
}
