# -*- coding: utf-8 -*-
"""
app/ui/modules/dict_manage.py
=============================
字典（下拉选项）管理：管理员可对任意下拉词条（文件类型 / 保存类型 / 物理位置 /
密级 / 保管期限）进行新增、编辑、删除。
- file_type / storage_type / storage_location 走通用字典项接口（DictItem）。
- security_level / retention_period 走各自独立表的管理接口。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QListWidget, QListWidgetItem,
    QTableWidget, QPushButton, QDialog, QFormLayout, QDialogButtonBox,
    QLineEdit, QSpinBox, QTextEdit, QMessageBox, QLabel,
)
from PySide6.QtCore import Qt

from app.ui.base import BaseModule
from app.core.api_client import ApiError


# 可管理的下拉类型定义：（显示名, 类型编码, 后端类别）
# backend: "dict" -> DictItem 接口；"sec" -> 密级；"ret" -> 保管期限
TYPE_DEFS = [
    ("文件类型", "file_type", "dict"),
    ("保存类型", "storage_type", "dict"),
    ("物理位置", "storage_location", "dict"),
    ("密级", "security_level", "sec"),
    ("保管期限", "retention_period", "ret"),
]

# 各类型的字段定义：（列名, 字段key, 控件类型, 是否必填）
# 控件类型：line=单行文本, int=整数, text=多行文本
FIELD_DEFS = {
    "dict": [
        ("编码", "item_code", "line", True),
        ("名称", "item_name", "line", True),
        ("排序", "sort_order", "int", False),
    ],
    "sec": [
        ("编码", "code", "line", True),
        ("名称", "name", "line", True),
        ("等级", "level", "int", False),
        ("说明", "description", "text", False),
    ],
    "ret": [
        ("编码", "code", "line", True),
        ("名称", "name", "line", True),
        ("年限(0=永久)", "years", "int", False),
    ],
}

# 列表中显示的列
DISPLAY_COLS = {
    "dict": [("编码", "item_code"), ("名称", "item_name"), ("排序", "sort_order")],
    "sec": [("编码", "code"), ("名称", "name"), ("等级", "level"), ("说明", "description")],
    "ret": [("编码", "code"), ("名称", "name"), ("年限", "years")],
}


class DictManageModule(BaseModule):
    """字典管理模块。"""

    title = "字典管理"

    def __init__(self):
        super().__init__()
        self.cur_type = None
        self.cur_backend = None
        self._build_ui()
        self._load_types()

    def _build_ui(self):
        root = QVBoxLayout(self)
        h = QHBoxLayout()
        self.type_list = QListWidget()
        self.type_list.setFixedWidth(140)
        self.type_list.currentRowChanged.connect(self._on_type_changed)
        h.addWidget(self.type_list, 0)

        right = QVBoxLayout()
        self.table = QTableWidget()
        right.addWidget(self.table, 1)
        bar = QHBoxLayout()
        self.btn_add = QPushButton("新增")
        self.btn_edit = QPushButton("编辑")
        self.btn_del = QPushButton("删除")
        self.btn_refresh = QPushButton("刷新")
        self.btn_add.clicked.connect(self._on_add)
        self.btn_edit.clicked.connect(self._on_edit)
        self.btn_del.clicked.connect(self._on_del)
        self.btn_refresh.clicked.connect(self._on_refresh)
        for b in (self.btn_add, self.btn_edit, self.btn_del, self.btn_refresh):
            bar.addWidget(b)
        right.addLayout(bar)
        h.addLayout(right, 1)
        root.addLayout(h)

    def _load_types(self):
        self.type_list.clear()
        for disp, code, backend in TYPE_DEFS:
            item = QListWidgetItem(disp)
            item.setData(Qt.UserRole, (code, backend))
            self.type_list.addItem(item)
        if self.type_list.count() > 0:
            self.type_list.setCurrentRow(0)

    def _on_type_changed(self, row):
        if row < 0:
            return
        code, backend = self.type_list.item(row).data(Qt.UserRole)
        self.cur_type = code
        self.cur_backend = backend
        self._load_items()

    def _fetch_items(self):
        if self.cur_backend == "dict":
            return self.api.dict_items(self.cur_type).get("data", [])
        if self.cur_backend == "sec":
            return self.api.security_levels().get("data", [])
        return self.api.retention_periods_manage().get("data", [])

    def _load_items(self):
        try:
            rows = self._fetch_items()
        except ApiError as e:
            self.handle_error(e, "加载字典项")
            return
        cols = DISPLAY_COLS[self.cur_backend]
        self.table.setColumnCount(len(cols))
        self.table.setHorizontalHeaderLabels([c[0] for c in cols])
        self.table.setRowCount(len(rows))
        for i, r in enumerate(rows):
            for j, (_, key) in enumerate(cols):
                val = r.get(key)
                self.table.setItem(i, j, QTableWidgetItem("" if val is None else str(val)))
        self._rows = rows

    def _on_refresh(self):
        if self.cur_type:
            self._load_items()

    def _selected_row(self):
        r = self.table.currentRow()
        if r < 0 or not hasattr(self, "_rows"):
            return None
        if r >= len(self._rows):
            return None
        return self._rows[r]

    def _on_add(self):
        dlg = DictItemEditDialog(self, self.cur_type, self.cur_backend)
        if dlg.exec() == QDialog.Accepted:
            self._load_items()

    def _on_edit(self):
        row = self._selected_row()
        if not row:
            QMessageBox.information(self, "提示", "请先选择一项")
            return
        dlg = DictItemEditDialog(self, self.cur_type, self.cur_backend, row)
        if dlg.exec() == QDialog.Accepted:
            self._load_items()

    def _on_del(self):
        row = self._selected_row()
        if not row:
            QMessageBox.information(self, "提示", "请先选择一项")
            return
        if not QMessageBox.question(
            self, "确认", f"确认删除「{row.get('item_name') or row.get('name')}」？",
            QMessageBox.Yes | QMessageBox.No
        ) == QMessageBox.Yes:
            return
        try:
            if self.cur_backend == "dict":
                self.api.delete_dict_item(row["id"])
            elif self.cur_backend == "sec":
                self.api.delete_security_level(row["id"])
            else:
                self.api.delete_retention_period(row["id"])
            QMessageBox.information(self, "提示", "已删除")
            self._load_items()
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"删除失败：{e}")


class DictItemEditDialog(QDialog):
    """新增 / 编辑单条字典项（按类型自适应字段）。"""

    def __init__(self, module: DictManageModule, type_code: str, backend: str, data: dict = None):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.type_code = type_code
        self.backend = backend
        self.data = data
        self.setWindowTitle("编辑字典项" if data else "新增字典项")
        self.resize(400, 280)
        self._edits = {}
        self._build_ui()
        if data:
            self._fill(data)

    def _build_ui(self):
        form = QFormLayout(self)
        for label, key, ctype, required in FIELD_DEFS[self.backend]:
            if ctype == "line":
                w = QLineEdit()
            elif ctype == "int":
                w = QSpinBox(); w.setMaximum(999999)
            else:
                w = QTextEdit(); w.setFixedHeight(60)
            self._edits[key] = w
            form.addRow(label + ("*" if required else "") + "：", w)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        form.addRow(btns)

    def _get(self, key, ctype):
        w = self._edits[key]
        if ctype == "int":
            return w.value()
        return w.toPlainText() if isinstance(w, QTextEdit) else w.text()

    def _fill(self, d: dict):
        for label, key, ctype, required in FIELD_DEFS[self.backend]:
            w = self._edits[key]
            val = d.get(key)
            if ctype == "int":
                w.setValue(int(val or 0))
            elif isinstance(w, QTextEdit):
                w.setPlainText(val or "")
            else:
                w.setText(str(val) if val is not None else "")

    def _submit(self):
        payload = {}
        for label, key, ctype, required in FIELD_DEFS[self.backend]:
            v = self._get(key, ctype)
            if required and not str(v).strip():
                QMessageBox.warning(self, "提示", f"请填写{label}")
                return
            payload[key] = v
        # dict 类型必须带 type_code
        if self.backend == "dict":
            payload["type_code"] = self.type_code
        try:
            if self.data:
                if self.backend == "dict":
                    self.api.update_dict_item(self.data["id"], payload)
                elif self.backend == "sec":
                    self.api.update_security_level(self.data["id"], payload)
                else:
                    self.api.update_retention_period(self.data["id"], payload)
                QMessageBox.information(self, "提示", "已更新")
            else:
                if self.backend == "dict":
                    self.api.create_dict_item(payload)
                elif self.backend == "sec":
                    self.api.create_security_level(payload)
                else:
                    self.api.create_retention_period(payload)
                QMessageBox.information(self, "提示", "已新增")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")
            return
        self.accept()
