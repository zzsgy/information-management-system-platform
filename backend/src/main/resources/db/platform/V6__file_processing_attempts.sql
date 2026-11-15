-- 检测进程每次尝试单独留痕，输出结果不能覆盖原有失败证据。
CREATE TABLE ops.job_attempt (
 id uuid PRIMARY KEY,job_id uuid NOT NULL REFERENCES ops.job(id),attempt_no integer NOT NULL CHECK(attempt_no>0),
 worker_identity_id uuid NOT NULL REFERENCES iam.service_identity(id),started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ended_at timestamptz,state varchar(32) NOT NULL DEFAULT 'RUNNING' CHECK(state IN ('RUNNING','SUCCEEDED','FAILED','ABANDONED')),
 result_ref varchar(256),sanitized_error text,lease_token varchar(255) NOT NULL,UNIQUE(job_id,attempt_no)
);
COMMENT ON TABLE ops.job_attempt IS '文件处理服务每次执行及租约的独立历史';
COMMENT ON COLUMN ops.job_attempt.id IS '尝试标识';
COMMENT ON COLUMN ops.job_attempt.job_id IS '固定原任务';
COMMENT ON COLUMN ops.job_attempt.attempt_no IS '任务尝试序号';
COMMENT ON COLUMN ops.job_attempt.worker_identity_id IS '实际受限处理服务身份';
COMMENT ON COLUMN ops.job_attempt.started_at IS '本次开始时间';
COMMENT ON COLUMN ops.job_attempt.ended_at IS '本次完成或失败时间';
COMMENT ON COLUMN ops.job_attempt.state IS '本次执行状态';
COMMENT ON COLUMN ops.job_attempt.result_ref IS '脱敏结果引用';
COMMENT ON COLUMN ops.job_attempt.sanitized_error IS '不含路径或秘密的失败代码';
COMMENT ON COLUMN ops.job_attempt.lease_token IS '固定租约令牌摘要';
CREATE INDEX ix_job_attempt_worker ON ops.job_attempt(worker_identity_id,state);
