#Requires -Version 7.0
<#
.SYNOPSIS
按明确维护决定续期本机日常预览已配置的服务身份，保留原模块、部门、动作及凭据。
.DESCRIPTION
只接受当前日常预览指针及其四类服务配置；停用身份不能通过续期恢复。
先备份，再在同一事务中续期并记录原因。到期前30天由管理员页面提醒，本脚本不自动运行。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('DriveScanner','ArchiveScanner','PlatformScanner','Office')][string]$Service,
    [Parameter(Mandatory)][datetimeoffset]$ValidUntil,
    [Parameter(Mandatory)][ValidateLength(5,500)][string]$Reason
)
$ErrorActionPreference='Stop'
$pointer=Get-Content 'D:/tmp/ims-platform-current-preview.json' -Raw | ConvertFrom-Json
if($pointer.purpose -ne 'USER_MAINTAINED_PREVIEW_NO_TEST_FIXTURES'){throw '不是日常预览环境'}
$config=Get-Content $pointer.configPath -Raw | ConvertFrom-Json
if($config.IMS_PLATFORM_DB_URL -notmatch '^jdbc:postgresql://127\.0\.0\.1:55469/(ims_platform_dev_[0-9]+)$'){throw '日常预览数据库不符'}
$database=$Matches[1]
if($ValidUntil -le [datetimeoffset]::Now -or $ValidUntil -gt [datetimeoffset]::Now.AddYears(1)){throw '有效期必须在未来一年内'}
$keys=@{DriveScanner='IMS_PLATFORM_SCANNER_SERVICE_ID';ArchiveScanner='IMS_PLATFORM_SCANNER_ARCHIVE_SERVICE_ID';PlatformScanner='IMS_PLATFORM_SCANNER_PLATFORM_SERVICE_ID';Office='IMS_PLATFORM_OFFICE_SERVICE_ID'}
$serviceId=[guid]$config.($keys[$Service])
$module=@{DriveScanner='DRIVE';ArchiveScanner='ARCHIVE';PlatformScanner='PLATFORM';Office='DRIVE'}[$Service]
$type=if($Service -eq 'Office'){'OFFICE'}else{'FILE_CHECK'}
$backup=Join-Path $pointer.runtimeRoot ('service-renewal-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.dump')
$pg='D:/PostgreSQL/pgsql16/bin'
$env:PGPASSWORD=$config.IMS_PLATFORM_DB_PASSWORD
try{
 & "$pg/pg_dump.exe" -h 127.0.0.1 -p 55469 -U $config.IMS_PLATFORM_DB_USER -d $database -Fc -f $backup
 if($LASTEXITCODE -ne 0){throw '备份失败，未续期'}
 $sql=@'
BEGIN;
WITH renewed AS (
 UPDATE iam.service_identity SET valid_until=:'until'::timestamptz,row_version=row_version+1
 WHERE id=:'service'::uuid AND service_type=:'type' AND allowed_module=:'module'
 AND status='ACTIVE' AND valid_until<:'until'::timestamptz RETURNING id,valid_until
)
INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,record_kind,severity,operation_result)
SELECT gen_random_uuid(),gen_random_uuid(),CURRENT_TIMESTAMP,(SELECT id FROM iam.user_account WHERE employee_no='admin'),'PLATFORM','SERVICE_IDENTITY',id,'RENEW_SERVICE_IDENTITY','SUCCEEDED',gen_random_uuid(),'BUSINESS_EVENT','INFO',jsonb_build_object('reason',:'reason','validUntil',valid_until) FROM renewed;
COMMIT;
SELECT name,status,valid_until FROM iam.service_identity WHERE id=:'service'::uuid;
'@
 $sql | & "$pg/psql.exe" -h 127.0.0.1 -p 55469 -U $config.IMS_PLATFORM_DB_USER -d $database -v ON_ERROR_STOP=1 -v "service=$serviceId" -v "module=$module" -v "type=$type" -v "until=$($ValidUntil.ToUniversalTime().ToString('o'))" -v "reason=$Reason"
 if($LASTEXITCODE -ne 0){throw '续期失败，请核对数据库回执'}
}finally{Remove-Item Env:PGPASSWORD}
