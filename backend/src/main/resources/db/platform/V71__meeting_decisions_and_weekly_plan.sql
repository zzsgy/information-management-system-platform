-- 会中决议独立于既有会议记录，保留历史内容。
ALTER TABLE portal.meeting_booking ADD COLUMN live_decisions text;
COMMENT ON COLUMN portal.meeting_booking.live_decisions IS '发起人在会议进行时保存的决议，带入纪要底稿';

ALTER TABLE portal.meeting_material DROP CONSTRAINT meeting_material_kind_check;
ALTER TABLE portal.meeting_material ADD CONSTRAINT meeting_material_kind_check
    CHECK (kind IN ('REPORT','WEEKLY_PLAN','TOPICS','COORDINATION'));
