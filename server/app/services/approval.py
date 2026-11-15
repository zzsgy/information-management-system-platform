# -*- coding: utf-8 -*-
"""
app/services/approval.py
=========================
审批流核心逻辑：
- 模板解析（按业务类型 + 档案词缀路由）
- 流程实例化（解析各节点实际审批人，按条件跳过不适用节点）
- 节点审批动作推进（支持会签、驳回、末节点通过）
说明：审批人来源包括 角色/账号/权限等级/权限码/部门领导/创建人领导。
"""
from datetime import datetime
from typing import Optional

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.models.approval import (
    APPROVER_CREATOR_LEADER,
    APPROVER_DEPT_LEADER,
    APPROVER_LEVEL,
    APPROVER_PERMISSION,
    APPROVER_ROLE,
    APPROVER_USER,
    ApprovalFlow,
    ApprovalFlowNode,
    ApprovalFlowRecord,
    ApprovalTemplate,
    ApprovalTemplateNode,
)
from app.models.archive import Archive, ArchiveCategory
from app.models.org import (
    Department,
    Permission,
    Role,
    RolePermission,
    User,
    UserRole,
)


# ---------------- 词缀匹配 ----------------
def _affix_match(field, op, value, ctx: dict) -> bool:
    """判断档案上下文 ctx 是否命中字段/运算符/值条件。"""
    if not field or not op or value is None or str(value).strip() == "":
        return True
    actual = str(ctx.get(field) or "")
    val = str(value)
    if op == "startswith":
        return actual.startswith(val)
    if op == "contains":
        return val in actual
    if op == "endswith":
        return actual.endswith(val)
    if op == "eq":
        return actual == val
    return True


def build_archive_ctx(db: Session, archive: Archive) -> dict:
    """构造档案信息词缀上下文，供模板/节点条件匹配。"""
    cat = db.get(ArchiveCategory, archive.category_id) if archive.category_id else None
    dept = db.get(Department, archive.dept_id) if archive.dept_id else None
    return {
        "archive_no": archive.archive_no or "",
        "archive_name": archive.archive_name or "",
        "keyword": archive.keyword or "",
        "category_name": cat.category_name if cat else "",
        "dept_name": dept.dept_name if dept else "",
    }


def build_summary(db: Session, archive_ids: list[int]) -> str:
    """生成关联档案摘要 JSON 字符串，用于流程列表展示。"""
    import json
    items = []
    for aid in (archive_ids or [])[:20]:
        arch = db.get(Archive, aid)
        if arch:
            items.append({"archive_no": arch.archive_no, "archive_name": arch.archive_name})
    return json.dumps(items, ensure_ascii=False)


def resolve_template(db: Session, biz_type: str, ctx: dict) -> Optional[ApprovalTemplate]:
    """按业务类型 + 档案词缀匹配模板，取优先级最高者。"""
    cands = db.query(ApprovalTemplate).filter_by(biz_type=biz_type, enabled=1).all()
    hit = [t for t in cands if _affix_match(t.trigger_field, t.trigger_op, t.trigger_value, ctx)]
    if not hit:
        return None
    hit.sort(key=lambda t: (t.priority or 0), reverse=True)
    return hit[0]


# ---------------- 审批人解析 ----------------
def _resolve_approvers(db: Session, node, archive: Archive) -> list[int]:
    t = node.approver_type
    v = node.approver_value
    if t == APPROVER_USER:
        return [int(v)] if v and str(v).strip().isdigit() else []
    if t == APPROVER_ROLE:
        role = db.query(Role).filter_by(role_code=v).first() if v else None
        if not role:
            return []
        return [ur.user_id for ur in db.query(UserRole).filter_by(role_id=role.id)]
    if t == APPROVER_PERMISSION:
        perm = db.query(Permission).filter_by(perm_code=v).first() if v else None
        if not perm:
            return []
        role_ids = [rp.role_id for rp in db.query(RolePermission).filter_by(permission_id=perm.id)]
        uids = set()
        for rid in role_ids:
            for ur in db.query(UserRole).filter_by(role_id=rid):
                uids.add(ur.user_id)
        return list(uids)
    if t == APPROVER_LEVEL:
        try:
            thr = int(v)
        except (TypeError, ValueError):
            return []
        role_ids = [r.id for r in db.query(Role).filter(Role.role_level <= thr)]
        uids = set()
        for rid in role_ids:
            for ur in db.query(UserRole).filter_by(role_id=rid):
                uids.add(ur.user_id)
        return list(uids)
    if t == APPROVER_DEPT_LEADER:
        dept = db.get(Department, archive.dept_id) if archive.dept_id else None
        if dept and dept.leader_user_id:
            return [dept.leader_user_id]
        return []
    if t == APPROVER_CREATOR_LEADER:
        creator = db.get(User, archive.creator_user_id) if archive.creator_user_id else None
        if creator:
            dept = db.get(Department, creator.dept_id)
            if dept and dept.leader_user_id:
                return [dept.leader_user_id]
        return []
    return []


def _default_flow_nodes(biz_type: str) -> list[ApprovalTemplateNode]:
    """无匹配模板时，生成默认单节点：由拥有该业务审批权限的人员审批。"""
    perm = f"{biz_type}.approve"
    node = ApprovalTemplateNode(
        node_order=1,
        node_name="审批",
        approver_type=APPROVER_PERMISSION,
        approver_value=perm,
    )
    return [node]


# ---------------- 流程实例化 ----------------
def create_flow(
    db: Session,
    biz_type: str,
    biz_id: int,
    applicant_id: int,
    title: str,
    archive_ctx: dict,
    archive: Archive,
    template: Optional[ApprovalTemplate] = None,
    archive_summary: str = "",
) -> ApprovalFlow:
    """依据模板（或默认）生成审批流程实例。"""
    flow = ApprovalFlow(
        biz_type=biz_type,
        biz_id=biz_id,
        title=title,
        applicant_user_id=applicant_id,
        template_id=template.id if template else None,
        current_node=0,
        status="pending",
        archive_summary=archive_summary,
    )
    db.add(flow)
    db.flush()

    tmpl_nodes = template.nodes if template else _default_flow_nodes(biz_type)
    # 按节点顺序过滤：满足条件才插入该节点
    kept = [n for n in sorted(tmpl_nodes, key=lambda x: x.node_order)
            if _affix_match(n.cond_field, n.cond_op, n.cond_value, archive_ctx)]
    if not kept:
        # 没有任何生效节点 → 直接通过
        flow.status = "approved"
        flow.current_node = 0
        return flow

    for n in kept:
        approvers = _resolve_approvers(db, n, archive)
        node = ApprovalFlowNode(
            flow_id=flow.id,
            node_order=n.node_order,
            node_name=n.node_name,
            approver_type=n.approver_type,
            approver_value=n.approver_value,
            approver_user_ids=",".join(str(u) for u in approvers) if approvers else "",
            acted_user_ids="",
            node_status="pending" if approvers else "skipped",
            is_countersign=n.is_countersign,
        )
        db.add(node)
    db.flush()
    # 跳过无审批人的节点，定位首个待审节点
    _advance_to_next_pending(db, flow)
    return flow


def _advance_to_next_pending(db: Session, flow: ApprovalFlow):
    """将 current_node 指向首个 pending 节点；无则流程置为 approved。"""
    nodes = sorted(flow.nodes, key=lambda x: x.node_order)
    for n in nodes:
        if n.node_status == "pending":
            flow.current_node = n.node_order
            return
    # 没有 pending 节点 → 全部 skipped/approved
    flow.current_node = 0
    flow.status = "approved"


# ---------------- 审批动作 ----------------
def can_act(flow: ApprovalFlow, user_id: int, is_admin: bool = False) -> bool:
    """当前用户是否可审批当前节点。"""
    if flow.status != "pending":
        return False
    node = _current_node(flow)
    if not node or node.node_status != "pending":
        return False
    if is_admin:
        return True
    ids = [int(x) for x in (node.approver_user_ids or "").split(",") if x.strip().isdigit()]
    return user_id in ids


def _current_node(flow: ApprovalFlow) -> Optional[ApprovalFlowNode]:
    for n in flow.nodes:
        if n.node_order == flow.current_node:
            return n
    return None


def act_on_flow(
    db: Session,
    flow: ApprovalFlow,
    operator_user_id: int,
    action: str,  # agree / reject
    opinion: Optional[str] = None,
    is_admin: bool = False,
) -> ApprovalFlow:
    """对当前节点执行审批动作，返回更新后的流程（status 可能为 approved/rejected）。"""
    if flow.status != "pending":
        raise ValueError("流程已结束，无法审批")
    node = _current_node(flow)
    if not node or node.node_status != "pending":
        raise ValueError("当前无待审批节点")
    if not (is_admin or can_act(flow, operator_user_id, is_admin)):
        raise ValueError("您不是当前节点的审批人")

    db.add(ApprovalFlowRecord(
        flow_id=flow.id, node_id=node.id, action=action,
        opinion=opinion, operator_user_id=operator_user_id, acted_at=datetime.now(),
    ))

    if action == "reject":
        node.node_status = "rejected"
        flow.status = "rejected"
        flow.current_node = 0
        return flow

    # agree
    acted = set(int(x) for x in (node.acted_user_ids or "").split(",") if x.strip().isdigit())
    acted.add(operator_user_id)
    node.acted_user_ids = ",".join(str(x) for x in acted)
    if node.is_countersign:
        approvers = [int(x) for x in (node.approver_user_ids or "").split(",") if x.strip().isdigit()]
        if not set(approvers).issubset(acted):
            # 会签未集齐，保持 pending
            return flow
    node.node_status = "approved"
    _advance_to_next_pending(db, flow)
    return flow


# ---------------- 查询 ----------------
def get_my_tasks(db: Session, user_id: int, biz_type: str = None) -> list[dict]:
    """返回当前用户待审批的流程列表（含档案摘要与当前节点名）。"""
    flows = db.query(ApprovalFlow).filter_by(status="pending").all()
    tasks = []
    for f in flows:
        node = _current_node(f)
        if not node:
            continue
        ids = [int(x) for x in (node.approver_user_ids or "").split(",") if x.strip().isdigit()]
        if user_id not in ids:
            continue
        if biz_type and f.biz_type != biz_type:
            continue
        tasks.append({
            "flow_id": f.id,
            "title": f.title,
            "biz_type": f.biz_type,
            "biz_id": f.biz_id,
            "applicant_user_id": f.applicant_user_id,
            "current_node_name": node.node_name,
            "archive_summary": f.archive_summary,
            "created_at": f.created_at.isoformat() if f.created_at else "",
        })
    return tasks


def get_flow_detail(db: Session, flow_id: int) -> Optional[dict]:
    """返回流程详情（节点列表 + 审批记录）。"""
    f = db.get(ApprovalFlow, flow_id)
    if not f:
        return None
    nodes = []
    for n in sorted(f.nodes, key=lambda x: x.node_order):
        approver_ids = [int(x) for x in (n.approver_user_ids or "").split(",") if x.strip().isdigit()]
        acted_ids = [int(x) for x in (n.acted_user_ids or "").split(",") if x.strip().isdigit()]
        names = []
        for uid in approver_ids:
            u = db.get(User, uid)
            names.append(u.real_name if u else f"用户{uid}")
        nodes.append({
            "node_order": n.node_order,
            "node_name": n.node_name,
            "approver_type": n.approver_type,
            "approver_names": names,
            "approver_user_ids": n.approver_user_ids or "",
            "acted_names": [ (db.get(User, uid).real_name if db.get(User, uid) else str(uid)) for uid in acted_ids ],
            "node_status": n.node_status,
            "is_countersign": n.is_countersign,
        })
    records = []
    for r in db.query(ApprovalFlowRecord).filter_by(flow_id=flow_id).order_by(ApprovalFlowRecord.id).all():
        op = db.get(User, r.operator_user_id)
        records.append({
            "action": r.action,
            "opinion": r.opinion,
            "operator": op.real_name if op else str(r.operator_user_id),
            "acted_at": r.acted_at.isoformat() if r.acted_at else "",
        })
    return {
        "flow_id": f.id,
        "title": f.title,
        "biz_type": f.biz_type,
        "biz_id": f.biz_id,
        "status": f.status,
        "applicant_user_id": f.applicant_user_id,
        "archive_summary": f.archive_summary,
        "nodes": nodes,
        "records": records,
    }
