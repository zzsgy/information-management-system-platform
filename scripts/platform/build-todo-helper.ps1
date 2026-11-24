<#
.SYNOPSIS
构建 Windows 待办托盘助手，输出到平台网页构建的下载目录。
.PARAMETER OutputDirectory
默认 web/dist-platform/downloads。请在网页构建完成后运行本脚本。
#>
param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root 'web/dist-platform/downloads' }
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$compiler = Join-Path $framework 'csc.exe'
if (!(Test-Path -LiteralPath $compiler)) { throw '需要 Windows .NET Framework 4.8 自带的 C# 编译器。' }
$directory = (New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName
$source = Join-Path $root 'desktop/todo-helper/Program.cs'
$updater = Join-Path $root 'desktop/todo-helper/HelperUpdater.cs'
$connection = Join-Path $root 'desktop/todo-helper/HelperConnection.cs'
$companyRoot = Join-Path $root 'desktop/todo-helper/IMS-LAN-CA.cer'
$output = Join-Path $directory 'ims-todo-helper.exe'
$compilerArgs = @('/nologo', '/target:winexe', '/optimize+', '/codepage:65001', "/out:$output",
    '/reference:System.Windows.Forms.dll', '/reference:System.Drawing.dll', '/reference:System.Net.Http.dll',
    '/reference:System.Web.Extensions.dll', '/reference:System.Security.dll', "/resource:$companyRoot,IMS-LAN-CA.cer", $source, $updater, $connection)
& $compiler @compilerArgs
if ($LASTEXITCODE -ne 0) { throw "托盘助手构建失败，退出码 $LASTEXITCODE。" }
# 清单与生成的 EXE 同批发布，客户端只接受版本和摘要均匹配的文件。
$manifest = [ordered]@{
    version = [Reflection.AssemblyName]::GetAssemblyName($output).Version.ToString(3)
    sha256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    bytes = (Get-Item -LiteralPath $output).Length
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'ims-todo-helper-version.json') -Encoding utf8NoBOM
Write-Output $output
