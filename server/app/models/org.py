# -*- coding: utf-8 -*-
"""
app/models/org.py
==================
本文件定义「组织架构与权限」相关的数据表：
- Department 部门（树形结构）
- User 用户（账户，只存密码哈希）
- Role 角色 / Permission 权限（最小授权单元）
- UserRole 用户-角色 / RolePermission 角色-权限（多对多）
- DataScopeRule 数据范围权限规则（ALL/DEPT/SUB_DEPT/SELF/CATEGORY）
说明：采用 RBAC（用户-角色-权限）+ 数据范围控制模型。
"""
from datetime import datetime
from typing import Optional

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, SoftDeleteMixin, TimestampMixin


class Department(Base, TimestampMixin, SoftDeleteMixin):
    """部门表：支持上下级树形结构。"""

    __tablename__ = "department"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    parent_id: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("department.id"), nullable=True, comment="上级部门ID，顶级为空"
    )
    dept_code: Mapped[str] = mapped_column(String(50), unique=True, comment="部门编码")
    dept_name: Mapped[str] = mapped_column(String(100), comment="部门名称")
    leader_user_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="部门负责人用户ID"
    )
    sort_order: Mapped[int] = mapped_column(Integer, default=0, comment="排序号")
    status: Mapped[int] = mapped_column(Integer, default=1, comment="1启用0停用")


class User(Base, TimestampMixin, SoftDeleteMixin):
    """用户表：系统账户，密码仅存哈希。"""

    __tablename__ = "user"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(
        String(64), unique=True, index=True, comment="登录账号"
    )
    password_hash: Mapped[str] = mapped_column(String(255), comment="密码哈希")
    real_name: Mapped[str] = mapped_column(String(50), comment="真实姓名")
    employee_no: Mapped[Optional[str]] = mapped_column(
        String(50), nullable=True, unique=True, comment="工号"
    )
    phone: Mapped[Optional[str]] = mapped_column(String(30), nullable=True, comment="电话")
    email: Mapped[Optional[str]] = mapped_column(String(120), nullable=True, comment="邮箱")
    dept_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("department.id"), comment="主部门ID"
    )
    account_status: Mapped[str] = mapped_column(
        String(20), default="pending", comment="pending/active/rejected/disabled/locked"
    )
    last_login_at: Mapped[Optional[datetime]] = mapped_column(
        DateTime, nullable=True, comment="最后登录时间"
    )


class Role(Base, TimestampMixin):
    """角色表：如超级管理员、档案管理员、普通员工等。"""

    __tablename__ = "role"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    role_code: Mapped[str] = mapped_column(String(50), unique=True, comment="角色编码")
    role_name: Mapped[str] = mapped_column(String(100), comment="角色名称")
    role_level: Mapped[int] = mapped_column(Integer, default=5, comment="级别，数字越小权限越大")
    status: Mapped[int] = mapped_column(Integer, default=1, comment="1启用0停用")


class Permission(Base, TimestampMixin):
    """权限表：最小授权单元，如 archive.read。"""

    __tablename__ = "permission"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    perm_code: Mapped[str] = mapped_column(String(100), unique=True, comment="权限编码")
    perm_name: Mapped[str] = mapped_column(String(100), comment="权限名称")
    module: Mapped[Optional[str]] = mapped_column(String(50), nullable=True, comment="所属模块")
    action: Mapped[Optional[str]] = mapped_column(String(50), nullable=True, comment="动作")


class UserRole(Base):
    """用户-角色关联表（多对多）。"""

    __tablename__ = "user_role"
    __table_args__ = (UniqueConstraint("user_id", "role_id", name="uq_user_role"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("user.id"), comment="用户ID"
    )
    role_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("role.id"), comment="角色ID"
    )
    scope_type: Mapped[Optional[str]] = mapped_column(
        String(20), nullable=True, comment="数据范围类型"
    )
    scope_value: Mapped[Optional[str]] = mapped_column(
        String(100), nullable=True, comment="数据范围取值"
    )


class RolePermission(Base):
    """角色-权限关联表（多对多）。"""

    __tablename__ = "role_permission"
    __table_args__ = (
        UniqueConstraint("role_id", "permission_id", name="uq_role_perm"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    role_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("role.id"), comment="角色ID"
    )
    permission_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("permission.id"), comment="权限ID"
    )


class DataScopeRule(Base, TimestampMixin):
    """数据范围规则表：控制角色能访问哪些数据。"""

    __tablename__ = "data_scope_rule"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    role_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("role.id"), comment="角色ID"
    )
    scope_type: Mapped[str] = mapped_column(
        String(20), comment="ALL/DEPT/SUB_DEPT/SELF/CATEGORY"
    )
    scope_value: Mapped[Optional[str]] = mapped_column(
        String(100), nullable=True, comment="范围取值（如部门ID、分类ID）"
    )
