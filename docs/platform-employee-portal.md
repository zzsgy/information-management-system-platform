# 员工首页与公告管理开发、验收记录

日期：2026-10-30。依据当前 V0.5 需求中的 HOME-001～009 和 ADM-108，沿用独立新库、Vue/TypeScript + Java/Spring 的现有开发边界，页面仅面向电脑端。

## 已交付功能

- 员工首页显示当前授权应用、待审批/待办理、公告、常用网站及本人未读消息。应用卡片区分工作与管理入口；管理入口集中在“管理中心”。
- 公告支持新建、编辑、草稿、发布、撤下、置顶、展示起止时间及全公司/精确部门范围。列表显示标题、分类、实际发布人姓名、发布部门及上海时区完整发布时间。修改保留首次发布信息，每次修改保存独立修订快照和审计。
- 公告详情采用百林科信纸的原始标识与底纹。正文为可维护的纯文本，保留换行；长公告自然延伸，页脚固定在正文和落款之后。
- 常用网站支持名称、完整 HTTP(S) 地址、显示顺序及启停。初始配置仅为“百林客 → https://www.biolink.city/”。
- 员工可以提交使用建议、问题反馈和其他意见，查看本人办理进度；IT 可回复并置为处理中或办结。每条回复形成一条本人站内消息，已读不改变办理结果。
- 统一待办连接当前审批节点、电子借阅临近到期、本人原件归还、获权原件交付/实收核验、IT 凭据补录及反馈办理。点击后进入对应模块；节点、借阅、凭据或反馈详情继续由原接口鉴权。
- 统一收件箱连接原有业务消息及反馈答复，支持全部/未读、分页、已读和来源跳转。搜索栏进入原公盘文件或档案目录查询，沿用原有权限过滤。

本批反馈为文字表单，正文最多 1000 字，IT 回复最多 2000 字；公告正文最多 10000 字。公告详情是浏览器内容页面，信纸中的电话、网址和邮箱作为模板品牌元素展示。

## 模板来源与保真

原件：`D:/work/01_工作/IT工作/信息化系统建设/百林科信纸-空.docx`。

保留副本：`docs/templates/百林科信纸-空.docx`。网页资源 `web/src/platform/assets/biolink-letterhead.png` 直接来自 DOCX 的 `word/media/image1.png`，没有重新绘制商标或改变原图像素。

DOCX SHA-256：`522F85047146727E04700A41A230A98041CBFC5EFBEC3476C28655BF956A5EA9`。

## 权限、数据与性能

新增独立权限：`PLATFORM.ANNOUNCEMENTS_MANAGE`、`PLATFORM.SITES_MANAGE`、`PLATFORM.FEEDBACK_MANAGE`。平台 IT 获得三项权限；“内容维护人员”只获得公告维护权，可通过现有角色管理分配。

公告员工列表和详情在数据库中实时限制状态、当前部门、开始及截止时间，再进行计数与分页；员工访问草稿、撤下、过期或其他部门公告统一返回 404。内容编辑权不授予人员、公盘或档案管理权。

管理写入复用 `Commands` 的事务、审计、一次性身份再验证、稳定操作号和预期版本。员工反馈提交使用本人写锁和唯一操作号：同一内容重试返回原记录，替换内容返回冲突。答复与办理状态原子提交，重试不生成重复消息。

待办和消息使用当前业务来源聚合，审批任职有效性复用 `workflow.assignment_valid`，业务通知继续复用 `ops.notification_current`。所有列表在数据库有界分页，公告列表不传正文；新表为公告部门范围、发布时间、反馈申请人、未办结队列和答复关联建立索引。没有引入新的消息中间件、全文索引或独立审批状态副本。

迁移：V36 建立 `portal` 内容表、修订快照、角色与权限；V37 补齐中文字段说明。已部署新系统在迁移时补充权限，首次空库初始化由现有 `PlatformSetup` 创建角色并授权。

## 新增接口

| 接口 | 用途与边界 |
| --- | --- |
| `GET /portal/home` | 当前应用、可见公告、启用网站、实时待办和未读摘要 |
| `GET /portal/announcements[/{id}]` | 员工公告列表/详情，动态展示范围 |
| `GET /admin/portal/announcements[/{id}]` | 获权内容维护人员读取全部状态 |
| `POST /admin/portal/announcements` | 创建公告，再验证类型 `PORTAL_ANNOUNCEMENT`、动作 `SAVE_ANNOUNCEMENT` |
| `PUT /admin/portal/announcements/{id}` | 带预期版本编辑或撤下公告 |
| `GET /admin/portal/departments` | 公告范围的最小部门选择数据 |
| `GET /portal/sites` | 启用的网站列表 |
| `GET/POST /admin/portal/sites`、`PUT /admin/portal/sites/{id}` | IT 网站配置，再验证类型 `PORTAL_SITE`、动作 `SAVE_SITE` |
| `GET/POST /portal/feedback`、`GET /portal/feedback/{id}` | 本人反馈及带操作号提交 |
| `GET /admin/portal/feedback[/{id}]` | IT 办理队列及详情 |
| `POST /admin/portal/feedback/{id}/replies` | 带版本回复，再验证类型 `PORTAL_FEEDBACK`、动作 `REPLY_FEEDBACK` |
| `GET /portal/dashboard-tasks` | 当前审批、办理、到期事项，支持分类分页 |
| `GET /portal/inbox`、`POST /portal/inbox/{id}/read` | 本人有效消息及已读/受控来源跳转 |

所有路径前缀为 `/api/v1`。匿名、首次设密限制、CSRF、同源及请求大小继续由原 `HttpBoundary` 执行。

## 本机隔离验收

新 PostgreSQL：`127.0.0.1:55462`，独立根目录 `D:/tmp/ims-platform-portal-20261030`。HTTPS 预览：`https://127.0.0.1:18462/platform.html`。

可运行构建：`D:/tmp/ims-platform-portal-final-build/archive-service-0.1.0.jar`。预览员工及管理员登录信息仅保存在该隔离根目录的 `portal-preview-access.private.json`，不写入仓库、截图或报告。

验证结果：

- 前端现有 16 项测试通过，`vue-tsc` 检查及平台构建通过。
- `PortalIntegrationTest` 21 项通过，连接真实新 PostgreSQL、身份、再验证与事务服务。
- Maven `verify`：发现 357 项，实际执行 146 项全部通过，其余 211 项因对应专项环境开关未启用而跳过。此次没有重跑其他批次的 Office、文件副本等专项部署验收。
- Edge 浏览器 8 组通过：真实登录与首次设密、管理越权/CSRF、公告发布/模板/完整元数据、反馈办理及消息闭环、网站停用、公告撤下即时清屏、搜索/待办/管理菜单、长公告全文和页脚排版。1100、1280、1440 像素桌面检查无页面横向溢出，未捕获脚本异常为 0。
- 维护工具 6 项边界测试通过。新增 `portal` 模式已纳入持锁备份、逐表摘要及恢复校验；在另一个新实例 `127.0.0.1:55463` 恢复到 `ims_platform_test_restore_portal_20261030`，89 张表及持久文件摘要全部一致，其中包含公告、范围、修订、网站、反馈及回复共 6 张新表。该首页专项库原件对象数为 0，验证的是本批实际元数据和身份/消息记录恢复，不代表生产附件容量或高可用性能验收。

专项还覆盖：跨部门和调岗后公告不可见，草稿/未开始/到期/撤下均不可读取；发布及反馈重试不重复；旧版本拒绝且回滚；撤销会话后旧再验证不能写入；员工不能查看他人反馈或读取他人答复；已读保持首次时间；审批原任职失效后待办、通知和已读入口均拒绝继续使用。

证据目录：`D:/work/01_工作/数据建设/tmp/platform_portal_20261030/`。主要文件：`build-verify.log`、`web-build-final.log`、`browser-final/report.json`、`backup-final-report.json`、`restore-report.json`、页面截图及 `source-change-report.json`。早期浏览器和备份诊断记录单独保留；最终结果以 `browser-final` 和最终备份/恢复报告为准。

## 复跑入口

先构建网页，再执行 Maven。仅当 `IMS_PORTAL_INTEGRATION=yes` 且数据库 URL 符合本批 55462 端口和隔离库命名时运行专项测试。浏览器脚本为 `web/tests/platform/portal-live.cjs`，使用 `IMS_PLATFORM_TEST_CONFIG` 指向隔离私有配置，`IMS_PORTAL_TEST_OUTPUT` 指向新的证据目录。脚本创建合成账号、发布/撤下合成公告，并在验收结束保留一份明确标注的本机演示公告。
