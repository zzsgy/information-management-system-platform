ALTER TABLE portal.meeting_item ADD COLUMN coordination_key varchar(48);
CREATE UNIQUE INDEX meeting_item_coordination_key
    ON portal.meeting_item (meeting_id, coordination_key) WHERE coordination_key IS NOT NULL;
COMMENT ON COLUMN portal.meeting_item.coordination_key IS '生成该待办的协调事项行标识；手工待办为空';

-- 旧会议只回填能唯一对应的待办，避免把同名手工待办错误关联到协调事项。
WITH matches AS (
    SELECT i.id, result->>'key' AS coordination_key,
           count(*) OVER (PARTITION BY i.id) AS matching_results,
           count(*) OVER (PARTITION BY m.id, result->>'key') AS matching_items
    FROM portal.meeting_booking m
    CROSS JOIN LATERAL jsonb_array_elements(m.coordination_results) result
    JOIN portal.meeting_item i ON i.meeting_id=m.id
        AND i.assignee_id::text=result->>'assigneeId'
        AND i.due_at=(result->>'dueAt')::timestamptz
        AND i.content=concat(result->>'subject', E'\n', result->>'conclusion')
    WHERE m.minutes_published_at IS NOT NULL AND result->>'followUp'='true'
)
UPDATE portal.meeting_item i SET coordination_key=m.coordination_key
FROM matches m
WHERE i.id=m.id AND m.matching_results=1 AND m.matching_items=1;
