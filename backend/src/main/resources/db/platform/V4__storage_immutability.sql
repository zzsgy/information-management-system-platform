-- 在数据库层固定上传意图及原件字节身份；状态转换仍由业务事务控制。
CREATE FUNCTION storage.reject_upload_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.module,NEW.business_context_id,NEW.uploader_id,NEW.service_identity_id,NEW.expected_sha256,
        NEW.expected_size,NEW.expected_media_type,NEW.idempotency_key,NEW.request_digest,NEW.expires_at,
        NEW.purpose,NEW.original_name,NEW.part_size,NEW.created_at)
    IS DISTINCT FROM
    ROW(OLD.id,OLD.module,OLD.business_context_id,OLD.uploader_id,OLD.service_identity_id,OLD.expected_sha256,
        OLD.expected_size,OLD.expected_media_type,OLD.idempotency_key,OLD.request_digest,OLD.expires_at,
        OLD.purpose,OLD.original_name,OLD.part_size,OLD.created_at)
 THEN RAISE EXCEPTION 'upload identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER upload_identity_immutable BEFORE UPDATE ON storage.upload_session FOR EACH ROW EXECUTE FUNCTION storage.reject_upload_identity_change();
COMMENT ON FUNCTION storage.reject_upload_identity_change() IS '禁止修改已创建的上传业务上下文及固定文件声明';

CREATE FUNCTION storage.reject_object_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.module,NEW.storage_key,NEW.sha256,NEW.byte_size,NEW.original_name,NEW.object_kind,NEW.created_by,NEW.created_at,NEW.service_identity_id)
    IS DISTINCT FROM
    ROW(OLD.id,OLD.module,OLD.storage_key,OLD.sha256,OLD.byte_size,OLD.original_name,OLD.object_kind,OLD.created_by,OLD.created_at,OLD.service_identity_id)
 THEN RAISE EXCEPTION 'file identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER object_identity_immutable BEFORE UPDATE ON storage.object FOR EACH ROW EXECUTE FUNCTION storage.reject_object_identity_change();
COMMENT ON FUNCTION storage.reject_object_identity_change() IS '禁止原地修改原件和派生文件的内容身份，检测状态可按规则更新';

CREATE FUNCTION storage.reject_part_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'upload parts are immutable'; END $$;
CREATE TRIGGER upload_part_immutable BEFORE UPDATE OR DELETE ON storage.upload_part FOR EACH ROW EXECUTE FUNCTION storage.reject_part_rewrite();
COMMENT ON FUNCTION storage.reject_part_rewrite() IS '阻止普通业务操作覆盖或删除已接收分片，保留策略通过专门受控维护处理';
