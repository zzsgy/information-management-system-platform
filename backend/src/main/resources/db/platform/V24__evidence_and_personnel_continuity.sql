-- 同意凭据固定到当前节点和业务快照；人员交接保留原任职、原决定与在途借还。
INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type)
 VALUES('09f4ded4-28db-4a13-ade3-0c0c88972401','PLATFORM','PLATFORM.EVIDENCE_RECORD','核验同意凭据并补录节点','WORKFLOW_STEP','EVIDENCE_RECORD',true,'MODULE');
INSERT INTO iam.role_permission(role_id,permission_id,effect,scope_policy,changed_by)
 SELECT r.id,p.id,'ALLOW','COMPANY',(SELECT id FROM iam.user_account ORDER BY created_at,id LIMIT 1)
 FROM iam.role r JOIN iam.permission p ON p.code='PLATFORM.EVIDENCE_RECORD' WHERE r.module='PLATFORM' AND r.code='IT_ADMIN';

CREATE TABLE workflow.evidence_submission (
 id uuid PRIMARY KEY,step_id uuid NOT NULL REFERENCES workflow.step(id),submitted_by uuid NOT NULL REFERENCES iam.user_account(id),
 original_approver_id uuid NOT NULL REFERENCES iam.user_account(id),assignment_ref uuid NOT NULL,step_version integer NOT NULL,
 agreed_at timestamptz NOT NULL,description varchar(2000) NOT NULL,business_snapshot jsonb NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','USED','REJECTED','CANCELLED')),
 reviewed_by uuid REFERENCES iam.user_account(id),review_note varchar(2000),reviewed_at timestamptz,decision_id uuid UNIQUE REFERENCES workflow.decision(id),
 row_version integer NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX ux_evidence_pending_step ON workflow.evidence_submission(step_id) WHERE state='PENDING';
CREATE INDEX ix_evidence_review_queue ON workflow.evidence_submission(state,created_at,id);
CREATE TABLE workflow.evidence (
 id uuid PRIMARY KEY,submission_id uuid NOT NULL REFERENCES workflow.evidence_submission(id),object_id uuid NOT NULL REFERENCES storage.object(id),
 digest char(64) NOT NULL,format varchar(128) NOT NULL,size bigint NOT NULL CHECK(size BETWEEN 1 AND 52428800),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(submission_id,object_id)
);
ALTER TABLE workflow.decision DROP CONSTRAINT decision_decision_mode_check;
ALTER TABLE workflow.decision ADD CHECK(decision_mode IN ('ONLINE','EVIDENCE_RECORD'));
CREATE TRIGGER evidence_immutable BEFORE UPDATE OR DELETE ON workflow.evidence FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE FUNCTION workflow.guard_evidence_submission() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN IF TG_OP='DELETE' OR OLD.state<>'PENDING' OR ROW(NEW.id,NEW.step_id,NEW.submitted_by,NEW.original_approver_id,NEW.assignment_ref,NEW.step_version,NEW.agreed_at,NEW.description,NEW.business_snapshot,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.step_id,OLD.submitted_by,OLD.original_approver_id,OLD.assignment_ref,OLD.step_version,OLD.agreed_at,OLD.description,OLD.business_snapshot,OLD.created_at)
 THEN RAISE EXCEPTION 'immutable evidence submission'; END IF; RETURN NEW;END $$;
CREATE TRIGGER evidence_submission_guard BEFORE UPDATE OR DELETE ON workflow.evidence_submission FOR EACH ROW EXECUTE FUNCTION workflow.guard_evidence_submission();
-- 补录证明与在线证明同样受固定业务和唯一节点约束，不能只修改方式字段绕过审批。
CREATE FUNCTION workflow.decision_valid(decision uuid,flow uuid,node uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM workflow.decision d JOIN workflow.step s ON s.id=d.step_id JOIN workflow.instance w ON w.id=s.instance_id
 WHERE d.id=decision AND s.id=node AND w.id=flow AND d.result='APPROVE' AND d.original_approver_id<>w.applicant_id AND d.operator_id<>w.applicant_id
 AND ((d.decision_mode='ONLINE' AND d.original_approver_id=d.operator_id)
 OR (d.decision_mode='EVIDENCE_RECORD' AND d.original_approver_id<>d.operator_id AND s.duty_code IN ('DEPARTMENT_MANAGER','GENERAL_MANAGER')
 AND EXISTS(SELECT 1 FROM workflow.evidence_submission e WHERE e.decision_id=d.id AND e.step_id=s.id AND e.state='USED'
 AND e.original_approver_id=d.original_approver_id AND e.original_approver_id=s.assignee_id AND e.reviewed_by=d.operator_id AND e.submitted_by=w.applicant_id
 AND e.assignment_ref=s.assignment_ref AND e.step_version+1=s.row_version AND e.agreed_at=d.agreed_at AND e.business_snapshot=w.business_snapshot
 AND (SELECT count(*) FROM workflow.evidence f WHERE f.submission_id=e.id) BETWEEN 1 AND 10
 AND NOT EXISTS(SELECT 1 FROM workflow.evidence f JOIN storage.object o ON o.id=f.object_id WHERE f.submission_id=e.id AND (o.state<>'READY' OR o.sha256<>f.digest OR o.byte_size<>f.size OR o.module<>'ARCHIVE'))))));
$$;
CREATE OR REPLACE FUNCTION workflow.approval_valid(flow uuid,kind text,business uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM workflow.instance w JOIN workflow.route_snapshot route ON route.id=w.route_snapshot_id AND route.instance_id=w.id
 WHERE w.id=flow AND w.business_type=kind AND w.business_id=business AND w.state='APPROVED' AND w.current_step_id IS NULL
 AND jsonb_array_length(route.nodes_snapshot)>0 AND jsonb_array_length(route.nodes_snapshot)=(SELECT count(*) FROM workflow.step s WHERE s.instance_id=w.id)
 AND NOT EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id AND (s.state<>'APPROVED' OR s.duty_code<>route.nodes_snapshot->(s.sequence_no-1)->>'duty'
 OR NOT EXISTS(SELECT 1 FROM workflow.decision d WHERE d.step_id=s.id AND workflow.decision_valid(d.id,w.id,s.id)))));
$$;

CREATE TABLE workflow.archivist_handover (
 id uuid PRIMARY KEY,department_id uuid NOT NULL REFERENCES iam.department(id),from_user_id uuid NOT NULL REFERENCES iam.user_account(id),
 to_user_id uuid NOT NULL REFERENCES iam.user_account(id),operator_id uuid NOT NULL REFERENCES iam.user_account(id),
 preview_digest char(64) NOT NULL,approval_reference varchar(256) NOT NULL,reason varchar(1000) NOT NULL,
 items_snapshot jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,CHECK(from_user_id<>to_user_id)
);
ALTER TABLE workflow.assignment_event ADD COLUMN department_id uuid REFERENCES iam.department(id),ADD COLUMN handover_batch_id uuid REFERENCES workflow.archivist_handover(id);
CREATE INDEX ix_assignment_handover ON workflow.assignment_event(handover_batch_id,step_id);
CREATE INDEX ix_handover_department ON workflow.archivist_handover(department_id,created_at DESC,id);
CREATE TRIGGER archivist_handover_immutable BEFORE UPDATE OR DELETE ON workflow.archivist_handover FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
ALTER TABLE archive.catalog_change ADD COLUMN managed_by uuid REFERENCES iam.user_account(id);
COMMENT ON COLUMN archive.catalog_change.managed_by IS '交接后的事项经办人；创建人及已发生审批不改写，空值表示原创建人';
COMMENT ON COLUMN workflow.assignment_event.department_id IS '交接涉及的部门；普通历史改派可为空';
COMMENT ON COLUMN workflow.assignment_event.handover_batch_id IS '同次原子交接批次；单节点改派为空';
CREATE TABLE iam.department_change (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),from_department_id uuid NOT NULL REFERENCES iam.department(id),
 to_department_id uuid NOT NULL REFERENCES iam.department(id),operator_id uuid NOT NULL REFERENCES iam.user_account(id),
 approval_ref varchar(256) NOT NULL,handover_note varchar(2000) NOT NULL,revoked_counts jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_person_department_change ON iam.department_change(user_id,created_at DESC,id);
CREATE TRIGGER department_change_immutable BEFORE UPDATE OR DELETE ON iam.department_change FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();

ALTER TABLE ops.business_notification DROP CONSTRAINT business_notification_source_kind_check;
ALTER TABLE ops.business_notification ADD CHECK(source_kind IN ('WORKFLOW_STEP','WORKFLOW_INSTANCE','ELECTRONIC_REMINDER','PHYSICAL_NOTICE','CATALOG_CHANGE','RETENTION','EVIDENCE_DECISION'));
ALTER FUNCTION ops.notification_current(uuid,uuid) RENAME TO notification_current_v23;
CREATE FUNCTION ops.notification_current(notice uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN n.source_kind='EVIDENCE_DECISION' THEN n.user_id=person AND n.state<>'CANCELLED' AND iam.archive_access_enabled(person)
 AND EXISTS(SELECT 1 FROM workflow.decision d JOIN workflow.step s ON s.id=d.step_id JOIN workflow.instance w ON w.id=s.instance_id
 WHERE d.id=n.source_id AND d.decision_mode='EVIDENCE_RECORD' AND person IN (d.original_approver_id,w.applicant_id))
 ELSE ops.notification_current_v23(notice,person) END FROM ops.business_notification n WHERE n.id=notice;
$$;
COMMENT ON TABLE workflow.evidence_submission IS '申请人提交的固定节点同意事实，只有核验后才能补录当前节点';
COMMENT ON TABLE workflow.evidence IS '通过真实检测的凭据原件及不可覆盖校验值，不作为正式归档PDF';
COMMENT ON TABLE workflow.archivist_handover IS '原子任职替换与未完事项交接的固定清单，不转移个人使用授权';
COMMENT ON TABLE iam.department_change IS '员工转岗与旧个人授权回收记录，保留历史借还与实物责任';
-- 共用字段说明覆盖每一新增列，避免类型可见但业务用途不明。
DO $$ DECLARE c record; labels jsonb:='{"id":"稳定记录标识","step_id":"固定审批节点","submitted_by":"实际提交申请人","original_approver_id":"原业务同意人","assignment_ref":"提交时固定任职来源","step_version":"提交时节点版本","agreed_at":"实际同意时间","description":"与本次对象动作期限对应的说明","business_snapshot":"冻结业务清单与用途期限","state":"凭据办理状态","reviewed_by":"实际IT核验经办人","review_note":"核验意见及未通过原因","reviewed_at":"实际核验时间","decision_id":"唯一有效补录决定","row_version":"并发操作版本","created_at":"服务器登记时间","submission_id":"同次凭据提交批次","object_id":"已检测不可变文件对象","digest":"原始字节SHA256","format":"真实检测媒体格式","size":"原件字节数","department_id":"交接部门","from_user_id":"原经办人","to_user_id":"新经办人","operator_id":"实际管理操作人","preview_digest":"前后人员及固定事项版本摘要","approval_reference":"批准交接依据","reason":"交接原因","items_snapshot":"固定未完事项和版本清单","user_id":"转岗员工","from_department_id":"原部门","to_department_id":"批准的新部门","approval_ref":"转岗批准依据","handover_note":"资料及实物责任核对说明","revoked_counts":"各领域实际回收数量"}'::jsonb;
 BEGIN FOR c IN SELECT table_schema,table_name,column_name FROM information_schema.columns WHERE (table_schema,table_name) IN (('workflow','evidence_submission'),('workflow','evidence'),('workflow','archivist_handover'),('iam','department_change')) LOOP EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',c.table_schema,c.table_name,c.column_name,labels->>c.column_name);END LOOP;END $$;
