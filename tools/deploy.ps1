# 将构建产物部署到本地 Velocity 测试服。
# 用法：pwsh -File tools/deploy.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$target = 'E:\MC\PluginTestServers\velocity\plugins'

$jar = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'VelocityAuth-*.jar' |
    Where-Object { $_.Name -notlike '*-plain.jar' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "未找到构建产物，请先运行 gradle build"
}

Get-ChildItem $target -Filter 'VelocityAuth-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $jar.FullName $target -Force
Write-Host "已部署 $($jar.Name) -> $target"
