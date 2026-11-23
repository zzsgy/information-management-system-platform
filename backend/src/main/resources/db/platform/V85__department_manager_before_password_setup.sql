-- 组织任职可以先核对已配置的角色。实际办理业务仍由 action_enabled 检查账号设密状态。
CREATE FUNCTION iam.role_action_configured(person uuid, action_code text) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT COALESCE(bool_or(rp.effect='ALLOW'), false) AND NOT COALESCE(bool_or(rp.effect='DENY'), false)
       AND NOT EXISTS (
           SELECT 1
           FROM iam.permission_assignment pa JOIN iam.permission p ON p.id=pa.permission_id
           WHERE pa.user_id=person AND p.code=action_code AND pa.state='ACTIVE' AND pa.effect='DENY'
             AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP)
       )
    FROM iam.user_role a
    JOIN iam.role r ON r.id=a.role_id
    JOIN iam.role_permission rp ON rp.role_id=r.id
    JOIN iam.permission p ON p.id=rp.permission_id AND p.module=r.module
    JOIN iam.user_account u ON u.id=a.user_id
    WHERE a.user_id=person AND u.status='ACTIVE' AND a.state='ACTIVE' AND r.status='ACTIVE'
      AND (r.module,r.code)<>('PLATFORM','IT_ADMIN') AND rp.scope_policy='COMPANY' AND p.code=action_code
      AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP);
$$;
COMMENT ON FUNCTION iam.role_action_configured(uuid,text) IS '按当前角色允许和明确拒绝计算权限配置，不用于判定账号可办理业务';

CREATE OR REPLACE FUNCTION iam.action_enabled(person uuid, action_code text) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT (iam.it_management_action(action_code) AND iam.system_administrator(person))
        OR (action_code IN ('ARCHIVE.ACCESS','ARCHIVE.PHYSICAL_BORROW') AND iam.it_shared_borrower(person))
        OR (EXISTS (
                SELECT 1 FROM iam.user_account u
                WHERE u.id=person AND u.status='ACTIVE' AND NOT u.password_setup_required
            ) AND iam.role_action_configured(person, action_code));
$$;
