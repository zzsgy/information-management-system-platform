#Requires -Version 7.0
<#
.SYNOPSIS
创建全新的 PostgreSQL 集群、TLS 证书和私有开发配置；不复用任何已有数据库目录。
.PARAMETER Root
必须尚不存在的英文绝对目录，作为独立开发环境根目录。
.PARAMETER PostgresBin
现有 PostgreSQL bin 路径；本脚本不安装软件、不修改系统服务。
.PARAMETER DatabasePort
独立数据库端口，缺省 55437；必须空闲，不复用旧服务。
.PARAMETER ApplicationPort
独立 HTTPS 端口，缺省 18443；只监听本机。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][string]$PostgresBin,
    [int]$DatabasePort = 55437,
    [int]$ApplicationPort = 18443
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not [IO.Path]::IsPathRooted($Root) -or $Root -match '[^\x00-\x7F]' -or (Test-Path -LiteralPath $Root)) { throw 'Root 必须是尚不存在的英文绝对目录。' }
if (Get-NetTCPConnection -LocalPort $DatabasePort,$ApplicationPort -State Listen -ErrorAction SilentlyContinue) { throw '独立端口已占用，请改用空闲端口。' }
$environmentRoot = [IO.Path]::GetFullPath($Root)
New-Item -ItemType Directory -Path $environmentRoot | Out-Null
# 隔离根目录关闭继承，只允许当前用户，子目录继承相同保护。
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$acl = Get-Acl -LiteralPath $environmentRoot
$acl.SetAccessRuleProtection($true,$false)
$rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $environmentRoot -AclObject $acl
function New-Secret { return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
# 数据库和 TLS 随机口令只写入私有目录与进程环境，启动配置不提交到仓库。
$ownerPassword = New-Secret
$applicationPassword = New-Secret
$tlsPassword = New-Secret
# 本机空库的默认管理员密码。正式接入前可在私有配置中覆盖。
$adminPassword = '123456'
$passwordPath = Join-Path $environmentRoot 'bootstrap-db.secret'
[IO.File]::WriteAllText($passwordPath,$ownerPassword,[Text.UTF8Encoding]::new($false))
$databaseDirectory = Join-Path $environmentRoot 'postgres'
& (Join-Path $PostgresBin 'initdb.exe') -D $databaseDirectory -U platform_owner --auth=scram-sha-256 --encoding=UTF8 --locale=C --pwfile=$passwordPath *> (Join-Path $environmentRoot 'initdb.log')
if ($LASTEXITCODE -ne 0) { throw '新集群初始化失败；详见私有目录中的 initdb.log。' }
Add-Content -LiteralPath (Join-Path $databaseDirectory 'postgresql.conf') -Value "`nlisten_addresses = '127.0.0.1'`nport = $DatabasePort`nmax_connections = 40`nshared_buffers = '128MB'`ntimezone = 'UTC'"
# 独立隐藏进程隔离标准句柄，避免 PostgreSQL 子进程继承 PowerShell 管道而令脚本一直等待。
$postgresStart = Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -ArgumentList @('-D',('"'+$databaseDirectory+'"'),'-l',('"'+(Join-Path $environmentRoot 'postgres.log')+'"'),'-w','start') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $environmentRoot 'start.log') -RedirectStandardError (Join-Path $environmentRoot 'start-error.log')
# Start-Process -Wait 会等待整个进程树；这里只等待 pg_ctl 本身，数据库子进程应继续运行。
if (-not $postgresStart.WaitForExit(30000)) { throw '等待新集群启动超时。' }
if ($postgresStart.ExitCode -ne 0) { throw '新集群启动失败。' }
$env:PGPASSWORD = $ownerPassword
$databaseName = 'ims_platform_dev_' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
# SQL 文件只在受限目录中存在；生成的 Base64 密码不含 SQL 引号。
$sqlPath = Join-Path $environmentRoot 'create-database.sql'
[IO.File]::WriteAllText($sqlPath,"CREATE ROLE platform_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$applicationPassword';`nCREATE DATABASE $databaseName OWNER platform_app;",[Text.UTF8Encoding]::new($false))
& (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $DatabasePort -U platform_owner -d postgres -v ON_ERROR_STOP=1 -f $sqlPath | Out-Null
if ($LASTEXITCODE -ne 0) { throw '独立数据库创建失败。' }
# 仅删除本次生成的两个秘密中间文件，不做目录递归删除。
Remove-Item -LiteralPath $passwordPath,$sqlPath
Remove-Item Env:PGPASSWORD
$keyStore = Join-Path $environmentRoot 'localhost.p12'
$env:IMS_PLATFORM_TLS_PASSWORD = $tlsPassword
& (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -alias platform-local -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore $keyStore -storepass:env IMS_PLATFORM_TLS_PASSWORD -validity 365 -dname 'CN=localhost' -ext 'SAN=ip:127.0.0.1,dns:localhost' *> (Join-Path $environmentRoot 'certificate.log')
if ($LASTEXITCODE -ne 0) { throw '本机证书创建失败。' }
$configuration = [ordered]@{
    IMS_PLATFORM_DB_URL = "jdbc:postgresql://127.0.0.1:$DatabasePort/$databaseName"
    IMS_PLATFORM_DB_USER = 'platform_app'; IMS_PLATFORM_DB_PASSWORD = $applicationPassword
    IMS_PLATFORM_ISOLATED = 'true'; IMS_PLATFORM_PORT = "$ApplicationPort"
    IMS_PLATFORM_TLS_KEYSTORE = $keyStore; IMS_PLATFORM_TLS_PASSWORD = $tlsPassword
    IMS_PLATFORM_LEDGER_ROOT = (Join-Path $environmentRoot 'security-ledger')
    IMS_PLATFORM_MAILBOX_ROOT = (Join-Path $environmentRoot 'development-mailbox')
    IMS_PLATFORM_BIND_ADDRESS = '127.0.0.1'
    IMS_PLATFORM_ADMIN_NO = 'admin'; IMS_PLATFORM_ADMIN_NAME = '平台管理员'
    IMS_PLATFORM_ADMIN_EMAIL = 'platform.dev@biolink.com'; IMS_PLATFORM_ADMIN_PASSWORD = $adminPassword
}
$configPath = Join-Path $environmentRoot 'platform.private.json'
[IO.File]::WriteAllText($configPath,($configuration | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Host "隔离开发环境已创建。配置文件：$configPath"
Write-Host "仅本机 PostgreSQL 端口：$DatabasePort；应用 HTTPS 端口：$ApplicationPort。"
