# -*- coding: utf-8 -*-
"""
app/api/damage.py
==================
本文件提供「损坏登记」接口：
- POST /api/damages       登记损坏（需 archive.write）
- POST /api/damages/{id}/handle  处理并留痕（需 archive.write）
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.archive import Archive
from app.models.business import DamageRecord
from app.models.org import User
from app.schemas.business import DamageCreate, DamageHandle
from app.schemas.common import ApiResponse, ok
from app.services import business as biz_svc
from app.services import log as log_svc

router = APIRouter(prefix="/api/damages", tags=["损坏"])


@router.get("", response_model=ApiResponse)
def list_damages(
    status: str = None, page: int = 1, page_size: int = 20,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read", "archive.write")),
):
    """损坏记录列表（可按状态过滤）。"""
    q = db.query(DamageRecord)
    if status:
        q = q.filter(DamageRecord.status == status)
    total = q.with_entities(func.count()).scalar() or 0
    rows = (
        q.order_by(DamageRecord.id.desc())
        .offset((page - 1) * page_size).limit(page_size).all()
    )
    items = []
    for r in rows:
        arch = db.get(Archive, r.archive_id)
        reporter = db.get(User, r.reported_by)
        items.append({
            "id": r.id, "archive_id": r.archive_id,
            "archive_name": arch.archive_name if arch else "",
            "damage_type": r.damage_type, "damage_desc": r.damage_desc,
            "status": r.status, "reporter_name": reporter.real_name if reporter else "",
            "created_at": r.reported_at,
        })
    return ok({"total": total, "items": items})


@router.post("", response_model=ApiResponse)
def create_damage(
    body: DamageCreate,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("archive.write")),
):
    rec = biz_svc.create_damage(db, reporter_id=user.id, data=body)
    log_svc.write_operation(
        db, user_id=user.id, module="damage", action="report",
        target_type="archive", target_id=body.archive_id,
    )
    db.commit()
    return ok({"id": rec.id, "status": rec.status}, "损坏已登记")


@router.post("/{damage_id}/handle", response_model=ApiResponse)
def handle_damage(
    damage_id: int, body: DamageHandle,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("archive.write")),
):
    try:
        rec = biz_svc.handle_damage(db, damage_id, body)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    log_svc.write_operation(
        db, user_id=user.id, module="damage", action="handle",
        target_type="damage", target_id=rec.id,
    )
    db.commit()
    return ok({"id": rec.id, "status": rec.status}, "损坏处理完成")
