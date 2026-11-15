"""
================================================================
文件说明：客户端冒烟测试窗口（Hello Window）
----------------------------------------------------------------
用途：
  - 验证 PySide6 桌面环境能否正常启动并弹出窗口；
  - 环境搭建阶段的验证脚本。正式客户端入口是 app/main.py
    （登录窗 + 工作台等，见开发文档04）。
运行：在 client 目录下执行   .venv/Scripts/python hello_window.py
================================================================
"""
import sys  # sys.argv 提供命令行参数，QApplication 初始化时要用

# 导入 PySide6 的界面控件类
from PySide6.QtWidgets import QApplication, QLabel, QVBoxLayout, QWidget

# 1. 创建应用对象（每个 Qt 程序有且只有一个 QApplication，负责全局事件循环）
app = QApplication(sys.argv)

# 2. 创建主窗口
win = QWidget()
win.setWindowTitle("ArchiveSystem - Hello Window")  # 设置窗口标题

# 3. 搭建布局：在窗口里垂直排列控件
layout = QVBoxLayout()  # 创建垂直布局容器
layout.addWidget(QLabel("Hello from ArchiveSystem client (PySide6)"))  # 加一行文字
win.setLayout(layout)  # 把布局挂到窗口上

# 4. 设置窗口初始大小并显示
win.resize(420, 200)
win.show()

# 5. 进入消息循环：窗口保持显示，直到用户关闭窗口才退出程序
sys.exit(app.exec())
