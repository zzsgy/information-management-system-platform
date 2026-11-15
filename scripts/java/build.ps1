<#
.SYNOPSIS
构建 Vue 网页，完成前后端测试，并将网页与 Java 服务打成同一个 JAR。
.PARAMETER MavenCommand
Maven 命令名或绝对路径；不自动安装运行时，也不跳过测试。
#>
[CmdletBinding()]
param([string]$MavenCommand = 'mvn')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# 按脚本位置定位仓库，支持中文和空格路径。
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
# 只调用已安装的 Maven；JAVA_HOME 应指向 JDK 21 或已验证的兼容版本。
$mavenExecutable = (Get-Command -Name $MavenCommand -ErrorAction Stop).Source
Push-Location -LiteralPath $repositoryRoot
try {
    # 按锁文件安装前端依赖，任何测试失败都阻止发布构建。
    Push-Location -LiteralPath (Join-Path $repositoryRoot 'web')
    try {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw '网页依赖安装失败。' }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw '网页回归测试失败。' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '网页类型检查或生产构建失败。' }
    }
    finally { Pop-Location }
    & $mavenExecutable '-B' '--no-transfer-progress' '-f' 'backend/pom.xml' 'verify'
    # 只有 Maven 返回零才允许将本次产物用于部署。
    $buildExitCode = $LASTEXITCODE
    if ($buildExitCode -ne 0) { throw "Maven verify 失败，退出码：$buildExitCode；请处理失败后重新构建。" }
    Write-Host '网页与Java测试、打包已通过；完整Web程序位于 backend/target。启动后用浏览器访问服务根地址。'
}
finally { Pop-Location }
