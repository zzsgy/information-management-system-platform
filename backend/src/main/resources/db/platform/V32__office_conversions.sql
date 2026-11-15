-- 转换结果按不可变来源版本和文档服务版本复用；原件不因转换而被覆盖。
CREATE TABLE drive.office_conversion (
 id uuid PRIMARY KEY,document_id uuid NOT NULL REFERENCES drive.document(id),source_version_id uuid NOT NULL REFERENCES drive.document_version(id),
 input_type varchar(8) NOT NULL,output_type varchar(8) NOT NULL,converter_version varchar(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('PENDING','READY','REJECTED')),
 requester_id uuid NOT NULL REFERENCES iam.user_account(id),login_session_id uuid NOT NULL REFERENCES iam.session(id),required_action varchar(16) NOT NULL CHECK(required_action IN ('VIEW','EDIT')),
 object_id uuid REFERENCES storage.object(id),result_code varchar(64),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL,
 UNIQUE(source_version_id,output_type,converter_version)
);
CREATE INDEX ix_office_conversion_document ON drive.office_conversion(document_id,created_at DESC);
COMMENT ON TABLE drive.office_conversion IS 'Office固定来源转换与检测结果；READY派生文件可在当前权限下复用，原件和历史版本保留';
COMMENT ON COLUMN drive.office_conversion.id IS '转换标识，同时固定文档服务重试键';
COMMENT ON COLUMN drive.office_conversion.document_id IS '所属公盘文档';
COMMENT ON COLUMN drive.office_conversion.source_version_id IS '转换时固定的不可变原件版本';
COMMENT ON COLUMN drive.office_conversion.input_type IS '由实际已检测原件确定的输入扩展名';
COMMENT ON COLUMN drive.office_conversion.output_type IS '平台允许的目标OOXML或PDF格式';
COMMENT ON COLUMN drive.office_conversion.converter_version IS '已配置并经探测匹配的ONLYOFFICE版本';
COMMENT ON COLUMN drive.office_conversion.state IS 'PENDING待确认、READY检测通过、REJECTED内容被拒绝';
COMMENT ON COLUMN drive.office_conversion.requester_id IS '最近请求该转换的当前获权人员';
COMMENT ON COLUMN drive.office_conversion.login_session_id IS '服务取源文件时必须仍有效的请求人登录会话';
COMMENT ON COLUMN drive.office_conversion.required_action IS '取源文件所需动作，阅读派生为VIEW，编辑格式转换为EDIT';
COMMENT ON COLUMN drive.office_conversion.object_id IS '检测后双份保存的转换对象，拒绝内容亦保留隔离记录';
COMMENT ON COLUMN drive.office_conversion.result_code IS '脱敏转换或检测结果';
COMMENT ON COLUMN drive.office_conversion.created_at IS '首次发起时刻';
COMMENT ON COLUMN drive.office_conversion.expires_at IS '专用服务取原件入口的有效截止时刻';
