# -*- coding: utf-8 -*-
"""
app/api/notices.py
===================
本文件提供「公告」接口：
- POST /api/notices      发布公告（需 notice.publish）
- GET  /api/notices      查询公告列表（需 notice.read）
说明：支持指定接收人或全员发布；已读状态由客户端调用 mark_read 标记。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.business import Notice, NoticeReceiver
from app.models.org import User
from app.schemas.business import NoticeCreate, NoticeOut
from app.schemas.common import ApiResponse, ok
from app.services import business as biz_svc

router = APIRouter(prefix="/api/notices", tags=["公告"])


@router.post("", response_model=ApiResponse)
def publish(
    body: NoticeCreate,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("notice.publish")),
):
    notice = biz_svc.publish_notice(db, publisher_id=user.id, data=body)
    db.commit()
    return ok(NoticeOut.model_validate(notice).model_dump(), "公告已发布")


@router.get("", response_model=ApiResponse)
def list_notices(
    page: int = 1, page_size: int = 20,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("notice.read")),
):
    base = db.query(Notice).filter(Notice.deleted_at.is_(None))
    total = base.with_entities(func.count()).scalar() or 0
    rows = (
        base.order_by(Notice.id.desc())
        .offset((page - 1) * page_size).limit(page_size).all()
    )
    return ok({
        "total": total,
        "items": [NoticeOut.model_validate(r).model_dump() for r in rows],
    })
