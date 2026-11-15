# -*- coding: utf-8 -*-
"""
app/ui/modules/my_tasks.py
===========================
我的待办：列出当前用户需要审批的流程，点击可查看进度并审批。
"""
import json
from PySide6.QtCore import Qt  # 审批按钮读取表格 UserRole 时也需要此模块级导入。

from PySide6.QtWidgets import QTableWidget, QLabel

from app.ui.base import BaseModule
from app.ui.flow_dialog import FlowDetailDialog
from app.core.api_client import ApiError


class MyTasksModule(BaseModule):
    title = "我的待办"

    def __init__(self):
        super().__init__()
        self._build_ui()

    def _build_ui(self):
        from PySide6.QtWidgets import QVBoxLayout
        from PySide6.QtCore import Qt
        root = QVBoxLayout(self)
        tools = self.build_toolbar(
            ("审批", self._on_approve, None),
            ("刷新", self.refresh, None),
        )
        root.addLayout(tools)
        self.table = QTableWidget()
        root.addWidget(self.table, 1)

    def refresh(self):
        try:
            res = self.api.my_tasks().get("data", [])
        except ApiError as e:
            self.handle_error(e, "加载待办")
            return
        self._rows = res
        headers = ["标题", "业务类型", "当前节点", "申请人", "创建时间", "关联档案"]
        self.setup_table(self.table, headers)
        self.table.setRowCount(len(res))
        for r, t in enumerate(res):
            arch = ""
            try:
                items = json.loads(t.get("archive_summary") or "[]")
                arch = "、".join(f"{x.get('archive_name')}" for x in items[:3])
            except Exception:  # noqa
                pass
            biz = "借阅" if t.get("biz_type") == "borrow" else "销毁"
            vals = [t.get("title"), biz, t.get("current_node_name"),
                    t.get("applicant_name"), t.get("created_at"), arch]
            for c, val in enumerate(vals):
                from PySide6.QtCore import Qt
                from PySide6.QtWidgets import QTableWidgetItem
                item = QTableWidgetItem("" if val is None else str(val))
                item.setData(Qt.UserRole, t)
                self.table.setItem(r, c, item)

    def _on_approve(self):
        row = self.table.currentRow()
        if row < 0:
            self.show_info("请先选择一条待办")
            return
        item = self.table.item(row, 0)
        task = item.data(Qt.UserRole) if item else None
        if not task:
            return
        dlg = FlowDetailDialog(self.api, flow_id=task["flow_id"])
        dlg.exec()
        self.refresh()
