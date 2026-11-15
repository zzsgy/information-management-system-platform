# -*- coding: utf-8 -*-
"""
app/schemas/business.py
=======================
本文件定义「借阅 / 损坏 / 销毁 / 公告」相关的请求与响应 Schema。
"""
from datetime import date, datetime
from typing import Optional

from pydantic import BaseModel


# ---------- 借阅 ----------
class BorrowCreate(BaseModel):
    purpose: Optional[str] = None
    expected_return_date: Optional[date] = None
    archive_ids: list[int] = []          # 借阅的档案ID列表
    borrow_mode: str = "electronic"      # physical/electronic/both


class BorrowApprove(BaseModel):
    approve: bool                        # True通过 False驳回
    opinion: Optional[str] = None


class BorrowReturn(BaseModel):
    return_condition: Optional[str] = None


class BorrowOut(BaseModel):
    id: int
    borrow_no: str
    applicant_user_id: int
    status: str
    apply_time: Optional[datetime] = None

    model_config = {"from_attributes": True}


# ---------- 损坏 ----------
class DamageCreate(BaseModel):
    archive_id: int
    damage_type: Optional[str] = None
    damage_desc: Optional[str] = None
    responsible_user_id: Optional[int] = None


class DamageHandle(BaseModel):
    handling_method: Optional[str] = None
    handling_result: Optional[str] = None
    status: str = "resolved"


# ---------- 销毁 ----------
class DestroyCreate(BaseModel):
    reason: Optional[str] = None
    scope_desc: Optional[str] = None
    archive_ids: list[int] = []          # 拟销毁档案ID


class DestroyExecute(BaseModel):
    witness: Optional[str] = None
    method: Optional[str] = None
    archive_ids: list[int] = []          # 实际销毁的档案ID列表


# ---------- 公告 ----------
class NoticeCreate(BaseModel):
    title: str
    content: Optional[str] = None
    notice_type: str = "system"
    receiver_user_ids: list[int] = []    # 接收人，空表示全员
    expire_time: Optional[datetime] = None


class NoticeOut(BaseModel):
    id: int
    title: str
    notice_type: str
    publish_status: str
    publish_time: Optional[datetime] = None

    model_config = {"from_attributes": True}
