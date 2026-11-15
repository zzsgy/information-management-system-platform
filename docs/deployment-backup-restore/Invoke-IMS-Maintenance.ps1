#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Check','Backup','RestoreVerify')][string]$Mode,
    [Parameter(Mandatory)][string]$Repo,
    [Parameter(Mandatory)][string]$Config,
    [string]$OutputRoot,
    [string]$Bundle,
    [int]$RestoreAppPort
)

$ErrorActionPreference = 'Stop'
$tool = (Resolve-Path -LiteralPath (Join-Path $Repo 'scripts/platform/maintenance.cjs')).ProviderPath
$configPath = (Resolve-Path -LiteralPath $Config).ProviderPath
$settings = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if (-not $settings.IMS_PLATFORM_DB_PASSWORD -or $settings.IMS_PLATFORM_DB_PASSWORD -eq 'REPLACE_SOURCE_PASSWORD') {
    throw '请填写源数据库密码。'
}
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source

if ($Mode -eq 'Check') {
    & $node $tool check --config $configPath
    if ($LASTEXITCODE -ne 0) { throw '维护配置检查失败。' }
    return
}

if (-not $OutputRoot -or -not [IO.Path]::IsPathFullyQualified($OutputRoot)) {
    throw '请通过 -OutputRoot 提供已存在的绝对父目录。'
}
$parent = (Resolve-Path -LiteralPath $OutputRoot).ProviderPath
if (-not (Test-Path -LiteralPath $parent -PathType Container)) { throw '输出父目录必须是目录。' }
# 项目工具负责创建全新的时间戳子目录。
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$output = Join-Path $parent $stamp

if ($Mode -eq 'Backup') {
    & $node $tool backup --config $configPath --output $output
    if ($LASTEXITCODE -ne 0) { throw '完整备份失败。' }
    $report = Get-Content -LiteralPath (Join-Path $output 'manifest.json') -Raw | ConvertFrom-Json
    if ($report.state -ne 'VERIFIED_BACKUP') { throw '备份清单未通过核验。' }
    Write-Output "完整备份目录 $output"
    return
}

if (-not $Bundle -or -not $PSBoundParameters.ContainsKey('RestoreAppPort')) {
    throw '恢复演练需要 -Bundle 和 -RestoreAppPort。'
}
foreach ($key in 'IMS_RESTORE_DB_PASSWORD','IMS_RESTORE_APP_PASSWORD') {
    if (-not $settings.$key -or $settings.$key -in @('REPLACE_RESTORE_OWNER_PASSWORD','REPLACE_RESTORE_APP_PASSWORD')) {
        throw "请填写 $key"
    }
}
$bundlePath = (Resolve-Path -LiteralPath $Bundle).ProviderPath
$database = "ims_platform_test_restore_$stamp"
$toolArgs = @('restore-verify','--config',$configPath,'--bundle',$bundlePath,'--output',$output,
    '--database',$database,'--port',"$RestoreAppPort",'--current-ledger',$settings.IMS_PLATFORM_LEDGER_ROOT)
& $node $tool @toolArgs
if ($LASTEXITCODE -ne 0) { throw '恢复演练失败。' }
$report = Get-Content -LiteralPath (Join-Path $output 'restore-report.json') -Raw | ConvertFrom-Json
if ($report.state -ne 'RESTORED_VERIFIED_NOT_OPENED' -or
    -not $report.recordDigestsMatched -or -not $report.fileDigestsMatched -or
    -not $report.latestSecurityLedgerCopied) { throw '恢复报告未通过核验。' }
Write-Output "恢复库 $database"
Write-Output "恢复结果目录 $output"
