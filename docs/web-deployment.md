# Web档案管理部署与开发

主入口已切换为浏览器网页，覆盖当前档案模块的登录、工作概览、台账、附件版本、共享、CSV导入导出、借阅、销毁、异常、审批、规则、公告、组织、基础配置、日志、备份校验。原`client/`和`/web/`轻量只读页面保留兼容，完整功能请使用服务根地址`/`。

## 构建和访问

构建电脑需要Node.js、npm、JDK与Maven。本次验证使用Node 24.19.0、npm 11.17.0；依赖锁定在`web/package-lock.json`。Java编译目标仍为21。部署服务器只需要Java、PostgreSQL及备份工具，不需要Node或Python。

Windows在仓库根目录执行：

```powershell
# 从锁文件安装网页依赖，执行前后端测试并打包。
./scripts/java/build.ps1
# 数据库、目录和初始管理员的环境变量按 java-deployment.md 设置。
./scripts/java/start.ps1 -InitializeAdmin
```

Linux对应执行`bash scripts/java/build.sh`和`bash scripts/java/start.sh --initialize-admin`。首次初始化后移除管理员初始化密码。以后正常启动无需初始化参数。默认产物`backend/target/archive-service-0.1.0.jar`包含全部网页资源。不要只运行Maven就宣称网页已更新：须先完成`web`的构建。

本机使用默认设置时访问`http://127.0.0.1:8000/`。允许公司内网访问时，将`IMS_HOST`设置为服务器内网地址或`0.0.0.0`，按公司网络规则放行应用端口。员工只需打开`http://服务器内网地址:8000/`。正式内网部署应在反向代理配置HTTPS，数据库端口不面向客户端电脑开放。统一反向代理`/`、`/assets/`和`/api/`，当前构建使用根路径，不能只代理`/api/`。

Web采用同源请求，无需开放CORS。登录令牌放在当前页签的sessionStorage，刷新时向服务端验证；退出、改密、禁用账号使令牌失效。所有权限最终由Java接口检查。浏览器无法替代密码管理或公司身份治理。

## 前端开发

```powershell
# 在 web 目录运行；开发代理指向本机Java服务8000端口。
npm ci
npm run dev
```

开发地址为`http://127.0.0.1:5173/`。生产环境不使用Vite开发服务器。需要调整本机后端端口时修改`vite.config.ts`的开发代理，生产请求地址仍为同源`/api`。

配置说明：`package.json`中`private`防止误发布npm包，`scripts`提供开发、检查、打包、单元测试和浏览器验收入口；`dependencies`为浏览器运行库，`devDependencies`为构建及测试工具。JSON清单不能合法加入注释，以上说明代替清单内注释。`tsconfig.json`使用JSONC注释说明编译选项，`vite.config.ts`和测试配置使用中文注释。

## 验证与数据边界

`npm test`仅运行不联网单元测试；`npm run build`包含严格类型检查。浏览器测试必须先启动连接**独立新库**的Java程序，限定`http://127.0.0.1:18091`，设置`IMS_E2E_ALLOW_WRITES=yes`以及测试管理员密码`IMS_E2E_PASSWORD`，再运行`npm run test:e2e`。测试会创建用户、角色、部门、档案和业务单，不能使用业务库。Edge需安装；测试不上传记录，关闭包含认证请求的追踪文件。

如Windows旧构建文件被占用，可使用Maven参数`-Dims.build.directory=新构建目录绝对路径`输出隔离产物，并在启动脚本传`-JarPath`（Linux为`--jar`）；不要强行覆盖正在运行的程序。备份恢复流程继续使用原Java运维说明，网页“恢复前校验”只检查备份包，实际恢复仍到新库和新附件目录离线完成。

前端采用Vue 3组合式API、TypeScript、Vite和Element Plus；参考[Vue官方快速开始](https://vuejs.org/guide/quick-start.html)、[Vite官方说明](https://vite.dev/guide/)和[Element Plus组件文档](https://element-plus.org/en-US/component/overview.html)。本轮未扩展公司门户、公盘和新的档案业务流程，也未开展100人并发压测。
