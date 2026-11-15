-- 每名员工每条公告只记录一次首次查看，不改动现有公告。
CREATE TABLE portal.announcement_read (
 user_id uuid NOT NULL REFERENCES iam.user_account(id),
 announcement_id uuid NOT NULL REFERENCES portal.announcement(id),
 read_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id, announcement_id)
);
COMMENT ON TABLE portal.announcement_read IS '员工本人查看过的公告索引';
COMMENT ON COLUMN portal.announcement_read.read_at IS '员工首次打开公告正文的时刻';
