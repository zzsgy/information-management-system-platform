# -*- coding: utf-8 -*-
"""
app/core/config.py
====================
本文件是服务端全局配置中心。
作用：从 .env 文件（或系统环境变量）读取所有运行时配置，
      供数据库连接、JWT、文件存储、网络监听等模块统一调用。
说明：使用 pydantic-settings 读取 .env，未设置时给出开发默认值。
"""
import os
import sys
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# 项目根目录：
# - 开发态：app 目录的上一级（即 server/）
# - 打包态（PyInstaller onedir）：可执行文件所在目录即为程序根目录，
#   这样 .env 与 storage 都能稳定定位在 exe 同目录下，与启动时的「当前目录」无关。
if getattr(sys, "frozen", False):
    BASE_DIR = Path(sys.executable).resolve().parent
else:
    BASE_DIR = Path(__file__).resolve().parent.parent.parent


class Settings(BaseSettings):
    """全局配置模型，字段对应 .env 中的同名变量。"""

    model_config = SettingsConfigDict(
        env_file=str(BASE_DIR / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",  # .env 里多余的变量忽略，不报错
    )

    # ---- 应用基础信息 ----
    APP_NAME: str = "ArchiveSystem"
    APP_ENV: str = "dev"          # dev / prod
    HOST: str = "0.0.0.0"         # 监听地址（开发用 127.0.0.1，联调用 0.0.0.0）
    PORT: int = 8000              # API 服务端口

    # ---- 数据库 ----
    # 默认空：未显式配置时采用内置 SQLite 文件库（storage/app.db），
    # 这样「交付安装包」开箱即用、零外部依赖；
    # 开发环境仍可在 .env 中配置 PostgreSQL（见 DATABASE_URL）。
    DATABASE_URL: str = ""

    # ---- JWT 认证 ----
    JWT_SECRET: str = "CHANGE_ME_SECRET"   # 生产环境必须修改为强随机值
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 720  # 访问令牌有效期（12 小时）

    # ---- 文件存储 ----
    STORAGE_ROOT: str = str(BASE_DIR / "storage")  # 电子文件落地根目录
    MAX_UPLOAD_MB: int = 200                       # 单个文件大小上限（MB）

    # ---- CORS（跨域，联调时客户端可能用浏览器调试）----
    CORS_ORIGINS: str = "*"        # 生产建议改成具体前端地址

    @property
    def storage_path(self) -> Path:
        """返回文件存储根目录 Path 对象，并确保目录存在。

        若 STORAGE_ROOT 为相对路径，则基于程序根目录（BASE_DIR）解析，
        避免依赖启动时的「当前工作目录」。
        """
        p = Path(self.STORAGE_ROOT)
        if not p.is_absolute():
            p = BASE_DIR / p
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def database_url(self) -> str:
        """返回实际使用的数据库连接串。

        若 .env 中配置了 DATABASE_URL 则优先使用（如 PostgreSQL）；
        否则回退到内置 SQLite 文件库，保证交付环境零外部依赖。
        """
        if self.DATABASE_URL:
            return self.DATABASE_URL
        sqlite_path = BASE_DIR / "storage" / "app.db"
        sqlite_path.parent.mkdir(parents=True, exist_ok=True)
        return f"sqlite:///{sqlite_path}"


# 全局唯一配置实例，其他模块直接 from app.core.config import settings
settings = Settings()

# 兼容旧字段：部分模块习惯用 DATABASE_URL，统一走 settings.database_url
DATABASE_URL = settings.database_url
