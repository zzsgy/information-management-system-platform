# -*- coding: utf-8 -*-
"""
app/db/base.py
==============
本文件定义 ORM 基础类：
- Base：所有数据表的声明基类（SQLAlchemy 2.0 风格）。
- TimestampMixin / SoftDeleteMixin：通用时间戳与软删除字段，
  各模型直接继承即可复用，避免重复定义。
说明：软删除用 deleted_at 字段标记，查询时默认过滤，不物理删除档案数据。
"""
from datetime import datetime

from sqlalchemy import DateTime, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    """所有模型继承的声明基类。"""

    pass


class TimestampMixin:
    """为模型补充 created_at / updated_at 自动时间戳。"""

    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), comment="创建时间"
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime,
        server_default=func.now(),
        onupdate=func.now(),
        comment="更新时间",
    )


class SoftDeleteMixin:
    """为模型补充 deleted_at 软删除标记。"""

    deleted_at: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True, comment="软删除时间，为空表示未删除"
    )
