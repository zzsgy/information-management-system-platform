# -*- coding: utf-8 -*-
"""
app/models/__init__.py
======================
本文件汇总导出所有 ORM 模型，供 Alembic / 服务层统一引用。
说明：新增模型后请在此处 import，确保 create_all 与迁移能发现它们。
"""
from app.models.archive import Archive, ArchiveCategory, ArchiveFile
from app.models.approval import (
    ApprovalFlow,
    ApprovalFlowNode,
    ApprovalFlowRecord,
    ApprovalTemplate,
    ApprovalTemplateNode,
)
from app.models.business import (
    BorrowDetail,
    BorrowRecord,
    DamageRecord,
    DestroyBatch,
    DestroyDetail,
    DestroyRequest,
    Notice,
    NoticeReceiver,
)
from app.models.dicts import (
    DictItem,
    DictType,
    NumberingRule,
    NumberingSequence,
    RetentionPeriod,
    SecurityLevel,
    SystemParameter,
)
from app.models.logs import FileAccessLog, OperationLog
from app.models.org import (
    DataScopeRule,
    Department,
    Permission,
    Role,
    RolePermission,
    User,
    UserRole,
)

# 导出清单（便于 from app.models import * 或 Alembic 自动发现）
__all__ = [
    "Department", "User", "Role", "Permission", "UserRole",
    "RolePermission", "DataScopeRule",
    "ArchiveCategory", "Archive", "ArchiveFile",
    "BorrowRecord", "BorrowDetail", "DamageRecord",
    "DestroyRequest", "DestroyBatch", "DestroyDetail",
    "ApprovalTemplate", "ApprovalTemplateNode",
    "ApprovalFlow", "ApprovalFlowNode", "ApprovalFlowRecord",
    "Notice", "NoticeReceiver",
    "SecurityLevel", "RetentionPeriod", "DictType", "DictItem",
    "NumberingRule", "NumberingSequence", "SystemParameter",
    "OperationLog", "FileAccessLog",
]
