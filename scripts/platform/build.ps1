#Requires -Version 7.0
<#
.SYNOPSIS
构建独立平台及 PDF 处理工作器，不覆盖旧档案系统产物。
#>
[CmdletBinding()]
param([string]$MavenCommand = 'mvn')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# 仓库位置按脚本解析，中文目录不影响构建。
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
Push-Location -LiteralPath (Join-Path $repositoryRoot 'web')
try {
    & npm.cmd ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw '依赖安装失败。' }
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw '前端回归测试失败。' }
    & npm.cmd run build:platform
    if ($LASTEXITCODE -ne 0) { throw '平台网页构建失败。' }
} finally { Pop-Location }
& (Join-Path $PSScriptRoot 'build-todo-helper.ps1')
Push-Location -LiteralPath $repositoryRoot
try {
    # 清理本配置的构建目录，防止已移除的类或过期网页资源继续混入新包。
    & $MavenCommand -B --no-transfer-progress -f backend/pom.xml -Pplatform clean verify
    if ($LASTEXITCODE -ne 0) { throw '后端测试或打包失败。' }
    # PDF 原型使用独立项目和依赖，避免将处理组件加入旧档案系统的运行包。
    & $MavenCommand -B --no-transfer-progress -f processing/pdf-renderer/pom.xml verify
    if ($LASTEXITCODE -ne 0) { throw 'PDF 工作器测试或打包失败。' }
    Write-Host '独立平台构建成功：backend/target-platform/archive-service-0.1.0.jar'
    Write-Host 'PDF 工作器构建成功：processing/pdf-renderer/target/pdf-page-worker-0.1.0.jar'
} finally { Pop-Location }
