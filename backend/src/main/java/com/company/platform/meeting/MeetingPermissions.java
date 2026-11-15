package com.company.platform.meeting;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 会议配置复用平台角色，只调整选中的会议任职。 */
@Service
public class MeetingPermissions {
    private final Sql db;
    private final IdentityService identity;

    public MeetingPermissions(Sql db, IdentityService identity) {
        this.db = db;
        this.identity = identity;
    }

    /** 会议授权仅由具有角色管理权限的 IT 管理员办理。 */
    public void authorize(Actor actor) {
        if (!actor.systemAdministrator()) throw ApiFault.denied();
        actor.require("PLATFORM.ROLES_MANAGE");
    }

    /** 返回两项会议角色的当前任职名单。 */
    public List<Map<String, Object>> list(Actor actor) {
        authorize(actor);
        return db.rows("""
                SELECT u.id,u.name,u.employee_no,r.code
                FROM iam.user_role a JOIN iam.role r ON a.role_id=r.id
                JOIN iam.user_account u ON u.id=a.user_id
                WHERE r.module='PLATFORM' AND r.code IN ('MEETING_ORGANIZER','MEETING_URGENT')
                  AND r.status='ACTIVE' AND a.state='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP
                  AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)
                ORDER BY u.name,u.id,r.code
                """).stream().map(row -> Sql.view(row, "id,name,employee_no,code")).toList();
    }

    /** 新授权要求具名在职账号。撤销保留任职历史，并使目标账号现有会话失效。 */
    public Map<String, Object> change(Commands.Context ctx, UUID userId, String code, boolean granted) {
        var user = db.required("SELECT * FROM iam.user_account WHERE id=? FOR UPDATE", userId);
        var role = db.required("SELECT id,status FROM iam.role WHERE module='PLATFORM' AND code=?", code);
        UUID roleId = (UUID) role.get("id");
        if (granted && (!"ACTIVE".equals(user.get("status")) || Boolean.TRUE.equals(user.get("it_shared_account"))
                || !"ACTIVE".equals(role.get("status")))) throw ApiFault.invalid("请选择可授权的在职员工和有效角色");
        // 历史任职保持可追查，已到期但尚未结束的记录先收口再新增。
        int changed = db.update("""
                UPDATE iam.user_role SET state='ENDED',valid_until=LEAST(valid_until,CURRENT_TIMESTAMP)
                WHERE user_id=? AND role_id=? AND state='ACTIVE'
                  AND (NOT ? OR (valid_until IS NOT NULL AND valid_until<=CURRENT_TIMESTAMP))
                """, userId, roleId, granted);
        if (granted) {
            changed += db.update("""
                    INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code)
                    SELECT ?,?,?,CURRENT_TIMESTAMP,?,'PLATFORM',?
                    WHERE NOT EXISTS (SELECT 1 FROM iam.user_role WHERE user_id=? AND role_id=? AND state='ACTIVE')
                    """, UUID.randomUUID(), userId, roleId, ctx.actor().userId(), code, userId, roleId);
        }
        if (changed > 0) identity.invalidate(userId, "ROLE_CHANGE", ctx.operation());
        return Sql.map("id", userId);
    }
}

