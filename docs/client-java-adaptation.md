# PySide6 客户端接入 Java 后端

本轮保留原桌面技术栈、左侧导航及业务页面结构。接口仍使用 `/api/...` 路径和 `{code,message,data}` 信封；Bearer 令牌作为不透明字符串处理，不依赖 JWT 格式。原 Python 服务端仅作为迁移参考。

## 本轮调整

- 登录、改密、文件上传下载、预览下载、文件列表、共享主体/授权查询及数据安全运维使用四线程 Qt 后台任务；结果经排队信号回到界面线程。请求固定使用发起时的账号，切换账号后不再显示旧任务结果。
- `archive.admin` 不再代表全系统权限；菜单及按钮按实际权限编码控制。服务端的数据范围和业务状态校验仍是最终依据，界面隐藏按钮不是安全边界。
- 401 清空对应旧会话并重新登录；旧请求不能清掉后来登录的新身份。退出调用服务端撤销接口，改密成功后重新登录，不再使用旧令牌调用 `me`。密码输入掩码显示，新建用户不再预填 `123456`。
- 修复非空首页统计的元组/字典读取错误、文件预览不存在的方法、分类删除不存在的方法、待办按钮缺失 Qt 导入、审批模板缺失表头类导入，以及节点条件框字段编码与中文标签倒置。首页及统计导出的到期窗口统一为 **30 天**。
- 档案详情增加最小的“编辑信息”入口并回填已有字段；未设置的日期仍保存为空，不能被编辑其他字段时改成当天。表单不允许直接修改生命周期状态。
- 销毁执行只查询 `GET /api/destroys/{id}` 中的获批明细，不再重新搜索全库，不默认全选。用户核对并手动选择全部获批档案，填写监销人及方法后整单执行；最终集合由服务端再次核验。
- 借阅申请可区分电子、实物和纸电结合。实物借阅按“批准 → 办理借出 → 归还”执行，电子借阅可结束已批准记录；办理需要 `borrow.approve`。
- 损坏处理按“已登记 → 处理中/已解决 → 已关闭”展示可选状态，保留真实处理方式和结果。
- 档案详情的“授权共享”按人员/部门及单个操作授权，可设置到期时间或撤销。共享主体接口仅返回最小标识信息；接收人仍需具有对应功能权限，读取授权不会附带文件下载或销毁权限。
- 下载先写同目录临时文件，完成后原子替换；中断保留已有目标文件并清理半成品。客户端拒绝携带令牌的跨主机 API 路径及重定向。

## 配置与会话

客户端启动地址按下列顺序读取：

1. 进程环境变量 `SERVER_URL`（便于统一部署固定服务地址）。
2. 当前操作系统用户的 `client.json`。
3. 旧版安装目录或 `client/` 下的 `.env`（兼容读取，不再修改）。
4. 默认 `http://127.0.0.1:8000`。

Windows 默认用户文件为 `%LOCALAPPDATA%/InformationManagementSystem/client.json`；Linux 为 `$XDG_CONFIG_HOME/InformationManagementSystem/client.json`，未设置 XDG 时使用 `~/.config/InformationManagementSystem/client.json`。环境变量 `IMS_CLIENT_CONFIG_DIR` 可指定独立可写目录。登录框保存地址不会改写程序安装目录，也不保存密码或令牌。

## 新增或明确的接口约定

| 接口 | 客户端用途 |
| --- | --- |
| `POST /api/auth/logout` | 撤销当前会话 |
| `GET /api/destroys/{id}` | 读取固定 `archive_ids` 与 `archives`，其中明细包含 `id/archive_no/archive_name` |
| `POST /api/borrows/{id}/checkout` | 实物借出；请求体为空对象 |
| `GET /api/archives/share-subjects?type=user或dept` | 读取受限共享主体 `id/name` |
| `GET/POST /api/archives/{id}/grants` | 查询或添加指定档案授权 |
| `DELETE /api/archives/{id}/grants/{grantId}` | 撤销单条授权 |

共享请求包含 `grantee_type`、`grantee_id`、`permission_code` 与可空 `expires_at`。到期时间在界面按本机时间选择，提交转换成带时区的 UTC ISO 时间。权限选项包含档案读取/编辑、文件预览/下载/上传、借阅申请/审批，以及销毁申请/审批/执行，且界面只列出授权者自身具备的操作。

`GET /api/departments` 在后端对档案用户提供受限的部门目录，不要求为了使用档案页面而授予用户管理权限。

## 备份和恢复边界

运维页面需要独立 `security.manage` 权限。备份是数据库与附件及校验清单的配套包。保留原 `POST /api/security/db-restore` 路由，但在线仅用于校验包及显示恢复说明；后端以 409 明确拒绝在线覆盖时，界面展示说明，不声称恢复成功。

实际恢复须按后端部署文档停服，在新的空数据库和新的附件目录恢复并验证，再切换配置。客户端不会自动重试备份、销毁、恢复等可能重复执行的请求。

## 验证方式

在仓库根目录执行标准库离线测试，不需要安装 Qt 或访问网络：

```powershell
python -X utf8 -m unittest discover -s client/tests -v
python -X utf8 -m compileall -q client/app client/tests
```

安装 `client/requirements.txt` 后可执行真实 PySide6 离屏冒烟：

```powershell
$env:QT_QPA_PLATFORM = "offscreen"
python -X utf8 client/tests/qt_smoke.py
```

当前标准库回归覆盖 25 项；真实 Qt 冒烟构造 14 个主模块和关键对话框，检查后台登录期间事件循环响应、原始字段回填、空日期保留、审批条件编码、精确销毁集合、共享授权和预览临时文件清理。离屏测试使用固定接口响应，不等同于 Java/PostgreSQL 联调或人工逐页验收。

真实 Java 联调脚本为 `client/tests/java_live_smoke.py`，必须先启动明确授权的本机临时 Java 服务及空测试数据库。脚本会创建测试档案、办理审批并逻辑销毁其中一份；不能对日常业务实例运行。地址与口令文件须显式传入，地址仅接受带端口的 `http://127.0.0.1`，不会自动采用客户端保存的地址：

```powershell
$env:IMS_SMOKE_BASE_URL = "http://127.0.0.1:18091"
$env:IMS_SMOKE_PASSWORD_FILE = ".dev/live-admin-password.txt"
python -X utf8 client/tests/java_live_smoke.py
```

口令文件由隔离测试环境准备，不提交仓库。脚本不会打印口令、令牌或响应正文，只报告步骤状态；不会改管理员密码或停止服务。本轮已在独立 PostgreSQL 测试库上完成 **11 个步骤**，输出 `JAVA_CLIENT_SMOKE_OK steps=11`：登录与权限、字典/部门/分类、草稿创建与编辑、直接改状态被拒、归档、附件上传/预览/下载及摘要一致、实物借阅通用审批/借出/归还、销毁错 ID 被拒/整单执行/重复执行被拒、销毁后附件不可读取、退出后旧令牌失效。测试结束保留一份范围对照档案及业务审计记录，后续随临时库统一处理。此结果覆盖实际 HTTP 契约，不代替人工逐页操作验收。

## 后续优化范围

本轮优先迁移关键耗时网络操作；部分普通列表查询、表单保存和批量导入仍沿用同步请求，尚未全量改为异步。未改造整体布局、分页体验、上传进度和取消机制；这些属于后续前端系统优化。权限变化由服务端实时执行，菜单清单在重新登录后更新。
