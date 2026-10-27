# VelocityAuth

![:VelocityAuth](https://count.getloli.com/@railgun19457_VelocityAuth?name=railgun19457_VelocityAuth&theme=minecraft&padding=6&offset=0&align=top&scale=1&pixelated=1&darkmode=auto)

VelocityAuth 是一个运行在 Velocity 上的账号认证插件：**账号名独立于游戏 ID**，注册门槛可选，支持邮箱/QQ 绑定与找回密码。

账号即规范身份——用任意游戏 ID 登录后，全服看到的都是账号的 `name` + `uuid`，Java 端与基岩端共用同一份账号数据。

## 功能特性

- 账号名 + 密码认证，账号名独立于游戏 ID；登录后 **profile 在握手阶段被重写**为账号身份，客户端自己也看到账号名
- 支持**任意游戏 ID 登录同一账号**（`test2` 登录 `test1` 后，全服表现为 `test1`）
- Java 端与基岩端（Geyser + Floodgate）**共用同一账号**，基岩端身份用 Floodgate UUID 区分
- 注册门槛可配：`none` / `email` / `qq`（无验证、邮箱收码、群内发码）
- 账号可绑定**邮箱**与 **QQ**，用于找回密码；邮箱域名可白/黑名单限制
- 统一验证会话：注册门槛 / 绑定 / 解绑 / 找回共用同一套一次性验证码（TTL、限次、限流）
- **记忆上次所在子服**，登录后自动回到上次退出的子服
- 未认证隔离三件套：路由到登录服 + 拦截命令 + `ServerPreConnect` 兜底拦跳服
- 正版玩家保留真实 Mojang profile，可选是否仍需 `/login`；正版名与盗版账号名冲突按**先来先得**
- 可选会话保持（同 IP 免密登录，**默认关闭**）
- 与 QQ bot 的安全 WebSocket 通道：Ed25519 身份 + X25519/AES-256-GCM，插件作服务端
- 完整管理命令：状态查询、账号管理、会话踢出、**强制登录（顶号）**、审计日志
- LibreLogin 旧库迁移工具（BCrypt 哈希可直接复用）
- MiniMessage 文案，中英双语 `lang/zh_cn.yml` `lang/en_us.yml`
- SQLite（默认）/ MySQL 双数据库

## 运行环境

- Java 21+
- Velocity 3.5.1（API `3.5.1`）
- 可选：Geyser + Floodgate（基岩端）、SMTP 服务、QQ bot（见 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)）

## 安装

1. 从 Release 下载插件 Jar（或本地构建）。
2. 放入 Velocity 的 `plugins` 目录。
3. 启动代理端，首次启动会自动释放配置：
   - `plugins/velocityauth/config.yml`
   - `plugins/velocityauth/lang/*.yml`

### 代理端配置（`velocity.toml`）

登录成功后插件会让客户端**重连一次**以完成身份重写，1.20.5+ 客户端走 Transfer 包基本无感，
因此需要允许接收 Transfer：

```toml
[advanced]
accepts-transfers = true
```

`player-info-forwarding-mode` 按你的习惯设置（`modern` 需要与后端 `forwarding.secret` 一致）。

### 服务器划分

- **登录服必须是专用服**，不要在上面挂按 UUID 存数据的插件（未认证玩家在该服表现为连接名身份）
- 主服正常挂载；基岩端需要 Geyser + Floodgate

## 命令

### 玩家命令

| 命令 | 说明 | 可用状态 |
|---|---|---|
| `/register <账号> <密码> [确认密码]` | 注册（`gate=email` 时为 `/register <账号> <密码> <邮箱> [确认密码]`） | 未认证 |
| `/login <账号> <密码>` | 登录 | 未认证 |
| `/logout` | 退出登录 | 已认证 |
| `/changepassword <旧密码> <新密码>` | 修改密码 | 已认证 |
| `/verify <验证码>` / `/verify cancel` | 输入邮箱验证码 / 取消验证（**仅 email 渠道**） | — |
| `/bind <email\|qq> [值]` | 绑定邮箱或 QQ（`qq` 走群内发码） | 已认证 |
| `/unbind <email\|qq>` | 解绑（需先通过一次验证） | 已认证 |
| `/forgot <账号> [email\|qq]` | 找回密码 | 未认证 |
| `/resetpassword <新密码> <确认密码>` | 验证通过后重置密码 | — |

别名：`login`=`l`、`register`=`reg`、`changepassword`=`changepw`/`cp`、`resetpassword`=`resetpw`

### 管理命令

`/velocityauth`（别名 `/vauth`）

| 子命令 | 说明 |
|---|---|
| `status` | 运行状态（数据库、SMTP、bot 连接、在线会话、待验证会话、已配对客户端、基岩端） |
| `reload` | 重载配置与语言文件 |
| `account <账号> info` | 账号详情（绑定、身份数、上次子服、注册/登录时间） |
| `account <账号> setpassword <密码>` | 强制改密 |
| `account <账号> unbind <email\|qq>` | 强制解绑 |
| `account <账号> lock\|unlock` | 锁定 / 解锁（锁定会踢出在线会话） |
| `account <账号> delete` | 删除账号（级联删除身份与绑定） |
| `sessions list` | 列出在线已认证会话 |
| `sessions kick <账号>` | 踢出某账号的所有在线会话 |
| `forcelogin <账号> [--kick]` | **强制登录为指定账号**（顶号），`--kick` 先踢掉该账号现有会话 |
| `pair new` / `pair list` / `pair revoke <id>` | bot 配对码管理 |
| `client list` / `client revoke <id>` | 已配对 bot 客户端管理 |
| `migrate librelogin [--dry-run] [--file <路径>]` | 从 LibreLogin 的 SQLite 库迁移 |
| `migrate librelogin --url <JDBC URL> [--user <用户>] [--password <密码>]` | 从 LibreLogin 的 MySQL / MariaDB 库迁移 |

> 插件**不提供查看明文密码**：密码以 bcrypt 单向哈希存储，插件本就拿不到明文；
> 需要顶号时请用 `forcelogin`。

### 从 LibreLogin 迁移

LibreLogin 把 bcrypt 结果拆成三列存储（`algo=BCrypt-2A`、`salt` 单独一列、
`hashed_password` 只存 `cost$hash`，不含 `$2a$` 前缀），迁移时会按原算法重新拼回标准
bcrypt 串，因此**玩家可以继续使用原密码登录**；Argon2ID 等本插件不支持的算法会被标记为
需要重置（占位哈希 `!needs-reset`，可用 `/forgot` 或管理命令改密）。

```bash
# SQLite（LibreLogin 的默认小规模部署）
/vauth migrate librelogin --file plugins/librelogin/librepremium_data.db --dry-run

# MySQL / MariaDB（生产常见）
/vauth migrate librelogin --url jdbc:mysql://127.0.0.1:3306/librelogin --user root --password *** --dry-run

# 确认统计无误后去掉 --dry-run 真正写入
```

迁移**不会自动执行**；`--dry-run` 只统计不写入；源库以只读方式打开。
若是 `.sql` 备份文件，先导入到任一 MySQL/MariaDB（`mysql -u root -p librelogin < backup.sql`）
再迁移，或导入后导出为 SQLite 用 `--file`。

## 权限

| 权限 | 说明 |
|---|---|
| `velocityauth.admin` | 使用 `/vauth`（不含强制登录） |
| `velocityauth.admin.forcelogin` | 使用 `/vauth forcelogin`（独立权限，不与 `admin` 合并） |

控制台默认拥有全部权限。

## 配置概览

`config.yml` 主要分区：

- `language`：`zh_cn` / `en_us`
- `database`：`sqlite`（默认）或 `mysql`
- `servers`：`login` 登录服、`default` 认证后默认服、`allowed` 允许进入的子服白名单、`remember-last` 是否记忆上次子服
- `auth.account-name`：账号名长度与正则；`auth.password`：密码长度；`auth.bcrypt-cost`
- `auth.session`：会话保持（`enabled` 默认 `false`、`ttl-minutes`、`bind-to-ip`）
- `auth.premium`：正版分支（`enabled`、`keep-real-uuid`、`require-password`、`name-conflict`）
- `registration.gate`：注册门槛 `none` / `email` / `qq`
- `binding.email`：`max-accounts-per-email`、`domain.mode`（`off`/`whitelist`/`blacklist`）与 `domain.list`
- `binding.qq`：`max-accounts-per-qq`
- `verification`：码长、`ttl-seconds`、`max-attempts`、按 IP / 账号的限流
- `smtp`：内置 SMTP（`enabled` 默认 `false`；QQ 邮箱填**授权码**而非登录密码）
- `qqbot`：bot 通道（`listen`、`port`、`group-id`、`require-group-member`、`pairing.*`）
- `proxy`：`address` / `port`，用于 `transferToHost` 指向代理自身（留空则用客户端连接时用的地址）
- `security`：bot 握手超时、握手帧允许的时间偏差

完整默认值与注释见 [`config.example.yml`](config.example.yml)（与 `src/main/resources/config.yml` 模板一致）。

## QQ bot 通道

插件监听一个 WebSocket 端口，bot 主动连接，双方用 Ed25519 互证身份、X25519 + AES-256-GCM 加密会话，
不依赖 TLS。协议契约、帧格式、密钥派生、密封帧、业务消息与**可执行测试向量**见：

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) —— bot 侧实现的唯一权威来源
- [`docs/protocol/vectors/`](docs/protocol/vectors) —— 双方实现都必须逐字节通过
- [`docs/protocol/reference/node/`](docs/protocol/reference/node) —— Node.js 参考实现（零依赖，含向量自检）

配对有两种方式：

- 控制台执行 `/vauth pair new`，把 `pairing_id` 与 `code` 配到 bot 侧（推荐）
- 无控制台部署（容器等）时在 `qqbot.pairing.bootstrap-code` 里预置一次性配对码

## 注意事项

- **登录服必须专用**：未认证玩家在登录服表现为连接名身份，挂了按 UUID 存数据的插件会导致数据错乱
- 登录成功后会有一次重连；1.20.5 以下客户端会断开并提示重新连接（`proxy.address` 建议显式配置）
- 未认证玩家被完全隔离在登录服：聊天事件不可取消，因此未认证玩家不会进入任何后端主服
- 基岩端重写为账号身份后，皮肤是否保留取决于 Floodgate 注入的 `textures` 属性，**需在装有 Geyser + Floodgate 的环境实测**
- QQ 渠道的验证码**只能在群里发**：游戏内 `/verify` 对 QQ 会话无效（否则公开群里的码可被任何人用来绕过"证明持有该 QQ"）
- 邮箱域名按 `@` 之后的域名**精确匹配**，不做子域名后缀匹配（避免 `mail.qq.com` 绕过白名单）
- 会话保持默认关闭：国内 NAT 共享 IP 普遍，同 IP 免密存在被他人利用的风险
- 正版名冲突采用先来先得：名称一旦被某个账号注册，其他人（包括正版玩家）无法再用该名称
- 以下文件**不要提交到版本库**：`config.yml`（含 SMTP 授权码、数据库密码）、`keys/`（Ed25519 私钥与已配对公钥）、`*.db`（玩家数据）

## 本地构建

```bash
./gradlew.bat clean build
```

构建产物位于：

- `build/libs/VelocityAuth-<version>.jar`（含依赖，放入 `plugins` 即可）

单元测试（含协议向量与 bot 通道集成测试）：

```bash
./gradlew.bat test
```

## License

MIT License. See [LICENSE](LICENSE).
