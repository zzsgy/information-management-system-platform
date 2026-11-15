# -*- coding: utf-8 -*-
"""
app/ui/modules/dashboard.py
===========================
首页仪表盘：登录后默认进入，展示档案概览统计。
数据来自 GET /api/archives/stats。
增强（v1.12）：
  - 顶部展示企业 LOGO（系统参数 enterprise_logo，本地图片路径，缺省占位文字）；
  - 档案状态分布支持饼图可视化；
  - 概览卡片可由管理员通过「首页设置」配置显示哪些（系统参数 dashboard_cards）。
"""
import json
import os

from PySide6.QtCore import Qt, QRect
from PySide6.QtGui import QColor, QPainter, QPen, QBrush, QPixmap, QFont
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTableWidget,
    QTableWidgetItem, QGroupBox, QScrollArea, QPushButton, QDialog,
    QFormLayout, QLineEdit, QListWidget, QListWidgetItem, QFileDialog,
    QDialogButtonBox, QMessageBox,
)

from app.core.api_client import ApiClient, ApiError
from app.core import session


# 档案状态 -> 中文标签（与后端 STATUS_LABELS 对齐）
STATUS_CN = {
    "draft": "草稿", "pending": "待审批", "archive": "已归档",
    "borrowed": "借阅中", "locked": "封存", "damaged": "已损坏",
    "destroy_pending": "销毁待审", "destroyed": "已销毁",
}
# 状态 -> 饼图配色
STATUS_COLOR = {
    "draft": "#95a5a6", "pending": "#f39c12", "archive": "#27ae60",
    "borrowed": "#2980b9", "locked": "#34495e", "damaged": "#c0392b",
    "destroy_pending": "#8e44ad", "destroyed": "#7f8c8d",
}
# 概览卡片可选项（key -> (标签, 主题色)）
CARD_OPTIONS = {
    "total": ("档案总数", "#2f7fd1"),
    "expiring": ("即将到期(30天)", "#e67e22"),
    "archived": ("已归档", "#27ae60"),
    "borrowed": ("借阅中", "#8e44ad"),
    "damaged": ("已损坏", "#c0392b"),
    "destroyed": ("已销毁", "#7f8c8d"),
}
# 默认展示的卡片
DEFAULT_CARDS = ["total", "expiring", "archived", "borrowed"]
# 系统参数键
PARAM_LOGO = "enterprise_logo"
PARAM_CARDS = "dashboard_cards"


class StatusPieWidget(QWidget):
    """档案状态分布饼图（QPainter 手绘 + 图例）。"""
    def __init__(self, parent=None):
        super().__init__(parent)
        self.slices = []  # [(label, value, color)]
        self.setMinimumSize(380, 200)

    def set_data(self, slices):
        self.slices = slices
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        pie_size = min(h - 16, 180)
        pie_rect = QRect(12, (h - pie_size) // 2, pie_size, pie_size)

        total = sum(s[1] for s in self.slices)
        if total <= 0:
            painter.setPen(QColor("#6b7785"))
            painter.drawText(pie_rect, Qt.AlignCenter, "暂无数据")
            return

        # 画饼
        start = 0
        for _label, value, color in self.slices:
            if value <= 0:
                continue
            span = int(value / total * 360 * 16)
            painter.setBrush(QBrush(QColor(color)))
            painter.setPen(QPen(Qt.NoPen))
            painter.drawPie(pie_rect, start, span)
            start += span

        # 画图例
        legend_x = pie_rect.right() + 18
        y = (h - len(self.slices) * 22) // 2
        font = QFont(); font.setPointSize(10)
        painter.setFont(font)
        painter.setPen(QColor("#2f3b4c"))
        for label, value, color in self.slices:
            painter.setBrush(QBrush(QColor(color)))
            painter.setPen(QPen(Qt.NoPen))
            painter.drawRect(legend_x, y + 2, 14, 14)
            painter.setPen(QColor("#2f3b4c"))
            painter.drawText(legend_x + 22, y + 14, f"{label}：{value}")
            y += 22


class DashboardModule(QWidget):
    """首页仪表盘模块（对所有登录用户可见）。"""
    title = "首页"

    def __init__(self):
        super().__init__()
        self.api = ApiClient()
        self.card_values = {}
        self.card_order = list(DEFAULT_CARDS)  # 当前展示的卡片顺序
        self.logo_path = ""
        self._build_ui()
        # 仅对拥有 dict.manage 权限的管理员显示「首页设置」
        if not session.has_perm("dict.manage"):
            self.btn_config.setVisible(False)

    # ---------------- UI ----------------
    def _build_ui(self):
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        inner = QWidget()
        scroll.setWidget(inner)
        root.addWidget(scroll)

        v = QVBoxLayout(inner)
        v.setContentsMargins(16, 16, 16, 16)
        v.setSpacing(14)

        # 顶部：LOGO + 标题 + 设置按钮
        top = QHBoxLayout()
        self.logo_label = QLabel()
        self.logo_label.setFixedSize(48, 48)
        self.logo_label.setStyleSheet("border:1px solid #e1e6ec;border-radius:6px;")
        self.logo_label.setScaledContents(True)
        top.addWidget(self.logo_label)
        title = QLabel("企业档案管理系统")
        title.setStyleSheet("font-size:18px;font-weight:bold;color:#2f3b4c;")
        top.addWidget(title)
        top.addStretch(1)
        self.btn_config = QPushButton("首页设置")
        self.btn_config.setObjectName("ghost")
        self.btn_config.clicked.connect(self._open_config)
        top.addWidget(self.btn_config)
        v.addLayout(top)

        # 概览卡片（顺序与显示项由 dashboard_cards 参数决定）
        self.card_row = QHBoxLayout()
        self.card_row.setSpacing(12)
        v.addLayout(self.card_row)

        # 状态分布：饼图 + 表格
        status_box = QGroupBox("档案状态分布")
        status_box.setStyleSheet("QGroupBox{font-weight:bold;color:#2f3b4c;}")
        sb_layout = QHBoxLayout(status_box)
        self.pie = StatusPieWidget()
        self.status_table = QTableWidget()
        sb_layout.addWidget(self.pie, 1)
        sb_layout.addWidget(self.status_table, 1)
        v.addWidget(status_box)

        # 分类 + 密级 并排
        mid = QHBoxLayout()
        mid.setSpacing(12)
        self.cat_table = QTableWidget()
        self.sec_table = QTableWidget()
        mid.addWidget(self._group_box("分类分布", self.cat_table))
        mid.addWidget(self._group_box("密级分布", self.sec_table))
        v.addLayout(mid)

        # 即将到期
        self.expire_table = QTableWidget()
        v.addWidget(self._group_box("即将到期档案", self.expire_table))

        v.addStretch(1)

    def _make_card(self, label, color):
        w = QWidget()
        w.setObjectName("dash_card")
        w.setStyleSheet(
            "#dash_card{background:#ffffff;border:1px solid #e1e6ec;"
            f"border-left:4px solid {color};border-radius:8px;}}"
            "#dash_card:hover{{border-color:{color};}}"
        )
        lv = QVBoxLayout(w)
        lv.setContentsMargins(16, 14, 16, 14)
        val = QLabel("—")
        val.setStyleSheet(f"font-size:26px;font-weight:bold;color:{color};")
        lab = QLabel(label)
        lab.setStyleSheet("font-size:12px;color:#6b7785;")
        lv.addWidget(val)
        lv.addWidget(lab)
        return w, val

    def _group_box(self, title, widget):
        gb = QGroupBox(title)
        gb.setStyleSheet("QGroupBox{font-weight:bold;color:#2f3b4c;}")
        lv = QVBoxLayout(gb)
        lv.setContentsMargins(10, 10, 10, 10)
        lv.addWidget(widget)
        return gb

    def _rebuild_cards(self):
        """根据当前 card_order 重建概览卡片。"""
        # 清空旧卡片
        while self.card_row.count():
            item = self.card_row.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self.card_values.clear()
        for key in self.card_order:
            if key not in CARD_OPTIONS:
                continue
            label, color = CARD_OPTIONS[key]
            card, val = self._make_card(label, color)
            self.card_values[key] = val
            self.card_row.addWidget(card)

    # ---------------- 数据填充 ----------------
    def refresh(self):
        try:
            # 先加载首页配置（LOGO / 卡片项）
            self._load_config()
            res = self.api.get("/api/archives/stats")
            if res.get("code") != 0:
                raise Exception(res.get("message") or "获取统计失败")
            d = res.get("data", {})

            # 卡片数值
            bs = {x["status"]: x["count"] for x in d.get("by_status", [])}
            mapping = {
                "total": d.get("total", 0),
                "expiring": d.get("expiring_count", 0),
                "archived": bs.get("archive", 0),
                "borrowed": bs.get("borrowed", 0),
                "damaged": bs.get("damaged", 0),
                "destroyed": bs.get("destroyed", 0),
            }
            for key, val in mapping.items():
                if key in self.card_values:
                    self.card_values[key].setText(str(val))

            # 饼图 + 状态表
            slices = []
            for x in d.get("by_status", []):
                st = x.get("status")
                slices.append((x.get("label") or STATUS_CN.get(st, st),
                               x.get("count", 0),
                               STATUS_COLOR.get(st, "#95a5a6")))
            self.pie.set_data(slices)
            # 饼图使用元组，表格使用命名字段；不能将元组直接按字典下标读取。
            status_rows = [{"label": label, "count": count} for label, count, _color in slices]
            self._fill_simple(self.status_table, status_rows, "label", "count")

            self._fill_simple(self.cat_table, d.get("by_category", []), "name", "count")
            self._fill_simple(self.sec_table, d.get("by_security", []), "name", "count")
            self._fill_expiring(d.get("expiring_soon", []))
        except Exception as e:  # noqa
            self._show_error(e)

    def _load_config(self):
        """读取企业 LOGO 与卡片配置（系统参数）。"""
        # LOGO
        try:
            r = self.api.get_param(PARAM_LOGO)
            self.logo_path = (r.get("data") or {}).get("value") or ""
        except Exception:  # noqa
            self.logo_path = ""
        if self.logo_path and os.path.isfile(self.logo_path):
            pix = QPixmap(self.logo_path)
            if not pix.isNull():
                self.logo_label.setPixmap(pix)
            else:
                self._clear_logo()
        else:
            self._clear_logo()

        # 卡片项
        try:
            r = self.api.get_param(PARAM_CARDS)
            raw = (r.get("data") or {}).get("value") or ""
            if raw:
                cards = json.loads(raw)
                if isinstance(cards, list) and cards:
                    self.card_order = [c for c in cards if c in CARD_OPTIONS]
        except Exception:  # noqa
            pass
        if not self.card_order:
            self.card_order = list(DEFAULT_CARDS)
        self._rebuild_cards()

    def _clear_logo(self):
        self.logo_label.setText("LOGO")
        self.logo_label.setStyleSheet(
            "border:1px solid #e1e6ec;border-radius:6px;color:#9aa5b1;font-size:11px;")

    def _open_config(self):
        dlg = DashboardConfigDialog(self, self.logo_path, self.card_order)
        if dlg.exec() == QDialog.Accepted:
            self.refresh()

    def _show_error(self, e):
        msg = str(e)
        if isinstance(e, ApiError) and e.status == 401:
            msg = "登录已失效，请重新登录。"
        QMessageBox.critical(self, "错误", f"加载仪表盘失败：{msg}")

    def _fill_simple(self, table, rows, name_key, count_key):
        headers = ["名称", "数量"]
        table.setColumnCount(len(headers))
        table.setHorizontalHeaderLabels(headers)
        table.setEditTriggers(QTableWidget.NoEditTriggers)
        table.setRowCount(len(rows))
        for r, row in enumerate(rows):
            table.setItem(r, 0, QTableWidgetItem("" if row[name_key] is None else str(row[name_key])))
            table.setItem(r, 1, QTableWidgetItem(str(row[count_key])))
        table.horizontalHeader().setStretchLastSection(True)

    def _fill_expiring(self, rows):
        headers = ["档案编号", "档案名称", "分类", "到期日期", "状态"]
        table = self.expire_table
        table.setColumnCount(len(headers))
        table.setHorizontalHeaderLabels(headers)
        table.setEditTriggers(QTableWidget.NoEditTriggers)
        table.setRowCount(len(rows))
        for r, a in enumerate(rows):
            vals = [
                a.get("archive_no", ""),
                a.get("archive_name", ""),
                a.get("category_name") or "—",
                a.get("expiry_date") or "—",
                STATUS_CN.get(a.get("status"), a.get("status", "")),
            ]
            for c, val in enumerate(vals):
                table.setItem(r, c, QTableWidgetItem("" if val is None else str(val)))
        table.horizontalHeader().setStretchLastSection(True)


class DashboardConfigDialog(QDialog):
    """首页设置：配置企业 LOGO 路径与概览卡片显示项（需 dict.manage 权限）。"""
    def __init__(self, parent, logo_path: str, card_order: list):
        super().__init__(parent)
        self.api = ApiClient()
        self.setWindowTitle("首页设置")
        self.resize(420, 360)
        self._build_ui(logo_path, card_order)

    def _build_ui(self, logo_path, card_order):
        v = QVBoxLayout(self)
        form = QFormLayout()
        self.logo_edit = QLineEdit(logo_path)
        self.logo_edit.setPlaceholderText("企业 LOGO 本地图片路径（留空显示默认文字）")
        browse = QPushButton("浏览…")
        browse.clicked.connect(self._browse)
        logo_h = QHBoxLayout()
        logo_h.addWidget(self.logo_edit, 1)
        logo_h.addWidget(browse)
        form.addRow("企业LOGO：", logo_h)
        v.addLayout(form)

        v.addWidget(QLabel("概览卡片（勾选要显示的项）："))
        self.list = QListWidget()
        for key, (label, _color) in CARD_OPTIONS.items():
            item = QListWidgetItem(label)
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
            item.setCheckState(Qt.Checked if key in card_order else Qt.Unchecked)
            item.setData(Qt.UserRole, key)
            self.list.addItem(item)
        v.addWidget(self.list, 1)

        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        v.addWidget(btns)

    def _browse(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "选择 LOGO 图片", "", "图片 (*.png *.jpg *.jpeg *.bmp)")
        if path:
            self.logo_edit.setText(path)

    def _submit(self):
        logo = self.logo_edit.text().strip()
        cards = []
        for i in range(self.list.count()):
            item = self.list.item(i)
            if item.checkState() == Qt.Checked:
                cards.append(item.data(Qt.UserRole))
        if not cards:
            QMessageBox.warning(self, "提示", "至少选择一项概览卡片")
            return
        try:
            self.api.set_param(PARAM_LOGO, logo, description="企业档案管理系统首页LOGO图片本地路径")
            self.api.set_param(PARAM_CARDS, json.dumps(cards), description="首页概览卡片显示项")
            QMessageBox.information(self, "提示", "设置已保存")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")
            return
        self.accept()
