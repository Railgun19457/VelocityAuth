# 确保测试环境全部在线：paper2612 / paper12111 / folia / velocity。
# 已监听端口的服务不会被重启（velocity 除外，见 -RestartVelocity）。
# 用法：pwsh -File tools/e2e/start-all.ps1 [-RestartVelocity]
param([switch]$RestartVelocity)
$ErrorActionPreference = 'Stop'

$mc = 'E:\MC\PluginTestServers'
$java = 'D:\Java\25\bin\java.exe'

$servers = @(
    @{ Name = 'paper26.1.2'; Dir = "$mc\paper26.1.2"; Jar = 'paper.jar'; Port = 25563 },
    @{ Name = 'paper1.21.11'; Dir = "$mc\paper1.21.11"; Jar = 'paper.jar'; Port = 25564 },
    @{ Name = 'folia1.21.11'; Dir = "$mc\folia1.21.11"; Jar = 'folia.jar'; Port = 25566 }
)

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

foreach ($server in $servers) {
    if (Test-Port $server.Port) {
        Write-Host "$($server.Name) 已在运行（端口 $($server.Port)）"
        continue
    }
    Write-Host "启动 $($server.Name) …"
    Start-Process -FilePath $java `
        -ArgumentList '-Xms1G', '-Xmx2G', '-jar', $server.Jar, '-nogui' `
        -WorkingDirectory $server.Dir `
        -RedirectStandardOutput (Join-Path $server.Dir 'va-console.log') `
        -RedirectStandardError (Join-Path $server.Dir 'va-console.err') | Out-Null
}

# 等待后端就绪
$deadline = (Get-Date).AddSeconds(120)
do {
    $pending = @()
    foreach ($server in $servers) {
        $log = Join-Path $server.Dir 'va-console.log'
        $ready = (Test-Path $log) -and (Select-String -Path $log -Pattern 'Done \(' -Quiet)
        if (-not $ready) { $pending += $server.Name }
    }
    if ($pending.Count -eq 0) { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

if ($pending.Count -gt 0) {
    Write-Warning "以下后端未就绪：$($pending -join ', ')"
} else {
    Write-Host '后端全部就绪'
}

if ($RestartVelocity) {
    & (Join-Path $PSScriptRoot 'restart-velocity.ps1')
} elseif (-not (Test-Port 25565)) {
    & (Join-Path $PSScriptRoot 'restart-velocity.ps1')
} else {
    Write-Host 'Velocity 已在运行'
}
