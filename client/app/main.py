# -*- coding: utf-8 -*-
"""
app/main.py
===========
客户端程序入口：启动 Qt 应用 → 登录 → 主窗口。
"""
import sys

from PySide6.QtWidgets import QApplication
from PySide6.QtCore import Qt

from app.core.theme import apply_saved_theme
from app.core.tasks import shutdown_tasks
from app.ui.login import LoginDialog
from app.ui.main_window import MainWindow


def main():
    # 高 DPI 适配
    if hasattr(Qt, "AA_EnableHighDpiScaling"):
        QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
    if hasattr(Qt, "AA_UseHighDpiPixmaps"):
        QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)

    app = QApplication(sys.argv)
    app.setApplicationName("企业档案管理系统")
    # 应用保存的主题（浅色/深色/跟随系统），默认跟随系统
    apply_saved_theme(app)

    login = LoginDialog()
    if login.exec() == LoginDialog.Accepted:
        win = MainWindow()
        app._main_window = win  # 应用持有当前主窗口，重新登录时由窗口控制器替换。
        win.show()
        exit_code = app.exec()  # 主界面关闭后的 Qt 返回码。
        shutdown_tasks()  # 在解释器清理 Qt 对象前等待已提交的请求安全结束。
        sys.exit(exit_code)
    else:
        shutdown_tasks()  # 取消登录也可能紧接在后台认证结束后，先释放工作器。
        sys.exit(0)


if __name__ == "__main__":
    main()
