-- 个人待办及员工分派事项独立于系统业务审批。
CREATE TABLE portal.employee_todo (
 id uuid PRIMARY KEY,
 creator_id uuid NOT NULL REFERENCES iam.user_account(id),
 assignee_id uuid NOT NULL REFERENCES iam.user_account(id),
 title varchar(160) NOT NULL,
 description varchar(2000) NOT NULL DEFAULT '',
 state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','SUBMITTED','COMPLETED','DELETED')),
 completion_note varchar(2000) NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 submitted_at timestamptz,
 completed_at timestamptz,
 row_version integer NOT NULL DEFAULT 1,
 operation_id uuid NOT NULL,
 request_digest varchar(64) NOT NULL,
 UNIQUE(creator_id,operation_id)
);
CREATE INDEX ix_employee_todo_assignee ON portal.employee_todo(assignee_id,state,id);
CREATE INDEX ix_employee_todo_creator ON portal.employee_todo(creator_id,state,id);
COMMENT ON TABLE portal.employee_todo IS '员工待办：本人创建本人负责为个人待办，分派事项由原分派人确认';
COMMENT ON COLUMN portal.employee_todo.completion_note IS '接收人提交的完成说明';
