# -*- coding: utf-8 -*-
"""
app/main.py
===========
本文件是 FastAPI 应用的「入口」：
- 创建 FastAPI 应用、配置 CORS。
- 启动时自动建表（create_all）并执行基础数据初始化（seed）。
- 注册全部 API 路由（前缀 /api）。
- 提供根路径健康检查 GET /health。
说明：生产环境应使用 Alembic 迁移；此处 create_all 用于开发与联调快速就绪。
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse

from app.api import all_routers
from app.core.config import settings
from app.core.logging import get_logger
from app.db.base import Base
from app.db.seed import seed
from app.db.session import SessionLocal, engine
from app.models import *  # noqa: F401,F403  确保模型被导入，create_all 才能建表
from sqlalchemy import inspect, text

logger = get_logger("ArchiveServer")

# 创建应用
app = FastAPI(
    title="企业档案管理系统 API",
    description="服务端：认证、档案、电子文件、借阅、销毁、公告、日志",
    version="1.0",
)

# 跨域配置（联调阶段客户端可能用浏览器调试）
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS.split(",") if settings.CORS_ORIGINS != "*" else ["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _migrate_schema(db):
    """对已存在的数据库补齐缺失列（SQLite 不支持 create_all 自动加列）。

    新表（如 archive_update_log）由 create_all 直接创建；此处仅处理
    历史库中 archive 表缺失的新字段，缺失才 ALTER ADD COLUMN。
    """
    try:
        insp = inspect(engine)
        if not insp.has_table("archive"):
            return
        existing = {c["name"] for c in insp.get_columns("archive")}
        needed = {
            "file_type": "VARCHAR(50)",
            "owner_name": "VARCHAR(100)",
            "cabinet_no": "VARCHAR(50)",
            "column_no": "VARCHAR(50)",
            "layer_no": "VARCHAR(50)",
            "storage_type": "VARCHAR(20)",
            "copy_count": "INTEGER",
            "archive_date": "DATE",
            "validity_months": "INTEGER",
            "expiry_date": "DATE",
            "remark": "TEXT",
        }
        for name, typ in needed.items():
            if name not in existing:
                db.execute(text(f"ALTER TABLE archive ADD COLUMN {name} {typ}"))
        # 编号规则表：补齐分段规则列
        if insp.has_table("numbering_rule"):
            nr_cols = {c["name"] for c in insp.get_columns("numbering_rule")}
            if "segments" not in nr_cols:
                db.execute(text("ALTER TABLE numbering_rule ADD COLUMN segments TEXT"))
        db.commit()
    except Exception as e:  # 迁移失败不应阻断启动
        logger.error(f"schema 迁移跳过: {e}")


@app.on_event("startup")
def on_startup():
    """应用启动时：建表 + 补齐旧库字段 + 初始化基础数据。"""
    try:
        Base.metadata.create_all(bind=engine)
        db = SessionLocal()
        try:
            _migrate_schema(db)
            seed(db)
        finally:
            db.close()
        logger.info("数据库初始化完成（建表 + 种子数据）")
    except Exception as e:  # 启动失败也记录，便于排查
        logger.error(f"启动初始化失败: {e}")


@app.get("/health", tags=["系统"])
def health():
    """健康检查接口，返回 ok 表示服务可用。"""
    return {"status": "ok", "app": settings.APP_NAME, "version": "1.0"}


@app.get("/", include_in_schema=False)
def root():
    """浏览器访问服务根地址时，自动跳转到 Web 档案浏览界面。"""
    return RedirectResponse(url="/web/", status_code=302)


# 注册全部业务路由
for _router in all_routers:
    app.include_router(_router)
