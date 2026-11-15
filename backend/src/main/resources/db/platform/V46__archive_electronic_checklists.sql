-- 电子清单保存原件及来源，纸质份数在记录的通用模板扩展字段 copyCount 中维护。
CREATE TABLE archive.electronic_checklist (
 id uuid PRIMARY KEY,box_id uuid NOT NULL REFERENCES archive.box(id),object_id uuid NOT NULL REFERENCES storage.object(id),
 row_count integer NOT NULL CHECK(row_count BETWEEN 1 AND 1000),created_by uuid NOT NULL REFERENCES iam.user_account(id),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(box_id,object_id)
);
COMMENT ON TABLE archive.electronic_checklist IS '档案盒电子清单导入来源；保留真实检测后的Excel原件，不按纸质份数复制电子文件';
ALTER TABLE archive.record ADD COLUMN checklist_id uuid REFERENCES archive.electronic_checklist(id);
COMMENT ON COLUMN archive.record.checklist_id IS '来源电子清单；序号沿用item_no，日期沿用formed_date，纸质份数沿用extension_values.copyCount';
CREATE INDEX ix_archive_record_checklist ON archive.record(checklist_id,item_no);
CREATE TABLE archive.electronic_version (
 id uuid PRIMARY KEY,record_id uuid NOT NULL REFERENCES archive.record(id),version_no integer NOT NULL CHECK(version_no>0),
 original_object_id uuid NOT NULL REFERENCES storage.object(id),pdf_version_id uuid REFERENCES archive.pdf_version(id),
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','READY','FAILED')),error_message varchar(512),
 created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(record_id,version_no),UNIQUE(record_id,original_object_id)
);
COMMENT ON TABLE archive.electronic_version IS '每条清单只挂接一份当前电子文件；重新上传追加历史版本，Office原件及受控PDF分别保留';
ALTER TABLE archive.record ADD COLUMN electronic_version_id uuid REFERENCES archive.electronic_version(id);
COMMENT ON COLUMN archive.record.electronic_version_id IS '当前清单电子文件版本；仅草稿可替换，旧原件和PDF历史保留';
CREATE TABLE archive.office_conversion (
 id uuid PRIMARY KEY,version_id uuid NOT NULL UNIQUE REFERENCES archive.electronic_version(id),
 session_id uuid NOT NULL REFERENCES iam.session(id),requester_id uuid NOT NULL REFERENCES iam.user_account(id),
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE archive.office_conversion IS '档案Office转换的限时签名取源上下文，不复用公盘文档权限';
ALTER TABLE storage.upload_session DROP CONSTRAINT upload_session_purpose_check;
ALTER TABLE storage.upload_session ADD CONSTRAINT upload_session_purpose_check CHECK(purpose IN ('DRIVE_FILE','DRIVE_VERSION','ARCHIVE_PDF','EVIDENCE','IMPORT_XLSX','ARCHIVE_CHECKLIST','ARCHIVE_ATTACHMENT'));
