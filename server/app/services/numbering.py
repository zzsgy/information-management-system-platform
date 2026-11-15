# -*- coding: utf-8 -*-
"""
app/services/numbering.py
=========================
本文件实现「自动编号服务」：根据编号规则生成唯一档案编号。

编号规则支持两种定义方式：
1. 传统模板 pattern，如 {DEPT}-{CATEGORY}-{YEAR}-{SEQ4}；
2. 分段规则 segments（推荐）：将编号拆分为若干「段」，每段指定生成方式：
   - fixed：固定文本；
   - ref：引用字段（部门编码 / 分类编码 / 保存期限编码 / 年份 / 月份 / 日期）；
   - seq：流水号（按规则周期与长度补零）。
   例如「XZ」(固定) + 部门编码 + 保存期限编码 + 4位流水号。

流水号在数据库事务内用 SELECT ... FOR UPDATE 行锁原子递增，避免并发重复。
"""
import json
from datetime import datetime
from typing import Optional

from sqlalchemy import select
from sqlalchemy.orm import Session
from sqlalchemy.sql import text

from app.models.archive import ArchiveCategory
from app.models.dicts import NumberingRule, NumberingSequence
from app.models.org import Department


# 引用字段可选值（与生成时解析对应）
REF_FIELDS = {
    "dept_code": "部门编码",
    "category_code": "分类编码",
    "retention_code": "保存期限编码",
    "year": "年份(YYYY)",
    "month": "月份(MM)",
    "day": "日期(DD)",
}


def _resolve_scope(rule: NumberingRule, dept_id: int, category_id: int) -> str:
    """根据规则适用范围拼装作用域键，用于区分不同流水号序列。"""
    if rule.scope_type == "global":
        return ""
    if rule.scope_type == "dept":
        return f"dept:{dept_id}"
    if rule.scope_type == "category":
        return f"cat:{category_id}"
    return f"dept:{dept_id}:cat:{category_id}"


def _period_key(rule: NumberingRule) -> str:
    """根据重置周期返回周期键（如 2026 或 2026-08）。"""
    now = datetime.now()
    if rule.reset_cycle == "MONTH":
        return now.strftime("%Y-%m")
    return now.strftime("%Y")


def _select_rule(db: Session, category_id: int) -> Optional[NumberingRule]:
    """选取适用规则：优先分类绑定的规则，否则取启用且优先级最高的规则。"""
    category = db.get(ArchiveCategory, category_id)
    rule: Optional[NumberingRule] = None
    if category and category.numbering_rule_id:
        rule = db.get(NumberingRule, category.numbering_rule_id)
    if not rule:
        rule = (
            db.query(NumberingRule)
            .filter_by(enabled=1)
            .order_by(NumberingRule.priority.desc())
            .first()
        )
    return rule


def _format_pattern(rule: NumberingRule, *, dept_code: str, cat_code: str, seq: int) -> str:
    """按模板拼装编号（兼容旧规则）。"""
    seq_str = str(seq).zfill(rule.sequence_length)
    return (
        rule.pattern.replace("{DEPT}", dept_code)
        .replace("{CATEGORY}", cat_code)
        .replace("{YEAR}", datetime.now().strftime("%Y"))
        .replace("{SEQ" + str(rule.sequence_length) + "}", seq_str)
        .replace("{SEQ}", seq_str)
    )


def _apply_width(value: str, length: int, pad: str) -> str:
    """按指定长度对齐：不足左侧补 pad，超出右侧截断。length<=0 不处理。"""
    value = "" if value is None else str(value)
    if length and length > 0:
        if len(value) < length:
            value = (pad or "0") * (length - len(value)) + value
        else:
            value = value[:length]
    return value


def _segments_from_rule(rule: NumberingRule) -> Optional[list]:
    """解析规则中的分段 JSON；无或非法则返回 None（走模板回退）。"""
    if not rule.segments:
        return None
    try:
        segs = json.loads(rule.segments)
        return segs if isinstance(segs, list) and segs else None
    except Exception:  # noqa
        return None


def _resolve_ref(ref: str, *, dept_code: str, cat_code: str, retention_code: str) -> str:
    """解析引用字段的真实值。"""
    now = datetime.now()
    mapping = {
        "dept_code": dept_code or "",
        "category_code": cat_code or "",
        "retention_code": retention_code or "",
        "year": now.strftime("%Y"),
        "month": now.strftime("%m"),
        "day": now.strftime("%d"),
    }
    return mapping.get(ref, "")


def _build_from_segments(
    segs: list, *, dept_code: str, cat_code: str, retention_code: str, seq: int
) -> str:
    """按分段规则拼装编号。"""
    parts = []
    for seg in segs:
        stype = seg.get("type")
        length = int(seg.get("length", 0) or 0)
        pad = seg.get("pad") or "0"
        if stype == "fixed":
            val = str(seg.get("value", ""))
        elif stype == "ref":
            val = _resolve_ref(
                seg.get("ref", ""),
                dept_code=dept_code, cat_code=cat_code, retention_code=retention_code,
            )
        elif stype == "seq":
            val = str(seq)
        else:
            val = ""
        parts.append(_apply_width(val, length, pad))
    return "".join(parts)


def _next_seq(db: Session, rule: NumberingRule, scope_key: str, period_key: str) -> int:
    """事务内原子递增流水号，返回新值。"""
    row = (
        db.query(NumberingSequence)
        .filter_by(rule_id=rule.id, scope_key=scope_key, period_key=period_key)
        .with_for_update()
        .first()
    )
    if row is None:
        row = NumberingSequence(
            rule_id=rule.id, scope_key=scope_key, period_key=period_key, current_value=0
        )
        db.add(row)
        db.flush()
    row.current_value += 1
    return row.current_value


def generate_archive_no(
    db: Session, *, dept_id: int, category_id: int, retention_code: str = None
) -> str:
    """为一份新档案生成唯一编号（占用并递增流水号）。"""
    category = db.get(ArchiveCategory, category_id)
    rule = _select_rule(db, category_id)
    if not rule:
        # 无规则时退化为时间戳编号，保证不报错
        return f"AR-{datetime.now().strftime('%Y%m%d%H%M%S')}"

    dept = db.get(Department, dept_id)
    dept_code = dept.dept_code if dept else "XZ"
    cat_code = category.category_code if category else "GEN"
    segs = _segments_from_rule(rule)
    scope_key = _resolve_scope(rule, dept_id, category_id)
    period_key = _period_key(rule)
    seq = _next_seq(db, rule, scope_key, period_key)
    if segs:
        return _build_from_segments(
            segs, dept_code=dept_code, cat_code=cat_code,
            retention_code=retention_code, seq=seq,
        )
    return _format_pattern(rule, dept_code=dept_code, cat_code=cat_code, seq=seq)


def preview_archive_no(
    db: Session, *, dept_id: int, category_id: int, retention_code: str = None
) -> str:
    """预览即将生成的档案编号（不占用流水号，只读）。"""
    category = db.get(ArchiveCategory, category_id)
    rule = _select_rule(db, category_id)
    if not rule:
        return f"AR-{datetime.now().strftime('%Y%m%d%H%M%S')}"

    dept = db.get(Department, dept_id)
    dept_code = dept.dept_code if dept else "XZ"
    cat_code = category.category_code if category else "GEN"
    segs = _segments_from_rule(rule)
    scope_key = _resolve_scope(rule, dept_id, category_id)
    period_key = _period_key(rule)
    row = (
        db.query(NumberingSequence)
        .filter_by(rule_id=rule.id, scope_key=scope_key, period_key=period_key)
        .first()
    )
    seq = (row.current_value + 1) if row else 1
    if segs:
        return _build_from_segments(
            segs, dept_code=dept_code, cat_code=cat_code,
            retention_code=retention_code, seq=seq,
        )
    return _format_pattern(rule, dept_code=dept_code, cat_code=cat_code, seq=seq)
