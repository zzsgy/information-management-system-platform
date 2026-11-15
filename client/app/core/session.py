# -*- coding: utf-8 -*-
"""
app/core/session.py
====================
客户端登录会话：保存当前令牌、用户信息与权限。
供各模块与 API 客户端共享，避免层层传参。
"""
import threading
from contextlib import contextmanager
from contextvars import ContextVar

# 登录会话状态（进程级单例）
_token = None
_user = None
_base_url = None
_permissions = []
_lock = threading.Lock()
_unbound = object()  # 区分无上下文和明确不带身份的登录请求。
_request_token = ContextVar("ims_request_token", default=_unbound)  # 后台任务发起时的身份。


def set_session(token: str, user: dict, base_url: str) -> None:
    """写入登录会话。"""
    global _token, _user, _base_url, _permissions
    with _lock:
        _token = token
        _user = dict(user or {})
        _base_url = base_url.rstrip("/") if base_url else base_url
        _permissions = list(user.get("permissions") or []) if user else []


def clear(expected_token=None) -> None:
    """清空会话；旧请求的 401 不得清除后来新登录的令牌。"""
    global _token, _user, _base_url, _permissions
    with _lock:
        if expected_token is not None and _token != expected_token:
            return
        _token = None
        _user = None
        _base_url = None
        _permissions = []


@contextmanager
def request_scope(token):
    """排队任务固定使用发起时的账号，避免切换账号后冒用新登录身份。"""
    marker = _request_token.set(token)  # 保存线程上下文以便退出时恢复。
    try:
        yield
    finally:
        _request_token.reset(marker)


def get_token() -> str:
    """后台线程读取绑定的令牌，界面线程读取当前会话。"""
    scoped = _request_token.get()  # 不透明 Bearer 令牌，不解析 JWT。
    if scoped is not _unbound:
        return scoped
    with _lock:
        return _token


def get_user() -> dict:
    """返回用户信息副本，避免调用方意外改动共享身份。"""
    with _lock:
        return dict(_user or {})


def get_base_url() -> str:
    """读取当前登录所在的服务地址。"""
    with _lock:
        return _base_url or ""


def get_permissions() -> list:
    """返回服务端授予的精确权限清单副本。"""
    with _lock:
        return list(_permissions)


def has_perm(*codes: str) -> bool:
    """判断当前用户是否拥有任一所需权限。"""
    if not codes:
        return True
    owned = get_permissions()  # 档案配置权限不等于全系统通行权限。
    if "*" in owned:
        return True
    return any(c in owned for c in codes)


def is_super_admin() -> bool:
    """是否为超级管理员（用于显示「系统管理」菜单）。"""
    roles = get_user().get("roles", [])  # 超级管理员只按实际角色识别。
    return "super_admin" in roles
