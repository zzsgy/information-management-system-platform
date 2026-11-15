-- 补齐首页内容域字段字典，不改变公告、反馈或账号状态。
DO $$ DECLARE field record; descriptions jsonb := '{
 "id":"稳定业务标识","title":"公告标题，最多200字符","category":"受控公告或反馈分类",
 "body":"公告纯文本正文或IT答复；按入口分别限制长度","state":"公告发布或反馈办理状态，不等同于权限",
 "scope":"公司或精确部门范围","valid_from":"展示开始时刻，含此时刻","valid_until":"展示截止时刻，不含此时刻，空表示不设截止",
 "pinned":"是否置顶；仍受展示范围和有效期限制","publisher_name":"实际首次发布者姓名快照","publisher_department":"首次发布者所属部门名称快照",
 "published_at":"首次发布时刻，后续编辑另存修订","created_by":"创建员工标识","created_at":"服务端首次写入时刻",
 "row_version":"并发更新版本，管理提交必须匹配","announcement_id":"关联的稳定公告标识","department_id":"公告可见的精确部门标识",
 "version":"对应公告修订版本","snapshot":"该版本完整正文、范围和发布信息快照","changed_by":"本次修订操作者","changed_at":"修订提交时刻",
 "name":"常用网站显示名称","url":"完整HTTP或HTTPS地址，不含登录凭据","sort_order":"显示顺序升序权重","enabled":"是否向员工展示",
 "applicant_id":"反馈申请人，只能读取本人记录","content":"员工反馈正文，最多1000字符","operation_id":"同一员工重试复用的操作号",
 "request_digest":"规范化请求摘要，防止同一操作号替换内容","feedback_id":"答复关联的反馈标识","author_id":"IT答复员工标识",
 "author_name":"答复时实际姓名快照","read_at":"申请人首次确认阅读答复的时刻"}'::jsonb;
BEGIN
 FOR field IN SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='portal' LOOP
  IF descriptions ? field.column_name THEN EXECUTE format('COMMENT ON COLUMN portal.%I.%I IS %L',field.table_name,field.column_name,descriptions->>field.column_name); END IF;
 END LOOP;
END $$;
