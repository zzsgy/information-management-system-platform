-- 按离职主档、实物借阅和原审批人保存跟进凭据；实收状态沿用原借阅单。
CREATE TABLE archive.departure_loan_followup (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    departure_id uuid NOT NULL REFERENCES iam.employee_departure(id),
    loan_id uuid NOT NULL REFERENCES archive.physical_loan(id),
    assignee_id uuid NOT NULL REFERENCES iam.user_account(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at timestamptz,
    UNIQUE (departure_id, loan_id, assignee_id)
);
CREATE INDEX ix_departure_loan_followup_assignee ON archive.departure_loan_followup(assignee_id, created_at DESC);
CREATE INDEX ix_paper_loan_scope_loan ON archive.paper_loan_scope(loan_id, request_id);
COMMENT ON TABLE archive.departure_loan_followup IS '离职未还纸质档案的逐人跟进待办和站内通知';
COMMENT ON COLUMN archive.departure_loan_followup.id IS '待办和站内通知共用编号';
COMMENT ON COLUMN archive.departure_loan_followup.departure_id IS '离职办理主档';
COMMENT ON COLUMN archive.departure_loan_followup.loan_id IS '原始实物借阅单';
COMMENT ON COLUMN archive.departure_loan_followup.assignee_id IS '有实际批准记录的原审批人';
COMMENT ON COLUMN archive.departure_loan_followup.created_at IS '跟进通知生成时间';
COMMENT ON COLUMN archive.departure_loan_followup.read_at IS '本人首次查看时间';
