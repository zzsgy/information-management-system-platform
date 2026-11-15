"""
================================================================
文件说明：服务端冒烟测试接口（Hello API）
----------------------------------------------------------------
用途：
  - 验证 FastAPI 服务能否正常启动、能否连通 PostgreSQL 数据库；
  - 提供 /health（自身健康）、/db-health（真实连库探测）、
    /tables（列出当前库的表）、/now（数据库服务器时间）等调试接口。
说明：
  - 这是环境搭建阶段的验证脚本。正式开发时会替换为
    app/main.py + 各业务模块路由（见开发文档03）。
  - 依赖 server/.env 里的 DATABASE_URL 数据库连接配置。
运行：在 server 目录下执行   .venv/Scripts/python hello_api.py
================================================================
"""
import os

import uvicorn
from dotenv import load_dotenv
from fastapi import FastAPI
from sqlalchemy import Engine, create_engine, text
from sqlalchemy.exc import SQLAlchemyError

# 加载项目根目录下的 .env（内含 DATABASE_URL 等配置）
load_dotenv()

app = FastAPI(title="ArchiveSystem Hello API", version="0.2.0")

# 从 .env 读取数据库连接串；缺失时回退到默认占位值
DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql+psycopg://archive_app:archive_app@localhost:5432/archive_system",
)

# echo=False：生产环境不打 SQL；调试时设为 True 可在控制台看到每条 SQL
engine: Engine = create_engine(DATABASE_URL, pool_pre_ping=True, echo=False)


@app.get("/")
def root():
    return {
        "message": "Hello from ArchiveSystem server",
        "status": "ok",
        "db": DATABASE_URL.split("@")[-1],  # 只暴露 host/db，不泄露口令
    }


@app.get("/health")
def health():
    """应用自身健康（不依赖数据库）。"""
    return {"status": "healthy"}


@app.get("/db-health")
def db_health():
    """真实连库探测：执行 SELECT 1 / 取数据库版本 / 当前登录用户。"""
    try:
        with engine.connect() as conn:
            ok = conn.execute(text("SELECT 1")).scalar()
            version = conn.execute(text("SHOW server_version")).scalar()
            current_user = conn.execute(text("SELECT current_user")).scalar()
            db_name = conn.execute(text("SELECT current_database()")).scalar()
        return {
            "status": "ok" if ok == 1 else "error",
            "server_version": version,
            "current_user": current_user,
            "database": db_name,
        }
    except SQLAlchemyError as exc:
        return {"status": "error", "detail": str(exc)}


@app.get("/tables")
def list_tables():
    """列出 archive_system 当前 public 模式下的所有表。"""
    try:
        with engine.connect() as conn:
            rows = conn.execute(
                text(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    ORDER BY table_name
                    """
                )
            ).fetchall()
        tables = [r[0] for r in rows]
        return {"count": len(tables), "tables": tables}
    except SQLAlchemyError as exc:
        return {"status": "error", "detail": str(exc)}


@app.get("/now")
def db_now():
    """返回数据库服务器当前时间（证明库在实时工作）。"""
    try:
        with engine.connect() as conn:
            now = conn.execute(text("SELECT now()")).scalar()
        return {"server_time": str(now)}
    except SQLAlchemyError as exc:
        return {"status": "error", "detail": str(exc)}


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8000)
