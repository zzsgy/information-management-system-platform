# -*- coding: utf-8 -*-
"""
app/db/seed.py
===============
本文件负责「首次启动初始化基础数据」：
- 创建超级管理员角色与全部权限。
- 创建默认管理员账号（admin / Admin@123456），状态 active。
- 创建默认密级、保管期限、一个全局编号规则。
说明：仅在数据为空时执行，可重复安全调用（幂等）。
"""
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.models.dicts import (
    DictItem,
    DictType,
    NumberingRule,
    RetentionPeriod,
    SecurityLevel,
    SystemParameter,
)
from app.models.org import (
    Department,
    Permission,
    Role,
    RolePermission,
    User,
    UserRole,
)

# 系统内置权限清单（模块:动作）
PERMISSIONS = [
    ("user", "read"), ("user", "write"), ("user", "audit"),
    ("archive", "read"), ("archive", "write"), ("archive", "delete"), ("archive", "admin"),
    ("file", "upload"), ("file", "download"), ("file", "preview"),
    ("borrow", "apply"), ("borrow", "approve"),
    ("destroy", "apply"), ("destroy", "approve"), ("destroy", "execute"),
    ("notice", "publish"), ("notice", "read"),
    ("dict", "manage"), ("log", "read"),
    ("approval", "template.manage"), ("approval", "todo.view"),
]

# 权限中文名称：种子写入 permission.perm_name，并对历史库同步更新，
# 使「角色权限」等处展示为中文（键为 模块.动作 编码）。
PERMISSION_NAMES = {
    "user.read": "用户查看",
    "user.write": "用户管理",
    "user.audit": "用户审核",
    "archive.read": "档案查看",
    "archive.write": "档案管理",
    "archive.delete": "档案删除",
    "archive.admin": "档案高级管理",
    "file.upload": "文件上传",
    "file.download": "文件下载",
    "file.preview": "文件预览",
    "borrow.apply": "借阅申请",
    "borrow.approve": "借阅审批",
    "destroy.apply": "销毁申请",
    "destroy.approve": "销毁审批",
    "destroy.execute": "销毁执行",
    "notice.publish": "公告发布",
    "notice.read": "公告查看",
    "dict.manage": "字典管理",
    "log.read": "日志查看",
    "approval.template.manage": "审批模板管理",
    "approval.todo.view": "待办查看",
}

# 权限中文说明：建角色时展示给管理员，帮助理解每项权限的含义。
# 与上方 PERMISSIONS 一一对应（键为 模块.动作 编码）。
PERMISSION_DESCRIPTIONS = {
    "user.read": "查看用户列表与基本信息",
    "user.write": "新增、编辑用户账号与部门归属",
    "user.audit": "审核用户注册/开通申请",
    "archive.read": "查看档案台账、详情与综合查询",
    "archive.write": "新增、编辑、归档档案",
    "archive.delete": "删除（软删除）档案记录",
    "archive.admin": "档案高级管理：分类、编号规则等",
    "file.upload": "上传电子文件原件",
    "file.download": "下载电子文件原件",
    "file.preview": "在线预览电子文件",
    "borrow.apply": "提交档案借阅申请",
    "borrow.approve": "审批档案借阅申请",
    "destroy.apply": "提交档案鉴定销毁申请",
    "destroy.approve": "审批档案销毁申请",
    "destroy.execute": "执行已批准的档案销毁",
    "notice.publish": "发布通知公告",
    "notice.read": "查看通知公告",
    "dict.manage": "管理字典（密级/保管期限/物理位置等）",
    "log.read": "查看操作日志与审计记录",
    "approval.template.manage": "管理审批模板与审批流",
    "approval.todo.view": "查看并处理我的待办审批",
}

DEFAULT_ADMIN_USERNAME = "admin"
DEFAULT_ADMIN_PASSWORD = "Admin@123456"


def seed(db: Session):
    """执行基础数据初始化（幂等）。"""
    # 1) 权限（perm_name 存中文名；历史库若仍是英文则同步更新）
    perm_map = {}
    for module, action in PERMISSIONS:
        code = f"{module}.{action}"
        cn_name = PERMISSION_NAMES.get(code, f"{module} {action}")
        perm = db.query(Permission).filter_by(perm_code=code).first()
        if not perm:
            perm = Permission(perm_code=code, perm_name=cn_name, module=module, action=action)
            db.add(perm)
            db.flush()
        elif perm.perm_name != cn_name:
            # 兼容旧库：把英文 perm_name 更新为中文名
            perm.perm_name = cn_name
        perm_map[code] = perm.id

    # 2) 超级管理员角色
    role = db.query(Role).filter_by(role_code="super_admin").first()
    if not role:
        role = Role(role_code="super_admin", role_name="超级管理员", role_level=1, status=1)
        db.add(role)
        db.flush()
        for pid in perm_map.values():
            db.add(RolePermission(role_id=role.id, permission_id=pid))

    # 3) 默认密级
    if db.query(func.count()).select_from(SecurityLevel).scalar() == 0:
        for code, name, lvl in [
            ("public", "公开", 1), ("internal", "内部", 2),
            ("secret", "秘密", 3), ("confidential", "机密", 4),
        ]:
            db.add(SecurityLevel(code=code, name=name, level=lvl))

    # 4) 默认保管期限
    if db.query(func.count()).select_from(RetentionPeriod).scalar() == 0:
        for code, name, years in [
            ("permanent", "永久", 0), ("y30", "30年", 30),
            ("y10", "10年", 10), ("y5", "5年", 5),
        ]:
            db.add(RetentionPeriod(code=code, name=name, years=years))

    # 5) 默认编号规则（全局，按年度重置，4位流水）
    if db.query(func.count()).select_from(NumberingRule).scalar() == 0:
        db.add(
            NumberingRule(
                rule_code="default_global",
                rule_name="默认全局规则",
                scope_type="global",
                pattern="{DEPT}-{CATEGORY}-{YEAR}-{SEQ4}",
                reset_cycle="YEAR",
                sequence_length=4,
                enabled=1,
                priority=0,
            )
        )

    # 5.5) 默认档案编号模式（auto 自动生成 / manual 手动输入）
    if db.query(func.count()).select_from(SystemParameter).filter(
        SystemParameter.param_key == "archive_no_mode"
    ).scalar() == 0:
        db.add(SystemParameter(
            param_key="archive_no_mode", param_value="auto",
            description="档案编号模式：auto 自动生成 / manual 手动输入",
        ))

    # 6) 默认根部门（管理员归属）
    root_dept = db.query(Department).filter_by(dept_code="ROOT").first()
    if not root_dept:
        root_dept = Department(
            dept_code="ROOT", dept_name="公司总部", sort_order=0, status=1
        )
        db.add(root_dept)
        db.flush()

    # 6.5) 字典类型：物理位置（storage_location），并给一个默认项
    loc_type = db.query(DictType).filter_by(type_code="storage_location").first()
    if not loc_type:
        loc_type = DictType(type_code="storage_location", type_name="物理位置")
        db.add(loc_type)
        db.flush()
    if db.query(func.count()).select_from(DictItem).filter(
        DictItem.type_code == "storage_location"
    ).scalar() == 0:
        db.add(DictItem(
            type_code="storage_location", item_code="unspecified",
            item_name="未指定位置", sort_order=0, enabled=1,
        ))

    # 6.6) 字典类型：文件类型（file_type）/ 保存类型（storage_type）
    def _ensure_dict(type_code, type_name, items):
        dtype = db.query(DictType).filter_by(type_code=type_code).first()
        if not dtype:
            dtype = DictType(type_code=type_code, type_name=type_name)
            db.add(dtype)
            db.flush()
        existing = {
            it[0] for it in db.query(DictItem.item_code)
            .filter(DictItem.type_code == type_code).all()
        }
        for code, name in items:
            if code not in existing:
                db.add(DictItem(
                    type_code=type_code, item_code=code, item_name=name,
                    sort_order=0, enabled=1,
                ))

    _ensure_dict("file_type", "文件类型", [
        ("doc", "文书"), ("contract", "合同"), ("finance", "财务"),
        ("image", "图片"), ("video", "音视频"), ("tech", "技术资料"), ("other", "其他"),
    ])
    _ensure_dict("storage_type", "保存类型", [
        ("paper", "纸质"), ("electronic", "电子"), ("mixed", "混合"),
    ])

    # 7) 默认管理员账号 + 角色
    if db.query(func.count()).select_from(User).scalar() == 0:
        admin = User(
            username=DEFAULT_ADMIN_USERNAME,
            password_hash=hash_password(DEFAULT_ADMIN_PASSWORD),
            real_name="系统管理员",
            dept_id=root_dept.id,
            account_status="active",
        )
        db.add(admin)
        db.flush()
        db.add(UserRole(user_id=admin.id, role_id=role.id, scope_type="ALL", scope_value="*"))

    db.commit()
