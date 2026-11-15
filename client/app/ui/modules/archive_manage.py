# -*- coding: utf-8 -*-
"""
app/ui/modules/archive_manage.py
================================
档案管理模块：档案分类管理 + 档案目录（新建/详情/归档）+ 电子文件（上传/下载）。
"""
import csv

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTabWidget, QTableWidget, QTableWidgetItem, QPushButton,
    QLabel, QLineEdit, QComboBox, QTextEdit, QDateEdit, QSpinBox, QDialog,
    QFormLayout, QDialogButtonBox, QFileDialog, QMessageBox, QTreeWidget,
    QListWidget, QListWidgetItem, QRadioButton, QButtonGroup, QGroupBox,
)
from PySide6.QtCore import Qt, QDate

from app.ui.base import BaseModule
from app.core import session, prefs
from app.core.api_client import ApiError
from app.core.i18n import status_name
from app.core.tasks import run_task


# 档案清单「全部可选列」定义（key, 标题）；顺序即默认显示顺序
ARCHIVE_COLUMN_DEFS = [
    ("archive_no", "档号"),
    ("archive_name", "名称"),
    ("file_type", "文件类型"),
    ("dept_name", "所属部门"),
    ("owner_name", "负责人"),
    ("location", "存放位置"),
    ("storage_type", "保存类型"),
    ("copy_count", "份数"),
    ("security_name", "密级"),
    ("archive_date", "存档日期"),
    ("validity_months", "有效期(月)"),
    ("expiry_date", "到期日期"),
    ("status_name", "状态"),
    ("latest", "更新记录(最新)"),
]


class ArchiveModule(BaseModule):
    """档案管理：包含两个标签页。"""

    title = "档案管理"

    def __init__(self):
        super().__init__()
        self.categories = []
        self.departments = []
        self.security_levels = []
        self.retention_periods = []
        self.locations = []
        # 档案清单列配置（可见列 + 顺序），来自本地偏好，None 表示使用默认全列
        self.col_config = prefs.get_pref("archive_columns")
        self._build_ui()


    def _build_ui(self):
        root = QVBoxLayout(self)
        tabs = QTabWidget()
        self.archive_tab = self._build_archive_tab()
        self.category_tab = self._build_category_tab()
        tabs.addTab(self.archive_tab, "档案目录")
        tabs.addTab(self.category_tab, "分类管理")
        root.addWidget(tabs, 1)

    # ============ 档案目录 ============
    def _build_archive_tab(self) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        # 查询条
        bar = QHBoxLayout()
        self.a_kw = QLineEdit(); self.a_kw.setPlaceholderText("关键字（档号/名称/关键词）")
        self.a_cat = QComboBox(); self.a_cat.addItem("全部分类", None)
        self.a_status = QComboBox()
        self.a_status.addItem("全部状态", None)
        for code, name in [
            ("draft", "草稿"), ("pending", "待归档"), ("archive", "已归档"),
            ("borrowed", "借阅中"), ("locked", "封存"), ("damaged", "破损"),
            ("destroy_pending", "待销毁"), ("destroyed", "已销毁"),
        ]:
            self.a_status.addItem(name, code)
        self.a_status.setCurrentText("全部状态")
        btn_search = QPushButton("查询")
        btn_search.clicked.connect(self.refresh)
        bar.addWidget(QLabel("查询：")); bar.addWidget(self.a_kw, 2)
        bar.addWidget(self.a_cat, 1); bar.addWidget(self.a_status, 1)
        bar.addWidget(btn_search)
        v.addLayout(bar)

        # 工具条
        toolbar_buttons = [
            ("查看详情", self._on_detail, "ghost"),
            ("刷新", self.refresh, None),
            ("列设置", self._on_col_config, "ghost"),
        ]
        if session.has_perm("archive.write"):
            toolbar_buttons.insert(0, ("新建档案", self._on_create, None))
            toolbar_buttons.insert(2, ("归档", self._on_archive, "ghost"))
            toolbar_buttons.insert(2, ("批量导入", self._on_import, "ghost"))
            toolbar_buttons.insert(3, ("下载模板", self._on_download_template, "ghost"))
        if session.has_perm("archive.admin"):
            toolbar_buttons.insert(4, ("编号配置", self._on_config, "ghost"))
        tools = self.build_toolbar(*toolbar_buttons)
        v.addLayout(tools)

        self.a_table = QTableWidget()
        self.a_table.cellDoubleClicked.connect(lambda r, c: self._on_detail())
        v.addWidget(self.a_table, 1)
        return w

    def refresh(self):
        """刷新档案列表、分类下拉、字典。"""
        try:
            info = self.api.dicts().get("data", {})
            self.security_levels = info.get("security_levels", [])
            self.retention_periods = info.get("retention_periods", [])
            try:
                self.locations = self.api.dict_items("storage_location").get("data", [])
            except ApiError:
                self.locations = []
            try:
                self.file_types = self.api.dict_items("file_type").get("data", [])
            except ApiError:
                self.file_types = []
            try:
                self.storage_types = self.api.dict_items("storage_type").get("data", [])
            except ApiError:
                self.storage_types = []
            cats = self.api.get("/api/categories").get("data", [])
            self.categories = cats
            depts = self.api.get("/api/departments").get("data", [])
            self.departments = depts
        except ApiError as e:
            self.handle_error(e, "加载基础数据")
            return

        # 刷新分类下拉
        cur = self.a_cat.currentData()
        self.a_cat.clear(); self.a_cat.addItem("全部分类", None)
        for c in self.categories:
            self.a_cat.addItem(c["category_name"], c["id"])
        if cur is not None:
            idx = self.a_cat.findData(cur)
            if idx >= 0:
                self.a_cat.setCurrentIndex(idx)

        # 查询档案
        params = {}
        kw = self.a_kw.text().strip()
        if kw:
            params["keyword"] = kw
        cid = self.a_cat.currentData()
        if cid:
            params["category_id"] = cid
        st = self.a_status.currentData()
        if st:
            params["status"] = st
        try:
            res = self.api.get("/api/archives", params=params).get("data", {})
        except ApiError as e:
            self.handle_error(e, "查询档案")
            return
        rows = res.get("items", [])
        cat_map = {c["id"]: c["category_name"] for c in self.categories}
        dept_map = {d["id"]: d["dept_name"] for d in self.departments}

        def _latest(r):
            lu = r.get("latest_update")
            if not lu:
                return "—"
            return f"{lu.get('operator_name') or ''} {lu.get('created_at') or ''}"

        # 全部可选列的取值函数
        resolvers = {
            "archive_no": lambda r: r.get("archive_no"),
            "archive_name": lambda r: r.get("archive_name"),
            "file_type": lambda r: r.get("file_type"),
            "dept_name": lambda r: dept_map.get(r.get("dept_id"), r.get("dept_id")),
            "owner_name": lambda r: r.get("owner_name"),
            "location": lambda r: r.get("physical_location") or (
                f"{r.get('cabinet_no') or ''}-{r.get('column_no') or ''}-{r.get('layer_no') or ''}"
            ),
            "storage_type": lambda r: r.get("storage_type"),
            "copy_count": lambda r: r.get("copy_count"),
            "security_name": lambda r: r.get("security_name"),
            "archive_date": lambda r: r.get("archive_date"),
            "validity_months": lambda r: r.get("validity_months"),
            "expiry_date": lambda r: r.get("expiry_date"),
            "status_name": lambda r: status_name(r.get("status")),
            "latest": _latest,
        }
        # 用户列配置：有序的可见 key 列表；为空/None 用默认全列
        cfg = self.col_config or [k for k, _ in ARCHIVE_COLUMN_DEFS]
        visible = [k for k in cfg if k in resolvers]
        if not visible:
            visible = list(resolvers.keys())
        headers = [dict(ARCHIVE_COLUMN_DEFS)[k] for k in visible]
        fns = [resolvers[k] for k in visible]
        self.fill_table(self.a_table, rows, headers, mapping=fns)

    # ---- 操作 ----
    def _on_create(self):
        dlg = ArchiveFormDialog(self, None)
        if dlg.exec() == QDialog.Accepted:
            self.refresh()

    def _on_import(self):
        dlg = BatchImportDialog(self)
        dlg.exec()

    def _on_download_template(self):
        path, _ = QFileDialog.getSaveFileName(
            self, "保存导入模板", "档案导入模板.csv", "CSV 文件 (*.csv)")
        if not path:
            return
        try:
            with open(path, "w", encoding="utf-8-sig", newline="") as f:
                csv.writer(f).writerow(BatchImportDialog.TEMPLATE_COLUMNS)
            QMessageBox.information(self, "提示", f"模板已保存到：\n{path}")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")

    def _on_detail(self):
        row = self.selected_row(self.a_table)
        if not row:
            self.show_info("请先选择一条档案")
            return
        dlg = ArchiveDetailDialog(self, row["id"])
        dlg.exec()
        self.refresh()

    def _on_archive(self):
        """归档只接受草稿；普通更新接口不承担任意状态转换。"""
        row = self.selected_row(self.a_table)
        if not row:
            self.show_info("请先选择一条档案")
            return
        if row.get("status") != "draft":
            self.show_info("只有草稿档案可以归档")
            return
        if not self.ask_yesno(f"确认将档案《{row['archive_name']}》归档？"):
            return
        try:
            self.api.post(f"/api/archives/{row['id']}/archive")
            self.show_info("已归档")
            self.refresh()
        except Exception as e:  # noqa
            self.handle_error(e, "归档")

    def _on_col_config(self):
        """打开档案清单列设置对话框，保存后刷新列表。"""
        dlg = ArchiveColumnConfigDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self.col_config = dlg.result_keys
            prefs.set_pref("archive_columns", self.col_config)
            self.refresh()

    def _on_config(self):
        dlg = NumberingConfigDialog(self)
        dlg.exec()

    # ============ 分类管理 ============
    def _build_category_tab(self) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        if session.has_perm("archive.admin"):
            tools = self.build_toolbar(
                ("新建分类", self._on_cat_create, None),
                ("编辑", self._on_cat_edit, None),
                ("删除", self._on_cat_delete, "danger"),
                ("刷新", self._refresh_cat, None),
            )
        else:
            tools = self.build_toolbar(("刷新", self._refresh_cat, None))
            v.addWidget(QLabel("仅管理员可新增 / 修改分类选项。"))
        v.addLayout(tools)
        self.c_table = QTableWidget()
        v.addWidget(self.c_table, 1)
        return w

    def _refresh_cat(self):
        try:
            rows = self.api.get("/api/categories").get("data", [])
        except ApiError as e:
            self.handle_error(e, "加载分类")
            return
        self.fill_table(self.c_table, rows, ["编码", "名称", "档案层级", "排序", "状态"],
                        mapping=["category_code", "category_name", "archive_level", "sort_order", "status"])

    def _on_cat_create(self):
        dlg = CategoryFormDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self._refresh_cat()
            self.refresh()

    def _on_cat_edit(self):
        row = self.selected_row(self.c_table)
        if not row:
            QMessageBox.information(self, "提示", "请先选择要编辑的分类")
            return
        dlg = CategoryFormDialog(self, row)
        if dlg.exec() == QDialog.Accepted:
            self._refresh_cat()
            self.refresh()

    def _on_cat_delete(self):
        row = self.selected_row(self.c_table)
        if not row:
            QMessageBox.information(self, "提示", "请先选择要删除的分类")
            return
        name = row.get("category_name")
        if QMessageBox.question(self, "确认", "确定删除分类「" + str(name) + "」？") != QMessageBox.Yes:
            return
        try:
            self.api.delete("/api/categories/" + str(row["id"]))
            QMessageBox.information(self, "提示", "分类已删除")
        except ApiError as e:
            self.handle_error(e, "删除分类")
            return
        self._refresh_cat()
        self.refresh()

    def showEvent(self, event):
        # 首次显示时加载数据
        if self.a_table.rowCount() == 0:
            self.refresh()
        if self.c_table.rowCount() == 0:
            self._refresh_cat()
        super().showEvent(event)


# ---------------- 新建/编辑档案对话框 ----------------
class ArchiveFormDialog(QDialog):
    def __init__(self, module: ArchiveModule, archive_id=None):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.archive_id = archive_id
        self.mode = "auto"
        self.setWindowTitle("新建档案" if not archive_id else "编辑档案")
        self.resize(460, 560)
        # 拉取编号模式（仅新建时用于决定档号输入方式）
        if not archive_id:
            try:
                self.mode = self.api.get_archive_no_mode().get("data", {}).get("mode", "auto")
            except ApiError:
                self.mode = "auto"
        self._build_ui()
        if archive_id:
            self._load_existing()

    def _load_existing(self):
        """编辑时回填现有元数据；不提供状态、创建人或归档经办人修改。"""
        try:
            archive = self.api.get(f"/api/archives/{self.archive_id}").get("data") or {}  # 服务端已授权的当前快照。
        except Exception as error:
            QMessageBox.critical(self, "错误", f"读取档案失败：{error}")
            self.setEnabled(False)
            return
        self.no_label.setText(archive.get("archive_no") or "—")
        # 文本字段独立映射，不把整个后端对象作为修改载荷回传。
        for key, control in (("archive_name", self.name), ("keyword", self.keyword),
                             ("owner_name", self.owner), ("cabinet_no", self.cabinet),
                             ("column_no", self.column), ("layer_no", self.layer),
                             ("volume_no", self.volume)):
            control.setText(archive.get(key) or "")
        for key, control in (("summary", self.summary), ("remark", self.remark)):
            control.setPlainText(archive.get(key) or "")
        for key, control in (("category_id", self.category), ("dept_id", self.dept),
                             ("security_level_id", self.security), ("retention_id", self.retention),
                             ("file_type", self.file_type), ("storage_type", self.storage_type)):
            value = archive.get(key)  # 精确使用服务端 ID/编码，不能依靠显示顺序。
            index = control.findData(value)
            if index < 0 and value is not None:
                # 跨部门共享或已停用字典可能不在候选清单，保留原值避免无意改写。
                label = archive.get({"category_id": "category_name", "dept_id": "dept_name",
                                     "security_level_id": "security_name", "retention_id": "retention_name"}.get(key, key))
                control.addItem(str(label or value), value)
                index = control.count() - 1
            if index >= 0:
                control.setCurrentIndex(index)
        location = archive.get("physical_location")  # 原位置可来自停用字典，仍须完整保留。
        if location:
            index = self.location.findText(location)
            if index < 0:
                self.location.addItem(location, location)
                index = self.location.count() - 1
            self.location.setCurrentIndex(index)
        self.location.setEnabled(session.has_perm("archive.admin"))
        self.copy_count.setValue(archive.get("copy_count") or 1)
        self.pages.setValue(archive.get("page_count") or 0)
        self.validity.setValue(archive.get("validity_months") or 0)
        for key, control in (("archive_date", self.adate), ("formation_date", self.fdate), ("filing_date", self.idate)):
            value = archive.get(key)  # Java 接口按 ISO 日期返回，不附加机器时区。
            if value:
                date = QDate.fromString(str(value)[:10], "yyyy-MM-dd")
                if date.isValid():
                    control.setDate(date)
            else:
                # 空日期显示为未设置，不能在编辑其他字段时偷偷写成当天。
                control.setMinimumDate(QDate(1900, 1, 1))
                control.setSpecialValueText("未设置")
                control.setDate(control.minimumDate())

    def _build_ui(self):
        form = QFormLayout(self)
        self.name = QLineEdit()
        self.category = QComboBox()
        for c in self.module.categories:
            self.category.addItem(c["category_name"], c["id"])
        self.dept = QComboBox()
        for d in self.module.departments:
            self.dept.addItem(d["dept_name"], d["id"])
        self.security = QComboBox(); self.security.addItem("（默认）", None)
        for s in self.module.security_levels:
            self.security.addItem(s["name"], s["id"])
        self.retention = QComboBox(); self.retention.addItem("（默认）", None)
        for r in self.module.retention_periods:
            self.retention.addItem(r["name"], r["id"])
        self.keyword = QLineEdit()
        self.summary = QTextEdit(); self.summary.setFixedHeight(70)
        self.location = QComboBox()
        self.location.addItem("（未指定）", None)
        for loc in self.module.locations:
            self.location.addItem(loc["item_name"], loc["item_code"])
        # ---- #4 新增字段控件 ----
        self.file_type = QComboBox(); self.file_type.addItem("（未指定）", None)
        for ft in self.module.file_types:
            self.file_type.addItem(ft["item_name"], ft["item_name"])
        self.owner = QLineEdit()
        self.cabinet = QLineEdit(); self.cabinet.setPlaceholderText("柜号")
        self.column = QLineEdit(); self.column.setPlaceholderText("列号")
        self.layer = QLineEdit(); self.layer.setPlaceholderText("层号")
        self.storage_type = QComboBox(); self.storage_type.addItem("（未指定）", None)
        for st in self.module.storage_types:
            self.storage_type.addItem(st["item_name"], st["item_name"])
        self.copy_count = QSpinBox(); self.copy_count.setMinimum(1); self.copy_count.setMaximum(9999)
        self.copy_count.setValue(1)
        self.adate = QDateEdit(); self.adate.setCalendarPopup(True); self.adate.setDate(QDate.currentDate())
        self.validity = QSpinBox(); self.validity.setMinimum(0); self.validity.setMaximum(999)
        self.validity.setSuffix(" 月")
        self.expiry_preview = QLabel("（由存档日期+有效期自动计算）")
        self.expiry_preview.setStyleSheet("color:#888")
        self.remark = QTextEdit(); self.remark.setFixedHeight(60)
        self.volume = QLineEdit()
        self.pages = QSpinBox(); self.pages.setMaximum(999999)
        self.fdate = QDateEdit(); self.fdate.setCalendarPopup(True); self.fdate.setDate(QDate.currentDate())
        self.idate = QDateEdit(); self.idate.setCalendarPopup(True); self.idate.setDate(QDate.currentDate())
        # 存档日期/有效期变化时刷新到期预览
        self.adate.dateChanged.connect(self._refresh_expiry)
        self.validity.valueChanged.connect(self._refresh_expiry)

        # ===== 主信息（字段与「台账清单」表头保持一致） =====
        form.addRow("档案名称*：", self.name)

        # 档案编号（档号）：依编号模式显示
        if self.archive_id:
            self.no_label = QLabel("（不可修改）")
            self.no_label.setStyleSheet("color:#555")
            form.addRow("档号：", self.no_label)
        elif self.mode == "manual":
            self.archive_no = QLineEdit()
            self.archive_no.setPlaceholderText("请输入档案编号")
            form.addRow("档号*：", self.archive_no)
        else:
            self.no_preview = QLabel("（保存时自动生成）")
            self.no_preview.setStyleSheet("color:#888")
            form.addRow("档号：", self.no_preview)
            self.category.currentIndexChanged.connect(self._refresh_preview)
            self.dept.currentIndexChanged.connect(self._refresh_preview)

        form.addRow("档案分类*：", self.category)
        form.addRow("文件类型：", self.file_type)
        form.addRow("所属部门*：", self.dept)
        form.addRow("负责人：", self.owner)
        # 存放位置（含柜号/列号/层号）
        loc_layout = QHBoxLayout()
        loc_layout.addWidget(self.location, 1)
        if session.has_perm("dict.manage"):
            self.loc_manage_btn = QPushButton("管理位置")
            self.loc_manage_btn.clicked.connect(self._manage_locations)
            loc_layout.addWidget(self.loc_manage_btn)
        form.addRow("存放位置：", loc_layout)
        cabinet_layout = QHBoxLayout()
        cabinet_layout.addWidget(QLabel("柜号：")); cabinet_layout.addWidget(self.cabinet)
        cabinet_layout.addWidget(QLabel("列号：")); cabinet_layout.addWidget(self.column)
        cabinet_layout.addWidget(QLabel("层号：")); cabinet_layout.addWidget(self.layer)
        form.addRow("柜号/列号/层号：", cabinet_layout)
        form.addRow("保存类型：", self.storage_type)
        form.addRow("份数：", self.copy_count)
        form.addRow("密级：", self.security)
        form.addRow("保管期限：", self.retention)
        form.addRow("存档日期：", self.adate)
        form.addRow("有效期(月)：", self.validity)
        form.addRow("到期日期：", self.expiry_preview)

        # ===== 补充信息（选填，台账未单列） =====
        form.addRow(QLabel("<b>补充信息（选填）</b>"), QLabel(""))
        form.addRow("关键词：", self.keyword)
        form.addRow("摘要：", self.summary)
        form.addRow("卷号：", self.volume)
        form.addRow("页数：", self.pages)
        form.addRow("形成日期：", self.fdate)
        form.addRow("归档日期：", self.idate)
        form.addRow("备注：", self.remark)

        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        form.addRow(btns)

        if not self.archive_id and self.mode == "auto":
            self._refresh_preview()

    def _refresh_expiry(self):
        ad = self.adate.date()
        vm = self.validity.value()
        if ad == self.adate.minimumDate() and self.adate.specialValueText():
            self.expiry_preview.setText("（未设置存档日期，到期日期为空）")
            return
        if vm <= 0:
            self.expiry_preview.setText("（未设置有效期，到期日期为空）")
            return
        # 简单加月计算
        import calendar
        total = (ad.year() * 12 + (ad.month() - 1)) + vm
        y, m = total // 12, total % 12 + 1
        last = calendar.monthrange(y, m)[1]
        d = min(ad.day(), last)
        self.expiry_preview.setText(f"（到期）{y:04d}-{m:02d}-{d:02d}")

    def _refresh_preview(self):
        cid = self.category.currentData()
        did = self.dept.currentData()
        if not cid or not did:
            self.no_preview.setText("（保存时自动生成）")
            return
        try:
            no = self.api.preview_archive_no(did, cid).get("data", {}).get("archive_no", "")
            self.no_preview.setText(f"（预览）{no}")
        except ApiError:
            self.no_preview.setText("（保存时自动生成）")

    def _submit(self):
        if not self.name.text().strip():
            QMessageBox.warning(self, "提示", "请填写档案名称")
            return
        if not self.archive_id and self.mode == "manual":
            if not self.archive_no.text().strip():
                QMessageBox.warning(self, "提示", "请填写档案编号")
                return
        payload = {
            "archive_name": self.name.text().strip(),
            "category_id": self.category.currentData(),
            "dept_id": self.dept.currentData(),
            "security_level_id": self.security.currentData(),
            "retention_id": self.retention.currentData(),
            "keyword": self.keyword.text().strip() or None,
            "summary": self.summary.toPlainText().strip() or None,
            "physical_location": self.location.currentText() if self.location.currentData() else None,
            "volume_no": self.volume.text().strip() or None,
            "page_count": self.pages.value(),
            "formation_date": self.fdate.date().toString("yyyy-MM-dd"),
            "filing_date": self.idate.date().toString("yyyy-MM-dd"),
            # ---- #4 新增字段 ----
            "file_type": self.file_type.currentData(),
            "owner_name": self.owner.text().strip() or None,
            "cabinet_no": self.cabinet.text().strip() or None,
            "column_no": self.column.text().strip() or None,
            "layer_no": self.layer.text().strip() or None,
            "storage_type": self.storage_type.currentData(),
            "copy_count": self.copy_count.value(),
            "archive_date": self.adate.date().toString("yyyy-MM-dd"),
            "validity_months": self.validity.value() or None,
            "remark": self.remark.toPlainText().strip() or None,
        }
        # 日期控件的“未设置”哨兵仅用于显示，不能写成真实的 1900 年业务日期。
        for key, control in (("archive_date", self.adate), ("formation_date", self.fdate), ("filing_date", self.idate)):
            if control.specialValueText() and control.date() == control.minimumDate():
                payload[key] = None
        if not self.archive_id and self.mode == "manual":
            payload["archive_no"] = self.archive_no.text().strip()
        try:
            if self.archive_id:
                self.api.put(f"/api/archives/{self.archive_id}", payload)
                QMessageBox.information(self, "提示", "档案更新成功")
            else:
                self.api.post("/api/archives", payload)
                QMessageBox.information(self, "提示", "档案创建成功")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")
            return
        self.accept()

    def _manage_locations(self):
        dlg = LocationManageDialog(self.module)
        dlg.exec()
        # 重新加载位置选项
        self.location.clear()
        self.location.addItem("（未指定）", None)
        for loc in self.module.locations:
            self.location.addItem(loc["item_name"], loc["item_code"])


# ---------------- 档案详情对话框 ----------------
class ArchiveDetailDialog(QDialog):
    def __init__(self, module: ArchiveModule, archive_id: int):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.archive_id = archive_id
        self.setWindowTitle("档案详情")
        self.resize(620, 520)
        self._build_ui()
        self._load()

    def _build_ui(self):
        v = QVBoxLayout(self)
        self.info = QTextEdit(); self.info.setReadOnly(True)
        v.addWidget(QLabel("基本信息：")); v.addWidget(self.info)

        # 存放位置（管理员可修改）
        loc_box = QHBoxLayout()
        loc_box.addWidget(QLabel("存放位置："))
        self.detail_loc = QComboBox()
        self.detail_loc.addItem("（未指定）", None)
        for loc in self.module.locations:
            self.detail_loc.addItem(loc["item_name"], loc["item_code"])
        self.detail_loc.setMinimumWidth(180)
        loc_box.addWidget(self.detail_loc)
        self.loc_save = QPushButton("保存位置")
        self.loc_save.clicked.connect(self._save_location)
        loc_box.addWidget(self.loc_save)
        loc_box.addStretch(1)
        can_write = session.has_perm("archive.write") and session.has_perm("archive.admin")  # 位置变更还需档案管理员权限。
        self.detail_loc.setEnabled(can_write)
        self.loc_save.setVisible(can_write)
        v.addLayout(loc_box)

        # 更新记录（最新一条 + 查看全部）
        upd_box = QHBoxLayout()
        upd_box.addWidget(QLabel("更新记录："))
        self.upd_label = QLabel("—")
        self.upd_label.setStyleSheet("color:#555")
        upd_box.addWidget(self.upd_label, 1)
        self.upd_btn = QPushButton("查看全部")
        self.upd_btn.clicked.connect(self._show_logs)
        upd_box.addWidget(self.upd_btn)
        v.addLayout(upd_box)

        v.addWidget(QLabel("电子文件："))
        file_actions = []  # 各文件操作独立授权，不因可看档案就开放下载或上传。
        if session.has_perm("file.upload"):
            file_actions.append(("上传文件", self._upload, None))
        if session.has_perm("file.preview"):
            file_actions.append(("预览", self._preview, "ghost"))
        if session.has_perm("file.download"):
            file_actions.append(("下载", self._download, "ghost"))
        fbar = self.module.build_toolbar(*file_actions)
        v.addLayout(fbar)
        self.file_table = QTableWidget()
        v.addWidget(self.file_table, 1)

        btns = QHBoxLayout()
        self.archive_btn = QPushButton("归档")
        self.archive_btn.clicked.connect(self._archive)
        self.edit_btn = QPushButton("编辑信息")  # 复用既有表单，只增加缺失的编辑入口。
        self.edit_btn.setVisible(session.has_perm("archive.write"))
        self.edit_btn.clicked.connect(self._edit_metadata)
        self.grants_btn = QPushButton("授权共享")  # 只提供入口，是否具备管理范围由服务端判断。
        self.grants_btn.setVisible(session.has_perm("archive.share"))
        self.grants_btn.clicked.connect(self._manage_grants)
        close_btn = QPushButton("关闭"); close_btn.clicked.connect(self.reject)
        btns.addStretch(1); btns.addWidget(self.grants_btn); btns.addWidget(self.edit_btn); btns.addWidget(self.archive_btn); btns.addWidget(close_btn)
        v.addLayout(btns)

    def _load(self):
        try:
            arch = self.api.get(f"/api/archives/{self.archive_id}").get("data", {})
            self.arch = arch
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"加载失败：{e}")
            self.reject(); return
        def _v(x, empty="—"):
            return x if x not in (None, "", []) else empty
        lines = [
            f"档号：{arch.get('archive_no')}",
            f"名称：{arch.get('archive_name')}",
            f"分类：{arch.get('category_name') or '—'}",
            f"部门：{arch.get('dept_name') or '—'}",
            f"状态：{status_name(arch.get('status'))}",
            f"文件类型：{_v(arch.get('file_type'))}",
            f"档案负责人：{_v(arch.get('owner_name'))}",
            f"柜号/列号/层号：{_v(arch.get('cabinet_no'))}/{_v(arch.get('column_no'))}/{_v(arch.get('layer_no'))}",
            f"保存类型：{_v(arch.get('storage_type'))}",
            f"保存份数：{arch.get('copy_count')}",
            f"密级：{_v(arch.get('security_name'))}",
            f"存档日期：{_v(arch.get('archive_date'))}",
            f"有效期(月)：{_v(arch.get('validity_months'))}",
            f"到期日期：{_v(arch.get('expiry_date'))}",
            f"文件数：{arch.get('file_count')}",
        ]
        if arch.get("keyword"):
            lines.append(f"关键词：{arch.get('keyword')}")
        if arch.get("summary"):
            lines.append(f"摘要：{arch.get('summary')}")
        if arch.get("remark"):
            lines.append(f"备注：{arch.get('remark')}")
        self.info.setPlainText("\n".join(lines))
        # 最新更新记录
        lu = arch.get("latest_update")
        if lu:
            fields = "、".join(lu.get("changed_fields") or [])
            self.upd_label.setText(
                f"{lu.get('operator_name') or ''} 于 {lu.get('created_at') or ''}"
                f"（变更：{fields or lu.get('action')}）"
            )
        else:
            self.upd_label.setText("暂无更新记录")
        # 预选当前存放位置
        cur = arch.get("physical_location")
        if cur:
            idx = self.detail_loc.findText(cur)
            if idx >= 0:
                self.detail_loc.setCurrentIndex(idx)
        # 状态变化走归档专用接口，普通表单不能任意更改状态。
        self.archive_btn.setEnabled(arch.get("status") == "draft" and session.has_perm("archive.write"))
        self.edit_btn.setEnabled(arch.get("status") in ("draft", "archive") and session.has_perm("archive.write"))
        self._load_files()

    def _load_files(self):
        try:
            rows = self.api.get("/api/files", params={"archive_id": self.archive_id}).get("data", [])
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"加载文件失败：{e}")
            return
        self.module.fill_table(self.file_table, rows,
                               ["文件名", "大小(字节)", "版本", "上传时间"],
                               mapping=["file_name", "file_size", "version_no", "uploaded_at"])

    def _upload(self):
        """文件上传在后台执行；完成后才刷新附件与主列表。"""
        path, _ = QFileDialog.getOpenFileName(self, "选择要上传的文件")
        if not path:
            return
        archive_id = self.archive_id  # 固定本次详情页对应的档案。
        run_task(self, lambda: self.api.upload_archive_file(archive_id, path),
                 self._uploaded,
                 lambda error: QMessageBox.critical(self, "错误", f"上传失败：{error}"),
                 controls=(self,))

    def _uploaded(self, _result):
        """上传成功后在主线程重新读取服务端档案及附件元数据。"""
        QMessageBox.information(self, "提示", "上传成功")
        self._load_files()
        self.module.refresh()

    def _download(self):
        """捕获文件和目标路径后后台下载，避免主线程被大附件阻塞。"""
        row = self.module.selected_row(self.file_table)
        if not row:
            QMessageBox.information(self, "提示", "请先选择要下载的文件")
            return
        save_path, _ = QFileDialog.getSaveFileName(self, "保存文件", row["file_name"])
        if not save_path:
            return
        file_id = row["id"]  # 防止后台工作读取已改变的表格选择。
        run_task(self, lambda: self.api.download_archive_file(file_id, save_path),
                 lambda _result: QMessageBox.information(self, "提示", "下载完成"),
                 lambda error: QMessageBox.critical(self, "错误", f"下载失败：{error}"),
                 controls=(self,))

    def _preview(self):
        row = self.module.selected_row(self.file_table)
        if not row:
            QMessageBox.information(self, "提示", "请先选择要预览的文件")
            return
        from app.ui.preview import PreviewDialog
        dlg = PreviewDialog(self.api, row["id"], row["file_name"],
                            row.get("mime_type") or "")
        dlg.exec()

    def _save_location(self):
        loc = self.detail_loc.currentData()
        val = self.detail_loc.currentText() if loc else None
        try:
            self.api.put(f"/api/archives/{self.archive_id}", {"physical_location": val})
            QMessageBox.information(self, "提示", "存放位置已更新")
            self.arch["physical_location"] = val
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")

    def _archive(self):
        if not QMessageBox.question(self, "确认", "确认归档该档案？",
                                    QMessageBox.Yes | QMessageBox.No) == QMessageBox.Yes:
            return
        try:
            self.api.post(f"/api/archives/{self.archive_id}/archive")
            QMessageBox.information(self, "提示", "已归档")
            self.accept()
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"归档失败：{e}")

    def _show_logs(self):
        dlg = UpdateLogDialog(self.module, self.archive_id)
        dlg.exec()

    def _manage_grants(self):
        """打开指定档案的人员/部门授权，查看权限不隐含文件下载权限。"""
        from app.ui.archive_grants import ArchiveGrantDialog
        dialog = ArchiveGrantDialog(self, self.api, self.archive_id)  # 独立配置本档案的共享记录。
        dialog.exec()

    def _edit_metadata(self):
        """在原有元数据表单编辑并重新读取详情，状态变化仍只能走专用流程。"""
        dialog = ArchiveFormDialog(self.module, self.archive_id)  # 使用已加载的字典和当前档案回填。
        if dialog.exec() == QDialog.Accepted:
            self._load()
            self.module.refresh()


# ---------------- 新建/编辑分类对话框 ----------------
class CategoryFormDialog(QDialog):
    def __init__(self, module: ArchiveModule, category: dict = None):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.category = category
        self.is_edit = category is not None
        self.setWindowTitle("编辑分类" if self.is_edit else "新建分类")
        self.resize(380, 320)
        self._build_ui()
        if self.is_edit:
            self._fill(category)

    def _fill(self, c: dict):
        self.code.setText(str(c.get("category_code", "")))
        self.name.setText(str(c.get("category_name", "")))
        self.level.setValue(int(c.get("archive_level", 1) or 1))
        self.sort.setValue(int(c.get("sort_order", 0) or 0))
        for i in range(self.sec.count()):
            if self.sec.itemData(i) == c.get("default_security_level_id"):
                self.sec.setCurrentIndex(i); break
        for i in range(self.ret.count()):
            if self.ret.itemData(i) == c.get("default_retention_id"):
                self.ret.setCurrentIndex(i); break

    def _build_ui(self):
        form = QFormLayout(self)
        self.code = QLineEdit()
        self.name = QLineEdit()
        self.level = QSpinBox(); self.level.setValue(1)
        self.sort = QSpinBox()
        self.sec = QComboBox(); self.sec.addItem("（默认）", None)
        for s in self.module.security_levels:
            self.sec.addItem(s["name"], s["id"])
        self.ret = QComboBox(); self.ret.addItem("（默认）", None)
        for r in self.module.retention_periods:
            self.ret.addItem(r["name"], r["id"])
        form.addRow("分类编码*：", self.code)
        form.addRow("分类名称*：", self.name)
        form.addRow("档案层级：", self.level)
        form.addRow("默认密级：", self.sec)
        form.addRow("默认保管期限：", self.ret)
        form.addRow("排序：", self.sort)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        form.addRow(btns)

    def _submit(self):
        if not self.code.text().strip() or not self.name.text().strip():
            QMessageBox.warning(self, "提示", "请填写编码与名称")
            return
        payload = {
            "category_code": self.code.text().strip(),
            "category_name": self.name.text().strip(),
            "archive_level": self.level.value(),
            "default_security_level_id": self.sec.currentData(),
            "default_retention_id": self.ret.currentData(),
            "sort_order": self.sort.value(),
            "status": 1,
        }
        try:
            if self.is_edit:
                self.api.put(f"/api/categories/{self.category['id']}", payload)
                QMessageBox.information(self, "提示", "分类更新成功")
            else:
                self.api.post("/api/categories", payload)
                QMessageBox.information(self, "提示", "分类创建成功")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")
            return
        self.accept()


# ---------------- 批量导入档案对话框 ----------------
class BatchImportDialog(QDialog):
    TEMPLATE_COLUMNS = [
        "档案名称", "档案类别", "部门", "文件类型", "档案负责人",
        "柜号", "列号", "层号", "保存类型", "保存份数",
        "存档日期", "有效期", "备注", "密级", "保管期限",
    ]

    def __init__(self, module: "ArchiveModule"):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self._content = None
        self.setWindowTitle("批量导入档案")
        self.resize(540, 340)
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)
        hint = QLabel(
            "支持 CSV 文件（UTF-8 编码，首行为表头）。必填：档案名称、档案类别、部门；"
            "其余为可选。分类与部门按名称匹配，密级/保管期限也按名称匹配。")
        hint.setWordWrap(True)
        v.addWidget(hint)

        bar = QHBoxLayout()
        self.path_edit = QLineEdit(); self.path_edit.setReadOnly(True)
        pick = QPushButton("选择 CSV 文件")
        pick.clicked.connect(self._pick)
        bar.addWidget(self.path_edit, 1)
        bar.addWidget(pick)
        v.addLayout(bar)

        self.info = QLabel("尚未选择文件")
        v.addWidget(self.info)

        self.result = QTextEdit(); self.result.setReadOnly(True)
        self.result.setMaximumHeight(110)
        v.addWidget(self.result, 1)

        btns = QHBoxLayout()
        imp = QPushButton("开始导入"); imp.clicked.connect(self._import)
        close = QPushButton("关闭"); close.clicked.connect(self.reject)
        btns.addStretch(1); btns.addWidget(imp); btns.addWidget(close)
        v.addLayout(btns)

    def _pick(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "选择 CSV 文件", "", "CSV 文件 (*.csv);;所有文件 (*.*)")
        if not path:
            return
        self.path_edit.setText(path)
        try:
            with open(path, "r", encoding="utf-8-sig", newline="") as f:
                rows = list(csv.reader(f))
            n = max(0, len(rows) - 1)
            self._content = open(path, "r", encoding="utf-8-sig").read()
            self.info.setText(f"已选择：{path}\n数据行数：{n}")
        except Exception as e:  # noqa
            self.info.setText(f"读取失败：{e}")
            self._content = None

    def _import(self):
        if not self._content:
            QMessageBox.warning(self, "提示", "请先选择 CSV 文件")
            return
        try:
            r = self.api.post("/api/archives/import", {"content": self._content})
            d = r.get("data", {}) or {}
            lines = [
                f"总计：{d.get('total')} 条",
                f"成功：{d.get('created')} 条",
                f"失败：{d.get('failed')} 条",
            ]
            for er in (d.get("errors") or [])[:20]:
                lines.append(f"第 {er.get('row')} 行：{er.get('error')}")
            self.result.setText("\n".join(lines))
            QMessageBox.information(
                self, "导入完成", f"成功 {d.get('created')} 条，失败 {d.get('failed')} 条")
            self.module.refresh()
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"导入失败：{e}")


# ---------------- 物理位置选项管理（管理员） ----------------
class LocationManageDialog(QDialog):
    def __init__(self, module: ArchiveModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.setWindowTitle("管理物理位置选项")
        self.resize(360, 320)
        self._build_ui()
        self._load()

    def _build_ui(self):
        v = QVBoxLayout(self)
        self.listw = QListWidget()
        v.addWidget(self.listw, 1)
        add_bar = QHBoxLayout()
        self.new_name = QLineEdit(); self.new_name.setPlaceholderText("新增位置名称")
        add_btn = QPushButton("添加")
        add_btn.clicked.connect(self._add)
        add_bar.addWidget(self.new_name, 1)
        add_bar.addWidget(add_btn)
        v.addLayout(add_bar)
        del_btn = QPushButton("删除选中")
        del_btn.clicked.connect(self._delete)
        v.addWidget(del_btn)
        close_btn = QPushButton("关闭")
        close_btn.clicked.connect(self.accept)
        v.addWidget(close_btn)

    def _load(self):
        try:
            items = self.api.dict_items("storage_location").get("data", [])
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"加载失败：{e}")
            return
        self.listw.clear()
        for it in items:
            item = QListWidgetItem(it["item_name"])
            item.setData(Qt.UserRole, it["id"])
            self.listw.addItem(item)

    def _add(self):
        name = self.new_name.text().strip()
        if not name:
            QMessageBox.warning(self, "提示", "请输入位置名称")
            return
        try:
            self.api.create_dict_item({
                "type_code": "storage_location",
                "item_code": name,
                "item_name": name,
            })
            self.new_name.clear()
            self._load()
            self.module.refresh()
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"添加失败：{e}")

    def _delete(self):
        item = self.listw.currentItem()
        if not item:
            QMessageBox.information(self, "提示", "请选择要删除的位置")
            return
        if not QMessageBox.question(
            self, "确认", f"确认删除位置「{item.text()}」？",
            QMessageBox.Yes | QMessageBox.No
        ) == QMessageBox.Yes:
            return
        item_id = item.data(Qt.UserRole)
        try:
            self.api.delete_dict_item(item_id)
            self._load()
            self.module.refresh()
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"删除失败：{e}")


# ---------------- 档案编号规则配置（管理员） ----------------
class NumberingConfigDialog(QDialog):
    def __init__(self, module: ArchiveModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.rules = []
        self.setWindowTitle("档案编号配置")
        self.resize(640, 480)
        self._build_ui()
        self._load()

    def _build_ui(self):
        v = QVBoxLayout(self)
        # 模式选择
        mode_box = QGroupBox("编号方式")
        mh = QHBoxLayout(mode_box)
        self.rb_auto = QRadioButton("自动生成")
        self.rb_manual = QRadioButton("手动输入")
        self.mode_group = QButtonGroup(self)
        self.mode_group.addButton(self.rb_auto, 1)
        self.mode_group.addButton(self.rb_manual, 2)
        mh.addWidget(self.rb_auto)
        mh.addWidget(self.rb_manual)
        mh.addStretch(1)
        self.mode_save = QPushButton("保存模式")
        self.mode_save.clicked.connect(self._save_mode)
        mh.addWidget(self.mode_save)
        v.addWidget(mode_box)

        # 规则表
        v.addWidget(QLabel("编号规则（按优先级匹配，可启停）："))
        self.rule_table = QTableWidget()
        self.rule_table.setColumnCount(7)
        self.rule_table.setHorizontalHeaderLabels(
            ["启用", "规则名称", "作用域", "模板", "重置周期", "流水位数", "优先级"]
        )
        self.rule_table.horizontalHeader().setStretchLastSection(True)
        v.addWidget(self.rule_table, 1)

        bar = QHBoxLayout()
        self.btn_add = QPushButton("新增规则")
        self.btn_edit = QPushButton("编辑选中")
        self.btn_del = QPushButton("删除选中")
        self.btn_preview = QPushButton("预览编号")
        self.btn_close = QPushButton("关闭")
        self.btn_add.clicked.connect(self._add_rule)
        self.btn_edit.clicked.connect(self._edit_rule)
        self.btn_del.clicked.connect(self._del_rule)
        self.btn_preview.clicked.connect(self._preview)
        self.btn_close.clicked.connect(self.accept)
        for b in (self.btn_add, self.btn_edit, self.btn_del, self.btn_preview, self.btn_close):
            bar.addWidget(b)
        v.addLayout(bar)

    def _load(self):
        try:
            mode = self.api.get_archive_no_mode().get("data", {}).get("mode", "auto")
            (self.rb_manual if mode == "manual" else self.rb_auto).setChecked(True)
            self.rules = self.api.numbering_rules().get("data", [])
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"加载失败：{e}")
            self.rules = []
        self._fill_rules()

    def _fill_rules(self):
        self.rule_table.setRowCount(len(self.rules))
        scope_map = {"global": "全局", "dept": "按部门", "category": "按分类"}
        cycle_map = {"YEAR": "按年", "MONTH": "按月", "NEVER": "不重置"}
        for i, r in enumerate(self.rules):
            self.rule_table.setItem(i, 0, QTableWidgetItem("是" if r["enabled"] else "否"))
            self.rule_table.setItem(i, 1, QTableWidgetItem(r["rule_name"]))
            self.rule_table.setItem(i, 2, QTableWidgetItem(scope_map.get(r["scope_type"], r["scope_type"])))
            self.rule_table.setItem(i, 3, QTableWidgetItem(self._seg_summary(r)))
            self.rule_table.setItem(i, 4, QTableWidgetItem(cycle_map.get(r["reset_cycle"], r["reset_cycle"])))
            self.rule_table.setItem(i, 5, QTableWidgetItem(str(r["sequence_length"])))
            self.rule_table.setItem(i, 6, QTableWidgetItem(str(r["priority"])))

    @staticmethod
    def _seg_summary(r: dict) -> str:
        segs = r.get("segments")
        if not segs:
            return r.get("pattern") or ""
        label = {"fixed": "固定", "ref": "引用", "seq": "流水"}
        parts = []
        for s in segs:
            t = s.get("type")
            if t == "fixed":
                parts.append(f"固定({s.get('value','')})")
            elif t == "ref":
                parts.append(f"引用({s.get('ref','')})")
            else:
                parts.append(f"流水({s.get('length',0)}位)")
        return "+".join(parts)

    def _selected_rule(self):
        row = self.rule_table.currentRow()
        if row < 0 or row >= len(self.rules):
            return None
        return self.rules[row]

    def _save_mode(self):
        mode = "manual" if self.rb_manual.isChecked() else "auto"
        try:
            self.api.set_archive_no_mode(mode)
            QMessageBox.information(self, "提示", "编号模式已保存")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")

    def _add_rule(self):
        dlg = NumberingRuleDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self._load()

    def _edit_rule(self):
        r = self._selected_rule()
        if not r:
            QMessageBox.information(self, "提示", "请选择一条规则")
            return
        dlg = NumberingRuleDialog(self, r)
        if dlg.exec() == QDialog.Accepted:
            self._load()

    def _del_rule(self):
        r = self._selected_rule()
        if not r:
            QMessageBox.information(self, "提示", "请选择一条规则")
            return
        if not QMessageBox.question(
            self, "确认", f"确认删除规则「{r['rule_name']}」？",
            QMessageBox.Yes | QMessageBox.No
        ) == QMessageBox.Yes:
            return
        try:
            self.api.delete_numbering_rule(r["id"])
            self._load()
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"删除失败：{e}")

    def _preview(self):
        if not self.module.categories or not self.module.departments:
            QMessageBox.information(self, "提示", "请先在档案目录页加载基础数据")
            return
        cat = self.module.categories[0]
        dept = self.module.departments[0]
        retention_id = None
        try:
            rets = self.api.dicts().get("data", {}).get("retention_periods", [])
            if rets:
                retention_id = rets[0]["id"]
        except Exception:  # noqa
            pass
        try:
            no = self.api.preview_archive_no(dept["id"], cat["id"], retention_id).get("data", {}).get("archive_no", "")
            QMessageBox.information(self, "预览", f"按当前规则，下一份档案编号将为：\n{no}")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"预览失败：{e}")


class _SegmentRow(QWidget):
    """编号规则中的一段：固定文本 / 引用字段 / 流水号。"""

    TYPES = [("固定文本", "fixed"), ("引用字段", "ref"), ("流水号", "seq")]
    REFS = [
        ("部门编码", "dept_code"), ("分类编码", "category_code"),
        ("保存期限编码", "retention_code"), ("年份(YYYY)", "year"),
        ("月份(MM)", "month"), ("日期(DD)", "day"),
    ]

    def __init__(self, parent, data: dict = None):
        super().__init__(parent)
        self.parent = parent
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        self.type_cb = QComboBox()
        for t, v in self.TYPES:
            self.type_cb.addItem(t, v)
        self.content = QLineEdit(); self.content.setPlaceholderText("固定文本")
        self.ref_cb = QComboBox()
        for t, v in self.REFS:
            self.ref_cb.addItem(t, v)
        self.ref_cb.setVisible(False)
        self.length = QSpinBox(); self.length.setRange(0, 20); self.length.setValue(0)
        self.pad = QLineEdit("0"); self.pad.setMaxLength(1); self.pad.setFixedWidth(28)
        self.del_btn = QPushButton("删除")
        self.del_btn.setObjectName("danger")

        layout.addWidget(QLabel("类型")); layout.addWidget(self.type_cb)
        layout.addWidget(QLabel("内容/引用")); layout.addWidget(self.content)
        layout.addWidget(self.ref_cb)
        layout.addWidget(QLabel("长度")); layout.addWidget(self.length)
        layout.addWidget(QLabel("填充")); layout.addWidget(self.pad)
        layout.addWidget(self.del_btn)

        self.type_cb.currentIndexChanged.connect(self._on_type)
        self.type_cb.currentIndexChanged.connect(self.parent._update_preview)
        self.content.textChanged.connect(self.parent._update_preview)
        self.ref_cb.currentIndexChanged.connect(self.parent._update_preview)
        self.length.valueChanged.connect(self.parent._update_preview)
        self.pad.textChanged.connect(self.parent._update_preview)
        self.del_btn.clicked.connect(self._on_del)

        if data:
            self._load(data)

    def _on_type(self):
        t = self.type_cb.currentData()
        if t == "fixed":
            self.content.setVisible(True); self.ref_cb.setVisible(False)
            self.content.setPlaceholderText("固定文本，如 XZ")
        elif t == "ref":
            self.content.setVisible(False); self.ref_cb.setVisible(True)
        else:  # seq
            self.content.setVisible(False); self.ref_cb.setVisible(False)

    def _on_del(self):
        self.parent._remove_segment(self)

    def _load(self, d: dict):
        idx = self.type_cb.findData(d.get("type", "fixed"))
        self.type_cb.setCurrentIndex(idx if idx >= 0 else 0)
        self.content.setText(d.get("value", "") or "")
        ridx = self.ref_cb.findData(d.get("ref", ""))
        if ridx >= 0:
            self.ref_cb.setCurrentIndex(ridx)
        self.length.setValue(int(d.get("length", 0) or 0))
        self.pad.setText(d.get("pad", "0") or "0")
        self._on_type()

    def to_dict(self) -> dict:
        t = self.type_cb.currentData()
        d = {"type": t, "length": self.length.value(), "pad": self.pad.text() or "0"}
        if t == "fixed":
            d["value"] = self.content.text()
            d["ref"] = ""
        elif t == "ref":
            d["ref"] = self.ref_cb.currentData()
            d["value"] = ""
        else:
            d["value"] = ""; d["ref"] = ""
        return d


class NumberingRuleDialog(QDialog):
    """新增 / 编辑单条编号规则（分段式编辑器）。"""

    def __init__(self, parent, rule: dict = None):
        super().__init__(parent)
        self.api = parent.api
        self.rule = rule
        self.seg_rows = []
        self.setWindowTitle("新增编号规则" if not rule else "编辑编号规则")
        self.resize(720, 420)
        self._build_ui()
        # 载入已有分段（无则给一个默认示例）
        segs = (rule or {}).get("segments")
        if segs:
            for s in segs:
                self._add_segment(s)
        else:
            self._add_segment({"type": "fixed", "value": "XZ", "ref": "", "length": 2, "pad": "0"})
            self._add_segment({"type": "ref", "value": "", "ref": "dept_code", "length": 0, "pad": "0"})
            self._add_segment({"type": "seq", "value": "", "ref": "", "length": 4, "pad": "0"})

    def _build_ui(self):
        v = QVBoxLayout(self)
        form = QFormLayout()
        self.code = QLineEdit(); self.code.setPlaceholderText("规则编码，如 dept_hr")
        self.name = QLineEdit()
        self.scope = QComboBox()
        for t, val in [("全局", "global"), ("按部门", "dept"), ("按分类", "category")]:
            self.scope.addItem(t, val)
        self.cycle = QComboBox()
        for t, val in [("按年", "YEAR"), ("按月", "MONTH"), ("不重置", "NEVER")]:
            self.cycle.addItem(t, val)
        self.enabled = QComboBox(); self.enabled.addItems(["启用", "停用"])
        self.priority = QSpinBox(); self.priority.setMaximum(999)
        form.addRow("规则编码*：", self.code)
        form.addRow("规则名称*：", self.name)
        form.addRow("作用域：", self.scope)
        form.addRow("重置周期：", self.cycle)
        form.addRow("状态：", self.enabled)
        form.addRow("优先级：", self.priority)
        v.addLayout(form)

        v.addWidget(QLabel("编号分段（按顺序拼接，每段可设固定文本 / 引用字段 / 流水号）："))
        self.seg_box = QVBoxLayout()
        v.addLayout(self.seg_box)
        add_btn = QPushButton("+ 添加一段")
        add_btn.clicked.connect(lambda: self._add_segment())
        v.addWidget(add_btn)

        self.preview = QLabel("示例预览：—")
        self.preview.setStyleSheet("color:#1565c0;font-weight:bold;")
        v.addWidget(self.preview)

        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        v.addWidget(btns)

        if self.rule:
            self.code.setText(self.rule.get("rule_code", ""))
            self.name.setText(self.rule.get("rule_name", ""))
            self.scope.setCurrentText(
                {"global": "全局", "dept": "按部门", "category": "按分类"}.get(self.rule.get("scope_type"), "全局"))
            self.cycle.setCurrentText(
                {"YEAR": "按年", "MONTH": "按月", "NEVER": "不重置"}.get(self.rule.get("reset_cycle"), "按年"))
            self.enabled.setCurrentIndex(0 if self.rule.get("enabled") else 1)
            self.priority.setValue(self.rule.get("priority", 0))

    def _add_segment(self, data: dict = None):
        row = _SegmentRow(self, data)
        self.seg_rows.append(row)
        self.seg_box.addWidget(row)
        self._update_preview()

    def _remove_segment(self, row):
        if row in self.seg_rows:
            self.seg_rows.remove(row)
        row.deleteLater()
        self._update_preview()

    def _update_preview(self):
        segs = [r.to_dict() for r in self.seg_rows]
        sample = {"dept_code": "HR", "category_code": "RS", "retention_code": "30Y",
                  "year": "2026", "month": "08", "day": "28"}
        parts = []
        for s in segs:
            if s["type"] == "fixed":
                val = s["value"]
            elif s["type"] == "ref":
                val = sample.get(s["ref"], "")
            else:
                val = "1".zfill(s["length"] or 1)
            if s["length"] and len(val) < s["length"]:
                val = (s["pad"] or "0") * (s["length"] - len(val)) + val
            elif s["length"]:
                val = val[:s["length"]]
            parts.append(val)
        self.preview.setText("示例预览：" + "".join(parts))

    def _submit(self):
        if not self.code.text().strip() or not self.name.text().strip():
            QMessageBox.warning(self, "提示", "请填写规则编码与名称")
            return
        if not self.seg_rows:
            QMessageBox.warning(self, "提示", "请至少添加一段")
            return
        segs = [r.to_dict() for r in self.seg_rows]
        payload = {
            "rule_code": self.code.text().strip(),
            "rule_name": self.name.text().strip(),
            "scope_type": self.scope.currentData() if hasattr(self.scope, "currentData") else
                         self.scope.itemData(self.scope.currentIndex()),
            "segments": segs,
            "reset_cycle": self.cycle.currentData() if hasattr(self.cycle, "currentData") else
                            self.cycle.itemData(self.cycle.currentIndex()),
            "sequence_length": 4,
            "enabled": 1 if self.enabled.currentIndex() == 0 else 0,
            "priority": self.priority.value(),
        }
        try:
            if self.rule:
                self.api.update_numbering_rule(self.rule["id"], payload)
            else:
                self.api.create_numbering_rule(payload)
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")
            return
        self.accept()


# ---------------- 档案更新记录（全部） ----------------
class UpdateLogDialog(QDialog):
    """展示某档案的全部更新记录（更新人 + 时间 + 变更字段）。"""

    def __init__(self, module: ArchiveModule, archive_id: int):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.archive_id = archive_id
        self.setWindowTitle("档案更新记录")
        self.resize(560, 360)
        v = QVBoxLayout(self)
        self.table = QTableWidget()
        self.table.setColumnCount(4)
        self.table.setHorizontalHeaderLabels(["操作人", "操作", "时间", "变更字段"])
        self.table.horizontalHeader().setStretchLastSection(True)
        v.addWidget(self.table, 1)
        close_btn = QPushButton("关闭")
        close_btn.clicked.connect(self.accept)
        v.addWidget(close_btn)
        self._load()

    def _load(self):
        try:
            logs = self.api.get_update_logs(self.archive_id).get("data", [])
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"加载失败：{e}")
            return
        self.table.setRowCount(len(logs))
        action_map = {"create": "创建", "update": "更新", "archive": "归档"}
        for i, r in enumerate(logs):
            fields = "、".join(r.get("changed_fields") or [])
            self.table.setItem(i, 0, QTableWidgetItem(r.get("operator_name") or "—"))
            self.table.setItem(i, 1, QTableWidgetItem(action_map.get(r.get("action"), r.get("action"))))
            self.table.setItem(i, 2, QTableWidgetItem((r.get("created_at") or "")[:19]))
            self.table.setItem(i, 3, QTableWidgetItem(fields or "—"))


# ---------------- 档案清单列字段管理（显隐 + 顺序） ----------------
class ArchiveColumnConfigDialog(QDialog):
    """档案清单列设置：勾选可见列、调整显示顺序，保存后持久化。"""

    def __init__(self, module: ArchiveModule):
        super().__init__(module)
        self.module = module
        self.setWindowTitle("档案清单列设置")
        self.resize(360, 480)
        self._build_ui()
        self._load()

    def _build_ui(self):
        v = QVBoxLayout(self)
        v.addWidget(QLabel("勾选要显示的列，并用「上移/下移」调整显示顺序："))
        self.table = QTableWidget()
        self.table.setColumnCount(2)
        self.table.setHorizontalHeaderLabels(["显示", "列名"])
        self.table.horizontalHeader().setStretchLastSection(True)
        self.table.verticalHeader().setVisible(False)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        v.addWidget(self.table, 1)

        bar = QHBoxLayout()
        self.btn_up = QPushButton("上移")
        self.btn_down = QPushButton("下移")
        self.btn_reset = QPushButton("恢复默认")
        self.btn_up.clicked.connect(self._on_up)
        self.btn_down.clicked.connect(self._on_down)
        self.btn_reset.clicked.connect(self._on_reset)
        bar.addWidget(self.btn_up)
        bar.addWidget(self.btn_down)
        bar.addWidget(self.btn_reset)
        bar.addStretch(1)
        v.addLayout(bar)

        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        v.addWidget(btns)

    def _load(self):
        cfg = self.module.col_config or [k for k, _ in ARCHIVE_COLUMN_DEFS]
        # 当前可见集合
        visible = set(cfg)
        # 顺序：先按配置顺序，再补上未出现的列（保证全部列出）
        ordered = [k for k in cfg if k in dict(ARCHIVE_COLUMN_DEFS)]
        for k, _ in ARCHIVE_COLUMN_DEFS:
            if k not in ordered:
                ordered.append(k)
        self.keys = ordered
        self._reload_rows(visible)

    def _reload_rows(self, visible=None):
        if visible is None:
            visible = self._visible_set()
        title_map = dict(ARCHIVE_COLUMN_DEFS)
        self.table.setRowCount(len(self.keys))
        for i, k in enumerate(self.keys):
            chk = QTableWidgetItem()
            chk.setCheckState(Qt.Checked if k in visible else Qt.Unchecked)
            self.table.setItem(i, 0, chk)
            self.table.setItem(i, 1, QTableWidgetItem(title_map[k]))

    def _visible_set(self):
        s = set()
        for i in range(self.table.rowCount()):
            if self.table.item(i, 0).checkState() == Qt.Checked:
                s.add(self.keys[i])
        return s

    def _move(self, delta: int):
        r = self.table.currentRow()
        if r < 0 or r + delta < 0 or r + delta >= self.table.rowCount():
            return
        visible = self._visible_set()
        self.keys[r], self.keys[r + delta] = self.keys[r + delta], self.keys[r]
        self._reload_rows(visible)
        self.table.selectRow(r + delta)

    def _on_up(self):
        self._move(-1)

    def _on_down(self):
        self._move(1)

    def _on_reset(self):
        self.keys = [k for k, _ in ARCHIVE_COLUMN_DEFS]
        self._reload_rows(set(self.keys))

    def _submit(self):
        self.result_keys = [
            self.keys[i] for i in range(self.table.rowCount())
            if self.table.item(i, 0).checkState() == Qt.Checked
        ]
        if not self.result_keys:
            QMessageBox.warning(self, "提示", "至少保留一列")
            return
        self.accept()
