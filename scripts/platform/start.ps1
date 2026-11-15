#Requires -Version 7.0
<#
.SYNOPSIS
从私有配置文件启动新平台；不读取旧系统 IMS_DB_* 配置，不结束其他程序。
.PARAMETER ConfigPath
仅当前 Windows 用户可读的独立开发配置 JSON 绝对路径；禁止提交到仓库。
#>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ConfigPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
$configuration = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json -AsHashtable
# 只接受新平台前缀，错误文件不能改写 HOME、PATH 或旧系统配置。
foreach ($entry in $configuration.GetEnumerator()) {
    if (-not $entry.Key.StartsWith('IMS_PLATFORM_')) { throw '配置包含非平台字段。' }
    [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
}
if ($env:IMS_PLATFORM_ISOLATED -ne 'true') { throw '配置未明确声明独立隔离环境。' }
$javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jarPath = Join-Path $repositoryRoot 'backend\target-platform\archive-service-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先执行 scripts/platform/build.ps1。' }
Write-Host '启动独立平台 HTTPS 服务；日志不输出初始化口令。Ctrl+C 停止当前应用。'
& $javaExecutable '-Dfile.encoding=UTF-8' '-jar' $jarPath
exit $LASTEXITCODE
