// M8 实机测试：管理命令（状态/账号/会话/强制登录/权限）+ 审计
//
// 前置：tools/e2e/grant-admin.ps1 已给 TestAdmin 授予 velocityauth.admin；
//       velocity 已启动，config 的 registration.gate=none。
import fs from 'node:fs'
import mineflayer from 'mineflayer'

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = Number(process.env.MC_PORT || 25565)
const VERSION = process.env.MC_VERSION || '1.20.4'
const PROXY_LOG = process.env.MC_PROXY_LOG || 'E:\\MC\\PluginTestServers\\velocity\\va-test-console.log'
const SETTLE = Number(process.env.MC_SETTLE_MS || 4200)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const runId = Math.random().toString(36).slice(2, 7)
const TARGET = ('tgt' + runId).slice(0, 12)
const TARGET_PASSWORD = 'TargetPass1'
const NEW_PASSWORD = 'NewPass9x'
const ADMIN = 'TestAdmin'
const ADMIN_PASSWORD = 'AdminPass1'

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

class Session {
  constructor(username) {
    this.username = username
    this.messages = []
    this.spawned = false
    this.ended = null
    this.bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VERSION, auth: 'offline', hideErrors: true })
    this.bot.on('spawn', () => { this.spawned = true })
    this.bot.on('messagestr', (m) => this.messages.push(m))
    this.bot.on('end', (r) => { this.ended = r })
    this.bot.on('error', () => {})
  }
  get profileName() { return this.bot.username }
  get inPlay() { return !!this.bot.entity }
  dump(label) {
    console.log(`  [诊断 ${label}] name=${this.username} spawned=${this.spawned} ended=${JSON.stringify(this.ended)}`)
    this.messages.slice(-10).forEach((m) => console.log(`    < ${m}`))
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
    try { this.bot.chat(text); return true } catch { return false }
  }
  quit() { try { this.bot.quit() } catch { /* ignore */ } }
}

async function connectAs(name) {
  const elapsed = Date.now() - lastConnectAt
  if (elapsed < SETTLE) await sleep(SETTLE - elapsed)
  lastConnectAt = Date.now()
  const session = new Session(name)
  const spawned = await session.waitSpawn()
  check(`${name}：进入登录服`, spawned, spawned ? '' : `end=${JSON.stringify(session.ended)}`)
  if (!spawned) session.dump('connect')
  return session
}

/** 注册或登录指定账号，并等待重连后返回已认证会话。 */
async function authenticate(connectionName, account, password) {
  const s = await connectAs(connectionName)
  if (!s.spawned) return null
  s.messages.length = 0
  s.chat(`/login ${account} ${password}`)
  const first = await s.waitMessage(/登录成功|账号或密码错误|已被锁定/, 15000)
  if (first && first.includes('账号或密码错误')) {
    s.messages.length = 0
    s.chat(`/register ${account} ${password} ${password}`)
    const registered = await s.waitMessage(/注册成功|已被占用/, 20000)
    check(`${connectionName}：注册 ${account}`, !!registered && registered.includes('注册成功'),
      registered || '（无响应）')
  } else if (first && first.includes('登录成功')) {
    check(`${connectionName}：登录 ${account}`, true, first)
  } else {
    check(`${connectionName}：登录 ${account}`, false, first || '（无响应）')
  }
  await s.waitEnd(20000)
  s.quit()
  await sleep(SETTLE)

  const back = await connectAs(connectionName)
  check(`${connectionName}：profile 为 ${account}`, back.profileName === account, `实际=${back.profileName}`)
  return back
}

async function main() {
  console.log(`\n=== M8 管理命令（管理员 ${ADMIN}，目标账号 ${TARGET}）===`)

  // 目标账号
  const target = await authenticate(nextName('M8T'), TARGET, TARGET_PASSWORD)
  // 管理员账号（连接名与账号名一致 → profile 重写后 UUID 不变，LuckPerms 授权仍然有效）
  const admin = await authenticate(ADMIN, ADMIN, ADMIN_PASSWORD)
  if (!admin || !target) return finish()

  // --- 权限：非管理员被拒绝 ---
  const plain = await connectAs(nextName('M8P'))
  plain.messages.length = 0
  plain.chat('/vauth status')
  const denied = await plain.waitMessage(/没有权限/, 10000)
  check('非管理员执行 /vauth 被拒绝', !!denied, denied || '（无响应）')
  plain.quit()
  await sleep(SETTLE)

  // --- status ---
  admin.messages.length = 0
  admin.chat('/vauth status')
  const statusHeader = await admin.waitMessage(/VelocityAuth 状态/, 10000)
  check('/vauth status 输出状态', !!statusHeader, statusHeader || '（无响应）')
  const dbLine = await admin.waitMessage(/数据库/, 5000)
  check('状态包含数据库信息', !!dbLine, dbLine || '（无响应）')

  // --- account info ---
  admin.messages.length = 0
  admin.chat(`/vauth account ${TARGET} info`)
  const infoHeader = await admin.waitMessage(new RegExp(`账号 ${TARGET}`), 10000)
  check('/vauth account <账号> info', !!infoHeader, infoHeader || '（无响应）')
  const lastServer = await admin.waitMessage(/上次子服/, 5000)
  check('info 含上次子服', !!lastServer, lastServer || '（无响应）')

  // --- sessions list ---
  admin.messages.length = 0
  admin.chat('/vauth sessions list')
  const sessionsHeader = await admin.waitMessage(/在线已认证会话/, 10000)
  check('/vauth sessions list', !!sessionsHeader, sessionsHeader || '（无响应）')
  const listed = await admin.waitMessage(new RegExp(TARGET), 5000)
  check('会话列表包含目标账号', !!listed, listed || '（无响应）')

  // --- lock ---
  admin.messages.length = 0
  admin.chat(`/vauth account ${TARGET} lock`)
  const locked = await admin.waitMessage(/已锁定/, 10000)
  check('/vauth account <账号> lock', !!locked, locked || '（无响应）')
  const kicked = await target.waitEnd(10000)
  check('锁定会踢出在线会话', kicked, `end=${target.ended}`)
  target.quit()

  const lockedTry = await connectAs(nextName('M8L'))
  lockedTry.messages.length = 0
  lockedTry.chat(`/login ${TARGET} ${TARGET_PASSWORD}`)
  const lockedMsg = await lockedTry.waitMessage(/已被锁定/, 15000)
  check('锁定后无法登录', !!lockedMsg, lockedMsg || '（无响应）')
  lockedTry.quit()
  await sleep(SETTLE)

  // --- unlock ---
  admin.messages.length = 0
  admin.chat(`/vauth account ${TARGET} unlock`)
  const unlocked = await admin.waitMessage(/已解锁/, 10000)
  check('/vauth account <账号> unlock', !!unlocked, unlocked || '（无响应）')

  // --- setpassword ---
  admin.messages.length = 0
  admin.chat(`/vauth account ${TARGET} setpassword ${NEW_PASSWORD}`)
  const pwSet = await admin.waitMessage(/已重置/, 10000)
  check('/vauth account <账号> setpassword', !!pwSet, pwSet || '（无响应）')

  const reloginName = nextName('M8N')
  const relogin = await connectAs(reloginName)
  relogin.messages.length = 0
  relogin.chat(`/login ${TARGET} ${NEW_PASSWORD}`)
  const newPwOk = await relogin.waitMessage(/登录成功/, 15000)
  check('强制改密后新密码可登录', !!newPwOk, newPwOk || '（无响应）')
  await relogin.waitEnd(20000)
  relogin.quit()
  await sleep(SETTLE)

  const relogin2 = await connectAs(reloginName)
  check(`改密后 profile 为 ${TARGET}`, relogin2.profileName === TARGET, `实际=${relogin2.profileName}`)
  check('已进入主服', lastServerFor(TARGET) === 'paper12111', `实际=${lastServerFor(TARGET)}`)

  // --- sessions kick ---
  admin.messages.length = 0
  admin.chat(`/vauth sessions kick ${TARGET}`)
  const kickMsg = await admin.waitMessage(/已踢出/, 10000)
  check('/vauth sessions kick', !!kickMsg, kickMsg || '（无响应）')
  const kicked2 = await relogin2.waitEnd(10000)
  check('目标会话被踢出', kicked2, `end=${relogin2.ended}`)
  relogin2.quit()
  await sleep(SETTLE)

  // --- unbind（未绑定时提示）---
  admin.messages.length = 0
  admin.chat(`/vauth account ${TARGET} unbind email`)
  const notBound = await admin.waitMessage(/尚未绑定/, 10000)
  check('/vauth account <账号> unbind', !!notBound, notBound || '（无响应）')

  // --- forcelogin ---
  admin.messages.length = 0
  admin.chat(`/vauth forcelogin ${TARGET} --kick`)
  const forceMsg = await admin.waitMessage(/强制登录/, 10000)
  check('/vauth forcelogin', !!forceMsg, forceMsg || '（无响应）')
  const forceEnded = await admin.waitEnd(20000)
  check('强制登录触发重连', forceEnded, `end=${admin.ended}`)
  admin.quit()
  await sleep(SETTLE)

  const forced = await connectAs(ADMIN)
  check(`强制登录后表现为 ${TARGET}`, forced.profileName === TARGET, `实际=${forced.profileName}`)
  forced.quit()
  await sleep(SETTLE)

  // --- reload ---
  const admin2 = await connectAs(ADMIN)
  admin2.messages.length = 0
  admin2.chat('/vauth reload')
  const reloaded = await admin2.waitMessage(/已重载/, 10000)
  check('/vauth reload', !!reloaded, reloaded || '（无响应）')

  // --- delete ---
  admin2.messages.length = 0
  admin2.chat(`/vauth account ${TARGET} delete`)
  const deleted = await admin2.waitMessage(/已删除/, 10000)
  check('/vauth account <账号> delete', !!deleted, deleted || '（无响应）')
  admin2.quit()

  console.log(`\nTARGET=${TARGET}`)
  return finish()
}

function finish() {
  const failed = results.filter((r) => !r.ok)
  console.log(`\n===== 结果：${results.length - failed.length}/${results.length} 通过 =====`)
  if (failed.length) {
    console.log('失败项：')
    failed.forEach((f) => console.log(`  - ${f.name} ${f.detail}`))
  }
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => {
  console.error('测试异常：', e)
  process.exit(1)
})
