-- 补充实物字段的具体中文业务含义；不修改已应用的V18及任何历史业务事实。
COMMENT ON TABLE archive.physical_delivery_loan IS '交付操作与实际借出的不可改写关联；一次操作可交付多个盒件';
COMMENT ON COLUMN workflow.route_snapshot.parent_snapshot_id IS '补批前的原路线快照；保留原决定和完整路线继承链';
COMMENT ON COLUMN workflow.route_snapshot.supplement_reason IS '实际期限重算触发补充批准的业务原因';
COMMENT ON COLUMN archive.borrow_batch.form IS '借阅形式：ELECTRONIC为电子使用，PAPER为实际纸质原件';

DO $$
DECLARE
 -- 描述按字段统一定义，同名标识在各表中具有相同的业务引用意义。
 descriptions jsonb := '{
 "id":"不可复用的稳定业务标识",
 "box_id":"原件所属档案盒标识；并发操作先锁此盒",
 "record_id":"对应盒内文件标识；整盒原件为空",
 "object_type":"实物单位：BOX为整盒，RECORD为单件原件",
 "barcode":"服务端生成的唯一原件编号；不授予目录或使用权限",
 "location":"原件实际库房、柜号及层位，仅向合法经办范围返回",
 "quantity":"原件单位数量固定为1；整盒与盒内文件数分开计量",
 "row_version":"并发版本，拒绝覆盖已发生的原件或借还变更",
 "created_at":"本业务记录首次写入数据库的时间",
 "request_id":"批准来源部门子单；续借通过范围关联沿用实际借出",
 "physical_item_id":"固定实际盒件原件标识，不以PDF或电子授权替代",
 "box_revision_id":"送审时冻结的已发布盒目录修订",
 "members":"不可改写的完整文件成员、目录修订、PDF版本及等级快照",
 "label":"提交时按申请人可见范围裁剪的题名",
 "current_request_id":"当前借出期限所依据的最新有效初借或续借子单",
 "borrower_id":"实际借阅人账号标识；本人归还申请与续借均据此核验",
 "delivered_by":"核对实际交付的经办人，必须与借阅人不同",
 "delivery_receipt_id":"库外独立刷盘的交接记录意图凭证标识",
 "actual_delivery_at":"已核验的实际交付时刻，原始累计借出期限从此起算",
 "due_at":"固定公司日历计算的应还截止，逾期不会释放原件占用",
 "actual_quantity":"本笔实际交出的盒或件数量，固定为1",
 "condition":"本次申报或交付时对数量、附件与完好情况的核对说明",
 "state":"服务端维护的本表业务状态，不接受页面直接指定最终结论",
 "returned_at":"完成实际实收核验的归还时刻；未完成实收为空",
 "loan_id":"同一笔实际借出标识；续借和每轮归还沿用该标识",
 "actor_id":"发起并经本人身份核验的实际操作者",
 "operation_id":"操作者本次业务操作幂等号，重试不得更换固定范围",
 "scope_digest":"冻结清单、数量与情况说明的摘要，待补批仅允许更新实际时间",
 "final_digest":"实际交付成功后的完整输入摘要，完成后时间亦不可变更",
 "body_snapshot":"原交付输入快照，保留完整批量范围，不含密码或再验证令牌",
 "delivery_id":"实际交付命令标识，与借出关联表共同证明本次结果",
 "reported_quantity":"借阅人申报交回的盒或件数，0或1，不代表已经实收",
 "note":"借阅人可选的归还补充说明",
 "submitted_at":"本轮归还声明提交时间，不能代替实际收到时间",
 "reviewed_by":"本轮实际实收复核的档案超级管理员，必须与借阅人不同",
 "receipt_id":"本轮实收核验或异常记录的库外意图凭证",
 "actual_received_at":"经办人核验的实际收到时刻，不得早于借出或晚于当前时刻",
 "received_quantity":"复核实际收到的盒或件数；确认归还时必须为1",
 "review_condition":"实收数量、附件、完好或异常情况的实际复核说明",
 "reviewed_at":"本轮实收复核记录提交到数据库的时间",
 "event_type":"只追加的原件位置、交付、归还、异常或续借事件类型",
 "details":"事件业务事实，不含正文、口令、会话令牌或原始文件路径",
 "occurred_at":"该业务事件写入数据库的时间",
 "user_id":"站内事项的明确接收人，不向其他员工开放",
 "kind":"到期、逾期、归还待办或复核结果事项类型",
 "deadline":"此事项固定对应的应还截止，续借后旧截止事项失效",
 "notice_day":"按上海日期计算的本轮通知去重日期"
 }'::jsonb;
 field record;
BEGIN
 FOR field IN SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='archive' AND table_name IN ('physical_item','paper_request_item','physical_loan','paper_loan_scope','physical_delivery','physical_delivery_loan','physical_return','loan_event','physical_notice') LOOP
  IF NOT descriptions ? field.column_name THEN RAISE EXCEPTION 'Missing physical field description: %',field.column_name; END IF;
  EXECUTE format('COMMENT ON COLUMN archive.%I.%I IS %L',field.table_name,field.column_name,descriptions->>field.column_name);
 END LOOP;
END $$;
