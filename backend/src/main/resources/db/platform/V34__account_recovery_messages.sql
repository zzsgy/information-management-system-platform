-- 账号找回与技术消息独立于档案保护状态，避免档案维护期间无法恢复账号。
CREATE TABLE iam.password_recovery (
 id uuid PRIMARY KEY, user_id uuid REFERENCES iam.user_account(id), account_bucket varchar(64) NOT NULL,
 code_hash varchar(64), auth_version integer, email_snapshot varchar(254), failures integer NOT NULL DEFAULT 0,
 sent_at timestamptz, expires_at timestamptz NOT NULL, closed_at timestamptz, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX recovery_account_time ON iam.password_recovery(account_bucket,created_at DESC);
CREATE TABLE ops.recovery_send_event(id uuid PRIMARY KEY,account_bucket varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX recovery_send_window ON ops.recovery_send_event(account_bucket,created_at DESC);
CREATE TABLE ops.account_message (
 id uuid PRIMARY KEY, user_id uuid REFERENCES iam.user_account(id), challenge_id uuid, kind varchar(32) NOT NULL,
 channel varchar(16) NOT NULL, recipient varchar(254), payload text, state varchar(16) NOT NULL DEFAULT 'PENDING',
 attempts integer NOT NULL DEFAULT 0, next_run_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token uuid, lease_until timestamptz, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 sent_at timestamptz, last_error varchar(64), row_version integer NOT NULL DEFAULT 1,
 CHECK(state IN ('PENDING','RUNNING','RETRY','SENT','MANUAL','CANCELLED')), CHECK(channel IN ('MAIL','DINGTALK'))
);
CREATE INDEX account_message_due ON ops.account_message(next_run_at,id) WHERE state IN ('PENDING','RETRY','RUNNING');
CREATE INDEX account_message_user_time ON ops.account_message(user_id,kind,created_at DESC);
CREATE TABLE ops.login_lock_event (id uuid PRIMARY KEY, account_bucket varchar(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX login_lock_window ON ops.login_lock_event(account_bucket,created_at DESC);
COMMENT ON TABLE iam.password_recovery IS '匿名找回流程；同一流程重发不重置失败次数，验证码仅保存带秘密键的摘要';
COMMENT ON TABLE ops.account_message IS '账号凭据和技术告警事务发件箱；凭据AES-GCM加密，发送或过期后清除密文';
COMMENT ON TABLE ops.login_lock_event IS '账号一小时累计锁定事件；不记录工号、密码或来源原文';
COMMENT ON TABLE ops.recovery_send_event IS '发码限次事实；含匿名账号相同计数，不记录验证码';
DO $$ DECLARE f record; labels jsonb := '{"id":"服务器生成的稳定标识","user_id":"关联账号；未知账号流程为空","account_bucket":"不可枚举的账号摘要","code_hash":"绑定流程和随机码的HMAC摘要","auth_version":"发码时账号安全代次","email_snapshot":"发码时绑定公司邮箱，变更即失效","failures":"流程累计错误次数，重发不清零","sent_at":"最近发码或通道接受时刻","expires_at":"到期后不可验证或投递","closed_at":"已消费、作废或失败耗尽时间","created_at":"实际创建时间","challenge_id":"临时密码或找回流程引用","kind":"受控消息类型","channel":"邮件或钉钉通道","recipient":"绑定公司邮箱，不由匿名请求指定","payload":"短期AES-GCM密文，不存明文凭据","state":"真实投递状态","attempts":"累计发送尝试次数","next_run_at":"最早重试时间","lease_token":"当前领取者租约","lease_until":"领取截止时间","last_error":"不含外部响应和秘密的错误代码","row_version":"管理重试并发版本"}'::jsonb;
BEGIN FOR f IN SELECT table_schema,table_name,column_name FROM information_schema.columns WHERE (table_schema='iam' AND table_name='password_recovery') OR (table_schema='ops' AND table_name IN ('account_message','login_lock_event','recovery_send_event')) LOOP
 EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',f.table_schema,f.table_name,f.column_name,labels->>f.column_name);
END LOOP; END $$;
