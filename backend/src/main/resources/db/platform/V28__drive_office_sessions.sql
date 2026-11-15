-- Office协作会话独立于正式版本，编辑器同步成功不等于平台已保存。
CREATE TABLE drive.edit_session (
 id uuid PRIMARY KEY,document_id uuid NOT NULL REFERENCES drive.document(id),base_version_id uuid NOT NULL REFERENCES drive.document_version(id),
 last_version_id uuid NOT NULL REFERENCES drive.document_version(id),edit_epoch integer NOT NULL,editor_key varchar(100) NOT NULL UNIQUE,
 state varchar(16) NOT NULL CHECK(state IN ('OPEN','CLOSING','CLOSED','UNKNOWN','REVOKED')),created_by uuid NOT NULL REFERENCES iam.user_account(id),
 next_save_seq integer NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL,
 row_version integer NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX ux_drive_edit_live ON drive.edit_session(document_id) WHERE state IN ('OPEN','CLOSING','UNKNOWN');
CREATE TABLE drive.edit_participant (
 session_id uuid NOT NULL REFERENCES drive.edit_session(id),user_id uuid NOT NULL REFERENCES iam.user_account(id),login_session_id uuid NOT NULL REFERENCES iam.session(id),
 auth_version integer NOT NULL,state varchar(16) NOT NULL CHECK(state IN ('ACTIVE','LEFT','REVOKED')),joined_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(session_id,user_id)
);
CREATE INDEX ix_drive_edit_person ON drive.edit_participant(user_id,session_id);
CREATE TABLE drive.save_receipt (
 id uuid PRIMARY KEY,session_id uuid NOT NULL REFERENCES drive.edit_session(id),save_seq integer NOT NULL,operation_id uuid NOT NULL,
 state varchar(24) NOT NULL CHECK(state IN ('REQUESTED','RECEIVING','SAVED','NO_CHANGES','UNKNOWN','REJECTED','RESOLVED')),
 event_key char(64),object_id uuid REFERENCES storage.object(id),published_version_id uuid REFERENCES drive.document_version(id),
 result_code varchar(64),requested_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 resolved_by uuid REFERENCES iam.user_account(id),resolved_at timestamptz,resolution varchar(32),reason varchar(1000),
 UNIQUE(session_id,save_seq),UNIQUE(session_id,operation_id)
);
CREATE UNIQUE INDEX ux_drive_save_event ON drive.save_receipt(session_id,event_key) WHERE event_key IS NOT NULL;
CREATE UNIQUE INDEX ux_drive_save_pending ON drive.save_receipt(session_id) WHERE state IN ('REQUESTED','RECEIVING');
COMMENT ON TABLE drive.edit_session IS '每文档最多一个有效协作批次，固定原版本、授权代次和最终保存状态';
COMMENT ON TABLE drive.edit_participant IS '平台掌握的完整参与者快照，不信任原生回调users为完整名单';
COMMENT ON TABLE drive.save_receipt IS '平台保存序号、原生事件去重、候选与正式版本的可追溯回执';
DO $$ DECLARE f record; description text; BEGIN
 FOR f IN SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='drive' AND table_name IN ('edit_session','edit_participant','save_receipt') LOOP
 description=CASE f.column_name
 WHEN 'id' THEN '稳定业务标识' WHEN 'document_id' THEN '所属公盘文档' WHEN 'base_version_id' THEN '编辑开始时固定正式版本'
 WHEN 'last_version_id' THEN '最近成功保存的正式版本' WHEN 'edit_epoch' THEN '文档编辑授权代次' WHEN 'editor_key' THEN '仅本批次使用的编辑器文档键'
 WHEN 'state' THEN '当前业务状态' WHEN 'created_by' THEN '实际发起人' WHEN 'next_save_seq' THEN '下一次平台保存序号'
 WHEN 'created_at' THEN '创建时刻' WHEN 'expires_at' THEN '会话最晚有效时刻' WHEN 'row_version' THEN '并发状态版本'
 WHEN 'session_id' THEN '所属协作批次' WHEN 'user_id' THEN '实际参与员工' WHEN 'login_session_id' THEN '加入时绑定的登录会话'
 WHEN 'auth_version' THEN '加入时员工授权代次' WHEN 'joined_at' THEN '加入时刻' WHEN 'save_seq' THEN '本批次单调递增保存序号'
 WHEN 'operation_id' THEN '客户端重试复用的稳定操作号' WHEN 'event_key' THEN '已验证原生回调语义摘要'
 WHEN 'object_id' THEN '独立检测并双份保存的候选对象' WHEN 'published_version_id' THEN '成功发布的新版本'
 WHEN 'result_code' THEN '脱敏结果或阻断原因' WHEN 'requested_by' THEN '申请保存的实际员工' WHEN 'resolved_by' THEN '待确认稿件处置负责人'
 WHEN 'resolved_at' THEN '完成处置时刻' WHEN 'resolution' THEN '接受或舍弃候选的处置结论' WHEN 'reason' THEN '实际业务原因' END;
 EXECUTE format('COMMENT ON COLUMN drive.%I.%I IS %L',f.table_name,f.column_name,description);
 END LOOP; END $$;
