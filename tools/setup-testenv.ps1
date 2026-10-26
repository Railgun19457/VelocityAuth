# 一键准备本地实机测试环境（E:\MC\PluginTestServers）。
#
# 做的事：
#   1. 备份并改为离线模式（proxy online-mode=false、force-key-authentication=false、
#      后端 velocity.online-mode=false、enforce-secure-profile=false）
#   2. 生成 plugins/velocityauth/config.yml（服务器名对齐 velocity.toml）
#   3. 部署最新构建产物
#
# 用法：pwsh -File tools/setup-testenv.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$mc = 'E:\MC\PluginTestServers'
$velocity = Join-Path $mc 'velocity'
$backends = @('paper26.1.2', 'paper1.21.11', 'folia1.21.11')

function Backup-Once([string]$path) {
    $backup = "$path.va-orig"
    if (-not (Test-Path $backup)) {
        Copy-Item $path $backup
        Write-Host "已备份 $path -> $backup"
    }
}

# ---------- 1. Velocity 代理 ----------
$velocityToml = Join-Path $velocity 'velocity.toml'
Backup-Once $velocityToml
$toml = Get-Content $velocityToml -Raw
$toml = [regex]::Replace($toml, '(?m)^online-mode = true\s*$', 'online-mode = false')
$toml = [regex]::Replace($toml, '(?m)^force-key-authentication = true\s*$', 'force-key-authentication = false')
Set-Content $velocityToml $toml -NoNewline -Encoding UTF8
Write-Host 'Velocity：online-mode=false, force-key-authentication=false'

# ---------- 2. 后端 ----------
foreach ($backend in $backends) {
    $dir = Join-Path $mc $backend

    $globalYml = Join-Path $dir 'config\paper-global.yml'
    Backup-Once $globalYml
    $lines = Get-Content $globalYml
    $velocityIndex = ($lines | Select-String -Pattern '^\s{2}velocity:\s*$' | Select-Object -First 1).LineNumber
    if (-not $velocityIndex) { throw "在 $globalYml 中找不到 velocity: 段" }
    for ($i = $velocityIndex; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s{4}online-mode:\s*true\s*$') {
            $lines[$i] = '    online-mode: false'
            break
        }
    }
    Set-Content $globalYml $lines -Encoding UTF8

    $properties = Join-Path $dir 'server.properties'
    Backup-Once $properties
    $props = Get-Content $properties -Raw
    $props = [regex]::Replace($props, '(?m)^enforce-secure-profile=true\s*$', 'enforce-secure-profile=false')
    Set-Content $properties $props -NoNewline -Encoding UTF8
    Write-Host "$backend：velocity.online-mode=false, enforce-secure-profile=false"
}

# ---------- 3. 插件配置 ----------
$pluginDir = Join-Path $velocity 'plugins\velocityauth'
New-Item -ItemType Directory -Force -Path $pluginDir | Out-Null
$pluginConfig = Join-Path $pluginDir 'config.yml'
Copy-Item (Join-Path $root 'src\main\resources\config.yml') $pluginConfig -Force

$cfg = Get-Content $pluginConfig -Raw
$cfg = $cfg -replace '(?m)^  login: lobby.*$', '  login: paper2612         # 登录服：未认证玩家停留的服务器'
$cfg = $cfg -replace '(?m)^  default: main.*$', '  default: paper12111      # 认证成功后默认进入的服务器'
$cfg = $cfg -replace '(?m)^  allowed:.*$', "  allowed:`n    - paper12111           #   记忆功能只会在这份白名单内恢复`n    - folia"
$cfg = $cfg -replace '(?m)^  port: 8765\s*$', '  port: 8766'
Set-Content $pluginConfig $cfg -NoNewline -Encoding UTF8
Write-Host "已生成 $pluginConfig（login=paper2612, default=paper12111, qqbot.port=8766）"

# ---------- 4. 部署 ----------
& (Join-Path $PSScriptRoot 'deploy.ps1')
