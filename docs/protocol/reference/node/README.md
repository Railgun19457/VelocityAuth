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
| `bot-client.mjs` | `ReferenceBot`：配对 → 会话认证 → 处理 `verify.watch` / 回复 `verify.matched`，并支持 `query.*` 查询；也可直接当命令行客户端跑 |
| `selfcheck.mjs` | 对照 [`../../vectors/`](../../vectors) 逐字节自检 |

## 自检（推荐先跑这个）

```bash
node selfcheck.mjs
```

覆盖：`canonical()` 用例、Ed25519 公钥与指纹、配对/会话帧的 `signature_input` 与签名
（含服务端两帧的**逐字节签名复现**）、X25519 shared secret、HKDF（salt/prk/key_c2s/key_s2c）、
密封帧的 nonce/AAD/密文/解密、内层明文解析。全部通过才算与协议契约一致。

## 连接插件

先让插件生成配对信息（`/velocityauth pair new`，或配置里的 `bot.pairing.bootstrap-code`），
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
| `VA_BOT_PROVIDER` | `qq` | 本 bot 服务的渠道 id（需与插件 `binding.social` 下的键一致） |
| `VA_BOT_SCOPES` | （空） | 监听位置展示串（如群号），仅用于提示 |
| `VA_BOT_SUBJECT` | `10001` | 命中时回报的平台账号 |
| `VA_BOT_STATE` | `./.bot-state.json` | 身份与服务端公钥的持久化位置 |

## 作为库使用

```js
import { ReferenceBot } from './bot-client.mjs'

const bot = new ReferenceBot(config, {})
bot.handlers.onWatch = (code, provider) => console.log('待验证码', code, provider)
bot.handlers.onAck = (code, consumed) => console.log('插件已消费', code, consumed)
await bot.connect()          // 握手完成后 resolve
bot.sendSealed('ping', {})   // 业务消息
```

`ReferenceBot` 在收到 `verify.watch` 时会自动回复 `verify.matched`；接真实平台时，
把 `onWatch` 换成"注册待命中码"、由消息回调触发 `sendSealed('verify.matched', { code, subject, scope, display_name })` 即可。
**平台策略（是否群成员、角色、账号格式）由 bot 自己判定**——只在判定有效时才回报；插件不校验房间/成员。

## 玩家信息查询（`query.v1`）

需要在插件侧开启 `bot.query.enabled=true`（默认关闭）。`sendQuery()` 会自动按响应的
`request_id` 关联请求与响应；**结果里的值都是字符串**（布尔是 `"true"`/`"false"`，
`accounts`/`identities` 是 `,` 连接的字符串，`entries` 是 `;` 连接的字符串）——见
[`PROTOCOL.md` 8.1](../../../PROTOCOL.md#81-玩家信息查询queryv1)。

```js
const account = await bot.sendQuery('query.account', { by: 'name', value: 'Alice' })
const hits    = await bot.sendQuery('query.lookup', { by: 'qq', value: '10001' })
// hits.accounts === 'Alice'（多个则逗号连接）
const log     = await bot.sendQuery('query.history', { by: 'name', value: 'Alice', result: 'failed' })
const binds   = await bot.sendQuery('query.binding-history', { by: 'email', value: 'a@qq.com' })
// binds.cooldown_until 为可再次绑定的 unix 秒（0=无冷却）
const stats   = await bot.sendQuery('query.stats')
// stats.accounts_total / stats.accounts_active / stats.bound_email / stats.online
// stats.registered_24h / stats.logins_7d ...（值均为字符串）
```

命令行也能直接跑一条查询（连上后发一条、打印结果、退出）：

```bash
node bot-client.mjs --pairing ./pairing.json --query lookup:qq:10001
node bot-client.mjs --pairing ./pairing.json --query account:name:Alice
node bot-client.mjs --pairing ./pairing.json --query binding-history:email:a@qq.com
```
