// M9 实机测试：LibreLogin 迁移
//
// 前置：tools/e2e/make-legacy-db.ps1 已生成旧库，并通过环境变量告知账号名。
import fs from 'node:fs'
import mineflayer from 'mineflayer'

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = Number(process.env.MC_PORT || 25565)
const VERSION = process.env.MC_VERSION || '1.20.4'
const SETTLE = Number(process.env.MC_SETTLE_MS || 4200)
const LEGACY_DB = process.env.VA_LEGACY_DB
const BCRYPT_ACCOUNT = process.env.VA_LEGACY_BCRYPT
const SHA256_ACCOUNT = process.env.VA_LEGACY_SHA256
const LEGACY_PASSWORD = 'LegacyPass1'
const ADMIN = 'TestAdmin'
const ADMIN_PASSWORD = 'AdminPass1'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let connSeq = 0
let lastConnectAt = 0
const nextName = (prefix) => `${prefix}${Date.now() % 100000}${++connSeq}`.slice(0, 16)

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -> ' + detail : ''}`)
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

async function main() {
  console.log(`\n=== M9 LibreLogin 迁移（${BCRYPT_ACCOUNT} / ${SHA256_ACCOUNT}）===`)
  check('收到旧库路径', !!LEGACY_DB, LEGACY_DB || '(缺失)')

  // 管理员登录
  const adminName = nextName('M9A')
  let admin = await connectAs(adminName)
  admin.messages.length = 0
  admin.chat(`/login ${ADMIN} ${ADMIN_PASSWORD}`)
  const adminLogin = await admin.waitMessage(/登录成功/, 15000)
  check('管理员登录', !!adminLogin, adminLogin || '（无响应）')
  await admin.waitEnd(20000)
  admin.quit()
  await sleep(SETTLE)
  admin = await connectAs(adminName)

  // 试运行
  admin.messages.length = 0
  admin.chat(`/vauth migrate librelogin --dry-run --file ${LEGACY_DB}`)
  const dryRun = await admin.waitMessage(/试运行|未写入/, 20000)
  check('--dry-run 提示未写入', !!dryRun, dryRun || '（无响应）')
  if (!dryRun) admin.dump('dry-run')

  // 正式迁移
  admin.messages.length = 0
  admin.chat(`/vauth migrate librelogin --file ${LEGACY_DB}`)
  const imported = await admin.waitMessage(/已导入/, 25000)
  check('迁移导入统计', !!imported, imported || '（无响应）')
  const needsReset = await admin.waitMessage(/需重置/, 8000)
  check('报告"需重置"数量', !!needsReset, needsReset || '（无响应）')
  const done = await admin.waitMessage(/迁移完成/, 8000)
  check('迁移完成提示', !!done, done || '（无响应）')
  if (!imported) admin.dump('migrate')
  admin.quit()
  await sleep(SETTLE)

  // 用旧密码登录迁移过来的账号
  const legacyName = nextName('M9B')
  const legacy = await connectAs(legacyName)
  legacy.messages.length = 0
  legacy.chat(`/login ${BCRYPT_ACCOUNT} ${LEGACY_PASSWORD}`)
  const legacyLogin = await legacy.waitMessage(/登录成功/, 20000)
  check('迁移账号可用旧 bcrypt 密码登录', !!legacyLogin, legacyLogin || '（无响应）')
  await legacy.waitEnd(20000)
  legacy.quit()
  await sleep(SETTLE)

  const legacy2 = await connectAs(legacyName)
  check(`profile 重写为 ${BCRYPT_ACCOUNT}`, legacy2.profileName === BCRYPT_ACCOUNT,
    `实际=${legacy2.profileName}`)
  check('迁移账号可进入主服', lastServer(BCRYPT_ACCOUNT) === 'paper12111', `实际=${lastServer(BCRYPT_ACCOUNT)}`)
  legacy2.quit()
  await sleep(SETTLE)

  // 不可转换密码的账号：应无法用旧密码登录（占位哈希）
  const sha = await connectAs(nextName('M9C'))
  sha.messages.length = 0
  sha.chat(`/login ${SHA256_ACCOUNT} ${LEGACY_PASSWORD}`)
  const shaFail = await sha.waitMessage(/账号或密码错误/, 20000)
  check('不可转换密码的账号需重置（旧密码无效）', !!shaFail, shaFail || '（无响应）')
  sha.quit()

  return finish()
}

function lastServer(name) {
  try {
    const log = 'E:\\MC\\PluginTestServers\\velocity\\va-test-console.log'
    const lines = fs.readFileSync(log, 'latin1').split(/\r?\n/)
    const pattern = new RegExp(`\\[server connection\\] ${name} -> (\\S+) has connected`)
    let found = null
    for (const line of lines) {
      const match = line.match(pattern)
      if (match) found = match[1]
    }
    return found
  } catch {
    return null
  }
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
