ALTER TABLE portal.meeting_attendee ADD COLUMN material_only boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN portal.meeting_attendee.material_only IS '仅作为补录材料的归属人，不构成会议邀请、占用或详情权限';
