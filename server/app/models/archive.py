# -*- coding: utf-8 -*-
"""
app/models/archive.py
======================
本文件定义「档案核心」相关数据表：
- ArchiveCategory 档案分类（树形，带默认密级/期限/编号规则）
- Archive 档案主表（最核心业务实体，含状态流转、编号、密级等）
- ArchiveFile 电子档案文件（一档多文件，版本管理，SHA256 校验）
说明：文件二进制不直接存库，只存元数据与存储标识（storage_key）。
"""
from datetime import date, datetime
from typing import Optional

from sqlalchemy import (
    Date,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, SoftDeleteMixin, TimestampMixin


class ArchiveCategory(Base, TimestampMixin):
    """档案分类表：树形结构，可挂默认密级/期限/编号规则。"""

    __tablename__ = "archive_category"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    parent_id: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("archive_category.id"), nullable=True, comment="父分类"
    )
    category_code: Mapped[str] = mapped_column(String(50), unique=True, comment="分类编码")
    category_name: Mapped[str] = mapped_column(String(100), comment="分类名称")
    archive_level: Mapped[int] = mapped_column(Integer, default=1, comment="层级")
    default_security_level_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="默认密级ID"
    )
    default_retention_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="默认保管期限ID"
    )
    numbering_rule_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="默认编号规则ID"
    )
    sort_order: Mapped[int] = mapped_column(Integer, default=0, comment="排序号")
    status: Mapped[int] = mapped_column(Integer, default=1, comment="1启用0停用")


class Archive(Base, TimestampMixin, SoftDeleteMixin):
    """档案主表：系统最核心业务实体。"""

    __tablename__ = "archive"
    __table_args__ = (UniqueConstraint("archive_no", name="uq_archive_no"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    archive_no: Mapped[str] = mapped_column(String(100), unique=True, comment="档案编号，规则引擎生成")
    archive_name: Mapped[str] = mapped_column(String(300), comment="档案名称")
    category_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("archive_category.id"), comment="分类ID"
    )
    dept_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("department.id"), comment="归属部门ID"
    )
    security_level_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="密级ID"
    )
    retention_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="保管期限ID"
    )
    archive_year: Mapped[Optional[int]] = mapped_column(Integer, comment="档案年度")
    formation_date: Mapped[Optional[date]] = mapped_column(Date, comment="形成日期")
    filing_date: Mapped[Optional[date]] = mapped_column(Date, comment="归档日期")
    creator_user_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="形成/登记人"
    )
    archiver_user_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="归档经办人"
    )
    keyword: Mapped[Optional[str]] = mapped_column(String(500), comment="关键词")
    summary: Mapped[Optional[str]] = mapped_column(Text, comment="摘要")
    physical_location: Mapped[Optional[str]] = mapped_column(
        String(300), comment="物理位置（库房/柜，来自位置字典）"
    )
    # ---- 档案清单扩展字段（#4）----
    file_type: Mapped[Optional[str]] = mapped_column(
        String(50), comment="文件类型（来自 file_type 字典项名称）"
    )
    owner_name: Mapped[Optional[str]] = mapped_column(
        String(100), comment="档案负责人"
    )
    cabinet_no: Mapped[Optional[str]] = mapped_column(String(50), comment="柜号")
    column_no: Mapped[Optional[str]] = mapped_column(String(50), comment="列号")
    layer_no: Mapped[Optional[str]] = mapped_column(String(50), comment="层号")
    storage_type: Mapped[Optional[str]] = mapped_column(
        String(20), comment="保存类型（纸质/电子/混合，来自 storage_type 字典项名称）"
    )
    copy_count: Mapped[int] = mapped_column(Integer, default=1, comment="保存份数")
    archive_date: Mapped[Optional[date]] = mapped_column(Date, comment="存档日期")
    validity_months: Mapped[Optional[int]] = mapped_column(
        Integer, comment="有效期（月），用于自动计算到期日期"
    )
    expiry_date: Mapped[Optional[date]] = mapped_column(
        Date, comment="到期日期（由存档日期+有效期自动计算，禁止手动修改）"
    )
    remark: Mapped[Optional[str]] = mapped_column(Text, comment="备注")
    volume_no: Mapped[Optional[str]] = mapped_column(String(100), comment="卷/盒号")
    page_count: Mapped[int] = mapped_column(Integer, default=0, comment="页数")
    file_count: Mapped[int] = mapped_column(Integer, default=0, comment="电子文件数")
    status: Mapped[str] = mapped_column(
        String(30), default="draft", comment="draft/pending/archive/borrowed/locked/damaged/destroy_pending/destroyed"
    )


class ArchiveFile(Base, TimestampMixin):
    """电子档案文件表：一档多文件，支持版本管理与完整性校验。"""

    __tablename__ = "archive_file"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    archive_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("archive.id"), index=True, comment="所属档案ID"
    )
    file_name: Mapped[str] = mapped_column(String(255), comment="原始文件名")
    file_ext: Mapped[Optional[str]] = mapped_column(String(20), comment="扩展名")
    mime_type: Mapped[Optional[str]] = mapped_column(String(100), comment="MIME类型")
    storage_provider: Mapped[str] = mapped_column(
        String(30), default="local", comment="local/nas/object/encrypted"
    )
    storage_key: Mapped[str] = mapped_column(String(500), comment="存储标识/相对路径")
    file_size: Mapped[int] = mapped_column(Integer, default=0, comment="文件大小(字节)")
    sha256: Mapped[Optional[str]] = mapped_column(String(64), comment="完整性哈希")
    version_no: Mapped[int] = mapped_column(Integer, default=1, comment="版本号")
    is_current: Mapped[int] = mapped_column(Integer, default=1, comment="是否当前版本1是0否")
    ocr_status: Mapped[str] = mapped_column(String(20), default="none", comment="none/pending/done/failed")
    ocr_text: Mapped[Optional[str]] = mapped_column(Text, comment="OCR识别文本")
    uploaded_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, comment="上传人")
    uploaded_at: Mapped[Optional[datetime]] = mapped_column(DateTime, comment="上传时间")
    status: Mapped[str] = mapped_column(
        String(20), default="active", comment="active/deleted/quarantined"
    )


class ArchiveUpdateLog(Base, TimestampMixin):
    """档案更新记录：每次档案信息变更写入一条，记录操作人与变更字段。"""

    __tablename__ = "archive_update_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    archive_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("archive.id"), index=True, comment="所属档案ID"
    )
    operator_id: Mapped[Optional[int]] = mapped_column(
        Integer, nullable=True, comment="操作人ID"
    )
    operator_name: Mapped[Optional[str]] = mapped_column(
        String(100), comment="操作人姓名（冗余，便于历史追溯）"
    )
    action: Mapped[str] = mapped_column(
        String(30), default="update", comment="create/update/archive 等动作"
    )
    changed_fields: Mapped[Optional[str]] = mapped_column(
        Text, comment="变更的字段名列表，JSON 数组字符串"
    )
