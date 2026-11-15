# -*- coding: utf-8 -*-
"""
app/ui/modules/approval_config.py
==================================
审批流配置（超级管理员）：
  1）「审批模板」页：管理审批模板与节点（节点审批人来源：角色 / 账号 /
     权限等级 / 权限码 / 部门领导 / 创建人领导；支持按档案信息词缀设置路由）。
  2）「审批流」页：查看系统已生成的审批流程实例（借阅/销毁提交后自动生成），
     可查看每个流程的节点进度与审批记录，超级管理员可直接在此通过/驳回。

说明：审批流实例由系统在提交借阅/销毁申请时自动创建，本页仅做查看与监管。
"""
from PySide6.QtCore import Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (
    QCheckBox, QComboBox, QDialog, QFormLayout, QHBoxLayout, QLabel,
    QLineEdit, QListWidget, QListWidgetItem, QPushButton, QTableWidget,
    QTableWidgetItem, QTabWidget, QVBoxLayout, QDialogButtonBox, QInputDialog,
    QWidget, QMessageBox, QHeaderView,
)

from app.ui.base import BaseModule
from app.core.api_client import ApiError

# 审批人来源：下拉选项（标签, 值）
APPROVER_TYPES = [
    ("角色", "role"), ("账号", "user"), ("权限等级", "permission_level"),
    ("权限码", "permission"), ("部门领导", "dept_leader"), ("创建人领导", "creator_leader"),
]
# 档案信息词缀字段：下拉选项（值, 标签）
AFFIX_FIELDS = [("", "无"), ("archive_no", "档号"), ("archive_name", "档案名称"),
                ("keyword", "关键词"), ("category_name", "分类名"), ("dept_name", "部门名")]
# 词缀匹配运算符：下拉选项（值, 标签）
AFFIX_OPS = [("", "无"), ("startswith", "前缀"), ("contains", "包含"),
             ("endswith", "后缀"), ("eq", "等于")]

# 流程状态中文映射
FLOW_STATUS_CN = {
    "pending": "审批中",
    "approved": "已通过",
    "rejected": "已驳回",
    "withdrawn": "已撤回",
}
# 节点状态中文映射
NODE_STATUS_CN = {
    "pending": "待审批",
    "approved": "已通过",
    "rejected": "已驳回",
    "skipped": "已跳过",
}
# 审批动作中文映射
ACTION_CN = {"agree": "通过", "reject": "驳回"}


class ApprovalConfigModule(BaseModule):
    """审批流配置主模块：以标签页组织「审批模板」与「审批流」两部分。"""
    title = "审批流配置"

    def __init__(self):
        super().__init__()
        self._build_ui()

    def _build_ui(self):
        # 根布局：竖向，放一个标签页控件
        root = QVBoxLayout(self)
        tabs = QTabWidget()

        # ---- 标签页一：审批模板 ----
        tpl_widget = QWidget()
        tpl_layout = QVBoxLayout(tpl_widget)
        # 模板工具条：新建 / 编辑 / 删除 / 刷新
        tpl_tools = self.build_toolbar(
            ("新建模板", self._on_create, None),
            ("编辑", self._on_edit, "ghost"),
            ("删除", self._on_delete, "danger"),
            ("刷新", self.refresh, None),
        )
        tpl_layout.addLayout(tpl_tools)
        self.table = QTableWidget()
        tpl_layout.addWidget(self.table, 1)
        tabs.addTab(tpl_widget, "审批模板")

        # ---- 标签页二：审批流 ----
        self.flow_widget = FlowListWidget(self)
        tabs.addTab(self.flow_widget, "审批流")

        root.addWidget(tabs, 1)

    # ===================== 审批模板相关 =====================
    def refresh(self):
        """加载并刷新「审批模板」表格。"""
        try:
            rows = self.api.approval_templates().get("data", [])
        except ApiError as e:
            self.handle_error(e, "加载模板")
            return
        self._rows = rows
        headers = ["名称", "业务", "优先级", "启用", "节点数", "触发条件"]
        self.setup_table(self.table, headers)
        self.table.setRowCount(len(rows))
        for r, t in enumerate(rows):
            cond = ""
            if t.get("trigger_field"):
                fld = dict(AFFIX_FIELDS).get(t["trigger_field"], t["trigger_field"])
                op = dict(AFFIX_OPS).get(t.get("trigger_op"), t.get("trigger_op"))
                cond = f"{fld} {op} {t.get('trigger_value')}"
            biz = "借阅" if t.get("biz_type") == "borrow" else "销毁"
            vals = [t.get("name"), biz, t.get("priority"),
                    "是" if t.get("enabled") else "否", len(t.get("nodes", [])), cond]
            for c, val in enumerate(vals):
                item = QTableWidgetItem("" if val is None else str(val))
                item.setData(Qt.UserRole, t)
                self.table.setItem(r, c, item)

    def _selected(self):
        """返回模板表格当前选中的整行数据。"""
        row = self.table.currentRow()
        if row < 0:
            return None
        item = self.table.item(row, 0)
        return item.data(Qt.UserRole) if item else None

    def _on_create(self):
        dlg = TemplateEditDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self.refresh()

    def _on_edit(self):
        t = self._selected()
        if not t:
            self.show_info("请先选择一个模板")
            return
        dlg = TemplateEditDialog(self, t)
        if dlg.exec() == QDialog.Accepted:
            self.refresh()

    def _on_delete(self):
        t = self._selected()
        if not t:
            self.show_info("请先选择一个模板")
            return
        if not self.ask_yesno(f"确认删除模板《{t['name']}》？"):
            return
        try:
            self.api.delete_approval_template(t["id"])
            self.show_info("已删除")
            self.refresh()
        except Exception as e:  # noqa
            self.handle_error(e, "删除")


class FlowListWidget(QWidget):
    """「审批流」标签页：列出系统已生成的流程实例，支持查看详情与审批。"""
    def __init__(self, module: BaseModule):
        super().__init__()
        self.module = module
        self.api = module.api
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)
        # 工具条：刷新 / 查看详情 / 通过 / 驳回
        tools = QHBoxLayout()
        btn_refresh = QPushButton("刷新")
        btn_detail = QPushButton("查看详情")
        btn_approve = QPushButton("通过")
        btn_reject = QPushButton("驳回")
        btn_approve.setObjectName("ghost")
        btn_reject.setObjectName("danger")
        btn_refresh.clicked.connect(self.refresh)
        btn_detail.clicked.connect(self._open_detail)
        btn_approve.clicked.connect(lambda: self._act("agree"))
        btn_reject.clicked.connect(lambda: self._act("reject"))
        tools.addWidget(btn_refresh)
        tools.addWidget(btn_detail)
        tools.addStretch(1)
        tools.addWidget(btn_approve)
        tools.addWidget(btn_reject)
        v.addLayout(tools)

        # 流程列表
        self.table = QTableWidget()
        v.addWidget(self.table, 1)

    def refresh(self):
        """加载并刷新审批流实例列表。"""
        try:
            rows = self.api.list_flows().get("data", [])
        except ApiError as e:
            self.module.handle_error(e, "加载审批流")
            return
        self._rows = rows
        headers = ["流程ID", "标题", "业务", "发起人", "状态", "创建时间"]
        self.module.setup_table(self.table, headers)
        self.table.setRowCount(len(rows))
        for r, f in enumerate(rows):
            biz = {"borrow": "借阅", "destroy": "销毁"}.get(f.get("biz_type"), f.get("biz_type"))
            status = FLOW_STATUS_CN.get(f.get("status"), f.get("status"))
            vals = [f.get("flow_id"), f.get("title"), biz,
                    f.get("applicant_name"), status, f.get("created_at")]
            for c, val in enumerate(vals):
                item = QTableWidgetItem("" if val is None else str(val))
                item.setData(Qt.UserRole, f)
                self.table.setItem(r, c, item)
        # 双击查看详情
        self.table.itemDoubleClicked.disconnect() if self.table.receivers(self.table.itemDoubleClicked) else None
        self.table.itemDoubleClicked.connect(lambda item: self._open_detail())

    def _selected(self):
        """返回流程列表当前选中的整行数据。"""
        row = self.table.currentRow()
        if row < 0:
            return None
        item = self.table.item(row, 0)
        return item.data(Qt.UserRole) if item else None

    def _open_detail(self):
        f = self._selected()
        if not f:
            self.module.show_info("请先选择一个审批流")
            return
        dlg = FlowDetailDialog(self.module, f["flow_id"])
        dlg.exec()

    def _act(self, action: str):
        """在列表页快捷通过/驳回当前选中的流程。"""
        f = self._selected()
        if not f:
            self.module.show_info("请先选择一个审批流")
            return
        opinion, ok = QInputDialog.getText(
            self, "审批意见",
            f"请输入{'通过' if action == 'agree' else '驳回'}意见（可选）：")
        if not ok:
            return
        try:
            self.api.act_flow(f["flow_id"], action, opinion if opinion else None)
            self.module.show_info("操作成功")
            self.refresh()
        except Exception as e:  # noqa
            self.module.handle_error(e, "审批")


class FlowDetailDialog(QDialog):
    """审批流详情：展示节点进度（高亮当前节点）与审批记录，支持通过/驳回。"""
    def __init__(self, module: BaseModule, flow_id: int):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.flow_id = flow_id
        self.setWindowTitle("审批流详情")
        self.resize(760, 580)
        self._build_ui()
        self.refresh()

    def _build_ui(self):
        v = QVBoxLayout(self)
        # 顶部概要信息
        self.info = QLabel()
        self.info.setWordWrap(True)
        v.addWidget(self.info)

        # 节点进度表
        v.addWidget(QLabel("审批节点进度"))
        self.node_table = QTableWidget()
        node_cols = ["顺序", "节点名称", "审批人", "状态", "已审批人"]
        self.module.setup_table(self.node_table, node_cols)
        v.addWidget(self.node_table, 1)

        # 审批记录表
        v.addWidget(QLabel("审批记录"))
        self.rec_table = QTableWidget()
        rec_cols = ["动作", "审批人", "意见", "时间"]
        self.module.setup_table(self.rec_table, rec_cols)
        v.addWidget(self.rec_table, 1)

        # 审批动作按钮
        btns = QHBoxLayout()
        btn_approve = QPushButton("通过")
        btn_reject = QPushButton("驳回")
        btn_reject.setObjectName("danger")
        btn_approve.clicked.connect(lambda: self._act("agree"))
        btn_reject.clicked.connect(lambda: self._act("reject"))
        btns.addStretch(1)
        btns.addWidget(btn_approve)
        btns.addWidget(btn_reject)
        v.addLayout(btns)

    def refresh(self):
        """加载并刷新流程详情。"""
        try:
            d = self.api.flow_detail(self.flow_id).get("data", {})
        except ApiError as e:
            self.module.handle_error(e, "加载详情")
            return
        self._detail = d
        # 概要信息
        biz = {"borrow": "借阅", "destroy": "销毁"}.get(d.get("biz_type"), d.get("biz_type"))
        status = FLOW_STATUS_CN.get(d.get("status"), d.get("status"))
        summary = d.get("archive_summary") or ""
        self.info.setText(
            f"标题：{d.get('title')}    业务：{biz}    状态：{status}    "
            f"发起人：{d.get('applicant_name', '')}\n摘要：{summary}"
        )
        # 节点：高亮第一个 pending 节点作为「当前节点」
        nodes = d.get("nodes", [])
        self.node_table.setRowCount(len(nodes))
        current_marked = False
        for r, n in enumerate(nodes):
            is_current = (not current_marked and n.get("node_status") == "pending")
            if is_current:
                current_marked = True
            status_cn = NODE_STATUS_CN.get(n.get("node_status"), n.get("node_status"))
            if is_current:
                status_cn = "● 当前待审"
            names = "、".join(n.get("approver_names", []) or [])
            acted = "、".join(n.get("acted_names", []) or [])
            vals = [n.get("node_order"), n.get("node_name"), names, status_cn, acted]
            for c, val in enumerate(vals):
                item = QTableWidgetItem("" if val is None else str(val))
                if is_current:
                    # 当前节点黄色高亮
                    item.setBackground(QColor("#FFF3CD"))
                    item.setForeground(QColor("#8A6D00"))
                self.node_table.setItem(r, c, item)
        # 审批记录
        recs = d.get("records", [])
        self.rec_table.setRowCount(len(recs))
        for r, rec in enumerate(recs):
            act_cn = ACTION_CN.get(rec.get("action"), rec.get("action"))
            vals = [act_cn, rec.get("operator"), rec.get("opinion"), rec.get("acted_at")]
            for c, val in enumerate(vals):
                item = QTableWidgetItem("" if val is None else str(val))
                self.rec_table.setItem(r, c, item)

    def _act(self, action: str):
        """对当前流程执行通过/驳回动作。"""
        opinion, ok = QInputDialog.getText(
            self, "审批意见",
            f"请输入{'通过' if action == 'agree' else '驳回'}意见（可选）：")
        if not ok:
            return
        try:
            self.api.act_flow(self.flow_id, action, opinion if opinion else None)
            self.module.show_info("操作成功")
            self.refresh()
        except Exception as e:  # noqa
            self.module.handle_error(e, "审批")


class TemplateEditDialog(QDialog):
    """审批模板编辑对话框：维护模板基础信息与多个审批节点。"""
    def __init__(self, module: BaseModule, template: dict = None):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.template = template
        self.setWindowTitle("新建审批模板" if not template else "编辑审批模板")
        self.resize(640, 560)
        self._build_ui()
        if template:
            self._fill(template)

    def _build_ui(self):
        v = QVBoxLayout(self)
        # 基础信息表单
        form = QFormLayout()
        self.name = QLineEdit()
        self.biz = QComboBox(); self.biz.addItems(["borrow", "destroy"])
        self.priority = QLineEdit(); self.priority.setText("0")
        self.enabled = QCheckBox("启用")
        self.enabled.setChecked(True)
        self.desc = QLineEdit()
        form.addRow("模板名称*：", self.name)
        form.addRow("业务类型*：", self.biz)
        form.addRow("优先级：", self.priority)
        form.addRow("状态：", self.enabled)
        form.addRow("说明：", self.desc)
        v.addLayout(form)

        # 触发条件（按档案词缀路由）
        trig = QFormLayout()
        self.t_field = QComboBox()
        for code, label in AFFIX_FIELDS:
            self.t_field.addItem(label, code)
        self.t_op = QComboBox()
        for code, label in AFFIX_OPS:
            self.t_op.addItem(label, code)
        self.t_value = QLineEdit()
        trig.addRow("触发字段：", self.t_field)
        trig.addRow("触发运算符：", self.t_op)
        trig.addRow("触发值：", self.t_value)
        v.addLayout(trig)

        v.addWidget(QLabel("审批节点（按顺序，可增删）："))
        node_bar = QHBoxLayout()
        add_btn = QPushButton("+ 添加节点"); add_btn.clicked.connect(self._add_node)
        del_btn = QPushButton("- 删除节点"); del_btn.clicked.connect(self._del_node)
        node_bar.addWidget(add_btn); node_bar.addWidget(del_btn); node_bar.addStretch(1)
        v.addLayout(node_bar)

        # 节点表格
        self.node_table = QTableWidget()
        cols = ["名称", "审批人来源", "取值", "条件字段", "条件运算", "条件值", "会签", "顺序"]
        self.node_table.setColumnCount(len(cols))
        self.node_table.setHorizontalHeaderLabels(cols)
        self.node_table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        v.addWidget(self.node_table, 1)

        # 底部确认/取消
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        v.addWidget(btns)

    def _fill(self, t):
        self.name.setText(t.get("name", ""))
        idx = self.biz.findText(t.get("biz_type", "borrow"))
        if idx >= 0:
            self.biz.setCurrentIndex(idx)
        self.priority.setText(str(t.get("priority", 0)))
        self.enabled.setChecked(bool(t.get("enabled", 1)))
        self.desc.setText(t.get("description") or "")
        self._set_combo(self.t_field, t.get("trigger_field"), AFFIX_FIELDS)
        self._set_combo(self.t_op, t.get("trigger_op"), AFFIX_OPS)
        self.t_value.setText(t.get("trigger_value") or "")
        for n in t.get("nodes", []):
            self._add_node(n)

    def _set_combo(self, combo, value, mapping):
        for i in range(combo.count()):
            if combo.itemData(i) == value:
                combo.setCurrentIndex(i)
                return

    def _add_node(self, node: dict = None):
        row = self.node_table.rowCount()
        self.node_table.insertRow(row)
        self._set_cell_combo(row, 1, APPROVER_TYPES,
                             node.get("approver_type") if node else "permission")
        # 节点表 helper 需要（标签, 编码），字段常量本身是（编码, 标签）。
        self._set_cell_combo(row, 3, [(label, code) for code, label in AFFIX_FIELDS],
                             node.get("cond_field") if node else "")
        self._set_cell_combo(row, 4, [(label, code) for code, label in AFFIX_OPS],
                             node.get("cond_op") if node else "")
        self.node_table.setItem(row, 0, QTableWidgetItem(node.get("node_name", f"节点{row+1}") if node else f"节点{row+1}"))
        self.node_table.setItem(row, 2, QTableWidgetItem(node.get("approver_value") or "" if node else ""))
        self.node_table.setItem(row, 5, QTableWidgetItem(node.get("cond_value") or "" if node else ""))
        cs = QComboBox(); cs.addItems(["否", "是"])
        cs.setCurrentIndex(1 if (node and node.get("is_countersign")) else 0)
        self.node_table.setCellWidget(row, 6, cs)
        self.node_table.setItem(row, 7, QTableWidgetItem(str(node.get("node_order", row+1) if node else row+1)))

    def _set_cell_combo(self, row, col, mapping, value):
        combo = QComboBox()
        for label, code in mapping:
            combo.addItem(label, code)
        for i in range(combo.count()):
            if combo.itemData(i) == value:
                combo.setCurrentIndex(i)
                break
        self.node_table.setCellWidget(row, col, combo)

    def _del_node(self):
        row = self.node_table.currentRow()
        if row >= 0:
            self.node_table.removeRow(row)

    def _collect_nodes(self):
        nodes = []
        for row in range(self.node_table.rowCount()):
            name = self.node_table.item(row, 0)
            value = self.node_table.item(row, 2)
            cond_v = self.node_table.item(row, 5)
            order = self.node_table.item(row, 7)
            atype = self.node_table.cellWidget(row, 1).currentData()
            cfield = self.node_table.cellWidget(row, 3).currentData()
            cop = self.node_table.cellWidget(row, 4).currentData()
            cs = self.node_table.cellWidget(row, 6).currentText()
            nodes.append({
                "node_order": int(order.text() or (row + 1)),
                "node_name": name.text().strip() or f"节点{row+1}",
                "approver_type": atype,
                "approver_value": (value.text().strip() or None) if value else None,
                "cond_field": cfield or None,
                "cond_op": cop or None,
                "cond_value": (cond_v.text().strip() or None) if cond_v else None,
                "is_countersign": 1 if cs == "是" else 0,
            })
        # 重新排序
        for i, n in enumerate(nodes, 1):
            n["node_order"] = i
        return nodes

    def _submit(self):
        if not self.name.text().strip():
            QMessageBox.warning(self, "提示", "请填写模板名称")
            return
        payload = {
            "name": self.name.text().strip(),
            "biz_type": self.biz.currentText(),
            "description": self.desc.text().strip() or None,
            "priority": int(self.priority.text() or 0),
            "enabled": 1 if self.enabled.isChecked() else 0,
            "trigger_field": self.t_field.currentData() or None,
            "trigger_op": self.t_op.currentData() or None,
            "trigger_value": self.t_value.text().strip() or None,
            "nodes": self._collect_nodes(),
        }
        try:
            if self.template:
                self.api.update_approval_template(self.template["id"], payload)
            else:
                self.api.create_approval_template(payload)
            QMessageBox.information(self, "提示", "保存成功")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}")
            return
        self.accept()
