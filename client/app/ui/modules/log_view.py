# -*- coding: utf-8 -*-
"""
app/ui/modules/log_view.py
==========================
日志审计模块：按模块/动作查询操作日志（审计留痕）。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTableWidget, QPushButton, QLabel,
    QComboBox, QLineEdit,
)

from app.ui.base import BaseModule
from app.core.api_client import ApiError


class LogModule(BaseModule):
    title = "日志审计"

    def __init__(self):
        super().__init__()
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)
        bar = QHBoxLayout()
        self.module = QComboBox()
        self.module.addItems(["全部模块", "auth", "archive", "borrow",
                              "destroy", "damage", "notice", "system", "log"])
        self.action = QLineEdit(); self.action.setPlaceholderText("动作（可选，如 login）")
        btn = QPushButton("查询"); btn.clicked.connect(self.refresh)
        bar.addWidget(QLabel("模块：")); bar.addWidget(self.module, 1)
        bar.addWidget(QLabel("动作：")); bar.addWidget(self.action, 1)
        bar.addWidget(btn)
        v.addLayout(bar)

        tools = self.build_toolbar(("刷新", self.refresh, None))
        v.addLayout(tools)
        self.table = QTableWidget()
        v.addWidget(self.table, 1)

    def refresh(self):
        params = {"page": 1, "page_size": 100}
        m = self.module.currentText()
        if m != "全部模块":
            params["module"] = m
        a = self.action.text().strip()
        if a:
            params["action"] = a
        try:
            res = self.api.get("/api/logs", params=params).get("data", {})
            rows = res.get("items", [])
        except ApiError as e:
            self.handle_error(e, "查询日志"); return
        self.fill_table(self.table, rows,
                        ["ID", "用户ID", "模块", "动作", "对象类型", "对象ID", "IP", "时间", "详情"],
                        mapping=["id", "user_id", "module", "action", "target_type",
                                 "target_id", "ip", "created_at", "detail"])

    def showEvent(self, event):
        if self.table.rowCount() == 0:
            self.refresh()
        super().showEvent(event)
