-- 初借末级批准即创建占用；旧借出记录仍按原交付与归还流程办理。
ALTER TABLE archive.physical_loan
  ADD COLUMN reserved_at timestamptz,
  ADD COLUMN pickup_by uuid REFERENCES iam.user_account(id),
  ADD COLUMN pickup_role varchar(16) CHECK(pickup_role IN ('ARCHIVIST','ARCHIVE_ADMIN')),
  ADD COLUMN pickup_at timestamptz,
  ALTER COLUMN delivered_by DROP NOT NULL,
  ALTER COLUMN delivery_receipt_id DROP NOT NULL,
  ALTER COLUMN actual_delivery_at DROP NOT NULL;
COMMENT ON COLUMN archive.physical_loan.reserved_at IS '新流程的末级批准及占用起点；空值沿用旧流程';
COMMENT ON COLUMN archive.physical_loan.pickup_by IS '同一次实际交付中先确认的经办人';
COMMENT ON COLUMN archive.physical_loan.pickup_role IS '先确认人本次承担的档案员或档案超级管理员职责';
COMMENT ON COLUMN archive.physical_loan.pickup_at IS '先确认人登记的实际交付时刻，第二人须确认同一记录';

ALTER TABLE archive.physical_return
  ADD COLUMN workflow_instance_id uuid UNIQUE REFERENCES workflow.instance(id),
  ADD COLUMN registered_by uuid REFERENCES iam.user_account(id),
  ADD COLUMN registration jsonb;
COMMENT ON COLUMN archive.physical_return.workflow_instance_id IS '新流程归还审批，空值为旧流程直接实收';
COMMENT ON COLUMN archive.physical_return.registered_by IS '归还获批后登记交接的档案所属部门档案员';
COMMENT ON COLUMN archive.physical_return.registration IS '实际收回或未取件的登记内容，由另一位档案超级管理员确认';

ALTER TABLE workflow.instance DROP CONSTRAINT instance_business_type_check;
ALTER TABLE workflow.instance ADD CHECK(business_type IN ('REGISTRATION','BORROW','ACCESS_POLICY','CATALOG_CHANGE','PHYSICAL_RETURN'));
INSERT INTO workflow.template_version(id,template_key,business_type,version_no,route_kind,nodes_definition,checksum)
SELECT '30000000-0000-4000-8000-000000000059','ARCHIVE_PHYSICAL_RETURN_V1','PHYSICAL_RETURN',1,'FIXED',definition,
       encode(sha256(convert_to(definition::text,'UTF8')),'hex')
FROM (SELECT '{"local":["APPLICANT_MANAGER","APPLICANT_ARCHIVIST","ARCHIVE_ADMIN"],"cross":["APPLICANT_MANAGER","APPLICANT_ARCHIVIST","OWNER_MANAGER","OWNER_ARCHIVIST","ARCHIVE_ADMIN"]}'::jsonb AS definition) route;

-- 到期任务只对借阅人、两边的有效部门职责和有效档案超管可见。
CREATE FUNCTION archive.record_paper_occupied(record uuid, box uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM archive.physical_item original
  JOIN archive.physical_loan l ON l.box_id=original.box_id AND l.state='IN_USE'
  JOIN archive.physical_item occupied ON occupied.id=l.physical_item_id
  WHERE original.record_id=record AND original.box_id=box
    AND (occupied.object_type='BOX' OR occupied.id=original.id)
);
$$;

CREATE FUNCTION archive.paper_due_recipient(loan uuid, person uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM archive.physical_loan l
  JOIN archive.borrow_request q ON q.id=l.request_id
  JOIN archive.borrow_batch b ON b.id=q.batch_id
  WHERE l.id=loan AND l.reserved_at IS NOT NULL AND l.state='IN_USE' AND l.due_at<=CURRENT_TIMESTAMP
    AND iam.archive_access_enabled(person)
    AND (l.borrower_id=person OR EXISTS (
      SELECT 1 FROM iam.department_manager a
      WHERE a.user_id=person AND a.duty_type='DEPARTMENT_MANAGER'
        AND a.department_id IN (b.applicant_department_id,q.owner_department_id)
        AND workflow.assignment_valid('DEPARTMENT',a.id,person)
    ) OR EXISTS (
      SELECT 1 FROM iam.user_role a WHERE a.user_id=person AND a.module='ARCHIVE'
        AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST'
          AND a.department_id IN (b.applicant_department_id,q.owner_department_id)))
        AND workflow.assignment_valid('ROLE',a.id,person)
    ))
);
$$;
