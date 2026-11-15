# -*- coding: utf-8 -*-
"""
app/core/styles.py
====================
客户端界面样式（QSS），支持「浅色 / 深色」双主题。
设计：用 __TOKEN__ 占位符编写模板，再按主题色板替换，得到最终 QSS 字符串。
说明：由 theme.py 在启动时调用 build_qss() 生成并应用；main.py 启动时应用。

两套色板（light / dark）统一变量名，保证布局与组件样式一致，仅颜色不同：
- 浅色：清爽专业蓝白风；深色：护眼深色风，主色仍用品牌蓝。
"""
# ===================== 两套色板 =====================
# 变量名统一，build_qss 用 __变量名大写__ 占位符替换。
THEME_VARS = {
    "light": {
        "BG_APP": "#f4f6f9",
        "BG_PANEL": "#ffffff",
        "BG_INPUT": "#ffffff",
        "BORDER": "#dce3ec",
        "TEXT": "#2c3e50",
        "TEXT_MUTED": "#6b7a8d",
        "PRIMARY": "#2f7fd1",
        "PRIMARY_HOVER": "#3a8ee0",
        "PRIMARY_PRESSED": "#286bb0",
        "PRIMARY_TEXT": "#ffffff",
        "DISABLED": "#b6c4d4",
        "DANGER": "#e25b4b",
        "DANGER_HOVER": "#ec6a5a",
        "GHOST_BG": "#e6edf5",
        "GHOST_TEXT": "#2f7fd1",
        "SEL_BG": "#cfe2f7",
        "SEL_TEXT": "#15314f",
        "ROW_HOVER": "#eef4fb",
        "HEADER_BG": "#eef2f7",
        "TAB_BG": "#e6edf5",
        "TITLE": "#1f2d3d",
        "GROUP_TITLE": "#2f7fd1",
        "STATUSBAR_BG": "#eef2f7",
        "SIDEBAR_BG": "#ffffff",
        "SIDEBAR_TEXT": "#2c3e50",
        "SIDEBAR_BORDER": "#e3e8ef",
        "SIDEBAR_HOVER": "#eef2f7",
        "SIDEBAR_SEL": "#2f7fd1",
        "USERCARD_BG": "#ffffff",
        "SETBTN_TEXT": "#6b7a8d",
    },
    "dark": {
        "BG_APP": "#1b212b",
        "BG_PANEL": "#232b36",
        "BG_INPUT": "#2a323d",
        "BORDER": "#353f4c",
        "TEXT": "#d6dde6",
        "TEXT_MUTED": "#8a96a6",
        "PRIMARY": "#3a8ee0",
        "PRIMARY_HOVER": "#4d9be8",
        "PRIMARY_PRESSED": "#2f7fd1",
        "PRIMARY_TEXT": "#ffffff",
        "DISABLED": "#4a5562",
        "DANGER": "#e25b4b",
        "DANGER_HOVER": "#ec6a5a",
        "GHOST_BG": "#2a3340",
        "GHOST_TEXT": "#9fc3ec",
        "SEL_BG": "#2f4a63",
        "SEL_TEXT": "#ffffff",
        "ROW_HOVER": "#2a3340",
        "HEADER_BG": "#2a323d",
        "TAB_BG": "#232b36",
        "TITLE": "#eaf0f7",
        "GROUP_TITLE": "#6db0ef",
        "STATUSBAR_BG": "#232b36",
        "SIDEBAR_BG": "#161b22",
        "SIDEBAR_TEXT": "#c7d0db",
        "SIDEBAR_BORDER": "#2a323d",
        "SIDEBAR_HOVER": "#232b36",
        "SIDEBAR_SEL": "#2f7fd1",
        "USERCARD_BG": "#161b22",
        "SETBTN_TEXT": "#9aa7b6",
    },
}


def build_qss(theme_name: str = "light") -> str:
    """根据主题名（light/dark）生成最终 QSS 字符串。"""
    v = THEME_VARS.get(theme_name, THEME_VARS["light"])
    tpl = """
QWidget {
    font-family: "Microsoft YaHei", "SimHei", sans-serif;
    font-size: 12px;
    color: __TEXT__;
}
QMainWindow, QDialog {
    background-color: __BG_APP__;
}
QLineEdit, QComboBox, QSpinBox, QDateEdit, QTextEdit, QPlainTextEdit {
    border: 1px solid __BORDER__;
    border-radius: 4px;
    padding: 4px 6px;
    background: __BG_INPUT__;
    color: __TEXT__;
    min-height: 20px;
}
QLineEdit:focus, QComboBox:focus, QTextEdit:focus, QDateEdit:focus, QSpinBox:focus {
    border: 1px solid __PRIMARY__;
}
QPushButton {
    background-color: __PRIMARY__;
    color: __PRIMARY_TEXT__;
    border: none;
    border-radius: 4px;
    padding: 6px 16px;
    min-width: 76px;
    text-align: center;
}
QPushButton:hover { background-color: __PRIMARY_HOVER__; }
QPushButton:pressed { background-color: __PRIMARY_PRESSED__; }
QPushButton:disabled { background-color: __DISABLED__; color: __TEXT_MUTED__; }
QPushButton#danger { background-color: __DANGER__; }
QPushButton#danger:hover { background-color: __DANGER_HOVER__; }
QPushButton#ghost { background-color: __GHOST_BG__; color: __GHOST_TEXT__; }
QPushButton#ghost:hover { background-color: __PRIMARY_HOVER__; color: __PRIMARY_TEXT__; }
QTableWidget {
    gridline-color: __BORDER__;
    background: __BG_PANEL__;
    border: 1px solid __BORDER__;
    selection-background-color: __SEL_BG__;
    selection-color: __SEL_TEXT__;
}
QTableWidget::item:selected {
    background: __SEL_BG__;
    color: __SEL_TEXT__;
}
QTableWidget::item:hover:!selected {
    background: __ROW_HOVER__;
}
QHeaderView::section {
    background-color: __HEADER_BG__;
    color: __TEXT__;
    border: none;
    border-bottom: 1px solid __BORDER__;
    padding: 6px 8px;
    font-weight: bold;
}
QTreeWidget {
    background: __BG_PANEL__;
    border: 1px solid __BORDER__;
    selection-background-color: __SEL_BG__;
    selection-color: __SEL_TEXT__;
}
QTreeWidget::item:selected { background: __PRIMARY__; color: #ffffff; }
QTabWidget::pane { border: 1px solid __BORDER__; top: -1px; }
QTabBar::tab {
    background: __TAB_BG__;
    color: __TEXT_MUTED__;
    padding: 7px 16px;
    border: 1px solid __BORDER__;
    border-bottom: none;
    border-top-left-radius: 4px; border-top-right-radius: 4px;
}
QTabBar::tab:selected { background: __BG_PANEL__; color: __PRIMARY__; }
QLabel#title { font-size: 15px; font-weight: bold; color: __TITLE__; }
QLabel#error { color: __DANGER__; }
QGroupBox {
    border: 1px solid __BORDER__;
    border-radius: 6px;
    margin-top: 10px;
    padding-top: 10px;
}
QGroupBox::title { subcontrol-origin: margin; left: 12px; padding: 0 6px; color: __GROUP_TITLE__; font-weight: bold; }
QStatusBar { background: __STATUSBAR_BG__; color: __TEXT__; }
QListWidget#SideNav {
    border: none;
    background: __SIDEBAR_BG__;
    color: __SIDEBAR_TEXT__;
    outline: 0;
}
QListWidget#SideNav::item {
    padding: 12px 16px;
    border-bottom: 1px solid __SIDEBAR_BORDER__;
}
QListWidget#SideNav::item:selected {
    background: __SIDEBAR_SEL__;
    color: #ffffff;
}
QListWidget#SideNav::item:hover:!selected {
    background: __SIDEBAR_HOVER__;
}
QWidget#UserCard {
    background: __USERCARD_BG__;
    border-top: 1px solid __SIDEBAR_BORDER__;
}
QLabel#UserName { color: __SIDEBAR_TEXT__; font-size: 13px; }
QLabel#UserAvatar {
    border-radius: 21px;
    background: __PRIMARY__;
    color: __PRIMARY_TEXT__;
    font-weight: bold;
    font-size: 16px;
    qproperty-alignment: AlignCenter;
}
QPushButton#UserSetBtn {
    color: __SETBTN_TEXT__;
    background: transparent;
    border: none;
    text-align: left;
    padding: 2px 0;
}
QPushButton#UserSetBtn:hover { color: __PRIMARY__; }
QDialogButtonBox { dialogbuttonbox-buttons-have-icons: 0; }
"""
    s = tpl
    for key, val in v.items():
        s = s.replace("__" + key.upper() + "__", val)
    return s


# 兼容旧引用：默认浅色样式
QSS = build_qss("light")
