# -*- coding: utf-8 -*-
"""真实 ApiClient 联调；仅允许显式指定的本机临时服务，会新建并销毁测试档案。"""
import argparse
from datetime import date, timedelta
import hashlib
import os
from pathlib import Path
import sys
import tempfile
from urllib.parse import urlsplit
from uuid import uuid4

CLIENT = Path(__file__).resolve().parents[1]  # 复用实际客户端代码，不为冒烟重新实现 HTTP。
sys.path.insert(0, str(CLIENT))

from app.core import session
from app.core.api_client import ApiClient, ApiError
from app.core.workflow_rules import approved_destroy_archives, destroy_execution_ids


def arguments():
    """地址和口令文件必须显式提供；避免误用客户端保存的业务服务器地址。"""
    parser = argparse.ArgumentParser(description="仅用于 127.0.0.1 临时数据库的真实客户端联调")
    parser.add_argument("--base-url", default=os.environ.get("IMS_SMOKE_BASE_URL"), help="临时服务的完整地址")
    parser.add_argument("--password-file", default=os.environ.get("IMS_SMOKE_PASSWORD_FILE"), help="只读口令文件路径")
    parser.add_argument("--username", default="admin", help="测试管理员账号")
    options = parser.parse_args()  # 参数值不写入测试输出。
    if not options.base_url or not options.password_file:
        parser.error("必须显式指定 IMS_SMOKE_BASE_URL 和 IMS_SMOKE_PASSWORD_FILE 或对应参数")
    endpoint = urlsplit(options.base_url)  # 不进行 DNS 解析，只允许明确的 IPv4 回环地址。
    if (endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1"
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment
            or endpoint.path not in ("", "/") or endpoint.port is None):
        parser.error("测试地址必须是显式端口的 http://127.0.0.1 地址，不可带路径或认证信息")
    options.base_url = options.base_url.rstrip("/")
    return options


class Smoke:
    """以步骤名报告成败；不打印响应正文、会话令牌、账号信息或口令。"""

    def __init__(self, options):
        """固定显式测试地址，并禁用环境代理以保证请求只发往本机。"""
        self.options = options  # 经参数校验的本次运行配置。
        self.api = ApiClient()  # 实际应用使用的请求、鉴权、上传及原子下载实现。
        self.api.base_url = options.base_url
        self.api._http.trust_env = False
        self.current_step = "初始化"  # 异常只显示步骤名和有限状态信息。
        self.completed = 0  # 已通过且完成断言的业务步骤数量。
        self.prefix = "CLIENT-SMOKE-" + uuid4().hex[:12]  # 唯一测试档案前缀，避免与其它联调互相干扰。

    def step(self, name, operation):
        """执行一步业务与断言；只有全部完成才输出 PASS。"""
        self.current_step = name
        result = operation()  # 对外只返回业务结果，不向控制台输出对象内容。
        self.completed += 1
        print("PASS " + name, flush=True)
        return result

    @staticmethod
    def require(condition):
        """断言失败不拼接业务数据，防止账号或令牌进入输出。"""
        if not condition:
            raise AssertionError("业务断言失败")

    def rejected(self, operation, statuses):
        """明确验证预期的拒绝状态，不能把连接失败或其他异常当成权限保护通过。"""
        try:
            operation()
        except ApiError as error:  # 只核对预期 HTTP 码，不输出服务端消息或正文。
            self.require(error.status in statuses)
        else:
            raise AssertionError("预期的拒绝没有发生")

    def login(self):
        """从本地文件读取口令，并按真实登录界面写入客户端共享会话。"""
        password = Path(self.options.password_file).read_text(encoding="utf-8-sig").strip()  # 不写日志或参数。
        self.require(bool(password))
        user = self.api.login(self.options.username, password)  # 返回的令牌按不透明字符串使用。
        del password
        session.set_session(user["access_token"], user, self.options.base_url)
        self.require(bool(user.get("permissions")))
        self.require(self.api.me()["data"]["username"] == self.options.username)

    def catalogues(self):
        """读取真实新库的字典、分类和部门，并选用本账号可用的最小必填字段。"""
        dictionaries = self.api.dicts()["data"]  # 密级和保管期限采用 Java 兼容字段。
        departments = self.api.get("/api/departments")["data"]  # 服务端已按权限收紧部门范围。
        categories = self.api.get("/api/categories")["data"]  # 档案分类树在当前桌面端使用平铺列表。
        self.require(bool(departments) and bool(categories))
        self.require(isinstance(dictionaries.get("security_levels"), list))
        self.require(isinstance(dictionaries.get("retention_periods"), list))
        return {"dept_id": departments[0]["id"], "category_id": categories[0]["id"]}

    def create_archive(self, catalogue, suffix):
        """只创建本次唯一前缀的测试草稿，不读写已有档案内容。"""
        body = {**catalogue, "archive_name": self.prefix + suffix,
                "archive_no": self.prefix + suffix, "copy_count": 1, "page_count": 2}  # 同时兼容自动/手动编号模式。
        archive = self.api.post("/api/archives", body)["data"]  # 创建成功后以真实主键继续操作。
        self.require(archive["status"] == "draft")
        return archive["id"]

    def edit_archive(self, archive_id):
        """验证元数据更新、空日期保留及普通编辑不能绕过归档状态机。"""
        updated = self.api.put(f"/api/archives/{archive_id}", {
            "archive_name": self.prefix + "-edited", "page_count": 3, "formation_date": None})["data"]
        self.require(updated["page_count"] == 3 and updated["formation_date"] is None)
        self.require(updated["status"] == "draft")
        self.rejected(lambda: self.api.put(f"/api/archives/{archive_id}", {"status": "archive"}), {409})
        self.require(bool(self.api.get_update_logs(archive_id)["data"]))

    def archive(self, archive_id):
        """通过专门业务入口归档，随后读取实际保存状态。"""
        self.api.post(f"/api/archives/{archive_id}/archive", {})
        self.require(self.api.get(f"/api/archives/{archive_id}")["data"]["status"] == "archive")

    def files(self, archive_id, temporary):
        """上传后同时校验预览与下载的字节和摘要，覆盖真实 multipart 及流式响应。"""
        content = ("Java 与 PySide6 附件联调\n" + self.prefix).encode("utf-8")  # 非敏感、可确定比较的测试内容。
        source = temporary / "client-smoke.txt"  # 本轮专属临时上传文件。
        source.write_bytes(content)
        uploaded = self.api.upload_archive_file(archive_id, str(source))["data"]  # Java 返回平铺文件元数据。
        self.require(uploaded["sha256"] == hashlib.sha256(content).hexdigest())
        file_id = uploaded["id"]  # 同一文件的两个独立权限入口。
        self.require(any(item["id"] == file_id for item in self.api.list_files(archive_id)))
        preview = temporary / "preview.txt"  # 由真实 ApiClient 下载预览字节。
        download = temporary / "download.txt"  # 与预览不同的目标，避免误用已有文件。
        self.api.preview_file(file_id, str(preview))
        self.api.download_archive_file(file_id, str(download))
        self.require(preview.read_bytes() == content and download.read_bytes() == content)
        return file_id

    def borrow(self, archive_id):
        """验证实物借阅从申请到归还，兼测通用待办审批同步业务状态。"""
        request = self.api.post("/api/borrows", {
            "archive_ids": [archive_id], "borrow_mode": "physical", "purpose": "隔离数据库客户端联调",
            "expected_return_date": (date.today() + timedelta(days=1)).isoformat()})["data"]
        borrow_id = request["id"]  # 本次申请产生的借阅单，禁止使用已有业务单。
        self.require(request["status"] == "pending")
        flow = self.api.flow_by_biz("borrow", borrow_id)["data"]  # 使用服务端固化的真实审批人和当前节点。
        self.api.act_flow(flow["id"], "agree", "客户端联合冒烟审批")
        self.require(self.api.get(f"/api/borrows/{borrow_id}")["data"]["status"] == "approved")
        self.rejected(lambda: self.api.post(f"/api/borrows/{borrow_id}/return", {"return_condition": "完好"}), {409})
        self.api.post(f"/api/borrows/{borrow_id}/checkout", {})
        self.require(self.api.get(f"/api/borrows/{borrow_id}")["data"]["status"] == "borrowed")
        self.require(self.api.get(f"/api/archives/{archive_id}")["data"]["status"] == "borrowed")
        self.api.post(f"/api/borrows/{borrow_id}/return", {"return_condition": "完好"})
        self.require(self.api.get(f"/api/borrows/{borrow_id}")["data"]["status"] == "returned")
        self.require(self.api.get(f"/api/archives/{archive_id}")["data"]["status"] == "archive")

    def destruction(self, archive_id, unrelated_id, file_id, temporary):
        """仅销毁当前脚本创建的档案，验证误选其它档案被拒绝并保留审批范围。"""
        request = self.api.post("/api/destroys", {
            "archive_ids": [archive_id], "reason": "隔离数据库客户端销毁联调", "scope_desc": self.prefix})["data"]
        destroy_id = request["id"]  # 仅使用本次申请的不可变获批集合。
        self.api.post(f"/api/destroys/{destroy_id}/approve", params={"approve": "true", "opinion": "客户端联调"})
        detail = self.api.get(f"/api/destroys/{destroy_id}")["data"]  # 与真实销毁执行对话框同一个详情接口。
        approved = approved_destroy_archives(detail)  # 复用前端纯函数检查范围和状态。
        execution_ids = destroy_execution_ids(approved, list(range(len(approved))))  # 显式选择本申请的全部明细。
        self.require(execution_ids == [archive_id])
        body = {"witness": "联调见证人", "method": "隔离数据逻辑销毁", "archive_ids": [unrelated_id]}
        self.rejected(lambda: self.api.post(f"/api/destroys/{destroy_id}/execute", body), {400})
        self.require(self.api.get(f"/api/archives/{unrelated_id}")["data"]["status"] == "archive")
        self.require(self.api.get(f"/api/destroys/{destroy_id}")["data"]["status"] == "approved")
        body["archive_ids"] = execution_ids
        self.require(bool(self.api.post(f"/api/destroys/{destroy_id}/execute", body)["data"]["batch_no"]))
        self.rejected(lambda: self.api.post(f"/api/destroys/{destroy_id}/execute", body), {409})
        self.rejected(lambda: self.api.get(f"/api/archives/{archive_id}"), {403, 404})
        self.rejected(lambda: self.api.download_archive_file(file_id, str(temporary / "destroyed.txt")), {403, 404})
        self.require(self.api.get(f"/api/archives/{unrelated_id}")["data"]["status"] == "archive")

    def logout(self):
        """确认服务端令牌已撤销并触发客户端清空失效会话。"""
        self.api.logout()
        self.rejected(self.api.me, {401})
        self.require(not session.get_token())

    def run(self):
        """按真实桌面业务顺序运行；始终释放会话及本地测试附件。"""
        try:
            self.step("登录及身份权限", self.login)
            catalogue = self.step("读取字典部门分类", self.catalogues)  # 后续创建使用真实有效外键。
            archive_id = self.step("创建草稿", lambda: self.create_archive(catalogue, "-A"))
            self.step("编辑及状态保护", lambda: self.edit_archive(archive_id))
            self.step("正式归档", lambda: self.archive(archive_id))
            unrelated_id = self.step("创建范围对照档案", lambda: self.create_archive(catalogue, "-B"))
            self.step("归档范围对照档案", lambda: self.archive(unrelated_id))
            with tempfile.TemporaryDirectory(prefix="ims-client-live-") as directory:
                temporary = Path(directory)  # 临时目录由本脚本创建，退出时只清理其内部测试文件。
                file_id = self.step("上传预览下载及摘要", lambda: self.files(archive_id, temporary))
                self.step("借阅审批借出归还", lambda: self.borrow(archive_id))
                self.step("销毁范围及重复执行保护", lambda: self.destruction(archive_id, unrelated_id, file_id, temporary))
            self.step("退出及会话撤销", self.logout)
            print(f"JAVA_CLIENT_SMOKE_OK steps={self.completed}", flush=True)
            return 0
        except Exception as error:  # 只显示有限异常类别和 HTTP 码，不打印异常正文或 traceback。
            status = error.status if isinstance(error, ApiError) and isinstance(error.status, int) else "none"
            print(f"FAIL {self.current_step} kind={type(error).__name__} status={status}", flush=True)
            return 1
        finally:
            if session.get_token():
                try:
                    self.api.logout()
                except Exception:
                    pass  # 清理失败不遮盖原步骤失败，且不得打印凭据。
            session.clear()
            self.api._http.close()


if __name__ == "__main__":
    sys.exit(Smoke(arguments()).run())
