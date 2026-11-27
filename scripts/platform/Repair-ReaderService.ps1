#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$ServiceName,
    [Parameter(Mandatory)][ValidateRange(1025,65535)][int]$Port,
    [Parameter(Mandatory)][string]$JavaPath,
    [Parameter(Mandatory)][string]$PdfWorkerJar,
    [Parameter(Mandatory)][string]$SofficePath,
    [Parameter(Mandatory)][string]$ReadingTempDirectory,
    [Parameter(Mandatory)][string]$ReceiptDirectory,
    [string]$BackupDirectory,
    [string]$BackupTaskName = 'IMS-Operations-Agent'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 只接受部署机绝对路径，避免把启动参数误指向 NSSM 的工作目录。
function Resolve-DeploymentPath([string]$Value) {
    if ($Value -notmatch '^(?:[A-Za-z]:[\\/]|\\\\)') { throw 'Use absolute Windows paths.' }
    return [IO.Path]::GetFullPath($Value)
}

# 确认监听端口由指定服务直接启动的 JVM 持有。
function Get-ReaderServiceProcess {
    $service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
    if (-not $service -or $service.State -ne 'Running') { return }
    foreach ($listener in (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if ($process -and $process.ParentProcessId -eq $service.ProcessId -and $process.Name -eq 'java.exe') {
            return $process
        }
    }
}

function Wait-ReaderService {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $process = Get-ReaderServiceProcess
        if ($process) { return $process }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "The Windows service did not regain its listener on port $Port."
}

$service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
if (-not $service -or $service.StartName -ne 'LocalSystem' -or
    [IO.Path]::GetFileName($service.PathName.Trim('"')) -ne 'nssm.exe' -or -not (Get-ReaderServiceProcess)) {
    throw 'Expected a running LocalSystem NSSM service that directly launches Java on the specified port.'
}
$settings = [ordered]@{
    'platform.reading.java'              = Resolve-DeploymentPath $JavaPath
    'platform.reading.worker-jar'        = Resolve-DeploymentPath $PdfWorkerJar
    'platform.reading.office-executable' = Resolve-DeploymentPath $SofficePath
    'platform.reading.temporary'         = Resolve-DeploymentPath $ReadingTempDirectory
    'platform.reading.audit-root'        = Resolve-DeploymentPath $ReceiptDirectory
}
foreach ($key in @('platform.reading.java', 'platform.reading.worker-jar', 'platform.reading.office-executable')) {
    if (-not (Test-Path -LiteralPath $settings[$key] -PathType Leaf)) { throw "Missing reader dependency: $key" }
}
$systemSid = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18')
if ($BackupDirectory) {
    $BackupDirectory = Resolve-DeploymentPath $BackupDirectory
    $directory = Get-Item -LiteralPath $BackupDirectory
    if (-not $directory.PSIsContainer -or $directory.FullName -eq $directory.Root.FullName -or
        ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Use the existing backup directory, not a volume root or a reparse point.'
    }
    $task = Get-ScheduledTask -TaskName $BackupTaskName -TaskPath '\'
    if ($task.Principal.UserId -notin @('SYSTEM', 'NT AUTHORITY\SYSTEM', $systemSid.Value) -or $task.State -eq 'Disabled') {
        throw 'Expected the enabled backup task to run as SYSTEM.'
    }
}

$registryPath = "SYSTEM\CurrentControlSet\Services\$ServiceName\Parameters"
$registry = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($registryPath, $true)
try {
    $original = $registry.GetValue('AppParameters', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    $valueKind = $registry.GetValueKind('AppParameters')
    if ($original -notmatch '(?:^|\s)-jar\s') { throw 'The service does not launch a JAR with the expected command line.' }
    $arguments = foreach ($key in $settings.Keys) { '"-D{0}={1}"' -f $key, $settings[$key] }
    $prefix = ($arguments -join ' ') + ' '
    if (-not $original.StartsWith($prefix, [StringComparison]::Ordinal)) {
        if ($original -match '(?i)-Dplatform\.reading\.(java|worker-jar|office-executable|temporary|audit-root)=') {
            throw 'Existing reader JVM properties differ. Review them before applying new values.'
        }
        # 保留原有启动参数和引号，将阅读属性放在 -jar 之前。
        $registry.SetValue('AppParameters', ($prefix + $original), $valueKind)
        try {
            Restart-Service -Name $ServiceName
            $process = Wait-ReaderService
            foreach ($key in $settings.Keys) {
                if (-not $process.CommandLine.Contains(('-D{0}={1}' -f $key, $settings[$key]))) {
                    throw "The live JVM did not receive $key."
                }
            }
        } catch {
            $registry.SetValue('AppParameters', $original, $valueKind)
            if ((Get-Service -Name $ServiceName).Status -eq 'Running') { Restart-Service -Name $ServiceName }
            else { Start-Service -Name $ServiceName }
            $null = Wait-ReaderService
            throw "Reader JVM settings were rolled back. Port $Port is listening again."
        }
    }
} finally { $registry.Dispose() }

Write-Host "STEP 1 READY: The service is listening on port $Port. Check the reading runtime and open an existing document."
if ($BackupDirectory) {
    # 由网页以服务身份验证回执绑定，再放行可能立刻停机的排队任务。
    $confirmation = Read-Host 'After the reading check passes, type BACKUP during the maintenance window'
    if ($confirmation -cne 'BACKUP') { return }

    $backupAcl = Get-Acl -LiteralPath $BackupDirectory
    $rights = [System.Security.AccessControl.FileSystemRights]::Modify
    $inheritance = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($systemSid, $rights, $inheritance, 'None', 'Allow')
    $backupAcl.AddAccessRule($rule)
    Set-Acl -LiteralPath $BackupDirectory -AclObject $backupAcl
    Write-Host 'SYSTEM access was restored. Check the queued backup in the operations console.'
}
