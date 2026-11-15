# -*- coding: utf-8 -*-
"""
app/ui/modules/borrow_manage.py
===============================
借阅管理模块：发起借阅申请、审批、归还。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTabWidget, QTableWidget, QPushButton,
    QLabel, QLineEdit, QTextEdit, QDateEdit, QDialog, QFormLayout,
    QDialogButtonBox, QListWidget, QMessageBox, QComboBox,
)
from PySide6.QtCore import QDate

from app.ui.base import BaseModule
from app.core.api_client import ApiError
from app.core.i18n import status_name
from app.core import session
from app.core.workflow_rules import can_checkout_borrow, can_return_borrow


class BorrowModule(BaseModule):
    title = "借阅管理"

    def __init__(self):
        super().__init__()
        self._build_ui()

    def _build_ui(self):
        root = QVBoxLayout(self)
        tabs = QTabWidget()
        self.apply_tab = self._build_apply_tab()
        self.approve_tab = self._build_approve_tab()
        tabs.addTab(self.apply_tab, "借阅申请")
        tabs.addTab(self.approve_tab, "借阅审批")
        tabs.setTabEnabled(1, session.has_perm("borrow.approve"))
        root.addWidget(tabs, 1)

    # ---- 申请页 ----
    def _build_apply_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        actions = []  # 申请与实物办理权限分离，按钮仅作为操作指引。
        if session.has_perm("borrow.apply"):
            actions.append(("新建申请", self._on_apply, None))
        if session.has_perm("borrow.approve"):
            actions.extend((("办理借出", self._on_checkout, None), ("归还", self._on_return, "ghost")))
        actions.append(("刷新", self._refresh_apply, None))
        tools = self.build_toolbar(*actions)
        v.addLayout(tools)
        self.a_table = QTableWidget(); v.addWidget(self.a_table, 1)
        return w

    def _refresh_apply(self):
        try:
            res = self.api.get("/api/borrows",
                               params={"page": 1, "page_size": 100}).get("data", {})
            rows = res.get("items", [])
        except ApiError as e:
            self.handle_error(e, "查询借阅"); return
        self.fill_table(self.a_table, rows,
                        ["单号", "状态", "借阅方式", "申请人", "档案", "申请时间", "期望归还"],
                        mapping=["borrow_no", lambda r: status_name(r.get("status")),
                                 lambda r: {"physical": "实物", "electronic": "电子", "both": "纸电结合"}.get(r.get("borrow_mode"), "—"),
                                 "applicant_name",
                                 lambda r: "、".join(r.get("archive_names", [])),
                                 "apply_time", "expected_return_date"])

    def _on_apply(self):
        dlg = BorrowApplyDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self._refresh_apply()

    def _on_return(self):
        """归还实物须已办理借出，纯电子借阅可直接结束批准的记录。"""
        row = self.selected_row(self.a_table)
        if not row:
            self.show_info("请先选择一条借阅记录"); return
        if not can_return_borrow(row):
            self.show_info("实物借阅须先办理借出；电子借阅须已批准后才能归还"); return
        if not self.ask_yesno("确认归还该借阅？"):
            return
        try:
            self.api.post(f"/api/borrows/{row['id']}/return",
                          {"return_condition": "正常归还"})
            self.show_info("归还完成"); self._refresh_apply()
        except Exception as e:  # noqa
            self.handle_error(e, "归还")

    def _on_checkout(self):
        """把已批准的实物借阅实际办理为借出，状态由服务端事务同步。"""
        row = self.selected_row(self.a_table)  # 当前待办理借阅单，不能直接修改档案状态。
        if not row or not can_checkout_borrow(row):
            self.show_info("请选择已批准的实物或纸电结合借阅单")
            return
        if not self.ask_yesno("确认已将本单全部实物档案交给借阅人，办理借出？"):
            return
        try:
            self.api.post(f"/api/borrows/{row['id']}/checkout", {})
            self.show_info("借出办理完成")
            self._refresh_apply()
        except Exception as error:
            self.handle_error(error, "借出")

    # ---- 审批页 ----
    def _build_approve_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        tools = self.build_toolbar(
            ("通过", self._on_approve_pass, None),
            ("驳回", self._on_approve_reject, "danger"),
            ("查看进度", self._on_progress, "ghost"),
            ("刷新", self._refresh_approve, None),
        )
        v.addLayout(tools)
        self.p_table = QTableWidget(); v.addWidget(self.p_table, 1)
        return w

    def _on_progress(self):
        row = self.selected_row(self.p_table)
        if not row:
            self.show_info("请先选择一条记录")
            return
        from app.ui.flow_dialog import FlowDetailDialog
        dlg = FlowDetailDialog(self.api, biz_type="borrow", biz_id=row["id"])
        dlg.exec()
        self._refresh_approve()

    def _refresh_approve(self):
        try:
            res = self.api.get("/api/borrows", params={"status": "pending",
                               "page": 1, "page_size": 100}).get("data", {})
            rows = res.get("items", [])
        except ApiError as e:
            self.handle_error(e, "查询待审批"); return
        self.fill_table(self.p_table, rows,
                        ["单号", "申请人", "档案", "申请时间", "期望归还"],
                        mapping=["borrow_no", "applicant_name",
                                 lambda r: "、".join(r.get("archive_names", [])),
                                 "apply_time", "expected_return_date"])

    def _on_approve_pass(self):
        self._approve(True)

    def _on_approve_reject(self):
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
            self.api.post(f"/api/borrows/{row['id']}/approve",
                          {"approve": approve, "opinion": opinion or None})
            self.show_info("审批完成"); self._refresh_approve()
        except Exception as e:  # noqa
            self.handle_error(e, "审批")

    def refresh(self):
        self._refresh_apply()
        self._refresh_approve()

    def showEvent(self, event):
        if self.a_table.rowCount() == 0:
            self._refresh_apply()
        super().showEvent(event)


class BorrowApplyDialog(QDialog):
    def __init__(self, module: BorrowModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.setWindowTitle("新建借阅申请")
        self.resize(420, 460)
        self._build_ui()
        self._load_archives()

    def _build_ui(self):
        v = QVBoxLayout(self)
        v.addWidget(QLabel("选择档案（可多选）："))
        self.list = QListWidget(); self.list.setSelectionMode(QListWidget.MultiSelection)
        v.addWidget(self.list, 1)
        form = QFormLayout()
        self.purpose = QLineEdit()
        self.borrow_mode = QComboBox()  # 区分实物流转与电子使用，避免一律写死 electronic。
        self.borrow_mode.addItem("电子档案", "electronic")
        self.borrow_mode.addItem("纸质/实物档案", "physical")
        self.borrow_mode.addItem("纸电结合", "both")
        self.ret_date = QDateEdit(); self.ret_date.setCalendarPopup(True)
        self.ret_date.setDate(QDate.currentDate().addDays(7))
        form.addRow("借阅事由：", self.purpose)
        form.addRow("借阅方式：", self.borrow_mode)
        form.addRow("期望归还：", self.ret_date)
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
        indexes = self.list.selectedIndexes()
        if not indexes:
            QMessageBox.warning(self, "提示", "请至少选择一份档案")
            return
        ids = [self.archives[i.row()]["id"] for i in indexes]
        payload = {
            "archive_ids": ids,
            "purpose": self.purpose.text().strip() or None,
            "expected_return_date": self.ret_date.date().toString("yyyy-MM-dd"),
            "borrow_mode": self.borrow_mode.currentData(),
        }
        try:
            self.api.post("/api/borrows", payload)
            QMessageBox.information(self, "提示", "借阅申请已提交")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"提交失败：{e}")
            return
        self.accept()
