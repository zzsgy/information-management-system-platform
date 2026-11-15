# -*- coding: utf-8 -*-
"""
app/ui/modules/file_manage.py
=============================
电子文件管理模块：按档案查看并管理其电子文件原件。
- 先选择档案，再列出该档案的全部电子文件（上传 / 预览 / 下载）。
- 后端 files API 已就绪：
  · GET  /api/files?archive_id=        列表（需要 archive.read）
  · POST /api/files/upload?archive_id= 上传（需要 file.upload）
  · GET  /api/files/{id}/preview       预览（需要 file.preview）
  · GET  /api/files/{id}/download      下载（需要 file.download）
"""
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QTableWidget, QPushButton, QLabel,
    QFileDialog, QDialog, QListWidget, QListWidgetItem, QLineEdit,
    QDialogButtonBox,
)
from PySide6.QtCore import Qt

from app.ui.base import BaseModule
from app.core.api_client import ApiError
from app.core import session
from app.core.tasks import run_task


class ArchivePickerDialog(QDialog):
    """档案选择器：按关键字搜索档案并选中一个。"""

    def __init__(self, api, parent=None):
        super().__init__(parent)
        self.api = api
        self.selected = None
        self.setWindowTitle("选择档案")
        self.resize(540, 440)
        v = QVBoxLayout(self)

        # 搜索栏：输入关键字后点击「搜索」
        bar = QHBoxLayout()
        self.kw = QLineEdit()
        self.kw.setPlaceholderText("输入档号或名称关键字（留空列出最近档案）")
        btn = QPushButton("搜索")
        btn.clicked.connect(self.search)
        bar.addWidget(self.kw, 1)
        bar.addWidget(btn)
        v.addLayout(bar)

        # 结果列表：双击即选中
        self.lst = QListWidget()
        self.lst.itemDoubleClicked.connect(self.accept)
        v.addWidget(self.lst, 1)

        # 确认/取消按钮
        box = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        box.accepted.connect(self.accept)
        box.rejected.connect(self.reject)
        v.addWidget(box)

        self.search()  # 初始列出最近档案

    def search(self):
        """调用档案列表接口，按关键字搜索并刷新结果。"""
        kw = self.kw.text().strip() or None
        try:
            res = self.api.get("/api/archives",
                               params={"keyword": kw, "page": 1, "page_size": 50})
            rows = (res.get("data") or {}).get("items", [])
        except ApiError:
            rows = []  # 搜索失败时不弹窗，避免打断选择
        self.lst.clear()
        for r in rows:
            text = f"{r.get('archive_no', '')}  {r.get('archive_name', '')}"
            item = QListWidgetItem(text)
            item.setData(Qt.UserRole, r)  # 绑定整行档案数据
            self.lst.addItem(item)

    def accept(self):
        item = self.lst.currentItem()
        if not item:
            return
        self.selected = item.data(Qt.UserRole)
        super().accept()


class FileModule(BaseModule):
    """电子文件管理：围绕单个档案的电子文件原件操作。"""

    title = "电子文件"

    def __init__(self):
        super().__init__()
        self.current_archive = None
        self._build_ui()

    def _build_ui(self):
        v = QVBoxLayout(self)

        # 顶部栏：显示当前档案 + 选择/上传/预览/下载/刷新
        bar = QHBoxLayout()
        self.archive_label = QLabel("未选择档案")
        self.btn_pick = QPushButton("选择档案")
        self.btn_pick.clicked.connect(self.select_archive)
        self.btn_upload = QPushButton("上传文件")
        self.btn_upload.clicked.connect(self.upload)
        self.btn_preview = QPushButton("预览")
        self.btn_preview.clicked.connect(self.preview)
        self.btn_download = QPushButton("下载")
        self.btn_download.clicked.connect(self.download)
        self.btn_refresh = QPushButton("刷新")
        self.btn_refresh.clicked.connect(self.refresh)
        bar.addWidget(QLabel("当前档案："))
        bar.addWidget(self.archive_label, 1)
        bar.addWidget(self.btn_pick)
        bar.addWidget(self.btn_upload)
        bar.addWidget(self.btn_preview)
        bar.addWidget(self.btn_download)
        bar.addWidget(self.btn_refresh)
        v.addLayout(bar)

        # 按权限控制按钮可用性（无权限则禁用，避免误点报错）
        self.btn_upload.setEnabled(session.has_perm("file.upload"))
        self.btn_preview.setEnabled(session.has_perm("file.preview"))
        self.btn_download.setEnabled(session.has_perm("file.download"))

        # 文件列表
        self.table = QTableWidget()
        v.addWidget(self.table, 1)

    def select_archive(self):
        """打开档案选择器，选定后刷新文件列表。"""
        d = ArchivePickerDialog(self.api, self)
        if d.exec() == QDialog.Accepted and d.selected:
            self.current_archive = d.selected
            a = self.current_archive
            self.archive_label.setText(f"{a.get('archive_no', '')}  {a.get('archive_name', '')}")
            self.refresh()

    def refresh(self):
        """后台读取当前档案的附件，不在界面线程等待网络。"""
        if not self.current_archive:
            return
        aid = self.current_archive.get("id")  # 捕获选择时的档案，避免工作线程读控件状态。
        run_task(self, lambda: self.api.list_files(aid), self._files_loaded,
                 lambda error: self.handle_error(error, "加载文件列表"),
                 controls=(self.btn_pick, self.btn_refresh))

    def _files_loaded(self, rows):
        """在主线程格式化返回的文件列表并更新表格。"""
        # 补充格式化字段，便于表格展示
        for r in rows:
            r["_size"] = self._fmt_size(r.get("file_size", 0))
            r["_current"] = "是" if r.get("is_current") == 1 else "否"
        self.fill_table(
            self.table, rows,
            ["ID", "文件名", "扩展名", "大小", "版本", "当前版本", "上传时间"],
            mapping=["id", "file_name", "file_ext", "_size",
                     "version_no", "_current", "uploaded_at"],
        )

    @staticmethod
    def _fmt_size(n):
        """将字节数格式化为易读文本。"""
        try:
            n = int(n)
        except (TypeError, ValueError):
            return "-"
        if n < 1024:
            return f"{n} B"
        if n < 1024 * 1024:
            return f"{n / 1024:.1f} KB"
        return f"{n / 1024 / 1024:.1f} MB"

    def _selected_file(self):
        """返回当前选中的文件行，未选则提示。"""
        row = self.selected_row(self.table)
        if not row:
            self.show_info("请先选择一行文件")
            return None
        return row

    def upload(self):
        """选择本地文件上传到当前档案。"""
        if not self.current_archive:
            self.show_info("请先选择档案")
            return
        path, _ = QFileDialog.getOpenFileName(self, "选择要上传的文件")
        if not path:
            return
        aid = self.current_archive.get("id")
        run_task(self, lambda: self.api.upload_archive_file(aid, path),
                 self._uploaded, lambda error: self.handle_error(error, "上传"),
                 controls=(self.btn_upload, self.btn_pick))

    def _uploaded(self, _result):
        """上传完成后刷新同一档案的附件列表。"""
        self.show_info("上传成功")
        self.refresh()

    def preview(self):
        """复用支持后台下载的内嵌预览，调用真实存在的 preview_file 接口。"""
        f = self._selected_file()
        if not f:
            return
        from app.ui.preview import PreviewDialog
        dialog = PreviewDialog(self.api, f["id"], f["file_name"], f.get("mime_type") or "")
        dialog.exec()

    def download(self):
        """将选中的电子文件保存到本地。"""
        f = self._selected_file()
        if not f:
            return
        fid = f.get("id")
        default = f.get("file_name") or f"file_{fid}"
        path, _ = QFileDialog.getSaveFileName(self, "下载文件", default)
        if not path:
            return
        run_task(self, lambda: self.api.download_archive_file(fid, path),
                 lambda _result: self.show_info("下载完成"),
                 lambda error: self.handle_error(error, "下载"),
                 controls=(self.btn_download,))
