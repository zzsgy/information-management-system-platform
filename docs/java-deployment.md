# Java 档案模块部署与恢复

本说明面向当前独立运行的档案模块。后端采用 **Java 21 编译目标、Spring Boot 4.1.1、PostgreSQL 17 或更新版本**；当前主前端已改为 **Vue浏览器网页**，完整构建与访问见[Web部署说明](web-deployment.md)。先在 Windows 内网试用，后续将同一个经过验证的 JAR 部署到 Linux。旧 `client/`、`server/` 和旧安装包保留作历史对照，不参与新Web服务启动。

系统从空库开始。本轮没有旧 Python 数据迁移步骤，也没有建立首页、公盘或完整的平台统一门户。

## 1. 准备依赖和持久化目录

构建机器需要 JDK 21 和 Maven；建议使用 Maven 3.9 系列。服务运行机器需要 Java 21 兼容运行时；由于恢复入口使用 Java 源文件启动，保留完整 JDK 21 更便于运维。更高版本 JDK 必须先完成本项目验证，编译产物仍以 `backend/pom.xml` 中的 Java 21 为目标。

数据库安装 PostgreSQL 17 或更新版本，同时安装同版本的 `psql`、`pg_dump`、`pg_restore`。备份工具主版本不得低于数据库服务主版本。首次部署优先使数据库、备份工具和恢复目标主版本一致，不将跨大版本升级与本次迁移同时处理。

从公司批准的软件源安装上述依赖，配置 `JAVA_HOME` 和 `PATH`。本仓库脚本不自动安装软件、不创建数据库、不申请管理员权限。安装后检查：

```powershell
java --version
mvn --version
psql --version
pg_dump --version
pg_restore --version
```

Windows 上建议 PostgreSQL 程序与数据库数据目录使用英文路径，例如 `D:\PostgreSQL\17`、`D:\ims-data\postgres`。本机验证中 PostgreSQL `initdb` 在中文路径下出现过编码问题；这不要求移动已有 Java 仓库，Java 项目路径可以包含中文。不要为处理路径问题移动或清空现有 PostgreSQL 数据目录。

建议为当前服务使用以下独立目录，实际位置由运维人员确定：

| 内容 | Windows 示例 | Linux 示例 | 说明 |
|---|---|---|---|
| 程序/源码 | 当前项目目录 | `/opt/ims/archive` | 升级时可替换程序，不混放业务数据 |
| 附件 | `D:\ims-data\attachments` | `/srv/ims/attachments` | 保存全部附件版本，必须持久化 |
| 完整备份 | `D:\ims-backups` | `/srv/ims-backups` | 不能位于附件目录内，也不能包含附件目录 |
| PostgreSQL 数据 | 由 PostgreSQL 服务管理 | 由 PostgreSQL 服务管理 | 应用不直接读写数据库数据文件 |

以普通专用服务账号运行 Java，授予其附件和备份目录必要的读写权限。不要与其他服务共享这些可写目录。目录不能是符号链接，附件树中出现符号链接时整体备份会拒绝执行。

## 2. 新建数据库和业务账号

以下操作由数据库管理员在 `psql` 中执行。管理员账号仅用于建库，不配置给 Java 服务。数据库名和角色名可调整，但后续环境变量必须对应。

```sql
-- 应用角色只拥有本业务库，不具有超级用户、建库、建角色或复制权限。
CREATE ROLE ims_archive LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
-- psql 交互设置真实密码；不要将口令写入 SQL 文件或命令历史。
\password ims_archive

-- 创建全新的 UTF-8 业务库，禁止指定已有生产库进行初始化。
CREATE DATABASE ims_archive OWNER ims_archive ENCODING 'UTF8';
REVOKE ALL ON DATABASE ims_archive FROM PUBLIC;
\connect ims_archive
-- 只有业务所有者能创建业务结构；Flyway 以该账号执行版本迁移。
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO ims_archive;
```

通过 PostgreSQL 的连接访问配置允许 Java 服务所在主机访问该库。不要将数据库端口开放给无关网段。应用账号需要拥有本库对象以执行 Flyway，但不需要 PostgreSQL 超级用户权限。

## 3. 构建并验证 JAR

在项目根目录打开 PowerShell：

```powershell
# 完成编译、自动化测试、验证及 Spring Boot JAR 打包。
.\scripts\java\build.ps1
```

也可直接使用跨平台命令：

```text
mvn -B --no-transfer-progress -f backend/pom.xml verify
```

当前版本产物为 `backend/target/archive-service-0.1.0.jar`。只有命令退出码为零时才能将本次产物用于部署。不能以存在旧 JAR、只完成编译或跳过测试作为本次构建成功的依据。

自动化测试使用隔离数据和测试目录。实际数据库集成与恢复演练应使用专门的新测试库，不能指向正在使用的业务库。测试环境、命令和结果另见[验证报告](java-refactor-validation.md)，不在这里固定测试数量。

## 4. 环境变量

下表与 `backend/src/main/resources/application.yml` 对应。启动脚本对数据库和持久化目录采用比配置文件更严格的显式检查，避免误用相对目录或空密码。

| 变量 | 配置文件缺省值 | 部署含义 |
|---|---|---|
| `IMS_HOST` | `127.0.0.1` | 监听地址；内网直连时显式改为合适地址，或放在 TLS 网关后 |
| `IMS_PORT` | `8000` | HTTP 端口，与客户端地址保持一致 |
| `IMS_DB_URL` | `jdbc:postgresql://localhost:5432/ims_archive` | 启动脚本要求显式配置，禁止指向旧业务库 |
| `IMS_DB_USER` | `ims_archive` | 本库业务角色，启动脚本要求显式配置 |
| `IMS_DB_PASSWORD` | 空 | 必须提供真实密码；示例和仓库不提供默认口令 |
| `IMS_DB_POOL_SIZE` | `15` | 连接池上限，根据后续并发测试调整 |
| `IMS_STORAGE_ROOT` | `./runtime/attachments` | 启动脚本要求使用明确的绝对路径 |
| `IMS_BACKUP_ROOT` | `./runtime/backups` | 启动脚本要求使用明确的绝对路径，不能与附件目录重叠 |
| `IMS_PG_BIN` | 空，使用 `PATH` | 包含 `pg_dump`、`pg_restore`、`psql` 的目录 |
| `IMS_BACKUP_TIMEOUT_SECONDS` | `240` | 完整备份/校验预算，允许 30～3600 秒；提高时同步客户端请求超时 |
| `IMS_MAX_FILE_SIZE` | `100MB` | 单个附件上传上限 |
| `IMS_MAX_REQUEST_SIZE` | `105MB` | 请求总大小，需大于单文件上限 |
| `IMS_SESSION_HOURS` | `12` | 服务端会话最长有效时间；停用、改密、退出另有即时撤销 |
| `IMS_BOOTSTRAP_ADMIN_PASSWORD` | 空 | 仅空账号库首次初始化使用，要求 12～128 字符；初始化后移除 |

密码应由进程环境、受限服务配置或公司密钥管理方式提供，不提交 `.env`、真实口令、备份包或本地绝对路径到 Git。后端不会自动读取客户端的 `.env`。

## 5. Windows 首次启动

先设置非敏感配置。下面是目录示例，应按实际安装位置修改：

```powershell
# 新业务库连接，不连接旧 Python 服务数据库。
$env:IMS_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/ims_archive'
$env:IMS_DB_USER = 'ims_archive'
# 附件与备份是两个彼此独立的持久化目录。
$env:IMS_STORAGE_ROOT = 'D:\ims-data\attachments'
$env:IMS_BACKUP_ROOT = 'D:\ims-backups'
# 使用已安装的 PostgreSQL 同版本工具。
$env:IMS_PG_BIN = 'D:\PostgreSQL\17\bin'
# 先只允许本机访问，完成验证后再开放所需内网地址。
$env:IMS_HOST = '127.0.0.1'
$env:IMS_PORT = '8000'

# 交互输入，不在脚本或控制台回显明文口令。
$databaseSecret = Read-Host '输入业务数据库账号密码' -AsSecureString
$env:IMS_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $databaseSecret).Password
Remove-Variable databaseSecret
$bootstrapSecret = Read-Host '设置首管理员初始密码（12至128字符）' -AsSecureString
$env:IMS_BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $bootstrapSecret).Password
Remove-Variable bootstrapSecret

# 首次空库初始化显式要求初始密码；脚本以前台进程运行服务。
.\scripts\java\start.ps1 -InitializeAdmin
```

第一次启动由 Flyway 建立数据库结构。迁移失败应解决原因后重试，不能关闭迁移验证继续运行。账号表为空且没有提供初始密码时，服务可能启动但不会创建管理员，日志会说明登录不可用；这不等于初始化完成。

首次账号名为 `admin`，密码就是部署者输入的初始密码。登录后修改密码，再移除启动环境中的 `IMS_BOOTSTRAP_ADMIN_PASSWORD`。前台运行时可在完成验证后按 Ctrl+C 停止当前 Java 进程，在原 PowerShell 中移除初始化变量并再次启动：

```powershell
# 只移除当前 PowerShell 的初始化配置；不改数据库中的已设密码。
Remove-Item Env:IMS_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
.\scripts\java\start.ps1
```

使用另一个终端检查健康状态：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8000/health'
```

健康接口检查数据库连通性，但不替代登录、权限、文件和审批验收。维护备份期间普通请求会收到 `503`，稍后重试；这段时间健康请求也可能被维护门禁暂时拒绝。

端口被占用时应确认占用服务并调整配置，启动脚本不会终止其他进程。长期运行应交给公司批准的 Windows 服务管理方式，使用独立服务账号和受限环境配置；当前脚本仅负责前台启动，不自动注册系统服务。

## 6. 浏览器访问

先使用`scripts/java/build.ps1`或`build.sh`构建包含网页的完整JAR，再按上文配置并启动。浏览器打开`http://127.0.0.1:8000/`即可登录。本机默认监听地址不接受其他电脑访问；多人试用应配置内网监听地址并使用服务器实际内网地址访问，正式部署通过公司TLS网关。

员工电脑不需要安装Python或Java客户端。原PySide6代码仅保留兼容对照，历史说明见`client-java-adaptation.md`；当前网页说明见`web-deployment.md`。

## 7. Linux 部署

先按 Linux 发行版的软件管理规范安装 JDK 21 和 PostgreSQL 17+ 客户端工具，部署同一版本的 JAR、脚本及恢复源码。附件与备份目录使用 Linux 绝对路径，并授权给服务账号。

```bash
# 业务配置须与新 Linux 环境中的数据库和持久化目录一致。
export IMS_DB_URL='jdbc:postgresql://127.0.0.1:5432/ims_archive'
export IMS_DB_USER='ims_archive'
export IMS_STORAGE_ROOT='/srv/ims/attachments'
export IMS_BACKUP_ROOT='/srv/ims-backups'
export IMS_HOST='127.0.0.1'
export IMS_PORT='8000'
# 按实际发行版 PostgreSQL 工具安装目录设置，也可将工具加入 PATH。
export IMS_PG_BIN='/usr/lib/postgresql/17/bin'
# 交互获取数据库口令，不在脚本中写入默认值。
read -r -s -p '数据库账号密码：' IMS_DB_PASSWORD
printf '\n'
export IMS_DB_PASSWORD

# 已有经过恢复验证的账号库，不需要再次设置初始管理员密码。
bash scripts/java/start.sh --jar backend/target/archive-service-0.1.0.jar
```

全新空库首次启动时，另行安全设置 `IMS_BOOTSTRAP_ADMIN_PASSWORD`，并加上 `--initialize-admin`；初始化规则与 Windows 相同。启动入口使用 `exec java`，便于后续交给 systemd。具体服务安装、环境文件权限、日志轮转、开机启动和证书由公司环境确认后配置，本脚本不自动修改系统服务。

从 Windows 迁移到 Linux 时使用下述整体备份与新库恢复流程；不要只复制 JAR 或只导入数据库。

## 8. 创建和验证完整备份

使用具有 `security.manage` 的账号进入客户端“数据安全运维”：

1. 运行电子文件完整性校验，处理缺失、大小变化或 SHA256 不匹配的文件。
2. 点击“立即备份”。服务进入维护门禁，先等待在途请求结束，再导出数据库并复制全部附件版本。
3. 只有 `pg_dump` 退出码为零、`pg_restore --list` 可读取、实际文件内容与数据库引用一致、ZIP 清单和全部哈希验证通过，才发布 `.imsbackup.zip` 文件并返回成功。
4. 在备份列表确认文件名、大小和时间。完整包位于 `IMS_BACKUP_ROOT`，同时包含 `database.dump`、`attachments/` 下的文件和 `manifest.properties`。
5. 将完整包复制到公司批准的独立备份介质，另行按恢复流程演练。与源盘放在同一磁盘的备份不足以覆盖磁盘损坏场景。

对应接口是 `POST /api/security/db-backup`、`GET /api/security/db-backups`、`GET /api/security/integrity/check`。成功创建响应含 `includes_attachments=true` 和 `verified=true`；这表示包创建与内容校验通过，不代表已经完成恢复演练。

删除单个备份使用 `DELETE /api/security/db-backups/{文件名}?confirm=true`，需要同一运维权限。接口不接受任意路径或目录递归删除。损坏包不会出现在可恢复列表中，需在服务端明确核对后处理。

失败工具日志保存在备份目录中的 `failed-<随机标识>.log`，不会作为备份展示。未完成暂存包不应当作成功备份。日志不能用于替代数据库与附件实体。

**当前一致性保证的条件：只有一个 Java 服务实例使用该业务库和附件目录，且不存在绕过应用直接改库或改附件的程序。** 门禁是进程内锁，覆盖同步 HTTP 请求全链路；它不是多实例分布式锁。后续引入后台写任务、多实例服务或其他平台共享写入时，必须先升级一致性方案。

默认备份预算 240 秒，客户端相关请求超时目前为 300 秒。若实际附件规模导致超时，应先测量所需窗口，再一起调整服务端预算、客户端请求超时和网关超时。不能只增加一处配置后把客户端超时当作备份成功。

## 9. 隔离恢复到新库和新附件目录

在线 `POST /api/security/db-restore` 只校验选中的完整包，然后明确返回 `409` 和 `offline_required`。它不会覆盖当前数据库，也不会返回普通成功响应。客户端应将其展示为“需要隔离恢复”，不能解释为恢复完成。

恢复采用独立 JDK 工具，原数据库、原附件和当前服务配置保持不变。先完成以下准备：

1. 保存完整备份包及其来源版本；确认有 JDK 21 和匹配的 `psql`、`pg_restore`。
2. 由数据库管理员建立**全新的空数据库**，例如 `ims_restore_01`。建议创建独立恢复业务角色 `ims_restore`，同样使用 `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`，仅让该角色拥有新库，并通过 `\password` 设置真实口令。
3. 恢复目标库不能先启动 Java 服务或执行 Flyway，也不能有其他连接。
4. 指定一个**尚不存在**的新附件目录，例如 `D:\ims-data\restore-attachments-01`；其父目录必须已经存在。不要选择当前附件目录或在原目录上覆盖。

Windows 示例：

```powershell
# 使用独立 RESTORE 配置，不修改正在运行的 IMS_DB_URL 等生产变量。
$env:IMS_RESTORE_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/ims_restore_01'
$env:IMS_RESTORE_DB_USER = 'ims_restore'
$restoreSecret = Read-Host '输入新恢复数据库账号密码' -AsSecureString
$env:IMS_RESTORE_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $restoreSecret).Password
Remove-Variable restoreSecret
$env:IMS_RESTORE_STORAGE_ROOT = 'D:\ims-data\restore-attachments-01'
$env:IMS_PG_BIN = 'D:\PostgreSQL\17\bin'
# 将下列文件名替换为备份列表中的真实完整文件名。
.\scripts\java\restore.ps1 -BackupFile 'D:\ims-backups\<实际备份文件名>.imsbackup.zip'
```

Linux 示例：

```bash
export IMS_RESTORE_DB_URL='jdbc:postgresql://127.0.0.1:5432/ims_restore_01'
export IMS_RESTORE_DB_USER='ims_restore'
read -r -s -p '新恢复数据库账号密码：' IMS_RESTORE_DB_PASSWORD
printf '\n'
export IMS_RESTORE_DB_PASSWORD
export IMS_RESTORE_STORAGE_ROOT='/srv/ims/restore-attachments-01'
# 使用实际备份包路径；恢复目标目录此时不应存在。
bash scripts/java/restore.sh '/srv/ims-backups/<实际备份文件名>.imsbackup.zip'
```

工具执行以下检查，任何一步失败都以非零退出码结束：

- 全部压缩成员必须与清单一致，路径必须安全，逐项大小和 SHA256 必须匹配。
- 目标数据库必须为空且没有其他连接。
- 使用 `pg_restore --single-transaction --exit-on-error --no-owner --no-acl`，不使用 `--clean`，不主动结束其他数据库连接。
- 恢复后的档案和附件记录数量与清单一致，每条附件元数据的存储键、大小、SHA256 与已验证实体一致。
- 撤销恢复库中的所有旧会话，记录恢复审计，再发布新的附件目录。

源文件入口是 `backend/src/main/java/com/company/ims/operations/OfflineRestore.java`；包装脚本内部使用 `java --source 21`，无需启动 Spring Boot，也不运行自动迁移。恢复暂存目录和过程记录保留在新附件目录的父目录，便于核对。

失败时，原库和原附件仍保留；**新的目标库或暂存目录可能已经包含恢复内容**。不要宣称已自动回滚，不要直接重试覆盖，也不要切换当前服务配置。检查日志后，由运维人员决定保留证据、换用另一组新的目标或明确清理本次测试目标。

恢复命令成功仍需验收：使用另一个隔离端口启动服务指向新库与新附件目录，重新登录，检查档案数量、权限隔离、附件下载、审批历史及完整性校验。旧会话已撤销，旧令牌不能继续使用。

验收完成后，在约定维护窗口停止旧服务，把 `IMS_DB_URL`、`IMS_DB_USER`、`IMS_DB_PASSWORD` 和 `IMS_STORAGE_ROOT` 一起切换到同一组恢复目标，再启动并检查。保留旧库与旧附件作为切换依据；是否清理由责任人另行决定。Linux 切换同样使用这组配套数据，不能混用两个备份时点。

## 10. 试用验收与当前限制

本轮代码验证和恢复演练结果应看随项目更新的验证报告。当前不能仅凭单元/集成测试就声称已通过真实生产环境、100 人并发或长周期稳定性验收。

先以几名用户验证：登录与禁用生效、部门隔离和共享、档案创建与归档、附件版本、不同权限下的借阅与审批、销毁申请明细核对、异常拒绝、备份和隔离恢复。之后根据真实附件规模、日常操作组合和网络环境开展几十至约 100 人使用规模的并发与容量测试。

部署时仍需确认服务账号、TLS、防火墙、独立备份介质、恢复时间目标、日志保留、运维职责和业务验收责任。首页、公盘、统一身份平台接入及前端较大范围体验优化按后续阶段推进，不属于已经交付的完整平台能力。
