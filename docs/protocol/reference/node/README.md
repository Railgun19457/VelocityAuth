# Node.js 参考实现（bot 侧）

[`PROTOCOL.md`](../../../PROTOCOL.md) 的可运行参考实现。**只依赖 Node 内置模块**，
不需要 `npm install`：

- 加密/摘要：`node:crypto`（Ed25519、X25519、HKDF、AES-256-GCM）
- WebSocket：全局 `WebSocket`
- 要求 **Node ≥ 22**

## 文件

| 文件 | 内容 |
|---|---|
| `protocol.mjs` | 协议原语：`canonical()`、`signatureInput()`、`messagePlaintext()`、`capabilitiesSha256()`、密钥派生、密封帧 `seal()` / `unseal()`、内层明文 `parseMessage()` |
| `bot-client.mjs` | `ReferenceBot`：配对 → 会话认证 → 处理 `verify.watch` / 回复 `verify.matched`；也可直接当命令行客户端跑 |
| `selfcheck.mjs` | 对照 [`../../vectors/`](../../vectors) 逐字节自检 |

## 自检（推荐先跑这个）

```bash
node selfcheck.mjs
```

覆盖：`canonical()` 用例、Ed25519 公钥与指纹、配对/会话帧的 `signature_input` 与签名
（含服务端两帧的**逐字节签名复现**）、X25519 shared secret、HKDF（salt/prk/key_c2s/key_s2c）、
密封帧的 nonce/AAD/密文/解密、内层明文解析。全部通过才算与协议契约一致。

## 连接插件

先让插件生成配对信息（`/velocityauth pair new`，或配置里的 `qqbot.pairing.bootstrap-code`），
把 `pairing_id` 与 `code` 写成一个 JSON：

```json
{ "host": "127.0.0.1", "port": 8765, "pairing_id": "pair_xxxxxxxxxxxxxxxx", "code": "配对码" }
```

然后：

```bash
node bot-client.mjs --pairing ./pairing.json
```

首次连接会完成配对并把服务端公钥 pin 到 `.bot-state.json`（与 `client_id` / `client_key_id`
一起复用）；之后同一份 state 直接走 `auth.*` 会话认证，不再需要配对码。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `VA_BOT_CLIENT_ID` | `node-bot-1` | bot 实例标识，需与插件侧 pin 的记录一致 |
| `VA_BOT_QQ` | `10001` | 命中群消息时回报的 QQ 号 |
| `VA_BOT_STATE` | `./.bot-state.json` | 身份与服务端公钥的持久化位置 |

## 作为库使用

```js
import { ReferenceBot } from './bot-client.mjs'

const bot = new ReferenceBot(config, {})
bot.handlers.onWatch = (code, groupId) => console.log('待验证码', code)
bot.handlers.onAck = (code, consumed) => console.log('插件已消费', code, consumed)
await bot.connect()          // 握手完成后 resolve
bot.sendSealed('ping', {})   // 业务消息
```

`ReferenceBot` 在收到 `verify.watch` 时会自动回复 `verify.matched`；接真实 QQ 时，
把 `onWatch` 换成"注册待命中码"、由群消息回调触发 `sendSealed('verify.matched', ...)` 即可，
并且**必须先真正查过群成员**（OneBot `get_group_member_info`）再回报 `is_group_member`。
