# -*- coding: utf-8 -*-
"""
app/services/log.py
====================
本文件提供「操作日志 / 文件访问日志」写入服务。
说明：关键动作（登录、增删改、查阅、下载、审批）统一调用本服务落库，
      满足审计可追溯要求。detail 中不含敏感明文。
"""
import uuid
from datetime import datetime

from sqlalchemy.orm import Session

from app.models.logs import FileAccessLog, OperationLog


def write_operation(
    db: Session,
    *,
    user_id: int | None,
    module: str,
    action: str,
    target_type: str | None = None,
    target_id: int | None = None,
    ip: str | None = None,
    user_agent: str | None = None,
    detail: str | None = None,
):
    """写入一条操作日志（不立即 commit，由调用方统一提交）。"""
    log = OperationLog(
        user_id=user_id,
        module=module,
        action=action,
        target_type=target_type,
        target_id=target_id,
        request_id=str(uuid.uuid4())[:64],
        ip_address=ip,
        user_agent=user_agent,
        detail_json=detail,
    )
    db.add(log)
    return log


def write_file_access(
    db: Session,
    *,
    user_id: int | None,
    archive_file_id: int | None,
    access_type: str,
    ip: str | None = None,
    result: str = "success",
):
    """写入一条文件访问日志。"""
    rec = FileAccessLog(
        user_id=user_id,
        archive_file_id=archive_file_id,
        access_type=access_type,
        ip_address=ip,
        access_time=datetime.now(),
        result=result,
    )
    db.add(rec)
    return rec
