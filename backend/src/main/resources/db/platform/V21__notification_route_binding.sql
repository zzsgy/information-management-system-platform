-- 补批保留原流程，但结果通知绑定新的不可变路线；旧结果不随同名APPROVED状态重新生效。
CREATE OR REPLACE FUNCTION ops.notification_current(notice uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM ops.business_notification n WHERE n.id=notice AND n.user_id=person AND n.state<>'CANCELLED' AND iam.archive_access_enabled(person) AND CASE n.source_kind
 WHEN 'WORKFLOW_STEP' THEN EXISTS(SELECT 1 FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE s.id=n.source_id AND s.assignee_id=person AND s.assignment_ref::text=n.source_version AND s.state='PENDING' AND w.state='PENDING' AND s.assignee_id<>w.applicant_id AND workflow.assignment_valid(s.assignment_type,s.assignment_ref,person))
 WHEN 'WORKFLOW_INSTANCE' THEN EXISTS(SELECT 1 FROM workflow.instance w WHERE w.id=n.source_id AND w.applicant_id=person AND w.state=n.source_state AND w.route_snapshot_id::text=n.source_version)
 WHEN 'ELECTRONIC_REMINDER' THEN EXISTS(SELECT 1 FROM ops.inbox_notification old WHERE old.id=n.source_id AND old.user_id=person AND old.state<>'CANCELLED' AND archive.reminder_current(old.request_id,person,old.deadline))
 WHEN 'PHYSICAL_NOTICE' THEN EXISTS(SELECT 1 FROM archive.physical_notice old JOIN archive.physical_loan l ON l.id=old.loan_id JOIN archive.box b ON b.id=l.box_id JOIN iam.user_account u ON u.id=person WHERE old.id=n.source_id AND old.user_id=person AND old.state<>'CANCELLED' AND (old.kind IN ('RETURN_CONFIRMED','RETURN_EXCEPTION') OR (l.state='IN_USE' AND old.deadline=l.due_at)) AND (l.borrower_id=person OR (iam.action_enabled(person,'ARCHIVE.PHYSICAL_MANAGE') AND EXISTS(SELECT 1 FROM iam.user_role a WHERE a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP) AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=b.department_id AND u.department_id=b.department_id)))))) ELSE false END);
$$;
CREATE OR REPLACE FUNCTION ops.workflow_notification() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE step workflow.step; BEGIN
 IF NEW.state='PENDING' AND NEW.current_step_id IS NOT NULL THEN
  SELECT * INTO step FROM workflow.step WHERE id=NEW.current_step_id;
  PERFORM ops.publish_notification('STEP:'||step.id||':'||step.assignee_id||':'||step.assignment_ref,step.assignee_id,'WORKFLOW_STEP',step.id,step.assignment_ref::text,'PENDING','TASK_AVAILABLE');
 ELSIF NEW.state IN ('APPROVED','REJECTED','WITHDRAWN','CLOSED') THEN
  PERFORM ops.publish_notification('RESULT:'||NEW.id||':'||NEW.state||':'||NEW.route_snapshot_id,NEW.applicant_id,'WORKFLOW_INSTANCE',NEW.id,NEW.route_snapshot_id::text,NEW.state,'WORKFLOW_'||NEW.state);
 END IF;RETURN NEW;
END $$;
COMMENT ON COLUMN ops.business_notification.source_version IS '待办绑定原审批任职记录；结果绑定原不可变路线，补批不会复活或吞并旧通知';
-- V20未保存路线的结果只保留历史，不把当前路线倒填成旧结果的原依据。
