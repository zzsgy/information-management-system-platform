# -*- coding: utf-8 -*-
"""
app/db/session.py
==================
本文件负责数据库连接：
- 用配置中的 DATABASE_URL 创建 SQLAlchemy 引擎（开启连接池健康检查）。
- SessionLocal：每次请求新建一个数据库会话。
- get_db：FastAPI 依赖项，自动为请求提供 session 并在结束后关闭。
说明：engine 的 pool_pre_ping 能在连接断开时自动重连，避免长时空闲后报错。
"""
from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import settings

# 创建数据库引擎
engine = create_engine(
    settings.database_url,
    pool_pre_ping=True,    # 连接前探活，自动剔除失效连接
    pool_size=10,          # 连接池大小
    max_overflow=20,       # 超出后最多再临时创建的连接数
    echo=False,            # 生产改为 False；开发可改 True 看 SQL
)

# 会话工厂
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


def get_db() -> Generator[Session, None, None]:
    """FastAPI 依赖：为每个请求提供独立数据库会话。"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
