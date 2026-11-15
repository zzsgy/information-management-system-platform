# -*- coding: utf-8 -*-
"""
app/models/logs.py
==================
本文件定义「审计与日志」相关数据表：
- OperationLog 操作日志（覆盖登录、增删改、查询、下载、审批等关键动作）
- FileAccessLog 文件访问日志（upload/preview/download/delete/version）
说明：关键操作必须可追溯，便于安全审计与故障排查。
"""
from datetime import datetime
from typing import Optional

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin


class OperationLog(Base, TimestampMixin):
    """操作日志表。"""

    __tablename__ = "operation_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="操作者")
    module: Mapped[Optional[str]] = mapped_column(String(50), comment="模块")
    action: Mapped[Optional[str]] = mapped_column(
        String(50), comment="create/update/view/download/approve/delete"
    )
    target_type: Mapped[Optional[str]] = mapped_column(String(50), comment="对象类型")
    target_id: Mapped[Optional[int]] = mapped_column(Integer, comment="对象ID")
    request_id: Mapped[Optional[str]] = mapped_column(String(64), comment="请求链路ID")
    ip_address: Mapped[Optional[str]] = mapped_column(String(64), comment="IP")
    user_agent: Mapped[Optional[str]] = mapped_column(String(500), comment="终端信息")
    detail_json: Mapped[Optional[str]] = mapped_column(Text, comment="操作详情（脱敏）")


class FileAccessLog(Base, TimestampMixin):
    """文件访问日志表。"""

    __tablename__ = "file_access_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="用户")
    archive_file_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="电子文件ID"
    )
    access_type: Mapped[Optional[str]] = mapped_column(
        String(20), comment="upload/preview/download/delete/version"
    )
    ip_address: Mapped[Optional[str]] = mapped_column(String(64), comment="IP")
    access_time: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="访问时间")
    result: Mapped[Optional[str]] = mapped_column(String(20), comment="success/fail")
