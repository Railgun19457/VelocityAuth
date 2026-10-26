// M5 实机测试：绑定与找回（邮箱绑定 / 解绑 / 找回密码 / 重置密码）
//
// 用法：node m5-binding.mjs   （VA_GATE 必须为 none；smtp 指向本地捕获服务）
import { SMTPServer } from 'smtp-server'
import fs from 'node:fs'
import mineflayer from 'mineflayer'

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = Number(process.env.MC_PORT || 25565)
const VERSION = process.env.MC_VERSION || '1.20.4'
const PROXY_LOG = process.env.MC_PROXY_LOG || 'E:\\MC\\PluginTestServers\\velocity\\va-test-console.log'
const SETTLE = Number(process.env.MC_SETTLE_MS || 4200)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const runId = Math.random().toString(36).slice(2, 7)
const ACCOUNT = ('va' + runId).slice(0, 12)
const PASSWORD = 'Passw0rd'
const NEW_PASSWORD = 'NewPass1'
const EMAIL = `va${runId}@qq.com`
const MISSING_EMAIL = `nobody${runId}@qq.com`

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

/** 清空已捕获邮件。 */
function clearMails() {
  capturedMails.length = 0
}

/** 等待至少一封新邮件并取出其中的验证码。 */
async function waitMailCode(timeout = 15000) {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    if (capturedMails.length > 0) {
      return extractCode(capturedMails[capturedMails.length - 1].raw)
    }
    await sleep(200)
  }
  return null
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

const connectNew = (prefix) => connectAs(nextName(prefix))

// ---------------------------------------------------------------- 测试阶段

async function registerAccount() {
  console.log(`\n=== M5 绑定与找回（账号 ${ACCOUNT}）===`)
  const name = nextName('M5R')
  const s = await connectAs(name)
  if (!s.spawned) return null
  s.messages.length = 0
  s.chat(`/register ${ACCOUNT} ${PASSWORD} ${PASSWORD}`)
  const success = await s.waitMessage(/注册成功/, 20000)
  check('注册账号', !!success, success || '（无响应）')
  await s.waitEnd(20000)
  s.quit()
  await sleep(SETTLE)

  const s2 = await connectAs(name)
  check(`profile 重写为 ${ACCOUNT}`, s2.profileName === ACCOUNT, `实际=${s2.profileName}`)
  check('已进入主服 paper12111', lastServerFor(ACCOUNT) === 'paper12111', `实际=${lastServerFor(ACCOUNT)}`)
  return { session: s2, name }
}

async function phaseBindEmail(session) {
  console.log('\n--- 邮箱绑定 ---')
  clearMails()
  session.messages.length = 0
  session.chat(`/bind email ${EMAIL}`)
  const sent = await session.waitMessage(/验证码已发送至/, 20000)
  check('绑定邮箱：验证码已发送', !!sent, sent || '（无响应）')
  const code = await waitMailCode(20000)
  check('绑定邮箱：邮件可取码', !!code, code ? '已提取' : '提取失败')

  session.messages.length = 0
  session.chat(`/verify ${code}`)
  const bound = await session.waitMessage(/绑定成功/, 15000)
  check('绑定邮箱成功', !!bound, bound || '（无响应）')
  if (!bound) session.dump('bind-email')

  session.messages.length = 0
  session.chat('/bind email other@qq.com')
  const already = await session.waitMessage(/已绑定了该类型/, 10000)
  check('重复绑定被拒绝', !!already, already || '（无响应）')

  session.messages.length = 0
  session.chat('/bind email not-an-email')
  const invalid = await session.waitMessage(/格式不正确/, 10000)
  check('非法邮箱被拒绝', !!invalid, invalid || '（无响应）')
}

async function phaseBindQq(session) {
  console.log('\n--- QQ 绑定（无 bot 连接时应提示服务不可用；完整 QQ 闭环见 M6）---')
  session.messages.length = 0
  session.chat('/bind qq')
  const unavailable = await session.waitMessage(/QQ 验证服务当前不可用/, 15000)
  check('无 bot 连接时 QQ 渠道不可用', !!unavailable, unavailable || '（无响应）')
  if (!unavailable) session.dump('bind-qq')

  session.messages.length = 0
  session.chat('/verify MC-XXXXXX')
  const noSession = await session.waitMessage(/没有进行中的验证/, 10000)
  check('无会话时 /verify 提示正确', !!noSession, noSession || '（无响应）')
}

async function phaseRecovery() {
  console.log('\n--- 找回密码 ---')
  const name = nextName('M5F')
  const s = await connectAs(name)
  if (!s.spawned) return null

  // 防枚举：不存在的账号返回同样的提示，且不发邮件
  clearMails()
  s.messages.length = 0
  s.chat(`/forgot nobody${runId} email`)
  const generic1 = await s.waitMessage(/如果该账号存在/, 10000)
  check('不存在账号：返回统一提示', !!generic1, generic1 || '（无响应）')
  await sleep(2500)
  check('不存在账号：未发送邮件', capturedMails.length === 0, `邮件数 ${capturedMails.length}`)

  // 只给账号名 → 提示选择渠道
  s.messages.length = 0
  s.chat(`/forgot ${ACCOUNT}`)
  const choose = await s.waitMessage(/请选择找回方式/, 10000)
  check('列出找回渠道', !!choose, choose || '（无响应）')

  // 选择 email 渠道 → 发码
  clearMails()
  s.messages.length = 0
  s.chat(`/forgot ${ACCOUNT} email`)
  const generic2 = await s.waitMessage(/如果该账号存在/, 10000)
  check('存在账号：返回统一提示', !!generic2, generic2 || '（无响应）')
  const code = await waitMailCode(20000)
  check('找回：邮件可取码', !!code, code ? '已提取' : '提取失败')

  // 未验证前不能重置密码
  s.messages.length = 0
  s.chat(`/resetpassword ${NEW_PASSWORD} ${NEW_PASSWORD}`)
  const noGrant = await s.waitMessage(/没有可用的重置授权/, 10000)
  check('未验证前无法重置密码', !!noGrant, noGrant || '（无响应）')

  // 输入验证码 → 获得重置授权
  s.messages.length = 0
  s.chat(`/verify ${code}`)
  const verified = await s.waitMessage(/请设置新密码/, 15000)
  check('验证通过后获得重置授权', !!verified, verified || '（无响应）')

  // 两次密码不一致
  s.messages.length = 0
  s.chat(`/resetpassword ${NEW_PASSWORD} Different1`)
  const mismatch = await s.waitMessage(/不一致/, 10000)
  check('两次密码不一致被拒绝', !!mismatch, mismatch || '（无响应）')

  // 重新走一次找回并正确重置
  clearMails()
  s.messages.length = 0
  s.chat(`/forgot ${ACCOUNT} email`)
  await s.waitMessage(/如果该账号存在/, 10000)
  const code2 = await waitMailCode(20000)
  s.messages.length = 0
  s.chat(`/verify ${code2}`)
  await s.waitMessage(/请设置新密码/, 15000)
  s.messages.length = 0
  s.chat(`/resetpassword ${NEW_PASSWORD} ${NEW_PASSWORD}`)
  const reset = await s.waitMessage(/密码已重置/, 15000)
  check('重置密码成功', !!reset, reset || '（无响应）')
  const ended = await s.waitEnd(20000)
  check('重置后自动重连登录', ended, `end=${s.ended}`)
  if (!reset) s.dump('reset')
  s.quit()
  await sleep(SETTLE)

  const s2 = await connectAs(name)
  check(`重置后自动登录为 ${ACCOUNT}`, s2.profileName === ACCOUNT, `实际=${s2.profileName}`)
  s2.quit()
  await sleep(SETTLE)
  return name
}

async function phaseNewPasswordAndUnbind() {
  console.log('\n--- 新密码登录 / 解绑 ---')
  const name = nextName('M5N')
  const s = await connectAs(name)
  if (!s.spawned) return

  // 旧密码应失效
  s.messages.length = 0
  s.chat(`/login ${ACCOUNT} ${PASSWORD}`)
  const oldFails = await s.waitMessage(/账号或密码错误/, 15000)
  check('旧密码已失效', !!oldFails, oldFails || '（无响应）')

  // 新密码可登录
  s.messages.length = 0
  s.chat(`/login ${ACCOUNT} ${NEW_PASSWORD}`)
  const loginOk = await s.waitMessage(/登录成功/, 15000)
  check('新密码可登录', !!loginOk, loginOk || '（无响应）')
  await s.waitEnd(20000)
  s.quit()
  await sleep(SETTLE)

  const s2 = await connectAs(name)
  check(`新密码登录后 profile 为 ${ACCOUNT}`, s2.profileName === ACCOUNT, `实际=${s2.profileName}`)

  // 解绑需要验证
  clearMails()
  s2.messages.length = 0
  s2.chat('/unbind email')
  const sent = await s2.waitMessage(/验证码已发送至/, 20000)
  check('解绑：需要验证', !!sent, sent || '（无响应）')
  const code = await waitMailCode(20000)
  s2.messages.length = 0
  s2.chat(`/verify ${code}`)
  const unbound = await s2.waitMessage(/解绑成功/, 15000)
  check('解绑成功', !!unbound, unbound || '（无响应）')
  if (!unbound) s2.dump('unbind')

  s2.messages.length = 0
  s2.chat('/unbind email')
  const notBound = await s2.waitMessage(/尚未绑定该类型/, 10000)
  check('重复解绑被拒绝', !!notBound, notBound || '（无响应）')
  s2.quit()
  await sleep(SETTLE)
}

async function main() {
  let smtp = null
  try {
    smtp = await startSmtpCapture()
    const registered = await registerAccount()
    if (registered) {
      await phaseBindEmail(registered.session)
      await phaseBindQq(registered.session)
      registered.session.quit()
      await sleep(SETTLE)
      await phaseRecovery()
      await phaseNewPasswordAndUnbind()
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
