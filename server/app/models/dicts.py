# -*- coding: utf-8 -*-
"""
app/models/dicts.py
===================
本文件定义「字典与配置」相关数据表：
- SecurityLevel 密级（公开/内部/秘密/机密）
- RetentionPeriod 保管期限
- DictType / DictItem 通用字典（文件类型、库房位置等可维护项）
- NumberingRule / NumberingSequence 自动编号规则与流水号
- SystemParameter 系统参数
说明：编号规则采用模板（如 {DEPT}-{CATEGORY}-{YEAR}-{SEQ4}），流水号在事务内原子递增。
"""
from typing import Optional

from sqlalchemy import (
    Boolean,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin


class SecurityLevel(Base, TimestampMixin):
    """密级表。"""

    __tablename__ = "security_level"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    code: Mapped[str] = mapped_column(String(50), unique=True, comment="编码")
    name: Mapped[str] = mapped_column(String(50), comment="名称")
    level: Mapped[int] = mapped_column(Integer, default=1, comment="等级数字，越大越敏感")
    description: Mapped[Optional[str]] = mapped_column(String(200), comment="说明")


class RetentionPeriod(Base, TimestampMixin):
    """保管期限表。"""

    __tablename__ = "retention_period"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    code: Mapped[str] = mapped_column(String(50), unique=True, comment="编码")
    name: Mapped[str] = mapped_column(String(100), comment="名称（如永久/30年/10年）")
    years: Mapped[Optional[int]] = mapped_column(Integer, comment="年限，0表示永久")


class DictType(Base, TimestampMixin):
    """字典类型（如 file_type / storage_location）。"""

    __tablename__ = "dict_type"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    type_code: Mapped[str] = mapped_column(String(50), unique=True, comment="类型编码")
    type_name: Mapped[str] = mapped_column(String(100), comment="类型名称")


class DictItem(Base, TimestampMixin):
    """字典项（隶属于某个字典类型）。"""

    __tablename__ = "dict_item"
    __table_args__ = (
        UniqueConstraint("type_code", "item_code", name="uq_dict_item"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    type_code: Mapped[str] = mapped_column(String(50), comment="所属字典类型编码")
    item_code: Mapped[str] = mapped_column(String(50), comment="项编码")
    item_name: Mapped[str] = mapped_column(String(100), comment="项名称")
    sort_order: Mapped[int] = mapped_column(Integer, default=0, comment="排序")
    enabled: Mapped[int] = mapped_column(Integer, default=1, comment="1启用0停用")


class NumberingRule(Base, TimestampMixin):
    """编号规则表：定义档案编号如何生成。"""

    __tablename__ = "numbering_rule"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    rule_code: Mapped[str] = mapped_column(String(50), unique=True, comment="规则编码")
    rule_name: Mapped[str] = mapped_column(String(100), comment="规则名称")
    scope_type: Mapped[str] = mapped_column(String(20), default="category", comment="category/dept/global")
    pattern: Mapped[str] = mapped_column(
        String(100), comment="模板（兼容旧规则），如 {DEPT}-{CATEGORY}-{YEAR}-{SEQ4}"
    )
    segments: Mapped[Optional[str]] = mapped_column(
        Text, comment="分段规则 JSON，优先于 pattern。每项含 type(fixed/ref/seq)/value/ref/length/pad"
    )
    reset_cycle: Mapped[str] = mapped_column(String(20), default="YEAR", comment="YEAR/MONTH/NEVER")
    sequence_length: Mapped[int] = mapped_column(Integer, default=4, comment="流水号位数")
    enabled: Mapped[int] = mapped_column(Integer, default=1, comment="1启用0停用")
    priority: Mapped[int] = mapped_column(Integer, default=0, comment="匹配优先级")


class NumberingSequence(Base):
    """流水号表：在事务内原子递增，保证并发不重复。"""

    __tablename__ = "numbering_sequence"
    __table_args__ = (
        UniqueConstraint("rule_id", "scope_key", "period_key", name="uq_seq"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    rule_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("numbering_rule.id"), comment="规则ID"
    )
    scope_key: Mapped[str] = mapped_column(String(100), default="", comment="作用域组合键")
    period_key: Mapped[str] = mapped_column(String(20), default="", comment="周期键，如2026")
    current_value: Mapped[int] = mapped_column(Integer, default=0, comment="当前流水号")


class SystemParameter(Base, TimestampMixin):
    """系统参数键值对。"""

    __tablename__ = "system_parameter"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    param_key: Mapped[str] = mapped_column(String(100), unique=True, comment="参数键")
    param_value: Mapped[Optional[str]] = mapped_column(Text, comment="参数值")
    description: Mapped[Optional[str]] = mapped_column(String(200), comment="说明")
