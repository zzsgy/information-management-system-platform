-- 自填地点保存在会议说明，只有具名配置地点参与会议室占用。
ALTER TABLE portal.meeting_booking ALTER COLUMN room_id DROP NOT NULL;
ALTER TABLE portal.meeting_booking ADD CONSTRAINT meeting_location_required
    CHECK (room_id IS NOT NULL OR NULLIF(btrim(description), '') IS NOT NULL);
COMMENT ON COLUMN portal.meeting_booking.room_id IS '为空时使用会议说明中的其他地点，不占用配置会议室';
INSERT INTO portal.meeting_room(id,name) VALUES
    ('00000000-0000-0000-0000-000000006401','研发楼2楼党建活动室'),
    ('00000000-0000-0000-0000-000000006402','研发楼2楼大会议室'),
    ('00000000-0000-0000-0000-000000006403','研发楼4楼培训室')
ON CONFLICT (name) DO NOTHING;
