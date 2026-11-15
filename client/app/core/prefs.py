# -*- coding: utf-8 -*-
"""
app/core/prefs.py
===================
客户端本地偏好持久化（不依赖服务端）。
用途：保存界面主题（浅色/深色/跟随系统）、档案清单列配置等。
说明：偏好文件存放于系统「应用配置目录」（Windows 为
%AppData%/ArchiveSystem/ 下），按当前 Windows 用户隔离，无需管理员权限。
"""
import json
import os

from PySide6.QtCore import QStandardPaths, QDir


def _prefs_path() -> str:
    """返回偏好文件路径，并确保所在目录存在。"""
    loc = QStandardPaths.writableLocation(QStandardPaths.AppConfigLocation)
    if not loc:
        loc = os.path.join(os.path.expanduser("~"), "ArchiveSystemClient")
    QDir().mkpath(loc)
    return os.path.join(loc, "prefs.json")


def load_prefs() -> dict:
    """读取全部偏好，文件不存在/损坏时返回空字典。"""
    try:
        with open(_prefs_path(), "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_prefs(data: dict):
    """全量保存偏好字典。"""
    try:
        with open(_prefs_path(), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def get_pref(key, default=None):
    """读取单个偏好项。"""
    return load_prefs().get(key, default)


def set_pref(key, value):
    """写入单个偏好项并持久化。"""
    data = load_prefs()
    data[key] = value
    save_prefs(data)
