# -*- coding: utf-8 -*-
"""四线程任务池中的网络工作；结果通过排队信号回到 Qt 界面线程。"""
import weakref
from PySide6.QtCore import QObject, QRunnable, QThreadPool, Qt, Signal, Slot
from PySide6.QtWidgets import QApplication
from app.core import session

_active_tasks = set()  # 保持任务接收器存活，直到任务完成。
_pool = None  # 懒创建四线程池，防止无界并发耗尽连接。


class _Worker(QRunnable):
    """执行纯数据/网络工作，不访问 Qt 控件。"""

    def __init__(self, bridge, operation, token):
        """记录结果接收器、无参数工作函数和发起时的身份。"""
        super().__init__()
        self.bridge = bridge  # 位于界面线程的结果接收器。
        self.operation = operation  # 已捕获输入的纯工作函数。
        self.token = token  # 排队期间也不能换用新用户身份。

    def run(self):
        """将数据或异常通过同一个信号送回，保持完成顺序。"""
        try:
            with session.request_scope(self.token):
                result = self.operation()  # 工作结果不应包含 Qt 控件。
            self.bridge.completed.emit(result, None)
        except Exception as error:
            self.bridge.completed.emit(None, error)


class _Bridge(QObject):
    """在主线程恢复按钮、渲染结果；窗口销毁后丢弃回调。"""

    completed = Signal(object, object)  # 工作结果、异常；异常为空代表成功。

    def __init__(self, owner, success, failure, controls, token_sensitive):
        """保存回调和按钮原始状态；以应用为父对象确保任务存活。"""
        super().__init__(QApplication.instance())
        self.owner = weakref.ref(owner)  # 不阻止已关闭窗口回收。
        self.alive = True  # 由 Qt destroyed 信号及时清除。
        self.success = success  # 成功时的主线程回调。
        self.failure = failure  # 异常时的主线程回调。
        self.controls = [(control, control.isEnabled()) for control in controls]  # 恢复之前的权限禁用状态。
        self.token = session.get_token()  # 发起任务时的当前登录身份。
        self.token_sensitive = token_sensitive  # 登录任务不依赖旧身份。
        owner.destroyed.connect(self._owner_destroyed)
        self.completed.connect(self._deliver, Qt.ConnectionType.QueuedConnection)
        for control, _enabled in self.controls:
            control.setEnabled(False)

    @Slot()
    def _owner_destroyed(self):
        """窗口释放后禁止访问其控件。"""
        self.alive = False

    @Slot(object, object)
    def _deliver(self, result, error):
        """恢复按钮并交付同一账号的结果；清理对接收器的强引用。"""
        try:
            if not self.alive or self.owner() is None:
                return
            for control, enabled in self.controls:
                control.setEnabled(enabled)
            if self.token_sensitive and session.get_token() != self.token:
                return
            if error is None:
                if self.success:
                    self.success(result)
            elif self.failure:
                self.failure(error)
        finally:
            _active_tasks.discard(self)
            self.deleteLater()


def run_task(owner, operation, success=None, failure=None, controls=(), token_sensitive=True):
    """提交后台任务；operation 只能使用提前捕获的数据，不得读取控件。"""
    global _pool
    if _pool is None:
        _pool = QThreadPool(QApplication.instance())
        _pool.setMaxThreadCount(4)
    bridge = _Bridge(owner, success, failure, controls, token_sensitive)  # 在界面线程创建接收器。
    _active_tasks.add(bridge)
    _pool.start(_Worker(bridge, operation, bridge.token))
    return bridge


def shutdown_tasks():
    """事件循环退出后等待已提交请求结束，避免 Python 退出时销毁仍运行的 Qt 工作器。"""
    if _pool is not None:
        _pool.waitForDone()
    _active_tasks.clear()
