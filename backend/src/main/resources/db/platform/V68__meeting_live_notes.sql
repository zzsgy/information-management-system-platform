ALTER TABLE portal.meeting_booking ADD COLUMN live_notes text;

COMMENT ON COLUMN portal.meeting_booking.live_notes IS '发起人开会期间保存的会议记录，发布纪要时使用独立文本快照及共享名单';
