# -*- coding: utf-8 -*-
"""
app/api/approval.py
====================
本文件提供「审批流」接口：
- 模板管理（超级管理员）：列表/详情/创建/更新/删除
- 我的待办：当前用户待审批流程、流程详情、审批动作
说明：审批人来源与条件在服务端解析（角色/账号/权限等级/权限码/部门领导/创建人领导 + 档案词缀）。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.deps import get_current_user, require_permissions
from app.models.approval import ApprovalFlow, ApprovalTemplate, ApprovalTemplateNode
from app.models.org import User
from app.schemas.approval import ApprovalAct, TemplateCreate, TemplateUpdate
from app.schemas.common import ApiResponse, ok
from app.services import approval as approval_svc

router = APIRouter(prefix="/api/approval", tags=["审批流"])


# ---------------- 模板管理（超级管理员） ----------------
@router.get("/templates", response_model=ApiResponse)
def list_templates(
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("approval.template.manage")),
):
    rows = db.query(ApprovalTemplate).order_by(ApprovalTemplate.biz_type, ApprovalTemplate.priority.desc()).all()
    items = []
    for t in rows:
        d = {
            "id": t.id, "name": t.name, "biz_type": t.biz_type,
            "description": t.description, "priority": t.priority, "enabled": t.enabled,
            "trigger_field": t.trigger_field, "trigger_op": t.trigger_op,
            "trigger_value": t.trigger_value,
            "nodes": [
                {
                    "node_order": n.node_order, "node_name": n.node_name,
                    "approver_type": n.approver_type, "approver_value": n.approver_value,
                    "cond_field": n.cond_field, "cond_op": n.cond_op, "cond_value": n.cond_value,
                    "is_countersign": n.is_countersign,
                } for n in sorted(t.nodes, key=lambda x: x.node_order)
            ],
        }
        items.append(d)
    return ok(items)


@router.get("/templates/{template_id}", response_model=ApiResponse)
def get_template(
    template_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("approval.template.manage")),
):
    t = db.get(ApprovalTemplate, template_id)
    if not t:
        raise HTTPException(status_code=404, detail="模板不存在")
    return ok({
        "id": t.id, "name": t.name, "biz_type": t.biz_type,
        "description": t.description, "priority": t.priority, "enabled": t.enabled,
        "trigger_field": t.trigger_field, "trigger_op": t.trigger_op,
        "trigger_value": t.trigger_value,
        "nodes": [
            {
                "node_order": n.node_order, "node_name": n.node_name,
                "approver_type": n.approver_type, "approver_value": n.approver_value,
                "cond_field": n.cond_field, "cond_op": n.cond_op, "cond_value": n.cond_value,
                "is_countersign": n.is_countersign,
            } for n in sorted(t.nodes, key=lambda x: x.node_order)
        ],
    })


@router.post("/templates", response_model=ApiResponse)
def create_template(
    body: TemplateCreate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("approval.template.manage")),
):
    t = ApprovalTemplate(
        name=body.name, biz_type=body.biz_type, description=body.description,
        priority=body.priority, enabled=body.enabled,
        trigger_field=body.trigger_field, trigger_op=body.trigger_op,
        trigger_value=body.trigger_value,
    )
    db.add(t)
    db.flush()
    for n in body.nodes:
        db.add(ApprovalTemplateNode(
            template_id=t.id, node_order=n.node_order, node_name=n.node_name,
            approver_type=n.approver_type, approver_value=n.approver_value,
            cond_field=n.cond_field, cond_op=n.cond_op, cond_value=n.cond_value,
            is_countersign=n.is_countersign,
        ))
    db.commit()
    return ok({"id": t.id}, "模板创建成功")


@router.put("/templates/{template_id}", response_model=ApiResponse)
def update_template(
    template_id: int, body: TemplateUpdate,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("approval.template.manage")),
):
    t = db.get(ApprovalTemplate, template_id)
    if not t:
        raise HTTPException(status_code=404, detail="模板不存在")
    for field in ["name", "biz_type", "description", "priority", "enabled",
                  "trigger_field", "trigger_op", "trigger_value"]:
        val = getattr(body, field)
        if val is not None:
            setattr(t, field, val)
    if body.nodes is not None:
        # 全量替换节点
        for n in list(t.nodes):
            db.delete(n)
        db.flush()
        for n in body.nodes:
            db.add(ApprovalTemplateNode(
                template_id=t.id, node_order=n.node_order, node_name=n.node_name,
                approver_type=n.approver_type, approver_value=n.approver_value,
                cond_field=n.cond_field, cond_op=n.cond_op, cond_value=n.cond_value,
                is_countersign=n.is_countersign,
            ))
    db.commit()
    return ok({"id": t.id}, "模板更新成功")


@router.delete("/templates/{template_id}", response_model=ApiResponse)
def delete_template(
    template_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("approval.template.manage")),
):
    t = db.get(ApprovalTemplate, template_id)
    if not t:
        raise HTTPException(status_code=404, detail="模板不存在")
    db.delete(t)
    db.commit()
    return ok(message="模板已删除")


# ---------------- 我的待办 / 审批 ----------------
@router.get("/my-tasks", response_model=ApiResponse)
def my_tasks(
    biz_type: str = None,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("approval.todo.view")),
):
    tasks = approval_svc.get_my_tasks(db, user.id, biz_type=biz_type)
    for t in tasks:
        applicant = db.get(User, t["applicant_user_id"])
        t["applicant_name"] = applicant.real_name if applicant else f"用户{t['applicant_user_id']}"
    return ok(tasks)


@router.get("/flow-by-biz", response_model=ApiResponse)
def flow_by_biz(
    biz_type: str, biz_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("approval.todo.view")),
):
    """按业务单查询其审批流程详情（用于借阅/销毁界面查看审批进度）。"""
    f = db.query(ApprovalFlow).filter_by(biz_type=biz_type, biz_id=biz_id).first()
    if not f:
        return ok(None)
    detail = approval_svc.get_flow_detail(db, f.id)
    applicant = db.get(User, detail["applicant_user_id"])
    detail["applicant_name"] = applicant.real_name if applicant else ""
    return ok(detail)


@router.get("/flows", response_model=ApiResponse)
def list_flows(
    biz_type: str = None, status: str = None,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("approval.todo.view")),
):
    """审批看板：列出流程（超级管理员可见全部，其余可见自己发起或待办的）。"""
    q = db.query(ApprovalFlow)
    if biz_type:
        q = q.filter(ApprovalFlow.biz_type == biz_type)
    if status:
        q = q.filter(ApprovalFlow.status == status)
    rows = q.order_by(ApprovalFlow.id.desc()).limit(200).all()
    items = []
    for f in rows:
        applicant = db.get(User, f.applicant_user_id)
        items.append({
            "flow_id": f.id, "title": f.title, "biz_type": f.biz_type,
            "biz_id": f.biz_id, "status": f.status,
            "applicant_name": applicant.real_name if applicant else "",
            "created_at": f.created_at.isoformat() if f.created_at else "",
        })
    return ok(items)


@router.get("/flows/{flow_id}", response_model=ApiResponse)
def flow_detail(
    flow_id: int,
    db: Session = Depends(get_db),
    _: User = Depends(require_permissions("approval.todo.view")),
):
    detail = approval_svc.get_flow_detail(db, flow_id)
    if not detail:
        raise HTTPException(status_code=404, detail="流程不存在")
    applicant = db.get(User, detail["applicant_user_id"])
    detail["applicant_name"] = applicant.real_name if applicant else ""
    return ok(detail)


@router.post("/flows/{flow_id}/act", response_model=ApiResponse)
def act(
    flow_id: int, body: ApprovalAct,
    db: Session = Depends(get_db),
    user: User = Depends(require_permissions("approval.todo.view")),
):
    flow = db.get(ApprovalFlow, flow_id)
    if not flow:
        raise HTTPException(status_code=404, detail="流程不存在")
    is_admin = "super_admin" in getattr(user, "_role_codes", [])
    if not approval_svc.can_act(flow, user.id, is_admin=is_admin):
        raise HTTPException(status_code=403, detail="您不是当前节点的审批人")
    try:
        flow = approval_svc.act_on_flow(
            db, flow, operator_user_id=user.id, action=body.action,
            opinion=body.opinion, is_admin=is_admin,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    db.commit()
    return ok({"flow_status": flow.status}, "审批已完成")
