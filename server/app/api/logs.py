# -*- coding: utf-8 -*-
"""
app/api/logs.py
===============
本文件提供「操作日志」查询接口：
- GET /api/logs   分页查询操作日志（需 log.read）
说明：供审计与故障排查使用，支持模块/动作/用户过滤。
"""
from fastapi import APIRouter, Depends
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.logs import OperationLog
from app.models.org import User
from app.schemas.common import ApiResponse, ok

router = APIRouter(prefix="/api/logs", tags=["日志"])


@router.get("", response_model=ApiResponse)
def list_logs(
    module: str = None, action: str = None, user_id: int = None,
    page: int = 1, page_size: int = 50,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("log.read")),
):
    q = db.query(OperationLog)
    if module:
        q = q.filter(OperationLog.module == module)
    if action:
        q = q.filter(OperationLog.action == action)
    if user_id:
        q = q.filter(OperationLog.user_id == user_id)
    total = q.with_entities(func.count()).scalar() or 0
    rows = (
        q.order_by(OperationLog.id.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    items = [
        {
            "id": r.id, "user_id": r.user_id, "module": r.module,
            "action": r.action, "target_type": r.target_type,
            "target_id": r.target_id, "ip": r.ip_address,
            "created_at": r.created_at, "detail": r.detail_json,
        }
        for r in rows
    ]
    return ok({"total": total, "items": items})
