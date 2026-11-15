# -*- coding: utf-8 -*-
"""
app/services/file.py
=====================
本文件实现「电子文件服务」：
- 接收上传的二进制，计算 SHA256 校验值，按 archive_id/年/月 安全目录存储
  （禁止客户端指定任意绝对路径）。
- 提供按 id 返回本地文件路径的读取方法。
- 删除/下载均需在调用方完成权限校验后再执行。
说明：文件二进制落盘到 STORAGE_ROOT，数据库只存元数据（见 ArchiveFile 模型）。
"""
import hashlib
import os
from datetime import datetime
from pathlib import Path

from fastapi import UploadFile

from app.core.config import settings
from app.models.archive import ArchiveFile


def _safe_storage_key(archive_id: int, filename: str) -> tuple[str, Path]:
    """生成安全的相对存储键与绝对路径（限制在 STORAGE_ROOT 内）。"""
    now = datetime.now()
    rel_dir = Path("archives") / str(archive_id) / now.strftime("%Y%m")
    abs_dir = settings.storage_path / rel_dir
    abs_dir.mkdir(parents=True, exist_ok=True)
    # 用时间戳+原名生成落盘文件名，避免覆盖与路径注入
    safe_name = f"{now.strftime('%Y%m%d%H%M%S%f')}_{os.path.basename(filename)}"
    return str(rel_dir / safe_name), abs_dir / safe_name


def save_upload(db, *, archive_id: int, file: UploadFile, uploaded_by: int) -> ArchiveFile:
    """保存上传文件，写盘并登记元数据，返回 ArchiveFile 记录（未 commit）。"""
    rel_key, abs_path = _safe_storage_key(archive_id, file.filename or "file")
    # 分块写入并计算哈希
    sha = hashlib.sha256()
    size = 0
    with open(abs_path, "wb") as f:
        for chunk in iter(lambda: file.file.read(1024 * 1024), b""):
            f.write(chunk)
            sha.update(chunk)
            size += len(chunk)

    # 旧版本置为非当前
    db.query(ArchiveFile).filter_by(archive_id=archive_id, is_current=1).update(
        {"is_current": 0}
    )

    ext = os.path.splitext(file.filename or "")[1].lstrip(".")[:20]
    rec = ArchiveFile(
        archive_id=archive_id,
        file_name=file.filename or "file",
        file_ext=ext,
        mime_type=file.content_type,
        storage_provider="local",
        storage_key=rel_key,
        file_size=size,
        sha256=sha.hexdigest(),
        version_no=1,
        is_current=1,
        uploaded_by=uploaded_by,
        uploaded_at=datetime.now(),
        status="active",
    )
    db.add(rec)
    db.flush()
    return rec


def get_absolute_path(storage_key: str) -> Path:
    """根据存储键返回绝对路径，并校验仍在 STORAGE_ROOT 内（防越权穿越）。"""
    abs_path = (settings.storage_path / storage_key).resolve()
    if settings.storage_path.resolve() not in abs_path.parents and abs_path != settings.storage_path.resolve():
        raise ValueError("非法存储路径")
    return abs_path
