ALTER TABLE portal.meeting_booking ADD COLUMN share_all boolean;

COMMENT ON COLUMN portal.meeting_booking.share_all IS '新会议发起时设置的全程共享开关；为空时沿用历史纪要的逐项权限';
