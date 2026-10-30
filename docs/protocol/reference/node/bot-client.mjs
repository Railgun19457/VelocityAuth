// 参考 bot：配对 + 会话认证 + 处理 verify.watch / 回复 verify.matched / 发送 query.*
//
// 用法：
//   node bot-client.mjs --pairing <pairing.json> [--watch <code>] [--ttl 300]
//   node bot-client.mjs --pairing <pairing.json> --query <type>:<by>:<value>
//     <type> ∈ account | lookup | history | binding-history | stats
//     例：--query lookup:qq:10001    --query account:name:Alice    --query stats
// pairing.json: { "host": "127.0.0.1", "port": 8765, "pairing_id": "...", "code": "..." }
import fs from 'node:fs'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import {
  b64u,
  b64uDecode,
  signatureInput,
  messagePlaintext,
  capabilitiesSha256,
  ed25519FromSeed,
  ed25519PublicFromRaw,
  x25519FromRaw,
  x25519PublicFromRaw,
  deriveKeys,
  seal,
  unseal,
  parseMessage,
} from './protocol.mjs'

const CAPABILITIES = ['verify.v1']
const CLIENT_ID = process.env.VA_BOT_CLIENT_ID || 'node-bot-1'
const CLIENT_KEY_ID = 'bot-k1'
const PROVIDER = process.env.VA_BOT_PROVIDER || 'qq'
const SCOPES = process.env.VA_BOT_SCOPES || ''
const STATE_FILE = process.env.VA_BOT_STATE || fileURLToPath(new URL('./.bot-state.json', import.meta.url))

function parseArgs() {
  const args = process.argv.slice(2)
  const out = {}
  for (let i = 0; i < args.length; i += 2) {
    out[args[i].replace(/^--/, '')] = args[i + 1]
  }
  return out
}

function loadOrCreateIdentity() {
  try {
    const state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'))
    return { seed: Buffer.from(state.seed, 'base64url'), serverKey: state.serverKey ? Buffer.from(state.serverKey, 'base64url') : null }
  } catch {
    const seed = crypto.randomBytes(32)
    fs.writeFileSync(STATE_FILE, JSON.stringify({ seed: b64u(seed) }))
    return { seed, serverKey: null }
  }
}

function saveServerKey(serverKey) {
  const state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'))
  state.serverKey = b64u(serverKey)
  fs.writeFileSync(STATE_FILE, JSON.stringify(state))
}

class ReferenceBot {
  constructor(config, options) {
    this.config = config
    this.options = options
    this.identity = loadOrCreateIdentity()
    this.ed = ed25519FromSeed(this.identity.seed)
    this.eph = x25519FromRaw(crypto.randomBytes(32))
    this.serverKey = this.identity.serverKey
    this.counter = 0
    this.logs = []
    this.ready = false
    this.sessionId = null
    this.outboundSeq = 0
    this.inboundLastSeq = -1
    this.pendingQueries = new Map()
    this.handlers = { onReady: () => {}, onWatch: () => {}, onAck: () => {} }
  }

  nextId() {
    return `n_${++this.counter}`
  }

  log(message) {
    this.logs.push(message)
    console.log(`[bot] ${message}`)
  }

  connect() {
    return new Promise((resolve, reject) => {
      const url = `ws://${this.config.host}:${this.config.port}`
      this.ws = new WebSocket(url)
      this.done = resolve
      this.failed = reject
      this.ws.addEventListener('open', () => this.onOpen())
      this.ws.addEventListener('message', (event) => this.onMessage(event.data))
      this.ws.addEventListener('close', () => {
        if (!this.ready) reject(new Error('连接在握手完成前关闭'))
      })
      this.ws.addEventListener('error', (event) => reject(new Error(String(event.message || event))))
    })
  }

  send(frame) {
    this.ws.send(JSON.stringify(frame))
  }

  sendPlain(type, payload, pairContext, explicitId) {
    const id = explicitId || this.nextId()
    const nonce = b64u(crypto.randomBytes(16))
    const ts = Math.floor(Date.now() / 1000)
    const code = pairContext ? this.config.code : null
    const input = signatureInput(pairContext ? 'pair' : 'auth', type, id, ts, nonce, payload, code)
    const sig = b64u(crypto.sign(null, Buffer.from(input, 'utf8'), this.ed.privateKey))
    this.send({ v: 1, kind: 'plain', type, id, ts, nonce, payload, sig })
  }

  sendSealed(type, payload) {
    const id = this.nextId()
    const ts = Math.floor(Date.now() / 1000)
    const plaintext = messagePlaintext(type, id, ts, payload)
    const seq = this.outboundSeq++
    const ct = seal(this.keys.keyC2s, 0, seq, plaintext)
    this.send({ v: 1, kind: 'sealed', seq, ct: b64u(ct) })
    return id
  }

  /**
   * 发送一条 query.* 请求并等待结果（按响应里的 request_id 关联）。
   * 例：await bot.sendQuery('query.lookup', { by: 'qq', value: '10001' })
   * 结果里的值都是字符串（见 PROTOCOL.md 8.1）。
   */
  sendQuery(type, payload = {}, timeoutMs = 10000) {
    const requestId = this.sendSealed(type, payload)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pendingQueries.delete(requestId)) reject(new Error(`查询超时：${type}`))
      }, timeoutMs)
      this.pendingQueries.set(requestId, {
        resolve: (result) => { clearTimeout(timer); resolve(result) },
        reject: (error) => { clearTimeout(timer); reject(error) },
      })
    })
  }

  verifyServerFrame(frame, pairContext) {
    const input = signatureInput(pairContext ? 'pair' : 'auth', frame.type, frame.id, frame.ts, frame.nonce,
      frame.payload, pairContext ? this.config.code : null)
    const ok = crypto.verify(null, Buffer.from(input, 'utf8'), ed25519PublicFromRaw(this.serverKey),
      b64uDecode(frame.sig))
    if (!ok) throw new Error(`服务端签名校验失败：${frame.type}`)
  }

  onOpen() {
    if (this.config.pairing_id && this.config.code) {
      this.helloId = this.nextId()
      this.helloNonce = b64u(crypto.randomBytes(16))
      const ts = Math.floor(Date.now() / 1000)
      const payload = {
        pairing_id: this.config.pairing_id,
        client_id: CLIENT_ID,
        client_key_id: CLIENT_KEY_ID,
        client_public_key: b64u(this.ed.rawPublic),
        provider: PROVIDER,
        scopes: SCOPES,
        capabilities: CAPABILITIES,
      }
      const input = signatureInput('pair', 'pair.hello', this.helloId, ts, this.helloNonce, payload,
        this.config.code)
      const sig = b64u(crypto.sign(null, Buffer.from(input, 'utf8'), this.ed.privateKey))
      this.send({ v: 1, kind: 'plain', type: 'pair.hello', id: this.helloId, ts, nonce: this.helloNonce,
        payload, sig })
    } else {
      this.sendAuthHello()
    }
  }

  sendAuthHello() {
    this.helloId = this.nextId()
    this.clientNonce = crypto.randomBytes(16)
    this.sendPlain('auth.hello', {
      client_id: CLIENT_ID,
      client_key_id: CLIENT_KEY_ID,
      client_public_key_sha256: b64u(crypto.createHash('sha256').update(this.ed.rawPublic).digest()),
      client_ephemeral_key: b64u(this.eph.rawPublic),
      client_nonce: b64u(this.clientNonce),
      provider: PROVIDER,
      scopes: SCOPES,
      capabilities: CAPABILITIES,
    }, false, this.helloId)
  }

  onMessage(text) {
    try {
      const frame = JSON.parse(text)
      if (frame.kind === 'sealed') this.onSealed(frame)
      else this.onPlain(frame)
    } catch (e) {
      this.log(`错误：${e.message}`)
      this.failed(e)
    }
  }

  onPlain(frame) {
    switch (frame.type) {
      case 'pair.challenge': {
        this.serverKey = b64uDecode(frame.payload.server_public_key)
        saveServerKey(this.serverKey)
        this.verifyServerFrame(frame, true)
        this.serverNonce = frame.payload.server_nonce
        this.sendPlain('pair.prove', {
          pairing_id: frame.payload.pairing_id,
          challenge_id: frame.payload.challenge_id,
          hello_id: this.helloId,
          client_id: CLIENT_ID,
          client_key_id: CLIENT_KEY_ID,
          client_public_key_sha256: b64u(crypto.createHash('sha256').update(this.ed.rawPublic).digest()),
          server_id: frame.payload.server_id,
          server_key_id: frame.payload.server_key_id,
          server_public_key_sha256: b64u(crypto.createHash('sha256').update(this.serverKey).digest()),
          client_nonce: this.helloNonce,
          server_nonce: frame.payload.server_nonce,
          selected_v: 1,
          client_capabilities_sha256: capabilitiesSha256(CAPABILITIES),
          server_capabilities_sha256: capabilitiesSha256(frame.payload.capabilities || CAPABILITIES),
        }, true)
        break
      }
      case 'pair.complete':
        this.verifyServerFrame(frame, true)
        this.log(`配对完成 session=${frame.payload.session_id}`)
        this.sendAuthHello()
        break
      case 'auth.challenge': {
        this.verifyServerFrame(frame, false)
        this.serverEphemeralKey = b64uDecode(frame.payload.server_ephemeral_key)
        this.serverNonce = frame.payload.server_nonce
        this.sendPlain('auth.prove', {
          challenge_id: frame.payload.challenge_id,
          hello_id: this.helloId,
          client_id: CLIENT_ID,
          client_key_id: CLIENT_KEY_ID,
          client_public_key_sha256: b64u(crypto.createHash('sha256').update(this.ed.rawPublic).digest()),
          client_ephemeral_key: b64u(this.eph.rawPublic),
          server_id: frame.payload.server_id,
          server_key_id: frame.payload.server_key_id,
          server_public_key_sha256: b64u(crypto.createHash('sha256').update(this.serverKey).digest()),
          server_ephemeral_key: frame.payload.server_ephemeral_key,
          client_nonce: b64u(this.clientNonce),
          server_nonce: this.serverNonce,
          selected_v: 1,
          client_capabilities_sha256: capabilitiesSha256(CAPABILITIES),
          server_capabilities_sha256: capabilitiesSha256(frame.payload.capabilities || CAPABILITIES),
        }, false)
        const shared = crypto.diffieHellman({
          privateKey: this.eph.privateKey,
          publicKey: x25519PublicFromRaw(this.serverEphemeralKey),
        })
        this.keys = deriveKeys(this.clientNonce, b64uDecode(this.serverNonce), shared)
        break
      }
      case 'error':
        this.failed(new Error(`服务端错误 ${frame.payload.code}: ${frame.payload.message}`))
        break
      default:
        this.log(`忽略明文帧 ${frame.type}`)
    }
  }

  onSealed(frame) {
    if (frame.seq <= this.inboundLastSeq) throw new Error(`seq 重放：${frame.seq}`)
    const plaintext = unseal(this.keys.keyS2c, 0, frame.seq, b64uDecode(frame.ct))
    this.inboundLastSeq = frame.seq
    const message = parseMessage(plaintext)
    switch (message.type) {
      case 'auth.result':
        this.sessionId = message.payload.session_id
        this.sendSealed('auth.confirm', { session_id: this.sessionId })
        this.ready = true
        this.log(`会话建立 session=${this.sessionId}`)
        if (this.done) this.done(true)
        this.handlers.onReady(this)
        break
      case 'verify.watch': {
        const code = message.payload.code
        this.log(`收到 verify.watch code=${code}`)
        this.handlers.onWatch(code, message.payload.provider)
        // 真实实现：在群里观测到该码、且已按平台策略（成员/角色等）判定有效后，再回报
        this.sendSealed('verify.matched', {
          code,
          subject: process.env.VA_BOT_SUBJECT || '10001',
          scope: process.env.VA_BOT_SCOPES || '',
          display_name: '参考bot',
          matched_at: Math.floor(Date.now() / 1000),
        })
        break
      }
      case 'verify.matched.ack':
        this.log(`verify.matched.ack consumed=${message.payload.consumed}`)
        this.handlers.onAck(message.payload.code, message.payload.consumed === 'true')
        break
      case 'verify.cancel':
        this.log(`收到 verify.cancel code=${message.payload.code}`)
        break
      case 'error': {
        const requestId = message.payload.request_id
        const pending = requestId ? this.pendingQueries.get(requestId) : null
        if (pending) {
          this.pendingQueries.delete(requestId)
          pending.reject(new Error(`查询错误 ${message.payload.code}: ${message.payload.message}`))
        } else {
          this.log(`服务端错误 ${message.payload.code}: ${message.payload.message}`)
        }
        break
      }
      case 'pong':
        break
      default: {
        if (message.type.endsWith('.result')) {
          const requestId = message.payload.request_id
          const pending = requestId ? this.pendingQueries.get(requestId) : null
          if (pending) {
            this.pendingQueries.delete(requestId)
            pending.resolve(message.payload)
            break
          }
        }
        this.log(`忽略密封帧 ${message.type}`)
      }
    }
  }

  close() {
    try {
      this.ws.close()
    } catch {
      /* ignore */
    }
  }
}

// 独立运行时：连上后等待并打印收到的事件
const args = parseArgs()
if (args.pairing) {
  const config = JSON.parse(fs.readFileSync(args.pairing, 'utf8'))
  const bot = new ReferenceBot(config, {})
  bot.handlers.onReady = async () => {
    if (args.watch) bot.sendSealed('verify.watch.ack', { code: args.watch, accepted: true })
    if (args.query) {
      const [type, by, value] = args.query.split(':')
      try {
        const result = await bot.sendQuery(`query.${type}`, { by, value })
        console.log(`[bot] query.${type} 结果：\n${JSON.stringify(result, null, 2)}`)
      } catch (e) {
        console.error(`[bot] 查询失败：${e.message}`)
      }
      bot.close()
    }
  }
  bot.connect().catch((e) => {
    console.error(`[bot] 失败：${e.message}`)
    process.exit(1)
  })
}

export { ReferenceBot }
