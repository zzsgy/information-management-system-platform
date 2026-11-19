-- 网页维护的服务绑定优先于部署环境中的旧编号；不复制或改写既有服务身份。
CREATE TABLE iam.service_binding (
 id uuid PRIMARY KEY,
 slot varchar(32) NOT NULL UNIQUE CHECK(slot IN ('DRIVE_SCANNER','ARCHIVE_SCANNER','PLATFORM_SCANNER','OFFICE')),
 service_identity_id uuid REFERENCES iam.service_identity(id),
 row_version integer NOT NULL DEFAULT 1,
 changed_by uuid REFERENCES iam.user_account(id),
 changed_at timestamptz
);
INSERT INTO iam.service_binding(id,slot) VALUES
 ('50000000-0000-4000-8000-000000000001','DRIVE_SCANNER'),
 ('50000000-0000-4000-8000-000000000002','ARCHIVE_SCANNER'),
 ('50000000-0000-4000-8000-000000000003','PLATFORM_SCANNER'),
 ('50000000-0000-4000-8000-000000000004','OFFICE');
COMMENT ON TABLE iam.service_binding IS 'IT 管理的固定业务服务绑定；未维护时沿用部署配置，修改后无需重启';
COMMENT ON COLUMN iam.service_binding.id IS '固定业务服务配置标识，用于再验证和并发版本检查';
COMMENT ON COLUMN iam.service_binding.slot IS '固定服务用途，禁止客户端扩展为任意程序或外部服务';
COMMENT ON COLUMN iam.service_binding.service_identity_id IS '当前启用的专用身份；空值沿用环境配置';
COMMENT ON COLUMN iam.service_binding.row_version IS '并发修改版本';
COMMENT ON COLUMN iam.service_binding.changed_by IS '最后维护的 IT 管理员';
COMMENT ON COLUMN iam.service_binding.changed_at IS '最后维护时间';
