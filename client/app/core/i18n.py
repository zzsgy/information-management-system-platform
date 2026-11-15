# -*- coding: utf-8 -*-
"""
app/core/i18n.py
================
界面中文化映射：档案状态、权限、角色等代码 -> 中文显示。
集中管理，便于统一替换与维护。
"""


# 档案状态：存储值 -> 中文
STATUS_NAMES = {
    "draft": "草稿",
    "pending": "待归档",
    "archive": "已归档",
    "borrowed": "借阅中",
    "locked": "封存",
    "damaged": "破损",
    "destroy_pending": "待销毁",
    "destroyed": "已销毁",
    "approved": "已批准",
    "rejected": "已驳回",
    "returned": "已归还",
    "executed": "已执行",
    "reported": "已登记",
    "processing": "处理中",
    "resolved": "已解决",
    "closed": "已关闭",
}

# 权限编码 -> 中文名称 + 说明
PERM_NAMES = {
    "archive.read": ("档案查看", "可浏览档案列表与详情"),
    "archive.write": ("档案编辑", "可新建、修改、归档档案"),
    "archive.admin": ("档案管理", "可维护分类、编号规则等档案配置"),
    "archive.share": ("档案共享", "可在管理范围内按人员或部门授权指定档案"),
    "security.manage": ("安全运维", "可执行文件完整性校验及已开放的备份运维操作"),
    "dict.manage": ("字典维护", "可维护文件类型、物理位置等字典项"),
    "system.admin": ("系统管理", "可管理系统设置"),
    "user.manage": ("用户管理", "可管理用户与角色"),
    "approval.manage": ("审批管理", "可管理审批流模板与节点"),
    "log.view": ("日志查看", "可查看操作日志"),
}

# 角色编码 -> 中文名称
ROLE_NAMES = {
    "super_admin": "超级管理员",
    "archive_admin": "档案管理员",
    "archive_user": "档案录入员",
    "viewer": "只读用户",
}


def status_name(code) -> str:
    """档案状态中文名，未知值原样返回。"""
    if code is None:
        return ""
    return STATUS_NAMES.get(str(code), str(code))


def perm_name(code: str) -> str:
    """权限中文名，未知编码原样返回。"""
    return PERM_NAMES.get(code, (code, ""))[0]


def perm_desc(code: str) -> str:
    """权限说明，未知返回空。"""
    return PERM_NAMES.get(code, (code, ""))[1]


def role_name(code: str) -> str:
    """角色中文名，未知编码原样返回。"""
    return ROLE_NAMES.get(code, code)
