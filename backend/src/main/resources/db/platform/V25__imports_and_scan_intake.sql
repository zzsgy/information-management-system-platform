-- 批量导入与扫描接收：仅追加新平台结构，保留旧系统及全部历史版本。
CREATE TABLE ops.import_batch (
 id uuid PRIMARY KEY, import_type varchar(16) NOT NULL CHECK(import_type IN ('EMPLOYEE','DEPARTMENT','ARCHIVE')),
 upload_object_id uuid NOT NULL REFERENCES storage.object(id), template_version varchar(64) NOT NULL,
 department_scope uuid[] NOT NULL, created_by uuid NOT NULL REFERENCES iam.user_account(id),
 state varchar(24) NOT NULL CHECK(state IN ('INVALID','READY','CONFIRMED','WITHDRAWN')),
 row_count integer NOT NULL CHECK(row_count BETWEEN 0 AND 1000), error_count integer NOT NULL,
 validation_digest char(64) NOT NULL, row_version integer NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, confirmed_at timestamptz, withdrawn_at timestamptz, reason text,
 UNIQUE(created_by,upload_object_id,import_type)
);
CREATE TABLE ops.import_row (
 batch_id uuid NOT NULL REFERENCES ops.import_batch(id), row_no integer NOT NULL,
 input_data jsonb NOT NULL, errors jsonb NOT NULL, object_type varchar(24), object_id uuid,
 result varchar(24) NOT NULL DEFAULT 'VALIDATED', PRIMARY KEY(batch_id,row_no)
);
CREATE INDEX ix_import_owner ON ops.import_batch(created_by,created_at DESC,id);
CREATE INDEX ix_import_scope ON ops.import_batch USING gin(department_scope);
CREATE TABLE archive.legacy_number_map (
 id uuid PRIMARY KEY, department_id uuid NOT NULL REFERENCES iam.department(id), object_type varchar(16) NOT NULL CHECK(object_type IN ('BOX','RECORD')),
 legacy_number varchar(256) NOT NULL, object_id uuid NOT NULL, batch_id uuid NOT NULL REFERENCES ops.import_batch(id),
 UNIQUE(department_id,object_type,legacy_number), UNIQUE(object_type,object_id)
);
CREATE TABLE archive.scan_device (
 id uuid PRIMARY KEY, service_identity_id uuid NOT NULL UNIQUE REFERENCES iam.service_identity(id),
 department_id uuid NOT NULL REFERENCES iam.department(id), name varchar(128) NOT NULL,
 software varchar(256) NOT NULL, staging_location varchar(512) NOT NULL, allowed_output_notes text NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','DISABLED')),row_version integer NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES iam.user_account(id), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE archive.scan_batch (
 id uuid PRIMARY KEY, department_id uuid NOT NULL REFERENCES iam.department(id), device_id uuid NOT NULL REFERENCES archive.scan_device(id),
 external_key varchar(128) NOT NULL, request_digest char(64) NOT NULL, expected_count integer NOT NULL CHECK(expected_count BETWEEN 1 AND 50),
 expected_pages integer NOT NULL CHECK(expected_pages BETWEEN 1 AND 500000), state varchar(24) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','RECEIVING','QUALITY_REVIEW','READY','CLOSED')),
 paused boolean NOT NULL DEFAULT false, originals_state varchar(16) NOT NULL DEFAULT 'STORED' CHECK(originals_state IN ('STORED','OUT','RETURNED')),
 special_approved boolean NOT NULL DEFAULT false, cleanup_approved boolean NOT NULL DEFAULT false,
 row_version integer NOT NULL DEFAULT 1, created_by uuid NOT NULL REFERENCES iam.user_account(id), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(device_id,external_key)
);
CREATE TABLE archive.scan_item (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES archive.scan_batch(id), record_id uuid NOT NULL REFERENCES archive.record(id),
 expected_pages integer NOT NULL CHECK(expected_pages BETWEEN 1 AND 10000), source_condition text NOT NULL, attachments text NOT NULL,
 special_handling boolean NOT NULL DEFAULT false, state varchar(24) NOT NULL DEFAULT 'WAITING' CHECK(state IN ('WAITING','RECEIVED','REWORK','PASSED')),
 generation integer NOT NULL DEFAULT 1, pdf_version_id uuid REFERENCES archive.pdf_version(id), received_pages integer,
 quality_check_id uuid REFERENCES archive.quality_check(id), row_version integer NOT NULL DEFAULT 1, UNIQUE(batch_id,record_id)
);
CREATE UNIQUE INDEX ux_scan_live_record ON archive.scan_item(record_id) WHERE state<>'PASSED';
CREATE TABLE archive.scan_event (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES archive.scan_batch(id), item_id uuid REFERENCES archive.scan_item(id),
 event_type varchar(32) NOT NULL, actor_id uuid REFERENCES iam.user_account(id), actor_service_id uuid REFERENCES iam.service_identity(id),
 details jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK((actor_id IS NOT NULL)::integer+(actor_service_id IS NOT NULL)::integer=1)
);
CREATE TABLE archive.scan_receipt (
 id uuid PRIMARY KEY, service_identity_id uuid NOT NULL REFERENCES iam.service_identity(id), event_id uuid NOT NULL,
 batch_id uuid NOT NULL REFERENCES archive.scan_batch(id), item_id uuid NOT NULL REFERENCES archive.scan_item(id),
 upload_session_id uuid NOT NULL UNIQUE REFERENCES storage.upload_session(id), receipt_key varchar(128) NOT NULL,
 request_digest char(64) NOT NULL, pdf_version_id uuid NOT NULL REFERENCES archive.pdf_version(id), received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(service_identity_id,event_id), UNIQUE(service_identity_id,receipt_key)
);
CREATE TABLE archive.scan_upload_binding (
 upload_session_id uuid PRIMARY KEY REFERENCES storage.upload_session(id), item_id uuid NOT NULL REFERENCES archive.scan_item(id),
 generation integer NOT NULL, item_version integer NOT NULL, record_version integer NOT NULL
);
CREATE TABLE iam.service_nonce (
 service_identity_id uuid NOT NULL REFERENCES iam.service_identity(id), nonce uuid NOT NULL,
 expires_at timestamptz NOT NULL, PRIMARY KEY(service_identity_id,nonce)
);
CREATE INDEX ix_scan_department ON archive.scan_batch(department_id,created_at DESC,id);
CREATE INDEX ix_scan_unreturned ON archive.scan_batch(created_at,id) WHERE originals_state='OUT';
CREATE INDEX ix_scan_items ON archive.scan_item(batch_id,id);
CREATE INDEX ix_scan_events ON archive.scan_event(batch_id,occurred_at,id);
CREATE INDEX ix_service_nonce_expiry ON iam.service_nonce(expires_at);
-- 历史接收回执、事件和旧号映射不可改写或删除。
CREATE FUNCTION archive.intake_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'intake history is immutable'; END $$;
CREATE TRIGGER scan_event_immutable BEFORE UPDATE OR DELETE ON archive.scan_event FOR EACH ROW EXECUTE FUNCTION archive.intake_history_immutable();
CREATE TRIGGER scan_receipt_immutable BEFORE UPDATE OR DELETE ON archive.scan_receipt FOR EACH ROW EXECUTE FUNCTION archive.intake_history_immutable();
CREATE TRIGGER legacy_number_immutable BEFORE UPDATE OR DELETE ON archive.legacy_number_map FOR EACH ROW EXECUTE FUNCTION archive.intake_history_immutable();
-- IT 只能配置设备；不因设备管理得到档案目录及正文权限。
INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type)
VALUES('b4b1c804-1ab1-4ab1-92a0-df6789746739','PLATFORM','PLATFORM.SCAN_DEVICES_MANAGE','扫描设备配置','SCAN_DEVICE','MANAGE',true,'COMPANY');
INSERT INTO iam.role_permission(role_id,permission_id,effect,scope_policy,changed_by) SELECT id,'b4b1c804-1ab1-4ab1-92a0-df6789746739'::uuid,'ALLOW','COMPANY',(SELECT id FROM iam.user_account ORDER BY created_at,id LIMIT 1) FROM iam.role WHERE module='PLATFORM' AND code='IT_ADMIN';

COMMENT ON TABLE ops.import_batch IS '受范围限制的台账预检批次；档案全部新增后仍须质量检查及独立复核';
COMMENT ON TABLE ops.import_row IS '原表行号、只读输入、字段错误及逐行结果，不存口令';
COMMENT ON TABLE archive.legacy_number_map IS '旧盒件编号到新系统稳定对象的不可变映射';
COMMENT ON TABLE archive.scan_device IS '设备批准范围和受控暂存登记，现场出口须由 IT 核验';
COMMENT ON TABLE archive.scan_batch IS '固定部门和设备的扫描批次，实体归还与电子质检分别记录';
COMMENT ON TABLE archive.scan_item IS '逐件原件状态、返工轮次及固定 PDF 候选';
COMMENT ON TABLE archive.scan_event IS '不可变原件交接、特殊批准、返工、暂停与暂存清理历史';
COMMENT ON TABLE archive.scan_receipt IS '专用服务的稳定业务回执，重传不重复挂接或发布';
COMMENT ON TABLE archive.scan_upload_binding IS '上传创建时冻结扫描项轮次和目录版本，纠正后旧上传失效';
COMMENT ON TABLE iam.service_nonce IS '签名请求防重放随机数，有效窗口结束后可清理';
DO $$ DECLARE row record; BEGIN
 FOR row IN SELECT * FROM (VALUES
 ('id','稳定内部标识'),('import_type','导入领域类型'),('upload_object_id','通过真实检测的台账对象'),('template_version','固定模板版本'),('department_scope','批准的部门集合'),('created_by','实际登记员工'),('state','受控业务状态'),('row_count','有效原表行数'),('error_count','错误行数'),('validation_digest','整批输入与规则预检摘要'),('row_version','并发修改版本'),('created_at','登记时刻'),('confirmed_at','确认提交时刻'),('withdrawn_at','撤回时刻'),('reason','操作原因'),
 ('batch_id','所属固定批次'),('row_no','Excel 原行号'),('input_data','原始固定字段快照'),('errors','行号字段错误集合'),('object_type','新增领域对象类型'),('object_id','新增稳定对象标识'),('result','逐行真实处理结果'),('department_id','获准所属部门'),('legacy_number','原有稳定编号'),('service_identity_id','专用扫描服务身份'),('name','设备显示名称'),('software','设备软件版本摘要'),('staging_location','受控暂存位置登记'),('allowed_output_notes','批准输出渠道及现场检查说明'),('device_id','固定接收设备'),('external_key','设备稳定批次键'),('request_digest','非秘密输入规范摘要'),('expected_count','预期文件件数'),('expected_pages','预期页数'),('paused','异常暂停标志'),('originals_state','原件保管归还状态'),('special_approved','特殊作业是否获独立批准'),('cleanup_approved','暂存清理是否获独立批准'),('record_id','绑定档案文件'),('source_condition','原件破损折页印章等情况'),('attachments','附件及来源核对说明'),('special_handling','是否需要特殊作业批准'),('generation','返工接收轮次'),('pdf_version_id','本轮固定 PDF 版本'),('received_pages','隔离解析实测页数'),('quality_check_id','正式归档可引用的人工检查记录'),('item_id','固定扫描明细'),('event_type','交接或处理事件类型'),('actor_id','实际操作员工'),('actor_service_id','实际扫描专用身份'),('details','本次不可变处理证据摘要'),('occurred_at','事件发生时刻'),('event_id','服务业务事件去重标识'),('upload_session_id','固定上传会话'),('receipt_key','设备持久化回执键'),('received_at','接收确认时刻'),('item_version','创建上传时扫描项版本'),('record_version','创建上传时目录版本'),('nonce','单次签名请求随机数'),('expires_at','防重放窗口截止时刻')
 ) AS definition(column_name,description)
 LOOP
  EXECUTE (SELECT string_agg(format('COMMENT ON COLUMN %I.%I.%I IS %L',table_schema,table_name,column_name,row.description),'; ')
   FROM information_schema.columns c WHERE c.column_name=row.column_name AND (table_schema,table_name) IN
    (('ops','import_batch'),('ops','import_row'),('archive','legacy_number_map'),('archive','scan_device'),('archive','scan_batch'),('archive','scan_item'),('archive','scan_event'),('archive','scan_receipt'),('archive','scan_upload_binding'),('iam','service_nonce')));
 END LOOP;
END $$;
