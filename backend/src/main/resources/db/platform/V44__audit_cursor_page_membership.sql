-- 仅扩展十分钟查询票据的分页元数据，不改写审计事实、范围快照或历史时间。
ALTER TABLE ops.audit_page_cursor ADD COLUMN page_ids uuid[],ADD COLUMN next_cursor_id uuid;
COMMENT ON COLUMN ops.audit_page_cursor.page_ids IS '首次读取时固定的有权记录编号；NULL为未读取，空数组为已读取的空页，每次返回仍重新授权';
COMMENT ON COLUMN ops.audit_page_cursor.next_cursor_id IS '同一查询内下一页的随机游标；随原票据到期回收，不对外接受修改';
