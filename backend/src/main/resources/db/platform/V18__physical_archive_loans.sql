-- 纸质占用独立于电子授权；盒行锁保证跨对象互斥，部分唯一索引兜底同原件重复借出。
ALTER TABLE archive.borrow_batch DROP CONSTRAINT borrow_batch_form_check,DROP CONSTRAINT borrow_batch_actions_check;
ALTER TABLE archive.borrow_batch ADD CHECK(form IN ('ELECTRONIC','PAPER')),
 ADD CHECK((form='ELECTRONIC' AND cardinality(actions) BETWEEN 1 AND 3 AND actions <@ ARRAY['VIEW','DOWNLOAD','PRINT']::varchar[]) OR (form='PAPER' AND actions=ARRAY['BORROW_PHYSICAL']::varchar[]));
ALTER TABLE archive.record ADD UNIQUE(id,box_id);
ALTER TABLE workflow.route_snapshot ADD COLUMN parent_snapshot_id uuid REFERENCES workflow.route_snapshot(id),ADD COLUMN supplement_reason varchar(1000);
COMMENT ON COLUMN workflow.route_snapshot.parent_snapshot_id IS '补充批准关联原不可变路线，不覆盖原快照';
COMMENT ON COLUMN workflow.route_snapshot.supplement_reason IS '实际交付触发补充分管领导的原因';
CREATE TABLE archive.physical_item (
 id uuid PRIMARY KEY,box_id uuid NOT NULL REFERENCES archive.box(id),record_id uuid,object_type varchar(16) NOT NULL CHECK(object_type IN ('BOX','RECORD')),
 barcode varchar(128) NOT NULL UNIQUE,location varchar(512) NOT NULL,quantity integer NOT NULL DEFAULT 1 CHECK(quantity=1),row_version integer NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(record_id),FOREIGN KEY(record_id,box_id) REFERENCES archive.record(id,box_id),
 CHECK((object_type='BOX' AND record_id IS NULL) OR (object_type='RECORD' AND record_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_physical_box ON archive.physical_item(box_id) WHERE object_type='BOX';
CREATE TABLE archive.paper_request_item (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES archive.borrow_request(id),physical_item_id uuid NOT NULL REFERENCES archive.physical_item(id),
 box_id uuid NOT NULL REFERENCES archive.box(id),box_revision_id uuid NOT NULL,members jsonb NOT NULL,label varchar(512) NOT NULL,
 UNIQUE(request_id,physical_item_id),FOREIGN KEY(box_id,box_revision_id) REFERENCES archive.box_revision(box_id,id)
);
CREATE TABLE archive.physical_loan (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES archive.borrow_request(id),current_request_id uuid NOT NULL REFERENCES archive.borrow_request(id),physical_item_id uuid NOT NULL REFERENCES archive.physical_item(id),
 box_id uuid NOT NULL REFERENCES archive.box(id),borrower_id uuid NOT NULL REFERENCES iam.user_account(id),delivered_by uuid NOT NULL REFERENCES iam.user_account(id),delivery_receipt_id uuid NOT NULL,
 actual_delivery_at timestamptz NOT NULL,due_at timestamptz NOT NULL,actual_quantity integer NOT NULL CHECK(actual_quantity=1),condition varchar(2000) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'IN_USE' CHECK(state IN ('IN_USE','RETURNED')),returned_at timestamptz,row_version integer NOT NULL DEFAULT 1,
 CHECK(due_at>actual_delivery_at),CHECK((state='IN_USE' AND returned_at IS NULL) OR (state='RETURNED' AND returned_at>=actual_delivery_at))
);
CREATE UNIQUE INDEX ux_active_physical_item ON archive.physical_loan(physical_item_id) WHERE state='IN_USE';
CREATE INDEX ix_physical_active_box ON archive.physical_loan(box_id,physical_item_id) WHERE state='IN_USE';
CREATE INDEX ix_physical_actor ON archive.physical_loan(borrower_id,due_at,id);
CREATE TABLE archive.paper_loan_scope (request_id uuid NOT NULL REFERENCES archive.borrow_request(id),loan_id uuid NOT NULL REFERENCES archive.physical_loan(id),PRIMARY KEY(request_id,loan_id));
CREATE TABLE archive.physical_delivery (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES archive.borrow_request(id),actor_id uuid NOT NULL REFERENCES iam.user_account(id),operation_id uuid NOT NULL,
 scope_digest varchar(64) NOT NULL,final_digest varchar(64),body_snapshot jsonb NOT NULL,state varchar(32) NOT NULL CHECK(state IN ('WAITING_APPROVAL','DELIVERED')),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(actor_id,operation_id)
);
CREATE TABLE archive.physical_delivery_loan (delivery_id uuid NOT NULL REFERENCES archive.physical_delivery(id),loan_id uuid NOT NULL REFERENCES archive.physical_loan(id),PRIMARY KEY(delivery_id,loan_id));
CREATE TABLE archive.physical_return (
 id uuid PRIMARY KEY,loan_id uuid NOT NULL REFERENCES archive.physical_loan(id),reported_quantity integer NOT NULL CHECK(reported_quantity BETWEEN 0 AND 1),condition varchar(2000) NOT NULL,note varchar(2000),
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','CONFIRMED','EXCEPTION')),submitted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 reviewed_by uuid REFERENCES iam.user_account(id),receipt_id uuid,actual_received_at timestamptz,received_quantity integer CHECK(received_quantity BETWEEN 0 AND 1),review_condition varchar(2000),reviewed_at timestamptz
);
CREATE UNIQUE INDEX ux_pending_physical_return ON archive.physical_return(loan_id) WHERE state='PENDING';
CREATE TABLE archive.loan_event (
 id uuid PRIMARY KEY,loan_id uuid REFERENCES archive.physical_loan(id),physical_item_id uuid NOT NULL REFERENCES archive.physical_item(id),actor_id uuid NOT NULL REFERENCES iam.user_account(id),
 event_type varchar(64) NOT NULL,details jsonb NOT NULL,occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE archive.physical_notice (
 id uuid PRIMARY KEY,loan_id uuid NOT NULL REFERENCES archive.physical_loan(id),user_id uuid NOT NULL REFERENCES iam.user_account(id),kind varchar(32) NOT NULL,
 deadline timestamptz NOT NULL,notice_day date NOT NULL,state varchar(16) NOT NULL DEFAULT 'UNREAD' CHECK(state IN ('UNREAD','READ','CANCELLED')),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(loan_id,user_id,kind,deadline,notice_day)
);
CREATE INDEX ix_paper_item_request ON archive.paper_request_item(request_id);
CREATE INDEX ix_physical_notice_person ON archive.physical_notice(user_id,state,created_at DESC);
CREATE INDEX ix_physical_due ON archive.physical_loan(due_at,id) WHERE state='IN_USE';
CREATE TRIGGER paper_request_item_immutable BEFORE UPDATE OR DELETE ON archive.paper_request_item FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER paper_scope_immutable BEFORE UPDATE OR DELETE ON archive.paper_loan_scope FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER delivery_scope_immutable BEFORE UPDATE OR DELETE ON archive.physical_delivery_loan FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER loan_event_immutable BEFORE UPDATE OR DELETE ON archive.loan_event FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type) VALUES
 ('09f4ded4-28db-4a13-ade3-0c0c88971801','ARCHIVE','ARCHIVE.PHYSICAL_BORROW','申请纸质借阅','PHYSICAL','PHYSICAL_BORROW',false,'MODULE'),
 ('09f4ded4-28db-4a13-ade3-0c0c88971802','ARCHIVE','ARCHIVE.PHYSICAL_MANAGE','办理实物交付和台账','PHYSICAL','PHYSICAL_MANAGE',true,'MODULE'),
 ('09f4ded4-28db-4a13-ade3-0c0c88971803','ARCHIVE','ARCHIVE.PHYSICAL_REVIEW','复核实际归还','PHYSICAL','PHYSICAL_REVIEW',true,'MODULE');
INSERT INTO iam.role_permission(role_id,permission_id,effect,scope_policy,changed_by)
 SELECT r.id,p.id,'ALLOW','COMPANY',(SELECT id FROM iam.user_account ORDER BY created_at,id LIMIT 1) FROM iam.role r JOIN iam.permission p ON p.module=r.module WHERE r.module='ARCHIVE' AND
 ((p.code='ARCHIVE.PHYSICAL_BORROW' AND r.code IN ('STAFF','DEPARTMENT_ARCHIVIST','SUPER_ADMIN')) OR (p.code='ARCHIVE.PHYSICAL_MANAGE' AND r.code IN ('DEPARTMENT_ARCHIVIST','SUPER_ADMIN')) OR (p.code='ARCHIVE.PHYSICAL_REVIEW' AND r.code='SUPER_ADMIN'));
-- 纸质题名策略允许空PDF，但不能生成电子正文权限。旧电子策略证明保持原有严格条件。
CREATE OR REPLACE FUNCTION archive.policy_approved(policy uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_policy p JOIN workflow.instance w ON w.id=p.workflow_instance_id JOIN archive.record r ON r.id=p.object_id
 WHERE p.id=policy AND p.object_type='RECORD' AND p.state='ACTIVE' AND p.valid_from<=CURRENT_TIMESTAMP AND (p.valid_until IS NULL OR p.valid_until>CURRENT_TIMESTAMP)
 AND workflow.approval_valid(w.id,'ACCESS_POLICY',p.object_id) AND w.business_snapshot->>'objectId'=p.object_id::text
 AND w.business_snapshot->>'recordRevisionId'=p.record_revision_id::text AND (w.business_snapshot->>'pdfVersionId') IS NOT DISTINCT FROM p.pdf_version_id::text
 AND (p.pdf_version_id IS NOT NULL OR (r.carrier_type='PAPER' AND NOT p.allow_owner_department_view AND w.business_snapshot->'userIds'='[]'::jsonb))
 AND (w.business_snapshot->>'basePolicyVersion')::integer+1=p.policy_version AND (w.business_snapshot->>'allowOwnerDepartmentView')::boolean=p.allow_owner_department_view
 AND w.business_snapshot->>'titleVisibility'=p.title_visibility AND w.business_snapshot->'safeSearchFields'=to_jsonb(p.safe_search_fields) AND w.business_snapshot->'departmentScope'=to_jsonb(p.department_scope)
 AND (w.business_snapshot->>'validFrom')::timestamptz=p.valid_from AND (w.business_snapshot->>'validUntil')::timestamptz IS NOT DISTINCT FROM p.valid_until);
$$;
CREATE OR REPLACE FUNCTION workflow.assignment_valid(kind text,reference uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT iam.archive_access_enabled(person) AND CASE kind
 WHEN 'ROLE' THEN EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.id=reference AND a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND r.status='ACTIVE' AND (a.role_code<>'DEPARTMENT_ARCHIVIST' OR a.department_id=u.department_id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'DEPARTMENT' THEN EXISTS(SELECT 1 FROM iam.department_manager a JOIN iam.department d ON d.id=a.department_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.id=reference AND a.user_id=person AND u.department_id=a.department_id AND a.duty_type='DEPARTMENT_MANAGER' AND d.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'SUPERVISING' THEN EXISTS(SELECT 1 FROM iam.department_manager a JOIN iam.department d ON d.id=a.department_id WHERE a.id=reference AND a.user_id=person AND a.duty_type='SUPERVISING_LEADER' AND d.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'COMPANY' THEN EXISTS(SELECT 1 FROM iam.company_duty a WHERE a.id=reference AND a.user_id=person AND a.duty_code='GENERAL_MANAGER' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) ELSE false END;
$$;
COMMENT ON TABLE archive.physical_item IS '独立盒或文件原件位置；电子载体不能生成虚构实物';
COMMENT ON TABLE archive.paper_request_item IS '送审冻结的实际原件与盒件成员清单';
COMMENT ON TABLE archive.physical_loan IS '实际交付才建立占用，逾期和归还待复核仍占用';
COMMENT ON TABLE archive.physical_delivery IS '可等待补批并以原操作号继续的交付命令';
COMMENT ON TABLE archive.physical_return IS '每轮归还申报与实物复核，异常不释放占用';
COMMENT ON TABLE archive.loan_event IS '只追加的交付、位置、归还、异常和续借事件';
COMMENT ON TABLE archive.paper_loan_scope IS '原审批和每次续借关联到同一实际借出，不创建第二份占用';
COMMENT ON TABLE archive.physical_notice IS '纸质到期与逾期站内事项，通知不改变占用和截止时间';
DO $$ DECLARE f record; BEGIN FOR f IN SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='archive' AND table_name IN ('physical_item','paper_request_item','physical_loan','paper_loan_scope','physical_delivery','physical_delivery_loan','physical_return','loan_event','physical_notice') LOOP
 EXECUTE format('COMMENT ON COLUMN archive.%I.%I IS %L',f.table_name,f.column_name,CASE f.column_name WHEN 'id' THEN '稳定业务标识' WHEN 'box_id' THEN '固定所属盒，用于所有竞争请求统一加锁' WHEN 'physical_item_id' THEN '实际原件，不以电子授权代替' WHEN 'state' THEN '受服务端状态迁移约束的状态' WHEN 'members' THEN '送审时固定目录修订及完整成员' WHEN 'actual_delivery_at' THEN '实际交付时间，作为原期限起点' WHEN 'due_at' THEN '固定日历计算的应还截止，不因逾期自动释放' WHEN 'body_snapshot' THEN '不含口令的原交付输入' WHEN 'final_digest' THEN '完成交付后固定的最终输入摘要' WHEN 'scope_digest' THEN '允许待补批时更新实际时间的固定清单摘要' WHEN 'row_version' THEN '并发修改版本' ELSE '纸质借还业务字段：'||f.column_name END);
 END LOOP; END $$;

-- 纸质题名策略使用空PDF的安全相等比较，仍不生成电子正文权。
CREATE OR REPLACE FUNCTION archive.visibility(record_id uuid,person uuid,catalog_allowed boolean) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN catalog_allowed AND EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.user_id=person AND u.status='ACTIVE' AND a.module='ARCHIVE'
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=r.department_id AND a.department_id=u.department_id)) AND a.state='ACTIVE' AND role.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) THEN 'MANAGE'
 WHEN r.state<>'PUBLISHED' OR b.state<>'PUBLISHED' THEN 'HIDDEN'
 WHEN EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.user_id=person AND g.record_id=r.id AND g.state='ACTIVE' AND archive.grant_valid(g.id,person))
 OR EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=r.id AND p.state='ACTIVE' AND archive.baseline_valid(p.id,person)) THEN 'METADATA'
 WHEN r.safe_title IS NOT NULL AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN iam.user_account u ON u.id=person WHERE p.object_type='RECORD' AND p.object_id=r.id AND archive.policy_approved(p.id)
 AND p.record_revision_id=r.current_revision_id AND p.pdf_version_id IS NOT DISTINCT FROM r.current_pdf_version_id AND p.title_visibility IN ('SAFE_TITLE','OWNER_DEPARTMENT') AND (p.title_visibility<>'OWNER_DEPARTMENT' OR u.department_id=r.department_id)
 AND u.department_id=ANY(p.department_scope) AND 'safeTitle'=ANY(p.safe_search_fields)) THEN 'TITLE_ONLY' ELSE 'HIDDEN' END
 FROM archive.record r JOIN archive.box b ON b.id=r.box_id WHERE r.id=record_id;
$$;
