# -*- coding: utf-8 -*-
"""
run.py
======
本文件是「服务端可执行程序」的启动入口，供 PyInstaller 打包成
ArchiveServer.exe 后直接运行。
作用：加载配置（监听地址/端口），用 uvicorn 启动 FastAPI 应用。
说明：打包后运行 ArchiveServer.exe 即等价于 `uvicorn app.main:app`。
"""
import os
import sys
import subprocess

# PyInstaller 窗口化打包（console=False）运行时 sys.stdout/stderr 为 None，
# uvicorn 的 DefaultFormatter 会调用 sys.stdout.isatty()，导致
# AttributeError: 'NoneType' object has no attribute 'isatty'
# 在导入 uvicorn / 初始化日志之前，将 None 重定向到 devnull。
if getattr(sys, "frozen", False):
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w")

# 将本文件所在目录（server/）加入模块搜索路径，保证 `import app` 在开发态
# 与各类虚拟环境/打包环境中都能稳定解析。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import uvicorn

from app.core.config import settings
from app.core.logging import get_logger
from app.main import app

logger = get_logger("ArchiveServer")


def _free_port(port: int):
    """释放被占用的监听端口：结束占用该端口的进程（如旧版本服务端残留）。

    避免「旧服务端仍占用 8000 端口、新服务端起不来」导致 Web / 接口无法访问。
    Windows 下通过 netstat 定位 PID 后 taskkill；非 Windows 跳过。
    """
    if os.name != "nt":
        return
    try:
        out = subprocess.check_output(
            f'netstat -ano | findstr /R ":{port}[ ]"',
            shell=True, text=True, timeout=10,
        )
        for line in out.splitlines():
            cols = line.split()
            if not cols:
                continue
            local = cols[1] if len(cols) > 1 else cols[0]
            pid = cols[-1]
            if local.endswith(f":{port}") and pid.isdigit() and pid != "0":
                logger.info(f"端口 {port} 被进程 {pid} 占用，尝试释放")
                subprocess.run(f"taskkill /PID {pid} /F", shell=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:  # noqa
        logger.warning(f"释放端口 {port} 失败（可忽略）：{e}")


def main():
    """以单进程方式启动 uvicorn 服务。"""
    _free_port(settings.PORT)
    logger.info(f"档案管理系统服务端启动，监听 {settings.HOST}:{settings.PORT}")
    uvicorn.run(
        app,
        host=settings.HOST,
        port=settings.PORT,
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    main()
