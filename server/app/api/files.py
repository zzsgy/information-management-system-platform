# -*- coding: utf-8 -*-
"""
app/api/files.py
=================
本文件提供「电子文件」接口：
- POST /api/files/upload?archive_id=   上传电子文件（校验后落盘，登记元数据）
- GET  /api/files?archive_id=          列出某档案的电子文件
- GET  /api/files/{file_id}/download   下载（先校验权限，再返回文件流）
说明：文件二进制不入库，只存元数据（ArchiveFile）；下载前必须有权限。
"""
from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.archive import ArchiveFile
from app.models.org import User
from app.schemas.archive import ArchiveFileOut
from app.schemas.common import ApiResponse, ok
from app.services import file as file_svc
from app.services import log as log_svc

router = APIRouter(prefix="/api/files", tags=["电子文件"])


@router.post("/upload", response_model=ApiResponse)
def upload(
    archive_id: int,
    file: UploadFile,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("file.upload")),
):
    """上传一份电子文件到指定档案（同步处理，文件读写在线程池中执行）。"""
    rec = file_svc.save_upload(db, archive_id=archive_id, file=file, uploaded_by=user.id)
    # 同步档案文件数
    from app.models.archive import Archive
    arch = db.get(Archive, archive_id)
    if arch:
        arch.file_count = (arch.file_count or 0) + 1
    log_svc.write_file_access(
        db, user_id=user.id, archive_file_id=rec.id, access_type="upload", result="success",
    )
    db.commit()
    return ok(ArchiveFileOut.model_validate(rec).model_dump(), "上传成功")


@router.get("", response_model=ApiResponse)
def list_files(
    archive_id: int = Query(..., description="档案ID"),
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    rows = db.query(ArchiveFile).filter_by(archive_id=archive_id, status="active").all()
    return ok([ArchiveFileOut.model_validate(r).model_dump() for r in rows])


@router.get("/{file_id}/preview")
def preview(
    file_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("file.preview")),
):
    """在线预览电子文件：内联返回文件流，供客户端内置预览组件渲染。

    支持的预览能力由客户端决定（图片/PDF/文本可内嵌渲染，Office 等可下载或用外部程序打开）。
    """
    rec = db.get(ArchiveFile, file_id)
    if not rec or rec.status != "active":
        raise HTTPException(status_code=404, detail="文件不存在")
    try:
        abs_path = file_svc.get_absolute_path(rec.storage_key)
    except ValueError:
        raise HTTPException(status_code=400, detail="非法存储路径")
    if not abs_path.exists():
        raise HTTPException(status_code=404, detail="文件实体缺失")
    log_svc.write_file_access(
        db, user_id=user.id, archive_file_id=rec.id, access_type="preview", result="success",
    )
    db.commit()
    return FileResponse(
        str(abs_path),
        filename=rec.file_name,
        media_type=rec.mime_type or "application/octet-stream",
        content_disposition_type="inline",
    )


@router.get("/{file_id}/download")
def download(
    file_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("file.download")),
):
    """下载电子文件：校验存在性与权限后返回本地文件。"""
    rec = db.get(ArchiveFile, file_id)
    if not rec or rec.status != "active":
        raise HTTPException(status_code=404, detail="文件不存在")
    try:
        abs_path = file_svc.get_absolute_path(rec.storage_key)
    except ValueError:
        raise HTTPException(status_code=400, detail="非法存储路径")
    if not abs_path.exists():
        raise HTTPException(status_code=404, detail="文件实体缺失")
    log_svc.write_file_access(
        db, user_id=user.id, archive_file_id=rec.id, access_type="download", result="success",
    )
    db.commit()
    return FileResponse(
        str(abs_path),
        filename=rec.file_name,
        media_type=rec.mime_type or "application/octet-stream",
    )
