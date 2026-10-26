// M4 实机测试：统一验证会话（email 门槛 / QQ 门槛）
//
// 用法：node m4-gate.mjs      （VA_GATE=email | qq，由 tools/e2e/run-m4.ps1 驱动）
//
// email 渠道：内嵌本地 SMTP 捕获服务（127.0.0.1:2525），从邮件正文取出验证码，
//             再在游戏内 /verify 输入，验证「域名规则 → 发码 → 输码 → 建号 → 绑定邮箱 → 重连」全链路。
// qq 渠道：从游戏内提示取出验证码，验证「出码 → 错误码 → 取消 → 超时 → 输码建号」。
import { SMTPServer } from 'smtp-server'
import fs from 'node:fs'
import mineflayer from 'mineflayer'

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = Number(process.env.MC_PORT || 25565)
const VERSION = process.env.MC_VERSION || '1.20.4'
const PROXY_LOG = process.env.MC_PROXY_LOG || 'E:\\MC\\PluginTestServers\\velocity\\va-test-console.log'
const SETTLE = Number(process.env.MC_SETTLE_MS || 4200)
const GATE = process.env.VA_GATE || 'email'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const runId = Math.random().toString(36).slice(2, 7)
const ACCOUNT = ('va' + runId).slice(0, 12)
const PASSWORD = 'Passw0rd'
const EMAIL = `va${runId}@qq.com`

/** 每次连接用独立连接名：未认证玩家的 UUID 由连接名派生，重名会被代理当作重复登录拒绝。 */
let connSeq = 0
let lastConnectAt = 0
const nextName = (prefix) => `${prefix}${runId}${++connSeq}`.slice(0, 16)

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -> ' + detail : ''}`)
}

function proxyLogLines() {
  try {
    return fs.readFileSync(PROXY_LOG, 'latin1').split(/\r?\n/)
  } catch {
    return []
  }
}

function lastServerFor(name) {
  const pattern = new RegExp(`\\[server connection\\] ${name} -> (\\S+) has connected`)
  let found = null
  for (const line of proxyLogLines()) {
    const match = line.match(pattern)
    if (match) found = match[1]
  }
  return found
}

// ---------------------------------------------------------------- SMTP 捕获

const capturedMails = []

function decodeQuotedPrintable(text) {
  return text
    .replace(/=\r?\n/g, '')
    .replace(/=([0-9A-Fa-f]{2})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
}

function extractCode(raw) {
  const headerEnd = raw.indexOf('\r\n\r\n') >= 0 ? raw.indexOf('\r\n\r\n') + 4 : raw.indexOf('\n\n') + 2
  const headers = raw.slice(0, headerEnd)
  const body = raw.slice(headerEnd)
  const candidates = []
  if (/content-transfer-encoding:\s*base64/i.test(headers)) {
    try {
      candidates.push(Buffer.from(body.replace(/\s+/g, ''), 'base64').toString('utf8'))
    } catch {
      /* ignore */
    }
  }
  if (/content-transfer-encoding:\s*quoted-printable/i.test(headers)) {
    candidates.push(decodeQuotedPrintable(body))
  }
  candidates.push(body, raw)
  for (const text of candidates) {
    const match = text.match(/[:：]\s*(\d{6})\b/) || text.match(/\b(\d{6})\b/)
    if (match) return match[1]
  }
  return null
}

async function startSmtpCapture() {
  const server = new SMTPServer({
    authOptional: true,
    disabledCommands: ['STARTTLS'],
    onData(stream, session, callback) {
      let raw = ''
      stream.on('data', (chunk) => {
        raw += chunk.toString('utf8')
      })
      stream.on('end', () => {
        capturedMails.push({ raw, to: session.envelope.rcptTo.map((r) => r.address) })
        callback()
      })
    },
  })
  await new Promise((resolve, reject) => {
    server.listen(2525, '127.0.0.1', resolve)
    server.on('error', reject)
  })
  return server
}

// ---------------------------------------------------------------- 会话

class Session {
  constructor(username) {
    this.username = username
    this.messages = []
    this.spawned = false
    this.ended = null
    this.kicked = null
    this.bot = mineflayer.createBot({
      host: HOST,
      port: PORT,
      username,
      version: VERSION,
      auth: 'offline',
      hideErrors: true,
    })
    this.bot.on('spawn', () => {
      this.spawned = true
    })
    this.bot.on('messagestr', (m) => this.messages.push(m))
    this.bot.on('kicked', (r) => {
      this.kicked = r
    })
    this.bot.on('end', (r) => {
      this.ended = r
    })
    this.bot.on('error', () => {})
  }

  get profileName() {
    return this.bot.username
  }

  get inPlay() {
    return !!this.bot.entity
  }

  dump(label) {
    console.log(`  [诊断 ${label}] name=${this.username} spawned=${this.spawned} ended=${JSON.stringify(this.ended)} kicked=${JSON.stringify(this.kicked)}`)
    this.messages.slice(-8).forEach((m) => console.log(`    < ${m}`))
  }

  async waitSpawn(timeout = 25000) {
    const start = Date.now()
    while (Date.now() - start < timeout) {
      if (this.spawned) return true
      if (this.ended !== null) return false
      await sleep(150)
    }
    return false
  }

  async waitEnd(timeout = 15000) {
    const start = Date.now()
    while (Date.now() - start < timeout) {
      if (this.ended !== null) return true
      await sleep(150)
    }
    return false
  }

  async waitMessage(pattern, timeout = 10000) {
    const start = Date.now()
    while (Date.now() - start < timeout) {
      const hit = this.messages.find((m) => pattern.test(m))
      if (hit) return hit
      await sleep(150)
    }
    return null
  }

  chat(text) {
    if (!this.inPlay) return false
    try {
      this.bot.chat(text)
      return true
    } catch {
      return false
    }
  }

  quit() {
    try {
      this.bot.quit()
    } catch {
      /* ignore */
    }
  }
}

async function connectAs(name) {
  // Velocity login-ratelimit 默认 3000ms（按 IP），保证两次连接间隔足够
  const elapsed = Date.now() - lastConnectAt
  if (elapsed < SETTLE) {
    await sleep(SETTLE - elapsed)
  }
  lastConnectAt = Date.now()
  const session = new Session(name)
  const spawned = await session.waitSpawn()
  check(`${name}：进入登录服`, spawned,
    spawned ? '' : `end=${JSON.stringify(session.ended)} kicked=${JSON.stringify(session.kicked)}`)
  if (!spawned) session.dump('connect')
  return session
}

/** 新连接名（用于未认证会话）。 */
function connectNew(prefix) {
  return connectAs(nextName(prefix))
}

// ---------------------------------------------------------------- 测试阶段

async function phaseEmailGate() {
  console.log(`\n=== M4 email 门槛（账号 ${ACCOUNT}）===`)

  // 域名白名单：gmail.com 应被拒绝
  const s0 = await connectNew('M4A')
  if (s0.spawned) {
    s0.messages.length = 0
    s0.chat(`/register ${ACCOUNT}x ${PASSWORD} someone@gmail.com`)
    const denied = await s0.waitMessage(/域名不被允许/, 10000)
    check('邮箱域名白名单生效', !!denied, denied || '（无响应）')
    if (!denied) s0.dump('domain')
    s0.quit()
    await sleep(SETTLE)
  }

  const nameB = nextName('M4B')
  const s = await connectAs(nameB)
  if (!s.spawned) return

  capturedMails.length = 0
  s.messages.length = 0
  s.chat(`/register ${ACCOUNT} ${PASSWORD} ${EMAIL}`)
  const sent = await s.waitMessage(/验证码已发送至/, 20000)
  check('邮箱验证码已发送提示', !!sent, sent || '（无响应）')
  check('SMTP 捕获到邮件', capturedMails.length === 1, `收到 ${capturedMails.length} 封`)
  const code = capturedMails.length ? extractCode(capturedMails[0].raw) : null
  check('可从邮件正文提取验证码', !!code, code ? '已提取' : '提取失败')
  if (!sent) s.dump('email-send')
  if (capturedMails.length && !code) console.log(`  [邮件原文]\n${capturedMails[0].raw}`)

  s.messages.length = 0
  s.chat('/verify 000000')
  const wrong = await s.waitMessage(/验证码错误/, 10000)
  check('错误验证码被拒绝', !!wrong, wrong || '（无响应）')

  s.messages.length = 0
  s.chat(`/verify ${code}`)
  const ok = await s.waitMessage(/验证成功/, 10000)
  check('正确验证码通过', !!ok, ok || '（无响应）')
  const success = await s.waitMessage(/注册成功/, 20000)
  check('验证后注册成功', !!success, success || '（无响应）')
  const ended = await s.waitEnd(20000)
  check('注册后触发重连', ended, `end=${s.ended}`)
  if (!success) s.dump('register')
  s.quit()
  await sleep(SETTLE)

  const s2 = await connectAs(nameB)
  check(`profile 重写为 ${ACCOUNT}`, s2.profileName === ACCOUNT, `实际=${s2.profileName}`)
  check('已认证玩家进入主服 paper12111', lastServerFor(ACCOUNT) === 'paper12111', `实际=${lastServerFor(ACCOUNT)}`)
  s2.quit()
  await sleep(SETTLE)
}

async function phaseQqGate() {
  console.log(`\n=== M4 QQ 门槛（账号 ${ACCOUNT}）===`)

  const nameC = nextName('M4C')
  const s = await connectAs(nameC)
  if (!s.spawned) return

  s.messages.length = 0
  s.chat(`/register ${ACCOUNT} ${PASSWORD}`)
  const instruction = await s.waitMessage(/请在 QQ 群/, 20000)
  check('QQ 出码提示', !!instruction, instruction || '（无响应）')
  const codeLine = await s.waitMessage(/MC-[A-Z0-9]+/, 10000)
  check('游戏内显示可复制验证码', !!codeLine, codeLine || '（未找到验证码）')
  const code = codeLine ? (codeLine.match(/MC-[A-Z0-9]+/) || [null])[0] : null
  if (!instruction) s.dump('qq-start')

  s.messages.length = 0
  s.chat('/verify MC-XXXXXX')
  const wrong = await s.waitMessage(/验证码错误/, 10000)
  check('错误验证码被拒绝', !!wrong, wrong || '（无响应）')

  s.messages.length = 0
  s.chat('/verify cancel')
  const cancelled = await s.waitMessage(/已取消/, 10000)
  check('可主动取消验证', !!cancelled, cancelled || '（无响应）')

  s.messages.length = 0
  s.chat(`/verify ${code}`)
  const noSession = await s.waitMessage(/没有进行中的验证/, 10000)
  check('取消后会话失效', !!noSession, noSession || '（无响应）')

  // 超时（run-m4.ps1 会把 ttl-seconds 设为 30）
  s.messages.length = 0
  s.chat(`/register ${ACCOUNT} ${PASSWORD}`)
  const second = await s.waitMessage(/请在 QQ 群/, 20000)
  check('可重新发起验证', !!second, second || '（无响应）')
  console.log('  等待会话超时（约 32 秒）…')
  await sleep(32000)
  s.messages.length = 0
  s.chat(`/verify ${code}`)
  const expired = await s.waitMessage(/已过期|没有进行中的验证/, 10000)
  check('会话超时后失效', !!expired, expired || '（无响应）')

  // 重新发起并用正确码完成
  s.messages.length = 0
  s.chat(`/register ${ACCOUNT} ${PASSWORD}`)
  const third = await s.waitMessage(/请在 QQ 群/, 20000)
  check('再次发起验证', !!third, third || '（无响应）')
  const line2 = await s.waitMessage(/MC-[A-Z0-9]+/, 10000)
  const code2 = line2 ? (line2.match(/MC-[A-Z0-9]+/) || [null])[0] : null
  s.messages.length = 0
  s.chat(`/verify ${code2}`)
  const success = await s.waitMessage(/注册成功/, 20000)
  check('QQ 验证通过后建号', !!success, success || '（无响应）')
  if (!success) s.dump('qq-register')
  await s.waitEnd(20000)
  s.quit()
  await sleep(SETTLE)

  const s2 = await connectAs(nameC)
  check(`profile 重写为 ${ACCOUNT}`, s2.profileName === ACCOUNT, `实际=${s2.profileName}`)
  s2.quit()
  await sleep(SETTLE)
}

async function main() {
  let smtp = null
  try {
    if (GATE === 'email') {
      smtp = await startSmtpCapture()
      await phaseEmailGate()
    } else {
      await phaseQqGate()
    }
  } catch (e) {
    console.error('测试异常：', e)
    results.push({ name: 'unexpected-error', ok: false, detail: String(e) })
  } finally {
    if (smtp) smtp.close()
  }
  const failed = results.filter((r) => !r.ok)
  console.log(`\n===== 结果：${results.length - failed.length}/${results.length} 通过 =====`)
  if (failed.length) {
    console.log('失败项：')
    failed.forEach((f) => console.log(`  - ${f.name} ${f.detail}`))
  }
  process.exit(failed.length ? 1 : 0)
}

main()
