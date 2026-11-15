# -*- coding: utf-8 -*-
"""
app/models/business.py
=======================
本文件定义「业务流转」相关数据表：
- BorrowRecord / BorrowDetail 借阅申请与明细（申请→审批→借出→归还）
- DamageRecord 损坏登记（上报→处理→留痕）
- DestroyRequest / DestroyBatch / DestroyDetail 销毁申请/批次/明细（审批后执行留痕）
- Notice / NoticeReceiver 公告与已读回执
说明：所有业务动作均要求可追溯、逻辑删除，销毁必须留痕。审批流相关表见 app/models/approval.py。
"""
from datetime import date, datetime
from typing import Optional

from sqlalchemy import (
    Date,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, SoftDeleteMixin, TimestampMixin


class BorrowRecord(Base, TimestampMixin):
    """借阅主记录。"""

    __tablename__ = "borrow_record"
    __table_args__ = (UniqueConstraint("borrow_no", name="uq_borrow_no"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    borrow_no: Mapped[str] = mapped_column(String(50), unique=True, comment="借阅单号")
    applicant_user_id: Mapped[int] = mapped_column(Integer, comment="申请人")
    applicant_dept_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="申请人部门")
    apply_time: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="申请时间")
    purpose: Mapped[Optional[str]] = mapped_column(String(500), comment="借阅目的")
    expected_return_date: Mapped[Optional[date]] = mapped_column(Date, comment="预计归还")
    actual_return_date: Mapped[Optional[date]] = mapped_column(Date, comment="实际归还")
    status: Mapped[str] = mapped_column(
        String(30), default="pending",
        comment="pending/approved/rejected/borrowed/returned/overdue/cancelled",
    )
    approved_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="审批人")
    approved_at: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="审批时间")


class BorrowDetail(Base, TimestampMixin):
    """借阅明细（一条借阅单可含多份档案）。"""

    __tablename__ = "borrow_detail"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    borrow_id: Mapped[int] = mapped_column(Integer, ForeignKey("borrow_record.id"), comment="借阅单ID")
    archive_id: Mapped[int] = mapped_column(Integer, ForeignKey("archive.id"), comment="档案ID")
    borrow_mode: Mapped[str] = mapped_column(
        String(20), default="electronic", comment="physical/electronic/both"
    )
    checkout_time: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="借出时间")
    return_condition: Mapped[Optional[str]] = mapped_column(String(100), comment="归还状况")
    notes: Mapped[Optional[str]] = mapped_column(String(500), comment="备注")


class DamageRecord(Base, TimestampMixin):
    """档案损坏登记。"""

    __tablename__ = "damage_record"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    archive_id: Mapped[int] = mapped_column(Integer, ForeignKey("archive.id"), comment="档案ID")
    reported_by: Mapped[int] = mapped_column(Integer, comment="上报人")
    reported_at: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="上报时间")
    damage_type: Mapped[Optional[str]] = mapped_column(String(50), comment="损坏类型")
    damage_desc: Mapped[Optional[str]] = mapped_column(Text, comment="损坏描述")
    responsible_user_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="责任人")
    handling_method: Mapped[Optional[str]] = mapped_column(String(100), comment="处理方式")
    handling_result: Mapped[Optional[str]] = mapped_column(Text, comment="处理结果")
    handled_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="处理人")
    handled_at: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="处理时间")
    status: Mapped[str] = mapped_column(
        String(20), default="reported", comment="reported/processing/resolved/closed"
    )


class DestroyRequest(Base, TimestampMixin):
    """销毁申请。"""

    __tablename__ = "destroy_request"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    applicant_user_id: Mapped[int] = mapped_column(Integer, comment="申请人")
    reason: Mapped[Optional[str]] = mapped_column(Text, comment="销毁理由")
    scope_desc: Mapped[Optional[str]] = mapped_column(Text, comment="销毁范围说明")
    status: Mapped[str] = mapped_column(
        String(30), default="pending", comment="pending/approved/rejected/executed"
    )
    approved_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="批准人")
    approved_at: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="批准时间")


class DestroyBatch(Base, TimestampMixin):
    """销毁执行批次（正式销毁留痕）。"""

    __tablename__ = "destroy_batch"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    destroy_request_id: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("destroy_request.id"), nullable=True, comment="来源申请"
    )
    batch_no: Mapped[str] = mapped_column(String(50), comment="销毁批次号")
    executed_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="执行人")
    executed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="执行时间")
    witness: Mapped[Optional[str]] = mapped_column(String(100), comment="监销/见证人")
    method: Mapped[Optional[str]] = mapped_column(String(100), comment="销毁方式")


class DestroyDetail(Base):
    """销毁明细（批次中每份档案的快照）。"""

    __tablename__ = "destroy_detail"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    batch_id: Mapped[int] = mapped_column(Integer, ForeignKey("destroy_batch.id"), comment="批次ID")
    archive_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="档案ID")
    archive_no: Mapped[Optional[str]] = mapped_column(String(100), comment="档案编号快照")
    archive_name: Mapped[Optional[str]] = mapped_column(String(300), comment="档案名称快照")
    original_status: Mapped[Optional[str]] = mapped_column(String(30), comment="销毁前状态")


class Notice(Base, TimestampMixin, SoftDeleteMixin):
    """公告通知。"""

    __tablename__ = "notice"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    title: Mapped[str] = mapped_column(String(200), comment="标题")
    content: Mapped[Optional[str]] = mapped_column(Text, comment="正文")
    publisher_user_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="发布人")
    notice_type: Mapped[str] = mapped_column(String(20), default="system", comment="system/archive/borrow/approval")
    publish_time: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="发布时间")
    expire_time: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="有效期至")
    publish_status: Mapped[str] = mapped_column(String(20), default="draft", comment="draft/published")
    attachment: Mapped[Optional[str]] = mapped_column(String(255), comment="附件路径")


class NoticeReceiver(Base, TimestampMixin):
    """公告接收人及已读状态。"""

    __tablename__ = "notice_receiver"
    __table_args__ = (
        UniqueConstraint("notice_id", "receiver_user_id", name="uq_notice_receiver"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    notice_id: Mapped[int] = mapped_column(Integer, ForeignKey("notice.id"), comment="公告ID")
    receiver_user_id: Mapped[int] = mapped_column(Integer, comment="接收人")
    read_at: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="已读时间")
