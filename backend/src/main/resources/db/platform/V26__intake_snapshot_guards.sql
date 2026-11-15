-- 台账输入、扫描绑定和已接收事实只追加，后续状态迁移不能改写原快照。
CREATE FUNCTION ops.guard_import_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'import history cannot be deleted'; END IF;
 IF TG_TABLE_NAME='import_batch' THEN
  IF ROW(NEW.id,NEW.import_type,NEW.upload_object_id,NEW.template_version,NEW.department_scope,NEW.created_by,NEW.created_at,NEW.row_count,NEW.error_count,NEW.validation_digest)
   IS DISTINCT FROM ROW(OLD.id,OLD.import_type,OLD.upload_object_id,OLD.template_version,OLD.department_scope,OLD.created_by,OLD.created_at,OLD.row_count,OLD.error_count,OLD.validation_digest)
   THEN RAISE EXCEPTION 'import snapshot is immutable'; END IF;
 ELSE
  IF ROW(NEW.batch_id,NEW.row_no,NEW.input_data,NEW.errors) IS DISTINCT FROM ROW(OLD.batch_id,OLD.row_no,OLD.input_data,OLD.errors)
   THEN RAISE EXCEPTION 'import row snapshot is immutable'; END IF;
  IF OLD.object_id IS NOT NULL AND ROW(NEW.object_id,NEW.object_type) IS DISTINCT FROM ROW(OLD.object_id,OLD.object_type)
   THEN RAISE EXCEPTION 'import result cannot be rebound'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER import_batch_snapshot BEFORE UPDATE OR DELETE ON ops.import_batch FOR EACH ROW EXECUTE FUNCTION ops.guard_import_snapshot();
CREATE TRIGGER import_row_snapshot BEFORE UPDATE OR DELETE ON ops.import_row FOR EACH ROW EXECUTE FUNCTION ops.guard_import_snapshot();
CREATE TRIGGER scan_upload_binding_immutable BEFORE UPDATE OR DELETE ON archive.scan_upload_binding FOR EACH ROW EXECUTE FUNCTION archive.intake_history_immutable();
-- 固定清单身份、获准设备和所属部门不可由后续处理静默改变。
CREATE FUNCTION archive.guard_scan_batch_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR ROW(NEW.id,NEW.department_id,NEW.device_id,NEW.external_key,NEW.request_digest,NEW.created_by,NEW.created_at,NEW.expected_count)
   IS DISTINCT FROM ROW(OLD.id,OLD.department_id,OLD.device_id,OLD.external_key,OLD.request_digest,OLD.created_by,OLD.created_at,OLD.expected_count)
 THEN RAISE EXCEPTION 'scan batch identity is immutable'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER scan_batch_identity BEFORE UPDATE OR DELETE ON archive.scan_batch FOR EACH ROW EXECUTE FUNCTION archive.guard_scan_batch_identity();
