# VelocityAuth 设计文档

> Velocity 代理端认证插件 · 账号体系 + 多平台共用 + 可选验证门槛 + 邮箱/QQ 找回
>
> 状态：设计稿 v1（已评审）
> 目标读者：插件开发者、bot 侧开发者、运维
>
> 配套文档：[`IMPLEMENTATION.md`](IMPLEMENTATION.md)（开发须知）· [`PROTOCOL.md`](PROTOCOL.md)（bot 协议契约）· [`README.md`](README.md)（文档索引）

---

## 目录

1. [目标与非目标](#1-目标与非目标)
2. [术语](#2-术语)
3. [整体架构](#3-整体架构)
4. [核心机制](#4-核心机制)
5. [统一验证会话](#5-统一验证会话)
6. [绑定与找回](#6-绑定与找回)
7. [数据模型](#7-数据模型)
8. [配置设计](#8-配置设计)
9. [命令与权限](#9-命令与权限)
10. [消息与 i18n](#10-消息与-i18n)
11. [bot 协议契约](#11-bot-协议契约)
12. [安全设计](#12-安全设计)
13. [部署说明](#13-部署说明)
14. [风险与未决点](#14-风险与未决点)
15. [实施里程碑](#15-实施里程碑)

---

## 1. 目标与非目标

### 目标

- **账号名 + 密码**的认证体系，账号名**独立于游戏 ID**
- 玩家可以用**任意游戏 ID** 登录同一账号，登录后全服统一表现为该账号身份
- **Java 端与基岩端（Geyser/Floodgate）共用同一账号**
- 注册门槛可配置：**无验证 / 邮箱验证 / QQ 验证**
- 账号可绑定**邮箱**与 **QQ**，用于**找回密码**
- 邮箱域名可限制（如仅允许 `@qq.com`）
- **记忆上次所在子服**，登录后自动回到上次退出的子服
- 提供与 QQ bot 的安全 WebSocket 通道

### 非目标（本期不做）

- 账号名与显示名分离（昵称系统）
- 与 Serveroute 的路由联动
- 2FA / TOTP
- 多代理共享会话（Redis）
- 邮箱找回之外的其它找回方式（如密保问题）

---

## 2. 术语

| 术语 | 含义 |
|---|---|
| **账号名** | 玩家注册的规范身份，唯一、不区分大小写，如 `test1` |
| **规范身份（canonical profile）** | 账号对应的 `name` + `uuid`，全服统一 |
| **连接名** | 玩家本次连接使用的 Minecraft ID，如 `test2` |
| **登录服（lobby）** | 未认证玩家停留的服务器 |
| **主服（main）** | 认证成功后进入的服务器 |
| **验证会话** | 一次性的"证明身份"流程，见第 5 节 |
| **绑定** | 账号与邮箱/QQ 的关联记录 |
| **pending binding** | 登录成功后、重连前，临时保存的 `连接名 → 账号` 映射 |

---

## 3. 整体架构

### 3.1 组件

```
                    ┌──────────────────────────────┐
   玩家(Java)  ────▶│                              │
                    │        Velocity 代理          │
   玩家(基岩)  ────▶│   + VelocityAuth 插件         │
     Geyser         │   + Floodgate（基岩身份）      │
                    │                              │
                    └───┬───────────┬──────────┬───┘
                        │           │          │
                   ┌────▼───┐  ┌────▼───┐  ┌───▼────────────┐
                   │ 登录服  │  │  主服   │  │  AstrBot (bot) │
                   │ lobby  │  │  main  │  │   (WS 客户端)   │
                   └────────┘  └────────┘  └───┬────────────┘
                                               │
                                          ┌────▼────┐
                                          │  QQ 群   │
                                          └─────────┘
                        │
                   ┌────▼────┐
                   │  SMTP    │  发邮箱验证码
                   └─────────┘
```

### 3.2 玩家进服全流程（时序）

```
玩家            代理/插件                登录服        主服        bot        SMTP
 │                 │                       │            │           │          │
 │ 连接(ID=test2)  │                       │            │           │          │
 ├────────────────▶│                       │            │           │          │
 │                 │ 查 identities / 会话    │            │           │          │
 │                 │ 未认证 → 路由到 lobby    │            │           │          │
 │                 ├──────────────────────▶│            │           │          │
 │                 │                       │            │           │          │
 │ /login test1 pw │                       │            │           │          │
 ├────────────────▶│ 校验通过               │            │           │          │
 │                 │ 记录 pending binding   │            │           │          │
 │                 │ (test2,ip) → account1  │            │           │          │
 │  要求重连        │                       │            │           │          │
 │◀────────────────┤                       │            │           │          │
 │                 │                       │            │           │          │
 │ 重连(ID=test2)  │                       │            │           │          │
 ├────────────────▶│ 命中 pending binding   │            │           │          │
 │                 │ 重写 profile → test1   │            │           │          │
 │                 │ 已认证 → 路由到 main    │            │           │          │
 │                 ├───────────────────────────────────▶│           │          │
```

> 关键点：**profile 重写在登录握手阶段完成**，因此客户端自己也会看到账号名（`test1`），不是"服务端认为你是 test1、客户端以为自己是 test2"。

---

## 4. 核心机制

### 4.1 身份模型

**账号即规范身份。**

- `name` = 账号显示名（保留注册时大小写）
- `uuid` = `UUID.nameUUIDFromBytes(("OfflinePlayer:" + 账号名小写).getBytes(UTF_8))`
  - 即 `GameProfile.forOfflinePlayer()` 的算法，用**小写规范名**保证大小写不敏感账号得到同一 UUID
- 任何连接名登录该账号后，全服看到的都是这个 `name` + `uuid`

**好处**：LuckPerms、经济、领地、地皮等所有按 UUID 记录的插件天然统一，无需适配。

### 4.2 profile 重写

Velocity 中唯一能改变"名字 + UUID"的公开 API 是 `GameProfileRequestEvent#setGameProfile(GameProfile)`，它在**登录握手阶段触发一次**（`PreLoginEvent` 之后、玩家对象创建之前）。

```
GameProfileRequestEvent 触发
  ├─ 命中 pending binding ？ → setGameProfile(账号规范 profile)   [已认证]
  ├─ 命中会话保持（同 IP）？ → setGameProfile(账号规范 profile)   [已认证]
  ├─ 正版玩家（配置允许）？  → 保留 Mojang 真实 profile           [已认证]
  └─ 其它                    → 不改，标记未认证
```

**注意**：`Player#setGameProfileProperties()` 只能改属性（皮肤等），**改不了 name/UUID**；运行期改身份需要反射内部字段，本设计**不采用**。

### 4.3 重连 / Transfer 绑定流程

因为 `GameProfileRequestEvent` 只在登录时触发、且此时还不知道玩家要登录哪个账号，采用**登录后重连**的方案：

1. 玩家在登录服执行 `/login test1 <password>`
2. 校验通过 → 写入 pending binding：
   ```
   key   = (连接名小写, 玩家 IP)
   value = { account_id, expiresAt = now + 90s }
   ```
3. 让客户端重连：
   - 客户端支持 Transfer 包（1.20.5+）→ `player.transferToHost(代理自身地址)`，几乎无感
   - 否则 → `player.disconnect(提示"登录成功，请重新连接")`
4. 重连时 `GameProfileRequestEvent` 命中 pending binding → 重写 profile → 已认证 → 路由到主服

**pending binding 消费即删除**，TTL 90 秒，避免被他人复用。

### 4.4 未认证隔离

| 手段 | 说明 |
|---|---|
| 路由 | `PlayerChooseInitialServerEvent`：未认证只能去登录服 |
| 命令 | `CommandExecuteEvent`：未认证时拦截除认证相关命令外的一切命令 |
| 跳服 | `ServerPreConnectEvent`：未认证时**拒绝连接非登录服的任何服务器**（兜底） |

> **兜底说明**：登录服内的传送门、NPC 插件、其它插件触发的跳服不会被命令拦截覆盖，因此额外用 `ServerPreConnectEvent` 兜底。这是本期要实现的项。

**部署要求**：登录服应当是**专用服务器**，不要在其上挂按 UUID 存数据的插件（未认证玩家在该服表现为连接名身份）。

### 4.5 会话保持

登录成功后可记住会话：

```
login_sessions: (account_id, ip, expires_at)
```

下次从**同一 IP** 连接且会话未过期 → 直接重写 profile 并认证（跳过 `/login`）。

- **默认关闭**（国内 NAT 共享 IP 普遍，免密风险高），由服主按需开启
- 开启后 TTL 可配（默认 30 分钟）
- `bind-to-ip: true` 时严格绑定 IP
- 安全权衡：NAT 共享 IP 环境下存在被他人利用的风险；如需开启，建议缩短 TTL 或仅对固定 IP 放行

### 4.6 正版玩家分支

- 代理 `online-mode: true`，或通过其它方式完成 Mojang 校验的正版玩家
- **保留真实 Mojang profile**（真实 UUID、真实皮肤），**不重写**
- 免密直通，可配置是否仍要求 `/login`
- **正版名与盗版账号名冲突：先来后到（`name-conflict: first-come`，默认）**
  - 账号名归**第一个注册者**所有，与是否正版无关
  - 若某名称已被离线账号注册，则真正的正版玩家用该名称连接时**被拒绝进入**（提示名称已被占用）
  - 反之，若正版玩家先注册，离线玩家也无法再占用该名称
  - 后果：可能出现正版玩家用不了自己 Mojang 名的情况，这是"先来后到"的必然代价
  - 备选模式 `premium-priority`：正版名始终优先，离线玩家不能占用已知正版名（需要外部正版名单）

### 4.7 Geyser / Floodgate（基岩端）

- 基岩玩家经 Geyser + Floodgate 进入，身份为 `.Steve` + Floodgate UUID
- 通过 Floodgate API 识别：`FloodgateApi.getInstance().isFloodgatePlayer(uuid)`
- 基岩玩家同样可用 `/login test1 <password>` → profile 重写为账号身份 → **与 Java 端共用账号数据**
- 两个必须实测的点：
  1. **监听器顺序**：Floodgate 也用 `GameProfileRequestEvent` 注入 profile，与本次重写存在执行顺序竞争，需验证最终生效结果
  2. **基岩皮肤**：重写为 Java UUID 后，后端可能不再识别为 Bedrock，皮肤会丢；尝试携带 `textures` 属性，能否生效需实测

### 4.8 记忆上次所在子服

认证成功后，把玩家**丢回上次退出时所在的子服**。

**记录时机**

- `ServerPostConnectEvent`：玩家每次切服时更新 `last_server`
- `DisconnectEvent`：玩家断开时更新 `last_server`

**恢复时机**

- `PlayerChooseInitialServerEvent`：认证通过后，若 `remember-last` 开启且 `last_server` 在允许列表内且服务器存在 → 进入 `last_server`；否则进入 `default`

**规则**

| 场景 | 行为 |
|---|---|
| `last_server` 为空（首次登录） | 进入 `default` |
| `last_server` = 登录服 | 忽略，进入 `default` |
| `last_server` 已不在 Velocity 注册表 | 进入 `default` |
| `last_server` 不在 `servers.allowed` 白名单 | 进入 `default` |
| `remember-last: false` | 始终进入 `default` |

**配置**（见 8.1）：`servers.login` / `servers.default` / `servers.allowed` / `servers.remember-last`

**存储**：`accounts.last_server`（见 7.1）

---

## 5. 统一验证会话

### 5.1 抽象

所有需要"证明身份"的场景复用同一个机制：

```
VerificationSession
  ├─ id
  ├─ purpose : register | bind_email | bind_qq | recover
  ├─ channel : email | qq
  ├─ code    : 一次性、TTL、限次
  ├─ context : 账号名 / 连接名 / IP / 待写入的数据（如待哈希的密码）
  └─ created_at / expires_at / attempts
```

**存储**：内存（短生命周期），不落库。重启即失效。

### 5.2 purpose × channel 矩阵

| purpose | channel=email | channel=qq |
|---|---|---|
| `register` | 邮箱收码 → 游戏内输码 → 建号 | 游戏内出码 → 群里发 → 建号 |
| `bind_email` | 邮箱收码 → 游戏内输码 → 绑定 | — |
| `bind_qq` | — | 游戏内出码 → 群里发 → 绑定 |
| `recover` | 邮箱收码 → 游戏内输码 → 改密 | 游戏内出码 → 群里发 → 改密 |

### 5.3 email 渠道

1. 生成 6 位数字码（可配长度）
2. 通过内置 SMTP 发送到目标邮箱
3. 玩家在游戏内输入码
4. 校验：码匹配 + 未过期 + 未超限次 → 会话完成

邮件内容（i18n 模板）：

```
主题：【<服务器名>】验证码
正文：
  你的验证码是：123456
  5 分钟内有效，请勿泄露给他人。
  如果这不是你本人的操作，请忽略本邮件。
```

### 5.4 qq 渠道

1. 生成 6–8 位码（建议数字+字母，降低碰撞）
2. **游戏内**提示：`请在 QQ 群 <群号> 发送：<码>`
   - 整条验证消息做成 **Adventure 点击复制**（`ClickEvent.copyToClipboard`），玩家点一下即可复制，方便粘贴到 QQ
3. 玩家在群内发送该码
4. bot 监听群消息 → 命中 → 校验群成员身份 → 回报插件
5. 插件校验后 → 在**游戏内**继续后续操作（建号 / 绑定 / 改密）

> **设计要点**：验证码在公开群里发送，本身不是敏感信息；真正的敏感操作（设密码、改密码）全部在游戏内完成。玩家无需添加 bot 好友。

### 5.5 生命周期与限流

| 项 | 默认 |
|---|---|
| 码长度 | 6（email 纯数字；qq 数字+字母） |
| TTL | 300 秒 |
| 最大尝试次数 | 5 |
| 单 IP 每分钟发起上限 | 5 |
| 单账号每分钟发起上限 | 3 |

超时 / 超限 / 主动取消 → 会话作废。

---

## 6. 绑定与找回

### 6.1 邮箱绑定

- 命令：`/bind email <邮箱>`
- 流程：校验域名规则 → 创建 `purpose=bind_email, channel=email` 会话 → 发码 → 游戏内输码 → 写入 `bindings`
- **基数限制**：`max-accounts-per-email`（默认 1）——同一邮箱最多绑定的账号数
- **域名限制**：
  - `mode: off | whitelist | blacklist`
  - `whitelist`：仅允许列表内域名（如 `["qq.com"]`）
  - `blacklist`：拒绝列表内域名（如临时邮箱域名）
  - 匹配规则：取 `@` 后的域名做**精确匹配**（不做子域名后缀匹配，避免 `mail.qq.com` 绕过）

### 6.2 QQ 绑定

- 命令：`/bind qq`
- 流程：创建 `purpose=bind_qq, channel=qq` 会话 → 游戏内出码 → 群里发 → bot 回报 → 写入 `bindings`
- **基数限制**：`max-accounts-per-qq`（默认 1）
- 与注册门槛**解耦**：任何注册模式下都可单独绑定 QQ

### 6.3 找回密码

- 命令：`/forgot <account>`
- 流程：
  1. 查账号是否存在 → **无论存在与否都返回相同提示**（防账号枚举）
  2. 存在且有绑定：列出可用渠道（邮箱 / QQ）
  3. 玩家选择渠道 → 创建 `purpose=recover` 会话
  4. 验证成功 → 允许在游戏内设置新密码
  5. 改密成功后可自动完成登录（走 4.3 的重连流程）
- 限流：按 IP + 账号，防止爆破

### 6.4 解绑

- `/unbind <email|qq>` → 需先通过一次验证（复用验证会话）
- 管理命令可强制解绑

---

## 7. 数据模型

### 7.1 表结构（SQLite / MySQL 通用）

```sql
CREATE TABLE accounts (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          TEXT    NOT NULL,                 -- 显示名（保留大小写）
  name_lower    TEXT    NOT NULL UNIQUE,          -- 规范名（小写，唯一）
  uuid          TEXT    NOT NULL UNIQUE,          -- 规范 UUID
  password_hash TEXT    NOT NULL,                 -- bcrypt
  status        TEXT    NOT NULL DEFAULT 'active',-- active | locked
  premium_uuid  TEXT,                             -- 正版真实 UUID（若有）
  last_server   TEXT,                             -- 上次所在子服（记忆功能）
  created_at    INTEGER NOT NULL,
  last_login_at INTEGER,
  last_ip       TEXT
);

CREATE TABLE identities (                          -- 连接名/基岩身份 → 账号
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id  INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  kind        TEXT    NOT NULL,                   -- java | bedrock
  identifier  TEXT    NOT NULL,                   -- 连接名小写 / Floodgate UUID
  first_seen  INTEGER NOT NULL,
  last_seen   INTEGER NOT NULL,
  UNIQUE (kind, identifier)
);

CREATE TABLE bindings (                            -- 邮箱 / QQ 绑定
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id  INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  type        TEXT    NOT NULL,                   -- email | qq
  identifier  TEXT    NOT NULL,                   -- email 小写 / qq 号
  verified_at INTEGER NOT NULL,
  created_at  INTEGER NOT NULL,
  UNIQUE (type, identifier, account_id)
);
CREATE INDEX idx_bindings_identifier ON bindings (type, identifier);

CREATE TABLE login_sessions (                      -- 会话保持
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  ip         TEXT    NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX idx_login_sessions_lookup ON login_sessions (account_id, ip);

CREATE TABLE audit_log (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  at         INTEGER NOT NULL,
  account_id INTEGER,
  action     TEXT    NOT NULL,                    -- register|login|logout|bind|unbind|recover|...
  ip         TEXT,
  detail     TEXT
);
CREATE INDEX idx_audit_log_at ON audit_log (at);
```

### 7.2 内存态（不落库）

| 结构 | 内容 | 生命周期 |
|---|---|---|
| `pendingBindings` | `(连接名, ip) → (account_id, expiresAt)` | 90 秒 |
| `verificationSessions` | 见第 5 节 | TTL |
| `loginRateLimiter` | 按 IP / 账号的计数窗口 | 滑动窗口 |

### 7.3 LibreLogin 迁移

若需迁移旧账号：

- 旧账号以"连接名 = 账号名"为主 → 直接映射为 `accounts.name`
- `identities` 用旧记录填充
- 密码哈希若为 BCrypt 可直接复用；否则标记为"需重置密码"
- 迁移脚本作为独立子命令，**不自动执行**

---

## 8. 配置设计

> **仓库里只放模板**：本仓库中的 `config.yml` 是**带占位符的默认模板**（无任何真实凭据），首次启动时释放到 `plugins/velocityauth/config.yml`。
> 真实配置（SMTP 授权码、QQ 群号、数据库密码、已配对公钥等）**只存在于服务器上，不进版本库**，见 8.3。

### 8.1 配置文件 `config.yml`

```yaml
# ============ VelocityAuth ============
language: zh_cn            # zh_cn | en_us

database:
  type: sqlite             # sqlite | mysql
  sqlite:
    file: velocityauth.db
  mysql:
    host: 127.0.0.1
    port: 3306
    database: velocityauth
    username: root
    password: ""

servers:
  login: lobby             # 登录服：未认证玩家停留的服务器
  default: main            # 认证成功后默认进入的服务器
  allowed:                 # 允许进入的子服（需为 Velocity 已注册的服务器名）
    - main                 #   记忆功能只会在这份白名单内恢复
    - survival
  remember-last: true      # 认证后自动回到上次退出时所在的子服

auth:
  account-name:
    min-length: 3
    max-length: 16
    pattern: "^[A-Za-z0-9_]+$"
    case-insensitive: true
  password:
    min-length: 6
    max-length: 64
  bcrypt-cost: 10
  session:
    enabled: false                   # 默认关闭：国内 NAT 普遍，同 IP 免密风险高
    ttl-minutes: 30
    bind-to-ip: true
  premium:
    enabled: true
    keep-real-uuid: true
    require-password: false        # 正版玩家是否仍需 /login
    name-conflict: first-come      # first-come | premium-priority

registration:
  gate: none                       # none | email | qq
  # gate=email 时是否强制绑定邮箱（一般恒为 true）
  # gate=qq 时是否强制绑定 QQ

binding:
  email:
    enabled: true
    max-accounts-per-email: 1
    domain:
      mode: whitelist              # off | whitelist | blacklist
      list: ["qq.com"]
  qq:
    enabled: true
    max-accounts-per-qq: 1

verification:
  email-code-length: 6
  qq-code-length: 6
  ttl-seconds: 300
  max-attempts: 5
  rate-limit:
    per-ip-per-minute: 5
    per-account-per-minute: 3

smtp:
  enabled: false                     # 需要邮箱验证/找回时再开
  host: smtp.qq.com
  port: 465
  ssl: true
  username: ""                       # 你的邮箱，如 example@qq.com
  password: ""                       # QQ 邮箱需填授权码，不是登录密码
  from: "VelocityAuth <example@qq.com>"
  timeout-seconds: 10

qqbot:
  enabled: false                     # 需要 QQ 验证/绑定时再开
  listen: "0.0.0.0"
  port: 8765
  group-id: ""                       # 验证用的 QQ 群号
  require-group-member: true
  pairing:
    ttl-seconds: 600
    max-attempts: 5
```

### 8.2 目录结构

```
plugins/velocityauth/
  config.yml
  lang/
    zh_cn.yml
    en_us.yml
  keys/
    identity.json          # 插件自身 Ed25519 密钥对
    paired_clients.json    # 已配对的 bot 公钥
  velocityauth.db          # SQLite
```

### 8.3 敏感信息与版本库

仓库**只提交模板与源码**，以下内容一律不进版本库：

| 路径 | 原因 |
|---|---|
| `config.yml` | 含 SMTP 授权码、QQ 群号、数据库密码 |
| `keys/` | Ed25519 私钥、已配对 bot 公钥 |
| `*.db` / `*.db-journal` | 玩家账号数据 |

仓库提供：

- `src/main/resources/config.yml` —— **默认模板**，敏感字段留空、功能开关默认关闭
- `config.example.yml` —— 与模板一致的示例（便于直接阅读）
- `.gitignore` —— 排除上述路径

`.gitignore` 示例：

```gitignore
# 构建产物
build/
.gradle/
bin/
*.class

# 运行时数据（绝不提交）
config.yml
keys/
*.db
*.db-journal
*.log

# IDE
.idea/
*.iml
.vscode/
```

> 说明：插件从 jar 内释放默认 `config.yml` 到数据目录；开发时若在项目根目录直接跑，需自行确保该文件被 `.gitignore` 覆盖。

---

## 9. 命令与权限

### 9.1 玩家命令

| 命令 | 说明 | 可用状态 |
|---|---|---|
| `/register <账号> <密码> [确认密码]` | 注册 | 未认证 |
| `/login <账号> <密码>` | 登录 | 未认证 |
| `/logout` | 退出登录 | 已认证 |
| `/changepassword <旧> <新>` | 修改密码 | 已认证 |
| `/bind <email\|qq> [值]` | 绑定邮箱或 QQ（`email` 需带地址，`qq` 走群验证） | 已认证 |
| `/unbind <email\|qq>` | 解绑 | 已认证（需验证） |
| `/forgot <账号>` | 找回密码 | 未认证 |

### 9.2 管理命令

`/velocityauth`（别名 `/vauth`），权限 `velocityauth.admin`

| 子命令 | 说明 |
|---|---|
| `reload` | 重载配置与语言文件 |
| `status` | 运行状态（数据库、SMTP、bot 连接、待处理会话数） |
| `account <账号> info` | 查看账号详情（绑定、身份、上次子服、会话） |
| `account <账号> setpassword <密码>` | 强制改密 |
| `account <账号> unbind <email\|qq>` | 强制解绑 |
| `account <账号> lock\|unlock` | 锁定 / 解锁 |
| `account <账号> delete` | 删除账号 |
| `forcelogin <账号> [--kick]` | **管理员强制登录为指定账号**（顶号），见 9.3 |
| `sessions list` | 列出当前已认证会话（玩家、账号、IP） |
| `sessions kick <账号>` | 踢出某账号的所有在线会话 |
| `pair new [--ttl 10m]` | 生成 bot 配对码 |
| `pair list` | 列出待配对记录 |
| `pair revoke <pairingId>` | 撤销配对 |
| `client list` | 列出已配对的 bot 客户端 |
| `client revoke <clientId>` | 撤销 bot 信任 |

> **关于"查看密码"**：密码以 bcrypt 单向哈希存储，**插件无法得知明文**，因此不提供查看明文密码的能力。取而代之的是下面的强制登录。

### 9.3 管理员强制登录（顶号）

用于管理员需要以某个玩家账号身份进行操作（如处理卡号、取回物品）的场景。

```
/vauth forcelogin <账号> [--kick]
```

流程：

1. 校验执行者权限（独立权限 `velocityauth.admin.forcelogin`）
2. 若 `--kick`：踢出该账号当前所有在线会话（见 `sessions kick`）
3. 为**执行者当前连接**创建 pending binding（指向目标账号），走 4.3 的重连流程
4. 重连后执行者即表现为该账号身份
5. 写审计日志：`action=forcelogin`，记录执行者、目标账号、IP

**安全约束**：

- 需要独立权限，不与 `velocityauth.admin` 合并
- 强制登录状态下，管理员操作**全部以目标账号身份记录**，请配合审计

---

## 10. 消息与 i18n

- 语言文件：`lang/zh_cn.yml`、`lang/en_us.yml`
- 结构：按场景分组，支持占位符与 MiniMessage
- 所有面向玩家的文本均走 i18n，不硬编码

```yaml
# lang/zh_cn.yml（节选）
prefix: "<gold>[认证]</gold> "
command:
  login:
    usage: "<red>用法：/login <账号> <密码></red>"
    success: "<green>登录成功，正在进入服务器…</green>"
    wrong-password: "<red>账号或密码错误。</red>"
    already-authenticated: "<red>你已经登录了。</red>"
  register:
    success: "<green>注册成功，正在进入服务器…</green>"
    name-taken: "<red>该账号名已被占用。</red>"
verification:
  email-sent: "<yellow>验证码已发送至 {email}，请在 5 分钟内输入。</yellow>"
  qq-instruction: "<yellow>请在 QQ 群 {group} 发送：{code}</yellow>"
  wrong-code: "<red>验证码错误，还可尝试 {remaining} 次。</red>"
  expired: "<red>验证已过期，请重新发起。</red>"
recovery:
  request-received: "<yellow>如果该账号存在，我们已向绑定的渠道发送了验证方式。</yellow>"
```

---

## 11. bot 协议契约

> **完整契约见 [`PROTOCOL.md`](PROTOCOL.md)**（含帧格式、规范化、签名输入、密钥派生、密封帧、业务消息、错误码、规范性安全要求与测试向量）。
> 本节仅为概要，字段以实现时 `PROTOCOL.md` 为准。

### 11.1 传输与角色

- WebSocket，**插件作服务端**，bot 作客户端
- 握手阶段：JSON 明文 + Ed25519 签名
- 会话阶段：`kind:"sealed"` 帧，AES-256-GCM 加密

### 11.2 握手

**阶段一 · 一次性配对**（仅首次）

```
pair.hello      bot -> plugin   { pairing_id, client_id, client_key_id,
                                  client_public_key, capabilities }
pair.challenge  plugin -> bot   { pairing_id, challenge_id, expires_at,
                                  server_id, server_key_id, server_public_key,
                                  server_nonce, selected_v, capabilities }
pair.prove      bot -> plugin   { pairing_id, challenge_id, hello_id,
                                  ...全部绑定字段... }
pair.complete   plugin -> bot   { ok, session_id, client_key_fingerprint,
                                  server_key_fingerprint, server_time, capabilities }
```

- 配对码由 `/velocityauth pair new` 生成：高熵（≥128 bit）、一次性、TTL、限次、仅存内存
- 配对码**永不上线**，而是作为签名 transcript 的一部分，双方互证"都知道配对码"

**阶段二 · 每次连接**

```
auth.hello      bot -> plugin   { client_id, client_key_id, client_public_key_sha256,
                                  client_ephemeral_key, client_nonce, capabilities }
auth.challenge  plugin -> bot   { challenge_id, expires_at, server_id, server_key_id,
                                  server_public_key_sha256, server_ephemeral_key,
                                  server_nonce, selected_v, capabilities }
auth.prove      bot -> plugin   { challenge_id, hello_id, ...全部绑定字段... }
auth.result     plugin -> bot   [sealed]  { ok, session_id, server_time, capabilities }
auth.confirm    bot -> plugin   [sealed]  { session_id }
```

### 11.3 加密

```
shared    = X25519(本地临时私钥, 对方临时公钥)          # 每连接新生成临时密钥 → 前向保密
salt      = SHA-256("VELOCITYAUTH-V1-SALT" || client_nonce || server_nonce)
prk       = HKDF-Extract(salt, shared)
key_c2s   = HKDF-Expand(prk, "velocityauth c2s", 32)
key_s2c   = HKDF-Expand(prk, "velocityauth s2c", 32)

nonce(12B) = 4B epoch || 8B seq（单调计数器，严禁随机）
AAD        = "VELOCITYAUTH-SEAL-V1\nv=1\nseq=<seq>\n"
```

**必须遵守**：每方向独立密钥、seq 严格递增且拒绝重放、临时密钥每连接更新。

### 11.4 会话消息（均为 sealed 帧）

| 消息 | 方向 | 载荷 |
|---|---|---|
| `verify.watch` | plugin → bot | `{ code, group_id, ttl }` |
| `verify.watch.ack` | bot → plugin | `{ code, accepted }` |
| `verify.matched` | bot → plugin | `{ code, qq, group_id, group_nick, is_group_member, matched_at }` |
| `verify.matched.ack` | plugin → bot | `{ code, consumed }`（bot 收到后可撤回群消息） |
| `verify.cancel` | plugin → bot | `{ code }` |
| `ping` / `pong` | 双向 | `{}` |
| `error` | 双向 | `{ code, message }` |

---

## 12. 安全设计

### 12.1 威胁与对策

| 威胁 | 对策 |
|---|---|
| 密码爆破 | bcrypt cost 10；登录/注册按 IP + 账号限流 |
| 验证码爆破 | 高熵码、TTL 300s、限次 5、限流 |
| 账号枚举 | `/forgot` 与 `/register` 返回统一提示；响应时间尽量一致 |
| 会话劫持 | 会话绑定 IP；TTL 可配 |
| 他人 QQ 注册 | 强制群成员校验；单 QQ 绑定数受限 |
| 邮箱域名绕过 | 精确匹配 `@` 后域名，不做后缀匹配 |
| 中间人（bot 通道） | 配对码互证 + Ed25519 身份 + X25519 前向保密 + AES-GCM |
| 重放攻击 | 握手 challenge_id + nonce；密封帧 seq 严格递增 |
| 群消息泄露验证码 | 验证码非敏感；敏感操作全部在游戏内完成 |

### 12.2 密钥与凭据

- 插件 Ed25519 私钥存 `keys/identity.json`，建议文件权限 `600`
- bot 公钥 pin 在 `keys/paired_clients.json`；指纹不匹配直接拒绝
- SMTP 凭据存于 `config.yml`，**不要提交到版本库**
- 日志中**绝不记录**明文密码、验证码、配对码

### 12.3 审计

`audit_log` 记录：注册、登录成功/失败、登出、绑定、解绑、找回、管理操作，含时间、账号、IP、详情。

---

## 13. 部署说明

1. **代理配置**
   - `online-mode` 按需；基岩端需 Geyser + Floodgate
   - 玩家信息转发（`player-info-forwarding-mode`）需与后端一致
2. **服务器划分**
   - 登录服专用，不挂按 UUID 存数据的插件
   - 主服正常
3. **后端 `enforce-secure-profile`**
   - 若采用重连方案，profile 在登录阶段已重写，客户端与代理一致，一般无需关闭；**若后续改用反射方案则必须关闭**
4. **bot 通道**
   - 插件监听端口需对 bot 可达；虽已应用层加密，仍建议限制来源 IP
5. **SMTP**
   - QQ 邮箱需使用**授权码**而非登录密码

---

## 14. 风险与未决点

| # | 风险 | 影响 | 处置 |
|---|---|---|---|
| R1 | 登录服传送门 / 其它插件跳服旁路 | 未认证玩家可能进主服 | **本期处理**：`ServerPreConnectEvent` 兜底 |
| R2 | Floodgate 与 profile 重写监听器顺序 | 基岩端重写可能不生效 | 上线前实测；必要时在 `ServerPostConnectEvent` 二次校正 |
| R3 | 基岩皮肤丢失 | 基岩玩家改用 Java 皮肤 | 尝试携带 `textures`；不行则接受 |
| R4 | `transferToHost` 需要 1.20.5+ | 老客户端体验为断线重连 | 已设计 fallback |
| R5 | 正版名与盗版账号名冲突 | 正版玩家可能用不了自己的名字 | **先来后到，已确认接受**；备选 `premium-priority` |
| R6 | NAT 共享 IP 会话劫持 | 同 IP 他人免密登录 | **默认关闭会话保持**（国内 NAT 普遍） |
| R7 | 邮箱域名白名单被绕过 | 非目标邮箱注册 | 精确匹配域名；可加二次校验 |
| R8 | 登录服其它插件看到连接名身份 | 数据错乱 | 部署要求：登录服专用 |
| R9 | 会话/验证态仅存内存 | 代理重启后失效 | 可接受；必要时后续落库 |
| R10 | 多代理部署无共享会话 | 多代理间不共享登录态 | 本期不做 |

---

## 15. 实施里程碑

| 里程碑 | 内容 | 依赖 |
|---|---|---|
| **M1 骨架** | Gradle 工程、插件元数据、YAML 配置加载、i18n、日志 | — |
| **M2 数据层** | 表结构、迁移、DAO、SQLite/MySQL 适配 | M1 |
| **M3 认证核心** | 注册/登录、bcrypt、profile 重写、重连绑定、未认证隔离、记忆上次子服、命令 | M2 |
| **M4 验证会话** | 统一验证会话、email 渠道（SMTP）、qq 渠道（先本地桩） | M3 |
| **M5 绑定与找回** | 邮箱/QQ 绑定、基数与域名限制、找回密码 | M4 |
| **M6 bot 协议** | 握手、配对、Ed25519、X25519/AES-GCM、会话消息、测试向量 | M4 |
| **M7 多平台** | Geyser/Floodgate、正版分支 | M3 |
| **M8 运维** | 管理命令、强制登录（顶号）、审计、限流、状态查询 | M2 |
| **M9 迁移** | LibreLogin 迁移工具 | M2 |

---

## 附：待评审确认项

**已确认**

| # | 项 | 结论 |
|---|---|---|
| 2 | `ServerPreConnectEvent` 兜底 | 本期实现（R1） |
| 3 | 正版名冲突策略 | `first-come` 先来后到 |
| 4 | 会话保持默认值 | **默认关闭**（国内 NAT 普遍，R6） |
| 5 | 管理命令清单 | 够用；`/bindemail`+`/bindqq` 合并为 `/bind <email\|qq>` |
| 6 | QQ 验证码字符集 | 数字+字母；消息支持点击复制 |
| 7 | `forcelogin` 二次确认 | 不需要 |

**待确认**

1. 登录服 / 主服 / 白名单子服的**实际服务器键名**（`lobby` / `main` 为占位，对应 `velocity.toml` 的 `[servers]` 段）
