ALTER TABLE portal.meeting_item ADD COLUMN group_id uuid;
UPDATE portal.meeting_item SET group_id=id;
ALTER TABLE portal.meeting_item ALTER COLUMN group_id SET NOT NULL;
ALTER TABLE portal.meeting_item ADD COLUMN removed_at timestamptz;

DROP INDEX portal.meeting_item_coordination_key;
CREATE UNIQUE INDEX meeting_item_coordination_tracker
    ON portal.meeting_item (meeting_id, coordination_key, assignee_id)
    WHERE coordination_key IS NOT NULL AND removed_at IS NULL;
CREATE UNIQUE INDEX meeting_item_group_tracker
    ON portal.meeting_item (group_id, assignee_id) WHERE removed_at IS NULL;

COMMENT ON TABLE portal.meeting_item IS '纪要待办的个人跟踪记录，同一事项共用分组标识并独立反馈';
COMMENT ON COLUMN portal.meeting_item.group_id IS '同一会后事项的分组标识，原单人待办以原记录 ID 分组';
COMMENT ON COLUMN portal.meeting_item.removed_at IS '取消跟踪人员的时间，保留已提交的反馈历史';
