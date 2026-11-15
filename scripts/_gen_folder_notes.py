#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
================================================================
文件用途：一次性脚本 —— 为工程各文件夹生成「a说明.txt」
        说明本文件夹的作用，供新手了解工程结构。
运行方式：python _gen_folder_notes.py（在本目录下执行一次即可）
执行完可删除本文件。
================================================================
"""
import os

# 工程根目录 = 本脚本所在目录的上一级（scripts/）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# { 相对路径: 说明文字 }，相对路径基于 ArchiveSystem 根目录
NOTES = {
    # ---------- 根级 ----------
    ".": (
        "【这是总工程根目录】\n"
        "项目名称：企业档案管理系统（ArchiveSystem）\n"
        "架构：C/S 模式。server/ 是服务端（FastAPI），client/ 是桌面客户端（PySide6）。\n"
        "客户端不直连数据库，全部通过服务端 REST API 访问数据。\n"
        "最终交付物：服务端安装程序 + 客户端安装程序（installer/ 目录下输出）。\n"
    ),
    "docs": "存放项目文档：设计说明、API 文档等。\n",
    "docs/api": "存放 API 接口文档（接口清单、请求/响应示例、调试记录）。\n",
    "docs/design": "存放设计类文档：数据库 ER 图说明、页面原型说明、架构图等。\n",
    "installer": "安装包相关总目录：打包脚本、最终产出的安装程序都放这里。\n",
    "installer/server_release": "【最终产物】服务端安装程序（exe/msi）输出目录，由 Inno Setup 打包后放入。\n",
    "installer/client_release": "【最终产物】客户端安装程序（ArchiveClientSetup.exe）输出目录。\n",
    "backups": "开发期备份目录：数据库备份与电子文件备份分开存放。\n",
    "backups/db": "存放 PostgreSQL 数据库备份文件（pg_dump 导出的 .sql/.dump）。\n",
    "backups/files": "存放档案电子文件的备份归档（与 server/storage 的正式存储区分开）。\n",
    "scripts": "运维/辅助脚本目录：建库、备份、启动停止等批处理和 Python 小工具。\n",
    "scripts/db": "数据库相关脚本：初始化建库 SQL、种子数据导入、备份恢复脚本等。\n",
    # ---------- 服务端 ----------
    "server": (
        "【服务端根目录】\n"
        "技术栈：Python 3.13 + FastAPI + SQLAlchemy 2.0 + Alembic + PostgreSQL。\n"
        "虚拟环境在 server/.venv（依赖已装好）。入口文件是 app/main.py。\n"
        "启动命令见 run_server.bat（后续创建）。\n"
    ),
    "server/app": "服务端应用代码包。所有业务代码都在这个包里，按职责分层组织子目录。\n",
    "server/app/core": (
        "核心配置层：\n"
        "config.py   —— 全局配置读取（.env 环境变量、数据库连接串等）\n"
        "security.py —— 密码哈希、JWT 令牌签发与校验\n"
        "logging.py  —— 日志配置\n"
    ),
    "server/app/db": (
        "数据库连接层：\n"
        "session.py —— 数据库会话（Session）管理\n"
        "base.py    —— ORM 声明基类\n"
        "seed.py    —— 初始化数据（首个管理员账号、字典数据等）\n"
    ),
    "server/app/models": "ORM 模型层：每个数据库表对应一个模型类（如 archive.py 对应档案主表）。\n",
    "server/app/schemas": "Pydantic 模式层：定义 API 请求/响应的数据格式并自动校验，不直接操作数据库。\n",
    "server/app/repositories": "数据访问层：封装数据库增删改查，service 层通过它读写数据，路由层不直接碰库。\n",
    "server/app/services": "业务逻辑层：实现具体业务规则（如借阅审批流转、自动编号生成），被 api 层调用。\n",
    "server/app/api": "路由层（API 接口）：按模块拆分文件（auth.py 登录认证、archives.py 档案、borrows.py 借阅等），定义 URL 和请求方法。\n",
    "server/app/utils": "通用工具函数：文件 SHA256 校验、分页参数处理、时间格式化等小工具。\n",
    "server/migrations": "Alembic 数据库迁移目录：管理表结构的版本变更历史。\n",
    "server/migrations/versions": "存放每次迁移生成的版本脚本（一个 py 文件代表一次表结构变更）。\n",
    "server/storage": (
        "档案电子文件存储根目录。\n"
        "重要约定：电子文件本体不入数据库，库里只存元数据和路径标识。\n"
    ),
    "server/storage/archive_files": "档案电子文件正式存储位置（按年份/分类分目录存放上传的 PDF、扫描件等）。\n",
    "server/storage/tmp": "上传临时区：文件先传到这里校验（SHA256、类型检查），通过后再移入正式目录。\n",
    "server/tests": "服务端自动化测试代码（pytest 用例：接口测试、权限测试等）。\n",
    # ---------- 客户端 ----------
    "client": (
        "【客户端根目录】\n"
        "技术栈：Python 3.13 + PySide6（Qt 桌面界面）+ requests（调服务端 API）。\n"
        "虚拟环境在 client/.venv（依赖已装好）。入口文件是 app/main.py。\n"
        "注意：客户端不直连数据库！所有数据操作都走服务端 HTTP 接口。\n"
    ),
    "client/app": "客户端应用代码包。界面、接口调用、配置等都在这个包里。\n",
    "client/app/config": "客户端配置模块：服务端地址（IP+端口）、界面偏好等配置的读取与保存。\n",
    "client/app/api": "网络请求模块：封装对服务端 REST API 的调用（登录、查询档案、上传下载等），统一处理 Token。\n",
    "client/app/models": "客户端数据模型：与服务端返回数据对应的 Pydantic 类，用于界面展示前的数据转换。\n",
    "client/app/views": (
        "页面视图层（核心目录）：每个窗口/页面对应一个文件，例如：\n"
        "login.py 登录窗、dashboard.py 工作台、archive_list.py 档案列表、\n"
        "archive_detail.py 档案详情、borrow.py 借阅、settings.py 设置。\n"
    ),
    "client/app/widgets": "可复用界面控件：自定义表格、分页条、弹窗确认框、文件预览组件等，供多个页面共用。\n",
    "client/app/services": "客户端后台任务服务：用 QThread 处理耗时操作（大文件上传下载），避免卡死界面。\n",
    "client/app/utils": "客户端工具函数：Token 存取、时间显示格式化、文件大小格式化等。\n",
    "client/resources": "客户端资源目录：图标、样式表等静态资源。\n",
    "client/resources/icons": "界面图标（png/svg）：窗口图标、按钮图标等。\n",
    "client/resources/styles": "QSS 样式表：统一控制界面配色、字体、控件外观（类似网页的 CSS）。\n",
    "client/tests": "客户端测试代码（界面逻辑、API 封装层的单元测试）。\n",
    "client/build": "PyInstaller 打包工作目录：打包过程的临时产物（build/dist）在这里生成。\n",
    "client/installer": "客户端打包配置：Inno Setup 脚本（.iss）、打包用的图标和许可证文本。\n",
}

SKIP_DIR_NAMES = {".venv", "__pycache__", "node_modules", ".git"}

def write_note(rel: str) -> None:
    """把说明写入 <目录>/a说明.txt；已存在则跳过，避免覆盖人工修改。"""
    target_dir = os.path.join(ROOT, rel) if rel != "." else ROOT
    note_path = os.path.join(target_dir, "a说明.txt")
    if os.path.exists(note_path):
        print(f"跳过（已存在）: {note_path}")
        return
    with open(note_path, "w", encoding="utf-8") as f:
        f.write(NOTES[rel])
    print(f"已写入: {note_path}")

def main() -> None:
    """遍历 NOTES 中登记的所有目录，逐个写入说明文件。"""
    for rel in NOTES:
        write_note(rel)
    print("\n完成。")

if __name__ == "__main__":
    main()
