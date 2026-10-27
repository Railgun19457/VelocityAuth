// 参考实现自检：对照 ../../vectors/ 的测试向量逐字节校验。
//
// 用法：node selfcheck.mjs
// 退出码 0 表示全部通过。
import fs from 'node:fs'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import {
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
  seal,
  sealNonce,
  sealAad,
  unseal,
  parseMessage,
} from './protocol.mjs'

const VECTORS = fileURLToPath(new URL('../../vectors/', import.meta.url))
const load = (name) => JSON.parse(fs.readFileSync(VECTORS + name, 'utf8'))

let failures = 0
function check(name, ok, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail && !ok ? '  -> ' + detail : ''}`)
}

// ---------------------------------------------------------------- 规范化
const canon = load('canonicalization.json')
let canonOk = true
for (const c of canon.cases) {
  if (canonical(c.input) !== c.canonical) canonOk = false
}
check('canonical() 用例全部一致', canonOk)

// ---------------------------------------------------------------- 密钥
const keys = load('keys.json')
const clientEd = ed25519FromSeed(Buffer.from(keys.client_ed25519.seed_hex, 'hex'))
check('client Ed25519 公钥与向量一致',
  b64u(clientEd.rawPublic) === keys.client_ed25519.public_key_b64url)
check('client Ed25519 指纹与向量一致',
  b64u(crypto.createHash('sha256').update(clientEd.rawPublic).digest())
    === keys.client_ed25519.public_key_sha256_b64url)

const clientEph = x25519FromRaw(Buffer.from(keys.client_x25519_ephemeral.private_key_hex, 'hex'))
check('client X25519 公钥与向量一致',
  b64u(clientEph.rawPublic) === keys.client_x25519_ephemeral.public_key_b64url)

// ---------------------------------------------------------------- 配对帧
const pairing = load('pairing.json')
const serverEd = ed25519FromSeed(Buffer.from(keys.server_ed25519.seed_hex, 'hex'))
let pairingOk = true
for (const entry of pairing.frames) {
  const f = entry.frame
  const input = signatureInput('pair', f.type, f.id, f.ts, f.nonce, f.payload, pairing.code)
  if (input !== entry.signature_input) {
    pairingOk = false
    console.log(`    signature_input 不一致：${f.type}`)
    continue
  }
  const serverSigned = f.type === 'pair.challenge' || f.type === 'pair.complete'
  const verifyKey = serverSigned ? serverEd.publicKey
    : ed25519PublicFromRaw(b64uDecode(keys.client_ed25519.public_key_b64url))
  const sigOk = crypto.verify(null, Buffer.from(input, 'utf8'), verifyKey, b64uDecode(f.sig))
  if (!sigOk) {
    pairingOk = false
    console.log(`    验签失败：${f.type}`)
  }
  // 服务端签名的两帧必须能逐字节复现（Ed25519 确定性签名）
  if (serverSigned) {
    const produced = b64u(crypto.sign(null, Buffer.from(input, 'utf8'), serverEd.privateKey))
    if (produced !== f.sig) {
      pairingOk = false
      console.log(`    签名不一致：${f.type}`)
    }
  }
}
check('pairing.json 全部帧签名输入与签名一致', pairingOk)

const provePayload = pairing.frames[2].frame.payload
check('capabilities 指纹与向量一致',
  capabilitiesSha256(['verify.v1']) === provePayload.client_capabilities_sha256)

// ---------------------------------------------------------------- 会话帧
const session = load('session.json')
let sessionOk = true
for (const entry of session.frames) {
  const f = entry.frame
  const input = signatureInput('auth', f.type, f.id, f.ts, f.nonce, f.payload, null)
  if (input !== entry.signature_input) {
    sessionOk = false
    console.log(`    signature_input 不一致：${f.type}`)
  }
  const serverSigned = f.type === 'auth.challenge'
  const verifyKey = serverSigned ? serverEd.publicKey
    : ed25519PublicFromRaw(b64uDecode(keys.client_ed25519.public_key_b64url))
  if (!crypto.verify(null, Buffer.from(input, 'utf8'), verifyKey, b64uDecode(f.sig))) {
    sessionOk = false
    console.log(`    验签失败：${f.type}`)
  }
}
check('session.json 全部帧签名输入与签名一致', sessionOk)

// ---------------------------------------------------------------- 密钥派生
const shared = crypto.diffieHellman({
  privateKey: clientEph.privateKey,
  publicKey: x25519PublicFromRaw(
    b64uDecode(keys.server_x25519_ephemeral.public_key_b64url)),
})
check('X25519 shared secret 与向量一致',
  b64u(shared) === session.key_schedule.shared_secret_b64url)

const helloPayload = session.frames[0].frame.payload
const challengePayload = session.frames[1].frame.payload
const ks = deriveKeys(b64uDecode(helloPayload.client_nonce),
  b64uDecode(challengePayload.server_nonce), shared)
check('HKDF salt 与向量一致', b64u(ks.salt) === session.key_schedule.salt)
check('HKDF prk 与向量一致', b64u(ks.prk) === session.key_schedule.prk)
check('key_c2s 与向量一致', b64u(ks.keyC2s) === session.key_schedule.key_c2s)
check('key_s2c 与向量一致', b64u(ks.keyS2c) === session.key_schedule.key_s2c)

// ---------------------------------------------------------------- 密封帧
let sealedOk = true
for (const entry of session.sealed) {
  const key = entry.key === 'key_c2s' ? ks.keyC2s : ks.keyS2c
  if (b64u(sealNonce(entry.epoch, entry.seq)) !== entry.nonce_b64url) sealedOk = false
  if (sealAad(entry.seq).toString('utf8') !== entry.aad) sealedOk = false
  const plaintext = Buffer.from(entry.plaintext, 'utf8')
  const ct = seal(key, entry.epoch, entry.seq, plaintext)
  if (b64u(ct) !== entry.ct) {
    sealedOk = false
    console.log(`    ct 不一致：seq=${entry.seq}`)
  }
  const back = unseal(key, entry.epoch, entry.seq, ct)
  if (!back.equals(plaintext)) sealedOk = false
  const parsed = parseMessage(plaintext)
  if (!parsed.type || !parsed.id) sealedOk = false
}
check('sealed 帧 nonce/AAD/密文/解密全部一致', sealedOk)

const watch = parseMessage(Buffer.from(session.sealed[2].plaintext, 'utf8'))
check('内层明文解析正确',
  watch.type === 'verify.watch' && watch.payload.code === 'MC-8F3K2A'
  && watch.payload.group_id === '123456' && watch.payload.ttl === '300')

console.log(failures === 0
  ? '\n参考实现：全部测试向量通过'
  : `\n参考实现：${failures} 项未通过`)
process.exit(failures === 0 ? 0 : 1)
