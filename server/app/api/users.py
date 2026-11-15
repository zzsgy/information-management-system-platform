# -*- coding: utf-8 -*-
"""
app/api/users.py
=================
本文件提供「用户 / 部门 / 角色 / 权限」管理接口：
- 部门：列表、创建、更新
- 用户：列表、创建、更新、禁用
- 角色：列表、创建（绑定权限）
- 权限：列表
说明：均受权限依赖保护，仅具备对应权限的角色可操作。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.db.seed import PERMISSION_DESCRIPTIONS
from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.org import (
    Department, Permission, Role, RolePermission, User, UserRole,
)
from app.schemas.common import ApiResponse, ok
from app.schemas.org import (
    DepartmentCreate, DepartmentOut, DepartmentUpdate,
    RoleCreate, RoleOut, UserCreate, UserOut, UserUpdate,
)

router = APIRouter(prefix="/api", tags=["用户与组织"])


# ---------------- 部门 ----------------
@router.get("/departments", response_model=ApiResponse)
def list_departments(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.read")),
):
    rows = db.query(Department).filter(Department.deleted_at.is_(None)).order_by(Department.sort_order).all()
    return ok([DepartmentOut.model_validate(r).model_dump() for r in rows])


@router.post("/departments", response_model=ApiResponse)
def create_department(
    body: DepartmentCreate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.write")),
):
    if db.query(Department).filter_by(dept_code=body.dept_code).first():
        raise HTTPException(status_code=400, detail="部门编码已存在")
    d = Department(**body.model_dump())
    db.add(d)
    db.commit()
    return ok(DepartmentOut.model_validate(d).model_dump(), "部门创建成功")


@router.put("/departments/{dept_id}", response_model=ApiResponse)
def update_department(
    dept_id: int, body: DepartmentUpdate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.write")),
):
    d = db.get(Department, dept_id)
    if not d:
        raise HTTPException(status_code=404, detail="部门不存在")
    for k, v in body.model_dump(exclude_unset=True).items():
        if v is not None:
            setattr(d, k, v)
    db.commit()
    return ok(message="部门更新成功")


# ---------------- 用户 ----------------
@router.get("/users", response_model=ApiResponse)
def list_users(
    page: int = 1, page_size: int = 20,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.read")),
):
    q = db.query(User).filter(User.deleted_at.is_(None))
    total = q.with_entities(func.count()).scalar() or 0
    rows = q.offset((page - 1) * page_size).limit(page_size).all()
    return ok({
        "total": total,
        "items": [UserOut.model_validate(r).model_dump() for r in rows],
    })


@router.post("/users", response_model=ApiResponse)
def create_user(
    body: UserCreate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.write")),
):
    if db.query(User).filter_by(username=body.username).first():
        raise HTTPException(status_code=400, detail="登录账号已存在")
    user = User(
        username=body.username,
        password_hash=hash_password(body.password),
        real_name=body.real_name,
        dept_id=body.dept_id,
        employee_no=body.employee_no,
        phone=body.phone,
        email=body.email,
        account_status="active",
    )
    db.add(user)
    db.flush()
    for rid in body.role_ids:
        db.add(UserRole(user_id=user.id, role_id=rid, scope_type="ALL", scope_value="*"))
    db.commit()
    return ok(UserOut.model_validate(user).model_dump(), "用户创建成功")


@router.put("/users/{user_id}", response_model=ApiResponse)
def update_user(
    user_id: int, body: UserUpdate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.write")),
):
    user = db.get(User, user_id)
    if not user or user.deleted_at is not None:
        raise HTTPException(status_code=404, detail="用户不存在")
    data = body.model_dump(exclude_unset=True)
    role_ids = data.pop("role_ids", None)
    for k, v in data.items():
        if v is not None:
            setattr(user, k, v)
    if role_ids is not None:
        db.query(UserRole).filter_by(user_id=user.id).delete()
        for rid in role_ids:
            db.add(UserRole(user_id=user.id, role_id=rid, scope_type="ALL", scope_value="*"))
    db.commit()
    return ok(message="用户更新成功")


@router.post("/users/{user_id}/disable", response_model=ApiResponse)
def disable_user(
    user_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.write")),
):
    user = db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    user.account_status = "disabled"
    db.commit()
    return ok(message="用户已禁用")


# ---------------- 角色 / 权限 ----------------
@router.get("/roles", response_model=ApiResponse)
def list_roles(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.read")),
):
    rows = db.query(Role).all()
    return ok([RoleOut.model_validate(r).model_dump() for r in rows])


@router.post("/roles", response_model=ApiResponse)
def create_role(
    body: RoleCreate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.write")),
):
    if db.query(Role).filter_by(role_code=body.role_code).first():
        raise HTTPException(status_code=400, detail="角色编码已存在")
    role = Role(role_code=body.role_code, role_name=body.role_name,
                role_level=body.role_level, status=body.status)
    db.add(role)
    db.flush()
    for code in body.permission_codes:
        perm = db.query(Permission).filter_by(perm_code=code).first()
        if perm:
            db.add(RolePermission(role_id=role.id, permission_id=perm.id))
    db.commit()
    return ok(RoleOut.model_validate(role).model_dump(), "角色创建成功")


@router.get("/permissions", response_model=ApiResponse)
def list_permissions(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("user.read")),
):
    rows = db.query(Permission).all()
    return ok([
        {
            "perm_code": p.perm_code,
            "perm_name": p.perm_name,
            "module": p.module,
            "description": PERMISSION_DESCRIPTIONS.get(p.perm_code, ""),
        }
        for p in rows
    ])
