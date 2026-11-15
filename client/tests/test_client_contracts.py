# -*- coding: utf-8 -*-
"""标准库离线回归：验证权限、会话、下载、配置和实际 UI 方法，不访问网络。"""
import ast
import importlib
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

CLIENT = Path(__file__).resolve().parents[1]  # 客户端源目录，便于从仓库根目录运行。
sys.path.insert(0, str(CLIENT))


def _dotenv_values(path):
    """测试桩只解析本测试使用的 KEY=VALUE，不依赖 python-dotenv 安装。"""
    return dict(line.split("=", 1) for line in Path(path).read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#") and "=" in line)


requests_stub = types.ModuleType("requests")  # 阻止测试执行实际网络请求。
requests_stub.RequestException = type("RequestException", (Exception,), {})
requests_stub.Session = Mock
dotenv_stub = types.ModuleType("dotenv")  # 配置读取测试只需环境文件字典能力。
dotenv_stub.dotenv_values = _dotenv_values
with patch.dict(sys.modules, {"requests": requests_stub, "dotenv": dotenv_stub}):
    from app.core import config, session
    from app.core.api_client import ApiClient, ApiError
from app.core.workflow_rules import (
    approved_destroy_archives, destroy_execution_ids, can_checkout_borrow, can_return_borrow,
)


class FakeResponse:
    """模拟服务端信封或流，用于异常、关闭连接及文件替换验证。"""

    def __init__(self, status=200, body=None, chunks=(), fail=False):
        """保存状态、信封、下载分块和人为中断标记。"""
        self.status_code = status  # HTTP 状态，与业务 code 分开。
        self.body = body if body is not None else {"code": 0, "message": "ok", "data": {}}
        self.chunks = chunks  # 下载时写入的字节块。
        self.fail = fail  # 模拟已写一部分后连接中断。
        self.closed = False  # 检查成功、失败路径都关闭响应。

    def json(self):
        """返回模拟信封，也支持模拟 HTML 等非 JSON 响应。"""
        if isinstance(self.body, Exception):
            raise self.body
        return self.body

    def close(self):
        """记录连接已释放。"""
        self.closed = True

    def iter_content(self, chunk_size):
        """先产出分块，再按测试配置模拟中断。"""
        yield from self.chunks
        if self.fail:
            raise OSError("模拟下载中断")


def extracted_method(relative_file, class_name, method_name, namespace=None):
    """执行真实源码中的单个 UI 方法，用控件桩验证行为而无需安装 Qt。"""
    source = CLIENT.joinpath(relative_file).read_text(encoding="utf-8-sig")  # 真实被测源码。
    tree = ast.parse(source)  # 只摘取方法，避免导入 PySide6 与全部窗口。
    klass = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == class_name)
    method = next(node for node in klass.body if isinstance(node, ast.FunctionDef) and node.name == method_name)
    scope = dict(namespace or {})  # 注入明确的 API 与控件测试桩。
    exec(compile(ast.Module(body=[method], type_ignores=[]), str(CLIENT / relative_file), "exec"), scope)
    return scope[method_name]


class SessionTests(unittest.TestCase):
    """实际权限与并发会话保护回归。"""

    def tearDown(self):
        """每例清理内存会话，防止测试相互授权。"""
        session.clear()

    def test_archive_admin_is_not_system_wildcard(self):
        """档案管理员不能凭一项权限获得用户管理、销毁或运维权限。"""
        session.set_session("opaque", {"permissions": ["archive.admin"]}, "http://localhost")
        self.assertTrue(session.has_perm("archive.admin"))
        for code in ("user.write", "destroy.execute", "security.manage"):
            self.assertFalse(session.has_perm(code))

    def test_explicit_wildcard_and_permission_copy(self):
        """只有显式星号可全权，调用者修改权限副本不影响会话。"""
        session.set_session("opaque", {"permissions": ["*"]}, "http://localhost")
        session.get_permissions().clear()
        self.assertTrue(session.has_perm("security.manage"))

    def test_background_identity_does_not_change_with_new_login(self):
        """旧任务始终使用旧令牌，且其 401 不清除后来新登录。"""
        session.set_session("old", {}, "http://localhost")
        with session.request_scope("old"):
            session.set_session("new", {}, "http://localhost")
            self.assertEqual(session.get_token(), "old")
            session.clear(expected_token="old")
        self.assertEqual(session.get_token(), "new")


class ApiTests(unittest.TestCase):
    """统一错误信封、身份失效及原子下载的回归。"""

    def setUp(self):
        """注入假连接池，全部请求在内存返回。"""
        session.set_session("opaque", {}, "http://localhost:8000")
        self.api = ApiClient()
        self.http = Mock()  # 单测试线程的 requests.Session 替身。
        self.api._local.http = self.http

    def tearDown(self):
        """清理认证状态。"""
        session.clear()

    def test_http_200_business_failure_is_not_success(self):
        """HTTP 成功但业务拒绝时不能弹出上传或销毁成功。"""
        self.http.request.return_value = FakeResponse(body={"code": 400, "message": "拒绝执行"})
        with self.assertRaisesRegex(ApiError, "拒绝执行"):
            self.api.post("/api/destroys/1/execute", {})

    def test_invalid_json_is_not_silent_success(self):
        """HTML 登录页或网关异常不能被当成成功数据。"""
        response = FakeResponse(body=ValueError("not json"))
        self.http.request.return_value = response
        with self.assertRaises(ApiError):
            self.api.get("/api/archives")
        self.assertTrue(response.closed)

    def test_401_clears_only_failed_session(self):
        """认证过期立即清理旧身份。"""
        self.http.request.return_value = FakeResponse(401, {"code": 401, "message": "会话失效"})
        with self.assertRaises(ApiError):
            self.api.get("/api/auth/me")
        self.assertIsNone(session.get_token())

    def test_cross_host_and_redirect_are_refused(self):
        """携带令牌的接口不允许改到其他站点，也不自动追随重定向。"""
        with self.assertRaises(ApiError):
            self.api.get("https://untrusted.example/collect")
        self.http.request.assert_not_called()
        self.http.request.return_value = FakeResponse(302)
        with self.assertRaises(ApiError):
            self.api.get("/api/auth/me")
        self.assertFalse(self.http.request.call_args.kwargs["allow_redirects"])

    def test_download_failure_preserves_existing_target(self):
        """下载半途失败不会覆盖用户已有文件，也不会留下半成品。"""
        response = FakeResponse(chunks=(b"partial",), fail=True)
        self.http.request.return_value = response
        with tempfile.TemporaryDirectory(dir=CLIENT / "tests") as directory:
            target = Path(directory) / "existing.txt"
            target.write_bytes(b"original")
            with self.assertRaises(OSError):
                self.api.download_archive_file(7, str(target))
            self.assertEqual(target.read_bytes(), b"original")
            self.assertEqual([path.name for path in Path(directory).iterdir()], ["existing.txt"])
        self.assertTrue(response.closed)

    def test_preview_uses_real_preview_endpoint(self):
        """文件预览便捷方法调用真实存在的受权限控制的 preview 路由。"""
        self.http.request.return_value = FakeResponse(chunks=(b"complete",))
        with tempfile.TemporaryDirectory(dir=CLIENT / "tests") as directory:
            target = Path(directory) / "preview.txt"
            self.api.preview_file(8, str(target))
            self.assertEqual(target.read_bytes(), b"complete")
        self.assertEqual(self.http.request.call_args.args[1], "http://localhost:8000/api/files/8/preview")

    def test_login_returns_permissions_without_mutating_session(self):
        """后台认证只返回数据，关闭或失败的登录窗口不能偷偷建立会话。"""
        session.clear()
        self.http.request.side_effect = [
            FakeResponse(body={"code": 0, "data": {"access_token": "new-token", "roles": ["viewer"]}}),
            FakeResponse(body={"code": 0, "data": {"permissions": ["archive.read"], "roles": ["viewer"]}}),
        ]
        data = self.api.login("viewer", "long-password")
        self.assertEqual(data["permissions"], ["archive.read"])
        self.assertIsNone(session.get_token())
        self.assertEqual(self.http.request.call_args.kwargs["headers"]["Authorization"], "Bearer new-token")

    def test_login_cannot_ignore_revoked_token(self):
        """即使登录响应带权限，权限补查返回 401 也不能当作登录成功。"""
        session.clear()
        self.http.request.side_effect = [
            FakeResponse(body={"code": 0, "data": {"access_token": "revoked", "permissions": ["*"]}}),
            FakeResponse(401, {"code": 401, "message": "已撤销"}),
        ]
        with self.assertRaises(ApiError):
            self.api.login("user", "long-password")
        self.assertIsNone(session.get_token())


class ConfigTests(unittest.TestCase):
    """用户可写配置与兼容读取路径的回归。"""

    def test_user_config_overrides_legacy_without_modifying_it(self):
        """保存到用户目录，原安装 .env 原字节不变，不在程序目录留下文件。"""
        with tempfile.TemporaryDirectory(dir=CLIENT / "tests") as directory:
            root = Path(directory)  # 测试独立的模拟安装目录及用户配置目录。
            legacy = root / ".env"
            legacy.write_text("SERVER_URL=http://old:8000\n", encoding="utf-8")
            with patch.dict(os.environ, {"IMS_CLIENT_CONFIG_DIR": str(root / "user")}), patch.object(config, "ENV_PATH", legacy):
                with patch.dict(os.environ, {}, clear=False):
                    os.environ.pop("SERVER_URL", None)
                    self.assertEqual(config.load_config()["SERVER_URL"], "http://old:8000")
                    config.save_server_url("http://new:8000/")
                    self.assertEqual(config.load_config()["SERVER_URL"], "http://new:8000")
                    self.assertEqual(legacy.read_text(encoding="utf-8"), "SERVER_URL=http://old:8000\n")

    def test_bad_addresses_are_rejected(self):
        """禁止令牌被发送到嵌入密码、无效协议或换行注入的地址。"""
        for address in ("ftp://host", "http://user:pass@host", "http://host?x=1", "http://host\nBAD=1", "http://host:99999"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                config.normalize_server_url(address)


class WorkflowTests(unittest.TestCase):
    """销毁范围锁定与实物借还状态机回归。"""

    def test_destroy_requires_matching_approved_snapshot(self):
        """状态、快照和 ID 集合缺一不可。"""
        approved = {"status": "approved", "archive_ids": [1], "archives": [{"id": 1}]}
        self.assertEqual(approved_destroy_archives(approved), [{"id": 1}])
        for changed in ({"status": "pending"}, {"archive_ids": [2]}, {"archives": []}, {"archives": [{"id": 1}, {"id": 1}]}):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                approved_destroy_archives({**approved, **changed})

    def test_destroy_cannot_execute_other_or_partial_archives(self):
        """整单必须手动选齐，不允许空、部分或越界索引。"""
        rows = [{"id": 1}, {"id": 2}]
        self.assertEqual(destroy_execution_ids(rows, [1, 0]), [1, 2])
        for selected in ([], [0], [0, 2]):
            with self.assertRaises(ValueError):
                destroy_execution_ids(rows, selected)

    def test_physical_loan_must_be_checked_out_before_return(self):
        """批准不是实物借出；电子借阅可结束已批准记录。"""
        physical = {"status": "approved", "borrow_mode": "physical"}
        self.assertTrue(can_checkout_borrow(physical))
        self.assertFalse(can_return_borrow(physical))
        self.assertTrue(can_return_borrow({**physical, "status": "borrowed"}))
        self.assertTrue(can_return_borrow({"status": "approved", "borrow_mode": "electronic"}))

    def test_destroy_dialog_reads_application_detail_not_archive_search(self):
        """调用真实加载方法，确保执行窗口不会退回全库，也不会默认全选。"""
        api = Mock()  # 仅允许详情响应中的一份档案。
        api.get.return_value = {"data": {"status": "approved", "archive_ids": [7],
                                              "archives": [{"id": 7, "archive_no": "A7", "archive_name": "已批准"}]}}
        widget = types.SimpleNamespace(api=api, destroy_id=3, list=Mock())
        method = extracted_method("app/ui/modules/destroy_manage.py", "DestroyExecDialog", "_load_archives",
                                  {"approved_destroy_archives": approved_destroy_archives, "QMessageBox": Mock()})
        method(widget)
        api.get.assert_called_once_with("/api/destroys/3")
        widget.list.addItem.assert_called_once_with("A7 已批准")
        widget.list.item.assert_not_called()


class DashboardTests(unittest.TestCase):
    """用非空统计数据执行真实刷新方法，覆盖原元组按字典读取错误。"""

    def test_nonempty_statistics_pass_named_rows_to_tables(self):
        """图表元组转换成命名字段后，分类和到期表仍继续刷新。"""
        widget = Mock()  # 提供刷新所需的 API 和控件，不依赖 Qt 绘图。
        widget.api.get.return_value = {"code": 0, "data": {"total": 3, "by_status": [{"status": "archive", "count": 3}],
                                                               "by_category": [{"name": "技术", "count": 3}]}}
        widget.card_values = {"total": Mock(), "archived": Mock()}
        method = extracted_method("app/ui/modules/dashboard.py", "DashboardModule", "refresh",
                                  {"STATUS_CN": {"archive": "已归档"}, "STATUS_COLOR": {"archive": "blue"}})
        method(widget)
        widget._show_error.assert_not_called()
        first_rows = widget._fill_simple.call_args_list[0].args[1]
        self.assertEqual(first_rows, [{"label": "已归档", "count": 3}])
        widget._fill_expiring.assert_called_once_with([])


class PasswordUiTests(unittest.TestCase):
    """真实密码修改方法的离线控件回归。"""

    def test_password_inputs_are_masked_and_success_requires_new_login(self):
        """两次输入均掩码；成功后不再携带旧令牌请求 me，而是重新登录。"""
        dialog = Mock()  # 返回旧密码和符合后端约束的新密码。
        dialog.getText.side_effect = [("old-password", True), ("new-password-long", True)]
        widget = Mock()  # 主窗口测试桩。
        tasks = []  # 保存后台任务，确保输入阶段不直接执行阻塞请求。

        def submit(owner, operation, success, failure, **options):
            """记录待执行任务，不在输入调用中请求网络。"""
            tasks.append((operation, success))

        method = extracted_method("app/ui/main_window.py", "MainWindow", "_change_password",
                                  {"QInputDialog": dialog, "QLineEdit": types.SimpleNamespace(Password=9),
                                   "QMessageBox": Mock(), "run_task": submit})
        method(widget)
        self.assertEqual([call.args[3] for call in dialog.getText.call_args_list], [9, 9])
        widget.api.change_password.assert_not_called()
        tasks[0][0]()
        widget.api.change_password.assert_called_once_with("old-password", "new-password-long")
        completed = extracted_method("app/ui/main_window.py", "MainWindow", "_password_changed", {"QMessageBox": Mock()})
        completed(widget, {"code": 0})
        widget.api.me.assert_not_called()
        widget._finish_logout.assert_called_once()


class ApiSurfaceTests(unittest.TestCase):
    """跨模块 API 调用契约，防止界面引用不存在的便捷方法。"""

    def test_all_ui_api_methods_exist(self):
        """扫描实际调用点，可覆盖原预览和分类删除的 AttributeError。"""
        missing = []  # 收集文件、行号、缺失方法，方便失败时定位。
        for path in (CLIENT / "app/ui").rglob("*.py"):
            tree = ast.parse(path.read_text(encoding="utf-8-sig"))
            for node in ast.walk(tree):
                if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                        and isinstance(node.func.value, ast.Attribute) and node.func.value.attr == "api"
                        and not callable(getattr(ApiClient, node.func.attr, None))):
                    missing.append((str(path.relative_to(CLIENT)), node.lineno, node.func.attr))
        self.assertEqual(missing, [])

    def test_approval_node_conditions_send_codes_not_chinese_labels(self):
        """执行实际添加节点方法，条件框绑定的是字段/运算码而不是中文。"""
        widget = Mock()  # 节点表和下拉控件的最小桩。
        widget.node_table.rowCount.return_value = 0
        method = extracted_method("app/ui/modules/approval_config.py", "TemplateEditDialog", "_add_node",
                                  {"APPROVER_TYPES": [("角色", "role")], "AFFIX_FIELDS": [("archive_no", "档号")],
                                   "AFFIX_OPS": [("eq", "等于")], "QComboBox": Mock, "QTableWidgetItem": str})
        method(widget)
        self.assertEqual(widget._set_cell_combo.call_args_list[1].args[2], [("档号", "archive_no")])
        self.assertEqual(widget._set_cell_combo.call_args_list[2].args[2], [("等于", "eq")])


if __name__ == "__main__":
    unittest.main()
