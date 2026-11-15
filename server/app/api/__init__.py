# -*- coding: utf-8 -*-
"""
app/api/__init__.py
====================
本文件汇总所有 API 路由模块，供 main.py 统一注册。
新增接口模块后，请在此 import 并加入 all_routers 列表。
"""
from app.api import (
    archives,
    approval,
    auth,
    borrows,
    damage,
    destroy,
    files,
    logs,
    notices,
    security_ops,
    system,
    users,
    web,
)

# 全部路由对象，main.py 会依次 include
all_routers = [
    auth.router,
    users.router,
    archives.router,
    files.router,
    borrows.router,
    destroy.router,
    damage.router,
    notices.router,
    logs.router,
    system.router,
    approval.router,
    security_ops.router,
    web.router,
]
