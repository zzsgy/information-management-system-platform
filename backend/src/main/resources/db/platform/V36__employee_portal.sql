-- 员工首页独立内容域；不改变公盘、档案或旧系统数据。
CREATE SCHEMA portal;
CREATE TABLE portal.announcement (
 id uuid PRIMARY KEY,title varchar(200) NOT NULL,category varchar(32) NOT NULL,
 body text NOT NULL,state varchar(16) NOT NULL CHECK(state IN ('DRAFT','PUBLISHED','WITHDRAWN')),
 scope varchar(16) NOT NULL CHECK(scope IN ('COMPANY','DEPARTMENTS')),
 valid_from timestamptz NOT NULL,valid_until timestamptz, pinned boolean NOT NULL DEFAULT false,
 publisher_name varchar(128),publisher_department varchar(128),published_at timestamptz,
 created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 row_version integer NOT NULL DEFAULT 1,CHECK(valid_until IS NULL OR valid_until>valid_from)
);
CREATE TABLE portal.announcement_department (
 announcement_id uuid NOT NULL REFERENCES portal.announcement(id),department_id uuid NOT NULL REFERENCES iam.department(id),
 PRIMARY KEY(announcement_id,department_id)
);
CREATE INDEX announcement_department_lookup ON portal.announcement_department(department_id,announcement_id);
CREATE INDEX announcement_published ON portal.announcement(pinned DESC,published_at DESC,id) WHERE state='PUBLISHED';
CREATE TABLE portal.announcement_revision (
 announcement_id uuid NOT NULL REFERENCES portal.announcement(id),version integer NOT NULL,
 snapshot jsonb NOT NULL,changed_by uuid NOT NULL REFERENCES iam.user_account(id),changed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(announcement_id,version)
);
CREATE TABLE portal.site (
 id uuid PRIMARY KEY,name varchar(80) NOT NULL,url varchar(2000) NOT NULL,sort_order integer NOT NULL DEFAULT 0,
 enabled boolean NOT NULL DEFAULT true,row_version integer NOT NULL DEFAULT 1
);
INSERT INTO portal.site(id,name,url) VALUES('00000000-0000-0000-0000-000000003601','百林客','https://www.biolink.city/');
CREATE TABLE portal.feedback (
 id uuid PRIMARY KEY,applicant_id uuid NOT NULL REFERENCES iam.user_account(id),category varchar(16) NOT NULL,
 content varchar(1000) NOT NULL,state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','PROCESSING','RESOLVED')),
 operation_id uuid NOT NULL,request_digest varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 row_version integer NOT NULL DEFAULT 1,UNIQUE(applicant_id,operation_id)
);
CREATE INDEX feedback_applicant ON portal.feedback(applicant_id,created_at DESC,id);
CREATE INDEX feedback_pending ON portal.feedback(created_at,id) WHERE state<>'RESOLVED';
CREATE TABLE portal.feedback_reply (
 id uuid PRIMARY KEY,feedback_id uuid NOT NULL REFERENCES portal.feedback(id),body varchar(2000) NOT NULL,
 author_id uuid NOT NULL REFERENCES iam.user_account(id),author_name varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,read_at timestamptz
);
CREATE INDEX feedback_reply_lookup ON portal.feedback_reply(feedback_id,created_at,id);

COMMENT ON TABLE portal.announcement IS '可维护公告；部门范围和展示有效期每次读取即时核验';
COMMENT ON COLUMN portal.announcement.body IS '纯文本正文，上限10000字符；禁止执行富文本脚本';
COMMENT ON COLUMN portal.announcement.published_at IS '首次发布时刻；修改保留首次发布信息，修订另存';
COMMENT ON COLUMN portal.announcement.publisher_name IS '实际首次发布者姓名快照';
COMMENT ON COLUMN portal.announcement.publisher_department IS '首次发布者所属部门名称快照';
COMMENT ON COLUMN portal.announcement.valid_until IS '展示截止时刻，不含此时刻；空表示不自动撤下';
COMMENT ON COLUMN portal.announcement.scope IS '全公司或精确指定部门，不隐式包含子部门';
COMMENT ON TABLE portal.announcement_revision IS '每次管理操作留存正文及范围快照，用于追溯';
COMMENT ON TABLE portal.site IS 'IT维护的常用网站；只允许HTTP和HTTPS链接，不在服务端抓取';
COMMENT ON COLUMN portal.site.sort_order IS '升序展示，相同权重按稳定标识排序';
COMMENT ON TABLE portal.feedback IS '员工意见反馈；本人读取，IT办理，操作号防重复提交';
COMMENT ON COLUMN portal.feedback.request_digest IS '规范化请求摘要，防止同一操作号替换内容';
COMMENT ON TABLE portal.feedback_reply IS 'IT答复历史，也是员工站内消息来源；已读不改变办理状态';
COMMENT ON COLUMN portal.feedback_reply.read_at IS '申请人首次阅读答复的时刻';

INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type) VALUES
 ('00000000-0000-0000-0000-000000003611','PLATFORM','PLATFORM.ANNOUNCEMENTS_MANAGE','公告维护','PORTAL_ANNOUNCEMENT','ANNOUNCEMENTS_MANAGE',true,'MODULE'),
 ('00000000-0000-0000-0000-000000003612','PLATFORM','PLATFORM.SITES_MANAGE','常用网站维护','PORTAL_SITE','SITES_MANAGE',true,'MODULE'),
 ('00000000-0000-0000-0000-000000003613','PLATFORM','PLATFORM.FEEDBACK_MANAGE','意见反馈办理','PORTAL_FEEDBACK','FEEDBACK_MANAGE',true,'MODULE');
-- 已部署库在迁移中补角色；空库由现有初始化流程在首位账号建立后赋权。
INSERT INTO iam.role(id,module,code,name)
 SELECT '00000000-0000-0000-0000-000000003610','PLATFORM','CONTENT_EDITOR','内容维护人员'
 WHERE EXISTS(SELECT 1 FROM iam.user_account);
INSERT INTO iam.role_permission(role_id,permission_id,effect,scope_policy,changed_by)
 SELECT r.id,p.id,'ALLOW','COMPANY',(SELECT id FROM iam.user_account ORDER BY created_at,id LIMIT 1)
 FROM iam.role r JOIN iam.permission p ON p.code IN ('PLATFORM.ANNOUNCEMENTS_MANAGE','PLATFORM.SITES_MANAGE','PLATFORM.FEEDBACK_MANAGE')
 WHERE r.module='PLATFORM' AND (r.code='IT_ADMIN' OR (r.code='CONTENT_EDITOR' AND p.code='PLATFORM.ANNOUNCEMENTS_MANAGE'));
