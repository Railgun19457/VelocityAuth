# M4 实机测试驱动：按门禁模式改写测试配置并重启 Velocity，然后跑 E2E。
# 用法：pwsh -File tools/e2e/run-m4.ps1 -Gate email|qq
param(
    [ValidateSet('email', 'qq')]
    [string]$Gate = 'email'
)
$ErrorActionPreference = 'Stop'

$config = 'E:\MC\PluginTestServers\velocity\plugins\velocityauth\config.yml'
$cfg = Get-Content $config -Raw

$cfg = [regex]::Replace($cfg, '(?m)^  gate: \S+.*$', "  gate: $Gate")
$cfg = [regex]::Replace($cfg, '(?m)^  ttl-seconds: \d+$', '  ttl-seconds: 30')
$cfg = [regex]::Replace($cfg, '(?m)^    per-ip-per-minute: \d+$', '    per-ip-per-minute: 30')
$cfg = [regex]::Replace($cfg, '(?m)^    per-account-per-minute: \d+$', '    per-account-per-minute: 30')
$cfg = [regex]::Replace($cfg, '(?m)^  enabled: false\s+# 需要邮箱验证/找回时再开$', '  enabled: true')
$cfg = [regex]::Replace($cfg, '(?m)^  host: smtp\.qq\.com$', '  host: 127.0.0.1')
$cfg = [regex]::Replace($cfg, '(?m)^  port: 465$', '  port: 2525')
$cfg = [regex]::Replace($cfg, '(?m)^  ssl: true$', '  ssl: false')
$cfg = [regex]::Replace($cfg, '(?m)^  from: "VelocityAuth <example@qq\.com>"$', '  from: "VelocityAuth <noreply@test.local>"')
# QQ bot（M4 仍是桩实现，这里只用于打开 qq 渠道）
$cfg = [regex]::Replace($cfg, '(?m)^  enabled: false\s+# 需要 QQ 验证/绑定时再开$', '  enabled: true')
$cfg = [regex]::Replace($cfg, '(?m)^  group-id: ""\s+# 验证用的 QQ 群号$', '  group-id: "123456"')
Set-Content $config $cfg -NoNewline -Encoding UTF8

Write-Host "测试配置已写入（gate=$Gate, ttl=30s, smtp=127.0.0.1:2525）"
& (Join-Path $PSScriptRoot 'start-all.ps1') -RestartVelocity

$env:VA_GATE = $Gate
node (Join-Path $PSScriptRoot 'm4-gate.mjs')
exit $LASTEXITCODE
