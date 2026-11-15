# -*- coding: utf-8 -*-
"""
app/ui/modules/damage_manage.py
===============================
损坏管理模块：损坏登记、处理。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTableWidget, QPushButton, QLabel,
    QLineEdit, QTextEdit, QComboBox, QDialog, QFormLayout, QDialogButtonBox,
    QMessageBox,
)

from app.ui.base import BaseModule
from app.core.api_client import ApiError
from app.core.i18n import status_name


class DamageModule(BaseModule):
    title = "损坏管理"

    def __init__(self):
        super().__init__()
        self.archives = []
        self.users = []
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)
        tools = self.build_toolbar(
            ("登记损坏", self._on_register, None),
            ("处理", self._on_handle, "ghost"),
            ("刷新", self.refresh, None),
        )
        v.addLayout(tools)
        self.table = QTableWidget()
        v.addWidget(self.table, 1)

    def refresh(self):
        try:
            res = self.api.get("/api/damages",
                               params={"page": 1, "page_size": 100}).get("data", {})
            rows = res.get("items", [])
        except ApiError as e:
            self.handle_error(e, "查询损坏记录"); return
        self.fill_table(self.table, rows,
                        ["ID", "档案", "损坏类型", "描述", "状态", "登记人", "登记时间"],
                        mapping=["id", "archive_name", "damage_type", "damage_desc",
                                 lambda r: status_name(r.get("status")), "reporter_name", "created_at"])

    def _on_register(self):
        dlg = DamageRegisterDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self.refresh()

    def _on_handle(self):
        row = self.selected_row(self.table)
        if not row:
            self.show_info("请先选择一条损坏记录"); return
        if row.get("status") not in ("reported", "processing", "resolved"):
            self.show_info("已关闭的损坏记录不能继续处理"); return
        dlg = DamageHandleDialog(self, row["id"], row.get("status"))
        if dlg.exec() == QDialog.Accepted:
            self.refresh()

    def showEvent(self, event):
        if self.table.rowCount() == 0:
            self.refresh()
        super().showEvent(event)


class DamageRegisterDialog(QDialog):
    def __init__(self, module: DamageModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.setWindowTitle("登记损坏")
        self.resize(400, 320)
        self._build_ui()
        self._load()

    def _build_ui(self):
        form = QFormLayout(self)
        self.archive = QComboBox()
        self.dtype = QLineEdit()
        self.desc = QTextEdit(); self.desc.setFixedHeight(70)
        form.addRow("所属档案*：", self.archive)
        form.addRow("损坏类型：", self.dtype)
        form.addRow("损坏描述：", self.desc)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        form.addRow(btns)

    def _load(self):
        try:
            res = self.api.get("/api/archives", params={"page": 1, "page_size": 200}).get("data", {})
            self.archives = res.get("items", [])
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"加载档案失败：{e}")
            self.archives = []
        for a in self.archives:
            self.archive.addItem(f"{a['archive_no']} {a['archive_name']}", a["id"])

    def _submit(self):
        if self.archive.currentData() is None:
            QMessageBox.warning(self, "提示", "请选择档案")
            return
        if not self.desc.toPlainText().strip():
            QMessageBox.warning(self, "提示", "请填写损坏描述")
            return
        payload = {
            "archive_id": self.archive.currentData(),
            "damage_type": self.dtype.text().strip() or None,
            "damage_desc": self.desc.toPlainText().strip() or None,
        }
        try:
            self.api.post("/api/damages", payload)
            QMessageBox.information(self, "提示", "损坏已登记")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"登记失败：{e}")
            return
        self.accept()


class DamageHandleDialog(QDialog):
    def __init__(self, module: DamageModule, damage_id: int, current_status="reported"):
        """按服务端状态机提供下一步状态，不允许已登记直接关闭。"""
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.damage_id = damage_id
        self.setWindowTitle("处理损坏")
        self.resize(380, 280)
        form = QFormLayout(self)
        self.method = QLineEdit()
        self.result = QLineEdit()
        self.status = QComboBox()  # 显示中文、提交稳定状态码。
        if current_status == "resolved":
            self.status.addItem("关闭记录", "closed")
        else:
            self.status.addItem("已解决", "resolved")
            self.status.addItem("处理中", "processing")
        form.addRow("处理方式：", self.method)
        form.addRow("处理结果：", self.result)
        form.addRow("状态：", self.status)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        form.addRow(btns)

    def _submit(self):
        """处理方式和结果必须明确，防止空操作被视为已经解决。"""
        if not self.method.text().strip() or not self.result.text().strip():
            QMessageBox.warning(self, "提示", "请填写处理方式和处理结果")
            return
        payload = {
            "handling_method": self.method.text().strip() or None,
            "handling_result": self.result.text().strip() or None,
            "status": self.status.currentData(),
        }
        try:
            self.api.post(f"/api/damages/{self.damage_id}/handle", payload)
            QMessageBox.information(self, "提示", "损坏处理完成")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"处理失败：{e}")
            return
        self.accept()
