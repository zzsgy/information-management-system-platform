-- 与IdentityService保持相同入口语义：有效公司范围角色允许，角色或个人明确拒绝优先；个人允许不增权。
-- 已应用的V15保留原校验值，使用新迁移收紧候选、必要职责分配与改派检查。
CREATE OR REPLACE FUNCTION iam.archive_access_enabled(person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT COALESCE(bool_or(rp.effect='ALLOW'),false) AND NOT COALESCE(bool_or(rp.effect='DENY'),false)
 AND NOT EXISTS(SELECT 1 FROM iam.permission_assignment pa JOIN iam.permission p ON p.id=pa.permission_id
  WHERE pa.user_id=person AND p.code='ARCHIVE.ACCESS' AND pa.state='ACTIVE' AND pa.effect='DENY'
  AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP))
 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id JOIN iam.role_permission rp ON rp.role_id=role.id
 JOIN iam.permission p ON p.id=rp.permission_id AND p.module=role.module JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND role.status='ACTIVE' AND u.status='ACTIVE'
 AND NOT u.password_setup_required AND p.code='ARCHIVE.ACCESS' AND rp.scope_policy='COMPANY'
 AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP);
$$;
COMMENT ON FUNCTION iam.archive_access_enabled(uuid) IS '与实时登录主体一致的档案入口：公司范围有效角色ALLOW减角色及个人DENY，首次设密账号不参与审批';
