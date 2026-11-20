-- 仅补从未分配过角色的模块，保留手工配置和已结束的任职。
CREATE FUNCTION iam.assign_employee_default_roles(target_employee uuid, grantor uuid)
RETURNS void LANGUAGE sql AS $$
    INSERT INTO iam.user_role (id, user_id, role_id, valid_from, assigned_by, approval_ref, module, role_code)
    SELECT gen_random_uuid(), u.id, r.id, CURRENT_TIMESTAMP, grantor, '员工默认角色', r.module, r.code
    FROM iam.user_account u
    CROSS JOIN (VALUES ('PLATFORM', 'MEMBER'), ('DRIVE', 'STAFF'), ('ARCHIVE', 'STAFF')) AS defaults(module, code)
    JOIN iam.role r ON r.module = defaults.module AND r.code = defaults.code AND r.status = 'ACTIVE'
    WHERE u.id = target_employee AND u.status = 'ACTIVE' AND NOT u.it_shared_account
      AND NOT EXISTS (
          SELECT 1 FROM iam.user_role ur WHERE ur.user_id = u.id AND ur.module = r.module
      );
$$;

DO $$
DECLARE
    bootstrap_admin uuid;
BEGIN
    SELECT id INTO bootstrap_admin FROM iam.user_account WHERE it_shared_account ORDER BY created_at, id LIMIT 1;
    -- 全新库执行迁移时尚无账号，后续新增员工时再分配。
    IF bootstrap_admin IS NOT NULL THEN
        PERFORM iam.assign_employee_default_roles(id, bootstrap_admin)
        FROM iam.user_account WHERE status = 'ACTIVE' AND NOT it_shared_account;
    END IF;
END;
$$;
