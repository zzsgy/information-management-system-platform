#Requires -Version 7.0
<#
.SYNOPSIS
由 Windows 任务计划程序运行日常预览组件，不初始化数据库或导入测试资料。
.PARAMETER Component
Database 保持数据库进程，Application 保持网页服务进程；分开托管便于独立发布应用。
.PARAMETER RuntimeRoot
当前日常预览的私有运行目录，凭据只从该目录读取到进程环境。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Database', 'Application', 'Office', 'LanOffice')][string]$Component,
    [Parameter(Mandatory)][string]$RuntimeRoot
)
$ErrorActionPreference = 'Stop'
$runtime = (Resolve-Path -LiteralPath $RuntimeRoot).ProviderPath
$configuration = Get-Content -LiteralPath (Join-Path $runtime 'platform.private.json') -Raw | ConvertFrom-Json
if ($configuration.IMS_PLATFORM_DB_URL -notmatch '^jdbc:postgresql://127\.0\.0\.1:55469/ims_platform_dev_[0-9]+$') {
    throw '本机日常预览库配置不符，拒绝启动其他数据库。'
}
$postgresBin = 'D:/PostgreSQL/pgsql16/bin'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
try {
    if ($Component -eq 'LanOffice') {
        # 独立托管局域网桥，不重启Office容器、WSL或原有回调桥。
        $lanConfigPath = $configuration.IMS_PLATFORM_PREVIEW_LAN_OFFICE_BRIDGE
        if (-not $lanConfigPath) { throw '尚未配置局域网Office桥。' }
        $lanScript = Join-Path $PSScriptRoot 'lan-office-bridge.cjs'
        $lanProcess = Start-Process -FilePath 'node' -ArgumentList @(('"' + $lanScript + '"'), ('"' + $lanConfigPath + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/lan-office-bridge.log" -RedirectStandardError "$runtime/lan-office-bridge-error.log"
        $lanProcess.WaitForExit()
        if ($lanProcess.ExitCode -ne 0) { throw '局域网Office桥退出，等待Windows任务重启。' }
        return
    }
    if ($Component -eq 'Office') {
        & (Join-Path $PSScriptRoot 'start-local-office.ps1') -RuntimeRoot $configuration.IMS_PLATFORM_PREVIEW_OFFICE_RUNTIME
        $bridgeConfig = $configuration.IMS_PLATFORM_PREVIEW_OFFICE_BRIDGE
        $bridge = Get-Content -LiteralPath $bridgeConfig -Raw | ConvertFrom-Json
        $previous = if (Test-Path $bridge.pidFile) { Get-Content $bridge.pidFile -Raw | ConvertFrom-Json } else { $null }
        $process = if ($previous) { Get-CimInstance Win32_Process -Filter "ProcessId=$($previous.pid)" } else { $null }
        if (-not $process -or $process.CommandLine -notlike "*$bridgeConfig*") {
            $bridgeScript = Join-Path $PSScriptRoot 'office-network-bridge.cjs'
            Start-Process -FilePath 'node' -ArgumentList @(('"' + $bridgeScript + '"'), ('"' + $bridgeConfig + '"')) -WindowStyle Hidden -RedirectStandardOutput "$runtime/office-bridge.log" -RedirectStandardError "$runtime/office-bridge-error.log" | Out-Null
        }
        # Office 与回调桥也由系统托管，不能依赖启动它们的临时终端存活。
        while ($true) {
            Start-Sleep -Seconds 30
            $health = Invoke-WebRequest -Uri ($configuration.IMS_PLATFORM_OFFICE_SERVER_ORIGIN + '/healthcheck') -SkipCertificateCheck -TimeoutSec 10
            if ($health.Content.Trim() -ne 'true') { throw 'Office健康检查未通过' }
            $bridgeProcess = Get-Content $bridge.pidFile -Raw | ConvertFrom-Json
            if (-not (Get-Process -Id $bridgeProcess.pid -ErrorAction SilentlyContinue)) { throw 'Office回调桥已退出' }
        }
    }
    if ($Component -eq 'Database') {
        # pg_ctl 沿用原集群和追加日志，异常退出后的恢复交给 PostgreSQL 自身处理。
        $databaseDirectory = Join-Path $runtime 'postgres'
        $starter = Start-Process -FilePath (Join-Path $postgresBin 'pg_ctl.exe') -ArgumentList @(
            '-D', ('"' + $databaseDirectory + '"'), '-l', ('"' + (Join-Path $runtime 'postgres.log') + '"'), '-w', 'start'
        ) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime "database-start-$stamp.log") -RedirectStandardError (Join-Path $runtime "database-start-$stamp-error.log")
        $starter.WaitForExit()
        if ($starter.ExitCode -ne 0) { throw '数据库启动失败，请查看本机数据库日志。' }
        $databasePid = [int](Get-Content -LiteralPath (Join-Path $databaseDirectory 'postmaster.pid') -TotalCount 1)
        @{ pid = $databasePid; startedAt = (Get-Date).ToString('o'); owner = 'WindowsTaskScheduler' } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'database-process.private.json') -Encoding UTF8
        Wait-Process -Id $databasePid
        return
    }

    # 登录触发时两个任务可同时开始，应用等待数据库接受连接后再启动。
    $databaseReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & (Join-Path $postgresBin 'pg_isready.exe') -h 127.0.0.1 -p 55469 -t 1 -q
        if ($LASTEXITCODE -eq 0) { $databaseReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $databaseReady) { throw '数据库在60秒内未就绪，应用未启动。' }
    foreach ($property in $configuration.PSObject.Properties) {
        [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
    }
    $env:IMS_DRIVE_TEST_ROOT = $runtime
    $env:IMS_CLAM_TEST_EXE = 'D:/tmp/ims-platform-batch2-clamav/program/clamav-1.5.4.win.x64/clamscan.exe'
    $classpath = (Get-Content -LiteralPath (Join-Path $runtime 'preview-classpath.txt') -Raw).Trim()
    # 发布流程只切换 classpath，进程记录据此计算版本目录，避免后台任务固定到旧构建。
    $classes = ($classpath -split ';') | Where-Object { $_ -match '[\\/]ims-platform-[^\\/]+-build[\\/]classes$' } | Select-Object -First 1
    if (-not $classes) { throw '日常预览 classpath 中未找到发布构建。' }
    $log = Join-Path $runtime "preview-$stamp.log"
    $errorLog = Join-Path $runtime "preview-$stamp-error.log"
    # 日常预览只允许迁移到已验收的显式版本；其他功能发布带入新SQL时不能顺带升级现有库。
    $approvedSchema = [string]$configuration.IMS_PLATFORM_PREVIEW_APPROVED_SCHEMA_VERSION
    if ($approvedSchema -notmatch '^[0-9]+$') { throw '请先配置已验收的预览数据库版本，拒绝自动升级现有库。' }
    # 缺省继续只监听回环；局域网试用须在私有配置中显式开启，数据库监听不随之改变。
    $bindAddress = if ($configuration.IMS_PLATFORM_BIND_ADDRESS) { [string]$configuration.IMS_PLATFORM_BIND_ADDRESS } else { '127.0.0.1' }
    $parsedAddress = $null
    if (-not [Net.IPAddress]::TryParse($bindAddress, [ref]$parsedAddress)) { throw '网页监听地址必须是有效IP地址。' }
    $arguments = @('-Dfile.encoding=UTF-8', ('-Dserver.address=' + $bindAddress), ('-Dspring.flyway.target=' + $approvedSchema), '-Djavax.net.ssl.trustStore=D:/tmp/ims-platform-office-20261029/office-certs/trust.p12', '-Djavax.net.ssl.trustStorePassword=local-public-trust', '-cp', ('"' + $classpath + '"'), 'com.company.platform.storage.UserPreviewApplication')
    $process = Start-Process -FilePath 'D:/DevTools/jdk-21/jdk-21.0.11+10/bin/java.exe' -ArgumentList $arguments -WorkingDirectory (Resolve-Path (Join-Path $PSScriptRoot '../..')).ProviderPath -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError $errorLog
    @{ pid = $process.Id; startedAt = (Get-Date).ToString('o'); build = (Split-Path $classes -Parent); log = $log; errorLog = $errorLog; owner = 'WindowsTaskScheduler' } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'application-process.private.json') -Encoding UTF8
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw '网页服务异常退出，等待Windows任务重启。' }
} catch {
    # 不输出配置对象，避免私有口令进入任务历史或普通终端。
    $_.Exception.Message | Add-Content -LiteralPath (Join-Path $runtime "$Component-task-error.log") -Encoding UTF8
    exit 1
}
