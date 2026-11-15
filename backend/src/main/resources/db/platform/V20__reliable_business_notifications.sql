-- 通知与流程事务一起提交；投递工作器只能送消息，不能重执批准或延长借阅。
CREATE TABLE ops.business_notification (
 id uuid PRIMARY KEY,event_key varchar(512) NOT NULL UNIQUE,user_id uuid NOT NULL REFERENCES iam.user_account(id),
 source_kind varchar(32) NOT NULL CHECK(source_kind IN ('WORKFLOW_STEP','WORKFLOW_INSTANCE','ELECTRONIC_REMINDER','PHYSICAL_NOTICE')),
 source_id uuid NOT NULL,source_version varchar(128),source_state varchar(32),kind varchar(64) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'UNREAD' CHECK(state IN ('UNREAD','READ','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,read_at timestamptz
);
CREATE INDEX ix_business_notice_person ON ops.business_notification(user_id,state,created_at DESC,id);
CREATE TABLE ops.notification_delivery (
 id uuid PRIMARY KEY,notification_id uuid NOT NULL UNIQUE REFERENCES ops.business_notification(id),
 channel varchar(16) NOT NULL DEFAULT 'LOCAL_MAIL' CHECK(channel='LOCAL_MAIL'),
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','RETRY','SENT','MANUAL','CANCELLED')),
 attempt_count integer NOT NULL DEFAULT 0,cycle_attempts integer NOT NULL DEFAULT 0,
 next_run_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,lease_token uuid,lease_until timestamptz,
 last_error varchar(64),receipt_id varchar(128),row_version integer NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,sent_at timestamptz
);
CREATE INDEX ix_delivery_due ON ops.notification_delivery(next_run_at,id) WHERE state IN ('PENDING','RETRY');
CREATE INDEX ix_delivery_lease ON ops.notification_delivery(lease_until,id) WHERE state='RUNNING';
CREATE TABLE ops.notification_attempt (
 id uuid PRIMARY KEY,delivery_id uuid NOT NULL REFERENCES ops.notification_delivery(id),attempt_no integer NOT NULL,
 lease_hash varchar(64) NOT NULL,outcome varchar(16) NOT NULL CHECK(outcome IN ('RUNNING','SENT','FAILED','UNKNOWN','CANCELLED')),
 started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,ended_at timestamptz,error_code varchar(64),receipt_id varchar(128),UNIQUE(delivery_id,attempt_no)
);

-- 与当前Actor相同的有效角色ALLOW减DENY；个人ALLOW不作为本期扩权入口。
CREATE FUNCTION iam.action_enabled(person uuid,action_code text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT COALESCE(bool_or(rp.effect='ALLOW'),false) AND NOT COALESCE(bool_or(rp.effect='DENY'),false)
 AND NOT EXISTS(SELECT 1 FROM iam.permission_assignment pa JOIN iam.permission p ON p.id=pa.permission_id WHERE pa.user_id=person AND p.code=action_code AND pa.state='ACTIVE' AND pa.effect='DENY' AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP))
 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.role_permission rp ON rp.role_id=r.id JOIN iam.permission p ON p.id=rp.permission_id AND p.module=r.module JOIN iam.user_account u ON u.id=a.user_id
 WHERE a.user_id=person AND u.status='ACTIVE' AND NOT u.password_setup_required AND a.state='ACTIVE' AND r.status='ACTIVE' AND rp.scope_policy='COMPANY' AND p.code=action_code AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP);
$$;
CREATE FUNCTION ops.notification_current(notice uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM ops.business_notification n WHERE n.id=notice AND n.user_id=person AND n.state<>'CANCELLED' AND iam.archive_access_enabled(person) AND CASE n.source_kind
 WHEN 'WORKFLOW_STEP' THEN EXISTS(SELECT 1 FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE s.id=n.source_id AND s.assignee_id=person AND s.assignment_ref::text=n.source_version AND s.state='PENDING' AND w.state='PENDING' AND s.assignee_id<>w.applicant_id AND workflow.assignment_valid(s.assignment_type,s.assignment_ref,person))
 WHEN 'WORKFLOW_INSTANCE' THEN EXISTS(SELECT 1 FROM workflow.instance w WHERE w.id=n.source_id AND w.applicant_id=person AND w.state=n.source_state)
 WHEN 'ELECTRONIC_REMINDER' THEN EXISTS(SELECT 1 FROM ops.inbox_notification old WHERE old.id=n.source_id AND old.user_id=person AND old.state<>'CANCELLED' AND archive.reminder_current(old.request_id,person,old.deadline))
 WHEN 'PHYSICAL_NOTICE' THEN EXISTS(SELECT 1 FROM archive.physical_notice old JOIN archive.physical_loan l ON l.id=old.loan_id JOIN archive.box b ON b.id=l.box_id JOIN iam.user_account u ON u.id=person WHERE old.id=n.source_id AND old.user_id=person AND old.state<>'CANCELLED' AND (old.kind IN ('RETURN_CONFIRMED','RETURN_EXCEPTION') OR (l.state='IN_USE' AND old.deadline=l.due_at)) AND (l.borrower_id=person OR (iam.action_enabled(person,'ARCHIVE.PHYSICAL_MANAGE') AND EXISTS(SELECT 1 FROM iam.user_role a WHERE a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP) AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=b.department_id AND u.department_id=b.department_id)))))) ELSE false END);
$$;
-- 事件键固定业务事实与接收人，重放不创建第二条通知或第二个投递任务。
CREATE FUNCTION ops.publish_notification(event text,person uuid,source text,object_id uuid,version text,expected text,category text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE notice uuid; BEGIN
 INSERT INTO ops.business_notification(id,event_key,user_id,source_kind,source_id,source_version,source_state,kind) VALUES(gen_random_uuid(),event,person,source,object_id,version,expected,category) ON CONFLICT(event_key) DO NOTHING;
 SELECT id INTO notice FROM ops.business_notification WHERE event_key=event;
 INSERT INTO ops.notification_delivery(id,notification_id) VALUES(gen_random_uuid(),notice) ON CONFLICT(notification_id) DO NOTHING;
END $$;
CREATE FUNCTION ops.workflow_notification() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE step workflow.step; BEGIN
 IF NEW.state='PENDING' AND NEW.current_step_id IS NOT NULL THEN
  SELECT * INTO step FROM workflow.step WHERE id=NEW.current_step_id;
  PERFORM ops.publish_notification('STEP:'||step.id||':'||step.assignee_id||':'||step.assignment_ref,step.assignee_id,'WORKFLOW_STEP',step.id,step.assignment_ref::text,'PENDING','TASK_AVAILABLE');
 ELSIF NEW.state IN ('APPROVED','REJECTED','WITHDRAWN','CLOSED') THEN
  PERFORM ops.publish_notification('RESULT:'||NEW.id||':'||NEW.state,NEW.applicant_id,'WORKFLOW_INSTANCE',NEW.id,NULL,NEW.state,'WORKFLOW_'||NEW.state);
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER workflow_notification AFTER INSERT OR UPDATE OF state,current_step_id ON workflow.instance FOR EACH ROW EXECUTE FUNCTION ops.workflow_notification();
CREATE FUNCTION ops.reassignment_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state='PENDING' AND EXISTS(SELECT 1 FROM workflow.instance w WHERE w.current_step_id=NEW.id AND w.state='PENDING') THEN PERFORM ops.publish_notification('STEP:'||NEW.id||':'||NEW.assignee_id||':'||NEW.assignment_ref,NEW.assignee_id,'WORKFLOW_STEP',NEW.id,NEW.assignment_ref::text,'PENDING','TASK_AVAILABLE');END IF;RETURN NEW;
END $$;
CREATE TRIGGER reassignment_notification AFTER UPDATE OF assignee_id,assignment_ref ON workflow.step FOR EACH ROW EXECUTE FUNCTION ops.reassignment_notification();
CREATE FUNCTION ops.reminder_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state<>'CANCELLED' THEN
  IF TG_TABLE_SCHEMA='ops' THEN PERFORM ops.publish_notification('ELECTRONIC:'||NEW.id,NEW.user_id,'ELECTRONIC_REMINDER',NEW.id,NULL,NULL,'ELECTRONIC_DUE');
  ELSE PERFORM ops.publish_notification('PHYSICAL:'||NEW.id,NEW.user_id,'PHYSICAL_NOTICE',NEW.id,NULL,NULL,NEW.kind);END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER electronic_notification AFTER INSERT OR UPDATE OF state ON ops.inbox_notification FOR EACH ROW EXECUTE FUNCTION ops.reminder_notification();
CREATE TRIGGER physical_notification AFTER INSERT OR UPDATE OF state ON archive.physical_notice FOR EACH ROW EXECUTE FUNCTION ops.reminder_notification();

COMMENT ON TABLE ops.business_notification IS '事务内生成的最小业务通知，只含类型与受控引用，不复制秘密题名';
COMMENT ON TABLE ops.notification_delivery IS '独立投递状态、可重入租约、重试及人工队列，不影响业务批准';
COMMENT ON TABLE ops.notification_attempt IS '逐次投递尝试及结果，超时不假定已成功或未发送';
DO $$ DECLARE field record; descriptions jsonb := '{"id":"稳定记录标识","event_key":"同一业务事实和接收人的去重键","user_id":"明确的通知接收员工","source_kind":"受支持的流程、电子提醒或实物事项来源","source_id":"来源标识，通知引用不授予读取权限","source_version":"原审批任职记录，用于改派后停止旧待办通知","source_state":"结果通知所对应的真实流程状态","kind":"由固定安全模板解释的通知种类","state":"本人已读状态或独立投递状态","created_at":"首次创建时间","read_at":"本人首次确认已读时间","notification_id":"对应唯一站内通知","channel":"本批仅LOCAL_MAIL私有本机投递，不声称已发公司邮箱","attempt_count":"历史投递尝试累计次数，不因人工重试归零","cycle_attempts":"本轮自动尝试数，初次加三次重试共四次","next_run_at":"下次允许领取的时刻","lease_token":"当前领取者随机租约，旧领取者不得确认新任务","lease_until":"租约到期时刻，过期任务可重领并按稳定幂等键去重","last_error":"脱敏错误码，不保存外部响应、正文或口令","receipt_id":"投递适配器返回的稳定回执","row_version":"人工操作和投递并发控制版本","sent_at":"适配器确认后的提交时间","delivery_id":"对应独立投递任务","attempt_no":"该任务历史递增的尝试编号","lease_hash":"当前尝试租约摘要，不保存明文令牌","outcome":"尝试结果；UNKNOWN表示中断后无法认定实际发送结果","started_at":"本次领取时间","ended_at":"本次结果确认时间","error_code":"本次脱敏失败原因"}'::jsonb; BEGIN
 FOR field IN SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='ops' AND table_name IN ('business_notification','notification_delivery','notification_attempt') LOOP
 IF NOT descriptions ? field.column_name THEN RAISE EXCEPTION 'Missing notification field description';END IF;
 EXECUTE format('COMMENT ON COLUMN ops.%I.%I IS %L',field.table_name,field.column_name,descriptions->>field.column_name);END LOOP;END $$;
