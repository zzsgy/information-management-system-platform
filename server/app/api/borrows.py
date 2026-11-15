# -*- coding: utf-8 -*-
"""
app/api/borrows.py
===================
本文件提供「借阅」接口：
- POST /api/borrows        发起借阅申请（需 borrow.apply）
- POST /api/borrows/{id}/approve  审批（需 borrow.approve）
- POST /api/borrows/{id}/return   归还（需 borrow.approve）
说明：状态流转 pending→approved→borrowed→returned，全程留痕。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from sqlalchemy import func

from app.models.archive import Archive
from app.models.business import BorrowDetail, BorrowRecord
from app.models.org import User
from app.schemas.business import BorrowApprove, BorrowCreate, BorrowOut, BorrowReturn
from app.schemas.common import ApiResponse, ok
from app.services import business as biz_svc
from app.services import log as log_svc

router = APIRouter(prefix="/api/borrows", tags=["借阅"])


@router.get("", response_model=ApiResponse)
def list_borrows(
    status: str = None, applicant_id: int = None,
    page: int = 1, page_size: int = 20,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("borrow.apply", "borrow.approve")),
):
    """借阅列表（可按状态/申请人过滤），供申请人与审批人查看。"""
    q = db.query(BorrowRecord)
    if status:
        q = q.filter(BorrowRecord.status == status)
    if applicant_id:
        q = q.filter(BorrowRecord.applicant_user_id == applicant_id)
    total = q.with_entities(func.count()).scalar() or 0
    rows = (
        q.order_by(BorrowRecord.id.desc())
        .offset((page - 1) * page_size).limit(page_size).all()
    )
    items = []
    for r in rows:
        d = BorrowOut.model_validate(r).model_dump()
        applicant = db.get(User, r.applicant_user_id)
        d["applicant_name"] = applicant.real_name if applicant else ""
        aids = [bd.archive_id for bd in db.query(BorrowDetail).filter_by(borrow_id=r.id)]
        d["archive_ids"] = aids
        names = []
        for aid in aids:
            arch = db.get(Archive, aid)
            names.append(arch.archive_name if arch else f"档案{aid}")
        d["archive_names"] = names
        items.append(d)
    return ok({"total": total, "items": items})


@router.post("", response_model=ApiResponse)
def create_borrow(
    body: BorrowCreate,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("borrow.apply")),
):
    if not body.archive_ids:
        raise HTTPException(status_code=400, detail="请选择至少一份档案")
    rec = biz_svc.create_borrow(db, applicant_id=user.id, data=body)
    log_svc.write_operation(
        db, user_id=user.id, module="borrow", action="apply",
        target_type="borrow", target_id=rec.id, detail=f"no={rec.borrow_no}",
    )
    db.commit()
    return ok(BorrowOut.model_validate(rec).model_dump(), "借阅申请已提交")


@router.post("/{borrow_id}/approve", response_model=ApiResponse)
def approve_borrow(
    borrow_id: int, body: BorrowApprove,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("borrow.approve")),
):
    is_admin = "super_admin" in getattr(user, "_role_codes", [])
    try:
        rec = biz_svc.approve_borrow(
            db, borrow_id, user.id, body.approve, body.opinion, is_admin=is_admin
        )
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    log_svc.write_operation(
        db, user_id=user.id, module="borrow", action="approve",
        target_type="borrow", target_id=rec.id, detail=f"approve={body.approve}",
    )
    db.commit()
    return ok(BorrowOut.model_validate(rec).model_dump(), "审批完成")


@router.post("/{borrow_id}/return", response_model=ApiResponse)
def return_borrow(
    borrow_id: int, body: BorrowReturn,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("borrow.approve")),
):
    try:
        rec = biz_svc.return_borrow(db, borrow_id, body.return_condition)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    log_svc.write_operation(
        db, user_id=user.id, module="borrow", action="return",
        target_type="borrow", target_id=rec.id,
    )
    db.commit()
    return ok(message="归还完成")
