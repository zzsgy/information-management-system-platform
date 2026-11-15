"""核验缺少领域授权和写保护适配器时的真实 HTTPS 拒绝，不产生任何业务文件。"""
import argparse
import json
import re
import ssl
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def main():
    """仅允许明确指定的本机隔离库；不向报告输出密码、Cookie、原文或内网路径。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--allow-isolated-writes", action="store_true")
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8-sig"))
    if not args.allow_isolated_writes or not re.fullmatch(r"jdbc:postgresql://127\.0\.0\.1:\d+/ims_platform_(dev|test|trial)[a-z0-9_]*", config["IMS_PLATFORM_DB_URL"]):
        raise SystemExit("拒绝：测试必须指定本机隔离库")
    base = "https://127.0.0.1:" + config["IMS_PLATFORM_PORT"]
    context = ssl._create_unverified_context()  # 仅本机自签开发证书。
    login = urllib.request.Request(base + "/api/v1/auth/login", data=json.dumps({"employeeNo": config["IMS_PLATFORM_ADMIN_NO"], "password": config["IMS_PLATFORM_ADMIN_PASSWORD"]}).encode(), headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(login, context=context, timeout=20) as response:
        csrf = json.loads(response.read())["data"]["csrfToken"]
        cookie = response.headers["Set-Cookie"].split(";", 1)[0]
    body = {"module": "ARCHIVE", "businessContextId": str(uuid.uuid4()), "originalName": "隔离验证.pdf", "expectedSize": 1, "expectedSha256": "0" * 64, "expectedMediaType": "application/pdf", "purpose": "ARCHIVE_PDF"}
    request = urllib.request.Request(base + "/api/v1/storage/upload-sessions", data=json.dumps(body).encode(), method="POST", headers={"Content-Type": "application/json", "Cookie": cookie, "X-CSRF-Token": csrf, "X-Operation-Id": str(uuid.uuid4())})
    try:
        response = urllib.request.urlopen(request, context=context, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    result = json.loads(response.read())
    if response.status != 503 or result.get("code") != "PROTECTION_NOT_READY":
        raise SystemExit(f"拒绝边界验证失败：HTTP {response.status}，code={result.get('code')}")
    # 二进制分片的专用上限不能被旧 64 KiB 规则误拦截；不存在的目标仍须返回不可见。
    part = base + "/api/v1/storage/upload-sessions/" + str(uuid.uuid4()) + "/parts/1"
    request = urllib.request.Request(part, data=b"x" * 65537, method="PUT", headers={"Content-Type": "application/octet-stream", "Cookie": cookie, "X-CSRF-Token": csrf})
    try:
        response = urllib.request.urlopen(request, context=context, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    if response.status != 404:
        raise SystemExit(f"分片大小边界验证失败：HTTP {response.status}")
    # 不支持的请求媒体类型应明确返回 415，不能伪装成服务保护故障。
    request = urllib.request.Request(part, data=b"{}", method="PUT", headers={"Content-Type": "application/json", "Cookie": cookie, "X-CSRF-Token": csrf})
    try:
        response = urllib.request.urlopen(request, context=context, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    if response.status != 415:
        raise SystemExit(f"媒体类型边界验证失败：HTTP {response.status}")
    print("通过：领域授权缺失拒绝创建，分片专用上限和媒体类型拒绝符合预期。")


if __name__ == "__main__":
    main()
