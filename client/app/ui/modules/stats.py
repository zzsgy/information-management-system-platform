# -*- coding: utf-8 -*-
"""
app/ui/modules/stats.py
========================
统计台账模块：以图表 + 表格展示档案统计，并支持导出 CSV。
数据来源：GET /api/archives/stats（服务端 archive_stats），
返回 total / by_status / by_category / by_security / expiring_count / expiring_soon。
"""
import csv

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTableWidget, QPushButton, QLabel,
    QScrollArea,
)
from PySide6.QtCore import Qt
from PySide6.QtGui import QPainter, QColor, QBrush

from app.ui.base import BaseModule
from app.core.api_client import ApiError


# 图表配色（环/扇区依次取色）
PALETTE = [
    "#5B8FF9", "#61DDAA", "#F6BD16", "#7262FD", "#78D3F8",
    "#9661BC", "#F6903D", "#008685", "#F08BB4", "#65789B",
]


class PieChart(QWidget):
    """饼图：展示 (标签, 数值) 分布，右侧附图例。"""

    def __init__(self, title="", parent=None):
        super().__init__(parent)
        self.title = title
        self.data = []  # [(label, value), ...]
        self.setMinimumHeight(200)

    def set_data(self, data):
        """更新数据并重绘。"""
        self.data = [(str(l), int(v)) for l, v in (data or []) if int(v) > 0]
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = self.rect()
        pad, title_h = 8, 22
        p.setPen(Qt.black)
        p.drawText(rect.left() + pad, rect.top() + 16, self.title)

        if not self.data:
            p.drawText(rect.left() + pad, rect.top() + 40, "暂无数据")
            return

        total = sum(v for _, v in self.data) or 1
        legend_x = rect.right() - 150
        pie_r = min(rect.width() - 170, rect.height() - title_h - pad) // 2
        if pie_r <= 0:
            return
        cx = rect.left() + pad + pie_r + 4
        cy = rect.top() + title_h + pie_r + 4

        # 绘制扇区
        start = 0.0
        for i, (label, val) in enumerate(self.data):
            ang = val / total * 360.0
            p.setBrush(QBrush(QColor(PALETTE[i % len(PALETTE)])))
            p.setPen(Qt.NoPen)
            p.drawPie(cx - pie_r, cy - pie_r, pie_r * 2, pie_r * 2,
                      int(start * 16), int(ang * 16))
            start += ang

        # 绘制图例
        ly = rect.top() + title_h + 4
        for i, (label, val) in enumerate(self.data):
            p.setBrush(QBrush(QColor(PALETTE[i % len(PALETTE)])))
            p.setPen(Qt.NoPen)
            p.drawRect(legend_x, ly, 12, 12)
            p.setPen(Qt.black)
            p.drawText(legend_x + 18, ly + 11, f"{label} ({val})")
            ly += 18


class BarChart(QWidget):
    """横向条形图：展示 (标签, 数值) 分布。"""

    def __init__(self, title="", parent=None):
        super().__init__(parent)
        self.title = title
        self.data = []
        self.setMinimumHeight(200)

    def set_data(self, data):
        self.data = [(str(l), int(v)) for l, v in (data or []) if int(v) > 0]
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = self.rect()
        pad, title_h, row_h = 8, 22, 24
        p.setPen(Qt.black)
        p.drawText(rect.left() + pad, rect.top() + 16, self.title)

        if not self.data:
            p.drawText(rect.left() + pad, rect.top() + 40, "暂无数据")
            return

        maxv = max(v for _, v in self.data) or 1
        label_w, value_w = 100, 50
        bar_x = rect.left() + pad + label_w
        bar_w = max(rect.width() - pad - label_w - value_w, 10)
        top = rect.top() + title_h + 4

        for i, (label, val) in enumerate(self.data):
            y = top + i * row_h
            p.setPen(Qt.black)
            p.drawText(rect.left() + pad, y + 15, str(label)[:10])
            w = int(bar_w * (val / maxv)) if maxv else 0
            p.setBrush(QBrush(QColor(PALETTE[i % len(PALETTE)])))
            p.setPen(Qt.NoPen)
            p.drawRect(bar_x, y + 3, w, row_h - 10)
            p.setPen(Qt.black)
            p.drawText(bar_x + w + 4, y + 15, str(val))


class StatsModule(BaseModule):
    """统计台账：分布图表 + 明细表 + 导出。"""

    title = "统计台账"

    def __init__(self):
        super().__init__()
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)

        # 工具条
        tools = self.build_toolbar(
            ("刷新", self.refresh, None),
            ("导出CSV", self.export_csv, None),
        )
        v.addLayout(tools)

        # 汇总卡片
        cards = QHBoxLayout()
        self.card_total = QLabel("档案总数：—")
        self.card_total.setObjectName("dash_card")
        self.card_expiring = QLabel("30天内即将到期：—")
        self.card_expiring.setObjectName("dash_card")
        cards.addWidget(self.card_total)
        cards.addWidget(self.card_expiring)
        cards.addStretch(1)
        v.addLayout(cards)

        # 用滚动区容纳多张图表（内容较高）
        scroll = QScrollArea()
        container = QWidget()
        cv = QVBoxLayout(container)

        # 一、状态分布（饼图）
        cv.addWidget(QLabel("一、状态分布"))
        self.status_chart = PieChart("状态分布")
        self.status_table = QTableWidget()
        h1 = QHBoxLayout()
        h1.addWidget(self.status_chart, 1)
        h1.addWidget(self.status_table, 1)
        cv.addLayout(h1)

        # 二、分类分布（条形图）
        cv.addWidget(QLabel("二、分类分布（Top）"))
        self.cat_chart = BarChart("分类分布")
        self.cat_table = QTableWidget()
        h2 = QHBoxLayout()
        h2.addWidget(self.cat_chart, 1)
        h2.addWidget(self.cat_table, 1)
        cv.addLayout(h2)

        # 三、密级分布（饼图）
        cv.addWidget(QLabel("三、密级分布"))
        self.sec_chart = PieChart("密级分布")
        self.sec_table = QTableWidget()
        h3 = QHBoxLayout()
        h3.addWidget(self.sec_chart, 1)
        h3.addWidget(self.sec_table, 1)
        cv.addLayout(h3)

        # 四、即将到期清单
        cv.addWidget(QLabel("四、即将到期（30天内）"))
        self.expire_table = QTableWidget()
        cv.addWidget(self.expire_table)

        scroll.setWidget(container)
        v.addWidget(scroll, 1)

    def refresh(self):
        """拉取统计并刷新图表与表格。"""
        try:
            data = self.api.archive_stats()
        except ApiError as e:
            self.handle_error(e, "加载统计")
            return

        # 汇总卡片
        self.card_total.setText(f"档案总数：{data.get('total', 0)}")
        self.card_expiring.setText(f"30天内即将到期：{data.get('expiring_count', 0)}")

        # 状态分布
        by_status = data.get("by_status", [])
        self.status_chart.set_data([(d.get("label"), d.get("count")) for d in by_status])
        self.fill_table(self.status_table, by_status, ["状态", "数量"],
                        mapping=["label", "count"])

        # 分类分布
        by_cat = data.get("by_category", [])
        self.cat_chart.set_data([(d.get("name"), d.get("count")) for d in by_cat])
        self.fill_table(self.cat_table, by_cat, ["分类", "数量"],
                        mapping=["name", "count"])

        # 密级分布
        by_sec = data.get("by_security", [])
        self.sec_chart.set_data([(d.get("name"), d.get("count")) for d in by_sec])
        self.fill_table(self.sec_table, by_sec, ["密级", "数量"],
                        mapping=["name", "count"])

        # 即将到期清单
        expiring = data.get("expiring_soon", [])
        self.fill_table(
            self.expire_table, expiring,
            ["档号", "名称", "分类", "密级", "到期日期", "状态"],
            mapping=["archive_no", "archive_name", "category_name",
                     "security_level", "expiry_date", "status"],
        )

    def export_csv(self):
        """将当前统计导出为 CSV 文件。"""
        try:
            data = self.api.archive_stats()
        except ApiError as e:
            self.handle_error(e, "导出统计")
            return

        path, _ = self._save_path()
        if not path:
            return
        try:
            with open(path, "w", newline="", encoding="utf-8-sig") as f:
                w = csv.writer(f)
                w.writerow(["档案管理系统 - 统计台账导出"])
                w.writerow(["档案总数", data.get("total", 0)])
                w.writerow(["30天内即将到期", data.get("expiring_count", 0)])
                w.writerow([])

                w.writerow(["【状态分布】", "数量"])
                for d in data.get("by_status", []):
                    w.writerow([d.get("label"), d.get("count")])
                w.writerow([])

                w.writerow(["【分类分布】", "数量"])
                for d in data.get("by_category", []):
                    w.writerow([d.get("name"), d.get("count")])
                w.writerow([])

                w.writerow(["【密级分布】", "数量"])
                for d in data.get("by_security", []):
                    w.writerow([d.get("name"), d.get("count")])
                w.writerow([])

                w.writerow(["【即将到期】", "名称", "分类", "密级", "到期日期", "状态"])
                for d in data.get("expiring_soon", []):
                    w.writerow([d.get("archive_no"), d.get("archive_name"),
                                d.get("category_name"), d.get("security_level"),
                                d.get("expiry_date"), d.get("status")])
            self.show_info(f"已导出：{path}")
        except OSError as e:
            self.show_error(f"导出失败：{e}")

    def _save_path(self):
        """弹出保存对话框，返回 (路径, 类型)。"""
        from PySide6.QtWidgets import QFileDialog
        return QFileDialog.getSaveFileName(
            self, "导出统计CSV", "统计台账.csv", "CSV文件 (*.csv)")

    def showEvent(self, event):
        if self.status_table.rowCount() == 0:
            self.refresh()
        super().showEvent(event)
