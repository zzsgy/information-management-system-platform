# -*- coding: utf-8 -*-
"""
app/core/api_client.py
=======================
服务端 REST 客户端：基于 requests 封装。
统一处理：鉴权头、JSON 解析、错误抛出、文件上传/下载。
所有返回按服务端统一结构 {code,message,data} 透传给调用方。
"""
import os
import tempfile
import threading
import urllib.parse

import requests

from app.core import config
from app.core import session


class ApiError(Exception):
    """接口调用异常：包含 HTTP 状态码与后端返回信息。"""

    def __init__(self, message: str, status=None, detail=None):
        super().__init__(message)
        self.status = status
        self.detail = detail


class ApiClient:
    """对服务端发起请求的轻量客户端；各线程分别复用自己的连接池。"""

    def __init__(self):
        self.base_url = session.get_base_url() or config.load_config()["SERVER_URL"]
        self._local = threading.local()  # requests.Session 按线程隔离，避免后台任务共享可变连接状态。

    @property
    def _http(self):
        """每个执行线程复用自己的连接池，不跨线程共享 requests.Session。"""
        if not hasattr(self._local, "http"):
            self._local.http = requests.Session()
        return self._local.http

    # ---------------- 底层请求 ----------------
    def _request(self, method: str, path: str, json_body=None, params=None,
                 files=None, stream=False, auth_token=None, timeout=(10, 60)):
        """保持原 API 信封；严格处理错误、令牌失效和跨主机请求。"""
        url = f"{self.base_url.rstrip('/')}/{path.lstrip('/')}"  # 仅允许本服务相对路由。
        if path.startswith(("http:", "https:", "//")):
            raise ApiError("接口必须使用当前服务的相对路径")
        headers = {}  # 此次请求的身份头，禁止放入全局 Session 默认头。
        token = auth_token if auth_token is not None else session.get_token()  # 登录补充权限时显式指定新令牌。
        if token:
            headers["Authorization"] = f"Bearer {token}"
        try:
            resp = self._http.request(
                method, url, json=json_body, params=params,
                files=files, headers=headers, stream=stream, timeout=timeout,
                allow_redirects=False,
            )
        except requests.RequestException as e:
            raise ApiError(f"无法连接服务器：{e}")

        if resp.status_code == 401 and token:
            session.clear(expected_token=token)
        if 300 <= resp.status_code < 400:
            resp.close()
            raise ApiError("服务器返回重定向，请检查服务地址", status=resp.status_code)
        if stream and resp.status_code < 400:
            # 成功流由下载方法负责关闭，错误流继续读取统一错误信封。
            return resp

        # 尝试解析 JSON
        try:
            data = resp.json()
        except ValueError as error:
            resp.close()
            raise ApiError("服务器响应不是有效的 JSON，请检查地址和服务状态", status=resp.status_code) from error

        resp.close()
        if not isinstance(data, dict):
            raise ApiError("服务器响应格式无效", status=resp.status_code)

        if resp.status_code >= 400:
            msg = (data.get("message") or data.get("detail")
                   or f"HTTP {resp.status_code}")
            raise ApiError(msg, status=resp.status_code, detail=data)
        if data.get("code") != 0:
            raise ApiError(data.get("message") or "服务端未确认操作成功", status=resp.status_code, detail=data)
        return data

    def get(self, path, params=None):
        return self._request("GET", path, params=params)

    def post(self, path, json_body=None, params=None):
        return self._request("POST", path, json_body=json_body, params=params)

    def put(self, path, json_body=None, params=None):
        return self._request("PUT", path, json_body=json_body, params=params)

    def delete(self, path, params=None):
        """兼容分类删除等现有调用，仍经过统一鉴权与错误处理。"""
        return self._request("DELETE", path, params=params)

    # ---------------- 业务便捷方法 ----------------
    def login(self, username: str, password: str) -> dict:
        """验证账号并读取实际权限；返回数据后由界面线程保存登录会话。"""
        res = self.post("/api/auth/login", {"username": username, "password": password})
        if res.get("code") != 0:
            raise ApiError(res.get("message", "登录失败"))
        data = res["data"]
        # 登录返回仅含角色，需再取权限清单以控制菜单可见性
        try:
            info = self._request("GET", "/api/system/info", auth_token=data["access_token"]).get("data", {})
            data["permissions"] = info.get("permissions", [])
            data["roles"] = info.get("roles", data.get("roles", []))
        except ApiError as error:
            # 登录数据已含权限时可兼容使用；读取失败不推断管理员权限。
            if error.status == 401 or "permissions" not in data:
                raise
        return data

    def logout(self):
        """撤销当前服务端会话；本地清理由窗口在完成后统一处理。"""
        return self.post("/api/auth/logout")

    def change_password(self, old_password: str, new_password: str):
        return self.post("/api/auth/change-password",
                         {"old_password": old_password, "new_password": new_password})

    def me(self) -> dict:
        return self.get("/api/auth/me")

    def system_info(self) -> dict:
        return self.get("/api/system/info")

    def dicts(self) -> dict:
        return self.get("/api/system/dicts")

    def dict_items(self, type_code: str) -> dict:
        return self.get("/api/system/dict-items", params={"type_code": type_code})

    def create_dict_item(self, payload: dict) -> dict:
        return self.post("/api/system/dict-items", payload)

    def update_dict_item(self, item_id: int, payload: dict) -> dict:
        return self.put(f"/api/system/dict-items/{item_id}", payload)

    def delete_dict_item(self, item_id: int) -> dict:
        return self._request("DELETE", f"/api/system/dict-items/{item_id}")

    # ---------------- 密级（独立表，管理员可维护） ----------------
    def security_levels(self) -> dict:
        return self.get("/api/system/security-levels")

    def create_security_level(self, payload: dict) -> dict:
        return self.post("/api/system/security-levels", payload)

    def update_security_level(self, item_id: int, payload: dict) -> dict:
        return self.put(f"/api/system/security-levels/{item_id}", payload)

    def delete_security_level(self, item_id: int) -> dict:
        return self._request("DELETE", f"/api/system/security-levels/{item_id}")

    # ---------------- 保管期限（独立表，管理员可维护） ----------------
    def retention_periods_manage(self) -> dict:
        return self.get("/api/system/retention-periods")

    def create_retention_period(self, payload: dict) -> dict:
        return self.post("/api/system/retention-periods", payload)

    def update_retention_period(self, item_id: int, payload: dict) -> dict:
        return self.put(f"/api/system/retention-periods/{item_id}", payload)

    def delete_retention_period(self, item_id: int) -> dict:
        return self._request("DELETE", f"/api/system/retention-periods/{item_id}")

    # ---------------- 档案编号规则 ----------------
    def numbering_rules(self) -> dict:
        return self.get("/api/system/numbering-rules")

    def create_numbering_rule(self, payload: dict) -> dict:
        return self.post("/api/system/numbering-rules", payload)

    def update_numbering_rule(self, rule_id: int, payload: dict) -> dict:
        return self.put(f"/api/system/numbering-rules/{rule_id}", payload)

    def delete_numbering_rule(self, rule_id: int) -> dict:
        return self._request("DELETE", f"/api/system/numbering-rules/{rule_id}")

    def preview_archive_no(self, dept_id: int, category_id: int, retention_id: int = None) -> dict:
        params = {"dept_id": dept_id, "category_id": category_id}
        if retention_id:
            params["retention_id"] = retention_id
        return self.get("/api/system/numbering-rules/preview", params=params)

    # ---------------- 系统参数（企业 LOGO / 首页卡片配置等） ----------------
    def get_param(self, key: str) -> dict:
        return self.get(f"/api/system/param/{key}")

    def set_param(self, key: str, value, description: str = None) -> dict:
        body = {"value": value}
        if description is not None:
            body["description"] = description
        return self.put(f"/api/system/param/{key}", body)

    def get_archive_no_mode(self) -> dict:
        return self.get("/api/system/archive-no-mode")

    def set_archive_no_mode(self, mode: str) -> dict:
        return self.put("/api/system/archive-no-mode", {"mode": mode})

    # ---------------- 审批流 ----------------
    def approval_templates(self) -> dict:
        return self.get("/api/approval/templates")

    def approval_template(self, template_id: int) -> dict:
        return self.get(f"/api/approval/templates/{template_id}")

    def create_approval_template(self, payload: dict) -> dict:
        return self.post("/api/approval/templates", payload)

    def update_approval_template(self, template_id: int, payload: dict) -> dict:
        return self.put(f"/api/approval/templates/{template_id}", payload)

    def delete_approval_template(self, template_id: int) -> dict:
        return self._request("DELETE", f"/api/approval/templates/{template_id}")

    def my_tasks(self, biz_type: str = None) -> dict:
        params = {"biz_type": biz_type} if biz_type else {}
        return self.get("/api/approval/my-tasks", params=params)

    def list_flows(self, biz_type: str = None, status: str = None) -> dict:
        params = {}
        if biz_type:
            params["biz_type"] = biz_type
        if status:
            params["status"] = status
        return self.get("/api/approval/flows", params=params)

    def flow_detail(self, flow_id: int) -> dict:
        return self.get(f"/api/approval/flows/{flow_id}")

    def flow_by_biz(self, biz_type: str, biz_id: int) -> dict:
        return self.get("/api/approval/flow-by-biz",
                        params={"biz_type": biz_type, "biz_id": biz_id})

    def act_flow(self, flow_id: int, action: str, opinion: str = None) -> dict:
        return self.post(f"/api/approval/flows/{flow_id}/act",
                         {"action": action, "opinion": opinion})

    def preview_file(self, file_id: int, save_path: str) -> str:
        """下载文件预览流到本地临时路径（内联预览接口）。"""
        return self.download_file(f"/api/files/{file_id}/preview", save_path)

    def upload_file(self, path: str, file_path: str, params: dict = None):
        """multipart 上传文件。file_path 为本地文件路径。"""
        filename = os.path.basename(file_path)
        with open(file_path, "rb") as f:
            files = {"file": (filename, f, "application/octet-stream")}
            return self._request("POST", path, params=params, files=files, timeout=(10, 300))

    def download_file(self, path: str, save_path: str):
        """先写同目录临时文件，完整下载成功才替换目标，失败保留原文件。"""
        resp = self._request("GET", path, stream=True)
        temporary = None  # 用于 finally 清理下载失败留下的半成品。
        try:
            handle, temporary = tempfile.mkstemp(prefix=".ims-download-", dir=os.path.dirname(os.path.abspath(save_path)))
            with os.fdopen(handle, "wb") as target:
                for chunk in resp.iter_content(chunk_size=65536):
                    if chunk:
                        target.write(chunk)
            os.replace(temporary, save_path)
        finally:
            resp.close()
            if temporary and os.path.exists(temporary):
                os.unlink(temporary)
        return save_path

    # ---------------- 电子文件（files）便捷方法 ----------------
    def list_files(self, archive_id: int) -> list:
        """列出某档案的电子文件（需要 archive.read 权限）。"""
        res = self.get("/api/files", params={"archive_id": archive_id})
        return res.get("data") or []

    def upload_archive_file(self, archive_id: int, file_path: str) -> dict:
        """上传一份电子文件到指定档案（需要 file.upload 权限）。"""
        return self.upload_file("/api/files/upload", file_path,
                                params={"archive_id": archive_id})

    def download_archive_file(self, file_id: int, save_path: str) -> str:
        """下载电子文件到本地（需要 file.download 权限）。"""
        return self.download_file(f"/api/files/{file_id}/download", save_path)

    # ---------------- 统计台账便捷方法 ----------------
    def archive_stats(self) -> dict:
        """获取档案统计（总数/状态/分类/密级分布/即将到期）。"""
        return self.get("/api/archives/stats").get("data", {})

    # ---------------- 数据安全运维便捷方法 ----------------
    def integrity_check(self) -> dict:
        """电子文件完整性校验：重算 SHA256 与库内比对。"""
        return self._request("GET", "/api/security/integrity/check", timeout=(10, 300)).get("data", {})

    def db_backup(self) -> dict:
        """立即执行一次数据库备份。"""
        return self._request("POST", "/api/security/db-backup", timeout=(10, 300)).get("data", {})

    def list_db_backups(self) -> list:
        """列出已存在的数据库备份文件。"""
        return self.get("/api/security/db-backups").get("data", []) or []

    def db_restore(self, file_name: str) -> dict:
        """沿用旧路由校验恢复包；Java 端禁止在线覆盖，返回停服恢复说明。"""
        return self._request("POST", "/api/security/db-restore",
                             json_body={"file": file_name, "confirm": True}, timeout=(10, 300)).get("data", {})

    def get_update_logs(self, archive_id: int) -> dict:
        """获取某档案的全部更新记录。"""
        return self._request("GET", f"/api/archives/{archive_id}/update-logs")
