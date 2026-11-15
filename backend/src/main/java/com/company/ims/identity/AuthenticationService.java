package com.company.ims.identity;

import com.company.ims.shared.AuditService;
import com.company.ims.shared.BusinessException;
import com.company.ims.shared.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 本地身份适配器的认证服务；仅持久化会话摘要，不存明文令牌。 */
@Service
public class AuthenticationService {
    /** 身份与会话数据访问入口。 */
    private final Db db;
    /** 本地账号转换为平台统一身份的适配器。 */
    private final LocalIdentityProvider identities;
    /** 操作审计写入器，禁止记录密码或令牌。 */
    private final AuditService audit;
    /** 会话有效小时数；由外置配置控制。 */
    private final long sessionHours;
    /** 密码学随机源，用于生成不可预测的会话凭证。 */
    private final SecureRandom random = new SecureRandom();

    /** 注入依赖并验证会话时长，拒绝无界有效期。 */
    public AuthenticationService(Db db, LocalIdentityProvider identities, AuditService audit,
                                 @Value("${ims.auth.session-hours:12}") long sessionHours) {
        this.db = db;
        this.identities = identities;
        this.audit = audit;
        if (sessionHours < 1 || sessionHours > 168) throw new IllegalArgumentException("会话有效期必须为1至168小时");
        this.sessionHours = sessionHours;
    }

    /**
     * 校验启用账号并颁发随机会话，返回兼容原桌面客户端的登录数据。
     * @param username 登录账号
     * @param password 仅用于本次哈希校验的明文密码
     * @return 包含access_token、角色及权限的响应内容
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> login(String username, String password) {
        // 查询前限制输入规模，避免将任意超长参数写入查询或审计。
        if (username == null || username.isBlank() || username.length() > 64) throw invalidLogin();
        // 行锁使禁用/改密与签发会话顺序化，避免禁用后并发签发旧凭证。
        Map<String, Object> user = db.one("SELECT * FROM ims_user WHERE username=? AND deleted_at IS NULL FOR UPDATE", username.trim());
        if (user == null || !"active".equals(user.get("account_status")) ||
                !IdentityPasswords.matches(password, (String) user.get("password_hash"))) {
            audit.record(null, "auth", "login_fail", "user", null, "登录失败，账号=" + username.trim());
            throw invalidLogin();
        }
        // 从操作系统随机源取得256位熵，编码结果仅返回给本次登录方。
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        // 仅返回登录方的随机会话凭证，禁止写入审计或日志。
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        // 从启用账号和当前角色计算出的统一身份。
        Actor actor = identities.actorFor(user);
        // 本次操作统一时间，确保会话签发和登录记录一致。
        OffsetDateTime now = OffsetDateTime.now();
        db.insert("ims_session", Map.of("user_id", actor.id(), "token_hash", LocalIdentityProvider.hash(token),
                "created_at", now, "expires_at", now.plusHours(sessionHours)));
        db.update("ims_user", actor.id(), Map.of("last_login_at", now, "updated_at", now));
        audit.record(actor.id(), "auth", "login", "user", actor.id(), "登录成功");
        // 对外返回的身份白名单字段，排除哈希和内部状态。
        Map<String, Object> data = userInfo(actor);
        data.put("access_token", token);
        data.put("token_type", "bearer");
        return data;
    }

    /** 修改本人密码并撤销所有既有会话，要求客户端重新登录。 */
    @Transactional
    public void changePassword(Actor actor, String oldPassword, String newPassword) {
        // 锁定本人账号；校验旧密码后才允许提交新哈希。
        Map<String, Object> user = db.required("SELECT * FROM ims_user WHERE id=? AND deleted_at IS NULL FOR UPDATE", actor.id());
        if (!IdentityPasswords.matches(oldPassword, (String) user.get("password_hash"))) {
            throw new BusinessException(400, "原密码错误");
        }
        // 新密码的带随机盐哈希，原明文不进入数据库。
        String hash = IdentityPasswords.encode(newPassword);
        db.update("ims_user", actor.id(), Map.of("password_hash", hash, "updated_at", OffsetDateTime.now()));
        revokeAll(actor.id());
        audit.record(actor.id(), "auth", "change_password", "user", actor.id(), "已修改密码并撤销所有会话");
    }

    /** 主动退出仅撤销当前凭证；其他设备会话仍按其有效期工作。 */
    @Transactional
    public void logout(Actor actor, String token) {
        if (token != null) db.execute("UPDATE ims_session SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND token_hash=? AND revoked_at IS NULL",
                actor.id(), LocalIdentityProvider.hash(token));
        audit.record(actor.id(), "auth", "logout", "user", actor.id(), "退出登录");
    }

    /** 撤销指定用户的全部会话，供账号停用、改密和授权变更调用。 */
    public void revokeAll(long userId) {
        db.execute("UPDATE ims_session SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL", userId);
    }

    /** 将统一身份投影为旧客户端能够读取的安全字段，不包含任何密码信息。 */
    public Map<String, Object> userInfo(Actor actor) {
        // 对外返回的身份白名单字段，排除哈希和内部状态。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", actor.id());
        data.put("username", actor.username());
        data.put("real_name", actor.realName());
        data.put("dept_id", actor.deptId());
        data.put("account_status", "active");
        data.put("roles", actor.roles());
        data.put("permissions", actor.permissions());
        return data;
    }

    /** 对不存在、停用和密码错误统一返回消息，避免暴露账号状态。 */
    private BusinessException invalidLogin() { return new BusinessException(401, "用户名或密码错误，或账号不可用"); }
}
