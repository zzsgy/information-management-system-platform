# -*- coding: utf-8 -*-
"""
app/api/destroy.py
====================
本文件提供「销毁」接口：
- POST /api/destroys         发起销毁申请（需 destroy.apply）
- POST /api/destroys/{id}/approve  审批（需 destroy.approve）
- POST /api/destroys/{id}/execute  执行销毁（需 destroy.execute，强制留痕）
说明：销毁前必须审批通过；执行后档案逻辑标记为已销毁，不物理删除。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.business import DestroyRequest
from app.models.org import User
from app.schemas.business import DestroyCreate, DestroyExecute
from app.schemas.common import ApiResponse, ok
from app.services import business as biz_svc
from app.services import log as log_svc

router = APIRouter(prefix="/api/destroys", tags=["销毁"])


@router.get("", response_model=ApiResponse)
def list_destroys(
    status: str = None, applicant_id: int = None,
    page: int = 1, page_size: int = 20,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("destroy.apply", "destroy.approve")),
):
    """销毁申请列表（可按状态/申请人过滤），供申请人与审批人查看。"""
    q = db.query(DestroyRequest)
    if status:
        q = q.filter(DestroyRequest.status == status)
    if applicant_id:
        q = q.filter(DestroyRequest.applicant_user_id == applicant_id)
    total = q.with_entities(func.count()).scalar() or 0
    rows = (
        q.order_by(DestroyRequest.id.desc())
        .offset((page - 1) * page_size).limit(page_size).all()
    )
    items = []
    for r in rows:
        applicant = db.get(User, r.applicant_user_id)
        items.append({
            "id": r.id, "reason": r.reason, "scope_desc": r.scope_desc,
            "status": r.status, "applicant_name": applicant.real_name if applicant else "",
            "created_at": r.created_at,
        })
    return ok({"total": total, "items": items})


@router.post("", response_model=ApiResponse)
def create_destroy(
    body: DestroyCreate,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("destroy.apply")),
):
    if not body.archive_ids:
        raise HTTPException(status_code=400, detail="请选择至少一份档案")
    req = biz_svc.create_destroy(db, applicant_id=user.id, data=body)
    log_svc.write_operation(
        db, user_id=user.id, module="destroy", action="apply",
        target_type="destroy", target_id=req.id,
    )
    db.commit()
    return ok({"id": req.id, "status": req.status}, "销毁申请已提交")


@router.post("/{destroy_id}/approve", response_model=ApiResponse)
def approve_destroy(
    destroy_id: int, approve: bool, opinion: str = None,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("destroy.approve")),
):
    is_admin = "super_admin" in getattr(user, "_role_codes", [])
    try:
        req = biz_svc.approve_destroy(
            db, destroy_id, user.id, approve, opinion, is_admin=is_admin
        )
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    log_svc.write_operation(
        db, user_id=user.id, module="destroy", action="approve",
        target_type="destroy", target_id=req.id, detail=f"approve={approve}",
    )
    db.commit()
    return ok({"id": req.id, "status": req.status}, "审批完成")


@router.post("/{destroy_id}/execute", response_model=ApiResponse)
def execute_destroy(
    destroy_id: int, body: DestroyExecute,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("destroy.execute")),
):
    try:
        batch = biz_svc.execute_destroy(
            db, destroy_id, executor_id=user.id, data=body, archive_ids=body.archive_ids
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    log_svc.write_operation(
        db, user_id=user.id, module="destroy", action="execute",
        target_type="destroy", target_id=destroy_id, detail=f"batch={batch.batch_no}",
    )
    db.commit()
    return ok({"batch_no": batch.batch_no}, "销毁执行完成，已留痕")
