-- 手工补录没有 XLSX 原件，但同样保留清单来源和档案盒关联。
ALTER TABLE archive.electronic_checklist ALTER COLUMN object_id DROP NOT NULL;
COMMENT ON COLUMN archive.electronic_checklist.object_id IS '经检测的 XLSX 原件；手工补录为 NULL';

-- 新建档案盒之前按所属部门限定清单上传和检测范围。
ALTER TABLE storage.upload_session DROP CONSTRAINT upload_session_purpose_check;
ALTER TABLE storage.upload_session ADD CONSTRAINT upload_session_purpose_check
  CHECK(purpose IN ('DRIVE_FILE','DRIVE_VERSION','ARCHIVE_PDF','EVIDENCE','IMPORT_XLSX','ARCHIVE_CHECKLIST','ARCHIVE_ATTACHMENT','ARCHIVE_REGISTRATION_CHECKLIST'));
