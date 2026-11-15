# 信息管理系统

当前平台包括员工工作台、企业公盘、档案管理、会议预约和管理中心。主前端位于 `web/src/platform/`，主后端位于 `backend/src/main/java/com/company/platform/`。

## 平台本机启动

使用 Java 21、Node.js、Maven、PowerShell 7 和 PostgreSQL。先构建，再初始化独立空库并启动。路径按本机安装位置调整。

```powershell
./scripts/platform/build.ps1
./scripts/platform/create-local-environment.ps1 -Root D:/ims-local -PostgresBin D:/PostgreSQL/pgsql16/bin
./scripts/platform/start.ps1 -ConfigPath D:/ims-local/platform.private.json
```

打开 `https://127.0.0.1:18443/platform.html`。全新本机环境的默认工号是 `admin`，初始密码是 `123456`。该默认密码仅用于回环地址上的首次初始化。初始化后如需开放内网访问，先在系统内修改密码。已有数据库沿用原账号和密码。

源码附带数据库结构迁移和验证代码。测试数据库、业务记录、上传附件、测试结果和本机连接凭据均由环境单独维护。

现行需求规格、总体架构、接口设计、数据库设计和两份子系统架构见 [设计文档](docs/specifications/README.md)。

## 档案兼容模块

当前实现是信息管理系统中的档案模块：**Vue 3 + TypeScript + Element Plus 网页前端，Java 21 + Spring Boot 4.1.1 后端，PostgreSQL 数据库**。用户直接用浏览器访问，不需要安装桌面客户端。网页随 Java 服务打入同一个 JAR，先在 Windows 内网验证，后续可使用相同产物部署到 Linux。

本轮从空数据库开始，不迁移旧 Python 服务的数据。`server/` 保留旧实现供对照；新部署入口为 `backend/`。当前不建设首页、公盘或统一平台门户，也不将规划中的全部档案流程提前纳入本轮。

| 目录 | 作用 |
|---|---|
| `backend/` | Java 服务、版本化数据库迁移、权限、档案、审批及运维 |
| `web/` | 当前主前端：登录、台账、附件、借阅、销毁、异常、审批与管理页面 |
| `client/` | 保留原 PySide6 客户端作迁移对照，不是当前主入口 |
| `scripts/java/` | Windows / Linux 构建、启动与恢复入口 |
| `docs/design/java-archive-architecture.md` | 架构、数据边界、接口兼容及后续集成说明 |
| `docs/java-deployment.md` | 空库初始化、配置、备份恢复和验证方法 |
| `docs/web-deployment.md` | Web构建、访问、开发代理与浏览器验收 |
| `docs/web-validation.md` | Web改造的实际测试结果与未验收边界 |
| `docs/java-refactor-validation.md` | 本次测试、真实联调、恢复演练结果和未验收范围 |
| `server/`、`installer/` | 历史 Python 服务和旧安装器；不用于 Java 服务部署 |

开始使用请阅读[Web部署说明](docs/web-deployment.md)。Windows执行 `./scripts/java/build.ps1`，Linux执行 `bash scripts/java/build.sh`；配置数据库后启动 JAR，浏览器访问 `http://服务器地址:8000/`。开发前端可在 `web` 目录运行 `npm run dev`。首次管理员密码必须显式提供，不提供内置默认密码。

新增或重构的前后端接口、方法、变量、配置和迁移结构使用中文注释；维护时同步更新注释、行为与验证说明。JSON依赖清单的用途见部署文档，实际版本以锁文件为准。

独立平台的后续开发位于 `backend/src/main/java/com/company/platform` 与 `web/src/platform`，采用独立库及 `platform` 构建配置，和上文保留的档案兼容入口分别构建。本批已接通明确动作审批、版本绑定、水印下载、受控打印和交付记录，见[受批下载和打印验收说明](docs/platform-approved-exports.md)；前一批见[管理配置与电子借阅生命周期](docs/platform-borrow-lifecycle.md)。电脑端为当前开发范围。

后续纸质借还批次接入真实纸质/混合原件台账、独立纸质审批、实际交付占用、续借及分管领导补批、实收归还与催还，见[纸质档案借还说明](docs/platform-physical-loans.md)。本地固定预览和本批测试库独立运行，浏览地址及角色说明也在该文档中。

部署验收批次新增本人业务通知、限次投递重试与人工队列，以及本机隔离备份/恢复维护工具，见[通知投递与恢复演练](docs/platform-operations-rehearsal.md)。用户纸质预览保持独立运行，正式通道和真实高可用资源继续按部署条件验收。

目录维护批次新增关键字段更正、完整PDF版本更正、撤下复核、保管到期提醒及分类字段模板，见[目录更正与保管验收说明](docs/platform-catalog-maintenance.md)。仍保留旧版本、在途借还和独立预览；不自动销毁档案。

后续批次接通审批凭据补录与人员交接，见[凭据与交接说明](docs/platform-evidence-personnel.md)。本批新增员工、部门及档案台账预检与整批导入，以及扫描清单、原件交接、专用签名接收、人工质检和返工，见[批量导入与扫描作业](docs/platform-imports-scanning.md)及[本机验收记录](docs/platform-imports-scanning-validation.md)。
