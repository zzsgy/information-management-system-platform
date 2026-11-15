# -*- coding: utf-8 -*-
"""
app/schemas/dicts.py
====================
字典项（DictItem）相关的请求与响应 Schema。
"""
from typing import Optional

from pydantic import BaseModel


class DictItemOut(BaseModel):
    id: int
    type_code: str
    item_code: str
    item_name: str
    sort_order: int
    enabled: int

    model_config = {"from_attributes": True}


class DictItemCreate(BaseModel):
    type_code: str
    item_code: str
    item_name: str
    sort_order: int = 0
    enabled: int = 1


class DictItemUpdate(BaseModel):
    item_name: Optional[str] = None
    sort_order: Optional[int] = None
    enabled: Optional[int] = None


# ---------- 编号规则 ----------
class Segment(BaseModel):
    """编号规则中的一段：固定文本 / 引用字段 / 流水号。"""
    type: str                       # fixed / ref / seq
    value: Optional[str] = ""      # fixed 时的固定文本
    ref: Optional[str] = ""        # ref 时的引用字段（dept_code/category_code/retention_code/year/month/day）
    length: int = 0                # 目标长度，<=0 表示不限制
    pad: Optional[str] = "0"       # 不足时左侧填充字符


class NumberingRuleOut(BaseModel):
    id: int
    rule_code: str
    rule_name: str
    scope_type: str
    pattern: str
    segments: Optional[object] = None
    reset_cycle: str
    sequence_length: int
    enabled: int
    priority: int

    model_config = {"from_attributes": True}


class NumberingRuleCreate(BaseModel):
    rule_code: str
    rule_name: str
    scope_type: str = "global"
    pattern: str = "{DEPT}-{CATEGORY}-{YEAR}-{SEQ4}"
    segments: Optional[list] = None
    reset_cycle: str = "YEAR"
    sequence_length: int = 4
    enabled: int = 1
    priority: int = 0


class NumberingRuleUpdate(BaseModel):
    rule_name: Optional[str] = None
    scope_type: Optional[str] = None
    pattern: Optional[str] = None
    segments: Optional[list] = None
    reset_cycle: Optional[str] = None
    sequence_length: Optional[int] = None
    enabled: Optional[int] = None
    priority: Optional[int] = None
