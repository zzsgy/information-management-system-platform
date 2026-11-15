# -*- coding: utf-8 -*-
"""
app/ui/modules/sys_manage.py
============================
系统管理模块：按实际用户管理权限展示部门、用户、角色及系统信息。
跨部门范围、角色授权上限及修改权限均由服务端再次校验。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTabWidget, QTableWidget, QPushButton,
    QLabel, QLineEdit, QTextEdit, QComboBox, QDialog, QFormLayout,
    QDialogButtonBox, QListWidget, QListWidgetItem, QMessageBox,
)
from PySide6.QtCore import Qt

from app.ui.base import BaseModule
from app.core.api_client import ApiError
from app.core import session
from app.core.i18n import role_name, perm_name, perm_desc
from app.ui.modules.dict_manage import DictManageModule


class SysModule(BaseModule):
    title = "系统管理"

    def __init__(self):
        super().__init__()
        self.departments = []
        self.roles = []
        self.permissions = []
        self.users = []
        self._build_ui()

    def _build_ui(self):
        root = QVBoxLayout(self)
        tabs = QTabWidget()
        self.dept_tab = self._build_dept_tab()
        self.user_tab = self._build_user_tab()
        self.role_tab = self._build_role_tab()
        self.info_tab = self._build_info_tab()
        self.dict_tab = DictManageModule()
        tabs.addTab(self.dept_tab, "部门管理")
        tabs.addTab(self.user_tab, "用户管理")
        tabs.addTab(self.role_tab, "角色权限")
        tabs.addTab(self.info_tab, "系统信息")
        tabs.addTab(self.dict_tab, "字典管理")
        tabs.setTabEnabled(4, session.has_perm("dict.manage"))
        root.addWidget(tabs, 1)

    # ---- 部门 ----
    def _build_dept_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        actions = [("刷新", self._refresh_dept, None)]  # 目录读取不代表可改组织树。
        if session.has_perm("user.write") and session.has_perm("user.manage.all"):
            actions.insert(0, ("新建部门", self._on_dept_create, None))
        v.addLayout(self.build_toolbar(*actions))
        self.d_table = QTableWidget(); v.addWidget(self.d_table, 1)
        return w

    def _refresh_dept(self):
        try:
            rows = self.api.get("/api/departments").get("data", [])
        except ApiError as e:
            self.handle_error(e, "加载部门"); return
        self.departments = rows
        self.fill_table(self.d_table, rows, ["编码", "名称", "排序", "状态"],
                        mapping=["dept_code", "dept_name", "sort_order", "status"])

    def _on_dept_create(self):
        dlg = DeptFormDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self._refresh_dept()

    # ---- 用户 ----
    def _build_user_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        actions = [("刷新", self._refresh_user, None)]  # 写入和禁用都需要精确用户写权限。
        if session.has_perm("user.write"):
            actions[:0] = [("新建用户", self._on_user_create, None), ("禁用", self._on_user_disable, "danger")]
        v.addLayout(self.build_toolbar(*actions))
        self.u_table = QTableWidget(); v.addWidget(self.u_table, 1)
        return w

    def _refresh_user(self):
        try:
            res = self.api.get("/api/users", params={"page": 1, "page_size": 200}).get("data", {})
            rows = res.get("items", [])
            self.roles = self.api.get("/api/roles").get("data", [])
            self.permissions = self.api.get("/api/permissions").get("data", [])
        except ApiError as e:
            self.handle_error(e, "加载用户"); return
        self.users = rows
        dept_map = {d["id"]: d["dept_name"] for d in self.departments}
        self.fill_table(self.u_table, rows,
                        ["ID", "账号", "姓名", "部门", "状态", "手机"],
                        mapping=["id", "username", "real_name",
                                 lambda r: dept_map.get(r.get("dept_id"), r.get("dept_id")),
                                 "account_status", "phone"])

    def _on_user_create(self):
        dlg = UserFormDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self._refresh_user()

    def _on_user_disable(self):
        row = self.selected_row(self.u_table)
        if not row:
            self.show_info("请先选择一名用户"); return
        if not self.ask_yesno(f"确认禁用用户 {row['username']}？"):
            return
        try:
            self.api.post(f"/api/users/{row['id']}/disable")
            self.show_info("用户已禁用"); self._refresh_user()
        except Exception as e:  # noqa
            self.handle_error(e, "禁用用户")

    # ---- 角色权限 ----
    def _build_role_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        actions = [("刷新", self._refresh_role, None)]  # 只读账号不能从角色页发起写操作。
        if session.has_perm("user.write"):
            actions.insert(0, ("新建角色", self._on_role_create, None))
        v.addLayout(self.build_toolbar(*actions))
        self.r_table = QTableWidget(); v.addWidget(self.r_table, 1)
        return w

    def _refresh_role(self):
        try:
            rows = self.api.get("/api/roles").get("data", [])
        except ApiError as e:
            self.handle_error(e, "加载角色"); return
        self.fill_table(self.r_table, rows, ["编码", "名称", "级别", "状态"],
                        mapping=["role_code", "role_name", "role_level", "status"])

    def _on_role_create(self):
        dlg = RoleFormDialog(self)
        if dlg.exec() == QDialog.Accepted:
            self._refresh_role()

    # ---- 系统信息 ----
    def _build_info_tab(self):
        w = QWidget(); v = QVBoxLayout(w)
        v.addLayout(self.build_toolbar(("刷新", self._refresh_info, None)))
        self.info_text = QTextEdit(); self.info_text.setReadOnly(True)
        v.addWidget(self.info_text, 1)
        return w

    def _refresh_info(self):
        try:
            info = self.api.system_info().get("data", {})
        except ApiError as e:
            self.handle_error(e, "加载系统信息"); return
        roles = "、".join(role_name(r) for r in info.get("roles", []))
        perms = info.get("permissions", [])
        perm_lines = "\n".join(f"  · {perm_name(p)}：{perm_desc(p)}" for p in perms)
        lines = [
            f"应用：{info.get('app_name')}",
            f"版本：{info.get('version')}",
            f"当前用户：{info.get('real_name')}({info.get('username')})",
            f"角色：{roles}",
            f"权限（共 {len(perms)} 项）：",
            perm_lines or "  ·（无）",
        ]
        self.info_text.setPlainText("\n".join(lines))

    def refresh(self):
        self._refresh_dept(); self._refresh_user(); self._refresh_role(); self._refresh_info()

    def showEvent(self, event):
        if self.d_table.rowCount() == 0:
            self._refresh_dept()
        if self.u_table.rowCount() == 0:
            self._refresh_user()
        if self.r_table.rowCount() == 0:
            self._refresh_role()
        if not self.info_text.toPlainText():
            self._refresh_info()
        super().showEvent(event)


class DeptFormDialog(QDialog):
    def __init__(self, module: SysModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.setWindowTitle("新建部门")
        self.resize(360, 240)
        form = QFormLayout(self)
        self.code = QLineEdit(); self.name = QLineEdit()
        self.sort = QLineEdit(); self.sort.setText("0")
        form.addRow("部门编码*：", self.code)
        form.addRow("部门名称*：", self.name)
        form.addRow("排序：", self.sort)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        form.addRow(btns)

    def _submit(self):
        if not self.code.text().strip() or not self.name.text().strip():
            QMessageBox.warning(self, "提示", "请填写编码与名称"); return
        payload = {"dept_code": self.code.text().strip(),
                   "dept_name": self.name.text().strip(),
                   "sort_order": int(self.sort.text() or 0), "status": 1}
        try:
            self.api.post("/api/departments", payload)
            QMessageBox.information(self, "提示", "部门创建成功")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}"); return
        self.accept()


class UserFormDialog(QDialog):
    def __init__(self, module: SysModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.setWindowTitle("新建用户")
        self.resize(400, 360)
        form = QFormLayout(self)
        self.username = QLineEdit()
        self.realname = QLineEdit()
        self.dept = QComboBox()
        for d in self.module.departments:
            self.dept.addItem(d["dept_name"], d["id"])
        self.password = QLineEdit()  # 由管理员填写独立初始密码，不再预填通用弱密码。
        self.password.setEchoMode(QLineEdit.Password)
        self.password.setPlaceholderText("12～128字符")
        self.roles = QListWidget(); self.roles.setSelectionMode(QListWidget.MultiSelection)
        for r in self.module.roles:
            item = QListWidgetItem(f"{r['role_name']}({r['role_code']})")
            item.setData(Qt.UserRole, r["id"])
            self.roles.addItem(item)
        form.addRow("登录账号*：", self.username)
        form.addRow("姓名*：", self.realname)
        form.addRow("所属部门*：", self.dept)
        form.addRow("初始密码：", self.password)
        form.addRow("分配角色：", self.roles)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        form.addRow(btns)

    def _submit(self):
        if not self.username.text().strip() or not self.realname.text().strip():
            QMessageBox.warning(self, "提示", "请填写账号与姓名"); return
        if not 12 <= len(self.password.text()) <= 128:
            QMessageBox.warning(self, "提示", "请填写12～128字符的初始密码"); return
        role_ids = [self.roles.item(i).data(Qt.UserRole) for i in range(self.roles.count())
                    if self.roles.item(i).isSelected()]
        payload = {"username": self.username.text().strip(),
                   "real_name": self.realname.text().strip(),
                   "dept_id": self.dept.currentData(),
                   "password": self.password.text(),
                   "role_ids": role_ids}
        try:
            self.api.post("/api/users", payload)
            QMessageBox.information(self, "提示", "用户创建成功")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}"); return
        self.accept()


class RoleFormDialog(QDialog):
    def __init__(self, module: SysModule):
        super().__init__(module)
        self.module = module
        self.api = module.api
        self.setWindowTitle("新建角色")
        self.resize(400, 420)
        v = QVBoxLayout(self)
        form = QFormLayout()
        self.code = QLineEdit(); self.name = QLineEdit()
        form.addRow("角色编码*：", self.code)
        form.addRow("角色名称*：", self.name)
        v.addLayout(form)
        v.addWidget(QLabel("分配权限（可多选）："))
        self.perms = QListWidget(); self.perms.setSelectionMode(QListWidget.MultiSelection)
        for p in self.module.permissions:
            # 显示「权限中文名（编码）」+ 换行「说明」，让管理员建角色时看得懂每项含义
            desc = p.get("description", "") or ""
            text = f"{p['perm_name']}（{p['perm_code']}）"
            if desc:
                text += f"\n    {desc}"
            item = QListWidgetItem(text)
            item.setData(Qt.UserRole, p["perm_code"])
            self.perms.addItem(item)
        v.addWidget(self.perms, 1)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.setCenterButtons(True)
        btns.accepted.connect(self._submit)
        btns.rejected.connect(self.reject)
        v.addWidget(btns)

    def _submit(self):
        if not self.code.text().strip() or not self.name.text().strip():
            QMessageBox.warning(self, "提示", "请填写编码与名称"); return
        codes = [self.perms.item(i).data(Qt.UserRole) for i in range(self.perms.count())
                 if self.perms.item(i).isSelected()]
        payload = {"role_code": self.code.text().strip(),
                   "role_name": self.name.text().strip(),
                   "role_level": 5, "status": 1, "permission_codes": codes}
        try:
            self.api.post("/api/roles", payload)
            QMessageBox.information(self, "提示", "角色创建成功")
        except Exception as e:  # noqa
            QMessageBox.critical(self, "错误", f"保存失败：{e}"); return
        self.accept()
