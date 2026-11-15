# -*- coding: utf-8 -*-
"""
app/ui/preview.py
==================
电子文件「在线预览」对话框：
- 图片：内嵌渲染（自适应缩放）
- PDF：使用 QtPdf 内嵌渲染
- 文本（txt/md/json/xml/csv 等）：内嵌显示
- 其他（Office 等）：提示并用系统默认程序打开
"""
import os
import tempfile
import threading

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QDesktopServices, QPixmap
from PySide6.QtWidgets import (
    QDialog, QHBoxLayout, QLabel, QPushButton, QScrollArea, QTextEdit,
    QVBoxLayout, QMessageBox,
)

try:
    from PySide6.QtPdf import QPdfDocument
    from PySide6.QtPdfWidgets import QPdfView
    _HAS_PDF = True
except Exception:  # pragma: no cover
    _HAS_PDF = False

from app.core.api_client import ApiError
from app.core.tasks import run_task

_IMAGE_EXT = {".png", ".jpg", ".jpeg", ".bmp", ".gif", ".webp", ".svg"}
_TEXT_EXT = {".txt", ".md", ".json", ".xml", ".csv", ".log", ".ini", ".yaml", ".yml", ".py", ".bat", ".sh"}


class PreviewDialog(QDialog):
    """文件预览对话框：根据扩展名选择内嵌渲染或外部打开。"""

    def __init__(self, api, file_id: int, file_name: str, mime_type: str = ""):
        super().__init__()
        self.api = api
        self.file_id = file_id
        self.file_name = file_name
        self.mime_type = mime_type or ""
        self.temp_path = None
        self._cancelled = threading.Event()  # 用户关闭时通知下载任务清理临时副本。
        self._pdf_document = None  # PDF 组件占用文件时先关闭文档再删除副本。
        self.setWindowTitle(f"预览 - {file_name}")
        self.resize(820, 640)
        self._build_ui()
        self._load()

    def _build_ui(self):
        v = QVBoxLayout(self)
        self.info = QLabel("正在加载预览…")
        self.info.setAlignment(Qt.AlignCenter)
        v.addWidget(self.info, 1)
        bar = QHBoxLayout()
        self.open_btn = QPushButton("用系统程序打开")
        self.open_btn.clicked.connect(self._open_external)
        bar.addStretch(1)
        bar.addWidget(self.open_btn)
        v.addLayout(bar)

    def _ext(self) -> str:
        return os.path.splitext(self.file_name)[1].lower()

    def _load(self):
        """在后台下载预览文件；取消后即使请求完成也不遗留文件。"""
        try:
            fd, self.temp_path = tempfile.mkstemp(suffix=self._ext())
            os.close(fd)
        except Exception as e:  # noqa
            self._load_failed(e)
            return
        # 捕获纯数据及线程安全取消标记，工作线程不读取任何 Qt 控件。
        path, file_id, api, cancelled = self.temp_path, self.file_id, self.api, self._cancelled

        def download():
            """执行预览请求；失败或已关闭时清理本次临时文件。"""
            try:
                api.preview_file(file_id, path)
            except Exception:
                if os.path.exists(path):
                    os.unlink(path)
                raise
            finally:
                if cancelled.is_set() and os.path.exists(path):
                    os.unlink(path)
            return path

        run_task(self, download, self._loaded, self._load_failed, controls=(self.open_btn,))

    def _loaded(self, _path):
        """只在仍打开的窗口中渲染已下载的文件。"""
        if not self._cancelled.is_set():
            self._render()

    def _load_failed(self, error):
        """失败时保留明确提示，不在构造过程中弹出尚未显示的对话框。"""
        self.info.setText(f"加载预览失败：{error}")
        self.open_btn.setEnabled(False)

    def _render(self):
        ext = self._ext()
        if ext in _IMAGE_EXT:
            self._render_image()
        elif ext == ".pdf" or "pdf" in self.mime_type:
            self._render_pdf()
        elif ext in _TEXT_EXT:
            self._render_text()
        else:
            self._render_unsupported()

    def _render_image(self):
        self.info.hide()
        area = QScrollArea()
        area.setWidgetResizable(True)
        label = QLabel()
        pix = QPixmap(self.temp_path)
        if pix.isNull():
            self.info.setText("图片无法解析")
            self.info.show()
            return
        # 自适应缩放
        max_w, max_h = 780, 560
        if pix.width() > max_w or pix.height() > max_h:
            pix = pix.scaled(max_w, max_h, Qt.KeepAspectRatio, Qt.SmoothTransformation)
        label.setPixmap(pix)
        label.setAlignment(Qt.AlignCenter)
        area.setWidget(label)
        self._swap(area)

    def _render_pdf(self):
        if not _HAS_PDF:
            self._render_unsupported("当前环境不支持内嵌 PDF 预览")
            return
        self.info.hide()
        pdf = QPdfDocument(self)
        self._pdf_document = pdf  # 关闭窗口时先释放 PDF 文件句柄。
        err = pdf.load(self.temp_path)
        if err != QPdfDocument.Error.None_:
            self._render_unsupported("PDF 解析失败")
            return
        view = QPdfView(self)
        view.setDocument(pdf)
        view.setZoomMode(QPdfView.ZoomMode.FitInView)
        self._swap(view)

    def _render_text(self):
        self.info.hide()
        try:
            with open(self.temp_path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
        except Exception:
            content = "(文本读取失败)"
        edit = QTextEdit()
        edit.setReadOnly(True)
        edit.setPlainText(content)
        self._swap(edit)

    def _render_unsupported(self, msg: str = None):
        self.info.setText(msg or "该文件类型暂不支持内嵌预览，请点击「用系统程序打开」。")
        self.info.show()

    def _swap(self, widget):
        """用渲染组件替换占位 info。"""
        layout = self.layout()
        # 在 info 之前插入，并隐藏 info
        layout.replaceWidget(self.info, widget)
        self.info.hide()

    def _open_external(self):
        if not self.temp_path or not os.path.exists(self.temp_path):
            QMessageBox.warning(self, "提示", "预览文件未就绪")
            return
        QDesktopServices.openUrl(QUrl.fromLocalFile(os.path.abspath(self.temp_path)))

    def closeEvent(self, event):
        """关闭窗口时清理受控预览副本，不删除用户显式下载的文件。"""
        self._cleanup()
        super().closeEvent(event)

    def done(self, result):
        """覆盖 Esc/accept/reject 路径，确保模态窗口同样清理临时副本。"""
        self._cleanup()
        super().done(result)

    def _cleanup(self):
        """取消后续渲染，释放 PDF 文件并删除临时副本。"""
        self._cancelled.set()
        if self._pdf_document is not None:
            self._pdf_document.close()
        if self.temp_path and os.path.exists(self.temp_path):
            try:
                os.remove(self.temp_path)
            except Exception:  # noqa
                pass
