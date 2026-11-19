-- 运行配置与服务授权分开保存；秘密仍留在部署机私有文件中。
CREATE TABLE ops.deployment_settings (
 id uuid PRIMARY KEY, section varchar(24) UNIQUE NOT NULL,
 settings jsonb NOT NULL DEFAULT '{}'::jsonb, row_version integer NOT NULL DEFAULT 1,
 changed_by uuid REFERENCES iam.user_account(id), changed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO ops.deployment_settings(id,section) VALUES
 ('51000000-0000-4000-8000-000000000001','SCANNER'),
 ('51000000-0000-4000-8000-000000000002','STORAGE'),
 ('51000000-0000-4000-8000-000000000003','READING'),
 ('51000000-0000-4000-8000-000000000004','OFFICE'),
 ('51000000-0000-4000-8000-000000000005','PROTECTION'),
 ('51000000-0000-4000-8000-000000000006','MAINTENANCE');
COMMENT ON TABLE ops.deployment_settings IS '平台IT受再验证、版本控制和审计约束的部署运行配置，不保存明文密码';
COMMENT ON COLUMN ops.deployment_settings.settings IS '固定配置白名单，空字符串明确清除已有配置';

CREATE TABLE ops.maintenance_agent (
 id boolean PRIMARY KEY DEFAULT true CHECK(id), seen_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 state varchar(24) NOT NULL CHECK(state IN ('READY','NOT_READY')), error_code varchar(64)
);
COMMENT ON TABLE ops.maintenance_agent IS '独立Windows维护执行器的实测心跳，网页不可直接写入';
