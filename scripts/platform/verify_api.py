"""第一批真实 PostgreSQL / HTTPS 集成验收；只允许显式指定的本机隔离库。

使用私有配置读取测试口令，不把 Cookie、临时密码或验证凭据写进测试报告。
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

# 集合标识是创建操作的固定再验证对象，与接口实现一致。
COLLECTION = "00000000-0000-0000-0000-000000000001"
CONFIG: dict[str, str] = {}
BASE = ""
PG_BIN = ""


def isolated_sql(statement: str, expect_failure=False):
    """仅操作已校验的新测试库；数据库密码通过子进程环境传递，不进入命令行或报告。"""
    address = urllib.parse.urlparse(CONFIG["IMS_PLATFORM_DB_URL"].removeprefix("jdbc:"))
    environment = dict(os.environ, PGPASSWORD=CONFIG["IMS_PLATFORM_DB_PASSWORD"])
    result = subprocess.run([str(Path(PG_BIN) / "psql.exe"), "-X", "-A", "-t", "-h", "127.0.0.1",
                             "-p", str(address.port), "-U", CONFIG["IMS_PLATFORM_DB_USER"],
                             "-d", address.path[1:], "-v", "ON_ERROR_STOP=1", "-c", statement],
                            capture_output=True, text=True, encoding="utf-8", env=environment, timeout=20)
    if bool(result.returncode) != expect_failure:
        raise AssertionError("隔离 SQL 验收结果不符合预期；不输出连接凭据或完整 SQL")
    return result.stderr if expect_failure else result.stdout.strip()


class Client:
    """隔离测试客户端；每个实例单独保存测试会话，只向固定回环地址发请求。"""

    def __init__(self, employee: str = "", password: str = ""):
        self.employee, self.password = employee, password
        self.token, self.csrf = "", ""
        # 仅测试自签本机证书；生产程序始终使用 HTTPS Secure Cookie。
        self.context = ssl._create_unverified_context()

    def call(self, method: str, path: str, body=None, headers=None, expected=None):
        """返回状态及安全包装；失败断言只显示错误码，不输出响应内的秘密。"""
        request_headers = {"X-Request-Id": str(uuid.uuid4())}
        if self.token:
            request_headers["Cookie"] = "IMS_SESSION=" + self.token
        if self.csrf:
            request_headers["X-CSRF-Token"] = self.csrf
        if body is not None:
            request_headers["Content-Type"] = "application/json"
        request_headers.update(headers or {})
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(BASE + "/api/v1" + path, data, request_headers, method=method)
        try:
            response = urllib.request.urlopen(request, context=self.context, timeout=20)
        except urllib.error.HTTPError as failure:
            response = failure
        result = json.loads(response.read())
        if expected is not None and response.status != expected:
            raise AssertionError(f"{method} {path}: expected {expected}, got {response.status}; code={result.get('code')}")
        return response.status, result, response.headers

    def login(self, expected=200):
        """只在成功后接受新 Cookie，模拟浏览器的单会话语义。"""
        status, result, headers = self.call("POST", "/auth/login", {"employeeNo": self.employee, "password": self.password}, expected=expected)
        if status == 200:
            self.token = headers["Set-Cookie"].split(";", 1)[0].split("=", 1)[1]
            self.csrf = result["data"]["csrfToken"]
        return status, result, headers

    def reauth(self, target, kind, action):
        """再验证凭据只绑定当前一次测试动作和对象。"""
        _, result, _ = self.call("POST", "/auth/reauthentications", {
            "password": self.password, "purpose": "MANAGE", "objectIds": [target],
            "scopeItems": [{"objectType": kind, "objectId": target, "actions": [action]}],
        }, expected=200)
        return result["data"]["verificationToken"]

    def command(self, method, path, body, target, kind, action, version=None, operation=None, token=None, expected=200):
        """发送稳定操作号及版本；可显式重用以核验幂等和并发。"""
        headers = {"X-Operation-Id": operation or str(uuid.uuid4()), "X-Reauth-Token": token or self.reauth(target, kind, action)}
        if version is not None:
            headers["X-Expected-Version"] = str(version)
        return self.call(method, path, body, headers, expected)


class PlatformApiTests(unittest.TestCase):
    """可执行的验收场景；每次运行用唯一前缀创建样本，不覆盖或清理已有数据。"""

    @classmethod
    def setUpClass(cls):
        cls.prefix = "QA" + uuid.uuid4().hex[:8]
        cls.admin = Client(CONFIG["IMS_PLATFORM_ADMIN_NO"], CONFIG["IMS_PLATFORM_ADMIN_PASSWORD"])
        cls.admin.login()
        _, result, _ = cls.admin.call("GET", "/admin/departments?pageSize=100", expected=200)
        cls.department = result["data"]["items"][0]["department"]
        cls.roles = {}
        for module in ("PLATFORM", "DRIVE"):
            _, result, _ = cls.admin.call("GET", f"/admin/roles?module={module}&pageSize=100", expected=200)
            cls.roles[module] = {row["code"]: row for row in result["data"]["items"]}

    @classmethod
    def create_user(cls, label, setup=True):
        """创建独立测试人员，从受限本地邮件读取临时口令并完成首次设密。"""
        employee = cls.prefix + label + uuid.uuid4().hex[:5]
        body = {"employeeNo": employee, "name": "验证人员" + label, "departmentId": cls.department["id"], "companyEmail": employee.lower() + "@biolink.com"}
        _, result, _ = cls.admin.command("POST", "/admin/users", body, COLLECTION, "COLLECTION", "CREATE_USER", expected=201)
        user = result["data"]
        messages = [json.loads(p.read_text(encoding="utf-8")) for p in Path(CONFIG["IMS_PLATFORM_MAILBOX_ROOT"]).glob("*.json")]
        message = next(m for m in messages if m["employeeNo"] == employee)
        client = Client(employee, message["temporaryPassword"])
        client.login()
        if setup:
            client.call("PUT", "/auth/initial-password", {"temporaryPassword": client.password, "newPassword": "x"}, expected=200)
            client.password = "x"
            client.login()
        return user, client

    @classmethod
    def assign(cls, user, role_ids):
        """用任职返回的授权版本提交，避免不必要地读取完整账号资料。"""
        _, current, _ = cls.admin.call("GET", f"/admin/users/{user['id']}/roles", expected=200)
        body = {"roles": [{"roleId": role} for role in role_ids], "reason": "隔离验收", "approvalRef": "QA-LOCAL"}
        return cls.admin.command("PUT", f"/admin/users/{user['id']}/roles", body, user["id"], "USER", "ASSIGN_ROLES", version=current["data"]["authVersion"])

    def test_01_anonymous_and_cookie_security(self):
        Client().call("GET", "/portal/home", expected=401)
        user, client = self.create_user("cookie")
        _, _, headers = client.login()
        for marker in ("Secure", "HttpOnly", "SameSite=Strict", "Path=/"):
            self.assertIn(marker, headers["Set-Cookie"])
        self.assertEqual(headers["Cache-Control"], "no-store")

    def test_02_unknown_fields_and_missing_csrf(self):
        self.admin.call("POST", "/auth/reauthentications", {"password": self.admin.password, "admin": True}, expected=400)
        self.admin.call("POST", "/admin/users", {}, {"X-CSRF-Token": ""}, expected=403)
        self.admin.call("POST", "/auth/reauthentications", {}, {"Origin": "https://untrusted.invalid"}, expected=403)

    def test_03_initial_password_is_restricted_and_single_use(self):
        user, client = self.create_user("initial", setup=False)
        client.call("GET", "/portal/home", expected=403)
        temporary = client.password
        client.call("PUT", "/auth/initial-password", {"temporaryPassword": temporary, "newPassword": "x"}, expected=200)
        client.call("GET", "/auth/me", expected=401)
        Client(client.employee, temporary).login(expected=401)
        client.password = "x"
        client.login()
        client.call("GET", "/portal/home", expected=200)

    def test_04_staff_cannot_manage_or_read_secrets(self):
        user, client = self.create_user("staff")
        client.call("GET", "/admin/users", expected=403)
        client.call("GET", "/admin/departments?projection=SELECTOR&purpose=ACCOUNT_ADMIN", expected=403)
        _, result, _ = self.admin.call("GET", f"/admin/users/{user['id']}", expected=200)
        self.assertNotIn("password_hash", json.dumps(result))
        self.assertNotIn("passwordHash", json.dumps(result))
        self.assertNotIn("token", json.dumps(result))

    def test_05_new_login_revokes_previous_session(self):
        user, first = self.create_user("single")
        second = Client(first.employee, first.password)
        second.login()
        first.call("GET", "/auth/me", expected=401)
        second.call("GET", "/auth/me", expected=200)

    def test_06_role_change_revokes_session_and_does_not_grant_platform(self):
        user, client = self.create_user("role")
        self.assign(user, [self.roles["DRIVE"]["STAFF"]["id"]])
        client.call("GET", "/auth/me", expected=401)
        client.login()
        _, result, _ = client.call("GET", "/auth/me", expected=200)
        self.assertIn("DRIVE.ACCESS", result["data"]["actions"])
        self.assertNotIn("PLATFORM.USERS_MANAGE", result["data"]["actions"])
        client.call("GET", "/admin/users", expected=403)

    def test_07_disable_enable_does_not_resurrect_roles(self):
        user, client = self.create_user("disable")
        self.assign(user, [self.roles["DRIVE"]["STAFF"]["id"]])
        client.login()
        _, result, _ = self.admin.call("GET", f"/admin/users/{user['id']}", expected=200)
        version = result["data"]["rowVersion"]
        self.admin.command("PUT", f"/admin/users/{user['id']}/status", {"status": "DISABLED", "reason": "隔离停用验证"}, user["id"], "USER", "USER_STATUS", version=version)
        client.call("GET", "/auth/me", expected=401)
        client.login(expected=401)
        self.admin.command("PUT", f"/admin/users/{user['id']}/status", {"status": "ACTIVE", "reason": "隔离启用验证"}, user["id"], "USER", "USER_STATUS", version=version + 1)
        client.login()
        _, result, _ = client.call("GET", "/auth/me", expected=200)
        self.assertEqual(result["data"]["roles"], [])

    def test_08_duplicate_submit_and_payload_conflict(self):
        operation = str(uuid.uuid4())
        body = {"name": "幂等验证", "code": self.prefix + "IDEMP"}
        token = self.admin.reauth(COLLECTION, "COLLECTION", "CREATE_DEPARTMENT")
        _, first, _ = self.admin.command("POST", "/admin/departments", body, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", token=token, operation=operation, expected=201)
        _, replay, _ = self.admin.command("POST", "/admin/departments", body, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", token=token, operation=operation, expected=201)
        self.assertEqual(first["data"]["id"], replay["data"]["id"])
        self.admin.command("POST", "/admin/departments", {**body, "name": "不同载荷"}, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", token=token, operation=operation, expected=409)

    def test_09_concurrent_duplicate_creates_only_one_object(self):
        operation = str(uuid.uuid4())
        body = {"name": "并发幂等", "code": self.prefix + "CONCURRENT"}
        token = self.admin.reauth(COLLECTION, "COLLECTION", "CREATE_DEPARTMENT")
        def submit(_):
            return self.admin.command("POST", "/admin/departments", body, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", token=token, operation=operation, expected=201)[1]["data"]["id"]
        with ThreadPoolExecutor(max_workers=2) as pool:
            identifiers = list(pool.map(submit, range(2)))
        self.assertEqual(identifiers[0], identifiers[1])

    def test_10_optimistic_version_and_department_cycle(self):
        _, first, _ = self.admin.command("POST", "/admin/departments", {"name": "上级", "code": self.prefix + "TREEA"}, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", expected=201)
        parent = first["data"]
        _, second, _ = self.admin.command("POST", "/admin/departments", {"name": "下级", "code": self.prefix + "TREEB", "parentId": parent["id"]}, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", expected=201)
        child = second["data"]
        self.admin.command("PATCH", f"/admin/departments/{parent['id']}", {"parentId": child["id"]}, parent["id"], "DEPARTMENT", "UPDATE_DEPARTMENT", version=1, expected=409)
        self.admin.command("PATCH", f"/admin/departments/{parent['id']}", {"name": "已修改"}, parent["id"], "DEPARTMENT", "UPDATE_DEPARTMENT", version=1)
        self.admin.command("PATCH", f"/admin/departments/{parent['id']}", {"name": "过期修改"}, parent["id"], "DEPARTMENT", "UPDATE_DEPARTMENT", version=1, expected=409)

    def test_11_reauth_cannot_be_reused_or_cross_action(self):
        token = self.admin.reauth(COLLECTION, "COLLECTION", "CREATE_DEPARTMENT")
        body = {"name": "一次验证", "code": self.prefix + "ONCE"}
        self.admin.command("POST", "/admin/departments", body, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", token=token, expected=201)
        self.admin.command("POST", "/admin/departments", {**body, "code": self.prefix + "TWICE"}, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", token=token, expected=403)
        token = self.admin.reauth(self.department["id"], "DEPARTMENT", "UPDATE_DEPARTMENT")
        self.admin.command("POST", "/admin/departments", body, COLLECTION, "COLLECTION", "CREATE_DEPARTMENT", token=token, expected=403)

    def test_12_self_promotion_and_multiple_drive_roles_rejected(self):
        _, me, _ = self.admin.call("GET", "/auth/me", expected=200)
        self.admin.command("PUT", f"/admin/users/{me['data']['user']['id']}/roles", {"roles": [], "reason": "验证", "approvalRef": "QA"}, me["data"]["user"]["id"], "USER", "ASSIGN_ROLES", version=1, expected=403)
        user, client = self.create_user("multi")
        _, role_view, _ = self.admin.call("GET", f"/admin/users/{user['id']}/roles", expected=200)
        self.admin.command("PUT", f"/admin/users/{user['id']}/roles", {"roles": [{"roleId": self.roles["DRIVE"][code]["id"]} for code in ("STAFF", "GENERAL_MANAGER")], "reason": "验证", "approvalRef": "QA"}, user["id"], "USER", "ASSIGN_ROLES", version=role_view["data"]["authVersion"], expected=409)
        client.call("GET", "/auth/me", expected=200)

    def test_13_login_lockout(self):
        user, client = self.create_user("lock")
        wrong = Client(client.employee, "incorrect-password")
        for _ in range(5):
            wrong.login(expected=401)
        client.login(expected=429)

    def test_14_invalid_email_and_paging(self):
        body = {"employeeNo": self.prefix + "EMAIL", "name": "验证", "departmentId": self.department["id"], "companyEmail": "x@external.invalid"}
        self.admin.command("POST", "/admin/users", body, COLLECTION, "COLLECTION", "CREATE_USER", expected=400)
        self.admin.call("GET", "/admin/users?pageSize=101", expected=400)
        self.admin.call("GET", "/admin/users?sort=name;drop:asc", expected=400)
        _, result, _ = self.admin.call("GET", "/admin/users?pageSize=2", expected=200)
        self.assertEqual(len(result["data"]["items"]), 2)
        self.assertTrue(result["data"]["hasNext"])

    def test_15_audit_records_success_and_denial_without_secrets(self):
        start = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)).isoformat()
        end = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=1)).isoformat()
        params = urllib.parse.urlencode({"from": start, "to": end, "pageSize": 100})
        _, result, _ = self.admin.call("GET", "/admin/audit-events?" + params, expected=200)
        self.assertGreater(result["data"]["total"], 0)
        text = json.dumps(result)
        for secret in (self.admin.password, self.admin.token, "password_hash", "operation_result"):
            self.assertNotIn(secret, text)
        self.assertTrue(any(row["result"] == "DENIED" for row in result["data"]["items"]))

    def test_16_role_permission_change_revokes_members(self):
        user, client = self.create_user("perms")
        role = self.roles["DRIVE"]["GENERAL_MANAGER"]
        self.assign(user, [role["id"]])
        client.login()
        _, detail, _ = self.admin.call("GET", f"/admin/roles/{role['id']}", expected=200)
        permission = next(p for p in detail["data"]["permissions"] if p["code"] == "DRIVE.ACCESS")
        body = {"permissions": [{"permissionId": permission["id"], "scopePolicy": {"code": "COMPANY"}, "effect": "DENY"}], "changeReason": "隔离撤权验证", "approvalRef": "QA"}
        self.admin.command("PUT", f"/admin/roles/{role['id']}/permissions", body, role["id"], "ROLE", "ROLE_PERMISSIONS", version=detail["data"]["role"]["rowVersion"])
        client.call("GET", "/auth/me", expected=401)
        client.login()
        _, result, _ = client.call("GET", "/auth/me", expected=200)
        self.assertNotIn("DRIVE.ACCESS", result["data"]["actions"])
        # 恢复角色原配置；只保留本次验收样本，不把试验性拒绝规则留给后续开发。
        _, current, _ = self.admin.call("GET", f"/admin/roles/{role['id']}", expected=200)
        self.admin.command("PUT", f"/admin/roles/{role['id']}/permissions", {"permissions": detail["data"]["assignments"], "changeReason": "恢复验收前配置", "approvalRef": "QA"}, role["id"], "ROLE", "ROLE_PERMISSIONS", version=current["data"]["role"]["rowVersion"])

    def test_17_archive_initialization_does_not_grant_it_archive_power(self):
        status, result, _ = self.admin.call("GET", "/admin/roles?module=ARCHIVE&pageSize=100")
        if status == 403:
            # 重复运行时初始化例外已经关闭，这也是正确结果。
            self.admin.call("GET", "/admin/permissions?module=ARCHIVE", expected=403)
            return
        self.assertEqual(status, 200)
        role = next(r for r in result["data"]["items"] if r["code"] == "SUPER_ADMIN")
        user, client = self.create_user("archivist")
        self.assign(user, [role["id"]])
        client.login()
        self.admin.call("GET", "/admin/roles?module=ARCHIVE", expected=403)
        self.admin.call("GET", "/admin/permissions?module=ARCHIVE", expected=403)
        client.call("GET", "/admin/roles?module=ARCHIVE", expected=200)
        client.call("GET", "/admin/users", expected=403)
        client.call("GET", "/admin/users?projection=SELECTOR&purpose=ARCHIVE_GRANT", expected=200)
        params = urllib.parse.urlencode({"from": (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)).isoformat(), "to": (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=1)).isoformat(), "module": "ARCHIVE"})
        _, events, _ = client.call("GET", "/admin/audit-events?" + params, expected=200)
        self.assertTrue(any(row["action"] == "ASSIGN_ROLES" for row in events["data"]["items"]))
        self.admin.call("GET", "/admin/audit-events?" + params, expected=403)

    def test_18_idle_expiry_and_polling_do_not_extend_session(self):
        """只调整新建测试人员的时间，验证轮询、显式操作和超时三个分支。"""
        user, client = self.create_user("idle")
        identifier = str(uuid.UUID(user["id"]))
        isolated_sql(f"UPDATE iam.session SET last_activity_at=CURRENT_TIMESTAMP-interval '2 minutes' WHERE user_id='{identifier}' AND revoked_at IS NULL")
        statement = f"SELECT last_activity_at FROM iam.session WHERE user_id='{identifier}' AND revoked_at IS NULL"
        before = isolated_sql(statement)
        client.call("GET", "/auth/me", expected=200)
        self.assertEqual(isolated_sql(statement), before)
        client.call("GET", "/portal/home", headers={"X-User-Activity": "1"}, expected=200)
        self.assertNotEqual(isolated_sql(statement), before)
        isolated_sql(f"UPDATE iam.session SET last_activity_at=CURRENT_TIMESTAMP-interval '31 minutes' WHERE user_id='{identifier}' AND revoked_at IS NULL")
        client.call("GET", "/auth/me", expected=401)

    def test_19_database_rollback_cannot_restore_old_authorization(self):
        """模拟隔离账号授权代次回退，独立账本必须阻止重新登录取得旧权限。"""
        user, client = self.create_user("rollback")
        identifier = str(uuid.UUID(user["id"]))
        version = int(isolated_sql(f"SELECT auth_version FROM iam.user_account WHERE id='{identifier}'"))
        try:
            isolated_sql(f"UPDATE iam.user_account SET auth_version={version - 1} WHERE id='{identifier}'")
            client.login(expected=503)
        finally:
            isolated_sql(f"UPDATE iam.user_account SET auth_version={version} WHERE id='{identifier}'")
        client.login()

    def test_20_audit_cannot_be_rewritten_and_admin_can_revoke_session(self):
        """触发器阻止改写真实审计；管理员撤销会话后原 Cookie 立即失效。"""
        user, client = self.create_user("revoke")
        identifier = str(uuid.UUID(user["id"]))
        refusal = isolated_sql(f"UPDATE ops.audit_event SET action='QA_REWRITE' WHERE actor_id='{identifier}'", expect_failure=True)
        self.assertIn("append-only", refusal)
        _, current, _ = self.admin.call("GET", f"/admin/users/{identifier}", expected=200)
        headers = {"X-Operation-Id": str(uuid.uuid4()), "X-Reauth-Token": self.admin.reauth(identifier, "USER", "REVOKE_SESSION"),
                   "X-Expected-Version": str(current["data"]["rowVersion"]), "X-Change-Reason": "isolated-test"}
        self.admin.call("DELETE", f"/admin/users/{identifier}/session", headers=headers, expected=200)
        client.call("GET", "/auth/me", expected=401)

    def test_21_delegated_manager_cannot_grant_capability_they_do_not_have(self):
        """拥有角色管理动作仍不得借预置角色转授本人没有的能力。"""
        user, client = self.create_user("delegate")
        target, _ = self.create_user("target")
        role = self.roles["DRIVE"]["GENERAL_MANAGER"]
        _, original, _ = self.admin.call("GET", f"/admin/roles/{role['id']}", expected=200)
        _, dictionary, _ = self.admin.call("GET", "/admin/permissions?module=DRIVE", expected=200)
        permission = next(p for p in dictionary["data"]["items"] if p["code"] == "DRIVE.ROLES_MANAGE")
        body = {"permissions": [{"permissionId": permission["id"], "scopePolicy": {"code": "COMPANY"}, "effect": "ALLOW"}], "changeReason": "验证转授权上限", "approvalRef": "QA"}
        try:
            self.admin.command("PUT", f"/admin/roles/{role['id']}/permissions", body, role["id"], "ROLE", "ROLE_PERMISSIONS", version=original["data"]["role"]["rowVersion"])
            self.assign(user, [role["id"]])
            client.login()
            _, current, _ = client.call("GET", f"/admin/users/{target['id']}/roles", expected=200)
            client.command("PUT", f"/admin/users/{target['id']}/roles", {"roles": [{"roleId": self.roles["DRIVE"]["STAFF"]["id"]}], "reason": "验证", "approvalRef": "QA"}, target["id"], "USER", "ASSIGN_ROLES", version=current["data"]["authVersion"], expected=403)
        finally:
            _, current, _ = self.admin.call("GET", f"/admin/roles/{role['id']}", expected=200)
            self.admin.command("PUT", f"/admin/roles/{role['id']}/permissions", {"permissions": original["data"]["assignments"], "changeReason": "恢复验收前配置", "approvalRef": "QA"}, role["id"], "ROLE", "ROLE_PERMISSIONS", version=current["data"]["role"]["rowVersion"])


def main():
    """必须明确允许隔离写入，并在发请求前验证数据库与 HTTP 目标。"""
    # Windows 控制台和管道统一 UTF-8，中文验收说明不依赖本机代码页。
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--postgres-bin", required=True, help="仅供隔离故障注入的 PostgreSQL bin 目录")
    parser.add_argument("--allow-isolated-writes", action="store_true")
    args = parser.parse_args()
    global CONFIG, BASE, PG_BIN
    CONFIG = json.loads(Path(args.config).read_text(encoding="utf-8-sig"))
    if not args.allow_isolated_writes or not re.fullmatch(r"jdbc:postgresql://127\.0\.0\.1:\d+/ims_platform_(dev|test|trial)[a-z0-9_]*", CONFIG["IMS_PLATFORM_DB_URL"]):
        raise SystemExit("拒绝：仅允许显式授权的本机独立平台库")
    BASE = "https://127.0.0.1:" + CONFIG["IMS_PLATFORM_PORT"]
    PG_BIN = args.postgres_bin
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(PlatformApiTests)
    started = time.monotonic()
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    report = {"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors), "passed": result.wasSuccessful(), "seconds": round(time.monotonic() - started, 2), "database": CONFIG["IMS_PLATFORM_DB_URL"], "base": BASE}
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    raise SystemExit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
