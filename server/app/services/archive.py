# -*- coding: utf-8 -*-
"""
app/services/archive.py
========================
本文件实现「档案与分类」业务逻辑：
- 分类：创建/列表（树形）。
- 档案：创建（自动生成编号、登记创建人）、更新、分页查询、详情、归档状态流转。
说明：创建档案时同步调用编号服务生成唯一 archive_no；文件数等统计由调用方维护。
"""
from datetime import datetime
from typing import Optional

from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from datetime import date, timedelta
import json

from app.core.security import hash_password
from app.models.archive import Archive, ArchiveCategory, ArchiveUpdateLog
from app.schemas.archive import ArchiveCreate
from app.models.dicts import (
    SecurityLevel,
    RetentionPeriod,
    SystemParameter,
)
from app.models.org import Department, User
from app.services import numbering as numbering_svc


def _archive_no_mode(db: Session) -> str:
    """读取档案编号模式：auto / manual，默认 auto。"""
    p = db.query(SystemParameter).filter_by(param_key="archive_no_mode").first()
    return p.param_value if p and p.param_value in ("auto", "manual") else "auto"


def _calc_expiry(archive_date, validity_months):
    """根据存档日期与有效期（月）计算到期日期；缺参返回 None。"""
    if not archive_date or not validity_months:
        return None
    total_months = (archive_date.year * 12 + (archive_date.month - 1)) + validity_months
    year = total_months // 12
    month = total_months % 12 + 1
    # 处理月末溢出（如 1月31 +1月 -> 2月无31，取月末）
    import calendar
    last_day = calendar.monthrange(year, month)[1]
    day = min(archive_date.day, last_day)
    return date(year, month, day)


def create_category(db: Session, data, operator_id: int | None = None) -> ArchiveCategory:
    """创建档案分类。"""
    cat = ArchiveCategory(
        category_code=data.category_code,
        category_name=data.category_name,
        parent_id=data.parent_id,
        archive_level=data.archive_level,
        default_security_level_id=data.default_security_level_id,
        default_retention_id=data.default_retention_id,
        numbering_rule_id=data.numbering_rule_id,
        sort_order=data.sort_order,
        status=data.status,
    )
    db.add(cat)
    db.flush()
    return cat


def list_categories(db: Session) -> list[ArchiveCategory]:
    """列出全部分类（前端自行组装树）。"""
    return db.query(ArchiveCategory).order_by(ArchiveCategory.sort_order).all()


def update_category(db: Session, cat_id: int, data) -> ArchiveCategory:
    """更新档案分类。"""
    cat = db.query(ArchiveCategory).filter_by(id=cat_id).first()
    if not cat:
        raise ValueError("分类不存在")
    if data.category_code is not None and data.category_code != cat.category_code:
        if db.query(ArchiveCategory).filter_by(category_code=data.category_code).first():
            raise ValueError("分类编码已存在")
        cat.category_code = data.category_code
    if data.category_name is not None:
        cat.category_name = data.category_name
    if data.parent_id is not None:
        cat.parent_id = data.parent_id
    if data.archive_level is not None:
        cat.archive_level = data.archive_level
    if data.default_security_level_id is not None:
        cat.default_security_level_id = data.default_security_level_id
    if data.default_retention_id is not None:
        cat.default_retention_id = data.default_retention_id
    if data.numbering_rule_id is not None:
        cat.numbering_rule_id = data.numbering_rule_id
    if data.sort_order is not None:
        cat.sort_order = data.sort_order
    if data.status is not None:
        cat.status = data.status
    db.flush()
    return cat


def delete_category(db: Session, cat_id: int):
    """删除档案分类（若存在关联档案则拒绝）。"""
    cat = db.query(ArchiveCategory).filter_by(id=cat_id).first()
    if not cat:
        raise ValueError("分类不存在")
    if db.query(Archive).filter_by(category_id=cat_id).first():
        raise ValueError("该分类下存在档案，无法删除")
    db.delete(cat)
    db.flush()


def create_archive(db: Session, data, creator_id: int) -> Archive:
    """创建档案：根据编号模式决定自动生成或采用手动编号。"""
    if _archive_no_mode(db) == "manual":
        if not data.archive_no or not data.archive_no.strip():
            raise ValueError("当前为手动编号模式，必须填写档案编号")
        archive_no = data.archive_no.strip()
        if db.query(Archive).filter_by(archive_no=archive_no).first():
            raise ValueError(f"档案编号 {archive_no} 已存在")
    else:
        retention_code = None
        if data.retention_id:
            rp = db.get(RetentionPeriod, data.retention_id)
            retention_code = rp.code if rp else None
        archive_no = numbering_svc.generate_archive_no(
            db, dept_id=data.dept_id, category_id=data.category_id,
            retention_code=retention_code,
        )
    archive = Archive(
        archive_no=archive_no,
        archive_name=data.archive_name,
        category_id=data.category_id,
        dept_id=data.dept_id,
        security_level_id=data.security_level_id,
        retention_id=data.retention_id,
        archive_year=data.archive_year or datetime.now().year,
        formation_date=data.formation_date,
        filing_date=data.filing_date or datetime.now().date(),
        creator_user_id=creator_id,
        archiver_user_id=data.archiver_user_id,
        keyword=data.keyword,
        summary=data.summary,
        physical_location=data.physical_location,
        volume_no=data.volume_no,
        page_count=data.page_count,
        file_type=data.file_type,
        owner_name=data.owner_name,
        cabinet_no=data.cabinet_no,
        column_no=data.column_no,
        layer_no=data.layer_no,
        storage_type=data.storage_type,
        copy_count=data.copy_count or 1,
        archive_date=data.archive_date,
        validity_months=data.validity_months,
        expiry_date=_calc_expiry(data.archive_date, data.validity_months),
        remark=data.remark,
        status="draft",
    )
    db.add(archive)
    db.flush()
    return archive


def import_archives(db: Session, rows: list, creator_id: int) -> tuple:
    """批量导入档案。

    :param rows: list[dict]，每个 dict 的 key 支持中文或英文列名。
        必填：档案名称(archive_name)、分类(按名称解析)、部门(按名称解析)。
        可选 #4 字段：文件类型/档案负责人/柜号/列号/层号/保存类型/保存份数/
              存档日期/有效期(月)/备注、密级(名称)/保管期限(名称)。
    :return: (created, errors)；errors 为 [{"row":int,"error":str}]。
    逐条提交，部分失败不影响其余行。
    """
    created = 0
    errors = []
    for idx, raw in enumerate(rows, start=1):
        try:
            def pick(*keys):
                for k in keys:
                    v = raw.get(k)
                    if v is not None and str(v).strip() != "":
                        return str(v).strip()
                return None

            cat_name = pick("category", "档案类别", "category_name")
            dept_name = pick("department", "部门", "dept_name")
            cat = None
            if cat_name:
                cat = db.query(ArchiveCategory).filter_by(category_name=cat_name).first()
                if not cat:
                    raise ValueError(f"分类「{cat_name}」不存在")
            dept = None
            if dept_name:
                dept = db.query(Department).filter_by(dept_name=dept_name).first()
                if not dept:
                    raise ValueError(f"部门「{dept_name}」不存在")
            sec = None
            sec_name = pick("security", "密级", "security_level")
            if sec_name:
                sec = db.query(SecurityLevel).filter_by(name=sec_name).first()
            ret = None
            ret_name = pick("retention", "保管期限")
            if ret_name:
                ret = db.query(RetentionPeriod).filter_by(name=ret_name).first()

            raw_copy = int(pick("copy_count", "保存份数") or 1)
            raw_valid = int(pick("validity_months", "有效期") or 0)
            data = ArchiveCreate(
                archive_name=pick("archive_name", "档案名称") or "",
                category_id=cat.id if cat else None,
                dept_id=dept.id if dept else None,
                security_level_id=sec.id if sec else None,
                retention_id=ret.id if ret else None,
                file_type=pick("file_type", "文件类型"),
                owner_name=pick("owner_name", "档案负责人"),
                cabinet_no=pick("cabinet_no", "柜号"),
                column_no=pick("column_no", "列号"),
                layer_no=pick("layer_no", "层号"),
                storage_type=pick("storage_type", "保存类型"),
                copy_count=raw_copy,
                archive_date=pick("archive_date", "存档日期"),
                validity_months=raw_valid or None,
                remark=pick("remark", "备注"),
            )
            if not data.archive_name:
                raise ValueError("档案名称不能为空")
            if not data.category_id:
                raise ValueError("分类不能为空或不存在")
            if not data.dept_id:
                raise ValueError("部门不能为空或不存在")
            create_archive(db, data, creator_id=creator_id)
            db.commit()
            created += 1
        except Exception as e:  # noqa
            db.rollback()
            errors.append({"row": idx, "error": str(e)})
    return created, errors


def update_archive(db: Session, archive_id: int, data, operator_id: int = None,
                   operator_name: str = None) -> Archive:
    """更新档案字段（仅传入的非空项），自动重算到期日期并写更新记录。"""
    archive = db.get(Archive, archive_id)
    if not archive or archive.deleted_at is not None:
        raise ValueError("档案不存在")
    payload = data.model_dump(exclude_unset=True)
    changed = []
    for field, value in payload.items():
        if value is None:
            continue
        if getattr(archive, field, None) != value:
            setattr(archive, field, value)
            changed.append(field)
    # 存档日期或有效期变化 -> 重算到期日期（到期日期禁止手动改，自动算）
    if ("archive_date" in payload and payload["archive_date"] is not None) or \
       ("validity_months" in payload and payload["validity_months"] is not None):
        archive.expiry_date = _calc_expiry(archive.archive_date, archive.validity_months)
    if changed:
        log = ArchiveUpdateLog(
            archive_id=archive.id,
            operator_id=operator_id,
            operator_name=operator_name,
            action="update",
            changed_fields=json.dumps(changed, ensure_ascii=False),
        )
        db.add(log)
        db.flush()
    return archive


def to_out_dict(db: Session, archive: Archive) -> dict:
    """将档案组装为前端展示所需的完整字典（含名称与最新更新记录）。"""
    dept = db.get(Department, archive.dept_id) if archive.dept_id else None
    cat = db.get(ArchiveCategory, archive.category_id) if archive.category_id else None
    sec = db.get(SecurityLevel, archive.security_level_id) if archive.security_level_id else None
    latest = (
        db.query(ArchiveUpdateLog)
        .filter_by(archive_id=archive.id)
        .order_by(ArchiveUpdateLog.id.desc())
        .first()
    )
    latest_update = None
    if latest:
        changed = []
        try:
            changed = json.loads(latest.changed_fields or "[]")
        except Exception:  # noqa
            changed = []
        latest_update = {
            "operator_name": latest.operator_name,
            "action": latest.action,
            "created_at": latest.created_at.isoformat() if latest.created_at else None,
            "changed_fields": changed,
        }
    return {
        "id": archive.id,
        "archive_no": archive.archive_no,
        "archive_name": archive.archive_name,
        "category_id": archive.category_id,
        "dept_id": archive.dept_id,
        "dept_name": dept.dept_name if dept else None,
        "category_name": cat.category_name if cat else None,
        "security_level_id": archive.security_level_id,
        "security_name": sec.name if sec else None,
        "archive_year": archive.archive_year,
        "physical_location": archive.physical_location,
        "status": archive.status,
        "file_count": archive.file_count,
        "file_type": archive.file_type,
        "owner_name": archive.owner_name,
        "cabinet_no": archive.cabinet_no,
        "column_no": archive.column_no,
        "layer_no": archive.layer_no,
        "storage_type": archive.storage_type,
        "copy_count": archive.copy_count,
        "archive_date": archive.archive_date.isoformat() if archive.archive_date else None,
        "validity_months": archive.validity_months,
        "expiry_date": archive.expiry_date.isoformat() if archive.expiry_date else None,
        "remark": archive.remark,
        "latest_update": latest_update,
        "created_at": archive.created_at.isoformat() if archive.created_at else None,
    }


def query_archives(
    db: Session,
    *,
    keyword: Optional[str] = None,
    category_id: Optional[int] = None,
    dept_id: Optional[int] = None,
    status: Optional[str] = None,
    page: int = 1,
    page_size: int = 20,
):
    """分页查询档案，支持关键词/分类/部门/状态过滤。"""
    q = db.query(Archive).filter(Archive.deleted_at.is_(None))
    if keyword:
        q = q.filter(
            or_(
                Archive.archive_name.ilike(f"%{keyword}%"),
                Archive.archive_no.ilike(f"%{keyword}%"),
                Archive.keyword.ilike(f"%{keyword}%"),
            )
        )
    if category_id:
        q = q.filter(Archive.category_id == category_id)
    if dept_id:
        q = q.filter(Archive.dept_id == dept_id)
    if status:
        q = q.filter(Archive.status == status)
    total = q.with_entities(func.count()).scalar() or 0
    rows = (
        q.order_by(Archive.id.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return total, rows


def get_archive(db: Session, archive_id: int) -> Archive:
    """获取档案详情。"""
    archive = db.get(Archive, archive_id)
    if not archive or archive.deleted_at is not None:
        raise ValueError("档案不存在")
    return archive


# 档案状态 -> 中文标签（用于仪表盘展示）
STATUS_LABELS = {
    "draft": "草稿",
    "pending": "待审批",
    "archive": "已归档",
    "borrowed": "借阅中",
    "locked": "封存",
    "damaged": "已损坏",
    "destroy_pending": "销毁待审",
    "destroyed": "已销毁",
}


def archive_stats(db: Session) -> dict:
    """首页仪表盘统计：总数、状态/分类/密级分布、即将到期。"""
    from app.models.dicts import SecurityLevel

    today = date.today()
    soon = today + timedelta(days=90)
    base = db.query(Archive).filter(Archive.deleted_at.is_(None))

    total = base.count()

    # 状态分布
    status_rows = (
        db.query(Archive.status, func.count())
        .filter(Archive.deleted_at.is_(None))
        .group_by(Archive.status)
        .all()
    )
    status_counts = {s: c for s, c in status_rows}
    by_status = [
        {"status": st, "label": label, "count": status_counts.get(st, 0)}
        for st, label in STATUS_LABELS.items()
    ]

    # 分类分布（Top8）+ 未分类
    cat_rows = (
        db.query(ArchiveCategory.category_name, func.count())
        .join(Archive, Archive.category_id == ArchiveCategory.id)
        .filter(Archive.deleted_at.is_(None))
        .group_by(ArchiveCategory.category_name)
        .order_by(func.count().desc())
        .limit(8)
        .all()
    )
    by_category = [{"name": n, "count": c} for n, c in cat_rows]
    uncat = (
        db.query(Archive)
        .filter(Archive.deleted_at.is_(None), Archive.category_id.is_(None))
        .count()
    )
    if uncat:
        by_category.append({"name": "未分类", "count": uncat})

    # 密级分布
    sec_rows = (
        db.query(SecurityLevel.name, func.count())
        .join(Archive, Archive.security_level_id == SecurityLevel.id)
        .filter(Archive.deleted_at.is_(None))
        .group_by(SecurityLevel.name)
        .all()
    )
    by_security = [{"name": n or "未设置", "count": c} for n, c in sec_rows]

    # 即将到期（90 天内，排除已销毁/销毁待审）
    expiring_filter = (
        Archive.deleted_at.is_(None),
        Archive.expiry_date.isnot(None),
        Archive.expiry_date >= today,
        Archive.expiry_date <= soon,
        Archive.status.notin_(["destroyed", "destroy_pending"]),
    )
    expiring_count = db.query(Archive).filter(*expiring_filter).count()
    expiring_list = (
        db.query(Archive)
        .filter(*expiring_filter)
        .order_by(Archive.expiry_date.asc())
        .limit(6)
        .all()
    )
    expiring_soon = [to_out_dict(db, a) for a in expiring_list]

    return {
        "total": total,
        "by_status": by_status,
        "by_category": by_category,
        "by_security": by_security,
        "expiring_count": expiring_count,
        "expiring_soon": expiring_soon,
    }
