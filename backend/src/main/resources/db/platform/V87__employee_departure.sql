-- 离职区别于临时停用，稳定账号标识一直保留供历史审计及业务关系引用。
ALTER TABLE iam.user_account DROP CONSTRAINT user_account_status_check,
 ADD CONSTRAINT user_account_status_check CHECK (status IN ('ACTIVE','DISABLED','DEPARTED'));
COMMENT ON COLUMN iam.user_account.status IS '在职可用、临时停用或已离职；后两种均禁止认证';

CREATE TABLE iam.employee_departure (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL UNIQUE REFERENCES iam.user_account(id),
 employee_no varchar(64) NOT NULL,
 employee_name varchar(128) NOT NULL,
 department_id uuid NOT NULL REFERENCES iam.department(id),
 department_name varchar(128) NOT NULL,
 previous_status varchar(16) NOT NULL CHECK (previous_status IN ('ACTIVE','DISABLED')),
 operator_id uuid NOT NULL REFERENCES iam.user_account(id),
 request_id uuid NOT NULL,
 reason varchar(1000) NOT NULL,
 impact jsonb NOT NULL,
 departed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX employee_departure_recent ON iam.employee_departure(departed_at DESC,id DESC);
CREATE TABLE iam.employee_departure_record (
 departure_id uuid NOT NULL REFERENCES iam.employee_departure(id),
 record_type varchar(32) NOT NULL,
 source_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
 PRIMARY KEY (departure_id,record_type,source_id)
);
CREATE INDEX employee_departure_record_recent ON iam.employee_departure_record(departure_id,occurred_at DESC,record_type,source_id);
COMMENT ON TABLE iam.employee_departure IS '一人一份不可覆盖的离职主档，保存离职生效时的人事资料与撤权影响';
COMMENT ON TABLE iam.employee_departure_record IS '独立离职留痕：保存操作日志及业务元数据；原业务表继续保有正文、附件和原经办人';
COMMENT ON COLUMN iam.employee_departure.user_id IS '不因离职而删除的历史账号标识';
COMMENT ON COLUMN iam.employee_departure.impact IS '办理前的授权、待交接事项和实物责任数量';
COMMENT ON COLUMN iam.employee_departure_record.record_type IS '稳定的历史来源类别';
COMMENT ON COLUMN iam.employee_departure_record.source_id IS '原表记录标识，不因原表留存策略删除快照';
COMMENT ON COLUMN iam.employee_departure_record.occurred_at IS '原事件时刻或记录建立时刻';
COMMENT ON COLUMN iam.employee_departure_record.snapshot IS '明确列出的业务元数据，不保存密码、令牌、凭据或业务文件副本';
CREATE TRIGGER departure_immutable BEFORE UPDATE OR DELETE ON iam.employee_departure
 FOR EACH ROW EXECUTE FUNCTION ops.reject_audit_rewrite();
CREATE TRIGGER departure_record_immutable BEFORE UPDATE OR DELETE ON iam.employee_departure_record
 FOR EACH ROW EXECUTE FUNCTION ops.reject_audit_rewrite();

-- 成批归档直接在数据库执行，避免把员工全量多年历史读入应用内存。
CREATE FUNCTION iam.capture_departure_records(departure uuid, person uuid) RETURNS void LANGUAGE sql AS $$
 INSERT INTO iam.employee_departure_record(departure_id,record_type,source_id,occurred_at,snapshot)
 SELECT departure, history.* FROM (
  SELECT 'AUDIT' AS record_type,e.id,e.occurred_at,
   jsonb_build_object('actorId',e.actor_id,'module',e.module,'resourceType',e.resource_type,'resourceId',e.resource_id,
    'action',e.action,'result',e.result,'requestId',e.request_id,'severity',e.severity) AS snapshot
  FROM ops.audit_event e WHERE e.record_kind<>'IDEMPOTENCY_RECEIPT'
   AND (e.actor_id=person OR (e.resource_type='USER' AND e.resource_id=person))
  UNION ALL
  SELECT 'INTERACTION',i.id,i.occurred_at,jsonb_build_object('page',i.page,'action',i.action,'control',i.control,'requestId',i.request_id)
  FROM ops.ui_interaction i WHERE i.user_id=person
  UNION ALL
  SELECT 'ROLE',r.id,r.valid_from,jsonb_build_object('module',r.module,'role',r.role_code,'departmentId',r.department_id,'state',r.state,'endedAt',r.valid_until)
  FROM iam.user_role r WHERE r.user_id=person
  UNION ALL
  SELECT 'PERSONAL_PERMISSION',p.id,p.valid_from,jsonb_build_object('permission',c.code,'effect',p.effect,'state',p.state,'scopeId',p.scope_id)
  FROM iam.permission_assignment p JOIN iam.permission c ON c.id=p.permission_id WHERE p.user_id=person
  UNION ALL
  SELECT 'DEPARTMENT_DUTY',d.id,d.valid_from,jsonb_build_object('duty',d.duty_type,'departmentId',d.department_id,'endedAt',d.valid_until)
  FROM iam.department_manager d WHERE d.user_id=person
  UNION ALL
  SELECT 'COMPANY_DUTY',d.id,d.valid_from,jsonb_build_object('duty',d.duty_code,'endedAt',d.valid_until)
  FROM iam.company_duty d WHERE d.user_id=person
  UNION ALL
  SELECT 'TRANSFER',c.id,c.created_at,jsonb_build_object('fromDepartmentId',c.from_department_id,'toDepartmentId',c.to_department_id,'operatorId',c.operator_id)
  FROM iam.department_change c WHERE c.user_id=person
  UNION ALL
  SELECT 'ARCHIVE_GRANT',g.id,g.valid_from,jsonb_build_object('recordId',g.record_id,'action',g.action,'state',g.state,'endedAt',g.valid_until)
  FROM archive.access_grant g WHERE g.user_id=person
  UNION ALL
  SELECT 'DRIVE_GRANT',g.id,g.created_at,jsonb_build_object('resourceId',g.resource_id,'resourceType',g.resource_type,'action',g.action,'state',g.state)
  FROM drive.resource_grant g WHERE g.subject_id=person
  UNION ALL
  SELECT 'WORKFLOW',w.id,w.created_at,jsonb_build_object('businessType',w.business_type,'businessId',w.business_id,'state',w.state)
  FROM workflow.instance w WHERE w.applicant_id=person
  UNION ALL
  SELECT 'WORKFLOW_STEP',s.id,COALESCE(s.completed_at,w.created_at),jsonb_build_object('workflowId',w.id,'duty',s.duty_code,'state',s.state)
  FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id WHERE s.assignee_id=person
  UNION ALL
  SELECT 'APPROVAL',d.id,d.recorded_at,jsonb_build_object('stepId',d.step_id,'operatorId',d.operator_id,'result',d.result,'mode',d.decision_mode)
  FROM workflow.decision d WHERE d.operator_id=person OR d.original_approver_id=person
  UNION ALL
  -- 新版纸质借阅在实际交付前已有占用记录，最早可追溯至取件或预约。
  SELECT 'PHYSICAL_LOAN',l.id,COALESCE(l.actual_delivery_at,l.pickup_at,l.reserved_at),
         jsonb_build_object('requestId',l.request_id,'state',l.state,'dueAt',l.due_at,'returnedAt',l.returned_at)
  FROM archive.physical_loan l WHERE l.borrower_id=person OR l.delivered_by=person
  UNION ALL
  SELECT 'ARCHIVE_RECORD',r.id,r.created_at,jsonb_build_object('boxId',r.box_id,'state',r.state)
  FROM archive.record r WHERE r.created_by=person
  UNION ALL
  SELECT 'DRIVE_VERSION',v.id,v.created_at,jsonb_build_object('documentId',v.document_id,'version',v.version_no,'source',v.source_type)
  FROM drive.document_version v WHERE v.created_by=person
  UNION ALL
  SELECT 'MEETING',m.id,m.starts_at,jsonb_build_object('kind',m.kind,'endsAt',m.ends_at,'cancelledAt',m.cancelled_at)
  FROM portal.meeting_booking m WHERE m.organizer_id=person
  UNION ALL
  SELECT 'MEETING_INVITATION',a.id,m.starts_at,jsonb_build_object('meetingId',m.id,'reply',a.reply,'removedAt',a.removed_at)
  FROM portal.meeting_attendee a JOIN portal.meeting_booking m ON m.id=a.meeting_id WHERE a.user_id=person
  UNION ALL
  SELECT 'TODO',t.id,t.created_at,jsonb_build_object('creatorId',t.creator_id,'assigneeId',t.assignee_id,'state',t.state,'completedAt',t.completed_at)
  FROM portal.employee_todo t WHERE t.creator_id=person OR t.assignee_id=person
  UNION ALL
  SELECT 'FEEDBACK',f.id,f.created_at,jsonb_build_object('category',f.category,'state',f.state)
  FROM portal.feedback f WHERE f.applicant_id=person
  UNION ALL
  SELECT 'FEEDBACK_REPLY',r.id,r.created_at,jsonb_build_object('feedbackId',r.feedback_id,'readAt',r.read_at)
  FROM portal.feedback_reply r WHERE r.author_id=person
  UNION ALL
  SELECT 'ANNOUNCEMENT',a.id,a.created_at,jsonb_build_object('category',a.category,'state',a.state)
  FROM portal.announcement a WHERE a.created_by=person
  UNION ALL
  SELECT 'ANNOUNCEMENT_READ',a.announcement_id,a.read_at,jsonb_build_object('announcementId',a.announcement_id)
  FROM portal.announcement_read a WHERE a.user_id=person
 ) history;
$$;
