ALTER TABLE portal.meeting_booking
    ADD COLUMN online_weekly_report boolean NOT NULL DEFAULT false;
ALTER TABLE portal.meeting_attendee
    ADD COLUMN weekly_department_id uuid REFERENCES iam.department(id);

CREATE TABLE portal.meeting_weekly_department (
    meeting_id uuid NOT NULL REFERENCES portal.meeting_booking(id),
    department_id uuid NOT NULL REFERENCES iam.department(id),
    name text NOT NULL,
    submitted_version integer,
    extracted_version integer,
    PRIMARY KEY (meeting_id, department_id)
);

CREATE TABLE portal.meeting_weekly_row (
    id uuid PRIMARY KEY,
    meeting_id uuid NOT NULL,
    department_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('REPORT','WEEKLY_PLAN','COORDINATION')),
    author_id uuid NOT NULL REFERENCES portal.meeting_attendee(id),
    author_name text NOT NULL,
    employee_no text NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version integer NOT NULL DEFAULT 1,
    deleted boolean NOT NULL DEFAULT false,
    data jsonb NOT NULL,
    FOREIGN KEY (meeting_id, department_id) REFERENCES portal.meeting_weekly_department(meeting_id, department_id)
);
CREATE INDEX meeting_weekly_row_sheet ON portal.meeting_weekly_row(meeting_id, department_id, created_at, id)
    WHERE NOT deleted;

CREATE TABLE portal.meeting_weekly_image (
    row_id uuid NOT NULL REFERENCES portal.meeting_weekly_row(id),
    image_key uuid NOT NULL,
    media_type varchar(16) NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg')),
    image_bytes bytea NOT NULL CHECK (octet_length(image_bytes) BETWEEN 1 AND 2097152),
    PRIMARY KEY (row_id, image_key)
);

CREATE TABLE portal.meeting_weekly_submission (
    meeting_id uuid NOT NULL,
    department_id uuid NOT NULL,
    version integer NOT NULL,
    submitted_by text NOT NULL,
    submitted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rows jsonb NOT NULL,
    PRIMARY KEY (meeting_id, department_id, version),
    FOREIGN KEY (meeting_id, department_id) REFERENCES portal.meeting_weekly_department(meeting_id, department_id)
);

COMMENT ON COLUMN portal.meeting_booking.online_weekly_report IS '启用部门协作周报，由发起人提取为会前材料';
COMMENT ON COLUMN portal.meeting_attendee.weekly_department_id IS '邀请时归属的周报部门，保留历史及子部门边界';
COMMENT ON TABLE portal.meeting_weekly_department IS '每场周会每个受邀部门一张表，提交及提取版本独立记录';
COMMENT ON TABLE portal.meeting_weekly_row IS '协作草稿行，保留原填报人、最后修改人及并发版本';
COMMENT ON TABLE portal.meeting_weekly_image IS '周报草稿及已提交版本引用的截图';
COMMENT ON TABLE portal.meeting_weekly_submission IS '部门负责人提交的不可变周报版本，重开和提取不会删除历史';
