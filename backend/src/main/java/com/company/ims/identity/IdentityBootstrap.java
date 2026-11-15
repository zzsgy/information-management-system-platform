package com.company.ims.identity;

import com.company.ims.shared.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 空库首管理员初始化，必须显式提供部署环境中的初始密码。 */
@Component
@Order(10)
public class IdentityBootstrap implements ApplicationRunner {
    /** 初始化状态日志，严禁输出密码与密码哈希。 */
    private static final Logger LOG = LoggerFactory.getLogger(IdentityBootstrap.class);
    /** 身份数据访问入口。 */
    private final Db db;
    /** 仅在账号表为空时使用的环境配置。 */
    private final String bootstrapPassword;

    /** 注入启动所需依赖；缺省空值代表尚未允许初始化。 */
    public IdentityBootstrap(Db db, @Value("${IMS_BOOTSTRAP_ADMIN_PASSWORD:}") String bootstrapPassword) {
        this.db = db;
        this.bootstrapPassword = bootstrapPassword;
    }

    /** 已有账号时不重置密码；空库缺少密码时保持服务可启动并明确报告登录不可用。 */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 锁定固定种子角色，避免多个实例同时初始化首管理员。
        Map<String, Object> superRole = db.required("SELECT id FROM ims_role WHERE role_code='super_admin' FOR UPDATE");
        if (db.scalarLong("SELECT COUNT(*) FROM ims_user") > 0) return;
        if (bootstrapPassword.isBlank()) {
            LOG.warn("账号库为空：未设置IMS_BOOTSTRAP_ADMIN_PASSWORD，尚未创建管理员，登录不可用。请设置至少12位初始密码后重启。");
            return;
        }
        // 初始密码经统一策略处理后的哈希。
        String passwordHash = IdentityPasswords.encode(bootstrapPassword);
        // 目标所属部门，必须通过范围与存在性检查。
        long departmentId = db.scalarLong("SELECT id FROM ims_department WHERE dept_code='ROOT'");
        // 目标用户的稳定标识，用于账号关联和会话撤销。
        long userId = db.insert("ims_user", Map.of("username", "admin", "password_hash", passwordHash,
                "real_name", "系统管理员", "dept_id", departmentId, "account_status", "active"));
        db.insert("ims_user_role", Map.of("user_id", userId, "role_id", superRole.get("id")));
        LOG.info("已创建首管理员admin。请登录后修改初始密码，并移除部署环境中的IMS_BOOTSTRAP_ADMIN_PASSWORD。");
    }
}
