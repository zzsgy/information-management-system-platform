<#
.SYNOPSIS
将完整备份恢复到新空库和全新附件目录，不覆盖旧数据、不自动切换配置。
.PARAMETER BackupFile
明确指定的本地 .imsbackup.zip 完整备份文件。
#>
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$BackupFile)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# 恢复工具按仓库位置查找，不需要 Spring Boot 正在运行。
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
$restoreSource = Join-Path $repositoryRoot 'backend\src\main\java\com\company\ims\operations\OfflineRestore.java' # 纯 JDK 源文件入口。
# RESTORE 前缀避免误读生产服务的数据库目标。
$requiredVariables = @('IMS_RESTORE_DB_URL', 'IMS_RESTORE_DB_USER', 'IMS_RESTORE_DB_PASSWORD', 'IMS_RESTORE_STORAGE_ROOT')
foreach ($variableName in $requiredVariables) {
    $configuredValue = [Environment]::GetEnvironmentVariable($variableName) # 不打印凭据内容。
    if ([string]::IsNullOrWhiteSpace($configuredValue)) { throw "恢复前必须设置：$variableName" }
}
if (-not [IO.Path]::IsPathRooted($env:IMS_RESTORE_STORAGE_ROOT)) { throw 'IMS_RESTORE_STORAGE_ROOT 必须是绝对路径。' }
if (Test-Path -LiteralPath $env:IMS_RESTORE_STORAGE_ROOT) { throw '恢复附件目标已存在，请使用尚不存在的全新目录。' }
if (-not (Test-Path -LiteralPath $restoreSource -PathType Leaf)) { throw '缺少 OfflineRestore.java，请使用完整项目中的恢复入口。' }
# 恢复工具会再次核验包格式、路径、大小与全部哈希。
$backupAbsolutePath = (Resolve-Path -LiteralPath $BackupFile -ErrorAction Stop).ProviderPath
if (-not (Test-Path -LiteralPath $backupAbsolutePath -PathType Leaf)) { throw '备份路径不是普通文件。' }
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command 'java' -ErrorAction Stop).Source } # 当前已安装 JDK。
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw '找不到 JDK 21，无法执行隔离恢复。' }
Write-Host '将验证备份并恢复到新空库及新附件目录；已有服务配置保持不变。'
& $javaExecutable '-Dfile.encoding=UTF-8' '--source' '21' $restoreSource $backupAbsolutePath
$restoreExitCode = $LASTEXITCODE # 非零不得解释为已完成或已自动回滚。
if ($restoreExitCode -ne 0) { Write-Error "隔离恢复未完成，退出码：$restoreExitCode。请检查新目标和恢复日志；不要切换配置。" -ErrorAction Continue }
exit $restoreExitCode
