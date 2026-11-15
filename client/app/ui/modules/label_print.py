# -*- coding: utf-8 -*-
"""
app/ui/modules/label_print.py
=============================
标签打印：勾选档案后，生成可打印标签（编号/名称/分类/密级/位置/到期），
通过系统打印预览输出（可打印或另存为 PDF）。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton,
    QTableWidget, QTableWidgetItem, QHeaderView, QAbstractItemView,
    QMessageBox,
)
from PySide6.QtPrintSupport import QPrinter, QPrintPreviewDialog
from PySide6.QtCore import Qt
from PySide6.QtGui import QTextDocument

from app.core.api_client import ApiClient, ApiError


STATUS_CN = {
    "draft": "草稿", "pending": "待审批", "archive": "已归档",
    "borrowed": "借阅中", "locked": "封存", "damaged": "已损坏",
    "destroy_pending": "销毁待审", "destroyed": "已销毁",
}


class LabelPrintModule(QWidget):
    """标签打印模块。"""

    title = "标签打印"

    def __init__(self):
        super().__init__()
        self.api = ApiClient()
        self._rows = []
        self._checked = set()
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)
        v.setContentsMargins(16, 16, 16, 16)
        v.setSpacing(10)

        # 工具条
        bar = QHBoxLayout()
        bar.setContentsMargins(0, 0, 0, 0)
        self.search = QLineEdit()
        self.search.setPlaceholderText("输入档案编号 / 名称 / 关键词查询")
        self.search.returnPressed.connect(self._load)
        btn_query = QPushButton("查询")
        btn_query.clicked.connect(self._load)
        btn_all = QPushButton("全选")
        btn_all.clicked.connect(self._select_all)
        btn_clear = QPushButton("清空")
        btn_clear.clicked.connect(self._clear_sel)
        btn_preview = QPushButton("打印预览")
        btn_preview.setObjectName("primary")
        btn_preview.clicked.connect(self._preview)
        bar.addWidget(QLabel("档案："))
        bar.addWidget(self.search, 1)
        bar.addWidget(btn_query)
        bar.addWidget(btn_all)
        bar.addWidget(btn_clear)
        bar.addWidget(btn_preview)
        v.addLayout(bar)

        # 表格
        self.table = QTableWidget()
        self.table.setColumnCount(5)
        self.table.setHorizontalHeaderLabels(["选择", "档案编号", "档案名称", "分类", "状态"])
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setColumnHidden(0, False)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.table.itemChanged.connect(self._on_check)
        v.addWidget(self.table, 1)

        self._load()

    def _load(self):
        try:
            params = {"page": 1, "page_size": 300}
            kw = self.search.text().strip()
            if kw:
                params["keyword"] = kw
            res = self.api.get("/api/archives", params=params)
            if res.get("code") != 0:
                raise ApiError(res.get("message") or "查询失败")
            self._rows = res.get("data", {}).get("items", [])
            self._fill()
        except Exception as e:  # noqa
            self._show_error(e)

    def _fill(self):
        rows = self._rows
        self.table.setRowCount(len(rows))
        for r, a in enumerate(rows):
            chk = QTableWidgetItem()
            chk.setFlags(Qt.ItemIsEnabled | Qt.ItemIsUserCheckable)
            chk.setCheckState(Qt.Checked if a["id"] in self._checked else Qt.Unchecked)
            chk.setData(Qt.UserRole, a["id"])
            self.table.setItem(r, 0, chk)
            self.table.setItem(r, 1, QTableWidgetItem(a.get("archive_no", "")))
            self.table.setItem(r, 2, QTableWidgetItem(a.get("archive_name", "")))
            self.table.setItem(r, 3, QTableWidgetItem(a.get("category_name") or "—"))
            self.table.setItem(r, 4, QTableWidgetItem(STATUS_CN.get(a.get("status"), a.get("status", ""))))

    def _on_check(self, item):
        if item.column() != 0:
            return
        aid = item.data(Qt.UserRole)
        if aid is None:
            return
        if item.checkState() == Qt.Checked:
            self._checked.add(aid)
        else:
            self._checked.discard(aid)

    def _select_all(self):
        self._checked = {a["id"] for a in self._rows}
        self._fill()

    def _clear_sel(self):
        self._checked.clear()
        self._fill()

    def _selected(self):
        sel = [a for a in self._rows if a["id"] in self._checked]
        return sel

    def _preview(self):
        sel = self._selected()
        if not sel:
            QMessageBox.information(self, "提示", "请先勾选要打印的档案。")
            return
        html = self._build_html(sel)
        doc = QTextDocument()
        doc.setHtml(html)
        printer = QPrinter(QPrinter.HighResolution)
        dlg = QPrintPreviewDialog(printer, self)
        dlg.paintRequested.connect(lambda p: doc.print_(p))
        dlg.exec()

    def _build_html(self, rows):
        cards = []
        for a in rows:
            loc = " / ".join(
                x for x in [a.get("cabinet_no"), a.get("column_no"), a.get("layer_no")] if x
            )
            cards.append(
                "<div style='border:1px solid #333;padding:8px;width:250px;height:120px;"
                "font-family:Microsoft YaHei,sans-serif;font-size:11px;'>"
                f"<div style='font-weight:bold;font-size:13px;'>{a.get('archive_no','')}</div>"
                f"<div style='font-weight:bold;margin:4px 0;'>{a.get('archive_name','')}</div>"
                f"<div>分类：{a.get('category_name') or '—'}&nbsp;&nbsp;密级：{a.get('security_name') or '—'}</div>"
                f"<div>位置：{loc or '—'}&nbsp;&nbsp;到期：{a.get('expiry_date') or '—'}</div>"
                "</div>"
            )
        per_row = 3
        html = "<html><body><table>"
        for i in range(0, len(cards), per_row):
            html += "<tr>"
            for j in range(per_row):
                if i + j < len(cards):
                    html += f"<td style='padding:6px;'>{cards[i + j]}</td>"
                else:
                    html += "<td></td>"
            html += "</tr>"
        html += "</table></body></html>"
        return html

    def _show_error(self, e):
        msg = str(e)
        if isinstance(e, ApiError) and e.status == 401:
            msg = "登录已失效，请重新登录。"
        QMessageBox.critical(self, "错误", f"标签打印失败：{msg}")
