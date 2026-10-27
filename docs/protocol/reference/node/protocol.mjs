// VelocityAuth bot 参考实现（Node.js）：配对 + 会话认证 + verify.watch/matched
//
// 依赖：仅 Node 内置 crypto 与全局 WebSocket（Node >= 22）。
// 用法：node bot-client.mjs --pairing <pairing.json> [--watch-file <file>]
//
// 该文件同时是 PROTOCOL.md 的参考实现，供 bot 侧开发者对照。
import crypto from 'node:crypto'
import fs from 'node:fs'

const B64 = {
  enc: (buf) => Buffer.from(buf).toString('base64url'),
  dec: (text) => Buffer.from(text, 'base64url'),
}

const b64u = B64.enc
const b64uDecode = B64.dec

// ---------------------------------------------------------------- 规范化

function render(value) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') return String(Math.trunc(value))
  if (typeof value === 'string') return value.replace(/\r/g, ' ').replace(/\n/g, ' ')
  if (Array.isArray(value)) return [...value].map(render).sort().join(',')
  throw new Error(`unsupported payload value: ${typeof value}`)
}

function canonical(payload) {
  return Object.keys(payload)
    .sort()
    .map((key) => `${key}=${render(payload[key])}\n`)
    .join('')
}

function signatureInput(context, type, id, ts, nonce, payload, code) {
  let text = 'VELOCITYAUTH-V1\n'
  text += `context=${context}\n`
  text += `type=${type}\n`
  text += `id=${id}\n`
  text += `ts=${ts}\n`
  text += `nonce=${nonce}\n`
  if (code !== null && code !== undefined) text += `code=${code}\n`
  text += canonical(payload)
  return text
}

function messagePlaintext(type, id, ts, payload) {
  let text = 'VELOCITYAUTH-MSG-V1\n'
  text += `type=${type}\n`
  text += `id=${id}\n`
  text += `ts=${ts}\n`
  text += canonical(payload)
  return Buffer.from(text, 'utf8')
}

function capabilitiesSha256(capabilities) {
  return b64u(crypto.createHash('sha256').update([...capabilities].sort().join('\n')).digest())
}

// ---------------------------------------------------------------- 密钥

const ED25519_PKCS8_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex')
const ED25519_SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex')
const X25519_PKCS8_PREFIX = Buffer.from('302e020100300506032b656e04220420', 'hex')
const X25519_SPKI_PREFIX = Buffer.from('302a300506032b656e032100', 'hex')

function ed25519FromSeed(seed) {
  const privateKey = crypto.createPrivateKey({
    key: Buffer.concat([ED25519_PKCS8_PREFIX, seed]),
    format: 'der',
    type: 'pkcs8',
  })
  const publicKey = crypto.createPublicKey(privateKey)
  const rawPublic = publicKey.export({ format: 'der', type: 'spki' }).subarray(-32)
  return { privateKey, publicKey, rawPublic }
}

function ed25519PublicFromRaw(raw) {
  return crypto.createPublicKey({
    key: Buffer.concat([ED25519_SPKI_PREFIX, raw]),
    format: 'der',
    type: 'spki',
  })
}

function x25519FromRaw(raw) {
  const privateKey = crypto.createPrivateKey({
    key: Buffer.concat([X25519_PKCS8_PREFIX, raw]),
    format: 'der',
    type: 'pkcs8',
  })
  const publicKey = crypto.createPublicKey(privateKey)
  const rawPublic = publicKey.export({ format: 'der', type: 'spki' }).subarray(-32)
  return { privateKey, publicKey, rawPublic }
}

function x25519PublicFromRaw(raw) {
  return crypto.createPublicKey({
    key: Buffer.concat([X25519_SPKI_PREFIX, raw]),
    format: 'der',
    type: 'spki',
  })
}

// ---------------------------------------------------------------- 密钥派生

function deriveKeys(clientNonce, serverNonce, shared) {
  const salt = crypto
    .createHash('sha256')
    .update(Buffer.concat([Buffer.from('VELOCITYAUTH-V1-SALT'), clientNonce, serverNonce]))
    .digest()
  const prk = crypto.createHmac('sha256', salt).update(shared).digest()
  const expand = (info) =>
    Buffer.from(crypto.hkdfSync('sha256', shared, salt, Buffer.from(info), 32))
  return {
    salt,
    prk,
    keyC2s: expand('velocityauth c2s'),
    keyS2c: expand('velocityauth s2c'),
  }
}

function sealNonce(epoch, seq) {
  const nonce = Buffer.alloc(12)
  nonce.writeUInt32BE(epoch >>> 0, 0)
  nonce.writeBigUInt64BE(BigInt(seq), 4)
  return nonce
}

function sealAad(seq) {
  return Buffer.from(`VELOCITYAUTH-SEAL-V1\nv=1\nseq=${seq}\n`, 'utf8')
}

function seal(key, epoch, seq, plaintext) {
  const cipher = crypto.createCipheriv('aes-256-gcm', key, sealNonce(epoch, seq))
  cipher.setAAD(sealAad(seq))
  return Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()])
}

function unseal(key, epoch, seq, ciphertext) {
  const data = Buffer.from(ciphertext)
  const tag = data.subarray(data.length - 16)
  const body = data.subarray(0, data.length - 16)
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, sealNonce(epoch, seq))
  decipher.setAAD(sealAad(seq))
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(body), decipher.final()])
}

function parseMessage(plaintext) {
  const text = Buffer.from(plaintext).toString('utf8')
  const lines = text.split('\n')
  const payload = {}
  let type = null
  let id = null
  for (const line of lines.slice(1)) {
    if (!line) continue
    const eq = line.indexOf('=')
    if (eq < 0) continue
    const key = line.slice(0, eq)
    const value = line.slice(eq + 1)
    if (key === 'type') type = value
    else if (key === 'id') id = value
    else if (key === 'ts') continue
    else payload[key] = value
  }
  return { type, id, payload }
}

export {
  b64u,
  b64uDecode,
  canonical,
  signatureInput,
  messagePlaintext,
  capabilitiesSha256,
  ed25519FromSeed,
  ed25519PublicFromRaw,
  x25519FromRaw,
  x25519PublicFromRaw,
  deriveKeys,
  sealNonce,
  sealAad,
  seal,
  unseal,
  parseMessage,
}
