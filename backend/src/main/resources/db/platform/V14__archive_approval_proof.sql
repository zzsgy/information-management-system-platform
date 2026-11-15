-- 正文交付不仅信任状态字段，还验证同业务来源、原路线和不可覆盖的逐节点批准决定。
CREATE FUNCTION workflow.approval_valid(flow uuid,kind text,business uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM workflow.instance w JOIN workflow.route_snapshot route ON route.id=w.route_snapshot_id AND route.instance_id=w.id
 WHERE w.id=flow AND w.business_type=kind AND w.business_id=business AND w.state='APPROVED' AND w.current_step_id IS NULL
 AND jsonb_array_length(route.nodes_snapshot)>0 AND jsonb_array_length(route.nodes_snapshot)=(SELECT count(*) FROM workflow.step s WHERE s.instance_id=w.id)
 AND NOT EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id AND (s.state<>'APPROVED' OR s.duty_code<>route.nodes_snapshot->(s.sequence_no-1)->>'duty'
 OR NOT EXISTS(SELECT 1 FROM workflow.decision d WHERE d.step_id=s.id AND d.result='APPROVE' AND d.original_approver_id=d.operator_id AND d.operator_id<>w.applicant_id))));
$$;
CREATE FUNCTION archive.policy_approved(policy uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_policy p JOIN workflow.instance w ON w.id=p.workflow_instance_id
 WHERE p.id=policy AND p.object_type='RECORD' AND p.state='ACTIVE' AND p.valid_from<=CURRENT_TIMESTAMP AND (p.valid_until IS NULL OR p.valid_until>CURRENT_TIMESTAMP)
 AND workflow.approval_valid(w.id,'ACCESS_POLICY',p.object_id)
 AND w.business_snapshot->>'objectId'=p.object_id::text AND w.business_snapshot->>'recordRevisionId'=p.record_revision_id::text AND w.business_snapshot->>'pdfVersionId'=p.pdf_version_id::text
 AND (w.business_snapshot->>'basePolicyVersion')::integer+1=p.policy_version AND (w.business_snapshot->>'allowOwnerDepartmentView')::boolean=p.allow_owner_department_view
 AND w.business_snapshot->>'titleVisibility'=p.title_visibility AND w.business_snapshot->'safeSearchFields'=to_jsonb(p.safe_search_fields) AND w.business_snapshot->'departmentScope'=to_jsonb(p.department_scope)
 AND (w.business_snapshot->>'validFrom')::timestamptz=p.valid_from AND (w.business_snapshot->>'validUntil')::timestamptz IS NOT DISTINCT FROM p.valid_until);
$$;
CREATE OR REPLACE FUNCTION archive.baseline_valid(policy_id uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_policy p JOIN archive.record r ON r.id=p.object_id JOIN archive.box b ON b.id=r.box_id
 JOIN archive.pdf_version v ON v.id=r.current_pdf_version_id AND v.record_id=r.id JOIN storage.object o ON o.id=v.object_id JOIN iam.user_account u ON u.id=person
 WHERE p.id=policy_id AND archive.policy_approved(p.id) AND p.allow_owner_department_view AND u.status='ACTIVE' AND u.department_id=r.department_id
 AND r.classification='GENERAL' AND r.state='PUBLISHED' AND b.state='PUBLISHED' AND p.record_revision_id=r.current_revision_id AND p.pdf_version_id=r.current_pdf_version_id
 AND v.review_state='APPROVED' AND o.state='READY' AND o.module='ARCHIVE');
$$;
CREATE OR REPLACE FUNCTION archive.grant_valid(grant_id uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_grant g JOIN archive.record r ON r.id=g.record_id JOIN archive.box b ON b.id=r.box_id
 JOIN archive.pdf_version v ON v.id=g.pdf_version_id AND v.record_id=r.id JOIN storage.object o ON o.id=v.object_id JOIN iam.user_account u ON u.id=person
 WHERE g.id=grant_id AND g.user_id=person AND u.status='ACTIVE' AND g.action='VIEW' AND g.state='ACTIVE' AND g.valid_from<=CURRENT_TIMESTAMP AND g.valid_until>CURRENT_TIMESTAMP
 AND r.state='PUBLISHED' AND b.state='PUBLISHED' AND r.current_revision_id=g.record_revision_id AND r.current_pdf_version_id=g.pdf_version_id AND v.review_state='APPROVED' AND o.state='READY' AND o.module='ARCHIVE'
 AND ((g.source_type='BORROW' AND EXISTS(SELECT 1 FROM archive.borrow_request q JOIN archive.borrow_batch batch ON batch.id=q.batch_id JOIN workflow.instance w ON w.id=q.workflow_instance_id
 JOIN workflow.route_snapshot route ON route.id=w.route_snapshot_id JOIN archive.request_item i ON i.request_id=q.id AND i.batch_id=q.batch_id
 WHERE q.id=g.source_request_id AND q.applicant_id=g.user_id AND batch.applicant_department_id=u.department_id AND batch.form='ELECTRONIC' AND q.state='APPROVED'
 AND workflow.approval_valid(w.id,'BORROW',q.id) AND route.highest_classification=batch.parent_max_classification AND q.approved_until>=g.valid_until AND 'VIEW'=ANY(q.approved_actions)
 AND i.record_id=g.record_id AND i.record_revision_id=g.record_revision_id AND i.pdf_version_id=g.pdf_version_id AND i.action=g.action AND i.classification_snapshot=r.classification))
 OR (g.source_type='BASELINE' AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN workflow.instance w ON w.id=p.workflow_instance_id
 WHERE p.id=g.source_policy_id AND archive.policy_approved(p.id) AND p.object_id=r.id AND p.record_revision_id=g.record_revision_id AND p.pdf_version_id=g.pdf_version_id AND p.policy_version=g.policy_version
 AND w.business_snapshot->'userIds' @> to_jsonb(ARRAY[person::text]) AND g.valid_from=p.valid_from AND g.valid_until=p.valid_until))));
$$;
CREATE OR REPLACE FUNCTION archive.visibility(record_id uuid,person uuid,catalog_allowed boolean) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN catalog_allowed AND EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.user_id=person AND u.status='ACTIVE' AND a.module='ARCHIVE'
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=r.department_id AND a.department_id=u.department_id)) AND a.state='ACTIVE' AND role.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) THEN 'MANAGE'
 WHEN r.state<>'PUBLISHED' OR b.state<>'PUBLISHED' THEN 'HIDDEN'
 WHEN EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.user_id=person AND g.record_id=r.id AND g.state='ACTIVE' AND archive.grant_valid(g.id,person))
 OR EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=r.id AND p.state='ACTIVE' AND archive.baseline_valid(p.id,person)) THEN 'METADATA'
 WHEN r.safe_title IS NOT NULL AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN iam.user_account u ON u.id=person WHERE p.object_type='RECORD' AND p.object_id=r.id AND archive.policy_approved(p.id)
 AND p.record_revision_id=r.current_revision_id AND p.pdf_version_id=r.current_pdf_version_id AND p.title_visibility IN ('SAFE_TITLE','OWNER_DEPARTMENT') AND (p.title_visibility<>'OWNER_DEPARTMENT' OR u.department_id=r.department_id)
 AND u.department_id=ANY(p.department_scope) AND 'safeTitle'=ANY(p.safe_search_fields)) THEN 'TITLE_ONLY' ELSE 'HIDDEN' END
 FROM archive.record r JOIN archive.box b ON b.id=r.box_id WHERE r.id=record_id;
$$;
CREATE OR REPLACE FUNCTION workflow.assignment_valid(kind text,reference uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM iam.user_account u WHERE u.id=person AND u.status='ACTIVE') AND CASE kind
 WHEN 'ROLE' THEN EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.id=reference AND a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND r.status='ACTIVE' AND (a.role_code<>'DEPARTMENT_ARCHIVIST' OR a.department_id=u.department_id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'DEPARTMENT' THEN EXISTS(SELECT 1 FROM iam.department_manager a JOIN iam.department d ON d.id=a.department_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.id=reference AND a.user_id=person AND u.department_id=a.department_id AND a.duty_type='DEPARTMENT_MANAGER' AND d.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'COMPANY' THEN EXISTS(SELECT 1 FROM iam.company_duty a WHERE a.id=reference AND a.user_id=person AND a.duty_code='GENERAL_MANAGER' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) ELSE false END;
$$;
COMMENT ON FUNCTION workflow.approval_valid(uuid,text,uuid) IS '同一业务来源与原固定路线逐节点真实批准同时成立，单独状态字段不能充当批准';
COMMENT ON FUNCTION archive.policy_approved(uuid) IS '基础策略全部字段须与真实业务审批快照逐项一致';
-- 新增表均标明业务用途；字段说明已在结构迁移逐项给出。
COMMENT ON TABLE archive.box IS '两层档案目录中的盒，编号永久唯一且不产生正文继承授权';
COMMENT ON TABLE archive.record IS '独立稳定文件号、所属盒及当前批准版本指针';
COMMENT ON TABLE archive.pdf_version IS '不可变完整PDF对象版本，技术检查与独立复核状态分开';
COMMENT ON TABLE archive.box_revision IS '不可覆盖的盒目录与原成员复核快照';
COMMENT ON TABLE archive.record_revision IS '不可覆盖的文件目录与固定PDF版本快照';
COMMENT ON TABLE archive.directory_template IS '按盒件和六类业务区分的固定字段目录模板版本';
COMMENT ON TABLE archive.number_rule IS '盒号分区与公司文件号流水的原子分配水位';
COMMENT ON TABLE archive.borrow_batch IS '冻结原清单最高等级和日历版本的电子借阅父批次';
COMMENT ON TABLE archive.borrow_request IS '按所属部门拆分且继承父批次最高等级的批准子单';
COMMENT ON TABLE archive.request_item IS '电子借阅中固定文件、目录修订、PDF和动作的不可扩大清单';
COMMENT ON TABLE archive.access_policy IS '已经真实流程批准的安全检索和本部门基础阅读策略';
COMMENT ON TABLE archive.access_grant IS '指定员工具体版本和动作的基础或借阅来源授权';
COMMENT ON TABLE workflow.instance IS '固定业务快照及当前必要节点的流程状态';
COMMENT ON TABLE workflow.route_snapshot IS '送审时不可覆盖的原最高等级和完整必要职责路线';
COMMENT ON TABLE workflow.step IS '必要职责和当前合法人员分离的有序待办节点';
COMMENT ON TABLE workflow.decision IS '每个节点唯一且不可覆盖的本人真实在线决定';
COMMENT ON TABLE workflow.assignment_event IS '职责更替或本人回避时不跳节点的合法改派历史';
COMMENT ON TABLE workflow.template_version IS '随代码交付的固定流程模板，不支持任意客户端编排';
COMMENT ON TABLE iam.company_duty IS '独立于模块角色的公司总经理组织审批职责历史';
COMMENT ON TABLE iam.work_calendar IS '借阅计算固定引用的公司工作日与调休日历版本';
