ALTER TABLE storage.preview_session ADD COLUMN state varchar(32) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','READY','FAILED','INVALIDATED'));
ALTER TABLE storage.preview_session ADD COLUMN page_count integer NOT NULL DEFAULT 0 CHECK(page_count>=0);
ALTER TABLE storage.preview_session ADD COLUMN job_id uuid REFERENCES ops.job(id);
COMMENT ON COLUMN storage.preview_session.state IS '页数处理和阅读会话的当前状态';
COMMENT ON COLUMN storage.preview_session.page_count IS '隔离进程解析的真实页数';
COMMENT ON COLUMN storage.preview_session.job_id IS '关联的页面元数据任务';
CREATE TABLE storage.object_reference (
 id uuid PRIMARY KEY,object_id uuid NOT NULL REFERENCES storage.object(id),owner_type varchar(64) NOT NULL,owner_id uuid NOT NULL,reference_kind varchar(64) NOT NULL,
 retain_until timestamptz,state varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','RELEASED')),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,released_at timestamptz
);
CREATE UNIQUE INDEX ux_storage_reference ON storage.object_reference(object_id,owner_type,owner_id,reference_kind) WHERE state='ACTIVE';
COMMENT ON TABLE storage.object_reference IS '业务版本引用的不可变文件保护索引，业务清除不立即删除实体';
COMMENT ON COLUMN storage.object_reference.id IS '引用记录标识';
COMMENT ON COLUMN storage.object_reference.object_id IS '被保留的不可变文件对象';
COMMENT ON COLUMN storage.object_reference.owner_type IS '来源对象类型';
COMMENT ON COLUMN storage.object_reference.owner_id IS '来源业务对象';
COMMENT ON COLUMN storage.object_reference.reference_kind IS '业务或恢复引用类型';
COMMENT ON COLUMN storage.object_reference.retain_until IS '引用保护期限';
COMMENT ON COLUMN storage.object_reference.state IS '引用状态';
COMMENT ON COLUMN storage.object_reference.created_at IS '创建时刻';
COMMENT ON COLUMN storage.object_reference.released_at IS '解除时刻';
