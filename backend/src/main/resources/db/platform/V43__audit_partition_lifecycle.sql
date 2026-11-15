-- 停机窗口迁移：全事务复制并逐行核对，失败整体回滚；不改变任何保留期、不销毁历史证据。
SET LOCAL statement_timeout = 0;
LOCK TABLE ops.audit_event, ops.audit_scope, ops.ui_interaction IN ACCESS EXCLUSIVE MODE;
ALTER TABLE ops.audit_event RENAME TO audit_event_before_partition;
ALTER TABLE ops.audit_scope RENAME TO audit_scope_before_partition;
ALTER TABLE ops.ui_interaction RENAME TO ui_interaction_before_partition;

-- PostgreSQL分区唯一键必须包含时间；小型全局登记表继续保证跨月份的事件和幂等键唯一。
CREATE TABLE ops.audit_identity (
 id uuid PRIMARY KEY, event_id uuid NOT NULL UNIQUE, occurred_at timestamptz NOT NULL,
 actor_id uuid, operation_key varchar(256)
);
CREATE UNIQUE INDEX audit_identity_operation ON ops.audit_identity(actor_id,operation_key) WHERE operation_key IS NOT NULL;
CREATE INDEX audit_identity_month ON ops.audit_identity(occurred_at,id);
INSERT INTO ops.audit_identity SELECT id,event_id,occurred_at,actor_id,CASE WHEN record_kind='IDEMPOTENCY_RECEIPT' THEN operation_key END FROM ops.audit_event_before_partition;
CREATE TABLE ops.interaction_identity (id uuid PRIMARY KEY,occurred_at timestamptz NOT NULL);
CREATE INDEX interaction_identity_month ON ops.interaction_identity(occurred_at,id);
INSERT INTO ops.interaction_identity SELECT id,occurred_at FROM ops.ui_interaction_before_partition;

CREATE TABLE ops.audit_month (
 month_start date PRIMARY KEY CHECK(extract(day FROM month_start)=1),
 state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','SEALED','ARCHIVED')),
 sealed_at timestamptz, archived_at timestamptz
);
COMMENT ON TABLE ops.audit_month IS 'UTC自然月；封存后禁止追加，归档仍保持在线查询，不自动分离或删除';
COMMENT ON COLUMN ops.audit_month.month_start IS 'UTC月初日期，同时作为分区及归档批次边界';
COMMENT ON COLUMN ops.audit_month.state IS '可追加、已封存、双副本归档完成';
COMMENT ON COLUMN ops.audit_month.sealed_at IS '等所有在途追加事务完成后封存的时刻';
COMMENT ON COLUMN ops.audit_month.archived_at IS '两份归档均完成摘要及逐行核验的时刻';

CREATE TABLE ops.audit_event (
 LIKE ops.audit_event_before_partition INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING COMMENTS,
 CONSTRAINT audit_event_time_pk PRIMARY KEY(id,occurred_at),
 FOREIGN KEY(actor_service_id) REFERENCES iam.service_identity(id)
) PARTITION BY RANGE(occurred_at);
CREATE INDEX audit_month_module_time ON ops.audit_event(module,occurred_at DESC,id DESC) WHERE record_kind<>'IDEMPOTENCY_RECEIPT';
CREATE INDEX audit_month_actor_time ON ops.audit_event(actor_id,occurred_at DESC,id DESC);
CREATE INDEX audit_month_resource ON ops.audit_event(module,resource_type,resource_id,occurred_at DESC);
CREATE INDEX audit_month_request ON ops.audit_event(request_id);
CREATE INDEX audit_month_operation ON ops.audit_event(actor_id,operation_key) WHERE record_kind='IDEMPOTENCY_RECEIPT';
CREATE INDEX audit_month_export ON ops.audit_event(actor_id,resource_id) WHERE resource_type='AUDIT_EXPORT' AND action='EXPORT_AUDIT' AND result='SUCCEEDED';
CREATE TABLE ops.audit_scope (
 event_id uuid NOT NULL,department_ids uuid[] NOT NULL DEFAULT '{}',folder_id uuid,occurred_at timestamptz NOT NULL,
 CONSTRAINT audit_scope_time_pk PRIMARY KEY(event_id,occurred_at),
 FOREIGN KEY(event_id,occurred_at) REFERENCES ops.audit_event(id,occurred_at)
) PARTITION BY RANGE(occurred_at);
CREATE INDEX audit_month_scope_departments ON ops.audit_scope USING gin(department_ids);
CREATE INDEX audit_month_scope_folder ON ops.audit_scope(folder_id,event_id);
CREATE TABLE ops.ui_interaction (
 LIKE ops.ui_interaction_before_partition INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING COMMENTS,
 CONSTRAINT interaction_time_pk PRIMARY KEY(id,occurred_at),FOREIGN KEY(user_id) REFERENCES iam.user_account(id)
) PARTITION BY RANGE(occurred_at);
CREATE INDEX interaction_month_time ON ops.ui_interaction(occurred_at DESC,id DESC);
CREATE INDEX interaction_month_request ON ops.ui_interaction(request_id);
CREATE INDEX interaction_month_user_time ON ops.ui_interaction(user_id,occurred_at DESC);

-- 标识符只由固定表白名单和数据库日期构造；同月建区使用跨实例事务锁。
CREATE FUNCTION ops.ensure_audit_month(target date) RETURNS void LANGUAGE plpgsql AS $$
DECLARE part text; parent text; start_at timestamptz; end_at timestamptz;
BEGIN
 target:=date_trunc('month',target)::date;
 PERFORM pg_advisory_xact_lock(61427043);
 IF EXISTS(SELECT 1 FROM ops.audit_month WHERE month_start=target) THEN RETURN; END IF;
 start_at:=target::timestamp AT TIME ZONE 'UTC';end_at:=(target+interval '1 month')::timestamp AT TIME ZONE 'UTC';
 FOREACH parent IN ARRAY ARRAY['audit_event','audit_scope','ui_interaction'] LOOP
  part:=parent||'_'||to_char(target,'YYYYMM');
  EXECUTE format('CREATE TABLE ops.%I PARTITION OF ops.%I FOR VALUES FROM (%L) TO (%L)',part,parent,start_at,end_at);
  EXECUTE format('CREATE TRIGGER audit_child_no_truncate BEFORE TRUNCATE ON ops.%I FOR EACH STATEMENT EXECUTE FUNCTION ops.reject_audit_rewrite()',part);
 END LOOP;
 INSERT INTO ops.audit_month(month_start) VALUES(target);
END $$;
DO $$ DECLARE first_month date;last_month date;month_value date;
BEGIN
 SELECT date_trunc('month',min(t))::date INTO first_month FROM (
  SELECT min(occurred_at) t FROM ops.audit_event_before_partition UNION ALL SELECT min(occurred_at) FROM ops.ui_interaction_before_partition
 ) dates;
 SELECT greatest(date_trunc('month',CURRENT_TIMESTAMP)+interval '12 months',max(t))::date INTO last_month FROM (
  SELECT max(occurred_at) t FROM ops.audit_event_before_partition UNION ALL SELECT max(occurred_at) FROM ops.ui_interaction_before_partition
 ) dates;
 FOR month_value IN SELECT generate_series(COALESCE(first_month,date_trunc('month',CURRENT_TIMESTAMP)::date),last_month,interval '1 month')::date LOOP
  PERFORM ops.ensure_audit_month(month_value);
 END LOOP;
END $$;
INSERT INTO ops.audit_event SELECT * FROM ops.audit_event_before_partition;
INSERT INTO ops.audit_scope SELECT s.*,e.occurred_at FROM ops.audit_scope_before_partition s JOIN ops.audit_event_before_partition e ON e.id=s.event_id;
INSERT INTO ops.ui_interaction SELECT * FROM ops.ui_interaction_before_partition;
-- EXCEPT逐字段比较，不能只用总条数相同宣称无损；先核对再移除同事务中的旧物理表。
DO $$ BEGIN
 IF EXISTS(SELECT * FROM ops.audit_event_before_partition EXCEPT SELECT * FROM ops.audit_event)
 OR (SELECT count(*) FROM ops.audit_event_before_partition)<>(SELECT count(*) FROM ops.audit_event)
 OR EXISTS(SELECT s.*,e.occurred_at FROM ops.audit_scope_before_partition s JOIN ops.audit_event_before_partition e ON e.id=s.event_id EXCEPT SELECT * FROM ops.audit_scope)
 OR (SELECT count(*) FROM ops.audit_scope_before_partition)<>(SELECT count(*) FROM ops.audit_scope)
 OR EXISTS(SELECT * FROM ops.ui_interaction_before_partition EXCEPT SELECT * FROM ops.ui_interaction)
 OR (SELECT count(*) FROM ops.ui_interaction_before_partition)<>(SELECT count(*) FROM ops.ui_interaction)
 THEN RAISE EXCEPTION 'audit migration content mismatch'; END IF;
END $$;
DROP TABLE ops.audit_scope_before_partition;
DROP TABLE ops.audit_event_before_partition;
DROP TABLE ops.ui_interaction_before_partition;

-- FOR SHARE与封存UPDATE互斥，避免在途追加在归档快照之后才提交。
CREATE FUNCTION ops.audit_month_open() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE current_state text;
BEGIN
 SELECT state INTO current_state FROM ops.audit_month WHERE month_start=date_trunc('month',NEW.occurred_at AT TIME ZONE 'UTC')::date FOR SHARE;
 IF current_state IS DISTINCT FROM 'OPEN' THEN RAISE EXCEPTION 'audit month is sealed or unavailable'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION ops.audit_global_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO ops.audit_identity VALUES(NEW.id,NEW.event_id,NEW.occurred_at,NEW.actor_id,CASE WHEN NEW.record_kind='IDEMPOTENCY_RECEIPT' THEN NEW.operation_key END);
 RETURN NEW;
END $$;
CREATE FUNCTION ops.interaction_global_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO ops.interaction_identity VALUES(NEW.id,NEW.occurred_at) ON CONFLICT(id) DO NOTHING;
 IF NOT FOUND THEN RETURN NULL; END IF; RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION ops.capture_audit_scope() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.record_kind<>'IDEMPOTENCY_RECEIPT' THEN
  INSERT INTO ops.audit_scope(event_id,department_ids,folder_id,occurred_at) VALUES(NEW.id,CASE WHEN NEW.module='ARCHIVE' THEN ops.audit_departments(NEW.resource_id) ELSE '{}'::uuid[] END,CASE WHEN NEW.module='DRIVE' THEN ops.audit_folder(NEW.resource_id) END,NEW.occurred_at);
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER audit_00_month_open BEFORE INSERT ON ops.audit_event FOR EACH ROW EXECUTE FUNCTION ops.audit_month_open();
CREATE TRIGGER audit_01_global_identity BEFORE INSERT ON ops.audit_event FOR EACH ROW EXECUTE FUNCTION ops.audit_global_identity();
CREATE TRIGGER audit_scope_capture AFTER INSERT ON ops.audit_event FOR EACH ROW EXECUTE FUNCTION ops.capture_audit_scope();
CREATE TRIGGER audit_security_signal AFTER INSERT ON ops.audit_event FOR EACH ROW EXECUTE FUNCTION ops.capture_security_signal();
CREATE TRIGGER audit_scope_month_open BEFORE INSERT ON ops.audit_scope FOR EACH ROW EXECUTE FUNCTION ops.audit_month_open();
CREATE TRIGGER interaction_00_month_open BEFORE INSERT ON ops.ui_interaction FOR EACH ROW EXECUTE FUNCTION ops.audit_month_open();
CREATE TRIGGER interaction_01_identity BEFORE INSERT ON ops.ui_interaction FOR EACH ROW EXECUTE FUNCTION ops.interaction_global_identity();
DO $$ DECLARE target text; BEGIN
 FOREACH target IN ARRAY ARRAY['audit_event','audit_scope','ui_interaction','audit_identity','interaction_identity'] LOOP
  EXECUTE format('CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON ops.%I FOR EACH ROW EXECUTE FUNCTION ops.reject_audit_rewrite()',target);
  EXECUTE format('CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON ops.%I FOR EACH STATEMENT EXECUTE FUNCTION ops.reject_audit_rewrite()',target);
 END LOOP;
END $$;
COMMENT ON TABLE ops.audit_identity IS '跨月事件编号与幂等键全局去重，只追加且纳入备份';
COMMENT ON TABLE ops.interaction_identity IS '跨月界面意图去重，只追加且纳入备份';
COMMENT ON COLUMN ops.audit_scope.occurred_at IS '与原事实一致的分区时间，查询不得只凭事件编号跨月联接';

CREATE TABLE ops.audit_page_cursor (
 id uuid PRIMARY KEY,query_id uuid NOT NULL REFERENCES ops.audit_export(id) ON DELETE CASCADE,
 anchor_time timestamptz,anchor_id uuid,CHECK((anchor_time IS NULL)=(anchor_id IS NULL))
);
CREATE INDEX audit_cursor_ticket ON ops.audit_page_cursor(query_id);
COMMENT ON TABLE ops.audit_page_cursor IS '随机游标绑定本人查询票据及稳定排序锚点；十分钟后随票据回收';

CREATE TABLE ops.audit_archive_run (
 id uuid PRIMARY KEY,month_start date NOT NULL REFERENCES ops.audit_month(month_start),requester_id uuid NOT NULL REFERENCES iam.user_account(id),
 auth_version integer NOT NULL,state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','ARCHIVED','FAILED','MANUAL')),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,finished_at timestamptz,lease_token uuid,lease_until timestamptz,
 sha256 char(64),byte_size bigint,row_counts jsonb,error_code varchar(64)
);
CREATE UNIQUE INDEX audit_archive_active_month ON ops.audit_archive_run(month_start) WHERE state IN ('QUEUED','RUNNING','ARCHIVED');
COMMENT ON TABLE ops.audit_archive_run IS '封存月份的双副本归档任务；核验失败保留原在线记录，不自动删除';
COMMENT ON COLUMN ops.audit_archive_run.row_counts IS '归档文件逐行解压复核后的各表数量';
COMMENT ON COLUMN ops.audit_archive_run.sha256 IS '两份压缩归档共同的SHA256摘要，不包含本地私有路径';

CREATE TABLE ops.audit_capacity_sample (
 id uuid PRIMARY KEY,sampled_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,total_bytes bigint NOT NULL,
 available_bytes bigint NOT NULL,filesystem_total_bytes bigint NOT NULL,estimated_rows bigint NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('NORMAL','WARNING','CRITICAL')),details jsonb NOT NULL
);
CREATE INDEX audit_capacity_time ON ops.audit_capacity_sample(sampled_at DESC);
COMMENT ON TABLE ops.audit_capacity_sample IS '后台按小时采样表及索引容量、数据库所在卷剩余量；估算条数不替代精确审计统计';
