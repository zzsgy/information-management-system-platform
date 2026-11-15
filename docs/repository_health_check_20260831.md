# 档案管理系统仓库健康检查报告

**检查时间**：2026-08-31  
**检查范围**：`C:\Apython\ArchiveSystem`（服务端 + 客户端 + 安装包）  
**当前版本**：v1.15（commit `0b674c1`，已推送 Gitee main）

---

## 一、Git 工作树状态

| 检查项 | 结果 | 说明 |
|---|---|---|
| 未提交修改 | ✅ 无 | `git status --short` 为空 |
| 当前分支 | ✅ main | 无未合并分支 |
| 最新提交 | ✅ `0b674c1` | v1.15 已推送 Gitee |
| 远程地址 | ⚠️ 含明文 token | `origin` URL 包含 Gitee OAuth token（与历史配置一致） |
| tracked 文件数 | 145 个 | 代码、资源、模板均已入库 |

---

## 二、.gitignore 与忽略项检查

`.gitignore` 当前规则覆盖：
- Python：`__pycache__/`、`*.py[cod]`、`.venv/`、`.env`
- IDE：`.vscode/`
- 运行时：`logs/`、`*.log`
- 生成产物：`build/`、`dist/`、`*.spec`
- 数据：`backups/`、`archive_files/`、`server/storage/`
- 安装包：`/installer/*`（仅保留 `server_release/`、`client_release/`、`*.iss`）
- 口令：`*.pw.txt`、`pg_pw.txt`

### 发现的问题

| 序号 | 文件/模式 | 当前状态 | 风险 | 建议 |
|---|---|---|---|---|
| 1 | `build_*.log`（根目录 6 个，共约 235KB） | 未跟踪、未忽略 | 占用仓库目录、可能泄露构建信息 | 删除或加入 `.gitignore` |
| 2 | `server/ArchiveServer.spec`、`client/ArchiveClient.spec` | 被 `.gitignore` 排除，未入库 | PyInstaller 规格文件含打包配置，丢失后需手动重建 | 评估是否应 `git add -f` 强制跟踪 |
| 3 | 部分 `__pycache__` 目录残留 | 已忽略 | 无提交风险，但建议清理保持整洁 | 定期清理即可 |

### 已确认正常

- `server/server.env`、`client/client.env` 已跟踪（安装包模板）。
- `installer/vc_redist.x64.exe` 被正确忽略（已随 v1.15 安装包分发，无需进仓库）。
- 安装产物 `installer/master_release/*.exe` 被正确忽略。

---

## 三、安装包与构建产物

### 安装包清单（master_release）

| 版本 | 文件名 | 大小 | 时间 |
|---|---|---|---|
| v1.06 ~ v1.14 | 历史包 | 51~52MB | 2026-08-28 ~ 2026-08-31 |
| v1.15 | `档案管理系统1.15-20260831.exe` | **52MB** | 2026-08-31 14:26 |

### 构建目录占用

| 路径 | 大小 | 说明 |
|---|---|---|
| `server/dist/ArchiveServer` | 34MB | 当前最新服务端产物 |
| `client/dist/ArchiveClient` | 121MB | 当前最新客户端产物 |
| `server/build/` | 32MB | PyInstaller 中间文件 |
| `client/build/` | 15MB | PyInstaller 中间文件 |
| `<workspace>/dist/` | 155MB | 本次 PyInstaller 误落到工作区的产物（已复制到正确位置） |
| `<workspace>/build/` | 46MB | 本次 PyInstaller 误落到工作区的中间文件 |

> 注意：PyInstaller 本次因当前工作目录为 `C:\Users\Administrator\WorkBuddy\Records Management System`，产物先落到该目录的 `dist/` 下，已手动复制到 `server/dist/` 与 `client/dist/`。建议后续打包脚本显式 `cd` 到 `server`/`client` 目录再执行 PyInstaller。

---

## 四、安全扫描

### 扫描范围

对仓库中所有 `*.py` 文件扫描关键词：`password`、`secret`、`token`、`DATABASE_URL`、`postgres://` 等。

### 发现

| 级别 | 位置 | 内容 | 说明 |
|---|---|---|---|
| 🔴 中风险 | `server/app/db/seed.py:97` | `DEFAULT_ADMIN_PASSWORD = "Admin@123456"` | 默认管理员密码硬编码在源码中 |
| 🟡 低风险 | `server/app/core/config.py:48` | `JWT_SECRET: str = "CHANGE_ME_SECRET"` | JWT 默认密钥为弱占位符（注释已提示生产必须修改） |
| 🟢 可接受 | `server/server.env` | `JWT_SECRET=请修改为随机强密钥` | 模板使用中文占位符，安装后需用户填写 |
| 🟢 可接受 | `.git/config` remote URL | 含 Gitee OAuth token | 与历史凭据配置一致，属已知风险 |

### 未发现问题

- 未在源码中硬编码数据库连接口令。
- 未在源码中硬编码 API token/私钥。
- `.env`、`.venv`、`pg_pw.txt` 等敏感文件已被正确忽略。

---

## 五、建议的收尾动作

### 推荐立即执行（低风险、提升整洁度）

1. **删除根目录遗留的 `build_*.log`**
   ```bash
   rm C:\Apython\ArchiveSystem\build_*.log
   ```

2. **在 `.gitignore` 中加入 `build_*.log`**
   防止后续打包日志再次进入工作树。

3. **清理 PyInstaller 误落到工作区的产物**
   ```powershell
   Remove-Item -Recurse -Force "C:\Users\Administrator\WorkBuddy\Records Management System\dist"
   Remove-Item -Recurse -Force "C:\Users\Administrator\WorkBuddy\Records Management System\build"
   ```
   这些目录已被 `.gitignore` 排除，删除不影响仓库，可释放约 **200MB** 磁盘。

### 推荐评估后执行（需你确认）

4. **强制跟踪 PyInstaller `.spec` 文件**
   ```bash
   git add -f server/ArchiveServer.spec client/ArchiveClient.spec
   git commit -m "chore: 跟踪 PyInstaller spec 文件"
   ```
   这些文件包含打包配置（入口、隐藏导入、输出选项），丢失后重建较麻烦。

5. **提升默认密钥安全性**
   - 将 `seed.py` 中的 `DEFAULT_ADMIN_PASSWORD` 改为从环境变量读取，例如：
     ```python
     DEFAULT_ADMIN_PASSWORD = os.getenv("DEFAULT_ADMIN_PASSWORD", "Admin@123456")
     ```
   - 将 `config.py` 中的 `JWT_SECRET` 默认值改为空字符串或随机生成，强制要求环境变量配置。
   > ⚠️ 执行此项前请确认你当前登录用的就是 `Admin@123456`，否则可能锁死管理员账户。

---

## 六、总体结论

- 仓库当前状态**干净、可构建、可推送**。
- v1.15 安装包已成功生成并通过源码级/打包级冒烟测试。
- 主要待优化点：**硬编码默认口令**、**误落工作区的构建产物**、**PyInstaller spec 是否入库**。
- 未发现会导致仓库污染或构建失败的严重问题。
