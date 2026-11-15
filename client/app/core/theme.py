# -*- coding: utf-8 -*-
"""
app/core/theme.py
==================
主题管理：浅色 / 深色 / 跟随系统。
提供：
- THEME_LABELS：三档主题的中文名。
- resolve_theme(name)：system 时读取 Windows 个性化设置判断深浅。
- apply_theme(app, name) / apply_saved_theme(app)：生成并应用 QSS。
说明：色板（THEME_VARS）定义在 styles.py；偏好（theme 取值）持久化在 app.core.prefs。
      跟随系统仅「启动时」判定一次，如需实时跟随可后续接入 WM_SETTINGCHANGE。
"""
import winreg

from PySide6.QtWidgets import QApplication

from app.core.styles import build_qss
from app.core import prefs


# 三档主题的中文名
THEME_LABELS = {"light": "浅色", "dark": "深色", "system": "跟随系统"}


def is_windows_dark() -> bool:
    """读取 Windows「应用模式」是否深色（AppsUseLightTheme=0 为深色）。"""
    try:
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
        )
        val, _ = winreg.QueryValueEx(key, "AppsUseLightTheme")
        return val == 0
    except Exception:
        return False


def resolve_theme(name: str) -> str:
    """把 system 解析为具体 light/dark；其余原样返回（非法值回退浅色）。"""
    if name == "system":
        return "dark" if is_windows_dark() else "light"
    return name if name in ("light", "dark") else "light"


def apply_theme(app: QApplication, name: str):
    """生成对应主题的 QSS 并应用到整个应用。"""
    qss = build_qss(resolve_theme(name))
    app.setStyleSheet(qss)
    app.setProperty("theme", name)


def apply_saved_theme(app: QApplication):
    """读取本地偏好并应用主题（默认跟随系统）。"""
    name = prefs.get_pref("theme", "system") or "system"
    apply_theme(app, name)


def set_theme(name: str):
    """切换主题并持久化；返回是否成功。"""
    if name not in THEME_LABELS:
        return False
    prefs.set_pref("theme", name)
    app = QApplication.instance()
    if app is not None:
        apply_theme(app, name)
    return True
