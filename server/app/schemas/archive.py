# -*- coding: utf-8 -*-
"""
app/schemas/archive.py
======================
本文件定义「档案 / 分类 / 电子文件」相关的请求与响应 Schema。
"""
from datetime import date, datetime
from typing import Optional

from pydantic import BaseModel, Field


# ---------- 分类 ----------
class CategoryCreate(BaseModel):
    category_code: str
    category_name: str
    parent_id: Optional[int] = None
    archive_level: int = 1
    default_security_level_id: Optional[int] = None
    default_retention_id: Optional[int] = None
    numbering_rule_id: Optional[int] = None
    sort_order: int = 0
    status: int = 1


class CategoryUpdate(BaseModel):
    category_code: Optional[str] = None
    category_name: Optional[str] = None
    parent_id: Optional[int] = None
    archive_level: Optional[int] = None
    default_security_level_id: Optional[int] = None
    default_retention_id: Optional[int] = None
    numbering_rule_id: Optional[int] = None
    sort_order: Optional[int] = None
    status: Optional[int] = None


class CategoryOut(BaseModel):
    id: int
    category_code: str
    category_name: str
    parent_id: Optional[int] = None
    archive_level: int
    sort_order: int
    status: int

    model_config = {"from_attributes": True}


# ---------- 档案 ----------
class ArchiveCreate(BaseModel):
    archive_name: str
    category_id: int
    dept_id: int
    archive_no: Optional[str] = None
    security_level_id: Optional[int] = None
    retention_id: Optional[int] = None
    archive_year: Optional[int] = None
    formation_date: Optional[date] = None
    filing_date: Optional[date] = None
    creator_user_id: Optional[int] = None
    archiver_user_id: Optional[int] = None
    keyword: Optional[str] = None
    summary: Optional[str] = None
    physical_location: Optional[str] = None
    volume_no: Optional[str] = None
    page_count: int = 0
    # ---- #4 档案清单字段 ----
    file_type: Optional[str] = None
    owner_name: Optional[str] = None
    cabinet_no: Optional[str] = None
    column_no: Optional[str] = None
    layer_no: Optional[str] = None
    storage_type: Optional[str] = None
    copy_count: int = 1
    archive_date: Optional[date] = None
    validity_months: Optional[int] = None
    remark: Optional[str] = None


class ArchiveUpdate(BaseModel):
    archive_name: Optional[str] = None
    category_id: Optional[int] = None
    dept_id: Optional[int] = None
    security_level_id: Optional[int] = None
    retention_id: Optional[int] = None
    keyword: Optional[str] = None
    summary: Optional[str] = None
    physical_location: Optional[str] = None
    volume_no: Optional[str] = None
    page_count: Optional[int] = None
    status: Optional[str] = None
    # ---- #4 档案清单字段 ----
    file_type: Optional[str] = None
    owner_name: Optional[str] = None
    cabinet_no: Optional[str] = None
    column_no: Optional[str] = None
    layer_no: Optional[str] = None
    storage_type: Optional[str] = None
    copy_count: Optional[int] = None
    archive_date: Optional[date] = None
    validity_months: Optional[int] = None
    remark: Optional[str] = None


class ArchiveOut(BaseModel):
    id: int
    archive_no: str
    archive_name: str
    category_id: int
    dept_id: int
    dept_name: Optional[str] = None
    category_name: Optional[str] = None
    security_level_id: Optional[int] = None
    security_name: Optional[str] = None
    archive_year: Optional[int] = None
    physical_location: Optional[str] = None
    status: str
    file_count: int
    # ---- #4 档案清单字段 ----
    file_type: Optional[str] = None
    owner_name: Optional[str] = None
    cabinet_no: Optional[str] = None
    column_no: Optional[str] = None
    layer_no: Optional[str] = None
    storage_type: Optional[str] = None
    copy_count: int = 1
    archive_date: Optional[date] = None
    validity_months: Optional[int] = None
    expiry_date: Optional[date] = None
    remark: Optional[str] = None
    latest_update: Optional[dict] = None
    created_at: Optional[datetime] = None

    model_config = {"from_attributes": True}


# ---------- 电子文件 ----------
class ArchiveFileOut(BaseModel):
    id: int
    archive_id: int
    file_name: str
    file_ext: Optional[str] = None
    file_size: int
    version_no: int
    is_current: int
    uploaded_at: Optional[datetime] = None

    model_config = {"from_attributes": True}
