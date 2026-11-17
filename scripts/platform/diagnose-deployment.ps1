#Requires -Version 5.1
<#
.SYNOPSIS
在部署机对比静态首页、登录会话接口和本机 PostgreSQL。只输出诊断状态，不输出密码或配置内容。
.PARAMETER Url
浏览器正在使用的平台 HTTPS 入口。
.PARAMETER ConfigPath
可选。显式指定部署实际使用的 deployment.properties 或 managed.properties。
#>
param(
    [Parameter(Mandatory)][uri]$Url,
    [string]$ConfigPath
)
$ErrorActionPreference = 'Stop'
if ($Url.Scheme -ne 'https') { throw '请提供 HTTPS 入口。' }
$base = $Url.GetLeftPart([UriPartial]::Authority)
foreach ($path in '/', '/api/v1/auth/me') {
    # 不发送凭据；证书信任另作检查，以便区分 TLS 和应用响应。
    $result = & curl.exe --noproxy '*' -ks --max-time 12 -o NUL -w 'HTTP=%{http_code} TCP=%{time_connect}s TLS=%{time_appconnect}s 首字节=%{time_starttransfer}s 总计=%{time_total}s' ($base + $path)
    Write-Host "$path $result"
}
& curl.exe --noproxy '*' -s --max-time 10 -o NUL ($base + '/')
Write-Host ('证书信任检查退出码 {0}' -f $LASTEXITCODE)

if (!$ConfigPath) {
    $listener = Get-NetTCPConnection -LocalPort $Url.Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        $match = [regex]::Match([string]$process.CommandLine, '--spring\.config\.additional-location=file:(?:"([^"]+)"|([^"]+\.properties))')
        if ($match.Success) {
            $file = $match.Groups[1].Value + $match.Groups[2].Value
            if ([IO.Path]::IsPathRooted($file) -and (Test-Path -LiteralPath $file)) { $ConfigPath = $file }
        }
    }
    if (!$ConfigPath) {
        $ConfigPath = @('deployment.properties','ims-data/managed.properties') |
            Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    }
}
if (!$ConfigPath -or !(Test-Path -LiteralPath $ConfigPath)) {
    Write-Host '未找到实际部署配置。请在部署目录运行，或用 -ConfigPath 指定配置文件。'
    exit 1
}

$config = @{}
foreach ($line in [IO.File]::ReadLines((Resolve-Path -LiteralPath $ConfigPath).Path)) {
    if ($line -match '^\s*(IMS_PLATFORM_DB_URL|IMS_PLATFORM_DB_USER|IMS_PLATFORM_DB_PASSWORD)\s*=(.*)$') {
        $config[$Matches[1]] = $Matches[2].Trim()
    }
}
if ($config.IMS_PLATFORM_DB_URL -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost):(\d+)/(ims_platform_(?:dev|test|trial)[a-z0-9_]*)(?:\?.*)?$') {
    Write-Host '配置未提供受支持的本机数据库地址，跳过数据库探测。'
    exit 1
}
$databaseHost = $Matches[1]
$databasePort = $Matches[2]
$databaseName = $Matches[3]
$reachable = Test-NetConnection -ComputerName $databaseHost -Port $databasePort -InformationLevel Quiet -WarningAction SilentlyContinue
Write-Host ('PostgreSQL 回环端口 {0} 可连接 {1}' -f $databasePort,$reachable)
if (!$reachable) { exit 1 }

$configDirectory = Split-Path -Parent (Resolve-Path -LiteralPath $ConfigPath).Path
$psql = @(
    (Join-Path (Get-Location).Path 'runtime/postgresql/bin/psql.exe')
    (Join-Path $configDirectory 'postgres-runtime/bin/psql.exe')
    (Get-Command psql.exe -ErrorAction SilentlyContinue).Source
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (!$psql) { Write-Host '未找到 PostgreSQL 客户端，端口检查完成。'; exit 1 }

$env:PGPASSWORD = $config.IMS_PLATFORM_DB_PASSWORD
$env:PGCONNECT_TIMEOUT = '4'
$env:PGOPTIONS = '-c statement_timeout=4000'
try {
    $sql = "SELECT pg_is_in_recovery(), to_regclass('ops.audit_event') IS NOT NULL, COALESCE(has_table_privilege(to_regclass('ops.audit_event'), 'INSERT'), false), (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock');"
    $ErrorActionPreference = 'Continue'
    $output = & $psql -h $databaseHost -p $databasePort -U $config.IMS_PLATFORM_DB_USER -d $databaseName -w -At -v ON_ERROR_STOP=1 -c $sql 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exitCode -ne 0) {
        $detail = ($output | Out-String)
        $cause = switch -Regex ($detail) {
            'password authentication failed' { '数据库账号或口令校验失败'; break }
            'no pg_hba.conf entry' { '数据库未允许此连接'; break }
            'timeout expired|statement timeout' { '数据库连接或查询超时'; break }
            'does not exist' { '数据库或角色不存在'; break }
            default { '数据库登录或查询失败' }
        }
        Write-Host "$cause，psql 退出码 $exitCode。"
        exit 1
    }
    $flags = ($output | Select-Object -Last 1) -split '\|'
    Write-Host ('PostgreSQL 查询成功，处于恢复模式 {0}，审计表存在 {1}，审计表写权限 {2}，等待锁的连接数 {3}' -f ($flags[0] -eq 't'),($flags[1] -eq 't'),($flags[2] -eq 't'),$flags[3])
} finally {
    Remove-Item Env:PGPASSWORD,Env:PGCONNECT_TIMEOUT,Env:PGOPTIONS -ErrorAction SilentlyContinue
}
