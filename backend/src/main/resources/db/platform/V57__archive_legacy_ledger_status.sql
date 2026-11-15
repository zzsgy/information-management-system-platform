-- 记录旧台账补录进度；已有盒初始为待补录。
ALTER TABLE archive.box ADD COLUMN legacy_ledger_status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (legacy_ledger_status IN ('PENDING','COMPLETED','NOT_REQUIRED'));
COMMENT ON COLUMN archive.box.legacy_ledger_status IS '原台账补录状态：待补录、已补录或无需补录，由目录维护人核对后标记';
