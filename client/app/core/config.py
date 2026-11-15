# -*- coding: utf-8 -*-
"""地址配置优先环境变量、用户配置，最后兼容安装目录 .env；不保存密码与令牌。"""
import json
import os
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import dotenv_values


def _base_dir() -> Path:
    """定位旧 .env；仅兼容读取，不再写入可能只读的安装目录。"""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent.parent


BASE_DIR = _base_dir()  # 开发源码或安装程序目录。
ENV_PATH = BASE_DIR / ".env"  # 旧版地址配置的兼容读取位置。
DEFAULT_SERVER_URL = "http://127.0.0.1:8000"  # 本机 Java 服务默认地址。


def user_config_path() -> Path:
    """Windows 使用 LocalAppData，Linux 使用 XDG_CONFIG_HOME，均按用户隔离。"""
    custom = os.getenv("IMS_CLIENT_CONFIG_DIR")  # 可配置便携安装的用户可写目录。
    if custom:
        return Path(custom).expanduser() / "client.json"
    if os.name == "nt":
        root = Path(os.getenv("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
    else:
        root = Path(os.getenv("XDG_CONFIG_HOME") or Path.home() / ".config")
    return root / "InformationManagementSystem" / "client.json"


def normalize_server_url(url: str) -> str:
    """验证 HTTP(S) 地址；拒绝嵌入密码、查询参数、换行或无效端口。"""
    value = (url or "").strip().rstrip("/") or DEFAULT_SERVER_URL  # 规范化接口拼接前缀。
    parsed = urlsplit(value)  # 解析主机与协议以阻止无效地址写入配置。
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or any(char.isspace() for char in value)):
        raise ValueError("服务器地址须为 http:// 或 https:// 地址，不能包含账号、空白或查询参数")
    try:
        parsed.port  # 触发标准库的端口合法性检查。
    except ValueError as error:
        raise ValueError("服务器端口无效") from error
    return value


def load_config() -> dict:
    """读取有效地址，不将 .env 注入进程环境，避免旧地址覆盖刚保存的新地址。"""
    saved = {}  # 当前操作系统用户保存的 JSON 配置。
    try:
        saved = json.loads(user_config_path().read_text(encoding="utf-8"))
        if not isinstance(saved, dict):
            saved = {}
    except (OSError, ValueError):
        pass
    legacy = dotenv_values(ENV_PATH) if ENV_PATH.is_file() else {}  # 兼容已有部署的 .env。
    url = os.getenv("SERVER_URL") or saved.get("SERVER_URL") or legacy.get("SERVER_URL")
    return {"SERVER_URL": normalize_server_url(url or DEFAULT_SERVER_URL)}


def save_server_url(url: str) -> None:
    """原子写入用户配置；不改旧 .env，失败时保留原文件。"""
    value = normalize_server_url(url)  # 写入前验证地址。
    path = user_config_path()  # 用户可写的配置路径。
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix="client-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump({"SERVER_URL": value}, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
