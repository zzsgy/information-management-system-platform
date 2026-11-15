-- 审计范围快照独立追加，历史事实不改写；找不到对象归属时只向模块总管理员开放。
CREATE TABLE ops.audit_scope (
 event_id uuid PRIMARY KEY REFERENCES ops.audit_event(id), department_ids uuid[] NOT NULL DEFAULT '{}', folder_id uuid
);
CREATE INDEX audit_scope_departments ON ops.audit_scope USING gin(department_ids);
CREATE INDEX audit_scope_folder ON ops.audit_scope(folder_id,event_id);
CREATE FUNCTION ops.audit_departments(target uuid) RETURNS uuid[] LANGUAGE sql STABLE AS $$
 SELECT COALESCE(array_agg(DISTINCT department_id),'{}'::uuid[]) FROM (
 SELECT department_id FROM archive.box WHERE id=target
 UNION SELECT department_id FROM archive.record WHERE id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.record_revision v ON v.record_id=r.id WHERE v.id=target
 UNION SELECT b.department_id FROM archive.box b JOIN archive.box_revision v ON v.box_id=b.id WHERE v.id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.access_grant g ON g.record_id=r.id WHERE g.id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.usage_request u ON u.record_id=r.id WHERE u.id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.export_item i ON i.record_id=r.id WHERE i.usage_id=target
 UNION SELECT owner_department_id FROM archive.borrow_request WHERE id=target OR batch_id=target OR workflow_instance_id=target
 UNION SELECT q.owner_department_id FROM archive.borrow_request q JOIN workflow.step s ON s.instance_id=q.workflow_instance_id WHERE s.id=target
 UNION SELECT r.department_id FROM archive.record r JOIN archive.record_revision v ON v.record_id=r.id LEFT JOIN workflow.step s ON s.instance_id=v.review_instance_id WHERE v.review_instance_id=target OR s.id=target
 UNION SELECT b.department_id FROM archive.box b JOIN archive.box_revision v ON v.box_id=b.id LEFT JOIN workflow.step s ON s.instance_id=v.review_instance_id WHERE v.review_instance_id=target OR s.id=target
 ) departments;
$$;
CREATE FUNCTION ops.audit_folder(target uuid) RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT id FROM (
 SELECT id FROM drive.folder WHERE id=target
 UNION SELECT folder_id FROM drive.document WHERE id=target
 UNION SELECT folder_id FROM drive.delegation WHERE id=target
 UNION SELECT CASE WHEN g.resource_type='FOLDER' THEN g.resource_id ELSE d.folder_id END FROM drive.resource_grant g LEFT JOIN drive.document d ON d.id=g.resource_id WHERE g.id=target
 ) folders LIMIT 1;
$$;
INSERT INTO ops.audit_scope SELECT id,CASE WHEN module='ARCHIVE' THEN ops.audit_departments(resource_id) ELSE '{}'::uuid[] END,CASE WHEN module='DRIVE' THEN ops.audit_folder(resource_id) END FROM ops.audit_event WHERE record_kind<>'IDEMPOTENCY_RECEIPT';
CREATE FUNCTION ops.capture_audit_scope() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.record_kind<>'IDEMPOTENCY_RECEIPT' THEN
 INSERT INTO ops.audit_scope VALUES(NEW.id,CASE WHEN NEW.module='ARCHIVE' THEN ops.audit_departments(NEW.resource_id) ELSE '{}'::uuid[] END,CASE WHEN NEW.module='DRIVE' THEN ops.audit_folder(NEW.resource_id) END);
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER audit_scope_capture AFTER INSERT ON ops.audit_event FOR EACH ROW EXECUTE FUNCTION ops.capture_audit_scope();
CREATE TRIGGER audit_scope_immutable BEFORE UPDATE OR DELETE ON ops.audit_scope FOR EACH ROW EXECUTE FUNCTION ops.reject_audit_rewrite();
CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON ops.audit_event FOR EACH STATEMENT EXECUTE FUNCTION ops.reject_audit_rewrite();
CREATE TRIGGER audit_scope_no_truncate BEFORE TRUNCATE ON ops.audit_scope FOR EACH STATEMENT EXECUTE FUNCTION ops.reject_audit_rewrite();

-- 保留期只允许延长；本阶段不提供销毁入口，档案及其历史证据永久保留。
CREATE TABLE ops.audit_retention (
 id boolean PRIMARY KEY DEFAULT true CHECK(id), interaction_years integer NOT NULL DEFAULT 3 CHECK(interaction_years>=3),
 business_years integer NOT NULL DEFAULT 3 CHECK(business_years>=3), row_version integer NOT NULL DEFAULT 1
);
INSERT INTO ops.audit_retention DEFAULT VALUES;
CREATE FUNCTION ops.retention_extend_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.interaction_years<OLD.interaction_years OR NEW.business_years<OLD.business_years THEN RAISE EXCEPTION 'retention cannot be shortened'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER retention_extend_only BEFORE UPDATE ON ops.audit_retention FOR EACH ROW EXECUTE FUNCTION ops.retention_extend_only();
CREATE TABLE ops.audit_export (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES iam.user_account(id), filters jsonb NOT NULL,
 cutoff timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP+interval '10 minutes'
);
CREATE TABLE ops.ui_interaction (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),request_id uuid NOT NULL,
 page varchar(40) NOT NULL,action varchar(24) NOT NULL,control varchar(24) NOT NULL,occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(action IN ('ACTIVATE','SUBMIT','CHANGE','EXPAND','NAVIGATE','CANCEL')),CHECK(control IN ('BUTTON','LINK','FORM','SELECT','SEARCH','INPUT','DETAILS','DIALOG','ROUTE'))
);
CREATE INDEX ui_interaction_time ON ops.ui_interaction(occurred_at DESC,id);
CREATE INDEX ui_interaction_request ON ops.ui_interaction(request_id);
CREATE TRIGGER ui_interaction_immutable BEFORE UPDATE OR DELETE ON ops.ui_interaction FOR EACH ROW EXECUTE FUNCTION ops.reject_audit_rewrite();
CREATE TRIGGER ui_interaction_no_truncate BEFORE TRUNCATE ON ops.ui_interaction FOR EACH STATEMENT EXECUTE FUNCTION ops.reject_audit_rewrite();

-- 有效使用按文件去重；告警、限流与发件箱同事务，自动重试只投递消息。
CREATE TABLE ops.security_signal (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),kind varchar(24) NOT NULL CHECK(kind IN ('ACCESS_DENIED','BULK_DOWNLOAD','BULK_PRINT')),
 file_id uuid NOT NULL,request_id uuid NOT NULL,occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,kind,file_id,request_id)
);
CREATE INDEX security_signal_window ON ops.security_signal(user_id,kind,occurred_at DESC,file_id);
CREATE TABLE ops.security_alert (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam.user_account(id),kind varchar(24) NOT NULL,
 observed_count integer NOT NULL,threshold_count integer NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','REVIEWING','RESOLVED')),row_version integer NOT NULL DEFAULT 1
);
CREATE INDEX security_alert_window ON ops.security_alert(user_id,kind,created_at DESC);
CREATE INDEX security_alert_queue ON ops.security_alert(state,created_at DESC,id);
CREATE TABLE ops.security_limit (
 user_id uuid NOT NULL REFERENCES iam.user_account(id),kind varchar(24) NOT NULL,blocked_until timestamptz NOT NULL,alert_id uuid NOT NULL REFERENCES ops.security_alert(id),PRIMARY KEY(user_id,kind)
);
CREATE TABLE ops.security_disposition (
 id uuid PRIMARY KEY,alert_id uuid NOT NULL REFERENCES ops.security_alert(id),operator_id uuid NOT NULL REFERENCES iam.user_account(id),
 state varchar(16) NOT NULL,reason varchar(1000) NOT NULL,released boolean NOT NULL,occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER security_disposition_immutable BEFORE UPDATE OR DELETE ON ops.security_disposition FOR EACH ROW EXECUTE FUNCTION ops.reject_audit_rewrite();
CREATE FUNCTION ops.security_signal_add(person uuid,category text,file uuid,request uuid) RETURNS void LANGUAGE plpgsql AS $$
 DECLARE total integer; boundary integer; alert uuid; BEGIN
 IF person IS NULL THEN RETURN; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(person::text||category,61427038));
 INSERT INTO ops.security_signal VALUES(gen_random_uuid(),person,category,file,request,CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;
 SELECT count(DISTINCT file_id) INTO total FROM ops.security_signal WHERE user_id=person AND kind=category AND occurred_at>CURRENT_TIMESTAMP-interval '10 minutes';
 boundary:=CASE category WHEN 'ACCESS_DENIED' THEN 10 WHEN 'BULK_PRINT' THEN 30 ELSE 50 END;
 IF total<boundary THEN RETURN; END IF;
 SELECT id INTO alert FROM ops.security_alert WHERE user_id=person AND kind=category AND created_at>CURRENT_TIMESTAMP-interval '1 hour' ORDER BY created_at DESC LIMIT 1;
 IF alert IS NULL THEN
 alert:=gen_random_uuid();
 INSERT INTO ops.security_alert(id,user_id,kind,observed_count,threshold_count) VALUES(alert,person,category,total,boundary);
 INSERT INTO ops.account_message(id,user_id,kind,channel,expires_at) VALUES(alert,person,category,'DINGTALK',CURRENT_TIMESTAMP+interval '1 day');
 END IF;
 INSERT INTO ops.security_limit VALUES(person,category,CURRENT_TIMESTAMP+interval '10 minutes',alert)
 ON CONFLICT(user_id,kind) DO UPDATE SET blocked_until=EXCLUDED.blocked_until,alert_id=EXCLUDED.alert_id;
 END $$;
CREATE FUNCTION ops.capture_security_signal() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE item uuid; category text; approved_batch boolean; BEGIN
 IF NEW.resource_type='REQUEST' AND NEW.action IN ('PERMISSION_DENIED','RESOURCE_NOT_VISIBLE') AND NEW.result='DENIED' THEN
 PERFORM ops.security_signal_add(NEW.actor_id,'ACCESS_DENIED',NEW.request_id,NEW.request_id);
 ELSIF NEW.module='DRIVE' AND NEW.action='DOWNLOAD_DELIVERED' AND NEW.result='SUCCEEDED' THEN
 PERFORM ops.security_signal_add(NEW.actor_id,'BULK_DOWNLOAD',NEW.resource_id,NEW.request_id);
 ELSIF NEW.module='ARCHIVE' AND NEW.resource_type='USAGE' AND NEW.result='SUCCEEDED' AND NEW.action IN ('PRINT_REQUESTED','EXPORT_DELIVERED') THEN
 SELECT CASE WHEN action='PRINT' THEN 'BULK_PRINT' ELSE 'BULK_DOWNLOAD' END,
 borrow_batch_id IS NOT NULL AND (SELECT count(*) FROM archive.request_item i WHERE i.batch_id=u.borrow_batch_id AND i.action=u.action)=(SELECT count(*) FROM archive.export_item i WHERE i.usage_id=u.id)
 INTO category,approved_batch FROM archive.export_request u WHERE id=NEW.resource_id;
 IF category IS NOT NULL AND NOT COALESCE(approved_batch,false) AND (NEW.action='PRINT_REQUESTED' OR category='BULK_DOWNLOAD') THEN
 FOR item IN SELECT record_id FROM archive.export_item WHERE usage_id=NEW.resource_id LOOP
 PERFORM ops.security_signal_add(NEW.actor_id,category,item,NEW.request_id); END LOOP;
 END IF;
 END IF; RETURN NEW;
 END $$;
CREATE TRIGGER audit_security_signal AFTER INSERT ON ops.audit_event FOR EACH ROW EXECUTE FUNCTION ops.capture_security_signal();
CREATE FUNCTION ops.capture_login_alert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.kind='LOGIN_LOCKS' THEN INSERT INTO ops.security_alert(id,user_id,kind,observed_count,threshold_count) VALUES(NEW.id,NEW.user_id,'LOGIN_LOCKS',3,3); END IF; RETURN NEW;
 END $$;
CREATE TRIGGER account_login_alert AFTER INSERT ON ops.account_message FOR EACH ROW EXECUTE FUNCTION ops.capture_login_alert();

INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type)
 SELECT gen_random_uuid(),m,m||'.'||a,n,'MODULE',a,true,'MODULE' FROM (VALUES
 ('DRIVE','AUDIT_READ','公盘审计查询'),('PLATFORM','AUDIT_EXPORT','平台审计导出'),('DRIVE','AUDIT_EXPORT','公盘审计导出'),('ARCHIVE','AUDIT_EXPORT','档案审计导出'),('PLATFORM','SECURITY_MANAGE','安全告警及日志保留管理')) v(m,a,n);
INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by)
 SELECT r.id,p.id,'COMPANY','ALLOW',(SELECT id FROM iam.user_account ORDER BY created_at LIMIT 1) FROM iam.role r JOIN iam.permission p ON p.module=r.module
 WHERE (r.code IN ('IT_ADMIN','SUPER_ADMIN') AND p.action IN ('AUDIT_EXPORT','SECURITY_MANAGE','AUDIT_READ')) OR (r.module='ARCHIVE' AND r.code='DEPARTMENT_ARCHIVIST' AND p.action='AUDIT_READ') OR (r.module='DRIVE' AND r.code='DEPARTMENT_MANAGER' AND p.action='AUDIT_READ') ON CONFLICT DO NOTHING;

COMMENT ON TABLE ops.audit_scope IS '不可变事件归属快照；未知归属不向部门角色开放';
COMMENT ON COLUMN ops.audit_scope.department_ids IS '事件涉及的全部档案所属部门，多部门事件不得向单部门角色泄露';
COMMENT ON COLUMN ops.audit_scope.folder_id IS '公盘事件发生时目录，查询还需当前有效管理委托';
COMMENT ON TABLE ops.audit_retention IS '最低保留年限，只能延长；本期不自动删除日志';
COMMENT ON TABLE ops.audit_export IS '绑定查询条件和时间截点的短期导出票据，交付时重新鉴权';
COMMENT ON TABLE ops.ui_interaction IS '仅固定页面与控件类型的用户意图，不保存输入值或业务文本';
COMMENT ON TABLE ops.security_signal IS '服务端真实事件窗口，去重键使用文件ID或被拒请求ID';
COMMENT ON TABLE ops.security_alert IS '每账号每类型一小时最多创建一条告警，不自动停用账号';
COMMENT ON TABLE ops.security_limit IS '十分钟临时限流，受限动作与恢复均可核查';
COMMENT ON TABLE ops.security_disposition IS 'IT处理及解除限流的追加证据';
