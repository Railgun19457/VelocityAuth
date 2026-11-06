# VelocityAuth Bot 协议契约 v1

> Velocity 插件 ⇄ QQ bot 的 WebSocket 通道协议
>
> 状态：v1（含可执行测试向量）
> 实现方：插件侧（Java，本仓库 `src/main/java/.../bot/`）/ bot 侧（由使用方自行实现，
> 本仓库附一份 [Node.js 参考实现](protocol/reference/node)）

---

## 目录

1. [角色与传输](#1-角色与传输)
2. [帧封套](#2-帧封套)
3. [规范化与签名输入](#3-规范化与签名输入)
4. [配对流程](#4-配对流程)
5. [会话认证流程](#5-会话认证流程)
6. [密钥派生](#6-密钥派生)
7. [密封帧](#7-密封帧)
8. [业务消息](#8-业务消息)
9. [错误码](#9-错误码)
10. [安全要求（规范性）](#10-安全要求规范性)
11. [测试向量](#11-测试向量)
12. [实现清单（bot 侧）](#12-实现清单bot-侧)

---

## 1. 角色与传输

| 项 | 约定 |
|---|---|
| 传输 | WebSocket |
| **服务端** | **Velocity 插件**（监听 `bot.listen:port`） |
| **客户端** | **bot**（主动连接、断线自动重连） |
| 帧格式 | 一个 WebSocket **文本帧** = 一个 JSON 对象 |
| 字符编码 | UTF-8 |
| 协议版本 | `v = 1` |

两个阶段：

1. **配对（pair.\*）** —— 仅首次，用一次性配对码建立互信、交换并 pin 双方 Ed25519 公钥
2. **会话（auth.\*）** —— 每次连接，用已 pin 的公钥互证身份，并协商出 AES-GCM 会话密钥

> 即使之后要跑在公网上，也**不需要** TLS：保密性与完整性由应用层的 X25519 + AES-256-GCM 提供。

---

## 2. 帧封套

### 2.1 握手帧（明文）

`pair.*` 与 `auth.*` 的前三条消息使用：

```jsonc
{
  "v": 1,
  "kind": "plain",
  "type": "pair.hello",       // 消息类型
  "id": "f_1",                // 帧 id，本连接内唯一
  "ts": 1730000000,           // unix 秒
  "nonce": "b64url(16B)",     // 每帧随机
  "payload": { },             // 类型相关字段（扁平）
  "sig": "b64url(Ed25519)"    // 见第 3 节
}
```

### 2.2 密封帧（加密）

握手完成后所有消息使用：

```jsonc
{
  "v": 1,
  "kind": "sealed",
  "seq": 0,                   // 本方向单调递增计数器
  "ct": "b64url(ciphertext||tag)"
}
```

- `nonce` 与 `AAD` 由 `seq` 推导，见第 7 节，**不单独传输**
- 内层明文的类型/字段见第 8 节

---

## 3. 规范化与签名输入

### 3.1 载荷约束

`payload` 只允许**扁平字段**：字符串、整数、布尔、`null`、字符串数组。
**禁止嵌套对象**（保证跨语言规范化无歧义）。

### 3.2 `canonical(payload)`

```
canonical = 对 payload 的 key 按 UTF-8 字节序升序排列，逐行输出 "key=value\n"
```

`value` 渲染规则：

| 类型 | 渲染 |
|---|---|
| 字符串 | 原样，`\r` `\n` 替换为空格 |
| 整数 | 十进制 |
| 布尔 | `true` / `false` |
| `null` | 空字符串 |
| 字符串数组 | 各元素渲染后**排序**，用 `,` 连接 |

### 3.3 `signature_input`

```
VELOCITYAUTH-V1\n
context=<pair|auth>\n
type=<消息类型>\n
id=<帧 id>\n
ts=<时间戳>\n
nonce=<nonce>\n
code=<配对码>\n        ← 仅 context=pair 时存在；context=auth 时该行不存在
<canonical(payload)>
```

`sig` = `Ed25519(签名方私钥, UTF-8(signature_input))`

- `context=pair`：**双方**的签名都包含配对码 → 互证"都知道配对码"，且配对码**永不上线**
- `context=auth`：不含配对码，凭已 pin 的公钥互证

> 参考实现见 `protocol/tools/gen_vectors.py` 的 `signature_input()`。

---

## 4. 配对流程

### 4.1 前置

1. 服主执行 `/velocityauth pair new`
2. 插件生成并**仅在内存中**保存：

   | 字段 | 说明 |
   |---|---|
   | `pairing_id` | 公开标识 |
   | `code` | 高熵配对码（24 随机字节 → base64url，约 192 bit），**只打印一次** |
   | `expires_at` | 默认 TTL 600 秒 |
   | `attempts` / `max_attempts` | 失败次数 / 上限（默认 5） |

   记录**只存内存**；配对成功（或失败超限 / 过期）后即被移除，之后再查视为不存在。

3. 服主把 `pairing_id` 与 `code` 配置到 bot 侧

### 4.2 消息序列

#### ① `pair.hello` — bot → plugin

| 字段 | 类型 | 说明 |
|---|---|---|
| `pairing_id` | string | |
| `client_id` | string | bot 实例标识，如 `astrbot-1` |
| `client_key_id` | string | bot 密钥标识 |
| `client_public_key` | string | b64url，**裸 Ed25519 公钥（32 字节）** |
| `provider` | string | 该 bot 服务的渠道 id（如 `qq` / `discord`），用于插件按渠道路由 |
| `scopes` | string | 可选的监听位置展示串（如群号，`,` 连接），仅用于提示 |
| `capabilities` | string[] | 如 `["verify.v1"]` |

`sig`：bot 用**自己的** Ed25519 私钥签名（证明持有该公钥）。

#### ② `pair.challenge` — plugin → bot

| 字段 | 类型 | 说明 |
|---|---|---|
| `pairing_id` | string | |
| `challenge_id` | string | 本次握手唯一 id |
| `expires_at` | int | 该 challenge 的过期时间 |
| `server_id` | string | 插件实例标识 |
| `server_key_id` | string | 插件密钥标识 |
| `server_public_key` | string | b64url 裸 Ed25519 公钥（32B） |
| `server_nonce` | string | b64url 16B |
| `selected_v` | int | 协商后的协议版本 |
| `capabilities` | string[] | |

`sig`：plugin 用自己私钥签名。

#### ③ `pair.prove` — bot → plugin

字段（全部参与签名，用于绑定整个握手上下文）：

`pairing_id`, `challenge_id`, `hello_id`(=①的 `id`),
`client_id`, `client_key_id`, `client_public_key_sha256`,
`server_id`, `server_key_id`, `server_public_key_sha256`,
`client_nonce`(=①的 `nonce`), `server_nonce`(=②的 `server_nonce`),
`selected_v`, `client_capabilities_sha256`, `server_capabilities_sha256`

`sig`：bot 用自己私钥签名。

> plugin 校验：用 ① 中的 `client_public_key` 验签；通过即证明 bot 知道配对码。
> 失败则 `attempts++`，超限作废。

#### ④ `pair.complete` — plugin → bot

| 字段 | 类型 |
|---|---|
| `ok` | true |
| `pairing_id` | string |
| `session_id` | string |
| `client_key_id` | string |
| `client_key_fingerprint` | string（sha256(裸公钥) 的 b64url） |
| `server_key_id` | string |
| `server_key_fingerprint` | string |
| `server_time` | int |
| `selected_v` | int |
| `capabilities` | string[] |

`sig`：plugin 用自己私钥签名（同样含配对码）。

> bot 校验：用 ② 中的 `server_public_key` 验签；通过即证明 plugin 也知道配对码 → **互信完成**。
> bot 随后 **pin 住 `server_public_key`**；plugin 则 pin 住 bot 公钥。

### 4.3 配对失败

plugin 返回 `error`（`kind:"plain"`，`type:"error"`）并关闭连接。错误码见第 9 节。

---

## 5. 会话认证流程

### ① `auth.hello` — bot → plugin

| 字段 | 类型 | 说明 |
|---|---|---|
| `client_id` | string | |
| `client_key_id` | string | 必须是已配对过的 |
| `client_public_key_sha256` | string | 与 pin 记录一致，否则拒绝 |
| `client_ephemeral_key` | string | b64url 裸 X25519 公钥（32B），**每次连接新生成** |
| `client_nonce` | string | b64url 16B |
| `provider` | string | 该 bot 服务的渠道 id（同 `pair.hello`） |
| `scopes` | string | 可选的监听位置展示串 |
| `capabilities` | string[] | |

`sig`：bot 用长期 Ed25519 私钥签名。

### ② `auth.challenge` — plugin → bot

| 字段 | 类型 | 说明 |
|---|---|---|
| `challenge_id` | string | |
| `expires_at` | int | |
| `server_id` | string | |
| `server_key_id` | string | |
| `server_public_key_sha256` | string | |
| `server_ephemeral_key` | string | b64url 裸 X25519 公钥（32B） |
| `server_nonce` | string | b64url 16B |
| `selected_v` | int | |
| `capabilities` | string[] | |

`sig`：plugin 用长期 Ed25519 私钥签名。

> bot **必须**校验 `server_public_key_sha256` 与本地 pin 一致，否则中止。

### ③ `auth.prove` — bot → plugin

字段：`challenge_id`, `hello_id`, `client_id`, `client_key_id`,
`client_public_key_sha256`, `client_ephemeral_key`,
`server_id`, `server_key_id`, `server_public_key_sha256`, `server_ephemeral_key`,
`client_nonce`, `server_nonce`, `selected_v`,
`client_capabilities_sha256`, `server_capabilities_sha256`

`sig`：bot 用长期 Ed25519 私钥签名。

> 该签名把**双方临时密钥**也纳入，从而把 ECDH 与身份绑定，抵御中间人。

### ④ `auth.result` — plugin → bot（**密封帧**，s2c seq=0）

内层明文：

| 字段 | 类型 |
|---|---|
| `ok` | true |
| `session_id` | string |
| `server_time` | int |
| `capabilities` | string[] |

> bot 能成功解密即证明 plugin 派生出了相同会话密钥（前向保密的密钥确认）。

### ⑤ `auth.confirm` — bot → plugin（**密封帧**，c2s seq=0）

内层明文：`{ "session_id": "..." }`

> plugin 能成功解密即证明 bot 也派生出了相同会话密钥 → **双向密钥确认完成**，此后进入业务消息。

---

## 6. 密钥派生

```
shared  = X25519(本地临时私钥, 对方临时公钥)          // 32B
salt    = SHA-256("VELOCITYAUTH-V1-SALT" || client_nonce_raw || server_nonce_raw)
prk     = HMAC-SHA256(salt, shared)                   // HKDF-Extract
key_c2s = HKDF-Expand(prk, "velocityauth c2s", 32)
key_s2c = HKDF-Expand(prk, "velocityauth s2c", 32)
```

- `client_nonce_raw` / `server_nonce_raw` = 从 b64url **解码后的原始字节**
- `HKDF-Expand(prk, info, L)`：标准 HKDF（RFC 5869），`T(i)=HMAC-SHA256(prk, T(i-1)||info||byte(i))`，取前 `L` 字节
- `key_c2s`：bot → plugin；`key_s2c`：plugin → bot

---

## 7. 密封帧

```
nonce(12B) = epoch(4B, 大端) || seq(8B, 大端)     // epoch 初始 0
AAD        = "VELOCITYAUTH-SEAL-V1\nv=1\nseq=<seq>\n"   (UTF-8)
ct         = AES-256-GCM(key_<direction>, nonce, plaintext, AAD)   // 密文||16B tag
```

内层明文（UTF-8）：

```
VELOCITYAUTH-MSG-V1\n
type=<消息类型>\n
id=<帧 id>\n
ts=<时间戳>\n
<canonical(payload)>
```

**发送方**：`seq` 从 0 开始，每帧 +1，按方向各自独立计数。
**接收方**：维护每个方向的 `last_seq`，`seq <= last_seq` 一律丢弃（防重放）。

---

## 8. 业务消息

均为密封帧。内层明文里的 `id` 是本帧的唯一标识（每帧都不同），**不是**请求 id；
请求与响应按业务字段关联：`verify.*` 用 `code`，`query.*` 用响应回带的 `request_id`。

> **注意**：密封帧内层是 canonical 文本（第 7 节），因此**接收方解析出的所有 payload 值都是字符串**
> ——布尔是 `"true"`/`"false"`，整数是十进制文本，字符串数组是**逗号连接**的字符串。下文凡标注
> `(int)` / `(bool)` 的字段，接收方拿到的是其字符串形式。

| 消息 | 方向 | payload 字段 | 说明 |
|---|---|---|---|
| `verify.watch` | plugin → bot | `code`(string), `ttl`(int), `provider`(string) | 登记一个待验证码，TTL 内该渠道出现此码即视为验证（插件只发给该 provider 的会话） |
| `verify.watch.ack` | bot → plugin | `code`, `accepted`(bool) | bot 确认已登记（或拒绝） |
| `verify.matched` | bot → plugin | `code`, `subject`(string), `scope`(string, 可选), `display_name`(string, 可选), `matched_at`(int) | bot 命中：**平台策略（成员/角色等）已由 bot 判定有效**，故只回事实 |
| `verify.matched.ack` | plugin → bot | `code`, `consumed`(bool) | plugin 已消费；bot 收到后可撤回群消息 |
| `verify.cancel` | plugin → bot | `code` | 会话取消/超时，bot 应停止监听 |
| `query.account` | bot → plugin | `by`(string), `value`(string) | 按 `name`\|`uuid` 查询账号详情（`query.v1`） |
| `query.account.result` | plugin → bot | 见 8.1 | 账号详情（`found=false` 表示不存在） |
| `query.lookup` | bot → plugin | `by`(string), `value`(string), `limit`(int, 可选) | 按 `ip` 或渠道 id 反查账号名列表（`query.v1`） |
| `query.lookup.result` | plugin → bot | `found`, `by`, `value`, `count`(int), `accounts`(string, `,` 分隔) | 命中的账号名（去重） |
| `query.history` | bot → plugin | `by`(string), `value`(string), `result`(string, 可选), `since`(int, 可选), `limit`(int, 可选) | 按 `name`\|`uuid` 查询登录历史（`query.v1`） |
| `query.history.result` | plugin → bot | `found`, `count`(int), `entries`(string, `;` 分隔), `data` | 每条 `at\|result\|ip\|identity`，时间倒序 |
| `query.binding-history` | bot → plugin | `by`(string), `value`(string), `limit`(int, 可选) | 按渠道 id 查绑定历史（含解绑/注销）（`query.v1`） |
| `query.binding-history.result` | plugin → bot | `found`, `by`, `value`, `count`(int), `entries`(string, `;` 分隔), `cooldown_until`(int) | 每条 `at\|action\|ip\|account_id\|account`，时间倒序 |
| `query.stats` | bot → plugin | `range`(int, 可选，1-90) | 聚合统计 + 活跃度（`query.v1`） |
| `query.stats.result` | plugin → bot | 见 8.1 | 账号 / 绑定 / 在线 / 时间窗统计 + `dau`/`wau`/`mau`（带 `range` 时另有 `trend`） |
| `query.accounts.list` | bot → plugin | `keyword`, `status`, `provider`, `identifier`, `online`, `created_after`, `created_before`, `last_login_after`, `last_login_before`, `sort`, `page`, `limit` | 管理端账号检索（`query.v1`，见 8.3） |
| `query.accounts.list.result` | plugin → bot | `found`, `total`, `page`, `page_size`, `count`, `data` | 分页账号摘要 |
| `query.sessions.list` | bot → plugin | `page`, `limit` | 在线已认证会话列表（`query.v1`，见 8.3） |
| `query.sessions.list.result` | plugin → bot | `found`, `total`, `page`, `page_size`, `count`, `data` | 在线会话 |
| `query.audit.list` | bot → plugin | `account_id`, `action`, `actor`, `source`, `since`, `until`, `page`, `limit` | 审计日志检索（`query.v1`，见 8.3） |
| `query.audit.list.result` | plugin → bot | `found`, `total`, `page`, `page_size`, `count`, `data` | 分页审计记录 |
| `admin.account.set-status` | bot → plugin | `id` 或 `name`, `status`(`active`\|`locked`), `actor`(可选) | 锁定 / 解锁（`admin.v1`，见 8.4） |
| `admin.account.reset-password` | bot → plugin | `id` 或 `name`, `password`, `actor`(可选) | 管理员重置密码 |
| `admin.account.delete` | bot → plugin | `id` 或 `name`, `confirm_name`, `actor`(可选) | 删除账号（必须回填账号名确认） |
| `admin.account.kick` | bot → plugin | `id` 或 `name`, `reason`(可选), `actor`(可选) | 踢出在线会话 |
| `admin.account.clear-session` | bot → plugin | `id` 或 `name`, `actor`(可选) | 清除会话保持 |
| `admin.binding.remove` | bot → plugin | `id` 或 `name`, `provider`, `actor`(可选) | 解绑指定渠道 |
| `admin.*.result` | plugin → bot | `ok`(bool), `id`, `name`, 其它按操作 | 管理操作结果 |
| `ping` | 双向 | — | 心跳 |
| `pong` | 双向 | — | 心跳应答 |
| `error` | 双向 | `code`(string), `message`(string), `request_id`(string, 可选) | 错误；握手阶段为明文帧 |

**典型时序**

```
plugin → bot   verify.watch   { code:"MC-8F3K2A", ttl:300, provider:"qq" }
bot    → plugin verify.watch.ack { code:"MC-8F3K2A", accepted:true }
   ... 玩家在群里发送 MC-8F3K2A ...
bot    → plugin verify.matched { code:"MC-8F3K2A", subject:"10001", scope:"123456",
                                 display_name:"张三", matched_at:... }
plugin → bot   verify.matched.ack { code:"MC-8F3K2A", consumed:true }
```

> **职责边界**：平台语义（房间、成员/角色、账号格式、平台反滥用）**全部由 bot 侧负责**——
> bot 只在判定有效时才回报 `verify.matched`。插件只做**平台无关**的接受规则：码命中活跃会话、
> TTL、尝试次数、该 `subject` 已绑账号数上限、绑定冷却。插件不校验"房间/成员"。

### 8.1 玩家信息查询（`query.v1`）

**默认关闭**，由 `bot.query.enabled` 控制；启用后 plugin 在 capabilities 里声明 `query.v1`。
**权限校验由 bot 侧负责**：plugin 只认「已配对且已建立会话」这一前提，返回给 bot 的数据
由 bot 决定展示给谁。响应载荷回带 `request_id`（= 请求内层 `id`），据此关联请求与响应。

> 字段编码见第 8 节开头：值在接收方都是字符串。`accounts` / `identities` 用 `,` 连接、
> `entries` 用 `;` 连接（各分量本身不含分隔符）。
>
> 从 v1.1 起，列表 / 详情类响应额外提供 `data` 字段（**紧凑 JSON 字符串**），
> 便于管理页直接解析；原有扁平字段保持不变，向后兼容。

`query.account`（`by` = `name` \| `uuid`）→ `query.account.result`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `found` | bool | 是否存在 |
| `id` / `name` / `uuid` / `status` | int / string | 账号标识与状态 |
| `last_server` / `last_ip` | string | 无则为空串 |
| `created_at` / `last_login_at` | int | unix 秒；从未登录为 0 |
| `bindings` | string | 每条 `provider:identifier:verified_at`，`;` 连接（如 `email:a@qq.com:1700000000;qq:10001:1700000001`） |
| `identities` | string | 每条 `kind:identifier`（`java` / `bedrock`），`,` 连接 |

`query.lookup`（`by` = `ip` 或**已配置的渠道 id**）→ `query.lookup.result`：
`found`(bool)、`by`、`value`、`count`(int)、`accounts`（`,` 连接的账号名）。同一渠道标识可绑定多个账号
（受 `max-accounts` 限制），故为多值；`ip` 会同时匹配登录历史与 `accounts.last_ip`。

`query.history`（`by` = `name` \| `uuid`；`result` ∈ `success` \| `failed` \| `logout`；`since` unix 秒）
→ `query.history.result`：`found`(bool)、`count`(int)、`entries`（`;` 连接），每条 `at|result|ip|identity`。

`limit` 会被裁剪到 `[1, bot.query.max-results]`。失败时回密封 `error` 帧，`code` 为
`query_disabled`（未启用）或 `bad_request`（参数非法）。

`query.binding-history`（`by` = 已配置的渠道 id）→ `query.binding-history.result`：
`found`(bool)、`by`、`value`、`count`(int)、`entries`（`;` 连接），每条 `at|action|ip|account_id|account`
（`action` ∈ `bind` \| `unbind` \| `delete`；`account` 为空表示账号已注销）。
另附 `cooldown_until`(int)：该标识可再次绑定的时间点（unix 秒，0 表示无冷却/已可绑定）。
即使账号已解绑或注销，这里仍能查到它曾绑定的 QQ / 邮箱。

`query.stats`（`range` 可选，1-90 天）→ `query.stats.result`：聚合统计（**无 PII**，只给数字）：

| 字段 | 含义 |
|---|---|
| `accounts_total` | 总账号数 |
| `accounts_<status>` | 按状态分组（`accounts_active` / `accounts_pending_binding` / `accounts_locked`） |
| `bound_<provider>` | 各渠道去重绑定账号数（如 `bound_email` / `bound_qq`；每个已配置渠道都有字段，无则 0） |
| `online` | 当前在线已认证会话数 |
| `bot_connected` | 已就绪的 bot 会话数 |
| `registered_24h` / `registered_7d` / `registered_30d` | 近窗口注册数 |
| `logins_24h` / `logins_7d` / `logins_30d` | 近窗口**成功**登录数 |
| `dau` / `wau` / `mau` | 近 24h / 7d / 30d 有成功登录的去重账号数（见 8.4） |
| `trend` | 仅在带 `range` 时出现：JSON 字符串，每日 `date`/`logins`/`accounts` |

> 查询接口只读；**永不返回密码哈希**。

### 8.2 账号管理（`admin.v1`）

**默认关闭**，由 `bot.admin.enabled` 控制；启用后 plugin 在 capabilities 里声明 `admin.v1`。

| 配置 | 说明 |
|---|---|
| `bot.admin.clients` | 允许发送 `admin.*` 的 `client_id` 白名单；空列表表示允许所有已配对客户端 |
| `bot.admin.read-only` | 只读模式：拒绝一切写操作（返回 `admin_read_only`） |
| `bot.admin.max-page-size` | 列表类接口单页最大条数 |
| `bot.admin.rate-limit-per-minute` | 单个客户端每分钟最多发送多少条 `admin.*` |

**授权模型**：插件只认「已配对 + 已就绪 + 客户端在白名单内」。`actor` 字段由 bot 传回，**仅用于审计追溯，不参与鉴权**——管理页自身的登录与权限由 bot 后端负责。

| 消息 | payload 字段 | 说明 |
|---|---|---|
| `admin.account.set-status` | `id` 或 `name`, `status`(`active`\|`locked`), `actor`(可选) | 锁定 / 解锁；锁定时同时踢出在线会话 |
| `admin.account.reset-password` | `id` 或 `name`, `password`, `actor`(可选) | 不校验旧密码 |
| `admin.account.delete` | `id` 或 `name`, `confirm_name`, `actor`(可选) | `confirm_name` 必须与账号名一致（不区分大小写） |
| `admin.account.kick` | `id` 或 `name`, `reason`(可选), `actor`(可选) | 踢出该账号的全部在线会话 |
| `admin.account.clear-session` | `id` 或 `name`, `actor`(可选) | 清除会话保持，下次必须重新输入密码 |
| `admin.binding.remove` | `id` 或 `name`, `provider`, `actor`(可选) | 解绑；若会破坏 `registration.require-binding` 则返回 `binding_required` |

成功时回 `<type>.result`：`ok`(true)、`id`、`name`，另按操作附带 `status` / `sessions` / `deleted`。
失败时回密封 `error`，`code` ∈ `admin_disabled` | `admin_read_only` | `rate_limited` | `confirm_required` |
`binding_required` | `not_bound` | `password_too_short` | `password_too_long` | `bad_request` | `internal_error`。

**审计**：所有写操作都会写入 `audit_log`，`action` 为 `admin_lock` / `admin_unlock` /
`admin_reset_password` / `admin_delete` / `admin_unbind` / `admin_kick` / `admin_clear_session`，
`source=bot`，`client_id` 为发起请求的客户端，`actor` 取自 payload。

### 8.3 管理端检索（`query.v1` 扩展）

三个列表接口的响应都带 `data` 字段（紧凑 JSON 字符串），由 bot 侧 `JSON.parse` 使用。

**`query.accounts.list`**（全部参数可选）

| 参数 | 说明 |
|---|---|
| `keyword` | 模糊匹配账号名、UUID、最近登录 IP、绑定标识 |
| `status` | `active` \| `locked` \| `pending_binding` |
| `provider` / `identifier` | 按绑定渠道（及可选的具体标识）筛选 |
| `online` | `true` 只返回当前在线账号 |
| `created_after` / `created_before` | 注册时间范围（unix 秒） |
| `last_login_after` / `last_login_before` | 最后登录时间范围（unix 秒） |
| `sort` | `name`(默认) \| `created_at` \| `-created_at` \| `last_login_at` \| `-last_login_at` \| `id` \| `-id` |
| `page` / `limit` | 页码（从 1 开始）/ 每页条数（上限 `max-page-size`） |

`data` 每行：`id`, `name`, `uuid`, `status`, `last_server`, `created_at`, `last_login_at`,
`last_ip`, `bindings`（`provider:identifier;...`）, `online`。

**`query.sessions.list`**（`page`, `limit`）：`data` 每行 `account_id`, `account`, `connection`,
`ip`, `authenticated_at`。

**`query.audit.list`**：`account_id`, `action`, `actor`, `source`(`bot`\|`command`\|`plugin`),
`since`, `until`, `page`, `limit`。
`data` 每行：`id`, `at`, `account_id`, `account`, `action`, `ip`, `detail`, `actor`, `source`, `client_id`。

### 8.4 活跃度（`query.stats` 扩展）

`query.stats` 新增可选参数 `range`（1-90 天）。响应始终包含：

| 字段 | 含义 |
|---|---|
| `dau` | 近 24 小时有成功登录的去重账号数 |
| `wau` | 近 7 天 |
| `mau` | 近 30 天 |

带 `range` 时另附 `trend`（JSON 字符串，按日期升序的数组），每项 `date`（UTC `YYYY-MM-DD`）、
`logins`（成功登录次数）、`accounts`（去重账号数）。

> 口径是「当天有重新登录」。走会话保持（免密）一直在线、当天未重新登录的玩家不计入。
> 数据受 `database.login-history-days`（默认 90 天）保留期限制；长期趋势请由 bot 侧缓存。

---

## 9. 错误码

| code | 触发 |
|---|---|
| `pairing_missing` | `pairing_id` 不存在（含已消费 / 已过期） |
| `pairing_expired` | 配对 challenge 已过期 |
| `pairing_attempts_exceeded` | 配对失败次数超限 |
| `signature_invalid` | 验签失败 / 字段不匹配 / 时间偏差过大 |
| `key_not_paired` | `client_key_id` 未配对或指纹不匹配 |
| `version_unsupported` | 协议版本不兼容 |
| `decrypt_failed` | 密封帧解密失败 / 未完成握手即收到密封帧 |
| `replay_detected` | `seq` 重放 |
| `query_disabled` | 收到 `query.*` 但查询接口未启用 |
| `admin_disabled` | 收到 `admin.*` 但管理接口未启用，或该客户端未被授权 |
| `admin_read_only` | 管理接口处于只读模式（`bot.admin.read-only=true`） |
| `confirm_required` | 危险操作缺少/不匹配 `confirm_name` |
| `binding_required` | 解绑会破坏「注册后必须绑定」的要求 |
| `not_bound` | 解绑时该渠道未绑定 |
| `rate_limited` | `admin.*` 超过 `bot.admin.rate-limit-per-minute` |
| `bad_request` | `query.*` / `admin.*` 参数非法（`by`/`value`/`result` 等） |
| `internal_error` | 其它（状态异常、未知帧、期望类型不符等） |

> 握手阶段的 `error` 为**明文帧**（第 4.3 节），业务 / 查询 / 管理阶段的 `error` 为**密封帧**
> 并回带 `request_id`。

---

## 10. 安全要求（规范性）

1. `ts` 与服务器时间偏差超过 **±120 秒**（默认，可配 `security.clock-skew-seconds`）的握手帧必须拒绝
2. `id` 在同一连接内必须唯一，重复拒绝
3. 配对码：**仅存内存**、一次性、TTL、限次、**绝不写入日志**
4. `pair.*` 的签名必须包含配对码（`code=` 行）
5. bot 必须校验 `server_public_key_sha256` 与本地 pin 一致
6. plugin 必须校验 `client_key_id` 已配对
7. 每方向独立密钥；`nonce` **必须**由 `seq` 确定性派生，**严禁随机**
8. `seq` 每方向严格递增；`seq <= last_seq` 一律拒绝
9. 临时 X25519 密钥**每次连接重新生成**（前向保密）
10. 任一手握/解密失败 → 立即关闭连接
11. Ed25519 私钥必须以受限权限存储；公钥以指纹 pin
12. 握手必须在 **10 秒**（默认，可配 `security.handshake-timeout-seconds`）内完成，否则关闭

---

## 11. 测试向量

目录 `protocol/vectors/`，由 `protocol/tools/gen_vectors.py` 生成（确定性，可复现）。

| 文件 | 内容 |
|---|---|
| `keys.json` | 固定的测试用 Ed25519 / X25519 密钥（**仅测试**） |
| `canonicalization.json` | `canonical()` 的输入/输出用例 |
| `pairing.json` | 四个配对帧 + 各自的 `signature_input` 明文 + 期望签名 |
| `session.json` | 三个 auth 握手帧 + 密钥派生中间值（`shared`/`salt`/`prk`/`key_c2s`/`key_s2c`）+ 三个密封帧的 `nonce`/`AAD`/明文/密文 |

**bot 侧自测建议**：

1. 用 `keys.json` 的种子重建密钥对
2. 对 `pairing.json` / `session.json` 的每个 `frame`，按第 3 节重建 `signature_input`，断言与文件中的字符串**逐字节相同**，并用对应私钥验签
3. 用 `session.json.key_schedule` 校验自己的 HKDF 结果
4. 用对应 `key`、`nonce_b64url`、`aad`、`plaintext` 复算 AES-GCM，断言 `ct` 一致；再反向解密 `ct` 得到 `plaintext`

---

## 12. 实现清单（bot 侧）

- [ ] Ed25519 密钥对生成与持久化（`client_id` / `client_key_id`）
- [ ] WebSocket 客户端 + 断线重连（指数退避）
- [ ] `canonical()` / `signature_input()` / `message_plaintext()` 实现
- [ ] Ed25519 签名与验签
- [ ] X25519 临时密钥对 + ECDH
- [ ] HKDF-SHA256（Extract + Expand）
- [ ] AES-256-GCM 加解密（`nonce` 由 `seq` 派生）
- [ ] 每方向 `seq` 计数与重放拒绝
- [ ] 配对流程（一次性，成功后 pin plugin 公钥）
- [ ] 会话认证流程（双向密钥确认）
- [ ] 心跳 `ping`/`pong` 与超时
- [ ] 业务消息处理：`verify.watch` → 群消息监听 → 群成员校验 → `verify.matched`
- [ ] 群消息命中后按需**撤回**（收到 `verify.matched.ack` 后）
- [ ] 业务消息处理：`query.account` / `query.lookup` / `query.history` / `query.binding-history`（按响应 `request_id` 关联）
- [ ] 业务消息处理：`query.stats`（含 `range` 活跃度趋势）/ `query.accounts.list` / `query.sessions.list` / `query.audit.list`
- [ ] 管理消息处理：`admin.*`（锁定 / 改密 / 删除 / 踢出 / 清会话 / 解绑），带 `confirm_name` 二次确认
- [ ] 通过全部测试向量

---

## 附 A：Node.js 参考实现

[`protocol/reference/node/`](protocol/reference/node) 是一份可直接运行的 bot 侧参考实现，
只用 Node 内置模块（`node:crypto` + 全局 `WebSocket`，**无需 `npm install`**，Node ≥ 22）：

| 文件 | 内容 |
|---|---|
| `protocol.mjs` | `canonical()` / `signature_input()` / `message_plaintext()` / HKDF / AES-GCM 密封帧 |
| `bot-client.mjs` | `ReferenceBot`：配对 + 会话认证 + `verify.watch` / `verify.matched` 处理 + `query.*`（`sendQuery`，命令行 `--query`） |

自检（对照本目录的测试向量，逐字节比对）：

```bash
node --input-type=module -e "
import * as m from './protocol.mjs';
// 见 reference/node/README.md 的完整自检命令
"
```

完整用法见 [`protocol/reference/node/README.md`](protocol/reference/node/README.md)。

---

## 附 B：权威性

- 本文件是 bot 侧实现的**唯一权威来源**；字段以本文件为准
- [`protocol/vectors/`](protocol/vectors) 是可执行的一致性门槛，双方实现都必须逐字节通过
- 插件侧实现位于本仓库 `src/main/java/.../bot/`（`bot/protocol/` 为协议层）
