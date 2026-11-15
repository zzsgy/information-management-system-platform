-- 员工名片资料独立保存，直属主管引用现有账号；旧员工可按需补录。
ALTER TABLE iam.user_account
    ADD COLUMN organization_name varchar(200),
    ADD COLUMN job_title varchar(128),
    ADD COLUMN supervisor_id uuid REFERENCES iam.user_account(id),
    ADD COLUMN mobile_phone varchar(32),
    ADD COLUMN contact_email varchar(254);
