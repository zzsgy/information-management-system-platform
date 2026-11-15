# -*- coding: utf-8 -*-
"""
app/ui/login.py
================
登录窗口：输入服务器地址、账号、密码完成登录。
成功后写入会话，主窗口方可打开。
"""
from PySide6.QtWidgets import (
    QDialog, QLabel, QLineEdit, QPushButton, QVBoxLayout, QHBoxLayout,
    QCheckBox, QFormLayout, QSpacerItem, QSizePolicy,
)
from PySide6.QtCore import Qt

from app.core import config
from app.core.api_client import ApiClient, ApiError
from app.core import session
from app.core.tasks import run_task


class LoginDialog(QDialog):
    """登录对话框。"""

    def __init__(self):
        super().__init__()
        self.api = ApiClient()
        self._login_running = False  # 防止重复提交及登录途中关闭后留下后台登录状态。
        self.setWindowTitle("企业档案管理系统 · 登录")
        self.setMinimumWidth(380)
        self.setModal(True)
        self._build_ui()
        # 回显已保存的服务器地址
        self.server_edit.setText(config.load_config()["SERVER_URL"])

    def _build_ui(self):
        main = QVBoxLayout(self)
        main.setSpacing(12)
        main.addItem(QSpacerItem(0, 10, QSizePolicy.Minimum, QSizePolicy.Fixed))

        title = QLabel("企业档案管理系统")
        title.setObjectName("title")
        title.setAlignment(Qt.AlignCenter)
        main.addWidget(title)

        sub = QLabel("请登录以继续使用")
        sub.setAlignment(Qt.AlignCenter)
        sub.setStyleSheet("color:#7a8a9c;")
        main.addWidget(sub)

        form = QFormLayout()
        form.setSpacing(10)
        self.server_edit = QLineEdit()
        self.server_edit.setPlaceholderText("例如 http://127.0.0.1:8000")
        self.user_edit = QLineEdit()
        self.user_edit.setPlaceholderText("登录账号")
        self.pwd_edit = QLineEdit()
        self.pwd_edit.setEchoMode(QLineEdit.Password)
        self.pwd_edit.returnPressed.connect(self._do_login)
        form.addRow("服务器：", self.server_edit)
        form.addRow("账号：", self.user_edit)
        form.addRow("密码：", self.pwd_edit)
        main.addLayout(form)

        self.err_label = QLabel("")
        self.err_label.setObjectName("error")
        self.err_label.setWordWrap(True)
        main.addWidget(self.err_label)

        btn_row = QHBoxLayout()
        btn_row.addStretch(1)
        self.login_btn = QPushButton("登 录")
        self.login_btn.setMinimumWidth(120)
        self.login_btn.clicked.connect(self._do_login)
        btn_row.addWidget(self.login_btn)
        main.addLayout(btn_row)

        main.addItem(QSpacerItem(0, 10, QSizePolicy.Minimum, QSizePolicy.Fixed))

    def _do_login(self):
        """捕获输入后后台登录；保存配置失败时停留在当前窗口显示错误。"""
        if self._login_running:
            return
        self.err_label.setText("")
        url = self.server_edit.text().strip()
        if not url:
            self.err_label.setText("请填写服务器地址")
            return
        # 保存服务器地址并刷新客户端
        try:
            url = config.normalize_server_url(url)
            config.save_server_url(url)
            self.api.base_url = url
        except (OSError, ValueError) as error:
            self.err_label.setText(str(error))
            return

        user = self.user_edit.text().strip()
        pwd = self.pwd_edit.text()
        if not user or not pwd:
            self.err_label.setText("请填写账号与密码")
            return

        session.clear()
        self._login_running = True
        self.login_btn.setText("登录中...")
        run_task(self, lambda: self.api.login(user, pwd), self._login_success,
                 self._login_error, controls=(self.login_btn, self.server_edit,
                                             self.user_edit, self.pwd_edit),
                 token_sensitive=False)

    def _login_success(self, data):
        """只在主线程建立登录会话和关闭窗口，不持久化密码及令牌。"""
        self._login_running = False
        self.pwd_edit.clear()
        session.set_session(data["access_token"], data, self.api.base_url)
        self.accept()

    def _login_error(self, error):
        """后台认证失败后恢复输入，避免界面冻结。"""
        self._login_running = False
        self.err_label.setText(str(error))
        self.login_btn.setText("登 录")

    def reject(self):
        """网络认证尚未完成时不允许窗口关闭，以免后台结果在关闭后建立会话。"""
        if not self._login_running:
            super().reject()
