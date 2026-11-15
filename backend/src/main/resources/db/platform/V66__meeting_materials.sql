-- NULL 保留历史会议的原征集规则，新会议保存明确选择的材料类型。
ALTER TABLE portal.meeting_booking ADD COLUMN material_types text[];
CREATE TABLE portal.meeting_material (
    attendee_id uuid NOT NULL REFERENCES portal.meeting_attendee(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('REPORT','TOPICS','COORDINATION')),
    payload jsonb NOT NULL,
    submitted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(attendee_id, kind)
);
COMMENT ON TABLE portal.meeting_material IS '按受邀员工和材料类型保存会前提交与提交时的员工资料';

