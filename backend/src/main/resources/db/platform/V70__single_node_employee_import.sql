-- 仅单机员工预检可没有文件库原件。历史批次和各档案引用保持原样。
ALTER TABLE ops.import_batch ALTER COLUMN upload_object_id DROP NOT NULL;
ALTER TABLE ops.import_batch ADD CONSTRAINT ck_import_batch_source
 CHECK (upload_object_id IS NOT NULL OR import_type = 'EMPLOYEE');
