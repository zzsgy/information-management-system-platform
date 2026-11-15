-- V1.1 第一批独立新库结构；不迁移、不覆盖旧系统。
CREATE SCHEMA iam;
CREATE SCHEMA ops;
CREATE TABLE iam.user_account (
  id uuid NOT NULL,
  employee_no varchar(64) NOT NULL,
  name varchar(128) NOT NULL,
  department_id uuid NOT NULL,
  company_email varchar(254) NOT NULL,
  status varchar(64) NOT NULL DEFAULT 'ACTIVE',
  password_hash varchar(255) NOT NULL,
  session_version integer NOT NULL DEFAULT 1,
  auth_version integer NOT NULL DEFAULT 1,
  password_setup_required boolean NOT NULL DEFAULT true,
  login_lock_until timestamptz,
  row_version integer NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.user_account IS '员工统一账号及有效会话、授权版本的根状态';
COMMENT ON COLUMN iam.user_account.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.user_account.employee_no IS '公司工号';
COMMENT ON COLUMN iam.user_account.name IS '显示名称';
COMMENT ON COLUMN iam.user_account.department_id IS '所属部门标识；FK → iam.department.id';
COMMENT ON COLUMN iam.user_account.company_email IS '固定公司邮箱';
COMMENT ON COLUMN iam.user_account.status IS '当前状态；枚举 ACTIVE/DISABLED';
COMMENT ON COLUMN iam.user_account.password_hash IS '带独立盐的自适应密码散列';
COMMENT ON COLUMN iam.user_account.session_version IS '登录代次';
COMMENT ON COLUMN iam.user_account.auth_version IS '授权代次';
COMMENT ON COLUMN iam.user_account.password_setup_required IS '是否仅允许首次设密';
COMMENT ON COLUMN iam.user_account.login_lock_until IS '密码登录暂停截止时间';
COMMENT ON COLUMN iam.user_account.row_version IS '乐观锁版本';
COMMENT ON COLUMN iam.user_account.created_at IS '创建时刻；本次详细设计补充';
COMMENT ON COLUMN iam.user_account.updated_at IS '最近修改时间；本次详细设计补充';
CREATE TABLE iam.department (
  id uuid NOT NULL,
  code varchar(64) NOT NULL,
  name varchar(128) NOT NULL,
  parent_id uuid,
  status varchar(64) NOT NULL DEFAULT 'ACTIVE',
  sort_order integer NOT NULL DEFAULT 0,
  row_version integer NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.department IS '无环的多级部门组织与部门编码';
COMMENT ON COLUMN iam.department.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.department.code IS '稳定业务代码';
COMMENT ON COLUMN iam.department.name IS '显示名称';
COMMENT ON COLUMN iam.department.parent_id IS '父级标识；FK → iam.department.id';
COMMENT ON COLUMN iam.department.status IS '当前状态；枚举 ACTIVE/DISABLED';
COMMENT ON COLUMN iam.department.sort_order IS '显示顺序';
COMMENT ON COLUMN iam.department.row_version IS '乐观锁版本';
COMMENT ON COLUMN iam.department.created_at IS '创建时刻；本次详细设计补充';
COMMENT ON COLUMN iam.department.updated_at IS '最近修改时间；本次详细设计补充';
CREATE TABLE iam.department_manager (
  id uuid NOT NULL,
  department_id uuid NOT NULL,
  user_id uuid NOT NULL,
  duty_type varchar(64) NOT NULL,
  valid_from timestamptz NOT NULL,
  valid_until timestamptz,
  replaced_assignment_id uuid,
  change_reason text NOT NULL,
  changed_by uuid NOT NULL,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.department_manager IS '部门负责人及独立分管领导职责的任职历史';
COMMENT ON COLUMN iam.department_manager.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.department_manager.department_id IS '所属部门标识；FK → iam.department.id';
COMMENT ON COLUMN iam.department_manager.user_id IS '员工标识；FK → iam.user_account.id';
COMMENT ON COLUMN iam.department_manager.duty_type IS '任职职责代码；枚举 DEPARTMENT_MANAGER/SUPERVISING_LEADER';
COMMENT ON COLUMN iam.department_manager.valid_from IS '生效时刻';
COMMENT ON COLUMN iam.department_manager.valid_until IS '失效时刻';
COMMENT ON COLUMN iam.department_manager.replaced_assignment_id IS '被替换的任职记录；FK → iam.department_manager.id';
COMMENT ON COLUMN iam.department_manager.change_reason IS '变更原因';
COMMENT ON COLUMN iam.department_manager.changed_by IS '变更经办人；FK → iam.user_account.id';
CREATE TABLE iam.role (
  id uuid NOT NULL,
  module varchar(64) NOT NULL,
  code varchar(64) NOT NULL,
  name varchar(128) NOT NULL,
  status varchar(64) NOT NULL DEFAULT 'ACTIVE',
  permission_version integer NOT NULL DEFAULT 1,
  row_version integer NOT NULL DEFAULT 1,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.role IS '平台、公盘、档案分域的基础角色定义';
COMMENT ON COLUMN iam.role.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.role.module IS '所属模块';
COMMENT ON COLUMN iam.role.code IS '稳定业务代码';
COMMENT ON COLUMN iam.role.name IS '显示名称';
COMMENT ON COLUMN iam.role.status IS '当前状态；枚举 ACTIVE/DISABLED';
COMMENT ON COLUMN iam.role.permission_version IS '权限配置版本';
COMMENT ON COLUMN iam.role.row_version IS '乐观锁版本';
CREATE TABLE iam.user_role (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  role_id uuid NOT NULL,
  department_id uuid,
  valid_from timestamptz NOT NULL,
  valid_until timestamptz,
  state varchar(64) NOT NULL DEFAULT 'ACTIVE',
  assigned_by uuid NOT NULL,
  approval_ref varchar(256),
  handover_ref varchar(256),
  module varchar(64) NOT NULL,
  role_code varchar(64) NOT NULL,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.user_role IS '员工在各模块和部门范围内的角色任职及历史';
COMMENT ON COLUMN iam.user_role.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.user_role.user_id IS '员工标识；FK → iam.user_account.id';
COMMENT ON COLUMN iam.user_role.role_id IS '角色标识；FK → iam.role.id';
COMMENT ON COLUMN iam.user_role.department_id IS '所属部门标识；FK → iam.department.id';
COMMENT ON COLUMN iam.user_role.valid_from IS '生效时刻';
COMMENT ON COLUMN iam.user_role.valid_until IS '失效时刻';
COMMENT ON COLUMN iam.user_role.state IS '业务状态；枚举 ACTIVE/ENDED';
COMMENT ON COLUMN iam.user_role.assigned_by IS '任职配置人；FK → iam.user_account.id';
COMMENT ON COLUMN iam.user_role.approval_ref IS '批准依据标识';
COMMENT ON COLUMN iam.user_role.handover_ref IS '交接记录标识';
COMMENT ON COLUMN iam.user_role.module IS '所属模块；本次详细设计补充';
COMMENT ON COLUMN iam.user_role.role_code IS '角色稳定代码的受控冗余列；本次详细设计补充';
CREATE TABLE iam.permission (
  id uuid NOT NULL,
  module varchar(64) NOT NULL,
  code varchar(64) NOT NULL,
  name varchar(128) NOT NULL,
  resource_type varchar(64) NOT NULL,
  action varchar(64) NOT NULL,
  sensitive_flag boolean NOT NULL DEFAULT false,
  assignable_scope_type varchar(64) NOT NULL,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.permission IS '可分配管理能力的受控权限字典';
COMMENT ON COLUMN iam.permission.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.permission.module IS '所属模块';
COMMENT ON COLUMN iam.permission.code IS '稳定业务代码';
COMMENT ON COLUMN iam.permission.name IS '显示名称';
COMMENT ON COLUMN iam.permission.resource_type IS '资源类别';
COMMENT ON COLUMN iam.permission.action IS '获准或实际执行的动作';
COMMENT ON COLUMN iam.permission.sensitive_flag IS '是否属于敏感操作';
COMMENT ON COLUMN iam.permission.assignable_scope_type IS '允许配置的范围类型';
CREATE TABLE iam.role_permission (
  role_id uuid NOT NULL,
  permission_id uuid NOT NULL,
  scope_policy varchar(32) NOT NULL,
  effect varchar(64) NOT NULL,
  version integer NOT NULL DEFAULT 1,
  changed_by uuid NOT NULL,
  changed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (role_id,permission_id)
);
COMMENT ON TABLE iam.role_permission IS '角色与权限的多对多配置关系';
COMMENT ON COLUMN iam.role_permission.role_id IS 'PK；角色标识；FK → iam.role.id';
COMMENT ON COLUMN iam.role_permission.permission_id IS 'PK；权限项标识；FK → iam.permission.id';
COMMENT ON COLUMN iam.role_permission.scope_policy IS '受控范围策略';
COMMENT ON COLUMN iam.role_permission.effect IS '允许或拒绝；枚举 ALLOW/DENY';
COMMENT ON COLUMN iam.role_permission.version IS '配置或授权版本';
COMMENT ON COLUMN iam.role_permission.changed_by IS '变更经办人；FK → iam.user_account.id';
COMMENT ON COLUMN iam.role_permission.changed_at IS '变更时刻';
CREATE TABLE iam.permission_assignment (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  permission_id uuid NOT NULL,
  scope_type varchar(64) NOT NULL,
  scope_id uuid,
  effect varchar(64) NOT NULL,
  valid_from timestamptz NOT NULL,
  valid_until timestamptz,
  granted_by uuid NOT NULL,
  approval_ref varchar(256),
  state varchar(64) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.permission_assignment IS '平台管理等非业务对象权限的个人允许、拒绝及期限';
COMMENT ON COLUMN iam.permission_assignment.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.permission_assignment.user_id IS '员工标识；FK → iam.user_account.id';
COMMENT ON COLUMN iam.permission_assignment.permission_id IS '权限项标识；FK → iam.permission.id';
COMMENT ON COLUMN iam.permission_assignment.scope_type IS '对象范围类型';
COMMENT ON COLUMN iam.permission_assignment.scope_id IS '范围对象标识';
COMMENT ON COLUMN iam.permission_assignment.effect IS '允许或拒绝；枚举 ALLOW/DENY';
COMMENT ON COLUMN iam.permission_assignment.valid_from IS '生效时刻';
COMMENT ON COLUMN iam.permission_assignment.valid_until IS '失效时刻';
COMMENT ON COLUMN iam.permission_assignment.granted_by IS '授予人；FK → iam.user_account.id';
COMMENT ON COLUMN iam.permission_assignment.approval_ref IS '批准依据标识';
COMMENT ON COLUMN iam.permission_assignment.state IS '业务状态；枚举 ACTIVE/REVOKED/EXPIRED';
CREATE TABLE iam.session (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  token_hash varchar(255) NOT NULL,
  session_version integer NOT NULL DEFAULT 1,
  auth_version integer NOT NULL DEFAULT 1,
  last_activity_at timestamptz NOT NULL,
  issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  client_summary jsonb NOT NULL,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.session IS '员工唯一有效登录会话';
COMMENT ON COLUMN iam.session.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.session.user_id IS '员工标识；FK → iam.user_account.id';
COMMENT ON COLUMN iam.session.token_hash IS '会话令牌摘要';
COMMENT ON COLUMN iam.session.session_version IS '登录代次';
COMMENT ON COLUMN iam.session.auth_version IS '授权代次';
COMMENT ON COLUMN iam.session.last_activity_at IS '最近有效业务操作时刻';
COMMENT ON COLUMN iam.session.issued_at IS '签发时刻';
COMMENT ON COLUMN iam.session.expires_at IS '截止时刻';
COMMENT ON COLUMN iam.session.revoked_at IS '撤销时刻';
COMMENT ON COLUMN iam.session.client_summary IS '最少必要的客户端摘要';
CREATE TABLE iam.credential_challenge (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  session_id uuid,
  purpose varchar(64) NOT NULL,
  credential_hash varchar(255) NOT NULL,
  expires_at timestamptz NOT NULL,
  error_count integer NOT NULL DEFAULT 0,
  resend_count integer NOT NULL DEFAULT 0,
  last_sent_at timestamptz,
  scope_digest char(64),
  consumed_at timestamptz,
  flow_id uuid NOT NULL,
  issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.credential_challenge IS '临时设密、邮箱找回及高风险操作再验证的一次性凭据';
COMMENT ON COLUMN iam.credential_challenge.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.credential_challenge.user_id IS '员工标识；FK → iam.user_account.id';
COMMENT ON COLUMN iam.credential_challenge.session_id IS '再次验证绑定的当前会话；找回、初始及重置设密时为空，不能伪造登录会话；FK → iam.session.id';
COMMENT ON COLUMN iam.credential_challenge.purpose IS '访问或验证用途';
COMMENT ON COLUMN iam.credential_challenge.credential_hash IS '一次性凭据摘要';
COMMENT ON COLUMN iam.credential_challenge.expires_at IS '截止时刻';
COMMENT ON COLUMN iam.credential_challenge.error_count IS '当前流程累计错误次数';
COMMENT ON COLUMN iam.credential_challenge.resend_count IS '重发次数';
COMMENT ON COLUMN iam.credential_challenge.last_sent_at IS '最近发送时刻';
COMMENT ON COLUMN iam.credential_challenge.scope_digest IS '对象清单及用途摘要';
COMMENT ON COLUMN iam.credential_challenge.consumed_at IS '成功消费时刻';
COMMENT ON COLUMN iam.credential_challenge.flow_id IS '验证码流程稳定标识';
COMMENT ON COLUMN iam.credential_challenge.issued_at IS '本次凭据实际签发时间；V1.1复核补充';
CREATE TABLE iam.service_identity (
  id uuid NOT NULL,
  service_type varchar(64) NOT NULL,
  name varchar(128) NOT NULL,
  allowed_module varchar(64) NOT NULL,
  department_scope uuid[] NOT NULL,
  allowed_actions varchar(32)[] NOT NULL,
  credential_ref varchar(256) NOT NULL,
  credential_version integer NOT NULL DEFAULT 1,
  valid_until timestamptz NOT NULL,
  status varchar(64) NOT NULL DEFAULT 'ACTIVE',
  row_version integer NOT NULL DEFAULT 1,
  PRIMARY KEY (id)
);
COMMENT ON TABLE iam.service_identity IS '扫描、处理、通知、备份等专用服务身份的范围和密钥引用';
COMMENT ON COLUMN iam.service_identity.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN iam.service_identity.service_type IS '专用服务类别';
COMMENT ON COLUMN iam.service_identity.name IS '显示名称';
COMMENT ON COLUMN iam.service_identity.allowed_module IS '允许服务的模块';
COMMENT ON COLUMN iam.service_identity.department_scope IS '获准部门标识集合';
COMMENT ON COLUMN iam.service_identity.allowed_actions IS '允许动作集合';
COMMENT ON COLUMN iam.service_identity.credential_ref IS '凭据库引用而非秘密值';
COMMENT ON COLUMN iam.service_identity.credential_version IS '凭据轮换版本';
COMMENT ON COLUMN iam.service_identity.valid_until IS '失效时刻';
COMMENT ON COLUMN iam.service_identity.status IS '当前状态；枚举 ACTIVE/DISABLED';
COMMENT ON COLUMN iam.service_identity.row_version IS '乐观锁版本';
CREATE TABLE ops.audit_event (
  id uuid NOT NULL,
  event_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  ingested_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  actor_id uuid,
  on_behalf_of_id uuid,
  module varchar(64) NOT NULL,
  resource_type varchar(64) NOT NULL,
  resource_id uuid,
  resource_version integer,
  action varchar(64) NOT NULL,
  result varchar(64) NOT NULL,
  authorization_source varchar(64),
  request_id uuid NOT NULL,
  application_id uuid,
  retention_until timestamptz,
  record_kind varchar(64) NOT NULL,
  operation_key varchar(256),
  request_digest char(64),
  operation_result jsonb,
  severity varchar(16) NOT NULL DEFAULT 'INFO',
  actor_service_id uuid,
  PRIMARY KEY (id)
);
COMMENT ON TABLE ops.audit_event IS '不可由业务操作者改写的关键业务与管理审计';
COMMENT ON COLUMN ops.audit_event.id IS 'PK；稳定内部标识';
COMMENT ON COLUMN ops.audit_event.event_id IS '业务事件去重标识';
COMMENT ON COLUMN ops.audit_event.occurred_at IS '实际发生时刻';
COMMENT ON COLUMN ops.audit_event.ingested_at IS '服务接收入库时刻';
COMMENT ON COLUMN ops.audit_event.actor_id IS '实际操作者';
COMMENT ON COLUMN ops.audit_event.on_behalf_of_id IS '被代办的原批准人';
COMMENT ON COLUMN ops.audit_event.module IS '所属模块';
COMMENT ON COLUMN ops.audit_event.resource_type IS '资源类别';
COMMENT ON COLUMN ops.audit_event.resource_id IS '业务对象标识';
COMMENT ON COLUMN ops.audit_event.resource_version IS '动作对应的业务版本';
COMMENT ON COLUMN ops.audit_event.action IS '获准或实际执行的动作';
COMMENT ON COLUMN ops.audit_event.result IS '真实操作或处理结果';
COMMENT ON COLUMN ops.audit_event.authorization_source IS '授权来源标识摘要';
COMMENT ON COLUMN ops.audit_event.request_id IS '请求追踪标识';
COMMENT ON COLUMN ops.audit_event.application_id IS '关联业务申请标识';
COMMENT ON COLUMN ops.audit_event.retention_until IS '记录保留截止时间';
COMMENT ON COLUMN ops.audit_event.record_kind IS '审计事实或幂等回执类别；枚举 BUSINESS_EVENT/SECURITY_EVENT/IDEMPOTENCY_RECEIPT；本次详细设计补充';
COMMENT ON COLUMN ops.audit_event.operation_key IS '本次写操作的稳定幂等键；本次详细设计补充';
COMMENT ON COLUMN ops.audit_event.request_digest IS '规范化请求载荷摘要；本次详细设计补充';
COMMENT ON COLUMN ops.audit_event.operation_result IS '仅保存对象标识状态及错误码的幂等回执；本次详细设计补充';
COMMENT ON COLUMN ops.audit_event.severity IS '实际事件级别；不影响必须记录的事件；枚举 INFO/WARN/ERROR/CRITICAL；V1.1复核补充';
COMMENT ON COLUMN ops.audit_event.actor_service_id IS '内部专用服务实际主体，与员工actor_id互斥；匿名拒绝事件可均空；V1.1复核补充；FK → iam.service_identity.id';
ALTER TABLE iam.user_account ADD FOREIGN KEY (department_id) REFERENCES iam.department(id) ON DELETE RESTRICT;
ALTER TABLE iam.department ADD FOREIGN KEY (parent_id) REFERENCES iam.department(id) ON DELETE RESTRICT;
ALTER TABLE iam.department_manager ADD FOREIGN KEY (department_id) REFERENCES iam.department(id) ON DELETE RESTRICT;
ALTER TABLE iam.department_manager ADD FOREIGN KEY (user_id) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.department_manager ADD FOREIGN KEY (replaced_assignment_id) REFERENCES iam.department_manager(id) ON DELETE RESTRICT;
ALTER TABLE iam.department_manager ADD FOREIGN KEY (changed_by) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.user_role ADD FOREIGN KEY (user_id) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.user_role ADD FOREIGN KEY (role_id) REFERENCES iam.role(id) ON DELETE RESTRICT;
ALTER TABLE iam.user_role ADD FOREIGN KEY (department_id) REFERENCES iam.department(id) ON DELETE RESTRICT;
ALTER TABLE iam.user_role ADD FOREIGN KEY (assigned_by) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.role_permission ADD FOREIGN KEY (role_id) REFERENCES iam.role(id) ON DELETE RESTRICT;
ALTER TABLE iam.role_permission ADD FOREIGN KEY (permission_id) REFERENCES iam.permission(id) ON DELETE RESTRICT;
ALTER TABLE iam.role_permission ADD FOREIGN KEY (changed_by) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.permission_assignment ADD FOREIGN KEY (user_id) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.permission_assignment ADD FOREIGN KEY (permission_id) REFERENCES iam.permission(id) ON DELETE RESTRICT;
ALTER TABLE iam.permission_assignment ADD FOREIGN KEY (granted_by) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.session ADD FOREIGN KEY (user_id) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.credential_challenge ADD FOREIGN KEY (user_id) REFERENCES iam.user_account(id) ON DELETE RESTRICT;
ALTER TABLE iam.credential_challenge ADD FOREIGN KEY (session_id) REFERENCES iam.session(id) ON DELETE RESTRICT;
ALTER TABLE ops.audit_event ADD FOREIGN KEY (actor_service_id) REFERENCES iam.service_identity(id) ON DELETE RESTRICT;
