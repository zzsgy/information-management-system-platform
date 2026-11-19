param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_.-]{1,128}$')][string]$ServiceName,
 [Parameter(Mandatory=$true)][ValidateSet('Status','Stop','Start')][string]$Action
)
$ErrorActionPreference='Stop'
# 固定服务操作，不接收命令模板，不终止同机其他Java进程。
$imsService=Get-Service -Name $ServiceName
if($Action -eq 'Status'){Write-Output $imsService.Status.ToString();exit}
if($Action -eq 'Stop'){Stop-Service -InputObject $imsService; $imsService.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(60))}
if($Action -eq 'Start'){Start-Service -InputObject $imsService; $imsService.WaitForStatus('Running',[TimeSpan]::FromSeconds(60))}
