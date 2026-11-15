-- 运维操作只有技术权限，不授予档案正文读取权；备份恢复由独立维护执行器执行。
CREATE TABLE ops.maintenance_run (
 id uuid PRIMARY KEY,kind varchar(32) NOT NULL CHECK(kind IN ('PROTECTION_CHECK','REPLICA_CHECK','BACKUP','RESTORE_VERIFY')),
 state varchar(24) NOT NULL CHECK(state IN ('QUEUED','WAITING_WINDOW','RUNNING','SUCCEEDED','FAILED','CANCELLED','MANUAL')),
 requester_id uuid NOT NULL REFERENCES iam.user_account(id),auth_version integer NOT NULL,reason varchar(1000) NOT NULL,
 source_backup_id uuid,parent_run_id uuid REFERENCES ops.maintenance_run(id),schedule_key varchar(64) UNIQUE,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,started_at timestamptz,finished_at timestamptz,
 lease_token uuid,lease_until timestamptz,checked_count bigint NOT NULL DEFAULT 0,failed_count bigint NOT NULL DEFAULT 0,total_count bigint NOT NULL DEFAULT 0,cursor_id uuid,
 summary jsonb NOT NULL DEFAULT '{}',error_code varchar(64),row_version integer NOT NULL DEFAULT 1
);
CREATE INDEX maintenance_queue ON ops.maintenance_run(state,created_at,id);
CREATE TABLE ops.backup_point (
 id uuid PRIMARY KEY REFERENCES ops.maintenance_run(id),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,verified_at timestamptz NOT NULL,
 retain_until timestamptz NOT NULL,dump_sha256 char(64) NOT NULL,table_count integer NOT NULL,object_count bigint NOT NULL,file_count bigint NOT NULL,
 scope varchar(32) NOT NULL DEFAULT 'LOCAL_ISOLATED_REHEARSAL',CHECK(retain_until>created_at)
);
ALTER TABLE ops.maintenance_run ADD FOREIGN KEY(source_backup_id) REFERENCES ops.backup_point(id);
CREATE TABLE ops.backup_plan (
 id boolean PRIMARY KEY DEFAULT true CHECK(id),enabled boolean NOT NULL DEFAULT false,hour_shanghai integer NOT NULL DEFAULT 2 CHECK(hour_shanghai BETWEEN 0 AND 23),
 retention_days integer NOT NULL DEFAULT 30 CHECK(retention_days BETWEEN 1 AND 3650),changed_by uuid REFERENCES iam.user_account(id),row_version integer NOT NULL DEFAULT 1
);
INSERT INTO ops.backup_plan DEFAULT VALUES;
CREATE TABLE ops.replica_check (
 object_id uuid PRIMARY KEY REFERENCES storage.object(id),run_id uuid NOT NULL REFERENCES ops.maintenance_run(id),
 state varchar(16) NOT NULL CHECK(state IN ('PASSED','FAILED')),checked_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 byte_size bigint NOT NULL,sha256 char(64) NOT NULL,error_code varchar(64)
);
CREATE INDEX replica_check_status ON ops.replica_check(state,checked_at,object_id);
CREATE TRIGGER backup_point_immutable BEFORE UPDATE OR DELETE ON ops.backup_point FOR EACH ROW EXECUTE FUNCTION ops.reject_audit_rewrite();
INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type)
 SELECT gen_random_uuid(),'PLATFORM','PLATFORM.'||code,name,'MODULE',code,true,'MODULE' FROM (VALUES
 ('OPERATIONS_MANAGE','运行检查与任务管理'),('BACKUP_MANAGE','独立备份管理'),('RESTORE_VERIFY','隔离恢复演练')) p(code,name);
INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by)
 SELECT r.id,p.id,'COMPANY','ALLOW',(SELECT id FROM iam.user_account ORDER BY created_at LIMIT 1) FROM iam.role r JOIN iam.permission p ON p.module=r.module WHERE r.module='PLATFORM' AND r.code='IT_ADMIN' AND p.action IN ('OPERATIONS_MANAGE','BACKUP_MANAGE','RESTORE_VERIFY') ON CONFLICT DO NOTHING;
COMMENT ON TABLE ops.maintenance_run IS '技术运维任务，浏览器不可提供路径、命令或恢复库名';
COMMENT ON TABLE ops.backup_point IS '实际完整核验后登记的独立备份；不等于生产高可用保护点';
COMMENT ON TABLE ops.backup_plan IS '每日排队计划，执行仍需要停机维护窗口，默认保留30天';
COMMENT ON TABLE ops.replica_check IS '逐原件双副本实测结果，不从文件数量推定摘要一致';
COMMENT ON COLUMN ops.maintenance_run.kind IS '保护检查、双副本检查、备份或隔离恢复';
COMMENT ON COLUMN ops.maintenance_run.state IS '等待窗口与正在执行分开显示，过期租约转人工核对';
COMMENT ON COLUMN ops.maintenance_run.auth_version IS '发起人的授权代次，撤权后待执行任务关闭';
COMMENT ON COLUMN ops.maintenance_run.reason IS '申请依据，不应填入档案正文或秘密';
COMMENT ON COLUMN ops.maintenance_run.source_backup_id IS '恢复固定使用的已验证备份';
COMMENT ON COLUMN ops.maintenance_run.parent_run_id IS '重试产生新任务，保留原失败记录与输出目录';
COMMENT ON COLUMN ops.maintenance_run.schedule_key IS '上海日期每日排队去重键';
COMMENT ON COLUMN ops.maintenance_run.lease_token IS '领取随机标识，旧执行器不能确认新租约结果';
COMMENT ON COLUMN ops.maintenance_run.lease_until IS '执行租约；长任务只续本人的有效租约';
COMMENT ON COLUMN ops.maintenance_run.cursor_id IS '副本核验游标，按对象ID分批而非一次加载全库';
COMMENT ON COLUMN ops.maintenance_run.checked_count IS '已实测对象数';
COMMENT ON COLUMN ops.maintenance_run.failed_count IS '实测异常对象数';
COMMENT ON COLUMN ops.maintenance_run.total_count IS '创建任务时点的待验原件数量';
COMMENT ON COLUMN ops.maintenance_run.summary IS '受控技术摘要，不包含数据库口令、本地路径和文件名';
COMMENT ON COLUMN ops.backup_point.retain_until IS '备份保留截止，当前只提醒到期，不自动删除业务或备份';
COMMENT ON COLUMN ops.backup_plan.hour_shanghai IS '上海时区每日排队小时';
COMMENT ON COLUMN ops.backup_plan.retention_days IS '新备份最低保留天数，修改不缩短已有备份期限';
COMMENT ON COLUMN ops.replica_check.sha256 IS '本次核验使用的数据库登记摘要';
COMMENT ON COLUMN ops.replica_check.error_code IS '固定错误代码，不回显磁盘或解析器原始异常';
