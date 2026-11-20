CREATE TABLE portal.meeting_material_image (
    attendee_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('REPORT', 'WEEKLY_PLAN')),
    image_key uuid NOT NULL,
    media_type varchar(16) NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg')),
    image_bytes bytea NOT NULL CHECK (octet_length(image_bytes) BETWEEN 1 AND 2097152),
    PRIMARY KEY (attendee_id, kind, image_key),
    FOREIGN KEY (attendee_id, kind) REFERENCES portal.meeting_material(attendee_id, kind) ON DELETE CASCADE
);
COMMENT ON TABLE portal.meeting_material_image IS '会前周报内联图片，随材料保存，访问权限继承对应材料';
