ALTER TABLE portal.meeting_booking
    ADD COLUMN actual_started_at timestamptz,
    ADD COLUMN actual_ended_at timestamptz,
    ADD CONSTRAINT meeting_actual_time_order CHECK (
        actual_ended_at IS NULL OR (actual_started_at IS NOT NULL AND actual_ended_at >= actual_started_at)
    );

COMMENT ON COLUMN portal.meeting_booking.actual_started_at IS '发起人实际开始会议的时刻，也是回复和会前材料截止时刻';
COMMENT ON COLUMN portal.meeting_booking.actual_ended_at IS '发起人实际结束会议的时刻，用于开放纪要填写';

-- 旧版已过预约结束时间的会议沿用原有状态，时间使用旧版状态的计算依据。
UPDATE portal.meeting_booking
SET actual_started_at=starts_at,actual_ended_at=ends_at,
    closed_at=CASE WHEN NOT track_follow_up THEN COALESCE(closed_at,ends_at) ELSE closed_at END
WHERE cancelled_at IS NULL AND ends_at<=CURRENT_TIMESTAMP;
