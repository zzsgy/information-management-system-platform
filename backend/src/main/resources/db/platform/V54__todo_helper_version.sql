ALTER TABLE portal.todo_desktop ADD COLUMN client_version varchar(15);
COMMENT ON COLUMN portal.todo_desktop.client_version IS '最近一次连接的助手版本，旧版未上报时为空';
