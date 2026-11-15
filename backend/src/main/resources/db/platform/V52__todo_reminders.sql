ALTER TABLE portal.employee_todo
 ADD COLUMN remind_at timestamptz,
 ADD COLUMN reminder_seen_at timestamptz;
COMMENT ON COLUMN portal.employee_todo.remind_at IS '接收人可选的提醒时刻';
COMMENT ON COLUMN portal.employee_todo.reminder_seen_at IS '接收人确认当前提醒的时刻，独立于待办办结';
CREATE INDEX employee_todo_due_reminders ON portal.employee_todo(assignee_id, remind_at, id)
 WHERE state='OPEN' AND remind_at IS NOT NULL AND reminder_seen_at IS NULL;

-- 每个账号可独立配对一台托盘助手，数据库仅保存配对凭据的散列。
CREATE TABLE portal.todo_desktop (
 user_id uuid PRIMARY KEY REFERENCES iam.user_account(id),
 token_hash char(64) NOT NULL UNIQUE,
 auth_version integer NOT NULL,
 last_poll_at timestamptz
);
COMMENT ON COLUMN portal.todo_desktop.last_poll_at IS '最近一次桌面轮询，用于将提醒优先交给在线助手';
