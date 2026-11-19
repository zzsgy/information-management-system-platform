param(
 [Parameter(Mandatory=$true)][string]$Node,
 [Parameter(Mandatory=$true)][string]$Bootstrap,
 [Parameter(Mandatory=$true)][string]$PostgresBin,
 [string]$TaskName='IMS-Operations-Agent'
)
$ErrorActionPreference='Stop'
# 只在部署机由管理员显式执行一次；网页保存配置不会安装计划任务或授予系统权限。
foreach($imsPath in @($Node,$Bootstrap,(Join-Path $PostgresBin 'psql.exe'))){if(-not (Test-Path -LiteralPath $imsPath -PathType Leaf)){throw "文件不存在：$imsPath"};if($imsPath.Contains('"')){throw '路径不允许双引号'}}
$imsAgent=Join-Path $PSScriptRoot 'operations-agent.cjs'
$imsAction=New-ScheduledTaskAction -Execute (Resolve-Path -LiteralPath $Node).Path -Argument ('"'+$imsAgent+'" --config "'+(Resolve-Path -LiteralPath $Bootstrap).Path+'" --postgres-bin "'+(Resolve-Path -LiteralPath $PostgresBin).Path+'"') -WorkingDirectory $PSScriptRoot
$imsTrigger=New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
$imsSettings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 25) -StartWhenAvailable
# 执行账号必须可管理该应用服务，并可访问数据库、源目录和独立备份盘。秘密仅交给Windows任务计划程序。
$imsCredential=Get-Credential -Message '填写部署服务账号；账号须具有该应用服务的停启权限'
Register-ScheduledTask -TaskName $TaskName -Action $imsAction -Trigger $imsTrigger -Settings $imsSettings -User $imsCredential.UserName -Password $imsCredential.GetNetworkCredential().Password -RunLevel Highest -Description 'IMS独立备份与隔离恢复；读取IT页面配置，有任务时进入停机维护窗口' | Out-Null
Write-Output '已安装。请回到运维与保护，检查运行环境和执行器心跳；此操作不立即发起备份。'
