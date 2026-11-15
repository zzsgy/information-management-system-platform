# -*- coding: utf-8 -*-
"""真实 PySide6 离屏冒烟；接口用固定数据替身，不访问网络或业务数据库。"""
import copy
import os
from pathlib import Path
import sys
import tempfile
import time
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")  # 不在用户桌面弹出测试窗口。
CLIENT = Path(__file__).resolve().parents[1]  # 客户端代码和测试输出所在目录。
sys.path.insert(0, str(CLIENT))

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QMessageBox
from app.core import config, session, tasks, prefs
from app.core.api_client import ApiClient
from app.ui.login import LoginDialog
from app.ui.main_window import MainWindow
from app.ui.modules.archive_manage import ArchiveDetailDialog, ArchiveFormDialog
from app.ui.modules.approval_config import TemplateEditDialog
from app.ui.modules.destroy_manage import DestroyExecDialog
from app.ui.modules.borrow_manage import BorrowApplyDialog
from app.ui.modules.damage_manage import DamageHandleDialog
from app.ui.archive_grants import ArchiveGrantDialog
from app.ui.preview import PreviewDialog

ARCHIVE = {"id": 1, "archive_no": "TEST-001", "archive_name": "离屏验收档案", "status": "archive",
           "dept_id": 1, "dept_name": "测试部门", "category_id": 1, "category_name": "技术档案",
           "security_level_id": 1, "security_name": "内部", "retention_id": 1, "retention_name": "长期",
           "copy_count": 1, "page_count": 2, "archive_date": None, "formation_date": None,
           "filing_date": None, "validity_months": None, "file_count": 1}  # 含空日期防误覆盖验证。
USER = {"id": 1, "username": "test-admin", "real_name": "测试管理员", "dept_id": 1,
        "roles": ["super_admin"], "permissions": ["*"], "access_token": "offline-token"}  # 纯测试身份。
CALLS = []  # 记录真实界面发出的路径和请求体，以核对接口兼容。
ERRORS = []  # 收集弹窗和异步槽异常，不能静默通过。


def request_stub(self, method, path, json_body=None, params=None, **options):
    """提供各现有界面所需的确定性响应，未知接口直接暴露为错误。"""
    CALLS.append((method, path, json_body, params))
    if path == "/api/auth/login":
        time.sleep(0.08)  # 让 Qt 定时器验证登录请求确实离开主线程。
        data = USER
    elif path == "/api/system/info":
        data = {**USER, "app_name": "信息管理系统", "version": "qt-smoke"}
    elif path == "/api/system/dicts":
        data = {"security_levels": [{"id": 1, "name": "内部"}], "retention_periods": [{"id": 1, "name": "长期"}]}
    elif path.startswith("/api/system/param/"):
        data = {"value": ""}
    elif path == "/api/system/archive-no-mode":
        data = {"mode": "auto"}
    elif path == "/api/system/numbering-rules/preview":
        data = {"archive_no": "TEST-002"}
    elif path == "/api/departments":
        data = [{"id": 1, "dept_code": "TEST", "dept_name": "测试部门", "sort_order": 0, "status": 1}]
    elif path == "/api/categories":
        data = [{"id": 1, "category_code": "TECH", "category_name": "技术档案", "archive_level": "file", "sort_order": 0, "status": 1}]
    elif path == "/api/archives/stats":
        data = {"total": 1, "by_status": [{"status": "archive", "label": "已归档", "count": 1}],
                "by_category": [{"name": "技术档案", "count": 1}], "by_security": [{"name": "内部", "count": 1}],
                "expiring_count": 0, "expiring_soon": []}
    elif path == "/api/archives/share-subjects":
        data = [{"id": 1, "name": "测试主体", "dept_id": 1}]
    elif path == "/api/archives/1/grants":
        data = [] if method == "GET" else {"id": 1}
    elif path == "/api/archives/1":
        data = ARCHIVE
    elif path == "/api/archives":
        data = {"total": 1, "items": [ARCHIVE]}
    elif path == "/api/files":
        data = [{"id": 1, "file_name": "验收.txt", "file_ext": ".txt", "file_size": 6, "version_no": 1, "is_current": 1}]
    elif path == "/api/users":
        data = {"total": 1, "items": [USER]}
    elif path == "/api/destroys/10":
        data = {"id": 10, "status": "approved", "archive_ids": [1], "archives": [ARCHIVE]}
    elif path in {"/api/notices", "/api/logs", "/api/borrows", "/api/destroys", "/api/damages"}:
        data = {"total": 0, "items": []}
    elif path in {"/api/roles", "/api/permissions", "/api/approval/templates", "/api/approval/flows", "/api/approval/my-tasks",
                   "/api/security/db-backups", "/api/system/dict-items", "/api/system/security-levels", "/api/system/retention-periods",
                   "/api/system/numbering-rules", "/api/archives/1/update-logs"}:
        data = []
    elif path == "/api/security/integrity/check":
        data = {"total": 1, "ok": 1, "failed": 0, "items": []}
    elif method != "GET":
        data = {"id": 1, "file": "offline.imsbackup.zip"}
    else:
        raise AssertionError(f"未配置的测试请求：{method} {path}")
    return {"code": 0, "message": "ok", "data": copy.deepcopy(data)}


def drain(app, predicate=None, timeout=5):
    """持续处理真实 Qt 队列，直到后台任务和指定条件都完成。"""
    deadline = time.monotonic() + timeout  # 防止网络任务/信号异常使测试无限等待。
    while tasks._active_tasks or (predicate and not predicate()):
        app.processEvents()
        if time.monotonic() >= deadline:
            raise AssertionError("Qt 后台任务未在预期时间内结束")
        time.sleep(0.005)
    app.processEvents()


def preview_stub(self, file_id, save_path):
    """模拟下载后的文本文件，实际 Qt 负责内嵌显示及关闭清理。"""
    Path(save_path).write_text("中文附件预览验收", encoding="utf-8")
    return save_path


def main():
    """构造全部主模块及关键对话框，验证真实 Qt 信号和操作结果。"""
    app = QApplication.instance() or QApplication([])  # 测试仅运行一个 Qt 应用。
    app.setQuitOnLastWindowClosed(False)
    old_hook = sys.excepthook  # Qt Python 槽异常必须计入测试失败。
    sys.excepthook = lambda kind, error, trace: ERRORS.append(f"{kind.__name__}: {error}")
    with patch.object(ApiClient, "_request", request_stub), patch.object(ApiClient, "preview_file", preview_stub), \
         patch.object(config, "load_config", return_value={"SERVER_URL": "http://localhost:8000"}), \
         patch.object(config, "save_server_url"), patch.object(prefs, "load_prefs", return_value={}), \
         patch.object(QMessageBox, "critical", side_effect=lambda owner, title, text: ERRORS.append(text)), \
         patch.object(QMessageBox, "information", return_value=QMessageBox.Ok), \
         patch.object(QMessageBox, "warning", return_value=QMessageBox.Ok):
        login = LoginDialog()  # 验证真实登录按钮在工作期间禁用，事件循环仍运行。
        login.user_edit.setText("test-admin")
        login.pwd_edit.setText("offline-password")
        ticks = []  # 主线程计时信号应在后台登录请求尚未完成时触发。
        QTimer.singleShot(20, lambda: ticks.append("responsive"))
        login._do_login()
        assert not login.login_btn.isEnabled()
        drain(app, lambda: login.result() == LoginDialog.Accepted)
        assert ticks and session.get_token() == "offline-token"
        window = MainWindow()  # 同时构造所有具备授权的实际模块。
        window.show()
        window._session_timer.stop()
        for index in range(window.nav.count()):
            window.nav.setCurrentRow(index)
            drain(app)
        archive_module = window.modules["档案管理"]  # 准备详情与编辑所需字典。
        archive_module.refresh()
        editor = ArchiveFormDialog(archive_module, 1)
        assert editor.name.text() == ARCHIVE["archive_name"]
        editor.name.setText("更新后的档案名称")
        editor._submit()
        update = next(call for call in reversed(CALLS) if call[:2] == ("PUT", "/api/archives/1"))
        assert update[2]["archive_date"] is None and "status" not in update[2]
        detail = ArchiveDetailDialog(archive_module, 1)
        assert detail.grants_btn.isVisibleTo(detail)
        assert detail.edit_btn.isEnabled() and not detail.archive_btn.isEnabled()
        template = TemplateEditDialog(window.modules["审批流配置"])
        template._add_node()
        template.node_table.cellWidget(0, 3).setCurrentIndex(1)
        template.node_table.cellWidget(0, 4).setCurrentIndex(1)
        assert template._collect_nodes()[0]["cond_field"] == "archive_no"
        assert template._collect_nodes()[0]["cond_op"] == "startswith"
        destroy = DestroyExecDialog(window.modules["销毁管理"], 10)
        assert destroy.list.count() == 1 and not destroy.list.selectedIndexes()
        destroy.list.item(0).setSelected(True)
        destroy.witness.setText("测试监销人")
        destroy.method.setText("测试方法")
        destroy._submit()
        assert next(call for call in reversed(CALLS) if call[1].endswith("/execute"))[2]["archive_ids"] == [1]
        borrow = BorrowApplyDialog(window.modules["借阅管理"])
        assert borrow.borrow_mode.count() == 3
        damage = DamageHandleDialog(window.modules["损坏管理"], 1, "resolved")
        assert damage.status.count() == 1 and damage.status.currentData() == "closed"
        grants = ArchiveGrantDialog(detail, detail.api, 1)
        drain(app)
        assert grants.subject.count() == 1 and grants.permission.count() == 10
        grants._add()
        drain(app)
        assert next(call for call in reversed(CALLS) if call[:2] == ("POST", "/api/archives/1/grants"))[2]["permission_code"] == "archive.read"
        preview = PreviewDialog(detail.api, 1, "验收.txt")
        preview.show()
        drain(app)
        assert Path(preview.temp_path).exists()
        preview.reject()
        assert not Path(preview.temp_path).exists()
        security = window.modules["数据安全运维"]
        security.run_check()
        drain(app)
        assert "正常 1 项" in security.check_summary.text()
        # 主界面在真实离屏平台渲染，若指定输出目录，可保留人工视觉核对图片。
        if len(sys.argv) > 1:
            output = Path(sys.argv[1])
            output.parent.mkdir(parents=True, exist_ok=True)
            window.nav.setCurrentRow(0)
            app.processEvents()
            assert window.grab().save(str(output))
        for widget in (preview, grants, damage, borrow, destroy, template, detail, editor, login, window):
            widget.close()
        drain(app)
        # 档案管理员的精确权限不能意外打开用户管理或安全运维模块。
        session.set_session("limited-token", {"id": 2, "username": "limited", "real_name": "档案管理员",
                                               "permissions": ["archive.read", "archive.admin"], "roles": ["archive_admin"]},
                            "http://localhost:8000")
        limited = MainWindow()
        limited._session_timer.stop()
        assert "系统管理" not in limited.modules and "数据安全运维" not in limited.modules
        assert "档案管理" in limited.modules and len(limited.modules) == 5
        limited.close()
        drain(app)
        assert not ERRORS, ERRORS
        tasks.shutdown_tasks()  # 等待原生线程真正退出，不能只依赖结果信号已被处理。
        print(f"QT_SMOKE_OK modules={len(window.modules)} calls={len(CALLS)} background_login_responsive=true")
    sys.excepthook = old_hook
    session.clear()


if __name__ == "__main__":
    main()
