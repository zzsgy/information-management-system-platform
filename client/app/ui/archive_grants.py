# -*- coding: utf-8 -*-
"""指定档案的人员/部门共享授权；各操作独立授权，服务端强制检查管理范围。"""
from PySide6.QtCore import Qt, QDateTime
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QFormLayout, QLabel, QComboBox,
    QCheckBox, QDateTimeEdit, QPushButton, QTableWidget, QTableWidgetItem,
    QMessageBox,
)
from app.core.tasks import run_task
from app.core import session


class ArchiveGrantDialog(QDialog):
    """为单份档案授予或撤销明确操作权限，不扩大全公司默认可见范围。"""

    def __init__(self, parent, api, archive_id):
        """记录当前档案标识，独立加载受限共享主体和已有授权。"""
        super().__init__(parent)
        self.api = api  # 使用已登录客户端，服务端仍逐条检查权限。
        self.archive_id = archive_id  # 此窗口只能操作这一份档案。
        self.setWindowTitle("档案授权共享")
        self.resize(750, 460)
        layout = QVBoxLayout(self)  # 共享说明、授权表单、现有授权表格。
        note = QLabel("各操作分别授权；共享扩展指定档案的数据范围，接收人仍须具有相应功能权限。")
        note.setWordWrap(True)
        layout.addWidget(note)
        form = QFormLayout()  # 不修改主界面布局，独立完成最小共享配置。
        self.subject_type = QComboBox()  # 选择授权给具体人员或整个部门。
        self.subject_type.addItem("指定人员", "user")
        self.subject_type.addItem("指定部门", "dept")
        self.subject = QComboBox()  # 受限接口只提供 ID 与显示名，不读取用户管理数据。
        self.permission = QComboBox()  # 不将档案读取隐式扩展成文件下载。
        for code, label in (("archive.read", "查看档案"), ("archive.write", "编辑档案"),
                            ("file.preview", "预览文件"), ("file.download", "下载文件"),
                            ("file.upload", "上传文件"), ("borrow.apply", "申请借阅"),
                            ("borrow.approve", "借阅审批/办理"), ("destroy.apply", "申请销毁"),
                            ("destroy.approve", "审批销毁"), ("destroy.execute", "执行销毁")):
            if session.has_perm(code):
                self.permission.addItem(label, code)
        self.expires_enabled = QCheckBox("设置授权到期时间（本机时间）")  # 不勾选代表持续到人工撤销。
        self.expires = QDateTimeEdit(QDateTime.currentDateTime().addDays(7))  # 本地时间供用户选择，提交时转换为 UTC。
        self.expires.setDisplayFormat("yyyy-MM-dd HH:mm")
        self.expires.setCalendarPopup(True)
        self.expires.setEnabled(False)
        self.expires_enabled.toggled.connect(self.expires.setEnabled)
        form.addRow("授权对象类型：", self.subject_type)
        form.addRow("授权对象：", self.subject)
        form.addRow("操作权限：", self.permission)
        form.addRow(self.expires_enabled, self.expires)
        layout.addLayout(form)
        buttons = QHBoxLayout()  # 授权、撤销、刷新和关闭按钮。
        self.add_btn = QPushButton("添加授权")
        self.remove_btn = QPushButton("撤销选中授权")
        self.refresh_btn = QPushButton("刷新")
        self.add_btn.clicked.connect(self._add)
        self.remove_btn.clicked.connect(self._remove)
        self.refresh_btn.clicked.connect(self._reload_grants)
        for button in (self.add_btn, self.remove_btn, self.refresh_btn):
            buttons.addWidget(button)
        buttons.addStretch(1)
        close = QPushButton("关闭")  # 退出仅关闭配置，不撤销任何授权。
        close.clicked.connect(self.reject)
        buttons.addWidget(close)
        layout.addLayout(buttons)
        self.table = QTableWidget()  # 显示服务端实际保存的授权记录。
        self.table.setColumnCount(5)
        self.table.setHorizontalHeaderLabels(["授权ID", "对象类型", "对象ID", "权限", "到期时间"])
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.horizontalHeader().setStretchLastSection(True)
        layout.addWidget(self.table, 1)
        self.subject_type.currentIndexChanged.connect(self._load_subjects)
        self._load_subjects()
        self._reload_grants()

    def _error(self, error):
        """权限不足、范围变化和连接失败都展示真实服务端消息。"""
        QMessageBox.critical(self, "共享授权失败", str(error))

    def _load_subjects(self):
        """只读取可用于授权的最小主体信息，不借用系统用户管理权限。"""
        kind = self.subject_type.currentData()  # 固定发起查询时的主体类型。
        self.subject.clear()
        run_task(self, lambda: self.api.get("/api/archives/share-subjects", params={"type": kind}),
                 self._subjects_loaded, self._error,
                 controls=(self.subject_type, self.subject, self.add_btn))

    def _subjects_loaded(self, response):
        """把受限接口返回的主体 ID 与名称填入选择框。"""
        rows = response.get("data") or []  # 受限主体清单，不包含密码、会话等信息。
        for row in rows:
            self.subject.addItem(f"{row.get('name') or row.get('dept_name') or row.get('username')}（{row['id']}）", row["id"])

    def _reload_grants(self):
        """后台加载本档案所有已配置授权，失败不伪造空结果。"""
        run_task(self, lambda: self.api.get(f"/api/archives/{self.archive_id}/grants"),
                 self._grants_loaded, self._error, controls=(self.refresh_btn,))

    def _grants_loaded(self, response):
        """在主线程展示授权并绑定完整行数据，撤销只提交选中记录的 ID。"""
        rows = response.get("data") or []  # 由服务端鉴权后返回的当前授权集合。
        self.table.setRowCount(len(rows))
        for index, row in enumerate(rows):
            values = [row["id"], "人员" if row.get("grantee_type") == "user" else "部门",
                      row.get("grantee_id"), row.get("permission_code"), row.get("expires_at") or "人工撤销前有效"]
            for column, value in enumerate(values):
                item = QTableWidgetItem(str(value))  # 不可编辑单元格，实际变更须调用授权接口。
                item.setData(Qt.UserRole, row)
                self.table.setItem(index, column, item)

    def _add(self):
        """捕获单一对象、单一权限及可选期限，再提交后端验证。"""
        if self.subject.currentData() is None:
            QMessageBox.warning(self, "提示", "请选择一个授权对象")
            return
        if self.permission.currentData() is None:
            QMessageBox.warning(self, "提示", "当前账号没有可授予的操作权限")
            return
        expiry = None  # 空值表示直到人工撤销；有值时必须是将来的 UTC 时间。
        if self.expires_enabled.isChecked():
            if self.expires.dateTime() <= QDateTime.currentDateTimeUtc():
                QMessageBox.warning(self, "提示", "授权到期时间必须晚于当前时间")
                return
            expiry = self.expires.dateTime().toUTC().toString(Qt.ISODate)
        payload = {"grantee_type": self.subject_type.currentData(), "grantee_id": self.subject.currentData(),
                   "permission_code": self.permission.currentData(), "expires_at": expiry}
        run_task(self, lambda: self.api.post(f"/api/archives/{self.archive_id}/grants", payload),
                 self._saved, self._error, controls=(self.add_btn, self.remove_btn))

    def _saved(self, _response):
        """修改成功后重新读取权限记录，不在本地猜测 ID 或有效期。"""
        self._reload_grants()

    def _remove(self):
        """经操作确认后撤销单条授权，保留其他授权。"""
        row_index = self.table.currentRow()  # 当前选择的授权行。
        item = self.table.item(row_index, 0) if row_index >= 0 else None  # 第一列保存完整记录。
        if item is None:
            QMessageBox.warning(self, "提示", "请先选择要撤销的授权")
            return
        row = item.data(Qt.UserRole)  # 后端创建的授权 ID，不由界面重新构造。
        if QMessageBox.question(self, "撤销授权", "确认撤销这条共享权限？", QMessageBox.Yes | QMessageBox.No,
                                QMessageBox.No) != QMessageBox.Yes:
            return
        grant_id = row["id"]  # 固定被撤销的记录，避免请求途中表格选择变化。
        run_task(self, lambda: self.api.delete(f"/api/archives/{self.archive_id}/grants/{grant_id}"),
                 self._saved, self._error, controls=(self.add_btn, self.remove_btn))
