# -*- coding: utf-8 -*-
"""
app/api/security_ops.py
========================
数据安全运维接口（仅超级管理员可用，API 层以 archive.admin 权限拦截）：
- GET  /api/security/integrity/check  电子文件完整性校验（重算 SHA256 与库内比对）
- POST /api/security/db-backup        数据库备份（pg_dump 到 backups/db 目录）
- GET  /api/security/db-backups       备份文件列表
- POST /api/security/db-restore       数据库恢复（选中备份，先自动备份再恢复，需 confirm）
说明：数据库备份/恢复依赖本地 PostgreSQL 的 pg_dump / pg_restore，
      可通过环境变量 PG_BIN 指定其所在目录，缺省回退到 C:\\Apython\\PostgreSQL\\bin。
"""
import hashlib
import os
import subprocess
from datetime import datetime
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import create_engine, text
from sqlalchemy.engine.url import make_url

from app.core.config import BASE_DIR, settings
from app.db.session import get_db
from app.deps import require_permissions
from app.models.archive import ArchiveFile
from app.models.org import User
from app.schemas.common import ApiResponse, ok

router = APIRouter(prefix="/api/security", tags=["数据安全运维"])

# 备份目录：程序根目录下的 backups/db（打包态 BASE_DIR 即 exe 所在目录）
BACKUP_DIR = BASE_DIR / "backups" / "db"
BACKUP_DIR.mkdir(parents=True, exist_ok=True)


# ------------------------- 工具函数 -------------------------
def _pg_exe(name: str) -> str:
    """解析 pg 工具可执行文件路径，按 PG_BIN 环境变量 → 已知目录 → PATH 顺序回退。"""
    candidates = []
    pg_bin = os.environ.get("PG_BIN")
    if pg_bin:
        candidates.append(Path(pg_bin) / name)
    candidates.append(Path(r"C:\Apython\PostgreSQL\bin") / name)
    candidates.append(Path(name))  # 退化为依赖 PATH
    for c in candidates:
        if c.exists():
            return str(c)
    return name


def _db_params() -> dict:
    """从 DATABASE_URL 解析 pg_dump / pg_restore 所需的连接参数。"""
    url = settings.DATABASE_URL or settings.database_url
    u = make_url(url)
    return {
        "host": u.host or "localhost",
        "port": u.port or 5432,
        "user": u.username,
        "password": u.password or "",
        "dbname": u.database,
    }


def _sha256_of(path: Path) -> str:
    """计算文件 SHA256 十六进制摘要。"""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _auto_backup(reason: str) -> str:
    """执行一次数据库备份，返回生成的文件名（不含目录）。"""
    p = _db_params()
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    fname = f"archive_system_{ts}_{reason}.dump"
    out = BACKUP_DIR / fname
    env = dict(os.environ)
    if p["password"]:
        env["PGPASSWORD"] = p["password"]
    cmd = [
        _pg_exe("pg_dump"),
        "-h", p["host"], "-p", str(p["port"]), "-U", p["user"],
        "--no-password", "-F", "c", "-f", str(out), p["dbname"],
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=600)
    if r.returncode != 0:
        raise HTTPException(status_code=500, detail=f"备份失败：{r.stderr[:300]}")
    return fname


def _terminate_connections(dbname: str):
    """终止除自身外的、指向目标库的其他连接（恢复前清理，避免对象被占用）。"""
    p = _db_params()
    maint = make_url(settings.DATABASE_URL or settings.database_url).set(database="postgres")
    eng = create_engine(maint)
    try:
        with eng.connect() as conn:
            conn.execution_options(isolation_level="AUTOCOMMIT")
            conn.execute(
                text("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                     "WHERE datname = :db AND pid <> pg_backend_pid()"),
                {"db": dbname},
            )
    finally:
        eng.dispose()


# ------------------------- 电子文件完整性校验 -------------------------
@router.get("/integrity/check", response_model=ApiResponse)
def integrity_check(
    db=Depends(get_db),
    _: User = Depends(require_permissions("archive.admin")),
):
    """重算全部电子文件的 SHA256，与入库时记录的哈希比对，列出异常项。"""
    from app.services import file as file_svc

    rows = db.query(ArchiveFile).filter_by(status="active").all()
    items, ok_count = [], 0
    for f in rows:
        entry = {
            "file_id": f.id, "file_name": f.file_name,
            "expected": f.sha256, "actual": None, "reason": None,
        }
        try:
            abs_path = file_svc.get_absolute_path(f.storage_key)
            if not abs_path.exists():
                entry["reason"] = "文件实体缺失"
            else:
                actual = _sha256_of(abs_path)
                entry["actual"] = actual
                if actual != f.sha256:
                    entry["reason"] = "哈希不匹配"
                else:
                    ok_count += 1
        except Exception as e:  # 单文件异常不影响整体
            entry["reason"] = f"读取失败：{e}"
        if entry["reason"]:
            items.append(entry)
    return ok({
        "total": len(rows),
        "ok": ok_count,
        "failed": len(items),
        "items": items,
    }, f"校验完成：正常 {ok_count} 项，异常 {len(items)} 项")


# ------------------------- 数据库备份 / 恢复 -------------------------
@router.post("/db-backup", response_model=ApiResponse)
def db_backup(
    _: User = Depends(require_permissions("archive.admin")),
):
    """立即执行一次数据库备份（自定义格式 .dump）。"""
    fname = _auto_backup("manual")
    size = (BACKUP_DIR / fname).stat().st_size
    return ok({"file": fname, "size": size}, f"备份成功：{fname}")


@router.get("/db-backups", response_model=ApiResponse)
def db_backups(
    _: User = Depends(require_permissions("archive.admin")),
):
    """列出已存在的数据库备份文件。"""
    items = []
    for fp in sorted(BACKUP_DIR.glob("*.dump"), key=lambda p: p.stat().st_mtime, reverse=True):
        items.append({
            "name": fp.name,
            "size": fp.stat().st_size,
            "mtime": datetime.fromtimestamp(fp.stat().st_mtime).strftime("%Y-%m-%d %H:%M:%S"),
        })
    return ok(items)


@router.post("/db-restore", response_model=ApiResponse)
def db_restore(
    body: dict,
    _: User = Depends(require_permissions("archive.admin")),
):
    """从指定备份恢复数据库。恢复前自动先备份一次作为回滚点；需 confirm=true。"""
    if not body.get("confirm"):
        raise HTTPException(status_code=400, detail="恢复操作需显式 confirm=true")
    fname = body.get("file")
    if not fname or not (BACKUP_DIR / fname).exists():
        raise HTTPException(status_code=404, detail="备份文件不存在")

    p = _db_params()
    # 1) 恢复前自动备份，确保可回滚
    pre = _auto_backup("pre_restore")
    # 2) 终止其他连接，避免对象被占用
    _terminate_connections(p["dbname"])
    # 3) 执行恢复（--clean 先清理同名对象）
    env = dict(os.environ)
    if p["password"]:
        env["PGPASSWORD"] = p["password"]
    cmd = [
        _pg_exe("pg_restore"),
        "-h", p["host"], "-p", str(p["port"]), "-U", p["user"],
        "--no-password", "--clean", "--if-exists",
        "-d", p["dbname"], str(BACKUP_DIR / fname),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=600)
    # pg_restore 在 --clean 模式下，若不存在同名对象会产生可忽略的告警（返回码非 0 但恢复成功）
    if r.returncode != 0 and "error" in (r.stderr or "").lower():
        # 恢复失败：回滚到刚才的自动备份
        _terminate_connections(p["dbname"])
        subprocess.run([
            _pg_exe("pg_restore"), "-h", p["host"], "-p", str(p["port"]),
            "-U", p["user"], "--no-password", "--clean", "--if-exists",
            "-d", p["dbname"], str(BACKUP_DIR / pre),
        ], capture_output=True, text=True, env=env, timeout=600)
        # 让服务端连接池重建
        from app.db.session import engine
        engine.dispose()
        raise HTTPException(status_code=500, detail=f"恢复失败已回滚：{r.stderr[:300]}")
    # 让服务端连接池重建，避免用到被终止的旧连接
    from app.db.session import engine
    engine.dispose()
    return ok({"restored": fname, "pre_backup": pre},
              f"恢复完成（恢复前已自动备份：{pre}）")
