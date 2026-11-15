# -*- coding: utf-8 -*-
"""
app/schemas/approval.py
=======================
审批流相关的请求/响应 Schema。
"""
from typing import Optional

from pydantic import BaseModel


class TemplateNodeIn(BaseModel):
    node_order: int
    node_name: str
    approver_type: str            # role/user/permission_level/permission/dept_leader/creator_leader
    approver_value: Optional[str] = None
    cond_field: Optional[str] = None   # 节点适用条件字段（档案词缀）
    cond_op: Optional[str] = None       # startswith/contains/endswith/eq
    cond_value: Optional[str] = None
    is_countersign: int = 0


class TemplateCreate(BaseModel):
    name: str
    biz_type: str                 # borrow / destroy
    description: Optional[str] = None
    priority: int = 0
    enabled: int = 1
    trigger_field: Optional[str] = None   # 模板路由条件字段（档案词缀）
    trigger_op: Optional[str] = None
    trigger_value: Optional[str] = None
    nodes: list[TemplateNodeIn] = []


class TemplateUpdate(BaseModel):
    name: Optional[str] = None
    biz_type: Optional[str] = None
    description: Optional[str] = None
    priority: Optional[int] = None
    enabled: Optional[int] = None
    trigger_field: Optional[str] = None
    trigger_op: Optional[str] = None
    trigger_value: Optional[str] = None
    nodes: Optional[list[TemplateNodeIn]] = None


class ApprovalAct(BaseModel):
    action: str                   # agree / reject
    opinion: Optional[str] = None
