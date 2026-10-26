// M6 实机测试：真实 bot 通道完成 QQ 验证门槛注册（M4 遗留的 QQ 闭环）
//
// 前置：tools/e2e/run-m6.ps1 已启动 Velocity 并写入 pairing.json。
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ReferenceBot } from './bot-client.mjs'
import mineflayer from 'mineflayer'

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = Number(process.env.MC_PORT || 25565)
const VERSION = process.env.MC_VERSION || '1.20.4'
const PROXY_LOG = process.env.MC_PROXY_LOG || 'E:\\MC\\PluginTestServers\\velocity\\va-test-console.log'
const SETTLE = Number(process.env.MC_SETTLE_MS || 4200)
const PAIRING_FILE = process.env.VA_PAIRING || fileURLToPath(new URL('./pairing.json', import.meta.url))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const runId = Math.random().toString(36).slice(2, 7)
const ACCOUNT = ('va' + runId).slice(0, 12)
const PASSWORD = 'Passw0rd'
const BOT_QQ = '20001'

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

async function main() {
  const pairing = JSON.parse(fs.readFileSync(PAIRING_FILE, 'utf8'))
  check('读取配对信息', !!pairing.pairing_id && !!pairing.code, `${pairing.host}:${pairing.port}`)

  // 1) bot 配对 + 建立会话
  const bot = new ReferenceBot(pairing, {})
  bot.handlers.onAck = (code, consumed) => {
    bot.lastAck = { code, consumed }
  }
  let watchCode = null
  bot.handlers.onWatch = (code) => { watchCode = code }
  await bot.connect()
  const ready = await new Promise((resolve) => {
    const start = Date.now()
    const timer = setInterval(() => {
      if (bot.ready) { clearInterval(timer); resolve(true) }
      else if (Date.now() - start > 20000) { clearInterval(timer); resolve(false) }
    }, 100)
  })
  check('bot 配对并建立加密会话', ready, ready ? `session=${bot.sessionId}` : '超时')

  // 2) 玩家以 QQ 门槛注册
  const name = `M6${runId}1`.slice(0, 16)
  const player = new Session(name)
  const spawned = await player.waitSpawn()
  check(`${name}：进入登录服`, spawned, spawned ? '' : `end=${player.ended}`)

  player.messages.length = 0
  player.chat(`/register ${ACCOUNT} ${PASSWORD}`)
  const instruction = await player.waitMessage(/请在 QQ 群/, 20000)
  check('游戏内出码提示', !!instruction, instruction || '（无响应）')

  // bot 应收到 verify.watch（插件 → bot）
  const watchOk = await new Promise((resolve) => {
    const start = Date.now()
    const timer = setInterval(() => {
      if (watchCode) { clearInterval(timer); resolve(true) }
      else if (Date.now() - start > 10000) { clearInterval(timer); resolve(false) }
    }, 100)
  })
  check('bot 收到 verify.watch', watchOk, watchCode ? `code=${watchCode}` : '超时')

  // bot 已自动回复 verify.matched，插件应完成注册
  const success = await player.waitMessage(/注册成功/, 25000)
  check('群消息命中后注册成功（QQ 门槛闭环）', !!success, success || '（无响应）')
  const ackOk = await new Promise((resolve) => {
    const start = Date.now()
    const timer = setInterval(() => {
      if (bot.lastAck) { clearInterval(timer); resolve(bot.lastAck.consumed) }
      else if (Date.now() - start > 10000) { clearInterval(timer); resolve(false) }
    }, 100)
  })
  check('bot 收到 verify.matched.ack(consumed=true)', ackOk, JSON.stringify(bot.lastAck || {}))

  const ended = await player.waitEnd(20000)
  check('注册后触发重连', ended, `end=${player.ended}`)
  player.quit()
  await sleep(SETTLE)

  // 3) 重连后 profile 应被重写为账号名
  const player2 = new Session(name)
  await player2.waitSpawn()
  check(`profile 重写为 ${ACCOUNT}`, player2.profileName === ACCOUNT, `实际=${player2.profileName}`)
  check('已认证玩家进入主服 paper12111', lastServerFor(ACCOUNT) === 'paper12111', `实际=${lastServerFor(ACCOUNT)}`)
  player2.quit()

  bot.close()

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
