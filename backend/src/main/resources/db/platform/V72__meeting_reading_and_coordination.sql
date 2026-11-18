ALTER TABLE portal.meeting_attendee ADD COLUMN read_material_revision text;
ALTER TABLE portal.meeting_attendee ADD COLUMN read_material_at timestamptz;
COMMENT ON COLUMN portal.meeting_attendee.read_material_revision IS '本人确认的其他员工会前资料版本';
COMMENT ON COLUMN portal.meeting_attendee.read_material_at IS '本人对该版本确认已阅读的时间';

ALTER TABLE portal.meeting_booking ADD COLUMN coordination_results jsonb NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN portal.meeting_booking.coordination_results IS '会中逐条保存的协调事项结论、责任人与会后跟踪决定';
