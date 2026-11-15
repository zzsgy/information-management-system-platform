-- 公盘文件链路所需的真实业务上下文；不授予任何默认内容写权限。
CREATE SCHEMA drive;
CREATE TABLE drive.folder (
 id uuid PRIMARY KEY,parent_id uuid REFERENCES drive.folder(id),zone varchar(64) NOT NULL CHECK(zone IN ('COMPANY_SHARED','DEPARTMENT_SHARED','DEPARTMENT_INTERNAL')),
 department_id uuid REFERENCES iam.department(id),folder_level varchar(16) NOT NULL CHECK(folder_level IN ('TOP','CHILD')),delegation_id uuid,
 name varchar(128) NOT NULL,name_key varchar(256) NOT NULL,state varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','DISABLED','RECYCLED','PURGED')),
 row_version integer NOT NULL DEFAULT 1,deleted_at timestamptz,deleted_by uuid REFERENCES iam.user_account(id),recycle_group_id uuid,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(id IS DISTINCT FROM parent_id),CHECK(department_id IS NOT NULL OR (zone='COMPANY_SHARED' AND folder_level='TOP' AND parent_id IS NULL))
);
CREATE UNIQUE INDEX ux_drive_department_top ON drive.folder(department_id,zone) WHERE folder_level='TOP' AND state='ACTIVE' AND department_id IS NOT NULL;
CREATE UNIQUE INDEX ux_drive_company_root ON drive.folder(zone) WHERE department_id IS NULL AND state='ACTIVE';
CREATE UNIQUE INDEX ux_drive_folder_name ON drive.folder(parent_id,name_key) NULLS NOT DISTINCT WHERE state='ACTIVE';
CREATE INDEX ix_drive_folder_parent ON drive.folder(parent_id,state);
CREATE TABLE drive.delegation (
 id uuid PRIMARY KEY,department_id uuid NOT NULL REFERENCES iam.department(id),folder_id uuid NOT NULL REFERENCES drive.folder(id),role_id uuid NOT NULL REFERENCES iam.role(id),
 allowed_actions varchar(32)[] NOT NULL,version integer NOT NULL DEFAULT 1,state varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','REVOKED')),
 created_by uuid NOT NULL REFERENCES iam.user_account(id),revoked_at timestamptz
);
CREATE UNIQUE INDEX ux_drive_active_delegation ON drive.delegation(department_id,folder_id,role_id) WHERE state='ACTIVE';
ALTER TABLE drive.folder ADD FOREIGN KEY(delegation_id) REFERENCES drive.delegation(id);
CREATE TABLE drive.resource_grant (
 id uuid PRIMARY KEY,subject_id uuid NOT NULL REFERENCES iam.user_account(id),resource_type varchar(32) NOT NULL CHECK(resource_type IN ('FOLDER','DOCUMENT')),
 resource_id uuid NOT NULL,action varchar(32) NOT NULL,effect varchar(16) NOT NULL CHECK(effect IN ('ALLOW','DENY')),
 delegation_id uuid NOT NULL REFERENCES drive.delegation(id),granted_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 state varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','REVOKED')),revoked_by uuid REFERENCES iam.user_account(id),revoked_at timestamptz,version integer NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX ux_drive_active_grant ON drive.resource_grant(subject_id,resource_type,resource_id,action,effect) WHERE state='ACTIVE';
CREATE INDEX ix_drive_grant_subject ON drive.resource_grant(subject_id,state,resource_type,resource_id,action);
CREATE TABLE drive.document (
 id uuid PRIMARY KEY,folder_id uuid NOT NULL REFERENCES drive.folder(id),publishing_department_id uuid NOT NULL REFERENCES iam.department(id),uploaded_by uuid NOT NULL REFERENCES iam.user_account(id),
 name varchar(512) NOT NULL,name_key varchar(512) NOT NULL,current_version_id uuid,status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RECYCLED','PURGED')),
 collaboration_enabled boolean NOT NULL DEFAULT false,edit_epoch integer NOT NULL DEFAULT 1,row_version integer NOT NULL DEFAULT 1,deleted_at timestamptz,deleted_by uuid REFERENCES iam.user_account(id),recycle_group_id uuid,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX ux_drive_document_name ON drive.document(folder_id,name_key) WHERE status='ACTIVE';
CREATE TABLE drive.document_version (
 id uuid PRIMARY KEY,document_id uuid NOT NULL REFERENCES drive.document(id),version_no integer NOT NULL CHECK(version_no>0),object_id uuid NOT NULL REFERENCES storage.object(id),
 parent_version_id uuid REFERENCES drive.document_version(id),source_type varchar(64) NOT NULL,created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(document_id,version_no),UNIQUE(document_id,id)
);
ALTER TABLE drive.document ADD FOREIGN KEY(id,current_version_id) REFERENCES drive.document_version(document_id,id);
CREATE INDEX ix_drive_document_folder ON drive.document(folder_id,status,id);
CREATE TABLE storage.preview_session (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),session_id uuid NOT NULL REFERENCES iam.session(id),module varchar(32) NOT NULL,
 document_id uuid NOT NULL REFERENCES drive.document(id),version_id uuid NOT NULL REFERENCES drive.document_version(id),object_id uuid NOT NULL REFERENCES storage.object(id),
 auth_version integer NOT NULL,document_version integer NOT NULL,expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_preview_user ON storage.preview_session(user_id,expires_at);
ALTER TABLE ops.job ADD COLUMN requester_session_id uuid REFERENCES iam.session(id);
COMMENT ON COLUMN ops.job.requester_session_id IS '发起任务的固定用户会话，后台处理不绕过会话失效';
COMMENT ON TABLE drive.folder IS '公盘分区和部门目录，所有权与内容动作分别核验';
COMMENT ON TABLE drive.delegation IS 'IT配置的部门负责人内容管理委托上界';
COMMENT ON TABLE drive.resource_grant IS '具体用户针对目录或文档的动作允许与明确拒绝';
COMMENT ON TABLE drive.document IS '公盘业务文档及当前不可变版本指针';
COMMENT ON TABLE drive.document_version IS '公盘不可变文件版本，发布必须引用检测通过对象';
COMMENT ON TABLE storage.preview_session IS '绑定用户、登录会话和业务版本的短期受控页面阅读会话';
-- 对每个新增字段给出中文说明，避免JSON/UUID字段成为无含义的技术占位。
DO $$ DECLARE field record; text_value text; BEGIN
 FOR field IN SELECT c.table_name,c.column_name FROM information_schema.columns c WHERE c.table_schema='drive' OR (c.table_schema='storage' AND c.table_name='preview_session') LOOP
  text_value=CASE field.column_name
   WHEN 'id' THEN '稳定内部标识' WHEN 'parent_id' THEN '上级目录标识' WHEN 'zone' THEN '公司共享、部门共享或部门内部区'
   WHEN 'department_id' THEN '所属部门标识' WHEN 'folder_level' THEN '顶层或内部子目录层级' WHEN 'delegation_id' THEN '有效委托依据'
   WHEN 'name' THEN '显示名称' WHEN 'name_key' THEN '规范化名称冲突键' WHEN 'state' THEN '当前业务状态' WHEN 'row_version' THEN '并发记录版本'
   WHEN 'deleted_at' THEN '进入回收状态时刻' WHEN 'deleted_by' THEN '回收经办人' WHEN 'recycle_group_id' THEN '同一次回收对象组'
   WHEN 'created_at' THEN '创建时刻' WHEN 'updated_at' THEN '最近修改时刻' WHEN 'role_id' THEN '受委托的部门负责人角色'
   WHEN 'allowed_actions' THEN '明确委托的动作上界' WHEN 'version' THEN '授权配置版本' WHEN 'created_by' THEN '实际创建者'
   WHEN 'revoked_at' THEN '撤销时刻' WHEN 'revoked_by' THEN '撤权经办人' WHEN 'subject_id' THEN '被授权员工'
   WHEN 'resource_type' THEN '目录或文档资源类型' WHEN 'resource_id' THEN '具体业务资源标识' WHEN 'action' THEN '独立内容动作'
   WHEN 'effect' THEN '允许或明确拒绝' WHEN 'granted_by' THEN '实际授权人' WHEN 'folder_id' THEN '所在或委托目录'
   WHEN 'publishing_department_id' THEN '内容发布归属部门' WHEN 'uploaded_by' THEN '最初上传人，不推导管理权'
   WHEN 'current_version_id' THEN '当前已发布版本指针' WHEN 'status' THEN '当前文档状态' WHEN 'collaboration_enabled' THEN '是否开启协作'
   WHEN 'edit_epoch' THEN '编辑批次代次' WHEN 'document_id' THEN '所属业务文档' WHEN 'version_no' THEN '同文档版本序号'
   WHEN 'object_id' THEN '检测通过的不可变对象' WHEN 'parent_version_id' THEN '来源历史版本' WHEN 'source_type' THEN '本地上传、协作或历史恢复来源'
   WHEN 'user_id' THEN '阅读会话所属员工' WHEN 'session_id' THEN '阅读时绑定的有效登录会话' WHEN 'module' THEN '所属独立业务模块'
   WHEN 'version_id' THEN '固定内容版本' WHEN 'auth_version' THEN '当前员工授权代次' WHEN 'document_version' THEN '创建阅读时的文档状态版本'
   WHEN 'expires_at' THEN '阅读会话失效时刻' ELSE '受控业务字段' END;
  EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',CASE WHEN field.table_name='preview_session' THEN 'storage' ELSE 'drive' END,field.table_name,field.column_name,text_value);
 END LOOP;
END $$;
