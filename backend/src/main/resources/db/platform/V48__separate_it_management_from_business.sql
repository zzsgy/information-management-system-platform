-- 从首次建库审计绑定通用账号，后续改工号也不改变身份。
ALTER TABLE iam.user_account ADD COLUMN it_shared_account boolean NOT NULL DEFAULT false;
UPDATE iam.user_account u SET it_shared_account=true
 WHERE u.id=(SELECT e.resource_id FROM ops.audit_event e
 WHERE e.module='PLATFORM' AND e.resource_type='USER' AND e.action='INITIALIZE'
 AND e.result='SUCCEEDED' AND e.actor_id=e.resource_id ORDER BY e.occurred_at LIMIT 1);
COMMENT ON COLUMN iam.user_account.it_shared_account IS '首次建库的 IT 通用账号，用于限定档案借阅特例';

CREATE FUNCTION iam.it_shared_borrower(person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM iam.user_account u WHERE u.id=person AND u.it_shared_account)
 AND iam.system_administrator(person);
$$;
COMMENT ON FUNCTION iam.it_shared_borrower(uuid) IS '通用账号必须仍有有效平台 IT 资格才可使用单节点借阅';

CREATE FUNCTION iam.it_management_action(action_code text) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
 SELECT action_code IN (
 'PLATFORM.USERS_MANAGE','PLATFORM.DEPARTMENTS_MANAGE','PLATFORM.ROLES_MANAGE',
 'PLATFORM.AUDIT_READ','PLATFORM.AUDIT_EXPORT','PLATFORM.SECURITY_MANAGE',
 'PLATFORM.OPERATIONS_MANAGE','PLATFORM.BACKUP_MANAGE','PLATFORM.RESTORE_VERIFY',
 'PLATFORM.EVIDENCE_RECORD','PLATFORM.SCAN_DEVICES_MANAGE','PLATFORM.SITES_MANAGE','PLATFORM.FEEDBACK_MANAGE',
 'DRIVE.ACCESS','DRIVE.ROLES_MANAGE','DRIVE.AUDIT_READ','DRIVE.AUDIT_EXPORT',
 'ARCHIVE.ROLES_MANAGE','ARCHIVE.AUDIT_READ','ARCHIVE.AUDIT_EXPORT');
$$;
COMMENT ON FUNCTION iam.it_management_action(text) IS '平台 IT 管理权限清单。业务权限由独立的业务角色或借阅批准产生';

CREATE OR REPLACE FUNCTION iam.action_enabled(person uuid,action_code text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT (iam.it_management_action(action_code) AND iam.system_administrator(person))
 OR (action_code IN ('ARCHIVE.ACCESS','ARCHIVE.PHYSICAL_BORROW') AND iam.it_shared_borrower(person))
 OR (SELECT COALESCE(bool_or(rp.effect='ALLOW'),false) AND NOT COALESCE(bool_or(rp.effect='DENY'),false)
 AND NOT EXISTS(SELECT 1 FROM iam.permission_assignment pa JOIN iam.permission p ON p.id=pa.permission_id WHERE pa.user_id=person AND p.code=action_code AND pa.state='ACTIVE' AND pa.effect='DENY' AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP))
 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.role_permission rp ON rp.role_id=r.id JOIN iam.permission p ON p.id=rp.permission_id AND p.module=r.module JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND u.status='ACTIVE' AND NOT u.password_setup_required AND a.state='ACTIVE' AND r.status='ACTIVE' AND (r.module,r.code)<>('PLATFORM','IT_ADMIN') AND rp.scope_policy='COMPANY' AND p.code=action_code AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP));
$$;

CREATE OR REPLACE FUNCTION iam.archive_access_enabled(person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT iam.action_enabled(person,'ARCHIVE.ACCESS');
$$;

CREATE OR REPLACE FUNCTION archive.catalog_manager(person uuid,department uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT (SELECT iam.archive_access_enabled(person) AND iam.action_enabled(person,'ARCHIVE.CATALOG_MANAGE') AND EXISTS(
 SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND r.status='ACTIVE' AND u.status='ACTIVE'
 AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=department AND u.department_id=department))));
$$;

CREATE OR REPLACE FUNCTION drive.manager_scope(person uuid,target uuid,act text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM drive.active_path(target) p JOIN drive.delegation d ON d.folder_id=p.id
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
 AND ((act IN ('VIEW','DOWNLOAD') AND iam.system_administrator(person)) OR (NOT EXISTS(SELECT 1 FROM rules WHERE effect='DENY') AND (
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
 AND ((act IN ('VIEW','DOWNLOAD') AND iam.system_administrator(person)) OR (NOT EXISTS(SELECT 1 FROM drive.resource_grant g WHERE g.subject_id=person AND g.action=act AND g.effect='DENY' AND g.state='ACTIVE' AND
 ((g.resource_type='DOCUMENT' AND g.resource_id=target) OR (g.resource_type='FOLDER' AND g.resource_id IN (SELECT id FROM drive.active_path(doc.folder_id)))))
 AND (drive.manager_scope(person,doc.folder_id,act) OR (act<>'PURGE' AND (act NOT IN ('DELETE','MOVE','RESTORE') OR doc.uploaded_by=person)
 AND (act<>'EDIT' OR doc.collaboration_enabled) AND (drive.can_folder(person,doc.folder_id,act) OR EXISTS(
 SELECT 1 FROM drive.resource_grant g JOIN drive.delegation d ON d.id=g.delegation_id JOIN drive.active_path(doc.folder_id) p ON p.id=d.folder_id
 WHERE g.subject_id=person AND g.resource_type='DOCUMENT' AND g.resource_id=target AND g.action=act AND g.effect='ALLOW' AND g.state='ACTIVE'
 AND d.state='ACTIVE' AND act=ANY(d.allowed_actions) AND d.department_id=doc.publishing_department_id)))))));
$$;

CREATE OR REPLACE FUNCTION archive.visibility(record_id uuid,person uuid,catalog_allowed boolean) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN catalog_allowed AND EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.user_id=person AND u.status='ACTIVE' AND a.module='ARCHIVE'
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=r.department_id AND a.department_id=u.department_id)) AND a.state='ACTIVE' AND role.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) THEN 'MANAGE'
 WHEN r.state<>'PUBLISHED' OR b.state<>'PUBLISHED' THEN 'HIDDEN'
 WHEN EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.user_id=person AND g.record_id=r.id AND g.state='ACTIVE' AND archive.grant_valid(g.id,person))
 OR EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=r.id AND p.state='ACTIVE' AND archive.baseline_valid(p.id,person)) THEN 'METADATA'
 WHEN iam.it_shared_borrower(person) THEN 'TITLE_ONLY'
 WHEN r.safe_title IS NOT NULL AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN iam.user_account u ON u.id=person WHERE p.object_type='RECORD' AND p.object_id=r.id AND archive.policy_approved(p.id)
 AND p.record_revision_id=r.current_revision_id AND p.pdf_version_id IS NOT DISTINCT FROM r.current_pdf_version_id AND p.title_visibility IN ('SAFE_TITLE','OWNER_DEPARTMENT') AND (p.title_visibility<>'OWNER_DEPARTMENT' OR u.department_id=r.department_id)
 AND u.department_id=ANY(p.department_scope) AND 'safeTitle'=ANY(p.safe_search_fields)) THEN 'TITLE_ONLY' ELSE 'HIDDEN' END
 FROM archive.record r JOIN archive.box b ON b.id=r.box_id WHERE r.id=record_id;
$$;

CREATE OR REPLACE FUNCTION archive.action_allowed(record_id uuid,person uuid,verb text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.record_id=record_id AND g.user_id=person AND g.action=verb AND archive.grant_valid(g.id,person) AND (verb<>'PRINT' OR g.consumed_copies<g.approved_copies))
 OR (verb='VIEW' AND EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=record_id AND archive.baseline_valid(p.id,person)));
$$;

CREATE OR REPLACE FUNCTION workflow.assignment_valid(kind text,reference uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT iam.archive_access_enabled(person) AND NOT iam.it_shared_borrower(person) AND CASE kind
 WHEN 'ROLE' THEN EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.id=reference AND a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND r.status='ACTIVE' AND (a.role_code<>'DEPARTMENT_ARCHIVIST' OR a.department_id=u.department_id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'DEPARTMENT' THEN EXISTS(SELECT 1 FROM iam.department_manager a JOIN iam.department d ON d.id=a.department_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.id=reference AND a.user_id=person AND u.department_id=a.department_id AND a.duty_type='DEPARTMENT_MANAGER' AND d.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'SUPERVISING' THEN EXISTS(SELECT 1 FROM iam.department_manager a JOIN iam.department d ON d.id=a.department_id WHERE a.id=reference AND a.user_id=person AND a.duty_type='SUPERVISING_LEADER' AND d.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'COMPANY' THEN EXISTS(SELECT 1 FROM iam.company_duty a WHERE a.id=reference AND a.user_id=person AND a.duty_code='GENERAL_MANAGER' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) ELSE false END;
$$;
