# -*- coding: utf-8 -*-
"""
app/ui/flow_dialog.py
======================
审批流程详情对话框（只读进度 + 当前节点审批动作）。
供「我的待办」与借阅/销毁界面的「查看进度」复用。
"""
from PySide6.QtWidgets import (
    QDialog, QHBoxLayout, QLabel, QListWidget, QListWidgetItem, QTextEdit,
    QPushButton, QVBoxLayout, QInputDialog, QMessageBox,
)

from app.core import session
from app.core.api_client import ApiError


class FlowDetailDialog(QDialog):
    """展示审批流程节点进度与记录，并允许当前审批人操作。"""

    def __init__(self, api, flow_id: int = None, biz_type: str = None, biz_id: int = None):
        super().__init__()
        self.api = api
        self.flow_id = flow_id
        self.biz_type = biz_type
        self.biz_id = biz_id
        self.detail = None
        self.setWindowTitle("审批流程详情")
        self.resize(560, 520)
        self._build_ui()
        self._load()

    def _build_ui(self):
        v = QVBoxLayout(self)
        self.title_lbl = QLabel("")
        v.addWidget(self.title_lbl)
        self.status_lbl = QLabel("")
        v.addWidget(self.status_lbl)

        v.addWidget(QLabel("审批节点："))
        self.node_list = QListWidget()
        v.addWidget(self.node_list, 1)

        v.addWidget(QLabel("审批记录："))
        self.record_edit = QTextEdit()
        self.record_edit.setReadOnly(True)
        self.record_edit.setFixedHeight(120)
        v.addWidget(self.record_edit)

        bar = QHBoxLayout()
        self.pass_btn = QPushButton("通过")
        self.reject_btn = QPushButton("驳回")
        self.refresh_btn = QPushButton("刷新")
        self.close_btn = QPushButton("关闭")
        self.pass_btn.clicked.connect(lambda: self._act("agree"))
        self.reject_btn.clicked.connect(lambda: self._act("reject"))
        self.refresh_btn.clicked.connect(self._load)
        self.close_btn.clicked.connect(self.reject)
        bar.addWidget(self.pass_btn)
        bar.addWidget(self.reject_btn)
        bar.addWidget(self.refresh_btn)
        bar.addStretch(1)
        bar.addWidget(self.close_btn)
        v.addLayout(bar)

    def _load(self):
        try:
            if self.flow_id:
                res = self.api.flow_detail(self.flow_id)
            elif self.biz_type and self.biz_id:
                res = self.api.flow_by_biz(self.biz_type, self.biz_id)
            else:
                QMessageBox.warning(self, "提示", "缺少流程标识")
                self.reject()
                return
            self.detail = res.get("data")
        except ApiError as e:
            QMessageBox.critical(self, "错误", f"加载失败：{e}")
            self.reject()
            return
        if not self.detail:
            QMessageBox.information(self, "提示", "暂无审批流程")
            self.reject()
            return
        self._render()

    def _can_act(self) -> bool:
        """按真实功能权限和审批快照判断按钮，不凭超级管理员名称绕过指派。"""
        d = self.detail
        if d.get("status") != "pending":
            return False
        # 找当前 pending 节点
        cur = next((n for n in d["nodes"] if n["node_status"] == "pending"), None)
        if not cur:
            return False
        permission = "borrow.approve" if d.get("biz_type") == "borrow" else "destroy.approve"  # 对应业务审批动作。
        if not session.has_perm(permission):
            return False
        uid = str((session.get_user() or {}).get("id", ""))
        ids = [x for x in (cur.get("approver_user_ids") or "").split(",") if x]
        return uid in ids

    def _render(self):
        d = self.detail
        self.title_lbl.setText(f"标题：{d.get('title')}　申请人：{d.get('applicant_name')}")
        self.status_lbl.setText(f"状态：{d.get('status')}")
        self.node_list.clear()
        for n in d.get("nodes", []):
            names = "、".join(n.get("approver_names", [])) or "（无审批人，自动跳过）"
            acted = "、".join(n.get("acted_names", []))
            text = (f"[{n['node_status']}] 第{n['node_order']}节点 {n['node_name']}　"
                    f"审批人：{names}")
            if acted:
                text += f"　已审：{acted}"
            if n.get("is_countersign"):
                text += "（会签）"
            item = QListWidgetItem(text)
            self.node_list.addItem(item)
        recs = d.get("records", [])
        if recs:
            lines = []
            for r in recs:
                lines.append(f"{r.get('acted_at','')} {r.get('operator')} "
                             f"{'通过' if r.get('action')=='agree' else '驳回'}"
                             f"：{r.get('opinion') or ''}")
            self.record_edit.setPlainText("\n".join(lines))
        else:
            self.record_edit.setPlainText("（暂无记录）")
        can = self._can_act()
        self.pass_btn.setEnabled(can)
        self.reject_btn.setEnabled(can)

    def _act(self, action: str):
        d = self.detail
        opinion, ok = QInputDialog.getText(self, "审批意见",
                                           "审批意见：" + ("（通过）" if action == "agree" else "（驳回）"))
        if not ok:
            return
        try:
            self.api.act_flow(d["flow_id"], action, opinion or None)
            QMessageBox.information(self, "提示", "审批完成")
            self._load()
            # 通知外部刷新（如待办列表）
            if hasattr(self.parent(), "refresh"):
                try:
                    self.parent().refresh()
                except Exception:  # noqa
                    pass
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"审批失败：{e}")
