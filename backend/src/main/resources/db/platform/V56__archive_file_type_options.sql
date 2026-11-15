-- 新登记使用独立的文件类型选项。历史台账保留登记时的文字。
CREATE TABLE archive.file_type_option (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL UNIQUE CHECK (btrim(name) <> ''),
    sort_order bigint GENERATED ALWAYS AS IDENTITY
);
COMMENT ON TABLE archive.file_type_option IS '档案盒新登记的文件类型选项，由平台 IT 管理员追加';
COMMENT ON COLUMN archive.file_type_option.sort_order IS '登记下拉选项的添加顺序';

INSERT INTO archive.file_type_option(name) VALUES
    ('合同'), ('行政文件'), ('台账'), ('通知'), ('会议纪要'),
    ('人事档案'), ('项目建设资料'), ('质量文件'), ('其他');
