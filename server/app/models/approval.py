# -*- coding: utf-8 -*-
"""
app/models/approval.py
=======================
本文件定义「可配置审批流」相关数据表：
- ApprovalTemplate        审批流模板（按业务类型 + 档案词缀路由）
- ApprovalTemplateNode    模板中的审批节点（定义审批人来源与适用条件）
- ApprovalFlow           审批流程实例（一次业务单据对应的审批流转）
- ApprovalFlowNode       流程实例中的具体节点（已解析出实际审批人）
- ApprovalFlowRecord     实际审批动作记录
说明：审批人来源支持 角色 / 账号 / 权限等级 / 权限码 / 部门领导 / 创建人领导；
      模板与节点均可按「档案信息词缀」设置条件（档号/名称/关键词/分类名/部门名）。
"""
from typing import Optional

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, TimestampMixin


# 审批人来源类型
APPROVER_ROLE = "role"                      # 指定角色编码下的所有用户
APPROVER_USER = "user"                      # 指定账号（用户ID）
APPROVER_LEVEL = "permission_level"         # 权限等级（角色 role_level <= 阈值）
APPROVER_PERMISSION = "permission"          # 拥有某权限码的用户
APPROVER_DEPT_LEADER = "dept_leader"        # 档案归属部门负责人
APPROVER_CREATOR_LEADER = "creator_leader"  # 档案形成人所在部门负责人

# 词缀匹配字段与运算符
AFFIX_FIELDS = ["archive_no", "archive_name", "keyword", "category_name", "dept_name"]
AFFIX_OPS = ["startswith", "contains", "endswith", "eq"]


class ApprovalTemplate(Base, TimestampMixin):
    """审批流模板：定义某类业务如何审批。"""

    __tablename__ = "approval_template"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(100), comment="模板名称")
    biz_type: Mapped[str] = mapped_column(String(30), comment="业务类型 borrow/destroy")
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True, comment="说明")
    priority: Mapped[int] = mapped_column(Integer, default=0, comment="优先级，数字越大越优先匹配")
    enabled: Mapped[int] = mapped_column(Integer, default=1, comment="1启用0停用")
    # 模板级触发条件（按档案信息词缀路由）：命中才套用本模板
    trigger_field: Mapped[Optional[str]] = mapped_column(String(30), nullable=True, comment="触发字段")
    trigger_op: Mapped[Optional[str]] = mapped_column(String(20), nullable=True, comment="触发运算符")
    trigger_value: Mapped[Optional[str]] = mapped_column(String(200), nullable=True, comment="触发值")

    nodes: Mapped[list["ApprovalTemplateNode"]] = relationship(
        "ApprovalTemplateNode", back_populates="template",
        cascade="all, delete-orphan", lazy="selectin",
    )


class ApprovalTemplateNode(Base, TimestampMixin):
    """审批流模板节点：定义某一审批节点的审批人来源与适用条件。"""

    __tablename__ = "approval_template_node"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    template_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("approval_template.id"), comment="所属模板"
    )
    node_order: Mapped[int] = mapped_column(Integer, comment="节点顺序（从1开始）")
    node_name: Mapped[str] = mapped_column(String(100), comment="节点名称")
    approver_type: Mapped[str] = mapped_column(String(30), comment="审批人来源类型")
    approver_value: Mapped[Optional[str]] = mapped_column(
        String(200), nullable=True, comment="审批人取值（角色编码/用户ID/等级/权限码）"
    )
    # 节点级适用条件（按档案信息词缀）：满足才插入该节点，否则跳过
    cond_field: Mapped[Optional[str]] = mapped_column(String(30), nullable=True, comment="条件字段")
    cond_op: Mapped[Optional[str]] = mapped_column(String(20), nullable=True, comment="条件运算符")
    cond_value: Mapped[Optional[str]] = mapped_column(String(200), nullable=True, comment="条件值")
    is_countersign: Mapped[int] = mapped_column(Integer, default=0, comment="是否会签（多人需全部通过）")

    template: Mapped["ApprovalTemplate"] = relationship("ApprovalTemplate", back_populates="nodes")


class ApprovalFlow(Base, TimestampMixin):
    """审批流程实例：一笔业务单据对应的审批流转。"""

    __tablename__ = "approval_flow"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    biz_type: Mapped[str] = mapped_column(String(30), comment="业务类型 borrow/destroy")
    biz_id: Mapped[int] = mapped_column(Integer, comment="业务单ID（借阅单/销毁申请）")
    title: Mapped[str] = mapped_column(String(200), comment="流程标题")
    applicant_user_id: Mapped[int] = mapped_column(Integer, comment="发起人")
    template_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="套用的模板ID"
    )
    current_node: Mapped[int] = mapped_column(
        Integer, default=0, comment="当前节点序号，0 表示流转结束"
    )
    status: Mapped[str] = mapped_column(
        String(30), default="pending", comment="pending/approved/rejected"
    )
    archive_summary: Mapped[Optional[str]] = mapped_column(
        Text, nullable=True, comment="关联档案摘要（JSON：[{archive_no, archive_name}]）"
    )

    nodes: Mapped[list["ApprovalFlowNode"]] = relationship(
        "ApprovalFlowNode", back_populates="flow",
        cascade="all, delete-orphan", lazy="selectin",
    )


class ApprovalFlowNode(Base, TimestampMixin):
    """流程实例节点：已解析出实际审批人。"""

    __tablename__ = "approval_flow_node"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    flow_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("approval_flow.id"), comment="所属流程"
    )
    node_order: Mapped[int] = mapped_column(Integer, comment="节点顺序")
    node_name: Mapped[str] = mapped_column(String(100), comment="节点名称")
    approver_type: Mapped[str] = mapped_column(String(30), comment="审批人来源类型")
    approver_value: Mapped[Optional[str]] = mapped_column(String(200), nullable=True, comment="审批人取值")
    approver_user_ids: Mapped[Optional[str]] = mapped_column(
        String(500), nullable=True, comment="实际审批人ID列表（逗号分隔）"
    )
    acted_user_ids: Mapped[Optional[str]] = mapped_column(
        String(500), nullable=True, comment="已审批人ID列表（逗号分隔）"
    )
    node_status: Mapped[str] = mapped_column(
        String(30), default="pending", comment="pending/approved/rejected/skipped"
    )
    is_countersign: Mapped[int] = mapped_column(Integer, default=0, comment="是否会签")

    flow: Mapped["ApprovalFlow"] = relationship("ApprovalFlow", back_populates="nodes")


class ApprovalFlowRecord(Base, TimestampMixin):
    """实际审批动作记录。"""

    __tablename__ = "approval_flow_record"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    flow_id: Mapped[int] = mapped_column(Integer, ForeignKey("approval_flow.id"), comment="流程ID")
    node_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="节点ID")
    action: Mapped[str] = mapped_column(String(20), comment="agree/reject")
    opinion: Mapped[Optional[str]] = mapped_column(Text, comment="审批意见")
    operator_user_id: Mapped[int] = mapped_column(Integer, comment="操作人")
    acted_at: Mapped[Optional[DateTime]] = mapped_column(DateTime, nullable=True, comment="审批时间")
