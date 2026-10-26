# M6 实机测试驱动：以真实 bot 通道完成 QQ 门槛注册闭环。
#
# 无控制台环境下无法执行 /vauth pair new，因此这里用 config.yml 的
# qqbot.pairing.bootstrap-code 预置一次性配对码（同样只存内存、一次性、TTL）。
#
# 用法：pwsh -File tools/e2e/run-m6.ps1
$ErrorActionPreference = 'Stop'

$config = 'E:\MC\PluginTestServers\velocity\plugins\velocityauth\config.yml'
$velocityDir = 'E:\MC\PluginTestServers\velocity'
$pairingFile = Join-Path $PSScriptRoot 'pairing.json'
$botPort = 8766

$code = -join ((1..32) | ForEach-Object { 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_'.ToCharArray()[(Get-Random -Maximum 64)] })

# 1) 改写测试配置
$cfg = Get-Content $config -Raw
$cfg = [regex]::Replace($cfg, '(?m)^  gate: \S+.*$', '  gate: qq')
$cfg = [regex]::Replace($cfg, '(?m)^  ttl-seconds: \d+$', '  ttl-seconds: 120')
$cfg = [regex]::Replace($cfg, '(?m)^  enabled: false\s+# 需要 QQ 验证/绑定时再开$', '  enabled: true')
$cfg = [regex]::Replace($cfg, '(?m)^  group-id: ""\s+# 验证用的 QQ 群号$', '  group-id: "123456"')
$cfg = [regex]::Replace($cfg, '(?m)^  port: 8765\s*$', "  port: $botPort")
$cfg = [regex]::Replace($cfg, '(?m)^    bootstrap-code: "".*$', "    bootstrap-code: `"$code`"")
Set-Content $config $cfg -NoNewline -Encoding UTF8

# 2) 重启 velocity
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*velocity.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 4
Start-Process -FilePath 'D:\Java\25\bin\java.exe' `
    -ArgumentList '-Xms512M', '-Xmx1G', '-jar', 'velocity.jar' `
    -WorkingDirectory $velocityDir `
    -RedirectStandardOutput (Join-Path $velocityDir 'va-test-console.log') `
    -RedirectStandardError (Join-Path $velocityDir 'va-test-console.err') | Out-Null

$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    if (Select-String -Path (Join-Path $velocityDir 'va-test-console.log') -Pattern 'Done \(' -Quiet) { break }
}
Write-Host 'Velocity 已就绪'

# 3) 从日志中取 pairing_id（日志只记录 id，不记录码），写 pairing.json
$logFile = Join-Path $velocityDir 'va-test-console.log'
$pairingId = $null
$match = Select-String -Path $logFile -Pattern 'pairing_id=(\S+?)[，,\s]' | Select-Object -Last 1
if ($match) { $pairingId = $match.Matches[0].Groups[1].Value }
if (-not $pairingId) {
    $match = Select-String -Path $logFile -Pattern '(pair_[0-9a-f]{8,})' | Select-Object -Last 1
    if ($match) { $pairingId = $match.Matches[0].Groups[1].Value }
}
if (-not $pairingId) { throw '未能从日志解析 pairing_id' }
Write-Host "pairing_id=$pairingId"

@{ host = '127.0.0.1'; port = $botPort; pairing_id = $pairingId; code = $code } |
    ConvertTo-Json | Set-Content $pairingFile -Encoding UTF8

# 4) 跑测试
$env:VA_PAIRING = $pairingFile
node (Join-Path $PSScriptRoot 'm6-qq-flow.mjs')
$exit = $LASTEXITCODE
Remove-Item $pairingFile -Force -ErrorAction SilentlyContinue
exit $exit
