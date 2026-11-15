ALTER TABLE portal.todo_desktop ADD COLUMN enabled boolean;
UPDATE portal.todo_desktop SET enabled=true WHERE last_poll_at IS NOT NULL;
COMMENT ON COLUMN portal.todo_desktop.enabled IS '桌面提醒偏好；NULL 等待助手首次连接，false 保留配对并使用网页提醒';
