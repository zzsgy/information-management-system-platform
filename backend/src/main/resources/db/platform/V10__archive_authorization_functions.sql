-- 所有入口复用批准来源有效性，授权行ACTIVE不代表可绕过驳回、撤回、到期或版本变更。
CREATE FUNCTION archive.baseline_valid(policy_id uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_policy p JOIN archive.record r ON r.id=p.object_id JOIN archive.box b ON b.id=r.box_id
 JOIN archive.pdf_version v ON v.id=r.current_pdf_version_id AND v.record_id=r.id JOIN storage.object o ON o.id=v.object_id
 JOIN iam.user_account u ON u.id=person JOIN workflow.instance w ON w.id=p.workflow_instance_id
 WHERE p.id=policy_id AND p.object_type='RECORD' AND p.state='ACTIVE' AND p.allow_owner_department_view AND p.valid_from<=CURRENT_TIMESTAMP AND (p.valid_until IS NULL OR p.valid_until>CURRENT_TIMESTAMP)
 AND u.status='ACTIVE' AND u.department_id=r.department_id AND r.classification='GENERAL' AND r.state='PUBLISHED' AND b.state='PUBLISHED'
 AND p.record_revision_id=r.current_revision_id AND p.pdf_version_id=r.current_pdf_version_id AND v.review_state='APPROVED' AND o.state='READY' AND o.module='ARCHIVE'
 AND w.state='APPROVED' AND NOT EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id AND s.state<>'APPROVED'));
$$;
CREATE FUNCTION archive.grant_valid(grant_id uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_grant g JOIN archive.record r ON r.id=g.record_id JOIN archive.box b ON b.id=r.box_id
 JOIN archive.pdf_version v ON v.id=g.pdf_version_id AND v.record_id=r.id JOIN storage.object o ON o.id=v.object_id JOIN iam.user_account u ON u.id=person
 WHERE g.id=grant_id AND g.user_id=person AND u.status='ACTIVE' AND g.action='VIEW' AND g.state='ACTIVE' AND g.valid_from<=CURRENT_TIMESTAMP AND g.valid_until>CURRENT_TIMESTAMP
 AND r.state='PUBLISHED' AND b.state='PUBLISHED' AND r.current_revision_id=g.record_revision_id AND r.current_pdf_version_id=g.pdf_version_id
 AND v.review_state='APPROVED' AND o.state='READY' AND o.module='ARCHIVE'
 AND ((g.source_type='BORROW' AND EXISTS(SELECT 1 FROM archive.borrow_request q JOIN archive.borrow_batch batch ON batch.id=q.batch_id JOIN workflow.instance w ON w.id=q.workflow_instance_id
 JOIN archive.request_item i ON i.request_id=q.id WHERE q.id=g.source_request_id AND q.applicant_id=g.user_id AND batch.applicant_department_id=u.department_id AND batch.form='ELECTRONIC'
 AND q.state='APPROVED' AND w.state='APPROVED' AND q.approved_until>=g.valid_until AND i.record_id=g.record_id AND i.record_revision_id=g.record_revision_id AND i.pdf_version_id=g.pdf_version_id AND i.action=g.action AND i.classification_snapshot=r.classification
 AND NOT EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id AND s.state<>'APPROVED')))
 OR (g.source_type='BASELINE' AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN workflow.instance w ON w.id=p.workflow_instance_id
 WHERE p.id=g.source_policy_id AND p.object_type='RECORD' AND p.object_id=r.id AND p.record_revision_id=g.record_revision_id AND p.pdf_version_id=g.pdf_version_id AND p.policy_version=g.policy_version
 AND p.state='ACTIVE' AND p.valid_from<=CURRENT_TIMESTAMP AND (p.valid_until IS NULL OR p.valid_until>CURRENT_TIMESTAMP) AND w.state='APPROVED'
 AND NOT EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id AND s.state<>'APPROVED')))));
$$;
-- 列表必须在匹配秘密字段、计数和分页之前确定表示；TITLE_ONLY仅可匹配批准安全题名。
CREATE FUNCTION archive.visibility(record_id uuid,person uuid,catalog_allowed boolean) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN catalog_allowed AND EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id WHERE a.user_id=person AND a.module='ARCHIVE'
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=r.department_id)) AND a.state='ACTIVE' AND role.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) THEN 'MANAGE'
 WHEN r.state<>'PUBLISHED' OR b.state<>'PUBLISHED' THEN 'HIDDEN'
 WHEN EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.user_id=person AND g.record_id=r.id AND g.state='ACTIVE' AND archive.grant_valid(g.id,person))
 OR EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=r.id AND p.state='ACTIVE' AND archive.baseline_valid(p.id,person)) THEN 'METADATA'
 WHEN r.safe_title IS NOT NULL AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN iam.user_account u ON u.id=person JOIN workflow.instance w ON w.id=p.workflow_instance_id
 WHERE p.object_type='RECORD' AND p.object_id=r.id AND p.state='ACTIVE' AND p.title_visibility='TITLE_ONLY' AND u.department_id=ANY(p.department_scope) AND 'safeTitle'=ANY(p.safe_search_fields)
 AND p.valid_from<=CURRENT_TIMESTAMP AND (p.valid_until IS NULL OR p.valid_until>CURRENT_TIMESTAMP) AND w.state='APPROVED' AND NOT EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id AND s.state<>'APPROVED')) THEN 'TITLE_ONLY' ELSE 'HIDDEN' END
 FROM archive.record r JOIN archive.box b ON b.id=r.box_id WHERE r.id=record_id;
$$;
COMMENT ON FUNCTION archive.baseline_valid(uuid,uuid) IS '一般本部门固定版本基础阅读的实时完整批准检查';
COMMENT ON FUNCTION archive.grant_valid(uuid,uuid) IS '具体人员授权及原借阅或基础批准的实时检查';
COMMENT ON FUNCTION archive.visibility(uuid,uuid,boolean) IS '先限定字段表示，再进行检索计数和分页';
