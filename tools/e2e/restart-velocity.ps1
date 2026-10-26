# 重启 Velocity 测试服（只影响 velocity.jar，不动后端）。
# 用法：pwsh -File tools/e2e/restart-velocity.ps1 [-WaitSeconds 20]
param([int]$WaitSeconds = 20)
$ErrorActionPreference = 'Stop'

$velocityDir = 'E:\MC\PluginTestServers\velocity'
$log = Join-Path $velocityDir 'va-test-console.log'

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*velocity.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 3

Start-Process -FilePath 'D:\Java\25\bin\java.exe' `
    -ArgumentList '-Xms512M', '-Xmx1G', '-jar', 'velocity.jar' `
    -WorkingDirectory $velocityDir `
    -RedirectStandardOutput $log `
    -RedirectStandardError (Join-Path $velocityDir 'va-test-console.err') | Out-Null

for ($i = 0; $i -lt $WaitSeconds; $i++) {
    Start-Sleep -Seconds 1
    if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'Done \(' -Quiet)) {
        Write-Host 'Velocity 已就绪'
        exit 0
    }
}
Write-Host '等待超时，请检查 va-test-console.log'
exit 1
