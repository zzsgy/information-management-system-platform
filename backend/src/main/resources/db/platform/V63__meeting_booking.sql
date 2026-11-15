-- 会议使用工作台数据域，正式会议室由管理员按实际清单维护。
CREATE TABLE portal.meeting_room (
    id uuid PRIMARY KEY,
    name text NOT NULL UNIQUE CHECK (btrim(name) <> ''),
    enabled boolean NOT NULL DEFAULT true
);
CREATE TABLE portal.meeting_booking (
    id uuid PRIMARY KEY,
    organizer_id uuid NOT NULL REFERENCES iam.user_account(id),
    room_id uuid NOT NULL REFERENCES portal.meeting_room(id),
    kind text NOT NULL CHECK (kind IN ('ORDINARY', 'WEEKLY')),
    subject text NOT NULL CHECK (btrim(subject) <> ''),
    description text,
    urgent boolean NOT NULL,
    collect_materials boolean NOT NULL,
    track_follow_up boolean NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL CHECK (ends_at > starts_at),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at timestamptz,
    cancel_reason text,
    minutes jsonb,
    minutes_published_at timestamptz,
    closed_at timestamptz,
    row_version integer NOT NULL DEFAULT 1,
    CHECK (kind <> 'WEEKLY' OR (collect_materials AND track_follow_up)),
    CHECK ((cancelled_at IS NULL) = (cancel_reason IS NULL))
);
CREATE INDEX meeting_room_time ON portal.meeting_booking (room_id, starts_at, ends_at) WHERE cancelled_at IS NULL;
CREATE INDEX meeting_organizer_time ON portal.meeting_booking (organizer_id, starts_at, ends_at) WHERE cancelled_at IS NULL;
CREATE TABLE portal.meeting_attendee (
    id uuid PRIMARY KEY,
    meeting_id uuid NOT NULL REFERENCES portal.meeting_booking(id),
    user_id uuid NOT NULL REFERENCES iam.user_account(id),
    reply text NOT NULL DEFAULT 'PENDING' CHECK (reply IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    decline_reason text,
    material_required boolean NOT NULL,
    report text,
    topics text,
    invited_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replied_at timestamptz,
    material_at timestamptz,
    removed_at timestamptz,
    UNIQUE (meeting_id, user_id),
    CHECK ((reply = 'DECLINED') = (decline_reason IS NOT NULL))
);
CREATE INDEX meeting_attendee_user ON portal.meeting_attendee (user_id, meeting_id) WHERE removed_at IS NULL;
CREATE TABLE portal.meeting_item (
    id uuid PRIMARY KEY,
    meeting_id uuid NOT NULL REFERENCES portal.meeting_booking(id),
    assignee_id uuid NOT NULL REFERENCES iam.user_account(id),
    content text NOT NULL CHECK (btrim(content) <> ''),
    due_at timestamptz NOT NULL,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SUBMITTED', 'RETURNED', 'CONFIRMED')),
    history jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at timestamptz
);
CREATE INDEX meeting_item_assignee ON portal.meeting_item (assignee_id, state, due_at);
CREATE INDEX meeting_item_booking ON portal.meeting_item (meeting_id);
CREATE TABLE portal.meeting_message (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES iam.user_account(id),
    meeting_id uuid NOT NULL REFERENCES portal.meeting_booking(id),
    item_id uuid REFERENCES portal.meeting_item(id),
    kind text NOT NULL,
    content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at timestamptz
);
CREATE INDEX meeting_message_recipient ON portal.meeting_message (user_id, created_at DESC);

COMMENT ON TABLE portal.meeting_room IS '会议地点选项，停用后保留历史引用';
COMMENT ON TABLE portal.meeting_booking IS '预约及纪要根记录，改期保留原会议标识';
COMMENT ON COLUMN portal.meeting_booking.minutes IS '底稿、总结、决议及发布前待办草稿';
COMMENT ON COLUMN portal.meeting_booking.closed_at IS '发起人确认办结时刻；普通会议自动办结由预定结束时刻计算';
COMMENT ON TABLE portal.meeting_attendee IS '受邀关系、个人回复和原始会前材料';
COMMENT ON COLUMN portal.meeting_attendee.material_required IS '发起时确定的部门经理或普通会议指定人员';
COMMENT ON COLUMN portal.meeting_attendee.removed_at IS '移除后终止原邀请权限和待办';
COMMENT ON TABLE portal.meeting_item IS '纪要发布时产生的反馈事项，各有一名责任人及完成期限';
COMMENT ON COLUMN portal.meeting_item.history IS '按提交顺序保存每次反馈、退回原因和确认';
COMMENT ON TABLE portal.meeting_message IS '会议站内消息，阅读不改变会议办理状态';

INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type) VALUES
    ('00000000-0000-0000-0000-000000006311','PLATFORM','PLATFORM.MEETING_WEEKLY','发起周会','MEETING','MEETING_WEEKLY',true,'MODULE'),
    ('00000000-0000-0000-0000-000000006312','PLATFORM','PLATFORM.MEETING_URGENT','标记紧急会议','MEETING','MEETING_URGENT',true,'MODULE');

-- 已有平台由迁移复用现有角色管理入口。空库在首次初始化时设置同样的角色及权限。
INSERT INTO iam.role(id,module,code,name)
SELECT v.id::uuid,'PLATFORM',v.code,v.name
FROM (VALUES ('00000000-0000-0000-0000-000000006321','MEETING_ORGANIZER','周会组织人员'),
             ('00000000-0000-0000-0000-000000006322','MEETING_URGENT','紧急会议发起人员')) v(id,code,name)
WHERE EXISTS (SELECT 1 FROM iam.user_account);

INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by)
SELECT r.id,p.id,'COMPANY','ALLOW',(SELECT id FROM iam.user_account ORDER BY created_at,id LIMIT 1)
FROM iam.role r JOIN iam.permission p ON p.module=r.module
WHERE r.module='PLATFORM' AND
    ((r.code='MEETING_ORGANIZER' AND p.action='MEETING_WEEKLY') OR
     (r.code='MEETING_URGENT' AND p.action='MEETING_URGENT'));

