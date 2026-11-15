@echo off
REM ============================================================
REM  run_server.bat  ——  启动档案管理系统服务端（开发/联调用）
REM  用法：双击本文件，或在命令行执行
REM  说明：使用 server/.venv 中的 Python 运行 run.py
REM ============================================================
cd /d %~dp0
call .venv\Scripts\activate.bat
python run.py
pause
