-- 人工重试开启新一轮，累计尝试保留，不能把历史失败次数清零。
ALTER TABLE ops.account_message ADD COLUMN cycle_attempts integer NOT NULL DEFAULT 0;
UPDATE ops.account_message SET cycle_attempts=attempts;
COMMENT ON COLUMN ops.account_message.cycle_attempts IS '本轮自动尝试次数；人工重试只重置本字段，累计attempts保留';
