# -*- coding: utf-8 -*-
"""
app/deps.py
============
本文件定义 FastAPI 的「依赖注入」：
- get_current_user：解析请求头 Bearer 令牌，加载当前用户对象（含角色/权限）。
- require_permissions：接口级权限校验依赖，未授权返回 403。
说明：所有受保护接口通过 Depends(get_current_user) 获取当前用户，
      通过 Depends(require_permissions("archive.read")) 做功能权限控制。
"""
from typing import Optional

from fastapi import Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.core.security import decode_access_token, oauth2_scheme
from app.db.session import get_db
from app.models.org import Permission, Role, RolePermission, User, UserRole


def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db),
) -> User:
    """解析令牌并返回当前登录用户；同时把角色/权限挂在 user 上供后续使用。"""
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录或令牌缺失"
        )
    payload = decode_access_token(token)
    user_id = int(payload.get("sub"))
    user = db.get(User, user_id)
    if not user or user.deleted_at is not None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="用户不存在或已禁用"
        )
    # 加载角色编码与权限编码，便于接口层判断
    role_ids = [
        ur.role_id for ur in db.query(UserRole).filter_by(user_id=user.id).all()
    ]
    perms = (
        db.query(Permission.perm_code)
        .join(RolePermission, RolePermission.permission_id == Permission.id)
        .filter(RolePermission.role_id.in_(role_ids))
        .distinct()
        .all()
    )
    user._role_codes = [r.role_code for r in db.query(Role).filter(Role.id.in_(role_ids)).all()]
    user._perm_codes = [p[0] for p in perms]
    return user


def require_permissions(*required: str):
    """返回一个依赖函数，校验当前用户是否拥有 required 中的任一权限。"""

    def checker(user: User = Depends(get_current_user)) -> User:
        owned = getattr(user, "_perm_codes", [])
        # 超级管理员（拥有 * 或 archive.admin）直接放行；否则需匹配任一权限
        if "*" in owned or "archive.admin" in owned:
            return user
        if not required or any(p in owned for p in required):
            return user
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="权限不足"
        )

    return checker
