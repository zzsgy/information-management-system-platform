CREATE TABLE portal.meeting_weekly_excel (
    meeting_id uuid NOT NULL,
    department_id uuid NOT NULL,
    workbook bytea NOT NULL,
    saved_at timestamptz,
    revision integer NOT NULL DEFAULT 1,
    PRIMARY KEY (meeting_id, department_id),
    FOREIGN KEY (meeting_id, department_id) REFERENCES portal.meeting_weekly_department(meeting_id, department_id)
);

CREATE TABLE portal.meeting_weekly_excel_session (
    id uuid PRIMARY KEY,
    meeting_id uuid NOT NULL,
    department_id uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('EDITING','READING','CLOSED','FAILED')),
    base_revision integer NOT NULL,
    submission_version integer,
    message text,
    expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP + interval '2 hours',
    FOREIGN KEY (meeting_id, department_id) REFERENCES portal.meeting_weekly_excel(meeting_id, department_id)
);
CREATE UNIQUE INDEX meeting_weekly_excel_active ON portal.meeting_weekly_excel_session(meeting_id, department_id)
    WHERE state='EDITING';

CREATE TABLE portal.meeting_weekly_excel_participant (
    session_id uuid NOT NULL REFERENCES portal.meeting_weekly_excel_session(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES iam.user_account(id),
    login_session_id uuid NOT NULL REFERENCES iam.session(id),
    PRIMARY KEY (session_id,user_id)
);

ALTER TABLE portal.meeting_weekly_submission ADD COLUMN workbook bytea;

COMMENT ON TABLE portal.meeting_weekly_excel IS '本场周会本部门原生 XLSX 草稿，文档服务最终保存后更新';
COMMENT ON TABLE portal.meeting_weekly_excel_session IS '部门 Excel 的协作批次及阅览票据，固定来源版本';
COMMENT ON TABLE portal.meeting_weekly_excel_participant IS '原生编辑或阅览必须绑定当前登录和受邀范围';
COMMENT ON COLUMN portal.meeting_weekly_submission.workbook IS '提交时经校验的原始 XLSX，保留原格式和截图';
