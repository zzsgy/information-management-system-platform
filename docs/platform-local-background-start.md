# 本机日常预览的独立后台启动

2026-10-31 将网页应用和 PostgreSQL 从开发终端启动改为当前 Windows 用户的任务计划程序托管。使用当前指针 `D:/tmp/ims-platform-current-preview.json`，继续沿用原日常预览库、附件、账号和网站配置，不运行集群初始化或测试数据导入。

## 本次中断检查

原入口通过 Codex 终端中的 `Start-Process` 拉起 Java，没有注册独立后台任务。检查时18467网页端口和55469数据库端口均不再监听，数据库日志在14:12:41记录工作进程被外部终止（异常码 `0x40010004`），随后数据库停止。此前未保留完整的历史进程树，不能据此单独证明 Codex 内部采用了哪种终止机制；旧启动方式没有明确的独立托管边界，符合用户反馈的重启后中断现象。

Windows 默认可以将创建的子进程纳入相同 Job；带相应结束标志时会统一终止组内进程。参考：[Microsoft Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)。新方案由 Windows Task Scheduler 创建组件进程，不再由 Codex 终端创建。

## 启动与日常更新

- 网页：`https://127.0.0.1:18467/platform.html`。
- 数据库任务：`BioLink-IMS-UserPreview-Database-55469`。
- 应用任务：`BioLink-IMS-UserPreview-Application-18467`。
- 两个任务以当前用户、普通权限和交互登录方式运行；不保存 Windows 密码。当前用户登录 Windows 后自动触发，没有默认三天运行上限，使用电池不会自动停止。
- 手动恢复：在 PowerShell 7 运行仓库的 `scripts/platform/start-user-preview-background.ps1`；重复执行会检查就绪状态，不重建库、不重复拉起已就绪组件。
- 原发布入口 `../tmp/platform_user_preview_20261030/start-user-preview.ps1` 已改为调用上述脚本。后续发布仍然切换 `preview-classpath.txt`，应用任务按该文件启动当前构建；数据库任务不随应用发布重启。
- `run-user-preview-component.ps1` 只由后台任务调用。数据库日志和应用日志留在原私有运行目录，两个组件的 PID 元数据注明 `WindowsTaskScheduler`。

此配置面向本机登录后的预览。电脑关机、注销或休眠会影响可用性；未改为在无人登录时运行的服务器服务。Office 专用 WSL、Office 容器和网络桥仍按原独立部署维护，不包含在这两个核心任务内。

2026-11-02补充：已开放同事局域网辅助测试入口`https://192.168.112.138:18467/platform.html`，保留原回环入口。新增独立Office LAN桥后台任务，未开放数据库监听；同事首次需导入公开测试证书。详细使用、连通验证及恢复方法见[局域网访问配置](platform-lan-access-20261102.md)。

## 验证记录

两个任务已实际创建并运行。启动命令返回后，另一个终端仍能访问网页并获得HTTP200；应用进程祖先链为 `java → pwsh → svchost → services`，独立于 Codex。重复运行原发布入口未更换当前组件 PID。数据库完成自身日志恢复，现有人员、部门、网站表可正常读取，另保存了恢复后数据库备份；没有重置库或覆盖资料。

用户原先显示连接被拒绝的 Chrome 标签页刷新后已恢复正常登录页。本次未实际重启 Codex 或 Windows，不将进程归属检查称为重启实测。任务导出、恢复前日志、原启动脚本、数据库备份和检查记录位于工作区 `tmp/service_lifetime_20261031/`。

## 数据库升级保护补充（2026-10-31）

日常私有配置必须设置`IMS_PLATFORM_PREVIEW_APPROVED_SCHEMA_VERSION`，应用后台任务将其固定为Flyway目标版本，防止其他页面发布顺带升级数据库。最初固定43；2026-11-02完成日志专项隔离验证、独立恢复及发布前数据库快照后，明确升级至44并固定此值。不能仅因为源码新增迁移就直接放开目标版本。发布和数据保留证据见[日志专项验收](platform-audit-storage-validation.md)。
