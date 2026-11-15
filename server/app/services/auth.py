# -*- coding: utf-8 -*-
"""
app/services/auth.py
=====================
本文件实现「认证服务」：
- authenticate：校验账号密码，返回用户（或 None）。
- change_password：修改密码（验证旧密码后更新哈希）。
说明：密码验证使用 core.security 的 verify_password；令牌签发在 api/auth.py。
"""
from sqlalchemy.orm import Session

from app.core.security import hash_password, verify_password
from app.models.org import User


def authenticate(db: Session, username: str, password: str) -> User | None:
    """校验用户名与密码，成功返回 User，失败返回 None。"""
    user = db.query(User).filter_by(username=username, deleted_at=None).first()
    if not user:
        return None
    if user.account_status not in ("active", "pending"):
        # 被禁用/拒绝/锁定的账户不允许登录
        return None
    if not verify_password(password, user.password_hash):
        return None
    return user


def change_password(db: Session, user: User, old_pwd: str, new_pwd: str) -> bool:
    """修改密码：校验旧密码后写入新哈希。成功返回 True。"""
    if not verify_password(old_pwd, user.password_hash):
        return False
    if len(new_pwd) < 6:
        raise ValueError("新密码至少6位")
    user.password_hash = hash_password(new_pwd)
    return True
