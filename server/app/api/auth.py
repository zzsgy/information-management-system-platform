# -*- coding: utf-8 -*-
"""
app/api/auth.py
================
本文件提供「认证接口」：
- POST /api/auth/login      登录（账号密码 → 返回 JWT）
- POST /api/auth/change-password  修改密码
- GET  /api/auth/me         获取当前用户信息
说明：登录成功签发 JWT 访问令牌；客户端后续请求在 Header 携带 Authorization: Bearer <token>。
"""
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.core.security import create_access_token
from app.db.session import get_db
from app.deps import get_current_user
from app.models.org import Role, User, UserRole
from app.schemas.common import ApiResponse, ok
from app.services import auth as auth_svc
from app.services import log as log_svc

router = APIRouter(prefix="/api/auth", tags=["认证"])


class LoginIn(BaseModel):
    username: str
    password: str


class ChangePwdIn(BaseModel):
    old_password: str
    new_password: str


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else None


@router.post("/login", response_model=ApiResponse)
def login(body: LoginIn, request: Request, db: Session = Depends(get_db)):
    """登录：校验账号密码，成功返回访问令牌。"""
    user = auth_svc.authenticate(db, body.username, body.password)
    if not user:
        # 记录一次失败登录（不暴露具体原因）
        log_svc.write_operation(
            db, user_id=None, module="auth", action="login_fail",
            ip=_client_ip(request), detail=f"user={body.username}",
        )
        db.commit()
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    role_codes = [
        r.role_code
        for r in db.query(Role)
        .join(UserRole, UserRole.role_id == Role.id)
        .filter(UserRole.user_id == user.id)
        .all()
    ]
    token = create_access_token(user.id, user.username, role_codes)
    user.last_login_at = datetime.now()
    log_svc.write_operation(
        db, user_id=user.id, module="auth", action="login",
        ip=_client_ip(request), detail="登录成功",
    )
    db.commit()
    return ok({
        "access_token": token,
        "token_type": "bearer",
        "user_id": user.id,
        "username": user.username,
        "real_name": user.real_name,
        "roles": role_codes,
    })


@router.post("/change-password", response_model=ApiResponse)
def change_password(
    body: ChangePwdIn,
    request: Request,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """修改当前登录用户密码。"""
    try:
        ok_flag = auth_svc.change_password(db, user, body.old_password, body.new_password)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    if not ok_flag:
        raise HTTPException(status_code=400, detail="原密码错误")
    log_svc.write_operation(
        db, user_id=user.id, module="auth", action="change_password",
        ip=_client_ip(request),
    )
    db.commit()
    return ok(message="密码修改成功")


@router.get("/me", response_model=ApiResponse)
def me(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    """返回当前登录用户基本信息与角色。"""
    role_codes = getattr(user, "_role_codes", [])
    return ok({
        "user_id": user.id,
        "username": user.username,
        "real_name": user.real_name,
        "dept_id": user.dept_id,
        "account_status": user.account_status,
        "roles": role_codes,
    })
