-- 人工逐页质量确认独立于病毒检测与最终归档复核；只保存有责任主体的确认，不伪造算法质检。
ALTER TABLE archive.pdf_version ADD COLUMN page_count integer NOT NULL DEFAULT 0 CHECK(page_count>=0);
COMMENT ON COLUMN archive.pdf_version.page_count IS '隔离PDF进程实测页数，人工质量确认须覆盖全部页';
CREATE TABLE archive.quality_check (
 id uuid PRIMARY KEY,object_type varchar(16) NOT NULL CHECK(object_type IN ('BOX','RECORD')),object_id uuid NOT NULL,revision_id uuid NOT NULL,pdf_version_id uuid REFERENCES archive.pdf_version(id),
 checked_by uuid NOT NULL REFERENCES iam.user_account(id),checked_page_count integer NOT NULL CHECK(checked_page_count>=0),result varchar(16) NOT NULL CHECK(result='PASS'),
 notes varchar(2000) NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE archive.quality_check IS '获权人员完成逐页人工检查后的责任确认，独立于病毒检测与最终复核';
COMMENT ON COLUMN archive.quality_check.id IS '人工质量确认标识';
COMMENT ON COLUMN archive.quality_check.object_type IS '档案盒或文件类型';
COMMENT ON COLUMN archive.quality_check.object_id IS '质量检查所属业务对象';
COMMENT ON COLUMN archive.quality_check.revision_id IS '固定受检目录修订';
COMMENT ON COLUMN archive.quality_check.pdf_version_id IS '固定受检PDF版本，盒确认时为空';
COMMENT ON COLUMN archive.quality_check.checked_by IS '真实人工检查责任人';
COMMENT ON COLUMN archive.quality_check.checked_page_count IS '本次人工确认已逐页核验的页数';
COMMENT ON COLUMN archive.quality_check.result IS '人工明确确认结果';
COMMENT ON COLUMN archive.quality_check.notes IS '清晰度页序完整性等人工检查结论';
COMMENT ON COLUMN archive.quality_check.created_at IS '检查责任确认时刻';
COMMENT ON COLUMN archive.usage_request.review_context_type IS '归档复核或送审前质量整理上下文类型';
CREATE TRIGGER quality_immutable BEFORE UPDATE OR DELETE ON archive.quality_check FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE FUNCTION archive.guard_pdf_identity() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN IF TG_OP='DELETE' OR ROW(NEW.id,NEW.record_id,NEW.version_no,NEW.object_id,NEW.source,NEW.correction_reason,NEW.created_by,NEW.created_at,NEW.page_count) IS DISTINCT FROM ROW(OLD.id,OLD.record_id,OLD.version_no,OLD.object_id,OLD.source,OLD.correction_reason,OLD.created_by,OLD.created_at,OLD.page_count) THEN RAISE EXCEPTION 'immutable archive pdf identity'; END IF; RETURN NEW; END; $$;
CREATE TRIGGER archive_pdf_identity BEFORE UPDATE OR DELETE ON archive.pdf_version FOR EACH ROW EXECUTE FUNCTION archive.guard_pdf_identity();
