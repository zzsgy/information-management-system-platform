# 第二批公共文件能力与公盘阅读接通记录

> 前半部分保留2026-10-27的阶段记录，其中“当前”“后续”均指当时状态。2026-10-29企业公盘生命周期与Office接入更新见本文末尾；此前50MiB PDF网页上传限制已被新实现替代。

本轮依据需求 V0.5、架构 V1.3、接口及数据库设计 V1.1，在独立新库接通公盘文件链路：**目录授权 → 分片上传 → 双副本核验 → 真实检测 → 正式发布 → 固定版本逐页阅读**。旧系统和第一批运行实例未切换。本记录描述代码和隔离验证结果，不作为生产安全或主备验收结论。

## 本轮完成

- 公盘真实授权按模块入口、分区和部门、现任负责人角色与独立任职关系、IT有效委托及个人动作共同计算。明确拒绝优先；IT不因基础管理身份取得默认上传权，公盘权限不会转换成档案正文权限。
- IT可建立部门顶层目录、建立和撤销负责人委托；现任负责人可在委托范围内创建子目录、新增和撤销个人动作授权。管理API复用一次性本人再验证、稳定操作号、版本检查、事务和审计。撤权写入库外安全账本，立即终止相关旧会话；账号先于授权的锁顺序避免与逐页交付形成等待环路。
- ClamAV原生进程适配器核验固定程序SHA-256、路径、超时、输出上限与实际扫描数量。正式模式始终要求有效官方主库及每日库，没有接受测试签名库的生产配置开关。检测器不可用保持对象待检测；可疑内容或不支持的实测类型隔离。技术失败可明确重试，并保留失败/成功尝试历史。检测通过仍不等于正式发布。
- 有界后台工作器自动处理合并、扫描和PDF页数任务，绑定发起人的有效登录会话和当前授权；支持过期租约重领。文件IO在事务外，提交前重查身份、业务授权、摘要、保护条件和租约。执行器没有公开HTTP执行入口。
- 签名保护门槛绑定数据库实例、节点、接管代次及最长60秒时效。写入要求旧主隔离、对应可写模块、30秒内副本验证和30分钟内完整保护点。最高接管代次在库外刷盘，服务重启后仍拒绝旧主证据。只读模式允许已核验版本读取；证据过期、签名不符或旧主未隔离均拒绝敏感页面。
- 正式发布重新核验当前上传权、原目录与干净对象，生成不可变业务版本和对象引用。同名默认冲突，不覆盖原件。列表在SQL计数和分页前排除文档明确拒绝，并联查版本和对象，避免逐条再查询。
- 阅读会话绑定用户、登录会话、授权代次、文档状态版本和具体文件版本，有效15分钟。每页在渲染前后验证双副本，交付前在短事务内再次核权并写库外刷盘回执；任何一步失败均不交付PNG。响应禁止缓存，不提供原PDF回退地址。公盘不默认个人水印；档案水印原型保留但尚未接入审批。
- 电脑端新增企业公盘、当前层目录、搜索分页、上传/检测/发布进度和阅读器。后台轮询不续期登录；授权或证据失效时清屏，换页/关闭释放内存图像，旧异步响应不能覆盖新目录或阅读窗口。未增加手机端布局。
- 配置、API、服务、字段、方法、关键并发分支和新增SQL带中文注释。V1—V4迁移没有改写；V5—V7追加公盘授权、处理尝试、阅读会话和对象引用。

## 主要接口

| 接口 | 本轮能力 |
| --- | --- |
| /api/v1/storage/upload-sessions 及分片、完成、取消子路由 | 原声明固定的受控上传 |
| GET /api/v1/jobs/{jobId} | 本人仍获权的上传或预览任务 |
| POST /api/v1/jobs/{jobId}/retry | 本人检测技术失败重试，不接受可疑或类型拒绝对象 |
| GET/POST /api/v1/drive/folders | 当前层可见目录、按职责创建顶层或子目录 |
| POST /api/v1/admin/drive/delegations 及 DELETE /{id} | IT建立或撤销部门负责人委托 |
| POST /api/v1/drive/resource-grants 及 DELETE /{id} | 负责人新增或撤销具体人员动作 |
| GET /api/v1/drive/resource-grants?resourceId=... | 负责人读取本层目录配置，上限100项；完整筛选/员工摘要待扩展 |
| GET/POST /api/v1/drive/documents、GET /{id} | 正式文档查询与发布 |
| POST /api/v1/drive/documents/{documentId}/previews | 仅PUBLISHED用途；候选检查明确拒绝 |
| GET /api/v1/drive/documents/{documentId}/previews/{previewId} 及 /pages/{pageNo} | 来源绑定清单与逐页PNG，文档和预览归属必须一致 |

当前只接通公盘公共文件链路。档案、流程凭据和导入领域适配器仍按默认拒绝，须在对应对象、流程和借阅批准范围实现后接入。目录移动、回收恢复、在线协作、原件下载、候选检查、授权修改/完整查询、后台任务管理及其管理页面属于后续业务批次，不以本轮接口替代。

## 配置与限制

全部变量使用 IMS_PLATFORM_ 前缀，详见 backend/src/main/resources/platform.yml 的中文注释。缺省空或关闭时，相关文件能力拒绝运行。

| 变量后缀 | 用途和限制 |
| --- | --- |
| STORAGE_PRIMARY / STORAGE_SECONDARY | 互不嵌套的两个私有绝对目录，不能与账本、邮箱嵌套；绑定新库实例，不能指向旧附件 |
| WORKER_ENABLED / WORKER_DELAY_MS | 缺省false；间隔缺省3000毫秒，每轮最多两件 |
| SCANNER_EXE / SCANNER_DATABASE / SCANNER_TEMP | 固定原生程序、官方签名目录及私有临时目录，均为绝对路径 |
| SCANNER_SHA256 / SCANNER_TIMEOUT | 程序摘要；超时缺省300秒、上限600秒 |
| SCANNER_SERVICE_ID | 限定模块、部门、动作和有效期的服务身份 |
| PROTECTION_ENABLED / PROTECTION_EVIDENCE / PROTECTION_KEY / NODE_ID | 独立保护代理证据、密钥与当前节点，缺省关闭 |
| READING_JAVA / READING_WORKER / READING_TEMP | 可信Java、独立PDF工作器JAR及私有临时目录，须同时分发工作器lib依赖 |
| READING_AUDIT | 库外页面交付回执目录，不能随业务库回退；写入失败停止交付 |

保护证据字段为 instanceId、nodeId、epoch、issuedAt、expiresAt、mode、oldPrimaryFenced、replicaVerifiedAt、backupId、backupProtectedAt、writableModules、signature。HMAC-SHA256覆盖去除signature后的规范JSON。密钥相邻的 .epoch 和 .epoch.lock 是库外高水位和进程互斥文件，不能因恢复业务库而删除或重置；密钥轮换须迁移高水位。业务HTTP接口不能签发保护证据。

后端继续保留公盘5 GiB、档案PDF 1 GiB、凭据50 MiB和8 MiB分片上限；导入XLSX 50 MiB仍是待业务确认的技术限制。**当前电脑端上传仅开放不超过50 MiB的PDF**，限制浏览器整文件摘要缓冲；大文件增量摘要和断点续传页面未交付，不能据后端上限宣称网页支持5 GiB。实测类型适配暂限PDF/PNG/JPEG/TIFF，其他声明格式不会直接放行；当前阅读仅支持PDF。

## 隔离验证结果（2026-10-27）

| 验证 | 结果 |
| --- | --- |
| 常规后端回归及打包 | 97项通过；需外部环境的17项另行显式执行 |
| 新增公盘/阅读PostgreSQL集成 | 9项通过，真实授权、原生检测进程和隔离PDF进程 |
| 原上传PostgreSQL回归 | 6项通过 |
| 独立PDF调用、水印、摘要及超时 | 1项通过 |
| ClamAV原生自定义签名验收 | 1项通过，使用无害测试摘要签名，生产模式拒绝该库 |
| PDF工作器自身回归 | 2项通过 |
| 前端测试、TypeScript及生产构建 | 7项通过，构建成功 |
| 真实HTTPS检查 | 18项通过 |
| 1440×1000 Edge电脑端 | 4项通过，浏览器无未处理错误 |

新增业务验证覆盖正常发布和PNG交付；扫描不可用不能发布、恢复后明确重试；命中隔离且不能洗白重试；个人撤权使旧阅读会话失效；文档明确拒绝优先并排除列表；次副本损坏阻断交付；保护证据过期和只读写入拒绝；委托撤销；扫描期间撤权不能提交READY结果。保护单元测试另覆盖签名篡改、错误模块、过期副本/保护点、未隔离旧主及服务重启后的接管代次回退。

HTTPS使用正式应用配置，实际观察到缺少官方病毒库时后台任务为SCANNER_UNAVAILABLE且对象不能发布。正常阅读原件来自真实引擎测试已通过的合成样本。匿名访问、CSRF缺失、跨文档预览、候选用途、越界页码、原件回退都被拒绝。电脑端验证真实登录、目录列表、PNG阅读及证据失效后的清屏。合成签名证据只验证门槛，不能证明存在真实备份或主备。

## 上线前仍需完成

1. 已验证ClamAV 1.5.4原生引擎；官方病毒库下载端点当前返回403，尚未取得有效正式库。生产必须安装并持续更新官方库，验证正常、命中、过期和不可用场景。测试签名不能替代生产库。参考[ClamAV官方签名管理说明](https://docs.clamav.net/manual/Usage/SignatureManagement.html)。
2. 本轮交付保护证据消费者，没有交付真实备份编排器和跨机器主备代理。正式代理须据实际副本、完整恢复点及旧主隔离证据签发，并演练接管、防双主和恢复。本机两目录不等于独立故障域，单机回执不等于生产不可改写存储。
3. PDF进程已限制堆、时间及输出，但仍须部署操作系统账号、文件系统、网络及资源隔离；真实业务样本的保真与性能验收待完成。工作器使用PDFBox 3.0.8，参考[官方读取边界](https://pdfbox.apache.org/3.0/migration.html)。
4. 受控孤儿核查、回收/保留、异常副本修复、任务管理与容量压测继续完善；当前故障优先拒绝，不自动使用未知副本交付正文。

## 环境与证据

本轮只用 D:\tmp\ims-platform-batch2-cont-20261027，PostgreSQL端口55439、临时HTTPS端口18445，只含合成数据。独立构建目录 D:\tmp\ims-platform-batch2-continuation-build，未覆盖原18443应用使用的产物。开发前源文件快照、哈希清单、测试日志、HTTP结果和电脑端截图位于 D:\work\01_工作\数据建设\tmp\platform_batch2_continued_20261027；实际凭据仅留在该轮私有环境。

开发前339个源文件中320个哈希不变，19个为本轮有意修改，0个缺失，详见证据目录的source-preservation.json。最终验收后已停止本轮18445临时应用和55439测试集群，测试数据与证据全部保留；原第一批应用进程22244仍在运行，未结束或覆盖。

复跑新增业务测试需显式 IMS_DRIVE_INTEGRATION=yes、本机新平台隔离库、私有目录、合成PDF和独立ClamAV程序。测试用签名替换仅存在于测试源码，未打入运行包。常规 mvn -Pplatform verify 不连接外部业务库；带外部数据库环境的测试须单独按类选择，不能将平台配置注入旧系统回归。第一批启动说明仍适用，新增变量见上表。

## 企业公盘生命周期与Office接入更新（2026-10-29）

本批补齐公盘日常操作及ONLYOFFICE接入协议。Office服务默认关闭，未部署时页面明确显示“尚未配置”；本批没有部署真实编辑器，不能将下面的协议集成测试表述为多人在线编辑已验收。旧系统、原18448预览配置及其55442数据库未切换。

### 已交付功能

- 电脑端支持目录浏览、全盘授权搜索、文件类型筛选、人员动作授权、负责人委托管理、目录创建与维护，以及独立的文件详情和回收站。新建目录、目录维护、授权表单分别保存输入；连续修改目录同步更新版本号和路径。
- 文件可重命名、上传新版本、查看/恢复历史版本、确认权限变化后移动、回收及恢复。恢复历史生成新版本，不覆盖旧记录。回收满30天后仅获委托负责人可逻辑清理，不自动删除原件、历史引用或备份。
- 公盘阅读、下载、上传、编辑、删除、移动、恢复分别授权，明确拒绝优先；员工删除、移动、恢复只作用于本人上传文件。跨部门单文件授权不会暴露父目录。IT默认内容权限仍为阅读/下载，不因基础管理角色获得内容写入权限；负责人管理受当前任职和IT委托上界限制。
- 上传改为Web Worker增量SHA-256及8MiB分片，界面接受后端5GiB上限，避免浏览器将整份原件放入内存。新增Office、UTF-8文本、压缩包及CAD格式识别，仍需实际检测通过。OOXML检查包结构并拒绝宏内容；旧Office检查OLE目录。压缩包/CAD仅作为原件管理，不能当作受控预览。没有执行5GiB文件的端到端容量验收，也未交付刷新页面后恢复上传任务的界面。
- 原件下载与历史下载检查当前DOWNLOAD授权和双副本；1MiB流式交付期间继续检查登录、撤权、状态和保护条件。页面下载链接绑定具体版本，ETag/If-Range防止不同版本的续传内容拼接。PDF历史版本可逐页受控阅读。
- Office接入具备固定服务地址/版本、分离的inbox与outbox密钥、原生签名回调、文档服务专用源文件入口、平台参与者记录、保存序号、检查点及回调去重。仅已开启协作且仍具VIEW和EDIT的人员可进入；当前在线处理上限独立设为256MiB。
- Office候选稿经过真实检测和双副本落盘后才能生成版本；撤权、检测失败、版本改变或最终保存无法确认时不会直接覆盖正式版本。负责人可结束异常会话、下载已通过检测的候选原件供检查，并在本人再验证后接受或舍弃；舍弃也保留对象记录。编辑器“已同步”与平台“已保存”分别呈现。
- 千文件目录测试暴露了逐文件重复计算目录授权的问题，V31改为每个目录计算一次，再处理文件明确拒绝与单文件例外。可见性过滤仍先于搜索匹配、计数和分页；1000文件测试通过3秒SQL执行上限。新增索引及不可变版本约束通过追加V27—V31迁移交付。

### 新增主要接口

| 接口 | 能力 |
| --- | --- |
| PATCH /api/v1/drive/documents/{id} | 文件重命名或负责人协作开关 |
| GET/POST /api/v1/drive/documents/{id}/versions | 历史分页或检测通过的新版本发布 |
| POST /api/v1/drive/documents/{id}/restore-version | 从历史追加新版本 |
| GET /api/v1/drive/documents/{id}/move-check、POST /{id}/move | 确认移动前后授权范围并执行 |
| DELETE /api/v1/drive/documents/{id} | 回收文件 |
| GET /api/v1/drive/recycle、POST /recycle/documents/{id}/restore、DELETE /recycle/documents/{id} | 回收查询、恢复、保留期后的逻辑清理 |
| GET /api/v1/drive/documents/{id}/download?versionId=... | 当前授权下的固定版本原件下载，支持单区间续传 |
| PATCH/DELETE /api/v1/drive/folders/{id} | 目录重命名、空目录回收；恢复及清理见DriveFolderController |
| GET /api/v1/drive/access-options、GET /api/v1/admin/drive/delegations | 当前可委托动作、人员搜索及IT委托查询 |
| GET /api/v1/drive/office/status、POST /api/v1/drive/documents/{id}/edit-sessions | Office状态与打开编辑会话；默认关闭 |
| /api/v1/drive/edit-sessions/{id}、/api/v1/drive/save-receipts | 会话、检查点、候选处理子接口详见OfficeController和OfficeReviewController |
| /integrations/onlyoffice/callback/{id}、/source/{id} | 仅接受文档服务outbox签名，不以普通浏览器登录代替服务身份 |

### Office配置与未完成项

新增配置位于platform.yml，全部以IMS_PLATFORM_OFFICE_开头：ENABLED默认false；BROWSER_ORIGIN为浏览器访问来源，SERVER_ORIGIN为平台调用地址，CALLBACK_ORIGIN为文档服务访问本平台地址；VERSION绑定实际验收版本。INBOX_KEY与OUTBOX_KEY分别指向独立密钥文件，TEMP为私有临时目录，SERVICE_ID为限定业务范围的服务身份，MAX_BYTES默认268435456。密钥不得放入网页资源、测试报告或日志。

本批已提出Office环境选择问题，但尚未收到答复，因此没有启动Docker或自行安装ONLYOFFICE。剩余工作包括：真实编辑器部署与跨服务网络联调、多人同时编辑/断开重连/最终保存验收、旧DOC/XLS/PPT保留原件并转换后编辑、Office和图片的受控预览。当前旧格式不能直接在线编辑；候选检查使用有下载权的原件交付，不能等同于仅有阅读权的受控预览。

真实服务接入前还需验证原生回调的重试行为和慢正文传输，并补齐保存处理的后台恢复机制；当前回调同步处理，HTTP请求超时不代表流式正文总耗时。目录移动、已有授权/委托的原位修改仍未交付，目前通过新增和撤销管理授权。没有以通用框架或未经确认的异常场景扩大本批实现。

协议依据为ONLYOFFICE官方[配置接口](https://api.onlyoffice.com/docs/docs-api/usage-api/config/)、[回调说明](https://api.onlyoffice.com/docs/docs-api/usage-api/callback-handler/)、[检查点命令](https://api.onlyoffice.com/docs/docs-api/additional-api/command-service/forcesave/)及[请求头签名](https://api.onlyoffice.com/docs/docs-api/additional-api/signature/request/token-in-header/)。协议测试中的HTTP服务只模拟文档服务边界；数据库、业务授权、原生ClamAV测试签名及双副本读写均执行实际实现。

### 本批验证与证据

| 验证 | 结果 |
| --- | --- |
| 最终常规后端verify | 124项实际通过；306项中182项依赖外部条件而跳过，跳过不计为通过 |
| DriveLifecycleIntegrationTest | 16项通过，真实隔离PostgreSQL；含Office回调、撤权候选、检测隔离、重复保存、版本上传、回收恢复及千文件目录 |
| DriveReadIntegrationTest、IntakeIntegrationTest | 35项通过，确认既有阅读及导入检测未退化 |
| 前端单元测试 | 16项通过，新增摘要测试与Node标准SHA-256独立对照 |
| TypeScript、电脑端生产构建 | 通过 |
| Edge真实HTTPS浏览器验收 | 8项通过；涵盖上传、下载、回收、授权拒绝、Office未配置提示与1366/1600宽度，无未处理页面异常 |
| Edge最终补验 | 4项通过：大目录侧栏独立滚动、目录连续两次修改与回收路径刷新、ETag续传200/206、下载链接绑定具体版本 |

本批隔离环境为D:\tmp\ims-platform-drive-20261029，PostgreSQL端口55449、临时HTTPS端口18455，只使用合成账号及文件；ClamAV无害测试签名替换仅存在于测试类，发布包不含该入口。双存储目录和合成保护证据用于验证拒绝逻辑，不代表独立故障域或正式主备已经验收。

源码基线、测试日志、浏览器脚本/截图和独立构建产物均保存在D:\work\01_工作\数据建设\tmp\platform_drive_20261029。常规verify不注入外部数据库配置；新增集成类还要求IMS_DRIVE_LIFECYCLE_INTEGRATION=yes并限制本批隔离数据库端口。不得为了复跑而将测试连接改为业务库。

最终包位于该证据目录desktop-release-final-build/archive-service-0.1.0.jar，45个网页资源与最新dist-platform逐个SHA-256一致，且不含浏览器验收启动器和测试检测器。新增Office三张表的字段说明缺失数为0。源文件基线298项中283项未改变、15项有意修改、无缺失，范围内新增26项，详见source-preservation.json；前端新增摘要测试另位于web/tests/unit/driveHash.test.ts。

验收结束已停止本批18455应用与55449测试集群，保留全部数据与证据；没有更新或启动原18448预览及55442数据库。本批隔离保护证据不能用于继续提供正式预览服务。

## ONLYOFFICE本机隔离部署与真实联调（2026-10-29后续）

用户已确认本机隔离部署。本节是上一节“尚未部署”的后续进展，旧记录保留作为阶段证据。新环境使用独立PostgreSQL 55450、平台HTTPS 18456、ONLYOFFICE HTTPS 18457；旧18448预览、55442数据库和历史Python系统未切换。

### 已接通的业务链路

- 多人使用同一文档服务key，平台独立记录全部参与者。关闭编辑先登记离开，最后一人等待原生最终回调；签名中的最后编辑者只作署名，不代替完整授权名单。静默轮询不延长登录，真实编辑才刷新活动时间。
- DOC、XLS、PPT在本人再验证和EDIT授权下转换为DOCX、XLSX、PPTX，检测和双副本通过后追加不可变版本。旧原件、摘要和历史下载保留；恢复历史时同时恢复正确扩展名。重复操作号不重复发布。
- 六类Office文件可按VIEW授权转换内部PDF，再由隔离工作器交付逐页PNG。PNG、JPEG、TIFF直接经过有界图片处理器。没有DOWNLOAD授权的人不能取得原件；原件和派生PDF的副本异常、当前撤权都阻断后续页面，不回退原件URL。
- 转换缓存绑定源版本、输出格式和文档服务版本；转换使用两个独立工作位，不占普通PDF渲染工作位。服务响应限制JSON 64KiB、候选256MiB，转换120秒、候选45秒整体期限覆盖响应正文，避免只限制连接头却长期挂住。
- 新增V32转换记录和V33预览派生对象绑定迁移。电脑端补齐转换入口、参与者、同步与正式保存状态，继续使用中文业务提示；未增加手机端布局。

### 已复现的原生检查点问题及保护

固定镜像`onlyoffice/documentserver:10.0.0.1`，实际服务返回`10.0.0.169`。真实双人Word编辑的非最终forcesave检查点多次遗漏部分字符，双方画面能看到完整文本。对照容器output.docx与平台保存对象，SHA-256完全相同；最终关闭两人后产生的另一个原生输出包含完整内容。该结果说明差异出现在文档服务候选输出，不能把平台协议测试通过当成检查点内容保真通过，也未假定具体的上游根因已经确定。

本批新增`IMS_PLATFORM_OFFICE_CHECKPOINT_ENABLED`，默认false。页面不提供“保存到平台”检查点按钮，直接调用检查点API也返回`OFFICE_CHECKPOINT_DISABLED`，不创建保存回执。多人编辑保留，所有人关闭后才按原生最终回调生成正式版本。未来只有固定镜像通过多人内容逐字比对后才开启此项；不为绕过问题降级到未经本批验证的旧镜像。失败复现脚本与候选证据保留在`tmp/platform_office_20261029/checkpoint-reproduction.cjs`、`checkpoint-evidence.json`及`office-checkpoint.docx`。

### 本机运行与恢复

本机已有Docker Desktop因安装注册项缺失不能启动，因此另建WSL2 Ubuntu 24.04发行版`ims-office-20261029`并安装Docker Engine；没有修补全局Docker注册表。专用WSL磁盘位于`D:\tmp\ims-office-engine-20261029`，平台运行资料位于`D:\tmp\ims-platform-office-20261029`。镜像固定摘要为`sha256:ca17889f10b11141cc6441836aebdd2835cf3e1b49681b2fd6e155c94b7850b3`，容器名`ims-onlyoffice-20261029`，限制2CPU、4GiB内存、6GiB含交换空间、256MiB共享内存；没有配置开机自动重启。

浏览器和平台访问`https://127.0.0.1:18457`，文档服务经`https://host.docker.internal:18458`回调。桥接只绑定WSL虚拟网卡、只接收当前专用发行版IP，再转发到Windows回环18456；下载依赖所需代理桥接18459同样限定来源，不在物理局域网监听。WSL地址重分配时必须同步修正桥接及容器主机映射，启动脚本会拒绝旧映射。

TLS采用本批独立CA；Java只在本进程使用trust.p12，文档服务只加载只读挂载中的CA。没有关闭业务请求证书验证，没有向系统全局安装根证书。Office只挂载自己的证书、私钥与CA公钥，未挂载CA私钥或平台私钥。inbox与outbox继续使用两个不同密钥。

原生Supervisor子进程没有继承容器`NODE_CONFIG`，因此出站签名覆盖配置源保存在私有运行目录`office-local-production-linux.json`，部署到容器`/etc/onlyoffice/documentserver/local-production-linux.json`，所有者root:ds、权限640，再重启`ds:docservice`。不手改镜像启动器生成的local.json。容器重建后必须重新应用该覆盖文件并实测签名；仅设置JWT_SECRET会使两个方向同钥，平台会拒绝错误出站签名。

恢复已经部署的Office服务可在仓库根目录运行：

```powershell
pwsh -File scripts/platform/start-local-office.ps1 -RuntimeRoot 'D:\tmp\ims-platform-office-20261029'
```

脚本复用专用容器、数据卷、WSL驻留进程和桥接，不创建/清理数据库，不替换容器，不启动旧预览。平台HTTPS应用和55450数据库分别启动。证据目录中的`prepare-office.cjs`、`office-probe.cjs`、`browser-runtime.cjs`保存了本机配置生成、签名版本探测和合成验收启动方式；口令及密钥只存在私有运行目录，不在报告内复制。

### 验证范围与发布限制

| 验证 | 结果 |
| --- | --- |
| 常规后端verify | 320项中125项实际通过、195项条件跳过，0失败；跳过不计通过 |
| DriveLifecycleIntegrationTest | 18项通过，授权、回执、重复保存、离开和过期等使用真实隔离库；外部回调协议边界仍为测试服务 |
| OfficeConversionIntegrationTest | 9项通过，真实ONLYOFFICE、原生ClamAV测试签名和隔离PDF进程；含三种转换/恢复、仅阅读、撤权、副本损坏、检测器不可用 |
| DriveReadIntegrationTest、PdfRenderClientIntegrationTest | 10项、2项通过；含实际图片子进程与授权控制 |
| PDF/图片工作器 | 7项通过 |
| 前端单元、TypeScript和电脑端构建 | 16项通过，构建成功 |
| Edge 1600×1000真实浏览器 | 最终10项通过：检查点关闭、双人Word最终保存、三类旧格式转换及历史复核、四类仅阅读预览、编辑中撤权清屏与迟到稿件隔离；无未处理页面错误 |
| 编辑器连续打开 | 独立复验同一页面连续打开并正常结束两次通过；启动恢复脚本在既有容器上运行通过 |

真实浏览器证据、保存文件、脚本及源码基线位于`D:\work\01_工作\数据建设\tmp\platform_office_20261029`。正式发布包为其中`release-build/archive-service-0.1.0.jar`；45个网页资源与最新dist-platform逐个SHA-256匹配，不含浏览器测试启动器和测试检测器。早期verify目录存在过期网页残留，最终包使用全新构建目录生成。

最终浏览器报告为`browser-office-report.json`。双人Word保存从实际画布键盘输入，经真实最终回调后下载DOCX并独立解析正文，逐字检查双方及最后编辑的三段标记；不是模拟编辑API。截图已核对中文正文、双人参与提示、仅阅读页及撤权后的清屏。三类旧格式此前已经由真实页面转换，最终复跑对已有结果与历史原件重新核对，不将重复查询记作第二次转换。浏览器联调曾遇到再次加载超时，保留中断记录并通过现有负责人再验证接口处理，随后连续打开和最终全流程均通过；未据此宣称网络故障恢复已经验收。Excel、PPT的转换与预览已验证，其双人编辑内容保真尚未单独验收。

本批源码基线334项中318项不变、16项有意修改、无缺失，范围内新增10项；明细见`source-preservation.json`。新增转换字段与预览绑定字段中文说明缺失数为0。当前保留18456合成验收应用、55450独立库和18457文档服务运行，便于继续联调；18448、55442没有启动或切换。该合成验收应用不能接收正式业务文件。

本机仅含合成账号与样本，检测器使用无害测试签名，保护事实使用合成签名证据；不能将本轮结果表述为官方病毒库、跨机器主备、独立故障域备份或正式不可改写存储已验收。多人检查点保真尚未通过；实际复杂业务文档、断网重连及并发容量仍需专项验收。当前转换/预览和最终保存的验证范围应分别陈述，不能泛称所有Office能力均已通过。

## Excel、PPT双人专项与检查点漏字修复（2026-10-30）

本节接续上次未通过的检查点验收，保留此前记录。仍使用相同固定镜像及`10.0.0.169`，没有替换Office版本、放宽签名校验或修改业务授权。新增文件均由合成样本上传，旧文件及失败候选保留。

最终状态：本机`https://127.0.0.1:18456`已开放“保存到平台”。三格式隔离专项17组通过，预览地址的Excel、PPT复验12组通过，均无页面脚本错误或有效同步时间回退。下文保留了修复前失败、根因及补丁证据；最终通过报告为`dependencies-fixed-full/report.json`和`preview-final-accepted/report.json`。

### 根因与修复证据

漏字来自专用WSL中Ubuntu NTP与宿主校时不一致，而非平台丢失文件字节。`systemd-timesyncd`测得NTP相对宿主偏差约-29.296秒；内核日志反复出现`Time jumped backwards`。修复前40秒实测有4次回退、最大跳变29.297秒，单次采样结束时宿主偏差却只有0.09秒，说明只比较一次当前时间会漏检。

浏览器同步记录进一步确认：失败批次306条已发送的变更都取得原生确认，其中部分记录的确认时间突然前移约29秒，随后又回退。原生检查点变更包只有300条，缺失6条；没有额外记录。固定版本[内存变更查询](https://github.com/ONLYOFFICE/server/blob/v10.0.0.169/DocService/sources/databaseConnectors/memoryConnector.js)按检查点时刻过滤，且[文件生成器](https://github.com/ONLYOFFICE/server/blob/v10.0.0.169/FileConverter/sources/converter.js)在非最终保存时传入该时刻，因此刚才被记为“未来”的中间字符被排除。最终保存没有同样的时间上界，内容完整。Ctrl+S本身不是已确认的根因，原步骤只是再次触发了该时钟问题。

用户明确授权后，仅在`ims-office-20261029`中执行`timedatectl set-ntp false`，停止与WSL宿主竞争的独立NTP；没有修改Windows时间或全局NTP配置。需要撤回本项配置时使用`timedatectl set-ntp true`，但必须重新检查校时一致性和检查点保真后再开放保存。该本机处理依赖宿主校时；正式服务器应统一可信校时来源，不能照搬为“所有服务器都关闭NTP”。

修复后40秒采样最大跳变约0.000004秒、回退0次、宿主偏差约0.092秒。对原失败键盘步骤重新验收，浏览器发送306条变更，原生检查点也包含306条，缺失0、额外0，双方文本完整。证据为本轮目录的`clock-before.json`、`clock-after.json`、`wire-analysis-OfficeLivef0d8a61a.json`、`wire-analysis-OfficeLive7870a8c1.json`及对应原生DOCX和变更包。

### 代码与复验方式

新增`scripts/platform/test-local-office-clock.ps1`，只读采样40秒，以单调钟对照实时钟，拒绝超过0.5秒的时钟跳变或超过2秒的宿主偏差；不自动改系统配置。`start-local-office.ps1`在恢复容器前执行该检查，报告保存在私有运行目录的`office-clock-check.json`。负例用本机真实回退验证为失败，修复后独立检查及完整启动脚本均通过。

新增`web/tests/platform/office-live.cjs`，沿用现有Playwright/Edge与正式登录、上传、检测、授权接口。每次新建三份样本，不改旧业务文件。两名独立登录用户在真实画布输入，下载服务端版本后独立解析OOXML，检查Word中文原文、Excel单元格/公式结果55、PPT两页顺序及每人的文本归属；不调用编辑器内部文档修改API，不用模拟回调代替验收。脚本同时保存原生同步确认时刻，检查回退，不记录认证帧或密钥。

```powershell
$env:IMS_OFFICE_TEST_FIXTURE = 'D:\tmp\ims-platform-office-20261029\browser-fixture.private.json'
$env:IMS_OFFICE_TEST_URL = 'https://127.0.0.1:18456'
$env:IMS_OFFICE_SAMPLE_DIRECTORY = 'D:\work\01_工作\数据建设\tmp\platform_office_20261029\samples'
$env:IMS_OFFICE_TEST_OUTPUT = Join-Path 'D:\work\01_工作\数据建设\tmp\platform_office_20261030' ('acceptance-' + [guid]::NewGuid().ToString('N'))
$env:IMS_OFFICE_TEST_FROM = 'docx'
$env:IMS_OFFICE_TEST_DELAY_BASES_MS = '3000'
node web/tests/platform/office-live.cjs
```

上述fixture只适用于已有的合成验收环境。复跑应指定新的输出目录保留前次证据，不能把该配置改指正式业务账号。独立18460测试实例先通过三类文件连续保存、重复提交、一人退出后继续保存、最终保存、重新打开及撤权隔离，再开启18456本地预览配置；18460随后关闭。配置缺省仍为false，其他部署须独立完成时钟及内容验收。

本轮验收文件和报告位于`D:\work\01_工作\数据建设\tmp\platform_office_20261030`，第一次完整回归见`fixed-probe/report.json`。18456开放后的第一轮复验见`preview-final`、`preview-verified`和`excel-ppt-confirmed`；这些报告保留了下述断言修正及加载失败，不能作为最终通过报告。各次版本摘要保存在相应目录`versions.jsonl`，原生同步确认时刻为`sync-clocks.json`。

首轮18456复验的17组内容及权限场景均通过，但额外时钟断言将原生无变更解锁的`time=-1`当作真实时间，报告保留在`preview-final`。对照固定版本原生`DocsCoServer.js`中`unSaveLock(ctx, conn, -1, -1, -1)`后修正采集边界：该标记不参与真实时间比较。原记录288项中8项为无变更解锁，其余280项没有时间回退；没有删除该失败报告或把它改成通过，修正后另建`preview-verified`目录复跑。

`preview-verified`中Word及Excel的全部保存内容核对通过；Excel最终版本再次打开时出现180秒加载超时，画面停留在原生编辑器骨架页，浏览器报告`Cannot read properties of undefined (reading 'extend')`。保存按钮一直禁用，已保存文件仍完整。脚本增加`IMS_OFFICE_TEST_FROM=xlsx`支持从中断格式开始在新样本上复验；`excel-ppt-confirmed`再次发生加载失败，完整错误栈定位到`ExtendedColorDialog.js`中的`Common.UI.Window.extend`。随后重新关闭18456检查点入口，在18460继续定位并修复，不能仅以重新加载偶然成功判定问题消失。

### 原生编辑器加载顺序修复

固定版本的`ColorPaletteExt`、`HSBColorPicker`、`MaskedField`、`TextareaField`使用`BaseView`却没有声明AMD依赖，`ExtendedColorDialog`同样漏写`Window`依赖。父模块将两者并列加载并不能保证基础类先完成，造成原生编辑器停在骨架页。此问题与[上游记录3822](https://github.com/ONLYOFFICE/DocumentServer/issues/3822)一致，当前`10.0.0.169`容器源码也已逐份核对。测试只将真实`BaseView.js`与`Window.js`响应延迟3秒，没有替换脚本或修改业务回调；修复前首次打开Excel即稳定失败，三个基础类错误及截图保存在`amd-before`。

新增`scripts/platform/patch-local-office-amd.py`，补齐这些未打包源码的依赖声明，后续追加下述实际复现的`Tip.js`，最终共六份源码。脚本先核对全部固定原件SHA-256，未知版本拒绝修改；原件保存在容器`/var/lib/onlyoffice/ims-amd-originals-v1`，gzip由修复后的源码重新生成。没有手改压缩文件、改变编辑内容、放宽权限或隐藏页面错误。`start-local-office.ps1`恢复固定容器时执行该补丁，摘要报告保存到私有运行目录`office-amd-patch.json`。镜像标签和摘要未变，六个源码及其压缩副本为明确记录的运行层补丁，不能称为完全未修改的原生容器。

`--check`只读核对源码及压缩副本；`--restore`恢复十二份原件，操作前须关闭检查点。临时目录测试直接执行实际补丁脚本，验证重复应用不改变内容、恢复后十二份原件逐字节一致、未知源码被拒绝且其余目标没有提前修改，见`amd-patch-lifecycle-final.json`。正常启动脚本会再次应用补丁，若决定永久撤回，应同时撤回启动集成。使用本地CA验证的真实HTTPS核对六份普通响应和六份gzip响应，全部与修复后摘要一致，见`amd-delivery-final.json`。此前已打开编辑器的浏览器需要关闭编辑后强制刷新，以更新缓存。

修复后保持相同3秒延迟，从全新合成样本完成三格式专项，`amd-fixed-full/report.json`为通过：17组场景、页面脚本错误0、280次有效同步确认无回退。包括每种格式连续三轮双人保存及重复请求不增版、一人退出后继续编辑和最终保存、最新版本重新打开、三格式仅VIEW用户被拒绝编辑、编辑中撤权清屏及迟到候选不覆盖正式版本。同步检查仍排除原生`time=-1`无变更标记。Excel公式为55，PPT两页顺序及双方文本归属正确；截图已检查。

随后在18456进行无额外加载延迟的Excel、PPT复验，`preview-accepted`的12组内容及权限场景全部通过，包括逐个解析C3至C8及C10单元格实际引用；但最终页面诊断发现`Tip.js`的`$.fn.tooltip.Constructor`在基础tooltip尚未到达时被访问。该报告仍为失败，未隐藏该错误。再次关闭18456检查点入口后，在18460将基础tooltip响应延迟3秒，首次打开即复现同样错误，证据为`tooltip-before`。

第六项补丁把`Tip.js`末尾立即执行改为声明`common/main/lib/mods/tooltip`依赖的AMD定义，确保扩展及其使用者都等待基础组件。没有增加反复重试或吞错。三个真实基础组件同时延迟3秒后，`dependencies-fixed-full/report.json`最终通过：17组场景、页面脚本错误0、280次有效同步确认无回退；本轮已包含Excel实际单元格引用核对。工具提示扩展初始化结果的只读断言已加入后续验收脚本。

更新后的完整启动脚本也已执行通过，40秒时钟检查及六份组件补丁均成功。最终恢复18456检查点入口后，在正常加载条件下重新执行Excel、PPT专项，`preview-final-accepted/report.json`通过：12组场景、页面脚本错误0、142次有效同步确认无回退。工具提示扩展的初始化断言、实际单元格引用及PPT文本归属均通过。当前保留18456应用、18457文档服务与55450独立测试库；临时18460及旧18448、55442均停止，见`runtime-accepted.json`。此前`runtime-final.json`只作为阶段记录保留。

校时检查仅在启动时运行，耗时约40秒；补丁只处理六份静态源码，内容相同不会重写。运行时继续使用既有同步、检测、双副本及版本保存流程，本轮没有增加保存接口的额外轮询或整库扫描。源码基线336项中333项未改、3项有意修改、缺失0项，新增3个脚本；最终摘要见`source-preservation.json`。脚本语法、现有Prettier格式和`git diff --check`均通过。

本轮没有修改Java业务实现或Vue页面布局，不需要更换上一轮已核对的平台网页/JAR；配置注释、启动检查、原生Office组件依赖补丁和验收脚本已更新。页面继续只针对电脑端。本机检测签名及保护证据仍为合成验收用途，复杂业务样本、断网重连、并发容量和跨机冗余不在本次通过范围内。
