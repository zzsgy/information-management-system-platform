-- 2026-10-30 新确认：公司编号 BLK27；IT 管理员统一拥有各模块管理及内容操作权限。
-- 保留已建人员和部门 ID；按代码或名称补齐组织，不创建任何员工。
INSERT INTO iam.department(id,code,name) SELECT gen_random_uuid(),'ROOT','公司'
WHERE NOT EXISTS(SELECT 1 FROM iam.department WHERE code='ROOT');
DO $$ DECLARE item record; target_department_id uuid; root_id uuid; BEGIN
 SELECT id INTO root_id FROM iam.department WHERE code='ROOT';
 FOR item IN SELECT * FROM (VALUES ('AD','行政部',10),('DHS/MEP','安全环保部',20),('YF','研发部',30),
 ('SC','生产运营部',40),('SD','仓储部',50),('QC','质量检定部',60),('QA','质量保证部',70),('PD','采购部',80),('ED','设备项目部',90)) AS d(code,name,sort_order) LOOP
  IF (SELECT count(*) FROM iam.department d WHERE d.code=item.code OR d.name=item.name)>1 THEN
   RAISE EXCEPTION 'ambiguous built-in department: %',item.name;
  END IF;
  SELECT id INTO target_department_id FROM iam.department d WHERE d.code=item.code OR d.name=item.name;
  IF target_department_id IS NULL THEN
   INSERT INTO iam.department(id,code,name,parent_id,sort_order) VALUES(gen_random_uuid(),item.code,item.name,root_id,item.sort_order);
  ELSE
   UPDATE iam.department SET code=item.code,name=item.name,parent_id=root_id,sort_order=item.sort_order,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=target_department_id;
  END IF;
 END LOOP;
END $$;
UPDATE iam.role SET name='IT管理员（全系统）',row_version=row_version+1 WHERE module='PLATFORM' AND code='IT_ADMIN';

CREATE FUNCTION iam.system_administrator(person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND r.module='PLATFORM' AND r.code='IT_ADMIN' AND a.state='ACTIVE' AND r.status='ACTIVE'
 AND u.status='ACTIVE' AND NOT u.password_setup_required AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP));
$$;
COMMENT ON FUNCTION iam.system_administrator(uuid) IS '全系统 IT 权限来自当前有效平台 IT 任职，不能由账号名或普通公盘管理员身份推导';
CREATE OR REPLACE FUNCTION iam.action_enabled(person uuid,action_code text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT iam.system_administrator(person) OR (SELECT COALESCE(bool_or(rp.effect='ALLOW'),false) AND NOT COALESCE(bool_or(rp.effect='DENY'),false)
 AND NOT EXISTS(SELECT 1 FROM iam.permission_assignment pa JOIN iam.permission p ON p.id=pa.permission_id WHERE pa.user_id=person AND p.code=action_code AND pa.state='ACTIVE' AND pa.effect='DENY' AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP))
 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.role_permission rp ON rp.role_id=r.id JOIN iam.permission p ON p.id=rp.permission_id AND p.module=r.module JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND u.status='ACTIVE' AND NOT u.password_setup_required AND a.state='ACTIVE' AND r.status='ACTIVE' AND rp.scope_policy='COMPANY' AND p.code=action_code AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP));
$$;
CREATE OR REPLACE FUNCTION iam.archive_access_enabled(person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT iam.system_administrator(person) OR (SELECT COALESCE(bool_or(rp.effect='ALLOW'),false) AND NOT COALESCE(bool_or(rp.effect='DENY'),false)
 AND NOT EXISTS(SELECT 1 FROM iam.permission_assignment pa JOIN iam.permission p ON p.id=pa.permission_id
  WHERE pa.user_id=person AND p.code='ARCHIVE.ACCESS' AND pa.state='ACTIVE' AND pa.effect='DENY'
  AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP))
 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id JOIN iam.role_permission rp ON rp.role_id=role.id
 JOIN iam.permission p ON p.id=rp.permission_id AND p.module=role.module JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND role.status='ACTIVE' AND u.status='ACTIVE'
 AND NOT u.password_setup_required AND p.code='ARCHIVE.ACCESS' AND rp.scope_policy='COMPANY'
 AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP));
$$;
CREATE OR REPLACE FUNCTION archive.catalog_manager(person uuid,department uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT iam.system_administrator(person) OR (SELECT iam.archive_access_enabled(person) AND iam.action_enabled(person,'ARCHIVE.CATALOG_MANAGE') AND EXISTS(
 SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND r.status='ACTIVE' AND u.status='ACTIVE'
 AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=department AND u.department_id=department))));
$$;
CREATE OR REPLACE FUNCTION drive.manager_scope(person uuid,target uuid,act text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT (iam.system_administrator(person) AND EXISTS(SELECT 1 FROM drive.active_path(target))) OR EXISTS(SELECT 1 FROM drive.active_path(target) p JOIN drive.delegation d ON d.folder_id=p.id
 JOIN drive.folder leaf ON leaf.id=target AND leaf.department_id=d.department_id
 JOIN iam.user_role ur ON ur.role_id=d.role_id AND ur.department_id=d.department_id AND ur.user_id=person
 JOIN iam.role r ON r.id=ur.role_id JOIN iam.department_manager m ON m.user_id=person AND m.department_id=d.department_id
 JOIN iam.user_account u ON u.id=person AND u.status='ACTIVE'
 WHERE d.state='ACTIVE' AND act=ANY(d.allowed_actions) AND ur.module='DRIVE' AND ur.role_code='DEPARTMENT_MANAGER' AND ur.state='ACTIVE' AND r.status='ACTIVE'
 AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP)
 AND m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP));
$$;
CREATE OR REPLACE FUNCTION drive.can_folder(person uuid,target uuid,act text) RETURNS boolean LANGUAGE sql STABLE AS $$
 WITH path AS MATERIALIZED (SELECT * FROM drive.active_path(target)),
 rules AS MATERIALIZED (SELECT g.* FROM drive.resource_grant g JOIN path p ON p.id=g.resource_id WHERE g.subject_id=person AND g.resource_type='FOLDER' AND g.action=act AND g.state='ACTIVE'),
 roles AS MATERIALIZED (SELECT ur.role_code FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id WHERE ur.user_id=person AND ur.module='DRIVE' AND ur.state='ACTIVE' AND r.status='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP))
 SELECT EXISTS(SELECT 1 FROM path f JOIN iam.user_account u ON u.id=person AND u.status='ACTIVE' WHERE f.id=target
 AND (iam.system_administrator(person) OR (NOT EXISTS(SELECT 1 FROM rules WHERE effect='DENY') AND (
 drive.manager_scope(person,target,act)
 OR (act IN ('VIEW','DOWNLOAD') AND (f.zone<>'DEPARTMENT_INTERNAL' OR u.department_id=f.department_id
 OR EXISTS(SELECT 1 FROM roles WHERE role_code IN ('IT_ADMIN','GENERAL_MANAGER'))
 OR (EXISTS(SELECT 1 FROM roles WHERE role_code='DEPARTMENT_MANAGER') AND EXISTS(
 WITH RECURSIVE parents AS (SELECT id,parent_id FROM iam.department WHERE id=f.department_id UNION SELECT d.id,d.parent_id FROM iam.department d JOIN parents p ON d.id=p.parent_id)
 SELECT 1 FROM parents p JOIN iam.department_manager m ON m.department_id=p.id AND m.user_id=person WHERE m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)))))
 OR (act IN ('VIEW','DOWNLOAD','UPLOAD','DELETE','MOVE','RESTORE') AND EXISTS(SELECT 1 FROM rules g JOIN drive.delegation d ON d.id=g.delegation_id JOIN path p ON p.id=d.folder_id
 WHERE g.effect='ALLOW' AND d.state='ACTIVE' AND act=ANY(d.allowed_actions) AND d.department_id=f.department_id))))));
$$;
CREATE OR REPLACE FUNCTION drive.can_document(person uuid,target uuid,act text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM drive.document doc JOIN iam.user_account u ON u.id=person AND u.status='ACTIVE'
 WHERE doc.id=target
 AND EXISTS(SELECT 1 FROM drive.active_path(doc.folder_id))
 AND ((iam.system_administrator(person) AND (act<>'EDIT' OR doc.collaboration_enabled)) OR (NOT iam.system_administrator(person) AND NOT EXISTS(SELECT 1 FROM drive.resource_grant g WHERE g.subject_id=person AND g.action=act AND g.effect='DENY' AND g.state='ACTIVE' AND
 ((g.resource_type='DOCUMENT' AND g.resource_id=target) OR (g.resource_type='FOLDER' AND g.resource_id IN (SELECT id FROM drive.active_path(doc.folder_id)))))
 AND (drive.manager_scope(person,doc.folder_id,act) OR (act<>'PURGE' AND (act NOT IN ('DELETE','MOVE','RESTORE') OR doc.uploaded_by=person)
 AND (act<>'EDIT' OR doc.collaboration_enabled) AND (drive.can_folder(person,doc.folder_id,act) OR EXISTS(
 SELECT 1 FROM drive.resource_grant g JOIN drive.delegation d ON d.id=g.delegation_id JOIN drive.active_path(doc.folder_id) p ON p.id=d.folder_id
 WHERE g.subject_id=person AND g.resource_type='DOCUMENT' AND g.resource_id=target AND g.action=act AND g.effect='ALLOW' AND g.state='ACTIVE'
 AND d.state='ACTIVE' AND act=ANY(d.allowed_actions) AND d.department_id=doc.publishing_department_id)))))));
$$;
CREATE OR REPLACE FUNCTION drive.visible_documents(person uuid,target_folder uuid) RETURNS SETOF drive.document LANGUAGE sql STABLE AS $$
 WITH folders AS MATERIALIZED (
  SELECT f.id,drive.can_folder(person,f.id,'VIEW') AS allowed FROM drive.folder f
  WHERE f.state='ACTIVE' AND (target_folder IS NULL OR f.id=target_folder)
 )
 SELECT d.* FROM drive.document d JOIN folders f ON f.id=d.folder_id WHERE d.status='ACTIVE'
 AND (iam.system_administrator(person) OR NOT EXISTS(SELECT 1 FROM drive.resource_grant g WHERE g.subject_id=person AND g.resource_type='DOCUMENT' AND g.resource_id=d.id AND g.action='VIEW' AND g.effect='DENY' AND g.state='ACTIVE'))
 AND CASE WHEN f.allowed THEN true
 WHEN EXISTS(SELECT 1 FROM drive.resource_grant g WHERE g.subject_id=person AND g.resource_type='DOCUMENT' AND g.resource_id=d.id AND g.action='VIEW' AND g.effect='ALLOW' AND g.state='ACTIVE')
 THEN drive.can_document(person,d.id,'VIEW') ELSE false END;
$$;
CREATE OR REPLACE FUNCTION archive.visibility(record_id uuid,person uuid,catalog_allowed boolean) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN catalog_allowed AND iam.system_administrator(person) THEN 'MANAGE' WHEN catalog_allowed AND EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.user_id=person AND u.status='ACTIVE' AND a.module='ARCHIVE'
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=r.department_id AND a.department_id=u.department_id)) AND a.state='ACTIVE' AND role.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) THEN 'MANAGE'
 WHEN r.state<>'PUBLISHED' OR b.state<>'PUBLISHED' THEN 'HIDDEN'
 WHEN iam.system_administrator(person) THEN 'METADATA'
 WHEN EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.user_id=person AND g.record_id=r.id AND g.state='ACTIVE' AND archive.grant_valid(g.id,person))
 OR EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=r.id AND p.state='ACTIVE' AND archive.baseline_valid(p.id,person)) THEN 'METADATA'
 WHEN r.safe_title IS NOT NULL AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN iam.user_account u ON u.id=person WHERE p.object_type='RECORD' AND p.object_id=r.id AND archive.policy_approved(p.id)
 AND p.record_revision_id=r.current_revision_id AND p.pdf_version_id IS NOT DISTINCT FROM r.current_pdf_version_id AND p.title_visibility IN ('SAFE_TITLE','OWNER_DEPARTMENT') AND (p.title_visibility<>'OWNER_DEPARTMENT' OR u.department_id=r.department_id)
 AND u.department_id=ANY(p.department_scope) AND 'safeTitle'=ANY(p.safe_search_fields)) THEN 'TITLE_ONLY' ELSE 'HIDDEN' END
 FROM archive.record r JOIN archive.box b ON b.id=r.box_id WHERE r.id=record_id;
$$;
CREATE OR REPLACE FUNCTION archive.action_allowed(record_id uuid,person uuid,verb text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT (verb='VIEW' AND iam.system_administrator(person) AND EXISTS(SELECT 1 FROM archive.record r JOIN archive.box b ON b.id=r.box_id WHERE r.id=record_id AND r.state='PUBLISHED' AND b.state='PUBLISHED' AND r.current_pdf_version_id IS NOT NULL)) OR EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.record_id=record_id AND g.user_id=person AND g.action=verb AND archive.grant_valid(g.id,person) AND (verb<>'PRINT' OR g.consumed_copies<g.approved_copies))
 OR (verb='VIEW' AND EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=record_id AND archive.baseline_valid(p.id,person)));
$$;

-- IT 阅读是独立且不可改写的来源，沿用本人会话、授权代次、固定版本、逐页水印和保护检查。
ALTER TABLE archive.usage_request ADD COLUMN system_admin_source boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN archive.usage_request.system_admin_source IS '是否使用全系统 IT 资格阅读；每次访问重新检查资格及授权代次';
DO $$ DECLARE constraint_name text; BEGIN
 SELECT conname INTO constraint_name FROM pg_constraint WHERE conrelid='archive.usage_request'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%grant_id%source_policy_id%review_context_id%';
 EXECUTE format('ALTER TABLE archive.usage_request DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE archive.usage_request ADD CONSTRAINT usage_exactly_one_source CHECK((grant_id IS NOT NULL)::integer+(source_policy_id IS NOT NULL)::integer+(review_context_id IS NOT NULL)::integer+system_admin_source::integer=1);
CREATE OR REPLACE FUNCTION archive.guard_usage_identity() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN IF ROW(NEW.id,NEW.user_id,NEW.session_id,NEW.auth_version,NEW.record_id,NEW.record_revision_id,NEW.pdf_version_id,NEW.object_id,NEW.grant_id,NEW.source_policy_id,NEW.review_context_id,NEW.review_context_type,NEW.source_version,NEW.action,NEW.expires_at,NEW.request_digest,NEW.system_admin_source) IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.session_id,OLD.auth_version,OLD.record_id,OLD.record_revision_id,OLD.pdf_version_id,OLD.object_id,OLD.grant_id,OLD.source_policy_id,OLD.review_context_id,OLD.review_context_type,OLD.source_version,OLD.action,OLD.expires_at,OLD.request_digest,OLD.system_admin_source) THEN RAISE EXCEPTION 'immutable archive usage binding'; END IF;RETURN NEW;END;$$;
