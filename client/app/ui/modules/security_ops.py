# -*- coding: utf-8 -*-
"""
app/ui/modules/security_ops.py
==============================
数据安全运维模块（需要独立 security.manage 权限）：
- 电子文件完整性校验：重算每个文件的 SHA256 并与入库哈希比对，列出异常。
- 配套备份包：包含数据库、附件及校验清单；在线只校验恢复包并展示停服恢复说明。
后端接口：/api/security/integrity/check、/api/security/db-backup、
          /api/security/db-backups、/api/security/db-restore。
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTableWidget, QPushButton, QLabel,
)

from app.ui.base import BaseModule
from app.core.api_client import ApiError
from app.core.tasks import run_task


class SecurityOpsModule(BaseModule):
    """数据安全运维：文件校验 + 数据库备份恢复。"""

    title = "数据安全运维"

    def __init__(self):
        super().__init__()
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)

        # 一、电子文件完整性校验
        v.addWidget(QLabel("一、电子文件完整性校验"))
        bar1 = QHBoxLayout()
        self.btn_check = QPushButton("开始校验")
        self.btn_check.clicked.connect(self.run_check)
        bar1.addWidget(self.btn_check)
        bar1.addStretch(1)
        v.addLayout(bar1)
        self.check_summary = QLabel("尚未校验")
        v.addWidget(self.check_summary)
        self.check_table = QTableWidget()
        v.addWidget(self.check_table, 1)

        # 二、数据库备份与恢复
        v.addWidget(QLabel("二、数据库与附件备份；恢复需停服执行"))
        bar2 = QHBoxLayout()
        self.btn_backup = QPushButton("立即备份")
        self.btn_backup.clicked.connect(self.do_backup)
        self.btn_restore = QPushButton("校验恢复包/查看恢复说明")
        self.btn_restore.clicked.connect(self.do_restore)
        bar2.addWidget(self.btn_backup)
        bar2.addWidget(self.btn_restore)
        bar2.addStretch(1)
        v.addLayout(bar2)
        self.backup_table = QTableWidget()
        v.addWidget(self.backup_table, 1)

    # ---------------- 电子文件完整性校验 ----------------
    def run_check(self):
        """完整性校验在后台请求，耗时期间防止重复触发。"""
        self.check_summary.setText("正在校验，请稍候…")
        run_task(self, self.api.integrity_check, self._check_finished,
                 self._check_failed, controls=(self.btn_check,))

    def _check_failed(self, error):
        """服务端未确认成功时标记失败，不能把空列表显示为全部正常。"""
        self.check_summary.setText("校验未完成")
        self.handle_error(error, "完整性校验")

    def _check_finished(self, data):
        """在主线程展示完整性校验汇总及异常清单。"""
        self.check_summary.setText(
            f"校验完成：共 {data.get('total', 0)} 项，正常 {data.get('ok', 0)} 项，"
            f"异常 {data.get('failed', 0)} 项")
        items = data.get("items", [])
        self.fill_table(
            self.check_table, items,
            ["文件ID", "文件名", "异常原因", "库内哈希(前12)", "实际哈希(前12)"],
            mapping=["file_id", "file_name", "reason",
                     lambda r: (r.get("expected") or "")[:12],
                     lambda r: (r.get("actual") or "")[:12]],
        )
        if not items:
            self.show_info("未发现异常，全部电子文件完整 ✅")

    # ---------------- 数据库备份 / 恢复 ----------------
    def do_backup(self):
        """后台执行备份，备份与恢复按钮互斥避免重复提交。"""
        run_task(self, self.api.db_backup, self._backup_finished,
                 lambda error: self.handle_error(error, "数据库备份"),
                 controls=(self.btn_backup, self.btn_restore))

    def _backup_finished(self, result):
        """只在服务端明确成功后展示备份路径。"""
        self.show_info(f"备份成功：{result.get('file')}")
        self.refresh_backups()

    def refresh_backups(self):
        """拉取备份文件列表。"""
        run_task(self, self.api.list_db_backups, self._backups_loaded,
                 lambda error: self.handle_error(error, "加载备份列表"))

    def _backups_loaded(self, rows):
        """后台读取后在主线程更新备份列表。"""
        self.fill_table(self.backup_table, rows,
                        ["备份文件", "大小(字节)", "生成时间"],
                        mapping=["name", "size", "mtime"])

    def do_restore(self):
        """在线只校验选中的备份，实际恢复由停服脚本写入新数据库与目录。"""
        row = self.selected_row(self.backup_table)
        if not row:
            self.show_info("请先在下方列表选择要校验的备份包")
            return
        name = row.get("name")  # 文件名来自服务端清单，不接受客户端任意本地路径。
        run_task(self, lambda: self.api.db_restore(name), self._restore_finished,
                 self._restore_check_failed,
                 controls=(self.btn_backup, self.btn_restore, self.btn_check))

    def _restore_finished(self, result):
        """即使校验返回成功也不声称已经恢复在线业务库。"""
        self.show_info(result.get("message") or "备份校验完成。请按部署文档停服，在新的空数据库和附件目录执行恢复。")

    def _restore_check_failed(self, error):
        """409 表示后端禁止在线覆盖并提供操作说明；其余错误如实提示。"""
        if isinstance(error, ApiError) and error.status == 409:
            self.show_info(str(error))
        else:
            self.handle_error(error, "恢复包校验")

    def showEvent(self, event):
        if self.backup_table.rowCount() == 0:
            self.refresh_backups()
        super().showEvent(event)
