# -*- coding: utf-8 -*-
"""
app/ui/modules/destroy_manage.py
================================
销毁管理模块：申请、审批、执行。执行时强制选择实际销毁的档案并留痕。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTabWidget, QTableWidget, QPushButton,
    QLabel, QLineEdit, QTextEdit, QDialog, QFormLayout, QDialogButtonBox,
    QListWidget, QMessageBox,
)
from PySide6.QtCore import QDate

from app.ui.base import BaseModule
from app.core.api_client import ApiError
from app.core.i18n import status_name
from app.core import session
from app.core.workflow_rules import approved_destroy_archives, destroy_execution_ids


class DestroyModule(BaseModule):
    title = "销毁管理"

    def __init__(self):
        super().__init__()
        self._build_ui()

    def _build_ui(self):
        root = QVBoxLayout(self)
        tabs = QTabWidget()
        self.apply_tab = self._build_apply_tab()
        self.approve_tab = self._build_approve_tab()
        self.exec_tab = self._build_exec_tab()
        tabs.addTab(self.apply_tab, "销毁申请")
        tabs.addTab(self.approve_tab, "销毁审批")
        tabs.addTab(self.exec_tab, "销毁执行")
        tabs.setTabEnabled(0, session.has_perm("destroy.apply"))
        tabs.setTabEnabled(1, session.has_perm("destroy.approve"))
        tabs.setTabEnabled(2, session.has_perm("destroy.execute"))
        root.addWidget(tabs, 1)

    # ---- 申请 ----
    def _build_apply_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        tools = self.build_toolbar(("新建申请", self._on_apply, None),
                                   ("刷新", self._refresh_apply, None))
        v.addLayout(tools)
        self.a_table = QTableWidget(); v.addWidget(self.a_table, 1)
        return w

    def _refresh_apply(self):
        try:
            res = self.api.get("/api/destroys",
                               params={"page": 1, "page_size": 100}).get("data", {})
            rows = res.get("items", [])
        except ApiError as e:
            self.handle_error(e, "查询销毁申请"); return
        self.fill_table(self.a_table, rows,
                        ["ID", "销毁原因", "范围说明", "状态", "申请人", "创建时间"],
                        mapping=["id", "reason", "scope_desc",
                                 lambda r: status_name(r.get("status")),
                                 "applicant_name", "created_at"])

    def _on_apply(self):
        dlg = DestroyApplyDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self._refresh_apply()

    # ---- 审批 ----
    def _build_approve_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        tools = self.build_toolbar(("通过", self._on_pass, None),
                                   ("驳回", self._on_reject, "danger"),
                                   ("查看进度", self._on_progress, "ghost"),
                                   ("刷新", self._refresh_approve, None))
        v.addLayout(tools)
        self.p_table = QTableWidget(); v.addWidget(self.p_table, 1)
        return w

    def _on_progress(self):
        row = self.selected_row(self.p_table)
        if not row:
            self.show_info("请先选择一条记录")
            return
        from app.ui.flow_dialog import FlowDetailDialog
        dlg = FlowDetailDialog(self.api, biz_type="destroy", biz_id=row["id"])
        dlg.exec()
        self._refresh_approve()

    def _refresh_approve(self):
        try:
            res = self.api.get("/api/destroys", params={"status": "pending",
                               "page": 1, "page_size": 100}).get("data", {})
            rows = res.get("items", [])
        except ApiError as e:
            self.handle_error(e, "查询待审批"); return
        self.fill_table(self.p_table, rows,
                        ["ID", "销毁原因", "申请人", "创建时间"],
                        mapping=["id", "reason", "applicant_name", "created_at"])

    def _on_pass(self):
        self._approve(True)

    def _on_reject(self):
        self._approve(False)

    def _approve(self, approve: bool):
        row = self.selected_row(self.p_table)
        if not row:
            self.show_info("请先选择一条待审批记录"); return
        from PySide6.QtWidgets import QInputDialog
        opinion, ok = QInputDialog.getText(self, "审批意见",
                                           "审批意见：" + ("（通过）" if approve else "（驳回）"))
        if not ok:
            return
        try:
            self.api.post(f"/api/destroys/{row['id']}/approve",
                          params={"approve": str(approve).lower(),
                                  "opinion": opinion or None})
            self.show_info("审批完成"); self._refresh_approve()
        except Exception as e:  # noqa
            self.handle_error(e, "审批")

    # ---- 执行 ----
    def _build_exec_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        tools = self.build_toolbar(("执行销毁", self._on_exec, "danger"),
                                   ("刷新", self._refresh_exec, None))
        v.addLayout(tools)
        self.e_table = QTableWidget(); v.addWidget(self.e_table, 1)
        return w

    def _refresh_exec(self):
        try:
            res = self.api.get("/api/destroys", params={"status": "approved",
                               "page": 1, "page_size": 100}).get("data", {})
            rows = res.get("items", [])
        except ApiError as e:
            self.handle_error(e, "查询待执行"); return
        self.fill_table(self.e_table, rows,
                        ["ID", "销毁原因", "申请人", "创建时间"],
                        mapping=["id", "reason", "applicant_name", "created_at"])

    def _on_exec(self):
        row = self.selected_row(self.e_table)
        if not row:
            self.show_info("请先选择一条已批准的销毁申请"); return
        dlg = DestroyExecDialog(self, row["id"])
        if dlg.exec() == QDialog.Accepted:
            self._refresh_exec()

    def refresh(self):
        self._refresh_apply(); self._refresh_approve(); self._refresh_exec()

    def showEvent(self, event):
        if self.a_table.rowCount() == 0:
            self._refresh_apply()
        super().showEvent(event)


class DestroyApplyDialog(QDialog):
    def __init__(self, module: DestroyModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.setWindowTitle("新建销毁申请")
        self.resize(440, 480)
        self._build_ui()
        self._load_archives()

    def _build_ui(self):
        v = QVBoxLayout(self)
        v.addWidget(QLabel("选择拟销毁档案（可多选）："))
        self.list = QListWidget(); self.list.setSelectionMode(QListWidget.MultiSelection)
        v.addWidget(self.list, 1)
        form = QFormLayout()
        self.reason = QTextEdit(); self.reason.setFixedHeight(60)
        self.scope = QLineEdit()
        form.addRow("销毁原因：", self.reason)
        form.addRow("范围说明：", self.scope)
        v.addLayout(form)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        v.addWidget(btns)

    def _load_archives(self):
        try:
            res = self.api.get("/api/archives", params={"page": 1, "page_size": 200,
                               "status": "archive"}).get("data", {})
            self.archives = res.get("items", [])
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"加载档案失败：{e}")
            self.archives = []
        for a in self.archives:
            self.list.addItem(f"{a['archive_no']} {a['archive_name']}")

    def _submit(self):
        idxs = self.list.selectedIndexes()
        if not idxs:
            QMessageBox.warning(self, "提示", "请至少选择一份档案")
            return
        ids = [self.archives[i.row()]["id"] for i in idxs]
        if not self.reason.toPlainText().strip():
            QMessageBox.warning(self, "提示", "请填写销毁原因")
            return
        payload = {
            "archive_ids": ids,
            "reason": self.reason.toPlainText().strip() or None,
            "scope_desc": self.scope.text().strip() or None,
        }
        try:
            self.api.post("/api/destroys", payload)
            QMessageBox.information(self, "提示", "销毁申请已提交")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"提交失败：{e}")
            return
        self.accept()


class DestroyExecDialog(QDialog):
    def __init__(self, module: DestroyModule, destroy_id: int):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.destroy_id = destroy_id
        self.setWindowTitle("执行销毁")
        self.resize(440, 460)
        self._build_ui()
        self._load_archives()

    def _build_ui(self):
        v = QVBoxLayout(self)
        form = QFormLayout()
        self.witness = QLineEdit()
        self.method = QLineEdit(); self.method.setPlaceholderText("如：碎纸+焚毁")
        form.addRow("监销人：", self.witness)
        form.addRow("销毁方式：", self.method)
        v.addLayout(form)
        v.addWidget(QLabel("仅显示本申请获批档案；请核对并手动选择全部后执行："))
        self.list = QListWidget(); self.list.setSelectionMode(QListWidget.MultiSelection)
        v.addWidget(self.list, 1)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        v.addWidget(btns)

    def _load_archives(self):
        try:
            res = self.api.get(f"/api/destroys/{self.destroy_id}").get("data", {})
            self.archives = approved_destroy_archives(res)
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"加载档案失败：{e}")
            self.archives = []
        for a in self.archives:
            self.list.addItem(f"{a['archive_no']} {a['archive_name']}")
            # 破坏性操作不默认选中，操作者须逐项核对获批范围。

    def _submit(self):
        idxs = self.list.selectedIndexes()
        if not idxs:
            QMessageBox.warning(self, "提示", "请选择实际销毁的档案")
            return
        try:
            ids = destroy_execution_ids(self.archives, [index.row() for index in idxs])
        except ValueError as error:
            QMessageBox.warning(self, "提示", str(error))
            return
        if not self.witness.text().strip() or not self.method.text().strip():
            QMessageBox.warning(self, "提示", "请填写监销人和销毁方式")
            return
        payload = {
            "archive_ids": ids,
            "witness": self.witness.text().strip() or None,
            "method": self.method.text().strip() or None,
        }
        try:
            self.api.post(f"/api/destroys/{self.destroy_id}/execute", payload)
            QMessageBox.information(self, "提示", "销毁执行完成，已留痕")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"执行失败：{e}")
            return
        self.accept()
