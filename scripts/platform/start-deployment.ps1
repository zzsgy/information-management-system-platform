#Requires -Version 5.1
<#
.SYNOPSIS
启动 Windows x64 部署包。优先使用原有配置；首次运行可创建独立的 PostgreSQL 与 HTTPS 配置。
.PARAMETER LanAddress
首次部署时指定本机要监听的 IPv4 地址。默认使用带 IPv4 默认网关的网卡地址。
.PARAMETER DataRoot
存放私有配置、日志与随包初始化的数据。默认使用包目录下的 ims-data；存在中文路径时选用系统公共文档下的专用目录。
.PARAMETER Port
首次部署的 HTTPS 端口，默认 18443。再次运行时以已有配置为准。
.PARAMETER DatabasePort
首次部署的独立数据库端口，默认 55443。再次运行时以已有配置为准。
#>
param(
    [ipaddress]$LanAddress,
    [string]$DataRoot,
    [ValidateRange(1,65535)][int]$Port = 18443,
    [ValidateRange(1,65535)][int]$DatabasePort = 55443
)
$ErrorActionPreference = 'Stop'
$jar = Join-Path $PSScriptRoot 'ims-platform-service.jar'
$java = Join-Path $PSScriptRoot 'runtime/java/bin/java.exe'
$keytool = Join-Path $PSScriptRoot 'runtime/java/bin/keytool.exe'
$postgres = Join-Path $PSScriptRoot 'runtime/postgresql'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (!$DataRoot) {
    $DataRoot = Join-Path $PSScriptRoot 'ims-data'
    if ($DataRoot -match '[^\x00-\x7F]') {
        # Windows PostgreSQL 在非 ASCII 安装或数据路径上可能无法完成 initdb。
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $suffix = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($PSScriptRoot + $identity.User.Value))).Replace('-','').Substring(0,16) }
        finally { $sha.Dispose() }
        $DataRoot = Join-Path ([Environment]::GetFolderPath('CommonDocuments')) "BioLinkIMS/$suffix"
    }
}
$data = [IO.Path]::GetFullPath($DataRoot)
$cluster = Join-Path $data 'postgres'
$managed = Join-Path $data 'managed.properties'
$custom = Join-Path $PSScriptRoot 'deployment.properties'
$loopback = '127.0.0.1'
$appLog = Join-Path $data 'application.log'
$appError = Join-Path $data 'application-error.log'
$startedApp = $null

function Get-Setting($config, $name, $defaultValue) {
    # 仅从示例约定的单行配置读取探测所需的地址与端口。应用自身仍负责解析完整配置。
    $match = [regex]::Match($config, "(?m)^\s*$name\s*=\s*([^\r\n]+)")
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return $defaultValue
}

function New-DeploymentSecret {
    $bytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Invoke-DeploymentTool($executable, [string[]]$arguments, $log, $inputText) {
    # Windows PowerShell 5.1 会把本机程序正常写入 stderr 的进度视为错误，按真实退出码判断。
    $ErrorActionPreference = 'Continue'
    if ($inputText) { $inputText | & $executable @arguments *> $log }
    else { & $executable @arguments *> $log }
    return $LASTEXITCODE
}

function Start-ManagedDatabase($portNumber) {
    # pg_ctl 只操作包自建的集群；若端口被其他进程占用，保留原实例。
    $log = Join-Path $data 'postgres.log'
    if ((Invoke-DeploymentTool (Join-Path $pg 'pg_ctl.exe') @('-D',$cluster,'status') (Join-Path $data 'postgres-status.log')) -eq 0) { return }
    if (Get-NetTCPConnection -LocalPort $portNumber -State Listen -ErrorAction SilentlyContinue) {
        throw "随包数据库端口 $portNumber 已被其他程序占用。首次部署可通过 -DatabasePort 指定空闲端口。"
    }
    $starter = Start-Process -FilePath (Join-Path $pg 'pg_ctl.exe') -ArgumentList @(
        '-D', ('"' + $cluster + '"'), '-l', ('"' + $log + '"'), '-w', 'start'
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $data 'postgres-start.log') -RedirectStandardError (Join-Path $data 'postgres-start-error.log')
    if (!$starter.WaitForExit(30000) -or
        (Invoke-DeploymentTool (Join-Path $pg 'pg_isready.exe') @('-h',$loopback,'-p',$portNumber,'-q') (Join-Path $data 'postgres-ready.log')) -ne 0) {
        throw "随包数据库启动失败。请查看 $log。"
    }
}

function Find-DeploymentPort($preferred, $excluded) {
    foreach ($candidate in $preferred..([Math]::Min($preferred + 20,65535))) {
        if ($candidate -ne $excluded -and !(Get-NetTCPConnection -LocalPort $candidate -State Listen -ErrorAction SilentlyContinue)) { return $candidate }
    }
    throw "从端口 $preferred 起没有找到空闲端口，请指定端口后重试。"
}

function Initialize-Deployment($address, $httpsPort, $pgPort) {
    if (Test-Path -LiteralPath $cluster) { throw "检测到尚未完成配置的数据库目录 $cluster。请保留该目录，使用 -DataRoot 指定另一个空目录重试安装，或恢复原配置。" }
    if (Get-NetTCPConnection -LocalPort $httpsPort,$pgPort -State Listen -ErrorAction SilentlyContinue) { throw '部署端口已被占用，请使用 -Port 或 -DatabasePort 指定空闲端口。' }
    $ownerPassword = New-DeploymentSecret
    $databasePassword = New-DeploymentSecret
    $tlsKeyPassword = New-DeploymentSecret
    $adminPassword = New-DeploymentSecret
    $passwordFile = Join-Path $data 'postgres-bootstrap.secret'
    $certificate = Join-Path $data ('ims-' + [IO.Path]::GetRandomFileName() + '.p12')
    $publicCertificate = Join-Path $data 'ims-lan.cer'
    $env:IMS_SETUP_CERT_PASSWORD = $tlsKeyPassword
    try {
        $san = 'SAN=ip:127.0.0.1,dns:localhost'
        if ($address -ne $loopback) { $san += ",ip:$address" }
        $certificateLog = Join-Path $data 'certificate.log'
        $keyArgs = @('-alias','ims','-keystore',$certificate,'-storepass:env','IMS_SETUP_CERT_PASSWORD')
        if ((Invoke-DeploymentTool $keytool (@('-genkeypair','-keyalg','RSA','-keysize','2048','-storetype','PKCS12','-validity','365','-dname',"CN=$address",'-ext',$san) + $keyArgs) $certificateLog) -ne 0) { throw "HTTPS 证书生成失败。请查看 $certificateLog。" }
        if ((Invoke-DeploymentTool $keytool (@('-exportcert','-file',$publicCertificate) + $keyArgs) (Join-Path $data 'certificate-export.log')) -ne 0) { throw '无法导出供浏览器信任的公开证书。' }
        [IO.File]::WriteAllText($passwordFile,$ownerPassword)
        $initLog = Join-Path $data 'postgres-init.log'
        if ((Invoke-DeploymentTool (Join-Path $pg 'initdb.exe') @('-D',$cluster,'-U','ims_platform_owner','--auth=scram-sha-256','--encoding=UTF8','--locale=C',"--pwfile=$passwordFile") $initLog) -ne 0) { throw "随包数据库初始化失败。请查看 $initLog。" }
        Add-Content -LiteralPath (Join-Path $cluster 'postgresql.conf') -Value "`nlisten_addresses = '127.0.0.1'`nport = $pgPort`ntimezone = 'UTC'" -Encoding ascii
        Start-ManagedDatabase $pgPort
        $env:PGPASSWORD = $ownerPassword
        # 初始数据库独立且为空；应用连接仍使用项目规定的回环地址与库名前缀。
        $databaseSql = @"
CREATE ROLE ims_platform_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$databasePassword';
CREATE DATABASE ims_platform_trial OWNER ims_platform_app;
"@
        $createLog = Join-Path $data 'postgres-create.log'
        if ((Invoke-DeploymentTool (Join-Path $pg 'psql.exe') @('-h',$loopback,'-p',$pgPort,'-U','ims_platform_owner','-d','postgres','-v','ON_ERROR_STOP=1') $createLog $databaseSql) -ne 0) { throw "创建平台空库失败。请查看 $createLog。" }
        $config = @(
            'IMS_PLATFORM_ISOLATED=true'
            "IMS_PLATFORM_BIND_ADDRESS=$address"
            "IMS_PLATFORM_PORT=$httpsPort"
            "IMS_PLATFORM_PUBLIC_URL=https://${address}:$httpsPort/"
            "IMS_PLATFORM_DB_URL=jdbc:postgresql://${loopback}:$pgPort/ims_platform_trial"
            'IMS_PLATFORM_DB_USER=ims_platform_app'
            "IMS_PLATFORM_DB_PASSWORD=$databasePassword"
            "IMS_PLATFORM_TLS_KEYSTORE=$certificate"
            "IMS_PLATFORM_TLS_PASSWORD=$tlsKeyPassword"
            "IMS_PLATFORM_LEDGER_ROOT=$data/security-ledger"
            "IMS_PLATFORM_MAILBOX_ROOT=$data/mailbox"
            'IMS_PLATFORM_ADMIN_NO=admin'
            'IMS_PLATFORM_ADMIN_EMAIL=platform.dev@biolink.com'
            "IMS_PLATFORM_ADMIN_PASSWORD=$adminPassword"
        )
        # Java properties 中反斜杠用于转义。生成路径统一用正斜杠。
        [IO.File]::WriteAllLines($managed, [string[]]($config | ForEach-Object { $_.Replace([char]92,[char]47) }))
    } finally {
        Remove-Item Env:IMS_SETUP_CERT_PASSWORD,Env:PGPASSWORD -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile -Force }
    }
}

try {
    if (!(Test-Path -LiteralPath $jar) -or !(Test-Path -LiteralPath $java)) { throw '部署包缺少应用 JAR 或内置 Java 21，请完整解压。' }
    if (!(Get-Command curl.exe -ErrorAction SilentlyContinue)) { throw '缺少 Windows curl.exe，无法确认 HTTPS 就绪。' }
    & $java --version
    if ($LASTEXITCODE -ne 0) { throw '内置 Java 21 无法启动。请核对 Windows x64 架构及运行库。' }
    New-Item -ItemType Directory -Path $data -Force | Out-Null
    # 凭据与日志只保存在启动账号能读取的目录中。
    $directory = [IO.DirectoryInfo]::new($data)
    $acl = $directory.GetAccessControl()
    $acl.SetAccessRuleProtection($true,$false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity.User,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
    $acl.SetAccessRule($rule)
    $directory.SetAccessControl($acl)

    $configPath = $custom
    $isManaged = !(Test-Path -LiteralPath $custom)
    if ($isManaged) {
        if ($data -match '[^\x00-\x7F]') { throw '随包数据库需要英文数据路径。请使用 -DataRoot 指定一个英文目录。' }
        if ($postgres -match '[^\x00-\x7F]') {
            $bundledPostgres = $postgres
            $postgres = Join-Path $data 'postgres-runtime'
            if (!(Test-Path -LiteralPath $postgres)) { Copy-Item -LiteralPath $bundledPostgres -Destination $postgres -Recurse }
        }
        $pg = Join-Path $postgres 'bin'
        if (!(Test-Path -LiteralPath (Join-Path $pg 'initdb.exe'))) { throw '缺少随包 PostgreSQL，请完整解压。' }
        $configPath = $managed
        if (!(Test-Path -LiteralPath $managed)) {
            if (!$PSBoundParameters.ContainsKey('Port')) { $Port = Find-DeploymentPort $Port $DatabasePort }
            if (!$PSBoundParameters.ContainsKey('DatabasePort')) { $DatabasePort = Find-DeploymentPort $DatabasePort $Port }
            if (!$LanAddress) {
                $LanAddress = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
                    ForEach-Object { $_.IPv4Address.IPAddress } | Select-Object -First 1
            }
            if (!$LanAddress) { $LanAddress = [ipaddress]$loopback }
            if ($LanAddress.AddressFamily -ne 'InterNetwork' -or !(Get-NetIPAddress -IPAddress $LanAddress.IPAddressToString -AddressFamily IPv4 -ErrorAction SilentlyContinue)) { throw '-LanAddress 必须是本机网卡的实际 IPv4 地址。' }
            Initialize-Deployment $LanAddress.IPAddressToString $Port $DatabasePort
        }
    }
    $content = [IO.File]::ReadAllText($configPath)
    $appPort = [int](Get-Setting $content 'IMS_PLATFORM_PORT' 18443)
    $bindAddress = Get-Setting $content 'IMS_PLATFORM_BIND_ADDRESS' $loopback
    $checkAddress = if ($bindAddress -in @('0.0.0.0','localhost')) { $loopback } else { $bindAddress }
    $publicUrl = Get-Setting $content 'IMS_PLATFORM_PUBLIC_URL' "https://${checkAddress}:$appPort/"
    $probeUrl = "https://${checkAddress}:$appPort/"
    if ($isManaged) {
        $databaseUrl = Get-Setting $content 'IMS_PLATFORM_DB_URL' ''
        $managedDatabasePort = ([uri]$databaseUrl.Substring(5)).Port
        Start-ManagedDatabase $managedDatabasePort
    }
    $listener = Get-NetTCPConnection -LocalPort $appPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $running = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        $commandLine = ([string]$running.CommandLine).Replace([char]92,[char]47)
        if (!$commandLine.Contains($jar.Replace([char]92,[char]47)) -or !$commandLine.Contains($configPath.Replace([char]92,[char]47))) { throw "HTTPS 端口 $appPort 已被其他程序占用。" }
    } else {
        $location = 'file:' + $configPath.Replace([char]92,[char]47)
        $javaArguments = @(
            '-jar', ('"' + $jar + '"'), ('"--spring.config.additional-location=' + $location + '"')
        )
        if ((Get-Setting $content 'IMS_PLATFORM_ADMIN_EMAIL' '') -eq 'ims-admin@example.internal') {
            # 兼容旧部署示例中的占位邮箱，保留原配置文件与数据库。
            $javaArguments += '--platform.bootstrap.email=platform.dev@biolink.com'
        }
        $startedApp = Start-Process -FilePath $java -ArgumentList $javaArguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $appLog -RedirectStandardError $appError
    }
    $ready = $false
    if ($isManaged) { $env:PGPASSWORD = Get-Setting $content 'IMS_PLATFORM_DB_PASSWORD' '' }
    try {
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if ($startedApp -and $startedApp.HasExited) { break }
            # 只对启动就绪探测忽略证书信任，浏览器仍应验证证书。
            $statusCode = & curl.exe --noproxy '*' -ks --max-time 2 -o NUL -w '%{http_code}' $probeUrl
            if ($statusCode -eq '200') {
                # 匿名会话应由应用正常拒绝。只有静态首页返回 200，无法证明数据库和审计可用。
                $apiStatus = & curl.exe --noproxy '*' -ks --max-time 7 -o NUL -w '%{http_code}' ($probeUrl + 'api/v1/auth/me')
                if ($apiStatus -eq '503') { throw "网页已打开，但平台接口返回保护检查 503。请先检查 PostgreSQL 和应用日志 $appLog。" }
                if ($apiStatus -ne '401') { Start-Sleep -Seconds 1; continue }
                $databaseReady = !$isManaged
                if ($isManaged) {
                    $readyLog = Join-Path $data 'database-ready.log'
                    $exitCode = Invoke-DeploymentTool (Join-Path $pg 'psql.exe') @('-h',$loopback,'-p',$managedDatabasePort,'-U','ims_platform_app','-d','ims_platform_trial','-At','-c','SELECT EXISTS (SELECT FROM iam.user_account)') $readyLog
                    $databaseReady = $exitCode -eq 0 -and (Get-Content -LiteralPath $readyLog) -contains 't'
                }
                if ($databaseReady) {
                    $ready = $true
                    break
                }
            }
            Start-Sleep -Seconds 1
        }
    } finally {
        if ($isManaged) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    }
    if (!$ready) { throw "平台启动未通过 HTTPS 检查。请查看 $appLog 和 $appError；已有配置请核对 PostgreSQL 与证书。" }
    Write-Host "平台已就绪：$publicUrl"
    if ($isManaged) {
        Write-Host "初始账号 admin，密码见私有文件 $managed 中的 IMS_PLATFORM_ADMIN_PASSWORD。"
        Write-Host "供访问电脑信任的公开证书：$(Join-Path $data 'ims-lan.cer')"
        if ($bindAddress -ne $loopback) {
            $principal = [Security.Principal.WindowsPrincipal]::new($identity)
            $ruleName = "BioLink IMS HTTPS $appPort"
            if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
                try {
                    if (!(Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue)) {
                        New-NetFirewallRule -Name $ruleName -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $bindAddress -LocalPort $appPort -RemoteAddress LocalSubnet -Profile Domain,Private | Out-Null
                    }
                } catch { Write-Warning "无法配置防火墙，请在目标电脑允许内网访问 TCP $appPort。" }
            } else { Write-Host "若其他电脑无法访问，请在防火墙允许内网访问 TCP $appPort，或以管理员身份重新运行本命令应用内网规则。" }
        }
    }
} catch {
    if ($startedApp -and !$startedApp.HasExited) { Stop-Process -Id $startedApp.Id -ErrorAction SilentlyContinue }
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
