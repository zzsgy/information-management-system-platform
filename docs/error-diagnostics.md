# 错误提示与后台诊断

适用范围：当前 Vue 平台网页 `web/src/platform` 和 Java 平台后端 `com.company.platform`。历史 Python 客户端及旧版 `/api` 应用不属于当前运行入口。

## 页面与日志的对应关系

接口失败显示中文原因、错误码和请求编号。后台按同一请求编号记录错误码、固定操作或模板路由、异常类型、SQLState 和异常链调用栈。后台任务还记录任务编号；页面轮询失败时同时显示任务编号与本次查询的请求编号。可先按页面请求编号查接口请求，再按任务编号查原处理日志。

日志不保存上传正文、口令、Cookie、密钥或未经清洗的外部响应。异常原始消息可能包含 SQL 参数或凭据，因此诊断日志保留类型、SQL 状态与调用栈，不直接打印这些消息。审批对象的“不可见”和“不存在”仍使用同一错误，避免向无权限人员透露记录是否存在。

预期的输入错误与权限拒绝保留原有业务提示；未知程序异常使用 `INTERNAL_ERROR`，不再误报成存储保护故障。未知异常的具体业务原因不能凭空推断，应根据请求编号查后台异常链。

## 主要故障分类

技术错误的完整代码和中文提示以 `backend/src/main/java/com/company/platform/SystemFailure.java` 为准。

| 故障 | 主要错误码 | 处理方向 |
| --- | --- | --- |
| 文件目录未配置、路径冲突、数据库实例不匹配 | `STORAGE_NOT_CONFIGURED`、`STORAGE_PATH_INVALID`、`STORAGE_INSTANCE_MISMATCH` | 核对部署配置及迁移的存储实例标识，不要重建或覆盖旧目录 |
| 磁盘不可访问、写入失败、文件副本校验失败 | `STORAGE_UNAVAILABLE`、`STORAGE_WRITE_FAILED`、`STORAGE_INTEGRITY_FAILED` | 检查磁盘、空间、服务账号权限与副本，不要把校验失败直接改为通过 |
| 保护服务未启用、状态文件不可读、签名或格式无效 | `PROTECTION_PROVIDER_UNAVAILABLE`、`PROTECTION_EVIDENCE_UNAVAILABLE`、`PROTECTION_EVIDENCE_INVALID` | 检查保护代理及其状态文件、密钥配置 |
| 保护状态过期、绑定错误、接管代次无效 | `PROTECTION_EVIDENCE_EXPIRED`、`PROTECTION_INSTANCE_MISMATCH`、`PROTECTION_EPOCH_INVALID` | 检查时钟、迁移绑定、主备接管状态 |
| 只读或隔离、模块未获准、副本核验或备份状态过期 | `PROTECTION_WRITE_BLOCKED`、`PROTECTION_MODULE_BLOCKED`、`REPLICA_VERIFICATION_EXPIRED`、`BACKUP_PROTECTION_EXPIRED` | 恢复实际保护服务，不关闭校验绕过 |
| 检测器未配置、程序不可用或摘要不匹配 | `SCANNER_NOT_CONFIGURED`、`SCANNER_EXECUTABLE_UNAVAILABLE`、`SCANNER_EXECUTABLE_MISMATCH` | 核对检测器安装路径、权限与已验收版本摘要 |
| 病毒库缺失、过期、临时目录故障 | `SCANNER_DATABASE_UNAVAILABLE`、`SCANNER_DATABASE_EXPIRED`、`SCANNER_TEMP_UNAVAILABLE` | 恢复官方病毒库及私有临时目录 |
| 检测超时、返回结果无效或程序失败 | `SCANNER_TIMEOUT`、`SCANNER_RESPONSE_INVALID`、`SCANNER_EXECUTION_FAILED` | 按任务编号检查检测程序；未完成检测的文件不得发布 |
| 可疑文件、实际格式不符 | `FILE_SUSPICIOUS`、`MEDIA_TYPE_NOT_ALLOWED` | 核对源文件；不允许通过重试把拒绝结论洗白 |
| 预览缺配置、资源忙、解析超时或输出异常 | `READING_NOT_CONFIGURED`、`READING_BUSY`、`RENDER_TIMEOUT`、`RENDER_OUTPUT_INVALID` | 检查解析程序和任务资源；文件已发布时不需要重新上传 |
| Office 配置、请求、响应异常 | `OFFICE_CONFIGURATION_INVALID`、`OFFICE_REQUEST_FAILED`、`OFFICE_RESPONSE_INVALID` | 核对 Office 服务；保存结果不确定时先查询保存记录 |
| 导出目录、大小或结果异常 | `EXPORT_STORAGE_INVALID`、`EXPORT_TOO_LARGE`、`EXPORT_RESULT_INVALID` | 检查派生目录，或减少导出范围 |
| 审批职责配置或凭据核验故障 | `WORKFLOW_CONFIGURATION_INVALID`、`WORKFLOW_EVIDENCE_INVALID` | 核查当前职责、流程配置及凭据记录 |
| 账号账本或通知投递故障 | `SECURITY_LEDGER_UNAVAILABLE`、`SECURITY_LEDGER_INVALID`、`CREDENTIAL_DELIVERY_FAILED`、`MESSAGE_*` | 检查账本、交付或投递服务；保留历史证据 |
| 数据库连接、超时、重复记录、引用冲突 | `DATABASE_UNAVAILABLE`、`DATABASE_TIMEOUT`、`DUPLICATE_RECORD`、`RECORD_IN_USE` | 按类型恢复服务、查询实际提交结果或检查关联数据 |
| 审计写入失败 | `AUDIT_WRITE_FAILED` | 核查审计数据库；后台同时保留原始故障与审计故障 |
| 网络故障、代理 HTML 响应或协议格式异常 | `NETWORK_UNAVAILABLE`、`HTTP_502` 等、`RESPONSE_FORMAT_INVALID` | 检查网络、代理或前后端版本；已经提交的操作先查询结果再重试 |

## 部署电脑的日志配置

默认日志写入服务工作目录下的 `logs/platform.log`，按天和20MB大小滚动，保留30天，总量上限1GB。服务账号需要日志目录写权限；不要放进网站静态资源目录。

部署时可在服务环境中设置：

```powershell
# 只在部署电脑配置。替换成部署电脑的实际日志路径。
$env:IMS_PLATFORM_LOG_FILE = 'E:/IMS/logs/platform.log'
```

如果通过 Windows 服务或启动配置文件管理环境变量，应写入该服务实际读取的配置，而非只在临时 PowerShell 窗口设置。更新应用并重启该服务后生效。

IT 管理员可在部署电脑只读查找：

```powershell
# 替换日志路径与页面上的请求编号；不会修改业务数据库或附件。
Select-String -Path 'E:/IMS/logs/platform.log' -SimpleMatch '替换为请求编号' -Context 0,35
# 后台任务还可用页面显示的任务编号定位原始执行异常。
Select-String -Path 'E:/IMS/logs/platform.log' -SimpleMatch '替换为任务编号' -Context 0,35
```

已存在的历史任务失败码继续保留；不会为了统一提示而重写历史记录。新版页面提供历史常见失败码的中文说明。更新错误提示不会自动修好部署电脑上的磁盘、保护代理、病毒库等实际故障，必须依据具体错误恢复对应服务。
