-- 业务唯一性、并发边界和实际查询索引；不依赖单机内存锁。
ALTER TABLE iam.user_account ADD UNIQUE (employee_no), ADD UNIQUE (company_email),
 ADD CHECK (status IN ('ACTIVE','DISABLED')), ADD CHECK (company_email ~ '^[^@[:space:]]+@biolink[.]com$'),
 ADD CHECK (auth_version > 0 AND session_version > 0 AND row_version > 0);
ALTER TABLE iam.department ADD UNIQUE (code), ADD CHECK (status IN ('ACTIVE','DISABLED')), ADD CHECK (id IS DISTINCT FROM parent_id);
ALTER TABLE iam.role ADD UNIQUE (module,code), ADD UNIQUE (id,module,code), ADD CHECK (status IN ('ACTIVE','DISABLED'));
ALTER TABLE iam.permission ADD UNIQUE (module,code);
ALTER TABLE iam.user_role ADD FOREIGN KEY (role_id,module,role_code) REFERENCES iam.role(id,module,code),
 ADD CHECK (state IN ('ACTIVE','ENDED')), ADD CHECK (valid_until IS NULL OR valid_until >= valid_from);
CREATE UNIQUE INDEX ux_user_role_active ON iam.user_role(user_id,role_id,COALESCE(department_id,'00000000-0000-0000-0000-000000000000'::uuid)) WHERE state='ACTIVE';
CREATE UNIQUE INDEX ux_drive_single_role ON iam.user_role(user_id) WHERE module='DRIVE' AND state='ACTIVE';
CREATE UNIQUE INDEX ux_department_archivist ON iam.user_role(department_id) WHERE module='ARCHIVE' AND role_code='DEPARTMENT_ARCHIVIST' AND state='ACTIVE';
CREATE UNIQUE INDEX ux_department_duty ON iam.department_manager(department_id,duty_type) WHERE valid_until IS NULL;
ALTER TABLE iam.department_manager ADD CHECK (duty_type IN ('DEPARTMENT_MANAGER','SUPERVISING_LEADER'));
ALTER TABLE iam.session ADD UNIQUE (token_hash), ADD CHECK (expires_at > issued_at);
CREATE UNIQUE INDEX ux_live_session ON iam.session(user_id) WHERE revoked_at IS NULL;
ALTER TABLE iam.credential_challenge ADD UNIQUE (credential_hash), ADD CHECK (expires_at > issued_at),
 ADD CHECK (purpose IN ('TEMP_PASSWORD','EMAIL_RECOVERY','REAUTH')),
 ADD CHECK (purpose <> 'REAUTH' OR (session_id IS NOT NULL AND scope_digest IS NOT NULL));
ALTER TABLE iam.role_permission ADD CHECK (effect IN ('ALLOW','DENY')),
 ADD CHECK (scope_policy IN ('SELF','OWN_DEPARTMENT','DEPARTMENT_TREE','COMPANY','DELEGATED'));
ALTER TABLE iam.permission_assignment ADD CHECK (effect IN ('ALLOW','DENY')), ADD CHECK (state IN ('ACTIVE','REVOKED','EXPIRED'));
ALTER TABLE ops.audit_event ADD UNIQUE (event_id), ADD CHECK (record_kind IN ('BUSINESS_EVENT','SECURITY_EVENT','IDEMPOTENCY_RECEIPT')),
 ADD CHECK (severity IN ('INFO','WARN','ERROR','CRITICAL')), ADD CHECK (actor_id IS NULL OR actor_service_id IS NULL);
CREATE UNIQUE INDEX ux_idempotency ON ops.audit_event(actor_id,operation_key) WHERE record_kind='IDEMPOTENCY_RECEIPT';
-- 索引围绕列表、授权、会话与审计路径；分页在数据库执行。
CREATE INDEX ix_user_department ON iam.user_account(department_id,id);
CREATE INDEX ix_department_parent ON iam.department(parent_id,id);
CREATE INDEX ix_user_role_lookup ON iam.user_role(user_id,state,module);
CREATE INDEX ix_role_users ON iam.user_role(role_id,state,user_id);
CREATE INDEX ix_personal_permission ON iam.permission_assignment(user_id,state);
CREATE INDEX ix_challenge_user ON iam.credential_challenge(user_id,purpose,expires_at);
CREATE INDEX ix_audit_timeline ON ops.audit_event(occurred_at DESC,id DESC);
CREATE INDEX ix_audit_actor ON ops.audit_event(actor_id,occurred_at DESC);
CREATE INDEX ix_audit_resource ON ops.audit_event(module,resource_type,resource_id,occurred_at DESC);
CREATE INDEX ix_audit_request ON ops.audit_event(request_id);
-- 认证计数属于技术辅助状态，不替代业务历史；不保存原始工号、IP 或口令摘要。
CREATE TABLE ops.auth_throttle (bucket varchar(64) PRIMARY KEY, attempts integer NOT NULL DEFAULT 0,
 window_start timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, blocked_until timestamptz);
COMMENT ON TABLE ops.auth_throttle IS '多实例共享的认证失败保护计数器，窗口过期可清理';
COMMENT ON COLUMN ops.auth_throttle.bucket IS 'HMAC 化账号或来源标识，不是身份凭据';
COMMENT ON COLUMN ops.auth_throttle.attempts IS '十五分钟窗口的失败次数';
COMMENT ON COLUMN ops.auth_throttle.window_start IS '计数窗口开始时刻';
COMMENT ON COLUMN ops.auth_throttle.blocked_until IS '五次失败后的暂停截止时刻';
CREATE TABLE platform_meta.instance (id uuid PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
COMMENT ON TABLE platform_meta.instance IS '独立新库与库外安全账本的配对标识';
COMMENT ON COLUMN platform_meta.instance.id IS '应用生成的数据库实例 UUID';
COMMENT ON COLUMN platform_meta.instance.created_at IS '首次初始化时刻';
-- 数据库层阻止应用误改审计；清理策略以后单独通过受控运维角色执行。
CREATE FUNCTION ops.reject_audit_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'audit events are append-only'; END; $$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON ops.audit_event FOR EACH ROW EXECUTE FUNCTION ops.reject_audit_rewrite();
