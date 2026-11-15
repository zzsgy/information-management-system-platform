<#
.SYNOPSIS
以前台进程启动 Java 档案服务，不结束其他程序。
.PARAMETER JarPath
可执行 JAR 路径；为空时按当前 backend/pom.xml 定位。
.PARAMETER InitializeAdmin
首次空库初始化时强制检查显式初始密码，不生成默认口令。
#>
[CmdletBinding()]
param([string]$JarPath, [switch]$InitializeAdmin)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# 程序根目录由脚本位置决定。
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
# 数据库和持久化目标必须显式配置，避免意外写入默认工作目录。
$requiredVariables = @('IMS_DB_URL', 'IMS_DB_USER', 'IMS_DB_PASSWORD', 'IMS_STORAGE_ROOT', 'IMS_BACKUP_ROOT')
foreach ($variableName in $requiredVariables) {
    $configuredValue = [Environment]::GetEnvironmentVariable($variableName) # 只检查是否为空，不打印口令。
    if ([string]::IsNullOrWhiteSpace($configuredValue)) { throw "请先设置环境变量：$variableName" }
}
foreach ($variableName in @('IMS_STORAGE_ROOT', 'IMS_BACKUP_ROOT')) {
    $configuredDirectory = [Environment]::GetEnvironmentVariable($variableName) # 升级程序时持久化路径应保持稳定。
    if (-not [IO.Path]::IsPathRooted($configuredDirectory)) { throw "$variableName 必须是绝对路径。" }
}
if (-not $env:IMS_DB_URL.StartsWith('jdbc:postgresql://')) { throw 'IMS_DB_URL 必须是 PostgreSQL JDBC 地址。' }
# 仅空库首次创建管理员时使用；已有账号不会被重置。
$initialPassword = $env:IMS_BOOTSTRAP_ADMIN_PASSWORD
if ($InitializeAdmin -and [string]::IsNullOrWhiteSpace($initialPassword)) { throw '首次初始化必须显式设置 IMS_BOOTSTRAP_ADMIN_PASSWORD。' }
if (-not [string]::IsNullOrEmpty($initialPassword) -and ($initialPassword.Length -lt 12 -or $initialPassword.Length -gt 128)) {
    throw '初始管理员密码长度必须是 12 至 128 个字符。'
}
# 使用显式 JAVA_HOME 或 PATH，不自动安装运行时。
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command 'java' -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw '找不到 Java，请安装 JDK 21 并设置 JAVA_HOME。' }
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    # 精确读取当前版本，避免误选 target 中遗留的旧产物。
    [xml]$projectXml = Get-Content -LiteralPath (Join-Path $repositoryRoot 'backend\pom.xml') -Raw -Encoding UTF8
    $artifactFilename = "$($projectXml.project.artifactId)-$($projectXml.project.version).jar" # 当前项目产物名。
    $JarPath = Join-Path $repositoryRoot "backend\target\$artifactFilename"
}
# 要求产物真实存在，构建失败时不会尝试运行其他安装包。
$jarAbsolutePath = (Resolve-Path -LiteralPath $JarPath -ErrorAction Stop).ProviderPath
if (-not (Test-Path -LiteralPath $jarAbsolutePath -PathType Leaf)) { throw '指定 JAR 不是普通文件。' }
Push-Location -LiteralPath $repositoryRoot
try {
    Write-Host '正在启动 Java 档案服务；请以启动日志及 /health 检查为准。Ctrl+C 停止当前服务。'
    & $javaExecutable '-Dfile.encoding=UTF-8' '-jar' $jarAbsolutePath
    $serviceExitCode = $LASTEXITCODE # 保留实际 Java 进程退出码。
}
finally { Pop-Location }
exit $serviceExitCode
