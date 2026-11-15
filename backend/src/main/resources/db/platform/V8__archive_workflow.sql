-- 档案与流程使用独立schema；只追加新平台结构，不读取旧系统正式业务数据。
CREATE SCHEMA archive;
CREATE SCHEMA workflow;
CREATE TABLE iam.company_duty (
 id uuid PRIMARY KEY,duty_code varchar(64) NOT NULL CHECK(duty_code='GENERAL_MANAGER'),user_id uuid NOT NULL REFERENCES iam.user_account(id),
 valid_from timestamptz NOT NULL,valid_until timestamptz,changed_by uuid NOT NULL REFERENCES iam.user_account(id),change_reason text NOT NULL,
 CHECK(valid_until IS NULL OR valid_until>=valid_from)
);
CREATE UNIQUE INDEX ux_company_current_duty ON iam.company_duty(duty_code) WHERE valid_until IS NULL;
CREATE TABLE iam.work_calendar (
 calendar_version integer NOT NULL CHECK(calendar_version>0),calendar_date date NOT NULL,is_workday boolean NOT NULL,holiday_name varchar(64),
 published_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,published_by uuid NOT NULL REFERENCES iam.user_account(id),change_reason text NOT NULL,
 PRIMARY KEY(calendar_version,calendar_date)
);
CREATE TABLE archive.directory_template (
 id uuid PRIMARY KEY,template_key varchar(256) NOT NULL,version_no integer NOT NULL CHECK(version_no>0),category_code varchar(64) NOT NULL,
 object_type varchar(16) NOT NULL CHECK(object_type IN ('BOX','RECORD')),name varchar(128) NOT NULL,state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','DISABLED')),
 created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(template_key,version_no)
);
CREATE TABLE archive.number_rule (
 scope_key varchar(256) PRIMARY KEY,last_value bigint NOT NULL CHECK(last_value>=0)
);
CREATE TABLE archive.box (
 id uuid PRIMARY KEY,box_no varchar(64) NOT NULL UNIQUE,department_id uuid NOT NULL REFERENCES iam.department(id),title varchar(512) NOT NULL,safe_title varchar(512),
 classification varchar(16) NOT NULL CHECK(classification IN ('GENERAL','IMPORTANT','CORE')),retention_code varchar(2) NOT NULL CHECK(retention_code IN ('05','10','30','YJ')),
 archive_date date NOT NULL,expiry_date date,physical_file_count integer NOT NULL DEFAULT 0 CHECK(physical_file_count>=0),location varchar(512),
 category_code varchar(64) NOT NULL CHECK(category_code IN ('ADMIN','SALES_CONTRACT','PURCHASE_CONTRACT','PROJECT_CONTRACT','QUALITY','WORK_RECORD')),
 carrier_type varchar(16) NOT NULL CHECK(carrier_type IN ('ELECTRONIC','PAPER','MIXED')),owner_id uuid NOT NULL REFERENCES iam.user_account(id),number_month date NOT NULL CHECK(EXTRACT(DAY FROM number_month)=1),
 keywords text[] NOT NULL CHECK(cardinality(keywords) BETWEEN 1 AND 20),abstract text,formed_date date,notes text,
 template_version_id uuid NOT NULL REFERENCES archive.directory_template(id),extension_values jsonb NOT NULL DEFAULT '{}',
 state varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PENDING_REVIEW','PUBLISHED','WITHDRAWN')),row_version integer NOT NULL DEFAULT 1,
 current_revision_id uuid,candidate_revision_id uuid,created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK((retention_code='YJ')=(expiry_date IS NULL)),UNIQUE(id,department_id,classification)
);
CREATE TABLE archive.record (
 id uuid PRIMARY KEY,file_no varchar(64) NOT NULL UNIQUE,box_id uuid NOT NULL,item_no integer NOT NULL CHECK(item_no>0),department_id uuid NOT NULL,
 classification varchar(16) NOT NULL,category_code varchar(64) NOT NULL CHECK(category_code IN ('ADMIN','SALES_CONTRACT','PURCHASE_CONTRACT','PROJECT_CONTRACT','QUALITY','WORK_RECORD')),
 carrier_type varchar(16) NOT NULL CHECK(carrier_type IN ('ELECTRONIC','PAPER','MIXED')),title varchar(512) NOT NULL,safe_title varchar(512),
 keywords text[] NOT NULL CHECK(cardinality(keywords) BETWEEN 1 AND 20),abstract text,owner_id uuid NOT NULL REFERENCES iam.user_account(id),archive_date date NOT NULL,
 retention_code varchar(2) NOT NULL CHECK(retention_code IN ('05','10','30','YJ')),expiry_date date,formed_date date,notes text,
 template_version_id uuid NOT NULL REFERENCES archive.directory_template(id),extension_values jsonb NOT NULL DEFAULT '{}',current_revision_id uuid,current_pdf_version_id uuid,candidate_revision_id uuid,
 state varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PENDING_REVIEW','PUBLISHED','WITHDRAWN')),row_version integer NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(box_id,item_no),FOREIGN KEY(box_id,department_id,classification) REFERENCES archive.box(id,department_id,classification),CHECK((retention_code='YJ')=(expiry_date IS NULL))
);
CREATE TABLE archive.pdf_version (
 id uuid PRIMARY KEY,record_id uuid NOT NULL REFERENCES archive.record(id),version_no integer NOT NULL CHECK(version_no>0),object_id uuid NOT NULL REFERENCES storage.object(id),source varchar(64) NOT NULL,
 correction_reason text,source_signature_info jsonb,review_state varchar(32) NOT NULL DEFAULT 'CHECK_PASSED' CHECK(review_state IN ('CHECK_PASSED','PENDING_REVIEW','APPROVED','REJECTED')),
 created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(record_id,version_no),UNIQUE(record_id,id)
);
CREATE TABLE archive.box_revision (
 id uuid PRIMARY KEY,box_id uuid NOT NULL REFERENCES archive.box(id),revision_no integer NOT NULL,metadata_snapshot jsonb NOT NULL,template_version_id uuid NOT NULL REFERENCES archive.directory_template(id),
 review_instance_id uuid,created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(box_id,revision_no),UNIQUE(box_id,id)
);
CREATE TABLE archive.record_revision (
 id uuid PRIMARY KEY,record_id uuid NOT NULL REFERENCES archive.record(id),revision_no integer NOT NULL,pdf_version_id uuid,metadata_snapshot jsonb NOT NULL,
 template_version_id uuid NOT NULL REFERENCES archive.directory_template(id),review_instance_id uuid,created_by uuid NOT NULL REFERENCES iam.user_account(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(record_id,revision_no),UNIQUE(record_id,id),UNIQUE(record_id,id,pdf_version_id),FOREIGN KEY(record_id,pdf_version_id) REFERENCES archive.pdf_version(record_id,id)
);
ALTER TABLE archive.box ADD FOREIGN KEY(id,current_revision_id) REFERENCES archive.box_revision(box_id,id),ADD FOREIGN KEY(id,candidate_revision_id) REFERENCES archive.box_revision(box_id,id);
ALTER TABLE archive.record ADD FOREIGN KEY(id,current_revision_id) REFERENCES archive.record_revision(record_id,id),ADD FOREIGN KEY(id,candidate_revision_id) REFERENCES archive.record_revision(record_id,id),ADD FOREIGN KEY(id,current_pdf_version_id) REFERENCES archive.pdf_version(record_id,id);
CREATE TABLE workflow.template_version (
 id uuid PRIMARY KEY,template_key varchar(256) NOT NULL,version_no integer NOT NULL,business_type varchar(64) NOT NULL,route_kind varchar(64) NOT NULL,nodes_definition jsonb NOT NULL,
 effective_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,state varchar(16) NOT NULL DEFAULT 'ACTIVE',checksum char(64) NOT NULL,UNIQUE(template_key,version_no)
);
CREATE TABLE workflow.instance (
 id uuid PRIMARY KEY,business_type varchar(64) NOT NULL CHECK(business_type IN ('REGISTRATION','BORROW','ACCESS_POLICY')),business_id uuid NOT NULL,applicant_id uuid NOT NULL REFERENCES iam.user_account(id),
 template_version_id uuid NOT NULL REFERENCES workflow.template_version(id),route_snapshot_id uuid NOT NULL,current_step_id uuid,round_no integer NOT NULL DEFAULT 1,
 state varchar(32) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPROVED','REJECTED','WITHDRAWN','CLOSED')),row_version integer NOT NULL DEFAULT 1,
 business_snapshot jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(business_type,business_id,round_no)
);
CREATE TABLE workflow.route_snapshot (
 id uuid PRIMARY KEY,instance_id uuid NOT NULL REFERENCES workflow.instance(id),batch_id uuid,highest_classification varchar(16) NOT NULL,form varchar(16),
 applicant_role varchar(64) NOT NULL,department_context jsonb NOT NULL,over_week boolean NOT NULL DEFAULT false,calendar_version integer,template_version integer NOT NULL,nodes_snapshot jsonb NOT NULL,
 UNIQUE(instance_id,id)
);
CREATE TABLE workflow.step (
 id uuid PRIMARY KEY,instance_id uuid NOT NULL REFERENCES workflow.instance(id),sequence_no integer NOT NULL,duty_code varchar(64) NOT NULL,department_id uuid REFERENCES iam.department(id),
 assignee_id uuid NOT NULL REFERENCES iam.user_account(id),assignment_type varchar(32) NOT NULL,assignment_ref uuid NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'WAITING' CHECK(state IN ('WAITING','PENDING','APPROVED','REJECTED','SKIPPED')),due_at timestamptz,completed_at timestamptz,row_version integer NOT NULL DEFAULT 1,
 UNIQUE(instance_id,sequence_no),UNIQUE(instance_id,id)
);
ALTER TABLE workflow.instance ADD FOREIGN KEY(id,route_snapshot_id) REFERENCES workflow.route_snapshot(instance_id,id) DEFERRABLE INITIALLY DEFERRED,
 ADD FOREIGN KEY(id,current_step_id) REFERENCES workflow.step(instance_id,id);
ALTER TABLE archive.box_revision ADD FOREIGN KEY(review_instance_id) REFERENCES workflow.instance(id);
ALTER TABLE archive.record_revision ADD FOREIGN KEY(review_instance_id) REFERENCES workflow.instance(id);
CREATE TABLE workflow.decision (
 id uuid PRIMARY KEY,step_id uuid NOT NULL UNIQUE REFERENCES workflow.step(id),result varchar(16) NOT NULL CHECK(result IN ('APPROVE','REJECT')),
 original_approver_id uuid NOT NULL REFERENCES iam.user_account(id),operator_id uuid NOT NULL REFERENCES iam.user_account(id),decision_mode varchar(32) NOT NULL CHECK(decision_mode='ONLINE'),
 agreed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,comment text NOT NULL,idempotency_key varchar(256) NOT NULL,
 UNIQUE(operator_id,idempotency_key)
);
CREATE TABLE workflow.assignment_event (
 id uuid PRIMARY KEY,step_id uuid NOT NULL REFERENCES workflow.step(id),from_user_id uuid NOT NULL REFERENCES iam.user_account(id),to_user_id uuid NOT NULL REFERENCES iam.user_account(id),
 operator_id uuid NOT NULL REFERENCES iam.user_account(id),reason text NOT NULL,approval_reference varchar(256) NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE archive.access_policy (
 id uuid PRIMARY KEY,object_type varchar(16) NOT NULL CHECK(object_type IN ('BOX','RECORD')),object_id uuid NOT NULL,department_scope uuid[] NOT NULL,
 title_visibility varchar(32) NOT NULL CHECK(title_visibility IN ('HIDDEN','TITLE_ONLY')),safe_search_fields varchar(64)[] NOT NULL,
 policy_version integer NOT NULL,approval_reference varchar(256) NOT NULL,approved_by uuid NOT NULL REFERENCES iam.user_account(id),workflow_instance_id uuid NOT NULL REFERENCES workflow.instance(id),
 valid_from timestamptz NOT NULL,valid_until timestamptz,state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','REVOKED','EXPIRED')),
 allow_owner_department_view boolean NOT NULL DEFAULT false,record_revision_id uuid REFERENCES archive.record_revision(id),pdf_version_id uuid REFERENCES archive.pdf_version(id),
 UNIQUE(object_type,object_id,policy_version),CHECK(valid_until IS NULL OR valid_until>valid_from),CHECK(NOT allow_owner_department_view OR (object_type='RECORD' AND record_revision_id IS NOT NULL AND pdf_version_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_archive_current_policy ON archive.access_policy(object_type,object_id) WHERE state='ACTIVE';
CREATE TABLE archive.borrow_batch (
 id uuid PRIMARY KEY,applicant_id uuid NOT NULL REFERENCES iam.user_account(id),applicant_department_id uuid NOT NULL REFERENCES iam.department(id),form varchar(16) NOT NULL CHECK(form='ELECTRONIC'),
 actions varchar(16)[] NOT NULL CHECK(actions=ARRAY['VIEW']::varchar[]),purpose varchar(2000) NOT NULL,parent_max_classification varchar(16) NOT NULL CHECK(parent_max_classification IN ('GENERAL','IMPORTANT','CORE')),
 item_count integer NOT NULL CHECK(item_count BETWEEN 1 AND 50),calendar_version integer NOT NULL,requested_period jsonb NOT NULL,requested_until timestamptz,
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPROVED','PARTIAL','REJECTED','WITHDRAWN','CLOSED')),submitted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE archive.borrow_request (
 id uuid PRIMARY KEY,batch_id uuid NOT NULL REFERENCES archive.borrow_batch(id),owner_department_id uuid NOT NULL REFERENCES iam.department(id),applicant_id uuid NOT NULL REFERENCES iam.user_account(id),
 workflow_instance_id uuid NOT NULL REFERENCES workflow.instance(id),route_snapshot_id uuid NOT NULL REFERENCES workflow.route_snapshot(id),round_no integer NOT NULL DEFAULT 1,
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPROVED','REJECTED','WITHDRAWN','CLOSED')),approved_until timestamptz,approved_actions varchar(16)[] NOT NULL,
 UNIQUE(batch_id,owner_department_id,round_no)
);
CREATE TABLE archive.request_item (
 id uuid PRIMARY KEY,batch_id uuid NOT NULL REFERENCES archive.borrow_batch(id),request_id uuid NOT NULL REFERENCES archive.borrow_request(id),record_id uuid NOT NULL REFERENCES archive.record(id),
 record_revision_id uuid NOT NULL,pdf_version_id uuid NOT NULL,action varchar(16) NOT NULL CHECK(action='VIEW'),classification_snapshot varchar(16) NOT NULL,
 UNIQUE(request_id,record_revision_id,action),FOREIGN KEY(record_id,record_revision_id,pdf_version_id) REFERENCES archive.record_revision(record_id,id,pdf_version_id)
);
CREATE TABLE archive.access_grant (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),record_id uuid NOT NULL REFERENCES archive.record(id),record_revision_id uuid NOT NULL,pdf_version_id uuid NOT NULL,
 action varchar(16) NOT NULL CHECK(action='VIEW'),source_type varchar(16) NOT NULL CHECK(source_type IN ('BASELINE','BORROW')),source_request_id uuid REFERENCES archive.borrow_request(id),source_policy_id uuid REFERENCES archive.access_policy(id),
 approval_reference varchar(256) NOT NULL,valid_from timestamptz NOT NULL,valid_until timestamptz NOT NULL,state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','REVOKED','EXPIRED')),
 policy_version integer NOT NULL DEFAULT 1,revoked_by uuid REFERENCES iam.user_account(id),revoked_at timestamptz,revocation_reason text,
 CHECK(valid_until>valid_from),CHECK((source_type='BORROW' AND source_request_id IS NOT NULL AND source_policy_id IS NULL) OR (source_type='BASELINE' AND source_policy_id IS NOT NULL AND source_request_id IS NULL)),
 FOREIGN KEY(record_id,record_revision_id,pdf_version_id) REFERENCES archive.record_revision(record_id,id,pdf_version_id)
);
CREATE UNIQUE INDEX ux_archive_live_grant ON archive.access_grant(user_id,record_revision_id,pdf_version_id,action,source_request_id,source_policy_id) NULLS NOT DISTINCT WHERE state='ACTIVE';
CREATE TABLE archive.usage_request (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),session_id uuid NOT NULL REFERENCES iam.session(id),auth_version integer NOT NULL,record_id uuid NOT NULL REFERENCES archive.record(id),
 record_revision_id uuid NOT NULL,pdf_version_id uuid NOT NULL,object_id uuid NOT NULL REFERENCES storage.object(id),grant_id uuid REFERENCES archive.access_grant(id),source_policy_id uuid REFERENCES archive.access_policy(id),
 review_context_id uuid,review_context_type varchar(32),source_version integer NOT NULL,action varchar(16) NOT NULL CHECK(action='VIEW'),state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','READY','FAILED','INVALIDATED')),
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,page_count integer NOT NULL DEFAULT 0,job_id uuid REFERENCES ops.job(id),
 CHECK((grant_id IS NOT NULL)::integer+(source_policy_id IS NOT NULL)::integer+(review_context_id IS NOT NULL)::integer=1),
 FOREIGN KEY(record_id,record_revision_id,pdf_version_id) REFERENCES archive.record_revision(record_id,id,pdf_version_id)
);
CREATE TABLE ops.outbox_event (
 id uuid PRIMARY KEY,event_id uuid NOT NULL UNIQUE,module varchar(32) NOT NULL,aggregate_type varchar(64) NOT NULL,aggregate_id uuid NOT NULL,aggregate_version integer NOT NULL,
 event_type varchar(64) NOT NULL,payload_ref varchar(256) NOT NULL,status varchar(16) NOT NULL DEFAULT 'PENDING',next_run_at timestamptz,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_archive_record_scope ON archive.record(department_id,state,created_at DESC,id);
CREATE INDEX ix_archive_record_box ON archive.record(box_id,state,id);
CREATE INDEX ix_archive_grant_read ON archive.access_grant(user_id,record_id,action,valid_until) WHERE state='ACTIVE';
CREATE INDEX ix_archive_grant_source ON archive.access_grant(source_request_id,source_policy_id,state);
CREATE INDEX ix_archive_policy_lookup ON archive.access_policy(object_type,object_id,state);
CREATE INDEX ix_workflow_actor_task ON workflow.step(assignee_id,state,instance_id);
CREATE INDEX ix_workflow_current ON workflow.instance(current_step_id,state);
CREATE INDEX ix_archive_request_actor ON archive.borrow_batch(applicant_id,submitted_at DESC,id);
CREATE INDEX ix_archive_usage_user ON archive.usage_request(user_id,expires_at);
CREATE INDEX ix_outbox_pending ON ops.outbox_event(status,next_run_at);
-- 固定快照、决定和日历只能追加，避免审批后悄悄修改原路线或原决定。
CREATE FUNCTION workflow.reject_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable approval history'; END; $$;
CREATE TRIGGER decision_immutable BEFORE UPDATE OR DELETE ON workflow.decision FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER route_immutable BEFORE UPDATE OR DELETE ON workflow.route_snapshot FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER assignment_immutable BEFORE UPDATE OR DELETE ON workflow.assignment_event FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER calendar_immutable BEFORE UPDATE OR DELETE ON iam.work_calendar FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER template_immutable BEFORE UPDATE OR DELETE ON workflow.template_version FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER record_revision_immutable BEFORE UPDATE OR DELETE ON archive.record_revision FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER box_revision_immutable BEFORE UPDATE OR DELETE ON archive.box_revision FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
-- 待办展示与决定提交共用当前任职校验，过期或被替换的职责不能继续批准。
CREATE FUNCTION workflow.assignment_valid(kind text,reference uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM iam.user_account u WHERE u.id=person AND u.status='ACTIVE') AND CASE kind
 WHEN 'ROLE' THEN EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id WHERE a.id=reference AND a.user_id=person AND a.module='ARCHIVE' AND a.state='ACTIVE' AND r.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'DEPARTMENT' THEN EXISTS(SELECT 1 FROM iam.department_manager a JOIN iam.department d ON d.id=a.department_id WHERE a.id=reference AND a.user_id=person AND a.duty_type='DEPARTMENT_MANAGER' AND d.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP))
 WHEN 'COMPANY' THEN EXISTS(SELECT 1 FROM iam.company_duty a WHERE a.id=reference AND a.user_id=person AND a.duty_code='GENERAL_MANAGER' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) ELSE false END;
$$;
-- 每个新字段由统一词典给出中文业务说明，表名用于区分同名字段的业务对象。
DO $$ DECLARE field record; words jsonb := '{"id":"稳定业务标识","user_id":"获权或任职员工","department_id":"所属部门","duty_code":"必要审批职责","valid_from":"业务生效时刻","valid_until":"业务失效时刻","changed_by":"职责变更经办人","change_reason":"变更依据说明","calendar_version":"固定公司工作日历版本","calendar_date":"公司日历日期","is_workday":"是否计入工作日","holiday_name":"节假日或调休说明","published_at":"版本发布时刻","published_by":"版本发布人","template_key":"固定模板代码","version_no":"不可变内容版本序号","category_code":"六类档案业务类别","object_type":"档案盒或文件类型","name":"显示名称","state":"当前受控业务状态","created_by":"实际创建人","created_at":"创建时刻","scope_key":"编号分区键","last_value":"已分配流水上界","box_no":"公司唯一档案盒号","title":"受控完整题名","safe_title":"需另经批准的安全题名","classification":"一般重要或核心保密等级","retention_code":"保管年限或永久代码","archive_date":"原始实际存档日期","expiry_date":"按日历年计算的保管到期日","physical_file_count":"实物清点总件数","location":"受控物理存放位置","carrier_type":"电子纸质或混合载体","owner_id":"业务责任人","number_month":"盒号所属编号月份首日","keywords":"获权检索关键词","abstract":"受控摘要","formed_date":"原始形成日期","notes":"受控目录备注","template_version_id":"固定模板版本","extension_values":"按模板校验的扩展值","row_version":"并发记录版本","current_revision_id":"当前已发布目录修订","candidate_revision_id":"本轮待审目录修订","updated_at":"最近更新时刻","file_no":"公司唯一独立文件号","box_id":"所属档案盒","item_no":"同盒不复用件号","current_pdf_version_id":"当前已发布PDF版本","record_id":"独立业务文件","object_id":"不可变文件对象或策略对象","source":"PDF来源","correction_reason":"替换PDF的业务原因","source_signature_info":"来源签名原始资料而非通过结论","review_state":"技术与业务复核状态","revision_no":"目录修订序号","metadata_snapshot":"不可变完整目录快照","review_instance_id":"关联归档复核流程","pdf_version_id":"固定批准PDF版本","business_type":"固定流程业务类型","route_kind":"受控路线类别","nodes_definition":"随版本发布的必要职责定义","effective_at":"模板生效时刻","checksum":"固定模板内容摘要","business_id":"流程业务对象","applicant_id":"真实申请人","route_snapshot_id":"固定送审路线快照","current_step_id":"当前待办节点","round_no":"本次送审轮次","business_snapshot":"固定业务输入清单","instance_id":"固定流程实例","batch_id":"原借阅父批次","highest_classification":"完整原清单最高等级","form":"电子或纸质借阅形式","applicant_role":"申请人送审身份","department_context":"路由关联部门快照","over_week":"纸质超过七自然日条件","template_version":"使用的固定路线版本","nodes_snapshot":"送审职责及人员不可变快照","sequence_no":"必要节点顺序","assignee_id":"当前合法处理人","assignment_type":"任职来源种类","assignment_ref":"当前具体任职记录","due_at":"待办提醒时刻","completed_at":"节点完成时刻","step_id":"本次决定或改派的节点","result":"明确批准或驳回结果","original_approver_id":"真实业务审批人","operator_id":"实际操作人","decision_mode":"在线决定方式","agreed_at":"业务审批发生时刻","recorded_at":"决定入库时刻","comment":"仅流程获权人员可见的审批意见","idempotency_key":"稳定操作去重键","from_user_id":"原处理人","to_user_id":"合法新处理人","reason":"业务原因","approval_reference":"真实业务批准引用","department_scope":"批准安全检索的受众部门集合","title_visibility":"是否开放批准安全题名","safe_search_fields":"允许检索的安全字段","policy_version":"授权来源版本","approved_by":"真实最终业务批准人","workflow_instance_id":"有效批准来源流程","allow_owner_department_view":"是否批准本部门一般文件基础阅读","record_revision_id":"固定文件目录修订","applicant_department_id":"送审时申请人所属部门","actions":"批准动作集合","purpose":"借阅业务用途","parent_max_classification":"完整父清单最高保密等级","item_count":"原批次不同文件数","requested_period":"送审期限规则快照","requested_until":"申请最迟自然日截止时刻","submitted_at":"实际送审时刻","owner_department_id":"档案所属部门","approved_until":"最终批准截止时刻","approved_actions":"最终批准动作","request_id":"所属部门借阅子单","action":"独立使用动作","classification_snapshot":"送审等级快照","source_type":"基础或借阅批准来源","source_request_id":"借阅批准子单","source_policy_id":"基础批准策略","revoked_by":"撤权经办人","revoked_at":"实际撤权时刻","revocation_reason":"撤权业务原因","session_id":"固定发起登录会话","auth_version":"固定用户授权代次","grant_id":"本次选定的具体人员授权","review_context_id":"固定候选复核流程上下文","source_version":"固定批准来源版本","expires_at":"阅读会话截止时刻","page_count":"隔离进程实测页数","job_id":"本人派生技术任务","event_id":"事件幂等标识","module":"独立业务模块","aggregate_type":"业务聚合类型","aggregate_id":"业务聚合标识","aggregate_version":"业务状态版本","event_type":"业务事件种类","payload_ref":"受控业务引用而非敏感正文","status":"待处理事件状态","next_run_at":"下次投递时刻"}'::jsonb;
 BEGIN FOR field IN SELECT table_schema,table_name,column_name FROM information_schema.columns WHERE table_schema IN ('archive','workflow') OR (table_schema='iam' AND table_name IN ('company_duty','work_calendar')) OR (table_schema='ops' AND table_name='outbox_event') LOOP
  EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',field.table_schema,field.table_name,field.column_name,COALESCE(words->>field.column_name,'受控业务字段'));
 END LOOP; END $$;
COMMENT ON SCHEMA archive IS '独立档案业务与具体版本批准，不继承公盘内容权限';
COMMENT ON SCHEMA workflow IS '固定路线与不可覆盖审批历史，不提供任意编排';
COMMENT ON TABLE archive.usage_request IS '绑定本人会话、具体批准来源与版本的水印逐页阅读会话';
COMMENT ON TABLE ops.outbox_event IS '与业务事务一起提交的事件引用，投递不重复执行业务';
