-- 保留历史富文本的格式标记，新反馈和答复可独立使用 Markdown。
ALTER TABLE portal.feedback ADD COLUMN content_format varchar(16) NOT NULL DEFAULT 'LEGACY'
    CHECK (content_format IN ('LEGACY', 'MARKDOWN'));
ALTER TABLE portal.feedback_reply ADD COLUMN content_format varchar(16) NOT NULL DEFAULT 'LEGACY'
    CHECK (content_format IN ('LEGACY', 'MARKDOWN'));

CREATE TABLE portal.feedback_image (
    feedback_id uuid NOT NULL REFERENCES portal.feedback(id),
    reply_id uuid REFERENCES portal.feedback_reply(id),
    image_key uuid NOT NULL,
    media_type varchar(16) NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg')),
    image_bytes bytea NOT NULL CHECK (octet_length(image_bytes) BETWEEN 1 AND 2097152),
    UNIQUE NULLS NOT DISTINCT (feedback_id, reply_id, image_key)
);
COMMENT ON TABLE portal.feedback_image IS '随反馈或答复一起提交的截图，读取权限继承反馈';
COMMENT ON COLUMN portal.feedback_image.reply_id IS '为空时截图属于反馈正文';
COMMENT ON COLUMN portal.feedback_image.image_key IS 'Markdown 中引用的消息内截图标识';
COMMENT ON COLUMN portal.feedback_image.image_bytes IS '经解码和重新编码的截图，单张上限 2 MiB';
