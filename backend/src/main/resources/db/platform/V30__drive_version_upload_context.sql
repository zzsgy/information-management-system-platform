-- 新版本使用文档级EDIT上下文，不能把目录UPLOAD授权当成已有文件编辑权。
ALTER TABLE storage.upload_session DROP CONSTRAINT upload_session_purpose_check;
ALTER TABLE storage.upload_session ADD CONSTRAINT upload_session_purpose_check CHECK(purpose IN ('DRIVE_FILE','DRIVE_VERSION','ARCHIVE_PDF','EVIDENCE','IMPORT_XLSX'));
ALTER TABLE storage.upload_session ADD CONSTRAINT drive_version_module_check CHECK(purpose<>'DRIVE_VERSION' OR module='DRIVE');
