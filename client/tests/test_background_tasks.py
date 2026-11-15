# -*- coding: utf-8 -*-
"""使用排队信号桩验证后台工作隔离与生命周期；不是实际 Qt 事件循环验收。"""
import importlib.util
from pathlib import Path
import sys
import threading
import types
import unittest
from unittest.mock import Mock, patch

CLIENT = Path(__file__).resolve().parents[1]  # 客户端目录，使测试不依赖当前工作目录。
sys.path.insert(0, str(CLIENT))
from app.core import session

_queue = []  # 模拟 Qt 排队信号，只有主测试线程显式派发时才执行回调。


class BoundSignal:
    """信号实例，记录槽函数和连接类型。"""

    def __init__(self):
        """为每个 QObject 创建独立的槽函数集合。"""
        self.slots = []  # 每项包含目标槽和是否排队。

    def connect(self, callback, connection=None):
        """记录真实代码是否显式使用排队连接。"""
        self.slots.append((callback, connection))

    def emit(self, *args):
        """排队连接不在发送线程执行槽函数。"""
        for callback, connection in self.slots:
            if connection == "queued":
                _queue.append((callback, args))
            else:
                callback(*args)


class Signal:
    """模拟 Qt 类属性 Signal 描述符。"""

    def __init__(self, *types_):
        """接收信号类型签名；测试桩不执行类型转换。"""
        self.name = None  # 创建实例后保存对应私有信号键。

    def __set_name__(self, owner, name):
        """记录描述符所在属性名。"""
        self.name = "_signal_" + name

    def __get__(self, instance, owner):
        """每个接收器拥有独立信号订阅。"""
        if instance is None:
            return self
        return instance.__dict__.setdefault(self.name, BoundSignal())


class QObject:
    """最小 QObject 生命周期桩。"""

    def __init__(self, parent=None):
        """提供 destroyed 信号供真实桥接器订阅。"""
        self.destroyed = BoundSignal()

    def deleteLater(self):
        """测试中无需 Qt 内存管理。"""
        pass


class Control:
    """记录按钮启用状态以及操作发生的线程。"""

    def __init__(self, enabled=True):
        """保留授权导致的原始禁用状态。"""
        self.enabled = enabled  # 当前按钮状态。
        self.threads = []  # 检查控件没有被后台线程操作。

    def isEnabled(self):
        """返回当前按钮状态。"""
        return self.enabled

    def setEnabled(self, enabled):
        """记录改动及所在线程。"""
        self.enabled = enabled
        self.threads.append(threading.get_ident())


def load_tasks():
    """为真实 tasks 模块注入最小 Qt 桩，不污染正常客户端导入路径。"""
    core = types.ModuleType("PySide6.QtCore")  # 仅包含被测任务桥需要的 Qt 类型。
    core.QObject, core.QRunnable, core.Signal = QObject, object, Signal
    core.QThreadPool = Mock
    core.Qt = types.SimpleNamespace(ConnectionType=types.SimpleNamespace(QueuedConnection="queued"))
    core.Slot = lambda *args: lambda function: function
    widgets = types.ModuleType("PySide6.QtWidgets")  # 测试无真实 QApplication。
    widgets.QApplication = types.SimpleNamespace(instance=lambda: None)
    spec = importlib.util.spec_from_file_location("tasks_under_test", CLIENT / "app/core/tasks.py")
    module = importlib.util.module_from_spec(spec)  # 独立模块，避免与真实运行时共享注册表。
    with patch.dict(sys.modules, {"PySide6": types.ModuleType("PySide6"), "PySide6.QtCore": core,
                                  "PySide6.QtWidgets": widgets}):
        spec.loader.exec_module(module)
    return module


class BackgroundTaskTests(unittest.TestCase):
    """真实工作器及桥接器在模拟事件循环下的回归。"""

    def setUp(self):
        """建立独立模块和会话，防止旧回调跨测试泄漏。"""
        self.tasks = load_tasks()
        self.owner = QObject()
        session.set_session("old", {}, "http://localhost")
        _queue.clear()

    def tearDown(self):
        """清理测试会话与排队回调。"""
        session.clear()
        _queue.clear()

    def _run(self, operation, success, failure=None, controls=(), change_identity=False):
        """真实工作器在 Python 后台线程运行，主线程稍后派发排队结果。"""
        bridge = self.tasks._Bridge(self.owner, success, failure, controls, True)
        self.tasks._active_tasks.add(bridge)
        worker = self.tasks._Worker(bridge, operation, bridge.token)
        if change_identity:
            session.set_session("new", {}, "http://localhost")
        thread = threading.Thread(target=worker.run)  # 不使用桩线程池，真实验证线程隔离。
        thread.start()
        thread.join(timeout=2)
        self.assertFalse(thread.is_alive())
        return bridge

    def _dispatch(self):
        """由测试主线程派发 Qt 排队结果。"""
        while _queue:
            callback, args = _queue.pop(0)
            callback(*args)

    def test_callbacks_and_controls_run_only_on_main_thread(self):
        """耗时工作离开主线程，按钮恢复及成功回调仍只在主线程执行。"""
        main_id = threading.get_ident()
        control = Control()
        callback = Mock()
        self._run(lambda: (threading.get_ident(), session.get_token()), callback, controls=(control,))
        callback.assert_not_called()
        self.assertFalse(control.enabled)
        self._dispatch()
        self.assertTrue(control.enabled)
        worker_id, token = callback.call_args.args[0]
        self.assertNotEqual(worker_id, main_id)
        self.assertEqual(token, "old")
        self.assertEqual(set(control.threads), {main_id})
        self.assertFalse(self.tasks._active_tasks)

    def test_old_identity_result_is_discarded_after_switching_user(self):
        """排队工作仍使用旧身份，新登录界面不接收旧数据。"""
        seen = []
        callback = Mock()
        self._run(lambda: seen.append(session.get_token()), callback, change_identity=True)
        self._dispatch()
        self.assertEqual(seen, ["old"])
        callback.assert_not_called()

    def test_destroyed_owner_receives_no_callback(self):
        """窗口已释放时不再碰控件，仍清理任务引用。"""
        callback = Mock()
        self._run(lambda: "done", callback)
        self.owner.destroyed.emit()
        self._dispatch()
        callback.assert_not_called()
        self.assertFalse(self.tasks._active_tasks)

    def test_failure_restores_original_disabled_control(self):
        """失败不能把原本因权限不足被禁用的按钮重新启用。"""
        control = Control(enabled=False)
        failure = Mock()

        def operation():
            """模拟网络失败。"""
            raise OSError("offline")

        self._run(operation, None, failure, controls=(control,))
        self._dispatch()
        self.assertFalse(control.enabled)
        self.assertIsInstance(failure.call_args.args[0], OSError)


if __name__ == "__main__":
    unittest.main()
