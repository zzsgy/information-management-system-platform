# -*- coding: utf-8 -*-
"""
app/ui/modules/notice_manage.py
================================
公告管理模块：发布公告、查看公告列表。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTableWidget, QPushButton, QLabel,
    QLineEdit, QTextEdit, QComboBox, QDialog, QFormLayout, QDialogButtonBox,
    QListWidget, QListWidgetItem, QMessageBox,
)
from PySide6.QtCore import Qt

from app.ui.base import BaseModule
from app.core.api_client import ApiError
from app.core import session


class NoticeModule(BaseModule):
    title = "公告管理"

    def __init__(self):
        super().__init__()
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)
        actions = [("刷新", self.refresh, None)]  # 可阅读公告不代表具有发布权限。
        if session.has_perm("notice.publish"):
            actions.insert(0, ("发布公告", self._on_publish, None))
        tools = self.build_toolbar(*actions)
        v.addLayout(tools)
        self.table = QTableWidget()
        v.addWidget(self.table, 1)

    def refresh(self):
        try:
            res = self.api.get("/api/notices",
                               params={"page": 1, "page_size": 100}).get("data", {})
            rows = res.get("items", [])
        except ApiError as e:
            self.handle_error(e, "查询公告"); return
        self.fill_table(self.table, rows,
                        ["ID", "标题", "类型", "发布状态", "发布时间"],
                        mapping=["id", "title", "notice_type", "publish_status", "publish_time"])

    def _on_publish(self):
        dlg = NoticePublishDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self.refresh()

    def showEvent(self, event):
        if self.table.rowCount() == 0:
            self.refresh()
        super().showEvent(event)


class NoticePublishDialog(QDialog):
    def __init__(self, module: NoticeModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.setWindowTitle("发布公告")
        self.resize(440, 420)
        self._build_ui()
        self._load_users()

    def _build_ui(self):
        v = QVBoxLayout(self)
        form = QFormLayout()
        self.title = QLineEdit()
        self.ntype = QComboBox(); self.ntype.addItems(["system", "business", "urgent"])
        self.content = QTextEdit(); self.content.setFixedHeight(120)
        form.addRow("标题*：", self.title)
        form.addRow("类型：", self.ntype)
        form.addRow("内容：", self.content)
        v.addLayout(form)
        v.addWidget(QLabel("接收人（不选择=全员）："))
        self.receivers = QListWidget()
        self.receivers.setSelectionMode(QListWidget.MultiSelection)
        v.addWidget(self.receivers, 1)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        v.addWidget(btns)

    def _load_users(self):
        try:
            res = self.api.get("/api/users", params={"page": 1, "page_size": 200}).get("data", {})
            self.user_list = res.get("items", [])
        except Exception:
            self.user_list = []
        for u in self.user_list:
            item = QListWidgetItem(f"{u.get('real_name')}({u.get('username')})")
            item.setData(Qt.UserRole, u["id"])
            self.receivers.addItem(item)

    def _submit(self):
        if not self.title.text().strip():
            QMessageBox.warning(self, "提示", "请填写标题")
            return
        ids = [self.receivers.item(i).data(Qt.UserRole) for i in range(self.receivers.count())
               if self.receivers.item(i).isSelected()]
        payload = {
            "title": self.title.text().strip(),
            "content": self.content.toPlainText().strip() or None,
            "notice_type": self.ntype.currentText(),
            "receiver_user_ids": ids,
        }
        try:
            self.api.post("/api/notices", payload)
            QMessageBox.information(self, "提示", "公告已发布")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"发布失败：{e}")
            return
        self.accept()
