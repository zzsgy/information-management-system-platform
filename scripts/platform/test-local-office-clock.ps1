#Requires -Version 7.0
<#
.SYNOPSIS
只读检查本机Office专用WSL的时钟连续性及其与宿主机的偏差。
.PARAMETER Distribution
已部署的Office专用WSL名称；本脚本只采样，不调整时间或更改校时服务。
.PARAMETER SampleSeconds
采样秒数，默认40秒覆盖Ubuntu NTP的32秒最小轮询；不能用一次date读数证明时钟稳定。
.PARAMETER ReportPath
可选诊断报告路径。仅包含时钟数值，不包含用户、文档或认证信息。
#>
[CmdletBinding()]
param(
    [string]$Distribution = 'ims-office-20261029',
    [ValidateRange(10, 120)][int]$SampleSeconds = 40,
    [string]$ReportPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# 实时钟可能回退；采样截止和相邻样本的预期增量必须使用单调钟。
$probe = @'
import json, time, sys
duration = int(sys.argv[1])
start = time.monotonic()
previous = None
samples = 0
largest_step = 0
backward = 0
while True:
    steady = time.monotonic()
    wall = time.time()
    if previous:
        step = (wall - previous[0]) - (steady - previous[1])
        largest_step = max(largest_step, abs(step))
        if step < -0.5:
            backward += 1
    previous = (wall, steady)
    samples += 1
    if steady - start >= duration:
        break
    time.sleep(0.1)
print(json.dumps({'sampleSeconds': steady-start, 'samples': samples,
    'largestStepSeconds': largest_step, 'backwardSteps': backward, 'guestEpochSeconds': wall}))
'@
$output = & wsl.exe -d $Distribution -u root --exec python3 -c $probe $SampleSeconds
if ($LASTEXITCODE -ne 0) { throw '无法完成Office专用WSL时钟采样。' }
$sample = $output | ConvertFrom-Json
$offset = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0 - $sample.guestEpochSeconds
$passed = $sample.largestStepSeconds -le 0.5 -and [Math]::Abs($offset) -le 2
$report = [ordered]@{
    distribution = $Distribution
    checkedAt = [DateTimeOffset]::UtcNow.ToString('o')
    passed = $passed
    sampleSeconds = $sample.sampleSeconds
    samples = $sample.samples
    backwardSteps = $sample.backwardSteps
    largestStepSeconds = $sample.largestStepSeconds
    hostMinusGuestSeconds = $offset
}
if ($ReportPath) { $report | ConvertTo-Json | Set-Content -LiteralPath $ReportPath -Encoding utf8NoBOM }
if (-not $passed) { throw ('Office时钟未通过：最大跳变{0:N3}秒，宿主偏差{1:N3}秒。先检查WSL与NTP是否重复校时，保持编辑中检查点关闭。' -f $sample.largestStepSeconds, $offset) }
Write-Host ('Office时钟检查通过：采样{0:N1}秒，最大跳变{1:N3}秒，宿主偏差{2:N3}秒。' -f $sample.sampleSeconds, $sample.largestStepSeconds, $offset)
