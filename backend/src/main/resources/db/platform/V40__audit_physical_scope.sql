-- 纸质借还、授权策略和审批证据沿用档案所属部门，不能回退到操作人的部门。
CREATE OR REPLACE FUNCTION ops.audit_departments(target uuid) RETURNS uuid[] LANGUAGE sql STABLE AS $$
 SELECT COALESCE(array_agg(DISTINCT department_id),'{}'::uuid[]) FROM (
 SELECT department_id FROM archive.box WHERE id=target
 UNION SELECT department_id FROM archive.record WHERE id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.record_revision v ON v.record_id=r.id WHERE v.id=target
 UNION SELECT b.department_id FROM archive.box b JOIN archive.box_revision v ON v.box_id=b.id WHERE v.id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.access_grant g ON g.record_id=r.id WHERE g.id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.usage_request u ON u.record_id=r.id WHERE u.id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.export_item i ON i.record_id=r.id WHERE i.usage_id=target
 UNION SELECT owner_department_id FROM archive.borrow_request WHERE id=target OR batch_id=target OR workflow_instance_id=target
 UNION SELECT q.owner_department_id FROM archive.borrow_request q JOIN workflow.step s ON s.instance_id=q.workflow_instance_id WHERE s.id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.record_revision v ON v.record_id=r.id LEFT JOIN workflow.step s ON s.instance_id=v.review_instance_id WHERE v.review_instance_id=target OR s.id=target
 UNION SELECT b.department_id FROM archive.box b JOIN archive.box_revision v ON v.box_id=b.id LEFT JOIN workflow.step s ON s.instance_id=v.review_instance_id WHERE v.review_instance_id=target OR s.id=target
 UNION SELECT b.department_id FROM archive.physical_item p JOIN archive.box b ON b.id=p.box_id WHERE p.id=target
 UNION SELECT b.department_id FROM archive.physical_loan l JOIN archive.box b ON b.id=l.box_id WHERE l.id=target
 UNION SELECT q.owner_department_id FROM archive.physical_delivery d JOIN archive.borrow_request q ON q.id=d.request_id WHERE d.id=target
 UNION SELECT b.department_id FROM archive.physical_return p JOIN archive.physical_loan l ON l.id=p.loan_id JOIN archive.box b ON b.id=l.box_id WHERE p.id=target
 UNION SELECT b.department_id FROM archive.access_policy p JOIN archive.box b ON b.id=p.object_id WHERE p.id=target AND p.object_type='BOX'
 UNION SELECT r.department_id FROM archive.access_policy p JOIN archive.record r ON r.id=p.object_id WHERE p.id=target AND p.object_type='RECORD'
 UNION SELECT r.department_id FROM archive.pdf_version p JOIN archive.record r ON r.id=p.record_id WHERE p.id=target
 UNION SELECT q.owner_department_id FROM workflow.evidence_submission e LEFT JOIN workflow.evidence file ON file.submission_id=e.id JOIN workflow.step s ON s.id=e.step_id JOIN archive.borrow_request q ON q.workflow_instance_id=s.instance_id WHERE e.id=target OR file.id=target
 ) departments;
$$;
