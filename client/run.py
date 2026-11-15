# -*- coding: utf-8 -*-
"""
run.py
======
「客户端可执行程序」的启动入口，供 PyInstaller 打包成
ArchiveClient.exe 后直接运行；开发态可 `python run.py` 启动。

作用：把脚本所在目录加入模块搜索路径（兼容虚拟环境），
再调用 app.main.main() 进入 Qt 应用。
"""
import os
import sys

# 兼容虚拟环境下脚本目录未加入 sys.path 的情况（PyInstaller 打包后不需要）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.main import main  # noqa: E402

if __name__ == "__main__":
    main()
