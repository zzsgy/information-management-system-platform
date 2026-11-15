# -*- coding: utf-8 -*-
"""
app/core/logging.py
====================
本文件配置服务端日志：
- 同时输出到控制台和 logs/server.log 文件（按大小滚动）。
- 提供 get_logger() 给各模块统一取用。
- 业务关键操作（登录、增删改、下载等）除写日志文件外，还会落库到 operation_log
  表，由 services 中的日志服务负责，本文件只负责技术日志。
"""
import logging
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

from app.core.config import BASE_DIR

# 日志目录：server/logs/
LOG_DIR = BASE_DIR / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "server.log"


def _build_logger() -> logging.Logger:
    """构造并返回根日志器，避免重复添加 handler。"""
    logger = logging.getLogger("ArchiveServer")
    if logger.handlers:
        return logger

    logger.setLevel(logging.INFO)
    fmt = logging.Formatter(
        "%(asctime)s | %(levelname)-7s | %(name)s | %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    # 控制台输出
    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(fmt)

    # 文件输出（单个最大 5MB，保留 3 个备份）
    file_handler = RotatingFileHandler(
        LOG_FILE, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8"
    )
    file_handler.setFormatter(fmt)

    logger.addHandler(console)
    logger.addHandler(file_handler)
    logger.propagate = False
    return logger


# 全局日志器
app_logger = _build_logger()


def get_logger(name: str = "ArchiveServer") -> logging.Logger:
    """获取一个带统一格式的日志器。"""
    lg = logging.getLogger(name)
    if not lg.handlers:
        lg = app_logger
    return lg
