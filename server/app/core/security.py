# -*- coding: utf-8 -*-
"""
app/core/security.py
=====================
本文件负责「认证与安全」相关的基础工具：
1. 密码哈希：只存哈希不存明文（使用 passlib 的 pbkdf2_sha256，纯 Python 实现）。
2. JWT 令牌：登录成功后签发短期访问令牌；提供校验函数。
3. 依赖注入工具：供 API 层校验请求头中的令牌并解析当前用户。
说明：令牌采用 HS256 对称算法，密钥来自配置 JWT_SECRET。
"""
from datetime import datetime, timedelta, timezone

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from passlib.context import CryptContext

from app.core.config import settings

# 密码哈希上下文（不依赖 bcrypt 二进制包，使用 pbkdf2_sha256）
pwd_context = CryptContext(schemes=["pbkdf2_sha256"], deprecated="auto")

# OAuth2 密码模式：告诉 FastAPI 去哪里取 token（即 /auth/login 接口）
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/auth/login", auto_error=False)


def hash_password(password: str) -> str:
    """对明文密码进行哈希，返回可存储的字符串。"""
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    """校验明文密码与哈希是否匹配。"""
    try:
        return pwd_context.verify(plain, hashed)
    except Exception:
        return False


def create_access_token(user_id: int, username: str, role_codes: list[str]) -> str:
    """生成 JWT 访问令牌，携带用户标识与角色，便于接口层做权限判断。"""
    expire = datetime.now(timezone.utc) + timedelta(
        minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES
    )
    payload = {
        "sub": str(user_id),       # 用户 ID
        "username": username,
        "roles": role_codes,        # 角色编码列表
        "exp": expire,
    }
    return jwt.encode(payload, settings.JWT_SECRET, algorithm=settings.JWT_SECRET_ALGORITHM if hasattr(settings, "JWT_SECRET_ALGORITHM") else settings.JWT_ALGORITHM)


def decode_access_token(token: str) -> dict:
    """解析并校验 JWT；失败抛出 401。"""
    try:
        return jwt.decode(
            token, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM]
        )
    except jwt.PyJWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="无效或已过期的令牌"
        )


# 全局异常：令牌缺失或无效时返回 401
_credentials_exc = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED, detail="未提供有效认证凭据"
)
