# VelocityAuth Bot 协议契约 v1

> Velocity 插件 ⇄ QQ bot 的 WebSocket 通道协议
>
> 状态：v1 草案（含可执行测试向量）
> 实现方：插件侧（Java，本项目）/ bot 侧（由使用方自行实现）

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
| **服务端** | **Velocity 插件**（监听 `qqbot.listen:port`） |
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
   | `code` | 高熵配对码（≥128 bit，base64url），**只打印一次** |
   | `expires_at` | 默认 TTL 600 秒 |
   | `attempts` | 失败次数，上限 5 |
   | `used` | 是否已消费 |

3. 服主把 `pairing_id` 与 `code` 配置到 bot 侧

### 4.2 消息序列

#### ① `pair.hello` — bot → plugin

| 字段 | 类型 | 说明 |
|---|---|---|
| `pairing_id` | string | |
| `client_id` | string | bot 实例标识，如 `astrbot-1` |
| `client_key_id` | string | bot 密钥标识 |
| `client_public_key` | string | b64url，**裸 Ed25519 公钥（32 字节）** |
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

均为密封帧。`id` 用于请求/响应关联。

| 消息 | 方向 | payload 字段 | 说明 |
|---|---|---|---|
| `verify.watch` | plugin → bot | `code`(string), `group_id`(string), `ttl`(int) | 登记一个待验证码，TTL 内该群出现此码即视为验证 |
| `verify.watch.ack` | bot → plugin | `code`, `accepted`(bool) | bot 确认已登记（或拒绝） |
| `verify.matched` | bot → plugin | `code`, `qq`(string), `group_id`, `group_nick`(string), `is_group_member`(bool), `matched_at`(int) | bot 命中：已通过群成员校验 |
| `verify.matched.ack` | plugin → bot | `code`, `consumed`(bool) | plugin 已消费；bot 收到后可撤回群消息 |
| `verify.cancel` | plugin → bot | `code` | 会话取消/超时，bot 应停止监听 |
| `ping` | 双向 | — | 心跳 |
| `pong` | 双向 | — | 心跳应答 |
| `error` | 双向 | `code`(string), `message`(string) | 错误 |

**典型时序**

```
plugin → bot   verify.watch   { code:"MC-8F3K2A", group_id:"123456", ttl:300 }
bot    → plugin verify.watch.ack { code:"MC-8F3K2A", accepted:true }
   ... 玩家在群里发送 MC-8F3K2A ...
bot    → plugin verify.matched { code:"MC-8F3K2A", qq:"10001", group_id:"123456",
                                 group_nick:"张三", is_group_member:true, matched_at:... }
plugin → bot   verify.matched.ack { code:"MC-8F3K2A", consumed:true }
```

> **职责边界**：`verify.matched` 里 plugin **不**信任 `is_group_member` 作为唯一依据——
> bot 必须真正查过群成员（OneBot `get_group_member_info`）。plugin 侧另有 TTL、限次与
> "该 QQ 已绑定账号数" 校验。

---

## 9. 错误码

| code | 触发 |
|---|---|
| `pairing_missing` | `pairing_id` 不存在 |
| `pairing_expired` | 配对记录过期 |
| `pairing_used` | 配对记录已被消费 |
| `pairing_attempts_exceeded` | 失败次数超限 |
| `signature_invalid` | 验签失败 |
| `key_not_paired` | `client_key_id` 未配对 |
| `version_unsupported` | 协议版本不兼容 |
| `decrypt_failed` | 密封帧解密失败 |
| `replay_detected` | `seq` 重放 |
| `rate_limited` | 触发限流 |
| `internal_error` | 其它 |

---

## 10. 安全要求（规范性）

1. `ts` 与服务器时间偏差超过 **±120 秒** 的握手帧必须拒绝
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
12. 握手必须在 **10 秒**内完成，否则关闭

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
- [ ] 通过全部测试向量

---

## 附：与设计文档的关系

- 业务背景与整体设计见 [`DESIGN.md`](DESIGN.md)
- 本文件是 bot 侧实现的**唯一权威来源**；字段以本文件为准
