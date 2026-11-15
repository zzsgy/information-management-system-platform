package com.company.ims.identity;

import com.company.ims.shared.Db;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 在本地身份表中解析凭证，每次请求重查账号与权限以支持即时停用。 */
@Component
public class LocalIdentityProvider implements IdentityProvider {
    /** 本地身份及会话存储访问入口。 */
    private final Db db;

    /** 注入数据库访问器。 */
    public LocalIdentityProvider(Db db) { this.db = db; }

    /** 对有效的随机凭证返回当前授权身份，不解析任何客户端自报的用户字段。 */
    @Override
    public Optional<Actor> resolve(String bearerToken) {
        if (bearerToken == null || bearerToken.length() < 32 || bearerToken.length() > 256) return Optional.empty();
        // 查询同时验证摘要、有效期、撤销状态和账号启用状态。
        Map<String, Object> user = db.one("""
                SELECT u.* FROM ims_user u JOIN ims_session s ON s.user_id=u.id
                WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP
                AND u.account_status='active' AND u.deleted_at IS NULL
                """, hash(bearerToken));
        return user == null ? Optional.empty() : Optional.of(actorFor(user));
    }

    /** 将本地用户记录及启用角色映射成平台中立的不可变身份。 */
    public Actor actorFor(Map<String, Object> user) {
        // 用数据库主键关联授权，避免账号更名影响关联关系。
        long userId = ((Number) user.get("id")).longValue();
        // 集合去重，防止多角色重复显示同一权限。
        Set<String> roles = new LinkedHashSet<>();
        // 只收集启用角色赋予的精确权限，不把角色名称当作权限。
        Set<String> permissions = new LinkedHashSet<>();
        db.list("SELECT r.role_code FROM ims_role r JOIN ims_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status=1", userId)
                .forEach(row -> roles.add((String) row.get("role_code")));
        db.list("""
                SELECT DISTINCT p.perm_code FROM ims_permission p
                JOIN ims_role_permission rp ON rp.permission_id=p.id JOIN ims_role r ON r.id=rp.role_id
                JOIN ims_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status=1
                """, userId).forEach(row -> permissions.add((String) row.get("perm_code")));
        return new Actor(userId, ((Number) user.get("dept_id")).longValue(), (String) user.get("username"),
                (String) user.get("real_name"), roles, permissions);
    }

    /** 对高熵令牌计算SHA256摘要；摘要只能定位会话，不能作为Bearer直接使用。 */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
