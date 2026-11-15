# -*- coding: utf-8 -*-
"""
app/api/archives.py
====================
本文件提供「档案 / 分类」管理接口：
- 分类：列表、创建
- 档案：创建（自动编号）、列表（分页查询）、详情、更新、归档
说明：档案创建需 archive.write 权限；查询需 archive.read。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
import json

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.archive import Archive, ArchiveCategory, ArchiveUpdateLog
from app.models.org import User
from app.schemas.archive import ArchiveCreate, ArchiveOut, ArchiveUpdate, CategoryCreate, CategoryUpdate, CategoryOut
from app.schemas.common import ApiResponse, ok
from app.services import archive as archive_svc
from app.services import log as log_svc

router = APIRouter(prefix="/api", tags=["档案"])


@router.get("/categories", response_model=ApiResponse)
def list_categories(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    rows = archive_svc.list_categories(db)
    return ok([CategoryOut.model_validate(r).model_dump() for r in rows])


@router.post("/categories", response_model=ApiResponse)
def create_category(
    body: CategoryCreate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.admin")),
):
    if db.query(ArchiveCategory).filter_by(category_code=body.category_code).first():
        raise HTTPException(status_code=400, detail="分类编码已存在")
    cat = archive_svc.create_category(db, body)
    db.commit()
    return ok(CategoryOut.model_validate(cat).model_dump(), "分类创建成功")


@router.put("/categories/{cat_id}", response_model=ApiResponse)
def update_category(
    cat_id: int,
    body: CategoryUpdate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.admin")),
):
    try:
        cat = archive_svc.update_category(db, cat_id, body)
    except ValueError as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e))
    db.commit()
    return ok(CategoryOut.model_validate(cat).model_dump(), "分类更新成功")


@router.delete("/categories/{cat_id}", response_model=ApiResponse)
def delete_category(
    cat_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.admin")),
):
    try:
        archive_svc.delete_category(db, cat_id)
    except ValueError as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e))
    db.commit()
    return ok(None, "分类已删除")


@router.post("/archives", response_model=ApiResponse)
def create_archive(
    body: ArchiveCreate,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("archive.write")),
):
    try:
        archive = archive_svc.create_archive(db, body, creator_id=user.id)
    except ValueError as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e))
    log_svc.write_operation(
        db, user_id=user.id, module="archive", action="create",
        target_type="archive", target_id=archive.id, detail=f"no={archive.archive_no}",
    )
    db.commit()
    return ok(archive_svc.to_out_dict(db, archive), "档案创建成功")


@router.post("/archives/import", response_model=ApiResponse)
def import_archives(
    body: dict,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("archive.write")),
):
    """批量导入档案：接收 CSV 文本（content 字段），按名称解析分类/部门后逐条创建。"""
    import csv
    import io

    content = body.get("content", "") if isinstance(body, dict) else ""
    if not isinstance(content, str) or not content.strip():
        raise HTTPException(status_code=400, detail="导入内容为空")
    reader = csv.DictReader(io.StringIO(content))
    rows = [dict(r) for r in reader]
    if not rows:
        raise HTTPException(status_code=400, detail="未解析到任何数据行")
    created, errors = archive_svc.import_archives(db, rows, creator_id=user.id)
    return ok(
        {"total": len(rows), "created": created, "failed": len(errors), "errors": errors[:50]},
        f"成功导入 {created} 条，失败 {len(errors)} 条",
    )


@router.get("/archives", response_model=ApiResponse)
def list_archives(
    keyword: str = None, category_id: int = None, dept_id: int = None,
    status: str = None, page: int = 1, page_size: int = 20,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    total, rows = archive_svc.query_archives(
        db, keyword=keyword, category_id=category_id, dept_id=dept_id,
        status=status, page=page, page_size=page_size,
    )
    return ok({
        "total": total,
        "items": [archive_svc.to_out_dict(db, r) for r in rows],
    })


@router.get("/archives/stats", response_model=ApiResponse)
def archive_stats(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    """首页仪表盘统计：总数、状态/分类/密级分布、即将到期。"""
    return ok(archive_svc.archive_stats(db))


@router.get("/archives/{archive_id}", response_model=ApiResponse)
def archive_detail(
    archive_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("archive.read")),
):
    try:
        arch = archive_svc.get_archive(db, archive_id)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    log_svc.write_operation(
        db, user_id=user.id, module="archive", action="view",
        target_type="archive", target_id=arch.id,
    )
    db.commit()
    return ok(archive_svc.to_out_dict(db, arch))


@router.put("/archives/{archive_id}", response_model=ApiResponse)
def update_archive(
    archive_id: int, body: ArchiveUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("archive.write")),
):
    # 物理位置（存放位置）仅允许管理员修改
    if body.physical_location is not None:
        owned = getattr(user, "_perm_codes", [])
        if "*" not in owned and "archive.admin" not in owned:
            raise HTTPException(
                status_code=403, detail="修改物理位置需要管理员权限"
            )
    try:
        arch = archive_svc.update_archive(
            db, archive_id, body,
            operator_id=user.id, operator_name=getattr(user, "real_name", None),
        )
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    log_svc.write_operation(
        db, user_id=user.id, module="archive", action="update",
        target_type="archive", target_id=arch.id,
    )
    db.commit()
    return ok(archive_svc.to_out_dict(db, arch), "档案更新成功")


@router.get("/archives/{archive_id}/update-logs", response_model=ApiResponse)
def list_update_logs(
    archive_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    """返回某档案的全部更新记录（按时间倒序）。"""
    archive = db.get(Archive, archive_id)
    if not archive or archive.deleted_at is not None:
        raise HTTPException(status_code=404, detail="档案不存在")
    rows = (
        db.query(ArchiveUpdateLog)
        .filter_by(archive_id=archive_id)
        .order_by(ArchiveUpdateLog.id.desc())
        .all()
    )
    items = []
    for r in rows:
        changed = []
        try:
            changed = json.loads(r.changed_fields or "[]")
        except Exception:  # noqa
            changed = []
        items.append({
            "id": r.id,
            "operator_name": r.operator_name,
            "action": r.action,
            "created_at": r.created_at.isoformat() if r.created_at else None,
            "changed_fields": changed,
        })
    return ok(items)


@router.post("/archives/{archive_id}/archive", response_model=ApiResponse)
def do_archive(
    archive_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("archive.write")),
):
    """将档案状态由草稿置为已归档（archive）。"""
    try:
        arch = archive_svc.get_archive(db, archive_id)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    arch.status = "archive"
    db.add(ArchiveUpdateLog(
        archive_id=arch.id, operator_id=user.id,
        operator_name=getattr(user, "real_name", None),
        action="archive", changed_fields=json.dumps(["status"], ensure_ascii=False),
    ))
    log_svc.write_operation(
        db, user_id=user.id, module="archive", action="archive",
        target_type="archive", target_id=arch.id,
    )
    db.commit()
    return ok(message="档案已归档")
