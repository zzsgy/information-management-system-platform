# -*- coding: utf-8 -*-
"""
app/schemas/common.py
=====================
本文件定义通用 Schema：
- 分页参数 / 分页响应包装
- 统一 API 响应结构 {code, message, data}
说明：前端/客户端统一按此结构解析，便于错误处理与分页展示。
"""
from typing import Generic, Optional, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class PageParam(BaseModel):
    """分页查询入参。"""

    page: int = 1
    page_size: int = 20


class PageResult(BaseModel, Generic[T]):
    """分页响应：总数与当前页数据。"""

    total: int
    page: int
    page_size: int
    items: list[T]


class ApiResponse(BaseModel, Generic[T]):
    """统一返回结构。code=0 表示成功，非0表示业务错误。"""

    code: int = 0
    message: str = "ok"
    data: Optional[T] = None


def ok(data=None, message: str = "ok"):
    """构造成功响应。"""
    return ApiResponse(code=0, message=message, data=data)


def fail(message: str, code: int = 1):
    """构造失败响应。"""
    return ApiResponse(code=code, message=message, data=None)
