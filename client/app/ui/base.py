# -*- coding: utf-8 -*-
"""
app/ui/base.py
===============
模块基类：为各业务模块提供通用能力
（API 客户端、消息提示、确认框、表格填充等），减少重复代码。
"""
from PySide6.QtWidgets import (
    QWidget, QMessageBox, QPushButton, QHBoxLayout, QTableWidget,
    QTableWidgetItem, QHeaderView,
)
from PySide6.QtCore import Qt

from app.core.api_client import ApiClient, ApiError


class BaseModule(QWidget):
    """所有业务模块的父类。"""

    #: 模块标题（子类覆盖）
    title = "模块"

    def __init__(self):
        super().__init__()
        self.api = ApiClient()

    # ---------------- 通用交互 ----------------
    def show_info(self, text: str):
        QMessageBox.information(self, "提示", text)

    def show_error(self, text: str):
        QMessageBox.critical(self, "错误", text)

    def ask_yesno(self, text: str) -> bool:
        return QMessageBox.question(
            self, "请确认", text,
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No
        ) == QMessageBox.Yes

    def handle_error(self, e: Exception, ctx: str = ""):
        """统一异常提示；401 提示重新登录。"""
        msg = str(e)
        if isinstance(e, ApiError) and e.status == 401:
            msg = "登录已失效，请重新登录。"
        self.show_error(f"{ctx}失败：{msg}" if ctx else msg)

    # ---------------- 工具条 ----------------
    def build_toolbar(self, *buttons) -> QHBoxLayout:
        """构建一行工具按钮，返回布局（由子类加入主布局）。"""
        bar = QHBoxLayout()
        bar.setContentsMargins(0, 0, 0, 6)
        for text, slot, kind in buttons:
            btn = QPushButton(text)
            if kind == "danger":
                btn.setObjectName("danger")
            elif kind == "ghost":
                btn.setObjectName("ghost")
            if slot:
                btn.clicked.connect(slot)
            bar.addWidget(btn)
        bar.addStretch(1)
        return bar

    # ---------------- 表格 ----------------
    def setup_table(self, table: QTableWidget, headers: list):
        """设置表头并禁止编辑。"""
        table.setColumnCount(len(headers))
        table.setHorizontalHeaderLabels(headers)
        table.setEditTriggers(QTableWidget.NoEditTriggers)
        table.setSelectionBehavior(QTableWidget.SelectRows)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        table.verticalHeader().setVisible(False)

    def fill_table(self, table: QTableWidget, rows: list, columns: list,
                   mapping=None):
        """
        填充表格。
        :param columns: 列标题列表
        :param mapping: 与列对应的取值函数或 key 列表；
                        若为 key 列表，则从每行 dict 取 row.get(key)
        """
        self.setup_table(table, columns)
        table.setRowCount(len(rows))
        for r, row in enumerate(rows):
            for c, col in enumerate(columns):
                if mapping is None:
                    val = row.get(col, "")
                else:
                    key = mapping[c]
                    val = row.get(key, "") if isinstance(key, str) else key(row)
                item = QTableWidgetItem("" if val is None else str(val))
                item.setData(Qt.UserRole, row)  # 绑定整行原始数据
                table.setItem(r, c, item)

    def selected_row(self, table: QTableWidget) -> dict | None:
        """返回当前选中行的原始数据 dict。"""
        row = table.currentRow()
        if row < 0:
            return None
        item = table.item(row, 0)
        return item.data(Qt.UserRole) if item else None
