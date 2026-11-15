# 新平台第一批开发与验收

基线：需求 V0.5、总体/公盘/档案架构 V1.3、接口和数据库设计 V1.1。用户已确认没有需要延续的正式业务数据或未完结借阅，旧系统保留，新平台使用独立新库。首批只建立身份、组织、权限和审计基础，不迁移旧业务。

## 本批交付

| 功能 | 已实现行为 |
| --- | --- |
| 身份与会话 | 工号登录、退出、本人资料、首次设密、本人改密、一次性再验证；单有效会话；空闲 30 分钟失效，后台只读轮询不续期；个人密码按既定规则只要求非空 |
| 工作台 | 本人资料、实际角色及获准应用入口；未实现待办与公告返回空状态，不生成示例业务 |
| 组织与人员 | 部门新增、基础资料和层级修改；员工新增、基础资料修改、启停；服务端分页和筛选；账号停用终止任职，重新启用不恢复原权限 |
| 角色与权限 | 平台、公盘、档案分域管理；任职配置、允许/拒绝配置；禁止自我增权、跨域授权和授出本人没有的能力；公盘单一有效角色。2026-11-04 用户明确取消最后管理员数量保护 |
| 档案初始化 | IT 仅可初始化首位档案超级管理员；初始化后例外关闭；不会因此获得档案管理或内容权限 |
| 审计与恢复保护 | 成功事实和业务同事务提交；拒绝事件独立留痕；日志只追加；角色变更归属实际模块；库外收紧账本在数据库版本落后或自身损坏时拒绝访问 |
| 网页 | 登录、工作台、组织人员、角色权限、操作记录；电脑浏览器布局；管理操作使用明确反馈和本人再验证 |

对应接口：API-IAM-001～005、008～012、014～015、017～020、022～027；API-PORTAL-001；API-OPS-002。本批实现这些接口的身份管理子集，其他模块不返回伪成功。

尚未实现：邮箱找回/重置、正式公司邮件投递、员工调动及档案员交接、部门职责链、个人临时管理授权、服务身份管理、工作日历、公盘文件业务、档案审批借阅、Office 转换、正式备份恢复和主备切换。

## 代码与隔离边界

- 新 Java 启动类：`backend/src/main/java/com/company/platform/PlatformApplication.java`。只扫描新平台包。
- 新前端入口：`web/platform.html`、`web/src/platform/`；构建到 `web/dist-platform/`。
- 新配置：`backend/src/main/resources/platform.yml`；只使用 `IMS_PLATFORM_*` 环境变量。
- 新迁移：`backend/src/main/resources/db/platform/`；当前 12 张设计业务表、技术计数表和实例配对表。没有一次性创建全部 80 张设计表。
- 新构建：Maven `platform` profile，输出 `backend/target-platform/`。默认旧入口及旧 `target/`、`web/dist/` 保持原用途。
- 启动前核验本机 PostgreSQL 地址、`ims_platform_dev/test/trial*` 新库名、显式隔离声明，以及旧 public 业务表是否存在。拒绝在有旧业务表的数据库中执行初始化。
- 新建独立 PostgreSQL 集群；当前服务不监听外网。数据库、证书、私有配置和安全账本都放在仓库外的新目录。旧系统数据库、附件、备份及进程不作为开发测试对象。

新增代码的 API、Service、核心方法、字段、配置和迁移均使用中文注释。数据库列含 SQL `COMMENT`；密钥、口令和个人秘密不写入注释或验收报告。

用户最新范围：暂不考虑手机端。本批验收以电脑浏览器为准，不继续手机端专项适配；已有基础响应式样式不代表移动端功能承诺。

## 性能、并发和扩展边界

1. 列表默认 20 条、上限 100 条，在数据库完成过滤、计数和分页；排序列白名单；会话、任职、审计时间线等建立索引。关键字包含匹配仍需按实际人员规模评估，不宣称已完成大数据量压测。
2. Hikari 连接池最大 12、最小空闲 2；取连接等待 5 秒；SQL 超时 10 秒、锁等待 5 秒。会话续期最多每 30 秒落库一次。
3. 使用数据库事务、行锁、约束、版本检查和稳定操作号处理并发。固定数据库咨询锁只串行化低频身份管理写入，不用于列表或后续文件传输。
4. 页面按功能延迟加载。列表搜索在提交时执行，过期查询响应不能覆盖新结果。口令和会话 Cookie 不进入 localStorage/sessionStorage。
5. 认证计数、会话和幂等回执保存在数据库；关键权限从当前数据库状态核验。授权变更使关联会话失效，不能依赖浏览器隐藏按钮保证权限。
6. 身份、组织、管理命令、审计、凭据投递和独立安全账本分开实现，后续业务复用接口边界。当前本机文件账本和投递箱是开发适配器，**没有完成生产冗余或高可用验收**。正式部署需接入独立持久回执、账本副本、投递重试、恢复协调和旧主隔离机制。

## 本批契约解释与扩展

- API-IAM-027 的 `X-Expected-Version` 采用 API-IAM-026 返回的 `authVersion`。最小人员选择器无需额外读取完整账号资料。
- 角色 PUT 替换操作者实际获准管理模块的完整角色集合。调用方应先读取当前任职，并携带这些模块中需要保留的角色；其他模块不会被清空。首位档案管理员例外仅在显式提交该角色时涉及档案域。
- API-IAM-023/024 的 RoleDetail 增加 `assignments: [{ permissionId, scopePolicy: { code }, effect }]`。原设计的 `permissions`、`scopePolicies` 无法关联允许/拒绝，新增字段避免前端把 DENY 当成 ALLOW；原字段保留。
- `/auth/me` 的 actions 可含派生能力 `ARCHIVE.INITIAL_ADMIN_ASSIGN`，用于展示一次性初始化入口；它不等于档案角色管理权限，最终判断仍由事务内检查执行。
- 当前角色管理权限仅实现固定 COMPANY 范围，不把未完成的部门树或委托范围解释成全公司权限。档案员更替在专门交接服务就绪前明确拒绝直接操作。
- `ops.auth_throttle` 和 `platform_meta.instance` 为技术辅助表；安全账本包含完整收紧事实、链摘要、持久链头和实例配对，不只保存不可恢复的摘要。
- 原始 Word 设计文档未改写。以上实现说明应在下一次正式设计版本同步。

## 构建与启动

环境：PowerShell 7、JDK 21、Maven、Node/npm、现有 PostgreSQL 16。以下命令在仓库根目录执行。先停止正在运行的**新平台实例**再重新打包；Windows 正在运行的 JAR 会被占用。

```powershell
# 完整构建：前端回归、类型检查、平台网页构建、后端测试及打包。
$env:JAVA_HOME = 'D:\DevTools\jdk-21\jdk-21.0.11+10'
.\scripts\platform\build.ps1 -MavenCommand 'D:\DevTools\maven\apache-maven-3.9.16\bin\mvn.cmd'

# 仅首次创建：Root 必须是尚不存在的英文绝对目录，两个端口必须空闲。
$env:JAVA_HOME = 'D:\DevTools\jdk-21\jdk-21.0.11+10'
.\scripts\platform\create-local-environment.ps1 `
  -Root 'D:\tmp\ims-platform-new-dev' `
  -PostgresBin 'D:\PostgreSQL\pgsql16\bin'

# 启动已创建的新平台；前台进程可用 Ctrl+C 结束。
.\scripts\platform\start.ps1 -ConfigPath 'D:\tmp\ims-platform-new-dev\platform.private.json'
```

访问 `https://127.0.0.1:18443/`。本机脚本生成开发用自签证书；正式部署需要可信证书。初始账号为 `DEV-ADMIN`，随机初始密码仅保存在该环境的受限配置文件中，没有通用默认密码。创建员工的临时密码有效 24 小时，只投递到本机受限 `development-mailbox`，当前不会向真实公司邮箱发送邮件。投递文件包含开发凭据，测试完成后按本机开发数据生命周期处理；正式投递与清理机制属于后续接入范围。

机器重启后，先用 `pg_ctl.exe -D '<该环境根目录>\postgres' -l '<该环境根目录>\postgres.log' -w start` 启动这个独立集群，再运行 start.ps1。停止时先关闭新平台，再对同一目录执行 `pg_ctl.exe -D '<该环境根目录>\postgres' -m fast -w stop`。不要使用旧系统的数据目录。

## 私有配置字段

JSON 不支持注释，所有键在此解释；文件只允许新平台前缀，禁止提交 Git。

| 字段 | 用途及单位 |
| --- | --- |
| IMS_PLATFORM_DB_URL | 独立本机 PostgreSQL JDBC 地址；没有旧库默认值 |
| IMS_PLATFORM_DB_USER / IMS_PLATFORM_DB_PASSWORD | 新库专用非超级用户及密码 |
| IMS_PLATFORM_ISOLATED | 必须显式为字符串 true，否则拒绝启动 |
| IMS_PLATFORM_PORT | 本机 HTTPS 端口；缺省 18443 |
| IMS_PLATFORM_TLS_KEYSTORE / IMS_PLATFORM_TLS_PASSWORD | 本机 PKCS12 证书绝对路径及保护口令 |
| IMS_PLATFORM_LEDGER_ROOT | 不随业务库恢复的安全账本绝对目录；禁止不同实例共用 |
| IMS_PLATFORM_MAILBOX_ROOT | 本机开发凭据投递目录，不通过 HTTP 提供访问 |
| IMS_PLATFORM_ADMIN_NO / IMS_PLATFORM_ADMIN_NAME | 仅空库初始化使用的工号和姓名 |
| IMS_PLATFORM_ADMIN_EMAIL | 初始化人员的 @biolink.com 邮箱 |
| IMS_PLATFORM_ADMIN_PASSWORD | 随机初始化口令；不重设已有账号。正式初始化后应从部署配置移除；验收脚本仅在隔离环境中使用它 |

## 验收复跑

API 和浏览器测试均写入带随机前缀的合成员工/部门；不清空数据库。它们会登录同一个单会话管理员，因此**顺序执行**。

```powershell
python .\scripts\platform\verify_api.py `
  --config 'D:\tmp\ims-platform-new-dev\platform.private.json' `
  --postgres-bin 'D:\PostgreSQL\pgsql16\bin' `
  --report 'D:\tmp\platform-api-result.json' --allow-isolated-writes

$env:IMS_PLATFORM_TEST_CONFIG = 'D:\tmp\ims-platform-new-dev\platform.private.json'
$env:IMS_PLATFORM_ALLOW_TEST_WRITES = 'yes'
Set-Location web
.\node_modules\.bin\playwright.cmd test --config playwright.platform.config.ts
```

API 测试包含真实 PostgreSQL 故障注入，但只定位本次生成的测试人员：空闲时间、授权代次回退、审计禁止改写；测试库名和回环地址必须通过护栏。测试专用客户端接受本机自签证书，不改变应用的 Secure Cookie 设置。

验收结果及页面检查见 `platform-batch1-validation.md`。浏览器验收产物在被 Git 忽略的 `web/platform-test-results/`；不录制含认证请求的 trace。
