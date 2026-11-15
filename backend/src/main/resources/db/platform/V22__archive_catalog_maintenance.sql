-- 目录更正先保存候选；已发布版本直到必要复核完成才切换。仅撤下，不删除历史或实物借还。
ALTER TABLE platform_meta.instance ADD COLUMN catalog_epoch integer NOT NULL DEFAULT 0 CHECK(catalog_epoch>=0);
COMMENT ON COLUMN platform_meta.instance.catalog_epoch IS '目录安全变更水位；与库外账本比对，旧备份不得复活撤下或旧版本访问';
ALTER TABLE archive.directory_template ADD COLUMN fields jsonb NOT NULL DEFAULT '[]',ADD COLUMN row_version integer NOT NULL DEFAULT 1;
COMMENT ON COLUMN archive.directory_template.fields IS '不可变字段定义：代码、名称、类型、必填、关键、查询显示、顺序、选项及停用标记';
COMMENT ON COLUMN archive.directory_template.row_version IS '启停并发版本，定义修订必须另建模板版本';
CREATE TABLE archive.catalog_change (
 id uuid PRIMARY KEY,object_type varchar(16) NOT NULL CHECK(object_type IN ('BOX','RECORD')),object_id uuid NOT NULL,
 department_id uuid NOT NULL REFERENCES iam.department(id),kind varchar(16) NOT NULL CHECK(kind IN ('GENERAL','CORRECTION','WITHDRAWAL')),
 state varchar(16) NOT NULL CHECK(state IN ('DRAFT','PENDING','APPLIED','REJECTED','WITHDRAWN')),
 before_snapshot jsonb NOT NULL,after_snapshot jsonb NOT NULL,member_snapshot jsonb NOT NULL DEFAULT '[]',
 base_version integer NOT NULL,candidate_pdf_id uuid REFERENCES archive.pdf_version(id),candidate_revision_id uuid,
 reason varchar(2000) NOT NULL,created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 quality_confirmed_at timestamptz,quality_notes varchar(2000),workflow_id uuid REFERENCES workflow.instance(id),
 applied_at timestamptz,row_version integer NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX ux_catalog_pending ON archive.catalog_change(object_type,object_id) WHERE state IN ('DRAFT','PENDING');
CREATE INDEX ix_catalog_changes_scope ON archive.catalog_change(department_id,created_at DESC,id);
CREATE INDEX ix_catalog_record_history ON archive.catalog_change(object_type,object_id,created_at DESC,id);
CREATE INDEX ix_archive_retention_box ON archive.box(expiry_date,id) WHERE state='PUBLISHED' AND expiry_date IS NOT NULL;
CREATE INDEX ix_archive_retention_record ON archive.record(expiry_date,id) WHERE state='PUBLISHED' AND expiry_date IS NOT NULL;
CREATE TABLE archive.catalog_number_reservation(identity_key varchar(256) PRIMARY KEY,object_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
COMMENT ON TABLE archive.catalog_number_reservation IS '编号更正及移盒后原编号、盒内件号永久保留，禁止再次分配给其他对象';
COMMENT ON COLUMN archive.catalog_number_reservation.identity_key IS '带类型的盒号、文件号或盒内件号唯一键';
COMMENT ON COLUMN archive.catalog_number_reservation.object_id IS '首次占用此编号的稳定业务对象';
COMMENT ON COLUMN archive.catalog_number_reservation.created_at IS '首次登记保留时间';
INSERT INTO archive.catalog_number_reservation(identity_key,object_id) SELECT 'BOX:'||box_no,id FROM archive.box UNION ALL SELECT 'FILE:'||file_no,id FROM archive.record UNION ALL SELECT 'SLOT:'||box_id||':'||item_no,id FROM archive.record;
CREATE FUNCTION archive.reserve_catalog_identity() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE identity text; BEGIN FOR identity IN SELECT unnest(CASE WHEN TG_TABLE_NAME='box' THEN ARRAY['BOX:'||(to_jsonb(NEW)->>'box_no')] ELSE ARRAY['FILE:'||(to_jsonb(NEW)->>'file_no'),'SLOT:'||(to_jsonb(NEW)->>'box_id')||':'||(to_jsonb(NEW)->>'item_no')] END) LOOP
 INSERT INTO archive.catalog_number_reservation(identity_key,object_id) VALUES(identity,NEW.id) ON CONFLICT DO NOTHING;
 IF NOT EXISTS(SELECT 1 FROM archive.catalog_number_reservation WHERE identity_key=identity AND object_id=NEW.id) THEN RAISE EXCEPTION 'catalog number already reserved'; END IF;END LOOP;RETURN NEW;END $$;
CREATE TRIGGER box_number_reservation BEFORE INSERT OR UPDATE OF box_no ON archive.box FOR EACH ROW EXECUTE FUNCTION archive.reserve_catalog_identity();
CREATE TRIGGER record_number_reservation BEFORE INSERT OR UPDATE OF file_no,box_id,item_no ON archive.record FOR EACH ROW EXECUTE FUNCTION archive.reserve_catalog_identity();
ALTER TABLE workflow.instance DROP CONSTRAINT instance_business_type_check;
ALTER TABLE workflow.instance ADD CHECK(business_type IN ('REGISTRATION','BORROW','ACCESS_POLICY','CATALOG_CHANGE'));
INSERT INTO workflow.template_version(id,template_key,version_no,business_type,route_kind,nodes_definition,checksum)
 SELECT '30000000-0000-4000-8000-000000000004'::uuid,'ARCHIVE_CATALOG_CHANGE_V1',1,'CATALOG_CHANGE','FIXED',definition::jsonb,encode(sha256(convert_to((definition::jsonb)::text,'UTF8')),'hex')
 FROM(VALUES('{"correction":["OWNER_MANAGER","ARCHIVE_ADMIN"],"withdrawal":["ARCHIVE_ADMIN"],"selfApproval":false}')) t(definition);
-- 盒与全部成员在同一事务纠错；约束延后到事务完成，不开放跨部门日常移交。
ALTER TABLE archive.record ALTER CONSTRAINT record_box_id_department_id_classification_fkey DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE archive.physical_item ALTER CONSTRAINT physical_item_record_id_box_id_fkey DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION archive.catalog_manager(person uuid,department uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT iam.archive_access_enabled(person) AND iam.action_enabled(person,'ARCHIVE.CATALOG_MANAGE') AND EXISTS(
 SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND r.status='ACTIVE' AND u.status='ACTIVE'
 AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=department AND u.department_id=department)));
$$;
-- 增加两种通知来源，原投递重试、幂等与人工队列保持复用。
ALTER TABLE ops.business_notification DROP CONSTRAINT business_notification_source_kind_check;
ALTER TABLE ops.business_notification ADD CHECK(source_kind IN ('WORKFLOW_STEP','WORKFLOW_INSTANCE','ELECTRONIC_REMINDER','PHYSICAL_NOTICE','CATALOG_CHANGE','RETENTION'));
ALTER FUNCTION ops.notification_current(uuid,uuid) RENAME TO notification_current_v21;
CREATE FUNCTION ops.notification_current(notice uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT ops.notification_current_v21(notice,person) OR EXISTS(SELECT 1 FROM ops.business_notification n WHERE n.id=notice AND n.user_id=person AND n.state<>'CANCELLED' AND CASE n.source_kind
 WHEN 'CATALOG_CHANGE' THEN EXISTS(SELECT 1 FROM archive.catalog_change c WHERE c.id=n.source_id AND c.state='APPLIED' AND archive.catalog_manager(person,c.department_id))
 WHEN 'RETENTION' THEN EXISTS(SELECT 1 FROM (SELECT id,department_id,expiry_date,state FROM archive.box UNION ALL SELECT r.id,r.department_id,r.expiry_date,CASE WHEN b.state='PUBLISHED' THEN r.state ELSE b.state END FROM archive.record r JOIN archive.box b ON b.id=r.box_id) o WHERE o.id=n.source_id AND o.state='PUBLISHED' AND o.expiry_date::text=n.source_version AND archive.catalog_manager(person,o.department_id))
 ELSE false END);
$$;
CREATE FUNCTION archive.guard_catalog_change() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN IF TG_OP='DELETE' OR ROW(NEW.id,NEW.object_type,NEW.object_id,NEW.department_id,NEW.kind,NEW.before_snapshot,NEW.after_snapshot,NEW.member_snapshot,NEW.base_version,NEW.candidate_pdf_id,NEW.candidate_revision_id,NEW.reason,NEW.created_by,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.object_type,OLD.object_id,OLD.department_id,OLD.kind,OLD.before_snapshot,OLD.after_snapshot,OLD.member_snapshot,OLD.base_version,OLD.candidate_pdf_id,OLD.candidate_revision_id,OLD.reason,OLD.created_by,OLD.created_at) OR OLD.state IN ('APPLIED','REJECTED','WITHDRAWN') THEN RAISE EXCEPTION 'immutable catalog evidence'; END IF;RETURN NEW;END $$;
CREATE TRIGGER catalog_change_evidence BEFORE UPDATE OR DELETE ON archive.catalog_change FOR EACH ROW EXECUTE FUNCTION archive.guard_catalog_change();
CREATE FUNCTION archive.guard_directory_definition() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN IF TG_OP='DELETE' OR ROW(NEW.id,NEW.template_key,NEW.version_no,NEW.category_code,NEW.object_type,NEW.name,NEW.fields,NEW.created_by,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.template_key,OLD.version_no,OLD.category_code,OLD.object_type,OLD.name,OLD.fields,OLD.created_by,OLD.created_at) THEN RAISE EXCEPTION 'immutable directory definition'; END IF;RETURN NEW;END $$;
CREATE TRIGGER directory_definition_immutable BEFORE UPDATE OR DELETE ON archive.directory_template FOR EACH ROW EXECUTE FUNCTION archive.guard_directory_definition();
COMMENT ON TABLE archive.catalog_change IS '目录普通编辑、关键更正及撤下的不可变前后证据；未批准不改变发布内容';
DO $$ DECLARE col record; labels jsonb:='{"id":"更正事项标识","object_type":"盒或文件类型","object_id":"目标稳定标识","department_id":"提交时所属部门","kind":"普通编辑、审批更正或审批撤下","state":"候选及办理状态","before_snapshot":"提交前完整目录快照","after_snapshot":"固定拟生效目录快照","member_snapshot":"整盒操作的冻结成员及版本","base_version":"原对象并发版本","candidate_pdf_id":"新PDF候选，原版仍保留","candidate_revision_id":"新PDF候选对应不可变修订","reason":"更正或撤下的真实业务原因","created_by":"实际提交员工","created_at":"提交时间","quality_confirmed_at":"候选逐页人工确认时间","quality_notes":"人工检查说明","workflow_id":"固定复核流程","applied_at":"实际生效时间","row_version":"事项并发版本"}'::jsonb; BEGIN FOR col IN SELECT column_name FROM information_schema.columns WHERE table_schema='archive' AND table_name='catalog_change' LOOP EXECUTE format('COMMENT ON COLUMN archive.catalog_change.%I IS %L',col.column_name,labels->>col.column_name);END LOOP;END $$;
