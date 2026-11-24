ALTER TABLE portal.announcement
    ADD COLUMN content_format varchar(16) NOT NULL DEFAULT 'LEGACY' CHECK (content_format IN ('LEGACY', 'MARKDOWN')),
    ADD COLUMN image_keys uuid[] NOT NULL DEFAULT '{}' CHECK (cardinality(image_keys) <= 3);
COMMENT ON COLUMN portal.announcement.content_format IS '旧公告沿用原正文格式，新编辑器保存 Markdown 源文';
COMMENT ON COLUMN portal.announcement.image_keys IS '当前正文中的图片标识，随公告版本记录留存';

CREATE TABLE portal.announcement_image (
    announcement_id uuid NOT NULL REFERENCES portal.announcement(id) ON DELETE CASCADE,
    image_key uuid NOT NULL,
    media_type varchar(16) NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg')),
    image_bytes bytea NOT NULL CHECK (octet_length(image_bytes) BETWEEN 1 AND 2097152),
    PRIMARY KEY (announcement_id, image_key)
);
COMMENT ON TABLE portal.announcement_image IS '公告各版本的内联图片，当前正文只公开 image_keys 引用的图片';
COMMENT ON COLUMN portal.announcement_read.read_at IS '员工首次确认已读的时间；启用明确确认前的既有阅读时间保留';
