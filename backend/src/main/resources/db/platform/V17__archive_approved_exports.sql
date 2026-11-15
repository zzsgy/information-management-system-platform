-- 查看、下载、打印独立批准；打印页码与累计份数均冻结到真实审批快照。
ALTER TABLE archive.borrow_batch DROP CONSTRAINT borrow_batch_actions_check;
ALTER TABLE archive.borrow_batch ADD CHECK(cardinality(actions) BETWEEN 1 AND 3 AND actions <@ ARRAY['VIEW','DOWNLOAD','PRINT']::varchar[]);
ALTER TABLE archive.request_item DROP CONSTRAINT request_item_action_check;
ALTER TABLE archive.request_item ADD CHECK(action IN ('VIEW','DOWNLOAD','PRINT')),
 ADD COLUMN approved_pages integer[],ADD COLUMN copies integer;
ALTER TABLE archive.access_grant DROP CONSTRAINT access_grant_action_check;
ALTER TABLE archive.access_grant ADD CHECK(action IN ('VIEW','DOWNLOAD','PRINT')),
 ADD COLUMN approved_pages integer[],ADD COLUMN approved_copies integer,ADD COLUMN consumed_copies integer NOT NULL DEFAULT 0,
 ADD CHECK((action='PRINT' AND cardinality(approved_pages)>0 AND approved_copies BETWEEN 1 AND 100 AND consumed_copies BETWEEN 0 AND approved_copies) OR (action<>'PRINT' AND approved_pages IS NULL AND approved_copies IS NULL AND consumed_copies=0));
COMMENT ON COLUMN archive.request_item.approved_pages IS '送审冻结的打印页码，默认完整PDF全部页；非打印为空';
COMMENT ON COLUMN archive.request_item.copies IS '送审冻结的累计打印份数上限，非打印为空';
COMMENT ON COLUMN archive.access_grant.approved_pages IS '实际批准的PRINT页码，须与不可变业务快照一致';
COMMENT ON COLUMN archive.access_grant.approved_copies IS 'PRINT累计受理份数上限，不推定实际出纸';
COMMENT ON COLUMN archive.access_grant.consumed_copies IS '已原子占用的打印份数，重放和未知打印结果不自动归零';

CREATE TABLE archive.export_request (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),session_id uuid NOT NULL REFERENCES iam.session(id),auth_version integer NOT NULL,
 action varchar(16) NOT NULL CHECK(action IN ('DOWNLOAD','PRINT')),operation_id uuid NOT NULL,request_digest varchar(64) NOT NULL,
 borrow_batch_id uuid REFERENCES archive.borrow_batch(id),state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','READY','FAILED')),
 job_id uuid REFERENCES ops.job(id),expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 generated_at timestamptz,watermark_name varchar(128) NOT NULL,watermark_employee_no varchar(64) NOT NULL,template_version integer NOT NULL DEFAULT 1,
 lease_token uuid,lease_until timestamptz,error_code varchar(64),UNIQUE(user_id,action,operation_id)
);
CREATE TABLE archive.export_item (
 id uuid PRIMARY KEY,usage_id uuid NOT NULL REFERENCES archive.export_request(id),sequence_no integer NOT NULL,
 record_id uuid NOT NULL REFERENCES archive.record(id),record_revision_id uuid NOT NULL,pdf_version_id uuid NOT NULL,object_id uuid NOT NULL REFERENCES storage.object(id),
 grant_id uuid NOT NULL REFERENCES archive.access_grant(id),source_version integer NOT NULL,file_no varchar(64) NOT NULL,
 pages integer[] NOT NULL CHECK(cardinality(pages)>0),copies integer NOT NULL CHECK(copies BETWEEN 1 AND 100),
 UNIQUE(usage_id,sequence_no),UNIQUE(usage_id,record_id),FOREIGN KEY(record_id,record_revision_id,pdf_version_id) REFERENCES archive.record_revision(record_id,id,pdf_version_id)
);
CREATE TABLE archive.export_asset (
 id uuid PRIMARY KEY,usage_id uuid NOT NULL REFERENCES archive.export_request(id),lease_token uuid NOT NULL,
 kind varchar(16) NOT NULL CHECK(kind IN ('PDF','ZIP','PAGE')),sequence_no integer NOT NULL DEFAULT 0,
 storage_key varchar(64) NOT NULL UNIQUE,sha256 varchar(64) NOT NULL,byte_size bigint NOT NULL CHECK(byte_size>0),
 media_type varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 purge_after timestamptz NOT NULL,storage_state varchar(16) NOT NULL DEFAULT 'LIVE' CHECK(storage_state IN ('LIVE','PURGED')),
 UNIQUE(usage_id,lease_token,kind,sequence_no)
);
CREATE TABLE archive.export_delivery (
 id uuid PRIMARY KEY,usage_id uuid NOT NULL REFERENCES archive.export_request(id),asset_id uuid NOT NULL REFERENCES archive.export_asset(id),
 operation_id uuid NOT NULL,receipt_id uuid NOT NULL,request_id uuid NOT NULL,authorized_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 result varchar(32) NOT NULL DEFAULT 'AUTHORIZED_FOR_DELIVERY' CHECK(result='AUTHORIZED_FOR_DELIVERY'),UNIQUE(usage_id,asset_id,operation_id)
);
CREATE TABLE archive.print_feedback (
 id uuid PRIMARY KEY,usage_id uuid NOT NULL REFERENCES archive.export_request(id),operator_id uuid NOT NULL REFERENCES iam.user_account(id),operation_id uuid NOT NULL,
 feedback varchar(32) NOT NULL CHECK(feedback IN ('SUBMITTED','CLIENT_FAILED','UNKNOWN')),source varchar(32) NOT NULL DEFAULT 'BROWSER_CLAIM' CHECK(source='BROWSER_CLAIM'),
 submitted_at timestamptz,recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,evidence_ref varchar(256),client_job_ref varchar(128),request_digest varchar(64) NOT NULL,
 UNIQUE(usage_id,operation_id)
);
CREATE INDEX ix_export_actor ON archive.export_request(user_id,created_at DESC,id);
CREATE INDEX ix_export_work ON archive.export_request(state,lease_until);
CREATE INDEX ix_export_purge ON archive.export_asset(purge_after,id) WHERE storage_state='LIVE';
CREATE INDEX ix_export_item_grant ON archive.export_item(grant_id,usage_id);
CREATE TRIGGER export_item_immutable BEFORE UPDATE OR DELETE ON archive.export_item FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER export_delivery_immutable BEFORE UPDATE OR DELETE ON archive.export_delivery FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
CREATE TRIGGER print_feedback_immutable BEFORE UPDATE OR DELETE ON archive.print_feedback FOR EACH ROW EXECUTE FUNCTION workflow.reject_rewrite();
COMMENT ON TABLE archive.export_request IS '固定本人会话、动作、操作号和水印身份的受控副本任务';
COMMENT ON TABLE archive.export_item IS '每个副本任务固定的文件版本、批准来源及打印范围，不接受客户路径';
COMMENT ON TABLE archive.export_asset IS '可重建的私有双副本输出，绑定任务租约，不与其他人共享成品';
COMMENT ON TABLE archive.export_delivery IS '取得库外刷盘回执后的服务端交付许可；不证明客户端收全或实际出纸';
COMMENT ON TABLE archive.print_feedback IS '浏览器声明的打印过程反馈，不能当作可核验的设备出纸事实';
DO $$ DECLARE field record; words jsonb:='{"id":"稳定内部标识","user_id":"本次获权员工","session_id":"固定发起会话","auth_version":"固定授权代次","action":"独立批准动作","operation_id":"客户端重试复用的操作号","request_digest":"不含凭据秘密的冻结输入摘要","borrow_batch_id":"可选原批准批次","state":"本次技术处理状态","job_id":"异步任务引用","expires_at":"本次使用截止时刻","created_at":"实际受理时刻","generated_at":"固定的服务端水印生成时刻","watermark_name":"水印姓名快照","watermark_employee_no":"水印工号快照","template_version":"水印算法模板版本","lease_token":"本次处理租约标识","lease_until":"处理租约截止","error_code":"清洗后的失败代码","usage_id":"所属副本任务","sequence_no":"冻结清单或输出页顺序","record_id":"独立档案文件","record_revision_id":"批准目录修订","pdf_version_id":"批准完整PDF版本","object_id":"已检测的原件对象","grant_id":"固定具体动作批准来源","source_version":"批准来源版本","file_no":"不可变独立文件号","pages":"规范化页码集合","copies":"本次占用的打印份数","kind":"派生PDF、整包或打印页","storage_key":"仅供服务内部使用的随机私有键","sha256":"实际派生内容SHA256","byte_size":"派生字节数","media_type":"实际媒体类型","purge_after":"受控缓存清理保护期","storage_state":"私有派生缓存是否仍在","asset_id":"实际交付输出","receipt_id":"库外持久回执","request_id":"实际请求追踪号","authorized_at":"交付前完成授权的时刻","result":"服务端准许交付的事实","operator_id":"实际反馈人","feedback":"已提交客户端、失败或未知","source":"不可冒充设备证明的浏览器声明来源","submitted_at":"客户端声明提交时刻","recorded_at":"服务器记录反馈时刻","evidence_ref":"未验证的客户端证据引用","client_job_ref":"客户端打印关联标识"}'::jsonb;
BEGIN FOR field IN SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='archive' AND table_name IN ('export_request','export_item','export_asset','export_delivery','print_feedback') LOOP
 EXECUTE format('COMMENT ON COLUMN archive.%I.%I IS %L',field.table_name,field.column_name,COALESCE(words->>field.column_name,'受控副本业务字段'));
END LOOP; END $$;

CREATE OR REPLACE FUNCTION archive.grant_valid(grant_id uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_grant g JOIN archive.record r ON r.id=g.record_id JOIN archive.box b ON b.id=r.box_id
 JOIN archive.pdf_version v ON v.id=g.pdf_version_id AND v.record_id=r.id JOIN storage.object o ON o.id=v.object_id JOIN iam.user_account u ON u.id=person
 WHERE g.id=grant_id AND g.user_id=person AND u.status='ACTIVE' AND g.state='ACTIVE' AND g.valid_from<=CURRENT_TIMESTAMP AND g.valid_until>CURRENT_TIMESTAMP
 AND r.state='PUBLISHED' AND b.state='PUBLISHED' AND r.current_revision_id=g.record_revision_id AND r.current_pdf_version_id=g.pdf_version_id AND v.review_state='APPROVED' AND o.state='READY' AND o.module='ARCHIVE'
 AND ((g.source_type='BORROW' AND EXISTS(SELECT 1 FROM archive.borrow_request q JOIN archive.borrow_batch batch ON batch.id=q.batch_id JOIN workflow.instance w ON w.id=q.workflow_instance_id
 JOIN workflow.route_snapshot route ON route.id=w.route_snapshot_id JOIN archive.request_item i ON i.request_id=q.id AND i.batch_id=q.batch_id
 WHERE q.id=g.source_request_id AND q.applicant_id=g.user_id AND batch.applicant_department_id=u.department_id AND batch.form='ELECTRONIC' AND q.state='APPROVED'
 AND workflow.approval_valid(w.id,'BORROW',q.id) AND route.highest_classification=batch.parent_max_classification AND q.approved_until>=g.valid_until AND g.action=ANY(q.approved_actions) AND g.action=ANY(batch.actions)
 AND i.record_id=g.record_id AND i.record_revision_id=g.record_revision_id AND i.pdf_version_id=g.pdf_version_id AND i.action=g.action AND i.classification_snapshot=r.classification
 AND EXISTS(SELECT 1 FROM jsonb_array_elements(w.business_snapshot->'items') item WHERE item->>'recordRevisionId'=g.record_revision_id::text AND item->>'pdfVersionId'=g.pdf_version_id::text AND COALESCE(item->>'action','VIEW')=g.action
  AND (g.action<>'PRINT' OR (item->'printPages'=to_jsonb(g.approved_pages) AND (item->>'copies')::integer=g.approved_copies AND i.approved_pages=g.approved_pages AND i.copies=g.approved_copies)))))
 OR (g.source_type='BASELINE' AND g.action='VIEW' AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN workflow.instance w ON w.id=p.workflow_instance_id
 WHERE p.id=g.source_policy_id AND archive.policy_approved(p.id) AND p.object_id=r.id AND p.record_revision_id=g.record_revision_id AND p.pdf_version_id=g.pdf_version_id AND p.policy_version=g.policy_version
 AND w.business_snapshot->'userIds' @> to_jsonb(ARRAY[person::text]) AND g.valid_from=p.valid_from AND g.valid_until=p.valid_until))));
$$;
CREATE FUNCTION archive.action_allowed(record_id uuid,person uuid,verb text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.record_id=record_id AND g.user_id=person AND g.action=verb AND archive.grant_valid(g.id,person) AND (verb<>'PRINT' OR g.consumed_copies<g.approved_copies))
 OR (verb='VIEW' AND EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=record_id AND archive.baseline_valid(p.id,person)));
$$;
COMMENT ON FUNCTION archive.action_allowed(uuid,uuid,text) IS '界面动作分别投影，下载或打印的元数据可见性不等于取得查看权';
