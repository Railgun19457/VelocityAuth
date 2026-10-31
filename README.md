# VelocityAuth

![:VelocityAuth](https://count.getloli.com/@railgun19457_VelocityAuth?name=railgun19457_VelocityAuth&theme=minecraft&padding=6&offset=0&align=top&scale=1&pixelated=1&darkmode=auto)

运行在 Velocity 上的账号认证插件。**账号名独立于游戏 ID**：用任意游戏 ID 登录后，全服看到的都是账号的 `name` + `uuid`，Java 端与基岩端共用同一份账号数据。

## 功能特性

- 账号名 + 密码认证；登录后 profile 在握手阶段被重写为账号身份
- 账号 UUID 用**离线模式原生算法**（`offline(账号名)`，大小写敏感），未装认证插件时期的历史数据（权限组、经济、领地、背包……）无缝接上
- 支持**任意游戏 ID 登录同一账号**（用 `test2` 登录 `test1`，全服表现为 `test1`）
- Java 端与基岩端（Geyser + Floodgate）**共用同一账号**
- **注册后必须绑定**（可配）：`email` 与任意社交渠道（默认 `qq`，可加 `discord` 等）可同时开启，玩家任选其一；未绑定不能登录，超时未绑定自动清除
- 邮箱 / 社交账号绑定用于**找回密码**；邮箱域名可白/黑名单限制
- **渠道无关的绑定框架**：`email` 走内置 SMTP，社交平台（QQ / Discord / …）由 bot 适配器实现，插件只保留**平台无关**的接受规则
- **绑定历史**：解绑 / 注销后仍保留渠道标识的绑定记录（可追溯曾绑定过哪些账号），并支持**再绑定冷却**（防同一邮箱/账号反复换号）
- **注销账号** `/deleteaccount`（需登录 + 二次验证）
- 验证码统一走一次性会话（TTL、限次、限流），邮件与提示带**操作名称**区分场景
- 未认证隔离：路由到登录服 + 拦截命令 + 兜底拦跳服；命令的**补全与可见性**按登录状态与权限过滤
- 记忆上次所在子服；可选会话保持（同 IP 免密，默认关闭）
- 正版与离线连接一视同仁：一律走账号认证，身份统一重写为账号的 `name` + `uuid`
- 完整管理命令（状态、账号管理、踢会话、顶号、审计日志）
- MiniMessage 文案（中英双语）+ 点击事件：`[登录]`/`[注册]`/`[忘记密码]` 一键填充、社交验证码点击复制
- SQLite（默认）/ MySQL
- 与社交平台 bot 的安全 WebSocket 通道（Ed25519 + X25519/AES-256-GCM，见 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)）
- bot 通道内置**玩家信息查询接口**（`query.v1`，默认关闭）：按账号查详情、按渠道标识/IP 反查账号、查登录历史（成功/失败/登出）、查渠道绑定历史（含解绑/注销）、查聚合统计（注册数/各渠道绑定数/在线等）；权限校验由 bot 侧负责

## 运行环境

- Java 21+，Velocity 3.5.1
- 可选：Geyser + Floodgate（基岩端）、SMTP 服务、社交平台 bot

## 安装

1. 把插件 Jar 放进 Velocity 的 `plugins` 目录（从 Release 下载或本地构建）。
2. 启动代理端，会自动释放可编辑文件到 `plugins/velocityauth/`：`config.yml`、`lang/zh_cn.yml`、`lang/en_us.yml`、`email.yml`。
3. `velocity.toml` 里允许接收 Transfer（登录成功后会重连一次完成身份重写）：

```toml
[advanced]
accepts-transfers = true
```

`player-info-forwarding-mode` 按习惯设置（`modern` 需与后端 `forwarding.secret` 一致）。

> **登录服必须是专用服**：未认证玩家在登录服表现为连接名身份，别在上面挂按 UUID 存数据的插件。

## 命令

### 玩家命令

| 命令 | 说明 | 可用状态 |
|---|---|---|
| `/register <账号> <密码> <确认密码>` | 注册 | 未认证 |
| `/login <账号> <密码>` | 登录 | 未认证 |
| `/logout` | 退出登录 | 已认证 |
| `/changepassword <旧密码> <新密码>` | 修改密码 | 已认证 |
| `/bind <渠道> [值]` | 绑定邮箱（`email`，需地址）或社交渠道（如 `qq`，群内发码） | 已认证，或待绑定 / 密码已通过的未认证连接 |
| `/unbind <渠道>` | 解绑（需先验证一次） | 已认证 |
| `/deleteaccount` | 注销账号（需二次验证） | 已认证 |
| `/verify <验证码>` / `/verify cancel` | 输入邮箱验证码 / 取消 | — |
| `/forgot <账号> [渠道]` | 找回密码 | 未认证 |
| `/resetpassword <新密码> <确认密码>` | 验证通过后重置密码 | — |

渠道参数（`email` / 各社交渠道）支持 Tab 补全。命令的补全与可见性按状态与权限过滤（未认证时只看到登录相关命令，`/vauth` 只对有权限的账号可见）。

### 注册与绑定

`/register` 固定三个参数。是否必须绑定由 `registration.require-binding` 决定：

- **空列表**：注册完直接可用并自动登录。
- **配置了 `email` / 社交渠道**：账号先落为「待绑定」，玩家任选其一完成绑定后才激活并自动登录；超过 `unbound-ttl-minutes` 未绑定会被自动清除、账号名释放。

未绑定的账号**不能登录**（`/login` 会提示先绑定）。判断以「是否已绑定」为准，所以配置从无到有后，**历史遗留的未绑定账号也会被拦在登录外**，需要先 `/bind`。

### 找回密码

1. `/forgot <账号>` → 列出可选渠道
2. `/forgot <账号> <渠道>` → 向**已绑定**的渠道发码
3. `/resetpassword <新密码> <确认密码>` → 验证通过后 5 分钟内可用，成功后自动登录

前提是账号有绑定；没绑定的账号收不到码。重置成功同样要满足 `require-binding`。

### 管理命令

`/velocityauth`（别名 `/vauth`）

| 子命令 | 说明 |
|---|---|
| `status` | 运行状态（数据库、SMTP、bot、会话、验证、基岩端） |
| `reload` | 重载配置、语言文件与邮件模板 |
| `account <账号> info` | 账号详情 |
| `account <账号> setpassword <密码>` | 强制改密 |
| `account <账号> unbind <渠道>` | 强制解绑 |
| `account <账号> lock\|unlock` | 锁定 / 解锁（锁定会踢出在线会话） |
| `account <账号> delete` | 删除账号（级联删除身份与绑定） |
| `sessions list` | 列出在线已认证会话 |
| `sessions kick <账号>` | 踢出某账号的所有在线会话 |
| `forcelogin <账号> [--kick]` | 强制登录为指定账号（顶号） |
| `pair new` / `pair list` / `pair revoke <id>` | bot 配对码管理 |
| `client list` / `client revoke <id>` | 已配对 bot 客户端管理 |

插件**不提供查看明文密码**（bcrypt 单向哈希）；需要顶号请用 `forcelogin`。

## 权限

| 权限 | 说明 |
|---|---|
| `velocityauth.admin` | 使用 `/vauth`（不含强制登录） |
| `velocityauth.admin.forcelogin` | 使用 `/vauth forcelogin`（独立权限，不与 `admin` 合并） |

控制台默认拥有全部权限。

## 配置

主要分区（完整默认值与注释见 [`config.example.yml`](config.example.yml)）：

- `language`：`zh_cn` / `en_us`
- `database`：`sqlite`（默认）或 `mysql`；`login-history-days` / `audit-log-days` / `binding-history-days` 三张历史表的保留天数（0=永久）
- `servers`：`login` 登录服、`default` 认证后默认服、`allowed` 允许进入的子服、`remember-last`
- `auth`：账号名 / 密码规则、`bcrypt-cost`、会话保持 `session`、基岩 `bedrock`
- `registration`：`require-binding`（注册后必须绑定的渠道）、`unbound-ttl-minutes`
- `binding`：`email`（基数、域名白/黑名单、冷却）+ `social`（任意社交渠道，各自基数/冷却/码长）；`cooldown-minutes` 解绑/注销后的再绑定冷却
- `verification`：码长、有效期、尝试次数、限流
- `smtp`：内置 SMTP（QQ 邮箱填**授权码**）
- `bot`：bot 通道（平台无关）：监听、配对、`query`（玩家信息查询接口，默认关闭）。各渠道的监听位置/成员校验由 bot 侧负责
- `proxy` / `security`：对外地址、bot 握手参数

**邮件模板**在 `plugins/velocityauth/email.yml`：`html`（精美卡片正文）+ `text`（纯文本兜底），占位符 `{action}`（操作名称）`{code}` `{minutes}` `{server}`。

**文案**在 `plugins/velocityauth/lang/*.yml`，MiniMessage 语法，可自行修改。

## 注意事项

- 登录成功后会有一次重连；1.20.5 以下客户端会断开并提示重新连接（建议显式配置 `proxy.address`）
- 社交渠道的验证码**只能在对应房间发**：游戏内 `/verify` 对社交会话无效，否则公开房间里的码可被任何人用来冒用
- 邮箱域名按 `@` 之后**精确匹配**（不做子域名后缀匹配，避免 `mail.qq.com` 绕过白名单）
- 会话保持默认关闭：国内 NAT 共享 IP 普遍，同 IP 免密有被他人利用的风险
- 账号名先来先得：名称一旦被注册，其他人无法再用
- `/vauth reload` 不重建 bot 通道：改动 `bot.*` 需重启代理
- 释放出来的 `config.yml` / `lang/*.yml` / `email.yml` 带 `# version: N` 标记。升级时：语言文件与邮件模板会在**备份原文件后**自动升级；`config.yml` 因含凭据**不会自动覆盖**，只会写出 `config.yml.new` 供比对。你改过但没有版本标记的文件也只会得到 `.new`
- **不要提交**：`config.yml`（含 SMTP 授权码、数据库密码）、`keys/`（私钥）、`*.db`（玩家数据）

## 本地构建

```bash
./gradlew.bat clean build     # 产物：build/libs/VelocityAuth-<version>.jar（含依赖）
./gradlew.bat test            # 单元测试（含协议向量与 bot 通道集成测试）
```

## License

MIT License. See [LICENSE](LICENSE).
