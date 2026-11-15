#Requires -Version 7.0
<#
.SYNOPSIS
恢复本机独立ONLYOFFICE容器，检查校时并应用已验收的固定版本组件补丁；不替换容器或业务库。
.PARAMETER RuntimeRoot
部署时生成的私有运行目录，包含bridge.private.json、office-certs和PID文件。
.PARAMETER Distribution
本机Office专用WSL发行版名称，不能填写日常开发或业务发行版。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RuntimeRoot,
    [string]$Distribution = 'ims-office-20261029',
    [string]$ContainerName = 'ims-onlyoffice-20261029',
    [string]$NodeCommand = 'node'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $RuntimeRoot).ProviderPath
$bridgePath = Join-Path $taskRoot 'bridge.private.json'
$bridge = Get-Content -LiteralPath $bridgePath -Raw | ConvertFrom-Json
$route = & wsl.exe -d $Distribution -u root --exec ip -4 route show default
if ($LASTEXITCODE -ne 0) { throw '无法读取专用WSL网络。' }
$guest = (& wsl.exe -d $Distribution -u root --exec hostname -I).Trim().Split(' ', [StringSplitOptions]::RemoveEmptyEntries)[0]
if ($route -notmatch ('via ' + [regex]::Escape($bridge.hostAddress) + ' ') -or $guest -ne $bridge.guestAddress) {
    throw 'WSL地址已变化。请更新bridge.private.json及容器host.docker.internal映射后再启动；本脚本不会替换容器或数据卷。'
}
$image = & wsl.exe -d $Distribution -u root --exec docker inspect --format '{{.Config.Image}}' $ContainerName
if ($LASTEXITCODE -ne 0 -or $image.Trim() -ne 'onlyoffice/documentserver:10.0.0.1@sha256:ca17889f10b11141cc6441836aebdd2835cf3e1b49681b2fd6e155c94b7850b3') { throw '未找到本批固定镜像容器，请按部署记录核对。' }
# 已复现的双重校时会使原生检查点漏掉已同步修改，恢复服务前先验证40秒时钟连续性。
& (Join-Path $PSScriptRoot 'test-local-office-clock.ps1') -Distribution $Distribution -ReportPath (Join-Path $taskRoot 'office-clock-check.json')
# 仅systemd后台服务不能让本机WSL持续驻留，保留一个可识别的前台等待进程。
$holderPath = Join-Path $taskRoot 'wsl-process.private.json'
$holder = if (Test-Path -LiteralPath $holderPath) { Get-Content -LiteralPath $holderPath -Raw | ConvertFrom-Json } else { $null }
$holding = if ($holder) { Get-CimInstance Win32_Process -Filter "ProcessId = $($holder.pid)" } else { $null }
if (-not $holding -or $holding.CommandLine -notlike "*$Distribution*sleep infinity*") {
    $process = Start-Process -FilePath 'wsl.exe' -ArgumentList @('-d', $Distribution, '-u', 'root', '--exec', 'sleep', 'infinity') -WindowStyle Hidden -PassThru
    @{ distribution = $Distribution; pid = $process.Id } | ConvertTo-Json | Set-Content -LiteralPath $holderPath -Encoding utf8NoBOM
}
$previous = if (Test-Path -LiteralPath $bridge.pidFile) { Get-Content -LiteralPath $bridge.pidFile -Raw | ConvertFrom-Json } else { $null }
$bridging = if ($previous) { Get-CimInstance Win32_Process -Filter "ProcessId = $($previous.pid)" } else { $null }
if (-not $bridging -or $bridging.CommandLine -notlike '*office-network-bridge.cjs*') {
    $script = Join-Path $PSScriptRoot 'office-network-bridge.cjs'
    Start-Process -FilePath $NodeCommand -ArgumentList @('"' + $script + '"', '"' + $bridgePath + '"') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRoot 'bridge.log') -RedirectStandardError (Join-Path $taskRoot 'bridge-error.log') | Out-Null
}
& wsl.exe -d $Distribution -u root --exec docker start $ContainerName | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Office容器启动失败。' }
# 原生未打包源码漏写AMD依赖，已复现Excel/PPT初始化失败；补丁校验固定摘要并重建gzip。
$patchReport = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'patch-local-office-amd.py') -Raw | & wsl.exe -d $Distribution -u root --exec docker exec -i $ContainerName python3 -
if ($LASTEXITCODE -ne 0) { throw 'Office组件依赖修复失败；保持平台检查点关闭并核对固定版本。' }
$patchReport | Set-Content -LiteralPath (Join-Path $taskRoot 'office-amd-patch.json') -Encoding utf8NoBOM
Write-Host 'ONLYOFFICE本机容器已启动，时钟检查通过；平台HTTPS应用和55450隔离数据库分别启动。检查点开关以平台配置及专项验收结果为准。'
