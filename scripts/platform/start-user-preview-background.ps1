#Requires -Version 7.0
<#
.SYNOPSIS
以当前 Windows 用户的独立后台任务启动日常预览，重复执行不会重复创建进程。
.DESCRIPTION
应用与数据库分别由 Windows 托管，Codex 和启动终端关闭不负责结束组件。
任务在当前用户登录后启动，不保存 Windows 密码，也不提升管理员权限。
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$pointer = Get-Content -LiteralPath 'D:/tmp/ims-platform-current-preview.json' -Raw | ConvertFrom-Json
if ($pointer.purpose -ne 'USER_MAINTAINED_PREVIEW_NO_TEST_FIXTURES') { throw '当前指针不是日常预览。' }
$runtime = (Resolve-Path -LiteralPath $pointer.runtimeRoot).ProviderPath
$runner = Join-Path $PSScriptRoot 'run-user-preview-component.ps1'
$powerShell = (Get-Process -Id $PID).Path
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
# 长时间预览不采用默认三天执行上限，使用电池时也不自动停止。
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -Hidden -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
$components = @(
    @{ Name = 'Database'; Task = 'BioLink-IMS-UserPreview-Database-55469' },
    @{ Name = 'Application'; Task = 'BioLink-IMS-UserPreview-Application-18467' }
)
$configuration = Get-Content -LiteralPath $pointer.configPath -Raw | ConvertFrom-Json
if ($configuration.IMS_PLATFORM_OFFICE_ENABLED -eq 'true') {
    $components += @{ Name = 'Office'; Task = 'BioLink-IMS-UserPreview-Office-18457' }
}
if ($configuration.IMS_PLATFORM_PREVIEW_LAN_OFFICE_BRIDGE) {
    $components += @{ Name = 'LanOffice'; Task = 'BioLink-IMS-UserPreview-LanOffice-18457' }
}
foreach ($component in $components) {
    $arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -File "{0}" -Component {1} -RuntimeRoot "{2}"' -f $runner, $component.Name, $runtime
    $task = Get-ScheduledTask -TaskName $component.Task -ErrorAction SilentlyContinue
    if ($task -and $task.Actions.Arguments -ne $arguments) { throw "已有同名任务用途不符：$($component.Task)" }
    if (-not $task) {
        $action = New-ScheduledTaskAction -Execute $powerShell -Argument $arguments -WorkingDirectory $runtime
        Register-ScheduledTask -TaskName $component.Task -Action $action -Principal $principal -Trigger $trigger -Settings $settings -Description '百林科日常预览，使用现有数据库与资料；独立于Codex运行。' | Out-Null
    }
    else { Set-ScheduledTask -TaskName $component.Task -Settings $settings | Out-Null }
}
foreach ($component in $components) {
    if ($component.Name -in @('Office', 'LanOffice')) {
        if ((Get-ScheduledTask -TaskName $component.Task).State -ne 'Running') { Start-ScheduledTask -TaskName $component.Task }
        continue
    }
    $ready = $false
    if ($component.Name -eq 'Database') {
        & 'D:/PostgreSQL/pgsql16/bin/pg_isready.exe' -h 127.0.0.1 -p 55469 -t 1 -q
        $ready = $LASTEXITCODE -eq 0
    } else {
        try { $ready = (Invoke-WebRequest -Uri $pointer.url -SkipCertificateCheck -TimeoutSec 2).StatusCode -eq 200 } catch {}
    }
    if (-not $ready) {
        # 发布只停止 Java；等待旧应用任务完成后重启，数据库任务持续运行。
        for ($attempt = 0; $attempt -lt 10; $attempt++) {
            if ((Get-ScheduledTask -TaskName $component.Task).State -ne 'Running') { break }
            Start-Sleep -Seconds 1
        }
        Start-ScheduledTask -TaskName $component.Task
    }
    for ($attempt = 0; $attempt -lt 60 -and -not $ready; $attempt++) {
        Start-Sleep -Seconds 1
        if ($component.Name -eq 'Database') {
            & 'D:/PostgreSQL/pgsql16/bin/pg_isready.exe' -h 127.0.0.1 -p 55469 -t 1 -q
            $ready = $LASTEXITCODE -eq 0
        } else {
            try { $ready = (Invoke-WebRequest -Uri $pointer.url -SkipCertificateCheck -TimeoutSec 2).StatusCode -eq 200 } catch {}
        }
    }
    if (-not $ready) { throw "$($component.Name)未就绪，请检查私有运行目录中的任务日志。" }
}
Write-Output "日常预览已就绪：$($pointer.url)；应用和数据库由 Windows 后台任务托管。"
