# -*- coding: utf-8 -*-
"""
app/schemas/org.py
===================
本文件定义「用户/部门/角色/权限」相关的请求与响应 Schema。
说明：响应模型只包含安全字段（不含密码哈希）；创建用户由服务端生成密码。
"""
from datetime import datetime
from typing import Optional

from pydantic import BaseModel, EmailStr, Field


# ---------- 部门 ----------
class DepartmentCreate(BaseModel):
    dept_code: str
    dept_name: str
    parent_id: Optional[int] = None
    leader_user_id: Optional[int] = None
    sort_order: int = 0
    status: int = 1


class DepartmentUpdate(BaseModel):
    dept_name: Optional[str] = None
    parent_id: Optional[int] = None
    leader_user_id: Optional[int] = None
    sort_order: Optional[int] = None
    status: Optional[int] = None


class DepartmentOut(BaseModel):
    id: int
    dept_code: str
    dept_name: str
    parent_id: Optional[int] = None
    sort_order: int
    status: int

    model_config = {"from_attributes": True}


# ---------- 用户 ----------
class UserCreate(BaseModel):
    username: str
    real_name: str
    dept_id: int
    password: str = Field(default="123456", description="初始密码，首次登录后修改")
    employee_no: Optional[str] = None
    phone: Optional[str] = None
    email: Optional[str] = None
    role_ids: list[int] = []


class UserUpdate(BaseModel):
    real_name: Optional[str] = None
    dept_id: Optional[int] = None
    employee_no: Optional[str] = None
    phone: Optional[str] = None
    email: Optional[str] = None
    account_status: Optional[str] = None
    role_ids: Optional[list[int]] = None


class UserOut(BaseModel):
    id: int
    username: str
    real_name: str
    employee_no: Optional[str] = None
    phone: Optional[str] = None
    email: Optional[str] = None
    dept_id: int
    account_status: str
    created_at: Optional[datetime] = None

    model_config = {"from_attributes": True}


# ---------- 角色 / 权限 ----------
class RoleCreate(BaseModel):
    role_code: str
    role_name: str
    role_level: int = 5
    status: int = 1
    permission_codes: list[str] = []


class RoleOut(BaseModel):
    id: int
    role_code: str
    role_name: str
    role_level: int
    status: int

    model_config = {"from_attributes": True}


class PermissionOut(BaseModel):
    id: int
    perm_code: str
    perm_name: str
    module: Optional[str] = None

    model_config = {"from_attributes": True}
