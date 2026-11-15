-- 续借另建批准批次和具体授权，不修改原批准期限，也不让待审申请延长正文权限。
ALTER TABLE archive.borrow_batch ADD COLUMN renewal_of_batch_id uuid REFERENCES archive.borrow_batch(id),
 ADD COLUMN renewal_reason varchar(1000),ADD COLUMN row_version integer NOT NULL DEFAULT 1;
ALTER TABLE archive.request_item ADD COLUMN renewed_grant_id uuid REFERENCES archive.access_grant(id);
ALTER TABLE archive.access_grant ADD COLUMN parent_grant_id uuid REFERENCES archive.access_grant(id);
-- 同一原批次只能存在一个待审或成功续借；驳回、撤回后可重新申请，成功后须续借最新批次。
CREATE UNIQUE INDEX ux_borrow_live_renewal ON archive.borrow_batch(renewal_of_batch_id)
 WHERE renewal_of_batch_id IS NOT NULL AND state IN ('PENDING','PARTIAL','APPROVED');
CREATE INDEX ix_borrow_renewal_history ON archive.borrow_batch(renewal_of_batch_id,submitted_at DESC);
CREATE INDEX ix_grant_expiration ON archive.access_grant(valid_until,id) WHERE state='ACTIVE';
CREATE INDEX ix_grant_renewal_parent ON archive.access_grant(parent_grant_id) WHERE parent_grant_id IS NOT NULL;
-- 批准依据存入对应任职与日历版本，不只留在HTTP输入里。
ALTER TABLE iam.department_manager ADD COLUMN approval_reference varchar(256);
ALTER TABLE iam.company_duty ADD COLUMN approval_reference varchar(256);
ALTER TABLE iam.work_calendar ADD COLUMN approval_reference varchar(256);

CREATE TABLE ops.inbox_notification (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),
 request_id uuid NOT NULL REFERENCES archive.borrow_request(id),batch_id uuid NOT NULL REFERENCES archive.borrow_batch(id),
 deadline timestamptz NOT NULL,reminder_day date NOT NULL,days_before integer NOT NULL CHECK(days_before IN (0,1)),
 state varchar(16) NOT NULL DEFAULT 'UNREAD' CHECK(state IN ('UNREAD','READ','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,read_at timestamptz,
 UNIQUE(user_id,request_id,deadline,days_before)
);
CREATE INDEX ix_inbox_person ON ops.inbox_notification(user_id,created_at DESC,id);
COMMENT ON TABLE ops.inbox_notification IS '电子借阅站内到期提醒；业务事务独立于用户阅读，重跑按来源期限去重';
COMMENT ON COLUMN archive.borrow_batch.renewal_of_batch_id IS '本次续借关联的原批准批次，空值表示首次申请';
COMMENT ON COLUMN archive.borrow_batch.renewal_reason IS '续借实际业务原因，不包含秘密正文';
COMMENT ON COLUMN archive.borrow_batch.row_version IS '批次进度变化时递增的并发版本';
COMMENT ON COLUMN archive.request_item.renewed_grant_id IS '续借送审冻结的原借阅授权，不能从其他批准来源替换';
COMMENT ON COLUMN archive.access_grant.parent_grant_id IS '已批准续借的原来源授权，仅保留关联历史，不回写原截止时间';
COMMENT ON COLUMN iam.department_manager.approval_reference IS '组织职责变更的批准依据';
COMMENT ON COLUMN iam.company_duty.approval_reference IS '公司职责变更的批准依据';
COMMENT ON COLUMN iam.work_calendar.approval_reference IS '该日历版本的公司批准依据';
COMMENT ON COLUMN ops.inbox_notification.id IS '稳定提醒标识';
COMMENT ON COLUMN ops.inbox_notification.user_id IS '仅本人可读的提醒接收员工';
COMMENT ON COLUMN ops.inbox_notification.request_id IS '原所属部门借阅子单';
COMMENT ON COLUMN ops.inbox_notification.batch_id IS '跳转时仍需鉴权的借阅批次';
COMMENT ON COLUMN ops.inbox_notification.deadline IS '提醒冻结截止时刻，发送和查看时重验';
COMMENT ON COLUMN ops.inbox_notification.reminder_day IS '上海时区的预定提醒日';
COMMENT ON COLUMN ops.inbox_notification.days_before IS '到期前一天或当天，单位为自然日';
COMMENT ON COLUMN ops.inbox_notification.state IS '未读、已读或因授权期限变化而取消';
COMMENT ON COLUMN ops.inbox_notification.created_at IS '站内投递实际提交时刻';
COMMENT ON COLUMN ops.inbox_notification.read_at IS '本人首次确认已读时刻';

-- 原借阅审批证据仍须真实有效；允许时间自然到期后的在途续借继续审批，但禁止撤权、换版后续权。
CREATE FUNCTION archive.borrow_proof_valid(grant_id uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_grant g JOIN archive.record r ON r.id=g.record_id JOIN archive.box b ON b.id=r.box_id
 JOIN archive.borrow_request q ON q.id=g.source_request_id JOIN archive.borrow_batch batch ON batch.id=q.batch_id
 JOIN workflow.instance w ON w.id=q.workflow_instance_id JOIN workflow.route_snapshot route ON route.id=w.route_snapshot_id
 JOIN archive.request_item i ON i.request_id=q.id AND i.record_id=g.record_id AND i.record_revision_id=g.record_revision_id AND i.pdf_version_id=g.pdf_version_id AND i.action=g.action
 JOIN archive.pdf_version v ON v.id=g.pdf_version_id AND v.record_id=r.id JOIN storage.object o ON o.id=v.object_id JOIN iam.user_account u ON u.id=person
 WHERE g.id=grant_id AND g.user_id=person AND g.source_type='BORROW' AND g.action='VIEW' AND g.state IN ('ACTIVE','EXPIRED')
 AND u.status='ACTIVE' AND batch.applicant_department_id=u.department_id AND q.applicant_id=person AND q.state='APPROVED'
 AND workflow.approval_valid(w.id,'BORROW',q.id) AND route.highest_classification=batch.parent_max_classification
 AND q.approved_until>=g.valid_until AND r.state='PUBLISHED' AND b.state='PUBLISHED' AND v.review_state='APPROVED' AND o.state='READY' AND o.module='ARCHIVE'
 AND r.current_revision_id=g.record_revision_id AND r.current_pdf_version_id=g.pdf_version_id AND i.classification_snapshot=r.classification);
$$;
COMMENT ON FUNCTION archive.borrow_proof_valid(uuid,uuid) IS '续借核验原借阅真实批准和固定版本，不把自然到期误当作永久撤权';

-- 站内提醒始终只指向当前仍有效且未被有效续借替代的具体来源，不凭旧任务延长授权。
CREATE FUNCTION archive.reminder_current(request uuid,person uuid,deadline timestamptz) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.source_request_id=request AND g.user_id=person AND g.valid_until=deadline
 AND archive.grant_valid(g.id,person) AND NOT EXISTS(SELECT 1 FROM archive.access_grant n WHERE n.parent_grant_id=g.id AND archive.grant_valid(n.id,person)));
$$;
COMMENT ON FUNCTION archive.reminder_current(uuid,uuid,timestamptz) IS '站内投递与查看共用当前期限和批准核验，续借或撤权后取消旧提醒';

-- 组织职责不替代模块入口权限；档案角色撤销后旧任职节点进入合法改派队列。
CREATE FUNCTION iam.archive_access_enabled(person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT COALESCE(bool_or(p.code='ARCHIVE.ACCESS' AND rp.effect='ALLOW'),false) AND NOT COALESCE(bool_or(p.code='ARCHIVE.ACCESS' AND rp.effect='DENY'),false)
 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id JOIN iam.role_permission rp ON rp.role_id=role.id JOIN iam.permission p ON p.id=rp.permission_id JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND role.status='ACTIVE' AND u.status='ACTIVE'
 AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP);
$$;
CREATE OR REPLACE FUNCTION workflow.assignment_valid(kind text,reference uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT iam.archive_access_enabled(person) AND CASE kind
 WHEN 'ROLE' THEN EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.id=reference AND a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND r.status='ACTIVE' AND (a.role_code<>'DEPARTMENT_ARCHIVIST' OR a.department_id=u.department_id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'DEPARTMENT' THEN EXISTS(SELECT 1 FROM iam.department_manager a JOIN iam.department d ON d.id=a.department_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.id=reference AND a.user_id=person AND u.department_id=a.department_id AND a.duty_type='DEPARTMENT_MANAGER' AND d.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'COMPANY' THEN EXISTS(SELECT 1 FROM iam.company_duty a WHERE a.id=reference AND a.user_id=person AND a.duty_code='GENERAL_MANAGER' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) ELSE false END;
$$;
COMMENT ON FUNCTION iam.archive_access_enabled(uuid) IS '当前档案入口权限按有效角色ALLOW减DENY计算，不能从组织职责自动增加';
