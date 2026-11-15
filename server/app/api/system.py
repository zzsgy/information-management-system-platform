# -*- coding: utf-8 -*-
"""
app/api/system.py
==================
本文件提供「系统级」接口：
- GET /api/system/info   返回服务端版本与当前用户权限（供客户端初始化）
- GET /api/system/dicts  返回字典数据（密级、保管期限等）
说明：健康检查 /health 在 main.py 中单独定义。
"""
from fastapi import APIRouter, Depends, HTTPException
import json
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.dicts import (
    DictItem, DictType, NumberingRule, NumberingSequence, RetentionPeriod,
    SecurityLevel, SystemParameter,
)
from app.models.org import User
from app.schemas.common import ApiResponse, ok
from app.schemas.dicts import (
    DictItemCreate, DictItemOut, DictItemUpdate, NumberingRuleCreate,
    NumberingRuleOut, NumberingRuleUpdate,
)
from app.services import numbering as numbering_svc

router = APIRouter(prefix="/api/system", tags=["系统"])


@router.get("/info", response_model=ApiResponse)
def info(user: User = Depends(get_current_user)):
    """返回当前登录用户的角色与权限，便于客户端动态控制按钮。"""
    return ok({
        "app_name": "企业档案管理系统",
        "version": "1.0",
        "user_id": user.id,
        "username": user.username,
        "real_name": user.real_name,
        "roles": getattr(user, "_role_codes", []),
        "permissions": getattr(user, "_perm_codes", []),
    })


@router.get("/param/{key}", response_model=ApiResponse)
def get_param(
    key: str,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    """读取系统参数（如企业 LOGO、首页卡片配置），供客户端展示。"""
    p = db.query(SystemParameter).filter_by(param_key=key).first()
    return ok({"key": key, "value": p.param_value if p else None})


@router.put("/param/{key}", response_model=ApiResponse)
def set_param(
    key: str, body: dict,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    """保存系统参数（企业 LOGO、首页卡片配置等），仅管理员可写。"""
    value = body.get("value")
    p = db.query(SystemParameter).filter_by(param_key=key).first()
    if not p:
        p = SystemParameter(param_key=key, description=body.get("description", ""))
        db.add(p)
        db.flush()
    p.param_value = value
    if body.get("description") is not None:
        p.description = body.get("description")
    db.commit()
    return ok({"key": key, "value": p.param_value}, "参数已保存")


@router.get("/dicts", response_model=ApiResponse)
def dicts(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    """返回密级与保管期限字典，供前端下拉选择。"""
    sec = db.query(SecurityLevel).all()
    ret = db.query(RetentionPeriod).all()
    return ok({
        "security_levels": [{"id": s.id, "code": s.code, "name": s.name} for s in sec],
        "retention_periods": [{"id": r.id, "code": r.code, "name": r.name} for r in ret],
    })


@router.get("/dict-items", response_model=ApiResponse)
def dict_items(
    type_code: str,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    """返回指定字典类型的启用项，供前端下拉选择（如物理位置 storage_location）。"""
    items = (
        db.query(DictItem)
        .filter(DictItem.type_code == type_code, DictItem.enabled == 1)
        .order_by(DictItem.sort_order, DictItem.id)
        .all()
    )
    return ok([DictItemOut.model_validate(i).model_dump() for i in items])


@router.post("/dict-items", response_model=ApiResponse)
def create_dict_item(
    body: DictItemCreate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    """新建字典项（管理员维护，如新增可选的物理位置）。"""
    if not db.query(DictType).filter_by(type_code=body.type_code).first():
        db.add(DictType(type_code=body.type_code, type_name=body.type_code))
        db.flush()
    if db.query(DictItem).filter_by(type_code=body.type_code, item_code=body.item_code).first():
        raise HTTPException(status_code=400, detail="该类型下编码已存在")
    item = DictItem(**body.model_dump())
    db.add(item)
    db.commit()
    return ok(DictItemOut.model_validate(item).model_dump(), "字典项创建成功")


@router.put("/dict-items/{item_id}", response_model=ApiResponse)
def update_dict_item(
    item_id: int, body: DictItemUpdate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    item = db.get(DictItem, item_id)
    if not item:
        raise HTTPException(status_code=404, detail="字典项不存在")
    for field, value in body.model_dump(exclude_unset=True).items():
        if value is not None:
            setattr(item, field, value)
    db.commit()
    return ok(DictItemOut.model_validate(item).model_dump(), "字典项更新成功")


@router.delete("/dict-items/{item_id}", response_model=ApiResponse)
def delete_dict_item(
    item_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    item = db.get(DictItem, item_id)
    if not item:
        raise HTTPException(status_code=404, detail="字典项不存在")
    db.delete(item)
    db.commit()
    return ok(message="字典项已删除")


# ============ 密级（独立表，供下拉管理） ============
@router.get("/security-levels", response_model=ApiResponse)
def list_security_levels(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    """列出全部密级（供管理员维护下拉选项）。"""
    rows = db.query(SecurityLevel).order_by(SecurityLevel.level, SecurityLevel.id).all()
    return ok([
        {"id": s.id, "code": s.code, "name": s.name,
         "level": s.level, "description": s.description}
        for s in rows
    ])


@router.post("/security-levels", response_model=ApiResponse)
def create_security_level(
    body: dict, db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    code = (body.get("code") or "").strip()
    name = (body.get("name") or "").strip()
    if not code or not name:
        raise HTTPException(status_code=400, detail="编码与名称必填")
    if db.query(SecurityLevel).filter_by(code=code).first():
        raise HTTPException(status_code=400, detail="密级编码已存在")
    item = SecurityLevel(code=code, name=name,
                         level=int(body.get("level") or 1),
                         description=body.get("description") or None)
    db.add(item); db.commit(); db.refresh(item)
    return ok({"id": item.id, "code": item.code, "name": item.name,
               "level": item.level, "description": item.description}, "密级创建成功")


@router.put("/security-levels/{item_id}", response_model=ApiResponse)
def update_security_level(
    item_id: int, body: dict, db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    item = db.get(SecurityLevel, item_id)
    if not item:
        raise HTTPException(status_code=404, detail="密级不存在")
    if body.get("code") is not None:
        item.code = body["code"]
    if body.get("name") is not None:
        item.name = body["name"]
    if body.get("level") is not None:
        item.level = int(body["level"])
    if body.get("description") is not None:
        item.description = body["description"]
    db.commit(); db.refresh(item)
    return ok({"id": item.id, "code": item.code, "name": item.name,
               "level": item.level, "description": item.description}, "密级更新成功")


@router.delete("/security-levels/{item_id}", response_model=ApiResponse)
def delete_security_level(
    item_id: int, db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    item = db.get(SecurityLevel, item_id)
    if not item:
        raise HTTPException(status_code=404, detail="密级不存在")
    db.delete(item); db.commit()
    return ok(message="密级已删除")


# ============ 保管期限（独立表，供下拉管理） ============
@router.get("/retention-periods", response_model=ApiResponse)
def list_retention_periods(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    """列出全部保管期限（供管理员维护下拉选项）。"""
    rows = db.query(RetentionPeriod).order_by(RetentionPeriod.years, RetentionPeriod.id).all()
    return ok([
        {"id": r.id, "code": r.code, "name": r.name, "years": r.years}
        for r in rows
    ])


@router.post("/retention-periods", response_model=ApiResponse)
def create_retention_period(
    body: dict, db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    code = (body.get("code") or "").strip()
    name = (body.get("name") or "").strip()
    if not code or not name:
        raise HTTPException(status_code=400, detail="编码与名称必填")
    if db.query(RetentionPeriod).filter_by(code=code).first():
        raise HTTPException(status_code=400, detail="保管期限编码已存在")
    item = RetentionPeriod(code=code, name=name,
                            years=int(body.get("years") or 0))
    db.add(item); db.commit(); db.refresh(item)
    return ok({"id": item.id, "code": item.code, "name": item.name,
               "years": item.years}, "保管期限创建成功")


@router.put("/retention-periods/{item_id}", response_model=ApiResponse)
def update_retention_period(
    item_id: int, body: dict, db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    item = db.get(RetentionPeriod, item_id)
    if not item:
        raise HTTPException(status_code=404, detail="保管期限不存在")
    if body.get("code") is not None:
        item.code = body["code"]
    if body.get("name") is not None:
        item.name = body["name"]
    if body.get("years") is not None:
        item.years = int(body["years"])
    db.commit(); db.refresh(item)
    return ok({"id": item.id, "code": item.code, "name": item.name,
               "years": item.years}, "保管期限更新成功")


@router.delete("/retention-periods/{item_id}", response_model=ApiResponse)
def delete_retention_period(
    item_id: int, db: Session = Depends(get_db),
    _: User = Depends(require_permissions("dict.manage")),
):
    item = db.get(RetentionPeriod, item_id)
    if not item:
        raise HTTPException(status_code=404, detail="保管期限不存在")
    db.delete(item); db.commit()
    return ok(message="保管期限已删除")


# ============ 档案编号规则 ============
def _get_mode(db: Session) -> str:
    """读取档案编号模式：auto（自动生成）/ manual（手动输入），默认 auto。"""
    p = db.query(SystemParameter).filter_by(param_key="archive_no_mode").first()
    return p.param_value if p and p.param_value in ("auto", "manual") else "auto"


@router.get("/numbering-rules", response_model=ApiResponse)
def list_numbering_rules(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    """列出全部编号规则（按优先级降序）。"""
    rows = (
        db.query(NumberingRule)
        .order_by(NumberingRule.priority.desc(), NumberingRule.id)
        .all()
    )
    out = []
    for r in rows:
        d = NumberingRuleOut.model_validate(r).model_dump()
        d["segments"] = json.loads(r.segments) if r.segments else None
        out.append(d)
    return ok(out)


@router.post("/numbering-rules", response_model=ApiResponse)
def create_numbering_rule(
    body: NumberingRuleCreate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.admin")),
):
    """新建编号规则（管理员）。支持分段规则 segments。"""
    if db.query(NumberingRule).filter_by(rule_code=body.rule_code).first():
        raise HTTPException(status_code=400, detail="规则编码已存在")
    payload = body.model_dump(exclude_unset=True)
    segs = payload.pop("segments", None)
    payload["pattern"] = payload.get("pattern") or ""
    rule = NumberingRule(**payload)
    if segs is not None:
        rule.segments = json.dumps(segs, ensure_ascii=False)
    db.add(rule)
    db.commit()
    d = NumberingRuleOut.model_validate(rule).model_dump()
    d["segments"] = segs
    return ok(d, "编号规则创建成功")


@router.put("/numbering-rules/{rule_id}", response_model=ApiResponse)
def update_numbering_rule(
    rule_id: int, body: NumberingRuleUpdate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.admin")),
):
    rule = db.get(NumberingRule, rule_id)
    if not rule:
        raise HTTPException(status_code=404, detail="编号规则不存在")
    for field, value in body.model_dump(exclude_unset=True).items():
        if field == "segments":
            rule.segments = json.dumps(value, ensure_ascii=False) if value is not None else None
            continue
        if value is not None:
            setattr(rule, field, value)
    db.commit()
    d = NumberingRuleOut.model_validate(rule).model_dump()
    d["segments"] = json.loads(rule.segments) if rule.segments else None
    return ok(d, "编号规则更新成功")


@router.delete("/numbering-rules/{rule_id}", response_model=ApiResponse)
def delete_numbering_rule(
    rule_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.admin")),
):
    rule = db.get(NumberingRule, rule_id)
    if not rule:
        raise HTTPException(status_code=404, detail="编号规则不存在")
    # 先删除该规则产生的流水号序列，避免外键约束导致删除失败
    db.query(NumberingSequence).filter_by(rule_id=rule.id).delete()
    db.delete(rule)
    db.commit()
    return ok(message="编号规则已删除")


@router.get("/numbering-rules/preview", response_model=ApiResponse)
def preview_number(
    dept_id: int, category_id: int, retention_id: int = None,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    """预览即将生成的档案编号（不占用流水号）。可传 retention_id 以解析保存期限编码。"""
    retention_code = None
    if retention_id:
        rp = db.get(RetentionPeriod, retention_id)
        retention_code = rp.code if rp else None
    no = numbering_svc.preview_archive_no(
        db, dept_id=dept_id, category_id=category_id, retention_code=retention_code
    )
    return ok({"archive_no": no})


@router.get("/archive-no-mode", response_model=ApiResponse)
def get_archive_no_mode(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.read")),
):
    """获取档案编号模式（auto/manual）。"""
    return ok({"mode": _get_mode(db)})


@router.put("/archive-no-mode", response_model=ApiResponse)
def set_archive_no_mode(
    body: dict, db: Session = Depends(get_db),
    _: User = Depends(require_permissions("archive.admin")),
):
    """设置档案编号模式：auto 自动生成 / manual 手动输入（管理员）。"""
    mode = body.get("mode")
    if mode not in ("auto", "manual"):
        raise HTTPException(status_code=400, detail="mode 必须为 auto 或 manual")
    p = db.query(SystemParameter).filter_by(param_key="archive_no_mode").first()
    if not p:
        p = SystemParameter(param_key="archive_no_mode", description="档案编号模式：auto 自动生成 / manual 手动输入")
        db.add(p)
        db.flush()
    p.param_value = mode
    db.commit()
    return ok({"mode": mode}, "编号模式已更新")
