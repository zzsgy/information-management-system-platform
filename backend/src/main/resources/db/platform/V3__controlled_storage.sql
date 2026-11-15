-- 第二批公共文件基础；只增加新结构，不修改已执行的 V1/V2 或旧系统数据库。
CREATE SCHEMA storage;

-- 元数据与实体分离；原件在隔离及检测之前不可被业务引用。
CREATE TABLE storage.object (
 id uuid PRIMARY KEY, module varchar(64) NOT NULL, storage_key varchar(256) NOT NULL UNIQUE,
 sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'), byte_size bigint NOT NULL CHECK (byte_size>0),
 media_type varchar(255) NOT NULL, original_name varchar(512) NOT NULL,
 object_kind varchar(64) NOT NULL CHECK (object_kind IN ('ORIGINAL','DERIVED')),
 state varchar(64) NOT NULL CHECK (state IN ('STAGED','QUARANTINED','CHECK_PENDING','READY','RETAINED','GC_PENDING','DELETED')),
 created_by uuid REFERENCES iam.user_account(id), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 check_result_ref varchar(256), retention_guard boolean NOT NULL DEFAULT true,
 service_identity_id uuid REFERENCES iam.service_identity(id),
 CHECK ((created_by IS NOT NULL)::integer + (service_identity_id IS NOT NULL)::integer = 1)
);
CREATE TABLE ops.job (
 id uuid PRIMARY KEY, job_type varchar(64) NOT NULL, module varchar(64) NOT NULL,
 requester_id uuid REFERENCES iam.user_account(id), business_ref varchar(256) NOT NULL,
 input_version integer NOT NULL CHECK (input_version>0), authorization_version integer NOT NULL CHECK (authorization_version>0),
 idempotency_key varchar(256) NOT NULL, state varchar(64) NOT NULL DEFAULT 'PENDING'
 CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
 lease_owner varchar(64), lease_until timestamptz, attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
 next_run_at timestamptz, result_ref varchar(256), error_code varchar(64),
 UNIQUE(requester_id,job_type,idempotency_key)
);
CREATE TABLE storage.upload_session (
 id uuid PRIMARY KEY, module varchar(64) NOT NULL, business_context_id uuid NOT NULL,
 uploader_id uuid REFERENCES iam.user_account(id), service_identity_id uuid REFERENCES iam.service_identity(id),
 expected_sha256 char(64) NOT NULL CHECK (expected_sha256 ~ '^[0-9a-f]{64}$'),
 expected_size bigint NOT NULL CHECK (expected_size>0), expected_media_type varchar(255) NOT NULL,
 idempotency_key varchar(256) NOT NULL, request_digest char(64) NOT NULL,
 state varchar(64) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','CHECK_PENDING','COMPLETE','REJECTED','CANCELLED','EXPIRED')),
 expires_at timestamptz NOT NULL, object_id uuid REFERENCES storage.object(id), job_id uuid REFERENCES ops.job(id),
 result_code varchar(64), cancellation_operation_id uuid, purpose varchar(32) NOT NULL CHECK (purpose IN ('DRIVE_FILE','ARCHIVE_PDF','EVIDENCE','IMPORT_XLSX')),
 original_name varchar(512) NOT NULL, part_size bigint NOT NULL DEFAULT 8388608 CHECK (part_size=8388608),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK ((uploader_id IS NOT NULL)::integer + (service_identity_id IS NOT NULL)::integer = 1),
 CHECK (expires_at>created_at)
);
CREATE UNIQUE INDEX ux_upload_user_operation ON storage.upload_session(uploader_id,idempotency_key) WHERE uploader_id IS NOT NULL;
CREATE UNIQUE INDEX ux_upload_service_operation ON storage.upload_session(service_identity_id,idempotency_key) WHERE service_identity_id IS NOT NULL;
CREATE TABLE storage.upload_part (
 upload_session_id uuid NOT NULL REFERENCES storage.upload_session(id), part_no integer NOT NULL CHECK (part_no>0),
 "offset" bigint NOT NULL CHECK ("offset">=0), byte_size bigint NOT NULL CHECK (byte_size>0 AND byte_size<=8388608),
 sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'), staging_key varchar(256) NOT NULL UNIQUE,
 received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(upload_session_id,part_no)
);
CREATE INDEX ix_upload_owner_state ON storage.upload_session(uploader_id,state,created_at DESC);
CREATE INDEX ix_upload_object ON storage.upload_session(object_id);
CREATE INDEX ix_job_pending ON ops.job(state,next_run_at);
CREATE INDEX ix_job_requester ON ops.job(requester_id,job_type);
CREATE INDEX ix_storage_object_state ON storage.object(state,created_at DESC);

-- 注释以静态白名单生成；不含任何实际文件名、用户身份或秘密。
COMMENT ON TABLE storage.object IS '不可变文件对象的元数据，原件在隔离检测通过前不可交付';
COMMENT ON TABLE storage.upload_session IS '绑定领域上下文、上传人、用途及预期摘要的分片会话';
COMMENT ON TABLE storage.upload_part IS '固定范围和摘要的已接收分片，不允许覆盖已有分片';
COMMENT ON TABLE ops.job IS '公共后台任务、租约及脱敏结果，待检测不等于业务发布';
DO $$ DECLARE item record; BEGIN
 FOR item IN SELECT * FROM (VALUES
 ('storage','object','id','对象标识'),('storage','object','module','所属业务模块'),('storage','object','storage_key','内部随机存储键'),('storage','object','sha256','完整内容的SHA256摘要'),('storage','object','byte_size','完整文件字节数'),('storage','object','media_type','声明或检测后的媒体类型'),('storage','object','original_name','固定来源文件名'),('storage','object','object_kind','原件或派生对象类别'),('storage','object','state','对象隔离及可用状态'),('storage','object','created_by','员工创建者'),('storage','object','created_at','创建时刻'),('storage','object','check_result_ref','受控检测结果引用'),('storage','object','retention_guard','保留与引用保护标记'),('storage','object','service_identity_id','服务创建者'),
 ('storage','upload_session','id','上传会话标识'),('storage','upload_session','module','所属业务模块'),('storage','upload_session','business_context_id','领域上下文标识'),('storage','upload_session','uploader_id','员工上传者'),('storage','upload_session','service_identity_id','服务上传者'),('storage','upload_session','expected_sha256','声明的完整摘要'),('storage','upload_session','expected_size','声明的完整字节数'),('storage','upload_session','expected_media_type','声明媒体类型'),('storage','upload_session','idempotency_key','创建操作稳定幂等键'),('storage','upload_session','request_digest','创建请求的非秘密规范摘要'),('storage','upload_session','state','上传会话状态'),('storage','upload_session','expires_at','上传会话截止时刻'),('storage','upload_session','object_id','最终隔离对象标识'),('storage','upload_session','job_id','检测任务标识'),('storage','upload_session','result_code','脱敏处理结果代码'),('storage','upload_session','cancellation_operation_id','取消操作的稳定标识'),('storage','upload_session','purpose','受控业务用途'),('storage','upload_session','original_name','固定来源文件名'),('storage','upload_session','part_size','固定分片字节数'),('storage','upload_session','created_at','创建时刻'),
 ('storage','upload_part','upload_session_id','所属上传会话'),('storage','upload_part','part_no','从1开始的分片序号'),('storage','upload_part','offset','分片起始字节'),('storage','upload_part','byte_size','分片字节数'),('storage','upload_part','sha256','分片SHA256摘要'),('storage','upload_part','staging_key','内部随机暂存键'),('storage','upload_part','received_at','分片完成接收时刻'),
 ('ops','job','id','后台任务标识'),('ops','job','job_type','任务类别'),('ops','job','module','所属模块'),('ops','job','requester_id','任务发起人'),('ops','job','business_ref','固定业务上下文引用'),('ops','job','input_version','输入版本'),('ops','job','authorization_version','发起时授权代次'),('ops','job','idempotency_key','稳定操作号'),('ops','job','state','任务状态'),('ops','job','lease_owner','租约持有者'),('ops','job','lease_until','租约截止时刻'),('ops','job','attempt_count','已尝试次数'),('ops','job','next_run_at','下次执行时间'),('ops','job','result_ref','受控结果引用'),('ops','job','error_code','脱敏失败代码')
 ) AS definitions(schema_name,table_name,column_name,description)
 LOOP EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',item.schema_name,item.table_name,item.column_name,item.description); END LOOP;
END $$;
