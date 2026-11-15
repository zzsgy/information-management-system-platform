# -*- coding: utf-8 -*-
"""
app/services/business.py
========================
本文件实现「借阅 / 损坏 / 销毁 / 公告」的业务逻辑：
- 借阅：申请→审批→借出→归还（含状态流转与明细）。
- 损坏：登记→处理→留痕。
- 销毁：申请→审批→执行批次（强制留痕，逻辑标记档案为已销毁）。
- 公告：发布→接收人/已读。
说明：所有写操作均不在此 commit，由 API 层统一提交，保证事务一致。
"""
from datetime import date, datetime

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.models.business import (
    BorrowDetail,
    BorrowRecord,
    DamageRecord,
    DestroyBatch,
    DestroyDetail,
    DestroyRequest,
    Notice,
    NoticeReceiver,
)
from app.models.archive import Archive
from app.models.approval import ApprovalFlow
from app.services import approval as approval_svc


# ---------------- 借阅 ----------------
def create_borrow(db: Session, applicant_id: int, data) -> BorrowRecord:
    """发起借阅申请，生成借阅单与明细。"""
    borrow_no = f"BY-{datetime.now().strftime('%Y%m%d%H%M%S%f')}"
    rec = BorrowRecord(
        borrow_no=borrow_no,
        applicant_user_id=applicant_id,
        applicant_dept_id=None,
        apply_time=datetime.now(),
        purpose=data.purpose,
        expected_return_date=data.expected_return_date,
        status="pending",
    )
    db.add(rec)
    db.flush()
    for aid in data.archive_ids:
        db.add(
            BorrowDetail(
                borrow_id=rec.id,
                archive_id=aid,
                borrow_mode=data.borrow_mode,
            )
        )
    # 依据档案信息词缀匹配审批模板，实例化审批流程
    first_archive = db.get(Archive, data.archive_ids[0]) if data.archive_ids else None
    ctx = approval_svc.build_archive_ctx(db, first_archive) if first_archive else {}
    template = approval_svc.resolve_template(db, "borrow", ctx) if first_archive else None
    summary = approval_svc.build_summary(db, data.archive_ids)
    approval_svc.create_flow(
        db, biz_type="borrow", biz_id=rec.id, applicant_id=applicant_id,
        title=f"借阅申请 {rec.borrow_no}", archive_ctx=ctx,
        archive=first_archive, template=template, archive_summary=summary,
    )
    return rec


def approve_borrow(db: Session, borrow_id: int, approver_id: int, approve: bool,
                   opinion=None, is_admin: bool = False):
    """审批借阅：对当前审批节点执行动作，流转结束后更新借阅单状态。"""
    rec = db.get(BorrowRecord, borrow_id)
    if not rec:
        raise ValueError("借阅单不存在")
    flow = db.query(ApprovalFlow).filter_by(biz_type="borrow", biz_id=borrow_id).first()
    if not flow:
        # 兜底：无流程直接按原逻辑处理
        rec.status = "approved" if approve else "rejected"
        rec.approved_by = approver_id
        rec.approved_at = datetime.now()
        return rec
    flow = approval_svc.act_on_flow(
        db, flow, operator_user_id=approver_id,
        action="agree" if approve else "reject", opinion=opinion, is_admin=is_admin,
    )
    if flow.status == "approved":
        rec.status = "approved"
        rec.approved_by = approver_id
        rec.approved_at = datetime.now()
    elif flow.status == "rejected":
        rec.status = "rejected"
        rec.approved_by = approver_id
        rec.approved_at = datetime.now()
    return rec


def return_borrow(db: Session, borrow_id: int, condition: str | None = None):
    """归还借阅：置为 returned 并记录实际归还日期。"""
    rec = db.get(BorrowRecord, borrow_id)
    if not rec:
        raise ValueError("借阅单不存在")
    rec.status = "returned"
    rec.actual_return_date = date.today()
    for d in db.query(BorrowDetail).filter_by(borrow_id=borrow_id):
        d.return_condition = condition
    return rec


# ---------------- 损坏 ----------------
def create_damage(db: Session, reporter_id: int, data) -> DamageRecord:
    """登记损坏。"""
    rec = DamageRecord(
        archive_id=data.archive_id,
        reported_by=reporter_id,
        reported_at=datetime.now(),
        damage_type=data.damage_type,
        damage_desc=data.damage_desc,
        responsible_user_id=data.responsible_user_id,
        status="reported",
    )
    db.add(rec)
    db.flush()
    return rec


def handle_damage(db: Session, damage_id: int, data) -> DamageRecord:
    """处理损坏并记录结果。"""
    rec = db.get(DamageRecord, damage_id)
    if not rec:
        raise ValueError("损坏记录不存在")
    rec.handling_method = data.handling_method
    rec.handling_result = data.handling_result
    rec.status = data.status
    rec.handled_at = datetime.now()
    return rec


# ---------------- 销毁 ----------------
def create_destroy(db: Session, applicant_id: int, data) -> DestroyRequest:
    """发起销毁申请，并依据档案词缀实例化审批流程。"""
    req = DestroyRequest(
        applicant_user_id=applicant_id,
        reason=data.reason,
        scope_desc=data.scope_desc,
        status="pending",
    )
    db.add(req)
    db.flush()
    first_archive = db.get(Archive, data.archive_ids[0]) if data.archive_ids else None
    ctx = approval_svc.build_archive_ctx(db, first_archive) if first_archive else {}
    template = approval_svc.resolve_template(db, "destroy", ctx) if first_archive else None
    summary = approval_svc.build_summary(db, data.archive_ids)
    approval_svc.create_flow(
        db, biz_type="destroy", biz_id=req.id, applicant_id=applicant_id,
        title=f"销毁申请 #{req.id}", archive_ctx=ctx,
        archive=first_archive, template=template, archive_summary=summary,
    )
    return req


def approve_destroy(db: Session, destroy_id: int, approver_id: int, approve: bool,
                   opinion=None, is_admin: bool = False):
    """审批销毁申请。"""
    req = db.get(DestroyRequest, destroy_id)
    if not req:
        raise ValueError("销毁申请不存在")
    flow = db.query(ApprovalFlow).filter_by(biz_type="destroy", biz_id=destroy_id).first()
    if not flow:
        req.status = "approved" if approve else "rejected"
        req.approved_by = approver_id
        req.approved_at = datetime.now()
        return req
    flow = approval_svc.act_on_flow(
        db, flow, operator_user_id=approver_id,
        action="agree" if approve else "reject", opinion=opinion, is_admin=is_admin,
    )
    if flow.status == "approved":
        req.status = "approved"
        req.approved_by = approver_id
        req.approved_at = datetime.now()
    elif flow.status == "rejected":
        req.status = "rejected"
        req.approved_by = approver_id
        req.approved_at = datetime.now()
    return req


def execute_destroy(db: Session, destroy_id: int, executor_id: int, data, archive_ids: list[int]):
    """执行销毁：生成批次并强制留痕，逻辑标记档案为已销毁（不物理删库）。"""
    req = db.get(DestroyRequest, destroy_id)
    if not req or req.status != "approved":
        raise ValueError("销毁申请未批准，无法执行")
    batch_no = f"DS-{datetime.now().strftime('%Y%m%d%H%M%S%f')}"
    batch = DestroyBatch(
        destroy_request_id=destroy_id,
        batch_no=batch_no,
        executed_by=executor_id,
        executed_at=datetime.now(),
        witness=data.witness,
        method=data.method,
    )
    db.add(batch)
    db.flush()
    for aid in archive_ids:
        arch = db.get(Archive, aid)
        db.add(
            DestroyDetail(
                batch_id=batch.id,
                archive_id=aid,
                archive_no=arch.archive_no if arch else None,
                archive_name=arch.archive_name if arch else None,
                original_status=arch.status if arch else None,
            )
        )
        if arch:
            arch.status = "destroyed"
            arch.deleted_at = datetime.now()  # 逻辑删除（留痕）
    req.status = "executed"
    return batch


# ---------------- 公告 ----------------
def publish_notice(db: Session, publisher_id: int, data) -> Notice:
    """发布公告，指定接收人（空列表表示全员）。"""
    notice = Notice(
        title=data.title,
        content=data.content,
        publisher_user_id=publisher_id,
        notice_type=data.notice_type,
        publish_time=datetime.now(),
        expire_time=data.expire_time,
        publish_status="published",
    )
    db.add(notice)
    db.flush()
    receivers = data.receiver_user_ids or []
    if receivers:
        for uid in receivers:
            db.add(NoticeReceiver(notice_id=notice.id, receiver_user_id=uid))
    else:
        # 全员：简单起见写入一条标记，查询时按发布范围展示
        db.add(NoticeReceiver(notice_id=notice.id, receiver_user_id=0))
    return notice
