# -*- coding: utf-8 -*-
"""
app/ui/main_window.py
=====================
主窗口：左侧导航 + 右侧模块区 + 底部状态栏。
导航项根据当前登录用户的权限（及是否超级管理员）动态显示。
"""
from PySide6.QtWidgets import (
    QMainWindow, QWidget, QListWidget, QListWidgetItem, QStackedWidget,
    QHBoxLayout, QVBoxLayout, QLabel, QStatusBar, QPushButton, QMenu,
    QMessageBox, QInputDialog, QLineEdit, QApplication,
)
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QAction

from app.core import session
from app.core.api_client import ApiClient, ApiError
from app.core.tasks import run_task
from app.ui.modules.archive_manage import ArchiveModule
from app.core.i18n import role_name
from app.ui.modules.dashboard import DashboardModule
from app.ui.modules.label_print import LabelPrintModule
from app.ui.modules.borrow_manage import BorrowModule
from app.ui.modules.destroy_manage import DestroyModule
from app.ui.modules.damage_manage import DamageModule
from app.ui.modules.notice_manage import NoticeModule
from app.ui.modules.log_view import LogModule
from app.ui.modules.sys_manage import SysModule
from app.ui.modules.my_tasks import MyTasksModule
from app.ui.modules.approval_config import ApprovalConfigModule
from app.ui.modules.file_manage import FileModule
from app.ui.modules.stats import StatsModule
from app.ui.modules.security_ops import SecurityOpsModule


class MainWindow(QMainWindow):
    """客户端主窗口。"""

    def __init__(self):
        super().__init__()
        self.api = ApiClient()
        self._reauthenticating = False  # 保护过期会话重新登录过程，避免重复弹窗。
        self.setWindowTitle("企业档案管理系统")
        self.resize(1180, 760)
        self.modules = {}          # label -> widget
        self.nav_items = []        # (label, perms, cls)
        self._build_nav_def()
        self._build_ui()
        self._build_statusbar()
        self._apply_permissions()
        # 默认选中第一个可见模块
        if self.nav.count() > 0:
            self.nav.setCurrentRow(0)
            self._on_nav_changed(0)
        self._session_timer = QTimer(self)  # 请求收到 401 后及时退出失效的业务界面。
        self._session_timer.timeout.connect(self._check_session)
        self._session_timer.start(1000)

    # ---------------- 导航定义 ----------------
    def _build_nav_def(self):
        self.nav_items = [
            ("首页", ("archive.read",), DashboardModule),
            ("档案管理", ("archive.read",), ArchiveModule),
            ("电子文件", ("archive.read",), FileModule),
            ("标签打印", ("archive.read",), LabelPrintModule),
            ("借阅管理", ("borrow.apply", "borrow.approve"), BorrowModule),
            ("销毁管理", ("destroy.apply", "destroy.approve", "destroy.execute"), DestroyModule),
            ("损坏管理", ("archive.write",), DamageModule),
            ("公告管理", ("notice.read", "notice.publish"), NoticeModule),
            ("日志审计", ("log.read",), LogModule),
            ("统计台账", ("archive.read",), StatsModule),
            ("数据安全运维", ("security.manage",), SecurityOpsModule),  # 与档案业务管理员分权。
            ("我的待办", ("approval.todo.view",), MyTasksModule),
            ("审批流配置", ("approval.template.manage",), ApprovalConfigModule),  # 仅超级管理员可见
            ("系统管理", ("user.read",), SysModule),  # 仅超级管理员可见（见下方过滤）
        ]

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QHBoxLayout(central)
        root.setSpacing(0)
        root.setContentsMargins(0, 0, 0, 0)

        # 左侧导航（样式由全局 QSS 的 #SideNav 选择器控制，随主题变化）
        self.nav = QListWidget()
        self.nav.setFixedWidth(160)
        self.nav.setObjectName("SideNav")
        self.nav.currentRowChanged.connect(self._on_nav_changed)
        root.addWidget(self.nav)

        # 左侧底部：用户卡片（头像 + 姓名 + 设置入口）
        self.user_card = QWidget()
        self.user_card.setObjectName("UserCard")
        uc = QVBoxLayout(self.user_card)
        uc.setContentsMargins(12, 10, 12, 10)
        uc.setSpacing(6)
        self.avatar = QLabel()
        self.avatar.setFixedSize(42, 42)
        self.avatar.setObjectName("UserAvatar")
        self.user_card_name = QLabel()
        self.user_card_name.setObjectName("UserName")
        self.set_btn = QPushButton("设置 ▾")
        self.set_btn.setObjectName("UserSetBtn")
        # 设置菜单：修改密码 / 退出登录 / 外观（主题切换）
        menu = QMenu(self)
        act_pwd = QAction("修改密码", self)
        act_pwd.triggered.connect(self._change_password)
        act_logout = QAction("退出登录", self)
        act_logout.triggered.connect(self._logout)
        menu.addAction(act_pwd)
        menu.addAction(act_logout)
        theme_menu = menu.addMenu("外观（主题）")
        self._theme_actions = {}
        for tname in ("light", "dark", "system"):
            from app.core.theme import THEME_LABELS
            act = QAction(THEME_LABELS[tname], self)
            act.setCheckable(True)
            act.triggered.connect(lambda _checked, n=tname: self._switch_theme(n))
            theme_menu.addAction(act)
            self._theme_actions[tname] = act
        self.set_btn.setMenu(menu)
        self._refresh_theme_menu()
        uc.addWidget(self.avatar)
        uc.addWidget(self.user_card_name)
        uc.addWidget(self.set_btn)
        root.addWidget(self.user_card)

        # 右侧模块区
        self.stack = QStackedWidget()
        root.addWidget(self.stack, 1)

    def _build_statusbar(self):
        bar = QStatusBar()
        self.setStatusBar(bar)
        user = session.get_user()
        name = user.get("real_name") or user.get("username") or "未登录"
        roles = "、".join(role_name(r) for r in user.get("roles", []))
        self.user_label = QLabel(f"当前用户：{name}（{roles}）")
        bar.addWidget(self.user_label)
        bar.addPermanentWidget(QLabel("企业档案管理系统 V1.0"))
        self._update_user_card()

    def _update_user_card(self):
        """填充左侧底部用户卡片（头像首字 + 姓名）。"""
        user = session.get_user()
        name = user.get("real_name") or user.get("username") or "未登录"
        initial = (user.get("real_name") or user.get("username") or "?")[0]
        self.avatar.setText(initial)
        self.user_card_name.setText(name)

    def _switch_theme(self, name: str):
        """切换主题并持久化。"""
        from app.core.theme import set_theme
        set_theme(name)
        self._refresh_theme_menu()

    def _refresh_theme_menu(self):
        """在「外观」子菜单上勾选当前主题。"""
        from app.core import prefs
        cur = prefs.get_pref("theme", "system") or "system"
        for n, act in self._theme_actions.items():
            act.setChecked(n == cur)

    def _apply_permissions(self):
        """按权限/角色过滤导航项并实例化模块。"""
        for label, perms, cls in self.nav_items:
            # 按服务端实际权限显示；角色名称不充当客户端通行证。
            if not session.has_perm(*perms):
                continue
            item = QListWidgetItem(label)
            item.setTextAlignment(Qt.AlignLeft)
            self.nav.addItem(item)
            widget = cls()
            self.modules[label] = widget
            self.stack.addWidget(widget)

    # ---------------- 交互 ----------------
    def _on_nav_changed(self, row: int):
        """切换已授权模块，忽略导航重建时 Qt 发送的无效索引。"""
        if row < 0 or self.nav.item(row) is None:
            return
        label = self.nav.item(row).text()
        widget = self.modules.get(label)
        if widget is None:
            return
        self.stack.setCurrentWidget(widget)
        # 模块可选实现 refresh()
        if hasattr(widget, "refresh"):
            try:
                widget.refresh()
            except Exception as e:  # noqa
                widget.handle_error(e, "刷新")

    def _change_password(self):
        """掩码输入旧密码及新密码，修改成功后按服务端策略重新登录。"""
        old_pwd, ok1 = QInputDialog.getText(self, "修改密码", "原密码：", QLineEdit.Password)
        if not ok1 or not old_pwd:
            return
        new_pwd, ok2 = QInputDialog.getText(self, "修改密码", "新密码（12～128字符）：", QLineEdit.Password)
        if not ok2 or not new_pwd:
            return
        if not 12 <= len(new_pwd) <= 128:
            QMessageBox.warning(self, "提示", "新密码长度须为12～128字符")
            return
        run_task(self, lambda: self.api.change_password(old_pwd, new_pwd),
                 self._password_changed,
                 lambda error: QMessageBox.critical(self, "错误", f"修改失败：{error}"),
                 controls=(self.set_btn,))

    def _password_changed(self, _result):
        """密码修改会撤销所有服务端会话，不再使用旧令牌调用 me。"""
        QMessageBox.information(self, "提示", "密码修改成功，所有登录已失效，请使用新密码重新登录。")
        self._finish_logout()

    def _logout(self):
        """后台撤销当前令牌，连接失败时也退出本机界面。"""
        run_task(self, self.api.logout,
                 lambda _result: self._finish_logout(),
                 lambda _error: self._finish_logout(),
                 controls=(self.set_btn,), token_sensitive=False)

    def _check_session(self):
        """任一请求确认令牌失效后关闭旧数据界面并重新认证。"""
        if not session.get_token() and not self._reauthenticating:
            self._finish_logout()

    def _finish_logout(self):
        """以应用持有新主窗口，修复局部变量退出后新窗口被回收的问题。"""
        if self._reauthenticating:
            return
        self._reauthenticating = True
        self._session_timer.stop()
        from app.ui.login import LoginDialog
        app = QApplication.instance()  # 保持重新登录期间的 Qt 应用运行。
        app.setQuitOnLastWindowClosed(False)
        session.clear()
        self.hide()
        login = LoginDialog()  # 新账号的认证窗口，旧模块不会复用。
        if login.exec() == LoginDialog.Accepted:
            win = MainWindow()  # 全量重建基于新用户权限的模块与菜单。
            app._main_window = win
            win.show()
            app.setQuitOnLastWindowClosed(True)
        else:
            app.quit()
        self.close()
        self.deleteLater()
